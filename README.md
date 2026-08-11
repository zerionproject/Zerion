# Zerion

**Anonymous. Encrypted. Post-Quantum Ready.**

<p align="center">
  <img src="zerion-android/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" width="120" height="120">
</p>

Zerion is a secure messaging app and encrypted vault designed for people who need private, anonymous, censorship-resistant communication.

Unlike traditional messengers, Zerion uses no servers, no accounts, no phone numbers, and no cloud services. All communication flows directly between devices using the Tor network, protecting users from surveillance, metadata collection, and IP exposure.

With hybrid post-quantum cryptography on **every message** (Mode 3-Full: per-frame ML-KEM-768 encapsulation mixed into the body AEAD key), post-compromise security via the Triple Ratchet, hardware-backed vault protection, and advanced anti-forensics features, Zerion provides strong security even against sophisticated adversaries - including "harvest now, decrypt later" attacks by future quantum adversaries.

---

## Why Zerion?

- **Truly anonymous** - No phone number, email, or registration
- **End-to-end encrypted** messaging, groups, voice notes, P2P voice and video calls
- **Per-message post-quantum hybrid ratchet (Mode 3-Full)** - Every frame in both directions carries a fresh ML-KEM-768 encapsulation against the peer's current ML-KEM public key; the shared secret is mixed into the body AEAD key on every frame
- **Post-Compromise Security** - Triple Ratchet (X25519 DH + per-message ML-KEM-768 PQ) for per-message key evolution
- **Tor-only networking** - Your IP address is never exposed to contacts
- **Direct peer-to-peer architecture** - No central servers
- **Encrypted Vault** for passwords, documents, media, and notes
- **Channels** - one-to-many broadcast (public or private) with optional discussion threads, reactions, and editor delegations
- **Post-quantum hardened end-to-end** - Hybrid ML-KEM-768 + X25519 at handshake, introductions, and on every transport frame; ML-DSA-65 + Ed25519 on every signed record
- **Zerion-only** - Purpose-built for Zerion-to-Zerion communication with maximum security
- **Downgrade attack protection** - PQ contacts stay PQ-secure forever
- **Anti-forensics protection** against mobile extraction tools
- **Open-source and auditable**

**Zerion collects zero personal data. Not by policy - by cryptographic design.**

---

## Core Features

### Encrypted Messaging

Private one-to-one chats and groups with end-to-end encryption using XSalsa20-Poly1305 (256-bit keys).
Disappearing messages and metadata removal ensure conversations remain confidential.
Photos, videos, voice notes, documents, and stickers; securely introduce two of your contacts to each other; translated into 35+ languages.

### Post-Compromise Security (PCS)

Zerion implements a Triple Ratchet protocol for post-compromise security:

- **Forward secrecy**: Past messages stay private even if your device is later compromised
- **Post-compromise recovery**: If an attacker compromises your device, security is restored after one message round-trip
- **Per-message keys**: Every message uses a unique encryption key derived from the current chain state

**Ratchet Modes:**
- **Mode 2 (Double Ratchet)**: X25519 DH ratchet for forward secrecy and classical post-compromise security.
- **Mode 3 (Triple Ratchet, per-epoch PQ)**: Adds ML-KEM-768 post-quantum ratchet every 25 messages or 24 hours. Retained as a fallback path.
- **Mode 3-Full (Triple Ratchet, per-message PQ - current default since v1.7)**: Every single frame in both directions carries a fresh ML-KEM-768 encapsulation. The per-stream chain key, the per-message body AEAD key, and the underlying X25519 ratchet all combine into a hybrid that requires breaking both X25519 and ML-KEM-768 - on every frame, not just at epoch boundaries.

### P2P Voice & Video Calls

Real peer-to-peer encrypted voice and video calls routed exclusively through Tor hidden services.
No STUN, no TURN, no VoIP servers - just private communication between devices.

- **Voice calls**: Opus codec at 24 kbps (16 kHz mono), AES-256-GCM encrypted
- **Video calls**: H.264 Main Profile (Level 3.1) at 640×480, AES-256-GCM encrypted with padded frames; adaptive frame rate and bitrate that step down under poor network conditions
- Camera switching, video pause/resume, and correct portrait orientation
- All frame metadata encrypted inside the payload - zero plaintext metadata on wire

### Channels

A one-to-many broadcast layer (one person writes, many people read) served from the publisher's own Tor onion. Subscribers pull posts directly over Tor and verify their signatures. There is no central server holding posts or the subscriber list.

- **Public channels**: anyone with the invite link can subscribe
- **Private channels**: subscribers request to join and the owner approves
- **Discussion threads**: the owner decides, per channel, whether subscribers can reply under a post
- **Reactions, pinned posts, and attachments**
- **Editor delegations**: let trusted people post without sharing your identity key
- **No subscriber-to-subscriber metadata**: subscribers never see one another

### Secure Vault

A hardware-backed encrypted vault for passwords, notes, photos, videos, and documents.
Uses Argon2id, AES-256-GCM, and StrongBox/Keystore integration for strong protection.

### Post-Quantum Security

All Zerion contacts use full post-quantum security:
- **ML-KEM-768 + X25519** hybrid key encapsulation for quantum-resistant key exchange
- **ML-DSA-65 + Ed25519** hybrid signatures for quantum-resistant authentication
- **PCS Mode 3-Full (Triple Ratchet, per-message ML-KEM-768)** for per-message key evolution with quantum-resistant post-compromise security

### Downgrade Attack Protection

Once a contact is established with post-quantum security, it stays that way.
Any attempt to reconnect with weaker security is automatically blocked.

---

## Download Zerion

**[Google Play](https://play.google.com/store/apps/details?id=com.professor.zerion)** - Get it on the Play Store

**[Download APK](https://github.com/zerionproject/Zerion/releases/latest)** - latest release (direct from GitHub)

**[F-Droid](https://f-droid.org/packages/com.professor.zerion/)** - Get it on F-Droid
```
APK signing fingerprint: D7FDB11125890D133AE89D8BA4F4331D9045E21EF01D9899A7CDEE6888F704C8
```

---

## Changelog

**v3.0 (in development):**
- A network protocol written in-house: fixed-size 4096-byte frames, constant-rate cover traffic so idle and active connections look identical on the wire, and per-message hybrid post-quantum encryption, all over Tor with no servers
- Keeps the post-quantum ratchet and the delivery database from the 2.x line
- Two additional transports are in the tree and under test before release: a Bluetooth offline mesh (message with no internet at all) and an opt-in I2P path; Tor stays mandatory and always on
- Launching soon; the 2.x line remains the current public release until then

**v2.0.7 (Latest release, July 2026):**
- Fixes a display bug where the decoy calculator keypad could render blank in portrait on some narrower screens (reported on HyperOS and GrapheneOS). No protocol change, no database upgrade, signing key unchanged

**v2.0.6 (July 2026):**
- Adding a contact is reliable again; connections recover immediately after a drop so contacts stay online more consistently; voice memos deliver reliably
- Contact pairing and the per-message post-quantum encryption were audited and hardened, and the release went through a full code and security review
- Both people need this version to add each other. No database upgrade

**v2.0.5 (June 2026):**
- Account backup reliability fix: backups work reliably across devices and report a clear error if anything goes wrong. No change to backup encryption, no protocol change, no database upgrade

**v2.0.4 (June 2026):**
- Back up your whole account to an encrypted file, or move it straight to a new phone over Tor
- Faster, more reliable connections through bridges in censored regions; quicker message delivery; smoother group chats with Enter-to-send and a tappable key fingerprint; app lock can hold for up to 24 hours in the background
- Full code and security review. No protocol change, no database upgrade

**v2.0.3 (June 2026):**
- Voice calls work cleanly in both directions and are on by default; opt-in video-calling beta
- Mute a channel, search and sort the vault, save channel post drafts, set a default disappearing timer, see pending group invites
- Lower battery and memory use, faster start, and a set of crash fixes. No protocol change, no database upgrade

**v2.0.2 (June 2026):**
- Channels now raise system notifications for new posts (subscribers) and new comments (owners), with a global Channels toggle and per-channel mute
- Group chats are resilient under concurrent admin actions - adding a member while another is removed, or messaging during a membership change, no longer splits the member list; invitees see the current roster immediately on accept
- At-rest encrypted preferences moved to an in-tree implementation, replacing the deprecated AndroidX `security-crypto` library (one-time settings reset on upgrade; conversations, channels, groups, contacts, and vault are unaffected)
- Exit from the foreground notification now reliably reopens cleanly on next launch

**v2.0.1 (June 2026):**
- Build hygiene for F-Droid main-repo distribution: the PhotoView library moved from a vendored binary to source, keeping a single signing key across Play Store, GitHub, and F-Droid so users can switch channels without reinstalling

**v2.0.0 (June 2026):**
- **Channels** - a publisher-to-subscriber broadcast layer with optional discussion threads; public or private, owner-approved subscribers, reactions, pinned posts, attachments, and editor delegations (post without sharing your identity key); subscribers never see one another
- **Hardened mode (opt-in)** - refuse to start on tampered devices, under a debugger/root/hooking framework, or when USB debugging/file transfer is enabled
- Cache wipe on sign-out, 60-second clipboard auto-clear, plain-language copy throughout

**v1.7.0 (May 2026):**
- **Headline:** Mode 3-Full per-message hybrid ratchet is now the default. Every frame in both directions carries a fresh ML-KEM-768 encapsulation; the decapsulated secret is mixed into the body AEAD key on every message. A single compromised key cannot decrypt any other message in the conversation, past or future.
- Group chat unread counter - Groups list now shows an unread badge per group (1, 2, 3, …, 99+); clears on open
- Multi-profile end-to-end polish: profile create, sign-in, switch and recovery paths reliable across the full lifecycle; profiles with missing display names heal automatically on next login
- Internationalisation: vault confirmation keywords and dialog strings route through string resources; case-insensitive confirmation match
- Accessibility: voice call control buttons (mute, speaker, video, switch camera, end, accept, decline) labelled for screen readers
- Streamlined the per-chat actions menu so every item maps to a real, user-visible behaviour
- Build-time zero-logging guarantee: a Gradle gate fails the build if any production source file references a logger, `Timber`, `android.util.Log`, or `System.err`/`System.out`
- Wire-compatible with 1.6.x peers (Mode 2 fallback when the peer is older); no vault or DB schema changes; signing key unchanged

**v1.6.2 (May 2026):**
- Native group-invite protocol replaces the legacy carrier (`OFFER`/`ACCEPT`/`DECLINE` on the 1:1 channel)
- Kick reliability fix: invitee epoch desync that silently dropped `MEMBER_REMOVED` is closed; removed users are purged from the local device atomically
- Tor-only transport - Bluetooth, Wi-Fi LAN, removable-drive sync, and dev-reporting subsystems removed
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

- [Overview](docs/ZERION_OVERVIEW.md): plain-language introduction and how Zerion compares
- [Technical Whitepaper](docs/ZERION_TECHNICAL_WHITEPAPER.md): full architecture, crypto, transport, vault and anti-forensics
- [Introduction / pairing signatures](docs/wire/F2_INTRODUCTION_HYBRID_SIG.md): hybrid-signed pairing record spec
- [Contact-add record placement](docs/wire/B3_RECORD_PLACEMENT.md): hybrid pairing record layout
- [GroupTr Wire Protocol](docs/wire/GROUPTR_WIRE_PROTOCOL.md): group invite and membership records
- [Channels Wire Protocol](docs/wire/CHANNELS_WIRE_PROTOCOL.md): publisher-to-subscriber broadcast records

---

## Development & Auditing

Transparency on how Zerion is built.

Large language models are used in two specific areas during Zerion's development:

- **Internal audits and security testing.** Pre-release codebase reviews, static-analysis sweeps, lock-leak detection, race-condition hunts, dependency CVE checks, and adversarial reviews of new code paths. Any finding is subsequently reviewed by the project's pentesters and developers before it is acted on. Findings that turn out to be wrong are discarded.
- **Tooling and routine work.** Validator scripts that pin invariants into static checks, release-note drafting, and documentation. Minimal, scoped use.

**LLMs are not used for the cryptography.** The cryptographic primitives, key management, session-state and ratchet logic, and wire-protocol framing are authored and owned by the project's developers and reviewed by pentesters. The low-level primitives come from established libraries (Bouncy Castle, the Tor daemon, SQLCipher). The LLM does not have commit authority. Every commit and rationale is in this public git history.

Full statement: [zerion.chat/blog/llm-use-in-zerion.html](https://zerion.chat/blog/llm-use-in-zerion.html)

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

## Built on Briar

Zerion is a derivative work of [Briar](https://briarproject.org), the peer-to-peer messaging project by the Briar Project, and is built on Briar's Bramble transport protocol. Zerion would not exist without their work, and we are grateful for it.

Zerion extends Briar with post-quantum cryptography, voice and video calls over Tor, an encrypted vault, channels, and other features, and has diverged from upstream Briar in both wire protocol and feature set. It is an independent project and is **not affiliated with, nor endorsed by, the Briar Project**.

- Briar: https://briarproject.org
- Briar source: https://code.briarproject.org/briar/briar

## License

Zerion is free and open-source under the **GNU General Public License v3.0 (GPLv3)** - the same license as Briar, the upstream project it is derived from. See [LICENSE.txt](LICENSE.txt) for the full text. Modifications to Briar's original files are recorded in this repository's public git history.
