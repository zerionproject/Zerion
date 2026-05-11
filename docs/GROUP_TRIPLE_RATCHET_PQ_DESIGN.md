# Group Triple Ratchet (Post-Quantum) Technical Design

**Version:** 0.1 (DRAFT)
**Date:** 2026-05-11
**Status:** PROPOSAL — pending cross-platform alignment with iOS
**Author:** Zerion Project

**Related documents**
- [PCS_DESIGN.md](PCS_DESIGN.md) — pairwise PCS specification (Mode 1/2/3)
- [TRIPLE_RATCHET_DESIGN.md](TRIPLE_RATCHET_DESIGN.md) — post-quantum ratchet specification
- [GROUP_PCS_SENDER_KEYS_DESIGN.md](GROUP_PCS_SENDER_KEYS_DESIGN.md) — current Sender Keys design (this proposal supersedes)

---

## Table of Contents

1. Executive Summary
2. Problem Statement
3. Why not MLS (yet)
4. Architecture
5. Wire Protocol
6. State Machine — membership
7. Send / Receive Flows
8. Member Add / Remove / Leave / Dissolve
9. Security Properties
10. Bandwidth and Latency Analysis
11. Migration from Sender Keys
12. Implementation Plan
13. Testing and Audit Plan
14. Open Questions

---

## 1. Executive Summary

### Goal

Bring group chat to the same security baseline as 1:1 chat:

- Forward Secrecy per message
- Post-Compromise Security via DH ratchet
- Hybrid post-quantum protection via ML-KEM-768 ratchet
- Cryptographic enforcement of member removal (a removed member cannot decrypt subsequent messages, even if they store ciphertexts they received before the removal)
- Replay protection
- Sender authentication (Ed25519 + ML-DSA-65 hybrid)

### Approach

Replace the existing Sender Keys architecture with **Pairwise Triple Ratchet**: for each group post, the sender encrypts the same plaintext N times — once per other member — through their existing 1:1 Triple Ratchet session with that member, then ships N records over their 1:1 contact channels with a `groupId` tag so receivers can re-assemble the group view.

Reuses the audited Mode-3 ratchet (`PcsRatchetImpl` + `PqRatchet`) from 1:1 chat verbatim. No new audited crypto.

### Tradeoffs

| Property | Sender Keys (today) | Pairwise Triple Ratchet (proposed) | MLS (future) |
|---|---|---|---|
| Per-message FS | Chain-key ratchet only | Triple Ratchet (X25519 + ML-KEM-768 + sym) | Tree ratchet |
| PCS on member compromise | Epoch refresh (manual) | Automatic per-message DH ratchet | Automatic |
| Hybrid PQ | Epoch only | Per-message ML-KEM-768 | Hybrid-PQ extension (draft) |
| Removal enforced cryptographically | No (relies on members ignoring removed peer) | Yes (next ratchet step locks them out) | Yes |
| Bandwidth | O(1) ciphertext per post | O(N) ciphertexts per post | O(log N) per change |
| Code reuse | Standalone sender keys | Reuses 1:1 ratchet 100 % | New protocol |
| Audit cost | Already done | Marginal (new wire glue only) | Full re-audit |

The bandwidth hit (N pairwise records per post instead of one broadcast record) is acceptable for the group sizes Zerion targets (≤ 50 members typical, hard cap 100). It buys us 1:1-grade security on the group surface without introducing a new crypto core to audit.

---

## 2. Problem Statement

### What Sender Keys gives us (today)

The current `SenderKeyManagerImpl` + `EpochRotationManagerImpl` design provides:

- Per-message forward secrecy via a symmetric chain-key ratchet inside each sender's outbound key
- Manual epoch refresh on membership change with ML-KEM-768 hybrid PQ
- Sender authentication via Ed25519 signature on each post

### What Sender Keys does not give us

1. **No PCS within an epoch.** If a member's device is compromised, the attacker learns their current chain key and can decrypt every group message that member sends until the next epoch rotation. There is no per-message DH refresh.

2. **Removal is cooperative, not cryptographic.** When the creator broadcasts a "remove member X" record, every remaining member is expected to start a new epoch and stop accepting X's messages. But the wire ciphertexts X already received remain decryptable by X. And if X is willing to forge — or if X colluded with another member who didn't rotate — X keeps a key derivable view.

3. **PQ is epoch-granular, not per-message.** The B.3 hybrid-PQ handshake runs only at epoch rotation. Within an epoch, posts are only protected by the symmetric ratchet and the underlying ML-KEM-768 epoch seed. A future store-and-decrypt attacker who breaks symmetric crypto in 30 years gets every message inside one epoch.

### What we need

Per-message FS + PCS + hybrid PQ + cryptographic removal enforcement, identical to what 1:1 chat already ships in B.3.

---

## 3. Why not MLS (yet)

MLS (RFC 9420) is the obvious "correct" answer for groups — tree-based ratchet, O(log N) operations, cryptographically enforced membership, hybrid-PQ extension in IETF draft. We are NOT proposing MLS for this release because:

1. **No production-quality MLS implementation exists for our stack.** OpenMLS is Rust; mlspp is C++; jmls (Kotlin) is alpha. Each adds a foreign-language dependency plus its own audit obligation. None has shipped hybrid PQ yet.

2. **Hybrid-PQ MLS is still drafting.** The IETF `mls-extensions` PQ work is expected to stabilise in Q3 2026. Adopting non-PQ MLS now and adding PQ later means a second migration.

3. **The Triple Ratchet we already audited is reusable.** Mode 3 (`PcsRatchetImpl` + `PqRatchet` + `XSalsa20Poly1305AuthenticatedCipher`) has the full Forward Secrecy + PCS + hybrid PQ guarantees we want. Wrapping it in a fan-out layer is a small glue change with no new crypto.

4. **Group sizes are small.** O(N) per send is fine when N ≤ 100. The asymptotic benefit of MLS only shows past ~200 members, which we do not target.

We MAY migrate to MLS later (≥ v2.0, 12+ months out) once hybrid-PQ MLS is standardised and a JVM-grade implementation exists. The pairwise design proposed here lives behind a wire-versioned record so the swap is cleanly possible.

---

## 4. Architecture

### 4.1 Transport

Group posts no longer ride Briar's group-sync BSP channel. Each member maintains a **1:1 contact channel** with every other member (already required today for sender-key distribution). A group post is sent as one private message per recipient over that contact's 1:1 channel, tagged with the group identifier.

This is the **same architectural change** the previous iOS v2 attempt was making. It is the correct direction; we are formalising it with proper cryptography this time.

### 4.2 Encryption layer

Each pairwise message is encrypted by the **existing 1:1 Mode 3 Triple Ratchet** for that contact:

- X25519 DH ratchet (provides PCS)
- ML-KEM-768 PQ ratchet (provides hybrid-PQ PCS)
- Symmetric chain ratchet (provides per-message FS)
- XSalsa20-Poly1305 AEAD on each record
- Per-record nonce derived from frame number (already implemented)

No new keys, no new derivations, no new AEAD constructions. The group-membership layer is wire-format-only.

### 4.3 Group identity

A group is still uniquely identified by its 32-byte `groupId` derived per Briar's existing `org.briarproject.bramble/GROUP_ID` label over the descriptor `[authorList, name, salt]`. Cross-platform compatible with current Briar groups.

The descriptor and groupId derivation are unchanged. The wire format above the AEAD is new.

### 4.4 Member representation

Each member is identified by their Ed25519 signing public key (32 bytes). The local store maps `(groupId, pubKey) → contactId` so the fan-out send path can find each recipient's 1:1 channel. There is no Briar `authorId` (hash) in the wire format — only raw pubkeys — to remove an entire class of cross-platform encoding mismatches.

---

## 5. Wire Protocol

All records ride the existing private-message 1:1 channel (`org.briarproject.briar.messaging`, `msgType` field at body[0]). Type numbers chosen to not collide with existing private-message types 0–9.

### 5.1 Record types

| msgType | Name | Notes |
|---|---|---|
| 32 | `GROUP_POST` | Encrypted group message body |
| 33 | `GROUP_MEMBER_ADDED` | Signed by group creator |
| 34 | `GROUP_MEMBER_REMOVED` | Signed by group creator |
| 35 | `GROUP_MEMBER_LEFT` | Signed by the leaver |
| 36 | `GROUP_DISSOLVED` | Signed by group creator |
| 37 | `GROUP_EPOCH_COMMIT` | Signed by creator — announces the epoch a removal commits to (see §8.3) |
| 38 | `GROUP_MEMBER_ROLE_CHANGED` | Signed by creator — promotes a member to admin or demotes back to member (multi-admin, see §8.5) |

Numbers reserve a clear band (32–63) for group-over-private records. No conflicts with current Briar group BSP types (0–3).

### 5.2 `GROUP_POST` body (msgType=32)

```
[
  32,                  // int — msgType
  groupId,             // raw 32B
  epoch,               // int — current group epoch (see §8.3)
  senderPubKey,        // raw 32B — Ed25519 signing pubkey of sender
  senderName,          // string — sender's display name for this post,
                       //          substituted with stealth alias if set
                       //          (see §8 — stealth name lives in the wire
                       //          so each post can carry a different name)
  body,                // raw — payload bytes; the 1:1 stream-level
                       //       Triple Ratchet provides the actual ratchet
                       //       FS / PCS / hybrid-PQ. The body is plaintext
                       //       after stream-decrypt.
  recordSig,           // raw 64B — Ed25519 sig over [groupId ‖ epoch(4 BE) ‖
                       //                            senderPubKey ‖
                       //                            blake2b(senderName) ‖
                       //                            blake2b(body)]
                       //         under label "org.briarproject.zerion/GROUP_POST"
  autoDeleteTimerMs    // int — OPTIONAL, omit for permanent messages
]
```

Body size: **7 or 8 BDF elements** (8 when `autoDeleteTimerMs` is appended).

The `body` is opaque to the group layer — the 1:1 stream-level Triple Ratchet (Mode 3: X25519 + ML-KEM-768 + symmetric chain) handles per-message FS + PCS + hybrid PQ for the bytes in transit. After the 1:1 decrypt, the `body` is the upper-layer payload (UTF-8 text in the current UI).

The `recordSig` defends against the only attack the pairwise 1:1 ratchet does not cover: a malicious contact tampering with a forwarded record before re-broadcasting. It is signed AFTER 1:1 decrypt at the group layer, so the signature covers the body-and-context.

### 5.3 Membership records (msgType 33–36)

```
GROUP_MEMBER_ADDED (33):
  [33, groupId, addedPubKey, addedName, epoch, timestamp, sig]

GROUP_MEMBER_REMOVED (34):
  [34, groupId, removedPubKey, fromEpoch, toEpoch, timestamp, sig]
  // toEpoch = fromEpoch + 1; the creator commits to this new epoch atomically

GROUP_MEMBER_LEFT (35):
  [35, groupId, leavingPubKey, epoch, timestamp, sig]

GROUP_DISSOLVED (36):
  [36, groupId, epoch, timestamp, sig]
```

All signatures are Ed25519 under label `"org.briarproject.zerion/GROUP_MEMBERSHIP"` over the canonical byte layout in §5.4.

### 5.4 Signed byte layouts (membership records)

```
ADDED    : groupId(32) ‖ addedPubKey(32)   ‖ epoch(4 BE)  ‖ timestamp(8 BE) ‖ 0x01
REMOVED  : groupId(32) ‖ removedPubKey(32) ‖ fromEpoch(4) ‖ toEpoch(4)      ‖ timestamp(8 BE) ‖ 0x02
LEFT     : groupId(32) ‖ leavingPubKey(32) ‖ epoch(4 BE)  ‖ timestamp(8 BE) ‖ 0x03
DISSOLVED: groupId(32) ‖ epoch(4 BE)       ‖ timestamp(8 BE) ‖ 0x04
```

Big-endian integers. Trailing single byte = action discriminator (0x01–0x04). Identical philosophy to existing Briar signing layouts; just different field set.

### 5.5 `GROUP_EPOCH_COMMIT` (msgType=37)

Sent by the creator atomically with `GROUP_MEMBER_REMOVED` (and bundled in the same BSP commit). Carries the post-removal epoch's PQ ratchet seed shared with every remaining member.

```
[
  37, groupId, fromEpoch, toEpoch,
  pqSeed,        // raw 32B — fresh randomness, ML-KEM-768-derived per-pair
                 //          for each recipient via the existing PqRatchet
  timestamp, sig
]
```

The `pqSeed` field is itself encrypted per-recipient through that pair's 1:1 Triple Ratchet — same construction as `GROUP_POST` ciphertext. This makes the new epoch's PQ rekey cryptographically inaccessible to the removed member.

Signed under `"org.briarproject.zerion/GROUP_EPOCH_COMMIT"` over `groupId ‖ fromEpoch(4) ‖ toEpoch(4) ‖ blake2b(pqSeed) ‖ timestamp(8) ‖ 0x05`.

---

## 6. State Machine — membership

### 6.1 Per-group local state

```
struct GroupState {
    groupId:       [u8; 32]
    name:          String
    salt:          [u8; 32]
    descriptor:    Bytes
    creatorPubKey: [u8; 32]
    epoch:         u32         // monotonically increases on any membership change
    dissolved:     bool
    members: List<{
        pubKey:    [u8; 32]
        name:      String
        joinedAt:  i64
        joinedAtEpoch: u32
    }>
    // Note: contactId is NOT stored here; resolved on-send via
    //       (pubKey -> contactId) lookup against the contact manager
}
```

### 6.2 Epoch semantics

`epoch` starts at 0 when the group is created. It increments by exactly 1 on:

- `GROUP_MEMBER_ADDED` accepted
- `GROUP_MEMBER_REMOVED` accepted
- `GROUP_MEMBER_LEFT` accepted

It does NOT increment on `GROUP_DISSOLVED` (dissolution is terminal). Every member tracks the same epoch counter; out-of-order delivery is handled by buffering records with `recordEpoch > localEpoch` until the gap fills.

`GROUP_POST` carries the sender's epoch view; recipients drop posts with `epoch < localEpoch - 1` (allow one epoch of lag for in-flight) and buffer posts with `epoch > localEpoch + 5` (resync needed; out of normal tolerance).

### 6.3 Validation invariants

Per inbound record:

1. groupId resolves to a known local group. Else drop silently.
2. Group is not dissolved. Else drop.
3. Signature verifies under the appropriate pubkey (creator for ADDED/REMOVED/DISSOLVED, leaver for LEFT, sender for POST).
4. For REMOVED: `removedPubKey != creatorPubKey`.
5. For LEFT: `leavingPubKey != creatorPubKey` (creator must dissolve).
6. For POST: `senderPubKey` is in the local member list AT THE EPOCH `epoch`. (We retain the last 5 epochs' member snapshots to allow in-flight posts during a transition.)

---

## 7. Send / Receive Flows

### 7.1 Send `GROUP_POST`

```
sendGroupPost(groupId, plaintext, ttlMs):
    g = getGroupState(groupId)
    timestamp = now()
    for each member in g.members where member.pubKey != localPubKey:
        contactId = lookupContact(member.pubKey)
        if contactId is None:
            log "missing contact for member, skipping"; continue
        ratchet = getOrCreatePcsRatchet(contactId)
        ciphertext = ratchet.encrypt(plaintext)        // existing Triple Ratchet
        sigInput = groupId ‖ epoch(4) ‖ localPubKey ‖ blake2b(ciphertext)
        sig = sign(GROUP_POST_LABEL, sigInput, localSigPrivKey)
        body = bdfEncode([32, groupId, g.epoch, localPubKey,
                          ciphertext, sig, ttlMs if > 0 else absent])
        sendPrivateMessage(contactId, body)
```

### 7.2 Receive `GROUP_POST`

```
onIncomingPrivateMessage(contactId, body):
    if body[0] != 32: dispatch as regular private message
    g = lookupGroup(body[1])
    if g is None or g.dissolved: drop
    epoch = body[2]
    senderPubKey = body[3]
    if !memberInEpoch(g, senderPubKey, epoch): drop
    ciphertext = body[4]
    sig = body[5]
    sigInput = body[1] ‖ epoch(4) ‖ senderPubKey ‖ blake2b(ciphertext)
    if !verify(GROUP_POST_LABEL, sigInput, sig, senderPubKey): drop
    ratchet = getOrCreatePcsRatchet(contactId)
    plaintext = ratchet.decrypt(ciphertext)             // existing Triple Ratchet
    deliverToConversationView(g, senderPubKey, plaintext)
    if body has element [6]: scheduleAutoDelete(plaintext.id, ttlMs)
```

The decrypt path REUSES `PcsStreamDecrypterImpl` exactly as 1:1 messages do — no new code in the crypto core.

---

## 8. Member Add / Remove / Leave / Dissolve

### 8.1 Add (creator unilateral, no invitee consent)

iOS v2 modelled adds as unilateral creator action. This proposal keeps that model — the receiver of `GROUP_MEMBER_ADDED` accepts the membership change immediately. Anyone who does not want to be added can `LEAVE` immediately on first seeing themselves in the member list.

Rationale: an invitation-with-consent flow doubles every membership round trip and we already have the 1:1 contact channel as the gate (you can only be added to a group by someone you have already accepted as a contact).

### 8.2 Leave (self-initiated)

The leaver signs `GROUP_MEMBER_LEFT` and broadcasts it via every other member's 1:1 channel. They locally mark the group as `dissolved=true` after sending. They stop sending and accepting posts for that groupId.

### 8.3 Remove (creator-initiated) — the load-bearing case

This is where Sender Keys is weak and Pairwise Triple Ratchet earns its keep. The creator:

1. Picks `removedPubKey`. Refuses if `removedPubKey == creatorPubKey`.
2. Bumps `epoch` from `fromEpoch` to `toEpoch = fromEpoch + 1`.
3. Builds `GROUP_MEMBER_REMOVED` (msgType=34) signed over the epoch transition.
4. Builds `GROUP_EPOCH_COMMIT` (msgType=37) carrying a fresh `pqSeed`.
5. Forces a DH ratchet step in EVERY remaining 1:1 pair (`PcsRatchetImpl.forceDhRatchetStep()`). This is the existing PCS-recovery primitive from B.3 — we just trigger it explicitly here.
6. Sends both records to every remaining member via their 1:1 channels. Does NOT send to the removed member.

The removed peer:
- Receives no `REMOVED` record on their channel.
- Continues their own 1:1 Triple Ratchet sessions with each member (still valid for direct 1:1 chat — they were not blocked, only un-grouped).
- Sees its own POSTs to the group rejected by every recipient via §6.3 invariant 6 (`epoch < toEpoch` is buffered, `senderPubKey` not in `toEpoch` member list).
- Cannot decrypt any subsequent group POST because the next ratchet step on each pair was triggered AFTER the removal commit — the new sending chain key is derived from a fresh DH + PQ seed the removed peer never saw.

**This is the cryptographic enforcement Sender Keys lacks.** Forward-secret AND post-compromise-secure.

### 8.4 Dissolve

Creator signs `GROUP_DISSOLVED`. All members on receipt mark group dissolved, attach a UI event. No further records accepted.

---

## 9. Security Properties

| Property | Guarantee | Mechanism |
|---|---|---|
| Per-message Forward Secrecy | Yes | Symmetric chain-key ratchet on each 1:1 pair (Mode 1) |
| Per-message Post-Compromise Security | Yes | X25519 DH ratchet on each 1:1 pair (Mode 2), one step per chain start |
| Hybrid Post-Quantum FS+PCS | Yes | ML-KEM-768 ratchet on each 1:1 pair (Mode 3), seed-refreshed every 25 messages OR 24 h |
| Cryptographic removal | Yes | Forced DH ratchet step in every surviving pair atomically with REMOVED record |
| Sender authentication | Yes | Per-post Ed25519 sig + per-1:1-message ratchet MAC |
| Replay protection | Yes | Frame counters in each 1:1 ratchet + epoch gate in §6.3 |
| Member-list integrity | Yes | All membership records signed by creator (or self for LEFT) |
| Metadata privacy from non-members | Yes | All wire records ride 1:1 Tor onion channels; no on-wire group identifier visible to anyone outside the member pair |
| Resistance to malicious creator | Partial | Creator can add unauthorised members (no invitee consent), but creator cannot decrypt members' 1:1 chats or forge POSTs. Counter-measure: stealth-name + LEAVE remediation in §8.1 |

### 9.1 Out-of-scope threats

- **Compromised member colluding with creator** — same as 1:1 chat: if Bob's device is fully compromised, Bob's group view is owned. Mitigated only by Bob's PCS recovery once the compromise ends.
- **Server-side traffic correlation** — Tor onion service guarantees apply; no group-specific tagging on the wire (one of the wins of moving off BSP for group POSTs).

---

## 10. Bandwidth and Latency Analysis

### 10.1 Per-post overhead

For a group of N members, sending one post costs N − 1 separate ratchet-encrypted records on the wire. Each record carries:

- Ratchet header (≈ 80 B for Mode-3 with per-message ML-KEM ciphertext chunk)
- Wrapped plaintext (variable)
- AEAD tag (16 B)
- Group signature (64 B)
- BDF framing (≈ 30 B)

Total fixed overhead per recipient ≈ **190 B + plaintext**.

For a 10-member group sending a 200-byte text:
- Sender Keys (today): ~300 B on wire (1 ciphertext)
- Pairwise Triple Ratchet (proposed): ~3500 B on wire (9 × ~390 B)

### 10.2 Tor latency

Each pairwise record traverses one Tor circuit (same as today's 1:1 messages). Sender dispatches in parallel; aggregate send latency ≈ slowest pair's circuit latency (typically 1–3 s over Tor).

### 10.3 Battery and CPU

Per send: N − 1 ML-KEM encapsulations on the sender side every ~25 messages (PQ ratchet step frequency). ML-KEM-768 encap is ~50 µs on modern ARM; 100 members × 1 encap every 25 messages ≈ negligible.

Receive side: 1 ML-KEM decap per ratchet step per pair. Same order.

### 10.4 Group-size ceiling

We cap groups at 100 members for v1. This gives a worst-case 99-record fan-out per post. Beyond ~200 members the bandwidth case for MLS becomes overwhelming and we revisit.

---

## 11. Migration from Sender Keys

### 11.1 Backwards compatibility

Existing Briar groups currently in flight on the network MUST keep working during the migration window. Strategy:

1. **Receive both formats.** Inbound msgType=3 (current SENDER_KEYS_POST) continues to be accepted and processed via existing Sender Keys path.
2. **Send the new format for groups created post-upgrade.** Old groups stay on Sender Keys; new groups use Pairwise Triple Ratchet. Distinguished by a flag in the group descriptor: `cryptoMode = "senderKeys" | "pairwiseRatchet"`.
3. **Group migrator (optional).** A separate tool can re-create a group with the new descriptor and ask members to re-join; this is the only path to upgrade an existing group.

### 11.2 What stays

- Group descriptor + groupId derivation
- Group invitation flow (Briar's existing CreatorProtocolEngine / InviteeProtocolEngine) for adding the first members
- 1:1 contact channels (we send all group records through them)
- The entire `PcsRatchetImpl` / `PqRatchet` / `XSalsa20Poly1305AuthenticatedCipher` stack
- AccountManager, identity management, signing key handling

### 11.3 What gets deleted (after migration period)

- `SenderKeyManagerImpl`
- `EpochRotationManagerImpl`
- `CapabilityManagerImpl`
- `SenderKeyDistributorImpl`
- `GroupMessageCryptoImpl`
- The msgType=3 (SENDER_KEYS_POST) branch of `GroupMessageValidator`

### 11.4 What changes

- `PrivateGroupManagerImpl` gets a new send path (`sendGroupPost` per §7.1) and a new dispatcher on incoming msgType 32–37
- New `GroupRatchetGateway` class that maps `(groupId, memberPubKey) → contactId → PcsRatchet` for the fan-out
- New event types: `GroupPostReceivedEvent`, `GroupMembershipChangedEvent`, `GroupEpochCommittedEvent`

---

## 12. Implementation Plan

### Phase 1 — Wire format (1 week)

- Reserve msgType 32–37 in `briar-core/.../messaging/MessageTypes.java`
- Extend `PrivateMessageValidator` to validate the new types
- Persist metadata under new `KEY_*` constants
- New event classes
- No state changes yet — receive-only acceptance

### Phase 2 — Receive path (1 week)

- `PrivateGroupManagerImpl` dispatches incoming msgType 32–37
- State changes apply (member-list updates, epoch bump, dissolved flag)
- Decrypt path connects to existing `PcsStreamDecrypterImpl` via the new `GroupRatchetGateway`
- No send-side changes yet — Android can receive iOS posts at this point

### Phase 3 — Send path (1 week)

- `GroupRatchetGateway` fan-out implementation
- `sendGroupPost` integrated into the conversation send UI
- Member add / remove / leave / dissolve user actions wired

### Phase 4 — Sender-key migration cutover (1 week)

- Group descriptor gains the `cryptoMode` flag
- Existing groups stay on Sender Keys; new groups default to Pairwise
- Optional manual "re-create with new crypto" tool

### Phase 5 — Audit + soak (2-4 weeks)

- External audit of the new wire layer (the crypto is already audited)
- Cross-platform interop testing with iOS
- Property tests covering the §6.3 invariants
- Fuzz the validators

**Total estimate: 6–8 weeks for one engineer**, longer with both platforms working in parallel.

---

## 13. Testing and Audit Plan

### 13.1 Unit tests

- Membership-record signature verification: every action discriminator (0x01–0x05) under both valid and tampered byte layouts
- Epoch state machine: out-of-order delivery, gap filling, far-future buffering, far-past dropping
- Fan-out send: verify N records produced per group of N members, each addressed to the correct contact
- Receive-side invariants: §6.3 1–6

### 13.2 Property tests (`bramble-core/.../crypto/...`)

- Forced DH ratchet step on remove → removed peer's stored ciphertexts no longer decrypt under any surviving pair
- Sender-id spoof: forge a POST with a different `senderPubKey` than the 1:1 channel's contact → must drop on §6.3 invariant 3 AND on the per-pair ratchet header mismatch

### 13.3 Cross-platform tests

- iOS sends `GROUP_MEMBER_ADDED` → Android updates member list → Android sends `GROUP_POST` → iOS decrypts via its pairwise Triple Ratchet
- Symmetric tests for every msgType combination

### 13.4 External audit scope

- Wire-format soundness (this document)
- Fan-out logic (no plaintext leak on partial-send failure)
- Epoch commit atomicity (cannot apply REMOVED without EPOCH_COMMIT)
- Stealth-name handling (no cross-group correlation)

NOT in audit scope (already audited):
- `PcsRatchetImpl`, `PqRatchet`, `XSalsa20Poly1305AuthenticatedCipher`
- B.3 hybrid-PQ handshake
- AccountManager / SQLCipher

---

## 14. Open Questions

1. **Concurrent membership changes.** If two creators (in a hypothetical multi-admin extension) bump epoch concurrently, how do we resolve? Current proposal: single-creator-only, no concurrency. Multi-admin deferred.

2. **Member-list authority during epoch gap.** If a member is offline when REMOVED is broadcast and comes back online after EPOCH_COMMIT has fully propagated, they need a "current state" refresh. Proposal: a periodic `GROUP_MEMBER_LIST_SNAPSHOT` record signed by the creator, sent every 24 h or on first contact after offline period.

3. **Stealth name vs. signed sender pubkey.** Stealth names need to survive the per-post signature. Proposal: stealth name lives outside the signed input — only `senderPubKey` is signed, the displayed name is a hint the receiver can choose to honour or override locally.

4. **MLS migration trigger.** What's the threshold to move? Proposal: when ≥ 30 % of active groups exceed 100 members, OR hybrid-PQ MLS stabilises with an audited JVM implementation.

5. **TTL semantics under fan-out.** Today's iOS-style TTL is per-message. Should it be per-recipient (each pair can have its own auto-delete) or strictly uniform? Proposal: uniform — the sender picks one TTL, every recipient honours the same value. Per-recipient was never iOS's model anyway.

---

## Repository pointers

- `bramble-core/src/main/java/org/briarproject/bramble/crypto/pcs/` — Triple Ratchet implementation, reuse as-is
- `bramble-core/src/main/java/org/briarproject/bramble/crypto/PqRatchet*` — ML-KEM-768 ratchet, reuse as-is
- `briar-core/src/main/java/org/briarproject/briar/messaging/MessageTypes.java` — reserve 32–37 here
- `briar-core/src/main/java/org/briarproject/briar/messaging/PrivateMessageValidator.java` — wire validators land here
- `briar-core/src/main/java/org/briarproject/briar/privategroup/PrivateGroupManagerImpl.java` — dispatch + state machine
- `zerion-android/src/main/java/com/professor/zerion/android/privategroup/` — UI changes (admin buttons, member list)

iOS source: `c:\Users\Iron\Desktop\Zerion App\Zerion Ios\Packages\ZerionMessaging\Sources\ZerionMessaging\Group\` — equivalent layer once both sides agree on this doc.
