package dev.secondsun.sfxstudio.core.fft

import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.mesh.HybridMeshDocument
import dev.secondsun.sfxstudio.core.mesh.MeshFace
import dev.secondsun.sfxstudio.core.mesh.MeshUv
import dev.secondsun.sfxstudio.core.mesh.MeshVertex
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Result of importing a PSX FFT stage into a [HybridMeshDocument] + `256 x 128` hero sheet. */
data class ImportedFftMesh(
    val meshDocument: HybridMeshDocument,
    val heroSheet: ByteArray,
    val bgTopColor: SnesColor,
    val bgBottomColor: SnesColor,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImportedFftMesh) return false
        return meshDocument == other.meshDocument &&
            heroSheet.contentEquals(other.heroSheet) &&
            bgTopColor == other.bgTopColor &&
            bgBottomColor == other.bgBottomColor
    }

    override fun hashCode(): Int {
        var result = meshDocument.hashCode()
        result = 31 * result + heroSheet.contentHashCode()
        result = 31 * result + bgTopColor.hashCode()
        result = 31 * result + bgBottomColor.hashCode()
        return result
    }
}

/**
 * Lowers FFT `PrimaryMesh` polygons directly into a Bank-`$71`-compatible [HybridMeshDocument]:
 * - Low-detail/uniform faces become fast directionally-shaded flat triangles/quads.
 * - High-contrast or alpha-cutout hero faces extract 1:1 native PSX UV sub-rectangles into
 *   a `256 x 128` (`32 KB`) unlit 4bpp indexed sheet with a chroma-preserving 15-color palette.
 */
object FftMeshImporter {
    private const val HERO_STD_DEV_MIN = 42.0
    private const val HERO_CHANNEL_STD_MIN = 26.0
    private const val MAX_FACE_BUDGET = 600
    private const val MAX_VERT_BUDGET = 600
    private const val MAX_UV_SPAN = 64
    private val LIGHT_DIR = normalize3(0.45, 0.75, -0.48)

    private data class DPoint3(
        val x: Double,
        val hy: Double,
        val z: Double,
    )

    private data class DUv(
        val u: Int,
        val v: Int,
    )

    private data class WorkingFace(
        val pts: List<DPoint3>,
        val uvs: List<DUv>,
        val page: Int,
        val paletteIndex: Int,
        val hasCutout: Boolean,
        val stdDev: Double,
        val maxChStd: Double,
        val shadeRgb: IntArray,
        val heroSampleColors: List<IntArray>,
        val isHeroCandidate: Boolean,
        val rawUMin: Int,
        val rawVMin: Int,
        val rawUMax: Int,
        val rawVMax: Int,
    )

    private data class PackedHeroRect(
        val page: Int,
        val paletteIndex: Int,
        val uMin: Int,
        val vMin: Int,
        val uMax: Int,
        val vMax: Int,
        val packX: Int,
        val packY: Int,
    ) {
        val width: Int get() = uMax - uMin + 1
        val height: Int get() = vMax - vMin + 1

        fun contains(
            page: Int,
            paletteIndex: Int,
            u0: Int,
            v0: Int,
            u1: Int,
            v1: Int,
        ): Boolean =
            this.page == page &&
                this.paletteIndex == paletteIndex &&
                this.uMin <= u0 &&
                this.uMax >= u1 &&
                this.vMin <= v0 &&
                this.vMax >= v1
    }

    fun importFromIso(
        isoFile: File,
        mapId: Int,
        meshName: String,
        defaultYaw: Int? = null,
        scaleMultiplier: Double = 1.0,
    ): ImportedFftMesh {
        val raw = FftIsoParser.parsePolygonMap(isoFile, mapId)
        val chosenYaw = defaultYaw ?: defaultYawForMap(mapId)
        return convertPolygonMap(
            raw = raw,
            meshName = meshName,
            defaultYaw = chosenYaw,
            scaleMultiplier = scaleMultiplier,
        )
    }

    internal fun convertPolygonMap(
        raw: FftPolygonMapData,
        meshName: String,
        defaultYaw: Int = 75,
        scaleMultiplier: Double = 1.0,
    ): ImportedFftMesh {
        require(defaultYaw in 0..119) { "defaultYaw must be in 0..119, got $defaultYaw" }
        require(scaleMultiplier in 0.25..2.5) { "scaleMultiplier must be in 0.25..2.5, got $scaleMultiplier" }

        // 1. Convert raw polygons to perimeter-CW winding (`cross2d > 0` when front-facing)
        //    and filter degenerate zero-UV shadow caps.
        val cwPolys =
            raw.polygons.mapNotNull { poly ->
                if (poly.uvs.distinct().size <= 1) return@mapNotNull null
                val pts =
                    if (poly.vertices.size == 4) {
                        listOf(poly.vertices[0], poly.vertices[2], poly.vertices[3], poly.vertices[1])
                    } else {
                        listOf(poly.vertices[0], poly.vertices[2], poly.vertices[1])
                    }
                val uvs =
                    if (poly.uvs.size == 4) {
                        listOf(poly.uvs[0], poly.uvs[2], poly.uvs[3], poly.uvs[1])
                    } else {
                        listOf(poly.uvs[0], poly.uvs[2], poly.uvs[1])
                    }
                Triple(
                    pts.map { DPoint3(it.x.toDouble(), it.hy.toDouble(), it.z.toDouble()) },
                    uvs.map { DUv(it.u and 0xFF, it.v and 0xFF) },
                    poly.page to poly.paletteIndex,
                )
            }
        require(cwPolys.isNotEmpty()) { "FFT map ${raw.mapId} has no valid static polygons." }

        // 2. Choose the finest 3D subdivision span threshold that stays inside vertex/face budgets.
        val subdivided = subdivideWithinBudget(cwPolys)

        // 3. Classify faces into hero-textured vs directionally-shaded flat.
        val workingFaces = subdivided.mapNotNull { classifyFace(raw, it.first, it.second, it.third.first, it.third.second) }
        require(workingFaces.isNotEmpty()) { "FFT map ${raw.mapId} produced 0 visible faces." }

        // 4. Pack hero UV rectangles 1:1 into the 256x128 sheet (1px gutter between blocks).
        val (packedRects, faceAssignments) = packHeroRectangles(workingFaces)

        // 5. Quantize a sublinear chroma-preserving 15-color BGR555 palette across shaded flat faces
        //    and unlit hero texels sampled strictly inside accepted hero triangles.
        val palette = quantizeHybridPalette(workingFaces, faceAssignments)

        // 6. Populate 256x128 indexed hero sheet (`0` = transparent, `1..15` = palette slot).
        val heroSheet = ByteArray(HybridMeshDocument.HERO_SHEET_BYTES)
        for (rect in packedRects) {
            for (dy in 0 until rect.height) {
                val srcV = rect.vMin + dy
                val dstRow = (rect.packY + dy) * HybridMeshDocument.HERO_SHEET_WIDTH
                for (dx in 0 until rect.width) {
                    val srcU = rect.uMin + dx
                    val col = raw.sampleColor(rect.page, srcU, srcV, rect.paletteIndex)
                    val slot = if (col == null) 0 else findNearestSlot(palette, col)
                    heroSheet[dstRow + rect.packX + dx] = slot.toByte()
                }
            }
        }

        // 7. Compute centered bounding box and scale that fills 256px width nicely while keeping spire unclipped.
        val (center, scale) = computeCenterAndScale(workingFaces, defaultYaw, scaleMultiplier)
        val vertexMap = LinkedHashMap<MeshVertex, Int>()
        val vertices = ArrayList<MeshVertex>()

        fun internVertex(p: DPoint3): Int {
            val qx =
                ((p.x - center.x) * scale * 64.0).roundToInt().coerceIn(
                    -HybridMeshDocument.MAX_Q106_ABS,
                    HybridMeshDocument.MAX_Q106_ABS,
                )
            val qy =
                ((p.hy - center.hy) * scale * 64.0).roundToInt().coerceIn(
                    -HybridMeshDocument.MAX_Q106_ABS,
                    HybridMeshDocument.MAX_Q106_ABS,
                )
            val qz =
                ((p.z - center.z) * scale * 64.0).roundToInt().coerceIn(
                    -HybridMeshDocument.MAX_Q106_ABS,
                    HybridMeshDocument.MAX_Q106_ABS,
                )
            val key = MeshVertex(qx, qy, qz)
            return vertexMap.getOrPut(key) {
                val id = vertices.size
                vertices.add(key)
                id
            }
        }

        val meshFaces =
            workingFaces.mapIndexed { idx, wf ->
                val vIndices = wf.pts.map(::internVertex)
                val rect = faceAssignments[idx]
                if (rect != null) {
                    val mappedUvs =
                        wf.uvs.map { uv ->
                            MeshUv(
                                u = (rect.packX + uv.u - rect.uMin).coerceIn(0, HybridMeshDocument.HERO_SHEET_WIDTH - 1),
                                v = (rect.packY + uv.v - rect.vMin).coerceIn(0, HybridMeshDocument.HERO_SHEET_HEIGHT - 1),
                            )
                        }
                    MeshFace(
                        indices = vIndices,
                        isTextured = true,
                        flatColorIndex = 0,
                        uvs = mappedUvs,
                    )
                } else {
                    val shadeColor = SnesColor.fromRgb888(wf.shadeRgb[0], wf.shadeRgb[1], wf.shadeRgb[2])
                    val slot = findNearestSlot(palette, shadeColor)
                    MeshFace(
                        indices = vIndices,
                        isTextured = false,
                        flatColorIndex = slot,
                    )
                }
            }

        val doc =
            HybridMeshDocument(
                name = meshName,
                mapId = raw.mapId,
                defaultYaw = defaultYaw,
                defaultCenterY = DEFAULT_CENTER_Y,
                palette = palette,
                vertices = vertices,
                faces = meshFaces,
            )
        val validation = doc.validate()
        require(validation.valid) {
            "Imported FFT mesh failed validation: ${validation.errors.joinToString("; ")}"
        }

        return ImportedFftMesh(
            meshDocument = doc,
            heroSheet = heroSheet,
            bgTopColor = raw.bgTop,
            bgBottomColor = raw.bgBottom,
        )
    }

    private fun subdivideWithinBudget(
        polys: List<Triple<List<DPoint3>, List<DUv>, Pair<Int, Int>>>,
    ): List<Triple<List<DPoint3>, List<DUv>, Pair<Int, Int>>> {
        val candidateSpans = listOf(48.0, 56.0, 68.0, 84.0, 128.0)
        for (spanLimit in candidateSpans) {
            val out = ArrayList<Triple<List<DPoint3>, List<DUv>, Pair<Int, Int>>>(polys.size)
            val uniqVerts = HashSet<Triple<Int, Int, Int>>()
            for ((pts, uvs, pgPal) in polys) {
                val pieces = subdivideRecursive(pts, uvs, spanLimit, depth = 0)
                for ((p, u) in pieces) {
                    out.add(Triple(p, u, pgPal))
                    for (pt in p) {
                        uniqVerts.add(Triple(pt.x.roundToInt(), pt.hy.roundToInt(), pt.z.roundToInt()))
                    }
                }
            }
            if (out.size <= MAX_FACE_BUDGET && uniqVerts.size <= MAX_VERT_BUDGET) {
                return out
            }
        }
        return polys.take(MAX_FACE_BUDGET)
    }

    private fun subdivideRecursive(
        pts: List<DPoint3>,
        uvs: List<DUv>,
        maxWorldSpan: Double,
        depth: Int,
    ): List<Pair<List<DPoint3>, List<DUv>>> {
        if (depth >= 2) return listOf(pts to uvs)
        val worldSpan =
            maxOf(
                pts.maxOf { it.x } - pts.minOf { it.x },
                pts.maxOf { it.hy } - pts.minOf { it.hy },
                pts.maxOf { it.z } - pts.minOf { it.z },
            )
        val uvSpan =
            max(
                uvs.maxOf { it.u } - uvs.minOf { it.u },
                uvs.maxOf { it.v } - uvs.minOf { it.v },
            )
        if (worldSpan <= maxWorldSpan && uvSpan <= MAX_UV_SPAN) {
            return listOf(pts to uvs)
        }

        fun midP(
            a: DPoint3,
            b: DPoint3,
        ): DPoint3 = DPoint3((a.x + b.x) * 0.5, (a.hy + b.hy) * 0.5, (a.z + b.z) * 0.5)

        fun midU(
            a: DUv,
            b: DUv,
        ): DUv = DUv((a.u + b.u) / 2, (a.v + b.v) / 2)

        return if (pts.size == 4) {
            val v0 = pts[0]
            val v1 = pts[1]
            val v2 = pts[2]
            val v3 = pts[3]
            val v01 = midP(v0, v1)
            val v12 = midP(v1, v2)
            val v23 = midP(v2, v3)
            val v30 = midP(v3, v0)
            val vc = midP(v01, v23)

            val t0 = uvs[0]
            val t1 = uvs[1]
            val t2 = uvs[2]
            val t3 = uvs[3]
            val t01 = midU(t0, t1)
            val t12 = midU(t1, t2)
            val t23 = midU(t2, t3)
            val t30 = midU(t3, t0)
            val tc = midU(t01, t23)

            listOf(
                listOf(v0, v01, vc, v30) to listOf(t0, t01, tc, t30),
                listOf(v01, v1, v12, vc) to listOf(t01, t1, t12, tc),
                listOf(vc, v12, v2, v23) to listOf(tc, t12, t2, t23),
                listOf(v30, vc, v23, v3) to listOf(t30, tc, t23, t3),
            ).flatMap { subdivideRecursive(it.first, it.second, maxWorldSpan, depth + 1) }
        } else {
            val v0 = pts[0]
            val v1 = pts[1]
            val v2 = pts[2]
            val m01 = midP(v0, v1)
            val m12 = midP(v1, v2)
            val m20 = midP(v2, v0)

            val t0 = uvs[0]
            val t1 = uvs[1]
            val t2 = uvs[2]
            val u01 = midU(t0, t1)
            val u12 = midU(t1, t2)
            val u20 = midU(t2, t0)

            listOf(
                listOf(v0, m01, m20) to listOf(t0, u01, u20),
                listOf(m01, v1, m12) to listOf(u01, t1, u12),
                listOf(m20, m12, v2) to listOf(u20, u12, t2),
                listOf(m01, m12, m20) to listOf(u01, u12, u20),
            ).flatMap { subdivideRecursive(it.first, it.second, maxWorldSpan, depth + 1) }
        }
    }

    private fun classifyFace(
        raw: FftPolygonMapData,
        pts: List<DPoint3>,
        uvs: List<DUv>,
        page: Int,
        paletteIndex: Int,
    ): WorkingFace? {
        val opaqueCols = ArrayList<IntArray>()
        var transparentCount = 0

        fun sampleTri(
            u0: DUv,
            u1: DUv,
            u2: DUv,
        ) {
            for (a in 0 until 7) {
                for (b in 0 until (7 - a)) {
                    val w0 = (a + 0.33) / 7.0
                    val w1 = (b + 0.33) / 7.0
                    val w2 = 1.0 - w0 - w1
                    if (w2 < -1e-4) continue
                    val u = (w0 * u0.u + w1 * u1.u + w2 * u2.u).roundToInt().coerceIn(0, 255)
                    val v = (w0 * u0.v + w1 * u1.v + w2 * u2.v).roundToInt().coerceIn(0, 255)
                    val c = raw.sampleColor(page, u, v, paletteIndex)
                    if (c == null) {
                        transparentCount++
                    } else {
                        opaqueCols.add(intArrayOf(c.r shl 3, c.g shl 3, c.b shl 3))
                    }
                }
            }
        }

        sampleTri(uvs[0], uvs[1], uvs[2])
        if (pts.size == 4) {
            sampleTri(uvs[0], uvs[2], uvs[3])
        }
        if (opaqueCols.isEmpty()) return null

        val meanR = opaqueCols.sumOf { it[0] }.toDouble() / opaqueCols.size
        val meanG = opaqueCols.sumOf { it[1] }.toDouble() / opaqueCols.size
        val meanB = opaqueCols.sumOf { it[2] }.toDouble() / opaqueCols.size

        var varR = 0.0
        var varG = 0.0
        var varB = 0.0
        for (c in opaqueCols) {
            val dr = c[0] - meanR
            val dg = c[1] - meanG
            val db = c[2] - meanB
            varR += dr * dr
            varG += dg * dg
            varB += db * db
        }
        varR /= opaqueCols.size
        varG /= opaqueCols.size
        varB /= opaqueCols.size
        val stdDev = sqrt(varR + varG + varB)
        val maxChStd = sqrt(maxOf(varR, varG, varB))

        // Outward normal for perimeter-CW `(v0, v1, v2)`: `(v2 - v0) x (v1 - v0)` points +Y up on floors.
        val ax = pts[2].x - pts[0].x
        val ay = pts[2].hy - pts[0].hy
        val az = pts[2].z - pts[0].z
        val bx = pts[1].x - pts[0].x
        val by = pts[1].hy - pts[0].hy
        val bz = pts[1].z - pts[0].z
        val nx = ay * bz - az * by
        val ny = az * bx - ax * bz
        val nz = ax * by - ay * bx
        val nLen = sqrt(nx * nx + ny * ny + nz * nz)
        val ndotl =
            if (nLen > 1e-4) {
                ((nx / nLen) * LIGHT_DIR[0] + (ny / nLen) * LIGHT_DIR[1] + (nz / nLen) * LIGHT_DIR[2]).coerceIn(-1.0, 1.0)
            } else {
                0.0
            }
        val shade = 0.82 + 0.32 * ndotl
        val shadeRgb =
            intArrayOf(
                (meanR * shade).roundToInt().coerceIn(0, 248),
                (meanG * shade).roundToInt().coerceIn(0, 248),
                (meanB * shade).roundToInt().coerceIn(0, 248),
            )

        val uMin = uvs.minOf { it.u }
        val uMax = uvs.maxOf { it.u }
        val vMin = uvs.minOf { it.v }
        val vMax = uvs.maxOf { it.v }
        val hasCutout = transparentCount > 0
        val isHeroCandidate = hasCutout || (stdDev >= HERO_STD_DEV_MIN && maxChStd >= HERO_CHANNEL_STD_MIN)

        return WorkingFace(
            pts = pts,
            uvs = uvs,
            page = page,
            paletteIndex = paletteIndex,
            hasCutout = hasCutout,
            stdDev = stdDev,
            maxChStd = maxChStd,
            shadeRgb = shadeRgb,
            heroSampleColors = opaqueCols,
            isHeroCandidate = isHeroCandidate,
            rawUMin = uMin,
            rawVMin = vMin,
            rawUMax = uMax,
            rawVMax = vMax,
        )
    }

    private fun packHeroRectangles(faces: List<WorkingFace>): Pair<List<PackedHeroRect>, Array<PackedHeroRect?>> {
        data class HeroCandidateRect(
            val page: Int,
            val paletteIndex: Int,
            val uMin: Int,
            val vMin: Int,
            val uMax: Int,
            val vMax: Int,
            val priority: Double,
        ) {
            val width: Int get() = uMax - uMin + 1
            val height: Int get() = vMax - vMin + 1
        }

        val candidatesByKey = LinkedHashMap<List<Int>, HeroCandidateRect>()
        for (f in faces) {
            if (!f.isHeroCandidate) continue
            val key = listOf(f.page, f.paletteIndex, f.rawUMin, f.rawVMin, f.rawUMax, f.rawVMax)
            val priority = (if (f.hasCutout) 1000.0 else 0.0) + f.stdDev + 0.25 * max(0, f.rawUMax - f.rawUMin + 1)
            val existing = candidatesByKey[key]
            if (existing == null || priority > existing.priority) {
                candidatesByKey[key] =
                    HeroCandidateRect(
                        page = f.page,
                        paletteIndex = f.paletteIndex,
                        uMin = f.rawUMin,
                        vMin = f.rawVMin,
                        uMax = f.rawUMax,
                        vMax = f.rawVMax,
                        priority = priority,
                    )
            }
        }

        // Remove boxes strictly contained inside a larger box on the same (page, pal).
        val sortedByAreaDesc = candidatesByKey.values.sortedByDescending { it.width * it.height }
        val nonContained = ArrayList<HeroCandidateRect>(sortedByAreaDesc.size)
        for (cand in sortedByAreaDesc) {
            val alreadyCovered =
                nonContained.any { outer ->
                    outer.page == cand.page &&
                        outer.paletteIndex == cand.paletteIndex &&
                        outer.uMin <= cand.uMin &&
                        outer.uMax >= cand.uMax &&
                        outer.vMin <= cand.vMin &&
                        outer.vMax >= cand.vMax
                }
            if (!alreadyCovered) {
                nonContained.add(cand)
            }
        }

        // Sort highest-priority hero features first, then taller shelves for tighter shelf packing.
        val toPack = nonContained.sortedWith(compareByDescending<HeroCandidateRect> { it.priority }.thenByDescending { it.height })
        val packedRects = ArrayList<PackedHeroRect>()

        var cursorX = 0
        var cursorY = 0
        var shelfHeight = 0

        for (cand in toPack) {
            val w = cand.width
            val h = cand.height
            if (w > HybridMeshDocument.HERO_SHEET_WIDTH || h > HybridMeshDocument.HERO_SHEET_HEIGHT) continue

            val existingCover =
                packedRects.firstOrNull {
                    it.contains(cand.page, cand.paletteIndex, cand.uMin, cand.vMin, cand.uMax, cand.vMax)
                }
            if (existingCover != null) continue

            if (cursorX + w > HybridMeshDocument.HERO_SHEET_WIDTH) {
                cursorX = 0
                cursorY += shelfHeight + 1
                shelfHeight = 0
            }
            if (cursorY + h > HybridMeshDocument.HERO_SHEET_HEIGHT) {
                continue
            }

            packedRects.add(
                PackedHeroRect(
                    page = cand.page,
                    paletteIndex = cand.paletteIndex,
                    uMin = cand.uMin,
                    vMin = cand.vMin,
                    uMax = cand.uMax,
                    vMax = cand.vMax,
                    packX = cursorX,
                    packY = cursorY,
                ),
            )
            cursorX += w + 1
            if (h > shelfHeight) {
                shelfHeight = h
            }
        }

        val assignments =
            Array(faces.size) { idx ->
                val f = faces[idx]
                if (!f.isHeroCandidate) {
                    null
                } else {
                    packedRects.firstOrNull {
                        it.contains(f.page, f.paletteIndex, f.rawUMin, f.rawVMin, f.rawUMax, f.rawVMax)
                    }
                }
            }

        return packedRects to assignments
    }

    private fun quantizeHybridPalette(
        faces: List<WorkingFace>,
        faceAssignments: Array<PackedHeroRect?>,
    ): List<SnesColor> {
        val colorWeights = LinkedHashMap<Int, Double>()

        fun addRgbWeight(
            r8: Int,
            g8: Int,
            b8: Int,
            weight: Double,
        ) {
            val qr = (r8.coerceIn(0, 255) ushr 3) shl 3
            val qg = (g8.coerceIn(0, 255) ushr 3) shl 3
            val qb = (b8.coerceIn(0, 255) ushr 3) shl 3
            val key = (qr shl 16) or (qg shl 8) or qb
            colorWeights[key] = (colorWeights[key] ?: 0.0) + weight
        }

        for (idx in faces.indices) {
            val f = faces[idx]
            if (faceAssignments[idx] != null) {
                for (c in f.heroSampleColors) {
                    addRgbWeight(c[0], c[1], c[2], 2.0)
                }
            } else {
                addRgbWeight(f.shadeRgb[0], f.shadeRgb[1], f.shadeRgb[2], 8.0)
            }
        }

        val items =
            colorWeights.entries.map { (rgb, w) ->
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF
                intArrayOf(r, g, b) to sqrt(w)
            }

        val centers = ArrayList<IntArray>(15)
        if (items.isNotEmpty()) {
            val first = items.maxByOrNull { it.second }!!.first
            centers.add(first.copyOf())
            while (centers.size < 15 && centers.size < items.size) {
                var bestColor = items[0].first
                var bestScore = -1.0
                for ((col, sw) in items) {
                    var minDistSq = Double.MAX_VALUE
                    for (c in centers) {
                        val dr = (col[0] - c[0]).toDouble()
                        val dg = (col[1] - c[1]).toDouble()
                        val db = (col[2] - c[2]).toDouble()
                        val d2 = 2.0 * dr * dr + 4.0 * dg * dg + 3.0 * db * db
                        if (d2 < minDistSq) minDistSq = d2
                    }
                    val score = minDistSq * sw.pow(0.30)
                    if (score > bestScore) {
                        bestScore = score
                        bestColor = col
                    }
                }
                if (bestScore <= 0.0) break
                centers.add(bestColor.copyOf())
            }

            repeat(12) {
                val buckets = Array(centers.size) { ArrayList<Pair<IntArray, Double>>() }
                for (item in items) {
                    var bestIdx = 0
                    var bestDist = Double.MAX_VALUE
                    for (ci in centers.indices) {
                        val c = centers[ci]
                        val dr = (item.first[0] - c[0]).toDouble()
                        val dg = (item.first[1] - c[1]).toDouble()
                        val db = (item.first[2] - c[2]).toDouble()
                        val d2 = 2.0 * dr * dr + 4.0 * dg * dg + 3.0 * db * db
                        if (d2 < bestDist) {
                            bestDist = d2
                            bestIdx = ci
                        }
                    }
                    buckets[bestIdx].add(item)
                }
                for (ci in centers.indices) {
                    val b = buckets[ci]
                    if (b.isNotEmpty()) {
                        val tw = b.sumOf { it.second }
                        centers[ci] =
                            intArrayOf(
                                ((b.sumOf { it.first[0] * it.second } / tw).roundToInt().coerceIn(0, 248) ushr 3) shl 3,
                                ((b.sumOf { it.first[1] * it.second } / tw).roundToInt().coerceIn(0, 248) ushr 3) shl 3,
                                ((b.sumOf { it.first[2] * it.second } / tw).roundToInt().coerceIn(0, 248) ushr 3) shl 3,
                            )
                    }
                }
            }
        }

        val out = ArrayList<SnesColor>(16)
        out.add(SnesColor.TRANSPARENT)
        for (c in centers) {
            out.add(SnesColor.fromRgb888(c[0], c[1], c[2]))
        }
        while (out.size < 16) {
            out.add(SnesColor.OPAQUE_BLACK)
        }
        return out
    }

    private fun findNearestSlot(
        palette: List<SnesColor>,
        color: SnesColor,
    ): Int {
        var bestSlot = 1
        var bestDist = Int.MAX_VALUE
        for (slot in 1 until min(16, palette.size)) {
            val d = palette[slot].distanceSquared(color)
            if (d < bestDist) {
                bestDist = d
                bestSlot = slot
            }
        }
        return bestSlot
    }

    private fun computeCenterAndScale(
        faces: List<WorkingFace>,
        defaultYaw: Int,
        scaleMultiplier: Double,
    ): Pair<DPoint3, Double> {
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minHy = Double.POSITIVE_INFINITY
        var maxHy = Double.NEGATIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY

        val allPts = ArrayList<DPoint3>(faces.size * 4)
        for (f in faces) {
            for (p in f.pts) {
                allPts.add(p)
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.hy < minHy) minHy = p.hy
                if (p.hy > maxHy) maxHy = p.hy
                if (p.z < minZ) minZ = p.z
                if (p.z > maxZ) maxZ = p.z
            }
        }
        val centerX = (minX + maxX) * 0.5
        val midHy = (minHy + maxHy) * 0.5
        val centerZ = (minZ + maxZ) * 0.5
        val halfHySpan = (maxHy - minHy) * 0.5

        var maxHorizCoord = 1.0
        for (p in allPts) {
            val dx = p.x - centerX
            val dz = p.z - centerZ
            maxHorizCoord = maxOf(maxHorizCoord, abs(dx), abs(dz))
        }

        val maxExtent = maxOf(1.0, maxHorizCoord, halfHySpan)
        val baseScale = TARGET_CELL_WORLD_SIZE / FFT_CELL_WORLD_SIZE
        val scale = (baseScale * scaleMultiplier).coerceIn(0.20, 500.0 / maxExtent)

        // Center short stages vertically at Y=80; keep tall full-scale stages centered on midHy
        // so the ground/courtyard remains framed in view on boot.
        val rad = defaultYaw * (2.0 * PI / 120.0)
        val ca = cos(rad)
        val sa = sin(rad)
        var minRy = Double.POSITIVE_INFINITY
        var maxRy = Double.NEGATIVE_INFINITY
        for (p in allPts) {
            val rz = (p.x - centerX) * sa + (p.z - centerZ) * ca
            val ry = (rz * 0.5 - (p.hy - midHy) * 0.8660254) * scale
            if (ry < minRy) minRy = ry
            if (ry > maxRy) maxRy = ry
        }
        val shiftSy =
            if (maxRy - minRy <= 152.0) {
                -0.5 * (minRy + maxRy)
            } else {
                0.0
            }
        val maxDeltaHy = max(0.0, (500.0 / scale) - halfHySpan)
        val deltaHy = (shiftSy / (0.8660254 * scale)).coerceIn(-maxDeltaHy, maxDeltaHy)

        return DPoint3(centerX, midHy + deltaHy, centerZ) to scale
    }

    private fun defaultYawForMap(mapId: Int): Int =
        when (mapId) {
            56 -> 75 // Orbonne Monastery: front cathedral doors + viaduct + canal view
            else -> 15
        }

    private fun normalize3(
        x: Double,
        y: Double,
        z: Double,
    ): DoubleArray {
        val len = sqrt(x * x + y * y + z * z)
        return doubleArrayOf(x / len, y / len, z / len)
    }

    private const val DEFAULT_CENTER_Y = 80
    private const val FFT_CELL_WORLD_SIZE = 28.0
    private const val TARGET_CELL_WORLD_SIZE = 32.0
}
