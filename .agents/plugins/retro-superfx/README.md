# Retro SuperFX Development Kit Plugin

An Antigravity plugin bundling tools, skills, rules, and MCP servers for SNES and SuperFX (GSU / FX3) RISC coprocessor development.

## Features

### MCP Servers & Tools
1. **`retro-superfx_mesen`** (`tools/mesen-mcp`):
   - `run_sfx_test`: Headless test execution of SuperFX unit tests via Mesen CE.
   - `assert_sfx_math`: Dynamic injection of test vectors and mathematical assertions in GSURAM/registers.
   - `capture_sfx_render`: Headless render capture to PNG with bounding boxes, non-black pixel counts, and perceptual hashes.

2. **`retro-superfx_lsp`** (`retro-lsp`):
   - `sfx_audit_invariants`: Audits SuperFX hardware invariants (8-NOP settling before `stop`, word alignment, no hardware `MERGE`, modal lifetimes).
   - `sfx_expand_macro`: Expands X-GSU macro DSL directives (`function`, `call`, `for`, `if_lt`, etc.) to ca65 assembly.
   - `sfx_lookup_symbol`: Resolves symbol definitions, scopes, and documentation across the workspace.
   - `sfx_analyze_registers`: Chaitin-Briggs graph coloring register allocation, live ranges, and interference graphs.

### Bundled Skills
- **`build-workspace`**: Topological compilation and testing across all projects.
- **`sfx-macro-language`**: Reference and syntax guide for the X-GSU ca65 macro DSL.
- **`sfx-test-runner`**: Runbook for headless Mesen CE test execution.
- **`create-gsu-test`**: Scaffolding new SuperFX unit tests from `test_template`.
- **`run-gsu-test`**: Building and running existing SuperFX tests in `tests/`.
- **`rebase-submodules`**: Fetching and rebasing submodules from GitHub.
- **`update-submodules`**: Staging and committing updated submodule references.
- **`lsp-feature-dev`**: Developing and wiring new LSP capabilities in `retro-lsp`.
- **`verify-pipeline`**: Full verification pipeline for `retro-lsp`.

### Rules
- Bundled `rules/AGENTS.md` specifying repository topology, FX3 hardware constraints, and calling conventions.

