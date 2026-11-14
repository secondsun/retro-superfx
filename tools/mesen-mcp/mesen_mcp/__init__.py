"""Mesen MCP Server package."""

from mesen_mcp.address_resolver import resolve_address
from mesen_mcp.artifacts import BuildArtifactIndex, GsuTarget, ResolvedSymbol
from mesen_mcp.math_verifier import assert_sfx_math
from mesen_mcp.profiler import profile_sfx_execution
from mesen_mcp.render_capture import capture_sfx_render
from mesen_mcp.runner import run_sfx_test
from mesen_mcp.session import (
    FakeMesenRunner,
    HarnessContext,
    HarnessOutcome,
    MesenRunner,
    SfxTestSession,
    SubprocessMesenRunner,
    SymbolTable,
)

__version__ = "0.2.0"

__all__ = [
    "BuildArtifactIndex",
    "FakeMesenRunner",
    "GsuTarget",
    "HarnessContext",
    "HarnessOutcome",
    "MesenRunner",
    "ResolvedSymbol",
    "SfxTestSession",
    "SubprocessMesenRunner",
    "SymbolTable",
    "assert_sfx_math",
    "capture_sfx_render",
    "profile_sfx_execution",
    "resolve_address",
    "run_sfx_test",
]
