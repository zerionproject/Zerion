# Supply chain: what is pinned, where, and how a pin is changed

This file lists every input of the release that is pinned, the gate that
enforces the pin, and the procedure for a deliberate change. A pin that is
not in this table is a finding. Companion documents:
[FDROID.md](FDROID.md) (the reference build),
`packaging/tor-android/PROVENANCE.md`,
`packaging/lyrebird-android/PROVENANCE.md` and
`packaging/monero-android/PROVENANCE.md` (the native builds).

## Build inputs and their gates

| Input | Pin | Gate | Deliberate change |
|---|---|---|---|
| Java and Kotlin dependencies, Android Gradle plugin, R8, aapt2 | `gradle/verification-metadata.xml` (SHA-256 of every artifact and PGP signatures, `verify-metadata` and `verify-signatures` true) and strict lockfiles in every module | Gradle refuses an unlisted or changed artifact in every build that keeps the metadata, and a release build stops unless verification is strict and the metadata is present; CI job `strict-from-clean-cache` resolves every compile, test and release runtime classpath from an empty cache with `--dependency-verification strict` and proves a tampered jar is rejected | Edit the metadata entry by hand for the new version, never regenerate it blindly; re-run the strict CI job |
| The F-Droid reference build | the recipe in fdroiddata | from 3.0.15 the recipe keeps `gradle/verification-metadata.xml`, so the F-Droid build, and the published APK that is its signed output, verify every dependency by checksum and signature; the newest fdroiddata entry (3.0.17, checked 2026-10-10) has no `rm:` step for the file, and `scripts/fdroid-metadata.py` refuses to run the CI reference build with a newest entry that removes it. The fdroiddata entries for 2.0.1 to 3.0.3 (the versions F-Droid has published so far) and the recipes behind the published APKs of 3.0.12 to 3.0.14 removed the file, so those builds used locked versions without checksum and signature verification. The recipe names `reproducible-apk-tools` by the tag `v0.3.0` (commit `dc069dc4cddf6ab5162f3ed3be1bc8a14711273f` on 2026-10-10) rather than by commit, and installs CMake from the build image's Debian packages without pinning it | a recipe change is a merge request to fdroiddata and a matching note here |
| Gradle distribution | `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` | the wrapper refuses a distribution with another hash | `./gradlew wrapper --gradle-version X --gradle-distribution-sha256-sum <sum from gradle.org/release-checksums>` |
| Gradle wrapper jar | `gradle/wrapper/gradle-wrapper.jar.sha256` (the jar Gradle 8.14.3 ships) | `scripts/check-wrapper.sh` (run first in CI) and `gradle/actions/wrapper-validation` | regenerate with `./gradlew wrapper`, update the pin file, confirm against `https://services.gradle.org/distributions/gradle-<version>-wrapper.jar.sha256` |
| Tor executable | built from source: Tor, libevent, OpenSSL and zlib at pinned tags and commits, NDK r29 by zip hash (`packaging/tor-android/build-tor-android.sh`); output hashes in `zerion-core-android/src/main/resources/org/zerionproject/tor/binaries.sha256` | `verifyTorNativeArtifacts` before `merge*JniLibFolders`; the app checks the same file before every start | rebuild in two environments, update `binaries.sha256` and `PROVENANCE.md` together |
| lyrebird executable | built from source since 3.0.18: lyrebird at the signed upstream tag `0.9.0`, commit `89d54872b176f936232ff39f7a8189b77d83d65e`, with the Go toolchain and Go modules below and NDK r29 by zip hash (`packaging/lyrebird-android/build-lyrebird-android.sh`, where every pin is fixed in the file and no environment variable can change one); the tag signer's public key, fingerprint `07948FFA64160A425BCD27EAC732B1D1C28F4E2F`, is committed in `packaging/lyrebird-android/keys/` with its SHA-256 pinned in the script; output hashes in `binaries.sha256`. Up to 3.0.17 the executable came from the Briar Project's `org.briarproject:lyrebird-android:0.6.2` jar, built with Go 1.22.12 | the same Tor gate (`verifyTorNativeArtifacts`) and runtime check; before building, `verify-signed-tag.sh` refuses the checkout unless the tag points at the pinned commit and gpgv or sqv verifies its signature against the committed key alone; the script also refuses an output that is not a stripped Android PIE executable for the ABI, whose arm64 segments are not 16 KB aligned, or whose Go build information names another Go release or a module whose version and hash are not in `go.sum` | change tag and commit together, and the key file, fingerprint and key hash when the signer changes (a key whose fingerprint matches the one in tor-browser-build's `keyring/anti-censorship.gpg`), rebuild in two environments, update `binaries.sha256`, `TorBinaryPinsTest` and `PROVENANCE.md` together; `BundledBridgeSourcesTest` fails when a bundled bridge line needs an argument the pinned lyrebird does not parse |
| Go toolchain for the lyrebird build | the official `go1.26.9.linux-amd64.tar.gz`, SHA-256 `42d158b4d8f7b61ac0a830567c940a86098fb7aac52e467a5ebec03ef5cc2f8d` in `build-lyrebird-android.sh`, as listed by https://go.dev/dl/; `GOTOOLCHAIN=local`, so the go command never fetches another toolchain | `sha256sum -c` before unpacking; the script refuses a toolchain directory it did not unpack itself (stamp file) and any `go version` other than the pinned one. `-checklinkname=0` lets `github.com/wlynxg/anet` reach `net.zoneCache` and x/net's `internal/socket.zoneCache` through `go:linkname` with its own copy of their type, so the script also refuses a toolchain whose `net/interface.go` declares `ipv6ZoneCache` with other fields than `sync.RWMutex`, `lastFetched time.Time`, `toIndex map[string]int` and `toName map[int]string`, in that order, and checks the x/net and anet declarations and anet's linkname targets the same way | take the version and hash from the JSON listing at https://go.dev/dl/?mode=json, change both together, rebuild in two environments; the Go release must still receive security fixes (Go supports the two newest major releases); if the new release changes the fields of `net.ipv6ZoneCache`, the build stops, and the move waits for an anet release whose copy matches |
| Go modules linked into lyrebird | the `go.sum` of the pinned lyrebird commit (its SHA-256 is recorded in `PROVENANCE.md`); modules come from `proxy.golang.org` | `-mod=readonly`, so go.mod and go.sum cannot change and a module without a go.sum entry is refused; `go mod verify` before the build; the build runs with the module proxy off; the build information of the result must list only modules whose versions and hashes are in that `go.sum`, with no replacement. Every entry of that `go.sum` was also checked against the Go checksum database when the pin was recorded (`PROVENANCE.md`) | only with a new lyrebird commit; never `go mod tidy`, never an edited `go.sum`, and nothing is vendored into this repository. The build writes the licence and notice texts of every linked module from the pinned sources and stops unless they equal `docs/licenses/lyrebird-modules.txt`, so a module change also updates that file |
| Built-in Tor bridge lines (`zerion-core/src/main/resources/bridges-*`) | a verbatim copy of the Tor Project's published lines in `zerion-core/src/test/resources/org/zerionproject/tor/bridge-sources.txt`, with the source URLs, the tor-browser-build commit, the fetch date and the SHA-256 of each fetched file (2026-10-17: pt_config.json `6169848125847b9d471de800b7c4278f2fb142e4bffc03e84700aa654d90ae1c`, circumvention map `0196f9f5af47fdfd70d8b6f0b0d57c1eefa24b2d975357ccfe95a7dd092c9b7b`): Tor Browser's built-in bridges (`projects/tor-expert-bundle/pt_config.json` in tor-browser-build) for the default obfs4, snowflake and meek lists, and the Tor Project's circumvention map (`https://bridges.torproject.org/moat/circumvention/map`) for the per-country snowflake and meek lists; the non-default obfs4 and the plain lists are on no public Tor Project list and are recorded in the same file as such | `BundledBridgeSourcesTest` fails when a default or per-country list differs from the copied lines, when a published default obfs4 line is neither bundled nor recorded as not bundled with its reason, or when a list without a public source changes without the copy | fetch both sources, replace the copy, bootstrap Tor through each line on its own, record every published default obfs4 line that is left out as not bundled with the date and the reason, and regenerate the lists from the copy; never add a line that is not in the copy |
| Monero library `libzmonero.so` | built from pinned source tarballs (SHA-256), the Monero commit, NDK r27b by zip hash, a fixed link order and a fixed build clock (`packaging/monero-android`); output hashes in `zerion-android/native/monero/verify-monero-native.gradle` | `verifyMoneroNativeArtifacts` before every `merge*JniLibFolders`, `merge*NativeLibs`, `package*`, `install*`, `assemble*` and `bundle*` task; it also refuses any file in `jniLibs` that no gate pins and any ABI directory the gate does not list | rebuild in both the pinned image and the F-Droid buildserver image; both must agree before the Gradle pins and `PROVENANCE.md` change. The build runs in a fixed, minimal environment (`env -i`, fixed locale, time zone and umask, no inherited compiler flags or git configuration, at most four parallel jobs) and writes a fingerprint of every build to `out/<abi>/buildinfo.txt`, which the Gradle gate prints when it refuses a library (see `PROVENANCE.md`, build environment) |
| Argon2 library `libzargon2.so` | vendored source under `zerion-android/src/main/cpp/argon2`, built by the Android Gradle plugin with the SDK's CMake and the pinned NDK; `-fno-ident` keeps the host compiler's ident string out of the object | reproducible build comparison (APK versus bundle, `scripts/compare-bundle-apk.py`) | a source update records the new upstream commit in `src/main/cpp/PROVENANCE.md` |
| Third-party prebuilt native libraries: SQLCipher (`libsqlcipher.so`, OpenSSL 3.5.4 statically linked, from `net.zetetic:sqlcipher-android:4.13.0`) and CameraX (`libimage_processing_util_jni.so`, `libsurface_util_jni.so`, from `androidx.camera:camera-core:1.4.2`) | Gradle verification of the AAR, and the per-ABI hashes in `zerion-android/native/thirdparty/thirdparty-native.sha256` | `verifyThirdPartyNativeArtifacts<Variant>` runs on the merged native libraries before `package*`, `strip*DebugSymbols` and `bundle*`; every `.so` in a shipped ABI must be either a first-party library pinned by its own gate or listed in the pin file | upgrade the dependency, update the metadata entry, run the gate once to read the new hashes from its failure message, check the vendor's release notes and build provenance, then update the pin file in the same commit |
| Android NDK for the Tor and lyrebird builds | r29, zip SHA-256 in `build-tor-android.sh` and `build-lyrebird-android.sh` (the lyrebird script refuses to run when the two differ) | each script refuses an unpacked NDK that was not verified by one of them (stamp file); only the Tor script accepts `ALLOW_UNVERIFIED_NDK=1` for a local experiment, the lyrebird script has no override | change the hash and revision together in both scripts |
| Android NDK for the Monero build | r27b, zip SHA-256 in the Dockerfile and `fdroid-build.sh` | `sha256sum -c` before unpacking | same |
| reproducible-apk-tools | commit `dc069dc4cddf6ab5162f3ed3be1bc8a14711273f` | `scripts/build-fdroid-apk.sh` refuses any other checkout; the fdroiddata recipe names the tag `v0.3.0`, which resolved to the same commit on 2026-10-10 but is not a pin | update both places |
| OWASP dependency-check CLI | version 12.2.2, archive hash from `DEPENDENCY_CHECK_SHA256` (required, the script refuses to run without it) | `scripts/run-dependency-check.sh` | record the published hash of the new release in the release notes of the run; the tool is not on the release path (it only reports) |
| Signing | the release certificate `d7fdb111...` | `scripts/sign-release.sh` refuses any other certificate; Gradle never signs and never reads `keystore.properties` | a key rotation is announced with the release |
| GitHub Actions | `actions/checkout`, `actions/setup-java`, `actions/setup-python`, `actions/upload-artifact` and `gradle/actions/wrapper-validation`, each pinned by commit hash with its version in a comment | every workflow refers to the commit, so a moved tag changes nothing; the workflows have `contents: read` only and no secrets | to move a pin, run `gh api repos/<owner>/<repo>/git/ref/tags/<tag> --jq .object.sha` (dereference an annotated tag with `.../git/tags/<sha>`) and write `uses: <owner>/<repo>@<sha> # <version>` |


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

## Continuous integration

The workflows run on pushes to `dev` and `master`, on `v*` tags and on pull
requests, each when files it checks change, and once a week regardless. None
of them holds a secret.

- `build-and-test.yml`: `scripts/check-version.py` (the build version, the
  release manifest, the changelogs and the current-release references agree,
  and a tag names the version it builds), the wrapper checks, and the unit
  tests of every module with strict dependency verification and the locked
  configuration copies. A second job builds the commit the way F-Droid does
  (`scripts/fdroid-metadata.py` takes the newest recipe from fdroiddata and
  refuses one that mentions dependency verification;
  `scripts/fdroid-reference-build.sh` runs `fdroid build --test --on-server` in
  F-Droid's build server image), so Tor, lyrebird and Monero are compiled from
  pinned source and every release gate runs: the native pins, `enforceNoLogs`,
  `verifyTranslations`, R8 and the wallet regression suite. The unsigned APK,
  the build log, the digest of the build server image and the fdroiddata and
  fdroidserver commits used are kept for two weeks.
- `dependency-verification.yml`: resolves every classpath from an empty cache
  with strict verification and proves a tampered artifact is refused.
- `docs-consistency.yml`: `scripts/check-version.py` (which also keeps the
  manifest's store channels at or below the released version and README.md's
  Google Play and F-Droid versions equal to the manifest), `scripts/check-docs.py`
  and the published APK against the release manifest. On a push or a pull
  request `check-docs.py` only warns when `docs/bridges/bridges.json` expires
  within 30 days; the weekly run uses `check-docs.py --release`, which fails
  then, as the release gate does. A second job, run weekly
  and by hand only because the stores change without a commit,
  `scripts/check-channels.py`, compares the manifest with GitHub's latest
  release, the newest version F-Droid publishes and the fdroiddata recipe.
  Google Play has no public version API and is confirmed by hand.

Signing stays off CI: `scripts/sign-release.sh` runs on the machine that holds
the release key, on the APK the F-Droid build produced.
