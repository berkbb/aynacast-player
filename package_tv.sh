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
