# ZTP and ZPP: Online Transport and Pull Rhythm

Two protocols carry the online path. ZTP is the transport seam that turns a
carrier into a byte stream between two paired contacts. ZPP is the rhythm that
runs over that stream, sending one fixed-size frame per time slot so that, within
each of its two rates, sending and idling look the same to an observer.

Both run above ZWF (see ZWF-MODE3FULL.md). ZTP produces the stream, ZPP decides
what goes in each frame, and ZWF seals the frames.

## ZTP: Zerion Tor transport

ZTP is a transport seam, not a bespoke framing. It dials and accepts connections
and hands the resulting raw stream to the session stack. The default carrier is
Tor v3 onion services, reached through the inherited onion wrapper. The same seam
has an I2P variant (see EMBEDDED-I2P.md).

Behaviour:

- Outbound connections dial a contact's Tor v3 onion on port 80 through a SOCKS
  factory. The outbound side knows which contact it dialled.
- The local side publishes a hidden service and accepts up to 64 inbound
  connections. The socket timeout is 20 seconds (30 seconds up to 3.0.17),
  about five idle frames, so a session that has gone silent is closed and
  dialled again within 20 seconds. Before its tag is read, a
  connection holds one of 16 pre-tag slots and must deliver the tag within a
  5-second deadline. Both slots are taken before the connection is handed to
  a worker, and a connection that finds no free slot is closed at once.
- During an onion rotation the announced next address is served on a
  listener of its own, so a session that arrives on it is known to have
  reached the new address.
- A dial the transport did not make because Tor was not connected is
  reported as not attempted. The poll does not dial the contact again for
  30 seconds (a wait that neither grows nor counts as a failed dial, and
  that Tor becoming connected clears at once); a dial asked for by a
  queued message, an acknowledgement, a contact just added, a dropped
  connection or a slow reach-out is still made during the wait. A dial
  Tor did not make uses none of the descriptor refreshes after a dropped
  connection, is not counted against an announced next address, and does
  not make a contact try that address again at once as a failed dial to
  the current address does (from 3.0.18). Up to 3.0.17 such dials counted
  towards the three failed dials after which a contact stopped trying
  the announced address until it learned another one.
- An update from a contact that carries no announced next address clears
  the one this side holds for that contact, and a rotation abandoned for
  a revocation withdraws its next address from the update at once, so no
  contact keeps dialling an address that is never published (from
  3.0.18). Clearing it does not reset the limit of one notification an
  hour about a contact's new address.
- A next address is announced only while it is published, and a
  promotion publishes the new address before it removes the old one
  (from 3.0.18).
- While a rotation waits for a contact that has not moved, the
  announcement is sent again an hour after the new address was created
  and then once a day, so a contact on 3.0.17 or earlier, which ignores a
  second announcement within an hour of the first, receives it (from
  3.0.18). Such a contact that already failed three dials to the
  announced address does not try it again until it updates, and the
  rotation waits for it meanwhile while it still reaches the old
  address.
- The phone that is not the designated dialer for a contact (the smaller
  author id dials) stops waiting for that contact once nothing has been
  heard from it for 90 days: no authenticated session and no received
  message on any transport, the time being kept per contact in encrypted
  settings and updated by a connection opened or a message received, at
  most once an hour. The designated dialer never stops waiting, so the
  other phone can always reach it at an address it kept; any contact
  within the 90 days restarts the clock, and the network settings list
  only the contacts still holding the rotation back (from 3.0.18). The
  silence is measured on the device clock, so a clock set 90 or more days
  ahead retires the old address on that phone at the next hourly check;
  this cannot split the pair, since the designated dialer never stops
  waiting. A clock set back writes nothing: a last contact time lower
  than the one already stored is ignored, so the silence can only grow
  with the clock, never shrink below what was seen.
- A scheduled rotation of the open address starts, and its interval is
  drawn, only once every contact has been in contact since the previous
  rotation was promoted, so a contact the silence rule passed over is not
  moved a second time before it comes back (from 3.0.18). A removal still
  rotates at once. Once the drawn interval, or the shortest one while
  none is drawn, has passed, the network settings list the contacts such
  a rotation still waits for before it starts, with when each was last
  seen.
- Republishing the announced next address when the transport starts is
  best effort: a failure there no longer stops the start, so the
  transport goes on to dial with Tor connected, and the hourly check
  publishes the address again (from 3.0.18).
- There is no per-connection handshake after pairing. The connection handler
  resumes the persisted session for the contact, under the contact's current
  root key or, once the peer proved it holds it, its pending one (see
  ZWF-ROOT-EVOLUTION.md); the side that accepts a connection answers under
  the root the dialler's tag was made with.
- Nothing outside the connection changes before the peer's first frame has
  authenticated: the connection is registered, an arrival through the
  authorized service is recorded, the onion rotation learns of the session
  and of the address it arrived on, and the message layer is offered the
  send queue only then. For the onion rotation a dial counts as reaching
  the contact only when the first frame authenticated, however long the
  socket stayed open. Two outbound signals come earlier, from the
  completed Tor connection alone: the dial backoff treats a socket that
  stayed open for ten seconds as a connection, and a dial that reached the
  contact's authorized address is a client authorization probe and
  contact over the authorized path (ONION_CLIENT_AUTH.md sections 3 and
  5), since only the holder of that address can complete it.

Inbound connections are anonymous, since a Tor onion accept does not name the
peer. ZTP resolves the peer by peeking the first 16 bytes of the stream, which are
the ZWF stream tag, and recognising that tag as a known contact, stream and root
epoch. A tag outside every contact's current window triggers, at most once every
10 seconds, a wider search up to 16384 stream ids ahead for **one** contact,
taking the contacts in turn, so the work of a search, and its timing, does not
depend on the number of contacts; a tag still unrecognised is rejected. A
contact whose counter ran ahead is found once its turn comes round, or at once
when this side dials it. First-time pairing uses a separate rendezvous path, not
this one.

On the wire, an online connection is therefore:

```
[ZWF stream tag: 16 bytes]
[ZWF stream header: 50 bytes]
[ZWF frame: 4096 bytes]
[ZWF frame: 4096 bytes]
...
```

Everything below the connection handler is identical for Tor and I2P.

## ZPP: paced pull protocol with two constant rates

ZPP runs over a ZWF duplex connection and shapes its timing. The send side emits
exactly one fixed-size ZWF frame per interval. That frame carries the next queued
record if there is one, or a cover record if the queue is empty. Because a real
record and a cover record are both a 4096-byte ZWF frame, an observer cannot tell
whether an interval carried a message or was idle, or how large a message was.

Timing (`ZppPacingPolicy`, `ZppConnectionRunnerImpl`):

- Two constant base intervals. The active interval, 750 ms, applies while
  anything is queued, and for two minutes after the connection opens, after
  this side's own last message and after a received message that counts as
  activity. The idle interval, 4 s, applies otherwise, the same on every
  network type.
- Each interval adds uniform zero-mean jitter of up to one third of its length.
- Activity shortens a pending idle wait to the jittered active spacing, never
  below 500 ms after the previous frame as the application hands it to Tor, so
  the onset of activity cannot burst, and a stall lengthens the cadence rather
  than causing a catch-up burst. Tor and TCP buffering can still bring frames
  closer together on the wire.
- Two lanes share the slots: records that fit one frame (texts, offers,
  requests, acknowledgements, receipts) and the frames of records that need
  several (attachment chunks, large posts). A slot carries the next small
  record when one is waiting and a bulk frame otherwise, so a long attachment
  transfer never holds back the chat behind it. The cadence does not change;
  a fragmented record is reassembled by the peer however its frames are
  interleaved. A frame carries at most 1698 bytes of payload (a fragment
  1686 bytes of the record), so at one frame per active slot a connection
  carries a little over 2 KB per second and a 4 MB attachment takes about half
  an hour.
- A record waits in the bulk lane far longer than the retransmission timer
  the database starts when it hands the record over, so the sender keeps the
  set of messages whose frames have not all left: such a message is neither
  offered nor sent again until its last frame is out, and only then does the
  timer govern a resend. The batch pulled from the database is bounded by the
  room left in the bulk lane, always at least one frame, and a requested
  message that does not fit waits in the database while shorter ones still
  go, so a text written during a transfer leaves at the next slot.

What this hides and what it does not: within a rate, an observer cannot tell
which frames carried data. The switch from the idle to the active rate, and
back after two quiet minutes, reveals the coarse onset and end of activity, and
because the active rate lasts while records are queued, the length of an active
period gives a rough bound on how much was sent. Only messages
this side created start or extend the two-minute window, and only when they
leave for the first time; that includes automatic ones such as address
updates and typing notices. Acknowledgements, offers and requests the peer's
records provoke, and resends of messages the peer asks for again, do not, but
the active interval applies while anything is queued, so a backlog of them
drains at it. A received message counts as activity within ten minutes of
this side's own last message and otherwise at most once per ten minutes, and
both that ration and the two-minute window start afresh with every
connection, so a peer that keeps sending, or keeps reconnecting, can hold this
side at or near the active rate. A message composed offline counts when it
first leaves, up to a day after it was written. The side of each pair with
the lower author id is the dialer (`ZtpPoller`, over Tor and over I2P when it
is enabled): there a queued message, acknowledgement or new contact dials the
contact at once, so the opening of a connection can coincide with a send, and
a contact that is not connected is dialled every 5 s, backing off to about
72 s after failures, and, once thirty minutes have passed since its last
connection started or ended, since it was added, or since the app started,
whichever is latest, every five to six minutes; a queued message or
acknowledgement still dials it at once. A connection that ends within ten
seconds counts as a failure and does not reset the backoff. After a
connection that lasted has ended, the second failed dial that follows makes
Tor forget the onion descriptors it holds (`NEWNYM`), and a dial that fails
again two minutes or more after that refresh does so once more, since a
restarted peer uploads its new descriptor only once its introduction
circuits are built: at most twice per drop, at most once every two minutes
across all contacts, and the budget survives this side's own network
blips, so a contact that came back on a fresh descriptor is found again
without waiting for Tor's own fifteen minute requery. A contact not reached
since Tor came up gets one such refresh, no earlier than one to two minutes
after Tor came up, so that the first dials after a reconnect keep their
circuits. A `NEWNYM` rotates the circuits of everything on the phone, not
only this contact's, which is why it is rate limited and capped. Since
3.0.18 the other side dials
too, on a timer and never because of a send: 15 to 20 s after its Tor
connection comes up, or after a connection that lasted at least ten seconds
drops, it dials on the same backoff for ten minutes, and after that every
five to six minutes while the contact is not connected, so a pair also
connects when only one side's Tor path reaches the other. When both sides
connect at once, the connection dialled by the side with the lower author id
is kept and the other is closed, on both sides. A connection stays open for
as long as both devices are online, so
a contact sees when this device is online. Connection existence, lifetime and
reconnects are visible to an observer of the Tor link. Calls and channel
requests do not use this pacing. A subscribed channel is pulled every 5 s
while it has recent activity, backing off to 60 s when it is quiet; while
its owner cannot be reached, every failed pull doubles the wait, up to
fifteen minutes. When Tor comes back after an outage the waits of channels
that were failing are halved, not cleared, and their next pulls are spread
over the first one to two minutes, so a reconnect does not open every
channel's circuits at once.

The receive side decodes each frame and drops cover before delivering the record
to the sink. It also drops every record but cover that arrives in a frame
without a post-quantum secret (the opening sentinel of a stream), so the rule
that application data travels only in post-quantum frames holds on receipt, not
only at the sender.

## Application records: ZMM

The records carried inside ZWF frames are ZMM records with a small header:

```
[type: uint16 big-endian][payload]
```

Both the type and the payload sit inside the ZWF frame AEAD, so neither the record
type nor the record length is visible on the wire. In production four types are
used: cover, fragment, sync and transport control (`0xF4`, see
ZWF-ROOT-EVOLUTION.md: root evolution and the exchange of ML-DSA identity keys,
handled by the transport and never delivered to the sync layer; its greeting is
built on the send thread at the moment it leaves). Private messages, group records, call signalling, receipts and
acknowledgements all travel as sync records, fragmented as needed; the other
defined types are not sent. A receiver drops a record of a type it does not
know; Android 3.0.14 does the same, which is how it stays on the pairing root.

A record is at most `ZmmConstants.MAX_RECORD_BYTES` (1 MiB) before
fragmentation: the receiver reassembles nothing larger, and the sender never
queues a larger record, which the peer would drop and the sync layer would send
again forever. The bound applies to every implementation that sends to Android;
the iOS fragmenter accepts records up to 11 MiB and must apply the same bound. A cover record has the cover
type and an empty payload. The receive side identifies cover by comparing the type
word and does not deliver it.

## Component provenance

ZTP and ZPP are Zerion's own protocol work. The provenance of the Tor onion
wrapper and of the database and contact layer they build on is recorded in
[README.md](README.md) in this directory and in the repository's NOTICE.md.
