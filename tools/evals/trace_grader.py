from __future__ import annotations

from dataclasses import asdict, dataclass, field
import fnmatch
import json
from pathlib import Path
import re
import subprocess
from typing import Any, Callable, Sequence
import yaml


DiagnosticRunner = Callable[[str], list[dict[str, Any]]]


@dataclass(frozen=True)
class GraderCheckResult:
    grader_type: str
    passed: bool
    message: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class CaseGradeResult:
    case_id: str
    priority: str
    passed: bool
    mode: str
    checks: list[GraderCheckResult] = field(default_factory=list)
    passing_fixture_passed: bool | None = None
    failing_fixture_rejected: bool | None = None

    def to_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "case_id": self.case_id,
            "priority": self.priority,
            "passed": self.passed,
            "mode": self.mode,
            "checks": [c.to_dict() for c in self.checks],
        }
        if self.passing_fixture_passed is not None:
            data["passing_fixture_passed"] = self.passing_fixture_passed
        if self.failing_fixture_rejected is not None:
            data["failing_fixture_rejected"] = self.failing_fixture_rejected
        return data


@dataclass
class Tier1EvalReport:
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


@dataclass
class TranscriptAuditReport:
    conversation_id: str
    transcript_path: str
    passed: bool
    user_prompt: str
    total_steps: int
    total_tool_calls: int
    skills_read: list[str] = field(default_factory=list)
    mcp_calls_by_tool: dict[str, int] = field(default_factory=dict)
    checks: list[GraderCheckResult] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "conversation_id": self.conversation_id,
            "transcript_path": self.transcript_path,
            "passed": self.passed,
            "user_prompt": self.user_prompt,
            "total_steps": self.total_steps,
            "total_tool_calls": self.total_tool_calls,
            "skills_read": self.skills_read,
            "mcp_calls_by_tool": self.mcp_calls_by_tool,
            "checks": [c.to_dict() for c in self.checks],
        }


def _unquote_compact_value(val: Any) -> Any:
    """Unwrap double-quoted strings or JSON-encoded strings from compact transcript.jsonl."""
    if not isinstance(val, str):
        return val
    stripped = val.strip()
    if len(stripped) >= 2 and stripped.startswith('"') and stripped.endswith('"'):
        try:
            loaded = json.loads(stripped)
            return loaded
        except json.JSONDecodeError:
            return stripped[1:-1]
    if (stripped.startswith("{") and stripped.endswith("}")) or (
        stripped.startswith("[") and stripped.endswith("]")
    ):
        try:
            return json.loads(stripped)
        except json.JSONDecodeError:
            return val
    return val


def normalize_tool_call(raw_call: dict[str, Any]) -> dict[str, Any]:
    """Normalize an Antigravity transcript tool_call or eval fixture tool_call into a canonical dict."""
    call_name = _unquote_compact_value(
        raw_call.get("tool") or raw_call.get("ToolName") or raw_call.get("name") or ""
    )
    call_server = _unquote_compact_value(
        raw_call.get("server") or raw_call.get("ServerName") or ""
    )
    raw_args = (
        raw_call.get("args")
        or raw_call.get("Arguments")
        or raw_call.get("arguments")
        or {}
    )
    if isinstance(raw_args, str):
        raw_args = _unquote_compact_value(raw_args)
    if isinstance(raw_args, dict):
        clean_args = {k: _unquote_compact_value(v) for k, v in raw_args.items()}
    else:
        clean_args = {}

    if call_name == "call_mcp_tool":
        mcp_server = str(
            clean_args.get("ServerName") or clean_args.get("server") or call_server or ""
        )
        mcp_tool = str(
            clean_args.get("ToolName") or clean_args.get("tool") or ""
        )
        inner_args = clean_args.get("Arguments") or clean_args.get("args") or {}
        if isinstance(inner_args, str):
            inner_args = _unquote_compact_value(inner_args)
        if not isinstance(inner_args, dict):
            inner_args = {}
        return {
            "server": mcp_server,
            "tool": mcp_tool,
            "name": mcp_tool,
            "args": inner_args,
            "raw_name": "call_mcp_tool",
        }

    return {
        "server": str(call_server) if call_server else None,
        "tool": str(call_name),
        "name": str(call_name),
        "args": clean_args,
    }


def resolve_transcript_path(target: str | Path) -> Path:
    """Resolve a conversation UUID or file/directory path to a transcript.jsonl file."""
    candidate = Path(target).expanduser()
    if candidate.is_file():
        return candidate.resolve()
    if candidate.is_dir():
        jsonl = candidate / ".system_generated" / "logs" / "transcript.jsonl"
        if jsonl.is_file():
            return jsonl.resolve()
        direct = candidate / "transcript.jsonl"
        if direct.is_file():
            return direct.resolve()

    brain_dir = Path.home() / ".gemini" / "antigravity" / "brain" / str(target)
    jsonl = brain_dir / ".system_generated" / "logs" / "transcript.jsonl"
    if jsonl.is_file():
        return jsonl.resolve()
    raise FileNotFoundError(f"Could not resolve transcript path for '{target}'")


def parse_transcript_jsonl(
    transcript_path: str | Path,
) -> tuple[str, int, list[dict[str, Any]], str]:
    """Parse an Antigravity transcript.jsonl into (user_prompt, step_count, normalized_tool_calls, sfx_code)."""
    path = resolve_transcript_path(transcript_path)
    user_prompt = ""
    step_count = 0
    tool_calls: list[dict[str, Any]] = []
    code_parts: list[str] = []

    for raw_line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        try:
            step = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(step, dict):
            continue
        step_count += 1

        if step.get("type") == "USER_INPUT" and not user_prompt:
            content = str(step.get("content", ""))
            m_req = re.search(
                r"<USER_REQUEST>\s*(.*?)\s*</USER_REQUEST>", content, re.DOTALL
            )
            user_prompt = m_req.group(1).strip() if m_req else content.strip()

        raw_calls = step.get("tool_calls")
        if isinstance(raw_calls, list):
            for rc in raw_calls:
                if not isinstance(rc, dict):
                    continue
                norm = normalize_tool_call(rc)
                tool_calls.append(norm)
                tname = norm.get("tool")
                targs = norm.get("args", {})
                if tname in ("write_file", "edit_file") and isinstance(targs, dict):
                    fpath = str(targs.get("Path", ""))
                    if fpath.endswith((".s", ".sgs", ".i", ".inc")):
                        snippet = str(
                            targs.get("Content") or targs.get("Replacement") or ""
                        )
                        if snippet:
                            code_parts.append(snippet)

    return user_prompt, step_count, tool_calls, "\n".join(code_parts)



def default_sfx_audit_runner(code: str) -> list[dict[str, Any]]:
    """Fast deterministic SuperFX static diagnostic auditor for Tier 1 snippet grading."""
    diagnostics: list[dict[str, Any]] = []
    raw_lines = code.splitlines()

    # Strip comments while preserving line numbers (1-based)
    cleaned_lines: list[tuple[int, str]] = []
    for idx, line in enumerate(raw_lines, start=1):
        without_comment = line.split(";", 1)[0].strip()
        if without_comment:
            cleaned_lines.append((idx, without_comment))

    # Split semicolon-chained statements into individual instruction tokens with line numbers
    statements: list[tuple[int, str]] = []
    for line_no, text in cleaned_lines:
        for part in text.split(";"):
            stmt = part.strip()
            if stmt:
                statements.append((line_no, stmt))

    fn_stack: list[int] = []
    for_stack: list[tuple[int, int]] = []  # (line_no, stack_depth_at_entry)
    sp_delta = 0

    for idx, (line_no, stmt) in enumerate(statements):
        tokens = stmt.split()
        if not tokens:
            continue
        head = tokens[0].lower()

        # Skip pure labels (e.g. "done:")
        if head.endswith(":") and len(tokens) == 1:
            continue
        if head.endswith(":"):
            tokens = tokens[1:]
            if not tokens:
                continue
            head = tokens[0].lower()

        if head == "function":
            fn_stack.append(line_no)
            sp_delta = 0
        elif head == "endfunction":
            if not fn_stack:
                diagnostics.append(
                    {
                        "line": line_no,
                        "rule": "UNBALANCED_ENDFUNCTION",
                        "severity": "ERROR",
                        "message": "'endfunction' without matching 'function'",
                    }
                )
            else:
                fn_stack.pop()
                if sp_delta != 0:
                    diagnostics.append(
                        {
                            "line": line_no,
                            "rule": "STACK_LEAK_ON_RETURN",
                            "severity": "ERROR",
                            "message": f"Stack displacement at function end is non-zero (delta={sp_delta})",
                        }
                    )
        elif head in ("for", "forr"):
            for_stack.append((line_no, sp_delta))
        elif head == "endfor":
            if not for_stack:
                diagnostics.append(
                    {
                        "line": line_no,
                        "rule": "UNBALANCED_ENDFOR",
                        "severity": "ERROR",
                        "message": "'endfor' without matching 'for' or 'forR'",
                    }
                )
            else:
                entry_line, entry_sp = for_stack.pop()
                if sp_delta != entry_sp:
                    diagnostics.append(
                        {
                            "line": line_no,
                            "rule": "LOOP_STACK_IMBALANCE",
                            "severity": "ERROR",
                            "message": (
                                f"Unbalanced stack inside for...endfor loop starting at line {entry_line} "
                                f"(delta={sp_delta - entry_sp})"
                            ),
                        }
                    )
        elif head == "gsu_stack_push":
            sp_delta += 2
        elif head == "gsu_stack_pop":
            sp_delta -= 2
        elif head == "gsu_stack_alloc_bytes" and len(tokens) > 1:
            try:
                sp_delta += int(tokens[1].lstrip("#$"), 0)
            except ValueError:
                sp_delta += 2
        elif head == "gsu_stack_free_bytes" and len(tokens) > 1:
            try:
                sp_delta -= int(tokens[1].lstrip("#$"), 0)
            except ValueError:
                sp_delta -= 2
        elif head == "return":
            if sp_delta != 0:
                diagnostics.append(
                    {
                        "line": line_no,
                        "rule": "STACK_LEAK_ON_RETURN",
                        "severity": "ERROR",
                        "message": f"Stack displacement at return is non-zero (delta={sp_delta})",
                    }
                )
        elif head == "merge":
            diagnostics.append(
                {
                    "line": line_no,
                    "rule": "NO_HARDWARE_MERGE",
                    "severity": "ERROR",
                    "message": "Hardware 'MERGE' instruction is not supported on SuperFX 3 (FX3).",
                }
            )
        elif head == "cache" and for_stack:
            diagnostics.append(
                {
                    "line": line_no,
                    "rule": "CACHE_IN_LOOP",
                    "severity": "ERROR",
                    "message": "'cache' inside a loop body resets CBR and invalidates all 32 cache lines.",
                }
            )
        elif head == "stop":
            nop_count = 0
            scan = idx - 1
            while scan >= 0:
                prev_stmt = statements[scan][1].strip()
                if prev_stmt.endswith(":"):
                    scan -= 1
                    continue
                if prev_stmt.lower() == "nop":
                    nop_count += 1
                    scan -= 1
                else:
                    break
            if nop_count < 8:
                diagnostics.append(
                    {
                        "line": line_no,
                        "rule": "PIPELINE_SETTLING_8_NOP",
                        "severity": "ERROR",
                        "message": f"Missing 8-NOP pipeline settling before 'stop' (found {nop_count} NOPs).",
                    }
                )
        elif head in ("ldw", "stw") and len(tokens) > 1:
            operand = tokens[1]
            m_hex = re.search(r"#?\$([0-9a-fA-F]+)", operand)
            m_dec = re.search(r"#(\d+)$", operand)
            val: int | None = None
            if m_hex:
                val = int(m_hex.group(1), 16)
            elif m_dec:
                val = int(m_dec.group(1), 10)
            if val is not None and (val % 2 != 0):
                diagnostics.append(
                    {
                        "line": line_no,
                        "rule": "UNALIGNED_WORD_ACCESS",
                        "severity": "ERROR",
                        "message": f"Unaligned 16-bit word access '{stmt}' at odd address.",
                    }
                )

    for open_fn in fn_stack:
        diagnostics.append(
            {
                "line": open_fn,
                "rule": "UNCLOSED_FUNCTION",
                "severity": "ERROR",
                "message": "'function' block is missing 'endfunction'",
            }
        )
    for open_for, _ in for_stack:
        diagnostics.append(
            {
                "line": open_for,
                "rule": "UNCLOSED_FOR",
                "severity": "ERROR",
                "message": "'for' loop is missing 'endfor'",
            }
        )

    return diagnostics


class TraceAndSnippetGrader:
    """Tier 1 static policy, hardware invariant, and tool-routing evaluator."""

    def __init__(
        self,
        workspace_root: Path | str | None = None,
        diagnostic_runner: DiagnosticRunner | None = None,
    ) -> None:
        self.workspace_root = (
            Path(workspace_root).resolve()
            if workspace_root is not None
            else Path(__file__).resolve().parents[2]
        )
        self.diagnostic_runner = diagnostic_runner or default_sfx_audit_runner

    def evaluate_grader(
        self,
        grader_spec: dict[str, Any],
        tool_calls: list[dict[str, Any]],
        code: str,
    ) -> GraderCheckResult:
        gtype = grader_spec.get("type", "")
        if gtype == "tool_called":
            return self._eval_tool_called(grader_spec, tool_calls)
        if gtype == "tool_forbidden":
            return self._eval_tool_forbidden(grader_spec, tool_calls)
        if gtype == "code_invariant":
            return self._eval_code_invariant(grader_spec, code)
        if gtype == "lsp_audit_clean":
            return self._eval_lsp_audit_clean(grader_spec, code)
        return GraderCheckResult(
            grader_type=gtype or "unknown",
            passed=False,
            message=f"Unsupported grader type: '{gtype}'",
        )

    def _eval_tool_called(
        self,
        spec: dict[str, Any],
        tool_calls: list[dict[str, Any]],
    ) -> GraderCheckResult:
        expected_server = spec.get("server")
        expected_tool = spec.get("tool", "")
        args_match: dict[str, Any] = spec.get("args_match", {}) or {}

        for call in tool_calls:
            norm = normalize_tool_call(call)
            call_server = norm.get("server")
            call_tool = norm.get("tool")
            call_args = norm.get("args") or {}

            if expected_server and call_server != expected_server:
                continue
            if call_tool != expected_tool:
                continue
            if self._args_match(call_args, args_match):
                return GraderCheckResult(
                    grader_type="tool_called",
                    passed=True,
                    message=f"Matched required tool call '{expected_tool}'",
                )

        server_qual = f"{expected_server}." if expected_server else ""
        return GraderCheckResult(
            grader_type="tool_called",
            passed=False,
            message=(
                f"Expected tool call '{server_qual}{expected_tool}' with args matching "
                f"{args_match}, but no matching call found in trace"
            ),
        )

    def _args_match(
        self,
        actual_args: dict[str, Any],
        expected_match: dict[str, Any],
    ) -> bool:
        if not isinstance(actual_args, dict):
            return False
        for key, pattern_or_val in expected_match.items():
            if key not in actual_args:
                return False
            actual_val = actual_args[key]
            if isinstance(pattern_or_val, str) and isinstance(actual_val, str):
                if actual_val == pattern_or_val:
                    continue
                if re.search(pattern_or_val, actual_val):
                    continue
                return False
            elif actual_val != pattern_or_val:
                return False
        return True

    def _eval_tool_forbidden(
        self,
        spec: dict[str, Any],
        tool_calls: list[dict[str, Any]],
    ) -> GraderCheckResult:
        forbidden_tool = spec.get("tool", "")
        args_pattern = spec.get("args_pattern")
        reason = spec.get("reason", f"Forbidden tool call '{forbidden_tool}'")

        for call in tool_calls:
            norm = normalize_tool_call(call)
            call_tool = norm.get("tool")
            if call_tool != forbidden_tool:
                continue
            call_args = norm.get("args") or {}
            serialized = (
                json.dumps(call_args, sort_keys=True)
                if not isinstance(call_args, str)
                else call_args
            )
            if args_pattern is None or re.search(args_pattern, serialized, re.IGNORECASE):
                return GraderCheckResult(
                    grader_type="tool_forbidden",
                    passed=False,
                    message=f"{reason} (matched '{call_tool}' with args: {serialized})",
                )

        return GraderCheckResult(
            grader_type="tool_forbidden",
            passed=True,
            message=f"No forbidden '{forbidden_tool}' calls detected",
        )

    def _eval_code_invariant(
        self,
        spec: dict[str, Any],
        code: str,
    ) -> GraderCheckResult:
        must_match: list[dict[str, Any]] = spec.get("must_match", []) or []
        must_not_match: list[dict[str, Any]] = spec.get("must_not_match", []) or []
        flags = re.IGNORECASE | re.MULTILINE | re.DOTALL

        for rule in must_match:
            pattern = rule.get("pattern", "")
            reason = rule.get("reason", f"Missing required pattern: {pattern}")
            if not re.search(pattern, code, flags):
                return GraderCheckResult(
                    grader_type="code_invariant",
                    passed=False,
                    message=f"Missing required invariant: {reason}",
                )

        for rule in must_not_match:
            pattern = rule.get("pattern", "")
            reason = rule.get("reason", f"Matched forbidden pattern: {pattern}")
            if re.search(pattern, code, flags):
                return GraderCheckResult(
                    grader_type="code_invariant",
                    passed=False,
                    message=f"Violated code invariant: {reason}",
                )

        return GraderCheckResult(
            grader_type="code_invariant",
            passed=True,
            message="All code_invariant patterns satisfied",
        )

    def _eval_lsp_audit_clean(
        self,
        spec: dict[str, Any],
        code: str,
    ) -> GraderCheckResult:
        allow_warnings = bool(spec.get("allow_warnings", False))
        violations = self.diagnostic_runner(code)
        blocking = [
            v
            for v in violations
            if not (allow_warnings and str(v.get("severity", "ERROR")).upper() == "WARNING")
        ]
        if blocking:
            first = blocking[0]
            msg = first.get("message", str(first))
            rule = first.get("rule", "DIAGNOSTIC")
            return GraderCheckResult(
                grader_type="lsp_audit_clean",
                passed=False,
                message=f"LSP audit reported {len(blocking)} violation(s): [{rule}] {msg}",
            )
        return GraderCheckResult(
            grader_type="lsp_audit_clean",
            passed=True,
            message="LSP audit clean (0 violations)",
        )

    def _extract_fixture_payload(
        self,
        replay_fixtures: dict[str, Any],
        variant: str,
    ) -> tuple[list[dict[str, Any]], str]:
        block = replay_fixtures.get(variant)
        if isinstance(block, dict):
            tool_calls = list(block.get("tool_calls", []) or [])
            code = str(block.get("code", "") or "")
            return tool_calls, code
        tool_calls = list(replay_fixtures.get(f"{variant}_trace", []) or [])
        code = str(replay_fixtures.get(f"{variant}_snippet", "") or "")
        return tool_calls, code

    def _run_agent_cmd(
        self,
        agent_cmd: str,
        prompt: str,
    ) -> tuple[list[dict[str, Any]], str]:
        if "{prompt}" in agent_cmd:
            cmd_str = agent_cmd.replace("{prompt}", prompt)
            proc = subprocess.run(
                cmd_str,
                shell=True,
                cwd=str(self.workspace_root),
                capture_output=True,
                text=True,
                check=False,
            )
        else:
            proc = subprocess.run(
                agent_cmd,
                shell=True,
                input=prompt,
                cwd=str(self.workspace_root),
                capture_output=True,
                text=True,
                check=False,
            )

        stdout = proc.stdout.strip()
        if not stdout:
            return [], ""

        try:
            parsed = json.loads(stdout)
            if isinstance(parsed, dict):
                return (
                    list(parsed.get("tool_calls", []) or []),
                    str(parsed.get("code", "") or ""),
                )
        except json.JSONDecodeError:
            pass

        # Fallback: JSONL transcript lines
        tool_calls: list[dict[str, Any]] = []
        code_parts: list[str] = []
        for line in stdout.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(obj, dict):
                if "tool_calls" in obj and isinstance(obj["tool_calls"], list):
                    tool_calls.extend(obj["tool_calls"])
                elif "tool" in obj or "ToolName" in obj:
                    tool_calls.append(obj)
                if "code" in obj and isinstance(obj["code"], str):
                    code_parts.append(obj["code"])
        return tool_calls, "\n".join(code_parts)

    def grade_case(
        self,
        case_spec: dict[str, Any],
        verify_graders: bool = True,
        agent_cmd: str | None = None,
    ) -> CaseGradeResult:
        case_id = str(case_spec.get("id", "unknown"))
        priority = str(case_spec.get("priority", "P0"))
        graders: list[dict[str, Any]] = list(case_spec.get("graders", []) or [])
        replay_fixtures: dict[str, Any] = dict(case_spec.get("replay_fixtures", {}) or {})

        if agent_cmd and not verify_graders:
            tool_calls, code = self._run_agent_cmd(
                agent_cmd=agent_cmd,
                prompt=str(case_spec.get("prompt", "")),
            )
            checks = [
                self.evaluate_grader(g, tool_calls=tool_calls, code=code)
                for g in graders
            ]
            return CaseGradeResult(
                case_id=case_id,
                priority=priority,
                passed=all(c.passed for c in checks),
                mode="agent_cmd",
                checks=checks,
            )

        # verify_graders mode (or default when agent_cmd is None)
        pass_calls, pass_code = self._extract_fixture_payload(replay_fixtures, "passing")
        fail_calls, fail_code = self._extract_fixture_payload(replay_fixtures, "failing")

        pass_checks = [
            self.evaluate_grader(g, tool_calls=pass_calls, code=pass_code)
            for g in graders
        ]
        fail_checks = [
            self.evaluate_grader(g, tool_calls=fail_calls, code=fail_code)
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
            else (self.workspace_root / "tools" / "evals" / "cases" / "tier1")
        )
        if not cdir.is_dir():
            return []
        loaded: list[dict[str, Any]] = []
        for yf in sorted(cdir.glob("*.yaml")):
            data = yaml.safe_load(yf.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                loaded.append(data)
        return loaded

    def detect_changed_files(self) -> list[str]:
        changed: set[str] = set()
        for cmd in (
            ["git", "diff", "--name-only"],
            ["git", "diff", "--cached", "--name-only"],
            ["git", "status", "--porcelain"],
        ):
            proc = subprocess.run(
                cmd,
                cwd=str(self.workspace_root),
                capture_output=True,
                text=True,
                check=False,
            )
            if proc.returncode != 0:
                continue
            for raw_line in proc.stdout.splitlines():
                line = raw_line.strip()
                if not line:
                    continue
                if cmd[-1] == "--porcelain":
                    parts = raw_line[3:].strip().split(" -> ")
                    path_str = parts[-1].strip()
                else:
                    path_str = line
                if path_str:
                    changed.add(path_str)
        return sorted(changed)

    def _matches_targets(self, targets: Sequence[str], changed_files: Sequence[str]) -> bool:
        for ch_file in changed_files:
            norm_file = ch_file.lstrip("./")
            for target in targets:
                norm_target = target.lstrip("./")
                if fnmatch.fnmatch(norm_file, norm_target):
                    return True
                if norm_target.endswith("/**"):
                    prefix = norm_target[:-3]
                    if norm_file == prefix or norm_file.startswith(prefix + "/"):
                        return True
                if norm_file == norm_target or norm_file.startswith(norm_target.rstrip("/") + "/"):
                    return True
        return False

    def select_cases(
        self,
        cases: Sequence[dict[str, Any]],
        priority: str | None = None,
        case_id: str | None = None,
        changed_files: Sequence[str] | None = None,
    ) -> list[dict[str, Any]]:
        selected: list[dict[str, Any]] = []
        for c in cases:
            if priority and str(c.get("priority", "")).upper() != priority.upper():
                continue
            if case_id and str(c.get("id", "")) != case_id:
                continue
            if changed_files is not None:
                targets = [str(t) for t in (c.get("targets", []) or [])]
                if not self._matches_targets(targets, changed_files):
                    continue
            selected.append(c)
        return selected

    def run_suite(
        self,
        cases_dir: Path | str | None = None,
        priority: str | None = None,
        case_id: str | None = None,
        changed_files: Sequence[str] | None = None,
        verify_graders: bool = True,
        agent_cmd: str | None = None,
    ) -> Tier1EvalReport:
        all_cases = self.load_cases(cases_dir=cases_dir)
        selected = self.select_cases(
            all_cases,
            priority=priority,
            case_id=case_id,
            changed_files=changed_files,
        )
        results = [
            self.grade_case(
                c,
                verify_graders=verify_graders if agent_cmd is None else verify_graders,
                agent_cmd=agent_cmd,
            )
            for c in selected
        ]
        passed_count = sum(1 for r in results if r.passed)
        overall_passed = all(r.passed for r in results)
        return Tier1EvalReport(
            passed=overall_passed,
            total_cases=len(results),
            passed_cases=passed_count,
            cases=results,
        )

    def audit_transcript(
        self,
        transcript_target: str | Path,
        case_id: str | None = None,
    ) -> TranscriptAuditReport:
        """Audit a real Antigravity conversation transcript against universal harness invariants and optional case."""
        path = resolve_transcript_path(transcript_target)
        conv_id = str(transcript_target)
        for part in path.parts:
            if re.match(
                r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
                part,
            ):
                conv_id = part
                break

        user_prompt, total_steps, tool_calls, sfx_code = parse_transcript_jsonl(path)

        skills_read: list[str] = []
        mcp_calls_by_tool: dict[str, int] = {}
        pngs_written: set[str] = set()
        pngs_read: set[str] = set()

        for call in tool_calls:
            tname = str(call.get("tool") or "")
            tserver = call.get("server")
            targs = call.get("args") or {}

            if tserver:
                qual = f"{tserver}:{tname}"
                mcp_calls_by_tool[qual] = mcp_calls_by_tool.get(qual, 0) + 1

            if tname == "read_file" and isinstance(targs, dict):
                rpath = str(targs.get("Path", ""))
                if rpath.endswith("SKILL.md"):
                    skill_name = Path(rpath).parent.name
                    if skill_name not in skills_read:
                        skills_read.append(skill_name)
                if rpath.lower().endswith(".png"):
                    pngs_read.add(Path(rpath).name)

            if tname == "write_file" and isinstance(targs, dict):
                wpath = str(targs.get("Path", ""))
                cnt = str(targs.get("Content", ""))
                for m_png in re.findall(r"([A-Za-z0-9_.-]+\.png)", cnt):
                    pngs_written.add(m_png)
                if wpath.lower().endswith(".png"):
                    pngs_written.add(Path(wpath).name)

        checks: list[GraderCheckResult] = []

        # 1. Universal P0: No grep/rg on .s/.sgs/.i/.inc sources for symbols
        checks.append(
            self._eval_tool_forbidden(
                {
                    "tool": "run_command",
                    "args_pattern": r"\b(grep|rg)\b.*\.(s|sgs|i|inc)\b",
                    "reason": "[P0 Symbol/Ref Policy] Do not grep .s/.sgs/.i/.inc sources; use sfx_lookup_symbol or sfx_find_references on retro-superfx_lsp",
                },
                tool_calls,
            )
        )

        # 2. Universal P0: No grep/rg on .sym/.dbg for linker addresses
        checks.append(
            self._eval_tool_forbidden(
                {
                    "tool": "run_command",
                    "args_pattern": r"\b(grep|rg)\b.*\.(sym|dbg)\b",
                    "reason": "[P0 Address Policy] Do not grep .cpu.sym or .dbg files; use sfx_resolve_address on retro-superfx_mesen",
                },
                tool_calls,
            )
        )

        # 3. Universal P1: Do not reverse-engineer tools/mesen-mcp internals during SuperFX tasks
        prompt_mentions_mcp = bool(
            re.search(r"(mesen-mcp|mcp_server|render_capture\.py|session\.py)", user_prompt, re.IGNORECASE)
        )
        edits_mcp = any(
            str(c.get("tool") or "") in ("edit_file", "write_file")
            and isinstance(c.get("args"), dict)
            and "tools/mesen-mcp/" in str(c["args"].get("Path", ""))
            for c in tool_calls
        )
        if not prompt_mentions_mcp and not edits_mcp:
            checks.append(
                self._eval_tool_forbidden(
                    {
                        "tool": "read_file",
                        "args_pattern": r"tools/mesen-mcp/mesen_mcp/",
                        "reason": "[P1 MCP Tool Hygiene] Do not read internal tools/mesen-mcp/ Python implementation during SuperFX tasks; read sfx-test-runner/SKILL.md or call retro-superfx_mesen MCP tools directly",
                    },
                    tool_calls,
                )
            )
            checks.append(
                self._eval_tool_forbidden(
                    {
                        "tool": "run_command",
                        "args_pattern": r"\b(cat|grep|rg)\b.*tools/mesen-mcp/",
                        "reason": "[P1 MCP Tool Hygiene] Do not grep/cat internal tools/mesen-mcp/ Python implementation during SuperFX tasks; read sfx-test-runner/SKILL.md or call retro-superfx_mesen MCP tools directly",
                    },
                    tool_calls,
                )
            )

        # 4. Bug Diagnosis Skill Activation Check
        is_bug_task = bool(
            re.search(
                r"\b(fix|bug|broken|disappear|glitch|wrong|failing|regression|diagnose|debug)\b",
                user_prompt,
                re.IGNORECASE,
            )
        )
        if is_bug_task:
            if "diagnosing-bugs" in skills_read:
                checks.append(
                    GraderCheckResult(
                        grader_type="skill_activated",
                        passed=True,
                        message="Activated 'diagnosing-bugs' skill on bug-fix task",
                    )
                )
            else:
                checks.append(
                    GraderCheckResult(
                        grader_type="skill_activated",
                        passed=False,
                        message="[P0 Skill Routing] Bug-fix task did not read '.agents/skills/diagnosing-bugs/SKILL.md' before investigating",
                    )
                )

        # 5. Multimodal Visual Inspection Check (if scratch PNGs were generated)
        if pngs_written:
            if pngs_read:
                checks.append(
                    GraderCheckResult(
                        grader_type="multimodal_render_inspection",
                        passed=True,
                        message=f"Inspected captured PNG(s) with read_file: {sorted(pngs_read)}",
                    )
                )
            else:
                checks.append(
                    GraderCheckResult(
                        grader_type="multimodal_render_inspection",
                        passed=False,
                        message=(
                            f"[P1 Visual Inspection] Generated PNG captures {sorted(pngs_written)} "
                            "in scratch scripts but never viewed them with read_file"
                        ),
                    )
                )

        # 6. FX3 Static Audit on any authored/edited SuperFX assembly
        if sfx_code.strip():
            checks.append(self._eval_lsp_audit_clean({}, sfx_code))

        # 7. Optional specific case graders
        if case_id:
            all_cases = self.load_cases()
            matched_cases = [c for c in all_cases if str(c.get("id")) == case_id]
            for mc in matched_cases:
                for g in mc.get("graders", []) or []:
                    checks.append(self.evaluate_grader(g, tool_calls=tool_calls, code=sfx_code))

        overall_passed = all(c.passed for c in checks)
        return TranscriptAuditReport(
            conversation_id=conv_id,
            transcript_path=str(path),
            passed=overall_passed,
            user_prompt=user_prompt,
            total_steps=total_steps,
            total_tool_calls=len(tool_calls),
            skills_read=skills_read,
            mcp_calls_by_tool=mcp_calls_by_tool,
            checks=checks,
        )


