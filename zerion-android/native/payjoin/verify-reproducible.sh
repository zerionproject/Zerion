#!/usr/bin/env bash
set -euo pipefail
SRC="${PAYJOIN_FFI_SRC:?set PAYJOIN_FFI_SRC to the payjoin-ffi source checkout}"
BASE="${PAYJOIN_REPRO_DIR:-$(mktemp -d)}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to NDK 27.1.12297006}"

seed_copy() {
  local dest="$1"
  rm -rf "$dest"; mkdir -p "$dest"
  cp -r "$SRC/src" "$dest/"
  cp "$SRC/Cargo.toml" "$SRC/Cargo.lock" "$SRC/rust-toolchain.toml" \
     "$SRC/build-android.sh" "$SRC/build.rs" "$SRC/uniffi-bindgen.rs" \
     "$SRC/uniffi.toml" "$dest/"
}

for tag in A B; do
  DIR="$BASE/repro_$tag/payjoin-ffi"
  echo "### BUILD $tag at $DIR"
  seed_copy "$DIR"
  ( cd "$DIR" && bash build-android.sh "$DIR/jniLibs" ) 2>&1 | grep -iE "error\[|error:|Finished|libpayjoin_ffi.so$|[0-9a-f]{64}" | tail -8
done

echo "### SHA-256 COMPARISON"
for abi in arm64-v8a armeabi-v7a; do
  HA=$(sha256sum "$BASE/repro_A/payjoin-ffi/jniLibs/$abi/libpayjoin_ffi.so" | awk '{print $1}')
  HB=$(sha256sum "$BASE/repro_B/payjoin-ffi/jniLibs/$abi/libpayjoin_ffi.so" | awk '{print $1}')
  if [ "$HA" = "$HB" ]; then echo "MATCH  $abi  $HA"; else echo "DIFFER $abi  A=$HA  B=$HB"; fi
done
