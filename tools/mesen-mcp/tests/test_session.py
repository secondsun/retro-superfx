from pathlib import Path
import pytest
import subprocess
from mesen_mcp.session import (
    FakeMesenRunner,
    SfxTestSession,
    SymbolTable,
)
from mesen_mcp.utils import resolve_test_dir


def test_symbol_table_offsets_and_aliases():
    table = SymbolTable(
        symbols={
            "my_var": 0x1000,
            "VECTOR_ADD_OUT": 0x2000,
            "test_setup": 0x0004,
        },
        sizes={
            "my_var": 4,
        },
    )

    # Exact match
    assert table.get_offset("my_var") == 0x1000
    # Case insensitivity
    assert table.get_offset("MY_VAR") == 0x1000
    assert table.get_offset("Test_Setup") == 0x0004

    # OUTPUT alias resolution
    assert table.get_offset("OUTPUT") == 0x2000
    assert table.get_offset("output") == 0x2000

    # Unknown
    assert table.get_offset("unknown_symbol") is None

    # Sizes
    assert table.get_size("my_var") == 4
    assert table.get_size("MY_VAR") == 4
    assert table.get_size("unspecified_var", default=2) == 2


def test_fake_mesen_runner():
    runner = FakeMesenRunner(stdout="TEST PASSED\n", stderr="", returncode=0)
    proc = runner.run("/usr/local/bin/mesen", ["--testrunner", "Test.sfc", "test.lua"], timeout=10)

    assert proc.returncode == 0
    assert proc.stdout == "TEST PASSED\n"
    assert runner.last_cmd == ["/usr/local/bin/mesen", "--testrunner", "Test.sfc", "test.lua"]


def test_sfx_test_session_with_fake_runner(tmp_path):
    # Create fake test directory
    test_dir = tmp_path / "fake_test"
    test_dir.mkdir()
    sfc_file = test_dir / "Test.sfc"
    sfc_file.write_bytes(b"\x00" * 100)
    lua_file = test_dir / "test.lua"
    lua_file.write_text("local OUTPUT = 0x0000\nlocal other = 123\n")

    runner = FakeMesenRunner(stdout="TEST PASSED: SFX Math Assertions Satisfied\n", returncode=0)
    session = SfxTestSession(test_dir=test_dir, test_name="fake_test", runner=runner, mesen_path="/dummy/mesen")

    # Inject mock symbols
    session._symbols = SymbolTable(symbols={"VECTOR_ADD_OUT": 0x3456})

    # Test sync_lua_script using OUTPUT alias
    session.sync_lua_script(lua_file)
    synced_content = lua_file.read_text()
    assert "local OUTPUT = 0x3456" in synced_content

    # Test run_lua
    proc = session.run_lua(lua_file, timeout_seconds=5)
    assert proc.returncode == 0
    assert "TEST PASSED" in proc.stdout
    assert runner.last_cmd[0] == "/dummy/mesen"
    assert runner.last_cmd[1] == "--testrunner"
    assert str(sfc_file) in runner.last_cmd
    assert str(lua_file) in runner.last_cmd


def test_fake_runner_default_mesen_path(tmp_path):
    test_dir = tmp_path / "fake_test_default_path"
    test_dir.mkdir()
    sfc_file = test_dir / "Test.sfc"
    sfc_file.write_bytes(b"\x00" * 10)
    lua_file = test_dir / "test.lua"
    lua_file.write_text("local foo = 1\n")

    runner = FakeMesenRunner(stdout="TEST PASSED\n", returncode=0)
    # mesen_path is None; should default to "mesen" without find_mesen error
    session = SfxTestSession(test_dir=test_dir, test_name="fake_test", runner=runner, mesen_path=None)

    # compile() should skip without throwing even without a Makefile
    session.compile()

    proc = session.run_lua(lua_file, timeout_seconds=5)
    assert proc.returncode == 0
    assert runner.last_cmd[0] == "mesen"
    assert runner.last_cmd[1] == "--testrunner"


def test_symbol_table_load_merges_dbg_and_cpu_sym_for_custom_symbols(tmp_path):
    test_dir = tmp_path / "custom_sym_test"
    test_dir.mkdir()

    # Create Test.dbg with base symbols
    dbg_content = """\
version\tmajor=2,minor=0
file\tid=0,name="Test.s",size=100,mtime=0x0,mod=0
seg\tid=0,name="CODE",start=0x808000,size=0x10,addrsize=absolute,type=ro,oname="Test.sfc",ooffs=0
span\tid=0,seg=0,start=0,size=2,type=0
line\tid=0,file=0,line=1
sym\tid=0,name="test_setup",addrsize=absolute,scope=0,def=0,val=0x700004,seg=0,type=lab
sym\tid=1,name="test_stop",addrsize=absolute,scope=0,def=0,val=0x700020,seg=0,type=lab
"""
    (test_dir / "Test.dbg").write_text(dbg_content, encoding="utf-8")

    # Create Test.cpu.sym with custom symbols not present in Test.dbg
    cpu_sym_content = """\
al 700004 .test_setup
al 700020 .test_stop
al 700010 .TEST_CASE_IDX
al 700014 .TEST_RESULT
"""
    (test_dir / "Test.cpu.sym").write_text(cpu_sym_content, encoding="utf-8")

    # Create test.lua with custom symbols and a loop counter
    lua_file = test_dir / "test.lua"
    lua_file.write_text(
        "local test_setup = 0x0000\n"
        "local test_stop = 0x0000\n"
        "local TEST_CASE_IDX = 0x0000\n"
        "local TEST_RESULT = 0x0000\n"
        "local fail_count = 0\n"
    )

    runner = FakeMesenRunner(stdout="TEST PASSED\n", returncode=0)
    session = SfxTestSession(test_dir=test_dir, test_name="custom_sym_test", runner=runner)

    # Verify SymbolTable loaded symbols from both Test.dbg and Test.cpu.sym
    table = session.symbols
    assert table.get_offset("test_setup") == 0x0004
    assert table.get_offset("test_stop") == 0x0020
    assert table.get_offset("TEST_CASE_IDX") == 0x0010
    assert table.get_offset("TEST_RESULT") == 0x0014

    # Verify sync_lua_script updates custom symbols from Test.cpu.sym even when Test.dbg is present
    session.sync_lua_script(lua_file)
    synced = lua_file.read_text(encoding="utf-8")
    assert "local test_setup = 0x0004" in synced
    assert "local test_stop = 0x0020" in synced
    assert "local TEST_CASE_IDX = 0x0010" in synced
    assert "local TEST_RESULT = 0x0014" in synced
    assert "local fail_count = 0" in synced



