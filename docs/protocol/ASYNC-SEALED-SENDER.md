# Async Sealed-Sender Envelope

The sealed-sender envelope is the crypto layer for offline delivery. It lets a
sender encrypt a message to a recipient who is not online, with no interactive
handshake, and hand that message to untrusted relays. A relay learns only what it
needs to forward, deduplicate and route to a prekey. It does not learn the sender
or the content, and it does not learn the recipient's identity directly. The
prekey selector fields are visible, but from 3.0.15 they name no recipient:
every account publishes the same signed-prekey id, and each contact knows the
recipient's one-time prekeys under ids of its own (see Selectors below). A
contact can recognise only envelopes sealed from its own copy of the bundle,
which in practice are its own. Envelopes sealed from a bundle published by
3.0.14 or earlier, until the sender receives a current one, still carry that
bundle's per-account selectors.

This layer is used by the Bluetooth mesh. The mesh transport carries these
envelopes; the envelope protects them.

## Trust model

- The recipient publishes a prekey bundle in advance. The sender needs only that
  bundle to seal a message.
- Relays are untrusted. They see an opaque envelope, the prekey selector
  (`prekeyKind`, `prekeyId`, `signedPrekeyId`), the sender's ephemeral key and
  KEM ciphertext, a time-to-live and a deduplication identifier (see the
  visibility column below).
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

`identityAgreePub` is bound into the key transcript and used for nothing else.
From 3.0.15 it is a hybrid agreement key generated once for this purpose and
kept with the prekeys; its private half is discarded. Up to 3.0.14 it was the
key of the user's pairing link at the time the bundle was made, so any contact
could match the bundle to a pairing link shared elsewhere, and a bundle made
after the link key rotated did not open at a recipient that had loaded the
older key.

## Selectors

From 3.0.15 the store publishes its keys so that the selector tells a relay
nothing:

- **Signed prekey.** Every bundle carries signed-prekey id `0`, the same for
  every account (earlier releases count from 1, so `0` names none of their
  keys). The recipient tries its current signed prekey and then, if an
  envelope of that time-to-live sealed to it could still be alive, its
  previous one: a sender cannot seal to a signed prekey after its expiry, so
  an envelope sealed to the previous key is dead once its time-to-live has
  passed since that key expired.
- **One-time prekeys.** Each contact receives the pool under its own ids, the
  first 16 bytes of `MAC(ONE_TIME_PREKEY_ALIAS, aliasKey, contactId, ownId)`
  where `aliasKey` is a random 32-byte secret kept with the prekeys. The store
  remembers which contacts it published to and maps an id back to its own on
  receipt. A key used through one contact's id is gone for every contact.
- **Earlier bundles.** A non-zero signed-prekey id, or a one-time id that is
  the store's own, comes from a bundle published by 3.0.14 or earlier; it is
  resolved directly, and the transcript is rebuilt with the agreement key that
  bundle carried.

Every attempt to open an envelope costs a hybrid decapsulation, and a stranger
can make any envelope name a recipient's keys. Attempts are drawn from a
budget for the device, refilling at 48 per second up to 96, and from a budget
for the Bluetooth neighbour the envelope arrived from, refilling at 16 per
second up to 32, so a single neighbour cannot spend the whole budget; an
envelope that arrives with either budget spent is not opened. An envelope that names a key but does not open
is remembered by a hash of its whole encoding, so a repeat costs a lookup; the
deduplication identifier is not used for this, because a forgery could carry
the identifier of a genuine envelope. An envelope that does not authenticate
never reaches the signature check.
A one-time prekey is preferred when available and is consumed on first use, which
gives a fresh key per message. When no one-time prekey is available the signed
prekey is used. Only a one-time prekey gives forward secrecy for that message:
an envelope sealed to the signed prekey can be opened by anyone who later obtains
that signed prekey's private key while it is retained.

## Delivery and cover

The delivery layer floods a sealed envelope through the mesh forwarder. It can
also emit cover envelopes. A cover envelope is sealed to a throwaway keypair with
random recipient identity fields, and its selector is that of an envelope
sealed from a published bundle: signed-prekey id `0`, or a random one-time
prekey id. A cover envelope is therefore indistinguishable on the wire from a
message sealed from a current bundle. On receipt, the delivery layer resolves the prekey,
opens the envelope, checks the deduplication identifier against a seen-store, and
consumes the one-time prekey if the message is accepted.

## Note on status

This construction is the crypto layer for the Bluetooth mesh path. It is separate
from the online ZWF path, which uses a continuous ratchet rather than a per-message
seal because both peers are present online.
