package dev.secondsun.sfxstudio.desktop.shell

import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.prefs.Preferences
import javax.swing.JFileChooser

/**
 * Host platform seam isolating OS file dialogs, directory pickers, clipboard access,
 * user preferences, and platform modifier conventions from [StudioState] and editor sessions.
 *
 * Production builds inject [AwtStudioPlatform]; headless tests inject an in-memory fake.
 */
interface StudioPlatform {
    val isMacOs: Boolean

    fun chooseOpenFile(
        title: String,
        initialDir: String? = null,
        allowedExtensions: Set<String> = emptySet(),
    ): File?

    fun chooseSaveFile(
        title: String,
        defaultFileName: String,
        initialDir: String? = null,
    ): File?

    fun chooseDirectory(
        title: String,
        initialDir: String? = null,
    ): File?

    fun copyToClipboard(text: String)

    fun getPreference(
        key: String,
        default: String? = null,
    ): String?

    fun setPreference(
        key: String,
        value: String,
    )
}

class AwtStudioPlatform : StudioPlatform {
    override val isMacOs: Boolean =
        System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)

    private val studioPrefs by lazy {
        try {
            Preferences.userRoot().node("dev/secondsun/sfxstudio")
        } catch (_: Throwable) {
            null
        }
    }

    private val legacyPrefs by lazy {
        try {
            Preferences.userRoot().node("dev/secondsun/superx/tools/packer")
        } catch (_: Throwable) {
            null
        }
    }

    override fun chooseOpenFile(
        title: String,
        initialDir: String?,
        allowedExtensions: Set<String>,
    ): File? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        initialDir?.let { dir ->
            if (File(dir).isDirectory) {
                dialog.directory = dir
            }
        }
        if (allowedExtensions.isNotEmpty()) {
            val normalizedExts = allowedExtensions.map { it.trimStart('.').lowercase() }.toSet()
            dialog.setFilenameFilter { _, name ->
                val ext = name.substringAfterLast('.', "").lowercase()
                ext in normalizedExts
            }
        }
        dialog.isVisible = true
        return dialog.file?.let { File(dialog.directory, it) }
    }

    override fun chooseSaveFile(
        title: String,
        defaultFileName: String,
        initialDir: String?,
    ): File? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
        dialog.file = defaultFileName
        initialDir?.let { dir ->
            if (File(dir).isDirectory) {
                dialog.directory = dir
            }
        }
        dialog.isVisible = true
        return dialog.file?.let { File(dialog.directory, it) }
    }

    override fun chooseDirectory(
        title: String,
        initialDir: String?,
    ): File? {
        val chooser =
            JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = title
                initialDir?.let { dir ->
                    val cur = File(dir)
                    if (cur.isDirectory) currentDirectory = cur
                }
            }
        val res = chooser.showOpenDialog(null)
        return if (res == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    override fun copyToClipboard(text: String) {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    }

    override fun getPreference(
        key: String,
        default: String?,
    ): String? {
        val current = studioPrefs?.get(key, null)
        if (current != null) return current
        return legacyPrefs?.get(key, default)
    }

    override fun setPreference(
        key: String,
        value: String,
    ) {
        try {
            studioPrefs?.put(key, value)
            studioPrefs?.flush()
        } catch (_: Throwable) {
        }
    }
}
