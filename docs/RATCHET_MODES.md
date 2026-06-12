# Zerion ratchet modes 1 / 2 / 3 / 3-Full — what each layer does

**Version:** 1.2
**Date:** 2026-05-20
**Status:** ACTIVE — describes the v1.7 shipped 1:1 message ratchet
**Author:** Zerion Project

> **v1.7 amendment.** Mode 3-Full ships as the default on new contacts.
> Every frame in both directions carries a fresh ML-KEM-768
> encapsulation against the peer's current ML-KEM public key; the
> shared secret is mixed into the per-frame body key via
> `HKDF(classicalMessageKey, ml_kem_shared_secret)`. The chain key is
> now per-stream (derived from `HKDF(rootKey, PCS_STREAM_CHAIN,
> streamNumber)` and advanced locally within the stream), so parallel
> Briar transport streams no longer share a single ratchet position.
> The previous per-epoch PQ rotation (Mode 3) becomes a fallback for
> mode-disabled paths only.

> **v1.6 amendment.** Phase 4d (January 2026) shipped Mode 3 framing on
> the wire (`0x2000` stream-header flag, per-frame PCS header, PQ
> chunks) but had three latent bugs that prevented any PQ epoch from
> completing end-to-end: responder chunk-type dispatch missing,
> responder shared secret wiped before use, factory state callbacks
> `null` in production. **v1.6 fixes all three**, plus adds
> cross-direction PQ mixing per epoch (one epoch now PQ-protects both
> directions of conversation), self-heal on crash + stuck-state
> recovery, and a pubkey-comparison tiebreak on simultaneous epoch
> starts. ML-KEM-768 secret is now actually mixed into the root key
> every 25 messages or 24 hours, both directions, persisted across
> reconnects. See [PCS_DESIGN.md §v1.6 amendment](PCS_DESIGN.md) for
> the full diff.

This document clarifies what the three "modes" of Zerion's pairwise ratchet actually mean and why none of them can be removed in isolation.

---

## Modes are layered, not alternatives

A common misreading is that Mode 1, Mode 2 and Mode 3 are three different ratchet protocols and the implementation picks one. That's wrong. They are three **stacked layers** of the same protocol. When the most recent layer (Mode 3) is active, all three layers run simultaneously on every message.

```
Mode 1                        symmetric chain-key ratchet
                              │   per-message forward secrecy
                              ▼
Mode 2  =  Mode 1  +          X25519 DH ratchet
                              │   post-compromise security (PCS)
                              ▼
Mode 3  =  Mode 2  +          ML-KEM-768 PQ ratchet (per epoch)
                              │   hybrid post-quantum PCS
                              ▼
Mode 3-Full  =  Mode 2  +     ML-KEM-768 PQ encap per frame
                                  per-message hybrid PQ
                                  (current default)
```

Mode 3-Full is built on top of Mode 2; Mode 2 is built on top of Mode 1.
You cannot remove the lower layers without breaking the higher one.
Mode 3 (per-epoch PQ rotation) remains as a fallback path; Mode 3-Full
replaces it as the default by encapsulating ML-KEM-768 on **every**
frame instead of every 25 frames.

---

## What each layer does on every message

### Mode 1 — symmetric chain-key ratchet

Every outbound message takes the current chain key, derives a fresh per-message AEAD key from it, then advances the chain key one step:

```
message_key  =  KDF(chain_key_i, MESSAGE_KEY_INPUT)
chain_key_{i+1}  =  KDF(chain_key_i, CHAIN_KEY_INPUT)
```

Once the message is sent, `chain_key_i` is wiped from memory. **Forward secrecy is per-frame:** even if the attacker captures `chain_key_{i+1}` later, they cannot derive `message_key_i` because the KDF is one-way.

Mode 1 is Briar's original ratchet. It provides forward secrecy and replay resistance but **no post-compromise security**: if an attacker captures the live `chain_key_i`, they can decrypt all future messages on that chain until something else (Mode 2 or 3) rotates the chain.

### Mode 2 — X25519 Diffie-Hellman ratchet

On top of Mode 1, every "chain start" (a new sending chain or a new receiving chain triggered by the peer's DH-step header) mixes a fresh X25519 DH agreement into the root key:

```
new_dh_priv  =  generate X25519 keypair
new_dh_pub   =  derive public from priv
dh_shared    =  X25519(my_new_dh_priv, peer_dh_pub)
new_root_key =  KDF(old_root_key, dh_shared)
new_chain_key =  KDF(new_root_key, CHAIN_KEY_INPUT)
```

The new DH public key rides in the next message's header. The peer sees it, runs the matching DH, and advances their root key the same way.

**Post-compromise security:** if an attacker captured my state at time T, the next DH step (on the next chain start, typically within a few messages) injects fresh randomness the attacker does not know. After one round trip, the attacker is locked out again — even though they had my keys at T.

Mode 2 = Signal's classic Double Ratchet (without the X3DH replacement). Forward secrecy + PCS. No quantum resistance.

### Mode 3 — ML-KEM-768 post-quantum ratchet

On top of Mode 2, every PQ epoch (default: every 25 sent messages OR every 24 hours, whichever fires first) mixes a fresh ML-KEM-768 encapsulation into the root key:

```
peer's_kem_pub                      (received from peer's earlier message)
ct, kem_shared  =  ML-KEM-Encaps(peer's_kem_pub)
new_root_key   =  KDF(old_root_key, kem_shared)
                                    (ct rides in subsequent messages
                                     as multi-chunk header field;
                                     peer decapsulates with their priv)
```

The encapsulated PQ shared secret is large (1088 bytes for ML-KEM-768 ciphertext). To avoid blowing up every message, the ciphertext is **split into 256-byte chunks** and dribbled across consecutive frames in the PQ epoch. The peer reassembles, decapsulates, and mixes into their root key at the same epoch boundary.

**Hybrid quantum-resistant PCS:** even against a future quantum attacker who can break the X25519 DH agreements (Mode 2's contribution) retroactively, the ML-KEM contribution to the root key remains secure. A quantum adversary cannot derive subsequent message keys without breaking ML-KEM-768 itself.

Mode 3 = Mode 2 + per-epoch PQ-PCS. Retained as a fallback path.

### Mode 3-Full — per-message ML-KEM-768 hybrid (current default)

On top of Mode 2, **every single frame** carries a fresh ML-KEM-768
encapsulation against the peer's currently advertised ML-KEM public
key. The chain key is per-stream (each transport stream derives its
own initial chain key from `HKDF(rootKey, PCS_STREAM_CHAIN,
streamNumber_8B)` and advances locally per frame within the stream),
and the per-frame body AEAD key is the hybrid:

```
classicalMK    =  KDF(streamChainKey_i, MESSAGE_KEY_INPUT)
ct, kem_ss     =  ML-KEM-Encaps(peer's currently advertised ML-KEM pubkey)
bodyKey_i      =  HKDF(classicalMK, kem_ss)
streamChainKey_{i+1}  =  KDF(streamChainKey_i, CHAIN_KEY_INPUT)
```

Each frame also advertises the sender's freshly rotated ML-KEM public
key, so subsequent peer-to-sender frames encapsulate against the
newest key. Recent sender keypairs are retained in a per-contact LRU
(cap 64) so the peer's CTs against slightly stale public keys still
decapsulate cleanly. The frame header carries a 16-byte `kpId`
(truncated SHA-256 of the encapsulation key) to disambiguate.

**Per-message hybrid quantum-resistant PCS:** every body key includes
fresh ML-KEM entropy. A quantum adversary who breaks past X25519 DH
agreements still cannot derive any future body key without
also breaking ML-KEM-768 — on a per-frame basis, not per-epoch.

Mode 3-Full is the active default on new Zerion 1:1 contacts since v1.7.

---

## Why removing any layer would break things

| Removed layer | Effect |
|---|---|
| Mode 1 only | Impossible. The symmetric chain is the only thing that derives per-message AEAD keys. Removing it means no message encryption at all. |
| Mode 2 only | Removing the DH ratchet drops continuous classical PCS. Mode 3-Full's per-message PQ rotation still rotates the body key every frame, so post-compromise recovery happens every frame against quantum adversaries; classical PCS recovery would be lost. |
| Mode 3 only | No-op when Mode 3-Full is active. Mode 3 is retained as a fallback for legacy code paths. |
| Mode 3-Full only | Drops per-message hybrid PQ. Falls back to Mode 3 per-epoch PQ. Acceptable as a feature gate; not the v1.7 default. |
| Modes 1+2 | See "Mode 1 only" — impossible. |
| Modes 2+3+3-Full | Drops both classical PCS and hybrid PQ. Reverts to Briar's original Mode 1 (forward-secret per-message symmetric chain, no recovery from key compromise). |

**Practical conclusion:** all layers are kept. Mode 3-Full is the active
default on new Zerion contacts since v1.7; Mode 3 remains as a fallback
path; Mode 1 + Mode 2 run as the foundation.

---

## What ships today

### Android — Mode 3-Full active from message zero (v1.7)

New 1:1 contacts initialize directly into Mode 3-Full via
`PcsSessionState.createInitialMode3Full` in `ContactManagerImpl`. Every
outbound frame is Mode 3-Full framed (stream-header carries the
Mode 3-Full flag bit) and includes a fresh ML-KEM-768 encapsulation
against the peer's currently advertised public key. Each transport
stream derives its own initial chain key from `HKDF(rootKey,
PCS_STREAM_CHAIN, streamNumber_8B)` and advances it locally; the
chain key is never persisted across streams.

### iOS — parity tracked separately

iOS parity for the Mode 3-Full per-message path is tracked separately
against the wire spec in
[`docs-internal/V1_7_IOS_PARITY_SPEC.md`](../docs-internal/V1_7_IOS_PARITY_SPEC.md);
this document does not assert a specific iOS rollout state. The receive
path on both platforms can decode the per-epoch Mode 3 format, so a peer
that has not yet enabled Mode 3-Full interoperates over the per-epoch
fallback. On any cross-platform channel where one side has not enabled
Mode 3-Full, that direction's ongoing transport ratchet runs at the
fallback level rather than per-message PQ; the direction in which both
sides have Mode 3-Full enabled runs per-message PQ. The initial contact
handshake (B.4) and identity layer (B.3) are hybrid PQ on both sides —
only the ongoing transport ratchet's PQ granularity depends on each
side's Mode 3-Full state.

---

## What this means for group chat

Group posts ride on the existing 1:1 channels. Whichever mode each 1:1 channel is in determines what protection the group post gets on that hop:

- **Both peers on Mode 3-Full:** per-message hybrid PQ on both halves of
  the pair → full protection on that hop.
- **One peer not yet on Mode 3-Full:** that direction falls back to the
  per-epoch Mode 3 (or Mode 2) receive path; the per-epoch fallback still
  carries hybrid PQ at epoch granularity.

As each platform's Mode 3-Full rollout completes (iOS parity tracked per
[`docs-internal/V1_7_IOS_PARITY_SPEC.md`](../docs-internal/V1_7_IOS_PARITY_SPEC.md)),
all group hops converge on Mode 3-Full across both platforms.

---

## References (code)

| What | File:line |
|---|---|
| Mode-3 initialization (Android) | `bramble-core/src/main/java/org/briarproject/bramble/contact/ContactManagerImpl.java:375-378` |
| Mode-3 stream-header flag | `bramble-core/src/main/java/org/briarproject/bramble/crypto/pcs/PcsStreamEncrypterImpl.java:266-268` |
| PQ epoch trigger | `bramble-core/src/main/java/org/briarproject/bramble/crypto/pcs/PcsStreamEncrypterImpl.java:204-226` |
| PQ epoch thresholds | `bramble-api/src/main/java/org/briarproject/bramble/api/crypto/pcs/PcsConstants.java:110-113` (`PQ_EPOCH_MESSAGE_THRESHOLD = 25`, `PQ_EPOCH_TIME_THRESHOLD_MS = 24h`) |
| `MODE3_ENABLED` flag (per-epoch fallback) | `bramble-api/src/main/java/org/briarproject/bramble/api/crypto/pcs/PcsConstants.java:71` |
| `MODE3_FULL_ENABLED` flag (per-message default) | `bramble-api/src/main/java/org/briarproject/bramble/api/crypto/pcs/PcsConstants.java` |
| Mode 3-Full per-message ML-KEM encapsulation path | `bramble-core/src/main/java/org/briarproject/bramble/crypto/pcs/PcsStreamEncrypterImpl.java` |
| Mode 3-Full initialization (`createInitialMode3Full`) | `bramble-api/src/main/java/org/briarproject/bramble/api/crypto/pcs/PcsSessionState.java` |
| Receive PQ chunks (per-epoch fallback) | `bramble-core/src/main/java/org/briarproject/bramble/crypto/pcs/PcsStreamDecrypterImpl.java:261-284` |

## References (design docs)

- [`TRIPLE_RATCHET_DESIGN.md`](TRIPLE_RATCHET_DESIGN.md) — full key-schedule specification for the three layers
- [`PCS_DESIGN.md`](PCS_DESIGN.md) — original pairwise PCS design
- [`SECURITY_OVERVIEW.md`](SECURITY_OVERVIEW.md) — high-level summary
