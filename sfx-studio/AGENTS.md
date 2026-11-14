# AGENTS.md — SuperFX Asset & Level Studio (`sfx-studio`)

`sfx-studio` is the authoring studio and offline asset pipeline for creating, editing, validating, and packing Texture Atlases and tactical Levels targeting the SuperFX (GSU / FX3) coprocessor and isometric engine (`IsoEngine`).

---

## 1. Project Mission & Deepened Reactor Architecture

The project is structured as a 3-module Maven reactor on Java 26 and Kotlin 2.4 under root package `dev.secondsun.sfxstudio`:

```
                                 [sfx-studio-core]
             (AtlasDocument, DescriptorTable, PendingTextureImport,
              CellPatch, LevelValidator, IsoSoftwareRenderer,
              StudioOperations + PathPolicy)
                                        │
                       ┌────────────────┴────────────────┐
                       ▼                                 ▼
               [sfx-studio-cli]               [sfx-studio-desktop]
          (Thin CliMain & strict-DTO       (Compose Multiplatform GUI,
           StudioMcpServer adapters)        StudioState + StudioPlatform)
```

### Module Breakdown
1. **`sfx-studio-core`**:
   - **Deepened Domain Modules**:
     - `AtlasDocument`: Immutable value module owning `.sfxproj`/`.sfxatlas` persistence, pre-write atomic validation (`saveAndExport` / `exportOnly`), standalone 15-color Median-Cut `quantizePalette()`, cascading `renameTexture`, dependent-checked `removeTexture`, `PendingTextureImport`, and `PlacementPolicy` (`STRICT_BOUNDS` vs `AUTO_GROW_MAX_256`). Low-level DTOs, `MedianCutQuantizer`, `TextureImporter`, `AtlasIndexGenerator`, and `AtlasTableGenerator` are `internal`.
     - `DescriptorTable` (`DescriptorLookup = Absent | Found | Unknown`): Single canonical symbolic/numeric Texture Descriptor resolver (`<= 256` entries), stripping `TEXTURE_ID_` and `TEX_` prefixes case-insensitively and extracting sub-rectangle pixels in `O(w*h)`.
     - Pure in-process `LevelValidator`, `LevelBinarySerializer`, `IsoSoftwareRenderer`, `Ca65MapImporter`, and shared `CellPatch` (`MapGrid.applyPatches`) — zero implicit disk `atlasPath` reads inside core validators/renderers.
     - `StudioOperations(pathPolicy: PathPolicy)`: Stdio-quiet orchestration facade with `PathPolicy.UNRESTRICTED` (CLI) and `PathPolicy.sandboxed(workspaceRoot)` (MCP — enforces normalized prefix + `toRealPath()` on existing ancestors to block `../`, `doc.atlasPath`, and symlink escapes).
   - **Strict Headless Invariant**: Zero Compose Multiplatform, Skiko, or MCP dependencies. Never writes to `System.out`.
2. **`sfx-studio-cli`**:
   - Thin adapters over `StudioOperations`: `CliMain` (`atlas` and `level` command groups, including `level set-cells` and `atlas import --auto-grow`) and `StudioMcpServer` (`sfx_pack_textures` and `sfx_manage_level` backed by strict Jackson `@JsonAlias` DTOs that reject mistyped JSON scalars).
3. **`sfx-studio-desktop`**:
   - Interactive Compose Multiplatform Desktop UI centered on `StudioState`, `EditorSession` (`defaultExtension`, `documentTypeLabel`), and injected `StudioPlatform` (`AwtStudioPlatform` in production, `FakeStudioPlatform` in headless unit tests).
   - Routes all save flows polymorphically through `StudioState.saveSession` / `saveSessionAs` / `saveAllDirtySessions`, resolves live unsaved atlases across open tabs before disk (`resolveLiveOrDiskAtlas`), and surfaces command/I/O failures through `StudioState.errorMessage` plus non-blocking workspace diagnostics banners.

---

## 2. Multi-Document & Testing Seams

- **Cross-Tab Live Atlas Resolution**: `StudioState.resolveLiveOrDiskAtlas(atlasFile)` checks open `AtlasEditorState` tabs by canonical path **before** touching disk, so unsaved textures, slices, aliases, and palette edits propagate immediately to `newLevelSession`, `importCa65Map`, `LevelValidator`, and `IsoSoftwareRenderer`.
- **Companion-Only `--output-dir` / `output_dir`**: When an explicit output directory is passed to `atlas pack` or `level pack`, only runtime companion artifacts (`.bin`, `.pal`, `.tab`, `.i`, `_atlas.png` or `.map`, `.i`, `_preview.png`) are written to `outputDir`; authoring `.sfxproj` / `.sfxlevel` files are never duplicated into `outputDir`.
- **Headless Desktop Testing**: Inject `FakeStudioPlatform` into `StudioState` / `AtlasEditorState` / `LevelEditorState` to test save-as extensions, open/import flows, clipboard, and preferences without AWT modals or touching OS user preferences.

---

## 3. Essential Commands & Workflows

Always use the project Maven Wrapper (`./mvnw`):

- **Apply Code Formatting**:
  ```bash
  ./mvnw spotless:apply
  ```
- **Full Verification Gate (Tests, Shaded JAR, Spotless, Javadoc)**:
  ```bash
  ./mvnw clean verify javadoc:javadoc
  ```

### CLI and MCP Execution
- **Run Headless MCP Server (stdio JSON-RPC)**:
  ```bash
  ./scripts/run-mcp.sh
  ```
- **Atlas Commands**:
  ```bash
  ./scripts/sfx-studio.sh atlas validate path/to/TEXTURES.sfxproj
  ./scripts/sfx-studio.sh atlas pack path/to/TEXTURES.sfxproj [--output-dir path/to/dist] [--auto-pack]
  ./scripts/sfx-studio.sh atlas import path/to/TEXTURES.sfxproj tile1.png --snap-palette [--auto-grow]
  ```
- **Level Commands**:
  ```bash
  ./scripts/sfx-studio.sh level create path/to/courtyard.sfxlevel --atlas path/to/TEXTURES.sfxproj --cols 8 --rows 8
  ./scripts/sfx-studio.sh level set-cells path/to/courtyard.sfxlevel --rect 1,1,3,3 --height 2 --top-tex stone
  ./scripts/sfx-studio.sh level set-cells path/to/courtyard.sfxlevel --cells-json @patches.json --resize 10x8
  ./scripts/sfx-studio.sh level validate path/to/courtyard.sfxlevel
  ./scripts/sfx-studio.sh level pack path/to/courtyard.sfxlevel [--output-dir path/to/dist] --yaw 15
  ./scripts/sfx-studio.sh level import-ca65 path/to/showcase_map.i --atlas path/to/TEXTURES.sfxproj --output path/to/showcase.sfxlevel
  ```
- **Launch Interactive Desktop UI**:
  ```bash
  ./scripts/sfx-studio.sh [path/to/TEXTURES.sfxproj | path/to/courtyard.sfxlevel]
  ```

---

## 4. Hardware & Format Invariants

1. **Unpacked 1-Byte-Per-Texel**: Every texel in `.bin` is an unpacked single byte (`$00`–`$0F`) for zero-bit-unpacking `getc; plot` rasterization.
2. **Power-of-Two Dimensions & `<= 256` Descriptors**: Atlas width/height and slice dimensions must each be powers of two in `[16, 32, 64, 128, 256]`. Total descriptors (`textures + slices + aliases`) must not exceed `256` (`u8` `.map` slot limit); atlases exceeding `32 KB` (`256x256`) emit an `ATLAS_EXCEEDS_BANK_71` warning when staged into Bank `$71`.
3. **BGR555 Palette**: 16 little-endian words (32 bytes). Slot 0 is permanently transparent (`$0000`). Opaque black is clamped to `$0001` (`R=1, G=0, B=0`).
4. **Canonical `Absent` Texture Resolution**: `null`, `""`, `"NONE"`, `"TEX_NONE"`, and `"TEXTURE_ID_NONE"` resolve to `DescriptorLookup.Absent`. `skirtTex = Absent` inherits `topTex` in both `IsoSoftwareRenderer` and `LevelBinarySerializer`; `panelTex = Absent` renders nothing and serializes `0`.
5. **ca65 Symbol Rules**: Texture names, slices, and aliases must match valid ca65 identifiers (`[A-Za-z_][A-Za-z0-9_]*`) and be unique case-insensitively across the document.

---

## 5. Domain Documentation & ADRs

- Domain Glossary: [`GLOSSARY.md`](GLOSSARY.md)
- Monorepo Tracking ADR: [`../docs/adr/0005-umbrella-tracked-projects-toward-monorepo.md`](../docs/adr/0005-umbrella-tracked-projects-toward-monorepo.md)
- Dedicated Studio MCP ADR: [`../docs/adr/0006-dedicated-studio-mcp-server-and-packaged-skills.md`](../docs/adr/0006-dedicated-studio-mcp-server-and-packaged-skills.md)
- Bank `$71` Asset Staging & MSU-1 Seam ADR: [`../docs/adr/0007-bank-71-asset-staging-and-msu1-loader-seam.md`](../docs/adr/0007-bank-71-asset-staging-and-msu1-loader-seam.md)
