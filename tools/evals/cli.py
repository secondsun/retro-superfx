from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Any, Sequence

from evals.harness_lint import DEFAULT_MAX_CONTEXT_TOKENS, HarnessLinter
from evals.models import EvalScorecard
from evals.outcome_grader import OutcomeGrader
from evals.trace_grader import TraceAndSnippetGrader


DEFAULT_WORKSPACE_ROOT = Path(__file__).resolve().parents[2]


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="run-evals",
        description="Agentic Harness Evaluation Suite for retro-superfx",
    )
    parser.add_argument(
        "--tier",
        choices=("0", "1", "2", "all"),
        default="0",
        help="Evaluation tier to execute (default: 0)",
    )
    parser.add_argument(
        "--suite",
        choices=("lint", "mcp-smoke", "all"),
        default="all",
        help="Tier 0 sub-suite to execute (default: all)",
    )
    parser.add_argument(
        "--priority",
        choices=("P0", "P1", "P2"),
        default=None,
        help="Filter Tier 1/2 cases by priority",
    )
    parser.add_argument(
        "--case",
        dest="case_id",
        default=None,
        help="Run a single evaluation case by ID",
    )
    parser.add_argument(
        "--changed",
        action="store_true",
        help="Run Tier 0 plus cases matching modified git paths",
    )
    parser.add_argument(
        "--verify-graders",
        action="store_true",
        help="Verify Tier 1/2 graders against built-in pass/fail replay fixtures",
    )
    parser.add_argument(
        "--agent-cmd",
        default=None,
        help="Live agent command template for Tier 1/2 execution",
    )
    parser.add_argument(
        "--save-baseline",
        type=Path,
        default=None,
        help="Write the JSON evaluation scorecard to the specified path",
    )
    parser.add_argument(
        "--compare",
        type=Path,
        default=None,
        help="Compare current run against a saved JSON baseline scorecard",
    )
    parser.add_argument(
        "--max-context-tokens",
        type=int,
        default=DEFAULT_MAX_CONTEXT_TOKENS,
        help=f"Maximum always-loaded context token budget (default: {DEFAULT_MAX_CONTEXT_TOKENS})",
    )
    parser.add_argument(
        "--workspace-root",
        type=Path,
        default=DEFAULT_WORKSPACE_ROOT,
        help="Path to the retro-superfx workspace root",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Emit machine-readable JSON output to stdout",
    )
    return parser


def _run_tier0(
    workspace_root: Path,
    suite: str,
    max_context_tokens: int,
) -> tuple[bool, dict[str, Any]]:
    tier0_results: dict[str, Any] = {}
    overall_passed = True

    if suite in ("lint", "all"):
        linter = HarnessLinter()
        lint_report = linter.lint_workspace(
            workspace_root=workspace_root,
            max_context_tokens=max_context_tokens,
        )
        tier0_results["lint"] = lint_report.to_dict()
        if not lint_report.passed:
            overall_passed = False

    if suite in ("mcp-smoke", "all"):
        try:
            from evals.mcp_smoke import McpSmokeVerifier  # type: ignore[import-not-found]
        except ImportError:
            if suite == "mcp-smoke":
                tier0_results["mcp_smoke"] = {
                    "passed": False,
                    "skipped": True,
                    "message": "McpSmokeVerifier not installed yet",
                }
                overall_passed = False
        else:
            verifier = McpSmokeVerifier(workspace_root=workspace_root)
            smoke_report = verifier.verify()
            smoke_dict = (
                smoke_report.to_dict()
                if hasattr(smoke_report, "to_dict")
                else dict(smoke_report)
            )
            tier0_results["mcp_smoke"] = smoke_dict
            if not smoke_dict.get("passed", False):
                overall_passed = False

    return overall_passed, tier0_results


def _format_human_summary(scorecard: EvalScorecard, comparison: dict[str, Any] | None) -> str:
    lines: list[str] = []
    lint_data = scorecard.tier0.get("lint")
    if lint_data is not None:
        status = "PASS" if lint_data["passed"] else "FAIL"
        budget = lint_data["context_budget"]
        lines.append(
            f"[Tier 0 / lint] {status} — "
            f"skills={lint_data['checked_skills']}, "
            f"symlinks={lint_data['checked_symlinks']}, "
            f"links={lint_data['checked_links']}, "
            f"context_tokens={budget['estimated_tokens']}/{budget['max_tokens']} "
            f"({budget['total_chars']} chars)"
        )
        for issue in lint_data.get("issues", []):
            loc = f" ({issue['path']})" if issue.get("path") else ""
            lines.append(f"  - [{issue['code']}]{loc}: {issue['message']}")

    smoke_data = scorecard.tier0.get("mcp_smoke")
    if smoke_data is not None:
        status = "PASS" if smoke_data.get("passed") else "FAIL"
        lines.append(f"[Tier 0 / mcp-smoke] {status}")
        for issue in smoke_data.get("issues", []):
            msg = issue.get("message", str(issue)) if isinstance(issue, dict) else str(issue)
            lines.append(f"  - {msg}")

    if scorecard.tier1:
        t1_status = "PASS" if scorecard.tier1.get("passed") else "FAIL"
        t1_passed = scorecard.tier1.get("passed_cases", 0)
        t1_total = scorecard.tier1.get("total_cases", 0)
        lines.append(
            f"[Tier 1] {t1_status} — {t1_passed}/{t1_total} cases passed"
        )
        for case_item in scorecard.tier1.get("cases", []):
            c_status = "PASS" if case_item.get("passed") else "FAIL"
            lines.append(
                f"  - [{case_item.get('priority', 'P0')}] {case_item.get('case_id')}: {c_status}"
            )
            if not case_item.get("passed"):
                for chk in case_item.get("checks", []):
                    if not chk.get("passed"):
                        lines.append(f"      * ({chk.get('grader_type')}) {chk.get('message')}")

    if scorecard.tier2:
        t2_status = "PASS" if scorecard.tier2.get("passed") else "FAIL"
        t2_passed = scorecard.tier2.get("passed_cases", 0)
        t2_total = scorecard.tier2.get("total_cases", 0)
        lines.append(
            f"[Tier 2] {t2_status} — {t2_passed}/{t2_total} cases passed"
        )
        for case_item in scorecard.tier2.get("cases", []):
            c_status = "PASS" if case_item.get("passed") else "FAIL"
            lines.append(
                f"  - [{case_item.get('priority', 'P1')}] {case_item.get('case_id')}: {c_status}"
            )
            if not case_item.get("passed"):
                for chk in case_item.get("checks", []):
                    if not chk.get("passed"):
                        lines.append(f"      * ({chk.get('grader_type')}) {chk.get('message')}")

    if comparison is not None:
        lines.append(
            f"[Baseline Compare] token_delta={comparison.get('token_delta', 0):+d}, "
            f"tier1_pass_rate_delta={comparison.get('tier1_pass_rate_delta', 0.0):+.2%}, "
            f"tier2_pass_rate_delta={comparison.get('tier2_pass_rate_delta', 0.0):+.2%}, "
            f"passed_changed={comparison.get('baseline_passed')} -> {comparison.get('current_passed')}"
        )

    overall = "PASS" if scorecard.passed else "FAIL"
    lines.append(f"Overall: {overall}")
    return "\n".join(lines)


def _extract_pass_rate(tier_data: dict[str, Any]) -> float:
    if not tier_data:
        return 0.0
    if "pass_rate" in tier_data:
        return float(tier_data["pass_rate"])
    total = int(tier_data.get("total_cases", 0))
    if total <= 0:
        return 1.0 if tier_data.get("passed", False) else 0.0
    return float(tier_data.get("passed_cases", 0)) / total


def _compare_scorecards(baseline: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    base_tokens = (
        baseline.get("tier0", {})
        .get("lint", {})
        .get("context_budget", {})
        .get("estimated_tokens", 0)
    )
    curr_tokens = (
        current.get("tier0", {})
        .get("lint", {})
        .get("context_budget", {})
        .get("estimated_tokens", 0)
    )
    base_t1_rate = _extract_pass_rate(baseline.get("tier1", {}))
    curr_t1_rate = _extract_pass_rate(current.get("tier1", {}))
    base_t2_rate = _extract_pass_rate(baseline.get("tier2", {}))
    curr_t2_rate = _extract_pass_rate(current.get("tier2", {}))
    return {
        "baseline_passed": baseline.get("passed"),
        "current_passed": current.get("passed"),
        "baseline_tokens": base_tokens,
        "current_tokens": curr_tokens,
        "token_delta": curr_tokens - base_tokens,
        "baseline_tier1_pass_rate": round(base_t1_rate, 4),
        "current_tier1_pass_rate": round(curr_t1_rate, 4),
        "tier1_pass_rate_delta": round(curr_t1_rate - base_t1_rate, 4),
        "baseline_tier2_pass_rate": round(base_t2_rate, 4),
        "current_tier2_pass_rate": round(curr_t2_rate, 4),
        "tier2_pass_rate_delta": round(curr_t2_rate - base_t2_rate, 4),
    }


def run_evals(argv: Sequence[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)
    workspace_root = args.workspace_root.resolve()

    overall_passed = True
    tier0_data: dict[str, Any] = {}
    tier1_data: dict[str, Any] = {}
    tier2_data: dict[str, Any] = {}

    run_t0 = args.tier in ("0", "all") or args.changed
    if run_t0:
        t0_passed, tier0_data = _run_tier0(
            workspace_root=workspace_root,
            suite=args.suite,
            max_context_tokens=args.max_context_tokens,
        )
        if not t0_passed:
            overall_passed = False

    run_t1 = args.tier in ("1", "all") or args.changed or (args.case_id is not None and args.tier == "1")
    if run_t1:
        grader = TraceAndSnippetGrader(workspace_root=workspace_root)
        changed_files = grader.detect_changed_files() if args.changed else None
        verify_mode = args.verify_graders or (args.agent_cmd is None)
        t1_report = grader.run_suite(
            priority=args.priority,
            case_id=args.case_id,
            changed_files=changed_files,
            verify_graders=verify_mode,
            agent_cmd=args.agent_cmd,
        )
        if not args.changed or t1_report.total_cases > 0 or args.tier in ("1", "all"):
            tier1_data = t1_report.to_dict()
            if not t1_report.passed:
                overall_passed = False

    run_t2 = args.tier in ("2", "all") or args.changed or (args.case_id is not None and args.tier == "2")
    if run_t2:
        t2_grader = OutcomeGrader(workspace_root=workspace_root)
        changed_files = t2_grader.detect_changed_files() if args.changed else None
        verify_mode = args.verify_graders or (args.agent_cmd is None)
        t2_report = t2_grader.run_suite(
            priority=args.priority,
            case_id=args.case_id,
            changed_files=changed_files,
            verify_graders=verify_mode,
            agent_cmd=args.agent_cmd,
        )
        if not args.changed or t2_report.total_cases > 0 or args.tier in ("2", "all"):
            tier2_data = t2_report.to_dict()
            if not t2_report.passed:
                overall_passed = False

    scorecard = EvalScorecard(
        passed=overall_passed,
        tier0=tier0_data,
        tier1=tier1_data,
        tier2=tier2_data,
    )
    payload = scorecard.to_dict()

    if args.save_baseline is not None:
        args.save_baseline.parent.mkdir(parents=True, exist_ok=True)
        args.save_baseline.write_text(
            json.dumps(payload, indent=2) + "\n", encoding="utf-8"
        )

    comparison: dict[str, Any] | None = None
    if args.compare is not None and args.compare.is_file():
        baseline_payload = json.loads(args.compare.read_text(encoding="utf-8"))
        comparison = _compare_scorecards(baseline_payload, payload)
        payload["comparison"] = comparison

    if args.json:
        print(json.dumps(payload, indent=2))
    else:
        print(_format_human_summary(scorecard, comparison))

    return 0 if scorecard.passed else 1


def main() -> None:
    sys.exit(run_evals())


if __name__ == "__main__":
    main()

