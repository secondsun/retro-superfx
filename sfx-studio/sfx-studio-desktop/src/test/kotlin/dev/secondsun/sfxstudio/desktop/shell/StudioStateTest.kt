package dev.secondsun.sfxstudio.desktop.shell

import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
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
    private fun saveSampleAtlas(
        file: File,
        name: String = file.nameWithoutExtension,
        width: Int = 64,
        height: Int = 64,
        gridSnap: Int = 16,
        textures: List<SourceTexture> = emptyList(),
    ) {
        val palette = SnesPalette().withSlot(1, SnesColor(20, 10, 5), assigned = true)
        AtlasDocument(
            name = name,
            atlas = TextureAtlas(width = width, height = height, textures = textures),
            palette = palette,
            gridSnap = gridSnap,
        ).saveAndExport(file)
    }

    @Test
    fun testDefaultSessionCreation() {
        val studio = StudioState(platform = FakeStudioPlatform())
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

        saveSampleAtlas(file1, "doc1", 64, 64, 16)
        saveSampleAtlas(file2, "doc2", 32, 32, 8)

        val studio = StudioState(platform = FakeStudioPlatform())
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

        saveSampleAtlas(file1, "projA", 64, 64, 16)
        saveSampleAtlas(file2, "projB", 32, 32, 8)

        val studio = StudioState(platform = FakeStudioPlatform())
        studio.openDocument(file1)
        val tabIndex1 = studio.activeSessionIndex

        studio.openDocument(file2)
        val tabIndex2 = studio.activeSessionIndex
        assertNotEquals(tabIndex1, tabIndex2)
        val countBeforeReopen = studio.openSessions.size

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

        saveSampleAtlas(file1, "test1")
        saveSampleAtlas(file2, "test2")

        val studio = StudioState(listOf(file1, file2), platform = FakeStudioPlatform())
        assertEquals(2, studio.openSessions.size)
        assertFalse(studio.hasAnyUnsavedChanges)

        val session1 = studio.openSessions[0] as AtlasEditorState
        session1.updatePaletteSlot(2, SnesColor(10, 10, 10))
        assertTrue(session1.hasUnsavedChanges)
        assertTrue(studio.hasAnyUnsavedChanges)

        session1.save()
        assertFalse(session1.hasUnsavedChanges)
        assertFalse(studio.hasAnyUnsavedChanges)

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
        saveSampleAtlas(file1, "clean")

        val studio = StudioState(listOf(file1), platform = FakeStudioPlatform())
        assertEquals(1, studio.openSessions.size)
        studio.newAtlasSession()
        assertEquals(2, studio.openSessions.size)

        studio.requestCloseSession(0)
        assertNull(studio.pendingCloseSessionIndex)
        assertEquals(1, studio.openSessions.size)

        val remaining = studio.openSessions[0] as AtlasEditorState
        remaining.updatePaletteSlot(1, SnesColor(20, 20, 20))
        assertTrue(remaining.hasUnsavedChanges)

        studio.requestCloseSession(0)
        assertEquals(0, studio.pendingCloseSessionIndex)
        assertEquals(1, studio.openSessions.size)

        studio.closeSession(0)
        assertNull(studio.pendingCloseSessionIndex)
        assertEquals(1, studio.openSessions.size)
        assertFalse(studio.openSessions[0].hasUnsavedChanges)
    }

    @Test
    fun testUntitledLevelSavesWithSfxlevelExtensionNotSfxproj(
        @TempDir tempDir: File,
    ) {
        val atlasFile = File(tempDir, "SHARED.sfxproj")
        saveSampleAtlas(
            atlasFile,
            "SHARED",
            textures = listOf(SourceTexture("grass", 16, 16, 0, 0, ByteArray(256) { 1 })),
        )

        val platform = FakeStudioPlatform()
        val studio = StudioState(platform = platform)
        val level = studio.newLevelSession("dungeon", atlasFile, cols = 4, rows = 4)

        // User enters an extensionless name in the Save dialog
        platform.saveFileQueue.addLast(File(tempDir, "dungeon_saved"))

        assertTrue(studio.saveActiveSession())
        assertEquals("dungeon.sfxlevel", platform.saveDialogDefaultNames.single())
        assertEquals("Save Level Document", platform.saveDialogTitles.single())

        val expectedLevelFile = File(tempDir, "dungeon_saved.sfxlevel")
        assertTrue(expectedLevelFile.exists(), "Untitled level must save with .sfxlevel extension")
        assertFalse(File(tempDir, "dungeon_saved.sfxproj").exists(), "Untitled level must never save as .sfxproj")
        assertTrue(File(tempDir, "dungeon_saved.map").exists())
        assertEquals(expectedLevelFile, level.documentFile)
    }

    @Test
    fun testSaveAllDirtySessionsSurfacesInactiveTabFailureAndSavesUntitledLevel(
        @TempDir tempDir: File,
    ) {
        val atlasFile = File(tempDir, "TAB0.sfxproj")
        saveSampleAtlas(
            atlasFile,
            "TAB0",
            textures = listOf(SourceTexture("floor", 16, 16, 0, 0, ByteArray(256) { 1 })),
        )

        val platform = FakeStudioPlatform()
        val studio = StudioState(listOf(atlasFile), platform = platform)
        val tab0Atlas = studio.openSessions[0] as AtlasEditorState

        // Create dirty untitled level in Tab 1 (now the active tab)
        val tab1Level = studio.newLevelSession("keep_me", atlasFile, cols = 4, rows = 4)
        tab1Level.stepSelectedHeight(2)
        assertEquals(1, studio.activeSessionIndex)

        // Corrupt inactive Tab 0 with an overlapping texture so its save() fails validation
        tab0Atlas.atlas =
            tab0Atlas.atlas.withTexture(SourceTexture("overlap", 16, 16, 0, 0, ByteArray(256) { 1 }))
        assertTrue(tab0Atlas.hasUnsavedChanges)

        assertFalse(studio.saveAllDirtySessions(), "Save All must abort and return false when an inactive tab fails")
        assertNotNull(studio.errorMessage, "Inactive-tab save error must surface on StudioState.errorMessage")
        assertTrue(studio.errorMessage!!.contains("Collision detected"))

        // Fix Tab 0 collision and retry Save All
        studio.errorMessage = null
        tab0Atlas.moveTexture(1, 16, 0)
        platform.saveFileQueue.addLast(File(tempDir, "keep_me"))

        assertTrue(studio.saveAllDirtySessions())
        assertNull(studio.errorMessage)
        assertFalse(studio.hasAnyUnsavedChanges)
        assertTrue(File(tempDir, "keep_me.sfxlevel").exists())
    }

    @Test
    fun testPromptOpenDocumentUsesInjectedPlatform(
        @TempDir tempDir: File,
    ) {
        val file1 = File(tempDir, "opened.sfxproj")
        saveSampleAtlas(file1, "opened")

        val platform = FakeStudioPlatform()
        platform.openFileQueue.addLast(file1)

        val studio = StudioState(platform = platform)
        studio.promptOpenDocument()

        assertEquals(file1.canonicalFile, studio.activeSession?.documentFile?.canonicalFile)
        assertEquals(tempDir.absolutePath, platform.preferences["lastImportDirectory"])
    }

    @Test
    fun testConstructorWithInitialFiles(
        @TempDir tempDir: File,
    ) {
        val file1 = File(tempDir, "first.sfxproj")
        val file2 = File(tempDir, "second.sfxatlas")

        saveSampleAtlas(file1, "first")
        saveSampleAtlas(file2, "second")

        val studio = StudioState(listOf(file1, file2), platform = FakeStudioPlatform())
        assertEquals(2, studio.openSessions.size)
        assertEquals(1, studio.activeSessionIndex)
        assertEquals(file2.canonicalFile, studio.activeSession?.documentFile?.canonicalFile)
    }

    @Test
    fun testUiScaleControls() {
        val studio = StudioState(platform = FakeStudioPlatform())
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
