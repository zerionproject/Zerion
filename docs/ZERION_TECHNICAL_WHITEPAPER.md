# Zerion 3.0 Technical Whitepaper

## Abstract

Zerion is an end-to-end encrypted, peer-to-peer messenger for Android that runs entirely over Tor, with no servers and no accounts. Zerion 3.0 replaces the inherited Bramble transport and synchronisation layers with a native protocol stack, ZTP, ZWF, ZPP and ZMM, carrying a hybrid post-quantum ratchet (Mode 3-Full) in which every message is protected by a fresh ML-KEM-768 key encapsulation layered over a classical symmetric chain. Traffic is shaped into fixed-size frames sent at a constant rate, so a network observer cannot distinguish messages from cover traffic or infer message sizes or timing.

Zerion is a fork of, and is built on, the Briar Project and its Bramble framework (GPLv3). The transport, wire format and ratchet described here are Zerion's own; the debt to Briar for the identity, database and Tor-integration foundations is gratefully acknowledged.

This document describes the protocol as implemented in the 3.0 source tree, current as of 3.0.4. As of 3.0.4 the vault also hosts optional, self-custodial Bitcoin and Monero wallets, described in [§9](#9-the-encrypted-vault-and-non-custodial-wallets). Where the implementation makes a deliberate trade-off or falls short of an idealised design, this document says so plainly (see [§11, Security Properties and Limitations](#11-security-properties-and-limitations)).

---

## Table of Contents

1. [Design goals](#1-design-goals)
2. [Threat model](#2-threat-model)
3. [System architecture](#3-system-architecture)
4. [Identity and pairing (key exchange)](#4-identity-and-pairing-key-exchange)
5. [Transport stack: ZTP, ZWF, ZPP, ZMM](#5-transport-stack-ztp-zwf-zpp-zmm)
6. [The Mode 3-Full ratchet](#6-the-mode-3-full-ratchet)
7. [Authenticated encryption and nonces](#7-authenticated-encryption-and-nonces)
8. [1:1 chat, group chat, channels and voice](#8-11-chat-group-chat-channels-and-voice)
9. [The encrypted vault and non-custodial wallets](#9-the-encrypted-vault-and-non-custodial-wallets)
10. [Anti-forensics and device hardening](#10-anti-forensics-and-device-hardening)
11. [Security properties and limitations](#11-security-properties-and-limitations)
12. [Cryptographic parameters](#12-cryptographic-parameters)

---

## 1. Design goals

- **Post-quantum by default.** Every message key incorporates ML-KEM-768 in addition to X25519, so recorded traffic is not decryptable by a future quantum adversary ("harvest now, decrypt later").
- **No servers, no accounts.** Peers connect directly to each other's Tor hidden services. There is no central relay, directory, or push service.
- **Metadata minimisation.** Fixed-size frames sent at a constant rate hide who is talking, when, and how much.
- **Fail closed.** Any authentication or format failure drops the stream rather than degrading to a weaker mode.
- **Forward secrecy and post-compromise security** on the message stream.

## 2. Threat model

Zerion aims to protect against:

- A **global passive network adversary** observing all Tor traffic: it should learn neither the content nor the size, count, or precise timing of messages, and Tor conceals the network location of both peers.
- An **active network adversary** that can drop, delay, reorder, or inject frames: it cannot forge or alter authenticated content, and any tampering drops the stream.
- A **future quantum adversary** with recorded ciphertext: the post-quantum layer keeps recorded traffic confidential.
- **Device seizure of a peer** after the fact: forward secrecy protects earlier messages, and the post-quantum ratchet heals the session after a transient key compromise.

Out of scope: a fully compromised endpoint (malware with the screen unlocked), traffic-confirmation attacks against Tor itself, and coercion of a user to unlock the device. Zerion applies platform hardening (see [§10](#10-anti-forensics-and-device-hardening)) to raise the cost of device-level attacks but does not claim to defeat a compromised OS.

## 3. System architecture

Zerion 3.0 is Android-only. Each device runs an embedded Tor process (via the `onionwrapper` library) and publishes a persistent v3 onion service. A contact is reached by dialling its onion address through Tor's SOCKS proxy. In the shipped release Tor is the only transport (the inherited Bluetooth, LAN and Internet-TCP plugins were removed), and it is mandatory and always on: it is the anonymity floor and cannot be disabled.

> **Additional transports (shipped in 3.0).** Two additional transports ship in 3.0; neither weakens the Tor-only guarantee of online messaging. (1) **I2P**: an opt-in extra, off by default, over an embedded in-process Java router using I2P's streaming library. I2P provides end-to-end tunnel anonymity (a peer does not learn your address); the residual is that a network observer can tell you *participate* in I2P, the same class of exposure as using Tor without bridges. The one clearnet bootstrap step (reseed) is routed through Tor's SOCKS proxy and fails closed, so joining I2P does not reveal the device address. It is off by default with Tor mandatory. (2) **Offline mesh**: a **Bluetooth-only** (no Wi-Fi) store-carry-forward transport for scenarios with no internet at all (disasters, blackouts, protests). It carries 1:1 messages and full group chat over the same hybrid post-quantum identities, using async sealed-sender encryption to a recipient's published post-quantum prekey (ML-KEM-768 + X25519 → XSalsa20-Poly1305, inner Ed25519 + ML-DSA-65 signature) flooded across nearby phones, which relay only opaque ciphertext. An earlier Wi-Fi Direct radio was **removed entirely** because it leaked the OS device name and a second MAC and connected indiscriminately, so the mesh is pure BLE. The mesh has a deliberately different threat model from Tor/I2P: it hides *content* but not *physical proximity*, so a co-located adversary can tell that a device is transmitting. It is "communicate when there is no internet," not "hide that you are communicating from someone standing next to you." Both transports are documented in full in [ZERION_MESH_AND_I2P.md](ZERION_MESH_AND_I2P.md).

The protocol stack, from the socket up:

- **ZTP** (Zerion Tor Protocol), runs Tor, publishes the onion service, dials peer onions, accepts inbound connections, and hands each connected socket to the connection handler.
- **ZWF** (Zerion Wire Format), the fixed-size, authenticated framing on each connection.
- **ZPP** (Zerion Pull Protocol), the constant-rate send scheduler that makes real traffic indistinguishable from cover traffic.
- **ZMM** (Zerion Message Module), application message records and fragmentation over the frame stream.

Identity, contacts, the message database and the pairing handshake are retained from the Briar/Bramble foundation but re-homed under the `org.zerionproject` namespace; the ratchet and the four protocols above are new in 3.0.

## 4. Identity and pairing (key exchange)

Each account has a **hybrid identity**: an Ed25519 key and an ML-DSA-65 key for signatures, plus X25519 and ML-KEM-768 keys for key agreement. A signature or key agreement is valid only if **both** the classical and the post-quantum halves verify, so forging an identity requires breaking both a classical and a post-quantum primitive.

Pairing happens out of band (QR code or a rendezvous link) and runs a hybrid authenticated key-agreement handshake:

1. Both sides exchange hybrid public keys and perform X25519 and ML-KEM-768 key agreement, combining the two shared secrets through a keyed BLAKE2b KDF with domain separation.
2. Each side signs the transcript with its hybrid (Ed25519 + ML-DSA-65) identity key; the peer verifies both signatures.
3. The result is bound to a short out-of-band commitment exchanged via the QR/link, so a man-in-the-middle who relays the handshake cannot match the commitment.
4. The handshake is **downgrade-resistant**: once a contact is paired with the hybrid protocol, a later attempt that offers only the classical protocol is rejected.

The handshake output is a long-lived per-contact **root key** from which every subsequent connection derives its session state.

## 5. Transport stack: ZTP, ZWF, ZPP, ZMM

### 5.1 ZWF, fixed-size authenticated frames

Every frame on the wire is exactly **4096 bytes** (`FRAME_LENGTH`), regardless of payload. Short payloads are zero-padded; larger application messages are fragmented across frames (by ZMM). Because a real frame and a cover frame are byte-for-byte the same size, an observer cannot tell them apart or infer message length.

A connection begins with a 16-byte **stream tag** and an encrypted **stream header** (`[wire version:2][streamId:8]`, 50 bytes on the wire including the 24-byte nonce and 16-byte MAC). Thereafter each 4096-byte frame is three independently-authenticated AEAD segments:

- **Segment 0, frame header** (4 bytes plaintext + 16-byte MAC): the payload length and padding length, encrypted under the classical message key.
- **Segment 1, Mode 3-Full header**: the sender's advertised ML-KEM public key, the ML-KEM ciphertext, a key-pair id, and the (wire-only) X25519 public key, encrypted under the classical message key, so the post-quantum material is authenticated *before* it is used (see [§6](#6-the-mode-3-full-ratchet)).
- **Segment 2, body**: the application payload plus padding, encrypted under the **hybrid** body key.

The **stream id** is a persistent, strictly-monotonic 64-bit counter that is never reused across reconnects, restarts or key rotations. It seeds both the ratchet chain and the AEAD nonce, so reusing it would repeat keystream, the counter is therefore persisted before any frame is sent. On receive, a stream id is validated against a sliding replay/reorder window of 256; within a stream, frames are strictly in order and any gap drops the stream.

### 5.2 ZPP, constant-rate traffic

The send side emits **exactly one frame per fixed time slot** through a scheduler: the next queued record if there is one, or a **cover frame** if the queue is empty. The slot interval is jittered by ±1/3 around a base cadence (about 750 ms) with zero mean, so the average rate is unchanged but the exact-interval fingerprint is removed. Cover and real frames are indistinguishable on the wire, which is what defeats statistical-disclosure and timing correlation: an observer sees a steady stream of identical frames whether the user is chatting or idle.

Because cover frames flow continuously, they also bootstrap the ratchet: the two peers exchange their ML-KEM public keys within the first slot or two of a connection, before any human-typed message is sent.

### 5.3 ZMM, records and fragmentation

Application data is carried as typed records (private messages, group and channel records, voice-call signalling, acknowledgements). Records larger than one frame's payload capacity are fragmented and reassembled. A cover frame carries a distinguished cover record that the receiver drops. Received application messages are deduplicated by message id against the (encrypted) database, which also bounds any replay at the message layer.

## 6. The Mode 3-Full ratchet

Mode 3-Full is Zerion 3.0's single message ratchet. It combines a classical symmetric chain (for forward secrecy) with a per-message post-quantum key encapsulation (for post-compromise security and quantum resistance).

### 6.1 Per-message keys

For each frame the sender:

1. Advances a **classical chain key** with a keyed BLAKE2b KDF to produce a per-message *classical key*. The chain is seeded once per connection from `(rootKey, streamId, streamHeaderNonce)`, where `streamHeaderNonce` is a fresh random value carried in the authenticated stream header, and is one-way, giving **forward secrecy**: a compromised chain key cannot recover earlier message keys. Salting the seed with the per-stream header nonce means that even a database restored from backup, which could hand back an already-used `streamId`, derives a different chain and never repeats a (key, nonce) pair.
2. Performs a **fresh ML-KEM-768 encapsulation** to the peer's current ML-KEM public key, yielding a ciphertext (sent in the frame's Mode 3-Full header) and a shared secret.
3. Derives the **hybrid body key** as `BLAKE2b-KDF(classical key, ML-KEM shared secret)`. The frame body is encrypted under this hybrid key, so the body stays confidential as long as *either* the classical chain *or* ML-KEM-768 is unbroken.
4. Folds the ML-KEM shared secret back into the chain key for the next message. Once a post-quantum secret has been absorbed, the chain can no longer be recomputed from `rootKey` alone, so forward secrecy and healing extend to the symmetric chain itself rather than resting only on the per-message hybrid body key.

The receiver mirrors this: it authenticates the Mode 3-Full header, decapsulates with the matching private key (looked up by key-pair id), and derives the same hybrid key.

**Post-quantum coverage is per message.** The only exception is the very first frame a side sends before it has learned the peer's ML-KEM public key: that frame carries an all-zero "sentinel" ciphertext and is classical-only. Because the constant-rate cover traffic exchanges public keys within the first slot, this sentinel only ever applies to an opening cover frame and never to a user message.

### 6.2 Key rotation and post-compromise security

Post-compromise security ("healing" after a transient key compromise) comes from **rotating the ML-KEM key pair**. A sender generates a new ML-KEM key pair every `MODE3_FULL_SEND_ROTATION_INTERVAL` = **16 messages** and advertises the new public key; the peer then encapsulates to a key whose private half a past attacker does not hold, locking the attacker out. Recent private key pairs are retained in a per-contact LRU of size **32** so that in-flight frames encapsulated to a just-superseded key still decrypt. Healing is reinforced by the fact that **each connection derives a fresh Mode 3-Full ratchet** on resume, so every reconnection re-roots the post-quantum state. Within a connection, healing extends to the symmetric chain itself: each message's ML-KEM shared secret is absorbed into the chain key (§6.1), so after an exchange whose lattice secret a past attacker cannot decapsulate, the chain state is beyond that attacker's reach even if they had captured an earlier chain key.

### 6.3 The classical DH ratchet is inert (by design)

The Mode 3-Full frame header also carries a 32-byte X25519 public key, a vestige of the classical Double Ratchet inherited from the upstream design. **In the 3.0 build this classical DH ratchet does not run**: the key is transmitted and AEAD-authenticated in every frame, but it drives no ratchet step (the receive path is constructed without a key parser and the send path never advances the DH root). The classical layer therefore contributes **forward secrecy** through its one-way chain, but **not** post-compromise security.

This is a deliberate deferral: the post-quantum ML-KEM ratchet already provides post-compromise security, so the classical DH ratchet was judged redundant and left unwired rather than maintained. The consequence, that post-compromise security rests entirely on the post-quantum layer, with no independent classical backstop, is stated honestly in [§11](#11-security-properties-and-limitations). Zerion does not claim a working classical Double Ratchet.

## 7. Authenticated encryption and nonces

The AEAD is **XSalsa20-Poly1305** (the NaCl `secretbox` construction): a 24-byte nonce, a 256-bit key, and a 128-bit Poly1305 tag verified in constant time before any plaintext is released.

The 24-byte nonce is constructed from `streamId`, the 64-bit frame number, the segment index (0/1/2), and a bit identifying which peer originated the stream. This guarantees that no `(key, nonce)` pair repeats:

- The **segment index** separates the three segments within a frame even when the frame-header and body happen to use the same key (the sentinel case), so their keystreams are disjoint.
- The **frame number** advances every frame and the classical message key advances every message, so no nonce recurs across frames of a stream.
- The **originator bit**, together with role-separated key derivation, keeps the two directions of a connection disjoint.

Key material and plaintext buffers are zeroised after use on every code path, including error paths.

## 8. 1:1 chat, group chat, channels and voice

- **1:1 chat.** Every private conversation runs the Mode 3-Full ratchet described above over a dedicated pair of ZWF streams (one per direction) on a direct Tor connection between the two peers. Messages, read receipts and typing state are ZMM records; attachments are fragmented across frames. There is no server copy of any message.
- **Group chat (GroupTr).** Groups are serverless and have no shared group key: each member relays every group message to the other members over its existing pairwise 1:1 Mode 3-Full connections. Every hop is therefore protected per-message by ML-KEM-768 exactly as a private chat is, and nothing group-related is ever in the clear on the wire. Group membership is authoritative from the group creator: each membership change (add or remove) is carried in a record signed with the creator's hybrid (Ed25519 + ML-DSA-65) identity and is rejected unless that signature verifies, and each change advances a monotonic epoch counter that members enforce to reject stale or replayed membership state. Removal is enforced by the remaining members no longer relaying to the excluded member, not by re-keying a shared secret (there is none to re-key); forward secrecy against a removed member therefore rests on the honest members ceasing to relay, and a member who chose to keep relaying could still reach an excluded party. Group records travel inside ZWF frames tagged by the ZMM registry.
- **Channels** are single-publisher, many-subscriber broadcast (announcements, feeds). The publisher signs each post with its hybrid identity and serves posts from a dedicated channel onion; subscribers pull over Tor and verify the signature chain, so a subscriber needs no trust in any third party and the publisher learns nothing about who is subscribed beyond a connecting Tor circuit. Posts, comments and reactions are content-addressed and tamper-evident.
- **Voice calls** are peer-to-peer over Tor: Opus audio (16 kHz mono, ~24 kbit/s) in fixed 20 ms / 640-byte frames, each frame encrypted with AES-256-GCM under a call key negotiated over the authenticated messaging channel. The fixed frame size and cadence avoid leaking speech patterns through packet timing.

## 9. The encrypted vault and non-custodial wallets

The vault is an on-device encrypted store for passwords, secure notes, documents and images, separate from messaging and protected by its **own** password.

- **Key derivation.** The vault password is stretched with **Argon2** (memory-hard) to derive the vault key; the derived key is held in the Android keystore and, on capable devices, in hardware-backed **StrongBox**, so it cannot be extracted from the device.
- **Encryption at rest.** Vault items (`PasswordEntry`, secure notes, documents, gallery media) are encrypted with authenticated encryption through a secure file-I/O layer; nothing in the vault is written in the clear.
- **No recovery.** There is no password-reset or recovery path, a forgotten vault password means the data is unrecoverable by design, so there is no backdoor to coerce.
- **Locks when idle** and on app lock; decrypted content is never persisted outside the vault. When the vault renders a document (for example a PDF), any decrypted temporary file is securely overwritten and deleted immediately after use, and a startup sweep removes any that a crash left behind.
- **Screen protection.** Vault screens are unconditionally `FLAG_SECURE` (excluded from screenshots and the recents thumbnail) and use an incognito keyboard on entry fields.

### 9.1 Non-custodial Bitcoin and Monero wallets

Zerion 3.0.4 adds optional non-custodial Bitcoin and Monero wallets that live inside the vault. They are **self-custodial and device-local**: the wallet seed is generated on the device, sealed as a vault item, and never leaves it. There is no custodian, no account, and no server that can move, freeze or see the funds. A wallet is reached only after the vault is unlocked, and each wallet carries its **own** password, distinct from the vault master password.

- **Seed sealing and spend-authority derivation.** Each wallet's seed is stored as a vault item under authenticated encryption. Obtaining spend authority requires the wallet password stretched with **Argon2id** (memory-hard) combined with an Android keystore factor, so decrypting the vault tier alone never yields spending power.
- **Bitcoin.** A BIP84 native-SegWit wallet (bitcoinj, mainnet): a fresh address per receive, fresh change addresses, coin control with per-output freezing and labels, and a cluster-aware privacy analyser. A send is authorised by reviewing the exact transaction; the reviewed plan is fingerprinted, the credential is re-checked, and only that same plan is signed and broadcast, so what the user reviews is what is broadcast (per-input value is bound by the SegWit signature, so a tampered input voids the signature rather than moving funds).
- **Monero.** A wallet built on Monero's own `wallet2` (pinned upstream source, built reproducibly, see below). At runtime it runs as a **spend-keyless, view-only** wallet, so day-to-day balance and history never bring the spend key into memory; the spend key is derived from the wallet password and held only for the moment a transaction is signed, then wiped. Receiving uses fresh subaddresses, never the primary address.
- **Tor by default.** All wallet network traffic (the Electrum client for Bitcoin, Monero nodes, broadcast, and price lookups) is carried over Tor through the local SOCKS proxy, with distinct stream isolation per wallet and per purpose, and no silent fallback to a direct connection. A non-Tor path exists only as an explicit, opt-in choice behind a warning.
- **Transaction-time authorisation.** Spending, revealing a seed, deleting or rescanning a wallet each require the wallet password at the moment of the action and are re-validated against the live lock state, so a screen left open cannot authorise a spend after the vault has locked. The displayed spendable balance never counts funds already committed to an unconfirmed send.
- **No forensic residue.** Wallet screens are `FLAG_SECURE`, the production build writes no logs from the wallet code, and secret buffers (passwords, seeds, derived keys) are zeroised after use on every path, including error paths.

**Reproducible native provenance.** The wallets rely on native libraries (Monero's `wallet2`, and Argon2 for key derivation). These are built from **pinned upstream source** inside a pinned container, never downloaded as opaque prebuilt binaries: the Monero commit and every dependency archive are hash-pinned and verified at build time, and each shipped library's per-ABI SHA-256 is published so that anyone can reproduce the build and confirm the bytes. The libraries ship inside the signed application package.

**Review.** The wallet foundation (the vault and its cryptography, the Bitcoin and Monero wallets, and the native boundary) has been through extensive internal security and code review across multiple independent adversarial passes, with the findings addressed in the shipping code.

## 10. Anti-forensics and device hardening

Zerion is designed to resist not only the network adversary but also examination of a **seized device**.

- **Duress and panic.** Zerion registers as a Guardian Project **panic responder**: a trusted panic trigger (for example from Ripple) locks the app immediately, and can be configured to wipe the account. The trigger is authenticated by signature pinning so a hostile app cannot spoof it.
- **Decoy launcher.** The app can present as a working **calculator** (or other innocuous app) via activity-aliases; the real app is reached only through a hidden entry, giving plausible deniability that Zerion is installed at all. Message notifications are content-free and can be hidden so the lock screen does not reveal the app.
- **Hardened mode.** On a device that fails integrity checks (root, insecure boot state), Zerion can block sensitive functionality and warn the user; a secure-boot guard and a **Tor-binary integrity pin** (the shipped `libtor`/`liblyrebird` binaries are pinned on first run and re-verified) detect tampering with the app's native components.
- **No forensic residue.** The production build emits **no logs** (nothing reaches logcat); message content never appears in notifications or on the lock screen; screenshots and the recents thumbnail are blocked on content screens (`FLAG_SECURE`); image and video attachments are re-encoded to strip EXIF and other metadata before sending; and decrypted media/temporary files are securely overwritten, not just unlinked.
- **Memory hygiene.** Key material, private keys, shared secrets, derived session keys, is zeroised after use on every code path, including error paths, to shorten its lifetime in RAM.
- **Storage.** The message database is **SQLCipher** (AES-256, schema version 66) with `cipher_memory_security` and `secure_delete` enabled; its key is derived from the account password with a memory-hard KDF and StrongBox-wrapped where available. Preferences use `EncryptedSharedPreferences` or the SQLCipher-backed store, never plaintext `SharedPreferences`.
- **Platform.** Minimum Android 10 (API 29), targets Android 16 (API 36); native libraries are 16 KB-page aligned; tapjacking and overlay-window defences are applied to sensitive screens.

## 11. Security properties and limitations

**Properties provided:**

- **Confidentiality and integrity** end-to-end, with post-quantum protection on every user message.
- **Forward secrecy**: the one-way classical chain and a fresh per-connection root mean a compromised current key does not expose past messages.
- **Post-compromise security**: healing via ML-KEM key rotation (every 16 messages) plus a fresh ratchet on every reconnection.
- **Mutual authentication**: hybrid signatures at pairing and per-frame AEAD thereafter; the post-quantum public key and ciphertext are authenticated before use.
- **Metadata resistance**: fixed 4096-byte frames at a constant, cover-filled rate over Tor hide content, size, count and timing.
- **Replay and reorder protection**: strictly-monotonic persistent stream ids, a 256-wide receive window, strict in-order framing per stream, and message-id deduplication at the database layer.
- **Fail-closed**: any authentication or format failure drops the stream.

**Honest limitations:**

- **Post-compromise security is post-quantum-only.** Because the classical DH ratchet is inert ([§6.3](#63-the-classical-dh-ratchet-is-inert-by-design)), there is no independent classical healing mechanism; a hypothetical implementation flaw in the ML-KEM ratchet would not be caught by a classical backstop. Wiring an independent classical Double Ratchet is possible future defence-in-depth.
- **Healing granularity** is one ML-KEM rotation (16 messages) or one reconnection, not strictly per message.
- **Post-restart stream replay** within the 256-id window is possible at the wire layer after a process restart (the in-memory seen-set is not persisted); it is absorbed by the durable database message-id deduplication, which is therefore a load-bearing control.
- **Group messaging has no shared group ratchet.** As described in [§8](#8-11-chat-group-chat-channels-and-voice), group content rides the members' pairwise post-quantum ratchets and group membership is authenticated by the creator's hybrid signature, but there is no group-wide key. Removing a member is enforced by the remaining members no longer relaying to them, not by re-keying a shared secret, so forward secrecy against a removed member depends on the honest members and a member who kept relaying could still reach an excluded party. A group is only as confidential as its members choose to keep it.
- **Channels: reading is anonymous, reacting and commenting are not.** Subscribing to and pulling a channel reveals nothing to the publisher beyond a connecting Tor circuit. Posting a reaction or a comment is an explicit user action that signs and sends the user's own identity key to the publisher, so those actions are attributable by design; a user who wishes to stay anonymous to a publisher should read only.
- **Channel publishers can equivocate.** Because subscribers do not gossip with each other, a malicious publisher could serve a different post history to different subscribers (a fork). The signature chain guarantees that each subscriber's view is internally consistent, gap-free and authentically signed by the publisher, but not that all subscribers see the same view. This is inherent to single-publisher broadcast without a shared consistency oracle.
- **The Bitcoin wallet uses a lightweight (Electrum) client, not full SPV.** Balances and confirmation counts are server assertions, checked against local structural and signature validation but not against proof-of-work. A malicious server cannot derive keys, sign, redirect a signed transaction, or make the wallet spend more than the reviewed plan (the signature commits every input and output); it can, however, withhold data or misreport confirmations, so a receiver should treat a single server's confirmation count with appropriate caution. Independent header/merkle verification is possible future work.
- **Monero balance reconciliation is view-based.** Because the runtime wallet is view-only, a spend made from the same seed in a different wallet is reconciled into the displayed balance the next time the wallet is opened with its password, not continuously. A Zerion send always opens the spend wallet first, so it can never select an already-spent output; the reconciliation concerns display, not fund safety.
- Endpoint compromise, Tor traffic-confirmation, and coercion are out of scope.

## 12. Cryptographic parameters

| Purpose | Primitive | Parameters |
|---|---|---|
| Key agreement | X25519 + ML-KEM-768 (hybrid) | ML-KEM enc key 1184 B, ciphertext 1088 B, decap key 2400 B, shared secret 32 B |
| Signatures | Ed25519 + ML-DSA-65 (hybrid) | ML-DSA public key 1952 B; hybrid signature 3373 B |
| AEAD | XSalsa20-Poly1305 | 24-byte nonce, 256-bit key, 128-bit tag |
| KDF / MAC | Keyed BLAKE2b | 256-bit output, domain-separated labels (`org.zerionproject/...`) |
| Message ratchet | Mode 3-Full | per-message ML-KEM-768; key-pair rotation every 16 messages; recent-key LRU 32 |
| Wire frame | ZWF | fixed 4096 B; 16-byte tag; 8-byte persistent stream id; replay window 256 |
| Transport padding rate | ZPP | one frame per ~750 ms slot ±1/3 jitter, real-or-cover |
| Voice | Opus + AES-256-GCM | 16 kHz mono ~24 kbit/s, 20 ms / 640-byte frames |
| Database | SQLCipher (AES-256) | schema version 66; StrongBox-wrapped key where available |
| Wallet key derivation | Argon2id | vault master 256 MiB / t=3; per-wallet 64 MiB / t=3; combined with a keystore factor |
| Wallet at-rest / native | AES-256-GCM; reproducible native build | random-nonce AEAD; Monero `wallet2` and Argon2 built from pinned source, per-ABI SHA-256 published |

---

*Zerion is built on the Briar Project's Bramble framework and is licensed under the GPLv3. The native transport (ZTP/ZWF/ZPP/ZMM) and the Mode 3-Full ratchet described here are Zerion's own work; Briar is credited for the identity, storage and Tor foundations on which they build.*
