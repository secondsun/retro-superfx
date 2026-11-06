#!/usr/bin/env bash
# run-sfx-test.sh - Headless SuperFX test runner using Mesen CE
# Usage:
#   ./tools/run-sfx-test.sh [all] [timeout_seconds]          # Run all active X-GSU unit tests
#   ./tools/run-sfx-test.sh <test_name_or_path> [timeout_s]  # Run a single test
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TESTS_ROOT="${ROOT_DIR}/snes-sfx-demo/X-GSU/tests"

# Parse '-- @sfx-test status=<active|wip|template> [reason="..."]' header from Lua scripts in a test directory
get_test_status() {
    local dir="$1"
    local lua_file
    for lua_file in "${dir}"/*.lua; do
        [ -f "${lua_file}" ] || continue
        local header
        header="$(grep -m 1 -E '^--[[:space:]]*@sfx-test[[:space:]]' "${lua_file}" || true)"
        if [ -n "${header}" ]; then
            local st
            st="$(echo "${header}" | sed -nE 's/.*status=([a-zA-Z0-9_-]+).*/\1/p')"
            if [ -n "${st}" ]; then
                echo "${st}"
                return 0
            fi
        fi
    done
    echo "active"
}

get_test_reason() {
    local dir="$1"
    local lua_file
    for lua_file in "${dir}"/*.lua; do
        [ -f "${lua_file}" ] || continue
        local header
        header="$(grep -m 1 -E '^--[[:space:]]*@sfx-test[[:space:]]' "${lua_file}" || true)"
        if [ -n "${header}" ]; then
            echo "${header}" | sed -nE 's/.*reason="([^"]*)".*/\1/p'
            return 0
        fi
    done
}

TEST_ARG="${1:-all}"
TIMEOUT="${2:-10}"

if [ "${TEST_ARG}" = "all" ]; then
    PASS_COUNT=0
    FAIL_COUNT=0
    SKIP_COUNT=0
    FAILED_TESTS=()

    for dir in "${TESTS_ROOT}"/*/; do
        [ -d "${dir}" ] || continue
        name="$(basename "${dir}")"
        status="$(get_test_status "${dir}")"
        if [ "${status}" = "template" ]; then
            continue
        fi
        if [ "${status}" = "wip" ]; then
            reason="$(get_test_reason "${dir}")"
            echo "=== SKIP (WIP): ${name}${reason:+ — ${reason}} ==="
            SKIP_COUNT=$((SKIP_COUNT + 1))
            continue
        fi
        echo "=== Running test: ${name} ==="
        if "$0" "${dir}" "${TIMEOUT}"; then
            PASS_COUNT=$((PASS_COUNT + 1))
        else
            FAIL_COUNT=$((FAIL_COUNT + 1))
            FAILED_TESTS+=("${name}")
        fi
    done

    echo "=================================================================="
    echo " SuperFX Test Suite Summary"
    echo " Passed: ${PASS_COUNT} | Failed: ${FAIL_COUNT} | Skipped (WIP): ${SKIP_COUNT}"
    if [ "${FAIL_COUNT}" -gt 0 ]; then
        echo " Failed tests: ${FAILED_TESTS[*]}"
        echo "=================================================================="
        exit 1
    fi
    echo "=================================================================="
    exit 0
fi

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

TEST_NAME="$(basename "${TEST_DIR}")"
TEST_STATUS="$(get_test_status "${TEST_DIR}")"
TEST_REASON="$(get_test_reason "${TEST_DIR}")"
if [ "${TEST_STATUS}" = "template" ]; then
    echo "Error: '${TEST_NAME}' is a scaffold template (${TEST_REASON:-not an executable unit test})." >&2
    exit 1
fi
if [ "${TEST_STATUS}" = "wip" ]; then
    echo "SKIP: '${TEST_NAME}' is a work in progress (WIP)${TEST_REASON:+: ${TEST_REASON}}."
    exit 0
fi

# Locate Mesen executable
MESEN_BIN="${MESEN_PATH:-}"
if [ -n "${MESEN_BIN}" ] && [ ! -x "${MESEN_BIN}" ]; then
    echo "Warning: MESEN_PATH is set to '${MESEN_BIN}', but it is not executable." >&2
    MESEN_BIN=""
fi

if [ -z "${MESEN_BIN}" ]; then
    if [ -x "${HOME}/Programs/Mesen" ]; then
        MESEN_BIN="${HOME}/Programs/Mesen"
    elif [ -x "${HOME}/.local/bin/Mesen" ]; then
        MESEN_BIN="${HOME}/.local/bin/Mesen"
    elif [ -x "${HOME}/bin/Mesen" ]; then
        MESEN_BIN="${HOME}/bin/Mesen"
    elif [ -x "${ROOT_DIR}/../Mesen2/bin/osx-arm64/Release/osx-arm64/publish/Mesen" ]; then
        MESEN_BIN="${ROOT_DIR}/../Mesen2/bin/osx-arm64/Release/osx-arm64/publish/Mesen"
    elif [ -x "${HOME}/Downloads/Mesen/Contents/MacOS/Mesen" ]; then
        MESEN_BIN="${HOME}/Downloads/Mesen/Contents/MacOS/Mesen"
    elif command -v Mesen >/dev/null 2>&1; then
        MESEN_BIN="$(command -v Mesen)"
    elif command -v mesen >/dev/null 2>&1; then
        MESEN_BIN="$(command -v mesen)"
    fi
fi

if [ -z "${MESEN_BIN}" ]; then
    echo "Error: Mesen emulator executable not found." >&2
    echo "Please ensure Mesen is placed in ~/Programs/Mesen, specified via MESEN_PATH, or on PATH." >&2
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

SYNC_SCRIPT="${ROOT_DIR}/.agents/skills/run-gsu-test/scripts/sync_test_labels.py"
EXTRACT_SCRIPT="${ROOT_DIR}/.agents/skills/create-gsu-test/scripts/extract_syms.py"

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

