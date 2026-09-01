# XMR Native / JNI Boundary Contract

The hardened contract of the `libzmonero.so` JNI boundary
(`packaging/monero-android/jni/zmonero.cpp`) as currently shipped. This layer
forwards to Monero `wallet2_api` (pinned v0.18.5.1 / `4f92268d`) and makes no
policy decision: authentication, session lifetime, storage sealing and the
send-authorization gate are enforced in Java. It contains no key logic of its
own beyond wiping secrets it copies.

## Opaque handle registry

Native objects are never handed to Java as raw pointers. A process-wide registry
(`g_reg`, guarded by `g_regMu`) maps a generated opaque `jlong` id (a counter,
not an address) to a `HandleEntry{ptr, kind, parent}`.

- **Handle kinds.** `KIND_WALLET` and `KIND_TX`. Every accessor resolves an id
  through the registry and checks the kind: `asWallet` requires `KIND_WALLET`,
  `asTx` requires `KIND_TX`. A wallet id used where a tx is expected (or vice
  versa) resolves to null and returns the error sentinel.
- **Parent ownership.** A transaction handle records its parent wallet id.
  `asTx` resolves the tx only if its parent is still a live wallet, so a tx can
  never outlive its wallet.
- **Stale / disposed / forged rejection.** An unknown, already-removed, or
  wrong-kind id fails to resolve; the accessor returns the typed error value and
  never dereferences memory. Forged ids (0, negative, oversized, arbitrary) are
  rejected the same way.
- **Atomic disposal.** Closing a wallet (`nClose`) atomically removes the wallet
  id and all its child tx ids from the registry, disposes any still-live child
  transactions, then closes — so no tx handle is left pointing at a freed wallet,
  and a later stale or double call is a safe no-op rather than a use-after-free
  or double-free. Disposing a tx (`nDisposeTx`) atomically erases-then-disposes
  via the tx's own recorded parent.

## Error semantics (typed sentinels)

Every accessor is total (it never crashes; a C++ exception becomes the fallback
value), and no fund/auth/session decision is ever made from an error value that
could be mistaken for a legitimate result. The representation is typed by return
kind:

- **long accessors** (balance, unlocked balance, heights, subaddress count, tx
  count/dust/fee/amount/change, refresh-from height) **all** return `NLONG_ERR` =
  `INT64_MIN` on an invalid/wrong-kind handle or a caught native exception — not a
  reachable legitimate value (real wallet2 values are non-negative). Java mirrors
  this as `NativeMonero.LONG_ERR`, and the `Prepared` disposed-guards in
  `NativeMoneroEngine` return the same `LONG_ERR`. There is one error value for
  the whole `jlong` category — no per-method exception. (`nTxCount`/`nTxDust`
  previously returned `-1`; they were normalized to `NLONG_ERR` so the boundary
  has a single contract per return type. Downstream is unchanged: `XmrSendSnapshot`
  already rejects any `< 1` count and `< 0` dust, which catches the sentinel.)
- **int accessors** (status, connection, address-kind, background-sync type)
  return `-1` (or `0 = INVALID` for address-kind) on error, outside the valid
  enum range.
- **history (`nHistory`) and txid lists (`nTxIds`, `nLookupTxs`)** return `null`
  on a native error, distinct from a legitimately empty (`""` / empty array)
  result; Java throws/handles on `null` so a scan error never looks like an empty
  history and never overwrites the last good history. This is the reference
  pattern for a query whose empty result is legitimate.
- **string accessors** return `""` on error. `""` is not distinguishable from a
  legitimate empty string, so these are used only for diagnostics (`nErrorString`,
  `nTxError`) or for values that are never legitimately empty in context
  (`nAddress`, `nSeed` on a spend wallet), where the caller treats empty as an
  error/absent state; no control-flow or fund decision reads them.
- **boolean operations** return `false` on error, which is **not** distinguishable
  from a legitimate `false`. This is safe because every boolean is consumed
  **fail-closed**: a `false`-on-error causes the caller to reject, retry, or treat
  the wallet as not-ready/not-synced — never to approve or spend. The relay
  primitive `nCommit` returning `false` (relay failed *or* native error) is mapped
  to `RELAY_UNCERTAIN`, not FAILED — the safe "may have broadcast" interpretation,
  reconciled by the durable spend journal. The one place background-ness is read
  to gate spend-wallet capability opens the known spend-wallet file and is guarded
  by a preceding `status()` check, so a `false`-on-error there cannot cause a
  spend against a view-only wallet. Collapsing error into `false` is therefore a
  deliberate, safe design for these operations — not an ambiguity to fix: a
  `false` result can only ever *withhold* an action, never authorize one, so a
  typed tri-state would change no decision. This is the intended final contract,
  not a deferred item.

## Exception and memory hygiene

- Every JNI entry point is wrapped in `JNI_GUARD` / `JNI_GUARD_VOID`, so any C++
  exception from wallet2 becomes the typed fallback value instead of propagating
  as an uncontrolled native crash.
- Pending JNI exceptions are checked and cleared (`clearPending`) around JNI
  calls that can throw (string and byte-array construction, history and lookup
  encoding), so a pending exception cannot corrupt a later call.
- Secrets copied across the boundary (passwords, seeds) are wiped: incoming
  byte arrays are zeroed and released with `JNI_ABORT`, `std::string` copies are
  wiped (`wipe`) after use, and byte arrays returned to Java are wiped after the
  region copy. On the Java side, `NativeMoneroEngine` encodes `char[]` to a
  scratch `byte[]`, passes it, and zeroes it in a `finally`.

## Thread ownership and lifecycle

- All wallet operations for one wallet run on the single session executor shared
  with the open/close path; the sync loop owns that executor while it runs and
  yields it for queued session work. Native transaction disposal runs on that
  same executor, so it can never race a concurrent native read or relay.
- A vault lock stops the sync loop and interrupts any in-flight scan; the native
  close is queued on the session executor and runs with no scan in progress.

**Handle-lifetime safety (why a free can never race a dereference).** The
registry mutex guards the id→pointer lookup, not the wallet2 call that follows,
so lifetime safety rests on the free operations (`nClose`, `nDisposeTx`) never
running concurrently with an accessor on the same handle. That invariant is
*enforced by structure*, not developer discipline, and was verified caller-by-
caller: the raw `NativeMonero` native methods are package-private (no code
outside this package can invoke the JNI), the raw `jlong` handles live only
inside the private `NativeSession`/`NativePrepared` (never exposed, so no caller
can free or dereference one directly), and the only production code that touches
raw JNI is `NativeMoneroEngine` — every other component (`XmrSyncManager`,
`XmrWalletManager`) uses the `Session`/`Prepared` interface and references
`NativeMonero` only for the `LONG_ERR` constant. Every free is routed onto the
single session executor after the sync loop has yielded (`closeCurrentSession`
runs there once `stop()` drops the loop; tx disposal is deferred via
`disposeOnExecutor`), so no accessor is in flight when a free runs. The only
deliberately cross-thread native calls are `stop()`'s `pauseRefresh`/
`stopRefresh` refresh-signals, which free nothing and are covered by wallet2's
own refresh locking — which is also why a naive same-thread assertion is not
added (it would false-positive on those safe signals). A refcounted/`shared_ptr`
registry that held lifetime across the dereference is available as future
hardening; it is not required while no free can race an accessor.

## Refresh-height accessors

- `nSetRecoveringFromSeed(wallet, recovering)` marks the wallet
  recovering-from-seed (`WalletImpl::setRecoveringFromSeed`) so a connect does not
  fast-forward an unscanned background wallet's refresh height to the daemon tip.
- `nGetRefreshFromHeight(wallet)` reads the current refresh-from height
  (`get_refresh_from_block_height`); a log-free read used to verify restore/rescan
  behavior on device.
- `nSetRefreshFromHeight(wallet, height)` forwards to the public
  `setRefreshFromBlockHeight`, which wallet2 makes a **no-op on a background
  wallet**; the runtime therefore never relies on it to change a background
  wallet's height.

## Provenance and integrity

`libzmonero.so` is built reproducibly from the pinned Monero source in a pinned
Docker image; the shipped per-ABI SHA-256 hashes are recorded in
`packaging/monero-android/PROVENANCE.md`. The library links 16 KB-page-aligned on
arm64 (`-Wl,-z,max-page-size=16384`), verified with `llvm-readelf -l`. Removing
developer comments from `zmonero.cpp` produces a byte-identical library (the
compiler discards comments), so the recorded hashes are unaffected by
documentation changes. The link-only rebuild (`relink.sh`) reproduces the exact
bytes from the cached, read-only Monero/dependency objects.
