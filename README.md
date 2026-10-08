# Zerion

**No identity. Encrypted. Post-quantum on every message.**

<p align="center">
  <img src="zerion-android/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" width="120" height="120">
</p>

Zerion is a private messenger with an encrypted vault and optional self-custodial Bitcoin and Monero wallets. There is no phone number, no account and no Zerion server: online messaging runs between the two devices' Tor onion services, every message carries a fresh post-quantum encapsulation, and traffic within a connection is shaped into fixed-size frames at a paced, cover-filled cadence. It is free software under the GPLv3.

This README describes the current release, **3.0.17**. Release history is in [CHANGELOG.md](CHANGELOG.md); the exact values for the current release (version, artifact hash, channels, platform status) are in [docs/release-manifest.json](docs/release-manifest.json).

## Architecture

- **ZTP** runs Tor, publishes each device's v3 onion service, dials contacts' onions and accepts inbound connections.
- **ZWF** frames every connection into fixed 4096-byte authenticated frames.
- **ZPP** paces the frames: one frame per interval, a cover frame when there is nothing to send.
- **ZMM** carries application records (messages, group records, call signalling, acknowledgements) inside the frames. Channels do not use this stack: channel requests go over their own Tor connections to the channel's onion service.
- **Mode 3-Full** is the message ratchet: a forward-secret symmetric chain per stream with a fresh ML-KEM-768 encapsulation mixed into every application frame (the opening cover frames a side sends before it has the peer's key carry none).
- **Bluetooth mesh** (opt-in) carries sealed-sender envelopes between nearby phones with no internet.
- **I2P** (development builds only, opt-in there) is a second carrier for the same frame stream; the published release does not contain it.

Each device holds its own contacts, messages, keys and state in an encrypted database. No Zerion server keeps a copy. Apart from what you send to your contacts, data leaves the device only in backups, account transfers and vault exports or shares that you start, and, when you use the Bluetooth mesh, as sealed envelopes carried by nearby phones. The protocol documents are in [docs/protocol/](docs/protocol/README.md) and the full description in the [technical whitepaper](docs/ZERION_TECHNICAL_WHITEPAPER.md).

## Privacy model

- No phone number, email or registration. Contacts are added by exchanging a link or scanning a code; identities are cryptographic keys.
- No central Zerion messaging server, directory, relay or message store. Messages travel between the two devices through Tor relays (guard, middle, rendezvous). The app also uses the Tor network, Tor bridges if configured, Electrum servers and Monero nodes for the wallets (default set in code, user-replaceable), a price endpoint over Tor, and the download stores; I2P routers only in development builds with I2P enabled.
- Tor hides each device's network location from contacts and from the relays. It does not hide that the device uses Tor, when connections open and close, or defeat an adversary who can watch the whole network. The [threat model](docs/ZERION_TECHNICAL_WHITEPAPER.md#2-threat-model) states these limits.
- Zerion runs no messaging, account or relay servers, so the project holds no messages, contact lists or account data. What a contact or a Tor relay can observe is part of the threat model.

## Cryptography

| Purpose | Primitive |
|---|---|
| Message AEAD on the wire | XSalsa20-Poly1305 (24-byte nonce, 16-byte tag) |
| Post-quantum key encapsulation | ML-KEM-768 |
| Classical key agreement | X25519 |
| Signatures on the pairing contact record and on channel, group, introduction and mesh records | ML-DSA-65 + Ed25519 (hybrid; both halves must verify, with the exceptions listed in [SECURITY_CLAIMS.md](docs/protocol/SECURITY_CLAIMS.md)) |
| Hashing, MAC and key derivation in the messaging core | keyed BLAKE2b-256 |
| Message ratchet | Mode 3-Full: per-message ML-KEM-768 over a forward-secret chain; sender key pair rotates every 16 own sends |
| Calls and vault AEAD | AES-256-GCM |
| Vault chunk keys, call endpoint keys | HKDF-SHA256 |
| Passwords | Argon2id (database key 64 to 256 MiB adapted to the device; vault 256 MiB; wallet 64 MiB), PBKDF2-HMAC-SHA256 for the duress password and the Bitcoin section credential |
| Identifiers and fingerprints | SHA-256 (SHA-512 in the Tor rendezvous derivation, SHA3-256 for the ML-KEM key-seed hash) |
| Database at rest | SQLCipher (AES-256), key derived from the password and a device-bound keystore factor |

The canonical list with the source file behind each row is [docs/crypto-primitives.json](docs/crypto-primitives.json). Post-compromise security rests on the ML-KEM layer: the X25519 field carried in each frame is authenticated but drives no ratchet (whitepaper §6.3). It applies to the per-connection ratchet state only: the long-term contact root key that seeds every connection is never rotated, so an attacker who copies it can authenticate as either party of that contact pair until the two re-pair (whitepaper §11).

## Traffic analysis protections

Within a live connection every frame is 4096 bytes and a real frame is indistinguishable from a cover frame. Frames are sent one per interval with zero-mean jitter: 750 ms while messages flowed in the last two minutes or are queued, 4 s afterwards, on every network type. Within a rate an observer cannot tell which frames carried data; the switch between the two rates reveals the coarse onset and end of activity, and because the active rate lasts while records are queued, the length of an active period gives a rough bound on how much was sent (a frame carries at most 1698 bytes of payload). Records this side queues in reply to the peer (acknowledgements, requests) also select the active rate, so a peer that keeps sending can keep this side at the active rate. A queued message dials the contact at once, so the opening of a connection can coincide with a send. Calls and channel requests do not use this pacing. Connection existence, lifetime and reconnects are visible to an observer of the Tor link, and a global observer is out of scope.

## Calls

Voice calls (on by default) and video calls (off by default) run between the two devices over Tor onion services: no VoIP server, no STUN or TURN. Audio is uncompressed 16 kHz mono PCM (256 kbit/s) in fixed 20 ms frames, so no codec-dependent frame size can leak speech patterns; video is H.264 in padded frames. Call media is designed to be encrypted with AES-256-GCM under a per-call key on top of the Tor layer. **In 3.0.11 that per-call key is derived incorrectly** (the derived key material is zeroed before use, so the application-layer cipher adds no confidentiality or integrity), and video nonces can repeat when video is stopped and restarted within a call. In 3.0.11 call confidentiality therefore rests on the Tor onion-service encryption between the two devices. We have no indication that this was exploited; using it would require an adversary inside the Tor connection or at an endpoint. Both defects are fixed since 3.0.12. Details: [SECURITY.md](SECURITY.md).

## Pairing

A contact is added by link (the normal path) or nearby (QR code or Bluetooth). Link pairing meets at a rendezvous derived from the link and runs a hybrid key agreement: static and ephemeral X25519 agreements, including the static-to-ephemeral terms that resist key-compromise impersonation, plus ML-KEM-768 encapsulations to the peer's ephemeral key and to the peer's committed static key. The static ML-KEM encapsulation makes the authentication post-quantum: completing the handshake requires the static ML-KEM private key behind the out-of-band commitment, a peer holding only the classical half cannot pair, and a peer offering the earlier, classically authenticated handshake version is refused. Nearby pairing (QR code or Bluetooth) is a hybrid X25519 plus ML-KEM-768 key agreement bound to the QR commitment (key-agreement protocol version 5). A post-quantum contact cannot later be re-added as classical, and the pairing key rotates after every completed contact addition. Since 3.0.12 both pairing paths are authenticated with the post-quantum key as well; 3.0.11 and earlier authenticated link pairing classically and paired nearby contacts classically ([SECURITY.md](SECURITY.md)).

## Vault

A separate encrypted store for passwords, notes, documents and media, locked with its own password. The key is derived with Argon2id and combined with a random secret wrapped by a key in the Android Keystore (StrongBox where the device has it), so the vault is bound to the device: neither the password alone nor a copied data directory alone opens it. Items are AES-256-GCM encrypted, vault screens block screenshots by default, and there is no recovery path.

## Bitcoin and Monero

Optional wallets inside the vault, each sealed under its own password. Keys are generated on the device and never leave it. Bitcoin: BIP84 native SegWit through an Electrum client, fresh address per receive, coin control, and a single-use send gate that broadcasts exactly the reviewed transaction. Monero: Monero's `wallet2` built from pinned source, a view-only wallet in normal use, with the spend-capable wallet opened only for a send and closed when the send ends, is cancelled, waits at review for three minutes or the vault locks, fresh subaddresses on receive. Wallet traffic goes over Tor by default with per-wallet and per-purpose circuit isolation and no silent clearnet fallback; a direct node is an explicit opt-in behind a warning and exposes the device address to that node. See [docs/WALLET_ARCHITECTURE.md](docs/WALLET_ARCHITECTURE.md).

## Network

Tor is the only internet transport for messaging and runs whenever the app is online; online messaging never bypasses it. Offline mode stops Tor together with all internet messaging. By default every connection the app makes goes through Tor. Explicit opt-ins that do not: a direct or local wallet node (a direct node learns the device address) and, in development builds only, I2P participation (when enabled, the user's network can see that the device uses I2P, although the reseed goes through Tor by default). The Bluetooth mesh is local radio: it hides content, not proximity. The onion address rotates on a schedule announced to contacts; in 3.0.11 the announced address was not republished after a restart in every case; corrected since 3.0.12.

### Tor client authorization

Every pair of contacts whose apps both support it moves from the open onion service to a second, client-authorized onion service (Tor v3 client authorization): each side generates a random X25519 dialing key for the other, publishes the other's public half on its own authorized service and hands the private half to Tor as a credential for the other's service. Anyone who learns the authorized address but holds no credential cannot decrypt its descriptor and cannot connect. Once both sides have proven the authorized path and committed, the pair uses the authorized address only, with no fallback to the open one; removing a contact revokes their key and rotates the address for the remaining contacts. The open service stays published for contacts whose app does not support this yet. Design and wire format: [docs/protocol/ONION_CLIENT_AUTH.md](docs/protocol/ONION_CLIENT_AUTH.md). Client authorization shipped in 3.0.12; 3.0.11 and earlier do not have it.

## Platforms

| Platform | Status | Version |
|---|---|---|
| Android 10 and later | AVAILABLE | 3.0.17 on [GitHub](https://github.com/zerionproject/Zerion/releases/latest); [Google Play](https://play.google.com/store/apps/details?id=com.professor.zerion) carries 3.0.14 until the newer bundle passes review; [F-Droid](https://f-droid.org/packages/com.professor.zerion/) offers 3.0.3 while the update to the current release is pending |
| Windows 10 and 11 (x64) | AVAILABLE | 1.0.1, [Zerion Desktop](https://github.com/zerionproject/Zerion-Desktop/releases/latest), a separate codebase |
| Linux (x64, aarch64 Flatpak) | AVAILABLE | 1.0.1, Zerion Desktop |
| macOS | IN DEVELOPMENT | none published |
| iOS | IN DEVELOPMENT | none published |

APK signing certificate SHA-256: `D7FDB11125890D133AE89D8BA4F4331D9045E21EF01D9899A7CDEE6888F704C8`. The desktop client has its own documentation and security posture in its repository.

## Security reviews

Zerion has received independent focused security reviews (the Monero wallet native integration and the Bitcoin wallet with the dormant PayJoin component, both by ZeroTrace; the 3.0.6 transport properties; one reported ratchet issue) and internal assessments. It has not received an independent security assessment or penetration test of the whole product. The full history with dates, scope, outcome and publication status, the terms used, and the known limitations of the current release are in [SECURITY.md](SECURITY.md). [docs/protocol/SECURITY_CLAIMS.md](docs/protocol/SECURITY_CLAIMS.md) lists every security claim with the code and test behind it.

## Build

JDK 21 and the Android SDK (API 36, NDK r27b for the Monero library). The Monero library is built from pinned source by `packaging/monero-android/`; see [docs/FDROID.md](docs/FDROID.md).

```
./gradlew :zerion-android:assembleOfficialDebug
./gradlew :zerion-core:test :zerion-app:test :zerion-android:testOfficialDebugUnitTest
./gradlew clean :zerion-android:assembleOfficialRelease -Pfdroid
```

The release build runs the zero-log source gate, the native hash gates and the wallet regression tests. Dependency versions are strictly locked, and a build from this repository verifies every dependency by checksum and signature under `gradle/verification-metadata.xml`. The reference F-Droid build removes that file, and the published APK is the signed output of that build, so for the published APK dependency integrity rests on the locked versions, HTTPS downloads and the release-time comparison with an independent build that keeps strict verification. Restoring verification in the F-Droid recipe is planned; see [docs/FDROID.md](docs/FDROID.md) and [CONTRIBUTING.md](CONTRIBUTING.md).

## Security reporting

Report vulnerabilities through a private GitHub security advisory or to `support@zerion.chat`; see [SECURITY.md](SECURITY.md).

## Documentation

- [Overview](docs/ZERION_OVERVIEW.md), [Technical whitepaper](docs/ZERION_TECHNICAL_WHITEPAPER.md) (architecture, threat model, cryptography, limitations)
- [Protocol index](docs/protocol/README.md): [ZTP and ZPP](docs/protocol/ZTP-ZPP.md), [ZWF and Mode 3-Full](docs/protocol/ZWF-MODE3FULL.md), [Sealed-sender envelope](docs/protocol/ASYNC-SEALED-SENDER.md), [Mesh transport](docs/protocol/MESH-TRANSPORT.md), [Embedded I2P](docs/protocol/EMBEDDED-I2P.md)
- [Security claims](docs/protocol/SECURITY_CLAIMS.md), [Claims matrix (protocol invariants)](docs/protocol/SECURITY_CLAIMS_MATRIX.md), [SECURITY.md](SECURITY.md)
- [Mesh and I2P](docs/ZERION_MESH_AND_I2P.md), [Wallet architecture](docs/WALLET_ARCHITECTURE.md), [Bitcoin](docs/BTC_ARCHITECTURE.md), [Monero](docs/XMR_ARCHITECTURE.md), [Wallet security invariants](docs/WALLET_SECURITY_INVARIANTS.md)
- [F-Droid and native builds](docs/FDROID.md), [Native provenance](packaging/monero-android/PROVENANCE.md)

How large language models are used in development, and what they are not used for: [zerion.chat/blog/llm-use-in-zerion.html](https://zerion.chat/blog/llm-use-in-zerion.html).

## Support

Zerion has no investors, no ads, no subscription and no telemetry. Donations fund development, security reviews and infrastructure. The addresses below are the ones published on [zerion.chat/donate.html](https://zerion.chat/donate.html).

Bitcoin: `bc1q5hfmyzkadwww9r96sff2ew36ctksmyapucx4kq`

Monero: `89GAQXYpdb13ReGi1c86PrFqxheEBfoB3ekoSL1AWUcV9DfH9PKnfaRRmoispTUSymKK3ykPK4tdYX1uiLxTNjPC8eGX9V4`

Ethereum / USDT (ERC-20): `0x8F639ec074a4d89546e61bDd84F081EE61E1FCF6`

## License

Zerion is free and open-source software under the GNU General Public License v3.0; see [LICENSE.txt](LICENSE.txt). Third-party software notices and attribution are in [NOTICE.md](NOTICE.md).

## Releases

[GitHub Releases](https://github.com/zerionproject/Zerion/releases) carry every version's APK and notes; [CHANGELOG.md](CHANGELOG.md) is the release history.
