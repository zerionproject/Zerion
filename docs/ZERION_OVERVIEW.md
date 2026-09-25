# Zerion Secure Messaging Overview

**Zerion is a private messenger with no Zerion server, no phone number, and no account. Every message is protected by post-quantum encryption, and online messaging runs over Tor, which hides who you talk to from your network and from the relays in between.**

This is the plain-language overview. For the cryptographic detail, see the [Technical Whitepaper](ZERION_TECHNICAL_WHITEPAPER.md).

## What makes Zerion different

**No Zerion servers.** Most "private" messengers route your messages through their servers. Zerion connects your phone to your contact's phone through the Tor network: your message travels over Tor relays, but there is no Zerion server in the middle, no message store to subpoena, and no company that can be ordered to hand over what it never had.

**No phone number, no email, no account.** You are never asked to identify yourself. You add a contact by scanning a QR code or sharing a link. That is the whole sign-up.

**Post-quantum on every message.** Ordinary encryption will one day be broken by quantum computers, and traffic recorded today can be decrypted then. Zerion mixes a NIST post-quantum algorithm (ML-KEM-768) into the key of every single message, on top of classical encryption. An attacker would have to break both to read anything, and recorded traffic stays safe against tomorrow's quantum computers.

**The network cannot read your conversation's shape.** While a connection is open, Zerion sends identical, fixed-size frames at a steady rhythm, real or filler. An observer cannot tell a real message from filler and cannot see how big your messages are or how many there were. Since 3.0.8 the rhythm has two speeds, a faster one while messages are flowing and a slower one after two quiet minutes, so an observer can tell roughly when a conversation starts and stops, but nothing finer. That an observer can also see that your phone is connected to Tor, and when connections open and close, is stated plainly in the technical whitepaper.

**A vault for your secrets.** Zerion includes an encrypted vault for passwords, notes, documents and photos, locked with its own password and bound to your device through the Android Keystore (StrongBox where the phone has it). It never leaves your device and has no recovery backdoor.

**Non-custodial crypto wallets.** Zerion 3.0.4 adds optional Bitcoin and Monero wallets inside the vault. They are self-custodial: the keys are generated on your phone, sealed in the vault under their own password, and never leave the device, so no company or server can move, freeze, or see your funds. The wallets reach the network over Tor by default (a direct node is an explicit opt-in behind a warning), and the Monero wallet keeps its spending key out of memory except for the instant a payment is signed. The native wallet code is built from pinned upstream source with published hashes checked at build time.

**Built to survive a seized phone.** Zerion can disguise itself as a calculator, wipe on a panic signal or a duress password, refuse to run on a tampered device, and it writes no logs. Screenshots are blocked on content screens, attachments are stripped of hidden metadata, and sensitive files are overwritten before deletion (a logical erasure; flash storage can retain copies below the file system).

## How Zerion compares

| | **Zerion** | Signal | SimpleX | Cwtch |
|---|---|---|---|---|
| No central messaging server | Yes | No | No | Yes |
| No phone number / account | Yes | No | Yes | Yes |
| Runs over Tor by default | Yes | No | Optional | Yes |
| Post-quantum key exchange | Yes | Yes | No | No |
| **Post-quantum on every message** | **Yes** | No | No | No |
| Paced cover traffic within a connection | Yes | No | No | No |
| Built-in encrypted vault | Yes | No | No | No |
| Built-in non-custodial BTC/XMR wallet | Yes | No | No | No |
| Anti-forensics (decoy, panic, no logs) | Yes | Partial | No | No |
| Open source | Yes | Yes | Yes | Yes |

Signal is excellent and well audited, but it needs your phone number and runs on its own servers. SimpleX removes accounts but still relies on relay servers and has no post-quantum ratchet. Cwtch runs serverless over Tor but has no post-quantum protection. Zerion's aim is to combine the serverless, Tor-native model with post-quantum security on every message and a built-in vault.

## Honest about the trade-offs

- Because there is no server, both people generally need to be online at the same time to exchange messages. There is no always-on relay holding messages for you; your own device retries until the contact is reachable.
- Since 3.0.12 both pairing paths are authenticated with the post-quantum key as well; in 3.0.11 and earlier, post-quantum protection at pairing was confidentiality only and nearby (QR/Bluetooth) pairing was classical. Messages themselves carry post-quantum protection on every frame regardless of how the contact was paired.
- Two features you can switch on bypass Tor: I2P (your network can see that you use I2P) and a direct wallet node (that node learns your address). Both are off by default.
- Platform status (September 2026): Android AVAILABLE (3.0.11); Windows and Linux AVAILABLE through the separate desktop client (1.0.1); macOS and iOS IN DEVELOPMENT, nothing published. The table in the README is the reference.
- Post-compromise "self-healing" comes from the post-quantum layer; the design deliberately relies on that rather than a second classical mechanism. The [Technical Whitepaper](ZERION_TECHNICAL_WHITEPAPER.md) documents this and the other trade-offs plainly.

Zerion's protocol, source, and this documentation are open for anyone to review.

## License and attribution

Zerion is released under the GPLv3. Third-party software notices and attribution are recorded in [NOTICE.md](../NOTICE.md). Zerion runs on the Tor network and thanks the Tor Project for it.
