# Zerion Triple Ratchet (Post-Quantum Ratchet) Technical Design

**Version:** 1.2
**Date:** 2026-05-12
**Status:** ACTIVE — Phase 5 (v1.6) fixes the latent bugs that prevented
Phase 4d from completing PQ epochs end-to-end; ML-KEM-768 rotation now
actually runs on the wire, both directions per epoch
**Author:** Zerion Project

> **v1.6 note:** Phase 4d shipped the wire framing for Mode 3 in January
> 2026 but had three latent bugs (responder chunk-type dispatch missing,
> responder shared secret wiped before use, factory state callbacks
> `null` in production) that prevented any PQ epoch from completing
> end-to-end. v1.6 (May 2026) closes all three plus adds cross-direction
> mixing, self-heal, and a pubkey-comparison tiebreak on simultaneous
> epoch starts. See [PCS_DESIGN.md §v1.6 amendment](PCS_DESIGN.md) for
> the full diff. Identity signing in v1.6 also goes hybrid Ed25519 +
> ML-DSA-65 on every group record — see
> [GROUP_TRIPLE_RATCHET_PQ_DESIGN.md](GROUP_TRIPLE_RATCHET_PQ_DESIGN.md).

---

> **v1.7 amendment — Mode 3-Full is now the default (READ THIS FIRST).**
> This document describes per-epoch Mode 3 (one ML-KEM-768 rotation every
> 25 messages or 24 hours). As of v1.7 (May 2026), that per-epoch path is
> **no longer the default** — it is a **fallback**. The default on new 1:1
> contacts is **Mode 3-Full**: a fresh ML-KEM-768 encapsulation on
> **every outbound frame**, with the per-frame shared secret mixed into
> the body AEAD key. Per-epoch Mode 3 is retained only for legacy/
> mode-disabled paths and the cross-platform interop window. Wherever
> this document says Mode 3 is "ACTIVE" or describes the 25-message /
> 24-hour epoch as the live behaviour, read that as the fallback path —
> the live default is per-message ML-KEM-768. The authoritative
> description of Mode 3-Full lives in
> [PCS_DESIGN.md §v1.7 amendment](PCS_DESIGN.md) and
> [RATCHET_MODES.md](RATCHET_MODES.md). The current database schema
> version is **65** (this document's references to v58 are a stale
> snapshot).

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Problem Statement](#2-problem-statement)
3. [Industry Analysis](#3-industry-analysis)
4. [Technical Requirements](#4-technical-requirements)
5. [Architecture Design](#5-architecture-design)
6. [Bandwidth Analysis for Tor](#6-bandwidth-analysis-for-tor)
7. [Key Schedule and Derivation](#7-key-schedule-and-derivation)
8. [State Machine](#8-state-machine)
9. [Message Header Extensions](#9-message-header-extensions)
10. [Compatibility Strategy](#10-compatibility-strategy)
11. [Implementation Plan](#11-implementation-plan)
12. [Security Analysis](#12-security-analysis)
13. [Open Questions](#13-open-questions)

---

## 1. Executive Summary

### Current State (Phase 4d Complete)

Zerion implements the full Triple Ratchet protocol:
- **Mode 1**: Symmetric Ratchet (per-message forward secrecy) - Briar compatibility
- **Mode 2**: Full Double Ratchet (X25519 DH + Symmetric, 1-RTT PCS recovery) - **FALLBACK**
- **Mode 3**: Triple Ratchet (per-epoch ML-KEM-768 PQ + X25519 DH + Symmetric) - **FALLBACK** (per-epoch rotation, every 25 messages or 24 hours)
- **Mode 3-Full**: per-message ML-KEM-768 encapsulation mixed into the body AEAD key (X25519 DH + Symmetric + per-frame PQ) - **DEFAULT since v1.7**
- **PQ Handshake**: ML-KEM-768 + X25519 hybrid initial key agreement

This exceeds Signal's current security baseline with per-message post-quantum protection for the ongoing ratchet. The remainder of this document specifies the per-epoch Mode 3 mechanism, which is now the fallback path; the per-message Mode 3-Full default is specified in [PCS_DESIGN.md §v1.7 amendment](PCS_DESIGN.md) and [RATCHET_MODES.md](RATCHET_MODES.md).

### Gap Analysis (RESOLVED)

**Previously Missing**: Post-quantum protection for the ongoing ratchet.

**Threat Addressed**: "Harvest now, decrypt later" - With the post-quantum ratchet active, ML-KEM-768 provides quantum-resistant protection for ongoing message keys. Under the Mode 3-Full default this protection refreshes on every frame; under the per-epoch Mode 3 fallback it refreshes every 25 messages or 24 hours.

### Implemented Solution

**Mode 3-Full (default since v1.7)** - per-message ML-KEM-768 encapsulation mixed into the body AEAD key, on every outbound frame to Zerion 1:1 contacts.

**Mode 3 (fallback)** - Sparse Post-Quantum Ratchet (SPQR) using ML-KEM-768, braided into the existing Double Ratchet at per-epoch boundaries (every 25 messages or 24 hours). Retained for legacy/mode-disabled paths and the cross-platform interop window. The mechanism specified in the rest of this document is this per-epoch fallback.

### Design Principles

1. **Hybrid security**: Require breaking BOTH X25519 AND ML-KEM
2. **Per-message PQ by default**: Under the Mode 3-Full default, a fresh ML-KEM-768 encapsulation is mixed into every frame's body key. The **sparse/per-epoch** variant described in the rest of this document (PQ exchanges every 25 messages or 24 hours, not per-message) is now the fallback path for legacy/mode-disabled contacts and the iOS interop window.
3. **Tor-optimized**: Minimize bandwidth overhead for Tor transport
4. **Backward compatible**: Mode 2 contacts continue working
5. **Feature-flagged**: Gradual rollout with explicit capability negotiation

---

## 2. Problem Statement

### 2.1 Current Vulnerability

```
Current Key Flow (Mode 2):

  Initial Handshake (QUANTUM-SAFE)
       │
       │  ML-KEM-768 + X25519 hybrid
       ▼
  Root Key (RK₀) ─────────────────────────────────────────────
       │
       │  (QUANTUM-VULNERABLE)
       ▼
  DH Ratchet Step: X25519(DHs, DHr)
       │
       ▼
  (RK₁, CK₁) = KDF_RK(RK₀, x25519_shared_secret)
       │
       │  Every subsequent ratchet uses X25519 only
       ▼
  Messages encrypted with keys derived from X25519 exchanges
```

**Problem**: After the initial handshake, all key material depends on X25519 exchanges. A future quantum computer could:

1. Record all network traffic (ciphertexts + DH public keys)
2. Use Shor's algorithm to recover X25519 private keys
3. Recompute all chain keys and message keys
4. Decrypt the entire conversation history

### 2.2 Threat Model Update

| Threat | Current Protection | After Triple Ratchet |
|--------|-------------------|---------------------|
| Classical passive eavesdropper | Protected | Protected |
| Classical active attacker | Protected | Protected |
| Quantum passive (stored traffic) | Initial handshake only | Full conversation |
| Quantum active (MITM) | Partial* | Full |
| Temporary device compromise | 1-RTT recovery | 1-RTT recovery |

*Initial handshake is PQ-safe, but ongoing MITM could inject classical-only ratchets.

### 2.3 Why This Matters

- **NSA/Five Eyes**: Known to collect encrypted traffic at scale
- **Quantum timeline**: Estimates range from 10-30 years for cryptographically relevant quantum computers
- **Data longevity**: Some communications remain sensitive for decades
- **Zerion's mission**: Protect activists, journalists, whistleblowers whose adversaries have long-term interests

---

## 3. Industry Analysis

### 3.1 Signal's Approach (SPQR - September 2023)

**Architecture**: Sparse Post-Quantum Ratchet alongside Double Ratchet

**Key Features**:
- ML-KEM-768 encapsulation keys (1,184 bytes)
- ML-KEM-768 ciphertexts (1,088 bytes)
- Chunked transmission using erasure codes (42-byte chunks)
- Keys mixed via `SK = KDF(DH_output || KEM_output)`

**Ratchet Frequency**: Continuous, but chunked across multiple messages

**Bandwidth Overhead**: ~2,272 bytes per PQ ratchet epoch, distributed across ~71 chunks

**Pros**:
- Maximum forward secrecy (PQ ratchet advances as fast as messaging allows)
- Full PCS for both classical and quantum adversaries

**Cons**:
- Complex chunking protocol
- Requires many messages to complete one PQ epoch
- Not optimized for low-bandwidth/high-latency networks

### 3.2 Apple's Approach (PQ3 - February 2024)

**Architecture**: Periodic Kyber rekeying alongside Double Ratchet

**Key Features**:
- ML-KEM (Kyber) for PQ ratchet
- Rekey approximately every 50 messages OR 7 days minimum
- 2KB+ overhead per rekey event

**Ratchet Frequency**: Sparse (every ~50 messages or time-based)

**Bandwidth Overhead**: ~2KB per rekey, amortized over 50 messages = ~40 bytes/message average

**Pros**:
- Simple implementation
- Predictable bandwidth
- Good for typical messaging patterns

**Cons**:
- Up to 50 messages exposed between PQ rekeys
- Time-based fallback (7 days) may be too long for high-security use

### 3.3 Comparison for Zerion

| Aspect | Signal SPQR | Apple PQ3 | Zerion (Proposed) |
|--------|-------------|-----------|-------------------|
| PQ Algorithm | ML-KEM-768 | ML-KEM | ML-KEM-768 |
| Rekey Frequency | Continuous (chunked) | Every ~50 msgs / 7 days | Every N msgs / T time |
| Per-Rekey Overhead | ~2.3KB (chunked) | ~2KB (single) | ~2.3KB (chunked) |
| Tor Suitability | Poor (many small msgs) | Medium | Optimized |
| Complexity | High | Medium | Medium |

---

## 4. Technical Requirements

### 4.1 Security Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| SR-1 | PQ ratchet must use ML-KEM-768 or stronger | MUST |
| SR-2 | Hybrid derivation: `SK = KDF(X25519_out \|\| MLKEM_out)` | MUST |
| SR-3 | PQ ratchet must advance within bounded messages | MUST |
| SR-4 | PQ state compromise must not affect X25519 security | MUST |
| SR-5 | Silent downgrade from Mode 3 to Mode 2 prohibited | MUST |

### 4.2 Bandwidth Requirements (Tor-Specific)

| ID | Requirement | Priority |
|----|-------------|----------|
| BR-1 | Average overhead < 100 bytes/message | SHOULD |
| BR-2 | Maximum single-message overhead < 512 bytes | MUST |
| BR-3 | PQ epoch completion within 20 messages | SHOULD |
| BR-4 | Support chunked transmission over multiple frames | MUST |

### 4.3 Compatibility Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| CR-1 | Mode 3 ↔ Mode 3: Full Triple Ratchet | MUST |
| CR-2 | Mode 3 ↔ Mode 2: Fallback to Double Ratchet | MUST |
| CR-3 | Mode 3 ↔ Mode 1: Fallback to Symmetric Ratchet | MUST |
| CR-4 | Briar contacts: Legacy mode (no Zerion PCS) | MUST |
| CR-5 | Capability negotiation at handshake | MUST |

---

## 5. Architecture Design

### 5.1 Triple Ratchet Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Mode 3: Triple Ratchet                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐          │
│  │  PQ Ratchet  │    │  DH Ratchet  │    │  Symmetric   │          │
│  │  (ML-KEM)    │    │  (X25519)    │    │   Ratchet    │          │
│  │  [SPARSE]    │    │  [PER-MSG]   │    │  [PER-MSG]   │          │
│  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘          │
│         │                   │                   │                   │
│         │ pq_secret         │ dh_secret         │                   │
│         │                   │                   │                   │
│         └─────────┬─────────┘                   │                   │
│                   │                             │                   │
│                   ▼                             │                   │
│         ┌──────────────────┐                    │                   │
│         │  Hybrid Root KDF │                    │                   │
│         │  RK' = KDF(RK,   │                    │                   │
│         │    dh ║ pq)      │                    │                   │
│         └────────┬─────────┘                    │                   │
│                  │                              │                   │
│                  │ new_root_key                 │                   │
│                  │                              │                   │
│                  └──────────────┬───────────────┘                   │
│                                 │                                   │
│                                 ▼                                   │
│                       ┌──────────────────┐                          │
│                       │    Chain Key     │                          │
│                       │   Derivation     │                          │
│                       └────────┬─────────┘                          │
│                                │                                    │
│                                ▼                                    │
│                       ┌──────────────────┐                          │
│                       │   Message Key    │                          │
│                       └────────┬─────────┘                          │
│                                │                                    │
│                                ▼                                    │
│                       ┌──────────────────┐                          │
│                       │ XSalsa20-Poly1305│                          │
│                       └──────────────────┘                          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.2 PQ Ratchet Epochs

The PQ ratchet operates in **epochs**. Each epoch involves:

1. **Key Generation**: One party generates ML-KEM keypair
2. **Encapsulation**: Other party encapsulates to the public key
3. **Secret Derivation**: Both parties derive shared PQ secret
4. **Braiding**: PQ secret mixed into root key derivation

```
Epoch 0 (Initial)
    │
    │  PQ secret from handshake
    ▼
Epoch 1
    │
    │  Alice generates ek, sends chunks
    │  Bob encapsulates, sends ct chunks
    │  Both derive pq_secret_1
    ▼
Epoch 2
    │
    │  Bob generates ek, sends chunks
    │  Alice encapsulates, sends ct chunks
    │  Both derive pq_secret_2
    ▼
    ...
```

### 5.3 Chunking Strategy for Tor

**Problem**: ML-KEM-768 public key = 1,184 bytes, ciphertext = 1,088 bytes. Tor cells = 512 bytes.

**Solution**: Sparse chunking with larger chunks than Signal

| Component | Size | Chunk Size | Chunks | Strategy |
|-----------|------|------------|--------|----------|
| EK seed + hash | 64 bytes | 64 bytes | 1 | Send immediately |
| EK vector | 1,120 bytes | 256 bytes | 5 | Piggyback on messages |
| Ciphertext | 1,088 bytes | 256 bytes | 5 | Piggyback on messages |

**Total chunks per epoch**: 11 (vs Signal's 71)

**Rationale**: Tor already has high latency; larger chunks reduce round-trips while staying within typical Briar message overhead.

### 5.4 Ratchet Trigger Conditions

PQ epoch advances when ANY of:

1. **Message count**: 25 messages since last PQ epoch
2. **Time elapsed**: 24 hours since last PQ epoch
3. **Manual trigger**: User requests immediate rekey

**Configurable parameters**:
```java
static final int PQ_EPOCH_MESSAGE_THRESHOLD = 25;
static final long PQ_EPOCH_TIME_THRESHOLD_MS = 24 * 60 * 60 * 1000; // 24 hours
static final int PQ_CHUNK_SIZE = 256; // bytes
```

---

## 6. Bandwidth Analysis for Tor

### 6.1 Current Overhead (Mode 2)

| Component | Size | Frequency |
|-----------|------|-----------|
| Transport tag | 16 bytes | Per message |
| Stream header | 82 bytes | Per stream |
| PCS header (no DH) | 10 bytes | Per message |
| PCS header (with DH) | 42 bytes | Per DH ratchet |
| Frame header | 20 bytes | Per frame |
| Payload MAC | 16 bytes | Per frame |

**Average per-message overhead**: ~54 bytes (assuming DH ratchet every 2nd message)

### 6.2 Proposed Overhead (Mode 3)

**Per-message (no PQ chunk)**:
| Component | Size |
|-----------|------|
| Mode 2 overhead | 54 bytes |
| PQ epoch counter | 4 bytes |
| PQ flags | 1 byte |
| **Total** | **59 bytes** |

**Per-message (with PQ chunk)**:
| Component | Size |
|-----------|------|
| Mode 2 overhead | 54 bytes |
| PQ epoch counter | 4 bytes |
| PQ flags | 1 byte |
| PQ chunk (256 bytes) | 256 bytes |
| PQ chunk MAC | 16 bytes |
| **Total** | **331 bytes** |

### 6.3 Amortized Analysis

**Scenario**: 25 messages per PQ epoch, 11 chunks total

```
Messages 1-11: Carry PQ chunks (331 bytes each)
Messages 12-25: No PQ chunks (59 bytes each)

Total for 25 messages:
  = (11 × 331) + (14 × 59)
  = 3,641 + 826
  = 4,467 bytes

Average per message: 178.7 bytes
```

**Comparison**:
| Mode | Avg bytes/message | Increase |
|------|-------------------|----------|
| Mode 2 (current) | 54 | baseline |
| Mode 3 (proposed) | 179 | +231% |
| Signal SPQR | ~86* | +59% |
| Apple PQ3 | ~94** | +74% |

*Signal optimized for many small messages
**Apple amortizes over 50 messages

### 6.4 Tor-Specific Considerations

| Factor | Impact | Mitigation |
|--------|--------|------------|
| High latency (1-3s RTT) | Chunking less painful | Larger chunks, fewer round-trips |
| 512-byte cells | Must fit in cells | 256-byte chunks + header < 512 |
| Bandwidth variability | Unpredictable delivery | Erasure coding not critical |
| Onion routing overhead | Already ~3x inflation | PQ overhead relatively smaller |

**Conclusion**: 179 bytes/message average is acceptable for Tor. The ~3x increase is offset by Tor already having high overhead, and the security benefit is significant.

---

## 7. Key Schedule and Derivation

### 7.1 KDF Functions

**KDF_PQ: Post-Quantum Root Key Update**

```
KDF_PQ(rk, pq_secret) → new_rk

  Input:
    rk: 32-byte current root key
    pq_secret: 32-byte ML-KEM shared secret

  Output:
    new_rk: 32-byte updated root key

  Derivation:
    new_rk = BLAKE2b-256(
      label: "org.briarproject.zerion/PCS_PQ_ROOT_UPDATE",
      key: rk,
      input: pq_secret
    )
```

**KDF_HYBRID: Combined DH + PQ Root Key Derivation**

```
KDF_HYBRID(rk, dh_secret, pq_secret) → (new_rk, chain_key)

  Input:
    rk: 32-byte root key
    dh_secret: 32-byte X25519 shared secret (may be null)
    pq_secret: 32-byte ML-KEM shared secret (may be null)

  Output:
    new_rk: 32-byte new root key
    chain_key: 32-byte chain key

  Derivation:
    // At least one of dh_secret or pq_secret must be non-null
    combined = dh_secret ║ pq_secret  // Concatenate non-null values

    new_rk = BLAKE2b-256(
      label: "org.briarproject.zerion/PCS_HYBRID_ROOT",
      key: rk,
      inputs: [combined, 0x01]
    )
    chain_key = BLAKE2b-256(
      label: "org.briarproject.zerion/PCS_HYBRID_CHAIN",
      key: rk,
      inputs: [combined, 0x02]
    )
```

### 7.2 Key Hierarchy with Triple Ratchet

```
Initial Handshake (X25519 + ML-KEM-768)
    │
    ▼
Static Master Key (hybrid)
    │
    ▼
Root Key (RK₀) = deriveKey("PCS_INITIAL_ROOT", staticMasterKey)
    │
    ├─────────────────────────────────────┐
    │                                     │
    ▼                                     ▼
[DH Ratchet]                        [PQ Ratchet]
X25519(DHs, DHr)                    ML-KEM(EK, CT)
    │                                     │
    │ dh_secret                           │ pq_secret
    │                                     │
    └──────────────┬──────────────────────┘
                   │
                   ▼
          KDF_HYBRID(RK, dh, pq)
                   │
                   ├──► new_rk
                   │
                   └──► chain_key
                             │
                             ▼
                   KDF_CK (same as Mode 2)
                             │
                             ▼
                        Message Key
```

### 7.3 When Secrets Are Mixed

| Event | DH Secret | PQ Secret | Operation |
|-------|-----------|-----------|-----------|
| Normal DH ratchet | Yes | No | KDF_HYBRID(rk, dh, null) |
| PQ epoch complete | No | Yes | KDF_PQ(rk, pq) |
| DH + PQ together | Yes | Yes | KDF_HYBRID(rk, dh, pq) |

---

## 8. State Machine

### 8.1 PQ Epoch States

```
                         ┌─────────────────┐
                         │   PQ_INACTIVE   │
                         │  (Mode 2 only)  │
                         └────────┬────────┘
                                  │ Upgrade to Mode 3
                                  ▼
                         ┌─────────────────┐
           ┌────────────►│   PQ_READY      │◄─────────────┐
           │             │ (Ready to start │              │
           │             │   new epoch)    │              │
           │             └────────┬────────┘              │
           │                      │                       │
           │            Initiator │ Responder             │
           │                      │                       │
           │      ┌───────────────┼───────────────┐       │
           │      │               │               │       │
           │      ▼               │               ▼       │
           │ ┌─────────────┐      │      ┌─────────────┐  │
           │ │ PQ_SENDING  │      │      │ PQ_RECEIVING│  │
           │ │  _EK_SEED   │      │      │  _EK_SEED   │  │
           │ └──────┬──────┘      │      └──────┬──────┘  │
           │        │             │             │         │
           │        ▼             │             ▼         │
           │ ┌─────────────┐      │      ┌─────────────┐  │
           │ │ PQ_SENDING  │      │      │ PQ_RECEIVING│  │
           │ │  _EK_VEC    │      │      │  _EK_VEC    │  │
           │ └──────┬──────┘      │      └──────┬──────┘  │
           │        │             │             │         │
           │        │ All chunks  │             │ All     │
           │        │   sent      │             │ chunks  │
           │        │             │             │ received│
           │        ▼             │             ▼         │
           │ ┌─────────────┐      │      ┌─────────────┐  │
           │ │ PQ_AWAITING │      │      │ PQ_SENDING  │  │
           │ │     _CT     │      │      │     _CT     │  │
           │ └──────┬──────┘      │      └──────┬──────┘  │
           │        │             │             │         │
           │        │ CT received │             │ CT sent │
           │        ▼             │             ▼         │
           │ ┌─────────────┐      │      ┌─────────────┐  │
           │ │ PQ_COMPLETE │◄─────┴─────►│ PQ_COMPLETE │  │
           │ └──────┬──────┘             └──────┬──────┘  │
           │        │                           │         │
           │        └───────────┬───────────────┘         │
           │                    │                         │
           │                    │ Derive pq_secret        │
           │                    │ Update root key         │
           │                    │ Increment epoch         │
           │                    │                         │
           └────────────────────┴─────────────────────────┘
```

### 8.2 State Variables

```java
class PqRatchetState {
    // Epoch tracking
    long currentEpoch;                    // Current PQ epoch number
    long epochStartTime;                  // When current epoch started
    int messagesSinceEpoch;               // Message count since last PQ rekey

    // PQ key material
    @Nullable MlKemKeyPair ourKeyPair;    // Our ML-KEM keypair (if initiator)
    @Nullable byte[] ourEkSeed;           // 32-byte seed for EK generation
    @Nullable byte[] ourEkVector;         // 1120-byte encapsulation key vector
    @Nullable byte[] theirEkSeed;         // Their EK seed (if responder)
    @Nullable byte[] theirEkVector;       // Their EK vector (if responder)
    @Nullable byte[] ciphertext;          // ML-KEM ciphertext

    // Chunking state
    PqEpochState state;                   // Current state in epoch
    int chunksSent;                       // Chunks transmitted
    int chunksReceived;                   // Chunks received
    byte[] pendingChunks;                 // Buffer for incoming chunks

    // Role
    boolean isInitiator;                  // Are we initiating this epoch?
}

enum PqEpochState {
    PQ_INACTIVE,        // Not in Mode 3
    PQ_READY,           // Ready to start new epoch
    PQ_SENDING_EK_SEED, // Sending EK seed
    PQ_SENDING_EK_VEC,  // Sending EK vector chunks
    PQ_RECEIVING_EK_SEED,
    PQ_RECEIVING_EK_VEC,
    PQ_AWAITING_CT,     // Waiting for ciphertext
    PQ_SENDING_CT,      // Sending ciphertext chunks
    PQ_COMPLETE         // Epoch complete, derive secret
}
```

### 8.3 Combined Mode 3 Session State

```java
class Mode3SessionState extends PcsSessionState {
    // Inherited from Mode 2
    SecretKey chainKey;
    int messageNumber;
    int previousChainLength;
    SecretKey rootKey;
    DhRatchetState dhState;

    // Mode 3 additions
    PqRatchetState pqState;
    boolean mode3Enabled;

    // Helper methods
    boolean shouldStartPqEpoch();
    void advancePqEpoch(byte[] pqSecret);
    byte[] getNextPqChunkToSend();
    void receivePqChunk(byte[] chunk);
}
```

---

## 9. Message Header Extensions

### 9.1 Mode 3 PCS Header

```
┌─────────────────────────────────────────────────────────────────┐
│                    Mode 3 PCS Message Header                     │
├─────────────────────────────────────────────────────────────────┤
│ Version (1 byte)                                                 │
│   0x01 = PCS header version 1                                    │
├─────────────────────────────────────────────────────────────────┤
│ Flags (1 byte)                                                   │
│   Bit 0 (FLAG_PCS_ENABLED): PCS enabled (always 1)              │
│   Bit 1 (FLAG_DH_RATCHET): DH public key present                │
│   Bit 2 (FLAG_PQ_ENABLED): Mode 3 PQ ratchet active             │
│   Bit 3 (FLAG_PQ_CHUNK): PQ chunk data present                  │
│   Bit 4-7: Reserved (must be 0)                                 │
├─────────────────────────────────────────────────────────────────┤
│ Message Number (4 bytes, big-endian)                            │
├─────────────────────────────────────────────────────────────────┤
│ Previous Chain Length (4 bytes, big-endian)                     │
├─────────────────────────────────────────────────────────────────┤
│ DH Ratchet Public Key (32 bytes, optional)                      │
│   Present only if FLAG_DH_RATCHET is set                        │
├─────────────────────────────────────────────────────────────────┤
│ PQ Epoch (4 bytes, optional)                                    │
│   Present only if FLAG_PQ_ENABLED is set                        │
├─────────────────────────────────────────────────────────────────┤
│ PQ Chunk Header (4 bytes, optional)                             │
│   Present only if FLAG_PQ_CHUNK is set                          │
│   Byte 0: Chunk type (0=EK_SEED, 1=EK_VEC, 2=CT)               │
│   Byte 1: Chunk index                                           │
│   Bytes 2-3: Chunk length                                       │
├─────────────────────────────────────────────────────────────────┤
│ PQ Chunk Data (variable, up to 256 bytes)                       │
│   Present only if FLAG_PQ_CHUNK is set                          │
└─────────────────────────────────────────────────────────────────┘

Size ranges:
  Mode 2 header (no PQ): 10-42 bytes
  Mode 3 header (no chunk): 14-46 bytes
  Mode 3 header (with chunk): 18-302 bytes
```

### 9.2 PQ Chunk Types

| Type | Value | Size | Description |
|------|-------|------|-------------|
| EK_SEED | 0 | 64 bytes | ML-KEM seed + hash (single chunk) |
| EK_VEC | 1 | 5 × 256 = 1,280 bytes | Encapsulation key vector (5 chunks) |
| CT | 2 | 5 × 256 = 1,280 bytes | Ciphertext (5 chunks, includes padding) |

---

## 10. Compatibility Strategy

### 10.1 Mode Negotiation

Capability flags in handshake:

```
PCS_CAPABILITY_FLAGS:
  Bit 0: Mode 1 supported (symmetric ratchet)
  Bit 1: Mode 2 supported (double ratchet)
  Bit 2: Mode 3 supported (triple ratchet)
  Bit 3-7: Reserved
```

**Negotiation matrix**:

| Alice | Bob | Result |
|-------|-----|--------|
| Mode 3 | Mode 3 | Mode 3 (Triple Ratchet) |
| Mode 3 | Mode 2 | Mode 2 (Double Ratchet) |
| Mode 3 | Mode 1 | Mode 1 (Symmetric) |
| Mode 2 | Mode 2 | Mode 2 |
| Mode 2 | Mode 1 | Mode 1 |
| Any | Legacy | Legacy |

### 10.2 Runtime Upgrade

Contacts can upgrade from Mode 2 to Mode 3 mid-conversation:

1. Both parties must support Mode 3 (capabilities exchanged at handshake)
2. Initiator sends first PQ epoch data
3. Responder acknowledges by participating in epoch
4. Both transition to Mode 3

**No downgrade allowed** once Mode 3 is established (same as Mode 2 rule).

### 10.3 Briar Compatibility

Briar contacts use legacy Briar protocol:
- No PCS at all
- Continue with time-period based key rotation
- Mode 1/2/3 negotiation only with Zerion contacts

---

## 11. Implementation Plan

### 11.1 File Changes

**New Files**:
```
bramble-api/src/main/java/org/briarproject/bramble/api/crypto/pcs/
  PqRatchetState.java        # PQ ratchet state container
  PqEpochState.java          # Epoch state enum
  MlKemKeyPair.java          # ML-KEM key pair wrapper

bramble-core/src/main/java/org/briarproject/bramble/crypto/pcs/
  PqRatchetImpl.java         # PQ ratchet implementation
  MlKemProvider.java         # ML-KEM operations (wraps Bouncy Castle)
  ChunkingManager.java       # Chunk assembly/disassembly
```

**Modified Files**:
```
bramble-api/src/main/java/org/briarproject/bramble/api/crypto/pcs/
  PcsSessionState.java       # Add Mode 3 state
  PcsConstants.java          # Add Mode 3 constants

bramble-core/src/main/java/org/briarproject/bramble/crypto/pcs/
  PcsRatchetImpl.java        # Integrate PQ ratchet
  PcsStateManager.java       # Persist Mode 3 state

bramble-core/src/main/java/org/briarproject/bramble/crypto/
  PcsStreamEncrypterImpl.java  # Add PQ chunk transmission
  PcsStreamDecrypterImpl.java  # Add PQ chunk reception

bramble-core/src/main/java/org/briarproject/bramble/db/
  JdbcDatabase.java          # Schema v58 with PQ tables (schema has since advanced; current is v65)
  Migration57_58.java        # PQ state migration
```

> **Schema note:** v58 was the schema version when the per-epoch PQ
> tables landed. The database schema has advanced since; the current
> version is **65**. Treat the v58 references in this section as a
> historical snapshot of the PQ-table migration, not the live schema
> version.

### 11.2 Database Schema Additions

```sql
-- PQ Ratchet State table
CREATE TABLE pqRatchetState (
    contactId _HASH NOT NULL,
    currentEpoch BIGINT NOT NULL DEFAULT 0,
    epochStartTime BIGINT NOT NULL,
    messagesSinceEpoch INT NOT NULL DEFAULT 0,
    state INT NOT NULL DEFAULT 0,       -- PqEpochState ordinal
    isInitiator INT NOT NULL DEFAULT 0,
    ourEkSeed _BINARY,
    ourEkVector _BINARY,
    ourDecapsKey _SECRET,               -- ML-KEM decapsulation key
    theirEkSeed _BINARY,
    theirEkVector _BINARY,
    ciphertext _BINARY,
    chunksSent INT NOT NULL DEFAULT 0,
    chunksReceived INT NOT NULL DEFAULT 0,
    pendingChunks _BINARY,
    PRIMARY KEY (contactId),
    FOREIGN KEY (contactId) REFERENCES contacts(contactId) ON DELETE CASCADE
);
```

### 11.3 Phased Rollout

**Phase 4a: Core Implementation** (Feature-flagged OFF)
- Implement ML-KEM wrapper
- Implement PQ ratchet state machine
- Implement chunking manager
- Add database schema
- Unit tests

**Phase 4b: Integration** (Feature-flagged OFF)
- Integrate with PCS stream encrypter/decrypter
- Add capability negotiation
- Integration tests
- Performance benchmarks on Tor

**Phase 4c: Testing** (Feature-flagged ON for opt-in)
- Beta testing with flag
- Bandwidth measurements
- Bug fixes

**Phase 4d: General Release** (Feature-flagged ON by default)
- Enable for all new conversations
- Document migration path

---

## 12. Security Analysis

### 12.1 Security Properties After Mode 3

| Property | Mode 2 | Mode 3 | Notes |
|----------|--------|--------|-------|
| Forward Secrecy | Yes | Yes | Symmetric ratchet |
| PCS (Classical) | 1-RTT | 1-RTT | DH ratchet |
| PCS (Quantum) | No | Yes* | PQ ratchet |
| Quantum-safe Handshake | Yes | Yes | ML-KEM + X25519 |
| Quantum-safe Ratchet | No | Yes | ML-KEM braid |
| Replay Protection | Yes | Yes | Message counters |

*PQ-PCS recovery bound = epoch length (25 messages or 24 hours)

### 12.2 Remaining Weaknesses

| Weakness | Severity | Status |
|----------|----------|--------|
| PQ epoch window (up to 25 msgs) | Low | Acceptable trade-off |
| No PCS for groups | Medium | Out of scope |
| Partial KCI protection | Medium | Unchanged from Mode 2 |
| Side-channel attacks | Out of scope | Implementation detail |
| Long-term device compromise | Out of scope | Physical security |

### 12.3 Comparison After Mode 3

| Feature | Zerion Mode 3 | Signal | Apple PQ3 |
|---------|---------------|--------|-----------|
| Per-message FS | Yes | Yes | Yes |
| DH Ratchet | Yes | Yes | Yes |
| PQ Handshake | Yes | Yes | Yes |
| PQ Ratchet | Yes | Yes | Yes |
| PQ Epoch Size | 25 msgs | Continuous | 50 msgs |
| Tor Optimized | Yes | No | No |

---

## 13. Open Questions

### 13.1 Design Decisions Needed

1. **Epoch size**: 25 messages proposed. Should this be configurable per-contact?

2. **Initiator selection**: Who initiates each epoch? Proposal: Alternate based on epoch number parity.

3. **Chunk loss handling**: What if a chunk is lost? Proposal: Retransmit on next message opportunity.

4. **ML-KEM variant**: ML-KEM-768 proposed. Should we support ML-KEM-1024 for higher security?

5. **Acknowledgment**: Should explicit ACKs be required, or is implicit progress sufficient?

### 13.2 Performance Questions

1. What is actual Tor overhead with Mode 3 in real-world usage?

2. Does chunking interact poorly with Tor's congestion control?

3. What is the battery impact of ML-KEM operations on mobile?

### 13.3 Future Considerations

1. **Katana**: Alternative ratcheting KEM with ~40% size savings. Monitor standardization progress.

2. **SLH-DSA**: Post-quantum signatures for authentication. Currently out of scope.

3. **Group PCS**: Requires separate design (MLS-style or custom).

---

## Appendix A: ML-KEM-768 Parameters

| Parameter | Value |
|-----------|-------|
| Public key size | 1,184 bytes |
| Secret key size | 2,400 bytes |
| Ciphertext size | 1,088 bytes |
| Shared secret size | 32 bytes |
| Security level | NIST Level 3 (~AES-192) |

---

## Appendix B: References

1. [Signal ML-KEM Braid Specification](https://signal.org/docs/specifications/mlkembraid/)
2. [Signal PQXDH Specification](https://signal.org/docs/specifications/pqxdh/)
3. [Signal SPQR Blog Post](https://signal.org/blog/spqr/)
4. [Apple PQ3 Security Analysis](https://security.apple.com/blog/imessage-pq3/)
5. [NIST FIPS 203 (ML-KEM)](https://csrc.nist.gov/pubs/fips/203/final)
6. [PQShield Signal Analysis](https://pqshield.com/diving-into-signals-new-pq-protocol/)

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
