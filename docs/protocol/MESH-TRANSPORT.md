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

Header size is 22 bytes. The maximum payload is 64 KiB. A relay never opens the
payload; it only decrements the hop count and rebroadcasts.

## Forwarding

The forwarder floods frames, deduplicates them, and limits the rate.

- Origination assigns a random 16-byte message id, sets the hop count to a value
  between 5 and 7 (the maximum of 7 minus a small random amount, so the initial
  hop count does not reveal the origin), marks the id seen, and broadcasts on all
  links.
- On receiving a frame, the forwarder applies a rate limit, decodes it,
  deduplicates on the message id against a seen set, delivers the payload to the
  local listener, and rebroadcasts the decremented frame on every link,
  excluding only the peer it arrived from on the arriving link.
- A store holds recent frames so that a device joining a link is caught up.

`MeshForwarder.onReceive` reports whether a frame was new to the node; the
Bluetooth link uses it to judge whether a neighbour is carrying anything.

Limits: the seen set holds 8192 message ids for 15 minutes each; entries do not
leave before they expire, except as described next. Each
neighbour may hold at most 1024 entries and send at most 50 frames per second;
the forwarder accepts at most 200 frames per second in total. When the set is
full of unexpired entries, the neighbour holding the most entries gives up its
oldest so a neighbour holding fewer is admitted; neighbour identities on a radio
link are cheap to invent, so this bounds what a flood spread over many
identities can shut out. The store holds up to 2 MiB. The maximum hop count is 7.

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
  Android gives every advertising set its own random address and changes it
  within a set no sooner than every 7 minutes, so the nonce and the address
  change together: a passive observer can follow one advertising identity for
  at most about 6 minutes, and the nonce links nothing the address does not.
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
  oldest link that has carried no frame new to this device in the 90 seconds
  since it came up, else the link whose last new frame is at least 6 minutes
  old. Every mesh device emits cover at least every 5 minutes, so a working
  neighbour keeps its slot, while advertisers that win the dial decision but
  carry nothing cannot hold the slots.
- Frames are sent with a 4-byte big-endian length prefix and then split into
  Bluetooth writes or notifications sized to the negotiated transfer unit. The
  receiver reassembles by the length prefix and passes whole frames to the
  forwarder.

While the mesh runs, the device's Bluetooth name is set to `Android`, the same
for every device, before the radio starts serving; a central that connects
can read the name, and it neither tells devices apart nor links one device's
advertising sets. The user's own name is kept in the encrypted settings and
put back when the mesh stops or is disabled, and at the next start of the app
if the app ended while the mesh ran. (Up to 3.0.14 the name was `BT-` and 6
random hex digits, fixed for a whole session, and was set only after the
radio had started.)

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

## Component provenance

The mesh is Zerion's own work. The mesh
reuses the core storage for contact bundles and the seen-store, and it reuses the
identity keys held by the inherited identity manager.
