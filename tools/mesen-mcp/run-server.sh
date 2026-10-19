#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="${SCRIPT_DIR}/.venv"

# Locate python and pip inside the virtual environment (Unix vs Windows)
if [ -f "${VENV_DIR}/Scripts/python.exe" ]; then
    PYTHON_BIN="${VENV_DIR}/Scripts/python.exe"
    PIP_BIN="${VENV_DIR}/Scripts/pip.exe"
else
    PYTHON_BIN="${VENV_DIR}/bin/python3"
    PIP_BIN="${VENV_DIR}/bin/pip"
fi

if [ ! -d "${VENV_DIR}" ] || [ ! -f "${PYTHON_BIN}" ]; then
    echo "Creating virtual environment at ${VENV_DIR}..." >&2
    if command -v python3 >/dev/null 2>&1; then
        PY_CMD="python3"
    elif command -v python >/dev/null 2>&1; then
        PY_CMD="python"
    else
        echo "Error: Python 3 is required but not found in PATH" >&2
        exit 1
    fi
    "${PY_CMD}" -m venv "${VENV_DIR}"
    if [ -f "${VENV_DIR}/Scripts/pip.exe" ]; then
        PYTHON_BIN="${VENV_DIR}/Scripts/python.exe"
        PIP_BIN="${VENV_DIR}/Scripts/pip.exe"
    else
        PYTHON_BIN="${VENV_DIR}/bin/python3"
        PIP_BIN="${VENV_DIR}/bin/pip"
    fi
    "${PIP_BIN}" install -q -r "${SCRIPT_DIR}/requirements.txt"
    "${PIP_BIN}" install -q -e "${SCRIPT_DIR}"
fi

export PYTHONPATH="${SCRIPT_DIR}:${PYTHONPATH:-}"
exec "${PYTHON_BIN}" -m mesen_mcp.server "$@"

