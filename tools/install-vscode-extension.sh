#!/usr/bin/env bash
# install-vscode-extension.sh - Build and install the retro-lsp VS Code extension (.vsix)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LSP_DIR="${ROOT_DIR}/retro-lsp"

REBUILD=false
for arg in "$@"; do
    case "$arg" in
        --rebuild)
            REBUILD=true
            ;;
        -h|--help)
            echo "Usage: $0 [options]"
            echo "Options:"
            echo "  --rebuild     Recompile the Java backend before packaging"
            echo "  -h, --help    Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown argument: $arg" >&2
            exit 1
            ;;
    esac
done

VSCODE_CMD=""
if command -v code >/dev/null 2>&1; then
    VSCODE_CMD="code"
elif command -v codium >/dev/null 2>&1; then
    VSCODE_CMD="codium"
else
    echo "Error: Neither 'code' nor 'codium' command found on PATH." >&2
    exit 1
fi

cd "${LSP_DIR}"

if [ "${REBUILD}" = true ] || [ ! -d "target/dependency" ] || [ ! -f "target/retro-lsp-0.4.0-PROTOTYPE.jar" ]; then
    echo "=== 1. Building retro-lsp JARs ==="
    ./mvnw clean package -DskipTests
fi

echo "=== 2. Creating jlink native image ==="
OS="$(uname -s)"
case "$OS" in
    Linux*)     ./scripts/link_linux.sh ;;
    Darwin*)    ./scripts/link_mac.sh ;;
    CYGWIN*|MINGW*|MSYS*) ./scripts/link_windows.sh ;;
    *)          echo "Unknown OS $OS, skipping jlink" ;;
esac

echo "=== 3. Packaging VS Code Extension (.vsix) ==="
./scripts/bundle_extension.sh

VSIX="$(ls -t "${LSP_DIR}/vscode"/retro-vscode-*.vsix 2>/dev/null | head -n 1)"
if [ -z "${VSIX}" ] || [ ! -f "${VSIX}" ]; then
    echo "Error: No packaged .vsix found in ${LSP_DIR}/vscode" >&2
    exit 1
fi

echo "=== 4. Installing extension into ${VSCODE_CMD} ==="
"${VSCODE_CMD}" --install-extension "${VSIX}" --force

echo ""
echo "=================================================================="
echo " ✓ Successfully installed $(basename "${VSIX}") into ${VSCODE_CMD}"
echo "=================================================================="

