#!/usr/bin/env bash
# Builds the pinned Payjoin source from two different directories under this
# one and compares the SHA-256 of every produced library with each other and
# with MANIFEST.txt. Matching hashes are the acceptance criterion; a
# difference is investigated, never accepted by rewriting the manifest.
#
#   ANDROID_NDK_HOME=<pinned NDK> bash verify-reproducible.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE="$HERE/repro"
MANIFEST="$HERE/MANIFEST.txt"

rm -rf "$BASE"
for tag in A B; do
  DIR="$BASE/$tag"
  echo "### BUILD $tag at $DIR"
  bash "$HERE/build-android.sh" "$DIR/jniLibs" "$DIR/payjoin-ffi" \
    2>&1 | grep -iE "error\[|error:|Finished|libpayjoin_ffi.so$|[0-9a-f]{64}" | tail -8
done

status=0
echo "### SHA-256 COMPARISON"
for abi in arm64-v8a armeabi-v7a; do
  HA=$(sha256sum "$BASE/A/jniLibs/$abi/libpayjoin_ffi.so" | awk '{print $1}')
  HB=$(sha256sum "$BASE/B/jniLibs/$abi/libpayjoin_ffi.so" | awk '{print $1}')
  WANT=$(grep -E "^sha256 $abi/libpayjoin_ffi.so " "$MANIFEST" | awk '{print $3}')
  if [ "$HA" = "$HB" ] && [ "$HA" = "$WANT" ]; then
    echo "MATCH  $abi  $HA"
  else
    echo "DIFFER $abi  A=$HA  B=$HB  manifest=$WANT"
    status=1
  fi
done
exit $status
