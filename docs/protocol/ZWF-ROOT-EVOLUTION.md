# ZWF contact root evolution, version 2

Every connection between two contacts derives its transport keys from the
contact's **root key**. Until Android 3.0.15 the root was the master key
agreed at pairing and never changed, so anyone who copied it from a device
(an unlocked phone, a backup file and its passphrase, a memory image) could
authenticate as either party in every later connection until the two
re-paired. Root evolution replaces the root, inside an authenticated
connection and with fresh hybrid key material, so that a copy of an older
root stops authenticating once the two peers have run one evolution that the
copier did not take part in.

This document is the reference for every implementation. Version 1 of the
record format was a development format of 3.0.15 that never shipped; a
receiver ignores it like any other unknown version.

## Terms

- **Epoch.** The root agreed at pairing is the root of epoch 0. Each completed
  evolution produces the root of the next epoch.
- **Current root.** The root of the contact's current epoch `e`.
- **Pending root.** The root of epoch `e + 1` while it is not yet in use on
  both sides.
- **Alice / Bob.** The roles of the pairing, decided by the order of the two
  author ids (the same roles the ZWF direction keys use, and the same rule
  that makes Alice the only side that dials over Tor, `ZtpPoller`). Alice
  starts every evolution; Bob answers.

## What each side stores

Per contact: the current epoch and root, and at most one pending root with a
flag saying whether the peer has proved it holds it. On Android the pairing
root stays in the contact's pairing row (it is the root of epoch 0 and the key
voice memos are wrapped under, see VOICE_MEMO_V2.md); the current root of an
epoch above 0 and the pending root are kept in rows of their own
(`DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT` and
`PCS_SLOT_TRANSPORT_ROOT_PENDING`). All of them are deleted with the contact.

Every change checks, in the same transaction, the epoch it starts from, so two
connections to the same contact never interleave two changes. Bob's pending
root is always unproved by Alice (he stores it before Alice has seen it);
Alice's pending root is always proved by Bob (she stores it only after
checking his proof). While a copy of the database is being written
(`RootKeyStore.beginSnapshot`) every change is refused.

## Key schedule

`KDF` is `CryptoComponent.deriveKey` (BLAKE2b-256 keyed with the key) and `H`
is `CryptoComponent.hash` (unkeyed BLAKE2b-256); both prefix the label and every
input with its uint32 length. `u64(x)` is a big-endian 64-bit integer.

```
transport(0)  = root(0)
transport(e)  = KDF("org.zerionproject.transport/ROOT_EPOCH_TRANSPORT_V1", root(e), u64(e))   e >= 1
chain(e)      = KDF("org.zerionproject.transport/ROOT_EPOCH_CHAIN_V1", root(e), u64(e))
transcript    = H("org.zerionproject.transport/ROOT_EVOLVE_TRANSCRIPT_V1",
                  [0x02], u64(e), x25519_A, ek_A, x25519_B, ct)
dh            = H("org.zerionproject.transport/ROOT_EVOLVE_DH_V1",
                  X25519(own, peer), x25519_A, x25519_B)
root(e + 1)   = KDF("org.zerionproject.transport/ROOT_EVOLVE_V1", chain(e),
                  u64(e + 1), ss, dh, transcript)
confirm(e+1)  = KDF("org.zerionproject.transport/ROOT_EVOLVE_CONFIRM_KEY_V1", root(e + 1))
```

The transcript's first input is the record version byte (`0x02`). `ss` is the
ML-KEM-768 shared secret Bob encapsulates to Alice's ephemeral encapsulation
key `ek_A`; `ct` is its ciphertext. `x25519_A` and `x25519_B` are ephemeral
X25519 public keys. The new root therefore depends on the previous root and on
fresh classical and post-quantum secrets that a party holding only the
previous root cannot compute.

The proofs are MACs of `u64(e + 1)` under `confirm(e + 1)`:

| Proof | Label |
| --- | --- |
| Bob's confirmation (RESP) | `org.zerionproject.transport/ROOT_EVOLVE_RESPONDER_CONFIRM_V1` |
| Alice's confirmation (CONFIRM) | `org.zerionproject.transport/ROOT_EVOLVE_INITIATOR_CONFIRM_V1` |
| Bob's completion (DONE) | `org.zerionproject.transport/ROOT_EVOLVE_RESPONDER_DONE_V1` |
| Pending-root id (HELLO, INIT) | `org.zerionproject.transport/ROOT_PENDING_ID_V1` |

**Domain separation.** The root of an epoch above 0 is never used directly as
a MAC or KDF key for two purposes: the transport keys come from
`transport(e)`, the next root from `chain(e)`, and the proofs from
`confirm(e + 1)`. The ZWF direction keys of epoch `e` are
`KDF(DIR_{A,B}_{ROOT,TAG,HEADER}, transport(e))`. Epoch 0 keeps the direction
keys from the pairing root itself, which is what peers without root evolution
use; the pairing root's other uses are one-shot pairing MACs under labels of
their own and the voice-memo wrap key, which both peers must keep deriving
from the pairing root for memos to stay readable.

## Records

The records travel as ZMM records of type `0xF4` (`TYPE_ROOT_EVOLUTION`), only
in frames that carry a post-quantum secret. A receiver drops a record of this
type that arrives in the classical opening frame, as it drops any record but
cover there. Layout, big-endian:

```
version[1] = 0x02   kind[1]   epoch[8]   body
```

| Kind | Sender | Body |
| --- | --- | --- |
| 1 HELLO | both | `pendingId[32] flags[1]`; the id is all zero when no root is pending; flag `0x01` asks the peer for its ML-DSA identity key |
| 2 INIT | Alice | `replaces[32] x25519_A[32] ek_A[1184]`; `replaces` is the pending-root id Bob showed in his HELLO on this connection, all zero when he showed none |
| 3 RESP | Bob | `x25519_B[32] ct[1088] bobConfirm[32]` |
| 4 CONFIRM | Alice | `aliceConfirm[32]` |
| 5 DONE | Bob | `bobDone[32]` |
| 6 IDENTITY_FIRST | both | the first 1008 bytes of `mlDsaPublicKey[1952] ed25519Signature[64]`; epoch 0 |
| 7 IDENTITY_SECOND | both | the last 1008 bytes of the same; epoch 0 |

A record of another version is ignored. A malformed record of version 2 is
ignored. Each record fits one ZWF frame; the identity is split in two because
it does not.

## Protocol

**HELLO is built when it leaves.** Each side queues HELLO at the start of a
connection, but its content (epoch, pending-root id, flags) is read from the
store at the moment the record is encrypted, in the first post-quantum frame.
A post-quantum frame can only be sent after the peer's first frame has been
received, and the peer sends its first frame only after loading its keys for
the connection. A HELLO therefore never describes a state older than the
peer's own view when it started that connection. Nothing is ever decided on a
HELLO alone that cannot be undone, except the one case below where this
ordering makes the decision safe.

On HELLO from a peer at the same epoch `e`:

- If this side holds a pending root and the peer's id proves the same root,
  Bob makes the pending root current, deletes the root of `e` and sends DONE.
  Alice waits for DONE or for a stream under the new root.
- If Bob holds a pending root the peer does not show, Bob does nothing: a
  HELLO from a parallel connection may have been built before Bob stored it,
  and Alice may be about to confirm it. Only an INIT that names the root
  replaces it (below).
- If Alice holds a proved pending root the peer does not show, and that root
  was already stored when this connection started, Alice drops it: Bob's
  HELLO was built after Alice's first frame on this connection, so after Bob
  stored the root, and Bob can only lack a root he stored by losing it (a
  write lost to a power cut, an older copy restored). A root stored after the
  connection started is kept, since the HELLO may predate it. Alice then
  offers again on the same connection.
- Otherwise, if Alice holds no pending root, she may start an evolution: not
  while evolution is paused, not while another connection to the contact has
  an offer in flight, and not within ten minutes of the last evolution with
  the contact. Alice sends INIT with fresh ephemeral keys and, in `replaces`,
  the pending-root id Bob showed (or zero).

On INIT, Bob (at epoch `e`, not rate-limited, see below) answers if he holds
no pending root, or if `replaces` names the pending root he holds. He then
computes `root(e + 1)`, **stores it as pending before** sending RESP with his
confirmation; storing replaces the named root. An INIT that names a root Bob
does not hold while he holds another is ignored: it was built before Bob's
HELLO reached Alice, and her next HELLO will carry his id. Replacing is safe
because Alice offers only while she holds no pending root and no offer is in
flight on another connection, and Alice never acquires a root whose offer has
ended: an INIT therefore proves that Alice never stored the root it names and
never will.

On RESP, Alice computes the same root, checks Bob's confirmation, **stores the
root as pending and proved before** sending CONFIRM, and wipes her ephemeral
keys. On CONFIRM, Bob checks Alice's proof, makes the pending root current,
deletes the root of `e` and sends DONE. On DONE, Alice does the same.

**Which root a connection uses.** Alice dials under her pending root once Bob
has proved it (her pending root is always proved), and under her current root
otherwise. After `PENDING_DIAL_FALLBACK_AFTER` (2) consecutive dials under
the pending root that ended without a frame of Bob's authenticating, one dial
goes out under the current root, and the alternation continues until a dial
authenticates: Bob recognises both roots while an evolution is open, so Alice
reaches him under whichever he still holds. The side that accepts a
connection answers under the root the dialler's tag was made with, so both
directions of a connection always use a root both peers hold. Incoming tags
are recognised under the current and the pending root. When the first frame
of a peer's stream authenticates under the pending root, the pending root is
made current and the previous root deleted: the peer would not use it unless
it held it.

**Rate limit.** Bob answers at most `MAX_ANSWERS_PER_INTERVAL` (3) offers per
contact per `ANSWER_INTERVAL_MS` (10 minutes). An honest Alice offers once per
connection, so the burst covers a few connections that die before completing;
a holder of a copied root cannot make Bob encapsulate, agree and write roots
at frame rate.

**Identity exchange.** A side whose contact record has no ML-DSA key for the
peer sets the HELLO flag; the peer answers once per connection with its
ML-DSA-65 public key and an Ed25519 signature over it by its author key under
the label `org.zerionproject.transport/CONTACT_ML_DSA_IDENTITY_V1`. The
receiver records the key only if the signature verifies under the author key
it already holds for the contact, and never replaces a key it knows. This is
how contacts paired before both devices had hybrid identities come to share
version 2 safety numbers.

## Interruption and interleaving

A side proves possession of a root only after storing it, sends under a
pending root only after the peer proved possession of it, and never drops a
root the peer may hold. The states an interruption can leave, and how Alice's
dials alone (the only dials over Tor) resolve them:

| Left behind | State | Recovery |
| --- | --- | --- |
| Cut after HELLO or INIT | nothing stored | the next connection starts again |
| Bob stored, RESP lost | Bob: pending, unproved; Alice: nothing | Bob's next HELLO shows the root; Alice's INIT names it and replaces it |
| Alice stored, CONFIRM lost | Alice: pending, proved; Bob: pending | Alice dials under it and Bob's stream authentication promotes, or the HELLOs match and Bob promotes with DONE |
| Bob promoted, DONE lost | Alice: pending, proved; Bob: current | Alice dials under it; Bob answers under it; Alice promotes on his first frame |
| Alice's HELLO from a parallel connection reaches Bob between his RESP and her CONFIRM | Bob keeps the pending root | Alice's CONFIRM promotes it as usual |
| Bob's write of the pending root lost after Alice proved it (power cut, older copy restored) | Alice: pending, proved; Bob: nothing | Alice's dials under the pending root fail, she dials under the current root, sees Bob's HELLO without the root, drops it and offers again |
| Alice dialled under the current root once while Bob already promoted | Bob cannot recognise that one dial | the next dial goes out under the pending root again |

`ContactRootEvolutionTest` cuts the exchange at every step in both dialling
directions; `RootEvolutionInterleavingTest` runs the parallel-connection,
lagged-record, replay, lost-write and fallback cases with Alice-only dialling,
and random interleavings of all of them over many seeds, and checks that two
dials in a row carry data both ways and that the pair ends on one root.

## Keys out of sync

If the two sides hold roots that never match again (an older backup restored
on one side after the other side evolved past it more than seven days
later, or a fork of one side's database), Alice's dials are accepted and
closed without any frame authenticating. After `OUT_OF_SYNC_AFTER` (6) such
dials in a row the contact is marked out of sync
(`ContactConnectionKeysEvent`); the conversation shows it and the contact's
details offer to re-add the contact. Bob's side cannot attribute the refused
connections to a contact and shows the contact as offline. A new link
exchange between the two re-keys the existing contact in place
(`ContactManagerImpl.rekeyContact`): the pairing root and the evolved roots
are replaced by the new root and the contact, its history and its
verification are kept. The mark clears when a dial authenticates or the
contact is re-keyed. No automatic recovery from persisted secrets is
offered: a copier of the database holds those secrets too, so anything that
let two diverged devices re-key without a new out-of-band exchange would let
the copier back in and defeat the property this document provides.

## Compatibility

- **Android 3.0.14 and earlier** drop ZMM records of unknown type
  (`ZmmDbRecordSink` delivers only `TYPE_SYNC`), never send HELLO, and so never
  take part: the contact keeps its pairing root and everything works as
  before. Once the peer updates, the next connection evolves the root.
- **iOS** does not implement root evolution. An iOS peer that drops unknown
  ZMM types stays on the pairing root like an old Android peer. The iOS port
  must implement this document to gain the property; until then an iOS
  contact's root never evolves and its safety number stays at version 1.
- **Downgrade.** An Android build older than this one would read only the
  pairing root and could not reach a contact whose root has evolved. Android
  does not install older versions over newer ones, and the database schema
  version refuses older code.

## Backups and account transfer

A copy of the account (a backup file or a direct transfer) holds the roots of
the moment it was made. It connects to a contact only while that contact's
root has not evolved past the copy, so restoring an old backup reaches the
contacts talked to since only after re-adding them. This is the same
capability a copier of the database has, and the property root evolution
provides depends on it. To keep the documented "move to a new phone" flow
working, making a copy pauses new evolutions on the device for seven days
(`AccountBackupManager.ROOT_EVOLUTION_PAUSE_MS`); no root changes at all while
the copy is written, and the pause is written after the copy, so the copy
does not carry it and the new phone evolves its roots at once, after which
the old phone, and any older copy, can no longer connect. The pause stops new
offers and answers only: an evolution both sides already hold a root for may
still complete, and the copy holds that root too. The export and import
screens say what a late restore means.

## Limits

- An evolution protects against a party that copied a root and is **passive**
  during the evolution. A party holding the current root and active during
  the evolution can run it with each side and keep a position in the middle,
  as with any post-compromise scheme; it can also complete an evolution with
  one side before the legitimate peer does and displace that peer, which the
  peer then sees as keys out of sync.
- Until the first evolution with a contact, and with peers that do not
  evolve, the root is the pairing root and is never replaced.
- An evolution runs at the start of a connection, at most once per ten minutes
  per contact. A copied root therefore stops working at the first connection
  after that.
