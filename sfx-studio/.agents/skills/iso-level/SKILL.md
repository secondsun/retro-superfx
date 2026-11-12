---
name: iso-level
description: >-
  Author, edit, import, validate, and pack SuperFX isometric Level Documents (.sfxlevel) and Bank-$71
  runtime maps (.map). Use when asked to create or modify IsoEngine/gsu_iso maps, convert ca65 def_cell
  maps, inspect software level previews (_preview.png), or wire levels into RAM Bank $71 or MSU-1 loaders.
---

# SuperFX Isometric Level Authoring & Bank-`$71` Staging

Guides agents through authoring, validating, importing, and packing `Level Document`s (`.sfxlevel`) and staging runtime assets (`.map`, `.tab`, `.bin`, `.pal`) into Game Pak RAM Bank `$71`.

---

## 1. Domain & Format Invariants

1. **Symbolic Texture Binding**:
   - `.sfxlevel` stores symbolic descriptor names (`topTex`, `skirtTex`, `panelTex`) resolved against a shared `Atlas Document` (`atlasPath`). Repacking or reordering the atlas updates exported `.map` indices without invalidating `.sfxlevel` sources.
2. **Grid Bounds & Binary `.map` Layout**:
   - Dimensions: `cols` and `rows` each in `4..16` (up to `256` cells; max `2,056` bytes).
   - 8-byte `map_header` (`cols`, `rows`, `origin_x = -(cols * 16)`, `origin_z = -(rows * 16)`) followed by row-major 8-byte `map_cell` records (`base_height`, `archetype_id` = `(rot << 6) | prim`, `top_tex`, `skirt_tex`, `panel_tex`, `panel_flags`, `reserved`, `reserved`).
3. **12 Shape Archetypes (`0..11`)**:
   - `PRIM_FLAT` (`0`), `PRIM_WEDGE_STRAIGHT` (`1`), `PRIM_WEDGE_STRAIGHT_2H` (`2`), `PRIM_WEDGE_OUTER` (`3`), `PRIM_WEDGE_OUTER_2H` (`4`), `PRIM_WEDGE_INNER` (`5`), `PRIM_WEDGE_INNER_2H` (`6`), `PRIM_STAIRS` (`7`), `PRIM_LIQUID_SHALLOW` (`8`), `PRIM_LIQUID_DEEP` (`9`), `PRIM_PILLAR` (`10`), `PRIM_FOLIAGE_CROSS` (`11`).
   - Rotations: `0`, `90`, `180`, `270` degrees clockwise. Edge panels: `N`, `E`, `S`, `W` plus optional `tall_2h` (`2h` height).
4. **Bank `$71` Asset Staging Budget (`ROM_EXPRAMSIZE = $07`)**:
   - `$71:0000–$71:7FFF` (`32 KB` max): `.bin` Texture Atlas (`0`-based offset == `$71` address).
   - `$71:8000–$71:8807`: `.tab` Texture Table (`magic = $5453`, `entry_count`, `atlas_width`, `stride_shift`, 8-byte entries).
   - `$71:8808–$71:900F`: `.map` Map Grid.
   - `$71:9010–$71:902F`: `.pal` 16-color BGR555 palette mirror (DMA'd to CGRAM `0`).

---

## 2. Primary Tool: `sfx_manage_level` (`retro-superfx_studio`)

Invoke **`call_mcp_tool`** directly on `retro-superfx_studio` (or CLI `./sfx-studio/scripts/sfx-studio.sh level ...` if the live MCP server has not yet reloaded a newly built tool):

### Create a Level
```json
{
  "ServerName": "retro-superfx_studio",
  "ToolName": "sfx_manage_level",
  "Arguments": {
    "action": "create",
    "level_path": "snes-sfx-demo/X-GSU/RamMapDemo/Data/courtyard.sfxlevel",
    "atlas_path": "snes-sfx-demo/X-GSU/RamMapDemo/Data/COURTYARD_ATLAS.sfxproj",
    "cols": 8,
    "rows": 8,
    "default_top_tex": "grass",
    "default_skirt_tex": "stone"
  }
}
```

### Patch Cells or Rectangular Regions (`set_cells`)
```json
{
  "ServerName": "retro-superfx_studio",
  "ToolName": "sfx_manage_level",
  "Arguments": {
    "action": "set_cells",
    "level_path": "snes-sfx-demo/X-GSU/RamMapDemo/Data/courtyard.sfxlevel",
    "cells": [
      { "col_start": 2, "col_end": 5, "row_start": 2, "row_end": 5, "base_height": 2, "top_tex": "stone" },
      { "col": 3, "row": 1, "base_height": 0, "archetype": "PRIM_STAIRS", "rotation": 180 },
      { "col": 2, "row": 2, "panel_tex": "parapet", "panels": ["N", "W"], "tall_2h": false }
    ]
  }
}
```

### Validate, Pack, or Import Legacy ca65 Maps
- **Validate**: `{"action": "validate", "level_path": "..."}`
- **Pack** (exports `.map`, constants-only `.i`, and software `_preview.png`): `{"action": "pack", "level_path": "...", "yaw": 15}`
- **Import `def_cell` `.i`**: `{"action": "import_ca65", "ca65_path": "...", "atlas_path": "...", "level_path": "..."}`

### Headless CLI Equivalent
```bash
./sfx-studio/scripts/sfx-studio.sh level create <level.sfxlevel> --atlas <atlas.sfxproj> --cols 8 --rows 8
./sfx-studio/scripts/sfx-studio.sh level validate <level.sfxlevel>
./sfx-studio/scripts/sfx-studio.sh level pack <level.sfxlevel> --yaw 15
./sfx-studio/scripts/sfx-studio.sh level import-ca65 <input.i> --atlas <atlas.sfxproj> --output <level.sfxlevel>
```

---

## 3. Verification Gate

1. **Pack Shared Atlas First**: Ensure `<ATLAS>.tab`, `<ATLAS>.bin`, and `<ATLAS>.pal` are up to date via `sfx_pack_textures` (`action: "pack"`).
2. **Validate & Visually Inspect Software Preview**: Confirm `valid == true` and call `read_file` on `<LEVEL>_preview.png` to inspect geometry, UV alignment, liquid flushness, and panels.
3. **Mesen Hardware Verification** (when wired into a ROM): Rebuild the target ROM (`make clean all`), run `capture_sfx_render` on `retro-superfx_mesen`, and call `read_file` on the captured `.png`.
