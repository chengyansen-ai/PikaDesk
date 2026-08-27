# PikaDesk notices

PikaDesk 0.1.0-dev is an independent derivative work based on TCHESS.

## Upstream source

- Project: TCHESS / public-Xiangqi
- Source: https://github.com/sojourners/public-Xiangqi
- Pinned base commit: `2d41525095639548059ebd930b0af4d29efc1364`
- License file supplied by upstream: GNU General Public License version 3
- Conservative project identifier: `GPL-3.0-only`

PikaDesk modifications are distributed under the same GPL v3 terms. Copyright remains with the respective upstream and PikaDesk contributors.

## Independence and excluded material

PikaDesk is not affiliated with, endorsed by, or a product of Shark Chess, TCHESS, or the official Pikafish project. No Shark Chess executable, private source code, paid-license material, model, UI asset, or branding is included.

Pikafish engine binaries and NNUE networks are not currently bundled. If they are added later, their exact official source, version, license terms, and SHA-256 must be recorded before packaging.

## Release gate

The inherited `yolov11.onnx` model identifies its export stack and AGPL-3.0 license in embedded metadata, but its custom training data and training script are not documented. The inherited `chessman.ttf` font has no separate author or license record. These unresolved provenance items are development-only and block release packaging until confirmed or replaced.

See `docs/third-party.md` and `docs/bundled-resources.sha256` for the complete audit record.
