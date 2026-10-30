# Mesh Transport: Flooding over Bluetooth Low Energy

The mesh carries messages between devices with no internet connection. It floods
sealed envelopes hop by hop over Bluetooth Low Energy until they reach the
recipient. The mesh moves opaque bytes; the sealed-sender envelope
(see ASYNC-SEALED-SENDER.md) protects them.

The mesh has two parts: a portable forwarding core that is independent of any
radio, and a Bluetooth Low Energy link that carries frames between two devices.

## Mesh frame

A mesh frame wraps one payload for flooding.

| Offset | Field | Size | Notes |
| --- | --- | --- | --- |
| 0 | version = 0x01 | 1 | |
| 1 | hopsLeft | 1 | remaining hops; 0 means do not relay |
| 2 | messageId | 16 | deduplication id, visible to relays |
| 18 | payloadLen (uint32) | 4 | |
| 22 | payload | variable | opaque, a sealed-sender envelope |

Header size is 22 bytes. The forwarder accepts payloads of up to 32 KiB. A relay
never opens the payload; it only decrements the hop count and rebroadcasts.

## Forwarding

The forwarder floods frames, deduplicates them, and limits the rate.

- Origination assigns a random 16-byte message id, sets the hop count to a value
  between 5 and 7 (the maximum of 7 minus a random 0 to 2), marks the id seen,
  and broadcasts on all links. A relay cannot tell from a hop count of 5 or 6
  whether the neighbour it heard the frame from originated it, but relays
  always decrement, so a frame that arrives with the full 7 comes straight from
  its origin; that is the case for about one frame in three a device
  originates.
- On receiving a frame, the forwarder applies a rate limit, decodes it,
  deduplicates on the message id against a seen set, rebroadcasts the
  decremented frame on every link, excluding only the peer it arrived from on
  the arriving link, and then delivers the payload to the local listener. From
  3.0.18 the rebroadcast comes first, so the extra work a recipient does to open
  an envelope addressed to it no longer delays its relay; up to 3.0.17 the
  payload was delivered first, and the later relay could show a neighbour
  which frames were meant for the device.
- Recent frames are kept in memory, but nothing hands them to a device that
  links later: a device that comes into range after a frame passed receives
  that message only when its sender sends it again (see Delivery below).

`MeshForwarder.onReceive` reports whether a frame was new to the node. From
3.0.19 the Bluetooth link no longer judges a neighbour by that: a link earns
its slot only when an envelope it delivered opens on this device, comes from a
contact and is not a replay, which `AsyncMeshDelivery` reports together with
the neighbour it came from (see the link slots below). Up to 3.0.18 any frame
new to the device counted, and a random frame costs nothing to make.

Limits: the seen set holds 8192 message ids for 15 minutes each; entries do not
leave before they expire, except as described next. Each
neighbour may hold at most 1024 entries and send at most 50 frames and two
maximum-size frames' worth of bytes (about 64 KiB) per second; the forwarder
accepts at most 200 frames and eight maximum-size frames' worth of bytes
(about 256 KiB) per second in total. Bytes are counted from 3.0.19; up to
3.0.18 only frames were, so one neighbour could push 50 full frames, about
1.6 MB, a second. When the set is
full of unexpired entries, the neighbour holding the most entries gives up its
oldest so a neighbour holding fewer is admitted; neighbour identities on a radio
link are cheap to invent, so this bounds what a flood spread over many
identities can shut out. The in-memory store of recent frames holds up to 2 MiB
and 1024 frames, the neighbour holding the most giving up its oldest first. The
maximum hop count is 7.

A neighbour is known here only by its Bluetooth address, which the platform
randomises and which a sender can change at will. Every per-neighbour limit
above therefore binds only a neighbour that keeps its address; against one
that invents addresses, only the device-wide limits bind: 200 frames and about
256 KiB a second, 8192 seen entries and the 2 MiB store. Counting bytes is
what makes the device-wide rate bound volume and not only frames: up to 3.0.18
invented addresses could together push 200 full frames, about 6.5 MB, a
second, far more than the radio can pass on, into a send queue that had no
bound (see the Bluetooth link below).

Half of the device-wide rate, 100 frames and about 128 KiB a second, is kept
for neighbours that delivered an envelope which opened on this device in the
last 6 minutes (`MeshForwarder.creditPeer`, called on the same report that
keeps a link's slot); every other neighbour shares the other half. A flood
over invented addresses therefore cannot take the whole rate from a working
contact nearby. The rate windows run on a monotonic clock, start afresh if
the clock ever reads earlier than a window's start, and a frame is charged to
its neighbour and to the device only when every limit admits it, so a frame
the device-wide limit refuses does not use up its neighbour's share (from
3.0.19).

## Bluetooth Low Energy link

The link runs both a GATT server and a GATT client on each device. It uses a fixed
service and characteristic for frame transfer, and a rotating service identifier
for private discovery.

- The frame characteristic supports write, write without response, and notify.
- Discovery uses a rotating service identifier computed as
  `SHA-256(discovery secret, epoch)` where the epoch advances every 10 minutes. A
  scanner matches a small window of epochs to tolerate clock skew. The discovery
  secret is a constant in the public source, so anyone can compute the current
  identifier and detect that a device runs the Zerion mesh; the identifier is the
  same for every device and names none of them.
- Each device advertises an 8-byte nonce in the scan response. When two
  devices meet, the one whose nonce sorts higher dials out, so exactly one
  side opens the connection. A fresh nonce is drawn for every advertising set,
  and the device replaces its advertising set after a random 4 to 6 minutes.
  Android is expected to give every advertising set its own random address
  and to change it within a set no sooner than every 7 minutes (not yet
  confirmed on a device), so the nonce and the address change together: an
  observer that sees only the advertisements can follow one advertising
  identity for at most about 6 minutes, and the nonce links nothing the
  address does not. That does not hold for a device linked to it, or for
  anyone who captures the link traffic, which Bluetooth does not encrypt here:
  the next nonce is announced over the link (next item). From 3.0.19 the new
  set starts after a random pause of 0.25 to 2.5 seconds instead of right after
  the old one stops, but timing and signal strength can still join consecutive
  sets.
  (Up to 3.0.14 the nonce lasted a fixed 10 minutes, longer than the address
  could, so an observer could chain address changes through it.) The first 2
  bytes of the nonce are 0xFF, so it sorts above the uniformly random nonce
  of a 3.0.14 device: such a device never dials a current one, which dials it
  instead. That keeps a 3.0.14 device, which cannot tell that a new
  advertising set belongs to a device it is already linked to, from opening a
  second link at every replacement.
- Before a device replaces its advertising set it sends the new nonce to the
  devices it is linked to, in a 10-byte link-local control frame (`0x7F`,
  `0x01`, then the nonce) that is never relayed, and it repeats the current
  nonce every 30 seconds. A linked neighbour therefore does not dial the new
  set as a stranger. Only the last two nonces each link announced count, and
  only while the link is up. A 3.0.14 or iOS device drops the frame, whose
  first byte is not a mesh frame version.
- Each device holds at most 6 outbound and 6 inbound links. When the slots
  are full and another device is to be linked, a link gives up its slot: the
  oldest link that has delivered no envelope this device opened in the 90
  seconds since it came up, else the link whose last such envelope is at
  least 6 minutes old, else, at most once a minute, a link chosen at random
  among those up for at least 10 minutes. An envelope counts only when it
  opens here, comes from a contact and is not a replay, so random frames,
  cover and replayed envelopes keep no slot, and a neighbour that only relays
  other devices' traffic keeps its slot only while it is not needed. From
  3.0.19; up to 3.0.18 any frame new to the device counted, so a neighbour
  holding every slot kept them all by sending one random frame every few
  minutes per link, and a slot held by a busy neighbour was never given up. A
  neighbour that keeps dropping and reopening links under fresh addresses can
  still take a slot the moment it frees, ahead of a device that is waiting.
  No busy link is rotated out while a link that has delivered no such
  envelope is still in its first 90 seconds, so a newcomer cannot push out
  busy links one after another. A device that keeps reappearing under fresh
  addresses can still cost one busy link its slot each time its own link has
  been dropped as silent, about once every 90 seconds per side, and it takes
  that slot itself until it is dropped again.
- Frames are sent with a 4-byte big-endian length prefix and then split into
  Bluetooth writes or notifications sized to the negotiated transfer unit. The
  receiver reassembles by the length prefix and passes whole frames to the
  forwarder.
- Frames wait for the radio in a send queue and for the forwarder in a receive
  queue. Each holds at most 64 frames or 1 MiB; when either bound is reached
  the oldest frame waiting is dropped. In the send queue, frames relayed for
  other devices are dropped first; frames this device originated (its
  envelopes and nonce announcements) are dropped only when no relayed frame
  is waiting, or when more than 16 of them, or more than 256 KiB, are
  waiting. The switch to a new advertising set is never dropped ahead of a
  frame. A direct message whose frame is dropped is sealed and sent again at
  its next outbox attempt (see Delivery below), and a flood that lasts can use
  up those attempts; a presence round or group record whose frame is dropped
  is not sent again until the next round or the next mesh start. From 3.0.19;
  up to 3.0.18 both queues were unbounded, so a neighbour sending faster than
  the radio drains could grow the app's memory until Android stopped it.
- From 3.0.19 the device advertises through an advertising set in legacy mode
  (connectable, scannable, no transmit power field, no device name), so the
  packets are the ones a 3.0.14 or later scanner expects; the discovery
  identifier and the nonce format are unchanged. Each set picks a random
  interval between 160 and 400 units of 0.625 ms (100 to 250 ms); up to 3.0.18
  the interval was a fixed 250 ms. During a set replacement the device holds a
  partial wake lock of at most 3 seconds, and a set that fails to start is
  tried again after 10 seconds. The advertising transmit power is medium, or
  low when the user picks the Short Bluetooth range; the power on an open
  connection is chosen by the Bluetooth controller, some controllers ignore
  the requested advertising level, signal strength stays measurable either
  way, and lowering the power only shrinks the range.
- The user can let the mesh pause while the screen is off (off by default).
  When that is on, the device stops advertising, scanning and its links about
  10 minutes after the screen turns off, counted in real time on the app's
  alarm-backed scheduler, so a sleeping device may pause up to about 15
  minutes later; it starts again when the screen turns on. A paused device
  relays nothing.

The fixed GATT service and characteristic, the rotating discovery identifier
and the 0xFFFF manufacturer marker are the same on every device: they identify
the app, not the person. The app never sets a Bluetooth address; whether each
advertising set gets its own random address, and how often it changes, is up
to the Android Bluetooth stack.

Neither the mesh nor Pair Nearby exposes the device's Bluetooth name: it is
never advertised, and while either runs the name, which a central that
connects can read, is set to a mask before the radio starts serving. The mask
is `Android` unless the user chose another name under "Bluetooth name while the
mesh runs" (at most 32 characters, control and invisible characters removed).
`Android` is the same for every device that keeps it; a chosen name is the
same in every session of one device and can link them. One owner holds the
name for the mesh and Pair Nearby, so neither puts the real name back while
the other runs. The user's own name is kept in the encrypted settings and put
back when the last of them stops, and at the next start of the app if the app
ended while the name was masked, or when Bluetooth is next switched on if
putting it back failed. The app waits up to one second for Android to apply the
mask and then starts the radio even if the change is still pending; it cannot
hide the name if Android does not apply it. Masking needs Android 12 or newer
and the BLUETOOTH_CONNECT permission. (Up to
3.0.14 the name was `BT-` and 6 random hex digits, fixed for a whole session,
and was set only after the radio had started; up to 3.0.18 Pair Nearby left the
real name in place.)

## Payload size padding

Before a payload is handed to the mesh, it is padded to a fixed size bucket. There
are two buckets, 4096 and 16384 bytes, with a 4-byte length prefix. Padding
quantises payload sizes to those two values, so the size of a mesh payload does
not reveal the size of the message inside it.

## Presence and cover

The mesh emits presence beacons so contacts can see each other as reachable, and
cover traffic that makes it harder to infer activity from timing. Presence
beacons are sealed the same way as messages, to the published signed-prekey id
that every account shares, so their selector is that of any other envelope.
Their time-to-live (180 seconds) differs from that of messages (7 days), so a
relay can tell presence frames from other frames.

Every presence round sends exactly 8 envelopes in random order, whatever the
number of contacts: a beacon for each contact whose turn it is, the contacts
beaconed longest ago first, and cover envelopes for the rest, also when the
device has no contact to beacon. A neighbour that collects rounds learns
neither the number of contacts nor which envelope of a round is real. A device
with more than 8 contacts beacons each of them every few rounds, so with more
than 16 a contact can drop out of "online via mesh" between beacons. Rounds run
every 60 seconds while offline mode is on and neighbours are linked; a new link
brings the next round forward after 1 to 4 seconds, but never sooner than 30
seconds after the previous round, so connecting over and over neither makes a
device send round after round nor times a round exactly.

A message is acknowledged only when the envelope that carried it was sealed at
most 10 minutes earlier (by the sender's signed timestamp), at most once every 5
minutes for the same message, and after a random delay of 3 to 30 seconds. A
captured envelope replayed next to a device therefore stops drawing a reply
once it is 10 minutes old, and draws at most one reply in 5 minutes before
that. The sender's retries are sealed afresh, so they still collect the
acknowledgement when the first one was lost. A message that arrives later than
that is still delivered; its sender sees it as sent, not delivered, unless a
retry is acknowledged.

## Delivery

Relays do not hold frames for devices that are not linked yet, so delivery to a
recipient who comes into range later rests on the sender. A direct message
stays in the sender's outbox and is sealed afresh and sent again every 30
seconds while a neighbour is linked, until it is acknowledged or its attempts
run out. A group record is sealed once; one that was never delivered is sealed
again when the sender's mesh next starts. Every repeat is a new envelope with a
new deduplication id, so relays cannot link the repeats of one message by
their ids, but the regular 30-second repeats of a device's outbox are visible
to its neighbours.

## Component provenance

The mesh is Zerion's own work. The mesh
reuses the core storage for contact bundles and the seen-store, and it reuses the
identity keys held by the inherited identity manager.
