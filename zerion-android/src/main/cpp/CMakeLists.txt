cmake_minimum_required(VERSION 3.22.1)
project(zargon2 C)

# Reference Argon2 (phc-winner-argon2 @ f57e61e / release 20190702), vendored
# under argon2/. Portable ref.c only (no x86 opt.c). Single-threaded build:
# parallelism is still honoured as a parameter and the derived output is
# identical to a threaded build, so this only affects speed for parallelism > 1.
set(ARGON2_DIR ${CMAKE_CURRENT_SOURCE_DIR}/argon2)

add_library(zargon2 SHARED
        argon2_jni.c
        ${ARGON2_DIR}/src/argon2.c
        ${ARGON2_DIR}/src/core.c
        ${ARGON2_DIR}/src/encoding.c
        ${ARGON2_DIR}/src/ref.c
        ${ARGON2_DIR}/src/thread.c
        ${ARGON2_DIR}/src/blake2/blake2b.c)

target_include_directories(zargon2 PRIVATE
        ${ARGON2_DIR}/include
        ${ARGON2_DIR}/src)

target_compile_definitions(zargon2 PRIVATE ARGON2_NO_THREADS)

target_compile_options(zargon2 PRIVATE -O3 -fvisibility=hidden)

# Android 15+ 16 KB page-size support: align ELF LOAD segments to 16 KB so the
# library maps correctly on 16 KB-page devices.
target_link_options(zargon2 PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
