#!/usr/bin/env bash
# Deterministic Android build for the Payjoin native library from the pinned
# source materialised by fetch-source.sh. Produces a single stable
# libpayjoin_ffi.so per ABI (bitcoin-ffi statically linked). All inputs are
# pinned; see MANIFEST.txt for the authoritative values.
#
#   bash build-android.sh [output-dir] [source-dir]
#   default output: ./jniLibs, default source: ./upstream (fetched if absent)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${1:-$HERE/jniLibs}"
CRATE_DIR="${2:-$HERE/upstream}"

# Pinned build inputs (must match MANIFEST).
PLATFORM="29"
ABIS=("arm64-v8a" "armeabi-v7a")
EXPECTED_NDK="27.1.12297006"
EXPECTED_CARGO_NDK="4.1.2"

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the pinned NDK path}"
NDK_REVISION="$(grep -E '^Pkg.Revision' "$ANDROID_NDK_HOME/source.properties" | sed -E 's/.*= *//' | tr -d '\r')"
if [ "$NDK_REVISION" != "$EXPECTED_NDK" ]; then
  echo "ANDROID_NDK_HOME is NDK $NDK_REVISION, MANIFEST pins $EXPECTED_NDK" >&2
  exit 1
fi
CARGO_NDK_VERSION="$(cargo ndk --version | sed -E 's/^cargo-ndk //' | tr -d '\r')"
if [ "$CARGO_NDK_VERSION" != "$EXPECTED_CARGO_NDK" ]; then
  echo "cargo-ndk is $CARGO_NDK_VERSION, MANIFEST pins $EXPECTED_CARGO_NDK" >&2
  exit 1
fi

bash "$HERE/fetch-source.sh" "$CRATE_DIR"

# Windows-form prefixes so the remap matches the paths rustc actually embeds.
towin() { command -v cygpath >/dev/null 2>&1 && cygpath -w "$1" || echo "$1"; }
CRATE_W="$(towin "$CRATE_DIR")"
CARGO_W="$(towin "${CARGO_HOME:-$HOME/.cargo}")"
RUSTUP_W="$(towin "${RUSTUP_HOME:-$HOME/.rustup}")"

# Determinism: single codegen unit, strip local symbols (dynamic exports kept),
# remap every absolute path to a fixed token so the build directory and the host
# home paths cannot leak into the binary. The flags travel in the encoded form
# so a build directory containing a space is handled like any other.
FLAGS=(
  -C codegen-units=1
  -C strip=symbols
  -C debuginfo=0
  "--remap-path-prefix=${CRATE_W}=/payjoin-ffi"
  "--remap-path-prefix=${CARGO_W}=/cargo"
  "--remap-path-prefix=${RUSTUP_W}=/rustup"
)
SEP=$'\x1f'
ENCODED=""
for f in "${FLAGS[@]}"; do
  ENCODED="${ENCODED}${ENCODED:+$SEP}${f}"
done
export CARGO_ENCODED_RUSTFLAGS="$ENCODED"
unset RUSTFLAGS

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

cd "$CRATE_DIR"
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
