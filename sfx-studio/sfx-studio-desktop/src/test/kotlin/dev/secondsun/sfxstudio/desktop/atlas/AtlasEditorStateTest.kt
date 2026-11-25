package dev.secondsun.sfxstudio.desktop.atlas

import dev.secondsun.sfxstudio.core.atlas.DescriptorAlias
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.atlas.VirtualSlice
import dev.secondsun.sfxstudio.desktop.shell.FakeStudioPlatform
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class AtlasEditorStateTest {
    @Test
    fun testInitialStateNotDirty() {
        val state = AtlasEditorState(standalonePlatform = FakeStudioPlatform())
        assertFalse(state.hasUnsavedChanges, "Newly created state should not have unsaved changes")
        assertEquals("TEXTURES", state.displayTitle)
        assertEquals("TEXTURES", state.projectName)
        assertEquals("sfxproj", state.defaultExtension)
        assertNull(state.documentFile)
        assertTrue(state.statusSummary.contains("Atlas: 128x128"))
    }

    @Test
    fun testModifyingAtlasMarksDirty(
        @TempDir tempDir: File,
    ) {
        val state = AtlasEditorState(standalonePlatform = FakeStudioPlatform())
        assertFalse(state.hasUnsavedChanges)

        val tex = SourceTexture("test", 16, 16, 0, 0, ByteArray(256))
        state.atlas = state.atlas.withTexture(tex)
        assertTrue(state.hasUnsavedChanges, "Adding a texture should mark unsaved changes")

        val projFile = File(tempDir, "test.sfxproj")
        val saved = state.saveProject(projFile)
        assertTrue(saved)
        assertFalse(state.hasUnsavedChanges, "Saving should clear unsaved changes")
        assertEquals(projFile, state.documentFile)
    }

    @Test
    fun testModifyingPaletteMarksDirty(
        @TempDir tempDir: File,
    ) {
        val state = AtlasEditorState(standalonePlatform = FakeStudioPlatform())
        val projFile = File(tempDir, "test.sfxproj")
        state.saveProject(projFile)
        assertFalse(state.hasUnsavedChanges)

        state.updatePaletteSlot(2, SnesColor(15, 15, 15))
        assertTrue(state.hasUnsavedChanges, "Updating palette slot should mark unsaved changes")

        state.newProject()
        assertFalse(state.hasUnsavedChanges, "New project should not have unsaved changes")
    }

    @Test
    fun testOpenProjectClearsDirty(
        @TempDir tempDir: File,
    ) {
        val state = AtlasEditorState(standalonePlatform = FakeStudioPlatform())
        val tex = SourceTexture("sprite", 16, 16, 0, 0, ByteArray(256))
        state.atlas = state.atlas.withTexture(tex)
        assertTrue(state.hasUnsavedChanges)

        val projFile = File(tempDir, "sample.sfxproj")
        state.saveProject(projFile)
        assertFalse(state.hasUnsavedChanges)

        state.updatePaletteSlot(3, SnesColor(5, 5, 5))
        assertTrue(state.hasUnsavedChanges)

        state.openProject(projFile)
        assertFalse(state.hasUnsavedChanges, "Opening project should clear unsaved changes")
    }

    @Test
    fun testStandaloneQuantizePaletteNeverAddsDummyAllTexture() {
        val state = AtlasEditorState(standalonePlatform = FakeStudioPlatform())
        state.updatePaletteSlot(1, SnesColor(31, 0, 0))
        state.updatePaletteSlot(2, SnesColor(0, 31, 0))
        state.updatePaletteSlot(3, SnesColor(0, 0, 31))

        val texA = SourceTexture("grass", 16, 16, 0, 0, ByteArray(256) { 1 })
        val texB = SourceTexture("water", 16, 16, 16, 0, ByteArray(256) { 3 })
        state.atlas = state.atlas.withTexture(texA).withTexture(texB)
        state.hasUnsavedChanges = false

        state.quantizePalette()

        assertEquals(2, state.atlas.textures.size)
        assertFalse(
            state.atlas.textures.any { it.name.equals("ALL", ignoreCase = true) },
            "Quantize Palette must never insert a dummy 'ALL' texture into the atlas",
        )
        assertTrue(state.currentValidation().valid)
    }

    @Test
    fun testPendingImportAutoGrowsWarnsBank71AndNeverStacksAtOriginWhenFull(
        @TempDir tempDir: File,
    ) {
        val studio = StudioState(platform = FakeStudioPlatform())
        val state = studio.openSessions[0] as AtlasEditorState

        // Fill all 15 palette slots (with slot 15 = pure red) and 16x16 atlas so unmapped blue opens PendingTextureImport
        for (slot in 1..14) {
            state.updatePaletteSlot(slot, SnesColor(slot * 2, 0, 0))
        }
        state.updatePaletteSlot(15, SnesColor(31, 0, 0))
        state.atlas =
            TextureAtlas(
                width = 16,
                height = 16,
                textures = listOf(SourceTexture("first", 16, 16, 0, 0, ByteArray(256) { 1 })),
            )

        // Write a 16x16 PNG with an unmapped color (pure blue) so NeedsRemapping is triggered
        val bluePng = File(tempDir, "blue_tile.png")
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(0, 0, 255)
        g.fillRect(0, 0, 16, 16)
        g.dispose()
        ImageIO.write(img, "PNG", bluePng)

        state.importFile(bluePng)
        val pending = state.pendingImport
        assertNotNull(pending, "Unmapped color should open PendingTextureImport capability")

        // Commit manual remap to slot 2: desktop AUTO_GROW_MAX_256 grows the 16x16 atlas cleanly
        val mapping = pending!!.uniqueColors.associateWith { 2 }
        assertTrue(state.commitManualImport(mapping))
        assertNull(state.pendingImport)
        assertEquals(2, state.atlas.textures.size)
        assertTrue(state.currentValidation().valid, "Imported texture must not collide at (0, 0)")

        // Resize to 256x128 (32 KB) and import a 256x128 Color.RED PNG matching slot 15 so auto-grow expands to 256x256 (64 KB > Bank $71)
        state.setAtlasDimensions(256, 128)
        val bigPng = File(tempDir, "big_sheet.png")
        val bigImg = BufferedImage(256, 128, BufferedImage.TYPE_INT_ARGB)
        val bg = bigImg.createGraphics()
        bg.color = Color.RED
        bg.fillRect(0, 0, 256, 128)
        bg.dispose()
        ImageIO.write(bigImg, "PNG", bigPng)

        state.importFile(bigPng)
        assertEquals(3, state.atlas.textures.size)
        assertEquals(256, state.atlas.width)
        assertEquals(256, state.atlas.height)
        assertTrue(
            state.currentValidation().warnings.any { it.code == "ATLAS_EXCEEDS_BANK_71" },
            "Growing past 32 KB must emit ATLAS_EXCEEDS_BANK_71 warning",
        )

        // Fill 256x256 completely and verify an unplaceable remap import fails cleanly without (0,0) stacking
        state.atlas =
            TextureAtlas(
                width = 256,
                height = 256,
                textures = listOf(SourceTexture("full_sheet", 256, 256, 0, 0, ByteArray(256 * 256) { 1 })),
            )
        val greenPng = File(tempDir, "green_overflow.png")
        val greenImg = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val gg = greenImg.createGraphics()
        gg.color = Color(0, 255, 0)
        gg.fillRect(0, 0, 16, 16)
        gg.dispose()
        ImageIO.write(greenImg, "PNG", greenPng)

        state.importFile(greenPng)
        assertNotNull(state.pendingImport)
        assertFalse(
            state.commitSnapImport(),
            "Full 256x256 atlas must reject placement rather than stacking at (0, 0)",
        )
        assertEquals(1, state.atlas.textures.size)
        assertNotNull(studio.errorMessage)
    }

    @Test
    fun testCascadingRenameAndSafeDeleteDependents() {
        val studio = StudioState(platform = FakeStudioPlatform())
        val state = studio.openSessions[0] as AtlasEditorState

        val brickPixels = ByteArray(32 * 32) { 1 }
        val mossPixels = ByteArray(16 * 16) { 2 }
        state.atlas =
            TextureAtlas(
                width = 64,
                height = 64,
                textures =
                    listOf(
                        SourceTexture("brick", 32, 32, 0, 0, brickPixels),
                        SourceTexture("moss", 16, 16, 32, 0, mossPixels),
                    ),
                slices = listOf(VirtualSlice("brick_half", "brick", 0, 0, 16, 16)),
                aliases = listOf(DescriptorAlias("wall", "brick")),
                descriptorOrder = listOf("brick", "brick_half", "wall", "moss"),
            )

        // Default deleteTexture rejects deletion when slices/aliases depend on the texture
        assertFalse(state.deleteTexture(0))
        assertEquals(2, state.atlas.textures.size)
        assertNotNull(studio.errorMessage)
        assertTrue(studio.errorMessage!!.contains("brick_half"))
        assertTrue(studio.errorMessage!!.contains("wall"))

        // Renaming "brick" -> "cobble" cascades across slice, alias, and descriptorOrder
        studio.errorMessage = null
        assertTrue(state.applyTextureEdit(0, "cobble", brickPixels))
        assertEquals("cobble", state.atlas.textures[0].name)
        assertEquals("cobble", state.atlas.slices[0].sourceTexture)
        assertEquals("cobble", state.atlas.aliases[0].target)
        assertEquals(listOf("cobble", "brick_half", "wall", "moss"), state.atlas.descriptorOrder)
        assertTrue(state.currentValidation().valid)

        // Case-insensitive duplicate rename ("moss" -> "COBBLE") is rejected
        assertFalse(state.applyTextureEdit(1, "COBBLE", mossPixels))
        assertEquals("moss", state.atlas.textures[1].name)
        assertNotNull(studio.errorMessage)
    }

    @Test
    fun testEditorSessionInterfaceMethods(
        @TempDir tempDir: File,
    ) {
        val state = AtlasEditorState(standalonePlatform = FakeStudioPlatform())
        assertNotNull(state.sessionId)

        val projFile = File(tempDir, "custom.sfxatlas")
        val savedAs = state.saveAs(projFile)
        assertTrue(savedAs)
        assertEquals(projFile, state.documentFile)
        assertFalse(state.hasUnsavedChanges)

        state.updatePaletteSlot(4, SnesColor(10, 10, 10))
        assertTrue(state.hasUnsavedChanges)
        val savedAgain = state.save()
        assertTrue(savedAgain)
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun testAutoPackAndAutoFit() {
        val state = AtlasEditorState(standalonePlatform = FakeStudioPlatform())
        val tex1 = SourceTexture("tex1", 16, 16, 0, 0, ByteArray(256))
        val tex2 = SourceTexture("tex2", 16, 16, 0, 0, ByteArray(256))
        state.atlas = state.atlas.withTexture(tex1).withTexture(tex2)

        state.autoPack()
        assertTrue(state.statusMessage.contains("Auto-packed"))

        state.autoFit()
        assertTrue(state.statusMessage.contains("Auto-fitted"))
    }

    @Test
    fun testUndoRedoAndBatchedPaletteEdits() {
        val state = AtlasEditorState(standalonePlatform = FakeStudioPlatform())
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
        assertFalse(state.hasUnsavedChanges)

        val tex = SourceTexture("tile", 16, 16, 0, 0, ByteArray(256) { 1 })
        state.atlas = state.atlas.withTexture(tex)
        assertTrue(state.canUndo)
        assertFalse(state.canRedo)
        assertTrue(state.hasUnsavedChanges)

        // Move texture
        assertTrue(state.moveTexture(0, 16, 16))
        assertEquals(16, state.atlas.textures[0].x)

        // Batched RGB slider edits collapse into a single undo entry
        state.beginEditBatch()
        state.updatePaletteSlot(1, SnesColor(5, 0, 0))
        state.updatePaletteSlot(1, SnesColor(15, 0, 0))
        state.updatePaletteSlot(1, SnesColor(31, 0, 0))
        state.endEditBatch()
        assertEquals(SnesColor(31, 0, 0), state.palette[1])

        // Undo 1: reverts entire batched palette drag in one step
        state.undo()
        assertNotEquals(SnesColor(31, 0, 0), state.palette[1])
        assertEquals(16, state.atlas.textures[0].x)
        assertTrue(state.canRedo)

        // Undo 2: reverts moveTexture
        state.undo()
        assertEquals(0, state.atlas.textures[0].x)

        // Undo 3: reverts adding texture and restores clean (unsaved=false) state
        state.undo()
        assertTrue(state.atlas.textures.isEmpty())
        assertFalse(state.canUndo)
        assertFalse(state.hasUnsavedChanges)

        // Redo restores all 3 steps
        state.redo()
        assertEquals(1, state.atlas.textures.size)
        state.redo()
        assertEquals(16, state.atlas.textures[0].x)
        state.redo()
        assertEquals(SnesColor(31, 0, 0), state.palette[1])
        assertFalse(state.canRedo)

        // newProject clears undo/redo history
        state.newProject()
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
    }

    @Test
    fun testSliceCrudCascadingRenameAndUndoRedo() {
        val state = AtlasEditorState(standalonePlatform = FakeStudioPlatform())
        val roofPixels = ByteArray(32 * 32) { 2 }
        state.atlas =
            TextureAtlas(
                width = 64,
                height = 64,
                textures = listOf(SourceTexture("roof", 32, 32, 0, 0, roofPixels)),
            )

        // Add slice inside "roof"
        val slice = VirtualSlice("roof_top_half", "roof", 0, 0, 16, 16)
        assertTrue(state.addSlice(slice))
        assertEquals(1, state.atlas.slices.size)
        assertEquals("roof_top_half", state.selectedSliceName)
        assertTrue(state.currentValidation().valid)

        // Out-of-bounds slice is rejected cleanly
        assertFalse(state.addSlice(VirtualSlice("bad_slice", "roof", 24, 0, 16, 16)))
        assertEquals(1, state.atlas.slices.size)
        assertNotNull(state.lastError)

        // Add an alias targeting the slice and pin order
        assertTrue(state.addAlias(DescriptorAlias("hero_trim", "roof_top_half")))
        assertTrue(state.pinDescriptorOrder())
        assertEquals(listOf("roof", "hero_trim", "roof_top_half"), state.atlas.descriptorOrder)

        // Update slice name & offset -> cascades into alias target and pinned descriptorOrder
        assertTrue(
            state.updateSlice(
                0,
                VirtualSlice("roof_ridge", "roof", 16, 0, 16, 16),
            ),
        )
        assertEquals("roof_ridge", state.atlas.slices[0].name)
        assertEquals(16, state.atlas.slices[0].x)
        assertEquals("roof_ridge", state.atlas.aliases[0].target)
        assertEquals(listOf("roof", "hero_trim", "roof_ridge"), state.atlas.descriptorOrder)
        assertEquals("roof_ridge", state.selectedSliceName)

        // Non-cascading deleteSlice rejects when alias depends on it
        assertFalse(state.deleteSlice(0, cascadeDependents = false))
        assertEquals(1, state.atlas.slices.size)
        assertNotNull(state.lastError)

        // Cascading deleteSlice removes slice + dependent alias and prunes descriptorOrder
        assertTrue(state.deleteSlice(0, cascadeDependents = true))
        assertTrue(state.atlas.slices.isEmpty())
        assertTrue(state.atlas.aliases.isEmpty())
        assertEquals(listOf("roof"), state.atlas.descriptorOrder)
        assertNull(state.selectedSliceName)

        // Undo restores slice, alias, and pinned order; Redo re-applies deletion
        state.undo()
        assertEquals(1, state.atlas.slices.size)
        assertEquals("roof_ridge", state.atlas.slices[0].name)
        assertEquals(1, state.atlas.aliases.size)
        assertEquals(listOf("roof", "hero_trim", "roof_ridge"), state.atlas.descriptorOrder)

        state.redo()
        assertTrue(state.atlas.slices.isEmpty())
        assertTrue(state.atlas.aliases.isEmpty())
    }

    @Test
    fun testAliasCrudCycleRejectionAndCascadeModalWorkflow() {
        val state = AtlasEditorState(standalonePlatform = FakeStudioPlatform())
        state.atlas =
            TextureAtlas(
                width = 64,
                height = 64,
                textures =
                    listOf(
                        SourceTexture("stone", 16, 16, 0, 0, ByteArray(256) { 1 }),
                        SourceTexture("wood", 16, 16, 16, 0, ByteArray(256) { 2 }),
                    ),
            )

        assertTrue(state.addAlias(DescriptorAlias("floor_main", "stone")))
        assertTrue(state.addAlias(DescriptorAlias("floor_alt", "floor_main")))
        assertEquals(2, state.atlas.aliases.size)

        // Reject cyclic alias update (floor_main -> floor_alt -> floor_main)
        assertFalse(state.updateAlias(0, DescriptorAlias("floor_main", "floor_alt")))
        assertEquals("stone", state.atlas.aliases[0].target)
        assertNotNull(state.lastError)

        // Valid rename cascades to downstream alias
        assertTrue(state.updateAlias(0, DescriptorAlias("floor_primary", "wood")))
        assertEquals("floor_primary", state.atlas.aliases[0].name)
        assertEquals("wood", state.atlas.aliases[0].target)
        assertEquals("floor_primary", state.atlas.aliases[1].target)

        // UI requestDeleteAlias opens PendingCascadeDelete without mutating until confirmed
        state.requestDeleteAlias(0)
        val pending = state.pendingCascadeDelete
        assertNotNull(pending)
        assertEquals(CascadeTargetKind.ALIAS, pending!!.kind)
        assertEquals(listOf("floor_alt"), pending.dependentAliases)
        assertEquals(2, state.atlas.aliases.size)

        state.cancelCascadeDelete()
        assertNull(state.pendingCascadeDelete)
        assertEquals(2, state.atlas.aliases.size)

        state.requestDeleteAlias(0)
        assertTrue(state.confirmCascadeDelete())
        assertNull(state.pendingCascadeDelete)
        assertTrue(state.atlas.aliases.isEmpty())

        // Undo restores both aliases
        state.undo()
        assertEquals(2, state.atlas.aliases.size)
    }

    @Test
    fun testPinMoveAndClearDescriptorOrderWithUndoRedo() {
        val state = AtlasEditorState(standalonePlatform = FakeStudioPlatform())
        state.atlas =
            TextureAtlas(
                width = 64,
                height = 64,
                textures =
                    listOf(
                        SourceTexture("alpha", 16, 16, 0, 0, ByteArray(256) { 1 }),
                        SourceTexture("beta", 16, 16, 16, 0, ByteArray(256) { 2 }),
                    ),
                slices = listOf(VirtualSlice("alpha_sub", "alpha", 0, 0, 16, 16)),
            )

        assertNull(state.atlas.descriptorOrder)
        assertTrue(state.pinDescriptorOrder())
        assertEquals(listOf("alpha", "beta", "alpha_sub"), state.atlas.descriptorOrder)

        // Move "alpha_sub" ($02) up to slot $00
        assertTrue(state.moveDescriptorInOrder(2, 0))
        assertEquals(listOf("alpha_sub", "alpha", "beta"), state.atlas.descriptorOrder)
        assertEquals(
            listOf("alpha_sub", "alpha", "beta"),
            state.document
                .descriptorTable()
                .getOrThrow()
                .entries
                .map { it.name },
        )

        // Undo reverts moveDescriptorInOrder
        state.undo()
        assertEquals(listOf("alpha", "beta", "alpha_sub"), state.atlas.descriptorOrder)

        // Redo re-applies moveDescriptorInOrder
        state.redo()
        assertEquals(listOf("alpha_sub", "alpha", "beta"), state.atlas.descriptorOrder)

        // Clear pinned order resets to null (automatic)
        assertTrue(state.clearDescriptorOrder())
        assertNull(state.atlas.descriptorOrder)
    }
}
