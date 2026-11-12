package dev.secondsun.sfxstudio.core.level

import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.atlas.TextureDescriptorInfo
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

data class IntPoint(
    val x: Int,
    val y: Int,
)

data class IsoBasis(
    val sxX: Int,
    val sxY: Int,
    val szX: Int,
    val szY: Int,
)

data class IsoRenderResult(
    val image: BufferedImage,
    val cellPickBuffer: IntArray,
    val cellFloorPolygons: List<List<IntPoint>>,
    val width: Int,
    val height: Int,
    val originX: Int,
    val originZ: Int,
) {
    fun pickCellIndex(
        x: Int,
        y: Int,
    ): Int {
        if (x !in 0 until width || y !in 0 until height) return -1
        val direct = cellPickBuffer[y * width + x]
        if (direct >= 0) return direct

        // Fallback: check floor polygons front-to-back if pixel was transparent
        for (idx in cellFloorPolygons.indices.reversed()) {
            if (pointInQuad(x, y, cellFloorPolygons[idx])) {
                return idx
            }
        }
        return -1
    }

    private fun pointInQuad(
        px: Int,
        py: Int,
        quad: List<IntPoint>,
    ): Boolean {
        if (quad.size < 4) return false
        return pointInTriangle(px, py, quad[0], quad[1], quad[2]) ||
            pointInTriangle(px, py, quad[0], quad[2], quad[3])
    }

    private fun pointInTriangle(
        px: Int,
        py: Int,
        a: IntPoint,
        b: IntPoint,
        c: IntPoint,
    ): Boolean {
        val d1 = sign(px, py, a, b)
        val d2 = sign(px, py, b, c)
        val d3 = sign(px, py, c, a)
        val hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0)
        val hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0)
        return !(hasNeg && hasPos)
    }

    private fun sign(
        px: Int,
        py: Int,
        p2: IntPoint,
        p3: IntPoint,
    ): Long = (px - p3.x).toLong() * (p2.y - p3.y).toLong() - (p2.x - p3.x).toLong() * (py - p3.y).toLong()
}

/**
 * Software reference rasterizer implementing IsoEngine's exact 30-degree orthographic
 * lattice projection (`LATTICE_V2`), `mg_cover_round` basis vectors, 5-step intra-cell
 * painter's order, half-open trapezoid intervals, and world-space UV wrapping.
 *
 * Powers both headless `<LEVEL>_preview.png` exports and the interactive Compose viewport.
 */
object IsoSoftwareRenderer {
    const val DEFAULT_WIDTH = 256
    const val DEFAULT_HEIGHT = 160
    const val STEP_1H_PIXELS = 14

    private const val UV_MODE_HORIZONTAL = 0
    private const val UV_MODE_WALL_NS = 1
    private const val UV_MODE_WALL_EW = 2
    private const val UV_MODE_SLOPE = 3

    private const val SLOT_TOP = 0
    private const val SLOT_SKIRT = 1
    private const val SLOT_PANEL = 2

    // Exact 121-entry Q2.14 sine table (0..120, 3 degrees per step) from gsu_trig.i
    private val SIN_TABLE_Q14 =
        intArrayOf(
            0x0000,
            0x0359,
            0x06B1,
            0x0A03,
            0x0D4E,
            0x1090,
            0x13C7,
            0x16F0,
            0x1A08,
            0x1D0E,
            0x2000,
            0x22DB,
            0x259E,
            0x2847,
            0x2AD3,
            0x2D41,
            0x2F90,
            0x31BD,
            0x33C7,
            0x35AD,
            0x376D,
            0x3906,
            0x3A78,
            0x3BC0,
            0x3CDE,
            0x3DD2,
            0x3E9A,
            0x3F36,
            0x3FA6,
            0x3FEA,
            0x4000,
            0x3FEA,
            0x3FA6,
            0x3F36,
            0x3E9A,
            0x3DD2,
            0x3CDE,
            0x3BC0,
            0x3A78,
            0x3906,
            0x376D,
            0x35AD,
            0x33C7,
            0x31BD,
            0x2F90,
            0x2D41,
            0x2AD3,
            0x2847,
            0x259E,
            0x22DB,
            0x2000,
            0x1D0E,
            0x1A08,
            0x16F0,
            0x13C7,
            0x1090,
            0x0D4E,
            0x0A03,
            0x06B1,
            0x0359,
            0x0000,
            -0x0359,
            -0x06B1,
            -0x0A03,
            -0x0D4E,
            -0x1090,
            -0x13C7,
            -0x16F0,
            -0x1A08,
            -0x1D0E,
            -0x2000,
            -0x22DB,
            -0x259E,
            -0x2847,
            -0x2AD3,
            -0x2D41,
            -0x2F90,
            -0x31BD,
            -0x33C7,
            -0x35AD,
            -0x376D,
            -0x3906,
            -0x3A78,
            -0x3BC0,
            -0x3CDE,
            -0x3DD2,
            -0x3E9A,
            -0x3F36,
            -0x3FA6,
            -0x3FEA,
            -0x4000,
            -0x3FEA,
            -0x3FA6,
            -0x3F36,
            -0x3E9A,
            -0x3DD2,
            -0x3CDE,
            -0x3BC0,
            -0x3A78,
            -0x3906,
            -0x376D,
            -0x35AD,
            -0x33C7,
            -0x31BD,
            -0x2F90,
            -0x2D41,
            -0x2AD3,
            -0x2847,
            -0x259E,
            -0x22DB,
            -0x2000,
            -0x1D0E,
            -0x1A08,
            -0x16F0,
            -0x13C7,
            -0x1090,
            -0x0D4E,
            -0x0A03,
            -0x06B1,
            -0x0359,
            0x0000,
        )

    private data class VertexUV(
        val x: Int,
        val y: Int,
        val uQ8: Int,
        val vQ8: Int,
    )

    private data class CompiledTrap(
        val dyStart: Int,
        val lineCount: Int,
        val xLStartQ8: Int,
        val xRStartQ8: Int,
        val dxLDyQ8: Int,
        val dxRDyQ8: Int,
        val uLStartQ8: Int,
        val vLStartQ8: Int,
        val duDyQ8: Int,
        val dvDyQ8: Int,
        val duDxQ8: Int,
        val dvDxQ8: Int,
        val texSlot: Int,
    )

    fun sinQ14(yawIndex: Int): Int {
        val norm = ((yawIndex % 120) + 120) % 120
        return SIN_TABLE_Q14[norm]
    }

    fun cosQ14(yawIndex: Int): Int {
        val norm = (((yawIndex + 30) % 120) + 120) % 120
        return SIN_TABLE_Q14[norm]
    }

    fun computeBasis(yawIndex: Int): IsoBasis {
        val sin = sinQ14(yawIndex)
        val cos = cosQ14(yawIndex)
        return IsoBasis(
            sxX = coverRound(cos, shift = 9, negate = false),
            sxY = coverRound(sin, shift = 10, negate = false),
            szX = coverRound(sin, shift = 9, negate = true),
            szY = coverRound(cos, shift = 10, negate = false),
        )
    }

    private fun coverRound(
        src: Int,
        shift: Int,
        negate: Boolean,
    ): Int {
        val bias = (1 shl shift) - 1
        val rounded =
            if (src < 0) {
                -((-src + bias) ushr shift)
            } else {
                (src + bias) ushr shift
            }
        return if (negate) -rounded else rounded
    }

    fun populateLattice(basis: IsoBasis): Array<IntPoint> {
        val sxX = basis.sxX
        val sxY = basis.sxY
        val szX = basis.szX
        val szY = basis.szY
        val c2X = sxX + szX
        val c2Y = sxY + szY

        val sx2X = sxX shr 1
        val sx2Y = sxY shr 1
        val sz2X = szX shr 1
        val sz2Y = szY shr 1

        val sx4X = sx2X shr 1
        val sx4Y = sx2Y shr 1
        val sz4X = sz2X shr 1
        val sz4Y = sz2Y shr 1

        val p0X = sx4X + sz4X
        val p0Y = sx4Y + sz4Y
        val p1X = p0X + sx2X
        val p1Y = p0Y + sx2Y
        val p2X = p1X + sz2X
        val p2Y = p1Y + sz2Y
        val p3X = p0X + sz2X
        val p3Y = p0Y + sz2Y

        return arrayOf(
            // 0..3: Base corners C0..C3 (y = 0)
            IntPoint(0, 0),
            IntPoint(sxX, sxY),
            IntPoint(c2X, c2Y),
            IntPoint(szX, szY),
            // 4..7: Skirt bottom corners at -1h (+14px)
            IntPoint(0, STEP_1H_PIXELS),
            IntPoint(sxX, sxY + STEP_1H_PIXELS),
            IntPoint(c2X, c2Y + STEP_1H_PIXELS),
            IntPoint(szX, szY + STEP_1H_PIXELS),
            // 8..11: Elevated corners at +1h (-14px)
            IntPoint(0, -STEP_1H_PIXELS),
            IntPoint(sxX, sxY - STEP_1H_PIXELS),
            IntPoint(c2X, c2Y - STEP_1H_PIXELS),
            IntPoint(szX, szY - STEP_1H_PIXELS),
            // 12..15: Elevated corners at +2h (-28px)
            IntPoint(0, -2 * STEP_1H_PIXELS),
            IntPoint(sxX, sxY - 2 * STEP_1H_PIXELS),
            IntPoint(c2X, c2Y - 2 * STEP_1H_PIXELS),
            IntPoint(szX, szY - 2 * STEP_1H_PIXELS),
            // 16..19: Canonical edge midpoints MN, ME, MS, MW at y = 0
            IntPoint(sx2X, sx2Y),
            IntPoint(sxX + sz2X, sxY + sz2Y),
            IntPoint(sx2X + szX, sx2Y + szY),
            IntPoint(sz2X, sz2Y),
            // 20..23: Inset pillar base corners P0..P3
            IntPoint(p0X, p0Y),
            IntPoint(p1X, p1Y),
            IntPoint(p2X, p2Y),
            IntPoint(p3X, p3Y),
            // 24..27: Inset pillar top corners T0..T3 (+2h = -28px)
            IntPoint(p0X, p0Y - 2 * STEP_1H_PIXELS),
            IntPoint(p1X, p1Y - 2 * STEP_1H_PIXELS),
            IntPoint(p2X, p2Y - 2 * STEP_1H_PIXELS),
            IntPoint(p3X, p3Y - 2 * STEP_1H_PIXELS),
        )
    }

    fun computeCenteredOrigin(
        cols: Int,
        rows: Int,
        centerX: Int,
        centerY: Int,
        yawIndex: Int,
    ): IntPoint {
        val sin = sinQ14(yawIndex)
        val cos = cosQ14(yawIndex)
        val sxXQ5 = cos shr 4
        val sxYQ5 = sin shr 5
        val szXQ5 = -(sin shr 4)
        val szYQ5 = cos shr 5

        val offXQ5 = (cols * sxXQ5 + rows * szXQ5) shr 1
        val offYQ5 = (cols * sxYQ5 + rows * szYQ5) shr 1
        val offX = (offXQ5 + 16) shr 5
        val offY = (offYQ5 + 16) shr 5
        return IntPoint(centerX - offX, centerY - offY)
    }

    fun writePreviewPng(
        grid: MapGrid,
        atlas: TextureAtlas,
        palette: SnesPalette,
        destinationFile: File,
        yawIndex: Int = 15,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
    ) {
        // Auto-fit canvas if grid is larger than 8x8 so 12x12..16x16 previews never clip
        val scaleCanvas = maxOf(grid.cols, grid.rows)
        val targetW = if (scaleCanvas > 8) maxOf(width, scaleCanvas * 36) else width
        val targetH = if (scaleCanvas > 8) maxOf(height, scaleCanvas * 24 + 48) else height
        val result =
            render(
                grid = grid,
                atlas = atlas,
                palette = palette,
                yawIndex = yawIndex,
                width = targetW,
                height = targetH,
            )
        destinationFile.parentFile?.mkdirs()
        ImageIO.write(result.image, "PNG", destinationFile)
    }

    fun render(
        grid: MapGrid,
        atlas: TextureAtlas,
        palette: SnesPalette,
        yawIndex: Int = 15,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        panX: Int = 0,
        panY: Int = 0,
        backgroundArgb: Int = 0xFF141820.toInt(),
    ): IsoRenderResult {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val argbPixels = IntArray(width * height) { backgroundArgb }
        val pickBuffer = IntArray(width * height) { -1 }

        val sin = sinQ14(yawIndex)
        val cos = cosQ14(yawIndex)
        val basis = computeBasis(yawIndex)
        val lat = populateLattice(basis)

        // Elevated midpoints M1[0..3] (+1h) and M2[0..3] (+2h)
        val m1 = Array(4) { k -> IntPoint(lat[16 + k].x, lat[16 + k].y - STEP_1H_PIXELS) }
        val m2 = Array(4) { k -> IntPoint(lat[16 + k].x, lat[16 + k].y - 2 * STEP_1H_PIXELS) }
        val kBot = IntPoint(lat[16].x + lat[19].x, lat[16].y + lat[19].y)
        val kTop = IntPoint(kBot.x, kBot.y - 2 * STEP_1H_PIXELS)

        // Compile templates for current yaw
        val flatTraps = compileQuad(lat[0], lat[1], lat[2], lat[3], UV_MODE_HORIZONTAL, SLOT_TOP, 32, 32, sin, cos)
        val skirtNsTraps =
            if (cos >= 0) {
                compileQuad(lat[3], lat[2], lat[6], lat[7], UV_MODE_WALL_NS, SLOT_SKIRT, 32, 16, sin, cos)
            } else {
                compileQuad(lat[1], lat[0], lat[4], lat[5], UV_MODE_WALL_NS, SLOT_SKIRT, 32, 16, sin, cos)
            }
        val skirtEwTraps =
            if (sin >= 0) {
                compileQuad(lat[2], lat[1], lat[5], lat[6], UV_MODE_WALL_EW, SLOT_SKIRT, 32, 16, sin, cos)
            } else {
                compileQuad(lat[0], lat[3], lat[7], lat[4], UV_MODE_WALL_EW, SLOT_SKIRT, 32, 16, sin, cos)
            }

        val panelTraps = compileAllEdgePanels(lat, sin, cos)
        val archTable = compileAllArchetypes(lat, m1, m2, kBot, kTop, flatTraps, sin, cos)

        val atlasBin = atlas.generateBinary()
        val descriptors = atlas.resolvedDescriptors()
        val paletteArgb =
            IntArray(16) { slot ->
                if (slot == 0) 0 else palette[slot].argb
            }

        val centerYDefault = (height * 5) / 9
        val origin = computeCenteredOrigin(grid.cols, grid.rows, width / 2 + panX, centerYDefault + panY, yawIndex)

        val sxXQ5 = cos shr 4
        val sxYQ5 = sin shr 5
        val szXQ5 = -(sin shr 4)
        val szYQ5 = cos shr 5
        val originXQ5 = origin.x shl 5
        val originZQ5 = origin.y shl 5

        // Precompute floor polygons for all cells (for wireframe overlays & empty-cell picking)
        val floorPolygons =
            List(grid.cols * grid.rows) { idx ->
                val c = idx % grid.cols
                val r = idx / grid.cols
                val cell = grid.cells[idx]
                val anchorX = (originXQ5 + c * sxXQ5 + r * szXQ5 + 16) shr 5
                val anchorY = ((originZQ5 + c * sxYQ5 + r * szYQ5 + 16) shr 5) - cell.baseHeight * STEP_1H_PIXELS
                listOf(
                    IntPoint(anchorX + lat[0].x, anchorY + lat[0].y),
                    IntPoint(anchorX + lat[1].x, anchorY + lat[1].y),
                    IntPoint(anchorX + lat[2].x, anchorY + lat[2].y),
                    IntPoint(anchorX + lat[3].x, anchorY + lat[3].y),
                )
            }

        // Back-to-front painter's traversal determined by camera yaw signs:
        // cos >= 0 -> camera looks from South (j increases toward viewer: 0..rows-1)
        // sin >= 0 -> camera looks from East  (i increases toward viewer: 0..cols-1)
        val jRange = if (cos >= 0) 0 until grid.rows else (grid.rows - 1) downTo 0
        val iRange = if (sin >= 0) 0 until grid.cols else (grid.cols - 1) downTo 0

        val backNsEdge = if (cos >= 0) 0 else 2 // 0=N, 2=S
        val frontNsEdge = if (cos >= 0) 2 else 0
        val backEwEdge = if (sin >= 0) 3 else 1 // 3=W, 1=E
        val frontEwEdge = if (sin >= 0) 1 else 3

        for (row in jRange) {
            for (col in iRange) {
                val cellIdx = row * grid.cols + col
                val cell = grid.cells[cellIdx]

                val anchorX = (originXQ5 + col * sxXQ5 + row * szXQ5 + 16) shr 5
                val baseAnchorY = (originZQ5 + col * sxYQ5 + row * szYQ5 + 16) shr 5
                val cellAnchorY = baseAnchorY - cell.baseHeight * STEP_1H_PIXELS

                val topDesc = resolveDescSafe(cell.topTex, descriptors)
                val skirtDesc = resolveDescSafe(cell.skirtTex, descriptors) ?: topDesc
                val panelDesc = resolveDescSafe(cell.panelTex, descriptors)

                // Render-time skirt drops against camera-facing neighbors
                val neighborNsRow = if (cos >= 0) row + 1 else row - 1
                val neighborEwCol = if (sin >= 0) col + 1 else col - 1
                val dropNs = (cell.baseHeight - grid.baseHeightOrZero(col, neighborNsRow)).coerceAtLeast(0)
                val dropEw = (cell.baseHeight - grid.baseHeightOrZero(neighborEwCol, row)).coerceAtLeast(0)

                val uBaseWorld = col * 32
                val vBaseWorld = row * 32

                // Step 1: Cell Skirts
                if (skirtDesc != null) {
                    for (s in 0 until dropNs) {
                        val stepY = cellAnchorY + s * STEP_1H_PIXELS
                        val uOff = (uBaseWorld and skirtDesc.uMask) shl 8
                        val vOff = ((s * 16) and skirtDesc.vMask) shl 8
                        drawTraps(
                            skirtNsTraps,
                            anchorX,
                            stepY,
                            uOff,
                            vOff,
                            skirtDesc,
                            atlas.width,
                            atlasBin,
                            paletteArgb,
                            argbPixels,
                            pickBuffer,
                            width,
                            height,
                            cellIdx,
                        )
                    }
                    for (s in 0 until dropEw) {
                        val stepY = cellAnchorY + s * STEP_1H_PIXELS
                        val uOff = (vBaseWorld and skirtDesc.uMask) shl 8
                        val vOff = ((s * 16) and skirtDesc.vMask) shl 8
                        drawTraps(
                            skirtEwTraps,
                            anchorX,
                            stepY,
                            uOff,
                            vOff,
                            skirtDesc,
                            atlas.width,
                            atlasBin,
                            paletteArgb,
                            argbPixels,
                            pickBuffer,
                            width,
                            height,
                            cellIdx,
                        )
                    }
                }

                // Step 2: Back Edge Panels
                if (cell.panels.hasAny && panelDesc != null) {
                    val is2h = if (cell.panels.tall2h) 1 else 0
                    if (hasEdgePanel(cell.panels, backNsEdge)) {
                        drawTraps(
                            panelTraps[(backNsEdge shl 1) or is2h],
                            anchorX,
                            cellAnchorY,
                            0,
                            0,
                            panelDesc,
                            atlas.width,
                            atlasBin,
                            paletteArgb,
                            argbPixels,
                            pickBuffer,
                            width,
                            height,
                            cellIdx,
                        )
                    }
                    if (hasEdgePanel(cell.panels, backEwEdge)) {
                        drawTraps(
                            panelTraps[(backEwEdge shl 1) or is2h],
                            anchorX,
                            cellAnchorY,
                            0,
                            0,
                            panelDesc,
                            atlas.width,
                            atlasBin,
                            paletteArgb,
                            argbPixels,
                            pickBuffer,
                            width,
                            height,
                            cellIdx,
                        )
                    }
                }

                // Step 3: Shape Archetype
                val archKey = (cell.archetype.id shl 2) or cell.rotation.index
                val traps = archTable[archKey]
                for (trap in traps) {
                    val desc =
                        when (trap.texSlot) {
                            SLOT_TOP -> topDesc
                            SLOT_SKIRT -> skirtDesc
                            SLOT_PANEL -> panelDesc
                            else -> topDesc
                        } ?: continue
                    val uOff = if (trap.texSlot == SLOT_PANEL) 0 else ((uBaseWorld and desc.uMask) shl 8)
                    val vOff = if (trap.texSlot == SLOT_PANEL) 0 else ((vBaseWorld and desc.vMask) shl 8)
                    drawSingleTrap(
                        trap,
                        anchorX,
                        cellAnchorY,
                        uOff,
                        vOff,
                        desc,
                        atlas.width,
                        atlasBin,
                        paletteArgb,
                        argbPixels,
                        pickBuffer,
                        width,
                        height,
                        cellIdx,
                    )
                }

                // Step 5: Front Edge Panels
                if (cell.panels.hasAny && panelDesc != null) {
                    val is2h = if (cell.panels.tall2h) 1 else 0
                    if (hasEdgePanel(cell.panels, frontNsEdge)) {
                        drawTraps(
                            panelTraps[(frontNsEdge shl 1) or is2h],
                            anchorX,
                            cellAnchorY,
                            0,
                            0,
                            panelDesc,
                            atlas.width,
                            atlasBin,
                            paletteArgb,
                            argbPixels,
                            pickBuffer,
                            width,
                            height,
                            cellIdx,
                        )
                    }
                    if (hasEdgePanel(cell.panels, frontEwEdge)) {
                        drawTraps(
                            panelTraps[(frontEwEdge shl 1) or is2h],
                            anchorX,
                            cellAnchorY,
                            0,
                            0,
                            panelDesc,
                            atlas.width,
                            atlasBin,
                            paletteArgb,
                            argbPixels,
                            pickBuffer,
                            width,
                            height,
                            cellIdx,
                        )
                    }
                }
            }
        }

        image.setRGB(0, 0, width, height, argbPixels, 0, width)
        return IsoRenderResult(
            image = image,
            cellPickBuffer = pickBuffer,
            cellFloorPolygons = floorPolygons,
            width = width,
            height = height,
            originX = origin.x,
            originZ = origin.y,
        )
    }

    private fun hasEdgePanel(
        panels: EdgePanels,
        edgeIndex: Int,
    ): Boolean =
        when (edgeIndex) {
            0 -> panels.north
            1 -> panels.east
            2 -> panels.south
            3 -> panels.west
            else -> false
        }

    private fun resolveDescSafe(
        symbol: String?,
        descriptors: List<TextureDescriptorInfo>,
    ): TextureDescriptorInfo? {
        if (descriptors.isEmpty()) return null
        if (symbol.isNullOrBlank()) return descriptors.firstOrNull()
        return try {
            val idx = LevelBinarySerializer.resolveTextureIndex(symbol, descriptors)
            descriptors.getOrNull(idx)
        } catch (_: Exception) {
            null
        }
    }

    private fun compileAllEdgePanels(
        lat: Array<IntPoint>,
        sin: Int,
        cos: Int,
    ): Array<List<CompiledTrap>> =
        Array(8) { idx ->
            val edge = idx ushr 1
            val is2h = (idx and 1) != 0
            val eBase = if (is2h) 12 else 8
            val vHeight = if (is2h) 32 else 16
            when (edge) {
                0 -> // North
                    if (cos >= 0) {
                        compileQuad(lat[eBase + 0], lat[eBase + 1], lat[1], lat[0], UV_MODE_WALL_NS, SLOT_PANEL, 32, vHeight, sin, cos)
                    } else {
                        compileQuad(lat[eBase + 1], lat[eBase + 0], lat[0], lat[1], UV_MODE_WALL_NS, SLOT_PANEL, 32, vHeight, sin, cos)
                    }
                1 -> // East
                    if (sin >= 0) {
                        compileQuad(lat[eBase + 2], lat[eBase + 1], lat[1], lat[2], UV_MODE_WALL_EW, SLOT_PANEL, 32, vHeight, sin, cos)
                    } else {
                        compileQuad(lat[eBase + 1], lat[eBase + 2], lat[2], lat[1], UV_MODE_WALL_EW, SLOT_PANEL, 32, vHeight, sin, cos)
                    }
                2 -> // South
                    if (cos >= 0) {
                        compileQuad(lat[eBase + 3], lat[eBase + 2], lat[2], lat[3], UV_MODE_WALL_NS, SLOT_PANEL, 32, vHeight, sin, cos)
                    } else {
                        compileQuad(lat[eBase + 2], lat[eBase + 3], lat[3], lat[2], UV_MODE_WALL_NS, SLOT_PANEL, 32, vHeight, sin, cos)
                    }
                else -> // West
                    if (sin >= 0) {
                        compileQuad(lat[eBase + 3], lat[eBase + 0], lat[0], lat[3], UV_MODE_WALL_EW, SLOT_PANEL, 32, vHeight, sin, cos)
                    } else {
                        compileQuad(lat[eBase + 0], lat[eBase + 3], lat[3], lat[0], UV_MODE_WALL_EW, SLOT_PANEL, 32, vHeight, sin, cos)
                    }
            }
        }

    private fun compileAllArchetypes(
        lat: Array<IntPoint>,
        m1: Array<IntPoint>,
        m2: Array<IntPoint>,
        kBot: IntPoint,
        kTop: IntPoint,
        flatTraps: List<CompiledTrap>,
        sin: Int,
        cos: Int,
    ): Array<List<CompiledTrap>> {
        val table = Array<List<CompiledTrap>>(64) { emptyList() }
        for (r in 0..3) {
            table[(ShapeArchetype.PRIM_FLAT.id shl 2) or r] = flatTraps

            val b = Array(4) { k -> lat[(k + r) and 3] }
            val e1 = Array(4) { k -> lat[8 + ((k + r) and 3)] }
            val e2 = Array(4) { k -> lat[12 + ((k + r) and 3)] }
            val mW = (r + 3) and 3
            val mE = (r + 1) and 3
            val mbW = lat[16 + mW]
            val mbE = lat[16 + mE]
            val m1W = m1[mW]
            val m1E = m1[mE]
            val m2W = m2[mW]
            val m2E = m2[mE]

            // PRIM_WEDGE_STRAIGHT (1h & 2h)
            table[(ShapeArchetype.PRIM_WEDGE_STRAIGHT.id shl 2) or r] =
                compileQuad(e1[0], e1[1], b[2], b[3], UV_MODE_SLOPE, SLOT_TOP, 32, 32, sin, cos) +
                compileQuad(e1[1], e1[0], b[0], b[1], UV_MODE_WALL_NS, SLOT_SKIRT, 32, 32, sin, cos) +
                compileTri(e1[0], b[3], b[0], UV_MODE_WALL_EW, SLOT_SKIRT, sin, cos) +
                compileTri(e1[1], b[1], b[2], UV_MODE_WALL_EW, SLOT_SKIRT, sin, cos)

            table[(ShapeArchetype.PRIM_WEDGE_STRAIGHT_2H.id shl 2) or r] =
                compileQuad(e2[0], e2[1], b[2], b[3], UV_MODE_SLOPE, SLOT_TOP, 32, 32, sin, cos) +
                compileQuad(e2[1], e2[0], b[0], b[1], UV_MODE_WALL_NS, SLOT_SKIRT, 32, 32, sin, cos) +
                compileTri(e2[0], b[3], b[0], UV_MODE_WALL_EW, SLOT_SKIRT, sin, cos) +
                compileTri(e2[1], b[1], b[2], UV_MODE_WALL_EW, SLOT_SKIRT, sin, cos)

            // PRIM_WEDGE_OUTER (1h & 2h)
            table[(ShapeArchetype.PRIM_WEDGE_OUTER.id shl 2) or r] =
                compileTri(e1[0], b[1], b[3], UV_MODE_SLOPE, SLOT_TOP, sin, cos) +
                compileTri(b[1], b[2], b[3], UV_MODE_HORIZONTAL, SLOT_TOP, sin, cos) +
                compileTri(e1[0], b[0], b[1], UV_MODE_WALL_NS, SLOT_SKIRT, sin, cos) +
                compileTri(e1[0], b[3], b[0], UV_MODE_WALL_EW, SLOT_SKIRT, sin, cos)

            table[(ShapeArchetype.PRIM_WEDGE_OUTER_2H.id shl 2) or r] =
                compileTri(e2[0], b[1], b[3], UV_MODE_SLOPE, SLOT_TOP, sin, cos) +
                compileTri(b[1], b[2], b[3], UV_MODE_HORIZONTAL, SLOT_TOP, sin, cos) +
                compileTri(e2[0], b[0], b[1], UV_MODE_WALL_NS, SLOT_SKIRT, sin, cos) +
                compileTri(e2[0], b[3], b[0], UV_MODE_WALL_EW, SLOT_SKIRT, sin, cos)

            // PRIM_WEDGE_INNER (1h & 2h)
            table[(ShapeArchetype.PRIM_WEDGE_INNER.id shl 2) or r] =
                compileTri(e1[0], e1[1], e1[3], UV_MODE_HORIZONTAL, SLOT_TOP, sin, cos) +
                compileTri(e1[1], b[2], e1[3], UV_MODE_SLOPE, SLOT_TOP, sin, cos) +
                compileQuad(e1[1], e1[0], b[0], b[1], UV_MODE_WALL_NS, SLOT_SKIRT, 32, 32, sin, cos) +
                compileQuad(e1[0], e1[3], b[3], b[0], UV_MODE_WALL_EW, SLOT_SKIRT, 32, 32, sin, cos) +
                compileTri(e1[3], b[2], b[3], UV_MODE_WALL_NS, SLOT_SKIRT, sin, cos) +
                compileTri(e1[1], b[1], b[2], UV_MODE_WALL_EW, SLOT_SKIRT, sin, cos)

            table[(ShapeArchetype.PRIM_WEDGE_INNER_2H.id shl 2) or r] =
                compileTri(e2[0], e2[1], e2[3], UV_MODE_HORIZONTAL, SLOT_TOP, sin, cos) +
                compileTri(e2[1], b[2], e2[3], UV_MODE_SLOPE, SLOT_TOP, sin, cos) +
                compileQuad(e2[1], e2[0], b[0], b[1], UV_MODE_WALL_NS, SLOT_SKIRT, 32, 32, sin, cos) +
                compileQuad(e2[0], e2[3], b[3], b[0], UV_MODE_WALL_EW, SLOT_SKIRT, 32, 32, sin, cos) +
                compileTri(e2[3], b[2], b[3], UV_MODE_WALL_NS, SLOT_SKIRT, sin, cos) +
                compileTri(e2[1], b[1], b[2], UV_MODE_WALL_EW, SLOT_SKIRT, sin, cos)

            // PRIM_STAIRS
            table[(ShapeArchetype.PRIM_STAIRS.id shl 2) or r] =
                compileQuad(m1W, m1E, e1[2], e1[3], UV_MODE_HORIZONTAL, SLOT_TOP, 32, 32, sin, cos) +
                compileQuad(e1[3], e1[2], b[2], b[3], UV_MODE_WALL_NS, SLOT_SKIRT, 32, 32, sin, cos) +
                compileQuad(e2[0], e2[1], m2E, m2W, UV_MODE_HORIZONTAL, SLOT_TOP, 32, 32, sin, cos) +
                compileQuad(m2W, m2E, m1E, m1W, UV_MODE_WALL_NS, SLOT_SKIRT, 32, 32, sin, cos) +
                compileQuad(e2[1], e2[0], b[0], b[1], UV_MODE_WALL_NS, SLOT_SKIRT, 32, 32, sin, cos) +
                compileQuad(m1W, e1[3], b[3], mbW, UV_MODE_WALL_EW, SLOT_SKIRT, 32, 32, sin, cos) +
                compileQuad(e2[0], m2W, mbW, b[0], UV_MODE_WALL_EW, SLOT_SKIRT, 32, 32, sin, cos) +
                compileQuad(e1[2], m1E, mbE, b[2], UV_MODE_WALL_EW, SLOT_SKIRT, 32, 32, sin, cos) +
                compileQuad(e2[1], b[1], mbE, m2E, UV_MODE_WALL_EW, SLOT_SKIRT, 32, 32, sin, cos)
        }

        // PRIM_LIQUID_SHALLOW & PRIM_LIQUID_DEEP
        val shallowTraps =
            compileQuad(lat[0], lat[1], lat[2], lat[3], UV_MODE_HORIZONTAL, SLOT_SKIRT, 32, 32, sin, cos) +
                compileQuad(lat[8], lat[9], lat[10], lat[11], UV_MODE_HORIZONTAL, SLOT_TOP, 32, 32, sin, cos)
        val deepTraps =
            compileQuad(lat[0], lat[1], lat[2], lat[3], UV_MODE_HORIZONTAL, SLOT_SKIRT, 32, 32, sin, cos) +
                compileQuad(lat[12], lat[13], lat[14], lat[15], UV_MODE_HORIZONTAL, SLOT_TOP, 32, 32, sin, cos)

        // PRIM_PILLAR (floor + 2 camera-facing walls + top cap)
        val pillarNsWall =
            if (cos >= 0) {
                compileQuad(lat[27], lat[26], lat[22], lat[23], UV_MODE_WALL_NS, SLOT_SKIRT, 32, 32, sin, cos)
            } else {
                compileQuad(lat[25], lat[24], lat[20], lat[21], UV_MODE_WALL_NS, SLOT_SKIRT, 32, 32, sin, cos)
            }
        val pillarEwWall =
            if (sin >= 0) {
                compileQuad(lat[26], lat[25], lat[21], lat[22], UV_MODE_WALL_EW, SLOT_SKIRT, 32, 32, sin, cos)
            } else {
                compileQuad(lat[24], lat[27], lat[23], lat[20], UV_MODE_WALL_EW, SLOT_SKIRT, 32, 32, sin, cos)
            }
        val pillarCap = compileQuad(lat[24], lat[25], lat[26], lat[27], UV_MODE_HORIZONTAL, SLOT_TOP, 32, 32, sin, cos)
        val pillarTraps = flatTraps + pillarNsWall + pillarEwWall + pillarCap

        // PRIM_FOLIAGE_CROSS (floor + 4 center-radiating fins sorted back-to-front)
        val finN = compileFin(kTop, kBot, m2[0], lat[16], sin >= 0, UV_MODE_WALL_NS, sin, cos)
        val finS = compileFin(kTop, kBot, m2[2], lat[18], sin < 0, UV_MODE_WALL_NS, sin, cos)
        val finE = compileFin(kTop, kBot, m2[1], lat[17], cos >= 0, UV_MODE_WALL_EW, sin, cos)
        val finW = compileFin(kTop, kBot, m2[3], lat[19], cos < 0, UV_MODE_WALL_EW, sin, cos)

        val stepA = if (cos >= 0) finN else finS
        val stepB = if (sin >= 0) finW else finE
        val stepC = if (cos >= 0) finS else finN
        val stepD = if (sin >= 0) finE else finW
        val foliageTraps = flatTraps + stepA + stepB + stepC + stepD

        for (r in 0..3) {
            table[(ShapeArchetype.PRIM_LIQUID_SHALLOW.id shl 2) or r] = shallowTraps
            table[(ShapeArchetype.PRIM_LIQUID_DEEP.id shl 2) or r] = deepTraps
            table[(ShapeArchetype.PRIM_PILLAR.id shl 2) or r] = pillarTraps
            table[(ShapeArchetype.PRIM_FOLIAGE_CROSS.id shl 2) or r] = foliageTraps
        }

        return table
    }

    private fun compileFin(
        kTop: IntPoint,
        kBot: IntPoint,
        mTop: IntPoint,
        mBot: IntPoint,
        kFirst: Boolean,
        uvMode: Int,
        sin: Int,
        cos: Int,
    ): List<CompiledTrap> =
        if (kFirst) {
            compileQuadWithExplicitUV(
                VertexUV(kTop.x, kTop.y, 0, 0),
                VertexUV(mTop.x, mTop.y, 16 shl 8, 0),
                VertexUV(mBot.x, mBot.y, 16 shl 8, 32 shl 8),
                VertexUV(kBot.x, kBot.y, 0, 32 shl 8),
                uvMode,
                SLOT_PANEL,
                sin,
                cos,
            )
        } else {
            compileQuadWithExplicitUV(
                VertexUV(mTop.x, mTop.y, 16 shl 8, 0),
                VertexUV(kTop.x, kTop.y, 0, 0),
                VertexUV(kBot.x, kBot.y, 0, 32 shl 8),
                VertexUV(mBot.x, mBot.y, 16 shl 8, 32 shl 8),
                uvMode,
                SLOT_PANEL,
                sin,
                cos,
            )
        }

    private fun compileQuad(
        p0: IntPoint,
        p1: IntPoint,
        p2: IntPoint,
        p3: IntPoint,
        uvMode: Int,
        texSlot: Int,
        uSpan: Int,
        vSpan: Int,
        sin: Int,
        cos: Int,
    ): List<CompiledTrap> =
        compileQuadWithExplicitUV(
            VertexUV(p0.x, p0.y, 0, 0),
            VertexUV(p1.x, p1.y, uSpan shl 8, 0),
            VertexUV(p2.x, p2.y, uSpan shl 8, vSpan shl 8),
            VertexUV(p3.x, p3.y, 0, vSpan shl 8),
            uvMode,
            texSlot,
            sin,
            cos,
        )

    private fun compileQuadWithExplicitUV(
        v0: VertexUV,
        v1: VertexUV,
        v2: VertexUV,
        v3: VertexUV,
        uvMode: Int,
        texSlot: Int,
        sin: Int,
        cos: Int,
    ): List<CompiledTrap> {
        val cross = (v1.x - v0.x).toLong() * (v2.y - v0.y).toLong() - (v1.y - v0.y).toLong() * (v2.x - v0.x).toLong()
        if (cross <= 0L) return emptyList()
        return compileTriangleVertices(v0, v1, v2, uvMode, texSlot, sin, cos) +
            compileTriangleVertices(v0, v2, v3, uvMode, texSlot, sin, cos)
    }

    private fun compileTri(
        p0: IntPoint,
        p1: IntPoint,
        p2: IntPoint,
        uvMode: Int,
        texSlot: Int,
        sin: Int,
        cos: Int,
    ): List<CompiledTrap> {
        val v0 = VertexUV(p0.x, p0.y, 0, 0)
        val v1 = VertexUV(p1.x, p1.y, 32 shl 8, 0)
        val v2 = VertexUV(p2.x, p2.y, 0, 32 shl 8)
        val cross = (v1.x - v0.x).toLong() * (v2.y - v0.y).toLong() - (v1.y - v0.y).toLong() * (v2.x - v0.x).toLong()
        if (cross <= 0L) return emptyList()
        return compileTriangleVertices(v0, v1, v2, uvMode, texSlot, sin, cos)
    }

    private fun compileTriangleVertices(
        a: VertexUV,
        b: VertexUV,
        c: VertexUV,
        uvMode: Int,
        texSlot: Int,
        sin: Int,
        cos: Int,
    ): List<CompiledTrap> {
        val sorted = listOf(a, b, c).sortedWith(compareBy<VertexUV> { it.y }.thenBy { it.x })
        val t0 = sorted[0]
        val t1 = sorted[1]
        val t2 = sorted[2]

        val totalDy = t2.y - t0.y
        if (totalDy <= 0) return emptyList()

        // Split long edge T0..T2 at scanline t1.y in Q8.8
        val fracDy = t1.y - t0.y
        val mXQ8 = (t0.x shl 8) + (((t2.x - t0.x) shl 8) * fracDy) / totalDy
        val mUQ8 = t0.uQ8 + ((t2.uQ8 - t0.uQ8) * fracDy) / totalDy
        val mVQ8 = t0.vQ8 + ((t2.vQ8 - t0.vQ8) * fracDy) / totalDy
        val t1XQ8 = t1.x shl 8

        val (duDxQ8, dvDxQ8) =
            if (uvMode == UV_MODE_HORIZONTAL) {
                // World-locked horizontal scanline gradient: du/dx = cos_yaw, dv/dx = -sin_yaw (Q2.14 -> Q8.8 is >> 6)
                (cos shr 6) to (-sin shr 6)
            } else {
                val widthQ8 = t1XQ8 - mXQ8
                if (kotlin.math.abs(widthQ8) >= 64) {
                    (((t1.uQ8 - mUQ8) shl 8) / widthQ8) to (((t1.vQ8 - mVQ8) shl 8) / widthQ8)
                } else {
                    0 to 0
                }
            }

        val out = mutableListOf<CompiledTrap>()
        val isT1Left = t1XQ8 < mXQ8

        // Top half: t0.y .. t1.y
        val topLines = t1.y - t0.y
        if (topLines > 0) {
            val leftEndX = if (isT1Left) t1XQ8 else mXQ8
            val rightEndX = if (isT1Left) mXQ8 else t1XQ8
            val leftEndU = if (isT1Left) t1.uQ8 else mUQ8
            val leftEndV = if (isT1Left) t1.vQ8 else mVQ8
            val t0XQ8 = t0.x shl 8
            out.add(
                CompiledTrap(
                    dyStart = t0.y,
                    lineCount = topLines,
                    xLStartQ8 = t0XQ8,
                    xRStartQ8 = t0XQ8,
                    dxLDyQ8 = (leftEndX - t0XQ8) / topLines,
                    dxRDyQ8 = (rightEndX - t0XQ8) / topLines,
                    uLStartQ8 = t0.uQ8,
                    vLStartQ8 = t0.vQ8,
                    duDyQ8 = (leftEndU - t0.uQ8) / topLines,
                    dvDyQ8 = (leftEndV - t0.vQ8) / topLines,
                    duDxQ8 = duDxQ8,
                    dvDxQ8 = dvDxQ8,
                    texSlot = texSlot,
                ),
            )
        }

        // Bottom half: t1.y .. t2.y
        val botLines = t2.y - t1.y
        if (botLines > 0) {
            val leftStartX = if (isT1Left) t1XQ8 else mXQ8
            val rightStartX = if (isT1Left) mXQ8 else t1XQ8
            val leftStartU = if (isT1Left) t1.uQ8 else mUQ8
            val leftStartV = if (isT1Left) t1.vQ8 else mVQ8
            val t2XQ8 = t2.x shl 8
            out.add(
                CompiledTrap(
                    dyStart = t1.y,
                    lineCount = botLines,
                    xLStartQ8 = leftStartX,
                    xRStartQ8 = rightStartX,
                    dxLDyQ8 = (t2XQ8 - leftStartX) / botLines,
                    dxRDyQ8 = (t2XQ8 - rightStartX) / botLines,
                    uLStartQ8 = leftStartU,
                    vLStartQ8 = leftStartV,
                    duDyQ8 = (t2.uQ8 - leftStartU) / botLines,
                    dvDyQ8 = (t2.vQ8 - leftStartV) / botLines,
                    duDxQ8 = duDxQ8,
                    dvDxQ8 = dvDxQ8,
                    texSlot = texSlot,
                ),
            )
        }

        return out
    }

    private fun drawTraps(
        traps: List<CompiledTrap>,
        anchorX: Int,
        anchorY: Int,
        uOffQ8: Int,
        vOffQ8: Int,
        desc: TextureDescriptorInfo,
        atlasWidth: Int,
        atlasBin: ByteArray,
        paletteArgb: IntArray,
        argbPixels: IntArray,
        pickBuffer: IntArray,
        screenW: Int,
        screenH: Int,
        cellIdx: Int,
    ) {
        for (trap in traps) {
            drawSingleTrap(
                trap,
                anchorX,
                anchorY,
                uOffQ8,
                vOffQ8,
                desc,
                atlasWidth,
                atlasBin,
                paletteArgb,
                argbPixels,
                pickBuffer,
                screenW,
                screenH,
                cellIdx,
            )
        }
    }

    private fun drawSingleTrap(
        trap: CompiledTrap,
        anchorX: Int,
        anchorY: Int,
        uOffQ8: Int,
        vOffQ8: Int,
        desc: TextureDescriptorInfo,
        atlasWidth: Int,
        atlasBin: ByteArray,
        paletteArgb: IntArray,
        argbPixels: IntArray,
        pickBuffer: IntArray,
        screenW: Int,
        screenH: Int,
        cellIdx: Int,
    ) {
        var y = anchorY + trap.dyStart
        var lines = trap.lineCount
        var xLQ8 = (anchorX shl 8) + trap.xLStartQ8
        var xRQ8 = (anchorX shl 8) + trap.xRStartQ8
        var uLQ8 = trap.uLStartQ8 + uOffQ8
        var vLQ8 = trap.vLStartQ8 + vOffQ8

        if (y < 0) {
            val skip = -y
            if (skip >= lines) return
            y = 0
            lines -= skip
            xLQ8 += trap.dxLDyQ8 * skip
            xRQ8 += trap.dxRDyQ8 * skip
            uLQ8 += trap.duDyQ8 * skip
            vLQ8 += trap.dvDyQ8 * skip
        }
        if (y + lines > screenH) {
            lines = screenH - y
            if (lines <= 0) return
        }

        val uMask = desc.uMask
        val vMask = desc.vMask
        val baseOffset = desc.romOffset

        repeat(lines) {
            var xStart = xLQ8 shr 8
            var xEnd = xRQ8 shr 8
            var uCur = uLQ8
            var vCur = vLQ8

            if (xStart < 0) {
                val clipX = -xStart
                uCur += trap.duDxQ8 * clipX
                vCur += trap.dvDxQ8 * clipX
                xStart = 0
            }
            if (xEnd > screenW) {
                xEnd = screenW
            }

            if (xStart < xEnd) {
                val rowBase = y * screenW
                for (px in xStart until xEnd) {
                    val uTex = (uCur shr 8) and uMask
                    val vTex = (vCur shr 8) and vMask
                    val texelIdx = baseOffset + vTex * atlasWidth + uTex
                    if (texelIdx in atlasBin.indices) {
                        val colorIdx = atlasBin[texelIdx].toInt() and 0x0F
                        if (colorIdx != 0) {
                            argbPixels[rowBase + px] = paletteArgb[colorIdx]
                            pickBuffer[rowBase + px] = cellIdx
                        }
                    }
                    uCur += trap.duDxQ8
                    vCur += trap.dvDxQ8
                }
            }

            y++
            xLQ8 += trap.dxLDyQ8
            xRQ8 += trap.dxRDyQ8
            uLQ8 += trap.duDyQ8
            vLQ8 += trap.dvDyQ8
        }
    }
}
