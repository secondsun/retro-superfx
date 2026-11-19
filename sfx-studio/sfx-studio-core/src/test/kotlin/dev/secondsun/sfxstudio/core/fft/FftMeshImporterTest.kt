package dev.secondsun.sfxstudio.core.fft

import dev.secondsun.sfxstudio.core.atlas.DescriptorTable
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.mesh.HybridMeshDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class FftMeshImporterTest {
    private fun buildSyntheticPolygonMap(): FftPolygonMapData {
        val sheet = ByteArray(FftIsoParser.TEXTURE_LEN)
        // Region 0 (u=0..23, v=0..23): uniform green -> low variance -> flat shaded
        for (v in 0 until 24) {
            for (u in 0 until 24) {
                sheet[v * FftIsoParser.TEXTURE_WIDTH + u] = if ((u + v) % 2 == 0) 2 else 3
            }
        }
        // Region 1 (u=32..55, v=0..31): high-contrast red/white brickwork with slot-0 transparent border
        for (v in 0 until 32) {
            for (u in 32 until 56) {
                val texel =
                    when {
                        v < 3 && (u < 35 || u > 52) -> 0 // transparent cutout corners
                        v % 8 == 0 || (u - 32) % 8 == 0 -> 1 // crisp white mortar / bright accent
                        else -> 4 // dark red/brown stone
                    }
                sheet[v * FftIsoParser.TEXTURE_WIDTH + u] = texel.toByte()
            }
        }

        val pal0 =
            listOf(
                null, // 0: transparent
                SnesColor(31, 31, 31), // 1: bright white
                SnesColor(6, 16, 4), // 2: moss green A
                SnesColor(7, 17, 4), // 3: moss green B (close in color -> low stdDev)
                SnesColor(18, 5, 4), // 4: dark brick red
            ) + List(11) { SnesColor.OPAQUE_BLACK }

        // Quad 0: large horizontal grass field using FFT Z-strip order (`(p1-p0)x(p2-p0) = +Y`)
        val grassQuad =
            FftPrimaryPolygon(
                vertices =
                    listOf(
                        FftPoint3(0, 0, 0),
                        FftPoint3(0, 0, 84),
                        FftPoint3(84, 0, 0),
                        FftPoint3(84, 0, 84),
                    ),
                uvs = listOf(FftUv(0, 0), FftUv(0, 23), FftUv(23, 0), FftUv(23, 23)),
                paletteIndex = 0,
                page = 0,
            )

        // Quad 1: vertical high-contrast hero archway wall facing +Z (`(p1-p0)x(p2-p0) = +Z`)
        val archQuad =
            FftPrimaryPolygon(
                vertices =
                    listOf(
                        FftPoint3(28, 48, 84),
                        FftPoint3(28, 0, 84),
                        FftPoint3(56, 48, 84),
                        FftPoint3(56, 0, 84),
                    ),
                uvs = listOf(FftUv(32, 0), FftUv(32, 31), FftUv(55, 0), FftUv(55, 31)),
                paletteIndex = 0,
                page = 0,
            )

        val tile = FftTerrainTile(surfaceType = 3, elevationHalf = 0, slopeRiseHalf = 0, slopeMask = 0, impassable = false)
        val grid = FftTerrainGrid(cols = 4, rows = 4, level0 = List(16) { tile }, level1 = List(16) { tile })

        return FftPolygonMapData(
            mapId = 56,
            texelIndices = sheet,
            palettes = listOf(pal0) + List(15) { pal0 },
            polygons = listOf(grassQuad, archQuad),
            terrain = grid,
            bgTop = SnesColor(2, 4, 8),
            bgBottom = SnesColor(1, 2, 4),
        )
    }

    @Test
    fun testHybridClassificationWindingAndPacking(
        @TempDir tempDir: File,
    ) {
        val raw = buildSyntheticPolygonMap()
        val imported = FftMeshImporter.convertPolygonMap(raw, meshName = "SYNTH_MESH", defaultYaw = 75)
        val doc = imported.meshDocument

        assertTrue(doc.validate().valid)
        // Grass quad (span 84) subdivided into 4 flat faces; hero arch quad remains textured.
        val flatFaces = doc.faces.filter { !it.isTextured }
        val texFaces = doc.faces.filter { it.isTextured }
        assertTrue(flatFaces.size >= 4, "Expected subdivided flat grass faces, got ${flatFaces.size}")
        assertTrue(texFaces.isNotEmpty(), "Expected high-contrast cutout arch face to remain textured")
        assertTrue(flatFaces.all { it.flatColorIndex in 1..15 })

        // Verify CW front-facing projection sign (`cross2d > 0`) on at least the upward ground quads at yaw=15
        val rad = 15 * (2.0 * PI / 120.0)
        val sinQ = (sin(rad) * 16384.0).roundToInt()
        val cosQ = (cos(rad) * 16384.0).roundToInt()
        val px =
            IntArray(doc.vertices.size) { i ->
                val v = doc.vertices[i]
                ((((v.xQ106 * cosQ) shr 16) - ((v.zQ106 * sinQ) shr 16) + 8) shr 4) + 128
            }
        val py =
            IntArray(doc.vertices.size) { i ->
                val v = doc.vertices[i]
                val rz = ((v.xQ106 * sinQ) shr 16) + ((v.zQ106 * cosQ) shr 16)
                ((((rz shr 1) - ((v.yQ106 * 0x376D) shr 16)) + 8) shr 4) + 80
            }
        val positiveFloorFaces =
            flatFaces.count { f ->
                val i0 = f.indices[0]
                val i1 = f.indices[1]
                val i2 = f.indices[2]
                val cross =
                    (px[i1] - px[i0]).toLong() * (py[i2] - py[i0]).toLong() -
                        (py[i1] - py[i0]).toLong() * (px[i2] - px[i0]).toLong()
                cross > 0L
            }
        assertEquals(flatFaces.size, positiveFloorFaces, "All upward flat ground faces must project CW (cross > 0)")

        // Export and verify `.msh`, `.bin`, `.tab`, `.pal`, `_preview.png`, and JSON roundtrip
        val meshFile = File(tempDir, "synth.sfxmesh")
        val atlasPrefix = File(tempDir, "SYNTH_ATLAS")
        val assets = doc.saveAndExport(meshFile, atlasPrefix, imported.heroSheet)

        assertTrue(assets.mshFile.exists())
        assertTrue(assets.binFile.exists())
        assertEquals(HybridMeshDocument.HERO_SHEET_BYTES.toLong(), assets.binFile.length())
        assertTrue(assets.tabFile.exists())
        assertTrue(assets.palFile.exists())
        assertEquals(32L, assets.palFile.length())
        assertTrue(assets.previewPngFile.exists())
        assertTrue(assets.sheetPngFile.exists())

        val parsedTab = DescriptorTable.parseTabBinary(assets.tabFile.readBytes())
        assertEquals(1, parsedTab.entryCount)
        assertEquals(256, parsedTab.atlasWidth)
        assertEquals(8, parsedTab.strideShift)

        val mshBytes = assets.mshFile.readBytes()
        val bb = ByteBuffer.wrap(mshBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(HybridMeshDocument.MESH_MAGIC, bb.short.toInt() and 0xFFFF)
        assertEquals(doc.vertices.size, bb.short.toInt() and 0xFFFF)
        assertEquals(doc.faces.size, bb.short.toInt() and 0xFFFF)
        assertEquals(75, bb.short.toInt() and 0xFFFF)
        assertEquals(80, bb.short.toInt() and 0xFFFF)
        assertEquals(HybridMeshDocument.BANK71_MESH_ADDR + 16, bb.short.toInt() and 0xFFFF)

        // Hero sheet preserves transparent slot 0 on cutout corners
        assertTrue(imported.heroSheet.any { it.toInt() == 0 })
        assertTrue(imported.heroSheet.any { (it.toInt() and 0xFF) in 1..15 })

        val loadedDoc = HybridMeshDocument.load(meshFile)
        assertFalse(loadedDoc.faces.isEmpty())
        assertEquals(doc, loadedDoc)

        val scaledDown = FftMeshImporter.convertPolygonMap(raw, meshName = "SYNTH_MESH_75", defaultYaw = 75, scaleMultiplier = 0.75)
        assertTrue(scaledDown.meshDocument.validate().valid)
        val maxAbsXDefault = doc.vertices.maxOf { kotlin.math.abs(it.xQ106) }
        val maxAbsXScaledDown = scaledDown.meshDocument.vertices.maxOf { kotlin.math.abs(it.xQ106) }
        assertTrue(
            maxAbsXScaledDown < maxAbsXDefault,
            "scaleMultiplier=0.75 ($maxAbsXScaledDown) should produce smaller X coords than 1.0 ($maxAbsXDefault)",
        )
    }
}
