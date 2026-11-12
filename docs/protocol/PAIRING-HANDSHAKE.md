# Pairing handshake and contact exchange: key schedule

Two contacts are added by one of two key agreements, followed by the same
contact exchange:

- **Link handshake** (`HandshakeManagerImpl`, `HandshakeCryptoImpl`): used by
  link pairing, Scan QR in link mode and contact requests, which all meet at a
  rendezvous over Tor and run this handshake.
- **Nearby key agreement** (`KeyAgreementProtocol`, BQP version 5): used by
  the nearby pairing code over Bluetooth or the local network.
- **Contact exchange** (`ContactExchangeManagerImpl`): runs on the same
  connection after either one. Each side sends its author, its ML-DSA-65
  public key and its transport properties, encrypted under header keys,
  together with a tag under the exchange key when key separation was
  negotiated and the pairing creates a new contact or, otherwise, an
  Ed25519 signature and a hybrid Ed25519 + ML-DSA-65 signature over a
  nonce; the contact is then stored with the session root.

This document describes the keys each step uses. The record layouts are in
`HandshakeRecordTypes`, `RecordTypes` and
[B3_RECORD_PLACEMENT.md](../wire/B3_RECORD_PLACEMENT.md).

## Key separation (from Android 3.0.19)

The output of a key agreement is a secret `K`. Up to 3.0.18 `K` itself was
the session root stored for the contact (the root of epoch 0 in
[ZWF-ROOT-EVOLUTION.md](ZWF-ROOT-EVOLUTION.md), the input of the rotation
keys and the voice-memo wrap key), and it was also used during the run: the
handshake proofs and the Mode 3 capability MAC were MACs under `K`, and the
contact exchange signed `MAC(K)` and derived its header keys from `K`. A
session key that is used inside the protocol that produces it is not
indistinguishable from random to anyone who sees the run, which is the
property standard composition proofs need.

From 3.0.19, when both sides support it, three keys are derived from `K`
under distinct labels and `K` is then wiped:

```
confirmation = KDF("org.zerionproject.core.handshake/CONFIRMATION_KEY_V5", K)
exchange     = KDF("org.zerionproject.core.handshake/EXCHANGE_KEY_V5", K)
root         = KDF("org.zerionproject.core.handshake/ROOT_KEY_V5", K)
```

`KDF` is `CryptoComponent.deriveKey` (keyed BLAKE2b-256, label and inputs
length-prefixed). The confirmation key keys the two proofs of ownership
(`ALICE_PROOF`, `BOB_PROOF`) and the Mode 3 capability MAC. The exchange key
replaces `K` in the contact exchange: the header keys
(`ALICE_HEADER_KEY`, `BOB_HEADER_KEY`) are derived from it and it keys the
authentication tag of the contact record (below). The root is stored for the
contact and is never an input of anything sent during the handshake or the
contact exchange. The labels of the handshake MACs and of the header keys
are unchanged; only their key changes. `HandshakeKeySeparationTest` checks that no key used
during the run equals the root or the exchange key, and that the proofs are
MACs under the confirmation key; `ContactExchangeIntegrationTest` runs the
same check across a handshake and the contact exchange that follows it.

For the nearby key agreement the separation is:

```
s        = hybrid shared secret under "org.zerionproject.core.keyagreement/HYBRID_SHARED_SECRET_V5_SEPARATED"
root     = KDF("org.zerionproject.core.keyagreement/MASTER_SECRET", s)
exchange = KDF("org.zerionproject.core.keyagreement/EXCHANGE_KEY", s)
```

The BQP confirmation records were already keyed by a separate
`KDF(CONFIRMATION_KEY, s)` and do not change; the change is that the contact
exchange after a nearby pairing uses `exchange` instead of the root.

### The contact record carries a tag under the exchange key

Up to 3.0.18 the contact record carried three signatures by the long-term
identity keys: `signature` (Ed25519 over `MAC(ALICE_NONCE or BOB_NONCE,
K)`), `hybridSignature` (Ed25519 + ML-DSA-65 over the same nonce) and the
B.3 proof (`B3PqProof`, Ed25519 over the signer's role, the session id
`BLAKE2b(key = ZERION_HANDSHAKE_SESSION_v1, sorted ephemeral X25519 keys)`
and the signer's static ML-KEM-768 public key,
[B3_RECORD_PLACEMENT.md](../wire/B3_RECORD_PLACEMENT.md)). They are only
ever checked by the peer, who holds `K` and therefore already knows who
completed the handshake. But they are signatures by identity keys over
values that either party can later reveal: whoever holds the two ephemeral
keys and the two records can check both B.3 proofs, and either peer can hand
over `K` and let a third party check all three signatures, which proves that
the two identities paired with each other.

From 3.0.19, when key separation was negotiated and the pairing creates a
new contact (a link, invite or contact-request pairing on which this side
did not choose Re-add), the record carries no signature at all. The
`signature` slot holds a 32-byte tag

```
tag = MAC("org.zerionproject.app.contact/EXCHANGE_TAG_V2", exchange,
          role, author, mlDsaSigPub, properties, timestamp,
          staticPqPub, sessionId)
```

(`CryptoComponent.mac`, keyed BLAKE2b-256; the label and every input are
length-prefixed) over everything the record asserts, in this order: the
sender's role (one byte, `0x01` for Alice and `0x02` for Bob, the roles of
the header keys), the BDF encoding of the author list exactly as sent
(format version, name, Ed25519 public key), the ML-DSA-65 public key (slot
5), the BDF encoding of the transport properties dictionary exactly as sent
(slot 1), the timestamp as 8 big-endian bytes (slot 3), the sender's static
ML-KEM-768 public key (the 1184 bytes the B.3 proof covered) and the B.3
session id of this handshake (32 bytes), which bind the tag to the committed
static key and to this run's ephemeral keys. After a nearby pairing, which
has no B.3 state, the last two inputs are empty. The `b3ProofSig` and
`hybridSignature` slots are sent as empty raws, so the record keeps its 7
slots. The receiver recomputes the tag with the peer's role over the fields
it received and compares in constant time (`CryptoComponent.verifyMac`). A
tag next to signatures is refused, and a tag is refused when key separation
was not negotiated. With separated keys the receiver accepts either form,
decided by the record itself: the tag form is a 32-byte slot 2 with slots 4
and 6 empty; the signed form is a 64-byte Ed25519 signature in slot 2 with
the hybrid signature present, and the B.3 proof present on the link path,
checked exactly as with an earlier peer.

What this keeps. The identity public keys sent under the tag are bound to
the committed static keys: the exchange key exists only for a party that
completed the handshake, which requires the static X25519 and ML-KEM-768
private keys behind the link or QR commitment, so the Ed25519 and ML-DSA-65
public keys in a record whose tag verifies come from the holder of the
committed keys. The ML-DSA-65 key is stored for the contact as before,
because the tag covers it. The static ML-KEM-768 key is bound in the record
where the B.3 proof bound it, so the flag the exchange stores for the
contact (`B3_SLOT_PRESENT_KEY_PREFIX`, read by
`ClientVersioningManagerImpl`) keeps its meaning: it is set after a link
pairing, where the static key and the session id are inputs of the tag, and
not after a nearby pairing.

The tag form does not prove possession of the identity keys: a peer could
present an identity public key it does not own (an unknown key share). For
a new contact that gains it nothing, because it cannot sign or decrypt as
that identity afterwards, an introduction of that contact fails when the
introducee must sign with the identity key, and the stored ML-DSA-65 key
means the later identity exchange (`ContactIdentityExchange`) is never run.
Replacing the keys of a contact the phone already has is different: whoever
re-keys a stored contact must prove possession of that contact's identity
keys, or anyone who knows a contact's public keys could, by having their own
link or request accepted with Re-add, replace that contact's root key and
transport properties with their own. Therefore a side that intends to
replace keys (`ContactManager.intendsToReplaceKeys`: the pending contact
carries the Re-add flag, set by Re-add in the contact list, by a contact
request that asks to reconnect or by a QR code scanned in person) and every
in-person pairing (the `verified` argument of the exchange, the nearby key
agreement) send the signed form, the three signatures exactly as with an
earlier peer, and a receiver that intends to replace keys refuses a tag
record whose author it already has as a contact (`FormatException` before
any contact logic runs; the pending contact is kept and no key changes). A
receiver with no such intent never replaces keys, so a tag record naming a
stored author ends as it did before: the pending contact is removed and the
contact is kept as it was. A re-key therefore happens only when both sides
chose Re-add, both send the signed form and both verify it. When only one
side chose Re-add, that side's exchange fails and its pending contact stays,
so the handshake is retried, while the other side sees that the contact
already exists; both phones have to use Re-add, as before.

What this changes. A holder of the exchange key can compute both tags
itself, so the records prove nothing to a third party even while the key
exists: both peers can produce them, and so could anyone who was given the
key. Without the exchange key a record cannot be checked at all. Both phones
wipe the exchange key when the contact exchange ends, and neither record
carries a signature by an identity key, so afterwards not even the two
parties can show that the other identity took part. A pairing that replaces the
keys of a stored contact, and an in-person pairing, keep the identity
signatures; they sign values derived from the exchange key and are provable
only to a holder of that key. With an earlier peer (minor version 4, no key
separation) the three signatures are sent and checked byte for byte as
before, with the B.3 proof over the public session id.
`ContactExchangeTagTest` pins the tag layout and that the receiving side
computes the same tag with the same key; `ContactExchangeB3ProofTest` runs
the contact exchange in both forms and checks that a tag under another key,
under the other role, over another timestamp or without the static key is
refused, that a tag next to signatures is refused, that a tag is refused
without separated keys, that a re-adding side sends the signed form, re-keys
on a signed record and refuses a tag naming a stored author, that a stored
author added again without Re-add keeps its keys, and that an in-person
pairing sends the signed form.

## Negotiation

### Link handshake: minor version 5

Each side sends its handshake minor version in the `MINOR_VERSION` record
(type `0x02`) before its ephemeral key. This release sends **5**
(`PROTOCOL_MINOR_VERSION`, `KEY_SEPARATION_MINOR_VERSION`). A peer below 4
(`KCI_MINOR_VERSION`) is refused, as before.

Key separation is used when both minor versions are at least 5
(`HandshakeManagerImpl.keySeparationNegotiated`); otherwise `K` is used
exactly as in 3.0.18 for every purpose. Both sides compute the decision from
the same two values, so they always agree.

**Downgrade.** Both minor versions are inputs of `K`, in role order (Alice's,
then Bob's), as they have been since minor version 2. If a party in the path
changes or removes a minor version record, the two sides derive different `K`
and the first proof of ownership fails, so the handshake is refused (a
missing record is refused outright as a version below 4). Lowering one
side's 5 to 4 cannot make two current peers agree on the earlier
derivation, and raising an earlier peer's 4 to 5 cannot make it agree on the
new one (`HandshakeKeySeparationTest`). The earlier derivation is used only
with a peer that announces 4 itself.

### Nearby key agreement: QR marker and capability record

Nearby pairing runs over Bluetooth LE only; Zerion ships no LAN or Wi-Fi
transport. The Bluetooth descriptor in the QR code carries only the
transport id, no device address: the Bluetooth service id is derived from
the QR code's commitment. BQP version 5 has no minor version, so the offer
travels in two places:

- **In the QR code.** A current side appends a marker descriptor
  `[0x7F, 0x01]` (transport id `TRANSPORT_ID_CAPABILITIES`, bit 0 = key
  separation) to the payload it shows. Android 3.0.12 to 3.0.18, the
  releases that speak BQP version 5, keep only the descriptors with
  transport id 1 or 2 (the old LAN id, unused, and Bluetooth) of a scanned
  payload and drop any other descriptor without
  failing, so they parse a marked code exactly as before. A current parser
  reports the marker as `Payload.offersKeySeparation()` and keeps it out of
  the transport descriptors. The marker adds 6 bytes to the payload before
  Base64.
- **On the connection.** A current side also sends a `CAPABILITIES` record
  (type `0x04`, payload `[0x01]`) immediately before its `KEY` record.
  Earlier releases skip unknown record types, so they read the `KEY` as
  before. A current side accepts this record only directly before the
  peer's `KEY` and skips it, as an unknown type, anywhere else.

The confirmation records are computed over the payloads without the marker
(`Payload.withoutCapabilities`), which is the encoding an earlier peer
computes from what it parsed, so a pairing with an earlier peer is
byte-identical to 3.0.18.

A side uses the separated derivation when both its own payload and the
payload it scanned carry the marker. It aborts if the scanned payload
carried the marker but no `CAPABILITIES` record came before the peer's key,
or a `CAPABILITIES` record came from a peer whose code had no marker. The
decision also selects the label of `s`.

**Downgrade.** The QR code is an out-of-band channel: each side scans it
from the other screen, and the code also carries the key commitment, so a
party on the radio or network link cannot change it. A party on that link
who removes the `CAPABILITIES` records, in one direction or both, makes the
side that scanned a marked code abort. A party that adds a record makes a
side that scanned an unmarked code abort; an earlier peer ignores it, and a
second record before the key is skipped. The two sides could derive
different `s` only if a QR marker itself differed, and then the BQP
confirmation fails. The earlier
derivation is used only when one of the two codes has no marker, that is,
with a peer that does not support the change.

## Compatibility

| This side | Peer | Link handshake | Nearby |
| --- | --- | --- | --- |
| 3.0.19 | 3.0.19 | separated keys | separated keys |
| 3.0.19 | Android 3.0.12 to 3.0.18 (handshake minor version 4, BQP version 5) | `K` as before | `K` as before (the peer drops the marker and skips the capability record) |
| Android 3.0.12 to 3.0.18 | 3.0.19 | `K` as before | `K` as before |
| 3.0.19 | Android 3.0.11 and earlier (minor version 2, BQP version 4) | refused, as by every release since 3.0.12 | refused (BQP version 4) |
| 3.0.19 | iOS, desktop | refused: they announce handshake minor version 2, below the minimum of 4 that Android has required since 3.0.12, so this change does not affect them | iOS has no BQP implementation in its repository (checked at commit `cfa4742`); the desktop branch speaks BQP version 4, which is refused |

The contact exchange learns the decision from the key agreement
(`HandshakeResult.isKeySeparated`, `KeyAgreementResult.isKeySeparated`),
sends the tag form with separated keys and the signature form otherwise. Contacts added before 3.0.19 keep their stored
root; nothing is re-derived.

## Wiping

With separated keys the handshake wipes `K` once the three keys are
derived and wipes the confirmation key when the handshake ends; on any
failure it also wipes the exchange key and the root. With the earlier
derivation the three keys are `K` itself: the handshake wipes it only on
failure, and on success it is handed on as the exchange key and the root.
The contact exchange wipes its two header keys when it returns or fails,
and the exchange key when it is separate from the root. The callers
(`IncomingHandshakeConnection`, `OutgoingHandshakeConnection`,
`AddNearbyContactViewModel`) wipe the root, and with the earlier derivation
therefore `K`, once the exchange has stored it or failed. The nearby key
agreement hands its keys to `AddNearbyContactViewModel` in
`KeyAgreementFinishedEvent`; if no screen is listening when the event
fires (the pairing screen was closed), those keys are left to the garbage
collector.

## Test vectors

`HandshakeVectorTest` pins the handshake secret, both proofs and the Mode 3
MAC of the earlier derivation, and the three separated keys, for fixed key
pairs, ciphertexts and KEM secrets. The derivation code
(`HandshakeCryptoImpl.deriveHybridMasterKeyPqAuth`, `HybridKeyAgreement`,
`CryptoComponentImpl`) is unchanged since 3.0.18, so the earlier vectors are
the bytes 3.0.18 produces.
