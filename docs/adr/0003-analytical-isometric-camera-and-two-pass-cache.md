# 0003. Analytical Isometric Camera Projection and Two-Pass 2D Vertex Cache

Date: 2026-10-18

## Status

Accepted

## Context

The `IsoEngine` SuperFX 3D renderer targets a Final Fantasy Tactics–style isometric/dimetric camera: a fixed downward elevation pitch ($\phi = 30^\circ$) that orbits horizontally (yaw $\theta$) around a target cell on the game board.

Previously, `X-GSU/gsu_maths` provided a general-purpose `camera_lookAt` routine (`gsu_camera.i`) that constructs a $4 \times 4$ view matrix at runtime using 32-bit square roots (`gsu_sqrt32`), reciprocals (`reciprocal`), vector normalization, and cross products, followed by `vector3_transform` (`gsu_vector.i`) performing nine 16-bit fixed-point `lmult` operations (`condensed_lmult`) per vertex on an unindexed 36-vertex cube soup.

Additionally, because SNES VRAM bandwidth and GSU framebuffer layout split the $256 \times 160$ screen into two vertical $128 \times 160$ frame-half passes (`GSU_FRAME_HALF = 0` for $X \in [0, 127]$ and `GSU_FRAME_HALF = 1` for $X \in [128, 255]$), running 3D vertex transformations and culling on both passes would duplicate all 3D geometry work every frame.

## Decision

1. **Analytical $30^\circ$ Orbital-Isometric Camera (`gsu_iso_camera.i`)**:
   - Bypass `camera_lookAt` and $4 \times 4$ matrix multiplication for isometric scenes.
   - Store only the camera yaw angle index, cached $\sin\theta$ and $\cos\theta$ (in Q2.14 from `gsu_trig.i`), and 2D screen center `(center_x, center_y)` in an `iso_camera` struct.
   - Compute 2D screen coordinates directly from Q8.8 3D coordinates $(x, y, z)$ using hardware `fmult` (Q8.8 $\times$ Q2.14 $\to$ Q10.6 in `r0` without touching `r4`), shifted left twice (`add r0; add r0`) and sign-extended (`hib; sex`) to integer pixels:
     $$X_{\text{screen}} = X_{\text{center}} + \text{int}(x \cos\theta - z \sin\theta)$$
     $$Y_{\text{screen}} = Y_{\text{center}} + \frac{\text{int}(x \sin\theta + z \cos\theta)}{2} - \text{int}(y \cos 30^\circ)$$
     where the $\sin 30^\circ = 0.5$ depth foreshortening is a single-cycle `asr` and $\cos 30^\circ \approx 0.8660$ uses `$376D` in Q2.14.

2. **Indexed Mesh & Per-Quad Winding Backface Culling**:
   - Represent the cube as 8 unique 3D vertices and 5 quad faces (omitting the permanently hidden bottom face), reducing per-frame vertex projections from 36 to 8.
   - Perform hidden-surface removal via a 2D screen-space cross-product winding check ($\text{cross} = (x_1 - x_0)(y_2 - y_0) - (y_1 - y_0)(x_2 - x_0) > 0$) once per quad face using single-cycle 8-bit signed `mult` instructions.

3. **Pass-0 Projection and Two-Pass 2D Draw Cache**:
   - Execute camera yaw updates, 3D vertex projection (`Projected Vertex Cache`), and quad backface culling exclusively during Pass 0 (`GSU_FRAME_HALF == 0`), emitting visible 2D screen-space triangles into a `GSURAM` draw list.
   - During Pass 1 (`GSU_FRAME_HALF == 1`), skip all 3D math and directly rasterize the cached 2D triangles with the $-128$ horizontal pass offset.

## Consequences

- **Positive**:
  - Eliminates runtime `sqrt`, `reciprocal`, vector normalization, and $4 \times 4$ matrix multiplication from the isometric frame loop.
  - Reduces vertex transformations per cube by $4.5\times$ (8 unique vertices vs. 36 unindexed triangle vertices) and replaces multi-instruction `condensed_lmult` sequences with single-instruction `fmult` calls.
  - Halves 3D geometry and culling cost across the two-pass $256 \times 160$ screen architecture.
- **Negative / Trade-offs**:
  - Specialized to orthographic fixed-pitch ($\phi = 30^\circ$) orbital cameras; scenes requiring free 3D pitch/roll or perspective projection must still use `camera_lookAt` and perspective division.
  - Requires a small `GSURAM` buffer to hold the projected 2D vertices (`8 * 4 = 32` bytes) and cached visible 2D triangles between Pass 0 and Pass 1.

