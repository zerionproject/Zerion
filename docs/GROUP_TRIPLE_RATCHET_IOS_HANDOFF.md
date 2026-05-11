# iOS handoff — Group Triple Ratchet wire protocol

**Version:** 1.0
**Date:** 2026-05-11
**Status:** ACTIVE — Android implementation shipped on `dev`, iOS to mirror
**Author:** Zerion Project

**Status of related docs**

- [GROUP_TRIPLE_RATCHET_PQ_DESIGN.md](GROUP_TRIPLE_RATCHET_PQ_DESIGN.md) is the design rationale; this doc supersedes its `§5 Wire Protocol` section with the byte-exact format that actually shipped on Android.

Read the design doc for the "why" (pairwise Triple Ratchet, MLS deferred, threat model, security properties). Read this doc for the "what" you need to ship byte-for-byte on iOS to interop.

---

## 1. Transport

Group records ride the existing 1:1 private-message channel between each pair of members (`org.briarproject.briar.messaging`, `CLIENT_ID + MAJOR_VERSION`). Each post or admin record is one private message per recipient. Stream-level encryption is the existing Mode-3 Triple Ratchet on the pair's transport — X25519 + ML-KEM-768 + symmetric chain. No new crypto.

The wire `msgType` is the first BDF element in the message body. Field numbering chosen to leave space below current types (0–9) and to not collide with any reserved Briar values.

```
GROUP_POST            = 32
GROUP_MEMBER_ADDED    = 33
GROUP_MEMBER_REMOVED  = 34
GROUP_MEMBER_LEFT     = 35
GROUP_DISSOLVED       = 36
GROUP_EPOCH_COMMIT    = 37
```

---

## 2. Identity model

All `*PubKey` fields below are the raw 32-byte **Ed25519 signing public key** of the relevant author. This is what Android's `Author.getPublicKey().getEncoded()` returns and what iOS's `AccountManager.getLocalAuthorPublicKey()` should return after the iOS team's commit 3bc8b51 fix.

The Briar SHA-256-derived `authorId` is **never** on the wire in this protocol. Local platforms may compute it for indexing (`hash("org.briarproject.bramble/AUTHOR_ID", uint32be(formatVersion=1), name_utf8, pubkey_32)`) but the wire identifies authors by their raw signing pubkey.

### 2.1 `groupId` derivation

Same as Briar's existing private-group convention, identical labels and byte layout:

```
authorList = BdfList[ int(1), string(creatorName), raw(creatorPubKey) ]
descriptor = BdfList[ list(authorList), string(groupName), raw(salt_32) ]

clientIdBytes      = "org.briarproject.zerion.grouptr".utf8
formatVersion      = uint8(1)               // single byte, NOT uint32
majorVersionBytes  = uint32be(0)            // 4 bytes

groupId = crypto.hash(
    "org.briarproject.bramble/GROUP_ID",    // label
    formatVersion,                          // byte[1]  = 0x01
    clientIdBytes,                          // utf-8 bytes of the client id
    majorVersionBytes,                      // 4 bytes big-endian
    descriptor                              // BDF-serialized bytes
)
```

`salt_32` is 32 fresh random bytes generated at group creation. `creatorName` is the creator's display name at creation time.

Note: `formatVersion` for the hash input is a single byte `0x01`; iOS must NOT use the 4-byte uint32 encoding here (that's a different field).

---

## 3. Wire records (BDF list bodies)

### 3.1 GROUP_POST (msgType=32)

```
[
  32,                  // int — wire type
  raw(32),             // groupId
  int (uint32 range),  // epoch — current group epoch as the sender sees it
  raw(32),             // senderPubKey (sender's Ed25519 signing pubkey)
  string,              // senderName — see §6 stealth name
  raw,                 // body — the actual message payload (UTF-8 text or
                       //        whatever upper-layer agrees; opaque here)
  raw(64),             // recordSig — Ed25519 sig per §4
  int                  // OPTIONAL: autoDeleteTimerMs (>0 = ephemeral)
]
```

Size: **7 or 8 BDF elements**.

### 3.2 GROUP_MEMBER_ADDED (msgType=33)

```
[
  33,
  raw(32),             // groupId
  raw(32),             // addedPubKey
  string,              // addedName
  int (uint32),        // epoch (= new epoch after the add applies)
  int,                 // timestamp (ms since epoch)
  raw(64)              // sig — Ed25519 by creator per §4
]
```

Size: **7 elements**.

### 3.3 GROUP_MEMBER_REMOVED (msgType=34)

```
[
  34,
  raw(32),             // groupId
  raw(32),             // removedPubKey
  int (uint32),        // fromEpoch
  int (uint32),        // toEpoch (MUST = fromEpoch + 1)
  int,                 // timestamp
  raw(64)              // sig — Ed25519 by creator per §4
]
```

Size: **7 elements**. Validator rejects if `removedPubKey == creator.pubkey` or `toEpoch != fromEpoch + 1`.

### 3.4 GROUP_MEMBER_LEFT (msgType=35)

```
[
  35,
  raw(32),             // groupId
  raw(32),             // leavingPubKey (= signer)
  int (uint32),        // epoch (= new epoch after leave applies)
  int,                 // timestamp
  raw(64)              // sig — Ed25519 by leaver per §4
]
```

Size: **6 elements**. Validator rejects if `leavingPubKey == creator.pubkey` (creator must dissolve).

### 3.5 GROUP_DISSOLVED (msgType=36)

```
[
  36,
  raw(32),             // groupId
  int (uint32),        // epoch (= new epoch after dissolve)
  int,                 // timestamp
  raw(64)              // sig — Ed25519 by creator per §4
]
```

Size: **5 elements**.

### 3.6 GROUP_EPOCH_COMMIT (msgType=37)

```
[
  37,
  raw(32),             // groupId
  int (uint32),        // fromEpoch
  int (uint32),        // toEpoch (MUST = fromEpoch + 1)
  raw,                 // pqSeed — fresh randomness, 32 B typical
  raw(64)              // sig — Ed25519 by creator per §4
]
```

Size: **6 elements**. Sent atomically alongside `GROUP_MEMBER_REMOVED` to every surviving member (not the removed one). Carries the new epoch's PQ refresh seed.

---

## 4. Signatures (Ed25519)

All signatures use the existing `crypto.sign(label, message_bytes, privateKey)` helper that prepends a length-prefixed label before signing. Labels:

```
"org.briarproject.zerion/GROUP_POST"
"org.briarproject.zerion/GROUP_MEMBERSHIP"      // ADDED, REMOVED, LEFT, DISSOLVED
"org.briarproject.zerion/GROUP_EPOCH_COMMIT"
```

Signed-input byte layouts. Big-endian integers throughout. Trailing single-byte action discriminator on membership records.

### 4.1 GROUP_POST signed input

```
groupId(32) ‖ epoch(4 BE) ‖ senderPubKey(32) ‖ H_name ‖ H_body

H_name = crypto.hash("org.briarproject.zerion/GROUP_POST_NAME", senderName_utf8)
H_body = crypto.hash("org.briarproject.zerion/GROUP_POST_CT", body_bytes)
```

Both hashes are the project's existing BLAKE2b 32-byte hash. Total signed-input length: 32 + 4 + 32 + 32 + 32 = 132 bytes.

### 4.2 GROUP_MEMBER_ADDED signed input (action = 0x01)

```
groupId(32) ‖ addedPubKey(32) ‖ epoch(4 BE) ‖ timestamp(8 BE) ‖ 0x01
```

Total: 77 bytes. Verifier uses the creator's pubkey from local group state.

### 4.3 GROUP_MEMBER_REMOVED signed input (action = 0x02)

```
groupId(32) ‖ removedPubKey(32) ‖ fromEpoch(4 BE) ‖ toEpoch(4 BE) ‖ timestamp(8 BE) ‖ 0x02
```

Total: 81 bytes. Verifier uses creator's pubkey.

### 4.4 GROUP_MEMBER_LEFT signed input (action = 0x03)

```
groupId(32) ‖ leavingPubKey(32) ‖ epoch(4 BE) ‖ timestamp(8 BE) ‖ 0x03
```

Total: 77 bytes. Verifier uses `leavingPubKey` (it's the signer per iOS convention).

### 4.5 GROUP_DISSOLVED signed input (action = 0x04)

```
groupId(32) ‖ epoch(4 BE) ‖ timestamp(8 BE) ‖ 0x04
```

Total: 45 bytes. Verifier uses creator's pubkey.

### 4.6 GROUP_EPOCH_COMMIT signed input (action = 0x05)

```
groupId(32) ‖ fromEpoch(4 BE) ‖ toEpoch(4 BE) ‖ H_seed ‖ timestamp(8 BE) ‖ 0x05

H_seed = crypto.hash("org.briarproject.zerion/GROUP_EPOCH_SEED", pqSeed)
```

Total: 32 + 4 + 4 + 32 + 8 + 1 = 81 bytes. Verifier uses creator's pubkey.

---

## 5. State machine — per group

Persisted locally; never on the wire.

```
GroupState = {
    groupId:               byte[32]
    name:                  String
    salt:                  byte[32]
    creatorPubKey:         byte[32]
    creatorName:           String
    created:               int64  (ms)
    epoch:                 uint32 (monotonic; bumps on add/remove/left/dissolve)
    dissolved:             bool
    members:               List<Member>
    defaultAutoDeleteMs:   int64  (0 = off, creator-set default for outbound posts)
}
Member = {
    pubKey:        byte[32]
    name:          String
    joinedAt:      int64
    joinedAtEpoch: uint32
}
```

Android stores this in `SettingsManager` namespace `"grouptr.g.<hex(groupId)>"` with one key per field. iOS may use whatever encrypted local store it has. The schema is local; only `epoch` and member-list state must agree across peers via the wire records.

### 5.1 Epoch rules

- Bumps by exactly +1 on each accepted membership-changing record.
- Sender of a `GROUP_POST` includes its current local epoch; receiver buffers if `epoch > local + 5` (out of tolerance) and drops if `epoch < local - 1` (too old).
- `GROUP_MEMBER_REMOVED` MUST have `toEpoch == fromEpoch + 1` AND `fromEpoch == current_local_epoch`. Receiver rejects otherwise.
- `GROUP_EPOCH_COMMIT` MUST have `fromEpoch == current_local_epoch` after applying the paired REMOVED record. Receiver rejects otherwise.

### 5.2 Receive-side validation order

For every inbound record on a 1:1 channel:

1. Decode BDF, dispatch on `body[0]`.
2. Structural checks (sizes, length bounds).
3. Verify signature per §4.
4. Look up local `GroupState` by `groupId`; drop silently if unknown.
5. Drop if `state.dissolved == true`.
6. For 33/34: signer is the creator (verified in §4). For 35/36 record types: check appropriate signer (§4).
7. For 34: refuse if `removedPubKey == creator.pubkey`. For 35: refuse if `leavingPubKey == creator.pubkey`.
8. Apply state change. For GROUP_POST: deliver upward to the chat UI with `senderPubKey`, `senderName`, `body`, `epoch`, `timestamp`, `autoDeleteTimerMs`.
9. For GROUP_POST: if `senderPubKey ∉ state.members` at this epoch, reject (member-list filter).

---

## 6. Stealth name (per-group display alias)

Local preference. Sender substitutes `localAuthor.name` with the alias at send time when filling the `senderName` field of `GROUP_POST`. Receivers display whatever name was on the wire — they do NOT cross-check against the sender's member-list entry. This means each post can carry a different name; the cryptographic identity is `senderPubKey`, the name is cosmetic.

Storage on Android: `SettingsManager` namespace `"grouptr.alias.<hex(groupId)>"`, key `stealthName`. Empty string or missing = no alias, fall back to local author's primary name. iOS may mirror under any local store; this setting NEVER goes over the wire.

---

## 7. Disappearing messages (TTL)

Two levels:

1. **Per-message TTL**: caller of `sendGroupPost` can pass an explicit `autoDeleteTimerMs`. If > 0, gets appended as the 8th BDF element of GROUP_POST and the receiver schedules local deletion `autoDeleteTimerMs` after receipt.

2. **Per-group default**: creator sets a default via `setGroupAutoDeleteTimer(groupId, ms)`. Stored in `GroupState.defaultAutoDeleteMs`. The sender substitutes this value when no explicit per-message TTL was provided. Receiver behaviour is unchanged — they only see the per-message field.

The default is **local to the sender**, NOT propagated. Each platform tracks its own default. Members of a group may have different defaults; what reaches peers is always the per-message value.

---

## 8. Send-side fan-out (one outbound message per non-self member)

For each member where `pubKey != localPub`:

1. Look up the local Contact whose `Author.getPublicKey()` matches `member.pubKey`. If none, skip silently — they're a known group member with no 1:1 channel set up, recoverable when they're contact-added later.
2. Get the 1:1 contact group: Android `MessagingManager.getContactGroup(contact).getId()`. iOS already computes the same via `computeInvitationContactGroupId(localAuthorId, remoteAuthorId)`.
3. Build the BDF body per §3.
4. Sign per §4.
5. Inject into the 1:1 BSP channel:
   - Android: `clientHelper.createMessage(contactGroupId, timestamp, bodyBytes)` → `clientHelper.addLocalMessage(txn, msg, new BdfDictionary(), shared=true, temporary=false)`.
   - iOS: equivalent flow via `MessagingManager` / queue private-message infrastructure.

The 1:1 BSP transport handles delivery + retry + ratchet encryption. From the group manager's point of view, send is fire-and-forget once `addLocalMessage` returns.

---

## 9. Concrete API surface — Android

For iOS team reference (mirror these names + behaviour).

```java
package org.briarproject.briar.api.grouptr;

interface GroupTrManager {
    GroupTrState getGroup(byte[] groupId);
    Collection<GroupTrState> getGroups();
    GroupTrState createGroup(String name);

    boolean isCreator(byte[] groupId, byte[] pubKey);
    boolean isMember(byte[] groupId, byte[] pubKey);
    long getEpoch(byte[] groupId);
    boolean isDissolved(byte[] groupId);

    void sendGroupPost(byte[] groupId, byte[] body, long autoDeleteTimerMs);

    void addMember(byte[] groupId, byte[] addedPubKey, String addedName);
    void removeMember(byte[] groupId, byte[] removedPubKey);
    void leaveGroup(byte[] groupId);
    void dissolveGroup(byte[] groupId);

    void setGroupAutoDeleteTimer(byte[] groupId, long ms);
    String getStealthName(byte[] groupId);
    void setStealthName(byte[] groupId, String alias);

    List<GroupTrPost> getRecentPosts(byte[] groupId);
}
```

`GroupTrAuthException.Reason` enum: `NOT_CREATOR`, `CANNOT_REMOVE_CREATOR`, `CANNOT_LEAVE_AS_CREATOR`, `GROUP_DISSOLVED`, `GROUP_NOT_FOUND`, `CONTACT_NOT_FOUND`. Thrown from admin actions when the caller isn't authorized.

---

## 10. Events posted on receive (Android EventBus)

iOS team should produce equivalents on the iOS EventBus.

- `GroupPostReceivedEvent(contactId, messageId, groupId, epoch, senderPubKey, senderName, body, timestamp, autoDeleteTimerMs)`
- `GroupMembershipChangedEvent(contactId, kind, groupId, epoch, timestamp, targetPubKey, targetName, fromEpoch, toEpoch, recordSig, signedInput)` where `kind ∈ {MEMBER_ADDED, MEMBER_REMOVED, MEMBER_LEFT, GROUP_DISSOLVED}`
- `GroupEpochCommitEvent(contactId, groupId, fromEpoch, toEpoch, pqSeed, recordSig, signedInput, timestamp)`

UI components subscribe to drive in-app updates.

---

## 11. Interop test plan

When the iOS implementation is ready, run this matrix in both directions:

| # | Action | Verify |
|---|---|---|
| 1 | iOS creates group `G` with creator `A` | Android contact `A`-side: nothing yet (no INVITE flow used; first record from A → contact B is a `GROUP_MEMBER_ADDED` carrying `groupId`); contact B receives no record because B isn't a member yet. iOS-side: `getGroup(G)` returns state with creator A only |
| 2 | iOS A calls `addMember(G, B.pubkey, "Bob")` | Android B: `GroupMembershipChangedEvent` with kind=MEMBER_ADDED fires; `GroupTrManager.getGroup(G)` returns state where B is now a member; B's stored creator pubkey matches A's actual signing pubkey |
| 3 | Android B calls `sendGroupPost(G, "hello".bytes, 0)` | iOS A: `GroupPostReceivedEvent` fires; `senderPubKey == B.pubkey`; `senderName == B.name` (or B's stealth alias if set); `body == "hello"` |
| 4 | iOS A sets stealth name "Phoenix" in G, sends a post | Android B receives the post with `senderName == "Phoenix"`; B's local member-list entry for A still shows A's primary name |
| 5 | iOS A calls `setGroupAutoDeleteTimer(G, 60_000)`, sends a post | Android B receives the post with `autoDeleteTimerMs == 60_000`; B schedules local cleanup at +60 s |
| 6 | iOS A calls `removeMember(G, B.pubkey)` | Android B receives `GROUP_MEMBER_REMOVED` (toEpoch == fromEpoch+1) + `GROUP_EPOCH_COMMIT` atomically; B is no longer a member from B's own view; any subsequent `GROUP_POST` from B is rejected on every remaining member's receive-filter |
| 7 | Both apps backgrounded for 10 minutes, then foreground | All records buffered during background deliver in order on resume; state machine catches up cleanly; no records dropped |
| 8 | iOS A re-adds B after a removal (new epoch ≥ removalEpoch + 2) | Android B accepts the re-add at the new epoch; member list reflects fresh `joinedAtEpoch` |
| 9 | Non-creator C attempts `removeMember` | iOS / Android throw `NOT_CREATOR` locally; even if forged record reached the wire, every receiver's §4 signature check would fail against the creator's pubkey |

### 11.1 Pre-flight checklist

- [ ] iOS computes the same `groupId` as Android for the same `(creatorName, creatorPubKey, groupName, salt)` tuple. Cross-check via a debug log of the 32-byte hash on both sides.
- [ ] iOS computes the same `H_name`, `H_body`, `H_seed` BLAKE2b hashes as Android. Same KDF labels, same input encoding.
- [ ] iOS Ed25519 sign/verify produces the same signature bytes as Android for the same `(label, signed_input, privKey)` triple. Spot-check with a fixed test vector.
- [ ] iOS BDF serialization order matches Android for each record type (the structural test).

---

## 12. Audit scope (when we do it together)

The crypto core (`PcsRatchet`, `PqRatchet`, `XSalsa20Poly1305AuthenticatedCipher`, `EdSignature`) is already audited as part of B.3 and is reused unchanged.

**What needs review:**

1. **Wire format soundness** — §3 layouts, §4 signed inputs. Goal: no parse ambiguity, no signature-malleability path, no canonicalization bug.
2. **Fan-out atomicity** — `removeMember` sends `REMOVED` + `EPOCH_COMMIT` to all surviving members. Question: what if delivery to one member fails after delivery to another succeeded? The on-wire record will eventually retry via BSP, but a peer that received only one of the two could be in a transient inconsistent state. Acceptable? (Likely yes — both records carry signatures; the receiver applies them idempotently when both arrive.)
3. **State-machine race conditions** — concurrent membership changes from the creator (admin tool sequencing) shouldn't be possible since only the creator can sign these. But re-delivery and reorder under BSP need to be examined.
4. **Stealth-name confusion** — can a malicious sender forge a `senderName` that impersonates another member? Answer: yes by intent (the name is sender-chosen). Receivers must rely on `senderPubKey`, not `senderName`, for identity. UI consequence: display name + pubkey fingerprint side-by-side when stakes are high.
5. **Removed-member decrypt window** — after `REMOVED + EPOCH_COMMIT` lands, the 1:1 ratchet between sender and removed peer is no longer used for group posts. But the ratchet is still alive (1:1 chat still works). Does anything leak group state to the removed peer through future 1:1 messages? Answer: no — group records ride only on contact groups where the contact is a current member of the group. The removed peer's 1:1 channel simply stops receiving group records.
6. **Replay** — every signed record is over `(groupId, epoch, ...)`. A re-delivered record (same payload twice) is idempotent on apply (member-add is no-op if already a member; epoch bump only fires once). But a record from an old epoch replayed at a later time: receiver drops via epoch check. OK.
7. **Member-list snapshot loss** — if a member goes offline before EPOCH_COMMIT arrives but after REMOVED arrives (or vice versa), their state may briefly be inconsistent. The protocol design considers this acceptable; the eventual delivery of both records reconciles. Reviewers should confirm.

Audit should happen once iOS lands and we have at least 4 hours of cross-platform soak.

---

## 13. Open questions for iOS team

1. Does iOS's `MessagingManager` already expose an equivalent of Android's `getContactGroup(Contact)` and `addLocalMessage(...)`? If not, what's the shortest path to write into the 1:1 channel from new code?
2. Where on iOS do you generate fresh randomness for `pqSeed`? Should match the source used by `PqRatchet` for ML-KEM-768 encapsulations (CSPRNG, same quality bar).
3. How does the iOS chat UI surface incoming events? On Android we use the existing EventBus; iOS appears to use `EventBus.shared` per the messaging code. Is hooking that the same pattern?
4. Stealth-name editor on iOS — recommend a per-group settings sheet matching the wire fields here. Android shipped a minimal "Set name in this group" dialog; iOS can match or improve.

---

## Repo pointers (Android implementation as reference)

| File | What |
|---|---|
| `briar-core/.../messaging/MessageTypes.java` | msgType 32–37 reservation |
| `briar-core/.../messaging/MessagingConstants.java` | metadata keys + signing labels |
| `briar-core/.../messaging/PrivateMessageValidator.java` | structural + signature validators |
| `briar-core/.../messaging/MessagingManagerImpl.java` | incoming dispatch → events |
| `briar-api/.../messaging/event/GroupPostReceivedEvent.java` | event class |
| `briar-api/.../messaging/event/GroupMembershipChangedEvent.java` | event class |
| `briar-api/.../messaging/event/GroupEpochCommitEvent.java` | event class |
| `briar-api/.../grouptr/GroupTrManager.java` | public API surface |
| `briar-core/.../grouptr/GroupTrManagerImpl.java` | state machine + send path |
| `briar-core/.../grouptr/GroupTrConstants.java` | labels, settings namespaces |
| `zerion-android/.../grouptr/GroupTrAdminActivity.java` | admin UI |
| `zerion-android/.../grouptr/GroupTrConversationActivity.java` | chat UI |

Commits on `dev` branch as of this writing: `b55ed17` (phase 1 wire), `86b33f2` (phase 2 state machine), `74ddbbb` (phase 3+4 send + admin), `4180052` (phase 5 admin UI), `956a24f` (TTL + stealth + chat UI + senderName field). Latest dev tip carries all of the above.
