#!/bin/bash
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
echo "=========================================================="
echo "    AynaCast TV — Release Paketleme Motoru (macOS)       "
echo "    (Universal APK, ARM64, ARM7, x64, AAB Bundle)        "
echo "=========================================================="
echo ""

result=0
bash "$SCRIPT_DIR/package_tv.sh" release "$@" || result=$?

echo ""
if [[ "$result" = 0 ]]; then
    echo "✅ Başarılı! Paketler hazır: $SCRIPT_DIR/dist"
    # Open Finder at the output directory
    open "$SCRIPT_DIR/dist" 2>/dev/null || true
else
    echo "❌ Paketleme başarısız oldu (Hata kodu: $result)."
fi

echo ""
if [[ -t 0 ]]; then
    read -r -p "Pencereyi kapatmak için Enter tuşuna basın..."
fi
exit "$result"
