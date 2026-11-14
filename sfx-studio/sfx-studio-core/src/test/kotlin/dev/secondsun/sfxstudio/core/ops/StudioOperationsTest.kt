package dev.secondsun.sfxstudio.core.ops

import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.level.CellPatch
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.LevelDocument
import dev.secondsun.sfxstudio.core.level.LevelDocumentService
import dev.secondsun.sfxstudio.core.level.MapCell
import dev.secondsun.sfxstudio.core.level.MapGrid
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import dev.secondsun.sfxstudio.core.level.applyPatches
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class StudioOperationsTest {
    private fun writeAtlas(
        dir: File,
        name: String = "ATLAS",
    ): File {
        val file = File(dir, "$name.sfxproj")
        val grass = SourceTexture("grass", 32, 32, 0, 0, ByteArray(32 * 32) { 1 })
        val stone = SourceTexture("stone", 32, 32, 32, 0, ByteArray(32 * 32) { 2 })
        val doc =
            AtlasDocument(
                name = name,
                atlas = TextureAtlas(64, 64, listOf(grass, stone)),
                palette =
                    SnesPalette()
                        .withSlot(1, SnesColor(0, 31, 0), assigned = true)
                        .withSlot(2, SnesColor(16, 16, 16), assigned = true),
            )
        doc.saveAndExport(file)
        return file
    }

    @Test
    fun testCellPatchRegionClearingAndPreservation() {
        val initial =
            MapGrid.filled(
                cols = 6,
                rows = 6,
                defaultCell =
                    MapCell(
                        baseHeight = 1,
                        topTex = "grass",
                        skirtTex = "stone",
                        panelTex = "stone",
                    ),
            )

        // Patch 1: region (1,1)..(3,2) updates baseHeight + archetype + panels, leaving panelTex intact
        // Patch 2: single cell (2,2) explicitly clears panelTex via updatePanelTex = true
        val (patched, updatedCount) =
            initial.applyPatches(
                listOf(
                    CellPatch(
                        colStart = 1,
                        rowStart = 1,
                        colEnd = 3,
                        rowEnd = 2,
                        baseHeight = 4,
                        archetype = "PRIM_WEDGE_STRAIGHT",
                        rotation = 180,
                        panels = listOf("N", "E"),
                        tall2h = true,
                    ),
                    CellPatch(
                        col = 2,
                        row = 2,
                        panelTex = null,
                        updatePanelTex = true,
                    ),
                ),
            )

        assertEquals(7, updatedCount)
        val c11 = patched.cellAt(1, 1)
        assertEquals(4, c11.baseHeight)
        assertEquals(ShapeArchetype.PRIM_WEDGE_STRAIGHT, c11.archetype)
        assertEquals(CellRotation.ROT_180, c11.rotation)
        assertEquals("stone", c11.panelTex, "Omitted panelTex must preserve existing value")
        assertTrue(c11.panels.north && c11.panels.east && c11.panels.tall2h)

        val c22 = patched.cellAt(2, 2)
        assertEquals(4, c22.baseHeight)
        assertNull(c22.panelTex, "Explicit updatePanelTex = true with null must clear panelTex to null")
    }

    @Test
    fun testSandboxedPathPolicyBlocksTraversalAtlasEscapeAndSymlinkEscape(
        @TempDir tempDir: File,
    ) {
        val workspace = File(tempDir, "workspace").apply { mkdirs() }
        val outside = File(tempDir, "outside").apply { mkdirs() }
        val outsideAtlas = writeAtlas(outside, "OUTSIDE_ATLAS")

        val ops = StudioOperations(PathPolicy.sandboxed(workspace.toPath()))

        // 1. Direct '../' traversal blocked
        assertThrows(SecurityException::class.java) {
            ops.validateAtlas("../outside/OUTSIDE_ATLAS.sfxproj")
        }

        // 2. Indirect escape via doc.atlasPath inside an in-workspace .sfxlevel blocked!
        val maliciousLevelFile = File(workspace, "trap.sfxlevel")
        val maliciousDoc =
            LevelDocument(
                name = "trap",
                atlasPath = "../outside/OUTSIDE_ATLAS.sfxproj",
                grid = MapGrid.filled(4, 4, MapCell(topTex = "grass")),
            )
        LevelDocumentService.saveAuthoringJson(maliciousLevelFile, maliciousDoc)
        assertThrows(SecurityException::class.java) {
            ops.validateLevel("trap.sfxlevel")
        }

        // 3. Real filesystem symlink inside workspace pointing outside workspace blocked!
        val symlinkPath = workspace.toPath().resolve("escaped_link")
        val symlinkSupported =
            runCatching {
                Files.createSymbolicLink(symlinkPath, outside.toPath())
                true
            }.getOrDefault(false)

        if (symlinkSupported) {
            assertThrows(SecurityException::class.java) {
                ops.validateAtlas("escaped_link/OUTSIDE_ATLAS.sfxproj")
            }
            assertThrows(SecurityException::class.java) {
                ops.packAtlas(
                    projectPath = outsideAtlas.name,
                    outputDir = "escaped_link/sub_out",
                )
            }
        }
    }

    @Test
    fun testPackLevelWithOutputDirDoesNotMutateSourceSfxlevel(
        @TempDir tempDir: File,
    ) {
        val workspace = File(tempDir, "ws").apply { mkdirs() }
        val atlasFile = writeAtlas(workspace, "SHARED")
        val ops = StudioOperations(PathPolicy.sandboxed(workspace.toPath()))

        val createRes =
            ops.createLevel(
                levelPath = "levels/arena.sfxlevel",
                atlasPath = atlasFile.relativeTo(workspace).path,
                cols = 6,
                rows = 6,
                defaultTopTex = "grass",
            )
        assertTrue(createRes.valid)

        val levelFile = File(workspace, "levels/arena.sfxlevel")
        val originalJson = levelFile.readText()
        levelFile.setLastModified(1_700_000_000_000L)
        val mtimeBefore = levelFile.lastModified()

        val distRes =
            ops.packLevel(
                levelPath = "levels/arena.sfxlevel",
                outputDir = "build/packed_levels",
                yaw = 45,
            )
        assertTrue(distRes.valid)
        assertTrue(File(workspace, "build/packed_levels/arena.map").exists())
        assertTrue(File(workspace, "build/packed_levels/arena.i").exists())
        assertTrue(File(workspace, "build/packed_levels/arena_preview.png").exists())
        assertFalse(File(workspace, "build/packed_levels/arena.sfxlevel").exists())

        assertEquals(originalJson, levelFile.readText())
        assertEquals(mtimeBefore, levelFile.lastModified(), "packLevel with outputDir must not rewrite source .sfxlevel")
    }
}
