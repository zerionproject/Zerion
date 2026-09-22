#!/usr/bin/env bash
#
# Signs a release artifact with the Zerion release key and verifies the result.
#
#   scripts/sign-release.sh <file.apk|file.aab> [keystore.properties]
#
# The key material comes from keystore.properties (default: ./keystore.properties,
# the same file the Gradle signing config reads), with the keys
#   storeFile=<path to the keystore>
#   storePassword=<...>
#   keyAlias=<...>
#   keyPassword=<...>
# Passwords are handed to the tools through the environment, never as command
# line arguments, so they do not appear in the process list or shell history.
#
# An APK is signed with apksigner using v2 and v3 signatures (v1 off: the
# minimum SDK is 29) and must verify with the expected release certificate.
# An AAB is signed with jarsigner (Play re-signs the bundle with its own key on
# install; the upload key only proves the upload came from us). The SHA-256 of
# the signed file is printed for the release notes.
#
set -euo pipefail

ARTIFACT="${1:?usage: sign-release.sh <file.apk|file.aab> [keystore.properties]}"
PROPS="${2:-$(cd "$(dirname "$0")/.." && pwd)/keystore.properties}"
EXPECTED_CERT="d7fdb11125890d133ae89d8ba4f4331d9045e21ef01d9899a7cdee6888f704c8"

if [ ! -f "$ARTIFACT" ]; then
	echo "ERROR: $ARTIFACT does not exist" >&2
	exit 1
fi
if [ ! -f "$PROPS" ]; then
	echo "ERROR: $PROPS not found (storeFile, storePassword, keyAlias, keyPassword)" >&2
	exit 1
fi

prop() {
	local value
	value="$(grep -E "^[[:space:]]*$1[[:space:]]*=" "$PROPS" | head -1 | sed -E "s/^[[:space:]]*$1[[:space:]]*=//")"
	value="${value%$'\r'}"
	if [ -z "$value" ]; then
		echo "ERROR: $1 is missing from $PROPS" >&2
		exit 1
	fi
	printf '%s' "$value"
}

KS="$(prop storeFile)"
case "$KS" in
	/*|[A-Za-z]:*) ;;
	*) KS="$(dirname "$PROPS")/$KS" ;;
esac
if [ ! -f "$KS" ]; then
	echo "ERROR: keystore $KS does not exist" >&2
	exit 1
fi
export ZERION_SIGN_STORE_PASS="$(prop storePassword)"
export ZERION_SIGN_KEY_PASS="$(prop keyPassword)"
KEY_ALIAS="$(prop keyAlias)"

# Tool lookup: PATH first, then the JDK named by JAVA_HOME and, for apksigner,
# the newest build-tools of the SDK named by ANDROID_HOME / ANDROID_SDK_ROOT.
# On Windows the SDK ships apksigner as a .bat wrapper.
find_tool() {
	local name="$1" candidate
	for candidate in "$name" "$name.bat"; do
		if command -v "$candidate" >/dev/null 2>&1; then command -v "$candidate"; return 0; fi
	done
	if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/$name" ]; then echo "$JAVA_HOME/bin/$name"; return 0; fi
	if [ -n "${JAVA_HOME:-}" ] && [ -f "$JAVA_HOME/bin/$name.exe" ]; then echo "$JAVA_HOME/bin/$name.exe"; return 0; fi
	local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
	if [ -n "$sdk" ] && [ -d "$sdk/build-tools" ]; then
		local newest
		newest="$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
		for candidate in "$newest$name" "$newest$name.bat"; do
			[ -f "$candidate" ] && { echo "$candidate"; return 0; }
		done
	fi
	return 1
}

case "$ARTIFACT" in
	*.apk)
		APKSIGNER="$(find_tool apksigner)" || { echo "ERROR: apksigner not found (PATH, or ANDROID_HOME/build-tools)" >&2; exit 1; }
		"$APKSIGNER" sign --ks "$KS" --ks-pass env:ZERION_SIGN_STORE_PASS \
			--ks-key-alias "$KEY_ALIAS" --key-pass env:ZERION_SIGN_KEY_PASS \
			--v1-signing-enabled false --v2-signing-enabled true \
			--v3-signing-enabled true --v4-signing-enabled false \
			"$ARTIFACT"
		CERT="$("$APKSIGNER" verify --print-certs "$ARTIFACT" | grep -i 'certificate SHA-256 digest' | head -1 | sed -E 's/.*: *//' | tr 'A-F' 'a-f')"
		if [ "$CERT" != "$EXPECTED_CERT" ]; then
			echo "ERROR: signed with certificate $CERT, expected $EXPECTED_CERT" >&2
			exit 1
		fi
		"$APKSIGNER" verify --verbose "$ARTIFACT" | grep -E 'Verified using v[23]' || {
			echo "ERROR: v2/v3 signature verification failed" >&2
			exit 1
		}
		;;
	*.aab)
		JARSIGNER="$(find_tool jarsigner)" || { echo "ERROR: jarsigner not found (PATH or JAVA_HOME)" >&2; exit 1; }
		"$JARSIGNER" -keystore "$KS" -storepass:env ZERION_SIGN_STORE_PASS \
			-keypass:env ZERION_SIGN_KEY_PASS -sigalg SHA256withRSA \
			-digestalg SHA-256 "$ARTIFACT" "$KEY_ALIAS"
		"$JARSIGNER" -verify -strict "$ARTIFACT" | grep -q '^jar verified' || {
			echo "ERROR: bundle signature verification failed" >&2
			exit 1
		}
		KEYTOOL="$(find_tool keytool)" || { echo "ERROR: keytool not found (PATH or JAVA_HOME)" >&2; exit 1; }
		BUNDLE_CERT="$("$KEYTOOL" -printcert -jarfile "$ARTIFACT" | grep -m1 'SHA256:' | sed -E 's/.*SHA256: *//' | tr -d ':' | tr 'A-F' 'a-f')"
		if [ "$BUNDLE_CERT" != "$EXPECTED_CERT" ]; then
			echo "ERROR: bundle signed with certificate $BUNDLE_CERT, expected $EXPECTED_CERT" >&2
			exit 1
		fi
		;;
	*)
		echo "ERROR: $ARTIFACT is neither an .apk nor an .aab" >&2
		exit 1
		;;
esac
unset ZERION_SIGN_STORE_PASS ZERION_SIGN_KEY_PASS

echo "signed:   $ARTIFACT"
echo "sha256:   $(sha256sum -- "$ARTIFACT" | cut -d' ' -f1 | tr -d '\\')"
