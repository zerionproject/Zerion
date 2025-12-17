# Zerion

**Anonymous. Encrypted. Post-Quantum Ready.**

<p align="center">
  <img src="zerion-android/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" width="120" height="120">
</p>

Zerion is a secure messaging app and encrypted vault designed for people who need private, anonymous, censorship-resistant communication.

Unlike traditional messengers, Zerion uses no servers, no accounts, no phone numbers, and no cloud services. All communication flows directly between devices using the Tor network, protecting users from surveillance, metadata collection, and IP exposure.

With hybrid post-quantum cryptography, hardware-backed vault protection, and advanced anti-forensics features, Zerion provides strong security even against sophisticated adversaries.

---

## Why Zerion?

- **Truly anonymous** — No phone number, email, or registration
- **End-to-end encrypted** messaging, groups, voice notes, and P2P calls
- **Tor-only networking** — Your IP address is never exposed to contacts
- **Direct peer-to-peer architecture** — No central servers
- **Encrypted Vault** for passwords, documents, media, and notes
- **Post-quantum hardened** — Hybrid ML-KEM-768 + X25519, ML-DSA-65 + Ed25519
- **Briar compatible** — Communicate with Briar users via explicit contact type selection
- **Downgrade attack protection** — PQ contacts stay PQ-secure forever
- **Anti-forensics protection** against mobile extraction tools
- **Open-source and auditable**

**Zerion collects zero personal data. Not by policy — by cryptographic design.**

---

## Core Features

### Encrypted Messaging

Private one-to-one chats and groups with end-to-end encryption.
Disappearing messages and metadata removal ensure conversations remain confidential.

### P2P Voice Calls

Real peer-to-peer encrypted voice calls routed exclusively through Tor hidden services.
No STUN, no TURN, no VoIP servers — just private communication between devices.

### Secure Vault

A hardware-backed encrypted vault for passwords, notes, photos, videos, and documents.
Uses Argon2id, AES-256-GCM, and StrongBox/Keystore integration for strong protection.

### Briar Compatibility

When adding a contact, you choose the security level:
- **Zerion (Post-Quantum)**: Full post-quantum security (ML-KEM-768 + X25519) for Zerion-to-Zerion communication
- **Briar-compatible (Classical)**: Classical security (X25519) for communication with Briar users

Your chat settings show the security level for each contact.

### Downgrade Attack Protection

Once a contact is established with post-quantum security, it stays that way.
Any attempt to reconnect with weaker security is automatically blocked.

---

## Download Zerion

**Coming soon:**

- F-Droid
- Direct APK download

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
- [P2P Voice Calls](docs/P2P_Voice_Calls_Documentation.md) — Voice calling specification

---

## License

Zerion is free and open-source under the **GPLv3** license.
