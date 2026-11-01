from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import json
from pathlib import Path
import re
import subprocess
from typing import Any, Sequence

from evals.mcp_smoke import StdioMcpClient, _extract_error_count
from evals.trace_grader import (
    CaseGradeResult,
    DiagnosticRunner,
    GraderCheckResult,
    TraceAndSnippetGrader,
)
from evals.worktree import WorktreeSandbox


_COMPARISON_RE = re.compile(r"^\s*(>=|<=|!=|==|>|<)\s*(.+?)\s*$")


@dataclass
class Tier2EvalReport:
    passed: bool
    total_cases: int
    passed_cases: int
    cases: list[CaseGradeResult] = field(default_factory=list)

    @property
    def pass_rate(self) -> float:
        if self.total_cases == 0:
            return 1.0
        return self.passed_cases / self.total_cases

    def to_dict(self) -> dict[str, Any]:
        return {
            "passed": self.passed,
            "total_cases": self.total_cases,
            "passed_cases": self.passed_cases,
            "pass_rate": round(self.pass_rate, 4),
            "cases": [c.to_dict() for c in self.cases],
        }


class OutcomeGrader(TraceAndSnippetGrader):
    """Tier 2 multi-step trajectory, worktree file, and MCP emulation outcome evaluator."""

    def __init__(
        self,
        workspace_root: Path | str | None = None,
        diagnostic_runner: DiagnosticRunner | None = None,
        lsp_client: Any | None = None,
        mesen_server: Any | None = None,
    ) -> None:
        super().__init__(
            workspace_root=workspace_root,
            diagnostic_runner=diagnostic_runner,
        )
        self._lsp_client = lsp_client
        self._mesen_server = mesen_server

    def evaluate_grader(
        self,
        grader_spec: dict[str, Any],
        tool_calls: list[dict[str, Any]],
        code: str,
        *,
        files: Sequence[str] | dict[str, Any] | None = None,
        mcp_outcomes: dict[str, Any] | list[dict[str, Any]] | None = None,
        sandbox_root: Path | None = None,
        live_mcp: bool = False,
    ) -> GraderCheckResult:
        gtype = grader_spec.get("type", "")
        if gtype == "tool_sequence_contains":
            return self._eval_tool_sequence_contains(grader_spec, tool_calls)
        if gtype == "file_exists":
            return self._eval_file_exists(
                grader_spec,
                files=files,
                sandbox_root=sandbox_root or self.workspace_root,
            )
        if gtype == "mcp_verifier":
            return self._eval_mcp_verifier(
                grader_spec,
                mcp_outcomes=mcp_outcomes,
                sandbox_root=sandbox_root or self.workspace_root,
                live_mcp=live_mcp,
            )
        return super().evaluate_grader(grader_spec, tool_calls=tool_calls, code=code)

    def _call_matches_token(self, call: dict[str, Any], token: str) -> bool:
        from evals.trace_grader import normalize_tool_call

        norm = normalize_tool_call(call)
        call_server = str(norm.get("server") or "")
        call_tool = str(norm.get("tool") or "")

        for alt in token.split("|"):
            alt = alt.strip()
            if not alt:
                continue
            if ":" in alt:
                exp_server, exp_tool = alt.split(":", 1)
                if (not call_server or call_server == exp_server) and call_tool == exp_tool:
                    return True
            elif "." in alt:
                exp_server, exp_tool = alt.split(".", 1)
                if (not call_server or call_server == exp_server) and call_tool == exp_tool:
                    return True
            else:
                if call_tool == alt:
                    return True
        return False

    def _eval_tool_sequence_contains(
        self,
        spec: dict[str, Any],
        tool_calls: list[dict[str, Any]],
    ) -> GraderCheckResult:
        sequence: list[str] = [
            str(s) for s in (spec.get("sequence") or spec.get("tools") or [])
        ]
        if not sequence:
            return GraderCheckResult(
                grader_type="tool_sequence_contains",
                passed=True,
                message="Empty tool sequence satisfied",
            )

        call_idx = 0
        for step in sequence:
            matched = False
            while call_idx < len(tool_calls):
                if self._call_matches_token(tool_calls[call_idx], step):
                    matched = True
                    call_idx += 1
                    break
                call_idx += 1
            if not matched:
                observed = [
                    str(c.get("tool") or c.get("ToolName") or c.get("name") or "")
                    for c in tool_calls
                ]
                return GraderCheckResult(
                    grader_type="tool_sequence_contains",
                    passed=False,
                    message=(
                        f"Missing or out-of-order tool step '{step}' in required sequence "
                        f"{sequence} (observed trace: {observed})"
                    ),
                )

        return GraderCheckResult(
            grader_type="tool_sequence_contains",
            passed=True,
            message=f"Matched required tool sequence {sequence}",
        )

    def _eval_file_exists(
        self,
        spec: dict[str, Any],
        files: Sequence[str] | dict[str, Any] | None,
        sandbox_root: Path,
    ) -> GraderCheckResult:
        raw_paths = spec.get("paths") or (
            [spec["path"]] if "path" in spec else []
        )
        required_paths = [str(p).lstrip("./") for p in raw_paths]

        virtual_set: set[str] | None = None
        if files is not None:
            if isinstance(files, dict):
                virtual_set = {str(k).lstrip("./") for k in files.keys()}
            else:
                virtual_set = {str(k).lstrip("./") for k in files}

        missing: list[str] = []
        for req in required_paths:
            if virtual_set is not None:
                if req not in virtual_set:
                    missing.append(req)
            else:
                if not (sandbox_root / req).exists():
                    missing.append(req)

        if missing:
            return GraderCheckResult(
                grader_type="file_exists",
                passed=False,
                message=f"Missing required file(s): {', '.join(missing)}",
            )
        return GraderCheckResult(
            grader_type="file_exists",
            passed=True,
            message=f"All required files exist ({len(required_paths)} checked)",
        )

    def _eval_mcp_verifier(
        self,
        spec: dict[str, Any],
        mcp_outcomes: dict[str, Any] | list[dict[str, Any]] | None,
        sandbox_root: Path,
        live_mcp: bool,
    ) -> GraderCheckResult:
        server = str(spec.get("server", ""))
        tool = str(spec.get("tool", ""))
        args: dict[str, Any] = dict(spec.get("args", {}) or {})
        expect: dict[str, Any] = dict(spec.get("expect", {}) or {})

        outcome_payload: dict[str, Any] | None = None
        if not live_mcp and mcp_outcomes is not None:
            outcome_payload = self._lookup_replay_outcome(mcp_outcomes, server, tool)

        if outcome_payload is None:
            if not live_mcp and mcp_outcomes is not None:
                return GraderCheckResult(
                    grader_type="mcp_verifier",
                    passed=False,
                    message=f"No MCP outcome recorded for '{server}:{tool}'",
                )
            try:
                outcome_payload = self._invoke_live_mcp(
                    server=server,
                    tool=tool,
                    args=args,
                    sandbox_root=sandbox_root,
                )
            except Exception as exc:
                return GraderCheckResult(
                    grader_type="mcp_verifier",
                    passed=False,
                    message=f"Live MCP call '{server}:{tool}' failed: {exc}",
                )

        for key_path, expected_expr in expect.items():
            found, actual_val = self._resolve_field(outcome_payload, str(key_path))
            if not found:
                return GraderCheckResult(
                    grader_type="mcp_verifier",
                    passed=False,
                    message=(
                        f"MCP outcome for '{tool}' missing expected field '{key_path}' "
                        f"(payload={outcome_payload})"
                    ),
                )
            if not self._matches_expectation(actual_val, expected_expr):
                return GraderCheckResult(
                    grader_type="mcp_verifier",
                    passed=False,
                    message=(
                        f"MCP outcome assertion failed on '{tool}.{key_path}': "
                        f"expected {expected_expr!r}, got {actual_val!r}"
                    ),
                )

        return GraderCheckResult(
            grader_type="mcp_verifier",
            passed=True,
            message=f"MCP verifier '{tool}' satisfied all expectations",
        )

    def _lookup_replay_outcome(
        self,
        mcp_outcomes: dict[str, Any] | list[dict[str, Any]],
        server: str,
        tool: str,
    ) -> dict[str, Any] | None:
        if isinstance(mcp_outcomes, dict):
            for candidate_key in (f"{server}:{tool}", f"{server}.{tool}", tool):
                if candidate_key in mcp_outcomes and isinstance(
                    mcp_outcomes[candidate_key], dict
                ):
                    return dict(mcp_outcomes[candidate_key])
            return None

        if isinstance(mcp_outcomes, list):
            for item in mcp_outcomes:
                if not isinstance(item, dict):
                    continue
                item_server = str(item.get("server", ""))
                item_tool = str(item.get("tool", ""))
                if item_tool == tool and (not server or not item_server or item_server == server):
                    result = item.get("result") or item.get("outcome") or item
                    if isinstance(result, dict):
                        return dict(result)
        return None

    def _invoke_live_mcp(
        self,
        server: str,
        tool: str,
        args: dict[str, Any],
        sandbox_root: Path,
    ) -> dict[str, Any]:
        if server in ("retro-superfx_lsp", "lsp"):
            if self._lsp_client is not None:
                return dict(self._lsp_client.call_tool(tool, args))
            with StdioMcpClient(workspace_root=sandbox_root) as client:
                return dict(client.call_tool(tool, args))

        if server in ("retro-superfx_mesen", "mesen"):
            mesen = self._mesen_server
            if mesen is None:
                from mesen_mcp.server import create_server

                mesen = create_server()
            call_res = asyncio.run(mesen.call_tool(tool, args))
            content = getattr(call_res, "content", [])
            if not content:
                return {}
            text = getattr(content[0], "text", "{}")
            return dict(json.loads(text))

        raise ValueError(f"Unknown MCP server '{server}'")

    def _resolve_field(
        self,
        payload: dict[str, Any],
        key_path: str,
    ) -> tuple[bool, Any]:
        if key_path in payload:
            return True, payload[key_path]

        parts = key_path.split(".")
        curr: Any = payload
        for part in parts:
            if isinstance(curr, dict) and part in curr:
                curr = curr[part]
            else:
                # Resilience alias: summary.errorCount <-> top-level errorCount / violations
                if key_path in ("summary.errorCount", "errorCount"):
                    if "errorCount" in payload:
                        return True, payload["errorCount"]
                    if isinstance(payload.get("summary"), dict) and "errorCount" in payload["summary"]:
                        return True, payload["summary"]["errorCount"]
                    if "violations" in payload:
                        return True, _extract_error_count(payload)
                return False, None
        return True, curr

    def _matches_expectation(self, actual: Any, expected: Any) -> bool:
        if isinstance(expected, str):
            m = _COMPARISON_RE.match(expected)
            if m:
                op, rhs_str = m.group(1), m.group(2)
                try:
                    rhs_val: Any = float(rhs_str) if "." in rhs_str else int(rhs_str)
                except ValueError:
                    rhs_val = rhs_str.strip("\"'")

                try:
                    if op == ">":
                        return bool(actual > rhs_val)
                    if op == ">=":
                        return bool(actual >= rhs_val)
                    if op == "<":
                        return bool(actual < rhs_val)
                    if op == "<=":
                        return bool(actual <= rhs_val)
                    if op == "==":
                        return bool(actual == rhs_val)
                    if op == "!=":
                        return bool(actual != rhs_val)
                except TypeError:
                    return False

        return bool(actual == expected)

    def _extract_tier2_fixture_payload(
        self,
        replay_fixtures: dict[str, Any],
        variant: str,
    ) -> tuple[
        list[dict[str, Any]],
        str,
        Sequence[str] | dict[str, Any] | None,
        dict[str, Any] | list[dict[str, Any]] | None,
    ]:
        block = replay_fixtures.get(variant)
        if isinstance(block, dict):
            tool_calls = list(block.get("tool_calls", []) or [])
            code = str(block.get("code", "") or "")
            files = block.get("files")
            mcp_outcomes = block.get("mcp_outcomes")
            return tool_calls, code, files, mcp_outcomes
        tool_calls, code = self._extract_fixture_payload(replay_fixtures, variant)
        return tool_calls, code, None, None

    def _run_agent_cmd_in_sandbox(
        self,
        agent_cmd: str,
        prompt: str,
        sandbox_dir: Path,
    ) -> tuple[
        list[dict[str, Any]],
        str,
        Sequence[str] | dict[str, Any] | None,
        dict[str, Any] | list[dict[str, Any]] | None,
    ]:
        if "{prompt}" in agent_cmd:
            cmd_str = agent_cmd.replace("{prompt}", prompt)
            proc = subprocess.run(
                cmd_str,
                shell=True,
                cwd=str(sandbox_dir),
                capture_output=True,
                text=True,
                check=False,
            )
        else:
            proc = subprocess.run(
                agent_cmd,
                shell=True,
                input=prompt,
                cwd=str(sandbox_dir),
                capture_output=True,
                text=True,
                check=False,
            )

        stdout = proc.stdout.strip()
        if not stdout:
            return [], "", None, None

        try:
            parsed = json.loads(stdout)
            if isinstance(parsed, dict):
                files_data = parsed.get("files")
                if isinstance(files_data, dict):
                    for rel_p, content in files_data.items():
                        dest = sandbox_dir / str(rel_p).lstrip("./")
                        dest.parent.mkdir(parents=True, exist_ok=True)
                        dest.write_text(str(content), encoding="utf-8")
                return (
                    list(parsed.get("tool_calls", []) or []),
                    str(parsed.get("code", "") or ""),
                    None,  # check real disk inside sandbox_dir
                    parsed.get("mcp_outcomes"),
                )
        except json.JSONDecodeError:
            pass

        tool_calls, code = self._run_agent_cmd(agent_cmd, prompt)
        return tool_calls, code, None, None

    def grade_case(
        self,
        case_spec: dict[str, Any],
        verify_graders: bool = True,
        agent_cmd: str | None = None,
    ) -> CaseGradeResult:
        case_id = str(case_spec.get("id", "unknown"))
        priority = str(case_spec.get("priority", "P1"))
        graders: list[dict[str, Any]] = list(case_spec.get("graders", []) or [])
        replay_fixtures: dict[str, Any] = dict(
            case_spec.get("replay_fixtures", {}) or {}
        )

        if agent_cmd and not verify_graders:
            with WorktreeSandbox(self.workspace_root) as sandbox_dir:
                (
                    tool_calls,
                    code,
                    files,
                    mcp_outcomes,
                ) = self._run_agent_cmd_in_sandbox(
                    agent_cmd=agent_cmd,
                    prompt=str(case_spec.get("prompt", "")),
                    sandbox_dir=sandbox_dir,
                )
                checks = [
                    self.evaluate_grader(
                        g,
                        tool_calls=tool_calls,
                        code=code,
                        files=files,
                        mcp_outcomes=mcp_outcomes,
                        sandbox_root=sandbox_dir,
                        live_mcp=(mcp_outcomes is None),
                    )
                    for g in graders
                ]
            return CaseGradeResult(
                case_id=case_id,
                priority=priority,
                passed=all(c.passed for c in checks),
                mode="agent_cmd",
                checks=checks,
            )

        (
            pass_calls,
            pass_code,
            pass_files,
            pass_outcomes,
        ) = self._extract_tier2_fixture_payload(replay_fixtures, "passing")
        (
            fail_calls,
            fail_code,
            fail_files,
            fail_outcomes,
        ) = self._extract_tier2_fixture_payload(replay_fixtures, "failing")

        pass_checks = [
            self.evaluate_grader(
                g,
                tool_calls=pass_calls,
                code=pass_code,
                files=pass_files,
                mcp_outcomes=pass_outcomes,
                sandbox_root=self.workspace_root,
                live_mcp=False,
            )
            for g in graders
        ]
        fail_checks = [
            self.evaluate_grader(
                g,
                tool_calls=fail_calls,
                code=fail_code,
                files=fail_files,
                mcp_outcomes=fail_outcomes,
                sandbox_root=self.workspace_root,
                live_mcp=False,
            )
            for g in graders
        ]

        passing_ok = all(c.passed for c in pass_checks)
        failing_rejected = any(not c.passed for c in fail_checks)
        overall_ok = passing_ok and failing_rejected

        summary_checks = list(pass_checks)
        if not failing_rejected:
            summary_checks.append(
                GraderCheckResult(
                    grader_type="replay_fixture_negative",
                    passed=False,
                    message=f"Failing replay fixture for '{case_id}' unexpectedly passed all graders!",
                )
            )

        return CaseGradeResult(
            case_id=case_id,
            priority=priority,
            passed=overall_ok,
            mode="verify_graders",
            checks=summary_checks,
            passing_fixture_passed=passing_ok,
            failing_fixture_rejected=failing_rejected,
        )

    def load_cases(self, cases_dir: Path | str | None = None) -> list[dict[str, Any]]:
        cdir = (
            Path(cases_dir).resolve()
            if cases_dir is not None
            else (self.workspace_root / "tools" / "evals" / "cases" / "tier2")
        )
        return super().load_cases(cases_dir=cdir)

    def run_suite(
        self,
        cases_dir: Path | str | None = None,
        priority: str | None = None,
        case_id: str | None = None,
        changed_files: Sequence[str] | None = None,
        verify_graders: bool = True,
        agent_cmd: str | None = None,
    ) -> Tier2EvalReport:
        t1_report = super().run_suite(
            cases_dir=cases_dir,
            priority=priority,
            case_id=case_id,
            changed_files=changed_files,
            verify_graders=verify_graders,
            agent_cmd=agent_cmd,
        )
        return Tier2EvalReport(
            passed=t1_report.passed,
            total_cases=t1_report.total_cases,
            passed_cases=t1_report.passed_cases,
            cases=t1_report.cases,
        )
