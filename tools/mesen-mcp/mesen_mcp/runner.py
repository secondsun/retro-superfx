import os
from pathlib import Path
import re
import subprocess
from typing import Any, Dict, Optional

from mesen_mcp.dbg_parser import parse_dbg_file
from mesen_mcp.mesen import find_mesen


def get_repo_root() -> Path:
    """Find the root directory of the retro-superfx repository."""
    # mesen_mcp is in tools/mesen-mcp/mesen_mcp -> 3 levels up is root
    return Path(__file__).resolve().parents[3]


def resolve_test_dir(test_name_or_path: str) -> Path:
    """Resolve test directory by path or name within snes-sfx-demo."""
    root_dir = get_repo_root()
    p = Path(test_name_or_path)

    if p.is_dir():
        return p.resolve()

    cand1 = root_dir / "snes-sfx-demo" / "X-GSU" / "tests" / test_name_or_path
    if cand1.is_dir():
        return cand1.resolve()

    cand2 = root_dir / "snes-sfx-demo" / "tests" / test_name_or_path
    if cand2.is_dir():
        return cand2.resolve()

    raise FileNotFoundError(f"Test directory not found: '{test_name_or_path}'")


def _sync_lua_labels(sym_map: Dict[str, int], lua_path: Path) -> None:
    """Synchronize variable assignments in test.lua with resolved symbol offsets."""
    if not lua_path.is_file():
        return

    content = lua_path.read_text(encoding="utf-8")
    var_pattern = re.compile(r"^(\s*(?:local\s+)?([A-Za-z0-9_]+)\s*=\s*)(0x[0-9A-Fa-f]+|\d+)(.*)$")
    lines = content.splitlines()
    updated_lines = []

    for line in lines:
        m = var_pattern.match(line)
        if m:
            prefix, var_name, current_val_str, suffix = m.groups()
            sym_name = var_name

            # Map alias names if needed
            test_dir_name = lua_path.parent.name.lower()
            if sym_name not in sym_map and sym_name == "OUTPUT":
                for candidate in (
                    "VECTOR_SUBTRACT_OUT",
                    "VECTOR_ADD_OUT",
                    "VECTOR_NEGATE_OUT",
                    "VECTOR_CROSS_OUT",
                    "VECTOR_TRANSFORM_OUT",
                    "vector_normalize_out",
                ):
                    if candidate in sym_map:
                        sym_name = candidate
                        break

            if sym_name in sym_map:
                target_offset = sym_map[sym_name]
                target_hex = f"0x{target_offset:04X}"
                try:
                    current_val = int(current_val_str, 16) if current_val_str.startswith(("0x", "0X")) else int(current_val_str)
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
) -> Dict[str, Any]:
    """Compile and headlessly execute a SuperFX unit test in Mesen."""
    try:
        test_dir = resolve_test_dir(test_name)
    except FileNotFoundError as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(e),
            "output": "",
        }

    # 1. Compile test ROM via make clean all
    make_res = subprocess.run(
        ["make", "-C", str(test_dir), "clean", "all"],
        capture_output=True,
        text=True,
    )
    if make_res.returncode != 0:
        return {
            "status": "FAIL",
            "test": test_name,
            "exit_code": make_res.returncode,
            "error": "Compilation failed",
            "output": make_res.stdout + "\n" + make_res.stderr,
        }

    # 2. Parse symbols from Test.dbg if generated
    dbg_file = test_dir / "Test.dbg"
    symbols_map: Dict[str, int] = {}
    if dbg_file.is_file():
        try:
            dbg_data = parse_dbg_file(dbg_file)
            for name, sym in dbg_data.symbols.items():
                symbols_map[name] = sym.offset
        except Exception:
            pass

    # Fallback to Test.cpu.sym if dbg missing
    if not symbols_map:
        sym_file = test_dir / "Test.cpu.sym"
        if sym_file.is_file():
            sym_pattern = re.compile(r"^al\s+([0-9A-Fa-f]{6})\s+\.([A-Za-z0-9_@]+)")
            with open(sym_file, "r", encoding="utf-8") as f:
                for line in f:
                    m = sym_pattern.match(line.strip())
                    if m:
                        full_val = int(m.group(1), 16)
                        symbols_map[m.group(2)] = full_val & 0xFFFF

    # 3. Locate Lua test script and synchronize symbols
    lua_script = test_dir / "test.lua"
    if not lua_script.is_file():
        lua_candidates = list(test_dir.glob("*.lua"))
        if lua_candidates:
            lua_script = lua_candidates[0]
        else:
            return {
                "status": "FAIL",
                "test": test_name,
                "error": f"No Lua test script found in {test_dir}",
                "output": make_res.stdout,
            }

    if symbols_map:
        _sync_lua_labels(symbols_map, lua_script)

    # 4. Locate ROM
    sfc_file = test_dir / "Test.sfc"
    if not sfc_file.is_file():
        return {
            "status": "FAIL",
            "test": test_name,
            "error": f"Test ROM {sfc_file} was not generated.",
            "output": make_res.stdout,
        }

    # 5. Resolve Mesen executable
    try:
        mesen_bin = find_mesen(override_path=mesen_path)
    except FileNotFoundError as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(e),
            "output": "",
        }

    # 6. Execute Mesen in testrunner mode
    cmd = [
        str(mesen_bin),
        "--testrunner",
        f"--timeout={timeout_seconds}",
        str(lua_script),
        str(sfc_file),
    ]

    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout_seconds + 5,
        )
        combined_output = proc.stdout + (("\n" + proc.stderr) if proc.stderr else "")
        status = (
            "PASS"
            if proc.returncode == 0 and ("TEST PASSED" in proc.stdout or "All tests passed" in proc.stdout)
            else "FAIL"
        )
        return {
            "status": status,
            "test": test_name,
            "exit_code": proc.returncode,
            "output": combined_output.strip(),
            "symbols": symbols_map,
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
            "symbols": symbols_map,
        }
