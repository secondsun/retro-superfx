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
  - Standalone 15-color Median-Cut quantization across existing textures, plus interactive 5-bit RGB sliders (`0..31`) and color remapping.
- **Canonical `DescriptorTable`, ca65 Atlas Index (`.i`), & Relocatable Texture Table (`.tab`)**:
  - Unified `DescriptorTable` (`<= 256` descriptors for `u8` `.map` indices) with case-insensitive `TEXTURE_ID_*` / `TEX_*` resolution and `O(w*h)` sub-rectangle texel extraction.
  - Emits ROM-resident `TEXTURE_TABLE` (`.i`) and relocatable runtime `.tab` binaries (`magic = $5453 "ST"`) for Game Pak RAM Bank `$71` staging.
- **Isometric Level Authoring (`.sfxlevel` -> `.map`, `.i`, `_preview.png`)**:
  - Symbolic texture binding across $4\times 4$ to $16\times 16$ grids, all 12 `IsoEngine` shape archetypes, 90-degree rotations, and `1h`/`2h` perimeter edge panels.
  - Canonical `Absent` texture semantics (`NONE` / blank): `skirtTex = Absent` inherits `topTex` in both software preview and binary `.map`; `panelTex = Absent` renders nothing and serializes `0`.
  - Legacy `ca65` `def_cell` map importer, Bank `$71` budget & geometry validator, and exact 30-degree orthographic software renderer (`IsoSoftwareRenderer`).
- **Virtual Slices & Descriptor Aliases**:
  - **Virtual Slices**: Define power-of-two sub-regions of Source Textures sharing pixels without consuming additional atlas space.
  - **Descriptor Aliases**: Symbolic alternate names resolving to existing texture descriptors.
  - Cascading Source Texture renames and safe dependent-checked deletions.
- **Multi-Document Tabbed Desktop Shell**:
  - Tabbed workspace supporting multiple open `Atlas Document` and `Level Document` sessions with live in-memory cross-tab texture/palette propagation (`resolveLiveOrDiskAtlas`).
  - Unified polymorphic save routing (`.sfxproj` vs `.sfxlevel`), platform-aware shortcuts (`Cmd+S` on macOS, `Ctrl+S` on Windows/Linux), non-blocking workspace diagnostics banners, and testable `StudioPlatform` host seam.
- **Headless CLI & MCP Tooling (`StudioOperations`)**:
  - Shared stdio-quiet `StudioOperations` facade with `PathPolicy` workspace sandboxing (blocking `../`, `atlasPath`, and symlink escapes).
  - Headless atlas and level creation, cell/region patching (`level set-cells`), packing, validation, ca65 import, and batch graphic import via `./scripts/sfx-studio.sh`.
  - Stdio JSON-RPC Model Context Protocol (MCP) server via `./scripts/run-mcp.sh` exposing `sfx_pack_textures` and `sfx_manage_level` with strict Jackson DTO validation.

---

## Architecture

The project is structured as a modular 3-tier reactor:

```
[sfx-studio-core] (AtlasDocument, DescriptorTable, LevelValidator, IsoSoftwareRenderer, StudioOperations + PathPolicy)
       │
       ├──> [sfx-studio-cli] (Thin CliMain + strict-DTO StudioMcpServer over StudioOperations)
       │
       └──> [sfx-studio-desktop] (Compose Desktop GUI, StudioState + StudioPlatform, Atlas & Level editors)
```

- **`sfx-studio-core`**: Pure headless domain and operations layer with zero UI or MCP dependencies.
- **`sfx-studio-cli`**: Thin command-line and stdio JSON-RPC MCP adapters over `StudioOperations`.
- **`sfx-studio-desktop`**: Compose Multiplatform desktop application isolated behind `StudioPlatform`.

---

## Building and Running

### Build, Format, and Verify
```bash
./mvnw spotless:apply
./mvnw clean verify javadoc:javadoc
```

### Run Desktop Application
```bash
./scripts/sfx-studio.sh [path/to/TEXTURES.sfxproj | path/to/courtyard.sfxlevel]
```

### Run Headless CLI Commands
```bash
# Validate Atlas Document:
./scripts/sfx-studio.sh atlas validate path/to/TEXTURES.sfxproj

# Pack companion files (.bin, .pal, .tab, .i, _atlas.png) without duplicating .sfxproj in --output-dir:
./scripts/sfx-studio.sh atlas pack path/to/TEXTURES.sfxproj [--output-dir path/to/dist] [--auto-pack]

# Import external images (defaults to strict bounds; pass --auto-grow to expand up to 256x256):
./scripts/sfx-studio.sh atlas import path/to/TEXTURES.sfxproj tile1.png tile2.png --snap-palette [--auto-grow]

# Create, patch cells/regions, validate, pack, or import isometric Level Documents:
./scripts/sfx-studio.sh level create path/to/courtyard.sfxlevel --atlas path/to/TEXTURES.sfxproj --cols 8 --rows 8
./scripts/sfx-studio.sh level set-cells path/to/courtyard.sfxlevel --rect 2,2,5,5 --height 2 --top-tex stone
./scripts/sfx-studio.sh level set-cells path/to/courtyard.sfxlevel --cells-json @patches.json --resize 10x8
./scripts/sfx-studio.sh level validate path/to/courtyard.sfxlevel
./scripts/sfx-studio.sh level pack path/to/courtyard.sfxlevel [--output-dir path/to/dist] --yaw 15
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
- Bank `$71` Asset Staging & MSU-1 Seam ADR: [`../docs/adr/0007-bank-71-asset-staging-and-msu1-loader-seam.md`](../docs/adr/0007-bank-71-asset-staging-and-msu1-loader-seam.md)
