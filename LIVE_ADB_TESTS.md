# AynaCast TV – Live ADB Test Plan

This document is for verifying AynaCast TV end to end on a real Android TV with real Apple devices
(iPhone/iPad/Mac). Each test says what to do, what should appear on screen and which log lines
should show up. Fill in the table at the bottom and send it back together with the `tv-logs/` folder.

> The automated tests (126 local network tests, 41 unit tests) verify the protocol; this plan targets
> only what can be seen on a real device (picture, sound, latency, Apple's real behaviour, TV hardware).

UI labels below are the app's English ones (Settings → App Language → English); the Turkish UI uses
the same layout.

---

## 0. Preparation

### Requirements
- Android TV (Android 8.0+ / API 26, the app's `minSdk`) with developer options enabled.
- On the same Wi-Fi/LAN: at least one iPhone/iPad, ideally a Mac and a **second Apple device** (for the takeover test).
- Android platform-tools (`adb`) on the computer. `dns-sd`, `nc` and `curl` come with macOS.
- APK: build the repository (`./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/`). The build
  produces one APK per CPU architecture: find the TV's with `adb shell getprop ro.product.cpu.abilist`
  and install the matching one (`app-armeabi-v7a-debug.apk`, `app-arm64-v8a-debug.apk`) or
  `app-universal-debug.apk`. Alternatively use the package `./package_tv.sh release` creates under `dist/`.

### ADB over the network on the TV
1. Settings → Device Preferences → About → press **Build** 7 times.
2. Developer options → enable **USB debugging** and (if present) **Network debugging / ADB over network**.
3. Note the TV's IP address (Settings → Network).
4. If Android 11+ uses "Wireless debugging", pair first: `adb pair IP:PAIRING_PORT`.

### Environment variables and helper function (once per terminal)
```bash
export TV=192.168.1.50:5555          # the TV's IP:port
export TV_IP=${TV%:*}
export PKG=com.babadogan.aynacast.tv
mkdir -p tv-logs

# Clears the log and records one test; press Ctrl+C when the test is over.
# Note: '*:S' must be quoted; zsh (the macOS default shell) treats an unquoted asterisk as a
# file name pattern and the command fails with "no matches found".
tvlog() {
  adb -s "$TV" logcat -c
  adb -s "$TV" logcat -v time \
    AirPlayRTSP:V AirPlayCrypto:V AirPlayNTP:V AirPlayVideo:V AirPlayJNI:V H264Parser:V \
    AirPlayDaemonService:V AirPlayNativeBridge:V VideoDecoderEngine:V AudioPlaybackEngine:V \
    PinPairingManager:V VideoCastActivity:V BootReceiver:V DisplayHelper:V GlVideoRenderer:V \
    EventLogger:V AndroidRuntime:E 'libc:F' 'DEBUG:F' '*:S' | tee "tv-logs/$1.log"
}
```

### Connect, install, grant permissions
```bash
adb connect "$TV"
adb -s "$TV" install -r app/build/outputs/apk/debug/app-universal-debug.apk   # or the APK for the TV's architecture
# Lets the app open its screens from the background (otherwise the notification fallback is used – see Test 10)
adb -s "$TV" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
# Notification permission on Android 13+
adb -s "$TV" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
adb -s "$TV" shell getprop ro.build.version.release   # Android version (write it in the results table)
adb -s "$TV" shell getprop ro.product.model            # TV model
```

---

## 1. Installation and service start

**Steps**
```bash
tvlog 01-startup     # keep this running in a separate terminal
adb -s "$TV" shell am start -n "$PKG/.ui.AirPlaySettingsActivity"
adb -s "$TV" shell dumpsys activity services "$PKG" | grep -E "ServiceRecord|isForeground"
```

**Expected screen:** the settings screen opens with "Service Status: Active" (green dot).

**Expected log**
- `AirPlay Daemon Service creating.`
- `Persistent identity loaded. Public key: <hex>` → note this key (compared in Test 11).
- `AirPlay RTSP Server started on port 7000, Video TCP on port 7100, Audio UDP on 7011`
- `_airplay._tcp registered successfully: ...` and `_raop._tcp registered successfully: ...`
- `Broadcasting AirPlay device name: '...', Device ID: '...', PK: '...'`
- In the `dumpsys` output the service shows `isForeground=true`.

**Fails if:** `AirPlay server failed to start`, `could not be started`, `Native AirPlay core unavailable`.

---

## 2. Visibility on the network (from a Mac)

```bash
dns-sd -B _airplay._tcp local.        # the TV's name must be listed (Ctrl+C)
dns-sd -B _raop._tcp local.           # "<MAC>@<TV name>" must be listed
dns-sd -L "<TV name>" _airplay._tcp local.   # TXT record
nc -vz "$TV_IP" 7000 && nc -vz "$TV_IP" 7100
curl -s "http://$TV_IP:7000/server-info"
```

**Expected**
- The TXT record contains `features=0x5A7FFFF7,0x1E`, `model=AppleTV...`, `pk=<key from Test 1>`; with PIN off, `pw` is absent or `false`.
- Ports are open; `/server-info` returns an XML plist (`features` 639, `model` AppleTV3,2).
- The TV appears on the iPhone under Control Center → Screen Mirroring.

---

## 3. iPhone screen mirroring (picture + sound)

**Steps:** `tvlog 03-mirroring-iphone` → iPhone Control Center → Screen Mirroring → the TV.
Then, in order: play a video with sound, rotate the iPhone between landscape and portrait (where the
app rotates), scroll a list fast and **let go**, hold the screen still for a few seconds, lock and
unlock the iPhone.

**Expected screen**
- The "Starting screen mirroring" card appears and the full-screen player follows within 1–2 seconds; the picture is smooth, without corruption.
- The info box at the top right (first 6 seconds) ends in **`• HW`**. `• SW` means the software decoder is in use: the picture lags on most TVs, so collect the log even if it looks acceptable.
- Portrait is centred with black bars on the sides; landscape fills the width with thin bars at the top and bottom. **Proportions stay correct** (round shapes stay round).
- **When scrolling stops, the final position shows at once** (it does not wait for the next screen change).
- Sound comes from the TV without crackle or gaps; changing the volume on the iPhone changes the TV's volume (a volume indicator slides in).

**Expected log**
- `Accepted incoming AirPlay connection #N from <iPhone IP>`
- `Pair-verify Step 2 completed successfully. Channel authenticated.`
- `NTP timing started: ...` and shortly after `NTP synchronized with sender (round trip ... ms)`
- `Responded to Video SETUP with streams: dataPort=7100, type=110`
- `AirPlay session started (RECORD).` / `Mirrored video started. Launching AirPlayPlayerActivity on-demand.`
- `OpenGL video renderer ready`
- `MediaCodec started on worker thread: <codec name> (...)` – the codec must be an **OMX/c2 hardware** decoder, `software=false`
- `Rendering video successfully at NN fps, X.XX Mbps` every second
- `Audio configured: codec=8 44100 Hz` (mirroring audio is AAC-ELD)
- On lock `Sender paused the video stream (display asleep)`, on unlock `Sender resumed the video stream`

**Fails if:** `Switching to software decoder fallback`, `Attempting software decoder fallback`,
`Decoder is 120 frames behind`, repeated `Dropping frame`, `AAC decoder keeps failing`, `Incorrect pair-verify signature`.

**Worth noting (not a failure):** `Retrying the hardware decoder without optional low-latency keys` followed by
`MediaCodec started ... software=false, minimalFormat=true` – the hardware decoder rejected an optional
setting and runs without it. Write the codec name and the `ACodec` error lines just before it in the notes.

**Measurement (optional):** run a stopwatch on the iPhone and photograph it together with the TV → note the latency (ms).

---

## 4. Mac screen mirroring

**Steps:** `tvlog 04-mirroring-mac` → Mac Control Center → Screen Mirroring → the TV. Try both
"Mirror Built-in Display" and "Use As Separate Display"; play sound on the Mac (choose the TV as the
sound output). Play a full-screen video too: an idle desktop sends frames of only a few hundred bytes
(`Video stream active: ... payloadLen=` ≈ 150–350, about 0.1 Mbps) and does not load the decoder.

**Expected:** same as Test 3; the Mac's name (e.g. "Berk’s MacBook Pro") appears on the settings screen
**with the correct characters** (no `?`). The extended display mode shows a picture too, with 16:10 proportions intact.

---

## 5. Session resilience

| # | Step | Expected screen | Expected log |
|---|------|-----------------|--------------|
| 5a | Stop mirroring from the iPhone | "Screen mirroring ended" card, then back to the previous TV app | `Ending AirPlay session: TEARDOWN` |
| 5b | Turn off Wi-Fi on the iPhone while mirroring | The player closes within ~15 s | `No request from the session owner for 15 s` or `closed without TEARDOWN` |
| 5c | After 5b, turn Wi-Fi back on and mirror again right away | Reconnects without waiting | possibly `takes over the session from` |
| 5d | While mirroring press **Home** on the remote, then open the app and press **"Show Mirroring"** | The picture comes back, no fast-forward or stale frames | `Replaying N buffered frame(s) from the last keyframe` |
| 5e | While iPhone A mirrors, **iPhone B** selects the TV | A's mirroring ends, B's picture appears | `Connection #M takes over the session from #N` |
| 5f | While mirroring, run `nc -vz $TV_IP 7100` **from another computer** | Mirroring continues | `Ignoring a video connection from <that computer's IP>` |
| 5g | While mirroring, open the app and press **"Stop Streaming"** | Mirroring ends on the TV and the iPhone; the TV stays listed | `Ending AirPlay session: stopped on the receiver` |
| 5h | Press **"Stop AirPlay"**, check Test 2's `dns-sd`, then **"Start AirPlay"** | "AirPlay Off" with an orange dot; the TV disappears from the list and returns after Start | service stops / `registered successfully` again |

> Do not run 5f from the mirroring device: a connection from the same IP counts as a legitimate reconnect.

---

## 6. Audio only (Music) and the Now Playing screen

**Steps:** `tvlog 06-music` → in Music/Spotify on the iPhone, the AirPlay icon → the TV. Pause/resume,
skip to the next song, change the volume on the iPhone, press **Back** on the TV, listen for 5 minutes.

**Expected screen:** cover art, title, artist, album and an advancing progress bar (non-ASCII characters
correct). The information updates when the song changes. Back closes the screen, **the music keeps
playing**, and the app's main screen shows "♪ Playing: …" with a **"Show Now Playing"** button that
reopens it. The mirroring player (black screen) does not open. After the music stops, the status
reads "Connected, nothing playing" until iOS closes the connection.

**Expected log**
- `Audio stream configured: ct=2 ...` (ALAC) or `ct=4` (AAC-LC); in Kotlin `Audio configured: codec=0` / `codec=4`
- `Now playing: '<title>' by '<artist>' (<album>)`, `Cover art received (... bytes, image/jpeg)`
- `Sender volume changed: -NN.NN dB`
- `Audio output: NN ms buffered of NN ms, underruns +N, dropped N B, ...` every 5 s

**Fails if:** audible gaps/clicks during the 5 minutes, `Unsupported audio compression type`, `?` characters on screen.

---

## 7. Video playback (not mirroring – the app's own AirPlay button)

### 7a. Safari / direct URL
Open a video in Safari on the iPhone (e.g. Apple's sample stream
`https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8`;
any web video if that address has moved), the player's AirPlay icon → the TV. `tvlog 07a-safari`.

| Check | Expected |
|-------|----------|
| Start | "Preparing video" card, then the full-screen player plays the video; the iPhone shows "Playing on TV" |
| Pause / play / seek from the iPhone | The TV follows; the iPhone's timeline matches the TV |
| TV remote: OK = pause/play, Left/Right = ∓10 s | Works, and the iPhone's play button / timeline follow |
| **Pause with the remote → play from the iPhone** | The TV resumes |
| Stop from the iPhone | "Playback ended" card, the TV player closes |
| **Back** on the TV | The player closes; the iPhone shows that playback ended |
| **Home** on the TV | The video stops (it does not keep playing unseen) |
| Let the video play to the end | Last frame held for 5 s (the sender may start the next video), then the player closes and the iPhone shows the end |

**Expected log:** `Video casting requested: https://... (start X s)`, `Casting https://... from Xs`,
`Sender seeks to NN.NN s` on seeks; at the end on the TV side `Video casting ended on the receiver; closing the casting session`.

### 7b. The YouTube app
A video in YouTube → AirPlay icon → the TV. `tvlog 07b-youtube`. Seek and pause while it plays;
then **switch to another video right away**; let a short video play to its end with autoplay on;
finally disconnect from the iPhone.

**Expected log (in order)**
- `Video casting requested: mlhls://localhost/master.m3u8`
- `FCUP request #N for mlhls://localhost/master.m3u8`
- `Master playlist received: K media playlists to fetch`
- `FCUP request #...` (one per media playlist, one at a time)
- `All playlists fetched; starting playback of http://localhost:7000/master.m3u8`
- `Casting http://localhost:7000/master.m3u8 from ...`
- A new `Video casting requested` when the video changes; possibly `Ignoring a playlist reply that belongs to no current request` (normal)
- At the end of a video with autoplay on: the next `Video casting requested` within 5 s, on the same screen

**Fails if:** `The sender could not provide the master playlist; stopping`, the player opening and staying
black, `Video casting playback failed`. Send the log in that case (see Troubleshooting).

### 7c. DRM-protected app (expected: not supported)
Try AirPlay from Netflix / Apple TV+ / Disney+. **Expected:** the app does not crash; an unprotected
intro may play, then the TV shows the **"This video is protected"** card and the iPhone shows an error
or falls back to mirroring. Log: `ERROR_CODE_DRM_SCHEME_UNSUPPORTED` or `HTTP Request: POST /fp-setup2`.
(FairPlay-protected content is deliberately not supported.)

---

## 8. PIN pairing

**Preparation:** Settings → **Connection Code (PIN)** → "On – Code on First Connection". On the Mac,
check `pw=true` with `dns-sd -L "<TV name>" _airplay._tcp local.`. `tvlog 08-pin`.

| # | Step | Expected |
|---|------|----------|
| 8a | The iPhone connects for the first time | A 4-digit code on the TV, the iPhone asks for it; the right code starts mirroring after a green "Paired" card. Log: `Sender requested on-screen PIN pairing`, `PIN pairing started`, `Device paired with PIN`, `PIN pairing completed; device registered` |
| 8b | The same iPhone connects again | No code is asked |
| 8c | A **wrong** code once on a new device | The iPhone asks again; entering the code on screen (a new code if the iPhone restarts) pairs. Log: `Wrong PIN entered on the sender` |
| 8d | Wait 2 min on the code screen | "The code expired" card, then the screen closes. Log: `PIN code expired` |
| 8e | Restart the service (Settings → "Restart Service") and reboot the TV (`adb -s $TV reboot`) | The iPhone from 8a connects **without a code** (persistent identity) |
| 8f | Settings → "Paired Devices (N)" | The list shows each device with its name and pairing date; "Remove" or "Forget All" makes the next connection ask for a code again |
| 8g | 9 wrong codes in a row from one device | "Too many wrong codes" card; no new code for 5 min. Log: `Too many wrong codes; PIN pairing locked for 300 s` |
| 8h | While a video plays (Test 7a), a **second** device selects the TV | The code screen opens, the playing video **does not close** |

Set PIN back to **"Off – No Code"** after the test (the default).

---

## 9. Audio/video sync (optional setting)

**Steps:** play an "audio video sync test" / "lip sync test" video from YouTube in **mirroring** mode
(as in Test 3). First with the setting **off**, then Settings → **Audio/Video Sync (Timestamps)** →
"On – Timestamp Sync". Change the setting while mirroring. `tvlog 09-sync`.

**Expected**
- Off: lowest latency; lip sync may be slightly off for the TV (note whether audio leads or trails, roughly by how many ms).
- On: the beep and the flash coincide; audio does not cut out, the picture does not stutter. The change takes effect **immediately**.
- Log: `Timestamp audio/video sync enabled`.

**Fails if:** audio cuts out completely when on, regular clicks, constant `Dropping frame`, a clear fps
drop (compare the `Rendering video successfully at NN fps` values).

---

## 10. Background launch fallback (without the permission)

```bash
adb -s "$TV" shell appops set "$PKG" SYSTEM_ALERT_WINDOW deny
```
With another app (e.g. YouTube) open on the TV: start mirroring; with PIN on, connect a new device; play a video.

**Expected:** opening the app shows the "Auto-Open Permission" dialog (choose Later). If a screen
cannot open by itself, a **toast** appears over the app in use: the PIN code itself (~10 s), or
"… is mirroring … open AynaCast TV". A notification is posted too; on Android TV it only appears in
the notification panel, and selecting it there opens the screen.
Log: `Could not open ...` is possible; the app must not crash. Afterwards:
```bash
adb -s "$TV" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
```

> Only applies to Android 10+: older versions do not restrict background activity starts.

---

## 11. Autostart on boot and persistent identity

```bash
adb -s "$TV" reboot
# Once the TV is up (without opening the app):
adb connect "$TV"; tvlog 11-boot
```
**Expected:** without opening the app, the TV appears in the iPhone's Screen Mirroring list (the boot
broadcast can reach the app up to a minute after boot). Log: `BootReceiver` lines and
`Persistent identity loaded. Public key: <the SAME hex as in Test 1>`. With "Stop AirPlay" active
the service must **not** start on boot.

---

## 12. Long run and resource usage

30 minutes of continuous mirroring (video + sound), then 30 minutes of music. Sample every 5 minutes:
```bash
for i in $(seq 0 13); do
  { echo "=== $(date +%T)"
    adb -s "$TV" shell top -b -n 1 | grep -i aynacast                       # CPU %
    adb -s "$TV" shell dumpsys meminfo "$PKG" | grep -E "TOTAL|Native Heap|Java Heap"
    echo "crash: $(adb -s "$TV" logcat -b crash -d | wc -l)"; } >> tv-logs/12-resources.log   # crash buffer (should stay 0)
  sleep 300
done
```
**Expected:** no crash/ANR; memory does not keep growing (first and last samples close); reasonable CPU
on a still screen (write the values measured while mirroring into the table). Picture and sound do not drift over time.

---

## Troubleshooting

- **The TV is not listed:** send Test 2's `dns-sd` output and `tv-logs/01-startup.log`. Check that the router
  has no "AP/client isolation" and does not block multicast/mDNS.
- **Black screen:** look for `MediaCodec started` and `Rendering video successfully` lines; note the codec name.
- **Lag / stutter:** check `software=` in the `MediaCodec started` line (or `• SW` on screen) and count
  `Decoder is 120 frames behind`. If the software decoder is in use, the `ACodec` / `OMXNodeInstance` lines
  just before `Failed to configure and start MediaCodec` name the setting the hardware decoder rejected
  (e.g. `decoder can not set low-latency to 1`).
- **YouTube does not play:** send `07b-youtube.log`. The only known suspect: the upper word of the AirPlay
  feature bits (`0x1E`) differs from UxPlay (`0x0`); try it in `AirPlayFeatures.kt` if needed.
- **Screenshot:** `adb -s "$TV" exec-out screencap -p > tv-logs/screen.png`
  (on some TVs the video plane, and screens during window animations, come out black; that is not a bug).
- **Crash:** `adb -s "$TV" logcat -b crash -d > tv-logs/crash.log` and if possible `adb -s "$TV" bugreport tv-logs/bugreport.zip`.

---

## Known open issues

### Audio: crackle and stutter (Mac and iPhone) – jitter buffer added, retest on Android 8

**Symptom:** crackle and short gaps. On the Philips it was heard in the first 10–15 seconds; on the
Arçelik (Android 11) it went on for the whole session.

**Cause:** audio packets arrive in Wi-Fi **bursts** (gaps of up to ~290 ms were measured on the
Arçelik) and were written straight into a ~160 ms `AudioTrack` with `WRITE_NON_BLOCKING`: a burst
overflowed it (audio silently dropped), the gap after it drained it (underruns). Nothing absorbed the
sender's clock drift either.

**Fix (`PcmJitterOutput`, default mode only):** the network thread appends decoded PCM to a queue; a
writer thread feeds a small `AudioTrack` with blocking writes, so the TV's clock paces playback. The
queue level adapts (+40 ms per refill, −10 ms per 30 quiet seconds; screen mirroring audio (AAC-ELD)
60–400 ms starting at 120 ms, music (ALAC / AAC-LC) 400–800 ms starting at 400 ms), drift is
corrected one sample frame per 10 ms chunk, extra latency left by refill pauses is cut after 2 s
(5 ms fade-in), and an output that stops consuming is rebuilt. The timestamp-sync mode keeps its
own path.

**Reading the log** (`Audio output:` every 5 s): `rebuffers` and `track underruns` should stay at +0
after the first ~5 seconds, `overflow` at 0 B. `max arrival gap` shows the Wi-Fi gaps; `target`
follows them.

| TV | Before | After |
|----|--------|-------|
| Arçelik, Mac mirroring | loss in every 5 s window (e.g. underruns +5, 45 KB dropped) | first ~5 s: 4 refills; then 3 min with no loss; latency ≈ 280 ms queue + 80 ms track |

**Still to check:** the Philips (Android 8), where earlier buffering attempts failed (an `AudioTrack`
there does not start until its buffer is full, and once stopped consuming after about a minute);
iPhone music (ALAC); whether the ~0.35 s audio delay is acceptable for lip-sync (otherwise use the
sync setting, which delays the picture to match).

### Mac Music app → TV: classic AirPlay (RSA) through a second entry

To the main receiver the macOS Music app only speaks **FairPlay version 2** (`Rejecting malformed
FairPlay key message (version byte 02, …)` → "unknown error 400"), with the PIN on or off and whatever
`et` or feature bits are advertised; no open-source receiver implements FairPlay 2 (UxPlay issue #570).
To a classic-only `_raop` speaker it uses **RSA** (AirPort Express key) instead, so the TV also
advertises **"<name> (Mac)"**: `_raop` only, `et=0,1 ek=1`, MAC = the receiver's with the last bit
flipped (with the same MAC macOS merges it into the main receiver). `AirPortRsa` signs the
`Apple-Challenge` and unwraps the ANNOUNCE `rsaaeskey`; ALAC, the jitter buffer and NTP are shared.

**Steps:** Mac Music → AirPlay → **"Arcelik Android TV (Mac)"**; play over a minute; move the volume
slider in the AirPlay menu.

**Expected log:** `Answered the classic AirPlay Apple-Challenge`, `Classic AirPlay ANNOUNCE from 'Mac':
ALAC 352 frames/packet, 44100 Hz, RSA-encrypted`, `Classic AirPlay SETUP: …`, `Sender volume changed`
values that follow the slider, `Audio output: … target 400 ms … rebuffers +0`.

**PIN on:** the entry advertises `pw=true`; the first ANNOUNCE gets `401` with a Digest challenge, the
Mac shows a password prompt and the TV shows a code at the same time; the code typed as the password is
checked (`Classic AirPlay sender paired with PIN`) and the Mac is remembered by its `DACP-ID` in the
paired devices list. Weaker than iPhone pairing: a DACP-ID proves nothing on the LAN.

**Result (13 Oct 2026, Arçelik):** ✅ plays; volume follows the AirPlay slider; ✅ PIN: password prompt +
on-screen code, then plays, song changes not asked again. Classic senders send no `/feedback`, so their
session lives on audio packets (`kClassicIdleTimeout`, 120 s; it ended after 15 s before). The first song's
artwork can be missing: the Mac sent `Cover art received (0 bytes, image/none)` and nothing later (the
next song's came at once). iPhone music and mirroring through the main entry unchanged.

### Test 12 (long run / resource usage) not done yet

Skipped in the 12 October 2026 run. Only an idle baseline exists: 0% CPU, ~33 MB total memory (PSS),
6.4 MB native heap, 5.3 MB Java heap, empty crash buffer. Sample every 5 minutes (memory growth, CPU,
crashes) during 30 min of mirroring + 30 min of music with the loop in Test 12.

### Not tried in this run

- **5e** (a second Apple device taking over the session) – no second device was used.
- **9, "On" mode** – only the default "Off" mode was checked (audio and video in sync on the iPhone).
- **10** – not applicable on this TV: Android 8 does not restrict background activity starts (Android 10+ does).

---

## Partial run: 13 October 2026

Arçelik Android TV (MStar M7322, 4 cores, Android 11, armeabi-v7a, 1080p @ 60 Hz) · MacBook Air · iPhone

| Test | Result | Notes |
|------|--------|-------|
| 1 Installation and service | ✅ | Release APK (armeabi-v7a) |
| 2 Network visibility | ✅ | `_airplay` and both `_raop` entries listed (main + "(Mac)"); TXT `features=0x5A7FFFF7,0x1E`, `model=AppleTV5,3`, same `pk`, `pw=true` (PIN was on); 7000/7100 open; `/server-info` features 639, AppleTV3,2 |
| 3 iPhone mirroring | ✅ | Hardware decoder, portrait 500x1080, avg 47 fps (min 30), up to 5.6 Mbps, no drops; lock/unlock pauses and resumes. A YouTube video started during mirroring is handed over to video casting and plays fine. Mirroring **audio** from the iPhone not checked (same AAC-ELD path as the Mac) |
| 4 Mac mirroring | ✅ | Fixed: `OMX.MS.AVC.Decoder` rejects `KEY_LOW_LATENCY`, Android 11 then fails `configure()` and the software decoder (~40 fps, 467 dropped frames per minute) lagged visibly. Now hardware, 42–60 fps at 2–3 Mbps, no drops. Fixed: constant audio crackle (jitter buffer, see the audio issue above) |
| 5a–5d Session resilience | ✅ (5c fixed) | 5a TEARDOWN, 5b ended after 15 s, 5d picture back with GOP replay. 5c failed at first: the iPhone could not find the TV after rejoining Wi-Fi (the TV receives no multicast, also after a reboot). Fixed with "Refresh Visibility" (re-announce every 60 s while idle): the TV appeared after ~30 s |
| 5e–5h Session resilience | ✅ | 5e Mac took over from the iPhone (`another sender took over`); 5f a connection to 7100 from another address (the TV's own shell) was ignored, mirroring went on at 41–50 fps; 5g `stopped on the receiver`, TV still listed; 5h service stopped and restarted, records registered again (the moment it was gone from the list was not observed) |
| 6 Music + Now Playing | ✅ | Apple Music (ALAC) through the jitter buffer, see 4f below |
| 7a Safari / URL video | ✅ / ⚠️ | Remote play/pause (`KEYCODE_MEDIA_PLAY_PAUSE`, scan 164) and OK reach the player and are reported to the iPhone; iPhone pause/play is followed. Started from Safari, the iPhone keeps a screen mirroring session open and several times ended the cast itself after 6–24 s (`/rate 0` + `/stop`, then mirroring again) while the TV was playing normally |
| 7b YouTube | ✅ | |
| 7c DRM | ✅ | Apple TV+: `Video casting requested` (FairPlay HLS), ExoPlayer `UnsupportedDrmException` ~18 s later, "protected" card on the TV, no crash |
| 8 PIN pairing | ✅ | Pairing after removing the device; with the permission the PIN screen opens over YouTube |
| 9 Audio/video sync (On) | ✅ | Mac mirroring: lip sync good by ear; HW decoder, avg 45 fps, no drops; old direct-AudioTrack path: 0 underruns, buffer at 600–680 of 700 ms (≈0.6 s latency), small overflow drops (13 KB at the start, then 1–3 KB now and then). If it crackles, the 700 ms track is the first thing to raise |
| 11 Boot + persistent identity | ✅ | `BootReceiver` started the service ~45 s after boot completed (other apps' boot broadcasts first); same public key `9fd6a4ed…`; iPhone sees the TV without opening the app; the paired Mac plays without being asked for the code again |
| 10 Background launch fallback | ✅ (fixed) | Without the permission Android aborts the start (`Abort background activity starts`) and the fallback notification is posted but **never shown**: Android TV only lists it in the notification panel. Added: a toast over the app in use (the PIN code for ~10 s, or "… is mirroring; … open AynaCast TV") and a permission dialog when the app is opened without it |

Not run on this TV: 12 (skipped on purpose). Mac Music app: through "Arcelik Android TV (Mac)" (see below).

**Discovery:** chronic on this TV (also after a reboot): it receives no multicast/broadcast on Wi-Fi.
The "Refresh Visibility" setting (on by default) re-registers the mDNS records every 60 s while idle
(`Re-announcing the receiver over mDNS (visibility refresh)`), so a phone that rejoins finds it within
a minute. Earlier: the TV once stopped receiving any multicast/broadcast on Wi-Fi (iPhone could not see it,
the Mac only from its cache; not even Chromecast answered queries). Turning the TV's Wi-Fi off and on
fixed it; afterwards only part of the queries were answered. A TV / router issue, not the app; if the
TV disappears from the iPhone, check with `dns-sd -B _airplay._tcp` and reconnect the TV's Wi-Fi.

### Changes made on Android 11 – compare on Android 8

Commits `863f2c9`, `6493ed3`, `3ddc418` were developed and measured only on the Arçelik (Android 11).
The Philips (Android 8) last ran the build before them; rerun Tests 3, 4 and 6 there and fill in the
last column.

| # | Change | Android 11 (Arçelik, Mac mirroring) | Android 8 (Philips) |
|---|--------|-------------------------------------|---------------------|
| 1 | `KEY_LOW_LATENCY` only when the decoder advertises `FEATURE_LowLatency` | Hardware decoder `OMX.MS.AVC.Decoder` instead of software; 42–60 fps, 0 dropped frames (before: ~40 fps, 467 drops/min) | Key is Android 11+ only, so no change expected: `MediaCodec started` line still shows the MTK hardware decoder, `software=false` |
| 2 | Hardware decoder retried without optional keys before the software fallback; failed codec released | Not triggered (`minimalFormat=false`) | `minimalFormat=` value: |
| 3 | `• HW` / `• SW` in the player's info box | `• HW` | Shows: |
| 4 | Audio jitter buffer (`PcmJitterOutput`), default mode | After the first ~5 s: `rebuffers +0`, `track underruns +0`, `overflow 0 B` for 3 min; crackle gone | Crackle (Mac / iPhone music): |
| 4a | – first seconds | 4 refills in the first 5 s, target rises 120 → 280 ms, excess trimmed within ~10 s | Refills / target: |
| 4b | – latency | ≈ 280 ms queue + 80 ms track ≈ 0.35 s; lip-sync acceptable by ear | Queue / track ms, lip-sync: |
| 4c | – Wi-Fi gaps (`max arrival gap`) | Usually ~80 ms, peaks 190–335 ms | Peaks: |
| 4d | – output stall (`Audio output stalled`) | Never | Seen? Sound after 1+ minute? (the earlier 400 ms attempt froze here) |
| 4e | – track start (Android 8 starts an `AudioTrack` only once its buffer is full) | – | Sound starts at once? |
| 4f | – iPhone Apple Music (ALAC, Test 6) | Music profile (target starts at 400 ms): 1 refill in the first ~30 s (Wi-Fi gaps up to 488 ms), then no loss. With the mirroring profile it had 6 | Refills / crackle: |

**Command for the audio numbers:**
`grep "Audio output" tv-logs/<test>.log` – one line every 5 seconds.

---

## Last run: 12 October 2026

Philips TPM171E (MediaTek, Android 8.0, armeabi-v7a, 1080p @ 50 Hz output) · iPhone 13 Pro · MacBook Air

| Test | Result | Notes |
|------|--------|-------|
| 1 Installation and service | ✅ | |
| 2 Network visibility | ✅ | |
| 3 iPhone mirroring | ✅ | Fixed: MTK hardware decoder failure (PTS spacing), corruption on fast scrolling, squashed landscape (GPU letterbox) |
| 4 Mac mirroring | ✅ / ⚠️ | Picture correct in both modes; audio: see the open issue above |
| 5a–5f Session resilience | ✅ | 5e not tried; 5b ≈ 15 s |
| 6 Music + Now Playing | ✅ | Volume indicator and "Show Now Playing" added |
| 7a Safari / URL video | ✅ | Fixed: a pause on the remote was not reported to the iPhone |
| 7b YouTube | ✅ | 5 s wait for autoplay at the end of a video; the 50 Hz output skips frames of 60 fps videos |
| 7c DRM | ✅ | No crash; "This video is protected" card |
| 8 PIN pairing | ✅ | Paired device list (name, date, remove one by one) |
| 9 Audio/video sync | ✅ | Default mode |
| 10 Notification fallback | – | Not applicable on Android 8 |
| 11 Boot + persistent identity | ✅ | Service up ~55 s after boot (system broadcast order) |
| 12 Long run / resources | – | Not done (see above) |

No crash records during the run.

---

## Results table (fill in and send back)

TV model / Android version: ……………… · iPhone model / iOS: ……………… · Mac / macOS: ………………

| Test | Result (✅/❌/–) | Notes (latency, fps, observations) |
|------|------------------|------------------------------------|
| 1 Installation and service | | |
| 2 Network visibility | | |
| 3 iPhone mirroring | | |
| 4 Mac mirroring | | |
| 5a–5h Session resilience | | |
| 6 Music + Now Playing | | |
| 7a Safari / URL video | | |
| 7b YouTube | | |
| 7c DRM (expected: not supported) | | |
| 8a–8h PIN pairing | | |
| 9 Audio/video sync | | |
| 10 Notification fallback | | |
| 11 Boot + persistent identity | | |
| 12 Long run / resources | | |

Send: this table + the whole `tv-logs/` folder.
