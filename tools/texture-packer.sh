#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PACKER_DIR="${WORKSPACE_ROOT}/snes-sfx-demo/build_tools/texture-packer"
JAR="${PACKER_DIR}/target/texture-packer-1.0-SNAPSHOT-jar-with-dependencies.jar"

if [ ! -f "${JAR}" ]; then
    (
        cd "${PACKER_DIR}"
        mvn -q package -DskipTests >&2
    )
fi

exec java -cp "${JAR}" dev.secondsun.superx.tools.packer.CliMainKt "$@"

