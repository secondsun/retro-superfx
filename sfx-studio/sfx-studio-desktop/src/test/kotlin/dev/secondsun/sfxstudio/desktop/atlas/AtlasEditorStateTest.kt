package dev.secondsun.sfxstudio.desktop.atlas

import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AtlasEditorStateTest {
    @Test
    fun testInitialStateNotDirty() {
        val state = AtlasEditorState()
        assertFalse(state.hasUnsavedChanges, "Newly created state should not have unsaved changes")
        assertEquals("TEXTURES", state.displayTitle)
        assertEquals("TEXTURES", state.projectName)
        assertNull(state.documentFile)
        assertTrue(state.statusSummary.contains("Atlas: 128x128"))
    }

    @Test
    fun testModifyingAtlasMarksDirty(
        @TempDir tempDir: File,
    ) {
        val state = AtlasEditorState()
        assertFalse(state.hasUnsavedChanges)

        val tex = SourceTexture("test", 16, 16, 0, 0, ByteArray(256))
        state.atlas = state.atlas.withTexture(tex)
        assertTrue(state.hasUnsavedChanges, "Adding a texture should mark unsaved changes")

        // Save project and verify clean
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
        val state = AtlasEditorState()
        val projFile = File(tempDir, "test.sfxproj")
        state.saveProject(projFile)
        assertFalse(state.hasUnsavedChanges)

        // Modify a palette slot
        state.updatePaletteSlot(2, SnesColor(15, 15, 15))
        assertTrue(state.hasUnsavedChanges, "Updating palette slot should mark unsaved changes")

        // New project should reset dirty flag
        state.newProject()
        assertFalse(state.hasUnsavedChanges, "New project should not have unsaved changes")
    }

    @Test
    fun testOpenProjectClearsDirty(
        @TempDir tempDir: File,
    ) {
        val state = AtlasEditorState()
        val tex = SourceTexture("sprite", 16, 16, 0, 0, ByteArray(256))
        state.atlas = state.atlas.withTexture(tex)
        assertTrue(state.hasUnsavedChanges)

        val projFile = File(tempDir, "sample.sfxproj")
        state.saveProject(projFile)
        assertFalse(state.hasUnsavedChanges)

        // Make another change
        state.updatePaletteSlot(3, SnesColor(5, 5, 5))
        assertTrue(state.hasUnsavedChanges)

        // Re-open saved file
        state.openProject(projFile)
        assertFalse(state.hasUnsavedChanges, "Opening project should clear unsaved changes")
    }

    @Test
    fun testLastImportDirectory() {
        val state = AtlasEditorState()
        state.lastImportDirectory = "/tmp"
        assertEquals("/tmp", state.lastImportDirectory)
    }

    @Test
    fun testEditorSessionInterfaceMethods(
        @TempDir tempDir: File,
    ) {
        val state = AtlasEditorState()
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
        val state = AtlasEditorState()
        val tex1 = SourceTexture("tex1", 16, 16, 0, 0, ByteArray(256))
        val tex2 = SourceTexture("tex2", 16, 16, 0, 0, ByteArray(256))
        state.atlas = state.atlas.withTexture(tex1).withTexture(tex2)

        state.autoPack()
        assertTrue(state.statusMessage.contains("Auto-packed"))

        state.autoFit()
        assertTrue(state.statusMessage.contains("Auto-fitted"))
    }
}
