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

## fdroiddata recipe

The recipe below is what the F-Droid build needs on top of the 3.0.3 entry:
the Debian packages the native build uses, a writable `/build` (the build
runs as `vagrant` and the path is part of the reproducible output, because
Monero's logging macros embed source paths), NDK r27b, and a `build:` step
that produces `libzmonero.so` for both ABIs before Gradle runs. `commit` is
the full hash of the release commit (F-Droid does not accept a tag name), the
same value recorded in `docs/release-manifest.json`.

```yaml
  - versionName: 3.0.11
    versionCode: 31100
    commit: 6e24cdd8476b9ce96fb27ecae81203256bd52cfa
    subdir: zerion-android
    submodules: true
    sudo:
      - apt-get update
      - apt-get install -y --no-install-recommends ca-certificates curl unzip git
        build-essential cmake pkg-config libtool automake autoconf gperf python3
        file xz-utils
      - mkdir -p /build
      - chown vagrant:vagrant /build
    gradle:
      - official
    srclibs:
      - reproducible-apk-tools@v0.3.0
    rm:
      - libs/gradle-witness.jar
      - gradle/verification-metadata.xml
    build: ANDROID_NDK_HOME=$$NDK$$ ../packaging/monero-android/fdroid-build.sh
    ndk: r27b
    gradleprops:
      - fdroid
    postbuild:
      - $$reproducible-apk-tools$$/inplace-fix.py --zipalign fix-newlines $$OUT$$
        'assets/i2p/certificates/reseed/*.crt' 'assets/i2p/certificates/ssl/*.crt'
      - mv $$OUT$$ unaligned.apk
      - $$reproducible-apk-tools$$/zipalign.py --page-size 4 --pad-like-apksigner
        --replace unaligned.apk $$OUT$$
```

`fdroid-build.sh` fetches every dependency archive with a pinned SHA-256 and
clones Monero at the pinned commit. If the F-Droid maintainers prefer declared
inputs, the same archives can be supplied as srclibs; note that git checkouts
of Boost, OpenSSL and libsodium are not byte-identical to the release
tarballs, so the pinned hashes would have to be re-recorded from that build.

## Reproducibility of the shipped library

The pinned hashes are only meaningful if the shipped `.so` was built by the
committed recipe from a clean tree. 3.0.10 shipped a `libzmonero.so` that had
been relinked against dependency archives cached from an earlier revision of
the build script; a clean run of the committed recipe, in both the pinned
Debian image and F-Droid's own `buildserver-trixie` image, deterministically
produces different bytes, so F-Droid cannot verify 3.0.10 against the
published APK. From 3.0.11 on, the build script refuses a cache that was not
produced by the current script, the hashes in PROVENANCE.md and the Gradle
gate are the clean-build values, and a release must ship exactly those bytes.
To check a candidate before tagging, run the build in
`registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie` and compare.

Two further rules follow from the 3.0.11 investigation:

- The Monero archives are linked in the explicit order pinned in
  `build-monero-android.sh`. An unsorted directory enumeration is not
  reproducible across hosts even with identical inputs, because lld lays the
  output out in input order. The 3.0.11 fdroiddata recipe pins the same order
  with a `prebuild` edit of the tagged script; later tags carry it in the
  script itself.
- The release APK that goes on GitHub as the F-Droid reference binary must be
  built on Linux, in F-Droid's build layout, not on a Windows host. The Gradle
  native library `libzargon2.so` keeps debug sections, so a Windows build
  embeds Windows source paths and the Windows-host clang ident string; the
  machine code is identical, but F-Droid compares whole files. The practical
  procedure is to run `fdroid build --test` for the version in the
  `buildserver-trixie` image, sign the resulting unsigned APK with the release
  key, and publish that signed file as the release asset.
