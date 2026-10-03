# Changelog

All notable changes to the **AynaCast TV Receiver** (`com.babadogan.aynacast.tv`) project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.1.0] - 2026-10-03

### Fixed
- **AirPlay Mirroring Black Screen & Hardware Decoder Resolution Crash:**
  - Resolved fatal MediaTek hardware video decoder crash (`VDEC_DEC_DECODE_HD_NOT_SUPPORT` / `0x80001001`) caused by advertising 4K panel resolution (`sys.display-size: 3840x2160`) to AirPlay, which forced senders (macOS/iOS) to stream 3616x2032 @ 75fps.
  - Capped negotiated AirPlay mirroring display resolution strictly to standard 1080p (`1920x1080 @ 60fps`) with `overscanned=false` in RTSP `GET /info` plist.
- **MediaCodec H.264 Pipeline & Bitstream Resiliency:**
  - Integrated SPS bitstream Exp-Golomb parser to dynamically determine authoritative video dimensions from incoming SPS NAL units.
  - Removed duplicate SPS/PPS prepending into keyframe payload which corrupted hardware decoder parsers.
  - Added seamless automatic software decoder fallback (`OMX.google.h264.decoder`) if the hardware decoder ever reports an error.
  - Enforced monotonic relative presentation timestamps (`nextFramePtsUs`) preventing SurfaceFlinger implausible timestamp warnings and buffer queue stalls.
  - Implemented IDR keyframe resync gate (`awaitingKeyframe`) on startup and reconnect to prevent predicted frame smearing and macroblocking artifacts.
- **Aspect-Fit Letterboxing & Pillarboxing:**
  - Implemented automatic aspect-fit centering in `AirPlayPlayerActivity` using Jetpack Compose `aspectRatio()` and solid black backing (`Color.Black`), cleanly letterboxing 16:10 MacBooks and pillarboxing portrait iPhones without stretching.

## [1.0.0] - 2026-10-02

### Added
- **Rebranding & Package Architecture:**
  - Rebranded to **AynaCast TV** combining Turkish ("Ayna" - Mirror) and English ("Cast" - Stream).
  - Package ID and namespace updated to `com.babadogan.aynacast.tv`.
- **AirPlay Mirroring Cryptographic Handshake & Video Socket:**
  - Integrated TweetNaCl (Ed25519, Curve25519 ECDH, SHA-512) and AES-128-CTR in C++ NDK engine.
  - Implemented authentic Ed25519 signing and Curve25519 ECDH key derivation for `/pair-verify` and `/pair-setup`.
  - Added dedicated TCP Port 7100 Video Server socket for receiving live iOS mirror stream and dispatching SPS/PPS and H.264 NALUs to Android `MediaCodec`.
  - Fixed TV remote D-Pad navigation, tab layout constraints, and contrast borders in Jetpack Compose UI.
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
