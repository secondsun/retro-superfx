package dev.secondsun.sfxstudio.desktop.level

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.EdgePanels
import dev.secondsun.sfxstudio.core.level.FacingDirection
import dev.secondsun.sfxstudio.core.level.GameplayBinarySerializer
import dev.secondsun.sfxstudio.core.level.GridCoord
import dev.secondsun.sfxstudio.core.level.InteractableKind
import dev.secondsun.sfxstudio.core.level.NpcAiType
import dev.secondsun.sfxstudio.core.level.RewardKind
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import dev.secondsun.sfxstudio.desktop.StudioRootContent
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.shell.FakeStudioPlatform
import dev.secondsun.sfxstudio.desktop.shell.LevelEditMode
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
                for (i in 0 until 15) {
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
        for (step in 0 until 90) {
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

    @Test
    fun testSelectingOccludedOrOffscreenCellMovesAndRotatesCameraButEditingDoesNot(
        @TempDir tempDir: File,
    ) {
        val atlasFile = createAtlasOnDisk(tempDir)
        val platform = FakeStudioPlatform()
        val studio = StudioState(listOf(atlasFile), platform = platform)

        // Build a 10x10 map where cell (0, 0) at h=0 is completely occluded at yaw=15 by a tall h=8 wall at (1, 1)/(1, 0)/(0, 1)
        val level = studio.newLevelSession("occluded_arena", atlasFile, cols = 10, rows = 10)
        level.selectRect(1, 0, 2, 2)
        level.stepSelectedHeight(8)
        level.selectSingleCell(0, 1)
        level.stepSelectedHeight(8)

        // Reset camera to default yaw=15, pan=(0, 0) where (0, 0) is behind the tall wall
        level.resetCamera()
        assertEquals(15, level.cameraYaw)
        assertEquals(0, level.cameraPanX)
        assertEquals(0, level.cameraPanY)

        // Selecting (0, 0) must rotate and/or pan the camera so (0, 0) is both in-frame and unoccluded
        level.selectSingleCell(0, 0)
        val (atlas, palette) = level.resolveAtlasAndPalette()!!
        val afterSelectRender =
            dev.secondsun.sfxstudio.core.level.IsoSoftwareRenderer.render(
                grid = level.document.grid,
                atlas = atlas,
                palette = palette,
                yawIndex = level.cameraYaw,
                panX = level.cameraPanX,
                panY = level.cameraPanY,
            )
        val poly0 = afterSelectRender.cellFloorPolygons[0]
        assertTrue(
            poly0.all { it.x in 0 until afterSelectRender.width && it.y in 0 until afterSelectRender.height },
            "Selected cell (0, 0) outline must be fully inside the 256x160 3D preview",
        )
        val visiblePixels = afterSelectRender.cellPickBuffer.count { it == 0 }
        assertTrue(
            visiblePixels >= 48,
            "Selected cell (0, 0) must be unoccluded in the 3D render (got $visiblePixels visible px at yaw=${level.cameraYaw})",
        )

        // Editing the selected cell must NOT move or rotate the camera even if the edit raises it off-screen
        val yawBeforeEdit = level.cameraYaw
        val panXBeforeEdit = level.cameraPanX
        val panYBeforeEdit = level.cameraPanY
        level.stepSelectedHeight(10)
        level.assignTextureToSlot(ActiveTextureSlot.TOP, "stone")
        assertEquals(yawBeforeEdit, level.cameraYaw, "Editing must not rotate camera")
        assertEquals(panXBeforeEdit, level.cameraPanX, "Editing must not pan camera X")
        assertEquals(panYBeforeEdit, level.cameraPanY, "Editing must not pan camera Y")

        // When animateCameraReveal is enabled (live UI), selection queues a reveal target instead of snapping
        level.resetCamera()
        level.animateCameraReveal = true
        level.selectSingleCell(0, 0)
        assertEquals(15, level.cameraYaw, "Animated reveal must not snap yaw synchronously")
        val target = level.cameraRevealTarget
        assertTrue(target != null && (target.yaw != 15 || target.panX != 0 || target.panY != 0))

        // Manual camera control immediately cancels any in-flight reveal animation
        level.panCameraBy(2, 0)
        assertNull(level.cameraRevealTarget)
    }

    @Test
    fun testGameplayLayerCrudWalkabilitySpawnsWaypointsAndOpenLevelAutoOverlay(
        @TempDir tempDir: File,
    ) {
        val atlasFile = createAtlasOnDisk(tempDir)
        val platform = FakeStudioPlatform()
        val studio = StudioState(listOf(atlasFile), platform = platform)
        val level = studio.newLevelSession("rpg_courtyard", atlasFile, cols = 8, rows = 8)

        // Terrain-only level starts with gameplay overlay disabled
        assertFalse(level.hasGameplayLayer)
        assertFalse(level.showGameplayOverlay)
        assertEquals(0, level.gameplayEntityCount)
        assertEquals(LevelEditMode.TERRAIN, level.editMode)

        // Save terrain-only file first to verify openLevel(terrainOnly) keeps showGameplayOverlay = false
        val terrainOnlyFile = File(tempDir, "terrain_only.sfxlevel")
        assertTrue(level.saveAs(terrainOnlyFile))

        // Switching to GAMEPLAY automatically enables showGameplayOverlay and syncs compact tab
        level.editMode = LevelEditMode.GAMEPLAY
        assertTrue(level.showGameplayOverlay)
        assertEquals(CompactInspectorTab.GAMEPLAY, level.compactInspectorTab)

        // Enable GameplayLayer
        level.enableGameplayLayer()
        assertTrue(level.hasGameplayLayer)
        assertEquals(1, level.gameplayEntityCount)
        assertEquals(
            PlayerSpawnExpected(0, 0, FacingDirection.SOUTH),
            level.document.gameplay
                ?.playerSpawn
                ?.let { PlayerSpawnExpected(it.col, it.row, it.facing) },
        )

        // Walkability override painting & deduplication
        level.setCellWalkabilityOverride(col = 1, row = 1, walkable = true, moveCost = 4, flags = 3)
        level.setCellWalkabilityOverride(col = 1, row = 1, walkable = true, moveCost = 5, flags = 2)
        assertEquals(
            1,
            level.document.gameplay!!
                .walkabilityOverrides.size,
            "Must replace existing override at same cell",
        )
        assertEquals(5, level.walkabilityOverrideAt(1, 1)?.moveCost)
        assertEquals(2, level.walkabilityOverrideAt(1, 1)?.flags)
        level.clearCellWalkabilityOverride(1, 1)
        assertNull(level.walkabilityOverrideAt(1, 1))

        // PlayerSpawn placement & facing cycle via PLACE_PLAYER tool
        level.gameplayTool = GameplayTool.PLACE_PLAYER
        level.handleGameplayCellClick(2, 6)
        assertEquals(
            2,
            level.document.gameplay!!
                .playerSpawn.col,
        )
        assertEquals(
            6,
            level.document.gameplay!!
                .playerSpawn.row,
        )
        assertEquals(
            FacingDirection.SOUTH,
            level.document.gameplay!!
                .playerSpawn.facing,
        )
        // Clicking the existing PlayerSpawn cell cycles cardinal facing S -> W
        level.handleGameplayCellClick(2, 6)
        assertEquals(
            FacingDirection.WEST,
            level.document.gameplay!!
                .playerSpawn.facing,
        )

        // Add NPC + author up to MAX_WAYPOINTS = 4 patrol waypoints
        val npcIdx = level.addNpc(col = 4, row = 4, id = "patrol_slime", aiType = NpcAiType.GUARD)
        assertEquals(0, npcIdx)
        assertEquals(0, level.selectedNpcIndex)
        assertEquals(2, level.gameplayEntityCount)

        level.gameplayTool = GameplayTool.EDIT_WAYPOINTS
        level.handleGameplayCellClick(4, 4)
        level.handleGameplayCellClick(6, 4)
        level.handleGameplayCellClick(6, 5)
        level.handleGameplayCellClick(4, 5)
        // Attempting a 5th waypoint must be capped at MAX_WAYPOINTS (4)
        level.handleGameplayCellClick(5, 5)

        val slime = level.document.gameplay!!.npcs[0]
        assertEquals(NpcAiType.PATROL, slime.aiType, "Adding waypoints to a GUARD NPC upgrades AI to PATROL")
        assertEquals(GameplayBinarySerializer.MAX_WAYPOINTS, slime.patrolWaypoints.size)
        assertEquals(
            listOf(GridCoord(4, 4), GridCoord(6, 4), GridCoord(6, 5), GridCoord(4, 5)),
            slime.patrolWaypoints,
        )

        // Toggling an existing waypoint removes it cleanly
        level.toggleOrAppendNpcWaypoint(0, 6, 5)
        assertEquals(
            3,
            level.document.gameplay!!
                .npcs[0]
                .patrolWaypoints.size,
        )
        level.toggleOrAppendNpcWaypoint(0, 6, 5)
        assertEquals(
            4,
            level.document.gameplay!!
                .npcs[0]
                .patrolWaypoints.size,
        )

        // Update NPC stats
        level.updateNpc(
            0,
            level.document.gameplay!!
                .npcs[0]
                .copy(hp = 24, atk = 8, def = 3, speed = 5, movePoints = 4),
        )
        assertEquals(
            24,
            level.document.gameplay!!
                .npcs[0]
                .hp,
        )

        // Add & update InteractableSpawn (Chest with ATK_BUFF reward)
        val chestIdx = level.addInteractable(col = 1, row = 2, kind = InteractableKind.CHEST, id = "hidden_chest")
        assertEquals(0, chestIdx)
        assertEquals(0, level.selectedInteractableIndex)
        assertNull(level.selectedNpcIndex)
        assertEquals(3, level.gameplayEntityCount)

        level.updateInteractable(
            0,
            level.document.gameplay!!.interactables[0].copy(
                rewardKind = RewardKind.ATK_BUFF,
                rewardValue = 3,
                closedSpriteId = 5,
                openSpriteId = 6,
            ),
        )
        assertTrue(level.currentValidation().valid)

        // Auto-selecting cells selects matching tactical entity (critical for UC7!)
        level.editMode = LevelEditMode.TERRAIN
        assertEquals(CompactInspectorTab.TERRAIN, level.compactInspectorTab)
        level.selectSingleCell(4, 4)
        assertEquals(0, level.selectedNpcIndex)
        assertNull(level.selectedInteractableIndex)
        level.selectSingleCell(1, 2)
        assertEquals(0, level.selectedInteractableIndex)
        assertNull(level.selectedNpcIndex)

        // Save gameplay level and verify .gmap & _gameplay.i are exported
        val gameplayLevelFile = File(tempDir, "rpg_courtyard.sfxlevel")
        assertTrue(level.saveAs(gameplayLevelFile))
        assertTrue(File(tempDir, "rpg_courtyard.map").exists())
        assertTrue(File(tempDir, "rpg_courtyard.gmap").exists())
        assertTrue(File(tempDir, "rpg_courtyard_gameplay.i").exists())

        // Verify openLevel sets showGameplayOverlay = false on terrain-only vs true on gameplay level
        val freshSession = LevelEditorState(studio = studio)
        assertTrue(freshSession.openLevel(terrainOnlyFile))
        assertFalse(freshSession.showGameplayOverlay, "Terrain-only level must keep gameplay overlay OFF on open")
        assertTrue(freshSession.openLevel(gameplayLevelFile))
        assertTrue(freshSession.showGameplayOverlay, "Level with GameplayLayer must enable gameplay overlay ON on open")
        assertEquals(3, freshSession.gameplayEntityCount)

        // Remove gameplay layer + Undo restores everything
        freshSession.removeGameplayLayer()
        assertFalse(freshSession.hasGameplayLayer)
        freshSession.undo()
        assertTrue(freshSession.hasGameplayLayer)
        assertEquals(3, freshSession.gameplayEntityCount)
    }

    @Test
    fun testPanToolSuppressesEditsAndZoomHitPickMathMatchesViewportLayouts(
        @TempDir tempDir: File,
    ) {
        val atlasFile = createAtlasOnDisk(tempDir)
        val studio = StudioState(platform = FakeStudioPlatform())
        val level = studio.newLevelSession("pan_zoom_test", atlasFile, cols = 8, rows = 8)

        level.brushCell = level.brushCell.copy(baseHeight = 5, topTex = "stone")
        level.activeTool = LevelTool.PAN
        assertTrue(level.isPanToolActive)

        // Clicking or dragging in PAN mode must never paint or change selectedCells
        val initialSelection = level.selectedCells
        level.handleCellInteraction(col = 3, row = 3, isDragContinue = false)
        level.handleCellInteraction(col = 4, row = 4, isDragContinue = true)
        assertEquals(initialSelection, level.selectedCells)
        assertEquals(
            0,
            level.document.grid
                .cellAt(3, 3)
                .baseHeight,
        )
        assertEquals(
            "grass",
            level.document.grid
                .cellAt(3, 3)
                .topTex,
        )

        // Verify 3D Iso viewport zoom & hit-picking consistency across Fit (0f), 1x, 2x, 3x
        for (zoom in listOf(0f, 1f, 2f, 3f)) {
            val layout = computeIsoViewportLayout(canvasW = 800, canvasH = 600, imgW = 256, imgH = 160, zoom = zoom)
            val centerScreen =
                Offset(
                    x = layout.offX + 128.5f * layout.scale,
                    y = layout.offY + 80.5f * layout.scale,
                )
            val (px, py) = mapCanvasToRenderPixel(centerScreen, 800, 600, 256, 160, zoom)
            assertEquals(128, px, "Expected exact X pixel pick at zoom=$zoom")
            assertEquals(80, py, "Expected exact Y pixel pick at zoom=$zoom")

            // Point slightly left/above image must map to negative (-1), never clamp to 0
            val outsideTopLeft = Offset(layout.offX - 2f, layout.offY - 2f)
            val (outX, outY) = mapCanvasToRenderPixel(outsideTopLeft, 800, 600, 256, 160, zoom)
            assertTrue(outX < 0 && outY < 0, "Outside top-left must map to negative render pixel")
        }

        // Verify 2D Grid pan & zoom coordinate mapping
        level.gridZoom = 2f
        level.panGridBy(Offset(40f, -25f))
        val gridLayout =
            computeGridViewportLayout(
                canvasW = 600,
                canvasH = 500,
                cols = 8,
                rows = 8,
                zoom = level.gridZoom,
                panOffset = level.gridPanOffset,
            )
        val cell4x6Screen =
            Offset(
                x = gridLayout.originX + 4.5f * gridLayout.cellPix,
                y = gridLayout.originY + 6.5f * gridLayout.cellPix,
            )
        val picked =
            mapCanvasToGridCoord(
                pos = cell4x6Screen,
                canvasW = 600,
                canvasH = 500,
                cols = 8,
                rows = 8,
                zoom = level.gridZoom,
                panOffset = level.gridPanOffset,
            )
        assertEquals(4 to 6, picked)

        level.resetGridView()
        assertEquals(0f, level.gridZoom)
        assertEquals(Offset.Zero, level.gridPanOffset)
        assertNotNull(mapCanvasToGridCoord(Offset(300f, 250f), 600, 500, 8, 8))
    }

    @Test
    fun testHeadlessResponsiveRenderingAcrossExpandedMediumAndCompact(
        @TempDir tempDir: File,
    ) {
        val atlasFile = createAtlasOnDisk(tempDir)
        val studio = StudioState(listOf(atlasFile), platform = FakeStudioPlatform())
        val level = studio.newLevelSession("responsive_demo", atlasFile, cols = 10, rows = 10)
        level.enableGameplayLayer()
        level.setPlayerSpawn(2, 8, FacingDirection.NORTH)
        level.addNpc(4, 6, id = "patrol_slime", aiType = NpcAiType.PATROL)
        level.toggleOrAppendNpcWaypoint(0, 6, 6)
        level.toggleOrAppendNpcWaypoint(0, 6, 7)
        level.toggleOrAppendNpcWaypoint(0, 4, 7)
        level.addInteractable(2, 3, kind = InteractableKind.CHEST, id = "hidden_chest")
        level.setCellWalkabilityOverride(5, 5, walkable = false)
        level.setCellWalkabilityOverride(3, 4, walkable = true, moveCost = 3)
        level.selectSingleCell(4, 6)

        val previewOutDir =
            System
                .getProperty("sfx.level.preview.dir")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { File(it).also { dir -> dir.mkdirs() } }

        fun renderViewport(
            tag: String,
            width: Int,
            height: Int,
        ) {
            val scene =
                ImageComposeScene(
                    width = width,
                    height = height,
                    density = Density(1f),
                ) {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        StudioRootContent(studioState = studio)
                    }
                }
            val bytes =
                try {
                    scene.render(0L)
                    val img = scene.render(16_000_000L)
                    img.encodeToData(EncodedImageFormat.PNG)?.bytes
                        ?: error("Failed to encode PNG for $tag")
                } finally {
                    scene.close()
                }
            assertTrue(bytes.size > 2048, "Expected rendered PNG > 2KB for $tag")
            if (previewOutDir != null) {
                File(previewOutDir, "$tag.png").writeBytes(bytes)
            }
        }

        // 1. Terrain Mode with active Gameplay Overlay + Tactical Summary Card across all 3 breakpoints
        level.editMode = LevelEditMode.TERRAIN
        renderViewport("terrain_gameplay_overlay_1600x960", 1600, 960)
        renderViewport("terrain_gameplay_overlay_1024x768", 1024, 768)
        renderViewport("terrain_gameplay_overlay_768x1024", 768, 1024)

        // 2. Full Gameplay Mode Inspector across Desktop and Portrait
        level.editMode = LevelEditMode.GAMEPLAY
        renderViewport("gameplay_mode_1600x960", 1600, 960)
        renderViewport("gameplay_mode_768x1024", 768, 1024)

        // 3. NewLevelModal over live level across Desktop and Portrait
        level.editMode = LevelEditMode.TERRAIN
        studio.showNewLevelModal = true
        renderViewport("new_level_modal_1600x960", 1600, 960)
        renderViewport("new_level_modal_768x1024", 768, 1024)
        studio.showNewLevelModal = false

        // 4. Diagnostics Banner & Broken Slot Error Styling
        level.selectSingleCell(3, 3)
        level.updateSelectedCells("Introduce broken texture") { cell ->
            cell.copy(
                topTex = "MISSING_TEX_SYMBOL",
                panels = EdgePanels(north = true, east = true, tall2h = true),
                panelTex = null,
            )
        }
        renderViewport("diagnostics_1600x960", 1600, 960)
        renderViewport("diagnostics_768x1024", 768, 1024)
    }

    private data class PlayerSpawnExpected(
        val col: Int,
        val row: Int,
        val facing: FacingDirection,
    )
}
