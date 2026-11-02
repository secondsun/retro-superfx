from __future__ import annotations

from pathlib import Path
import sys


TOOLS_DIR = Path(__file__).resolve().parents[2]
MESEN_MCP_DIR = TOOLS_DIR / "mesen-mcp"

for p in (str(TOOLS_DIR), str(MESEN_MCP_DIR)):
    if p not in sys.path:
        sys.path.insert(0, p)


import pytest
from evals.trace_grader import TraceAndSnippetGrader


def test_tool_called_grader_passes_on_matching_call_and_fails_when_missing() -> None:
    grader = TraceAndSnippetGrader()
    spec = {
        "type": "tool_called",
        "server": "retro-superfx_lsp",
        "tool": "sfx_lookup_symbol",
        "args_match": {
            "symbol": "map_compile_quad_face",
            "path": r"^snes-sfx-demo/X-GSU/",
        },
    }

    passing_trace = [
        {
            "server": "retro-superfx_lsp",
            "tool": "sfx_lookup_symbol",
            "args": {
                "symbol": "map_compile_quad_face",
                "path": "snes-sfx-demo/X-GSU/IsoEngine/",
            },
        }
    ]
    res_pass = grader.evaluate_grader(spec, tool_calls=passing_trace, code="")
    assert res_pass.passed is True

    failing_trace = [
        {
            "server": "retro-superfx_lsp",
            "tool": "sfx_find_references",
            "args": {"symbol": "map_compile_quad_face"},
        }
    ]
    res_fail = grader.evaluate_grader(spec, tool_calls=failing_trace, code="")
    assert res_fail.passed is False
    assert "sfx_lookup_symbol" in res_fail.message


def test_tool_forbidden_grader_catches_grep_and_passes_clean_trace() -> None:
    grader = TraceAndSnippetGrader()
    spec = {
        "type": "tool_forbidden",
        "tool": "run_command",
        "args_pattern": r"\b(grep|rg)\b.*\.(s|sgs|i|inc|sym|dbg)\b",
        "reason": "Do not grep assembly or symbol files; use MCP symbol tools.",
    }

    clean_trace = [
        {
            "server": "retro-superfx_lsp",
            "tool": "sfx_lookup_symbol",
            "args": {"symbol": "draw_textured_triangle"},
        }
    ]
    assert grader.evaluate_grader(spec, tool_calls=clean_trace, code="").passed is True

    forbidden_trace = [
        {
            "tool": "run_command",
            "args": {"CommandLine": "grep -rn draw_textured_triangle snes-sfx-demo/X-GSU/test.sgs"},
        }
    ]
    res_bad = grader.evaluate_grader(spec, tool_calls=forbidden_trace, code="")
    assert res_bad.passed is False
    assert "Do not grep" in res_bad.message


def test_code_invariant_grader_enforces_must_match_and_must_not_match() -> None:
    grader = TraceAndSnippetGrader()
    spec = {
        "type": "code_invariant",
        "must_match": [
            {
                "pattern": r"from\s+r8\s*(?:;|\n)\s*sub\s+#1",
                "reason": "Use non-destructive 'from r8; sub #1' to preserve r8",
            }
        ],
        "must_not_match": [
            {
                "pattern": r"with\s+r8\s*(?:;|\n)\s*sub\s+#1",
                "reason": "Never use destructive 'with r8; sub #1' when r8 must be preserved",
            }
        ],
    }

    good_code = "function check_r8\n    from r8\n    sub #1\n    beq is_one\n    nop\nis_one:\n    return\nendfunction\n"
    assert grader.evaluate_grader(spec, tool_calls=[], code=good_code).passed is True

    bad_code = "function check_r8\n    with r8\n    sub #1\n    beq is_one\n    nop\nis_one:\n    return\nendfunction\n"
    res_bad = grader.evaluate_grader(spec, tool_calls=[], code=bad_code)
    assert res_bad.passed is False
    assert "preserve r8" in res_bad.message or "Never use destructive" in res_bad.message


def test_lsp_audit_clean_grader_catches_fx3_and_stack_violations() -> None:
    grader = TraceAndSnippetGrader()
    spec = {"type": "lsp_audit_clean"}

    clean_code = (
        "function clean_kernel\n"
        "    cache\n"
        "    for #4\n"
        "        gsu_stack_push r1\n"
        "        gsu_stack_pop r1\n"
        "    endfor\n"
        "    rpix\n"
        "    rpix\n"
        "    nop\n    nop\n    nop\n    nop\n    nop\n    nop\n    nop\n    nop\n"
        "    stop\n"
        "    return\n"
        "endfunction\n"
    )
    assert grader.evaluate_grader(spec, tool_calls=[], code=clean_code).passed is True

    # Test missing 8 NOPs before stop, hardware merge, cache in loop, stack leak
    for bad_snippet, expected_rule in [
        ("function f\n    merge\n    return\nendfunction\n", "NO_HARDWARE_MERGE"),
        ("function f\n    nop\n    stop\n    return\nendfunction\n", "PIPELINE_SETTLING_8_NOP"),
        ("function f\n    for #4\n        cache\n    endfor\n    return\nendfunction\n", "CACHE_IN_LOOP"),
        ("function f\n    for #4\n        gsu_stack_push r1\n    endfor\n    return\nendfunction\n", "LOOP_STACK_IMBALANCE"),
        ("function f\n    ldw ($0003)\n    return\nendfunction\n", "UNALIGNED_WORD_ACCESS"),
    ]:
        res = grader.evaluate_grader(spec, tool_calls=[], code=bad_snippet)
        assert res.passed is False, f"Expected failure for {expected_rule}"
        assert expected_rule in res.message


def test_verify_graders_mode_validates_passing_and_failing_replay_fixtures() -> None:
    grader = TraceAndSnippetGrader()
    case_spec = {
        "id": "sample_case",
        "tier": 1,
        "priority": "P0",
        "targets": ["AGENTS.md", ".agents/rules/sfx-assembly-rules.md"],
        "prompt": "Find where map_compile_quad_face is defined.",
        "graders": [
            {
                "type": "tool_called",
                "server": "retro-superfx_lsp",
                "tool": "sfx_lookup_symbol",
                "args_match": {"symbol": "map_compile_quad_face"},
            },
            {
                "type": "tool_forbidden",
                "tool": "run_command",
                "args_pattern": r"\b(grep|rg)\b",
            },
        ],
        "replay_fixtures": {
            "passing": {
                "tool_calls": [
                    {
                        "server": "retro-superfx_lsp",
                        "tool": "sfx_lookup_symbol",
                        "args": {"symbol": "map_compile_quad_face"},
                    }
                ],
                "code": "",
            },
            "failing": {
                "tool_calls": [
                    {
                        "tool": "run_command",
                        "args": {"CommandLine": "grep -rn map_compile_quad_face snes-sfx-demo/"},
                    }
                ],
                "code": "",
            },
        },
    }

    res = grader.grade_case(case_spec, verify_graders=True)
    assert res.passed is True
    assert res.passing_fixture_passed is True
    assert res.failing_fixture_rejected is True

    # If the failing fixture accidentally passes all graders, verify_graders must fail!
    broken_case = dict(case_spec)
    broken_case["replay_fixtures"] = {
        "passing": case_spec["replay_fixtures"]["passing"],
        "failing": case_spec["replay_fixtures"]["passing"],
    }
    res_broken = grader.grade_case(broken_case, verify_graders=True)
    assert res_broken.passed is False
    assert res_broken.failing_fixture_rejected is False


def test_filtering_by_priority_case_id_and_changed_paths(tmp_path: Path) -> None:
    cases_dir = tmp_path / "cases" / "tier1"
    cases_dir.mkdir(parents=True)

    case_a = {
        "id": "p0_case_a",
        "tier": 1,
        "priority": "P0",
        "targets": [".agents/rules/sfx-assembly-rules.md", "snes-sfx-demo/X-GSU/**"],
        "prompt": "Test A",
        "graders": [{"type": "code_invariant", "must_match": [{"pattern": "nop", "reason": "nop"}]}],
        "replay_fixtures": {
            "passing": {"code": "nop"},
            "failing": {"code": "add r1"},
        },
    }
    case_b = {
        "id": "p1_case_b",
        "tier": 1,
        "priority": "P1",
        "targets": [".agents/rules/cross-repo-invariants.md", "retro-common/**"],
        "prompt": "Test B",
        "graders": [{"type": "code_invariant", "must_match": [{"pattern": "return", "reason": "ret"}]}],
        "replay_fixtures": {
            "passing": {"code": "return"},
            "failing": {"code": "nop"},
        },
    }
    import yaml

    (cases_dir / "a.yaml").write_text(yaml.safe_dump(case_a), encoding="utf-8")
    (cases_dir / "b.yaml").write_text(yaml.safe_dump(case_b), encoding="utf-8")

    grader = TraceAndSnippetGrader(workspace_root=tmp_path)

    # Filter by priority P0
    rep_p0 = grader.run_suite(cases_dir=cases_dir, priority="P0", verify_graders=True)
    assert rep_p0.total_cases == 1
    assert rep_p0.cases[0].case_id == "p0_case_a"

    # Filter by case_id
    rep_id = grader.run_suite(cases_dir=cases_dir, case_id="p1_case_b", verify_graders=True)
    assert rep_id.total_cases == 1
    assert rep_id.cases[0].case_id == "p1_case_b"

    # Filter by changed_files
    rep_changed = grader.run_suite(
        cases_dir=cases_dir,
        changed_files=["retro-common/src/main/java/Foo.java"],
        verify_graders=True,
    )
    assert rep_changed.total_cases == 1
    assert rep_changed.cases[0].case_id == "p1_case_b"


def test_all_seven_real_tier1_yaml_cases_pass_verify_graders() -> None:
    workspace_root = Path(__file__).resolve().parents[3]
    grader = TraceAndSnippetGrader(workspace_root=workspace_root)
    report = grader.run_suite(verify_graders=True)

    expected_ids = {
        "p0_symbol_lookup_no_grep",
        "p0_find_references_no_grep",
        "p0_resolve_address_no_grep",
        "p0_modal_nondestructive_compare",
        "p0_no_to_before_move",
        "p0_stop_pipeline_settling_and_rpix",
        "p0_loop_stack_and_cache_discipline",
        "p0_diagnosing_bugs_skill_and_loop_first",
        "p1_cross_repo_install_local",
        "p1_no_mcp_internals_reverse_engineering",
    }
    actual_ids = {c.case_id for c in report.cases}
    assert actual_ids == expected_ids
    assert report.total_cases == 10
    assert report.passed_cases == 10
    assert report.passed is True
    for c in report.cases:
        assert c.passing_fixture_passed is True, f"{c.case_id} passing fixture failed: {c.checks}"
        assert c.failing_fixture_rejected is True, f"{c.case_id} failing fixture was not rejected"


def test_agent_cmd_execution_mode_grades_live_json_output(tmp_path: Path) -> None:
    grader = TraceAndSnippetGrader(workspace_root=tmp_path)
    case_spec = {
        "id": "live_agent_case",
        "tier": 1,
        "priority": "P0",
        "targets": ["AGENTS.md"],
        "prompt": "Lookup symbol foo",
        "graders": [
            {
                "type": "tool_called",
                "server": "retro-superfx_lsp",
                "tool": "sfx_lookup_symbol",
                "args_match": {"symbol": "foo"},
            }
        ],
        "replay_fixtures": {},
    }

    fake_agent_script = tmp_path / "fake_agent.py"
    fake_agent_script.write_text(
        "import json, sys\n"
        "prompt = sys.stdin.read()\n"
        "out = {'tool_calls': [{'server': 'retro-superfx_lsp', 'tool': 'sfx_lookup_symbol', 'args': {'symbol': 'foo'}}], 'code': ''}\n"
        "print(json.dumps(out))\n",
        encoding="utf-8",
    )

    res = grader.grade_case(
        case_spec,
        verify_graders=False,
        agent_cmd=f'"{sys.executable}" "{fake_agent_script}"',
    )
    assert res.passed is True
    assert res.mode == "agent_cmd"


def test_cli_tier1_verify_graders_and_baseline_compare(tmp_path: Path) -> None:
    import json
    import subprocess

    workspace_root = Path(__file__).resolve().parents[3]
    script = workspace_root / "tools" / "run-evals.sh"
    baseline_path = tmp_path / "tier1_baseline.json"

    proc = subprocess.run(
        [
            str(script),
            "--tier",
            "1",
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
    assert proc.returncode == 0, f"STDOUT: {proc.stdout}\nSTDERR: {proc.stderr}"
    payload = json.loads(proc.stdout)
    assert payload["passed"] is True
    assert payload["tier1"]["passed"] is True
    assert payload["tier1"]["total_cases"] == 10
    assert payload["tier1"]["passed_cases"] == 10
    assert baseline_path.is_file()

    # Now run with --priority P0 and --compare against baseline
    cmp_proc = subprocess.run(
        [
            str(script),
            "--tier",
            "1",
            "--priority",
            "P0",
            "--verify-graders",
            "--compare",
            str(baseline_path),
            "--json",
        ],
        cwd=str(workspace_root),
        capture_output=True,
        text=True,
        check=False,
    )
    assert cmp_proc.returncode == 0
    cmp_payload = json.loads(cmp_proc.stdout)
    assert cmp_payload["tier1"]["total_cases"] == 8
    assert "tier1_pass_rate_delta" in cmp_payload["comparison"]
    assert cmp_payload["comparison"]["tier1_pass_rate_delta"] == 0.0

    # Filter by single --case ID via CLI
    single_proc = subprocess.run(
        [
            str(script),
            "--tier",
            "1",
            "--case",
            "p0_modal_nondestructive_compare",
            "--verify-graders",
            "--json",
        ],
        cwd=str(workspace_root),
        capture_output=True,
        text=True,
        check=False,
    )
    assert single_proc.returncode == 0
    single_payload = json.loads(single_proc.stdout)
    assert single_payload["tier1"]["total_cases"] == 1
    assert single_payload["tier1"]["cases"][0]["case_id"] == "p0_modal_nondestructive_compare"


def test_cli_changed_flag_routes_modified_files_to_matching_tier1_cases(
    tmp_path: Path,
) -> None:
    import json
    import subprocess
    import yaml

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

    (repo / "AGENTS.md").write_text("# Clean AGENTS.md\n", encoding="utf-8")
    (repo / "retro-common").mkdir(parents=True)
    foo_java = repo / "retro-common" / "Foo.java"
    foo_java.write_text("class Foo {}\n", encoding="utf-8")

    cases_dir = repo / "tools" / "evals" / "cases" / "tier1"
    cases_dir.mkdir(parents=True)
    (cases_dir / "p1_cross_repo.yaml").write_text(
        yaml.safe_dump(
            {
                "id": "p1_cross_repo_install_local",
                "tier": 1,
                "priority": "P1",
                "targets": ["retro-common/**"],
                "prompt": "Install local",
                "graders": [
                    {
                        "type": "tool_called",
                        "tool": "run_command",
                        "args_match": {"CommandLine": "install-local"},
                    }
                ],
                "replay_fixtures": {
                    "passing": {
                        "tool_calls": [
                            {"tool": "run_command", "args": {"CommandLine": "./tools/install-local.sh"}}
                        ]
                    },
                    "failing": {"tool_calls": []},
                },
            }
        ),
        encoding="utf-8",
    )
    (cases_dir / "p0_unrelated.yaml").write_text(
        yaml.safe_dump(
            {
                "id": "p0_unrelated_mesen",
                "tier": 1,
                "priority": "P0",
                "targets": ["tools/mesen-mcp/**"],
                "prompt": "Mesen",
                "graders": [{"type": "code_invariant", "must_match": [{"pattern": "nop", "reason": "nop"}]}],
                "replay_fixtures": {
                    "passing": {"code": "nop"},
                    "failing": {"code": "stop"},
                },
            }
        ),
        encoding="utf-8",
    )

    subprocess.run(["git", "add", "."], cwd=str(repo), check=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=str(repo), check=True, capture_output=True)

    # Modify retro-common/Foo.java -> --changed should select only p1_cross_repo_install_local
    foo_java.write_text("class Foo { int x; }\n", encoding="utf-8")

    workspace_root = Path(__file__).resolve().parents[3]
    script = workspace_root / "tools" / "run-evals.sh"
    proc = subprocess.run(
        [
            str(script),
            "--changed",
            "--suite",
            "lint",
            "--workspace-root",
            str(repo),
            "--json",
        ],
        cwd=str(repo),
        capture_output=True,
        text=True,
        check=False,
    )
    assert proc.returncode == 0, f"STDOUT: {proc.stdout}\nSTDERR: {proc.stderr}"
    payload = json.loads(proc.stdout)
    assert payload["passed"] is True
    assert payload["tier0"]["lint"]["passed"] is True
    assert payload["tier1"]["total_cases"] == 1
    assert payload["tier1"]["cases"][0]["case_id"] == "p1_cross_repo_install_local"


def test_transcript_normalization_and_audit(tmp_path: Path) -> None:
    import json

    grader = TraceAndSnippetGrader()

    # Verify compact transcript.jsonl double-encoded call_mcp_tool unwrapping
    bad_transcript = tmp_path / "bad_transcript.jsonl"
    bad_lines = [
        {
            "step_index": 0,
            "type": "USER_INPUT",
            "content": "<USER_REQUEST>\nWhen the IsoEngine model is displayed, ramparts disappear. Please review and fix.\n</USER_REQUEST>",
        },
        {
            "step_index": 1,
            "type": "PLANNER_RESPONSE",
            "tool_calls": [
                {
                    "name": "run_command",
                    "args": {
                        "CommandLine": '"grep -n \\"back_ns_mask\\" snes-sfx-demo/X-GSU/IsoEngine/map_grid.i"'
                    },
                },
                {
                    "name": "write_file",
                    "args": {
                        "Path": '"/tmp/scratch/capture.py"',
                        "Content": '"img.save(\\"/tmp/scratch/yaw_30.png\\")"',
                    },
                },
            ],
        },
    ]
    bad_transcript.write_text(
        "\n".join(json.dumps(x) for x in bad_lines) + "\n", encoding="utf-8"
    )
    bad_rep = grader.audit_transcript(bad_transcript)
    assert bad_rep.passed is False
    failed_types = {c.grader_type for c in bad_rep.checks if not c.passed}
    assert "tool_forbidden" in failed_types
    assert "skill_activated" in failed_types
    assert "multimodal_render_inspection" in failed_types

    # Verify clean transcript passes all checks
    good_transcript = tmp_path / "good_transcript.jsonl"
    good_lines = [
        {
            "step_index": 0,
            "type": "USER_INPUT",
            "content": "<USER_REQUEST>\nWhen the IsoEngine model is displayed, ramparts disappear. Please review and fix.\n</USER_REQUEST>",
        },
        {
            "step_index": 1,
            "type": "PLANNER_RESPONSE",
            "tool_calls": [
                {
                    "name": "read_file",
                    "args": {
                        "Path": '"/Users/summerspittman/Projects/retro-superfx/.agents/skills/diagnosing-bugs/SKILL.md"'
                    },
                },
                {
                    "name": "call_mcp_tool",
                    "args": {
                        "ServerName": '"retro-superfx_lsp"',
                        "ToolName": '"sfx_lookup_symbol"',
                        "Arguments": '{"symbol":"map_stamp_edge_panel"}',
                    },
                },
                {
                    "name": "write_file",
                    "args": {
                        "Path": '"/tmp/scratch/capture.py"',
                        "Content": '"img.save(\\"/tmp/scratch/yaw_30.png\\")"',
                    },
                },
                {
                    "name": "read_file",
                    "args": {"Path": '"/tmp/scratch/yaw_30.png"'},
                },
            ],
        },
    ]
    good_transcript.write_text(
        "\n".join(json.dumps(x) for x in good_lines) + "\n", encoding="utf-8"
    )
    good_rep = grader.audit_transcript(good_transcript)
    assert good_rep.passed is True
    assert good_rep.skills_read == ["diagnosing-bugs"]
    assert good_rep.mcp_calls_by_tool == {"retro-superfx_lsp:sfx_lookup_symbol": 1}






