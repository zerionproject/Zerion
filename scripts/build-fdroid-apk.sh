#!/usr/bin/env bash
#
# Builds the GitHub-release APK exactly the way F-Droid builds it, so the
# published binary is byte-for-byte reproducible and F-Droid's verification
# passes. This mirrors the F-Droid recipe in fdroiddata
# (metadata/com.professor.zerion.yml) 1:1.
#
# The single most common release mistake is building the GitHub APK WITHOUT
# -Pfdroid. Without that flag the APK embeds a dynamic git hash and build
# timestamp and keeps the merged androidx baseline profile (assets/dexopt/
# baseline.prof*), none of which F-Droid's -Pfdroid build produces, so the two
# binaries differ and F-Droid refuses to publish. Always release the APK this
# script produces, never a plain assembleOfficialRelease.
#
# Usage:
#   scripts/build-fdroid-apk.sh
#
# Requires: JDK 21, the Android SDK, python3, git, and apksigner on PATH, plus
# the release keystore described by ./keystore.properties (storeFile,
# storePassword, keyAlias, keyPassword; the same file the Gradle signing config
# reads). The post-processing tool (reproducible-apk-tools) is fetched at a
# pinned commit and refused if the checkout does not match that commit.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# reproducible-apk-tools v0.3.0: the tag is only a name, the commit is the pin.
RAT_COMMIT="dc069dc4cddf6ab5162f3ed3be1bc8a14711273f"
RAT_REPO="https://github.com/obfusk/reproducible-apk-tools.git"
RAT_DIR="${REPRODUCIBLE_APK_TOOLS:-$REPO_ROOT/.reproducible-apk-tools}"
VERSION="$(grep -E 'versionName "' zerion-android/build.gradle | head -1 | sed -E 's/.*versionName "([^"]+)".*/\1/')"
OUT_APK="$REPO_ROOT/zerion-${VERSION}.apk"

echo "==> Building reproducible F-Droid APK for version ${VERSION}"

if [ ! -d "$RAT_DIR/.git" ]; then
	echo "==> Fetching reproducible-apk-tools at ${RAT_COMMIT}"
	rm -rf "$RAT_DIR"
	git init -q "$RAT_DIR"
	git -C "$RAT_DIR" remote add origin "$RAT_REPO"
	git -C "$RAT_DIR" fetch -q --depth 1 origin "$RAT_COMMIT"
	git -C "$RAT_DIR" checkout -q --detach FETCH_HEAD
fi
RAT_HEAD="$(git -C "$RAT_DIR" rev-parse HEAD)"
if [ "$RAT_HEAD" != "$RAT_COMMIT" ]; then
	echo "ERROR: reproducible-apk-tools checkout is at ${RAT_HEAD}, expected ${RAT_COMMIT}. Refusing to post-process with an unpinned tool." >&2
	exit 1
fi
if [ -n "$(git -C "$RAT_DIR" status --porcelain)" ]; then
	echo "ERROR: reproducible-apk-tools checkout has local modifications. Refusing." >&2
	exit 1
fi

# Mirror the F-Droid recipe. The Gradle dependency verification metadata stays
# in place so every artifact resolved for the shipped build is checksum- and
# signature-verified; a mismatch fails the build instead of silently building
# an unverified dependency in.
sed -i "/include ':bramble-java'/d" settings.gradle || true
if [ ! -f gradle/verification-metadata.xml ]; then
	echo "ERROR: gradle/verification-metadata.xml is missing; refusing to build without dependency verification." >&2
	exit 1
fi

echo "==> assembleOfficialRelease -Pfdroid"
./gradlew clean :zerion-android:assembleOfficialRelease -Pfdroid

BUILT="$(find zerion-android/build/outputs/apk/official/release -name '*.apk' | head -1)"
if [ -z "$BUILT" ]; then
	echo "ERROR: no APK produced" >&2
	exit 1
fi
cp "$BUILT" "$OUT_APK"

echo "==> Post-build: zipalign (reproducible-apk-tools ${RAT_COMMIT})"
mv "$OUT_APK" "$REPO_ROOT/unaligned.apk"
python3 "$RAT_DIR/zipalign.py" --page-size 4 --pad-like-apksigner \
	--replace "$REPO_ROOT/unaligned.apk" "$OUT_APK"
rm -f "$REPO_ROOT/unaligned.apk"

# Guard: the reproducible build must NOT contain a baseline profile. If it does,
# -Pfdroid did not take effect and the APK will fail F-Droid verification.
if unzip -l "$OUT_APK" | grep -q 'assets/dexopt/baseline'; then
	echo "ERROR: APK contains assets/dexopt/baseline.* - -Pfdroid did not apply." >&2
	exit 1
fi

echo "==> Signing with the release key"
"$REPO_ROOT/scripts/sign-release.sh" "$OUT_APK"

echo ""
echo "==> Done: $OUT_APK"
echo "    Upload this as the zerion-${VERSION}.apk asset on the v${VERSION} GitHub release,"
echo "    together with the SHA-256 printed above."
