#!/usr/bin/env bash
# Builds the native AirPlay core for the host with AddressSanitizer/UBSan and drives it over real
# sockets like an Apple sender would (RTSP framing, SETUP/TEARDOWN, audio routing, shutdown).
# Requirements: clang/clang++ and python3 with the `cryptography` package (independent pair-verify client).
# Uses TCP 17000/17100 and UDP 7010/7011/7012.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/../../app/src/main/cpp"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

CC="${CC:-clang}"
CXX="${CXX:-clang++}"
PYTHON="${PYTHON:-python3}"
SAN="-fsanitize=address,undefined -g -O1"
# Shorter feedback watchdog and idle timeout so those tests run quickly.
DEFS="-DAIRPLAY_FEEDBACK_TIMEOUT_SEC=4 -DAIRPLAY_IDLE_TIMEOUT_SEC=5"

for f in tweetnacl.c aes.c alac.c playfair/hand_garble.c playfair/modified_md5.c playfair/omg_hax.c playfair/playfair.c playfair/sap_hash.c; do
    "$CC" -c $SAN -w -I"$HERE/stub" -I"$SRC" "$SRC/$f" -o "$OUT/$(basename "$f" .c).o"
done
for f in airplay_crypto.cpp audio_rtp_buffer.cpp bplist_reader.cpp dmap_parser.cpp raop_ntp.cpp raop_rtsp_server.cpp video_cast.cpp h264_stream_parser.cpp; do
    "$CXX" -std=c++17 -c $SAN $DEFS -Wall -Wextra -I"$HERE/stub" -I"$SRC" "$SRC/$f" -o "$OUT/$(basename "$f" .cpp).o"
done
"$CXX" -std=c++17 $SAN -I"$HERE/stub" -I"$SRC" "$HERE/harness.cpp" "$OUT"/*.o -o "$OUT/harness"

HARNESS="$OUT/harness" SERVER_LOG="$OUT/server.log" "$PYTHON" "$HERE/driver.py"
