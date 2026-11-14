package dev.secondsun.sfxstudio.desktop.atlas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.AtlasExportSpec
import dev.secondsun.sfxstudio.core.atlas.AtlasImportOutcome
import dev.secondsun.sfxstudio.core.atlas.AtlasValidationResult
import dev.secondsun.sfxstudio.core.atlas.PendingTextureImport
import dev.secondsun.sfxstudio.core.atlas.PlacementPolicy
import dev.secondsun.sfxstudio.core.atlas.SFX_PROJ_EXTENSION
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.desktop.shell.AwtStudioPlatform
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

class AtlasEditorState(
    val studio: StudioState? = null,
    private val standalonePlatform: StudioPlatform? = null,
) : EditorSession {
    override val sessionId: String = UUID.randomUUID().toString()
    override val defaultExtension: String = SFX_PROJ_EXTENSION
    override val documentTypeLabel: String = "Atlas Document"

    private val fallbackPlatform by lazy { AwtStudioPlatform() }
    val platform: StudioPlatform
        get() = studio?.platform ?: standalonePlatform ?: fallbackPlatform

    var document by mutableStateOf(AtlasDocument())
        private set

    var atlas: TextureAtlas
        get() = document.atlas
        set(value) {
            if (document.atlas != value) {
                document = document.copy(atlas = value)
                hasUnsavedChanges = true
            }
        }

    var palette: SnesPalette
        get() = document.palette
        set(value) {
            if (document.palette != value) {
                document = document.copy(palette = value)
                hasUnsavedChanges = true
            }
        }

    var gridSnap: Int
        get() = document.gridSnap
        set(value) {
            if (document.gridSnap != value) {
                document = document.withGridSnap(value)
                hasUnsavedChanges = true
            }
        }

    var projectName: String
        get() = document.name
        set(value) {
            if (document.name != value) {
                document = document.copy(name = value)
                hasUnsavedChanges = true
            }
        }

    var selectedTextureIndex by mutableStateOf<Int?>(null)
    var selectedPaletteSlot by mutableStateOf(1)
    var zoom by mutableStateOf(2f)
    var activeTab by mutableStateOf(AtlasEditorTab.LAYOUT)

    var currentProjectFile by mutableStateOf<File?>(null)
    override val documentFile: File? get() = currentProjectFile
    override val displayTitle: String get() = projectName

    override var statusMessage by mutableStateOf("Ready")
    override var hasUnsavedChanges by mutableStateOf(false)

    override val statusSummary: String
        get() =
            "Atlas: ${atlas.width}x${atlas.height} | Textures: ${atlas.textures.size} | " +
                "Palette: ${palette.assignedCount}/15 assigned"

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
    var lastError by mutableStateOf<String?>(null)
        private set

    fun reportError(message: String) {
        lastError = message
        studio?.reportError(message)
    }

    val selectedTexture: SourceTexture?
        get() = selectedTextureIndex?.let { atlas.textures.getOrNull(it) }

    fun currentValidation(): AtlasValidationResult = document.validate(warnBank71Overflow = true)

    fun newProject() {
        document = AtlasDocument()
        selectedTextureIndex = null
        selectedPaletteSlot = 1
        currentProjectFile = null
        hasUnsavedChanges = false
        lastError = null
        statusMessage = "Created new project"
    }

    fun openProject(file: File): Boolean =
        try {
            val loaded = AtlasDocument.load(file)
            document = loaded
            currentProjectFile = file
            selectedTextureIndex = if (loaded.atlas.textures.isNotEmpty()) 0 else null
            hasUnsavedChanges = false
            lastError = null
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

    fun copyTextToClipboard(text: String) {
        platform.copyToClipboard(text)
        statusMessage = "Copied ca65 .i index to clipboard"
    }

    fun promptExportPng() {
        val file =
            platform.chooseSaveFile(
                title = "Export Atlas PNG",
                defaultFileName = "${projectName}_atlas.png",
                initialDir = lastExportDirectory ?: lastImportDirectory,
            ) ?: return
        try {
            TextureComposeRenderer.writeAtlasPng(atlas, palette, file)
            file.parentFile?.absolutePath?.let { updateLastExportDirectory(it) }
            statusMessage = "Exported PNG to '${file.name}'"
        } catch (e: Exception) {
            reportError("Failed to export PNG: ${e.message}")
        }
    }

    fun promptExportPaletteBin() {
        val file =
            platform.chooseSaveFile(
                title = "Export Palette Binary",
                defaultFileName = "$projectName.pal",
                initialDir = lastExportDirectory ?: lastImportDirectory,
            ) ?: return
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(palette.toBgr555Binary())
            file.parentFile?.absolutePath?.let { updateLastExportDirectory(it) }
            statusMessage = "Exported palette binary to '${file.name}'"
        } catch (e: Exception) {
            reportError("Failed to export palette: ${e.message}")
        }
    }

    fun promptExportGraphicsBin() {
        val file =
            platform.chooseSaveFile(
                title = "Export Graphics Binary",
                defaultFileName = "$projectName.bin",
                initialDir = lastExportDirectory ?: lastImportDirectory,
            ) ?: return
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(atlas.generateBinary())
            file.parentFile?.absolutePath?.let { updateLastExportDirectory(it) }
            statusMessage = "Exported graphics binary to '${file.name}'"
        } catch (e: Exception) {
            reportError("Failed to export graphics: ${e.message}")
        }
    }

    fun promptExportIndexFile() {
        val file =
            platform.chooseSaveFile(
                title = "Export ca65 Index File",
                defaultFileName = "$projectName.i",
                initialDir = lastExportDirectory ?: lastImportDirectory,
            ) ?: return
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

    fun promptImportFile() {
        val file =
            platform.chooseOpenFile(
                title = "Import Bitmap",
                initialDir = lastImportDirectory,
                allowedExtensions = setOf("png", "bmp", "gif", "jpg", "jpeg"),
            ) ?: return
        file.parentFile?.absolutePath?.let { updateLastImportDirectory(it) }
        importFile(file)
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
        document = nextDoc
        hasUnsavedChanges = true
        lastError = null
        selectedTextureIndex = nextDoc.atlas.textures.lastIndex
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
        if (quantized != document) {
            document = quantized
            hasUnsavedChanges = true
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
                if (nextDoc != document) {
                    document = nextDoc
                    hasUnsavedChanges = true
                }
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
                document = nextDoc
                hasUnsavedChanges = true
                lastError = null
                selectedTextureIndex =
                    if (nextDoc.atlas.textures.isEmpty()) {
                        null
                    } else {
                        minOf(index, nextDoc.atlas.textures.lastIndex)
                    }
                statusMessage = "Deleted texture '$name'"
                true
            },
            onFailure = { err ->
                reportError(err.message ?: "Cannot delete texture '$name'")
                false
            },
        )
    }

    fun autoFit() {
        val fitted = document.autoFit()
        if (fitted != document) {
            document = fitted
            hasUnsavedChanges = true
        }
        statusMessage = "Auto-fitted atlas dimensions to ${atlas.width}x${atlas.height}"
    }

    fun autoPack() {
        document.autoPack().fold(
            onSuccess = { packedDoc ->
                if (packedDoc != document) {
                    document = packedDoc
                    hasUnsavedChanges = true
                }
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
                    document = document.copy(atlas = packed)
                    hasUnsavedChanges = true
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
            document = document.copy(atlas = atlas.copy(width = newW, height = newH))
            hasUnsavedChanges = true
        }
        statusMessage = "Resized atlas to ${newW}x$newH"
        return true
    }

    fun updatePaletteSlot(
        slot: Int,
        color: SnesColor,
    ) {
        if (slot in 1..15) {
            val next = document.withPaletteColor(slot, color, assigned = true)
            if (next != document) {
                document = next
                hasUnsavedChanges = true
            }
        }
    }

    fun requestUnassignSlot(slot: Int) {
        if (slot !in 1..15) return
        val inUseBy = atlas.textures.filter { it.usedPaletteIndices().contains(slot) }
        if (inUseBy.isNotEmpty()) {
            unassignPromptSlot = slot
        } else {
            val next = document.unassignPaletteSlot(slot)
            if (next != document) {
                document = next
                hasUnsavedChanges = true
            }
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
        document =
            document.copy(
                atlas = atlas.copy(textures = updatedTextures),
                palette = palette.unassignSlot(slot),
            )
        hasUnsavedChanges = true
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
        if (afterPixels != document) {
            document = afterPixels
            hasUnsavedChanges = true
        }
        editingTextureIndex = null
        lastError = null
        statusMessage = "Updated texture '${updatedName.trim()}'"
        return true
    }

    fun applyTextureEdit(
        index: Int,
        updated: SourceTexture,
    ): Boolean = applyTextureEdit(index, updated.name, updated.indexedPixels)
}
