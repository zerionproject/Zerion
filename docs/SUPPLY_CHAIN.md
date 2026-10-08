# Supply chain: what is pinned, where, and how a pin is changed

This file lists every input of the release that is pinned, the gate that
enforces the pin, and the procedure for a deliberate change. A pin that is
not in this table is a finding. Companion documents:
[FDROID.md](FDROID.md) (the reference build),
`packaging/tor-android/PROVENANCE.md` and
`packaging/monero-android/PROVENANCE.md` (the native builds).

## Build inputs and their gates

| Input | Pin | Gate | Deliberate change |
|---|---|---|---|
| Java and Kotlin dependencies, Android Gradle plugin, R8, aapt2 | `gradle/verification-metadata.xml` (SHA-256 of every artifact and PGP signatures, `verify-metadata` and `verify-signatures` true) and strict lockfiles in every module | Gradle refuses an unlisted or changed artifact in every build that keeps the metadata; CI job `strict-from-clean-cache` resolves every compile, test and release runtime classpath from an empty cache with `--dependency-verification strict` and proves a tampered jar is rejected | Edit the metadata entry by hand for the new version, never regenerate it blindly; re-run the strict CI job |
| The F-Droid reference build | the recipe in fdroiddata | the published recipe still removes `gradle/verification-metadata.xml`, so the published APK is built with locked versions but without checksum and signature verification; a recipe change that keeps the metadata, pins `reproducible-apk-tools` by commit `dc069dc4cddf6ab5162f3ed3be1bc8a14711273f` and pins the CMake of the Monero build is prepared for the owner's merge request (a strict build from an empty cache passes on Linux and the metadata carries the Linux aapt2 artifact) | a recipe change is a merge request to fdroiddata and a matching note here |
| Gradle distribution | `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` | the wrapper refuses a distribution with another hash | `./gradlew wrapper --gradle-version X --gradle-distribution-sha256-sum <sum from gradle.org/release-checksums>` |
| Gradle wrapper jar | `gradle/wrapper/gradle-wrapper.jar.sha256` (the jar Gradle 8.14.3 ships) | `scripts/check-wrapper.sh` (run first in CI) and `gradle/actions/wrapper-validation` | regenerate with `./gradlew wrapper`, update the pin file, confirm against `https://services.gradle.org/distributions/gradle-<version>-wrapper.jar.sha256` |
| Tor executable | built from source: Tor, libevent, OpenSSL and zlib at pinned tags and commits, NDK r29 by zip hash (`packaging/tor-android/build-tor-android.sh`); output hashes in `zerion-core-android/src/main/resources/org/zerionproject/tor/binaries.sha256` | `verifyTorNativeArtifacts` before `merge*JniLibFolders`; the app checks the same file before every start | rebuild in two environments, update `binaries.sha256` and `PROVENANCE.md` together |
| lyrebird executable | `org.briarproject:lyrebird-android:0.6.2` (Gradle verification) and the per-ABI hashes in `binaries.sha256` | the same Tor gate and runtime check | the executable is the one the upstream jar ships, built with the upstream Go toolchain; moving to a newer Go toolchain needs a new upstream lyrebird release |
| Monero library `libzmonero.so` | built from pinned source tarballs (SHA-256), the Monero commit, NDK r27b by zip hash, a fixed link order and a fixed build clock (`packaging/monero-android`); output hashes in `zerion-android/native/monero/verify-monero-native.gradle` | `verifyMoneroNativeArtifacts` before every `merge*JniLibFolders`, `merge*NativeLibs`, `package*`, `install*`, `assemble*` and `bundle*` task; it also refuses any file in `jniLibs` that no gate pins and any ABI directory the gate does not list | rebuild in both the pinned image and the F-Droid buildserver image; both must agree before the Gradle pins and `PROVENANCE.md` change. The build runs in a fixed, minimal environment (`env -i`, fixed locale, time zone and umask, no inherited compiler flags or git configuration, at most four parallel jobs) and writes a fingerprint of every build to `out/<abi>/buildinfo.txt`, which the Gradle gate prints when it refuses a library (see `PROVENANCE.md`, build environment) |
| Argon2 library `libzargon2.so` | vendored source under `zerion-android/src/main/cpp/argon2`, built by the Android Gradle plugin with the SDK's CMake and the pinned NDK; `-fno-ident` keeps the host compiler's ident string out of the object | reproducible build comparison (APK versus bundle, `scripts/compare-bundle-apk.py`) | a source update records the new upstream commit in `src/main/cpp/PROVENANCE.md` |
| Third-party prebuilt native libraries: SQLCipher (`libsqlcipher.so`, OpenSSL 3.5.4 statically linked, from `net.zetetic:sqlcipher-android:4.13.0`) and CameraX (`libimage_processing_util_jni.so`, `libsurface_util_jni.so`, from `androidx.camera:camera-core:1.4.2`) | Gradle verification of the AAR, and the per-ABI hashes in `zerion-android/native/thirdparty/thirdparty-native.sha256` | `verifyThirdPartyNativeArtifacts<Variant>` runs on the merged native libraries before `package*`, `strip*DebugSymbols` and `bundle*`; every `.so` in a shipped ABI must be either a first-party library pinned by its own gate or listed in the pin file | upgrade the dependency, update the metadata entry, run the gate once to read the new hashes from its failure message, check the vendor's release notes and build provenance, then update the pin file in the same commit |
| Android NDK for the Tor build | r29, zip SHA-256 in `build-tor-android.sh` | the script refuses an unpacked NDK that it did not verify itself (stamp file), unless `ALLOW_UNVERIFIED_NDK=1` is set for a local experiment | change the hash and revision together |
| Android NDK for the Monero build | r27b, zip SHA-256 in the Dockerfile and `fdroid-build.sh` | `sha256sum -c` before unpacking | same |
| reproducible-apk-tools | commit `dc069dc4cddf6ab5162f3ed3be1bc8a14711273f` | `scripts/build-fdroid-apk.sh` refuses any other checkout; the recipe pins the same commit | update both places |
| OWASP dependency-check CLI | version 12.2.2, archive hash from `DEPENDENCY_CHECK_SHA256` (required, the script refuses to run without it) | `scripts/run-dependency-check.sh` | record the published hash of the new release in the release notes of the run; the tool is not on the release path (it only reports) |
| Signing | the release certificate `d7fdb111...` | `scripts/sign-release.sh` refuses any other certificate; Gradle never signs and never reads `keystore.properties` | a key rotation is announced with the release |
| GitHub Actions | tags `actions/checkout@v4`, `actions/setup-java@v4`, `actions/setup-python@v5`, `gradle/actions/wrapper-validation@v4` | none yet (the workflows have `contents: read` only and no secrets) | pin by commit: for each action run `gh api repos/<owner>/<repo>/git/ref/tags/<tag> --jq .object.sha` (dereference an annotated tag with `.../git/tags/<sha>`), write `uses: <owner>/<repo>@<sha> # <tag>`, and let Dependabot or a monthly check move the pins |


Dependency locks: every configuration is locked in strict mode (`gradle.lockfile` per module, `buildscript-gradle.lockfile` for the build script). To update them run `./gradlew resolveAndLockAll --write-locks` and then `./gradlew writeIdeCopyLockState`. The second task records lock state for the configuration copies the Android Gradle plugin resolves while Android Studio syncs a project (named `<configuration>Copy`), which otherwise have no lock state and make the sync fail; `./gradlew verifyLockedConfigurationCopies` (part of `check`) resolves such copies under strict locking and fails when that state is missing.

## Line endings

`.gitattributes` forces LF on every text file. The bridge lists and the Tor pin
file are packaged as text resources; a Windows checkout with `core.autocrlf`
once turned them into CRLF, which made the Play bundle differ from the F-Droid
APK in exactly those entries. After the attributes file is committed, run
`git add --renormalize .` once.

## Bundled OpenSSL copies

Three OpenSSL copies ship in the APK: inside Tor (static), inside the Monero
library (static) and inside SQLCipher (static, vendor-built). Before a release
the versions are compared and the two copies this project builds must be at
the same patch release:

    strings packaging/tor-android/out/arm64-v8a/libtor.so | grep -m1 '^OpenSSL 3'
    strings zerion-android/src/main/jniLibs/arm64-v8a/libzmonero.so | grep -m1 '^OpenSSL 3'
    unzip -p ~/.gradle/caches/modules-2/files-2.1/net.zetetic/sqlcipher-android/4.13.0/*/sqlcipher-android-4.13.0.aar jni/arm64-v8a/libsqlcipher.so | strings | grep -m1 '^OpenSSL 3'

A difference is a release blocker for the two in-tree builds and a dependency
upgrade request for SQLCipher.

## Reproducibility checks

- APK: `scripts/build-fdroid-apk.sh` (independent, verification kept) must
  match the `fdroid build --test` output entry for entry before signing.
- Bundle: `scripts/compare-bundle-apk.py <aab> <apk>` must report 0 differing
  and 0 unmatched entries (the manifest and the resource table are compared
  by presence, the `res/` tree by count, everything else byte for byte).
- Native libraries: the per-ABI hashes above, produced in two independent
  environments.

## Build mode

Every Gradle build of this project is the reproducible build. `gradle.properties`
sets `fdroid=true`, the recipe passes the same property, and the build refuses
`-Pfdroid=false`: static `BuildConfig` values, no baseline profile, no Gradle
signing, and a dependency graph that matches the lockfiles (the profile
installer is excluded and not locked). This is why `-Pfdroid` on the command
line is harmless and redundant.
