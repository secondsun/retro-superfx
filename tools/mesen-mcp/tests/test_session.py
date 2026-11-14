import os
from pathlib import Path
import subprocess
import pytest

from mesen_mcp import (
    BuildArtifactIndex,
    FakeMesenRunner,
    SfxTestSession,
    SubprocessMesenRunner,
    SymbolTable,
)


def test_symbol_table_facade_over_build_artifact_index():
    idx = BuildArtifactIndex.from_strings(
        cpu_sym_text=(
            "al 700004 .test_setup\n"
            "al 701000 .MY_VAR\n"
            "al 701004 .VECTOR_ADD_OUT\n"
            "al 718000 .STAGED_TAB\n"
        )
    )
    table = SymbolTable.from_index(idx)

    assert table.get_offset("MY_VAR") == 0x1000
    assert table.get_offset("my_var") == 0x1000
    assert table.get_size("MY_VAR") == 4
    assert table.get_offset("OUTPUT") == 0x1004
    # Bank $71 linear offset 0x18000 preserved through SymbolTable facade
    assert table.get_offset("STAGED_TAB") == 0x18000
    assert table.get_address("STAGED_TAB") == 0x718000


def test_fake_mesen_runner_captures_lua_and_invokes_on_run(tmp_path):
    lua_file = tmp_path / "harness.lua"
    lua_file.write_text("-- test harness\nemu.stop(0)\n", encoding="utf-8")

    seen = {}

    def hook(cmd, lua_code):
        seen["cmd"] = cmd
        seen["lua"] = lua_code
        return None

    runner = FakeMesenRunner(stdout="OK\n", on_run=hook)
    proc = runner.run("/bin/mesen", ["--testrunner", str(lua_file), "Test.sfc"], timeout=5)

    assert proc.stdout == "OK\n"
    assert runner.last_lua_code == "-- test harness\nemu.stop(0)\n"
    assert runner.lua_scripts == ["-- test harness\nemu.stop(0)\n"]
    assert seen["lua"] == runner.last_lua_code


def test_session_incremental_compile_if_stale(tmp_path, monkeypatch):
    proj = tmp_path / "inc_proj"
    proj.mkdir()
    makefile = proj / "Makefile"
    makefile.write_text("all:\n\t@true\n", encoding="utf-8")
    src = proj / "main.sgs"
    src.write_text("; kernel\n", encoding="utf-8")
    sfc = proj / "Test.sfc"
    sfc.write_bytes(b"\x00" * 32)
    dbg = proj / "Test.dbg"
    dbg.write_text("version\tmajor=2,minor=0\n", encoding="utf-8")

    make_calls = []

    def fake_subprocess_run(cmd, **kwargs):
        make_calls.append(cmd)
        # Touch artifacts to simulate fresh build
        os.utime(sfc, (3000, 3000))
        os.utime(dbg, (3000, 3000))
        return subprocess.CompletedProcess(args=cmd, returncode=0, stdout="", stderr="")

    monkeypatch.setattr(subprocess, "run", fake_subprocess_run)

    # 1. Fresh artifacts (newer than source and Makefile) -> compile(mode="if_stale") skips make!
    os.utime(makefile, (1000, 1000))
    os.utime(src, (1000, 1000))
    os.utime(sfc, (2000, 2000))
    os.utime(dbg, (2000, 2000))

    session = SfxTestSession(
        test_dir=proj, test_name="inc_proj", runner=SubprocessMesenRunner()
    )
    assert session.compile(mode="if_stale") is False
    assert make_calls == []

    # 2. Source updated -> incremental `make all` runs WITHOUT `clean`!
    os.utime(src, (2500, 2500))
    assert session.compile(mode="if_stale") is True
    assert len(make_calls) == 1
    assert make_calls[0] == ["make", "-C", str(proj.resolve()), "all"]
    assert "clean" not in make_calls[0]

    # 3. Directory without Makefile skips gracefully without error
    prebuilt = tmp_path / "prebuilt_rom"
    prebuilt.mkdir()
    (prebuilt / "SuperFX.sfc").write_bytes(b"\x00" * 32)
    prebuilt_session = SfxTestSession(
        test_dir=prebuilt, test_name="prebuilt", runner=SubprocessMesenRunner()
    )
    assert prebuilt_session.compile(mode="always", force=True) is False


def test_session_run_harness_cleans_temp_files_on_value_error(tmp_path):
    proj = tmp_path / "err_proj"
    proj.mkdir()
    (proj / "Test.sfc").write_bytes(b"\x00" * 16)
    (proj / "Test.cpu.sym").write_text("al 808000 .ROM_ONLY\n", encoding="utf-8")

    leaked_paths = []
    session = SfxTestSession(
        test_dir=proj, test_name="err_proj", runner=FakeMesenRunner()
    )

    def bad_builder(s, ctx):
        leaked_paths.append(ctx.results_json_path)
        return "\n".join(s.emit_lua_overrides({"ROM_ONLY": 1}))

    with pytest.raises(ValueError, match="emu.memType.gsuWorkRam"):
        session.run_harness(bad_builder, json_sentinel="MATH_RESULTS_JSON")

    assert len(leaked_paths) == 1
    assert leaked_paths[0] is not None
    assert not leaked_paths[0].exists()
