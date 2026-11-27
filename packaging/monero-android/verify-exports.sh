#!/usr/bin/env bash
# Checks the dynamic symbol table of libzmonero.so: every exported defined
# symbol must be a JNI entry point (JNI_OnLoad, JNI_OnUnload or
# Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_*). Any
# other export means the library was linked without jni/zmonero.map and
# -Wl,--exclude-libs,ALL. Run from the repository root with
# ANDROID_NDK_HOME set (NDK 27.1.12297006, the one the library is built with).
#
#   packaging/monero-android/verify-exports.sh            checks both ABIs
#   packaging/monero-android/verify-exports.sh path.so    checks one file
set -euo pipefail

NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to NDK 27.1.12297006}"
NM="$(ls "${NDK}"/toolchains/llvm/prebuilt/*/bin/llvm-nm* 2>/dev/null | head -1)"
[ -n "${NM}" ] || { echo "llvm-nm not found under ${NDK}"; exit 2; }

check() {
	local so="$1"
	[ -f "${so}" ] || { echo "MISSING ${so}"; return 1; }
	local total jni other
	total="$("${NM}" -D --defined-only "${so}" | wc -l | tr -d ' ')"
	jni="$("${NM}" -D --defined-only "${so}" | awk '{print $3}' \
		| grep -c -E '^(JNI_OnLoad|JNI_OnUnload|Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_)' || true)"
	other=$((total - jni))
	echo "${so}: ${total} exported, ${jni} JNI entry points, ${other} other"
	if [ "${other}" -ne 0 ]; then
		"${NM}" -D --defined-only "${so}" | awk '{print $3}' \
			| grep -v -E '^(JNI_OnLoad|JNI_OnUnload|Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_)' | head -10
		echo "FAIL: ${so} exports symbols outside the JNI surface"
		return 1
	fi
	[ "${jni}" -ge 1 ] || { echo "FAIL: ${so} exports no JNI entry point"; return 1; }
	echo "OK   ${so}"
}

fail=0
if [ $# -ge 1 ]; then
	check "$1" || fail=1
else
	for abi in arm64-v8a armeabi-v7a; do
		check "zerion-android/src/main/jniLibs/${abi}/libzmonero.so" || fail=1
	done
fi
[ "${fail}" -eq 0 ] && echo "export gate: PASS" || { echo "export gate: FAIL"; exit 1; }
