package dev.secondsun.sfxstudio.core.fft

import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.LevelValidator
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FftLevelImporterTest {
    private fun putU16(
        buf: ByteBuffer,
        offset: Int,
        value: Int,
    ) {
        buf.putShort(offset, (value and 0xFFFF).toShort())
    }

    private fun putU32(
        buf: ByteBuffer,
        offset: Int,
        value: Int,
    ) {
        buf.putInt(offset, value)
    }

    private fun makeGnsRecord(
        arrangement: Int,
        time: Int,
        weather: Int,
        type: Int,
        sector: Int,
        length: Int,
    ): ByteArray {
        val rec = ByteArray(FftIsoParser.GNS_RECORD_BYTES)
        rec[2] = arrangement.toByte()
        rec[3] = (((time and 1) shl 7) or ((weather and 7) shl 4)).toByte()
        rec[4] = (type and 0xFF).toByte()
        rec[5] = ((type ushr 8) and 0xFF).toByte()
        rec[8] = (sector and 0xFF).toByte()
        rec[9] = ((sector ushr 8) and 0xFF).toByte()
        rec[12] = (length and 0xFF).toByte()
        rec[13] = ((length ushr 8) and 0xFF).toByte()
        rec[14] = ((length ushr 16) and 0xFF).toByte()
        rec[15] = ((length ushr 24) and 0xFF).toByte()
        return rec
    }

    private fun writeSectors(
        raf: RandomAccessFile,
        startSector: Long,
        payload: ByteArray,
    ) {
        var offset = 0
        var sector = startSector
        while (offset < payload.size) {
            raf.seek(sector * FftIsoParser.SECTOR_RAW_SIZE + FftIsoParser.SECTOR_HEADER_SIZE)
            val chunk = ByteArray(FftIsoParser.SECTOR_SIZE)
            val copyLen = minOf(FftIsoParser.SECTOR_SIZE, payload.size - offset)
            System.arraycopy(payload, offset, chunk, 0, copyLen)
            raf.write(chunk)
            offset += FftIsoParser.SECTOR_SIZE
            sector++
        }
    }

    private fun buildSyntheticMeshBytes(
        cols: Int = 4,
        rows: Int = 4,
    ): ByteArray {
        // Layout:
        //   0x000..0x0C3 (196B) header
        //   0x0C4..     primary mesh (1 flat quad on (0,0), 1 straight wedge quad on (1,0), 1 outer corner on (2,0))
        //   palettes    16 * 32 = 512B
        //   lights/bg   45B
        //   terrain     2 + 4096 = 4098B
        val meshStart = 196
        val nTriTex = 0
        val nQuadTex = 3
        val quadVertsBytes = nQuadTex * 4 * 6
        val normalsBytes = nQuadTex * 4 * 6
        val uvsBytes = nQuadTex * 12
        val meshSize = 8 + quadVertsBytes + normalsBytes + uvsBytes

        val palStart = meshStart + meshSize
        val lightsStart = palStart + 16 * 32
        val terrainStart = lightsStart + 45
        val totalLen = terrainStart + 2 + 16 * 16 * 8 * 2

        val bb = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        putU32(bb, 0x40, meshStart)
        putU32(bb, 0x44, palStart)
        putU32(bb, 0x64, lightsStart)
        putU32(bb, 0x68, terrainStart)

        // Mesh header: N=0, P=3, Q=0, R=0
        putU16(bb, meshStart, nTriTex)
        putU16(bb, meshStart + 2, nQuadTex)

        var off = meshStart + 8
        // Quad 0: flat cell (0, 0) at hy = 24 (raw y = -24)
        val q0 = listOf(intArrayOf(0, -24, 0), intArrayOf(28, -24, 0), intArrayOf(0, -24, 28), intArrayOf(28, -24, 28))
        // Quad 1: straight 1h wedge on cell (1, 0), North edge high (+24 -> hy=48, South at hy=24)
        val q1 = listOf(intArrayOf(28, -48, 0), intArrayOf(56, -48, 0), intArrayOf(28, -24, 28), intArrayOf(56, -24, 28))
        // Quad 2: flat border water tile at cell (0, 1) at hy = 24
        val q2 = listOf(intArrayOf(0, -24, 28), intArrayOf(28, -24, 28), intArrayOf(0, -24, 56), intArrayOf(28, -24, 56))
        for (q in listOf(q0, q1, q2)) {
            for (v in q) {
                bb.putShort(off, v[0].toShort())
                bb.putShort(off + 2, v[1].toShort())
                bb.putShort(off + 4, v[2].toShort())
                off += 6
            }
        }
        // Skip normals
        off += normalsBytes
        // Quad UVs: page=0, pal=0, region u=0..19, v=0..19
        for (i in 0 until nQuadTex) {
            bb.put(off + 0, 0)
            bb.put(off + 1, 0)
            bb.put(off + 2, 0) // pal 0
            bb.put(off + 4, 19)
            bb.put(off + 5, 0)
            bb.put(off + 6, 0) // page 0
            bb.put(off + 8, 0)
            bb.put(off + 9, 19)
            bb.put(off + 10, 19)
            bb.put(off + 11, 19)
            off += 12
        }

        // Palettes: pal 0 slot 0 = transparent (0), slot 1 = pure STP black (0x8000 -> clamped $0001), slot 2 = green, slot 3 = blue
        putU16(bb, palStart + 0, 0x0000)
        putU16(bb, palStart + 2, 0x8000)
        putU16(bb, palStart + 4, 0x03E0) // G=31
        putU16(bb, palStart + 6, 0x7C00) // B=31

        // Background gradient at lightsStart + 39
        bb.put(lightsStart + 39, 16)
        bb.put(lightsStart + 40, 32)
        bb.put(lightsStart + 41, 64)

        // Terrain header + Level 0 (stride 16)
        bb.put(terrainStart, cols.toByte())
        bb.put(terrainStart + 1, rows.toByte())
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cellOff = terrainStart + 2 + (r * 16 + c) * 8
                val surf = if (c == 0 && r == 1) 0x07 else 0x03 // (0, 1) is border water
                bb.put(cellOff, surf.toByte())
                bb.put(cellOff + 2, 2) // 2 half-units = 24 raw
            }
        }
        return bb.array()
    }

    @Test
    fun testDecodePsxRgb15ClampsStpBlackAndPreservesZeroTransparency() {
        assertNull(FftIsoParser.decodePsxRgb15(0x0000))
        assertEquals(SnesColor.OPAQUE_BLACK, FftIsoParser.decodePsxRgb15(0x8000))
        assertEquals(SnesColor(31, 0, 0), FftIsoParser.decodePsxRgb15(0x001F))
        assertEquals(SnesColor(0, 31, 0), FftIsoParser.decodePsxRgb15(0x03E0))
        assertEquals(SnesColor(0, 0, 31), FftIsoParser.decodePsxRgb15(0x7C00))
    }

    @Test
    fun testMode2IsoParsingSelectsDayClearScenarioAndImportsValidBank71Level(
        @TempDir tempDir: File,
    ) {
        val isoFile = File(tempDir, "synthetic_fft.bin")
        val gnsSector = FftIsoParser.GNS_SECTORS[0]
        val decoyTexSector = 200L
        val dayTexSector = 270L
        val dayMeshSector = 340L

        val decoyTex = ByteArray(FftIsoParser.TEXTURE_RAW_LEN) { 0x11 }
        val dayTex = ByteArray(FftIsoParser.TEXTURE_RAW_LEN) { 0x22 } // index 2 = bright green
        val meshBytes = buildSyntheticMeshBytes(cols = 4, rows = 4)

        RandomAccessFile(isoFile, "rw").use { raf ->
            // Write GNS directory with rainy/night decoy BEFORE clear day (0,0,0) scenario
            raf.seek(gnsSector * FftIsoParser.SECTOR_RAW_SIZE + FftIsoParser.SECTOR_HEADER_SIZE)
            raf.write(makeGnsRecord(0, 0, 2, FftIsoParser.RECORD_TYPE_TEXTURE, decoyTexSector.toInt(), decoyTex.size))
            raf.write(makeGnsRecord(0, 0, 0, FftIsoParser.RECORD_TYPE_TEXTURE, dayTexSector.toInt(), dayTex.size))
            raf.write(makeGnsRecord(0, 0, 0, FftIsoParser.RECORD_TYPE_MESH_PRIMARY, dayMeshSector.toInt(), meshBytes.size))
            raf.write(makeGnsRecord(0, 0, 0, FftIsoParser.RECORD_TYPE_END, 0, 0))

            writeSectors(raf, decoyTexSector, decoyTex)
            writeSectors(raf, dayTexSector, dayTex)
            writeSectors(raf, dayMeshSector, meshBytes)
        }

        val parsed = FftIsoParser.parseMap(isoFile, mapId = 0)
        // Must have selected day clear texture (index 2) rather than rainy decoy (index 1)
        assertEquals(2, parsed.texelIndices[0].toInt() and 0x0F)
        assertEquals(4, parsed.terrain.cols)
        assertEquals(4, parsed.terrain.rows)

        val imported =
            FftLevelImporter.importFromIso(
                isoFile = isoFile,
                mapId = 0,
                atlasName = "TEST_FFT_ATLAS",
                levelName = "test_fft",
                atlasRelativePath = "TEST_FFT_ATLAS.sfxproj",
            )

        assertTrue(imported.atlasDocument.atlas.sizeBytes <= LevelValidator.BANK_71_MAX_ATLAS_BYTES)
        assertTrue(imported.atlasDocument.validate(warnBank71Overflow = true).valid)

        val validation = LevelValidator.validate(imported.levelDocument, imported.atlasDocument)
        assertTrue(validation.valid)
        assertEquals(0, validation.warnings.count { it.code == "UNFLUSH_LIQUID_SURFACE" })

        val grid = imported.levelDocument.grid
        assertEquals(ShapeArchetype.PRIM_FLAT, grid.cellAt(0, 0).archetype)
        assertEquals(ShapeArchetype.PRIM_WEDGE_STRAIGHT, grid.cellAt(1, 0).archetype)
        assertEquals(CellRotation.ROT_0, grid.cellAt(1, 0).rotation)
        // Border water at (0, 1) must fall back to PRIM_FLAT to avoid unflush liquid warnings
        assertEquals(ShapeArchetype.PRIM_FLAT, grid.cellAt(0, 1).archetype)
    }
}
