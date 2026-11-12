#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STUDIO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
WORKSPACE_ROOT="$(cd "${STUDIO_DIR}/.." && pwd)"

CLI_JAR="${STUDIO_DIR}/sfx-studio-cli/target/sfx-studio-cli-1.0.0-SNAPSHOT-all.jar"

ensure_cli_jar() {
    if [ ! -f "${CLI_JAR}" ]; then
        echo "Building sfx-studio-cli shaded JAR..." >&2
        if ! "${STUDIO_DIR}/mvnw" -q -f "${STUDIO_DIR}/pom.xml" -pl sfx-studio-cli -am package -DskipTests -DskipGpg=true -Dgpg.skip=true >&2; then
            "${STUDIO_DIR}/mvnw" -q -f "${STUDIO_DIR}/sfx-studio-cli/pom.xml" package -DskipTests -DskipGpg=true -Dgpg.skip=true >&2
        fi
    fi
    if [ ! -f "${CLI_JAR}" ]; then
        echo "Error: Failed to find or build sfx-studio-cli JAR: ${CLI_JAR}" >&2
        exit 1
    fi
}

IS_HEADLESS=false
if [ $# -gt 0 ]; then
    case "$1" in
        --mcp|help|--help|-h|atlas|level|pack|import|validate)
            IS_HEADLESS=true
            ;;
    esac
    for arg in "$@"; do
        if [ "$arg" = "--mcp" ]; then
            IS_HEADLESS=true
            break
        fi
    done
fi

if [ "$IS_HEADLESS" = true ]; then
    ensure_cli_jar
    exec java -Djava.awt.headless=true -XX:TieredStopAtLevel=1 -jar "${CLI_JAR}" "$@"
fi

# GUI mode
if [ $# -gt 0 ] && [ "$1" = "--gui" ]; then
    shift
fi

DESKTOP_TARGET_DIR="${STUDIO_DIR}/sfx-studio-desktop/target"
DESKTOP_JAR=""

find_desktop_jar() {
    if [ -f "${DESKTOP_TARGET_DIR}/sfx-studio-desktop-1.0.0-SNAPSHOT.jar" ] && [ -d "${DESKTOP_TARGET_DIR}/dependency" ]; then
        echo "${DESKTOP_TARGET_DIR}/sfx-studio-desktop-1.0.0-SNAPSHOT.jar"
    elif [ -f "${DESKTOP_TARGET_DIR}/sfx-studio-desktop-1.0.0-SNAPSHOT-all.jar" ]; then
        echo "${DESKTOP_TARGET_DIR}/sfx-studio-desktop-1.0.0-SNAPSHOT-all.jar"
    elif [ -f "${DESKTOP_TARGET_DIR}/sfx-studio-desktop-1.0.0-SNAPSHOT-jar-with-dependencies.jar" ]; then
        echo "${DESKTOP_TARGET_DIR}/sfx-studio-desktop-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
    fi
}

DESKTOP_JAR="$(find_desktop_jar)"

if [ -z "${DESKTOP_JAR}" ]; then
    echo "Building sfx-studio-desktop JAR..." >&2
    if ! "${STUDIO_DIR}/mvnw" -q -f "${STUDIO_DIR}/pom.xml" -pl sfx-studio-desktop -am package -DskipTests -DskipGpg=true -Dgpg.skip=true >&2; then
        "${STUDIO_DIR}/mvnw" -q -f "${STUDIO_DIR}/sfx-studio-desktop/pom.xml" package -DskipTests -DskipGpg=true -Dgpg.skip=true >&2 || true
    fi
    DESKTOP_JAR="$(find_desktop_jar)"
fi

if [ -n "${DESKTOP_JAR}" ] && [ -f "${DESKTOP_JAR}" ]; then
    exec java -jar "${DESKTOP_JAR}" "$@"
else
    echo "Error: sfx-studio-desktop JAR not found at ${DESKTOP_TARGET_DIR}. Build sfx-studio-desktop or run with headless arguments." >&2
    exit 1
fi
