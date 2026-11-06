"""SuperFX unit test runner using headless Mesen."""

from pathlib import Path
import re
import subprocess
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


def _sync_lua_labels(sym_map: Any, lua_path: Path) -> None:
    """Backward compatibility wrapper for synchronizing Lua labels."""
    if hasattr(sym_map, "get_offset"):
        from mesen_mcp.session import SymbolTable
        table = sym_map
    elif isinstance(sym_map, dict):
        from mesen_mcp.session import SymbolTable
        table = SymbolTable(symbols=sym_map)
    else:
        return

    content = lua_path.read_text(encoding="utf-8")
    import re
    var_pattern = re.compile(
        r"^(\s*(?:local\s+)?([A-Za-z0-9_]+)\s*=\s*)(0x[0-9A-Fa-f]+|\d+)(.*)$"
    )
    lines = content.splitlines()
    updated_lines = []

    for line in lines:
        m = var_pattern.match(line)
        if m:
            prefix, var_name, current_val_str, suffix = m.groups()
            if var_name.lower() in ("index", "fail_count", "i", "j", "k"):
                updated_lines.append(line)
                continue
            target_offset = table.get_offset(var_name)

            if target_offset is not None:
                target_hex = f"0x{target_offset:04X}"
                try:
                    current_val = (
                        int(current_val_str, 16)
                        if current_val_str.startswith(("0x", "0X"))
                        else int(current_val_str)
                    )
                except ValueError:
                    current_val = None

                if current_val != target_offset:
                    updated_lines.append(f"{prefix}{target_hex}{suffix}")
                else:
                    updated_lines.append(line)
            else:
                updated_lines.append(line)
        else:
            updated_lines.append(line)

    lua_path.write_text("\n".join(updated_lines) + "\n", encoding="utf-8")


def run_sfx_suite(
    timeout_seconds: int = 15,
    mesen_path: Optional[str] = None,
    runner: Optional[MesenRunner] = None,
    skip_compile: bool = False,
) -> Dict[str, Any]:
    """Execute all active SuperFX unit tests, skipping templates and WIP suites."""
    root_dir = get_repo_root()
    tests_root = root_dir / "snes-sfx-demo" / "X-GSU" / "tests"
    if not tests_root.is_dir():
        return {
            "status": "FAIL",
            "test": "all",
            "error": f"Tests root directory not found: {tests_root}",
            "output": "",
        }

    results = []
    pass_count = 0
    fail_count = 0
    skip_count = 0
    failed_tests = []
    output_lines = []

    for test_dir in sorted(tests_root.iterdir()):
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
            test_name=test_name,
            timeout_seconds=timeout_seconds,
            mesen_path=mesen_path,
            runner=runner,
            skip_compile=skip_compile,
        )
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
    """Compile and headlessly execute a SuperFX unit test in Mesen."""
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

    try:
        session = SfxTestSession.from_test_name(
            test_name, runner=runner, mesen_path=mesen_path
        )
        session.compile(skip=skip_compile)
    except FileNotFoundError as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(e),
            "output": "",
        }
    except RuntimeError as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "exit_code": 1,
            "error": "Compilation failed",
            "output": str(e),
        }

    # Locate Lua test script and synchronize symbols
    lua_script = session.test_dir / "test.lua"
    if not lua_script.is_file():
        lua_candidates = list(session.test_dir.glob("*.lua"))
        if lua_candidates:
            lua_script = lua_candidates[0]
        else:
            return {
                "status": "FAIL",
                "test": test_name,
                "error": f"No Lua test script found in {session.test_dir}",
                "output": "",
            }

    session.sync_lua_script(lua_script)

    try:
        proc = session.run_lua(lua_script, timeout_seconds=timeout_seconds)
        combined_output = proc.stdout + (("\n" + proc.stderr) if proc.stderr else "")
        upper_stdout = proc.stdout.upper()
        has_explicit_fail = "TEST FAILED" in upper_stdout or "FAIL:" in upper_stdout
        status = (
            "PASS"
            if proc.returncode == 0 and not has_explicit_fail
            else "FAIL"
        )
        return {
            "status": status,
            "test": test_name,
            "exit_code": proc.returncode,
            "output": combined_output.strip(),
        }
    except subprocess.TimeoutExpired as te:
        stdout = te.stdout or ""
        stderr = te.stderr or ""
        return {
            "status": "FAIL",
            "test": test_name,
            "exit_code": -1,
            "error": f"Test execution timed out after {timeout_seconds} seconds",
            "output": (stdout + "\n" + stderr).strip(),
        }
    except Exception as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(e),
            "output": "",
        }


# Re-export key verification, capture, and profiling APIs
from mesen_mcp.math_verifier import assert_sfx_math
from mesen_mcp.profiler import profile_sfx_execution
from mesen_mcp.render_capture import capture_sfx_render
