# Monero (XMR) Wallet Architecture

Current shipping architecture of the Monero wallet in the Zerion vault. Every
statement here is verified against the production implementation; where a feature
is intentionally absent it is called out as such rather than described as if
present.

Primary code: `zerion-android/src/main/java/com/professor/zerion/android/vault/wallet/xmr/`,
the UI in `vault/ui/Xmr*.java`, and the native JNI boundary in
`packaging/monero-android/jni/zmonero.cpp` (built into `libzmonero.so`). The
native layer wraps Monero `wallet2_api`, pinned to v0.18.5.1 (commit
`4f92268d`); see `packaging/monero-android/PROVENANCE.md`. Shared ZVault behavior
is in `WALLET_ARCHITECTURE.md`; this document covers Monero specifics.

## Storage layers and the Store-1 invariant

A Monero wallet in Zerion is a two-layer construction on disk, both derived from
the single 25-word seed. The seed is the ultimate recovery secret; the spend
keys derived from it are themselves fund-critical whenever they are present in
memory (only during a transient spend session), which is why the spend wallet is
opened only to send and closed immediately after.

- The **seed** is sealed as a vault item under the wallet password (see the
  security-invariants doc for the vault AEAD/KDF). It is never written to disk in
  the clear and is wiped from memory after use.
- The **spend wallet** — files `w` and `w.keys` — holds the spend key. `w.keys`
  is encrypted with a main-file password derived from the wallet password via
  Argon2id (`XmrWalletKek.deriveMainFilePassword`), so opening the spend wallet
  requires the wallet password.
- The **background (view-only) wallet** — files `w.background` and
  `w.background.keys` — holds only the view key. Its keys file is encrypted under
  a separate random, vault-tier background credential (not the wallet password).
  It is produced from the spend wallet by Monero's background-sync mechanism
  (`wallet2::setup_background_sync` / `store_background_cache`).

**Store-1 invariant:** decrypting the vault tier alone (the background
credential) yields a spend-keyless wallet. Obtaining spend authority requires the
wallet password to derive the main-file key and open `w.keys`. A compromise of
the vault master key without the wallet password cannot spend XMR.

**Persistent sync cache.** The native wallet files (`w`, `w.keys`,
`w.background`, `w.background.keys`) are the wallet2 scan cache and keys, stored
in the wallet's live directory keyed by the immutable wallet id. They are bound
to the wallet identity (address fingerprint), so a cache that does not match the
opened wallet is rejected rather than trusted. Deleting a wallet erases these
files (secure delete), so wallet deletion is a true erasure, not just a
de-listing. The cache is derived state: it can always be rebuilt from the seed,
which is why a rescan can discard and rebuild it without risk.

## Runtime model

The ordinary wallet view runs against the **background (view-only) wallet**:
opening a wallet for viewing (`openWalletForView`) and the sync loop
(`XmrSyncManager`) use `w.background`, so day-to-day balance and history display
never bring the spend key into memory. The background wallet is opened with the
vault-tier credential and requires no wallet-password prompt.

A **transient spend session** is opened only when the user sends. It opens the
spend wallet `w` with the wallet-password-derived key, builds and relays exactly
one transaction, and is closed immediately. The wallet password is required only
for this operation.

On vault lock, the session is invalidated: the sync loop is stopped, the pending
recovery-phrase hand-off is wiped, any in-flight send authorization is
invalidated, and the native handles are closed on the session executor. A lock
wins every race with an in-flight scan or send.

## Send

The send path is a state machine (`XmrSendFlow`) with no UI as a security
authority. One send proceeds:

1. **Stage A — authenticate to open the spend wallet.** The wallet password
   derives the main-file key and opens `w`.
2. **Review from the exact signed transaction.** The transaction is built and
   signed (not relayed); the amount, final fee, dust and txids shown for review
   are read from that exact prepared transaction, never guessed. The consumed
   input total (amount + fee + change) is read for the reservation.
3. **Stage B — fresh per-transaction authorization.** A second, fresh
   authentication produces a single-use token bound to the snapshot fingerprint,
   ownership, session epoch and lock generation.
4. **Serialized relay.** On one session-executor operation: re-validate against
   the live native object, compare the fingerprint to the authorized one, consume
   the authorization once, capture the connected relay endpoint, write the
   durable spend journal, and only then relay the same prepared transaction.
   There is no thread or UI gap between final validation and the journal + relay,
   and no silent reconstruction of a different transaction or node.

**Pending / balance overlay.** On relay (success or uncertain), a durable record
(`XmrPendingSend`) is written with the exact txids, amount, fee, net debit and
consumed-input total. It drives two independent lifetimes:

- **Balance reservation.** The displayed spendable balance is reduced by the
  consumed-input total until the spend has *converged* into the background cache
  (the spend wallet's post-relay state written back via `store_background_cache`,
  after which the background balance itself excludes the spent outputs). The
  reservation is released only on positive convergence, never on history
  observation, and is never double-subtracted. The reserved value is the full
  consumed input, not the net debit, because a view-only wallet still counts the
  spent inputs and re-scans the change output.
- **Outgoing history row.** The record is permanent outgoing history (see below).

**Reconciliation.** The spend journal is reconciled against canonical history to
resolve or quarantine an uncertain relay. A definitively unresolved journal
quarantines further spends until cleared.

## External-spend reconciliation

A view-only wallet cannot compute key images, so an output that a **different
wallet holding the same seed** has spent stays unspent in the view cache and
would be shown as spendable. Zerion reconciles this with the supported wallet2
mechanism rather than a parallel representation:

- The background wallet scans the chain over Tor as normal. wallet2 records a
  transaction that spends one of its outputs (matched by global output index,
  not key image) as a *plausible spend* in the background cache
  (`m_background_sync_data`), and the periodic cache store persists it.
- On every wallet entry, once the wallet password is supplied,
  `reconcileExternalSpends` opens the spend wallet locally with no view session
  holding the cache. Opening it runs wallet2
  `process_background_cache_on_open`, which replays those plausible spends with
  the spend key present, resolves each real key image and marks the
  externally-spent outputs spent. Storing the spend wallet regenerates
  `w.background` carrying the spent flags (a store on the CustomPassword main
  wallet updates the background cache), so the reopened view excludes them.
- This is **local**: no daemon is contacted for the reconciliation, and the
  spend key is in memory only for this transient step, gated by the main-file
  password derived from the wallet password. Store-1 holds — vault-tier access
  alone still cannot open the spend wallet. The step is best-effort: on any
  failure the cache is left unchanged and the displayed balance is never
  increased, and the next entry retries.
- The same store also incorporates any of Zerion's own sends the view has since
  scanned, so every pending send the reopened spend wallet authoritatively
  reports as outgoing is converged there. Its reservation is released exactly
  once, so a send whose relay-time convergence failed is released when its spend
  genuinely lands and the same debit is never subtracted twice. An external
  spend carries no pending record, so it only ever reduces the raw balance via
  the resolved spent flag.
- Correctness across a rescan: a rescan rebuilds the cache from the seed and
  re-activates every reservation (`deconvergePendingSends`); the background
  wallet re-scans the external spend and re-records the plausible spend, and the
  next entry re-reconciles it, so no stale spent-state can outlive a rebuild and
  no already-spent output reappears as spendable.
- The daemon-based alternative (`export_key_images` on the spend wallet →
  `import_key_images` with `check_spent`, which queries `/is_key_image_spent`)
  is the mechanism for a wallet with no scanned background cache to draw on; it
  requires a trusted daemon and is not the path this separate-file layout is
  built around. It remains available as future hardening, not a correctness
  dependency.

## History

- **Incoming** transactions are detected by the view key and appear in canonical
  wallet2 history as they are scanned.
- **Outgoing** transactions cannot be reconstructed by a view-only wallet (it
  cannot compute key images to recognise its own spends; after a mined send it
  only sees the change return, and after a rescan it has no knowledge of past
  sends at all). Zerion therefore treats its own record of each send as locally
  authoritative, permanent outgoing history. `XmrWalletManager.mergeOutgoingHistory`
  shows one outgoing row per txid using the exact locally known amount and fee,
  enriched with canonical height/confirmations/mined state once the chain has
  observed the txid, and suppresses the canonical row for that txid (which a view
  wallet would otherwise surface only as the change coming back in). Rows are
  deduplicated by txid, newest first.
- Outgoing history **survives restart** (records reloaded on open), **rescan**
  (records are re-activated, never deleted) and **rename** (records are carried
  over and re-bound to the new wallet id). History is published every sync cycle,
  so rows appear while scanning, not only at full sync.

## Receive

Receiving uses fresh Monero **subaddresses** (never the primary address). The
issued receive index is tracked crash-safely in a durable ledger
(`XmrSubaddressLedger` / `XmrReceiveJsonStore`); a pool of subaddress strings is
pre-generated at open time. wallet2's default subaddress lookahead is 50 major x
200 minor, so a clean seed restore rediscovers previously used subaddresses from
the seed and chain alone, without depending on the local receive-index metadata.

## Restore and rescan

- **Date to height (versioned checkpoints).** A chosen calendar date maps to a
  restore height via `XmrBirthday.heightForDate`, which extrapolates from the
  nearest entry in a versioned table of trusted (timestamp, height) checkpoints
  using Monero's target block time, minus a safety margin that **grows with the
  extrapolation distance**. The governing invariant is *false-early is acceptable,
  false-late is not*: the growing margin guarantees that accumulated block-rate
  drift over long ranges can never place the scan start after a transaction on the
  requested date. A verified checkpoint is appended each release so the
  extrapolation distance stays small; the single fixed-anchor extrapolation it
  replaced could drift late for dates far from the anchor.
- **Recovering-from-seed marker.** A freshly restored/rescanned background wallet
  is stored with `watch_only == false`, so wallet2's `isNewWallet` would classify
  it as new and its `doInit` would fast-forward the refresh height to the daemon
  tip on connect, silently skipping all history before now. The sync loop marks
  the wallet recovering-from-seed (`setRecoveringFromSeed(true)`) before each
  connect, which keeps the stored early height in place so the scan starts there.
  (The public `setRefreshFromBlockHeight` cannot repair this after connect — it
  is a no-op on a background wallet — so the height is preserved through the
  connect, not corrected afterward.) Source-verified lifetime: `m_recoveringFromSeed`
  is read only by `WalletImpl::isNewWallet` (which gates the one `doInit`
  fast-forward) and touches nothing in refresh, cache, reconnect, hash handling or
  output scanning, so setting it before every connect is benign and needs no
  explicit clear — a daemon may refine progress but never move the start later
  than this locally trusted bound.
- **Background cache rebuild.** A rescan re-seals the wallet from the seed at the
  new height and regenerates the background cache from the spend wallet. Because
  changing the birthday regenerates the background cache from the spend wallet,
  and the spend wallet's keys are sealed under the wallet password (Store-1), a
  rescan legitimately requires a single, scoped wallet-password prompt (entered
  once in the rescan dialog, returning directly to sync — not a full re-login).
- **History while scanning.** History is published every cycle; found rows appear
  progressively.

## Networking

Sync and relay are **Tor-only** by default: the sync loop connects only to the
configured onion nodes over the local SOCKS proxy and never falls back to
clearnet; when no node connects the state is OFFLINE, never an endless spinner
and never a direct connection. Node selection (`XmrNodeConfig`) has four tiers:
**OWN** (the user's own node, best privacy, used exclusively), **VETTED** (the
Zerion-vetted onion node set, the default, never marked "trusted"), **CUSTOM**
(user-added remote nodes over Tor), and **DIRECT** (an explicit clearnet node,
reduced privacy, exclusive and opt-in behind a warning). The vetted onion node
set is defined in code, not in configuration. Failover is sequential and
bounded. A daemon is never trusted for cryptographic correctness — it cannot
forge ownership of outputs or fabricate transactions. It can, however, censor,
delay, withhold responses, and lie about tip/availability, producing stale or
incomplete state; against that the wallet fails closed for correctness and
preserves the last known good balance/history rather than replacing it with an
empty or degraded one on a transient error.

## Fiat display

`XmrPrice` fetches the XMR price from Kraken over Tor, for display and
amount-entry conversion only. The price is **non-authoritative**: no wallet data
is sent to the price endpoint, and a stale or missing price never blocks a send —
it degrades to native-XMR-only operation and can never alter or block the signed
transaction (the reviewed and relayed amounts are always the exact atomic-piconero
values, never derived from a live rate at send time). Amount arithmetic uses exact
decimal handling (atomic piconero via BigDecimal).

## Intentionally absent / deferred

- **Max / sweep.** A "send max" sweep is **removed**, not hidden: a guessed
  reserve on a money path was unacceptable, and a fee-exact sweep needs a native
  wallet2 sweep path that is future work.
- **Daemon-based key-image import.** The `export_key_images` /
  `import_key_images(check_spent)` round-trip against a trusted daemon is not
  used; external-spend reconciliation is done locally through the background
  cache (see *External-spend reconciliation* above), which needs no daemon. The
  daemon-based path remains available as future hardening for a wallet with no
  scanned cache to draw on, not a correctness dependency.
