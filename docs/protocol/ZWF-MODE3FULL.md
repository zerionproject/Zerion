# ZWF: Zerion Wire Format and the Mode 3-Full Ratchet

ZWF is the wire format Zerion uses on an online connection between two paired
contacts. It carries a stream of fixed-size frames, each protected by an
authenticated cipher and by the Mode 3-Full ratchet. The ratchet gives forward
secrecy and post-compromise security within a connection; both properties rest
on the ML-KEM layer alone (see "The ratchet" below and the security claims
matrix), not on an independent classical ratchet. Across connections, the
contact's root key, which seeds every connection, evolves with fresh hybrid
secrets between peers that both support it (see "Session resumption" and
ZWF-ROOT-EVOLUTION.md), so a copied root stops authenticating after an
evolution the copier did not take part in.

ZWF sits directly on a raw byte stream. That stream can come from Tor, from I2P,
or from any other carrier that provides an ordered reliable channel. The carrier
sees only fixed-size frames.

## Design goals

- Every frame is the same size, so the carrier cannot infer message length.
- A passive observer cannot link two frames to the same conversation without the
  per-contact tag key.
- A compromise of the current keys does not reveal earlier messages (forward
  secrecy) and the ratchet heals in later messages (post-compromise security)
  within a connection. A compromise of the contact root key heals at the
  next root evolution the attacker does not take part in, between peers that
  both support root evolution.
- The post-quantum layer contributes to every message, not only to the initial
  handshake.

## Constants

| Name | Value | Meaning |
| --- | --- | --- |
| `WIRE_VERSION` | 1 | Stream-header version field |
| `FRAME_LENGTH` | 4096 | Fixed on-wire size of every frame |
| `TAG_LENGTH` | 16 | Stream tag length |
| `STREAM_ID_LENGTH` | 8 | Stream identifier length |
| `NONCE_LENGTH` | 24 | XSalsa20 nonce length |
| `MAC_LENGTH` | 16 | Poly1305 tag length |
| `REPLAY_WINDOW_SIZE` | 256 | Receive-side reorder and replay window |
| `PCS_PROTOCOL_VERSION` | 6 | Version byte inside each Mode 3-Full header |
| `MODE3_FULL_SEND_ROTATION_INTERVAL` | 16 | Messages between sender ML-KEM key rotations |
| `MODE3_FULL_RECV_SK_LRU_SIZE` | 32 | Recent decapsulation keypairs retained by the receiver |

ML-KEM-768 sizes: encapsulation key 1184, decapsulation key 2400, ciphertext
1088, shared secret 32. X25519 public key 32.

## Stream layout

A stream begins with a tag and an encrypted stream header, sent once on the first
frame. All following bytes are frames.

```
tag[16]                first 16 bytes of MAC(ZWF_STREAM_TAG, tagKey, streamId)
streamHeaderNonce[24]
MAC[16] + streamHeader ciphertext[10]     total stream header on wire = 50
frame[4096]
frame[4096]
...
```

Every sealed segment on the wire, here and in the frames, is the 16-byte
Poly1305 tag followed by the ciphertext (the NaCl `secretbox` layout).

Stream-header plaintext (10 bytes), encrypted under a dedicated stream-header key
with the random 24-byte header nonce:

| Offset | Field | Size |
| --- | --- | --- |
| 0 | version (uint16) | 2 |
| 2 | streamId (uint64) | 8 |

The chain key is not carried in the header. The receiver reseeds it from
`(rootKey, streamId, streamHeaderNonce)`, so an observer never sees keying
material.

## Frame layout

Each frame is exactly 4096 bytes and is made of three authenticated segments.

| Offset | Segment | Plaintext size | On-wire size |
| --- | --- | --- | --- |
| 0 | Frame header (segment 0) | 4 | 20 |
| 20 | Mode 3-Full header (segment 1) | 2346 | 2362 |
| 2382 | Body (segment 2) | payload + padding | payload + padding + 16 |

Frame-header plaintext (4 bytes):

| Offset | Field | Size | Notes |
| --- | --- | --- | --- |
| 0 | totalPayloadLength (uint16) | 2 | High bit of byte 0 is the final-frame flag; the length uses the low 15 bits |
| 2 | paddingLength (uint16) | 2 | Padding bytes are zero and are checked to be zero on decrypt |

Segments 0 and 1 are sealed with the classical message key. Segment 2, the body,
is sealed with the hybrid body key when a post-quantum shared secret is present
for that message. The maximum payload in one frame is
`4096 - 20 - 2362 - 16 = 1698` bytes. Larger records fragment across frames; in
production, reassembly is performed by the ZMM message layer, which scopes
fragments by message id, fragment index and count. The final-frame flag marks
the end of a stream, not the general reassembly mechanism.

## Frame nonce

The 24-byte nonce is derived structurally, never sent.

| Offset | Field | Size |
| --- | --- | --- |
| 0 | streamId (uint64) | 8 |
| 8 | frameNumber (uint64) | 8 |
| 16 | 0x80 domain marker | 1 |
| 17 | segment index (0, 1, or 2) | 1 |
| 18 | originator flag | 1 |
| 19 | zero | 5 |

Because `streamId` is bound into both the nonce and the chain-key seed, two
streams never share a nonce space even if a key derivation were to repeat.

## Mode 3-Full header

Segment 1 carries the ratchet state for the message. Its plaintext is 2346
bytes.

| Field | Size | Notes |
| --- | --- | --- |
| version | 1 | `PCS_PROTOCOL_VERSION` = 6 |
| flags | 1 | PCS enabled, DH ratchet, PQ enabled, Mode 3-Full frame; every receiver requires all four, so the DH ratchet flag stays set although no DH ratchet runs |
| messageNumber | 4 | uint32 |
| previousChainLength | 4 | uint32 |
| dhPublicKey | 32 | All zero; kept for the frame layout (see "The ratchet") |
| pqEpoch | 4 | uint32 |
| chunk PK_ADVERTISE | 1188 | type 0x10, index, length 1184, then the ML-KEM-768 encapsulation key |
| chunk KEM_CT | 1092 | type 0x11, index, length 1088, then the ML-KEM-768 ciphertext |
| chunk KP_ID | 20 | type 0x12, index, length 16, then the id of the recipient key used |

The three chunks let each side advertise its current ML-KEM encapsulation key,
send a ciphertext to the peer's advertised key, and name which key a ciphertext
was made against.

## The ratchet

Zerion runs a symmetric chain per stream with a post-quantum layer folded into
it. There is no classical DH ratchet: the 32-byte `dhPublicKey` field of the
header is kept so the frame layout stays the one every peer parses, and it is
sent as zeros (earlier Android releases sent a fixed X25519 key there; no Android 3.0.x
receiver parses it). Forward secrecy before the first ML-KEM secret is
mixed in is therefore limited to the chain advance under a key a root-key
holder can reconstruct; from the first post-quantum contribution onward, every
key depends on an ML-KEM secret.

Chain seeding. The per-stream initial chain key is
`KDF(PCS_STREAM_CHAIN, rootKey, streamId, salt)` where the salt is the random
24-byte stream-header nonce. Both sides feed the same inputs and reach the same
chain key. Per frame the chain advances with a chain-key KDF that produces the
next chain key and the message key.

Post-quantum contribution. For each message, if the peer's ML-KEM encapsulation
key is known, the sender encapsulates to it and obtains a shared secret. The
sender rotates its own encapsulation key every 16 messages. The shared secret is
folded in two places:

- Body key. `deriveHybridMessageKey(classicalMessageKey, sharedSecret)` mixes the
  post-quantum secret into the key that seals the body segment.
- Chain fork. `mixPqSecretIntoChainKey(nextChainKey, sharedSecret)` folds the
  post-quantum secret into the chain key itself, so the secret ratchets forward
  and every later message depends on it.

The frames a side sends before it has learned the peer's key carry an all-zero
ciphertext sentinel and no post-quantum secret; they are cover frames only.
The sender holds application records until the peer's key is known, and the
receiver enforces it as well: the pull protocol drops every record but cover
that arrives in a frame without a post-quantum secret. From the first frame
after that, the post-quantum layer is active and continuous. Because the shared secret is
folded into the chain, an attacker who records traffic and later obtains the
classical keys still cannot derive the body keys without also breaking ML-KEM.

Receive side. The receiver looks up its decapsulation keypair by the 16-byte key
id in the header, keeping up to 32 recent keypairs per connection so that
in-flight messages made against a rotated key still open. Keypairs older than
the one the peer last used are pruned and zeroized, but only once the frame
that used it has authenticated in full, so a frame that fails leaves every
keypair usable.

Rotation and the retention bound. A side rotates its keypair every 16 of its
own sends, as long as fewer than 32 retired keypairs are retained. Retained
keypairs are pruned as soon as the peer uses a newer one, which every peer
frame does once it has read the newer advertisement. Rotation therefore pauses
only when the peer has not been heard from for about 512 of this side's sends;
the pause avoids evicting a keypair that the peer's frames still in flight may
be encapsulated to, which would break the connection. Rotation is thus bounded
by the peer's progress in that one case, not independent of the peer.

## Stream identifiers and replay

Send stream identifiers are strictly monotonic and are persisted before first use,
so they are not reused across restarts or crashes (a database restored from
backup can hand back a used identifier). Because the chain seed is salted with
the random stream-header nonce, a reused identifier would not repeat chain keys
or (key, nonce) pairs, but it would repeat the stream tag, which links streams;
the counter is durable by construction.

The receive side validates each incoming identifier against a 256-wide window
that tolerates reordering and rejects replays; after a restart the persisted
high-water mark acts as a floor, so older identifiers are refused. The replay
check runs as soon as the encrypted stream header authenticates, before the
first frame is opened, so a replayed stream is refused before it can publish
any ratchet state (an attacker without the stream-header key cannot reach the
check with forged identifiers). Nothing outside the connection changes until
the first frame has authenticated: the connection is registered, the message
layer is offered the send queue and the onion rotation and client
authorization learn of the session only then.

## Session resumption

A connection does not re-handshake. The connection handler re-derives the ZWF
session for that contact from the persisted **root keys** and **role**, and
starts a **fresh Mode 3-Full ratchet for each connection**: the Mode 3-Full
state is *not* carried over from a previous connection
(`ZtpConnectionEstablisher.resume()` calls `deriveSession(keys, sendEpoch,
alice)` and creates a new initial state), so a compromise of one connection's
ratchet does not extend to the next. Within a single connection, both
directions share one Mode 3-Full state under a lock, so a peer key learned
while receiving is available to the sender on the same connection.

The root key evolves. Each connection between two peers that support it runs
the root evolution of ZWF-ROOT-EVOLUTION.md: the peers fold fresh X25519 and
ML-KEM-768 secrets into a new root, confirm it, and delete the old one, after
which a copy of the old root no longer authenticates in either direction. A
connection is keyed under one root epoch: the dialler chooses (its pending root
once the peer proved it holds it, its current root otherwise, and the current
root again for one dial after two dials under the pending root that never
authenticated) and the side that accepts answers under the same epoch. With a peer that does not support root
evolution, such as Android 3.0.14, the root stays the one agreed at pairing and
a compromise of it is not healed until the two re-pair.

Key lifetime. When a connection ends, the ML-KEM decapsulation keys of its
Mode 3-Full state, the stream chain keys, the session's direction root, tag
and stream-header keys and the copies of the root keys loaded for it are
zeroized, as are replaced chain keys on every frame and the Poly1305 subkey
after each segment. The cryptographic library's own key objects (the
ML-KEM private-key parameters it builds for each decapsulation) and values the
Java runtime copied are outside Zerion's control and are not zeroized.

## Component provenance

ZWF and Mode 3-Full are Zerion's own protocol work. The provenance of the
database and identity storage that hold the root key is recorded in
[README.md](README.md) in this directory and in the repository's NOTICE.md.
