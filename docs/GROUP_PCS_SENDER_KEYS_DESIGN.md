# Group PCS Design: Sender Keys Architecture

**Status:** SUPERSEDED — replaced by Group Triple Ratchet (see [GROUP_TRIPLE_RATCHET_PQ_DESIGN.md](GROUP_TRIPLE_RATCHET_PQ_DESIGN.md)). The Sender Keys code in `briar-core/.../privategroup/senderkeys/` remains in the tree for backward compatibility with existing legacy groups; new groups default to the Triple Ratchet wire (msgType 32–38). This document is preserved for historical reference of the prior design.
**Version:** 1.0 (historical)
**Date:** 2026-02-04 (superseded 2026-05-11)
**Author:** Professor

**Related Documents:**
- [PCS_DESIGN.md](PCS_DESIGN.md) - Pairwise PCS specification (Mode 1/2/3)
- [TRIPLE_RATCHET_DESIGN.md](TRIPLE_RATCHET_DESIGN.md) - Post-quantum ratchet specification

---

## 1. Overview

This document specifies the cryptographic design for secure group messaging in Zerion using a **Sender Keys** architecture with **post-quantum epoch refresh**.

### 1.1 Goals

- Forward secrecy for group messages
- Post-compromise security via key rotation
- Membership change security (rekey on join/leave)
- Backward compatibility with Briar (graceful fallback)
- Bounded Tor overhead

### 1.2 Non-Goals

- Perfect forward secrecy per-message (too expensive for groups)
- Real-time membership revocation (async model)

---

## 2. Architecture

### 2.1 Core Concept: Per-Sender Keys

Each group member maintains their own **SenderKey** for outbound messages:

```
Member A → Group:  Encrypt with SenderKey_A
Member B → Group:  Encrypt with SenderKey_B
Member C → Group:  Encrypt with SenderKey_C
```

Receivers maintain a cache of all members' SenderKeys to decrypt incoming messages.

### 2.2 Key Distribution via Pairwise Channels

SenderKeys are **never** sent inside group messages. They are distributed using existing pairwise PCS channels (Mode 2/3):

```
On group creation:
  Creator generates SenderKey_Creator
  Creator sends SenderKey_Creator to each invitee via pairwise channel

On member join:
  New member generates SenderKey_New
  New member receives all existing SenderKeys via pairwise from each member
  New member distributes SenderKey_New to all existing members via pairwise
```

### 2.3 Post-Quantum Integration

PQ key exchange happens at the **pairwise level**, not per-group-message:

- Pairwise channels use Mode 3 (ML-KEM + X25519)
- SenderKey distribution inherits PQ protection from pairwise transport
- Epoch refresh (PQ re-keying) is amortized across groups

---

## 3. Cryptographic Specification

### 3.1 SenderKey Structure

```
SenderKey {
    chainKey:       byte[32]    // Current chain key
    messageIndex:   uint32      // Messages sent with this key
    epoch:          uint32      // Key generation epoch
    createdAt:      uint64      // Timestamp of creation
    authorId:       AuthorId    // Owner of this SenderKey
}
```

### 3.2 Key Derivation

**Initial SenderKey Generation:**
```
chainKey_0 = HKDF-SHA256(
    ikm = random(32),
    salt = groupId || authorId,
    info = "org.zerion/SENDER_KEY_INIT",
    len = 32
)
```

**Per-Message Key Derivation (Symmetric Ratchet):**
```
messageKey_n = HKDF-SHA256(
    ikm = chainKey_n,
    salt = empty,
    info = "org.zerion/MESSAGE_KEY" || messageIndex,
    len = 32
)

chainKey_{n+1} = HKDF-SHA256(
    ikm = chainKey_n,
    salt = empty,
    info = "org.zerion/CHAIN_KEY",
    len = 32
)
```

**Epoch Rotation (Post-Quantum Refresh):**
```
chainKey_new = HKDF-SHA256(
    ikm = chainKey_old || pqSharedSecret,
    salt = groupId || epoch,
    info = "org.zerion/SENDER_KEY_EPOCH",
    len = 32
)
```

Where `pqSharedSecret` is derived from ML-KEM encapsulation via pairwise channel.

### 3.3 Message Encryption

**Encrypt (Sender):**
```
1. Derive messageKey from SenderKey
2. nonce = epoch || messageIndex || random(4)
3. ciphertext = AES-256-GCM(messageKey, nonce, plaintext, aad)
4. signature = Ed25519-Sign(senderPrivateKey, ciphertext || nonce || groupId)
5. output = { ciphertext, nonce, epoch, messageIndex, signature, senderId }
```

**Decrypt (Receiver):**
```
1. Lookup SenderKey for senderId
2. Verify signature over ciphertext || nonce || groupId
3. Derive messageKey from SenderKey at (epoch, messageIndex)
4. plaintext = AES-256-GCM-Decrypt(messageKey, nonce, ciphertext, aad)
5. Advance local copy of sender's chain if needed
```

**AAD (Additional Authenticated Data):**
```
aad = groupId || senderId || epoch || messageIndex || timestamp
```

### 3.4 Signature Placement

**CRITICAL: Sign ciphertext, never plaintext.**

```
Correct:   Sign(ciphertext || metadata)
Wrong:     Sign(plaintext) then Encrypt
```

---

## 4. Protocol Messages

### 4.1 SenderKey Distribution Message

Sent via pairwise channel when:
- Group created (creator → all members)
- Member joins (new ↔ existing, bidirectional)
- Epoch refresh (sender → all)

```
SenderKeyDistribution {
    type:           uint8 = 0x01
    groupId:        byte[32]
    senderKey:      SenderKey
    signature:      byte[64]    // Signs groupId || senderKey
}
```

### 4.2 Encrypted Group Message

```
EncryptedGroupMessage {
    type:           uint8 = 0x02
    groupId:        byte[32]
    senderId:       AuthorId
    epoch:          uint32
    messageIndex:   uint32
    nonce:          byte[12]
    ciphertext:     byte[]
    signature:      byte[64]    // Signs ciphertext || nonce || groupId
    timestamp:      uint64
}
```

### 4.3 Rekey Request (Membership Change)

```
RekeyRequest {
    type:           uint8 = 0x03
    groupId:        byte[32]
    reason:         uint8       // JOIN=1, LEAVE=2, KICK=3, EPOCH=4
    affectedMember: AuthorId    // Who joined/left
    newSenderKey:   SenderKey   // Requester's new key
    signature:      byte[64]
}
```

---

## 5. State Machine

### 5.1 SenderKey States

```
UNINITIALIZED → ACTIVE → ROTATING → ACTIVE
                  ↓
               REVOKED
```

### 5.2 Group Crypto States

```
enum GroupCryptoMode {
    NONE,           // No encryption (Briar compat)
    SENDER_KEYS,    // Full Sender Keys mode
    DEGRADED        // Partial (some members lack capability)
}
```

### 5.3 State Transitions

**On Group Creation:**
```
1. Creator generates SenderKey
2. State = ACTIVE
3. Distribute to invited members via pairwise
```

**On Member Join:**
```
1. All existing members rotate SenderKey (membership change security)
2. New member generates SenderKey
3. Bidirectional distribution via pairwise
4. Mark old keys as REVOKED after grace period
```

**On Member Leave/Kick:**
```
1. All remaining members rotate SenderKey
2. Distribute new keys via pairwise
3. Removed member's cached keys become stale (cannot decrypt future)
```

**On Epoch Refresh:**
```
Triggered by: messageCount > EPOCH_THRESHOLD (default: 100)
              OR time > EPOCH_INTERVAL (default: 24h)

1. Generate new chainKey with PQ material
2. Increment epoch
3. Reset messageIndex = 0
4. Distribute via pairwise
```

---

## 6. Database Schema

### 6.1 New Tables

```sql
-- Per-sender key state (one per member per group)
CREATE TABLE groupSenderKeys (
    groupId         BLOB NOT NULL,
    authorId        BLOB NOT NULL,
    chainKey        BLOB NOT NULL,      -- Encrypted at rest
    epoch           INTEGER NOT NULL,
    messageIndex    INTEGER NOT NULL,
    createdAt       INTEGER NOT NULL,
    isLocal         INTEGER NOT NULL,   -- 1 if this is our key
    PRIMARY KEY (groupId, authorId)
);

-- Key history for out-of-order decryption
CREATE TABLE groupKeyHistory (
    groupId         BLOB NOT NULL,
    authorId        BLOB NOT NULL,
    epoch           INTEGER NOT NULL,
    messageIndex    INTEGER NOT NULL,
    messageKey      BLOB NOT NULL,      -- Cached derived key
    expiresAt       INTEGER NOT NULL,
    PRIMARY KEY (groupId, authorId, epoch, messageIndex)
);

-- Group crypto metadata
CREATE TABLE groupCryptoState (
    groupId         BLOB PRIMARY KEY,
    cryptoMode      INTEGER NOT NULL,   -- GroupCryptoMode enum
    lastRekeyTime   INTEGER NOT NULL,
    rekeyReason     INTEGER,
    minCapability   INTEGER NOT NULL    -- Minimum member capability
);
```

### 6.2 Migration Strategy

Existing groups default to `cryptoMode = NONE` (Briar-compatible).

Upgrade path:
1. Check all members support SENDER_KEYS
2. Creator initiates upgrade via special message
3. All members acknowledge
4. Generate and distribute initial SenderKeys
5. Set `cryptoMode = SENDER_KEYS`

---

## 7. Capability Negotiation

### 7.1 Capability Advertisement

```
GroupCapability {
    SENDER_KEYS_V1 = 0x01
    PQ_EPOCH_V1    = 0x02
}
```

Advertised in:
- Contact handshake (pairwise)
- Group join message

### 7.2 Mode Selection

```
groupCryptoMode = min(capabilities of all members)

If any member lacks SENDER_KEYS_V1:
    groupCryptoMode = NONE (plaintext, Briar compat)

If all members have SENDER_KEYS_V1 but not PQ_EPOCH_V1:
    groupCryptoMode = SENDER_KEYS (classical only)

If all members have both:
    groupCryptoMode = SENDER_KEYS with PQ epochs
```

### 7.3 Downgrade Protection

- Capability is authenticated (signed in handshake)
- Mode can only upgrade, never downgrade for existing members
- New member with lower capability triggers warning, not auto-downgrade

---

## 8. Security Analysis

### 8.1 Threat Model

**Attacker Capabilities:**
- Passive network observer (Tor mitigates)
- Compromised group member (key isolation)
- Compromised past key (forward secrecy)
- Quantum adversary (future-proof via PQ epochs)

### 8.2 Compromise Scenarios

| Scenario | Impact | Mitigation |
|----------|--------|------------|
| Single member key compromised | Only that member's sent messages exposed | Key isolation, rekey on detection |
| Member removed but has old keys | Cannot decrypt messages after rekey | Membership change triggers rekey |
| Replay attack | Detected via messageIndex | Reject duplicate (epoch, index) |
| Reorder attack | AAD includes timestamp | Timestamp validation window |

### 8.3 Forward Secrecy Guarantees

- **Per-epoch:** Full forward secrecy between epochs
- **Per-message:** Weak forward secrecy (chain ratchet)
- **Membership change:** Strong forward secrecy (full rekey)

### 8.4 Post-Compromise Security

- Epoch rotation limits exposure window
- Membership change forces full rekey
- PQ refresh ensures quantum resistance

---

## 9. Performance Analysis

### 9.1 Tor Overhead

| Operation | Messages via Tor |
|-----------|------------------|
| Send group message | 1 (broadcast) |
| Distribute SenderKey | N-1 (pairwise to each member) |
| Epoch refresh | N-1 (pairwise) |
| Member join | 2*(N-1) (bidirectional) |

**Amortization:** SenderKey distribution is infrequent compared to messages.

### 9.2 Storage

- Per member per group: ~100 bytes (SenderKey + metadata)
- Key history: ~64 bytes per cached messageKey
- History limit: 1000 entries per sender (configurable)

### 9.3 Bandwidth

- Group message overhead: +84 bytes (signature + epoch + index + nonce)
- SenderKey distribution: ~200 bytes per pairwise message

---

## 10. Implementation Phases

### Phase G1: Storage + State Machine - COMPLETED
- ✅ Implement database schema (`Migration61_62.java` - contactCapabilities table)
- ✅ Implement SenderKey state machine (`SenderKeyState.java`)
- ✅ Implement GroupCryptoStateManager (`SenderKeyManager.java`, `GroupCryptoState.java`)

### Phase G2: Key Distribution - COMPLETED
- ✅ Implement SenderKeyDistribution message (`SenderKeyDistributionFactory.java`)
- ✅ Integrate with pairwise transport (`SenderKeyDistributor.java`)
- ✅ Implement join/leave rekey flow

### Phase G3: Message Encryption - COMPLETED
- ✅ Implement encrypt (sender side) (`GroupMessageCryptoImpl.java`)
- ✅ Implement decrypt (receiver side)
- ✅ Implement signature verify (`GroupMessageValidator.java` - SENDER_KEYS_POST)

### Phase G4: Epoch Rotation + PQ - COMPLETED
- ✅ Implement time/count based rotation (`EpochRotationManagerImpl.java`)
- ✅ Integrate PQ key material from pairwise
- ✅ Implement graceful epoch transition

### Phase G5: Capability + Migration - COMPLETED
- ✅ Implement capability negotiation (`CapabilityManagerImpl.java`)
- ✅ Implement upgrade flow for existing groups
- ✅ Implement Briar compatibility fallback (NONE mode)

---

## 11. Test Plan

### 11.1 Unit Tests (SenderKeyCryptoTest.java)

- [x] SenderKey generation correctness
- [x] Chain ratchet produces unique keys
- [x] Epoch rotation resets chain correctly
- [x] Message encryption/decryption roundtrip
- [x] Signature verification
- [x] Out-of-order message handling
- [x] Skipped message key caching

### 11.2 Integration Tests (SenderKeysIntegrationTest.java)

- [x] Member joins after messages sent
- [x] Member removal triggers rekey
- [x] Epoch rollover boundaries (message count threshold)
- [x] Time-based epoch rotation
- [x] End-to-end encrypt/distribute/decrypt
- [x] Out-of-order message delivery
- [x] Multiple members with independent keys
- [x] Sender key state transitions
- [x] Post-quantum shared secret integration
- [x] Replay attack prevention

### 11.3 Adversarial Tests (EpochRotationTest.java + Integration)

- [x] Replay rejected (via message index tracking)
- [x] Reorder handled (key caching)
- [x] Invalid signature rejected (GroupMessageValidator)
- [x] Wrong epoch rejected (epoch comparison)
- [x] Removed member cannot decrypt new messages
- [x] Downgrade attack prevented (capability negotiation)

### 11.4 Runtime Tests

- [ ] Offline member catch-up (manual verification)
- [ ] Tor disconnect/reconnect (manual verification)
- [ ] Large attachment in group (manual verification)
- [ ] Rapid message burst (manual verification)
- [ ] Device sleep/wake (manual verification)

---

## 12. Open Questions

1. **How is a SenderKey revoked?**
   - Answer: On membership change, all members generate new SenderKey. Old keys are marked REVOKED and deleted after grace period (e.g., 7 days for offline catch-up).

2. **How does a late-joining member obtain keys safely?**
   - Answer: Existing members send their current SenderKey via pairwise channel. New member cannot decrypt messages from before they joined (by design).

3. **How is a malicious sender contained?**
   - Answer: Key isolation ensures malicious sender only compromises their own outbound stream. Admin can kick and trigger rekey.

4. **What happens if PCS is unavailable temporarily?**
   - Answer: Queue SenderKey distribution messages. Continue using current keys until pairwise channel is restored. Epoch refresh waits for all members to acknowledge.

---

## 13. Appendix: Comparison with Current Implementation

| Aspect | Current (Triple DH) | New (Sender Keys) |
|--------|---------------------|-------------------|
| Key model | Single shared GroupKey | Per-sender SenderKey |
| Compromise impact | All messages exposed | Only sender's messages |
| Membership change | No rekey | Mandatory rekey |
| PQ integration | Per-group DH | Amortized via pairwise |
| Scalability | O(N²) DH for N members | O(N) key distribution |
| Briar compat | Breaks | Graceful fallback |

---

## 14. Implementation Status

- [x] Core implementation completed (2026-02-04)
- [x] Unit tests passing
- [x] Integration tests passing
- [x] Legacy GroupCrypto removed
- [ ] APK validation pending
- [ ] Production deployment pending

**Implementation Notes:**
- Legacy shared-key GroupCrypto fully removed
- SENDER_KEYS_POST message type added (type=3)
- Capability negotiation active via CapabilityManager
- Epoch rotation thresholds: 100 messages OR 24 hours
- PQ material integrated via pairwise channel amortization

---

*Document reflects implemented state as of 2026-02-04.*
