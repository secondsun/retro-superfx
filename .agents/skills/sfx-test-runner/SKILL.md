---
name: sfx-test-runner
description: >-
  Run, verify, and debug automated SuperFX unit tests using Mesen CE in headless mode.
  Instructs agents to prioritize invoking the retro-superfx_mesen MCP tools (run_sfx_test, assert_sfx_math, capture_sfx_render),
  with fallback to CLI scripts.
---

# SuperFX Headless Test Runner Skill

This skill explains how to build, run, and verify SuperFX unit tests located in `snes-sfx-demo/X-GSU/tests/` using Mesen CE.

---

## 1. Primary Execution: retro-superfx_mesen MCP Server

Agents should **always prioritize calling the MCP tools** provided by the `retro-superfx_mesen` server via `call_mcp_tool`.

### A. Run Unit Test (`run_sfx_test`)
Builds and runs a test suite headlessly in Mesen CE and returns structured pass/fail results, logs, and resolved symbols:

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

### B. Mathematical Assertions (`assert_sfx_math`)
Injects parameterized input values into GSURAM or hardware registers, executes the routine under test, and verifies outputs against expectations:

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

### C. Framebuffer Render Capture (`capture_sfx_render`)
Runs a SuperFX graphical demo or scene headlessly and captures a PNG of the framebuffer for visual inspection or perceptual hashing:

```json
{
  "ServerName": "retro-superfx_mesen",
  "ToolName": "capture_sfx_render",
  "Arguments": {
    "scene_test_name": "SpriteScale",
    "output_png_path": "/tmp/spritescale_render.png",
    "timeout_seconds": 15
  }
}
```

---

## 2. Fallback CLI Execution

If the MCP server is not reachable or if custom Lua hook experimentation is required, run via the CLI helper script from the workspace root:

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

---

## 4. Symbol Address Synchronization

Mesen executes breakpoints at 16-bit Work RAM addresses. The lower 2 bytes (`address & 0xFFFF`) of symbols in `Test.cpu.sym` must match the label addresses defined in `test.lua`.

Use the helper script to verify:
```bash
python3 snes-sfx-demo/.agents/skills/create-gsu-test/scripts/extract_syms.py snes-sfx-demo/X-GSU/tests/<name>/Test.cpu.sym
```
Ensure `test.lua` contains:
```lua
local ADDR_TEST_START = 0x...
local ADDR_TEST_STOP  = 0x...
```
matching the extracted symbols.
