package dev.secondsun.sfxstudio.desktop.shell

import java.io.File

enum class LevelEditMode {
    TERRAIN,
    GAMEPLAY,
}

/**
 * Polymorphic seam for open documents in SuperFX Studio Desktop.
 *
 * Eliminates session-type downcasting (`session is LevelEditorState`) across menu items,
 * tab-close confirmation, and application-exit save workflows.
 */
interface EditorSession {
    val sessionId: String
    val displayTitle: String
    val defaultExtension: String
    val documentTypeLabel: String
    val documentFile: File?
    val hasUnsavedChanges: Boolean
    val statusSummary: String
    val statusMessage: String
    val canUndo: Boolean
    val canRedo: Boolean

    fun undo()

    fun redo()

    fun save(): Boolean

    fun saveAs(file: File): Boolean
}

/**
 * Optional capability interface implemented by [dev.secondsun.sfxstudio.desktop.level.LevelEditorState]
 * so the unified Studio Command Bar can switch Terrain/Gameplay modes and toggle the Gameplay Overlay
 * without coupling shell code to internal level editor widgets.
 */
interface LevelCommandBarSession : EditorSession {
    var editMode: LevelEditMode
    var showGameplayOverlay: Boolean
    val hasGameplayLayer: Boolean
    val gameplayEntityCount: Int

    fun toggleGameplayOverlay() {
        showGameplayOverlay = !showGameplayOverlay
    }
}
