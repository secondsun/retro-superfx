#!/usr/bin/env bash
# status.sh - Multi-repository git and build status across retro-superfx projects
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECTS=("java-language-server" "retro-common" "sfx-optimizer" "retro-lsp" "snes-sfx-demo")

echo "=================================================================="
echo " retro-superfx Multi-Repository Status"
echo " Root: ${ROOT_DIR}"
echo "=================================================================="

for proj in "${PROJECTS[@]}"; do
    proj_dir="${ROOT_DIR}/${proj}"
    echo ""
    echo "------------------------------------------------------------------"
    echo " Project: ${proj}"
    echo " Path:    ${proj_dir}"
    echo "------------------------------------------------------------------"

    if [ ! -d "${proj_dir}" ]; then
        echo "  [MISSING] Directory not found."
        continue
    fi

    if [ -d "${proj_dir}/.git" ]; then
        branch="$(git -C "${proj_dir}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "detached")"
        head_commit="$(git -C "${proj_dir}" log -1 --oneline 2>/dev/null || echo "no commits")"
        dirty="$(git -C "${proj_dir}" status --porcelain 2>/dev/null)"
        
        echo "  Git Branch : ${branch}"
        echo "  Latest HEAD: ${head_commit}"
        if [ -n "${dirty}" ]; then
            echo "  Working Tree: DIRTY"
            git -C "${proj_dir}" status --short | sed 's/^/    /'
        else
            echo "  Working Tree: CLEAN"
        fi
    else
        echo "  [NO GIT] Not a git repository."
    fi

    # Check Maven or Make details
    if [ -f "${proj_dir}/pom.xml" ]; then
        version="$(grep -m 1 -oP '(?<=<version>)[^<]+' "${proj_dir}/pom.xml" || echo "unknown")"
        echo "  Maven Version: ${version}"
    fi
done

echo ""
echo "=================================================================="
echo " Status Check Complete"
echo "=================================================================="

