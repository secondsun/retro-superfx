#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PACKER_DIR="${WORKSPACE_ROOT}/snes-sfx-demo/build_tools/texture-packer"
JAR="${PACKER_DIR}/target/texture-packer-1.0-SNAPSHOT-jar-with-dependencies.jar"

if [ -f "${JAR}" ]; then
    exec java -cp "${JAR}" dev.secondsun.superx.tools.packer.CliMainKt "$@"
else
    # Build or run via Maven exec
    (
        cd "${PACKER_DIR}"
        mvn exec:java -Dexec.mainClass="dev.secondsun.superx.tools.packer.CliMainKt" -Dexec.args="$*"
    )
fi

