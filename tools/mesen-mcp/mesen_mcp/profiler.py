"""Headless SuperFX (GSU) cycle and frame profiler for Mesen CE."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from typing import Any, Dict, List, Optional, Tuple

from mesen_mcp.math_verifier import _parse_key
from mesen_mcp.session import MesenRunner, SfxTestSession, SymbolTable


def _build_input_override_lines(
    inputs: Dict[str, int],
    symbols: SymbolTable,
) -> List[str]:
    """Generate Lua statements to inject input overrides into GSURAM or registers."""
    setup_lines: List[str] = []
    for key, val in inputs.items():
        base_name, offset, reg_name = _parse_key(key)
        if reg_name is not None:
            setup_lines.append(
                f'        emu.setState({{ ["cart.coprocessor.{reg_name}"] = {val & 0xFFFF} }})'
            )
        else:
            sym_addr = symbols.get_offset(base_name)
            if sym_addr is None:
                raise ValueError(
                    f"Unresolved input symbol: '{base_name}' in inputs: '{key}'"
                )
            target_addr = (sym_addr + offset) & 0xFFFF
            size = symbols.get_size(base_name, 2)
            has_explicit_offset = offset != 0

            if not has_explicit_offset and (size >= 4 or abs(val) > 0xFFFF):
                high_word = (val >> 16) & 0xFFFF
                low_word = val & 0xFFFF
                setup_lines.append(
                    f"        emu.writeWord(0x{target_addr:04X}, {high_word}, emu.memType.gsuWorkRam)"
                )
                setup_lines.append(
                    f"        emu.writeWord(0x{(target_addr + 2) & 0xFFFF:04X}, {low_word}, emu.memType.gsuWorkRam)"
                )
            else:
                setup_lines.append(
                    f"        emu.writeWord(0x{target_addr:04X}, {val & 0xFFFF}, emu.memType.gsuWorkRam)"
                )
    return setup_lines


def _generate_profile_lua_script(
    symbols: SymbolTable,
    warmup_runs: int,
    sample_runs: int,
    inputs: Optional[Dict[str, int]],
    timeout_seconds: int,
    results_json_path: str,
) -> str:
    """Generate ephemeral Lua script for GSU cycle, hotspot, cache, and VBlank profiling."""
    setup_lines = _build_input_override_lines(inputs or {}, symbols)
    test_setup_addr = symbols.get_offset("test_setup")
    gsu_entry_addr = symbols.get_offset("GSU_Code") or 0x0000
    frame_half_addr = symbols.get_offset("GSU_FRAME_HALF")

    has_test_setup_lua = "true" if test_setup_addr is not None else "false"
    test_setup_hex = f"0x{(test_setup_addr or 0):04X}"
    gsu_entry_hex = f"0x{gsu_entry_addr:04X}"
    has_frame_half_lua = "true" if frame_half_addr is not None else "false"
    frame_half_hex = f"0x{(frame_half_addr or 0):04X}"

    escaped_results_path = results_json_path.replace("\\", "\\\\").replace('"', '\\"')
    max_gsu_cycles = max(1, timeout_seconds) * 21477270
    max_frames = max(60, timeout_seconds * 55)

    apply_inputs_body = (
        "\n".join(setup_lines) if setup_lines else "        -- no input overrides"
    )

    lua_code = f"""-- Ephemeral Mesen SFX Cycle & Frame Profiler Script
local results_path = "{escaped_results_path}"
local warmup_runs = {warmup_runs}
local sample_runs = {sample_runs}
local has_test_setup = {has_test_setup_lua}
local test_setup_addr = {test_setup_hex}
local gsu_entry_addr = {gsu_entry_hex}
local has_frame_half = {has_frame_half_lua}
local frame_half_addr = {frame_half_hex}
local max_gsu_cycles = {max_gsu_cycles}
local max_frames = {max_frames}

local gsu_cpu = emu.cpuType.gsu
local done = false
local gsu_active = false
local completed_warmups = 0
local completed_samples = 0

local last_cyc = nil
local last_pc = nil
local last_in_cache = false
local cbr = 0
local prev_was_cache = false

local run_cycles = 0
local run_cached_cycles = 0
local run_uncached_cycles = 0
local run_vblanks = 0
local run_frame_half = nil
local idle_frames = 0
local total_frames = 0
local inst_counter = 0

local pc_cycles = {{}}
local pc_execs = {{}}
local runs = {{}}

local function applyInputOverrides()
{apply_inputs_body}
end

local function writeResults(timed_out, active_r15)
    local pc_cyc_parts = {{}}
    for pc, cyc in pairs(pc_cycles) do
        table.insert(pc_cyc_parts, string.format('"0x%04X": %d', pc, cyc))
    end
    local pc_exec_parts = {{}}
    for pc, cnt in pairs(pc_execs) do
        table.insert(pc_exec_parts, string.format('"0x%04X": %d', pc, cnt))
    end
    local run_parts = {{}}
    for _, r in ipairs(runs) do
        local fh_str = (r.frame_half ~= nil) and tostring(r.frame_half) or "null"
        table.insert(run_parts, string.format(
            '{{"run": %d, "cycles": %d, "cached_cycles": %d, "uncached_cycles": %d, "vblanks": %d, "frame_half": %s}}',
            r.run, r.cycles, r.cached_cycles, r.uncached_cycles, r.vblanks, fh_str
        ))
    end
    local r15_str = (active_r15 ~= nil) and tostring(active_r15) or "null"
    local json_str = string.format(
        '{{"timed_out": %s, "active_r15": %s, "warmup_runs_completed": %d, "sample_runs_completed": %d, "runs": [%s], "pc_cycles": {{%s}}, "pc_execs": {{%s}}}}',
        timed_out and "true" or "false",
        r15_str,
        completed_warmups,
        completed_samples,
        table.concat(run_parts, ", "),
        table.concat(pc_cyc_parts, ", "),
        table.concat(pc_exec_parts, ", ")
    )
    local f = io.open(results_path, "w")
    if f then
        f:write(json_str)
        f:close()
    end
    return json_str
end

emu.addEventCallback(function()
    if done then return end
    total_frames = total_frames + 1
    if gsu_active then
        run_vblanks = run_vblanks + 1
        idle_frames = 0
    else
        idle_frames = idle_frames + 1
        if idle_frames >= 2 and (completed_warmups + completed_samples) > 0 and completed_samples < sample_runs then
            emu.setState({{
                ["cart.coprocessor.r15"] = gsu_entry_addr,
                ["cart.coprocessor.sfr.running"] = true,
                ["cart.coprocessor.stopped"] = false
            }})
            idle_frames = 0
        end
    end

    if total_frames >= max_frames then
        done = true
        local json_str = writeResults(true, last_pc or 0)
        print("__PROFILE_RESULTS_JSON_START__" .. json_str .. "__PROFILE_RESULTS_JSON_END__")
        emu.stop(1)
    end
end, emu.eventType.endFrame)

emu.addMemoryCallback(function(addr, val)
    if done then return end
    local cyc = emu.getCpuCycleCount(gsu_cpu)
    local pc = addr & 0xFFFF
    inst_counter = inst_counter + 1

    if not gsu_active then
        gsu_active = true
        run_vblanks = 0
        idle_frames = 0
        if has_frame_half then
            run_frame_half = emu.read(frame_half_addr, emu.memType.gsuWorkRam, false) & 0xFF
        else
            run_frame_half = nil
        end
        if not has_test_setup then
            applyInputOverrides()
        end
    end

    if has_test_setup and pc == test_setup_addr then
        applyInputOverrides()
    end

    if prev_was_cache then
        local st = emu.getCpuState(gsu_cpu)
        cbr = st.cacheBase or 0
        prev_was_cache = false
    end

    local is_sampling = (completed_warmups >= warmup_runs)

    if last_cyc ~= nil then
        local delta = cyc - last_cyc
        if delta > 0 and delta < 100000 then
            if is_sampling then
                run_cycles = run_cycles + delta
                if last_in_cache then
                    run_cached_cycles = run_cached_cycles + delta
                else
                    run_uncached_cycles = run_uncached_cycles + delta
                end
                pc_cycles[last_pc] = (pc_cycles[last_pc] or 0) + delta
            end
        end
    end

    if is_sampling then
        pc_execs[pc] = (pc_execs[pc] or 0) + 1
    end

    last_cyc = cyc
    last_pc = pc
    last_in_cache = (cbr ~= 0) and (((pc - cbr) & 0xFFFF) < 0x0200)

    if (inst_counter & 0xFFFF) == 0 and run_cycles >= max_gsu_cycles then
        done = true
        local json_str = writeResults(true, pc)
        print("__PROFILE_RESULTS_JSON_START__" .. json_str .. "__PROFILE_RESULTS_JSON_END__")
        emu.stop(1)
        return
    end

    if val == 0x02 then
        prev_was_cache = true
    elseif val == 0x00 then
        if is_sampling then
            -- Attribute 1 cycle to the stop instruction itself
            run_cycles = run_cycles + 1
            if last_in_cache then
                run_cached_cycles = run_cached_cycles + 1
            else
                run_uncached_cycles = run_uncached_cycles + 1
            end
            pc_cycles[pc] = (pc_cycles[pc] or 0) + 1
            completed_samples = completed_samples + 1
            table.insert(runs, {{
                run = completed_samples,
                cycles = run_cycles,
                cached_cycles = run_cached_cycles,
                uncached_cycles = run_uncached_cycles,
                vblanks = run_vblanks + 1,
                frame_half = run_frame_half
            }})
        else
            completed_warmups = completed_warmups + 1
        end

        gsu_active = false
        run_cycles = 0
        run_cached_cycles = 0
        run_uncached_cycles = 0
        run_vblanks = 0
        run_frame_half = nil
        last_cyc = nil
        last_pc = nil
        last_in_cache = false
        cbr = 0
        prev_was_cache = false

        if completed_samples >= sample_runs then
            done = true
            local json_str = writeResults(false, nil)
            print("__PROFILE_RESULTS_JSON_START__" .. json_str .. "__PROFILE_RESULTS_JSON_END__")
            emu.stop(0)
        end
    end
end, emu.callbackType.exec, 0x000000, 0xFFFFFF, emu.cpuType.gsu, emu.memType.gsuMemory)
"""
    return lua_code


def _parse_pc_key(key: str) -> int:
    """Parse a PC key string ('0x8000' or '32768') into a 16-bit integer offset."""
    k = str(key).strip()
    if k.startswith(("0x", "0X")):
        return int(k, 16) & 0xFFFF
    return int(k) & 0xFFFF


def _aggregate_profile_data(
    data: Dict[str, Any],
    symbols: SymbolTable,
    target_name: str,
    warmup_runs: int,
    sample_runs: int,
    max_cycles: Optional[int],
    top_n: int,
) -> Dict[str, Any]:
    """Aggregate raw PC cycle/execution counts and run metrics into the profiler schema."""
    timed_out = bool(data.get("timed_out", False))
    active_r15_raw = data.get("active_r15")
    active_r15: Optional[int] = (
        int(active_r15_raw) & 0xFFFF if active_r15_raw is not None else None
    )

    raw_pc_cycles = data.get("pc_cycles", {}) or {}
    raw_pc_execs = data.get("pc_execs", {}) or {}

    pc_cycles: Dict[int, int] = {
        _parse_pc_key(k): int(v) for k, v in raw_pc_cycles.items() if int(v) > 0
    }
    pc_execs: Dict[int, int] = {
        _parse_pc_key(k): int(v) for k, v in raw_pc_execs.items() if int(v) > 0
    }

    runs: List[Dict[str, Any]] = list(data.get("runs", []) or [])

    total_cycles_from_pcs = sum(pc_cycles.values())
    total_cycles_from_runs = sum(int(r.get("cycles", 0)) for r in runs)
    total_cycles = (
        total_cycles_from_runs if total_cycles_from_runs > 0 else total_cycles_from_pcs
    )

    cached_cycles = sum(int(r.get("cached_cycles", 0)) for r in runs)
    uncached_cycles = sum(int(r.get("uncached_cycles", 0)) for r in runs)
    if cached_cycles == 0 and uncached_cycles == 0 and total_cycles > 0:
        uncached_cycles = total_cycles

    num_runs = len(runs)
    if num_runs > 0:
        avg_cycles = round(total_cycles / num_runs, 2)
        min_cycles = min(int(r.get("cycles", 0)) for r in runs)
        max_cycles_obs = max(int(r.get("cycles", 0)) for r in runs)
        avg_vblanks = round(
            sum(int(r.get("vblanks", 1)) for r in runs) / num_runs, 2
        )
    else:
        avg_cycles = float(total_cycles)
        min_cycles = total_cycles
        max_cycles_obs = total_cycles
        avg_vblanks = 0.0

    cached_pct = (
        round((cached_cycles * 100.0) / total_cycles, 2)
        if total_cycles > 0
        else 0.0
    )

    # Pass breakdown (for Frame Half 0 / 1 multi-pass engines)
    pass_0_runs = [r for r in runs if r.get("frame_half") == 0]
    pass_1_runs = [r for r in runs if r.get("frame_half") == 1]
    has_frame_half = (
        len(pass_0_runs) > 0
        or len(pass_1_runs) > 0
        or symbols.get_offset("GSU_FRAME_HALF") is not None
    )

    pass_breakdown: Optional[Dict[str, Any]] = None
    if has_frame_half and num_runs > 0:
        pass_breakdown = {}
        for pass_idx, p_runs in ((0, pass_0_runs), (1, pass_1_runs)):
            if p_runs:
                p_total = sum(int(r.get("cycles", 0)) for r in p_runs)
                p_cached = sum(int(r.get("cached_cycles", 0)) for r in p_runs)
                p_vblanks = sum(int(r.get("vblanks", 1)) for r in p_runs)
                pass_breakdown[f"pass_{pass_idx}"] = {
                    "runs": len(p_runs),
                    "total_cycles": p_total,
                    "average_cycles": round(p_total / len(p_runs), 2),
                    "average_vblanks": round(p_vblanks / len(p_runs), 2),
                    "cached_execution_pct": (
                        round((p_cached * 100.0) / p_total, 2)
                        if p_total > 0
                        else 0.0
                    ),
                }

        if "pass_0" in pass_breakdown and "pass_1" in pass_breakdown:
            vblanks_per_full_frame = round(
                pass_breakdown["pass_0"]["average_vblanks"]
                + pass_breakdown["pass_1"]["average_vblanks"],
                2,
            )
        else:
            vblanks_per_full_frame = round(avg_vblanks * 2.0, 2)
    else:
        vblanks_per_full_frame = avg_vblanks

    effective_fps = (
        round(60.0 / vblanks_per_full_frame, 2)
        if vblanks_per_full_frame > 0
        else 0.0
    )

    # Aggregate hotspots by function and by label
    func_cycles: Dict[str, int] = {}
    label_cycles: Dict[Tuple[str, str], int] = {}
    label_execs: Dict[Tuple[str, str], int] = {}

    for pc, cyc in pc_cycles.items():
        func_name = symbols.resolve_function(pc)
        func_cycles[func_name] = func_cycles.get(func_name, 0) + cyc

        lbl_name, enclosing_func = symbols.resolve_label(pc)
        lbl_key = (lbl_name, enclosing_func)
        label_cycles[lbl_key] = label_cycles.get(lbl_key, 0) + cyc

    for pc, cnt in pc_execs.items():
        lbl_name, enclosing_func = symbols.resolve_label(pc)
        lbl_key = (lbl_name, enclosing_func)
        label_execs[lbl_key] = label_execs.get(lbl_key, 0) + cnt

    denom_cycles = total_cycles_from_pcs if total_cycles_from_pcs > 0 else max(1, total_cycles)

    hotspots_by_function: List[Dict[str, Any]] = []
    for func_name, cyc in sorted(
        func_cycles.items(), key=lambda item: (-item[1], item[0])
    )[: max(1, top_n)]:
        entry_offset = symbols.function_entries.get(func_name)
        if entry_offset is not None and entry_offset in pc_execs:
            call_count = pc_execs[entry_offset]
        else:
            call_count = 1 if cyc > 0 else 0
        hotspots_by_function.append(
            {
                "symbol": func_name,
                "cycles": cyc,
                "percentage_of_total": round((cyc * 100.0) / denom_cycles, 2),
                "call_count": call_count,
            }
        )

    hotspots_by_label: List[Dict[str, Any]] = []
    for (lbl_name, enclosing_func), cyc in sorted(
        label_cycles.items(), key=lambda item: (-item[1], item[0][0])
    )[: max(1, top_n)]:
        hotspots_by_label.append(
            {
                "symbol": lbl_name,
                "enclosing_function": enclosing_func,
                "cycles": cyc,
                "percentage_of_total": round((cyc * 100.0) / denom_cycles, 2),
                "executions": label_execs.get((lbl_name, enclosing_func), 0),
            }
        )

    budget_exceeded = bool(
        max_cycles is not None and avg_cycles > float(max_cycles)
    )
    passed = (not timed_out) and (not budget_exceeded) and (num_runs > 0)

    result: Dict[str, Any] = {
        "status": "PASS" if passed else "FAIL",
        "passed": passed,
        "target": target_name,
        "summary": {
            "total_cycles": total_cycles,
            "average_cycles": avg_cycles,
            "min_cycles": min_cycles,
            "max_cycles_observed": max_cycles_obs,
            "max_cycles_budget": max_cycles,
            "budget_exceeded": budget_exceeded,
            "warmup_runs": int(data.get("warmup_runs_completed", warmup_runs)),
            "sample_runs": num_runs,
            "cached_cycles": cached_cycles,
            "uncached_cycles": uncached_cycles,
            "cached_execution_pct": cached_pct,
            "vblanks_per_run": avg_vblanks,
            "vblanks_per_full_frame": vblanks_per_full_frame,
            "effective_fps": effective_fps,
            "pass_breakdown": pass_breakdown,
        },
        "runs": runs,
        "hotspots_by_function": hotspots_by_function,
        "hotspots_by_label": hotspots_by_label,
    }

    if timed_out:
        active_lbl, active_func = (
            symbols.resolve_label(active_r15)
            if active_r15 is not None
            else ("unknown", "unknown")
        )
        result["timed_out"] = True
        result["active_r15"] = (
            f"0x{active_r15:04X}" if active_r15 is not None else None
        )
        result["active_symbol"] = active_lbl
        result["active_function"] = active_func
        result["error"] = (
            f"Execution timed out (active R15={result['active_r15']}, "
            f"symbol={active_lbl}, function={active_func})"
        )
    elif budget_exceeded:
        result["error"] = (
            f"Cycle budget exceeded: average {avg_cycles} cycles > max_cycles {max_cycles}"
        )

    return result


def profile_sfx_execution(
    target_name: str,
    warmup_runs: int = 0,
    sample_runs: int = 1,
    max_cycles: Optional[int] = None,
    inputs: Optional[Dict[str, int]] = None,
    top_n: int = 15,
    timeout_seconds: int = 15,
    mesen_path: Optional[str] = None,
    runner: Optional[MesenRunner] = None,
) -> Dict[str, Any]:
    """Profile SuperFX (GSU) execution cycles, function/label hotspots, cache residency, and VBlank FPS."""
    if sample_runs < 1:
        sample_runs = 1
    if warmup_runs < 0:
        warmup_runs = 0

    try:
        session = SfxTestSession.from_test_name(
            target_name, runner=runner, mesen_path=mesen_path
        )
        if (session.test_dir / "Makefile").is_file():
            session.compile()
    except FileNotFoundError as e:
        return {
            "status": "FAIL",
            "passed": False,
            "target": target_name,
            "error": str(e),
            "summary": {},
            "runs": [],
            "hotspots_by_function": [],
            "hotspots_by_label": [],
        }
    except RuntimeError as e:
        return {
            "status": "FAIL",
            "passed": False,
            "target": target_name,
            "error": "Compilation failed",
            "output": str(e),
            "summary": {},
            "runs": [],
            "hotspots_by_function": [],
            "hotspots_by_label": [],
        }

    fd_json, results_json_path = tempfile.mkstemp(
        prefix="sfx_profile_res_", suffix=".json"
    )
    os.close(fd_json)

    try:
        with tempfile.NamedTemporaryFile(
            suffix=".lua", mode="w", encoding="utf-8", delete=False
        ) as lua_tmp:
            lua_script_path = lua_tmp.name
            lua_content = _generate_profile_lua_script(
                symbols=session.symbols,
                warmup_runs=warmup_runs,
                sample_runs=sample_runs,
                inputs=inputs,
                timeout_seconds=timeout_seconds,
                results_json_path=results_json_path,
            )
            lua_tmp.write(lua_content)
    except ValueError as ve:
        for p in (results_json_path, locals().get("lua_script_path")):
            if p and os.path.isfile(p):
                try:
                    os.remove(p)
                except OSError:
                    pass
        return {
            "status": "FAIL",
            "passed": False,
            "target": target_name,
            "error": str(ve),
            "summary": {},
            "runs": [],
            "hotspots_by_function": [],
            "hotspots_by_label": [],
        }

    def _load_profile_json(output_text: str) -> Optional[Dict[str, Any]]:
        if (
            os.path.isfile(results_json_path)
            and os.path.getsize(results_json_path) > 0
        ):
            try:
                with open(results_json_path, "r", encoding="utf-8") as f:
                    return json.load(f)
            except Exception:
                pass
        match = re.search(
            r"__PROFILE_RESULTS_JSON_START__(.*?)__PROFILE_RESULTS_JSON_END__",
            output_text,
            re.DOTALL,
        )
        if match:
            try:
                return json.loads(match.group(1))
            except Exception:
                pass
        return None

    try:
        proc = session.run_lua(
            Path(lua_script_path), timeout_seconds=timeout_seconds
        )
        combined_output = proc.stdout + (
            ("\n" + proc.stderr) if proc.stderr else ""
        )
        data = _load_profile_json(combined_output)
        if not data:
            return {
                "status": "FAIL",
                "passed": False,
                "target": target_name,
                "error": "Failed to parse profiler results from Mesen output",
                "output": combined_output.strip(),
                "summary": {},
                "runs": [],
                "hotspots_by_function": [],
                "hotspots_by_label": [],
            }

        return _aggregate_profile_data(
            data=data,
            symbols=session.symbols,
            target_name=target_name,
            warmup_runs=warmup_runs,
            sample_runs=sample_runs,
            max_cycles=max_cycles,
            top_n=top_n,
        )

    except subprocess.TimeoutExpired as te:
        stdout = (
            te.stdout.decode("utf-8", errors="replace")
            if isinstance(te.stdout, bytes)
            else (te.stdout or "")
        )
        stderr = (
            te.stderr.decode("utf-8", errors="replace")
            if isinstance(te.stderr, bytes)
            else (te.stderr or "")
        )
        combined = (stdout + "\n" + stderr).strip()
        partial_data = _load_profile_json(combined)
        if partial_data:
            partial_data["timed_out"] = True
            return _aggregate_profile_data(
                data=partial_data,
                symbols=session.symbols,
                target_name=target_name,
                warmup_runs=warmup_runs,
                sample_runs=sample_runs,
                max_cycles=max_cycles,
                top_n=top_n,
            )
        return {
            "status": "FAIL",
            "passed": False,
            "timed_out": True,
            "target": target_name,
            "active_r15": None,
            "active_symbol": None,
            "active_function": None,
            "error": f"Execution timed out after {timeout_seconds} seconds",
            "output": combined,
            "summary": {},
            "runs": [],
            "hotspots_by_function": [],
            "hotspots_by_label": [],
        }
    except Exception as e:
        return {
            "status": "FAIL",
            "passed": False,
            "target": target_name,
            "error": str(e),
            "summary": {},
            "runs": [],
            "hotspots_by_function": [],
            "hotspots_by_label": [],
        }
    finally:
        for p in (results_json_path, lua_script_path):
            try:
                if os.path.isfile(p):
                    os.remove(p)
            except OSError:
                pass

