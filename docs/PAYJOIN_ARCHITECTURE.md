# Payjoin v2 (BIP77) integration architecture

> **CLASSIFICATION: DESIGN / NON-SHIPPING.** This is a forward design for a
> feature that is **disabled in production** (`PayjoinFeature.PRODUCTION_ENABLED
> = false`, no runtime override). Signing is not wired and no funded Payjoin has
> been performed. It does NOT describe shipping behavior. The authoritative source
> for current, shipping Bitcoin behavior is `BTC_ARCHITECTURE.md`, which records
> Payjoin as build-gated OFF. Keep this document only while the design is actively
> maintained; if it is abandoned, delete it (git history preserves it).

This document defines the ownership, signing, authentication, and relay model
that the Payjoin work implements before any wallet key touches a Payjoin proposal.

## Components

- Java wallet (bitcoinj 0.16.3, BIP84 SegWit). Sole owner of seeds, keys,
  derivation paths, and UTXO state. Encrypted, machine-bound, shredded after
  use.
- Native library `libpayjoin_ffi.so` (Rust). Provides BIP77 protocol state
  machine, OHTTP/HPKE transport encoding, URI parsing, and PSBT structural
  handling. Holds no wallet secrets.
- `PayjoinValidator` / `PayjoinSession` (Java). Defense-in-depth structural
  validation of any proposal, independent of the native layer.
- Untrusted directory/relay, reached only over Tor with a dedicated isolation
  context. No Zerion-operated coordinator.

## Trust boundary

The native layer and the counterparty proposal are both untrusted with respect
to ownership. The native layer performs protocol mechanics; it never decides
which inputs belong to the wallet. The counterparty can propose any PSBT; it
must never be able to cause the wallet to sign an input the wallet does not
independently prove it owns.

## The one source of truth for input ownership

Ownership is derived exclusively from Java wallet state:

1. The wallet enumerates its own UTXOs from its own key tree (BIP84 external and
   change chains, plus any Silent Payment outputs it has already claimed).
2. For every input in any PSBT, ownership is decided by matching the input's
   `outpoint` (txid:vout) against the wallet's own UTXO set, and confirming the
   input's `scriptPubKey` derives from a wallet key at a known derivation path.
3. An input is wallet-owned only if both hold. The `witness_utxo` /
   `non_witness_utxo` supplied inside the PSBT is treated as claimed data to be
   checked against wallet state, never as proof.

No field in the proposal, and no return value from the native layer, can mark an
input as wallet-owned. A `is_script_owned` style callback exposed by the native
receiver API is answered by this same Java-side derivation check, so the native
layer learns ownership from the wallet, not the reverse.

## Sender flow (the only flow enabled first)

Zerion acts as the Payjoin sender paying a receiver's BIP77 URI. Receiving is
out of scope for the first integration.

```
1. Java builds the original transaction (recipient + change) from wallet UTXOs
   selected under the existing privacy engine, and produces the original PSBT.
   Every input here is wallet-owned by construction (step "source of truth").

2. Java serializes the original PSBT and passes it, plus the parsed BIP77 URI,
   across the FFI to the native sender. Native encodes the OHTTP/HPKE request.

3. PayjoinSession sends the encrypted request over Tor (dedicated isolation) to
   the untrusted relay and receives the receiver's proposal PSBT, or fails
   closed (null / timeout / relay down -> normal send offered explicitly).

4. Native parses the proposal into a structured form. Java then re-derives, from
   wallet state alone:
     - the exact set of inputs that are wallet-owned (outpoint + script match),
     - that all original wallet inputs are still present and unmodified,
     - that the recipient output and amount are unchanged,
     - that change is present and not reduced beyond the agreed fee bound,
     - that no unexpected outputs or scripts were introduced.
   PayjoinValidator enforces these structurally, in parallel with any native
   check. Disagreement fails closed.

5. Only if the proposal is VALIDATED does Java build the final transaction to
   sign. The set of inputs to sign is exactly the wallet-owned set from step 4,
   recomputed from wallet state, never taken from the proposal or the native
   layer. Receiver-contributed inputs are left unsigned.

6. Fresh authentication (below) is required, bound to the final transaction.
   Java signs only its own inputs using P2WPKH BIP143. The signed PSBT is passed
   back to native only to finalize/extract the network transaction, or Java
   finalizes directly. Broadcast follows the existing durable-broadcast path.
```

## Signing authorization

- Signing is gated by the existing `SendGate`: single-use, bound to a plan
  fingerprint. For Payjoin the fingerprint is computed over the final
  transaction: the ordered wallet-owned inputs to be signed, all outputs
  (script + amount), the fee, and the locktime/version.
- The authentication captured from the user (fresh wallet PIN, per the
  per-transaction auth rule) is bound to that fingerprint. Any change to the
  final transaction between authorization and signing invalidates the gate.
- The wallet signs input-by-input, and only inputs whose derivation path it
  holds. A signing request for an input outside the wallet-owned set is a
  programming error and aborts the send.

## The exact fresh-auth boundary

Authentication happens once, immediately before signing the final transaction,
and covers the transaction that will actually be broadcast:

- Not at proposal request time (the final tx is not yet known).
- Not reused from the original-transaction authorization (the proposal can
  change inputs, fee, and change).
- The user reviews the final transaction (recipient unchanged, fee, change,
  added receiver inputs count) and authenticates against that exact fingerprint.
- The credential is used to unlock signing material, then shredded. It never
  crosses the FFI.

## Relay / directory policy

- Payjoin is disabled until a BIP77 directory/relay is vetted over Tor.
- The relay is untrusted. It observes only encrypted OHTTP request size and
  timing, never wallet keys, the wallet graph, balances, unrelated UTXOs, or
  labels.
- Zerion does not run a Payjoin coordinator or relay to make the feature work.
  If no third-party directory can be vetted, Payjoin stays disabled and the
  wallet offers only a normal send.
- Vetting criteria and the candidate result are recorded in
  `zerion-android/native/payjoin/RELAY_VETTING.md`.

## FFI surface constraints

The Java -> Rust boundary carries only: serialized PSBTs, the parsed URI/OHTTP
parameters, and opaque protocol-context handles. It never carries seed phrases,
master keys, extended private keys, ZVault credentials, derivation secrets, or
unrelated wallet state. A native panic or binding failure maps to a controlled
Payjoin failure; it never continues the send.

## What is deliberately not built yet

- Real signing is not connected.
- Payjoin receiving is not implemented.
- No funded Payjoin transaction is performed until relay vetting, the ownership
  model, and boundary hardening are reviewed and approved.
