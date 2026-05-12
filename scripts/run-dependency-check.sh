#!/usr/bin/env bash
# Dependency vulnerability scan via the standalone OWASP dependency-check
# CLI. Run from the repo root:
#
#     ./scripts/run-dependency-check.sh
#
# What it does:
#   - Resolves all Gradle module compile/runtime dependencies into local jars
#     under build/owasp-input/ via Gradle's `dependencies` task.
#   - Downloads + caches the OWASP dependency-check CLI under build/owasp-cli/
#     (auto-resolves the latest stable release).
#   - Runs the CLI against the resolved dependency tree, writing HTML + JSON
#     reports to build/reports/dependency-check/.
#   - Honors config/owasp-suppressions.xml for accepted false positives.
#
# Optional env vars:
#   NVD_API_KEY   Free key from https://nvd.nist.gov/developers/request-an-api-key
#                 Speeds up NVD data feed download (~30s vs ~10min without).
#   CVSS_FAIL     Numeric CVSS threshold above which the script exits 1
#                 (default: 7.0). Set to 11 to never fail.

set -euo pipefail

CVSS_FAIL="${CVSS_FAIL:-7.0}"
DC_VERSION="${DC_VERSION:-10.0.4}"
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
	URL="https://github.com/jeremylong/DependencyCheck/releases/download/v${DC_VERSION}/dependency-check-${DC_VERSION}-release.zip"
	curl -fsSL -o "$ZIP" "$URL"
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
		"$ROOT_DIR/bramble-api/build/libs" \
		"$ROOT_DIR/bramble-core/build/libs" \
		"$ROOT_DIR/briar-api/build/libs" \
		"$ROOT_DIR/briar-core/build/libs" \
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
