---
name: rebase-submodules
description: >-
  Fetch and rebase Git submodules on the latest upstream commits from GitHub.
  Supports custom remotes (default: origin), custom branches (default: prototype),
  safe auto-stashing, and automatic fallback for submodules tracking master.
  Pauses and alerts on errors without rolling back completed submodules.
---

# Git Submodules Fetch & Rebase Skill

This skill documents how to fetch the latest commits from GitHub (or any specified Git remote) and rebase submodules onto their target upstream branches.

---

## 1. Automated Execution (Recommended)

A helper script is provided in `tools/` and `scripts/`:

```bash
# Fetch and rebase all submodules onto origin/prototype
./tools/rebase-submodules.sh
```

### CLI Options & Flags

| Flag | Description | Default |
| :--- | :--- | :--- |
| `-r`, `--remote NAME` | Git remote repository to fetch from | `origin` |
| `-b`, `--branch NAME` | Target branch to checkout and rebase onto | `prototype` |
| `-n`, `--dry-run` | Preview planned fetch and rebase actions without modifying git state | `false` |
| `--no-stash` | Disable automatic `git stash`; fail immediately if working tree is dirty | `false` (auto-stash enabled) |
| `--no-fallback` | Disallow fallback to active/default branch if target branch is missing upstream | `false` (fallback enabled) |
| `[SUBMODULE...]` | Optional list of specific submodules to target (e.g. `sfx-optimizer`) | All registered submodules |

---

## 2. Common Usage Scenarios

### A. Default Synchronize (origin / prototype)
Fetches from `origin` and rebases `prototype` on all submodules (falling back to `master` for `java-language-server`):
```bash
./tools/rebase-submodules.sh
```

### B. Dry-Run Preview
Inspect what commands will be executed without modifying any files or git refs:
```bash
./tools/rebase-submodules.sh --dry-run
```

### C. Updating an Alternate Branch
To update an alternate branch (e.g. `main` or a feature branch) across submodules:
```bash
# Rebase all submodules onto origin/main
./tools/rebase-submodules.sh --branch main

# Rebase a specific submodule onto an alternate branch
./tools/rebase-submodules.sh --branch feature-3d snes-sfx-demo
```

### D. Updating a Single Submodule
```bash
./tools/rebase-submodules.sh sfx-optimizer
```

### E. Specifying an Alternate Remote
```bash
./tools/rebase-submodules.sh --remote upstream --branch prototype
```

---

## 3. Error Handling & Invariants

> [!IMPORTANT]
> **No Rollbacks**: When an error or conflict occurs during a rebase, any previously completed submodules **MUST NOT** be rolled back. Successful fetches and rebases remain intact.

> [!WARNING]
> **Pause and Alert**: If the script exits with an error (exit code `1`):
> 1. The agent must **pause execution immediately**.
> 2. The agent must **alert the user** with:
>    - The name and path of the failed submodule.
>    - The cause of the failure (e.g., merge conflicts, uncommitted changes, or network error).
>    - Step-by-step resolution commands (`git status`, edit conflicts, `git add`, `git rebase --continue` or `git rebase --abort`).

### Handling Merge Conflicts
If a submodule encounters conflicts during rebase:
1. Navigate to the submodule:
   ```bash
   cd <submodule-dir>
   ```
2. View conflicting files:
   ```bash
   git status
   ```
3. Resolve conflict markers in code editor and stage the resolved files:
   ```bash
   git add <resolved-file>
   ```
4. Continue the rebase:
   ```bash
   git rebase --continue
   ```
   *(Or abort the rebase to restore pre-rebase state: `git rebase --abort`)*
5. If auto-stashed changes were saved, restore them once the rebase finishes:
   ```bash
   git stash pop
   ```

---

## 4. Branch Fallback Behavior

`java-language-server` tracks `master` upstream and does not maintain a `prototype` branch on GitHub.
By default:
- When `--branch prototype` is requested, the script recognizes that `origin/prototype` does not exist for `java-language-server`.
- It automatically falls back to its active tracking branch (`master`) and displays an informative notice.
- If `--no-fallback` is passed, the script will strictly require the branch to exist on the remote and exit with an error if missing.

---

## 5. Working Tree Stash Behavior

If a submodule contains modified or untracked files:
1. The script automatically runs `git stash push -u -m "rebase-submodules: auto-stash before rebase..."`.
2. The fetch and rebase execute cleanly on the clean working tree.
3. The script automatically pops the stash (`git stash pop`) after the rebase completes.
4. If `--no-stash` is set, the script will instead halt immediately and prompt the user to commit or stash manually.

---

## 6. Manual Step-by-Step Procedure

If performing the workflow manually without the script:

```bash
# 1. Enter the submodule directory
cd <submodule-dir>

# 2. Check for uncommitted changes and stash if necessary
git status --porcelain
git stash push -u -m "wip stash before rebase"

# 3. Fetch the remote
git fetch origin

# 4. Checkout the target branch (e.g. prototype)
git checkout prototype

# 5. Rebase onto upstream
git rebase origin/prototype

# 6. Restore stashed changes if any were saved
git stash pop
```

