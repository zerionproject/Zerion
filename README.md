Zerion

Anonymous. Encrypted. Post-Quantum Ready.

<p align="center"> <img src="zerion-android/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" width="120" height="120"> </p>

Zerion is a next-generation secure messenger and encrypted vault for Android. Built on the Briar platform and enhanced with Tor, hybrid post-quantum cryptography, and hardware-backed protection, Zerion enables private communication and secure data storage without accounts, phone numbers, or metadata.

Why Zerion?

Truly anonymous — No phone number, email, or cloud account

Post-quantum hardened — Hybrid ML-KEM + X25519, ML-DSA + Ed25519 

README

Tor-only networking — Your IP address never touches another device

No servers — Fully peer-to-peer, decentralized architecture

Built-in secure vault — Hardware-backed AES-256-GCM protection

Zerion collects zero personal data. Not by policy — by design.

Core Features
🔐 Encrypted Messaging

End-to-end encrypted chats and private groups

Auto-delete timers

Voice messages (Opus) with metadata stripping

Works offline via Wi-Fi Direct

📞 P2P Voice Calls

End-to-end encrypted audio (AES-256-GCM)

Routed fully through Tor hidden services

No servers, no STUN/TURN, no IP leakage

Opus codec with 16 kbps bandwidth footprint 

README

🗄️ Secure Vault

Encrypted password manager, notes, photos, and documents

Metadata removal for media

Hardware-backed keys (Android Keystore / StrongBox)

Argon2id-based master key derivation

Security Architecture

Zerion integrates modern, defense-in-depth security:

Hybrid Post-Quantum Cryptography (ML-KEM-768 + X25519, ML-DSA + Ed25519)

RASP protections: root detection, debugger detection, Frida/Xposed detection, emulator detection 

README

Anti-Forensics: memory shredding, cache corruption, forensic tool detection

Encrypted local database with Argon2id KDF

Per-item vault encryption using AES-256-GCM

For full technical details, see the Zerion Technical Whitepaper
. 

ZERION_TECHNICAL_WHITEPAPER

Installation

Coming Soon:

F-Droid

Direct APK download

Alternative stores

Documentation

Technical Whitepaper

Post-Quantum Messaging Architecture

P2P Voice Calling Protocol

License

Zerion is free and open-source under the GPL v3 license.