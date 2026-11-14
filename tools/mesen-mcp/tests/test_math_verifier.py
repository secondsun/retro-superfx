import json
from pathlib import Path
import re
import subprocess

from mesen_mcp import FakeMesenRunner, assert_sfx_math


MATH_FIXTURE_DBG = """\
version\tmajor=2,minor=0
file\tid=0,name="Test.sgs",size=400,mtime=0x6ACEE723,mod=0
seg\tid=0,name="GSUCODE",start=0x038000,size=0x0100,addrsize=absolute,type=ro
seg\tid=1,name="GSURAM",start=0x700000,size=0x0100,addrsize=absolute,type=rw
seg\tid=2,name="EXPRAM",start=0x710000,size=0x10000,addrsize=absolute,type=rw
seg\tid=3,name="RODATA",start=0x048000,size=0x1000,addrsize=absolute,type=ro
seg\tid=4,name="BSS",start=0x7E2000,size=0x0100,addrsize=absolute,type=rw
sym\tid=0,name="test_setup",addrsize=absolute,scope=0,val=0x038004,seg=0,type=lab
sym\tid=1,name="test_stop",addrsize=absolute,scope=0,val=0x038020,seg=0,type=lab
sym\tid=2,name="INPUT32",addrsize=absolute,scope=0,val=0x700010,seg=1,type=lab
sym\tid=3,name="VECTOR_ADD_OUT",addrsize=absolute,scope=0,val=0x700014,seg=1,type=lab
sym\tid=4,name="NEXT_GSU_SYM",addrsize=absolute,scope=0,val=0x700018,seg=1,type=lab
sym\tid=5,name="STAGED_DWORD",addrsize=absolute,scope=0,val=0x718000,seg=2,type=lab
sym\tid=6,name="STAGED_END",addrsize=absolute,scope=0,val=0x718004,seg=2,type=lab
sym\tid=7,name="ROM_TABLE",addrsize=absolute,scope=0,val=0x048000,seg=3,type=lab
sym\tid=8,name="WRAM_BUF",addrsize=absolute,scope=0,val=0x7E2000,seg=4,type=lab
"""


def _make_math_project(tmp_path: Path) -> Path:
    proj = tmp_path / "math_proj"
    proj.mkdir()
    (proj / "Test.sfc").write_bytes(b"\x00" * 64)
    (proj / "Test.dbg").write_text(MATH_FIXTURE_DBG, encoding="utf-8")
    return proj


def test_assert_sfx_math_bank70_bank71_32bit_signed_and_rom_exec_callbacks(tmp_path):
    proj = _make_math_project(tmp_path)

    payload = {
        "passed": True,
        "results": {"OUTPUT": -65536, "STAGED_DWORD": -10, "r5": -2, "OUTPUT + 2": 42},
        "diffs": {"OUTPUT": 0, "STAGED_DWORD": 1, "r5": 0, "OUTPUT + 2": 0},
    }
    # Deliberately emit multiline JSON between sentinels to verify re.DOTALL fallback!
    multiline_json = json.dumps(payload, indent=2)
    fake = FakeMesenRunner(
        stdout=f"boot log\n__MATH_RESULTS_JSON_START__\n{multiline_json}\n__MATH_RESULTS_JSON_END__\n",
        returncode=0,
    )

    res = assert_sfx_math(
        test_name=str(proj),
        input_overrides={
            ".INPUT32": 0x00010000,
            "STAGED_DWORD": 0x12345678,
            "r5": 0xFFFE,
        },
        expected_outputs={
            "OUTPUT": -65536,
            "STAGED_DWORD": -9,
            "r5": -2,
            "OUTPUT + 2": 42,
        },
        epsilon=1,
        runner=fake,
    )

    assert res["status"] == "PASS"
    assert res["results"]["STAGED_DWORD"] == -10
    assert res["diffs"]["STAGED_DWORD"] == 1

    lua = fake.last_lua_code
    assert lua is not None
    # Bank $70 32-bit big-word write (high word at 0x0010, low word at 0x0012)
    assert "emu.writeWord(0x0010, 1, emu.memType.gsuWorkRam)" in lua
    assert "emu.writeWord(0x0012, 0, emu.memType.gsuWorkRam)" in lua
    # Bank $71 32-bit big-word write (high word at 0x18000, low word at 0x18002 — NOT 0x8000/0x8002!)
    assert "emu.writeWord(0x18000, 4660, emu.memType.gsuWorkRam)" in lua
    assert "emu.writeWord(0x18002, 22136, emu.memType.gsuWorkRam)" in lua
    # Bank $71 32-bit read + signed two's-complement reconstruction
    assert "emu.readWord(0x18000, emu.memType.gsuWorkRam, false)" in lua
    assert "emu.readWord(0x18002, emu.memType.gsuWorkRam, false)" in lua
    assert "2147483648" in lua and "4294967296" in lua
    # 24-bit ROM GSUCODE execution callbacks on emu.memType.gsuMemory with emu.cpuType.gsu
    assert "local test_setup = 0x038004" in lua
    assert "local test_stop  = 0x038020" in lua
    assert (
        "emu.addMemoryCallback(onTestSetup, emu.callbackType.exec, test_setup, test_setup, emu.cpuType.gsu, emu.memType.gsuMemory)"
        in lua
    )
    assert (
        "emu.addMemoryCallback(onTestStop, emu.callbackType.exec, test_stop, test_stop, emu.cpuType.gsu, emu.memType.gsuMemory)"
        in lua
    )


def test_assert_sfx_math_file_channel_and_failed_assertion(tmp_path):
    proj = _make_math_project(tmp_path)

    def write_json_file(_cmd, lua_code):
        assert lua_code is not None
        m = re.search(r'io\.open\("([^"]+)",\s*"w"\)', lua_code)
        assert m is not None
        json_file = Path(m.group(1).replace("\\\\", "\\"))
        json_file.write_text(
            json.dumps(
                {
                    "passed": False,
                    "results": {"OUTPUT": 10},
                    "diffs": {"OUTPUT": 989},
                }
            ),
            encoding="utf-8",
        )
        return None

    fake = FakeMesenRunner(stdout="", returncode=1, on_run=write_json_file)
    res = assert_sfx_math(
        test_name=str(proj),
        input_overrides={"INPUT32": 100},
        expected_outputs={"OUTPUT": 999},
        runner=fake,
    )
    assert res["status"] == "FAIL"
    assert res["results"]["OUTPUT"] == 10
    assert res["diffs"]["OUTPUT"] == 989


def test_assert_sfx_math_rejects_rom_wram_and_missing_symbols(tmp_path):
    proj = _make_math_project(tmp_path)
    fake = FakeMesenRunner()

    res_rom_in = assert_sfx_math(
        test_name=str(proj),
        input_overrides={"ROM_TABLE": 1},
        expected_outputs={"OUTPUT": 0},
        runner=fake,
    )
    assert res_rom_in["status"] == "FAIL"
    assert "ROM_TABLE" in res_rom_in["error"]
    assert "gsuWorkRam" in res_rom_in["error"]

    res_wram_out = assert_sfx_math(
        test_name=str(proj),
        input_overrides={"INPUT32": 1},
        expected_outputs={"WRAM_BUF": 0},
        runner=fake,
    )
    assert res_wram_out["status"] == "FAIL"
    assert "WRAM_BUF" in res_wram_out["error"]

    res_unknown = assert_sfx_math(
        test_name=str(proj),
        input_overrides={"NO_SUCH_SYM": 1},
        expected_outputs={"OUTPUT": 0},
        runner=fake,
    )
    assert res_unknown["status"] == "FAIL"
    assert "unresolved input symbol" in res_unknown["error"].lower()


def test_assert_sfx_math_bytes_timeout_and_nonexistent_test(tmp_path):
    proj = _make_math_project(tmp_path)

    def raise_bytes_timeout(cmd, _lua):
        raise subprocess.TimeoutExpired(
            cmd=cmd,
            timeout=3,
            output=b"partial stdout bytes",
            stderr=b"partial stderr bytes",
        )

    res_timeout = assert_sfx_math(
        test_name=str(proj),
        input_overrides={"INPUT32": 1},
        expected_outputs={"OUTPUT": 1},
        timeout_seconds=3,
        runner=FakeMesenRunner(on_run=raise_bytes_timeout),
    )
    assert res_timeout["status"] == "FAIL"
    assert res_timeout["exit_code"] == -1
    assert "timed out" in res_timeout["error"].lower()
    assert "partial stdout bytes" in res_timeout["output"]
    assert "partial stderr bytes" in res_timeout["output"]

    res_missing = assert_sfx_math(
        test_name="nonexistent_test_xyz",
        input_overrides={"INPUT": 1},
        expected_outputs={"OUTPUT": 1},
    )
    assert res_missing["status"] == "FAIL"
    assert "not found" in res_missing["error"].lower()
