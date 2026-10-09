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

### 4. Run SuperFX Unit Tests Headless
Executes automated tests in `snes-sfx-demo/X-GSU/tests/` using Mesen CE in headless mode:
```bash
./tools/run-sfx-test.sh vector3_add 10
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
  - [`sfx-macro-language`](.agents/skills/sfx-macro-language/SKILL.md): Reference and syntax for the X-GSU ca65 macro DSL.
  - [`sfx-test-runner`](.agents/skills/sfx-test-runner/SKILL.md): Headless Mesen CE test execution and debugging.

