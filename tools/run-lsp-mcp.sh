#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Detect platform
OS_NAME="$(uname -s)"
case "${OS_NAME}" in
    Darwin*)  PLATFORM="mac" ;;
    Linux*)   PLATFORM="linux" ;;
    CYGWIN*|MINGW*|MSYS*) PLATFORM="windows" ;;
    *)        PLATFORM="linux" ;;
esac

LAUNCHER_NAME="launcher"
if [ "${PLATFORM}" = "windows" ]; then
    LAUNCHER_NAME="launcher.bat"
fi

DIST_LAUNCHER="${WORKSPACE_ROOT}/retro-lsp/dist/${PLATFORM}/bin/${LAUNCHER_NAME}"
VSCODE_PLATFORM_LAUNCHER="${WORKSPACE_ROOT}/retro-lsp/vscode/server/${PLATFORM}/bin/${LAUNCHER_NAME}"
VSCODE_LAUNCHER="${WORKSPACE_ROOT}/retro-lsp/vscode/server/bin/${LAUNCHER_NAME}"

# Check if --workspace was already passed in arguments
HAS_WORKSPACE_ARG=false
for arg in "$@"; do
    if [ "$arg" = "--workspace" ]; then
        HAS_WORKSPACE_ARG=true
        break
    fi
done

EXTRA_ARGS=()
if [ "$HAS_WORKSPACE_ARG" = false ]; then
    EXTRA_ARGS=("--workspace" "${WORKSPACE_ROOT}")
fi

if [ -x "${DIST_LAUNCHER}" ]; then
    exec "${DIST_LAUNCHER}" --mcp "${EXTRA_ARGS[@]}" "$@"
elif [ -x "${VSCODE_PLATFORM_LAUNCHER}" ]; then
    exec "${VSCODE_PLATFORM_LAUNCHER}" --mcp "${EXTRA_ARGS[@]}" "$@"
elif [ -x "${VSCODE_LAUNCHER}" ]; then
    exec "${VSCODE_LAUNCHER}" --mcp "${EXTRA_ARGS[@]}" "$@"
fi

# Fallback: run via Java if dist/ hasn't been jlinked but retro-lsp was built via Maven
LSP_TARGET="${WORKSPACE_ROOT}/retro-lsp/target"
DEP_DIR="${LSP_TARGET}/dependency"
MAIN_JAR="$(find "${LSP_TARGET}" -maxdepth 1 -name "retro-lsp-*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" ! -name "*-tests.jar" 2>/dev/null | head -n 1 || true)"

if [ -n "${MAIN_JAR}" ] && [ -d "${DEP_DIR}" ] && command -v java >/dev/null 2>&1; then
    exec java -p "${DEP_DIR}:${MAIN_JAR}" \
        --add-modules ALL-MODULE-PATH \
        -m dev.secondsun.retrolsp/dev.secondsun.retrolsp.Main \
        --mcp "${EXTRA_ARGS[@]}" "$@"
fi

echo "Error: retro-lsp launcher not found for platform '${PLATFORM}'. Run ./tools/build-all.sh to build retro-lsp." >&2
exit 1

