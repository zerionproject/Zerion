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
| September 2026 | internal assessment | project | whole Android product (3.0.11), followed by a second internal assessment of the remediated tree | remediation on the `security-r1` branch, not yet released | not published |

## Limitations of the previous release (3.0.11), fixed in 3.0.12

Recorded here so that no document overstates what 3.0.11 shipped. All four are fixed in 3.0.12, the current release; the entries are kept as the history of the previous one. None of them is known to have been exploited.

- Call media: the per-call key derivation has a defect (the derived key material is zeroed before use, so the application-layer cipher adds no confidentiality or integrity) and video nonces can repeat when video is restarted within a call. In 3.0.11 call confidentiality rests on the Tor onion-service layer between the two devices; using the defect would require an adversary inside that connection or at an endpoint.
- Pairing: post-quantum protection at link pairing is confidentiality only; authentication is classical (X25519 ownership proofs bound to the out-of-band commitment and an Ed25519 signature), so only an adversary with a quantum computer active during the pairing could impersonate a peer. Nearby (QR/Bluetooth) pairing is classical.
- Onion address rotation: the announced next address was not republished after a restart in every case.
- Release build: the release script removed the dependency-verification metadata before the release build.

## Supported versions

Only the latest release receives fixes. Both people in a conversation need a compatible version; release notes say when a version is required.

## What is enforced by code and what is a design goal

[docs/protocol/SECURITY_CLAIMS.md](docs/protocol/SECURITY_CLAIMS.md) lists every public security claim with the code that enforces it, the test that proves it, its threat-model limits, the first version that shipped it, and its external review status.
