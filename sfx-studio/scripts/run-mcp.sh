#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STUDIO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
WORKSPACE_ROOT="$(cd "${STUDIO_DIR}/.." && pwd)"

CLI_JAR="${STUDIO_DIR}/sfx-studio-cli/target/sfx-studio-cli-1.0.0-SNAPSHOT-all.jar"

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

exec java -Djava.awt.headless=true -XX:TieredStopAtLevel=1 -jar "${CLI_JAR}" --mcp "${EXTRA_ARGS[@]}" "$@"
