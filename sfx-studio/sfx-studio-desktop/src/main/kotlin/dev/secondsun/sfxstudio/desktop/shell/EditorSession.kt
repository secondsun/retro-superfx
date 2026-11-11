package dev.secondsun.sfxstudio.desktop.shell

import java.io.File

/**
 * Seam for open documents in SuperFX Studio.
 * Represents an active editor tab (Texture Atlas, and future Level Editor / Script sessions).
 */
interface EditorSession {
    val sessionId: String
    val displayTitle: String
    val documentFile: File?
    val hasUnsavedChanges: Boolean
    val statusSummary: String
    val statusMessage: String

    fun save(): Boolean

    fun saveAs(file: File): Boolean
}
