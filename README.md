# AynaCast TV — AirPlay & Screen Mirroring Receiver (Android TV & Google TV)

[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Google%20TV-blue.svg)](https://developer.android.com/tv)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple.svg)](https://kotlinlang.org)
[![Compose TV](https://img.shields.io/badge/Jetpack%20Compose%20for%20TV-1.0.0-green.svg)](https://developer.android.com/jetpack/compose/tv)
[![NDK](https://img.shields.io/badge/NDK-r30-orange.svg)](https://developer.android.com/ndk)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A high-performance, ultra-low-latency, hardware-accelerated **AirPlay Receiver & Screen Mirroring Engine** engineered specifically for **Android TV** and **Google TV**, combining native Turkish ("Ayna" - Mirror) and English ("Cast" - Stream) branding: **AynaCast TV** (`com.babadogan.aynacast.tv`).

Built on a two-tier architecture:
1. **Background Daemon Service (Zero UI Overhead):** A low-footprint foreground daemon constantly listening on the local network (`mDNS / RTSP 7000 / Video TCP 7100`) without occupying RAM with active UI components.
2. **On-Demand Jetpack Compose for TV Interface:** When an Apple device initiates a mirror session, the service instantly launches the playback activity, connects the C++ NDK video stream directly to Android's hardware `MediaCodec`, and terminates automatically when the session teardown occurs—returning the TV to whatever app was previously open.

---

## 🏛️ System Architecture

```text
[ iPhone / iPad / Mac ]
         │
         │  (mDNS Discovery: _airplay._tcp & _raop._tcp / Port 7000)
         │  (RTSP Handshake: OPTIONS, /info, pair-setup, fp-setup, SETUP, RECORD)
         ▼
┌────────────────────────────────────────────────────────────────────────┐
│  Android Foreground Daemon (AirPlayDaemonService)                      │
│  • WifiLock (LOW_LATENCY) & MulticastLock management                   │
│  • Android NsdManager dynamic mDNS Bonjour broadcast                  │
│  • C/C++ NDK Core Engine (libairplaycore.so via CMake & JNI)           │
│  • Network change detection & auto mDNS re-registration                │
│  • AudioFocusRequest (Transient gain with pause for other TV audio)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Stream Started Callback (FLAG_ACTIVITY_NEW_TASK)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│  On-Demand UI (AirPlayPlayerActivity - Jetpack Compose for TV)        │
│  • Automatically pops forward when streaming starts                    │
│  • Hosts SurfaceView connected directly to VideoDecoderEngine          │
│  • Non-intrusive HUD: Device Name, 60 FPS, Resolution badge            │
│  • TV Remote Back button handling (onBackPressedDispatcher)            │
│  • Closes immediately on TEARDOWN returning TV to previous screen      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
    ┌───────────────────────────────┴───────────────────────────────┐
    │                                                               │
    ▼ (Annex-B H.264 NAL Packets)                                   ▼ (44.1 kHz PCM Audio)
┌────────────────────────────────────────┐      ┌────────────────────────────────────────┐
│  Hardware MediaCodec (AVC / H.264)     │      │  Low-Latency AudioTrack                │
│  • SPS / PPS injection (csd-0, csd-1)  │      │  • 44.1 kHz, 16-bit PCM, Stereo       │
│  • KEY_LOW_LATENCY when supported      │      │  • Jitter buffer + writer thread       │
│  • Zero-copy Direct Hardware Render    │      │  • USAGE_MEDIA & AudioFocus transient  │
└────────────────────────────────────────┘      └────────────────────────────────────────┘
```

---

## ✨ Key Features

- **Dynamic Device Identity:** Automatically resolves and broadcasts the TV's real human-readable system name (`Settings.Global.device_name`, Bluetooth name, or Model) rather than a generic hardcoded label.
- **Localization (TR / EN / RO):**
  - **Turkish (Default):** First-class native Turkish user interface and strings.
  - **English and Romanian:** Complete localizations with 1:1 key parity verified by unit tests.
- **Full Theme Mode Support (System / Dark / Light):**
  - **System (Default):** Follows the Android TV system appearance setting.
  - **Dark:** OLED/TV-optimized deep black theme.
  - **Light:** High-contrast crisp light theme.
  - Persisted across restarts via `AppSettingsRepository`.
- **Dual TV Windows:**
  - **Settings Window (`AirPlaySettingsActivity`):** Live service indicator, D-Pad focused controls, appearance selection, language picker, and instructions.
  - **About Window:** Application version, architecture overview, supported network protocols, developer attribution, and legal notice.
- **TV D-Pad Remote Focus:** Animated high-contrast borders and color shifts when navigating with a television remote control.
- **Mirrored Audio:** AAC-ELD (screen mirroring) and AAC-LC audio decoded with `MediaCodec`, ALAC decoded natively; sender volume is applied. Out-of-order packets are reordered and lost ones are requested again from the sender. A jitter buffer with its own writer thread absorbs Wi-Fi bursts and clock drift (≈0.35 s for mirroring, deeper for music).
- **macOS Music app:** besides the main receiver the TV advertises a classic AirPlay speaker, **"<TV name> (Mac)"**, which the Music app streams to with RSA (the main receiver only gets FairPlay 2 from it, which cannot be implemented).
- **UxPlay-compatible protocol behaviour:** NTP timing with the sender, verified pair-verify signatures, `txtAirPlay` info replies, per-stream `TEARDOWN`, and an optional timestamp audio/video sync (UxPlay `-vsync`; off by default for the lowest latency, as UxPlay recommends for macOS mirroring and hardware decoding).
- **Audio-only AirPlay:** Music streamed to the TV shows an Apple TV-like Now Playing screen (cover art, title, artist, progress); Back keeps it playing in the background.
- **Video casting:** An app's AirPlay button (Safari, YouTube and most video apps) can send its video to the TV instead of mirroring the screen; it plays in a Media3/ExoPlayer player controlled from the iPhone or the TV remote. DRM-protected services (Netflix, Apple TV+, Disney+) are not supported by design.
- **On-screen PIN (optional):** A 4-digit code can be required the first time a device connects; paired devices are remembered. A Mac using the "(Mac)" entry types the code into its password prompt.
- **Auto-Open on Android 10+:** The app asks for the "Display over other apps" permission that Android 10+ requires for the player to open by itself; without it a toast over the current app shows the PIN code or says to open AynaCast TV.
- **Refresh Visibility:** re-announces the receiver every minute while idle, for TVs whose Wi-Fi drops incoming multicast (otherwise a phone that rejoined Wi-Fi cannot find them).
- **Hardware decoder first:** low-latency keys are only requested from decoders that support them, a failing hardware decoder is retried before the software fallback, and the player shows `HW` / `SW`.
- **Zero-Warning Clean Build:** Fully compliant with Android 14/15/16 SDK guidelines, deprecation-free modern APIs (`BluetoothManager`, `WIFI_MODE_FULL_LOW_LATENCY`, `POST_NOTIFICATIONS`).

---

## 📦 Distribution Packages (`dist/`)

The automated packaging engine produces both universal and split-architecture builds:

| Package | Target | Size | Description |
| :--- | :--- | :---: | :--- |
| `AynaCastTV-v1.3.0-universal.apk` | Universal | ~12 MB | Single APK containing `arm64-v8a`, `armeabi-v7a`, and `x86_64`. |
| `AynaCastTV-v1.3.0-arm64-v8a.apk` | 64-bit ARM | ~9.5 MB | Optimized for modern 64-bit Android TV & Google TV devices. |
| `AynaCastTV-v1.3.0-armeabi-v7a.apk` | 32-bit ARM | ~9.0 MB | Optimized for legacy 32-bit Android TV boxes and sticks. |
| `AynaCastTV-v1.3.0-x86_64.apk` | 64-bit x86 | ~9.5 MB | Optimized for Android TV emulators and Intel/AMD hardware. |
| `AynaCastTV-v1.3.0-release.aab` | Google Play | ~11 MB | Official Android App Bundle for Google Play Console distribution. |

---

## 🛠️ Build & Package Instructions

### 1. Using macOS Finder (One-Click)
Double-click [`AynaCastTV-Package.command`](AynaCastTV-Package.command) in the project root. It will compile all release APKs and the AAB bundle, and automatically open the `dist/` folder when finished.

### 2. Using Terminal Command
```bash
# Build all release packages (Universal, ARM64, ARM7, x64, AAB)
./package_tv.sh release

# Build debug package
./package_tv.sh debug

# Run all unit tests
./gradlew test
```

### 3. Using Android Studio
1. Open Android Studio and choose **Open Project**.
2. Select the cloned `jetpack_compose_airplay` project folder.
3. Click **Sync Project with Gradle Files**.
4. Select your Android TV device/emulator and click **Run 'app'**.

---

## 🧪 Unit Tests

This project includes verified unit tests ensuring zero regressions:
- **`AirPlayStringsTest`:** Validates non-blank strings, language distinctiveness, and 1:1 XML resource matching between Turkish, English and Romanian.
- **`AirPortRsaTest`:** Classic AirPlay RSA: OAEP key unwrapping against the JVM's OAEP and challenge signatures checked with the public key.
- **`AppSettingsTest`:** Validates default language (Turkish), default theme (System), and parsing rules.
- **`DeviceIdentityHelperTest`:** Validates MAC formatting, placeholder-MAC rejection, derived device IDs and mDNS name length limits.
- **`AirPlayAudioFormatTest`:** Validates AAC-ELD / AAC-LC AudioSpecificConfig bytes and AirPlay volume mapping.
- **`H264SpsParserTest`:** Validates SPS resolution parsing (cropping, emulation-prevention bytes).
- **`AvSyncTest`, `PinPairingServerTest`, `AirPlayFeaturesTest`, `DnsTxtRecordTest`:** A/V sync latency rules, SRP/GCM PIN pairing against independent vectors, advertised feature bits and TXT encoding.
- **`VideoCastSessionTest`:** Video casting state: a closing player cannot stop or report for the cast that replaced it, and sender seeks/rates sent before the player exists are kept.
- **Native host tests (`tools/native-host-tests/run.sh`):** Drive the C++ AirPlay core over real sockets under AddressSanitizer/UBSan (154 checks: protocol, audio/video, video casting, PIN pairing, classic AirPlay (RSA / Digest PIN), session ownership, hostile input).

Run all tests via:
```bash
./gradlew test
```

---

## ⚠️ Known Issues

- **Two entries on the Mac:** the macOS Music app cannot use the main receiver (it only offers FairPlay 2 there), so the TV also appears as **"<TV name> (Mac)"**; pick that one in the Music app. iPhones list it as an extra speaker.
- **Mac PIN:** a Mac types the on-screen code as a password and is then remembered by its `DACP-ID`; this is weaker than the iPhone's SRP pairing (another LAN host could reuse a seen ID).
- **Some TVs do not hear discovery queries** (Wi-Fi drops incoming multicast; seen on an Arçelik / MStar M7322, Android 11). Keep "Refresh Visibility" on: a phone that rejoined Wi-Fi then finds the TV within about a minute. Turning the TV's Wi-Fi off and on can also help.
- **Safari videos while mirroring:** with screen mirroring on, iOS may end a Safari video cast after a few seconds and go back to mirroring; stop mirroring first and use the AirPlay button in the video.
- **Audio latency:** clean audio needs ≈0.35 s of buffering on a Wi-Fi TV, so lips can trail slightly in mirroring; the optional timestamp sync mode delays the picture instead (it still uses the older audio path).
- **Mac Music artwork** can be missing for the first song (the Mac sends an empty image); the next song's appears.
- **DRM:** Netflix, Apple TV+, Disney+ and other FairPlay-protected videos are not supported by design; the TV shows "This video is protected".
- **Android 10+ without "Display over other apps":** screens cannot open over another app; a toast tells you to open AynaCast TV.
- Not yet verified: the 1.3.0 audio and Mac changes on Android 8, and a long (30 min+) run. Details and measurements: [`LIVE_ADB_TESTS.md`](LIVE_ADB_TESTS.md), [`CHANGELOG.md`](CHANGELOG.md).

---

## ⚖️ Legal Disclaimer & Trademark Notice

> **IMPORTANT LEGAL NOTICE**
> 
> - **AirPlay, Apple TV, iPhone, iPad, Mac, and macOS** are registered trademarks of **Apple Inc.**, registered in the U.S. and other countries and regions.
> - This software is an **independent, open-source clean-room implementation** developed solely for device interoperability, personal research, and education.
> - This project is **NOT affiliated with, sponsored by, authorized by, or endorsed by Apple Inc.**
> - **FairPlay & DRM Notice:** This software **does NOT decrypt, bypass, or circumvent any Digital Rights Management (DRM) or FairPlay-protected streams** (such as encrypted media from Apple TV+, Netflix, etc.). It functions exclusively by receiving unencrypted local network screen mirroring and audio frames over standard network protocols (RTSP, RTP, H.264, PCM) for cross-platform hardware interoperability.

---

## 📄 License

Licensed under the **MIT License**. See the [LICENSE](LICENSE) file for details and [TRADEMARKS.md](TRADEMARKS.md) for trademark notices.
