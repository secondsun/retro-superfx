---
name: sfx-test-runner
description: >-
  Run, verify, and debug automated SuperFX unit tests using Mesen CE in headless mode with Lua callbacks.
  Use when asked to test SuperFX assembly functions, inspect test outputs, or verify ROM execution.
---

# SuperFX Headless Test Runner Skill

This skill explains how to build, run, and verify SuperFX unit tests located in `snes-sfx-demo/X-GSU/tests/` using Mesen CE.

---

## 1. Quick Execution

To run any test from the root directory:
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

## 2. Test Execution Pipeline

```
1. make -C snes-sfx-demo/X-GSU/tests/<name> clean all
2. Extract symbol addresses from Test.cpu.sym
3. Sync test_setup / test_start / test_stop addresses in test.lua
4. Mesen --testrunner --timeout=<sec> test.lua Test.sfc
```

### Critical Mesen Flags
- Always pass `--testrunner` (all lowercase, no hyphen).
- Passing `--test-runner` with a hyphen inadvertently launches interactive GUI mode.
- Mesen binary is located at `/home/summers/Programs/Mesen`.

---

## 3. Symbol Address Synchronization

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

