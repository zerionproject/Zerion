# Security policy

## Reporting a vulnerability

Report security issues privately, in one of two ways:

- a [private GitHub security advisory](https://github.com/zerionproject/Zerion/security/advisories/new) (preferred: the thread stays private until a fix is released), or
- email to `support@zerion.chat`; a throwaway address over Tor is fine.

Include a description, steps to reproduce, the affected component and your assessment of the impact. Text is enough to start; we will arrange a channel for files or a proof of concept. We read every report, will not pursue legal action against good-faith researchers, credit reporters with their permission, and disclose publicly after a fix is released. We ask for 90 days before public disclosure. There is no bug bounty.

In scope: the Android application, its cryptography, the Tor and I2P integration, the mesh transport, the vault and wallets, the build and release tooling. Out of scope: the website, social engineering, attacks that require physical possession of an unlocked device, denial of service, and issues in third-party dependencies (report those upstream).

## Terms used in Zerion's documents

- **Internal review**: performed by Zerion's own team and process. This includes the automated sweeps and manual testing described in the [LLM statement](https://zerion.chat/blog/llm-use-in-zerion.html).
- **Independent focused security review**: an external party reviewing a defined subsystem or component.
- **Independent security assessment**: an external engagement whose scope is the product as a whole.

Zerion has received independent focused reviews. Zerion has **not** received an independent security assessment of the whole product. No document of the project may say otherwise until such an assessment has been completed and published.

## Review history

| Date | Kind | Reviewer | Scope | Outcome | Publication |
|---|---|---|---|---|---|
| continuous | internal review | project | whole codebase before each release | findings fixed before tagging | not published |
| August 2026 | external vulnerability report | a researcher (private) | Mode 3-Full ML-KEM rotation | fixed in 3.0.7 (`c1fbbdcf`) with regression tests | fix public; report private |
| August to September 2026 | independent focused review | ZeroTrace | Monero wallet native integration and JNI boundary | one Low (native object lifetime race) fixed in 3.0.7; documentation rescoped | fixes public; report private |
| September 2026 | independent focused review | a researcher (private) | 3.0.6 transport and privacy properties | PROTO and PRIV findings fixed in 3.0.7 | fixes public; report private |
| September 2026 | independent focused review | ZeroTrace | Bitcoin wallet and the dormant PayJoin component | two Medium and one Low fixed in 3.0.9; one PayJoin blocker open by design while the feature stays disabled | fixes public; report private |
| September 2026 | internal assessment | project | whole Android product (3.0.11), followed by a second internal assessment of the remediated tree | remediation shipped in 3.0.12; the two assurance findings left open there and a runtime defect found afterwards are fixed in 3.0.13 | not published |
| September 2026 | release review | project | 3.0.13 setup path and build reproducibility, after the Google Play review of 3.0.13 failed at account creation | first-account key store failure and the build-path dependency of the Argon2 library fixed in 3.0.14 | not published |
| October 2026 | internal assessment | project | whole Android product (3.0.14) with two-phone device testing, followed by a review of the remediated tree | 7 High, 84 Medium and the lower-rated findings fixed in 3.0.15 | not published |

## Limitations of the previous release (3.0.14), fixed in 3.0.15

Recorded here so that no document overstates what 3.0.14 shipped. All are fixed in 3.0.15, the current release; the entries are kept as the history of the previous one. None of them is known to have been exploited. The list names the issues rated High and the main Medium ones; the lower-rated fixes are summarised in [CHANGELOG.md](CHANGELOG.md).

- Profiles: a profile marked hidden was listed by name on the Profiles screen, and the vault, the wallets, stickers and several settings were shared by every profile on the device. The Profiles screen now shows only the signed-in profile and those stores are kept per profile; someone with root or forensic access to the device can still see how many profiles exist.
- Password checks: the password prompts for switching to a profile and for deleting one signed in on the running session, so the wrong profile could be switched to or deleted. They now only verify the password.
- Database integrity: foreign keys were not enforced on the Android database, so removing a contact left its keys, messages and send queue behind, and a reused contact number could inherit them. Foreign keys are enforced, the rows left behind are removed on upgrade, and contact numbers are never reused.
- Shared media: attachments in channels and videos in groups were published with their EXIF, GPS or MP4 location data. Photos, videos and audio in groups and channels are now stripped of hidden metadata before they are sent.
- Channels over Tor: the address of a channel was looked up through the system resolver before the request went to Tor, which could reveal it to the network. Channel requests now hand the address to Tor unresolved. Rotating or deleting a channel address could also lose the old address key before it was retired; it is now kept until the retirement is complete.
- Contact keys: the long-term key two contacts share never changed after pairing, so a copy taken from a device stayed usable until the contacts paired again. Between two 3.0.15 devices it now renews at the start of a connection.
- Calls: after a lost connection one phone could stay in a dead call for minutes, the call screen showed over the lock screen without the app lock, and the other side could turn a camera back on after it had been turned off. Fixed.
- Attachments: a text sent while a large video was transferring waited until the video had arrived, and queued parts of an attachment were offered again, which roughly doubled a transfer. Fixed.
- Groups: a member removal applied after the group key changed could be lost, a member leaving advanced the group key by itself, and the disappearing-message timer of a group was not sent to the members. Fixed.
- Sign-in protection: the erase-after-failures policy could be bypassed by stopping the app at the right moment or by waiting a day, and some failures of the key files could count the correct password as a failure. Fixed.
- Tor status: on phones whose connection keeps dropping the status could stay on "Publishing", and a wrong device clock was not reported. Fixed.
- Build: the Monero library build took settings from the environment that started it, so the same recipe could build different bytes on another machine, and the F-Droid recipe removed Gradle dependency verification. The native build now runs in a fixed environment and the recipe keeps verification on.

## Limitations of 3.0.13, fixed in 3.0.14

Kept as history. Both are fixed in 3.0.14. Neither is a vulnerability.

- First account on some devices: since 3.0.12 the app refuses to generate its Android key store key when the key store throws on the lookup of that key, so that a temporary key store failure can never replace the key that existing profiles depend on. On a fresh installation there is no profile to protect, but the guard still applied, and on devices whose key store throws when a key that was never created is looked up, account creation failed with "Setup Failed" on every attempt (Google Play's review device is such a device; 3.0.12 and 3.0.13 could not be set up there). No data was at risk: the failure happened before any profile existed. 3.0.14 generates the key for the first account regardless of the lookup and keeps the guard from then on; the dialog names the cause.
- Reproducibility (REPRO-13-01): 3.0.13 was reproducible only under the original `/src` build layout; F-Droid's canonical build path exposed a DWARF path variance in `libzargon2.so`, the Argon2 library the app build compiles from source and packages unstripped. Every other entry of the APK is identical between the two layouts. Fixed in 3.0.14, which compiles that library without debug sections; the 3.0.13 release manifest's statement that the F-Droid recipe builds the same payload holds only for a build at `/src`.

## Limitations of 3.0.12, fixed in 3.0.13

Kept as history. All are fixed in 3.0.13. None of them is known to have been exploited.

- Client authorization after a network change: Tor discards the credentials the app installs over its control port whenever its configuration changes, and the app changes it on every connectivity change. In 3.0.12, after the first such change following a Tor start, every locked-in contact was unreachable until Tor was restarted. The protected path never fell back to the open address; it was unavailable. Messages from such contacts still arrived.
- Tor 0.4.9.12: the release carries the Tor version that preceded the 0.4.9.13 security release of 23 September 2026.
- Assurance: the Tor wrapper hardening in the source tree was not compiled into 3.0.12 (the published library was used, with the transport applying the isolation and padding settings over the control port instead), and the Tor and lyrebird executables were verified only through the build's dependency verification, which the F-Droid build removes.

## Limitations of 3.0.11, fixed in 3.0.12

Kept as history. All four were fixed in 3.0.12. None of them is known to have been exploited.

- Call media: the per-call key derivation has a defect (the derived key material is zeroed before use, so the application-layer cipher adds no confidentiality or integrity) and video nonces can repeat when video is restarted within a call. In 3.0.11 call confidentiality rests on the Tor onion-service layer between the two devices; using the defect would require an adversary inside that connection or at an endpoint.
- Pairing: post-quantum protection at link pairing is confidentiality only; authentication is classical (X25519 ownership proofs bound to the out-of-band commitment and an Ed25519 signature), so only an adversary with a quantum computer active during the pairing could impersonate a peer. Nearby (QR/Bluetooth) pairing is classical.
- Onion address rotation: the announced next address was not republished after a restart in every case.
- Release build: the release script removed the dependency-verification metadata before the release build. The release script no longer does, but the reference F-Droid build still removes that metadata, and since 3.0.12 the published APK is the signed output of that build; see [docs/FDROID.md](docs/FDROID.md).

## Supported versions

Only the latest release receives fixes. Both people in a conversation need a compatible version; release notes say when a version is required.

## What is enforced by code and what is a design goal

[docs/protocol/SECURITY_CLAIMS.md](docs/protocol/SECURITY_CLAIMS.md) lists every public security claim with the code that enforces it, the test that proves it, its threat-model limits, the first version that shipped it, and its external review status.
