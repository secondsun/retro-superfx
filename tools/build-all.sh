#!/usr/bin/env bash
# build-all.sh - Build and test all retro-superfx projects in dependency order
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DO_INSTALL=false
BUILD_ROMS=false
SKIP_TESTS=false

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
        -h|--help)
            echo "Usage: $0 [options]"
            echo "Options:"
            echo "  -i, --install     Run 'mvn clean install -DskipGpg=true' for upstream libraries"
            echo "  -r, --roms        Also compile sample ROMs in snes-sfx-demo"
            echo "  --skip-tests      Compile without running JUnit test suites"
            echo "  -h, --help        Show this help message"
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

EXTRA_FLAGS="-DskipGpg=true"
if [ "$SKIP_TESTS" = true ]; then
    EXTRA_FLAGS="${EXTRA_FLAGS} -DskipTests=true"
fi

echo "=================================================================="
echo " retro-superfx: Building All Subprojects in Dependency Order"
echo " Goal: ${MAVEN_GOAL} | Skip Tests: ${SKIP_TESTS} | Build ROMs: ${BUILD_ROMS}"
echo "=================================================================="

# Step 1: java-language-server
echo ""
echo ">>> [1/4] java-language-server..."
(
    cd "${ROOT_DIR}/java-language-server"
    ./mvnw clean ${MAVEN_GOAL} ${EXTRA_FLAGS}
)

# Step 2: retro-common
echo ""
echo ">>> [2/4] retro-common..."
(
    cd "${ROOT_DIR}/retro-common"
    ./mvnw clean ${MAVEN_GOAL} ${EXTRA_FLAGS}
)

# Step 3: sfx-optimizer
echo ""
echo ">>> [3/4] sfx-optimizer..."
(
    cd "${ROOT_DIR}/sfx-optimizer"
    ./mvnw clean ${MAVEN_GOAL} ${EXTRA_FLAGS}
)

# Step 4: retro-lsp
echo ""
echo ">>> [4/4] retro-lsp..."
(
    cd "${ROOT_DIR}/retro-lsp"
    ./mvnw clean test ${EXTRA_FLAGS}
)

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

