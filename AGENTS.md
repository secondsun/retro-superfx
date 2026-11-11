# AGENTS.md — retro-superfx Umbrella Workspace Guide

Welcome to the **`retro-superfx`** agentic project root. This workspace coordinates a suite of interconnected retro gaming libraries, compilers, language tooling, and homebrew demos targeting the **Super Nintendo Entertainment System (SNES)** and the **SuperFX (GSU / FX3)** RISC coprocessor.

---

## 1. Multi-Repository Topology & Architecture

This root acts as an agentic umbrella workspace across 6 projects (5 Git submodules + umbrella-tracked `sfx-studio`):

```
                                [java-language-server]
                             (LSP protocol models & RPC)
                                          │
                                          ├────────────────────────┐
                                          ▼                        ▼
                                    [retro-common] ──> [sfx-optimizer] ──> [retro-lsp]
                              (Lexer, parser, AST)     (Register allocator    (VS Code extension
                                          │             & compiler pass)      & server backend)
       [sfx-studio]                       │                        │
  (Asset & level studio,                  │                        ▼ (future compiler pass)
   CLI & StudioMCP)                       │                        │
          │                               ▼                        ▼
          └───────────────────────> [snes-sfx-demo]
                                ├── libSFX (SDK & cc65 tools)
                                ├── X-GSU  (Macro DSL & 3D math)
                                └── SpriteScale / edge-table (Demos)
```

### Subproject Breakdown

1. **`java-language-server`** (`dev.secondsun:languageserver`):
   - Pure-Java 26 implementation of the Language Server Protocol (LSP) data structures and JSON-RPC message pump.
   - Zero-dependency core (using Gson for serialization).
   - Upstream dependency for `retro-lsp`.

2. **`retro-common`** (`dev.secondsun:retro-common`):
   - Java 26 lexical analysis, tokenization, instruction matching, symbol indexing, and documentation extraction.
   - Supports 6502, 65816, and SuperFX assembly, as well as `ca65` directives and `X-GSU` macros.
   - Upstream dependency for `sfx-optimizer` and `retro-lsp`.

3. **`sfx-optimizer`** (`dev.secondsun:sfx-optimizer`):
   - Kotlin/Java compiler pass, register allocator, and interactive visualizer for SuperFX assembly.
   - Implements **Chaitin-Briggs Graph-Coloring Register Allocation**, Control Flow Graph (CFG) generation, live-range intervals, and variable lowering.
   - Features: `CA65MacroExpander`, `StackDepthTracker`, and `ParallelMoveResolver` (cycle swaps via XOR).
   - Embedded web visualizer on port `8080`.
   - Dependency for `retro-lsp` and future compiler pass for `snes-sfx-demo`.

4. **`retro-lsp`** (`dev.secondsun:retro-lsp`):
   - Language Server Protocol implementation and VS Code extension tailored for SNES, libSFX, and SuperFX.
   - Backend: Java 26 modular application packaged with `jlink`.
   - Frontend: TypeScript 5.7+ VS Code extension client in `vscode/`.

5. **`sfx-studio`** (`dev.secondsun:sfx-studio`):
   - 3-module Kotlin/Compose Desktop asset and level studio (`sfx-studio-core`, `sfx-studio-cli`, `sfx-studio-desktop`) + headless stdio `StudioMCP` (`sfx-studio/scripts/run-mcp.sh`).
   - Generates `.sfxproj` texture atlases, companion binaries (`.bin`, `.pal`), and CA65 assembly headers (`.i`).
   - Tracked directly in umbrella repo toward progressive monorepo consolidation (ADR 0005).

6. **`snes-sfx-demo`**:
   - SuperFX homebrew codebase containing:
     - **`libSFX`**: SNES bare-metal SDK, bootstrapper, interrupt handlers, and bundled `cc65` toolchain binaries (`ca65`, `ld65`, `superfamicheck`). Contains small customizations from upstream libSFX.
     - **`X-GSU`**: High-level `ca65` macro language (`function`, `call`, `return`, `stack`, `control`), 3D fixed-point vector/matrix math, and automated unit test suite.
     - **`SpriteScale` & `edge-table`**: SuperFX polygon rasterization demo and dynamic scanline edge-table architecture.

---

## 2. The X-GSU ca65 Macro DSL

The `X-GSU` directory (`snes-sfx-demo/X-GSU/common/`) defines a high-level macro language for SuperFX assembly; full syntax reference is in [`.agents/skills/sfx-macro-language/SKILL.md`](.agents/skills/sfx-macro-language/SKILL.md):

- **Functions & Scoping (`function.i`)**: `function <name> [params]` enters `.scope` and pushes return address `R11`; `call <name> [args]` emits `link #4` and branches to `<name>_function`; `return` pops return address into `R15` (`PC`) with `nop` in delay slot; `endfunction` closes scope.
- **Unit Test Requirement**: Authoring a new X-GSU `function` in `snes-sfx-demo/X-GSU/` requires creating and passing a headless unit test in `snes-sfx-demo/X-GSU/tests/<name>/` before completion; see [`.agents/skills/create-gsu-test/SKILL.md`](.agents/skills/create-gsu-test/SKILL.md). Note: `buffer_rnc`, `decode_rnc`, and `hufftable_rnc` are works in progress (WIP) and not expected to pass.
- **Variables & Allocation**: `register <var1>, <var2>` declares compiler-managed pseudo-variables; `register <var> = <reg>` constrains variables to specific physical registers.
- **Stack Operations (`stack.i` — `R10` is `stackPointer`)**: `init_stack` points `R10` to `gsu_stack_ram` (512-byte buffer in GSURAM); manipulate with `gsu_stack_push R`, `gsu_stack_pop R`, `gsu_stack_peek R`, `gsu_stack_alloc_bytes S`, and `gsu_stack_free_bytes S`.
- **Hardware Loops & Conditionals**: `for <count>` / `forR <reg>` backs up `R12`/`R13` (`backuploop`); `endfor` emits `loop; nop; restoreloop`. Never push temporary variables inside `for ... endfor` without popping before `endfor`. Signed conditionals: `if_lt`, `if_gt`, `if_eq`, `if_lte`.

---

## 3. SuperFX (FX3) Hardware Invariants

Key coprocessor hardware constraints; see [`.agents/rules/sfx-assembly-rules.md`](.agents/rules/sfx-assembly-rules.md) for complete register roles and side-effect tables:

1. **Target & Architecture**: SuperFX 3 (FX3 / RP2350B coprocessor at 21.47 MHz). No hardware `MERGE` instruction (workaround via `hib`, `lob`, `swap`, `or`).
2. **RAM Pipeline Settling & Plot Flush**: Work RAM writes are asynchronous—insert **8 NOPs** before halting (`stop`) or reading memory in tests. Always execute **two `rpix` instructions** (`rpix; rpix`) before the 8 settling NOPs and `stop` to flush dual 8-pixel plot caches.
3. **Memory & Alignment**: 16-bit word operations (`ldw`/`stw`) require even-byte alignment. `ldw (Rn)` / `ldb (Rn)` only access GSURAM (`$700000`, masked to `$1FFF`); read ROM tables via `romb` + `r14` + `getb`/`getbh`.
4. **Modal Instruction Scope (`with`, `to`, `from`)**: Prefixes modify `Sreg`/`Dreg` for the **single immediately following instruction only**, then reset to `R0`. `with Rn` sets `Sreg=Rn, Dreg=Rn` (destructive; never use for comparisons!). `from Rn` sets `Sreg=Rn, Dreg=R0` (non-destructive test; note that `from Rn; sub #0`/`add #0` without `to` overwrites `R0`—use `with Rn; add #0` or `moves Rn, Rn` if `R0` is live). Use `from Rn; sub Rm` (or `cmp`) for non-destructive comparison.
5. **No Modal Prefix Before `move`**: `move Dreg, Sreg` expands to `with Sreg; to Dreg`. Never prefix `move` (`moves`, `moveb`, `movew`) with `to`/`with`/`from`, as `move` configures its own modal registers and overwrites any preceding prefix.
6. **Operand Bounds**: Short-immediate arithmetic/bitwise/multiply (`add`, `adc`, `sub`, `sbc`, `and`, `bic`, `or`, `xor`, `mult`, `umult`) only accepts 4-bit unsigned immediates `#0`–`#15` (`#$0`–`#$F`); `link` only accepts `#1`–`#4`. Constants > 15 require `ibt` (8-bit signed; note `GSU_Assembler.i` checks `0..255`, so mask negative bytes via `#<(-k)` or `#$80`–`#$FF`) or `iwt` (16-bit word). Register forms of `and`, `bic`, `or`, `xor`, `mult`, `umult` only accept `R1`–`R15`.
7. **512-Byte Instruction Cache**: 32 lines of 16 bytes. Running inside cache executes at 1 cycle/instruction. Execute `cache; move r13, r15` once before compute-heavy leaf loops. Subroutines (`call`, `bra`) do not invalidate cache tags. Never place `cache` inside loops or helper subroutines (forces cold reloads).

---

## 4. Cross-Project Workflows & Tools

Convenience tooling is located in `tools/`:

### 1. Workspace Health & Git Status
Check git branches and dirty trees across all 5 repos:
```bash
./tools/status.sh
```

### 2. Local Artifact Installation (Fast Dev Loop)
When editing `java-language-server`, `retro-common`, or `sfx-optimizer`, compile and install to `~/.m2` without deploying to Sonatype:
```bash
./tools/install-local.sh
```

### 3. Build & Test Entire Stack
Builds projects in strict topological order (`java-language-server` -> `retro-common` -> `sfx-optimizer` -> `retro-lsp`):
```bash
./tools/build-all.sh
# To install locally during build:
./tools/build-all.sh --install
# To also compile sample ROMs in snes-sfx-demo:
./tools/build-all.sh --install --roms
# To also package and install the VS Code extension:
./tools/build-all.sh --install --install-vscode
```

### 4. Build & Install VS Code Extension
Package the self-contained native JLink runtime and install the `.vsix` into VS Code (`code` or `codium`):
```bash
./tools/install-vscode-extension.sh
```

### 5. SuperFX Headless Emulation & Testing (`retro-superfx_mesen` MCP)
Agents must **strongly prefer calling the MCP tools** on `retro-superfx_mesen` by invoking the **`call_mcp_tool`** tool (these tools are lazy-loaded behind `call_mcp_tool`, not top-level tool declarations; call `call_mcp_tool` directly without reading `<appDataDir>/mcp/` schemas first):
- **`run_sfx_test`**: Headless test execution in Mesen CE (`test_name` e.g. `'sqrt'` or `'all'`, `timeout_seconds`).
- **`assert_sfx_math`**: Dynamic parameter injection and mathematical output assertions in GSURAM/registers.
- **`capture_sfx_render`**: Headless framebuffer capture to PNG for visual inspection or perceptual hashing.
- **`profile_sfx_execution`**: Profiles GSU execution cycles, hotspots, cache residency, and VBlank FPS.
- **`sfx_resolve_address`**: Resolves symbol <-> address, nearest label with delta, memory regions, and layout ranges from `.dbg` and `.cpu.sym`.

Ready-to-copy **`call_mcp_tool`** arguments (invoke `call_mcp_tool` with these exact JSON objects):

```json
{ "ServerName": "retro-superfx_mesen", "ToolName": "run_sfx_test",
  "Arguments": { "test_name": "all", "timeout_seconds": 15 } }
```

```json
{ "ServerName": "retro-superfx_mesen", "ToolName": "capture_sfx_render",
  "Arguments": { "scene_test_name": "IsoEngine", "timeout_seconds": 15 } }
```

```json
{ "ServerName": "retro-superfx_mesen", "ToolName": "profile_sfx_execution",
  "Arguments": { "target_name": "IsoEngine", "warmup_runs": 1, "sample_runs": 2, "timeout_seconds": 15 } }
```

*Emergency Fallback CLI (only if `call_mcp_tool` is unavailable):*
```bash
./tools/run-sfx-test.sh all 15
# Or run a single test:
./tools/run-sfx-test.sh vector3_add 10
```

### 6. SuperFX Language & Analysis Tools (`retro-superfx_lsp` MCP)
Agents must **strongly prefer calling the `retro-superfx_lsp` MCP tools** by invoking the **`call_mcp_tool`** tool (lazy-loaded behind `call_mcp_tool`, not top-level tool declarations; no need to read `<appDataDir>/mcp/` schemas first):
- **`sfx_audit_diagnostics`**: Unified static diagnostics tool validating syntax errors (block balancing for `function`/`endfunction`, `for`/`endfor`, `.if`/`.endif`, `.proc`/`.endproc`, instruction and macro arity, duplicate symbols), FX3 hardware invariants (8-NOP settling before `stop`, word alignment on `ldw`/`stw`, no hardware `MERGE`, modal lifetimes), and stack displacement invariants (return leaks, divergent branch depths).
- **`sfx_audit_invariants`**: Backward-compatible alias for `sfx_audit_diagnostics`.
- **`sfx_expand_macro`**: Expands X-GSU macro DSL directives (`function`, `call`, `for`, `if_lt`, stack ops) into concrete instruction streams.
- **`sfx_analyze_registers`**: Chaitin-Briggs graph coloring, live-range intervals, CFG basic blocks, and register pressure.
- **`sfx_lookup_symbol`**: Finds where a symbol is **defined** (path, 1-based line, doc comment). Prefer it over `grep` for definition/doc lookups; accepts `path` and `maxResults`. Not for usages, linker addresses, or plain text.
- **`sfx_find_references`**: Finds usages and references of CA65/X-GSU symbols across the workspace index. Understands `call` to `function`, macro invocations, and scoped symbols; skips comments and string literals; supports `path`, `maxResults`, `includeDefinitions`, and `contextLines`.

### 7. SuperFX Asset & Studio Tools (`retro-superfx_studio` MCP)
Agents must **strongly prefer calling the `retro-superfx_studio` MCP tools** by invoking the **`call_mcp_tool`** tool (lazy-loaded behind `call_mcp_tool`; no need to read `<appDataDir>/mcp/` schemas first):
- **`sfx_pack_textures`**: Packs, updates, or validates a SuperFX Texture Atlas (`.sfxproj`), exporting companion binaries (`.bin`, `.pal`), ca65 assembly index (`.i`), and layout PNG, or imports bitmap graphics with Median-Cut color quantization.

Ready-to-copy **`call_mcp_tool`** argument:

```json
{ "ServerName": "retro-superfx_studio", "ToolName": "sfx_pack_textures",
  "Arguments": { "project_path": "snes-sfx-demo/X-GSU/IsoEngine/Data/TEXTURES.sfxproj", "action": "validate" } }
```

#### Mandatory Skill & Tool Routing by Task Type

| Task / Trigger | First Action (Mandatory) |
|---|---|
| Bug report, visual glitch, broken behavior, or regression ("review and fix", "appears/disappears", "failing") | Read [`.agents/skills/diagnosing-bugs/SKILL.md`](.agents/skills/diagnosing-bugs/SKILL.md) **first** and build a tight red-capable reproducer loop before reading assembly to form hypotheses. |
| Running Mesen tests, resolving `.cpu.sym`/`.dbg` addresses, or capturing scene renders | Read [`.agents/skills/sfx-test-runner/SKILL.md`](.agents/skills/sfx-test-runner/SKILL.md) and invoke `call_mcp_tool` with `ServerName: "retro-superfx_mesen"` (never read `tools/mesen-mcp/` Python internals to learn CLI flags). |
| Authoring, modifying, or packing texture atlases (`.sfxproj`, `.bin`, `.pal`, `TEXTURES.i`) | Read [`.agents/skills/texture-atlas/SKILL.md`](.agents/skills/texture-atlas/SKILL.md), pack via `call_mcp_tool` (`sfx_pack_textures` on `retro-superfx_studio`), and verify with `call_mcp_tool` (`capture_sfx_render`). |
| Inspecting captured `.png` screenshots (from `capture_sfx_render` or Mesen Lua scripts) | Call `read_file` directly on the `.png` path for multimodal visual inspection instead of writing blind pixel-diff or ASCII scripts. |
| Finding where a label / `function` / macro / struct / field / constant is **defined**, plus its doc comment (In/Out/Clobbers) | Call `call_mcp_tool` with `ToolName: "sfx_lookup_symbol"` on `retro-superfx_lsp` (never `grep` `.s`/`.sgs`/`.i`/`.inc` files). |
| Finding where a symbol is **used** (references, calls, reads, writes) | Call `call_mcp_tool` with `ToolName: "sfx_find_references"` on `retro-superfx_lsp` (never `grep` `.s`/`.sgs`/`.i`/`.inc` files). |
| Resolving the linker address of a symbol, or what lives at an address (`.cpu.sym` / `.dbg`) | Call `call_mcp_tool` with `ToolName: "sfx_resolve_address"` on `retro-superfx_mesen` (never `grep` `.cpu.sym` or `.dbg` files). |
| Plain text, regex, Lua/Python/cfg files, `.build/*.opt.sgs` output | Use `grep`. |

> **Mandatory:** Do not `grep` `.s`/`.sgs`/`.i`/`.inc` sources for a symbol's definition or usages, and do not `grep` `.cpu.sym`/`.dbg` for addresses. Invoke **`call_mcp_tool`** with `sfx_lookup_symbol`, `sfx_find_references`, or `sfx_resolve_address` first. Fall back to `grep` only if `call_mcp_tool` is unavailable or returns nothing, and say so. If these tools are missing from your tool list, the MCP servers are stale: tell the user to run `./tools/build-all.sh --install` and restart them.

Ready-to-copy **`call_mcp_tool`** arguments (invoke `call_mcp_tool` with these exact JSON objects):

```json
{ "ServerName": "retro-superfx_lsp", "ToolName": "sfx_lookup_symbol",
  "Arguments": { "symbol": "map_compile_quad_face", "path": "snes-sfx-demo/X-GSU/IsoEngine/" } }
```

```json
{ "ServerName": "retro-superfx_lsp", "ToolName": "sfx_find_references",
  "Arguments": { "symbol": "iso_camera_set_yaw", "path": "snes-sfx-demo/X-GSU/" } }
```

```json
{ "ServerName": "retro-superfx_lsp", "ToolName": "sfx_audit_diagnostics",
  "Arguments": { "path": "snes-sfx-demo/X-GSU/IsoEngine/map_grid.i" } }
```

```json
{ "ServerName": "retro-superfx_mesen", "ToolName": "sfx_resolve_address",
  "Arguments": { "project": "snes-sfx-demo/X-GSU/IsoEngine", "symbol": "draw_cached_trap", "end_symbol": "@trap_finished" } }
```

Result fields: `location.path` / `references[].path` (workspace-relative), `location.lineNumber` / `references[].lineNumber` (**1-based**, matches `grep -n` / `read_file`; `line` is the legacy 0-based value), `documentation`, `totalReferences`, `references[].kind` (`call`, `read`, `write`, `macro-invocation`, `use`), `references[].text` (when `contextLines > 0`). Real sources rank above fixture copies under `src/test/resources/`, `.build/`, and `build_tools/`.

### 8. Update & Push Submodules
Stage modified submodules, generate descriptive commit messages, and push to the active branch on `origin`:
```bash
./tools/update-submodules.sh
# To preview actions without mutating:
./tools/update-submodules.sh --dry-run
# To commit each submodule individually:
./tools/update-submodules.sh --split
```

### 9. Fetch & Rebase Submodules
Fetch latest upstream commits from GitHub and rebase submodules on target branch (default: `prototype`, remote: `origin`):
```bash
./tools/rebase-submodules.sh
# To preview actions without mutating:
./tools/rebase-submodules.sh --dry-run
# To rebase an alternate branch across submodules:
./tools/rebase-submodules.sh --branch main
```

### 10. Agentic Harness Evaluation Suite (`tools/run-evals.sh`)
Validate harness links/symlinks, skill parity, context token budgets, MCP contracts, and SuperFX policy/outcome graders:
```bash
# Tier 0: Static harness lint + live MCP smoke checks
./tools/run-evals.sh --tier 0
# Run Tier 0 + only Tier 1/2 cases matching modified git files
./tools/run-evals.sh --changed
# Verify all Tier 0, Tier 1, and Tier 2 graders against built-in pass/fail fixtures
./tools/run-evals.sh --tier all --verify-graders
# Audit a real Antigravity conversation transcript against harness invariants
./tools/run-evals.sh --transcript <conversation-id-or-path>
```

---

## 5. Future Roadmap & Pipeline Integration

- **Independent X-GSU Project**: Extracting `snes-sfx-demo/X-GSU` into its own standalone repository and library.
- **Compiler Pipeline Integration**: Lowering high-level `.sgs` files through `sfx-optimizer` before feeding output assembly into `ca65`/`ld65` in `libSFX.make`.
- **Dynamic Edge-Table Standalone Library**: Extracting `edge-table` from `SpriteScale` into a documented reusable library.

---

## 6. Agent Configuration & Skills

Workspace customizations are maintained in `.agents/`:
- **Rules**:
  - [`.agents/rules/cross-repo-invariants.md`](.agents/rules/cross-repo-invariants.md): Multi-repo isolation, JVM dependencies, and code hygiene.
  - [`.agents/rules/sfx-assembly-rules.md`](.agents/rules/sfx-assembly-rules.md): SuperFX architecture and FX3 constraints.
- **Skills**:
  - [`build-workspace`](.agents/skills/build-workspace/SKILL.md): Build, test, and synchronize local Maven artifacts.
  - [`create-gsu-test`](.agents/skills/create-gsu-test/SKILL.md): Scaffold, configure, and execute new SuperFX unit tests from `test_template`.
  - [`lsp-feature-dev`](.agents/skills/lsp-feature-dev/SKILL.md): Design, implement, and test new LSP features in `retro-lsp`.
  - [`rebase-submodules`](.agents/skills/rebase-submodules/SKILL.md): Fetch and rebase Git submodules from GitHub.
  - [`run-gsu-test`](.agents/skills/run-gsu-test/SKILL.md): Build, synchronize symbol addresses, and run existing SuperFX unit tests.
  - [`sfx-macro-language`](.agents/skills/sfx-macro-language/SKILL.md): Reference and syntax for the X-GSU ca65 macro DSL.
  - [`sfx-test-runner`](.agents/skills/sfx-test-runner/SKILL.md): Headless Mesen CE test execution and debugging.
  - [`texture-atlas`](.agents/skills/texture-atlas/SKILL.md): Author, pack, update, and validate SuperFX texture atlases and companion files.
  - [`update-submodules`](.agents/skills/update-submodules/SKILL.md): Stage, commit, and push updated Git submodules to origin.
  - [`verify-pipeline`](.agents/skills/verify-pipeline/SKILL.md): Run and diagnose the `retro-lsp` verification pipeline.

---

## Guardrails & standards

Run `./tools/install-git-hooks.sh` once per clone to install the pre-commit hook (spotless + tests for the JVM repos). Review judgement calls live in [`CODING_STANDARDS.md`](CODING_STANDARDS.md); `sfx-optimizer` compile/CLI steps are in `sfx-optimizer/AGENTS.md` §5.

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues using the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Canonical triage roles mapped to standard labels (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Multi-context layout with a root `GLOSSARY-MAP.md` pointing to per-subproject domain glossaries and ADRs. See `docs/agents/domain.md`.



