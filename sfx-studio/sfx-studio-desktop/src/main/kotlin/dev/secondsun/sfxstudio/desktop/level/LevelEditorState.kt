package dev.secondsun.sfxstudio.desktop.level

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.secondsun.sfxstudio.core.atlas.ProjectService
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.atlas.TextureDescriptorInfo
import dev.secondsun.sfxstudio.core.level.Ca65MapImporter
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.DiagnosticSeverity
import dev.secondsun.sfxstudio.core.level.EdgePanels
import dev.secondsun.sfxstudio.core.level.IsoSoftwareRenderer
import dev.secondsun.sfxstudio.core.level.LevelDocument
import dev.secondsun.sfxstudio.core.level.LevelDocumentService
import dev.secondsun.sfxstudio.core.level.LevelValidationResult
import dev.secondsun.sfxstudio.core.level.LevelValidator
import dev.secondsun.sfxstudio.core.level.MapCell
import dev.secondsun.sfxstudio.core.level.MapGrid
import dev.secondsun.sfxstudio.core.level.SFX_LEVEL_EXTENSION
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.shell.EditorSession
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import java.io.File
import java.util.UUID

enum class LevelTool {
    SELECT,
    BRUSH,
    EYEDROPPER,
}

enum class LevelCenterViewMode {
    SPLIT,
    ISO_3D,
    TOP_DOWN_2D,
}

enum class ActiveTextureSlot {
    TOP,
    SKIRT,
    PANEL,
}

data class BrushLayerMask(
    val applyHeight: Boolean = true,
    val applyArchetypeAndRotation: Boolean = true,
    val applyTopTex: Boolean = true,
    val applySkirtTex: Boolean = true,
    val applyPanelTexAndFlags: Boolean = false,
)

/**
 * Stateful editing session for an isometric [LevelDocument] (`.sfxlevel`) in SuperFX Studio Desktop.
 *
 * Resolves live [TextureAtlas] and [SnesPalette] data from an open [AtlasEditorState] tab when
 * available, falling back to a timestamp-keyed disk cache otherwise.
 */
class LevelEditorState(
    val studio: StudioState? = null,
    initialDocument: LevelDocument =
        LevelDocument(
            name = "untitled_level",
            atlasPath = "",
            grid = MapGrid.filled(8, 8),
        ),
    initialAtlasFile: File? = null,
) : EditorSession {
    override val sessionId: String = UUID.randomUUID().toString()

    private var _document = mutableStateOf(initialDocument)
    var document: LevelDocument
        get() = _document.value
        private set(value) {
            _document.value = value
        }

    var currentLevelFile by mutableStateOf<File?>(null)
    override val documentFile: File? get() = currentLevelFile

    /**
     * Explicit atlas file handle used before an untitled level has been saved to disk.
     */
    var unsavedAtlasFile by mutableStateOf<File?>(initialAtlasFile)

    override val displayTitle: String
        get() = document.name

    override var hasUnsavedChanges by mutableStateOf(false)
    override var statusMessage by mutableStateOf("Ready")
    var errorMessage by mutableStateOf<String?>(null)

    // Tooling & selection state
    var activeTool by mutableStateOf(LevelTool.SELECT)
    var activeTextureSlot by mutableStateOf(ActiveTextureSlot.TOP)
    var brushCell by mutableStateOf(
        initialDocument.grid.cells.firstOrNull() ?: MapCell(),
    )
    var brushMask by mutableStateOf(BrushLayerMask())

    var selectedCells by mutableStateOf<Set<Pair<Int, Int>>>(setOf(0 to 0))
    var hoveredCell by mutableStateOf<Pair<Int, Int>?>(null)

    // Viewport state
    var centerViewMode by mutableStateOf(LevelCenterViewMode.SPLIT)
    var cameraYaw by mutableStateOf(15)
    var cameraPanX by mutableStateOf(0)
    var cameraPanY by mutableStateOf(0)
    private var camXQ6: Int = 0
    private var camZQ6: Int = 0
    var isoZoom by mutableStateOf(2.0f)

    // Undo / Redo history
    private val undoStack = ArrayDeque<LevelDocument>()
    private val redoStack = ArrayDeque<LevelDocument>()
    private var strokeSnapshot: LevelDocument? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // Disk fallback cache for closed atlases
    private var cachedDiskKey: Pair<String, Long>? = null
    private var cachedDiskData: Pair<TextureAtlas, SnesPalette>? = null

    override val statusSummary: String
        get() {
            val g = document.grid
            val valRes = currentValidation()
            return "${g.cols}x${g.rows} (${g.byteSize} B) | Yaw $cameraYaw | " +
                "${valRes.errors.size} err, ${valRes.warnings.size} warn"
        }

    fun resolveAtlasFile(): File? {
        val levelFile = currentLevelFile
        if (levelFile != null && document.atlasPath.isNotBlank()) {
            return LevelDocumentService.resolveAtlasFile(levelFile, document.atlasPath)
        }
        if (unsavedAtlasFile != null) {
            return unsavedAtlasFile
        }
        if (document.atlasPath.isNotBlank()) {
            return File(document.atlasPath).absoluteFile.normalize()
        }
        return null
    }

    /**
     * Resolves the active [TextureAtlas] and [SnesPalette] for rendering and validation.
     * Prefers an open [AtlasEditorState] tab matching the canonical path so pixel/palette edits
     * reflect live across tabs without requiring a disk save.
     */
    fun resolveAtlasAndPalette(): Pair<TextureAtlas, SnesPalette>? {
        val targetFile = resolveAtlasFile()
        if (targetFile != null) {
            val targetCanonical = canonicalOrAbsolute(targetFile)
            val openMatch =
                studio
                    ?.openSessions
                    ?.filterIsInstance<AtlasEditorState>()
                    ?.firstOrNull { session ->
                        session.documentFile?.let { canonicalOrAbsolute(it) == targetCanonical } == true
                    }
            if (openMatch != null) {
                return openMatch.atlas to openMatch.palette
            }

            if (targetFile.exists() && targetFile.isFile) {
                val key = targetCanonical.path to targetFile.lastModified()
                if (cachedDiskKey == key && cachedDiskData != null) {
                    return cachedDiskData
                }
                return try {
                    val (loadedAtlas, loadedPal, _) = ProjectService.loadProject(targetFile)
                    val pair = loadedAtlas to loadedPal
                    cachedDiskKey = key
                    cachedDiskData = pair
                    pair
                } catch (_: Exception) {
                    null
                }
            }
        } else {
            // Fallback for brand-new untitled levels: bind preview to an open unsaved Atlas tab if present
            val firstOpenAtlas =
                studio
                    ?.openSessions
                    ?.filterIsInstance<AtlasEditorState>()
                    ?.firstOrNull()
            if (firstOpenAtlas != null) {
                return firstOpenAtlas.atlas to firstOpenAtlas.palette
            }
        }
        return null
    }

    fun invalidateDiskAtlasCache() {
        cachedDiskKey = null
        cachedDiskData = null
    }

    fun currentValidation(): LevelValidationResult {
        val resolved = resolveAtlasAndPalette()
        val dummyFile = currentLevelFile ?: File(document.name + ".$SFX_LEVEL_EXTENSION")
        return if (resolved != null) {
            LevelValidator.validate(dummyFile, document, overrideAtlas = resolved.first)
        } else {
            LevelValidator.validate(dummyFile, document)
        }
    }

    /**
     * Extracts a preview [SourceTexture] for any descriptor (base texture, virtual slice, or alias)
     * so slice and alias swatches render accurately in the level palette dock.
     */
    fun extractDescriptorPreview(
        atlas: TextureAtlas,
        desc: TextureDescriptorInfo,
    ): SourceTexture? {
        val direct = atlas.textures.find { it.name == desc.name }
        if (direct != null) return direct

        val atlasBytes = atlas.generateBinary()
        val subPixels = ByteArray(desc.width * desc.height)
        for (v in 0 until desc.height) {
            for (u in 0 until desc.width) {
                val idx = desc.romOffset + v * atlas.width + u
                if (idx in atlasBytes.indices) {
                    subPixels[v * desc.width + u] = atlasBytes[idx]
                }
            }
        }
        return SourceTexture(
            name = desc.name,
            width = desc.width,
            height = desc.height,
            x = 0,
            y = 0,
            indexedPixels = subPixels,
        )
    }

    private fun pushUndo(snapshot: LevelDocument) {
        if (undoStack.lastOrNull() == snapshot) return
        if (undoStack.size >= MAX_HISTORY) {
            undoStack.removeFirst()
        }
        undoStack.addLast(snapshot)
        redoStack.clear()
    }

    private fun mutateDocument(
        recordUndo: Boolean = true,
        status: String? = null,
        transform: (LevelDocument) -> LevelDocument,
    ) {
        val before = document
        val after = transform(before)
        if (after == before) return
        if (recordUndo) {
            pushUndo(before)
        }
        document = after
        hasUnsavedChanges = true
        if (status != null) {
            statusMessage = status
        }
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        if (redoStack.size >= MAX_HISTORY) {
            redoStack.removeFirst()
        }
        redoStack.addLast(document)
        document = prev
        clampSelectionToBounds()
        hasUnsavedChanges = true
        statusMessage = "Undo"
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        if (undoStack.size >= MAX_HISTORY) {
            undoStack.removeFirst()
        }
        undoStack.addLast(document)
        document = next
        clampSelectionToBounds()
        hasUnsavedChanges = true
        statusMessage = "Redo"
    }

    fun beginStroke() {
        if (strokeSnapshot == null) {
            strokeSnapshot = document
        }
    }

    fun endStroke() {
        val start = strokeSnapshot
        strokeSnapshot = null
        if (start != null && start != document) {
            pushUndo(start)
        }
    }

    fun selectSingleCell(
        col: Int,
        row: Int,
        additive: Boolean = false,
    ) {
        if (!document.grid.isInBounds(col, row)) return
        val coord = col to row
        selectedCells =
            if (additive) {
                if (coord in selectedCells && selectedCells.size > 1) {
                    selectedCells - coord
                } else {
                    selectedCells + coord
                }
            } else {
                setOf(coord)
            }
        statusMessage = "Selected cell ($col, $row)"
    }

    fun selectRect(
        colA: Int,
        rowA: Int,
        colB: Int,
        rowB: Int,
    ) {
        val minC = minOf(colA, colB).coerceIn(0, document.grid.cols - 1)
        val maxC = maxOf(colA, colB).coerceIn(0, document.grid.cols - 1)
        val minR = minOf(rowA, rowB).coerceIn(0, document.grid.rows - 1)
        val maxR = maxOf(rowA, rowB).coerceIn(0, document.grid.rows - 1)
        val next = mutableSetOf<Pair<Int, Int>>()
        for (r in minR..maxR) {
            for (c in minC..maxC) {
                next.add(c to r)
            }
        }
        selectedCells = next
        statusMessage = "Selected ${next.size} cell(s)"
    }

    fun handleCellInteraction(
        col: Int,
        row: Int,
        isDragContinue: Boolean = false,
        shiftHeld: Boolean = false,
    ) {
        if (!document.grid.isInBounds(col, row)) return
        when (activeTool) {
            LevelTool.SELECT -> {
                if (!isDragContinue) {
                    selectSingleCell(col, row, additive = shiftHeld)
                }
            }
            LevelTool.BRUSH -> {
                paintCell(col, row, recordImmediateUndo = strokeSnapshot == null)
            }
            LevelTool.EYEDROPPER -> {
                pickCellIntoBrush(col, row, switchBackToBrush = !shiftHeld)
            }
        }
    }

    fun pickCellIntoBrush(
        col: Int,
        row: Int,
        switchBackToBrush: Boolean = true,
    ) {
        if (!document.grid.isInBounds(col, row)) return
        brushCell = document.grid.cellAt(col, row)
        if (switchBackToBrush) {
            activeTool = LevelTool.BRUSH
        }
        statusMessage = "Sampled cell ($col, $row) into brush"
    }

    fun paintCell(
        col: Int,
        row: Int,
        recordImmediateUndo: Boolean = true,
    ) {
        if (!document.grid.isInBounds(col, row)) return
        mutateDocument(recordUndo = recordImmediateUndo, status = "Painted cell ($col, $row)") { doc ->
            val prev = doc.grid.cellAt(col, row)
            val merged = mergeBrushOntoCell(prev)
            doc.copy(grid = doc.grid.withCell(col, row, merged))
        }
    }

    fun applyBrushToSelection() {
        if (selectedCells.isEmpty()) return
        mutateDocument(status = "Applied brush to ${selectedCells.size} cell(s)") { doc ->
            val updates =
                selectedCells
                    .filter { (c, r) -> doc.grid.isInBounds(c, r) }
                    .associateWith { (c, r) -> mergeBrushOntoCell(doc.grid.cellAt(c, r)) }
            doc.copy(grid = doc.grid.withCells(updates))
        }
    }

    private fun mergeBrushOntoCell(prev: MapCell): MapCell =
        MapCell(
            baseHeight = if (brushMask.applyHeight) brushCell.baseHeight else prev.baseHeight,
            archetype = if (brushMask.applyArchetypeAndRotation) brushCell.archetype else prev.archetype,
            rotation = if (brushMask.applyArchetypeAndRotation) brushCell.rotation else prev.rotation,
            topTex = if (brushMask.applyTopTex) brushCell.topTex else prev.topTex,
            skirtTex = if (brushMask.applySkirtTex) brushCell.skirtTex else prev.skirtTex,
            panelTex = if (brushMask.applyPanelTexAndFlags) brushCell.panelTex else prev.panelTex,
            panels = if (brushMask.applyPanelTexAndFlags) brushCell.panels else prev.panels,
        )

    fun updateSelectedCells(
        description: String,
        transform: (MapCell) -> MapCell,
    ) {
        if (selectedCells.isEmpty()) return
        mutateDocument(status = description) { doc ->
            val updates =
                selectedCells
                    .filter { (c, r) -> doc.grid.isInBounds(c, r) }
                    .associateWith { (c, r) -> transform(doc.grid.cellAt(c, r)) }
            doc.copy(grid = doc.grid.withCells(updates))
        }
    }

    fun stepSelectedHeight(delta: Int) {
        updateSelectedCells("Adjusted height by ${if (delta >= 0) "+$delta" else "$delta"}") { cell ->
            cell.copy(baseHeight = (cell.baseHeight + delta).coerceIn(0, LevelValidator.MAX_BASE_HEIGHT))
        }
    }

    fun rotateSelectedClockwise() {
        updateSelectedCells("Rotated selected cell(s) 90° CW") { cell ->
            cell.rotatedClockwise()
        }
    }

    fun setSelectedArchetype(archetype: ShapeArchetype) {
        brushCell =
            brushCell.copy(
                archetype = archetype,
                rotation = if (archetype.isRotationallySymmetric) CellRotation.ROT_0 else brushCell.rotation,
            )
        if (activeTool == LevelTool.SELECT && selectedCells.isNotEmpty()) {
            updateSelectedCells("Set archetype ${archetype.displayName}") { cell ->
                cell.copy(
                    archetype = archetype,
                    rotation = if (archetype.isRotationallySymmetric) CellRotation.ROT_0 else cell.rotation,
                )
            }
        }
    }

    fun setSelectedRotation(rotation: CellRotation) {
        brushCell = brushCell.copy(rotation = rotation)
        if (activeTool == LevelTool.SELECT && selectedCells.isNotEmpty()) {
            updateSelectedCells("Set rotation ${rotation.degrees}°") { cell ->
                cell.copy(rotation = if (cell.archetype.isRotationallySymmetric) CellRotation.ROT_0 else rotation)
            }
        }
    }

    fun assignTextureToActiveSlot(texName: String?) {
        brushCell =
            when (activeTextureSlot) {
                ActiveTextureSlot.TOP -> brushCell.copy(topTex = texName)
                ActiveTextureSlot.SKIRT -> brushCell.copy(skirtTex = texName)
                ActiveTextureSlot.PANEL -> brushCell.copy(panelTex = texName)
            }
        if (activeTool == LevelTool.SELECT && selectedCells.isNotEmpty()) {
            updateSelectedCells("Assigned ${activeTextureSlot.name.lowercase()} texture '${texName ?: "none"}'") { cell ->
                when (activeTextureSlot) {
                    ActiveTextureSlot.TOP -> cell.copy(topTex = texName)
                    ActiveTextureSlot.SKIRT -> cell.copy(skirtTex = texName)
                    ActiveTextureSlot.PANEL -> cell.copy(panelTex = texName)
                }
            }
        }
    }

    fun updateSelectedPanels(transform: (EdgePanels) -> EdgePanels) {
        brushCell = brushCell.copy(panels = transform(brushCell.panels))
        if (activeTool == LevelTool.SELECT && selectedCells.isNotEmpty()) {
            updateSelectedCells("Updated edge panels") { cell ->
                val nextPanels = transform(cell.panels)
                cell.copy(
                    panels =
                        nextPanels.copy(
                            tall2h = nextPanels.hasAny && nextPanels.tall2h,
                        ),
                )
            }
        }
    }

    fun resizeGrid(
        newCols: Int,
        newRows: Int,
    ) {
        val c = newCols.coerceIn(MapGrid.MIN_DIM, MapGrid.MAX_DIM)
        val r = newRows.coerceIn(MapGrid.MIN_DIM, MapGrid.MAX_DIM)
        if (c == document.grid.cols && r == document.grid.rows) return
        val fill =
            MapCell(
                baseHeight = 0,
                topTex =
                    brushCell.topTex ?: document.grid.cells
                        .firstOrNull()
                        ?.topTex,
                skirtTex =
                    brushCell.skirtTex ?: document.grid.cells
                        .firstOrNull()
                        ?.skirtTex,
            )
        mutateDocument(status = "Resized grid to ${c}x$r") { doc ->
            doc.copy(grid = doc.grid.resized(c, r, fill))
        }
        clampSelectionToBounds()
    }

    fun linkAtlasFile(atlasFile: File) {
        if (!atlasFile.exists()) {
            errorMessage = "Atlas file '${atlasFile.path}' does not exist."
            return
        }
        unsavedAtlasFile = atlasFile
        invalidateDiskAtlasCache()
        val relPath =
            currentLevelFile?.let { computeRelativePath(it, atlasFile) }
                ?: atlasFile.path
        mutateDocument(status = "Linked atlas '${atlasFile.name}'") { doc ->
            val resolved = resolveAtlasAndPalette()?.first
            val firstTex = resolved?.resolvedDescriptors()?.firstOrNull()?.name
            val patchedCells =
                if (firstTex != null) {
                    doc.grid.cells.map { cell ->
                        cell.copy(
                            topTex = cell.topTex ?: firstTex,
                            skirtTex = cell.skirtTex ?: firstTex,
                        )
                    }
                } else {
                    doc.grid.cells
                }
            doc.copy(
                atlasPath = relPath,
                grid = doc.grid.copy(cells = patchedCells),
            )
        }
    }

    fun rotateCameraClockwise(steps: Int = 1) {
        cameraYaw = (((cameraYaw + steps) % 120) + 120) % 120
        syncCamToScreen()
    }

    fun rotateCameraCounterClockwise(steps: Int = 1) {
        cameraYaw = (((cameraYaw - steps) % 120) + 120) % 120
        syncCamToScreen()
    }

    fun rotateCamera90CounterClockwise() {
        rotateCameraCounterClockwise(30)
    }

    fun rotateCamera90Clockwise() {
        rotateCameraClockwise(30)
    }

    /**
     * Pans the camera up (moving the model down on screen, +Y screen offset).
     */
    fun panCameraUp(pixels: Int = PAN_STEP_PIXELS) {
        panCameraBy(0, pixels)
    }

    /**
     * Pans the camera down (moving the model up on screen, -Y screen offset).
     */
    fun panCameraDown(pixels: Int = PAN_STEP_PIXELS) {
        panCameraBy(0, -pixels)
    }

    /**
     * Pans the camera down the negative X axis (moving the model right on screen, +X screen offset).
     */
    fun panCameraNegativeX(pixels: Int = PAN_STEP_PIXELS) {
        panCameraBy(pixels, 0)
    }

    /**
     * Pans the camera in the positive X direction (moving the model left on screen, -X screen offset).
     */
    fun panCameraPositiveX(pixels: Int = PAN_STEP_PIXELS) {
        panCameraBy(-pixels, 0)
    }

    fun panCameraBy(
        deltaPanX: Int,
        deltaPanY: Int,
    ) {
        cameraPanX = (cameraPanX + deltaPanX).coerceIn(-MAX_PAN_PIXELS, MAX_PAN_PIXELS)
        cameraPanY = (cameraPanY + deltaPanY).coerceIn(-MAX_PAN_PIXELS, MAX_PAN_PIXELS)
        syncScreenToCam()
    }

    fun resetCamera() {
        cameraYaw = 15
        cameraPanX = 0
        cameraPanY = 0
        camXQ6 = 0
        camZQ6 = 0
    }

    private fun fmult(
        a: Int,
        b: Int,
    ): Int = ((a.toLong() * b.toLong()) shr 16).toInt()

    /**
     * Matches `iso_camera_sync_screen_to_cam` in `gsu_iso_camera.i`:
     * updates world-space `camXQ6` and `camZQ6` from screen-space `(cameraPanX, cameraPanY)`.
     */
    private fun syncScreenToCam() {
        val sin = IsoSoftwareRenderer.sinQ14(cameraYaw)
        val cos = IsoSoftwareRenderer.cosQ14(cameraYaw)
        val dxQ6 = cameraPanX shl 6
        val dzQ6 = cameraPanY shl 7
        camXQ6 = (fmult(dxQ6, cos) + fmult(dzQ6, sin)) shl 2
        camZQ6 = (fmult(dzQ6, cos) - fmult(dxQ6, sin)) shl 2
    }

    /**
     * Matches `iso_camera_sync_cam_to_screen` in `gsu_iso_camera.i`:
     * rotates world-space `(camXQ6, camZQ6)` around the viewport center `(128, 80)`
     * and clamps the resulting screen-space offset to `[-MAX_PAN_PIXELS, MAX_PAN_PIXELS]`.
     */
    private fun syncCamToScreen() {
        val sin = IsoSoftwareRenderer.sinQ14(cameraYaw)
        val cos = IsoSoftwareRenderer.cosQ14(cameraYaw)
        val dxQ4 = fmult(camXQ6, cos) - fmult(camZQ6, sin)
        val dzQ4 = fmult(camXQ6, sin) + fmult(camZQ6, cos)
        val dx = (dxQ4 + 8) shr 4
        val dy = ((dzQ4 + 8) shr 4) shr 1
        cameraPanX = dx.coerceIn(-MAX_PAN_PIXELS, MAX_PAN_PIXELS)
        cameraPanY = dy.coerceIn(-MAX_PAN_PIXELS, MAX_PAN_PIXELS)
    }

    private fun clampSelectionToBounds() {
        val valid = selectedCells.filter { (c, r) -> document.grid.isInBounds(c, r) }.toSet()
        selectedCells = if (valid.isNotEmpty()) valid else setOf(0 to 0)
    }

    fun primarySelectedCell(): MapCell? {
        val first = selectedCells.firstOrNull() ?: return null
        return if (document.grid.isInBounds(first.first, first.second)) {
            document.grid.cellAt(first.first, first.second)
        } else {
            null
        }
    }

    fun openLevel(file: File): Boolean =
        try {
            val loaded = LevelDocumentService.loadLevel(file)
            currentLevelFile = file
            unsavedAtlasFile = null
            invalidateDiskAtlasCache()
            undoStack.clear()
            redoStack.clear()
            document = loaded
            brushCell = loaded.grid.cells.firstOrNull() ?: MapCell()
            selectedCells = setOf(0 to 0)
            hasUnsavedChanges = false
            errorMessage = null
            statusMessage = "Opened level '${file.name}'"
            true
        } catch (e: Exception) {
            errorMessage = "Failed to open level '${file.name}': ${e.message}"
            false
        }

    fun importCa65Map(
        ca65File: File,
        explicitAtlasFile: File? = null,
    ): Boolean =
        try {
            val atlasFile = explicitAtlasFile ?: resolveAtlasFile()
            val atlas =
                if (atlasFile != null && atlasFile.exists()) {
                    ProjectService.loadProject(atlasFile).first
                } else {
                    resolveAtlasAndPalette()?.first
                }
            val relAtlas =
                if (atlasFile != null && currentLevelFile != null) {
                    computeRelativePath(currentLevelFile!!, atlasFile)
                } else {
                    atlasFile?.path ?: document.atlasPath
                }
            if (atlasFile != null) {
                unsavedAtlasFile = atlasFile
            }
            val imported =
                Ca65MapImporter.importFile(
                    ca65File = ca65File,
                    atlasPath = relAtlas,
                    atlas = atlas,
                    levelName = document.name.ifBlank { ca65File.nameWithoutExtension },
                )
            mutateDocument(status = "Imported '${ca65File.name}' (${imported.grid.cols}x${imported.grid.rows})") {
                imported
            }
            brushCell = imported.grid.cells.firstOrNull() ?: MapCell()
            selectedCells = setOf(0 to 0)
            true
        } catch (e: Exception) {
            errorMessage = "Failed to import '${ca65File.name}': ${e.message}"
            false
        }

    override fun save(): Boolean {
        val target = currentLevelFile ?: return false
        return saveAs(target)
    }

    override fun saveAs(file: File): Boolean =
        try {
            val resolvedAtlasFile = resolveAtlasFile()
            val resolvedPair = resolveAtlasAndPalette()
            val updatedDoc =
                if (resolvedAtlasFile != null) {
                    document.copy(
                        name = file.nameWithoutExtension,
                        atlasPath = computeRelativePath(file, resolvedAtlasFile),
                    )
                } else {
                    document.copy(name = file.nameWithoutExtension)
                }

            val validation =
                LevelValidator.validate(
                    levelFile = file,
                    document = updatedDoc,
                    overrideAtlas = resolvedPair?.first,
                )
            if (!validation.valid) {
                val errSummary =
                    validation.errors
                        .take(3)
                        .joinToString("\n") { "• [${it.code}] ${it.message}" }
                // Still save the authoring .sfxlevel JSON so work is never lost, while reporting why .map export was skipped
                file.parentFile?.mkdirs()
                file.writeText(LevelDocumentService.toJson(updatedDoc))
                document = updatedDoc
                currentLevelFile = file
                hasUnsavedChanges = false
                errorMessage = "Saved '${file.name}', but binary .map export was skipped due to validation errors:\n$errSummary"
                statusMessage = "Saved '${file.name}' (with ${validation.errors.size} error(s))"
                true
            } else {
                LevelDocumentService.saveLevel(
                    levelFile = file,
                    document = updatedDoc,
                    atlas = resolvedPair?.first,
                    palette = resolvedPair?.second,
                    previewYaw = cameraYaw,
                )
                document = updatedDoc
                currentLevelFile = file
                hasUnsavedChanges = false
                errorMessage = null
                val warnSuffix =
                    if (validation.warnings.isNotEmpty()) {
                        " (${validation.warnings.size} warning(s))"
                    } else {
                        ""
                    }
                statusMessage = "Saved and packed '${file.name}' (.map, .i, _preview.png)$warnSuffix"
                true
            }
        } catch (e: Exception) {
            errorMessage = "Failed to save '${file.name}': ${e.message}"
            false
        }

    fun hasDiagnosticAt(
        col: Int,
        row: Int,
        validation: LevelValidationResult,
    ): DiagnosticSeverity? {
        if (validation.errors.any { it.col == col && it.row == row }) return DiagnosticSeverity.ERROR
        if (validation.warnings.any { it.col == col && it.row == row }) return DiagnosticSeverity.WARNING
        return null
    }

    companion object {
        const val MAX_HISTORY = 50
        const val PAN_STEP_PIXELS = 2
        const val MAX_PAN_PIXELS = 128

        private fun canonicalOrAbsolute(file: File): File =
            try {
                file.canonicalFile
            } catch (_: Throwable) {
                file.absoluteFile
            }

        private fun computeRelativePath(
            levelFile: File,
            atlasFile: File,
        ): String {
            val baseDir = (levelFile.absoluteFile.parentFile ?: File(".").absoluteFile).toPath().normalize()
            val target = atlasFile.absoluteFile.toPath().normalize()
            return try {
                baseDir.relativize(target).toString().replace('\\', '/')
            } catch (_: IllegalArgumentException) {
                atlasFile.path
            }
        }
    }
}
