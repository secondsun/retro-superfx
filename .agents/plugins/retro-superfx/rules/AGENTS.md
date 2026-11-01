# retro-superfx Plugin Rules

See the workspace root [`AGENTS.md`](file:///Users/summerspittman/Projects/retro-superfx/AGENTS.md) and [`.agents/rules/sfx-assembly-rules.md`](file:///Users/summerspittman/Projects/retro-superfx/.agents/rules/sfx-assembly-rules.md) for the canonical multi-repository topology, X-GSU macro DSL, SuperFX (FX3) hardware invariants, and MCP tool workflows.

## Modal Instruction Invariant (`with`, `to`, `from`)
- `Sreg` and `Dreg` default to `R0` and always reset back to `R0` immediately after the configured instruction executes.
- `with Rn`: sets `Sreg = Rn, Dreg = Rn` (modifies `Rn` in-place). Never use `with Rn; sub #k` for non-destructive tests/comparisons, as the subtraction overwrites `Rn`.
- `from Rn`: sets `Sreg = Rn, Dreg = R0` (preserves `Rn`, writes result into `R0` and updates flags). Use this for testing/comparisons (`from r8; sub #1; beq`).
- `to Rn`: sets `Sreg = R0, Dreg = Rn` (reads from `R0`, stores into `Rn`).

## Mandatory Framebuffer Capture Gate for Scene Optimizations
- Any optimization, refactoring, or pipeline change affecting full-scene demos (`IsoEngine`, `SpriteScale`) MUST execute the `capture_sfx_render` MCP tool on `retro-superfx_mesen` (or verify via headless screenshot) to confirm `non_black_pixels > 0` before declaring the task complete.
- Passing isolated subroutine unit tests (`draw_textured_triangle`, `test_cached_trap`) is necessary but NOT sufficient to verify full scene integration.
