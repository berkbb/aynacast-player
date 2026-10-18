# Development Guide — AynaCast TV Receiver

This document details the engineering specifications, build requirements, NDK architecture, and testing guidelines for the **AynaCast TV Receiver** (`com.babadogan.aynacast.tv`) project.

---

## 1. Prerequisites & Environment Setup

To compile and debug this project, the following toolchain is required:

- **JDK:** OpenJDK 21 LTS (e.g. Azul Zulu 21.0.12+)
- **Android SDK:** Compile SDK 37.2 (Android 17) / Target SDK 35 (Min SDK 26)
- **Android NDK:** NDK r30 (`30.0.16248370` or higher)
- **CMake:** Version 4.1.2 or higher
- **Android Gradle Plugin (AGP):** 9.4.1 (built-in Kotlin) with Gradle 9.7.1
- **Kotlin:** 2.2.10 (bundled with AGP 9.4.1; the Compose compiler plugin version must match)
- **Jetpack Compose Compiler:** Integrated Kotlin Compose Plugin

### Local Configuration (`local.properties`)
Ensure your `local.properties` specifies the SDK directory:
```properties
sdk.dir=/Users/berkbabadogan/SDK/Android
```
AGP automatically resolves side-by-side NDK versions from `<sdk.dir>/ndk/<ndkVersion>`.

---

## 2. Architecture & Code Graph

The codebase is organized into modular layers:

```text
jetpack_compose_airplay/
├── app/
│   ├── src/main/cpp/                     # Native C/C++ Engine (libairplaycore.so)
│   │   ├── CMakeLists.txt                # CMake 4.x NDK build configuration
│   │   ├── airplay_protocol.h            # Data structures, frame packets, and state models
│   │   ├── airplay_crypto.h / .cpp       # Ed25519, Curve25519 ECDH & pair-verify cryptographic engine
│   │   ├── tweetnacl.c / .h              # Self-contained public domain crypto primitives
│   │   ├── aes.c / .h                    # AES-128-CTR stream encryption
│   │   ├── raop_rtsp_server.h / .cpp     # RTSP port 7000 listener, dynamic /info plist, TCP 7100 video server, UDP 7011 audio
│   │   ├── bplist_reader.h / .cpp        # Bounds-checked binary plist reader (+ txtAirPlay reply writer)
│   │   ├── raop_ntp.h / .cpp             # NTP timing client: sender clock -> local monotonic time
│   │   ├── video_cast.h / .cpp           # AirPlay video casting (URL / YouTube HLS via FCUP)
│   │   ├── dmap_parser.h / .cpp          # Now-playing DMAP metadata parser
│   │   ├── audio_rtp_buffer.h / .cpp     # Audio RTP reorder buffer with retransmission requests
│   │   ├── alac.c / .h                   # ALAC decoder for audio-only streaming (hardened against corrupt frames)
│   │   ├── h264_stream_parser.h / .cpp   # AVCC-to-Annex B demuxer & SPS/PPS extractor
│   │   └── airplay_jni.cpp               # JNI bridge exposing callbacks to Kotlin
│   │
│   ├── src/main/java/com/babadogan/aynacast/tv/
│   │   ├── engine/
│   │   │   ├── AirPlayNativeBridge.kt    # JNI interface and listener signatures
│   │   │   ├── VideoDecoderEngine.kt     # Low-latency MediaCodec AVC decoder (KEY_LOW_LATENCY, GOP replay)
│   │   │   ├── H264SpsParser.kt          # SPS resolution parser (pure Kotlin, unit tested)
│   │   │   ├── AudioPlaybackEngine.kt    # AAC-ELD / AAC-LC MediaCodec decoding, routes PCM to the output
│   │   │   ├── PcmJitterOutput.kt        # Jitter buffer + writer thread feeding a small AudioTrack
│   │   │   ├── AirPortRsa.kt             # Classic AirPlay RSA (AirPort Express key): challenge, rsaaeskey
│   │   │   ├── AirPlayFeatures.kt        # Advertised AirPlay feature bits
│   │   │   ├── AirPlayAudioFormat.kt     # AudioSpecificConfig builder & volume mapping (unit tested)
│   │   │   └── AvSync.kt                 # Optional timestamp A/V sync (UxPlay -vsync equivalent)
│   │   ├── service/
│   │   │   ├── AirPlayDaemonService.kt   # Foreground daemon, mDNS broadcast & network monitor
│   │   │   └── BootReceiver.kt           # Autostart daemon on ACTION_BOOT_COMPLETED
│   │   ├── session/
│   │   │   └── AirPlaySessionManager.kt  # Reactive StateFlow session metrics & coordinator
│   │   ├── settings/
│   │   │   └── AppSettingsRepository.kt  # Persistent settings (language, theme, PIN, sync, visibility refresh, ...)
│   │   ├── ui/
│   │   │   ├── AirPlayPlayerActivity.kt  # Full-screen SurfaceView on-demand playback activity
│   │   │   ├── AirPlaySettingsActivity.kt# Home screen (status) plus the Settings and About windows, TV D-Pad focus
│   │   │   ├── TvFullScreenWindow.kt     # Full-screen window with a top-right close button; Back closes it
│   │   │   ├── PairedDevicesDialog.kt    # Paired devices as icon cards (icon from the reported model, music note for Mac Music)
│   │   │   ├── OverlayPermissionDialog.kt# "Display over other apps" prompt on launch (Android 10+)
│   │   │   ├── localization/
│   │   │   │   └── AirPlayStrings.kt     # Typed catalog: Turkish (default), English, Romanian
│   │   │   └── theme/                    # Material 3 TV themes (System, Dark, Light)
│   │   └── util/
│   │       ├── DeviceIdentityHelper.kt   # Dynamic TV system name & MAC address resolution
│   │       ├── DnsTxtRecord.kt           # DNS-SD TXT wire format (shared by mDNS and GET /info)
│   │       ├── AndroidDeviceType.kt      # Television form-factor detector
│   │       └── NotificationPermission.kt # Android 13+ POST_NOTIFICATIONS handler
│   │
│   └── src/test/java/com/babadogan/aynacast/tv/ # Unit tests verifying bilingual parity and models
│       ├── AirPlayStringsTest.kt
│       ├── AirPortRsaTest.kt
│       ├── AirPlayAudioFormatTest.kt
│       ├── AppSettingsTest.kt
│       ├── DeviceIdentityHelperTest.kt
│       └── H264SpsParserTest.kt
│
└── tools/native-host-tests/               # Host (macOS/Linux) socket-level tests for the C++ core
```

---

## 3. Native C++ Engine & Network Protocols

### A. mDNS / Bonjour Advertising
The application advertises two DNS-SD service types on port 7000 via Android's `NsdManager`:
1. `_airplay._tcp`: AirPlay video and screen mirroring service.
   - `model=AppleTV5,3`
   - `features=0x5A7FFFF7,0x1E` (screen mirroring + audio + URL video playback bits 0 and 4 for video casting — see `AirPlayFeatures.kt`)
   - `srcvers=220.68`
   - `deviceid=<Hardware MAC>`
2. `_raop._tcp`: Remote Audio Output Protocol service.
   - Service name: `<MAC_NO_COLONS>@<TV_DEVICE_NAME>`
   - `sr=44100`, `ss=16`, `ch=2`, `tp=UDP`, `et=0,3,5`
3. `_raop._tcp` classic speaker for the macOS Music app: `<MAC with the last bit flipped>@<TV_DEVICE_NAME> (Mac)`,
   `et=0,1 ek=1` (RSA only), `pw=true` when the PIN is on. With the records above the Music app only offers FairPlay 2;
   its own MAC keeps macOS from merging it into the main receiver.

With "Refresh Visibility" on (default), the records are re-registered every 60 s while nothing is streaming, for TVs
whose Wi-Fi drops incoming multicast (mdnsd can then announce but never answer queries).

### B. RTSP Handshake Flow
When an Apple client connects to TCP port 7000, the C++ engine processes:
- `OPTIONS * RTSP/1.0`: Declares supported methods (`ANNOUNCE`, `SETUP`, `RECORD`, `TEARDOWN`, `OPTIONS`, `GET_PARAMETER`, `SET_PARAMETER`, `POST`, `GET`).
- `GET /info`: Returns Apple-compatible XML property list defining receiver model and features.
- `POST /pair-setup` and `POST /pair-verify`: Negotiates Curve25519 keys and session parameters. In step 2 the client's Ed25519 signature over (client ECDH key ‖ server ECDH key) is verified after skipping the 64-byte AES-CTR keystream used in step 1; a bad signature closes the connection (as in UxPlay).
- `GET /info` with the binary-plist qualifier `txtAirPlay` / `txtRAOP` returns only that TXT record; unknown RTSP methods get `501 Not Implemented`.
- Timing: after the session `SETUP` the receiver queries the sender's `timingPort` with NTP requests (every 250 ms for the first 8, then every 3 s) and maps sender timestamps (video header bytes 8–15, audio RTCP sync packets) onto the local `CLOCK_MONOTONIC`/`System.nanoTime()` timeline.
- Video casting (HTTP/1.1 on port 7000, `video_cast.cpp`): `/server-info`, `/reverse` (PTTH event channel), `/play`, `/scrub`, `/rate`, `/stop`, `/playback-info`, `/setProperty`, `/action`. Direct URLs go to the player; YouTube `mlhls://localhost/master.m3u8` playlists are requested from the sender with FCUP events (replies are matched by `FCUP_Response_RequestID`; `FCUP_Response_StatusCode` is 0 on success and is not checked), rewritten to `http://localhost:7000/...` and served to Media3/ExoPlayer. A stop on the TV sends a `stopped` event and closes the casting connections.
- PIN pairing (`/pair-pin-start`, `/pair-setup-pin`): SRP-6a (SHA-1, RFC 5054 2048-bit group, Apple's 40-byte session key) and AES-128-GCM key exchange in `PinPairingServer.kt`; in PIN mode pair-verify only admits remembered devices.
- Now playing: `SET_PARAMETER` with `application/x-dmap-tagged` (title/artist/album), `image/jpeg|png` (cover art) and `progress:` feeds the Now Playing screen.
- Audio RTP: packets are reordered in a 256-slot buffer; a gap triggers one retransmission request (`0x80 0xD5`) to the sender's control port, retransmissions (`0x56`) fill it, and a missing run is skipped after 60 ms so playback never stalls.
- `POST /fp-setup`: Simulates FairPlay key derivation phase 1/2 (the phase-2 mode byte must be 0–3). Only FairPlay version 3 is implemented; the macOS Music app's version 2 is rejected (logged with its version byte).
- Classic AirPlay (the "(Mac)" entry): any request with `Apple-Challenge` gets an `Apple-Response` (RSA PKCS#1 signature over challenge + receiver IP + MAC, padded to 32 bytes, done in Kotlin `AirPortRsa` via JNI); `ANNOUNCE` carries an SDP (`a=rtpmap:96 AppleLossless`, `a=fmtp`, `a=rsaaeskey` RSA-OAEP, `a=aesiv`); `SETUP` with a `Transport` header (sender control/timing ports) is answered with the receiver's ports and starts NTP and ALAC; the audio key is used as is (no pair-verify hashing). With the PIN on, `ANNOUNCE` gets `401` with an HTTP Digest challenge (realm `raop`) and `PinPairingManager.checkClassicDigest` verifies the on-screen code. Classic senders send no `/feedback`, so their session ends after 120 s without requests or audio.
- Handshake state (pair-verify ECDH secret and signature keys, FairPlay key message) is kept per RTSP connection and forgotten when it closes; the session keys are derived from the handshake of the connection that sends the session `SETUP`.
- Session ownership: the connection that sends the session `SETUP` (the one carrying `ekey`/`timingPort`) owns the session. Other connections get `409 Conflict` for `SETUP` streams, `RECORD`, `TEARDOWN`, `SET_PARAMETER`, `GET_PARAMETER`, `FLUSH` and `PAUSE`; a new session `SETUP` from another connection takes the session over and shuts the old connection down (Apple TV / UxPlay `-nohold` behaviour). A connection that sends a stream `SETUP` or `RECORD` while no session exists becomes the owner. With PIN pairing on, every `SETUP` and `RECORD` requires a completed pair-verify on that connection (`470` otherwise). The mirroring video port only accepts a connection from the IP of an established session.
- Limits: 32 open connections, 8 MB request bodies, idle connections closed after 120 s unless they own the session, belong to video casting or come from the session sender's IP; binary plists are parsed with an object-visit cap and a copy budget (4× the document + 1 MB).
- Identity: the Ed25519 key is derived from a 32-byte seed stored by `ServerIdentityStore` (`setIdentitySeed` before `initNativeServer`), so `pk` is stable across restarts.
- `SETUP rtsp://...`: Binary-plist SETUP. Session setup (`ekey`/`eiv`) and stream type 110 answer with the video TCP data port 7100; stream type 96 answers with audio UDP data/control ports 7011/7012. The audio codec is taken from `ct` (2 = ALAC decoded natively, 4 = AAC-LC, 8 = AAC-ELD decoded by MediaCodec).
- `RECORD`: Starts the session (`onStreamStarted()`). The player activity opens when mirrored video actually starts (first SPS/PPS), so audio-only AirPlay keeps the current TV app on screen.
- `TEARDOWN` (UxPlay semantics): stream type 96 stops audio, stream type 110 stops the mirrored video (the player closes, the session and audio may continue), and a body without streams ends the session; the player then calls `finish()` to return the TV to previous content. If the RTSP connection that sent `RECORD` closes without `TEARDOWN` (Wi-Fi loss, sender sleep), or sends no request (`POST /feedback` arrives every ~2 s) for 15 s, the session is ended the same way.

### C. Video Pipeline
- Input: H.264 NAL units (AVCC length-prefixed or raw stream).
- Parsing: `h264_stream_parser.cpp` inspects NAL headers, extracts SPS (`type 7`) and PPS (`type 8`), and wraps payloads in standard Annex B start codes (`0x00000001`).
- Hardware Decoding: `VideoDecoderEngine.kt` injects SPS/PPS as `csd-0` and `csd-1` into Android `MediaCodec` with `KEY_PRIORITY = 0` and vendor low-latency keys; `KEY_LOW_LATENCY` only when the decoder advertises `FEATURE_LowLatency` (on Android 11 a decoder that rejects it fails `configure()`). A failing hardware decoder is retried once without the optional keys before the software fallback. Frames go through `GlVideoRenderer` (letterboxing) to the `SurfaceView`.

### D. Audio Output
Decoded PCM goes to `PcmJitterOutput`: the network thread appends to a queue, a writer thread feeds a small `AudioTrack` with blocking writes (the TV's clock paces playback). The queue target adapts (+40 ms per refill, −10 ms per quiet 30 s; mirroring 60–400 ms starting at 120 ms, music 400–800 ms), drift is corrected one sample frame per 10 ms chunk, extra latency from refill pauses is trimmed after 2 s, and a stalled output is rebuilt. Mirroring audio in timestamp-sync mode still uses the direct `AudioTrack` path in `AudioPlaybackEngine`.

---

## 4. Localization Standards (1:1 Parity)

This project uses **Turkish as the Default Language**, with **English** and **Romanian** alongside.

When adding a new user-facing string:
1. Add a method in [`AirPlayStrings.kt`](app/src/main/java/com/babadogan/aynacast/tv/ui/localization/AirPlayStrings.kt) with an exhaustive `when (lang)` (`TR`, `EN`, `RO`); no `if (lang == TR) … else …` shortcuts, so the compiler flags a missing language.
2. Add corresponding XML entries in:
   - `app/src/main/res/values/strings.xml` (Turkish)
   - `app/src/main/res/values-en/strings.xml` (English)
   - `app/src/main/res/values-ro/strings.xml` (Romanian)
3. Run `./gradlew test`. The `AirPlayStringsTest` will automatically fail if:
   - Any key returns a blank or empty string.
   - Any XML resource is not mirrored 1:1 between `values/`, `values-en/` and `values-ro/`.

---

## 5. Testing & Validation

Run the test suite using Gradle:
```bash
./gradlew test
```

Native AirPlay core (C++) regression tests run on the development machine with AddressSanitizer/UBSan.
They build the core for the host and drive it over real sockets like an Apple sender (split and pipelined
RTSP requests, SETUP/TEARDOWN plists, AAC/ALAC audio routing, dropped connections, shutdown with live clients,
session ownership and takeover, PIN gate, video casting / FCUP ordering, classic AirPlay RSA/SDP/Transport
and its Digest PIN, hostile plists, connection limits):
```bash
./tools/native-host-tests/run.sh
```
Requires `clang`, `clang++` and `python3`; uses TCP ports 17000/17100 and UDP 7011/7012.

To run static analysis and lint vital checks:
```bash
./gradlew :app:lintVitalRelease
```

---

## 6. Packaging & Release Builds

The automated release workflow compiles both universal and split APKs:

```bash
# Terminal release package
./package_tv.sh release

# Or double-click in macOS Finder
./AynaCastTV-Package.command
```

Output directory: `dist/`
- `AynaCastTV-v1.5.1-universal-release.apk` (All architectures)
- `AynaCastTV-v1.5.1-arm64-v8a-release.apk` (ARM64 only)
- `AynaCastTV-v1.5.1-armeabi-v7a-release.apk` (ARM7 only)
- `AynaCastTV-v1.5.1-x86_64-release.apk` (x86_64 only)
