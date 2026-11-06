#!/usr/bin/env bash
# Run an automated SuperFX (GSU) unit test or the full test suite.
# Delegates to tools/run-sfx-test.sh (the canonical point of truth for WIP/template tests).
# Usage: ./run_test.sh [test_directory_or_name] [timeout_seconds]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

exec "${ROOT_DIR}/tools/run-sfx-test.sh" "$@"

