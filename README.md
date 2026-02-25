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
- **Briar compatible** — Communicate with Briar users via Briar-compatible mode
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
- **Mode 1 (Symmetric ratchet)**: Used with Briar contacts for compatibility. Provides forward secrecy.
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

### Briar-Compatible Mode

When adding a contact, you choose the security level:
- **Zerion (Post-Quantum)**: Full post-quantum security (ML-KEM-768 + X25519) + PCS Mode 3 (Triple Ratchet) for Zerion-to-Zerion communication with quantum-resistant post-compromise security
- **Briar-compatible (Classical)**: Classical security (X25519) + PCS Mode 1 (symmetric ratchet) for communication with Briar users

Your chat settings show the security level for each contact.

### Downgrade Attack Protection

Once a contact is established with post-quantum security, it stays that way.
Any attempt to reconnect with weaker security is automatically blocked.

---

## Download Zerion

**[Download APK](https://github.com/zerionproject/Zerion/releases/latest)**

- F-Droid (pending review)

---

## Development Status

Zerion is under active development.

**v1.0.4 (Latest):**
- P2P encrypted video calls over Tor
- Video orientation correction (TextureView + rotation matrix)
- Crypto-protocol hardening: 8 vulnerabilities fixed (ZERION-001 through ZERION-009)
- Fail-closed PQ epoch reset, constant-time key comparison, KEM secret zeroing
- Receive-side PQ epoch completion, chunk index validation, EK seed hash computation
- Voice signal ephemeral cleanup (immediate delete + startup purge)
- Zero-log CI enforcement (Gradle build task)
- Video/audio stream metadata moved inside encrypted payload

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
