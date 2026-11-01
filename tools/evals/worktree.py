from __future__ import annotations

from pathlib import Path
import shutil
import subprocess
import tempfile
from typing import Any


class WorktreeSandbox:
    """Context manager providing an isolated temporary Git worktree (or directory snapshot)."""

    def __init__(self, workspace_root: Path | str) -> None:
        self.workspace_root = Path(workspace_root).resolve()
        self._sandbox_dir: Path | None = None
        self._is_git_worktree = False

    def _has_git_head(self) -> bool:
        if not (self.workspace_root / ".git").exists():
            return False
        proc = subprocess.run(
            ["git", "rev-parse", "--verify", "HEAD"],
            cwd=str(self.workspace_root),
            capture_output=True,
            text=True,
            check=False,
        )
        return proc.returncode == 0

    def __enter__(self) -> Path:
        tmp_path = Path(tempfile.mkdtemp(prefix="retro-superfx-eval-wt-")).resolve()
        self._sandbox_dir = tmp_path

        if self._has_git_head():
            # Remove the empty directory first so git worktree add can create it cleanly,
            # or pass the empty dir directly to git worktree add --detach
            proc = subprocess.run(
                ["git", "worktree", "add", "--detach", str(tmp_path), "HEAD"],
                cwd=str(self.workspace_root),
                capture_output=True,
                text=True,
                check=False,
            )
            if proc.returncode == 0:
                self._is_git_worktree = True
                return tmp_path

        # Fallback for non-git directories in unit tests: lightweight directory copy
        if tmp_path.exists():
            shutil.rmtree(tmp_path, ignore_errors=True)
        shutil.copytree(
            self.workspace_root,
            tmp_path,
            symlinks=True,
            ignore=shutil.ignore_patterns(".git", "__pycache__", ".pytest_cache"),
        )
        self._is_git_worktree = False
        return tmp_path

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        if self._sandbox_dir is None:
            return
        try:
            if self._is_git_worktree:
                subprocess.run(
                    ["git", "worktree", "remove", "--force", str(self._sandbox_dir)],
                    cwd=str(self.workspace_root),
                    capture_output=True,
                    text=True,
                    check=False,
                )
                subprocess.run(
                    ["git", "worktree", "prune"],
                    cwd=str(self.workspace_root),
                    capture_output=True,
                    text=True,
                    check=False,
                )
        finally:
            if self._sandbox_dir.exists():
                shutil.rmtree(self._sandbox_dir, ignore_errors=True)
            self._sandbox_dir = None
            self._is_git_worktree = False
