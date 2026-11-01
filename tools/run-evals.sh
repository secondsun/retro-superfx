#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PYTHON_BIN="${WORKSPACE_ROOT}/tools/mesen-mcp/.venv/bin/python"

if [[ ! -x "${PYTHON_BIN}" ]]; then
  PYTHON_BIN="python3"
fi

export PYTHONPATH="${WORKSPACE_ROOT}/tools:${WORKSPACE_ROOT}/tools/mesen-mcp${PYTHONPATH:+:${PYTHONPATH}}"

exec "${PYTHON_BIN}" -m evals.cli "$@"

