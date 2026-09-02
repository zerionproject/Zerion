# Building Zerion for F-Droid (native libraries from source)

Zerion contains no committed prebuilt binaries. Every native library is built
from pinned source, so an F-Droid build can reproduce the whole app. This
document describes how each native component is built.

## Native components

| Library | Loaded as | Source | How it is built |
|---|---|---|---|
| `libzargon2.so` | `zargon2` | `zerion-android/src/main/cpp/` (Argon2 C, vendored) | Gradle `externalNativeBuild` (CMake) — built automatically by the normal Android build. Nothing extra needed. |
| `libzmonero.so` | `zmonero` | `packaging/monero-android/` (JNI wrapper) + **official Monero**, built from pinned source | Built from source per ABI, then placed in `zerion-android/src/main/jniLibs/<abi>/`. See below. |
| `libpayjoin_ffi.so` | (not loaded) | `zerion-android/native/payjoin/` (Rust) | **Excluded from the APK** (`zerion-android/build.gradle` packaging `excludes`). Dormant; not built or shipped. |

The `.so` files are **not** committed (`.gitignore`); the build produces them.
Two Gradle gates enforce integrity on every `assemble*/bundle*`:

- `zerion-android/native/monero/verify-monero-native.gradle` — fails the build
  if a shipped ABI's `libzmonero.so` is missing, and verifies it against the
  per-ABI SHA-256 pinned in `packaging/monero-android/PROVENANCE.md`.
- `zerion-android/native/payjoin/verify-payjoin-native.gradle` — inert unless a
  Payjoin `.so` is present.

## Building `libzmonero.so`

The reproducible build is defined by `packaging/monero-android/Dockerfile` and
`packaging/monero-android/build-monero-android.sh`, and pins:

| Component | Pin |
|---|---|
| Monero | tag `v0.18.5.1`, commit `4f92268d7c16741cfb41e5bbe2aa46cc260a9ea5` |
| OpenSSL | `1.1.1w` |
| Boost | `1.84.0` |
| libsodium | `1.0.19` |
| Android NDK | r27b (`ndkVersion 27.1.12297006`) |
| Base image | `debian:bookworm-20250630-slim` |

The build compiles Monero's `wallet_api` and its dependencies from source, then
links the small, auditable JNI wrapper (`packaging/monero-android/jni/zmonero.cpp`)
into `libzmonero.so`. No unofficial fork and no prebuilt Monero binary is used.
It runs per ABI (`arm64-v8a`, `armeabi-v7a`); each `.so` is stripped and its
SHA-256 recorded (see PROVENANCE.md).

Local / release build:

```
# for each ABI, build the .so into zerion-android/src/main/jniLibs/<abi>/
#   (see packaging/monero-android/Dockerfile for the exact pinned steps)
./gradlew :zerion-android:assembleOfficialRelease -Pfdroid
```

## Notes for the F-Droid recipe

- Build `libzmonero.so` for `arm64-v8a` and `armeabi-v7a` from the pinned
  sources above into `zerion-android/src/main/jniLibs/<abi>/` before the Gradle
  step (a `prebuild`/`build` step replicating the Dockerfile, using F-Droid's
  NDK r27b).
- Then run the reproducible app build: `assembleOfficialRelease -Pfdroid`
  (`-Pfdroid` strips VCS/timestamp inputs for a reproducible APK).
- `verify-monero-native.gradle` pins the expected `.so` SHA-256. A from-source
  build that reproduces the pinned bytes passes as-is; if F-Droid's toolchain
  produces a different-but-equivalent binary, update the pinned hashes in that
  gate + PROVENANCE.md to the F-Droid-reproducible values (the source is the
  integrity boundary for the F-Droid build).
- `libzargon2.so` needs nothing extra — Gradle builds it from `src/main/cpp`.
