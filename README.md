# AynaCast TV — AirPlay & Screen Mirroring Receiver (Android TV & Google TV)

[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Google%20TV-blue.svg)](https://developer.android.com/tv)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Compose TV](https://img.shields.io/badge/Jetpack%20Compose%20for%20TV-1.0.0-green.svg)](https://developer.android.com/jetpack/compose/tv)
[![NDK](https://img.shields.io/badge/NDK-r30-orange.svg)](https://developer.android.com/ndk)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

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
│  • KEY_LOW_LATENCY = 1, Real-Time Prio │      │  • PERFORMANCE_MODE_LOW_LATENCY        │
│  • Zero-copy Direct Hardware Render    │      │  • USAGE_MEDIA & AudioFocus transient  │
└────────────────────────────────────────┘      └────────────────────────────────────────┘
```

---

## ✨ Key Features

- **Dynamic Device Identity:** Automatically resolves and broadcasts the TV's real human-readable system name (`Settings.Global.device_name`, Bluetooth name, or Model) rather than a generic hardcoded label.
- **Bilingual Multiplatform Localization (TR / EN):**
  - **Turkish (Default):** First-class native Turkish user interface and strings.
  - **English (Secondary):** Complete English localization with verified 1:1 parity tested by unit tests.
- **Full Theme Mode Support (System / Dark / Light):**
  - **System (Default):** Follows the Android TV system appearance setting.
  - **Dark:** OLED/TV-optimized deep black theme.
  - **Light:** High-contrast crisp light theme.
  - Persisted across restarts via `AppSettingsRepository`.
- **Dual TV Windows:**
  - **Settings Window (`AirPlaySettingsActivity`):** Live service indicator, D-Pad focused controls, appearance selection, language picker, and instructions.
  - **About Window:** Application version, architecture overview, supported network protocols, developer attribution, and legal notice.
- **TV D-Pad Remote Focus:** Animated high-contrast borders and color shifts when navigating with a television remote control.
- **Zero-Warning Clean Build:** Fully compliant with Android 14/15/16 SDK guidelines, deprecation-free modern APIs (`BluetoothManager`, `WIFI_MODE_FULL_LOW_LATENCY`, `POST_NOTIFICATIONS`).

---

## 📦 Distribution Packages (`dist/`)

The automated packaging engine produces both universal and split-architecture builds:

| Package | Target | Size | Description |
| :--- | :--- | :---: | :--- |
| `AynaCastTV-v1.0.0-universal.apk` | Universal | ~12 MB | Single APK containing `arm64-v8a`, `armeabi-v7a`, and `x86_64`. |
| `AynaCastTV-v1.0.0-arm64-v8a.apk` | 64-bit ARM | ~9.5 MB | Optimized for modern 64-bit Android TV & Google TV devices. |
| `AynaCastTV-v1.0.0-armeabi-v7a.apk` | 32-bit ARM | ~9.0 MB | Optimized for legacy 32-bit Android TV boxes and sticks. |
| `AynaCastTV-v1.0.0-x86_64.apk` | 64-bit x86 | ~9.5 MB | Optimized for Android TV emulators and Intel/AMD hardware. |
| `AynaCastTV-v1.0.0-release.aab` | Google Play | ~11 MB | Official Android App Bundle for Google Play Console distribution. |

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
2. Select `/Users/berkbabadogan/Desktop/jetpack_compose_airplay`.
3. Click **Sync Project with Gradle Files**.
4. Select your Android TV device/emulator and click **Run 'app'**.

---

## 🧪 Unit Tests

This project includes verified unit tests ensuring zero regressions:
- **`AirPlayStringsTest`:** Validates non-blank strings, bilingual distinctiveness, and 1:1 XML resource matching between Turkish and English.
- **`AppSettingsTest`:** Validates default language (Turkish), default theme (System), and parsing rules.
- **`DeviceIdentityHelperTest`:** Validates MAC address formatting and RAOP prefix extraction.

Run all tests via:
```bash
./gradlew test
```

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

Licensed under the **Apache License, Version 2.0**. See the [LICENSE](LICENSE) file for complete terms and copyright notices.
