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
elif [ -d "${ROOT_DIR}/snes-sfx-demo/X-GSU/${TEST_ARG}" ]; then
    TEST_DIR="${ROOT_DIR}/snes-sfx-demo/X-GSU/${TEST_ARG}"
elif [ -d "${ROOT_DIR}/snes-sfx-demo/${TEST_ARG}" ]; then
    TEST_DIR="${ROOT_DIR}/snes-sfx-demo/${TEST_ARG}"
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
elif [ -x "${ROOT_DIR}/../Mesen2/bin/osx-arm64/Release/osx-arm64/publish/Mesen" ]; then
    MESEN_BIN="${ROOT_DIR}/../Mesen2/bin/osx-arm64/Release/osx-arm64/publish/Mesen"
elif [ -x "/Users/summerspittman/Downloads/Mesen/Contents/MacOS/Mesen" ]; then
    MESEN_BIN="/Users/summerspittman/Downloads/Mesen/Contents/MacOS/Mesen"
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
SYM_FILE=""
if [ -f "${TEST_DIR}/Test.cpu.sym" ]; then
    SYM_FILE="${TEST_DIR}/Test.cpu.sym"
elif [ -f "${TEST_DIR}/SuperFX.cpu.sym" ]; then
    SYM_FILE="${TEST_DIR}/SuperFX.cpu.sym"
elif [ -f "${TEST_DIR}/$(basename "${TEST_DIR}").cpu.sym" ]; then
    SYM_FILE="${TEST_DIR}/$(basename "${TEST_DIR}").cpu.sym"
else
    ALT_SYM="$(find "${TEST_DIR}" -maxdepth 1 -name "*.cpu.sym" | head -n 1 || true)"
    if [ -n "${ALT_SYM}" ]; then
        SYM_FILE="${ALT_SYM}"
    fi
fi

LUA_SCRIPT=""
if [ -f "${TEST_DIR}/test.lua" ]; then
    LUA_SCRIPT="${TEST_DIR}/test.lua"
elif [ -f "${TEST_DIR}/test_controller.lua" ]; then
    LUA_SCRIPT="${TEST_DIR}/test_controller.lua"
else
    ALT_LUA="$(find "${TEST_DIR}" -maxdepth 1 -name "test_*.lua" | head -n 1 || true)"
    if [ -z "${ALT_LUA}" ]; then
        ALT_LUA="$(find "${TEST_DIR}" -maxdepth 1 -name "*.lua" | head -n 1 || true)"
    fi
    if [ -n "${ALT_LUA}" ]; then
        LUA_SCRIPT="${ALT_LUA}"
    fi
fi

if [ -z "${LUA_SCRIPT}" ] || [ ! -f "${LUA_SCRIPT}" ]; then
    echo "Error: No .lua test script found in ${TEST_DIR}" >&2
    exit 1
fi

SYNC_SCRIPT="${ROOT_DIR}/snes-sfx-demo/.agents/skills/run-gsu-test/scripts/sync_test_labels.py"
EXTRACT_SCRIPT="${ROOT_DIR}/snes-sfx-demo/.agents/skills/create-gsu-test/scripts/extract_syms.py"

if [ -n "${SYM_FILE}" ] && [ -f "${SYM_FILE}" ] && [ "$(basename "${LUA_SCRIPT}")" = "test.lua" ] && [ -f "${SYNC_SCRIPT}" ]; then
    echo ">>> [2/3] Synchronizing symbol addresses in $(basename "${LUA_SCRIPT}")..."
    python3 "${SYNC_SCRIPT}" "${SYM_FILE}" "${LUA_SCRIPT}" || true
elif [ -n "${SYM_FILE}" ] && [ -f "${SYM_FILE}" ] && [ -f "${EXTRACT_SCRIPT}" ]; then
    echo ">>> [2/3] Extracting symbol map..."
    python3 "${EXTRACT_SCRIPT}" "${SYM_FILE}" || true
fi

# 3. Execute via Mesen CE test runner
SFC_FILE=""
if [ -f "${TEST_DIR}/Test.sfc" ]; then
    SFC_FILE="${TEST_DIR}/Test.sfc"
elif [ -f "${TEST_DIR}/SuperFX.sfc" ]; then
    SFC_FILE="${TEST_DIR}/SuperFX.sfc"
elif [ -f "${TEST_DIR}/$(basename "${TEST_DIR}").sfc" ]; then
    SFC_FILE="${TEST_DIR}/$(basename "${TEST_DIR}").sfc"
else
    ALT_SFC="$(find "${TEST_DIR}" -maxdepth 1 -name "*.sfc" | head -n 1 || true)"
    if [ -n "${ALT_SFC}" ]; then
        SFC_FILE="${ALT_SFC}"
    fi
fi

if [ -z "${SFC_FILE}" ] || [ ! -f "${SFC_FILE}" ]; then
    echo "Error: Test ROM was not generated in ${TEST_DIR}." >&2
    exit 1
fi

echo ">>> [3/3] Executing Mesen CE headless testrunner..."
"${MESEN_BIN}" --testrunner --timeout="${TIMEOUT}" "${LUA_SCRIPT}" "${SFC_FILE}"
echo "Test execution finished."

