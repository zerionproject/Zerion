#!/usr/bin/env bash
# Reproducible Monero wallet2_api + minimal JNI build for one Android ABI.
# Builds official Monero (pinned tag) and its dependencies from source, then
# links the auditable JNI wrapper into libzmonero.so. No prebuilt Monero binary.
# /build/deps/<abi> and /build/monero are mountable caches so re-runs skip
# already-built dependencies. Fails hard on any error.
set -euo pipefail

ABI="${ABI:-arm64-v8a}"
API=24
NDK="${ANDROID_NDK_HOME}"
TC="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
JOBS="$(nproc)"
OUT=/build/out/${ABI};   mkdir -p "${OUT}"
DEPS=/build/deps/${ABI}; mkdir -p "${DEPS}/lib" "${DEPS}/include"
SRC=/build/src/${ABI};   mkdir -p "${SRC}"

case "${ABI}" in
  arm64-v8a)   TRIPLE=aarch64-linux-android;    OSSL_ARCH=android-arm64;
               SODIUM_SCRIPT=android-armv8-a.sh; PAGE=16384 ;;
  armeabi-v7a) TRIPLE=armv7a-linux-androideabi; OSSL_ARCH=android-arm;
               SODIUM_SCRIPT=android-armv7-a.sh; PAGE=4096  ;;
  x86_64)      TRIPLE=x86_64-linux-android;      OSSL_ARCH=android-x86_64;
               SODIUM_SCRIPT=android-x86_64.sh;  PAGE=4096  ;;
  *) echo "unsupported ABI ${ABI}"; exit 2 ;;
esac
BINTRIPLE=$([ "${ABI}" = "armeabi-v7a" ] && echo arm-linux-androideabi || echo ${TRIPLE})

export ANDROID_NDK_HOME=${NDK}
export AR=${TC}/bin/llvm-ar
export RANLIB=${TC}/bin/llvm-ranlib
export STRIP=${TC}/bin/llvm-strip
export CC=${TC}/bin/${TRIPLE}${API}-clang
export CXX=${TC}/bin/${TRIPLE}${API}-clang++
export PATH=${TC}/bin:${PATH}

verify_sha256() {
  # Assert a downloaded source archive matches its pinned SHA-256 before it is
  # ever unpacked or compiled, then record it. Recording alone (the previous
  # behaviour) let a compromised mirror ship a changed input whose new hash was
  # silently accepted; comparing first makes a mismatch abort the build.
  local file="$1" expected="$2" got
  got="$(sha256sum "${file}" | awk '{print $1}')"
  if [ "${got}" != "${expected}" ]; then
    echo "SOURCE HASH MISMATCH for ${file}: got ${got} expected ${expected}" >&2
    exit 4
  fi
  echo "${got}  ${file}" | tee -a "${OUT}/SOURCES.sha256"
}

echo "=== [1/5] OpenSSL ${OPENSSL_VERSION} (${ABI}) ==="
if [ -f "${DEPS}/lib/libcrypto.a" ] && [ -f "${DEPS}/lib/libssl.a" ]; then
  echo "cached, skipping"
else
  cd ${SRC}
  [ -f openssl.tar.gz ] || curl -fsSL -o openssl.tar.gz https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz
  verify_sha256 openssl.tar.gz cf3098950cb4d853ad95c0841f1f9c6d3dc102dccfcacd521d93925208b76ac8
  rm -rf openssl-${OPENSSL_VERSION}; tar xf openssl.tar.gz
  cd openssl-${OPENSSL_VERSION}
  ANDROID_NDK_ROOT=${NDK} ./Configure ${OSSL_ARCH} -D__ANDROID_API__=${API} \
    no-shared no-tests no-asm --prefix=${DEPS}
  make -j${JOBS} build_libs
  make install_dev
fi
[ -f "${DEPS}/lib/libcrypto.a" ] || { echo "OpenSSL install failed"; exit 3; }

echo "=== [2/5] libsodium ${SODIUM_VERSION} (${ABI}) ==="
if [ -f "${DEPS}/lib/libsodium.a" ]; then
  echo "cached, skipping"
else
  cd ${SRC}
  [ -f sodium.tar.gz ] || curl -fsSL -o sodium.tar.gz https://download.libsodium.org/libsodium/releases/libsodium-${SODIUM_VERSION}.tar.gz
  verify_sha256 sodium.tar.gz 018d79fe0a045cca07331d37bd0cb57b2e838c51bc48fd837a1472e50068bbea
  rm -rf libsodium-*/
  tar xf sodium.tar.gz
  cd libsodium-*/
  # libsodium's own NDK scripts set the correct arch/crypto flags.
  ./dist-build/${SODIUM_SCRIPT}
  cp -f libsodium-android-*/lib/libsodium.a ${DEPS}/lib/
  cp -rf libsodium-android-*/include/* ${DEPS}/include/
fi
[ -f "${DEPS}/lib/libsodium.a" ] || { echo "libsodium install failed"; exit 3; }

ICONV_VERSION=1.17
echo "=== [2b] libiconv ${ICONV_VERSION} (${ABI}) ==="
if [ -f "${DEPS}/lib/libiconv.a" ]; then
  echo "cached, skipping"
else
  cd ${SRC}
  [ -f iconv.tar.gz ] || curl -fsSL -o iconv.tar.gz \
    https://mirrors.kernel.org/gnu/libiconv/libiconv-${ICONV_VERSION}.tar.gz \
    || curl -fsSL -o iconv.tar.gz \
    https://ftp.gnu.org/pub/gnu/libiconv/libiconv-${ICONV_VERSION}.tar.gz
  verify_sha256 iconv.tar.gz 8f74213b56238c85a50a5329f77e06198771e70dd9a739779f4c02f65d971313
  rm -rf libiconv-${ICONV_VERSION}; tar xf iconv.tar.gz
  cd libiconv-${ICONV_VERSION}
  ./configure --host=${BINTRIPLE} --prefix=${DEPS} --disable-shared \
    --enable-static CC=${CC} AR=${AR} RANLIB=${RANLIB} CFLAGS="-fPIC"
  make -j${JOBS}
  make install
fi
[ -f "${DEPS}/lib/libiconv.a" ] || { echo "libiconv install failed"; exit 3; }

echo "=== [3/5] Boost ${BOOST_VERSION} (${ABI}) ==="
if ls ${DEPS}/lib/libboost_locale.a >/dev/null 2>&1; then
  echo "cached, skipping"
else
  cd ${SRC}
  [ -f boost.tar.bz2 ] || curl -fsSL -o boost.tar.bz2 https://archives.boost.io/release/${BOOST_VERSION}/source/boost_${BOOST_UNDERSCORE}.tar.bz2
  verify_sha256 boost.tar.bz2 cc4b893acf645c9d4b698e9a0f08ca8846aa5d6c68275c14c3e7949c24109454
  rm -rf boost_${BOOST_UNDERSCORE}; tar xf boost.tar.bz2
  cd boost_${BOOST_UNDERSCORE}
  ./bootstrap.sh --prefix=${DEPS}
  cat > user-config.jam <<EOF
using clang : android : ${CXX} : <archiver>${AR} <ranlib>${RANLIB} ;
EOF
  ./b2 -j${JOBS} --user-config=user-config.jam toolset=clang-android \
    --prefix=${DEPS} --build-dir=b2build \
    include=${DEPS}/include \
    --with-system --with-thread --with-filesystem --with-chrono \
    --with-date_time --with-regex --with-serialization --with-program_options \
    --with-locale boost.locale.icu=off boost.locale.iconv=on \
    boost.locale.posix=off boost.locale.std=off boost.locale.winapi=off \
    link=static runtime-link=static threading=multi \
    target-os=android cxxflags="-std=c++14 -fPIC -I${DEPS}/include" \
    linkflags="-L${DEPS}/lib -liconv" install
fi
[ -f "${DEPS}/lib/libboost_locale.a" ] || { echo "Boost locale build failed"; exit 3; }

EXPAT_VERSION=2.6.4
UNBOUND_VERSION=1.22.0
echo "=== [3a] expat ${EXPAT_VERSION} (${ABI}) ==="
if [ -f "${DEPS}/lib/libexpat.a" ]; then
  echo "cached, skipping"
else
  cd ${SRC}
  EXPAT_TAG=R_$(echo ${EXPAT_VERSION} | tr . _)
  [ -f expat.tar.bz2 ] || curl -fsSL -o expat.tar.bz2 \
    https://github.com/libexpat/libexpat/releases/download/${EXPAT_TAG}/expat-${EXPAT_VERSION}.tar.bz2
  verify_sha256 expat.tar.bz2 8dc480b796163d4436e6f1352e71800a774f73dbae213f1860b60607d2a83ada
  rm -rf expat-${EXPAT_VERSION}; tar xf expat.tar.bz2
  cd expat-${EXPAT_VERSION}
  ./configure --host=${BINTRIPLE} --prefix=${DEPS} --disable-shared \
    --enable-static --without-docbook --without-examples --without-tests \
    CC=${CC} AR=${AR} RANLIB=${RANLIB} CFLAGS="-fPIC"
  make -j${JOBS}
  make install
fi
[ -f "${DEPS}/lib/libexpat.a" ] || { echo "expat install failed"; exit 3; }

echo "=== [3b] unbound ${UNBOUND_VERSION} (${ABI}) ==="
if [ -f "${DEPS}/lib/libunbound.a" ]; then
  echo "cached, skipping"
else
  cd ${SRC}
  [ -f unbound.tar.gz ] || curl -fsSL -o unbound.tar.gz \
    https://nlnetlabs.nl/downloads/unbound/unbound-${UNBOUND_VERSION}.tar.gz
  verify_sha256 unbound.tar.gz c5dd1bdef5d5685b2cedb749158dd152c52d44f65529a34ac15cd88d4b1b3d43
  rm -rf unbound-${UNBOUND_VERSION}; tar xf unbound.tar.gz
  cd unbound-${UNBOUND_VERSION}
  ./configure --host=${BINTRIPLE} --prefix=${DEPS} --disable-shared \
    --enable-static --disable-flto --with-pthreads \
    --with-ssl=${DEPS} --with-libexpat=${DEPS} \
    --without-pythonmodule --without-pyunbound \
    CC=${CC} AR=${AR} RANLIB=${RANLIB} CFLAGS="-fPIC" \
    ac_cv_func_getentropy=no
  make -j${JOBS}
  make install
fi
[ -f "${DEPS}/lib/libunbound.a" ] || { echo "unbound install failed"; exit 3; }

ZMQ_VERSION=4.3.5
echo "=== [3c] libzmq ${ZMQ_VERSION} (${ABI}) ==="
if [ -f "${DEPS}/lib/libzmq.a" ]; then
  echo "cached, skipping"
else
  cd ${SRC}
  [ -f zmq.tar.gz ] || curl -fsSL -o zmq.tar.gz \
    https://github.com/zeromq/libzmq/releases/download/v${ZMQ_VERSION}/zeromq-${ZMQ_VERSION}.tar.gz
  verify_sha256 zmq.tar.gz 6653ef5910f17954861fe72332e68b03ca6e4d9c7160eb3a8de5a5a913bfab43
  rm -rf zeromq-${ZMQ_VERSION}; tar xf zmq.tar.gz
  cd zeromq-${ZMQ_VERSION}
  ./configure --host=${BINTRIPLE} --prefix=${DEPS} --disable-shared \
    --enable-static --without-docs --disable-perf --disable-curve-keygen \
    --disable-Werror --disable-libunwind \
    CC=${CC} CXX=${CXX} AR=${AR} RANLIB=${RANLIB} \
    CFLAGS="-fPIC" CXXFLAGS="-fPIC" LIBS="-latomic"
  make -j${JOBS}
  make install
fi
[ -f "${DEPS}/lib/libzmq.a" ] || { echo "libzmq install failed"; exit 3; }

echo "=== [4/5] Monero ${MONERO_TAG} (${MONERO_COMMIT}) (${ABI}) ==="
cd /build
mkdir -p monero
if [ ! -d monero/.git ]; then
  find monero -mindepth 1 -delete 2>/dev/null || true
  git clone --recursive --branch ${MONERO_TAG} --depth 1 \
    https://github.com/monero-project/monero.git monero
fi
cd monero
GOT_COMMIT="$(git rev-parse HEAD)"
echo "monero HEAD=${GOT_COMMIT} expected=${MONERO_COMMIT}"
[ "${GOT_COMMIT}" = "${MONERO_COMMIT}" ] || { echo "MONERO COMMIT MISMATCH"; exit 3; }
git submodule sync && git submodule update --init --force --recursive
# Documented minimal patch: disable RandomX's aarch64 JIT so it uses the
# interpreter fallback. A daemon-trusting light wallet never executes RandomX
# proof-of-work (only randomx_get_flags is called, which the fallback provides),
# and the aarch64 JIT template carries an out-of-range conditional branch that
# cannot be linked into a library this large. This does not touch Monero source.
RANDOMX_COMMON=/build/monero/external/randomx/src/common.hpp
if grep -q '^#elif defined(__aarch64__)$' "${RANDOMX_COMMON}"; then
  sed -i 's|^#elif defined(__aarch64__)$|#elif defined(__aarch64__) \&\& 0 // Zerion: RandomX interpreter only|' "${RANDOMX_COMMON}"
fi
# Companion patch: the aarch64 JIT sources reference JitCompilerA64, which the
# common.hpp change removes, so they must not be compiled. Drop them from the
# RandomX source list (the interpreter fallback replaces them).
RANDOMX_CMAKE=/build/monero/external/randomx/CMakeLists.txt
sed -i '/src\/jit_compiler_a64_static\.S/d' "${RANDOMX_CMAKE}"
sed -i 's|src/jit_compiler_a64\.cpp)|)|' "${RANDOMX_CMAKE}"
# Both patches must be present exactly once; fail the build if either drifted,
# so the interpreter-only RandomX change can never silently change.
PATCH_COUNT=$(grep -c 'aarch64__) && 0 // Zerion' "${RANDOMX_COMMON}")
[ "${PATCH_COUNT}" = "1" ] || { echo "RandomX common.hpp patch count=${PATCH_COUNT} (expected 1)"; exit 4; }
if grep -q '^#elif defined(__aarch64__)$' "${RANDOMX_COMMON}"; then
  echo "unpatched aarch64 RandomX JIT line still present"; exit 4;
fi
if grep -q 'jit_compiler_a64' "${RANDOMX_CMAKE}"; then
  echo "RandomX a64 JIT sources still in CMake source list"; exit 4;
fi
echo "RandomX interpreter patches verified (common.hpp + CMakeLists)"
MB=/build/monero/build/${ABI}
# Dependencies are built; drop the cross compilers from the environment so
# Monero's translations ExternalProject (which has no toolchain file) builds its
# host generate_translations_header tool with the host gcc instead of the NDK
# clang. The main Monero build sets its compiler from the toolchain file, so it
# is unaffected.
unset CC CXX AR RANLIB
if [ ! -f "${MB}/lib/libwallet_api.a" ]; then
  rm -rf ${MB}; mkdir -p ${MB}; cd ${MB}
  cmake ../.. \
    -DCMAKE_TOOLCHAIN_FILE=${NDK}/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=${ABI} -DANDROID_PLATFORM=android-${API} \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_FIND_ROOT_PATH=${DEPS} -DCMAKE_PREFIX_PATH=${DEPS} \
    -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
    -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
    -DBUILD_GUI_DEPS=ON -DBUILD_TESTS=OFF \
    -DSTATIC=ON -DUSE_DEVICE_TREZOR=OFF -DBUILD_TAG="android" \
    -DBoost_NO_BOOST_CMAKE=ON -DBoost_NO_SYSTEM_PATHS=ON -DBOOST_ROOT=${DEPS} \
    -DBoost_INCLUDE_DIR=${DEPS}/include -DBoost_LIBRARY_DIR=${DEPS}/lib \
    -DOPENSSL_ROOT_DIR=${DEPS} -DOPENSSL_USE_STATIC_LIBS=ON \
    -DOPENSSL_INCLUDE_DIR=${DEPS}/include \
    -DOPENSSL_SSL_LIBRARY=${DEPS}/lib/libssl.a \
    -DOPENSSL_CRYPTO_LIBRARY=${DEPS}/lib/libcrypto.a \
    -DSODIUM_LIBRARY=${DEPS}/lib/libsodium.a \
    -DSODIUM_INCLUDE_DIR=${DEPS}/include \
    -DUNBOUND_INCLUDE_DIR=${DEPS}/include \
    -DUNBOUND_LIBRARIES=${DEPS}/lib/libunbound.a \
    -DEXPAT_INCLUDE_DIR=${DEPS}/include \
    -DEXPAT_LIBRARY=${DEPS}/lib/libexpat.a \
    -DZMQ_INCLUDE_PATH=${DEPS}/include \
    -DZMQ_LIB=${DEPS}/lib/libzmq.a \
    -DICONV_INCLUDE_DIR=${DEPS}/include \
    -DICONV_LIBRARIES=${DEPS}/lib/libiconv.a \
    -DCMAKE_C_FLAGS="-Wl,-z,max-page-size=${PAGE}" \
    -DCMAKE_CXX_FLAGS="-Wl,-z,max-page-size=${PAGE}"
  make -j${JOBS} wallet_api
fi
[ -f "${MB}/lib/libwallet_api.a" ] || { echo "Monero wallet_api build failed"; exit 3; }
cd /build

echo "=== [5/5] link libzmonero.so (${ABI}) ==="
export CXX=${TC}/bin/${TRIPLE}${API}-clang++
MONERO=/build/monero
LIBS=$(find ${MB} -name '*.a' | tr '\n' ' ')
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
echo "=== RESULT (${ABI}) ==="
ls -la ${OUT}/libzmonero.so
sha256sum ${OUT}/libzmonero.so | tee ${OUT}/libzmonero.so.sha256
${TC}/bin/llvm-readelf -l ${OUT}/libzmonero.so | grep -m1 LOAD || true
echo "BUILD OK ${ABI}"
