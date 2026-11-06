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
    ./mvnw clean install -DskipGpg=true -Dgpg.skip=true -DskipTests=false
)

# 2. retro-common (dev.secondsun:retro-common)
echo ""
echo ">>> [2/3] Building & installing retro-common..."
(
    cd "${ROOT_DIR}/retro-common"
    ./mvnw clean install -DskipGpg=true -Dgpg.skip=true -DskipTests=false
)

# 3. sfx-optimizer (dev.secondsun:sfx-optimizer)
echo ""
echo ">>> [3/3] Building & installing sfx-optimizer..."
(
    cd "${ROOT_DIR}/sfx-optimizer"
    ./mvnw clean install -DskipGpg=true -Dgpg.skip=true -DskipTests=false
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

# Sync newly built upstream JARs into retro-lsp/dist/*/app if present
for app_dir in "${ROOT_DIR}/retro-lsp/dist/"*/app; do
    if [ -d "$app_dir" ]; then
        echo ""
        echo ">>> Syncing updated upstream JARs to $app_dir..."

        # 1. languageserver
        lang_jar="$(find "${ROOT_DIR}/java-language-server/target" -maxdepth 1 -name "languageserver-*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" ! -name "*-tests.jar" 2>/dev/null | head -n 1 || true)"
        if [ -n "$lang_jar" ]; then
            atomic_sync_jar "$lang_jar" "languageserver" "$app_dir"
        fi

        # 2. retro-common
        rc_jar="$(find "${ROOT_DIR}/retro-common/target" -maxdepth 1 -name "retro-common-*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" ! -name "*-tests.jar" 2>/dev/null | head -n 1 || true)"
        if [ -n "$rc_jar" ]; then
            atomic_sync_jar "$rc_jar" "retro-common" "$app_dir"
        fi

        # 3. sfx-optimizer
        opt_jar="$(find "${ROOT_DIR}/sfx-optimizer/target" -maxdepth 1 -name "sfx-optimizer*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" ! -name "*-tests.jar" ! -name "*-shaded.jar" 2>/dev/null | head -n 1 || true)"
        if [ -n "$opt_jar" ]; then
            atomic_sync_jar "$opt_jar" "sfx-optimizer" "$app_dir"
        fi
    fi
done

echo ""
echo "=================================================================="
echo " Local install complete! All artifacts installed to ~/.m2/repository"
echo " Downstream projects (retro-lsp) can now resolve updated dependencies."
echo "=================================================================="

