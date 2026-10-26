# Changelog

All notable changes to the **AynaCast TV Receiver** (`com.babadogan.aynacast.tv`) project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Security
- **Mac PIN could be skipped with a known DACP-ID:** a classic AirPlay sender (the "(Mac)" entry) was let in without a password whenever its `DACP-ID` belonged to a paired Mac. That ID is a header the sender chooses, and Macs announce it on the network, so any LAN host could stream to the TV and take over a running session with the PIN on. The code a Mac paired with is now its password and is checked with HTTP Digest on every connection (fresh nonce per connection, so a seen answer cannot be replayed); a wrong password counts towards the PIN lockout. A paired Mac that does not answer the challenge within 3 s gets a new code on screen. Macs paired with an earlier version enter a code once more.

### Tests
- Native: 154 → 157 checks (classic Digest per connection and replay). Unit: `ClassicDigestTest`.

## [1.5.3] - 2026-10-19

### Fixed
- **Update window focus:** the remote's focus now starts on "Update Now" (or Close) when the result appears, instead of on nothing, so OK works at once.

## [1.5.2] - 2026-10-19

### Added
- **Update from GitHub:** "Check for Updates" at the bottom of About checks AynaCast's GitHub releases and opens a result window: up to date, no published release / repository not public, GitHub unreachable, or a newer version. "Update Now" downloads the release APK for the TV's processor (arm64-v8a / armeabi-v7a / x86_64, else universal), checks it against the SHA-256 digest GitHub publishes and installs it over the app after the TV's confirmation prompt; AynaCast reopens by itself after the update. Without the "Install unknown apps" permission its setting opens first. If the installed app has a different signing key, the window explains to uninstall AynaCast and install the new version.
- **Signing check when packaging:** `package_tv.sh` compares the release APK's signing certificate with `release-cert.sha256` and prints a SIGNING KEY MISMATCH warning when they differ.

## [1.5.1] - 2026-10-18

### Added
- **Read-Only License Viewer:** One-click/tap read-only GPL-3.0 license viewer dialog (`LicenseViewerDialog`) directly accessible from the License card on the About screen, complete with TV remote D-Pad scrolling and full monospace license text.
- **SHA-256 Verification:** Automated SHA-256 hash generation (`SHA256SUMS`) in `package_tv.sh` alongside all release and debug APK artifacts.

### Changed
- **Packaging:** APK outputs now have distinctive, architecture-specific names (`AynaCastTV-v1.5.1-<abi>-<buildType>.apk`) directly from Gradle and the packaging script; dropped Google Play AAB bundle generation in favor of direct installable APKs, while preserving the Universal APK build.
- **Settings and About:** a Close button at the bottom of the window as well as the one in the top-right corner, as in Pitwall Updater. In Settings, Restart Service moved down next to it, so both stay on screen while the page scrolls.
- **Home screen:** fits on one screen, so moving to the lower cards no longer scrolls the info cards out of view; the How to Connect card is text only and the remote skips it.

## [1.5.0] - 2026-10-17

### Changed
- **Refreshed look, matching Pitwall Updater:** darker background and cards, an accent bar before the screen and window titles, small grey captions on the cards, and buttons that fill light with dark text when focused. The home screen opens with info cards for the AirPlay name, version and status.
- **Publisher:** About now shows "BABADOGAN TECH AND EDU" under Publisher; the Ankara University Teknokent line is gone.

### Fixed
- **Paired devices:** a focused device card (which grows slightly) is no longer cut off under the title bar when the remote scrolls to a lower row.

## [1.4.0] - 2026-10-17

### Documentation
- README "Tested Devices & Compatibility" and a "Devices to test next" list in `LIVE_ADB_TESTS.md`: only two MediaTek-family 32-bit TVs are verified; Amlogic / Google TV (Android 12–14), arm64, NVIDIA Shield and a long run are still untested.

### Changed
- **License: MIT → GNU GPL-3.0.** The app bundles the GPL-3.0 `playfair` FairPlay code (as UxPlay and RPiPlay do), so the project as a whole must be GPL-3.0; its license file and origin are now included (`app/src/main/cpp/playfair/`), and `THIRD_PARTY_NOTICES.md` lists all third-party code. The About screen says GPL-3.0 in every language.
- **Simpler home screen:** it now shows only the receiver status, how-to and permission cards. **Settings** and **About** open in their own full-screen windows with a close button in the top-right corner; the remote's Back button closes whichever window is open.
- **Paired devices:** the manager opens full screen and shows each device as a card with an icon (iPhone, iPad, Mac, music note or generic), its name and pairing date; long lists scroll and the buttons are no longer clipped. The icon follows the hardware model the device reports when it connects (e.g. `iPhone15,2`), so a renamed device still gets the right one; devices paired earlier pick up their model on their next connection, and the Mac Music app (classic AirPlay) is shown with a music note.

## [1.3.0] - 2026-10-13

### Known issues
- **macOS Music app needs its own entry:** to the main receiver it only offers FairPlay version 2, which no open-source receiver can implement (UxPlay #570). The TV therefore also advertises a classic AirPlay speaker, **"<TV name> (Mac)"**, which the Mac uses with RSA; Macs (and iPhones, in their speaker list) see two entries.
- **Mac PIN is weaker than iPhone pairing:** a Mac enters the on-screen code as an HTTP Digest password and is then remembered by its `DACP-ID`, which another LAN host could reuse.
- **Mac Music artwork:** the Mac sometimes sends an empty image for the first song and nothing later; the next song's artwork arrives.
- **TVs whose Wi-Fi drops incoming multicast** (seen on an Arçelik / MStar M7322, Android 11) cannot answer discovery queries; the TV relies on its announcements. "Refresh Visibility" (on by default) re-announces every minute while idle, so a phone that rejoined Wi-Fi finds it within ~30–60 s; an open device list may briefly drop and re-add the TV.
- **Safari videos while screen mirroring is on:** iOS treats the cast as a temporary hand-off and may end it after a few seconds and return to mirroring; stop mirroring first.
- **Timestamp sync mode** (off by default) still plays mirroring audio through the older direct `AudioTrack` path (≈0.6 s buffer, small overflow drops).
- **Android 8 not re-verified:** the audio jitter buffer, the Mac entry and the toast fallback were developed and measured on Android 11 (Arçelik); the Philips (Android 8) comparison is pending (`LIVE_ADB_TESTS.md`).
- **Long-run test (Test 12) not done yet.**

### Added
- **macOS Music app support** through a classic AirPlay (RAOP) speaker entry "<name> (Mac)": RSA `Apple-Challenge` signing and `rsaaeskey` unwrapping with the AirPort Express key (`AirPortRsa`), SDP `ANNOUNCE`, `Transport` `SETUP`; ALAC, NTP and the jitter buffer are shared with the other paths. With the PIN on, the Mac is asked for the on-screen code as its password and remembered in the paired devices list.
- **Audio jitter buffer** (`PcmJitterOutput`): decoded audio is queued and fed to a small `AudioTrack` by its own thread with blocking writes, so Wi-Fi bursts no longer overflow the track and the gaps after them no longer drain it. Adaptive target (mirroring 120 ms start, music 400 ms), one-frame drift correction, latency trim after refill pauses, stalled-output recovery.
- **Refresh Visibility** setting (on by default): re-registers the mDNS records every 60 s while nothing is streaming.
- **Romanian** app language (all texts, resources and date formats), alongside Turkish and English.
- **Background launch fallback that is visible on TVs:** without "Display over other apps" a toast shows the PIN code or says that the screen could not come to the front; the app asks for the permission when it is opened.
- **Decoder indicator:** the player's info box ends in `HW` or `SW`.
- **Diagnostics:** remote key codes, sender rate commands and the playing/paused events sent to the sender are logged; a rejected FairPlay key message logs its version.

### Fixed
- **Mirroring lag on MStar/MediaTek TVs (Android 11):** `KEY_LOW_LATENCY` made `OMX.MS.AVC.Decoder` fail `configure()` and the slow software decoder took over (~40 fps, hundreds of dropped frames a minute); it is now only requested from decoders that advertise it, and a failing hardware decoder is retried without optional keys (and released) before the software fallback.
- **Constant audio crackle** with Mac mirroring and iPhone music (see the jitter buffer above).
- **Classic AirPlay sessions ended after 15 s:** they send no `/feedback`; they now live on audio packets (120 s silence limit).
- **Language buttons** did not fit three languages; theme names were Turkish-or-English only.

### Added
- **Screen transitions:** opening and closing status cards with window animations for mirroring, video casting and PIN; a closing card is not covered by the next screen.
- **Receiver controls:** an "AirPlay off" kill switch (persisted, respected on boot) and a "Stop streaming" button that disconnects the current sender.
- **Paired devices:** listed with name and pairing date, removable one by one; PIN outcome cards (paired, expired, wrong code, locked).
- **Volume indicator** for sender volume changes, and a "Show Now Playing" entry while music plays.
- Accurate status text (mirroring / playing audio / connected but idle); the Settings tab scrolls to the settings.

### Fixed
- **MediaTek hardware decoder:** a replayed GOP fed back to back read as ~24,000 fps and the decoder failed, falling back to the software decoder; input timestamps are now spaced at least 1/60 s apart.
- **Corrupted picture on fast scrolling:** delta frames were dropped when the decoder was briefly busy; it is now waited for.
- **Stretched / squashed mirroring:** the TV's video plane applied its own aspect processing; mirrored video is now letterboxed with OpenGL into a full-screen frame (iPhone, iPad and Mac aspect ratios).
- **Video casting:** pausing with the TV remote is reported to the sender (playing / paused / loading events); the session waits 5 s at the end of a video for autoplay or repeat; DRM-protected streams show an explanation; faster start (1 s start buffer).
- **Native crashes:** Connection threads could outlive the server when the service stopped or restarted (use-after-free); they are now tracked, woken and joined. JNI callback threads are detached from the VM on exit and pending Java exceptions are cleared.
- **Audio:** Mirroring audio (AAC-ELD) was fed into the ALAC decoder, producing noise and risking a heap overflow. The codec is now taken from the audio `SETUP` (`ct`), AAC is decoded with `MediaCodec`, and the ALAC decoder rejects corrupt frame sizes and zero-runs that overflowed its buffers.
- **Session lifecycle:** A sender that disappears without `TEARDOWN` (Wi-Fi loss, sleep) no longer leaves the player frozen on screen; the session ends when its RTSP connection closes (TCP keep-alive detects silent drops) or when the session owner sends no `/feedback` heartbeat for 15 s (as UxPlay does).
- **Audio filler packets:** AAC-ELD "no data" packets (`00 68 34 00`) and 44-byte ALAC filler packets are skipped instead of being decoded; unknown compression types are muted rather than decoded as noise.
- **RTSP parsing:** Requests split across TCP segments or pipelined in one segment are now framed correctly; `SETUP`/`TEARDOWN` bodies are parsed as binary plists instead of byte searches; device names containing `&` or `<` no longer break the `/info` XML.
- **Video pipeline:** Surface bind / codec config tasks can no longer be dropped by a full frame queue; the surface is released synchronously in `surfaceDestroyed`; frames buffered since the last keyframe are replayed into a new decoder; oversized access units no longer leak decoder input buffers; SPS parsing removes emulation-prevention bytes.
- **mDNS:** Opening the app no longer registers duplicate services ("Name (2)"); the advertised `pk` always matches the running server and is also published in the `_raop._tcp` record (as RPiPlay does); network changes re-register only when local addresses change; names longer than the DNS-SD label limit are truncated instead of failing to register.
- **Device ID:** Placeholder MACs (`02:00:00:00:00:00`) are ignored and derived IDs are valid unicast addresses.
- **UI:** About screen showed the wrong license (Apache 2.0 → MIT) and a hard-coded version; the port list said "Video UDP 7100" (it is TCP); the service status reflects whether the server is actually listening; the player opens in its own task so closing it returns to the previous TV app.
- **Lint:** `BluetoothAdapter.getName` is guarded by the Android 12+ `BLUETOOTH_CONNECT` check (lint error).

### Added
- **UxPlay parity** (checked against FDH2/UxPlay, FD-/RPiPlay and jqssun/android-airplay-server):
  - NTP timing exchange with the sender's `timingPort`; sender timestamps are mapped onto the local clock.
  - Audio RTP reorder buffer with retransmission requests to the sender's control port; burst losses are skipped after one short wait instead of stalling.
  - Pair-verify step 2 signature verification (connection closed on failure).
  - `GET /info` `txtAirPlay` / `txtRAOP` qualifier replies and `501 Not Implemented` for unknown RTSP methods.
  - Per-stream `TEARDOWN`: stopping the mirrored video keeps the session (and audio) alive.
  - Optional "Audio/Video Sync (Timestamps)" setting, the equivalent of UxPlay `-vsync`; off by default as UxPlay recommends for macOS mirroring and hardware decoding.
- The player opens when mirrored video starts, so audio-only AirPlay no longer shows a black screen.

- **Video casting** (UxPlay `-hls` semantics): an app's AirPlay button can hand its video to the TV instead of mirroring. Direct URLs (Safari and most apps) and YouTube's `mlhls://` playlists (fetched from the sender through FCUP requests and served to the player from `http://localhost:7000`, with `#YT-EXT-CONDENSED-URL` segments expanded) play in a Media3/ExoPlayer screen; the sender controls play/pause/seek, and the TV remote works too. FairPlay-protected video (`/fp-setup2`) is refused, as in UxPlay.
- **Now Playing screen** for audio-only AirPlay: cover art, title, artist, album (DMAP metadata) and a progress bar, like an Apple TV.
- **On-screen PIN pairing** (UxPlay `-pin`, off by default in Settings): a 4-digit code is shown the first time a device connects (SRP-6a / AES-GCM `/pair-setup-pin`); paired devices are remembered and can be forgotten.

### Fixed (additional)
- Device names with emoji no longer crash JNI (standard UTF-8 conversion in both directions).
- `POST /pair-setup-pin` was routed to the plain `/pair-setup` handler (caught by the native tests).
- "Display over other apps" permission card (Android 10+) and a fallback notification to open the mirrored screen when the system blocks background launches.
- "Show Mirroring" button while a session is active, sender name in the HUD, sender volume control, localized (TR/EN) notifications.
- Unit tests for audio format, SPS parsing and device identity; native host test suite under `tools/native-host-tests/`.

### Security & robustness (full review of the change set)
- **One session owner:** the connection that sent the session `SETUP` owns the session. `SETUP`/`RECORD`/`TEARDOWN`/`SET_PARAMETER` from another connection get `409 Conflict`, so a second iPhone (or any LAN host) can no longer end or corrupt a running mirror. A new sender's session `SETUP` takes over and disconnects the previous one, like an Apple TV; this also lets a sender that reconnected replace its own stale connection at once. A connection that drops after `SETUP` but before `RECORD` now releases timing, audio and keys.
- **Per-connection handshakes:** pair-verify and FairPlay state is kept per connection (as UxPlay does), so concurrent handshakes cannot overwrite each other's keys.
- **Crash fixes:** NTP timing start/stop from different connections could terminate the process (`std::terminate`); a tiny ALAC frame size (`spf`) allowed a heap overflow from one UDP packet; a UTF-16 length overflow and shared-reference / huge-count binary plists could crash or exhaust memory; an out-of-range FairPlay mode byte read past the key tables; corrupt ALAC frames replayed the previous PCM.
- **Mirroring video port:** only the session's sender can (re)open TCP 7100; a LAN scan no longer cuts the mirror off.
- **Connection limits:** at most 32 open connections; silent connections that own nothing close after 120 s; request bodies are capped at 8 MB.
- **PIN pairing:** pairing only works against a code that is on screen (no silent "0000" fallback), allows 3 wrong entries per code, locks for 5 minutes after 9 wrong entries in a row, and the code expires after 2 minutes even when its screen could not be opened. With PIN on, only a connection that completed pair-verify may start a session.
- **Persistent identity:** the receiver's Ed25519 key is derived from a stored seed, so the advertised `pk` and PIN pairings survive restarts; the identity and pairings are excluded from cloud backup and device transfer.
- **Video casting:** a closing player screen could stop the next cast and tell the sender "stopped"; late FCUP replies for a previous video were accepted; `/play` before `/reverse` hung forever; `/scrub` and `/rate` sent before the player existed were lost; a missing master playlist left the sender waiting; Home left the video playing invisibly.
- **Background launches:** the PIN code and casting screens get the same fallback notification as mirroring (the PIN notification shows the code itself).
- **A/V sync mode:** audio no longer goes permanently silent when the TV's audio path is slower than the video (the shared latency rises instead); inserted silence never crowds out the audio packet. Video frames are now held *before* decoding until they are due, so video can wait as long as the TV's audio needs without filling the display queue and stalling the decoder (frames reach the display at most 50 ms early); the measured video delay excludes that deliberate wait.
- **Mirroring restart:** after a video-only `TEARDOWN` the next video `SETUP` starts a fresh AES-CTR stream even when it reuses the stream connection ID (as UxPlay), instead of continuing the old keystream into garbage.
- **GOP replay:** an incomplete GOP replays only its keyframe, replayed frames are not shown except the newest, and a surface teardown interrupts the replay.
- **TV remote:** the "Show Mirroring" and overlay-permission buttons are reachable with the D-pad.
- **Second review round:**
  - YouTube casting: real senders report a successful FCUP reply with status `0`, which the first fix treated as a failure and aborted every YouTube cast; replies are now judged by their content, as in UxPlay.
  - PIN mode: every `SETUP` and `RECORD` (not only the session `SETUP`) requires a completed pair-verify, and the mirroring video port only accepts a connection from an established session's sender.
  - Sender names with non-ASCII characters (`Berk’s iPhone`, Turkish letters, emoji) were shown with `?`; UTF-16 plist strings are now decoded to UTF-8.
  - Idle connections from the session's own sender (e.g. a silent event connection) are no longer closed.
  - The last frame of a screen change was only shown when the next frame arrived (mirroring is variable-frame-rate); the decoder is now drained while idle.
  - A sender's play/pause after the TV remote had changed playback was ignored.
  - Turning A/V sync on or off now takes effect immediately; another device's PIN screen no longer ends a running video cast; plugging in a keyboard or remote, or a UI mode change, no longer restarts the player screens; an old PIN's expiry can no longer cancel a new code.
- Native tests: 93 → 126 checks; unit tests: 35 → 41 (`VideoCastSessionTest`, A/V sync audio latency).

## [1.2.0] - 2026-10-03 - Milestone: Mac Screen Mirroring (Video Only / Without Sound)

### Added
- **Milestone: Mac Screen Mirroring (Without Sound / Video Only):**
  - Reliable, artifact-free, freeze-free full-screen AirPlay video mirroring from macOS to Philips Android TV (`TPM171E`, MT5891 AVC decoder).
  - High framerate up to 60 FPS with hardware-accelerated MediaCodec pipeline rendering directly onto `SurfaceView`.
  - In-band Annex-B SPS/PPS parameter set prepending before IDR keyframes for seamless decoder stream synchronization.
  - Multi-threaded video architecture: isolated worker thread (`AirPlay-VideoDecoder`) guarantees decoupling between RTSP network socket and UI rendering.
  - Robust handling of macOS extended display vs mirror mode with aspect-fit letterboxing/pillarboxing.

### Fixed
- **AirPlay Mirroring Black Screen & Hardware Decoder Resolution Crash:**
  - Resolved fatal MediaTek hardware video decoder crash (`VDEC_DEC_DECODE_HD_NOT_SUPPORT` / `0x80001001`) caused by advertising 4K panel resolution (`sys.display-size: 3840x2160`) to AirPlay, which forced senders (macOS/iOS) to stream 3616x2032 @ 75fps.
  - Capped negotiated AirPlay mirroring display resolution strictly to standard 1080p (`1920x1080 @ 60fps`) with `overscanned=false` in RTSP `GET /info` plist.
- **MediaCodec H.264 Pipeline & Bitstream Resiliency:**
  - Integrated SPS bitstream Exp-Golomb parser to dynamically determine authoritative video dimensions from incoming SPS NAL units.
  - Added seamless automatic software decoder fallback (`OMX.google.h264.decoder`) if the hardware decoder ever reports an error.
  - Enforced monotonic relative presentation timestamps (`nextFramePtsUs`) preventing SurfaceFlinger implausible timestamp warnings and buffer queue stalls.
  - Implemented IDR keyframe input buffer retry queueing to guarantee initial frame decoding without black screen.
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
