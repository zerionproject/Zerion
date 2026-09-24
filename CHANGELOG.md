# Changelog

Release notes for every published version. The current architecture and its security properties are described in [README.md](README.md), [SECURITY.md](SECURITY.md) and [docs/](docs/); an entry below describes its own release only and is not updated afterwards. Artifacts and hashes are on [GitHub Releases](https://github.com/zerionproject/Zerion/releases); the values for the current release are in [docs/release-manifest.json](docs/release-manifest.json).

## Unreleased (3.0.12, in preparation)

- Security fixes from the September 2026 internal assessments (see the known limitations of 3.0.11 in [SECURITY.md](SECURITY.md)): call key derivation and video nonce handling, post-quantum authentication at pairing, post-quantum nearby pairing, onion rotation republishing, dependency verification during the release build, and the remaining findings recorded there. Not yet released; 3.0.11 remains the current version until 3.0.12 is published.
- Client authorization of your contact address: each pair of contacts whose apps support it moves to a second onion address whose descriptor can only be read by the holder of a key generated for that one contact. Someone who learns the address without the key cannot read it and cannot connect. Once both sides have proven the new path, that address becomes the only way they reach each other, and removing a contact withdraws their key and changes the address for everyone else.
- Calls: fixed a call that could keep ringing on one phone after the other had given up, a self view that appeared rotated, a video call that reported a camera error when the video link was simply lost, and a first video attempt that could fail with a broken connection. Video setup now has a full minute before it gives up.
- Network status now shows what Tor is actually doing, building circuits or publishing your address, offers a Restart Tor button, and reports how many contacts have client authorization locked in.
- Documentation cleanup: current architecture only in the README, release history moved here, provenance recorded in NOTICE.md, release metadata in a single manifest, documentation consistency checks. The call audio format, the pairing authentication and the new address protection are described as the code implements them.

## 3.0.11 (September 2026)

- Fixes a bug where the app could stay offline to contacts after a spell without signal, such as in an elevator or a garage, until it was force closed. The app now notices when a link that stayed attached stops or resumes passing traffic, and it restarts Tor's network when Tor is stuck reconnecting, so contacts can reach you again without a restart.
- Upgrades Tor to 0.4.9.12.
- Builds the Monero wallet library from a clean tree on every release and records the resulting hashes, so the F-Droid build can be verified against the published APK.

## 3.0.10 (September 2026)

- Fixes a bug where updating to 3.0.9 could show a database error after signing in. No data was affected: the failed database upgrade rolled back and left the account, contacts, messages and wallets intact, and installing this version opens the account normally. The database upgrade step is now idempotent and self-healing, and is covered by a regression test that runs it against a real database. Functionally identical to 3.0.9 otherwise.

## 3.0.9 (September 2026)

- Rotating pairing links: the handshake key behind your pairing link rotates after every successful contact addition, so a previously shared link stops identifying you once its pairings resolve; pairings in flight are bound to the key they started with and are unaffected (database schema v67).
- Fixes from an independent focused security review of the Bitcoin wallet and the dormant PayJoin component (researcher: ZeroTrace): strict custom-node classification so a hostile hostname can never bypass the selected Tor routing policy (enforced again at the socket boundary), pending payments keep their coins reserved through every reconciliation state with node responses verified against the requested transaction, and the reviewed fee now always equals the exact final fee.
- Devices with unreliable secure-element firmware no longer crash-loop until a phone restart: key store failures are handled safely everywhere, transient failures no longer discard keys, and a clear explanation screen appears when the device key store is unresponsive; there is never a fallback to unencrypted storage.
- Separate text size settings for chats and for the rest of the interface.
- Fixes: password dots invisible on the light-theme sign-in screen, a stale "Invalid password" message after successfully unlocking the vault.

## 3.0.8 (September 2026)

- Idle data usage cut by five to ten times: the cover traffic now has an active and an idle rate (slower still on mobile data, with a setting to control it); within each rate real and cover frames remain indistinguishable and sending never bursts.
- Storage cleanup: cancelled or failed media uploads no longer leave data behind, orphaned attachment chunks are reclaimed automatically including space leaked by older versions, channel attachment caches are garbage collected, and the encrypted database compacts itself when deletions free significant space.
- Vault: auto-lock timeout and hide-content settings now work as configured, photos taken into the vault save at full resolution, and unsaved note changes warn before closing.
- A full-app hygiene pass: fixed a crash in chats containing voice-call history, password dialogs keep your input when validation fails, notification switches reflect the real system state, dates on older group messages, tappable links in groups and channels, smoother media scrolling in group chats, and more texts moved to translations.

## 3.0.7 (September 2026)

- Security hardening from an external vulnerability report and an independent focused review of the 3.0.6 transport: stream replay protection across restarts, stricter post-quantum ratchet state handling, connection session caps with real socket teardown, onion address rotation wired end to end, and retained ratchet keys stripped from persisted state.
- Vault: asks for the password every time you enter it, locks when you leave, and gains a chat button to return to the messaging environment.
- Fixed a crash after account creation on devices whose key store accepts generating a hardware-backed key but fails when using it; candidate keys are now probed before selection.
- Fixed from an independent focused security review of the Monero wallet native integration (researcher: ZeroTrace): a native wallet lifetime race between refresh interruption and wallet destruction, plus documentation claims rescoped to what the code enforces.
- Fixes: sign-in visibility in light theme, language changes apply immediately, password change no longer succeeds with a mismatched confirmation.

## 3.0.6 (September 2026)

- F-Droid buildability: all native libraries now build from pinned upstream source with published per-ABI hashes; no prebuilt binaries remain in the repository.
- 16 KB memory-page compatibility: updated the one bundled library that was not aligned for Android devices with 16 KB pages.
- No protocol change and no database upgrade.

## 3.0.4 (September 2026)

- Optional non-custodial Bitcoin and Monero wallets inside the vault: self-custodial (the seed is generated on-device and never leaves it), each with its own Argon2id-derived password. The Monero wallet runs view-only at rest so the spend key is in memory only while a payment is signed. All wallet traffic (Electrum, Monero nodes, broadcast, price) goes over Tor by default with per-wallet and per-purpose stream isolation.
- The native wallet libraries (Monero `wallet2`, Argon2) are built from pinned upstream source with published per-ABI hashes and build-time verification gates.
- The wallet foundation (vault, Bitcoin and Monero wallets, and the native boundary) went through extensive internal security and code review in several separate adversarial passes, with the findings fixed.
- Play Store review fixes: a complete Light theme option, user control over background connections, and localisation updates.
- Both people still need matching versions to message; no messaging protocol or database change.

## 3.0.3 (August 2026)

- Require a typed confirmation before wiping an account, and clearer contact-trust labelling; the disappearing-messages timer refreshes correctly on returning to a conversation. No protocol change, no database upgrade.

## 3.0.1 (August 2026)

- Fixes a startup bug where the app could fail to launch and show a black screen on some installs, including from Google Play, because a new integrity self-check did not recognise Google Play's app-signing key.
- Optional hardened mode is now off by default; it is still available under Security settings.
- No protocol change and no database upgrade from 3.0.0.

## 3.0.0 (August 2026)

- A network protocol written in-house: fixed-size 4096-byte frames, paced cover traffic so active use is indistinguishable from idle within a live connection, and per-message hybrid post-quantum encryption, all over Tor with no Zerion servers.
- Keeps the post-quantum ratchet and the delivery database from the 2.x line.
- Two new transports: a Bluetooth offline mesh for messaging with no internet at all (one-to-one and group, with replies and photos) and an opt-in embedded I2P transport; Tor stays mandatory and always on for online messaging.
- Both people need this version to message each other.

## 2.0.7 (July 2026)

- Fixes a display bug where the decoy calculator keypad could render blank in portrait on some narrower screens (reported on HyperOS and GrapheneOS). No protocol change, no database upgrade, signing key unchanged.

## 2.0.6 (July 2026)

- Adding a contact is reliable again; connections recover immediately after a drop so contacts stay online more consistently; voice memos deliver reliably.
- Contact pairing and the per-message post-quantum encryption were reviewed and hardened, and the release went through a full internal code and security review.
- Both people need this version to add each other. No database upgrade.

## 2.0.5 (June 2026)

- Account backup reliability fix: backups work reliably across devices and report a clear error if anything goes wrong. No change to backup encryption, no protocol change, no database upgrade.

## 2.0.4 (June 2026)

- Back up your whole account to an encrypted file, or move it straight to a new phone over Tor.
- Faster, more reliable connections through bridges in censored regions; quicker message delivery; smoother group chats with Enter-to-send and a tappable key fingerprint; app lock can hold for up to 24 hours in the background.
- Full internal code and security review. No protocol change, no database upgrade.

## 2.0.3 (June 2026)

- Voice calls work cleanly in both directions and are on by default; opt-in video-calling beta.
- Mute a channel, search and sort the vault, save channel post drafts, set a default disappearing timer, see pending group invites.
- Lower battery and memory use, faster start, and a set of crash fixes. No protocol change, no database upgrade.

## 2.0.2 (June 2026)

- Channels now raise system notifications for new posts (subscribers) and new comments (owners), with a global Channels toggle and per-channel mute.
- Group chats are resilient under concurrent admin actions: adding a member while another is removed, or messaging during a membership change, no longer splits the member list; invitees see the current roster immediately on accept.
- At-rest encrypted preferences moved to an in-tree implementation, replacing the deprecated AndroidX `security-crypto` library (one-time settings reset on upgrade; conversations, channels, groups, contacts, and vault are unaffected).
- Exit from the foreground notification now reliably reopens cleanly on next launch.

## 2.0.1 (June 2026)

- Build hygiene for F-Droid main-repo distribution: the PhotoView library moved from a vendored binary to source, keeping a single signing key across Play Store, GitHub, and F-Droid so users can switch channels without reinstalling.

## 2.0.0 (June 2026)

- Channels: a publisher-to-subscriber broadcast layer with optional discussion threads; public or private, owner-approved subscribers, reactions, pinned posts, attachments, and editor delegations (post without sharing your identity key); subscribers never see one another.
- Hardened mode (opt-in): refuse to start on tampered devices, under a debugger/root/hooking framework, or when USB debugging/file transfer is enabled.
- Cache wipe on sign-out, 60-second clipboard auto-clear, plain-language copy throughout.

## 1.7.0 (May 2026)

- Mode 3-Full per-message hybrid ratchet is now the default. Every frame in both directions carries a fresh ML-KEM-768 encapsulation; the decapsulated secret is mixed into the body AEAD key on every message.
- Group chat unread counter; multi-profile end-to-end polish; internationalisation of vault confirmation keywords; accessibility labels on call controls; streamlined per-chat actions menu.
- Build-time zero-logging guarantee: a Gradle gate fails the build if any production source file references a logger.
- Wire-compatible with 1.6.x peers (Mode 2 fallback when the peer is older); no vault or DB schema changes; signing key unchanged.

## 1.6.2 (May 2026)

- Native group-invite protocol replaces the legacy carrier on the 1:1 channel.
- Kick reliability fix: invitee epoch desync that silently dropped `MEMBER_REMOVED` is closed; removed users are purged from the local device atomically.
- Non-Tor transports removed: Bluetooth, Wi-Fi LAN, removable-drive sync, and dev-reporting subsystems.
- All `SharedPreferences` routed through Android Keystore-backed encrypted preferences.
- Hybrid Ed25519 + ML-DSA-65 signatures extended to private-group and invitation contexts.
- Carry-forward downgrade-lock token reconstruction fix; vault, biometric and lock-screen findings of an internal audit patched.
- Supply-chain: `junit-bom-5.11.4` pinned by SHA-256 in dependency-verification metadata.

## 1.6.0 (May 2026)

- PCS Mode 3 post-quantum ratchet completes end-to-end; ML-KEM-768 mixed into the root key every 25 messages or 24 hours, both directions.
- Hybrid Ed25519 + ML-DSA-65 signatures on every group record (3,373 bytes).
- Vault password KDF migrated from a PBKDF2 placeholder to Argon2id.
- DB schema v62 to v63 (nullable ML-DSA columns, lazy backfill on first login).
- Critical, high and medium findings of an internal code audit patched before tag.

## 1.5.0 (May 2026)

- B.3 hybrid pairing: ML-KEM-768 + X25519 contact handshake with downgrade defence.
- B.4 onion rotation: Tor v3 onion address rotates every 5 to 14 days to defeat long-term linkability.
- Hybrid identity proofs at first pair (Ed25519 + ML-DSA-65); per-direction PQ epoch infrastructure.

## 1.2.0

- Security hardening: video call camera deadlock fixed, password handling uses `char[]` throughout.
- Registration Lock: protect your account with PIN or password (PBKDF2-SHA256).
- App icon changer: disguise as Calculator, Notes, or Weather; chat text size chooser and bubble colour picker; navigation bar size setting; invite friends sharing.
- Edge-to-edge rendering for Android 15 (SDK 35); link previews default off (fetched via Tor when enabled).
- Removed QR/zxing dependency, Bluetooth and Wi-Fi hotspot dead code; cleaned 2,100+ dead localised strings across 47 languages.

## 1.0.10

- Now available on Google Play Store; fixed local self-view rotation during video calls; fixed camera switch race condition; vault UI refinements.

## 1.0.9

- UI/UX improvements: rich empty states with icons across all list screens; conversation empty state with contextual action prompt; zVault branding.

## 1.0.8

- Auto-wipe on max login attempts is now immediate; forensic tool detection (Cellebrite, GrayKey, ADB, USB data transfer) triggers immediate app lock; message clipboard auto-clears after 60 seconds; emergency file corruption overwrites entire file contents with secure flush.

## 1.0.7

- Fixed self-view rotation during video calls; fixed spurious "Camera error" toast after hanging up; fixed call timer overlapping the local video preview.

## 1.0.6

- Video call security: AES-GCM authentication failure detection; video encoder drain thread clean shutdown; video decoder consecutive failure tracking; `FLAG_SECURE` on the auth screen; `char[]` password handling in the strength estimator.

## 1.0.5

- Video call quality: 640x480 at 24 fps / 600 kbps, H.264 Main Profile Level 3.1; remote video rotation metadata; camera switch async callback; call UX indicators; VoiceCallService key zeroing and threading fixes.

## 1.0.4

- P2P encrypted video calls over Tor; crypto-protocol hardening (8 vulnerabilities fixed); voice signal ephemeral cleanup; zero-log CI enforcement.
