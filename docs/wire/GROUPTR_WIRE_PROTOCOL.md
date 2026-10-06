# GroupTr - group membership and post wire protocol

iOS parity for Zerion group chat. Android implementation: `zerion-app/.../grouptr/GroupTrManagerImpl.java` plus the validator at `zerion-app/.../messaging/PrivateMessageValidator.java`. Shipped on Android since 1.5. Only the creator can add or remove members, dissolve the group, change roles and send snapshots; the ADMIN role can be assigned but grants no authority on Android.

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

`32`'s wire format is documented separately; this doc covers 33–38 + 41 (the
membership records), 42–44 (the invite handshake) and 46 (the group
settings). msgTypes 39 and 40 are reserved constants only - no validator
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

Group records other than posts (33-38, 41, 43, 44, 46) are consumed when they
arrive. A received record is kept for 5 minutes, a sent record for 5 minutes
after the contact acknowledged it. An invite offer (42) is a conversation card
and is kept like a message.

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

**Must be paired with msgType 37 (`GROUP_EPOCH_COMMIT`)** on the same outgoing send. Android sends 34 and 37 as two separate messages in one DB transaction: 34 to every member including the removed one, 37 to every member except the removed one. The receiver applies 34 only if its toEpoch is higher than the local epoch, and 37 only if its fromEpoch equals the local epoch; each sets the local epoch to toEpoch. Order therefore matters. If 37 is processed first, the following 34 is refused as stale and the removal is not applied on that device (known limitation). If 34 is processed first, the following 37 is ignored.

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
  the old leaving; a device's own entry keeps its epoch until the snapshot
  that follows its addition (see msgType 41).
- **Version 8, creator on version 8**: the leaver is taken out of the member
  list and the shared epoch does **not** move. A member's own record cannot
  advance the epoch, which only the creator's records do, so a leaving sent to
  some devices only cannot put them ahead of the others (where they would
  refuse the creator's next records and drop the others' posts). The creator
  confirms the leaving with its own msgType 34 + 37 for the leaver
  (k to k+1), sent to the remaining members, which moves every device to the
  next epoch together. The creator also confirms a leaving signed at an
  older epoch, so a member that left while behind is still taken out.
- **Version 8, creator on an earlier version** (or not a contact): the
  creator does not confirm and moves its own epoch on the leaving, so the
  receiver keeps the earlier behaviour (epoch moves to
  `min(newEpoch, local + 1)` when `newEpoch` is higher than the local epoch),
  to stay in step with it. In such a group a member can still put a chosen
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

Validator size: **7 slots**. Creator-only on Android receiver. `newRole` must be in `[0, 2]`.

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

Max 256 members per snapshot.

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

The second case is intended to reconcile an invitee whose local state
was created from the invite. On Android it normally has no effect
(known limitation): the creator sends the msgType-33 record at the new
epoch E+1 and then the snapshot at the same epoch E+1, and a receiver
that has already applied the 33 is at epoch E+1 and refuses the
snapshot, because a snapshot must have a strictly higher epoch. The
invitee's member list therefore usually holds only the creator and
itself, and it drops posts from other members.

A receiver applies msgType 41 only if the group exists, is not
dissolved, the snapshot's epoch is strictly higher than the local
epoch, the list has at most 256 entries and the signature verifies
against the creator's keys. It replaces the member list; names,
joinedAt and ML-DSA keys come from the previous local entry or the
contact list, not from the snapshot; and it sets the local epoch to the
snapshot's epoch.

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

### LIST_SNAPSHOT (`snapshotSignedInput`)

```
mlHash = H(label="org.zerionproject/GROUP_MEMBER_LIST", memberCanonical)   // H as defined under EPOCH_COMMIT
signedInput = [32B groupId][4B BE epoch][8B BE timestamp][32B mlHash][0x07]
total: 77 bytes
```

Where `memberCanonical` is the concatenation of `[32B pubKey][1B role][4B BE joinedAtEpoch]` for each member, in the order they appear in the BdfList.

## Signing (sender side)

Every GroupTr record (msgTypes 32 to 38, 41 to 44 and 46) carries a hybrid Ed25519 + ML-DSA-65 signature of exactly 3373 bytes. There is no Ed25519-only mode: if the local identity has no ML-DSA-65 private key, Android refuses to sign and nothing is sent.

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
- `"org.zerionproject/GROUPTR_INVITE_OFFER"` for msgType 42
- `"org.zerionproject/GROUPTR_INVITE_ACCEPT"` for msgType 43
- `"org.zerionproject/GROUPTR_INVITE_DECLINE"` for msgType 44
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
     - GROUP_SETTINGS:
         the pairwise sender must be the CREATOR; signer = CREATOR
     - DISSOLVED, ROLE_CHANGED, LIST_SNAPSHOT:
         signer = CREATOR; sender not checked
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
creator's msgType 33 arrives. Android accepts 33, 34 and 37 only from the
creator.

## State machine - receive

Given a MEMBER_ADDED record:

```
GroupTrManagerImpl on MEMBER_ADDED:
    state = local group with this groupId
    if state is missing, removed from this device, or dissolved: drop
    if the pairwise sender is not the creator: drop
    if the hybrid signature does not verify against the creator's keys: drop
    if event.epoch <= state.epoch: drop                  (stale or replayed)
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

## What the iOS team needs to do to fix Peter's invite

Concrete checklist:

1. **Do not reuse the legacy private-group invitation carrier** (the `privategroup.invitation` client inherited from upstream). GroupTr replaced it. The invite layer GroupTr DOES use is the 42/43/44 handshake on the pairwise messaging channel documented above - implement that, not the upstream invitation client. The membership records (33–41) are still silent fan-out and must NOT appear as visible chat messages.
2. **In the iOS group-create UI**: `createGroup(name)` must be purely local. Do NOT send anything over the wire when a group is created. The group is invisible to peers until the first `addMember` call.
3. **In the iOS "add member" handler**: build the msgType-33 record exactly as specified above, sign with the hybrid key, and send it to the new member AND every other existing member over their pairwise messaging channels.
4. **In the iOS private-message receive path**: when a record's first BdfList element is `33L`, route to a membership handler. Do NOT show it as a visible chat message. Just verify the signature and apply.
5. **In the iOS group state machine**: a MEMBER_ADDED record is applied only to a group that already exists locally. The invitee's group is created when the user accepts the msgType-42 OFFER; the later MEMBER_ADDED for the local user only advances the epoch.
6. **Hybrid signatures**: every record must be signed with `hybridSign(label, signedInput, HybridSignaturePrivateKey)`; a client without an ML-DSA-65 private key cannot send GroupTr records. On receive, accept only a 3373-byte signature whose Ed25519 and ML-DSA-65 halves both verify.
7. **Wire format byte-exactness**: pay close attention to big-endian encoding of `epoch` (4 bytes) and `timestamp` (8 bytes) in the signed-inputs. Off-by-one or endianness errors will produce signatures Android rejects.

## What this does NOT cover

- GroupTr has no group key and no group ratchet: the sender sends a separately signed copy of each post over the pairwise channel to each member that is its contact, and the confidentiality, forward secrecy and post-compromise security of a post are those of the pairwise channel each copy travels over (see the Technical Whitepaper, group section).
- Recovery from missing membership records: msgType 41 exists, but see the limitation in "When to send a snapshot" above.

## Quick interop sanity test for iOS team

When iOS is wired up, the smallest test that proves the protocol works end-to-end:

1. Android user (Alice) creates a group named "Test" and invites Peter (msgType 42).
2. Peter accepts (msgType 43); his device creates the group at epoch 0.
3. Alice's app runs `addMember(group.id, peter.pubkey, peter.name)` and sends msgType-33 (and the snapshot) over the pairwise Tor channel with Peter.
4. Peter's iOS Zerion receives, validates the signature, applies the state.
5. **Peter's group "Test" advances to Alice's epoch** without any further user action.

If that flow fails on iOS, the bug is in steps 4–5 (receive routing or state apply). Send the BdfList bytes of the msgType-33 record from the wire dump and Android can verify byte-exact equality against what its validator expects.
