#!/usr/bin/env bash
# Verifies that the shipped Monero native library has 16 KB-aligned LOAD
# segments on arm64-v8a (Android 15+ 16 KB page-size gate). armeabi-v7a is
# 32-bit (4 KB pages) and is not subject to the rule. Mirrors the BTC gate.
set -euo pipefail

NDK="${ANDROID_NDK_HOME:-C:/Users/Iron/AppData/Local/Android/Sdk/ndk/27.1.12297006}"
READELF="$(ls "${NDK}"/toolchains/llvm/prebuilt/*/bin/llvm-readelf* 2>/dev/null | head -1)"
JNI="zerion-android/src/main/jniLibs"

fail=0
for so in "${JNI}/arm64-v8a/libzmonero.so"; do
  [ -f "${so}" ] || { echo "MISSING ${so}"; fail=1; continue; }
  al="$("${READELF}" -l "${so}" | grep -m1 LOAD | awk '{print $NF}')"
  case "${al}" in
    0x4000|0x10000) echo "OK   ${so} align=${al}" ;;
    *) echo "FAIL ${so} align=${al} (need >= 0x4000)"; fail=1 ;;
  esac
done
[ "${fail}" -eq 0 ] && echo "16KB gate: PASS" || { echo "16KB gate: FAIL"; exit 1; }
