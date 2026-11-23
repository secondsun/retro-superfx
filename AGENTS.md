# AGENTS.md — `retro-superfx` Umbrella Workspace Guide

Umbrella workspace coordinating 6 interconnected SNES / SuperFX (GSU / FX3) libraries, compiler passes, language tooling, asset studio, and homebrew demos.

## 1. Multi-Repository Topology

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
  (Asset & level studio,                  │                        ▼
   CLI & StudioMCP)                       │                        │
          │                               ▼                        ▼
          └───────────────────────> [snes-sfx-demo]
                                ├── libSFX (SDK, libSFX.make & cc65 tools)
                                ├── X-GSU  (Macro DSL, 3D math, IsoEngine, gsu_mesh)
                                └── SpriteScale / edge-table (Demos)
```

| Project | Role | Guide |
|---|---|---|
| `java-language-server` | Java 26 LSP protocol models & JSON-RPC pump (`dev.secondsun:languageserver`) | [`java-language-server/AGENTS.md`](java-language-server/AGENTS.md) |
| `retro-common` | Java 26 lexer, parser, `WorkspaceIndex`, 6502/65816/SuperFX ISA (`dev.secondsun:retro-common`) | [`retro-common/AGENTS.md`](retro-common/AGENTS.md) |
| `sfx-optimizer` | Kotlin/Java Chaitin-Briggs register allocator, `R10` spill lowering, inliner, peephole (`dev.secondsun:sfx-optimizer`) | [`sfx-optimizer/AGENTS.md`](sfx-optimizer/AGENTS.md) · [Pipeline Doc](docs/sfx-optimizer-pipeline-integration.md) |
| `retro-lsp` | Java 26 JLink LSP/MCP server + TypeScript VS Code extension (`dev.secondsun:retro-lsp`) | [`retro-lsp/AGENTS.md`](retro-lsp/AGENTS.md) |
| `sfx-studio` | Umbrella-tracked Kotlin/Compose asset & level studio + `StudioMCP` (`dev.secondsun:sfx-studio`) | [`sfx-studio/AGENTS.md`](sfx-studio/AGENTS.md) |
| `snes-sfx-demo` | `libSFX` SDK, `X-GSU` macro DSL, `IsoEngine`/`RamMapDemo`/`IsoRPGDemo`, Mesen CE test suite | [`snes-sfx-demo/AGENTS.md`](snes-sfx-demo/AGENTS.md) |

---

## 2. X-GSU DSL & SuperFX (FX3) Hardware Invariants

- **X-GSU Macro DSL**: Full syntax (`function`/`call`/`return`, `register`, `R10` `stack.i`, `for`/`forR`, signed `if_*`) lives in [`.agents/skills/sfx-macro-language/SKILL.md`](.agents/skills/sfx-macro-language/SKILL.md).
- **Unit Test Requirement**: Authoring a new X-GSU `function` in `snes-sfx-demo/X-GSU/` requires a passing headless Mesen CE test in `snes-sfx-demo/X-GSU/tests/<name>/` ([`create-gsu-test`](.agents/skills/create-gsu-test/SKILL.md)). WIP RNC suites (`buffer_rnc`, `decode_rnc`, `hufftable_rnc`) are skipped.
- **FX3 Hardware Constraints**: Complete register roles and side-effect tables live in [`.agents/rules/sfx-assembly-rules.md`](.agents/rules/sfx-assembly-rules.md). Critical non-negotiables:
  1. **No hardware `MERGE`** on FX3 (emulate via `hib`/`lob`/`swap`/`or`).
  2. **Plot flush & RAM settling**: Execute `rpix; rpix` then **8 NOPs** before `stop` or test RAM inspection.
  3. **Alignment & bus**: `ldw`/`stw` require even addresses and only access GSURAM (`$700000`); read ROM via `romb` + `r14` + `getb`/`getbh`.
  4. **Modal scope (`with`/`to`/`from`)**: Applies to the **single immediately following instruction**, then resets to `R0`. Never prefix `move`/`moves`/`moveb`/`movew` with a modal op, and use `from Rn; sub Rm` (never destructive `with Rn`) for comparisons.
  5. **Immediate bounds**: Short immediates on ALU/mult ops are `#0..#15` (`link` is `#1..#4`). Larger constants require `ibt` (mask negatives via `#<(-k)`) or `iwt`.
  6. **512-byte instruction cache**: Place `cache; move r13, r15` once before hot leaf loops; never place `cache` inside loops or helper subroutines.

---

## 3. Mandatory Skill & Tool Routing

Invoke MCP tools directly via **`call_mcp_tool`** (all 3 servers are lazy-loaded behind `call_mcp_tool`; no need to read `<appDataDir>/mcp/` schemas first). For copy-paste JSON payloads and `./tools/*.sh` flags, see [`docs/agents/mcp-tools.md`](docs/agents/mcp-tools.md).

| Task / Trigger | First Action (Mandatory) |
|---|---|
| Bug report, visual glitch, broken behavior, or regression | Read [`.agents/skills/diagnosing-bugs/SKILL.md`](.agents/skills/diagnosing-bugs/SKILL.md) **first** and build a tight red-capable reproducer before forming hypotheses. |
| Running Mesen tests, resolving `.cpu.sym`/`.dbg` addresses, profiling, or capturing renders | Read [`.agents/skills/sfx-test-runner/SKILL.md`](.agents/skills/sfx-test-runner/SKILL.md) and call `retro-superfx_mesen` (`run_sfx_test`, `assert_sfx_math`, `capture_sfx_render`, `profile_sfx_execution`, `sfx_resolve_address`). Use `symbols: [...]` / `test_names: [...]` to batch lookups and test runs. |
| Finding a symbol definition, doc comment, or file outline (`.s`/`.sgs`/`.i`/`.inc`) | Call `retro-superfx_lsp:sfx_lookup_symbol` (pass `outline: true` with `path` to map large `.i` files instead of sliding `read_file` reads). |
| Finding symbol usages/references (`.s`/`.sgs`/`.i`/`.inc`) | Call `retro-superfx_lsp:sfx_find_references`. |
| Static syntax, FX3 hardware, or stack-balance audit | Call `retro-superfx_lsp:sfx_audit_diagnostics` (or `sfx_expand_macro` / `sfx_analyze_registers`). |
| Resolving linker addresses, cache spans, or `.dbg` summary | Call `retro-superfx_mesen:sfx_resolve_address` (supports single `symbol`, batch `symbols`, `address`, `range`, `prefix`, `end_symbol`, and `summary: true`). |
| Authoring/packing texture atlases (`.sfxproj`) | Read [`.agents/skills/texture-atlas/SKILL.md`](.agents/skills/texture-atlas/SKILL.md), call `retro-superfx_studio:sfx_pack_textures`, and verify via `capture_sfx_render`. |
| Authoring/importing/packing isometric levels (`.sfxlevel`) | Read [`.agents/skills/iso-level/SKILL.md`](.agents/skills/iso-level/SKILL.md) and call `retro-superfx_studio:sfx_manage_level`. |
| Inspecting captured `.png` screenshots | Call `read_file` directly on the `.png` path for visual inspection. |
| Plain text / TODO comment scans, regex, Lua/Python/cfg files, `.build/*.opt.sgs` | Use `grep`. |

> **Policy**: Always use `retro-superfx_lsp` (`sfx_lookup_symbol` / `sfx_find_references`) instead of `grep` for assembly definitions/usages, and `retro-superfx_mesen:sfx_resolve_address` instead of `grep` on `.cpu.sym`/`.dbg` for addresses. Fall back to `grep` only if `call_mcp_tool` is unavailable or returns nothing, and say so. If tools are missing, ask the user to run `./tools/build-all.sh --install` and restart MCP servers.

---

## 4. Guardrails, Standards & References

- **Git Hooks**: Run `./tools/install-git-hooks.sh` once per clone to install `tools/git-hooks/pre-commit` (Spotless, JVM tests, Javadoc, Kotlin `return@repeat` guard, `snes-sfx-demo` `--Werror` & `test.lua` symbol checks, harness Tier 0 lint).
- **Harness Evals**: Run `./tools/run-evals.sh --changed` (or `--tier 0` / `--transcript <id>`) after editing `AGENTS.md`, `.agents/`, or `tools/`.
- **Cross-Repo Invariants**: [`.agents/rules/cross-repo-invariants.md`](.agents/rules/cross-repo-invariants.md).
- **Review Judgement Calls**: [`CODING_STANDARDS.md`](CODING_STANDARDS.md).
- **MCP Payloads & `./tools/` CLI**: [`docs/agents/mcp-tools.md`](docs/agents/mcp-tools.md).
- **Compiler Pipeline & Benchmarks**: [`docs/sfx-optimizer-pipeline-integration.md`](docs/sfx-optimizer-pipeline-integration.md).
- **Issue Tracker, Triage & Domain Glossaries**: [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md), [`docs/agents/triage-labels.md`](docs/agents/triage-labels.md), [`docs/agents/domain.md`](docs/agents/domain.md) (`GLOSSARY-MAP.md`).
- **Workspace Skills**: `build-workspace`, `create-gsu-test`, `diagnosing-bugs`, `iso-level`, `lsp-feature-dev`, `rebase-submodules`, `run-gsu-test`, `sfx-macro-language`, `sfx-test-runner`, `texture-atlas`, `update-submodules`, `verify-pipeline`.
