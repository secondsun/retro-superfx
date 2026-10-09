---
name: update-submodules
description: >-
  Stage, commit, and push updated Git submodules with new commits to origin on the active branch.
  Use when asked to 'git add' submodules with commits, update submodule references, commit submodule pointers,
  or push submodule updates to origin.
---

# Git Submodules Update & Push Skill

This skill documents how to identify Git submodules containing new commits, stage their updated pointers (`git add`), generate descriptive commit messages (`"updating submodule[s] $submodule_name"`), and push them to the active branch on `origin`.

---

## 1. Automated Execution (Recommended)

A helper script is provided in `tools/` and `scripts/`:

```bash
# Update, commit, and push all submodules with commits
./tools/update-submodules.sh
```

### Common Options & Flags

| Flag | Description |
| :--- | :--- |
| `-n`, `--dry-run` | Preview actions without making any Git modifications. |
| `--no-push` | Stage and commit locally, but do not push to origin. |
| `-s`, `--split` | Create an individual commit per submodule rather than one batched commit. |
| `-b`, `--brackets` | Use literal `"updating submodule[s] <names>"` format. |
| `-m`, `--message MSG` | Override the generated commit message. |
| `-r`, `--remote NAME` | Override the target remote (default: `origin`). |
| `--branch NAME` | Override the target branch (default: active branch). |
| `[SUBMODULE...]` | Target only specific submodules (e.g. `sfx-optimizer`). |

### Examples

```bash
# Preview what would be staged, committed, and pushed
./tools/update-submodules.sh --dry-run

# Commit each updated submodule in its own separate commit
./tools/update-submodules.sh --split

# Update only a single specific submodule
./tools/update-submodules.sh sfx-optimizer

# Use literal bracket syntax for batch commit
./tools/update-submodules.sh --brackets
```

---

## 2. Commit Message Rules

- **Single Submodule**: `"updating submodule <name>"` (e.g., `updating submodule sfx-optimizer`)
- **Multiple Submodules (Batched)**: `"updating submodules <name1>, <name2>"` (e.g., `updating submodules sfx-optimizer, snes-sfx-demo`)
- **Bracket Notation (`-b`)**: `"updating submodule[s] <names>"`
- **Split Commits (`-s`)**: Each submodule receives its own commit with `"updating submodule <name>"`.

---

## 3. Manual Step-by-Step Procedure

If performing the workflow manually without the script:

### Step 1: Detect Submodules with New Commits

Inspect submodule statuses to find entries prefixed with `+` (meaning the checked-out commit differs from the index):
```bash
git submodule status
```

Or extract the submodule names directly:
```bash
git submodule status | grep '^[+]' | awk '{print $2}'
```

### Step 2: Stage the Modified Submodules

Stage only the submodule paths:
```bash
git add <submodule_path>
# E.g.:
git add sfx-optimizer snes-sfx-demo
```

### Step 3: Create the Commit

```bash
# If single submodule:
git commit -m "updating submodule <submodule_name>" -- <submodule_path>

# If multiple submodules:
git commit -m "updating submodules <submodule1>, <submodule2>" -- <submodule1> <submodule2>
```

### Step 4: Push to the Active Branch on Origin

Determine the active branch and push:
```bash
ACTIVE_BRANCH=$(git rev-parse --abbrev-ref HEAD)
git push origin "$ACTIVE_BRANCH"
```

---

## 4. Safety Checks & Hygiene

1. **Submodule Working Trees**: Ensure working directories inside the submodules are clean. If a submodule has uncommitted edits, commit them inside the submodule repository before updating the parent gitlink.
2. **Upstream Submodule Commits**: Verify that commits made inside submodules have already been pushed to their respective remotes, avoiding broken references for collaborators.
3. **Detached HEAD in Parent**: Do not push if the parent repository is in a detached HEAD state. Switch to the target branch first (`git checkout <branch>`).

