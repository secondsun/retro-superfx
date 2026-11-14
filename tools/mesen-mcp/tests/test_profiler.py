import json
import subprocess

from mesen_mcp import FakeMesenRunner, profile_sfx_execution


SAMPLE_PROFILE_DBG = """\
version\tmajor=2,minor=0
file\tid=0,name="Test.sgs",size=1200,mtime=0x6ACEE723,mod=0
seg\tid=0,name="GSUCODE",start=0x038000,size=0x0100,addrsize=absolute,type=ro,oname="Test.sfc",ooffs=0
seg\tid=1,name="GSURAM",start=0x700000,size=0x0100,addrsize=absolute,type=rw
seg\tid=2,name="EXPRAM",start=0x710000,size=0x10000,addrsize=absolute,type=rw
seg\tid=3,name="RODATA",start=0x048000,size=0x1000,addrsize=absolute,type=ro
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
sym\tid=12,name="STAGED_PARAM",addrsize=absolute,scope=0,def=8,val=0x718000,seg=2,type=lab
sym\tid=13,name="ROM_ASSET",addrsize=absolute,scope=0,def=9,val=0x048040,seg=3,type=lab
"""


def test_profiler_two_pass_fps_hotspots_and_bank71_input_emission(tmp_path):
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
    runner = FakeMesenRunner(
        stdout=f"__PROFILE_RESULTS_JSON_START__{json.dumps(fake_json)}__PROFILE_RESULTS_JSON_END__\n",
        returncode=0,
    )

    res = profile_sfx_execution(
        target_name=str(test_dir),
        warmup_runs=1,
        sample_runs=2,
        max_cycles=1200,
        inputs={"INPUT": 0x00010000, "STAGED_PARAM": 77, "r4": 42},
        runner=runner,
    )

    assert res["status"] == "PASS"
    assert res["passed"] is True
    summary = res["summary"]
    assert summary["total_cycles"] == 1800
    assert summary["average_cycles"] == 900.0
    assert summary["cached_execution_pct"] == 66.67
    assert summary["vblanks_per_full_frame"] == 3.0
    assert summary["effective_fps"] == 20.0
    assert summary["pass_breakdown"]["pass_0"]["average_cycles"] == 1000.0
    assert summary["pass_breakdown"]["pass_1"]["average_cycles"] == 800.0

    funcs = res["hotspots_by_function"]
    assert funcs[0]["symbol"] == "gsu_sqrt32"
    assert funcs[0]["cycles"] == 1100
    assert funcs[0]["call_count"] == 4

    labels = res["hotspots_by_label"]
    # ROM_ASSET at 0x048040 must NOT shadow square_root_loop at 0x038040!
    assert labels[0]["symbol"] == "square_root_loop"
    assert labels[0]["enclosing_function"] == "gsu_sqrt32"
    assert labels[0]["executions"] == 120

    lua = runner.last_lua_code
    assert lua is not None
    assert "local test_setup_addr = 0x038004" in lua
    assert "emu.writeWord(0x0010, 1, emu.memType.gsuWorkRam)" in lua
    assert "emu.writeWord(0x0012, 0, emu.memType.gsuWorkRam)" in lua
    assert "emu.writeWord(0x18000, 77, emu.memType.gsuWorkRam)" in lua
    assert '["cart.coprocessor.r4"] = 42' in lua


def test_profiler_budget_exceeded_and_rom_input_rejection(tmp_path):
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
    res_budget = profile_sfx_execution(
        target_name=str(test_dir),
        max_cycles=1500,
        runner=FakeMesenRunner(
            stdout=f"__PROFILE_RESULTS_JSON_START__{json.dumps(fake_json)}__PROFILE_RESULTS_JSON_END__"
        ),
    )
    assert res_budget["status"] == "FAIL"
    assert res_budget["summary"]["budget_exceeded"] is True

    res_rom_err = profile_sfx_execution(
        target_name=str(test_dir),
        inputs={"ROM_ASSET": 1},
        runner=FakeMesenRunner(),
    )
    assert res_rom_err["status"] == "FAIL"
    assert "ROM_ASSET" in res_rom_err["error"]

    res_unknown = profile_sfx_execution(
        target_name=str(test_dir),
        inputs={"DOES_NOT_EXIST": 123},
        runner=FakeMesenRunner(),
    )
    assert res_unknown["status"] == "FAIL"
    assert "unresolved input symbol" in res_unknown["error"].lower()


def test_profiler_bytes_timeout_recovers_partial_hotspots_and_active_r15(tmp_path):
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
    raw_bytes = (
        f"__PROFILE_RESULTS_JSON_START__\n{json.dumps(partial_json, indent=2)}\n__PROFILE_RESULTS_JSON_END__".encode(
            "utf-8"
        )
    )

    def raise_bytes_timeout(cmd, _lua):
        raise subprocess.TimeoutExpired(
            cmd=cmd, timeout=2, output=raw_bytes, stderr=b"timeout"
        )

    res = profile_sfx_execution(
        target_name=str(test_dir),
        timeout_seconds=2,
        runner=FakeMesenRunner(on_run=raise_bytes_timeout),
    )
    assert res["status"] == "FAIL"
    assert res["passed"] is False
    assert res["timed_out"] is True
    assert res["active_r15"] == "0x8044"
    assert res["active_symbol"] == "square_root_loop"
    assert res["active_function"] == "gsu_sqrt32"
    assert res["hotspots_by_function"][0]["symbol"] == "gsu_sqrt32"
    assert res["hotspots_by_function"][0]["cycles"] == 50000
