# Changelog

All notable changes to the **AirPlay TV Receiver** project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-10-02

### Added
- **Native C++ AirPlay Engine (`libairplaycore.so`):**
  - RTSP server on TCP port 7000 supporting `OPTIONS`, `GET /info` (Apple XML plist), `POST /pair-setup`, `POST /pair-verify`, `POST /fp-setup`, `SETUP`, `RECORD`, `TEARDOWN`, `PAUSE`, `FLUSH`, `GET_PARAMETER`, and `SET_PARAMETER`.
  - Curve25519 diffie-hellman key exchange simulation and ChaCha20 stream payload decrypter.
  - H.264 stream parser converting AVCC length-prefixed NAL units into Annex B format (`0x00000001`) with automatic SPS (`type 7`) and PPS (`type 8`) extraction.
  - High-performance JNI bridge communicating with Android runtime via asynchronous callbacks.
- **Background Daemon Architecture (`AirPlayDaemonService`):**
  - Android Foreground Service with `connectedDevice` type and `stopWithTask="false"`.
  - Automatic network discovery publishing `_airplay._tcp` and `_raop._tcp` services via Android's `NsdManager`.
  - Dynamic device identity resolution using `DeviceIdentityHelper` (fetching real TV system name, Bluetooth name, or device model).
  - Network state listener using `ConnectivityManager.NetworkCallback` to refresh mDNS when TV IP address changes.
  - Transient audio focus management (`AudioManager.requestAudioFocus`) pausing background TV audio during active AirPlay sessions.
  - Automatic startup on TV boot via `BootReceiver` listening for `ACTION_BOOT_COMPLETED`.
- **Hardware-Accelerated Video & Audio Pipelines:**
  - `VideoDecoderEngine`: Hardware-accelerated H.264 decoding using Android `MediaCodec` with `KEY_LOW_LATENCY = 1` and `KEY_PRIORITY = 0` rendering directly onto `SurfaceView`.
  - `AudioPlaybackEngine`: Low-latency 44.1 kHz 16-bit PCM stereo audio playback via Android `AudioTrack` configured with `PERFORMANCE_MODE_LOW_LATENCY`.
- **Jetpack Compose for TV Interface:**
  - `AirPlayPlayerActivity`: Full-screen on-demand video playback window launched automatically on `RECORD` and dismissed via `finish()` on `TEARDOWN`.
  - TV Leanback HUD displaying connected device name, real-time FPS counter, and resolution.
  - `AirPlaySettingsActivity`: Two-tab TV control center featuring **Settings** and **About** screens with D-Pad focus indicators and TV remote navigation.
- **Bilingual Multiplatform Localization (TR / EN):**
  - Turkish set as the default primary language (`AppLanguage.TR`).
  - English set as the secondary language (`AppLanguage.EN`).
  - Strict 1:1 key parity enforced and validated by `AirPlayStringsTest` unit tests across both typed catalog (`AirPlayStrings.kt`) and XML resources (`values/` & `values-en/`).
- **Complete Theme Management:**
  - Support for `System` (dynamic system theme following `isSystemInDarkTheme()`), `Dark` (OLED black), and `Light` appearance modes.
  - Persistent user preference storage via `AppSettingsRepository`.
- **Android TV Platform & Legal Compliance:**
  - Declaration of Android TV hardware exclusions (`touchscreen`, `faketouch`, `telephony`, `camera`, `bluetooth`, `wifi` marked as `required="false"`).
  - Runtime notification permission helper for Android 13+ (`POST_NOTIFICATIONS`).
  - Prominent Apple trademark disclaimers and interoperability notices in both the application UI and repository root documentation.
- **Automated Packaging Ecosystem:**
  - Finder double-clickable `AirPlayTV-Package.command` script.
  - Terminal packaging script `package_tv.sh` producing Universal APK, ARM64 APK, ARM7 APK, x86_64 APK, and Google Play App Bundle (AAB).
  - Unit test suite (`AirPlayStringsTest`, `AppSettingsTest`, `DeviceIdentityHelperTest`).
