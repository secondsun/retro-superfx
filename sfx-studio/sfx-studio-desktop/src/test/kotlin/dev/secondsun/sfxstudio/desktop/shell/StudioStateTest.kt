package dev.secondsun.sfxstudio.desktop.shell

import dev.secondsun.sfxstudio.core.atlas.ProjectService
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StudioStateTest {
    @Test
    fun testDefaultSessionCreation() {
        val studio = StudioState()
        assertEquals(1, studio.openSessions.size)
        assertEquals(0, studio.activeSessionIndex)
        assertNotNull(studio.activeSession)
        assertTrue(studio.activeSession is AtlasEditorState)
        assertFalse(studio.hasAnyUnsavedChanges)
    }

    @Test
    fun testOpeningMultipleDocumentsCreatesDistinctTabs(
        @TempDir tempDir: File,
    ) {
        val file1 = File(tempDir, "doc1.sfxproj")
        val file2 = File(tempDir, "doc2.sfxatlas")

        ProjectService.saveProject(file1, "doc1", TextureAtlas(64, 64), SnesPalette(), 16)
        ProjectService.saveProject(file2, "doc2", TextureAtlas(32, 32), SnesPalette(), 8)

        val studio = StudioState()
        val initialCount = studio.openSessions.size

        studio.openDocument(file1)
        assertEquals(initialCount + 1, studio.openSessions.size)
        assertEquals(studio.openSessions.size - 1, studio.activeSessionIndex)
        assertEquals(file1.canonicalFile, studio.activeSession?.documentFile?.canonicalFile)

        studio.openDocument(file2)
        assertEquals(initialCount + 2, studio.openSessions.size)
        assertEquals(studio.openSessions.size - 1, studio.activeSessionIndex)
        assertEquals(file2.canonicalFile, studio.activeSession?.documentFile?.canonicalFile)
    }

    @Test
    fun testReopeningAlreadyOpenFileFocusesTab(
        @TempDir tempDir: File,
    ) {
        val file1 = File(tempDir, "projA.sfxproj")
        val file2 = File(tempDir, "projB.sfxproj")

        ProjectService.saveProject(file1, "projA", TextureAtlas(64, 64), SnesPalette(), 16)
        ProjectService.saveProject(file2, "projB", TextureAtlas(32, 32), SnesPalette(), 8)

        val studio = StudioState()
        studio.openDocument(file1)
        val tabIndex1 = studio.activeSessionIndex

        studio.openDocument(file2)
        val tabIndex2 = studio.activeSessionIndex
        assertNotEquals(tabIndex1, tabIndex2)
        val countBeforeReopen = studio.openSessions.size

        // Re-open file1
        studio.openDocument(file1)
        assertEquals(countBeforeReopen, studio.openSessions.size, "Should not duplicate already open document")
        assertEquals(tabIndex1, studio.activeSessionIndex, "Should focus existing tab for file1")
    }

    @Test
    fun testHasAnyUnsavedChangesTracksAcrossSessions(
        @TempDir tempDir: File,
    ) {
        val file1 = File(tempDir, "test1.sfxproj")
        val file2 = File(tempDir, "test2.sfxproj")

        ProjectService.saveProject(file1, "test1", TextureAtlas(64, 64), SnesPalette(), 16)
        ProjectService.saveProject(file2, "test2", TextureAtlas(64, 64), SnesPalette(), 16)

        val studio = StudioState(listOf(file1, file2))
        assertEquals(2, studio.openSessions.size)
        assertFalse(studio.hasAnyUnsavedChanges)

        // Modify first session
        val session1 = studio.openSessions[0] as AtlasEditorState
        session1.updatePaletteSlot(2, SnesColor(10, 10, 10))
        assertTrue(session1.hasUnsavedChanges)
        assertTrue(studio.hasAnyUnsavedChanges, "Studio should report dirty when any open session is dirty")

        // Save first session
        session1.save()
        assertFalse(session1.hasUnsavedChanges)
        assertFalse(studio.hasAnyUnsavedChanges)

        // Modify second session
        val session2 = studio.openSessions[1] as AtlasEditorState
        session2.atlas = session2.atlas.withTexture(SourceTexture("tex", 16, 16, 0, 0, ByteArray(256)))
        assertTrue(session2.hasUnsavedChanges)
        assertTrue(studio.hasAnyUnsavedChanges)
    }

    @Test
    fun testClosingCleanVsDirtySession(
        @TempDir tempDir: File,
    ) {
        val file1 = File(tempDir, "clean.sfxproj")
        ProjectService.saveProject(file1, "clean", TextureAtlas(64, 64), SnesPalette(), 16)

        val studio = StudioState(listOf(file1))
        assertEquals(1, studio.openSessions.size)
        studio.newAtlasSession()
        assertEquals(2, studio.openSessions.size)

        // Tab 0 is clean -> requestCloseSession should close it immediately
        studio.requestCloseSession(0)
        assertNull(studio.pendingCloseSessionIndex)
        assertEquals(1, studio.openSessions.size)

        // Remaining tab is dirtied
        val remaining = studio.openSessions[0] as AtlasEditorState
        remaining.updatePaletteSlot(1, SnesColor(20, 20, 20))
        assertTrue(remaining.hasUnsavedChanges)

        // requestCloseSession on dirty tab should prompt via pendingCloseSessionIndex
        studio.requestCloseSession(0)
        assertEquals(0, studio.pendingCloseSessionIndex)
        assertEquals(1, studio.openSessions.size, "Should not close until user confirms")

        // Confirm discard
        studio.closeSession(0)
        assertNull(studio.pendingCloseSessionIndex)
        // Closing the last tab should auto-create a new empty untitled session
        assertEquals(1, studio.openSessions.size)
        assertFalse(studio.openSessions[0].hasUnsavedChanges)
    }

    @Test
    fun testConstructorWithInitialFiles(
        @TempDir tempDir: File,
    ) {
        val file1 = File(tempDir, "first.sfxproj")
        val file2 = File(tempDir, "second.sfxatlas")

        ProjectService.saveProject(file1, "first", TextureAtlas(64, 64), SnesPalette(), 16)
        ProjectService.saveProject(file2, "second", TextureAtlas(64, 64), SnesPalette(), 16)

        val studio = StudioState(listOf(file1, file2))
        assertEquals(2, studio.openSessions.size)
        assertEquals(1, studio.activeSessionIndex)
        assertEquals(file2.canonicalFile, studio.activeSession?.documentFile?.canonicalFile)
    }

    @Test
    fun testUiScaleControls() {
        val studio = StudioState()
        assertEquals(1.0f, studio.uiScaleMultiplier)

        studio.zoomInUi()
        assertEquals(1.1f, studio.uiScaleMultiplier, 0.01f)

        studio.zoomOutUi()
        assertEquals(1.0f, studio.uiScaleMultiplier, 0.01f)

        studio.setUiScale(2.5f)
        assertEquals(2.5f, studio.uiScaleMultiplier)

        studio.resetUiScale()
        assertEquals(1.0f, studio.uiScaleMultiplier)
    }
}
