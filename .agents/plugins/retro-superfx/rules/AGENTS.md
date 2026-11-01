# retro-superfx Plugin Rules

See the workspace root [`AGENTS.md`](file:///Users/summerspittman/Projects/retro-superfx/AGENTS.md) and [`.agents/rules/sfx-assembly-rules.md`](file:///Users/summerspittman/Projects/retro-superfx/.agents/rules/sfx-assembly-rules.md) for the canonical multi-repository topology, X-GSU macro DSL, SuperFX (FX3) hardware invariants, and MCP tool workflows.

## Mandatory Framebuffer Capture Gate for Scene Optimizations
- Any optimization, refactoring, or pipeline change affecting full-scene demos (`IsoEngine`, `SpriteScale`) MUST execute the `capture_sfx_render` MCP tool on `retro-superfx_mesen` (or verify via headless screenshot) to confirm `non_black_pixels > 0` before declaring the task complete.
- Passing isolated subroutine unit tests (`draw_textured_triangle`, `test_cached_trap`) is necessary but NOT sufficient to verify full scene integration.
