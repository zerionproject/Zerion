# Notices

Zerion is free software under the GNU General Public License, version 3 (see [LICENSE.txt](LICENSE.txt)). This file records the third-party origin of parts of the source tree and of the libraries the application depends on, as the licences require. It is a provenance record, not a description of the current architecture; the current architecture is described in [README.md](README.md) and [docs/](docs/).

## Code derived from Briar (Bramble)

Zerion began in October 2025 as a modified copy of the Briar messenger (Copyright 2011 to 2014 Sublime Software Ltd and the Briar Project, GPLv3). Since then the Zerion Project has modified that code extensively; the modifications and their dates are recorded in this repository's git history, and the copyright line of the original work is preserved in [LICENSE.txt](LICENSE.txt). The following parts of the current tree still contain code that originates in Briar's Bramble library, moved to the `org.zerionproject` namespace and adapted:

| Module | Packages | What it is |
|---|---|---|
| `zerion-core-api`, `zerion-core` | `org.zerionproject.core.{db, settings, data, record, identity, contact, rendezvous, keyagreement, sync, plugin, lifecycle, event, properties, versioning, cleanup, reliability, account, battery, client, io, system, transport, crypto}` | database and settings layer, BDF encoding, record layer, identity and contact management, pairing rendezvous and handshake framework, nearby key agreement, message validation and delivery pipeline, plugin and lifecycle machinery, and the classical cryptographic component |
| `zerion-core-android` | `org.zerionproject.core.*` (Android bindings) | Android implementations of the above |
| `zerion-app-api`, `zerion-app` | `org.zerionproject.app.{messaging, introduction, client, attachment, avatar, autodelete, identity}` | messaging, introduction and client frameworks |
| `zerion-android` | parts of `com.professor.zerion.android` (activities, contact management, settings, login) | Android application code |

Everything else in the tree, in particular the online transport and wire format (`org.zerionproject.transport`, `org.zerionproject.crypto`, `org.zerionproject.sync.Zpp*`, `org.zerionproject.message`), the Mode 3-Full ratchet (`org.zerionproject.core.crypto.pcs`), the sealed-sender envelope and mesh (`org.zerionproject.core.crypto.async`, `org.zerionproject.transport.mesh`), the I2P carrier, channels, groups, calls, the vault, the wallets, multi-profile and the hardening features, is the Zerion Project's own work.

Zerion is an independent project and is not affiliated with or endorsed by the Briar Project.

## Libraries from the Briar Project

The application depends on these libraries published by the Briar Project (GPLv3 unless stated): `lyrebird-android` (packaged pluggable-transport binary), `jtorctl` (BSD), `dont-kill-me-lib`, `socks-socket`, `null-safety`.

The embedded Tor process is run by `org.zerionproject.tor`, which is derived from the Briar Project's `onionwrapper` 0.1.4 (GPLv3) and modified by the Zerion Project: the Tor and lyrebird executables are verified against pinned hashes before they run, the SOCKS listener is isolated and connection padding enabled in the generated configuration, process shutdown is bounded, a start that fails or is interrupted leaves no process behind, and all logging is removed. The published library is no longer used.

## Other third-party components

- Tor (BSD), built from the pinned official release source into `libtor.so` by [packaging/tor-android](packaging/tor-android/PROVENANCE.md) with a build makefile from the Guardian Project (BSD, vendored there); lyrebird (BSD) via the package above.
- Bouncy Castle (MIT): classical and post-quantum primitives.
- SQLCipher (BSD).
- bitcoinj (Apache 2.0).
- Monero `wallet2` (BSD), built from pinned source into `libzmonero.so`; see [packaging/monero-android/PROVENANCE.md](packaging/monero-android/PROVENANCE.md).
- Argon2 reference implementation (CC0 / Apache 2.0), vendored under `zerion-android/src/main/cpp/argon2/` with its [LICENSE](zerion-android/src/main/cpp/argon2/LICENSE).
- PhotoView (Apache 2.0), vendored under `zerion-android/src/main/java/com/github/chrisbanes/photoview/` with its [LICENSE](zerion-android/src/main/java/com/github/chrisbanes/photoview/LICENSE).
- Guardian Project panic-kit and trusted-intents (Apache 2.0), under `zerion-android/src/main/java/info/guardianproject/` with their [LICENSE.txt](zerion-android/src/main/java/info/guardianproject/LICENSE.txt).
- I2P router (public domain and other licences per component), shaded in `i2p-embedded/`.
- PngSuite test images (see `zerion-android/src/androidTestOfficial/assets/PngSuite/PngSuite.LICENSE`).
