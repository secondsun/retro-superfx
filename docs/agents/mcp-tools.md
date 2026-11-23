# MCP Tools & Workspace CLI Reference

Disclosed reference for [AGENTS.md](../../AGENTS.md). Load when you need copy-paste `call_mcp_tool` JSON payloads or workspace helper script flags.

## 1. Ready-to-Copy `call_mcp_tool` Payloads

### `retro-superfx_mesen` (Headless Emulation, Profiling & Address Resolution)

Run one test, a batch (`test_names`), or `"all"`:
```json
{ "ServerName": "retro-superfx_mesen", "ToolName": "run_sfx_test",
  "Arguments": { "test_name": "all", "timeout_seconds": 15 } }
```
```json
{ "ServerName": "retro-superfx_mesen", "ToolName": "run_sfx_test",
  "Arguments": { "test_names": ["test_spill_alloc", "sprite_mask"], "timeout_seconds": 15 } }
```

Capture framebuffer PNG (inspect result directly via `read_file`):
```json
{ "ServerName": "retro-superfx_mesen", "ToolName": "capture_sfx_render",
  "Arguments": { "scene_test_name": "IsoEngine", "timeout_seconds": 15 } }
```

Profile GSU execution cycles & cache residency:
```json
{ "ServerName": "retro-superfx_mesen", "ToolName": "profile_sfx_execution",
  "Arguments": { "target_name": "IsoEngine", "warmup_runs": 1, "sample_runs": 2, "timeout_seconds": 15 } }
```

Resolve single symbol, cache span, batch symbols, or `.dbg` file summary:
```json
{ "ServerName": "retro-superfx_mesen", "ToolName": "sfx_resolve_address",
  "Arguments": { "project": "snes-sfx-demo/X-GSU/IsoEngine", "symbol": "draw_cached_trap", "end_symbol": "@trap_finished" } }
```
```json
{ "ServerName": "retro-superfx_mesen", "ToolName": "sfx_resolve_address",
  "Arguments": { "project": "snes-sfx-demo/X-GSU/tests/sprite_mask",
                 "symbols": ["test_setup", "test_start", "test_stop", "TEST_CASE_IDX", "TEST_RESULT"] } }
```

### `retro-superfx_lsp` (Static Analysis, Symbol Lookup, Outline & References)

Lookup a symbol definition + doc comment, or outline all definitions in a large `.i` file:
```json
{ "ServerName": "retro-superfx_lsp", "ToolName": "sfx_lookup_symbol",
  "Arguments": { "symbol": "map_compile_quad_face", "path": "snes-sfx-demo/X-GSU/IsoEngine/" } }
```
```json
{ "ServerName": "retro-superfx_lsp", "ToolName": "sfx_lookup_symbol",
  "Arguments": { "path": "snes-sfx-demo/X-GSU/gsu_mesh/gsu_mesh.i", "outline": true } }
```

Find symbol usages across the workspace:
```json
{ "ServerName": "retro-superfx_lsp", "ToolName": "sfx_find_references",
  "Arguments": { "symbol": "iso_camera_set_yaw", "path": "snes-sfx-demo/X-GSU/" } }
```

Run static diagnostics (syntax, FX3 invariants, stack balance):
```json
{ "ServerName": "retro-superfx_lsp", "ToolName": "sfx_audit_diagnostics",
  "Arguments": { "path": "snes-sfx-demo/X-GSU/IsoEngine/map_grid.i" } }
```

### `retro-superfx_studio` (Texture Atlases & Isometric Levels)

```json
{ "ServerName": "retro-superfx_studio", "ToolName": "sfx_pack_textures",
  "Arguments": { "project_path": "snes-sfx-demo/X-GSU/IsoEngine/Data/TEXTURES.sfxproj", "action": "validate" } }
```

Result conventions for `retro-superfx_lsp`: `location.path` / `references[].path` are workspace-relative; `lineNumber` is **1-based** (matching `read_file`; `line` is legacy 0-based). Real sources rank above fixture copies (`src/test/resources/`, `.build/`, `build_tools/`).

---

## 2. Workspace Helper Scripts (`tools/`)

- `./tools/status.sh` — Branch and dirty-tree status across all repos.
- `./tools/install-local.sh` — Compile and install `java-language-server`, `retro-common`, and `sfx-optimizer` into `~/.m2`.
- `./tools/build-all.sh [--install] [--roms] [--install-vscode]` — Topological build & test across the stack.
- `./tools/install-vscode-extension.sh` — Package JLink runtime and install `.vsix`.
- `./tools/update-submodules.sh [--dry-run] [--split]` — Stage, commit, and push updated submodules.
- `./tools/rebase-submodules.sh [--dry-run] [--branch <name>]` — Fetch and rebase submodules on upstream.
- `./tools/run-sfx-test.sh <test|all> [timeout]` — Emergency CLI fallback if `retro-superfx_mesen` MCP is unavailable.
- `./tools/run-evals.sh [--tier 0|all] [--changed] [--verify-graders] [--transcript <id-or-path>]` — Harness lint, MCP smoke, and transcript audit suite.
