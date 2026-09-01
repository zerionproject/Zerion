# Monero native (wallet2_api) Android build - provenance & reproducibility

This is the supply-chain gate for the XMR native library `libzmonero.so`, the
Monero counterpart to `src/main/cpp/PROVENANCE.md` (native Argon2). The library
moves funds; it must be built from pinned official Monero source, with recorded
hashes, and never replaced by an opaque prebuilt download.

## What is built

- `libzmonero.so` for `arm64-v8a` and `armeabi-v7a`: the minimal JNI wrapper
  (`jni/zmonero.cpp`) statically linked against **official Monero's**
  `wallet_api` and its dependencies. No unofficial fork and no prebuilt Monero
  binary is used; everything is compiled from source inside the pinned
  container.
- The JNI surface is the small, auditable set in `jni/zmonero.cpp`
  (create/restore/open/close, seed, address/subaddress, init-with-Tor-proxy,
  refresh/heights/synchronized, balance, prepare/commit, validate). Monero's own
  audited code performs all key handling, scanning, ring signing and transaction
  construction.

## Pins

| Component | Pin |
|---|---|
| Monero | tag `v0.18.5.1`, commit `4f92268d7c16741cfb41e5bbe2aa46cc260a9ea5` |
| OpenSSL | `1.1.1w` (openssl.org source tarball) |
| Boost | `1.84.0` (archives.boost.io) |
| libsodium | `1.0.19` |
| Android NDK | r27b (== Android Studio `ndkVersion 27.1.12297006`) |
| Base image | `debian:bookworm-20250630-slim` |
| Android API | 24 (minSdk of the app) |
| ABIs | arm64-v8a, armeabi-v7a |

The build records the SHA-256 of every downloaded source tarball to
`out/<abi>/SOURCES.sha256`, the NDK zip hash to `/opt/ndk.sha256`, the Monero
`git rev-parse HEAD` (asserted equal to the pinned commit, build aborts on
mismatch), and the SHA-256 of the produced `libzmonero.so` to
`out/<abi>/libzmonero.so.sha256`. First-observed values are recorded below and
become the expected gate on subsequent builds.

## Build flags relevant to security / compatibility

- `-DUNBOUND_ENABLED=OFF` and `-DUSE_DEVICE_TREZOR=OFF`: no bundled DNS resolver
  and no hardware-wallet transport. Daemon addresses passed from Java are onion
  or IP only (never a hostname needing DNS), so there is no clearnet DNS path;
  this is verified again in Java before `init` is called.
- `-DSTATIC=ON -DBUILD_TESTS=OFF`: fully static link into a single `.so`.
- `-Wl,-z,max-page-size=16384` (arm64): 16 KB-aligned LOAD segments for Android
  15+ 16 KB page-size devices. armeabi-v7a is 32-bit (4 KB pages, not subject to
  the rule). The alignment is verified with `llvm-readelf -l` on the packaged
  release APK, the same gate BTC's native libs pass.

## Reproduce

```
cd packaging/monero-android
docker build -t zerion-monero-build:v0.18.5.1 .
docker run --rm -e ABI=arm64-v8a   -v "$PWD/out:/build/out" zerion-monero-build:v0.18.5.1
docker run --rm -e ABI=armeabi-v7a -v "$PWD/out:/build/out" zerion-monero-build:v0.18.5.1
# artifacts: out/<abi>/libzmonero.so (+ .sha256, SOURCES.sha256)
```

Then copy each `libzmonero.so` into
`zerion-android/src/main/jniLibs/<abi>/` and run the 16 KB verification
(`packaging/monero-android/verify-16k.sh`) plus the equivalence/lifecycle tests.

## Integrity model

`libzmonero.so` ships inside the APK's `jniLibs` and is loaded with
`System.loadLibrary("zmonero")` from the app's own extracted native-library
directory. Its integrity therefore rests on the **APK signature** (the whole
package, this library included, is signed and cannot be modified without
re-signing) together with the reproducible build and the per-ABI SHA-256 hashes
published above, which anyone can recompute from the pinned source and image to
verify a shipped APK. It is **not** part of the `TorBinaryIntegrity` runtime
pin set: that mechanism exists for the Tor binaries (`libtor.so` /
`liblyrebird.so`), and a runtime self-hash of a library loaded from the same
signed APK would add nothing over the signature (an attacker able to replace the
in-APK library could equally patch the check). Adding the Monero library to that
runtime set remains available as optional defense-in-depth but is not what
protects it today; the build-time reproducible-hash gate is the meaningful
control and is the one to extend to the Monero library (see the release
checklist).

## Additional pinned dependencies (required by Monero)

Monero v0.18.5.1 hard-requires these; all are built from source, none prebuilt:

| Component | Pin |
|---|---|
| libiconv | 1.17 (Boost.Locale backend on Android) |
| expat | 2.6.4 (unbound dependency) |
| unbound | 1.22.0 (built, DNS never invoked - onion/IP daemons only) |
| ZeroMQ | 4.3.5 |

Notes recorded during bring-up:
- Boost.Locale is built with the iconv backend (`boost.locale.icu=off`,
  `iconv=on`, posix/std/winapi backends off) because Android bionic lacks
  `monetary.h`/`nl_langinfo_l` at API 24.
- expat, unbound, libiconv and ZeroMQ are compiled with `-fPIC` so they link
  into the shared `libzmonero.so`.
- RandomX is built in interpreter mode (no aarch64 JIT) via two minimal patches
  to the RandomX submodule: `external/randomx/src/common.hpp` selects the
  fallback (interpreter) compiler on aarch64 (`using JitCompiler =
  JitCompilerFallback`), and `external/randomx/CMakeLists.txt` drops the aarch64
  JIT sources. A daemon-trusting light wallet never executes RandomX
  proof-of-work; the Monero `wallet_api` links RandomX only for the
  `randomx_get_flags` CPU-feature query symbol, which must resolve at `dlopen`
  and which the interpreter build still provides (no `get_block_longhash` /
  RandomX hashing is ever called). This also drops the RandomX aarch64 JIT
  template, whose out-of-range conditional branch cannot be
  linked into a library this large. The build asserts both patches are present
  exactly once and fails otherwise, so the change cannot silently drift.
- The C++ runtime is linked statically (`-static-libstdc++`) so `libzmonero.so`
  is self-contained: its only dynamic dependencies are Android-system libraries
  (`liblog.so`, `libm.so`, `libdl.so`, `libc.so`) - no `libc++_shared.so`.
- The host `generate_translations_header` tool is built with the host gcc (the
  NDK cross compilers are unset before the Monero configure) so the cross build
  never runs a target binary.

## Recorded hashes (first observed, arm64-v8a build 2026-08-27)

- OpenSSL 1.1.1w tarball SHA-256: `cf3098950cb4d853ad95c0841f1f9c6d3dc102dccfcacd521d93925208b76ac8`
- libsodium 1.0.19 tarball SHA-256: `018d79fe0a045cca07331d37bd0cb57b2e838c51bc48fd837a1472e50068bbea`
- libiconv 1.17 tarball SHA-256: `8f74213b56238c85a50a5329f77e06198771e70dd9a739779f4c02f65d971313`
- Boost 1.84.0 tarball SHA-256: `cc4b893acf645c9d4b698e9a0f08ca8846aa5d6c68275c14c3e7949c24109454`
- expat 2.6.4 tarball SHA-256: `8dc480b796163d4436e6f1352e71800a774f73dbae213f1860b60607d2a83ada`
- unbound 1.22.0 tarball SHA-256: `c5dd1bdef5d5685b2cedb749158dd152c52d44f65529a34ac15cd88d4b1b3d43`
- ZeroMQ 4.3.5 tarball SHA-256: `6653ef5910f17954861fe72332e68b03ca6e4d9c7160eb3a8de5a5a913bfab43`
- **libzmonero.so arm64-v8a SHA-256:
  `6b5458bd33cc21b0ad7d2b329bc4b265b37b9c2852b90bfb1b2da0e290dea037`**
  (RandomX interpreter-only build; all LOAD segments 16 KB-aligned; NEEDED =
  liblog/libm/libdl/libc only). This is the accepted arm64 artifact. Supersedes
  `3943db3d5c5b7088091f24e34191e43844bda32d097af6e83b3e42855e1ed074` (the JNI
  sentinel normalization below; deps/Monero `.a` inputs unchanged).
- libzmonero.so armeabi-v7a SHA-256:
  `c45bf4c949ba43fa43272f3b202531b74d5af45ad86fd3568109e1226ef8f151`
  (ELF32/ARM; 4 KB pages as expected for 32-bit ARM). Supersedes
  `e63d48b87dd92deb4a922cbefac12014eac58b65434d515f20478e0d4e55ad83`.
  **Status: BUILT + STATICALLY VERIFIED /
  RUNTIME UNVERIFIED** (no compatible 32-bit ARM runtime target available;
  runtime not inferred from arm64). 32-bit ARM RandomX has no JIT, so the
  interpreter patch is a no-op there.

Wrapper history (pinned Monero and dependency versions unchanged throughout):
- XMR-P3.1 (2026-08-28) added three forwarders for background, non-blocking
  synchronization: `nStartRefresh`, `nPauseRefresh`, `nSetAutoRefreshInterval`
  (wallet2_api `startRefresh` / `pauseRefresh` / `setAutoRefreshInterval`).
- XMR-P3.3 (2026-08-28) added `nStopRefreshThread`, which stops and joins the
  wallet's refresh thread (`WalletImpl::stopRefresh`). It is required before a
  persisting close because wallet2_api `close(store=true)` writes the cache
  before it stops the thread; without the join a lock during catch-up could
  persist a cache the scan was still mutating. `stopRefresh` is private in the
  pinned wallet2_api (reachable upstream only from the destructor, which runs
  after the store), so the wrapper includes `wallet.h` with private access
  enabled for that one call. The link step gained the include directories
  needed for `wallet.h` (`external`, `external/rapidjson/include`,
  `external/supercop/include`, the per-ABI `generated_include`).
- XMR-P4-B commit 2 (2026-08-28) added six read-only / synchronization
  forwarders for the Send review and reconciliation primitives, with no new
  relay path (`nCommit` is byte-for-byte unchanged: `commit("", false)`, one
  attempt, no retry, no re-sign, no failover):
  - `nTxIds` (all txids of a PendingTransaction as `String[]`, wallet2 order),
    `nTxCount`, `nTxDust` (inspection of the existing object only);
  - `nAddressKind` (mainnet classification via `get_account_address_from_str`),
    `nIntegratedAddress` (via `WalletImpl::integratedAddress`);
  - `nWaitRefreshIdle` (bounded `try_lock` on `m_refreshMutex2`, released
    immediately; never held across a sleep; the mutex is a plain
    `boost::mutex` in the pinned source, so a bounded poll is used, not a timed
    lock);
  - `nLookupTxs` (per-txid `/get_transactions` over the wallet's existing
    `invoke_http_json` connection: same proxy/Tor path, no failover, no retry;
    returns factual per-index codes only — pool / mined-height / missed /
    error).
  These reach `WalletImpl`'s private `m_refreshMutex2` and `m_wallet` under the
  same `#define private public` around `wallet.h` already used for
  `stopRefresh`. Superseded SHAs: `66441ba1…` / `a4d3beb6…`.
- JNI sentinel normalization (2026-09-01) rebuilt both ABIs (arm64
  `6b5458bd33cc21b0ad7d2b329bc4b265b37b9c2852b90bfb1b2da0e290dea037`, armv7
  `c45bf4c949ba43fa43272f3b202531b74d5af45ad86fd3568109e1226ef8f151`). The sole
  source change is that `nTxCount` and `nTxDust` now return the canonical
  `NLONG_ERR` (`INT64_MIN`) on an invalid/wrong-kind/exception path, matching
  every other `jlong` accessor (they previously returned `-1`). No Monero/deps
  `.a` input changed; only the link-step `zmonero.cpp` differs, verified by
  relinking the prior source and reproducing the superseded hashes exactly
  (`3943db3d…` / `e63d48b8…`) before relinking the new source. Behavior is
  unchanged: the send-snapshot validator already rejected any `< 1` count and
  `< 0` dust, covering both sentinels.
- XMR-P4-B commit 2A (2026-08-28), a review-hardening pass, made three changes
  and rebuilt both ABIs (arm64
  `3943db3d5c5b7088091f24e34191e43844bda32d097af6e83b3e42855e1ed074`, armv7
  `e63d48b87dd92deb4a922cbefac12014eac58b65434d515f20478e0d4e55ad83`):
  - **removed `nIntegratedAddress`** from the production surface. P4 only needs
    to classify an existing integrated address (`nAddressKind`, which reads the
    embedded payment id through Monero's parser), never to generate one from an
    arbitrary payment id, so the generator is gone rather than shipped unused.
    Integrated-classification tests now use fixed mainnet vectors from Monero's
    own `validate_address.py` suite.
  - **hardened `nLookupTxs` against adversarial daemon responses**: the request
    timeout is clamped to `[1s, 30s]` (down from 60 s) so a silent peer cannot
    wedge the caller's executor for a minute; a structurally invalid response
    (an unexpected or non-hex txid) fails the whole batch to error; and a txid
    answered contradictorily (present and missed at once, answered twice, or an
    impossible height) fails that one index to error. `missed_tx` is still only
    ever set from a well-formed miss for a requested txid; a missing entry stays
    an error. The per-index result stays aligned with the requested order.
  - the `nWaitRefreshIdle` documentation was corrected to the real contract
    (a bounded `try_lock` poll on a plain `boost::mutex`, clamped to 5 s; true
    means idle only because refresh was paused and stopped first). No code
    change was needed there; the timeout was already forwarded to epee's http
    client, which applies it to connect, send and receive. Supersedes
    `37776e23…` / `af74623a…`.
Earlier SHAs (`f38ee41d…`/`1b39c004…`, then `d5b903e3…`/`4aeb8443…`) are
superseded.

The NDK r27b zip hash is captured to `/opt/ndk.sha256` inside the image at
build time; pin it here once the image is rebuilt with the value echoed.
