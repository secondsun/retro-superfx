import argparse
import json
import sys
from typing import Any, Dict

try:
    from mcp.server.mcpserver import MCPServer as FastMCP
except ImportError:
    from mcp.server.fastmcp import FastMCP

from mesen_mcp.runner import run_sfx_test as execute_run_sfx_test


def create_server() -> FastMCP:
    """Create and configure the Mesen MCP server instance."""
    app = FastMCP(
        name="mesen-mcp",
        instructions="MCP Server for SuperFX testing and headless Mesen emulation",
    )

    @app.tool()
    def run_sfx_test(test_name: str, timeout_seconds: int = 15) -> Dict[str, Any]:
        """Compile and execute a SuperFX unit test headlessly using Mesen.

        Args:
            test_name: Name of the test directory (e.g. 'sqrt', 'transform', 'vector3_add')
                       or path to the test directory.
            timeout_seconds: Maximum execution time in seconds (default: 15).

        Returns:
            Structured test results including 'status' ('PASS' | 'FAIL'), 'test',
            'output', and resolved symbols.
        """
        return execute_run_sfx_test(test_name=test_name, timeout_seconds=timeout_seconds)

    return app


# Default global server instance for CLI entry point
mcp = create_server()


def main():
    """Run the Mesen MCP server."""
    parser = argparse.ArgumentParser(description="Mesen MCP Server for SuperFX")
    parser.add_argument(
        "--transport",
        choices=["stdio", "sse"],
        default="stdio",
        help="Transport type (default: stdio)",
    )
    args = parser.parse_args()

    server = create_server()
    if args.transport == "stdio":
        server.run(transport="stdio")
    else:
        server.run(transport="sse")


if __name__ == "__main__":
    main()
