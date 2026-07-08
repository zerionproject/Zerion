# ZERION SECURE MESSAGING APP - COMPREHENSIVE TECHNICAL WHITEPAPER

## Executive Summary

Zerion is an end-to-end encrypted, peer-to-peer secure messaging application for Android that provides anonymity through Tor integration and includes a built-in encrypted vault for secure file storage. Built on the Bramble protocol (originally developed by the Briar Project), Zerion independently expanded it with voice calls over Tor, encrypted vault storage, and post-quantum cryptography. The application emphasizes privacy, security, and metadata protection.

**Post-Quantum Security**: Zerion implements **full hybrid post-quantum cryptography on every message** using NIST-standardized algorithms (ML-KEM-768 + X25519 for key exchange and the per-message transport ratchet, ML-DSA-65 + Ed25519 for signatures), providing defense-in-depth protection against both current and future quantum computing threats — including "harvest now, decrypt later" attacks. Since v1.7, every transport frame in both directions carries a fresh ML-KEM-768 encapsulation; the encapsulated shared secret is mixed into the per-frame body AEAD key via HKDF, producing a hybrid key that is secure as long as either X25519 or ML-KEM-768 is secure.

**Current release**: v2.0.6 (versionCode 20006, July 2026). For an at-a-glance summary of everything shipped since v2.1 of this whitepaper (December 2025), see [§0 Updates since v2.1](#0-updates-since-v21).

---

## TABLE OF CONTENTS

0. [Updates since v2.1](#0-updates-since-v21)
1. [Onboarding & Account Setup](#1-onboarding--account-setup)
2. [Messaging Architecture](#2-messaging-architecture) — includes §2.5 Channels (publisher → subscriber broadcast)
3. [Tor Integration](#3-tor-integration)
4. [End-to-End Encryption](#4-end-to-end-encryption)
5. [Post-Compromise Security](#5-post-compromise-security)
6. [Vault Feature - Secure File Storage](#6-vault-feature---secure-file-storage)
7. [Contact Discovery & Addition](#7-contact-discovery--addition)
8. [P2P Voice Calling](#8-p2p-voice-calling)
9. [Data Storage Security](#9-data-storage-security)
10. [Feature Highlights](#10-feature-highlights)
11. [Security Properties](#11-security-properties) — includes §11.4 Hardened Mode
12. [Technical Specifications](#12-technical-specifications)
13. [Architecture Diagrams](#13-architecture-diagrams)
14. [File Path Reference](#14-file-path-reference)
15. [Conclusion](#15-conclusion)

---

## 0. UPDATES SINCE v2.1

This section summarizes every protocol- or security-relevant change shipped between whitepaper v2.1 (December 16, 2025) and the current document version (July 2026, app release v2.0.6 / versionCode 20006). The rest of the document remains structurally correct; this section is the authoritative diff. Detailed designs live in the companion documents listed under each item.

### v1.5 (May 1, 2026) — B.3 pairing & B.4 onion rotation

- **B.3 — Hybrid post-quantum pairing.** The contact-add handshake now performs ML-KEM-768 alongside the existing X25519 KEM and binds the resulting hybrid shared secret to the peer's hybrid identity (Ed25519 + ML-DSA-65). See [docs/wire/B3_RECORD_PLACEMENT.md](wire/B3_RECORD_PLACEMENT.md) and [docs/wire/F2_INTRODUCTION_HYBRID_SIG.md](wire/F2_INTRODUCTION_HYBRID_SIG.md). Downgrade defense: once a contact is paired with the hybrid protocol, a re-pair attempt offering only the classical protocol is rejected.
- **B.4 — Onion rotation.** Tor v3 onion addresses now rotate every 5–14 days (uniform-randomized per rotation) to defeat long-term linkability of an account to a single onion. The onion-management API lives in the upstream `onionwrapper` fork — see [docs/wire/B4_ONIONWRAPPER_API.md](wire/B4_ONIONWRAPPER_API.md).

### v1.6.0 (May 11, 2026) — Mode 3 PQ rotation completes; group records hybrid-signed

- **PCS Mode 3 actually completes end-to-end.** Phase 4d (January 2026) shipped Mode 3 framing on the wire (the `0x2000` stream-header flag, the per-frame PCS header with `FLAG_PQ_ENABLED`, the chunk codec, the state machine) but three latent bugs prevented any post-quantum epoch from completing in production:
  1. The responder's stream-decrypter had no chunk-type dispatch — peer `EK_SEED` chunks were silently absorbed in `PQ_READY` instead of transitioning into `PQ_RECEIVING_EK_VEC`.
  2. The responder cloned its ML-KEM-768 ciphertext and immediately wiped the resulting shared secret, then tried to recover the secret at derive-time by re-encapsulating. Bouncy Castle's ML-KEM generator is randomized per call, so re-encapsulation produced a different ciphertext and the equality check failed.
  3. `StreamEncrypterFactoryImpl` and `StreamDecrypterFactoryImpl` passed `null` for their state callbacks. Every Mode 2 DH advance and every Mode 3 PQ-epoch completion was computed in memory and discarded on stream close.

  v1.6.0 fixes all three, plus adds cross-direction PQ mixing (one epoch now PQ-protects *both* directions atomically, not just the side that supplied the ciphertext), a self-heal path on database load that resets transient states to `PQ_READY` so a peer crash mid-epoch recovers on the next cycle, and a pubkey-comparison tiebreak for simultaneous-start races. ML-KEM-768 is now actually mixed into the root key every 25 messages or 24 hours, both directions, persisted across reconnects. See [PCS_DESIGN.md §v1.6 amendment](PCS_DESIGN.md).
- **Hybrid identity signatures on every group record.** `GROUP_POST`, `GROUP_MEMBER_ADDED` / `REMOVED` / `LEFT`, `GROUP_DISSOLVED`, `GROUP_EPOCH_COMMIT`, `GROUP_MEMBER_ROLE_CHANGED`, `GROUP_MEMBER_LIST_SNAPSHOT` now carry an Ed25519 + ML-DSA-65 hybrid signature (3,373 bytes: 64 bytes Ed25519 + 3,309 bytes ML-DSA-65). The local identity carries a new ML-DSA-65 keypair alongside the existing Ed25519 keypair; the AuthorId (derived from the Ed25519 public key) is unchanged. See [GROUP_TRIPLE_RATCHET_PQ_DESIGN.md](GROUP_TRIPLE_RATCHET_PQ_DESIGN.md).
- **`CONTACT_INFO` 6-slot extension.** Slot[5] carries the peer's ML-DSA-65 public key (1,952 bytes). Persisted on the `contacts` row. Lookup is cached by Ed25519-hex key with `ContactAddedEvent` / `ContactRemovedEvent` invalidation. New pairings exchange the ML-DSA half at handshake time; existing contacts paired before v1.6.0 fall back to Ed25519-prefix verification (no regression vs. v1.5) until both sides re-add.
- **Vault — real Argon2id.** The vault password KDF was internally PBKDF2-HMAC-SHA256 in earlier releases (the class was called `Argon2`, with an acknowledged "placeholder pending future migration" comment). v1.6.0 routes the vault through the same Bouncy Castle `Argon2BytesGenerator` (`ARGON2_id` mode, 256 MB memory, 3 iterations) used by the database KDF. Existing vaults remain readable via an algorithm flag in the vault header; the export bundle format is bumped to v2. Changing the vault password migrates a legacy vault to Argon2id on the next save.
- **Database schema v62 → v63.** Three nullable columns added: two on `localAuthors` (`mlDsaPublicKey`, `mlDsaPrivateKey`) and one on `contacts` (`mlDsaPublicKey`). Lazy-backfill: the first time an existing account opens on v1.6.0, the identity manager generates an ML-DSA-65 keypair if one isn't present and persists it. No existing data is migrated or invalidated.
- **v1.6.0 audit findings patched before tag.** Critical: GROUP_POST hybrid signature was only verified at the Ed25519-prefix level by the validator and never re-verified at the manager layer; fixed by carrying `recordSig` in `GroupPostReceivedEvent` and re-verifying in `cachePost`. High: TOCTOU race in cross-direction PQ mixing closed via single-transaction `PcsStateManager.mixPqSecretInto*Root`. Medium: GROUP_MEMBER_LEFT same pattern as GROUP_POST, now verified at manager layer; ML-KEM shared secret zeroed immediately after clone in `deriveEpochSecret`.

### v1.6.1 (May 13, 2026) — Whole-app security audit + GroupTr hardening

- Whole-app security audit fixes (vault, biometric, settings, password setup, deletion paths, lock-screen exposure).
- GroupTr security hardening: epoch monotonicity, creator-only writes on `MEMBER_ADDED` / `MEMBER_REMOVED` / `MEMBER_ROLE_CHANGED`, cleanup of pending invite-state on accept / decline.
- `addMember` now sends to the target peer; receive auto-applies when target is self.

### v1.6.2 (May 15, 2026) — Native group invites, Tor-only transport, at-rest hardening

- **Native GroupTr invite protocol.** The legacy `org.briarproject.briar.privategroup.invitation` client is removed from the shipped APK. Group invitations now use three native message types on the existing 1:1 channel between sender and recipient: `OFFER` (msgType 42), `ACCEPT` (43), `DECLINE` (44). The invite payload is a signed BDF dictionary carried inside the same Triple Ratchet envelope every other 1:1 message uses. One protocol now covers create, invite, join, role change, kick, leave, and dissolve. Wire format: [docs/wire/GROUPTR_WIRE_PROTOCOL.md](wire/GROUPTR_WIRE_PROTOCOL.md).
- **Kick reliability.** Fixed an invitee-side epoch desync that silently dropped `MEMBER_REMOVED` records when the strict `toEpoch == localEpoch + 1` check failed because `applyMemberAdded` had short-circuited without bumping the local epoch. `applyMemberAdded` now updates the epoch unconditionally; the `MEMBER_REMOVED` check is relaxed from strict-successor to monotonic. When the local user is removed, the group is purged from the local device atomically with applying the change. Same logic on `leaveGroup` / `dissolveGroup`.
- **Tor-only transport (final).** The last non-Tor transport code paths are removed: the Bluetooth plugin (assets, manifest entries, factory class), the Wi-Fi LAN TCP plugin (discovery code, `ACCESS_WIFI_STATE` permission, factory class), the removable-drive sync feature subtree, and the dev-reporting / crash-batching subsystem. The plugin registry has exactly one entry: Tor v3 onion. Manifest no longer requests `BLUETOOTH*` or `ACCESS_WIFI_STATE`.
- **All `SharedPreferences` keystore-encrypted.** Every `SharedPreferences` read and write across the app is routed through `EncryptedSharedPreferences` with a master key generated and held in the Android Keystore (hardware-backed where available, non-exportable, device-bound). The only exception is a small early-init store for the launcher theme and language — values needed before the application context is available — documented in the codebase.
- **Hybrid signatures extended.** The Ed25519 + ML-DSA-65 hybrid signing path introduced in v1.6.0 for group records is now applied to the private-group and private-group invitation contexts that still carried Ed25519-only signatures, closing the last legacy signing path.
- **Downgrade-lock token reconstruction.** v1.6.0's implementation reconstructed the downgrade-lock token from the wrong field set during a carry-forward re-pair, which would invalidate the lock on a clean re-pair. v1.6.2 reconstructs from the canonical input; the lock survives every re-pair on both sides.
- **Supply chain.** `junit-bom-5.11.4` is now pinned by SHA-256 in `gradle/verification-metadata.xml`. Gradle dependency locking is enabled across all production modules.

### Discontinued / removed

- **Legacy private-group invitation carrier** (`org.briarproject.briar.privategroup.invitation`) — removed in v1.6.2.
- **Bluetooth transport plugin** — removed in v1.6.2.
- **Wi-Fi LAN TCP transport plugin** — removed in v1.6.2.
- **Removable-drive sync feature** — removed in v1.6.2.
- **Dev-reporting / crash-batching subsystem** — removed in v1.6.2.

These removals are intentional and final; Zerion's threat model treats every additional transport as additional metadata surface and every additional reporting channel as a phone-home vector.

### v1.7 (May 2026) — Per-message ML-KEM-768 hybrid ratchet (Mode 3-Full) shipped

- **Mode 3-Full is the default on new 1:1 contacts.** Every transport
  frame in both directions carries a fresh ML-KEM-768 encapsulation
  against the peer's currently advertised ML-KEM public key. The
  encapsulated shared secret is mixed into the per-frame body AEAD key
  via `bodyKey = HKDF(classicalMessageKey, ml_kem_shared_secret)`.
  Sender rotates its own ML-KEM keypair on every successful
  encapsulation and advertises the freshly generated public key in the
  same frame; recent sender keypairs are retained in a per-contact LRU
  (cap 64) so peer ciphertexts against slightly stale public keys still
  decapsulate cleanly. A 16-byte `kpId` (truncated SHA-256 of the
  encapsulation key) in the frame header identifies which keypair the
  peer encapsulated against.
- **Per-stream chain key.** Each transport stream derives its own
  initial chain key from `HKDF(rootKey, "PCS_STREAM_CHAIN",
  streamNumber_8B)` and advances it locally per frame within the
  stream. The chain key is never persisted across streams. This
  eliminates the parallel-stream desync that constrained the prior
  shared-chainKey design and lets Briar transport open multiple
  concurrent streams to the same contact without contention.
- **Lock-free transport I/O.** The per-contact lock (now bound as a
  `@Singleton` in the Dagger graph so all consumers share one
  instance) protects in-memory `Mode3FullState` mutation only. Blocking
  Tor I/O calls (`writeTag`, `writeStreamHeader`, `out.write`,
  `in.read`) run outside the lock so a slow Tor circuit on one
  direction never starves the other direction of the same contact.
- **Key zeroing on the ML-KEM hot path.** ML-KEM shared secrets are
  zeroed immediately after the body AEAD key derives from them, on
  both the encapsulation and decapsulation sides.
- **Pre-commit cryptographic audit.** The v1.7 cycle ran a focused
  audit on the per-message PQ ratchet covering key/nonce uniqueness,
  key zeroing, state machine integrity, error-path information leaks,
  and ML-KEM keypair LRU eviction. Findings before tag: zero ML-KEM
  shared secrets after derivation (H3), narrow the bare PQ-epoch
  exception catch (H6), defer chain-key advancement until all
  per-frame MACs verify (H1), remove the dev-only validation harness
  (L2), KpId defensive copy of bytes (M1) — all patched before tag.
  Detailed design and audit results live in
  [PCS_DESIGN.md §v1.7 amendment](PCS_DESIGN.md).
- **Symmetric AEAD unchanged.** Transport AEAD remains XSalsa20-Poly1305
  (24-byte nonce, 16-byte Poly1305 MAC). The Bramble transport framing
  is built around those parameters; PQ defense lives in the per-message
  ML-KEM encapsulation, not in the symmetric primitive.

### v2.0 (June 2026) — Channels, Hardened Mode, at-rest forensic-defense tightening

- **Channels.** A new top-level messaging modality: publisher → subscriber broadcast with optional discussion threads. Public channels are joinable from an invite link; closed channels require explicit publisher approval. Posts are signed under the publisher's hybrid Ed25519+ML-DSA-65 identity and pulled by subscribers from the publisher's own Tor v3 onion (no third party, no Briar `PrivateGroup` client, no central server). Subscribers pull every ~5 seconds when foregrounded. Editor delegations let the publisher authorize co-publishers without sharing the publisher private key. See the new [§2.5 Channels](#25-channels--publisher--subscriber-broadcast) for the full design.
- **Channel discussion threads (Telegram-style).** Subscribers can post comments under any channel post when the publisher has enabled discussions. The publisher gates the comment-request handler on its own boolean preference (stored encrypted via `ChannelDiscussionStore`) and propagates the gate to the UI; flipping it OFF immediately hides the comment composer on every subscriber's next poll. Comments are dual-signed (Ed25519 + ML-DSA-65) and carried as a separate wire type.
- **Channel-pull replay protection.** The publisher now keeps a per-channel ring buffer of seen `(channelId, nonce)` pairs with a 5-minute TTL and a 4,096-entry bound, and rejects any pull request whose nonce is replayed. Prior versions accepted any `(nonce, HMAC)` pair indefinitely — a captured request could be replayed forever, useful for traffic analysis against the publisher's hidden service and for harvesting the wrapped content-key envelope after capability rotation.
- **Closed-channel manifest gate.** The publisher now refuses to return *any* response (manifest, posts, delegations, content-key envelope) to a closed-channel pull request unless the request carries a valid HMAC challenge computed under the channel's join capability. Prior versions served the manifest — including `name`, `description`, `currentOnion`, `activeDelegations`, and the `joinCapability` itself when present — to anyone who could reach the onion address, which broke the closed-channel guarantee against any party who learned the onion (via leaked screenshot, mis-shared link, or Tor side-channel). Additionally, `joinCapability` is no longer serialised onto the wire even for legitimate holders: holders reconstruct it locally from their invite, and the manifest signature verifies against the local copy on both sides.
- **Hardened Mode (opt-in).** Three new independently-toggleable protections defend against attack paths that lock-screen wipe tools (Wasted, Sentry, Zerion's own panic responder) cannot reach:
  - **Strict boot verification.** Refuse to start unless `ro.boot.verifiedbootstate = "green"`, `ro.boot.flash.locked = "1"`, and `ro.boot.veritymode ≠ "disabled"`. Closes the recovery-mode `/data` dump bypass.
  - **Tamper detection.** Refuse to start if a debugger is attached (`TracerPid ≠ 0`, `Debug.isDebuggerConnected`), if a root binary (`/system/bin/su`, `daemonsu`, `Superuser.apk`) is present, if Magisk artifacts exist (`/sbin/.magisk`, `/data/adb/magisk*`, `/proc/self/mounts` hooks), if Frida indicators appear in `/proc/self/maps` (`frida-agent`, `gum-js-loop`, `linjector`) or port 27042 is reachable, if Xposed / LSPosed / EdXposed artifacts are present, or if the ADB daemon is listening on `localhost:5555`.
  - **USB panic.** When ADB or MTP/PTP is activated at runtime, fire a panic — either sign-out only (account locked, data preserved) or sign-out plus full account wipe (irreversible). Default scope is sign-out only; the destructive option requires a second confirmation dialog. The wipe action is now actually wired into the panic-response flow — in prior versions, `AntiForensics.handleForensicAttack` only zeroed memory and corrupted cache; the panic-wipe was never invoked.
  See the new [§11.4 Hardened Mode](#114-hardened-mode-opt-in-advanced-defenses) for full enumeration.
- **Forensic at-rest tightening.**
  - **Cache wipe on logout.** `AntiForensics.wipeCachesOnLogout()` is now invoked from `ZerionControllerImpl.signOut` on every sign-out (not only the panic path). Cached decrypted media that materialised in `getCacheDir()` during video/voice playback is corruption-overwritten before the cache directory is unlinked.
  - **60-second clipboard auto-clear.** A new `SecureClipboard.copy` helper unifies every Zerion clipboard write: sets `EXTRA_IS_SENSITIVE` on Android 13+ (suppresses keyboard preview, strips from clipboard history) and schedules a 60-second auto-clear that replaces the entry with a zero-width space only if the clipboard still holds the same text. Channel feed copy-post, channel-invite copy, and inline-invite copy all route through it. Closes the pre-Android-13 gap where copied invite links and post bodies persisted in clipboard history until reboot.
  - **No keyboard predictive-dictionary leak.** `textNoSuggestions` is now set on every Zerion message-class input — 1:1 composer, group composer, channel publish composer, channel comments composer, and channel-create description — so soft keyboards (Gboard, SwiftKey) cannot build a personal dictionary from typed message bodies.
- **Recap of forensic defenses against logical / file-system / physical / cloud extraction tools** (Cellebrite UFED, GrayKey, Magnet AXIOM, MSAB XRY): everything sensitive is encrypted-at-rest (SQLCipher database with `cipher_memory_security=ON` and `secure_delete=ON`; `EncryptedSharedPreferences` for both UI and secure prefs; `MetadataStripper` strips JPEG EXIF and video metadata before any attachment is sent; `FLAG_SECURE` applied via `BaseActivity.applyScreenshotProtection` on every Zerion activity covering screenshots, screen recording, recents thumbnail, and casting; `VISIBILITY_SECRET` on every notification channel; `allowBackup=false` with `backup_rules.xml` and `data_extraction_rules.xml` excluding everything recursively; ProGuard/R8 minification on release). The single remaining gap categories — system-level usage-stats timeline and cache-file mtime/atime — are unfixable from inside any unprivileged Android app.

### v2.0.2 (June 2026, versionCode 20002) — Channel notifications, in-tree EncryptedSharedPreferences

- **Channel post / comment system notifications.** Subscribers now receive system notifications for new channel posts, and publishers for new discussion-thread comments, surfaced through the same `VISIBILITY_SECRET` notification path as 1:1 and group messages (no message body in the lock-screen preview). Notifications are generated locally from pull results — no push channel and no third party is involved.
- **In-tree `EncryptedSharedPreferences` implementation.** The deprecated AndroidX `security-crypto` library (Google deprecated it in April 2025) is removed from the dependency set and replaced by an in-tree `EncryptedSharedPreferences` implementation that wraps the same Android Keystore master-key model (hardware-backed where available, non-exportable, device-bound). Behaviour and at-rest format are unchanged for callers; the app no longer ships a deprecated, unmaintained crypto dependency. This closes the migration item that earlier revisions tracked as a future v2.x maintenance task.

### v2.0.3 (June 2026, versionCode 20003): reliability and maintenance

- **ANR fix.** Binder IPC on network-state changes is moved off the main thread, removing an application-not-responding stall triggered by rapid connectivity transitions.
- **Dependency bumps.** Routine dependency updates for maintenance and hygiene.
- **Build reproducibility.** The build toolchain is pinned so release artifacts reproduce deterministically.

### v2.0.4 (June 2026, versionCode 20004): reliability

- **Create-profile keystore IV fix.** Profile creation now reads the keystore initialization vector strictly, fixing an intermittent create-profile failure.
- **Tor improvements.** Special-use foreground service for the Tor process, a bridge auto-fallback watchdog, a custom-bridge configuration UI, and Tor 0.4.9.9.

### v2.0.5 (June 2026, versionCode 20005): account-backup hotfix

- **Backup key derivation lightened for memory headroom.** The account-backup key derivation was tuned down in memory pressure so backup and restore complete reliably across a wider range of devices. Error reporting on failure is clearer, and backups produced by earlier versions still open.

### v2.0.6 (July 2026, versionCode 20006): contact-add, reliability, and hardening

- **Contact-add hybrid rendezvous is now a real X25519 shared secret.** The version-1 contact-add link carries a 32-byte commitment plus a 32-byte X25519 rendezvous key, and the rendezvous key agreement is a real X25519 Diffie-Hellman. The earlier observer-derivable rendezvous (a hash of public commitments) was removed. See [§7 Contact Discovery & Addition](#7-contact-discovery--addition).
- **Contact-add crash fixes and handshake hardening.** Fixed contact-add crashes (hybrid pending-contact database read; hybrid transport-key derivation) and hardened the add-contact handshake: fail-closed forward secrecy, MAC-authenticated post-quantum capability, strict link decoding, and per-pending-contact rate limiting.
- **Post-quantum transport ratchet stabilized.** Fixed the message-delivery stall caused by ratchet desync. A brief per-frame root-absorption experiment was added and then removed; per-message post-quantum protection stays in the body key, and root-level post-quantum mixing stays in the acknowledged epoch mechanism. Corrupt post-compromise-security state now fails closed (keeps the classical ratchet) instead of dropping to an unratcheted stream. See [PCS_DESIGN.md](PCS_DESIGN.md).
- **Connection reliability.** Removed a fixed 30-minute connection-recycle that churned healthy links; reconnect after a dropped connection is now immediate. Targeted zombie-connection defenses are retained.
- **Voice memos.** Voice memos are chunked for reliable delivery and a delivery-stall race was fixed. Cover-traffic cadence is constant for metadata resistance.
- **Decoder hardening and cleanup.** Bounded message-decoder memory (denial-of-service caps), consolidated secure file deletion, accessibility labels, and a large string, dead-code, and import cleanup.

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
   - Password used to derive database encryption key via Argon2id
     (memory-hard, quantum-resistant; legacy Scrypt-derived keys are
     auto-migrated on first sign-in, see §9.2)
   - Key derivation parameters calibrated to device performance

3. **Step 3: Battery Optimization**
   - Requests exemption from Doze mode
   - Critical for reliable message delivery
   - Optional but recommended

**Security Architecture**:
```
User Password
     ↓
[Argon2id KDF] ← Random Salt (256-bit)
     ↓
Database Encryption Key (256-bit)
     ↓
[Encrypted SQLite Database (SQLCipher)]
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

### 2.2 Group Messaging — Pairwise Triple Ratchet over Hybrid PQ

Zerion does not use the legacy Briar `PrivateGroup` client (sender-key fan-out)
for end-user group chats. Instead, each authored group post is fanned out as
N-1 individually-encrypted messages, one per other member, over the same
pairwise Triple-Ratchet channels that 1:1 conversations use (see §5.3
Mode 3). This means group messages inherit the same security properties as
1:1 messages — full hybrid post-quantum key agreement (X25519 +
ML-KEM-768), Ed25519 + ML-DSA-65 signatures, forward secrecy via the
sending chain, post-compromise security via the DH ratchet step.

**Group state model (`GroupTrManager`)**:

```
GroupTrState {
    groupId:       BLAKE2b-256(creatorName || creatorPubKey || name || salt)
    name:          UTF-8, ≤ 256 bytes
    salt:          32 bytes, random at creation
    creatorPubKey: 32-byte Ed25519
    creatorName:   UTF-8
    created:       int64 ms
    epoch:         uint32, monotonically increasing
    dissolved:     bool
    members:       List<GroupTrMember>
    defaultTTL:    int64 ms (auto-delete)
}

GroupTrMember {
    pubKey, name, joinedAt, joinedAtEpoch, role ∈ {MEMBER, ADMIN, CREATOR}
}
```

State is persisted via `SettingsManager` (encrypted at rest by SQLCipher);
there is no shared group sync client and no on-disk group message table —
group posts live in each pairwise contact group as ordinary 1:1 records
tagged with the group ID.

**Wire message types** (over the pairwise 1:1 channel):

| msgType | Name                         | Authed by      |
| ------- | ---------------------------- | -------------- |
| 32      | GROUP_POST                   | sender Ed25519 |
| 33      | GROUP_MEMBER_ADDED           | creator        |
| 34      | GROUP_MEMBER_REMOVED         | creator        |
| 35      | GROUP_MEMBER_LEFT            | leaver         |
| 36      | GROUP_DISSOLVED              | creator        |
| 37      | GROUP_EPOCH_COMMIT           | creator        |
| 38      | GROUP_MEMBER_ROLE_CHANGED    | creator        |
| 41      | GROUP_MEMBER_LIST_SNAPSHOT   | creator        |

All membership records carry a domain-separated Ed25519 signature over
`(groupId, target, epoch, timestamp, action_byte)`; the receive validator
(`PrivateMessageValidator`) verifies the signature against the claimed
signer and rejects malformed or stale records before delivery.

**Epoch-bound post-compromise security**:

When the creator removes a member, the `GROUP_MEMBER_REMOVED` record and a
`GROUP_EPOCH_COMMIT` (carrying a 32-byte fresh PQ seed) are fanned out
atomically. Remaining members advance their local epoch on commit. The act
of dispatching these records over each pair's Triple-Ratchet channel
naturally triggers the encrypter's DH-ratchet step on the next send,
which gives the new epoch the same post-compromise property as a 1:1
ratchet rotation.

**Out-of-order epoch handling**:

A receiver tolerates posts arriving up to `EPOCH_BUFFER_TOLERANCE = 5`
epochs ahead of its local view (e.g., during a connection gap). Buffered
posts are released on the next legitimate epoch advance. Posts older than
`localEpoch - 1` are dropped.

**Privacy features**:

- **Stealth name**: A member may set a per-group display alias (separate
  from their identity name); only the alias is signed into outbound
  `GROUP_POST` records.
- **Group-default auto-delete TTL**: Creator-settable; if a sender does
  not specify a per-post timer, the group default is applied.
- **No mutual-contact assumption**: A group may include members who are
  not 1:1 contacts of each other. Inviter delivery only — non-mutual
  members cannot derive private contact channels from group membership
  alone. Direct messaging between non-mutual members across a group is a
  future opt-in design (see internal relay-privacy design).

**Member-list snapshot**:

The creator may publish a `GROUP_MEMBER_LIST_SNAPSHOT` (msgType=41)
signed over the canonical concatenation of
`pubKey(32) || role(1) || joinedAtEpoch(4 BE)` per member. Members
reconcile their local roster from the snapshot when the snapshot epoch
≥ local epoch, allowing late joiners and clients that missed
intermediate `MEMBER_ADDED/REMOVED` records to recover canonical state.

### 2.5 Channels — Publisher → Subscriber Broadcast

A Zerion **channel** is a one-to-many broadcast surface: a single publisher (or a delegated set of editors) posts; an unbounded set of subscribers reads. Channels are an independent messaging modality with their own data model, their own pull protocol, and their own onion endpoint per publisher. Channels do **not** reuse the Briar `forum` or `blog` clients, and they do **not** use the same pairwise Triple Ratchet that 1:1 and group messages use — they have a fundamentally different threat model (no per-pair forward secrecy because subscribers are not necessarily mutual contacts of one another).

#### 2.5.1 Channel topology

```
                ┌─────────────────────┐
                │  Channel Publisher  │
                │  (owns onion key)   │
                │   +N editors via    │
                │     delegations     │
                └──────────┬──────────┘
                           │  signs every post
                           │  Ed25519 + ML-DSA-65
                           ▼
              ┌──────────────────────────┐
              │   Publisher's Tor v3     │
              │   hidden service onion   │
              └──────────┬───────────────┘
                         │
            pull every ~5 s when foreground
                         │
        ┌────────────┬───┴────────────┐
        ▼            ▼                ▼
   ┌────────┐  ┌────────┐         ┌────────┐
   │  Sub A │  │  Sub B │   ...   │  Sub N │
   └────────┘  └────────┘         └────────┘
```

Subscribers pull from the publisher; the publisher never pushes. Channels are inherently asynchronous — a subscriber that has been offline for a week catches up on the next pull. No subscriber-to-subscriber traffic exists in the channels protocol.

#### 2.5.2 Channel state model

```
ChannelState {
    channelId             := BLAKE2b-256(publisherHybridPubKey || salt)
    salt                  := 32 random bytes at creation
    publisherEd25519PubKey:= 32 bytes
    publisherMlDsaPubKey  := 1,952 bytes
    name                  := UTF-8, ≤ 256 bytes
    description           := UTF-8, ≤ 2,048 bytes
    avatarHash            := optional 32-byte BLAKE2b-256
    createdAtHourMs       := int64 ms (hour-floored)
    publicChannel         := bool
    joinCapability        := nullable 32 bytes (closed channels only)
    currentOnion          := publisher's Tor v3 onion (rotates per B.4)
    manifestSeq           := uint64, monotonically increasing
    weArePublisher        := bool (local-only)
    contentKeyHash        := nullable 32 bytes
    contentKey            := nullable 32 bytes (local-only, never wired)
    activeDelegations     := List<ChannelDelegationCert>
    revokedDelegationSeqs := List<uint64>
    nextDelegationSeq     := uint64
    onionPrivateKey       := publisher-only, never leaves the device
    pinnedPostSeq         := int64 (−1 if none)
    requiresApproval      := bool (closed-channel join gate)
}
```

State is persisted via `SettingsManager` in dedicated SQLCipher namespaces (`zerion-channels-state`, `-posts`, `-priv`, `-mirror`, `-index`, `-unread`). Per-channel posts, reactions, comments, subscriber roster, applications, post tombstones, and self-announce records each live in their own namespace.

#### 2.5.3 Public vs. closed channels

| | Public channel | Closed channel |
|---|---|---|
| Invite link contents | onion + channelId | onion + channelId + 32-byte `joinCapability` |
| Anyone with the link can | subscribe and read | subscribe and *request* approval |
| Publisher approval required to read | no | yes (`requiresApproval = true`) |
| Manifest visible to non-capability holders | yes (name, description, onion are public-by-design) | **no** — pull responses are gated on a valid HMAC challenge under the join capability (§2.5.6) |
| Content key envelope returned to | every subscriber | only HMAC-verified capability holders |
| `joinCapability` serialised on the wire | yes (it's already public) | **no** — holders reconstruct it locally from the invite (§2.5.6) |

#### 2.5.4 Post structure and signatures

```
ChannelPost {
    seqNum               := uint64, monotonically increasing per channel
    prevHash             := 32-byte BLAKE2b-256 of the previous post's signed
                            input (forms a hash chain)
    timestampHourMs      := int64 ms (hour-floored)
    body                 := UTF-8, ≤ 10,000 bytes
    ttlMs                := optional disappearing-message timer
    attachments          := List<ChannelAttachment>
    pinnedHint           := bool
    delegateSignerEd25519:= optional 32 bytes (editor delegation)
    delegateSignerMlDsa  := optional 1,952 bytes
    signature            := hybrid Ed25519 + ML-DSA-65, 3,373 bytes
}
```

Every post carries a full **hybrid Ed25519 + ML-DSA-65 signature** over the canonical signed input `(channelId || seqNum || prevHash || timestampHourMs || body || ttlMs || attachmentDigest)`. The subscriber-side validator (`ChannelChainVerifier`) re-derives `prevHash`, verifies signature continuity through the chain, and rejects any post whose seq is non-monotonic, whose `prevHash` doesn't match the previous post's signed-input hash, or whose hybrid signature does not verify. Delegate-signed posts carry the publisher-issued delegation certificate by reference (`delegationSeq`) and the verifier checks that the delegation has not been revoked.

Channel posts are stored **encrypted at rest** in the publisher's local DB using a per-channel `contentKey` (AES-256-GCM with a deterministic body nonce derived from `(channelId, seqNum)`). Subscribers receive the content key only after a successful HMAC challenge — see §2.5.6.

#### 2.5.5 Editor delegations

The publisher can delegate posting authority to up to *N* editors without sharing the publisher private key. A `ChannelDelegationCert` is a publisher-signed structure binding an editor's Ed25519 + ML-DSA-65 public key to a validity interval and a monotonically increasing `delegationSeq`. Editors sign posts under their own keys; subscribers verify the post signature against the editor key and verify the delegation certificate against the publisher key. Revocation is achieved by adding `delegationSeq` to `revokedDelegationSeqs` in the next published manifest — the subscriber-side validator rejects any post signed under a revoked delegation, even if the delegation cert itself is still locally cached.

#### 2.5.6 Pull protocol and replay-resistant HMAC challenge

```
Subscriber                                       Publisher (onion)
    │                                                  │
    │ PullRequest {                                    │
    │   channelId, sinceSeqNum,                        │
    │   nonce             (32 random bytes),           │
    │   hmacResponse     := HMAC(joinCapability,       │
    │                            "PULL_CHALLENGE",     │
    │                            channelId || nonce)   │
    │ }                                                │
    │ ────────────────────────────────────────────────►│
    │                                                  │
    │                              record (channelId, nonce) in TTL ring;
    │                              reject if already seen (replay);
    │                              verify hmacResponse against
    │                              local joinCapability;
    │                              reject if invalid AND
    │                                channel is closed.
    │                                                  │
    │ PullResponse {                                   │
    │   manifest (signed),                             │
    │   posts[]   (signed, since sinceSeqNum),         │
    │   contentKeyEnvelope (only if HMAC verified),    │
    │   reactions[], comments[]                        │
    │ }                                                │
    │ ◄────────────────────────────────────────────────│
```

**Replay protection.** The publisher maintains a per-channel ring buffer of seen `(channelId, nonce)` pairs with a **5-minute TTL** and a **4,096-entry bound**. Replayed nonces are rejected outright, before any response is built. Prior versions accepted any HMAC indefinitely — a captured request could be replayed to keep harvesting the wrapped content key, useful for traffic analysis against the publisher's onion and dangerous after capability rotation.

**Closed-channel manifest gate.** When the channel is closed (`isPublicChannel = false`, `joinCapability ≠ null`), the publisher returns an **empty response** to any request that does not carry a fresh, valid HMAC. The manifest itself — name, description, current onion, delegations — is not disclosed to non-holders. Public channels return the manifest unconditionally because all of its fields are public-by-design.

**`joinCapability` is not on the wire for closed channels.** Capability holders already know it (it came from the invite link). The manifest signature is computed over `(..., joinCapability, ...)` on both sides — the publisher signs over its local copy, the subscriber verifies over its local copy. The capability never leaves either device once both sides have it.

**Subscriber poll cadence.** Foreground subscribers pull every **5 seconds** during the first few rounds after a hit, decaying to **12 seconds** when idle. Background pulls are gated on Tor circuit availability and battery state.

#### 2.5.7 Content-key delivery and rotation

```
ContentKey (32 random bytes, generated at publisher creation)
                       │
                       ├── used locally to encrypt posts at rest (AES-256-GCM)
                       │
                       ├── never wired in plaintext
                       │
                       └── delivered to capability holders via
                           [HKDF(joinCapability, channelId) → KEK]
                           [AES-256-GCM wrap → ContentKeyEnvelope]
                           returned in pull response after HMAC verification
```

The content key is generated once at channel creation, encrypts every post in the local DB, and is delivered to each new capability holder exactly once per pull (wrapped under a KEK derived from their join capability). The wrapped envelope is *not* a long-term secret — the publisher returns it on every successful pull, and the subscriber may re-wrap or re-derive at any time.

#### 2.5.8 Subscriber approval (closed-channel join gate)

For closed channels where `requiresApproval = true`, a subscriber whose joinCapability HMAC verifies still cannot read posts until the publisher approves them. The approval flow:

1. Subscriber sends an **`APPLY`** request carrying their display name and Ed25519 + ML-DSA-65 identity.
2. Publisher stores it in `ChannelApplicationStore` and fires a UI event.
3. Publisher approves or denies via the channel-management UI.
4. Subscriber polls `CHECK_APPROVAL` (rate-limited to once per 30 s); on approval, subsequent pulls include the content-key envelope.
5. Denied applicants can re-apply (the prior DENIED record is replaced with a fresh APPLIED record).

Subscriber bans are stored locally on the publisher (`ChannelSubscriberStore.isBanned`); a banned subscriber's HMAC still verifies but the publisher returns an empty response.

#### 2.5.9 Reactions

Subscribers may attach an emoji reaction to any post seqNum. Reactions carry the same dual signature as comments (Ed25519 + ML-DSA-65 under the reacter's identity key), are aggregated server-side by the publisher, and are returned to all subscribers in the pull response. The publisher enforces a per-author / per-channel cap to prevent reaction-flooding.

#### 2.5.10 Discussion threads (Telegram-style comments)

When the publisher enables discussions (per-channel toggle stored in `ChannelDiscussionStore`, default ON, owner-only setter), subscribers can post comments under any post seqNum. Comments are dual-signed (Ed25519 + ML-DSA-65), bounded at 1,024 bytes of body and 4,096 comments per channel / 64 per author. Comment requests are gated identically to pull requests (replay-resistant HMAC challenge); when the publisher disables discussions, `handleCommentRequest` returns an immediate rejection ack, and the subscriber UI hides the comment composer on the next refresh.

Comment storage uses a separate per-channel BDF namespace (`zerion-channels-comments`); comments are persistently retained across publisher restarts but are tombstoned when the parent post is deleted.

#### 2.5.11 Attachments

Channel-post attachments use the same encrypted-blob store (`ChannelBlobStore`) as 1:1 attachments: AES-256-GCM under a per-attachment key, with the per-attachment key wrapped under the channel's content key. Thumbnails are generated client-side, encrypted under a separate thumbnail key (also wrapped), and embedded in the post itself for lazy display. Subscribers fetch the full blob via a separate `ATTACHMENT_FETCH` pull request keyed by blob hash.

#### 2.5.12 Channel onion rotation

Channels rotate their hidden service onion on the same cadence as the publisher's contact onion (uniformly random every 5–14 days, per [B.4 in §0](#v15-may-1-2026--b3-pairing--b4-onion-rotation)). The new onion address is propagated to subscribers in the next manifest. A subscriber whose locally-cached `currentOnion` is stale falls back to a brief blast across the recent-rotation cache (configurable, default 3 prior onions retained for 21 days) before declaring the channel unreachable. The publisher's onion private key never leaves the device.

#### 2.5.13 Tombstones and ephemeral posts

A publisher may set a per-post `ttlMs` (1 hour, 1 day, 1 week, or 30 days). When a subscriber's local clock crosses `timestampHourMs + ttlMs`, the post is deleted from local storage and a per-post tombstone is recorded so a stale pull response cannot resurrect it (`ChannelPostTombstoneStore`). Publishers can also tombstone individual posts manually at any time. Subscribers that pull after a tombstone is published learn of it from the publisher's tombstone list (cryptographically bound to the manifest seq); subsequent pull responses no longer include the tombstoned seqNum.

#### 2.5.14 Channel-pull wire types

```
WIRE_TYPE_PULL_REQUEST           // subscriber → publisher: pull
WIRE_TYPE_PULL_RESPONSE          // publisher → subscriber
WIRE_TYPE_ATTACHMENT_FETCH       // subscriber → publisher: blob by hash
WIRE_TYPE_ATTACHMENT_RESPONSE    // publisher → subscriber
WIRE_TYPE_APPLY_REQUEST          // subscriber → publisher: closed-channel
WIRE_TYPE_APPLY_RESPONSE         // publisher → subscriber: ack
WIRE_TYPE_CHECK_APPROVAL         // subscriber polls
WIRE_TYPE_CHECK_APPROVAL_RESPONSE
WIRE_TYPE_COMMENT_REQUEST        // subscriber → publisher: post comment
WIRE_TYPE_COMMENT_ACK            // publisher → subscriber
WIRE_TYPE_REACTION_REQUEST
WIRE_TYPE_REACTION_ACK
WIRE_TYPE_SELF_ANNOUNCE          // subscriber claims a display name
```

All wire-level messages are BDF dictionaries with explicit `type` fields. All publisher-side handlers run under a per-channel `ReentrantLock` (`ChannelManagerImpl.lockFor(channelId)`) so concurrent pulls cannot race on post-list snapshotting, manifest serialisation, or content-key envelope wrapping.

#### 2.5.15 What channels deliberately do NOT do

- **No subscriber-to-subscriber traffic.** A subscriber knows the publisher's onion but does not learn other subscribers' onions or identities by participating. (The publisher does see each subscriber's hybrid pubkey when they apply or comment — this is required for signature verification.)
- **No global discoverability.** There is no channel directory, no global index, no search. A channel exists only if someone shares its invite link out-of-band.
- **No subscriber count or member list disclosure to subscribers.** Only the publisher knows the subscriber roster.
- **No push notifications from publisher to subscriber.** Pull-only by design — pushing would require the publisher to know each subscriber's onion at the protocol layer, which would leak subscriber identities to anyone who controls the publisher.

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
[Display as QR/Link] (104 chars: 32-byte commitment + 32-byte X25519 rendezvous key)
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

**Current Status**: IMPLEMENTED - All Phases Complete including Mode 3 Triple Ratchet (see `docs/PCS_DESIGN.md`).

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
- Recovery on time-period rotation (see table below)

**Key Rotation Periods by Transport**

The rotation period is calculated as: `MAX_LATENCY + MAX_CLOCK_DIFFERENCE`

| Transport | MAX_LATENCY | Clock Tolerance | Rotation Period | Source |
|-----------|-------------|-----------------|-----------------|--------|
| Tor | 30 seconds | 24 hours | ~24 hours | `TorPluginFactory.java:42` |

Tor v3 onion is the sole transport (Bluetooth, LAN TCP, WAN TCP, and
removable-drive plugins were removed in v1.6.2 — see [§0](#v162-may-15-2026--native-group-invites-tor-only-transport-at-rest-hardening)).

**Constants Reference**:
- `MAX_CLOCK_DIFFERENCE = 24 hours` (`TransportConstants.java:69`)
- Formula: `timePeriodLength = maxLatency + MAX_CLOCK_DIFFERENCE` (`TransportKeyManagerImpl.java:75-88`)

**Mode 2: Full Double Ratchet (Implemented)**
- Adds DH ratchet step per message exchange
- Maximum PCS: recovery within 1 round-trip
- Higher bandwidth (32-byte DH public key per message)

**Mode 3-Full: Per-message PQ ratchet (Active for Zerion↔Zerion, v1.7+)**
- Combines DH ratchet (X25519) + per-frame PQ ratchet (ML-KEM-768)
- **Every transport frame in both directions** carries a fresh ML-KEM-768
  encapsulation against the peer's currently advertised ML-KEM public key.
  The encapsulated shared secret is mixed into the per-frame body AEAD key
  via `bodyKey = HKDF(classicalMessageKey, ml_kem_shared_secret)`.
- Sender rotates its own ML-KEM keypair on every successful encapsulation
  and advertises the freshly generated public key in the same frame;
  recent sender keypairs are retained in a per-contact LRU (cap 64) so
  peer ciphertexts against slightly stale public keys still decapsulate
  cleanly. A 16-byte `kpId` in the frame header identifies which keypair
  the peer encapsulated against.
- Per-stream chain key: each transport stream derives its own initial
  chain key from `HKDF(rootKey, "PCS_STREAM_CHAIN", streamNumber_8B)`
  and advances it locally per frame. The chain key is never persisted
  across streams. Eliminates the parallel-stream desync that constrained
  the prior shared-chainKey design.
- ML-KEM shared secrets are zeroed immediately after the body AEAD key
  derives from them, on both encapsulation and decapsulation sides.
- Zerion↔Briar contacts use Mode 1/2 for compatibility.

> **Legacy note**: The earlier Mode 3 design (Phase 4d, January 2026) rotated PQ epochs every 25 messages or 24 hours with chunked transmission. v1.7 (May 2026) replaced this with per-message ML-KEM as described above. See [§0 v1.7 entry](#v17-may-2026--per-message-ml-kem-768-hybrid-ratchet-mode-3-full-shipped) for the migration rationale.

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

Mode 3-Full (per-message ML-KEM-768 mixed into the body AEAD key) is the
**default** for Zerion↔Zerion contacts since v1.7. Mode 2 (per-message DH)
and the earlier per-epoch Mode 3 (PQ rotation every 25 messages / 24h) remain
as fallbacks for compatibility; Mode 1 is symmetric-only.

| Property | Mode 1 | Mode 2 | Mode 3 (fallback, per-epoch) | Mode 3-Full (default, per-message) |
|----------|--------|--------|------------------------------|------------------------------------|
| Forward Secrecy | ✅ | ✅ | ✅ | ✅ |
| Post-Compromise Recovery | Time-based (~24h for Tor) | 1 round-trip | 1 round-trip | 1 round-trip |
| Quantum Resistance | ✅ (via handshake) | ✅ (via handshake) | ✅ (per-epoch ML-KEM, 25 msg / 24h) | ✅ (per-message ML-KEM, every frame) |
| Out-of-order tolerance | ✅ | ✅ | ✅ | ✅ |
| PQ Forward Secrecy | ❌ | ❌ | ✅ | ✅ (per frame) |

### 5.9 Implementation Status

| Phase | Status | Description |
|-------|--------|-------------|
| Phase 1-3 | **Complete** | Symmetric ratchet, capability negotiation, session state |
| Phase 4a | **Complete** | Mode 2 Double Ratchet with DH |
| Phase 4b | **Complete** | Mode 3 infrastructure (ML-KEM-768, chunking) |
| Phase 4c | **Complete** | Mode 3 capability negotiation |
| Phase 4d | **Complete** | Mode 3 Triple Ratchet activation (per-epoch) |
| v1.7 | **Complete** | Mode 3-Full per-message ML-KEM-768 (default) |

**Mode 3-Full is the default**: since v1.7, Zerion↔Zerion contacts default
to the per-message ML-KEM-768 hybrid ratchet (a fresh encapsulation mixed
into every transport frame's body AEAD key — see §5.3). The earlier
per-epoch Mode 3 (PQ rotation every 25 messages / 24 hours) and Mode 2
remain as fallbacks. Zerion↔Briar contacts continue using Mode 1/2 for
compatibility.

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
- X25519 rendezvous key (32 bytes), used for the rendezvous key agreement
- Transport descriptors (Tor .onion)
- Encoded as base32

### 7.2 Link-Based Addition

**Link Format**:
```
zerion://[104 lowercase base32 chars: 32-byte commitment + 32-byte X25519 rendezvous key]
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
| **1** | Hybrid commitment (32 bytes) + X25519 rendezvous key (32 bytes) | Post-Quantum (192-bit) | Zerion only |

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
- **Opus codec compression** (24 kbps VOIP mode with FEC/PLC)
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
- **Bitrate**: ~24 kbps with Variable Bitrate (VBR)
- **Compression Ratio**: ~10x (640 bytes PCM → ~60 bytes Opus)

**Audio Source**: `MediaRecorder.AudioSource.VOICE_COMMUNICATION`
- Automatically enables Acoustic Echo Cancellation (AEC)
- Automatically enables Noise Suppression (NS)
- Automatically enables Automatic Gain Control (AGC)
- Optimized for VoIP applications
- Hardware-accelerated when available

**Why Opus Codec (via Concentus)?**

Zerion uses the Opus codec implemented via the Concentus pure Java library:
1. **Bandwidth Efficiency**: 24 kbps vs 256 kbps PCM (~10x compression)
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
Compressed Opus Frame (~60 bytes)
            ↓
[AES-256-GCM Encryption]
    ├── Key: Derived outgoing/incoming key
    ├── IV/Nonce: Random 12 bytes per frame
    └── Auth Tag: 16 bytes GCM tag
            ↓
Encrypted Frame (~60 bytes + 12 IV + 16 tag = ~88 bytes)
            ↓
[Transmit over Tor Hidden Service]
            ↓
[AES-256-GCM Decryption on Receiver]
            ↓
Compressed Opus Frame (~60 bytes)
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
[Opus Encoder] (Concentus - 24 kbps VOIP mode)
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
- **Bitrate**: ~24 kbps (Opus VOIP mode)
- **Latency**: ~100-200ms end-to-end (including Tor routing)
- **Jitter**: <50ms with stable connection (200-350ms jitter buffer)
- **Packet Loss Tolerance**: Excellent - Opus FEC + PLC handles up to 20% loss
- **Compression Ratio**: ~10x (640 bytes PCM → ~60 bytes Opus)

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

### 8.13 Video Calling

Zerion also provides **end-to-end encrypted peer-to-peer video calling** over Tor, layered on the same signaling, key-exchange, and P2P-over-onion transport as voice calls. Video calls add an encrypted camera stream alongside the encrypted Opus audio stream; both streams travel inside the same per-call AES-256-GCM-secured channel.

**Video Format**:
- **Codec**: H.264 (AVC), **Main Profile, Level 3.1**
- **Resolution**: 640x480
- **Frame Rate**: 24 fps (nominal)
- **Bitrate**: ~600 kbps (nominal)
- **Color**: standard YUV 4:2:0 camera capture

**Video Frame Encryption**:
- **Algorithm**: AES-256-GCM, per-frame, using the same per-call key material as the audio stream (HKDF-derived outgoing/incoming keys).
- **Per-frame nonce**: unique random 12-byte IV per frame; 16-byte GCM auth tag.
- **Frame padding**: each encrypted video frame is padded to a bucketed length before transmission so that the on-the-wire frame size does not leak the precise compressed-frame size (defeats frame-size traffic analysis of scene complexity / motion).

**Adaptive bitrate and frame-rate controller**:

The video sender continuously monitors connection quality (packet loss, jitter, round-trip latency over the Tor circuit) and steps the encoder down through a fixed ladder when the link cannot sustain the current tier, then recovers upward when conditions improve:

```
640x480 @ 24 fps / 600 kbps   (default)
        ↓ degrade
640x480 @ 15 fps / 250 kbps
        ↓ degrade
640x480 @ 10 fps / 150 kbps
        ↓ degrade
640x480 @  5 fps /  80 kbps
        ↓ degrade
   video off (audio-only fallback)
```

There is no separate low-resolution capture mode — every tier captures and encodes at 640x480; the controller modulates frame rate and bitrate only.

**Call controls**:
- **Camera switch**: toggle between front and rear cameras mid-call without tearing down the call or renegotiating keys.
- **Video pause/resume**: the local user can suspend the outgoing camera stream (audio continues) and resume it later within the same call.
- **Mute/speaker controls**: shared with the voice-call UI.

**Security & transport properties**: identical to voice calls — direct P2P over Tor hidden services (no STUN/TURN, no relays, no IP exposure), per-call forward secrecy (keys generated per call, destroyed at call end), FLAG_SECURE screenshot protection during the call, and no recording or telemetry. The audio half of a video call remains Opus 24 kbps as described in §8.3.

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
[SQLCipher Database (libsqlcipher)]
     ├── AES-256-CBC per page, HMAC-SHA512 per page
     ├── 4 KB page size (default), random IV per page
     ├── PRAGMA cipher_memory_security = ON
     │   (zeros decrypted pages in memory after use)
     ├── PRAGMA secure_delete = ON
     │   (zeros freed pages on row delete — defeats DB-page carving
     │    of deleted rows by Cellebrite UFED FS extraction)
     └── PRAGMA busy_timeout = 5000
     ↓
Encrypted Database File (app_db/db.sqlite)
```

**Post-Quantum Security Analysis**:
- **Argon2id**: Memory-hard KDF, not affected by quantum computers
- **XSalsa20-Poly1305**: 256-bit symmetric - Grover's algorithm halves to 128-bit security
- **BLAKE2b**: Hash function maintains 128-bit PQ security at 256-bit output
- **Overall**: 128-bit post-quantum security for database encryption

**Note on prior whitepaper revisions**: Versions of this document up to v3.0 described the underlying database as "H2/HyperSQL" with AES-256-CBC per-page. The actual on-disk format has been SQLCipher (libsqlcipher / `net.zetetic:sqlcipher-android`) since the Zerion fork diverged from the original Briar codebase. The page cipher and HMAC mode above are SQLCipher's, not H2's. See `bramble-android/src/main/java/org/briarproject/bramble/db/SqlCipherDatabase.java`.

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
- **Codec**: Opus (high quality, low latency)
- **Recording**: Up to 5 minutes
- **Storage**: Encrypted like other attachments
- **UI**: Waveform visualization, playback controls

**Features**:
- Hold-to-record
- Slide-to-cancel
- Play/pause controls
- Progress bar
- Duration display

### 10.2.1 Stickers

A built-in catalogue of expressive image stickers, shipped with the app
(no external download, no network egress, no third-party CDN). Stickers
are sent as standard image attachments encrypted under the conversation's
Triple Ratchet — they carry no special wire type and inherit identical
confidentiality, forward secrecy, and PCS properties as any other image
attachment. The catalogue is local-only; no per-user usage telemetry is
collected or transmitted.

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
- 35+ languages supported
- RTL layout support
- Culturally appropriate content

### 10.6 Multi-Profile Isolation

Zerion supports multiple, fully isolated identities on a single device — for users who need separate work / personal contexts, or who need plausible deniability that a secondary profile exists at all.

**Design choices**:

- **Password-only hidden profiles.** The login screen shows no profile names and no profile count. The user types one password; the app silently routes to the profile whose stored database key decrypts under that password. An observer of the unlocked phone screen cannot tell how many profiles exist on the device.
- **Restart-on-switch.** Switching profile cleanly stops the current profile's services (database close, Tor data directory release), terminates the process, and re-launches into the login screen. The two profiles never co-exist in memory or on the network simultaneously.
- **Secure wipe on delete.** Deleting a profile overwrites every file in that profile's data directory with zeros (`fd.sync()`'d) before unlinking. The same overwrite-then-delete pattern protects voice and video temp cache files.

**On-disk layout**:

```
<app private files dir>/
  profiles/
    <profile-uuid-1>/
      db/        # SQLCipher database for this profile
      key/       # Argon2id-encrypted DB key + display name marker
      tor/       # Per-profile Tor data dir (own v3 onion key)
    <profile-uuid-2>/
      ...
  login.lockout  # Global failed-attempt counter (not per profile)
```

**Cryptographic isolation per profile**:

| Material | Per-profile? |
|---|---|
| Argon2id salt | Independent per profile (baked into the encrypted DB key blob) |
| Database key | Independent per profile |
| Local identity Ed25519 + ML-DSA-65 keypair | Independent per profile |
| Tor v3 onion key | Independent per profile |
| Vault contents | Independent per profile |
| SkippedKeyStore + ratchet state | Tied to the active profile's database |

**Threat model notes**:

- A forensic dump of profile A's ciphertext yields nothing decryptable about profile B even with profile A's password in hand. The two databases use independently derived keys.
- An attacker who briefly observes the unlocked phone screen sees only the active profile. No UI hint exists that other profiles are present, unless the user navigates to Settings → Profiles.
- Wrong-password feedback time scales with profile count (one Argon2id evaluation per stored profile per failed attempt). This is intentional: an attacker cannot tell from timing alone whether the device has 1 profile or 5.
- Switching profiles tears down Tor completely before the new profile starts, so an on-device passive observer cannot correlate the two profiles' Tor circuits.

**Login flow**:

```
On signIn(password):
    for profileId in stored profiles:
        salt, encryptedDbKey = read profile dir
        KEK = Argon2id(password, salt, cost)
        try:
            dbKey = decrypt(encryptedDbKey, KEK)
            open SQLCipher with dbKey
            set active profile = profileId
            return success (no signal of which profile matched)
        on decryption failure:
            try next profile
    record failed attempt
    increment global lockout counter
    return INVALID_CIPHERTEXT (no detail of which profile or how many tried)
```

---

## 11. SECURITY PROPERTIES

### 11.1 Threat Model

**Protected Against**:

| Threat | Protection |
|--------|------------|
| Network Surveillance | Tor anonymity |
| Metadata Collection | Minimal metadata, P2P architecture |
| Message Interception | End-to-end encryption (hybrid PQ) |
| Database Theft | SQLCipher with Argon2id-derived key; `secure_delete=ON` defeats deleted-row carving |
| Password Attacks | Memory-hard Argon2id KDF (legacy Scrypt auto-migrated, see §9.2) |
| Timing Attacks | Constant-time crypto primitives |
| Man-in-the-Middle | Hybrid commitment-based handshake (BQP v2) |
| Replay Attacks | Per-frame nonces; channel-pull replay TTL ring (§2.5.6); group epoch monotonicity |
| Forensic dump tools (logical / FS extraction) | At-rest encryption everywhere (SQLCipher DB + EncryptedSharedPreferences + AES-GCM attachment blobs + AES-GCM channel content); `allowBackup=false`; `FLAG_SECURE` on every activity; `VISIBILITY_SECRET` on every notification; ProGuard/R8 minification on release |
| Forensic dump tools (physical / chip-off) | Same as above plus `secure_delete=ON` zeros freed DB pages |
| Recovery-mode `/data` dump (bootloader-unlock bypass) | **Partial mitigation** via Hardened Mode strict-boot toggle (§11.4) — refuses to start if `verifiedbootstate ≠ "green"` |
| ADB / USB exfiltration with debugging enabled | `allowBackup=false` blocks `adb backup`; Hardened Mode USB-panic toggle (§11.4) signs out and optionally wipes |
| Frida / Xposed / LSPosed runtime instrumentation | **Partial mitigation** via Hardened Mode tamper toggle (§11.4) — refuses to start if hooks detected |
| Root binary / Magisk presence | **Partial mitigation** via Hardened Mode tamper toggle (§11.4) |
| Keyboard predictive-dictionary leak | `textNoSuggestions` on every message-class input |
| Clipboard leak (post-copy persistence) | `EXTRA_IS_SENSITIVE` on Android 13+ plus universal 60 s auto-clear via `SecureClipboard.copy` |

**Out of Scope**:
- **Full device root with active malware in the same UID** — no app sandbox can defend against a hostile component running with the app's own privileges.
- **Physical device access while unlocked.** If the screen is unlocked, an attacker with hands-on access can act as the user. Zerion's auto-lock timeout, FLAG_SECURE, and biometric/PIN re-prompt narrow this window; they do not eliminate it.
- **User phishing / social engineering.**
- **System-level usage-stats timeline** (`/data/system/usagestats/`) and **cache file mtime/atime** — these are OS-level artifacts that record *when* Zerion was foregrounded. No unprivileged Android app can suppress them. Zerion's cache-wipe-on-logout (§9.5, §0 v2.0) corrupts the *contents* of cache files; the directory timestamps remain.

**Quantum Computer Protection** ✅:
- **Key Exchange**: Hybrid ML-KEM-768 + X25519 (NIST Level 3)
- **Signatures**: Hybrid ML-DSA-65 + Ed25519 (NIST Level 3)
- **Defense-in-Depth**: Both classical AND PQ algorithms must be broken
- **Per-message PQ ratchet (v1.7+)**: Every transport frame carries fresh ML-KEM-768 encapsulation — see §5.3 Mode 3-Full.

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

### 11.4 Hardened Mode (opt-in advanced defenses)

Hardened Mode is a user-opt-in bundle of three independently-toggleable refuse-to-start protections, exposed under Settings → Security → "Hardened Mode (Advanced)". All three default to OFF. Together they close attack paths that no app-layer panic responder can reach: recovery-mode dumps, root-level instrumentation, and active USB exfiltration.

#### 11.4.1 Strict Boot Verification

On every `BaseActivity.onCreate`, before Dagger graph construction and before the SQLCipher database is touched, the app reads Android's verified-boot properties via `android.os.SystemProperties` reflection (with a `/system/bin/getprop` fallback) and refuses to start if any of these hold:

- `ro.boot.verifiedbootstate ≠ "green"` — the boot image is not OEM-signed; the OS may have been modified.
- `ro.boot.flash.locked ≠ "1"` — the bootloader is unlocked; the device can be booted into a custom recovery to dump `/data`.
- `ro.boot.veritymode = "disabled"` — dm-verity is off; on-disk modifications to system partitions are not detected.

**Attack path closed.** An attacker who unlocks the bootloader and boots into a custom recovery to bypass Wasted/Sentry/Zerion's panic responder now hits a refuse-to-start on the next launch. The user re-locks the bootloader (or reflashes a stock image) to recover. The "Disable Hardened Mode" button on the block screen requires explicit confirmation.

#### 11.4.2 Tamper Detection

Refuses to start if any of these are detected:

- **Debugger attached** — `Debug.isDebuggerConnected()`, `Debug.waitingForDebugger()`, or `/proc/self/status` `TracerPid ≠ 0`.
- **Root binary present** — `/system/bin/su`, `/system/xbin/su`, `/system/sbin/su`, `/sbin/su`, `/vendor/bin/su`, `/su/bin/su`, `/data/local/{,bin/,xbin/}su`, `/system/app/Superuser.apk`, `/system/etc/init.d/99SuperSUDaemon`, `/system/xbin/daemonsu`.
- **Magisk artifacts** — `/sbin/.magisk`, `/cache/.disable_magisk`, `/dev/.magisk.unblock`, `/system/etc/init/magisk.rc`, `/data/adb/magisk{,.db}`, `/data/adb/modules`. Also checked: `/proc/self/maps` and `/proc/self/mounts` for the string `magisk` or `/data/adb`.
- **Frida instrumentation** — `/data/local/tmp/{re.frida.server,frida-server}`. `/proc/self/maps` scanned for `frida-agent`, `frida-gadget`, `libfrida`, `gum-js-loop`, `gmain`, `linjector`. Probes TCP `127.0.0.1:27042` (default frida-server port) with a 250 ms timeout.
- **Xposed / LSPosed / EdXposed framework** — `/system/framework/XposedBridge.jar`, `/system/lib{,64}/libxposed_art.so`, `/system/bin/app_process{32,64}_xposed`, app data dirs for `de.robv.android.xposed.installer`, `org.meowcat.edxposed.manager`, `io.va.exposed`, `org.lsposed.manager`. `/proc/self/maps` scanned for `XposedBridge`, `libxposed`, `LSPosed`, `EdXposed`.
- **ADB daemon listening** — probes TCP `127.0.0.1:5555` with a 250 ms timeout. Catches both USB ADB and wireless-debugging configurations.

**Attack path closed.** A device with active runtime instrumentation cannot launch Zerion. The user must remove the instrumentation (or disable the toggle) to recover.

#### 11.4.3 USB Panic

Extends the existing `AntiForensics` USB monitor: when ADB or MTP/PTP is enabled while Zerion is running, fire a panic. Two scopes (presented in a confirmation dialog when the user enables the toggle):

- **Sign out only** (default). Account locks. Data preserved. Password required to re-open.
- **Sign out and WIPE account** (requires a second confirmation). Calls `signOut(handler, deleteAccount=true)`, which invokes the same `AccountWipeCleanup.wipe` path as the panic-button purge.

The USB-panic action is bound by `ZerionControllerImpl.armUsbPanicIfConfigured`, refreshed on every `ZerionActivity.onResume` so a freshly-toggled preference is honoured without restart.

**Critical fix (v2.0).** In prior versions, `AntiForensics.handleForensicAttack` zeroed memory and corrupted cache but never actually triggered the panic-wipe flow. The USB detector was wired to a no-op. v2.0 wires the detector through `armUsbPanic(Runnable)` to the real `signOut` path.

#### 11.4.4 Block screen

When any strict-boot or tamper check trips, `BaseActivity` short-circuits before Dagger injection runs, and routes to `HardenedBlockActivity`. The block screen:

- Has `FLAG_SECURE`, `excludeFromRecents`, isolated `taskAffinity=""` — leaks nothing in screenshots or recents.
- Explains the specific failure reason ("Verified boot not in GREEN state…", "Frida instrumentation detected…", etc.) via `SecureBootGuard.describe`.
- Offers two buttons: **Disable Hardened Mode** (with confirmation; clears the toggles and finishes the task) or **Quit** (`finishAndRemoveTask`).

#### 11.4.5 What Hardened Mode does NOT defend against

- **A hostile component running with Zerion's UID.** Hardened Mode's checks are themselves Java code; an attacker who already controls the app process can patch them. Hardened Mode raises the cost of *getting* there (no debugger, no Frida, no root, no unlocked bootloader).
- **Hardware-level supply-chain compromise** (e.g., a tampered SoC). Outside any software defense.
- **Compromise of the user's password.** Hardened Mode does not defend against decrypting the database when the correct password is provided.

### 11.5 Forensic-Defense Posture (Cellebrite / GrayKey / Magnet AXIOM / MSAB XRY)

| Forensic extraction tier | Defense in Zerion | Notes |
|---|---|---|
| **Cloud extraction** (UFED Cloud, GrayShift Premium cloud, AXIOM Cloud) | `allowBackup="false"`, `backup_rules.xml`, `data_extraction_rules.xml` exclude everything recursively. No account, no FCM/GCM token, no Google sync. | Nothing to extract from the cloud — Zerion is account-less. |
| **Logical extraction** (UFED logical, GrayKey unprivileged, AXIOM logical) | Everything sensitive is encrypted-at-rest: SQLCipher database, EncryptedSharedPreferences (AES-256-SIV keys / AES-256-GCM values), encrypted attachment blobs, encrypted channel content. | Logical extraction returns encrypted blobs requiring the user's password. |
| **File-system extraction** (UFED FS, AFU GrayKey, AXIOM full FS) | Same as logical — at-rest encryption holds. `secure_delete=ON` zeros freed DB pages. `MetadataStripper` strips JPEG EXIF and video metadata before any attachment is sent. Cache wipe on logout corrupts any plaintext that materialised during media playback. | The only meaningful surface is cache-during-playback. Closing the app or signing out scrubs it. |
| **Physical / chip-off extraction** | Same as logical+FS — SQLCipher + page-zeroed deletes + Argon2id-derived key. Requires brute-forcing the user's password. | Same encryption surface; no plaintext anywhere on disk. |
| **Recovery-mode `/data` dump** (bootloader-unlock bypass of app-layer wipes) | **Hardened Mode strict-boot toggle (§11.4.1)** refuses to launch on a device whose verified-boot state is not GREEN. | This is the only forensic tier that bypasses Wasted/Sentry/Zerion's own panic responder; Hardened Mode is the only way to defend. |
| **System-level usage-stats / file mtime** | Out of scope. No unprivileged Android app can suppress these. | These leak *when* Zerion was used, never *what*. |

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
- **Handshake Protocol**: v1 (classical, Briar-compat) / v2 (hybrid PQ, Zerion-only)
- **PCS Mode**: 1 / 2 / 3-Full (per-frame ML-KEM-768, v1.7+)
- **GroupTr wire**: msgTypes 32-38, 41-44 (see §12.4.1)
- **Channel wire**: pull / response / attachment / apply / comment / reaction families (see §2.5.14)
- **Database Schema**: v65 (current; lazily backfilled at first sign-in on upgrade from earlier versions)

### 12.4.1 Application Message Type Numbers

All application records carried over a pairwise contact's 1:1 client
group use the following msgType assignments. Numbers ≤ 31 are reserved
for direct conversation; 32–63 are reserved for group-chat protocol
records; new numbers must be added monotonically and never reused.

| Number | Name                          | Direction                    |
| ------ | ----------------------------- | ---------------------------- |
| 0      | PRIVATE_MESSAGE               | peer ↔ peer                  |
| 1      | ATTACHMENT                    | peer ↔ peer                  |
| 2      | VOICE_SIGNAL                  | peer ↔ peer                  |
| 3      | ATTACHMENT_MANIFEST           | peer ↔ peer                  |
| 4      | ATTACHMENT_CHUNK              | peer ↔ peer                  |
| 5      | SENDER_KEY_DISTRIBUTION       | reserved (legacy)            |
| 6      | REKEY_REQUEST                 | reserved                     |
| 7      | MESSAGE_REACTION              | peer ↔ peer                  |
| 8      | TYPING_INDICATOR              | peer ↔ peer                  |
| 9      | LINK_PREVIEW_MESSAGE          | peer ↔ peer                  |
| 32     | GROUP_POST                    | sender → each member         |
| 33     | GROUP_MEMBER_ADDED            | creator → each member        |
| 34     | GROUP_MEMBER_REMOVED          | creator → each member        |
| 35     | GROUP_MEMBER_LEFT             | leaver → each member         |
| 36     | GROUP_DISSOLVED               | creator → each member        |
| 37     | GROUP_EPOCH_COMMIT            | creator → each member        |
| 38     | GROUP_MEMBER_ROLE_CHANGED     | creator → each member        |
| 41     | GROUP_MEMBER_LIST_SNAPSHOT    | creator → each member        |
| 42     | GROUPTR_INVITE_OFFER          | inviter → invitee            |
| 43     | GROUPTR_INVITE_ACCEPT         | invitee → inviter            |
| 44     | GROUPTR_INVITE_DECLINE        | invitee → inviter            |

Channel wire types live on a separate carrier (publisher's onion) and are not msgTyped — see §2.5.14.

### 12.5 Network Parameters

| Parameter | Value | Notes |
|-----------|-------|-------|
| Max Message Size | 32 KB | Compressed |
| Max Attachment Size | 10 MB | User configurable |
| Connection Timeout | 60 seconds | Per attempt |
| Tor Circuit Timeout | 120 seconds | Bootstrap |
| Key Rotation Period | 24 hours | Mode 1/2 transport keys |
| Max Offline Queue | 10,000 messages | Per contact |
| Sync Interval (1:1 / group) | 30 seconds | When active |
| **Channel pull interval (foreground)** | **5 seconds** | First few rounds after a hit |
| **Channel pull interval (idle)** | **12 seconds** | Decay after no new posts |
| **Channel onion rotation** | **5–14 days, uniform random** | B.4, per publisher |
| **Channel pull-nonce TTL** | **5 minutes** | Replay-resistance ring buffer |
| **Channel pull-nonce ring bound** | **4,096 entries** | Per channel |
| **Channel-comment cap** | **4,096 / channel, 64 / author** | Spam gate |
| **Hardened-Mode TCP probe timeout** | **250 ms** | ADB:5555, Frida:27042 |
| **Clipboard auto-clear** | **60 seconds** | Every Zerion clipboard write |
| **Cache wipe on logout** | **Every sign-out** | `AntiForensics.wipeCachesOnLogout` |

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
│            Zerion Application Layer                     │
│  - Private Messaging   - Group Chat (GroupTr)           │
│  - Channels (pub/sub)  - Voice & Video Calls            │
│  - Vault Manager       - Contact Manager                │
│  - Hardened Mode       - AntiForensics                  │
└───────────────────────┬─────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────┐
│             Bramble Protocol Layer                      │
│  - Crypto Component (hybrid PQ)                         │
│  - Triple-Ratchet PCS (Mode 1/2/3-Full)                 │
│  - SQLCipher Database   - Event Bus                     │
│  - Identity Manager     - Settings Manager              │
└───────────────────────┬─────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────┐
│               Transport Plugin                          │
│  - Tor v3 onion (sole transport)                        │
│  - Bluetooth, LAN TCP, removable-drive REMOVED (v1.6.2) │
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
├── SqlCipherDatabase.java
└── Migration.java
```

**Vault**:
```
zerion-android/src/main/java/com/professor/zerion/android/vault/
├── VaultManager.java
├── VaultViewModel.java
├── crypto/
│   ├── VaultCrypto.java
│   ├── VaultKeystore.java
│   └── Argon2.java              # Now routes through Bouncy Castle Argon2BytesGenerator
│                                  (real Argon2id, 256 MB memory, 3 iters) since v1.6.0.
│                                  Earlier releases used PBKDF2-HMAC-SHA256 under the
│                                  same class name as a documented placeholder.
├── storage/SecureFileIO.java
├── utils/
│   ├── MetadataStripper.java    # EXIF + video metadata stripping
│   └── SecureMemory.java        # Random-overwrite then zero-fill
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
├── OpusEncoder.java               # Opus audio encoder
├── OpusDecoder.java               # Opus audio decoder
└── VoiceCallCrypto.java          # Audio encryption/decryption
```

**Contact Addition**:
```
briar-android/src/main/java/com/professor/zerion/android/contact/add/
├── nearby/AddNearbyContactActivity.java
├── remote/LinkExchangeFragment.java
└── remote/AddContactViewModel.java
```

**Channels (new in v2.0)**:
```
briar-api/src/main/java/org/briarproject/briar/api/channel/
├── ChannelManager.java               # API surface (createChannel, publishPost,
│                                       postComment, areDiscussionsEnabled, ...)
├── ChannelState.java                 # Persistent state model
├── ChannelPost.java                  # Signed post record
├── ChannelComment.java               # Signed comment record
├── ChannelReaction.java              # Signed emoji reaction
├── ChannelInviteLink.java            # Invite-link parser/encoder
├── ChannelDelegationCert.java        # Editor-delegation certificate
└── ChannelSubscriber.java            # Roster entry

briar-core/src/main/java/org/briarproject/briar/channel/
├── ChannelManagerImpl.java           # Publisher + subscriber main impl
├── ChannelPullProtocol.java          # Pull request / response wire codec orchestration
├── ChannelPullCodec.java             # BDF encode / decode for pull frames
├── ChannelCodec.java                 # Signed-input canonical encoders
├── ChannelSignatures.java            # Hybrid Ed25519 + ML-DSA-65 sign / verify
├── ChannelContentKey.java            # Per-channel AES-256-GCM content key + envelope wrap
├── ChannelChainVerifier.java         # prevHash chain validator
├── ChannelHmacChallenge.java         # Pull-challenge HMAC helper
├── ChannelStore.java                 # Channel state + post namespace store
├── ChannelBlobStore.java             # Encrypted attachment blob store
├── ChannelCommentStore.java          # Per-channel comment namespace
├── ChannelDiscussionStore.java       # Per-channel discussions-enabled gate (v2.0)
├── ChannelReactionStore.java         # Per-channel reaction namespace
├── ChannelSubscriberStore.java       # Subscriber roster + ban list
├── ChannelApplicationStore.java      # Closed-channel pending applications
├── ChannelMyApplicationsStore.java   # Subscriber's own application state
├── ChannelTombstoneStore.java        # Channel-level tombstones
├── ChannelPostTombstoneStore.java    # Per-post tombstones
├── ChannelSelfAnnounceStore.java     # Subscriber display-name claims
├── ChannelTransport.java             # Onion request/response carrier
└── ChannelPostValidator.java         # Validate inbound post signatures + chain

zerion-android/src/main/java/com/professor/zerion/android/channel/
├── ChannelFeedActivity.java          # Channel reader / publisher composer UI
├── ChannelCommentsActivity.java      # Discussion thread UI per post (v2.0)
├── ChannelListFragment.java          # Channel list / search / create entry
├── ChannelInviteHandlerActivity.java # Parse incoming invite links
├── ChannelInviteSpanUtil.java        # Render invite links + copy-to-clipboard
├── ChannelSubscribersActivity.java   # Publisher roster admin
├── ChannelPendingApplicationsActivity.java  # Closed-channel approvals
└── ChannelDelegationsActivity.java   # Editor management
```

**Hardened Mode (new in v2.0)**:
```
zerion-android/src/main/java/com/professor/zerion/android/security/
├── SecureBootGuard.java              # Verified-boot + tamper detectors
├── HardenedModeEvaluator.java        # Per-toggle eval + preference keys
├── HardenedBlockActivity.java        # Refuse-to-start block screen
├── AntiForensics.java                # USB monitor + cache corruption (wipe-on-logout
│                                       wired in v2.0; armUsbPanic / disarmUsbPanic)
└── SecurityManager.java              # Screenshot protection + lockout
```

**Forensic-defense helpers**:
```
zerion-android/src/main/java/com/professor/zerion/android/util/
├── SecureClipboard.java              # 60-s auto-clear + EXTRA_IS_SENSITIVE (v2.0)
└── BrowserGuard.java                 # External-URL warning dialog
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

**Channels (v2.0)**:
- Publisher → subscriber broadcast over the publisher's own Tor v3 onion
- Hybrid Ed25519 + ML-DSA-65 signatures on every post, comment, and reaction
- Closed channels with publisher approval + per-channel content-key envelope
- Editor delegations (publisher authorizes co-publishers without sharing the publisher private key)
- Per-channel discussion-thread toggle (Telegram-style)
- Replay-resistant pull challenge (5-min nonce TTL, 4,096-entry ring per channel)
- Wire-level scrubbing of `joinCapability` from closed-channel responses

**Vault Integration**:
- Unified secure storage
- Metadata stripping (EXIF, video)
- Multiple item types
- Export/import with separate password

**P2P Architecture**:
- No single point of failure
- No data on third-party servers
- Offline message queuing
- Resilient communication

**P2P Voice Calling**:
- End-to-end encrypted voice calls over Tor
- Opus codec (24 kbps VOIP mode via Concentus)
- Automatic echo cancellation, noise suppression, AGC
- No third-party STUN/TURN servers
- Forward secrecy with per-call keys
- Low-latency optimized for Tor (~100-200ms)
- Dedicated VOICE_SIGNAL protocol (separate from messaging)
- Screenshot protection during calls
- Speakerphone toggle with volume boost

**Hardened Mode (v2.0)**:
- Strict boot verification (refuse-to-start if `verifiedbootstate ≠ "green"`)
- Tamper detection (debugger, root, Magisk, Frida, Xposed, ADB daemon)
- USB-panic (sign-out or full wipe when ADB / MTP is enabled at runtime)
- Block screen with `FLAG_SECURE`, `excludeFromRecents`, explicit failure reason

**Privacy by Design**:
- No telemetry
- No crash reporting
- No analytics
- No logging of any kind (JUL silenced in static block; no `android.util.Log`, no Timber, no `System.out/err`)
- Tor-only transport (Bluetooth, LAN TCP, removable-drive, dev-reporting all removed)
- Open source (auditable), licensed under the **GNU General Public License v3 (GPLv3)**
- Localized in **35+ languages** with full RTL layout support

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
- Bouncy Castle 1.82 (cryptography: ML-KEM-768, ML-DSA-65, Argon2id, BLAKE2b). Upgrade to 1.84 is tracked as a supply-chain-verified follow-up — Zerion does not exercise the BC 2026 CVE code paths (no LDAP, no PGP, no FrodoKEM).
- i2p.crypto.eddsa (Ed25519)
- Curve25519-java (X25519)
- Tor Expert Bundle (anonymity) — `tor-android 0.4.8.22`, `lyrebird-android 0.6.2`, `onionwrapper 0.1.4`
- **SQLCipher for Android `net.zetetic:sqlcipher-android 4.13.0`** (encrypted database)
- Concentus (pure-Java Opus codec for voice calls)
- In-tree `EncryptedSharedPreferences` implementation over the Android Keystore master key (replaced the deprecated AndroidX `security-crypto 1.1.0` library in v2.0.2 — DONE; the deprecated dependency is no longer shipped)

**Dependency verification**: `gradle/verification-metadata.xml` pins SHA-256 + SHA-512 per artifact with `<verify-signatures>true</verify-signatures>`. New dependencies require a signature check against the publisher's PGP key before the hash entry is added.

**Security Research**:
- Signal Protocol (inspiration)
- Briar Project (base architecture)
- Tor Project (anonymity network)

---

**Document Information**:
- **Version**: 3.3
- **Date**: July 3, 2026
- **Status**: Production
- **Classification**: Public Technical Documentation
- **Author**: Zerion Project
- **Contact**: https://github.com/zerionproject/Zerion
- **Corresponds to app release**: v2.0.6 (versionCode 20006)

**Document History**:
- **v3.3 (2026-07-03)**: Updated for app release v2.0.6 (versionCode 20006). Adds §0 entries for v2.0.3 through v2.0.6 (reliability, Tor, account-backup, and the v2.0.6 contact-add hybrid rendezvous, ratchet stabilization, connection-reliability, voice-memo, and hardening changes). Corrects the version-1 contact-add link description: the link carries a 32-byte commitment plus a 32-byte X25519 rendezvous key (104 base32 chars), and the rendezvous key agreement is a real X25519 Diffie-Hellman.
- **v3.2 (2026-06-02)**: **Channels, Hardened Mode, forensic-defense tightening.** Adds full §2.5 Channels section (publisher → subscriber broadcast over a per-publisher Tor onion, hybrid-signed posts, closed-channel HMAC manifest gate, replay-resistant pull challenge, editor delegations, discussion threads, subscriber approvals, attachments, onion rotation, tombstones). Adds §11.4 Hardened Mode (strict boot verification, tamper detection, USB panic) and §11.5 Forensic-Defense Posture table mapping defenses against Cellebrite / GrayKey / Magnet AXIOM / MSAB XRY tiers. Corrects §9.1 (database is SQLCipher, not H2/HyperSQL — prior revisions had this wrong). Updates §5.3 to v1.7 per-message ML-KEM (was pre-v1.7 25-message epoch). Updates §11.1 threat-model table to include forensic-tool and tamper categories; moves "device compromise" from out-of-scope to partial-mitigation via Hardened Mode. Updates §12.4 protocol versions (schema v63, PCS Mode 3-Full). Updates §12.5 network parameters with channel-specific cadences. Updates §13.1 architecture diagram (Tor-only transport, no Bluetooth / LAN-TCP / removable-drive). Adds new §14 file-path subsections for channels, Hardened Mode, and forensic-defense helpers. Adds §0 v2.0 entry.
- **v3.0 (2026-05-15)**: **Mode 3 PQ rotation, hybrid group signatures, native group invites, Tor-only transport.** Adds §0 "Updates since v2.1" covering everything shipped between Dec 2025 and May 2026: B.3 hybrid pairing and B.4 onion rotation (v1.5.0), PCS Mode 3 end-to-end completion + hybrid Ed25519+ML-DSA-65 signatures on every group record + real Argon2id vault KDF (v1.6.0), whole-app audit + GroupTr hardening (v1.6.1), native group-invite protocol replacing the legacy `privategroup.invitation` carrier + Tor-only transport (Bluetooth / Wi-Fi LAN / removable-drive / dev-reporting removed) + all `SharedPreferences` keystore-encrypted + extended hybrid signing + downgrade-lock token fix (v1.6.2). See §0 for the authoritative diff.
- v2.1 (2025-12-16): **Version Negotiation & Security Hardening** - Added Briar compatibility via explicit contact type selection, downgrade attack prevention, contact security level tracking (postQuantum flag), UI security indicator in Chat Settings
- v2.0 (2025-11-26): **Full hybrid post-quantum cryptography** - Phase 2 complete with ML-KEM-768 + X25519 key exchange and ML-DSA-65 + Ed25519 signatures (NIST FIPS 203/204)
- v1.4 (2025-11-26): Post-quantum Phase 1 - Argon2id KDF migration, BLAKE2b-384 hash option, automatic Scrypt→Argon2id migration
- v1.3 (2025-11-26): Updated voice calling with dedicated VOICE_SIGNAL protocol, Opus codec, screenshot protection, speakerphone improvements
- v1.2 (2025-01-14): Added Opus codec integration and network quality indicators
- v1.1 (2025-01-10): Added voice call documentation
- v1.0 (2025-11-10): Initial comprehensive whitepaper

---

*This whitepaper is based on the Zerion codebase as of v2.0.6 (versionCode 20006, July 2026). For the most current information, please refer to the source code repository and the per-document amendments under [docs/](.).*

**End of Document**
