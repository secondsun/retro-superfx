---
name: sfx-test-runner
description: >-
  Run, verify, and debug automated SuperFX unit tests using Mesen CE in headless mode.
  Instructs agents to prioritize invoking the retro-superfx_mesen MCP tools (run_sfx_test, assert_sfx_math, capture_sfx_render, profile_sfx_execution, sfx_resolve_address),
  with fallback to CLI scripts.
---

# SuperFX Headless Test Runner Skill

This skill explains how to build, run, and verify SuperFX unit tests located in `snes-sfx-demo/X-GSU/tests/` using Mesen CE.

---

## 1. Primary Execution: retro-superfx_mesen MCP Server (`call_mcp_tool`)

> [!IMPORTANT]
> **Always invoke `retro-superfx_mesen` tools via the `call_mcp_tool` tool first.**
> In Antigravity, these MCP tools are lazy-loaded behind the top-level **`call_mcp_tool`** tool declaration — there are no top-level `run_sfx_test` or `capture_sfx_render` tool declarations. Call **`call_mcp_tool`** directly with `ServerName: "retro-superfx_mesen"`, `ToolName: "<tool>"`, and `Arguments: {...}` (you do **not** need to read `<appDataDir>/mcp/` schema files first, and **never** read `tools/mesen-mcp/` Python internals to learn CLI flags).

### A. Run Unit Test (`run_sfx_test` via `call_mcp_tool`)
Builds and runs a test suite headlessly in Mesen CE and returns structured pass/fail results, logs, and resolved symbols.

Call the **`call_mcp_tool`** tool with:
```json
{
  "ServerName": "retro-superfx_mesen",
  "ToolName": "run_sfx_test",
  "Arguments": {
    "test_name": "vector3_add",
    "timeout_seconds": 15
  }
}
```

### B. Mathematical Assertions (`assert_sfx_math` via `call_mcp_tool`)
Injects parameterized input values into GSURAM or hardware registers, executes the routine under test, and verifies outputs against expectations.

Call the **`call_mcp_tool`** tool with:
```json
{
  "ServerName": "retro-superfx_mesen",
  "ToolName": "assert_sfx_math",
  "Arguments": {
    "test_name": "sqrt",
    "input_overrides": {
      "INPUT_VAL": 144
    },
    "expected_outputs": {
      "OUTPUT_VAL": 12
    },
    "epsilon": 0,
    "timeout_seconds": 15
  }
}
```

### C. Framebuffer Render Capture (`capture_sfx_render` via `call_mcp_tool`)
Runs a SuperFX graphical demo or scene (`IsoEngine`, `SpriteScale`) headlessly and captures a PNG of the framebuffer for visual inspection or perceptual hashing. After `capture_sfx_render` completes, call **`read_file`** directly on the returned `png_path` to visually inspect the rendered image.

Call the **`call_mcp_tool`** tool with:
```json
{
  "ServerName": "retro-superfx_mesen",
  "ToolName": "capture_sfx_render",
  "Arguments": {
    "scene_test_name": "IsoEngine",
    "timeout_seconds": 15
  }
}
```

### D. Execution Profiling (`profile_sfx_execution` via `call_mcp_tool`)
Profiles GSU cycle counts, hotspots by function and label, instruction cache residency, and VBlank FPS.

Call the **`call_mcp_tool`** tool with:
```json
{
  "ServerName": "retro-superfx_mesen",
  "ToolName": "profile_sfx_execution",
  "Arguments": {
    "target_name": "sqrt",
    "sample_runs": 1,
    "top_n": 10
  }
}
```

### E. Symbol & Address Resolution (`sfx_resolve_address` via `call_mcp_tool`)
Resolves symbol names to linker addresses, PC addresses to nearest enclosing functions or labels with deltas, and memory layout ranges from `.dbg` and `.cpu.sym` build outputs (never `grep` `.cpu.sym` or `.dbg` files).

Call the **`call_mcp_tool`** tool with:
```json
{
  "ServerName": "retro-superfx_mesen",
  "ToolName": "sfx_resolve_address",
  "Arguments": {
    "project": "snes-sfx-demo/X-GSU/IsoEngine",
    "symbol": "GSU_JOYPAD"
  }
}
```

Resolve address to nearest label / function within the same memory region (`call_mcp_tool`):
```json
{
  "ServerName": "retro-superfx_mesen",
  "ToolName": "sfx_resolve_address",
  "Arguments": {
    "project": "snes-sfx-demo/X-GSU/IsoEngine",
    "address": "80:9DA6"
  }
}
```

Inspect memory layout in a range (sorted ascending, duplicates collapsed, via `call_mcp_tool`):
```json
{
  "ServerName": "retro-superfx_mesen",
  "ToolName": "sfx_resolve_address",
  "Arguments": {
    "project": "snes-sfx-demo/X-GSU/IsoEngine",
    "range": "700000-701FFF"
  }
}
```

---

## 2. Emergency Fallback CLI Execution

Only if `call_mcp_tool` on `retro-superfx_mesen` is unreachable or custom Lua hook experimentation is required, run via the CLI helper script from the workspace root:

```bash
./tools/run-sfx-test.sh <test_name> [timeout_seconds]
```

Examples:
```bash
./tools/run-sfx-test.sh vector3_add 10
./tools/run-sfx-test.sh length 10
./tools/run-sfx-test.sh sqrt 10
```

---

## 3. Test Execution Pipeline

When executing tests under the hood (or via fallback CLI):

```
1. make -C snes-sfx-demo/X-GSU/tests/<name> clean all
2. Extract symbol addresses from Test.cpu.sym
3. Sync test_setup / test_start / test_stop addresses in test.lua
4. Mesen --testrunner --timeout=<sec> test.lua Test.sfc
```

### Critical Mesen & Python Environment Notes
- Always pass `--testrunner` (all lowercase, no hyphen). Passing `--test-runner` with a hyphen inadvertently launches interactive GUI mode.
- For Python helper scripts requiring `PIL` (`Pillow`) or `pytest` (such as image comparison or perceptual hashing), use `./tools/mesen-mcp/.venv/bin/python` rather than system `python3`.
- **Work-in-Progress (WIP) Tests**: `buffer_rnc`, `decode_rnc`, and `hufftable_rnc` in `snes-sfx-demo/X-GSU/tests/` are works in progress and are not expected to pass. Skip them when running full test suite sweeps.

---

## 4. Symbol Address Synchronization

Mesen executes breakpoints at 16-bit Work RAM addresses. The lower 2 bytes (`address & 0xFFFF`) of symbols in `Test.cpu.sym` must match the label addresses defined in `test.lua`.

Use the helper script to verify:
```bash
python3 .agents/skills/create-gsu-test/scripts/extract_syms.py snes-sfx-demo/X-GSU/tests/<name>/Test.cpu.sym
```
Ensure `test.lua` contains:
```lua
local ADDR_TEST_START = 0x...
local ADDR_TEST_STOP  = 0x...
```
matching the extracted symbols.
