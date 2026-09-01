# Zerion Secure Messaging Overview

**Zerion is a private messenger with no servers, no phone number, and no account. Every message is protected by post-quantum encryption, and everything runs over Tor so the network cannot see who you talk to.**

This is the plain-language overview. For the cryptographic detail, see the [Technical Whitepaper](ZERION_TECHNICAL_WHITEPAPER.md).

## What makes Zerion different

**No servers, ever.** Most "private" messengers still route your messages through their servers. Zerion connects your phone directly to your contact's phone over the Tor network. There is no company in the middle, no message store to subpoena, and nothing to hack.

**No phone number, no email, no account.** You are never asked to identify yourself. You add a contact by scanning a QR code or sharing a link. That is the whole sign-up.

**Post-quantum on every message.** Ordinary encryption will one day be broken by quantum computers, and traffic recorded today can be decrypted then. Zerion mixes a NIST post-quantum algorithm (ML-KEM-768) into the key of every single message, on top of classical encryption. An attacker would have to break both to read anything, and recorded traffic stays safe against tomorrow's quantum computers.

**The network cannot read your patterns.** Zerion sends a steady stream of identical, fixed-size packets whether you are chatting or idle. An observer cannot tell a real message from filler, cannot see how big your messages are, and cannot see when you are active. Combined with Tor, this hides not just what you say but that you are saying anything.

**A vault for your secrets.** Zerion includes an encrypted vault for passwords, notes, documents and photos, locked with its own password and hardware-backed key. It never leaves your device and has no recovery backdoor.

**Non-custodial crypto wallets.** Zerion 3.0.4 adds optional Bitcoin and Monero wallets inside the vault. They are self-custodial: the keys are generated on your phone, sealed in the vault under their own password, and never leave the device, so no company or server can move, freeze, or see your funds. Like everything else in Zerion the wallets reach the network only over Tor, and the Monero wallet keeps its spending key out of memory except for the instant a payment is signed. The native wallet code is built from pinned upstream source with published, reproducible hashes.

**Built to survive a seized phone.** Zerion can disguise itself as a calculator, wipe on a panic signal, refuse to run on a tampered device, and it writes no logs. Screenshots are blocked, attachments are stripped of hidden metadata, and deleted files are securely overwritten.

## How Zerion compares

| | **Zerion** | Signal | SimpleX | Cwtch | Briar |
|---|---|---|---|---|---|
| No servers (pure P2P) | Yes | No | No | Yes | Yes |
| No phone number / account | Yes | No | Yes | Yes | Yes |
| Runs over Tor by default | Yes | No | Optional | Yes | Yes |
| Post-quantum key exchange | Yes | Yes | No | No | No |
| **Post-quantum on every message** | **Yes** | No | No | No | No |
| Constant-rate traffic shaping | Yes | No | No | No | No |
| Built-in encrypted vault | Yes | No | No | No | No |
| Built-in non-custodial BTC/XMR wallet | Yes | No | No | No | No |
| Anti-forensics (decoy, panic, no logs) | Yes | Partial | No | No | Partial |
| Open source | Yes | Yes | Yes | Yes | Yes |

Signal is excellent and well audited, but it needs your phone number and runs on its own servers. SimpleX removes accounts but still relies on relay servers and has no post-quantum ratchet. Cwtch and Briar pioneered serverless messaging over Tor, and Zerion is built on Briar's foundations, but neither offers post-quantum protection. Zerion's aim is to combine the serverless, Tor-native model with post-quantum security on every message and a built-in vault.

## Honest about the trade-offs

- Because there are no servers, both people generally need to be online at the same time to exchange messages directly. There is no always-on relay holding messages for you.
- Zerion is Android-only today.
- Post-compromise "self-healing" comes from the post-quantum layer; the design deliberately relies on that rather than a second classical mechanism. The [Technical Whitepaper](ZERION_TECHNICAL_WHITEPAPER.md) documents this and the other trade-offs plainly.

Zerion's protocol, source, and this documentation are open for anyone to review.

## Credits

Zerion is built on the [Briar Project](https://briarproject.org) and its Bramble framework, and is released under the GPLv3. Zerion adds its own native post-quantum transport and ratchet, voice calls over Tor, the encrypted vault, optional non-custodial Bitcoin and Monero wallets, and the anti-forensics features described above. Our thanks to the Briar and Tor projects for the foundations this work stands on.
