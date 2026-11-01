from __future__ import annotations

import json
from pathlib import Path
import subprocess
from typing import Any

import pytest

from evals.mcp_smoke import (
    EXPECTED_LSP_TOOLS,
    EXPECTED_MESEN_TOOLS,
    McpSmokeVerifier,
)


WORKSPACE_ROOT = Path(__file__).resolve().parents[3]


class FakeMesenTool:
    def __init__(self, name: str, properties: dict[str, Any] | None = None) -> None:
        self.name = name
        self.input_schema = {
            "type": "object",
            "properties": properties if properties is not None else {"arg": {"type": "string"}},
        }


class FakeToolContent:
    def __init__(self, payload: dict[str, Any]) -> None:
        self.text = json.dumps(payload)


class FakeToolResult:
    def __init__(self, payload: dict[str, Any], is_error: bool = False) -> None:
        self.content = [FakeToolContent(payload)]
        self.is_error = is_error


class FakeMesenServer:
    def __init__(
        self,
        tools: list[str] | None = None,
        resolve_payload: dict[str, Any] | None = None,
    ) -> None:
        names = tools if tools is not None else list(EXPECTED_MESEN_TOOLS)
        self._tools = [FakeMesenTool(name) for name in names]
        self._resolve_payload = (
            resolve_payload
            if resolve_payload is not None
            else {
                "status": "SUCCESS",
                "found": True,
                "address": "0x700B1A",
                "region": "GSURAM",
            }
        )

    async def list_tools(self) -> list[FakeMesenTool]:
        return self._tools

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> FakeToolResult:
        if name == "sfx_resolve_address":
            return FakeToolResult(self._resolve_payload)
        return FakeToolResult({"status": "PASS"})


class FakeLspClient:
    def __init__(
        self,
        tools: list[str] | None = None,
        clean_diag: dict[str, Any] | None = None,
        buggy_diag: dict[str, Any] | None = None,
        lookup_res: dict[str, Any] | None = None,
        refs_res: dict[str, Any] | None = None,
    ) -> None:
        names = tools if tools is not None else list(EXPECTED_LSP_TOOLS)
        self._tools = [
            {
                "name": name,
                "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}}},
            }
            for name in names
        ]
        self._clean_diag = (
            clean_diag
            if clean_diag is not None
            else {"valid": True, "violations": [], "summary": "0 violations"}
        )
        self._buggy_diag = (
            buggy_diag
            if buggy_diag is not None
            else {
                "valid": False,
                "violations": [
                    {
                        "line": 8,
                        "rule": "NO_HARDWARE_MERGE",
                        "category": "HARDWARE_INVARIANT",
                        "severity": "ERROR",
                        "message": "Hardware MERGE not supported on FX3",
                    },
                    {
                        "line": 12,
                        "rule": "MISMATCHED_BLOCK_CLOSER",
                        "category": "SYNTAX",
                        "severity": "ERROR",
                        "message": "Mismatched block closer",
                    },
                    {
                        "line": 18,
                        "rule": "STACK_LEAK_ON_RETURN",
                        "category": "STACK_INVARIANT",
                        "severity": "ERROR",
                        "message": "Stack displacement non-zero",
                    },
                ],
                "summary": "3 violations",
            }
        )
        self._lookup_res = (
            lookup_res
            if lookup_res is not None
            else {
                "symbol": "iso_camera_set_yaw",
                "found": True,
                "location": {
                    "path": "snes-sfx-demo/X-GSU/gsu_maths/gsu_iso_camera.i",
                    "lineNumber": 24,
                },
            }
        )
        self._refs_res = (
            refs_res
            if refs_res is not None
            else {
                "symbol": "iso_camera_set_yaw",
                "totalReferences": 3,
                "references": [
                    {
                        "path": "snes-sfx-demo/X-GSU/IsoEngine/SuperFX.sgs",
                        "lineNumber": 305,
                        "kind": "call",
                    }
                ],
            }
        )

    def list_tools(self) -> list[dict[str, Any]]:
        return self._tools

    def call_tool(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        if name == "sfx_audit_diagnostics":
            target = str(arguments.get("path", "") or arguments.get("uri", ""))
            if "clean_fx3" in target:
                return self._clean_diag
            return self._buggy_diag
        if name == "sfx_lookup_symbol":
            return self._lookup_res
        if name == "sfx_find_references":
            return self._refs_res
        return {}


def test_fake_passing_contract_succeeds() -> None:
    verifier = McpSmokeVerifier(
        workspace_root=WORKSPACE_ROOT,
        lsp_client=FakeLspClient(),
        mesen_server=FakeMesenServer(),
    )
    report = verifier.verify()
    assert report.passed is True
    assert report.issues == []
    assert report.clean_fixture_errors == 0
    assert report.buggy_fixture_errors == 3
    assert "HARDWARE_INVARIANT" in report.buggy_fixture_categories
    assert "SYNTAX" in report.buggy_fixture_categories


def test_detects_missing_mesen_tool() -> None:
    missing_one = [t for t in EXPECTED_MESEN_TOOLS if t != "capture_sfx_render"]
    verifier = McpSmokeVerifier(
        workspace_root=WORKSPACE_ROOT,
        lsp_client=FakeLspClient(),
        mesen_server=FakeMesenServer(tools=missing_one),
    )
    report = verifier.verify()
    assert report.passed is False
    codes = [issue["code"] for issue in report.issues]
    assert "MISSING_MESEN_TOOL" in codes


def test_detects_missing_lsp_tool() -> None:
    missing_one = [t for t in EXPECTED_LSP_TOOLS if t != "sfx_find_references"]
    verifier = McpSmokeVerifier(
        workspace_root=WORKSPACE_ROOT,
        lsp_client=FakeLspClient(tools=missing_one),
        mesen_server=FakeMesenServer(),
    )
    report = verifier.verify()
    assert report.passed is False
    codes = [issue["code"] for issue in report.issues]
    assert "MISSING_LSP_TOOL" in codes


def test_detects_unexpected_errors_in_clean_fixture() -> None:
    bad_clean = {
        "valid": False,
        "violations": [
            {
                "line": 4,
                "rule": "PIPELINE_SETTLING_8_NOP",
                "category": "HARDWARE_INVARIANT",
                "severity": "ERROR",
                "message": "Unexpected error in clean fixture",
            }
        ],
    }
    verifier = McpSmokeVerifier(
        workspace_root=WORKSPACE_ROOT,
        lsp_client=FakeLspClient(clean_diag=bad_clean),
        mesen_server=FakeMesenServer(),
    )
    report = verifier.verify()
    assert report.passed is False
    codes = [issue["code"] for issue in report.issues]
    assert "CLEAN_FIXTURE_FAILED" in codes


def test_detects_missed_violations_in_buggy_fixture() -> None:
    missed_buggy = {
        "valid": True,
        "violations": [],
    }
    verifier = McpSmokeVerifier(
        workspace_root=WORKSPACE_ROOT,
        lsp_client=FakeLspClient(buggy_diag=missed_buggy),
        mesen_server=FakeMesenServer(),
    )
    report = verifier.verify()
    assert report.passed is False
    codes = [issue["code"] for issue in report.issues]
    assert "BUGGY_FIXTURE_MISSED_ERRORS" in codes


def test_detects_missing_category_in_buggy_fixture() -> None:
    only_hardware = {
        "valid": False,
        "violations": [
            {
                "line": 8,
                "rule": "NO_HARDWARE_MERGE",
                "category": "HARDWARE_INVARIANT",
                "severity": "ERROR",
                "message": "Hardware MERGE not supported on FX3",
            }
        ],
    }
    verifier = McpSmokeVerifier(
        workspace_root=WORKSPACE_ROOT,
        lsp_client=FakeLspClient(buggy_diag=only_hardware),
        mesen_server=FakeMesenServer(),
    )
    report = verifier.verify()
    assert report.passed is False
    codes = [issue["code"] for issue in report.issues]
    assert "BUGGY_FIXTURE_MISSING_CATEGORY" in codes


def test_live_workspace_mcp_smoke_passes() -> None:
    verifier = McpSmokeVerifier(workspace_root=WORKSPACE_ROOT)
    report = verifier.verify()
    assert report.passed is True, f"Live MCP smoke failed: {report.issues}"
    assert set(EXPECTED_MESEN_TOOLS).issubset(set(report.mesen_tools))
    assert set(EXPECTED_LSP_TOOLS).issubset(set(report.lsp_tools))
    assert report.clean_fixture_errors == 0
    assert report.buggy_fixture_errors > 0
    assert "HARDWARE_INVARIANT" in report.buggy_fixture_categories
    assert any(
        cat in report.buggy_fixture_categories for cat in ("SYNTAX", "STACK_INVARIANT")
    )


def test_cli_tier0_mcp_smoke_passes() -> None:
    script = WORKSPACE_ROOT / "tools" / "run-evals.sh"
    res = subprocess.run(
        [str(script), "--tier", "0", "--suite", "mcp-smoke", "--json"],
        cwd=str(WORKSPACE_ROOT),
        capture_output=True,
        text=True,
        check=False,
    )
    assert res.returncode == 0, f"STDOUT: {res.stdout}\nSTDERR: {res.stderr}"
    payload = json.loads(res.stdout)
    assert payload["passed"] is True
    assert payload["tier0"]["mcp_smoke"]["passed"] is True
