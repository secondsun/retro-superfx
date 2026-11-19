package dev.secondsun.sfxstudio.core.fft

import dev.secondsun.sfxstudio.core.atlas.SnesColor
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.ceil

/** 3D point in FFT map space where [hy] is positive-up (`-rawY`). */
data class FftPoint3(
    val x: Int,
    val hy: Int,
    val z: Int,
)

/** 2D texel coordinate inside a 256x256 PSX texture page. */
data class FftUv(
    val u: Int,
    val v: Int,
)

/** Static textured triangle extracted from an FFT `PrimaryMesh` section. */
data class FftTexturedTriangle(
    val p0: FftPoint3,
    val p1: FftPoint3,
    val p2: FftPoint3,
    val uv0: FftUv,
    val uv1: FftUv,
    val uv2: FftUv,
    val paletteIndex: Int,
    val page: Int,
)

/** Single tile entry from the FFT tactical terrain table (`locTerrain`). */
data class FftTerrainTile(
    val surfaceType: Int,
    val elevationHalf: Int,
    val slopeRiseHalf: Int,
    val slopeMask: Int,
    val impassable: Boolean,
) {
    val isWater: Boolean
        get() = surfaceType in WATER_SURFACE_TYPES

    companion object {
        val WATER_SURFACE_TYPES = setOf(0x07, 0x08, 0x09, 0x0B, 0x18, 0x1A)
    }
}

/** 2-layer FFT tactical terrain grid (`Level 0` ground + `Level 1` upper bridges). */
data class FftTerrainGrid(
    val cols: Int,
    val rows: Int,
    val level0: List<FftTerrainTile>,
    val level1: List<FftTerrainTile>,
) {
    fun tileAt(
        col: Int,
        row: Int,
        layer: Int = 0,
    ): FftTerrainTile {
        require(col in 0 until cols && row in 0 until rows) {
            "Terrain tile ($col, $row) out of bounds (${cols}x$rows)"
        }
        val list = if (layer == 0) level0 else level1
        return list[row * cols + col]
    }
}

/** Static polygon (3-vertex triangle or 4-vertex Z-strip quad) from FFT `PrimaryMesh`. */
data class FftPrimaryPolygon(
    val vertices: List<FftPoint3>,
    val uvs: List<FftUv>,
    val paletteIndex: Int,
    val page: Int,
)

/** Parsed raw FFT polygons, textures, unlit CLUTs, and background colors. */
data class FftPolygonMapData(
    val mapId: Int,
    val texelIndices: ByteArray,
    val palettes: List<List<SnesColor?>>,
    val polygons: List<FftPrimaryPolygon>,
    val terrain: FftTerrainGrid,
    val bgTop: SnesColor,
    val bgBottom: SnesColor,
) {
    fun sampleColor(
        page: Int,
        u: Int,
        v: Int,
        paletteIndex: Int,
    ): SnesColor? = samplePsxSheetColor(texelIndices, palettes, page, u, v, paletteIndex)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FftPolygonMapData) return false
        return mapId == other.mapId &&
            texelIndices.contentEquals(other.texelIndices) &&
            palettes == other.palettes &&
            polygons == other.polygons &&
            terrain == other.terrain &&
            bgTop == other.bgTop &&
            bgBottom == other.bgBottom
    }

    override fun hashCode(): Int {
        var result = mapId
        result = 31 * result + texelIndices.contentHashCode()
        result = 31 * result + palettes.hashCode()
        result = 31 * result + polygons.hashCode()
        result = 31 * result + terrain.hashCode()
        result = 31 * result + bgTop.hashCode()
        result = 31 * result + bgBottom.hashCode()
        return result
    }
}

internal fun samplePsxSheetColor(
    texelIndices: ByteArray,
    palettes: List<List<SnesColor?>>,
    page: Int,
    u: Int,
    v: Int,
    paletteIndex: Int,
): SnesColor? {
    val clampedU = u.coerceIn(0, FftIsoParser.TEXTURE_WIDTH - 1)
    val clampedV = v.coerceIn(0, 255)
    val sheetV = (page and 0x03) * 256 + clampedV
    val texelIdx = texelIndices[sheetV * FftIsoParser.TEXTURE_WIDTH + clampedU].toInt() and 0x0F
    return palettes[paletteIndex and 0x0F][texelIdx]
}

/** Parsed static map resources from a Final Fantasy Tactics PSX disc image. */
data class FftMapData(
    val mapId: Int,
    val texelIndices: ByteArray,
    val palettes: List<List<SnesColor?>>,
    val triangles: List<FftTexturedTriangle>,
    val terrain: FftTerrainGrid,
    val bgTop: SnesColor,
    val bgBottom: SnesColor,
) {
    fun sampleColor(
        page: Int,
        u: Int,
        v: Int,
        paletteIndex: Int,
    ): SnesColor? = samplePsxSheetColor(texelIndices, palettes, page, u, v, paletteIndex)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FftMapData) return false
        return mapId == other.mapId &&
            texelIndices.contentEquals(other.texelIndices) &&
            palettes == other.palettes &&
            triangles == other.triangles &&
            terrain == other.terrain &&
            bgTop == other.bgTop &&
            bgBottom == other.bgBottom
    }

    override fun hashCode(): Int {
        var result = mapId
        result = 31 * result + texelIndices.contentHashCode()
        result = 31 * result + palettes.hashCode()
        result = 31 * result + triangles.hashCode()
        result = 31 * result + terrain.hashCode()
        result = 31 * result + bgTop.hashCode()
        result = 31 * result + bgBottom.hashCode()
        return result
    }
}

internal data class FftGnsRecord(
    val arrangement: Int,
    val time: Int,
    val weather: Int,
    val type: Int,
    val sector: Int,
    val length: Int,
)

/**
 * Reads Mode 2 Form 1 sectors, `.GNS` scenario directories, static geometry (`locPrimaryMesh`),
 * unlit 15-bit CLUTs (`locTexturePalettesColor`), and tactical grids (`locTerrain`) from a
 * Final Fantasy Tactics PSX `.bin` image without loading the full disc into memory.
 */
object FftIsoParser {
    const val SECTOR_SIZE = 2048
    const val SECTOR_RAW_SIZE = 2352
    const val SECTOR_HEADER_SIZE = 24

    const val RECORD_TYPE_TEXTURE = 0x1701
    const val RECORD_TYPE_MESH_PRIMARY = 0x2E01
    const val RECORD_TYPE_MESH_OVERRIDE = 0x2F01
    const val RECORD_TYPE_MESH_ALT = 0x3001
    const val RECORD_TYPE_END = 0x3101
    const val GNS_RECORD_BYTES = 20
    private const val MAX_GNS_RECORDS = 256

    const val TEXTURE_WIDTH = 256
    const val TEXTURE_HEIGHT = 1024
    const val TEXTURE_LEN = TEXTURE_WIDTH * TEXTURE_HEIGHT
    const val TEXTURE_RAW_LEN = TEXTURE_LEN / 2

    private const val MESH_HEADER_BYTES = 8
    private const val LOC_PRIMARY_MESH = 0x40
    private const val LOC_PALETTES_COLOR = 0x44
    private const val LOC_LIGHTS_AND_BG = 0x64
    private const val LOC_TERRAIN = 0x68
    private const val TERRAIN_ROW_STRIDE = 16
    private const val TERRAIN_LAYER_BYTES = 16 * 16 * 8

    // Sector offsets for MAP000.GNS .. MAP125.GNS in the US PSX release
    val GNS_SECTORS =
        longArrayOf(
            10026,
            11304,
            12656,
            12938,
            13570,
            14239,
            14751,
            15030,
            15595,
            16262,
            16347,
            16852,
            17343,
            17627,
            18175,
            19510,
            20075,
            20162,
            20745,
            21411,
            21692,
            22270,
            22938,
            23282,
            23557,
            23899,
            23988,
            24266,
            24544,
            24822,
            25099,
            25764,
            26042,
            26229,
            26362,
            27028,
            27643,
            27793,
            28467,
            28555,
            29165,
            29311,
            29653,
            29807,
            30473,
            30622,
            30966,
            31697,
            32365,
            33032,
            33701,
            34349,
            34440,
            34566,
            34647,
            34745,
            35350,
            35436,
            35519,
            35603,
            35683,
            35765,
            36052,
            36394,
            36530,
            36612,
            37214,
            37817,
            38386,
            38473,
            38622,
            39288,
            39826,
            40120,
            40724,
            41391,
            41865,
            42532,
            43200,
            43295,
            43901,
            44569,
            45044,
            45164,
            45829,
            46498,
            47167,
            47260,
            47928,
            48595,
            49260,
            49538,
            50108,
            50387,
            50554,
            51120,
            51416,
            52082,
            52749,
            53414,
            53502,
            53579,
            53659,
            54273,
            54359,
            54528,
            54621,
            54716,
            54812,
            54909,
            55004,
            55097,
            55192,
            55286,
            55383,
            56051,
            56123,
            56201,
            56279,
            56356,
            0,
            0,
            0,
            0,
            0,
            56435,
        )

    fun parseMap(
        isoFile: File,
        mapId: Int,
    ): FftMapData {
        val raw = parsePolygonMap(isoFile, mapId)
        return toTriangulatedMapData(raw)
    }

    fun parsePolygonMap(
        isoFile: File,
        mapId: Int,
    ): FftPolygonMapData {
        require(isoFile.exists()) { "FFT ISO file '${isoFile.path}' does not exist." }
        require(mapId in GNS_SECTORS.indices) {
            "FFT map ID $mapId out of valid range 0..${GNS_SECTORS.lastIndex}."
        }
        val gnsSector = GNS_SECTORS[mapId]
        require(gnsSector > 0L) {
            "FFT map ID $mapId has no GNS sector entry (empty slot)."
        }

        RandomAccessFile(isoFile, "r").use { raf ->
            val records = readGnsRecords(raf, gnsSector)
            val meshRec =
                selectMeshRecord(raf, records)
                    ?: error("FFT MAP${mapId.toString().padStart(3, '0')} contains no valid primary/override mesh record.")
            val texRec =
                selectTextureRecord(records, meshRec)
                    ?: error("FFT MAP${mapId.toString().padStart(3, '0')} contains no texture record.")

            val rawTex = readSectors(raf, texRec.sector.toLong(), texRec.length)
            val meshBytes = readSectors(raf, meshRec.sector.toLong(), meshRec.length)
            return parsePolygonMapSections(
                mapId = mapId,
                rawTextureBytes = rawTex,
                meshFileBytes = meshBytes,
            )
        }
    }

    internal fun parseMapSections(
        mapId: Int,
        rawTextureBytes: ByteArray,
        meshFileBytes: ByteArray,
    ): FftMapData = toTriangulatedMapData(parsePolygonMapSections(mapId, rawTextureBytes, meshFileBytes))

    internal fun parsePolygonMapSections(
        mapId: Int,
        rawTextureBytes: ByteArray,
        meshFileBytes: ByteArray,
    ): FftPolygonMapData {
        val texelIndices = unpack4bppTextureSheet(rawTextureBytes)
        val palettes = parsePalettes(meshFileBytes)
        val polygons = parseStaticPrimaryPolygons(meshFileBytes)
        val terrain = parseTerrain(meshFileBytes)
        val (bgTop, bgBottom) = parseBackgroundColors(meshFileBytes)
        return FftPolygonMapData(
            mapId = mapId,
            texelIndices = texelIndices,
            palettes = palettes,
            polygons = polygons,
            terrain = terrain,
            bgTop = bgTop,
            bgBottom = bgBottom,
        )
    }

    private fun toTriangulatedMapData(raw: FftPolygonMapData): FftMapData {
        val triangles = ArrayList<FftTexturedTriangle>(raw.polygons.size * 2)
        for (poly in raw.polygons) {
            val pts = poly.vertices
            val uvs = poly.uvs
            triangles.add(
                FftTexturedTriangle(
                    p0 = pts[0],
                    p1 = pts[1],
                    p2 = pts[2],
                    uv0 = uvs[0],
                    uv1 = uvs[1],
                    uv2 = uvs[2],
                    paletteIndex = poly.paletteIndex,
                    page = poly.page,
                ),
            )
            if (pts.size == 4) {
                triangles.add(
                    FftTexturedTriangle(
                        p0 = pts[1],
                        p1 = pts[3],
                        p2 = pts[2],
                        uv0 = uvs[1],
                        uv1 = uvs[3],
                        uv2 = uvs[2],
                        paletteIndex = poly.paletteIndex,
                        page = poly.page,
                    ),
                )
            }
        }
        return FftMapData(
            mapId = raw.mapId,
            texelIndices = raw.texelIndices,
            palettes = raw.palettes,
            triangles = triangles,
            terrain = raw.terrain,
            bgTop = raw.bgTop,
            bgBottom = raw.bgBottom,
        )
    }

    internal fun readSectors(
        raf: RandomAccessFile,
        startSector: Long,
        byteLength: Int,
    ): ByteArray {
        require(byteLength >= 0) { "Negative byte length: $byteLength" }
        if (byteLength == 0) return ByteArray(0)
        val sectorCount = ceil(byteLength.toDouble() / SECTOR_SIZE).toInt()
        val out = ByteArray(byteLength)
        var written = 0
        val sectorBuf = ByteArray(SECTOR_SIZE)
        for (i in 0 until sectorCount) {
            val fileOffset = (startSector + i) * SECTOR_RAW_SIZE + SECTOR_HEADER_SIZE
            raf.seek(fileOffset)
            raf.readFully(sectorBuf)
            val toCopy = minOf(SECTOR_SIZE, byteLength - written)
            System.arraycopy(sectorBuf, 0, out, written, toCopy)
            written += toCopy
        }
        return out
    }

    internal fun readGnsRecords(
        raf: RandomAccessFile,
        gnsSector: Long,
    ): List<FftGnsRecord> {
        raf.seek(gnsSector * SECTOR_RAW_SIZE + SECTOR_HEADER_SIZE)
        val records = mutableListOf<FftGnsRecord>()
        val recBuf = ByteArray(GNS_RECORD_BYTES)
        for (idx in 0 until MAX_GNS_RECORDS) {
            raf.readFully(recBuf)
            val record = parseGnsRecord(recBuf)
            if (record.type == RECORD_TYPE_END) {
                break
            }
            records.add(record)
        }
        return records
    }

    internal fun parseGnsRecord(buf: ByteArray): FftGnsRecord {
        require(buf.size >= GNS_RECORD_BYTES) { "Expected $GNS_RECORD_BYTES-byte GNS record, got ${buf.size}" }
        val arrangement = buf[2].toInt() and 0xFF
        val flags = buf[3].toInt() and 0xFF
        val time = (flags ushr 7) and 0x01
        val weather = (flags ushr 4) and 0x07
        val type = u16le(buf, 4)
        val sector = u16le(buf, 8)
        val length = u32le(buf, 12).toInt()
        return FftGnsRecord(
            arrangement = arrangement,
            time = time,
            weather = weather,
            type = type,
            sector = sector,
            length = length,
        )
    }

    internal fun selectMeshRecord(
        raf: RandomAccessFile,
        records: List<FftGnsRecord>,
    ): FftGnsRecord? {
        fun hasPrimaryGeometry(rec: FftGnsRecord): Boolean {
            if (rec.length < 0xB4) return false
            val header = readSectors(raf, rec.sector.toLong(), minOf(rec.length, 0xB4))
            return u32le(header, LOC_PRIMARY_MESH) != 0L
        }

        val primaries = records.filter { it.type == RECORD_TYPE_MESH_PRIMARY && hasPrimaryGeometry(it) }
        primaries.firstOrNull { it.arrangement == 0 && it.time == 0 && it.weather == 0 }?.let { return it }
        primaries.firstOrNull()?.let { return it }

        val overrides = records.filter { it.type == RECORD_TYPE_MESH_OVERRIDE && hasPrimaryGeometry(it) }
        overrides.firstOrNull { it.arrangement == 0 && it.time == 0 && it.weather == 0 }?.let { return it }
        return overrides.firstOrNull()
    }

    internal fun selectTextureRecord(
        records: List<FftGnsRecord>,
        meshRecord: FftGnsRecord,
    ): FftGnsRecord? {
        val textures = records.filter { it.type == RECORD_TYPE_TEXTURE && it.length >= TEXTURE_RAW_LEN }
        textures
            .firstOrNull {
                it.arrangement == meshRecord.arrangement &&
                    it.time == meshRecord.time &&
                    it.weather == meshRecord.weather
            }?.let { return it }
        textures
            .firstOrNull {
                it.time == meshRecord.time && it.weather == meshRecord.weather
            }?.let { return it }
        return textures.firstOrNull()
    }

    internal fun unpack4bppTextureSheet(raw: ByteArray): ByteArray {
        val out = ByteArray(TEXTURE_LEN)
        val limit = minOf(raw.size, TEXTURE_RAW_LEN)
        for (i in 0 until limit) {
            val b = raw[i].toInt() and 0xFF
            out[i * 2] = (b and 0x0F).toByte()
            out[i * 2 + 1] = ((b ushr 4) and 0x0F).toByte()
        }
        return out
    }

    internal fun parsePalettes(meshBytes: ByteArray): List<List<SnesColor?>> {
        val palPtr = u32le(meshBytes, LOC_PALETTES_COLOR).toInt()
        require(palPtr > 0 && palPtr + 16 * 16 * 2 <= meshBytes.size) {
            "Invalid FFT palette pointer $palPtr"
        }
        return List(16) { palIdx ->
            List(16) { slot ->
                val word = u16le(meshBytes, palPtr + (palIdx * 16 + slot) * 2)
                decodePsxRgb15(word)
            }
        }
    }

    internal fun decodePsxRgb15(word: Int): SnesColor? {
        if (word == 0) return null
        val r = word and 0x1F
        val g = (word ushr 5) and 0x1F
        val b = (word ushr 10) and 0x1F
        return if (r == 0 && g == 0 && b == 0) {
            SnesColor.OPAQUE_BLACK
        } else {
            SnesColor(r, g, b)
        }
    }

    internal fun parseStaticPrimaryPolygons(meshBytes: ByteArray): List<FftPrimaryPolygon> {
        val ptrMesh = u32le(meshBytes, LOC_PRIMARY_MESH).toInt()
        require(ptrMesh > 0 && ptrMesh + MESH_HEADER_BYTES <= meshBytes.size) {
            "Invalid FFT primary mesh pointer $ptrMesh"
        }

        val nTriTex = u16le(meshBytes, ptrMesh)
        val nQuadTex = u16le(meshBytes, ptrMesh + 2)
        val nTriUntex = u16le(meshBytes, ptrMesh + 4)
        val nQuadUntex = u16le(meshBytes, ptrMesh + 6)

        var off = ptrMesh + MESH_HEADER_BYTES
        val triVerts =
            List(nTriTex) {
                val p0 = readPoint3(meshBytes, off)
                val p1 = readPoint3(meshBytes, off + 6)
                val p2 = readPoint3(meshBytes, off + 12)
                off += 18
                listOf(p0, p1, p2)
            }
        val quadVerts =
            List(nQuadTex) {
                val p0 = readPoint3(meshBytes, off)
                val p1 = readPoint3(meshBytes, off + 6)
                val p2 = readPoint3(meshBytes, off + 12)
                val p3 = readPoint3(meshBytes, off + 18)
                off += 24
                listOf(p0, p1, p2, p3)
            }

        // Skip untextured border skirts (Q, R) and textured polygon normals
        off += (nTriUntex * 3 + nQuadUntex * 4) * 6
        off += (nTriTex * 3 + nQuadTex * 4) * 6

        val out = ArrayList<FftPrimaryPolygon>(nTriTex + nQuadTex)
        for (i in 0 until nTriTex) {
            val u0 = u8(meshBytes, off)
            val v0 = u8(meshBytes, off + 1)
            val pal = u8(meshBytes, off + 2) and 0x0F
            val u1 = u8(meshBytes, off + 4)
            val v1 = u8(meshBytes, off + 5)
            val page = u8(meshBytes, off + 6) and 0x03
            val u2 = u8(meshBytes, off + 8)
            val v2 = u8(meshBytes, off + 9)
            off += 10

            out.add(
                FftPrimaryPolygon(
                    vertices = triVerts[i],
                    uvs = listOf(FftUv(u0, v0), FftUv(u1, v1), FftUv(u2, v2)),
                    paletteIndex = pal,
                    page = page,
                ),
            )
        }

        for (i in 0 until nQuadTex) {
            val u0 = u8(meshBytes, off)
            val v0 = u8(meshBytes, off + 1)
            val pal = u8(meshBytes, off + 2) and 0x0F
            val u1 = u8(meshBytes, off + 4)
            val v1 = u8(meshBytes, off + 5)
            val page = u8(meshBytes, off + 6) and 0x03
            val u2 = u8(meshBytes, off + 8)
            val v2 = u8(meshBytes, off + 9)
            val u3 = u8(meshBytes, off + 10)
            val v3 = u8(meshBytes, off + 11)
            off += 12

            out.add(
                FftPrimaryPolygon(
                    vertices = quadVerts[i],
                    uvs = listOf(FftUv(u0, v0), FftUv(u1, v1), FftUv(u2, v2), FftUv(u3, v3)),
                    paletteIndex = pal,
                    page = page,
                ),
            )
        }

        return out
    }

    internal fun parseTerrain(meshBytes: ByteArray): FftTerrainGrid {
        val ptrTerrain = u32le(meshBytes, LOC_TERRAIN).toInt()
        require(ptrTerrain > 0 && ptrTerrain + 2 + TERRAIN_LAYER_BYTES * 2 <= meshBytes.size) {
            "Invalid FFT terrain pointer $ptrTerrain"
        }
        val cols = u8(meshBytes, ptrTerrain)
        val rows = u8(meshBytes, ptrTerrain + 1)
        require(cols in 1..TERRAIN_ROW_STRIDE && rows in 1..TERRAIN_ROW_STRIDE) {
            "Unsupported FFT terrain grid dimensions ${cols}x$rows"
        }

        val level0Base = ptrTerrain + 2
        val level1Base = level0Base + TERRAIN_LAYER_BYTES
        val level0 = ArrayList<FftTerrainTile>(cols * rows)
        val level1 = ArrayList<FftTerrainTile>(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cellOff = (r * TERRAIN_ROW_STRIDE + c) * 8
                level0.add(readTerrainTile(meshBytes, level0Base + cellOff))
                level1.add(readTerrainTile(meshBytes, level1Base + cellOff))
            }
        }
        return FftTerrainGrid(cols = cols, rows = rows, level0 = level0, level1 = level1)
    }

    private fun readTerrainTile(
        bytes: ByteArray,
        off: Int,
    ): FftTerrainTile {
        val b0 = u8(bytes, off)
        val b2 = u8(bytes, off + 2)
        val b3 = u8(bytes, off + 3)
        val b4 = u8(bytes, off + 4)
        val b6 = u8(bytes, off + 6)
        return FftTerrainTile(
            surfaceType = b0 and 0x3F,
            elevationHalf = b2,
            slopeRiseHalf = b3,
            slopeMask = b4,
            impassable = (b0 == 0) || ((b6 and 0x01) != 0),
        )
    }

    internal fun parseBackgroundColors(meshBytes: ByteArray): Pair<SnesColor, SnesColor> {
        val ptr = u32le(meshBytes, LOC_LIGHTS_AND_BG).toInt()
        if (ptr <= 0 || ptr + 45 > meshBytes.size) {
            return SnesColor.OPAQUE_BLACK to SnesColor.OPAQUE_BLACK
        }
        // 3 directional lights: 9 i16 colors (18 B) + 9 i16 positions (18 B) + 3 B ambient RGB8
        val bgOff = ptr + 36 + 3
        val top = SnesColor.fromRgb888(u8(meshBytes, bgOff), u8(meshBytes, bgOff + 1), u8(meshBytes, bgOff + 2))
        val bot = SnesColor.fromRgb888(u8(meshBytes, bgOff + 3), u8(meshBytes, bgOff + 4), u8(meshBytes, bgOff + 5))
        return top to bot
    }

    private fun readPoint3(
        bytes: ByteArray,
        off: Int,
    ): FftPoint3 =
        FftPoint3(
            x = i16le(bytes, off),
            hy = -i16le(bytes, off + 2),
            z = i16le(bytes, off + 4),
        )

    private fun u8(
        bytes: ByteArray,
        off: Int,
    ): Int = bytes[off].toInt() and 0xFF

    private fun u16le(
        bytes: ByteArray,
        off: Int,
    ): Int = (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)

    private fun i16le(
        bytes: ByteArray,
        off: Int,
    ): Int {
        val u = u16le(bytes, off)
        return if ((u and 0x8000) != 0) u or -0x10000 else u
    }

    private fun u32le(
        bytes: ByteArray,
        off: Int,
    ): Long =
        (bytes[off].toLong() and 0xFFL) or
            ((bytes[off + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[off + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[off + 3].toLong() and 0xFFL) shl 24)
}
