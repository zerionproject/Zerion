# Zerion Security Overview

**Version:** 1.1.0
**Last reviewed:** May 2026 (v1.6)

---

## Summary

Zerion is a peer-to-peer encrypted messenger for Android with voice calls over Tor. All traffic routes exclusively through the Tor network. There are no central servers, no metadata collection, and no logging in production builds.

A comprehensive security audit covering 10 domains (cryptography, network, voice calls, Android platform, authentication, database, input validation, dependencies, logging, memory safety) was completed and all actionable findings have been resolved. An additional internal audit was run during the v1.6 development cycle focused on the PCS Mode 3 rewrite and the hybrid-signing migration; four findings (one critical, one high, two medium) were caught and patched before tag — see the v1.6 audit notes below.

## v1.6 status (May 2026)

- **PCS Mode 3 post-quantum ratchet** now actually completes epochs end-to-end. Phase 4d (January 2026) shipped Mode 3 framing on the wire but three latent bugs prevented any PQ epoch from completing: responder chunk-type dispatch missing, responder shared secret wiped before use, factory state callbacks `null` in production. All three fixed in v1.6, plus cross-direction PQ mixing per epoch (one epoch now PQ-protects both directions, was one-direction-only in Phase 4d), self-heal on crash + stuck-state recovery, pubkey-comparison tiebreak on simultaneous epoch starts. ML-KEM-768 shared secret is now mixed into the root key every 25 messages or 24 hours, both directions, persisted across reconnects.
- **Hybrid identity signatures on group records.** Every group record (GROUP_POST, MEMBER_ADDED/REMOVED/LEFT, DISSOLVED, EPOCH_COMMIT, ROLE_CHANGED, MEMBER_LIST_SNAPSHOT) now carries a hybrid Ed25519 + ML-DSA-65 signature (3,373 bytes). CONTACT_INFO bumped to 6-slot to carry the peer's ML-DSA-65 public key. Database schema migration v62 → v63 adds nullable ML-DSA columns on `localAuthors` and `contacts`; existing accounts lazy-backfill an ML-DSA-65 keypair on first sign-in. AuthorId stays stable.
- **Vault Argon2id alignment.** The vault password KDF was internally PBKDF2-HMAC-SHA256 in earlier releases (acknowledged placeholder). v1.6 uses real Argon2id via Bouncy Castle (same generator as the database KDF). Legacy vaults still readable via a feature-flag bit in the vault header. Export bundle format bumped to v2.
- **v1.6 audit findings (patched before tag):** GROUP_POST hybrid signature was only verified at Ed25519-prefix level by the validator and never re-verified at the manager layer (critical, fixed by passing recordSig in GroupPostReceivedEvent and re-verifying in cachePost); GROUP_MEMBER_LEFT same pattern, now verified at manager layer; TOCTOU race in cross-direction PQ mixing closed via atomic single-transaction `PcsStateManager.mixPqSecretInto*Root`; ML-KEM shared secret zeroed immediately after clone in `deriveEpochSecret`.

---

## Encryption

| Layer | Algorithm | Details |
|---|---|---|
| Transport | XSalsa20-Poly1305 | Bramble transport encryption with proper nonce construction |
| Messages | AES-256-GCM | End-to-end encrypted via Bramble sync protocol |
| Voice frames | AES-256-GCM | Per-frame authenticated encryption with counter-based nonces |
| Voice key wrap | AES-256 CTR | IES implementation (migrated from CBC to CTR) |
| Key exchange | X25519 + ML-KEM-768 | Hybrid post-quantum key exchange |
| Signatures | Ed25519 + ML-DSA-65 | Hybrid post-quantum signatures |
| Database | H2 with AES cipher | Local database encrypted at rest |
| Vault | Argon2id + AES-256-GCM | Encrypted file storage with memory-hard KDF |
| KDF (passwords) | Argon2id / scrypt | Password-based key derivation |

## Voice Call Security

- Peer-to-peer audio over Tor hidden services (no servers)
- Forward secrecy via ephemeral secret exchange (HMAC-based key derivation)
- Replay protection with monotonic sequence numbers
- CRC32 integrity check per frame before decryption
- Keys passed via in-memory holder (never via Android Intent extras)
- Counter-based nonces with 1000-gap advance on reconnection
- All key material zeroed on call end (keys, jitter buffer, contact name)

## Network

- Tor-only transport (Bluetooth, LAN, clearnet TCP all disabled)
- No clearnet HTTP requests anywhere in the app
- UPnP multicast discovery disabled
- Network security config blocks all cleartext traffic
- Tor hidden service keys stored in app-private directory

## Authentication

- Password-based login with Argon2id key derivation
- Minimum 8-character password enforced
- Persistent brute-force lockout (survives app restart)
- Account wipe after maximum failed attempts
- Android Keystore integration for hardware-backed key protection
- Constant-time comparison in key agreement verification

## Memory Safety

- Password API uses char[] throughout the entire chain (UI to KDF), zeroed after use
- SecretKey has explicit clear() method for key material destruction
- Audio encryption/decryption keys stored as raw byte[] and zeroed on cleanup
- Jitter buffer zeroed on call teardown
- Contact name cleared from memory on call end

## Android Platform

- FLAG_SECURE applied globally (screenshot/screen recording prevention)
- Incognito keyboard enforced on all text inputs (no predictive text, no learning)
- No analytics, tracking, or advertising SDKs
- Backup disabled with comprehensive exclusion rules
- Notifications use VISIBILITY_SECRET (no metadata on lock screen)
- No dynamic code loading in production paths

## Logging Policy

- No logging in production builds
- No Logger, no android.util.Log, no System.out/err in production code
- Debug logging gated by BuildConfig.DEBUG compile-time constant
- Crash reports exclude message content and contact identifiers

## Dependencies

All critical and high-severity dependency issues resolved:
- H2 Database updated to 2.2.224
- Jackson-databind updated to 2.15.3
- NanoHTTPD removed (unused, had known CVE)
- Keystore passwords moved to environment variables
- Tor binary from official Tor Project with hash verification

## Additional Documentation

- [Technical Whitepaper](ZERION_TECHNICAL_WHITEPAPER.md)
- [P2P Voice Calls](P2P_Voice_Calls_Documentation.md)
- [Triple Ratchet Design](TRIPLE_RATCHET_DESIGN.md)
- [Post-Compromise Security](PCS_DESIGN.md)
- [Group PCS Sender Keys](GROUP_PCS_SENDER_KEYS_DESIGN.md)
