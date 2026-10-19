# GroupTr - group membership and post wire protocol

iOS parity for Zerion group chat. Android implementation: `zerion-app/.../grouptr/GroupTrManagerImpl.java` plus the validator at `zerion-app/.../messaging/PrivateMessageValidator.java`. Shipped on Android since 1.5. Only the creator can add or remove members, dissolve the group, change roles and send snapshots; the creator may let members, or chosen members, invite contacts that the creator's device then adds, but never lets anyone else remove a member. The ADMIN role grants no authority on Android, and from 3.0.18 the Android app no longer offers to assign it (msgType 38 keeps its meaning).

## Protocol versions

Peers learn each other's version from the minor version of the messaging
client they announce (`MessagingManager.MINOR_VERSION`). Minor version 8
(Android 3.0.15) is the second version of the group protocol
(`GROUP_PROTOCOL_V2_MIN_VERSION`). It adds three things, and a sender looks
at the recipient's announced version before using any of them:

- **Large posts.** A receiver on version 8 keeps a post's body in the
  message only, so it takes in a post as large as a message allows (the
  composer allows about 9.5 MB). A receiver on an earlier version copies the
  body into the message's metadata, which holds no value longer than 64 KiB,
  and refuses a larger post as invalid. A sender therefore sends a post with
  a body larger than 64 KiB (65,536 bytes) only to members that announce
  version 8; `sendGroupPost` returns how many members it passed over and the
  group screen says so. Posts of at most 64 KiB reach every member.
- **The group settings record (msgType 46)**, which carries the group's
  disappearing timer. It is sent only to members on version 8; an earlier
  version would refuse it as an unknown type. The creator sends it again,
  re-signed over the same timestamp, to a member whose announced messaging
  version reaches 8 later (or becomes known), and a timer set under an
  earlier version, which no member was ever told of, is dated and sent at
  the first start of version 8. The creator's screen says how many members
  run an older app: they do not apply the timer to their own posts, but
  receivers on version 8 apply it to those posts on receipt.
- **Leave confirmation.** A creator on version 8 confirms a member's leaving
  with its own removal and epoch commit, and a member on version 8 lets a
  leaving move the shared epoch only in a group whose creator runs an
  earlier version (see msgType 35).

iOS: the iOS client announces its own messaging minor version; Android uses
the three features above with an iOS peer only if that peer announces 8 or
more. An iOS client that announces 8 must implement them first.

Minor version 10 (Android 3.0.18, `GROUP_ACTIONS_MIN_VERSION`) adds two
post actions and member invitations:

- **Delete for everyone (msgType 47, `GROUP_POST_DELETE`).** The author of a
  post can withdraw it from the members that received it. The record goes only
  to members that announce 10 or more; an earlier version would refuse it as an
  unknown type.
- **Replies (post body type 5, `TYPE_REPLY`).** A post can quote an earlier
  post. One signed body goes to every member, so a sender uses the reply body
  only when every member contact it sends the post to announces 10 or more
  (a member whose version is not known counts as older), and otherwise sends
  the same text as a plain text body. An earlier version would show the reply
  body as raw bytes.

- **Member invitations (msgTypes 48 to 52).** The creator's signed group
  permissions and the records by which a member invites a contact that the
  creator's device then adds. Each record is sent only to a contact that
  announces 10 or more. See "Member invitations" below.

An iOS client that announces 10 must implement all three first, and Android
uses none of them with a peer that announces less.

Minor version 11 (Android 3.0.18, `GROUP_INVITER_GRANTS_MIN_VERSION` and
`GROUP_INCOGNITO_MIN_VERSION`) adds two features:

- **Per-member add rights (msgTypes 53 and 54).** The creator gives chosen
  members the right to add members; see "Per-member add rights".
- **Incognito names (msgTypes 55 and 56).** A user creates or joins a group
  under a random name; see "Incognito names".

Each of the records 53 to 56 is sent only to a contact that announces 11 or
more; an earlier version would refuse it as an unknown type. Everything of
version 10, including the group-wide switch of msgType 48, works unchanged
with a peer on version 10. An iOS client must implement 53 to 56, for its
roles, before it announces 11.

Minor version 12 (Android 3.0.18, `SALTED_TEXT_MIN_VERSION`) changes no
group record: it adds a random salt to one-to-one text messages and edits
("Salted bodies" in
[MESSAGE-EDIT-DELETE.md](../protocol/MESSAGE-EDIT-DELETE.md)). Android
announces 12 while `B3Constants.B3_PROOF_ENABLED` is on, and 5 otherwise. An
iOS client must accept the salted bodies before it announces 12.

## Two layers: silent membership fan-out + an explicit invite handshake

GroupTr has **two** distinct wire layers, and iOS must implement both:

1. **Membership records (msgTypes 33–41) are silent fan-out.** These are
   signed state-transition records consumed by the membership state machine.
   They are NOT shown in any chat thread and require no user action.
2. **The invite handshake (msgTypes 42 OFFER / 43 ACCEPT / 44 DECLINE),
   added 2026-05.** This is an explicit invitation-and-response exchange that
   precedes a creator-driven `addMember`. The OFFER is delivered to the
   invitee, who may accept or decline; only on ACCEPT does the creator fan out
   the corresponding membership records.

So the earlier framing - "there is no group invite or accept wire message,
GroupTr is fan-out-of-signed-records, not invitation-and-accept" - is no
longer accurate as of the 2026-05 invite layer. Both statements describe the
membership records (33–41) correctly, but the 42/43/44 handshake layer now
sits on top.

When Alice (creator) directly adds Peter to a group via `addMember` (Android
calls `addMember` only after a verified ACCEPT):

1. Alice's app builds a single `GROUP_MEMBER_ADDED` record (msgType 33).
2. Alice sends that record over the **existing pairwise private-message channel**
   with each current group member, including Peter himself.
3. On Peter's device, his private-message validator dispatches by the first
   BdfList element (`33L`), routes the record to the group-membership handler,
   fires a `GroupMembershipChangedEvent`, and the GroupTr manager applies it
   only if Peter already holds the group, which Android creates when Peter
   accepts the msgType-42 OFFER; otherwise the record is dropped.

For the membership-record path, Peter does NOT see anything in his chat thread
with Alice. The record carries `MSG_KEY_LOCAL=false` and is consumed silently
by the membership state machine.

The invite handshake (42/43/44) is the user-visible layer: an OFFER produces a
pending invite the invitee can accept or decline. See the "Invite handshake"
sections below for the exact wire formats.

## Transport

All GroupTr records ride over the same pairwise messaging channel as private messages. No new sync-client, no new group-message group ID, no new transport. Each record is a BdfList whose first element is the msgType integer.

In Zerion 3.0 these records travel as ordinary private messages, carried as sync records inside ZWF frames over the paced ZPP transport; the record format below is unchanged.

```
Alice's pairwise messaging Group with Peter (pairwise contact group)
                    |
                    |  GROUP_MEMBER_ADDED record (msgType 33)
                    v
                Peter's app
                    |
                    v
        PrivateMessageValidator dispatches by msgType
                    |
                    v
        validateGroupMemberAdded() - parses, computes the signed input
                    |
                    v
        MessagingManagerImpl.incomingGroupMembership() - fires event
                    |
                    v
        GroupTrManagerImpl.handleMembershipEvent() - verifies sig,
                                                     applies state
```

## Message types (Android `MessageTypes.java`)

| msgType | Constant | Purpose |
|---|---|---|
| 32 | `GROUP_POST` | Group post. Not encrypted at the group layer: each copy is protected by the pairwise channel it is sent over and carries the sender's hybrid signature. |
| 33 | `GROUP_MEMBER_ADDED` | The "invite". Adds a new member at a new epoch. |
| 34 | `GROUP_MEMBER_REMOVED` | Creator removes a member; epoch bumps. Sent together with 37. |
| 35 | `GROUP_MEMBER_LEFT` | A member voluntarily leaves. |
| 36 | `GROUP_DISSOLVED` | Creator dissolves the group. |
| 37 | `GROUP_EPOCH_COMMIT` | Advances the epoch by one; carries a random seed that only the signature covers. Sent with 34. |
| 38 | `GROUP_MEMBER_ROLE_CHANGED` | Creator promotes/demotes a member to/from admin. |
| 39 | `GROUP_MEMBER_KEY_ROTATED_RESERVED` | Reserved - not emitted or accepted on the wire. |
| 40 | `GROUP_FORWARDED_RESERVED` | Reserved - not emitted or accepted on the wire. |
| 41 | `GROUP_MEMBER_LIST_SNAPSHOT` | Full member-list snapshot at a given epoch (for repair / late joiners). |
| 42 | `GROUPTR_INVITE_OFFER` | Creator offers a group invite to a contact. Signing label `org.zerionproject/GROUPTR_INVITE_OFFER`. |
| 43 | `GROUPTR_INVITE_ACCEPT` | Invitee accepts an offer. Signing label `org.zerionproject/GROUPTR_INVITE_ACCEPT`. |
| 44 | `GROUPTR_INVITE_DECLINE` | Invitee declines an offer. Signing label `org.zerionproject/GROUPTR_INVITE_DECLINE`. |
| 45 | none | Not assigned; kept free for the planned stale-invite resync. |
| 46 | `GROUP_SETTINGS` | The creator's group settings: the group's disappearing timer (version 8 and later). Signing label `org.zerionproject/GROUP_SETTINGS`. |
| 47 | `GROUP_POST_DELETE` | The author withdraws one of its posts from every member (version 10 and later). Signing label `org.zerionproject/GROUP_POST_DELETE`. |
| 48 | `GROUP_PERMISSIONS` | The creator's group permissions; bit 0 lets members invite their contacts (version 10 and later). Signing label `org.zerionproject/GROUP_PERMISSIONS`. |
| 49 | `GROUPTR_MEMBER_INVITE_OFFER` | A member offers a contact an invitation to the group (version 10). Signing label `org.zerionproject/GROUPTR_MEMBER_INVITE_OFFER`. |
| 50 | `GROUPTR_MEMBER_INVITE_ACCEPT` | The invited contact accepts a member's invitation (version 10). Signing label `org.zerionproject/GROUPTR_MEMBER_INVITE_ACCEPT`. |
| 51 | `GROUPTR_MEMBER_INVITE_DECLINE` | The invited contact refuses a member's invitation (version 10). Signing label `org.zerionproject/GROUPTR_MEMBER_INVITE_DECLINE`. |
| 52 | `GROUPTR_JOIN_REQUEST` | The inviting member asks the creator's device to add the contact (version 10). Signing label `org.zerionproject/GROUPTR_JOIN_REQUEST`. |
| 53 | `GROUP_INVITER_GRANT` | The creator gives one member the right to add members, or takes it back (version 11). Signing label `org.zerionproject/GROUP_INVITER_GRANT`. |
| 54 | `GROUPTR_GRANTED_INVITE_OFFER` | A member that holds that right offers a contact an invitation (version 11). Signing label `org.zerionproject/GROUPTR_GRANTED_INVITE_OFFER`. |
| 55 | `GROUPTR_INVITE_ACCEPT_INCOGNITO` | The invitee accepts a creator's offer under its incognito name (version 11). Carries the signature of 43. |
| 56 | `GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO` | The invited contact accepts a member's invitation under its incognito name (version 11). Carries the signature of 50. |

`32`'s record format is summarised below; this doc covers 33 to 38 and 41
(the membership records), 42 to 44 (the invite handshake), 46 (the group
settings), 47 (delete for everyone), 48 to 52 (member invitations, see
"Member invitations"), 53 and 54 (see "Per-member add rights"), 55 and 56
(see "Incognito names"), and the post bodies carried in slot 5
of msgType 32. msgTypes 39 and 40 are reserved constants only - no validator
path encodes or accepts them.

### 32 - GROUP_POST: storage and size

The record format is unchanged:
`BdfList.of(32L, groupId, epoch, senderPubKey, senderName, body, sig[, ttl])`.
The signed input covers `H("org.zerionproject/GROUP_POST_CT", body)`, not
the body itself. From version 8 a receiver stores in the message's metadata
the body's hash and length (`groupBodyHash`, `groupBodyLength`), not the body,
and reads the body from the message when the post is shown, so a post as
large as a message is taken in. A sender keeps each copy it sends the same
way. An earlier version stored the body in the metadata, which limited a post
to 64 KiB on the receiving side; the first start of version 8 converts every
stored post once, which also makes the sender's own earlier posts larger than
64 KiB readable again.

A post's auto-delete timer is the one its sender signed (slot 8). Where the
group has a disappearing timer (msgType 46) that is shorter, or the post
carries none, the group's timer applies to the post on the receiving device.
The timer counts as in a one-to-one conversation, never from the sender's
clock: on the receiving device from the time the post arrived there
(`groupReceivedAt` in the stored copy's metadata), also while the post is
pending, so a post that took longer than its timer to arrive, or came from a
device whose clock is off, is still shown for the length of its timer and a
post dated in the future cannot outlive the timer; on the sending device the
post leaves the screen when the timer has run from the time it was sent, and
each stored copy carries the timer as a cleanup duration that starts when the
member acknowledges the copy, so a copy still on its way to a member is kept
until it gets there. A copy that is never acknowledged stays, as a sent
message does in a conversation (there is no relay). Every stored copy is due
for cleanup when its timer runs out; the group screen hides the post at the
same moment. An earlier version counted every copy from the sender's
timestamp and scanned every stored post at each start to find expired ones;
version 8 relies on the cleanup timers.

### Post identity

Every post has a stable 32-byte identity, the same on every device and for
every copy of the post:

```
postSignedInput = [32B groupId][4B BE epoch][32B senderPubKey]
                  [32B H("org.zerionproject/GROUP_POST_NAME", UTF-8 senderName)]
                  [32B H("org.zerionproject/GROUP_POST_CT", body)]
                  [8B BE timestamp][8B BE ttl]                      (148 bytes)
postId = H("org.zerionproject/GROUP_POST_SEEN", postSignedInput)
```

`postSignedInput` is exactly the input the post signature (label
`org.zerionproject/GROUP_POST`) covers: `epoch` is slot 2 as a 4-byte unsigned
value, `timestamp` is the timestamp of the enclosing private message, `ttl` is
slot 7 or 0 when the record has seven slots, and H is defined under
EPOCH_COMMIT below. Android keeps the identities of the posts it took in (the
seen set) and refuses a post whose identity it has seen; replies (body type 5)
and deletes (msgType 47) name a post by this identity.

### Post bodies (slot 5 of msgType 32)

A body that does not start with `0xFF 0x01` is plain UTF-8 text (body type
1 is also text: `[0xFF][0x01][0x01][UTF-8 text]`). Other bodies are
`[0xFF][0x01][type][payload]`:

| type | Name | Payload |
|---|---|---|
| 2 | VOICE | `[4B BE durationMs][Ogg Opus]` |
| 3 | IMAGE | `[1B mimeLength][mime][image]` |
| 4 | VIDEO | `[4B BE durationMs][1B mimeLength][mime][video]` |
| 5 | REPLY | `[32B targetPostId][UTF-8 text]` (version 10 and later) |

From 3.0.18 Android cleans a VOICE payload before it sends it: the Opus
comment header the recorder writes is replaced by an empty one (no vendor
string, no comments) and every page gets a fresh random stream serial, with
its checksum recomputed; a recording that cannot be read page by page is not
sent. A VIDEO payload is remuxed and cleaned like a channel video (from
3.0.15; from 3.0.18 the write times, track languages and handler names of an
MP4 and the writer, title and date fields of a WebM are zeroed as well).

A REPLY body is at least 35 bytes; its text must be valid UTF-8 and may be
empty; the existing size limits of a post apply to the whole body. The
reference is authenticated: the post signature covers H(body). Android sends
only text replies. A receiver shows the quoted post by looking up
`targetPostId` among the posts it holds; when the post is missing or deleted it
shows "original unavailable", and nothing about the quoted post is sent. A
REPLY body that is too short or whose text is not valid UTF-8 is shown as an
empty text post. An Android client before version 10 shows a REPLY body as its
raw bytes decoded as text, which is why a sender falls back to a plain text
body (the same text, no reference) unless every member contact it sends to
announces 10 or more.

### Decisions kept with stored posts

The group manager records what it decided about each received post on the
post's stored copy (`groupPostState`):

- **accepted**: the post passed every check (hybrid signature, signer and
  delivering contact are members, epoch window, seen set); it is shown and is
  not checked again when the group is loaded again;
- **pending**: the post cannot be decided yet, because its epoch is more than
  5 ahead of the local epoch, its signer or the contact that delivered it is
  not known as a member yet, or it is more than 1 epoch behind and its signer
  is not known to have been a member at that epoch (its `joinedAtEpoch` is
  later); it is decided again, by the same checks, when the group is next
  loaded. A pending post keeps its arrival time and is due for cleanup when
  the timer in force for it runs out. Pending posts are bounded per
  delivering contact and group (125 posts and 24 MiB; the oldest goes first;
  the count is kept in memory and the chat is read again only when a bound
  is reached);
- **refused**: a post for a group not held here, with a signature that fails,
  a timer that has run out or that the seen set has seen is removed from the
  message store at once, so it neither reappears when the group is loaded
  again nor is checked again. A post is never removed for its epoch alone: a
  post more than 1 epoch behind is accepted when its signer was a member at
  that epoch (the creator always; another member when its `joinedAtEpoch` is
  not later), so a member whose posts arrive after the creator's later
  records loses nothing, and a replay is caught by the seen set.

When a group is loaded, an accepted post of a remote sender with 200 newer
accepted posts of the same sender is removed from the store: the rule that
chooses the posts to show never keeps more of one sender's posts than the
count bound, so it could never be shown again.

A post stored by an earlier version carries no decision; it is checked when it
is chosen for display, as before, and removed if its signature fails.

### Retention of other group records

Group records other than posts (33-38, 41, 43, 44, 46, 47, 48, 50 to 53, 55, 56) are consumed when
they arrive. A received record is kept for 5 minutes, a sent record for 5
minutes after the contact acknowledged it (a record that is never acknowledged
stays queued and is sent again). An invite offer (42) is a conversation card
and is kept like a message, and so is a member invitation offer (49 and 54). What a delete (47) leaves behind on the receiving
device is described under msgType 47. From 3.0.18 each member this device took out by a removal (34) or a leaving (35) leaves its
key and one epoch in the group's settings (`grouptr-order:<groupHex>`, at most 256 members per group, the oldest goes first), until a
33 of the creator at a higher epoch clears it (see msgTypes 34, 35 and 41); the record goes when the group is removed from the
device. Epoch commits that earlier 3.0.18 test builds kept there are deleted when the app starts.

## Wire format - every membership record

All BdfLists, encoded with the existing private-message BDF encoder. Byte counts assume Android's `BdfWriter`.

### 33 - GROUP_MEMBER_ADDED

```
BdfList.of(
    33L,                       // msgType (Long)
    groupId,                   // 32-byte groupId (raw bytes)
    addedPubKey,               // 32-byte Ed25519 pubkey of new member (raw bytes)
    addedName,                 // UTF-8 string (1..256 bytes); not covered by the signature, Android stores it as the display name
    newEpoch,                  // Long, range [0, 2^32-1]
    timestamp,                 // Long, signed
    sig                        // hybrid signature, raw bytes; the validator accepts 1..4096 bytes, the manager accepts only 3373 (see "Signing" below)
)
```

Validator size: exactly **7 slots**.

### 34 - GROUP_MEMBER_REMOVED

```
BdfList.of(
    34L,                       // msgType
    groupId,                   // 32 bytes
    removedPubKey,             // 32 bytes
    fromEpoch,                 // Long, [0, 2^32-1]
    toEpoch,                   // Long, must equal fromEpoch + 1
    timestamp,                 // Long
    sig                        // signature
)
```

Validator size: exactly **7 slots**. `toEpoch == fromEpoch + 1` is enforced.

**Must be paired with msgType 37 (`GROUP_EPOCH_COMMIT`)** on the same outgoing send. Android and iOS send 34 and 37 as two separate messages: 34 to every member including the removed one, 37 to every member except the removed one. The two can arrive in either order, and other records of the creator can arrive between them. The receiver (Android from 3.0.18) applies them as follows; the records are unchanged, so this works with every existing sender:

- Only the creator, as pairwise sender and signer, can remove a member, and never the creator itself. A 34 that any other contact delivers is refused, whatever its epochs.
- A 34 never removes a member whose entry joined at an epoch higher than the 34's fromEpoch: the creator added that member again after this removal. The entry shows this once the device has taken the creator's 33 for that member (also a 33 older than the local epoch, see "State machine - receive") or a snapshot that lists it. A late 34 that arrives before that 33 still removes the member, and the 33 that follows does not add it back: the member returns with the creator's next snapshot.
- A 34 whose toEpoch is higher than the local epoch removes the member and sets the local epoch to toEpoch.
- A 34 whose toEpoch equals the local epoch removes the member and leaves the epoch as it is (a device that applied the 37 first with an earlier version reaches this case).
- A 34 whose toEpoch is lower than the local epoch (a late 34: the creator's later records, or a record of the creator that a member relayed, such as a 38, moved the device on before it arrived) removes the member too and never moves the epoch. It never removes the receiving device itself. Earlier Android versions refused a late 34, so the removal was lost on that device.
- Applying the same 34 again changes nothing.
- A 34 that does not find the member added again records the member with the 34's toEpoch (or keeps a higher epoch already recorded), so a snapshot that still lists that membership does not bring the member back (see msgType 41).
- The 37 of the same step is not needed: the 34 alone removes the member and, as above, moves the epoch when its toEpoch is higher (see msgType 37).

### 35 - GROUP_MEMBER_LEFT

```
BdfList.of(
    35L,                       // msgType
    groupId,                   // 32 bytes
    leavingPubKey,             // 32 bytes (sender's own pubkey)
    newEpoch,                  // Long (the leaver's local epoch + 1)
    timestamp,                 // Long
    sig                        // signature
)
```

Validator size: **6 slots**. Signature is verified against the LEAVING member's pubkey (it's a self-attestation, not an admin action). The pairwise sender is not checked. The record is unchanged in version 8; how a receiver applies it changed:

- A leaving counts only from the member's current membership: one whose
  `newEpoch` is not greater than the epoch the member joined at, or whose
  `timestamp` is more than 5 minutes before the time the member was added
  (`joinedAt`), is a replay and is ignored. A msgType 33 for another member
  already listed moves that entry to the record's epoch and timestamp, so a
  device that takes the new addition before the old removal still refuses
  the old leaving; a device's own entry keeps its epoch until a snapshot
  at a higher epoch replaces the member list (see msgType 41).
- **Version 8, creator on version 8**: the leaver is taken out of the member
  list and the shared epoch does **not** move. From 3.0.18 the receiver
  records the leaver with the lower of the 35's epoch and the local
  epoch, so a snapshot that still lists that membership does not add it
  back, while the creator, which adds a member again only above the 35's
  epoch, can (see msgType 41). This holds also for an old genuine 35
  that a member relays after the device moved on. From 3.0.18 a
  device that joined through a member's invitation (msgTypes 49 and 50)
  treats its creator as on version 8 or later even when the creator is not
  its contact, since member invitations need the creator on version 10. A
  member's own record cannot
  advance the epoch, which only the creator's records do, so a leaving sent to
  some devices only cannot put them ahead of the others (where they would
  refuse the creator's next records and drop the others' posts). The creator
  confirms the leaving with its own msgType 34 + 37 for the leaver
  (k to k+1), sent to the remaining members, which moves every device to the
  next epoch together. The creator also confirms a leaving signed at an
  older epoch, so a member that left while behind is still taken out.
- **Version 8, creator on an earlier version** (or not a contact, unless
  the device joined through a member's invitation): the
  creator does not confirm and moves its own epoch on the leaving, so the
  receiver keeps the earlier behaviour (epoch moves to
  `min(newEpoch, local + 1)` when `newEpoch` is higher than the local epoch),
  to stay in step with it. From 3.0.18 it records the leaver with the
  lower of the 35's epoch and that new local epoch, as above. In such a
  group a member can still put a chosen
  device one epoch ahead per member that leaves; this ends once the creator
  updates.
- **Earlier versions** receiving the creator's confirmation after having
  moved their epoch on the leaving refuse both records as stale and stay at
  the same epoch as the creator.

### 36 - GROUP_DISSOLVED

```
BdfList.of(
    36L,                       // msgType
    groupId,                   // 32 bytes
    newEpoch,                  // Long
    timestamp,                 // Long
    sig                        // signature - must be the CREATOR's key
)
```

Validator size: **5 slots**. Creator-only on Android receiver; admins cannot dissolve.

### 37 - GROUP_EPOCH_COMMIT

```
BdfList.of(
    37L,                       // msgType
    groupId,                   // 32 bytes
    fromEpoch,                 // Long
    toEpoch,                   // Long (== fromEpoch + 1)
    pqSeed,                    // 32 random bytes (validator accepts 1..4096); covered by the signature as a hash, not used to derive any key
    sig                        // signature
)
```

Validator size: **6 slots**. Sent only together with msgType 34 (33, 35, 36 and 38 also advance the epoch, without a 37). The pqSeed is hashed under label `"org.zerionproject/GROUP_EPOCH_SEED"` into the signed-input. The record has no timestamp slot: the timestamp in its signed input is the timestamp of the enclosing private message (the sender uses the same value for both).

Receiver (Android from 3.0.18): a 37 changes nothing and is not kept. The 34 of the same step carries the removal and, when its toEpoch is higher than the local epoch, moves the epoch, in whichever order the two arrive (see msgType 34), so the epoch never moves without the removal. Android up to 3.0.17 moved the epoch on a 37 whose fromEpoch equalled the local epoch, which is why senders still send it.

### 38 - GROUP_MEMBER_ROLE_CHANGED

```
BdfList.of(
    38L,                       // msgType
    groupId,                   // 32 bytes
    targetPubKey,              // 32 bytes
    newRole,                   // Long: 0 = MEMBER, 1 = ADMIN, 2 = CREATOR (never sent - creator role is fixed)
    epoch,                     // Long
    timestamp,                 // Long
    sig                        // signature - CREATOR ONLY
)
```

Validator size: **7 slots**. Creator-only on Android receiver. `newRole` must be in `[0, 2]`. The ADMIN role grants nothing on Android; from 3.0.18 the Android app no longer sends 38 (it offers no way to assign the role), and a receiver still applies a 38 from the creator as before.

### 41 - GROUP_MEMBER_LIST_SNAPSHOT

```
BdfList.of(
    41L,                       // msgType
    groupId,                   // 32 bytes
    epoch,                     // Long
    timestamp,                 // Long
    memberList,                // BdfList of BdfLists, see below
    sig                        // signature
)
```

Validator size: **6 slots**. Each entry in `memberList` is:

```
BdfList.of(
    pubKey,        // 32 bytes
    name,          // UTF-8, 0..256 bytes; not signed, ignored by Android
    joinedAt,      // Long >= 0 (timestamp); not signed, ignored by Android
    joinedAtEpoch, // Long, [0, 2^32-1]
    role           // Long, 0..2
)
```

Max 256 members per snapshot. From 3.0.18 Android sends `""` as every name
and `0` as every `joinedAt`, so a snapshot no longer tells every member the
names the creator holds for the others or when each joined; every receiver
since 3.0.0 ignores both fields, and a receiver must not rely on them.

### When to send a snapshot

A creator MUST fan out a fresh `GROUP_MEMBER_LIST_SNAPSHOT` (msgType 41)
in two cases:

1. **Manual repair** - the creator may call `sendMemberListSnapshot(groupId)`
   to recover members whose local state has diverged from the creator's
   (the Android app has no UI for this).
2. **Immediately after a successful `addMember` driven by an invite
   ACCEPT response** (2026-06-09 update). On the creator's device,
   when handling an inbound `GROUPTR_INVITE_ACCEPT` (msgType 43):
   - Call `addMember(groupId, responderPubKey, responderName)` first.
   - On success, immediately call `sendMemberListSnapshot(groupId)`.

The second case reconciles an invitee whose local state was created from
the invite. The creator sends the msgType-33 record at the new epoch E+1
and then the snapshot at the same epoch E+1, so a receiver that has
already applied the 33 is at epoch E+1 when the snapshot arrives. A
receiver therefore applies a snapshot at a higher epoch and, in a
limited form, a snapshot at its own epoch:

- **Higher epoch.** A receiver applies msgType 41 if the group exists,
  is not dissolved, the snapshot's epoch is strictly higher than the
  local epoch, the list has at most 256 entries and the signature
  verifies against the creator's keys. It replaces the member list;
  names, joinedAt and ML-DSA keys come from the previous local entry or
  the contact list, not from the snapshot; and it sets the local epoch
  to the snapshot's epoch. From 3.0.18 it leaves out a member that this
  device recorded as removed or left (see below).
- **Equal epoch (Android from 3.0.18).** A snapshot whose epoch equals
  the local epoch passes the same checks (group exists and is not
  dissolved, at most 256 entries, creator's signature; the contact that
  delivers it is not checked) and must list the receiving device itself;
  the creator's own device never takes one. It can only add: every
  listed member that the local list lacks is added with the role and
  joinedAtEpoch the snapshot gives, as long as the list holds fewer than
  256 members, except a member this device recorded as removed or left.
  It never removes a member, never removes the receiving device, never
  changes the role, joinedAtEpoch or name of a listed member, and never
  changes the epoch or the group permissions. Removals and role changes
  always come with a higher epoch.
- **Members that left or were removed (Android from 3.0.18).** The
  device records each member it takes out with one epoch: the toEpoch of
  the removal (34), or for a leaving (35) the lower of the 35's epoch and
  the local epoch once the leaving is applied. A snapshot entry for that
  member whose joinedAtEpoch is at or below that epoch belongs to the
  membership that ended, and both kinds of snapshot leave it out, so no
  snapshot brings back a member that left or was removed (the device
  keeps the newest 256 such records per group). The creator adds a member
  again only above that epoch, so a snapshot that lists it with a higher
  joinedAtEpoch adds it. A creator's 33 for the member clears a record
  below the 33's epoch, also when the 33 is not newer than the local
  epoch.

With these rules a new member learns every member from the snapshot of
its own addition, also when it applied the creator's next addition (a
33 at a later epoch) before that snapshot arrived: the stale snapshot is
refused and the next one, at the device's epoch, adds the members it
lacks. A snapshot at an epoch lower than the local epoch is refused.
Android 3.0.15 to 3.0.17 applied an equal-epoch snapshot only when it
was the first snapshot after the device's own addition and listed the
device at that epoch, and then replaced the list; earlier versions
refused every equal-epoch snapshot.

A snapshot at a higher epoch that passes these checks but does not list
the receiver itself, on a device that holds itself as a confirmed member (epoch above
0, listed in its own member list and not waiting to be admitted, see
msgType 50), removes the group from the device exactly as a msgType 34
naming the receiver does, and the device shows that it was removed. This
is how an invitee that is not the creator's contact learns of its
removal, since it never receives the creator's 34. A device still
waiting to be admitted, or in the bootstrap state of 42, applies such a
snapshot as before and is not removed by it.

From version 10 a member also passes a creator's snapshot on, unchanged, to a
contact it invited and whose acceptance it sent to the creator, once the
snapshot lists that contact (see "Member invitations"). The creator does not
send a third kind of snapshot for this: it is the snapshot of case 2.

### 46 - GROUP_SETTINGS

```
BdfList.of(
    46L,                       // msgType
    groupId,                   // 32 bytes
    timerMs,                   // Long: 0 (off) or 60,000 .. 31,536,000,000
    timestamp,                 // Long: when the creator chose the setting
    sig                        // hybrid signature of the CREATOR
)
```

Validator size: exactly **5 slots**; a timer outside the range of a
conversation timer is refused. Signed input
`[32B groupId][8B BE timerMs][8B BE timestamp][0x08]` (49 bytes) under the
label `org.zerionproject/GROUP_SETTINGS`.

The creator sends the record to every member on version 8 when it sets the
timer, to a member it adds while a timer is set, and to a member whose
announced messaging version reaches 8 after the timer was set or whose
version was not yet known when it was set (each re-signed over the same
timestamp, so every copy orders the same and a member that holds it drops the
copy as not newer). A timer set under an earlier version has no timestamp and
was never sent; the first start of version 8 dates it with the current time
and sends it. A receiver applies the record only for a live group, only when
the pairwise sender is the creator, only when its timestamp is newer than the
settings it holds, and only when the creator's hybrid signature verifies. The
timer then applies to the posts the member sends (when they carry none or a
longer one) and to the posts it receives, counted from their arrival.

### 47 - GROUP_POST_DELETE

```
BdfList.of(
    47L,                       // msgType
    groupId,                   // 32 bytes
    epoch,                     // Long, [0, 2^32-1]: the author's epoch when it deleted
    senderPubKey,              // 32 bytes: the author's Ed25519 key, as slot 3 of msgType 32
    targetPostId,              // 32 bytes: the post identity (see "Post identity")
    timestamp,                 // Long: milliseconds, when the author deleted
    sig                        // hybrid signature of the author, exactly 3373 bytes
)
```

Validator size: exactly **7 slots**, and the record must end the message.
groupId, senderPubKey and targetPostId must be exactly 32 bytes, epoch must be
in `[0, 2^32-1]`, timestamp must be a Long and sig must be exactly 3373 bytes;
anything else is refused as invalid. The validator stores metadata only
(`groupId`, `groupEpoch`, `groupSenderPubKey`, `groupTargetPostId`,
`groupRecordTimestamp`, `groupRecordSig`); there is no content to store.

Signed input (109 bytes) under the label `org.zerionproject/GROUP_POST_DELETE`:

```
[32B groupId][4B BE epoch][32B senderPubKey][32B targetPostId][8B BE timestamp][0x09]
```

The label differs from the post label `org.zerionproject/GROUP_POST`, so a
delete signature can never be taken for a post signature or the other way
round.

A receiver applies the record only when all of the following hold, and
otherwise ignores it without a trace:

1. the group is held and not dissolved;
2. the pairwise contact that delivered the record is a member or the creator,
   and so is `senderPubKey` (the same rule as for a post);
3. the Ed25519 and ML-DSA-65 keys of `senderPubKey` are known (a member entry,
   the local identity or a contact);
4. both halves of `sig` verify over the signed input (the same rule as for
   every group record, see "Verification");
5. the target post's signer is `senderPubKey`: only the author of a post can
   delete it for everyone. A record signed by anyone else never removes the
   post, also not when it arrives before the post.

Applying it removes every stored copy of the post (whatever its decision
state), the post held in memory and any copy held for a later epoch, fixes the
group's unread count when the post was unread, and tells an open group screen
and the notifications to drop it. A post counts as unread when it was accepted
after the time the group was last read (settings key `readAt` in
`grouptr-removed:<groupHex>`). A device that holds no read time for the group
(a group last read before 3.0.18 and not opened since) takes the moment its
unread count rises from zero as the read time, since every post accepted
before that moment was read; while it still holds no read time, a delete does
not lower the count, which then stays too high until the group is opened
rather than hiding a post that is unread. If no copy of the post is held yet,
the delete waits, each waiting delete kept for 7 days: at most 125 waiting
deletes per author (a new one pushes out that author's oldest) and 500 per
group (when the group is full, the oldest waiting delete of the author holding
the most gives way), so one member's deletes cannot push out the waiting
deletes of members holding fewer. A post that arrives while its author's delete
waits, or within 7 days after a delete of it was applied (at most 500 applied
deletes per group), is removed on arrival, before its signature is checked,
and is never shown. A delete for a post that is already deleted changes
nothing. Epoch and timestamp are covered by the signature but not otherwise
checked: replaying an author's delete can only withdraw that author's own
post again.

Sender: only the author can delete for everyone, and only while it is a
member of a live group. It sends one record per post to every member contact
that announces version 10 or more (the same pairwise fan-out as a post,
including the offline mesh path) and removes every local copy of the post at
once, also the copies still queued for members, which are then never sent.
Members that announce an earlier version, or whose version is not known, are
not sent the record and keep the post; `deletePostsForEveryone` returns how
many such member contacts there are and the group screen says so. The author
remembers its own deletes for 7 days (at most 500 per group, settings key
`own` in the namespace `grouptr-removed:<groupHex>`, entries
`<targetPostIdHex>.<epoch>_<timestamp>.<keptUntil>`); when a member contact
announces messaging minor version 10 or later inside that time, the author
sends it each remembered delete again, re-signed over the same epoch,
target and timestamp, so the record is identical in content. Members that are not
the author's contacts never received the post. The sent records are kept like
other group records: queued until the member acknowledges them, then removed 5
minutes later.

Delete for me removes every local copy of the selected posts and sends
nothing; a copy of such a post that arrives again within 7 days is dropped.

## Invite handshake (msgTypes 42 / 43 / 44, added 2026-05)

The invite handshake is a separate layer from the membership records. It is an
explicit OFFER → ACCEPT|DECLINE exchange over the same pairwise messaging
channel. All three records carry `MSG_KEY_LOCAL=false`. On the Android receiver
the validator (`PrivateMessageValidator.validateGrouptrInviteOffer` /
`validateGrouptrInviteResponse`) only checks structure + field lengths; the
signature is verified later by `GroupTrManagerImpl.handleGrouptrInviteOffer` /
`handleGrouptrInviteResponse`.

Flow:

1. Creator calls `inviteContactToGroup(...)`, which builds and sends a msgType-42
   OFFER to the invited contact and records a pending "invite sent" entry.
   It refuses (`GROUP_FULL`) while the group lists 256 members
   (`MAX_GROUP_MEMBERS`), the most a snapshot may carry, and `addMember`
   refuses a new member then as well, so the creator's own add never makes
   the group's snapshots invalid.
2. Invitee's `handleGrouptrInviteOffer` verifies the OFFER signature against the
   creator's pubkey, re-derives the groupId from `(creatorName, creatorPubKey,
   groupName, salt)` and checks it matches, applies freshness bounds
   (max age 7 days, max future skew 24 hours), then stores a pending
   "invite received" entry. No group state is materialised yet. An offer for
   a group the invitee already holds is admitted when that group is still in
   its bootstrap state (epoch 0, the creator and the invitee as its only
   members, the invitee joined at epoch 0): such a group is the remains of an
   earlier accept the creator never confirmed, and accepting the new offer
   replaces it. A group the creator has moved on (any record applied) is not
   offered again.
3. Invitee calls `acceptInvite(...)` (sends msgType-43 ACCEPT and materialises
   local group state) or `declineInvite(...)` (sends msgType-44 DECLINE).
   `acceptInvite` checks the offer's freshness again, so an offer left open
   on the invitation card cannot be accepted after it ran out; it is then
   dropped and refused with `INVITE_EXPIRED`.
4. Creator's `handleGrouptrInviteResponse` verifies the response signature
   against the responder's pubkey. The pending "invite sent" entry records
   when the invite was sent. An answer is taken when, by its own signed
   timestamp, it was given while the invite was open (within 7 days of the
   invite, with 24 hours of clock skew allowed either way), it is not dated
   more than 24 hours ahead of the creator's clock, and the invite is still
   kept: the creator keeps an invite for 7 days plus a grace period of 7
   more days, so an answer that waited for the creator to come online is
   not lost. The creator can list the kept invites (`getSentInvites`) and
   revoke one (`revokeInvite`); a revoked invite's answer is ignored, and
   the invitee is not told. On ACCEPT it calls
   `addMember(groupId, responderPubKey, responderName)` then
   `sendMemberListSnapshot(groupId)` (this is the snapshot trigger documented
   above). On DECLINE it simply clears the pending "invite sent" entry.
   Invites sent by an earlier version carry no time; they are dated when the
   database is opened, before any answer or screen can see them, so they run
   out 7 days after the upgrade.

### 42 - GROUPTR_INVITE_OFFER

```
BdfList.of(
    42L,                       // msgType (Long)
    grouptrGroupId,            // 32-byte raw groupId
    groupName,                 // UTF-8 string, 0..100 bytes
    salt,                      // 32-byte raw group salt
    creatorName,               // UTF-8 string, 1..MAX_AUTHOR_NAME_LENGTH bytes
    creatorPubKey,             // 32-byte raw Ed25519 pubkey of the creator
    timestamp,                 // Long
    sig                        // signature, raw bytes (length 1..4096)
)
```

Validator size: exactly **8 slots**. The validator enforces:
groupId == 32 B, groupName 0..100, salt == 32 B, creatorName length in
`[1, MAX_AUTHOR_NAME_LENGTH]`, creatorPubKey == 32 B, sig length in `[1, 4096]`.
On the manager side the OFFER is rejected unless the pairwise sender's pubkey
equals `creatorPubKey` and the locally-derived groupId matches.

### 43 - GROUPTR_INVITE_ACCEPT

```
BdfList.of(
    43L,                       // msgType (Long)
    grouptrGroupId,            // 32-byte raw groupId
    timestamp,                 // Long
    sig                        // signature, raw bytes (length 1..4096)
)
```

Validator size: exactly **4 slots**. groupId == 32 B, sig length in `[1, 4096]`.

### 44 - GROUPTR_INVITE_DECLINE

Identical wire shape to ACCEPT, only the leading msgType differs:

```
BdfList.of(
    44L,                       // msgType (Long)
    grouptrGroupId,            // 32-byte raw groupId
    timestamp,                 // Long
    sig                        // signature, raw bytes (length 1..4096)
)
```

Validator size: exactly **4 slots**, same field checks as ACCEPT. ACCEPT and
DECLINE share the `validateGrouptrInviteResponse` path; only the signing label
and the manager-side handling differ.

### Invite signed-input (all three: OFFER / ACCEPT / DECLINE)

All three records sign over the **same field layout**, produced by
`offerSignedInputBound(groupId, keyA, keyB, timestamp, groupName, salt,
creatorName)`:

```
[32B groupId]
[32B keyA]                 // BE-ordered as written by ByteBuffer.put
[32B keyB]
[8B BE timestamp]          // ByteBuffer.putLong - big-endian
[4B BE groupName length][groupName UTF-8 bytes]
[4B BE salt length][salt bytes]
[4B BE creatorName length][creatorName UTF-8 bytes]
```

The two 32-byte key slots carry different roles depending on direction:

- **OFFER (signer = creator):** `keyA = creatorPubKey`, `keyB = invitee's
  contactPubKey`. Signed with label
  `org.zerionproject/GROUPTR_INVITE_OFFER`.
- **ACCEPT / DECLINE (signer = invitee):** `keyA = responder's own pubkey`,
  `keyB = creatorPubKey`. The creator re-builds the identical bytes with
  `keyA = responderPub`, `keyB = creatorPubKey` to verify. Signed with label
  `org.zerionproject/GROUPTR_INVITE_ACCEPT` or
  `.../GROUPTR_INVITE_DECLINE` respectively.

Note the length fields are 4-byte big-endian (`ByteBuffer.putInt`) prefixes on
the three variable-length UTF-8 / raw fields, and `timestamp` is the 8-byte
big-endian Long (`ByteBuffer.putLong`). The signature itself follows the same
hybrid Ed25519 + ML-DSA-65 `signOrThrow` pattern as the membership records
(see "Signing" below), so `sig` is always 3373 bytes.

## Member invitations (msgTypes 48 to 52, version 10)

From messaging minor version 10 (`MessagingManager.GROUP_ACTIONS_MIN_VERSION`)
the creator can let members invite their own contacts. It is off for every
group, existing and new, until the creator turns it on. Membership itself does
not change: only the creator's device ever signs membership records (33 to 38,
41), and a contact a member invites becomes a member only when the creator's
device adds it, which it does by itself while the setting is on. Joining
therefore completes once the creator's phone is online.

There is no relay. Each record below travels over the pairwise messaging
channel between the two devices named, like every other GroupTr record.

### Flow

Roles: C is the creator, M a member who has C as a contact, X a contact of M
who need not be C's contact.

1. C turns the setting on. C's device signs a GROUP_PERMISSIONS record (48)
   with bit 0 set and sends it to every member whose contact record announces
   version 10 or later. M keeps it, including C's signature.
2. M invites X. M's device sends X a GROUPTR_MEMBER_INVITE_OFFER (49) that
   carries what X needs to verify the group and later to verify C's records:
   the group's name, salt and id, C's name, C's Ed25519 key and C's ML-DSA-65
   key, and C's signed permission record, all under M's hybrid signature. X
   sees an invitation card in its chat with M.
3. X accepts (50) or declines (51); the answer goes to M. On accepting, X's
   device holds the group in a joining state (below) and cannot post yet.
4. On an acceptance, M's device sends C a GROUPTR_JOIN_REQUEST (52) that
   carries X's key, the name M knows X by, X's ML-DSA-65 key and X's signed
   acceptance, under M's hybrid signature.
5. C's device checks the request (below) and, if every check passes, runs
   `addMember(X)` (msgType 33 to the members it can reach, and to X if X is
   C's contact) and `sendMemberListSnapshot` (msgType 41). If the setting is
   off or any check fails, nothing happens and nothing is answered.
6. M receives C's snapshot that lists X and sends X an exact copy of that
   record (the same bytes, with C's signature). X applies it under the
   snapshot rules of msgType 41 and is a member.

After that, X's posts reach the members that are X's contacts, as for any
member; members that are not X's contacts are shown as out of reach.

### 48 - GROUP_PERMISSIONS

```
BdfList.of(
    48L,                       // msgType
    groupId,                   // 32 bytes
    flags,                     // Long >= 0; bit 0 (value 1) = MEMBERS_MAY_INVITE
    timestamp,                 // Long: when the creator chose the setting
    sig                        // hybrid signature of the CREATOR
)
```

Validator size: exactly **5 slots**; a negative `flags` is refused; `sig`
length in `[1, 4096]` (the manager accepts only 3373). Signed input:

```
[32B groupId][8B BE flags][8B BE timestamp][0x09]
total: 49 bytes
label: org.zerionproject/GROUP_PERMISSIONS
```

Sending mirrors msgType 46. The creator sends only the bits it defines (today
0 or 1). Each change is dated `max(now, previous timestamp + 1)`, signed once
and stored with its signature. The creator sends the stored record, unchanged,
to every member on version 10 when the setting changes, to a member it adds
while a record exists (enabled or disabled), and to a member whose announced
version reaches 10 later. Unlike 46 the record is not signed again for each
copy. A group without a record, which includes every group created before
version 10, has the flags 0: members may not invite.

A record is taken in an invitation for at most 14 days
(`PERMISSIONS_MAX_AGE_MS`, the same 14 days as `JOIN_WINDOW_MS`), so a
member that kept an old record after the creator turned the setting off can
use it only that long. While the setting is on, the creator therefore signs
the record again, with the same flags and a new timestamp
(`max(now, previous timestamp + 1)`), once the record it holds is 7 days old
or older (`PERMISSIONS_REFRESH_MS`): when the database is opened, when the
creator posts to the group and when it adds a member. The new record is sent
like a change, to every member on version 10. A creator that runs none of
these for more than 14 days leaves its members unable to invite until it
does; its device would not have admitted their invitees within the 14 days
of `JOIN_WINDOW_MS` either.

A receiver applies the record only for a live group, only when the pairwise
sender is the creator, only when its timestamp is strictly newer than the
record it holds, and only when the creator's hybrid signature verifies. It
keeps the flags as received, unknown bits included, and acts on bit 0 only.
It keeps the signature too, because a member puts the record into its
invitations. From version 11 a member that the group-wide switch does not
cover may hold its own grant instead (msgType 53).

### 49 - GROUPTR_MEMBER_INVITE_OFFER

```
BdfList.of(
    49L,                       // msgType
    groupId,                   // 32 bytes
    groupName,                 // UTF-8 string, 0..100 bytes
    salt,                      // 32 bytes
    creatorName,               // UTF-8 string, 1..50 bytes (MAX_AUTHOR_NAME_LENGTH)
    creatorPubKey,             // 32 bytes, the creator's Ed25519 key
    creatorMlDsaPubKey,        // 1952 bytes, the creator's ML-DSA-65 key
    inviterPubKey,             // 32 bytes, the inviting member's Ed25519 key
    permFlags,                 // Long >= 0, the flags of the creator's record 48
    permTimestamp,             // Long, the timestamp of that record
    permSig,                   // that record's hybrid signature, 1..4096 bytes
    timestamp,                 // Long, when the member made the offer
    sig                        // the inviting member's hybrid signature, 1..4096 bytes
)
```

Validator size: exactly **13 slots**, with the lengths above; a negative
`permFlags` is refused. The validator stores the offer as an unread
conversation card (`MSG_KEY_READ = false`, the group name, salt, creator name
and key and the inviter's key in the metadata); it is kept like a message, not
consumed.

Signed input, signer = the inviting member M:

```
[32B groupId]
[32B inviterPubKey]
[32B inviteePubKey]                 // X's Ed25519 key; not on the wire, X uses its own key
[32B creatorPubKey]
[8B BE timestamp]
[4B BE length][groupName UTF-8 bytes]
[4B BE length][salt]
[4B BE length][creatorName UTF-8 bytes]
[4B BE length][creatorMlDsaPubKey]
[8B BE permFlags]
[8B BE permTimestamp]
[4B BE length][permSig]
label: org.zerionproject/GROUPTR_MEMBER_INVITE_OFFER
```

The member sends an offer only when it is a listed member and not the
creator, is not itself waiting to be admitted, holds a permission record with
bit 0 set that is not more than 14 days old and not more than 24 hours ahead
of its clock, its member list holds fewer than 256 members, it has the
creator as a contact that announces version 10, and the invited contact
announces version 10, is not a member and is not the creator.
`creatorMlDsaPubKey` comes from the member's contact record of the creator.
A member keeps at most 64 open invitations per group; an invitation stays
open for 14 days.

The invitee admits an offer, and shows it as acceptable, only when all of these
hold:

- its timestamp is not more than 24 hours ahead of the invitee's clock and
  not more than 7 days old (as for 42);
- the pairwise sender's Ed25519 key equals `inviterPubKey`, and the inviter
  is not the creator (the creator invites with 42);
- the invitee is neither the creator nor the inviter;
- bit 0 of `permFlags` is set;
- `permTimestamp` is not more than 24 hours ahead of the invitee's clock and
  not more than 14 days old (`PERMISSIONS_MAX_AGE_MS`, see msgType 48);
- `groupId` re-derives from `(creatorName, creatorPubKey, groupName, salt)`
  exactly as for 42;
- if the invitee already knows an ML-DSA-65 key for `creatorPubKey` (a contact
  record or a stored group member entry), it equals `creatorMlDsaPubKey`;
- the invitee does not hold the group, or holds it dissolved or without
  itself in the member list, or holds it in the bootstrap state of 42, or in
  a joining state the creator never confirmed (epoch 0); accepting the new
  offer then replaces that state;
- no other member invitation for the group is pending on the device;
- the inviting member's hybrid signature verifies under the keys of the
  invitee's contact record of the member;
- the permission record verifies, under label
  `org.zerionproject/GROUP_PERMISSIONS` and the signed input of msgType 48,
  with `creatorPubKey` and `creatorMlDsaPubKey` from the offer.

At most 64 member invitations are kept pending; each runs out after 7 days.

Only the first admitted offer for a group is kept pending; a later offer of
another member for the same group still shows its card, but that card has no
offer behind it. Accepting or declining a card therefore names the contact
whose chat shows it, and the device answers only when the pending offer came
from that contact. A card of another inviter, or a card whose offer is no
longer pending (expired, answered or never admitted), is refused with
`MEMBER_INVITES_UNAVAILABLE`, nothing is sent, and the card says the
invitation is no longer available.

### 50 - GROUPTR_MEMBER_INVITE_ACCEPT and 51 - GROUPTR_MEMBER_INVITE_DECLINE

```
BdfList.of(
    50L,                       // msgType; 51L for a refusal
    groupId,                   // 32 bytes
    inviterPubKey,             // 32 bytes, the inviting member's Ed25519 key
    timestamp,                 // Long
    sig                        // the invitee's hybrid signature, 1..4096 bytes
)
```

Validator size: exactly **5 slots**. Signed input, signer = the invitee X:

```
[32B groupId]
[32B inviteePubKey]                 // X's Ed25519 key
[32B inviterPubKey]                 // M's Ed25519 key
[32B creatorPubKey]
[8B BE timestamp]
[4B BE length][inviteeMlDsaPubKey]  // X's ML-DSA-65 key, 1952 bytes
total: 2092 bytes
label: org.zerionproject/GROUPTR_MEMBER_INVITE_ACCEPT (50)
       org.zerionproject/GROUPTR_MEMBER_INVITE_DECLINE (51)
```

The signed input binds X's ML-DSA-65 key as well, so the key the join request
carries is covered by X's own signature and not only by the member's.

On accepting, the invitee checks the offer's age again, sends 50 to the
member, and holds the group in a **joining state**: epoch 0; members the
creator (role CREATOR, with the ML-DSA-65 key from the offer), the inviting
member (MEMBER, with the ML-DSA-65 key of the invitee's contact record) and
itself (MEMBER), each with `joinedAtEpoch = 0`; and a local mark naming the
inviting member. While the mark is set and the epoch is 0, or the invitee is
not in its own member list, the invitee is waiting for the creator: it refuses
to post (`NOT_A_MEMBER`), the group screen says it waits for the creator's
phone, and every group post it receives, also from the inviting member, is
held as pending: stored, not shown and not counted as unread. The state ends
when a creator-signed snapshot that lists the invitee moves the group past
epoch 0 (or, if the invitee is the creator's contact, the creator's msgType
33 does); applying a snapshot that lists the invitee also clears the mark.
Held posts are then decided again under the usual rules the next time the
group's posts are loaded. The creator takes an acceptance for at most 14 days
(`JOIN_WINDOW_MS`), so a joining state the creator has not confirmed after
that can never complete: it is removed from the device when the database is
opened and when the group screen asks whether it is still waiting.

The inviting member takes an answer only when `inviterPubKey` is its own key,
the pairwise sender is the contact it invited to that group and the invitation
is still open, the answer's timestamp is timely by the rule of 43 (given
while the invitation was open, with 24 hours of skew either way, not more than
24 hours ahead), and the signature verifies under the invitee's Ed25519 key
and the ML-DSA-65 key of the member's contact record of the invitee. A refusal
ends the invitation. An acceptance makes the member send record 52 to the
creator if it may still invite (as for sending 49); otherwise the invitation
ends.

### 52 - GROUPTR_JOIN_REQUEST

```
BdfList.of(
    52L,                       // msgType
    groupId,                   // 32 bytes
    inviteePubKey,             // 32 bytes
    inviteeName,               // UTF-8 string, 1..50 bytes: the member's contact name for the invitee
    inviteeMlDsaPubKey,        // 1952 bytes
    acceptTimestamp,           // Long, the timestamp of the invitee's record 50
    acceptSig,                 // the invitee's signature of record 50, 1..4096 bytes
    timestamp,                 // Long, when the member sent the request
    sig                        // the inviting member's hybrid signature, 1..4096 bytes
)
```

Validator size: exactly **9 slots**, with the lengths above. The inviting
member is the pairwise sender; it is not repeated in the record. Signed input,
signer = the inviting member M:

```
[32B groupId]
[32B inviterPubKey]                 // M's Ed25519 key
[32B inviteePubKey]
[8B BE acceptTimestamp]
[8B BE timestamp]
[4B BE length][inviteeName UTF-8 bytes]
[4B BE length][inviteeMlDsaPubKey]
[4B BE length][acceptSig]
label: org.zerionproject/GROUPTR_JOIN_REQUEST
```

The creator's device acts on a request by itself, with no user action, and only
when all of these hold:

- the group is live and the device is its creator;
- the pairwise sender is a listed member and not the creator;
- the invitee is neither the creator nor that member, and is not listed;
- the group lists fewer than 256 members (`MAX_GROUP_MEMBERS`, the most a
  snapshot may carry); a request refused for this reason is not used up and
  is taken if it arrives again once there is room;
- the creator's own permission record has bit 0 set, or (version 11) the
  creator's own grant for that member (msgType 53) has bit 0 set;
- the creator has not removed the invitee: the creator's device remembers
  the last 256 members it removed from the group (settings key `removed` in
  `grouptr.mi.<groupHex>`, oldest dropped first) and refuses a member's
  request for any of them until the creator adds that person itself, which
  ends the entry;
- `acceptTimestamp` and `timestamp` are each not more than 24 hours ahead of
  the creator's clock and not more than 14 days old (7 days of invitation plus
  7 days for the creator to come online);
- if the creator has the invitee as a contact with an ML-DSA-65 key, it
  equals `inviteeMlDsaPubKey`;
- the member's hybrid signature verifies under the member's keys;
- `acceptSig` verifies as an acceptance (label and signed input of 50) with
  `inviteePubKey` and `inviteeMlDsaPubKey`, the pairwise sender as the
  inviting member and the creator's own key as `creatorPubKey`;
- no acceptance of the same invitee with this or a later `acceptTimestamp` was
  used in this group within the last 14 days (the creator remembers at most
  512 per group and refuses new invitees while that store is full);
- fewer than 20 contacts of that member were added in the member's current
  24 hour window.

It then runs `addMember(groupId, inviteePubKey, inviteeName)`, keeping
`inviteeMlDsaPubKey` in the new member entry, and `sendMemberListSnapshot`.
In every other case nothing happens and no answer is sent.

### The creator's snapshot passed on to the invitee

The invitee is usually not the creator's contact and so receives no record
from the creator. The inviting member passes the creator's snapshot on: when
it receives any msgType 41 for a group it holds that lists a contact whose
acceptance it sent to the creator, it verifies the creator's signature and
sends the record unchanged (the same bytes and signature) to that contact,
then ends the invitation. It does so whether or not it applied the snapshot
itself (it usually has already applied the creator's 33 at the same epoch and
refuses the snapshot as not newer). The invitee applies it under the existing
rules of 41: the sender is not checked, the epoch is higher than the joining
state's 0, and the signature verifies under the creator's keys, the ML-DSA-65
key being the one from the offer.

### Trust model

- Only the creator's device signs membership records, the permission
  record and the grants (53); the member invitation records give no member any
  authority over the member list, and a grant only lets a member ask. The
  creator's device decides each addition itself, and no member can remove
  anyone.
- The invitee cannot check the creator's ML-DSA-65 key on its own unless it
  has the creator as a contact; it takes the key on the inviting member's
  signature. A dishonest member can therefore make its invitee wait for a
  creator that never adds it, or give it a wrong ML-DSA-65 key for the
  creator; with that key the invitee would refuse the creator's real records,
  and an attacker who can also forge Ed25519 could present records the
  invitee accepts.
- The creator likewise takes the invitee's ML-DSA-65 key from the member's
  signature and the invitee's own acceptance, unless the invitee is its
  contact, in which case it must match the contact record. The invitee's name
  is the member's word, as the name in 33 is the creator's.
- The member must have the creator as a contact (the request has to reach
  the creator directly). An invitee that joined through a member cannot
  invite further unless it is also the creator's contact; second-degree
  invitations are not supported.
- An invitee that is not the creator's contact learns later membership
  changes through the member that invited it. That member remembers each
  invitee it admitted (settings key `admitted.<inviteeHex>` in
  `grouptr.mi.<groupHex>`, at most 256 per group) and the epoch of the last
  snapshot it passed on (`forwardedEpoch`). Every creator-signed snapshot
  (41) with a higher epoch that the member receives is passed on unchanged
  to each remembered invitee, also when the member's own epoch already
  equals it; an invitee that a snapshot no longer lists is sent that
  snapshot, which tells it it was removed, and is then forgotten. The
  invitee then removes the group from its device, as for a msgType 34
  naming it (see msgType 41). A
  dissolution (36) the member receives is passed on unchanged, rebuilt from
  its fields (`[36, groupId, epoch, timestamp, sig]`), before the member
  removes the group. In a group where member invitations were ever
  configured (the creator holds a permissions record), the creator sends a
  snapshot after removing a member, so the removal reaches invitees this
  way; additions already end with a snapshot. The invitee takes these
  records under the existing rules (signer the creator, sender not checked;
  a snapshot at a strictly higher epoch replaces the list, one at the
  invitee's own epoch can only add members, see msgType 41). The invitee
  still receives no 46 or 48,
  and nothing reaches it once its inviting member has left the group.

### Version gate, older clients and iOS

Every one of the records 48 to 52 is sent only to a contact whose messaging
client announces minor version 10 or later; the forwarded snapshot is a
msgType 41, which every version understands. A client before version 10
refuses 48 to 52 as unknown types, so it is never sent one: a creator before
version 10 never sends 48 and its members cannot invite, a member before
version 10 is not sent 48, and a contact before version 10 cannot be invited
by a member (the contact picker shows it as needing a newer version).

iOS: Android sends none of these records to an iOS client until it announces
version 10. An iOS client that announces 10 must implement, for its roles:
receiving and keeping 48; showing and admitting 49 by the rules above;
sending 50 or 51 and holding the joining state; as a member, sending 49,
taking 50 and 51, sending 52 and passing the creator's snapshot on; as a
creator, the permission switch, sending 48, signing it again before it is
14 days old while the setting is on (Android invitees refuse an older one),
and the automatic handling of 52.
The byte layouts above must match exactly.

Records 53 to 56 belong to version 11 and are described in the next two
sections.

## Per-member add rights (msgTypes 53 and 54, version 11)

From messaging minor version 11
(`MessagingManager.GROUP_INVITER_GRANTS_MIN_VERSION`) the creator can let
chosen members add members without turning on the group-wide switch of
msgType 48. The right only lets a member invite its own contacts through the
member invitation flow above; the creator's device still signs every
membership record and decides each addition itself. A member that holds the
right can never remove anyone: removal records (34), snapshots (41) and the
dissolution (36) are taken only under the creator's signature, and 34 only
from the creator as pairwise sender (see "Verification"), whoever holds a
right.

### 53 - GROUP_INVITER_GRANT

```
BdfList.of(
    53L,                       // msgType
    groupId,                   // 32 bytes
    memberPubKey,              // 32 bytes, the Ed25519 key of the member the grant names
    flags,                     // Long >= 0; bit 0 (value 1) = may add members
    timestamp,                 // Long: when the creator chose the setting
    sig                        // hybrid signature of the CREATOR
)
```

Validator size: exactly **6 slots**; a negative `flags` is refused; `sig`
length in `[1, 4096]` (the manager accepts only 3373). Signed input:

```
[32B groupId][32B memberPubKey][8B BE flags][8B BE timestamp][0x0A]
total: 81 bytes
label: org.zerionproject/GROUP_INVITER_GRANT
```

The creator's device keeps one grant per member (settings key
`grant.<memberHex>` in `grouptr.mi.<groupHex>`). Granting or revoking signs a
new record dated `max(now, previous timestamp + 1)` and stores it before
anything is sent, so a revocation takes effect on the creator's device at
once: from then on it refuses join requests (52) from that member, unless the
group-wide switch is on. The record is sent only to the member it names, and
only when that member is the creator's contact and announces 11 or later; it
is sent again when that member's announced version reaches 11. Revoking sends
a record with flags 0. While a grant is set the creator signs it again with a
new timestamp once it is 7 days old (`PERMISSIONS_REFRESH_MS`), on the same
occasions as msgType 48 (database opened, a post by the creator, a member
added). The creator forgets a member's grant when it removes that member or
confirms its leaving, so a person added again starts without the right.

A receiver applies the record only for a live group, only when the pairwise
sender is the creator, only when `memberPubKey` is its own key (a device never
keeps a grant naming another member, and the creator never sends one), only
when its timestamp is strictly newer than the grant it holds, and only when
the creator's hybrid signature verifies. Other members do not learn who holds
the right.

### 54 - GROUPTR_GRANTED_INVITE_OFFER

The same 13 slots, lengths and signed-input layout as msgType 49, with two
differences: slots 8 to 10 (`permFlags`, `permTimestamp`, `permSig`) carry
the inviting member's own grant (the flags, timestamp and creator signature
of the msgType 53 naming it), and the member signs under its own label
`org.zerionproject/GROUPTR_GRANTED_INVITE_OFFER`. The validator keeps it as an
invitation card exactly as 49.

A member uses 49 while the group-wide switch is on and its record 48 is at
most 14 days old; otherwise it uses 54 when it holds a grant with bit 0 set
that is at most 14 days old and not more than 24 hours ahead of its clock. It
sends 54 only to a contact that announces 11 or later (the contact picker
shows older contacts as needing a newer version). The invitee admits a 54 by
the rules of 49, except that bit 0 of the grant's flags must be set and the
grant must verify under label `org.zerionproject/GROUP_INVITER_GRANT` and the
signed input of 53, with `memberPubKey` equal to `inviterPubKey`, against the
creator's keys from the offer; a grant naming another member is refused. The
answers (50, 51 and 56) and the join request (52) are unchanged.

The creator's device admits a join request (52) when its own permission
record (48) has bit 0 set or its own grant for the inviting member has bit 0
set; every other rule of 52 applies unchanged. A member before version 11
never receives a grant and can still invite while the group-wide switch is
on. An iOS client that announces 11 must, for its roles, keep its own grant,
send 54 and admit 54 as above, and as a creator sign, send, refresh and
enforce grants.

## Incognito names (msgTypes 55 and 56, version 11)

From messaging minor version 11
(`MessagingManager.GROUP_INCOGNITO_MIN_VERSION`) a user can create a group, or
accept an invitation, "incognito in this group". The device then shows itself
in that group under a random name of the form `<Adjective> <Noun> <NN>`,
drawn on the device from the fixed word lists of `GroupTrIncognitoNames` (64
adjectives, 64 nouns, a number from 10 to 99, single ASCII spaces, for example
`Amber Falcon 27`). The user can draw another name before confirming and
cannot change it afterwards (`setStealthName` is refused with
`INCOGNITO_NAME_FIXED`). The name is kept per group on the device (settings
key `incognitoName` in `grouptr.alias.<groupHex>`). Incognito names are not
per group keys.

The incognito device never puts its account name into a group record:

- its posts (32) carry the random name in slot 4, covered by the post
  signature as any name;
- a group created incognito uses the random name as the creator name: it is
  bound into the group id derivation and carried in 42, 49 and 54, and every
  member's entry for the creator holds it;
- accepting a creator's invitation sends 55 instead of 43, so the creator
  adds the device (33) under the random name;
- accepting a member's invitation sends 56 instead of 50, so the inviting
  member names the device by the random name in its join request (52) and
  the creator adds it (33) under that name.

### 55 - GROUPTR_INVITE_ACCEPT_INCOGNITO

```
BdfList.of(
    55L,                       // msgType
    grouptrGroupId,            // 32 bytes
    timestamp,                 // Long
    name,                      // UTF-8 string, 1..50 bytes: the joiner's name in this group
    sig                        // the signature a 43 would carry, 1..4096 bytes
)
```

Validator size: exactly **5 slots**. `sig` is the acceptance signature of 43
(label `org.zerionproject/GROUPTR_INVITE_ACCEPT`, the invite signed input with
`keyA` the joiner's key and `keyB` the creator's key). The name is not covered
by it, as the name in 33 is not; the pairwise channel it arrives on
authenticates its sender. The creator handles 55 exactly as an accepting 43
and adds the joiner under `name` instead of the name it chose when it
invited.

### 56 - GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO

```
BdfList.of(
    56L,                       // msgType
    groupId,                   // 32 bytes
    inviterPubKey,             // 32 bytes
    timestamp,                 // Long
    name,                      // UTF-8 string, 1..50 bytes
    sig                        // the invitee's signature of record 50, 1..4096 bytes
)
```

Validator size: exactly **6 slots**. `sig` is the acceptance signature of 50
(same label and signed input), so the inviting member passes it on in 52
unchanged and a creator on version 10 verifies it as before. The inviting
member handles 56 exactly as an accepting 50 and puts `name` into 52 as
`inviteeName`.

A device sends 55 only when the creator (the contact the offer 42 came from)
announces 11 or later, and 56 only when the inviting member does. With an
older peer the incognito choice is not offered, and an incognito accept is
refused with `INCOGNITO_NOT_SUPPORTED` before anything is sent, because an
older peer would put the account name it knows into 33 or 52.

### How other members show an incognito member

A receiver on version 11 that shows a post whose signed sender name is an
incognito name (`GroupTrIncognitoNames.isIncognitoName`: exactly the grammar
above, words from the fixed lists) shows that name, marked as incognito, with
the key fingerprint, and not the name it holds for that key in its own
contact list. There is no flag on the wire: the name is the signal, and the
post signature covers it. A receiver before version 11 shows the post as
before, the name it knows for the key followed by the posting name. A user who
types a name of this form as an ordinary per group name is shown the same
way.

Incognito hides the account name, not the identity key. Every member still
receives the incognito member's Ed25519 key (in 33, 41 and every post), and a
member that has that key among its contacts, or learns it elsewhere, can
recognise the person; the creator and an inviting member know whom they
invited.

iOS: an iOS client that announces 11 must send and handle 55 and 56 as above,
use the identical word lists and grammar both to draw names and to recognise
them, and never place the account name into a group record of a group it is
incognito in.

## Signed-input format (byte-exact)

Each record carries a signature over a deterministic byte string. **iOS must produce the exact same bytes** or Android rejects on `crypto.verifyHybridSignature`.

### MEMBER_ADDED / MEMBER_LEFT (`membershipSignedInput`)

```
[32B groupId][32B targetPubKey][4B BE epoch][8B BE timestamp][1B action]
total: 77 bytes
action: 0x01 for ADDED, 0x03 for LEFT
```

Note: Big-endian for all integer fields. **`timestamp` is the 8-byte signed Long. `epoch` is the low 4 bytes (treated as uint32).**

### MEMBER_REMOVED (`removedSignedInput`)

```
[32B groupId][32B removedPubKey][4B BE fromEpoch][4B BE toEpoch][8B BE timestamp][0x02]
total: 81 bytes
```

### DISSOLVED (`dissolveSignedInput`)

```
[32B groupId][4B BE epoch][8B BE timestamp][0x04]
total: 45 bytes
```

### EPOCH_COMMIT (`epochCommitSignedInput`)

```
[32B groupId][4B BE fromEpoch][4B BE toEpoch][32B H(label="org.zerionproject/GROUP_EPOCH_SEED", pqSeed)][8B BE timestamp][0x05]
total: 81 bytes
```

The pqSeed itself is NOT in the signed-input - its hash is. H(label, x) = BLAKE2b-256(uint32_be(len(label)) || label || uint32_be(len(x)) || x), 32 bytes.

### ROLE_CHANGED (`roleChangedSignedInput`)

```
[32B groupId][32B targetPubKey][1B newRole][4B BE epoch][8B BE timestamp][0x06]
total: 78 bytes
```

### GROUP_POST_DELETE (`GroupTrPostRemovals.signedInput`)

```
[32B groupId][4B BE epoch][32B senderPubKey][32B targetPostId][8B BE timestamp][0x09]
total: 109 bytes
```

### GROUP_INVITER_GRANT (`GroupTrMemberInvites.grantSignedInput`)

```
[32B groupId][32B memberPubKey][8B BE flags][8B BE timestamp][0x0A]
total: 81 bytes
```

### LIST_SNAPSHOT (`snapshotSignedInput`)

```
mlHash = H(label="org.zerionproject/GROUP_MEMBER_LIST", memberCanonical)   // H as defined under EPOCH_COMMIT
signedInput = [32B groupId][4B BE epoch][8B BE timestamp][32B mlHash][0x07]
total: 77 bytes
```

Where `memberCanonical` is the concatenation of `[32B pubKey][1B role][4B BE joinedAtEpoch]` for each member, in the order they appear in the BdfList.

## Signing (sender side)

Every GroupTr record (msgTypes 32 to 38, 41 to 44, 46, 47 and 48 to 56) carries a hybrid Ed25519 + ML-DSA-65 signature of exactly 3373 bytes. There is no Ed25519-only mode: if the local identity has no ML-DSA-65 private key, Android refuses to sign and nothing is sent.

```
def signOrThrow(label, signed, ed25519PrivateKey):
    mlDsaPriv = identityManager.getLocalMlDsaSigPrivateKey()
    if mlDsaPriv is None:
        raise error                         # no Ed25519-only fallback
    hybridKey = HybridSignaturePrivateKey(
        ed25519=ed25519PrivateKey.encoded,  # 32 bytes
        mlDsa=mlDsaPriv                     # 4032 bytes
    )
    return crypto.hybridSign(label, signed, hybridKey)
    # 3373 bytes = Ed25519 (64) || ML-DSA-65 (3309)
```

Both halves sign the same framed message
`M = uint32_be(len(label)) || label (UTF-8) || uint32_be(len(signed)) || signed`.

Labels used in GroupTr:

- `"org.zerionproject/GROUP_MEMBERSHIP"` for msgType 33, 34, 35, 36, 38
- `"org.zerionproject/GROUP_EPOCH_COMMIT"` for msgType 37
- `"org.zerionproject/GROUP_MEMBER_LIST_SNAPSHOT"` for msgType 41
- `"org.zerionproject/GROUP_SETTINGS"` for msgType 46
- `"org.zerionproject/GROUP_POST_DELETE"` for msgType 47
- `"org.zerionproject/GROUPTR_INVITE_OFFER"` for msgType 42
- `"org.zerionproject/GROUPTR_INVITE_ACCEPT"` for msgType 43
- `"org.zerionproject/GROUPTR_INVITE_DECLINE"` for msgType 44
- `"org.zerionproject/GROUP_PERMISSIONS"` for msgType 48, and for the
  permission record a msgType 49 carries
- `"org.zerionproject/GROUPTR_MEMBER_INVITE_OFFER"` for msgType 49
- `"org.zerionproject/GROUPTR_MEMBER_INVITE_ACCEPT"` for msgType 50, and for
  the acceptance a msgType 52 carries
- `"org.zerionproject/GROUPTR_MEMBER_INVITE_DECLINE"` for msgType 51
- `"org.zerionproject/GROUPTR_JOIN_REQUEST"` for msgType 52
- `"org.zerionproject/GROUP_INVITER_GRANT"` for msgType 53, and for the grant
  a msgType 54 carries
- `"org.zerionproject/GROUPTR_GRANTED_INVITE_OFFER"` for msgType 54
- msgTypes 55 and 56 carry the signature of 43 and 50 under those labels
- `"org.zerionproject/GROUP_POST"` for msgType 32 (separate spec)

(Labels are defined in `zerion-app/.../grouptr/GroupTrConstants.java` and `MessagingConstants`.)

## Verification (receiver side, current Android)

For each membership record, after the validator's structural check, `GroupTrManagerImpl.handleMembershipEvent` runs:

```
1. Resolve sender's pubkey from the pairwise ContactId on the incoming Event.
2. Compute the signed-input (same function as the sender used).
3. Pick the verifying key:
     - MEMBER_ADDED, MEMBER_REMOVED, EPOCH_COMMIT:
         the pairwise sender must be the CREATOR; signer = CREATOR
     - MEMBER_LEFT:
         signer = the leaving member (never the creator); sender not checked
     - GROUP_SETTINGS, GROUP_PERMISSIONS:
         the pairwise sender must be the CREATOR; signer = CREATOR
     - GROUP_INVITER_GRANT:
         the pairwise sender must be the CREATOR; signer = CREATOR; the
         grant must name the receiving device
     - member invitation records 49 to 52:
         see "Member invitations"
     - DISSOLVED, ROLE_CHANGED, LIST_SNAPSHOT:
         signer = CREATOR; sender not checked
     - GROUP_POST_DELETE:
         signer = senderPubKey of the record; the pairwise sender and the
         signer must both be members (or the creator); the record removes a
         post only if that post's signer is the same key
4. Verify the signature (GroupTrManagerImpl.verify, same rule for every record):
     if the signed input is empty: drop
     mlDsaPub = ML-DSA-65 public key known locally for the signer's Ed25519 key
                (a stored group member entry, the local identity, or the contact record)
     if mlDsaPub is unknown: drop            (whatever the signature length)
     if sig.length != 3373: drop             (a 64-byte Ed25519-only signature is dropped)
     accept only if Ed25519.verify(sig[0..64], M, signerEd25519Pub)
                and ML-DSA-65.verify(sig[64..3373], M, mlDsaPub)
5. If verification fails: silently drop the record (no log in production
   per project policy).
```

There is no Ed25519-only fallback: a client must never accept a signature
because its first 64 bytes verify as Ed25519, since that half is a valid
standalone Ed25519 signature and accepting it would let anyone strip the
ML-DSA-65 half. For msgTypes 32 and 35 the validator also checks the
Ed25519 half on its own and rejects the message if it fails; passing that
check never makes a membership record acceptable. The Android group screen
and group notifications show only the posts the group manager accepted
after the full checks: they react to `GroupTrPostAcceptedEvent`, which the
manager raises for each post it takes in, and treat the raw arrival of a
post only as a cue to read the accepted posts again.

Records whose sender is not checked are accepted from any contact that
delivers a validly signed copy with an epoch higher than the local epoch
(known limitation); for example, a newly accepted invitee (epoch 0)
accepts an older creator-signed snapshot relayed by any contact until the
creator's msgType 33 arrives. From 3.0.18 this also holds for a
creator-signed snapshot at the local epoch, which can only add members the
device lacks (see msgType 41). Android accepts 33, 34 and 37 only from the
creator.

## State machine - receive

Given a MEMBER_ADDED record:

```
GroupTrManagerImpl on MEMBER_ADDED:
    state = local group with this groupId
    if state is missing, removed from this device, or dissolved: drop
    if the pairwise sender is not the creator: drop
    if the hybrid signature does not verify against the creator's keys: drop
    if event.epoch <= state.epoch:                       (older addition)
        unless it is this device, raise a listed entry for pubKey to
            joinedAtEpoch = event.epoch when it is lower
        clear a departure record of pubKey below event.epoch
        never add an absent member, never move the epoch; return
    if a member already has this pubKey:
        unless it is this device: move its entry to
            joinedAt = message timestamp, joinedAtEpoch = event.epoch
        state.epoch = event.epoch; persist; release buffered posts; return
    append member(pubKey, addedName, joinedAt = message timestamp,
                  joinedAtEpoch = event.epoch, role MEMBER)
    state.epoch = event.epoch                            (epoch gaps are accepted)
    persist; release buffered group posts
```

Membership records are never buffered. Only group posts are buffered, in memory: a post more than 5 epochs ahead of the local epoch is held (bounded per sender, per group and in total, by count and by bytes) until the local epoch is within 5 of it; a post more than 1 epoch behind is accepted when its signer was a member at that epoch and otherwise kept as pending, never removed for its epoch alone. The stored copy of a held post is pending; when the post is released it is accepted and announced like any other post (`GroupTrPostAcceptedEvent`), so an open group screen shows it at once.

## Who receives a post

There is no relay: a member sends each post over the pairwise channel to every
member that is its own contact, and members do not pass posts on. A member
that is not the sender's contact does not receive the post, and its posts do
not reach the sender. The group screen says how many members are out of reach
and lists them (`getMembersOutOfReach`); the member list marks them. Relaying
posts through other members was considered and not adopted: it would reveal to
each relaying member which members it can reach for others and would need
relay authorisation and loop control, and adding the members as contacts
already gives the same reach without a third party.

**What the sender is shown (device behaviour, no wire change).** Each copy
of a post is a message of its own in the pairwise conversation with one
contact, and the pairwise sync already keeps, for every such message,
whether it was sent and whether that contact's device acknowledged it; it
needs that to send again and to start a copy's disappearing timer. From
3.0.18 the sender's group screen reads the state of its own posts from those
copies (`GroupTrManager.getOwnPostDeliveries`, `GroupTrPostDeliveries`,
`GroupTrOwnCopies`). Every copy of the post this device holds counts, in the
conversation of any contact, also one to a member removed or departed since
the post:

- "waiting to send" while any copy has not left the device;
- "sent" once every copy has left it over the internet; a copy flooded over
  the Bluetooth mesh while the device was offline is still waiting, because
  the mesh does not report whether any phone received it, until the copy is
  sent over the internet;
- "delivered" once every copy is acknowledged and every current member that
  joined at or before the post's epoch holds an acknowledged copy.

A post stays "sent" while a member of the group never got it: one out of
reach, one whose app is too old for a post this large, or one whose copy
could not be stored. A member removed before its copy arrived keeps the
post "sent" too, because this device still holds that unacknowledged copy.
A member skipped for an old app, or whose copy could not be stored, has no
copy on this device, so once that member is removed the post can turn
"delivered". Deleting a contact also deletes the copies held for it, which
then no longer count. Message info names the same state.

The device knows which stored messages are copies of its own posts from
sending them and from reading them when the group's posts are loaded, and
notes them only for the posts kept within the group's bounds (at most 200);
it keeps that list in memory only, with one entry per member for the
member's key rather than one per copy, and reads the sync state in batches
of 64. When the sync reports one of those copies sent or acknowledged, the
open group screen reads the state of that post alone; opening or reloading
the screen reads them all. A state read earlier never replaces one read
later. Nothing new is stored, sent or kept per member to work this out, no
read state exists, and the screen shows one state for the whole post,
never which member has it. Up to 3.0.17 a stored post of one's own showed
its time only.

## What the iOS team needs to do to fix Peter's invite

Concrete checklist:

1. **Do not reuse the legacy private-group invitation carrier** (the `privategroup.invitation` client inherited from upstream). GroupTr replaced it. The invite layer GroupTr DOES use is the 42/43/44 handshake on the pairwise messaging channel documented above - implement that, not the upstream invitation client. The membership records (33–41) are still silent fan-out and must NOT appear as visible chat messages.
2. **In the iOS group-create UI**: `createGroup(name)` must be purely local. Do NOT send anything over the wire when a group is created. The group is invisible to peers until the first `addMember` call.
3. **In the iOS "add member" handler**: build the msgType-33 record exactly as specified above, sign with the hybrid key, and send it to the new member AND every other existing member over their pairwise messaging channels.
4. **In the iOS private-message receive path**: when a record's first BdfList element is `33L`, route to a membership handler. Do NOT show it as a visible chat message. Just verify the signature and apply.
5. **In the iOS group state machine**: a MEMBER_ADDED record is applied only to a group that already exists locally. The invitee's group is created when the user accepts the msgType-42 OFFER; the later MEMBER_ADDED for the local user only advances the epoch.
6. **Hybrid signatures**: every record must be signed with `hybridSign(label, signedInput, HybridSignaturePrivateKey)`; a client without an ML-DSA-65 private key cannot send GroupTr records. On receive, accept only a 3373-byte signature whose Ed25519 and ML-DSA-65 halves both verify.
7. **Wire format byte-exactness**: pay close attention to big-endian encoding of `epoch` (4 bytes) and `timestamp` (8 bytes) in the signed-inputs. Off-by-one or endianness errors will produce signatures Android rejects.
8. **Receiver rules for records that arrive out of order (iOS parity required)**: Android 3.0.18 never lets a 37 change anything (msgType 37); applies the creator's late 34 without moving the epoch, never to the receiving device and never to a member added again after the 34's fromEpoch (msgType 34); takes the creator's 33 that is not newer than the local epoch only to raise the listed member's joinedAtEpoch and clear its departure record ("State machine - receive"); records every member it takes out by a 34 or 35 with one epoch (for a 35 the lower of its epoch and the local epoch) and leaves that membership out of every snapshot, so no snapshot adds back a member that left or was removed (msgType 41); takes a creator snapshot at its own epoch to add missing members only, never removing or changing anything (msgType 41); and, on a device that joined through a member's invitation, treats the creator as on version 8 or later for leavings (msgType 35). These rules change no record and need no version gate, but an iOS receiver must implement them the same way, or a removal can be lost on an iOS device whose 37 or a later record arrives first, a member that left can come back with an older snapshot, and a new iOS member can stay unaware of earlier members and keep their posts pending.

## What this does NOT cover

- GroupTr has no group key and no group ratchet: the sender signs each post once and sends a copy over the pairwise channel to each member that is its contact, and the confidentiality, forward secrecy and post-compromise security of a post are those of the pairwise channel each copy travels over (see the Technical Whitepaper, group section).
- Full recovery from missing membership records. A snapshot (41) repairs a device only when the creator sends one (after an addition, after a removal in a group where member invitations were configured, or on a manual repair, for which the Android app has no button), and a snapshot at the device's own epoch only adds members. A device that never receives a removal's 34 (for example an invitee that is not the creator's contact) keeps the removed member until a snapshot at a higher epoch arrives, and a leaving reaches a device only from the leaver itself or with the creator's next snapshot. A late 34 is applied only when the creator delivers it; a copy relayed by another member is refused. A member the creator added again can be taken out by the creator's late 34 for its earlier membership when that 34 arrives before the 33 that added it again; it comes back with the creator's next snapshot. A device that took a member out keeps the newest 256 such records per group, so in a group that removed more members than that, a snapshot could list an older removed membership again. Record 38 (role change) is still taken from whichever contact delivers it, so a member can move a device's epoch on with a relayed 38; with these rules a late 34 still applies, but a snapshot from before that 38 is then refused as older.

## Quick interop sanity test for iOS team

When iOS is wired up, the smallest test that proves the protocol works end-to-end:

1. Android user (Alice) creates a group named "Test" and invites Peter (msgType 42).
2. Peter accepts (msgType 43); his device creates the group at epoch 0.
3. Alice's app runs `addMember(group.id, peter.pubkey, peter.name)` and sends msgType-33 (and the snapshot) over the pairwise Tor channel with Peter.
4. Peter's iOS Zerion receives, validates the signature, applies the state.
5. **Peter's group "Test" advances to Alice's epoch** without any further user action.

If that flow fails on iOS, the bug is in steps 4–5 (receive routing or state apply). Send the BdfList bytes of the msgType-33 record from the wire dump and Android can verify byte-exact equality against what its validator expects.
