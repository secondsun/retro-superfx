import json
import pytest
from mesen_mcp.server import create_server, mcp


@pytest.mark.asyncio
async def test_server_list_tools():
    server = create_server()
    tools = await server.list_tools()
    tool_names = [t.name for t in tools]
    assert "run_sfx_test" in tool_names
    assert "assert_sfx_math" in tool_names
    assert "capture_sfx_render" in tool_names


@pytest.mark.asyncio
async def test_server_call_run_sfx_test():
    server = create_server()
    result = await server.call_tool("run_sfx_test", {"test_name": "sqrt", "timeout_seconds": 15})
    assert not result.is_error

    # In FastMCP / MCPServer, content contains TextContent
    assert len(result.content) > 0
    payload = json.loads(result.content[0].text)
    assert payload["status"] == "PASS"
    assert payload["test"] == "sqrt"


@pytest.mark.asyncio
async def test_server_call_assert_sfx_math():
    server = create_server()
    result = await server.call_tool(
        "assert_sfx_math",
        {
            "test_name": "sqrt",
            "input_overrides": {"INPUT": 100},
            "expected_outputs": {"OUTPUT": 10},
            "epsilon": 0,
            "timeout_seconds": 15,
        },
    )
    assert not result.is_error
    assert len(result.content) > 0
    payload = json.loads(result.content[0].text)
    assert payload["status"] == "PASS"
    assert payload["results"]["OUTPUT"] == 10
    assert payload["diffs"]["OUTPUT"] == 0


@pytest.mark.asyncio
async def test_server_call_capture_sfx_render(tmp_path):
    output_png = tmp_path / "server_capture.png"
    server = create_server()
    result = await server.call_tool(
        "capture_sfx_render",
        {
            "scene_test_name": "sqrt",
            "output_png_path": str(output_png),
            "timeout_seconds": 15,
        },
    )
    assert not result.is_error
    assert len(result.content) > 0
    payload = json.loads(result.content[0].text)
    assert payload["status"] == "SUCCESS"
    assert payload["png_path"] == str(output_png)
    assert "metrics" in payload
    assert payload["metrics"]["width"] == 256
    assert payload["metrics"]["height"] == 224
