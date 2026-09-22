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
| OpenSSL | `3.5.8` (LTS; openssl.org source tarball, SHA-256 `a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2`, identical on openssl.org and the GitHub release asset with its published `.sha256`) |
| expat | `2.8.5` (SHA-256 `952c03c33a6b337f12dae7a9b0f9dee86f867550d35c994d6bdaaddd37dc8454`, identical from the GitHub release asset and the SourceForge mirror) |
| unbound | `1.22.0` (SHA-256 `c5dd1bdef5d5685b2cedb749158dd152c52d44f65529a34ac15cd88d4b1b3d43`) |
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

- `-DUSE_DEVICE_TREZOR=OFF`: no hardware-wallet transport. Monero's build has
  no switch to leave unbound out: `wallet2` links `libunbound` (and through it
  expat) for OpenAlias resolution, so both are compiled from the pinned
  tarballs above and linked statically; the app never calls the OpenAlias
  path, because every daemon address is validated as an onion or IP literal
  (or, in Direct mode, resolved by the device) before it reaches the wallet,
  and `use_ssl=false` keeps TLS out of reach, so the residual exposure of
  OpenSSL, unbound and expat is their internal use by the wallet library,
  which is why all three are kept at maintained versions. Every Tor-mode daemon address (the vetted
  set, custom nodes and the own node) is handed to the SOCKS proxy unresolved,
  so Tor resolves it remotely and no local DNS query is made. The one exception
  is the explicit Direct mode, which the user must acknowledge as
  reduced-privacy: a Direct node given as a hostname is resolved by the
  device's own resolver (`XmrNode.requiresLocalDns()`), and the
  acknowledgement text says so.
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

## Accepted hashes (clean rebuild on OpenSSL 3.5.8 and expat 2.8.5, 2026-09-22)

These are the values the Gradle gate enforces since the dependency pins moved
from OpenSSL 1.1.1w (end of life) to OpenSSL 3.5.8 LTS and from expat 2.6.4 to
expat 2.8.5 (SC-04). Nothing else in the recipe changed: the shim, the build
script's two documented patches, unbound 1.22.0, the Monero commit and the
NDK are the same as for the previous hashes. Produced by
`docker build -t zerion-monero-build:r4 .` from this directory and one fresh
container per ABI, each from an empty `/build`. The arm64 library embeds the
string `OpenSSL 3.5.8` and no `1.1.1w`.

- **libzmonero.so arm64-v8a SHA-256:
  `7358be68861ce806770422dcd2b3e68396e60a84e4461ee2b0eae1a64f2c7a5d`**
- **libzmonero.so armeabi-v7a SHA-256:
  `c4e19ca0cb36699ff9c0a0ec550119400488a9dd68eb7c08d6754d2212dc4a32`**

They supersede `62471e48…` / `07296eae…` (gated shim build), listed below
for the record.

## Previous hashes (clean rebuild with the gated shim, 2026-09-22)

These were the values the Gradle gate enforced after the JNI shim gained
wallet-level synchronisation and the remaining shim findings of the 3.0.11
assessment were fixed (JNI-02, JNI-03, JNI-04, JNI-06 and the asynchronous
rescan used by XMR-10). Only `jni/zmonero.cpp` changed; the build script,
its two documented patches, the dependency set and the Monero commit are
unchanged. Produced by `docker build -t zerion-monero-build:r3 .` from this
directory and one fresh container per ABI, each from an empty `/build`.

What the shim now does:

- Wallet-level synchronisation (JNI-04). Everything the Java side polls
  (balances, scanned height, subaddress count, transaction history) is
  mutated by the wallet API's refresh thread with no lock a caller could
  take. The API serialises its own refresh passes on
  `WalletImpl::m_refreshMutex2`; the shim uses that mutex as the wallet
  gate. A read that can take it reads live state with no refresh in flight
  and refreshes the shim's snapshot; a read that cannot is served from the
  snapshot the refresh thread itself keeps through a `WalletListener`
  installed at open, whose callbacks run on the refresh thread between
  blocks, where the state is consistent (figures every 100 blocks or once a
  second, plus on every money event, and figures and history at the end of
  each pass). `init`, `store`, subaddress creation, the refresh-from height
  and transaction creation take the gate or fail closed (the Java side
  quiesces the refresh thread before each of them, so in practice the gate
  is free); relay takes it unconditionally. The wallet's own
  `TransactionHistory` is therefore rebuilt only under the gate, on either
  thread, which also excludes the JNI-01 race structurally; the build
  script's history patch stays as a second line.
- Logging is silenced in `JNI_OnLoad`, before any address validator can run
  (JNI-06); the validators silence again explicitly. `wallet2::get_seed`
  prints to stdout only for a non-deterministic wallet, which Zerion never
  creates, and never prints the seed.
- A persisting close stores under the gate, always frees the wallet
  (closing without a store and deleting it directly if the API's close
  fails), and reports whether the cache was written (JNI-03).
- The trusted-daemon flag is applied after `init`, which otherwise
  overrides it from loopback detection, and can be read back (JNI-02).
- `nRescanBlockchain` hands the rescan to the refresh thread instead of
  running it on the caller.

- **libzmonero.so arm64-v8a SHA-256:
  `62471e48ce317f26f6acfc1e55cfcc764f7e121b0202dce7a6aaff1e87085b86`**
- **libzmonero.so armeabi-v7a SHA-256:
  `07296eaee22a6541ea2787c3f6adc01fd2ec0d4685971c92bea93f21752abe36`**

They supersede `d02388a8…` / `79e72e60…` (history patch build), listed
below for the record.

## Previous hashes (clean rebuild with the history patch, 2026-09-21)

These were the values the Gradle gate enforced after the transaction
history race fix (JNI-01). `build-monero-android.sh` gained a second documented
patch: the wallet API refresh thread no longer refreshes the transaction
history when it finds it empty (`WalletImpl::doRefresh` in
`src/wallet/api/wallet.cpp`), because the JNI shim refreshes and reads the
history itself and the two rebuilt the same objects concurrently, which let
the shim dereference objects the refresh thread had just deleted. The build
verifies that the patch is present exactly once. Produced by
`docker build -t zerion-monero-build:r2 .` from this directory and one fresh
container per ABI, each from an empty `/build`; a second fresh arm64 build
reproduced the same bytes.

- **libzmonero.so arm64-v8a SHA-256:
  `d02388a85912a57126af3ab6a76aa1698e55d4674d53dd678de34b55777e6c75`**
- **libzmonero.so armeabi-v7a SHA-256:
  `79e72e60551730c6a95757d9a81b51388b43a99efaff84624e3df7698f9dbc99`**

They supersede `6c59b64b…` / `4e6df174…` (3.0.11), listed below for the
record.

## Previous hashes (clean rebuild from the committed recipe, 2026-09-21, 3.0.11)

These are the values the Gradle gate enforces. They were produced by running
`build-monero-android.sh` exactly as committed, from an empty `/build`, in two
independent environments that agree byte for byte: the pinned
`debian:bookworm-20250630-slim` image of this directory's Dockerfile and
F-Droid's `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`. Each
was run twice in the trixie image with identical results.

- **libzmonero.so arm64-v8a SHA-256:
  `6c59b64b5ae06c1189fdf27c4e5551c6c3f8bad83016c9b6311d0fff5ed0f2a3`**
- **libzmonero.so armeabi-v7a SHA-256:
  `4e6df1748fbb1aeb4ac6baac7562218cec0d339ac6e9227229b27916d660920b`**

They supersede `6b5458bd…` / `c45bf4c9…` (the libraries shipped in 3.0.10).
Those were relinked with `relink.sh` against dependency archives cached from
a build of an earlier revision of this script; the committed recipe cannot
reproduce them from a clean tree, which is why F-Droid could not verify
3.0.10. Only the dependency inputs differ: a fresh build's `libboost_thread.a`
pulls one more `boost::exception` object into the link. The script now refuses
a cache that was not produced by the current revision (`.recipe.sha256`
stamps under `/build/deps/<abi>` and `/build/monero`), and `relink.sh` refuses
to relink over such a cache.

The link step consumes the Monero archives in a fixed, explicit order. Until
3.0.11 the script enumerated them with an unsorted `find`, whose order is a
property of the host filesystem, and lld lays the output out in input order:
F-Droid's runner and a local build produced byte-identical dependency and
Monero archives (every input hashed the same on both sides) yet different
`libzmonero.so` bytes, and relinking the same archives in sorted or reversed
order gave two further hashes. The pinned list is the order that produced the
accepted hashes above, so they remain valid; the script fails if the set of
archives ever differs from the list. For 3.0.11 itself the F-Droid recipe
applies the same order to the tagged script in a `prebuild` step.

## Recorded hashes (first observed, arm64-v8a build 2026-08-27; superseded)

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
