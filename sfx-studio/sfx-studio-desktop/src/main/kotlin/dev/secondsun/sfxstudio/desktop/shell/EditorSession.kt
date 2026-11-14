package dev.secondsun.sfxstudio.desktop.shell

import java.io.File

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

    fun save(): Boolean

    fun saveAs(file: File): Boolean
}
