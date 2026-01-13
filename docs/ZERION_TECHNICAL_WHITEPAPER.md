# ZERION SECURE MESSAGING APP - COMPREHENSIVE TECHNICAL WHITEPAPER

## Executive Summary

Zerion (formerly a Briar fork) is an end-to-end encrypted, peer-to-peer secure messaging application for Android that provides anonymity through Tor integration and includes a built-in encrypted vault for secure file storage. The application emphasizes privacy, security, and metadata protection through its architecture built on the Bramble protocol framework.

**Post-Quantum Security**: Zerion implements **full hybrid post-quantum cryptography** using NIST-standardized algorithms (ML-KEM-768 + X25519 for key exchange, ML-DSA-65 + Ed25519 for signatures), providing defense-in-depth protection against both current and future quantum computing threats.

---

## TABLE OF CONTENTS

1. [Onboarding & Account Setup](#1-onboarding--account-setup)
2. [Messaging Architecture](#2-messaging-architecture)
3. [Tor Integration](#3-tor-integration)
4. [End-to-End Encryption](#4-end-to-end-encryption)
5. [Post-Compromise Security](#5-post-compromise-security)
6. [Vault Feature - Secure File Storage](#6-vault-feature---secure-file-storage)
7. [Contact Discovery & Addition](#7-contact-discovery--addition)
8. [P2P Voice Calling](#8-p2p-voice-calling)
9. [Data Storage Security](#9-data-storage-security)
10. [Feature Highlights](#10-feature-highlights)
11. [Security Properties](#11-security-properties)
12. [Technical Specifications](#12-technical-specifications)
13. [Architecture Diagrams](#13-architecture-diagrams)
14. [File Path Reference](#14-file-path-reference)
15. [Conclusion](#15-conclusion)

---

## 1. ONBOARDING & ACCOUNT SETUP

### 1.1 Application Launch Flow

**Primary File**: `SplashScreenActivity.java`

**Initial Launch Sequence**:
```
[App Launch] → [Splash Screen] → [Account Check]
                                       ↓
                        ┌──────────────┴──────────────┐
                        ↓                             ↓
                [Account Exists]              [No Account]
                        ↓                             ↓
                [Main Activity]           [Setup Flow (3 steps)]
```

**Implementation Details**:
- **Account Verification**: Checks `AccountManager.hasDatabaseKey()`
- **Animation**: Matrix-style decoding effect on logo
- **Routing**: Smart navigation based on account state

### 1.2 Three-Step Account Creation

**Setup Flow**:

1. **Step 1: Display Name**
   - User enters their chosen name
   - No PII required (no phone number, email, etc.)
   - Name stored as part of Author identity

2. **Step 2: Password Creation**
   - Minimum 8 characters (simplified from original strict requirements)
   - Real-time strength estimation
   - Password used to derive database encryption key via Scrypt KDF
   - Key derivation parameters calibrated to device performance

3. **Step 3: Battery Optimization**
   - Requests exemption from Doze mode
   - Critical for reliable message delivery
   - Optional but recommended

**Security Architecture**:
```
User Password
     ↓
[Scrypt KDF] ← Random Salt (256-bit)
     ↓
Database Encryption Key (256-bit)
     ↓
[Encrypted SQLite Database]
```

### 1.3 Identity Generation

**Components Created**:
- **Ed25519 Key Pair**: For signing and identity
- **Author ID**: Derived from public key
- **Database Key**: Derived from password
- **Local Storage**: Encrypted database initialized

**Key Properties**:
- No central registration
- No user tracking
- Self-sovereign identity
- Cryptographically verifiable

---

## 2. MESSAGING ARCHITECTURE

### 2.1 One-on-One Private Messaging

**Message Structure**:
```java
Message {
    type: PRIVATE_MESSAGE
    text: String (max 10,000 bytes)
    attachments: List<AttachmentHeader>
    autoDeleteTimer: Long (optional)
    timestamp: Long
    groupId: Unique conversation ID
}
```

**Message Lifecycle**:
```
[Compose] → [Encrypt] → [Store Locally] → [Queue for Sync]
                                              ↓
                                          [Tor Transport]
                                              ↓
                          [Remote Device] ← [Sync Protocol]
                                              ↓
                                          [Decrypt] → [Display]
```

**Features**:
- **End-to-End Encrypted**: Never decrypted in transit
- **Offline Queue**: Messages stored until delivery
- **Delivery Receipts**: Ack system for confirmation
- **Read Receipts**: Optional read status
- **Auto-Delete**: Timer-based disappearing messages

### 2.2 Private Group Messaging

**Group Architecture**:

```
Group Structure:
├── Creator (Author ID)
├── Members (List<Author>)
│   ├── Visibility Status (VISIBLE/INVISIBLE)
│   └── Invitation State
├── Messages (Threaded)
│   ├── Parent Message ID (for threading)
│   └── Previous Message ID (for ordering)
└── Metadata (Encrypted)
```

**Message Types**:
- **JOIN**: Member joins group
- **POST**: Standard group message

**Member Visibility Modes**:
- **VISIBLE**: Member known to all
- **INVISIBLE**: Hidden membership (privacy feature)
- **REVEALED_BY_US**: We revealed to contact
- **REVEALED_BY_CONTACT**: Contact revealed to us

**Threading Model**:
- Messages can reference parent for context
- Chronological ordering via previous message IDs
- Timestamp validation for consistency

### 2.3 Transport Layer Encryption

**Key Hierarchy**:

```
Static Key Pair (Long-term)
     +
Ephemeral Key Pair (Per-session)
     ↓
[ECDH Key Agreement]
     ↓
Static Master Key
     ↓
Root Key
     ↓
┌────────────┴────────────┐
↓                         ↓
Incoming Keys        Outgoing Keys
     ↓                    ↓
[Rotation Keys per Time Period]
     ↓
┌────┴────┬────────┬────────┐
↓         ↓        ↓        ↓
Tag Key  Header   Frame    Stream
         Key      Key      Cipher
```

**Time-Based Key Rotation**:
- Keys rotate every time period
- Previous, current, and next period keys maintained
- Automatic derivation using BLAKE2b-256 HKDF

**Message Authentication**:
- BLAKE2b-keyed MAC on all messages
- Protocol version + stream number bound to MAC
- Prevents replay and tampering

### 2.4 Synchronization Protocol

**Sync State Machine**:

```
[Local Changes] → [Generate Offer/Batch]
                        ↓
                  [Send via Transport]
                        ↓
                  [Remote Processes]
                        ↓
                  [Send Request/Ack]
                        ↓
                  [Complete Sync]
```

**Sync Components**:
- **Offers**: Notify peer of available messages
- **Requests**: Ask for specific messages
- **Acks**: Confirm receipt
- **Batches**: Send actual message content

**Database Integration**:
- Transaction-based updates
- Event broadcasting on changes
- Automatic cleanup of delivered messages
- Message dependency tracking

---

## 3. TOR INTEGRATION

### 3.1 Tor Network Architecture

**Components**:

```
┌─────────────────────────┐
│   Zerion Application    │
└───────────┬─────────────┘
            ↓
┌───────────────────────────┐
│    TorPlugin Manager      │
│  - State Management       │
│  - Circuit Control        │
│  - Bridge Selection       │
└───────────┬───────────────┘
            ↓
┌───────────────────────────┐
│   Embedded Tor Process    │
│  - Hidden Service         │
│  - SOCKS5 Proxy           │
│  - Circuit Building       │
└───────────┬───────────────┘
            ↓
    [Tor Network]
```

### 3.2 Hidden Service (.onion v3)

**v3 Onion Address Generation**:

```java
Public Key (32 bytes)
     ↓
[SHA3-256](".onion checksum" + pubkey + version)
     ↓
Checksum (first 2 bytes)
     ↓
[Concatenate: pubkey + checksum + version]
     ↓
[Base32 Encode]
     ↓
56-character .onion address
```

**Properties**:
- **Length**: 56 characters (base32)
- **Security**: Derived from Ed25519 public key
- **Persistence**: Private key stored for reuse
- **Verification**: Built-in checksum

**Publishing Flow**:
```
[Generate Key Pair] → [Start Hidden Service]
                             ↓
                   [Tor Descriptor Published]
                             ↓
                   [Reachable via .onion]
```

### 3.3 Connection Types

**Incoming Connections**:
- Listen on hidden service port
- Accept connections from Tor network
- Validate and route to sync protocol

**Outgoing Connections**:
- Connect via SOCKS5 proxy
- Resolve .onion addresses
- Establish circuits through Tor

### 3.4 Network Adaptation

**Censorship Circumvention**:

```
[Country Detection]
        ↓
[Circumvention Provider]
        ↓
┌───────┴──────────┐
↓                  ↓
Direct Tor    Bridge Mode
              ┌────┴────┬────────┐
              ↓         ↓        ↓
          Snowflake   Meek    Obfs4
```

**Bridge Selection Logic**:
- **IPv6-only**: Meek or Snowflake
- **High-censorship regions**: Recommended bridges
- **Default**: Direct Tor connection

**Battery Optimization**:
- Tor disabled on battery save mode (configurable)
- Connection padding only on WiFi + charging
- Adaptive circuit management

### 3.5 Tor States

**State Transitions**:
```
STARTING → CONNECTING → CONNECTED (ACTIVE)
    ↓           ↓            ↓
STOPPING ← DISABLED ← INACTIVE (offline)
```

**State Descriptions**:
- **STARTING_STOPPING**: Tor bootstrap/shutdown
- **DISABLED**: User disabled or battery save mode
- **INACTIVE**: Enabled but device offline
- **ENABLING**: Connecting to Tor network
- **ACTIVE**: Fully operational

---

## 4. END-TO-END ENCRYPTION

### 4.1 Cryptographic Primitives

**Core Algorithms (Post-Quantum Hardened)**:

| Function | Algorithm | Details | Post-Quantum Security |
|----------|-----------|---------|----------------------|
| Key Agreement | **Hybrid ML-KEM-768 + X25519** | NIST FIPS 203 | ✅ **NIST Level 3** |
| Signatures | **Hybrid ML-DSA-65 + Ed25519** | NIST FIPS 204 | ✅ **NIST Level 3** |
| Symmetric Encryption | XSalsa20-Poly1305 | AEAD | ✅ 128-bit PQ |
| Hashing | BLAKE2b-256/384 | Fast, secure | ✅ 128/192-bit PQ |
| MAC | BLAKE2b (keyed) | Authenticated | ✅ 128-bit PQ |
| KDF | HKDF-BLAKE2b | Key derivation | ✅ 128-bit PQ |
| Password KDF | Argon2id | Memory-hard | ✅ Quantum-resistant |

**Security Properties**:
- **Full hybrid post-quantum cryptography** (Phase 2 complete)
- Defense-in-depth: Both classical AND PQ algorithms must be broken
- Constant-time implementations
- Side-channel resistance
- Hardware acceleration where available

### 4.2 BQP Key Agreement Protocol (Post-Quantum Hybrid)

**Bramble QR Protocol (BQP)** - Now with hybrid post-quantum support:

**Phase 1: Payload Generation**
```
[Generate Hybrid Ephemeral Key Pair]
    ├── X25519 component (32 bytes)
    └── ML-KEM-768 component (1,184 bytes)
        ↓
[Create Commitment] = BLAKE2b(hybrid_pubkey || nonce)
        ↓
[Encode Payload] = {commitment_hash, transports}
        ↓
[Display as QR/Link] (53 chars, commitment-based)
```

> **Note**: Hybrid public keys (1,216 bytes) are too large for QR codes.
> Version 1 uses commitment-based staged exchange - QR contains hash,
> full key exchanged over established connection.

**Phase 2: Payload Exchange**
```
Alice Commitment ←→ Bob Commitment
     ↓                ↓
[Connect via Transport]
     ↓
[Exchange Full Hybrid Public Keys]
     ↓
[Verify Commitments Match Keys]
     ↓
[Determine Roles] (lexicographic comparison)
```

**Phase 3: Hybrid Key Agreement**
```
┌─────────────────────────────────────────────────────────────┐
│              HYBRID KEY AGREEMENT (ML-KEM + X25519)          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  X25519 Key Agreement:                                      │
│    Ephemeral-Ephemeral ECDH                                 │
│         +                                                   │
│    Static-Ephemeral ECDH (Alice)                            │
│         +                                                   │
│    Ephemeral-Static ECDH (Bob)                              │
│         ↓                                                   │
│    X25519 Shared Secret (32 bytes)                          │
│                                                             │
│  ML-KEM-768 Key Encapsulation:                              │
│    [Encapsulate with recipient's ML-KEM public key]         │
│         ↓                                                   │
│    ML-KEM Shared Secret (32 bytes)                          │
│         +                                                   │
│    Ciphertext (1,088 bytes)                                 │
│                                                             │
│  Hybrid Combination:                                        │
│    [X25519 Secret || ML-KEM Secret]                         │
│         ↓                                                   │
│    [HKDF-BLAKE2b with domain separation]                    │
│         ↓                                                   │
│    Hybrid Shared Master Key (256-bit)                       │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Security Properties**:
- **Perfect Forward Secrecy**: Ephemeral keys
- **Mutual Authentication**: Both parties contribute
- **Deniability**: No long-term signatures
- **Resistance to MITM**: Commitment scheme
- **Post-Quantum Security**: ML-KEM-768 (NIST Level 3)
- **Defense-in-Depth**: Both X25519 AND ML-KEM must be broken

### 4.3 Message Encryption

**Encryption Flow**:

```
Plaintext Message
     ↓
[Serialize to BDF]
     ↓
[XSalsa20-Poly1305 AEAD]
     ├── Key: Derived from shared secret
     ├── Nonce: 192-bit random
     └── AAD: Message metadata
     ↓
[Ciphertext + 128-bit Auth Tag]
     ↓
[Store/Transmit]
```

**Nonce Management**:
- 192-bit nonces (extended)
- Random generation
- Collision resistance

**Associated Data**:
- Message type
- Timestamp
- Group ID
- Prevents context manipulation

### 4.4 Key Rotation

**Rotation Schedule**:
```
Time Period N-1 (Previous)
Time Period N (Current)
Time Period N+1 (Next)
```

**Derived Keys per Period**:
- Tag Key (message authentication)
- Header Key (header encryption)
- Frame Keys (content encryption)

**Benefits**:
- Post-compromise security
- Automatic rotation
- No manual intervention

---

## 5. POST-COMPROMISE SECURITY

### 5.1 Overview

Post-Compromise Security (PCS) ensures that even if an attacker temporarily compromises a device and extracts cryptographic keys, the security of future messages is automatically restored after a bounded number of messages. This is a critical property for high-risk users operating in adversarial environments.

**Current Status**: Design complete (see `docs/PCS_DESIGN.md`), implementation pending.

### 5.2 Design Goals

| Goal | Description |
|------|-------------|
| Per-message keys | Each message encrypted with unique key |
| Forward secrecy | Compromise of key N does not expose N-1 |
| Recovery bound | Security restored within K messages |
| Backward compatible | Works with legacy clients |
| Quantum safe | Maintains ML-KEM-768 + X25519 hybrid security |

### 5.3 Architecture

Zerion PCS implements a Double Ratchet algorithm with two operational modes:

**Mode 1: Symmetric-Only Ratchet**
```
Root Key (RK)
    │
    ▼
Chain Key (CK) ──► CK₁ ──► CK₂ ──► CK₃ ...
                    │       │       │
                    ▼       ▼       ▼
                   MK₁     MK₂     MK₃
                    │       │       │
                    ▼       ▼       ▼
                  Msg 1   Msg 2   Msg 3
```

- Per-message key derivation from chain key
- Forward secrecy within session
- Recovery on time-period rotation (~42 hours)

**Mode 2: Full Double Ratchet (Future)**
- Adds DH ratchet step per message exchange
- Maximum PCS: recovery within 1 round-trip
- Higher bandwidth (32-byte DH public key per message)

### 5.4 Key Derivation Functions

All KDF operations use BLAKE2b with explicit domain separation:

```
KDF_CK(chain_key) → (new_chain_key, message_key)

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

### 5.5 Message Header Extensions

PCS messages include additional header fields:

| Field | Size | Description |
|-------|------|-------------|
| Version | 1 byte | 0x06 for PCS protocol |
| Flags | 1 byte | DH ratchet present, PCS capability |
| Message Number | 4 bytes | Chain position counter |
| Previous Chain Length | 4 bytes | For out-of-order handling |
| DH Public Key | 32 bytes | Optional, Mode 2 only |

**Minimum overhead**: 10 bytes per message
**Maximum overhead**: 50 bytes per message (with DH key)

### 5.6 Out-of-Order Message Handling

PCS maintains bounded storage of skipped message keys:

```java
MAX_SKIP = 1000           // Maximum skipped keys per contact
MAX_SKIP_AGE = 7 days     // Automatic pruning
```

When a message arrives out of order:
1. Calculate skipped key positions
2. Derive and store skipped keys (bounded)
3. Decrypt with correct key
4. Delete used key immediately

### 5.7 Capability Negotiation

PCS is negotiated during handshake and persisted per-contact:

| Alice PCS | Bob PCS | Result |
|-----------|---------|--------|
| Yes | Yes | PCS enabled (v6) |
| Yes | No | Legacy mode (v5) |
| No | Yes | Legacy mode (v5) |

**Downgrade Protection**: Once PCS is established, downgrade is blocked unless:
- User explicitly resets conversation
- Contact is re-added after deletion

### 5.8 Security Properties

| Property | Mode 1 | Mode 2 |
|----------|--------|--------|
| Forward Secrecy | ✅ | ✅ |
| Post-Compromise Recovery | Time-based (~42h) | 1 round-trip |
| Quantum Resistance | ✅ (via handshake) | ✅ |
| Out-of-order tolerance | ✅ | ✅ |

### 5.9 Implementation Status

| Phase | Status | Description |
|-------|--------|-------------|
| Phase 1 | **Design Complete** | Symmetric ratchet, capability negotiation |
| Phase 2 | Planned | Full Double Ratchet with DH |
| Phase 3 | Future | ML-KEM Braid (post-quantum ratchet) |

For complete technical specification, see `docs/PCS_DESIGN.md`.

---

## 6. VAULT FEATURE - SECURE FILE STORAGE

### 6.1 Vault Key Derivation

**Multi-Layer Security**:

```
User Password (8+ chars)
     ↓
[Argon2id KDF] ← Salt (256-bit)
     ├── Memory: 256 MB (high-end)
     ├── Iterations: 2-4
     └── Parallelism: 1
     ↓
Password-Derived Key (256-bit)

     +

Hardware Keystore
     ↓
[Generate Random Secret] (256-bit)
     ↓
[Wrap with Keystore Key]
     ↓
Wrapped Secret (stored)

     ↓

[XOR Combine]
     ↓
[HKDF-SHA256]("vault master")
     ↓
Vault Master Key (256-bit)
```

**Security Features**:
- **Memory-Hard**: Resists GPU/ASIC attacks
- **Hardware-Backed**: Android Keystore integration
- **Adaptive**: Calibrates to device specs
- **Password Verification MAC**: Fast wrong-password detection

### 6.2 Item Encryption

**Per-Item Security**:

```
Vault Item (file/note/password)
     ↓
[Generate Random Item Key] (256-bit)
     ↓
[AES-256-GCM Encrypt Content]
     ├── Key: Item Key
     ├── Nonce: 96-bit random
     ├── AAD: Item name
     └── Tag: 128-bit
     ↓
Encrypted Content

     Item Key
     ↓
[AES-256-GCM Encrypt with Master Key]
     ↓
Encrypted Item Key

     ↓

Store: {Encrypted Key, Encrypted Content, Metadata}
```

**Storage Structure**:
```
vault/
├── vault.header (KDF params, wrapped keys, MAC)
└── items/
    ├── {uuid-1}/
    │   ├── header.bin (encrypted metadata)
    │   └── content.bin (encrypted content)
    └── {uuid-2}/
        ├── header.bin
        └── content.bin
```

### 6.3 Vault Security Features

**Auto-Lock**:
- Timeout: 60 seconds of inactivity
- Key cleared from memory with random overwrite
- Requires password to re-unlock

**Rate Limiting**:
```
Failed Attempt 1: 1 second delay
Failed Attempt 2: 2 second delay
Failed Attempt 3: 4 second delay
Failed Attempt 4: 8 second delay
...
Failed Attempt 10: Vault locked (optional wipe)
```

**Secure Memory Management**:
```java
// Key clearing
SecureMemory.shred(vaultMasterKey); // Random overwrite
Arrays.fill(vaultMasterKey, (byte) 0); // Zero fill
vaultMasterKey = null; // Null reference
System.gc(); // Force garbage collection
```

**Metadata Stripping**:
- EXIF data removal from images
- GPS coordinates stripped
- Camera information removed
- Timestamp normalization

### 6.4 Vault Item Types

**Supported Types**:

1. **Secure Notes**
   - Plain text notes
   - Optional additional password layer
   - Markdown support

2. **Passwords**
   - Title, username, password, URL, notes
   - JSON serialization
   - Auto-fill integration ready

3. **Photos/Videos**
   - Metadata stripped
   - Encrypted thumbnails
   - Gallery view

4. **Documents**
   - PDFs, Office docs, etc.
   - Preview support
   - File type detection

### 6.5 Export/Import

**Export Process**:
```
[User Password] → [Argon2 KDF] → [Export Key]
                                      ↓
[Collect All Items] → [Re-encrypt with Export Key]
                                      ↓
[Export Container]: {Version, Salt, Items[]}
```

**Security**:
- Different password for export
- Portable encrypted container
- No cloud dependency
- Local file only

---

## 7. CONTACT DISCOVERY & ADDITION

### 7.1 QR Code Method

**Process Flow**:

```
User A                          User B
  |                               |
[Generate Handshake]      [Generate Handshake]
  |                               |
[Display QR Code]         [Display QR Code]
  |                               |
[Scan B's QR] ←────────────→ [Scan A's QR]
  |                               |
[Verify Commitment]       [Verify Commitment]
  |                               |
[Establish Connection]    [Establish Connection]
  |←────────Tor───────────────→|
  |                               |
[Key Agreement Protocol (BQP)]   |
  |←─────────────────────────→|
  |                               |
[Exchange Identities]     [Exchange Identities]
  |                               |
[Add Contact]             [Add Contact]
```

**QR Code Contents**:
- Protocol version
- Ephemeral public key (32 bytes)
- Commitment hash (32 bytes)
- Transport descriptors (Tor .onion)
- Encoded as Base64

### 7.2 Link-Based Addition

**Link Format**:
```
zerion://[base64-encoded-handshake-payload]
```

**Example**:
```
zerion://AQIDBAAFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIj...
```

**Exchange Methods**:
- Copy/paste
- Share via other apps
- Email, chat, etc.
- Same security as QR (commitment scheme)

### 7.3 Pending Contacts

**States**:

```
[LINK_EXCHANGED]
     ↓
[WAITING_FOR_CONNECTION]
     ↓
[CONNECTING]
     ↓
[KEY_AGREEMENT_STARTED]
     ↓
[VERIFYING]
     ↓
[ADDING_CONTACT]
     ↓
[ADDED] / [FAILED]
```

**Timeout Handling**:
- Connection timeout: 60 seconds
- Key agreement timeout: 120 seconds
- Automatic retry logic
- Error reporting

### 7.4 Contact Verification

**Trust Model**:

```
[UNVERIFIED] ──────────┐
     ↓                 ↓
[Compare Fingerprints] [Video Call]
     ↓                 ↓
[Out-of-Band Verification]
     ↓
[VERIFIED]
```

**Verification Methods**:
- In-person fingerprint comparison
- Phone call fingerprint read
- Video call verification
- Physical meeting

**Fingerprint Format**:
- 64-character hexadecimal
- Derived from public key
- Grouped for readability

### 7.5 Version Negotiation (Briar Compatibility)

Zerion implements automatic version negotiation to maintain backward compatibility with Briar while enabling post-quantum security for Zerion-to-Zerion communication.

**Link Format Versions**:

| Version | Key Type | Security Level | Compatible With |
|---------|----------|----------------|-----------------|
| **0** | X25519 (32 bytes) | Classical (128-bit) | Briar, Zerion |
| **1** | Hybrid commitment (32 bytes) | Post-Quantum (192-bit) | Zerion only |

**Version Detection Flow**:
```
┌─────────────────────────────────────────────────────────────────────────┐
│                    VERSION NEGOTIATION FLOW                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  1. Link Creation:                                                       │
│     • Zerion creates VERSION 1 links (hybrid PQ commitment)              │
│     • Briar creates VERSION 0 links (classical X25519)                   │
│                                                                          │
│  2. Link Parsing:                                                        │
│     • Parse incoming link → Extract version (0 or 1)                     │
│     • Store version in PendingContact.formatVersion                      │
│                                                                          │
│  3. Handshake Selection:                                                 │
│     • VERSION 0 → Use classical X25519 keys (Briar-compatible)           │
│     • VERSION 1 → Use hybrid ML-KEM-768 + X25519 keys (PQ-secure)        │
│                                                                          │
│  4. Contact Creation:                                                    │
│     • Store postQuantum flag based on handshake type                     │
│     • Display security level in Chat Settings UI                         │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

**Communication Scenarios**:
- **Zerion ↔ Zerion**: Both use VERSION 1 links → Hybrid PQ handshake → `postQuantum=true`
- **Zerion ↔ Briar**: Briar uses VERSION 0 link → Classical handshake → `postQuantum=false`

### 7.6 Downgrade Attack Prevention

Once a contact is established with post-quantum security, subsequent handshakes with the same remote author must also use PQ to prevent downgrade attacks.

**Protection Mechanism**:
```
┌─────────────────────────────────────────────────────────────────────────┐
│                    DOWNGRADE ATTACK PREVENTION                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  When completing a handshake for a new contact:                          │
│                                                                          │
│  1. Check existing contacts with same AuthorId                           │
│  2. If any existing contact has postQuantum=true:                        │
│     • New handshake MUST also be PQ (formatVersion=1)                    │
│     • Classical handshake attempt → SecurityDowngradeException           │
│  3. If no existing PQ contacts → Allow either classical or PQ            │
│                                                                          │
│  Attack Scenario Blocked:                                                │
│  ┌──────────────┐         ┌──────────────┐                               │
│  │   Zerion A   │←──PQ──→│   Zerion B   │  (established with PQ)         │
│  └──────────────┘         └──────────────┘                               │
│         ↓                                                                 │
│  [Attacker deletes contact, sends classical link]                        │
│         ↓                                                                 │
│  ┌──────────────────────────────────────┐                                │
│  │  SecurityDowngradeException thrown!  │  ← Attack blocked              │
│  └──────────────────────────────────────┘                                │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

**Database Schema**:
```sql
-- Contacts table includes postQuantum flag
contacts(
    contact_id INTEGER PRIMARY KEY,
    author_id BINARY(32),
    ...
    postQuantum BOOLEAN NOT NULL DEFAULT FALSE
)

-- PendingContacts table includes formatVersion
pendingContacts(
    pending_contact_id BINARY(32) PRIMARY KEY,
    ...
    formatVersion INT NOT NULL DEFAULT 0
)
```

---

## 8. P2P VOICE CALLING

### 8.1 Overview

Zerion provides **end-to-end encrypted peer-to-peer voice calling** over Tor, offering private and anonymous real-time voice communication between contacts. Voice calls use a **dedicated signaling protocol** separate from text messaging, with additional real-time audio streaming capabilities.

**Key Features**:
- End-to-end encrypted audio using AES-256-GCM
- Direct P2P connection over Tor hidden services
- **Dedicated VOICE_SIGNAL message type** (separate from text messages)
- **Opus codec compression** (16 kbps VOIP mode with FEC/PLC)
- Automatic audio processing (echo cancellation, noise suppression, automatic gain control)
- Call state management and connection monitoring
- Network quality metrics and adaptive handling
- **Screenshot protection** during active calls (FLAG_SECURE)
- **Speakerphone toggle** with configurable volume boost
- No third-party servers or relays

### 8.2 Voice Call Architecture

**Primary Service**: `VoiceCallService.java`

**Call Flow**:
```
[Caller Device]                                    [Callee Device]
      ↓                                                   ↓
[Initiate Call] → [Send VOICE_SIGNAL] ──Tor──→ [Receive Signal via VoiceSignalReceivedEvent]
      ↓                                                   ↓
[Generate Key]                                   [Accept/Decline]
      ↓                                                   ↓
[Derive Audio Keys] ←─────Tor──────── [Share Voice Key]
      ↓                                                   ↓
[P2P Connection] ←─────Tor Hidden Services─────→ [P2P Connection]
      ↓                                                   ↓
[Audio Stream] ←──────AES-256-GCM Encrypted─────→ [Audio Stream]
      ↓                                                   ↓
[Real-time Audio]                               [Real-time Audio]
```

### 8.3 Audio Configuration

**Audio Format**: Opus Codec Compressed Audio
- **Sample Rate**: 16 kHz
- **Bit Depth**: 16-bit signed integer (internal PCM)
- **Channels**: Mono (1 channel)
- **Frame Size**: 320 samples per frame (20ms frames)
- **Codec**: Opus VOIP mode via Concentus (pure Java implementation)
- **Bitrate**: ~16 kbps with Variable Bitrate (VBR)
- **Compression Ratio**: 16x (640 bytes PCM → ~40 bytes Opus)

**Audio Source**: `MediaRecorder.AudioSource.VOICE_COMMUNICATION`
- Automatically enables Acoustic Echo Cancellation (AEC)
- Automatically enables Noise Suppression (NS)
- Automatically enables Automatic Gain Control (AGC)
- Optimized for VoIP applications
- Hardware-accelerated when available

**Why Opus Codec (via Concentus)?**

Zerion uses the Opus codec implemented via the Concentus pure Java library:
1. **Bandwidth Efficiency**: 16 kbps vs 256 kbps PCM (16x compression)
2. **Forward Error Correction (FEC)**: Resilience against packet loss
3. **Packet Loss Concealment (PLC)**: Synthesizes audio for lost frames
4. **Pure Java**: No native library dependencies, cross-platform compatible
5. **VOIP Optimized**: Designed specifically for real-time voice communication

**Trade-off**: Slightly higher CPU usage for compression/decompression in exchange for dramatically lower bandwidth requirements, making voice calls more reliable over Tor.

### 8.4 Call Encryption

**Encryption Method**: AES-256-GCM (Galois/Counter Mode)

**Key Derivation Architecture**:
```
[Caller Generates Random Voice Call Key (256-bit)]
            ↓
[Share Key with Callee via Bramble Transport]
            ↓
[Both Derive Audio Encryption Keys Using HKDF]
            ↓
    ┌───────┴───────┐
    ↓               ↓
[Outgoing Key]  [Incoming Key]
    ↓               ↓
[Encrypt Sent]  [Decrypt Received]
```

**Key Properties**:
- **Voice Call Key**: 256-bit random key generated by caller
- **Key Sharing**: Transmitted securely via existing Bramble encrypted transport
- **Key Derivation**: HKDF-SHA256 derives separate outgoing/incoming keys
- **Per-Call Keys**: New key generated for each call (no key reuse)
- **Forward Secrecy**: Keys destroyed when call ends

**Audio Frame Encryption**:
```
Raw PCM Audio (320 samples, 640 bytes)
            ↓
[Opus Encoder] (Concentus pure Java)
            ↓
Compressed Opus Frame (~40 bytes)
            ↓
[AES-256-GCM Encryption]
    ├── Key: Derived outgoing/incoming key
    ├── IV/Nonce: Random 12 bytes per frame
    └── Auth Tag: 16 bytes GCM tag
            ↓
Encrypted Frame (~40 bytes + 12 IV + 16 tag = ~68 bytes)
            ↓
[Transmit over Tor Hidden Service]
            ↓
[AES-256-GCM Decryption on Receiver]
            ↓
Compressed Opus Frame (~40 bytes)
            ↓
[Opus Decoder] (with PLC for lost frames)
            ↓
Raw PCM Audio (320 samples, 640 bytes)
```

**Security Properties**:
- **Authenticated Encryption**: GCM mode provides both confidentiality and integrity
- **Per-Frame Nonce**: Each audio frame encrypted with unique random nonce
- **Replay Protection**: Frame sequence numbers prevent replay attacks
- **End-to-End**: Only caller and callee possess decryption keys

### 8.5 P2P Connection Management

**Connection Establishment**:
1. **Tor Circuit Setup**: Both devices establish Tor hidden service connections
2. **Signaling**: Dedicated VOICE_SIGNAL message type via Bramble transport
   - CALL_OFFER: Initiates call with encryption key
   - CALL_ANSWER: Accepts call with .onion address
   - CALL_REJECT: Declines incoming call
   - CALL_END: Terminates call with duration
   - CALL_BUSY: Indicates callee is in another call
3. **Signal Delivery**: Via `VoiceSignalReceivedEvent` to `VoiceCallService`
4. **Key Exchange**: Voice call key shared securely via CALL_OFFER signal
5. **Stream Setup**: Direct P2P audio socket connection over Tor
6. **Handshake**: Connection verification and audio pipeline initialization

**Connection States**:
```
IDLE → OUTGOING_RINGING → OUTGOING_CONNECTING →
OUTGOING_CONNECTED → ENDED

IDLE → INCOMING_RINGING → INCOMING_CONNECTING →
INCOMING_CONNECTED → ENDED

Error states: CONNECTION_ERROR, REJECTED, TIMEOUT
```

**Network Quality Monitoring**:
- **Jitter Tracking**: Monitors arrival time variance of audio packets
- **Packet Loss Detection**: Tracks missing/dropped frames
- **Latency Measurement**: Round-trip time estimation
- **Adaptive Handling**: Quality degradation warnings
- **Automatic Reconnection**: Recovery from temporary network failures

### 8.6 Audio Processing Pipeline

**Recording Pipeline** (Sender):
```
Microphone Input
      ↓
[MediaRecorder: VOICE_COMMUNICATION]
      ↓
[Automatic AEC/NS/AGC] ← Applied by Android
      ↓
[PCM Audio Capture] (16 kHz, 16-bit, mono)
      ↓
[Frame Buffering] (320 samples, 20ms frames)
      ↓
[Opus Encoder] (Concentus - 16 kbps VOIP mode)
      ↓
[CRC32 Integrity Check]
      ↓
[AES-256-GCM Encryption]
      ↓
[Transmit over Tor P2P Connection]
```

**Playback Pipeline** (Receiver):
```
[Receive from Tor P2P Connection]
      ↓
[AES-256-GCM Decryption]
      ↓
[CRC32 Verification]
      ↓
[Opus Decoder] (with PLC for lost/corrupted frames)
      ↓
[Jitter Buffer] (200-350ms circular buffer)
      ↓
[Volume Processing] (speakerphone boost if enabled)
      ↓
[AudioTrack Playback]
      ↓
Speaker/Earpiece Output
```

**Audio Effects (Automatic)**:
- **Acoustic Echo Cancellation (AEC)**: Prevents microphone from picking up speaker output
- **Noise Suppression (NS)**: Reduces background noise
- **Automatic Gain Control (AGC)**: Normalizes audio volume

**CRITICAL**: These effects are automatically enabled by the `VOICE_COMMUNICATION` audio source. Manual initialization causes double processing and robot-like audio distortion.

### 8.7 Call State Management

**Call States**:
- **IDLE**: No active call
- **OUTGOING_RINGING**: Calling peer, waiting for response
- **OUTGOING_CONNECTING**: Peer accepted, establishing P2P connection
- **OUTGOING_CONNECTED**: Active outgoing call with audio streaming
- **INCOMING_RINGING**: Receiving call, user can accept/decline
- **INCOMING_CONNECTING**: User accepted, establishing P2P connection
- **INCOMING_CONNECTED**: Active incoming call with audio streaming
- **ENDED**: Call terminated normally
- **CONNECTION_ERROR**: Network or connection failure
- **REJECTED**: Callee declined call
- **TIMEOUT**: No response within timeout period

**Notifications**:
- **Incoming Call**: Full-screen notification with accept/decline actions
- **Active Call**: Ongoing notification showing call duration
- **Call Ended**: Toast notification with reason (ended, declined, error, etc.)

### 8.8 User Interface

**Primary Activity**: `VoiceCallActivity.java`

**UI Components**:
- **Contact Avatar**: Visual identification of call participant
- **Call State Display**: Shows current state (connecting, connected, etc.)
- **Call Duration Timer**: Real-time call duration counter
- **Control Buttons**:
  - Microphone mute/unmute toggle
  - Speaker mode toggle (earpiece/speakerphone with 2.0x volume boost)
  - End call button
- **Network Quality Indicator**: Visual feedback for connection quality (latency, packet loss, signal strength, codec info)
- **Screenshot Protection**: FLAG_SECURE prevents screen capture during calls

**UI States**:
```
[Outgoing Call]
    ↓
"Calling [Contact Name]..."
    ↓
[Ringing animation]
    ↓
"Connected" + [Duration Timer]

[Incoming Call]
    ↓
"[Contact Name] is calling"
    ↓
[Accept] [Decline] buttons
    ↓
"Connected" + [Duration Timer]
```

### 8.9 Security & Privacy Properties

**Encryption Security**:
- **Algorithm**: AES-256-GCM (NIST-approved, industry-standard)
- **Key Size**: 256-bit keys (post-quantum resistant key size)
- **Authentication**: GCM authenticated encryption (integrity + confidentiality)
- **Forward Secrecy**: New key per call, keys destroyed after call ends
- **No Key Reuse**: Each call uses unique randomly generated key

**Transport Security**:
- **Tor Hidden Services**: Both signaling and audio over Tor
- **Onion Routing**: 3-hop circuit provides anonymity
- **No IP Exposure**: Caller and callee IP addresses hidden
- **Traffic Analysis Resistance**: Audio traffic indistinguishable from other Tor traffic

**Metadata Protection**:
- **No Call Logs on Server**: All call metadata local-only
- **No Third-Party Servers**: Direct P2P connection (no STUN/TURN servers)
- **No Phone Numbers**: Calls identified by cryptographic IDs
- **Offline Capable**: Call history stored in encrypted local database

**Privacy Guarantees**:
- **End-to-End Encryption**: Only caller and callee can decrypt audio
- **No Recording**: Application does not record or store call audio
- **No Telemetry**: Call quality metrics not transmitted to third parties
- **Anonymous Calling**: No personally identifiable information required

### 8.10 Performance Characteristics

**Audio Quality**:
- **Bitrate**: ~16 kbps (Opus VOIP mode)
- **Latency**: ~100-200ms end-to-end (including Tor routing)
- **Jitter**: <50ms with stable connection (200-350ms jitter buffer)
- **Packet Loss Tolerance**: Excellent - Opus FEC + PLC handles up to 20% loss
- **Compression Ratio**: 16x (640 bytes PCM → ~40 bytes Opus)

**Bandwidth Requirements**:
- **Upload**: ~18 kbps (outgoing audio with encryption overhead)
- **Download**: ~18 kbps (incoming audio with encryption overhead)
- **Total**: ~36 kbps bidirectional
- **Tor Overhead**: Additional ~10-20% for Tor routing overhead

**Battery Impact**:
- **Codec**: Moderate (Opus encoding/decoding in pure Java)
- **Tor Routing**: Moderate (encrypted routing overhead)
- **Screen On**: Higher when UI active
- **Background**: Wake locks prevent sleep during active call

**Network Requirements**:
- **Minimum Bandwidth**: 100 kbps bidirectional
- **Recommended**: 256 kbps+ for quality headroom
- **Connection Type**: WiFi or 3G/4G/5G cellular
- **Tor Circuit**: Stable 3-hop circuit required

### 8.11 Technical Implementation Files

**Core Service**:
- `VoiceCallService.java`: Main service managing P2P voice calls, audio streaming, encryption, and connection management

**Audio Components**:
- `OpusEncoder.java`: Opus encoder using Concentus pure Java library
- `OpusDecoder.java`: Opus decoder with PLC support using Concentus

**Signaling Protocol**:
- `VoiceSignal.java`: Voice signal message model
- `VoiceSignalType.java`: Signal type enum (CALL_OFFER, CALL_ANSWER, CALL_REJECT, CALL_END, ICE_CANDIDATE, CALL_BUSY)
- `VoiceSignalFactory.java`: Factory for creating voice signals
- `VoiceSignalReceivedEvent.java`: Event for delivering signals to VoiceCallService
- `MessageTypes.java`: Defines VOICE_SIGNAL type (type=2) separate from PRIVATE_MESSAGE

**UI Components**:
- `VoiceCallActivity.java`: Main call screen UI with screenshot protection
- `ConversationActivity.java`: Includes call button to initiate calls

**Encryption**:
- `VoiceCallCrypto.java`: AES-256-GCM encryption/decryption and key derivation

**Data Models**:
- Voice call state management integrated into Bramble transport layer

### 8.12 Voice Signaling Protocol

**Dedicated Message Type**:
Voice call signaling uses a dedicated `VOICE_SIGNAL` message type (type=2) completely separate from text messages (type=0). This ensures:
- Voice signals never appear in the conversation UI
- Clean separation between messaging and voice call protocols
- Reliable signal delivery without message clutter

**Signal Types**:
```
CALL_OFFER (0)    - Initiates call with encryption key
CALL_ANSWER (1)   - Accepts call with .onion address
CALL_REJECT (2)   - Declines incoming call
CALL_END (3)      - Terminates call with optional duration
ICE_CANDIDATE (4) - Network connectivity data
CALL_BUSY (5)     - Callee is in another call
```

**Signal Delivery**:
- Signals are parsed by `PrivateMessageValidator`
- Delivered via `VoiceSignalReceivedEvent` to `VoiceCallService`
- Complete isolation from text messaging flow

---

## 9. DATA STORAGE SECURITY

### 9.1 Database Encryption

**Encryption Architecture** (Post-Quantum Hardened):

```
User Password
     ↓
[Argon2id KDF] ← Post-Quantum Hardened (v1.4+)
     ├── Memory: 64-512 MB (adaptive)
     ├── Iterations: 2-6 (calibrated ~1 second)
     ├── Parallelism: 1
     ├── Salt: 256-bit random
     └── Output: 256-bit key
     ↓
[Optional Key Strengthening]
     └── Android Keystore HMAC-SHA256
     ↓
Database Encryption Key (256-bit)
     ↓
[XSalsa20-Poly1305 AEAD]
     ├── 192-bit nonce (random per encryption)
     ├── 128-bit authentication tag
     └── Authenticated encryption
     ↓
Encrypted Database Key File
     ↓
[H2/HyperSQL Database]
     ├── AES-256-CBC
     ├── Per-page encryption
     └── Random IV per block
     ↓
Encrypted Database File
```

**Post-Quantum Security Analysis**:
- **Argon2id**: Memory-hard KDF, not affected by quantum computers
- **XSalsa20-Poly1305**: 256-bit symmetric - Grover's algorithm halves to 128-bit security
- **BLAKE2b**: Hash function maintains 128-bit PQ security at 256-bit output
- **Overall**: 128-bit post-quantum security for database encryption

### 9.2 KDF Migration (Scrypt → Argon2id)

**Automatic Migration Protocol**:
```
[User Login with Password]
     ↓
[Load Encrypted DB Key from File]
     ↓
[Check Format Version Byte]
     ├── 0 = Scrypt (legacy)
     ├── 1 = Scrypt + Strengthened (legacy)
     ├── 2 = Argon2id (current)
     └── 3 = Argon2id + Strengthened (current)
     ↓
[Decrypt with Appropriate KDF]
     ├── Legacy: Use Scrypt KDF
     └── Current: Use Argon2id KDF
     ↓
[Successful Decryption?]
     ├── No → Invalid Password Error
     └── Yes → Continue
     ↓
[Check Migration Needed?]
     ├── Format 0 or 1 → Re-encrypt with Argon2id
     └── Format 2 or 3 → No action needed
     ↓
[Store Updated Key File]
     ↓
[User Logged In - Migration Complete]
```

**Migration Properties**:
- **Transparent**: Users don't notice the migration
- **Automatic**: Happens on first login after upgrade
- **One-Way**: Once migrated, always uses Argon2id
- **Backward Compatible**: Can still read legacy Scrypt-encrypted keys

**Database Schema** (Simplified):

```sql
-- Contacts
contacts(
    contact_id BINARY(32) PK,
    author_id BINARY(32),
    public_key BINARY(32),
    verified BOOLEAN,
    alias VARCHAR
)

-- Messages
messages(
    message_id BINARY(32) PK,
    group_id BINARY(32),
    timestamp BIGINT,
    raw BLOB,
    state INTEGER,
    shared BOOLEAN
)

-- Groups
groups(
    group_id BINARY(32) PK,
    client_id VARCHAR,
    descriptor BLOB
)

-- Settings
settings(
    namespace VARCHAR,
    key VARCHAR,
    value VARCHAR,
    PRIMARY KEY (namespace, key)
)
```

### 9.3 Secure File Deletion

**Multi-Pass Overwrite**:

```
Pass 1: Random data
Pass 2: Zeros
Pass 3: Random data
     ↓
[Force Sync to Disk]
     ↓
[Delete File]
     ↓
[Sync Directory]
```

**Implementation**:
- 4096-byte buffer
- Secure random source
- fsync() after each pass
- Directory sync for persistence

### 9.4 Attachment Storage

**Encrypted Attachments**:

```
Attachment File
     ↓
[Generate Attachment Key]
     ↓
[XSalsa20-Poly1305 Encrypt]
     ↓
[Store Encrypted File]
     +
[Store Key in Database]
     ↓
[Auto-Cleanup After Delivery]
```

**Storage Location**:
```
/data/data/com.professor.zerion/files/attachments/
├── {message-id-1}.encrypted
├── {message-id-2}.encrypted
└── ...
```

### 9.5 Memory Security

**Key Lifecycle Management**:

```
[Generate/Derive Key]
     ↓
[Use for Crypto Operation]
     ↓
[Overwrite with Random Data]
     ↓
[Zero Fill]
     ↓
[Null Reference]
     ↓
[Force Garbage Collection]
```

**Protected Data**:
- Encryption keys
- Password-derived keys
- Plaintext messages (temporary)
- Vault master key
- Private keys

---

## 10. FEATURE HIGHLIGHTS

### 10.1 Disappearing Messages

**Configuration**:
- Timer options: 5 min, 1 hour, 1 day, 1 week, custom
- Per-conversation setting
- Mutual agreement required

**Deletion Process**:
```
[Message Delivered]
     ↓
[Message Read]
     ↓
[Timer Starts]
     ↓
[Timer Expires]
     ↓
[Secure Deletion]
     ├── Overwrite message
     ├── Delete from database
     └── Notify remote peer
```

### 10.2 Voice Messages

**Technical Details**:
- **Codec**: Opus (high quality, low bitency)
- **Recording**: Up to 5 minutes
- **Storage**: Encrypted like other attachments
- **UI**: Waveform visualization, playback controls

**Features**:
- Hold-to-record
- Slide-to-cancel
- Play/pause controls
- Progress bar
- Duration display

### 10.3 Rich Attachments

**Supported Types**:
- Images (JPEG, PNG, GIF, WebP)
- Videos (MP4, WebM)
- Documents (PDF, Office, Text)
- Audio (MP3, OGG, Opus)

**Processing**:
```
[Select File]
     ↓
[Validate Type & Size]
     ↓
[Strip Metadata] (images/videos)
     ↓
[Encrypt]
     ↓
[Store Locally]
     ↓
[Queue for Sync]
```

**Size Limits**:
- Images: 10 MB
- Videos: 10 MB
- Documents: 10 MB
- Configurable by user

### 10.4 Network Resilience

**Offline Capabilities**:
- Messages queued locally
- Automatic sync when online
- No message loss
- Delivery confirmation

**Connection Management**:
- Automatic Tor reconnection
- Circuit rebuilding
- Transport fallback
- Network change detection

**Battery Optimization**:
- Doze mode exemption (optional)
- Background restrictions handling
- Adaptive sync frequency
- WiFi-only mode (optional)

### 10.5 User Experience

**Material Design 3**:
- Modern UI
- Dark/light themes
- Adaptive colors
- Smooth animations

**Accessibility**:
- Screen reader support
- Large text support
- High contrast mode
- Keyboard navigation

**Localization**:
- Multiple languages supported
- RTL layout support
- Culturally appropriate content

---

## 11. SECURITY PROPERTIES

### 11.1 Threat Model

**Protected Against**:

| Threat | Protection |
|--------|------------|
| Network Surveillance | Tor anonymity |
| Metadata Collection | Minimal metadata, P2P architecture |
| Message Interception | End-to-end encryption |
| Database Theft | Encrypted at rest |
| Password Attacks | Memory-hard KDF (Scrypt/Argon2) |
| Timing Attacks | Constant-time operations |
| Man-in-the-Middle | Key agreement commitment scheme |
| Replay Attacks | Nonces, timestamps, MACs |

**Out of Scope**:
- Device compromise (malware, root access)
- Physical device access
- User phishing/social engineering

**Quantum Computer Protection** ✅:
- **Key Exchange**: Hybrid ML-KEM-768 + X25519 (NIST Level 3)
- **Signatures**: Hybrid ML-DSA-65 + Ed25519 (NIST Level 3)
- **Defense-in-Depth**: Both classical AND PQ algorithms must be broken

### 11.2 Cryptographic Security

**Key Strengths**:
- **Hybrid Key Exchange**: ML-KEM-768 (1,184 bytes) + X25519 (32 bytes)
- **Hybrid Signatures**: ML-DSA-65 (1,952 bytes) + Ed25519 (32 bytes)
- Symmetric: 256-bit keys (128-bit post-quantum security)
- Hash: BLAKE2b-256/384 output (128/192-bit PQ security)
- MAC: 256-bit keys

**Security Properties**:

1. **Forward Secrecy**
   - Ephemeral keys per session
   - Past sessions protected even if long-term key compromised

2. **Post-Compromise Security**
   - Key rotation limits damage
   - Static key compromise bounded in time

3. **Deniability**
   - No non-repudiable signatures on messages
   - Plausible deniability of message content

4. **Authentication**
   - Mutual authentication in key agreement
   - Message authentication via MACs
   - Contact verification via fingerprints

### 11.3 Privacy Properties

**Anonymity**:
- All connections via Tor
- No IP address exposure
- No phone number requirement
- No email requirement
- No central user database

**Metadata Minimization**:
- No timestamps visible to network
- No message sizes visible
- No traffic analysis patterns
- Group membership hidden (invisible mode)

**Local Privacy**:
- Encrypted database
- Secure screen capture blocking
- Incognito keyboard mode
- Screenshot detection

---

## 12. TECHNICAL SPECIFICATIONS

### 12.1 System Requirements

**Minimum**:
- Android 5.0 (API 21)
- 2 GB RAM
- 100 MB storage
- Network connectivity

**Recommended**:
- Android 8.0+ (API 26+)
- 4 GB RAM
- 500 MB storage
- WiFi connectivity

### 12.2 Performance Characteristics

**Cryptographic Operations**:
- Key generation: ~50ms
- Key agreement: ~100ms
- Message encryption: <1ms
- Message decryption: <1ms
- Password derivation: 2-4 seconds

**Network Performance**:
- Tor bootstrap: 10-30 seconds
- Connection establishment: 5-15 seconds
- Message latency: 1-5 seconds (Tor)
- Max throughput: Limited by Tor (~1 MB/s)

**Battery Usage**:
- Idle (connected): ~2-5% per hour
- Active messaging: ~5-10% per hour
- Background sync: ~1-3% per hour

### 12.3 Cryptographic Specifications

| Component | Algorithm | Key/Output Size | Post-Quantum Security |
|-----------|-----------|-----------------|----------------------|
| **Key Agreement** | **Hybrid ML-KEM-768 + X25519** | **1,216-byte public key** | ✅ **NIST Level 3** |
| **Signatures** | **Hybrid ML-DSA-65 + Ed25519** | **1,984-byte public key** | ✅ **NIST Level 3** |
| Symmetric Encryption | XSalsa20-Poly1305 | 256-bit key, 192-bit nonce | ✅ 128-bit PQ |
| Vault Encryption | AES-256-GCM | 256-bit key, 96-bit nonce | ✅ 128-bit PQ |
| Hashing | BLAKE2b-256/384 | 256/384-bit | ✅ 128/192-bit PQ |
| MAC | BLAKE2b (keyed) | 256-bit key, 256-bit tag | ✅ 128-bit PQ |
| KDF | HKDF-BLAKE2b | Variable output | ✅ PQ-safe |
| Password KDF (DB) | **Argon2id** | 256-bit | ✅ Quantum-resistant |
| Password KDF (Vault) | Argon2id | 256-bit | ✅ Quantum-resistant |

**Hybrid Key Sizes (Phase 2 Complete)**:

| Key Type | X25519/Ed25519 | ML-KEM/ML-DSA | Total Hybrid |
|----------|----------------|---------------|--------------|
| Agreement Public Key | 32 bytes | 1,184 bytes | **1,216 bytes** |
| Agreement Private Key | 32 bytes | 2,400 bytes | **2,432 bytes** |
| Signature Public Key | 32 bytes | 1,952 bytes | **1,984 bytes** |
| Signature Private Key | 32 bytes | 4,032 bytes | **4,064 bytes** |
| Signature | 64 bytes | 3,309 bytes | **3,373 bytes** |

### 12.4 Protocol Versions

- **Bramble Protocol**: v1
- **Transport Protocol**: v2
- **Sync Protocol**: v2
- **Handshake Protocol**: v1 (classical) / v2 (hybrid PQ)
- **Database Schema**: v52

### 12.5 Network Parameters

| Parameter | Value | Notes |
|-----------|-------|-------|
| Max Message Size | 32 KB | Compressed |
| Max Attachment Size | 10 MB | User configurable |
| Connection Timeout | 60 seconds | Per attempt |
| Tor Circuit Timeout | 120 seconds | Bootstrap |
| Key Rotation Period | 24 hours | Transport keys |
| Max Offline Queue | 10,000 messages | Per contact |
| Sync Interval | 30 seconds | When active |

---

## 13. ARCHITECTURE DIAGRAMS

### 13.1 System Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Android UI Layer                     │
│  (Activities, Fragments, ViewModels, Compose)           │
│  - Splash Screen      - Conversations     - Vault UI    │
│  - Setup Flow         - Contact List      - Settings    │
└───────────────────────┬─────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────┐
│              Briar Application Layer                    │
│  - Private Messaging    - Groups         - Vault Mgmt   │
│  - Contact Management   - Attachments    - Key Exchange │
└───────────────────────┬─────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────┐
│             Bramble Protocol Layer                      │
│  - Crypto Component    - Sync Protocol   - Key Mgmt     │
│  - Transport Crypto    - Database        - Event Bus    │
└───────────────────────┬─────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────┐
│               Transport Plugins                         │
│  - Tor Plugin (primary)    - Removable Drive (backup)   │
│  - LAN TCP (deprecated)                                 │
└─────────────────────────────────────────────────────────┘
                        │
                        ▼
                 [Tor Network]
```

### 13.2 Message Flow Diagram

```
┌──────────┐                                    ┌──────────┐
│  User A  │                                    │  User B  │
└────┬─────┘                                    └────┬─────┘
     │                                               │
     │ 1. Compose Message                            │
     ▼                                               │
┌─────────────┐                                      │
│  Encrypt    │                                      │
│  (E2E Key)  │                                      │
└─────┬───────┘                                      │
      │                                              │
      │ 2. Store Locally                             │
      ▼                                              │
┌──────────────┐                                     │
│  Database A  │                                     │
└──────┬───────┘                                     │
       │                                             │
       │ 3. Sync Protocol                            │
       ▼                                             │
┌──────────────┐         4. Tor Transport      ┌────▼────────┐
│ Offer/Batch  │ ─────────────────────────────►│   Sync Rx   │
└──────────────┘                                └────┬────────┘
                                                     │
                                               5. Store
                                                     ▼
                                              ┌─────────────┐
                                              │ Database B  │
                                              └──────┬──────┘
                                                     │
                                               6. Decrypt
                                                     ▼
                                              ┌─────────────┐
                                              │   Display   │
                                              └─────────────┘
```

### 13.3 Vault Encryption Layers

```
User Password ────┐
                  │
        [Argon2 KDF] ← Salt
                  │
                  ▼
        Password Key (256-bit)
                  │
                  ├─────────────┐
                  │             │
                  ▼             ▼
        ┌──────────────┐  ┌──────────────┐
        │   XOR with   │  │   Keystore   │
        │   Keystore   │  │   Random     │
        │   Secret     │  │   Secret     │
        └──────┬───────┘  └──────────────┘
               │
               ▼
        [HKDF-SHA256]
               │
               ▼
        Vault Master Key (256-bit)
               │
               ├────────────────┬────────────────┐
               │                │                │
               ▼                ▼                ▼
        ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
        │ Encrypt Item │ │   Encrypt    │ │   Password   │
        │     Keys     │ │   Metadata   │ │   Verify MAC │
        └──────────────┘ └──────────────┘ └──────────────┘
```

### 13.4 Tor Connection Flow

```
┌────────────┐
│   Zerion   │
└──────┬─────┘
       │ 1. Start Tor
       ▼
┌──────────────────┐
│   Tor Process    │
└──────┬───────────┘
       │ 2. Bootstrap
       ▼
┌──────────────────┐
│   Tor Network    │
│  (Guard Nodes)   │
└──────┬───────────┘
       │ 3. Build Circuits
       ▼
┌──────────────────────────────────┐
│      Hidden Service (.onion)     │
│                                  │
│  [Generate Key Pair]             │
│         ↓                        │
│  [Create Descriptor]             │
│         ↓                        │
│  [Publish to Directory]          │
│         ↓                        │
│  [Listen for Connections]        │
└──────┬───────────────────────────┘
       │
       │ 4. Accept Incoming / Make Outgoing
       ▼
┌──────────────────┐
│  Peer Connection │
└──────────────────┘
```

---

## 14. FILE PATH REFERENCE

### 14.1 Core Application Files

**Onboarding & Setup**:
```
briar-android/src/main/java/com/professor/zerion/android/
├── splash/SplashScreenActivity.java
├── account/SetupActivity.java
├── account/SetPasswordFragment.java
└── account/DozeFragment.java
```

**Messaging**:
```
briar-core/src/main/java/org/briarproject/briar/
├── messaging/PrivateMessageFactoryImpl.java
├── messaging/MessagingManagerImpl.java
├── privategroup/PrivateGroupManagerImpl.java
└── privategroup/GroupMessageFactoryImpl.java
```

**Cryptography (Post-Quantum Hybrid)**:
```
bramble-core/src/main/java/org/briarproject/bramble/crypto/
├── CryptoComponentImpl.java        # Core crypto with hybrid PQ support
├── TransportCryptoImpl.java
├── PasswordBasedKdf.java           # Argon2id KDF implementation
├── PasswordStrengthEstimatorImpl.java
├── KeyAgreementCryptoImpl.java
├── MlKem768.java                   # ML-KEM-768 BouncyCastle wrapper
├── MlDsa65.java                    # ML-DSA-65 BouncyCastle wrapper
├── HybridKeyAgreement.java         # Hybrid X25519 + ML-KEM operations
├── HybridSignature.java            # Hybrid Ed25519 + ML-DSA operations
├── HybridAgreementKeyParser.java   # Parse hybrid agreement keys
└── HybridSignatureKeyParser.java   # Parse hybrid signature keys
```

**Post-Quantum Key Classes (API)**:
```
bramble-api/src/main/java/org/briarproject/bramble/api/crypto/
├── PostQuantumConstants.java       # PQ key/signature size constants
├── CryptoAlgorithm.java            # Algorithm enum with key sizes
├── HybridAgreementPublicKey.java   # X25519 + ML-KEM-768 (1,216 bytes)
├── HybridAgreementPrivateKey.java  # X25519 + ML-KEM-768 (2,432 bytes)
├── HybridSignaturePublicKey.java   # Ed25519 + ML-DSA-65 (1,984 bytes)
└── HybridSignaturePrivateKey.java  # Ed25519 + ML-DSA-65 (4,064 bytes)
```

**Key Agreement**:
```
bramble-core/src/main/java/org/briarproject/bramble/keyagreement/
├── KeyAgreementTaskImpl.java
├── KeyAgreementProtocol.java
├── KeyAgreementConnector.java
└── PayloadParserImpl.java
```

**Tor Integration**:
```
bramble-core/src/main/java/org/briarproject/bramble/plugin/tor/
├── TorPlugin.java
├── TorPluginFactory.java
├── CircumventionProvider.java
└── BridgeTest.java
```

**Database**:
```
bramble-core/src/main/java/org/briarproject/bramble/db/
├── DatabaseComponentImpl.java
├── DatabaseModule.java
├── H2Database.java
└── Migration.java
```

**Vault**:
```
briar-android/src/main/java/com/professor/zerion/android/vault/
├── VaultManager.java
├── VaultViewModel.java
├── crypto/
│   ├── VaultCrypto.java
│   ├── VaultKeystore.java
│   └── Argon2.java
├── storage/SecureFileIO.java
├── utils/
│   ├── MetadataStripper.java
│   └── SecureMemory.java
└── ui/
    ├── VaultActivity.java
    ├── VaultSetupFragment.java
    ├── VaultGalleryFragment.java
    ├── VaultPasswordsFragment.java
    └── SecureNoteFragment.java
```

**P2P Voice Calling**:
```
briar-android/src/main/java/com/professor/zerion/android/conversation/voice/
├── VoiceCallService.java          # Core P2P voice call service
├── VoiceCallActivity.java         # Voice call UI
├── OpusEncoder.java               # Opus audio encoder (disabled)
├── OpusDecoder.java               # Opus audio decoder (disabled)
└── VoiceCallCrypto.java          # Audio encryption/decryption
```

**Contact Addition**:
```
briar-android/src/main/java/com/professor/zerion/android/contact/add/
├── nearby/AddNearbyContactActivity.java
├── remote/LinkExchangeFragment.java
└── remote/AddContactViewModel.java
```

### 14.2 Configuration Files

**Build Configuration**:
```
briar-android/build.gradle
bramble-android/build.gradle
bramble-core/build.gradle
briar-core/build.gradle
```

**Security Configuration**:
```
briar-android/src/main/res/xml/
├── network_security_config.xml
└── backup_rules.xml
```

**Manifest**:
```
briar-android/src/main/AndroidManifest.xml
```

---

## 15. CONCLUSION

### 15.1 Summary of Security Features

Zerion provides military-grade security through:

1. **Multi-Layer Encryption**
   - End-to-end messaging encryption
   - End-to-end voice call encryption (AES-256-GCM)
   - Encrypted database storage
   - Encrypted vault storage
   - Transport layer encryption

2. **Anonymity & Privacy**
   - Tor-based networking
   - No phone number requirement
   - No central server
   - Minimal metadata

3. **Modern Post-Quantum Cryptography**
   - **Hybrid ML-KEM-768 + X25519** (NIST FIPS 203)
   - **Hybrid ML-DSA-65 + Ed25519** (NIST FIPS 204)
   - XSalsa20-Poly1305
   - AES-256-GCM
   - BLAKE2b-256/384
   - Argon2id

4. **Secure Key Management**
   - Hardware-backed keys (Android Keystore)
   - Memory-hard password derivation
   - Automatic key rotation
   - Secure key deletion

### 15.2 Unique Features

**Vault Integration**:
- Unified secure storage
- Metadata stripping
- Multiple item types
- Export/import capability

**P2P Architecture**:
- No single point of failure
- No data on servers
- Offline message queuing
- Resilient communication

**P2P Voice Calling**:
- End-to-end encrypted voice calls over Tor
- Opus codec (16 kbps VOIP mode via Concentus)
- Automatic echo cancellation, noise suppression, AGC
- No third-party STUN/TURN servers
- Forward secrecy with per-call keys
- Low-latency optimized for Tor (~100-200ms)
- Dedicated VOICE_SIGNAL protocol (separate from messaging)
- Screenshot protection during calls
- Speakerphone toggle with volume boost

**Privacy by Design**:
- No telemetry
- No crash reporting (optional)
- No analytics
- Open source (auditable)

### 15.3 Use Cases

**High-Security Communication**:
- Journalists and sources
- Activists and organizers
- Privacy-conscious individuals
- Corporate confidential communication

**Secure File Storage**:
- Sensitive documents
- Password management
- Private photos/videos
- Encrypted backups

**Anonymous Networking**:
- Tor-only communication
- No IP exposure
- Censorship circumvention
- Traffic analysis resistance

### 15.4 Post-Quantum Cryptography Status

**Two-Phase Post-Quantum Migration - COMPLETE**:

| Phase | Component | Status | Algorithms |
|-------|-----------|--------|------------|
| **Phase 1** | Database & Login | ✅ **Complete** | Argon2id KDF |
| **Phase 2** | Messaging Layer | ✅ **Complete** | ML-KEM-768 + X25519, ML-DSA-65 + Ed25519 |

**Phase 1 - Database & Login (v1.4+)**:
- ✅ **Argon2id KDF** - Memory-hard password derivation (replaces Scrypt)
- ✅ **BLAKE2b-384** - Enhanced hash function option (192-bit PQ security)
- ✅ **Automatic Migration** - Legacy Scrypt databases upgraded transparently
- ✅ **256-bit Symmetric Keys** - 128-bit post-quantum security throughout

**Phase 2 - Messaging Layer (v2.0+)**:
- ✅ **Hybrid ML-KEM-768 + X25519** - Post-quantum key encapsulation (NIST FIPS 203)
- ✅ **Hybrid ML-DSA-65 + Ed25519** - Post-quantum digital signatures (NIST FIPS 204)
- ✅ **Defense-in-Depth** - Both algorithms must be broken to compromise security
- ✅ **NIST Level 3** - AES-192 equivalent post-quantum security

**Hybrid Key Exchange (ML-KEM-768 + X25519)**:
```
┌─────────────────────────────────────────────────────────────┐
│              HYBRID KEY EXCHANGE                            │
├─────────────────────────────────────────────────────────────┤
│  NIST FIPS 203 compliant (August 2024 standard)             │
│  Public Key Size: 1,216 bytes (32 X25519 + 1,184 ML-KEM)    │
│  Ciphertext Size: 1,088 bytes                               │
│  Security Level: NIST Level 3 (AES-192 equivalent PQ)       │
└─────────────────────────────────────────────────────────────┘
```

**Hybrid Digital Signatures (ML-DSA-65 + Ed25519)**:
```
┌─────────────────────────────────────────────────────────────┐
│              HYBRID DIGITAL SIGNATURES                       │
├─────────────────────────────────────────────────────────────┤
│  NIST FIPS 204 compliant (August 2024 standard)             │
│  Public Key Size: 1,984 bytes (32 Ed25519 + 1,952 ML-DSA)   │
│  Signature Size: 3,373 bytes (64 Ed25519 + 3,309 ML-DSA)    │
│  Security Level: NIST Level 3 (AES-192 equivalent PQ)       │
└─────────────────────────────────────────────────────────────┘
```

**Complete Post-Quantum Security Analysis**:
| Component | Algorithm | Classical Security | Post-Quantum Security |
|-----------|-----------|-------------------|----------------------|
| Key Exchange | **Hybrid ML-KEM-768 + X25519** | 128-bit | **192-bit (NIST Level 3)** |
| Signatures | **Hybrid ML-DSA-65 + Ed25519** | 128-bit | **128-bit (NIST Level 3)** |
| Database KDF | Argon2id | 256-bit | 128-bit (Grover) |
| Symmetric Encryption | XSalsa20-Poly1305 | 256-bit | 128-bit (Grover) |
| Hash Functions | BLAKE2b-256/384 | 256/384-bit | 128/192-bit (Grover) |
| MAC | BLAKE2b-256 | 256-bit | 128-bit (Grover) |

**Why Hybrid Cryptography?**
```
┌─────────────────────────────────────────────────────────────┐
│                    HYBRID SECURITY                          │
├─────────────────────────────────────────────────────────────┤
│  Attacker must break BOTH:                                  │
│    • X25519/Ed25519 (classical) AND                         │
│    • ML-KEM-768/ML-DSA-65 (post-quantum)                    │
│                                                             │
│  If quantum computers break X25519/Ed25519:                 │
│    → ML-KEM-768/ML-DSA-65 still protects data               │
│                                                             │
│  If flaws found in ML-KEM/ML-DSA:                           │
│    → X25519/Ed25519 still protects data                     │
└─────────────────────────────────────────────────────────────┘
```

**Note**: Symmetric cryptography is quantum-resistant. Grover's algorithm only provides quadratic speedup, halving effective security. 128-bit post-quantum security remains computationally infeasible.

**Security Improvements**:
- Hardware security module integration
- Biometric authentication
- Secure element support
- Zero-knowledge architecture

### 15.5 Compliance & Auditing

**Security Audit Status**:
- Code review ongoing
- Penetration testing planned
- Third-party audit recommended

**Open Source**:
- Full source code available
- Community contributions welcome
- Transparent development
- Public issue tracking

---

## APPENDIX A: GLOSSARY

**AEAD**: Authenticated Encryption with Associated Data
**BQP**: Bramble QR Protocol
**E2EE**: End-to-End Encryption
**ECDH**: Elliptic Curve Diffie-Hellman
**EdDSA**: Edwards-curve Digital Signature Algorithm
**HKDF**: HMAC-based Key Derivation Function
**KDF**: Key Derivation Function
**MAC**: Message Authentication Code
**P2P**: Peer-to-Peer
**PFS**: Perfect Forward Secrecy
**PII**: Personally Identifiable Information

---

## APPENDIX B: REFERENCES

**Cryptographic Standards**:
- RFC 7748: Elliptic Curves for Security (Curve25519)
- RFC 8032: Edwards-Curve Digital Signature Algorithm (Ed25519)
- RFC 7539: ChaCha20 and Poly1305 (XSalsa20-Poly1305)
- RFC 5869: HMAC-based Extract-and-Expand Key Derivation Function

**NIST Post-Quantum Standards (August 2024)**:
- **FIPS 203**: ML-KEM (Module-Lattice-Based Key-Encapsulation Mechanism)
- **FIPS 204**: ML-DSA (Module-Lattice-Based Digital Signature Algorithm)

**Libraries Used**:
- Bouncy Castle 1.82+ (cryptography, ML-KEM, ML-DSA)
- LibSodium/Lazysodium (NaCl crypto)
- i2p.crypto.eddsa (Ed25519)
- Curve25519-java (X25519)
- Tor Expert Bundle (anonymity)
- H2/HyperSQL (encrypted database)

**Security Research**:
- Signal Protocol (inspiration)
- Briar Project (base architecture)
- Tor Project (anonymity network)

---

**Document Information**:
- **Version**: 2.1
- **Date**: December 16, 2025
- **Status**: Production
- **Classification**: Public Technical Documentation
- **Author**: Zerion Development Team
- **Contact**: https://github.com/zerionproject/Zerion

**Document History**:
- v2.1 (2025-12-16): **Version Negotiation & Security Hardening** - Added Briar compatibility via explicit contact type selection, downgrade attack prevention, contact security level tracking (postQuantum flag), UI security indicator in Chat Settings
- v2.0 (2025-11-26): **Full hybrid post-quantum cryptography** - Phase 2 complete with ML-KEM-768 + X25519 key exchange and ML-DSA-65 + Ed25519 signatures (NIST FIPS 203/204)
- v1.4 (2025-11-26): Post-quantum Phase 1 - Argon2id KDF migration, BLAKE2b-384 hash option, automatic Scrypt→Argon2id migration
- v1.3 (2025-11-26): Updated voice calling with dedicated VOICE_SIGNAL protocol, Opus codec, screenshot protection, speakerphone improvements
- v1.2 (2025-01-14): Added Opus codec integration and network quality indicators
- v1.1 (2025-01-10): Added voice call documentation
- v1.0 (2025-11-10): Initial comprehensive whitepaper

---

*This whitepaper is based on the Zerion codebase. For the most current information, please refer to the source code repository and official documentation.*

**End of Document**
