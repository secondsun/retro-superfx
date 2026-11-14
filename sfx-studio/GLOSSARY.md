# GLOSSARY: SuperFX Asset & Level Studio (`sfx-studio`)

Authoring studio and offline asset pipeline for creating, editing, validating, and exporting Texture Atlases and tactical Levels targeting the SuperFX isometric engine.

## Language

### Documents

**Atlas Document**:
The immutable authoring specification (`AtlasDocument`, persisted as `.sfxproj` or `.sfxatlas`) for a single Texture Atlas, holding its name, SNES BGR555 palette, grid snap, Source Textures, Virtual Slices, Descriptor Aliases, and optional explicit descriptor ordering so multiple Level Documents can share one atlas.
_Avoid_: Project file, texture project, atlas manifest

**Level Document**:
The authoring specification (`LevelDocument`, persisted as `.sfxlevel`) for a single Level, pairing its visual Map Grid and shared Atlas Document reference (`atlasPath`) with optional cell-aligned gameplay layers.
_Avoid_: Map project, stage file, scene file

### Texture Authoring & Descriptor Resolution

**Source Texture**:
An individual power-of-two graphic imported or drawn in the studio to be quantized, edited, and positioned within a Texture Atlas. Renaming a Source Texture cascades across dependent Virtual Slices, Descriptor Aliases, and explicit descriptor ordering; deleting one is rejected by default while dependents exist.
_Avoid_: Imported tile, bitmap graphic, source image

**Virtual Slice**:
A secondary region defined as a power-of-two sub-rectangle of an existing Source Texture within an Atlas Document, sharing texel data without occupying additional space in the Texture Atlas.
_Avoid_: Sub-texture, texture slice, crop region

**Descriptor Alias**:
An alternate symbolic identifier in an Atlas Document that resolves to the same runtime Texture Descriptor as another Source Texture, Virtual Slice, or non-cyclic alias.
_Avoid_: Texture alias, duplicate ID, linked texture

**Descriptor Table**:
The canonical resolved catalog (`DescriptorTable`, capped at 256 entries to fit unsigned 8-bit `.map` indices) built from an Atlas Document's Source Textures, Virtual Slices, and Descriptor Aliases. Owns symbolic and numeric `DescriptorLookup` (`Absent`, `Found`, `Unknown`), direct sub-rectangle texel extraction, and relocatable `.tab` serialization.
_Avoid_: Ad-hoc symbol map, slot list

**Pending Texture Import**:
A capability handle (`PendingTextureImport`) representing a decoded bitmap awaiting palette conflict resolution (manual slot mapping, nearest-color Euclidean snap, or global 15-color Median-Cut quantization) and atomic placement under a `PlacementPolicy`.
_Avoid_: Raw unplaced bitmap, remap dialog state

**Placement Policy**:
The rule (`PlacementPolicy`) governing how newly imported Source Textures are placed when current atlas bounds are full: `STRICT_BOUNDS` (default in CLI and `StudioMCP`) rejects overflow, whereas `AUTO_GROW_MAX_256` (default in Desktop, opt-in via `--auto-grow` / `auto_grow`) expands power-of-two dimensions up to `256x256` and warns if the `32 KB` Bank `$71` budget is exceeded.
_Avoid_: Silent resize, origin fallback

**Atlas Index**:
The exported ca65 symbolic include (`.i`) describing a Texture Atlas's dimensions and each entry's identifier, coordinates, masks, shift, and `TEXTURE_TABLE` offset for consumption by link-time builds.
_Avoid_: Index file, texture header

**Texture Table**:
The exported relocatable binary catalog (`.tab`, `magic = $5453 "ST"`) of 8-byte Texture Descriptors paired with a Texture Atlas for Bank `$71` runtime lookup without link-time symbol binding.
_Avoid_: Descriptor binary, texture LUT, slot file

### Level Editing & Operations Seams

**Cell Patch**:
A declarative single-cell or rectangular-region mutation (`CellPatch`) applied to a `MapGrid`, distinguishing omitted texture slots (preserved) from explicit `"NONE"` / `null` clears (`DescriptorLookup.Absent`).
_Avoid_: Ad-hoc cell map, raw JSON patch

**Studio Operations**:
The pure, stdio-quiet orchestration facade (`StudioOperations`) in `sfx-studio-core` shared by both the CLI (`CliMain`) and MCP server (`StudioMcpServer`) for validating, importing, patching, and atomically exporting Atlas and Level Documents.
_Avoid_: Duplicate CLI/MCP handlers

**Path Policy**:
The path-resolution and sandboxing boundary (`PathPolicy.UNRESTRICTED` vs `PathPolicy.sandboxed(workspaceRoot)`) injected into `StudioOperations` to prevent relative-path, `atlasPath`, and symlink escapes outside an agent workspace.
_Avoid_: Inline canonical-path checks

**Studio Platform**:
The desktop host boundary (`StudioPlatform`: `AwtStudioPlatform` in production, `FakeStudioPlatform` in headless tests) isolating native file/directory pickers, system clipboard access, OS modifier conventions, and user preferences from `StudioState`.
_Avoid_: Direct `FileDialog`/`JFileChooser` calls in session state
