---
name: texture-atlas
description: >-
  Author, pack, update, and validate SuperFX texture atlases (.sfxproj) for IsoEngine and 3D GSU scenes.
  Use when asked to create new textures, modify existing textures or palettes, define virtual slices or
  descriptor aliases, or generate companion binaries (.bin, .pal, .tab) and ca65 assembly includes (.i).
---

# SuperFX Texture Atlas Authoring & Packing

This skill guides agents through creating, updating, validating, and packing SuperFX (FX3 / GSU) Texture Atlases for 3D polygon rasterization and `IsoEngine` dioramas.

---

## 1. Hardware & Engine Invariants

1. **Unpacked 1-Byte-Per-Texel Layout**:
   - Every texel in `.bin` (e.g. `TEXTURES.bin`) is stored as an unpacked single byte holding a 4-bit palette index (`$00`–`$0F`) in row-major order.
   - Enables the SuperFX scanline rasterizer loop to execute `getc; plot` directly with zero runtime bit-unpacking or masking.
2. **Independent Power-of-Two Dimensions & `<= 256` Descriptors**:
   - Atlas width/height and slice dimensions must each be a power of two in `[16, 32, 64, 128, 256]`.
   - Wrapping is computed with bitmasks: `u_mask = width - 1` and `v_mask = height - 1`.
   - Row stride offset is computed via `V << ATLAS_STRIDE_SHIFT` (where `ATLAS_STRIDE_SHIFT = log2(width)`), avoiding hardware multiplication.
   - Total resolved descriptors (`textures + slices + aliases`) must not exceed **`256`** (`DescriptorTable.MAX_ENTRIES`, matching `u8` `.map` texture indices). Atlases larger than `32 KB` (`256x256` = `64 KB`) are valid for ROM scenes (`IsoEngine`) but exceed Game Pak RAM Bank `$71`'s `32 KB` `.bin` window.
3. **SNES 16-Color BGR555 Palette (`.pal`)**:
   - 32 bytes (16 little-endian 16-bit words).
   - **Slot 0 is permanently transparent** (`$0000`).
   - **Opaque black is clamped to `$0001`** (`R=1, G=0, B=0`) so black texels and transparent texels remain unambiguous.
4. **Link-Time Bank 4 & Relocatable Bank `$71` Tables**:
   - In ROM-resident scenes (`IsoEngine`), `TEXTURE_TABLE` resides in `.segment "GSUDATA"` (`.i`) with link-time `.loword(textures_binary + TEXTURE_<NAME>_OFFSET)`.
   - For RAM Bank-`$71` scenes (`gsu_iso`), packing also emits `<NAME>.tab` (`magic = $5453 "ST"`, `entry_count`, `atlas_width`, `stride_shift` + `0`-based 8-byte descriptors matching `$71:0000+` addresses).
5. **Virtual Slices, Descriptor Aliases, & Safe Referential Integrity**:
   - **Virtual Slice**: Power-of-two sub-rectangle of an existing Source Texture (e.g. $32 \times 16$ parapet inside stone, $16 \times 32$ foliage fin inside grass). Shares texel data without allocating extra atlas space.
   - **Descriptor Alias**: Alternate symbolic name mapping to an existing descriptor (e.g. `water_deep` -> `water`). Cyclic aliases and case-insensitive duplicate ca65 identifiers are rejected during validation.
   - In `AtlasDocument`, renaming a Source Texture cascades across dependent slices, aliases, and `descriptorOrder`; deleting a Source Texture is rejected while slices or aliases reference it unless `cascadeDependents = true`.

---

## 2. Tools & Invocation

> [!IMPORTANT]
> **Always use `call_mcp_tool` first.** In Antigravity, MCP tools are lazy-loaded behind the top-level **`call_mcp_tool`** tool declaration (there are no top-level `sfx_pack_textures`, `sfx_lookup_symbol`, or `capture_sfx_render` tool declarations). Invoke **`call_mcp_tool`** directly with `ServerName`, `ToolName`, and `Arguments` — you do **not** need to read `<appDataDir>/mcp/` schema files first. Do not fall back to CLI scripts or `grep` unless `call_mcp_tool` returns an explicit server-unavailable error.

### A. Pack, Import, or Validate Atlases (`sfx_pack_textures` via `call_mcp_tool`)

Call the **`call_mcp_tool`** tool with:
```json
{
  "ServerName": "retro-superfx_studio",
  "ToolName": "sfx_pack_textures",
  "Arguments": {
    "project_path": "snes-sfx-demo/X-GSU/IsoEngine/Data/TEXTURES.sfxproj",
    "action": "pack"
  }
}
```
*(Note: When using `sfx-studio` as a standalone plugin outside the umbrella workspace, pass `"ServerName": "sfx-studio_studio"` instead.)*

Parameters inside `Arguments` (strict JSON types — booleans must be `true`/`false`, never strings):
- `project_path` (or `projectPath`): Workspace-relative path to the Atlas Document (`.sfxproj` or `.sfxatlas`).
- `action`: `"pack"` (default — validates atomically before writing `<NAME>.bin`, `<NAME>.pal`, `<NAME>.tab`, `<NAME>.i`, and `<NAME>_atlas.png`), `"import"`, or `"validate"`.
- `auto_pack`: Boolean; when `true`, automatically computes shelf packing for all textures and updates `.sfxproj` in place.
- `output_dir`: Optional alternate destination directory for companion files (`.bin`, `.pal`, `.tab`, `.i`, `_atlas.png`) — never copies `.sfxproj` into `output_dir`.
- `image_paths`: Array of image paths to import (when `action` is `"import"`).
- `snap_palette`: Boolean; snaps incoming colors to the nearest assigned palette slots.
- `auto_quantize`: Boolean; executes global Median-Cut 15-color quantization across all textures.
- `auto_grow`: Boolean (default `false` in CLI/MCP `STRICT_BOUNDS`); when `true`, automatically grows power-of-two atlas dimensions up to `256x256` if current bounds are full.

Returns structured JSON:
- `status`: `"SUCCESS"` or `"FAIL"`.
- `valid`: Boolean indicating manifest and packing validity.
- `atlasWidth`, `atlasHeight`: Computed atlas dimensions.
- `descriptorCount`: Number of generated descriptors in the `DescriptorTable`.
- `exportedFiles`: List of written file paths.

### B. Symbol & Reference Search (`sfx_lookup_symbol`, `sfx_find_references`, `sfx_resolve_address`)

When inspecting where texture constants (`TEXTURE_ID_*`, `TEXTURE_*_OFFSET`, `TEXTURE_TABLE`), primitive archetypes (`PRIM_*`), or scene assembly routines (`map_compile_quad_face`, `SuperFX.s`, `map.sgs`) are **defined** or **used** across `.s`/`.sgs`/`.i`/`.inc` files, **do not use `grep`**. Call **`call_mcp_tool`** instead:

- **Find where a symbol is defined** (file path, 1-based line number, and doc comment) — call **`call_mcp_tool`**:
  ```json
  {
    "ServerName": "retro-superfx_lsp",
    "ToolName": "sfx_lookup_symbol",
    "Arguments": {
      "symbol": "TEXTURE_ID_FOLIAGE",
      "path": "snes-sfx-demo/X-GSU/IsoEngine/"
    }
  }
  ```
- **Find all references/usages of a symbol** across assembly files — call **`call_mcp_tool`**:
  ```json
  {
    "ServerName": "retro-superfx_lsp",
    "ToolName": "sfx_find_references",
    "Arguments": {
      "symbol": "TEXTURE_ID_FOLIAGE",
      "path": "snes-sfx-demo/X-GSU/IsoEngine/"
    }
  }
  ```
- **Resolve a linker address from `.cpu.sym` / `.dbg`** — call **`call_mcp_tool`**:
  ```json
  {
    "ServerName": "retro-superfx_mesen",
    "ToolName": "sfx_resolve_address",
    "Arguments": {
      "project": "snes-sfx-demo/X-GSU/IsoEngine",
      "symbol": "TEXTURE_TABLE"
    }
  }
  ```

### C. Emergency CLI Fallback (Only if `call_mcp_tool` is Unavailable)

Only if the MCP server cannot be reached via `call_mcp_tool`:
```bash
# Validate Atlas Document:
./sfx-studio/scripts/sfx-studio.sh atlas validate snes-sfx-demo/X-GSU/IsoEngine/Data/TEXTURES.sfxproj

# Pack companion files (optionally into --output-dir without copying .sfxproj):
./sfx-studio/scripts/sfx-studio.sh atlas pack snes-sfx-demo/X-GSU/IsoEngine/Data/TEXTURES.sfxproj [--output-dir <dir>]

# Import external images (pass --auto-grow to expand up to 256x256 when bounds are full):
./sfx-studio/scripts/sfx-studio.sh atlas import <project.sfxproj> tile1.png tile2.png --snap-palette [--auto-grow]
```

---

## 3. Workflows

### Workflow A: Direct Text/ASCII Pixel Art Authoring

Agents can draft or edit textures directly in an Atlas Document (`.sfxproj` / `.sfxatlas`) using the `pixelsAscii` multiline grid format:
- `.` represents palette index `0` (transparent).
- `1` through `9` represent palette indices `1` through `9`.
- `A` through `F` (or `a` through `f`) represent palette indices `10` through `15`.
- Whitespace and newlines format the 2D grid and are ignored during parsing.

Example texture entry in `.sfxproj`:
```json
{
  "name": "brick",
  "width": 16,
  "height": 16,
  "x": 0,
  "y": 0,
  "pixelsAscii": "
    1111111111111111
    1333333113333331
    1333333113333331
    1111111111111111
    1333113333331133
    1333113333331133
    1111111111111111
    1333333113333331
    1333333113333331
    1111111111111111
    1333113333331133
    1333113333331133
    1111111111111111
    1333333113333331
    1333333113333331
    1111111111111111
  "
}
```

After modifying the Atlas Document, call `sfx_pack_textures` with `action: "pack"` to validate and update the binaries and ca65 includes.

---

### Workflow B: Defining Virtual Slices & Aliases

To add a sub-region (like an architectural panel or fin) without duplicating bitmap data:

1. Add an entry to the `slices` array in the Atlas Document:
```json
"slices": [
  {
    "name": "parapet",
    "sourceTexture": "stone",
    "x": 0,
    "y": 0,
    "width": 32,
    "height": 16
  }
]
```
2. Add an alias to the `aliases` array if referencing existing texels under another name:
```json
"aliases": [
  {
    "name": "water_deep",
    "target": "water"
  }
]
```
3. When `descriptorOrder` is set, it **must list every defined texture, slice, and alias exactly once** (no omissions or duplicates):
```json
"descriptorOrder": [
  "roof", "grass", "water", "sand", "water_deep", "stone", "parapet", "foliage"
]
```
4. Run `sfx_pack_textures` to regenerate `TEXTURES.i` and `TEXTURES.tab`.

---

### Workflow C: Creating a New Texture Atlas

1. Create a new directory alongside your scene/model data (e.g. `snes-sfx-demo/X-GSU/MyScene/Data/`).
2. Scaffold a new Atlas Document JSON (`.sfxproj`) with the desired dimensions (e.g. 64x64 or 128x128), 16-color BGR555 palette, and texture definitions.
3. Call `sfx_pack_textures` with `action: "pack"`.
4. Include the generated files in your assembly code:
```assembly
.include "Data/MY_SCENE.i"

.segment "GSUDATA"
textures_binary:
    .incbin "Data/MY_SCENE.bin"

.segment "ROMSPR"
    incbin Palette, "Data/MY_SCENE.pal"
```

---

## 4. Mandatory Framebuffer Verification Gate

Per workspace invariants, any modification to an active scene atlas (such as `IsoEngine/Data/TEXTURES`) must execute the following verification steps before declaring completion:

1. **Rebuild the ROM**:
   ```bash
   make -C snes-sfx-demo/X-GSU/IsoEngine clean all
   ```
2. **Execute Headless Framebuffer Capture via `call_mcp_tool`**:
   ```json
   {
     "ServerName": "retro-superfx_mesen",
     "ToolName": "capture_sfx_render",
     "Arguments": {
      "scene_test_name": "IsoEngine",
      "timeout_seconds": 15
     }
   }
   ```
3. **Verify Metrics & Visually Inspect**:
   - Confirm `status == "SUCCESS"` and `metrics.non_black_pixels > 0`.
   - Call **`read_file`** directly on the returned `png_path` to visually inspect the rendered scene.

---

## 5. Implementation & Generator Pointers

When modifying `sfx-studio` itself or its generated `.i`/`.tab` formats:

- **Immutable Document & Atomic Export**: `sfx-studio/sfx-studio-core/src/main/kotlin/dev/secondsun/sfxstudio/core/atlas/AtlasDocument.kt`
- **Canonical Descriptor Table & `.tab` Binary**: `sfx-studio/sfx-studio-core/src/main/kotlin/dev/secondsun/sfxstudio/core/atlas/DescriptorTable.kt`
- **ca65 Index & `TEXTURE_TABLE` Generator**: `sfx-studio/sfx-studio-core/src/main/kotlin/dev/secondsun/sfxstudio/core/atlas/AtlasIndexGenerator.kt`
- **Shared CLI/MCP Facade & Path Sandboxing**: `sfx-studio/sfx-studio-core/src/main/kotlin/dev/secondsun/sfxstudio/core/ops/StudioOperations.kt`
- **Core Unit Tests**: `sfx-studio/sfx-studio-core/src/test/kotlin/dev/secondsun/sfxstudio/core/atlas/AtlasDocumentTest.kt` and `DescriptorTableResolutionTest.kt`
- **MCP Tool Adapter**: `sfx-studio/sfx-studio-cli/src/main/kotlin/dev/secondsun/sfxstudio/cli/mcp/StudioMcpServer.kt`
- **Rebuild & Verify After Editing Kotlin Sources**:
  ```bash
  ./sfx-studio/mvnw -f sfx-studio/pom.xml spotless:apply
  ./sfx-studio/mvnw -f sfx-studio/pom.xml clean verify javadoc:javadoc
  ```
