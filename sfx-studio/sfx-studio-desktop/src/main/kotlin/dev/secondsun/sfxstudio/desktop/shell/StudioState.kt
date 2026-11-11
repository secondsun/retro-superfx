package dev.secondsun.sfxstudio.desktop.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import java.io.File
import java.util.prefs.Preferences

class StudioState(
    initialFiles: List<File> = emptyList(),
) {
    // User preferences
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

    fun getPreference(
        key: String,
        default: String? = null,
    ): String? {
        val current = studioPrefs?.get(key, null)
        if (current != null) return current
        return legacyPrefs?.get(key, default)
    }

    fun setPreference(
        key: String,
        value: String,
    ) {
        try {
            studioPrefs?.put(key, value)
            studioPrefs?.flush()
        } catch (_: Throwable) {
        }
    }

    var lastImportDirectory: String? by mutableStateOf(
        getPreference("lastImportDirectory")?.takeIf { File(it).isDirectory },
    )

    fun updateLastImportDirectory(dir: String) {
        lastImportDirectory = dir
        setPreference("lastImportDirectory", dir)
    }

    var lastExportDirectory: String? by mutableStateOf(
        getPreference("lastExportDirectory")?.takeIf { File(it).isDirectory },
    )

    fun updateLastExportDirectory(dir: String) {
        lastExportDirectory = dir
        setPreference("lastExportDirectory", dir)
    }

    // UI scaling
    var uiScaleMultiplier by mutableStateOf(1.0f)

    fun zoomInUi() {
        uiScaleMultiplier = (Math.round((uiScaleMultiplier + 0.1f) * 10f) / 10f).coerceAtMost(3.0f)
    }

    fun zoomOutUi() {
        uiScaleMultiplier = (Math.round((uiScaleMultiplier - 0.1f) * 10f) / 10f).coerceAtLeast(0.5f)
    }

    fun resetUiScale() {
        uiScaleMultiplier = 1.0f
    }

    fun setUiScale(scale: Float) {
        uiScaleMultiplier = scale.coerceIn(0.5f, 3.0f)
    }

    // Sessions
    private val _openSessions = mutableStateListOf<EditorSession>()
    val openSessions: List<EditorSession> get() = _openSessions

    var activeSessionIndex by mutableStateOf(0)

    val activeSession: EditorSession?
        get() = if (activeSessionIndex in _openSessions.indices) _openSessions[activeSessionIndex] else null

    val hasAnyUnsavedChanges: Boolean
        get() = _openSessions.any { it.hasUnsavedChanges }

    // Modals
    var showAboutModal by mutableStateOf(false)
    var showExitConfirmationModal by mutableStateOf(false)
    var pendingCloseSessionIndex by mutableStateOf<Int?>(null)
    var errorMessage by mutableStateOf<String?>(null)

    init {
        val validFiles =
            initialFiles.filter {
                it.isFile &&
                    (
                        it.extension.equals("sfxproj", ignoreCase = true) ||
                            it.extension.equals("sfxatlas", ignoreCase = true)
                    )
            }
        if (validFiles.isNotEmpty()) {
            for (file in validFiles) {
                openDocument(file)
            }
        } else {
            newAtlasSession()
        }
    }

    fun newAtlasSession(): AtlasEditorState {
        val session = AtlasEditorState(studio = this)
        _openSessions.add(session)
        activeSessionIndex = _openSessions.size - 1
        return session
    }

    fun openDocument(file: File) {
        val canonical =
            try {
                file.canonicalFile
            } catch (_: Throwable) {
                file.absoluteFile
            }
        val existingIndex =
            _openSessions.indexOfFirst { session ->
                session.documentFile?.let { df ->
                    try {
                        df.canonicalFile == canonical
                    } catch (_: Throwable) {
                        df.absoluteFile == canonical
                    }
                } == true
            }

        if (existingIndex >= 0) {
            selectSession(existingIndex)
            return
        }

        try {
            val session = AtlasEditorState(studio = this)
            val success = session.openProject(file)
            if (success) {
                _openSessions.add(session)
                activeSessionIndex = _openSessions.size - 1
            } else {
                errorMessage = session.errorMessage ?: "Failed to open document '${file.name}'"
            }
        } catch (e: Exception) {
            errorMessage = "Failed to open document: ${e.message}"
        }
    }

    fun selectSession(index: Int) {
        if (index in _openSessions.indices) {
            activeSessionIndex = index
        }
    }

    fun requestCloseSession(index: Int) {
        if (index !in _openSessions.indices) return
        val session = _openSessions[index]
        if (session.hasUnsavedChanges) {
            pendingCloseSessionIndex = index
        } else {
            closeSession(index)
        }
    }

    fun closeSession(index: Int) {
        if (index !in _openSessions.indices) return
        _openSessions.removeAt(index)
        if (pendingCloseSessionIndex == index) {
            pendingCloseSessionIndex = null
        }

        if (_openSessions.isEmpty()) {
            newAtlasSession()
        } else {
            if (activeSessionIndex >= _openSessions.size) {
                activeSessionIndex = _openSessions.size - 1
            } else if (activeSessionIndex > index) {
                activeSessionIndex--
            }
        }
    }
}
