#!/usr/bin/env bash
# Points core.hooksPath of the umbrella repo and each JVM submodule at tools/git-hooks.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
hooks="$root/tools/git-hooks"
chmod +x "$hooks"/*

for repo in . java-language-server retro-common sfx-optimizer retro-lsp; do
  if [ -e "$root/$repo/.git" ]; then
    git -C "$root/$repo" config core.hooksPath "$hooks"
    echo "hooks installed: $repo"
  fi
done

