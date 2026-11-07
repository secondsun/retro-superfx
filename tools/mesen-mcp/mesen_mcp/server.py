import argparse
import json
import sys
from typing import Any, Dict, Optional, Union

try:
    from mcp.server.mcpserver import MCPServer as FastMCP
except ImportError:
    from mcp.server.fastmcp import FastMCP


def create_server() -> FastMCP:
    """Create and configure the Mesen MCP server instance."""
    app = FastMCP(
        name="mesen-mcp",
        instructions=(
            "MCP Server for SuperFX testing, symbol/address resolution, and headless Mesen emulation. "
            "Invoke these tools via the `call_mcp_tool` tool with `ServerName: \"retro-superfx_mesen\"` "
            "and `ToolName` set to `run_sfx_test`, `capture_sfx_render`, `sfx_resolve_address`, "
            "`assert_sfx_math`, or `profile_sfx_execution` instead of using CLI scripts or grep."
        ),
    )

    @app.tool()
    def run_sfx_test(test_name: str, timeout_seconds: int = 15) -> Dict[str, Any]:
        """Use INSTEAD OF './tools/run-sfx-test.sh' to compile and execute a SuperFX unit test headlessly using Mesen.

        Args:
            test_name: Name of the test directory (e.g. 'sqrt', 'transform', 'vector3_add')
                       or path to the test directory, or 'all' to run all active unit tests.
            timeout_seconds: Maximum execution time in seconds (default: 15).

        Returns:
            Structured test results including 'status' ('PASS' | 'FAIL'), 'test',
            'exit_code', and 'output'. When test_name is 'all', includes 'total',
            'passed', 'failed', 'skipped', and 'results'.
        """
        from mesen_mcp.runner import run_sfx_test as execute_run_sfx_test

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
        from mesen_mcp.math_verifier import assert_sfx_math as execute_assert_sfx_math

        return execute_assert_sfx_math(
            test_name=test_name,
            input_overrides=input_overrides,
            expected_outputs=expected_outputs,
            epsilon=epsilon,
            timeout_seconds=timeout_seconds,
        )

    @app.tool()
    def capture_sfx_render(
        scene_test_name: Optional[str] = None,
        target_name: Optional[str] = None,
        project: Optional[str] = None,
        test_name: Optional[str] = None,
        output_png_path: Optional[str] = None,
        settle_frames: int = 0,
        timeout_seconds: int = 15,
    ) -> Dict[str, Any]:
        """Execute a SuperFX scene or test ROM headlessly and capture its framebuffer PNG for visual inspection.

        Args:
            scene_test_name: Name of the scene demo or test directory (e.g. 'SpriteScale', 'IsoEngine', 'sqrt').
                Also accepts 'target_name', 'project', or 'test_name'.
            output_png_path: Optional target file path for the captured PNG.
            settle_frames: Minimum frame count to wait before capturing (default: 0).
                When 0, defaults to 60 for continuous scenes without test_stop, or 8 post-stop frames for tests.
            timeout_seconds: Maximum execution time in seconds (default: 15).

        Returns:
            Structured results: {"status": "SUCCESS" | "FAIL", "png_path": ..., "metrics": {...}}
        """
        from mesen_mcp.render_capture import capture_sfx_render as execute_capture_sfx_render

        resolved_scene = scene_test_name or target_name or project or test_name
        if not resolved_scene:
            return {
                "status": "FAIL",
                "error": "Missing required argument 'scene_test_name' (or 'target_name' / 'project').",
            }
        return execute_capture_sfx_render(
            scene_test_name=resolved_scene,
            output_png_path=output_png_path,
            settle_frames=settle_frames,
            timeout_seconds=timeout_seconds,
        )

    @app.tool()
    def profile_sfx_execution(
        target_name: Optional[str] = None,
        scene_test_name: Optional[str] = None,
        project: Optional[str] = None,
        test_name: Optional[str] = None,
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
                Also accepts 'scene_test_name', 'project', or 'test_name'.
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
        from mesen_mcp.profiler import profile_sfx_execution as execute_profile_sfx_execution

        resolved_target = target_name or scene_test_name or project or test_name
        if not resolved_target:
            return {
                "status": "FAIL",
                "passed": False,
                "error": "Missing required argument 'target_name' (or 'scene_test_name' / 'project').",
            }
        return execute_profile_sfx_execution(
            target_name=resolved_target,
            warmup_runs=warmup_runs,
            sample_runs=sample_runs,
            max_cycles=max_cycles,
            inputs=inputs,
            top_n=top_n,
            timeout_seconds=timeout_seconds,
        )

    @app.tool()
    def sfx_resolve_address(
        project: Optional[str] = None,
        target_name: Optional[str] = None,
        scene_test_name: Optional[str] = None,
        symbol: Optional[str] = None,
        symbol_name: Optional[str] = None,
        name: Optional[str] = None,
        end_symbol: Optional[str] = None,
        end_address: Optional[Union[str, int]] = None,
        address: Optional[Union[str, int]] = None,
        addr: Optional[Union[str, int]] = None,
        prefix: Optional[str] = None,
        range: Optional[str] = None,
        max_results: int = 20,
    ) -> Dict[str, Any]:
        """Use INSTEAD OF grep on .cpu.sym/.dbg to resolve symbol <-> address and inspect memory layout from ld65 build outputs.

        Args:
            project: Path or name of the demo or test directory (optional; defaults to last used or 'IsoEngine'; also accepts 'target_name' or 'scene_test_name').
            symbol: Symbol name to look up (with or without leading '.', also accepts 'symbol_name' or 'name').
            end_symbol: Optional end symbol or label name to compute byte_span, cache_lines_16b, and fits_in_512b_cache from 'symbol'.
            end_address: Optional end address (hex string or int) to compute byte_span and cache residency from 'symbol'.
            address: Address as hex string or int, or bank:offset (e.g. '80:9DA6', '0xAE88', 0x700B1A; also accepts 'addr').
            prefix: Prefix string to find matching symbols (e.g. 'ISO_CAM').
            range: Address range string (e.g. '700000-701FFF') to list symbols sorted by address.
            max_results: Maximum number of list items to return (default: 20).

        Returns:
            Structured resolution results including symbol addresses, deltas, byte spans, cache line counts,
            memory regions, sourceLocation (file and line number with context lines), nearestBefore/nearestAfter, and file definitions.
        """
        from mesen_mcp.address_resolver import resolve_address as execute_resolve_address

        resolved_proj = project or target_name or scene_test_name
        resolved_sym = symbol or symbol_name or name
        resolved_addr = address if address is not None else addr
        return execute_resolve_address(
            project=resolved_proj,
            symbol=resolved_sym,
            end_symbol=end_symbol,
            end_address=end_address,
            address=resolved_addr,
            prefix=prefix,
            range=range,
            max_results=max_results,
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

    if args.transport == "stdio":
        mcp.run(transport="stdio")
    else:
        mcp.run(transport="sse")


if __name__ == "__main__":
    main()
