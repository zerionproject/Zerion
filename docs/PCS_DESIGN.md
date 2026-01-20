# Zerion Post-Compromise Security (PCS) Technical Design

**Version:** 1.0-DRAFT
**Date:** 2026-01-13
**Status:** PENDING REVIEW
**Author:** Claude Code

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Current Infrastructure Analysis](#2-current-infrastructure-analysis)
3. [PCS Requirements](#3-pcs-requirements)
4. [Design Overview](#4-design-overview)
5. [State Machine](#5-state-machine)
6. [Key Schedule and Derivation](#6-key-schedule-and-derivation)
7. [Message Header Extensions](#7-message-header-extensions)
8. [Capability Negotiation](#8-capability-negotiation)
9. [Backward Compatibility](#9-backward-compatibility)
10. [State Persistence and Recovery](#10-state-persistence-and-recovery)
11. [Failure Modes](#11-failure-modes)
12. [Security Analysis](#12-security-analysis)
13. [Implementation Roadmap](#13-implementation-roadmap)
14. [Test Vectors](#14-test-vectors)

---

## 1. Executive Summary

This document specifies the Post-Compromise Security (PCS) implementation for Zerion, extending the existing hybrid post-quantum cryptographic infrastructure with a Double Ratchet-style key evolution mechanism.

### Goals

- **Per-message forward secrecy**: Compromise of current keys does not expose past messages
- **Post-compromise recovery**: After key compromise, security is restored within bounded messages
- **Backward compatibility**: New clients communicate with legacy clients without breaking
- **Quantum resistance**: Maintain existing ML-KEM-768 + X25519 hybrid security

### Non-Goals

- Full Triple Ratchet (ML-KEM Braid): Deferred due to bandwidth constraints on Tor
- Group messaging PCS: Separate design required

---

## 2. Current Infrastructure Analysis

### 2.1 Existing Cryptographic Stack

| Component | Current Implementation | Location |
|-----------|----------------------|----------|
| Key Agreement | X25519 + ML-KEM-768 hybrid | `HybridKeyAgreement.java` |
| Symmetric Cipher | XSalsa20-Poly1305 | `XSalsa20Poly1305AuthenticatedCipher.java` |
| KDF | BLAKE2b (label-prefixed) | `CryptoComponentImpl.java` |
| Key Rotation | Time-period based (~42 hours) | `TransportKeyManagerImpl.java` |
| Session State | Per-contact key sets | `TransportKeys.java` |

### 2.2 Current Key Hierarchy

```
Initial Handshake (X25519 + ML-KEM-768)
    │
    ▼
Static Master Key = BLAKE2b(x25519_secret ║ mlkem_secret ║ public_keys)
    │
    ▼
Root Key = deriveKey("CONTACT_ROOT_KEY", staticMasterKey)
    │
    ├──► Tag Key = deriveKey("ALICE_TAG_KEY", rootKey, transportId)
    │
    └──► Header Key = deriveKey("ALICE_HEADER_KEY", rootKey, transportId)
             │
             ▼
         Frame Key (per stream, from stream header)
             │
             ▼
         Message Encryption (XSalsa20-Poly1305)
```

### 2.3 Current Weaknesses

1. **No symmetric ratchet**: Same root key used across all messages in a time period
2. **No DH ratchet**: No per-message ephemeral key exchange
3. **Time-based only**: Key rotation is time-period bound, not message-count bound
4. **Long session vulnerability**: Compromise exposes all messages within ~42 hour window

---

## 3. PCS Requirements

### 3.1 Security Requirements

| Requirement | Description | Priority |
|-------------|-------------|----------|
| SR-1 | Message keys must be unique per message | MUST |
| SR-2 | Compromise of message key N must not expose key N-1 | MUST |
| SR-3 | After compromise, recovery must occur within K messages | MUST |
| SR-4 | Skipped message keys must be bounded | MUST |
| SR-5 | Downgrade to non-PCS must be explicit, never silent | MUST |
| SR-6 | Quantum-safe key derivation must be maintained | MUST |

### 3.2 Functional Requirements

| Requirement | Description | Priority |
|-------------|-------------|----------|
| FR-1 | Out-of-order message decryption | MUST |
| FR-2 | Message loss tolerance (configurable) | MUST |
| FR-3 | Conversation resumption after offline period | MUST |
| FR-4 | Attachment encryption uses same ratchet | MUST |
| FR-5 | State persistence across app restart | MUST |

### 3.3 Compatibility Requirements

| Requirement | Description | Priority |
|-------------|-------------|----------|
| CR-1 | New ↔ New: PCS enabled | MUST |
| CR-2 | New ↔ Old: Legacy crypto, no breakage | MUST |
| CR-3 | Protocol version negotiation | MUST |
| CR-4 | No silent downgrade after PCS established | MUST |

---

## 4. Design Overview

### 4.1 Architecture

Zerion PCS implements a **Symmetric-Key Ratchet** with optional **DH Ratchet** enhancement:

```
┌─────────────────────────────────────────────────────────────────┐
│                      PCS Layer                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐      │
│  │   DH Ratchet │───►│ Root Chain   │───►│ Send Chain   │──┐   │
│  │  (optional)  │    │    (RK)      │    │    (CKs)     │  │   │
│  └──────────────┘    └──────────────┘    └──────────────┘  │   │
│         │                   │                    │          │   │
│         │                   ▼                    ▼          │   │
│         │            ┌──────────────┐    ┌──────────────┐  │   │
│         │            │ Recv Chain   │    │ Message Key  │  │   │
│         │            │    (CKr)     │    │    (MK)      │──┼──►│
│         │            └──────────────┘    └──────────────┘  │   │
│         │                   │                              │   │
│         │                   ▼                              │   │
│         │            ┌──────────────┐                      │   │
│         └───────────►│ Skipped Keys │                      │   │
│                      │  (bounded)   │                      │   │
│                      └──────────────┘                      │   │
│                                                             │   │
└─────────────────────────────────────────────────────────────┴───┘
                              │
                              ▼
                    ┌──────────────────┐
                    │ XSalsa20-Poly1305│
                    │   Encryption     │
                    └──────────────────┘
```

### 4.2 Ratchet Modes

**Mode 1: Symmetric-Only Ratchet (Phase 1)**
- Per-message key derivation from chain key
- Forward secrecy within session
- Recovery on next DH exchange (time-period rotation)

**Mode 2: Full Double Ratchet (Phase 2)**
- Symmetric ratchet + per-message DH ratchet
- Maximum PCS: recovery within 1 round-trip
- Higher bandwidth (32-byte DH public key per message)

### 4.3 Protocol Version

```
PCS_PROTOCOL_VERSION = 6

Version History:
  4 = Legacy (pre-hybrid)
  5 = Hybrid PQ handshake (current)
  6 = Hybrid PQ + PCS ratchet (this design)
```

---

## 5. State Machine

### 5.1 PCS Session States

```
                    ┌───────────────┐
                    │   INACTIVE    │
                    │ (no session)  │
                    └───────┬───────┘
                            │ Handshake complete
                            ▼
                    ┌───────────────┐
                    │  INITIALIZED  │
                    │ (root key set)│
                    └───────┬───────┘
                            │ First message sent/received
                            ▼
                    ┌───────────────┐
              ┌────►│    ACTIVE     │◄────┐
              │     │ (ratcheting)  │     │
              │     └───────┬───────┘     │
              │             │             │
        DH Ratchet    Symmetric     Message
          step         ratchet      received
              │             │             │
              │             ▼             │
              │     ┌───────────────┐     │
              └─────│   RATCHETED   │─────┘
                    │ (key advanced)│
                    └───────────────┘
                            │
                            │ Contact deleted / session reset
                            ▼
                    ┌───────────────┐
                    │   DESTROYED   │
                    │ (keys erased) │
                    └───────────────┘
```

### 5.2 State Variables

```java
class PcsSessionState {
    // DH Ratchet Keys (Mode 2 only)
    KeyPair DHs;           // Our current DH ratchet key pair
    PublicKey DHr;         // Their current DH ratchet public key

    // Root Chain
    SecretKey RK;          // 32-byte root key

    // Sending Chain
    SecretKey CKs;         // 32-byte sending chain key
    int Ns;                // Send message counter (0, 1, 2, ...)

    // Receiving Chain
    SecretKey CKr;         // 32-byte receiving chain key
    int Nr;                // Receive message counter

    // Previous Chain
    int PN;                // Previous chain length (for skipped key calc)

    // Skipped Keys (bounded)
    Map<SkippedKeyId, SecretKey> MKSKIPPED;  // Max 1000 entries

    // Protocol State
    int protocolVersion;   // 6 for PCS
    boolean pcsNegotiated; // True if peer supports PCS
    long epoch;            // Current epoch (for hybrid ratchet)
}

record SkippedKeyId(byte[] dhPublicKey, int messageNumber) {}
```

### 5.3 State Transitions

| Current State | Event | Next State | Action |
|---------------|-------|------------|--------|
| INACTIVE | Handshake complete | INITIALIZED | Set RK from handshake |
| INITIALIZED | Send message | ACTIVE | Initialize CKs, derive MK |
| INITIALIZED | Receive message | ACTIVE | Initialize CKr, derive MK |
| ACTIVE | Send message | ACTIVE | Advance CKs, derive MK |
| ACTIVE | Receive message | ACTIVE | Advance CKr, derive MK |
| ACTIVE | Receive new DHr | RATCHETED | DH ratchet step |
| RATCHETED | Send message | ACTIVE | Use new CKs |
| ANY | Contact deleted | DESTROYED | Erase all keys |
| ANY | Error (unrecoverable) | DESTROYED | Erase all keys |

---

## 6. Key Schedule and Derivation

### 6.1 KDF Functions

All KDF operations use BLAKE2b with explicit domain separation labels.

**KDF_RK: Root Key Derivation**
```
KDF_RK(rk, dh_out) → (new_rk, chain_key)

  Input:
    rk: 32-byte root key
    dh_out: 32-byte DH output (or KEM shared secret)

  Output:
    new_rk: 32-byte new root key
    chain_key: 32-byte chain key

  Derivation:
    temp = BLAKE2b-512(
      label: "org.briarproject.zerion/PCS_ROOT_KDF",
      key: rk,
      input: dh_out
    )
    new_rk = temp[0:32]
    chain_key = temp[32:64]
```

**KDF_CK: Chain Key Derivation**
```
KDF_CK(ck) → (new_ck, message_key)

  Input:
    ck: 32-byte chain key

  Output:
    new_ck: 32-byte new chain key
    message_key: 32-byte message key

  Derivation:
    new_ck = BLAKE2b-256(
      label: "org.briarproject.zerion/PCS_CHAIN_KEY",
      key: ck,
      input: 0x01
    )
    message_key = BLAKE2b-256(
      label: "org.briarproject.zerion/PCS_MESSAGE_KEY",
      key: ck,
      input: 0x02
    )
```

### 6.2 Key Hierarchy with PCS

```
Initial Handshake (X25519 + ML-KEM-768)
    │
    ▼
Static Master Key (existing)
    │
    ▼
Root Key (RK₀) = deriveKey("PCS_INITIAL_ROOT", staticMasterKey)
    │
    ├───────────────────────────────────────────┐
    │                                           │
    ▼                                           ▼
[DH Ratchet Step]                        [Symmetric Ratchet]
    │                                           │
(RK₁, CKs₁) = KDF_RK(RK₀, DH(DHs, DHr))       │
    │                                           │
    ▼                                           ▼
Send Chain                               Recv Chain
CKs₁ ─► CKs₂ ─► CKs₃ ...                CKr₁ ─► CKr₂ ─► CKr₃ ...
 │       │       │                        │       │       │
 ▼       ▼       ▼                        ▼       ▼       ▼
MK₁     MK₂     MK₃                      MK₁     MK₂     MK₃
 │       │       │                        │       │       │
 ▼       ▼       ▼                        ▼       ▼       ▼
Encrypt Encrypt Encrypt                  Decrypt Decrypt Decrypt
Msg 1   Msg 2   Msg 3                    Msg 1   Msg 2   Msg 3
```

### 6.3 Label Separation

All KDF labels MUST be unique and include the full domain:

| Operation | Label |
|-----------|-------|
| Initial root | `org.briarproject.zerion/PCS_INITIAL_ROOT` |
| Root KDF | `org.briarproject.zerion/PCS_ROOT_KDF` |
| Chain key advance | `org.briarproject.zerion/PCS_CHAIN_KEY` |
| Message key derive | `org.briarproject.zerion/PCS_MESSAGE_KEY` |
| Header encryption | `org.briarproject.zerion/PCS_HEADER_KEY` |
| Skipped key index | `org.briarproject.zerion/PCS_SKIPPED_INDEX` |

---

## 7. Message Header Extensions

### 7.1 PCS Message Header

```
┌─────────────────────────────────────────────────────────────┐
│                    PCS Message Header                        │
├─────────────────────────────────────────────────────────────┤
│ Version (1 byte)                                             │
│   0x06 = PCS protocol version                                │
├─────────────────────────────────────────────────────────────┤
│ Flags (1 byte)                                               │
│   Bit 0: DH ratchet present (1 = yes)                        │
│   Bit 1: PCS capability (1 = supported)                      │
│   Bit 2-7: Reserved (must be 0)                              │
├─────────────────────────────────────────────────────────────┤
│ Message Number (4 bytes, big-endian)                         │
│   Current chain message counter (Ns or Nr)                   │
├─────────────────────────────────────────────────────────────┤
│ Previous Chain Length (4 bytes, big-endian)                  │
│   Message count in previous sending chain (PN)               │
├─────────────────────────────────────────────────────────────┤
│ DH Ratchet Public Key (32 bytes, optional)                   │
│   Present only if Flags bit 0 is set                         │
│   X25519 public key for DH ratchet step                      │
├─────────────────────────────────────────────────────────────┤
│ Epoch (8 bytes, optional, Phase 2)                           │
│   For future ML-KEM Braid integration                        │
└─────────────────────────────────────────────────────────────┘

Minimum header size: 10 bytes (no DH key)
Maximum header size: 50 bytes (with DH key and epoch)
```

### 7.2 Header Encryption

The PCS header is encrypted separately from the message body:

```
encrypted_header = XSalsa20-Poly1305(
  key: header_key,
  nonce: message_number ║ zeros(16),
  plaintext: pcs_header,
  aad: transport_tag
)
```

### 7.3 Wire Format

```
┌──────────────────────────────────────────────────────────┐
│ Transport Tag (16 bytes)                                  │
├──────────────────────────────────────────────────────────┤
│ Stream Header (82 bytes, existing)                        │
├──────────────────────────────────────────────────────────┤
│ PCS Header (encrypted, 10-50 bytes + 16 MAC)              │
├──────────────────────────────────────────────────────────┤
│ Frame Header (20 bytes, existing)                         │
├──────────────────────────────────────────────────────────┤
│ Payload (encrypted with MK, up to 992 bytes)              │
├──────────────────────────────────────────────────────────┤
│ Payload MAC (16 bytes)                                    │
└──────────────────────────────────────────────────────────┘
```

---

## 8. Capability Negotiation

### 8.1 Negotiation Protocol

PCS capability is negotiated during the initial handshake and persisted:

```
┌─────────┐                              ┌─────────┐
│  Alice  │                              │   Bob   │
└────┬────┘                              └────┬────┘
     │                                        │
     │  Handshake Message 1                   │
     │  + PCS_CAPABILITY flag                 │
     │───────────────────────────────────────►│
     │                                        │
     │  Handshake Message 2                   │
     │  + PCS_CAPABILITY flag (if supported)  │
     │◄───────────────────────────────────────│
     │                                        │
     │  [Both see PCS flags = PCS enabled]    │
     │  [One missing = Legacy mode]           │
     │                                        │
```

### 8.2 Capability Storage

```java
interface ContactCapabilities {
    boolean supportsPcs();
    int protocolVersion();
    long capabilityTimestamp();
}
```

Capabilities are stored per-contact in the database and checked before each message.

### 8.3 Negotiation Rules

| Alice PCS | Bob PCS | Result |
|-----------|---------|--------|
| Yes | Yes | PCS enabled, version 6 |
| Yes | No | Legacy mode, version 5 |
| No | Yes | Legacy mode, version 5 |
| No | No | Legacy mode, version 5 |

---

## 9. Backward Compatibility

### 9.1 Version Detection

The protocol version is encoded in the first byte of the PCS header:

```java
int detectProtocolVersion(byte[] header) {
    if (header.length < 1) return LEGACY_VERSION;
    int version = header[0] & 0xFF;
    if (version == 0x06) return PCS_VERSION;
    if (version == 0x05) return HYBRID_VERSION;
    return LEGACY_VERSION;
}
```

### 9.2 Message Processing Flow

```
Receive Message
    │
    ▼
┌──────────────────┐
│ Parse Transport  │
│     Header       │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐     No      ┌──────────────────┐
│ PCS Negotiated?  │────────────►│ Process Legacy   │
└────────┬─────────┘             └──────────────────┘
         │ Yes
         ▼
┌──────────────────┐
│ Parse PCS Header │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐     Yes     ┌──────────────────┐
│ New DH Key?      │────────────►│ DH Ratchet Step  │
└────────┬─────────┘             └──────────────────┘
         │ No                            │
         ▼                               ▼
┌──────────────────┐             ┌──────────────────┐
│ Symmetric        │◄────────────│ Update Chains    │
│ Ratchet Step     │             └──────────────────┘
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ Derive MK,       │
│ Decrypt Message  │
└──────────────────┘
```

### 9.3 Downgrade Protection

**CRITICAL**: Once PCS is established for a conversation, it MUST NOT silently downgrade.

```java
void validateNoDowngrade(ContactId contact, int incomingVersion) {
    ContactCapabilities caps = getCapabilities(contact);

    if (caps.supportsPcs() && incomingVersion < PCS_VERSION) {
        // FAIL CLOSED - do not process
        throw new SecurityException(
            "PCS downgrade attempt detected. " +
            "Expected version >= 6, got " + incomingVersion
        );
    }
}
```

Downgrade is only permitted if:
1. User explicitly resets the conversation
2. Contact is re-added after deletion
3. Both parties agree via explicit protocol message (future)

---

## 10. State Persistence and Recovery

### 10.1 Database Schema

```sql
CREATE TABLE pcs_session_state (
    contact_id BLOB NOT NULL PRIMARY KEY,
    protocol_version INTEGER NOT NULL,
    pcs_negotiated INTEGER NOT NULL,

    -- Root chain
    root_key BLOB NOT NULL,

    -- Sending chain
    send_chain_key BLOB,
    send_counter INTEGER NOT NULL DEFAULT 0,

    -- Receiving chain
    recv_chain_key BLOB,
    recv_counter INTEGER NOT NULL DEFAULT 0,

    -- Previous chain
    prev_chain_length INTEGER NOT NULL DEFAULT 0,

    -- DH ratchet (Mode 2)
    dh_private_key BLOB,
    dh_public_key BLOB,
    dh_remote_public_key BLOB,

    -- Metadata
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,

    FOREIGN KEY (contact_id) REFERENCES contacts(id) ON DELETE CASCADE
);

CREATE TABLE pcs_skipped_keys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    contact_id BLOB NOT NULL,
    dh_public_key BLOB NOT NULL,
    message_number INTEGER NOT NULL,
    message_key BLOB NOT NULL,
    created_at INTEGER NOT NULL,

    UNIQUE(contact_id, dh_public_key, message_number),
    FOREIGN KEY (contact_id) REFERENCES contacts(id) ON DELETE CASCADE
);

CREATE INDEX idx_skipped_keys_contact ON pcs_skipped_keys(contact_id);
CREATE INDEX idx_skipped_keys_created ON pcs_skipped_keys(created_at);
```

### 10.2 State Synchronization

State MUST be persisted atomically after each operation:

```java
@Transaction
void processMessageAndUpdateState(Message msg, PcsSessionState state) {
    // 1. Decrypt message with current state
    byte[] plaintext = decrypt(msg, state);

    // 2. Advance ratchet
    state.advanceReceiveChain();

    // 3. Persist new state (atomic)
    db.updatePcsState(state);

    // 4. Store message
    db.insertMessage(plaintext);
}
```

### 10.3 Recovery Scenarios

| Scenario | Recovery Action |
|----------|----------------|
| App crash during send | Resend with same message number |
| App crash during receive | Message will be re-delivered, use skipped key |
| Database corruption | Initiate new handshake (lose PCS state) |
| Contact re-added | Fresh PCS negotiation |

### 10.4 Skipped Key Limits

```java
static final int MAX_SKIP = 1000;      // Max skipped keys per contact
static final int MAX_SKIP_AGE_MS =
    7 * 24 * 60 * 60 * 1000;           // 7 days

void pruneSkippedKeys(ContactId contact) {
    long cutoff = System.currentTimeMillis() - MAX_SKIP_AGE_MS;
    db.deleteSkippedKeysBefore(contact, cutoff);

    int count = db.countSkippedKeys(contact);
    if (count > MAX_SKIP) {
        db.deleteOldestSkippedKeys(contact, count - MAX_SKIP);
    }
}
```

---

## 11. Failure Modes

### 11.1 Failure Classification

| Failure | Severity | Action |
|---------|----------|--------|
| KDF failure | CRITICAL | Abort, erase state |
| Decryption failure (MAC) | HIGH | Reject message, keep state |
| Unknown protocol version | MEDIUM | Reject message, keep state |
| Skipped key limit exceeded | MEDIUM | Reject message, warn user |
| Downgrade attempt | CRITICAL | Reject message, alert user |
| State persistence failure | HIGH | Retry, then abort |

### 11.2 Fail-Closed Principle

All cryptographic operations MUST fail closed:

```java
SecretKey deriveMessageKey(SecretKey chainKey) {
    try {
        return kdfCk(chainKey);
    } catch (Exception e) {
        // FAIL CLOSED: erase state, do not continue
        eraseState();
        throw new CryptoException("KDF failed, state erased", e);
    }
}
```

### 11.3 Error Propagation

```
┌─────────────────┐
│  Crypto Error   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐     Recoverable?     ┌─────────────────┐
│ Classify Error  │─────────────────────►│ Log, Continue   │
└────────┬────────┘         Yes          └─────────────────┘
         │ No
         ▼
┌─────────────────┐
│ Erase Sensitive │
│     State       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Notify User     │
│ (silent fail    │
│  prohibited)    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Require New     │
│   Handshake     │
└─────────────────┘
```

---

## 12. Security Analysis

### 12.1 Security Properties

| Property | Achieved | Mechanism |
|----------|----------|-----------|
| Forward Secrecy | Yes | Symmetric ratchet + key deletion |
| Post-Compromise Security | Yes | DH ratchet (Mode 2) / Time rotation (Mode 1) |
| Key Compromise Impersonation | Partial | Requires separate authentication |
| Replay Protection | Yes | Message counters + skipped key tracking |
| Quantum Resistance | Yes | Initial handshake uses ML-KEM-768 |

### 12.2 Threat Model

**In Scope:**
- Passive network eavesdropper (including quantum adversary for stored traffic)
- Temporary device compromise (state exfiltration)
- Message reordering and loss

**Out of Scope:**
- Long-term device compromise (persistent malware)
- Side-channel attacks on implementation
- Denial of service attacks

### 12.3 Recovery Bounds

| Mode | Messages to Recovery | Notes |
|------|---------------------|-------|
| Mode 1 (Symmetric) | Unbounded within session | Recovery on time-period rotation |
| Mode 2 (Full DR) | 1 round-trip | Each DH exchange heals |

### 12.4 Comparison with Industry

| Feature | Zerion PCS | Signal DR | Apple PQ3 |
|---------|-----------|-----------|-----------|
| Per-message keys | Yes | Yes | Yes |
| DH Ratchet | Mode 2 | Yes | Yes |
| PQ Handshake | ML-KEM-768 | ML-KEM-768 | ML-KEM |
| PQ Ratchet | No (Phase 2) | Triple Ratchet | Periodic |
| Auth | Deniable | Deniable | Non-deniable |

---

## 13. Implementation Roadmap

### Phase 1: Symmetric Ratchet (Core PCS)

**Scope:**
- Implement symmetric ratchet (KDF_CK)
- Add PCS headers to messages
- Capability negotiation
- Backward compatibility layer
- State persistence
- Skipped key management

**Files to Create:**
- `PcsSessionState.java` - State container
- `PcsRatchet.java` - Ratchet operations
- `PcsHeaderCodec.java` - Header encoding/decoding
- `PcsCapabilityNegotiator.java` - Negotiation logic

**Files to Modify:**
- `TransportKeyManagerImpl.java` - Integrate PCS state
- `StreamEncrypterImpl.java` - Use per-message keys
- `StreamDecrypterImpl.java` - Use per-message keys
- `TransportCryptoImpl.java` - Add PCS KDF functions
- Database schema

### Phase 2: DH Ratchet (Full Double Ratchet)

**Scope:**
- Add DH ratchet step
- Per-message ephemeral keys
- Full PCS with 1-RTT recovery

**Additional Files:**
- `DhRatchet.java` - DH ratchet operations

### Phase 3: Post-Quantum Ratchet (Future)

**Scope:**
- ML-KEM Braid integration
- Triple Ratchet style PCS
- Full quantum-safe forward secrecy and PCS

**Deferred due to:**
- Bandwidth constraints on Tor (1KB+ per message)
- Complexity of chunking protocol
- Battery/performance impact

---

## 14. Test Vectors

### 14.1 KDF_CK Test Vector

```
Input:
  chain_key = 0x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20

Expected Output:
  new_chain_key = BLAKE2b-256(
    label: "org.briarproject.zerion/PCS_CHAIN_KEY",
    key: chain_key,
    input: 0x01
  )

  message_key = BLAKE2b-256(
    label: "org.briarproject.zerion/PCS_MESSAGE_KEY",
    key: chain_key,
    input: 0x02
  )
```

### 14.2 Ratchet Sequence Test

```
Initial State:
  RK = 0x... (from handshake)
  CKs = null
  CKr = null
  Ns = 0
  Nr = 0

After Alice sends message 1:
  CKs = KDF_RK(RK, DH_CONSTANT)[1]  // Mode 1: use constant
  MK_1 = KDF_CK(CKs)[1]
  CKs = KDF_CK(CKs)[0]
  Ns = 1

After Alice sends message 2:
  MK_2 = KDF_CK(CKs)[1]
  CKs = KDF_CK(CKs)[0]
  Ns = 2

Verify: MK_1 ≠ MK_2
Verify: Cannot derive MK_1 from current CKs
```

### 14.3 Out-of-Order Test

```
Alice sends: M1, M2, M3
Bob receives: M1, M3 (M2 lost)

Bob's state after M1:
  Nr = 1
  MKSKIPPED = {}

Bob's state after M3:
  Nr = 3
  MKSKIPPED = {(DHr, 2): MK_2}

Bob receives M2 (delayed):
  Lookup (DHr, 2) in MKSKIPPED
  Decrypt with MK_2
  Delete (DHr, 2) from MKSKIPPED
```

### 14.4 Backward Compatibility Test

```
Scenario: New client (v6) → Old client (v5)

New client check:
  peer_capabilities = getCapabilities(contact)
  assert peer_capabilities.protocolVersion == 5
  assert peer_capabilities.supportsPcs == false

Result:
  Use legacy encryption (version 5)
  Do NOT send PCS headers
  Do NOT use ratchet
```

---

## Appendix A: Glossary

| Term | Definition |
|------|------------|
| CK | Chain Key - intermediate key for deriving message keys |
| DH | Diffie-Hellman key exchange |
| DR | Double Ratchet algorithm |
| KDF | Key Derivation Function |
| MK | Message Key - symmetric key for one message |
| PCS | Post-Compromise Security |
| RK | Root Key - top of key hierarchy |
| SCKA | Sparse Continuous Key Agreement |

---

## Appendix B: References

1. [Signal Double Ratchet Specification](https://signal.org/docs/specifications/doubleratchet/)
2. [ML-KEM Braid Specification](https://signal.org/docs/specifications/mlkembraid/)
3. [Signal SPQR Blog Post](https://signal.org/blog/spqr/)
4. [PQShield Analysis of Signal PQ Protocol](https://pqshield.com/diving-into-signals-new-pq-protocol/)
5. [Apple PQ3 Security Analysis](https://security.apple.com/assets/files/Security_analysis_of_the_iMessage_PQ3_protocol_Stebila.pdf)

---

## Approval

This design document requires review and approval before implementation begins.

| Role | Name | Date | Signature |
|------|------|------|-----------|
| Security Lead | | | |
| Technical Lead | | | |
| Project Owner | | | |

---

**END OF DOCUMENT**
