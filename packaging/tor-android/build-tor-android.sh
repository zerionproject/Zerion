#!/usr/bin/env bash
# Builds the Tor executable the app runs (libtor.so, one per shipped ABI) from
# pinned official sources: the Tor release tag, libevent, OpenSSL and zlib at
# the commits below, with Android NDK r29 verified by hash, through the
# vendored Makefile next to this script. Every pin is asserted after checkout
# and the build aborts on any mismatch. The output is byte-reproducible
# against the values recorded in PROVENANCE.md: the Makefile pins the build
# clock (SOURCE_DATE_EPOCH), the debug prefix and the optimisation level, and
# strips the result. The same script runs in the Briar Project's reproducer
# image, in F-Droid's build server and on any Debian host with the packages
# listed in PROVENANCE.md.
#
# Usage:
#   ./build-tor-android.sh                 builds arm64-v8a and armeabi-v7a
#   ABIS="arm64-v8a" ./build-tor-android.sh
#   OUT=/some/dir ./build-tor-android.sh   writes <OUT>/<abi>/libtor.so
#
# Environment: ANDROID_NDK_R29 may point at an unpacked NDK r29; otherwise the
# NDK zip is downloaded to NDK_CACHE (default /opt) and verified by SHA-256.
# WORK (default /build/tor) is the build directory; it must be an absolute
# path because the Makefile records it as the install prefix.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

TOR_TAG="${TOR_TAG:-tor-0.4.9.13}"
TOR_COMMIT="${TOR_COMMIT:-3c575400909efe6599d88e61e7daf0012655ca44}"
TOR_URL=https://gitlab.torproject.org/tpo/core/tor.git

LIBEVENT_TAG=release-2.1.12-stable
LIBEVENT_COMMIT=5df3037d10556bfcb675bc73e516978b75fc7bc7
LIBEVENT_URL=https://github.com/libevent/libevent.git

OPENSSL_TAG=openssl-3.5.7
OPENSSL_COMMIT=8cf17aaeb4599f8af87fefd810b5b5fee90fe69e
OPENSSL_URL=https://github.com/openssl/openssl.git

ZLIB_TAG=v1.3.2
ZLIB_COMMIT=da607da739fa6047df13e66a2af6b8bec7c2a498
ZLIB_URL=https://github.com/madler/zlib.git

NDK_URL=https://dl.google.com/android/repository/android-ndk-r29-linux.zip
NDK_SHA256=4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf
NDK_REVISION=29.0.14206865

ABIS="${ABIS:-arm64-v8a armeabi-v7a}"
WORK="${WORK:-/build/tor}"
OUT="${OUT:-${HERE}/out}"
NDK_CACHE="${NDK_CACHE:-/opt}"

say() { echo "=== tor-android $(date -u +%H:%M:%S) $*"; }

download() {
	local url="$1" dest="$2"
	if command -v curl > /dev/null 2>&1; then
		curl -sS -L --retry 3 -o "${dest}" "${url}"
	else
		wget -q -O "${dest}" "${url}"
	fi
}

prepare_ndk() {
	if [ -n "${ANDROID_NDK_R29:-}" ]; then
		ANDROID_NDK_HOME="${ANDROID_NDK_R29}"
		return
	fi
	ANDROID_NDK_HOME="${NDK_CACHE}/android-ndk-r29"
	if [ -d "${ANDROID_NDK_HOME}" ]; then
		return
	fi
	say "downloading NDK r29"
	mkdir -p "${NDK_CACHE}"
	download "${NDK_URL}" "${NDK_CACHE}/android-ndk-r29.zip"
	echo "${NDK_SHA256}  ${NDK_CACHE}/android-ndk-r29.zip" | sha256sum -c - > /dev/null
	unzip -q "${NDK_CACHE}/android-ndk-r29.zip" -d "${NDK_CACHE}"
	rm -f "${NDK_CACHE}/android-ndk-r29.zip"
}

fetch() {
	local dir="$1" url="$2" ref="$3" want="$4"
	rm -rf "${dir}"
	git -c advice.detachedHead=false clone -q --branch "${ref}" --depth 1 "${url}" "${dir}"
	local got
	got="$(git -C "${dir}" rev-parse HEAD)"
	if [ "${got}" != "${want}" ]; then
		echo "pin mismatch for ${ref}: expected ${want}, got ${got}" >&2
		exit 1
	fi
	echo "$(basename "${dir}") ${ref} ${got}" >> "${OUT}/SOURCES.txt"
	say "source $(basename "${dir}") ${ref} ${got}"
}

prepare_ndk
export ANDROID_NDK_HOME
grep -q "Pkg.Revision = ${NDK_REVISION}" "${ANDROID_NDK_HOME}/source.properties"
say "NDK ${NDK_REVISION} at ${ANDROID_NDK_HOME}"

mkdir -p "${WORK}" "${OUT}"
: > "${OUT}/SOURCES.txt"
install -m644 "${HERE}/Makefile" "${WORK}/Makefile"
fetch "${WORK}/tor" "${TOR_URL}" "${TOR_TAG}" "${TOR_COMMIT}"
fetch "${WORK}/libevent" "${LIBEVENT_URL}" "${LIBEVENT_TAG}" "${LIBEVENT_COMMIT}"
fetch "${WORK}/openssl" "${OPENSSL_URL}" "${OPENSSL_TAG}" "${OPENSSL_COMMIT}"
fetch "${WORK}/zlib" "${ZLIB_URL}" "${ZLIB_TAG}" "${ZLIB_COMMIT}"

for ABI in ${ABIS}; do
	say "building ${ABI}"
	rm -f "${WORK}"/*-build-stamp
	make -C "${WORK}" clean APP_ABI="${ABI}" > "${OUT}/clean-${ABI}.log" 2>&1
	test ! -e "${WORK}/tor/src/app/tor"
	make -C "${WORK}" tor APP_ABI="${ABI}" V=0 > "${OUT}/make-${ABI}.log" 2>&1
	file "${WORK}/tor/src/app/tor" | grep -q "${ABI/arm64-v8a/aarch64}" || file "${WORK}/tor/src/app/tor" | grep -q "${ABI/armeabi-v7a/ARM, EABI5}"
	mkdir -p "${OUT}/${ABI}"
	install -m644 "${WORK}/tor/src/app/tor" "${OUT}/${ABI}/libtor.so"
	touch --no-dereference -t 201001010000.00 "${OUT}/${ABI}/libtor.so"
	sha256sum "${OUT}/${ABI}/libtor.so" | cut -d' ' -f1 > "${OUT}/${ABI}/libtor.so.sha256"
	say "RESULT ${ABI} $(cat "${OUT}/${ABI}/libtor.so.sha256") $(stat -c %s "${OUT}/${ABI}/libtor.so")"
done
say "done"
