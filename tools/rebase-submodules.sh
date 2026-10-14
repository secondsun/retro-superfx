#!/usr/bin/env bash
# rebase-submodules.sh - Fetch and rebase submodules on latest version from remote
set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT_DIR"

REMOTE="origin"
TARGET_BRANCH="prototype"
DRY_RUN=false
AUTO_STASH=true
ALLOW_FALLBACK=true
SPECIFIED_SUBS=()

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS] [SUBMODULE...]

Fetches the specified remote (default: origin) and rebases submodules
onto the latest upstream version of the target branch (default: prototype).

If a submodule lacks the target branch upstream (e.g., java-language-server tracking master),
it automatically falls back to its active tracking branch unless --no-fallback is specified.

If an error or conflict occurs during a rebase, the script pauses and exits immediately
with diagnostic instructions. Successfully rebased submodules are NOT rolled back.

Options:
  -r, --remote NAME    Git remote to fetch from (default: origin)
  -b, --branch NAME    Branch name to rebase onto (default: prototype)
  -n, --dry-run        Show planned actions without modifying any git state
      --no-stash       Do not auto-stash dirty working trees; exit with error instead
      --no-fallback    Do not fall back to active branch if target branch is missing on remote
  -h, --help           Show this help message

Arguments:
  [SUBMODULE...]       Optional list of submodule names or paths (e.g., sfx-optimizer).
                       If omitted, all submodules registered in .gitmodules are processed.

Examples:
  ./tools/rebase-submodules.sh                     # Fetch & rebase all submodules onto origin/prototype
  ./tools/rebase-submodules.sh --branch main       # Rebase all submodules onto origin/main
  ./tools/rebase-submodules.sh --remote upstream   # Fetch and rebase from upstream remote
  ./tools/rebase-submodules.sh sfx-optimizer       # Rebase only sfx-optimizer
  ./tools/rebase-submodules.sh --dry-run           # Preview rebase operations
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -r|--remote)
            REMOTE="$2"
            shift 2
            ;;
        -b|--branch)
            TARGET_BRANCH="$2"
            shift 2
            ;;
        -n|--dry-run)
            DRY_RUN=true
            shift
            ;;
        --no-stash)
            AUTO_STASH=false
            shift
            ;;
        --no-fallback)
            ALLOW_FALLBACK=false
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            echo "Error: Unknown option: $1" >&2
            usage
            exit 1
            ;;
        *)
            SPECIFIED_SUBS+=("$1")
            shift
            ;;
    esac
done

# Collect all registered submodules from .gitmodules
ALL_REGISTERED_SUBS=()
if [ -f ".gitmodules" ]; then
    while IFS= read -r p; do
        [ -n "$p" ] && ALL_REGISTERED_SUBS+=("$p")
    done < <(git config --file .gitmodules --get-regexp path 2>/dev/null | awk '{print $2}')
fi

if [ ${#ALL_REGISTERED_SUBS[@]} -eq 0 ]; then
    echo "Error: No submodules registered in .gitmodules." >&2
    exit 1
fi

# Determine target submodules to operate on
SUBS_TO_PROCESS=()
if [ ${#SPECIFIED_SUBS[@]} -gt 0 ]; then
    for target in "${SPECIFIED_SUBS[@]}"; do
        target="${target%/}"
        found=false
        for reg in "${ALL_REGISTERED_SUBS[@]}"; do
            if [ "$target" = "$reg" ]; then
                found=true
                break
            fi
        done
        if [ "$found" = false ]; then
            echo "Error: '$target' is not a registered submodule in .gitmodules." >&2
            exit 1
        fi
        SUBS_TO_PROCESS+=("$target")
    done
else
    SUBS_TO_PROCESS=("${ALL_REGISTERED_SUBS[@]}")
fi

echo "=================================================================="
echo " Submodule Fetch & Rebase"
echo " Remote:         $REMOTE"
echo " Target Branch:  $TARGET_BRANCH"
echo " Submodules:     ${SUBS_TO_PROCESS[*]}"
[ "$DRY_RUN" = true ] && echo " Mode:           DRY RUN (no changes will be made)"
echo "=================================================================="

SUCCESSFUL_SUBS=()

for sub in "${SUBS_TO_PROCESS[@]}"; do
    proj_dir="${ROOT_DIR}/${sub}"
    echo ""
    echo "------------------------------------------------------------------"
    echo " Processing: ${sub}"
    echo " Path:       ${proj_dir}"
    echo "------------------------------------------------------------------"

    if [ ! -d "${proj_dir}" ]; then
        echo "Error: Submodule directory '${proj_dir}' does not exist." >&2
        echo "Run 'git submodule update --init ${sub}' to initialize it." >&2
        exit 1
    fi

    if [ ! -d "${proj_dir}/.git" ] && [ ! -f "${proj_dir}/.git" ]; then
        echo "Error: '${proj_dir}' is not a valid git repository or submodule." >&2
        exit 1
    fi

    # Verify remote exists
    if ! git -C "${proj_dir}" remote get-url "${REMOTE}" &>/dev/null; then
        echo "Error: Remote '${REMOTE}' is not configured in submodule '${sub}'." >&2
        exit 1
    fi

    # Check dirty working tree
    dirty_status="$(git -C "${proj_dir}" status --porcelain 2>/dev/null || true)"
    stashed=false

    if [ -n "${dirty_status}" ]; then
        if [ "$AUTO_STASH" = false ]; then
            echo "Error: Submodule '${sub}' has uncommitted changes in its working tree, and --no-stash is set." >&2
            git -C "${proj_dir}" status --short
            exit 1
        fi

        if [ "$DRY_RUN" = true ]; then
            echo "  [DRY RUN] Would stash uncommitted changes in '${sub}'"
        else
            echo "  Notice: Stashing uncommitted changes in '${sub}'..."
            git -C "${proj_dir}" stash push -u -m "rebase-submodules: auto-stash before rebase onto ${REMOTE}/${TARGET_BRANCH}"
            stashed=true
        fi
    fi

    # Fetch remote
    if [ "$DRY_RUN" = true ]; then
        echo "  [DRY RUN] Would fetch remote '${REMOTE}'"
    else
        echo "  Fetching '${REMOTE}'..."
        if ! git -C "${proj_dir}" fetch "${REMOTE}"; then
            echo "Error: Failed to fetch '${REMOTE}' in submodule '${sub}'." >&2
            if [ "$stashed" = true ]; then
                echo "Restoring stashed changes..." >&2
                git -C "${proj_dir}" stash pop || true
            fi
            exit 1
        fi
    fi

    # Determine branch to rebase onto
    effective_branch="${TARGET_BRANCH}"
    if ! git -C "${proj_dir}" rev-parse --verify --quiet "refs/remotes/${REMOTE}/${TARGET_BRANCH}" >/dev/null; then
        if [ "$ALLOW_FALLBACK" = true ]; then
            active_branch="$(git -C "${proj_dir}" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
            if [ -n "${active_branch}" ] && [ "${active_branch}" != "HEAD" ] && \
               git -C "${proj_dir}" rev-parse --verify --quiet "refs/remotes/${REMOTE}/${active_branch}" >/dev/null; then
                effective_branch="${active_branch}"
                echo "  Notice: Branch '${TARGET_BRANCH}' not found on ${REMOTE}; falling back to active branch '${effective_branch}'."
            else
                remote_head_ref="$(git -C "${proj_dir}" symbolic-ref "refs/remotes/${REMOTE}/HEAD" 2>/dev/null || true)"
                if [ -n "${remote_head_ref}" ]; then
                    default_branch="${remote_head_ref#refs/remotes/${REMOTE}/}"
                    effective_branch="${default_branch}"
                    echo "  Notice: Branch '${TARGET_BRANCH}' not found on ${REMOTE}; falling back to remote default branch '${effective_branch}'."
                else
                    echo "Error: Requested branch '${TARGET_BRANCH}' not found on remote '${REMOTE}' in '${sub}', and no fallback could be determined." >&2
                    if [ "$stashed" = true ]; then
                        git -C "${proj_dir}" stash pop || true
                    fi
                    exit 1
                fi
            fi
        else
            echo "Error: Branch '${TARGET_BRANCH}' does not exist on remote '${REMOTE}' in submodule '${sub}' (--no-fallback enabled)." >&2
            if [ "$stashed" = true ]; then
                git -C "${proj_dir}" stash pop || true
            fi
            exit 1
        fi
    fi

    if [ "$DRY_RUN" = true ]; then
        echo "  [DRY RUN] Would checkout branch '${effective_branch}'"
        echo "  [DRY RUN] Would rebase '${effective_branch}' onto '${REMOTE}/${effective_branch}'"
        if [ -n "${dirty_status}" ]; then
            echo "  [DRY RUN] Would pop stashed changes"
        fi
        SUCCESSFUL_SUBS+=("${sub} -> ${REMOTE}/${effective_branch} (dry run)")
        continue
    fi

    # Ensure local branch exists and is checked out
    current_branch="$(git -C "${proj_dir}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "HEAD")"
    if [ "${current_branch}" != "${effective_branch}" ]; then
        if git -C "${proj_dir}" show-ref --verify --quiet "refs/heads/${effective_branch}"; then
            echo "  Checking out local branch '${effective_branch}'..."
            git -C "${proj_dir}" checkout "${effective_branch}"
        else
            echo "  Creating and checking out tracking branch '${effective_branch}' from '${REMOTE}/${effective_branch}'..."
            git -C "${proj_dir}" checkout -b "${effective_branch}" --track "${REMOTE}/${effective_branch}"
        fi
    fi

    # Rebase onto upstream
    echo "  Rebasing onto '${REMOTE}/${effective_branch}'..."
    if ! git -C "${proj_dir}" rebase "${REMOTE}/${effective_branch}"; then
        echo "" >&2
        echo "==================================================================" >&2
        echo " ERROR: Rebase encountered conflicts or failed in '${sub}'" >&2
        echo "==================================================================" >&2
        echo " Project:       ${sub}" >&2
        echo " Directory:     ${proj_dir}" >&2
        echo " Target Branch: ${REMOTE}/${effective_branch}" >&2
        echo "" >&2
        echo " The rebase has PAUSED due to conflicts." >&2
        echo " Note: Previously rebased submodules were NOT rolled back." >&2
        if [ ${#SUCCESSFUL_SUBS[@]} -gt 0 ]; then
            echo " Successfully updated so far: ${SUCCESSFUL_SUBS[*]}" >&2
        fi
        echo "" >&2
        echo " Resolution Steps:" >&2
        echo "   1. Inspect conflicting files:" >&2
        echo "      cd \"${proj_dir}\" && git status" >&2
        echo "   2. Resolve conflicts in your editor and stage the resolved files:" >&2
        echo "      git add <resolved-files>" >&2
        echo "   3. Continue the rebase:" >&2
        echo "      git rebase --continue" >&2
        echo "   (Or abort this submodule's rebase: git rebase --abort)" >&2
        if [ "$stashed" = true ]; then
            echo "" >&2
            echo "   Important: Stashed working tree changes were preserved." >&2
            echo "   Once rebase completes, restore them with: git stash pop" >&2
        fi
        echo "==================================================================" >&2
        exit 1
    fi

    # Restore stash if needed
    if [ "$stashed" = true ]; then
        echo "  Restoring stashed changes in '${sub}'..."
        if ! git -C "${proj_dir}" stash pop; then
            echo "" >&2
            echo "==================================================================" >&2
            echo " WARNING: Conflicts occurred while applying stash in '${sub}'" >&2
            echo "==================================================================" >&2
            echo " The rebase onto ${REMOTE}/${effective_branch} succeeded," >&2
            echo " but applying local uncommitted changes resulted in conflicts." >&2
            echo " Directory: ${proj_dir}" >&2
            echo " Resolution:" >&2
            echo "   1. Resolve conflicts in working tree." >&2
            echo "   2. Drop the stash when done: git stash drop" >&2
            echo "==================================================================" >&2
            exit 1
        fi
    fi

    head_hash="$(git -C "${proj_dir}" rev-parse --short HEAD)"
    echo "  ✓ Successfully rebased '${sub}' on '${REMOTE}/${effective_branch}' (${head_hash})"
    SUCCESSFUL_SUBS+=("${sub} (${effective_branch}@${head_hash})")
done

echo ""
echo "=================================================================="
echo " Submodule Rebase Complete"
echo "=================================================================="
for item in "${SUCCESSFUL_SUBS[@]}"; do
    echo "  ✓ ${item}"
done
echo "=================================================================="

