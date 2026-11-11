package dev.secondsun.sfxstudio.cli

import dev.secondsun.sfxstudio.core.atlas.DescriptorAlias
import dev.secondsun.sfxstudio.core.atlas.ProjectService
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.atlas.VirtualSlice
import org.junit.jupiter.api.Assertions.assertEquals
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
    ): File {
        val projFile = File(dir, "$baseName.sfxproj")
        val pixels = ByteArray(16 * 16) { 1 }
        val tex = SourceTexture("box", 16, 16, 0, 0, pixels)
        val slice = VirtualSlice("box_half", "box", 0, 0, 16, 16)
        val alias = DescriptorAlias("box_dup", "box")

        val atlas =
            TextureAtlas(
                width = 32,
                height = 32,
                textures = listOf(tex),
                slices = listOf(slice),
                aliases = listOf(alias),
            )
        val palette = SnesPalette().withSlot(1, SnesColor(31, 0, 0), assigned = true)

        ProjectService.saveProject(projFile, baseName, atlas, palette, 16)
        return projFile
    }

    @Test
    fun testLegacyPackAndValidate(
        @TempDir tempDir: File,
    ) {
        val projFile = createSampleProject(tempDir, "TEST_LEGACY")

        // Legacy validate
        val valCode = CliMain.execute(arrayOf("validate", projFile.absolutePath))
        assertEquals(0, valCode)

        // Legacy pack
        val packCode = CliMain.execute(arrayOf("pack", projFile.absolutePath))
        assertEquals(0, packCode)

        assertTrue(File(tempDir, "TEST_LEGACY.bin").exists())
        assertTrue(File(tempDir, "TEST_LEGACY.pal").exists())
        assertTrue(File(tempDir, "TEST_LEGACY.i").exists())
        assertTrue(File(tempDir, "TEST_LEGACY_atlas.png").exists())

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

        // atlas validate
        val valCode = CliMain.execute(arrayOf("atlas", "validate", projFile.absolutePath))
        assertEquals(0, valCode)

        // atlas pack
        val packCode = CliMain.execute(arrayOf("atlas", "pack", projFile.absolutePath))
        assertEquals(0, packCode)

        assertTrue(File(tempDir, "TEST_NOUN.bin").exists())
        assertTrue(File(tempDir, "TEST_NOUN.pal").exists())
        assertTrue(File(tempDir, "TEST_NOUN.i").exists())
        assertTrue(File(tempDir, "TEST_NOUN_atlas.png").exists())
    }

    @Test
    fun testPackWithOutputDirAndAutoPack(
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

        assertTrue(outDir.exists())
        assertTrue(File(outDir, "TEST_AUTOPACK.sfxproj").exists())
        assertTrue(File(outDir, "TEST_AUTOPACK.bin").exists())
        assertTrue(File(outDir, "TEST_AUTOPACK.pal").exists())
        assertTrue(File(outDir, "TEST_AUTOPACK.i").exists())
        assertTrue(File(outDir, "TEST_AUTOPACK_atlas.png").exists())
    }

    @Test
    fun testAtlasImportWithSnapPalette(
        @TempDir tempDir: File,
    ) {
        val projFile = createSampleProject(tempDir, "TEST_IMPORT")

        // Create 16x16 PNG image
        val imgFile = File(tempDir, "imported_tile.png")
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()
        g2d.color = Color.RED
        g2d.fillRect(0, 0, 16, 16)
        g2d.dispose()
        ImageIO.write(image, "PNG", imgFile)

        val code =
            CliMain.execute(
                arrayOf(
                    "atlas",
                    "import",
                    projFile.absolutePath,
                    imgFile.absolutePath,
                    "--snap-palette",
                ),
            )
        assertEquals(0, code)

        val (loadedAtlas, _, _) = ProjectService.loadProject(projFile)
        assertEquals(2, loadedAtlas.textures.size)
        assertTrue(loadedAtlas.textures.any { it.name == "imported_tile" })
    }

    @Test
    fun testHelpAndUnknownCommands() {
        assertEquals(0, CliMain.execute(arrayOf("help")))
        assertEquals(0, CliMain.execute(arrayOf("--help")))
        assertEquals(0, CliMain.execute(arrayOf("-h")))
        assertEquals(0, CliMain.execute(arrayOf("atlas", "help")))

        assertEquals(1, CliMain.execute(emptyArray()))
        assertEquals(1, CliMain.execute(arrayOf("atlas")))
        assertEquals(1, CliMain.execute(arrayOf("atlas", "unknown_subcommand")))
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

        val atlas =
            TextureAtlas(
                width = 32,
                height = 32,
                textures = listOf(tex1, tex2),
            )
        val palette = SnesPalette().withSlot(1, SnesColor(31, 0, 0), assigned = true)
        ProjectService.saveProject(projFile, "COLLISION", atlas, palette, 16)

        val code = CliMain.execute(arrayOf("atlas", "validate", projFile.absolutePath))
        assertEquals(2, code) // Exception raised and caught
    }
}
