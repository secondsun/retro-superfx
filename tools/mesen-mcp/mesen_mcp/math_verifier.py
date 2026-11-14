"""Mathematical verification and parameterized assertions for SuperFX routines."""

from typing import Any, Dict, Optional

from mesen_mcp.session import MesenRunner, SfxTestSession


def assert_sfx_math(
    test_name: str,
    input_overrides: Dict[str, int],
    expected_outputs: Dict[str, int],
    epsilon: int = 0,
    timeout_seconds: int = 15,
    mesen_path: Optional[str] = None,
    runner: Optional[MesenRunner] = None,
) -> Dict[str, Any]:
    """Assert mathematical correctness of SuperFX routines with parameterized inputs."""
    try:
        session = SfxTestSession.from_test_name(
            test_name, runner=runner, mesen_path=mesen_path
        )
    except FileNotFoundError as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(e),
            "results": {},
            "diffs": {},
        }

    try:
        outcome = session.run_harness(
            lambda s, ctx: s.build_math_harness_lua(
                input_overrides=input_overrides,
                expected_outputs=expected_outputs,
                epsilon=epsilon,
                results_json_path=str(ctx.results_json_path),
            ),
            timeout_seconds=timeout_seconds,
            json_sentinel="MATH_RESULTS_JSON",
            compile_mode="if_stale",
        )
    except RuntimeError as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "exit_code": 1,
            "error": "Compilation failed",
            "output": str(e),
            "results": {},
            "diffs": {},
        }
    except (ValueError, FileNotFoundError) as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(e),
            "results": {},
            "diffs": {},
        }
    except Exception as e:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": str(e),
            "results": {},
            "diffs": {},
            "symbols": session.symbols.symbols,
        }

    if outcome.timed_out:
        return {
            "status": "FAIL",
            "test": test_name,
            "exit_code": -1,
            "error": f"Execution timed out after {timeout_seconds} seconds",
            "output": outcome.output,
            "results": {},
            "diffs": {},
            "symbols": session.symbols.symbols,
        }

    if outcome.data is None:
        return {
            "status": "FAIL",
            "test": test_name,
            "error": "Failed to parse math assertion results from Mesen output",
            "output": outcome.output,
            "results": {},
            "diffs": {},
        }

    passed = bool(outcome.data.get("passed", False))
    return {
        "status": "PASS" if passed else "FAIL",
        "test": test_name,
        "exit_code": outcome.exit_code,
        "results": outcome.data.get("results", {}),
        "diffs": outcome.data.get("diffs", {}),
        "output": outcome.output,
        "symbols": session.symbols.symbols,
    }
