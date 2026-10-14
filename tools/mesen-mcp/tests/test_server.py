import json
import pytest
from mesen_mcp.server import mcp, create_server


@pytest.mark.asyncio
async def test_server_list_tools():
    server = create_server()
    tools = await server.list_tools()
    tool_names = [t.name for t in tools]
    assert "run_sfx_test" in tool_names


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
