#!/usr/bin/env bash
# Run a GSU test in Mesen CE test runner mode.
# Usage: ./run_test.sh [test_directory] [timeout_seconds]

set -euo pipefail

TEST_DIR="${1:-.}"
TIMEOUT="${2:-10}"

cd "$TEST_DIR"

if [ ! -f "Test.sfc" ]; then
    echo "Error: Test.sfc not found in $TEST_DIR. Run 'make clean all' first." >&2
    exit 1
fi

if [ ! -f "test.lua" ]; then
    echo "Error: test.lua not found in $TEST_DIR." >&2
    exit 1
fi

MESEN_BIN="${MESEN_PATH:-}"
if [ -n "$MESEN_BIN" ] && [ ! -x "$MESEN_BIN" ]; then
    MESEN_BIN=""
fi

if [ -z "$MESEN_BIN" ]; then
    if [ -x "${HOME}/Programs/Mesen" ]; then
        MESEN_BIN="${HOME}/Programs/Mesen"
    elif [ -x "${HOME}/.local/bin/Mesen" ]; then
        MESEN_BIN="${HOME}/.local/bin/Mesen"
    elif [ -x "${HOME}/bin/Mesen" ]; then
        MESEN_BIN="${HOME}/bin/Mesen"
    elif command -v Mesen >/dev/null 2>&1; then
        MESEN_BIN="$(command -v Mesen)"
    elif command -v mesen >/dev/null 2>&1; then
        MESEN_BIN="$(command -v mesen)"
    fi
fi

if [ -z "$MESEN_BIN" ] || [ ! -x "$MESEN_BIN" ]; then
    echo "Error: Mesen executable not found." >&2
    echo "Please ensure Mesen is placed in ~/Programs/Mesen, specified via MESEN_PATH, or on PATH." >&2
    exit 1
fi

echo "Running Mesen test runner in $TEST_DIR (timeout: ${TIMEOUT}s)..."
"$MESEN_BIN" --testrunner --timeout="$TIMEOUT" test.lua Test.sfc
EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    echo "=== TEST PASSED ==="
else
    echo "=== TEST FAILED (Exit Code: $EXIT_CODE) ==="
fi

exit $EXIT_CODE
