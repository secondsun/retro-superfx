#!/usr/bin/env bash
# update-submodules.sh - Stage modified submodules, generate commit message, and push to origin
set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT_DIR"

DRY_RUN=false
PUSH=true
SPLIT=false
USE_BRACKETS=false
REMOTE="origin"
CUSTOM_BRANCH=""
CUSTOM_MSG=""
TARGET_SUBS=()

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS] [SUBMODULE...]

Stages git submodules with new commits, generates a commit message
("updating submodule[s] <names>"), and pushes to the active branch in origin.

Options:
  -n, --dry-run        Show actions that would be executed without modifying git state
  --no-push            Stage and commit, but do not push to origin
  -s, --split          Create separate commits for each submodule instead of a single batched commit
  -b, --brackets       Use literal "submodule[s]" notation in commit message
  -m, --message MSG    Override commit message (in split mode, prefix or template)
  -r, --remote NAME    Git remote to push to (default: origin)
  --branch NAME        Branch name to push to (default: active branch)
  -h, --help           Show this help message

Arguments:
  [SUBMODULE...]       Optional list of submodule names or paths. If omitted, all
                       submodules with new commits are automatically detected.

Examples:
  ./tools/update-submodules.sh                      # Detect, commit, and push all modified submodules
  ./tools/update-submodules.sh --dry-run            # Preview actions without making changes
  ./tools/update-submodules.sh --no-push            # Add and commit locally without pushing
  ./tools/update-submodules.sh -s                   # Commit each updated submodule individually
  ./tools/update-submodules.sh sfx-optimizer        # Update only sfx-optimizer
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -n|--dry-run)
            DRY_RUN=true
            shift
            ;;
        --no-push)
            PUSH=false
            shift
            ;;
        -s|--split)
            SPLIT=true
            shift
            ;;
        -b|--brackets)
            USE_BRACKETS=true
            shift
            ;;
        -m|--message)
            CUSTOM_MSG="$2"
            shift 2
            ;;
        -r|--remote)
            REMOTE="$2"
            shift 2
            ;;
        --branch)
            CUSTOM_BRANCH="$2"
            shift 2
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
            TARGET_SUBS+=("$1")
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

# Determine target submodules to inspect
SUBS_TO_INSPECT=()
if [ ${#TARGET_SUBS[@]} -gt 0 ]; then
    for target in "${TARGET_SUBS[@]}"; do
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
        SUBS_TO_INSPECT+=("$target")
    done
else
    SUBS_TO_INSPECT=("${ALL_REGISTERED_SUBS[@]}")
fi

# Find which submodules actually have commit updates
MODIFIED_SUBS=()
for sub in "${SUBS_TO_INSPECT[@]}"; do
    is_modified=false
    st_status=$(git submodule status -- "$sub" 2>/dev/null || true)
    if [[ "$st_status" =~ ^\+ ]]; then
        is_modified=true
    fi

    if git diff --cached --raw HEAD -- "$sub" 2>/dev/null | grep -q '160000'; then
        is_modified=true
    fi

    if [ "$is_modified" = true ]; then
        MODIFIED_SUBS+=("$sub")
    fi
done

if [ ${#MODIFIED_SUBS[@]} -eq 0 ]; then
    echo "No submodules with new commits found to update."
    exit 0
fi

# Informative checks on local submodules
for sub in "${MODIFIED_SUBS[@]}"; do
    if [ -d "$sub/.git" ] || [ -f "$sub/.git" ]; then
        dirty_tree=$(git -C "$sub" status --porcelain 2>/dev/null || true)
        if [ -n "$dirty_tree" ]; then
            echo "Notice: Submodule '$sub' contains uncommitted changes in its working tree."
        fi
        unpushed=$(git -C "$sub" log @{u}..HEAD --oneline 2>/dev/null || true)
        if [ -n "$unpushed" ]; then
            echo "Notice: Submodule '$sub' has local commits not yet pushed to its remote."
        fi
    fi
done

# Resolve active branch
BRANCH="$CUSTOM_BRANCH"
if [ -z "$BRANCH" ]; then
    BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "HEAD")"
fi

if [ "$PUSH" = true ] && [ "$DRY_RUN" = false ]; then
    if [ "$BRANCH" = "HEAD" ]; then
        echo "Error: HEAD is detached. Cannot determine active branch to push." >&2
        echo "Use --branch <name> or --no-push." >&2
        exit 1
    fi

    if ! git remote get-url "$REMOTE" &>/dev/null; then
        echo "Error: Git remote '$REMOTE' does not exist." >&2
        exit 1
    fi
fi

# Build commit message for batch mode
names_str=$(printf ", %s" "${MODIFIED_SUBS[@]}")
names_str="${names_str:2}"

if [ -n "$CUSTOM_MSG" ]; then
    BATCH_MSG="$CUSTOM_MSG"
elif [ "$USE_BRACKETS" = true ]; then
    BATCH_MSG="updating submodule[s] ${names_str}"
elif [ ${#MODIFIED_SUBS[@]} -eq 1 ]; then
    BATCH_MSG="updating submodule ${MODIFIED_SUBS[0]}"
else
    BATCH_MSG="updating submodules ${names_str}"
fi

# Dry run mode
if [ "$DRY_RUN" = true ]; then
    echo "=================================================================="
    echo " [DRY RUN] Submodules to update:"
    for sub in "${MODIFIED_SUBS[@]}"; do
        echo "   - $sub"
    done
    echo ""
    echo " Branch: $BRANCH (remote: $REMOTE)"
    echo " Commands that would be executed:"
    if [ "$SPLIT" = true ]; then
        for sub in "${MODIFIED_SUBS[@]}"; do
            msg="updating submodule $sub"
            [ "$USE_BRACKETS" = true ] && msg="updating submodule[s] $sub"
            [ -n "$CUSTOM_MSG" ] && msg="$CUSTOM_MSG: $sub"
            echo "   git add \"$sub\""
            echo "   git commit -m \"$msg\" -- \"$sub\""
        done
    else
        echo "   git add ${MODIFIED_SUBS[*]}"
        echo "   git commit -m \"$BATCH_MSG\" -- ${MODIFIED_SUBS[*]}"
    fi
    if [ "$PUSH" = true ]; then
        echo "   git push \"$REMOTE\" \"$BRANCH\""
    fi
    echo "=================================================================="
    exit 0
fi

# Execution
if [ "$SPLIT" = true ]; then
    for sub in "${MODIFIED_SUBS[@]}"; do
        msg="updating submodule $sub"
        [ "$USE_BRACKETS" = true ] && msg="updating submodule[s] $sub"
        [ -n "$CUSTOM_MSG" ] && msg="$CUSTOM_MSG: $sub"
        echo "==> Staging: $sub"
        git add "$sub"
        echo "==> Committing: '$msg'"
        git commit -m "$msg" -- "$sub"
    done
else
    echo "==> Staging: ${MODIFIED_SUBS[*]}"
    git add "${MODIFIED_SUBS[@]}"
    echo "==> Committing: '$BATCH_MSG'"
    git commit -m "$BATCH_MSG" -- "${MODIFIED_SUBS[@]}"
fi

if [ "$PUSH" = true ]; then
    echo "==> Pushing to $REMOTE on branch $BRANCH..."
    git push "$REMOTE" "$BRANCH"
    echo "Successfully updated and pushed submodules."
else
    echo "Skipping push (--no-push specified)."
fi

