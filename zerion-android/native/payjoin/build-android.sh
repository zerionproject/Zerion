#!/usr/bin/env bash
# Deterministic Android build for the Payjoin native library.
# Produces a single stable libpayjoin_ffi.so per ABI (bitcoin-ffi statically linked).
# All inputs are pinned; see MANIFEST for the authoritative values.
set -euo pipefail

CRATE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${1:-$CRATE_DIR/jniLibs}"

# Pinned build inputs (must match MANIFEST).
NDK_VERSION="27.1.12297006"
PLATFORM="29"
ABIS=("arm64-v8a" "armeabi-v7a")

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the pinned NDK path}"

# Windows-form prefixes so the remap matches the paths rustc actually embeds.
towin() { command -v cygpath >/dev/null 2>&1 && cygpath -w "$1" || echo "$1"; }
CRATE_W="$(towin "$CRATE_DIR")"
CARGO_W="$(towin "${CARGO_HOME:-$HOME/.cargo}")"
RUSTUP_W="$(towin "${RUSTUP_HOME:-$HOME/.rustup}")"

# Determinism: single codegen unit, strip local symbols (dynamic exports kept),
# remap every absolute path to a fixed token so the build directory and the host
# home paths cannot leak into the binary.
export RUSTFLAGS="-C codegen-units=1 -C strip=symbols -C debuginfo=0 \
--remap-path-prefix=${CRATE_W}=/payjoin-ffi \
--remap-path-prefix=${CARGO_W}=/cargo \
--remap-path-prefix=${RUSTUP_W}=/rustup"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

for abi in "${ABIS[@]}"; do
  cargo ndk --platform "$PLATFORM" -t "$abi" -o "$OUT_DIR" \
    build --release --locked --features uniffi
  # Single-library policy: keep only libpayjoin_ffi.so. bitcoin-ffi is statically
  # linked into it; cargo-ndk also emits a redundant hash-suffixed bitcoin cdylib
  # that Android never loads.
  find "$OUT_DIR/$abi" -name 'libbitcoin_ffi*.so' -delete
done

echo "== artifacts =="
find "$OUT_DIR" -name 'libpayjoin_ffi.so' | sort | while read -r f; do
  sha256sum "$f"
done
