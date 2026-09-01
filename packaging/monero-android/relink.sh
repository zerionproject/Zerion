#!/usr/bin/env bash
# Link-only rebuild of libzmonero.so from the audited JNI wrapper against the
# already-built, cached Monero + dependency static libraries. This performs ONLY
# step [5/5] of build-monero-android.sh (the final link+strip); it does not touch
# git, submodules, or recompile Monero/deps. The cached .a inputs are mounted
# read-only, so the audit tree cannot be mutated. Same compiler, flags and inputs
# as the reproducible build's link step.
set -euo pipefail

ABI="${ABI:-arm64-v8a}"
API=24
NDK="${ANDROID_NDK_HOME}"
TC="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
OUT=/build/out/${ABI};   mkdir -p "${OUT}"
DEPS=/build/deps/${ABI}
MONERO=/build/monero
MB=/build/monero/build/${ABI}

case "${ABI}" in
  arm64-v8a)   TRIPLE=aarch64-linux-android;    PAGE=16384 ;;
  armeabi-v7a) TRIPLE=armv7a-linux-androideabi; PAGE=4096  ;;
  *) echo "unsupported ABI ${ABI}"; exit 2 ;;
esac

export CXX=${TC}/bin/${TRIPLE}${API}-clang++
STRIP=${TC}/bin/llvm-strip

LIBS=$(find ${MB} -name '*.a' | tr '\n' ' ')
echo "=== relink ${ABI} (PAGE=${PAGE}) ==="
${CXX} -shared -fPIC -O2 -fvisibility=hidden -std=c++17 \
  -Wl,-z,max-page-size=${PAGE} -Wl,-z,common-page-size=${PAGE} \
  -I${MONERO}/src -I${MONERO}/src/wallet/api \
  -I${MONERO}/external -I${MONERO}/external/easylogging++ \
  -I${MONERO}/external/rapidjson/include -I${MONERO}/external/supercop/include \
  -I${MONERO}/contrib/epee/include \
  -I${MB}/generated_include -I${MB} \
  -I${DEPS}/include \
  /build/jni/zmonero.cpp \
  -Wl,--start-group ${LIBS} -Wl,--end-group \
  ${DEPS}/lib/libboost_*.a ${DEPS}/lib/libsodium.a \
  ${DEPS}/lib/libunbound.a ${DEPS}/lib/libexpat.a ${DEPS}/lib/libiconv.a \
  ${DEPS}/lib/libssl.a ${DEPS}/lib/libcrypto.a \
  -llog -latomic -static-libstdc++ \
  -o ${OUT}/libzmonero.so
${STRIP} --strip-unneeded ${OUT}/libzmonero.so
echo "=== RESULT ${ABI} ==="
ls -la ${OUT}/libzmonero.so
sha256sum ${OUT}/libzmonero.so
${TC}/bin/llvm-readelf -l ${OUT}/libzmonero.so | grep -m1 LOAD || true
echo "RELINK OK ${ABI}"
