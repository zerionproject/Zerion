#!/usr/bin/env bash
# Builds the Tor executable for the two shipped ABIs on F-Droid's build server
# or any Debian host, from the pinned sources in build-tor-android.sh, and
# installs the result where the Gradle build reads it
# (packaging/tor-android/out/<abi>/libtor.so). The Gradle pin gate then
# verifies the bytes against src/main/resources/org/zerionproject/tor/
# binaries.sha256 before they are packaged, so a build whose output differs
# from the recorded hashes fails instead of shipping.
#
# Prerequisites: Debian packages ca-certificates curl unzip git build-essential
# make patch pkg-config autoconf automake libtool perl python3 file xz-utils.
# The NDK r29 zip is downloaded and verified by hash unless ANDROID_NDK_R29
# points at an unpacked copy.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
export WORK="${WORK:-/build/tor}"
export OUT="${HERE}/out"
export ABIS="arm64-v8a armeabi-v7a"
"${HERE}/build-tor-android.sh"
for ABI in ${ABIS}; do
	echo "installed -> packaging/tor-android/out/${ABI}/libtor.so $(cat "${OUT}/${ABI}/libtor.so.sha256")"
done
