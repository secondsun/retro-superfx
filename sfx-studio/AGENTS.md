# AGENTS.md — SuperFX Asset & Level Studio (`sfx-studio`)

`sfx-studio` is the authoring studio and offline asset pipeline for creating, editing, validating, and packing Texture Atlases and tactical Levels targeting the SuperFX (GSU / FX3) coprocessor and isometric engine (`IsoEngine`).

---

## 1. Project Mission & Reactor Architecture

The project is structured as a 3-module Maven reactor on Java 26 and Kotlin 2.4 under root package `dev.secondsun.sfxstudio`:

```
                                [sfx-studio-core]
                    (Headless domain models, shelf packing,
                     palette quantization, ca65 generator)
                                       │
                      ┌────────────────┴────────────────┐
                      ▼                                 ▼
              [sfx-studio-cli]               [sfx-studio-desktop]
         (Headless CLI & stdio JSON-RPC    (Compose Multiplatform GUI,
               MCP Server)                  tabbed multi-document canvas)
```

### Module Breakdown
1. **`sfx-studio-core`**:
   - Pure domain models, algorithms, and file serializers.
   - Texture Atlas shelf-packing, Median-Cut 15-color palette quantization, SNES BGR555 conversion, relocatable Texture Table (`.tab`) generation, ca65 assembly Atlas Index (`.i`) generation, isometric Level Document (`.sfxlevel` / `.map`) serialization, `Ca65MapImporter`, `LevelValidator`, and `IsoSoftwareRenderer`.
   - **Strict Headless Invariant**: Zero Compose Multiplatform, Skiko, or MCP dependencies. Must compile and execute headlessly in any JVM environment without graphical subsystems.
2. **`sfx-studio-cli`**:
   - Headless CLI interface (`atlas` and `level` command groups) and stdio JSON-RPC MCP server (`StudioMcpServer`).
   - Implements `sfx_pack_textures` and `sfx_manage_level` tools for automated agent workflows.
   - Depends only on `sfx-studio-core`. Zero UI/Compose dependencies.
3. **`sfx-studio-desktop`**:
   - Interactive Compose Multiplatform Desktop UI.
   - Tabbed multi-document canvas supporting both Atlas Editors and visual Isometric Level Editors (rotatable 3D software viewport, 2D top-down grid, multi-cell selection, brush/eyedropper tools, and live Bank `$71` diagnostics), pixel editor, palette conflict resolver modal, hex dump preview, and high-DPI scaling.

---

## 2. Multi-Document Architecture & Extension Seam

The studio employs a multi-document workspace architecture centered on `StudioState` and `EditorSession`:
- **Atlas Document** (`.sfxproj`, `.sfxatlas`): Authoring specification for a single Texture Atlas, defining palette, Source Textures, layout coordinates, Virtual Slices, and Descriptor Aliases.
- **Level Document** (`.sfxlevel`): Authoring specification for an isometric level, binding Map Grid surface geometry (`4x4` to `16x16`) and cell-aligned gameplay layers to a referenced Atlas Document by symbolic texture name.
- **Multi-Document Seam**: `StudioState` manages multiple open `EditorSession` instances (`AtlasEditorState`, `LevelEditorState`) in tabs. Open Level Documents resolve live texture pixels and palettes directly from an open matching `AtlasEditorState` tab, falling back to disk when the atlas is not open.

---

## 3. Essential Commands & Workflows

Always use the project Maven Wrapper (`./mvnw`):

- **Compile and Run Tests**:
  ```bash
  ./mvnw clean test
  ```
- **Apply Code Formatting**:
  ```bash
  ./mvnw spotless:apply
  ```
- **Check Code Formatting**:
  ```bash
  ./mvnw spotless:check
  ```
- **Build Full Package (All Modules & JARs)**:
  ```bash
  ./mvnw clean test package
  ```
- **Generate Javadocs**:
  ```bash
  ./mvnw test javadoc:javadoc
  ```

### CLI and MCP Execution
- **Run Headless MCP Server (stdio JSON-RPC)**:
  ```bash
  ./scripts/run-mcp.sh
  ```
- **Validate an Atlas Document**:
  ```bash
  ./scripts/sfx-studio.sh atlas validate path/to/TEXTURES.sfxproj
  ```
- **Pack an Atlas Document**:
  ```bash
  ./scripts/sfx-studio.sh atlas pack path/to/TEXTURES.sfxproj
  ```
- **Import Bitmap Graphics with Palette Snapping**:
  ```bash
  ./scripts/sfx-studio.sh atlas import path/to/TEXTURES.sfxproj tile1.png tile2.png --snap-palette
  ```
- **Create, Validate, Pack, or Import Isometric Levels**:
  ```bash
  ./scripts/sfx-studio.sh level create path/to/courtyard.sfxlevel --atlas path/to/TEXTURES.sfxproj --cols 8 --rows 8
  ./scripts/sfx-studio.sh level validate path/to/courtyard.sfxlevel
  ./scripts/sfx-studio.sh level pack path/to/courtyard.sfxlevel --yaw 15
  ./scripts/sfx-studio.sh level import-ca65 path/to/showcase_map.i --atlas path/to/TEXTURES.sfxproj --output path/to/showcase.sfxlevel
  ```
- **Launch Interactive Desktop UI**:
  ```bash
  ./scripts/sfx-studio.sh [path/to/TEXTURES.sfxproj | path/to/courtyard.sfxlevel]
  ```

---

## 4. Hardware & Format Invariants

1. **Unpacked 1-Byte-Per-Texel**: Every texel in `.bin` is an unpacked single byte (`$00`–`$0F`) for zero-bit-unpacking `getc; plot` rasterization.
2. **Power-of-Two Dimensions**: Atlas width and height must each be powers of two in `[16, 32, 64, 128, 256]`.
3. **BGR555 Palette**: 16 little-endian words (32 bytes). Slot 0 is permanently transparent (`$0000`). Opaque black is clamped to `$0001` (`R=1, G=0, B=0`).
4. **ca65 Symbol Rules**: Texture names and aliases must match valid ca65 identifiers (`[A-Za-z_][A-Za-z0-9_]*`).

---

## 5. Domain Documentation & ADRs

- Domain Glossary: [`GLOSSARY.md`](GLOSSARY.md)
- Monorepo Tracking ADR: [`../docs/adr/0005-umbrella-tracked-projects-toward-monorepo.md`](../docs/adr/0005-umbrella-tracked-projects-toward-monorepo.md)
- Dedicated Studio MCP ADR: [`../docs/adr/0006-dedicated-studio-mcp-server-and-packaged-skills.md`](../docs/adr/0006-dedicated-studio-mcp-server-and-packaged-skills.md)
- Bank `$71` Asset Staging & MSU-1 Seam ADR: [`../docs/adr/0007-bank-71-asset-staging-and-msu1-loader-seam.md`](../docs/adr/0007-bank-71-asset-staging-and-msu1-loader-seam.md)
