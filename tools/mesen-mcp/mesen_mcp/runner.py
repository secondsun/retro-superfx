"""SuperFX unit test runner using headless Mesen."""

from pathlib import Path
import subprocess
from typing import Any, Dict, Optional

from mesen_mcp.session import MesenRunner, SfxTestSession
from mesen_mcp.utils import resolve_test_dir


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


def run_sfx_test(
    test_name: str,
    timeout_seconds: int = 15,
    mesen_path: Optional[str] = None,
    runner: Optional[MesenRunner] = None,
    skip_compile: bool = False,
) -> Dict[str, Any]:
    """Compile and headlessly execute a SuperFX unit test in Mesen."""
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
        status = (
            "PASS"
            if proc.returncode == 0
            and ("TEST PASSED" in proc.stdout or "All tests passed" in proc.stdout)
            else "FAIL"
        )
        return {
            "status": status,
            "test": test_name,
            "exit_code": proc.returncode,
            "output": combined_output.strip(),
            "symbols": session.symbols.symbols,
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
            "symbols": session.symbols.symbols,
        }
    except Exception as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(e),
            "output": "",
            "symbols": session.symbols.symbols,
        }


# Re-export key verification and capture APIs
from mesen_mcp.math_verifier import assert_sfx_math
from mesen_mcp.render_capture import capture_sfx_render
