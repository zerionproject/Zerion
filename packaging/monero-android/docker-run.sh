#!/bin/sh
# Builds libzmonero.so for one ABI in the pinned image of this directory's
# Dockerfile, started the way PROVENANCE.md documents: one fresh container,
# every Linux capability dropped and no right to gain new privileges. The
# library lands in out/<abi>/ next to this script.
#
# Usage: docker-run.sh <arm64-v8a|armeabi-v7a> [image]
#        image defaults to zerion-monero-build:v0.18.5.4 (see PROVENANCE.md)
set -eu

usage() {
  echo "usage: $0 <arm64-v8a|armeabi-v7a> [image]" >&2
  exit 2
}

[ "$#" -ge 1 ] && [ "$#" -le 2 ] || usage
ABI="$1"
case "$ABI" in
  arm64-v8a|armeabi-v7a) ;;
  *) usage ;;
esac
IMAGE="${2:-zerion-monero-build:v0.18.5.4}"

cd "$(dirname "$0")"
mkdir -p out

exec docker run --rm --cap-drop=ALL --security-opt=no-new-privileges \
  -e "ABI=$ABI" -v "$PWD/out:/build/out" "$IMAGE"
