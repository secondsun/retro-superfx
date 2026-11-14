package dev.secondsun.sfxstudio.desktop.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.SFX_ATLAS_EXTENSION
import dev.secondsun.sfxstudio.core.atlas.SFX_PROJ_EXTENSION
import dev.secondsun.sfxstudio.core.level.LevelDocument
import dev.secondsun.sfxstudio.core.level.MapCell
import dev.secondsun.sfxstudio.core.level.MapGrid
import dev.secondsun.sfxstudio.core.level.SFX_LEVEL_EXTENSION
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.level.LevelEditorState
import java.io.File

class StudioState(
    initialFiles: List<File> = emptyList(),
    val platform: StudioPlatform = AwtStudioPlatform(),
) {
    fun getPreference(
        key: String,
        default: String? = null,
    ): String? = platform.getPreference(key, default)

    fun setPreference(
        key: String,
        value: String,
    ) {
        platform.setPreference(key, value)
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

    // Modals & unified command/I/O error sink
    var showAboutModal by mutableStateOf(false)
    var showNewLevelModal by mutableStateOf(false)
    var showExitConfirmationModal by mutableStateOf(false)
    var pendingCloseSessionIndex by mutableStateOf<Int?>(null)
    var errorMessage by mutableStateOf<String?>(null)

    fun reportError(message: String) {
        errorMessage = message
    }

    // Timestamp-keyed disk fallback cache for closed AtlasDocuments
    private val diskAtlasCache = LinkedHashMap<Pair<String, Long>, AtlasDocument>()

    fun invalidateDiskAtlasCache() {
        diskAtlasCache.clear()
    }

    /**
     * Resolves an [AtlasDocument] by checking open [AtlasEditorState] tabs **first** (preserving
     * unsaved in-memory textures, slices, aliases, and palette edits), falling back to a
     * timestamp-keyed disk cache when the atlas is closed, or to the first open atlas tab when
     * [atlasFile] is `null`.
     */
    fun resolveLiveOrDiskAtlas(atlasFile: File?): AtlasDocument? {
        if (atlasFile != null) {
            val targetCanonical = canonicalOrAbsolute(atlasFile)
            val openMatch =
                _openSessions
                    .filterIsInstance<AtlasEditorState>()
                    .firstOrNull { session ->
                        session.documentFile?.let { canonicalOrAbsolute(it) == targetCanonical } == true
                    }
            if (openMatch != null) {
                return openMatch.document
            }

            if (atlasFile.exists() && atlasFile.isFile) {
                val key = targetCanonical.path to atlasFile.lastModified()
                diskAtlasCache[key]?.let { return it }
                return try {
                    val loaded = AtlasDocument.load(atlasFile)
                    diskAtlasCache[key] = loaded
                    loaded
                } catch (_: Exception) {
                    null
                }
            }
            return null
        }

        return _openSessions
            .filterIsInstance<AtlasEditorState>()
            .firstOrNull()
            ?.document
    }

    init {
        val validFiles =
            initialFiles.filter {
                it.isFile &&
                    (
                        it.extension.equals(SFX_PROJ_EXTENSION, ignoreCase = true) ||
                            it.extension.equals(SFX_ATLAS_EXTENSION, ignoreCase = true) ||
                            it.extension.equals(SFX_LEVEL_EXTENSION, ignoreCase = true)
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

    fun newLevelSession(
        name: String = "untitled_level",
        atlasFile: File? = null,
        cols: Int = 8,
        rows: Int = 8,
    ): LevelEditorState {
        val defaultTex =
            resolveLiveOrDiskAtlas(atlasFile)
                ?.descriptorTable()
                ?.getOrNull()
                ?.entries
                ?.firstOrNull()
                ?.name

        val initialDoc =
            LevelDocument(
                name = name,
                atlasPath = atlasFile?.path ?: "",
                grid =
                    MapGrid.filled(
                        cols = cols.coerceIn(MapGrid.MIN_DIM, MapGrid.MAX_DIM),
                        rows = rows.coerceIn(MapGrid.MIN_DIM, MapGrid.MAX_DIM),
                        defaultCell = MapCell(baseHeight = 0, topTex = defaultTex, skirtTex = defaultTex),
                    ),
            )
        val session =
            LevelEditorState(
                studio = this,
                initialDocument = initialDoc,
                initialAtlasFile = atlasFile,
            )
        _openSessions.add(session)
        activeSessionIndex = _openSessions.size - 1
        return session
    }

    fun promptOpenDocument() {
        val picked =
            platform.chooseOpenFile(
                title = "Open SuperFX Document",
                initialDir = lastImportDirectory ?: lastExportDirectory,
                allowedExtensions = setOf(SFX_PROJ_EXTENSION, SFX_ATLAS_EXTENSION, SFX_LEVEL_EXTENSION),
            )
        if (picked != null) {
            picked.parentFile?.absolutePath?.let { updateLastImportDirectory(it) }
            openDocument(picked)
        }
    }

    fun openDocument(file: File) {
        val canonical = canonicalOrAbsolute(file)
        val existingIndex =
            _openSessions.indexOfFirst { session ->
                session.documentFile?.let { canonicalOrAbsolute(it) == canonical } == true
            }

        if (existingIndex >= 0) {
            selectSession(existingIndex)
            return
        }

        try {
            if (file.extension.equals(SFX_LEVEL_EXTENSION, ignoreCase = true)) {
                val session = LevelEditorState(studio = this)
                val success = session.openLevel(file)
                if (success) {
                    _openSessions.add(session)
                    activeSessionIndex = _openSessions.size - 1
                } else if (errorMessage == null) {
                    reportError(session.lastError ?: "Failed to open level '${file.name}'")
                }
            } else {
                val session = AtlasEditorState(studio = this)
                val success = session.openProject(file)
                if (success) {
                    _openSessions.add(session)
                    activeSessionIndex = _openSessions.size - 1
                } else if (errorMessage == null) {
                    reportError(session.lastError ?: "Failed to open document '${file.name}'")
                }
            }
        } catch (e: Exception) {
            reportError("Failed to open document: ${e.message}")
        }
    }

    /**
     * Unified save entrypoint for any [EditorSession] (used by File -> Save, toolbar Save,
     * tab-close Save, and Save All on exit).
     */
    fun saveSession(session: EditorSession): Boolean {
        if (session.documentFile != null) {
            return session.save()
        }
        return saveSessionAs(session)
    }

    /**
     * Prompts for a destination file via [StudioPlatform.chooseSaveFile] using the session's
     * own [EditorSession.defaultExtension] (`.sfxproj` vs `.sfxlevel`), ensuring untitled
     * levels are never saved with `.sfxproj`.
     */
    fun saveSessionAs(session: EditorSession): Boolean {
        val ext = session.defaultExtension
        val baseTitle =
            if (session.displayTitle.endsWith(".$ext", ignoreCase = true)) {
                session.displayTitle
            } else {
                "${session.displayTitle}.$ext"
            }
        val initialDir =
            session.documentFile?.parentFile?.absolutePath
                ?: lastExportDirectory
                ?: lastImportDirectory
        val chosen =
            platform.chooseSaveFile(
                title = "Save ${session.documentTypeLabel}",
                defaultFileName = baseTitle,
                initialDir = initialDir,
            ) ?: return false

        val normalizedFile =
            if (chosen.extension.isEmpty()) {
                File(chosen.parentFile, "${chosen.name}.$ext")
            } else {
                chosen
            }
        normalizedFile.parentFile?.absolutePath?.let { updateLastExportDirectory(it) }
        return session.saveAs(normalizedFile)
    }

    fun saveActiveSession(): Boolean = activeSession?.let { saveSession(it) } ?: false

    fun saveActiveSessionAs(): Boolean = activeSession?.let { saveSessionAs(it) } ?: false

    fun saveAllDirtySessions(): Boolean {
        for (session in _openSessions.filter { it.hasUnsavedChanges }) {
            if (!saveSession(session)) {
                return false
            }
        }
        return true
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

    companion object {
        internal fun canonicalOrAbsolute(file: File): File =
            try {
                file.canonicalFile
            } catch (_: Throwable) {
                file.absoluteFile
            }
    }
}
