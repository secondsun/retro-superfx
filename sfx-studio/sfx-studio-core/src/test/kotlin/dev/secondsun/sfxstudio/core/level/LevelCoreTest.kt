package dev.secondsun.sfxstudio.core.level

import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.DescriptorAlias
import dev.secondsun.sfxstudio.core.atlas.DescriptorTable
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.atlas.VirtualSlice
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.imageio.ImageIO

class LevelCoreTest {
    private fun buildSampleAtlasDoc(
        width: Int = 64,
        height: Int = 64,
        descriptorOrder: List<String>? = null,
    ): AtlasDocument {
        val grass = SourceTexture("grass", 32, 32, 0, 0, ByteArray(32 * 32) { 1 })
        val stone = SourceTexture("stone", 32, 32, 32, 0, ByteArray(32 * 32) { 2 })
        val water = SourceTexture("water", 32, 32, 0, 32, ByteArray(32 * 32) { idx -> if (idx % 2 == 0) 0 else 3 })
        val parapet = VirtualSlice("parapet", "stone", 0, 0, 32, 16)
        val foliage = VirtualSlice("foliage", "grass", 0, 0, 16, 32)
        val waterDeep = DescriptorAlias("water_deep", "water")

        val atlas =
            TextureAtlas(
                width = width,
                height = height,
                textures = listOf(grass, stone, water),
                slices = listOf(parapet, foliage),
                aliases = listOf(waterDeep),
                descriptorOrder = descriptorOrder,
            )
        val palette =
            SnesPalette()
                .withSlot(1, SnesColor.fromRgb888(40, 180, 60))
                .withSlot(2, SnesColor.fromRgb888(140, 140, 150))
                .withSlot(3, SnesColor.fromRgb888(40, 110, 220))
        return AtlasDocument(name = "ATLAS", atlas = atlas, palette = palette)
    }

    @Test
    fun testDescriptorTableBinaryAndRoundTrip(
        @TempDir tempDir: File,
    ) {
        val atlasDoc = buildSampleAtlasDoc(width = 128, height = 64)
        val tabBytes = atlasDoc.descriptorTable().getOrThrow().toTabBinary()
        val parsed = DescriptorTable.parseTabBinary(tabBytes)

        assertEquals(DescriptorTable.TAB_MAGIC, parsed.magic)
        assertEquals(6, parsed.entryCount)
        assertEquals(128, parsed.atlasWidth)
        assertEquals(7, parsed.strideShift)
        assertEquals(8 + 6 * 8, tabBytes.size)

        val grassEntry = parsed.entries[0]
        assertEquals(0, grassEntry.atlasOffset)
        assertEquals(31, grassEntry.uMask)
        assertEquals(31 shl 7, grassEntry.vMaskShifted)
        assertEquals((31 shl 7) or 0x7F, grassEntry.vOffsetMask)

        assertEquals(32, parsed.entries[1].atlasOffset)
        assertEquals(4096, parsed.entries[2].atlasOffset)

        val parapetEntry = parsed.entries.find { it.uMask == 31 && it.vMaskShifted == (15 shl 7) }
        assertNotNull(parapetEntry)
        assertEquals(32, parapetEntry!!.atlasOffset)

        val projFile = File(tempDir, "TEST_ATLAS.sfxproj")
        atlasDoc.copy(name = "TEST_ATLAS").saveAndExport(projFile)
        val savedTab = File(tempDir, "TEST_ATLAS.tab")
        assertTrue(savedTab.exists())
        assertArrayEquals(tabBytes, savedTab.readBytes())
    }

    @Test
    fun testLevelDocumentRoundTripAnd16x16Grid(
        @TempDir tempDir: File,
    ) {
        val atlasDoc = buildSampleAtlasDoc()
        val projFile = File(tempDir, "ATLAS.sfxproj")
        atlasDoc.saveAndExport(projFile)

        var grid =
            MapGrid.filled(
                cols = 16,
                rows = 16,
                defaultCell = MapCell(baseHeight = 1, archetype = ShapeArchetype.PRIM_FLAT, topTex = "grass", skirtTex = "stone"),
            )
        grid =
            grid.withCell(
                4,
                5,
                MapCell(
                    baseHeight = 2,
                    archetype = ShapeArchetype.PRIM_STAIRS,
                    rotation = CellRotation.ROT_90,
                    topTex = "stone",
                    skirtTex = "stone",
                    panelTex = "parapet",
                    panels = EdgePanels(north = true, west = true, tall2h = false),
                ),
            )

        val doc =
            LevelDocument(
                name = "fortress_16",
                atlasPath = "ATLAS.sfxproj",
                grid = grid,
                gameplayLayers = mapOf("spawnPoints" to listOf(mapOf("col" to 2, "row" to 3))),
            )

        val levelFile = File(tempDir, "fortress_16.sfxlevel")
        val exported = LevelDocumentService.saveAndExport(levelFile, doc, atlasDoc)
        assertNotNull(exported.levelFile)
        assertTrue(exported.levelFile!!.exists())
        assertTrue(exported.mapBinFile.exists())
        assertTrue(exported.headerFile.exists())
        assertNotNull(exported.previewPngFile)
        assertTrue(exported.previewPngFile!!.exists())

        val mapBytes = exported.mapBinFile.readBytes()
        assertEquals(2056, mapBytes.size)
        assertEquals(16, mapBytes[0].toInt() and 0xFF)
        assertEquals(16, mapBytes[1].toInt() and 0xFF)

        val headerText = exported.headerFile.readText()
        assertTrue(headerText.contains("FORTRESS_16_COLS = 16"))
        assertTrue(headerText.contains("FORTRESS_16_ROWS = 16"))
        assertTrue(headerText.contains("FORTRESS_16_MAP_BYTE_SIZE = 2056"))
        assertFalse(headerText.contains(".segment"))

        val reloaded = LevelDocumentService.loadLevel(levelFile)
        assertEquals(16, reloaded.grid.cols)
        assertEquals(16, reloaded.grid.rows)
        assertNotNull(reloaded.gameplayLayers)
        assertEquals(ShapeArchetype.PRIM_STAIRS, reloaded.grid.cellAt(4, 5).archetype)
        assertEquals(CellRotation.ROT_90, reloaded.grid.cellAt(4, 5).rotation)
        assertEquals(EdgePanels(north = true, west = true), reloaded.grid.cellAt(4, 5).panels)

        val deserializedGrid = LevelBinarySerializer.deserialize(mapBytes, atlasDoc.atlas)
        assertEquals(reloaded.grid.cellAt(4, 5), deserializedGrid.cellAt(4, 5))

        val reorderedDoc =
            buildSampleAtlasDoc(
                descriptorOrder = listOf("stone", "water", "water_deep", "parapet", "foliage", "grass"),
            )
        val reorderedBytes = LevelBinarySerializer.serialize(reloaded.grid, reorderedDoc.atlas)
        assertEquals(5, reorderedBytes[10].toInt() and 0xFF)
    }

    @Test
    fun testCa65MapImporterShowcaseAndStarship() {
        val repoRoot = File("..").canonicalFile
        val showcaseCa65 = File(repoRoot, "snes-sfx-demo/X-GSU/IsoEngine/Data/showcase_map.i")
        val showcaseAtlasProj = File(repoRoot, "snes-sfx-demo/X-GSU/IsoEngine/Data/TEXTURES.sfxproj")
        if (showcaseCa65.exists() && showcaseAtlasProj.exists()) {
            val atlasDoc = AtlasDocument.load(showcaseAtlasProj)
            val doc = Ca65MapImporter.importFile(showcaseCa65, "TEXTURES.sfxproj", atlasDoc.atlas, "showcase_map")
            assertEquals(8, doc.grid.cols)
            assertEquals(8, doc.grid.rows)

            val validation = LevelValidator.validate(doc, atlasDoc)
            assertTrue(validation.valid, "Expected showcase_map valid, got errors: ${validation.errors}")
            assertTrue(validation.warnings.isEmpty(), "Expected zero warnings on showcase_map, got: ${validation.warnings}")

            for (yaw in listOf(15, 45, 75, 105)) {
                val rendered = IsoSoftwareRenderer.render(doc.grid, atlasDoc.atlas, atlasDoc.palette, yawIndex = yaw)
                val nonBgCount = rendered.cellPickBuffer.count { it >= 0 }
                assertTrue(nonBgCount > 1000, "Expected >1000 rendered diorama pixels at yaw $yaw, got $nonBgCount")
            }
        }

        val starshipCa65 = File(repoRoot, "snes-sfx-demo/X-GSU/StarshipMap/Data/starship_map.i")
        val starshipAtlasProj = File(repoRoot, "snes-sfx-demo/X-GSU/StarshipMap/Data/TEXTURES.sfxproj")
        if (starshipCa65.exists() && starshipAtlasProj.exists()) {
            val atlasDoc = AtlasDocument.load(starshipAtlasProj)
            val doc = Ca65MapImporter.importFile(starshipCa65, "TEXTURES.sfxproj", atlasDoc.atlas, "starship_map")
            assertEquals(12, doc.grid.cols)
            assertEquals(8, doc.grid.rows)
            val validation = LevelValidator.validate(doc, atlasDoc)
            assertTrue(validation.valid, "Expected starship_map valid, got errors: ${validation.errors}")
        }
    }

    @Test
    fun testLevelValidatorCatchesErrorsAndWarnings() {
        val atlasDoc = buildSampleAtlasDoc()
        var grid =
            MapGrid.filled(
                cols = 4,
                rows = 4,
                defaultCell = MapCell(baseHeight = 0, archetype = ShapeArchetype.PRIM_FLAT, topTex = "grass", skirtTex = "stone"),
            )

        grid =
            grid.withCell(
                1,
                1,
                MapCell(
                    baseHeight = 0,
                    archetype = ShapeArchetype.PRIM_LIQUID_SHALLOW,
                    topTex = "water",
                    skirtTex = "stone",
                ),
            )

        grid =
            grid.withCell(
                2,
                2,
                MapCell(
                    baseHeight = 1,
                    archetype = ShapeArchetype.PRIM_FLAT,
                    topTex = "stone",
                    skirtTex = "stone",
                    panelTex = "stone",
                    panels = EdgePanels(south = true, tall2h = false),
                ),
            )

        val warnDoc = LevelDocument("warn_test", "ATLAS.sfxproj", grid)
        val warnRes = LevelValidator.validate(warnDoc, atlasDoc)
        assertTrue(warnRes.valid)
        assertTrue(warnRes.warnings.any { it.code == "UNFLUSH_LIQUID_SURFACE" })
        assertTrue(warnRes.warnings.any { it.code == "PANEL_1H_CROPPED_TEXTURE" })

        val badGrid = grid.withCell(0, 0, MapCell(baseHeight = 0, topTex = "nonexistent_tex"))
        val oversizedAtlasDoc = buildSampleAtlasDoc(width = 256, height = 256)
        val errRes = LevelValidator.validate(LevelDocument("err_test", "ATLAS.sfxproj", badGrid), oversizedAtlasDoc)
        assertFalse(errRes.valid)
        assertTrue(errRes.errors.any { it.code == "UNKNOWN_TOP_TEXTURE" })
        assertTrue(errRes.errors.any { it.code == "ATLAS_EXCEEDS_BANK_71" })
    }

    @Test
    fun testPreviewPngExportDimensions(
        @TempDir tempDir: File,
    ) {
        val atlasDoc = buildSampleAtlasDoc()
        val grid =
            MapGrid.filled(
                cols = 6,
                rows = 6,
                defaultCell = MapCell(baseHeight = 2, archetype = ShapeArchetype.PRIM_PILLAR, topTex = "stone", skirtTex = "stone"),
            )
        val pngFile = File(tempDir, "pillar_preview.png")
        IsoSoftwareRenderer.writePreviewPng(grid, atlasDoc.atlas, atlasDoc.palette, pngFile, yawIndex = 20)
        assertTrue(pngFile.exists())
        val img = ImageIO.read(pngFile)
        assertEquals(256, img.width)
        assertEquals(160, img.height)
    }
}
