#!/usr/bin/env bash
# Dependency vulnerability scan via the standalone OWASP dependency-check
# CLI. Run from the repo root:
#
#     ./scripts/run-dependency-check.sh
#
# What it does:
#   - Downloads and caches the OWASP dependency-check CLI under build/owasp-cli/
#     at the pinned version DC_VERSION (default 12.2.2). The archive must match
#     DEPENDENCY_CHECK_SHA256, otherwise the script stops before running it.
#   - Scans only what is already built: the jars in build/libs of
#     zerion-core-api, zerion-core, zerion-app-api and zerion-app, and the
#     official debug APK. It builds and resolves nothing itself, so run the
#     Gradle build first; a directory that is missing or empty is skipped.
#   - Writes HTML + JSON reports to build/reports/dependency-check/.
#   - Honors config/owasp-suppressions.xml for accepted false positives.
#
# Required env var (first run, when the CLI is downloaded):
#   DEPENDENCY_CHECK_SHA256  Published SHA-256 of the release archive.
#
# Optional env vars:
#   DC_VERSION    dependency-check release to download (default: 12.2.2).
#   NVD_API_KEY   Free key from https://nvd.nist.gov/developers/request-an-api-key
#                 Speeds up NVD data feed download (~30s vs ~10min without).
#   CVSS_FAIL     Numeric CVSS threshold above which the script exits 1
#                 (default: 7.0). Set to 11 to never fail.

set -euo pipefail

CVSS_FAIL="${CVSS_FAIL:-7.0}"
DC_VERSION="${DC_VERSION:-12.2.2}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT_DIR/build"
CLI_DIR="$BUILD_DIR/owasp-cli"
REPORT_DIR="$BUILD_DIR/reports/dependency-check"
SUPPRESS="$ROOT_DIR/config/owasp-suppressions.xml"

mkdir -p "$CLI_DIR" "$REPORT_DIR"

ZIP="$CLI_DIR/dependency-check-$DC_VERSION-release.zip"
BIN="$CLI_DIR/dependency-check/bin/dependency-check.sh"

if [ ! -x "$BIN" ]; then
	echo ">>> Downloading OWASP dependency-check $DC_VERSION CLI..."
	URL="https://github.com/dependency-check/DependencyCheck/releases/download/v${DC_VERSION}/dependency-check-${DC_VERSION}-release.zip"
	if ! curl -fsSL -o "$ZIP" "$URL"; then
		URL="https://github.com/jeremylong/DependencyCheck/releases/download/v${DC_VERSION}/dependency-check-${DC_VERSION}-release.zip"
		curl -fsSL -o "$ZIP" "$URL"
	fi
	if [ -z "${DEPENDENCY_CHECK_SHA256:-}" ]; then
		echo "ERROR: set DEPENDENCY_CHECK_SHA256 to the published SHA-256 of $(basename "$ZIP");" >&2
		echo "       the tool runs on the release host and is not executed unverified." >&2
		exit 1
	fi
	echo "${DEPENDENCY_CHECK_SHA256}  ${ZIP}" | sha256sum -c - || {
		echo "ERROR: downloaded archive does not match DEPENDENCY_CHECK_SHA256" >&2
		exit 1
	}
	(cd "$CLI_DIR" && unzip -q -o "$ZIP")
fi

ARGS=(
	--project "Zerion"
	--out "$REPORT_DIR"
	--format HTML
	--format JSON
	--failOnCVSS "$CVSS_FAIL"
	--disableAssembly
	--disableNuspec
	--disableNodeJS
	--disableNodeAudit
	--disableRetireJS
)

for p in \
		"$ROOT_DIR/zerion-core-api/build/libs" \
		"$ROOT_DIR/zerion-core/build/libs" \
		"$ROOT_DIR/zerion-app-api/build/libs" \
		"$ROOT_DIR/zerion-app/build/libs" \
		"$ROOT_DIR/zerion-android/build/outputs/apk/official/debug"; do
	if [ -d "$p" ] && [ "$(ls -A "$p" 2>/dev/null)" ]; then
		ARGS+=(--scan "$p")
	fi
done

if [ -f "$SUPPRESS" ]; then
	ARGS+=(--suppression "$SUPPRESS")
fi

if [ -n "${NVD_API_KEY:-}" ]; then
	ARGS+=(--nvdApiKey "$NVD_API_KEY")
fi

echo ">>> Running OWASP dependency-check (this may take several minutes on first run while NVD data is downloaded)..."
"$BIN" "${ARGS[@]}"

echo ""
echo ">>> Report: $REPORT_DIR/dependency-check-report.html"
echo ">>> JSON:   $REPORT_DIR/dependency-check-report.json"
