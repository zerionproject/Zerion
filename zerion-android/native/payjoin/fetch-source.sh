#!/usr/bin/env bash
# Materialises the pinned Payjoin crate source next to this file, from the
# repository and revision recorded in MANIFEST.txt, and lays the committed
# inputs over it: the lockfile, the toolchain pin, the UniFFI config and the
# native-boundary hardening tests. Nothing here depends on the machine it
# runs on; the checkout is refused unless it is at exactly the pinned commit.
#
#   bash fetch-source.sh [target-dir]      # default: ./upstream
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${1:-$HERE/upstream}"
MANIFEST="$HERE/MANIFEST.txt"

UPSTREAM_REPO="https://github.com/payjoin/payjoin-ffi.git"
UPSTREAM_REV="$(grep -E '^# upstream_crate ' "$MANIFEST" | sed -E 's/.*@ ([0-9a-f]{40}).*/\1/')"
if [ -z "$UPSTREAM_REV" ]; then
  echo "MANIFEST.txt carries no upstream_crate revision" >&2
  exit 1
fi

if [ ! -d "$TARGET/.git" ]; then
  rm -rf "$TARGET"
  git init -q "$TARGET"
  git -C "$TARGET" remote add origin "$UPSTREAM_REPO"
  git -C "$TARGET" fetch -q --depth 1 origin "$UPSTREAM_REV"
  git -C "$TARGET" checkout -q --detach FETCH_HEAD
fi
HEAD="$(git -C "$TARGET" rev-parse HEAD)"
if [ "$HEAD" != "$UPSTREAM_REV" ]; then
  echo "upstream checkout is at $HEAD, expected $UPSTREAM_REV" >&2
  exit 1
fi

# The committed inputs win over whatever the upstream tree carries.
cp "$HERE/Cargo.lock" "$TARGET/Cargo.lock"
cp "$HERE/rust-toolchain.toml" "$TARGET/rust-toolchain.toml"
cp "$HERE/uniffi.toml" "$TARGET/uniffi.toml"
mkdir -p "$TARGET/tests"
cp "$HERE/hardening.rs" "$TARGET/tests/hardening.rs"

for f in Cargo.toml build.rs uniffi-bindgen.rs src; do
  [ -e "$TARGET/$f" ] || { echo "upstream tree lacks $f" >&2; exit 1; }
done

echo "source ready at $TARGET (payjoin-ffi @ $HEAD)"
