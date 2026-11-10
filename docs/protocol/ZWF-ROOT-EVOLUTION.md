# ZWF contact root evolution, versions 2 and 3

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
receiver ignores it like any other unknown version. Version 2 shipped in
3.0.15. Version 3 (Android 3.0.19) is the same protocol with one change: the
key that the proofs and the pending-root id are made with is derived beside
the new root instead of from it, so the new root is not an input of any
record of the evolution that produces it. Transport use of the new root
starts only after Alice has verified Bob's RESP; from then on a frame under
the new root can complete the evolution on Bob's side instead of the
CONFIRM (see "Which root a connection uses"), as in version 2. Version 3 is used only between two peers that
both announce it (see "Version negotiation").

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
connections to the same contact never interleave two changes. A version 3 pending root is stored with
its confirmation key (in the `chainKey` column of the pending row, with bit 1
of `previousChainLength` set; bit 0 is the proved flag, as before), so that a
later connection can still make and check its pending-root id and its DONE.
A row written by an earlier release has neither bit 1 nor a separate key and
reads as version 2. The confirmation key is deleted with the pending row when
the root is promoted or dropped. Bob's pending
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
confirm(e+1)  = KDF("org.zerionproject.transport/ROOT_EVOLVE_CONFIRM_KEY_V1", root(e + 1))      version 2
confirm(e+1)  = KDF("org.zerionproject.transport/ROOT_EVOLVE_CONFIRM_KEY_V3", chain(e),
                  u64(e + 1), ss, dh, transcript)                                          version 3
```

The transcript's first input is the record version byte of the evolution
(`0x02` or `0x03`), so the two versions never produce the same root from the
same exchange. In version 3 `root(e + 1)` and `confirm(e + 1)` are two
outputs of the same keyed KDF over the same material under distinct labels:
holding one gives nothing about the other, and `root(e + 1)` is not an input
of any MAC sent in the evolution records. `ss` is the
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
`confirm(e + 1)`. In version 2 `confirm(e + 1)` is a subkey of the new root,
so the new root is an input of the proofs of its own evolution; version 3
removes that. The ZWF direction keys of epoch `e` are
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
version[1] = 0x02 or 0x03   kind[1]   epoch[8]   body
```

| Kind | Sender | Body |
| --- | --- | --- |
| 1 HELLO | both | `pendingId[32] flags[1]`; the id is all zero when no root is pending; flag `0x01` asks the peer for its ML-DSA identity key; flag `0x02` says the sender accepts version 3 evolutions |
| 2 INIT | Alice | `replaces[32] x25519_A[32] ek_A[1184]`; `replaces` is the pending-root id Bob showed in his HELLO on this connection, all zero when he showed none |
| 3 RESP | Bob | `x25519_B[32] ct[1088] bobConfirm[32]` |
| 4 CONFIRM | Alice | `aliceConfirm[32]` |
| 5 DONE | Bob | `bobDone[32]` |
| 6 IDENTITY_FIRST | both | the first 1008 bytes of `mlDsaPublicKey[1952] ed25519Signature[64]`; epoch 0 |
| 7 IDENTITY_SECOND | both | the last 1008 bytes of the same; epoch 0 |

HELLO and the identity records are always sent as version 2, so every peer
that implements root evolution reads them. INIT, RESP, CONFIRM and DONE carry
the version of the evolution they belong to. A record of another version is
ignored, and so is a malformed record; a version 3 HELLO or identity record
is malformed. Each record fits one ZWF frame; the identity is split in two
because it does not.

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
  the contact unless she or the peer is waiting for one (see Evolution before
  records). Alice sends INIT with fresh ephemeral keys and, in `replaces`,
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

**Rate limit.** Bob answers at most `MAX_ANSWERS_PER_INTERVAL` (6, 3 up to
3.0.18) offers per contact per `ANSWER_INTERVAL_MS` (10 minutes). An honest
Alice offers once per connection, so the burst covers a few connections that
die before completing; a holder of a copied root cannot make Bob encapsulate,
agree and write roots at frame rate. An offer Bob does not answer counts, for
the side that waits, like any other evolution that did not complete.

## Evolution before records

Up to 3.0.18 an evolution was attempted at the start of a connection and user
records were sent whether or not it completed. A party that held a copied
root and could withhold traffic (drop the RESP, hold back one direction so
that no HELLO arrives, or simply leave a long connection alone) could
therefore keep the copy valid for as long as it liked while the two peers
kept exchanging records. From 3.0.19 an evolution that is due comes first.

A 3.0.19 side sets flag `0x04` in every HELLO. A side applies the waiting
rules below to a contact that has ever sent a HELLO with that flag; this is
kept per contact and never cleared, so a HELLO without the flag, or a
connection on which no HELLO arrives, does not switch it off. The HELLO is a
record inside the authenticated stream, so a party that cannot forge frames
cannot change its flags.

- **The peer's HELLO comes first.** For a contact that has ever sent a HELLO,
  or whose root has evolved, a side that is not paused sends no user record
  on a connection before the peer's first HELLO of that connection has
  arrived (or an evolution with the contact has completed, which needs the
  peer's records as well), and closes the connection if the HELLO has not
  arrived `EVOLUTION_DEADLINE_MS` (30 seconds) after the connection
  started. Records are not even taken from the store
  before that, so nothing is marked as sent that never left. A party that
  holds back one direction from the first frame therefore stops both.
- **Due.** An evolution with a contact is due when
  `MIN_EVOLUTION_INTERVAL_MS` (ten minutes) or more have passed since this
  side last made a new root current with that contact, when it never did, or
  when the stored time lies in the future. The time is stored per contact. A
  side for which an evolution is due sets flag `0x10` in its HELLO, and an
  evolution is due for a side whose peer set it.
- **At the start of a connection.** If an evolution is due and neither side
  is paused, a side keeps sending only evolution records and cover frames
  until the evolution has completed for it on any connection: the new root
  is its current root and the old one is deleted. For Bob that is on
  CONFIRM, for Alice on DONE, for either on a HELLO or a first frame of a
  peer stream that proves the peer holds the new root. Alice's proved
  pending root does not count, since Bob has not confirmed it yet. Alice
  offers on a HELLO with flag `0x10` even if her own ten minute limit has
  not passed; the flag counts only in a HELLO of the epoch this side is at.
  If the evolution has not completed 30 seconds after the peer's HELLO
  arrived, the side closes the connection, and the dialler waits before the
  next dial as it does after a dial that failed, so a blocked evolution does
  not turn into a tight loop of connections. A connection that closes while
  another one to the contact is open is not counted this way.
- **Trying again on the same connection.** While Bob waits he repeats his
  HELLO every `EVOLUTION_NUDGE_MS` (five seconds). On such a HELLO Alice,
  if she is not paused, is at the same epoch and holds no pending root,
  makes an offer she could not make at first (another connection to the
  contact still owned one), or gives up an offer that got no answer for
  three of those intervals and makes a new one. If the HELLO shows the root
  she already holds as proved, she sends her CONFIRM again. So these cases
  resolve within seconds instead of after a reconnect.
- **On an open connection.** `LIVE_EVOLUTION_INTERVAL_MS` (one hour) after
  the last evolution, Alice offers again on the open connection and Bob sends
  a HELLO with flag `0x10`. Records keep flowing during this evolution, but
  a side closes the connection if it has not completed within 30 seconds,
  and the reconnect then falls under the rules above.
- **Paused.** A side whose evolutions are paused (below), or that is writing
  a copy of the account at that moment, sets flag `0x08` in its HELLO and
  neither waits nor is waited for. A side that becomes paused on an open
  connection says so in a HELLO at its next hourly check, or as soon as the
  peer asks it for an evolution, and the peer then expects no evolution on
  that connection until a HELLO without the flag or an offer arrives.
- **Earlier releases.** A peer that never sent flag `0x04` is not waited for
  beyond its HELLO: 3.0.15 to 3.0.18 pause for seven days after a backup and
  cannot say so, and waiting for them would cut them off. With such a peer
  Alice still offers at the start of a connection and every hour on an open
  one.

What this gives: a side sends only after its own root has been replaced, so
holding back the evolution holds back the records too. A party with a copied
root that only drops or delays traffic has two choices, to let an evolution
complete, after which its copy authenticates nothing, or to stop the pair
from exchanging records, which they notice. How a rotation that keeps
failing is shown to them is in "Renewal that does not complete". What it
does not give is in Limits.

Evolution records leave on a lane of their own that is never held back
(`ZppSendScheduler`), so two sides that both wait can still complete the
evolution they are waiting for.

**Identity exchange.** A side whose contact record has no ML-DSA key for the
peer sets the HELLO flag; the peer answers once per connection with its
ML-DSA-65 public key and an Ed25519 signature over it by its author key under
the label `org.zerionproject.transport/CONTACT_ML_DSA_IDENTITY_V1`. The
receiver records the key only if the signature verifies under the author key
it already holds for the contact, and never replaces a key it knows. This is
how contacts paired before both devices had hybrid identities come to share
version 2 safety numbers.

## Version negotiation

There was no capability signal for root evolution before 3.0.19, and the
contact's messaging minor version describes the messaging client, not the
transport, so version 3 is announced in the HELLO itself: a 3.0.19 side sets
flag `0x02` in every HELLO. Earlier releases ignore flags they do not know
and records of a version they do not know. Flags `0x04`, `0x08` and `0x10`
belong to Evolution before records.

- Alice chooses the version of an evolution when she offers it: version 3 if
  she supports it and the HELLO that triggered the offer, on the same
  connection, carries flag `0x02`; otherwise version 2. Her INIT carries that
  version.
- Bob answers an INIT in the version of the INIT. An earlier Bob never sees a
  version 3 INIT, because he never sets the flag, and an earlier Alice only
  sends version 2.
- RESP, CONFIRM and DONE must carry the version of the evolution: Alice drops
  a RESP whose version is not that of her offer, and both sides check
  CONFIRM, DONE and the pending-root id under the version stored with the
  pending root, never under the version of the incoming record.

Both sides therefore always run one evolution in one version. The HELLO
travels in an authenticated post-quantum frame under the current root, so
only a holder of the current root can remove the flag, and such a party can
already run the evolution with each side (see Limits).

**Across an update.** A pending root keeps the version it was stored with.
A version 2 root stored by an earlier release is promoted, matched or
replaced as before: its pending-root id is still the version 2 one, so a
HELLO from either release proves it, and an INIT that replaces it may be
version 3. The next evolution after both sides run 3.0.19 is version 3.
`RootEvolutionVersionTest` cuts an evolution at every step for every pairing
of current and earlier peers, and updates one side or the other in the
middle of an interrupted version 2 evolution.

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
on one side after the other side evolved past it more than 24 hours
later, or a fork of one side's database), Alice's dials are accepted and
closed without any frame authenticating. After `OUT_OF_SYNC_AFTER` (6) such
dials in a row the contact is marked out of sync (an open repair dial of
ONION_CLIENT_AUTH.md section 5 is not counted when it is refused, since a
peer that requires client authorization refuses it by design)
(`ContactConnectionKeysEvent`); the conversation shows it and the contact's
details offer to re-add the contact. Bob's side cannot attribute the refused
connections to a contact and shows the contact as offline. Alice cannot tell
diverged roots apart from other dials that end before Bob's first frame:
Bob having removed the contact while his old address still answers, which
shows the same mark (the in-app notice names both causes), or six
connections in a row lost to the network at that point, which shows it until
an evolution completes or a connection on which no evolution was due
authenticates (see "Renewal that does not complete"). A new link
exchange between the two re-keys the existing contact in place
(`ContactManagerImpl.rekeyContact`): the pairing root and the evolved roots
are replaced by the new root and the contact, its history and its
verification are kept.

**Who may re-key (from 3.0.19).** Up to 3.0.18 every completed add whose
peer turned out to be an existing contact re-keyed that contact, so a holder
of a copied identity whose contact request was accepted took over the
existing contact with its verification and history, and displaced the real
peer. From 3.0.19 a side re-keys an existing contact only when it meant to:
the user started the add from Re-add contact (a request sent from there is
marked as a reconnect, and a link pasted there pairs as a re-add), or
accepted a request marked as a reconnect after the warning it shows, or
scanned the other phone's code in person. The mark belongs to that one
pending contact (its id and creation time, `PendingContactFlags`): an add
that succeeds consumes it, a refused add removes the pending contact and
its marks with it, and so does every other removal of the pending contact
through the contact manager or the request code; a later pending contact
under the same link has another creation time and never inherits it. At the end of the pairing exchange each 3.0.19 side also
sends a `REPLACE_KEYS` record saying whether it means to re-key; a side
re-keys only if it means to and the peer did not say that it does not, and
otherwise refuses the add (`ContactExistsException`), leaves the contact's
keys as they were and shows the request as "Already a contact". Two 3.0.19
phones therefore either both re-key or both refuse. A 3.0.18 phone sends no
such record and always re-keys: against it the 3.0.19 side's own intent
decides, so a 3.0.18 phone that pairs with an existing contact without a
re-add on the 3.0.19 side ends up re-keyed while the 3.0.19 side keeps its
keys; the 3.0.19 side then sees the contact out of sync and repairs it with
Re-add contact. A copier of a contact's identity can still ask to reconnect
through a request marked as such: the receiver is warned that accepting
replaces that contact's keys and must confirm, and, as above, the displaced
real peer sees the keys out of sync. The mark clears when an evolution
completes, when a connection with no evolution outstanding authenticates,
or when the contact is re-keyed. No automatic recovery from persisted secrets is
offered: a copier of the database holds those secrets too, so anything that
let two diverged devices re-key without a new out-of-band exchange would let
the copier back in and defeat the property this document provides.

## Renewal that does not complete

The rules of "Evolution before records" hold the records back; this section
makes a rotation that keeps failing visible. Up to 3.0.18 the only health
notice was the out-of-sync mark above, raised by dials that did not
authenticate, and any dial that authenticated cleared it. A party holding a
copied root that let every connection authenticate and cut it before RESP,
CONFIRM or DONE therefore kept the pair on the old root with nothing shown
on either side. From 3.0.19 each side keeps per contact, in the settings
table beside the other evolution state (`RootKeyStore`, keys `stalls.`,
`stalledSince.`, `notRenewing.`):

- how many connections in a row ended with an evolution outstanding. A
  connection counts when the peer's stream, or a record from it, had
  authenticated on it; this side was still waiting for the peer's HELLO or
  for a due evolution to complete when the connection ended; no other
  connection to the contact was open; and the peer is one that waits for
  evolutions (it once sent flag `0x04`). The dialler counts it when its
  connection ends, the responder when its own does, so both sides count.
- when the first connection of the current run ended.

When the run reaches `NOT_RENEWING_AFTER_CONNECTIONS` (6), or it holds at
least `NOT_RENEWING_AFTER_MS_MIN_CONNECTIONS` (3) connections and the latest
is counted `NOT_RENEWING_AFTER_MS` (one hour) or more after the first one of
the run, the contact is marked as not renewing (`ContactKeyRenewalEvent`). The
conversation and the contact's details then say that the connection keys are
not being renewed, that a poor network can cause it, and offer to re-add the
contact. The run and the mark are cleared only by a completed evolution with
the contact (`RootKeyStore.renewed`, which also clears the out-of-sync mark,
since a completed evolution proves that the peer holds the root), by
re-keying the contact or by removing it. A connection on which nothing from
the peer authenticated is not counted; the six-dial rule above covers it on
the dialling side. A connection that ends with an evolution outstanding
while another connection to the contact is open is noted and counted once
when the last of those connections ends, whatever its own state, so a group
of overlapping connections counts at most once; a connection closed twice
counts once. If the stored run cannot be read the connection is not counted.
A clock that jumps forward by an hour between two stalled connections of a
run of three brings the notice forward; a clock that went back starts the
run again.

The out-of-sync mark is no longer cleared by a dial that merely
authenticated. It clears when an evolution completes, or when a connection on
which the peer authenticated ends with no evolution outstanding: the peer
does not wait for evolutions (an earlier release, iOS), either side has said
in its HELLO that it is paused, or this side made a new root current less
than ten minutes ago. In every other case an authenticated connection leaves
the mark as it is. Ordinary records, which between two 3.0.19 sides do not
flow before a due evolution anyway, can therefore not paper over a stalled
rotation on either side. The run of failed dials that raises the mark is
still ended by a dial that authenticates, so the mark is not raised by
failures spread over authenticated connections; the run of stalled
connections covers those.

What this does not give: six connections, or an hour, of a network that cuts
every connection between the HELLO and the DONE show the notice too; the
notice names that cause and goes away at the next completed evolution. With
a peer on 3.0.15 to 3.0.18, which cannot say that it is paused, no run is
kept and an authenticated connection still clears the out-of-sync mark, as
before, so the notice needs both sides on 3.0.19. A party holding the root
and active on the connection can run the evolution with each side, as
before, and causes no notice. `RootEvolutionStallWarningTest` runs the
stalled connections against the real connection code on both sides, the
hour bound, a paused and a not-due connection, an earlier release and the
unauthenticated and parallel cases; `RootEvolutionManagerTest` and
`RootKeyStoreDatabaseTest` cover the counting and the stored state.

## Compatibility

- **Android 3.0.15 to 3.0.18** run version 2. They never set HELLO flag
  `0x02` and ignore version 3 records, so a 3.0.19 peer evolves with them in
  version 2 and the new root is still used for the proofs of its own
  evolution, as in those releases.
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
  version refuses older code. A backup made by 3.0.19 and restored into
  3.0.15 to 3.0.18 (possible while the schema version is the same) would
  also misread a version 3 pending root: those releases treat the pending
  row as unproved and check its id under a key derived from the root, so an
  evolution that was in flight when the backup was made does not complete,
  and if the other phone had already moved to the new root the contact shows
  as out of sync until it is re-added. The backup format carries no app
  version, so this is documented rather than refused; restore a backup with
  the same or a newer app.

## Backups and account transfer

A copy of the account (a backup file or a direct transfer) holds the roots of
the moment it was made. It connects to a contact only while that contact's
root has not evolved past the copy, so restoring an old backup reaches the
contacts talked to since only after re-adding them. This is the same
capability a copier of the database has, and the property root evolution
provides depends on it. To keep the documented "move to a new phone" flow
working, making a copy pauses new evolutions on the device for 24 hours
(`AccountBackupManager.ROOT_EVOLUTION_PAUSE_MS`; seven days up to 3.0.18, a
time during which every root the copy holds stayed valid); no root changes
at all from
the start of the copy until the pause is written, and the pause is written
only once the copy has been saved or sent, so the copy does not carry it and
the new phone evolves its roots at once, after which the old phone, and any
older copy, can no longer connect. A copy that fails or is cancelled before it
is saved or sent writes no pause and leaves an earlier pause as it was. The
pause stops new offers and answers only: an evolution both sides already hold
a root for may still complete, and the copy holds that root too. The export
and import screens say what a late restore means.

## Limits

- An evolution protects against a party that copied a root and is **passive**
  during the evolution. A party holding the current root and active during
  the evolution can run it with each side and keep a position in the middle,
  as with any post-compromise scheme; it can also complete an evolution with
  one side before the legitimate peer does and displace that peer, which the
  peer then sees as keys out of sync.
- Until the first evolution with a contact, and with peers that do not
  evolve, the root is the pairing root and is never replaced.
- Between two 3.0.19 sides a copied root stops working at the first
  connection that is made ten minutes or more after the last evolution, or
  within an hour and 30 seconds on a connection that stays open, because a
  side sends no record before that evolution has completed for it. This
  holds against a party that withholds or delays traffic. A party that
  withholds everything keeps the copy valid for as long as it also keeps the
  pair from talking.
- Each side is held on its own. When one side has completed and the last
  record to the other is withheld, the first side's records can still flow
  on a second connection that was open in parallel, until the other side
  completes or its deadline closes that connection; a parallel connection
  that was opened before the evolution was due falls under the one hour
  rule.
- With a peer on 3.0.15 to 3.0.18 an evolution is still only attempted, and
  records flow once its HELLO has arrived whether or not the evolution
  completes.
- A contact that never sent a HELLO and whose root never evolved is treated
  as an earlier release. A party that withheld every HELLO since the pairing
  keeps it that way.
- A holder of the root who is active on the connection, not only
  withholding, is outside these rules as before. Such a party can now also
  make a side believe that a contact on an earlier release waits for
  evolutions, which silences that contact until it is re-added (re-adding
  or removing a contact resets what is remembered about it).
- With a peer on an earlier release only the offering side renews on an
  open connection, since such a peer offers at the start of a connection
  only.
- A path on which one direction takes longer than a few seconds can miss
  the 30 second limits and is then retried, where 3.0.18 delivered slowly.
- A pair whose connections authenticate but never complete a due evolution
  is shown as not renewing after six such connections in a row, or after
  three of them spread over an hour or more, not before; until then, and
  toward earlier releases always, the only sign is that records do not flow.
- A paused side keeps its roots for 24 hours after a copy of the account is
  made, and each new copy starts that time again. Someone who can make a
  backup on the device already holds everything on it.
- A database error while the pause is read is treated as paused.
- If a network cuts more evolutions within ten minutes than the responder
  answers (`MAX_ANSWERS_PER_INTERVAL`), records wait until that window has
  passed.
