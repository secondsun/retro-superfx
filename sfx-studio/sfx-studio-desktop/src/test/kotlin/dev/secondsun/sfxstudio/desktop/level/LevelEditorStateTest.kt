package dev.secondsun.sfxstudio.desktop.level

import androidx.compose.ui.input.key.Key
import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.EdgePanels
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.shell.FakeStudioPlatform
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LevelEditorStateTest {
    private fun createAtlasOnDisk(dir: File): File {
        val atlasFile = File(dir, "EDITOR_ATLAS.sfxproj")
        val grass = SourceTexture("grass", 32, 32, 0, 0, ByteArray(32 * 32) { 1 })
        val stone = SourceTexture("stone", 32, 32, 32, 0, ByteArray(32 * 32) { 2 })
        val parapet = SourceTexture("parapet", 32, 16, 0, 32, ByteArray(32 * 16) { 3 })
        val atlas =
            TextureAtlas(
                width = 64,
                height = 64,
                textures = listOf(grass, stone, parapet),
            )
        val palette =
            SnesPalette()
                .withSlot(1, SnesColor(4, 24, 6), assigned = true)
                .withSlot(2, SnesColor(16, 16, 18), assigned = true)
                .withSlot(3, SnesColor(22, 20, 14), assigned = true)
        AtlasDocument(
            name = "EDITOR_ATLAS",
            atlas = atlas,
            palette = palette,
            gridSnap = 16,
        ).saveAndExport(atlasFile)
        return atlasFile
    }

    @Test
    fun testSelectionTransformsAndUndoRedo(
        @TempDir tempDir: File,
    ) {
        val atlasFile = createAtlasOnDisk(tempDir)
        val studio = StudioState(platform = FakeStudioPlatform())
        val level = studio.newLevelSession("courtyard", atlasFile, cols = 6, rows = 6)

        assertEquals(6, level.document.grid.cols)
        assertEquals(
            "grass",
            level.document.grid
                .cellAt(0, 0)
                .topTex,
        )
        assertFalse(level.canUndo)

        level.selectRect(1, 1, 2, 2)
        assertEquals(4, level.selectedCells.size)
        level.stepSelectedHeight(2)
        assertEquals(
            2,
            level.document.grid
                .cellAt(1, 1)
                .baseHeight,
        )
        assertEquals(
            2,
            level.document.grid
                .cellAt(2, 2)
                .baseHeight,
        )
        assertEquals(
            0,
            level.document.grid
                .cellAt(0, 0)
                .baseHeight,
        )
        assertTrue(level.canUndo)

        level.selectSingleCell(1, 1)
        level.setSelectedArchetype(ShapeArchetype.PRIM_STAIRS)
        level.activeTextureSlot = ActiveTextureSlot.PANEL
        level.assignTextureToActiveSlot("parapet")
        level.updateSelectedPanels { EdgePanels(north = true) }
        level.rotateSelectedClockwise()

        val edited = level.document.grid.cellAt(1, 1)
        assertEquals(ShapeArchetype.PRIM_STAIRS, edited.archetype)
        assertEquals(CellRotation.ROT_90, edited.rotation)
        assertTrue(edited.panels.east)
        assertFalse(edited.panels.north)

        level.undo()
        assertEquals(
            CellRotation.ROT_0,
            level.document.grid
                .cellAt(1, 1)
                .rotation,
        )
        assertTrue(
            level.document.grid
                .cellAt(1, 1)
                .panels.north,
        )
        assertTrue(level.canRedo)

        level.redo()
        assertEquals(
            CellRotation.ROT_90,
            level.document.grid
                .cellAt(1, 1)
                .rotation,
        )
    }

    @Test
    fun testBrushStrokeBatchingAndLayerMask(
        @TempDir tempDir: File,
    ) {
        val atlasFile = createAtlasOnDisk(tempDir)
        val studio = StudioState(platform = FakeStudioPlatform())
        val level = studio.newLevelSession("brush_test", atlasFile, cols = 5, rows = 5)

        level.selectRect(0, 0, 4, 0)
        level.stepSelectedHeight(3)

        level.activeTool = LevelTool.BRUSH
        level.brushCell = level.brushCell.copy(baseHeight = 0, topTex = "stone")
        level.brushMask =
            BrushLayerMask(
                applyHeight = false,
                applyArchetypeAndRotation = false,
                applyTopTex = true,
                applySkirtTex = false,
                applyPanelTexAndFlags = false,
            )

        level.beginStroke()
        level.paintCell(0, 0, recordImmediateUndo = false)
        level.paintCell(1, 0, recordImmediateUndo = false)
        level.paintCell(2, 0, recordImmediateUndo = false)
        level.endStroke()

        assertEquals(
            "stone",
            level.document.grid
                .cellAt(0, 0)
                .topTex,
        )
        assertEquals(
            "stone",
            level.document.grid
                .cellAt(2, 0)
                .topTex,
        )
        assertEquals(
            3,
            level.document.grid
                .cellAt(1, 0)
                .baseHeight,
            "Height must be preserved when masked off",
        )

        level.undo()
        assertEquals(
            "grass",
            level.document.grid
                .cellAt(0, 0)
                .topTex,
        )
        assertEquals(
            "grass",
            level.document.grid
                .cellAt(1, 0)
                .topTex,
        )
        assertEquals(
            "grass",
            level.document.grid
                .cellAt(2, 0)
                .topTex,
        )
    }

    @Test
    fun testLiveCrossTabAtlasResolutionInValidationImportCa65AndRequestSave(
        @TempDir tempDir: File,
    ) {
        val atlasFile = createAtlasOnDisk(tempDir)
        val platform = FakeStudioPlatform()

        val studio = StudioState(listOf(atlasFile), platform = platform)
        val atlasSession = studio.openSessions[0] as AtlasEditorState

        // Mutate open AtlasEditorState in memory WITHOUT saving to disk, putting "water_live" first
        val waterTex = SourceTexture("water_live", 32, 32, 0, 64, ByteArray(32 * 32) { 4 })
        atlasSession.atlas =
            atlasSession.atlas
                .copy(
                    height = 128,
                    descriptorOrder = listOf("water_live", "grass", "stone", "parapet"),
                ).withTexture(waterTex)
        atlasSession.updatePaletteSlot(4, SnesColor(2, 12, 30))
        assertTrue(atlasSession.hasUnsavedChanges)

        // 1. newLevelSession sees live unsaved first descriptor ("water_live") BEFORE disk ("grass")
        val levelSession = studio.newLevelSession("live_scene", atlasFile, cols = 4, rows = 4)
        assertEquals(
            "water_live",
            levelSession.document.grid
                .cellAt(0, 0)
                .topTex,
        )
        assertTrue(levelSession.currentValidation().valid)

        // 2. importCa65Map resolves TEXTURE_ID_WATER_LIVE from open unsaved tab BEFORE disk
        val ca65File = File(tempDir, "live_map.i")
        ca65File.writeText(
            buildString {
                appendLine("MAP_COLS = 4")
                appendLine("MAP_ROWS = 4")
                appendLine("def_cell 1, PRIM_FLAT, TEXTURE_ID_WATER_LIVE, NONE, 0, PANEL_NONE")
                repeat(15) {
                    appendLine("def_cell 0, PRIM_FLAT, TEXTURE_ID_GRASS, TEXTURE_ID_STONE, 0, PANEL_NONE")
                }
            },
        )
        assertTrue(levelSession.importCa65Map(ca65File))
        assertEquals(
            "water_live",
            levelSession.document.grid
                .cellAt(0, 0)
                .topTex,
        )
        assertNull(
            levelSession.document.grid
                .cellAt(0, 0)
                .skirtTex,
        )
        assertNull(
            levelSession.document.grid
                .cellAt(0, 0)
                .panelTex,
        )
        assertTrue(levelSession.currentValidation().valid)

        // 3. requestSave() on untitled level delegates to StudioState.saveSession -> .sfxlevel
        val levelFile = File(tempDir, "live_scene.sfxlevel")
        platform.saveFileQueue.addLast(File(tempDir, "live_scene"))
        assertTrue(levelSession.requestSave())
        assertTrue(levelFile.exists())
        assertTrue(File(tempDir, "live_scene.map").exists())
        assertTrue(File(tempDir, "live_scene.i").exists())
        assertTrue(File(tempDir, "live_scene_preview.png").exists())

        // Re-opening .sfxlevel focuses existing tab in StudioState
        val countBefore = studio.openSessions.size
        studio.openDocument(levelFile)
        assertEquals(countBefore, studio.openSessions.size)
        assertEquals(levelSession, studio.activeSession)
    }

    @Test
    fun testCameraPanRotationPivotAndKeyBindings() {
        val level = LevelEditorState()
        assertEquals(15, level.cameraYaw)
        assertEquals(0, level.cameraPanX)
        assertEquals(0, level.cameraPanY)

        // Verify Q/W/E/A/S/D and Arrow key mappings
        assertEquals(IsoCameraControlAction.ROTATE_CW, mapKeyToCameraAction(Key.Q))
        assertEquals(IsoCameraControlAction.PAN_UP, mapKeyToCameraAction(Key.W))
        assertEquals(IsoCameraControlAction.PAN_UP, mapKeyToCameraAction(Key.DirectionUp))
        assertEquals(IsoCameraControlAction.ROTATE_CCW, mapKeyToCameraAction(Key.E))
        assertEquals(IsoCameraControlAction.PAN_NEG_X, mapKeyToCameraAction(Key.A))
        assertEquals(IsoCameraControlAction.PAN_NEG_X, mapKeyToCameraAction(Key.DirectionLeft))
        assertEquals(IsoCameraControlAction.PAN_DOWN, mapKeyToCameraAction(Key.S))
        assertEquals(IsoCameraControlAction.PAN_DOWN, mapKeyToCameraAction(Key.DirectionDown))
        assertEquals(IsoCameraControlAction.PAN_POS_X, mapKeyToCameraAction(Key.D))
        assertEquals(IsoCameraControlAction.PAN_POS_X, mapKeyToCameraAction(Key.DirectionRight))

        // Row 1: Q rotates clockwise (+1 yaw), W pans camera up (+2 panY), E rotates CCW (-1 yaw)
        IsoCameraControlAction.ROTATE_CW.perform(level)
        assertEquals(16, level.cameraYaw)
        IsoCameraControlAction.ROTATE_CCW.perform(level)
        assertEquals(15, level.cameraYaw)

        IsoCameraControlAction.PAN_UP.perform(level)
        assertEquals(2, level.cameraPanY)
        IsoCameraControlAction.PAN_DOWN.perform(level)
        assertEquals(0, level.cameraPanY)

        // Row 2: A pans -X (+2 panX / model right), D pans +X (-2 panX / model left)
        IsoCameraControlAction.PAN_NEG_X.perform(level)
        assertEquals(2, level.cameraPanX)
        IsoCameraControlAction.PAN_POS_X.perform(level)
        assertEquals(0, level.cameraPanX)

        // Test gsu_iso_camera.i screen-center pivot:
        // At yaw = 0 (cos = 1, sin = 0), panX = 32, panY = 0 -> rotating 90 deg CW (yaw = 30, cos = 0, sin = 1)
        // moves the offset from (+32, 0) to (0, +16) due to the 2:1 isometric foreshortening!
        level.resetCamera()
        level.cameraYaw = 0
        level.panCameraBy(32, 0)
        assertEquals(32, level.cameraPanX)
        assertEquals(0, level.cameraPanY)

        level.rotateCameraClockwise(30)
        assertEquals(30, level.cameraYaw)
        assertEquals(0, level.cameraPanX)
        assertEquals(16, level.cameraPanY)

        // Full 360-degree rotation in 120 single-step (3°) increments must have zero cumulative drift
        repeat(90) {
            level.rotateCameraClockwise(1)
        }
        assertEquals(0, level.cameraYaw)
        assertEquals(32, level.cameraPanX)
        assertEquals(0, level.cameraPanY)

        // Clamping at +/-128 pixels
        level.panCameraBy(500, -500)
        assertEquals(128, level.cameraPanX)
        assertEquals(-128, level.cameraPanY)
    }
}
