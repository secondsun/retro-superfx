import argparse
import json
import sys
from typing import Any, Dict, Optional

try:
    from mcp.server.mcpserver import MCPServer as FastMCP
except ImportError:
    from mcp.server.fastmcp import FastMCP

from mesen_mcp.math_verifier import assert_sfx_math as execute_assert_sfx_math
from mesen_mcp.profiler import profile_sfx_execution as execute_profile_sfx_execution
from mesen_mcp.render_capture import capture_sfx_render as execute_capture_sfx_render
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

    @app.tool()
    def assert_sfx_math(
        test_name: str,
        input_overrides: Dict[str, int],
        expected_outputs: Dict[str, int],
        epsilon: int = 0,
        timeout_seconds: int = 15,
    ) -> Dict[str, Any]:
        """Assert mathematical correctness of SuperFX routines with parameterized inputs.

        Args:
            test_name: Name of the test directory (e.g. 'sqrt', 'vector3_add', 'transform').
            input_overrides: Mapping of symbol/register names to integer values to inject.
            expected_outputs: Mapping of symbol/register names to expected integer values.
            epsilon: Maximum allowed absolute difference for assertions (default: 0).
            timeout_seconds: Maximum execution time in seconds (default: 15).

        Returns:
            Structured results: {"status": "PASS" | "FAIL", "test": test_name, "results": {...}, "diffs": {...}}
        """
        return execute_assert_sfx_math(
            test_name=test_name,
            input_overrides=input_overrides,
            expected_outputs=expected_outputs,
            epsilon=epsilon,
            timeout_seconds=timeout_seconds,
        )

    @app.tool()
    def capture_sfx_render(
        scene_test_name: str,
        output_png_path: Optional[str] = None,
        timeout_seconds: int = 15,
    ) -> Dict[str, Any]:
        """Execute a SuperFX scene or test ROM headlessly and capture its framebuffer.

        Args:
            scene_test_name: Name of the scene demo or test directory (e.g. 'SpriteScale', 'sqrt').
            output_png_path: Optional target file path for the captured PNG.
            timeout_seconds: Maximum execution time in seconds (default: 15).

        Returns:
            Structured results: {"status": "SUCCESS" | "FAIL", "png_path": ..., "metrics": {...}}
        """
        return execute_capture_sfx_render(
            scene_test_name=scene_test_name,
            output_png_path=output_png_path,
            timeout_seconds=timeout_seconds,
        )

    @app.tool()
    def profile_sfx_execution(
        target_name: str,
        warmup_runs: int = 0,
        sample_runs: int = 1,
        max_cycles: Optional[int] = None,
        inputs: Optional[Dict[str, int]] = None,
        top_n: int = 15,
        timeout_seconds: int = 15,
    ) -> Dict[str, Any]:
        """Profile SuperFX (GSU) execution cycles, function/label hotspots, cache residency, and VBlank FPS.

        Args:
            target_name: Name of the test or scene directory (e.g. 'sqrt', 'IsoEngine', 'SpriteScale').
            warmup_runs: Number of GSU invocations to skip before recording metrics (default: 0).
            sample_runs: Number of GSU invocations to profile (default: 1).
            max_cycles: Optional maximum allowed average GSU cycles per invocation budget.
            inputs: Optional mapping of symbol/register names to integer values to inject before execution.
            top_n: Maximum number of hotspot functions and labels to return (default: 15).
            timeout_seconds: Maximum execution time in seconds (default: 15).

        Returns:
            Structured profiling results including 'passed', 'summary' (total/average cycles,
            cached_execution_pct, vblanks_per_full_frame, effective_fps, pass_breakdown),
            'hotspots_by_function', and 'hotspots_by_label'.
        """
        return execute_profile_sfx_execution(
            target_name=target_name,
            warmup_runs=warmup_runs,
            sample_runs=sample_runs,
            max_cycles=max_cycles,
            inputs=inputs,
            top_n=top_n,
            timeout_seconds=timeout_seconds,
        )

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
