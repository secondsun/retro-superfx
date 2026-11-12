# SuperFX Asset & Level Studio (`sfx-studio`)

A graphical desktop studio and headless asset pipeline built with **Compose Multiplatform Desktop** and **Kotlin 2.4** on **Java 26** for authoring, editing, validating, and packing graphics and level geometry for the SuperFX (GSU / FX3) RISC coprocessor and `IsoEngine`.

---

## Features

- **Texture Atlas Generation (`.bin`)**:
  - Independent power-of-two dimensions from $16\times 16$ to $256\times 256$.
  - Unpacked 1-byte-per-texel layout (`$00`–`$0F`) for zero-bit-unpacking `getc; plot` execution in SuperFX ROM/RAM.
- **SNES BGR555 Palette (`.pal`)**:
  - 16-color palette (32 bytes binary, 16 little-endian words).
  - Slot 0 is permanently locked as **Transparent** (`$0000`).
  - Opaque black is clamped to **`$0001`** (`R=1, G=0, B=0`) to eliminate transparency ambiguity.
  - Interactive 5-bit RGB sliders (`0..31`), hex word display, and color remapping.
- **ca65 Assembly Atlas Index (`.i`) & Relocatable Texture Table (`.tab`)**:
  - Exports `ATLAS_WIDTH`, `ATLAS_HEIGHT`, `ATLAS_SIZE_BYTES`, `ATLAS_STRIDE_SHIFT`, and per-texture constants (`TEXTURE_<NAME>_ID`, `TEXTURE_<NAME>_OFFSET`, `TEXTURE_<NAME>_U_MASK`, `TEXTURE_<NAME>_V_MASK`, `TEXTURE_<NAME>_WIDTH_SHIFT`).
  - Emits ROM-resident `TEXTURE_TABLE` (`.i`) and relocatable runtime `.tab` binaries (`magic = $5453 "ST"`) for Game Pak RAM Bank `$71` staging.
- **Isometric Level Authoring (`.sfxlevel` -> `.map`, `.i`, `_preview.png`)**:
  - Symbolic texture binding across $4\times 4$ to $16\times 16$ grids, all 12 `IsoEngine` shape archetypes, 90-degree rotations, and `1h`/`2h` perimeter edge panels.
  - Legacy `ca65` `def_cell` map importer (`showcase_map.i`, `starship_map.i`), Bank `$71` budget & geometry validator, and exact 30-degree orthographic software renderer (`IsoSoftwareRenderer`).
- **Virtual Slices & Descriptor Aliases**:
  - **Virtual Slices**: Define sub-regions of Source Textures sharing pixels without consuming additional atlas space.
  - **Descriptor Aliases**: Symbolic alternate names resolving to existing texture descriptors.
- **Multi-Document Tabbed Desktop Shell**:
  - Tabbed workspace supporting multiple open `Atlas Document` and `Level Document` editors with live cross-tab texture/palette updates.
  - Visual level editor with rotatable 3D Isometric Viewport, 2D Top-Down Grid, single/marquee multi-cell selection, Brush/Eyedropper tools with layer masks, and undo/redo history.
  - Visual grid snapping (`4px`, `8px`, `16px`), drag-and-drop placement with boundary clamping and collision avoidance, auto-fit and shelf-packing.
  - Zoomable pixel-art editor modal (Pencil, Flood Fill, Eyedropper, Undo/Redo).
  - Palette Conflict & Reduction modal (Euclidean auto-snap and Median-Cut 15-color auto-quantize).
  - Hex preview of generated binaries with offset highlighting.
  - High-DPI and 4K display support with dynamic UI scaling.
- **Headless CLI & MCP Tooling**:
  - Headless atlas and level creation, packing, validation, ca65 import, and batch graphic import via `./scripts/sfx-studio.sh`.
  - Stdio JSON-RPC Model Context Protocol (MCP) server via `./scripts/run-mcp.sh` exposing `sfx_pack_textures` and `sfx_manage_level` for agent automation.

---

## Architecture

The project is structured as a modular 3-tier reactor:

```
[sfx-studio-core] (Headless domain models, shelf packing, palette quantization, ca65 generator)
       │
       ├──> [sfx-studio-cli] (Headless CLI & stdio JSON-RPC MCP server)
       │
       └──> [sfx-studio-desktop] (Compose Multiplatform Desktop GUI & multi-document editor)
```

- **`sfx-studio-core`**: Headless domain layer with zero UI or MCP dependencies.
- **`sfx-studio-cli`**: Command-line interface and MCP server.
- **`sfx-studio-desktop`**: Compose Multiplatform desktop application.

---

## Building and Running

### Build and Test
```bash
./mvnw clean test package
```

### Apply Code Formatting
```bash
./mvnw spotless:apply
```

### Run Desktop Application
```bash
./scripts/sfx-studio.sh [path/to/TEXTURES.sfxproj]
```

### Run Headless CLI Commands
```bash
# Validate Atlas Document:
./scripts/sfx-studio.sh atlas validate path/to/TEXTURES.sfxproj

# Pack and export companion files:
./scripts/sfx-studio.sh atlas pack path/to/TEXTURES.sfxproj

# Import external images:
./scripts/sfx-studio.sh atlas import path/to/TEXTURES.sfxproj tile1.png tile2.png --snap-palette

# Create, validate, pack, or import isometric Level Documents:
./scripts/sfx-studio.sh level create path/to/courtyard.sfxlevel --atlas path/to/TEXTURES.sfxproj --cols 8 --rows 8
./scripts/sfx-studio.sh level validate path/to/courtyard.sfxlevel
./scripts/sfx-studio.sh level pack path/to/courtyard.sfxlevel --yaw 15
./scripts/sfx-studio.sh level import-ca65 path/to/showcase_map.i --atlas path/to/TEXTURES.sfxproj --output path/to/showcase.sfxlevel
```

### Run Headless MCP Server
```bash
./scripts/run-mcp.sh
```

---

## Architecture References
- Domain Glossary: [`GLOSSARY.md`](GLOSSARY.md)
- Monorepo Tracking ADR: [`../docs/adr/0005-umbrella-tracked-projects-toward-monorepo.md`](../docs/adr/0005-umbrella-tracked-projects-toward-monorepo.md)
- Dedicated Studio MCP ADR: [`../docs/adr/0006-dedicated-studio-mcp-server-and-packaged-skills.md`](../docs/adr/0006-dedicated-studio-mcp-server-and-packaged-skills.md)
