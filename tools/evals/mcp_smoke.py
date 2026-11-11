from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import json
from pathlib import Path
import subprocess
from typing import Any


DEFAULT_WORKSPACE_ROOT = Path(__file__).resolve().parents[2]

EXPECTED_MESEN_TOOLS: tuple[str, ...] = (
    "run_sfx_test",
    "assert_sfx_math",
    "capture_sfx_render",
    "profile_sfx_execution",
    "sfx_resolve_address",
)

EXPECTED_LSP_TOOLS: tuple[str, ...] = (
    "sfx_audit_diagnostics",
    "sfx_audit_invariants",
    "sfx_expand_macro",
    "sfx_lookup_symbol",
    "sfx_find_references",
    "sfx_analyze_registers",
)

EXPECTED_STUDIO_TOOLS: tuple[str, ...] = ("sfx_pack_textures",)


@dataclass
class McpSmokeReport:
    passed: bool
    issues: list[dict[str, Any]] = field(default_factory=list)
    mesen_tools: list[str] = field(default_factory=list)
    lsp_tools: list[str] = field(default_factory=list)
    studio_tools: list[str] = field(default_factory=list)
    clean_fixture_errors: int = 0
    buggy_fixture_errors: int = 0
    buggy_fixture_categories: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "passed": self.passed,
            "issues": list(self.issues),
            "mesen_tools": list(self.mesen_tools),
            "lsp_tools": list(self.lsp_tools),
            "studio_tools": list(self.studio_tools),
            "clean_fixture_errors": self.clean_fixture_errors,
            "buggy_fixture_errors": self.buggy_fixture_errors,
            "buggy_fixture_categories": list(self.buggy_fixture_categories),
        }


class StdioMcpClient:
    """Single-session JSON-RPC 2.0 stdio client for RetroLspMCP or StudioMCP."""

    def __init__(
        self,
        workspace_root: Path,
        launcher_rel_path: str = "tools/run-lsp-mcp.sh",
        timeout_seconds: float = 15.0,
    ) -> None:
        self.workspace_root = workspace_root.resolve()
        self.launcher_rel_path = launcher_rel_path
        self.timeout_seconds = timeout_seconds
        self._proc: subprocess.Popen[str] | None = None
        self._next_id = 1

    def __enter__(self) -> StdioMcpClient:
        launcher = self.workspace_root / self.launcher_rel_path
        self._proc = subprocess.Popen(
            [str(launcher)],
            cwd=str(self.workspace_root),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        self._request(
            "initialize",
            {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "retro-superfx-evals", "version": "1.0.0"},
            },
        )
        self._notify("notifications/initialized", {})
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        if self._proc is None:
            return
        try:
            if self._proc.stdin is not None and not self._proc.stdin.closed:
                self._proc.stdin.close()
        except Exception:
            pass
        try:
            self._proc.terminate()
            self._proc.wait(timeout=2.0)
        except Exception:
            try:
                self._proc.kill()
            except Exception:
                pass
        self._proc = None

    def _notify(self, method: str, params: dict[str, Any]) -> None:
        if self._proc is None or self._proc.stdin is None:
            raise RuntimeError("StdioMcpClient process is not running")
        msg = {"jsonrpc": "2.0", "method": method, "params": params}
        self._proc.stdin.write(json.dumps(msg) + "\n")
        self._proc.stdin.flush()

    def _request(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        if self._proc is None or self._proc.stdin is None or self._proc.stdout is None:
            raise RuntimeError("StdioMcpClient process is not running")
        req_id = self._next_id
        self._next_id += 1
        msg = {"jsonrpc": "2.0", "id": req_id, "method": method, "params": params}
        self._proc.stdin.write(json.dumps(msg) + "\n")
        self._proc.stdin.flush()

        while True:
            line = self._proc.stdout.readline()
            if not line:
                stderr_out = ""
                if self._proc.stderr is not None:
                    stderr_out = self._proc.stderr.read()
                raise RuntimeError(
                    f"EOF from MCP server ({self.launcher_rel_path}) while waiting for '{method}' response. Stderr: {stderr_out}"
                )
            line = line.strip()
            if not line:
                continue
            response = json.loads(line)
            if response.get("id") == req_id:
                if "error" in response:
                    raise RuntimeError(
                        f"JSON-RPC error calling '{method}': {response['error']}"
                    )
                return dict(response.get("result", {}))

    def list_tools(self) -> list[dict[str, Any]]:
        result = self._request("tools/list", {})
        return list(result.get("tools", []))

    def call_tool(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        result = self._request(
            "tools/call",
            {"name": name, "arguments": arguments},
        )
        if result.get("isError"):
            content = result.get("content", [])
            err_text = content[0].get("text", "") if content else "Unknown tool error"
            raise RuntimeError(f"Tool '{name}' returned error: {err_text}")
        content = result.get("content", [])
        if not content:
            return {}
        text = content[0].get("text", "{}")
        return dict(json.loads(text))


def _extract_error_count(diag_payload: dict[str, Any]) -> int:
    if "errorCount" in diag_payload:
        return int(diag_payload["errorCount"])
    violations = diag_payload.get("violations", [])
    return sum(
        1
        for v in violations
        if str(v.get("severity", "ERROR")).upper() == "ERROR"
    )


def _extract_categories(diag_payload: dict[str, Any]) -> list[str]:
    categories: set[str] = set()
    for cat in diag_payload.get("categories", []):
        if cat:
            categories.add(str(cat))
    for v in diag_payload.get("violations", []):
        cat = v.get("category")
        if cat:
            categories.add(str(cat))
    return sorted(categories)


class McpSmokeVerifier:
    """Verifies live or injected MesenMCP and RetroLspMCP contracts and diagnostic fixtures."""

    def __init__(
        self,
        workspace_root: Path = DEFAULT_WORKSPACE_ROOT,
        lsp_client: Any | None = None,
        mesen_server: Any | None = None,
        studio_client: Any | None = None,
    ) -> None:
        self.workspace_root = workspace_root.resolve()
        self._lsp_client = lsp_client
        self._mesen_server = mesen_server
        self._studio_client = studio_client

    def verify(self) -> McpSmokeReport:
        issues: list[dict[str, Any]] = []
        mesen_tools = self._verify_mesen(issues)
        (
            lsp_tools,
            clean_errors,
            buggy_errors,
            buggy_categories,
        ) = self._verify_lsp(issues)
        studio_tools = self._verify_studio(issues)

        return McpSmokeReport(
            passed=len(issues) == 0,
            issues=issues,
            mesen_tools=mesen_tools,
            lsp_tools=lsp_tools,
            studio_tools=studio_tools,
            clean_fixture_errors=clean_errors,
            buggy_fixture_errors=buggy_errors,
            buggy_fixture_categories=buggy_categories,
        )

    def _verify_mesen(self, issues: list[dict[str, Any]]) -> list[str]:
        server = self._mesen_server
        if server is None:
            from mesen_mcp.server import create_server

            server = create_server()

        try:
            raw_tools = asyncio.run(server.list_tools())
        except Exception as exc:
            issues.append(
                {
                    "code": "MESEN_LIST_TOOLS_FAILED",
                    "message": f"Failed to list tools from MesenMCP: {exc}",
                }
            )
            return []

        tool_map: dict[str, Any] = {}
        for tool in raw_tools:
            name = getattr(tool, "name", None) or (
                tool.get("name") if isinstance(tool, dict) else None
            )
            schema = (
                getattr(tool, "input_schema", None)
                or getattr(tool, "inputSchema", None)
                or (
                    tool.get("inputSchema") or tool.get("input_schema")
                    if isinstance(tool, dict)
                    else None
                )
            )
            if name:
                tool_map[str(name)] = schema

        for expected in EXPECTED_MESEN_TOOLS:
            if expected not in tool_map:
                issues.append(
                    {
                        "code": "MISSING_MESEN_TOOL",
                        "message": f"MesenMCP is missing required tool '{expected}'",
                    }
                )
            else:
                schema = tool_map[expected]
                if not isinstance(schema, dict) or "properties" not in schema:
                    issues.append(
                        {
                            "code": "INVALID_MESEN_SCHEMA",
                            "message": f"MesenMCP tool '{expected}' has an invalid input schema",
                        }
                    )

        if "sfx_resolve_address" in tool_map:
            sample_project = (
                self.workspace_root
                / "tools"
                / "mesen-mcp"
                / "tests"
                / "fixtures"
                / "sample_project"
            )
            try:
                call_res = asyncio.run(
                    server.call_tool(
                        "sfx_resolve_address",
                        {
                            "project": str(sample_project),
                            "symbol": "GSU_JOYPAD",
                        },
                    )
                )
                is_error = getattr(call_res, "is_error", False)
                content = getattr(call_res, "content", [])
                payload = json.loads(content[0].text) if content else {}
                if is_error or payload.get("status") != "SUCCESS" or not payload.get("found"):
                    issues.append(
                        {
                            "code": "RESOLVE_ADDRESS_FAILED",
                            "message": f"sfx_resolve_address failed smoke check: {payload}",
                        }
                    )
            except Exception as exc:
                issues.append(
                    {
                        "code": "RESOLVE_ADDRESS_FAILED",
                        "message": f"sfx_resolve_address raised exception: {exc}",
                    }
                )

        return list(tool_map.keys())

    def _verify_lsp(
        self, issues: list[dict[str, Any]]
    ) -> tuple[list[str], int, int, list[str]]:
        if self._lsp_client is not None:
            return self._verify_lsp_with_client(self._lsp_client, issues)

        try:
            with StdioMcpClient(workspace_root=self.workspace_root) as client:
                return self._verify_lsp_with_client(client, issues)
        except Exception as exc:
            issues.append(
                {
                    "code": "LSP_CONNECTION_FAILED",
                    "message": f"Failed to communicate with RetroLspMCP over stdio: {exc}",
                }
            )
            return [], 0, 0, []

    def _verify_lsp_with_client(
        self, client: Any, issues: list[dict[str, Any]]
    ) -> tuple[list[str], int, int, list[str]]:
        raw_tools = client.list_tools()
        tool_map: dict[str, Any] = {}
        for tool in raw_tools:
            name = tool.get("name") if isinstance(tool, dict) else getattr(tool, "name", None)
            schema = (
                (tool.get("inputSchema") or tool.get("input_schema"))
                if isinstance(tool, dict)
                else (getattr(tool, "inputSchema", None) or getattr(tool, "input_schema", None))
            )
            if name:
                tool_map[str(name)] = schema

        for expected in EXPECTED_LSP_TOOLS:
            if expected not in tool_map:
                issues.append(
                    {
                        "code": "MISSING_LSP_TOOL",
                        "message": f"RetroLspMCP is missing required tool '{expected}'",
                    }
                )
            else:
                schema = tool_map[expected]
                if not isinstance(schema, dict) or "properties" not in schema:
                    issues.append(
                        {
                            "code": "INVALID_LSP_SCHEMA",
                            "message": f"RetroLspMCP tool '{expected}' has an invalid input schema",
                        }
                    )

        clean_errors = 0
        buggy_errors = 0
        buggy_categories: list[str] = []

        if "sfx_audit_diagnostics" in tool_map:
            clean_rel = "tools/evals/fixtures/clean_fx3.sgs"
            clean_res = client.call_tool("sfx_audit_diagnostics", {"path": clean_rel})
            clean_errors = _extract_error_count(clean_res)
            if clean_errors != 0 or clean_res.get("valid") is False:
                issues.append(
                    {
                        "code": "CLEAN_FIXTURE_FAILED",
                        "message": f"Expected 0 errors on {clean_rel}, got {clean_errors}: {clean_res}",
                    }
                )

            buggy_rel = "tools/evals/fixtures/buggy_fx3.sgs"
            buggy_res = client.call_tool("sfx_audit_diagnostics", {"path": buggy_rel})
            buggy_errors = _extract_error_count(buggy_res)
            buggy_categories = _extract_categories(buggy_res)
            if buggy_errors <= 0:
                issues.append(
                    {
                        "code": "BUGGY_FIXTURE_MISSED_ERRORS",
                        "message": f"Expected >0 errors on {buggy_rel}, got {buggy_errors}",
                    }
                )
            else:
                has_hw = "HARDWARE_INVARIANT" in buggy_categories
                has_syntax_or_stack = any(
                    cat in buggy_categories
                    for cat in ("SYNTAX", "STACK_INVARIANT", "STACK")
                )
                if not (has_hw and has_syntax_or_stack):
                    issues.append(
                        {
                            "code": "BUGGY_FIXTURE_MISSING_CATEGORY",
                            "message": (
                                f"Expected HARDWARE_INVARIANT and SYNTAX/STACK_INVARIANT categories "
                                f"on {buggy_rel}, got {buggy_categories}"
                            ),
                        }
                    )

        camera_uri = (
            self.workspace_root
            / "snes-sfx-demo"
            / "X-GSU"
            / "gsu_maths"
            / "gsu_iso_camera.i"
        ).resolve().as_uri()

        if "sfx_lookup_symbol" in tool_map:
            lookup_res = client.call_tool(
                "sfx_lookup_symbol",
                {
                    "symbol": "iso_camera_set_yaw",
                    "path": "snes-sfx-demo/X-GSU/",
                    "uri": camera_uri,
                },
            )
            loc = lookup_res.get("location") or {}
            line_num = loc.get("lineNumber", 0)
            if not lookup_res.get("found") or not isinstance(line_num, int) or line_num < 1:
                issues.append(
                    {
                        "code": "SYMBOL_LOOKUP_FAILED",
                        "message": f"sfx_lookup_symbol failed smoke check: {lookup_res}",
                    }
                )

        if "sfx_find_references" in tool_map:
            refs_res = client.call_tool(
                "sfx_find_references",
                {
                    "symbol": "iso_camera_set_yaw",
                    "path": "snes-sfx-demo/X-GSU/",
                    "uri": camera_uri,
                },
            )
            refs = refs_res.get("references") or []
            first_line = refs[0].get("lineNumber", 0) if refs else 0
            if (
                refs_res.get("totalReferences", 0) < 1
                or len(refs) < 1
                or not isinstance(first_line, int)
                or first_line < 1
            ):
                issues.append(
                    {
                        "code": "FIND_REFERENCES_FAILED",
                        "message": f"sfx_find_references failed smoke check: {refs_res}",
                    }
                )

        return list(tool_map.keys()), clean_errors, buggy_errors, buggy_categories

    def _verify_studio(self, issues: list[dict[str, Any]]) -> list[str]:
        if self._studio_client is not None:
            return self._verify_studio_with_client(self._studio_client, issues)

        launcher = self.workspace_root / "sfx-studio" / "scripts" / "run-mcp.sh"
        if not launcher.exists():
            return []

        try:
            with StdioMcpClient(
                workspace_root=self.workspace_root,
                launcher_rel_path="sfx-studio/scripts/run-mcp.sh",
            ) as client:
                return self._verify_studio_with_client(client, issues)
        except Exception as exc:
            issues.append(
                {
                    "code": "STUDIO_CONNECTION_FAILED",
                    "message": f"Failed to communicate with StudioMCP over stdio: {exc}",
                }
            )
            return []

    def _verify_studio_with_client(
        self, client: Any, issues: list[dict[str, Any]]
    ) -> list[str]:
        raw_tools = client.list_tools()
        tool_map: dict[str, Any] = {}
        for tool in raw_tools:
            name = tool.get("name") if isinstance(tool, dict) else getattr(tool, "name", None)
            schema = (
                (tool.get("inputSchema") or tool.get("input_schema"))
                if isinstance(tool, dict)
                else (getattr(tool, "inputSchema", None) or getattr(tool, "input_schema", None))
            )
            if name:
                tool_map[str(name)] = schema

        for expected in EXPECTED_STUDIO_TOOLS:
            if expected not in tool_map:
                issues.append(
                    {
                        "code": "MISSING_STUDIO_TOOL",
                        "message": f"StudioMCP is missing required tool '{expected}'",
                    }
                )
            else:
                schema = tool_map[expected]
                if not isinstance(schema, dict) or "properties" not in schema:
                    issues.append(
                        {
                            "code": "INVALID_STUDIO_SCHEMA",
                            "message": f"StudioMCP tool '{expected}' has an invalid input schema",
                        }
                    )

        if "sfx_pack_textures" in tool_map:
            project_path = (
                self.workspace_root
                / "snes-sfx-demo"
                / "X-GSU"
                / "IsoEngine"
                / "Data"
                / "TEXTURES.sfxproj"
            )
            if project_path.exists():
                call_args = {
                    "project_path": "snes-sfx-demo/X-GSU/IsoEngine/Data/TEXTURES.sfxproj",
                    "action": "validate",
                }
                try:
                    res = client.call_tool("sfx_pack_textures", call_args)
                    status = res.get("status")
                    valid = res.get("valid")
                    if status != "SUCCESS" or valid is not True:
                        issues.append(
                            {
                                "code": "STUDIO_VALIDATION_FAILED",
                                "message": f"sfx_pack_textures validation smoke check failed: {res}",
                            }
                        )
                except Exception as exc:
                    issues.append(
                        {
                            "code": "STUDIO_VALIDATION_FAILED",
                            "message": f"sfx_pack_textures raised exception: {exc}",
                        }
                    )

        return list(tool_map.keys())
