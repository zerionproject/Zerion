# GroupTr — Group Triple Ratchet wire protocol

iOS parity for Zerion group chat. Android implementation: `briar-core/.../grouptr/GroupTrManagerImpl.java` plus the validator at `briar-core/.../messaging/PrivateMessageValidator.java`. Shipped on Android since 1.5; admin-signature verify path corrected in 1.6 (commit `06f95a7`).

## The one architectural fact that fixes Peter's missing invite

**There is no "group invite" or "accept" wire message.** GroupTr replaced the legacy Briar private-group invitation protocol entirely. When Alice (creator/admin) adds Peter to a group:

1. Alice's app builds a single `GROUP_MEMBER_ADDED` record (msgType 33).
2. Alice sends that record over the **existing pairwise private-message channel** with each current group member, including Peter himself.
3. On Peter's device, his private-message validator dispatches by the first BdfList element (`33L`), routes the record to the group-membership handler, fires a `GroupMembershipChangedEvent`, and the GroupTr manager applies it locally — Peter's group list now shows the new group.

Peter does NOT see anything in his chat thread with Alice. The record carries `MSG_KEY_LOCAL=false` and is consumed silently by the membership state machine. The group appears in Peter's Groups tab as if he had always been a member.

**If iOS's "invite never arrives in single chat" bug is because iOS is trying to send/receive an INVITE message and waiting for an ACCEPT, you have the wrong protocol entirely.** GroupTr is fan-out-of-signed-records, not invitation-and-accept.

## Transport

All GroupTr records ride over the same pairwise messaging channel as private messages. No new sync-client, no new group-message group ID, no new transport. Each record is a BdfList whose first element is the msgType integer.

```
Alice's pairwise messaging Group with Peter (Briar contact group)
                    |
                    |  GROUP_MEMBER_ADDED record (msgType 33)
                    v
                Peter's app
                    |
                    v
        PrivateMessageValidator dispatches by msgType
                    |
                    v
        validateGroupMemberAdded() — parses + signs metadata
                    |
                    v
        MessagingManagerImpl.incomingGroupMembership() — fires event
                    |
                    v
        GroupTrManagerImpl.handleMembershipEvent() — verifies sig,
                                                     applies state
```

## Message types (Android `MessageTypes.java`)

| msgType | Constant | Purpose |
|---|---|---|
| 32 | `GROUP_POST` | Encrypted message in a group |
| 33 | `GROUP_MEMBER_ADDED` | The "invite". Adds a new member at a new epoch. |
| 34 | `GROUP_MEMBER_REMOVED` | Admin/creator removes a member; epoch bumps. Always followed by 37. |
| 35 | `GROUP_MEMBER_LEFT` | A member voluntarily leaves. |
| 36 | `GROUP_DISSOLVED` | Creator dissolves the group. |
| 37 | `GROUP_EPOCH_COMMIT` | Confirms an epoch change with a PQ seed. Paired with 34. |
| 38 | `GROUP_MEMBER_ROLE_CHANGED` | Creator promotes/demotes a member to/from admin. |
| 41 | `GROUP_MEMBER_LIST_SNAPSHOT` | Full member-list snapshot at a given epoch (for repair / late joiners). |

`32`'s wire format is documented separately; this doc covers 33–38 + 41 (the membership records).

## Wire format — every membership record

All BdfLists, encoded with the existing private-message BDF encoder. Byte counts assume Android's `BdfWriter`.

### 33 — GROUP_MEMBER_ADDED

```
BdfList.of(
    33L,                       // msgType (Long)
    groupId,                   // 32-byte groupId (raw bytes)
    addedPubKey,               // 32-byte Ed25519 pubkey of new member (raw bytes)
    addedName,                 // UTF-8 string (1..256 bytes)
    newEpoch,                  // Long, range [0, 2^32-1]
    timestamp,                 // Long, signed
    sig                        // signature, raw bytes (length 64 OR 3373 — see "Signing" below)
)
```

Validator size: exactly **7 slots**.

### 34 — GROUP_MEMBER_REMOVED

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

**Must be paired with msgType 37 (`GROUP_EPOCH_COMMIT`)** on the same outgoing send. Order doesn't matter for the receiver but Android dispatches both back-to-back inside the same DB transaction.

### 35 — GROUP_MEMBER_LEFT

```
BdfList.of(
    35L,                       // msgType
    groupId,                   // 32 bytes
    leavingPubKey,             // 32 bytes (sender's own pubkey)
    newEpoch,                  // Long
    timestamp,                 // Long
    sig                        // signature
)
```

Validator size: **6 slots**. Signature is verified against the LEAVING member's pubkey (it's a self-attestation, not an admin action).

### 36 — GROUP_DISSOLVED

```
BdfList.of(
    36L,                       // msgType
    groupId,                   // 32 bytes
    newEpoch,                  // Long
    timestamp,                 // Long
    sig                        // signature — must be the CREATOR's key
)
```

Validator size: **5 slots**. Creator-only on Android receiver; admins cannot dissolve.

### 37 — GROUP_EPOCH_COMMIT

```
BdfList.of(
    37L,                       // msgType
    groupId,                   // 32 bytes
    fromEpoch,                 // Long
    toEpoch,                   // Long (== fromEpoch + 1)
    pqSeed,                    // 32 random bytes (mixed into the post-quantum ratchet root)
    sig                        // signature
)
```

Validator size: **6 slots**. Sent immediately after any record that changes the epoch (currently just msgType 34). The pqSeed is hashed under label `"org.briarproject.zerion/GROUP_EPOCH_SEED"` into the signed-input.

### 38 — GROUP_MEMBER_ROLE_CHANGED

```
BdfList.of(
    38L,                       // msgType
    groupId,                   // 32 bytes
    targetPubKey,              // 32 bytes
    newRole,                   // Long: 0 = MEMBER, 1 = ADMIN, 2 = CREATOR (never sent — creator role is fixed)
    epoch,                     // Long
    timestamp,                 // Long
    sig                        // signature — CREATOR ONLY
)
```

Validator size: **7 slots**. Creator-only on Android receiver. `newRole` must be in `[0, 2]`.

### 41 — GROUP_MEMBER_LIST_SNAPSHOT

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
    name,          // UTF-8, 0..256 bytes
    joinedAt,      // Long (timestamp)
    joinedAtEpoch, // Long, [0, 2^32-1]
    role           // Long, 0..2
)
```

Max 1000 members per snapshot.

## Signed-input format (byte-exact)

Each record carries a signature over a deterministic byte string. **iOS must produce the exact same bytes** or Android rejects on `crypto.verifySignature`.

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
[32B groupId][4B BE fromEpoch][4B BE toEpoch][32B BLAKE2b(label="org.briarproject.zerion/GROUP_EPOCH_SEED", pqSeed)][8B BE timestamp][0x05]
total: 89 bytes
```

The pqSeed itself is NOT in the signed-input — its hash is. Label is the hash function's domain-separation prefix.

### ROLE_CHANGED (`roleChangedSignedInput`)

```
[32B groupId][32B targetPubKey][1B newRole][4B BE epoch][8B BE timestamp][0x06]
total: 78 bytes
```

### LIST_SNAPSHOT (`snapshotSignedInput`)

```
mlHash = BLAKE2b(label="org.briarproject.zerion/GROUP_MEMBER_LIST", memberCanonical)
signedInput = [32B groupId][4B BE epoch][8B BE timestamp][32B mlHash][0x07]
total: 77 bytes
```

Where `memberCanonical` is the concatenation of `[32B pubKey][1B role][4B BE joinedAtEpoch]` for each member, in the order they appear in the BdfList.

## Signing (sender side)

Identical pattern to F-2 hybrid signatures: hybrid-sign with Ed25519 + ML-DSA-65 when the local identity has an ML-DSA-65 private key, else fall back to Ed25519.

```
def signOrThrow(label, signed, ed25519PrivateKey):
    mlDsaPriv = identityManager.getLocalMlDsaSigPrivateKey()
    if mlDsaPriv is not None:
        hybridKey = HybridSignaturePrivateKey(
            ed25519=ed25519PrivateKey.encoded,  # 32 bytes
            mlDsa=mlDsaPriv                     # 4032 bytes
        )
        return crypto.hybridSign(label, signed, hybridKey)
        # returns 3373 bytes = 64 (Ed25519) + 3309 (ML-DSA-65)
    else:
        return crypto.sign(label, signed, ed25519PrivateKey)
        # returns 64 bytes
```

Labels used in GroupTr:

- `"org.briarproject.zerion/GROUP_MEMBERSHIP"` for msgType 33, 34, 35, 36, 38
- `"org.briarproject.zerion/GROUP_EPOCH_COMMIT"` for msgType 37
- `"org.briarproject.zerion/GROUP_MEMBER_LIST_SNAPSHOT"` for msgType 41
- `"org.briarproject.zerion/GROUP_POST"` for msgType 32 (separate spec)

(Constants live in `briar-core/.../grouptr/GroupTrConstants.java`.)

## Verification (receiver side, Android 1.6 path)

For each membership record, after the validator's structural check, `GroupTrManagerImpl.handleMembershipEvent` runs:

```
1. Resolve sender's pubkey from the pairwise ContactId on the incoming Event.
2. Compute the signed-input (same function as the sender used).
3. Pick the verifying key:
     - MEMBER_ADDED, MEMBER_REMOVED, EPOCH_COMMIT:
         must be CREATOR or current ADMIN
     - MEMBER_LEFT:
         must be the targetPubKey itself
     - DISSOLVED, ROLE_CHANGED:
         must be the CREATOR
4. Dispatch on signature length:
     if sig.length == 3373 AND peer's ML-DSA pubkey is known:
         verify hybrid (HybridSignaturePublicKey)
     else if sig.length == 64:
         verify Ed25519 only
     else if sig.length == 3373 AND peer's ML-DSA pubkey is unknown:
         take first 64 bytes (Ed25519 prefix) and verify Ed25519
5. If verification fails: silently drop the record (no log in production
   per project policy).
```

**The admin verify on items 33/34/37 was creator-only before commit `06f95a7`.** If your iOS receiver is still creator-only, admin-sent removes will silently fail there too. Mirror the verify-against-creator-OR-current-admin logic.

## State machine — receive

Given a verified MEMBER_ADDED record:

```
GroupTrManagerImpl.applyMemberAdded(state, event):
    if any existing member already has this pubKey: return (idempotent)
    if state.epoch is not (event.epoch - 1): drop  (out-of-order — buffer for later)
    state.members.append(new member with role MEMBER)
    state.epoch = event.epoch
    persist
    drain future-buffer for this group
    fire MembershipChangedEvent to UI
```

Out-of-order future-epoch events are buffered (up to 5 epochs ahead, 500 events total per group) until the gap closes. This is critical for partial-network-partition recovery.

## What the iOS team needs to do to fix Peter's invite

Concrete checklist:

1. **Remove any iOS code that expects a Briar-style group "invitation" protocol** (with INVITATION_REQUEST / INVITATION_ACCEPT records or anything message-visible in a chat thread). GroupTr does not use this.
2. **In the iOS group-create UI**: `createGroup(name)` must be purely local. Do NOT send anything over the wire when a group is created. The group is invisible to peers until the first `addMember` call.
3. **In the iOS "add member" handler**: build the msgType-33 record exactly as specified above, sign with the hybrid key, and send it to the new member AND every other existing member over their pairwise messaging channels.
4. **In the iOS private-message receive path**: when a record's first BdfList element is `33L`, route to a membership handler. Do NOT show it as a visible chat message. Do NOT require any user "accept" action. Just verify the signature and apply.
5. **In the iOS group state machine**: a MEMBER_ADDED record where the addedPubKey matches the local user is the trigger that ADDS THE GROUP TO THE LOCAL GROUP LIST. No accept-button required. The group appears immediately.
6. **Hybrid signatures**: every signed record must be signed with `hybridSign(label, signedInput, HybridSignaturePrivateKey)` when ML-DSA-65 private key is present locally. Verify on receive with length-dispatch as described above.
7. **Wire format byte-exactness**: pay close attention to big-endian encoding of `epoch` (4 bytes) and `timestamp` (8 bytes) in the signed-inputs. Off-by-one or endianness errors will produce signatures Android rejects.

## What this does NOT cover

- msgType 32 (`GROUP_POST`) — separate spec, see `GROUP_TRIPLE_RATCHET_PQ_DESIGN.md`.
- Forward secrecy / post-compromise security ratcheting inside the group — that's a property of how the per-message keys are derived from the PCS root, also in the PQ design doc.
- Recovery from missing membership records — partially covered by msgType 41 snapshot but not fully (no spec for triggering a snapshot send).

## Quick interop sanity test for iOS team

When iOS is wired up, the smallest test that proves the protocol works end-to-end:

1. Android user (Alice) creates a group named "Test".
2. Alice calls `addMember(group.id, peter.pubkey, peter.name)`.
3. Alice's app sends msgType-33 over the pairwise Tor channel with Peter.
4. Peter's iOS Zerion receives, validates the signature, applies the state.
5. **Peter sees the group "Test" appear in his Groups tab** — without ever opening his chat thread with Alice and without any "Accept group invite?" dialog.

If that flow fails on iOS, the bug is in steps 4–5 (receive routing or state apply). Send the BdfList bytes of the msgType-33 record from the wire dump and Android can verify byte-exact equality against what its validator expects.
