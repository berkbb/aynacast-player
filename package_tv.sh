#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ -d "/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home" ]]; then
        export JAVA_HOME="/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home"
    fi
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
    if [[ -d "/Users/berkbabadogan/SDK/Android" ]]; then
        export ANDROID_HOME="/Users/berkbabadogan/SDK/Android"
    elif [[ -d "$HOME/SDK/Android" ]]; then
        export ANDROID_HOME="$HOME/SDK/Android"
    elif [[ -d "$HOME/Library/Android/sdk" ]]; then
        export ANDROID_HOME="$HOME/Library/Android/sdk"
    fi
fi

MODE="${1:-release}"
# Single source of truth: versionName in app/build.gradle.kts
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -n 1)"
VERSION="${VERSION:-0.0.0}"
DIST_DIR="$SCRIPT_DIR/dist"

# Signing check: installed apps (and their in-app update) only accept a new version signed with the
# same key. release-cert.sha256 holds the expected certificate; a mismatch is reported, not fatal.
check_release_signing() {
    local apk="$1"
    local expected actual apksigner sdk
    expected="$(tr -d '[:space:]' < release-cert.sha256 2>/dev/null || true)"
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/SDK/Android}}"
    apksigner="$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1)"
    if [[ -z "$apksigner" || -z "$expected" ]]; then
        echo "⚠️  Signing not verified (apksigner or release-cert.sha256 missing)."
        return 0
    fi
    actual="$("$apksigner" verify --print-certs "$apk" 2>/dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | head -n 1)"
    if [[ "$actual" == "$expected" ]]; then
        echo "🔏 Signing key matches release-cert.sha256 (${actual:0:16}…)."
        return 0
    fi
    cat <<WARN

⚠️⚠️⚠️  SIGNING KEY MISMATCH / İMZA ANAHTARI UYUŞMUYOR  ⚠️⚠️⚠️
   Expected / Beklenen : $expected
   This APK / Bu APK   : ${actual:-unknown}
   Devices that have the app signed with the expected key CANNOT update to this build, and the
   in-app update fails too. On those devices uninstall the app first, then install this version
   (the app's data and settings are removed).
   Beklenen anahtarla kurulu cihazlar bu sürüme GÜNCELLENEMEZ, uygulama içi güncelleme de çalışmaz.
   O cihazlarda uygulamayı önce kaldırın, sonra bu sürümü yeniden kurun (uygulama verileri silinir).
   To keep the key, build where ~/.signing/babadogan-tv-release.p12 and its Keychain password are set up (README, Release signing).

WARN
}

echo "=========================================================="
echo "    AynaCast TV Paketleme / Derleme Motoru                "
echo "    Hedef Mod: $MODE  |  Versiyon: v$VERSION            "
echo "=========================================================="

case "$MODE" in
    debug)
        echo "📦 Debug APK'lar derleniyor..."
        ./gradlew :app:assembleDebug
        mkdir -p "$DIST_DIR"
        rm -rf "$DIST_DIR"/*
        cp -f app/build/outputs/apk/debug/*.apk "$DIST_DIR/" 2>/dev/null || true
        (cd "$DIST_DIR" && shasum -a 256 *.apk > SHA256SUMS 2>/dev/null || true)
        echo ""
        echo "=========================================================="
        echo "✅ Debug paketleri oluşturuldu ($DIST_DIR):"
        echo "=========================================================="
        ls -lh "$DIST_DIR"
        echo "=========================================================="
        ;;

    release)
        echo "📦 Release APK'lar (Universal, ARM64, ARM7, x64) derleniyor..."
        ./gradlew :app:assembleRelease

        mkdir -p "$DIST_DIR"
        rm -rf "$DIST_DIR"/*

        cp -f app/build/outputs/apk/release/*.apk "$DIST_DIR/" 2>/dev/null || true
        (cd "$DIST_DIR" && shasum -a 256 *.apk > SHA256SUMS)

        check_release_signing "$DIST_DIR/AynaCastTV-v${VERSION}-universal-release.apk"
        echo ""
        echo "=========================================================="
        echo "🎉 Paketleme Tamamlandı! Dağıtım Dosyaları ($DIST_DIR):"
        echo "=========================================================="
        ls -lh "$DIST_DIR"
        echo "=========================================================="
        ;;

    clean)
        ./gradlew clean
        rm -rf "$DIST_DIR"
        echo "Temizlik tamamlandı."
        ;;

    *)
        echo "Kullanım: ./package_tv.sh [release|debug|clean]"
        exit 1
        ;;
esac
