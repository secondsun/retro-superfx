#!/usr/bin/env bash
# build-all.sh - Build and test all retro-superfx projects in dependency order
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DO_INSTALL=false
BUILD_ROMS=false
SKIP_TESTS=false
BUILD_VSCODE=false
INSTALL_VSCODE=false

for arg in "$@"; do
    case "$arg" in
        -i|--install)
            DO_INSTALL=true
            ;;
        -r|--roms)
            BUILD_ROMS=true
            ;;
        --skip-tests)
            SKIP_TESTS=true
            ;;
        --vscode)
            BUILD_VSCODE=true
            ;;
        --install-vscode)
            BUILD_VSCODE=true
            INSTALL_VSCODE=true
            ;;
        -h|--help)
            echo "Usage: $0 [options]"
            echo "Options:"
            echo "  -i, --install         Run 'mvn clean install -DskipGpg=true' for upstream libraries"
            echo "  -r, --roms            Also compile sample ROMs in snes-sfx-demo"
            echo "  --skip-tests          Compile without running JUnit test suites"
            echo "  --vscode              Package retro-lsp VS Code extension (.vsix)"
            echo "  --install-vscode      Package and install extension into VS Code ('code' CLI)"
            echo "  -h, --help            Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown argument: $arg" >&2
            exit 1
            ;;
    esac
done

MAVEN_GOAL="test"
if [ "$DO_INSTALL" = true ]; then
    MAVEN_GOAL="install"
fi

EXTRA_FLAGS="-DskipGpg=true -Dgpg.skip=true"
if [ "$SKIP_TESTS" = true ]; then
    EXTRA_FLAGS="${EXTRA_FLAGS} -DskipTests=true"
fi

echo "=================================================================="
echo " retro-superfx: Building All Subprojects in Dependency Order"
echo " Goal: ${MAVEN_GOAL} | Skip Tests: ${SKIP_TESTS} | Build ROMs: ${BUILD_ROMS}"
echo "=================================================================="

if [ -z "$(git -C "${ROOT_DIR}" config core.hooksPath || true)" ]; then
    "${ROOT_DIR}/tools/install-git-hooks.sh"
fi

# Step 1: java-language-server
echo ""
echo ">>> [1/5] java-language-server..."
(
    cd "${ROOT_DIR}/java-language-server"
    ./mvnw clean ${MAVEN_GOAL} ${EXTRA_FLAGS}
)

# Step 2: retro-common
echo ""
echo ">>> [2/5] retro-common..."
(
    cd "${ROOT_DIR}/retro-common"
    ./mvnw clean ${MAVEN_GOAL} ${EXTRA_FLAGS}
)

# Step 3: sfx-optimizer
echo ""
echo ">>> [3/5] sfx-optimizer..."
(
    cd "${ROOT_DIR}/sfx-optimizer"
    ./mvnw clean ${MAVEN_GOAL} ${EXTRA_FLAGS}
)

# Step 4: texture-packer (companion CLI JAR required by retro-lsp tests)
echo ""
echo ">>> [4/5] snes-sfx-demo/build_tools/texture-packer..."
(
    cd "${ROOT_DIR}/snes-sfx-demo/build_tools/texture-packer"
    mvn clean package ${EXTRA_FLAGS}
)

# Step 5: retro-lsp
echo ""
echo ">>> [5/5] retro-lsp..."
(
    cd "${ROOT_DIR}/retro-lsp"
    ./mvnw clean package ${EXTRA_FLAGS}
)

atomic_sync_jar() {
    local src_jar="$1"
    local prefix="$2"
    local app_dir="$3"

    [ -f "$src_jar" ] || return 0
    [ -d "$app_dir" ] || return 0

    local src_name
    src_name="$(basename "$src_jar")"

    local existing
    existing="$(find "$app_dir" -maxdepth 1 -name "${prefix}*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" ! -name "*-tests.jar" 2>/dev/null | head -n 1 || true)"

    local dest_name="$src_name"
    if [ "$src_name" = "${prefix}.jar" ] && [ -n "$existing" ]; then
        dest_name="$(basename "$existing")"
    fi

    local tmp_file="${app_dir}/${dest_name}.tmp.$$"
    cp "$src_jar" "$tmp_file"
    mv -f "$tmp_file" "${app_dir}/${dest_name}"

    if [ -n "$existing" ] && [ "$(basename "$existing")" != "$dest_name" ]; then
        rm -f "$existing"
    fi
}

# Sync freshly built JARs to retro-lsp/dist/<os>/app/ if dist dirs exist
for app_dir in "${ROOT_DIR}/retro-lsp/dist/"*/app; do
    if [ -d "$app_dir" ]; then
        echo ">>> Syncing freshly built JARs into $app_dir..."
        main_jar="$(find "${ROOT_DIR}/retro-lsp/target" -maxdepth 1 -name "retro-lsp-*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" ! -name "*-tests.jar" 2>/dev/null | head -n 1 || true)"
        if [ -n "$main_jar" ]; then
            atomic_sync_jar "$main_jar" "retro-lsp" "$app_dir"
        fi

        for dep in "retro-common" "sfx-optimizer" "languageserver"; do
            dep_jar="$(find "${ROOT_DIR}/retro-lsp/target/dependency" -maxdepth 1 -name "${dep}*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" ! -name "*-tests.jar" 2>/dev/null | head -n 1 || true)"
            if [ -z "$dep_jar" ]; then
                case "$dep" in
                    languageserver) dep_jar="$(find "${ROOT_DIR}/java-language-server/target" -maxdepth 1 -name "languageserver-*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" ! -name "*-tests.jar" 2>/dev/null | head -n 1 || true)" ;;
                    retro-common) dep_jar="$(find "${ROOT_DIR}/retro-common/target" -maxdepth 1 -name "retro-common-*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" ! -name "*-tests.jar" 2>/dev/null | head -n 1 || true)" ;;
                    sfx-optimizer) dep_jar="$(find "${ROOT_DIR}/sfx-optimizer/target" -maxdepth 1 -name "sfx-optimizer*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" ! -name "*-tests.jar" ! -name "*-shaded.jar" 2>/dev/null | head -n 1 || true)" ;;
                esac
            fi
            if [ -n "$dep_jar" ]; then
                atomic_sync_jar "$dep_jar" "$dep" "$app_dir"
            fi
        done
    fi
done

# Step 5a (Optional): VS Code Extension Packaging & Installation
if [ "$BUILD_VSCODE" = true ]; then
    echo ""
    echo ">>> Packaging VS Code extension..."
    (
        cd "${ROOT_DIR}/retro-lsp"
        OS="$(uname -s)"
        case "$OS" in
            Linux*)     ./scripts/link_linux.sh ;;
            Darwin*)    ./scripts/link_mac.sh ;;
            CYGWIN*|MINGW*|MSYS*) ./scripts/link_windows.sh ;;
            *)          echo "Unknown OS $OS, skipping jlink" ;;
        esac
        ./scripts/bundle_extension.sh
    )

    if [ "$INSTALL_VSCODE" = true ]; then
        VSCODE_CMD=""
        if command -v code >/dev/null 2>&1; then
            VSCODE_CMD="code"
        elif command -v codium >/dev/null 2>&1; then
            VSCODE_CMD="codium"
        fi

        if [ -n "$VSCODE_CMD" ]; then
            VSIX="$(ls -t "${ROOT_DIR}/retro-lsp/vscode"/retro-vscode-*.vsix 2>/dev/null | head -n 1)"
            if [ -n "$VSIX" ] && [ -f "$VSIX" ]; then
                echo ">>> Installing VS Code extension: $(basename "$VSIX")..."
                "$VSCODE_CMD" --install-extension "$VSIX" --force
            fi
        else
            echo "Warning: Neither 'code' nor 'codium' CLI found; skipped extension install." >&2
        fi
    fi
fi

# Step 5 (Optional): snes-sfx-demo ROMs
if [ "$BUILD_ROMS" = true ]; then
    echo ""
    echo ">>> [5/5] Building snes-sfx-demo test ROMs..."
    if [ -d "${ROOT_DIR}/snes-sfx-demo/X-GSU/tests/vector3_add" ]; then
        echo "  Building X-GSU vector3_add test ROM..."
        make -C "${ROOT_DIR}/snes-sfx-demo/X-GSU/tests/vector3_add" clean all
    fi
fi

echo ""
echo "=================================================================="
echo " All builds succeeded!"
echo "=================================================================="

