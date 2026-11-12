package dev.secondsun.sfxstudio.desktop.atlas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.secondsun.sfxstudio.core.atlas.AtlasIndexGenerator
import dev.secondsun.sfxstudio.core.atlas.ImportResult
import dev.secondsun.sfxstudio.core.atlas.MedianCutQuantizer
import dev.secondsun.sfxstudio.core.atlas.ProjectService
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.atlas.TextureImporter
import dev.secondsun.sfxstudio.desktop.shell.EditorSession
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import dev.secondsun.sfxstudio.desktop.ui.TextureComposeRenderer
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID

enum class AtlasEditorTab {
    LAYOUT,
    HEX_PREVIEW,
    INDEX_PREVIEW,
}

typealias PackerTab = AtlasEditorTab

class AtlasEditorState(
    val studio: StudioState? = null,
) : EditorSession {
    override val sessionId: String = UUID.randomUUID().toString()

    private var _atlas = mutableStateOf(TextureAtlas(128, 128))
    var atlas: TextureAtlas
        get() = _atlas.value
        set(value) {
            if (_atlas.value != value) {
                _atlas.value = value
                hasUnsavedChanges = true
            }
        }

    private var _palette = mutableStateOf(SnesPalette())
    var palette: SnesPalette
        get() = _palette.value
        set(value) {
            if (_palette.value != value) {
                _palette.value = value
                hasUnsavedChanges = true
            }
        }

    var selectedTextureIndex by mutableStateOf<Int?>(null)
    var selectedPaletteSlot by mutableStateOf(1)

    private var _gridSnap = mutableStateOf(16)
    var gridSnap: Int
        get() = _gridSnap.value
        set(value) {
            if (_gridSnap.value != value) {
                _gridSnap.value = value
                hasUnsavedChanges = true
            }
        }

    var zoom by mutableStateOf(2f)

    private var _uiScaleMultiplier = mutableStateOf(1.0f)
    var uiScaleMultiplier: Float
        get() = studio?.uiScaleMultiplier ?: _uiScaleMultiplier.value
        set(value) {
            if (studio != null) {
                studio.setUiScale(value)
            } else {
                _uiScaleMultiplier.value = value
            }
        }

    var activeTab by mutableStateOf(AtlasEditorTab.LAYOUT)

    var currentProjectFile by mutableStateOf<File?>(null)
    override val documentFile: File? get() = currentProjectFile

    private var _projectName = mutableStateOf("TEXTURES")
    var projectName: String
        get() = _projectName.value
        set(value) {
            if (_projectName.value != value) {
                _projectName.value = value
                hasUnsavedChanges = true
            }
        }

    override val displayTitle: String get() = projectName

    override var statusMessage by mutableStateOf("Ready")
    override var hasUnsavedChanges by mutableStateOf(false)

    override val statusSummary: String
        get() =
            "Atlas: ${atlas.width}x${atlas.height} | Textures: ${atlas.textures.size} | " +
                "Palette: ${palette.assignedCount}/15 assigned"

    // Directory persistence for import / export pickers
    private var _lastImportDirectory = mutableStateOf<String?>(null)
    private var _lastExportDirectory = mutableStateOf<String?>(null)

    var lastImportDirectory: String?
        get() = studio?.lastImportDirectory ?: _lastImportDirectory.value
        set(value) {
            if (studio != null) {
                if (value != null) studio.updateLastImportDirectory(value)
            } else {
                _lastImportDirectory.value = value
            }
        }

    var lastExportDirectory: String?
        get() = studio?.lastExportDirectory ?: _lastExportDirectory.value
        set(value) {
            if (studio != null) {
                if (value != null) studio.updateLastExportDirectory(value)
            } else {
                _lastExportDirectory.value = value
            }
        }

    fun updateLastExportDirectory(dir: String) {
        if (studio != null) {
            studio.updateLastExportDirectory(dir)
        } else {
            lastExportDirectory = dir
        }
    }

    fun updateLastImportDirectory(dir: String) {
        if (studio != null) {
            studio.updateLastImportDirectory(dir)
        } else {
            lastImportDirectory = dir
        }
    }

    // Modals
    private var _showAboutModal = mutableStateOf(false)
    var showAboutModal: Boolean
        get() = studio?.showAboutModal ?: _showAboutModal.value
        set(value) {
            if (studio != null) studio.showAboutModal = value else _showAboutModal.value = value
        }

    var showExportModal by mutableStateOf(false)

    private var _showExitConfirmationModal = mutableStateOf(false)
    var showExitConfirmationModal: Boolean
        get() = studio?.showExitConfirmationModal ?: _showExitConfirmationModal.value
        set(value) {
            if (studio != null) studio.showExitConfirmationModal = value else _showExitConfirmationModal.value = value
        }

    var editingTextureIndex by mutableStateOf<Int?>(null)
    var pendingImport by mutableStateOf<ImportResult.NeedsRemapping?>(null)
    var unassignPromptSlot by mutableStateOf<Int?>(null)
    var errorMessage by mutableStateOf<String?>(null)

    val selectedTexture: SourceTexture?
        get() = selectedTextureIndex?.let { if (it in atlas.textures.indices) atlas.textures[it] else null }

    fun zoomInUi() {
        if (studio != null) {
            studio.zoomInUi()
        } else {
            uiScaleMultiplier = (Math.round((uiScaleMultiplier + 0.1f) * 10f) / 10f).coerceAtMost(3.0f)
        }
    }

    fun zoomOutUi() {
        if (studio != null) {
            studio.zoomOutUi()
        } else {
            uiScaleMultiplier = (Math.round((uiScaleMultiplier - 0.1f) * 10f) / 10f).coerceAtLeast(0.5f)
        }
    }

    fun resetUiScale() {
        if (studio != null) {
            studio.resetUiScale()
        } else {
            uiScaleMultiplier = 1.0f
        }
    }

    fun setUiScale(scale: Float) {
        if (studio != null) {
            studio.setUiScale(scale)
        } else {
            uiScaleMultiplier = scale.coerceIn(0.5f, 3.0f)
        }
    }

    fun newProject() {
        _atlas.value = TextureAtlas(128, 128)
        _palette.value = SnesPalette()
        selectedTextureIndex = null
        selectedPaletteSlot = 1
        currentProjectFile = null
        _projectName.value = "TEXTURES"
        hasUnsavedChanges = false
        statusMessage = "Created new project"
    }

    fun openProject(file: File): Boolean =
        try {
            val (loadedAtlas, loadedPalette, loadedGrid) = ProjectService.loadProject(file)
            _atlas.value = loadedAtlas
            _palette.value = loadedPalette
            _gridSnap.value = loadedGrid
            currentProjectFile = file
            _projectName.value = file.nameWithoutExtension
            selectedTextureIndex = if (loadedAtlas.textures.isNotEmpty()) 0 else null
            hasUnsavedChanges = false
            statusMessage = "Loaded project '${file.name}'"
            true
        } catch (e: Exception) {
            errorMessage = "Failed to open project: ${e.message}"
            false
        }

    fun saveProject(file: File? = currentProjectFile): Boolean {
        if (file == null) return false
        return try {
            ProjectService.saveProject(file, projectName, atlas, palette, gridSnap)
            currentProjectFile = file
            _projectName.value = file.nameWithoutExtension
            hasUnsavedChanges = false
            statusMessage = "Saved project '${file.name}' and companion assets (.bin, .pal, .i, .png)"
            true
        } catch (e: Exception) {
            errorMessage = "Failed to save project: ${e.message}"
            false
        }
    }

    override fun save(): Boolean = saveProject()

    override fun saveAs(file: File): Boolean = saveProject(file)

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
            ProjectService.exportAssets(
                destinationDir = destinationDir,
                baseName = baseName,
                atlas = atlas,
                palette = palette,
                graphicsBinFileName = graphicsBinFileName,
                paletteBinFileName = paletteBinFileName,
                tableBinFileName = tableBinFileName,
                indexFileName = indexFileName,
                pngFileName = pngFileName,
                includeGraphicsBin = includeGraphicsBin,
                includePaletteBin = includePaletteBin,
                includeTableBin = includeTableBin,
                includeIndexFile = includeIndexFile,
                includePng = includePng,
            )
            updateLastExportDirectory(destinationDir.absolutePath)
            statusMessage = "Exported assets to '${destinationDir.name}/'"
            true
        } catch (e: Exception) {
            errorMessage = "Failed to export assets: ${e.message}"
            false
        }

    fun promptExportPng(parent: Frame? = null) {
        val dialog = FileDialog(parent, "Export Atlas PNG", FileDialog.SAVE)
        dialog.file = "${projectName}_atlas.png"
        (lastExportDirectory ?: lastImportDirectory)?.let { dir ->
            if (File(dir).isDirectory) dialog.directory = dir
        }
        dialog.isVisible = true
        val file = dialog.file?.let { File(dialog.directory, it) }
        if (file != null) {
            try {
                TextureComposeRenderer.writeAtlasPng(atlas, palette, file)
                updateLastExportDirectory(file.parentFile.absolutePath)
                statusMessage = "Exported PNG to '${file.name}'"
            } catch (e: Exception) {
                errorMessage = "Failed to export PNG: ${e.message}"
            }
        }
    }

    fun promptExportPaletteBin(parent: Frame? = null) {
        val dialog = FileDialog(parent, "Export Palette Binary", FileDialog.SAVE)
        dialog.file = "$projectName.pal"
        (lastExportDirectory ?: lastImportDirectory)?.let { dir ->
            if (File(dir).isDirectory) dialog.directory = dir
        }
        dialog.isVisible = true
        val file = dialog.file?.let { File(dialog.directory, it) }
        if (file != null) {
            try {
                file.parentFile?.mkdirs()
                file.writeBytes(palette.toBgr555Binary())
                updateLastExportDirectory(file.parentFile.absolutePath)
                statusMessage = "Exported palette binary to '${file.name}'"
            } catch (e: Exception) {
                errorMessage = "Failed to export palette: ${e.message}"
            }
        }
    }

    fun promptExportGraphicsBin(parent: Frame? = null) {
        val dialog = FileDialog(parent, "Export Graphics Binary", FileDialog.SAVE)
        dialog.file = "$projectName.bin"
        (lastExportDirectory ?: lastImportDirectory)?.let { dir ->
            if (File(dir).isDirectory) dialog.directory = dir
        }
        dialog.isVisible = true
        val file = dialog.file?.let { File(dialog.directory, it) }
        if (file != null) {
            try {
                file.parentFile?.mkdirs()
                file.writeBytes(atlas.generateBinary())
                updateLastExportDirectory(file.parentFile.absolutePath)
                statusMessage = "Exported graphics binary to '${file.name}'"
            } catch (e: Exception) {
                errorMessage = "Failed to export graphics: ${e.message}"
            }
        }
    }

    fun promptExportIndexFile(parent: Frame? = null) {
        val dialog = FileDialog(parent, "Export ca65 Index File", FileDialog.SAVE)
        dialog.file = "$projectName.i"
        (lastExportDirectory ?: lastImportDirectory)?.let { dir ->
            if (File(dir).isDirectory) dialog.directory = dir
        }
        dialog.isVisible = true
        val file = dialog.file?.let { File(dialog.directory, it) }
        if (file != null) {
            try {
                file.parentFile?.mkdirs()
                file.writeText(AtlasIndexGenerator.generate(atlas, projectName))
                updateLastExportDirectory(file.parentFile.absolutePath)
                statusMessage = "Exported ca65 index file to '${file.name}'"
            } catch (e: Exception) {
                errorMessage = "Failed to export .i file: ${e.message}"
            }
        }
    }

    fun promptImportFile(parent: Frame? = null) {
        val dialog = FileDialog(parent, "Import Bitmap", FileDialog.LOAD)
        lastImportDirectory?.let { dir ->
            if (File(dir).isDirectory) {
                dialog.directory = dir
            }
        }
        dialog.isVisible = true
        val dir = dialog.directory
        val fileName = dialog.file
        if (dir != null) {
            updateLastImportDirectory(dir)
        }
        if (fileName != null && dir != null) {
            val file = File(dir, fileName)
            importFile(file)
        }
    }

    fun importFile(file: File) {
        when (val result = TextureImporter.importFromFile(file, palette)) {
            is ImportResult.Success -> {
                palette = result.updatedPalette
                placeImportedTexture(result.texture)
            }
            is ImportResult.NeedsRemapping -> {
                pendingImport = result
            }
            is ImportResult.Error -> {
                errorMessage = result.message
            }
        }
    }

    private fun placeImportedTexture(texture: SourceTexture) {
        // Try finding placement in current atlas
        var placement = atlas.findPlacement(texture, gridSnap)
        var workingAtlas = atlas

        if (placement == null) {
            // Try expanding atlas up to 256x256
            val sizes = listOf(16, 32, 64, 128, 256)
            var expanded = false
            for (newW in sizes.filter { it >= workingAtlas.width }) {
                for (newH in sizes.filter { it >= workingAtlas.height }) {
                    if (newW == workingAtlas.width && newH == workingAtlas.height) continue
                    val candidateAtlas = workingAtlas.copy(width = newW, height = newH)
                    val p = candidateAtlas.findPlacement(texture, gridSnap)
                    if (p != null) {
                        workingAtlas = candidateAtlas
                        placement = p
                        expanded = true
                        break
                    }
                }
                if (expanded) break
            }
        }

        if (placement != null) {
            val placedTex = texture.copy(x = placement.first, y = placement.second)
            atlas = workingAtlas.withTexture(placedTex)
            selectedTextureIndex = atlas.textures.size - 1
            statusMessage = "Imported '${placedTex.name}' at (${placedTex.x}, ${placedTex.y})"
        } else {
            errorMessage =
                "Could not find space for '${texture.name}' (${texture.width}x${texture.height}) in atlas up to 256x256."
        }
    }

    fun moveTexture(
        index: Int,
        targetX: Int,
        targetY: Int,
    ): Boolean {
        require(index in atlas.textures.indices)
        val tex = atlas.textures[index]
        // Grid snap
        val snappedX = (targetX / gridSnap) * gridSnap
        val snappedY = (targetY / gridSnap) * gridSnap

        if (atlas.canPlace(tex, snappedX, snappedY, excludeTextureIndex = index)) {
            val moved = tex.copy(x = snappedX, y = snappedY)
            atlas = atlas.withUpdatedTexture(index, moved)
            return true
        }
        return false
    }

    fun deleteTexture(index: Int) {
        if (index in atlas.textures.indices) {
            val name = atlas.textures[index].name
            atlas = atlas.withoutTexture(index)
            selectedTextureIndex = if (atlas.textures.isEmpty()) null else minOf(index, atlas.textures.size - 1)
            statusMessage = "Deleted texture '$name'"
        }
    }

    fun autoFit() {
        val (fitW, fitH) = atlas.autoFitDimensions()
        atlas = atlas.copy(width = fitW, height = fitH)
        statusMessage = "Auto-fitted atlas dimensions to ${fitW}x$fitH"
    }

    fun autoPack() {
        val packed = atlas.autoPack(gridSnap)
        if (packed != null) {
            atlas = packed
            statusMessage = "Auto-packed textures with ${gridSnap}px grid"
        } else {
            errorMessage = "Could not pack all textures into ${atlas.width}x${atlas.height} atlas"
        }
    }

    fun setAtlasDimensions(
        newW: Int,
        newH: Int,
    ): Boolean {
        // If shrinking, check if placed textures fit
        for (tex in atlas.textures) {
            if (tex.x + tex.width > newW || tex.y + tex.height > newH) {
                // Try repacking into new dimensions
                val candidateAtlas = atlas.copy(width = newW, height = newH)
                val packed = candidateAtlas.autoPack(gridSnap)
                if (packed != null) {
                    atlas = packed
                    statusMessage = "Resized atlas to ${newW}x$newH and auto-repacked"
                    return true
                } else {
                    errorMessage =
                        "Cannot shrink atlas to ${newW}x$newH: placed textures would be out of bounds or overlap."
                    return false
                }
            }
        }
        atlas = atlas.copy(width = newW, height = newH)
        statusMessage = "Resized atlas to ${newW}x$newH"
        return true
    }

    fun updatePaletteSlot(
        slot: Int,
        color: SnesColor,
    ) {
        if (slot in 1..15) {
            palette = palette.withSlot(slot, color, assigned = true)
        }
    }

    fun requestUnassignSlot(slot: Int) {
        if (slot !in 1..15) return
        val inUseBy = atlas.textures.filter { it.usedPaletteIndices().contains(slot) }
        if (inUseBy.isNotEmpty()) {
            unassignPromptSlot = slot
        } else {
            palette = palette.unassignSlot(slot)
            statusMessage = "Unassigned palette slot $slot"
        }
    }

    fun confirmUnassignSlot(
        slot: Int,
        remapToSlot: Int,
    ) {
        // Remap all textures using slot
        val updatedTextures =
            atlas.textures.map { tex ->
                if (tex.usedPaletteIndices().contains(slot)) {
                    tex.remapPixels(mapOf(slot to remapToSlot))
                } else {
                    tex
                }
            }
        atlas = atlas.copy(textures = updatedTextures)
        palette = palette.unassignSlot(slot)
        unassignPromptSlot = null
        statusMessage = "Unassigned slot $slot (remapped texels to slot $remapToSlot)"
    }

    fun applyTextureEdit(
        index: Int,
        updated: SourceTexture,
    ) {
        if (index in atlas.textures.indices) {
            atlas = atlas.withUpdatedTexture(index, updated)
            editingTextureIndex = null
            statusMessage = "Updated texture '${updated.name}'"
        }
    }

    fun autoSnapImport(pending: ImportResult.NeedsRemapping) {
        val mapping = mutableMapOf<SnesColor, Int>()
        for (color in pending.uniqueColors) {
            val existing = pending.currentPalette.findExactIndex(color)
            if (existing != null) {
                mapping[color] = existing
            } else {
                mapping[color] = pending.currentPalette.findNearestAssignedIndex(color)
            }
        }
        val tex =
            TextureImporter.createTextureFromImage(
                image = pending.rawImage,
                name = pending.suggestedName,
                colorMapping = mapping,
                fallbackPalette = pending.currentPalette,
            )
        placeImportedTexture(tex)
        pendingImport = null
        statusMessage = "Auto-snapped '${tex.name}' to existing palette"
    }

    fun globalQuantizeImport(pending: ImportResult.NeedsRemapping) {
        // Collect all colors from all existing textures + incoming image
        val allColors = mutableListOf<SnesColor>()
        for (tex in atlas.textures) {
            for (v in 0 until tex.height) {
                for (u in 0 until tex.width) {
                    val slot = tex.getPixel(u, v)
                    if (slot != 0) {
                        allColors.add(palette[slot])
                    }
                }
            }
        }
        for (color in pending.uniqueColors) {
            allColors.add(color)
        }

        val newPalette = MedianCutQuantizer.quantize(allColors)

        // Remap existing textures to new palette
        val remappedTextures =
            atlas.textures.map { tex ->
                val remapMap = mutableMapOf<Int, Int>()
                for (slot in 1..15) {
                    val oldColor = palette[slot]
                    remapMap[slot] = newPalette.findNearestAssignedIndex(oldColor)
                }
                tex.remapPixels(remapMap)
            }

        // Map incoming texture
        val incomingMapping = mutableMapOf<SnesColor, Int>()
        for (color in pending.uniqueColors) {
            incomingMapping[color] = newPalette.findNearestAssignedIndex(color)
        }
        val newTex =
            TextureImporter.createTextureFromImage(
                image = pending.rawImage,
                name = pending.suggestedName,
                colorMapping = incomingMapping,
                fallbackPalette = newPalette,
            )

        palette = newPalette
        atlas = atlas.copy(textures = remappedTextures)
        placeImportedTexture(newTex)
        pendingImport = null
        statusMessage = "Global 15-color quantization applied across all textures"
    }
}

typealias AtlasEditorSession = AtlasEditorState
typealias PackerState = AtlasEditorState
