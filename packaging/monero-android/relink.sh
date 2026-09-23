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

# Refuse to relink against cached inputs that were not produced by the current
# build-monero-android.sh: a relink over a stale cache is how the 3.0.10
# library came to differ from what the committed recipe builds.
RECIPE_SHA="$(sha256sum /build/build-monero-android.sh | awk '{print $1}')"
for stamp in "${DEPS}/.recipe.sha256" "${MONERO}/.recipe.sha256"; do
  [ -f "${stamp}" ] && [ "$(cat "${stamp}")" = "${RECIPE_SHA}" ] \
    || { echo "relink refused: cached inputs in $(dirname "${stamp}") were not built by the current recipe" >&2; exit 4; }
done

# The same fixed archive order as the full recipe: an unsorted directory
# enumeration is not reproducible across hosts even with identical inputs.
LIBS=""
for lib in \
  lib/libwallet_api.a lib/libwallet.a \
  external/db_drivers/liblmdb/liblmdb.a external/easylogging++/libeasylogging.a \
  external/randomx/librandomx.a contrib/epee/src/libepee.a \
  src/multisig/libmultisig.a src/cryptonote_basic/libcryptonote_format_utils_basic.a \
  src/cryptonote_basic/libcryptonote_basic.a src/mnemonics/libmnemonics.a \
  src/libversion.a src/cryptonote_core/libcryptonote_core.a \
  src/blockchain_db/libblockchain_db.a src/ringct/libringct_basic.a \
  src/ringct/libringct.a src/blocks/libblocks.a src/checkpoints/libcheckpoints.a \
  src/crypto/libcncrypto.a src/hardforks/libhardforks.a \
  src/device_trezor/libdevice_trezor.a src/rpc/librpc_base.a \
  src/device/libdevice.a src/net/libnet.a src/common/libcommon.a; do
  [ -f "${MB}/${lib}" ] || { echo "pinned Monero archive missing: ${lib}" >&2; exit 3; }
  LIBS="${LIBS} ${MB}/${lib}"
done
FOUND=$(find ${MB} -name '*.a' | sort | tr '\n' ' ')
PINNED=$(echo ${LIBS} | tr ' ' '\n' | sort | tr '\n' ' ')
[ "${FOUND}" = "${PINNED}" ] || { echo "relink refused: archive set differs from the pinned list" >&2; exit 3; }
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
