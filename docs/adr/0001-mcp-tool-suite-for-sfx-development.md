# 0001. Architecture for Mesen and Retro-LSP MCP Tool Suites

Date: 2026-10-13

## Status

Accepted

## Context

Agents developing complex SuperFX 3D math (`X-GSU`) and isometric graphics rendering (`IsoEngine`) need rich feedback loops:
1. Static feedback on SuperFX hardware invariants (pipelining, word alignment, modal prefixes, macro expansions).
2. Runtime feedback from execution (math correctness against floating/fixed-point expectations, cycle counts, framebuffer captures).

Directly running bash scripts or manual inspection is slow and prone to silent errors.

## Decisions

1. **Retro-LSP MCP**:
   - Embed MCP support directly in the Java `retro-lsp` codebase under `dev.secondsun.retrolsp.mcp` using the **Official Java MCP SDK** (`io.modelcontextprotocol.sdk:mcp`).
   - Migrate JSON serialization from Gson to **Jackson 3 / Tools Jackson** required by the Java MCP SDK.
   - Support both direct stdio CLI launching (`retro-lsp --mcp`) and VS Code configuration (`retroca65.enableMcp`) with an extension restart trigger.
   - Remove the buggy `Uses : ...` register liveness hover from `HoverFeature.java`. Move register live-range analysis into a dedicated, thoroughly tested MCP tool (`sfx_analyze_registers`).
   - Expose 4 focused static analysis tools: `sfx_audit_invariants`, `sfx_expand_macro`, `sfx_lookup_symbol`, and `sfx_analyze_registers`.

2. **Mesen MCP**:
   - Build a standalone Python 3 MCP server in `tools/mesen-mcp/`.
   - Use `ld65` generated `.dbg` files as the authoritative source of truth for symbol resolution and source file mapping.
   - Implement a **hybrid math verification suite**:
     - Tool A: `run_sfx_test(test_name)`: Runs established human-readable demo tests (e.g. `snes-sfx-demo/X-GSU/tests/*`) as regression and educational demonstrations.
     - Tool B: `assert_sfx_math(test_name, input_overrides, expected_outputs)`: Parameterizes an existing test suite by injecting runtime vectors via an ephemeral Lua overlay harness without modifying the base demo source.
   - Provide framebuffer capture (`capture_sfx_render`) producing PNGs and quantitative metrics (bounding box, drawn pixel count, perceptual hash).
   - Explicitly defer `edge_table` inspection until that subsystem matures.

3. **Rollout & Upstream Migration Strategy**:
   - **Phase 1: Mesen MCP Server (`tools/mesen-mcp/`)**: Python 3 server with `ld65` `.dbg` parser, `run_sfx_test`, `assert_sfx_math`, and `capture_sfx_render` using emulator discovery hierarchy from `tools/run-sfx-test.sh`.
   - **Phase 2: Retro-LSP Hover Cleanup**: Remove inaccurate `Uses : ...` register hover from `HoverFeature.java`.
   - **Phase 3: Retro-LSP Java MCP Integration**: Introduce the Java MCP SDK and Jackson into `retro-lsp` while leaving `java-language-server` on Gson initially.
   - **Future Track**: Schedule an upstream migration of `java-language-server` to Jackson in a coordinated effort once `retro-lsp` MCP stabilizes.

## Consequences

- Clean separation between compilation/static analysis (Java) and emulation/runtime inspection (Python).
- Tests remain human-readable and function as educational documentation while enabling automated, parameterized agent assertions.
- LSP hover documentation is cleaned up, eliminating inaccurate register usage displays.
- Zero disruption to existing LSP communication while incrementally introducing MCP capabilities.



