# Async Sealed-Sender Envelope

The sealed-sender envelope is the crypto layer for offline delivery. It lets a
sender encrypt a message to a recipient who is not online, with no interactive
handshake, and hand that message to untrusted relays. A relay learns only what it
needs to forward and deduplicate. It does not learn the sender, the recipient, or
the content.

This layer is used by the Bluetooth mesh. The mesh transport carries these
envelopes; the envelope protects them.

## Trust model

- The recipient publishes a prekey bundle in advance. The sender needs only that
  bundle to seal a message.
- Relays are untrusted. They see an opaque envelope, a time-to-live, and a
  deduplication identifier, and nothing else.
- The recipient authenticates the sender after opening, from a signature inside
  the sealed record. A relay cannot see who signed.

## Primitive sizes

| Key or value | Size |
| --- | --- |
| ML-KEM-768 public / secret / ciphertext / shared secret | 1184 / 2400 / 1088 / 32 |
| ML-DSA-65 public / secret / signature | 1952 / 4032 / 3309 |
| X25519 public key | 32 |
| Hybrid agreement public key (X25519 then ML-KEM-768) | 1216 |
| Hybrid signature public key (Ed25519 then ML-DSA-65) | 1984 |
| Hybrid signature | 3373 |

## Envelope layout

`HEADER_BYTES = 2350`. The maximum sealed blob is 6 MiB.

| Offset | Field | Size | Visibility |
| --- | --- | --- | --- |
| 0 | version = 0x01 | 1 | relay |
| 1 | prekeyKind | 1 | relay; 0x01 one-time, 0x00 signed-prekey |
| 2 | prekeyId | 16 | relay |
| 18 | signedPrekeyId (uint32) | 4 | relay |
| 22 | senderEphemeralPub | 1216 | relay; hybrid agreement public key |
| 1238 | kemCiphertext | 1088 | relay; ML-KEM-768 ciphertext |
| 2326 | ttl (uint32) | 4 | relay; seconds, advisory |
| 2330 | dedupId | 16 | relay |
| 2346 | ciphertextLen (uint32) | 4 | relay |
| 2350 | aeadBlob | variable | Poly1305 tag then XSalsa20 ciphertext |

The fields a relay can read are only those it needs: the prekey selector so the
recipient can find the right decapsulation key, the ephemeral key and ciphertext,
the advisory time-to-live, the deduplication identifier, and the blob length.

## Sealing

1. The sender generates an ephemeral hybrid agreement keypair and encapsulates to
   the recipient's agreement key, obtaining a ciphertext and a shared secret.
2. The message key is a one-pass hybrid agreement bound to a transcript:
   `deriveHybridSharedSecretAsResponder(ASYNC_SEALED_SENDER_V1, recipientAgreementPub, ephemeral, sharedSecret, transcript)`.
   The transcript is a fixed-size concatenation of only the fields the recipient
   can reconstruct: version, recipient identity signature key (1984), recipient
   identity agreement key (1216), prekey kind, prekey id (16), signed-prekey id,
   ephemeral public key (1216), KEM ciphertext (1088), time-to-live, and
   deduplication identifier. The send timestamp is deliberately left out of the
   key transcript and is instead signed inside the record, so the timestamp
   cannot be used to grind the key.
3. The AEAD key is `KDF(ENVELOPE_KEY, messageKey)`. The AEAD nonce is
   `MAC(ENVELOPE_NONCE, messageKey, transcript)` truncated to 24 bytes. The
   cipher is XSalsa20-Poly1305.

## Inner signed record

Before AEAD sealing, the plaintext is a signed record:

```
senderIdentitySigPub[1984]
messageType[1]
payload[...]
ttl[4]  (uint32)
dedupId[16]
sendTimestamp[8]  (uint64)
signature[3373]   hybrid Ed25519 + ML-DSA-65 over the transcript and the prefix above
```

The signature label is `SENDER_AUTH`. On open, the recipient decrypts, reads the
trailer, checks that the inner time-to-live and deduplication identifier equal the
outer ones, and verifies the hybrid signature against the sender identity key it
just learned. Deciding whether that identity is a trusted contact, consuming the
one-time prekey, and persisting the deduplication identifier are the caller's
responsibility.

## Prekey bundle

A recipient publishes this bundle so senders can seal to it offline.

```
version[1]
identitySigPub[1984]
identityAgreePub[1216]
signedPrekeyId[4]
signedPrekeyPub[1216]
signedPrekeyExpiry[8]
signedPrekeySig[3373]     over version, signedPrekeyId, signedPrekeyPub, expiry
oneTimePrekeyCount[2]     up to 1000
oneTimePrekeys[]          each is id[16] then pub[1216]
bundleSig[3373]           over everything above
```

Both signatures are hybrid and are verified against the bundle's own identity key.
A one-time prekey is preferred when available and is consumed on first use, which
gives a fresh key per message. When no one-time prekey is available the signed
prekey is used.

## Delivery and cover

The delivery layer floods a sealed envelope through the mesh forwarder. It can
also emit cover envelopes. A cover envelope is sealed to a throwaway keypair with
random recipient identity fields and a signed-prekey id drawn from a
log-distribution over a plausible range, so a cover envelope is indistinguishable
on the wire from a message. On receipt, the delivery layer resolves the prekey,
opens the envelope, checks the deduplication identifier against a seen-store, and
consumes the one-time prekey if the message is accepted.

## Note on status

This construction is the crypto layer for the Bluetooth mesh path. It is separate
from the online ZWF path, which uses a continuous ratchet rather than a per-message
seal because both peers are present online.
