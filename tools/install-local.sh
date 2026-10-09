#!/usr/bin/env bash
# install-local.sh - Build and install upstream Java/Kotlin libraries to ~/.m2 repository
# Enables rapid cross-project iteration without requiring Maven Central publishing.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "=================================================================="
echo " retro-superfx: Installing Local Maven Artifacts"
echo " Order: java-language-server -> retro-common -> sfx-optimizer"
echo "=================================================================="

# 1. java-language-server (dev.secondsun:languageserver)
echo ""
echo ">>> [1/3] Building & installing java-language-server..."
(
    cd "${ROOT_DIR}/java-language-server"
    ./mvnw clean install -DskipGpg=true -DskipTests=false
)

# 2. retro-common (dev.secondsun:retro-common)
echo ""
echo ">>> [2/3] Building & installing retro-common..."
(
    cd "${ROOT_DIR}/retro-common"
    ./mvnw clean install -DskipGpg=true -DskipTests=false
)

# 3. sfx-optimizer (dev.secondsun:sfx-optimizer)
echo ""
echo ">>> [3/3] Building & installing sfx-optimizer..."
(
    cd "${ROOT_DIR}/sfx-optimizer"
    ./mvnw clean install -DskipGpg=true -DskipTests=false
)

echo ""
echo "=================================================================="
echo " Local install complete! All artifacts installed to ~/.m2/repository"
echo " Downstream projects (retro-lsp) can now resolve updated dependencies."
echo "=================================================================="

