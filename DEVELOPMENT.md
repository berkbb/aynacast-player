# Development Guide — AirPlay TV Receiver

This document details the engineering specifications, build requirements, NDK architecture, and testing guidelines for the **AirPlay TV Receiver** project.

---

## 1. Prerequisites & Environment Setup

To compile and debug this project, the following toolchain is required:

- **JDK:** OpenJDK 21 LTS (e.g. Azul Zulu 21.0.12+)
- **Android SDK:** Compile SDK 35 / Target SDK 35 (Min SDK 26)
- **Android NDK:** NDK r30 (`30.0.16248370` or higher)
- **CMake:** Version 4.1.2 or higher
- **Android Gradle Plugin (AGP):** 8.6.1+
- **Kotlin:** 2.0.21+
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
│   │   ├── airplay_crypto.h / .cpp       # Curve25519 & FairPlay handshake simulator
│   │   ├── raop_rtsp_server.h / .cpp     # RTSP port 7000 listener and /info plist responder
│   │   ├── h264_stream_parser.h / .cpp   # AVCC-to-Annex B demuxer & SPS/PPS extractor
│   │   └── airplay_jni.cpp               # JNI bridge exposing callbacks to Kotlin
│   │
│   ├── src/main/java/com/airplay/tv/
│   │   ├── engine/
│   │   │   ├── AirPlayNativeBridge.kt    # JNI interface and listener signatures
│   │   │   ├── VideoDecoderEngine.kt     # Low-latency MediaCodec AVC decoder (KEY_LOW_LATENCY)
│   │   │   └── AudioPlaybackEngine.kt    # AudioTrack PCM engine with transient AudioFocus
│   │   ├── service/
│   │   │   ├── AirPlayDaemonService.kt   # Foreground daemon, mDNS broadcast & network monitor
│   │   │   └── BootReceiver.kt           # Autostart daemon on ACTION_BOOT_COMPLETED
│   │   ├── session/
│   │   │   └── AirPlaySessionManager.kt  # Reactive StateFlow session metrics & coordinator
│   │   ├── settings/
│   │   │   └── AppSettingsRepository.kt  # Persistent language and theme storage
│   │   ├── ui/
│   │   │   ├── AirPlayPlayerActivity.kt  # Full-screen SurfaceView on-demand playback activity
│   │   │   ├── AirPlaySettingsActivity.kt# Settings & About tabs with TV D-Pad focus controls
│   │   │   ├── localization/
│   │   │   │   └── AirPlayStrings.kt     # Typed bilingual catalog (Turkish default, English secondary)
│   │   │   └── theme/                    # Material 3 TV themes (System, Dark, Light)
│   │   └── util/
│   │       ├── DeviceIdentityHelper.kt   # Dynamic TV system name & MAC address resolution
│   │       ├── AndroidDeviceType.kt      # Television form-factor detector
│   │       └── NotificationPermission.kt # Android 13+ POST_NOTIFICATIONS handler
│   │
│   └── src/test/java/com/airplay/tv/     # Unit tests verifying bilingual parity and models
│       ├── AirPlayStringsTest.kt
│       ├── AppSettingsTest.kt
│       └── DeviceIdentityHelperTest.kt
```

---

## 3. Native C++ Engine & Network Protocols

### A. mDNS / Bonjour Advertising
The application advertises two DNS-SD service types on port 7000 via Android's `NsdManager`:
1. `_airplay._tcp`: AirPlay video and screen mirroring service.
   - `model=AppleTV3,2`
   - `features=0x5A7FFFF7,0x1E`
   - `srcvers=220.68`
   - `deviceid=<Hardware MAC>`
2. `_raop._tcp`: Remote Audio Output Protocol service.
   - Service name: `<MAC_NO_COLONS>@<TV_DEVICE_NAME>`
   - `sr=44100`, `ss=16`, `ch=2`, `tp=UDP`

### B. RTSP Handshake Flow
When an Apple client connects to TCP port 7000, the C++ engine processes:
- `OPTIONS * RTSP/1.0`: Declares supported methods (`ANNOUNCE`, `SETUP`, `RECORD`, `TEARDOWN`, `OPTIONS`, `GET_PARAMETER`, `SET_PARAMETER`, `POST`, `GET`).
- `GET /info`: Returns Apple-compatible XML property list defining receiver model and features.
- `POST /pair-setup` and `POST /pair-verify`: Negotiates Curve25519 keys and session parameters.
- `POST /fp-setup`: Simulates FairPlay key derivation phase 1/2.
- `SETUP rtsp://...`: Configures UDP transport ports (Video: 7100, Audio: 6000).
- `RECORD`: Triggers `onStreamStarted()` JNI callback which brings `AirPlayPlayerActivity` to the foreground.
- `TEARDOWN`: Shuts down the stream and calls `finish()` to return the TV to previous content.

### C. Video Pipeline
- Input: H.264 NAL units (AVCC length-prefixed or raw stream).
- Parsing: `h264_stream_parser.cpp` inspects NAL headers, extracts SPS (`type 7`) and PPS (`type 8`), and wraps payloads in standard Annex B start codes (`0x00000001`).
- Hardware Decoding: `VideoDecoderEngine.kt` injects SPS/PPS as `csd-0` and `csd-1` into Android `MediaCodec` configured with `KEY_LOW_LATENCY = 1` and `KEY_PRIORITY = 0`. Buffers are rendered directly to `SurfaceView`.

---

## 4. Localization Standards (1:1 Parity)

This project strictly enforces **Turkish as the Default Language** and **English as the Secondary Language**.

When adding a new user-facing string:
1. Add a method in [`AirPlayStrings.kt`](app/src/main/java/com/airplay/tv/ui/localization/AirPlayStrings.kt) with explicit branches for `AppLanguage.TR` and `AppLanguage.EN`.
2. Add corresponding XML entries in:
   - `app/src/main/res/values/strings.xml` (Turkish)
   - `app/src/main/res/values-en/strings.xml` (English)
3. Run `./gradlew test`. The `AirPlayStringsTest` will automatically fail if:
   - Any key is missing from either language.
   - Any key returns a blank or empty string.
   - Any XML resource is not mirrored 1:1 between `values/` and `values-en/`.

---

## 5. Testing & Validation

Run the test suite using Gradle:
```bash
./gradlew test
```

To run static analysis and lint vital checks:
```bash
./gradlew :app:lintVitalRelease
```

---

## 6. Packaging & Release Builds

The automated release workflow compiles both universal and split APKs alongside an AAB bundle:

```bash
# Terminal release package
./package_tv.sh release

# Or double-click in macOS Finder
./AirPlayTV-Package.command
```

Output directory: `dist/`
- `AirPlayTV-v1.0.0-universal.apk` (All architectures)
- `AirPlayTV-v1.0.0-arm64-v8a.apk` (ARM64 only)
- `AirPlayTV-v1.0.0-armeabi-v7a.apk` (ARM7 only)
- `AirPlayTV-v1.0.0-x86_64.apk` (x86_64 only)
- `AirPlayTV-v1.0.0-release.aab` (Google Play Bundle)
