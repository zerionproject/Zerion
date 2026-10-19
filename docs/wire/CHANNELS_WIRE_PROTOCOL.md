# Channels - wire protocol

Shipped on Android since v2.0.0.

iOS parity for Zerion broadcast channels. Android implementation lives in
`zerion-app/.../channel/` - the orchestrator is `ChannelManagerImpl.java`,
the wire codecs are `ChannelPullCodec.java` (request/response framing) and
`ChannelCodec.java` (signed-input byte layouts + invite links), and the
post-chain rules are in `ChannelPostValidator.java` /
`ChannelChainVerifier.java`. Constants are in
`zerion-app-api/.../channel/ChannelConstants.java`
(`CLIENT_ID = "org.zerionproject.channel"`, MAJOR 0 / MINOR 1).

## The one architectural fact

**Channels are a single-publisher STAR over Tor, not a mesh.** Each channel
has exactly one publisher (its creator). The publisher binds a dedicated
v3 onion service and answers request/response RPCs on it. Subscribers do
not gossip with each other - they **PULL** directly from the publisher's
onion (`ChannelTransport.requestFromOnion(onion, requestBytes)`). There is
no flooding, no store-and-forward between subscribers, no sync-client.

**Who answers a pull.** Only the owner's device binds a channel server
(`bindPublisherServer`, and `retireDueOnions` for onions still retiring); a
subscriber binds none, opting in to mirroring (`setMirrorOptedIn`) stores a
preference that nothing serves from, and the `neighbourHints` of a reply are
sent empty and never read. A subscriber dials the onion its own state names
(`onionToPull`): the one in the invite link until it has taken a manifest,
then the one named by the newest manifest it took, which the channel's key
signs, and as a fallback one that such a manifest named earlier. A
member that opens a link of a channel it already holds never makes the
link's address its own (section 6, Rejoining with a renewed link). The
invite link is not signed, so whoever hands it over picks the first address
dialled. From 3.0.18 a subscriber takes posts, reactions, comments and the
comment setting from a reply only when the onion it dialled is the one its
newest taken manifest names or the one named by the manifest in that same
reply, once its signature verified, whether or not that manifest is newer
than the one the device holds (`pullOnce`); from any other onion it takes
the signed manifest alone (and a content key only if it matches the hash
that manifest signs) and, on its first pull, pulls again at once from the
onion that manifest names. It keeps as a fallback only onions that a
manifest it took named. Only the holder of
an onion's key can answer at that onion, and only the owner's device holds
the keys of the onions its manifests name, so every post, reaction and
comment a subscriber takes in was served by the owner's device, and is still
checked under its signature. Up to 3.0.17 the first pull took posts from the
link's address and kept that address as a fallback, so whoever handed over
the link could answer those pulls with genuine signed posts of its choosing,
leaving gaps or, from the development builds of 3.0.18 that sent erased
posts, posts stripped of their bodies.

**Where a member's requests go.** Every request that carries something
about the member (an application to join, a check for approval, an
announced name, a comment, a reaction, an editor's post or change request,
an attachment fetch) goes from 3.0.18 only to an onion the owner's device
vouched for (`confirmedOnion`): the one the newest manifest the member took
names, or, while it has taken none, an onion the owner's device confirmed
just before with a signature under the channel's key. The one exception is
a member that applies again through a link because the onion it holds does
not answer: that application, and the checks for its approval that the
link's candidate makes, go where a new applicant's would, to the onion the
owner's device confirmed when asked at the link's address (section 6,
Rejoining with a renewed link). A private channel's
manifest cannot serve here, since its signature covers the join
capability, which an applicant does not hold. The member asks the onion it
knows to confirm it (`WIRE_TYPE_CONFIRM_ONION`, below); the owner's device
answers only for an onion it serves (its current onion or one still
retiring) and signs `channelId || int32 len || UTF-8 onion` (the onion
without `.onion`, lower case) under `SIGNING_LABEL_ONION_CONFIRMATION =
"org.zerionproject/CHANNEL_ONION_CONFIRMATION"`, sending the channel's salt
and ML-DSA-65 key with it. The member checks that the channel id is the
hash of the link's Ed25519 key with that ML-DSA-65 key and salt (and the
ML-DSA-65 key it already holds, if any), that the signature verifies and
that the onion is a v3 address, and then sends its request to the onion
the confirmation names, keeping that onion as its current one. A
confirmation names only an onion the requester already knew (a relay that
asks for another onion learns nothing it did not send), so it lets no
holder of an old link follow the channel to a new address. When no
confirmation can be had (an onion that is not the owner's, a reply that
does not verify, or an owner up to 3.0.17, which does not know the request
and answers with an empty reply), the request is not sent and fails as a
request to an unreachable channel does. Up to 3.0.17 these requests went to
the address in the state, which before the first manifest is the unsigned
one of the link, so whoever handed over the link received the application
(display name, member key, ephemeral key) and could answer a check for
approval.

Consequences:

- **If the publisher is offline, no new posts propagate.** A subscriber's
  refresh simply fails to connect; nothing else carries the posts.
- Each subscription is pulled on its own schedule. A channel just joined is
  pulled at once; subscriptions held when the app starts are first pulled at
  random times within 15 s. After each pull the subscription's interval is
  5 s if a pull of that channel in the last 120 s brought new posts or a
  changed reaction or comment set, otherwise it grows by 5 s up to 60 s, and
  the next pull falls at a uniformly random time between half and one and a
  half intervals later. From 3.0.18 a pull that fails (no answer, or a reply
  that does not verify) doubles the interval instead, up to 15 minutes, with
  the same random spread, and a failed pull never brings the next automatic
  pull earlier than it was due. A successful pull brings back the usual
  schedule. Opening the channel's feed pulls at once and starts the doubling
  again from the first step, without bringing the next automatic pull
  forward; the feed's later refreshes while it stays open are pulls like any
  other (a failed one doubles the interval) and reset nothing. Tor becoming
  active on the device also starts the doubling again from the first step,
  and each failing subscription is then pulled again within about a minute.
  Up to 3.0.17 failures did not lengthen the interval, so a channel
  whose owner never answered was pulled every 30 to 90 s for as long as the
  app ran. From 3.0.18 a subscription with no successful pull for 90 days
  (since the last successful pull the device recorded or, when it recorded
  none, since its first attempt from 3.0.18 on) is no longer pulled
  automatically: opening its feed pulls it once, a successful pull returns
  it to the usual schedule, and it can be left at any time. No two
  subscriptions share a schedule, so their pulls are not synchronised; but
  every subscription is first pulled within 15 s of sign-in, failing ones
  again within about a minute of Tor becoming active, and all of them stop
  together at sign-out, offline mode or loss of the network, so colluding
  publishers can match the start and end of a subscriber's sessions. An
  open feed also refreshes its own channel: at once when it opens, then
  after about 3.5 s for a few rounds and about 12 s after that,
  each wait drawn uniformly between half and one and a half of those values
  (from 3.0.18; fixed intervals before), so the refreshes follow no fixed
  period, but the publisher can still tell when and for how long the feed is
  open. A feed whose subscription has gone 90 days without a successful pull
  makes only the pull when it opens. The time of the last successful pull and
  whether the latest attempt failed are kept on the device
  (`zerion-channels-sync-status`, at most one write a minute while pulls
  succeed) so the feed can say when it was last updated; they are never
  sent.
- The publisher persists each channel's onion key and republishes the same
  onion at startup, on Tor `TransportActiveEvent` and
  `B4OwnRotationCompletedEvent` (for channels not yet bound) and on periodic
  retries. Every 28 to 36 days it moves the channel to a fresh onion, signs a
  manifest naming it (`manifestSeq` + 1) and keeps the old onion published
  for 30 days, serving that manifest so subscribers follow; then the old
  onion and its key are removed (section 9). The old key moves from the
  channel state to the onion record in the same transaction, whether or not
  a server was bound for it at that moment, so no rotation, deletion or
  crash can lose the only address subscribers know. Rotating a private
  channel's invite link moves the channel to a fresh onion and removes the
  old one, and every onion still retiring, at once. A subscriber remembers
  the last 12 onions a channel used and, once the current one has not
  answered for 15 minutes, tries them in turn on every other pull, so a
  publisher restored from an older backup is found again (section 9). Only
  onions that a manifest the subscriber took named are remembered.
- Pulls are incremental for posts, reactions and comments. A subscriber asks
  for the posts after its chain tip (`sinceSeqNum = -1` when it holds none)
  and names the reaction and comment revisions it holds; a publisher of
  protocol version 2 answers with what changed since (section 2). The
  publisher returns at most 100 posts after `sinceSeqNum`; `pullAndApply`
  repeats up to 64 rounds.

```
            Publisher (onion service, ChannelServer)
                          ^
        pull request      |      pull response (manifest + posts + ...)
      +-------------------+-------------------+
      |                   |                   |
  Subscriber A       Subscriber B        Subscriber C
  (requestFromOnion) (requestFromOnion)  (requestFromOnion)
```

## Transport framing

Channel RPCs do not use ZWF, ZPP, the ZMM registry or the Mode 3-Full ratchet. Each RPC is one TCP stream through the local Tor SOCKS proxy to port 80 of the publisher's per-channel v3 onion service (no client authorization). The subscriber writes a 4-byte big-endian length and one canonical BDF dictionary (at most 256 KiB); the publisher replies with a 4-byte big-endian length and one BDF dictionary (the subscriber accepts at most 16 MiB); the stream is then closed. A zero-length reply means the request was refused. There is no padding, cover traffic or constant-rate scheduling. The Tor onion-service circuit is the only encryption of the whole exchange, and it is classical. At the application layer only these fields are encrypted: in private channels the post `body`, the per-attachment `key` and the `contentKeyEnvelope`; in all channels the attachment blobs and `thumb` (public channels send the per-attachment key unwrapped). Manifests, post metadata, comments, reactions, announces, applications and approval responses are plaintext BDF inside the Tor circuit.

Publisher limits (`TorChannelTransport`), all kept **per hosted channel** and shared by every onion that serves it (its current onion and the ones still retiring): at most 6 request handlers at once (the thread pool has room for 32 channels' full share), so a flood of connections to one channel, through however many of its onions, leaves handlers for the others (all connections arrive from the local Tor process, so peers themselves cannot be told apart). The length prefix must arrive within 15 s; the request must then arrive at 8 KiB/s on average after a 10 s grace (at most 120 s in all); the reply must keep moving (20 s at most per 64 KiB chunk) and be taken at 32 KiB/s on average after a 20 s grace (at most 20 minutes in all). Replies above 1 MiB held at once are bounded to 16 MiB per channel. Each channel may send at most 256 MiB of replies per 60 s, charged per 64 KiB chunk as it is written, so a reader that asks for a large reply and hangs up spends only what was sent to it; a reply that would start past the budget is sent as zero length, and one that reaches the budget while being sent is cut. A reply refused by any of these limits is sent as zero length. A listener whose onion could not be published is closed again.

Every RPC is a single **BdfDictionary** (not a BdfList), written with the
`BdfWriter` and read with `BdfReader`. Each dictionary carries a
`"type"` string key whose value is one of the `WIRE_TYPE_*` constants below.
The publisher dispatches inbound requests on that type
(`ChannelManagerImpl.handlePublisherRequest` via
`ChannelPullCodec.peekType`). All request/response pairs are synchronous:
the subscriber calls `requestFromOnion` and blocks on the single response
dictionary.

Unlike GroupTr (which rides the pairwise messaging channel as BdfLists keyed
by an integer msgType), Channels run their **own** onion RPC and key every
frame by a **string** `WIRE_TYPE_*` discriminator. There are no integer
msgType numbers in this protocol.

### Wire types (`ChannelConstants`)

| `type` value | Constant | Direction | Purpose |
|---|---|---|---|
| `ZERION_CHANNEL_PULL_REQUEST_V1` | `WIRE_TYPE_PULL_REQUEST` | sub → pub | Bootstrap or incremental pull |
| `ZERION_CHANNEL_PULL_RESPONSE_V1` | `WIRE_TYPE_PULL_RESPONSE` | pub → sub | Manifest + posts + reactions + comments |
| `ZERION_CHANNEL_MANIFEST_V1` | `WIRE_TYPE_MANIFEST` | (nested) | Signed channel manifest, embedded in pull response |
| `ZERION_CHANNEL_POST_V1` | `WIRE_TYPE_POST` | (nested) | Reserved type tag for a post (posts ride inside the pull response `posts` list) |
| `ZERION_CHANNEL_GET_ATTACHMENT_V1` | `WIRE_TYPE_GET_ATTACHMENT` | sub → pub | Fetch one attachment blob by hash |
| `ZERION_CHANNEL_ATTACHMENT_BLOB_V1` | `WIRE_TYPE_ATTACHMENT_BLOB` | pub → sub | Attachment blob bytes |
| `ZERION_CHANNEL_POST_REACTION_V1` | `WIRE_TYPE_POST_REACTION` | sub → pub | Submit a reaction to a post |
| `ZERION_CHANNEL_REACTION_ACK_V1` | `WIRE_TYPE_REACTION_ACK` | pub → sub | Boolean ack of a reaction |
| `ZERION_CHANNEL_POST_COMMENT_V1` | `WIRE_TYPE_POST_COMMENT` | sub → pub | Submit a comment on a post |
| `ZERION_CHANNEL_COMMENT_ACK_V1` | `WIRE_TYPE_COMMENT_ACK` | pub → sub | Boolean ack of a comment |
| `ZERION_CHANNEL_ANNOUNCE_V1` | `WIRE_TYPE_ANNOUNCE` | sub → pub | Subscriber announces display name |
| `ZERION_CHANNEL_ANNOUNCE_ACK_V1` | `WIRE_TYPE_ANNOUNCE_ACK` | pub → sub | Boolean ack of an announce |
| `ZERION_CHANNEL_APPLY_TO_JOIN_V1` | `WIRE_TYPE_APPLY_TO_JOIN` | sub → pub | Apply to a private approval-gated channel |
| `ZERION_CHANNEL_APPLY_ACK_V1` | `WIRE_TYPE_APPLY_ACK` | pub → sub | Boolean ack of an application |
| `ZERION_CHANNEL_CHECK_APPROVAL_V1` | `WIRE_TYPE_CHECK_APPROVAL` | sub → pub | Poll whether an application was approved |
| `ZERION_CHANNEL_APPROVAL_RESPONSE_V1` | `WIRE_TYPE_APPROVAL_RESPONSE` | pub → sub | Approval status + wrapped capability |
| `ZERION_CHANNEL_CONFIRM_ONION_V1` | `WIRE_TYPE_CONFIRM_ONION` | sub → pub | Ask the owner's device to confirm an onion before a member's first request (from 3.0.18): keys `type`, `v`, `channelId`, `onion` (at most 80 characters) |
| `ZERION_CHANNEL_ONION_CONFIRMED_V1` | `WIRE_TYPE_ONION_CONFIRMED` | pub → sub | Keys `type`, `channelId`, `onion`, `salt`, `ml` (the channel's ML-DSA-65 key), `sig`; an empty reply when the onion is not the owner's (The one architectural fact, Where a member's requests go) |
| `ZERION_CHANNEL_DELEGATION_V1` | `WIRE_TYPE_DELEGATION` | (nested) | Editor delegation cert (carried inside manifest) |
| `ZERION_CHANNEL_SUBMIT_POST_V1` | `WIRE_TYPE_SUBMIT_POST` | editor → pub | An editor's post for the publisher to add to the chain (protocol version 2) |
| `ZERION_CHANNEL_SUBMIT_POST_ACK_V1` | `WIRE_TYPE_SUBMIT_POST_ACK` | pub → editor | `status` (`OK`, `STALE`, `REFUSED`) and the publisher's chain tip `tip` |
| `ZERION_CHANNEL_CHANGE_POST_V1` | `WIRE_TYPE_CHANGE_POST` | editor → pub | An editor asks the owner's device to delete, edit or set comments on one of the editor's own posts (protocol version 4, section 6) |
| `ZERION_CHANNEL_CHANGE_POST_ACK_V1` | `WIRE_TYPE_CHANGE_POST_ACK` | pub → editor | `status` (`OK`, `STALE`, `REFUSED`) |
| `ZERION_CHANNEL_TOMBSTONE_V1` | `WIRE_TYPE_CHANNEL_TOMBSTONE` | pub → sub | Signed channel-deleted tombstone (returned in place of any response) |

### Protocol version

Every request a client from 3.0.18 on sends carries `v` (long) = 5
(`ChannelConstants.PROTOCOL_VERSION`); clients from 3.0.15 to 3.0.17 send 2,
and development builds of 3.0.18 made before versions 4 and 5 sent 3 and 4.
A request without `v` comes from a client up to 3.0.14. Version 2
(`PROTOCOL_VERSION_DELTAS`) brought item deltas, the covering proof and
format 2 posts; version 3 (`PROTOCOL_VERSION_AUTHOR_BINDING`) brings the
owner's countersignature of author bindings: a reply to a request with
`v >= 3` carries a manifest signature that also covers every
author binding the reply carries (section 5, Author binding). Version 4
(`PROTOCOL_VERSION_POST_CHANGES`) brings post edits, comment settings that
subscribers learn, and editors' change requests: a reply to a request with
`v >= 4` carries the comment setting `ds` and a manifest signature that also
covers that setting (sections 3 and 5), and only such a reply carries edit
marks (section 4, Edits). Version 5 (`PROTOCOL_VERSION_ERASED_EDITS`) brings
erased posts: only a reply to a request with `v >= 5` carries a post whose
body an edit erased (section 4, Edits). A reply carries `v` = 5 to a request
with `v >= 5`, 4 to `v` = 4 and 3 to `v` = 3. A publisher from 3.0.15 to
3.0.17 treats `v` from 3 on as 2, and a development build of 3.0.18 that
sent 4 treats 5 as 4. A publisher up
to 3.0.14 ignores
`v` and the other new fields, so a newer subscriber is served by it as
before. A publisher from 3.0.15 on answers a request without `v` in the
format such a client understands
(whole reaction and comment sets, and only the leading posts in the legacy
post format, section 4), and shows its owner that subscribers on an older
release exist: they cannot read posts made from 3.0.15 on, refuse them
without storing anything, and receive nothing past the first such post until
they update. iOS has a channel implementation of its own, with a different,
list-based wire format that an Android publisher does not parse; Android
and iOS channels have never interoperated, before or after version 2.

A `WIRE_TYPE_SUBSCRIPTION_HINT` constant
(`ZERION_CHANNEL_SUBSCRIPTION_HINT_V1`) is also defined; the pull response
carries a `neighbourHints` list of strings (Android currently always sends
an empty list - see `handlePublisherRequest`). TODO: the hint list is
plumbed end-to-end but unused; iOS may ignore it for now.

## 1. Pull request - `WIRE_TYPE_PULL_REQUEST`

`ChannelPullCodec.encodePullRequest` / `decodePullRequest`; built by
`ChannelPullProtocol.buildRequest`.

| key | BDF type | notes |
|---|---|---|
| `type` | string | `ZERION_CHANNEL_PULL_REQUEST_V1` |
| `v` | long | protocol version: 5 from 3.0.18, 2 from 3.0.15 to 3.0.17; absent from clients up to 3.0.14 |
| `channelId` | raw | 32 bytes (`CHANNEL_ID_BYTES`) |
| `sinceSeqNum` | long | `-1` when the subscriber holds no chain tip; otherwise its tip, and the publisher sends posts with `seqNum > sinceSeqNum` |
| `rc` | raw, optional | 16-byte reactions cursor (8-byte epoch, 8-byte revision) the subscriber holds |
| `cc` | raw, optional | 16-byte comments cursor |
| `nonce` | raw, optional | 16-byte client nonce (`BOOTSTRAP_HMAC_NONCE_BYTES`), private channels |
| `hmacResponse` | raw, optional | legacy proof: keyed BLAKE2b-256 MAC (key = capability) over channelId and the nonce |
| `hmac2` | raw, optional | covering proof (protocol version 2), see below |

A subscriber holding a capability sends the nonce with both proofs, so a
publisher up to 3.0.14 checks the legacy proof and a 3.0.15 publisher the
covering one. A public channel's request carries no proof.

### Capability proofs on the publisher

Pull, comment, reaction, announce, attachment and submit-post requests to a
private channel carry a proof that the sender holds the current join
capability; without a valid one the publisher returns an empty reply. Apply
and check-approval requests carry no proof, nor does an onion
confirmation request.

- **Covering proof (`hmac2`, protocol version 2).**
  `crypto.mac("org.zerionproject/CHANNEL_REQUEST_PROOF_V2",
  SecretKey(capability), channelId, nonce, request)` where `request` is the
  canonical BDF encoding of the request dictionary without the fields
  `nonce`, `hmac`, `hmac2` and `hmacResponse` (BDF writes dictionary keys in
  order, so both sides compute the same bytes). It binds the request type
  and every field, so a proof taken from one request does not make another
  acceptable. A request with `v >= 2` must carry it.
- **Legacy proof (`hmacResponse` on pulls, `hmac` on the other types).**
  `crypto.mac("org.zerionproject/CHANNEL_HMAC_CHALLENGE",
  SecretKey(capability), channelId, nonce)`. It binds only the channel and
  the nonce. It is accepted only from a request without `v`, for clients up
  to 3.0.14; a request stripped of `v` and `hmac2` is judged under this
  older rule, so the covering proof protects requests between version 2
  peers but cannot stop such a downgrade while older clients are served.
- **Nonces.** The publisher keeps a per-channel, in-memory, insertion-ordered
  set of recent nonces (not persisted; cleared on restart and on channel
  removal; entries older than 5 min or beyond 4096 are dropped). A nonce
  already in the set is refused. A nonce is added only after its proof has
  verified, so a request with a bad proof cannot use up the nonce a genuine
  request is about to send. Replay protection is limited to 5 minutes, the
  last 4096 nonces and the current process; requests carry no timestamp.

## 2. Pull response - `WIRE_TYPE_PULL_RESPONSE`

`ChannelPullCodec.encodePullResponse` / `decodePullResponse`.

| key | BDF type | notes |
|---|---|---|
| `type` | string | `ZERION_CHANNEL_PULL_RESPONSE_V1` |
| `v` | long, optional | present when the reply carries `rs` and `cs`: 5 for a request with `v >= 5` (as 4, and the posts may include erased posts, section 4, Edits), 4 for a request with `v` = 4 (the manifest signature then covers the author bindings and `ds`, section 3), 3 for a request with `v` = 3 (it covers the author bindings, section 5), otherwise 2 |
| `ds` | dictionary, versions 4 and 5 | comment setting: `on` (boolean, the channel's setting) and `x` (list of long, the posts whose setting differs from it, ascending, distinct, at most 4096, `MAX_COMMENT_EXCEPTIONS_PER_CHANNEL`); section 5, Comment settings |
| `manifest` | dictionary | the signed manifest (section 3) |
| `posts` | list of dict | each entry is a wire post (section 4) |
| `contentKeyEnvelope` | raw, optional | AES-GCM-wrapped channel content key (private channels, only when the proof passed) |
| `neighbourHints` | list of string | currently empty |
| `reactions` | list of dict | section 5: changed items, or the whole set |
| `comments` | list of dict | section 5: changed items, or the whole set |
| `rs` | dictionary, optional | reactions sync: `cursor` (raw 16), `full` (boolean), `removed` (list of item keys, at most 512) |
| `cs` | dictionary, optional | comments sync, same keys |

The publisher builds this in `handlePullRequest`. It always re-signs the
manifest fresh (`signLatestManifest`) so the embedded `currentOnion` and
`manifestSeq` are current. `contentKeyEnvelope` is only attached when the
proof passed **and** the channel has a content key
(`wrapContentKey(capability, channelId, contentKey)`).

### Reaction and comment deltas

`ChannelItemSync` keeps, per channel and kind, a random 8-byte epoch, a
revision counter, the revision and a digest of every held item, and a log of
the last 512 removals, each channel in a settings namespace of its own
(`zerion-channels-item-sync:<channelId hex>`), so serving one channel reads
that channel's state alone. A subscriber keeps its cursors in memory only,
so its first pull of a channel after each start takes the whole sets (from
3.0.18; earlier versions stored them in
`zerion-channels-sync-cursor:<channelId hex>`, which is deleted the first
time the channel is pulled). A publisher that finds its database was restored
from a backup or copied starts a fresh epoch for every hosted channel, so a
subscriber whose cursor named revisions the copy reissues with other content
takes the whole sets again instead of silently drifting apart. Every write of the held set is compared with the
previous one: a new or changed item takes the next revision, and an item
that left takes the next revision in the removal log. An item is keyed by
`seq:hex(signer Ed25519)` for reactions and by its comment id for comments.

To a request of version 2 the publisher answers, per kind:

- cursor current (same epoch, same revision): `full = false`, no items, no
  removals; an unchanged channel costs one manifest;
- cursor older but within the removal log: `full = false`, the items whose
  revision is newer than the cursor, and the keys removed since;
- any other cursor, or none: `full = true` and the whole set.

The `cursor` in the reply is the publisher's position after it. The
subscriber applies a delta by removing the listed keys from its held set and
taking the changed items in last, verified, within the ceilings and on posts
it shows; a whole set replaces the held set. It keeps the new cursor only
when the reply carried fewer than 100 posts, so items of posts it has not
fetched yet are sent again on the next round. A request without `v` gets the
whole sets, without `rs` and `cs`; a reply without them is taken as whole
sets, and the subscriber forgets its cursor.

## 3. Manifest - `WIRE_TYPE_MANIFEST`

`ChannelPullCodec.encodeManifest`. This is the channel's signed metadata
record. Subscribers verify and merge it in
`ChannelPullProtocol.mergeManifestIntoLocal`.

| key | BDF type | notes |
|---|---|---|
| `type` | string | `ZERION_CHANNEL_MANIFEST_V1` |
| `channelId` | raw | 32 bytes |
| `salt` | raw | 16 bytes (`CHANNEL_SALT_BYTES`) |
| `publisherEd25519` | raw | publisher Ed25519 public key (32 bytes) |
| `publisherMlDsa` | raw | publisher ML-DSA-65 public key |
| `name` | string | ≤ 64 chars (`MAX_CHANNEL_NAME_CHARS`) |
| `description` | string | ≤ 1024 chars (`MAX_CHANNEL_DESCRIPTION_CHARS`) |
| `avatarHash` | raw, optional | 32-byte blob hash |
| `createdAtHourMs` | long | creation time, floored to the hour |
| `publicChannel` | boolean | |
| `joinCapability` | raw, optional | emitted only for **public** channels (`buildResponseAsPublisher` sets `wireJoinCapability = isPublic ? joinCapability : null`), which have none, so Android never emits it. For private channels the capability is delivered out-of-band (invite link / approval envelope), never in the manifest; private subscribers verify the manifest signature with their own capability. |
| `currentOnion` | string | publisher onion address (may be empty before first bind) |
| `manifestSeq` | long | monotonic; an older or equal `manifestSeq` is verified but not merged, and the pull continues |
| `contentKeyHash` | raw, optional | 32-byte hash of the content key, used to validate an unwrapped envelope |
| `activeDelegations` | list of dict | editor delegation certs (section 6) |
| `revokedDelegationSeqs` | list of long | revoked delegation sequence numbers |
| `pinnedPostSeq` | long | `-1` = none (`ChannelState.NO_PINNED_POST`) |
| `requiresApproval` | boolean | private approval-gated channel |
| `discussionsEnabled` | boolean | **only written when `ChannelConstants.DISCUSSIONS_IN_MANIFEST` is true** |
| `signature` | raw | hybrid signature over the manifest signed-input; in a version 3 reply over the signed-input followed by the reply's author bindings digest (section 5, Author binding); in a reply of version 4 or 5 over both digests and the comment setting digest (below) |

### `DISCUSSIONS_IN_MANIFEST` gating

`ChannelConstants.DISCUSSIONS_IN_MANIFEST` is currently **`false`**. While it
is false:

- `encodeManifest` does **not** emit the `discussionsEnabled` key, and
  `manifestSignedInput` does **not** include the discussions byte in the
  signed bytes (see the trailing `if (DISCUSSIONS_IN_MANIFEST)` in both).
- The decoder reads `manifest.getBoolean("discussionsEnabled", true)` - 
  absent ⇒ defaults to `true`.
- The subscriber does **not** persist the manifest value into its local
  `ChannelDiscussionStore`; it stores the signed `ds` of a version 4 or 5
  reply instead (section 5, Comment settings).

The manifest itself therefore never carries the setting, and its signed
bytes are the same for every client, so a subscriber of any release verifies
it as before. From protocol version 4 the setting reaches subscribers
outside the manifest dictionary instead: a reply of version 4 or 5 carries
it in `ds` (section 2) and the owner signs it together with the manifest
(below).
The publisher enforces it whatever the subscriber's version: it rejects a
comment RPC on a post whose comments are off (`handleCommentRequest` →
`discussionStore.isEnabledFor`). Flipping `DISCUSSIONS_IN_MANIFEST` to true
would break manifest verification for every subscriber up to 3.0.17 and is
not planned. **iOS should treat the manifest `discussionsEnabled` field as
optional/defaulting-true, take the setting from `ds` when it speaks
version 4 or later, and rely on the publisher's comment-ack for the
authoritative answer.**

### Manifest signed-input (byte-exact)

`ChannelCodec.manifestSignedInput` builds a flat `ByteBuffer` in this order
(big-endian, all integers; `crypto.hash` = BLAKE2b with a domain label):

```
channelId (32)
salt (16)
publisherEd25519Pub (32)
publisherMlDsaPub (var)
nameHash (32)            = hash("…/CHANNEL_MANIFEST_NAME",  UTF-8 name)
descHash (32)            = hash("…/CHANNEL_MANIFEST_DESC",  UTF-8 description)
avatarPresent (1)        0|1
avatar (32)              avatarHash, or 32 zero bytes when absent
createdAtHourMs (8)
publicChannel (1)        0|1
capabilityPresent (1)    0|1
capability (32)          joinCapability, or 32 zero bytes when absent
onionLen (4)             length of lowercase ASCII onion
onionBytes (onionLen)
manifestSeq (8)
contentKeyHashPresent (1)
contentKeyHashBytes (32) or 32 zero bytes
delegationsHash (32)     = hash("…/CHANNEL_MANIFEST_DELEGATIONS", canonical)
revokedHash (32)         = hash("…/CHANNEL_MANIFEST_REVOKED", canonical)
pinnedPostSeq (8)
requiresApproval (1)     0|1
[discussionsEnabled (1)] only if DISCUSSIONS_IN_MANIFEST (currently omitted)
```

`delegationsCanonicalHash`: `int32 count` then per cert
`delegateeEd25519 || delegateeMlDsa || int64 validFrom || int64 validUntil ||
int64 delegationSeq || signature`, hashed under
`…/CHANNEL_MANIFEST_DELEGATIONS`. `revokedCanonicalHash`: `int32 count` then
each `int64 seq`, hashed under `…/CHANNEL_MANIFEST_REVOKED`.

In a reply of version 3 the publisher signs `signed-input || D` instead,
under label `SIGNING_LABEL_MANIFEST_V3 =
"org.zerionproject/CHANNEL_MANIFEST_V3"`, where `D` is the author bindings
digest of the reply (section 5, Author binding), and the subscriber verifies
it with `verifyBoundManifest`; a version 3 signature does not verify under
the label below, nor the reverse. In a reply of version 4 or 5 the
publisher signs `signed-input || D || T` under `SIGNING_LABEL_MANIFEST_V4 =
"org.zerionproject/CHANNEL_MANIFEST_V4"` (`signPostChangesManifest`,
`verifyPostChangesManifest`), where `T` is the digest of the reply's comment
setting `ds`:

```
T = hash("org.zerionproject/CHANNEL_DISCUSSIONS",
         on (1) || int32 count || int64 seq ...)
```

with the post numbers of `x` ascending. A reply of version 4 or 5 without
`ds`, or
whose `ds` does not match the signature, fails as a whole, like a reply with
an altered author binding. Otherwise the manifest is signed with the
publisher's hybrid key under label
`SIGNING_LABEL_MANIFEST = "org.zerionproject/CHANNEL_MANIFEST"`
(`ChannelSignatures.signManifest` → `crypto.hybridSign`). Verified with
`verifyManifest` → `crypto.verifyHybridSignature`.

### Subscriber merge / acceptance checks (`mergeManifestIntoLocal`)

A manifest whose `manifestSeq` is negative or above 2^62
(`MAX_SEQUENCE_NUMBER`) is refused before anything else is read.

In order, a manifest is rejected (merge returns null ⇒ pull fails) if any of:

1. `publisherEd25519` differs from the locally pinned publisher key.
2. local ML-DSA key is known and `publisherMlDsa` differs.
3. `channelId` differs from local.
4. the channelId is not reproducible:
   `hash("org.zerionproject/CHANNEL_ID",
   HybridSignaturePublicKey(ed, mlDsa).getEncoded(), salt)` must equal the
   channelId. This binds the channel id to the publisher key + salt.
5. the hybrid manifest signature fails to verify.
6. `currentOnion` is neither empty nor a v3 address (`^[a-z2-7]{56}(\.onion)?$`),
   so a subscriber never stores an address the transport would refuse.

If all pass but `incomingSeq <= local.getManifestSeq()`, the local state is
returned unchanged (stale manifest, ignored but not an error).

A merged manifest replaces the local `activeDelegations` list. Every
certificate that was active locally and is absent from the new list is kept
locally as a **retired** certificate (`ChannelState.retiredDelegations`,
newest 64 by `delegationSeq`, never sent on the wire). Retired certificates
let the subscriber keep verifying posts that were signed under them, so a
revocation or renewal never makes an already-published post unverifiable.

## 4. Posts

Posts ride inside the pull response `posts` list. `ChannelPullCodec.postToWire`
/ `wireToPost`.

| key | BDF type | notes |
|---|---|---|
| `seqNum` | long | 0-based, at most 2^62 (`MAX_SEQUENCE_NUMBER`; a post above it is unreadable); the publisher assigns one more than its chain tip, never reusing a number (TTL / purge), and after a restore from a backup jumps 2^32 ahead |
| `prevHash` | raw | 32 bytes; canonical hash of the previous post (all-zero for `seqNum 0`, and for a chain restart, below) |
| `timestampHourMs` | long | floored to the hour |
| `body` | string | public: plaintext; private: unpadded standard base64 of the AES-GCM ciphertext including the 16-byte tag (no IV sent); absent from an erased post |
| `bh` | raw, version 5 replies only | 32 bytes (`POST_BODY_HASH_BYTES`), in place of `body` in a format 2 post whose body an edit erased: the `bodyHash` of its signed-input (Edits, below) |
| `ttlMs` | long | 0 = no expiry; ephemeral if > 0 |
| `signature` | raw | hybrid signature over the post signed-input of the post's format |
| `pv` | long, optional | post format: absent or 1 = legacy, 2 = format 2 |
| `salt` | raw, format 2 | 16 random bytes per post |
| `delegateSignerEd25519` | raw, optional | present only if signed by an editor |
| `delegateSignerMlDsa` | raw, optional | present only if signed by an editor |
| `attachments` | list of dict | each: `hash` (32B blob hash), `size` (long), `mime` (string), `key` (per-attachment key, wrapped for private channels), `thumb` (raw, optional) |

Every post published from 3.0.15 on is in format 2. A post with any other
`pv`, or a format 2 post without a 16-byte salt, is unreadable. Posts
published by earlier releases stay in the legacy format and are verified as
such; format is part of what the signature covers, so a post cannot be moved
from one format to the other.

At most 8 attachments per post; the publisher accepts up to 50 MiB of plaintext each. `ZERION_CHANNEL_GET_ATTACHMENT_V1` keys: `type`, `v`, `channelId`, `blobHash`, plus `nonce`, `hmac` and `hmac2` in private channels. The reply has `type`, `blobHash` and `blob` (empty if not held). The subscriber reads the reply with a field bound of 16 MiB, the largest reply it accepts. Metadata stripping is a client duty: the Android app re-encodes images and remuxes video and other ISO media before publishing (from 3.0.15). From 3.0.18 nothing is published unchanged: a remuxed MP4 has the creation and modification times and the language of its movie, track and media headers and the handler names of its tracks zeroed in place; a remuxed WebM has the muxing and writing application, title, date and file name fields of its segment information, its track names and the vendor string and comments of a Vorbis setup zeroed in place, and is refused if it carries tags, attachments or chapters; sound is labelled with the type its content shows, not the type the picker reports; a PDF is redrawn page by page as JPEG images in an isolated process into a new PDF without a document information dictionary, identifiers or XMP; plain text is kept only when it holds no NUL byte; every other file type is refused with a message that says why.

### Post signed-input (byte-exact)

Format 2 (`ChannelCodec.postSignedInputV2`), signed under
`SIGNING_LABEL_POST_V2 = "org.zerionproject/CHANNEL_POST_V2"`:

```
format (1)               = 2
int32 len || channelId
seqNum (8)
int32 len || prevHash
timestampHourMs (8)
ttlMs (8)
int32 len || salt
bodyHash (32)            = hash("…/CHANNEL_POST_BODY_V2", salt, UTF-8 wireBody)
attachmentsHash (32)     = hash("…/CHANNEL_POST_ATTACHMENTS_V2", salt, canonical)
```

The format 2 attachments canonical bytes (`attachmentsHashV2`) are
`int32 count` and then per attachment, each field length-prefixed with an
int32: `blobHash`, `int64 size`, UTF-8 `mime`, the per-attachment `key` as
sent (wrapped in a private channel), and `thumb` (length -1 when absent).
The signature therefore covers the per-attachment key: a post served with an
exchanged key is refused.

Legacy format (`ChannelCodec.postSignedInput`), signed under
`SIGNING_LABEL_POST = "org.zerionproject/CHANNEL_POST"`:

```
channelId (32)
seqNum (8)
prevHash (32)
timestampHourMs (8)
bodyHash (32)            = hash("…/CHANNEL_POST_BODY", UTF-8 wireBody)
attachmentsHash (32)     = hash("…/CHANNEL_POST_ATTACHMENTS", canonical attachments)
ttlMs (8)
```

Legacy `attachmentsHash` canonical bytes: per attachment `blobHash || int64
size || ASCII mime || (thumb?1:0) [|| thumb]`, all concatenated then hashed;
it does not cover the per-attachment key. A client up to 3.0.14 verifies
every post under the legacy label, so it refuses a format 2 post and stores
nothing of it. Note `body` is the **wire** body (the base64 ciphertext for a
private channel), so the signature covers exactly what is transmitted. For a
normal post the verifying key is the publisher hybrid key; for an editor's
post it is `HybridSignaturePublicKey(delegateEd, delegateMl)` after the
delegation checks pass (section 6).

### Hash chain (`ChannelChainVerifier` / `ChannelPostValidator`)

- The canonical hash of a format 2 post is
  `hash("org.zerionproject/CHANNEL_POST_CHAIN_V2", signedInputV2 || int32
  sigLen || signature)`; of a legacy post
  `hash("org.zerionproject/CHANNEL_POST_CHAIN", channelId || seqNum ||
  prevHash || timestampHourMs || UTF-8 body || attachmentsHash || ttlMs ||
  signature)`. Either way the chain commits to the exact signed bytes and
  the signature. A format 2 post's signature and canonical hash depend on
  its body only through `bodyHash`, so a post whose body an edit erased
  keeps both (Edits, below); a legacy post's canonical hash covers the body
  itself, which is one reason a legacy post cannot be edited.
- Every device keeps a **chain tip** per channel: the sequence number of
  the last post it accepted or, as publisher, issued, and that post's
  canonical hash. The tip stays when the post itself goes (expiry,
  deletion, the subscriber's storage window), so no sequence number is ever
  issued or accepted twice.
- A post with a higher sequence number than `tip + 1` opens a **gap**: the
  posts in between expired or were deleted before this device received
  them. A post at `tip + 1` may name the tip's hash, the zero hash (a
  **chain restart**, issued by a publisher that lost the hash of its last
  post) or any other hash (a publisher restored from a backup whose chain
  no longer ends where this device's does). Every one of these posts is
  verified under its signature, which covers the sequence number and the
  link, and a subscriber takes posts only from the owner's onion (Who
  answers a pull, above), so a gap or a new link is the publisher's
  statement and nothing a third party can cause; refusing a new
  link would add no integrity and only stop the channel for good. A device
  that holds nothing yet accepts its first post at any sequence number
  (sequence number 0 must name the zero hash). A sequence number at or
  below the tip, or above 2^62, is refused, and the loops over held ranges
  end whatever the range, so no number a publisher sends can hang or crash
  a subscriber. A publisher that finds its database was restored from a
  backup or copied to another device (the database and a file next to it,
  which no backup carries, hold the same random identity) moves every hosted
  channel's tip, manifest number and delegation counter 2^32 ahead, so its
  next post and manifest reach subscribers that already hold the numbers it
  would otherwise repeat.
- `ChannelPostValidator` additionally enforces 4096 chars
  (`MAX_POST_BODY_CHARS`) on the wire body and verifies the post signature.
  The publisher checks the plaintext and, in a private channel, also the
  encrypted wire body against the same limit, and refuses a post whose
  wire body is longer (`ChannelPostTooLongException`, since 3.0.15), so no
  post is published that subscribers would refuse for its length
  (`BODY_TOO_LARGE`) and that would hold back the posts after it.

### Skip-known rule (incremental apply)

`ChannelPullProtocol.processSubscriberResponse`: posts at or below the
subscriber's chain tip are skipped. Each remaining post is validated against
the merged state and the tip after the previous accepted post:

- `OK`: accepted and shown.
- `DELEGATION_REVOKED` (chain, certificate signature, window and post
  signature all verified; only the delegation is revoked): accepted and
  stored **withheld**. The body, attachments, reactions and comments are not
  shown, no unread count or notification is raised, and reactions to the
  post are refused.
- `DELEGATION_NOT_FOUND` for an editor's post whose chain link is valid: the
  subscriber has never held that certificate and cannot check the
  signature. The post is held **provisionally** and accepted, withheld, only
  when a later post in the same batch verifies and follows it directly, so
  committing to it through the hash chain. Since no signature vouches for
  such a post yet, it must link to the tip exactly when it is at `tip + 1`
  (a new link is accepted only under a verified signature); it may open the
  chain of a device holding nothing or follow a gap. A provisional run that
  reaches the end of the batch is discarded, so an unverifiable tail is
  never stored, and a gap may open a provisional run but never close one.
- anything else (`CHAIN_BROKEN`, `SEQ_OUT_OF_ORDER`, `BAD_SIGNATURE`,
  `DELEGATION_OUT_OF_WINDOW`, `BODY_TOO_LARGE`): **breaks** the loop; the
  rest of the batch is discarded.

An erased post that passes these checks is then held to the rule in Edits
(below): in a complete reply (fewer than 100 posts, none of which broke the
loop) it is refused, with everything after it, unless a valid mark naming
it follows; in any other reply it is stored, but not shown, counted or
announced until such a mark arrives or it is fetched again with its body.

`PULL_BATCH_MAX_POSTS = 100`. Withholding is a subscriber-side view
decision; nothing about it appears on the wire, and the publisher serves the
original post bytes unchanged.

### Storage

Each post is stored as its own value (settings namespace
`zerion-channels-post:<channelId hex>:<seqNum>`), next to one small record
per channel (`zerion-channels-post-meta:<channelId hex>`) holding the ranges
held, the chain tip, the count and bytes held, the highest post read, the
withheld posts, the erased posts still waiting for their edit mark (at most
200), the runs of posts the device cannot read (at most 64 runs of
consecutive sequence numbers, below), the posts at or below the highest
post read that became visible after it and count as unread again (at most
64 runs), the earliest expiry and the posts given up. Appending a post
writes that post and the record in one transaction. A post whose body an
edit erased is
stored with an empty body, its body hash (`bh`) and, in a private channel,
the hash of the content key its body could be read under (Edits, below).

**Unread posts.** The unread count is the number of held posts above the
highest post read that are not withheld, not erased posts waiting for their
mark and not posts the device cannot read, plus the posts at or below it
that became visible after it (until the channel is read again). Opening the
channel moves the highest post read to the newest held post and clears
those. A post is announced (`ChannelPostReceivedEvent`) once, when it
becomes visible: on arrival, or later when its mark arrives, it is fetched
again with its body or the device gains the key that opens it.

**Posts the device cannot read.** In a private channel, a post that no
content key the device holds opens (made under a key from before a renewed
link, or arriving in a reply that brought no key) is stored, so the chain
moves on, but not shown, counted or announced (from 3.0.18). A subscriber's
content key changes only when a pull reply's `contentKeyEnvelope` gives it
a key it did not hold (`ChannelPullProtocol.processSubscriberResponse`,
stored by `pullOnce`, or for a renewed link by `rejoinChannelThroughLink`
or the link's candidate): normally the first reply after it joined or after its application
was approved. A member cut off by a renewed link gets no new key until it
uses the new link. Up to 3.0.17 it then had to leave and join again,
starting from an empty channel; from 3.0.18 it can take the new link
without leaving (section 6, Rejoining with a renewed link) and keeps the
content key it held as an earlier key
(`zerion-channels-retired-content-keys`, the newest 64), as it does when an
application approved again brings it a new capability. A
member reads posts with every content key it holds, current or earlier, so
the posts it holds, and posts under those keys that it fetches later, stay
readable; posts under a key it never held stay unreadable. Each
time the key changes, the device reads the posts it holds but could not
read (`openNewlyReadable`): a mark among them is stored hidden and applied,
and every other post that now opens is counted and announced once, also
when the channel was read past it. Such posts are kept in at
most 64 runs of consecutive sequence numbers, so a run of thousands of
posts under an old key costs one entry; a post that would start a 65th run
is passed over like a post above 1 MiB (the chain moves past it and it is
counted among the posts given up).

Releases up to 3.0.14
kept all posts of a channel as one list (`zerion-channels-posts`); the first
access to such a channel reads that one value, decodes it post by post and
moves the posts in chunks of at most 64 posts or about 1 MiB, each chunk in
one transaction with the record, which notes how far the move got, so a move
cut short resumes where it stopped and memory stays at about the size of the
stored value. The list is deleted when the move completes, and a list left
behind by an interrupted move is deleted on the next access. Removing a
channel deletes the posts the record names and the list itself without
moving anything, so a channel whose list cannot be moved can still be
deleted. The owner's device also keeps the bytes of editors' posts it holds
per channel (section 6), and a random installation identity
(`zerion-channels-instance`, mirrored in the file `channel-instance` next to
the database) that tells a restored or copied database apart (Hash chain).

A subscriber keeps at most 10,000 posts and 32 MiB of posts per channel as a
**rolling window**: the oldest held posts give way to new ones, except the
pinned post and one edit mark, the newest valid mark naming the pinned post
within its edit window, which gives the pinned post its text (section 4,
Edits); every other mark naming the pinned post, an earlier one or one made
outside the window, gives way like any post, so the ceilings hold whatever
the owner publishes. A single post above 1 MiB is passed over (the chain
moves past it and it is not kept). The feed says when older posts were
removed this way. A full channel therefore keeps receiving new posts. At
most 200 erased posts wait for their mark at once
(`ChannelPostCeilings.MAX_WAITING_ERASED_POSTS`); when another arrives, the
oldest waiting one is given up.

### TTL / purge

A task run 5 minutes after the database opens and then every 24 hours
(`purgeExpiredPosts`) runs on the publisher and on every subscriber and
deletes each post with `ttlMs > 0` and `now > timestampHourMs + ttlMs`,
with its blobs, reactions and comments. A channel is read post by post only
when its record says a post has expired, or once a week to delete attachment
blobs nothing refers to; otherwise the purge reads one record per channel.
The chain tip stays, so the publisher's next post takes the next sequence
number, never the number of an expired one; subscribers that held the
expired post accept it directly, and subscribers that never held it bridge
the gap. A private format 2 body is encrypted under a nonce derived from the
sequence number and the post's random salt, so even a sequence number issued
twice (a publisher restored from an old backup, or the same account active
on two devices) cannot repeat a nonce. `MAX_TTL_SECONDS` and the `TTL_*`
constants are defined but not enforced.

### Deletes

A post delete is itself a published post whose body is a tombstone marker
`ZRN_TOMBSTONE:<channelIdHex>:<seqNum>:D` (`TOMBSTONE_PREFIX`); in a private
channel the marker is encrypted like any body. Only a post **signed by the
publisher** is a marker: an editor's post is a post whatever its body says,
and the publisher refuses a submitted post whose body has the marker's shape
or the shape of an edit mark (section 6), so no editor can make a marker.
An editor deletes one of its own posts by asking the owner's device
(`WIRE_TYPE_CHANGE_POST`, section 6), which checks the request and then
publishes the marker under the channel's key exactly as for the owner's own
deletes, so subscribers of every release from 3.0.15 apply it. The publisher
then deletes the target post itself, its attachment blobs, every edit mark
naming it within its edit window, whatever the post's format (Edits,
below), and its reactions and comments, and stops serving them; a
subscriber of this release that receives the marker does the same with its
copy. The owner's device refuses to delete a marker on its own, so an
edited post never loses the mark that gives its text. A subscriber from 3.0.18 stores a received marker, deletion or edit,
as a hidden post: it raises no notification and counts as no unread post
(up to 3.0.17 a deletion marker did both). Subscribers that had not fetched
the target yet bridge the gap. Subscribers on releases up to 3.0.14 keep a
copy they already hold and show it with a "deleted" placeholder; one that
had not fetched the target yet stops at the gap, as it stops at the first
format 2 post anyway (the marker itself is one), until it updates. This is
distinct from the channel-level tombstone (section 8).

### Edits

From protocol version 4. An edit is a published post whose body is an edit
mark `ZRN_EDIT:<channelIdHex>:<seqNum>:<text>` (`EDIT_PREFIX`), signed by the
publisher like a deletion marker and encrypted like any body in a private
channel. As with deletions, only a post signed by the channel's key is a
mark. A device shows the text of the newest mark naming a post in place of
the post's own body, keeps the post's attachments and marks it edited; the
marks themselves are never shown. A mark counts only when it names an
earlier post the device holds that is neither deleted nor itself a marker,
and the mark's hour is at most 24 hours after the post's
(`POST_EDIT_WINDOW_MS`, the window of edits in one-to-one chats; with
timestamps floored to the hour the owner can edit for 24 to 25 hours after
posting). A withheld post (section 4, Skip-known rule) stays a placeholder:
the text of a mark naming it is not shown, but the mark erases its body like
any other (below). Only a format 2 post can be edited, and no device of
this release makes a mark for a post in the legacy format. Development
builds of 3.0.18 made before protocol version 5 could; such a mark still
gives the legacy post its text and replaces an earlier mark, but a legacy
post's body is never erased, since its canonical hash covers the body
itself (Hash chain, above), so its original text stays in the stored
record. The window keeps what readers read and discussed from being
rewritten long afterwards; deleting stays possible at any time. The owner
edits any post; an editor edits only its own posts, through a change request
(section 6). The mark of an ephemeral post expires when the post does (its
TTL ends where the post's ends). When a mark is published or taken in, the
device deletes the earlier marks of the same post, so the publisher serves,
and a new subscriber fetches, only the newest one, and deleting a post
deletes its marks.

**The original is erased (from 3.0.18, protocol version 5).** There is no
edit history, so no device keeps the earlier version of an edited post.
When a mark is published, or taken in by a subscriber, and names an earlier
format 2 post the device holds that is not itself a mark, within the window,
the device first deletes the earlier marks of that post, in a transaction
of their own (a deletion, which requests the checkpoint below), and then
erases the post's body, also when the post is withheld: it rewrites the
stored post, in one transaction with the channel's post record, with an
empty body and the post's body hash `bh` in its place (the 32-byte
`bodyHash` of its format 2 signed-input, `hash("…/CHANNEL_POST_BODY_V2",
salt, UTF-8 wireBody)`, taken over the ciphertext in a private channel)
and, in a private channel, the hash of the content key it could read the
body under (Private channels and renewed links, below). It then requests
the truncating WAL checkpoint that follows every committed deletion, so the
space SQLCipher's `secure_delete` zeroed when the stored value was replaced
is written over the old pages in the database file and the log is emptied.
A second edit therefore leaves neither the original nor the first edit's
text. What stays of the post is what verification, ordering and display
need: its sequence number, link, hour, TTL, format, salt, signature, the
signer keys of an editor's post, its attachments (an edit changes only the
text) and the body hash. The signature and canonical hash of a format 2 post cover the body
only through `bodyHash` (Hash chain, above), so the erased post still
verifies under its signature, keeps its canonical hash, and the chain
through it is unchanged. A private channel encrypts every body under the
channel-wide content key, which every member holds, so there is no per-post
key whose deletion would erase a body cryptographically; the ciphertext
itself is erased. An erasure cut short (the process killed after the mark
was stored and before the earlier marks were deleted or the post was
rewritten) is completed when the feed next reads the mark, if the mark is
among the newest posts the feed reads (`getRecentPosts` reads the posts it
returns and the 16 before them), which also deletes an earlier mark it
finds left behind; a mark further back is completed by the weekly full scan
of the storage task (`purgeExpiredPosts`), which applies a mark again only
when its post still holds a body, still waits for its mark or has another
mark, so the scan reads the channel once however many marks it holds. The
erased text is in no other record either: a
notification of new posts shows a count, not text, the channel list and the
feed show what `getRecentPosts` returns and reload on every post and state
event, and a backup is made with `VACUUM INTO`, which copies only live
rows.

A request with `v >= 5` is served the stored posts as they are, so an
erased post travels with `bh` and without `body`, followed later in the
chain by its newest mark; in a private channel the publisher passes over an
erased post that cannot be read under the current content key (Private
channels and renewed links, below). A subscriber accepts an erased post
only when it is in format 2, its `bh` has 32 bytes, it verifies under its
signature over that hash and links like any post. What happens next
depends on the reply:

- A valid mark naming it (signed by the channel's key, within the window)
  follows in the same reply: the post is stored and shown with the mark's
  text. A later post signed by the channel's key that the device cannot
  decrypt (made under a content key it does not hold) counts as such a
  mark, since the device can read neither; the erased post is then stored
  and waits, unseen, until the device can read that post (Storage, Posts
  the device cannot read).
- The reply is not complete: it has the full 100 posts
  (`PULL_BATCH_MAX_POSTS`), whatever became of its last posts (an editor's
  posts the device cannot check yet are held back, Skip-known rule, above),
  or one of its posts failed its checks, so the rest of it was discarded.
  The mark may be in a later reply, so the erased post is stored and waits
  for it: it is not shown, counts as no unread post and raises no
  notification, and a comment on it raises none either. When a valid mark
  naming it is taken in, the post is shown, counted as unread (also when
  the channel was read past it while it waited) and announced once, like a
  post that has just arrived; a post withheld meanwhile (its editor was
  revoked) is neither counted nor announced, and one the device cannot read
  joins the posts it cannot read. The subscriber moves past a waiting post
  like past any other, so a full reply that ends in a post it cannot check
  yet, or a post that fails between an erased post and its mark, never
  holds it at the erased post.
- Otherwise the reply is complete and holds no mark for it, which an honest
  publisher never sends: it keeps the newest mark as long as the post (the
  mark's TTL ends where the post's ends, and the owner's device refuses to
  delete a marker on its own). The erased post and every post after it in
  the reply are refused, and the next pull starts again before it.

A post still waiting once a complete reply has brought the subscriber up to
the owner's chain tip has no mark coming. The subscriber then asks the same
onion for the posts from that post on (`repairWaiting`, at most 4 requests
after a pull) and compares each waiting post with the post the reply
carries at its number. A post with a body whose canonical hash equals that
of the stored erased post (so its body hashes to the stored `bh`, which its
signature covers) and that stays within the 1 MiB a stored post may take is
stored with that body and shown, counted and announced once like a post
that has just arrived; a post the reply carries erased again or above that
size, or does not carry although the reply covers its number, is given up
and deleted, since it can never be shown. A waiting post followed by a post
the device cannot read is not asked for, since that post may be its mark.
So a post whose body went missing (only the owner's device answers a pull,
Who answers a pull, above) is never hidden for good: it comes back with its
body or is deleted.

An erased post is shown only with the text of a valid mark naming it and is
otherwise not shown at all, never as an empty post. A reply of version 4 or
lower that carries `bh`, and an editor's submitted post that carries it,
fail to decode. When a subscriber's storage window gives way (Storage,
above), it keeps the newest valid mark of the pinned post and no other.

**Private channels and renewed links.** A device shows an erased post of a
private channel only while it holds the content key under which it could
read the original. The device that erases a body notes in the stored post
the hash of the content key that opened it (an empty value when the body
showed as plain text, nothing when the device could not read it), and a
subscriber that receives an erased post notes the hash of the content key
that the reply's signed manifest names (`contentKeyHash`, the key the owner
served it under), also when that reply brought it no key yet, so the post
shows once the device gains that key; this local field is never sent. The
owner's device sends an erased post only when the noted key is the
channel's current content key (or the body was plain text) and passes over
the others, as it passes over erased posts for older requests (below). A
post that a development build of 3.0.18 erased before this rule existed
noted no key: the owner's device, which keeps every content key the channel
had, shows it, and sends it only while the channel never renewed its link;
a subscriber keeps it hidden. A member that joined after the owner renewed
the link holds only the keys from then on (one that took the renewed link
while it held the channel also keeps the keys it held before, Storage
above), so a post made under a key in force before the owner
renewed the link stays hidden from members who joined after the renewal,
also once it is edited, as it did before it was erased: a member that
already held its ciphertext erases it and keeps it hidden, and one that had
not fetched it sees a gap. An edit mark thus never makes visible a post the
device could not read, nor attachments whose keys it cannot unwrap. An
erased post that a device erased while it could not read it, or received
under a key it never gains, is never counted or announced, also when its
mark is taken in again (a second edit or the weekly scan).

**Older subscribers.** A subscriber whose request has `v` below 5 is never
sent an erased post: the publisher passes over erased posts when it fills
such a reply (`withoutChanges`), so a subscriber that had not fetched a post
before it was edited sees a gap in the sequence numbers, which it accepts
(Hash chain, above), and never sees that post: its original text is gone and
such a subscriber cannot read the erased form. A request with `v` = 4
(3.0.18 development builds made before version 5) is still sent the marks.
A request with `v` below 4 (3.0.15 to 3.0.17, and 3.0.18 development builds
sending 3) is sent no mark either: the publisher passes over marks as well,
also across pages made only of marks and erased posts. Releases 3.0.15 to
3.0.17 treat every channel-signed body that starts with
`ZRN_TOMBSTONE:<channelIdHex>:<number>:` as a deletion and remove the post,
and show every other channel-signed body as text, so a mark sent to them
would either delete the post or appear as raw text. Clients up to 3.0.14
stop at the first format 2 post anyway.

**Limits.** The erasure reaches the devices from 3.0.18 on that hold the
post when the mark reaches them: the owner's device, editors and every
subscriber (a subscriber that opted in to mirroring stores posts as any
subscriber does). A subscriber on 3.0.17 or earlier that fetched the post
before it was edited keeps the original text, since no edit ever reaches
it, and so does a subscriber that updates after it passed the mark: its
chain tip is already past the mark and the publisher does not send it again.
Nothing erases copies made before the edit reached a device (a backup
exported earlier, screenshots, another app the text was shared with), and
the erasure is logical: what flash wear levelling or a copy-on-write file
system keeps below the database file is out of its reach, and no physical
overwrite of the flash is claimed. A legacy-format post keeps its original
text (Edits, above).

**The body hash can be tested against guesses.** The erased post keeps `bh`
together with the salt it was taken with, and both reach every device the
post reaches: anyone who can pull a public channel, and in a private
channel every member, who holds the content key the body was encrypted
under (the publisher sends an erased post of a private channel only to
members who hold that key, above). Whoever holds them can test a guess of
the original offline, as often as they like and without the publisher
noticing: in a public channel by hashing the candidate text with the salt,
in a private channel by encrypting the candidate under the content key with
the nonce that the sequence number and the salt give, which makes the
ciphertext deterministic, and hashing that. The text the mark shows is a
strong seed for such guesses. Most edits are small (a typo, a digit, a
date, an amount, a name, a missing word), so the original is usually a
small variation of the text on screen: trying every change of one or two
characters, or of one word from a dictionary, in a text of a few hundred
characters takes millions of hashes, which an ordinary computer does in
seconds, and a short original, or one drawn from a small set (an amount, a
time, yes or no), falls the same way. The erasure keeps the original from
being read back from a stored record; it does not keep it secret from
whoever can guess it, so a small correction must be assumed recoverable by
every reader who receives the erased post. A text that must not be
recoverable should be deleted (a deleted post keeps no hash, Deletes,
above) rather than edited.

The body hash stays because the format needs it. It is part of the format 2
signed input, so the post's signature covers it, and through the canonical
hash the next post's `prevHash` names it. A subscriber that fetches the post
after the edit verifies the signature over `bh` and links the next post to
the canonical hash computed from it; the owner's device sends `bh` with the
erased post to every request of version 5 or later, so that such
subscribers can do this; a subscriber checks a post taken back by
`repairWaiting` against it; and a subscriber checks an editor's stored posts
again under their signatures when the editor's certificate is revoked
(`ChannelWithholding`), erased posts included. A value keyed by a secret of
the device, kept in place of the stored hash, would not close the test
either: the stored signature verifies only over the right body hash, so the
signature, the salt and the other signed fields confirm a guess on their
own, and the owner's device has to serve the hash itself to every new
reader. Closing the test needs a post format whose body hash also covers a
random secret that travels with the body and is erased with it. Releases
3.0.15 to 3.0.17 refuse a whole pull reply that carries a post in any format
other than the legacy one and format 2, so posts in such a format could not
be published to them. This release does not change the format: every format
2 post, published before or after it, keeps a body hash that can be tested
in this way.

## 5. Comments and reactions

Both are submitted to the publisher's onion as separate RPC types and stored
at the publisher; they are then redistributed to all subscribers inside
subsequent pull responses (`reactions` / `comments` lists, as deltas to
version 2 requests, section 2). Both are signed with a hybrid key (Ed25519 +
ML-DSA-65), not the publisher's channel key. ML-DSA is mandatory for these
signatures: `ChannelSignatures.signUser` throws if the ML-DSA private key is
missing, and `verifyUser` returns false if the signer's ML-DSA public key is
absent. From 3.0.18 every device binds each author's Ed25519 key in a
channel to one ML-DSA-65 key and refuses an item whose ML-DSA-65 key differs
(Author binding, below); up to 3.0.17 the ML-DSA public key was taken from
the same message without any binding, so authorship rested on the Ed25519
signature.

**Which key signs.** From 3.0.15 a member signs its reactions, comments,
announcements, applications and approval checks in a channel with a **member
key of that channel** (`ChannelMemberKeys`): `root = deriveKey(
"org.zerionproject/CHANNEL_MEMBER_KEY", SecretKey(identity Ed25519 private
key), channelId)`; the Ed25519 half is the key with seed
`hash("…/CHANNEL_MEMBER_KEY_ED25519", root)[0:32]`, the ML-DSA-65 half the
key FIPS 204 generates from seed `hash("…/CHANNEL_MEMBER_KEY_ML_DSA",
root)[0:32]`. It is the same key every time the member acts in that channel,
also after leaving and joining again, and nothing of it is stored; without
the identity private key no one can tell which identity it belongs to or
link it to the member's key in another channel. The owner signs with the
channel's own key, so the owner speaks as the channel. A comment carries the
name the member announced in the channel, or none; the account name is
never used. Clients up to 3.0.14 sign with the account identity keys and the
account name; their items stay linkable. An application made by such a
client keeps having its approval checked with the identity key after the
update.

Every pull reply carries, for every retained comment, the author's name (if
any) and keys, and for every reaction the signer's keys, to every puller
(anyone who knows a public channel's onion; every capability holder of a
private channel).

**Retention.** The publisher tells signers it **knows** from anonymous ones:
the channel's own key, the subscribers the owner marked as **trusted**
(`zerion-channels-trusted`, the newest 4096 keys),
approved applicants (from 3.0.18 those approved under the capability the
channel uses now and, until the first renewal of the link from 3.0.18 on,
those approved before 3.0.18; section 6, Apply) and editors are known,
none of them banned. A key that
merely announced a name is not known: announcing costs nothing, so it would
let anyone make as many known keys as it likes. Anonymous items share smaller ceilings (128
per channel, 32 per post, 8 per signer, 768 KiB) and give way only to each
other: an anonymous item never removes a known signer's item, and is refused
when only known items could make room. A known item makes room by removing
anonymous items first, then the oldest known items, within the channel
ceilings (256 items, 64 per post, 32 per signer, 1.5 MiB). Anonymous writes
share the channel's write allowance (64 MiB burst, 512 MiB per hour); each
known signer has its own (16 MiB burst, 128 MiB per hour), so a flood of
fresh keys can neither erase nor block what known subscribers wrote. The
apps show comments in the order the publisher admitted them, with the
author's name or "Anonymous", the first 8 hex digits of the author's key in
the channel, and an owner mark for the channel key.

**Freshness.** A reaction or comment is taken in only while its timestamp is
at most 48 hours old and at most 2 hours ahead; a different reaction whose
timestamp is not after the signer's held reaction on the same post changes
nothing. Timestamps are whole hours, so a member who changes a reaction
within the hour signs the change for the hour after the held one (and is
told to wait when that would be more than 2 hours ahead); a signed item seen
once therefore cannot be replayed later to come back after it gave way or
to override a later choice, not even one from the same hour.

**Bans.** The owner bans a key, announced or not, in a list kept per channel
(`zerion-channels-bans`, the newest 4096 keys). Banning removes the key's
reactions and comments, which subscribers drop on their next pull, takes
away its trusted mark, revokes any editor certificate naming it and refuses
its future reactions, comments, announcements, applications, submitted
posts and change requests. A ban cannot stop anyone from reading: pulls are
anonymous, so the publisher cannot tell a banned key's pulls apart. The
owner bans ("Block" on screen) and marks subscribers as trusted ("Trust
their comments") from the member sheet, which from 3.0.18 opens from the
list of people who shared their name (announced subscribers that are not
banned; the settings count exactly these, since anonymous readers are not
known), from a comment the owner holds and from the editor list, and is
the only place these actions are offered. Its Safety code, under Advanced,
is the app's identity fingerprint (`IdentityFingerprint.forIdentity`:
BLAKE2b-256 over the Ed25519 and the ML-DSA-65 half of the member's
channel key, shown as six groups of ten numbers); the member sees the
same code for their own channel key under Advanced in the channel
settings, and no code is shown while the owner's device does not know the
ML-DSA half. From 3.0.18 the owner can
lift a ban (`unbanSubscriber`, "Unblock" in the member sheet; the Blocked
list in the channel settings also lists banned keys that never announced a
name): the key leaves the list and its new items are taken again, but what
the ban removed is not restored, the trusted mark is not given back and a
revoked editor certificate stays revoked. To cut someone off from a private
channel the owner removes everyone from it with "Remove from channel" in
the member sheet, which bans the key and renews the invite link (or with
"Renew invite link" in the channel settings), which rotates the invite link: a
new capability, content key and onion, so every subscriber needs a new
link (section 6) and, in a channel that needs approval, a new approval;
there are no per-reader keys.

### Author binding (protocol version 3)

From 3.0.18; the reference, with the analysis, test vectors and limits, is
[CHANNEL-AUTHOR-PQ-BINDING.md](../protocol/CHANNEL-AUTHOR-PQ-BINDING.md).
`K(M) = hash("org.zerionproject/CHANNEL_AUTHOR_KEY", M)`. The binding of an
author key `E` is, in order: `K` of the channel's ML-DSA key if `E` is the
channel key; the pin stored for `E`; `K` of the ML-DSA key every held
delegation certificate naming `E` names (certificates that disagree bind
`E` to nothing); otherwise none. Every comment, reaction, announcement,
application and approval check the publisher receives, every editor
certificate it issues, and every comment and reaction a subscriber takes
from a reply must carry an `ml` whose `K` equals the binding of its `ed`;
the check runs before the signature is verified and does not depend on the
peer's protocol version. When an item of a new `E` verifies and is taken
in, `K(ml)` becomes the pin of `E`, written before the item; a pin is never
replaced (no rotation). The first time a device checks a binding in a
channel it pins the keys of the records it already holds (certificates;
publisher: applications, announced subscribers; comments; reactions; first
record wins). Pins live in `zerion-channels-author-pins:<channelId hex>`
(row `hex(E)` = `counter:hex(K)`, rows `n`, `s`, `v`), at most 4096 per
channel beyond the authors of held records; the oldest pins of authors the
device holds nothing of are dropped first.

A reply of version 3 is signed over the bindings it carries:

```
D = hash("org.zerionproject/CHANNEL_AUTHOR_BINDINGS",
         int32 count || (ed (32) || K(ml) (32)) ...)
```

over the distinct pairs of all its comment and reaction entries, sorted as
unsigned bytes; the manifest signature is then over `signed-input || D`
under `SIGNING_LABEL_MANIFEST_V3` (section 3). Such a reply carries only
entries with a 32-byte `ed` and a 1952-byte `ml`, and the decoder drops any
other entry, so both sides hash the same entries. A subscriber that has
taken in a version 3 reply of a channel (row `v`) takes comments and
reactions of that channel only from version 3 replies afterwards; a reply
of an earlier version still updates the manifest and the posts and leaves
the item cursors where they were.

### Comment submit - `WIRE_TYPE_POST_COMMENT`

`encodeCommentRequest` keys: `type`, `v`, `channelId`, `seq` (parent post
seqNum), `id` (commentId, a random `long`), `body`, `name` (author display
name, empty when none was announced), `ts`, `ed` (author Ed25519 pub), `ml`
(author ML-DSA pub), `sig`, plus `nonce`, `hmac` and `hmac2` in private
channels.
Response is `WIRE_TYPE_COMMENT_ACK` = `{type, ok: boolean}`.

Comment signed-input (`ChannelCodec.commentSignedInput`):
```
channelId (32) || int64 parentPostSeqNum || int64 commentId
|| int32 bodyLen || UTF-8 body || int32 nameLen || UTF-8 name
|| int64 timestampHourMs
```
Label `SIGNING_LABEL_COMMENT = "org.zerionproject/CHANNEL_COMMENT"`.

Publisher acceptance (`handleCommentRequest`), each check returns `ok=false`
on failure: a valid capability proof in private channels; channelId match;
`ed` of 32, `ml` of 1952 and `sig` of 3373 bytes; comments on for the parent
post (Comment settings, below); body
1 to 1024 chars and name at most 64 chars; parent post held and not
withheld; timestamp fresh; the write allowance of the author's class not
spent (checked before the signature); `ml` matching the author's binding
(Author binding, above); valid author signature; author not banned; no held comment with the same `commentId` (an
identical resubmission returns `ok=true` without change); room under the
retention rule above. Subscribers never learn the ban list.

**Comment dedup**: a comment whose `commentId` is already held is accepted
only if identical. Stored sets are kept within the same ceilings.

In the **comments** list of a pull response the keys are: `seq`, `id`,
`body`, `name`, `ed`, `ml`, `ts`, `sig` (sig omitted if empty). On receipt
(`applyIncomingComments`) the subscriber applies the reply as a whole set or
as a delta (section 2): it keeps verified items on held, non-withheld posts
whose keys match their authors' bindings, fits them to the ceilings and
stores the result in one write. It fires
`ChannelCommentReceivedEvent` for new comments.

### Reaction submit - `WIRE_TYPE_POST_REACTION`

`encodeReactionRequest` keys: `type`, `v`, `channelId`, `seq` (post
seqNum), `emoji`, `ts`, `ed`, `ml`, `sig`, plus `nonce`, `hmac` and `hmac2`
in private channels. Response is `WIRE_TYPE_REACTION_ACK` =
`{type, ok}`.

Reaction signed-input (`ChannelCodec.reactionSignedInput`):
```
channelId (32) || int64 postSeqNum || int32 emojiLen || UTF-8 emoji
|| int64 timestampHourMs
```
Label `SIGNING_LABEL_REACTION = "org.zerionproject/CHANNEL_REACTION"`.
`MAX_REACTION_EMOJI_BYTES = 32`; the ceilings and the retention rule are
those of comments (section 5). The target post must be held and not
withheld, the keys and signature must have the sizes of a comment's and
`ml` must match the signer's binding.

**Reaction identical-skip** (`ChannelReactionPolicy.withAdmitted`): a
reaction is keyed by `(postSeqNum, signerEd25519)` - one reaction per signer
per post. A newer submission for an existing `(post, signer)` **replaces**
it where it stands; an identical one or an older one changes nothing.
Announcements carry no such ordering: an older signed announce still
replaces the current name.

In the **reactions** list the keys are: `seq`, `emoji`, `ed`, `ml`, `ts`,
`sig`. On receipt (`applyIncomingReactions`) the subscriber applies the reply as
a whole set or a delta, as for comments.

### Announce - `WIRE_TYPE_ANNOUNCE`

A subscriber may announce a display name to the publisher (manual and
optional). Announces are stored only by the publisher and are not sent to
other readers. `encodeAnnounceRequest` keys: `type`, `v`, `channelId`, `name`,
`ts`, `ed`, `ml`, `sig`, plus `nonce`, `hmac` and `hmac2` in private
channels. The key is the member key of the channel. Response `WIRE_TYPE_ANNOUNCE_ACK
= {type, ok}`. Signed-input (`announceSignedInput`): `channelId || int32
nameLen || UTF-8 name || int64 ts`, label `SIGNING_LABEL_ANNOUNCE`.
`MAX_DISPLAY_NAME_BYTES = 64`, `MAX_ANNOUNCED_SUBSCRIBERS = 4096`. There is
no automatic announce.

### Comment settings

The owner sets whether comments are on for the whole channel
(`setDiscussionsEnabled`) and for single posts
(`setPostCommentsEnabled`); an editor sets it for its own posts through a
change request (section 6). The setting is the channel's value plus a list
of posts whose setting differs from it (`ChannelDiscussionStore`, rows
`zerion-channels-discussions` and `zerion-channels-discussion-exceptions`,
at most 4096 posts; a further post is refused); a post's comments are on
when the channel's value is on and the post is not listed, or the channel's
value is off and the post is listed. Changing the channel's value clears the
list, so it applies to every post until the owner sets single posts again;
the list keeps only posts the device still shows. The publisher returns
`ok=false` to a comment on a post whose comments are off, whatever the
sender's version.

From protocol version 4 every reply carries the setting in `ds` (section 2)
under the owner's signature (section 3, `T`): `on` is the channel's value and
`x` the list. A subscriber stores the setting of each reply of version 4
or 5 it takes, hides the comment entry of a post whose comments are off, shows why,
and refuses to send a comment there; a reply of an earlier version (an owner
on an earlier release) clears what it stored, so the entry is shown again
and the owner's acknowledgement decides. The setting is signed rather than
merely served over the onion because everything a subscriber takes in is
verified under the channel's key: a remembered old onion, or a server
restored from an older backup, can then never make a subscriber hide or show
comments the owner did not choose. Subscribers up to 3.0.17 never learn the
setting: they show the comment entry everywhere and are told that a comment
on a post with comments off could not be sent.

### Member outbox (device behaviour, no wire change)

From 3.0.18 a member's device keeps a request it could not deliver instead
of dropping it (`ChannelOutboxStore`). This covers comments, reactions,
announcements, and an editor's posts and change requests (section 6);
applications keep their own record (section 6, request and approve).
Nothing new goes on the wire and nothing is relayed: the requests are the
ones above, sent only to the owner's own onion, and only while it answers.
Up to 3.0.17 such a request failed and was lost.

**A new request.** The member first checks what it can on its own
(comments on for the post, an editor certificate, a post that fits, a
target that is its own post); a reaction that would have to be signed more
than 2 hours ahead fails as too soon without removing the reaction waiting
for that post.

When an item the new request replaces is waiting (a reaction to the same
post, a name, an edit or comment setting of the same post), the new request
takes that item's place in the queue: in one step under the channel's lock
the waiting item's content is replaced by the new one, with the new kept
time and no failed tries, and other items of that kind for that post,
waiting or not sent, are removed. The device holds the channel's send lock
while it does this, so the old content cannot be on its way at that
moment, and it is never sent afterwards. The queue is then sent as below,
the new request in the replaced item's place. The call succeeds if the new
request is accepted; it fails and the request is removed from the queue if
the owner refuses it, if the owner keeps answering `STALE`, or if it has to
be signed more than 2 hours ahead. If access is lost in that round the call
fails with `ChannelNoAccessException` and the request stays in its place,
not sent for lost access like every other waiting item, and comes back with
them (below). Otherwise it stays in that place and the call fails with
`ChannelRequestQueuedException`, as below. Nothing remains of the replaced
content.

Otherwise, when items are waiting, the device pulls first and sends them
(below); the new request is then sent only if none is left waiting in front
of it, otherwise (also when that pull fails) it is kept behind them without
being sent, and not-sent items of the same kind for the same post are
removed. A sent request ends one of four ways:

- accepted (`ok=true`, `OK`): done, and not-sent items of the same kind
  for the same post are removed;
- refused (`ok=false`, `REFUSED`, an empty reply to a submitted post or
  change request, which is how an owner on an earlier release answers, or
  `STALE` three times in a row): the call fails and nothing is kept;
- no access: in a private channel the owner's onion answered a comment,
  reaction or announcement with an empty reply, a pull then failed although
  the onion answered, and the onion the member trusts for the channel
  (read again after that pull) still answers a request that needs no
  capability proof (`WIRE_TYPE_CONFIRM_ONION`) with a confirmation of that
  same onion that verifies under the channel's key with the same checks as
  when an onion is first confirmed: the member's capability proof is no
  longer accepted. The call fails with `ChannelNoAccessException` and
  nothing is kept; a confirmation that is missing, names another onion or
  does not verify makes it no answer instead. An empty reply alone is never
  taken as no access, since
  the owner's device also answers empty when its outgoing allowance is
  spent, possibly after it stored the item: if the pull succeeds and shows
  the item it counts as accepted, and otherwise as no answer; an owner on a
  release that does not answer `WIRE_TYPE_CONFIRM_ONION` never yields no
  access;
- no answer (no connection, a timeout, an unreadable reply, or an empty
  reply that is not no access as above): the item is kept, replacing the
  items it replaces, and the call fails with
  `ChannelRequestQueuedException` (a `DbException`), so it is never
  reported as sent. The apps say it will be sent when the channel is
  reachable, or, when the exception's `isBehindEarlierItems()` is true (the
  owner answered, the round stopped at an earlier item that waits for its
  next try, and the new request was not tried), that it will be sent after
  what was sent earlier. When 50 items are held and none
  is replaced, the call fails and nothing is kept.

An announcement counts as delivered only on `ok=true` (up to 3.0.17 the
member ignored the answer).

**What is stored.** One row per channel in the encrypted settings,
`zerion-channels-outbox:<channelId hex>`, holding for each item its kind,
target post, text (comment body, emoji, name, post body or new text), a
post's lifetime, the wanted comment setting, a local id (a comment's
`commentId`), the time it was kept, the tip of the chain held when an
editor's post was kept, the end of the lifetime of the post a deletion
targets (when it has one), whether it was ever sent without an answer, its
count of failed tries, the time of its next try, once it is not sent why,
and for an item not sent for lost access a hash of the capability the
refused request was proven with. No signature, timestamp, capability proof or request bytes are
stored: each attempt signs again with the current hour and a fresh proof,
so an item kept for days still falls inside the owner's 48-hour window
(section 5, Freshness).

**When it is sent.** Waiting items are sent strictly in the order kept,
after every successful pull of the channel (the background poll, each
refresh while the feed is open, and joining), before a new request as
above, and on a retry. In a private channel where that pull fails although
the owner's onion answered, waiting items are still sent before a new
request or on a retry, so they end as no access instead of waiting. For each item in turn:

- an item whose next try is still ahead stops the round; later items wait
  behind it and never overtake it. A next try more than an hour ahead, which
  only a clock set back can cause, counts as due;
- a deletion needs only the post's number, so it is sent even when the
  member no longer holds the post (the owner checks that the post is the
  editor's); it is dropped without being sent, and not counted as sent,
  only once the post's lifetime is over, the member holds neither the post
  nor the owner's deletion mark for it, and so the post is gone for
  everyone; a deletion of a post without a lifetime is never dropped this
  way;
- an item the pulled state already shows is done without being sent
  again: a comment by its `commentId` and the member key; a reaction, edit
  or comment setting by the state the pull brought; a deletion by the
  owner-signed deletion mark of the post; an
  editor's post that was sent at least once without an answer by its
  editor key and text among the posts after the tip held when it was kept,
  where that tip is moved past each earlier post of the same outbox once
  that one is delivered, so two posts with the same text are both sent;
- accepted: done;
- refused: marked not sent and not tried again on its own; for a comment
  sent before without an answer the device first asks for the whole comment
  set again, and if the owner holds the comment it counts as delivered
  (the owner refuses a second copy of a held `commentId` that differs in
  hour or signature);
- no access: marked not sent with that reason, and so is every other
  waiting item of the channel except one being deleted, without being
  sent; the round ends there, and a new request waiting for it fails with
  `ChannelNoAccessException` without being sent, so losing access costs,
  besides the pull that starts the round, one item's request, one pull and
  one confirmation request. Every such item is stamped with a hash of the
  capability the refused request was proven with, also when the member
  takes a renewed link while that round finishes. When the capability the
  member holds for the channel differs from the stamp (it is approved again
  or takes a renewed link), the item is waiting again, with no failed tries
  and its first kept time, so the 7-day limit still counts from then. An
  item without a stamp (kept by an earlier 3.0.18 development build) is
  stamped with the capability held when it is first read, and comes back
  only after a later change;
- a reaction that would have to be signed more than 2 hours ahead waits
  until the hour it can be signed, without counting as a failed try, and
  stops the round;
- no answer, or `STALE` three times in a row for an editor's post or
  change, is a failed try: the item waits 1, 2, 4, 8 and 16 minutes after
  the first five and stops the round; the sixth failed try marks it not
  sent, after which the round goes on with later items.

**Retry and delete.** The user can retry a not-sent item, which puts it at
the end of the queue with a new 7-day limit and no failed tries and sends
it, after any items waiting before it, if the channel answers; or delete
it. A delete waits for a send in progress and reports one of three
results (`deleteQueuedItem`): `DELETED` for an item removed before it was
sent (a send in progress skips it, so it is never sent), `SENT` for an item
that this device sent or found delivered (the last 512 such items while
the app runs), and `GONE` for an item no longer held for another reason
(deleted already, replaced, removed after a refusal of the new request
that replaced it, a deletion dropped as above, or removed after 7 days); the
apps tell the user only about `SENT`.

**Limits.** An item still waiting 7 days after it was kept is marked not
sent and not sent. A not-sent item is removed 7 days after it was marked.
At most 50 items are held per channel. Leaving or deleting the channel
removes its outbox. Items reach the owner only while the owner's device is
online and its onion answers; there is no other path, and while pulls fail
nothing is sent in the background, so a waiting item then changes only when
the member sends or retries something in the channel or after 7 days. An
item that was sent once without an answer may have reached the owner even
if it is later deleted or marked not sent. Items sent right after a pull
arrive close in time to it, which the owner's device can observe, as it
can when a member comments while reading. The stored text is what the
member typed; it stays in the encrypted database like the device's own
copies of comments and posts until it is sent, refused, deleted or
expired.

### Times shown on the device (no wire change)

Every time a channel item carries is a whole hour: the `timestampHourMs` of
a post, the `ts` of a comment or reaction and the hour an application was
made are floored to the hour (a multiple of an hour in UTC) by the device
that makes them, so no item that leaves a device carries the minute, and
the owner's device and every reader keep only the hour. The author's own
device keeps the exact time of an item only while it waits to be sent: the
member outbox (`ChannelOutboxStore`) and the list of items being sent keep
when the item was made until it is sent, refused, deleted or expired. From
3.0.18 the Android app never shows a channel time as if it knew the minute
(`HourTimes`, used by `UiUtils.formatChannelHour`):

- An item of today reads "around 10:00": the locale's clock for the start
  of the hour, which a 12-hour locale writes without minutes ("around
  10 AM"). An item of another day reads "Oct 17, around 10:00", with the
  year when it is not the current one. This holds for feed cards,
  comments, Message info and the times that tell two commenters of the
  same name apart. The channel row of the home list says "around 10:00"
  for today and, like chat rows, the weekday or the date for older items
  (the day alone for the midnight hour).
- An access request reads "Requested Oct 18, around 10:00".
- A post or comment that is being sent, waits in the member outbox or was
  not sent shows the hour its own local time falls in, worded the same, so
  its time does not change when the stored item takes its place. It changes
  only when the item is signed in a later hour, for example after waiting
  in the outbox.
- In a time zone whose offset is not a whole number of hours the kept hour
  starts at the half or quarter hour and reads, for example, "around
  10:30". The day shown is the day that hour starts on, which can be the
  day before: a comment written at 00:10 in India (UTC+5:30) is kept under
  18:00 UTC and reads "Oct 17, around 11:30 PM" on Oct 18.

The wording uses two strings, `channels_time_around` ("around %1$s") and
`channels_time_day_around` ("%1$s, around %2$s"). Up to 3.0.17 a comment
written at 10:48 showed "10:48" while it was sent and "10:00" once stored,
cards and Message info showed "10:00", an item from late the previous
evening showed no day until 24 hours had passed, and an access request
showed its date with "10:00", which suggested a minute the channel does
not keep.

## 6. Public vs private channels; editor delegations

### Public channels

`joinCapability == null`, `contentKey == null`. Post bodies are plaintext
inside the Tor circuit; attachment blobs are encrypted, but their keys travel
unwrapped in the post. Anyone with the invite link (channelId + publisher
Ed25519 + onion) can pull every post the publisher still holds, 100 per
request. No capability proof.

### Private channels - open invite link

`joinCapability != null` (32 bytes, `JOIN_CAPABILITY_BYTES`),
`contentKey != null` (32 bytes, `CONTENT_KEY_BYTES`). The capability appears
in the invite link only when the channel is **not** approval-gated
(`formatInviteLink` adds the `k=` param only when `!publicChannel &&
joinCapability != null && !requiresApproval`). Holding the capability lets a
subscriber answer the MAC challenge and unwrap the `contentKeyEnvelope` to
decrypt post bodies and attachment keys. `rotateJoinCapability` replaces the
capability and the content key and moves the channel to a fresh onion,
removing the old one at once: every existing subscriber is cut off until it
gets a new link, holders of the old link can no longer even reach the
device, and posts from before the rotation stay under the old key, which is
never sent to new holders. The owner's device keeps every earlier content
key (`ChannelStore.getRetiredContentKeys`) and still reads and serves those
posts; a member that joins with the new link stores them as posts it cannot
read (section 4, Storage), which are never counted or announced. A member
that already holds the channel takes the new link without leaving and keeps
the key it held (Rejoining with a renewed link, below), so it still reads
the posts it holds and those made under that key.

### Private channels - request → owner-approve

When `requiresApproval` is set, the capability is **not** in the link
(`p=1` flag instead). The join handshake (all over the publisher onion;
from 3.0.18 an applicant first has the onion confirmed by the owner's
device and sends steps 1 and 3 only to the onion the confirmation names,
as "Where a member's requests go" under "The one architectural fact"
describes; an applicant from 3.0.18 that has not taken the owner's signed
manifest, which is every new applicant, therefore cannot apply to an owner
up to 3.0.17, and one whose
application is still pending learns no decision from such an owner, until
the owner updates):

1. **Apply** - `WIRE_TYPE_APPLY_TO_JOIN`. Keys: `type`, `v`, `channelId`,
   `name`, `ts`, `ed`, `ml` (the applicant's member key of the channel,
   section 5), `eph` (an ephemeral **hybrid agreement** public key,
   `crypto.generateHybridAgreementKeyPair`), `sig`. Signed-input
   `applicationSignedInput` = `channelId || int32 nameLen || UTF-8 name ||
   int64 ts || int32 ephLen || eph`, label `SIGNING_LABEL_APPLICATION`.
   Response `WIRE_TYPE_APPLY_ACK = {type, ok}`. The publisher stores a
   PENDING `ChannelApplication` (`MAX_PENDING_APPLICATIONS = 256`). For an
   applicant it already holds (same `ed`), a repeated application (same
   `eph`) is acknowledged and changes nothing; from 3.0.18 one with another
   `eph` and an older `ts` (a late copy, or a device that left and applied
   again before the hour of the application held) is refused (`ok = false`)
   and changes nothing, so the applicant is told it was not confirmed and
   can apply again later, where up to 3.0.17 it was acknowledged without
   being kept. One with a new
   `eph` and a `ts` no older than the held one (the applicant left and
   applied again) replaces it and waits for approval again. From 3.0.18
   the publisher notes in each approval a hash of the capability it wrapped
   (a local field, never sent); when the held application was approved
   under the capability the channel uses now and the key is not banned, the
   new one is approved at once instead: the publisher wraps that same
   capability to the new `eph` as in step 2 and keeps the key approved, so
   applying again grants nothing the member does not already hold. Renewing
   the link (`rotateJoinCapability`) revokes every approval: an approval
   made under an earlier capability does not count, and the new
   application waits for approval like a new applicant's. Such an approval
   is not answered as approved (step 4), is not listed among the channel's
   applications and does not make its key known (section 5, Retention).
   An approval made before 3.0.18 notes no capability. It stays listed
   and known as up to 3.0.17, but it is never approved again at once: a
   new application from its key (another `eph`) waits for approval with
   nothing wrapped, and a check for it is answered only with what was
   stored when it was approved (step 4). The first renewal from 3.0.18 on
   notes on each such approval the capability that renewal replaces, in
   the same write as the renewal, so from then on it is revoked like any
   other approval made before that renewal. A banned key is refused as before. A DENIED application is
   always replaced. A repeated
   application the publisher already holds is acknowledged even when its
   write budget for applications is spent, since it writes nothing.
   The applicant stores its application with the ephemeral private key
   before sending it, marked as not yet confirmed: an unconfirmed
   application is never polled and reads as not applied, and the channel
   screen offers to apply. `ok = true` confirms it; `ok = false` removes
   it, and the applicant is told it was not confirmed. With no answer or an
   unreadable one it stays unconfirmed and the applicant is told the same.
   From the channel screen, an application already confirmed and still
   pending is not sent again; through an approval link (Rejoining with a
   renewed link, below) a new one always is, replacing it. Applying again
   under the same name while the application is unconfirmed sends the
   identical application (same hour and ephemeral key), which a publisher from this version
   acknowledges if it stored the first copy and stores otherwise. Every
   other new application gets a new key and an hour at least one hour after
   the latest hour this device has sent for the channel, which it remembers
   through refusals and forgotten applications, so a late copy of an
   earlier application is older and changes nothing. Leaving the channel
   removes that hour with everything else kept for the channel, so an
   application made right after leaving and joining again can be ignored
   by the owner until the hour of the last one has passed. A refused copy
   never removes an application another attempt has confirmed. Success is
   reported only when the application is still held confirmed (or already
   decided) afterwards, and an application that was not answered, was
   refused or could not reach the owner is told apart from one that could
   not be prepared on this device. The ephemeral private key is kept until the application is
   approved, refused, replaced or the channel is left.
2. **Owner approves** (`approveApplication`, publisher-only): the publisher
   calls `crypto.hybridEncapsulate(applicantEphemeralPubKey)`, which
   encapsulates only to the ML-KEM-768 half of the key (the X25519 half is
   unused), derives a wrap key
   `deriveKey(APPROVAL_WRAP_LABEL, SecretKey(ML-KEM shared secret), channelId)`,
   and AES-GCM wraps the channel **capability** into an envelope. KEM ciphertext +
   envelope are stored on the application. (Deny just marks it DENIED.)
3. **Poll** - `WIRE_TYPE_CHECK_APPROVAL`. Keys: `type`, `v`, `channelId`,
   `ts`, `ed`, `ml`, `sig`, signed with the key the application was made
   with. Signed-input `checkApprovalSignedInput = channelId ||
   int64 ts`, label `SIGNING_LABEL_CHECK_APPROVAL`. Throttled to once per
   30s per channel (`pollApprovalStatusIfPending`).
4. **Approval response** - `WIRE_TYPE_APPROVAL_RESPONSE`. Keys: `type`,
   `status` (`"PENDING"` / `"APPROVED"` / `"DENIED"` / `"UNKNOWN"`),
   `kemCt` (raw, optional), `envelope` (raw, optional). `"UNKNOWN"` means
   the publisher holds no application for that key (it was lost, or the
   channel was set up again); it is sent only when the poll carries
   `v >= PROTOCOL_VERSION_POST_CHANGES` (4), and older applicants get
   `"DENIED"` as before. From 3.0.18 the publisher answers `"APPROVED"`,
   always with the `kemCt` and `envelope` it stored when it approved, only
   for an approval made under the capability the channel uses now or for
   one made before 3.0.18 that has both stored (they wrap the capability
   it was approved under, never a later one); for any other approval, one
   a renewal revoked included, it answers as for an application it does
   not hold. The applicant then forgets its application, so it
   reads as not applied and can apply again. A publisher that cannot read
   its records answers `"PENDING"`, never `"DENIED"`. On `"APPROVED"` the applicant
   decapsulates `kemCt` with its ephemeral key pair
   (`crypto.hybridDecapsulate(ownEphKeyPair, kemCt)`), which yields the same
   ML-KEM-768 shared secret the publisher wrapped with, derives the same wrap
   key and unwraps the capability. An applicant that holds no capability,
   or the same one, stores it. From 3.0.18 an applicant that holds another
   capability keeps it and everything it holds: it first pulls, from its
   chain tip, the onion the check went to with the new capability, and
   takes the capability, the content key and the manifest only from a
   reply whose manifest verifies under the channel's key with that
   capability, is newer than the one it holds and whose content key opens
   to the hash that manifest signs (keeping the earlier key, as below),
   and only then marks its application approved. While that onion cannot
   be reached, or answers with nothing (which is also what an owner's
   device sends while it sheds load), its application stays waiting and
   the next check tries again; when it answers with anything else that
   brings no such manifest, the approval cannot be verified against the
   channel as it is now, so the applicant forgets the application and can
   apply again. Empty answers are borne for a day only: the applicant
   keeps the time of the first one in its application record, in the same
   write (never sent), so a restart neither resets nor extends it, and once
   an empty answer comes at least 24 hours after that time with no
   verified answer or refusal in between, it forgets the application as
   on any other answer (an owner that kept answering `"APPROVED"` for a
   capability it has since replaced would otherwise be asked again on
   every check). A clock set back before that time starts the run again
   once; after that the run keeps its start. The run ends when a verified
   answer or a refusal replaces or removes the application record, or when
   a candidate's confirmed onion answers with something other than an empty
   reply; a non-empty answer that leaves the record unchanged does not end
   it. A new application, or a renamed one with a new ephemeral key,
   starts without one. A ciphertext or
   envelope that
   does not open, or a stored ephemeral key that cannot be read or is
   missing while the applicant has no capability, stores nothing and makes
   the applicant forget the
   application, so it reads as not applied and can apply again. Applicants on releases
   up to 3.0.14 used a derivation that never matched the publisher's key, so
   on those releases an approved application stays PENDING.

An application approved again that brings a capability other than the one
the member holds (it applied again after a renewed link, below) also keeps
the content key the member held as an earlier key, so the next pull, which
brings the new key, leaves the posts it holds readable.

### Rejoining with a renewed link (from 3.0.18)

Opening an invite link of a channel the device already holds never
contacts the link's address by itself. Once the device does contact it, it
keeps contacting it exactly as a device that joined with that link would:
the same requests, on the same occasions and the same schedule, until the
same end. Nothing new goes on the wire and the owner's device does nothing
different. Up to 3.0.17 such a link was ignored, so a member cut off by a
renewed link had to leave, losing everything it held, and join again.
Development builds of 3.0.18 asked the link's address at once, later sent
it a single request and then nothing more, or kept a link's onion in the
application record; none of them was released.

1. **Answered without asking anyone.** When the device is the channel's
   owner, any link with the channel's id is treated as one the device
   already holds, whatever key, onion or capability it carries: nothing
   is sent, nothing is kept from the link and nothing changes. The join
   dialog says the device is already subscribed, as up to 3.0.17, and a
   link opened from a chat or another app opens the channel (up to 3.0.17
   an approval link of the owner's own channel showed the apply form,
   which the owner's device then refused). When the link names a publisher
   Ed25519 key other than the one the device holds (or an ML-DSA-65 key
   other than the held one), or when it carries no v3 onion, nothing is
   sent, nothing changes and the app says the link did not bring back
   access.
2. **Check the access held (`rejoinChannel`).** Only once the user chooses
   to join from the link, and only when the device has taken a manifest or
   holds a capability, it pulls as usual (`refreshChannel`, from its chain
   tip) from the onion it holds, keeping what that pull brings; that onion
   is the one its newest manifest names, or a fallback such a manifest
   named, or, without a manifest, the onion the device already pulls on its
   own schedule. If the pull succeeds the access works and the link
   changes nothing (the app opens the channel or says the member is already
   subscribed). For an approval link, an applicant whose application is
   still pending is told nothing new and nothing is sent. When the link
   names the onion the device already uses and carries no other
   capability (an approval link carries none), the link brings nothing
   new: when that onion could not be reached the app says to try again
   later, and when it refused, for a link that is not an approval link,
   the app says the link did not bring back access. When the pull brings
   a tombstone that verifies (section 8), the channel is removed as by any
   pull and the app says the owner deleted it. Otherwise the
   app offers to try the link or, for an approval link (`p=1`, no
   capability), to apply again; when the device holds a capability and
   its onion could not be reached, the offer says so. Applying again
   changes nothing the device holds until a verified reply replaces it.
   Nothing is sent to the link's address before the user accepts.
3. **Try the link (`rejoinChannelThroughLink`, an open or public link).**
   If the device took a manifest, it first sends an ordinary pull (section
   1) from its chain tip, proving the link's capability (or the one it
   holds when the link carries none), to the onion that manifest names; if
   that onion answers, the link's address is never contacted and the reply
   is taken as below (a tombstone that verifies removes the channel, and
   the app says the owner deleted it). Otherwise the device keeps the
   link's address as a candidate of the channel (`ChannelLinkCandidates`,
   `zerion-channels-link-candidates`, at most 4 per channel, with the
   link's capability or none; while 4 are kept a further link is neither
   kept nor contacted, and the app says so), returns before sending it
   anything, and
   from then on contacts it as a device that joined with that link
   contacts the address of its link: the same pull (`sinceSeqNum = -1`, no
   reaction or comment cursors, a proof only of the link's own
   capability, none for a public link), first through the same first pull
   a new channel gets once its join returns (`bootstrapChannel`, run while
   the channel's screen opens and pulls, so the two may overlap exactly as
   a new channel's do), again when the channel's screen opens (which, as
   for a new channel, also restarts the backoff) and on its refresh ticks
   while the screen stays open, none of which waits for a pull still
   running, as for a new channel. Each tick skips the candidate once its
   own schedule rests, as a new channel's screen skips a channel that
   rests, whether or not the channel the device holds rests, and pulls
   the channel the device holds only when that one does not rest. The
   candidate is also contacted on its own pull schedule (due at once, then 5 s doubling on each
   failure up to 15 minutes, each wait drawn between half and one and a
   half of it), which, as a new channel's, skips the address while a pull
   of it is running and rests once 90 days have passed since the address
   last answered or, when it never answered, since its first failure. As
   for a new channel, a tombstone that does not verify (including one a
   device cannot check because it holds no ML-DSA-65 key of the channel)
   counts as an answer for the backoff and those 90 days only, and is
   never applied. The held capability, the member key and anything else the
   device holds are never sent to the link's address. The candidate is
   never the channel's onion. It ends
   only when a reply verifies (below), when the channel is left, or, for
   its schedule, after those 90 days; a device that joined with the link
   would keep asking just as long. A reply verifies when its manifest
   verifies under the channel's key with the candidate's capability (a
   private manifest's signature covers the capability) and, in a private
   channel, its `contentKeyEnvelope` opens under that capability to a key
   whose hash is the manifest's `contentKeyHash`. If that manifest is newer
   than the newest the device took, the device stores it, the capability
   and the key, keeps the content key it held as an earlier key (section
   4, Storage) and the onion it used until then among its remembered
   onions (section 9), and reads posts it held but could not read; its
   current onion is the one the manifest names. Either way the candidate
   ends there, as a new member's contact with its link's address ends at
   its first verified reply; a reply that verifies but is not newer
   changes nothing else. A reply from the onion the device's manifest
   names also gives its posts, reactions and comments; one from the link's
   address gives only its manifest and key, and the next pull fetches the
   posts from the chain tip. Posts, reactions, comments and settings the
   device holds stay. Any other reply, an empty one included, changes
   nothing, so a link with a wrong capability never replaces the
   capability held. The app says the access was updated when the onion
   the device's manifest names answered, and otherwise that the link will
   keep being tried in the background.
4. **Apply again through an approval link (`applyToJoin` with the link).**
   The application goes to the onion the device holds (the one its
   manifest names, or for an applicant without a manifest the onion the
   owner's device confirms), as any application does, and the checks for
   its approval go there as usual. Only when that onion cannot be reached
   (or, without a manifest, gives no readable confirmation) does the device
   keep the link's address as a candidate without a capability and do what
   a new applicant does: ask it to confirm itself
   (`WIRE_TYPE_CONFIRM_ONION`), take the confirmation as a new applicant
   takes it (moving the candidate to the onion it names, checked as in
   "Where a member's requests go") and send the application there. From
   then on the candidate is contacted as a new applicant's channel is: on
   the occasions and schedule of step 3, at most every 30 seconds a
   confirmation and a check for approval while that application waits,
   then the pull without a capability; once approved, the pull with the
   approved capability, whose verified reply ends the candidate as in step
   3 and only then replaces what the device holds. Only a reply that
   brings a newer manifest under the capability that this application's
   own approval wrapped (opened with its ephemeral key) marks the
   application approved; a verified reply that is not newer, or one under
   any other capability, ends the candidate and leaves the application as
   it is, and a reply that is not empty and does not verify under the
   approved capability makes the device forget the application, so it can
   apply again; an empty reply counts as a failed pull and joins the same
   run of empty answers as the check above (step 4 of the approval
   handshake), so after 24 hours of them it forgets the application in
   the same way. When
   the owner's device answers a check that it approved the key while this
   device holds no waiting application (an approval already taken), the
   candidate pulls with the capability the device holds, which therefore
   goes only to the onion the owner's device confirmed for that check,
   never to the link's address. If the confirmation
   fails, the candidate stays without an application, as a new applicant
   whose application failed keeps its channel and goes on pulling. The
   application record keeps no onion. The application and the checks for
   its approval carry the member key and go only to an onion the owner's
   device confirmed with its signature (a relay or impostor at the link's
   address that answers the confirmation for another onion makes the
   device dial that onion directly), so the link's address sees only the
   confirmation question, exactly as from a new applicant. After a renewal
   the application waits for the owner's approval like a new applicant's;
   only the owner's device, which receives the member key, can tell it
   comes from a member approved before.

Limits: an owner up to 3.0.17 answers no confirmation, so applying again
through an approval link of such an owner fails when the onion the device
holds is dark. Renewing the link revokes every approval, so a member
approved before it needs a new approval from the owner (an approval made
before 3.0.18 counts until the first renewal from 3.0.18 on, and is never
renewed at once); the owner's device answers a check for a revoked
approval as for one it does not hold, so
the member forgets it, and applying again through the new link always
sends a new application. Posts
made under a content key the member never held stay unreadable. Once a
candidate's reply verified and the device took that manifest, the device
pulls the onion the manifest names as any member does, from its chain tip;
when that onion is the link's own address, which only the owner's device
can then answer, the owner's device can tell from the chain tip that the
device already held posts.

### Editor delegations - `WIRE_TYPE_DELEGATION`

The publisher can issue delegation certificates, up to
`MAX_ACTIVE_DELEGATIONS_PER_CHANNEL = 8`, naming an editor's member key of
the channel (the owner picks an announced subscriber by name, which opens
the member sheet where "Make editor" issues it and the Safety code tells
two people with the same name apart, or pastes the key the editor copied
under Advanced in the channel settings; "Remove editor" in the member sheet
revokes it). An editor posts text (no attachments,
since blobs live on the publisher): it builds a format 2 post on the chain
tip it holds, signs it with its member key and submits it with
`WIRE_TYPE_SUBMIT_POST` (keys `type`, `v`, `channelId`, `post` = the wire
post, plus `nonce`, `hmac`, `hmac2` in private channels). The publisher adds
it to the chain if it is in format 2, has no attachments, carries this hour
or the one before, does not have the shape of a deletion marker (section
4, Deletes), comes from a key that is not banned, follows the publisher's
tip directly and verifies under an active, unrevoked certificate covering
its hour, and charges it to the editor's write allowance; it answers `OK`,
`STALE` with its tip (the editor pulls and tries again, up to 3 times) or
`REFUSED`. An editor may add at most 30 posts an hour and 150 a day
(`MAX_EDITOR_POSTS_PER_HOUR`, `MAX_EDITOR_POSTS_PER_DAY`, counted per
channel in `zerion-channels-editor-quota:<channelId hex>` and written in
the transaction that stores the post, so a restart does not reset them),
and the posts of all editors held for a channel stay within 64 MiB
(`MAX_EDITOR_POST_BYTES_PER_CHANNEL`), so no editor can flush the channel's
history from subscribers' storage windows in less than weeks or grow the
owner's storage without bound. Subscribers receive the
post in their next pull and verify it as below. A publisher up to 3.0.14
does not know the type and answers with an empty reply, which the editor
reports as a refusal.

**Change requests (protocol version 4).** An editor deletes, edits or sets
comments on one of its own posts with `WIRE_TYPE_CHANGE_POST` (keys `type`,
`v`, `channelId`, `seq` = the target post, `op` = `D` delete, `E` edit or
`C` comments, `base` (long), `on` (boolean), `body` (string), `ts`, `ed`,
`ml`, `sig`, plus `nonce`, `hmac`, `hmac2` in private channels), signed with
its member key over `postChangeSignedInput` = `channelId || int64 seq ||
int32 opLen || UTF-8 op || int64 base || on (1) || int32 bodyLen || UTF-8
body || int64 ts`, label `SIGNING_LABEL_POST_CHANGE =
"org.zerionproject/CHANNEL_POST_CHANGE"`. For an edit `body` is the new text
and `base` the sequence number of the newest edit mark of the post the
editor holds, or the post's own number when it has none; for `C`, `on` is the
wanted setting; otherwise `body` is empty, `base` is -1 and `on` false. The
owner's device (`handleChangePost`) answers `REFUSED` unless the request
names this channel, has the key and signature sizes of a comment, carries
this hour or the one before, comes from a key that is not banned and holds
an active, unrevoked certificate covering this hour whose ML-DSA-65 key is
`ml`, the signature verifies, the target is held, shown, not deleted and
signed by that same editor key, an edit is within the window and fits a
post, and the editor's write allowance and its 30 an hour and 150 a day
(shared with its posts) are not spent; it answers `STALE` when an edit's
`base` is not the newest version it holds, and `OK` after it has applied the
change. It applies a change as if the owner made it: a deletion or an edit
is published as a marker signed by the channel's key (section 4), so
subscribers judge it like any owner marker and those from 3.0.15 apply
deletions; a setting change goes into the comment setting. The editor pulls
after `OK` and after `STALE`, and tries an edit up to 3 times. Subscribers
therefore cannot tell an editor's own change from the owner's; the rule that
an editor changes only its own posts is the owner's device's, which is also
the only device that could sign any change. The request travels inside the
Tor circuit to the owner's onion, so only the owner's device sees it; a
replayed edit is `STALE` once the post has a newer version, a replayed
deletion changes nothing, and a replayed setting change is refused once the
hour after the request's hour has passed. A publisher up to 3.0.17 does not know the
type and answers with an empty reply, which the editor reports as a refusal.
From 3.0.18 a post or change request the owner's onion does not answer at
all waits in the member outbox (section 5) and is built and signed again
when it is sent.

Each `ChannelDelegationCert`
(`certToWire`) has keys: `type`, `channelId`, `delegateeEd25519`,
`delegateeMlDsa`, `validFromHourMs`, `validUntilHourMs` (0 = unbounded),
`delegationSeq`, `signature`. Certs are carried inside the manifest
(`activeDelegations`), revoked via `revokedDelegationSeqs`.

Delegation signed-input (`delegationSignedInput`): `channelId ||
delegateeEd25519 || delegateeMlDsa || int64 validFrom || int64 validUntil ||
int64 delegationSeq`, label `SIGNING_LABEL_DELEGATION =
"org.zerionproject/CHANNEL_DELEGATION"`, signed by the **publisher**
hybrid key. A delegate-signed post (`post.signedByDelegate()`) is shown
only if the cert exists in `activeDelegations` (or, for a post published
before a renewal, in the local retired list), is not in
`revokedDelegationSeqs`, covers `post.timestampHourMs`
(`coversTimestamp`), and the cert's own signature verifies against the
publisher key (`ChannelPostValidator.checkDelegationIfApplicable`).

A delegate key may hold several certificates over time (renewal issues a
new `delegationSeq` for the same key). A post is judged under a certificate
held for its key, active or retired, whose window covers
`post.timestampHourMs`: one that is not revoked if there is one, the
earliest issued among them, otherwise the earliest issued revoked one. A
certificate's window starts at the hour it was issued, so a key the owner
grants again after a revocation posts under the new grant from that hour on,
exactly as the compose bar it is shown says, while a post made before the
new grant is judged under the revoked one alone and stays withheld; a later
certificate never invalidates a post made under an earlier unrevoked one. If
no held certificate covers the post, the newest one is used and the result
is `DELEGATION_OUT_OF_WINDOW`.

Revocation does not break the chain. `DELEGATION_REVOKED` is returned only
when every other check, including the post signature under the revoked
certificate, has passed; such a post is kept withheld (section 4,
skip-known rule). When a merged manifest adds a sequence number to
`revokedDelegationSeqs`, the subscriber judges its stored delegate-signed
posts again under the merged state and withholds those now reported as
revoked; the unread count is recomputed (section 4, Storage, Unread
posts), and a withheld post is never counted or announced, also when an
edit mark naming it arrives after it was withheld. The publisher applies
the same step to its own copy when it
revokes. A withheld post is never shown again. Withheld posts are stored
with an extra local key `withheld` (boolean, absent means false) that is
never sent on the wire.

### Invite link format (`ChannelCodec.formatInviteLink` / `parseInviteLink`)

```
zerion://channel/<base32 channelId>/<base32 publisherEd25519>
        [?k=<base32 joinCapability>]   (private, non-approval only)
        [&o=<onion>]                    (lowercase v3, regex [a-z2-7]{56})
        [&p=1]                          (requires approval)
```
Scheme `zerion`, host `channel`, params `k` / `o` / `p`
(`INVITE_LINK_*_PARAM`). Links are always written with the lowercase
scheme. Readers match the scheme without regard to the case of the
letters A to Z (`Zerion://`, `ZERION://` and every other mix). On
Android any other character in the scheme, including look-alike and
invisible characters, makes the link invalid; iOS first removes
whitespace, soft hyphens and some dash characters from the whole link.
On Android a channel link is rewritten to `zerion://` by
`ZerionLinks.normalizeScheme` before it is parsed or routed, and a
contact link is matched by `HandshakeLinkConstants.LINK_REGEX` and kept
with the lowercase scheme. Normalizing the scheme leaves everything
after `://` untouched, and the rest of the link is parsed exactly as
before: on Android the host must still be `channel` in lowercase (iOS
also accepts any case of the host). The Android system matches link
schemes case-sensitively, so the Android link entry registers all 64
case variants of `zerion`.

An `m=` param (`INVITE_LINK_MLDSA_PARAM`) is
reserved but not currently emitted; `parseInviteLink` leaves the publisher
ML-DSA key null and lets the subscriber learn it from the first signed
manifest. `INVITE_LINK_MAX_LENGTH = 4096`. A link with no `k` and no `p` is
treated as public.

## 7. Signing / crypto summary

- **Identity / authorship signatures** are hybrid **Ed25519 + ML-DSA-65**
  via `crypto.hybridSign` / `crypto.verifyHybridSignature` with
  per-purpose domain labels (`SIGNING_LABEL_*`). The publisher signs the
  manifest, posts, delegations, tombstone, and its own comments and
  reactions with the channel's own hybrid signature key (generated by
  `crypto.generateHybridSignatureKeyPair`, private key stored via
  `store.putPublisherPrivKey`). Members sign comments, reactions, announces,
  applications, approval checks and editor posts with the member key of the
  channel (section 5). User signatures **require** an ML-DSA component
  (classical-only is refused). From 3.0.18 the signer's ML-DSA key is
  bound per channel to its Ed25519 key and version 3 replies carry the
  owner's signature over those bindings (section 5, Author binding); up
  to 3.0.17 it was self-asserted per message.
- **Channel id binding**:
  `channelId = hash("org.zerionproject/CHANNEL_ID",
  HybridSignaturePublicKey(ed,mlDsa).getEncoded(), salt)`. Subscribers
  re-derive and reject any manifest that does not match.
- **At-rest / content encryption is AES-256-GCM** (`ChannelContentKey`,
  `AES/GCM/NoPadding`, 12-byte IV, 128-bit tag):
  - Post bodies in private channels, format 2: key = 32-byte channel
    content key, nonce `hash("…/CHANNEL_BODY_NONCE_V2", channelId, int64
    seqNum, salt)[0:12]`, AAD = `0x02 || channelId || int64 seqNum || salt`;
    the 16-byte salt is random per post, so no nonce repeats under a content
    key even if a sequence number were issued twice. Legacy format: nonce
    `hash("…/CHANNEL_BODY_NONCE", channelId, seqNum)[0:12]`, AAD =
    `channelId || int64 seqNum`. The wire body is base64 of the ciphertext.
  - Attachment blobs: per-attachment 32-byte key, random 12-byte nonce
    prefixed to the ciphertext, AAD = `channelId || int32 mimeLen ||
    UTF-8 mime || int64 size`. Blobs are content-addressed by
    `hash("…/CHANNEL_ATTACHMENT_BLOB", encryptedBlob)`.
  - Content-key wrap (for the pull-response envelope): wrap key =
    `deriveKey("…/CHANNEL_CONTENT_KEY_WRAP", SecretKey(capability),
    channelId, info="ZERION_CHANNEL_CONTENT_KEY_WRAP")`, AES-GCM with random
    IV. Per-attachment keys in private channels use the same construction
    keyed by the channel content key; public channels send the
    per-attachment key unwrapped.
  - Approval capability envelope: wrap key =
    `deriveKey(APPROVAL_WRAP_LABEL, SecretKey(ML-KEM-768 sharedSecret),
    channelId)`, AES-GCM with random IV (ML-KEM only, not hybrid). The
    applicant recovers the secret with `hybridDecapsulate` (section 6).
- **Onion confirmation** (from 3.0.18): the owner's device signs
  `channelId || int32 len || UTF-8 onion` with the channel's hybrid key
  under `SIGNING_LABEL_ONION_CONFIRMATION` (The one architectural fact,
  Where a member's requests go).
- **MAC challenge** uses `crypto.mac` (keyed BLAKE2b-256) under
  `"org.zerionproject/CHANNEL_HMAC_CHALLENGE"` keyed by the capability,
  over channelId and the client nonce.
- Channel confidentiality is classical (Tor and AES-GCM); only the ML-DSA-65
  signature halves and the approval wrap use post-quantum primitives, and
  channel traffic does not use the ZWF path.

## 8. Channel tombstone - `WIRE_TYPE_CHANNEL_TOMBSTONE`

When the publisher deletes a channel (`deleteChannel`, and from 3.0.18
`deleteChannelForEveryone`, the deletion the owner's app uses; it offers
`deleteChannel` only in the case described below), it stores a signed tombstone (`publishTombstone`) and deletes everything else it
keeps for the channel. Its onions, the current one and any still retiring,
then serve the tombstone in place of **any** response for 14 days
(`DELETED_CHANNEL_GRACE_DAYS`), also across restarts, after which the onions
are removed and their keys and the tombstone deleted. The onions answer
only while the owner's phone runs Zerion and is connected, so the owner's
app says, before the delete and when it is done, that for the next 14 days
subscribers' phones remove the channel when they check for posts while
this phone runs Zerion and is connected, and that a phone that does not
check in that time keeps its copy.
`deleteChannelForEveryone` signs the tombstone before it changes anything:
when it cannot sign (the channel's signing key is missing or cannot be
read, for example because it was stored with the wrong length), it removes
nothing, the channel stays served as before, and it reports the failure
(`ChannelDeleteNotSignedException`), so the owner is told that nothing was
removed; `deleteChannel` in that case removes the channel on this device
only. The owner's app offers that only right after a signed delete failed
for this reason, as an explicit "Remove from this phone only" choice that
says subscribers are not told, keep their copy and get no new posts, and
that again asks for DELETE and the account password; it is never offered
for any other failure or taken without that choice. The owner's app asks for
the word DELETE and then the account password, checked like a sign-in (the
same wrong-password limits and lockout), before `deleteChannelForEveryone`
runs. The app reports the channel as deleted only when `hasDeletionNotice`
shows that this device stores the signed notice; when the notice is stored
but a later step of the removal failed, it reports the channel as deleted
and says that it is fully removed from the phone at the next sign-in, when
`finishPendingRemovals` completes it. Keys
(`encodeTombstone`): `type`, `channelId`, `ts`, `sig`. Signed-input
`tombstoneSignedInput = channelId || int64 ts`, label
`SIGNING_LABEL_CHANNEL_TOMBSTONE`. A subscriber that pulls and sees a
tombstone verifies it against the pinned publisher key
(`applyTombstoneIfValid`) and, if valid, removes the channel locally. It
keeps the channel's name and the day it was removed, and nothing else (not
the channel id), for the notice in the channel list that the owner deleted
the channel (`zerion-channels-deleted-notices`, at most 32 names); the list
shows the notice until the user dismisses it or 30 calendar days (UTC)
have begun since that day, whichever comes first, and the name is deleted
then (an expired name is deleted when the list is next read or by the daily
purge, whichever runs first). Subscribers that do not connect within the
grace period keep their copy. Up to 3.0.17 they went on pulling the
removed onions every 30 to 90 s indefinitely; from 3.0.18 their failed
pulls back off to about one every 15 minutes and stop 90 days after the
last successful pull the device recorded (or after its first attempt, when
it recorded none), after which only opening the channel's feed tries again
(see the pull schedule above).

On the owner's device the tombstone, the onion record that now carries the
key of every onion the channel had (the current one included, whether or
not a server was bound for it) and a pending-removal mark are written in
one transaction before anything is removed, so the tombstone is served from
every onion subscribers know, by servers already bound or by the next onion
maintenance from the stored keys.

Removing a channel locally (leaving, a verified tombstone, or the owner's
delete) deletes the channel's state and keys, posts and their record,
attachment blobs, reactions, comments, their revision history and cursors,
announced subscribers, bans and trusted marks, applications, deleted-post
marks, editor post counts, remembered onions, the discussion setting, the
pull status and the unread count. The removal is marked as pending
(`zerion-channels-pending-removal`) before its first step and the mark is
cleared after its last, so a removal cut short by the process being killed
is finished at the next start instead of leaving rows nothing would ever
remove. The rows are deleted, not blanked, so no row naming the channel is
left; on the owner's device only the tombstone and the onion record remain
until the grace period ends, and on a subscriber that applied a tombstone
only the channel's name and the day of its removal remain, without the id,
until the notice that the owner deleted it is dismissed or 30 days pass.

## 9. Onion lifecycle

`ChannelOnionStore` keeps, per hosted channel, when the current onion is
next rotated and the retired onions that stay published until a given time.

- **Periodic rotation.** Every 28 to 36 days (random per channel) the
  publisher binds a fresh onion, makes it current with a manifest naming it
  (`manifestSeq` + 1) and keeps the old onion published for 30 days, still
  answering every request, so subscribers learn the new address from the
  next signed manifest they pull. Then the old onion is removed and its key
  deleted. Whoever only held the old address, such as an old invite link,
  can no longer reach the device after that; share a fresh link. An
  applicant whose application is still pending checks it at the address of
  its link once the owner's device confirmed that address, and reads no
  manifest, so an application left pending past the end of a migration
  window can no longer learn that it was approved; the applicant needs a
  fresh link.
- **Revocation.** Rotating a private channel's invite link moves the
  channel to a fresh onion and removes the old one at once with its key,
  together with every onion still retiring from earlier rotations. The old
  onions are removed first, so they are gone even while Tor is down; the
  fresh onion is then published at once or, if Tor is unavailable, as soon
  as it is back (the publisher retries every 3 minutes and when Tor becomes
  active). Subscribers holding the new capability learn the new address
  from the owner's next invite link or from a manifest; those without it
  cannot reach the channel any more until they use the new link, which
  from 3.0.18 a member can take without leaving the channel (section 6,
  Rejoining with a renewed link). Renewing also revokes every approval of
  an approval channel, those made before 3.0.18 included: the owner's
  device no longer answers, lists or counts such an approval as approved,
  and a member approved before it
  that applies with the new link waits for a new approval, as a new
  applicant does (section 6, Apply).
- **Failed periodic rotation.** When the fresh onion cannot be published,
  the current onion stays and the periodic rotation is tried again at the
  next check.
- **Atomicity.** The retiring onion's key is taken from the stored channel
  state, not from a server that happens to be bound, and the onion record
  that keeps it is written in the same transaction as the state that stops
  naming it; a retiring onion whose server is not bound is bound at once
  and after every restart. A crash at any point of a rotation or a deletion
  therefore leaves the old onion served.
- **Restore.** A publisher that finds its database was restored from a
  backup or copied (Hash chain, section 4) keeps every onion whose key it
  still holds published for another 30 days. A subscriber remembers the
  last 12 onions that manifests it took named
  (`zerion-channels-remembered-onions`; from 3.0.18 never the address of
  an invite link, which nothing signs, and such an address an earlier
  release kept gives only its manifest); once the current onion has not
  answered for 15 minutes it tries them in turn on every other pull, and
  follows the manifest the one that answers serves, taking posts from it
  only when that manifest names it (section The one architectural fact,
  Who answers a pull). Only the holder of an onion's key can serve it and
  everything served is verified under the channel's key, so trying an old
  address trusts no one new. A backup older than every onion the
  subscriber remembers (about a year) cannot be found this way; the owner
  shares a fresh link.
- **Deletion.** See section 8.
- Retired onions are rebound after a restart until their time, and the
  schedule is checked every 15 minutes.

Banning a subscriber does not rotate the onion: in a public channel anyone
with the link can read anyway, and in a private channel the owner rotates
the invite link ("Remove from channel" in the member sheet bans and renews
in one step; "Block" bans only) to cut a banned member off, which every
other subscriber then needs a new link for too.

## What this doc does NOT cover

- The Tor onion publishing plumbing (`TorChannelTransport.bindServer`,
  `TorPluginOnionPublisher`, `OnionPublisher`) beyond what sections 9 and
  Transport framing say.
- The full on-disk store schemas. Channel state lives in settings
  namespaces of the SQLCipher-backed SettingsManager (for example
  `zerion-channels-state`, `zerion-channels-comments`,
  `zerion-channels-reactions`, `zerion-channels-item-sync:<hex>`,
  `zerion-channels-onions`,
  `zerion-channels-remembered-onions`, `zerion-channels-bans`,
  `zerion-channels-trusted`, `zerion-channels-editor-quota:<hex>`,
  `zerion-channels-pending-removal`, `zerion-channels-instance`,
  `zerion-channels-author-pins:<hex>`, `zerion-channels-discussions`,
  `zerion-channels-discussion-exceptions`; posts as in
  section 4, Storage); attachment blobs are encrypted files under
  `channel-blobs/`. These are local state, not wire format.

## Open TODOs / uncertainties for iOS parity

- **`neighbourHints`** (`WIRE_TYPE_SUBSCRIPTION_HINT`) is plumbed but Android
  always sends an empty list. Unclear if it will be used; iOS can ignore.
- **`WIRE_TYPE_POST`** is defined as a constant but posts are never sent as a
  standalone top-level frame - they only appear inside the pull response
  `posts` list. The tag may be reserved for a future standalone post push.
- **`discussionsEnabled` in the manifest** is gated off
  (`DISCUSSIONS_IN_MANIFEST = false`); the comment setting travels in `ds`
  of version 4 and 5 replies instead (section 5, Comment settings). iOS must
  default the field to `true` when absent and not include it in the
  manifest signed-input while the flag is false.
- **Versioning.** `MAJOR_VERSION`/`MINOR_VERSION` (0/1) are not on the wire.
  Since 3.0.15 every request carries `v` (2; 5 from 3.0.18, section
  Protocol version), a reply
  carrying deltas carries `v`, and a post carries its format in `pv`; the
  `*_V1` type strings are unchanged. iOS implements none of this protocol:
  its channel code (`ChannelPullProtocol.swift`) sends list-based frames
  that an Android publisher, which reads dictionaries, refuses, so iOS and
  Android channels do not interoperate in any version; an iOS channel
  implementation of this wire format would start from this document.
- **ML-DSA public key in invite links** (`m=` param) is reserved but unused;
  the subscriber currently learns the publisher ML-DSA key from the first
  signed manifest, and `mergeManifestIntoLocal` only hard-pins ML-DSA if a
  local copy already exists.
