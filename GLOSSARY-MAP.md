# GLOSSARY-MAP: retro-superfx Umbrella Workspace

This map points to context-specific domain glossaries across the workspace.

## Contexts

- **Umbrella System & MCP Architecture**: [`GLOSSARY.md`](./GLOSSARY.md) (Tooling, MesenMCP, RetroLspMCP, StudioMCP, verification harnesses)
- **retro-common**: [`retro-common/GLOSSARY.md`](./retro-common/GLOSSARY.md) (Lexer, token stream, AST, symbol table)
- **sfx-optimizer**: [`sfx-optimizer/GLOSSARY.md`](./sfx-optimizer/GLOSSARY.md) (CFG, live-range intervals, Chaitin-Briggs graph coloring, parallel moves)
- **retro-lsp**: [`retro-lsp/GLOSSARY.md`](./retro-lsp/GLOSSARY.md) (LSP JSON-RPC, features, VS Code client bridge)
- **sfx-studio**: [`sfx-studio/GLOSSARY.md`](./sfx-studio/GLOSSARY.md) (Atlas Documents, Level Documents, Source Textures, Virtual Slices, Descriptor Aliases, Atlas Index)
- **snes-sfx-demo / X-GSU**: [`snes-sfx-demo/GLOSSARY.md`](./snes-sfx-demo/GLOSSARY.md) (ca65 macro DSL, fixed-point math, Level, Map Grid, Texture Atlas, IsoEngine rasterization)

## Relationships

- **sfx-studio → snes-sfx-demo / X-GSU**: `sfx-studio` compiles authored Atlas Documents and Level Documents into packed Texture Atlases, palettes, Texture Tables, Atlas Indexes, and Map Grids consumed at link time or staged at runtime by `snes-sfx-demo`.
- **retro-common → sfx-optimizer → retro-lsp**: `retro-common` tokenizes and indexes assembly sources; `sfx-optimizer` analyzes CFGs and allocates registers; `retro-lsp` exposes diagnostics and symbol navigation to editors and `RetroLspMCP`.
