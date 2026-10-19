#!/usr/bin/env bash
# Builds the lyrebird executable for the two shipped ABIs on F-Droid's build
# server or any Debian host, from the pinned sources in
# build-lyrebird-android.sh, and installs the result where the Gradle build
# reads it (packaging/lyrebird-android/out/<abi>/liblyrebird.so). The Gradle
# pin gate then verifies the bytes against src/main/resources/org/
# zerionproject/tor/binaries.sha256 before they are packaged, so a build whose
# output differs from the recorded hashes fails instead of shipping.
#
# packaging/tor-android/fdroid-build.sh runs this script after the Tor build,
# with the same NDK_CACHE, so the recipe's Tor line builds both executables
# from the one NDK r29 it verified, and the recipe needs no line and no
# package of its own for lyrebird.
#
# Prerequisites: Debian packages ca-certificates curl git tar gzip unzip
# coreutils, all of which the Tor build needs as well, and gpgv or sqv for the
# tag signature, one of which Debian's apt depends on. The Go toolchain is
# downloaded and verified by hash; the NDK r29 zip is downloaded and verified
# by hash unless NDK_CACHE or ANDROID_NDK_R29 already holds a copy this
# repository's scripts verified.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
export WORK="${LYREBIRD_WORK:-/build/lyrebird}"
export OUT="${HERE}/out"
export ABIS="arm64-v8a armeabi-v7a"
"${HERE}/build-lyrebird-android.sh"
for ABI in ${ABIS}; do
	echo "installed -> packaging/lyrebird-android/out/${ABI}/liblyrebird.so $(cat "${OUT}/${ABI}/liblyrebird.so.sha256")"
done
