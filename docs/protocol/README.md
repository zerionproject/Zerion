# Zerion Protocol Specifications

This directory documents the protocols that Zerion defines and implements. It
covers the wire formats, the cryptographic constructions, and the message flow
for each layer of the stack.

The first table lists the components whose code originates in Briar's
Bramble library (the provenance record is [NOTICE.md](../../NOTICE.md)); the
second lists Zerion's own protocol work. Bramble's transport and
synchronisation stack is not used: the online path is ZTP, ZWF, ZPP and ZMM,
and describing it as "Bramble transport" is wrong. Zerion is an independent
project and is not affiliated with or endorsed by the Briar Project.

## Components with Bramble-derived code

| Component | Where | Notes |
| --- | --- | --- |
| Database and settings layer | `zerion-core/.../core/db`, `core/settings` | SQLCipher-backed on Android; schema extended by Zerion |
| BDF data encoding | `core/data` | unchanged encoding rules |
| Record layer | `core/record` | used by the pairing handshake and the nearby key agreement; payload caps added by Zerion |
| Identity and contact management | `core/identity`, `core/contact` | extended with hybrid post-quantum identity keys, downgrade protection and rotating pairing keys |
| Pairing rendezvous and handshake framework | `core/rendezvous`, `core/contact/HandshakeManagerImpl` | extended with an ML-KEM-768 encapsulation to the peer's ephemeral key |
| Nearby key agreement (QR / Bluetooth) | `core/keyagreement` | BQP version 4; classical X25519 in 3.0.11 |
| Sync bookkeeping and validation pipeline | `core/sync` | message validation, delivery and dependency tracking; the Bramble sync transport is not used |
| Plugin and lifecycle machinery, event bus | `core/plugin`, `core/lifecycle`, `core/event` | hosts Zerion's transports |
| Tor onion wrapper | `onionwrapper/` | the Briar Project's onionwrapper library, vendored and pinned |
| Messaging, introduction and group frameworks | `zerion-app/.../messaging`, `introduction`, `client` | introductions carry hybrid signatures; groups are Zerion's `grouptr` |

## Zerion protocol components

| Component | Where | Document |
| --- | --- | --- |
| ZTP, the Tor transport seam | `zerion-core/.../transport/Ztp*` | ZTP-ZPP.md |
| ZWF, the fixed-size wire format | `zerion-core/.../crypto/ZwfMode3FullStream*`, `zerion-wire` | ZWF-MODE3FULL.md |
| ZPP, the paced cover-traffic scheduler | `zerion-core/.../sync/Zpp*` | ZTP-ZPP.md |
| ZMM, application records and fragmentation | `zerion-core/.../message` | ZTP-ZPP.md |
| Mode 3-Full, the per-message post-quantum ratchet | `core/crypto/pcs` | ZWF-MODE3FULL.md |
| Async sealed-sender envelope | `core/crypto/async` | ASYNC-SEALED-SENDER.md |
| Bluetooth mesh transport | `zerion-core/.../transport/mesh`, `zerion-android/.../mesh` | MESH-TRANSPORT.md |
| Embedded I2P carrier | `zerion-core/.../transport/i2p`, `zerion-android/.../i2p`, `i2p-embedded` | EMBEDDED-I2P.md |
| Onion address rotation | `zerion-core/.../transport/B4OnionRotation` | ZTP-ZPP.md |

## Protocol stack

Zerion runs one message stack over three interchangeable carriers.

```
  Application records (ZMM)
        |
  Session crypto:
    - online  : ZWF stream + Mode 3-Full ratchet      (ZWF-MODE3FULL.md)
    - offline : Async Sealed-Sender envelope           (ASYNC-SEALED-SENDER.md)
        |
  Delivery:
    - online  : ZTP transport + ZPP pull rhythm        (ZTP-ZPP.md)
    - offline : Mesh flooding over Bluetooth Low Energy (MESH-TRANSPORT.md)
        |
  Carriers:
    - Tor v3 onion services            (inherited onion wrapper, see ZTP-ZPP.md)
    - Bluetooth Low Energy             (MESH-TRANSPORT.md)
    - Embedded I2P, optional           (EMBEDDED-I2P.md)
```

The online path and the offline path use different session crypto because they
have different trust and timing models. Online, both peers are present and hold a
long-lived shared root key, so Zerion runs a continuous forward-secret ratchet.
Offline, the recipient may be absent for days and messages are relayed by
untrusted devices, so Zerion seals each message to the recipient's published
prekey bundle with no interactive handshake.

## Documents

| File | Scope |
| --- | --- |
| [ZWF-MODE3FULL.md](ZWF-MODE3FULL.md) | The online wire format and the Mode 3-Full post-quantum ratchet |
| [ZTP-ZPP.md](ZTP-ZPP.md) | The Tor transport seam and the constant-rate pull protocol |
| [ASYNC-SEALED-SENDER.md](ASYNC-SEALED-SENDER.md) | The offline sealed-sender envelope used by the mesh |
| [MESH-TRANSPORT.md](MESH-TRANSPORT.md) | Store-and-forward flooding and the Bluetooth Low Energy link |
| [EMBEDDED-I2P.md](EMBEDDED-I2P.md) | The optional embedded I2P carrier and its privacy trade-off |

## Cryptographic primitives

All layers share one primitive set.

| Purpose | Primitive |
| --- | --- |
| Authenticated encryption | XSalsa20-Poly1305, 24-byte nonce, 16-byte tag |
| Key encapsulation (post-quantum) | ML-KEM-768 |
| Key agreement (classical) | X25519 |
| Signature (post-quantum) | ML-DSA-65 |
| Signature (classical) | Ed25519 |
| Hashing, MAC and key derivation (messaging core) | keyed BLAKE2b-256 with domain-separated labels (`CryptoComponentImpl`) |
| Identifiers and fingerprints | SHA-256 (ML-KEM key-pair ids, mesh discovery); SHA-512 in the Tor rendezvous derivation; SHA3-256 for the ML-KEM key-seed hash |
| Password stretching | Argon2id (account database key, vault, wallets); PBKDF2-HMAC-SHA256 (duress password, Bitcoin section credential); legacy scrypt files still open |
| Vault and calls | AES-256-GCM; HKDF-SHA256 and HMAC-SHA256 for vault chunk keys and call endpoint keys |

Public keys and signatures are hybrid: a classical key concatenated with a
post-quantum key, so a break of either family alone does not break the
construction. Sizes are listed in each document and are fixed by
`PostQuantumConstants` and `PcsConstants`.

## Conventions

Byte layouts are shown as field tables with fixed offsets. Integers are
big-endian unless stated otherwise. Lengths are in bytes. A field written as
`name:N` is N bytes wide; `name:uintK` is a K-bit big-endian unsigned integer.
