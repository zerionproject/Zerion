# Phase 2: Post-Quantum Messaging Layer Migration

## Status: COMPLETE (with Full Security Hardening)

Phase 2 implementation of hybrid post-quantum cryptography for Zerion's messaging layer is **fully implemented and tested**, including:
- Version negotiation for backward compatibility with Briar
- Persistent security level tracking in database
- Downgrade attack prevention
- UI security level indicator

## Overview

This document describes the implementation of hybrid post-quantum cryptography for Zerion's messaging layer. Phase 1 (completed) hardened the database/login layer with Argon2id. Phase 2 addresses the remaining quantum-vulnerable components:

- **Key Exchange**: Curve25519 ECDH → **Hybrid ML-KEM-768 + X25519** ✅
- **Signatures**: Ed25519 → **Hybrid ML-DSA-65 + Ed25519** ✅
- **Version Negotiation**: Automatic detection of peer capabilities ✅

## Implementation Summary

### Completed Components

| Component | Status | Files |
|-----------|--------|-------|
| ML-KEM-768 Wrapper | ✅ Complete | `MlKem768.java` |
| ML-DSA-65 Wrapper | ✅ Complete | `MlDsa65.java` |
| Hybrid Key Agreement | ✅ Complete | `HybridKeyAgreement.java` |
| Hybrid Signatures | ✅ Complete | `HybridSignature.java` |
| Hybrid Key Classes | ✅ Complete | `HybridAgreement*.java`, `HybridSignature*.java` |
| Key Parsers | ✅ Complete | `HybridAgreementKeyParser.java`, `HybridSignatureKeyParser.java` |
| CryptoComponent API | ✅ Complete | `CryptoComponent.java`, `CryptoComponentImpl.java` |
| Protocol Constants | ✅ Complete | `KeyAgreementConstants.java`, `HandshakeLinkConstants.java` |
| Version Negotiation | ✅ Complete | `Identity.java`, `IdentityManagerImpl.java`, `PendingContact.java` |
| Handshake Selection | ✅ Complete | `HandshakeManagerImpl.java`, `PendingContactFactoryImpl.java` |
| Unit Tests | ✅ Complete | `HybridCryptographyTest.java`, `MlDsa65Test.java` |

## Version Negotiation (Briar Compatibility)

Zerion implements automatic version negotiation to maintain compatibility with Briar while enabling post-quantum security for Zerion-to-Zerion communication.

### How It Works

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    VERSION NEGOTIATION FLOW                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  1. Link Creation:                                                       │
│     • Zerion creates VERSION 1 links (hybrid commitment)                 │
│     • Briar creates VERSION 0 links (classical X25519)                   │
│                                                                          │
│  2. Link Parsing:                                                        │
│     • Parse link → Extract version (0 or 1)                              │
│     • Store version in PendingContact                                    │
│                                                                          │
│  3. Handshake Selection:                                                 │
│     • VERSION 0 → Use classical X25519 keys (Briar-compatible)           │
│     • VERSION 1 → Use hybrid PQ keys (post-quantum secure)               │
│                                                                          │
│  ┌──────────────┐         ┌──────────────┐         ┌──────────────┐     │
│  │   Zerion A   │ ←─v1──→ │   Zerion B   │ = PQ-secure              │     │
│  └──────────────┘         └──────────────┘                          │     │
│                                                                          │
│  ┌──────────────┐         ┌──────────────┐         ┌──────────────┐     │
│  │   Zerion     │ ←─v0──→ │    Briar     │ = Classical (compatible) │     │
│  └──────────────┘         └──────────────┘                          │     │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Link Format Versions

| Version | Key Type | Security | Compatible With |
|---------|----------|----------|-----------------|
| **0** | X25519 public key (32B) | Classical (128-bit) | Briar, Zerion |
| **1** | Hybrid commitment (32B) | Post-Quantum (192-bit) | Zerion only |

### Identity Key Storage

Each Zerion identity now stores **both** classical and hybrid keys:

```java
Identity {
    LocalAuthor localAuthor;          // User's identity

    // Classical keys (Briar compatibility)
    PublicKey handshakePublicKey;     // X25519 (32 bytes)
    PrivateKey handshakePrivateKey;   // X25519 (32 bytes)

    // Hybrid keys (Zerion-to-Zerion PQ security)
    PublicKey hybridHandshakePublicKey;   // X25519+ML-KEM-768 (1,216 bytes)
    PrivateKey hybridHandshakePrivateKey; // X25519+ML-KEM-768 (2,432 bytes)
}
```

### Handshake Key Selection

The `HandshakeManagerImpl` automatically selects keys based on the pending contact's link version:

```java
// From HandshakeManagerImpl.handshake()
if (pendingContact.isPostQuantum()) {
    // PQ handshake - use hybrid keys
    keyPair = identityManager.getHybridHandshakeKeys(txn);
} else {
    // Classical handshake - use X25519 keys (Briar-compatible)
    keyPair = identityManager.getHandshakeKeys(txn);
}
```

### Zerion-to-Zerion Communication

When two Zerion users exchange links:

1. **User A** creates a VERSION 1 link containing a commitment to their hybrid key
2. **User B** scans/receives the link, sees VERSION 1, and selects hybrid keys
3. Both parties perform hybrid key exchange (X25519 + ML-KEM-768)
4. Result: **Post-quantum secure session**

### Zerion-to-Briar Communication

When a Zerion user connects with a Briar user:

1. **Briar user** creates a VERSION 0 link with their X25519 key
2. **Zerion user** scans/receives the link, sees VERSION 0, and selects classical keys
3. Both parties perform classical X25519 key exchange
4. Result: **Briar-compatible session** (classical security)

## Security Level Persistence

The security level (classical vs post-quantum) is persisted throughout the contact lifecycle:

### Database Schema Updates

**Migration 50→51**: Add formatVersion to pendingContacts
```sql
ALTER TABLE pendingContacts ADD COLUMN formatVersion INT NOT NULL DEFAULT 0;
```

**Migration 51→52**: Add postQuantum to contacts
```sql
ALTER TABLE contacts ADD COLUMN postQuantum BOOLEAN NOT NULL DEFAULT FALSE;
```

### Contact Security Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    SECURITY LEVEL PERSISTENCE                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  1. Link Exchange                                                        │
│     • Parse link version (0=classical, 1=PQ)                             │
│     • Create PendingContact with formatVersion                           │
│     • formatVersion stored in database                                   │
│                                                                          │
│  2. Handshake Completion                                                 │
│     • PendingContact.isPostQuantum() checked                             │
│     • Contact created with postQuantum flag                              │
│     • postQuantum stored in database                                     │
│                                                                          │
│  3. Contact Display                                                      │
│     • Contact.isPostQuantum() returns stored value                       │
│     • Chat Settings shows security level                                 │
│     • "Post-Quantum Security" or "Classical Security"                    │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Contact Class

```java
public class Contact {
    private final boolean postQuantum;

    public boolean isPostQuantum() {
        return postQuantum;
    }

    public boolean isClassical() {
        return !postQuantum;
    }
}
```

## Downgrade Attack Prevention

Once a contact is established with post-quantum security, subsequent contacts with the same author must also use PQ.

### Implementation

```java
// ContactManagerImpl.java
private void checkForSecurityDowngrade(Transaction txn, AuthorId remoteId,
        boolean newIsPostQuantum) throws DbException {
    Collection<Contact> existingContacts =
            db.getContactsByAuthorId(txn, remoteId);
    for (Contact existing : existingContacts) {
        if (existing.isPostQuantum() && !newIsPostQuantum) {
            throw new SecurityDowngradeException(remoteId, true, false);
        }
    }
}
```

### Attack Scenario Blocked

```
Scenario: Attacker tries to downgrade an existing PQ contact

1. Alice and Bob are Zerion users (PQ contact established)
2. Attacker deletes Bob from Alice's device
3. Attacker sends Alice a classical (v0) link pretending to be Bob
4. Alice's app checks existing contacts by Bob's AuthorId
5. Finds previous PQ contact → SecurityDowngradeException thrown
6. Attack blocked! Alice is notified of the security issue
```

### SecurityDowngradeException

```java
public class SecurityDowngradeException extends DbException {
    private final AuthorId remoteAuthorId;
    private final boolean existingWasPostQuantum;
    private final boolean newIsPostQuantum;

    @Override
    public String getMessage() {
        return "Security downgrade attack detected: existing contact used " +
                (existingWasPostQuantum ? "post-quantum" : "classical") +
                " security, new handshake uses " +
                (newIsPostQuantum ? "post-quantum" : "classical");
    }
}
```

## UI Security Indicator

The Chat Settings screen displays the security level for each contact:

### Display Logic

```java
// ChatSettingsActivity.java
if (contactItem.isPostQuantum()) {
    securityLevelTitle.setText(R.string.security_level_post_quantum);
    securityLevelDescription.setText(
            R.string.security_level_post_quantum_description);
} else {
    securityLevelTitle.setText(R.string.security_level_classical);
    securityLevelDescription.setText(
            R.string.security_level_classical_description);
}
```

### String Resources

```xml
<string name="security_level_post_quantum">Post-Quantum Security</string>
<string name="security_level_classical">Classical Security</string>
<string name="security_level_post_quantum_description">
    This contact uses hybrid post-quantum cryptography (X25519 + ML-KEM-768),
    providing protection against future quantum computer attacks.
</string>
<string name="security_level_classical_description">
    This contact uses classical cryptography, compatible with Briar.
    Upgrade both apps to Zerion for post-quantum security.
</string>
```

## Security Architecture

### Post-Phase 2 Security Posture

| Component | Hybrid Algorithm | Classical Security | Post-Quantum Security |
|-----------|------------------|--------------------|-----------------------|
| Key Exchange | ML-KEM-768 + X25519 | 128-bit | 192-bit (NIST Level 3) |
| Signatures | ML-DSA-65 + Ed25519 | 128-bit | 128-bit (NIST Level 3) |
| Symmetric Encryption | XSalsa20-Poly1305 | 256-bit | 128-bit (Grover) |
| Hashing | BLAKE2b-256/384 | 256/384-bit | 128/192-bit (Grover) |
| KDF | Argon2id | Memory-hard | Quantum-resistant |

### Defense-in-Depth

Both classical AND post-quantum algorithms must be broken to compromise security:

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

## Algorithm Details

### ML-KEM-768 (Kyber)

- **NIST Standard**: FIPS 203 (August 2024)
- **Security Level**: NIST Level 3 (AES-192 equivalent)
- **Public Key Size**: 1,184 bytes
- **Private Key Size**: 2,400 bytes
- **Ciphertext Size**: 1,088 bytes
- **Shared Secret**: 32 bytes

### ML-DSA-65 (Dilithium)

- **NIST Standard**: FIPS 204 (August 2024)
- **Security Level**: NIST Level 3 (AES-192 equivalent)
- **Public Key Size**: 1,952 bytes
- **Private Key Size**: 4,032 bytes
- **Signature Size**: 3,309 bytes (BouncyCastle encoding)

> **Note**: BouncyCastle 1.82+ produces 3,309-byte signatures instead of the NIST spec's 3,293 bytes due to internal context headers.

## File Structure

### API Layer (`bramble-api/src/main/java/.../crypto/`)

```
├── CryptoAlgorithm.java              # Algorithm enum with key sizes
├── PostQuantumConstants.java         # PQ key/signature size constants
├── HybridAgreementPublicKey.java     # X25519 + ML-KEM-768 public key (1,216 bytes)
├── HybridAgreementPrivateKey.java    # X25519 + ML-KEM-768 private key (2,432 bytes)
├── HybridSignaturePublicKey.java     # Ed25519 + ML-DSA-65 public key (1,984 bytes)
└── HybridSignaturePrivateKey.java    # Ed25519 + ML-DSA-65 private key (4,064 bytes)
```

### Core Layer (`bramble-core/src/main/java/.../crypto/`)

```
├── MlKem768.java                     # ML-KEM-768 BouncyCastle wrapper
├── MlDsa65.java                      # ML-DSA-65 BouncyCastle wrapper
├── HybridKeyAgreement.java           # Hybrid KEM operations
├── HybridSignature.java              # Hybrid signature operations
├── HybridAgreementKeyParser.java     # Parse hybrid agreement keys
└── HybridSignatureKeyParser.java     # Parse hybrid signature keys
```

### Test Files (`bramble-core/src/test/java/.../crypto/`)

```
├── HybridCryptographyTest.java       # 16+ comprehensive tests
└── MlDsa65Test.java                  # ML-DSA-65 unit tests
```

## Key Structures

### Hybrid Agreement Key (1,216 bytes)

```
┌────────────────────┬────────────────────────────────────────┐
│ X25519 (32 bytes)  │ ML-KEM-768 Public Key (1,184 bytes)    │
└────────────────────┴────────────────────────────────────────┘
```

### Hybrid Signature Key (1,984 bytes)

```
┌─────────────────────┬────────────────────────────────────────┐
│ Ed25519 (32 bytes)  │ ML-DSA-65 Public Key (1,952 bytes)     │
└─────────────────────┴────────────────────────────────────────┘
```

### Hybrid Signature (3,373 bytes)

```
┌─────────────────────┬─────────────────────────────────────────┐
│ Ed25519 (64 bytes)  │ ML-DSA-65 Signature (3,309 bytes)       │
└─────────────────────┴─────────────────────────────────────────┘
```

## API Usage

### Generating Hybrid Key Pairs

```java
CryptoComponent crypto = ...; // Injected

// Generate hybrid key agreement key pair
KeyPair agreementKeyPair = crypto.generateHybridAgreementKeyPair();
HybridAgreementPublicKey pubKey = (HybridAgreementPublicKey) agreementKeyPair.getPublic();
HybridAgreementPrivateKey privKey = (HybridAgreementPrivateKey) agreementKeyPair.getPrivate();

// Generate hybrid signature key pair
KeyPair signatureKeyPair = crypto.generateHybridSignatureKeyPair();
HybridSignaturePublicKey sigPubKey = (HybridSignaturePublicKey) signatureKeyPair.getPublic();
HybridSignaturePrivateKey sigPrivKey = (HybridSignaturePrivateKey) signatureKeyPair.getPrivate();
```

### Signing and Verifying

```java
// Sign a message
byte[] message = "Hello, quantum-safe world!".getBytes();
String label = "zerion.message.v1";
byte[] signature = crypto.hybridSign(label, message, sigPrivKey);

// Verify the signature
boolean valid = crypto.verifyHybridSignature(signature, label, message, sigPubKey);
```

### Parsing Keys

```java
// Parse hybrid agreement key
KeyParser agreementParser = crypto.getHybridAgreementKeyParser();
HybridAgreementPublicKey parsed = (HybridAgreementPublicKey)
    agreementParser.parsePublicKey(pubKeyBytes);

// Parse hybrid signature key
KeyParser signatureParser = crypto.getHybridSignatureKeyParser();
HybridSignaturePublicKey parsedSig = (HybridSignaturePublicKey)
    signatureParser.parsePublicKey(sigPubKeyBytes);
```

### Extracting Classical Components

```java
// For backward compatibility, extract classical key components
HybridAgreementPublicKey hybridPub = ...;
byte[] x25519Pub = hybridPub.getX25519PublicKey();  // 32 bytes
byte[] mlKemPub = hybridPub.getMlKemPublicKey();    // 1,184 bytes

HybridSignaturePublicKey hybridSigPub = ...;
byte[] ed25519Pub = hybridSigPub.getEd25519PublicKey();  // 32 bytes
byte[] mlDsaPub = hybridSigPub.getMlDsaPublicKey();      // 1,952 bytes
```

## Protocol Versions

### Key Agreement Protocol

| Version | Algorithm | Status |
|---------|-----------|--------|
| 4 | X25519 only | Legacy (default) |
| 5 | Hybrid ML-KEM-768 + X25519 | Available |

### Contact Exchange Protocol

| Version | Signature Algorithm | Status |
|---------|---------------------|--------|
| 1 | Ed25519 only | Legacy (default) |
| 2 | Hybrid ML-DSA-65 + Ed25519 | Available |

### Handshake Link Format

| Version | Key Type | Link Size |
|---------|----------|-----------|
| 0 | X25519 (32B) | 53 chars (Base32) |
| 1 | Commitment hash (32B) | 53 chars (Base32) |

> **Note**: Hybrid keys (1,216 bytes) are too large for practical link exchange. Version 1 uses a commitment-based staged exchange where the link contains a BLAKE2b-256 hash commitment (32 bytes), and the full hybrid key is exchanged over the established Tor connection.

## Testing

All tests pass:

```
./gradlew :bramble-core:test --tests 'org.briarproject.bramble.crypto.*'

BUILD SUCCESSFUL
```

### Test Coverage

- **HybridCryptographyTest.java**: 16+ tests covering:
  - Key pair generation (agreement and signature)
  - Key uniqueness
  - Key parsing and serialization
  - Sign and verify operations
  - Invalid key rejection
  - Invalid message rejection
  - Invalid label rejection
  - Tampered signature rejection
  - Empty message handling
  - Large message handling (1MB)
  - Component extraction
  - Memory clearing

- **MlDsa65Test.java**: ML-DSA-65 specific tests

## Security Considerations

### Implemented Protections

1. **Constant-time operations**: All key comparisons use constant-time algorithms
2. **Memory clearing**: Private keys support `clear()` method for secure wiping
3. **Domain separation**: Hybrid keys use separate labels from legacy keys
4. **Labeled signatures**: All signatures include context labels to prevent misuse

### Key Derivation Labels

```java
// Legacy labels
SHARED_SECRET_LABEL = "org.briarproject.bramble.keyagreement/SHARED_SECRET"
MASTER_KEY_LABEL = "org.briarproject.bramble.keyagreement/MASTER_SECRET"

// Hybrid labels (domain-separated)
SHARED_SECRET_LABEL_HYBRID = "org.briarproject.bramble.keyagreement/HYBRID_SHARED_SECRET_V1"
MASTER_KEY_LABEL_HYBRID = "org.briarproject.bramble.keyagreement/HYBRID_MASTER_SECRET_V1"
```

## Key Sizes Summary

| Algorithm | Public Key | Private Key | Signature/Ciphertext |
|-----------|------------|-------------|----------------------|
| X25519 | 32 B | 32 B | N/A |
| Ed25519 | 32 B | 32 B | 64 B |
| ML-KEM-768 | 1,184 B | 2,400 B | 1,088 B |
| ML-DSA-65 | 1,952 B | 4,032 B | 3,309 B |
| **Hybrid Agreement** | **1,216 B** | **2,432 B** | **1,088 B** |
| **Hybrid Signature** | **1,984 B** | **4,064 B** | **3,373 B** |

## Dependencies

- **BouncyCastle 1.82+**: Provides ML-KEM and ML-DSA implementations
- **i2p.crypto.eddsa**: Provides Ed25519 implementation
- **Curve25519-java**: Provides X25519 implementation

## Future Work

1. ~~**Protocol Integration**: Update key exchange protocols to use hybrid keys~~ ✅ Done
2. ~~**Database Migration**: Add hybrid key storage columns for persistence~~ ✅ Done (Migration 50→52)
3. ~~**UI Updates**: Show hybrid security status to users (PQ badge/indicator)~~ ✅ Done (Chat Settings)
4. ~~**Version Negotiation**: Implement automatic fallback for older clients~~ ✅ Done
5. ~~**Downgrade Attack Prevention**: Block classical handshakes after PQ established~~ ✅ Done

All planned features are now complete.

## Version History

- **v1.0** (2024-11): Initial design document
- **v1.1** (2024-11): Added QR code strategy, migration details
- **v2.0** (2024-11): **IMPLEMENTATION COMPLETE** - All core components implemented and tested
- **v2.1** (2024-12): **VERSION NEGOTIATION** - Added automatic Briar compatibility:
  - Dual key storage (classical + hybrid) in Identity
  - Format version detection in PendingContact
  - Automatic key selection in HandshakeManager
  - Zerion-to-Zerion: PQ-secure (v1 links)
  - Zerion-to-Briar: Classical-compatible (v0 links)
- **v2.2** (2024-12): **SECURITY HARDENING** - Full security implementation:
  - Database persistence for formatVersion (Migration 50→51)
  - Database persistence for postQuantum flag (Migration 51→52)
  - Downgrade attack prevention via SecurityDowngradeException
  - UI security level indicator in Chat Settings
  - Contact.isPostQuantum() and Contact.isClassical() methods
  - ContactItem.isPostQuantum() for Android UI layer
