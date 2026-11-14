package dev.secondsun.sfxstudio.cli

import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.DescriptorAlias
import dev.secondsun.sfxstudio.core.atlas.DescriptorTable
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.atlas.VirtualSlice
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.LevelBinarySerializer
import dev.secondsun.sfxstudio.core.level.LevelDocumentService
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class CliMainTest {
    private fun createSampleProject(
        dir: File,
        baseName: String = "TEST_CLI",
        width: Int = 32,
        height: Int = 32,
    ): File {
        val projFile = File(dir, "$baseName.sfxproj")
        val pixels = ByteArray(16 * 16) { 1 }
        val tex = SourceTexture("box", 16, 16, 0, 0, pixels)
        val slice = VirtualSlice("box_half", "box", 0, 0, 16, 16)
        val alias = DescriptorAlias("box_dup", "box")

        val atlas =
            TextureAtlas(
                width = width,
                height = height,
                textures = listOf(tex),
                slices = listOf(slice),
                aliases = listOf(alias),
            )
        val palette = SnesPalette().withSlot(1, SnesColor(31, 0, 0), assigned = true)

        AtlasDocument(
            name = baseName,
            atlas = atlas,
            palette = palette,
            gridSnap = 16,
        ).saveAndExport(projFile)
        return projFile
    }

    @Test
    fun testLegacyPackAndValidate(
        @TempDir tempDir: File,
    ) {
        val projFile = createSampleProject(tempDir, "TEST_LEGACY")

        val valCode = CliMain.execute(arrayOf("validate", projFile.absolutePath))
        assertEquals(0, valCode)

        val packCode = CliMain.execute(arrayOf("pack", projFile.absolutePath))
        assertEquals(0, packCode)

        assertTrue(File(tempDir, "TEST_LEGACY.bin").exists())
        assertTrue(File(tempDir, "TEST_LEGACY.pal").exists())
        assertTrue(File(tempDir, "TEST_LEGACY.tab").exists())
        assertTrue(File(tempDir, "TEST_LEGACY.i").exists())
        assertTrue(File(tempDir, "TEST_LEGACY_atlas.png").exists())

        val parsedTab = DescriptorTable.parseTabBinary(File(tempDir, "TEST_LEGACY.tab").readBytes())
        assertEquals(3, parsedTab.entryCount)
        assertEquals(32, parsedTab.atlasWidth)
        assertEquals(5, parsedTab.strideShift)

        val includeContent = File(tempDir, "TEST_LEGACY.i").readText()
        assertTrue(includeContent.contains("TEXTURE_ID_BOX = 0"))
        assertTrue(includeContent.contains("TEXTURE_ID_BOX_DUP = 1"))
        assertTrue(includeContent.contains("TEXTURE_ID_BOX_HALF = 2"))
        assertTrue(includeContent.contains(".segment \"GSUDATA\""))
        assertTrue(includeContent.contains(".export TEXTURE_TABLE"))
    }

    @Test
    fun testNounScopedAtlasPackAndValidate(
        @TempDir tempDir: File,
    ) {
        val projFile = createSampleProject(tempDir, "TEST_NOUN")

        val valCode = CliMain.execute(arrayOf("atlas", "validate", projFile.absolutePath))
        assertEquals(0, valCode)

        val packCode = CliMain.execute(arrayOf("atlas", "pack", projFile.absolutePath))
        assertEquals(0, packCode)

        assertTrue(File(tempDir, "TEST_NOUN.bin").exists())
        assertTrue(File(tempDir, "TEST_NOUN.pal").exists())
        assertTrue(File(tempDir, "TEST_NOUN.tab").exists())
        assertTrue(File(tempDir, "TEST_NOUN.i").exists())
        assertTrue(File(tempDir, "TEST_NOUN_atlas.png").exists())
    }

    @Test
    fun testPackWithOutputDirExportsCompanionsWithoutCopyingSfxproj(
        @TempDir tempDir: File,
    ) {
        val projFile = createSampleProject(tempDir, "TEST_AUTOPACK")
        val outDir = File(tempDir, "output_dist")

        val code =
            CliMain.execute(
                arrayOf(
                    "atlas",
                    "pack",
                    projFile.absolutePath,
                    "--output-dir",
                    outDir.absolutePath,
                    "--auto-pack",
                ),
            )
        assertEquals(0, code)

        assertTrue(projFile.exists())
        assertTrue(outDir.exists())
        assertFalse(
            File(outDir, "TEST_AUTOPACK.sfxproj").exists(),
            "Decision 2.3: --output-dir must receive only companion binaries, never a duplicate .sfxproj",
        )
        assertTrue(File(outDir, "TEST_AUTOPACK.bin").exists())
        assertTrue(File(outDir, "TEST_AUTOPACK.pal").exists())
        assertTrue(File(outDir, "TEST_AUTOPACK.tab").exists())
        assertTrue(File(outDir, "TEST_AUTOPACK.i").exists())
        assertTrue(File(outDir, "TEST_AUTOPACK_atlas.png").exists())
    }

    @Test
    fun testAtlasImportStrictBoundsDefaultAndAutoGrowFlag(
        @TempDir tempDir: File,
    ) {
        // 16x16 atlas already occupied by 16x16 "box"
        val projFile = createSampleProject(tempDir, "TEST_IMPORT", width = 16, height = 16)

        val imgFile = File(tempDir, "imported_tile.png")
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()
        g2d.color = Color.RED
        g2d.fillRect(0, 0, 16, 16)
        g2d.dispose()
        ImageIO.write(image, "PNG", imgFile)

        // Without --auto-grow: CLI defaults to STRICT_BOUNDS and fails cleanly
        val strictCode =
            CliMain.execute(
                arrayOf(
                    "atlas",
                    "import",
                    projFile.absolutePath,
                    imgFile.absolutePath,
                    "--snap-palette",
                ),
            )
        assertEquals(2, strictCode)
        assertEquals(
            1,
            AtlasDocument
                .load(projFile)
                .atlas.textures.size,
        )

        // With --auto-grow: grows atlas within <=256x256 and imports
        val growCode =
            CliMain.execute(
                arrayOf(
                    "atlas",
                    "import",
                    projFile.absolutePath,
                    imgFile.absolutePath,
                    "--snap-palette",
                    "--auto-grow",
                ),
            )
        assertEquals(0, growCode)

        val loaded = AtlasDocument.load(projFile)
        assertEquals(2, loaded.atlas.textures.size)
        assertTrue(loaded.atlas.textures.any { it.name == "imported_tile" })
        assertTrue(loaded.atlas.width >= 32 || loaded.atlas.height >= 32)
    }

    @Test
    fun testLevelCreateSetCellsValidatePackAndImportCa65(
        @TempDir tempDir: File,
    ) {
        val projFile = createSampleProject(tempDir, "LEVEL_ATLAS")
        val levelFile = File(tempDir, "courtyard.sfxlevel")

        val createCode =
            CliMain.execute(
                arrayOf(
                    "level",
                    "create",
                    levelFile.absolutePath,
                    "--atlas",
                    projFile.absolutePath,
                    "--cols",
                    "6",
                    "--rows",
                    "5",
                    "--top-tex",
                    "box",
                    "--skirt-tex",
                    "box_dup",
                ),
            )
        assertEquals(0, createCode)
        assertTrue(levelFile.exists())
        assertTrue(File(tempDir, "courtyard.map").exists())
        assertTrue(File(tempDir, "courtyard.i").exists())
        assertTrue(File(tempDir, "courtyard_preview.png").exists())

        // 1. CLI single-patch flags + --resize on level set-cells
        val setFlagsCode =
            CliMain.execute(
                arrayOf(
                    "level",
                    "set-cells",
                    levelFile.absolutePath,
                    "--rect",
                    "1,1,2,2",
                    "--height",
                    "3",
                    "--archetype",
                    "PRIM_STAIRS",
                    "--rot",
                    "90",
                    "--panel-tex",
                    "box_half",
                    "--panels",
                    "N,W",
                    "--tall-2h",
                    "--resize",
                    "8x6",
                ),
            )
        assertEquals(0, setFlagsCode)
        val afterFlags = LevelDocumentService.loadLevel(levelFile)
        assertEquals(8, afterFlags.grid.cols)
        assertEquals(6, afterFlags.grid.rows)
        val cell11 = afterFlags.grid.cellAt(1, 1)
        assertEquals(3, cell11.baseHeight)
        assertEquals(ShapeArchetype.PRIM_STAIRS, cell11.archetype)
        assertEquals(CellRotation.ROT_90, cell11.rotation)
        assertEquals("box_half", cell11.panelTex)
        assertTrue(cell11.panels.north && cell11.panels.west && cell11.panels.tall2h)

        // 2. CLI --cells-json @file batch patch
        val patchesJsonFile = File(tempDir, "patches.json")
        patchesJsonFile.writeText(
            """
            [
              {"col": 0, "row": 0, "base_height": 5, "skirt_tex": "NONE"},
              {"col_start": 4, "col_end": 5, "row_start": 0, "row_end": 1, "base_height": 2}
            ]
            """.trimIndent(),
        )
        val setJsonCode =
            CliMain.execute(
                arrayOf(
                    "level",
                    "set-cells",
                    levelFile.absolutePath,
                    "--cells-json",
                    "@${patchesJsonFile.absolutePath}",
                ),
            )
        assertEquals(0, setJsonCode)
        val afterJson = LevelDocumentService.loadLevel(levelFile)
        assertEquals(5, afterJson.grid.cellAt(0, 0).baseHeight)
        assertNull(afterJson.grid.cellAt(0, 0).skirtTex)
        assertEquals(2, afterJson.grid.cellAt(5, 1).baseHeight)

        val valCode = CliMain.execute(arrayOf("level", "validate", levelFile.absolutePath))
        assertEquals(0, valCode)

        val outDir = File(tempDir, "packed_level")
        val packCode =
            CliMain.execute(
                arrayOf(
                    "level",
                    "pack",
                    levelFile.absolutePath,
                    "--output-dir",
                    outDir.absolutePath,
                    "--yaw",
                    "45",
                ),
            )
        assertEquals(0, packCode)
        assertFalse(File(outDir, "courtyard.sfxlevel").exists())
        assertTrue(File(outDir, "courtyard.map").exists())
        assertTrue(File(outDir, "courtyard.i").exists())
        assertTrue(File(outDir, "courtyard_preview.png").exists())

        // 3. Legacy ca65 import
        val ca65File = File(tempDir, "legacy_map.i")
        ca65File.writeText(
            """
            MAP_COLS = 4
            MAP_ROWS = 4
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            def_cell 1, PRIM_STAIRS | ROT_90, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, TEXTURE_ID_BOX_HALF, PANEL_N | PANEL_W
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            def_cell 0, PRIM_FLAT, TEXTURE_ID_BOX, TEXTURE_ID_BOX_DUP, 0, PANEL_NONE
            """.trimIndent(),
        )
        val importedLevelFile = File(tempDir, "imported.sfxlevel")
        val importCode =
            CliMain.execute(
                arrayOf(
                    "level",
                    "import-ca65",
                    ca65File.absolutePath,
                    "--atlas",
                    projFile.absolutePath,
                    "--output",
                    importedLevelFile.absolutePath,
                ),
            )
        assertEquals(0, importCode)
        val importedDoc = LevelDocumentService.loadLevel(importedLevelFile)
        assertEquals(4, importedDoc.grid.cols)
        assertEquals(4, importedDoc.grid.rows)
        assertEquals(ShapeArchetype.PRIM_STAIRS, importedDoc.grid.cellAt(1, 0).archetype)

        val atlas = AtlasDocument.load(projFile).atlas
        val parsedBin = LevelBinarySerializer.deserialize(File(tempDir, "imported.map").readBytes(), atlas)
        assertEquals(importedDoc.grid, parsedBin)
    }

    @Test
    fun testHelpAndUnknownCommands() {
        assertEquals(0, CliMain.execute(arrayOf("help")))
        assertEquals(0, CliMain.execute(arrayOf("--help")))
        assertEquals(0, CliMain.execute(arrayOf("-h")))
        assertEquals(0, CliMain.execute(arrayOf("atlas", "help")))
        assertEquals(0, CliMain.execute(arrayOf("level", "help")))

        assertEquals(1, CliMain.execute(emptyArray()))
        assertEquals(1, CliMain.execute(arrayOf("atlas")))
        assertEquals(1, CliMain.execute(arrayOf("atlas", "unknown_subcommand")))
        assertEquals(1, CliMain.execute(arrayOf("level")))
        assertEquals(1, CliMain.execute(arrayOf("level", "unknown_subcommand")))
        assertEquals(1, CliMain.execute(arrayOf("unknown_verb")))
    }

    @Test
    fun testValidationDetectsCollision(
        @TempDir tempDir: File,
    ) {
        val projFile = File(tempDir, "COLLISION.sfxproj")
        val pixels = ByteArray(16 * 16) { 1 }
        val tex1 = SourceTexture("box1", 16, 16, 0, 0, pixels)
        val tex2 = SourceTexture("box2", 16, 16, 0, 0, pixels) // Collides with box1!

        val doc =
            AtlasDocument(
                name = "COLLISION",
                atlas = TextureAtlas(width = 32, height = 32, textures = listOf(tex1, tex2)),
                palette = SnesPalette().withSlot(1, SnesColor(31, 0, 0), assigned = true),
                gridSnap = 16,
            )
        projFile.writeText(doc.toJson())

        val code = CliMain.execute(arrayOf("atlas", "validate", projFile.absolutePath))
        assertEquals(2, code)
    }
}
