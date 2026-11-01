from __future__ import annotations

import json
from pathlib import Path
import subprocess
import time

import pytest

from evals.harness_lint import HarnessLinter


WORKSPACE_ROOT = Path(__file__).resolve().parents[3]


def _create_minimal_workspace(root: Path) -> Path:
    """Create a clean minimal workspace fixture satisfying all Tier 0 harness contracts."""
    root.mkdir(parents=True, exist_ok=True)
    (root / "AGENTS.md").write_text(
        "# AGENTS.md\n\nSee [rules](.agents/rules/sfx-assembly-rules.md).\n",
        encoding="utf-8",
    )
    rules_dir = root / ".agents" / "rules"
    rules_dir.mkdir(parents=True, exist_ok=True)
    (rules_dir / "sfx-assembly-rules.md").write_text(
        "# SuperFX Assembly Rules\n\nSee [AGENTS.md](../../AGENTS.md).\n",
        encoding="utf-8",
    )

    plugin_rules_dir = root / ".agents" / "plugins" / "retro-superfx" / "rules"
    plugin_rules_dir.mkdir(parents=True, exist_ok=True)
    (plugin_rules_dir / "AGENTS.md").write_text(
        f"# Plugin Rules\n\nSee [root](file://{root / 'AGENTS.md'}#L1-L3).\n",
        encoding="utf-8",
    )

    tools_dir = root / "tools"
    tools_dir.mkdir(parents=True, exist_ok=True)
    helper_script = tools_dir / "helper.sh"
    helper_script.write_text("#!/usr/bin/env bash\necho ok\n", encoding="utf-8")

    skill_content = (
        "---\n"
        "name: sample-skill\n"
        "description: Sample skill for testing sync parity.\n"
        "---\n\n"
        "# Sample Skill\n\n"
        "Run [helper.sh](scripts/helper.sh).\n"
    )
    for skill_root in [
        root / ".agents" / "skills" / "sample-skill",
        root / ".agents" / "plugins" / "retro-superfx" / "skills" / "sample-skill",
    ]:
        (skill_root / "scripts").mkdir(parents=True, exist_ok=True)
        (skill_root / "SKILL.md").write_text(skill_content, encoding="utf-8")

    # Symlink in .agents/skills/sample-skill/scripts/helper.sh -> ../../../../tools/helper.sh
    symlink_path = root / ".agents" / "skills" / "sample-skill" / "scripts" / "helper.sh"
    symlink_path.symlink_to("../../../../tools/helper.sh")

    # Identical regular file in plugin skill
    plugin_script = (
        root
        / ".agents"
        / "plugins"
        / "retro-superfx"
        / "skills"
        / "sample-skill"
        / "scripts"
        / "helper.sh"
    )
    plugin_script.write_text("#!/usr/bin/env bash\necho ok\n", encoding="utf-8")

    return root


def test_clean_minimal_workspace_passes(tmp_path: Path) -> None:
    ws = _create_minimal_workspace(tmp_path)
    linter = HarnessLinter()
    report = linter.lint_workspace(ws)

    assert report.passed is True
    assert report.issues == []
    assert report.checked_skills >= 2
    assert report.checked_symlinks == 1
    assert report.checked_links >= 3
    assert report.context_budget.estimated_tokens > 0
    assert report.context_budget.estimated_tokens <= report.context_budget.max_tokens


def test_detects_drifted_duplicate_skill_file(tmp_path: Path) -> None:
    ws = _create_minimal_workspace(tmp_path)
    drifted = (
        ws
        / ".agents"
        / "plugins"
        / "retro-superfx"
        / "skills"
        / "sample-skill"
        / "SKILL.md"
    )
    drifted.write_text(
        "---\nname: sample-skill\ndescription: Drifted description.\n---\n# Drifted\n",
        encoding="utf-8",
    )

    report = HarnessLinter().lint_workspace(ws)
    assert report.passed is False
    codes = [issue.code for issue in report.issues]
    assert "SKILL_PARITY_DRIFT" in codes


def test_detects_missing_file_in_shared_skill(tmp_path: Path) -> None:
    ws = _create_minimal_workspace(tmp_path)
    extra_file = (
        ws
        / "snes-sfx-demo"
        / ".agents"
        / "skills"
        / "sample-skill"
        / "SKILL.md"
    )
    extra_file.parent.mkdir(parents=True, exist_ok=True)
    extra_file.write_text(
        "---\nname: sample-skill\ndescription: Sample skill for testing sync parity.\n---\n",
        encoding="utf-8",
    )

    report = HarnessLinter().lint_workspace(ws)
    assert report.passed is False
    codes = [issue.code for issue in report.issues]
    assert "SKILL_PARITY_DRIFT" in codes


def test_detects_broken_symlink(tmp_path: Path) -> None:
    ws = _create_minimal_workspace(tmp_path)
    broken_link = ws / ".agents" / "skills" / "sample-skill" / "scripts" / "helper.sh"
    broken_link.unlink()
    broken_link.symlink_to("../../../tools/helper.sh")  # off by one level

    report = HarnessLinter().lint_workspace(ws)
    assert report.passed is False
    codes = [issue.code for issue in report.issues]
    assert "BROKEN_SYMLINK" in codes


def test_detects_missing_frontmatter_fields(tmp_path: Path) -> None:
    ws = _create_minimal_workspace(tmp_path)
    bad_skill_dir = ws / ".agents" / "skills" / "bad-frontmatter"
    bad_skill_dir.mkdir(parents=True, exist_ok=True)
    (bad_skill_dir / "SKILL.md").write_text(
        "---\nname: bad-frontmatter\n---\n# Missing description\n",
        encoding="utf-8",
    )

    report = HarnessLinter().lint_workspace(ws)
    assert report.passed is False
    codes = [issue.code for issue in report.issues]
    assert "INVALID_FRONTMATTER" in codes


def test_detects_broken_markdown_and_file_links(tmp_path: Path) -> None:
    ws = _create_minimal_workspace(tmp_path)
    (ws / "AGENTS.md").write_text(
        "# AGENTS.md\n\n"
        "Broken relative: [missing](.agents/rules/does-not-exist.md)\n"
        f"Broken file URI: [missing-uri](file://{ws / 'missing_target.md'}#L10)\n",
        encoding="utf-8",
    )

    report = HarnessLinter().lint_workspace(ws)
    assert report.passed is False
    broken_issues = [i for i in report.issues if i.code == "BROKEN_LINK"]
    assert len(broken_issues) == 2


def test_detects_context_budget_overrun_and_respects_disable_model_invocation(
    tmp_path: Path,
) -> None:
    ws = _create_minimal_workspace(tmp_path)
    # A user-only skill with disable-model-invocation: true should not count toward model-invoked skill budget
    manual_skill = ws / ".agents" / "skills" / "manual-only"
    manual_skill.mkdir(parents=True, exist_ok=True)
    (manual_skill / "SKILL.md").write_text(
        "---\n"
        "name: manual-only\n"
        "description: " + ("A" * 1200) + "\n"
        "disable-model-invocation: true\n"
        "---\n# Manual skill\n",
        encoding="utf-8",
    )

    baseline = HarnessLinter().lint_workspace(ws, max_context_tokens=500)
    assert baseline.passed is True

    # Now set max_context_tokens below the actual always-loaded token count
    overrun = HarnessLinter().lint_workspace(ws, max_context_tokens=5)
    assert overrun.passed is False
    codes = [issue.code for issue in overrun.issues]
    assert "CONTEXT_BUDGET_EXCEEDED" in codes


def test_real_workspace_passes_under_two_seconds() -> None:
    start = time.monotonic()
    report = HarnessLinter().lint_workspace(WORKSPACE_ROOT)
    elapsed = time.monotonic() - start

    assert report.passed is True, f"Real workspace failed lint: {report.issues}"
    assert elapsed < 2.0, f"Lint took {elapsed:.2f}s (expected < 2.0s)"


def test_run_evals_cli_tier0_lint_passes_on_real_workspace() -> None:
    script = WORKSPACE_ROOT / "tools" / "run-evals.sh"
    start = time.monotonic()
    proc = subprocess.run(
        [str(script), "--tier", "0", "--suite", "lint", "--json"],
        cwd=str(WORKSPACE_ROOT),
        capture_output=True,
        text=True,
        check=False,
    )
    elapsed = time.monotonic() - start

    assert proc.returncode == 0, f"STDOUT: {proc.stdout}\nSTDERR: {proc.stderr}"
    assert elapsed < 2.0, f"CLI took {elapsed:.2f}s (expected < 2.0s)"
    payload = json.loads(proc.stdout)
    assert payload["passed"] is True
    assert payload["tier0"]["lint"]["passed"] is True


def test_save_and_compare_baseline(tmp_path: Path) -> None:
    ws = _create_minimal_workspace(tmp_path / "ws")
    baseline_path = tmp_path / "baseline.json"
    script = WORKSPACE_ROOT / "tools" / "run-evals.sh"

    save_proc = subprocess.run(
        [
            str(script),
            "--tier",
            "0",
            "--suite",
            "lint",
            "--workspace-root",
            str(ws),
            "--save-baseline",
            str(baseline_path),
            "--json",
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    assert save_proc.returncode == 0
    assert baseline_path.is_file()

    # Add extra text to AGENTS.md and compare against baseline
    (ws / "AGENTS.md").write_text(
        (ws / "AGENTS.md").read_text(encoding="utf-8") + ("\nExtra context " * 20),
        encoding="utf-8",
    )
    cmp_proc = subprocess.run(
        [
            str(script),
            "--tier",
            "0",
            "--suite",
            "lint",
            "--workspace-root",
            str(ws),
            "--compare",
            str(baseline_path),
            "--json",
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    assert cmp_proc.returncode == 0
    cmp_payload = json.loads(cmp_proc.stdout)
    assert cmp_payload["comparison"]["token_delta"] > 0


def test_pre_commit_hook_triggers_tier0_lint_on_staged_harness_files(
    tmp_path: Path,
) -> None:
    repo = _create_minimal_workspace(tmp_path / "repo")
    subprocess.run(["git", "init"], cwd=str(repo), check=True, capture_output=True)
    subprocess.run(
        ["git", "config", "user.email", "test@example.com"],
        cwd=str(repo),
        check=True,
    )
    subprocess.run(
        ["git", "config", "user.name", "Test User"],
        cwd=str(repo),
        check=True,
    )

    # Wire tools/run-evals.sh in the temporary repo to invoke our real evals package
    run_evals_script = repo / "tools" / "run-evals.sh"
    python_bin = WORKSPACE_ROOT / "tools" / "mesen-mcp" / ".venv" / "bin" / "python"
    run_evals_script.write_text(
        "#!/usr/bin/env bash\n"
        "set -euo pipefail\n"
        f'export PYTHONPATH="{WORKSPACE_ROOT / "tools"}:{WORKSPACE_ROOT / "tools/mesen-mcp"}"\n'
        f'exec "{python_bin}" -m evals.cli --workspace-root "{repo}" "$@"\n',
        encoding="utf-8",
    )
    run_evals_script.chmod(0o755)

    hook_src = WORKSPACE_ROOT / "tools" / "git-hooks" / "pre-commit"

    # Stage clean AGENTS.md -> pre-commit must pass
    subprocess.run(["git", "add", "AGENTS.md", ".agents"], cwd=str(repo), check=True)
    clean_hook = subprocess.run(
        ["bash", str(hook_src)],
        cwd=str(repo),
        capture_output=True,
        text=True,
        check=False,
    )
    assert clean_hook.returncode == 0, f"STDERR: {clean_hook.stderr}"
    assert "[pre-commit] harness tier 0" in clean_hook.stdout

    # Now introduce a broken markdown link in AGENTS.md and stage it -> pre-commit must fail
    (repo / "AGENTS.md").write_text(
        "# Broken\n\nSee [nope](.agents/rules/missing.md)\n",
        encoding="utf-8",
    )
    subprocess.run(["git", "add", "AGENTS.md"], cwd=str(repo), check=True)
    bad_hook = subprocess.run(
        ["bash", str(hook_src)],
        cwd=str(repo),
        capture_output=True,
        text=True,
        check=False,
    )
    assert bad_hook.returncode != 0

