# playfair

FairPlay SAP (version 3) handshake code used for the AirPlay `/fp-setup` exchange and to unwrap the
session key (`playfair_decrypt`).

- Origin: [EstebanKubata/playfair](https://github.com/EstebanKubata/playfair), as bundled in
  [FD-/RPiPlay](https://github.com/FD-/RPiPlay/tree/master/lib/playfair) and
  [FDH2/UxPlay](https://github.com/FDH2/UxPlay).
- License: **GNU General Public License v3.0** (see [LICENSE.md](LICENSE.md)). Because this code is
  part of the app, AynaCast TV as a whole is distributed under the GPL-3.0 as well.
- Only FairPlay version 3 is implemented; version 2 (sent by the macOS Music app) is not.
