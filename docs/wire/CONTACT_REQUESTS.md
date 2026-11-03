# Contact requests, invite links and link v2

Status: specification for Android 3.0.19, revision 3 (after two internal
adversarial reviews), phase 1 implemented at `c064f0c8` (core, no UI; the
deviations are listed in section 14). Written against branch `f4-invite` at
`6bf0a45a` from the owner requests of 2026-10-19 and 2026-10-26. Every "today"
statement names the code it was checked against.

## 1. Goals and non-goals

Goals:

- A adds B with nothing but B's ZerionID link. B sees a request and accepts,
  declines or blocks it later, when B's phone is online.
- Several labelled permanent invite links per account ("Support", "Press"),
  each withdrawable on its own.
- The Add-contact screen has two options only: **Add contact**
  (the Send invite screen, titled "Add contact" like its card) and **Scan QR**.
- The contact handshake (`HandshakeManagerImpl`, commitment check at line 175),
  the rendezvous (`RendezvousPollerImpl`, `ZtpDuplexPlugin`
  `createRendezvousEndpoint`) and Nearby pairing (BQP v5) are reused, with the
  per-row expiry and one-sided rendezvous of section 5.

Permanent rule, not negotiable: **no relay, no mailbox, no server, no
store-and-forward**. A request lives only on the sender's phone. It is
delivered directly over Tor to an onion service run by the receiver's phone at
a moment when both phones are connected; until then the sender's phone keeps
it and retries. Nothing is ever handed to a third device or service.

The Android BLE mesh does **not** carry requests. It does not fit the existing
mesh envelope: an `AsyncSealedSender` envelope opens only under the
recipient's published prekeys and carries a hybrid signature by the sender's
identity, while a requester has no prekey bundle for B and stays anonymous
until B accepts; the request is sealed to B's full hybrid link key, which the
sender obtains only from B's inbox over Tor; and acceptance completes over the
Tor rendezvous anyway. Offline adding stays with Pair Nearby.

Non-goals: usernames, directories or search; any change to how existing
contacts talk; delivery when the two phones are never connected at a common
moment within the windows of section 5.

## 2. Links and link v2 format

Today (`HandshakeLinkConstants`, `PendingContactFactoryImpl` lines 65 to 151,
`ContactManagerImpl.getHandshakeLink` lines 306 to 317): a link is `zerion://`
plus 104 lowercase Base32 characters encoding 65 bytes: `0x01 ||
H(HYBRID_COMMITMENT_LABEL, hybrid public key) (32) || X25519 public key (32)`.
It carries no onion. `LINK_REGEX` already accepts and ignores a `?...` suffix.

Three kinds of link key exist from 3.0.19:

| Key | Used by | Lifetime |
| --- | --- | --- |
| Per-add handshake key (today's identity hybrid handshake key) | Scan QR in person, v1 pairings bound to it | rotates after every add through it, as today |
| ZerionID key | Send invite, "Your ZerionID", mutual fallback from Send invite | replaced after every request accepted through it (section 3), by "Reset ZerionID" and by the flood detector of section 8 |
| Permanent link key, one per label | Invite links | long-lived; never rotated; ends on withdraw by the user; paused for a cooldown by the flood detector of section 8 |

A one-time key (section 4) is a fourth, sender-side kind, erased after use.

Link v2 adds the inbox onion as a query parameter:

```
zerion://<body>?i=<inbox>
body  = Base32(type || commitment(32) || x25519(32)), 104 characters
type  = 0x01 ZerionID (mutual flow capable)
        0x02 request-only (permanent invite links)
inbox = Tor v3 onion address of the link's inbox, 56 characters, without
        ".onion" (its own checksum and version byte included)
```

A ZerionID is 172 characters. Further `&name=value` parameters are reserved and
ignored. The inbox is a dedicated onion per link, never a contact, rendezvous
or channel onion. The type byte is read as `raw[0] & 0xFF` (today's parser
compares a signed byte). The inbox is not bound into the body; the effect of
substituting it is in section 11.

Parsing on 3.0.19:

| Input | Result |
| --- | --- |
| type 0x01 or 0x02, valid `i` | request flow (Send invite) |
| type 0x01, no `i` (v1 link) | notice "Older link. Both of you paste each other's link, or use Scan QR.", then today's mutual flow with the binding of section 10 |
| `i` present but not a valid v3 onion | "This link is damaged", nothing stored |
| type 0x02, no `i` | "This link is incomplete" |
| type 0x00 (53 characters) | unchanged mutual flow |
| type above 0x02 | `UnsupportedVersionException`, "newer version" message |
| any own link (per-add, ZerionID, permanent) or a link whose `i` is any own inbox | "This is your own link" |

Older peers, checked in code:

| Link given to | Behaviour |
| --- | --- |
| Android 3.0.18 or older, ZerionID | `LinkExchangeFragment.getRemoteHandshakeLinkOrNull` returns `"zerion://" + group(1)`, dropping the query: mutual flow with the ZerionID key, which completes when the 3.0.19 user pastes the old peer's link |
| Android 3.0.18 or older, permanent | type 2 above `FORMAT_VERSION` 1: `UnsupportedVersionException`, toast `unsupported_link` ("This link comes from a newer version of Zerion. Please upgrade") |
| iOS `ContactExchange.parseAddContactLink`, ZerionID | reads Base32 up to `?`, finds 104 characters and version 1: mutual flow |
| iOS, permanent | `version == formatVersion` fails: "Invalid link format. Links start with zerion://" |

## 3. Inbox onion service

Keys. Each ZerionID and permanent link has its own hybrid agreement key pair and
its own random Ed25519 onion key (`NEW:ED25519-V3`, blob kept as `_SECRET`).
For the puzzle of this section each inbox has a 32-byte `inboxSecret` drawn
from the CSPRNG once per process, held in memory only and kept across DEL_ONION
and ADD_ONION republishes of that inbox within the process (the replay set and
the event counts live in memory too). A process restart draws a new one.

Publication, while Zerion's Tor is connected:

- **Permanent link:** always, until withdrawn (owner decision 4).
- **ZerionID:** while `now < lastSharedAt + 30 days`. `lastSharedAt` is set only
  by a tap on Share, Copy or Show QR for the ZerionID inside Zerion (Send invite
  or Invite links), or on **Keep receiving** on the Invite links screen, never
  by merely opening a screen. Zerion cannot see a ZerionID passed on outside the
  app (forwarded from a chat, printed, read aloud), so such a share does not
  extend the window. The Invite links screen shows "Receiving requests until
  <date>" and **Keep receiving**, which restarts the 30 days (the main
  button only when the ZerionID is not receiving or has less than 3 days
  left, `NEARLY_EXPIRED_MS`, otherwise a text button); its "More info" says
  that sharing the ZerionID again starts the count over. Anyone who ever held the ZerionID can watch whether its inbox
  answers; the window limits that to 30 days after the user last invited
  someone, matches the sender's retry window, and costs nothing while closed.
- **Reset ZerionID:** DEL_ONION on the old inbox at once and a new key, inbox
  and link. The old key record is kept only while undecided requests through
  it remain, so they can still be accepted.
- **Rotation after an accept:** when the user accepts a request that arrived
  through the active ZerionID (not through a permanent link, not a Scan QR
  pairing), `ContactRequestReceiver.accept` performs the same rotation as
  Reset ZerionID in the accepting transaction, right after the pending
  contact is created: the old ZerionID is retired, a new key, inbox and link
  are generated, and the old inbox gets DEL_ONION at the next refresh. The
  old key record stays only while undecided or accepted requests through it
  remain, so those can still be accepted and retried; accepting one of them
  later rotates nothing, since the old key is no longer the active ZerionID.
  As after a reset, the new ZerionID waits for its first share (`lastSharedAt`
  is 0): nobody holds the new address yet, so publishing it would only cost a
  descriptor and an introduction point; the next Share, Copy or Show QR tap
  opens it for 30 days. The rotation never fails the accept: if the request's
  key is not the active ZerionID (or there is none) it is retired on a best
  effort basis and the current ZerionID is kept or a fresh one created. Blocks
  stay with the old ZerionID and go with its record, as after a reset. The Add
  contact and Invite links screens say "It changes after each contact you
  accept with it." under "Your ZerionID"; the "Your ZerionID changed after you
  shared it." refusal on the mutual fallback keeps working for a link copied
  before the rotation.

Client authorization, as restricted discovery only. The inbox is published
through `TorOnionServiceControl.publish` (which requires a `ClientAuthV3` key)
with one key derived from the link: `seed = H(".../INBOX_CLIENT_AUTH", body(65),
inbox public key(32))`, clamped as X25519. The sender registers it with
`addClientKey` for the attempt and removes it with `removeClientKey`. This is
an exception to ONION_CLIENT_AUTH.md section 2 (directional keys generated at
random by the dialer): every link holder shares one key. The protection is
marginal: it only stops someone who has the 56-character address without the
link from decrypting the descriptor and introducing. The descriptor is served
to anyone who asks for the address, so a bare address still shows whether the
inbox is published.

Tor proof of work. From 3.0.19 the in-tree Tor 0.4.9.14 (`packaging/tor-android`)
is built with `--enable-gpl`, which includes Tor's onion-service `pow` module,
and every inbox is published with the ADD_ONION keywords
`PoWDefensesEnabled=1 PoWQueueRate=50 PoWQueueBurst=250`
(`ContactRequestConstants.INBOX_POW_QUEUE_RATE` and `INBOX_POW_QUEUE_BURST`).
Contact, rendezvous and channel onions are published without them.
`TorOnionServiceControl.publishInbox` first asks `GETINFO version` on the same
control connection and adds the keywords only from Tor 0.4.9.2, the release
that accepts them in ADD_ONION. If Tor still refuses them (a 552, 512 or 513
reply that names an unrecognized keyword or the PoW keywords, as an older or
differently built Tor gives), it publishes that inbox once more without them,
so the inbox stays reachable; any other refusal fails the publication. If
`GETINFO version` fails or closes the connection, the inbox is published
without the keywords on a new connection. Whether an inbox was published with proof of work is kept in memory
(`ContactLinkStore.isInboxPowProtected`) and reported as
`ContactLinkInfo.isPowProtected`.

The rate and burst are below Tor's defaults of 250 and 2500 because the
service runs on a phone: every introduction Tor admits costs a rendezvous
circuit and then reaches the app's own puzzle and slots (3 solved, 1 unsolved
per inbox), so an inbox can use only a few introductions at a time, and a
smaller burst bounds the memory Tor's queue may hold for pending
introductions. What it defends: under a flood of introductions Tor raises the
effort it suggests in the descriptor and serves the introductions that carry
the most work first, so introductions without work are dropped inside Tor
before a rendezvous circuit is built or the app sees a connection. The app
puzzle below works one step later, on a connection that already exists, and
rations the slots. Neither stops a determined attacker with enough computing
power; Tor's proof of work raises the cost of a flood and lets senders who did
the work through first. The `HiddenServiceEnableIntroDoS*` options are torrc
lines that ADD_ONION does not set.

ADD_ONION for an inbox: `Flags=Detach,V3Auth,MaxStreamsCloseCircuit
MaxStreams=1`, the proof-of-work keywords above when Tor supports them, the
derived `ClientAuthV3` key, one port to a fresh `openServiceListener()`
target. Inbox connections go to an inbox handler only,
never to the ZTP connection handler.

Puzzle, stateless per epoch. Epoch `e = floor(now / 10 minutes)`; level `k`
from 0 to 3 with difficulty `d = [0, 16, 20, 24][k]`, so the ceiling equals
the sender cap of 24 bits; nonce `N(e, k) = mac(".../PUZZLE_EPOCH",
inboxSecret, e, k)`, so nonces come from the CSPRNG secret and never repeat
within their validity. Level is set from **work-bearing events only**, never
from connection counts. A work-bearing event is one admitted solved
connection, counted once, at the moment it is given a slot and KEY is sent.
The level is set at the start of each epoch from the events of the last 10
minutes, rises at once within an epoch when the events of the current epoch
cross a higher threshold, and never falls within an epoch. Thresholds: under
10 events level 0, under 30 level 1, under 100 level 2, else level 3.

Zero-work eviction. The inbox also counts unsolved-slot replacements and
connections closed without a valid SOLUTION. When more than 30 of these fall
within the last minute, the level of the current epoch becomes at least 1 (it
still never falls within the epoch). These counts never count as events for
higher levels.

Flood detector, per inbox, in memory (`InboxPuzzle`). A flood is declared
when, within a sliding window of 10 minutes (`FLOOD_WINDOW_MS`), the inbox
saw more than `FLOOD_ZERO_WORK_PER_10_MIN` (300) zero-work connections (the
same unsolved replacements and closes without a valid SOLUTION as above), or
when the level has been 3 for `FLOOD_MAX_LEVEL_EPOCHS` (3) consecutive epochs:
the epoch in which it rose to 3 counts as the first, every following epoch
that starts at level 3 (at least 100 admitted solved connections in the epoch
before) adds one, and an epoch that starts below 3 resets the count to zero.
The detector fires once per puzzle, and the puzzle of a removed inbox is
discarded. On a flood `InboxService.flood` runs on the inbox worker. A
permanent link is **paused**, not withdrawn: it stays ACTIVE and valid, its
inbox gets DEL_ONION at once, and `pausedUntil` is set to now plus a cooldown
that starts at 1 hour (`FLOOD_PAUSE_MIN_MS`) and doubles for every further
flood within 24 hours of the previous one (`FLOOD_PAUSE_RESET_MS`), capped at
24 hours (`FLOOD_PAUSE_MAX_MS`); a day without floods starts again at 1 hour.
`refresh` treats a paused link as closed (`ContactLinkStore.isInboxOpen(txn,
link, now)`) and republishes it by itself once `now >= pausedUntil`, with a
fresh puzzle; `InboxService` schedules a refresh for the earliest pause end,
and the periodic refresh, a Tor reconnect and an app start cover a missed
timer. Holders of the link only see it unreachable during the pause. The
Invite links screen shows "Too many requests. Paused until <time>" on the link
with two actions, "Resume now" (`resumePermanentLink`: clears the pause and
republishes; the doubling count is kept) and "Remove" (the normal withdraw,
which also clears the link's flood slot). The ZerionID gets the same treatment
as Reset ZerionID (retired, new key, inbox and link, the new one waiting for
its first share). Flood state lives in the encrypted settings (namespace
`org.zerionproject.contactrequest`) in a bounded set of slots: one slot
`flood.zeroId` for the ZerionID (`.link`, `.at`) and `FLOOD_NOTICE_SLOTS` (10)
slots `flood.0` to `flood.9` for permanent links (`.link`, `.at`, `.label`,
`.pausedUntil`, `.count`, `.lastAt`); a link takes its own slot, a free slot
or the one with the oldest flood, and dismiss, withdraw and reset clear the
slot, so the namespace never grows with the number of floods. A
`ContactLinkFloodedEvent` is broadcast for the fixed-text notification ("Invite
link paused: too many requests. It resumes by itself." or the
ZerionID text); the ZerionID card shows "Too many requests. Reset on <date>" until
dismissed. Requests already received stay. A flood report whose transaction
fails (`DbException`) is queued again after `FLOOD_RETRY_MS` (5 seconds) and
the puzzle keeps its state until it succeeds; a report for a link that is no
longer active still drops the puzzle and slots of that inbox. When an inbox
is unpublished (pause, flood, withdraw, reset, rotation) a connection that was
already admitted (its SOLUTION passed `admit` and it was sent KEY while the
inbox was published) is left to finish: the link's private key is cleared
only when the last admitted session has ended or `UNPUBLISH_GRACE_MS` (10
seconds) has passed, whichever comes first, and a session still open then is
closed; the link row is held in memory (`ContactLinkStore.hold`) so a withdraw
or reset does not delete it under that session, and is removed after the
session if no request needs it. `InboxSession` passes the admission time to
`ContactRequestReceiver`, which accepts a REQUEST for a link that is no longer
ACTIVE only when the link was retired or withdrawn at or after that admission
and at most 10 seconds before the request arrived, so a sender in the middle
of a paid handshake gets its acknowledgement. Every other connection, also
one that solved but was not yet admitted, is closed at once, and a new
connection is refused as before (the onion is gone and a session admitted
after the link ended does not pass the receiver check). At sign-out every
session is closed and the key cleared at once, also for an inbox still in its
grace; when Tor stops, the sessions end with their sockets. A link row that
is no longer needed is removed when its last session ends, and at the next
start if the app stopped in between. The detector is app level only: it changes no Tor
setting, never falls back to clearnet and never disables client
authorization.

Why these thresholds do not catch a legitimate burst: 20 friends who send at
the same time are 20 solved connections, which keep the level at 1 (10 to 29
events), and at most one zero-work connection each (a sender without a
solution reads HELLO and disconnects, then reconnects with its solution), with
retries 1, 2 and 5 minutes later; even 50 friends who all fail every attempt
within the window stay far below 300 zero-work connections and reach level 2
at most (30 to 99 events). 300 zero-work connections in 10 minutes is one
every two seconds for the whole window, and level 3 for three epochs needs at
least 100 solved 24-bit connections in each of two consecutive epochs, which
no group of friends produces. `InboxPuzzleTest` covers a burst of 50 solved
requests (no flood), a sustained zero-work flood (flood, reported once), the
sliding window and the level-3 count with its reset when the level falls.

A solution `x` is valid when `Blake2b-256(".../PUZZLE", N(e, k), linkId, x)`
starts with `d` zero bits, for `e` the current epoch and `k` its current
level or one below it, or `e` the previous epoch and `k` its final level or
one below it. A level 0 solution carries no work, so it is accepted only for
the `(e, 0)` that the HELLO of the same connection announced. Each `(e, k, x)`
is inserted into an in-memory replay set for 20 minutes atomically at the
moment its connection is given a slot and KEY is sent, never earlier; a
solution already in the set is refused. A process restart draws a new
`inboxSecret`, which voids outstanding solutions.

Wire exchange, one request per connection, extended record header (version
0x01, type, uint32 length; the length must equal the size below and is checked
before any payload byte is read):

| Type | Direction | Payload | Size |
| --- | --- | --- | --- |
| 0x01 HELLO | inbox, at once on every connection | protocol 0x01, level k, d, epoch e (uint64), N(e, k) (32) | 43 |
| 0x02 SOLUTION | sender, as its first record | e (uint64), k, x (8) | 17 |
| 0x03 KEY | inbox, after a valid SOLUTION | link hybrid public key (1216), inbox ephemeral X25519 `Y` (32) | 1248 |
| 0x04 FULL | inbox, instead of KEY | empty | 0 |
| 0x07 SLOTS_BUSY | inbox, instead of KEY, unauthenticated | empty | 0 |
| 0x05 SEALED | sender | ephemeral hybrid public key (1216), ML-KEM-768 ciphertext (1088), tag and ciphertext (16 + 1536) | 3856 |
| 0x06 ACK | inbox | `mac(".../ACK", mk, requestId, kind)` | 32 |

The inbox sends HELLO at once on every connection. At level 0 the sender
reads HELLO and answers in the same connection with SOLUTION carrying that
HELLO's `(e, 0)` and any `x`. At level 1 and above a sender without a valid
solution reads HELLO and disconnects, solves offline, and reconnects; a sender
holding a solution writes SOLUTION as soon as the connection opens, then
reads HELLO and the reply, so it moves to the solved slots at once. If that
HELLO names the same epoch and level as its solution but a different
`N(e, k)`, the inbox restarted: the sender re-solves at once, which is not a
failed attempt. The inbox closes a connection whose first record is not a
valid SOLUTION. The sender gives up on a HELLO with `d` above 24
(retrying on its schedule), and aborts if `H(HYBRID_COMMITMENT_LABEL, key)`
differs from the link's commitment or the key's X25519 half differs from the
link's (the test of `HandshakeManagerImpl.rendezvousKeyMatches`).

Slots and deadlines, per inbox: 3 slots for connections that presented a valid
solution and 1 slot for an unsolved one. A new unsolved connection replaces the
current unsolved one; an unsolved connection is held at most 5 seconds. A
connection that has sent a complete SOLUTION record leaves the unsolved slot
and is never replaced as unsolved while it is being validated. A solved
connection is never evicted for an unsolved one. A newly solved connection
finding the 3 solved slots busy receives SLOTS_BUSY and is closed; its
solution is not entered in the replay set and is not counted as an event.
SEALED must arrive within 10 seconds of KEY. The work itself has no connection deadline: its time scales
with `d` and is bounded by epoch validity (10 to 20 minutes); the sender
bounds its own work as below. `Y` is generated per connection and erased when
it closes.

Sender work bound. From 3.0.19 a sender never works on one puzzle for more
than 90 seconds (`SENDER_SOLVE_BUDGET_MS`): the solver checks the time every
1024 hashes, in addition to its iteration bound, and gives up when the budget
is spent. Before it starts a level it uses a hash rate measured once per
process the first time a paid level comes up: it hashes for 300 ms to let the
runtime compile the loop, then counts the hashes of the next 300 ms
(`CALIBRATION_WARMUP_MS`, `CALIBRATION_MS`, in blocks of 1024). It refuses a
level whose expected work (2^d hashes) at that rate, doubled as a safety
margin, would exceed the budget. A measurement of the first 4096 hashes, as
first implemented, ran while the loop was still interpreted and read 8 to 10
times below the steady rate, which made a Pixel 9 Pro refuse 20 bits. Either way the
attempt ends as too hard: it counts as one failed attempt, the Sent row says
that the other phone is very busy and Zerion will try again later, and the
retry schedule continues. The solver runs on its own thread at the lowest
thread priority, so the screen stays responsive. 24 bits stays the hard
maximum. Measured with the app's own solver class run through `app_process`
with the JIT on (the way an installed release build runs this loop; the debug
build is interpreted and 8 to 10 times slower): Moto g06 power, 3 runs each,
16 bits 0.16 to 0.96 seconds, 20 bits 0.09 to 6.6 seconds, 24 bits 41 to 90
seconds, steady rate about 280 000 hashes per second. Pixel 9 Pro: 16 bits
0.2 to 0.46 seconds, 20 bits 30 to 33 seconds, 24 bits 53 seconds. At that
rate 24 bits is expected to take about a minute, so with the doubled margin
both phones refuse level 3 and solve levels 1 and 2: while an inbox is under
attack they show "very busy, try later" instead of draining their battery.

## 4. Request record

Sealing reuses the `AsyncSealedSender` primitives (hybrid X25519 plus
ML-KEM-768 agreement combined with BLAKE2b in `HybridKeyAgreement`,
`deriveKey` and `mac`, `XSalsa20Poly1305AuthenticatedCipher`) without a sender
signature. Labels are under `org.zerionproject.core.contactrequest/`, written
`.../` here.

Sender, with B's verified key `L`, `linkId` (the link commitment), inbox onion
public key `O`, and `helloHash = H(".../HELLO", e, k, N(e, k), KEY payload)`:

```
(ct, ss) = crypto.hybridEncapsulate(L)
E        = crypto.generateHybridAgreementKeyPair()
eph      = crypto.deriveSharedSecret(".../EPHEMERAL", Y, X25519 half of E)
mk       = crypto.deriveHybridSharedSecretAsResponder("CONTACT_REQUEST_V1",
               L, E, ss, helloHash, O, linkId, ct, eph)
key      = crypto.deriveKey(".../SEAL_KEY", mk)
nonce    = first 24 bytes of crypto.mac(".../SEAL_NONCE", mk)
blob     = XSalsa20Poly1305(key, nonce, padded plaintext)
```

The inbox computes `eph` with `Y` and E's X25519 half, then `mk` with
`crypto.deriveHybridSharedSecret("CONTACT_REQUEST_V1", E.pub, linkKeyPair, ct,
helloHash, O, linkId, ct, eph)`. Both sides reject an all-zero X25519 result
for `eph`. Forward secrecy caveat: erasing `Y` keeps recorded traffic
unreadable after a later theft of the link key only as long as X25519 holds
(the gain is classical; the ML-KEM half has none), and it gives nothing against
an inbox substituter, who picks `Y` itself.

Plaintext: `uint16 length || BDF list || zero padding`, always 1536 bytes:

| # | Field | Rule |
| --- | --- | --- |
| 0 | format | 1 |
| 1 | kind | 1 request, 2 withdraw |
| 2 | requestId | 16 random bytes, the same on every retry |
| 3 | linkId | must equal the receiving link's commitment |
| 4 | oneTimeLink | 65-byte type 0x01 body of a fresh hybrid key pair `T` (request only) |
| 5 | pop | `mac(".../POP", deriveSharedSecret(".../POP_DH", L x25519, T x25519), helloNonce, requestId)` with `helloNonce = N(e, k)` (request only) |
| 6 | name | 1 to 50 UTF-8 bytes (`MAX_AUTHOR_NAME_LENGTH` counts bytes) |
| 7 | note | null or 1 to 200 characters (code points), at most 800 UTF-8 bytes |
| 8 | timestamp | sender clock rounded down to the hour, ms |
| 9 | senderTag | `mac(".../SENDER_TAG", accountTagSecret, linkId)`, 32 bytes |
| 10 | reconnect | boolean: the sender started the request from Re-add contact and asks the receiver, who already has the sender as a contact, to replace that contact's keys (section 9) |

A withdraw carries fields 0 to 3. A request has all eleven fields. The one-time link has no inbox: after
acceptance the rendezvous connects A to B (section 5). `pop` proves A holds the
private half of the one-time X25519 key, so A cannot point B's rendezvous at a
third party's key. The label text never leaves B.

Receiver order after exact length, decryption (failure: close without reply)
and the `linkId` check. Replay state is keyed on (requestId, kind):

0. **Withdraw** (kind 2), before every other rule: always acknowledged
   identically, whatever the state, including unknown requestIds. An undecided
   request with that requestId is deleted; after Accept, B's pending row is
   removed and the request shows "Cancelled by sender". (requestId, 2) is recorded.
1. A known (requestId, 1) is acknowledged again with no new entry (a retry
   after a lost ACK; declined and blocked included).
2. A oneTimeLink already seen under another requestId is refused (close
   without reply).
3. `pop` must verify.
4. oneTimeLink must not equal any own link key, any `pendingContacts` key or
   any contact's handshake key.
5. Name and note limits; control and bidirectional override characters
   removed; `ReservedNames` hits shown as "No name".
6. Blocked (linkId, senderTag): acknowledged, discarded.
7. One undecided request per (linkId, senderTag), a newer replacing the older.

All deadlines use B's receive time. A timestamp more than 48 hours from it is
not dropped: the request is stored, acknowledged and shown with "Their clock
is off by more than two days, so times may look wrong."

What B learns before accepting: the name and note A typed, which link was used,
the receive time, and an opaque tag that links all requests from one account
through one link. Not A's identity keys, contact onion or network address; the
record size is fixed. After the handshake: A's identity and profile name,
contact transport properties and a safety code. What A learns before an answer:
that B's inbox was reachable at the delivery time.

## 5. Sender and receiver state

Schema 72 to 73 (`Migration72_73`), private material in `_SECRET` columns:

| Table | Columns |
| --- | --- |
| `pendingContacts` (changed) | adds `expiry BIGINT NULL` (null keeps `timestamp + RENDEZVOUS_TIMEOUT_MS`) and `rendezvousMode INT DEFAULT 0` (0 both ways, 1 dial only, 2 publish only) |
| `contactLinks` | linkId, localAuthorId, kind (ZerionID, permanent, per-add binding), state (active, retired, withdrawn), label, hybridPublicKey, hybridPrivateKey `_SECRET`, inboxKey `_SECRET`, createdAt, lastSharedAt, retiredAt, withdrawnAt |
| `requestAccount` | localAuthorId, accountTagSecret `_SECRET` |
| `outgoingRequests` | requestId, localAuthorId, targetLink, alias, shownName, note, oneTimePublicKey, oneTimePrivateKey `_SECRET`, createdAt, state, attempts, nextAttemptAt, deliveredAt, pendingContactId |
| `incomingRequests` | requestId, linkId, oneTimeLink, name, note, senderTag, senderTimestamp, receivedAt, state, acceptedAt, pendingContactId |
| `requestReplay` | requestIdHash, kind, oneTimeKeyHash, linkId, expiresAt (receivedAt + 32 days) |
| `requestBlocks` | linkId, senderTag, createdAt |

The reconnect flag of a request is kept in the settings, per request id
(`ReconnectMarks`: one namespace for sent and one for received requests), and
the pending contact that an accepted or delivered reconnect request creates
carries the re-add mark of `PendingContactFlags` (see ZWF-ROOT-EVOLUTION.md,
"Who may re-key"), so that the pairing may replace an existing contact's keys.

A per-add binding row holds no key (the per-add key stays in the identity);
section 10 explains it. The migration is idempotent (add a column only if
missing, `CREATE TABLE IF NOT EXISTS`). The SharedPreferences keys
`own_link_last_shared` and `own_link_last_shared_at` (`OwnLinkMemory`) are
deleted after migration; share state lives in `contactLinks`.

Sender states (A's clock for every A deadline):

| State | Pending row | Ends by |
| --- | --- | --- |
| QUEUED | none; one-time keys only in `outgoingRequests` | ACK to DELIVERED; createdAt + 30 days to NOT_DELIVERED |
| DELIVERED | created now, `rendezvousMode` 1, `expiry = deliveredAt + 32 days` | handshake to ADDED; expiry to NO_ANSWER |
| ADDED | removed by the add | one-time keys erased |
| NOT_DELIVERED, NO_ANSWER, CANCELLED | none | keys erased; row kept until dismissed |

Because no pending row exists while QUEUED, `RendezvousPollerImpl` starts
nothing for undelivered requests.

Retries (QUEUED, and WITHDRAW), only while Tor is connected: at once, then
after 1, 2, 5, 10, 20 and 30 minutes, then hourly until 72 hours, then every 4
hours until createdAt + 30 days; each delay times a random 0.8 to 1.2. When Tor
becomes connected an overdue attempt runs 5 to 60 seconds later. A timeout, a
HELLO above 24 bits, or FULL after a paid solution each count as one failed
attempt. A solution is reused for a reconnect within its epoch validity. On
SLOTS_BUSY the sender reconnects with the same solution after a random 5 to 30
seconds, up to 3 times within the solution's validity, and only then counts
one failed attempt. A HELLO showing an inbox restart (section 3) leads to an
immediate new solution, not a failed attempt. A paid solution is one with
`d` above 0; a request pays for at most 2 per hour, and an attempt that would
need a third waits for the hour to pass. Attempt timeout 120 seconds.

Rendezvous is one-sided. `createRendezvousEndpoint` derives two onions from
the pair's key material, one per role. A, in DELIVERED, publishes nothing
(`rendezvousMode` 1, a non-publishing variant) and dials the onion of B's role,
the one it would compute as `remoteOnion` (B's role from `isAlice(L x25519, T
key pair)`), every 20 seconds for the first 15 minutes after delivery, then
every 2 minutes until 2 hours after delivery, then every 15 minutes until 48
hours after delivery, then hourly, while connected. A dial to an onion that is
not published yet fails quietly: Tor asks each of the responsible directories
(at most six) in turn, gets "not found", closes the request and clears its
per-directory requery history for that onion, so the next dial asks again. The
first 15 minutes cover two people adding each other at the same time: the wait
after an Accept is then the receiver's onion publication (usually 30 to 60
seconds) plus about 20 to 30 seconds and the connection setup. Later the 2
minute phase bounds it to a few minutes. Only the 3 most recently delivered
requests get the 20 second phase at once (`MAX_EAGER_DIALS`); older ones in
their first 15 minutes dial every 2 minutes, so sending many requests at once
cannot pile up connection attempts across requests (one request whose dials
stall in Tor can still have up to about four waiting at once, each ending
after at most 65 seconds). A dial counts as due half a poll tick (5 seconds)
early, so the cadence stays at 20 seconds on the 10 second tick, and a clock
set back makes a row due at once instead of silencing it. A row whose delivery
time lies in the future (the clock was ahead and was corrected) dials every 15
minutes until that time has passed. Overlapping dials to the same onion share
one descriptor fetch in Tor. Cost: during those 15 minutes the phone asks up
to six directories about every 20 seconds and stays awake while it does
(roughly 270 directory requests instead of 45), for each of the 3 newest
delivered requests, also when the Accept comes later. Android Doze may defer
these timers while the phone is
idle; dialing resumes in maintenance windows and when the app is opened. B, on
Accept, creates a pending row from A's oneTimeLink with its link key pair as
snapshot, `rendezvousMode` 2 (publish its role's onion, never dial) and `expiry
= min(acceptedAt + 2 days, receivedAt + 32 days)`. The handshake is today's. If
B's window ends, the row shows "Could not connect" with **Try again** (2 more
days, never past receivedAt + 32 days). B deletes undecided requests at
receivedAt + 30 days.

Rotation. `rotateHybridHandshakeKeys` runs only when the snapshot used is the
identity's current per-add key (today it runs on every add,
`ContactManagerImpl` lines 194 and 219). ZerionID, permanent and one-time keys
never trigger it.

Decline is silent: no message is sent, since one would require B's phone to
contact A, telling A that B is online and has read the request, and it gives a
pressuring sender a definite answer. A sees "Delivered", from 3 days after delivery "No answer yet",
until NO_ANSWER, the same for declined, blocked and unopened requests. Block is
silent too: the request is deleted and (linkId, senderTag) is stored. A reset
ZerionID has a new linkId, so blocks do not carry across a reset.

Cancel. QUEUED: delete. DELIVERED: remove the pending row, erase keys, and send
a sealed WITHDRAW with the same requestId on the retry schedule within
deliveredAt + 32 days; B handles it by rule 0 of section 4.

## 6. Receiver UI

- **Requests** screen from a "Contact requests" row at the top of the Chats
  home (always shown; badge = unopened count; the home screen has no per-tab
  toolbar menu) and from the notification, tabs **Received** and **Sent**; it
  replaces the pending-contacts list, whose mutual rows appear under Sent.
  Only one Requests screen is ever on the back stack, and it keeps its
  dialogs across a rotation.
- Accepted rows stay under Received as "Accepted, connects when you are both
  online" until the contact is added; at the end of the window they show
  "Could not connect" with **Try again**. A request the sender withdrew after
  acceptance shows "Cancelled by sender" with **Dismiss**. A request whose
  pairing ran but was refused because the sender is already a contact and
  the add was not a re-add on both sides shows "Already a contact" with
  **Dismiss**, under the name the user gave when accepting
  (`IncomingRequest.STATE_ALREADY_CONTACT`, set from
  `PendingContactAlreadyContactEvent` by `ContactRequestManagerImpl`); a
  sent request ends the same way (`OutgoingRequest.STATE_ALREADY_CONTACT`,
  **Remove**).
- A received request marked as a reconnect (field 10) shows "Asks to
  reconnect as an existing contact" under the link in its row, and its detail
  says that the sender claims to be an existing contact and that accepting
  replaces that contact's connection keys on both phones, so that their old
  phone can no longer reach this one. Accepting it marks the pending contact
  as a re-add; a request without the flag never replaces a contact's keys.
- Sent rows: Not sent yet, Delivered (no answer yet), Accepted, Not
  delivered, No answer, Cancelled, Cancelled after delivery, and Cancelling
  (a cancellation still being delivered), which has no action until it ends.
  **Cancel request** (confirmed) while open, **Remove** once ended.
- While the account is paused after a restore (or the pause flag is found
  persisted), the Contacts screen asks once per session "Is this the only
  device using this account?" (**Yes, continue** / **Not now**), and Send
  invite, Requests and Invite links show a banner until it is confirmed.
- Received row: sender-chosen name, "via your ZerionID" or "via <label>",
  receive time. Never the note. The name is marked as chosen by the sender
  (not checked) in the detail dialog only, not in the row.
- Detail: name with the caption "Name chosen by the sender, not checked."
  right under it, note as plain text (no link detection), link, receive time,
  "After you accept, compare the safety code." **Accept** (asks the local
  name, prefilled), **Decline** (undo for 5 seconds), **Block** (confirm, naming
  the one inbox the block covers: "You will get no more requests from them
  through your ZerionID." or "... through this link: <label>.", and "... through
  the link this request came from." for a link without a label).
- Notification on a new channel "Contact requests": "New contact request" or
  "%d new contact requests", no name, note or label, opening Received behind
  the app lock. None for duplicates, withdraws or blocked senders.
- Name and note are deleted on accept, decline, block or expiry.
- After Accept the row shows "Connecting…", or "Adding contact…" while the
  pending contact is being added, with a progress bar for 2 minutes after
  Accept or Try again; a sent row whose pending contact is connecting or being
  added shows the same.
  When the contact is added (`ContactRequestAddedEvent`, local only, carries
  the new contact id) the row stays as "Contact added" with **Open chat**
  while the screen is open, with a snackbar offering the same; the screen
  never closes by itself.
- A sent request in Delivered for 3 days or more (`RequestRow.NO_ANSWER_YET_MS`,
  counted from delivery on this phone) shows "No answer yet" until it ends as
  No answer at 32 days. Nothing is sent.
- The sender's phone shows "Your contact request was accepted" on the
  Contact requests channel when its request becomes a contact, with no name,
  note, label or link, opening the new chat behind the app lock (the Sent tab
  when an earlier one is still shown). None while the Requests screen is in
  the foreground or for a muted contact; it is removed when that chat or the
  Requests screen is opened.

## 7. Permanent links

- Created under Send invite > Invite links with a label (1 to 32 characters,
  local). At most 5. Own key pair, inbox onion, type 0x02 body; never rotated.
- Per link: Share, Copy, Show QR (shown on screen, not saved as an image),
  request count, block count,
  **Remove** (DEL_ONION at once; the key stays only while undecided requests
  through it remain, so they can still be accepted).
- A permanent link is the owner's fixed identifier, for support or other
  specific purposes: it never rotates, unlike the ZerionID, which changes after
  every accepted request. The Invite links intro says "Invite links stay the
  same until you remove them. Good for support."
- A flooded permanent link (section 8) is paused by the app for a cooldown
  (1 hour, doubling within a day, at most 24 hours) and republished by itself;
  the link stays valid, the Invite links screen shows "Too many requests.
  Paused until <time>" with "Resume now" and "Remove", and a fixed-text notification
  says that a link was paused.
- Limits shown behind "More info" on the Invite links screen: the inbox
  answers whenever Zerion is connected, so link holders see when the phone is
  online; anyone with the link can send requests, and a link that gets too
  many requests pauses for a while; anyone can
  publish a link labelled "Zerion Support", so people must get a support link
  from a source they trust and compare the safety code.

## 8. Abuse limits

Every limit is per inbox; nothing is shared across inboxes, so load on one link
reveals nothing about another and cannot block another.

| Limit | Value |
| --- | --- |
| Tor introductions | onion-service proof of work when the bundled Tor supports it (from 3.0.19): queue rate 50, burst 250 (section 3) |
| Tor streams per circuit | 1, circuit closed on a second |
| Connections | 3 solved slots, 1 unsolved slot held at most 5 seconds; solved never evicted for unsolved; SLOTS_BUSY when the solved slots are taken |
| Difficulty | levels 0, 16, 20, 24 bits from work-bearing events only (section 3); ceiling 24 bits |
| Zero-work eviction | more than 30 unsolved replacements or closes without a valid SOLUTION per minute hold the epoch at level 1 or above |
| Flood pause | more than 300 zero-work connections in a sliding 10 minutes, or level 3 for 3 consecutive epochs: a permanent link is paused (inbox removed for 1 hour, doubling for every further flood within 24 hours, cap 24 hours, republished by itself; Resume now and Remove on the Invite links screen) and the ZerionID is reset by the app (section 3), with a notification |
| Sender cap | gives up above 24 bits; at most 90 seconds of work per puzzle, and a level is refused when its expected work at the measured hash rate, doubled, exceeds that (section 3); at most 2 paid solutions per hour per request |
| Solution replay | (e, k, x) entered when its connection gets a slot, refused again for 20 minutes |
| Undecided requests | 50 per ZerionID, 200 per permanent link; only newly stored requests count; then FULL |
| Record sizes | exact, from the header |
| Sender | 20 open outgoing requests; one per target link |

The ceiling equals the sender cap, so a phone that solves 24 bits within its
budget is never locked out by the puzzle alone; a slower phone refuses the
levels it cannot finish in time and shows the busy notice while an inbox is
under load, the price of bounded work on low-powered phones. The puzzle only
prices work: no puzzle a phone can afford stops an
attacker with a GPU, who can solve at 24 bits fast enough to keep the solved
slots busy. Tor onion-service proof of work, on every inbox from 3.0.19 when
the bundled Tor supports it, makes a flood of introductions cost work and
serves senders who did the work first; it raises the cost of making a link
unreachable but does not rule it out for an attacker with enough computing
power, and an inbox that Tor published without it (section 3) has only the
puzzle. Anyone who holds a permanent link (or a ZerionID within its window) and
spends that power can still make it unreachable. What the flood detector of
section 3 costs an attacker: every inbox is published with `MaxStreams=1` and
`MaxStreamsCloseCircuit` (`TorOnionServiceControl.INBOX_FLAGS` and
`INBOX_OPTIONS`), so a second stream opened while one is still open on the
same rendezvous circuit closes the circuit. Tor's `HiddenServiceMaxStreams`
limits simultaneous streams only: connections made one after another can
reuse one rendezvous circuit, so the 301 zero-work connections that trip the
detector need not cost a new circuit or a new INTRODUCE1 each, and Tor's proof
of work does not price them. Tripping the detector is therefore cheap; what
bounds the damage is the pause itself. The level 3 path costs about 2^31
hashes over 10 to 20 minutes. The detector buys the attacker downtime, not
the address:
a flooded permanent link is paused for a cooldown that starts at 1 hour and
doubles for repeated floods (cap 24 hours), then comes back by itself, so the
owner of a support link does not have to share it again; the user can also
resume it at once or remove it. The ZerionID is reset, since it rotates after
every accept anyway and is cheap to share again. Both can still be done by
hand before the detector fires. A sender whose attempt ends because the inbox asks for more
than the cap, or for more than this phone can solve within its budget, shows "Their phone is busy. Zerion will try again." on the Sent row, and keeps its retry schedule. Debug builds
have "Measure invite puzzle" under Settings > Connections, which solves
16, 20 and 24-bit puzzles with the real solver, without the budget, and
shows the times and the measured hash rate, to measure low-end phones; release builds do not contain it.

## 9. Add-contact screen

Two cards replace today's three (`AddContactChooserFragment`):

- **Add contact** (the Send invite screen, titled "Add contact" like its
  card). "Their ZerionID or link" paste field; the name and note fields appear
  only once a link is in the field (pasted, typed, scanned or opened from a
  link). For a v2 link: "Their name (only you see it)", "Your name (they see
  it)" (prefilled with the profile name), optional note with a 200 counter
  (counted in characters, not code units) and the helper text "They see it when
  they open your request.", **Send request**. A v1
  link is handled on the Send invite screen itself: it shows the notice of
  section 2, hides the request fields and pairs through the ZerionID fallback of
  section 10 when **Add contact** is pressed, after the link is parsed. A
  classical (0x00) link is refused there with "Older link. Ask them to update
  Zerion, or use Scan QR." Below: "Your
  ZerionID" with Share, Copy and Show QR (today's "share your link") and Invite
  links. **Reset ZerionID** sits on the Invite links screen (also reached from
  Settings), next to the permanent links and the blocked senders.
- **Scan QR.** One screen with two modes, chosen when it opens from the
  same Tor state as the connection chip (connected means the Tor plugin is
  active and offline mode is off):
  - Connected: today's in-person QR exchange with the per-add key (both scan,
    contact marked verified, key rotates), under the line "Scan each other's
    code".
  - Not connected: the Pair Nearby (BQP) code is shown instead and the camera
    scans for one, under the line "No internet: pairing nearby over
    Bluetooth".

  In either mode a scanned BQP code goes to nearby pairing; a scanned link
  with `i` opens Send invite prefilled (not verified); a link without `i`
  continues the in-person exchange when connected and, when not connected,
  says that the code needs an internet connection. There is no separate
  "Pair nearby" entry.

Re-add contact (from a chat's settings, for a contact whose keys are out of
sync) opens the same Add contact screen in re-add mode: a request sent from
it carries the reconnect flag and the screen says so ("This request tells
them you are already their contact and want to reconnect..."), a pasted v1
link pairs as a re-add, and a code scanned in person is a re-add by itself.
The pairing replaces the existing contact's keys only when both sides meant
to; the rule and its 3.0.18 limit are in ZWF-ROOT-EVOLUTION.md, "Who may
re-key".

Compatibility. Android phones on 3.0.18 or older and iOS phones can still be
added by pasting each other's links on both phones (from Send invite, as in
section 2) or with Scan QR. They cannot send or receive invites. Invites on
iOS are not implemented yet and are not claimed anywhere.

The numbers in this specification (at most 5 permanent links, the 30-day
ZerionID window, the 24-bit difficulty cap of the sender, 20 open outgoing
requests, 32-character labels, 200-character notes) are named constants in
`ContactRequestConstants` that the app reads through the API. They can be
changed in code only; there is no remote configuration, now or later.

## 10. Compatibility, migration and parity

- Both sides need 3.0.19 for requests; `i` is the capability signal.
- Implementation traps for the mutual fallback from Send invite:
  `ContactManagerImpl.addPendingContact(link, alias, sharedOwnLink, ...)`
  compares `sharedOwnLink` with `getHandshakeLink(txn)` (throwing
  `OwnLinkChangedException`), and `addPendingContact(txn, ...)` snapshots
  `identityManager.getHybridHandshakeKeys(txn)` into
  `setPendingContactOurKeys`. Both must use the ZerionID for pairings started
  from Send invite. The fallback creates the ZerionID if none exists yet. A
  per-add binding younger than 2 days is used instead, and then forgotten,
  only when the fallback names no shared ZerionID and the binding is newer than
  the ZerionID's last share; from 3.0.19 Android records no binding when the
  QR code is merely shown, so bindings come only from the migration of
  `OwnLinkMemory` below.
- Migration: pending rows keep their 2-day expiry; the per-add key stays the
  identity's handshake key; an `OwnLinkMemory` entry for a per-add link shared
  in the last 2 days becomes a per-add binding row in `contactLinks`; the
  ZerionID and its inbox are created at the first Share, Copy or Show QR (or the
  first fallback pairing).
- Backup import: link records are restored, but inbox publishing and request
  sending stay paused until the user confirms this is the only active device;
  two devices serving one onion would split requests between them.
- iOS parity (`Zerion Ios`, `feature/zwf-wire-port`): query and type 0x02
  parsing with the "newer version" message; ZerionID and permanent keys; inbox
  client and service with the derived client key (needs the iOS port of
  ONION_CLIENT_AUTH.md) and the epoch puzzle; one-sided rendezvous and per-row
  expiry; Requests UI; rotation rule. Desktop (`feature/desktop`): the same.

## 11. Security claims and limits

Draft rows for `docs/protocol/SECURITY_CLAIMS.md` (first version 3.0.19,
DESIGN GOAL until section 12 passes, external review NONE):

| Claim | Implementation | Source files | Tests | Limitation |
| --- | --- | --- | --- | --- |
| Contact requests use no relay, mailbox or server | Kept in the sender's database, delivered directly to the receiver's inbox onion | `ContactRequestSender`, `InboxService` | `ContactRequestSenderTest` | Both phones must be connected at a common moment within the windows |
| Only the link owner's phone can read a request | Sealed to the link's hybrid key plus a per-connection ephemeral X25519 key, bound to HELLO, KEY, epoch and inbox key | `ContactRequestCrypto` | `ContactRequestCryptoTest`, vectors | Name and note are unauthenticated; forward secrecy is classical only |
| Before accepting, the receiver learns no sender identity or address | One-time key with proof of possession, per-link tag, fixed size | `ContactRequestCrypto` | `ContactRequestPrivacyTest` | The tag links one account's requests through one link |
| Decline and block are silent | No message; blocked senders get a normal ACK | `InboxService` | `InboxServiceTest` | The sender sees delivery |
| Request notifications show no content | Fixed text, own channel | `AndroidNotificationManagerImpl` | `RequestNotificationTest` | Shows that a request arrived |
| Inbox onions are separate and removed on withdraw or reset | One onion per link; DEL_ONION | `ContactLinkStore`, `InboxService` | `InboxLifecycleTest` | See limits |
| A request cannot replace an existing contact's keys unless both users meant it | The pairing re-keys an existing contact only when this side's pending contact carries the re-add mark (Re-add contact, a reconnect request accepted after its warning, or an in-person scan) and the peer's `REPLACE_KEYS` record does not say no; otherwise the add is refused and the request shows "Already a contact" | `ContactManagerImpl`, `PendingContactFlags`, `ContactExchangeManagerImpl`, `ContactRequestReceiver`, `ContactRequestSender`, `ReconnectMarks` | `ContactManagerImplTest`, `ContactExchangeIntegrationTest`, `ContactRequestReceiverTest`, `ContactRequestSenderTest` | A holder of a copied identity can send a reconnect request; the receiver must confirm after a warning. A 3.0.18 peer always re-keys (ZWF-ROOT-EVOLUTION.md, "Who may re-key") |
| The ZerionID rotates after every accepted request | `ContactRequestReceiver.accept` retires the active ZerionID and creates a new key, inbox and link in the accepting transaction (not published until the next share; the rotation never fails the accept); permanent links and Scan QR pairings rotate nothing | `ContactRequestReceiver`, `ContactLinkStore` | `InboxLifecycleTest`, `ContactRequestReceiverTest` | The old key stays while requests through it are undecided or accepted; blocks stay with the old ZerionID |
| A flooded inbox is paused or reset by the app | Per-inbox detector (300 zero-work connections in 10 minutes or level 3 for 3 epochs) pauses a permanent link (DEL_ONION for 1 hour, doubling within 24 hours, cap 24 hours, republished by itself; Resume now and Remove) or resets the ZerionID, with a fixed-text notification; no Tor setting changes; bounded flood slots; a failed report is retried | `InboxPuzzle`, `InboxService`, `ContactLinkStore`, `AndroidNotificationManagerImpl`, `InviteLinksController` | `InboxPuzzleTest`, `InboxLifecycleTest`, `RequestNotificationTest`, `InviteLinksControllerTest` | An attacker who holds a link can make it unreachable for the cooldown, at the cost of a rendezvous circuit and a proof-of-work gated introduction per connection; a ZerionID shared outside the app must be shared again after a reset |

The existing row "Pairing-key rotation" must be rewritten: the per-add key
(Scan QR) rotates after every add through it; ZerionID and permanent keys
are long-lived (reset, withdraw); one-time keys are erased after use. It must
also say that pairings other than Scan QR now share one static key, the
ZerionID, so contacts added that way can tell they used the same link key (they
could already link the user through identity keys). Its test
`ContactManagerImplTest.convertingPendingContactRotatesHandshakeKeys` is
rewritten to cover rotation for per-add snapshots and no rotation for the other
three.

Limits to state:

1. A permanent inbox shows the phone's online pattern to every link holder; a
   ZerionID does so for 30 days after each share, and an open ZerionID inbox shows
   that the user shared it recently. After "Reset ZerionID", after an
   accepted request through it and after a flood reset, the old ZerionID stops
   receiving immediately, so a ZerionID passed on to several people serves only
   the first one who is accepted; the others see "Not delivered" until they
   get the new one. The difficulty in HELLO reveals coarse request
   volume to anyone polling it.
2. All inboxes come and go with Tor, so someone holding two of a user's links
   can tell they belong to one phone.
3. While a request is open, a modified receiver can see when the sender is
   online (from A's dials, up to 32 days; to about 20 seconds in the first
   15 minutes after delivery, then to 2 minutes, 15 minutes or an hour), and
   from the dial rate whether the sender delivered several newer requests.
4. Anyone with a link can send requests, and with enough computing power can
   make the link unreachable (section 8). Tor onion-service proof of work, on
   inboxes from 3.0.19 when the bundled Tor supports it, raises that cost and
   no puzzle stops a determined attacker. Anyone with a link can also trip
   the flood detector cheaply (300 zero-work connections in 10 minutes, which
   one rendezvous circuit can carry one after another because MaxStreams
   limits only simultaneous streams, or level 3 into a third consecutive
   10-minute epoch, 10 to 20 minutes after it was first reached); the pause
   bounds the damage: a permanent link is then paused for 1 hour, doubling for
   repeated floods up to 24 hours, and comes back by itself, so the attacker
   buys downtime and not the address; a ZerionID is reset and the user has to
   share the new one. The detector never touches Tor settings, clearnet or
   client authorization.
5. Name and note are unauthenticated; fake support links are possible; the
   safety code is the check.
6. The senderTag binds only unmodified clients and links all requests from one
   account through one link.
7. Someone who substitutes the inbox in a link before A receives it can drop
   A's request (A never sees "Delivered") and send B a request of their own at
   the same moment; they cannot read A's request or forge B's ACK, but they
   choose `Y`, so forward secrecy does not apply to that request.
8. Client authorization on inboxes only stops holders of the bare address.
9. B's stored requests are protected at rest by the database encryption only.
10. A ZerionID not shared for 30 days never receives; its senders see only "Not
    delivered".
11. A WITHDRAW cannot reach a closed inbox; the undecided request then stays
    on B's phone until receivedAt + 30 days.
12. FULL is answered before KEY, so while a link is full the inbox cannot
    recognise a retry after a lost ACK or a WITHDRAW; the sender keeps
    retrying and may end at "Not delivered" although B holds the request.
13. A backup copy carries the paused flag, which the live device sets in its
    database while the copy is taken; if the app stops between the two
    steps, the live device also stays paused until the user confirms it is
    the only active device.

## 12. Test plan

Unit (JVM, fake clock, no logging):

- Links: every row of the tables in section 2, including `raw[0] & 0xFF`, own
  links and own inboxes; the 3.0.18 `LINK_REGEX` and parser on v2 links.
- Crypto: round trip; a change to HELLO, KEY, epoch, `O`, linkId, `Y` or one
  byte fails; all-zero `eph` rejected; erased `Y` cannot reopen a recorded
  SEALED; `pop` with a foreign key fails; constant record size; vectors in
  `docs/wire/test_vectors/CONTACT_REQUEST_v1.txt`.
- Puzzle: level from work-bearing events only (connection floods leave it at
  0), rise within an epoch, current and previous epoch accepted, superseded
  level refused, replay set, new `inboxSecret` after restart; flood detector:
  50 solved requests are no flood, 301 zero-work connections in 10 minutes
  are, the window slides, level 3 for 3 epochs is, and the count resets when
  the level falls.
- Lifecycle: a flooded permanent link is paused and republished by itself at
  the deadline (fake clock), the pause doubles within a day, is capped at 24
  hours and resets after a quiet day, Resume now and Remove while paused,
  bounded flood slots with the oldest overwritten, a flooded ZerionID is still
  reset (one slot), a flood report that hits a busy database is retried, a
  report for an inactive link drops its detector, an admitted connection
  survives the unpublish; an accept through the ZerionID rotates it (new
  ZerionID unpublished until shared, old inbox removed, old key kept for its
  requests, a later accept through the old key rotates nothing), an accept
  through a second active ZerionID never fails; an accept through a permanent
  link rotates nothing.
- Inbox: length checks, slot rules (solved never evicted, unsolved 5 seconds),
  deadlines, FULL counting only new entries, replay order with WITHDRAW first,
  blocks do not carry across a ZerionID reset, reset closes the old inbox.
- Sender: schedule and jitter, Tor up and down, FULL after a paid solution as
  one failure, no pending row while QUEUED, cancel in each state, every expiry
  on its own clock, dial schedule 20 seconds, then 2 minutes, then 15
  minutes, then hourly.
- Rendezvous: modes 1 and 2, the onion A dials, per-row expiry, Try again;
  rotation rule; fallback binding (ZerionID, or a per-add binding under 2 days).
- `Migration72_73` against the real schema on both backends in this tree,
  HyperSQL (JVM) and SQLCipher (`SqlCipherDatabaseForTests` on an emulator,
  never on the owner's phones), run twice to prove idempotence; the
  SharedPreferences keys are gone afterwards. There is no H2 backend.
- Backup import pauses inboxes and sending until confirmed.

Two phones (debug app on both, or the Moto release app as the old peer; never
the Pixel release app; `adb install -r` only), with a debug build shortening
days to minutes:

1. Upgrade step first: Moto release 3.0.18 with populated pending rows,
   `adb install -r` the 3.0.19 release build; rows survive and complete.
2. Moto sends while Pixel is offline; Pixel comes online; notification without
   content; note only on open.
3. Accept while Moto is offline; Moto comes online and dials at its next poll
   (within 20 seconds in the first 15 minutes after delivery, within 2
   minutes in the first 2 hours, otherwise within 15
   minutes, while the app is active; Doze may defer it on an idle phone);
   safety codes match.
4. Decline and Block (Moto ends in "No answer"; a repeat after Block never
   shows; after "Reset ZerionID" the old link is "Not delivered" and a request
   through the new ZerionID shows again); Cancel before and after delivery and
   after Accept ("Cancelled by sender").
5. Two labelled permanent links; withdraw one; undecided requests through it
   still acceptable.
6. Moto release 3.0.18 pastes Pixel's ZerionID (mutual fallback) and a permanent
   link ("newer version").
7. Scan QR: in person, BQP routing, Pair nearby offline.

## 13. Open questions for the owner

1. Decided for 3.0.19: Tor is rebuilt with `--enable-gpl` (new pinned
   binaries) and every inbox uses Tor's proof-of-work defence when the bundled
   Tor supports it (section 3).
2. Ship requests on Android 3.0.19 before iOS parity, given iOS users get only
   the mutual fallback and cannot open permanent links?
3. User-facing names "ZerionID" and "Invite links"?
4. The numbers: 5 permanent links, ZerionID inbox open 30 days after each share,
   difficulty ceiling 24 bits equal to the sender cap.

## 14. Implementation status

Phase 1 (core, no UI) is implemented on `f4-invite`. The code follows
revision 3 and the puzzle and slot amendments, except as listed here.

### Deviations from revision 3

1. Hybrid private keys (link keys and one-time keys) are stored in `_BINARY`
   columns, as `pendingContacts.ourPrivateKey` already is, because `_SECRET`
   is a fixed 32-byte type on HyperSQL. The 32-byte secrets (`inboxKey`,
   `accountTagSecret`) are `_SECRET`. All of them are inside the encrypted
   database.
2. `outgoingRequests` has an extra `cancelledAt` column and an internal
   WITHDRAWING state (shown as cancelled), the start of the withdraw retry
   schedule. Cancelling a queued request keeps the row as CANCELLED without
   keys until it is dismissed; if the attempt that was running turns out to
   have delivered it, the row becomes WITHDRAWING and a WITHDRAW is sent.
   A WITHDRAWING request does not count towards the 20 open requests or the
   one request per link.
3. The inbox onion key is a 32-byte CSPRNG seed turned into the address and
   the `ED25519-V3` blob locally (as rendezvous onions are), not
   `NEW:ED25519-V3`, so a link carries its inbox before Tor first starts.
4. Rule 1 also acknowledges, with no entry, a request whose (requestId, 2) is
   recorded, so a reordered request cannot bring back a withdrawn one. A
   WITHDRAW is recorded only for a requestId the receiver holds; any other
   WITHDRAW is acknowledged and leaves nothing.
5. The key of a retired or withdrawn link is kept while undecided or accepted
   requests through it remain, so Try again can restore the pending row's
   snapshot after the rendezvous wiped it.
6. FULL is answered before KEY (limit 12 of section 11); no protocol change.
7. The backup copy carries the paused flag and a stop during the copy leaves
   the live device paused (limit 13 of section 11).
9. Field 10 (reconnect) was added after revision 3, with the re-add mark on
   pending contacts and the `REPLACE_KEYS` record of the pairing exchange, so
   that a request can repair an out-of-sync contact from a distance without
   letting any accepted request replace an existing contact's keys.
8. SLOTS_BUSY at level 0 is retried up to 3 times too, with a fresh
   in-connection solution, since there is nothing to reuse.
9. A level 0 solution is accepted only for the `(e, 0)` that the HELLO of the
   same connection announced, so "one below" at level 1 admits no free
   solutions.
10. The sender refuses, as one failed attempt, any HELLO whose level is above
    3, whose difficulty is above 24 or whose difficulty is not the level's
    (0, 16, 20, 24), also when the HELLO signals a restart; the solver stops
    after 2^30 hashes.
11. A sender keeps a solution for 20 minutes of its own clock after solving
    it and never compares its clock with the inbox epoch; the inbox refuses
    stale solutions and the restart and refusal paths handle the rest.
12. The inbox indexes its replay records by one-time key and by link, answers
    FULL when a link holds 4 times its undecided cap in replay records, and
    counts rule 7 replacements and blocked senders' requests in a per-link
    limit of 30 per hour, answering FULL above it. A rule 7 replacement
    raises the "request received" notice at most once per hour per (link,
    tag).
13. A request arriving through a retired or withdrawn link is refused even if
    its connection began before the link closed, and unpublishing an inbox
    closes its open connections.
14. A mutual pairing started from the old link exchange or the in-person QR
    exchange with no per-add
    binding compares the link shown on screen with the current per-add key
    and refuses if it rotated in between; the Send invite fallback refuses
    any own link.
15. Owner request of 2026-10-27: the ZerionID rotates after every request
    accepted through it (section 3) and a permanent link stays fixed. The
    accept of a request through the old, retired key rotates nothing. Review
    round 2 (ZR-12): the new ZerionID is not published until the user shares
    it, like a reset one; carrying the window over published an address that
    nobody held.
16. Owner request of 2026-10-27 and review round 2 (ZR-01): the flood
    detector of section 3 pauses a flooded permanent link (cooldown 1 hour,
    doubling within a day, cap 24 hours, automatic republish) instead of
    withdrawing it, because withdrawing turned "degrade" into "kill" for the
    price of one circuit per connection, and resets a flooded ZerionID. Flood
    state is kept in bounded slots in the encrypted settings rather than on
    the link record (the ZerionID record is deleted with its last request
    while the notice must stay until dismissed, and settings rows cannot be
    deleted individually, so slots are reused). `ContactLinkFloodedEvent`
    drives a fixed-text notification on the Contact requests channel. No
    schema change.

