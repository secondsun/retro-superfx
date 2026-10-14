#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="${SCRIPT_DIR}/.venv"

if [ ! -d "${VENV_DIR}" ]; then
    echo "Creating virtual environment at ${VENV_DIR}..." >&2
    python3 -m venv "${VENV_DIR}"
    "${VENV_DIR}/bin/pip" install -q -r "${SCRIPT_DIR}/requirements.txt"
    "${VENV_DIR}/bin/pip" install -q -e "${SCRIPT_DIR}"
fi

export PYTHONPATH="${SCRIPT_DIR}:${PYTHONPATH:-}"
exec "${VENV_DIR}/bin/python3" -m mesen_mcp.server "$@"
