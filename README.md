# Zerion

**Anonymous. Encrypted. Post-Quantum Ready.**

<p align="center">
  <img src="zerion-android/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" width="120" height="120">
</p>

Zerion is a secure messaging app and encrypted vault designed for people who need private, anonymous, censorship-resistant communication.

Unlike traditional messengers, Zerion uses no servers, no accounts, no phone numbers, and no cloud services. All communication flows directly between devices using the Tor network, protecting users from surveillance, metadata collection, and IP exposure.

With hybrid post-quantum cryptography, post-compromise security (Triple Ratchet with ML-KEM-768), hardware-backed vault protection, and advanced anti-forensics features, Zerion provides strong security even against sophisticated adversaries.

---

## Why Zerion?

- **Truly anonymous** — No phone number, email, or registration
- **End-to-end encrypted** messaging, groups, voice notes, P2P voice and video calls
- **Post-Compromise Security** — Triple Ratchet (X25519 DH + ML-KEM-768 PQ) for per-message key evolution
- **Tor-only networking** — Your IP address is never exposed to contacts
- **Direct peer-to-peer architecture** — No central servers
- **Encrypted Vault** for passwords, documents, media, and notes
- **Post-quantum hardened** — Hybrid ML-KEM-768 + X25519, ML-DSA-65 + Ed25519
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
- **Mode 2 (Double Ratchet)**: X25519 DH ratchet for forward secrecy and post-compromise security.
- **Mode 3 (Triple Ratchet)**: Active for Zerion↔Zerion contacts. Adds ML-KEM-768 post-quantum ratchet on top of Mode 2 for quantum-resistant post-compromise security.

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

**[Download APK](https://github.com/zerionproject/Zerion/releases/latest)** — v1.0.10 (direct from GitHub)

**F-Droid:** [fdroid.zerion.chat](https://fdroid.zerion.chat/fdroid/repo)
```
Repo fingerprint: D7FDB11125890D133AE89D8BA4F4331D9045E21EF01D9899A7CDEE6888F704C8
```

---

## Changelog

**v1.0.10 (Latest):**
- Now available on Google Play Store
- Fixed local self-view rotation during video calls (correct formula for front/back camera)
- Fixed camera switch race condition: `onClosed()` callback ensures hardware is fully released before reopening
- Descriptive camera error messages (ERROR_CAMERA_IN_USE, ERROR_CAMERA_DISABLED, etc.)
- Edge-to-edge rendering support for Android 15+ (SDK 35 compliance)
- Removed deprecated `setStatusBarColor`/`setNavigationBarColor` API usage
- Removed screen orientation lock from VoiceCallActivity (supports large screens/foldables)
- Vault UI refinements: cleaner onboarding text, minimal labels

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
- [PCS Design](docs/PCS_DESIGN.md) — Post-Compromise Security (Triple Ratchet) specification
- [Triple Ratchet Design](docs/TRIPLE_RATCHET_DESIGN.md) — Mode 3 ML-KEM-768 ratchet specification
- [P2P Voice & Video Calls](docs/P2P_Voice_Calls_Documentation.md) — Voice and video calling specification

---

## License

Zerion is free and open-source under the **GPLv3** license.
