# Zerion Security Overview

**Version:** 2.0.7
**Last reviewed:** July 2026 (v2.0.7)

---

## Summary

Zerion is a peer-to-peer encrypted messenger for Android with voice and video calls over Tor. All traffic routes exclusively through the Tor network. There are no central servers, no metadata collection, and no logging in production builds.

We review the code ourselves before each release and fix what we find. Recent reviews covered the cryptography, networking, voice and video, the Android platform, authentication, the database, input handling, dependencies, logging, and memory safety. These are internal reviews, not a third-party audit. Additional internal audits were run during the v1.6 cycle: the PCS Mode 3 rewrite and the hybrid-signing migration produced four findings (one critical, one high, two medium) caught and patched before the v1.6.0 tag; a follow-up audit during the v1.6.2 cycle covered password setup, settings, vault, biometric, deletion paths, and lock-screen exposure (see the version notes below).

## v2.0.7 status (July 2026)

- **Decoy-calculator display fix.** Resolved a layout bug where the disguised
  calculator keypad could render blank in portrait on some narrower screens
  (reported on HyperOS and GrapheneOS). No protocol, cryptography, or database
  change; the signing key is unchanged.

## v2.0 / v2.0.6 status (July 2026)

- **Channels (broadcast publishing).** Channels are a publisher → subscriber
  broadcast surface. A channel is public or private; private channels are
  closed - the owner gates which contacts may subscribe, and subscribers
  never see one another (the subscriber list is non-disclosed to the
  membership). Every channel record - posts, owner-gated discussion-thread
  comments, reactions - carries a hybrid Ed25519 + ML-DSA-65 signature, so
  authorship and integrity are post-quantum-bound. Owners may delegate a
  bounded set of editors who can publish on the channel's behalf; editor
  delegations are themselves hybrid-signed records. v2.0.2 added in-app
  system notifications for new posts and new comments.
- **Hardened mode (opt-in, v2.0).** When enabled, Zerion refuses to start on
  a device that fails integrity checks: detected tamper/root/hook frameworks,
  an attached debugger, or USB debugging / file-transfer (MTP) left enabled.
  This is an anti-forensics posture aimed at physical-extraction tooling
  (Cellebrite, GrayKey and similar): the app declines to run in the exact
  environments those tools require.
- **Anti-forensics / extraction-tool resistance.** On sign-out the app wipes
  its caches so no plaintext working state survives a locked session. The
  clipboard auto-clears 60 seconds after any sensitive copy, limiting what a
  later extraction can recover.
- **In-tree EncryptedSharedPreferences (v2.0.2).** The deprecated AndroidX
  `security-crypto` library was removed and replaced with an in-tree
  EncryptedSharedPreferences implementation. Behaviour is unchanged from the
  caller's perspective - every preference read/write is still keystore-backed
  (master key in the Android Keystore, hardware-backed where available) - but
  the encryption layer is now maintained in the Zerion source tree rather than
  pinned to an unmaintained dependency.

## v1.7 status (May 2026)

- **Per-message post-quantum hybrid ratchet (Mode 3-Full) is the default
  on new 1:1 contacts.** Every transport frame carries a fresh ML-KEM-768
  encapsulation against the peer's currently advertised ML-KEM public
  key; the encapsulated shared secret is mixed into the per-frame body
  AEAD key via `HKDF(classicalMessageKey, ml_kem_shared_secret)`. The
  per-frame frame-header AEAD key remains the classical symmetric chain
  derivation. Sender rotates its ML-KEM keypair on every successful
  encapsulation; recent sender keypairs are retained in a per-contact
  LRU (cap 64) so peer ciphertexts against slightly stale public keys
  still decapsulate.
- **Per-stream chain key.** Each transport stream derives its own
  initial chain key from `HKDF(rootKey, PCS_STREAM_CHAIN,
  streamNumber_8B)` and advances it locally per frame; the chain key
  is never persisted across streams. This eliminates the parallel-stream
  desync that affected the prior shared-chainKey design and lets Briar
  transport open multiple parallel streams to the same contact without
  contention.
- **Lock-free transport I/O.** The per-contact lock protects in-memory
  Mode3FullState mutation only; the actual Tor I/O calls
  (`writeTag`, `writeStreamHeader`, `out.write`, `in.read`) run outside
  the lock so a slow Tor circuit on one direction never starves the
  other direction of the same contact.
- **Key zeroing on the ML-KEM hot path.** ML-KEM shared secrets are
  zeroed immediately after the body AEAD key derives from them, in both
  the encapsulation and decapsulation paths.
- **Pre-commit cryptographic audit.** The v1.7 cycle ran a focused audit
  on the per-message PQ ratchet covering key/nonce uniqueness, key
  zeroing, state machine integrity, error-path information leaks, and
  ML-KEM keypair LRU eviction. Findings before tag: H3 (zero ML-KEM
  shared secrets), H6 (narrow PQ-epoch catch), H1 (defer chain-key
  advancement until all per-frame MACs verify), L2 (remove dev-only
  validation harness), M1 (KpId defensive copy of bytes) - all patched
  before tag.

## v1.6.2 status (May 2026)

- **Native GroupTr invite protocol.** The legacy private-group invitation client (`org.briarproject.briar.privategroup.invitation`) is removed from the shipped APK. Group invitations now ride three native message types (`OFFER` 42 / `ACCEPT` 43 / `DECLINE` 44) on the existing 1:1 channel between sender and recipient. The invitation payload is a signed BDF dictionary carried inside the same Triple Ratchet envelope every other 1:1 message uses. One protocol now covers create, invite, join, role change, kick, leave, and dissolve.
- **Kick reliability.** Fixed an invitee-side epoch desync that silently dropped `MEMBER_REMOVED` records when the strict `toEpoch == localEpoch + 1` check failed because `applyMemberAdded` had short-circuited without bumping the local epoch. `applyMemberAdded` now updates the epoch even when the target is already a member; the `MEMBER_REMOVED` check is relaxed from strict-successor to monotonic. When the local user is removed, the group is purged atomically with applying the change. Same logic on `leaveGroup` and `dissolveGroup`.
- **Tor-only transport (final).** The last non-Tor transport code paths are removed: the Bluetooth plugin (assets, manifest entries, factory), the Wi-Fi LAN TCP plugin (discovery code, `ACCESS_WIFI_STATE` permission, factory), the removable-drive sync feature, and the dev-reporting/crash-batching subsystem. The plugin registry has exactly one entry: Tor v3 onion.
- **All `SharedPreferences` keystore-encrypted.** Every `SharedPreferences` read/write across the app is now routed through `EncryptedSharedPreferences` with a master key generated and held in the Android Keystore (hardware-backed where available, non-exportable, device-bound). The only exception is a small early-init store for the launcher theme and language - values needed before the application context is available - documented in the codebase.
- **Hybrid signatures extended.** The hybrid Ed25519 + ML-DSA-65 signing path introduced in v1.6.0 for group records is now applied to the private-group and private-group invitation contexts that still carried Ed25519-only signatures, closing the last legacy signing path.
- **Downgrade-lock token reconstruction.** Fixed a carry-forward bug in v1.6.0's downgrade-lock implementation where the token was reconstructed from the wrong field set during re-pair, allowing the lock to invalidate on a clean re-pair. Reconstruction is now canonical; the lock survives every re-pair on both sides.
- **v1.6.2 audit findings (patched before tag):** `sanitizePasswordChars` on the password-setup screen was zeroing the sanitized buffer in a `finally` block before the async Argon2 KDF completed, producing locked-out accounts on the next login - reverted, with ownership of the sanitized buffer transferred to `SetupViewModel.createAccount` which zeroes after the KDF returns. Vault/biometric/lock-screen exposure paths hardened: ephemeral file wipe on delete paths, widened notification-visibility flags (`VISIBILITY_SECRET`), zero-on-derive of handshake ephemeral private keys, supply-chain pin for `junit-bom-5.11.4` in dependency-verification metadata.
- **Diagnostic-logging policy enforced at build time.** Temporary `[grouptr][KICK]` traces added during kick-flow diagnosis are stripped before tag. The `enforceNoLogs` Gradle task fails the build on any `Logger`, `android.util.Log`, `LOG.*`, `System.err`, `System.out`, `printStackTrace`, or `Timber` reference in the production source tree.

## v1.6.0 status (May 2026)

- **PCS Mode 3 post-quantum ratchet** now actually completes epochs end-to-end. Phase 4d (January 2026) shipped Mode 3 framing on the wire but three latent bugs prevented any PQ epoch from completing: responder chunk-type dispatch missing, responder shared secret wiped before use, factory state callbacks `null` in production. All three fixed in v1.6, plus cross-direction PQ mixing per epoch (one epoch now PQ-protects both directions, was one-direction-only in Phase 4d), self-heal on crash + stuck-state recovery, pubkey-comparison tiebreak on simultaneous epoch starts. ML-KEM-768 shared secret is now mixed into the root key every 25 messages or 24 hours, both directions, persisted across reconnects.
- **Hybrid identity signatures on group records.** Every group record (GROUP_POST, MEMBER_ADDED/REMOVED/LEFT, DISSOLVED, EPOCH_COMMIT, ROLE_CHANGED, MEMBER_LIST_SNAPSHOT) now carries a hybrid Ed25519 + ML-DSA-65 signature (3,373 bytes). CONTACT_INFO bumped to 6-slot to carry the peer's ML-DSA-65 public key. Database schema migration v62 → v63 adds nullable ML-DSA columns on `localAuthors` and `contacts`; existing accounts lazy-backfill an ML-DSA-65 keypair on first sign-in. AuthorId stays stable.
- **Vault Argon2id alignment.** The vault password KDF was internally PBKDF2-HMAC-SHA256 in earlier releases (acknowledged placeholder). v1.6 uses real Argon2id via Bouncy Castle (same generator as the database KDF). Legacy vaults still readable via a feature-flag bit in the vault header. Export bundle format bumped to v2.
- **v1.6 audit findings (patched before tag):** GROUP_POST hybrid signature was only verified at Ed25519-prefix level by the validator and never re-verified at the manager layer (critical, fixed by passing recordSig in GroupPostReceivedEvent and re-verifying in cachePost); GROUP_MEMBER_LEFT same pattern, now verified at manager layer; TOCTOU race in cross-direction PQ mixing closed via atomic single-transaction `PcsStateManager.mixPqSecretInto*Root`; ML-KEM shared secret zeroed immediately after clone in `deriveEpochSecret`.

---

## Encryption

| Layer | Algorithm | Details |
|---|---|---|
| Transport AEAD | XSalsa20-Poly1305 | Per-frame authenticated encryption, 24-byte nonce |
| Per-message PQ ratchet | ML-KEM-768 (Mode 3-Full) | Fresh encapsulation against peer ML-KEM pubkey on every frame; shared secret mixed into body AEAD key via `HKDF(classicalMessageKey, ml_kem_shared_secret)`; sender rotates keypair per successful encapsulation |
| Chain key | Per-stream HKDF | Derived from `HKDF(rootKey, PCS_STREAM_CHAIN, streamNumber_8B)`, advanced locally per frame within the stream |
| Classical ratchet | X25519 Double Ratchet | Underlies Mode 3-Full; provides classical post-compromise security |
| Voice frames | AES-256-GCM | Per-frame authenticated encryption with counter-based nonces |
| Video frames | AES-256-GCM | Per-frame authenticated encryption; frames padded to a fixed size to defeat frame-size analysis |
| Voice key wrap | AES-256 CTR | IES implementation (migrated from CBC to CTR) |
| Contact handshake | X25519 + ML-KEM-768 | Hybrid post-quantum key exchange (B.4) |
| Introductions | X25519 + ML-KEM-768 | Hybrid PQ KEM at introduction time (Phase 5b) |
| Signatures | Ed25519 + ML-DSA-65 | Hybrid post-quantum signatures (B.3) |
| Database | SQLCipher (AES-256-CBC per page + per-page HMAC) | Local database encrypted at rest (schema version 65) |
| Vault | Argon2id + AES-256-GCM | Encrypted file storage with memory-hard KDF |
| KDF (passwords) | Argon2id (active) | Password-based key derivation; scrypt is legacy only (read and auto-migrated to Argon2id, never used for new accounts) |

## Voice Call Security

- Peer-to-peer audio over Tor hidden services (no servers)
- Forward secrecy via ephemeral secret exchange (HMAC-based key derivation)
- Replay protection with monotonic sequence numbers
- CRC32 integrity check per frame before decryption
- Keys passed via in-memory holder (never via Android Intent extras)
- Counter-based nonces with 1000-gap advance on reconnection
- All key material zeroed on call end (keys, jitter buffer, contact name)

## Video Call Security

- Peer-to-peer video over the same Tor connection as voice (no servers)
- H.264 Main Profile Level 3.1, 640x480 at 24 fps
- AES-256-GCM per-frame authenticated encryption (same per-call key derivation as voice)
- Frames padded to a fixed size to defeat frame-size traffic analysis
- Adaptive controller steps quality down under load (15 fps / 250 kbps → 10 fps / 150 kbps → 5 fps / 80 kbps → off)
- `FLAG_SECURE` on the video call activity (screenshot/recording prevention)

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

- No logging in production builds - period
- No `Logger`, no `android.util.Log`, no `System.out`/`err`, no
  `printStackTrace`, no `Timber`, no `LOG.*` anywhere in the production
  source tree
- The `java.util.logging` root logger is silenced at process start
  before any code can attach a handler
- The `enforceNoLogs` Gradle task fails the build on any logging
  reference in the production tree, blocking the regression at CI level
- Crash reports are disabled - the dev-reporting subsystem was removed
  in v1.6.2

## Dependencies

All critical and high-severity dependency issues resolved:
- Local database is SQLCipher (AES-256-CBC per page + per-page HMAC); H2 is not used as the storage engine
- Jackson-databind updated to 2.15.3
- NanoHTTPD removed (unused, had known CVE)
- Keystore passwords moved to environment variables
- Tor binary from official Tor Project, bundled inside the APK as a native library and covered by Android's APK signature check at install time (no over-the-air binary downloads, no runtime fetch)

## Additional Documentation

- [Technical Whitepaper](ZERION_TECHNICAL_WHITEPAPER.md)
- [P2P Voice Calls](P2P_Voice_Calls_Documentation.md)
- [Triple Ratchet Design](TRIPLE_RATCHET_DESIGN.md)
- [Post-Compromise Security](PCS_DESIGN.md)
- [Group Triple Ratchet (PQ) Design](GROUP_TRIPLE_RATCHET_PQ_DESIGN.md)
- [Ratchet Modes](RATCHET_MODES.md)
- [GroupTr Wire Protocol](wire/GROUPTR_WIRE_PROTOCOL.md)
