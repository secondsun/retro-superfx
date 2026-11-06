# Retro SuperFX Development Kit Plugin

An Antigravity plugin bundling tools, skills, rules, and MCP servers for SNES and SuperFX (GSU / FX3) RISC coprocessor development.

## Features

### MCP Servers & Tools
1. **`retro-superfx_mesen`** (`tools/mesen-mcp`):
   - `run_sfx_test`: Headless test execution of SuperFX unit tests via Mesen CE.
   - `assert_sfx_math`: Dynamic injection of test vectors and mathematical assertions in GSURAM/registers.
   - `capture_sfx_render`: Headless render capture to PNG with bounding boxes, non-black pixel counts, and perceptual hashes.
   - `profile_sfx_execution`: Profiles SuperFX execution cycles, hotspots, cache residency, and VBlank FPS.
   - `sfx_resolve_address`: Resolves symbol <-> address, delta to nearest label (`nearestBefore` / `nearestAfter`), memory regions, and layout ranges from `.dbg` and `.cpu.sym`.

2. **`retro-superfx_lsp`** (`retro-lsp`):
   - `sfx_audit_diagnostics`: Audits SuperFX code for syntax errors, block structure (`function`/`endfunction`), stack cleanup (`Δ_SP == 0`), and hardware invariants (8-NOP settling before `stop`, word alignment, no hardware `MERGE`, modal lifetimes). Alias `sfx_audit_invariants` is retained for backwards compatibility.
   - `sfx_expand_macro`: Expands X-GSU macro DSL directives (`function`, `call`, `for`, `if_lt`, etc.) to ca65 assembly.
   - `sfx_lookup_symbol`: Resolves symbol definitions, scopes, and documentation across the workspace.
   - `sfx_find_references`: Finds references and usages of symbols across the workspace index (understands `call`, macros, scopes; skips comments and strings).
   - `sfx_analyze_registers`: Chaitin-Briggs graph coloring register allocation, live ranges, and interference graphs.
   - `sfx_pack_textures`: Packs, updates, or validates a SuperFX Texture Atlas (`.sfxproj`), exporting companion binaries (`.bin`, `.pal`), ca65 assembly index (`.i`), and layout PNG.

### Canonical Skills & Rules
All workspace skills and rules live in a single canonical location at the repository root (`.agents/skills/` and `.agents/rules/`) so they are not loaded twice inside the umbrella workspace.


