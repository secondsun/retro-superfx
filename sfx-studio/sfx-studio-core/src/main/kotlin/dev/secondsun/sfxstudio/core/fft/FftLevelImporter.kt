package dev.secondsun.sfxstudio.core.fft

import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.EdgePanels
import dev.secondsun.sfxstudio.core.level.LevelDocument
import dev.secondsun.sfxstudio.core.level.LevelValidator
import dev.secondsun.sfxstudio.core.level.MapCell
import dev.secondsun.sfxstudio.core.level.MapGrid
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Result of importing a Final Fantasy Tactics stage into `sfx-studio` documents. */
data class ImportedFftLevel(
    val atlasDocument: AtlasDocument,
    val levelDocument: LevelDocument,
    val bgTopColor: SnesColor,
    val bgBottomColor: SnesColor,
)

/**
 * Adapts parsed Final Fantasy Tactics map geometry, unlit CLUT textures, and tactical terrain
 * into a Bank-`$71`-compatible [AtlasDocument] (15-color BGR555 palette, `<= 32 KB`) and
 * [LevelDocument] (`4..16 x 4..16` [MapGrid]).
 */
object FftLevelImporter {
    private const val CELL_WORLD_SIZE = 28.0
    private const val HEIGHT_UNIT_1H = 24.0
    private const val UPWARD_NORMAL_MIN_Y = 0.15
    private const val HORIZONTAL_NORMAL_MIN_Y = 0.88
    private const val BORDER_PROXIMITY_TOLERANCE = 3.0
    private const val MAX_PATCH_BYTES_BUDGET = 28_672
    private const val MAX_PATCH_CLUSTERS = 28

    private data class TriInfo(
        val tri: FftTexturedTriangle,
        val nx: Double,
        val ny: Double,
        val nz: Double,
        val isUpward: Boolean,
        val minX: Double,
        val maxX: Double,
        val minZ: Double,
        val maxZ: Double,
        val minHy: Double,
        val maxHy: Double,
        val area2dXz: Double,
    )

    private enum class PatchRole(
        val prefix: String,
    ) {
        TOP("top"),
        SKIRT("skirt"),
        PANEL("panel"),
        FOLIAGE("foliage"),
    }

    private data class ColorPatch(
        val width: Int,
        val height: Int,
        val hasTransparency: Boolean,
        val role: PatchRole,
        val pixels: Array<SnesColor?>,
    ) {
        fun similarityKey(): Triple<Int, Int, Boolean> = Triple(width, height, hasTransparency)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ColorPatch) return false
            return width == other.width &&
                height == other.height &&
                hasTransparency == other.hasTransparency &&
                role == other.role &&
                pixels.contentEquals(other.pixels)
        }

        override fun hashCode(): Int {
            var res = width
            res = 31 * res + height
            res = 31 * res + hasTransparency.hashCode()
            res = 31 * res + role.hashCode()
            res = 31 * res + pixels.contentHashCode()
            return res
        }
    }

    private data class CellGeometry(
        val col: Int,
        val row: Int,
        val rawFloorHy: Double,
        val cornerHy: DoubleArray,
        val archetype: ShapeArchetype,
        val rotation: CellRotation,
        val relativeBase1h: Int,
        val isWater: Boolean,
        var topPatchIdx: Int? = null,
        var skirtPatchIdx: Int? = null,
        var panelPatchIdx: Int? = null,
        var panels: EdgePanels = EdgePanels(),
    )

    fun importFromIso(
        isoFile: File,
        mapId: Int,
        atlasName: String,
        levelName: String,
        atlasRelativePath: String,
    ): ImportedFftLevel {
        val mapData = FftIsoParser.parseMap(isoFile, mapId)
        return convertMapData(
            mapData = mapData,
            atlasName = atlasName,
            levelName = levelName,
            atlasRelativePath = atlasRelativePath,
        )
    }

    internal fun convertMapData(
        mapData: FftMapData,
        atlasName: String,
        levelName: String,
        atlasRelativePath: String,
    ): ImportedFftLevel {
        val cols = mapData.terrain.cols.coerceIn(MapGrid.MIN_DIM, MapGrid.MAX_DIM)
        val rows = mapData.terrain.rows.coerceIn(MapGrid.MIN_DIM, MapGrid.MAX_DIM)

        val triInfos = mapData.triangles.mapNotNull { classifyTriangle(it) }
        val upwardTris = triInfos.filter { it.isUpward }
        val verticalTris = triInfos.filter { !it.isUpward }

        // 1. Sample cell surface heights and fit archetypes + rotations
        val rawCornerHy = Array(cols * rows) { DoubleArray(4) }
        val rawFloorHy = DoubleArray(cols * rows)
        val rawCells =
            ArrayList<CellGeometry>(cols * rows)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                val terrainTile = mapData.terrain.tileAt(c, r, 0)
                val fallbackHy = terrainTile.elevationHalf * (HEIGHT_UNIT_1H / 2.0)
                val corners = sampleCellCornerHeights(c, r, upwardTris, fallbackHy)
                rawCornerHy[idx] = corners

                val isWater =
                    terrainTile.isWater &&
                        (corners.average() <= fallbackHy + 16.0)
                val stairRot = detectStairRotation(c, r, upwardTris)
                val fitted =
                    if (isWater) {
                        val waterTop = corners.average()
                        Triple(ShapeArchetype.PRIM_FLAT, CellRotation.ROT_0, waterTop)
                    } else if (stairRot != null) {
                        val lowHy = corners.minOrNull() ?: fallbackHy
                        Triple(ShapeArchetype.PRIM_STAIRS, stairRot, lowHy)
                    } else {
                        fitArchetypeAndBaseHy(corners)
                    }

                rawFloorHy[idx] = fitted.third
                rawCells.add(
                    CellGeometry(
                        col = c,
                        row = r,
                        rawFloorHy = fitted.third,
                        cornerHy = corners,
                        archetype = fitted.first,
                        rotation = fitted.second,
                        relativeBase1h = 0,
                        isWater = isWater,
                    ),
                )
            }
        }

        // Normalize lowest cell height to 0 (or 1 if needed, clamped within 0..MAX_BASE_HEIGHT)
        val minRaw1h = rawCells.minOf { (it.rawFloorHy / HEIGHT_UNIT_1H).roundToInt() }
        val maxSpan1h =
            rawCells.maxOf { ((it.rawFloorHy / HEIGHT_UNIT_1H).roundToInt() - minRaw1h) }
        val heightShift1h = minRaw1h
        val heightDivisor =
            if (maxSpan1h > LevelValidator.MAX_BASE_HEIGHT) {
                LevelValidator.MAX_BASE_HEIGHT.toDouble() / maxSpan1h.toDouble()
            } else {
                1.0
            }

        val adjustedHeights =
            IntArray(cols * rows) { idx ->
                val raw1h = (rawCells[idx].rawFloorHy / HEIGHT_UNIT_1H).roundToInt() - heightShift1h
                (raw1h * heightDivisor).roundToInt().coerceIn(0, LevelValidator.MAX_BASE_HEIGHT)
            }

        // Re-apply water flushness checks after normalizing heights
        val finalCells =
            ArrayList<CellGeometry>(cols * rows).apply {
                for (idx in rawCells.indices) {
                    val cell = rawCells[idx]
                    val h = adjustedHeights[idx]
                    add(cell.copy(relativeBase1h = h))
                }
            }
        applyWaterArchetypes(finalCells, cols, rows)

        // 2. Extract raw ColorPatches for tops, skirts, edge panels, and foliage
        val extractedPatches = mutableListOf<ColorPatch>()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = finalCells[r * cols + c]
                val topPatch = extractTopPatch(mapData, c, r, upwardTris)
                if (topPatch != null) {
                    cell.topPatchIdx = extractedPatches.size
                    extractedPatches.add(topPatch)
                }
            }
        }

        assignVerticalFeatures(mapData, finalCells, cols, rows, verticalTris, extractedPatches)

        // Ensure every cell has a valid top texture (fallback to first or generated neutral tile)
        if (extractedPatches.none { it.role == PatchRole.TOP }) {
            val fallbackPixels = Array<SnesColor?>(32 * 32) { SnesColor(12, 12, 12) }
            extractedPatches.add(ColorPatch(32, 32, false, PatchRole.TOP, fallbackPixels))
        }
        val firstTopIdx = extractedPatches.indexOfFirst { it.role == PatchRole.TOP }
        for (cell in finalCells) {
            if (cell.topPatchIdx == null) {
                cell.topPatchIdx = firstTopIdx
            }
        }

        // 3. Deduplicate & cluster patches so packed atlas fits inside Bank $71 (<= 32 KB)
        val (clusteredPatches, patchRemap) =
            clusterPatchesToBudget(
                patches = extractedPatches,
                cells = finalCells,
            )

        // 4. Weighted 15-color BGR555 palette quantization (unlit CLUT colors)
        val patchWeights = IntArray(clusteredPatches.size)
        for (cell in finalCells) {
            cell.topPatchIdx?.let { patchWeights[patchRemap[it]] += 2 }
            cell.skirtPatchIdx?.let { patchWeights[patchRemap[it]] += 1 }
            cell.panelPatchIdx?.let { patchWeights[patchRemap[it]] += 1 }
        }
        val palette = quantizeWeightedPalette(clusteredPatches, patchWeights)

        // 5. Materialize SourceTextures and pack AtlasDocument (prefer 128x128, fall back to 128x256)
        val namesByCluster = assignDescriptorNames(clusteredPatches)
        val sourceTextures =
            clusteredPatches.mapIndexed { idx, patch ->
                toSourceTexture(namesByCluster[idx], patch, palette)
            }
        val atlas = packAtlasWithinBank71(sourceTextures)
        val atlasDoc =
            AtlasDocument(
                name = atlasName,
                atlas = atlas,
                palette = palette,
                gridSnap = 16,
            )

        // 6. Build LevelDocument
        val mapCells =
            finalCells.map { cell ->
                val topName = cell.topPatchIdx?.let { namesByCluster[patchRemap[it]] }
                val skirtName = cell.skirtPatchIdx?.let { namesByCluster[patchRemap[it]] }
                val panelName = cell.panelPatchIdx?.let { namesByCluster[patchRemap[it]] }
                MapCell(
                    baseHeight = cell.relativeBase1h,
                    archetype = cell.archetype,
                    rotation = cell.rotation,
                    topTex = topName,
                    skirtTex = skirtName,
                    panelTex = panelName,
                    panels = cell.panels,
                )
            }
        val levelDoc =
            LevelDocument(
                name = levelName,
                atlasPath = atlasRelativePath,
                grid = MapGrid(cols = cols, rows = rows, cells = mapCells),
            )

        return ImportedFftLevel(
            atlasDocument = atlasDoc,
            levelDocument = levelDoc,
            bgTopColor = mapData.bgTop,
            bgBottomColor = mapData.bgBottom,
        )
    }

    private fun classifyTriangle(tri: FftTexturedTriangle): TriInfo? {
        if (tri.uv0 == tri.uv1 && tri.uv1 == tri.uv2) return null

        val abx = (tri.p1.x - tri.p0.x).toDouble()
        val aby = (tri.p1.hy - tri.p0.hy).toDouble()
        val abz = (tri.p1.z - tri.p0.z).toDouble()
        val acx = (tri.p2.x - tri.p0.x).toDouble()
        val acy = (tri.p2.hy - tri.p0.hy).toDouble()
        val acz = (tri.p2.z - tri.p0.z).toDouble()

        val nx = aby * acz - abz * acy
        val ny = abz * acx - abx * acz
        val nz = abx * acy - aby * acx
        val mag = sqrt(nx * nx + ny * ny + nz * nz)
        if (mag < 1e-6) return null

        val area2dXz = abs((tri.p1.x - tri.p0.x) * (tri.p2.z - tri.p0.z) - (tri.p2.x - tri.p0.x) * (tri.p1.z - tri.p0.z)).toDouble()
        return TriInfo(
            tri = tri,
            nx = nx / mag,
            ny = ny / mag,
            nz = nz / mag,
            isUpward = abs(ny) / mag >= UPWARD_NORMAL_MIN_Y && area2dXz >= 8.0,
            minX = min(tri.p0.x, min(tri.p1.x, tri.p2.x)).toDouble(),
            maxX = max(tri.p0.x, max(tri.p1.x, tri.p2.x)).toDouble(),
            minZ = min(tri.p0.z, min(tri.p1.z, tri.p2.z)).toDouble(),
            maxZ = max(tri.p0.z, max(tri.p1.z, tri.p2.z)).toDouble(),
            minHy = min(tri.p0.hy, min(tri.p1.hy, tri.p2.hy)).toDouble(),
            maxHy = max(tri.p0.hy, max(tri.p1.hy, tri.p2.hy)).toDouble(),
            area2dXz = area2dXz,
        )
    }

    private fun sampleCellCornerHeights(
        col: Int,
        row: Int,
        upwardTris: List<TriInfo>,
        fallbackHy: Double,
    ): DoubleArray {
        val x0 = col * CELL_WORLD_SIZE
        val z0 = row * CELL_WORLD_SIZE
        val cand =
            upwardTris.filter {
                it.maxX >= x0 && it.minX <= x0 + CELL_WORLD_SIZE && it.maxZ >= z0 && it.minZ <= z0 + CELL_WORLD_SIZE
            }
        if (cand.isEmpty()) {
            return doubleArrayOf(fallbackHy, fallbackHy, fallbackHy, fallbackHy)
        }

        // Sample an 8x8 grid inside the cell to compute interior fallback
        var sumHy = 0.0
        var hits = 0
        for (sz in 0 until 8) {
            val z = z0 + 0.5 + (sz + 0.5) * (CELL_WORLD_SIZE - 1.0) / 8.0
            for (sx in 0 until 8) {
                val x = x0 + 0.5 + (sx + 0.5) * (CELL_WORLD_SIZE - 1.0) / 8.0
                val top = highestTriangleAt(cand, x, z)
                if (top != null) {
                    sumHy += top.second
                    hits++
                }
            }
        }
        val cellMeanHy = if (hits > 0) sumHy / hits else fallbackHy

        val offsets =
            arrayOf(
                1.0 to 1.0, // C0: NW
                (CELL_WORLD_SIZE - 1.0) to 1.0, // C1: NE
                (CELL_WORLD_SIZE - 1.0) to (CELL_WORLD_SIZE - 1.0), // C2: SE
                1.0 to (CELL_WORLD_SIZE - 1.0), // C3: SW
            )
        return DoubleArray(4) { k ->
            val (dx, dz) = offsets[k]
            highestTriangleAt(cand, x0 + dx, z0 + dz)?.second ?: cellMeanHy
        }
    }

    private fun highestTriangleAt(
        tris: List<TriInfo>,
        x: Double,
        z: Double,
    ): Pair<TriInfo, Double>? {
        var bestTri: TriInfo? = null
        var bestHy = Double.NEGATIVE_INFINITY
        for (info in tris) {
            if (x < info.minX - 1e-3 || x > info.maxX + 1e-3 || z < info.minZ - 1e-3 || z > info.maxZ + 1e-3) continue
            val w = barycentricXz(info.tri, x, z) ?: continue
            val hy = w.first * info.tri.p0.hy + w.second * info.tri.p1.hy + w.third * info.tri.p2.hy
            if (hy > bestHy) {
                bestHy = hy
                bestTri = info
            }
        }
        return bestTri?.let { it to bestHy }
    }

    private fun barycentricXz(
        tri: FftTexturedTriangle,
        x: Double,
        z: Double,
    ): Triple<Double, Double, Double>? {
        val x0 = tri.p0.x.toDouble()
        val z0 = tri.p0.z.toDouble()
        val x1 = tri.p1.x.toDouble()
        val z1 = tri.p1.z.toDouble()
        val x2 = tri.p2.x.toDouble()
        val z2 = tri.p2.z.toDouble()

        val denom = (z1 - z2) * (x0 - x2) + (x2 - x1) * (z0 - z2)
        if (abs(denom) < 1e-6) return null
        val w0 = ((z1 - z2) * (x - x2) + (x2 - x1) * (z - z2)) / denom
        val w1 = ((z2 - z0) * (x - x2) + (x0 - x2) * (z - z2)) / denom
        val w2 = 1.0 - w0 - w1
        val eps = -2e-3
        if (w0 < eps || w1 < eps || w2 < eps) return null
        return Triple(w0, w1, w2)
    }

    private fun detectStairRotation(
        col: Int,
        row: Int,
        upwardTris: List<TriInfo>,
    ): CellRotation? {
        val x0 = col * CELL_WORLD_SIZE
        val z0 = row * CELL_WORLD_SIZE
        val flatTreads =
            upwardTris.filter { info ->
                abs(info.ny) >= HORIZONTAL_NORMAL_MIN_Y &&
                    info.minX >= x0 - 1.0 &&
                    info.maxX <= x0 + CELL_WORLD_SIZE + 1.0 &&
                    info.minZ >= z0 - 1.0 &&
                    info.maxZ <= z0 + CELL_WORLD_SIZE + 1.0 &&
                    (info.maxHy - info.minHy) <= 2.0
            }
        if (flatTreads.size < 2) return null

        val distinctHeights = flatTreads.map { (it.minHy / 6.0).roundToInt() * 6.0 }.distinct()
        if (distinctHeights.size < 2) return null
        val minH = distinctHeights.min()
        val maxH = distinctHeights.max()
        if (maxH - minH < 18.0) return null

        // Check that treads actually rise across X or Z rather than stacking directly above each other
        val lowCenter =
            flatTreads.filter { it.minHy <= minH + 4.0 }.let { lows ->
                val cx = lows.map { (it.minX + it.maxX) * 0.5 }.average()
                val cz = lows.map { (it.minZ + it.maxZ) * 0.5 }.average()
                cx to cz
            }
        val highCenter =
            flatTreads.filter { it.maxHy >= maxH - 4.0 }.let { highs ->
                val cx = highs.map { (it.minX + it.maxX) * 0.5 }.average()
                val cz = highs.map { (it.minZ + it.maxZ) * 0.5 }.average()
                cx to cz
            }
        val dx = highCenter.first - lowCenter.first
        val dz = highCenter.second - lowCenter.second
        if (abs(dx) < 4.0 && abs(dz) < 4.0) return null

        return if (abs(dz) >= abs(dx)) {
            if (dz < 0) CellRotation.ROT_0 else CellRotation.ROT_180
        } else {
            if (dx > 0) CellRotation.ROT_90 else CellRotation.ROT_270
        }
    }

    private fun fitArchetypeAndBaseHy(cornersHy: DoubleArray): Triple<ShapeArchetype, CellRotation, Double> {
        val corners1h = DoubleArray(4) { cornersHy[it] / HEIGHT_UNIT_1H }
        val minCorner = corners1h.min()
        val maxCorner = corners1h.max()

        // Fast path for flat or near-flat cells
        if (maxCorner - minCorner <= 0.35) {
            return Triple(ShapeArchetype.PRIM_FLAT, CellRotation.ROT_0, cornersHy.average())
        }

        val candidates =
            listOf(
                ShapeArchetype.PRIM_FLAT to intArrayOf(0, 0, 0, 0),
                ShapeArchetype.PRIM_WEDGE_STRAIGHT to intArrayOf(1, 1, 0, 0),
                ShapeArchetype.PRIM_WEDGE_STRAIGHT_2H to intArrayOf(2, 2, 0, 0),
                ShapeArchetype.PRIM_WEDGE_OUTER to intArrayOf(1, 0, 0, 0),
                ShapeArchetype.PRIM_WEDGE_OUTER_2H to intArrayOf(2, 0, 0, 0),
                ShapeArchetype.PRIM_WEDGE_INNER to intArrayOf(1, 1, 0, 1),
                ShapeArchetype.PRIM_WEDGE_INNER_2H to intArrayOf(2, 2, 0, 2),
            )

        var bestArch = ShapeArchetype.PRIM_FLAT
        var bestRot = CellRotation.ROT_0
        var bestBase1h = corners1h.average()
        var bestErr = Double.POSITIVE_INFINITY

        for ((arch, template) in candidates) {
            val rots = if (arch == ShapeArchetype.PRIM_FLAT) listOf(0) else listOf(0, 1, 2, 3)
            for (r in rots) {
                var sumDiff = 0.0
                for (k in 0 until 4) {
                    val cornerIdx = (k + r) and 3
                    sumDiff += corners1h[cornerIdx] - template[k]
                }
                val base1h = (sumDiff / 4.0).roundToInt().toDouble()
                var err = 0.0
                for (k in 0 until 4) {
                    val cornerIdx = (k + r) and 3
                    val d = corners1h[cornerIdx] - (base1h + template[k])
                    err += d * d
                }
                if (arch == ShapeArchetype.PRIM_FLAT) {
                    err -= 0.18 // Small stability preference for PRIM_FLAT when error is tiny
                }
                if (err < bestErr) {
                    bestErr = err
                    bestArch = arch
                    bestRot = CellRotation.fromIndex(r)
                    bestBase1h = base1h * HEIGHT_UNIT_1H
                }
            }
        }
        return Triple(bestArch, bestRot, bestBase1h)
    }

    private fun applyWaterArchetypes(
        cells: MutableList<CellGeometry>,
        cols: Int,
        rows: Int,
    ) {
        fun cellTop(
            c: Int,
            r: Int,
        ): Int {
            val cell = cells[r * cols + c]
            return when (cell.archetype) {
                ShapeArchetype.PRIM_FLAT,
                ShapeArchetype.PRIM_PILLAR,
                ShapeArchetype.PRIM_FOLIAGE_CROSS,
                -> cell.relativeBase1h
                else -> cell.relativeBase1h + cell.archetype.heightSpan1h
            }
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                val cell = cells[idx]
                if (!cell.isWater) continue
                val waterSurface = cell.relativeBase1h
                if (c in 1 until (cols - 1) && r in 1 until (rows - 1) && waterSurface >= 1) {
                    val flushWithBanks =
                        listOf(c to (r - 1), (c + 1) to r, c to (r + 1), (c - 1) to r).all { (nc, nr) ->
                            val neighbor = cells[nr * cols + nc]
                            if (neighbor.isWater) {
                                neighbor.relativeBase1h >= waterSurface
                            } else {
                                cellTop(nc, nr) >= waterSurface
                            }
                        }
                    if (flushWithBanks) {
                        cells[idx] =
                            cell.copy(
                                archetype = ShapeArchetype.PRIM_LIQUID_SHALLOW,
                                rotation = CellRotation.ROT_0,
                                relativeBase1h = (waterSurface - 1).coerceAtLeast(0),
                            )
                    }
                }
            }
        }
    }

    private fun extractTopPatch(
        mapData: FftMapData,
        col: Int,
        row: Int,
        upwardTris: List<TriInfo>,
    ): ColorPatch? {
        val x0 = col * CELL_WORLD_SIZE
        val z0 = row * CELL_WORLD_SIZE
        val cand =
            upwardTris.filter {
                it.maxX >= x0 && it.minX <= x0 + CELL_WORLD_SIZE && it.maxZ >= z0 && it.minZ <= z0 + CELL_WORLD_SIZE
            }
        if (cand.isEmpty()) return null

        val pixels = arrayOfNulls<SnesColor>(32 * 32)
        var hits = 0
        for (tz in 0 until 32) {
            val wz = z0 + (tz + 0.5) * CELL_WORLD_SIZE / 32.0
            for (tx in 0 until 32) {
                val wx = x0 + (tx + 0.5) * CELL_WORLD_SIZE / 32.0
                val hit = highestTriangleAt(cand, wx, wz) ?: continue
                val w = barycentricXz(hit.first.tri, wx, wz) ?: continue
                val u = (w.first * hit.first.tri.uv0.u + w.second * hit.first.tri.uv1.u + w.third * hit.first.tri.uv2.u).roundToInt()
                val v = (w.first * hit.first.tri.uv0.v + w.second * hit.first.tri.uv1.v + w.third * hit.first.tri.uv2.v).roundToInt()
                val color = mapData.sampleColor(hit.first.tri.page, u, v, hit.first.tri.paletteIndex)
                if (color != null) {
                    pixels[tz * 32 + tx] = color
                    hits++
                }
            }
        }
        if (hits == 0) return null
        if (hits < 32 * 32) {
            fillMissingOpaquePixels(pixels, 32, 32)
        }
        return ColorPatch(32, 32, hasTransparency = false, role = PatchRole.TOP, pixels = pixels)
    }

    private fun assignVerticalFeatures(
        mapData: FftMapData,
        cells: MutableList<CellGeometry>,
        cols: Int,
        rows: Int,
        verticalTris: List<TriInfo>,
        extractedPatches: MutableList<ColorPatch>,
    ) {
        val skirtCandidates = Array(cols * rows) { mutableListOf<TriInfo>() }
        val foliageCandidates = Array(cols * rows) { mutableListOf<TriInfo>() }
        val panelCandidates = Array(cols * rows) { Array(4) { mutableListOf<TriInfo>() } }

        fun cellMaxHy(
            c: Int,
            r: Int,
        ): Double = cells[r * cols + c].cornerHy.max()

        for (info in verticalTris) {
            val spanHy = info.maxHy - info.minHy
            if (spanHy < 8.0) continue

            val cx = (info.tri.p0.x + info.tri.p1.x + info.tri.p2.x) / 3.0
            val cz = (info.tri.p0.z + info.tri.p1.z + info.tri.p2.z) / 3.0

            val c = (cx / CELL_WORLD_SIZE).toInt().coerceIn(0, cols - 1)
            val r = (cz / CELL_WORLD_SIZE).toInt().coerceIn(0, rows - 1)

            val alignedNs =
                (info.maxZ - info.minZ) <= BORDER_PROXIMITY_TOLERANCE &&
                    abs(cz - (cz / CELL_WORLD_SIZE).roundToInt() * CELL_WORLD_SIZE) <= BORDER_PROXIMITY_TOLERANCE
            val alignedEw =
                (info.maxX - info.minX) <= BORDER_PROXIMITY_TOLERANCE &&
                    abs(cx - (cx / CELL_WORLD_SIZE).roundToInt() * CELL_WORLD_SIZE) <= BORDER_PROXIMITY_TOLERANCE

            if (!alignedNs && !alignedEw) {
                // Interior vertical polygon: check for transparent foliage cutout
                if (spanHy >= 20.0 && c in 0 until cols && r in 0 until rows && info.maxHy > cellMaxHy(c, r) + 14.0) {
                    val transRatio = estimateTransparencyRatio(mapData, info.tri)
                    if (transRatio >= 0.20) {
                        foliageCandidates[r * cols + c].add(info)
                    }
                }
                continue
            }

            if (alignedNs) {
                val gridRowLine = (cz / CELL_WORLD_SIZE).roundToInt()
                val cellNorth = if (gridRowLine - 1 in 0 until rows) (c to (gridRowLine - 1)) else null
                val cellSouth = if (gridRowLine in 0 until rows) (c to gridRowLine) else null
                val topNorth = cellNorth?.let { cellMaxHy(it.first, it.second) } ?: Double.NEGATIVE_INFINITY
                val topSouth = cellSouth?.let { cellMaxHy(it.first, it.second) } ?: Double.NEGATIVE_INFINITY
                val maxNeighborTop = max(topNorth, topSouth)

                if (info.maxHy > maxNeighborTop + 14.0 && spanHy <= 56.0) {
                    // Protruding parapet/wall -> attach to whichever adjacent cell has floor near info.minHy
                    val target =
                        when {
                            cellSouth != null && abs(topSouth - info.minHy) <= 14.0 -> cellSouth to 0 // North edge of cellSouth
                            cellNorth != null && abs(topNorth - info.minHy) <= 14.0 -> cellNorth to 2 // South edge of cellNorth
                            else -> null
                        }
                    if (target != null) {
                        val (coord, edgeIdx) = target
                        panelCandidates[coord.second * cols + coord.first][edgeIdx].add(info)
                    }
                } else {
                    // Cliff / skirt wall under the taller adjacent cell
                    val skirtOwner =
                        when {
                            cellNorth != null && cellSouth != null -> if (topNorth >= topSouth) cellNorth else cellSouth
                            cellNorth != null -> cellNorth
                            else -> cellSouth
                        }
                    if (skirtOwner != null && info.maxHy <= cellMaxHy(skirtOwner.first, skirtOwner.second) + 10.0) {
                        skirtCandidates[skirtOwner.second * cols + skirtOwner.first].add(info)
                    }
                }
            } else {
                val gridColLine = (cx / CELL_WORLD_SIZE).roundToInt()
                val cellWest = if (gridColLine - 1 in 0 until cols) ((gridColLine - 1) to r) else null
                val cellEast = if (gridColLine in 0 until cols) (gridColLine to r) else null
                val topWest = cellWest?.let { cellMaxHy(it.first, it.second) } ?: Double.NEGATIVE_INFINITY
                val topEast = cellEast?.let { cellMaxHy(it.first, it.second) } ?: Double.NEGATIVE_INFINITY
                val maxNeighborTop = max(topWest, topEast)

                if (info.maxHy > maxNeighborTop + 14.0 && spanHy <= 56.0) {
                    val target =
                        when {
                            cellEast != null && abs(topEast - info.minHy) <= 14.0 -> cellEast to 3 // West edge of cellEast
                            cellWest != null && abs(topWest - info.minHy) <= 14.0 -> cellWest to 1 // East edge of cellWest
                            else -> null
                        }
                    if (target != null) {
                        val (coord, edgeIdx) = target
                        panelCandidates[coord.second * cols + coord.first][edgeIdx].add(info)
                    }
                } else {
                    val skirtOwner =
                        when {
                            cellWest != null && cellEast != null -> if (topWest >= topEast) cellWest else cellEast
                            cellWest != null -> cellWest
                            else -> cellEast
                        }
                    if (skirtOwner != null && info.maxHy <= cellMaxHy(skirtOwner.first, skirtOwner.second) + 10.0) {
                        skirtCandidates[skirtOwner.second * cols + skirtOwner.first].add(info)
                    }
                }
            }
        }

        // Materialize skirt patches (true 1h=16px / 2h=32px scale)
        for (idx in cells.indices) {
            val cands = skirtCandidates[idx]
            if (cands.isNotEmpty()) {
                val bestSkirt = selectBestVerticalGroup(cands)
                val rawSpan = bestSkirt.maxOf { it.maxHy } - bestSkirt.minOf { it.minHy }
                val skirtHeight = if (rawSpan > 28.0) 32 else 16
                val patch = sampleVerticalPatch(mapData, bestSkirt, 32, skirtHeight, PatchRole.SKIRT, preserveTransparency = false)
                if (patch != null) {
                    cells[idx].skirtPatchIdx = extractedPatches.size
                    extractedPatches.add(patch)
                }
            }
        }

        // Propagate direct skirt textures to elevated/border cells lacking a direct skirt hit
        val directSkirts = IntArray(cells.size) { cells[it].skirtPatchIdx ?: -1 }
        val directCells = cells.indices.filter { directSkirts[it] >= 0 }
        if (directCells.isNotEmpty()) {
            val defaultSkirtIdx =
                directCells
                    .groupingBy { directSkirts[it] }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val idx = r * cols + c
                    if (directSkirts[idx] >= 0) continue
                    val cell = cells[idx]
                    val neighborOffsets = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)
                    val exposesSkirt =
                        cell.archetype != ShapeArchetype.PRIM_FLAT ||
                            neighborOffsets.any { (dc, dr) ->
                                val nc = c + dc
                                val nr = r + dr
                                if (nc !in 0 until cols || nr !in 0 until rows) {
                                    cell.relativeBase1h > 0
                                } else {
                                    cell.relativeBase1h > cells[nr * cols + nc].relativeBase1h
                                }
                            }
                    if (!exposesSkirt) continue

                    val bestNeighbor =
                        directCells
                            .filter { oc ->
                                val oCell = cells[oc]
                                abs(oCell.col - c) + abs(oCell.row - r) <= 4
                            }.minByOrNull { oc ->
                                val oCell = cells[oc]
                                val manhattan = abs(oCell.col - c) + abs(oCell.row - r)
                                manhattan * 24.0 + abs(oCell.rawFloorHy - cell.rawFloorHy)
                            }
                    cells[idx].skirtPatchIdx = bestNeighbor?.let { directSkirts[it] } ?: defaultSkirtIdx
                }
            }
        }

        // Materialize foliage crosses (take priority over edge panels on flat cells)
        for (idx in cells.indices) {
            val cands = foliageCandidates[idx]
            val cell = cells[idx]
            if (cands.isNotEmpty() && cell.archetype == ShapeArchetype.PRIM_FLAT && !cell.isWater) {
                val bestFoliage = selectBestVerticalGroup(cands)
                val patch = sampleVerticalPatch(mapData, bestFoliage, 16, 32, PatchRole.FOLIAGE, preserveTransparency = true)
                if (patch != null) {
                    cells[idx] = cell.copy(archetype = ShapeArchetype.PRIM_FOLIAGE_CROSS)
                    cells[idx].panelPatchIdx = extractedPatches.size
                    extractedPatches.add(patch)
                }
            }
        }

        // Materialize edge panels
        for (idx in cells.indices) {
            if (cells[idx].archetype == ShapeArchetype.PRIM_FOLIAGE_CROSS) continue
            val edgeLists = panelCandidates[idx]
            val allPanelTris = edgeLists.flatMap { it }
            if (allPanelTris.isEmpty()) continue
            val north = edgeLists[0].isNotEmpty()
            val east = edgeLists[1].isNotEmpty()
            val south = edgeLists[2].isNotEmpty()
            val west = edgeLists[3].isNotEmpty()
            val maxSpan = allPanelTris.maxOf { it.maxHy - cells[idx].rawFloorHy }
            val tall2h = maxSpan >= 34.0
            val bestPanelGroup = selectBestVerticalGroup(allPanelTris)
            val patchH = if (tall2h) 32 else 16
            val patch = sampleVerticalPatch(mapData, bestPanelGroup, 32, patchH, PatchRole.PANEL, preserveTransparency = true)
            if (patch != null) {
                cells[idx].panels = EdgePanels(north = north, east = east, south = south, west = west, tall2h = tall2h)
                cells[idx].panelPatchIdx = extractedPatches.size
                extractedPatches.add(patch)
            }
        }
    }

    private fun estimateTransparencyRatio(
        mapData: FftMapData,
        tri: FftTexturedTriangle,
    ): Double {
        val uMin = min(tri.uv0.u, min(tri.uv1.u, tri.uv2.u))
        val uMax = max(tri.uv0.u, max(tri.uv1.u, tri.uv2.u))
        val vMin = min(tri.uv0.v, min(tri.uv1.v, tri.uv2.v))
        val vMax = max(tri.uv0.v, max(tri.uv1.v, tri.uv2.v))
        var total = 0
        var transparent = 0
        for (v in vMin..vMax step 2) {
            for (u in uMin..uMax step 2) {
                total++
                if (mapData.sampleColor(tri.page, u, v, tri.paletteIndex) == null) {
                    transparent++
                }
            }
        }
        return if (total == 0) 0.0 else transparent.toDouble() / total.toDouble()
    }

    private fun selectBestVerticalGroup(tris: List<TriInfo>): List<TriInfo> {
        val grouped =
            tris.groupBy { info ->
                val spanX = info.maxX - info.minX
                val spanZ = info.maxZ - info.minZ
                if (spanX <= spanZ) {
                    "X:${((info.minX + info.maxX) * 0.5 / 6.0).roundToInt()}"
                } else {
                    "Z:${((info.minZ + info.maxZ) * 0.5 / 6.0).roundToInt()}"
                }
            }
        return grouped.values.maxByOrNull { group ->
            val hSpan = group.maxOf { it.maxHy } - group.minOf { it.minHy }
            val horizSpan =
                if (group.first().let { (it.maxX - it.minX) <= (it.maxZ - it.minZ) }) {
                    group.maxOf { it.maxZ } - group.minOf { it.minZ }
                } else {
                    group.maxOf { it.maxX } - group.minOf { it.minX }
                }
            hSpan * max(1.0, horizSpan) * group.size
        } ?: tris
    }

    private fun sampleVerticalPatch(
        mapData: FftMapData,
        tris: List<TriInfo>,
        width: Int,
        height: Int,
        role: PatchRole,
        preserveTransparency: Boolean,
    ): ColorPatch? {
        if (tris.isEmpty()) return null
        val spanX = tris.maxOf { it.maxX } - tris.minOf { it.minX }
        val spanZ = tris.maxOf { it.maxZ } - tris.minOf { it.minZ }
        val useX = spanX >= spanZ
        val sMin = if (useX) tris.minOf { it.minX } else tris.minOf { it.minZ }
        val sMax = if (useX) tris.maxOf { it.maxX } else tris.maxOf { it.maxZ }
        val hyMin = tris.minOf { it.minHy }
        val hyMax = tris.maxOf { it.maxHy }
        val hyBottom =
            if (role == PatchRole.SKIRT) {
                val window = if (height >= 32) 48.0 else 24.0
                max(hyMin, hyMax - window)
            } else {
                hyMin
            }
        if (sMax - sMin < 1e-3 || hyMax - hyBottom < 1e-3) return null

        val pixels = arrayOfNulls<SnesColor>(width * height)
        var opaqueHits = 0
        var transHits = 0

        for (py in 0 until height) {
            val hy = hyMax - (py + 0.5) * (hyMax - hyBottom) / height.toDouble()
            for (px in 0 until width) {
                val s = sMin + (px + 0.5) * (sMax - sMin) / width.toDouble()
                for (info in tris) {
                    val t = info.tri
                    val s0 = if (useX) t.p0.x.toDouble() else t.p0.z.toDouble()
                    val s1 = if (useX) t.p1.x.toDouble() else t.p1.z.toDouble()
                    val s2 = if (useX) t.p2.x.toDouble() else t.p2.z.toDouble()
                    val w =
                        barycentric2d(s0, t.p0.hy.toDouble(), s1, t.p1.hy.toDouble(), s2, t.p2.hy.toDouble(), s, hy)
                            ?: continue
                    val u = (w.first * t.uv0.u + w.second * t.uv1.u + w.third * t.uv2.u).roundToInt()
                    val v = (w.first * t.uv0.v + w.second * t.uv1.v + w.third * t.uv2.v).roundToInt()
                    val color = mapData.sampleColor(t.page, u, v, t.paletteIndex)
                    pixels[py * width + px] = color
                    if (color != null) opaqueHits++ else transHits++
                    break
                }
            }
        }

        if (opaqueHits == 0) return null
        val hasTrans = preserveTransparency && transHits > 0
        if (!hasTrans && opaqueHits < width * height) {
            fillMissingOpaquePixels(pixels, width, height)
        }
        return ColorPatch(width, height, hasTransparency = hasTrans, role = role, pixels = pixels)
    }

    private fun barycentric2d(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        px: Double,
        py: Double,
    ): Triple<Double, Double, Double>? {
        val denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if (abs(denom) < 1e-6) return null
        val w0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / denom
        val w1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / denom
        val w2 = 1.0 - w0 - w1
        val eps = -2e-3
        if (w0 < eps || w1 < eps || w2 < eps) return null
        return Triple(w0, w1, w2)
    }

    private fun fillMissingOpaquePixels(
        pixels: Array<SnesColor?>,
        width: Int,
        height: Int,
    ) {
        val validCoords = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (pixels[y * width + x] != null) validCoords.add(x to y)
            }
        }
        if (validCoords.isEmpty()) return
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (pixels[y * width + x] == null) {
                    val nearest = validCoords.minBy { (vx, vy) -> (vx - x) * (vx - x) + (vy - y) * (vy - y) }
                    pixels[y * width + x] = pixels[nearest.second * width + nearest.first]
                }
            }
        }
    }

    private fun clusterPatchesToBudget(
        patches: List<ColorPatch>,
        cells: List<CellGeometry>,
    ): Pair<List<ColorPatch>, IntArray> {
        val usage = IntArray(patches.size)
        for (cell in cells) {
            cell.topPatchIdx?.let { usage[it]++ }
            cell.skirtPatchIdx?.let { usage[it]++ }
            cell.panelPatchIdx?.let { usage[it]++ }
        }

        // Step A: exact deduplication
        val unique = mutableListOf<ColorPatch>()
        val remap = IntArray(patches.size)
        val exactMap = HashMap<ColorPatch, Int>()
        for (i in patches.indices) {
            val p = patches[i]
            val existing = exactMap[p]
            if (existing != null) {
                remap[i] = existing
            } else {
                val newIdx = unique.size
                exactMap[p] = newIdx
                unique.add(p)
                remap[i] = newIdx
            }
        }

        // Track cluster membership & weighted usage per unique cluster
        val clusterMembers = Array(unique.size) { idx -> mutableListOf(idx) }
        val activeClusters = unique.indices.toMutableList()

        fun clusterAreaBytes(): Int = activeClusters.sumOf { unique[it].width * unique[it].height }

        // Step B: agglomerative medoid merging while over Bank $71 budget
        while (activeClusters.size > MAX_PATCH_CLUSTERS || clusterAreaBytes() > MAX_PATCH_BYTES_BUDGET) {
            var bestA = -1
            var bestB = -1
            var bestDist = Double.POSITIVE_INFINITY

            for (ii in activeClusters.indices) {
                val a = activeClusters[ii]
                val pa = unique[a]
                for (jj in ii + 1 until activeClusters.size) {
                    val b = activeClusters[jj]
                    val pb = unique[b]
                    if (pa.similarityKey() != pb.similarityKey()) continue
                    if (pa.role != pb.role) continue
                    val d = patchDistance(pa, pb)
                    if (d < bestDist) {
                        bestDist = d
                        bestA = a
                        bestB = b
                    }
                }
            }

            if (bestA < 0) {
                // Allow merging across roles (except foliage/panels with different dims/alpha) if needed
                for (ii in activeClusters.indices) {
                    val a = activeClusters[ii]
                    val pa = unique[a]
                    for (jj in ii + 1 until activeClusters.size) {
                        val b = activeClusters[jj]
                        val pb = unique[b]
                        if (pa.similarityKey() != pb.similarityKey()) continue
                        val d = patchDistance(pa, pb)
                        if (d < bestDist) {
                            bestDist = d
                            bestA = a
                            bestB = b
                        }
                    }
                }
            }

            if (bestA < 0) break

            // Merge bestB into bestA and choose medoid minimizing total distance to all members
            val merged = clusterMembers[bestA] + clusterMembers[bestB]
            val medoidIdx =
                merged.minBy { cand ->
                    merged.sumOf { other -> patchDistance(unique[cand], unique[other]) }
                }
            clusterMembers[medoidIdx] = merged.toMutableList()
            activeClusters.remove(bestA)
            activeClusters.remove(bestB)
            activeClusters.add(medoidIdx)
        }

        val finalList = activeClusters.map { unique[it] }
        val uniqueToFinal = IntArray(unique.size)
        for ((finalIdx, medoidUniqueIdx) in activeClusters.withIndex()) {
            for (memberUniqueIdx in clusterMembers[medoidUniqueIdx]) {
                uniqueToFinal[memberUniqueIdx] = finalIdx
            }
        }
        val finalRemap = IntArray(patches.size) { origIdx -> uniqueToFinal[remap[origIdx]] }
        return finalList to finalRemap
    }

    private fun patchDistance(
        a: ColorPatch,
        b: ColorPatch,
    ): Double {
        var sum = 0.0
        for (i in a.pixels.indices) {
            val ca = a.pixels[i]
            val cb = b.pixels[i]
            sum +=
                when {
                    ca == null && cb == null -> 0.0
                    ca == null || cb == null -> 2000.0
                    else -> ca.distanceSquared(cb).toDouble()
                }
        }
        return sum / a.pixels.size.toDouble()
    }

    private fun quantizeWeightedPalette(
        patches: List<ColorPatch>,
        patchWeights: IntArray,
    ): SnesPalette {
        val weightedColors = LinkedHashMap<SnesColor, Int>()
        for (idx in patches.indices) {
            val weight = max(1, patchWeights[idx])
            for (c in patches[idx].pixels) {
                if (c != null) {
                    weightedColors[c] = (weightedColors[c] ?: 0) + weight
                }
            }
        }
        if (weightedColors.isEmpty()) {
            return SnesPalette()
        }

        if (weightedColors.size <= 15) {
            var pal = SnesPalette()
            for (slot in 1..15) pal = pal.unassignSlot(slot)
            weightedColors.keys.forEachIndexed { idx, color ->
                pal = pal.withSlot(idx + 1, color, assigned = true)
            }
            return pal
        }

        // Weighted Median-Cut into 15 buckets
        data class WeightedColor(
            val color: SnesColor,
            val weight: Int,
        )

        fun boxLargestRange(items: List<WeightedColor>): Int {
            val rr = items.maxOf { it.color.r } - items.minOf { it.color.r }
            val rg = items.maxOf { it.color.g } - items.minOf { it.color.g }
            val rb = items.maxOf { it.color.b } - items.minOf { it.color.b }
            return maxOf(rr, rg, rb)
        }

        fun splitBox(items: List<WeightedColor>): Pair<List<WeightedColor>, List<WeightedColor>> {
            val rr = items.maxOf { it.color.r } - items.minOf { it.color.r }
            val rg = items.maxOf { it.color.g } - items.minOf { it.color.g }
            val rb = items.maxOf { it.color.b } - items.minOf { it.color.b }
            val sorted =
                when {
                    rg >= rr && rg >= rb -> items.sortedBy { it.color.g }
                    rb >= rr && rb >= rg -> items.sortedBy { it.color.b }
                    else -> items.sortedBy { it.color.r }
                }
            val totalWeight = sorted.sumOf { it.weight.toLong() }
            var acc = 0L
            var splitIdx = 1
            for (i in 0 until sorted.lastIndex) {
                acc += sorted[i].weight
                if (acc * 2L >= totalWeight) {
                    splitIdx = i + 1
                    break
                }
            }
            splitIdx = splitIdx.coerceIn(1, sorted.lastIndex)
            return sorted.subList(0, splitIdx) to sorted.subList(splitIdx, sorted.size)
        }

        val allItems = weightedColors.entries.map { WeightedColor(it.key, it.value) }
        val boxes = mutableListOf(allItems)
        while (boxes.size < 15) {
            val target = boxes.filter { it.size > 1 }.maxByOrNull { boxLargestRange(it) } ?: break
            boxes.remove(target)
            val (left, right) = splitBox(target)
            boxes.add(left)
            if (right.isNotEmpty()) boxes.add(right)
        }

        fun weightedMean(items: List<WeightedColor>): SnesColor {
            var sr = 0L
            var sg = 0L
            var sb = 0L
            var sw = 0L
            for (item in items) {
                val w = item.weight.toLong()
                sr += item.color.r * w
                sg += item.color.g * w
                sb += item.color.b * w
                sw += w
            }
            if (sw == 0L) return SnesColor.OPAQUE_BLACK
            val r = (sr.toDouble() / sw).roundToInt().coerceIn(0, 31)
            val g = (sg.toDouble() / sw).roundToInt().coerceIn(0, 31)
            val b = (sb.toDouble() / sw).roundToInt().coerceIn(0, 31)
            return if (r == 0 && g == 0 && b == 0) SnesColor.OPAQUE_BLACK else SnesColor(r, g, b)
        }

        var centroids = boxes.map { weightedMean(it) }.toMutableList()
        // 5 iterations of weighted k-means refinement
        repeat(5) {
            val buckets = Array(centroids.size) { mutableListOf<WeightedColor>() }
            for (item in allItems) {
                var bestIdx = 0
                var bestDist = Int.MAX_VALUE
                for (ci in centroids.indices) {
                    val d = centroids[ci].distanceSquared(item.color)
                    if (d < bestDist) {
                        bestDist = d
                        bestIdx = ci
                    }
                }
                buckets[bestIdx].add(item)
            }
            for (ci in centroids.indices) {
                if (buckets[ci].isNotEmpty()) {
                    centroids[ci] = weightedMean(buckets[ci])
                }
            }
        }

        val uniqueCentroids = centroids.distinctBy { it.bgr555 }.take(15)
        var pal = SnesPalette()
        for (slot in 1..15) pal = pal.unassignSlot(slot)
        uniqueCentroids.forEachIndexed { idx, color ->
            pal = pal.withSlot(idx + 1, color, assigned = true)
        }
        return pal
    }

    private fun assignDescriptorNames(patches: List<ColorPatch>): List<String> {
        val counters = HashMap<PatchRole, Int>()
        return patches.map { patch ->
            val n = counters.getOrDefault(patch.role, 0)
            counters[patch.role] = n + 1
            "${patch.role.prefix}_${n.toString().padStart(2, '0')}"
        }
    }

    private fun toSourceTexture(
        name: String,
        patch: ColorPatch,
        palette: SnesPalette,
    ): SourceTexture {
        val bytes = ByteArray(patch.width * patch.height)
        for (i in patch.pixels.indices) {
            val c = patch.pixels[i]
            val slot = if (c == null) 0 else palette.findNearestAssignedIndex(c)
            bytes[i] = (slot and 0x0F).toByte()
        }
        return SourceTexture(
            name = name,
            width = patch.width,
            height = patch.height,
            indexedPixels = bytes,
        )
    }

    private fun packAtlasWithinBank71(textures: List<SourceTexture>): TextureAtlas {
        val totalArea = textures.sumOf { it.width * it.height }
        val candidates =
            buildList {
                if (totalArea <= 16_384) add(128 to 128)
                add(128 to 256)
                add(256 to 128)
            }
        for ((w, h) in candidates) {
            val packed = TextureAtlas(width = w, height = h, textures = textures).autoPack(16)
            if (packed != null) return packed
        }
        error("Packed FFT textures (${textures.size} tiles, $totalArea bytes) exceed 32 KB Bank \$71 atlas budget.")
    }
}
