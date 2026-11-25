package dev.secondsun.sfxstudio.desktop.atlas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.AtlasExportSpec
import dev.secondsun.sfxstudio.core.atlas.AtlasImportOutcome
import dev.secondsun.sfxstudio.core.atlas.AtlasValidationResult
import dev.secondsun.sfxstudio.core.atlas.DescriptorAlias
import dev.secondsun.sfxstudio.core.atlas.PendingTextureImport
import dev.secondsun.sfxstudio.core.atlas.PlacementPolicy
import dev.secondsun.sfxstudio.core.atlas.SFX_PROJ_EXTENSION
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.atlas.VirtualSlice
import dev.secondsun.sfxstudio.desktop.awt.createDefaultStudioPlatform
import dev.secondsun.sfxstudio.desktop.shell.EditorSession
import dev.secondsun.sfxstudio.desktop.shell.StudioPlatform
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import dev.secondsun.sfxstudio.desktop.ui.TextureComposeRenderer
import java.io.File
import java.util.UUID

enum class AtlasEditorTab {
    LAYOUT,
    HEX_PREVIEW,
    INDEX_PREVIEW,
}

enum class AtlasCatalogTab {
    TEXTURES,
    DESCRIPTORS,
    PALETTE,
}

enum class AtlasCanvasTool {
    MOVE,
    PAN,
}

enum class CascadeTargetKind {
    TEXTURE,
    SLICE,
    ALIAS,
}

data class PendingCascadeDelete(
    val kind: CascadeTargetKind,
    val index: Int,
    val targetName: String,
    val dependentSlices: List<String>,
    val dependentAliases: List<String>,
)

class AtlasEditorState(
    val studio: StudioState? = null,
    private val standalonePlatform: StudioPlatform? = null,
) : EditorSession {
    override val sessionId: String = UUID.randomUUID().toString()
    override val defaultExtension: String = SFX_PROJ_EXTENSION
    override val documentTypeLabel: String = "Atlas Document"

    private val fallbackPlatform by lazy { createDefaultStudioPlatform() }
    val platform: StudioPlatform
        get() = studio?.platform ?: standalonePlatform ?: fallbackPlatform

    var document by mutableStateOf(AtlasDocument())
        private set

    // Undo / Redo history
    private val undoStack = ArrayDeque<AtlasDocument>()
    private val redoStack = ArrayDeque<AtlasDocument>()
    private var batchSnapshot: AtlasDocument? = null
    private var savedDocument: AtlasDocument = document
    private var historyRevision by mutableStateOf(0)

    override val canUndo: Boolean
        get() {
            historyRevision
            return undoStack.isNotEmpty()
        }

    override val canRedo: Boolean
        get() {
            historyRevision
            return redoStack.isNotEmpty()
        }

    private fun pushUndo(snapshot: AtlasDocument) {
        if (undoStack.lastOrNull() == snapshot) return
        if (undoStack.size >= MAX_HISTORY) {
            undoStack.removeFirst()
        }
        undoStack.addLast(snapshot)
        redoStack.clear()
        historyRevision++
    }

    private fun commitDocumentChange(
        next: AtlasDocument,
        recordUndo: Boolean = batchSnapshot == null,
    ): Boolean {
        val before = document
        if (next == before) return false
        if (recordUndo) {
            pushUndo(before)
        }
        document = next
        hasUnsavedChanges = (document != savedDocument)
        return true
    }

    fun beginEditBatch() {
        if (batchSnapshot == null) {
            batchSnapshot = document
        }
    }

    fun endEditBatch() {
        val start = batchSnapshot
        batchSnapshot = null
        if (start != null && start != document) {
            pushUndo(start)
        }
    }

    private fun clampSelections() {
        val idx = selectedTextureIndex
        if (idx != null) {
            selectedTextureIndex =
                if (atlas.textures.isEmpty()) {
                    null
                } else {
                    idx.coerceIn(0, atlas.textures.lastIndex)
                }
        }
        val sliceName = selectedSliceName
        if (sliceName != null && atlas.slices.none { it.name.equals(sliceName, ignoreCase = true) }) {
            selectedSliceName = null
        }
    }

    override fun undo() {
        endEditBatch()
        val prev = undoStack.removeLastOrNull() ?: return
        if (redoStack.size >= MAX_HISTORY) {
            redoStack.removeFirst()
        }
        redoStack.addLast(document)
        document = prev
        clampSelections()
        hasUnsavedChanges = (document != savedDocument)
        historyRevision++
        statusMessage = "Undo"
    }

    override fun redo() {
        endEditBatch()
        val next = redoStack.removeLastOrNull() ?: return
        if (undoStack.size >= MAX_HISTORY) {
            undoStack.removeFirst()
        }
        undoStack.addLast(document)
        document = next
        clampSelections()
        hasUnsavedChanges = (document != savedDocument)
        historyRevision++
        statusMessage = "Redo"
    }

    var atlas: TextureAtlas
        get() = document.atlas
        set(value) {
            commitDocumentChange(document.copy(atlas = value))
            clampSelections()
        }

    var palette: SnesPalette
        get() = document.palette
        set(value) {
            commitDocumentChange(document.copy(palette = value))
        }

    var gridSnap: Int
        get() = document.gridSnap
        set(value) {
            commitDocumentChange(document.withGridSnap(value))
        }

    var projectName: String
        get() = document.name
        set(value) {
            commitDocumentChange(document.copy(name = value))
        }

    var selectedTextureIndex by mutableStateOf<Int?>(null)
    var selectedSliceName by mutableStateOf<String?>(null)
    var selectedDescriptorName by mutableStateOf<String?>(null)
    var selectedPaletteSlot by mutableStateOf(1)
    var zoom by mutableStateOf(2f)
    var activeTab by mutableStateOf(AtlasEditorTab.LAYOUT)

    // Adaptive dock & canvas interaction state
    var catalogTab by mutableStateOf(AtlasCatalogTab.TEXTURES)
    var canvasTool by mutableStateOf(AtlasCanvasTool.MOVE)
    var canvasPanOffset by mutableStateOf(Offset.Zero)
    var activeDiagnosticIndex by mutableStateOf(0)

    var currentProjectFile by mutableStateOf<File?>(null)
    override val documentFile: File? get() = currentProjectFile
    override val displayTitle: String get() = projectName

    override var statusMessage by mutableStateOf("Ready")
    override var hasUnsavedChanges by mutableStateOf(false)

    override val statusSummary: String
        get() {
            val kb = atlas.sizeBytes / 1024
            val sizeLabel = if (kb > 0) "$kb KB" else "${atlas.sizeBytes} B"
            return "Atlas: ${atlas.width}x${atlas.height} ($sizeLabel) | " +
                "${atlas.textures.size} Tex | Palette: ${palette.assignedCount}/15"
        }

    // Standalone directory state when running without StudioState
    private var localLastImportDir by mutableStateOf<String?>(null)
    private var localLastExportDir by mutableStateOf<String?>(null)

    var lastImportDirectory: String?
        get() = studio?.lastImportDirectory ?: localLastImportDir
        set(value) {
            if (studio != null) {
                if (value != null) studio.updateLastImportDirectory(value)
            } else {
                localLastImportDir = value
            }
        }

    var lastExportDirectory: String?
        get() = studio?.lastExportDirectory ?: localLastExportDir
        set(value) {
            if (studio != null) {
                if (value != null) studio.updateLastExportDirectory(value)
            } else {
                localLastExportDir = value
            }
        }

    fun updateLastExportDirectory(dir: String) {
        lastExportDirectory = dir
    }

    fun updateLastImportDirectory(dir: String) {
        lastImportDirectory = dir
    }

    // Session modals & unified error reporting
    var showExportModal by mutableStateOf(false)
    var editingTextureIndex by mutableStateOf<Int?>(null)
    var pendingImport by mutableStateOf<PendingTextureImport?>(null)
    var unassignPromptSlot by mutableStateOf<Int?>(null)

    // Slice, Alias & Cascade Delete modal state
    var showSliceModal by mutableStateOf(false)
    var editingSliceIndex by mutableStateOf<Int?>(null)
    var sliceParentTextureHint by mutableStateOf<String?>(null)

    var showAliasModal by mutableStateOf(false)
    var editingAliasIndex by mutableStateOf<Int?>(null)

    var pendingCascadeDelete by mutableStateOf<PendingCascadeDelete?>(null)

    var lastError by mutableStateOf<String?>(null)
        private set

    fun reportError(message: String) {
        lastError = message
        studio?.reportError(message)
    }

    val selectedTexture: SourceTexture?
        get() = selectedTextureIndex?.let { atlas.textures.getOrNull(it) }

    fun currentValidation(): AtlasValidationResult = document.validate(warnBank71Overflow = true)

    fun openNewSliceModal(parentTextureName: String? = selectedTexture?.name ?: atlas.textures.firstOrNull()?.name) {
        if (atlas.textures.isEmpty()) {
            reportError("Import at least one Source Texture before creating a Virtual Slice.")
            return
        }
        editingSliceIndex = null
        sliceParentTextureHint = parentTextureName
        showSliceModal = true
    }

    fun openEditSliceModal(index: Int) {
        if (index !in atlas.slices.indices) return
        editingSliceIndex = index
        sliceParentTextureHint = atlas.slices[index].sourceTexture
        showSliceModal = true
    }

    fun closeSliceModal() {
        showSliceModal = false
        editingSliceIndex = null
        sliceParentTextureHint = null
    }

    fun openNewAliasModal() {
        val total = atlas.textures.size + atlas.slices.size + atlas.aliases.size
        if (total == 0) {
            reportError("Add at least one Source Texture before creating a Descriptor Alias.")
            return
        }
        editingAliasIndex = null
        showAliasModal = true
    }

    fun openEditAliasModal(index: Int) {
        if (index !in atlas.aliases.indices) return
        editingAliasIndex = index
        showAliasModal = true
    }

    fun closeAliasModal() {
        showAliasModal = false
        editingAliasIndex = null
    }

    fun newProject() {
        val fresh = AtlasDocument()
        undoStack.clear()
        redoStack.clear()
        batchSnapshot = null
        document = fresh
        savedDocument = fresh
        selectedTextureIndex = null
        selectedSliceName = null
        selectedDescriptorName = null
        selectedPaletteSlot = 1
        canvasPanOffset = Offset.Zero
        activeDiagnosticIndex = 0
        closeSliceModal()
        closeAliasModal()
        pendingCascadeDelete = null
        currentProjectFile = null
        hasUnsavedChanges = false
        lastError = null
        historyRevision++
        statusMessage = "Created new project"
    }

    fun openProject(file: File): Boolean =
        try {
            val loaded = AtlasDocument.load(file)
            undoStack.clear()
            redoStack.clear()
            batchSnapshot = null
            document = loaded
            savedDocument = loaded
            currentProjectFile = file
            selectedTextureIndex = if (loaded.atlas.textures.isNotEmpty()) 0 else null
            selectedSliceName = null
            selectedDescriptorName = null
            canvasPanOffset = Offset.Zero
            activeDiagnosticIndex = 0
            closeSliceModal()
            closeAliasModal()
            pendingCascadeDelete = null
            hasUnsavedChanges = false
            lastError = null
            historyRevision++
            statusMessage = "Loaded project '${file.name}'"
            true
        } catch (e: Exception) {
            reportError("Failed to open project: ${e.message}")
            false
        }

    fun saveProject(file: File? = currentProjectFile): Boolean {
        if (file == null) return false
        return try {
            val targetDoc =
                if (currentProjectFile == null && document.name == "TEXTURES") {
                    document.copy(name = file.nameWithoutExtension)
                } else {
                    document
                }
            targetDoc.saveAndExport(documentFile = file)
            document = targetDoc
            savedDocument = targetDoc
            currentProjectFile = file
            hasUnsavedChanges = false
            lastError = null
            studio?.invalidateDiskAtlasCache()
            val warnings = targetDoc.validate(warnBank71Overflow = true).warnings
            val warnSuffix = if (warnings.isNotEmpty()) " (${warnings.size} warning(s))" else ""
            statusMessage = "Saved '${file.name}' and companion assets (.bin, .pal, .tab, .i, .png)$warnSuffix"
            true
        } catch (e: Exception) {
            reportError("Failed to save project: ${e.message}")
            false
        }
    }

    override fun save(): Boolean = saveProject()

    override fun saveAs(file: File): Boolean = saveProject(file)

    fun requestSave(): Boolean = studio?.saveSession(this) ?: save()

    fun exportAllAssets(
        destinationDir: File,
        baseName: String,
        graphicsBinFileName: String = "$baseName.bin",
        paletteBinFileName: String = "$baseName.pal",
        tableBinFileName: String = "$baseName.tab",
        indexFileName: String = "$baseName.i",
        pngFileName: String = "${baseName}_atlas.png",
        includeGraphicsBin: Boolean = true,
        includePaletteBin: Boolean = true,
        includeTableBin: Boolean = true,
        includeIndexFile: Boolean = true,
        includePng: Boolean = true,
    ): Boolean =
        try {
            val spec =
                AtlasExportSpec(
                    baseName = baseName,
                    graphicsBinFileName = graphicsBinFileName,
                    paletteBinFileName = paletteBinFileName,
                    indexFileName = indexFileName,
                    pngFileName = pngFileName,
                    tableBinFileName = tableBinFileName,
                    includeGraphicsBin = includeGraphicsBin,
                    includePaletteBin = includePaletteBin,
                    includeIndexFile = includeIndexFile,
                    includePng = includePng,
                    includeTableBin = includeTableBin,
                )
            document.exportOnly(outputDir = destinationDir, spec = spec)
            updateLastExportDirectory(destinationDir.absolutePath)
            lastError = null
            statusMessage = "Exported assets to '${destinationDir.name}/'"
            true
        } catch (e: Exception) {
            reportError("Failed to export assets: ${e.message}")
            false
        }

    fun chooseExportDirectory(initialDir: String?): File? = platform.chooseDirectory("Select Export Directory", initialDir)

    fun requestExportDirectory(
        initialDir: String?,
        onResult: (File?) -> Unit,
    ) {
        platform.requestDirectory("Select Export Directory", initialDir, onResult)
    }

    fun copyTextToClipboard(text: String) {
        platform.copyToClipboard(text)
        statusMessage = "Copied ca65 .i index to clipboard"
    }

    fun promptExportPng() {
        platform.requestSaveFile(
            title = "Export Atlas PNG",
            defaultFileName = "${projectName}_atlas.png",
            initialDir = lastExportDirectory ?: lastImportDirectory,
        ) { file ->
            if (file == null) return@requestSaveFile
            try {
                TextureComposeRenderer.writeAtlasPng(atlas, palette, file)
                file.parentFile?.absolutePath?.let { updateLastExportDirectory(it) }
                statusMessage = "Exported PNG to '${file.name}'"
            } catch (e: Exception) {
                reportError("Failed to export PNG: ${e.message}")
            }
        }
    }

    fun promptExportPaletteBin() {
        platform.requestSaveFile(
            title = "Export Palette Binary",
            defaultFileName = "$projectName.pal",
            initialDir = lastExportDirectory ?: lastImportDirectory,
        ) { file ->
            if (file == null) return@requestSaveFile
            try {
                file.parentFile?.mkdirs()
                file.writeBytes(palette.toBgr555Binary())
                file.parentFile?.absolutePath?.let { updateLastExportDirectory(it) }
                statusMessage = "Exported palette binary to '${file.name}'"
            } catch (e: Exception) {
                reportError("Failed to export palette: ${e.message}")
            }
        }
    }

    fun promptExportGraphicsBin() {
        platform.requestSaveFile(
            title = "Export Graphics Binary",
            defaultFileName = "$projectName.bin",
            initialDir = lastExportDirectory ?: lastImportDirectory,
        ) { file ->
            if (file == null) return@requestSaveFile
            try {
                file.parentFile?.mkdirs()
                file.writeBytes(atlas.generateBinary())
                file.parentFile?.absolutePath?.let { updateLastExportDirectory(it) }
                statusMessage = "Exported graphics binary to '${file.name}'"
            } catch (e: Exception) {
                reportError("Failed to export graphics: ${e.message}")
            }
        }
    }

    fun promptExportIndexFile() {
        platform.requestSaveFile(
            title = "Export ca65 Index File",
            defaultFileName = "$projectName.i",
            initialDir = lastExportDirectory ?: lastImportDirectory,
        ) { file ->
            if (file == null) return@requestSaveFile
            document.renderIndexPreview(projectName).fold(
                onSuccess = { text ->
                    try {
                        file.parentFile?.mkdirs()
                        file.writeText(text)
                        file.parentFile?.absolutePath?.let { updateLastExportDirectory(it) }
                        statusMessage = "Exported ca65 index file to '${file.name}'"
                    } catch (e: Exception) {
                        reportError("Failed to export .i file: ${e.message}")
                    }
                },
                onFailure = { err ->
                    reportError("Failed to generate .i file: ${err.message}")
                },
            )
        }
    }

    fun promptImportFile() {
        platform.requestOpenFile(
            title = "Import Bitmap",
            initialDir = lastImportDirectory,
            allowedExtensions = setOf("png", "bmp", "gif", "jpg", "jpeg"),
        ) { file ->
            if (file == null) return@requestOpenFile
            file.parentFile?.absolutePath?.let { updateLastImportDirectory(it) }
            importFile(file)
        }
    }

    fun importFile(file: File) {
        when (val outcome = document.beginImport(file, PlacementPolicy.AUTO_GROW_MAX_256)) {
            is AtlasImportOutcome.Imported -> {
                applyImportedDocument(
                    nextDoc = outcome.document,
                    placed = outcome.placedTexture,
                    actionVerb = "Imported",
                )
            }
            is AtlasImportOutcome.NeedsRemapping -> {
                pendingImport = outcome.pending
            }
            is AtlasImportOutcome.Invalid -> {
                reportError(outcome.message)
            }
        }
    }

    fun commitManualImport(mapping: Map<SnesColor, Int>): Boolean {
        val pending = pendingImport ?: return false
        return pending.commitManual(document, mapping, PlacementPolicy.AUTO_GROW_MAX_256).fold(
            onSuccess = { nextDoc ->
                pendingImport = null
                applyImportedDocument(nextDoc, nextDoc.atlas.textures.last(), "Mapped and imported")
                true
            },
            onFailure = { err ->
                pendingImport = null
                reportError(err.message ?: "Failed to place '${pending.suggestedName}' in atlas")
                false
            },
        )
    }

    fun commitSnapImport(): Boolean {
        val pending = pendingImport ?: return false
        return pending.commitSnap(document, PlacementPolicy.AUTO_GROW_MAX_256).fold(
            onSuccess = { nextDoc ->
                pendingImport = null
                applyImportedDocument(nextDoc, nextDoc.atlas.textures.last(), "Auto-snapped")
                true
            },
            onFailure = { err ->
                pendingImport = null
                reportError(err.message ?: "Failed to place '${pending.suggestedName}' in atlas")
                false
            },
        )
    }

    fun commitGlobalQuantizeImport(): Boolean {
        val pending = pendingImport ?: return false
        return pending.commitGlobalQuantize(document, PlacementPolicy.AUTO_GROW_MAX_256).fold(
            onSuccess = { nextDoc ->
                pendingImport = null
                applyImportedDocument(nextDoc, nextDoc.atlas.textures.last(), "Quantized and imported")
                true
            },
            onFailure = { err ->
                pendingImport = null
                reportError(err.message ?: "Failed to place '${pending.suggestedName}' in atlas")
                false
            },
        )
    }

    private fun applyImportedDocument(
        nextDoc: AtlasDocument,
        placed: SourceTexture,
        actionVerb: String,
    ) {
        commitDocumentChange(nextDoc)
        lastError = null
        selectedTextureIndex = nextDoc.atlas.textures.lastIndex
        selectedSliceName = null
        val bank71Warn =
            nextDoc
                .validate(warnBank71Overflow = true)
                .warnings
                .firstOrNull { it.code == "ATLAS_EXCEEDS_BANK_71" }
        statusMessage =
            if (bank71Warn != null) {
                "$actionVerb '${placed.name}' at (${placed.x}, ${placed.y}) — Warning: atlas exceeds 32 KB Bank \$71 budget"
            } else {
                "$actionVerb '${placed.name}' at (${placed.x}, ${placed.y})"
            }
    }

    /**
     * Standalone 15-color Median-Cut quantization across all existing textures in the atlas.
     */
    fun quantizePalette() {
        val quantized = document.quantizePalette()
        if (commitDocumentChange(quantized)) {
            lastError = null
            statusMessage = "Quantized palette to 15 colors across ${atlas.textures.size} texture(s)"
        }
    }

    fun moveTexture(
        index: Int,
        targetX: Int,
        targetY: Int,
    ): Boolean {
        require(index in atlas.textures.indices)
        val snappedX = (targetX / gridSnap) * gridSnap
        val snappedY = (targetY / gridSnap) * gridSnap
        return document.moveTexture(index, snappedX, snappedY).fold(
            onSuccess = { nextDoc ->
                commitDocumentChange(nextDoc)
                true
            },
            onFailure = { false },
        )
    }

    fun deleteTexture(
        index: Int,
        cascadeDependents: Boolean = false,
    ): Boolean {
        if (index !in atlas.textures.indices) return false
        val name = atlas.textures[index].name
        return document.removeTexture(index, cascadeDependents = cascadeDependents).fold(
            onSuccess = { nextDoc ->
                commitDocumentChange(nextDoc)
                lastError = null
                selectedTextureIndex =
                    if (nextDoc.atlas.textures.isEmpty()) {
                        null
                    } else {
                        minOf(index, nextDoc.atlas.textures.lastIndex)
                    }
                clampSelections()
                statusMessage = "Deleted texture '$name'"
                true
            },
            onFailure = { err ->
                reportError(err.message ?: "Cannot delete texture '$name'")
                false
            },
        )
    }

    fun addSlice(slice: VirtualSlice): Boolean =
        document.addSlice(slice).fold(
            onSuccess = { nextDoc ->
                commitDocumentChange(nextDoc)
                lastError = null
                selectedSliceName = slice.name
                selectedDescriptorName = slice.name
                closeSliceModal()
                statusMessage = "Added virtual slice '${slice.name}'"
                true
            },
            onFailure = { err ->
                reportError(err.message ?: "Failed to add virtual slice '${slice.name}'")
                false
            },
        )

    fun updateSlice(
        index: Int,
        slice: VirtualSlice,
    ): Boolean {
        if (index !in atlas.slices.indices) return false
        val oldName = atlas.slices[index].name
        return document.updateSlice(index, slice).fold(
            onSuccess = { nextDoc ->
                commitDocumentChange(nextDoc)
                lastError = null
                val cleanName = slice.name.trim()
                if (selectedSliceName.equals(oldName, ignoreCase = true) || selectedSliceName == null) {
                    selectedSliceName = cleanName
                }
                selectedDescriptorName = cleanName
                closeSliceModal()
                statusMessage = "Updated virtual slice '$cleanName'"
                true
            },
            onFailure = { err ->
                reportError(err.message ?: "Failed to update virtual slice '${slice.name}'")
                false
            },
        )
    }

    fun deleteSlice(
        index: Int,
        cascadeDependents: Boolean = false,
    ): Boolean {
        if (index !in atlas.slices.indices) return false
        val name = atlas.slices[index].name
        return document.removeSlice(index, cascadeDependents = cascadeDependents).fold(
            onSuccess = { nextDoc ->
                commitDocumentChange(nextDoc)
                lastError = null
                if (selectedSliceName.equals(name, ignoreCase = true)) {
                    selectedSliceName = null
                }
                statusMessage = "Deleted virtual slice '$name'"
                true
            },
            onFailure = { err ->
                reportError(err.message ?: "Cannot delete slice '$name'")
                false
            },
        )
    }

    fun addAlias(alias: DescriptorAlias): Boolean =
        document.addAlias(alias).fold(
            onSuccess = { nextDoc ->
                commitDocumentChange(nextDoc)
                lastError = null
                selectedDescriptorName = alias.name
                closeAliasModal()
                statusMessage = "Added descriptor alias '${alias.name}' -> '${alias.target}'"
                true
            },
            onFailure = { err ->
                reportError(err.message ?: "Failed to add alias '${alias.name}'")
                false
            },
        )

    fun updateAlias(
        index: Int,
        alias: DescriptorAlias,
    ): Boolean {
        if (index !in atlas.aliases.indices) return false
        return document.updateAlias(index, alias).fold(
            onSuccess = { nextDoc ->
                commitDocumentChange(nextDoc)
                lastError = null
                selectedDescriptorName = alias.name.trim()
                closeAliasModal()
                statusMessage = "Updated descriptor alias '${alias.name.trim()}'"
                true
            },
            onFailure = { err ->
                reportError(err.message ?: "Failed to update alias '${alias.name}'")
                false
            },
        )
    }

    fun deleteAlias(
        index: Int,
        cascadeDependents: Boolean = false,
    ): Boolean {
        if (index !in atlas.aliases.indices) return false
        val name = atlas.aliases[index].name
        return document.removeAlias(index, cascadeDependents = cascadeDependents).fold(
            onSuccess = { nextDoc ->
                commitDocumentChange(nextDoc)
                lastError = null
                statusMessage = "Deleted descriptor alias '$name'"
                true
            },
            onFailure = { err ->
                reportError(err.message ?: "Cannot delete alias '$name'")
                false
            },
        )
    }

    fun pinDescriptorOrder(): Boolean =
        document.pinCurrentDescriptorOrder().fold(
            onSuccess = { nextDoc ->
                commitDocumentChange(nextDoc)
                lastError = null
                statusMessage = "Pinned descriptor order (${nextDoc.atlas.descriptorOrder?.size ?: 0} entries)"
                true
            },
            onFailure = { err ->
                reportError(err.message ?: "Failed to pin descriptor order")
                false
            },
        )

    fun clearDescriptorOrder(): Boolean =
        document.withDescriptorOrder(null).fold(
            onSuccess = { nextDoc ->
                commitDocumentChange(nextDoc)
                lastError = null
                statusMessage = "Reset descriptor order to automatic"
                true
            },
            onFailure = { err ->
                reportError(err.message ?: "Failed to reset descriptor order")
                false
            },
        )

    fun moveDescriptorInOrder(
        fromIndex: Int,
        toIndex: Int,
    ): Boolean =
        document.moveDescriptorInOrder(fromIndex, toIndex).fold(
            onSuccess = { nextDoc ->
                commitDocumentChange(nextDoc)
                lastError = null
                statusMessage = String.format("Moved descriptor to slot $%02X", toIndex)
                true
            },
            onFailure = { err ->
                reportError(err.message ?: "Failed to reorder descriptor")
                false
            },
        )

    private fun collectTransitiveDependentAliases(rootNames: Collection<String>): List<String> {
        val removedUpper = LinkedHashSet<String>()
        for (name in rootNames) {
            removedUpper.add(name.uppercase())
        }
        val dependentAliases = mutableListOf<String>()
        var changed = true
        while (changed) {
            changed = false
            for (alias in atlas.aliases) {
                val aliasUpper = alias.name.uppercase()
                if (aliasUpper !in removedUpper && alias.target.uppercase() in removedUpper) {
                    removedUpper.add(aliasUpper)
                    dependentAliases.add(alias.name)
                    changed = true
                }
            }
        }
        return dependentAliases
    }

    fun requestDeleteTexture(index: Int) {
        if (index !in atlas.textures.indices) return
        val tex = atlas.textures[index]
        val depSlices = atlas.slices.filter { it.sourceTexture.equals(tex.name, ignoreCase = true) }.map { it.name }
        val depAliases = collectTransitiveDependentAliases(listOf(tex.name) + depSlices)
        if (depSlices.isEmpty() && depAliases.isEmpty()) {
            deleteTexture(index, cascadeDependents = false)
        } else {
            pendingCascadeDelete =
                PendingCascadeDelete(
                    kind = CascadeTargetKind.TEXTURE,
                    index = index,
                    targetName = tex.name,
                    dependentSlices = depSlices,
                    dependentAliases = depAliases,
                )
        }
    }

    fun requestDeleteSlice(index: Int) {
        if (index !in atlas.slices.indices) return
        val slice = atlas.slices[index]
        val depAliases = collectTransitiveDependentAliases(listOf(slice.name))
        if (depAliases.isEmpty()) {
            deleteSlice(index, cascadeDependents = false)
        } else {
            pendingCascadeDelete =
                PendingCascadeDelete(
                    kind = CascadeTargetKind.SLICE,
                    index = index,
                    targetName = slice.name,
                    dependentSlices = emptyList(),
                    dependentAliases = depAliases,
                )
        }
    }

    fun requestDeleteAlias(index: Int) {
        if (index !in atlas.aliases.indices) return
        val alias = atlas.aliases[index]
        val depAliases = collectTransitiveDependentAliases(listOf(alias.name))
        if (depAliases.isEmpty()) {
            deleteAlias(index, cascadeDependents = false)
        } else {
            pendingCascadeDelete =
                PendingCascadeDelete(
                    kind = CascadeTargetKind.ALIAS,
                    index = index,
                    targetName = alias.name,
                    dependentSlices = emptyList(),
                    dependentAliases = depAliases,
                )
        }
    }

    fun confirmCascadeDelete(): Boolean {
        val pending = pendingCascadeDelete ?: return false
        pendingCascadeDelete = null
        return when (pending.kind) {
            CascadeTargetKind.TEXTURE -> deleteTexture(pending.index, cascadeDependents = true)
            CascadeTargetKind.SLICE -> deleteSlice(pending.index, cascadeDependents = true)
            CascadeTargetKind.ALIAS -> deleteAlias(pending.index, cascadeDependents = true)
        }
    }

    fun cancelCascadeDelete() {
        pendingCascadeDelete = null
    }

    fun autoFit() {
        commitDocumentChange(document.autoFit())
        statusMessage = "Auto-fitted atlas dimensions to ${atlas.width}x${atlas.height}"
    }

    fun autoPack() {
        document.autoPack().fold(
            onSuccess = { packedDoc ->
                commitDocumentChange(packedDoc)
                statusMessage = "Auto-packed textures with ${gridSnap}px grid"
            },
            onFailure = {
                reportError("Could not pack all textures into ${atlas.width}x${atlas.height} atlas")
            },
        )
    }

    fun setAtlasDimensions(
        newW: Int,
        newH: Int,
    ): Boolean {
        for (tex in atlas.textures) {
            if (tex.x + tex.width > newW || tex.y + tex.height > newH) {
                val candidateAtlas = atlas.copy(width = newW, height = newH)
                val packed = candidateAtlas.autoPack(gridSnap)
                return if (packed != null) {
                    commitDocumentChange(document.copy(atlas = packed))
                    statusMessage = "Resized atlas to ${newW}x$newH and auto-repacked"
                    true
                } else {
                    reportError(
                        "Cannot shrink atlas to ${newW}x$newH: placed textures would be out of bounds or overlap.",
                    )
                    false
                }
            }
        }
        if (atlas.width != newW || atlas.height != newH) {
            commitDocumentChange(document.copy(atlas = atlas.copy(width = newW, height = newH)))
        }
        statusMessage = "Resized atlas to ${newW}x$newH"
        return true
    }

    fun updatePaletteSlot(
        slot: Int,
        color: SnesColor,
    ) {
        if (slot in 1..15) {
            commitDocumentChange(document.withPaletteColor(slot, color, assigned = true))
        }
    }

    fun requestUnassignSlot(slot: Int) {
        if (slot !in 1..15) return
        val inUseBy = atlas.textures.filter { it.usedPaletteIndices().contains(slot) }
        if (inUseBy.isNotEmpty()) {
            unassignPromptSlot = slot
        } else {
            commitDocumentChange(document.unassignPaletteSlot(slot))
            statusMessage = "Unassigned palette slot $slot"
        }
    }

    fun confirmUnassignSlot(
        slot: Int,
        remapToSlot: Int,
    ) {
        val updatedTextures =
            atlas.textures.map { tex ->
                if (tex.usedPaletteIndices().contains(slot)) {
                    tex.remapPixels(mapOf(slot to remapToSlot))
                } else {
                    tex
                }
            }
        commitDocumentChange(
            document.copy(
                atlas = atlas.copy(textures = updatedTextures),
                palette = palette.unassignSlot(slot),
            ),
        )
        unassignPromptSlot = null
        statusMessage = "Unassigned slot $slot (remapped texels to slot $remapToSlot)"
    }

    fun applyTextureEdit(
        index: Int,
        updatedName: String,
        updatedPixels: ByteArray,
    ): Boolean {
        if (index !in atlas.textures.indices) return false
        val currentName = atlas.textures[index].name
        val afterRename =
            if (updatedName != currentName) {
                document.renameTexture(index, updatedName).getOrElse { err ->
                    reportError(err.message ?: "Failed to rename texture")
                    return false
                }
            } else {
                document
            }
        val afterPixels =
            afterRename.updateTexturePixels(index, updatedPixels).getOrElse { err ->
                reportError(err.message ?: "Failed to update texture pixels")
                return false
            }
        commitDocumentChange(afterPixels)
        editingTextureIndex = null
        lastError = null
        statusMessage = "Updated texture '${updatedName.trim()}'"
        return true
    }

    fun applyTextureEdit(
        index: Int,
        updated: SourceTexture,
    ): Boolean = applyTextureEdit(index, updated.name, updated.indexedPixels)

    companion object {
        private const val MAX_HISTORY = 100
    }
}
