# SuperFX Asset & Level Studio Plugin

An Antigravity plugin bundling tools, skills, and the headless MCP server for the SuperFX (GSU / FX3) Asset & Level Studio (`sfx-studio`).

## Features

### MCP Server & Tools
1. **`sfx-studio_studio`** (configured as `studio` in `mcp_config.json` via `./scripts/run-mcp.sh`):
   - `sfx_pack_textures`: Packs, validates, and imports graphics for SuperFX Texture Atlases (`.sfxproj` / `.sfxatlas`). Exports unpacked 1-byte-per-texel `.bin`, SNES BGR555 `.pal`, relocatable `.tab`, ca65 `.i` assembly index, and layout `.png`.
   - `sfx_manage_level`: Creates, patches cells/regions, validates, imports from ca65 `def_cell` `.i`, and packs isometric Level Documents (`.sfxlevel`), exporting `.map`, constants-only `.i`, and software `_preview.png`.

### Canonical Skills
Canonical skills packaged directly with this project:
- **`texture-atlas`**: Author, pack, update, and validate SuperFX Texture Atlases (`.sfxproj`) for 3D polygon rasterization and `IsoEngine` dioramas. Documented in `../../skills/texture-atlas/SKILL.md`.
- **`iso-level`**: Author, edit, import, validate, and pack SuperFX isometric Level Documents (`.sfxlevel`) and Bank-`$71` runtime maps (`.map`). Documented in `../../skills/iso-level/SKILL.md`.

## Standalone Usage
When loaded standalone outside the `retro-superfx` umbrella workspace, invoke `sfx_pack_textures` via `call_mcp_tool` with `"ServerName": "sfx-studio_studio"`.
