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
  local listener, and rebroadcasts the decremented frame on every link except the
  one it arrived on.
- A store holds recent frames so that a device joining a link is caught up.

Limits: the seen set holds 8192 message ids as a least-recently-used set, the
store holds up to 2 MiB, and the forwarder accepts at most 200 frames per second.
The maximum hop count is 7.

## Bluetooth Low Energy link

The link runs both a GATT server and a GATT client on each device. It uses a fixed
service and characteristic for frame transfer, and a rotating service identifier
for private discovery.

- The frame characteristic supports write, write without response, and notify.
- Discovery uses a rotating service identifier computed as
  `SHA-256(discovery secret, epoch)` where the epoch advances every 10 minutes. A
  scanner matches a small window of epochs to tolerate clock skew. This means two
  Zerion devices recognise each other without advertising a stable identifier that
  a third party could track.
- Each device advertises an 8-byte session nonce that rotates every epoch. When
  two devices meet, the one whose nonce sorts higher dials out, so exactly one
  side opens the connection.
- Frames are sent with a 4-byte big-endian length prefix and then split into
  Bluetooth writes or notifications sized to the negotiated transfer unit. The
  receiver reassembles by the length prefix and passes whole frames to the
  forwarder.

The device also masks its Bluetooth adapter name while the mesh is active and
restores it on stop, so the mesh does not expose a device name.

## Payload size padding

Before a payload is handed to the mesh, it is padded to a fixed size bucket. There
are two buckets, 4096 and 16384 bytes, with a 4-byte length prefix. Padding
quantises payload sizes to those two values, so the size of a mesh payload does
not reveal the size of the message inside it.

## Presence and cover

The mesh emits presence beacons so contacts can see each other as reachable, and
cover traffic so an observer cannot infer activity from timing. Presence beacons
are sealed the same way as messages, so they are indistinguishable from content on
the wire. The number of presence frames sent in a round is padded up to a fixed
step, so the count of frames does not reveal the number of contacts.

## Relationship to Briar

The mesh is Zerion's own work. Briar has no Bluetooth mesh of this kind. The mesh
reuses inherited storage for contact bundles and the seen-store, and it reuses the
identity keys held by the inherited identity manager.
