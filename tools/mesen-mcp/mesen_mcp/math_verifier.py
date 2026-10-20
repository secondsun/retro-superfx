"""Mathematical verification and parameterized assertions for SuperFX routines."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from typing import Any, Dict, Optional, Tuple

from mesen_mcp.session import MesenRunner, SfxTestSession, SymbolTable
from mesen_mcp.utils import resolve_test_dir


def _parse_key(key: str) -> Tuple[str, int, Optional[str]]:
    """Parse a symbol or register key.

    Returns:
        (base_name, offset, register_name_if_register)
    """
    key_clean = key.strip()
    reg_match = re.match(r"^[rR]([0-9]|1[0-5])$", key_clean)
    if reg_match:
        reg_num = int(reg_match.group(1))
        return (f"r{reg_num}", 0, f"r{reg_num}")

    offset_match = re.match(
        r"^([A-Za-z0-9_]+)(?:\s*([+-])\s*(0x[0-9A-Fa-f]+|\d+))?$", key_clean
    )
    if offset_match:
        base_name = offset_match.group(1)
        sign = offset_match.group(2)
        offset_str = offset_match.group(3)
        if offset_str:
            offset_val = (
                int(offset_str, 16)
                if offset_str.startswith(("0x", "0X"))
                else int(offset_str)
            )
            if sign == "-":
                offset_val = -offset_val
        else:
            offset_val = 0
        return (base_name, offset_val, None)

    return (key_clean, 0, None)


def _generate_lua_script(
    test_setup_addr: int,
    test_stop_addr: int,
    input_overrides: Dict[str, int],
    expected_outputs: Dict[str, int],
    symbols: SymbolTable,
    epsilon: int,
    results_json_path: str,
) -> str:
    """Generate ephemeral Lua test harness script."""
    setup_lines = []
    for key, val in input_overrides.items():
        base_name, offset, reg_name = _parse_key(key)
        if reg_name is not None:
            setup_lines.append(
                f'    emu.setState({{ ["cart.coprocessor.{reg_name}"] = {val & 0xFFFF} }})'
            )
        else:
            sym_addr = symbols.get_offset(base_name)
            if sym_addr is None:
                raise ValueError(
                    f"Unresolved input symbol: '{base_name}' in overrides: '{key}'"
                )
            target_addr = (sym_addr + offset) & 0xFFFF
            size = symbols.get_size(base_name, 2)
            has_explicit_offset = offset != 0

            # If 32-bit symbol without explicit offset or value requires 32 bits
            if not has_explicit_offset and (size >= 4 or abs(val) > 0xFFFF):
                high_word = (val >> 16) & 0xFFFF
                low_word = val & 0xFFFF
                setup_lines.append(
                    f"    emu.writeWord(0x{target_addr:04X}, {high_word}, emu.memType.gsuWorkRam)"
                )
                setup_lines.append(
                    f"    emu.writeWord(0x{(target_addr + 2) & 0xFFFF:04X}, {low_word}, emu.memType.gsuWorkRam)"
                )
            else:
                setup_lines.append(
                    f"    emu.writeWord(0x{target_addr:04X}, {val & 0xFFFF}, emu.memType.gsuWorkRam)"
                )

    stop_lines = [
        "    local results = {}",
        "    local diffs = {}",
        "    local results_parts = {}",
        "    local diffs_parts = {}",
        "    local all_passed = true",
    ]

    for key, exp_val in expected_outputs.items():
        base_name, offset, reg_name = _parse_key(key)
        safe_key = key.replace('"', '\\"')

        if reg_name is not None:
            stop_lines.append(
                f'    local actual_{safe_key} = emu.getState()["cart.coprocessor.{reg_name}"] or 0'
            )
        else:
            sym_addr = symbols.get_offset(base_name)
            if sym_addr is None:
                raise ValueError(
                    f"Unresolved output symbol: '{base_name}' in expectations: '{key}'"
                )
            target_addr = (sym_addr + offset) & 0xFFFF
            size = symbols.get_size(base_name, 2)
            has_explicit_offset = offset != 0

            if not has_explicit_offset and (size >= 4 or abs(exp_val) > 0xFFFF):
                stop_lines.append(
                    f"    local hi_{safe_key} = emu.readWord(0x{target_addr:04X}, emu.memType.gsuWorkRam, false)"
                )
                stop_lines.append(
                    f"    local lo_{safe_key} = emu.readWord(0x{(target_addr + 2) & 0xFFFF:04X}, emu.memType.gsuWorkRam, false)"
                )
                stop_lines.append(
                    f"    local actual_{safe_key} = (hi_{safe_key} * 65536) + lo_{safe_key}"
                )
                if exp_val < 0:
                    stop_lines.append(
                        f"    if actual_{safe_key} >= 2147483648 then actual_{safe_key} = actual_{safe_key} - 4294967296 end"
                    )
            else:
                stop_lines.append(
                    f"    local actual_{safe_key} = emu.readWord(0x{target_addr:04X}, emu.memType.gsuWorkRam, false)"
                )
                if exp_val < 0:
                    stop_lines.append(
                        f"    if actual_{safe_key} >= 32768 then actual_{safe_key} = actual_{safe_key} - 65536 end"
                    )

        stop_lines.append(
            f"    local diff_{safe_key} = math.abs(actual_{safe_key} - ({exp_val}))"
        )
        stop_lines.append(
            f"    if diff_{safe_key} > {epsilon} then all_passed = false end"
        )
        stop_lines.append(
            f"    table.insert(results_parts, string.format('\"%s\": %d', '{safe_key}', actual_{safe_key}))"
        )
        stop_lines.append(
            f"    table.insert(diffs_parts, string.format('\"%s\": %d', '{safe_key}', diff_{safe_key}))"
        )

    escaped_results_path = results_json_path.replace("\\", "\\\\").replace('"', '\\"')
    stop_lines.extend(
        [
            f'    local json_str = string.format(\'{{"passed": %s, "results": {{%s}}, "diffs": {{%s}}}}\', all_passed and "true" or "false", table.concat(results_parts, ", "), table.concat(diffs_parts, ", "))',
            f'    local f = io.open("{escaped_results_path}", "w")',
            "    if f then",
            "        f:write(json_str)",
            "        f:close()",
            "    end",
            '    print("__MATH_RESULTS_JSON_START__" .. json_str .. "__MATH_RESULTS_JSON_END__")',
            "    if all_passed then",
            '        print("TEST PASSED: SFX Math Assertions Satisfied")',
            "        emu.stop(0)",
            "    else",
            '        print("TEST FAILED: SFX Math Assertions Exceeded Epsilon")',
            "        emu.stop(1)",
            "    end",
        ]
    )

    lua_code = f"""-- Ephemeral Mesen SFX Math Assertion Script
local test_setup = 0x{test_setup_addr:04X}
local test_stop  = 0x{test_stop_addr:04X}

function onTestSetup(address, value)
{os.linesep.join(setup_lines)}
end

function onTestStop(address, value)
{os.linesep.join(stop_lines)}
end

emu.addMemoryCallback(onTestSetup, emu.callbackType.exec, test_setup, test_setup, 4, emu.memType.gsuWorkRam)
emu.addMemoryCallback(onTestStop,  emu.callbackType.exec, test_stop,  test_stop,  4, emu.memType.gsuWorkRam)
"""
    return lua_code


def assert_sfx_math(
    test_name: str,
    input_overrides: Dict[str, int],
    expected_outputs: Dict[str, int],
    epsilon: int = 0,
    timeout_seconds: int = 15,
    mesen_path: Optional[str] = None,
    runner: Optional[MesenRunner] = None,
) -> Dict[str, Any]:
    """Assert mathematical correctness of SuperFX routines with parameterized inputs."""
    try:
        session = SfxTestSession.from_test_name(
            test_name, runner=runner, mesen_path=mesen_path
        )
        session.compile()
    except FileNotFoundError as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(e),
            "results": {},
            "diffs": {},
        }
    except RuntimeError as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "exit_code": 1,
            "error": "Compilation failed",
            "output": str(e),
            "results": {},
            "diffs": {},
        }

    # Resolve test_setup and test_stop breakpoints
    test_setup_addr = session.symbols.get_offset("test_setup") or 0x0003
    test_stop_addr = session.symbols.get_offset("test_stop") or 0x0020

    # Create temporary files for results JSON and ephemeral Lua script
    fd_json, results_json_path = tempfile.mkstemp(
        prefix="sfx_math_res_", suffix=".json"
    )
    os.close(fd_json)

    try:
        with tempfile.NamedTemporaryFile(
            suffix=".lua", mode="w", encoding="utf-8", delete=False
        ) as lua_tmp:
            lua_script_path = lua_tmp.name
            lua_content = _generate_lua_script(
                test_setup_addr=test_setup_addr,
                test_stop_addr=test_stop_addr,
                input_overrides=input_overrides,
                expected_outputs=expected_outputs,
                symbols=session.symbols,
                epsilon=epsilon,
                results_json_path=results_json_path,
            )
            lua_tmp.write(lua_content)
    except ValueError as ve:
        try:
            os.remove(results_json_path)
            if "lua_script_path" in locals():
                os.remove(lua_script_path)
        except OSError:
            pass
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(ve),
            "results": {},
            "diffs": {},
        }

    try:
        proc = session.run_lua(
            Path(lua_script_path), timeout_seconds=timeout_seconds
        )
        combined_output = proc.stdout + (("\n" + proc.stderr) if proc.stderr else "")

        # Try reading results from JSON file first
        data = None
        if (
            os.path.isfile(results_json_path)
            and os.path.getsize(results_json_path) > 0
        ):
            try:
                with open(results_json_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
            except Exception:
                pass

        # Fallback to parsing stdout
        if not data:
            match = re.search(
                r"__MATH_RESULTS_JSON_START__(.*?)__MATH_RESULTS_JSON_END__",
                combined_output,
            )
            if match:
                try:
                    data = json.loads(match.group(1))
                except Exception:
                    pass

        if not data:
            return {
                "status": "FAIL",
                "test": test_name,
                "error": "Failed to parse math assertion results from Mesen output",
                "output": combined_output.strip(),
                "results": {},
                "diffs": {},
            }

        passed = data.get("passed", False)
        return {
            "status": "PASS" if passed else "FAIL",
            "test": test_name,
            "exit_code": proc.returncode,
            "results": data.get("results", {}),
            "diffs": data.get("diffs", {}),
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
            "error": f"Execution timed out after {timeout_seconds} seconds",
            "output": (stdout + "\n" + stderr).strip(),
            "results": {},
            "diffs": {},
            "symbols": session.symbols.symbols,
        }
    except Exception as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(e),
            "results": {},
            "diffs": {},
            "symbols": session.symbols.symbols,
        }
    finally:
        for p in (results_json_path, lua_script_path):
            try:
                if os.path.isfile(p):
                    os.remove(p)
            except OSError:
                pass
