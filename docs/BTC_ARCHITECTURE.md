# Bitcoin (BTC) Wallet Architecture

Current shipping architecture of the Bitcoin wallet in the Zerion vault. Every
statement is verified against the production implementation; intentionally
absent or disabled features are called out as such. Shared ZVault behavior
(identity, password layering, storage/crash-safety, locking, network-privacy
philosophy, recovery, release) lives in `WALLET_ARCHITECTURE.md`; this document
covers only Bitcoin specifics.

Primary code: `zerion-android/src/main/java/com/professor/zerion/android/vault/wallet/btc/`
and the vault UI in `vault/ui/`. Bitcoin primitives use bitcoinj on mainnet.

## Storage and security

- **Self-custodial, device-local.** Keys are derived on-device from a BIP39
  mnemonic (`BtcKeys.deriveKey`, `MnemonicCode.toSeed`); nothing is custodial.
- **Seed sealing.** The mnemonic is stored as a vault `WALLET` item
  (`WalletStore.createWallet`) as `[version byte][UTF-8 mnemonic]`, encrypted by
  the vault (`VaultManager.addItem` / `addItemWithPassword`); plaintext buffers
  are shredded after use.
- **KDF.** Argon2id. Vault master default 256 MiB / 3 iterations / parallelism 1
  / 32-byte key; the per-wallet ("extra") password uses 64 MiB / 3 iterations.
  The native Argon2 is preferred with a fail-closed BouncyCastle fallback.
- **Per-wallet password.** A wallet may carry its own password. Reads are
  fail-closed (`WalletStore.accessFor`): a password-protected wallet with an
  empty/absent password is rejected and never falls through to the no-password
  path.
- **Wallet-section authentication.** The BTC section has its own credential
  (PBKDF2, 120,000 iterations, 32-byte hash) distinct from the vault master
  password, with exponential backoff after repeated failures.
- **Transaction-specific authorization.** `SendGate` holds one pending plan and
  authorizes a send only when the reviewed transaction fingerprint (SHA-256 over
  sorted inputs+outputs) matches the plan and the credential re-check passes;
  it is single-use. There is no combined review-and-send.
- **Lock behavior.** Wallet access is bound to a lock generation
  (`WalletSessionGuard`): access requires the vault unlocked, the section
  unlocked, and the generation unchanged. Any vault lock increments the
  generation, so unlocking the vault never implies wallet access and a lock
  invalidates an in-flight wallet session.

## Networking

- **Tor by default.** Per-wallet routing defaults to Tor; the Electrum client
  connects through the local SOCKS proxy for any non-local, non-direct endpoint.
  Even the built-in TLS default node is reached over Tor unless the user
  explicitly changes routing.
- **Stream isolation.** SOCKS5 username/password gives per-circuit isolation, with
  distinct isolation contexts per wallet and per purpose (scan / broadcast /
  silent-payment / price), e.g. broadcast uses its own tag.
- **Servers.** Built-in defaults are an onion Electrum node (preferred) and a TLS
  default (`electrum.blockstream.info:50002`); the user can add and remove their
  own nodes, including a LAN/local node. There is no separate "vetted tier" naming
  on BTC (that is an XMR concept); BTC distinguishes built-in-default from
  user-added.
- **TLS.** Port 50002 is TLS. If a certificate pin is set (captured trust-on-first-
  use, stored per node, SHA-256), the client fails closed on mismatch; otherwise
  standard CA validation plus explicit hostname verification.
- **No automatic clearnet/plaintext fallback.** Fallbacks are only other
  configured Electrum endpoints over the same SOCKS proxy; no path downgrades a
  Tor connection to direct or plaintext on failure. A PLAINTEXT mode exists only
  for a user-added non-TLS/LAN node, never as a fallback. Endpoint invariants are
  enforced (direct requires TLS; onion cannot be local; pin only for TLS).
- **Direct (non-Tor) mode.** Explicit opt-in only: it requires a verified TLS
  endpoint, is produced only when the user selects Direct routing, and the UI
  requires confirming a warning dialog. The default stays Tor.

## Electrum server trust model (accepted architectural limitation)

The Bitcoin client talks to an Electrum server and does **not** perform SPV
(header-chain / merkle-proof) verification: balances, UTXO existence, and
confirmation counts are server assertions, checked only against local structural
validation, not against proof-of-work. This is a deliberate limitation of the
current lightweight client, not a defect and not a shipped feature — it is stated
here so the guarantee is not overstated.

What a malicious or compromised Electrum server **cannot** do (bounded by local
cryptography, so no fund-loss vector): it cannot derive private keys or the seed;
it cannot sign or alter a transaction; it cannot redirect an authenticated,
signed transaction (the signature commits every input and output, and per-input
value is bound by the segwit sighash, so any tampered UTXO value simply voids the
signature); it cannot make the wallet spend more than the reviewed plan (the fee
is fixed by the committed outpoints and outputs). Broadcast uncertainty is
resolved conservatively (POSSIBLY_SENT, never a false FAILED that could free a
still-live reservation).

What it **may** influence (availability / observation, within local-validation
limits): it can withhold or delay responses (denial of service), hide a
transaction or UTXO, report a stale tip, and lie about **confirmation counts** —
so a receiver could be shown a payment as confirmed that is not yet buried, or be
shown fewer confirmations than exist. A receiver accepting goods against an
unverified confirmation is the practical risk. Mitigations in place: scan and
broadcast use different endpoints where possible, and the pinned/TLS-verified
connection resists a network-level MITM; full independent header/merkle
verification is tracked as **future hardening**, deliberately not added now
because it would materially change the client for a receiver-side confirmation
concern with no fund-loss exposure.

## Receive

- **BIP84 native segwit (bech32 P2WPKH).** Derivation `m/84'/0'/account'/change/
  index` on mainnet.
- **Fresh address per receive** with a gap limit of 20; the scan returns the first
  unused receive address and the receive index is persisted. Change goes to a
  fresh change address.
- **Address-reuse avoidance.** Fresh change addresses; the privacy analyzer flags
  reuse.
- **Silent Payments (receive).** Implemented but disabled by default (see below).

## Send and privacy

- **Coin selection.** Standard policy is largest-value-first; strict/extreme policy
  is cluster-aware (prefers the smallest single cluster that covers the target,
  then largest clusters first, largest coins within a cluster). Dust limit 294 sat.
- **Coin Control.** Manual UTXO selection with per-UTXO freeze and label; frozen
  coins are never auto-selected and are rejected if manually forced.
- **Privacy analyzer.** Each send is analyzed and rated (HIGH/MEDIUM/LOW), flagging
  cluster merges, silent-payment mixing, address reuse, and extra inputs, and
  surfaced in the send review.
- **Extreme Privacy Mode.** A per-wallet setting mapping to the strict, cluster-
  aware policy; a send that would merge more than one cluster throws unless the
  user explicitly allows the merge. Fully enabled in production.
- **Authorization fingerprint.** The send-gate fingerprint is a SHA-256 over the
  sorted input outpoints (txid:vout) and the outputs (address:amount, change
  included). The **identical `SendPlan` object** that was fingerprinted at Review
  is the object signed and broadcast — nothing is re-planned or re-scanned in
  between — and `buildAndSign` is deterministic for the fields the fingerprint
  does not name (version = 2, opt-in-RBF sequence, default locktime), so
  *reviewed == authenticated == signed == broadcast* holds field-for-field.
  Per-input value is bound at signing by the segwit sighash (a wrong value voids
  the signature), and the fee is fully determined by the committed
  outpoints+outputs. The fingerprint does not currently name version, locktime,
  sequence, or wallet/network identity because those are compile-time constants;
  **it must be extended to cover them before any feature makes them variable**
  (user-selectable locktime, non-RBF/CPFP sends).
- **BIP69** input/output ordering is applied before signing. **Decision: KEEP.**
  Rationale: deterministic ordering removes the wallet-specific ordering signal
  (input/output order no longer fingerprints the sending wallet), which is a net
  anti-fingerprinting improvement for a privacy-focused wallet, and it makes the
  signed transaction a deterministic function of the reviewed plan (supporting the
  authorization binding above). The trade-off — BIP69 itself is a detectable
  policy shared with other BIP69 wallets — is acceptable because it groups Zerion
  with a large anonymity set rather than exposing a unique per-wallet ordering.
  Revisit only if the ecosystem's ordering norms shift materially.
- **Transaction version / RBF.** Version 2, opt-in RBF (input sequence
  `0xfffffffd`).
- **Pending-input reservation.** Outpoints of broadcast-but-unconfirmed
  transactions are persisted and reserved so a later send cannot select them,
  preventing an accidental double-spend; the reservation is reconciled against
  confirmed chain state (states BROADCASTING -> SENT / POSSIBLY_SENT / FAILED with
  a confirmation cross-check on the broadcast endpoint).
- **Broadcast.** Over Tor via a dedicated broadcast endpoint and isolation tag,
  on a different Electrum node from the scan node where possible. Server rejection
  is distinguished from transport uncertainty (uncertain broadcasts are marked
  POSSIBLY_SENT rather than failed).

## Deferred

- **Anti-fee-sniping `nLockTime`.** Setting the transaction `nLockTime` to the
  current block height (so a broadcast transaction cannot be trivially re-mined
  into an earlier block) is a reviewed, deferred hardening item, not yet enabled.

## Experimental / disabled — actual status

- **Payjoin.** Disabled in production by a build-flag hard gate
  (`PayjoinFeature.PRODUCTION_ENABLED = false`); there is no runtime override.
  Both offering and starting Payjoin are unreachable in a production build. The
  full plumbing exists but is dormant.
- **Silent Payments (receiving).** Implemented but disabled by default via a
  per-wallet runtime toggle (default off); scanning no-ops when off and is guarded
  by cryptographic self-tests before any spend. Recovering received SP funds is a
  Taproot key-path sweep to a normal address.
- **Silent Payments (sending to an `sp1` address).** NOT IMPLEMENTED: address
  validation does not accept silent-payment addresses and there is no SP payment-
  output construction path. The SP encoder is used only to display the wallet's
  own receive address.
