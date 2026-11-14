import json
from pathlib import Path
import re
import pytest

from PIL import Image

from mesen_mcp import FakeMesenRunner
from mesen_mcp.server import create_server


SERVER_FIXTURE_DBG = """\
version\tmajor=2,minor=0
file\tid=0,name="Test.sgs",size=200,mtime=0x6ACEE723,mod=0
seg\tid=0,name="GSUCODE",start=0x038000,size=0x0080,addrsize=absolute,type=ro
seg\tid=1,name="GSURAM",start=0x700000,size=0x0080,addrsize=absolute,type=rw
scope\tid=0,name="",mod=0,size=128
scope\tid=1,name="gsu_sqrt32",mod=0,size=48,parent=0
sym\tid=0,name="GSU_Code",addrsize=absolute,scope=0,val=0x038000,seg=0,type=lab
sym\tid=1,name="test_setup",addrsize=absolute,scope=0,val=0x038004,seg=0,type=lab
sym\tid=2,name="test_stop",addrsize=absolute,scope=0,val=0x038020,seg=0,type=lab
sym\tid=3,name="gsu_sqrt32_function",addrsize=absolute,scope=0,val=0x038030,seg=0,type=lab
sym\tid=4,name="INPUT",addrsize=absolute,scope=0,val=0x700010,seg=1,type=lab
sym\tid=5,name="OUTPUT",addrsize=absolute,scope=0,val=0x700014,seg=1,type=lab
"""


def _make_server_fixture(tmp_path: Path) -> Path:
    proj = tmp_path / "srv_proj"
    proj.mkdir()
    (proj / "Test.sfc").write_bytes(b"\x00" * 64)
    (proj / "Test.dbg").write_text(SERVER_FIXTURE_DBG, encoding="utf-8")
    (proj / "test.lua").write_text('local INPUT = 0x0000\nprint("TEST PASSED")\n', encoding="utf-8")
    return proj


@pytest.mark.asyncio
async def test_server_list_tools_and_schemas():
    server = create_server()
    tools = await server.list_tools()
    by_name = {t.name: t for t in tools}
    expected = {
        "run_sfx_test",
        "assert_sfx_math",
        "capture_sfx_render",
        "profile_sfx_execution",
        "sfx_resolve_address",
    }
    assert expected.issubset(by_name.keys())
    for name in expected:
        schema = getattr(by_name[name], "inputSchema", None) or getattr(
            by_name[name], "input_schema", None
        )
        assert isinstance(schema, dict) and "properties" in schema
        assert "runner" not in schema["properties"]


@pytest.mark.asyncio
async def test_server_all_five_tools_via_fake_runner(tmp_path):
    proj = _make_server_fixture(tmp_path)

    # 1. run_sfx_test
    srv_run = create_server(runner=FakeMesenRunner(stdout="TEST PASSED\n"))
    res_run = await srv_run.call_tool("run_sfx_test", {"test_name": str(proj)})
    payload_run = json.loads(res_run.content[0].text)
    assert payload_run["status"] == "PASS"
    assert "symbols" not in payload_run

    # 2. assert_sfx_math
    math_json = json.dumps({"passed": True, "results": {"OUTPUT": 10}, "diffs": {"OUTPUT": 0}})
    srv_math = create_server(
        runner=FakeMesenRunner(
            stdout=f"__MATH_RESULTS_JSON_START__{math_json}__MATH_RESULTS_JSON_END__"
        )
    )
    res_math = await srv_math.call_tool(
        "assert_sfx_math",
        {
            "test_name": str(proj),
            "input_overrides": {"INPUT": 100},
            "expected_outputs": {"OUTPUT": 10},
        },
    )
    payload_math = json.loads(res_math.content[0].text)
    assert payload_math["status"] == "PASS"
    assert payload_math["results"]["OUTPUT"] == 10

    # 3. capture_sfx_render
    out_png = tmp_path / "server_capture.png"

    def write_png(_cmd, lua_code):
        assert lua_code is not None
        m = re.search(r'local target_png = "([^"]+)"', lua_code)
        assert m is not None
        Image.new("RGB", (256, 224), (0, 0, 0)).save(
            Path(m.group(1).replace("\\\\", "\\")), format="PNG"
        )
        return None

    srv_cap = create_server(runner=FakeMesenRunner(on_run=write_png))
    res_cap = await srv_cap.call_tool(
        "capture_sfx_render",
        {"target_name": str(proj), "output_png_path": str(out_png), "settle_frames": 5},
    )
    payload_cap = json.loads(res_cap.content[0].text)
    assert payload_cap["status"] == "SUCCESS"
    assert payload_cap["png_path"] == str(out_png.resolve())
    assert payload_cap["metrics"]["width"] == 256

    # 4. profile_sfx_execution
    prof_json = json.dumps(
        {
            "timed_out": False,
            "active_r15": None,
            "warmup_runs_completed": 0,
            "sample_runs_completed": 1,
            "runs": [
                {
                    "run": 1,
                    "cycles": 320,
                    "cached_cycles": 200,
                    "uncached_cycles": 120,
                    "vblanks": 1,
                    "frame_half": None,
                }
            ],
            "pc_cycles": {"0x8032": 320},
            "pc_execs": {"0x8030": 1, "0x8032": 32},
        }
    )
    srv_prof = create_server(
        runner=FakeMesenRunner(
            stdout=f"__PROFILE_RESULTS_JSON_START__{prof_json}__PROFILE_RESULTS_JSON_END__"
        )
    )
    res_prof = await srv_prof.call_tool(
        "profile_sfx_execution",
        {"project": str(proj), "inputs": {"INPUT": 100}, "max_cycles": 5000},
    )
    payload_prof = json.loads(res_prof.content[0].text)
    assert payload_prof["status"] == "PASS"
    assert payload_prof["passed"] is True
    assert payload_prof["hotspots_by_function"][0]["symbol"] == "gsu_sqrt32"

    # 5. sfx_resolve_address
    res_addr = await srv_prof.call_tool(
        "sfx_resolve_address",
        {"project": str(proj), "symbol": "gsu_sqrt32"},
    )
    payload_addr = json.loads(res_addr.content[0].text)
    assert payload_addr["status"] == "SUCCESS"
    assert payload_addr["address"] == "0x038030"
