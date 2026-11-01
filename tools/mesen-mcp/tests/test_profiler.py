import json
from pathlib import Path
import subprocess
import pytest

from mesen_mcp.dbg_parser import parse_dbg_string
from mesen_mcp.profiler import (
    _generate_profile_lua_script,
    profile_sfx_execution,
)
from mesen_mcp.session import FakeMesenRunner, SfxTestSession, SymbolTable


SAMPLE_PROFILE_DBG = """\
version\tmajor=2,minor=0
file\tid=0,name="Test.sgs",size=1200,mtime=0x6ACEE723,mod=0
seg\tid=0,name="GSUCODE",start=0x038000,size=0x0100,addrsize=absolute,type=ro,oname="Test.sfc",ooffs=0
seg\tid=1,name="GSURAM",start=0x700000,size=0x0100,addrsize=absolute,type=rw
scope\tid=0,name="",mod=0,size=256
scope\tid=1,name="gsu_sqrt32",mod=0,type=scope,size=64,parent=0
scope\tid=2,name="helper_fn",mod=0,type=scope,size=48,parent=0
sym\tid=0,name="GSU_Code",addrsize=absolute,scope=0,def=10,val=0x038000,seg=0,type=lab
sym\tid=1,name="test_setup",addrsize=absolute,scope=0,def=12,val=0x038004,seg=0,type=lab
sym\tid=2,name="test_stop",addrsize=absolute,scope=0,def=18,val=0x038020,seg=0,type=lab
sym\tid=3,name="gsu_sqrt32_function",addrsize=absolute,scope=0,def=25,val=0x038030,seg=0,type=lab
sym\tid=4,name="square_root_loop",addrsize=absolute,scope=1,def=30,val=0x038040,seg=0,type=lab
sym\tid=5,name="@LOCAL-MACRO_SYMBOL-0001",addrsize=absolute,scope=1,def=35,val=0x038048,seg=0,type=lab
sym\tid=6,name="sqrt_done",addrsize=absolute,scope=1,def=42,val=0x038060,seg=0,type=lab
sym\tid=7,name="helper_fn_function",addrsize=absolute,scope=0,def=50,val=0x038070,seg=0,type=lab
sym\tid=8,name="helper_loop",addrsize=absolute,scope=2,def=55,val=0x038080,seg=0,type=lab
sym\tid=9,name="INPUT",addrsize=absolute,scope=0,def=5,val=0x700010,seg=1,type=lab
sym\tid=10,name="OUTPUT",addrsize=absolute,scope=0,def=6,val=0x700014,seg=1,type=lab
sym\tid=11,name="GSU_FRAME_HALF",addrsize=absolute,scope=0,def=7,val=0x700018,seg=1,type=lab
"""


def _make_symbol_table_from_dbg(dbg_text: str) -> SymbolTable:
    dbg_data = parse_dbg_string(dbg_text)
    symbols_map = {name: sym.offset for name, sym in dbg_data.symbols.items()}
    label_map = {
        name: sym.offset
        for name, sym in dbg_data.symbols.items()
        if sym.type == "lab"
    }
    sizes = SymbolTable._estimate_symbol_sizes(dbg_data)
    func_spans, lbl_spans, func_entries = SymbolTable._build_dbg_spans(dbg_data)
    return SymbolTable(
        symbols=symbols_map,
        sizes=sizes,
        label_symbols=label_map,
        function_spans=func_spans,
        label_spans=lbl_spans,
        function_entries=func_entries,
    )


def test_symbol_table_dbg_function_and_label_spans():
    table = _make_symbol_table_from_dbg(SAMPLE_PROFILE_DBG)

    # Function resolution
    assert table.resolve_function(0x8000) == "GSU_Code"
    assert table.resolve_function(0x8015) == "GSU_Code"
    assert table.resolve_function(0x8030) == "gsu_sqrt32"
    assert table.resolve_function(0x8048) == "gsu_sqrt32"
    assert table.resolve_function(0x8070) == "helper_fn"
    assert table.resolve_function(0x8085) == "helper_fn"

    # Label resolution (Note: @LOCAL-MACRO_SYMBOL-0001 at 0x8048 is filtered out,
    # so 0x8048 resolves to square_root_loop!)
    assert table.resolve_label(0x8004) == ("test_setup", "GSU_Code")
    assert table.resolve_label(0x8032) == ("gsu_sqrt32", "gsu_sqrt32")
    assert table.resolve_label(0x8042) == ("square_root_loop", "gsu_sqrt32")
    assert table.resolve_label(0x804A) == ("square_root_loop", "gsu_sqrt32")
    assert table.resolve_label(0x8062) == ("sqrt_done", "gsu_sqrt32")
    assert table.resolve_label(0x8082) == ("helper_loop", "helper_fn")

    # Function entry offsets
    assert table.function_entries["GSU_Code"] == 0x8000
    assert table.function_entries["gsu_sqrt32"] == 0x8030
    assert table.function_entries["helper_fn"] == 0x8070


def test_symbol_table_fallback_spans_from_dict():
    table = SymbolTable(
        symbols={
            "GSU_Code": 0x0000,
            "test_call": 0x0008,
            "my_math_function": 0x0020,
            "inner_loop": 0x0030,
        }
    )
    assert table.resolve_function(0x0004) == "GSU_Code"
    assert table.resolve_function(0x0025) == "my_math"
    assert table.resolve_label(0x0034) == ("inner_loop", "my_math")


def test_profiler_with_fake_runner_two_pass_fps_and_hotspots(tmp_path):
    test_dir = tmp_path / "fake_iso"
    test_dir.mkdir()
    (test_dir / "SuperFX.sfc").write_bytes(b"\x00" * 64)
    (test_dir / "Test.dbg").write_text(SAMPLE_PROFILE_DBG, encoding="utf-8")

    fake_json = {
        "timed_out": False,
        "active_r15": None,
        "warmup_runs_completed": 1,
        "sample_runs_completed": 2,
        "runs": [
            {
                "run": 1,
                "cycles": 1000,
                "cached_cycles": 600,
                "uncached_cycles": 400,
                "vblanks": 2,
                "frame_half": 0,
            },
            {
                "run": 2,
                "cycles": 800,
                "cached_cycles": 600,
                "uncached_cycles": 200,
                "vblanks": 1,
                "frame_half": 1,
            },
        ],
        "pc_cycles": {
            "0x8004": 100,
            "0x8030": 200,
            "0x8042": 900,
            "0x8080": 600,
        },
        "pc_execs": {
            "0x8000": 2,
            "0x8004": 2,
            "0x8030": 4,
            "0x8042": 120,
            "0x8070": 10,
            "0x8080": 80,
        },
    }
    stdout = (
        f"__PROFILE_RESULTS_JSON_START__{json.dumps(fake_json)}__PROFILE_RESULTS_JSON_END__\n"
    )
    runner = FakeMesenRunner(stdout=stdout, returncode=0)

    res = profile_sfx_execution(
        target_name=str(test_dir),
        warmup_runs=1,
        sample_runs=2,
        max_cycles=1200,
        runner=runner,
    )

    assert res["status"] == "PASS"
    assert res["passed"] is True
    summary = res["summary"]
    assert summary["total_cycles"] == 1800
    assert summary["average_cycles"] == 900.0
    assert summary["min_cycles"] == 800
    assert summary["max_cycles_observed"] == 1000
    assert summary["budget_exceeded"] is False
    assert summary["cached_cycles"] == 1200
    assert summary["uncached_cycles"] == 600
    assert summary["cached_execution_pct"] == 66.67
    assert summary["vblanks_per_run"] == 1.5
    assert summary["vblanks_per_full_frame"] == 3.0
    assert summary["effective_fps"] == 20.0
    assert summary["pass_breakdown"]["pass_0"]["average_cycles"] == 1000.0
    assert summary["pass_breakdown"]["pass_1"]["average_cycles"] == 800.0

    # Hotspots by function
    funcs = res["hotspots_by_function"]
    assert funcs[0]["symbol"] == "gsu_sqrt32"
    assert funcs[0]["cycles"] == 1100
    assert funcs[0]["call_count"] == 4
    assert funcs[1]["symbol"] == "helper_fn"
    assert funcs[1]["cycles"] == 600
    assert funcs[1]["call_count"] == 10
    assert funcs[2]["symbol"] == "GSU_Code"
    assert funcs[2]["cycles"] == 100
    assert funcs[2]["call_count"] == 2

    # Hotspots by label
    labels = res["hotspots_by_label"]
    assert labels[0]["symbol"] == "square_root_loop"
    assert labels[0]["enclosing_function"] == "gsu_sqrt32"
    assert labels[0]["cycles"] == 900
    assert labels[0]["executions"] == 120


def test_profiler_max_cycles_budget_exceeded(tmp_path):
    test_dir = tmp_path / "fake_budget"
    test_dir.mkdir()
    (test_dir / "Test.sfc").write_bytes(b"\x00" * 64)
    (test_dir / "Test.dbg").write_text(SAMPLE_PROFILE_DBG, encoding="utf-8")

    fake_json = {
        "timed_out": False,
        "active_r15": None,
        "warmup_runs_completed": 0,
        "sample_runs_completed": 1,
        "runs": [
            {
                "run": 1,
                "cycles": 2500,
                "cached_cycles": 0,
                "uncached_cycles": 2500,
                "vblanks": 1,
                "frame_half": None,
            }
        ],
        "pc_cycles": {"0x8040": 2500},
        "pc_execs": {"0x8030": 1, "0x8040": 250},
    }
    runner = FakeMesenRunner(
        stdout=f"__PROFILE_RESULTS_JSON_START__{json.dumps(fake_json)}__PROFILE_RESULTS_JSON_END__",
        returncode=0,
    )

    res = profile_sfx_execution(
        target_name=str(test_dir),
        max_cycles=1500,
        runner=runner,
    )
    assert res["status"] == "FAIL"
    assert res["passed"] is False
    assert res["summary"]["budget_exceeded"] is True
    assert "budget exceeded" in res["error"].lower()


def test_profiler_timeout_returns_partial_hotspots_and_active_r15(tmp_path):
    test_dir = tmp_path / "fake_timeout"
    test_dir.mkdir()
    (test_dir / "Test.sfc").write_bytes(b"\x00" * 64)
    (test_dir / "Test.dbg").write_text(SAMPLE_PROFILE_DBG, encoding="utf-8")

    partial_json = {
        "timed_out": True,
        "active_r15": 0x8044,
        "warmup_runs_completed": 0,
        "sample_runs_completed": 0,
        "runs": [],
        "pc_cycles": {"0x8042": 50000, "0x8004": 100},
        "pc_execs": {"0x8030": 1, "0x8042": 10000},
    }

    class TimeoutFakeRunner:
        def run(self, mesen_bin, args, timeout):
            raise subprocess.TimeoutExpired(
                cmd=[mesen_bin] + args,
                timeout=timeout,
                output=f"__PROFILE_RESULTS_JSON_START__{json.dumps(partial_json)}__PROFILE_RESULTS_JSON_END__",
                stderr="",
            )

    res = profile_sfx_execution(
        target_name=str(test_dir),
        timeout_seconds=2,
        runner=TimeoutFakeRunner(),
    )
    assert res["status"] == "FAIL"
    assert res["passed"] is False
    assert res["timed_out"] is True
    assert res["active_r15"] == "0x8044"
    assert res["active_symbol"] == "square_root_loop"
    assert res["active_function"] == "gsu_sqrt32"
    assert len(res["hotspots_by_function"]) == 2
    assert res["hotspots_by_function"][0]["symbol"] == "gsu_sqrt32"
    assert res["hotspots_by_function"][0]["cycles"] == 50000


def test_profiler_input_overrides_and_invalid_symbol(tmp_path):
    table = _make_symbol_table_from_dbg(SAMPLE_PROFILE_DBG)
    lua_code = _generate_profile_lua_script(
        symbols=table,
        warmup_runs=0,
        sample_runs=1,
        inputs={"INPUT": 0x00010000, "r4": 42},
        timeout_seconds=10,
        results_json_path="/tmp/dummy.json",
    )
    assert "emu.writeWord(0x0010, 1, emu.memType.gsuWorkRam)" in lua_code
    assert "emu.writeWord(0x0012, 0, emu.memType.gsuWorkRam)" in lua_code
    assert '["cart.coprocessor.r4"] = 42' in lua_code

    test_dir = tmp_path / "fake_bad_input"
    test_dir.mkdir()
    (test_dir / "Test.sfc").write_bytes(b"\x00" * 64)
    (test_dir / "Test.dbg").write_text(SAMPLE_PROFILE_DBG, encoding="utf-8")

    res = profile_sfx_execution(
        target_name=str(test_dir),
        inputs={"DOES_NOT_EXIST": 123},
        runner=FakeMesenRunner(),
    )
    assert res["status"] == "FAIL"
    assert "unresolved input symbol" in res["error"].lower()


def test_profile_sfx_execution_sqrt_end_to_end():
    res = profile_sfx_execution(
        target_name="sqrt",
        inputs={"INPUT": 65536},
        max_cycles=5000,
        timeout_seconds=15,
    )
    assert res["status"] == "PASS"
    assert res["passed"] is True
    assert res["summary"]["total_cycles"] > 100
    assert res["summary"]["budget_exceeded"] is False
    func_names = [h["symbol"] for h in res["hotspots_by_function"]]
    assert "gsu_sqrt32" in func_names
    label_names = [h["symbol"] for h in res["hotspots_by_label"]]
    assert "square_root_loop" in label_names

