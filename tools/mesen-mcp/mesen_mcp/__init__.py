"""Mesen MCP Server package."""

from mesen_mcp.math_verifier import assert_sfx_math
from mesen_mcp.profiler import profile_sfx_execution
from mesen_mcp.render_capture import capture_sfx_render
from mesen_mcp.runner import run_sfx_test
from mesen_mcp.session import (
    FakeMesenRunner,
    MesenRunner,
    SfxTestSession,
    SubprocessMesenRunner,
    SymbolTable,
)

__version__ = "0.2.0"

__all__ = [
    "assert_sfx_math",
    "capture_sfx_render",
    "profile_sfx_execution",
    "run_sfx_test",
    "FakeMesenRunner",
    "MesenRunner",
    "SfxTestSession",
    "SubprocessMesenRunner",
    "SymbolTable",
]
