# Zerion

**Anonymous. Encrypted. Post-Quantum Ready.**

<p align="center">
  <img src="zerion-android/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" width="120" height="120">
</p>

Zerion is a secure messaging app and encrypted vault designed for people who need private, anonymous, censorship-resistant communication.

Unlike traditional messengers, Zerion uses no servers, no accounts, no phone numbers, and no cloud services. All communication flows directly between devices using the Tor network, protecting users from surveillance, metadata collection, and IP exposure.

With hybrid post-quantum cryptography, post-compromise security (Double Ratchet), hardware-backed vault protection, and advanced anti-forensics features, Zerion provides strong security even against sophisticated adversaries.

---

## Why Zerion?

- **Truly anonymous** — No phone number, email, or registration
- **End-to-end encrypted** messaging, groups, voice notes, and P2P calls
- **Post-Compromise Security** — Double Ratchet with X25519 DH for per-message key evolution
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

Zerion implements a Double Ratchet protocol for post-compromise security:

- **Forward secrecy**: Past messages stay private even if your device is later compromised
- **Post-compromise recovery**: If an attacker compromises your device, security is restored after one message round-trip
- **Per-message keys**: Every message uses a unique encryption key derived from the current chain state

**Ratchet Modes:**
- **Mode 1 (Symmetric ratchet)**: Used with Briar contacts for compatibility. Provides forward secrecy.
- **Mode 2 (Double Ratchet)**: Used between Zerion users. Provides both forward secrecy and post-compromise security via X25519 DH ratchet.

### P2P Voice Calls

Real peer-to-peer encrypted voice calls routed exclusively through Tor hidden services.
No STUN, no TURN, no VoIP servers — just private communication between devices.

### Secure Vault

A hardware-backed encrypted vault for passwords, notes, photos, videos, and documents.
Uses Argon2id, AES-256-GCM, and StrongBox/Keystore integration for strong protection.

### Briar-Compatible Mode

When adding a contact, you choose the security level:
- **Zerion (Post-Quantum)**: Full post-quantum security (ML-KEM-768 + X25519) + PCS Mode 2 (Double Ratchet) for Zerion-to-Zerion communication
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

Zerion is under active development. Current focus areas:

**In Progress:**
- P2P video calls
- File transfer improvements
- UI/UX refinements

**Planned:**
- Multi-device sync
- Offline messaging queue enhancements

---

## Documentation

- [Technical Whitepaper](docs/ZERION_TECHNICAL_WHITEPAPER.md) — Complete architecture & crypto design
- [Post-Quantum Messaging](docs/POST_QUANTUM_MESSAGING.md) — PQ implementation details
- [PCS Design](docs/PCS_DESIGN.md) — Post-Compromise Security (Double Ratchet) specification
- [P2P Voice Calls](docs/P2P_Voice_Calls_Documentation.md) — Voice calling specification

---

## License

Zerion is free and open-source under the **GPLv3** license.
