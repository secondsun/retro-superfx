## Problem Statement

`IsoEngine` renders an isometric 3D scene of 18 prisms (72 vertices, 44 visible faces, including textured terrain blocks and untextured cached trapezoids) across two 128×160 `Frame Half` passes per full 256×160 frame. Currently, the engine suffers from a very low framerate (~5–8 FPS, taking 6–10+ VBlanks per full frame) due to five compounding bottlenecks across the `GSU` pipeline and 65816 host harness:

1. **Catastrophic Per-Pixel Overhead in `draw_textured_triangle` (~150+ cycles/pixel)**:
   - For every single texel plotted, `draw_textured_triangle` executes a full `call plot_texel` subroutine invocation (incurring caller-save stack pushes/pops and jumping out of any instruction cache), recomputes the horizontal interpolation factor `f = (x - xMin) * recip_dx` from scratch using 16×16 multiplies (`lmult` and two `fmult`s), reloads 6 global variables (`TRI_U_LEFT`, `TRI_V_LEFT`, `TRI_DU`, `TRI_DV`, `TRI_ATLAS_OFFSET`, `TRI_SHADE_OFFSET`) from `GSURAM`, and stalls on synchronous ROM byte fetches (`r14` -> `getb`).
   - Additionally, on every scanline of a triangle, `draw_textured_triangle` and `draw_triangle` test all 3 triangle edges and perform ROM reciprocal table lookups (`call reciprocal_lookup_rom`) to recompute `(y - yA) * recip_dy` instead of stepping precomputed Q8.8 edge slopes (`dx/dy`, `du/dy`, `dv/dy`) incrementally.
2. **Missing Active `Frame Half` Horizontal Culling & Redundant `rpix` Flushes**:
   - `draw_triangle` and `draw_textured_triangle` only cull triangles against `[0, 256)` rather than the active 128-pixel `Frame Half` window (`[0, 127]` on Pass 0, `[128, 255]` on Pass 1). A textured triangle located entirely in the right half still runs its full scanline setup, edge interpolation, and UV reciprocal lookups during Pass 0 only to clamp to zero pixels on each scanline.
   - `draw_cached_trap` executes `rpix; rpix` at the end of *every single trapezoid* (~80 times per frame) instead of once at the end of the pass before `stop`, and uses `O(N)` iterative loops (`@skip_loop`, `@clip_u_loop`) for top/left clipping instead of constant-time multiplies.
3. **Unconditional Per-Frame Geometry Projection, Sorting, and Backface Culling**:
   - On Pass 0 of every frame, `SuperFX.sgs` unconditionally re-projects all 72 vertices (`iso_camera_project_mesh`), re-sorts objects, and re-culls all 44 faces—even on the >95% of frames where the `Isometric Camera` has not rotated or panned, and even when the camera only translated via D-pad (which shifts screen-space `(X, Y)` uniformly without changing depths, visibility, or `Scanline Trapezoid` widths/slopes).
   - Furthermore, `iso_camera_project_mesh` calls `iso_camera_project_vertex` 72 times in a loop, reloading camera yaw/pitch indices and performing ROM sine/cosine lookups 72 times for the exact same camera angles.
4. **Disabled `GSU` High-Speed Multiply Mode (`MS0`)**:
   - `initGSU_4bpp_160` configures `GSU_CFGR` as `%10000000` (IRQ masked, standard-speed multiply) instead of `%10100000` (bit 5 `MS0 = 1`, high-speed multiply), causing every `mult`, `umult`, `fmult`, and `lmult` in vertex projection and rasterization to take nearly twice as many cycles.
5. **Premature `INIDISP` Forced Blank in 65816 `Vblank` Handler**:
   - The NMI `Vblank` handler sets `RW_INIDISP` to `$80` (forced blank) *before* checking `gsuRunning`. Whenever the `GSU` takes more than one VBlank to finish a `Frame Half`, the screen is blanked during active display or left in forced blank without a DMA transfer, causing screen flicker and wasted VBlank state changes.

## Solution

Optimize `IsoEngine` across five coordinated improvements:
1. **Cache-Resident Incremental Affine Texture Rasterization**: Replace the per-pixel `plot_texel` call and per-pixel `lmult`/`fmult` interpolation with an incremental affine inner loop inside the 512-byte `GSU` instruction cache (`cache`), stepping `u += du_dx` and `v += dv_dx` per pixel and pipelining ROM `Texel` fetches (`r14` address update interleaved before `getc` / `getb` and `plot`). Route textured quad faces through `compile_face_traps` and `draw_cached_trap` (which already supports textured `Scanline Trapezoid` descriptors) or pre-split triangles into top/bottom trapezoids with precomputed Q8.8 slopes.
2. **Active `Frame Half` Culling & `draw_cached_trap` Tightening**: Cull triangles and faces against `[pass_x_offset, pass_x_offset + 128)` prior to scanline setup, remove the redundant `rpix; rpix` from `draw_cached_trap`, and replace `@skip_loop` and `@clip_u_loop` in `draw_cached_trap` with constant-time `fmult`/`mult` adjustments.
3. **Camera Dirty-Flag Caching & Batched Mesh Projection**: Track camera rotation and translation dirty state across frames so Pass 0 skips `iso_camera_project_mesh`, depth sorting, and `compile_face_traps` when the `Isometric Camera` is unchanged; apply a fast 2D screen-space `(dx, dy)` offset when the camera only translates (`move` without `rotate`); and inline `iso_camera_project_vertex` into `iso_camera_project_mesh` with trig values (`sin_yaw`, `cos_yaw`, `sin_pitch`, `cos_pitch`) fetched from ROM once before the 72-vertex loop.
4. **Enable `GSU` High-Speed Multiply (`MS0`)**: Set `GSU_CFGR` to `%10100000` in `initGSU_4bpp_160`.
5. **Fix `Vblank` `INIDISP` Ordering**: Check `gsuRunning` at the very start of `Vblank` before touching `INIDISP`, so forced blank is only engaged when the `GSU` has actually finished a `Frame Half` and a DMA transfer is ready to run.

## User Stories

1. As a player / viewer of `IsoEngine`, I want the textured and untextured isometric scene to render at a smooth, flicker-free framerate without black-screen strobing when the `GSU` spans multiple VBlanks.
2. As an engine developer, I want textured faces to rasterize inside a `cache`-resident inner pixel loop using incremental `u += du_dx` and `v += dv_dx` stepping with pipelined `r14` ROM prefetching, so that per-pixel cost drops from ~150 cycles to ~14–18 cycles.
3. As an engine developer, I want `draw_triangle` and `draw_textured_triangle` (and/or textured quad face compilation into `Scanline Trapezoid` records) to pre-sort vertices by `Y`, compute edge slopes (`dx/dy`, `du/dy`, `dv/dy`) once per triangle, and cull against the active 128-pixel `Frame Half` bounds `[pass_x_offset, pass_x_offset + 128)` before entering the scanline loop, so that off-half primitives cost zero scanline work.
4. As an engine developer, I want `draw_cached_trap` to omit its per-trapezoid `rpix; rpix` flush (relying on the pass-end `rpix; rpix` before `stop`), and to compute top-clipping (`y < 0`) and left-clipping (`xL < 0`) via multiplication rather than `O(N)` loops, so that clipped trapezoids and cached trapezoid exits execute in constant time.
5. As an engine developer, I want Pass 0 to skip vertex projection, object depth sorting, backface culling, and `compile_face_traps` whenever the `Isometric Camera` state (`yaw`, `pitch`, `origin_x`, `origin_y`, `origin_z`) has not changed since the previous frame, so that static frames bypass all geometry setup on Pass 0 just like Pass 1.
6. As an engine developer, when the user pans the `Isometric Camera` with the D-pad (`origin_x` / `origin_y` changed, `yaw` and `pitch` unchanged), I want Pass 0 to translate the cached `Projected Vertex Cache` (`MODEL_V2`) and cached trapezoid `xL`/`y_top` coordinates by the screen-space delta `(delta_sx, delta_sy)` without re-running 3D rotation, depth sorting, backface culling, or slope compilation, so that camera panning is nearly as fast as a static camera.
7. As an engine developer, when the `Isometric Camera` rotates (`yaw` or `pitch` changed), I want `iso_camera_project_mesh` to look up `sin(yaw)`, `cos(yaw)`, `sin(pitch)`, and `cos(pitch)` from ROM once per frame and project all 72 vertices inside a single `cache`-resident hardware `loop` without per-vertex `call` overhead, so that rotation frames project vertices in ~2,500 cycles instead of ~15,000+ cycles.
8. As an engine developer, I want `initGSU_4bpp_160` to enable `GSU_CFGR` bit 5 (`MS0 = 1`, `$A0`), so that all hardware multiplications run at high speed.
9. As an engine developer, I want all existing headless visual and mathematical tests for `IsoEngine`, `draw_cached_trap`, `draw_textured_triangle`, and `iso_camera` to continue passing with pixel-accurate or perceptually equivalent output while demonstrating a verified cycle reduction in `MesenMCP`.

## Implementation Decisions

### 1. Cache-Resident Incremental Texture Rasterizer & Unified Trapezoid Path
- **Option A (Preferred for Quad Faces)**: Extend the scene rendering dispatch in `SuperFX.sgs` so that textured quad faces (terrain blocks with `Atlas Index` / UV mappings) compile into textured `Scanline Trapezoid` descriptors via `compile_face_traps` (which already allocates and populates the 24-byte `TRAP_*` descriptor including `U_L`, `V_L`, `DU_DY`, `DV_DY`, `DU_DX`, `DV_DX`, `ATLAS`, and `SHADE`) or reuse incremental scanline trapezoid stepping across both Pass 0 and Pass 1.
- **Optimize the Textured Inner Loop in `draw_cached_trap` and `draw_textured_triangle`**:
  - Keep `u` (Q8.8), `v` (Q8.8), `du_dx` (Q8.8), `dv_dx` (Q8.8), `combined_offset` (`(atlas_offset << 8) | shade_offset`), and a tile-row base register in physical `GSU` registers across the horizontal span `loop`, eliminating all `ldw` reloads from `GSURAM` inside the pixel loop.
  - Eliminate `call plot_texel` and per-pixel `lmult`/`fmult`. Instead, at the start of each scanline (when not pre-computed in a `Scanline Trapezoid`), compute `du_dx = (uR - uL) * recip_dx` and `dv_dx = (vR - vL) * recip_dx` once per scanline (or once per trapezoid for parallelogram faces), and step `u += du_dx`, `v += dv_dx` per pixel inside a `cache`-resident `loop`.
  - **Pipeline `r14` ROM Prefetch**: Compute the initial texel ROM address into `r14` before entering the `loop`. Inside the `loop`, advance `u` and `v`, compute the *next* pixel's `r14` address while the `GSU` ROM buffer finishes fetching the *current* pixel's byte, then execute `getc; plot` (or `getb` + shade offset + `color; plot`), hiding the 5-cycle ROM fetch latency behind the UV arithmetic.

### 2. Active `Frame Half` Culling & Constant-Time Clipping in `draw_cached_trap`
- In `draw_triangle` and `draw_textured_triangle`:
  - Before scanline setup, compute `minX = min(x1, x2, x3)` and `maxX = max(x1, x2, x3)` relative to `pass_x_offset` (`minX - pass_x_offset`, `maxX - pass_x_offset`). If `maxX_rel < 0` or `minX_rel >= 128`, return immediately.
  - Pre-sort the 3 vertices by `Y` (`y0 <= y1 <= y2`) once per triangle and precompute the 3 Q8.8 edge slopes (`dx02`, `dx01`, `dx12`) using `reciprocal_lookup_rom` at triangle setup (at most 3 lookups per triangle instead of 2–3 lookups per scanline).
- In `draw_cached_trap`:
  - Remove the `rpix; rpix` instructions before `@done` (leaving only the pass-final `rpix; rpix` in `SuperFX.sgs` before the 8 settling `nop`s and `stop`).
  - Replace the `@skip_loop` (when `y_top < 0`) with direct multiplications (`skip = -y_top`; `xL += dxL * skip`, `xR += dxR * skip`, `uL += du_dy * skip`, `vL += dv_dy * skip`).
  - Replace the `@clip_u_loop` (when `xL_rel < 0`) with direct multiplications (`clip = -xL_rel`; `u += du_dx * clip`, `v += dv_dx * clip`).

### 3. Camera Dirty State & Batched `cache`-Resident Mesh Projection
- In `gsu_iso_camera.i` and `SuperFX.sgs`:
  - Maintain previous-frame camera state in `GSURAM` (`PREV_CAM_YAW`, `PREV_CAM_PITCH`, `PREV_CAM_OX`, `PREV_CAM_OY`, `PREV_CAM_OZ`) alongside a dirty mode computed on Pass 0 after `update_camera`:
    - **`CAM_CLEAN` (0)**: Camera angles and origin are unchanged (and `GSU_INIT_FLAG != 0`). Pass 0 skips `iso_camera_project_mesh`, `sort_objects`, `cull_faces`, and `compile_face_traps`, jumping straight to drawing from the existing `Projected Vertex Cache` and `Scanline Trapezoid` cache.
    - **`CAM_ROTATED` or `CAM_MOVED` (dirty)**: When camera state changes, run the batched `iso_camera_project_mesh`. (If only `origin_x`/`origin_y`/`origin_z` changed while `yaw` and `pitch` are unchanged, `sort_objects` and `cull_faces` can still be skipped because orthographic isometric face normals and relative object depths depend only on `yaw` and `pitch`!)
  - Refactor `iso_camera_project_mesh` so that it loads `sin(yaw)`, `cos(yaw)`, `sin(pitch)`, and `cos(pitch)` from `sin_table` / `cos_table` **once** before the vertex loop, enters `cache`, and loops over all `N` vertices (`r12 = count`) inline without calling `iso_camera_project_vertex`.

### 4. Hardware Configuration (`GSU_CFGR`) & `Vblank` Synchronization
- In `CpuMacros.s` (`initGSU_4bpp_160`), change the `GSU_CFGR` initialization value from `#%10000000` to `#%10100000` (`$A0`, IRQ masked + high-speed multiply `MS0 = 1`), matching the standard `libSFX` `GSU_CFGR_DEFAULT` configuration and ADR 0003.
- In `SuperFX.s` (`Vblank`), check `lda gsuRunning; bne @skip_vblank` *before* writing `#$80` to `RW_INIDISP`. Only enter forced blank when `gsuRunning == 0` (meaning the `GSU` has completed the current `Frame Half` and VRAM DMA is ready to execute).

## Testing Decisions

### What Makes a Good Test
- Tests must verify both mathematical/visual equivalence (no corrupted textures, broken seams at the `x = 128` `Frame Half` boundary, or missing faces) and measurable cycle/VBlank reduction in headless Mesen CE.

### Modules & Seams to Test
- **Subroutine unit test seam (`run_sfx_test` & `assert_sfx_math`)**:
  - `iso_camera` unit test in `snes-sfx-demo/X-GSU/tests/iso_camera/`: Verify that the batched `iso_camera_project_mesh` produces identical `(screen_x, screen_y)` coordinates in the `Projected Vertex Cache` as single-vertex projection across multiple `yaw`, `pitch`, and `origin` combinations.
  - `draw_cached_trap` / `draw_textured_triangle` unit tests: Verify that clipped and unclipped textured and flat primitives produce expected pixel output in the column-major 4bpp framebuffer.
- **Full-frame visual & performance seam (`capture_sfx_render` & `profile_sfx_execution`)**:
  - Capture the rendered 256×160 `IsoEngine` framebuffer before and after optimizations using `capture_sfx_render` and verify visual parity (no missing faces, intact `Cell Skirt` occlusion, and clean `Frame Half` stitch at `x = 128`).
  - Measure Pass 0 and Pass 1 `GSU` cycle counts and VBlanks per frame on both static camera frames and rotating camera frames to confirm the framerate increase.

### Prior Art
- Follow the existing test harnesses in `snes-sfx-demo/X-GSU/tests/iso_camera/`, `snes-sfx-demo/X-GSU/tests/compile_face_traps/`, and `snes-sfx-demo/X-GSU/IsoEngine/test_controller.lua`.

## Out of Scope

- Changing the map format (`MAP_FORMAT.md`) or altering the artistic layout of the 18-prism demo scene.
- Switching from 4bpp (`160px` tall) mode to 2bpp or 8bpp screen modes.
- Perspective division (`1/z`) during vertex projection (the engine uses an analytical orthographic `Isometric Camera` per ADR 0003).

## Further Notes

- Preserves all `FX3` hardware invariants required by `AGENTS.md`: no `MERGE` instruction, word-aligned `ldw`/`stw` accesses in `GSURAM`, dual `rpix; rpix` followed by 8 settling `nop`s before `stop`, and `romb` + `r14` + `getb`/`getc` for ROM table and `Texture Atlas` reads.

