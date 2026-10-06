# Tor for Android build - provenance & reproducibility

This is the supply-chain record for the Tor executable the app runs,
`libtor.so`, one per shipped ABI. The app is a Tor-only messenger, so this
binary is the anonymity floor: it is built from the pinned official Tor
release source with its three static dependencies at pinned commits, through a
vendored build makefile, and the result is verified by hash by the build
before packaging and by the app before every start. No prebuilt Tor binary and
no unverified download is used.

## Why the build moved in tree

Until 3.0.12 the app took Tor from the Briar Project's published
`org.briarproject:tor-android` artifact, itself a reproducible build of the
same sources with the same makefile. Tor 0.4.9.13, a security release of
2026-09-23 with ten TROVE fixes, was not available from that publisher when
the app needed it, so the same build now runs from this directory with the
same toolchain, which keeps the output byte-identical to what the publisher
would produce: the 0.4.9.12 build from this script reproduces the published
artifact's executables exactly (see the verification section).

## What is built

- `libtor.so` for `arm64-v8a` and `armeabi-v7a`: the Tor client, statically
  linked against libevent, OpenSSL and zlib, built with
  `--disable-module-relay --disable-module-dirauth` (client and onion service
  only), `--enable-android`, PIE, stripped. The exact configure lines are in
  `Makefile`.
- The lyrebird pluggable-transport executable is unchanged and still comes
  from the verified `org.briarproject:lyrebird-android:0.6.2` jar.

## Pins

| Component | Pin |
|---|---|
| Tor | tag `tor-0.4.9.13`, commit `3c575400909efe6599d88e61e7daf0012655ca44` (tag signed by the Tor release key; the matching `tor-0.4.9.13.tar.gz.sha256sum` on dist.torproject.org, `5e748d3272cdf44a7d7741173f371c8def3d96eecb77e93c89c50663ce9cc792`, carries a good signature from David Goulet's key `B74417EDDF22AC9F9E90F49142E86A2A11F48D36`, one of the keys listed in Tor's README) |
| libevent | tag `release-2.1.12-stable`, commit `5df3037d10556bfcb675bc73e516978b75fc7bc7` |
| OpenSSL | tag `openssl-3.5.8`, commit `f4dc4d58b48d346a8270183f89acf826d459b0ca` |
| zlib | tag `v1.3.2`, commit `da607da739fa6047df13e66a2af6b8bec7c2a498` |
| Android NDK | r29 (`29.0.14206865`), zip SHA-256 `4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf` |
| Android API | 21 (the NDK platform level the makefile targets) |
| Build clock | `SOURCE_DATE_EPOCH=1234567890`, `TZ=UTC`, `-fdebug-compilation-dir .`, `-Os`, set by the makefile |
| Makefile | vendored from the Briar Project's tor-reproducer, originally Guardian Project's `tor-android/external/Makefile` (BSD 3-clause); unmodified apart from its header |
| ABIs | arm64-v8a, armeabi-v7a |

The script asserts every commit after checkout and aborts on a mismatch; it
records the resolved commits in `out/SOURCES.txt` and the SHA-256 of each
`libtor.so` next to it.

## Recorded hashes

| File | SHA-256 | Size |
|---|---|---|
| `out/arm64-v8a/libtor.so` | `586209695b73aea897086d0500b7ef1dcccb0a5a8a002c4e1903ab2ad866dced` | 8829968 bytes |
| `out/armeabi-v7a/libtor.so` | `1b83790304f4c15a283ada143d35ef86a30027d814d0cd8cf6e1004c360eeb09` | 7114876 bytes |

The same values are the pins in
`zerion-core-android/src/main/resources/org/zerionproject/tor/binaries.sha256`,
which the Gradle gate `verifyTorNativeArtifacts` checks before the files are
merged and the app checks before every Tor start. A change of any pin above
has to be accompanied by a rebuild and an update of both places.

## Verification

- Toolchain proof: the same script with `TOR_TAG=tor-0.4.9.12` and
  `TOR_COMMIT=78923280eed3eff6a77910bba59ecc1fa2244e02` (the tag whose git id
  the shipped 3.0.12 daemon reports) produced exactly the same bytes as the
  published `tor-android-0.4.9.12.jar` executables
  (`bf36260486d44c2745fda8e4448668a1ee758db0aa79156299979916b03124a3` and
  `e28eee1ce24a54c8518a5ea91de9365f7db3ea0a9c67a6c2c039b6a85e632485`).
- F-Droid: `fdroid-build.sh` run inside
  `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie` with the apt
  packages the recipe lists produced the same two hashes (2026-09-25, gcc 14.2.0 host toolchain, NDK r29 downloaded and verified inside the image).
- Runtime (2026-10-05, OpenSSL 3.5.8 build): on the Moto (Android 15) and the Pixel 9 Pro (Android 17) the installed `libtor.so` has the arm64-v8a SHA-256 above and answers `Tor version 0.4.9.13 (git-3c575400909efe65)` with Libevent 2.1.12-stable, OpenSSL 3.5.8, Zlib 1.3.2; both phones sign in, reach each other over Tor and establish audio calls with it, and a call survives a 35 s connection loss and ends on both sides within the liveness deadline after a lasting loss.

## How to rebuild

Inside the Briar reproducer image (Debian trixie with the build packages) or
on any Debian host with `ca-certificates curl unzip git build-essential make
patch pkg-config autoconf automake libtool perl python3 file xz-utils`:

    ./build-tor-android.sh

writes `out/<abi>/libtor.so` and `out/SOURCES.txt`. `fdroid-build.sh` is the
same build with the F-Droid defaults and is the recipe's build step. The
Gradle build copies the two executables from `out/` into its own directory
and verifies them; nothing is read from the source tree at run time.
