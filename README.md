# Zerion

**Anonymous. Encrypted. Post-Quantum Ready.**

<p align="center">
  <img src="zerion-android/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" width="120" height="120">
</p>

Zerion is a secure messaging app and encrypted vault designed for people who need private, anonymous, censorship-resistant communication.

Unlike traditional messengers, Zerion uses no servers, no accounts, no phone numbers, and no cloud services. All communication flows directly between devices using the Tor network, protecting users from surveillance, metadata collection, and IP exposure.

With hybrid post-quantum cryptography on **every message** (Mode 3-Full: per-frame ML-KEM-768 encapsulation mixed into the body AEAD key), post-compromise security via the Triple Ratchet, hardware-backed vault protection, and advanced anti-forensics features, Zerion provides strong security even against sophisticated adversaries — including "harvest now, decrypt later" attacks by future quantum adversaries.

---

## Why Zerion?

- **Truly anonymous** — No phone number, email, or registration
- **End-to-end encrypted** messaging, groups, voice notes, P2P voice and video calls
- **Per-message post-quantum hybrid ratchet (Mode 3-Full)** — Every frame in both directions carries a fresh ML-KEM-768 encapsulation against the peer's current ML-KEM public key; the shared secret is mixed into the body AEAD key on every frame
- **Post-Compromise Security** — Triple Ratchet (X25519 DH + per-message ML-KEM-768 PQ) for per-message key evolution
- **Tor-only networking** — Your IP address is never exposed to contacts
- **Direct peer-to-peer architecture** — No central servers
- **Encrypted Vault** for passwords, documents, media, and notes
- **Post-quantum hardened end-to-end** — Hybrid ML-KEM-768 + X25519 at handshake, introductions, and on every transport frame; ML-DSA-65 + Ed25519 on every signed record
- **Zerion-only** — Purpose-built for Zerion-to-Zerion communication with maximum security
- **Downgrade attack protection** — PQ contacts stay PQ-secure forever
- **Anti-forensics protection** against mobile extraction tools
- **Open-source and auditable**

**Zerion collects zero personal data. Not by policy — by cryptographic design.**

---

## Core Features

### Encrypted Messaging

Private one-to-one chats and groups with end-to-end encryption using XSalsa20-Poly1305 (256-bit keys).
Disappearing messages and metadata removal ensure conversations remain confidential.

### Post-Compromise Security (PCS)

Zerion implements a Triple Ratchet protocol for post-compromise security:

- **Forward secrecy**: Past messages stay private even if your device is later compromised
- **Post-compromise recovery**: If an attacker compromises your device, security is restored after one message round-trip
- **Per-message keys**: Every message uses a unique encryption key derived from the current chain state

**Ratchet Modes:**
- **Mode 2 (Double Ratchet)**: X25519 DH ratchet for forward secrecy and classical post-compromise security.
- **Mode 3 (Triple Ratchet, per-epoch PQ)**: Adds ML-KEM-768 post-quantum ratchet every 25 messages or 24 hours. Retained as a fallback path.
- **Mode 3-Full (Triple Ratchet, per-message PQ — current default since v1.7)**: Every single frame in both directions carries a fresh ML-KEM-768 encapsulation. The per-stream chain key, the per-message body AEAD key, and the underlying X25519 ratchet all combine into a hybrid that requires breaking both X25519 and ML-KEM-768 — on every frame, not just at epoch boundaries.

### P2P Voice & Video Calls

Real peer-to-peer encrypted voice and video calls routed exclusively through Tor hidden services.
No STUN, no TURN, no VoIP servers — just private communication between devices.

- **Voice calls**: Opus codec at 32kbps, AES-256-GCM encrypted, ~100-200ms latency
- **Video calls**: H.264 at 320x240 15fps, AES-256-GCM encrypted with padded frames
- Camera switching, video pause/resume, and correct portrait orientation
- All frame metadata encrypted inside the payload — zero plaintext metadata on wire

### Secure Vault

A hardware-backed encrypted vault for passwords, notes, photos, videos, and documents.
Uses Argon2id, AES-256-GCM, and StrongBox/Keystore integration for strong protection.

### Post-Quantum Security

All Zerion contacts use full post-quantum security:
- **ML-KEM-768 + X25519** hybrid key encapsulation for quantum-resistant key exchange
- **ML-DSA-65 + Ed25519** hybrid signatures for quantum-resistant authentication
- **PCS Mode 3 (Triple Ratchet)** for per-message key evolution with quantum-resistant post-compromise security

### Downgrade Attack Protection

Once a contact is established with post-quantum security, it stays that way.
Any attempt to reconnect with weaker security is automatically blocked.

---

## Download Zerion

**[Google Play](https://play.google.com/store/apps/details?id=com.professor.zerion)** — Get it on the Play Store

**[Download APK](https://github.com/zerionproject/Zerion/releases/latest)** — v1.7.0 (direct from GitHub)

**F-Droid:** [fdroid.zerion.chat](https://fdroid.zerion.chat/fdroid/repo)
```
Repo fingerprint: D7FDB11125890D133AE89D8BA4F4331D9045E21EF01D9899A7CDEE6888F704C8
```

---

## Changelog

**v1.7.0 (Latest, May 2026):**
- **Headline:** Mode 3-Full per-message hybrid ratchet is now the default. Every frame in both directions carries a fresh ML-KEM-768 encapsulation; the decapsulated secret is mixed into the body AEAD key on every message. A single compromised key cannot decrypt any other message in the conversation, past or future.
- Group chat unread counter — Groups list now shows an unread badge per group (1, 2, 3, …, 99+); clears on open
- Multi-profile end-to-end polish: profile create, sign-in, switch and recovery paths reliable across the full lifecycle; profiles with missing display names heal automatically on next login
- Internationalisation: vault confirmation keywords and dialog strings route through string resources; case-insensitive confirmation match
- Accessibility: voice call control buttons (mute, speaker, video, switch camera, end, accept, decline) labelled for screen readers
- Streamlined the per-chat actions menu so every item maps to a real, user-visible behaviour
- Build-time zero-logging guarantee: a Gradle gate fails the build if any production source file references a logger, `Timber`, `android.util.Log`, or `System.err`/`System.out`
- Wire-compatible with 1.6.x peers (Mode 2 fallback when the peer is older); no vault or DB schema changes; signing key unchanged

**v1.6.2 (May 2026):**
- Native group-invite protocol replaces the legacy carrier (`OFFER`/`ACCEPT`/`DECLINE` on the 1:1 channel)
- Kick reliability fix: invitee epoch desync that silently dropped `MEMBER_REMOVED` is closed; removed users are purged from the local device atomically
- Tor-only transport — Bluetooth, Wi-Fi LAN, removable-drive sync, and dev-reporting subsystems removed
- All `SharedPreferences` routed through Android Keystore-backed `EncryptedSharedPreferences`
- Hybrid Ed25519 + ML-DSA-65 signatures extended to private-group and invitation contexts
- Carry-forward downgrade-lock token reconstruction fix
- Vault, biometric, and lock-screen audit findings patched
- Supply-chain: `junit-bom-5.11.4` pinned by SHA-256 in dependency-verification metadata

**v1.6.0 (May 2026):**
- PCS Mode 3 post-quantum ratchet now completes end-to-end (responder dispatch, shared-secret persistence, state callbacks); ML-KEM-768 mixed into the root key every 25 messages or 24 hours, both directions
- Hybrid Ed25519 + ML-DSA-65 signatures on every group record (3,373 bytes)
- Vault password KDF migrated from PBKDF2 placeholder to real Argon2id
- DB schema v62 → v63 (nullable ML-DSA columns, lazy-backfill on first login)
- Critical/high/medium audit findings patched before tag

**v1.5.0 (May 2026):**
- B.3 hybrid pairing: ML-KEM-768 + X25519 contact handshake with downgrade defense
- B.4 onion rotation: Tor v3 onion address rotates every 5–14 days to defeat long-term linkability
- Hybrid identity proofs at first pair (Ed25519 + ML-DSA-65)
- Per-direction PQ epoch infrastructure (groundwork for the v1.6.0 completion fix)

**v1.2.0:**
- Security hardening: video call camera deadlock fixed, password handling uses char[] throughout
- Registration Lock: protect your account with PIN or password (PBKDF2-SHA256)
- App icon changer: disguise as Calculator, Notes, or Weather
- Chat text size chooser and bubble color picker
- Navigation bar size setting
- Invite Friends sharing feature
- Edge-to-edge rendering for Android 15 (SDK 35)
- Link previews default OFF (fetched via Tor when enabled)
- Removed QR/zxing dependency, Bluetooth, Wi-Fi hotspot dead code
- Cleaned 2,100+ dead localized strings across 47 languages

**v1.0.10:**
- Now available on Google Play Store
- Fixed local self-view rotation during video calls
- Fixed camera switch race condition
- Vault UI refinements

**v1.0.9:**
- UI/UX improvements: rich empty states with icons across all list screens
- Conversation empty state with contextual action prompt
- zVault branding: updated all labels to match minimalist style

**v1.0.8:**
- Auto-wipe on max login attempts is now immediate (no confirmation dialog required)
- Forensic tool detection (Cellebrite, GrayKey, ADB, USB data transfer) now triggers immediate app lock
- Message clipboard auto-clears after 60 seconds
- Emergency file corruption now overwrites entire file contents with secure flush

**v1.0.7:**
- Fixed self-view rotation during video calls (front camera formula corrected)
- Fixed spurious "Camera error" toast appearing after hanging up a video call
- Fixed call timer overlapping local video preview pip

**v1.0.6:**
- Video call security: AES-GCM authentication failure detection (stream integrity)
- Video encoder drain thread: clean shutdown with EOS flag
- Video decoder: consecutive failure tracking, codec error detection
- Auth screen: FLAG_SECURE added to prevent screenshot leakage
- Password handling: char[] passed directly to strength estimator, no String copy

**v1.0.5:**
- Video call quality: 640x480 @ 24fps / 600kbps, H.264 Main Profile Level 3.1
- Remote video rotation: per-frame rotation metadata
- Camera switch: async callback ensures correct transform after front/back switch
- Video call UX: mute/speaker active state indicators, auto-speaker on video start
- VoiceCallService: fix SecretKey zeroing, TorConnection/AudioRecord threading races

**v1.0.4:**
- P2P encrypted video calls over Tor
- Crypto-protocol hardening: 8 vulnerabilities fixed
- Voice signal ephemeral cleanup, zero-log CI enforcement

**Planned:**
- Multi-device sync
- File transfer improvements
- UI/UX refinements

---

## Documentation

- [Technical Whitepaper](docs/ZERION_TECHNICAL_WHITEPAPER.md) — Complete architecture & crypto design
- [Security Overview](docs/SECURITY_OVERVIEW.md) — Per-version security status and audit notes
- [PCS Design](docs/PCS_DESIGN.md) — Post-Compromise Security (Triple Ratchet) specification
- [Ratchet Modes](docs/RATCHET_MODES.md) — Mode 1 / 2 / 3 layered explainer
- [Triple Ratchet Design](docs/TRIPLE_RATCHET_DESIGN.md) — Mode 3 ML-KEM-768 ratchet specification
- [Group Triple Ratchet (PQ)](docs/GROUP_TRIPLE_RATCHET_PQ_DESIGN.md) — Hybrid-signed group records
- [GroupTr Wire Protocol](docs/wire/GROUPTR_WIRE_PROTOCOL.md) — Native group-invite + membership messages
- [P2P Voice & Video Calls](docs/P2P_Voice_Calls_Documentation.md) — Voice and video calling specification

---

## Support Zerion

Zerion has no investors, no ads, no subscription, and no telemetry. The project is funded entirely by donations from people who value private communication. If Zerion is useful to you, please consider supporting development:

**Bitcoin (BTC)**
```
bc1qkjgzqmgrgtq3wh2qhrtmsg50cfrlcsssn5u97y
```

**Bitcoin Lightning**
```
Zerion@cake.cash
```

**Monero (XMR)**
```
83yVsTFT8tt8m9UBQ5KUP9hKHcNASFRvN3ewzUraTFb2TXq1BkeCvUucUusTA1dmgsJjWKGLt3s9AMF5bp15Qh1P9fNY4bF
```

**Ethereum / USDT (ERC-20)**
```
0xE80e802736d759847918EcBD90457E6aAa5Cca45
```

Donations fund security audits, infrastructure (F-Droid repo, onion-rotation testing, signing keys), and continued development of new privacy features. Thank you.

More details and copy-to-clipboard buttons: [zerion.chat/donate.html](https://zerion.chat/donate.html)

---

## License

Zerion is free and open-source under the **GPLv3** license.
