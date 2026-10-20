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

