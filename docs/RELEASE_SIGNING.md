# Release signing

Every Gradle build of this project is unsigned on purpose: `gradle.properties`
sets `fdroid=true`, which keeps the reproducible F-Droid path and the local
path identical, and the signing step is a separate, tracked script that runs
on the release machine only. This file is the process; the scripts are the
implementation and are tested against a throwaway keystore (see below).

## Inputs

- `keystore.properties` at the repository root (never committed):

  ```
  storeFile=/absolute/path/to/zerion.jks
  storePassword=...
  keyAlias=zerionkey
  keyPassword=...
  ```

  The same file feeds the Gradle signing config when `fdroid` is off, so there
  is exactly one description of the key.

- The release certificate. Sideloaded APKs on GitHub and the F-Droid
  reproducible build carry the certificate with SHA-256
  `d7fdb11125890d133ae89d8ba4f4331d9045e21ef01d9899a7cdee6888f704c8`.
  Google Play re-signs the bundle with its own key
  (`b12ddf964ac59e3914984ec93e068768756bb0b917cb45c3fb2b65dc6c7940c6`),
  which is why both are accepted by the in-app signature check.

## APK (GitHub release, byte-identical to F-Droid)

```
scripts/build-fdroid-apk.sh
```

1. Fetches `reproducible-apk-tools` at commit
   `dc069dc4cddf6ab5162f3ed3be1bc8a14711273f` (the commit behind tag
   `v0.3.0`) and refuses to continue if the checkout is at any other commit or
   has local modifications. The tool rewrites the unsigned APK immediately
   before signing, so it is pinned by commit, not by a movable tag.
2. Builds with `-Pfdroid` under Gradle dependency verification.
3. Normalises the bundled certificate newlines and zipaligns exactly as the
   F-Droid recipe does.
4. Calls `scripts/sign-release.sh` on the result.

## AAB (Play upload)

```
./gradlew clean :zerion-android:bundleOfficialRelease -Pfdroid
scripts/sign-release.sh zerion-android/build/outputs/bundle/officialRelease/zerion-android-official-release.aab
```

## What `sign-release.sh` guarantees

- Reads `storeFile`, `storePassword`, `keyAlias`, `keyPassword` from
  `keystore.properties` (a relative `storeFile` is resolved against the
  properties file's directory).
- Passes both passwords through the environment (`--ks-pass env:` for
  apksigner, `-storepass:env` for jarsigner); they never appear on a command
  line.
- APK: v2 and v3 signatures on, v1 and v4 off (minimum SDK 29), then verifies
  the file and refuses to finish unless the signer certificate is the release
  certificate above and a v2 or v3 signature verified.
- AAB: `jarsigner` with SHA-256 digests, then `jarsigner -verify -strict` and a
  check that the signer certificate read back with `keytool -printcert` is the
  release certificate above.
- Prints the SHA-256 of the signed file; that value goes into the GitHub
  release text and the website.

## Testing the script

The script is exercised with a throwaway keystore so a change to it cannot
go unnoticed until release day:

```
keytool -genkeypair -keystore /tmp/t.jks -storepass secret -keypass secret \
  -alias t -keyalg RSA -keysize 2048 -validity 1 -dname CN=test
printf 'storeFile=/tmp/t.jks\nstorePassword=secret\nkeyAlias=t\nkeyPassword=secret\n' > /tmp/t.properties
scripts/sign-release.sh some.apk /tmp/t.properties
```

With a throwaway key the script signs and verifies, then fails on the
certificate check, which is the expected outcome: only the release key
passes.

## Tags

Release tags are created after the signed artifacts exist and are annotated
with the SHA-256 of the APK and the AAB, so the tag, the GitHub asset and the
published hash describe the same bytes.
