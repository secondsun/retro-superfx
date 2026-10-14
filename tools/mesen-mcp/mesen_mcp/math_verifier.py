import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from typing import Any, Dict, Optional, Tuple

from mesen_mcp.dbg_parser import DbgData, parse_dbg_file
from mesen_mcp.mesen import find_mesen
from mesen_mcp.utils import get_repo_root, resolve_test_dir


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

    offset_match = re.match(r"^([A-Za-z0-9_]+)(?:\s*([+-])\s*(0x[0-9A-Fa-f]+|\d+))?$", key_clean)
    if offset_match:
        base_name = offset_match.group(1)
        sign = offset_match.group(2)
        offset_str = offset_match.group(3)
        if offset_str:
            offset_val = int(offset_str, 16) if offset_str.startswith(("0x", "0X")) else int(offset_str)
            if sign == "-":
                offset_val = -offset_val
        else:
            offset_val = 0
        return (base_name, offset_val, None)

    return (key_clean, 0, None)


def _resolve_symbol(
    name: str,
    symbols_map: Dict[str, int],
) -> Optional[int]:
    """Resolve symbol address from symbol map with case tolerance and aliases."""
    if name in symbols_map:
        return symbols_map[name]
    if name.upper() in symbols_map:
        return symbols_map[name.upper()]
    if name.lower() in symbols_map:
        return symbols_map[name.lower()]

    # Check aliases for OUTPUT
    if name.upper() == "OUTPUT":
        for candidate in (
            "VECTOR_SUBTRACT_OUT",
            "VECTOR_ADD_OUT",
            "VECTOR_NEGATE_OUT",
            "VECTOR_CROSS_OUT",
            "VECTOR_TRANSFORM_OUT",
            "vector_normalize_out",
        ):
            if candidate in symbols_map:
                return symbols_map[candidate]

    return None


def _estimate_symbol_sizes(dbg_data: Optional[DbgData]) -> Dict[str, int]:
    """Estimate size in bytes for symbols based on address gaps in segments."""
    size_map: Dict[str, int] = {}
    if not dbg_data:
        return size_map

    # Group symbols by segment
    by_seg: Dict[int, list] = {}
    for sym in dbg_data.all_symbols:
        if sym.seg is not None:
            by_seg.setdefault(sym.seg, []).append(sym)

    for seg_id, sym_list in by_seg.items():
        sorted_syms = sorted(sym_list, key=lambda s: s.val)
        for i, sym in enumerate(sorted_syms):
            # Look for next symbol with strictly greater val
            next_val = None
            for j in range(i + 1, len(sorted_syms)):
                if sorted_syms[j].val > sym.val:
                    next_val = sorted_syms[j].val
                    break
            if next_val is not None:
                size_map[sym.name] = next_val - sym.val
                size_map[sym.name.upper()] = next_val - sym.val
                size_map[sym.name.lower()] = next_val - sym.val

    return size_map


def _generate_lua_script(
    test_setup_addr: int,
    test_stop_addr: int,
    input_overrides: Dict[str, int],
    expected_outputs: Dict[str, int],
    symbols_map: Dict[str, int],
    symbol_sizes: Dict[str, int],
    epsilon: int,
    results_json_path: str,
) -> str:
    """Generate ephemeral Lua test harness script."""
    setup_lines = []
    for key, val in input_overrides.items():
        base_name, offset, reg_name = _parse_key(key)
        if reg_name is not None:
            setup_lines.append(f'    emu.setState({{ ["cart.coprocessor.{reg_name}"] = {val & 0xFFFF} }})')
        else:
            sym_addr = _resolve_symbol(base_name, symbols_map)
            if sym_addr is None:
                raise ValueError(f"Unresolved input symbol: '{base_name}' in overrides: '{key}'")
            target_addr = (sym_addr + offset) & 0xFFFF
            size = symbol_sizes.get(base_name, 2)
            has_explicit_offset = offset != 0

            # If 32-bit symbol without explicit offset or value requires 32 bits
            if not has_explicit_offset and (size >= 4 or abs(val) > 0xFFFF):
                high_word = (val >> 16) & 0xFFFF
                low_word = val & 0xFFFF
                setup_lines.append(f"    emu.writeWord(0x{target_addr:04X}, {high_word}, emu.memType.gsuWorkRam)")
                setup_lines.append(f"    emu.writeWord(0x{(target_addr + 2) & 0xFFFF:04X}, {low_word}, emu.memType.gsuWorkRam)")
            else:
                setup_lines.append(f"    emu.writeWord(0x{target_addr:04X}, {val & 0xFFFF}, emu.memType.gsuWorkRam)")

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
            stop_lines.append(f"    local actual_{safe_key} = emu.getState()[\"cart.coprocessor.{reg_name}\"] or 0")
        else:
            sym_addr = _resolve_symbol(base_name, symbols_map)
            if sym_addr is None:
                raise ValueError(f"Unresolved output symbol: '{base_name}' in expectations: '{key}'")
            target_addr = (sym_addr + offset) & 0xFFFF
            size = symbol_sizes.get(base_name, 2)
            has_explicit_offset = offset != 0

            if not has_explicit_offset and (size >= 4 or abs(exp_val) > 0xFFFF):
                stop_lines.append(f"    local hi_{safe_key} = emu.readWord(0x{target_addr:04X}, emu.memType.gsuWorkRam, false)")
                stop_lines.append(f"    local lo_{safe_key} = emu.readWord(0x{(target_addr + 2) & 0xFFFF:04X}, emu.memType.gsuWorkRam, false)")
                stop_lines.append(f"    local actual_{safe_key} = (hi_{safe_key} * 65536) + lo_{safe_key}")
                if exp_val < 0:
                    stop_lines.append(f"    if actual_{safe_key} >= 2147483648 then actual_{safe_key} = actual_{safe_key} - 4294967296 end")
            else:
                stop_lines.append(f"    local actual_{safe_key} = emu.readWord(0x{target_addr:04X}, emu.memType.gsuWorkRam, false)")
                if exp_val < 0:
                    stop_lines.append(f"    if actual_{safe_key} >= 32768 then actual_{safe_key} = actual_{safe_key} - 65536 end")

        stop_lines.append(f"    local diff_{safe_key} = math.abs(actual_{safe_key} - ({exp_val}))")
        stop_lines.append(f"    if diff_{safe_key} > {epsilon} then all_passed = false end")
        stop_lines.append(f"    table.insert(results_parts, string.format('\"%s\": %d', '{safe_key}', actual_{safe_key}))")
        stop_lines.append(f"    table.insert(diffs_parts, string.format('\"%s\": %d', '{safe_key}', diff_{safe_key}))")

    escaped_results_path = results_json_path.replace("\\", "\\\\").replace('"', '\\"')
    stop_lines.extend([
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
    ])

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
) -> Dict[str, Any]:
    """Assert mathematical correctness of SuperFX routines with parameterized inputs."""
    try:
        test_dir = resolve_test_dir(test_name)
    except FileNotFoundError as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(e),
            "results": {},
            "diffs": {},
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
            "results": {},
            "diffs": {},
        }

    # 2. Parse symbols from Test.dbg or fallback to Test.cpu.sym
    dbg_file = test_dir / "Test.dbg"
    symbols_map: Dict[str, int] = {}
    dbg_data: Optional[DbgData] = None
    if dbg_file.is_file():
        try:
            dbg_data = parse_dbg_file(dbg_file)
            for name, sym in dbg_data.symbols.items():
                symbols_map[name] = sym.offset
        except Exception:
            pass

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

    symbol_sizes = _estimate_symbol_sizes(dbg_data)

    # Resolve test_setup and test_stop breakpoints
    test_setup_addr = symbols_map.get("test_setup", 0x0003)
    test_stop_addr = symbols_map.get("test_stop", 0x0020)

    # 3. Locate ROM file
    sfc_file = test_dir / "Test.sfc"
    if not sfc_file.is_file():
        candidates = list(test_dir.glob("*.sfc"))
        if candidates:
            sfc_file = candidates[0]
        else:
            return {
                "status": "FAIL",
                "test": test_name,
                "error": f"Test ROM was not generated in {test_dir}",
                "results": {},
                "diffs": {},
            }

    # 4. Resolve Mesen binary
    try:
        mesen_bin = find_mesen(override_path=mesen_path)
    except FileNotFoundError as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(e),
            "results": {},
            "diffs": {},
        }

    # 5. Generate ephemeral Lua overlay script and results tempfile
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as json_tmp:
        results_json_path = json_tmp.name

    with tempfile.NamedTemporaryFile(suffix=".lua", mode="w", encoding="utf-8", delete=False) as lua_tmp:
        lua_script_path = lua_tmp.name
        try:
            lua_content = _generate_lua_script(
                test_setup_addr=test_setup_addr,
                test_stop_addr=test_stop_addr,
                input_overrides=input_overrides,
                expected_outputs=expected_outputs,
                symbols_map=symbols_map,
                symbol_sizes=symbol_sizes,
                epsilon=epsilon,
                results_json_path=results_json_path,
            )
            lua_tmp.write(lua_content)
        except ValueError as ve:
            try:
                os.remove(results_json_path)
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

    # 6. Execute Mesen headlessly
    cmd = [
        str(mesen_bin),
        "--testrunner",
        f"--timeout={timeout_seconds}",
        str(lua_script_path),
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

        # Try reading results from JSON file first
        data = None
        if os.path.isfile(results_json_path) and os.path.getsize(results_json_path) > 0:
            try:
                with open(results_json_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
            except Exception:
                pass

        # Fallback to parsing stdout
        if not data:
            match = re.search(r"__MATH_RESULTS_JSON_START__(.*?)__MATH_RESULTS_JSON_END__", combined_output)
            if match:
                try:
                    data = json.loads(match.group(1))
                except Exception:
                    pass

        if data:
            passed = bool(data.get("passed", False))
            status = "PASS" if passed else "FAIL"
            return {
                "status": status,
                "test": test_name,
                "results": data.get("results", {}),
                "diffs": data.get("diffs", {}),
            }
        else:
            return {
                "status": "FAIL",
                "test": test_name,
                "exit_code": proc.returncode,
                "error": "Failed to extract test results from emulation output",
                "output": combined_output.strip(),
                "results": {},
                "diffs": {},
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
            "results": {},
            "diffs": {},
        }
    finally:
        for p in (results_json_path, lua_script_path):
            try:
                if os.path.isfile(p):
                    os.remove(p)
            except OSError:
                pass
