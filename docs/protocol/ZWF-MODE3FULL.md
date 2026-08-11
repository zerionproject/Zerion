# ZWF: Zerion Wire Format and the Mode 3-Full Ratchet

ZWF is the wire format Zerion uses on an online connection between two paired
contacts. It carries a stream of fixed-size frames, each protected by an
authenticated cipher and by the Mode 3-Full ratchet. The ratchet gives forward
secrecy and post-compromise security against both classical and quantum
adversaries.

ZWF sits directly on a raw byte stream. That stream can come from Tor, from I2P,
or from any other carrier that provides an ordered reliable channel. The carrier
sees only fixed-size frames.

## Design goals

- Every frame is the same size, so the carrier cannot infer message length.
- A passive observer cannot link two frames to the same conversation without the
  per-contact tag key.
- A compromise of the current keys does not reveal earlier messages (forward
  secrecy) and the ratchet heals in later messages (post-compromise security).
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
streamHeader ciphertext[10] + MAC[16]     total stream header on wire = 50
frame[4096]
frame[4096]
...
```

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
`4096 - 20 - 2362 - 16 = 1698` bytes. Larger records fragment across frames and
are reassembled by the final-frame flag.

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
| flags | 1 | PCS enabled, DH ratchet, PQ enabled, Mode 3-Full frame |
| messageNumber | 4 | uint32 |
| previousChainLength | 4 | uint32 |
| dhPublicKey | 32 | X25519 ratchet public key |
| pqEpoch | 4 | uint32 |
| chunk PK_ADVERTISE | 1188 | type 0x10, index, length 1184, then the ML-KEM-768 encapsulation key |
| chunk KEM_CT | 1092 | type 0x11, index, length 1088, then the ML-KEM-768 ciphertext |
| chunk KP_ID | 20 | type 0x12, index, length 16, then the id of the recipient key used |

The three chunks let each side advertise its current ML-KEM encapsulation key,
send a ciphertext to the peer's advertised key, and name which key a ciphertext
was made against.

## The ratchet

Zerion runs a Double-Ratchet style construction with a post-quantum layer folded
into the chain.

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

The first message, sent before the peer's key is learned, carries an all-zero
ciphertext sentinel and no post-quantum secret. From the second message onward,
the post-quantum layer is active and continuous. Because the shared secret is
folded into the chain, an attacker who records traffic and later obtains the
classical keys still cannot derive the body keys without also breaking ML-KEM.

Receive side. The receiver looks up its decapsulation keypair by the 16-byte key
id in the header, keeping the 32 most recent keypairs so that in-flight messages
made against a rotated key still open.

## Stream identifiers and replay

Send stream identifiers are strictly monotonic and are persisted before first use,
so they are never reused across restarts or crashes. Reusing an identifier under
the long-lived root key would repeat chain keys and nonces, which would be a
complete loss of confidentiality, so the counter is durable by construction.

The receive side validates each incoming identifier against a 256-wide window
that tolerates reordering and rejects replays. The replay check runs only after
the first frame of a stream authenticates, so an attacker cannot exhaust the
window with forged identifiers.

## Session resumption

A connection does not re-handshake. The connection handler resumes the stored
session for that contact: the root key, the role, and the Mode 3-Full state. Both
directions of a connection share one Mode 3-Full state under a lock, so a peer key
learned while receiving is available to the sender on the same connection.

## Relationship to Briar

Briar's transport security protocol is not used on this path. ZWF and Mode 3-Full
replace it. The database and identity storage that hold the root key and the
persisted session are inherited Briar components.
