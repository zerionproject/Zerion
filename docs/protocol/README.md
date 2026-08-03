# Zerion Protocol Specifications

This directory documents the protocols that Zerion defines and implements. It
covers the wire formats, the cryptographic constructions, and the message flow
for each layer of the stack.

Zerion is built on the Briar and Bramble framework. Briar provides the parts
that Zerion reuses without change: the local database, the synchronisation
bookkeeping, identity storage, the plugin and lifecycle machinery, and the Tor
onion wrapper. The protocols described here are Zerion's own work and replace or
sit above those inherited parts. Where a document refers to inherited Briar
behaviour it says so.

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
| Hashing and key derivation | SHA-256 and SHA-512 based KDF and MAC |

Public keys and signatures are hybrid: a classical key concatenated with a
post-quantum key, so a break of either family alone does not break the
construction. Sizes are listed in each document and are fixed by
`PostQuantumConstants` and `PcsConstants`.

## Conventions

Byte layouts are shown as field tables with fixed offsets. Integers are
big-endian unless stated otherwise. Lengths are in bytes. A field written as
`name:N` is N bytes wide; `name:uintK` is a K-bit big-endian unsigned integer.
