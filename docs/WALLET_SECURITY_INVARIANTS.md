# Wallet Security Invariants and Threat Model

The invariants future changes to the Bitcoin and Monero wallets must not break.
Each is enforced by current code; a change that would violate one is a
release blocker, not a refactor.

## Threat model (summary)

- **Device-local, self-custodial.** Keys and seeds live only on the device,
  sealed in the vault. There is no server that holds funds or can move them.
- **Adversaries considered:** a network observer / malicious node (mitigated by
  Tor-only transport and no clearnet fallback); an attacker with the vault master
  key but not the wallet password (mitigated by per-wallet password sealing and,
  for XMR, Store-1); a hostile daemon returning malformed data (mitigated by
  strict parsing that drops bad rows); UI compromise attempting to authorize a
  different transaction than reviewed (mitigated by the signed-review /
  fresh-authorization / fingerprint-bound relay chain); a crash or process death
  mid-operation (mitigated by durable journals and idempotent rebuild-from-seed).
- **Out of scope:** a fully compromised unlocked device with the wallet password
  in hand; supply-chain compromise of the pinned, hash-verified native library.

## Cross-cutting invariants

1. **The mutable display name is never cryptographic identity.** `walletId` (the
   vault item id) is the immutable identity; all fund/security state is keyed to
   it. The user-changeable display name must never participate in key derivation,
   KDF salt, KEK, wallet id, file/directory names, native filenames, background
   credentials, address fingerprints, journal or auth-token ownership. For both
   coins the display name is stored in settings and a rename changes only it — the
   id is immutable and the seed is never re-encrypted or migrated. (The vault
   item's content AEAD is bound to the item's fixed creation-time name, a per-item
   value set once and never changed by a rename, so a rename touches no
   cryptography.) Renaming must never change the password that opens a wallet or
   affect any other wallet. BTC and XMR share this rename invariant with no
   per-coin exception; existing BTC wallets keep their current id, which is the
   immutable identity from this version onward.

2. **Tor-only, no silent clearnet.** Wallet network I/O (sync, broadcast, price)
   goes over Tor by default; there is no automatic fallback to clearnet or
   plaintext. A non-Tor path, where it exists at all, is explicit and opt-in.

3. **No logging of wallet data.** No `Log`/`System.out`/`System.err`/
   `printStackTrace`/`println`/native debug traces anywhere in the wallet or
   native code. Secrets never appear in exception messages. The release build
   enforces this (`enforceNoLogs`).

4. **Hostile-daemon trust model.** A malicious daemon/server cannot forge
   ownership of outputs or fabricate valid transactions — cryptographic
   correctness does not depend on the daemon. It *can* censor, delay, withhold
   responses, and lie about availability/tip, producing stale or incomplete state.
   The wallet fails **closed for correctness** (malformed rows are dropped, never
   allowed to fabricate balance/history/a reservation; a send is built against the
   spend wallet's own verified state) and **preserves last-known-good for
   availability** (a transient/invalid/error update never replaces a wallet's
   previously verified balance or history with an empty or degraded one — see the
   last-known-good rule below).

5. **Last-known-good over empty.** A valid, complete update is published; an
   invalid, error, or transient update retains the last known good state plus a
   degraded status indication. A hostile or broken endpoint must never be able to
   turn a wallet with history into an empty wallet.

6. **Secret buffers have consistent, non-consuming ownership.** A `char[]`/
   `byte[]` secret is owned by the top-level caller, which wipes it in its own
   `finally`. A callee that needs the value derives from a private copy and wipes
   only that copy — it never mutates the caller's buffer. No shared vault/BTC/XMR
   API that does not explicitly advertise consuming semantics may zero a caller's
   input, so a secret can never be unexpectedly destroyed before a later use.
   (This closes the class that caused the rename-seals-under-zeros defect.)

7. **A crash never loses or substitutes a wallet.** Item creation writes content
   before the committing header; rename/delete remove the old item last; and a
   vault master-password change stages the new header to a marker and commits it
   with a single atomic rename, with a reconciliation before every unlock that
   rolls an interrupted change fully back or fully forward. No interrupted
   operation may leave a wallet listed-but-seedless, encrypted under a key the
   live header does not match, or unrecoverable.

## Bitcoin invariants

1. **reviewed == authenticated == signed == broadcast.** The transaction the user
   reviews is the exact transaction that is authorized, signed, and broadcast.
   There is no silent reconstruction of a different transaction, change of
   inputs/outputs, fee, or node between review and broadcast.

2. **Pending-input reservation.** Inputs (UTXOs) consumed by a broadcast-but-not-
   yet-confirmed transaction are reserved so a subsequent send cannot select them,
   preventing an accidental double-spend across pending sends. The reservation is
   reconciled deterministically against confirmed chain state.

(The BTC networking, receive, and privacy specifics are documented in
`BTC_ARCHITECTURE.md`; the invariants above are the ones that gate changes.)

## Monero invariants

1. **reviewed == post-review-authorized == exact relayed transaction.** The
   reviewed transaction, the fresh Stage-B authorization, and the relayed
   transaction are the same signed object, bound by a fingerprint compared on the
   single serialized relay operation. A journal write failure means no relay.

2. **ZVault compromise alone cannot obtain XMR spend authority (Store-1).**
   Decrypting the vault tier yields only the spend-keyless background wallet.
   Spending requires the wallet password to open `w.keys`.

3. **A Zerion-originated spend never displays its funds as safely spendable.**
   Because the background (view-only) wallet cannot compute key images, the inputs
   of a Zerion send are reserved from the displayed spendable balance until the
   spend has positively converged into the background cache. The reservation is
   the full consumed-input total, is released only on positive convergence (never
   on history observation, never on a timeout), is never double-subtracted, and is
   re-activated after a rescan. Over-reservation (showing less) is acceptable;
   under-reservation is not.

4. **A same-seed external spend is reconciled before its funds are shown as
   spendable.** A view-only wallet cannot compute key images, so an output that a
   different wallet holding the same seed has spent stays unspent in the view
   cache. Zerion reconciles this with the supported wallet2 mechanism: the
   background wallet records the spending transaction as a plausible spend in the
   background cache, and on every wallet entry — once the wallet password is
   supplied — the spend wallet is opened locally, which runs wallet2
   `process_background_cache_on_open` to replay that plausible spend with the
   spend key present, resolve its key image and mark the output spent; the store
   regenerates the background cache with the spent flag, so the reopened view
   excludes it. This is local (no daemon) and transient (the spend key is wiped
   immediately after), so Store-1 holds. It is best-effort and fail-safe: on any
   failure the displayed balance is never increased and the next entry retries.
   The reconciliation also converges any own send the spend wallet now reports as
   outgoing, so the same debit is never subtracted twice, and it re-runs after a
   rescan (the plausible spend is re-scanned and re-reconciled), so a spent
   output can never reappear as spendable. A double-spend was never possible in
   any case — an actual Zerion send opens the spend wallet, which holds the key
   images. The daemon-based `export_key_images` / `import_key_images` round-trip
   is an available future hardening, not a correctness dependency.

5. **Every Zerion-originated outgoing transaction is permanently discoverable —
   while the local metadata exists.** The exact txid/amount/fee known at signing
   are persisted durably and shown as an outgoing history row that survives
   restart, in-place rescan, recovery and rename, merged with and deduplicated
   against canonical history. This is *local-metadata* recovery: a clean install
   holding only the seed recovers funds and incoming history but cannot
   reconstruct historical outgoing destinations or exact local send metadata, as
   the chain does not encode them recoverably.

6. **Rescan/recovery cannot silently skip historical blocks.** A restored or
   rescanned wallet scans from its stored early height (from a versioned, always
   conservatively-early checkpoint table), not the daemon tip; the
   recovering-from-seed marker prevents wallet2's new-wallet fast-forward, and a
   daemon may refine progress but never move the scan start later than this bound.

7. **Native boundary is fail-closed and typed.** See `XMR_JNI_CONTRACT.md`:
   opaque handles, kind/parent checks, typed error sentinels (long accessors
   `NLONG_ERR`, history/txids null-on-error, booleans fail-closed), exception
   guards, secret wiping, single-executor ownership.
