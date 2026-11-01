# AGENTS.md — retro-superfx Umbrella Workspace Guide

Welcome to the **`retro-superfx`** agentic project root. This workspace coordinates a suite of interconnected retro gaming libraries, compilers, language tooling, and homebrew demos targeting the **Super Nintendo Entertainment System (SNES)** and the **SuperFX (GSU / FX3)** RISC coprocessor.

---

## 1. Multi-Repository Topology & Architecture

This root acts as an agentic umbrella workspace across 5 separate Git repositories:

```
                                [java-language-server]
                             (LSP protocol models & RPC)
                                          │
                                          ├────────────────────────┐
                                          ▼                        ▼
                                    [retro-common] ──> [sfx-optimizer] ──> [retro-lsp]
                              (Lexer, parser, AST)     (Register allocator    (VS Code extension
                                          │             & compiler pass)      & server backend)
                                          │                        │
                                          │                        ▼ (future compiler hook)
                                          └───────────────> [snes-sfx-demo]
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

5. **`snes-sfx-demo`**:
   - SuperFX homebrew codebase containing:
     - **`libSFX`**: SNES bare-metal SDK, bootstrapper, interrupt handlers, and bundled `cc65` toolchain binaries (`ca65`, `ld65`, `superfamicheck`). Contains small customizations from upstream libSFX.
     - **`X-GSU`**: High-level `ca65` macro language (`function`, `call`, `return`, `stack`, `control`), 3D fixed-point vector/matrix math, and automated unit test suite.
     - **`SpriteScale` & `edge-table`**: SuperFX polygon rasterization demo and dynamic scanline edge-table architecture.

---

## 2. The X-GSU ca65 Macro DSL

The `X-GSU` directory (`snes-sfx-demo/X-GSU/common/`) defines a high-level macro language for SuperFX assembly:

### A. Functions & Subroutine Scoping (`function.i`)
- **`function <name> [params]`**: Generates `<name>_function:` label, enters `.scope <name>`, and pushes `R11` (return address) onto the stack.
- **`call <name> [args]`**: Emits `link #4` (stores return address in `R11`) and branches to `<name>_function`.
- **`return`**: Pops return address from stack into `R15` (`PC`) with `nop` in delay slot.
- **`endfunction`**: Closes `.endscope`.
- **Unit Test Requirement**: Authoring a new X-GSU `function` in `snes-sfx-demo/X-GSU/` requires creating and passing a headless unit test in `snes-sfx-demo/X-GSU/tests/<name>/` before completion; see [`.agents/plugins/retro-superfx/skills/create-gsu-test/SKILL.md`](.agents/plugins/retro-superfx/skills/create-gsu-test/SKILL.md).

### B. High-Level Variables & `sfx-optimizer` Keywords
- **`register <var1>, <var2>`**: Declares pseudo-variables to be mapped to hardware registers.
- **`register <var> = <reg>`**: Pre-colored constraint forcing a variable to a specific physical register (e.g. `register output = r4`).
- **Modal Prefix Instructions**: `with`, `to`, `from` can be applied to declared pseudo-variables or physical registers.

### C. Stack Operations (`stack.i` — `R10` is `stackPointer`)
- **`init_stack`**: Initializes `R10` to `gsu_stack_ram` (512-byte buffer in GSURAM).
- **`gsu_stack_push R`**: Emits `stw (r10); inc r10; inc r10`.
- **`gsu_stack_pop R`**: Emits `dec r10; dec r10; to R; ldw (r10)`.
- **`gsu_stack_peek R`**: Peeks top of stack without advancing `R10`.
- **`gsu_stack_alloc_bytes S`** / **`gsu_stack_free_bytes S`**: Dynamically adjusts `R10` by `S` bytes.

### D. Hardware Loops
- **`for <count>` / `forR <reg>`**: Backs up `R12` (counter) and `R13` (loop address) to stack (`backuploop`), then sets up loop registers.
- **`endfor`**: Emits `loop; nop; restoreloop` (pops `R13` and `R12`).
- **Critical Rule**: Never push temporary variables to the stack inside `for ... endfor` without popping them before `endfor`.

### E. Conditionals (`control.i`)
- **`if_lt R, V, instruction`**: Signed comparison `R < V`.
- **`if_gt R, V, instruction`**: Signed comparison `R > V`.
- **`if_eq R, V, instruction`**: Equality comparison `R == V`.
- **`if_lte R, V, instruction`**: Signed comparison `R <= V`.

---

## 3. SuperFX (FX3) Hardware Invariants

When writing or optimizing SuperFX code:
1. **Target Chip**: SuperFX 3 (FX3 / RP2350B coprocessor at 21.47 MHz).
2. **No Hardware `MERGE` Instruction**: In FX3, `MERGE` is not implemented in hardware. Workaround with `hib`, `lob`, `swap`, and `or`.
3. **8 NOP Pipeline Settling**: SuperFX Work RAM writes are pipelined asynchronously. Insert **8 NOPs** before halting (`stop`) or testing memory in emulator scripts.
4. **16-Bit Word Alignment**: All `ldw`/`stw` memory operations must target 16-bit word aligned (even) addresses.
5. **Modal Instruction Scope**: `with`, `to`, `from` modify registers for the **single immediately following instruction only**. Subsequent arithmetic resets `Sreg` and `Dreg` back to `R0`.
6. **Dual `rpix` Pixel Cache Flush**: `plot` buffers pixels in dual 8-pixel caches (primary and secondary). Execute two `rpix` instructions (`rpix; rpix`) before the 8 settling NOPs and `stop` to flush all plotted pixels to Work RAM.
7. **GSURAM-Only `ldw` / `ldb` Addressing**: `ldw (Rn)` and `ldb (Rn)` only access `GSURAM` (`$700000`, masked to `$1FFF`). Read ROM-resident tables in `.segment "GSUCODE"` or `.segment "ROM*"` via `romb` + `r14` + `getb`/`getbh`.
8. **512-Byte Instruction Cache vs Pixel Cache**:
   - The GSU features a **512-byte on-chip instruction cache** (32 lines of 16 bytes each), distinct from the dual 8-pixel plot cache.
   - When executing inside the cache, opcodes run at single-cycle speed (21.47 MHz on FX3) and free external buses for concurrent RAM writes or ROM prefetching.
   - **`CACHE` opcode behavior**: If `CBR != (R15 & $FFF0)`, sets `CBR = R15 & $FFF0` and clears all 32 valid flags. If `CBR` matches, tags are preserved.
   - **Subroutine residency**: Subroutines (`call`, `jmp`, `bra`) **do not invalidate or flush the cache**. If a target is outside the current 512-byte window, it executes at un-cached bus speeds, and returning to the caller immediately resumes 1-cycle cache hits.
   - **Avoid Over-Invalidation / Cache Spraying**: Never put `cache` inside a loop or inside helper functions, as changing `CBR` invalidates all 512 bytes on every iteration. Reserve `cache` for heavy, high-iteration leaf kernels (e.g. rasterization loops, batch transforms, framebuffer clears) using the canonical idiom:
     ```assembly
     cache
     move r13, r15
     ```

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
```

### 4. SuperFX Headless Emulation & Testing (`retro-superfx_mesen` MCP)
Agents should **prioritize calling the MCP tools** on `retro-superfx_mesen` via `call_mcp_tool`:
- **`run_sfx_test`**: Headless test execution in Mesen CE (`test_name`, `timeout_seconds`).
- **`assert_sfx_math`**: Dynamic parameter injection and mathematical output assertions in GSURAM/registers.
- **`capture_sfx_render`**: Headless framebuffer capture to PNG for visual inspection or perceptual hashing.
- **`profile_sfx_execution`**: Profiles GSU execution cycles, hotspots, cache residency, and VBlank FPS.
- **`sfx_resolve_address`**: Resolves symbol <-> address, nearest label with delta, memory regions, and layout ranges from `.dbg` and `.cpu.sym`.

*Fallback CLI:*
```bash
./tools/run-sfx-test.sh vector3_add 10
```

### 5. SuperFX Language & Analysis Tools (`retro-superfx_lsp` MCP)
Agents should actively utilize the `retro-superfx_lsp` MCP tools via `call_mcp_tool` for assembly authoring, analysis, and verification:
- **`sfx_audit_diagnostics`**: Unified static diagnostics tool validating syntax errors (block balancing for `function`/`endfunction`, `for`/`endfor`, `.if`/`.endif`, `.proc`/`.endproc`, instruction and macro arity, duplicate symbols), FX3 hardware invariants (8-NOP settling before `stop`, word alignment on `ldw`/`stw`, no hardware `MERGE`, modal lifetimes), and stack displacement invariants (return leaks, divergent branch depths).
- **`sfx_audit_invariants`**: Backward-compatible alias for `sfx_audit_diagnostics`.
- **`sfx_expand_macro`**: Expands X-GSU macro DSL directives (`function`, `call`, `for`, `if_lt`, stack ops) into concrete instruction streams.
- **`sfx_analyze_registers`**: Chaitin-Briggs graph coloring, live-range intervals, CFG basic blocks, and register pressure.
- **`sfx_lookup_symbol`**: Finds where a symbol is **defined** (path, 1-based line, doc comment). Prefer it over `grep` for definition/doc lookups; accepts `path` and `maxResults`. Not for usages, linker addresses, or plain text.
- **`sfx_find_references`**: Finds usages and references of CA65/X-GSU symbols across the workspace index. Understands `call` to `function`, macro invocations, and scoped symbols; skips comments and string literals; supports `path`, `maxResults`, `includeDefinitions`, and `contextLines`.
- **`sfx_pack_textures`**: Packs, updates, or validates a SuperFX Texture Atlas (`.sfxproj`), exporting companion binaries (`.bin`, `.pal`), ca65 assembly index (`.i`), and layout PNG, or imports bitmap graphics with Median-Cut color quantization.

#### When to use `sfx_lookup_symbol` vs. `sfx_find_references` vs. `grep`

| Need | Use |
|---|---|
| Where a label / `function` / macro / struct / struct field / constant is **defined**, plus its doc comment (In/Out/Clobbers) | `sfx_lookup_symbol` |
| Where a symbol is **used** (references) | `sfx_find_references` |
| Linker address of a symbol, or what lives at an address (`.sym` / `.dbg`) | `sfx_resolve_address` on `retro-superfx_mesen` |
| Plain text, regex, Lua/Python/cfg files, `.build/*.opt.sgs` output | `grep` |

> **Mandatory:** Do not `grep` `.s`/`.sgs`/`.i`/`.inc` sources for a symbol's definition or usages, and do not `grep` `.cpu.sym`/`.dbg` for addresses. Call `sfx_lookup_symbol`, `sfx_find_references`, or `sfx_resolve_address` first. Fall back to `grep` only if the tool is unavailable or returns nothing, and say so. If these tools are missing from your tool list, the MCP servers are stale: tell the user to run `./tools/build-all.sh --install` and restart them.

Ready-to-copy calls (the tools are lazy-loaded; no need to read their schemas first):

```json
{ "ServerName": "retro-superfx_lsp", "ToolName": "sfx_lookup_symbol",
  "Arguments": { "symbol": "map_compile_quad_face", "path": "snes-sfx-demo/X-GSU/IsoEngine/" } }
```

```json
{ "ServerName": "retro-superfx_lsp", "ToolName": "sfx_find_references",
  "Arguments": { "symbol": "iso_camera_set_yaw", "path": "snes-sfx-demo/X-GSU/" } }
```

Result fields: `location.path` / `references[].path` (workspace-relative), `location.lineNumber` / `references[].lineNumber` (**1-based**, matches `grep -n` / `read_file`; `line` is the legacy 0-based value), `documentation`, `totalReferences`, `references[].kind` (`call`, `read`, `write`, `macro-invocation`, `use`), `references[].text` (when `contextLines > 0`). Real sources rank above fixture copies under `src/test/resources/`, `.build/`, and `build_tools/`.

### 6. Update & Push Submodules
Stage modified submodules, generate descriptive commit messages, and push to the active branch on `origin`:
```bash
./tools/update-submodules.sh
# To preview actions without mutating:
./tools/update-submodules.sh --dry-run
# To commit each submodule individually:
./tools/update-submodules.sh --split
```

### 7. Fetch & Rebase Submodules
Fetch latest upstream commits from GitHub and rebase submodules on target branch (default: `prototype`, remote: `origin`):
```bash
./tools/rebase-submodules.sh
# To preview actions without mutating:
./tools/rebase-submodules.sh --dry-run
# To rebase an alternate branch across submodules:
./tools/rebase-submodules.sh --branch main
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
  - [`rebase-submodules`](.agents/skills/rebase-submodules/SKILL.md): Fetch and rebase Git submodules from GitHub.
  - [`sfx-macro-language`](.agents/skills/sfx-macro-language/SKILL.md): Reference and syntax for the X-GSU ca65 macro DSL.
  - [`sfx-test-runner`](.agents/skills/sfx-test-runner/SKILL.md): Headless Mesen CE test execution and debugging.
  - [`texture-atlas`](.agents/skills/texture-atlas/SKILL.md): Author, pack, update, and validate SuperFX texture atlases and companion files.
  - [`update-submodules`](.agents/skills/update-submodules/SKILL.md): Stage, commit, and push updated Git submodules to origin.

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



