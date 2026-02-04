#!/bin/bash
# ============================================================================
# Zerion Group Sender Keys Test Runner
# ============================================================================
# Runs all Sender Keys / Group PCS test suites and outputs a summary.
#
# Usage:
#   ./scripts/run-group-senderkeys-tests.sh
#
# Exit codes:
#   0 = All tests passed
#   1 = One or more tests failed
# ============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"

echo "=============================================="
echo "Zerion Group Sender Keys Test Suite"
echo "=============================================="
echo "Date: $(date)"
echo "Project Root: $PROJECT_ROOT"
echo ""

# Test classes to run
TEST_CLASSES=(
    "org.briarproject.briar.privategroup.senderkeys.SenderKeyCryptoTest"
    "org.briarproject.briar.privategroup.senderkeys.EpochRotationTest"
    "org.briarproject.briar.privategroup.senderkeys.SenderKeysIntegrationTest"
)

TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

echo "Running Sender Keys unit and integration tests..."
echo ""

for TEST_CLASS in "${TEST_CLASSES[@]}"; do
    echo "----------------------------------------------"
    echo "Running: $TEST_CLASS"
    echo "----------------------------------------------"

    if ./gradlew :briar-core:test --tests "$TEST_CLASS" --info 2>&1 | tee /tmp/test_output.txt; then
        echo "✓ PASSED: $TEST_CLASS"
        ((PASSED_TESTS++))
    else
        echo "✗ FAILED: $TEST_CLASS"
        ((FAILED_TESTS++))
    fi
    ((TOTAL_TESTS++))
    echo ""
done

echo "=============================================="
echo "TEST SUMMARY"
echo "=============================================="
echo "Total test classes: $TOTAL_TESTS"
echo "Passed: $PASSED_TESTS"
echo "Failed: $FAILED_TESTS"
echo ""

if [ $FAILED_TESTS -eq 0 ]; then
    echo "✓ ALL TESTS PASSED"
    echo ""
    echo "HTML reports available at:"
    echo "  briar-core/build/reports/tests/test/index.html"
    exit 0
else
    echo "✗ SOME TESTS FAILED"
    echo ""
    echo "Review failed tests in:"
    echo "  briar-core/build/reports/tests/test/index.html"
    exit 1
fi
