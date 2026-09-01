# Native Argon2id — provenance and build

The wallet-password KDF uses the reference Argon2 implementation, built
reproducibly from vendored source at app build time (no prebuilt `.so` is
checked in or downloaded). The Java (Bouncy Castle) implementation remains the
fail-closed fallback and is proven cryptographically equivalent by tests.

## Upstream source
- Project: phc-winner-argon2 (the PHC reference C implementation of Argon2)
- Repo: https://github.com/P-H-C/phc-winner-argon2
- Pinned commit: `f57e61e19229e23c4445b85494dbf7c07de721cb`
- Corresponds to release: 20190702 (the latest tagged reference release)
- Algorithm version compiled/used: Argon2 v1.3 (`ARGON2_VERSION_13 = 0x13`),
  matching Bouncy Castle's default, so derived keys are identical.
- License: dual CC0 1.0 / Apache License 2.0 (see vendored `argon2/LICENSE`).

## Vendored files (portable subset; no x86 `opt.c`, no CLI/bench/test)
SHA-256 of each vendored file, as committed:

```
518a8b3bea30c3310116e45e255c635f313201e0c33c14a3919047dc0a366b57  include/argon2.h
37354bf9d61211c0e7b7e636728ca4d9881d434e09d2dbac6671b92ffb2a0263  src/argon2.c
e5c5563b74599ac16a8f28d3748984c1af2c84327c684999624332e060bcea8e  src/core.c
7e654cf03547982c93dc1f1eceac8de75bf3509ecc3edc52bf4a8a31e7d2d0e1  src/encoding.c
914fa69d8e01d2042820dc24d01129d7182f50de256ef9606e8d2d9485a1525c  src/ref.c
4448efa3e1add67e1041a48a8567b080530a6017ba8e60a3677a9fcdfa7a4ccc  src/thread.c
a29374f5b56070e216001f59b2b5758fe7ff486158ffcfa8fef0c1f4b1287afb  src/core.h
e0b30dc694990a379bc6ebedcfe85327d0fd6cf7fb1e6411c95edb7dfd669efd  src/encoding.h
58b6316b67b676debba7194ab0c563d8cb390d8ecff8782088132cce6bd264df  src/thread.h
193ec50b9b8e7345b0c2aed4665036cec43fe317ff9a8535213b2769ba8e1c0f  src/blake2/blake2-impl.h
2693277b6130f7f5b2b2d5e2c76b63958b7344b2d5950c6a0da821547e36b09c  src/blake2/blake2.h
370ee07f8abb38af6636e8148de8fddbec8f4a001c847f026373d185ec495538  src/blake2/blake2b.c
15856322ca20bbe88b31967b6f9f3b129238db3923180eca919d6c0217789dbc  src/blake2/blamka-round-ref.h
e01fc30f00792a2bb95136ebe7dd7d01baab62e719ed26ae1b08a3b6b114fdad  LICENSE
```

`argon2_jni.c` is a thin, project-authored JNI shim containing no cryptographic
logic; it forwards to `argon2id_hash_raw` and returns null on any error.

## Build toolchain
- Built via Gradle's CMake integration from `CMakeLists.txt` (this directory).
- CMake: 3.22.1 (Android SDK cmake).
- NDK: 27.x (Android SDK ndk). Record the exact `ndkVersion` from the module
  `build.gradle` for the shipped build.
- ABIs: `arm64-v8a`, `armeabi-v7a` (every ABI Zerion ships).
- Output: `libzargon2.so` per ABI, packaged in the APK.

## Reproduce
1. `git clone https://github.com/P-H-C/phc-winner-argon2 && git checkout f57e61e19229e23c4445b85494dbf7c07de721cb`
2. Diff the vendored `argon2/` tree against upstream `include/` and `src/` — must match the SHA-256 above.
3. Build the module; the KDF equivalence tests (`NativeArgon2EquivalenceTest`)
   compare native vs Java output against each other and the official Argon2
   test vector, and gate the release.
