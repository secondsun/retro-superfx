"""SuperFX unit test runner using headless Mesen."""

from pathlib import Path
import re
from typing import Any, Dict, Optional

from mesen_mcp.session import MesenRunner, SfxTestSession
from mesen_mcp.utils import get_repo_root, resolve_test_dir

_SFX_TEST_HEADER_RE = re.compile(r"^--\s*@sfx-test\s+(.*)$")
_STATUS_ATTR_RE = re.compile(r"\bstatus=([A-Za-z0-9_-]+)")
_REASON_ATTR_RE = re.compile(r'\breason="([^"]*)"')


def parse_sfx_test_header(test_dir: Path) -> Dict[str, str]:
    """Parse '-- @sfx-test status=<active|wip|template> [reason=\"...\"]' from Lua scripts in test_dir."""
    lua_files = []
    primary = test_dir / "test.lua"
    if primary.is_file():
        lua_files.append(primary)
    for candidate in sorted(test_dir.glob("*.lua")):
        if candidate not in lua_files and candidate.is_file():
            lua_files.append(candidate)

    for lua_file in lua_files:
        try:
            for line in lua_file.read_text(encoding="utf-8").splitlines()[:10]:
                m = _SFX_TEST_HEADER_RE.match(line.strip())
                if m:
                    attrs = m.group(1)
                    st_match = _STATUS_ATTR_RE.search(attrs)
                    rs_match = _REASON_ATTR_RE.search(attrs)
                    return {
                        "status": st_match.group(1).lower() if st_match else "active",
                        "reason": rs_match.group(1) if rs_match else "",
                    }
        except OSError:
            continue
    return {"status": "active", "reason": ""}


def _find_test_lua_script(test_dir: Path) -> Optional[Path]:
    primary = test_dir / "test.lua"
    if primary.is_file():
        return primary
    for candidate in sorted(test_dir.glob("*.lua")):
        if candidate.is_file():
            return candidate
    return None


def run_sfx_suite(
    timeout_seconds: int = 15,
    mesen_path: Optional[str] = None,
    runner: Optional[MesenRunner] = None,
    skip_compile: bool = False,
    tests_root: Optional[Path] = None,
) -> Dict[str, Any]:
    """Execute all active SuperFX unit tests, skipping templates and WIP suites."""
    resolved_root = (
        Path(tests_root).resolve()
        if tests_root is not None
        else (get_repo_root() / "snes-sfx-demo" / "X-GSU" / "tests")
    )
    if not resolved_root.is_dir():
        return {
            "status": "FAIL",
            "test": "all",
            "error": f"Tests root directory not found: {resolved_root}",
            "output": "",
        }

    results = []
    pass_count = 0
    fail_count = 0
    skip_count = 0
    failed_tests = []
    output_lines = []

    for test_dir in sorted(resolved_root.iterdir()):
        if not test_dir.is_dir():
            continue
        test_name = test_dir.name
        header_meta = parse_sfx_test_header(test_dir)
        if header_meta["status"] == "template":
            continue
        if header_meta["status"] == "wip":
            reason = header_meta["reason"] or "Work in progress"
            skip_count += 1
            res = {
                "status": "SKIP",
                "test": test_name,
                "test_status": "wip",
                "reason": reason,
                "output": f"SKIP (wip): {reason}",
            }
            results.append(res)
            output_lines.append(f"SKIP (WIP): {test_name} — {reason}")
            continue

        res = run_sfx_test(
            test_name=str(test_dir) if tests_root is not None else test_name,
            timeout_seconds=timeout_seconds,
            mesen_path=mesen_path,
            runner=runner,
            skip_compile=skip_compile,
        )
        res["test"] = test_name
        results.append(res)
        if res["status"] == "PASS":
            pass_count += 1
            output_lines.append(f"PASS: {test_name}")
        else:
            fail_count += 1
            failed_tests.append(test_name)
            err_msg = res.get("error") or res.get("output", "")
            first_line = err_msg.splitlines()[0] if err_msg else "Test failed"
            output_lines.append(f"FAIL: {test_name} ({first_line})")

    summary_banner = (
        f"SuperFX Test Suite Summary\n"
        f"Passed: {pass_count} | Failed: {fail_count} | Skipped (WIP): {skip_count}"
    )
    if failed_tests:
        summary_banner += f"\nFailed tests: {', '.join(failed_tests)}"

    full_output = "\n".join(output_lines) + "\n\n" + summary_banner

    return {
        "status": "PASS" if fail_count == 0 else "FAIL",
        "test": "all",
        "total": len(results),
        "passed": pass_count,
        "failed": fail_count,
        "skipped": skip_count,
        "failed_tests": failed_tests,
        "results": results,
        "output": full_output,
    }


def run_sfx_test(
    test_name: str,
    timeout_seconds: int = 15,
    mesen_path: Optional[str] = None,
    runner: Optional[MesenRunner] = None,
    skip_compile: bool = False,
) -> Dict[str, Any]:
    """Incrementally compile and headlessly execute a SuperFX unit test via an ephemeral synced Lua script."""
    if test_name.strip().lower() == "all":
        return run_sfx_suite(
            timeout_seconds=timeout_seconds,
            mesen_path=mesen_path,
            runner=runner,
            skip_compile=skip_compile,
        )

    try:
        test_dir = resolve_test_dir(test_name)
    except FileNotFoundError as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(e),
            "output": "",
        }

    header_meta = parse_sfx_test_header(test_dir)
    if header_meta["status"] in ("wip", "template"):
        reason = header_meta["reason"] or (
            "Scaffold template"
            if header_meta["status"] == "template"
            else "Work in progress"
        )
        return {
            "status": "SKIP",
            "test": test_name,
            "test_status": header_meta["status"],
            "reason": reason,
            "output": f"SKIP ({header_meta['status']}): {reason}",
        }

    lua_script = _find_test_lua_script(test_dir)
    if lua_script is None:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": f"No Lua test script found in {test_dir}",
            "output": "",
        }

    session = SfxTestSession(
        test_dir=test_dir,
        test_name=test_name,
        runner=runner,
        mesen_path=mesen_path,
    )

    try:
        outcome = session.run_harness(
            lambda s, _ctx: s.sync_lua_source(
                lua_script.read_text(encoding="utf-8", errors="replace")
            ),
            timeout_seconds=timeout_seconds,
            compile_mode="skip" if skip_compile else "if_stale",
        )
    except RuntimeError as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "exit_code": 1,
            "error": "Compilation failed",
            "output": str(e),
        }
    except Exception as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(e),
            "output": "",
        }

    if outcome.timed_out:
        return {
            "status": "FAIL",
            "test": test_name,
            "exit_code": -1,
            "error": f"Test execution timed out after {timeout_seconds} seconds",
            "output": outcome.output,
        }

    upper_stdout = outcome.stdout.upper()
    has_explicit_fail = "TEST FAILED" in upper_stdout or "FAIL:" in upper_stdout
    status = (
        "PASS"
        if outcome.exit_code == 0 and not has_explicit_fail
        else "FAIL"
    )
    return {
        "status": status,
        "test": test_name,
        "exit_code": outcome.exit_code,
        "output": outcome.output,
    }


from mesen_mcp.math_verifier import assert_sfx_math
from mesen_mcp.profiler import profile_sfx_execution
from mesen_mcp.render_capture import capture_sfx_render
