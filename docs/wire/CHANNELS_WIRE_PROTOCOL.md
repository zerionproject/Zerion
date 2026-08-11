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
- Subscribers poll on a fixed cadence. `ChannelManagerImpl.onDatabaseOpened`
  schedules `refreshAllSubscriptionsSafely()` every 5 seconds; for every
  non-publisher channel with a known onion it fires a `pullAndApply`.
- The publisher re-binds its onion on `B4OwnRotationCompletedEvent` and on
  Tor `TransportActiveEvent`, bumping `manifestSeq` so subscribers learn the
  new address from the next signed manifest they pull.
- **A public channel re-sends ALL posts on every pull.** A public channel
  has `joinCapability == null` (see `createChannel`: `capability =
  publicChannel ? null : freshBytes(...)`). In `pullAndApply`, the request
  is built as `isBootstrap || s.getJoinCapability() == null ?
  buildBootstrapRequest(...)`, and `buildBootstrapRequest` hard-codes
  `sinceSeqNum = -1L`. So a public subscriber always sends a bootstrap
  (`sinceSeqNum = -1`) and the publisher returns every post with
  `seqNum > -1`, i.e. the whole chain. Only private channels (with a
  capability) send incremental pulls anchored at `getHighestKnownPostSeq()`.

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

In Zerion 3.0 these records are carried inside ZWF frames over the ZPP constant-rate transport, tagged by the ZMM record registry; the record format below is unchanged.

Every RPC is a single **BdfDictionary** (not a BdfList), written with the
Bramble `BdfWriter` and read with `BdfReader`. Each dictionary carries a
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
| `ZERION_CHANNEL_TOMBSTONE_V1` | `WIRE_TYPE_CHANNEL_TOMBSTONE` | pub → sub | Signed channel-deleted tombstone (returned in place of any response) |

A `WIRE_TYPE_SUBSCRIPTION_HINT` constant
(`ZERION_CHANNEL_SUBSCRIPTION_HINT_V1`) is also defined; the pull response
carries a `neighbourHints` list of strings (Android currently always sends
an empty list - see `handlePublisherRequest`). TODO: the hint list is
plumbed end-to-end but unused; iOS may ignore it for now.

## 1. Pull request - `WIRE_TYPE_PULL_REQUEST`

`ChannelPullCodec.encodePullRequest` / `decodePullRequest`.

| key | BDF type | notes |
|---|---|---|
| `type` | string | `ZERION_CHANNEL_PULL_REQUEST_V1` |
| `channelId` | raw | 32 bytes (`CHANNEL_ID_BYTES`) |
| `sinceSeqNum` | long | `-1` = bootstrap (send everything); otherwise send posts with `seqNum > sinceSeqNum` |
| `hmacResponse` | raw, optional | HMAC over the publisher challenge (private channels only) |
| `nonce` | raw, optional | 16-byte client nonce (`BOOTSTRAP_HMAC_NONCE_BYTES`) |

Two request shapes (`ChannelPullProtocol`):

- **Bootstrap** - `buildBootstrapRequest(channelId)` →
  `encodePullRequest(channelId, -1L, null, null)`. No HMAC, no nonce. Used
  by every public channel and by the first pull of any channel.
- **Authenticated incremental** - `buildAuthenticatedRequest(channelId,
  sinceSeqNum, capability, publisherNonce)`. `sinceSeqNum` is the
  subscriber's `getHighestKnownPostSeq()`. `hmacResponse =
  ChannelHmacChallenge.respond(capability, publisherNonce, channelId)`,
  which is `crypto.mac("org.zerionproject/CHANNEL_HMAC_CHALLENGE",
  SecretKey(capability), channelId, nonce)`.

### Challenge handling on the publisher

In `handlePublisherRequest` (the default branch), for a channel that has a
`joinCapability` (i.e. private):

1. If `hmacResponse` and `nonce` are both present, the publisher first calls
   `recordFreshNonce(channelId, nonce)` - an anti-replay LRU ring,
   `PULL_NONCE_TTL_MS = 5 min`, `PULL_NONCE_MAX_PER_CHANNEL = 4096`. A nonce
   seen before (within TTL) is rejected (`return new byte[0]`).
2. It then verifies the HMAC via `ChannelHmacChallenge.verify`. A bad MAC →
   empty response.
3. If the channel is private with a capability and the challenge did not
   pass, the publisher returns `new byte[0]` - no manifest, no posts. **A
   private channel only serves data to a holder of the capability.**

> Note: the *nonce here is the subscriber's own random nonce*, not a
> publisher-issued challenge. The MAC binds (capability, channelId, nonce)
> so only a capability holder can produce it, and the publisher's replay
> ring prevents reuse. TODO: there is no separate publisher→subscriber
> challenge round-trip; iOS should generate a fresh 16-byte nonce per pull.

## 2. Pull response - `WIRE_TYPE_PULL_RESPONSE`

`ChannelPullCodec.encodePullResponse` / `decodePullResponse`.

| key | BDF type | notes |
|---|---|---|
| `type` | string | `ZERION_CHANNEL_PULL_RESPONSE_V1` |
| `manifest` | dictionary | the signed manifest (section 3) |
| `posts` | list of dict | each entry is a wire post (section 4) |
| `contentKeyEnvelope` | raw, optional | AES-GCM-wrapped channel content key (private channels, only when the challenge passed) |
| `neighbourHints` | list of string | currently empty |
| `reactions` | list of dict | section 5 |
| `comments` | list of dict | section 5 |

The publisher builds this in `buildResponseAsPublisher`. It always re-signs
the manifest fresh (`signLatestManifest`) so the embedded `currentOnion` and
`manifestSeq` are current. `contentKeyEnvelope` is only attached when the
challenge passed **and** the channel has a content key
(`wrapContentKey(capability, channelId, contentKey)`); it lets a freshly
approved subscriber learn the symmetric key without it ever appearing in the
invite link.

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
| `joinCapability` | raw, optional | only present on the wire for **public** channels (`buildResponseAsPublisher` sets `wireJoinCapability = isPublic ? joinCapability : null`). For private channels the capability is delivered out-of-band (invite link / approval envelope), never in the manifest. |
| `currentOnion` | string | publisher onion address (may be empty before first bind) |
| `manifestSeq` | long | monotonic; a merge is rejected if `incomingSeq <= local.getManifestSeq()` |
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

In order, a manifest is rejected (merge returns null ⇒ pull fails) if any of:

1. `publisherEd25519` differs from the locally pinned publisher key.
2. local ML-DSA key is known and `publisherMlDsa` differs.
3. `channelId` differs from local.
4. the channelId is not reproducible:
   `hash("org.zerionproject/CHANNEL_ID",
   HybridSignaturePublicKey(ed, mlDsa).getEncoded(), salt)` must equal the
   channelId. This binds the channel id to the publisher key + salt.
5. the hybrid manifest signature fails to verify.

If all pass but `incomingSeq <= local.getManifestSeq()`, the local state is
returned unchanged (stale manifest, ignored but not an error).

## 4. Posts

Posts ride inside the pull response `posts` list. `ChannelPullCodec.postToWire`
/ `wireToPost`.

| key | BDF type | notes |
|---|---|---|
| `seqNum` | long | 0-based, strictly increasing, no gaps |
| `prevHash` | raw | 32 bytes; canonical hash of the previous post (all-zero for `seqNum 0`) |
| `timestampHourMs` | long | floored to the hour |
| `body` | string | public: plaintext; private: base64 of AES-GCM ciphertext |
| `ttlMs` | long | 0 = no expiry; ephemeral if > 0 |
| `signature` | raw | hybrid signature over the post signed-input |
| `delegateSignerEd25519` | raw, optional | present only if signed by a delegated editor |
| `delegateSignerMlDsa` | raw, optional | present only if signed by a delegated editor |
| `attachments` | list of dict | each: `hash` (32B blob hash), `size` (long), `mime` (string), `key` (per-attachment key, wrapped for private channels), `thumb` (raw, optional) |

### Post signed-input (byte-exact)

`ChannelCodec.postSignedInput`:

```
channelId (32)
seqNum (8)
prevHash (32)
timestampHourMs (8)
bodyHash (32)            = hash("…/CHANNEL_POST_BODY", UTF-8 wireBody)
attachmentsHash (32)     = hash("…/CHANNEL_POST_ATTACHMENTS", canonical attachments)
ttlMs (8)
```

`attachmentsHash` canonical bytes (`ChannelCodec.attachmentsHash`): per
attachment `blobHash || int64 size || ASCII mime || (thumb?1:0) [|| thumb]`,
all concatenated then hashed. Note `body` here is the **wire** body (the
base64 ciphertext for a private channel), so the signature covers exactly
what is transmitted.

Signed under `SIGNING_LABEL_POST = "org.zerionproject/CHANNEL_POST"`.
For a normal post the verifying key is the publisher hybrid key; for a
delegate-signed post it is `HybridSignaturePublicKey(delegateEd, delegateMl)`
after the delegation cert checks pass (see section 6).

### Hash chain (`ChannelChainVerifier` / `ChannelPostValidator`)

- `seqNum 0` must carry an all-zero `prevHash`.
- For `seqNum n > 0`, `prevHash` must equal the canonical hash of post
  `n-1`. The canonical hash (`postCanonicalHash`) is
  `hash("org.zerionproject/CHANNEL_POST_CHAIN",
  channelId || seqNum || prevHash || timestampHourMs || UTF-8 body ||
  attachmentsHash || ttlMs || signature)` - note this hashes the **wire**
  body and **includes the signature**, so the chain commits to the exact
  signed bytes.
- `ChannelPostValidator` additionally enforces body length ≤ 4096 chars
  (`MAX_POST_BODY_CHARS`) and verifies the post signature.

### Skip-known rule (incremental apply)

`ChannelPullProtocol.processSubscriberResponse`: `lastKnownSeq` is the
seqNum of the last locally stored post (or `-1`). For each incoming post,
**`if (incoming.getSeqNum() <= lastKnownSeq) continue;`** - already-known
posts are silently skipped. The first post that fails validation **breaks**
the loop (the rest of the batch is discarded - the chain must be contiguous).
`PULL_BATCH_MAX_POSTS = 100`.

### TTL / purge

`MAX_TTL_SECONDS = 30 days`. Convenience constants exist
(`TTL_OFF`, `TTL_ONE_HOUR_MS`, … `TTL_THIRTY_DAYS_MS`). A daily task
(`runDailyPurgeSafely` → `purgeExpiredPosts`) drops expired ephemeral posts.
`DEFAULT_RECENT_POSTS_RETAINED = 500`.

### Deletes

A post delete is itself a published post whose body is a tombstone marker
`ZRN_TOMBSTONE:<channelIdHex>:<seqNum>:D` (`TOMBSTONE_PREFIX`). Readers parse
these markers (`parseTombstoneTarget`) and render the targeted post as
` - deleted - `. This is distinct from the channel-level tombstone (section 8).

## 5. Comments and reactions

Both are submitted by subscribers via their own onion RPC and stored at the
publisher; they are then redistributed to all subscribers inside subsequent
pull responses (`reactions` / `comments` lists). Both are signed by the
**author's identity** hybrid key (Ed25519 + ML-DSA-65), not the publisher
key. ML-DSA is mandatory for these user signatures: `ChannelSignatures.signUser`
throws if the local ML-DSA private key is missing, and `verifyUser` returns
false if the author's ML-DSA public key is absent.

### Comment submit - `WIRE_TYPE_POST_COMMENT`

`encodeCommentRequest` keys: `type`, `channelId`, `seq` (parent post
seqNum), `id` (commentId, a random `long`), `body`, `name` (author display
name), `ts`, `ed` (author Ed25519 pub), `ml` (author ML-DSA pub), `sig`.
Response is `WIRE_TYPE_COMMENT_ACK` = `{type, ok: boolean}`.

Comment signed-input (`ChannelCodec.commentSignedInput`):
```
channelId (32) || int64 parentPostSeqNum || int64 commentId
|| int32 bodyLen || UTF-8 body || int32 nameLen || UTF-8 name
|| int64 timestampHourMs
```
Label `SIGNING_LABEL_COMMENT = "org.zerionproject/CHANNEL_COMMENT"`.

Publisher acceptance (`handleCommentRequest`), each returns `ok=false` on
failure: channelId match; discussions enabled; body non-empty and ≤ 1024
chars (`MAX_COMMENT_BODY_CHARS`); valid author signature; author not banned;
channel under `MAX_COMMENTS_PER_CHANNEL` (4096); author under
`MAX_COMMENTS_PER_AUTHOR` (256).

**Comment dedup** (`ChannelCommentStore.putComment`): a new comment is
dropped if an existing comment has the same `commentId`. Hard cap 4096
comments per channel in the store as well.

In the **comments** list of a pull response the keys are: `seq`, `id`,
`body`, `name`, `ed`, `ml`, `ts`, `sig` (sig omitted if empty). On receipt
(`applyIncomingComments`) the subscriber re-verifies the author signature,
checks the author is not banned, then `putComment` (which dedups by
`commentId`), and fires `ChannelCommentReceivedEvent`.

### Reaction submit - `WIRE_TYPE_POST_REACTION`

`encodeReactionRequest` keys: `type`, `channelId`, `seq` (post seqNum),
`emoji`, `ts`, `ed`, `ml`, `sig`. Response is `WIRE_TYPE_REACTION_ACK` =
`{type, ok}`.

Reaction signed-input (`ChannelCodec.reactionSignedInput`):
```
channelId (32) || int64 postSeqNum || int32 emojiLen || UTF-8 emoji
|| int64 timestampHourMs
```
Label `SIGNING_LABEL_REACTION = "org.zerionproject/CHANNEL_REACTION"`.
`MAX_REACTION_EMOJI_BYTES = 32`, `MAX_REACTIONS_PER_POST = 256`.

**Reaction identical-skip** (`ChannelReactionStore.putReaction`): a reaction
is keyed by `(postSeqNum, signerEd25519)` - one reaction per user per post.
A submission that matches an existing `(post, signer)` **replaces** it; but
if the emoji and timestamp are unchanged the store returns `false` (no write,
no event) - identical re-submits are no-ops. A different emoji from the same
user overwrites their previous reaction.

In the **reactions** list the keys are: `seq`, `emoji`, `ed`, `ml`, `ts`,
`sig`. On receipt (`applyIncomingReactions`) the subscriber re-verifies,
checks ban, then `putReaction` (identical-skip applies).

### Announce - `WIRE_TYPE_ANNOUNCE`

A subscriber announces its display name so the publisher and other readers
can attribute comments/reactions. `encodeAnnounceRequest` keys: `type`,
`channelId`, `name`, `ts`, `ed`, `ml`, `sig`. Response `WIRE_TYPE_ANNOUNCE_ACK
= {type, ok}`. Signed-input (`announceSignedInput`): `channelId || int32
nameLen || UTF-8 name || int64 ts`, label `SIGNING_LABEL_ANNOUNCE`.
`MAX_DISPLAY_NAME_BYTES = 64`, `MAX_ANNOUNCED_SUBSCRIBERS = 4096`. Subscribers
auto-announce once after a successful pull (`tryAutoAnnounceIfNeeded`).

### Discussions can be disabled per channel by the owner

`setDiscussionsEnabled(channelId, enabled)` is publisher-only. It writes the
local `ChannelDiscussionStore`. With discussions off the publisher returns
`ok=false` to every comment RPC. (See the `DISCUSSIONS_IN_MANIFEST` note in
section 3 for where the flag is - and is not - signed.)

## 6. Public vs private channels; editor delegations

### Public channels

`joinCapability == null`, `contentKey == null`. Posts are plaintext on the
wire. Anyone with the invite link (channelId + publisher Ed25519 + onion)
can bootstrap-pull the whole chain. No HMAC challenge.

### Private channels - open invite link

`joinCapability != null` (32 bytes, `JOIN_CAPABILITY_BYTES`),
`contentKey != null` (32 bytes, `CONTENT_KEY_BYTES`). The capability appears
in the invite link only when the channel is **not** approval-gated
(`formatInviteLink` adds the `k=` param only when `!publicChannel &&
joinCapability != null && !requiresApproval`). Holding the capability lets a
subscriber answer the HMAC challenge and unwrap the `contentKeyEnvelope` to
decrypt post bodies and attachment keys.

### Private channels - request → owner-approve

When `requiresApproval` is set, the capability is **not** in the link
(`p=1` flag instead). The join handshake (all over the publisher onion):

1. **Apply** - `WIRE_TYPE_APPLY_TO_JOIN`. Keys: `type`, `channelId`, `name`,
   `ts`, `ed`, `ml`, `eph` (an ephemeral **hybrid agreement** public key,
   `crypto.generateHybridAgreementKeyPair`), `sig`. Signed-input
   `applicationSignedInput` = `channelId || int32 nameLen || UTF-8 name ||
   int64 ts || int32 ephLen || eph`, label `SIGNING_LABEL_APPLICATION`.
   Response `WIRE_TYPE_APPLY_ACK = {type, ok}`. The publisher stores a
   PENDING `ChannelApplication` (`MAX_PENDING_APPLICATIONS = 256`).
2. **Owner approves** (`approveApplication`, publisher-only): the publisher
   does `crypto.hybridEncapsulate(applicantEphemeralPubKey)` to get a KEM
   ciphertext + shared secret, derives a wrap key
   `deriveKey(APPROVAL_WRAP_LABEL, sharedSecret, channelId)`, and AES-GCM
   wraps the channel **capability** into an envelope. KEM ciphertext +
   envelope are stored on the application. (Deny just marks it DENIED.)
3. **Poll** - `WIRE_TYPE_CHECK_APPROVAL`. Keys: `type`, `channelId`, `ts`,
   `ed`, `ml`, `sig`. Signed-input `checkApprovalSignedInput = channelId ||
   int64 ts`, label `SIGNING_LABEL_CHECK_APPROVAL`. Throttled to once per
   30s per channel (`pollApprovalStatusIfPending`).
4. **Approval response** - `WIRE_TYPE_APPROVAL_RESPONSE`. Keys: `type`,
   `status` (`"PENDING"` / `"APPROVED"` / `"DENIED"`), `kemCt` (raw,
   optional), `envelope` (raw, optional). On `"APPROVED"` the applicant runs
   `deriveHybridSharedSecretAsResponder(APPROVAL_WRAP_LABEL, …, kemCt)`,
   unwraps the envelope to recover the capability, and stores it into local
   `ChannelState` - it is now a full private subscriber.

### Editor delegations - `WIRE_TYPE_DELEGATION`

The publisher can delegate posting rights to other identities, up to
`MAX_ACTIVE_DELEGATIONS_PER_CHANNEL = 8`. Each `ChannelDelegationCert`
(`certToWire`) has keys: `type`, `channelId`, `delegateeEd25519`,
`delegateeMlDsa`, `validFromHourMs`, `validUntilHourMs` (0 = unbounded),
`delegationSeq`, `signature`. Certs are carried inside the manifest
(`activeDelegations`), revoked via `revokedDelegationSeqs`.

Delegation signed-input (`delegationSignedInput`): `channelId ||
delegateeEd25519 || delegateeMlDsa || int64 validFrom || int64 validUntil ||
int64 delegationSeq`, label `SIGNING_LABEL_DELEGATION =
"org.zerionproject/CHANNEL_DELEGATION"`, signed by the **publisher**
hybrid key. A delegate-signed post (`post.signedByDelegate()`) is accepted
only if the cert exists in `activeDelegations`, is not in
`revokedDelegationSeqs`, covers `post.timestampHourMs`
(`coversTimestamp`), and the cert's own signature verifies against the
publisher key (`ChannelPostValidator.checkDelegationIfApplicable`).

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
  manifest, posts, delegations, and tombstone with the channel's own
  hybrid signature key (generated by `crypto.generateHybridSignatureKeyPair`,
  private key stored via `store.putPublisherPrivKey`). Subscribers sign
  comments, reactions, announces, applications, and approval-checks with
  their **personal** identity Ed25519 key + local ML-DSA-65 key. Personal
  user signatures **require** ML-DSA - classical-only is refused.
- **Channel id binding**:
  `channelId = hash("org.zerionproject/CHANNEL_ID",
  HybridSignaturePublicKey(ed,mlDsa).getEncoded(), salt)`. Subscribers
  re-derive and reject any manifest that does not match.
- **At-rest / content encryption is AES-256-GCM** (`ChannelContentKey`,
  `AES/GCM/NoPadding`, 12-byte IV, 128-bit tag):
  - Post bodies in private channels: key = 32-byte channel content key,
    deterministic nonce `hash("…/CHANNEL_BODY_NONCE", channelId, seqNum)[0:12]`,
    AAD = `channelId || int64 seqNum`. The wire body is base64 of the
    ciphertext.
  - Attachment blobs: per-attachment 32-byte key, random 12-byte nonce
    prefixed to the ciphertext, AAD = `channelId || int32 mimeLen ||
    UTF-8 mime || int64 size`. Blobs are content-addressed by
    `hash("…/CHANNEL_ATTACHMENT_BLOB", encryptedBlob)`.
  - Content-key wrap (for the pull-response envelope and per-attachment key
    wrapping in private channels): wrap key =
    `deriveKey("…/CHANNEL_CONTENT_KEY_WRAP", SecretKey(capability),
    channelId, info="ZERION_CHANNEL_CONTENT_KEY_WRAP")`, AES-GCM with random
    IV.
  - Approval capability envelope: wrap key =
    `deriveKey(APPROVAL_WRAP_LABEL, SecretKey(KEM sharedSecret), channelId)`,
    AES-GCM with random IV.
- **HMAC challenge** uses `crypto.mac` under
  `"org.zerionproject/CHANNEL_HMAC_CHALLENGE"` keyed by the capability.

## 8. Channel tombstone - `WIRE_TYPE_CHANNEL_TOMBSTONE`

When the publisher deletes a channel (`deleteChannel`), it stores a signed
tombstone (`publishTombstone`). Thereafter `handlePublisherRequest` returns
the tombstone bytes in place of **any** response. Keys
(`encodeTombstone`): `type`, `channelId`, `ts`, `sig`. Signed-input
`tombstoneSignedInput = channelId || int64 ts`, label
`SIGNING_LABEL_CHANNEL_TOMBSTONE`. A subscriber that pulls and sees a
tombstone verifies it against the pinned publisher key
(`applyTombstoneIfValid`) and, if valid, removes the channel locally.

## What this doc does NOT cover

- The Tor onion bind/rotation plumbing (`TorChannelTransport`,
  `TorPluginOnionPublisher`, `OnionPublisher`) beyond the STAR fact above.
- The on-disk store schemas (`ChannelStore`, `ChannelBlobStore`,
  `ChannelSubscriberStore`, etc.). These are EncryptedSharedPreferences /
  SQLCipher-backed namespaces (e.g. `zerion-channels-comments`,
  `zerion-channels-reactions`, `zerion-channels-discussions`) and are local
  state, not wire format.

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
- **`MINOR_VERSION`/`MAJOR_VERSION`** (0/1) are defined but no version byte
  was observed inside the wire dictionaries; versioning is carried by the
  `*_V1` suffix on the `type` string. TODO: confirm whether a future bump
  changes the `type` string or adds a numeric field.
- **ML-DSA public key in invite links** (`m=` param) is reserved but unused;
  the subscriber currently learns the publisher ML-DSA key from the first
  signed manifest, and `mergeManifestIntoLocal` only hard-pins ML-DSA if a
  local copy already exists.
