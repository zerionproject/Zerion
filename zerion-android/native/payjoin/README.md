# Payjoin native library build pipeline

This directory is the source of truth for the Payjoin v2 (BIP77) native library
`libpayjoin_ffi.so`. Prebuilt `.so` files are never authoritative. The library
is reproduced from pinned source, a pinned toolchain, and the committed
lockfile, following the process below, and every packaged artifact is verified
against `MANIFEST.txt`.

## What is pinned

All values are authoritative in `MANIFEST.txt`. Summary:

- Upstream crate: `payjoin/payjoin-ffi` at rev `c07a35f3d99cd5e02e27bfe7faf1b519828a8ee2` (`payjoin_ffi` 0.22.1).
- `payjoin` protocol crate: `payjoin/rust-payjoin` rev `53a3187887e82925fc3f999fb73ab08caf8d1832`, enforced by `Cargo.lock` with `--locked`.
- `bitcoin-ffi`: `bitcoindevkit/bitcoin-ffi` rev `6b1d131`.
- BIP77 HPKE: `bitcoin-hpke` 0.13.0 (DHKEM secp256k1 + HKDF-SHA256 + ChaCha20Poly1305). OHTTP: `bitcoin-ohttp` 0.6.0.
- Rust toolchain: `1.91.1` (pinned in `rust-toolchain.toml`, exact version, not the floating `stable` channel).
- UniFFI: 0.29.1. `cargo-ndk`: 4.1.2.
- Android NDK: 27.1.12297006. Platform/min API: 29.
- ABIs: `arm64-v8a`, `armeabi-v7a` (matches the app's release `abiFilters`; both are first-class, alongside Tor/SQLCipher/lyrebird).
- Build profile: `release`, built with `--locked`.
- Determinism flags: `-C codegen-units=1 -C strip=symbols -C debuginfo=0` plus `--remap-path-prefix` for the crate, `CARGO_HOME`, and `RUSTUP_HOME`.
- `Cargo.lock`: 416 crates, committed here.

## Single-library decision

Android loads exactly one native library, `libpayjoin_ffi.so`. `bitcoin-ffi` is
statically linked into it (verified: the artifact exports the callable
`ffi_bitcoin_ffi_*` functions and their metadata). Because the Kotlin bindings
are generated in library mode against the merged cdylib, `findLibraryName`
resolves both the `payjoin_ffi` and `bitcoin` UniFFI components to
`payjoin_ffi`, so no second library is loaded at runtime.

`cargo-ndk` additionally emits a redundant hash-suffixed `libbitcoin_ffi-*.so`
cdylib. It is never loaded and is deleted by the build script. Android runtime
loading does not depend on any Cargo hash-suffixed filename, and no binary is
renamed by hand.

## Build

```
export ANDROID_NDK_HOME='<pinned NDK path>'   # 27.1.12297006
bash build-android.sh <output-dir>            # default: ./jniLibs
```

Produces `<output-dir>/<abi>/libpayjoin_ffi.so` for both ABIs and prints each
SHA-256.

## Bindings

Generated in library mode against a host cdylib (UDL mode misses the 28
proc-macro exports):

```
cargo build --release --locked --features uniffi
cargo run --release --locked --features uniffi --bin uniffi-bindgen -- \
  generate --library target/release/<host cdylib> --language kotlin \
  --no-format --out-dir bindings
```

Bindings are committed alongside the pinned source; they are regenerated, not
edited.

## Reproducibility

`verify-reproducible.sh` builds the pinned source from two different absolute
paths and compares SHA-256. A matching hash across both build directories is the
acceptance criterion. If hashes differ, the remaining nondeterministic input is
investigated and documented; new hashes are not accepted without explanation.

## Verification at package time

`verify-payjoin-native.gradle` checks every packaged `libpayjoin_ffi.so` against
`MANIFEST.txt` and rejects any unexpected native artifact (including a stray
`libbitcoin_ffi*.so`). It is wired into release/bundle assembly. There is no
runtime downloading of native libraries.
