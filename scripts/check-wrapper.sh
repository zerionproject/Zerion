#!/usr/bin/env bash
# Verifies the Gradle wrapper before anything runs it: the wrapper jar must
# match the checksum recorded next to it (the jar that Gradle 8.14.3 itself
# ships and the `wrapper` task writes), and gradle-wrapper.properties must pin
# the distribution by SHA-256. A wrapper jar is executable code fetched into
# every clone, so a changed jar fails here instead of running.
#
# Usage: scripts/check-wrapper.sh           (from the repository root or anywhere)
#
# To confirm the recorded checksum against Gradle's published list once network
# access is available:
#   curl -fsSL https://services.gradle.org/distributions/gradle-8.14.3-wrapper.jar.sha256
# must print the same value as gradle/wrapper/gradle-wrapper.jar.sha256.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PIN="gradle/wrapper/gradle-wrapper.jar.sha256"
JAR="gradle/wrapper/gradle-wrapper.jar"
PROPS="gradle/wrapper/gradle-wrapper.properties"

[ -f "$PIN" ] || { echo "ERROR: $PIN is missing" >&2; exit 1; }
[ -f "$JAR" ] || { echo "ERROR: $JAR is missing" >&2; exit 1; }
WANT="$(awk 'NF >= 1 && $1 !~ /^#/ {print $1; exit}' "$PIN")"
GOT="$(sha256sum "$JAR" | cut -d' ' -f1)"
if [ "$WANT" != "$GOT" ]; then
	echo "ERROR: $JAR has SHA-256 $GOT, expected $WANT (regenerate with ./gradlew wrapper from the pinned distribution and update $PIN deliberately)" >&2
	exit 1
fi
grep -q '^distributionSha256Sum=[0-9a-f]\{64\}$' "$PROPS" || {
	echo "ERROR: $PROPS does not pin the distribution by SHA-256" >&2
	exit 1
}
grep -q -E '^distributionUrl=https(\\)?://services\.gradle\.org/distributions/gradle-8\.14\.3-all\.zip$' "$PROPS" || {
	echo "ERROR: $PROPS does not name the pinned distribution" >&2
	exit 1
}
echo "wrapper ok: $JAR $GOT; distribution pinned"
