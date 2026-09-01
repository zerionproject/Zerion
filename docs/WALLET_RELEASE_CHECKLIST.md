# Wallet Release / Security Checklist

The gate every wallet (BTC/XMR) release passes before freeze. Freeze happens only
after the user's own funded acceptance on their device, never before.

## Pre-flight (repository state)

- [ ] Clean git working tree (`git status` empty); all changes committed in
      focused commits with no Claude attribution and no competitor references.
- [ ] Branch workflow honored (feature branch; `dev` -> `master` only after
      cross-device tests).

## Code quality gates

- [ ] Source comment scan across ALL Zerion-owned wallet production code — the
      shared vault (`vault/VaultManager`, `WalletStore`, `vault/crypto`,
      `vault/storage`), BTC (`vault/wallet/btc`), XMR (`vault/wallet/xmr`), the
      wallet UI (`vault/ui/*`), and the native wallet glue (`zmonero.cpp`): no
      `//`, `/* */`, `/** */` developer comments, no `TODO`/`FIXME`/`HACK`/`XXX`,
      no commented-out code. Preserve legally required copyright/SPDX/license and
      upstream attribution; never strip vendored upstream code.
- [ ] Logging/diagnostics scan: no `Log`/`println`/`printf`/`System.out|err`/
      `printStackTrace`/native debug traces in source; release `enforceNoLogs`
      passes; no secrets in exceptions. Any temporary debug instrumentation
      removed.
- [ ] Dead/commented-out code removed.

## Shared-vault security gates (same standard as the native layer)

- [ ] **Vault rekey crash recovery**: master-password change is marker-committed
      and `reconcileRekeyIfNeeded` rolls any interrupted change fully OLD or fully
      NEW; the item-set swap and header commit are each `fsyncVaultDir`-durable so
      the commit cannot persist ahead of the item swap on a reordering filesystem;
      device test green.
- [ ] **Unlock throttle live**: every failed unlock (wrong password included, not
      only a keystore/IO error) arms the exponential backoff; the counter is not
      dead.
- [ ] **Item create/rename/delete atomicity**: content-before-header on create;
      no orphan pre-commit artifacts can become active; rename/delete never remove
      the last recoverable seed before the replacement is committed.
- [ ] **Wallet identity**: `walletId` (vault item id) is immutable across rename
      for both coins (XMR and BTC verified; rename is a settings-only display-name
      change, no reseal, no state migration); the mutable display name enters no
      cryptography; a rename never changes the opening password or affects another
      wallet.
- [ ] **Secret-buffer ownership**: no shared API mutates a caller's password/seed
      buffer unless it advertises consuming semantics; regression test proves the
      vault decrypt leaves its input unchanged.
- [ ] **XMR JNI error distinction**: no fund/auth/session decision on an ambiguous
      JNI fallback (longs `NLONG_ERR`, history/txids null-on-error, booleans
      fail-closed).
- [ ] **XMR restore-height safety**: versioned checkpoint table, conservatively
      early; tests across dates far into the future and across checkpoint edges.
- [ ] **External-spend reconciliation**: a same-seed spend made in another wallet
      is reconciled into the view balance on entry via wallet2
      `process_background_cache_on_open` (local, no daemon, spend key transient so
      Store-1 holds); the reconciliation converges only own sends the spend wallet
      reports (no double-subtraction) and survives rescan; a Zerion send can never
      select an already-spent output.
- [ ] **BTC transaction fingerprint**: commits every consensus/fund-relevant field
      that could differ between review and broadcast, or the signer is proven
      deterministic-constant for the omitted fields.
- [ ] **Release-config review**: exported components / release manifest reviewed;
      release signing identity correct; `android:allowBackup=false`,
      `android:debuggable` absent, no cleartext traffic in the release build.

## Tests

- [ ] XMR JVM unit tests green (strict): `:zerion-android:testOfficialDebugUnitTest
      --tests "com.professor.zerion.android.vault.wallet.xmr.*"`.
- [ ] BTC fund-critical diff reviewed (no unintended change to stable BTC code).

## Native reproducibility and integrity

- [ ] `libzmonero.so` rebuilt reproducibly (full build or `relink.sh`); per-ABI
      SHA-256 matches `jniLibs` and is recorded in `PROVENANCE.md`. The library is
      loaded from the signed APK, so the APK signature plus these published,
      reproducible hashes are its integrity control — not a runtime self-hash (it
      is not in the `TorBinaryIntegrity` pin set, and `PROVENANCE.md` must not
      claim it is).
- [ ] **Build-time native integrity gates (enforced, not just recorded):**
      `verify-monero-native.gradle` runs on every assemble/bundle and fails the
      build if a packaged `libzmonero.so` does not match the accepted per-ABI
      SHA-256 (update jniLibs + `PROVENANCE.md` + the gate together on a deliberate
      rebuild); `build-monero-android.sh` `verify_sha256`-asserts every pinned
      dependency tarball (OpenSSL/Boost/libsodium/libiconv/expat/unbound/ZeroMQ)
      and aborts on mismatch; the `Dockerfile` `sha256sum -c`-asserts the pinned
      NDK r27b zip; the Monero `git rev-parse HEAD` assertion against the pinned
      commit remains.
- [ ] **Disabled native feature not packaged:** `libpayjoin_ffi.so` is excluded
      from the production APK while Payjoin is build-gated OFF (no production
      loader); re-include only in a variant that actually enables the feature.
- [ ] 16 KB LOAD alignment verified on arm64 (`llvm-readelf -l`, `0x4000`).
- [ ] New native symbols present (`llvm-nm -D`); removing comments must not change
      the hash (byte-identical rebuild).

## Device regression (Moto — the developer test device only)

Never `connectedAndroidTest` (it clean-installs and wipes data). Install with
`adb install -r` and run named safe classes via
`am instrument -e class <class> com.professor.zerion.debug.test/com.professor.zerion.android.BriarTestRunner`.

- [ ] `MoneroNativeHardeningTest` (handle registry / typed errors) green.
- [ ] `XmrBackgroundSyncDeviceTest` (Store-1 / convergence plumbing) green.
- [ ] `XmrRestoreHeightDeviceTest` (restore/rescan height, incl. live-daemon)
      green.
- [ ] `XmrRenameDeviceTest` (same password opens after rename, multiple renames)
      green.
- [ ] Destructive scenarios exercised on Moto only: rescan, cache deletion,
      restore/import, process death, migration, background rebuild. No real-value
      transactions; developers never broadcast a funded transaction.

## Pixel (user device — install only)

- [ ] `adb install -r <final.apk>` (data-preserving; `firstInstallTime`
      unchanged). Do NOT operate the Pixel wallet.
- [ ] Record the final APK SHA-256.

## User acceptance (the user, on their device)

- [ ] Rename -> reopen with same password.
- [ ] Outgoing history persists across restart/rescan.
- [ ] Rescan from an early date recovers incoming history; balance/reservation
      correct.
- [ ] Funded send acceptance if the change touches the send path.

## Freeze

- [ ] Freeze tag applied ONLY after the user reports PASS. Never freeze on
      developer verification alone.

## Verdict format

Each gate report ends with either `READY FOR USER PIXEL ...` (zero open
CRITICAL/HIGH, all automated gates green) or `BLOCK — FIX REQUIRED`.
