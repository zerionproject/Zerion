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

## Limitations of the previous release (3.0.13), fixed in 3.0.14

Recorded here so that no document overstates what 3.0.13 shipped. Both are fixed in 3.0.14, the current release; the entries are kept as the history of the previous one. Neither is a vulnerability.

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
- Release build: the release script removed the dependency-verification metadata before the release build.

## Supported versions

Only the latest release receives fixes. Both people in a conversation need a compatible version; release notes say when a version is required.

## What is enforced by code and what is a design goal

[docs/protocol/SECURITY_CLAIMS.md](docs/protocol/SECURITY_CLAIMS.md) lists every public security claim with the code that enforces it, the test that proves it, its threat-model limits, the first version that shipped it, and its external review status.
