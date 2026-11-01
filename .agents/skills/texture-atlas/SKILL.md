---
name: texture-atlas
description: >-
  Author, pack, update, and validate SuperFX texture atlases (.sfxproj) for IsoEngine and 3D GSU scenes.
  Use when asked to create new textures, modify existing textures or palettes, define virtual slices or
  descriptor aliases, or generate companion binaries (.bin, .pal) and ca65 assembly includes (.i).
---

# SuperFX Texture Atlas Authoring & Packing

This skill guides agents through creating, updating, and packing SuperFX (FX3 / GSU) Texture Atlases for 3D polygon rasterization and `IsoEngine` dioramas.

---

## 1. Hardware & Engine Invariants

1. **Unpacked 1-Byte-Per-Texel Layout**:
   - Every texel in `.bin` / `_graphics.bin` is stored as an unpacked single byte holding a 4-bit palette index (`$00`–`$0F`) in row-major order.
   - Enables the SuperFX scanline rasterizer loop to execute `getc; plot` directly with zero runtime bit-unpacking or masking.
2. **Independent Power-of-Two Dimensions**:
   - Atlas width and height must each be a power of two in `[16, 32, 64, 128, 256]`.
   - Wrapping is computed with bitmasks: `u_mask = width - 1` and `v_mask = height - 1`.
   - Row stride offset is computed via `V << ATLAS_STRIDE_SHIFT` (where `ATLAS_STRIDE_SHIFT = log2(width)`), avoiding hardware multiplication.
3. **SNES 16-Color BGR555 Palette (`.pal`)**:
   - 32 bytes (16 little-endian 16-bit words).
   - **Slot 0 is permanently transparent** (`$0000`).
   - **Opaque black is clamped to `$0001`** (`R=1, G=0, B=0`) so black texels and transparent texels remain unambiguous.
4. **Link-Time Bank 4 Relocation**:
   - In ca65, `TEXTURE_TABLE` resides in `.segment "GSUDATA"` and points to ROM texel offsets pre-baked at link time using `.loword(textures_binary + TEXTURE_<NAME>_OFFSET)`.
5. **Virtual Slices & Descriptor Aliases**:
   - **Virtual Slice**: Sub-rectangle of an existing Source Texture (e.g. $32 \times 16$ parapet inside stone, $16 \times 32$ foliage fin inside grass). Shares pixels without allocating extra atlas space.
   - **Descriptor Alias**: Alternate symbolic name mapping to an existing descriptor (e.g. `water_deep` -> `water`).

---

## 2. Tools & Invocation

Agents should use the **`sfx_pack_textures`** tool on the `retro-superfx_lsp` MCP server, or the fallback CLI script `./tools/texture-packer.sh`.

### MCP Tool Usage (`sfx_pack_textures`)

```json
{
  "ServerName": "retro-superfx_lsp",
  "ToolName": "sfx_pack_textures",
  "Arguments": {
    "project_path": "snes-sfx-demo/X-GSU/IsoEngine/Data/TEXTURES.sfxproj",
    "action": "pack"
  }
}
```

Parameters:
- `project_path`: Path to the `.sfxproj` manifest (workspace-relative).
- `action`: `"pack"` (default), `"import"`, or `"validate"`.
- `auto_pack`: Boolean; when `true`, automatically computes shelf packing for all textures.
- `output_dir`: Optional alternate destination directory for exported files.
- `image_paths`: Array of image paths to import (when `action` is `"import"`).
- `snap_palette`: Boolean; snaps incoming colors to the nearest assigned palette slots.
- `auto_quantize`: Boolean; executes global Median-Cut 15-color quantization across all textures.

### CLI Fallback

```bash
# Validate project manifest:
./tools/texture-packer.sh validate snes-sfx-demo/X-GSU/IsoEngine/Data/TEXTURES.sfxproj

# Pack and export companion files:
./tools/texture-packer.sh pack snes-sfx-demo/X-GSU/IsoEngine/Data/TEXTURES.sfxproj

# Import external images:
./tools/texture-packer.sh import <project.sfxproj> tile1.png tile2.png --snap-palette
```

---

## 3. Workflows

### Workflow A: Direct Text/ASCII Pixel Art Authoring

Agents can draft or edit textures directly in `.sfxproj` using the `pixelsAscii` multiline grid format.

In `pixelsAscii`:
- `.` represents palette index `0` (transparent).
- `1` through `9` represent palette indices `1` through `9`.
- `A` through `F` (or `a` through `f`) represent palette indices `10` through `15`.
- Whitespace and newlines format the 2D grid and are ignored during parsing.

Example `ProjectTextureDto`:
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

After modifying the `.sfxproj`, call `sfx_pack_textures` with `action: "pack"` to update the binaries and ca65 includes.

---

### Workflow B: Defining Virtual Slices & Aliases

To add a sub-region (like an architectural panel or fin) without duplicating bitmap data:

1. Add an entry to the `slices` array in `.sfxproj`:
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
3. To preserve a specific sequential order in `TEXTURE_TABLE`, set `descriptorOrder`:
```json
"descriptorOrder": [
  "roof", "grass", "water", "sand", "water_deep", "stone", "parapet", "foliage"
]
```
4. Run `sfx_pack_textures` to regenerate `TEXTURES.i`.

---

### Workflow C: Creating a New Texture Atlas

1. Create a new directory alongside your scene/model data (e.g. `snes-sfx-demo/X-GSU/MyScene/Data/`).
2. Scaffold a new `.sfxproj` JSON with the desired dimensions (e.g. 64x64 or 128x128), 16-color BGR555 palette, and texture definitions.
3. Call `sfx_pack_textures` with `action: "pack"`.
4. Include the generated files in your assembly code:
```assembly
.include "Data/MY_SCENE.i"

.segment "GSUDATA"
textures_binary:
    .incbin "Data/MY_SCENE_graphics.bin"

.segment "ROMSPR"
    incbin Palette, "Data/MY_SCENE_palette.bin"
```

---

## 4. Mandatory Framebuffer Verification Gate

Per workspace invariants, any modification to an active scene atlas (such as `IsoEngine/Data/TEXTURES`) must execute the following verification steps before declaring completion:

1. **Rebuild the ROM**:
   ```bash
   make -C snes-sfx-demo/X-GSU/IsoEngine clean all
   ```
2. **Execute Headless Framebuffer Capture**:
   Call the `capture_sfx_render` tool on `retro-superfx_mesen`:
   ```json
   {
     "ServerName": "retro-superfx_mesen",
     "ToolName": "capture_sfx_render",
     "Arguments": {
       "scene_test_name": "IsoEngine"
     }
   }
   ```
3. **Verify Metrics**:
   Confirm `status == "SUCCESS"` and `metrics.non_black_pixels > 0`.

