from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import yaml

from evals.models import ContextBudgetReport, HarnessLintReport, LintIssue


DEFAULT_MAX_CONTEXT_TOKENS = 16000
CANONICAL_WORKSPACE_PREFIX = "/Users/summerspittman/Projects/retro-superfx/"

SKILL_ROOT_REL_PATHS = (
    ".agents/skills",
    ".agents/plugins/retro-superfx/skills",
    "snes-sfx-demo/.agents/skills",
    "retro-lsp/.agents/skills",
    "sfx-studio/.agents/skills",
    "sfx-studio/.agents/plugins/sfx-studio/skills",
)

AGENT_DOT_DIRS = (
    ".agents",
    "sfx-studio/.agents",
    "snes-sfx-demo/.agents",
    "snes-sfx-demo/X-GSU/.agents",
    "retro-lsp/.agents",
    "sfx-optimizer/.agents",
    "retro-common/.agents",
    "java-language-server/.agents",
)

_FRONTMATTER_RE = re.compile(r"^---\s*\n(.*?)\n---\s*(?:\n|$)", re.DOTALL)
_FENCED_BLOCK_RE = re.compile(r"```.*?```", re.DOTALL)
_INLINE_CODE_LINK_RE = re.compile(r"`[^`\n]*?\[[^\]]+\]\([^)]+\)[^`\n]*?`")
_MARKDOWN_LINK_RE = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
_HARDCODED_LUA_PATH_RE = re.compile(r"""["']/(?:Users|home)/[^"'\n]+["']""")
_HARDCODED_USER_PATH_RE = re.compile(
    r"""(?i)(?:/(?:Users|home)/[a-zA-Z0-9_-]+|[a-zA-Z]:\\Users\\[a-zA-Z0-9_-]+)"""
)
_IGNORED_SCHEMES = ("http://", "https://", "conversation://", "mailto:", "#")
_IGNORED_FILENAMES = {".DS_Store"}


class HarnessLinter:
    """Static Tier 0 contract linter for the retro-superfx agentic harness."""

    def lint_workspace(
        self,
        workspace_root: Path | str,
        max_context_tokens: int = DEFAULT_MAX_CONTEXT_TOKENS,
    ) -> HarnessLintReport:
        root = Path(workspace_root).resolve()
        issues: list[LintIssue] = []

        checked_symlinks = self._check_symlinks(root, issues)
        self._check_skill_parity(root, issues)
        checked_skills, context_budget = self._check_frontmatter_and_budget(
            root, max_context_tokens, issues
        )
        checked_links = self._check_markdown_links(root, issues)
        self._check_test_lua_hardcoded_paths(root, issues)
        self._check_workspace_hardcoded_paths(root, issues)

        return HarnessLintReport(
            passed=len(issues) == 0,
            issues=issues,
            context_budget=context_budget,
            checked_skills=checked_skills,
            checked_symlinks=checked_symlinks,
            checked_links=checked_links,
        )

    def _check_symlinks(self, root: Path, issues: list[LintIssue]) -> int:
        checked = 0
        for rel_dir in AGENT_DOT_DIRS:
            base = root / rel_dir
            if not base.exists():
                continue
            for dirpath, dirnames, filenames in os.walk(base, followlinks=False):
                current_dir = Path(dirpath)
                for entry in sorted(dirnames + filenames):
                    if entry in _IGNORED_FILENAMES:
                        continue
                    candidate = current_dir / entry
                    if candidate.is_symlink():
                        checked += 1
                        target_raw = os.readlink(candidate)
                        if not candidate.exists():
                            rel_path = str(candidate.relative_to(root))
                            issues.append(
                                LintIssue(
                                    code="BROKEN_SYMLINK",
                                    message=(
                                        f"Broken symlink '{rel_path}' -> '{target_raw}' "
                                        f"(target does not exist)"
                                    ),
                                    path=rel_path,
                                )
                            )
        return checked

    def _check_skill_parity(self, root: Path, issues: list[LintIssue]) -> None:
        skills_by_name: dict[str, list[Path]] = {}
        for rel_skill_root in SKILL_ROOT_REL_PATHS:
            sr = root / rel_skill_root
            if not sr.is_dir():
                continue
            for child in sorted(sr.iterdir()):
                if child.is_dir() and not child.name.startswith("."):
                    skills_by_name.setdefault(child.name, []).append(child)

        for skill_name, raw_skill_dirs in sorted(skills_by_name.items()):
            seen_resolved: set[Path] = set()
            skill_dirs: list[Path] = []
            for sd in raw_skill_dirs:
                resolved_sd = sd.resolve()
                if resolved_sd not in seen_resolved:
                    seen_resolved.add(resolved_sd)
                    skill_dirs.append(sd)

            if len(skill_dirs) < 2:
                continue

            files_per_copy: dict[Path, dict[str, bytes | None]] = {}
            all_rel_files: set[str] = set()
            for sdir in skill_dirs:
                file_map: dict[str, bytes | None] = {}
                for dirpath, _, filenames in os.walk(sdir, followlinks=False):
                    cdir = Path(dirpath)
                    if "__pycache__" in cdir.parts:
                        continue
                    for fn in sorted(filenames):
                        if fn in _IGNORED_FILENAMES:
                            continue
                        fp = cdir / fn
                        rel_fp = str(fp.relative_to(sdir))
                        all_rel_files.add(rel_fp)
                        if fp.exists():
                            file_map[rel_fp] = hashlib.sha256(fp.read_bytes()).digest()
                        else:
                            file_map[rel_fp] = None
                files_per_copy[sdir] = file_map

            for rel_fp in sorted(all_rel_files):
                present_in = [
                    sdir for sdir in skill_dirs if rel_fp in files_per_copy[sdir]
                ]
                if len(present_in) != len(skill_dirs):
                    missing_in = [
                        str(sdir.relative_to(root))
                        for sdir in skill_dirs
                        if rel_fp not in files_per_copy[sdir]
                    ]
                    issues.append(
                        LintIssue(
                            code="SKILL_PARITY_DRIFT",
                            message=(
                                f"Shared skill '{skill_name}' file '{rel_fp}' is missing in: "
                                f"{', '.join(missing_in)}"
                            ),
                            path=rel_fp,
                        )
                    )
                    continue

                digests = {files_per_copy[sdir][rel_fp] for sdir in skill_dirs}
                if None in digests or len(digests) > 1:
                    locations = ", ".join(
                        str((sdir / rel_fp).relative_to(root)) for sdir in skill_dirs
                    )
                    issues.append(
                        LintIssue(
                            code="SKILL_PARITY_DRIFT",
                            message=(
                                f"Shared skill '{skill_name}' file '{rel_fp}' differs across copies: "
                                f"{locations}"
                            ),
                            path=str((skill_dirs[0] / rel_fp).relative_to(root)),
                        )
                    )

    def _collect_skill_files(self, root: Path) -> list[Path]:
        skill_files: list[Path] = []
        for rel_dir in AGENT_DOT_DIRS:
            base = root / rel_dir
            if base.is_dir():
                skill_files.extend(sorted(base.glob("**/SKILL.md")))
        return skill_files

    def _collect_always_loaded_rule_files(self, root: Path) -> list[Path]:
        files: list[Path] = []
        agents_md = root / "AGENTS.md"
        if agents_md.is_file():
            files.append(agents_md)
        for rel_glob in (
            ".agents/rules/*.md",
            ".agents/plugins/retro-superfx/rules/*.md",
        ):
            files.extend(sorted(root.glob(rel_glob)))
        return files

    def _collect_subproject_doc_files(self, root: Path) -> list[Path]:
        files: list[Path] = []
        for rel_glob in (
            "sfx-studio/AGENTS.md",
            "snes-sfx-demo/AGENTS.md",
            "snes-sfx-demo/X-GSU/AGENTS.md",
            "retro-lsp/AGENTS.md",
            "retro-lsp/.agents/rules/*.md",
            "sfx-optimizer/AGENTS.md",
            "retro-common/AGENTS.md",
            "java-language-server/AGENTS.md",
        ):
            files.extend(sorted(root.glob(rel_glob)))
        return files

    def _check_frontmatter_and_budget(
        self,
        root: Path,
        max_context_tokens: int,
        issues: list[LintIssue],
    ) -> tuple[int, ContextBudgetReport]:
        skill_files = self._collect_skill_files(root)
        skill_desc_chars = 0
        model_invoked_count = 0
        manual_only_count = 0
        seen_resolved_skills: set[Path] = set()

        for sf in skill_files:
            rel_sf = str(sf.relative_to(root))
            text = sf.read_text(encoding="utf-8")
            match = _FRONTMATTER_RE.match(text)
            if not match:
                issues.append(
                    LintIssue(
                        code="INVALID_FRONTMATTER",
                        message=f"Missing YAML frontmatter block in '{rel_sf}'",
                        path=rel_sf,
                    )
                )
                continue

            try:
                meta = yaml.safe_load(match.group(1))
            except yaml.YAMLError as exc:
                issues.append(
                    LintIssue(
                        code="INVALID_FRONTMATTER",
                        message=f"Malformed YAML frontmatter in '{rel_sf}': {exc}",
                        path=rel_sf,
                    )
                )
                continue

            if not isinstance(meta, dict):
                issues.append(
                    LintIssue(
                        code="INVALID_FRONTMATTER",
                        message=f"YAML frontmatter in '{rel_sf}' must be a mapping",
                        path=rel_sf,
                    )
                )
                continue

            name = meta.get("name")
            description = meta.get("description")
            if not isinstance(name, str) or not name.strip():
                issues.append(
                    LintIssue(
                        code="INVALID_FRONTMATTER",
                        message=f"Missing or empty 'name' in frontmatter of '{rel_sf}'",
                        path=rel_sf,
                    )
                )
            if not isinstance(description, str) or not description.strip():
                issues.append(
                    LintIssue(
                        code="INVALID_FRONTMATTER",
                        message=f"Missing or empty 'description' in frontmatter of '{rel_sf}'",
                        path=rel_sf,
                    )
                )

            # Deduplicate by resolved realpath for context budget calculation
            resolved_sf = sf.resolve()
            if resolved_sf in seen_resolved_skills:
                continue
            seen_resolved_skills.add(resolved_sf)

            disable_model = bool(meta.get("disable-model-invocation", False))
            if disable_model:
                manual_only_count += 1
            else:
                model_invoked_count += 1
                if isinstance(name, str) and isinstance(description, str):
                    skill_desc_chars += len(name.strip()) + len(description.strip())

        rule_files = self._collect_always_loaded_rule_files(root)
        always_loaded_file_chars = sum(
            len(rf.read_text(encoding="utf-8")) for rf in rule_files if rf.is_file()
        )
        total_chars = always_loaded_file_chars + skill_desc_chars
        estimated_tokens = total_chars // 4

        budget = ContextBudgetReport(
            always_loaded_file_chars=always_loaded_file_chars,
            skill_description_chars=skill_desc_chars,
            total_chars=total_chars,
            estimated_tokens=estimated_tokens,
            max_tokens=max_context_tokens,
            model_invoked_skill_count=model_invoked_count,
            manual_only_skill_count=manual_only_count,
        )
        if not budget.within_budget:
            issues.append(
                LintIssue(
                    code="CONTEXT_BUDGET_EXCEEDED",
                    message=(
                        f"Always-loaded context budget exceeded: estimated {estimated_tokens} tokens "
                        f"({total_chars} chars) > ceiling of {max_context_tokens} tokens"
                    ),
                    path="AGENTS.md",
                )
            )
        return len(skill_files), budget

    def _check_markdown_links(self, root: Path, issues: list[LintIssue]) -> int:
        files_to_scan = (
            self._collect_always_loaded_rule_files(root)
            + self._collect_subproject_doc_files(root)
            + self._collect_skill_files(root)
        )
        checked_links = 0
        for md_file in files_to_scan:
            if not md_file.is_file():
                continue
            rel_md = str(md_file.relative_to(root))
            raw_text = md_file.read_text(encoding="utf-8")
            stripped_text = _FENCED_BLOCK_RE.sub("", raw_text)
            stripped_text = _INLINE_CODE_LINK_RE.sub("", stripped_text)

            for match in _MARKDOWN_LINK_RE.finditer(stripped_text):
                target = match.group(1).strip()
                if not target or any(target.startswith(s) for s in _IGNORED_SCHEMES):
                    continue
                if "<" in target or ">" in target:
                    continue

                path_part = target.split("#", 1)[0].strip()
                if not path_part:
                    continue

                checked_links += 1
                resolved = self._resolve_link_target(root, md_file, path_part)
                if not resolved.exists():
                    issues.append(
                        LintIssue(
                            code="BROKEN_LINK",
                            message=(
                                f"Broken markdown link '{target}' in '{rel_md}' "
                                f"(resolved to '{resolved}')"
                            ),
                            path=rel_md,
                        )
                    )
        return checked_links

    def _resolve_link_target(self, root: Path, md_file: Path, path_part: str) -> Path:
        if path_part.startswith("file://"):
            fs_path_str = path_part[len("file://") :]
            if fs_path_str.startswith(CANONICAL_WORKSPACE_PREFIX):
                rel_suffix = fs_path_str[len(CANONICAL_WORKSPACE_PREFIX) :]
                candidate = root / rel_suffix
                if candidate.exists():
                    return candidate
            return Path(fs_path_str)

        rel_to_file = md_file.parent / path_part
        if rel_to_file.exists():
            return rel_to_file
        rel_to_root = root / path_part
        if rel_to_root.exists():
            return rel_to_root
        return rel_to_file

    def _check_test_lua_hardcoded_paths(
        self, root: Path, issues: list[LintIssue]
    ) -> None:
        demo_root = root / "snes-sfx-demo"
        if not demo_root.is_dir():
            return
        for lua_file in sorted(demo_root.glob("**/test.lua")):
            if not lua_file.is_file():
                continue
            rel_lua = str(lua_file.relative_to(root))
            for lineno, line in enumerate(
                lua_file.read_text(encoding="utf-8").splitlines(), start=1
            ):
                code_part = line.split("--", 1)[0]
                match = _HARDCODED_LUA_PATH_RE.search(code_part)
                if match:
                    issues.append(
                        LintIssue(
                            code="HARDCODED_LUA_PATH",
                            message=(
                                f"Hardcoded user home path {match.group(0)} in "
                                f"'{rel_lua}:{lineno}'; use script_dir relative paths instead"
                            ),
                            path=rel_lua,
                        )
                    )

    def _check_workspace_hardcoded_paths(
        self, root: Path, issues: list[LintIssue]
    ) -> None:
        """Scan workspace scripts, configs, tools, and docs for hardcoded user home paths."""
        excluded_path_parts = {
            ".git",
            "node_modules",
            ".venv",
            "libSFX/tools",
            "target",
            "dist",
            ".idea",
            "__pycache__",
            "cases",
            "fixtures",
        }
        valid_extensions = {
            ".sh",
            ".bat",
            ".cmd",
            ".py",
            ".kt",
            ".java",
            ".ts",
            ".js",
            ".mjs",
            ".md",
            ".yaml",
            ".yml",
            ".json",
            ".cfg",
            ".s",
            ".sgs",
            ".i",
            ".inc",
        }

        # Directories and root files to inspect
        scan_roots = [
            root / "tools",
            root / ".agents",
            root / "README.md",
            root / "AGENTS.md",
            root / "CODING_STANDARDS.md",
            root / "java-language-server" / "AGENTS.md",
            root / "retro-common" / "AGENTS.md",
            root / "sfx-optimizer" / "AGENTS.md",
            root / "retro-lsp" / "AGENTS.md",
            root / "retro-lsp" / "scripts",
            root / "sfx-studio" / "AGENTS.md",
            root / "snes-sfx-demo" / "README.md",
            root / "snes-sfx-demo" / "AGENTS.md",
            root / "snes-sfx-demo" / "X-GSU" / "AGENTS.md",
        ]

        files_to_scan: list[Path] = []
        for sr in scan_roots:
            if not sr.exists():
                continue
            if sr.is_file():
                files_to_scan.append(sr)
            elif sr.is_dir():
                for dirpath, _, filenames in os.walk(sr):
                    dp = Path(dirpath)
                    if any(part in dp.parts for part in excluded_path_parts):
                        continue
                    for fn in filenames:
                        fp = dp / fn
                        if fp.suffix.lower() in valid_extensions:
                            # Skip test files designed to test linting/mocking
                            rel = str(fp.relative_to(root))
                            if (
                                "test_harness_lint.py" in rel
                                or "test_trace_grader.py" in rel
                                or "mocking.md" in rel
                                or rel.startswith("tools/run-evals.sh")
                            ):
                                continue
                            files_to_scan.append(fp)

        for file_path in sorted(set(files_to_scan)):
            rel_file = str(file_path.relative_to(root))
            try:
                content = file_path.read_text(encoding="utf-8", errors="ignore")
            except Exception:
                continue

            for lineno, line in enumerate(content.splitlines(), start=1):
                # Ignore CANONICAL_WORKSPACE_PREFIX definition in harness_lint.py itself
                if "CANONICAL_WORKSPACE_PREFIX" in line:
                    continue
                match = _HARDCODED_USER_PATH_RE.search(line)
                if match:
                    issues.append(
                        LintIssue(
                            code="HARDCODED_USER_PATH",
                            message=(
                                f"Hardcoded user home path '{match.group(0)}' in "
                                f"'{rel_file}:{lineno}'; derive path dynamically at runtime or use relative paths"
                            ),
                            path=rel_file,
                        )
                    )



