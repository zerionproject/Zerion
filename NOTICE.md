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

The application depends on these libraries published by the Briar Project (GPLv3 unless stated): `jtorctl` (BSD), `dont-kill-me-lib`, `null-safety`. Up to 3.0.17 it also packaged the lyrebird executable from the Briar Project's `lyrebird-android` 0.6.2; from 3.0.18 lyrebird is built in this repository from the upstream source (see below) and that package is no longer used.

The embedded Tor process is run by `org.zerionproject.tor`, which is derived from the Briar Project's `onionwrapper` 0.1.4 (GPLv3) and modified by the Zerion Project: the Tor and lyrebird executables are verified against pinned hashes before they run, the SOCKS listener is isolated and connection padding enabled in the generated configuration, process shutdown is bounded, a start that fails or is interrupted leaves no process behind, and all logging is removed. The published library is no longer used.

## Other third-party components

- Tor (BSD), built from the pinned official release source into `libtor.so` by [packaging/tor-android](packaging/tor-android/PROVENANCE.md) with a build makefile from the Guardian Project (BSD, vendored there).
- lyrebird (Copyright the Tor Project and Yawning Angel; BSD 3-clause, except the Elligator2 X25519 package `internal/x25519ell2` and the meek_lite transport `transports/meeklite/transport.go`, which are GPLv3 or later, so the executable as a whole is under GPLv3 or later; lyrebird's LICENSE file also carries a BSD 3-clause licence of the Go Authors, for its SOCKS4 proxy code `cmd/lyrebird/proxy_socks4.go`, which is derived from Go's sources), built from the signed upstream release tag 0.9.0 into `liblyrebird.so` by [packaging/lyrebird-android](packaging/lyrebird-android/PROVENANCE.md). The executable statically links the Go runtime and standard library (BSD 3-clause, the Go Authors) and these Go modules, at the versions in lyrebird 0.9.0's `go.sum` and recorded in the build information of each executable, each under its own licence:
  - BSD 3-clause: `filippo.io/edwards25519`, `github.com/google/uuid`, `github.com/miekg/dns`, `github.com/refraction-networking/utls`, `github.com/wlynxg/anet`, `gitlab.com/yawning/edwards25519-extra`, the Tor Project's `ptutil` and `snowflake/v2`, and `golang.org/x/crypto`, `x/net`, `x/sys`, `x/text` and `x/time` (with the Go project's additional patent grant).
  - BSD 2-clause: `github.com/pkg/errors`.
  - MIT: `github.com/andybalholm/brotli`, `github.com/klauspost/cpuid/v2`, `github.com/klauspost/reedsolomon`, `github.com/patrickmn/go-cache`, the Pion WebRTC modules (`github.com/pion/datachannel`, `dtls/v3`, `ice/v4`, `interceptor`, `logging`, `mdns/v2`, `randutil`, `rtcp`, `rtp`, `sctp`, `sdp/v3`, `srtp/v3`, `stun/v3`, `transport/v4`, `turn/v4`, `webrtc/v4`), `github.com/theodorsm/covert-dtls`, `github.com/txthinking/runnergroup`, `github.com/txthinking/socks5`, `github.com/xtaci/kcp-go/v5`, `github.com/xtaci/smux` and the Tor Project's `webtunnel`.
  - Apache 2.0: the AWS SDK for Go v2 modules (`github.com/aws/aws-sdk-go-v2` and its `config`, `credentials`, `feature/ec2/imds`, `internal/configsources`, `internal/endpoints/v2`, `internal/ini`, `service/internal/accept-encoding`, `service/internal/presigned-url`, `service/signin`, `service/sqs`, `service/sso`, `service/ssooidc` and `service/sts` modules) and `github.com/aws/smithy-go`, `github.com/golang/mock` and `github.com/tjfoc/gmsm`. The AWS SDK for Go carries the notice "AWS SDK for Go. Copyright 2015 Amazon.com, Inc. or its affiliates. All Rights Reserved. Copyright 2014-2015 Stripe, Inc." and smithy-go the notice "Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved."
  - BSD 3-clause, Apache 2.0 and MIT for different parts: `github.com/klauspost/compress`.
  - CC0 1.0: `github.com/dchest/siphash` and the Tor Project's `goptlib`.
  - BSD Zero Clause: `github.com/realclientip/realclientip-go`.
  - The full licence and notice texts of lyrebird, the Go standard library and runtime and every linked module, as the build reads them from the pinned sources, are in [docs/licenses/lyrebird-modules.txt](docs/licenses/lyrebird-modules.txt); the build stops when they differ from that file.
- Bouncy Castle (MIT): classical and post-quantum primitives.
- SQLCipher (BSD).
- bitcoinj (Apache 2.0).
- Monero `wallet2` (BSD), built from pinned source into `libzmonero.so`; see [packaging/monero-android/PROVENANCE.md](packaging/monero-android/PROVENANCE.md).
- Argon2 reference implementation (CC0 / Apache 2.0), vendored under `zerion-android/src/main/cpp/argon2/` with its [LICENSE](zerion-android/src/main/cpp/argon2/LICENSE).
- PhotoView (Apache 2.0), vendored under `zerion-android/src/main/java/com/github/chrisbanes/photoview/` with its [LICENSE](zerion-android/src/main/java/com/github/chrisbanes/photoview/LICENSE).
- Guardian Project panic-kit and trusted-intents (Apache 2.0), under `zerion-android/src/main/java/info/guardianproject/` with their [LICENSE.txt](zerion-android/src/main/java/info/guardianproject/LICENSE.txt).
- I2P router (public domain and other licences per component), shaded in `i2p-embedded/`.
- PngSuite test images (see `zerion-android/src/androidTestOfficial/assets/PngSuite/PngSuite.LICENSE`).
