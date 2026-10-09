#!/usr/bin/env bash
# run-sfx-test.sh - Headless SuperFX test runner using Mesen CE
# Usage: ./tools/run-sfx-test.sh <test_name_or_path> [timeout_seconds]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ARG="${1:-vector3_add}"
TIMEOUT="${2:-10}"

# Resolve test directory
if [ -d "${TEST_ARG}" ]; then
    TEST_DIR="$(cd "${TEST_ARG}" && pwd)"
elif [ -d "${ROOT_DIR}/snes-sfx-demo/X-GSU/tests/${TEST_ARG}" ]; then
    TEST_DIR="${ROOT_DIR}/snes-sfx-demo/X-GSU/tests/${TEST_ARG}"
elif [ -d "${ROOT_DIR}/snes-sfx-demo/tests/${TEST_ARG}" ]; then
    TEST_DIR="${ROOT_DIR}/snes-sfx-demo/tests/${TEST_ARG}"
else
    echo "Error: Test directory not found: ${TEST_ARG}" >&2
    echo "Available tests in snes-sfx-demo/X-GSU/tests/:" >&2
    ls -1 "${ROOT_DIR}/snes-sfx-demo/X-GSU/tests" | grep -v '\.' | sed 's/^/  /' >&2
    exit 1
fi

# Locate Mesen executable
MESEN_BIN=""
if [ -x "/home/summers/Programs/Mesen" ]; then
    MESEN_BIN="/home/summers/Programs/Mesen"
elif command -v Mesen >/dev/null 2>&1; then
    MESEN_BIN="$(command -v Mesen)"
elif command -v mesen >/dev/null 2>&1; then
    MESEN_BIN="$(command -v mesen)"
fi

if [ -z "${MESEN_BIN}" ]; then
    echo "Error: Mesen emulator executable not found." >&2
    echo "Please ensure Mesen is placed in /home/summers/Programs/Mesen or on PATH." >&2
    exit 1
fi

echo "=================================================================="
echo " Running SuperFX Headless Unit Test"
echo " Test Dir: ${TEST_DIR}"
echo " Mesen:    ${MESEN_BIN}"
echo " Timeout:  ${TIMEOUT}s"
echo "=================================================================="

# 1. Compile test ROM
echo ">>> [1/3] Compiling test ROM..."
make -C "${TEST_DIR}" clean all

# 2. Check and report symbol addresses, then sync test.lua
SYM_FILE="${TEST_DIR}/Test.cpu.sym"
LUA_SCRIPT="${TEST_DIR}/test.lua"
if [ ! -f "${LUA_SCRIPT}" ]; then
    ALT_LUA="$(find "${TEST_DIR}" -maxdepth 1 -name "*.lua" | head -n 1 || true)"
    if [ -n "${ALT_LUA}" ]; then
        LUA_SCRIPT="${ALT_LUA}"
    fi
fi

SYNC_SCRIPT="${ROOT_DIR}/snes-sfx-demo/.agents/skills/run-gsu-test/scripts/sync_test_labels.py"
EXTRACT_SCRIPT="${ROOT_DIR}/snes-sfx-demo/.agents/skills/create-gsu-test/scripts/extract_syms.py"

if [ -f "${SYM_FILE}" ] && [ -f "${LUA_SCRIPT}" ] && [ -f "${SYNC_SCRIPT}" ]; then
    echo ">>> [2/3] Synchronizing symbol addresses in $(basename "${LUA_SCRIPT}")..."
    python3 "${SYNC_SCRIPT}" "${SYM_FILE}" "${LUA_SCRIPT}" || true
elif [ -f "${SYM_FILE}" ] && [ -f "${EXTRACT_SCRIPT}" ]; then
    echo ">>> [2/3] Extracting symbol map..."
    python3 "${EXTRACT_SCRIPT}" "${SYM_FILE}" || true
fi

# 3. Execute via Mesen CE test runner
LUA_SCRIPT="${TEST_DIR}/test.lua"
if [ ! -f "${LUA_SCRIPT}" ]; then
    # Some tests use test_<name>.lua
    ALT_LUA="$(find "${TEST_DIR}" -maxdepth 1 -name "*.lua" | head -n 1 || true)"
    if [ -n "${ALT_LUA}" ]; then
        LUA_SCRIPT="${ALT_LUA}"
    else
        echo "Error: No .lua test script found in ${TEST_DIR}" >&2
        exit 1
    fi
fi

SFC_FILE="${TEST_DIR}/Test.sfc"
if [ ! -f "${SFC_FILE}" ]; then
    echo "Error: Test ROM ${SFC_FILE} was not generated." >&2
    exit 1
fi

echo ">>> [3/3] Executing Mesen CE headless testrunner..."
"${MESEN_BIN}" --testrunner --timeout="${TIMEOUT}" "${LUA_SCRIPT}" "${SFC_FILE}"
echo "Test execution finished."

