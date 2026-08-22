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
# Requires: JDK 21, the Android SDK, python3, and apksigner on PATH, plus the
# release keystore at ./keystore.properties (key: keystore, storePassword,
# keyAlias, keyPassword). reproducible-apk-tools v0.3.0 is fetched if absent.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

RAT_VERSION="v0.3.0"
RAT_DIR="${REPRODUCIBLE_APK_TOOLS:-$REPO_ROOT/.reproducible-apk-tools}"
VERSION="$(grep -E 'versionName "' zerion-android/build.gradle | head -1 | sed -E 's/.*versionName "([^"]+)".*/\1/')"
OUT_APK="$REPO_ROOT/zerion-${VERSION}.apk"

echo "==> Building reproducible F-Droid APK for version ${VERSION}"

if [ ! -d "$RAT_DIR" ]; then
	echo "==> Fetching reproducible-apk-tools ${RAT_VERSION}"
	git clone --depth 1 --branch "$RAT_VERSION" \
		https://github.com/obfusk/reproducible-apk-tools.git "$RAT_DIR"
fi

# Mirror the F-Droid recipe: these files are removed for the build. They affect
# only build-time dependency verification, never the APK bytes.
rm -f libs/gradle-witness.jar gradle/verification-metadata.xml
sed -i "/include ':bramble-java'/d" settings.gradle || true

echo "==> assembleOfficialRelease -Pfdroid"
./gradlew clean :zerion-android:assembleOfficialRelease -Pfdroid

BUILT="$(find zerion-android/build/outputs/apk/official/release -name '*.apk' | head -1)"
if [ -z "$BUILT" ]; then
	echo "ERROR: no APK produced" >&2
	exit 1
fi
cp "$BUILT" "$OUT_APK"

echo "==> Post-build: normalise reseed cert newlines + zipalign (reproducible-apk-tools)"
python3 "$RAT_DIR/inplace-fix.py" --zipalign fix-newlines "$OUT_APK" \
	'assets/i2p/certificates/reseed/*.crt' 'assets/i2p/certificates/ssl/*.crt'
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
if [ ! -f keystore.properties ]; then
	echo "ERROR: keystore.properties not found at repo root." >&2
	exit 1
fi
KS=$(grep -E '^keystore=' keystore.properties | cut -d= -f2-)
KS_PASS=$(grep -E '^storePassword=' keystore.properties | cut -d= -f2-)
KEY_ALIAS=$(grep -E '^keyAlias=' keystore.properties | cut -d= -f2-)
KEY_PASS=$(grep -E '^keyPassword=' keystore.properties | cut -d= -f2-)
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
	--ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEY_PASS" "$OUT_APK"
apksigner verify --print-certs "$OUT_APK" | grep -i 'SHA-256' || true

echo ""
echo "==> Done: $OUT_APK"
echo "    Upload this as the zerion-${VERSION}.apk asset on the v${VERSION} GitHub release."
echo "    Its signing cert must be d7fdb11125890d133ae89d8ba4f4331d9045e21ef01d9899a7cdee6888f704c8"
