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

Consequences:

- **If the publisher is offline, no new posts propagate.** A subscriber's
  refresh simply fails to connect; nothing else carries the posts.
- Each subscription is pulled on its own schedule. A channel just joined is
  pulled at once; subscriptions held when the app starts are first pulled at
  random times within 15 s. After each pull the subscription's interval is
  5 s if a pull of that channel in the last 120 s brought new posts or a
  changed reaction or comment set, otherwise it grows by 5 s up to 60 s, and
  the next pull falls at a uniformly random time between half and one and a
  half intervals later. No two subscriptions share a schedule, so the
  publishers of two channels cannot link one subscriber by the timing of its
  pulls. An open feed also refreshes its own channel.
- The publisher persists each channel's onion key and republishes the same
  onion at startup, on Tor `TransportActiveEvent` and
  `B4OwnRotationCompletedEvent` (for channels not yet bound) and on periodic
  retries. Every 28 to 35 days it moves the channel to a fresh onion, signs a
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
  publisher restored from an older backup is found again (section 9).
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
| `ZERION_CHANNEL_DELEGATION_V1` | `WIRE_TYPE_DELEGATION` | (nested) | Editor delegation cert (carried inside manifest) |
| `ZERION_CHANNEL_SUBMIT_POST_V1` | `WIRE_TYPE_SUBMIT_POST` | editor → pub | An editor's post for the publisher to add to the chain (protocol version 2) |
| `ZERION_CHANNEL_SUBMIT_POST_ACK_V1` | `WIRE_TYPE_SUBMIT_POST_ACK` | pub → editor | `status` (`OK`, `STALE`, `REFUSED`) and the publisher's chain tip `tip` |
| `ZERION_CHANNEL_TOMBSTONE_V1` | `WIRE_TYPE_CHANNEL_TOMBSTONE` | pub → sub | Signed channel-deleted tombstone (returned in place of any response) |

### Protocol version

Every request a client from 3.0.15 on sends carries `v` (long) = 2
(`ChannelConstants.PROTOCOL_VERSION`). A request without `v` comes from a
client up to 3.0.14. A publisher up to 3.0.14 ignores `v` and the other new
fields, so a 3.0.15 subscriber is served by it as before. A 3.0.15 publisher
answers a request without `v` in the format such a client understands
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
| `v` | long | protocol version, 2; absent from clients up to 3.0.14 |
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
and check-approval requests carry no proof.

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
| `v` | long, optional | 2 when the reply carries `rs` and `cs` |
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
(`zerion-channels-item-sync:<channelId hex>`; a subscriber's cursors in
`zerion-channels-sync-cursor:<channelId hex>`), so serving one channel reads
that channel's state alone. A publisher that finds its database was restored
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
| `signature` | raw | hybrid signature over the manifest signed-input |

### `DISCUSSIONS_IN_MANIFEST` gating

`ChannelConstants.DISCUSSIONS_IN_MANIFEST` is currently **`false`**. While it
is false:

- `encodeManifest` does **not** emit the `discussionsEnabled` key, and
  `manifestSignedInput` does **not** include the discussions byte in the
  signed bytes (see the trailing `if (DISCUSSIONS_IN_MANIFEST)` in both).
- The decoder reads `manifest.getBoolean("discussionsEnabled", true)` - 
  absent ⇒ defaults to `true`.
- The subscriber does **not** persist the wire value into its local
  `ChannelDiscussionStore` (`pullAndApply` only calls
  `discussionStore.setEnabled` when `DISCUSSIONS_IN_MANIFEST`).

So today, whether discussions are on is enforced **only at the publisher**:
the publisher rejects comment RPCs when its local `ChannelDiscussionStore`
says off (`handleCommentRequest` → `discussionStore.isEnabled`). Flipping
`DISCUSSIONS_IN_MANIFEST` to true would move the flag into the signed
manifest. **iOS should treat the manifest `discussionsEnabled` field as
optional/defaulting-true and rely on the publisher's comment-ack for the
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

Signed with the publisher's hybrid key under label
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
| `body` | string | public: plaintext; private: unpadded standard base64 of the AES-GCM ciphertext including the 16-byte tag (no IV sent) |
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

At most 8 attachments per post; the publisher accepts up to 50 MiB of plaintext each. `ZERION_CHANNEL_GET_ATTACHMENT_V1` keys: `type`, `v`, `channelId`, `blobHash`, plus `nonce`, `hmac` and `hmac2` in private channels. The reply has `type`, `blobHash` and `blob` (empty if not held). The subscriber reads the reply with a field bound of 16 MiB, the largest reply it accepts. Metadata stripping is a client duty: the Android app re-encodes images and remuxes video and other ISO media before publishing (from 3.0.15); other file types are sent unchanged.

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
  the signature.
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
  link, and only the publisher serves posts, so a gap or a new link is the
  publisher's statement and nothing a third party can cause; refusing a new
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
  The publisher checks the plaintext, so in the current implementation a
  private post over 3056 UTF-8 bytes is published but refused by
  subscribers (`BODY_TOO_LARGE`), blocking all later posts (known
  limitation).

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

`PULL_BATCH_MAX_POSTS = 100`. Withholding is a subscriber-side view
decision; nothing about it appears on the wire, and the publisher serves the
original post bytes unchanged.

### Storage

Each post is stored as its own value (settings namespace
`zerion-channels-post:<channelId hex>:<seqNum>`), next to one small record
per channel (`zerion-channels-post-meta:<channelId hex>`) holding the ranges
held, the chain tip, the count and bytes held, the highest post read, the
withheld posts, the earliest expiry and the posts given up. Appending a post
writes that post and the record in one transaction. Releases up to 3.0.14
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
pinned post, and a single post above 1 MiB is passed over (the chain moves
past it and it is not kept). The feed says when older posts were removed
this way. A full channel therefore keeps receiving new posts.

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
(section 6), so no editor can delete anything. The publisher then deletes
the target post itself, its attachment blobs, and its reactions and
comments, and stops serving them; a subscriber of this release that receives
the marker does the same with its copy. Subscribers that had not fetched the
target yet bridge the gap. Subscribers on releases up to 3.0.14 keep a copy
they already hold and show it with a "deleted" placeholder; one that had not
fetched the target yet stops at the gap, as it stops at the first format 2
post anyway (the marker itself is one), until it updates. This is distinct
from the channel-level tombstone (section 8).

## 5. Comments and reactions

Both are submitted to the publisher's onion as separate RPC types and stored
at the publisher; they are then redistributed to all subscribers inside
subsequent pull responses (`reactions` / `comments` lists, as deltas to
version 2 requests, section 2). Both are signed with a hybrid key (Ed25519 +
ML-DSA-65), not the publisher's channel key. ML-DSA is mandatory for these
signatures: `ChannelSignatures.signUser` throws if the ML-DSA private key is
missing, and `verifyUser` returns false if the signer's ML-DSA public key is
absent. The ML-DSA public key is taken from the same message and is not
pinned, so authorship rests on the Ed25519 signature.

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
the channel's own key, the subscribers the owner marked as **trusted** in
the subscriber list (`zerion-channels-trusted`, the newest 4096 keys),
approved applicants and editors are known, none of them banned. A key that
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
its future reactions, comments, announcements, applications and submitted
posts. The owner bans from the subscriber list or by long-pressing a
comment, and marks subscribers as trusted from the subscriber list.

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
discussions enabled; body 1 to 1024 chars and name at most 64 chars; parent
post held and not withheld; timestamp fresh; the write allowance of the
author's class not spent (checked before the signature); valid author
signature; author not banned; no held comment with the same `commentId` (an
identical resubmission returns `ok=true` without change); room under the
retention rule above. Subscribers never learn the ban list.

**Comment dedup**: a comment whose `commentId` is already held is accepted
only if identical. Stored sets are kept within the same ceilings.

In the **comments** list of a pull response the keys are: `seq`, `id`,
`body`, `name`, `ed`, `ml`, `ts`, `sig` (sig omitted if empty). On receipt
(`applyIncomingComments`) the subscriber applies the reply as a whole set or
as a delta (section 2): it keeps verified items on held, non-withheld posts,
fits them to the ceilings and stores the result in one write. It fires
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
withheld.

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

### Discussions can be disabled per channel by the owner

`setDiscussionsEnabled(channelId, enabled)` is publisher-only. It writes the
local `ChannelDiscussionStore`. With discussions off the publisher returns
`ok=false` to every comment RPC. (See the `DISCUSSIONS_IN_MANIFEST` note in
section 3 for where the flag is - and is not - signed.)

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
never sent to new holders; in the current implementation the publisher's own
view can no longer decrypt them (known limitation).

### Private channels - request → owner-approve

When `requiresApproval` is set, the capability is **not** in the link
(`p=1` flag instead). The join handshake (all over the publisher onion):

1. **Apply** - `WIRE_TYPE_APPLY_TO_JOIN`. Keys: `type`, `v`, `channelId`,
   `name`, `ts`, `ed`, `ml` (the applicant's member key of the channel,
   section 5), `eph` (an ephemeral **hybrid agreement** public key,
   `crypto.generateHybridAgreementKeyPair`), `sig`. Signed-input
   `applicationSignedInput` = `channelId || int32 nameLen || UTF-8 name ||
   int64 ts || int32 ephLen || eph`, label `SIGNING_LABEL_APPLICATION`.
   Response `WIRE_TYPE_APPLY_ACK = {type, ok}`. The publisher stores a
   PENDING `ChannelApplication` (`MAX_PENDING_APPLICATIONS = 256`). For an
   applicant it already holds (same `ed`), a repeated application or one
   with an older `ts` is acknowledged and changes nothing. One with a new
   `eph` and a `ts` no older than the held one (the applicant left and
   applied again) replaces it and waits for approval again, even if the held
   one was approved. A DENIED application is always replaced.
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
   `status` (`"PENDING"` / `"APPROVED"` / `"DENIED"`), `kemCt` (raw,
   optional), `envelope` (raw, optional). On `"APPROVED"` the applicant
   decapsulates `kemCt` with its ephemeral key pair
   (`crypto.hybridDecapsulate(ownEphKeyPair, kemCt)`), which yields the same
   ML-KEM-768 shared secret the publisher wrapped with, derives the same wrap
   key, unwraps the capability and stores it. An envelope that does not open
   leaves the application PENDING and stores nothing. Applicants on releases
   up to 3.0.14 used a derivation that never matched the publisher's key, so
   on those releases an approved application stays PENDING.

### Editor delegations - `WIRE_TYPE_DELEGATION`

The publisher can issue delegation certificates, up to
`MAX_ACTIVE_DELEGATIONS_PER_CHANNEL = 8`, naming an editor's member key of
the channel (the owner picks an announced subscriber, or pastes the key the
editor copied from the channel menu). An editor posts text (no attachments,
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
reports as a refusal. Each `ChannelDelegationCert`
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
revoked; the unread count is recomputed over posts that are neither read
nor withheld. The publisher applies the same step to its own copy when it
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
(`INVITE_LINK_*_PARAM`). An `m=` param (`INVITE_LINK_MLDSA_PARAM`) is
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
  (classical-only is refused), but the signer's ML-DSA key is
  self-asserted per message.
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
- **MAC challenge** uses `crypto.mac` (keyed BLAKE2b-256) under
  `"org.zerionproject/CHANNEL_HMAC_CHALLENGE"` keyed by the capability,
  over channelId and the client nonce.
- Channel confidentiality is classical (Tor and AES-GCM); only the ML-DSA-65
  signature halves and the approval wrap use post-quantum primitives, and
  channel traffic does not use the ZWF path.

## 8. Channel tombstone - `WIRE_TYPE_CHANNEL_TOMBSTONE`

When the publisher deletes a channel (`deleteChannel`), it stores a signed
tombstone (`publishTombstone`) and deletes everything else it keeps for the
channel. Its onions, the current one and any still retiring, then serve the
tombstone in place of **any** response for 14 days
(`DELETED_CHANNEL_GRACE_DAYS`), also across restarts, after which the onions
are removed and their keys and the tombstone deleted. Keys
(`encodeTombstone`): `type`, `channelId`, `ts`, `sig`. Signed-input
`tombstoneSignedInput = channelId || int64 ts`, label
`SIGNING_LABEL_CHANNEL_TOMBSTONE`. A subscriber that pulls and sees a
tombstone verifies it against the pinned publisher key
(`applyTombstoneIfValid`) and, if valid, removes the channel locally.
Subscribers that do not connect within the grace period keep their copy.

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
marks, editor post counts, remembered onions, the discussion setting and the
unread count. The removal is marked as pending (`zerion-channels-pending-
removal`) before its first step and the mark is cleared after its last, so
a removal cut short by the process being killed is finished at the next
start instead of leaving rows nothing would ever remove. The rows are
deleted, not blanked, so no row naming the channel is left; on the owner's
device only the tombstone and the onion record remain until the grace period
ends.

## 9. Onion lifecycle

`ChannelOnionStore` keeps, per hosted channel, when the current onion is
next rotated and the retired onions that stay published until a given time.

- **Periodic rotation.** Every 28 to 35 days (random per channel) the
  publisher binds a fresh onion, makes it current with a manifest naming it
  (`manifestSeq` + 1) and keeps the old onion published for 30 days, still
  answering every request, so subscribers learn the new address from the
  next signed manifest they pull. Then the old onion is removed and its key
  deleted. Whoever only held the old address, such as an old invite link,
  can no longer reach the device after that; share a fresh link. An
  applicant whose application is still pending checks it at the address of
  its link and reads no manifest, so an application left pending past the
  end of a migration window can no longer learn that it was approved; the
  applicant needs a fresh link.
- **Revocation.** Rotating a private channel's invite link moves the
  channel to a fresh onion and removes the old one at once with its key,
  together with every onion still retiring from earlier rotations. The old
  onions are removed first, so they are gone even while Tor is down; the
  fresh onion is then published at once or, if Tor is unavailable, as soon
  as it is back (the publisher retries every 3 minutes and when Tor becomes
  active). Subscribers holding the new capability learn the new address
  from the owner's next invite link or from a manifest; those without it
  cannot reach the channel any more.
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
  last 12 onions a channel used (`zerion-channels-remembered-onions`); once
  the current onion has not answered for 15 minutes it tries them in turn on
  every other pull, and follows the manifest the one that answers serves.
  Only the holder of an onion's key can serve it and everything served is
  verified under the channel's key, so trying an old address trusts no one
  new. A backup older than every onion the subscriber remembers (about a
  year) cannot be found this way; the owner shares a fresh link.
- **Deletion.** See section 8.
- Retired onions are rebound after a restart until their time, and the
  schedule is checked every 15 minutes.

Banning a subscriber does not rotate the onion: in a public channel anyone
with the link can read anyway, and in a private channel the owner rotates
the invite link (offered with the ban) to cut a banned member off.

## What this doc does NOT cover

- The Tor onion publishing plumbing (`TorChannelTransport.bindServer`,
  `TorPluginOnionPublisher`, `OnionPublisher`) beyond what sections 9 and
  Transport framing say.
- The full on-disk store schemas. Channel state lives in settings
  namespaces of the SQLCipher-backed SettingsManager (for example
  `zerion-channels-state`, `zerion-channels-comments`,
  `zerion-channels-reactions`, `zerion-channels-item-sync:<hex>`,
  `zerion-channels-sync-cursor:<hex>`, `zerion-channels-onions`,
  `zerion-channels-remembered-onions`, `zerion-channels-bans`,
  `zerion-channels-trusted`, `zerion-channels-editor-quota:<hex>`,
  `zerion-channels-pending-removal`, `zerion-channels-instance`; posts as in
  section 4, Storage); attachment blobs are encrypted files under
  `channel-blobs/`. These are local state, not wire format.

## Open TODOs / uncertainties for iOS parity

- **`neighbourHints`** (`WIRE_TYPE_SUBSCRIPTION_HINT`) is plumbed but Android
  always sends an empty list. Unclear if it will be used; iOS can ignore.
- **`WIRE_TYPE_POST`** is defined as a constant but posts are never sent as a
  standalone top-level frame - they only appear inside the pull response
  `posts` list. The tag may be reserved for a future standalone post push.
- **`discussionsEnabled` in the manifest** is gated off
  (`DISCUSSIONS_IN_MANIFEST = false`); today it is publisher-enforced only.
  iOS must default the field to `true` when absent and not include it in the
  manifest signed-input while the flag is false.
- **Versioning.** `MAJOR_VERSION`/`MINOR_VERSION` (0/1) are not on the wire.
  Since 3.0.15 every request carries `v` (section Protocol version), a reply
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
