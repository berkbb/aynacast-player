# Third-Party Notices

AynaCast TV is licensed under the **GNU General Public License v3.0** (see [LICENSE](LICENSE)).
It includes the following third-party code, under licenses compatible with the GPL-3.0:

| Component | Location | License | Source |
|-----------|----------|---------|--------|
| playfair (FairPlay SAP v3 handshake) | `app/src/main/cpp/playfair/` | GPL-3.0 ([LICENSE.md](app/src/main/cpp/playfair/LICENSE.md)) | [EstebanKubata/playfair](https://github.com/EstebanKubata/playfair), via [RPiPlay](https://github.com/FD-/RPiPlay) / [UxPlay](https://github.com/FDH2/UxPlay) |
| ALAC decoder | `app/src/main/cpp/alac.c`, `alac.h` | MIT-style (Copyright (c) 2005 David Hammerton; notice in the file) | David Hammerton's ALAC decoder, as used by shairport / RPiPlay |
| tiny-AES-c | `app/src/main/cpp/aes.c`, `aes.h` | The Unlicense (public domain) | [kokke/tiny-AES-c](https://github.com/kokke/tiny-AES-c) |
| TweetNaCl | `app/src/main/cpp/tweetnacl.c`, `tweetnacl.h` | Public domain | [tweetnacl.cr.yp.to](https://tweetnacl.cr.yp.to/) |

Protocol behaviour (RTSP handshakes, NTP timing, audio buffering, PIN pairing, video casting) follows
[UxPlay](https://github.com/FDH2/UxPlay) and [RPiPlay](https://github.com/FD-/RPiPlay) (both GPL-3.0);
classic AirPlay (RSA challenge, SDP `ANNOUNCE`, HTTP Digest password) follows
[shairport-sync](https://github.com/mikebrady/shairport-sync).

## AirPort Express RSA key

`app/src/main/java/com/babadogan/aynacast/tv/engine/AirPortRsa.kt` contains the RSA key that classic
AirPlay senders expect from an AirPort Express. It has been public since 2011 and is used, for
interoperability, by shairport-sync and other open-source receivers. It is not used to decrypt any
DRM-protected content.

## Libraries

Android libraries (AndroidX, Jetpack Compose, Media3/ExoPlayer, Kotlin) are fetched by Gradle under
their own licenses (mostly Apache License 2.0), which are compatible with the GPL-3.0.
