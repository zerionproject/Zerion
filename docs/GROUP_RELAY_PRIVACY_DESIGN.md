# Group chat relay-based privacy — design proposal

**Version:** 0.1 (DRAFT — pending review + Trail of Bits audit)
**Date:** 2026-05-11
**Status:** PROPOSAL only, NOT implemented
**Author:** Zerion Project

**Related**

- [GROUP_TRIPLE_RATCHET_PQ_DESIGN.md](GROUP_TRIPLE_RATCHET_PQ_DESIGN.md) — current shipped design, requires every pair of members to be 1:1 contacts
- [GROUP_TRIPLE_RATCHET_IOS_HANDOFF.md](GROUP_TRIPLE_RATCHET_IOS_HANDOFF.md) — current shipped wire spec
- [TRIPLE_RATCHET_DESIGN.md](TRIPLE_RATCHET_DESIGN.md) — 1:1 hybrid PQ Triple Ratchet (Mode 3)
- [PCS_DESIGN.md](PCS_DESIGN.md) — pairwise PCS Mode 1/2/3 specification

---

## Problem

The currently shipped Group Triple Ratchet (Phase 1–5 on dev) requires every pair of members to be 1:1 contacts. If Hans creates a group with Vincent and George — who don't know each other — then Vincent has no 1:1 channel with George, so Vincent's group posts cannot reach George. The current implementation silently drops fan-out attempts to members with no contact relationship.

Trying to fix this naively means **leaking onion addresses**: when Hans adds Vincent to the group with George, Vincent's app would need George's onion address to establish a 1:1 channel — which means Hans is implicitly publishing George's contact info to Vincent. The user has explicitly flagged this as unacceptable.

---

## Goal

Vincent and George can exchange group posts **without ever learning each other's onion addresses** (no implicit contact-add) and **without Hans (or any other member) being able to decrypt their messages** (true E2E inside the group).

Security baseline must match the rest of Zerion:

- Hybrid post-quantum: X25519 + ML-KEM-768 for key agreement, Ed25519 + ML-DSA-65 for signatures.
- Per-message forward secrecy.
- Post-compromise security via per-pair DH + PQ ratchet step on epoch rotation.
- Cryptographic removal enforcement: removed members cannot decrypt subsequent posts.

---

## Approach

Each member publishes ephemeral key-agreement pubkeys at group join time, alongside their signing pubkey. Members derive **pairwise shared secrets** from each other's published material without any direct communication. Group posts are encrypted under derived per-pair keys; ciphertexts are routed via existing 1:1 channels (the creator or any member with a 1:1 contact relationship to the recipient acts as a forwarder, but cannot decrypt the forwarded bytes).

This is essentially MLS-style group state but kept lightweight: O(N) keys per member, no tree, no commits-and-proposals machinery — relies on the existing creator-signed `GROUP_MEMBER_ADDED` / `GROUP_MEMBER_REMOVED` records as the source of truth for who's a member at which epoch.

### Key material per member, published in `GROUP_MEMBER_ADDED`

The current wire record `GROUP_MEMBER_ADDED` (msgType=33) needs three new fields:

```
[33, groupId, addedPubKey, addedName, addedX25519Pub, addedMlKemPub,
 epoch, ts, sig]
```

- `addedX25519Pub` — 32-byte X25519 public key (the member's ephemeral DH key for this group; **regenerated per group**, never reused across groups)
- `addedMlKemPub` — 1184-byte ML-KEM-768 encapsulation key (the member's ephemeral PQ key for this group; regenerated per group)
- Both stored in the local `GroupTrState.members[i]` so all current members know everyone's per-group key material.

The signature input is extended to cover the new fields (BLAKE2b-hash both pubkeys into the signed input so we don't blow up the input size).

The member's local app holds the matching private keys in the SQLCipher-encrypted DB, indexed by `(groupId, memberId=self)`. They are deleted when the group is dissolved or when the member is removed.

### Pairwise shared secret derivation

For any two members A and B with published `(X25519_A, MLKEM_A)` and `(X25519_B, MLKEM_B)`:

```
// Both sides can compute the same shared secret independently.

dh_shared    = X25519(A.x25519_priv, B.x25519_pub)
                 OR equivalently
                 X25519(B.x25519_priv, A.x25519_pub)

// PQ side: whoever initiates encapsulates; the resulting ciphertext is
// shipped once and stored locally on both sides. After the first exchange,
// the encapsulated shared secret is the long-term PQ contribution.
pq_ct, pq_shared = MLKEM_768_Encaps(peer's MLKEM_pub)   // sender side
pq_shared        = MLKEM_768_Decaps(self_MLKEM_priv, pq_ct)  // peer side

pair_key_root = HKDF-Extract(salt=groupId,
                             ikm = dh_shared ‖ pq_shared)

pair_chain_key_at_epoch_E =
    HKDF-Expand(pair_key_root,
                info = "org.briarproject.zerion/GROUP_PAIR_CHAIN" ‖
                       sorted_pair_pubkeys ‖ uint32be(E),
                L = 32)
```

`sorted_pair_pubkeys` = the two Ed25519 signing pubkeys concatenated in lexicographic order — makes the derivation symmetric so A and B compute the same bytes.

Each pair has a unique chain key per epoch. Forward secrecy comes from chain-key ratcheting per message within an epoch. PCS comes from epoch rotation (new PQ encapsulation on each epoch — see §3).

### Routing: who delivers the ciphertext

When member A sends a group post, A produces N − 1 ciphertexts (one per other member). For each recipient R:

- If A has a 1:1 contact relationship with R → A sends directly over that 1:1 channel (same as today's design).
- If A does NOT have a 1:1 relationship with R → A picks a **forwarder**: someone A has a 1:1 channel with AND who has a 1:1 channel with R. A sends the ciphertext to the forwarder with a `forward_to=R.pubkey` wrapper. The forwarder relays.
  - Preferred forwarder order: the creator first, then any other admin, then any member who's likely to be online (last-seen heuristic).
  - The forwarder CANNOT decrypt — the ciphertext is under `pair_chain_key(A, R)` which the forwarder doesn't have.

The forwarder learns: A sent something for R. They don't learn the contents. Metadata (sender, recipient, group, epoch, timestamp) is visible to the forwarder for routing purposes. This is acceptable — the forwarder is a fellow group member who already knows everyone is a member.

### Removal — cryptographic enforcement

When the creator removes B, a `GROUP_EPOCH_COMMIT` (msgType=37) carries a fresh per-epoch `pqSeed` and an instruction for every remaining member to **rotate their ML-KEM ephemeral key** for the group. Each surviving member:

1. Generates a fresh ML-KEM-768 keypair.
2. Publishes the new pubkey via a new wire record `GROUP_MEMBER_KEY_ROTATED` (msgType=39).
3. Discards the old keypair.

After every survivor has rotated, B no longer has the private keys needed to decrypt any subsequent post — because:

- Every subsequent `pair_chain_key(X, Y)` for surviving X, Y derives from the new ML-KEM shared secret which B never received.
- The X25519 contribution alone is broken if a future quantum attacker decrypts B's stored ciphertexts, but the PQ contribution from fresh ML-KEM after rotation defeats that too.

The rotation can be **async** — survivors may rotate on different schedules. The `GROUP_EPOCH_COMMIT` carries the deadline: posts sent after `commit.timestamp + N hours` from members who haven't published a rotated key are rejected by other survivors as "stale". Sane default: N = 6 hours.

### Wire format additions (relative to currently shipped Phase 1–5)

New msgTypes reserved alongside the existing 32–38:

```
GROUP_MEMBER_KEY_ROTATED = 39   // member published a new MLKEM pubkey
GROUP_FORWARDED          = 40   // wrapper for relay-routed ciphertext
```

`GROUP_FORWARDED` body:

```
[
  40,
  raw(32),     // groupId
  raw(32),     // intendedRecipientPubKey
  raw,         // wrappedCiphertext — opaque to forwarder
                // = encrypted under pair_chain_key(originalSender,
                //                                  intendedRecipient)
  raw(32),     // originalSenderPubKey
  int,         // originalEpoch
  int,         // ts
  raw(64)      // sig — Ed25519 by originalSender over
                // groupId ‖ recipient ‖ blake2b(wrappedCiphertext) ‖
                // epoch(4) ‖ ts(8) ‖ 0x07
]
```

The forwarder receives `GROUP_FORWARDED`, sees `intendedRecipientPubKey != self.pubkey`, looks up its 1:1 contact for that recipient, and re-sends the entire body (unchanged) as a new `GROUP_FORWARDED` record on that contact's channel. Signature stays intact — the recipient verifies the originalSender's sig directly, not the forwarder's.

`GROUP_MEMBER_KEY_ROTATED` body:

```
[
  39,
  raw(32),     // groupId
  raw(32),     // memberPubKey (Ed25519, = signer)
  raw(1184),   // newMlKemPub
  int,         // epoch (= epoch from EPOCH_COMMIT that triggered this rotation)
  int,         // ts
  raw(64)      // sig — Ed25519 by member over
                // groupId ‖ memberPubKey ‖ blake2b(newMlKemPub) ‖
                // epoch(4) ‖ ts(8) ‖ 0x08
]
```

The new pubkey replaces the member's existing MLKEM key in local `GroupTrState`. Pairwise shared secrets with this member are re-derived from the new key on next use.

`GROUP_POST` body changes (msgType=32):

```
[32, groupId, epoch, senderPubKey, senderName,
 perPairCiphertexts, sig, ttl?]
```

where `perPairCiphertexts` is a BdfList of N − 1 entries `[recipientPubKey, ciphertext_for_that_recipient]`. Each ciphertext is encrypted under that pair's chain key + nonce. The sender ships ALL N − 1 entries inside one BDF body so peer fan-out is one-message-per-channel even for big groups.

(Tradeoff: one bigger record per channel rather than N − 1 smaller ones. Bandwidth is similar; logic simpler.)

### State machine adjustments

- `GroupTrState.members[i]` adds `x25519Pub` (32 B) and `mlKemPub` (1184 B).
- Local secret store: `GroupTrPrivateKeys` per group, persisted in SQLCipher. Holds `(x25519_priv, mlKemPair_current, mlKemPair_pending_during_rotation)`. Generated at `createGroup` time for the creator; generated at first-receipt of own `GROUP_MEMBER_ADDED` for invitees.
- Pairwise chain-key cache: `(groupId, peerPubKey, epoch) → chain_key` LRU map, lazy-derived on first use. Each entry rotates per epoch.

### Send / receive flow

**Send (text post):**
1. Compute `epoch = state.epoch`.
2. For each other member X, derive `K(self, X, epoch)`.
3. Encrypt `body` under each `K(self, X, epoch)` with a per-message nonce (XSalsa20-Poly1305).
4. Build `perPairCiphertexts` list.
5. Sign per §5.4 of the original handoff doc (with updated signed-input that hashes the perPairCiphertexts list).
6. For each recipient: if 1:1 channel exists, send directly via `GROUP_POST`; else wrap in `GROUP_FORWARDED` and ship to a forwarder.

**Receive (direct `GROUP_POST`):**
1. Validate signature against `senderPubKey`.
2. Look up own slot in `perPairCiphertexts` by matching recipient = self.pubkey.
3. Derive `K(sender, self, epoch)` if not cached.
4. Decrypt own ciphertext slot.
5. Deliver upward as a `GroupTrPost`.

**Receive (`GROUP_FORWARDED` for self):**
1. Validate `originalSender` sig.
2. Decrypt `wrappedCiphertext` under `K(originalSender, self, epoch)`.
3. Deliver.

**Receive (`GROUP_FORWARDED` not for self):**
1. Validate sig (sanity).
2. Look up own contact for `intendedRecipientPubKey`. If found, re-emit on that channel. Else drop (we're not a valid forwarder for this hop).

### Forwarder reliability

Each member acts as forwarder for the subset of peers they're 1:1-connected to. In a group where everyone knows the creator but no one knows each other else, the creator is the unique forwarder for every pair. In a denser graph, multiple forwarders exist and the sender picks heuristically.

Failure mode: a recipient is reachable only via a forwarder who is offline. The sender retries on a different forwarder (if available) after a short backoff. If no forwarder is available, the post stays queued locally and ships on next online state change of any candidate forwarder.

### Privacy properties under the relay design

| Concern | Outcome |
|---|---|
| Onion addresses leak between non-contact members | NO — onion addresses are never on the wire; forwarders only know the recipient pubkey, not their onion address. |
| Forwarder can decrypt forwarded messages | NO — `wrappedCiphertext` is encrypted under `pair_chain_key(originalSender, recipient)` which the forwarder doesn't have. |
| Forwarder can replay or tamper | Replay → caught by per-message nonce + frame-counter dedup at recipient. Tamper → invalidates the originalSender's Ed25519 signature. |
| Forwarder learns sender → recipient mapping | YES — accepted tradeoff. Mitigation: rotate forwarder per message (sender picks a different forwarder each time). |
| Removed member decrypts subsequent posts | NO — after `GROUP_EPOCH_COMMIT` + every survivor's `GROUP_MEMBER_KEY_ROTATED`, the removed peer's PQ contribution to any pair key is no longer derivable. |
| Member compromise reveals other pairs' messages | NO — each pair has its own chain key derived independently. Compromise of member A's keys reveals only A's chains with each peer, not pairs between other members. |

### Cost

- Per group, per member: 32 B (X25519 priv) + ~2 KB (ML-KEM priv) + ~1.2 KB ML-KEM pub for each peer ≈ ~120 KB cached pubkeys for a 100-member group. Acceptable.
- Per post: N − 1 ciphertext slots × (~50 B payload + 16 B AEAD tag) inside one BDF body. For a 200-byte post in a 10-member group: ~700 B body + signatures.
- Per epoch rotation: every survivor publishes one `GROUP_MEMBER_KEY_ROTATED` (~1280 B body + sig). For a 10-member group: ~12 KB of rotation traffic.

### Migration from currently shipped Phase 1–5

- `GROUP_POST` body format changes incompatibly (single `body` field becomes `perPairCiphertexts` list).
- `GROUP_MEMBER_ADDED` body adds two pubkey fields incompatibly.
- Recommend bumping a per-group `cryptoMode` flag in the descriptor: `cryptoMode == 0` = current Phase 1–5 (works for fully-connected groups), `cryptoMode == 1` = relay-based with hybrid-PQ pairwise keys.
- Old groups stay on `cryptoMode == 0`; new groups default to `cryptoMode == 1`. Manual upgrade tool for users who want to migrate an existing group.

### Implementation phases

1. **Wire layer** — new msgTypes 39, 40; expanded GROUP_MEMBER_ADDED and GROUP_POST. ~2 days.
2. **Per-group key generation + storage** — generate X25519 + ML-KEM keypairs at group join, persist in SQLCipher. ~3 days.
3. **Pair key derivation + cache** — HKDF chain, per-(group, peer, epoch) LRU. ~2 days.
4. **Encrypt/decrypt path** — XSalsa20-Poly1305 under per-pair keys. ~3 days.
5. **Forwarder selection + re-emit** — graph traversal, retry on failure, online-state heuristic. ~5 days.
6. **Epoch rotation** — `GROUP_MEMBER_KEY_ROTATED` send/receive, stale-key gating. ~3 days.
7. **Cross-platform interop with iOS** — both teams aligned. ~1 week.
8. **External audit (Trail of Bits)** — wire layer + key schedule + rotation atomicity. ~3-4 weeks elapsed.

**Total**: roughly 3 weeks of implementation per platform + audit. The audit is the gating step.

### Pre-implementation questions to settle

1. ML-KEM-768 chosen for parity with B.3. Confirm vs. ML-KEM-1024 for higher security margin in groups (where compromised pair keys = more leverage than 1:1).
2. Forwarder reliability under partial connectivity — should we send each post to multiple forwarders in parallel (faster delivery, more bandwidth) or one at a time with retry (slower, less bandwidth)?
3. Epoch rotation deadline (`N = 6 hours` in §3) — too tight for offline mobile users? Sliding window per member? Need to think through the UX.
4. Per-message forwarder rotation vs. per-session — sender picks a different forwarder each post or sticks with one for the session?
5. Cross-platform pubkey encoding — confirm iOS's ML-KEM-768 encapsulation key is the same 1184-byte byte string as Android's (BoringSSL vs. liboqs vs. Bouncy Castle — need to match).

---

## Decision

This is a proposal. Do NOT implement until reviewed + Trail of Bits sign-off on the key schedule. The currently shipped Phase 1–5 design (fully-connected-groups-only) remains the v1.0 protocol; this is v2.0 candidate.

Once approved:
- ship behind a feature flag (`groupTrRelayEnabled = false`)
- run internal soak for 2-4 weeks with the flag off
- enable flag for opt-in beta users
- enable for all new groups after audit clearance + clean beta data
