# Zerion Multi-Transport: Offline Mesh and I2P

This document explains the two transports Zerion adds on top of its mandatory
Tor transport: a Bluetooth-only offline mesh, and an opt-in embedded I2P router.
It describes how each works as built. The Tor transport, the wire format (ZWF),
the pull protocol (ZPP), and the Mode 3-Full ratchet are covered in
[ZERION_TECHNICAL_WHITEPAPER.md](ZERION_TECHNICAL_WHITEPAPER.md); this document
assumes that background.

Neither transport weakens the Tor-only guarantee of the shipped release. Tor is
always on and is the anonymity floor. The mesh is off by default; I2P is a
debug-build-only opt-in, also off by default. A fresh install runs Tor and
nothing else.

---

# Part I. The offline mesh (Bluetooth)

## 1. Purpose and scope

The offline mesh lets two Zerion users exchange messages with no internet at all:
no Tor, no I2P, no Wi-Fi, no cell data. It is built for disasters, blackouts,
protests, and remote areas, where nearby phones relay for each other over
short-range radio.

The mesh is Bluetooth Low Energy only. An earlier build also carried a Wi-Fi
Direct radio; it was removed because Wi-Fi Direct leaks the operating-system
device name, connects indiscriminately to nearby peers, and exposes a second MAC
address. The mesh is now pure BLE, which keeps the radio footprint and the
fingerprint small.

Tor remains the mandatory transport for online use. The mesh is additive:
turning it on never stops Tor, and turning Tor off (offline mode) leaves the
mesh as the only path. The mesh carries one-to-one messages and full group chat.
It deliberately does not carry public channels (Section 11).

## 2. Threat model, stated honestly

The mesh and the online transports protect different things, and the difference
matters.

- Tor and I2P hide *network location*: who you are talking to, and where you are
  on the internet.
- The mesh hides *content*: every payload a relay carries is opaque post-quantum
  ciphertext that only the intended recipient can open. Relays learn nothing
  about the message, the sender, or group membership.
- The mesh does not hide *physical proximity*. A co-located adversary with a
  radio can tell that a device is transmitting and roughly where it is. This is
  inherent to any local-radio system.

In one sentence: the mesh is for "communicate when there is no internet," not
"hide from someone standing next to you that you are communicating." The app
states this to the user before the mesh is enabled.

## 3. Layers

From the radio up:

1. **BLE transport** (`BleMeshTransport`). Carries opaque frames between devices
   in range.
2. **Managed flooding** (`MeshForwarder`). Store-carry-forward across multiple
   hops, with de-duplication and bounded storage.
3. **Async sealed-sender crypto** (`AsyncMeshDelivery`, `AsyncSealedSender`).
   Post-quantum encryption to a recipient's published prekey, with an inner
   sender signature.
4. **Message routing** (`MeshMessageRouter`). Turns an opened payload into a
   stored message, an acknowledgement, a presence update, or a group record.
5. **Application senders** (`MeshTextSender`, `MeshGroupSender`).

## 4. BLE transport

Every device acts at the same time as a GATT peripheral (advertising and
accepting connections) and a GATT central (scanning and connecting). This lets
any two devices in range link up without fixed roles. To avoid both sides
dialling each other at once (connection glare), the device advertising the
numerically higher session nonce is the one that connects as central; the other
stays peripheral.

Discovery does not use a static "this device runs Zerion" beacon. The advertised
service identifier is derived from a shared secret and the current time
(`discoveryUuid(epoch)`), rotating on a fixed epoch, and scanners check the
current and adjacent epochs to tolerate clock skew. The advertisement carries no
device name (`setIncludeDeviceName(false)`), and while the mesh is active the
classic BLE adapter name is masked to a random `BT-xxxxxx` string, restored when
the mesh stops. Frames are fragmented to fit the negotiated BLE MTU and
reassembled on the receiving side.

The transport tracks the Bluetooth adapter state: if the mesh is enabled while
Bluetooth is off, it registers for `ACTION_STATE_CHANGED` and brings the radio
up the moment Bluetooth is switched on, and tears it back down when Bluetooth is
switched off, without needing the mesh to be toggled again.

Residual: an adversary who extracts the app's discovery secret can still compute
the current identifier and detect Zerion presence. Fully hiding participation
would require contact-scoped discovery, which breaks open relaying. This is a
documented, deferred design choice.

## 5. Managed flooding

`MeshForwarder` implements store-carry-forward flooding:

- Each frame carries a message id, a payload, and a hop budget. The initial hop
  budget starts from a maximum (`MeshFrame.MAX_HOPS`, currently 7) minus a small
  random amount (0 to 2), so the starting value does not itself identify the
  origin.
- A relay that receives a frame it has not seen decrements the hop budget and
  re-broadcasts it while the budget remains, then stores it for a while so a peer
  that arrives later still receives it.
- De-duplication is by message id, so the same frame looping through the mesh is
  forwarded once, not repeatedly.
- The relay store is byte-bounded (`STORE_MAX_BYTES`, currently 2 MB) with
  oldest-first eviction, so a device cannot be filled up, and a per-node frame
  rate limit (currently 200 frames/second) bounds abuse.

Delivery is eventual and disruption-tolerant: a message can hop through several
phones and wait in their stores until the recipient comes into range.

## 6. Async sealed-sender crypto

A live handshake is impossible on the mesh: the recipient may be offline and the
relays are untrusted. The mesh therefore uses a second crypto mode, distinct from
the live online Mode 3-Full ratchet.

Each message is sealed to the recipient's published **prekey bundle** using a
hybrid key encapsulation: ML-KEM-768 (post-quantum) combined with X25519
(classical), mixed through a keyed KDF, feeding an XSalsa20-Poly1305
authenticated cipher. Inside the sealed envelope is a signature over the sender
identity, the recipient, and the payload, using the sender's hybrid Ed25519 +
ML-DSA-65 identity key; both halves must verify. This gives:

- **Confidentiality and integrity to relays.** Only the holder of the recipient
  prekey's private half can open the envelope. Every relay sees a fixed-size
  opaque blob.
- **Sender authentication.** The recipient knows for certain which contact sent
  the message, and a relay cannot forge or alter one.
- **Post-quantum protection.** Both the key agreement and the signature combine a
  classical and a post-quantum primitive, so an attacker must break both.

The envelope is padded to a fixed size (`MeshPadding`) so the plaintext length is
never observable, and it carries a send timestamp used for replay-window and
freshness checks. On receipt, the delivery layer marks an envelope as seen before
handing it to the router, so a replayed envelope is not delivered twice.

## 7. Prekey distribution

Sealing to a recipient requires their current prekey bundle. Bundles are
exchanged over the encrypted online channel (Tor or I2P) whenever a pair is
online together: on connection, a device sends its bundle to a contact, throttled
so it is not resent constantly. Received bundles are verified against the
contact's identity and stored (`MeshBundleStore`). When the pair later goes
offline, the mesh already holds the bundle it needs.

A record for a contact whose bundle is not yet held cannot be sealed. It is
queued and retried once the bundle arrives (Section 12), rather than dropped.

## 8. One-to-one messaging

`MeshTextSender` and `MeshMessageRouter` implement one-to-one text over the mesh.
The router dispatches by message type:

| Type | Value | Meaning |
|---|---|---|
| `MESH_TEXT` | 1 | A one-to-one text message. Inner payload is a message id, a compose time, and the text. |
| `MESH_ACK` | 2 | Acknowledges a received message id, so the sender can mark it delivered. |
| `MESH_PRESENCE` | 4 | A presence heartbeat (empty padded payload). |
| `MESH_GROUP_RECORD` | 5 | A group record (post, membership change, or invite); see Section 9. |

Flow:

- **Send.** The message is sealed to the contact's prekey and flooded. It is
  stored locally so the sender sees it immediately, and added to a retry outbox.
- **Receive.** The router resolves which contact sent it by matching the
  authenticated sender identity against the user's contacts. Messages from
  unknown senders are dropped. A received message is de-duplicated against a
  persistent seen-set (`MeshSeenStore`), stored, and then acknowledged with a
  `MESH_ACK`.
- **Delivery state.** A one-to-one message shows one tick when flooded and two
  ticks when the matching `MESH_ACK` returns. This reuses the existing
  sent/delivered event path, so the conversation reads the same as it does
  online, with a "via mesh" tag.
- **Reliability.** The outbox (`MeshOutbox`) retries on a fixed interval
  (currently 30 s) until the acknowledgement arrives, is bounded (currently 512
  entries, 24 attempts, 7-day time-to-live), and reloads undelivered messages
  from the database after a restart, so a kill or reboot still delivers.

## 9. Group chat over the mesh

Group chat ("GroupTr") is already pairwise underneath: a group post, a
membership change (add, remove, leave, dissolve, promote, demote), and an invite
are each a signed record delivered to every member through that member's
one-to-one channel. Every one of these funnels through a single method that sends
a record to a member.

The mesh reuses this. A small sink interface (`GroupTrMeshSink`) lets the group
manager hand a record to the mesh without depending on any transport code. When
the app is in offline mode, that single send point stores the record locally and
hands it to `MeshGroupSender`, which seals it to each member's prekey with type
`MESH_GROUP_RECORD` and floods it. Because every group operation already flows
through that one point, posts, membership changes, and invites all travel over
the mesh with one mechanism.

On receipt, a group record runs through the **same** validation and dispatch
pipeline as an online record: the message is placed in the sending contact's
group, validated (which performs full signature verification of the record),
stored, and dispatched to the normal typed handlers, which fire the same events
the app already reacts to. Group posts are re-verified against the sender's group
key and checked for membership; membership changes require the group creator's
signature and pass the epoch state machine. The security properties are identical
to the online path; there is no separate, weaker mesh path.

Consequences:

- A group can be created, invited to, joined, and used entirely offline over
  Bluetooth, including admin and member roles.
- The compose time travels in the envelope and is signature-bound, so a relay
  cannot backdate or reorder a record without breaking its signature.
- Records for a member whose prekey is not yet held are retried by a bounded
  outbox until that member exchanges keys.

## 10. Presence

`MeshPresenceTracker` shows contacts as online over the mesh. Presence is
inferred, not announced to strangers: any opened message from a contact,
including a heartbeat, marks that contact present. A contact is considered
present for a time-to-live (currently 150 s) and swept out afterward (every
30 s).

Beaconing is driven by the mesh lifecycle, not by opening a conversation. The
sender starts as soon as the mesh starts, emits a first heartbeat shortly after
(currently 4 s), and repeats on an interval (currently 60 s) so an idle but
reachable contact still shows online. In addition, when a new BLE peer connects,
a presence round is sent about 1.5 s later, so a contact that comes into range is
detected in seconds rather than at the next interval. Heartbeats are sealed
per-contact like any other message and carry a short time-to-live (currently
180 s). The conversation and contact list show "online via mesh" while Tor is
off, and normal online status when Tor is on.

## 11. What the mesh does not carry: channels

Public channels are deliberately not on the mesh. A channel is a one-to-many
publish-and-subscribe feed served from a host onion and pulled by subscribers
over Tor. It has no offline analogue: there is no host to reach, and sealing to
one recipient does not fit a public broadcast. Carrying channels offline would
require a separate unsealed broadcast primitive and mesh-level discovery, which
is a different and larger system. One-to-one and group messaging cover what a
crisis needs, which is reaching specific people and groups.

## 12. Reliability and limits

Built-in reliability:

- **Retry outboxes.** One-to-one sends retry until delivered or until a bounded
  time-to-live and attempt cap, and survive restart via a database reload.
- **De-duplication.** A persistent seen-set for one-to-one, and a record-hash
  plus a durable message-existence check for group records, so a re-flooded
  record is processed once.
- **Retry-when-a-peer-arrives.** A record that cannot be sealed yet, because the
  target's prekey is not held, is queued and delivered once that peer exchanges
  keys.
- **Cover traffic.** When peers are present, each device occasionally emits a
  throwaway sealed envelope addressed to itself (interval randomized, currently
  120 to 300 s). Nobody else can open it, so it is relayed and dropped
  everywhere, which blends real sends into background chatter.

Honest limits:

- Members must have exchanged prekey bundles at least once while online together
  (the retry outbox covers the waiting window).
- Large media does not fit the BLE path; text one-to-one and group messages do.
  Oversized payloads are dropped rather than sent.
- Group posts do not show a per-member delivery tick the way a one-to-one message
  shows two ticks.
- Group-record durability across a restart is weaker than one-to-one. A
  one-to-one message reloads its outbox from the database after a restart; the
  group send outbox is in memory only, so a group record composed fully offline
  that has not reached any peer before the app restarts may not be re-sent to a
  member who was never in range. Bringing group-record durability to parity with
  one-to-one is tracked work.
- Presence heartbeats are one padded frame per contact per interval, so a
  co-located BLE observer can count how many contacts a device is beaconing to,
  even though it cannot read who they are. Reducing this signal is tracked work.

## 13. Offline pairing

Two devices that have never met can still become contacts with no internet, using
an offline QR key-agreement. The same authenticated key-agreement that Zerion
runs for online pairing runs instead over a direct point-to-point BLE link, with
the QR code carrying the key-agreement commitment so a relayed man-in-the-middle
cannot match it. Roles are fixed (the device showing the QR is the server, the
device scanning is the client) so there is no connection glare. After pairing,
the two are contacts and can exchange prekeys and messages over the mesh.

## 14. Where the code lives

- Flooding and frames: `zerion-core` `org.zerionproject.transport.mesh`
  (`MeshForwarder`, `MeshFrame`).
- Async crypto: `zerion-core` `org.zerionproject.core.crypto.async`
  (`AsyncMeshDelivery`, `AsyncSealedSender`, `AsyncPrekeyBundle`,
  `MeshBundleStore`, `MeshSeenStore`).
- BLE radio, managers, routing, senders: `zerion-android`
  `com.professor.zerion.android.mesh` (`MeshManager`, `MeshController`,
  `BleMeshTransport`, `MeshMessageRouter`, `MeshTextSender`, `MeshGroupSender`,
  `MeshOutbox`, `MeshPresenceTracker`, `MeshPadding`).
- Group sink: `zerion-app-api` `GroupTrMeshSink`, wired through `GroupTrManagerImpl`.
- Receive into the app: `MessagingManagerImpl.receiveMeshMessage` and
  `receiveMeshGroupRecord`.
- Offline pairing: `zerion-android`
  `com.professor.zerion.android.contact.add.nearby.ble`.

---

# Part II. The I2P transport

## 15. Status and default

I2P is an **opt-in, debug-build-only** transport. It is off by default
(`I2pConstants.DEFAULT_PREF_PLUGIN_ENABLE = false`), and the plugin enforces this
at startup: without the explicit preference, `I2pDuplexPlugin.start()` sets the
plugin to `DISABLED` and returns. Release builds do not contain the I2P plugin at
all. Tor stays mandatory and always on when I2P is enabled; I2P is an additional
path, not a replacement.

I2P is present so that Zerion can be reached over a second anonymity network with
a different topology from Tor. It is gated to debug builds until a packet-capture
audit confirms the residual exposure below.

## 16. Embedded in-process router

Zerion does not shell out to an external I2P daemon. It runs a full I2P router
in-process, using the `net.i2p` Java router bundled into the app, and talks to it
over the I2CP streaming library (`I2PSocketManager`), the embedded-router
counterpart of a SAM-based integration.

The router is configured conservatively (`BundledI2pRouter`):

- Router assets are extracted from the app's `i2p` asset directory into app
  storage on first start.
- It carries no participating tunnels (`router.maxParticipatingTunnels = 0`) and
  is not a floodfill participant, so the device relays only its own traffic.
- NTCP2 and SSU (UDP) transports are enabled; UPnP is disabled in both the router
  and the Android layer.
- Bandwidth is capped (currently 128 KB/s inbound, 64 KB/s outbound).
- Router logging is set to the critical level only.

## 17. Reseed over Tor, fail closed

The one step where a fresh I2P router must reach the clearnet is the initial
reseed, where it downloads a starting set of router infos over HTTPS. Zerion
routes that step through Tor's SOCKS proxy so it does not reveal the device's IP
address, and requires it to be encrypted so it cannot silently fall back to a
direct connection:

```
router.reseedSSLProxyEnable = true
router.reseedSSLProxyType    = SOCKS5
router.reseedSSLProxyHost    = 127.0.0.1
router.reseedSSLProxyPort    = <Tor SOCKS port>
router.reseedSSLRequired     = true
```

Because Tor is mandatory and started before I2P, the SOCKS proxy is available
when the router reseeds. If it is not, the reseed fails rather than reaching out
directly, so joining I2P never leaks the device address during bootstrap.

## 18. Addressing and non-blocking boot

A device's I2P address is a `Destination` derived from a persisted keypair, so it
is known immediately and does not depend on the router having tunnels yet. On
start, `I2pStreamingTransport` derives the destination from the keypair, returns
it right away for publication, and then brings the I2CP session up on a background
executor, retrying every 15 s until the router has tunnels. A slow first-boot
reseed therefore never blocks the UI or kills the plugin; the address is
advertised and the session catches up.

The destination is published to contacts as a transport property
(`I2pConstants.PROP_I2P_DEST`), exchanged over the existing encrypted channel the
same way onion addresses are. Because an I2P destination is much longer than an
onion address, the transport-property length limit was raised to accommodate it.

## 19. Connections feed the same stack

I2P is a duplex transport under the same connection machinery as Tor. Inbound
streams are accepted from an `I2PServerSocket` (bounded to 64 concurrent inbound
connections) and outbound streams are dialled with `I2PSocketManager.connect`.
Both are handed to the shared `ZtpConnectionHandler`, so every connection, over
Tor or over I2P, runs the identical ZWF frame format, ZPP constant-rate pull
protocol, and Mode 3-Full ratchet described in the whitepaper. I2P changes only
how bytes are carried, not what is carried.

## 20. Threat model and residual

I2P provides end-to-end tunnel anonymity: a peer you talk to does not learn your
network address, and neither do the routers relaying your tunnels. The residual,
and the reason I2P is opt-in and off by default, is participation visibility: a
network observer positioned to watch your connection can tell that you
*participate* in I2P, the same class of exposure as using Tor without bridges.
The reseed step is routed through Tor and fails closed, so bootstrap does not
reveal the device address, but steady-state I2P participation is observable at
the "this device is an I2P node" level. This is why Tor stays mandatory and
always on, and I2P is an extra you turn on deliberately.

## 21. Where the code lives

- Router lifecycle and reseed config: `zerion-android` (debug source set)
  `com.professor.zerion.android.i2p.BundledI2pRouter`.
- Streaming transport and session: `com.professor.zerion.android.i2p`
  (`I2pStreamingTransport`, `BundledI2pStack`, `I2pStackModule`).
- Plugin, factory, and enable gate: `zerion-core`
  `org.zerionproject.transport.i2p` (`I2pDuplexPlugin`,
  `I2pDuplexPluginFactory`), with constants in `zerion-core-api`
  `org.zerionproject.core.api.plugin.I2pConstants`.
