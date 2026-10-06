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
SHA-256 of each vendored file as committed, over the committed bytes (LF
line endings). On a Windows checkout that converts line endings, hash the
committed blobs (for example `git show HEAD:<path> | sha256sum`), not the
working-tree files:

```
25ed629feca91ca9d361441160c6fbc10318bb0fb3757555b418ed47b705b35b  include/argon2.h
b1289ec7134e8502e9113396fdac89402bf2575ee1b35e33fb7410f2fb63bb6d  src/argon2.c
d6ddc9e28c51d2c3b0d542c0c4678c4d9d788da048e4f557166030d0ef62618b  src/core.c
7b9a0c019abc6fca7e6e0a9abd2f7b22a885f8831827cfbd4bfd4502dd9f7806  src/encoding.c
9ac347fd8dc737af69bbb93d56ac8b4ab5488152f606880c8d7fc4592e207647  src/ref.c
af2ab481fcf5ef00f1b2deb346bda3642797b417fc0ed98bfb7ae80e716f90d1  src/thread.c
32f6ab8c0c313d9336d2731a001426b68d246bba5b362fabeaf593c333da7d37  src/core.h
a4e0681ef4b0eb229a35760b603b7a32e9019cfe98c31732f747f087e5e39828  src/encoding.h
650e713fb584de2e6aeb307e64228f95cef733ea667faa0bb111960aaace30ef  src/thread.h
ec9884fe834c30eb362f0cef3432a43a5c496b0d6d1d637a5a590a45bec4d79c  src/blake2/blake2-impl.h
196cd9adf0660474ea04cb686c122f3ca8c758445c5ff0806f438e6412ac8423  src/blake2/blake2.h
7eb2f3faac14c532fb75f645f518686f3ef0db4c7b9849a1ffc73d262b596281  src/blake2/blake2b.c
8d5fc886bbc0b55af10ac6f1e9a5995a4e8d4abace46642fb1832c84d38c3007  src/blake2/blamka-round-ref.h
ac36638bcfcedb75441a5daeeaf4ef75b565911712583c272830e9fa7fddb590  LICENSE
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
