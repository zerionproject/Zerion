#!/usr/bin/env bash
# Self-contained, Docker-free build of libzmonero.so for the two shipped ABIs
# (arm64-v8a, armeabi-v7a), for F-Droid or any Debian/Ubuntu host. It fetches
# and SHA-256-verifies every pinned dependency source, clones official Monero at
# the pinned commit, builds them from source, links the auditable JNI wrapper,
# and installs the result into the app's jniLibs so the normal Gradle build can
# package it. This wraps build-monero-android.sh (unchanged) so the output stays
# byte-reproducible with packaging/monero-android/PROVENANCE.md.
#
# Prerequisites (install these first; the F-Droid recipe does so via its own
# sudo/ndk steps):
#   - Android NDK r27b, with ANDROID_NDK_HOME pointing at it
#     (android-ndk-r27b, sha256
#      33e16af1a6bbabe12cad54b2117085c07eab7e4fa67cdd831805f0e94fd826c1)
#   - Debian/Ubuntu packages: ca-certificates curl unzip git build-essential
#     cmake pkg-config libtool automake autoconf ccache gperf python3 file
#     xz-utils
#
# Usage:  ANDROID_NDK_HOME=/opt/android-ndk-r27b ./fdroid-build.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "${HERE}/../.." && pwd)"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the android-ndk-r27b directory}"

# Pinned versions, identical to packaging/monero-android/Dockerfile.
export OPENSSL_VERSION=1.1.1w
export SODIUM_VERSION=1.0.19
export BOOST_VERSION=1.84.0
export BOOST_UNDERSCORE=1_84_0
export MONERO_TAG=v0.18.5.1
export MONERO_COMMIT=4f92268d7c16741cfb41e5bbe2aa46cc260a9ea5

# build-monero-android.sh works under /build (matching the container, which
# keeps the linked .so byte-reproducible with the published PROVENANCE hashes).
WORK=/build
mkdir -p "${WORK}/jni"
install -m644 "${HERE}/jni/zmonero.cpp" "${WORK}/jni/zmonero.cpp"
install -m755 "${HERE}/build-monero-android.sh" "${WORK}/build-monero-android.sh"

cd "${WORK}"
for ABI in arm64-v8a armeabi-v7a; do
  echo "=== building libzmonero.so for ${ABI} ==="
  ABI="${ABI}" "${WORK}/build-monero-android.sh"
  install -Dm644 "${WORK}/out/${ABI}/libzmonero.so" \
    "${REPO}/zerion-android/src/main/jniLibs/${ABI}/libzmonero.so"
  echo "installed -> zerion-android/src/main/jniLibs/${ABI}/libzmonero.so"
done
echo "=== done: libzmonero.so built from source for arm64-v8a + armeabi-v7a ==="
