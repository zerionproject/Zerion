# Zerion Security Overview

**Version:** 1.0.2
**Last reviewed:** February 2026

---

## Summary

Zerion is a peer-to-peer encrypted messenger for Android with voice calls over Tor. All traffic routes exclusively through the Tor network. There are no central servers, no metadata collection, and no logging in production builds.

A comprehensive security audit covering 10 domains (cryptography, network, voice calls, Android platform, authentication, database, input validation, dependencies, logging, memory safety) was completed and all actionable findings have been resolved.

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
