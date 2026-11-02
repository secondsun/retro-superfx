from __future__ import annotations

from pathlib import Path
import subprocess
import sys


TOOLS_DIR = Path(__file__).resolve().parents[2]
MESEN_MCP_DIR = TOOLS_DIR / "mesen-mcp"

for p in (str(TOOLS_DIR), str(MESEN_MCP_DIR)):
    if p not in sys.path:
        sys.path.insert(0, p)


from evals.outcome_grader import OutcomeGrader
from evals.worktree import WorktreeSandbox


def test_worktree_sandbox_creates_isolated_git_worktree_and_cleans_up_on_exit(
    tmp_path: Path,
) -> None:
    repo = tmp_path / "repo"
    repo.mkdir(parents=True)
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

    tracked = repo / "README.md"
    tracked.write_text("# Original\n", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=str(repo), check=True)
    subprocess.run(
        ["git", "commit", "-m", "initial commit"],
        cwd=str(repo),
        check=True,
        capture_output=True,
    )

    sandbox_path_captured: Path | None = None
    with WorktreeSandbox(repo) as sandbox_dir:
        sandbox_path_captured = sandbox_dir
        assert sandbox_dir.is_dir()
        assert sandbox_dir != repo.resolve()
        assert (sandbox_dir / "README.md").read_text(encoding="utf-8") == "# Original\n"

        # Mutate tracked file and add untracked file inside sandbox
        (sandbox_dir / "README.md").write_text("# Mutated in Sandbox\n", encoding="utf-8")
        (sandbox_dir / "new_test.sgs").write_text("function foo\nreturn\nendfunction\n", encoding="utf-8")

        # Parent repo must remain untouched
        assert tracked.read_text(encoding="utf-8") == "# Original\n"
        assert not (repo / "new_test.sgs").exists()

    # After context exit, temporary worktree directory is gone and git worktree list is clean
    assert sandbox_path_captured is not None
    assert not sandbox_path_captured.exists()
    assert tracked.read_text(encoding="utf-8") == "# Original\n"

    wt_proc = subprocess.run(
        ["git", "worktree", "list", "--porcelain"],
        cwd=str(repo),
        check=True,
        capture_output=True,
        text=True,
    )
    worktrees = [
        line.split(" ", 1)[1].strip()
        for line in wt_proc.stdout.splitlines()
        if line.startswith("worktree ")
    ]
    assert len(worktrees) == 1


def test_tool_sequence_contains_grader_verifies_relative_order_and_alternatives() -> None:
    grader = OutcomeGrader()
    spec = {
        "type": "tool_sequence_contains",
        "sequence": [
            "retro-superfx_lsp:sfx_audit_diagnostics",
            "retro-superfx_mesen:run_sfx_test|retro-superfx_mesen:assert_sfx_math",
        ],
    }

    # In-order trace with intermediate tools -> PASS
    in_order_trace = [
        {"tool": "write_file", "args": {"Path": "tests/foo/test.sgs"}},
        {"server": "retro-superfx_lsp", "tool": "sfx_audit_diagnostics", "args": {}},
        {"tool": "edit_file", "args": {"Path": "tests/foo/Makefile"}},
        {"server": "retro-superfx_mesen", "tool": "assert_sfx_math", "args": {}},
    ]
    res_pass = grader.evaluate_grader(spec, tool_calls=in_order_trace, code="")
    assert res_pass.passed is True

    # Out-of-order trace -> FAIL
    out_of_order_trace = [
        {"server": "retro-superfx_mesen", "tool": "run_sfx_test", "args": {}},
        {"server": "retro-superfx_lsp", "tool": "sfx_audit_diagnostics", "args": {}},
    ]
    res_order_fail = grader.evaluate_grader(spec, tool_calls=out_of_order_trace, code="")
    assert res_order_fail.passed is False
    assert "run_sfx_test" in res_order_fail.message or "assert_sfx_math" in res_order_fail.message

    # Missing second step -> FAIL
    missing_trace = [
        {"server": "retro-superfx_lsp", "tool": "sfx_audit_diagnostics", "args": {}},
    ]
    res_missing_fail = grader.evaluate_grader(spec, tool_calls=missing_trace, code="")
    assert res_missing_fail.passed is False


def test_file_exists_grader_checks_replay_files_and_workspace_disk(tmp_path: Path) -> None:
    (tmp_path / "tests" / "vec_dot").mkdir(parents=True)
    (tmp_path / "tests" / "vec_dot" / "Makefile").write_text("all:\n", encoding="utf-8")

    grader = OutcomeGrader(workspace_root=tmp_path)
    spec = {
        "type": "file_exists",
        "paths": [
            "tests/vec_dot/Makefile",
            "tests/vec_dot/test.sgs",
        ],
    }

    # Disk is missing test.sgs -> FAIL when no replay files override
    res_disk_missing = grader.evaluate_grader(
        spec, tool_calls=[], code="", sandbox_root=tmp_path
    )
    assert res_disk_missing.passed is False
    assert "tests/vec_dot/test.sgs" in res_disk_missing.message

    # Replay files list provides both -> PASS
    res_replay_pass = grader.evaluate_grader(
        spec,
        tool_calls=[],
        code="",
        files=["tests/vec_dot/Makefile", "tests/vec_dot/test.sgs"],
    )
    assert res_replay_pass.passed is True

    # Write test.sgs to disk -> PASS on disk check
    (tmp_path / "tests" / "vec_dot" / "test.sgs").write_text("nop\n", encoding="utf-8")
    res_disk_pass = grader.evaluate_grader(
        spec, tool_calls=[], code="", sandbox_root=tmp_path
    )
    assert res_disk_pass.passed is True


def test_mcp_verifier_grader_supports_replay_and_live_mcp_servers() -> None:
    workspace_root = Path(__file__).resolve().parents[3]
    grader = OutcomeGrader(workspace_root=workspace_root)

    # 1. Replay outcome verification with numeric comparison and nested dot-paths
    render_spec = {
        "type": "mcp_verifier",
        "server": "retro-superfx_mesen",
        "tool": "capture_sfx_render",
        "args": {"project": "snes-sfx-demo/X-GSU/IsoEngine"},
        "expect": {
            "status": "SUCCESS",
            "metrics.non_black_pixels": "> 0",
        },
    }
    pass_outcomes = {
        "capture_sfx_render": {
            "status": "SUCCESS",
            "metrics": {"non_black_pixels": 14200, "total_pixels": 35840},
        }
    }
    assert (
        grader.evaluate_grader(
            render_spec, tool_calls=[], code="", mcp_outcomes=pass_outcomes
        ).passed
        is True
    )

    fail_outcomes = {
        "capture_sfx_render": {
            "status": "SUCCESS",
            "metrics": {"non_black_pixels": 0, "total_pixels": 35840},
        }
    }
    res_black = grader.evaluate_grader(
        render_spec, tool_calls=[], code="", mcp_outcomes=fail_outcomes
    )
    assert res_black.passed is False
    assert "metrics.non_black_pixels" in res_black.message

    # 2. Live MCP invocation against RetroLspMCP (sfx_audit_diagnostics on clean_fx3.sgs)
    live_lsp_spec = {
        "type": "mcp_verifier",
        "server": "retro-superfx_lsp",
        "tool": "sfx_audit_diagnostics",
        "args": {"path": "tools/evals/fixtures/clean_fx3.sgs"},
        "expect": {
            "valid": True,
            "summary.errorCount": 0,
        },
    }
    res_live_lsp = grader.evaluate_grader(
        live_lsp_spec, tool_calls=[], code="", live_mcp=True
    )
    assert res_live_lsp.passed is True, res_live_lsp.message

    # 3. Live MCP invocation against MesenMCP (sfx_resolve_address on sample_project)
    sample_proj = str(
        workspace_root / "tools" / "mesen-mcp" / "tests" / "fixtures" / "sample_project"
    )
    live_mesen_spec = {
        "type": "mcp_verifier",
        "server": "retro-superfx_mesen",
        "tool": "sfx_resolve_address",
        "args": {"project": sample_proj, "symbol": "GSU_JOYPAD"},
        "expect": {
            "status": "SUCCESS",
            "found": True,
        },
    }
    res_live_mesen = grader.evaluate_grader(
        live_mesen_spec, tool_calls=[], code="", live_mcp=True
    )
    assert res_live_mesen.passed is True, res_live_mesen.message


def test_outcome_grader_executes_agent_cmd_inside_isolated_worktree_sandbox(
    tmp_path: Path,
) -> None:
    repo = tmp_path / "repo"
    repo.mkdir(parents=True)
    subprocess.run(["git", "init"], cwd=str(repo), check=True, capture_output=True)
    subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=str(repo), check=True)
    subprocess.run(["git", "config", "user.name", "Test User"], cwd=str(repo), check=True)
    (repo / "README.md").write_text("root\n", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=str(repo), check=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=str(repo), check=True, capture_output=True)

    agent_script = tmp_path / "sandbox_agent.py"
    agent_script.write_text(
        "import json, pathlib, sys\n"
        "pathlib.Path('created_in_worktree.sgs').write_text('function f\\nreturn\\nendfunction\\n')\n"
        "print(json.dumps({\n"
        "  'tool_calls': [\n"
        "    {'server': 'retro-superfx_lsp', 'tool': 'sfx_audit_diagnostics', 'args': {}},\n"
        "    {'server': 'retro-superfx_mesen', 'tool': 'run_sfx_test', 'args': {}}\n"
        "  ],\n"
        "  'code': 'function f\\nreturn\\nendfunction\\n'\n"
        "}))\n",
        encoding="utf-8",
    )

    case_spec = {
        "id": "worktree_agent_test",
        "tier": 2,
        "priority": "P1",
        "targets": ["README.md"],
        "prompt": "Create created_in_worktree.sgs",
        "graders": [
            {
                "type": "tool_sequence_contains",
                "sequence": ["sfx_audit_diagnostics", "run_sfx_test"],
            },
            {
                "type": "file_exists",
                "paths": ["created_in_worktree.sgs"],
            },
        ],
        "replay_fixtures": {},
    }

    grader = OutcomeGrader(workspace_root=repo)
    res = grader.grade_case(
        case_spec,
        verify_graders=False,
        agent_cmd=f'"{sys.executable}" "{agent_script}"',
    )
    assert res.passed is True, res.checks
    # Ensure created_in_worktree.sgs was NOT created in the parent repo!
    assert not (repo / "created_in_worktree.sgs").exists()


def test_all_three_real_tier2_yaml_cases_pass_verify_graders() -> None:
    workspace_root = Path(__file__).resolve().parents[3]
    grader = OutcomeGrader(workspace_root=workspace_root)
    report = grader.run_suite(verify_graders=True)

    expected_ids = {
        "p1_author_xgsu_function_with_test",
        "p1_scene_render_capture_gate",
        "p2_texture_atlas_pack_workflow",
    }
    actual_ids = {c.case_id for c in report.cases}
    assert actual_ids == expected_ids
    assert report.total_cases == 3
    assert report.passed_cases == 3
    assert report.passed is True
    for c in report.cases:
        assert c.passing_fixture_passed is True, f"{c.case_id} passing fixture failed: {c.checks}"
        assert c.failing_fixture_rejected is True, f"{c.case_id} failing fixture was not rejected"


def test_cli_tier2_and_tier_all_verify_graders_end_to_end(tmp_path: Path) -> None:
    import json

    workspace_root = Path(__file__).resolve().parents[3]
    script = workspace_root / "tools" / "run-evals.sh"
    baseline_path = tmp_path / "all_baseline.json"

    # Run --tier 2 --verify-graders
    proc_t2 = subprocess.run(
        [str(script), "--tier", "2", "--verify-graders", "--json"],
        cwd=str(workspace_root),
        capture_output=True,
        text=True,
        check=False,
    )
    assert proc_t2.returncode == 0, f"STDOUT: {proc_t2.stdout}\nSTDERR: {proc_t2.stderr}"
    payload_t2 = json.loads(proc_t2.stdout)
    assert payload_t2["passed"] is True
    assert payload_t2["tier2"]["passed"] is True
    assert payload_t2["tier2"]["total_cases"] == 3
    assert payload_t2["tier2"]["passed_cases"] == 3

    # Run --tier all --verify-graders --save-baseline
    proc_all = subprocess.run(
        [
            str(script),
            "--tier",
            "all",
            "--verify-graders",
            "--save-baseline",
            str(baseline_path),
            "--json",
        ],
        cwd=str(workspace_root),
        capture_output=True,
        text=True,
        check=False,
    )
    assert proc_all.returncode == 0, f"STDOUT: {proc_all.stdout}\nSTDERR: {proc_all.stderr}"
    payload_all = json.loads(proc_all.stdout)
    assert payload_all["passed"] is True
    assert payload_all["tier0"]["lint"]["passed"] is True
    assert payload_all["tier0"]["mcp_smoke"]["passed"] is True
    assert payload_all["tier1"]["passed"] is True
    assert payload_all["tier1"]["total_cases"] == 10
    assert payload_all["tier2"]["passed"] is True
    assert payload_all["tier2"]["total_cases"] == 3
    assert baseline_path.is_file()


def test_ci_workflow_and_harness_docs_exist_and_pass_tier0_lint() -> None:
    import yaml

    workspace_root = Path(__file__).resolve().parents[3]
    wf_path = workspace_root / ".github" / "workflows" / "agent-evals.yml"
    assert wf_path.is_file(), "Missing .github/workflows/agent-evals.yml"

    wf_data = yaml.safe_load(wf_path.read_text(encoding="utf-8"))
    assert isinstance(wf_data, dict)
    assert "jobs" in wf_data
    wf_text = wf_path.read_text(encoding="utf-8")
    assert "./tools/run-evals.sh" in wf_text
    assert "--verify-graders" in wf_text

    agents_text = (workspace_root / "AGENTS.md").read_text(encoding="utf-8")
    assert "./tools/run-evals.sh" in agents_text

    writing_skill_text = (
        workspace_root / ".agents" / "skills" / "writing-for-agents" / "SKILL.md"
    ).read_text(encoding="utf-8")
    assert "./tools/run-evals.sh --changed" in writing_skill_text
