#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ -d "/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home" ]]; then
        export JAVA_HOME="/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home"
    fi
fi

MODE="${1:-release}"
VERSION="1.0.0"
DIST_DIR="$SCRIPT_DIR/dist"

echo "=========================================================="
echo "    AirPlay TV Paketleme / Derleme Motoru                "
echo "    Hedef Mod: $MODE  |  Versiyon: v$VERSION            "
echo "=========================================================="

case "$MODE" in
    debug)
        ./gradlew :app:assembleDebug
        mkdir -p "$DIST_DIR"
        cp -f app/build/outputs/apk/debug/*.apk "$DIST_DIR/" 2>/dev/null || true
        echo ""
        echo "✅ Debug paketleri oluşturuldu: $DIST_DIR"
        ;;

    release)
        echo "📦 Release APK'lar (Universal, ARM64, ARM7, x64) ve AAB paketi derleniyor..."
        ./gradlew :app:assembleRelease :app:bundleRelease

        mkdir -p "$DIST_DIR"
        rm -rf "$DIST_DIR"/*

        # 1. Universal APK
        if compgen -G "app/build/outputs/apk/release/*universal*.apk" > /dev/null; then
            cp app/build/outputs/apk/release/*universal*.apk "$DIST_DIR/AirPlayTV-v$VERSION-universal.apk"
        fi

        # 2. ARM64 (arm64-v8a)
        if compgen -G "app/build/outputs/apk/release/*arm64-v8a*.apk" > /dev/null; then
            cp app/build/outputs/apk/release/*arm64-v8a*.apk "$DIST_DIR/AirPlayTV-v$VERSION-arm64-v8a.apk"
        fi

        # 3. ARMv7 (armeabi-v7a)
        if compgen -G "app/build/outputs/apk/release/*armeabi-v7a*.apk" > /dev/null; then
            cp app/build/outputs/apk/release/*armeabi-v7a*.apk "$DIST_DIR/AirPlayTV-v$VERSION-armeabi-v7a.apk"
        fi

        # 4. x86_64
        if compgen -G "app/build/outputs/apk/release/*x86_64*.apk" > /dev/null; then
            cp app/build/outputs/apk/release/*x86_64*.apk "$DIST_DIR/AirPlayTV-v$VERSION-x86_64.apk"
        fi

        # 5. Android App Bundle (AAB)
        if compgen -G "app/build/outputs/bundle/release/*.aab" > /dev/null; then
            cp app/build/outputs/bundle/release/*.aab "$DIST_DIR/AirPlayTV-v$VERSION-release.aab"
        fi

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
