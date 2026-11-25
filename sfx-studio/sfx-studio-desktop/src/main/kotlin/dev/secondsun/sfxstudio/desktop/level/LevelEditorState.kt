package dev.secondsun.sfxstudio.desktop.level

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.DescriptorTable
import dev.secondsun.sfxstudio.core.atlas.SFX_ATLAS_EXTENSION
import dev.secondsun.sfxstudio.core.atlas.SFX_PROJ_EXTENSION
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.atlas.TextureDescriptorInfo
import dev.secondsun.sfxstudio.core.level.Ca65MapImporter
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.DiagnosticSeverity
import dev.secondsun.sfxstudio.core.level.EdgePanels
import dev.secondsun.sfxstudio.core.level.FacingDirection
import dev.secondsun.sfxstudio.core.level.GameplayBinarySerializer
import dev.secondsun.sfxstudio.core.level.GameplayLayer
import dev.secondsun.sfxstudio.core.level.GridCoord
import dev.secondsun.sfxstudio.core.level.IntPoint
import dev.secondsun.sfxstudio.core.level.InteractableKind
import dev.secondsun.sfxstudio.core.level.InteractableSpawn
import dev.secondsun.sfxstudio.core.level.IsoSoftwareRenderer
import dev.secondsun.sfxstudio.core.level.LevelDiagnostic
import dev.secondsun.sfxstudio.core.level.LevelDocument
import dev.secondsun.sfxstudio.core.level.LevelDocumentService
import dev.secondsun.sfxstudio.core.level.LevelValidationResult
import dev.secondsun.sfxstudio.core.level.LevelValidator
import dev.secondsun.sfxstudio.core.level.MapCell
import dev.secondsun.sfxstudio.core.level.MapGrid
import dev.secondsun.sfxstudio.core.level.NpcAiType
import dev.secondsun.sfxstudio.core.level.NpcSpawn
import dev.secondsun.sfxstudio.core.level.PlayerSpawn
import dev.secondsun.sfxstudio.core.level.SFX_LEVEL_EXTENSION
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import dev.secondsun.sfxstudio.core.level.WalkabilityOverride
import dev.secondsun.sfxstudio.desktop.awt.createDefaultStudioPlatform
import dev.secondsun.sfxstudio.desktop.shell.LevelCommandBarSession
import dev.secondsun.sfxstudio.desktop.shell.LevelEditMode
import dev.secondsun.sfxstudio.desktop.shell.StudioPlatform
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import java.io.File
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToInt

enum class LevelTool {
    SELECT,
    BRUSH,
    EYEDROPPER,
    PAN,
}

enum class GameplayTool {
    SELECT_ENTITY,
    PAINT_WALKABILITY,
    PLACE_PLAYER,
    PLACE_NPC,
    EDIT_WAYPOINTS,
    PLACE_INTERACTABLE,
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

enum class CompactInspectorTab {
    TERRAIN,
    TEXTURES,
    GAMEPLAY,
    ALERTS,
}

data class BrushLayerMask(
    val applyHeight: Boolean = true,
    val applyArchetypeAndRotation: Boolean = true,
    val applyTopTex: Boolean = true,
    val applySkirtTex: Boolean = true,
    val applyPanelTexAndFlags: Boolean = false,
) {
    val enabledCount: Int
        get() {
            var count = 0
            if (applyHeight) count++
            if (applyArchetypeAndRotation) count++
            if (applyTopTex) count++
            if (applySkirtTex) count++
            if (applyPanelTexAndFlags) count++
            return count
        }
}

data class CameraRevealTarget(
    val yaw: Int,
    val panX: Int,
    val panY: Int,
)

internal data class IsoViewportLayout(
    val fitScale: Float,
    val scale: Float,
    val drawW: Int,
    val drawH: Int,
    val offX: Int,
    val offY: Int,
)

internal fun computeIsoViewportLayout(
    canvasW: Int,
    canvasH: Int,
    imgW: Int,
    imgH: Int,
    zoom: Float,
): IsoViewportLayout {
    val safeW = imgW.coerceAtLeast(1)
    val safeH = imgH.coerceAtLeast(1)
    val fitScale = minOf(canvasW.toFloat() / safeW, canvasH.toFloat() / safeH).coerceAtLeast(0.5f)
    val effectiveScale = if (zoom <= 0f) fitScale else zoom.coerceIn(0.5f, 8f)
    val drawW = (safeW * effectiveScale).roundToInt().coerceAtLeast(1)
    val drawH = (safeH * effectiveScale).roundToInt().coerceAtLeast(1)
    val offX = ((canvasW - drawW) / 2f).roundToInt()
    val offY = ((canvasH - drawH) / 2f).roundToInt()
    return IsoViewportLayout(
        fitScale = fitScale,
        scale = effectiveScale,
        drawW = drawW,
        drawH = drawH,
        offX = offX,
        offY = offY,
    )
}

internal fun mapCanvasToRenderPixel(
    pos: Offset,
    canvasW: Int,
    canvasH: Int,
    imgW: Int,
    imgH: Int,
    zoom: Float,
): Pair<Int, Int> {
    val layout = computeIsoViewportLayout(canvasW, canvasH, imgW, imgH, zoom)
    val px = floor((pos.x - layout.offX) / layout.scale).toInt()
    val py = floor((pos.y - layout.offY) / layout.scale).toInt()
    return px to py
}

internal data class GridViewportLayout(
    val cellPix: Float,
    val originX: Float,
    val originY: Float,
    val rulerLeft: Float,
    val rulerTop: Float,
    val totalW: Float,
    val totalH: Float,
)

internal fun computeGridViewportLayout(
    canvasW: Int,
    canvasH: Int,
    cols: Int,
    rows: Int,
    zoom: Float = 0f,
    panOffset: Offset = Offset.Zero,
    rulerLeft: Float = 20f,
    rulerTop: Float = 16f,
): GridViewportLayout {
    val safeCols = cols.coerceAtLeast(1)
    val safeRows = rows.coerceAtLeast(1)
    val availW = (canvasW - rulerLeft - 8f).coerceAtLeast(48f)
    val availH = (canvasH - rulerTop - 24f).coerceAtLeast(48f)
    val fitCellPix = minOf(availW / safeCols, availH / safeRows).coerceAtLeast(12f)
    val cellPix = if (zoom <= 0f) fitCellPix else (fitCellPix * zoom.coerceIn(0.5f, 6f)).coerceAtLeast(12f)
    val totalW = cellPix * safeCols
    val totalH = cellPix * safeRows
    val centeredX = rulerLeft + (canvasW - rulerLeft - totalW) / 2f
    val centeredY = rulerTop + 12f + (canvasH - rulerTop - 16f - totalH) / 2f
    return GridViewportLayout(
        cellPix = cellPix,
        originX = centeredX + panOffset.x,
        originY = centeredY + panOffset.y,
        rulerLeft = rulerLeft,
        rulerTop = rulerTop,
        totalW = totalW,
        totalH = totalH,
    )
}

internal fun mapCanvasToGridCoord(
    pos: Offset,
    canvasW: Int,
    canvasH: Int,
    cols: Int,
    rows: Int,
    zoom: Float = 0f,
    panOffset: Offset = Offset.Zero,
): Pair<Int, Int>? {
    val layout = computeGridViewportLayout(canvasW, canvasH, cols, rows, zoom, panOffset)
    val c = floor((pos.x - layout.originX) / layout.cellPix).toInt()
    val r = floor((pos.y - layout.originY) / layout.cellPix).toInt()
    return if (c in 0 until cols && r in 0 until rows) c to r else null
}

/**
 * Stateful editing session for an isometric [LevelDocument] (`.sfxlevel`) in SuperFX Studio Desktop.
 *
 * Resolves live [AtlasDocument] instances via [StudioState.resolveLiveOrDiskAtlas] so unsaved
 * textures, slices, aliases, and palette edits in open tabs propagate immediately to level
 * creation, ca65 `.i` import, validation, and 3D software rendering.
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
    private val standalonePlatform: StudioPlatform? = null,
) : LevelCommandBarSession {
    override val sessionId: String = UUID.randomUUID().toString()
    override val defaultExtension: String = SFX_LEVEL_EXTENSION
    override val documentTypeLabel: String = "Level Document"

    private val fallbackPlatform by lazy { createDefaultStudioPlatform() }
    val platform: StudioPlatform
        get() = studio?.platform ?: standalonePlatform ?: fallbackPlatform

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
    var lastError by mutableStateOf<String?>(null)
        private set

    fun reportError(message: String) {
        lastError = message
        studio?.reportError(message)
    }

    // Authoring Mode & Gameplay Overlay (LevelCommandBarSession)
    private var _editMode by mutableStateOf(LevelEditMode.TERRAIN)
    override var editMode: LevelEditMode
        get() = _editMode
        set(value) {
            _editMode = value
            if (value == LevelEditMode.GAMEPLAY) {
                showGameplayOverlay = true
                compactInspectorTab = CompactInspectorTab.GAMEPLAY
            } else {
                gameplayPanActive = false
                if (compactInspectorTab == CompactInspectorTab.GAMEPLAY) {
                    compactInspectorTab = CompactInspectorTab.TERRAIN
                }
            }
        }

    override var showGameplayOverlay by mutableStateOf(initialDocument.gameplay != null)

    override val hasGameplayLayer: Boolean
        get() = document.gameplay != null

    override val gameplayEntityCount: Int
        get() {
            val gp = document.gameplay ?: return 0
            return 1 + gp.npcs.size + gp.interactables.size
        }

    // Tooling & selection state
    var activeTool by mutableStateOf(LevelTool.SELECT)
    var gameplayTool by mutableStateOf(GameplayTool.SELECT_ENTITY)
    var gameplayPanActive by mutableStateOf(false)

    val isPanToolActive: Boolean
        get() =
            if (editMode == LevelEditMode.TERRAIN) {
                activeTool == LevelTool.PAN
            } else {
                gameplayPanActive
            }

    var selectedNpcIndex by mutableStateOf<Int?>(null)
    var selectedInteractableIndex by mutableStateOf<Int?>(null)
    var paintWalkable by mutableStateOf(true)
    var paintMoveCost by mutableStateOf(1)
    var paintWalkFlags by mutableStateOf(0)
    var pendingInteractableKind by mutableStateOf(InteractableKind.CHEST)

    var activeTextureSlot by mutableStateOf(ActiveTextureSlot.TOP)
    var expandedTextureSlots by mutableStateOf<Set<ActiveTextureSlot>>(emptySet())
    var brushCell by mutableStateOf(
        initialDocument.grid.cells.firstOrNull() ?: MapCell(),
    )
    var brushMask by mutableStateOf(BrushLayerMask())

    var selectedCells by mutableStateOf<Set<Pair<Int, Int>>>(setOf(0 to 0))
    var hoveredCell by mutableStateOf<Pair<Int, Int>?>(null)

    // Viewport & responsive inspector state
    var centerViewMode by mutableStateOf(LevelCenterViewMode.SPLIT)
    var compactInspectorTab by mutableStateOf(CompactInspectorTab.TERRAIN)
    var activeDiagnosticIndex by mutableStateOf(0)

    var cameraYaw by mutableStateOf(15)
    var cameraPanX by mutableStateOf(0)
    var cameraPanY by mutableStateOf(0)
    private var camXQ6: Int = 0
    private var camZQ6: Int = 0

    /**
     * 3D viewport zoom factor (`0f` = Fit to viewport, or explicit `1f..4f` scale).
     */
    var isoZoom by mutableStateOf(0f)

    /**
     * 2D top-down grid zoom factor (`0f` = Fit to viewport, or explicit `1f..4f` multiplier).
     */
    var gridZoom by mutableStateOf(0f)
    var gridPanOffset by mutableStateOf(Offset.Zero)

    var animateCameraReveal: Boolean = false
    var cameraRevealTarget by mutableStateOf<CameraRevealTarget?>(null)
        private set

    // Undo / Redo history
    private val undoStack = ArrayDeque<LevelDocument>()
    private val redoStack = ArrayDeque<LevelDocument>()
    private var strokeSnapshot: LevelDocument? = null
    private var savedDocument: LevelDocument = initialDocument
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

    // Local disk cache only when running standalone without StudioState
    private var standaloneDiskKey: Pair<String, Long>? = null
    private var standaloneDiskDoc: AtlasDocument? = null

    init {
        syncGameplayEntitySelectionForCell(0, 0)
    }

    override val statusSummary: String
        get() {
            val g = document.grid
            val gp = document.gameplay
            val gmapPart =
                if (gp != null) {
                    " + ${GameplayBinarySerializer.byteSize(g, gp)} B .gmap"
                } else {
                    ""
                }
            val valRes = currentValidation()
            return "${g.cols}x${g.rows} (${g.byteSize} B$gmapPart) | Yaw ${cameraYaw * 3}° | " +
                "${valRes.errors.size} err, ${valRes.warnings.size} warn"
        }

    fun isLinkedAtlasOpenInTab(): Boolean {
        val s = studio ?: return false
        val targetFile = resolveAtlasFile()
        if (targetFile == null) {
            return s.openSessions.any { it.defaultExtension != SFX_LEVEL_EXTENSION }
        }
        val canonical = StudioState.canonicalOrAbsolute(targetFile)
        return s.openSessions.any { session ->
            session !== this && session.documentFile?.let { StudioState.canonicalOrAbsolute(it) == canonical } == true
        }
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
     * Resolves the active [AtlasDocument] for rendering, validation, and ca65 import.
     * Prefers an open `AtlasEditorState` tab matching the canonical path before touching disk.
     */
    fun resolveAtlasDocument(explicitAtlasFile: File? = null): AtlasDocument? {
        val targetFile = explicitAtlasFile ?: resolveAtlasFile()
        if (studio != null) {
            return studio.resolveLiveOrDiskAtlas(targetFile)
        }
        if (targetFile != null && targetFile.exists() && targetFile.isFile) {
            val canonical = StudioState.canonicalOrAbsolute(targetFile)
            val key = canonical.path to targetFile.lastModified()
            if (standaloneDiskKey == key && standaloneDiskDoc != null) {
                return standaloneDiskDoc
            }
            return try {
                val loaded = AtlasDocument.load(targetFile)
                standaloneDiskKey = key
                standaloneDiskDoc = loaded
                loaded
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    fun resolveAtlasAndPalette(): Pair<TextureAtlas, SnesPalette>? = resolveAtlasDocument()?.let { it.atlas to it.palette }

    fun invalidateDiskAtlasCache() {
        standaloneDiskKey = null
        standaloneDiskDoc = null
        studio?.invalidateDiskAtlasCache()
    }

    fun currentValidation(): LevelValidationResult {
        val atlasDoc = resolveAtlasDocument()
        if (atlasDoc != null) {
            return LevelValidator.validate(document, atlasDoc)
        }
        val targetFile = resolveAtlasFile()
        val diag =
            if (targetFile == null) {
                LevelDiagnostic(
                    severity = DiagnosticSeverity.ERROR,
                    code = "MISSING_ATLAS_PATH",
                    message = "No Texture Atlas (.sfxproj) is linked to this level.",
                )
            } else {
                LevelDiagnostic(
                    severity = DiagnosticSeverity.ERROR,
                    code = "ATLAS_NOT_FOUND",
                    message = "Linked Texture Atlas '${targetFile.name}' could not be found or loaded.",
                )
            }
        return LevelValidationResult(valid = false, errors = listOf(diag), warnings = emptyList())
    }

    /**
     * Extracts a preview [SourceTexture] directly from the backing [SourceTexture] sub-rectangle
     * via [DescriptorTable] without materializing the entire atlas binary.
     */
    fun extractDescriptorPreview(
        atlas: TextureAtlas,
        desc: TextureDescriptorInfo,
    ): SourceTexture? =
        atlas
            .descriptorTable()
            .getOrNull()
            ?.extractPreviewTexture(desc)

    private fun pushUndo(snapshot: LevelDocument) {
        if (undoStack.lastOrNull() == snapshot) return
        if (undoStack.size >= MAX_HISTORY) {
            undoStack.removeFirst()
        }
        undoStack.addLast(snapshot)
        redoStack.clear()
        historyRevision++
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
        hasUnsavedChanges = (document != savedDocument)
        if (status != null) {
            statusMessage = status
        }
    }

    override fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        if (redoStack.size >= MAX_HISTORY) {
            redoStack.removeFirst()
        }
        redoStack.addLast(document)
        document = prev
        clampSelectionToBounds()
        hasUnsavedChanges = (document != savedDocument)
        historyRevision++
        statusMessage = "Undo"
    }

    override fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        if (undoStack.size >= MAX_HISTORY) {
            undoStack.removeFirst()
        }
        undoStack.addLast(document)
        document = next
        clampSelectionToBounds()
        hasUnsavedChanges = (document != savedDocument)
        historyRevision++
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

    private fun syncGameplayEntitySelectionForCell(
        col: Int,
        row: Int,
    ) {
        val gp = document.gameplay
        if (gp == null) {
            selectedNpcIndex = null
            selectedInteractableIndex = null
            return
        }
        val npcIdx = gp.npcs.indexOfFirst { it.col == col && it.row == row }.takeIf { it >= 0 }
        val interIdx = gp.interactables.indexOfFirst { it.col == col && it.row == row }.takeIf { it >= 0 }
        selectedNpcIndex = npcIdx
        selectedInteractableIndex = if (npcIdx == null) interIdx else null
    }

    fun selectSingleCell(
        col: Int,
        row: Int,
        additive: Boolean = false,
    ) {
        if (!document.grid.isInBounds(col, row)) return
        val coord = col to row
        val wasRemoved = additive && coord in selectedCells && selectedCells.size > 1
        selectedCells =
            if (additive) {
                if (wasRemoved) {
                    selectedCells - coord
                } else {
                    selectedCells + coord
                }
            } else {
                setOf(coord)
            }
        if (!wasRemoved) {
            syncGameplayEntitySelectionForCell(col, row)
            ensureCellVisibleInPreview(col, row)
        }
        statusMessage = "Selected cell ($col, $row)"
    }

    fun selectPlayerSpawn() {
        val gp = document.gameplay ?: return
        val c = gp.playerSpawn.col
        val r = gp.playerSpawn.row
        if (document.grid.isInBounds(c, r)) {
            selectedCells = setOf(c to r)
            selectedNpcIndex = null
            selectedInteractableIndex = null
            ensureCellVisibleInPreview(c, r)
            statusMessage = "Selected Player Spawn at ($c, $r)"
        }
    }

    fun selectNpc(index: Int) {
        val gp = document.gameplay ?: return
        val npc = gp.npcs.getOrNull(index) ?: return
        selectedNpcIndex = index
        selectedInteractableIndex = null
        if (document.grid.isInBounds(npc.col, npc.row)) {
            selectedCells = setOf(npc.col to npc.row)
            ensureCellVisibleInPreview(npc.col, npc.row)
        }
        statusMessage = "Selected NPC '${npc.id}' at (${npc.col}, ${npc.row})"
    }

    fun selectInteractable(index: Int) {
        val gp = document.gameplay ?: return
        val inter = gp.interactables.getOrNull(index) ?: return
        selectedInteractableIndex = index
        selectedNpcIndex = null
        if (document.grid.isInBounds(inter.col, inter.row)) {
            selectedCells = setOf(inter.col to inter.row)
            ensureCellVisibleInPreview(inter.col, inter.row)
        }
        statusMessage = "Selected ${inter.kind.name.lowercase()} '${inter.id}' at (${inter.col}, ${inter.row})"
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
        ensureCellVisibleInPreview(
            colB.coerceIn(0, document.grid.cols - 1),
            rowB.coerceIn(0, document.grid.rows - 1),
        )
        statusMessage = "Selected ${next.size} cell(s)"
    }

    /**
     * Ensures the cell at ([col], [row]) is both inside the `256x160` 3D software preview
     * and unoccluded by foreground geometry.
     *
     * Invoked only when selecting a cell (or opening/importing a level), never when editing.
     * If the cell is already in-frame and visible, the camera is left untouched; otherwise
     * pans at the current yaw first, and if still occluded rotates to the nearest unobstructed yaw.
     */
    fun ensureCellVisibleInPreview(
        col: Int,
        row: Int,
        animate: Boolean = animateCameraReveal,
    ) {
        val grid = document.grid
        if (!grid.isInBounds(col, row)) return
        val cellIdx = row * grid.cols + col
        val width = IsoSoftwareRenderer.DEFAULT_WIDTH
        val height = IsoSoftwareRenderer.DEFAULT_HEIGHT

        fun applyCameraTarget(
            targetYaw: Int,
            targetPanX: Int,
            targetPanY: Int,
        ) {
            val normYaw = ((targetYaw % 120) + 120) % 120
            val clampedPanX = targetPanX.coerceIn(-MAX_PAN_PIXELS, MAX_PAN_PIXELS)
            val clampedPanY = targetPanY.coerceIn(-MAX_PAN_PIXELS, MAX_PAN_PIXELS)
            if (normYaw == cameraYaw && clampedPanX == cameraPanX && clampedPanY == cameraPanY) {
                cameraRevealTarget = null
                return
            }
            if (animate) {
                cameraRevealTarget = CameraRevealTarget(normYaw, clampedPanX, clampedPanY)
            } else {
                cameraRevealTarget = null
                setCameraImmediate(normYaw, clampedPanX, clampedPanY)
            }
        }

        val atlasAndPalette = resolveAtlasAndPalette()
        if (atlasAndPalette == null) {
            val poly = computeCellFloorPolygon(grid, col, row, cameraYaw, cameraPanX, cameraPanY, width, height)
            if (!isPolygonInBounds(poly, width, height)) {
                val (panX, panY) = computePanToCenterCell(grid, col, row, cameraYaw, width, height)
                applyCameraTarget(cameraYaw, panX, panY)
            }
            return
        }

        val (atlas, palette) = atlasAndPalette

        fun evaluate(
            yaw: Int,
            panX: Int,
            panY: Int,
        ): Pair<Boolean, Int> {
            val res =
                IsoSoftwareRenderer.render(
                    grid = grid,
                    atlas = atlas,
                    palette = palette,
                    yawIndex = yaw,
                    width = width,
                    height = height,
                    panX = panX,
                    panY = panY,
                )
            val poly = res.cellFloorPolygons.getOrNull(cellIdx).orEmpty()
            val inBounds = isPolygonInBounds(poly, width, height)
            var visiblePixels = 0
            for (p in res.cellPickBuffer) {
                if (p == cellIdx) visiblePixels++
            }
            return inBounds to visiblePixels
        }

        // 1. Leave camera untouched if cell is already in-frame and unoccluded
        val (curInBounds, curVisible) = evaluate(cameraYaw, cameraPanX, cameraPanY)
        if (curInBounds && curVisible >= MIN_VISIBLE_PIXELS) {
            cameraRevealTarget = null
            return
        }

        // 2. Try panning at the current yaw first (avoid unnecessary rotation)
        val (curCenterPanX, curCenterPanY) = computePanToCenterCell(grid, col, row, cameraYaw, width, height)
        var bestYaw = cameraYaw
        var bestPanX = curCenterPanX
        var bestPanY = curCenterPanY
        var (bestInBounds, bestVisible) = evaluate(cameraYaw, curCenterPanX, curCenterPanY)
        if (bestInBounds && bestVisible >= MIN_VISIBLE_PIXELS) {
            applyCameraTarget(bestYaw, bestPanX, bestPanY)
            return
        }

        // 3. Cell is occluded at current yaw: search candidate yaws (90° turns first, then 45°/15°)
        val candidateOffsets = listOf(30, -30, 60, 15, -15, 45, -45, 75, -75)
        for (offset in candidateOffsets) {
            val candYaw = (((cameraYaw + offset) % 120) + 120) % 120
            val (candPanX, candPanY) = computePanToCenterCell(grid, col, row, candYaw, width, height)
            val (candInBounds, candVisible) = evaluate(candYaw, candPanX, candPanY)
            val isBetter =
                (candInBounds && !bestInBounds) ||
                    (candInBounds == bestInBounds && candVisible > bestVisible)
            if (isBetter) {
                bestYaw = candYaw
                bestPanX = candPanX
                bestPanY = candPanY
                bestInBounds = candInBounds
                bestVisible = candVisible
                if (bestInBounds && bestVisible >= GOOD_VISIBLE_PIXELS) {
                    break
                }
            }
        }

        applyCameraTarget(bestYaw, bestPanX, bestPanY)
    }

    internal fun setCameraImmediate(
        yaw: Int,
        panX: Int,
        panY: Int,
    ) {
        cameraYaw = ((yaw % 120) + 120) % 120
        cameraPanX = panX.coerceIn(-MAX_PAN_PIXELS, MAX_PAN_PIXELS)
        cameraPanY = panY.coerceIn(-MAX_PAN_PIXELS, MAX_PAN_PIXELS)
        syncScreenToCam()
    }

    internal fun clearCameraRevealTarget() {
        cameraRevealTarget = null
    }

    private fun computeCellFloorPolygon(
        grid: MapGrid,
        col: Int,
        row: Int,
        yawIndex: Int,
        panX: Int,
        panY: Int,
        width: Int,
        height: Int,
    ): List<IntPoint> {
        val sin = IsoSoftwareRenderer.sinQ14(yawIndex)
        val cos = IsoSoftwareRenderer.cosQ14(yawIndex)
        val basis = IsoSoftwareRenderer.computeBasis(yawIndex)
        val lat = IsoSoftwareRenderer.populateLattice(basis)
        val centerYDefault = (height * 5) / 9
        val origin = IsoSoftwareRenderer.computeCenteredOrigin(grid.cols, grid.rows, width / 2 + panX, centerYDefault + panY, yawIndex)
        val sxXQ5 = cos shr 4
        val sxYQ5 = sin shr 5
        val szXQ5 = -(sin shr 4)
        val szYQ5 = cos shr 5
        val cell = grid.cellAt(col, row)
        val anchorX = ((origin.x shl 5) + col * sxXQ5 + row * szXQ5 + 16) shr 5
        val anchorY = (((origin.y shl 5) + col * sxYQ5 + row * szYQ5 + 16) shr 5) - cell.baseHeight * IsoSoftwareRenderer.STEP_1H_PIXELS
        return listOf(
            IntPoint(anchorX + lat[0].x, anchorY + lat[0].y),
            IntPoint(anchorX + lat[1].x, anchorY + lat[1].y),
            IntPoint(anchorX + lat[2].x, anchorY + lat[2].y),
            IntPoint(anchorX + lat[3].x, anchorY + lat[3].y),
        )
    }

    private fun computePanToCenterCell(
        grid: MapGrid,
        col: Int,
        row: Int,
        yawIndex: Int,
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        val poly0 = computeCellFloorPolygon(grid, col, row, yawIndex, 0, 0, width, height)
        val cx0 = (poly0[0].x + poly0[2].x) / 2
        val cy0 = (poly0[0].y + poly0[2].y) / 2
        val panX = ((width / 2) - cx0).coerceIn(-MAX_PAN_PIXELS, MAX_PAN_PIXELS)
        val panY = ((height / 2) - cy0).coerceIn(-MAX_PAN_PIXELS, MAX_PAN_PIXELS)
        return panX to panY
    }

    private fun isPolygonInBounds(
        poly: List<IntPoint>,
        width: Int,
        height: Int,
    ): Boolean = poly.isNotEmpty() && poly.all { it.x in 0 until width && it.y in 0 until height }

    fun handleCellInteraction(
        col: Int,
        row: Int,
        isDragContinue: Boolean = false,
        shiftHeld: Boolean = false,
    ) {
        if (!document.grid.isInBounds(col, row)) return
        if (editMode == LevelEditMode.GAMEPLAY) {
            if (gameplayPanActive) return
            if (!isDragContinue) {
                handleGameplayCellClick(col, row)
            } else if (gameplayTool == GameplayTool.PAINT_WALKABILITY) {
                if (document.gameplay == null) {
                    enableGameplayLayer()
                }
                setCellWalkabilityOverride(
                    col = col,
                    row = row,
                    walkable = paintWalkable,
                    moveCost = if (paintWalkable) paintMoveCost else 0,
                    flags = paintWalkFlags,
                    recordImmediateUndo = strokeSnapshot == null,
                )
                selectedCells = setOf(col to row)
            }
            return
        }

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
            LevelTool.PAN -> {
                // Safe navigation mode: never selects or paints cells
            }
        }
    }

    // --- Gameplay Layer Authoring Operations ---

    private fun createDefaultGameplayLayer(grid: MapGrid): GameplayLayer {
        val primary = selectedCells.firstOrNull()
        val candidateCoords =
            buildList {
                if (primary != null && grid.isInBounds(primary.first, primary.second)) {
                    add(primary)
                }
                for (r in 0 until grid.rows) {
                    for (c in 0 until grid.cols) {
                        add(c to r)
                    }
                }
            }
        val walkableCell =
            candidateCoords.firstOrNull { (c, r) ->
                GameplayBinarySerializer.defaultWalkableFor(grid.cellAt(c, r).archetype)
            }
        return if (walkableCell != null) {
            GameplayLayer(
                playerSpawn = PlayerSpawn(col = walkableCell.first, row = walkableCell.second, facing = FacingDirection.SOUTH),
            )
        } else {
            GameplayLayer(
                walkabilityOverrides = listOf(WalkabilityOverride(col = 0, row = 0, walkable = true, moveCost = 1)),
                playerSpawn = PlayerSpawn(col = 0, row = 0, facing = FacingDirection.SOUTH),
            )
        }
    }

    fun enableGameplayLayer() {
        showGameplayOverlay = true
        if (document.gameplay != null) return
        mutateDocument(status = "Enabled Gameplay Layer (.gmap)") { doc ->
            doc.copy(gameplay = createDefaultGameplayLayer(doc.grid))
        }
        val spawn = document.gameplay?.playerSpawn
        if (spawn != null) {
            selectedCells = setOf(spawn.col to spawn.row)
        }
    }

    fun removeGameplayLayer() {
        if (document.gameplay == null) return
        selectedNpcIndex = null
        selectedInteractableIndex = null
        mutateDocument(status = "Removed Gameplay Layer") { doc ->
            doc.copy(gameplay = null)
        }
    }

    fun walkabilityOverrideAt(
        col: Int,
        row: Int,
    ): WalkabilityOverride? = document.gameplay?.walkabilityOverrides?.firstOrNull { it.col == col && it.row == row }

    fun effectiveWalkabilityAt(
        col: Int,
        row: Int,
    ): WalkabilityOverride {
        val existing = walkabilityOverrideAt(col, row)
        if (existing != null) return existing
        val defaultWalk =
            if (document.grid.isInBounds(col, row)) {
                GameplayBinarySerializer.defaultWalkableFor(document.grid.cellAt(col, row).archetype)
            } else {
                true
            }
        return WalkabilityOverride(
            col = col,
            row = row,
            walkable = defaultWalk,
            moveCost = if (defaultWalk) 1 else 0,
            flags = 0,
        )
    }

    fun setCellWalkabilityOverride(
        col: Int,
        row: Int,
        walkable: Boolean,
        moveCost: Int = if (walkable) 1 else 0,
        flags: Int = 0,
        recordImmediateUndo: Boolean = true,
    ) {
        if (!document.grid.isInBounds(col, row)) return
        val clampedCost = if (walkable) moveCost.coerceIn(0, 7) else 0
        val clampedFlags = flags.coerceIn(0, 15)
        showGameplayOverlay = true
        mutateDocument(
            recordUndo = recordImmediateUndo,
            status = "Set walkability at ($col, $row): ${if (walkable) "walkable (cost $clampedCost)" else "blocked"}",
        ) { doc ->
            val gp = doc.gameplay ?: createDefaultGameplayLayer(doc.grid)
            val filtered = gp.walkabilityOverrides.filterNot { it.col == col && it.row == row }
            val updatedOv =
                WalkabilityOverride(
                    col = col,
                    row = row,
                    walkable = walkable,
                    moveCost = clampedCost,
                    flags = clampedFlags,
                )
            doc.copy(gameplay = gp.copy(walkabilityOverrides = filtered + updatedOv))
        }
    }

    fun clearCellWalkabilityOverride(
        col: Int,
        row: Int,
    ) {
        val gp = document.gameplay ?: return
        if (gp.walkabilityOverrides.none { it.col == col && it.row == row }) return
        mutateDocument(status = "Cleared walkability override at ($col, $row)") { doc ->
            val cur = doc.gameplay ?: return@mutateDocument doc
            doc.copy(
                gameplay =
                    cur.copy(
                        walkabilityOverrides = cur.walkabilityOverrides.filterNot { it.col == col && it.row == row },
                    ),
            )
        }
    }

    fun setPlayerSpawn(
        col: Int,
        row: Int,
        facing: FacingDirection = document.gameplay?.playerSpawn?.facing ?: FacingDirection.SOUTH,
    ) {
        if (!document.grid.isInBounds(col, row)) return
        showGameplayOverlay = true
        mutateDocument(status = "Set Player Spawn to ($col, $row) facing ${facing.name}") { doc ->
            val gp = doc.gameplay ?: createDefaultGameplayLayer(doc.grid)
            doc.copy(gameplay = gp.copy(playerSpawn = PlayerSpawn(col = col, row = row, facing = facing)))
        }
    }

    private fun uniqueNpcId(
        existing: List<NpcSpawn>,
        requested: String?,
        aiType: NpcAiType,
    ): String {
        val used = existing.map { it.id.lowercase() }.toSet()
        val baseCandidate =
            requested
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { SourceTexture.sanitizeToIdentifier(it).lowercase() }
        if (baseCandidate != null && baseCandidate !in used) {
            return baseCandidate
        }
        val prefix = baseCandidate ?: if (aiType == NpcAiType.PATROL) "patrol_npc" else "guard_npc"
        var suffix = existing.size
        while ("${prefix}_$suffix".lowercase() in used) {
            suffix++
        }
        return "${prefix}_$suffix"
    }

    fun addNpc(
        col: Int,
        row: Int,
        id: String? = null,
        aiType: NpcAiType = NpcAiType.GUARD,
    ): Int? {
        if (!document.grid.isInBounds(col, row)) return null
        val curCount = document.gameplay?.npcs?.size ?: 0
        if (curCount >= 255) {
            reportError("Maximum of 255 NPCs reached.")
            return null
        }
        showGameplayOverlay = true
        var addedIndex: Int? = null
        mutateDocument(status = "Added NPC at ($col, $row)") { doc ->
            val gp = doc.gameplay ?: createDefaultGameplayLayer(doc.grid)
            val resolvedId = uniqueNpcId(gp.npcs, id, aiType)
            val initialWaypoints =
                if (aiType == NpcAiType.PATROL) {
                    listOf(GridCoord(col, row))
                } else {
                    emptyList()
                }
            val newNpc =
                NpcSpawn(
                    id = resolvedId,
                    col = col,
                    row = row,
                    aiType = aiType,
                    patrolWaypoints = initialWaypoints,
                )
            val nextNpcs = gp.npcs + newNpc
            addedIndex = nextNpcs.lastIndex
            doc.copy(gameplay = gp.copy(npcs = nextNpcs))
        }
        if (addedIndex != null) {
            selectedNpcIndex = addedIndex
            selectedInteractableIndex = null
            selectedCells = setOf(col to row)
        }
        return addedIndex
    }

    fun updateNpc(
        index: Int,
        updated: NpcSpawn,
    ) {
        val gp = document.gameplay ?: return
        if (index !in gp.npcs.indices) return
        val cols = document.grid.cols
        val rows = document.grid.rows
        val sanitized =
            updated.copy(
                id = updated.id.trim().ifBlank { gp.npcs[index].id },
                col = updated.col.coerceIn(0, cols - 1),
                row = updated.row.coerceIn(0, rows - 1),
                spriteId = updated.spriteId.coerceIn(0, 255),
                aggroRange = updated.aggroRange.coerceIn(0, 255),
                patrolWaypoints =
                    updated.patrolWaypoints
                        .take(GameplayBinarySerializer.MAX_WAYPOINTS)
                        .map { GridCoord(it.col.coerceIn(0, cols - 1), it.row.coerceIn(0, rows - 1)) },
                hp = updated.hp.coerceIn(1, 255),
                atk = updated.atk.coerceIn(0, 255),
                def = updated.def.coerceIn(0, 255),
                speed = updated.speed.coerceIn(0, 255),
                movePoints = updated.movePoints.coerceIn(0, 255),
            )
        mutateDocument(status = "Updated NPC '${sanitized.id}'") { doc ->
            val cur = doc.gameplay ?: return@mutateDocument doc
            if (index !in cur.npcs.indices) return@mutateDocument doc
            val list = cur.npcs.toMutableList()
            list[index] = sanitized
            doc.copy(gameplay = cur.copy(npcs = list))
        }
    }

    fun removeNpc(index: Int) {
        val gp = document.gameplay ?: return
        if (index !in gp.npcs.indices) return
        val removedId = gp.npcs[index].id
        mutateDocument(status = "Removed NPC '$removedId'") { doc ->
            val cur = doc.gameplay ?: return@mutateDocument doc
            if (index !in cur.npcs.indices) return@mutateDocument doc
            val list = cur.npcs.toMutableList()
            list.removeAt(index)
            doc.copy(gameplay = cur.copy(npcs = list))
        }
        val curSel = selectedNpcIndex
        selectedNpcIndex =
            when {
                curSel == null -> null
                curSel == index -> null
                curSel > index -> curSel - 1
                else -> curSel
            }
    }

    fun toggleOrAppendNpcWaypoint(
        npcIndex: Int,
        col: Int,
        row: Int,
    ) {
        val gp = document.gameplay ?: return
        if (npcIndex !in gp.npcs.indices || !document.grid.isInBounds(col, row)) return
        val npc = gp.npcs[npcIndex]
        val coord = GridCoord(col, row)
        if (coord in npc.patrolWaypoints) {
            val nextWps = npc.patrolWaypoints.filterNot { it == coord }
            updateNpc(npcIndex, npc.copy(patrolWaypoints = nextWps))
            statusMessage = "Removed waypoint ($col, $row) from '${npc.id}'"
            return
        }
        if (npc.patrolWaypoints.size >= GameplayBinarySerializer.MAX_WAYPOINTS) {
            statusMessage =
                "NPC '${npc.id}' already has maximum ${GameplayBinarySerializer.MAX_WAYPOINTS} patrol waypoints"
            return
        }
        val nextWps = npc.patrolWaypoints + coord
        val nextAi = if (npc.aiType == NpcAiType.GUARD) NpcAiType.PATROL else npc.aiType
        updateNpc(npcIndex, npc.copy(aiType = nextAi, patrolWaypoints = nextWps))
        statusMessage = "Added waypoint #${nextWps.size} ($col, $row) to '${npc.id}'"
    }

    fun removeNpcWaypoint(
        npcIndex: Int,
        waypointIndex: Int,
    ) {
        val gp = document.gameplay ?: return
        val npc = gp.npcs.getOrNull(npcIndex) ?: return
        if (waypointIndex !in npc.patrolWaypoints.indices) return
        val nextWps = npc.patrolWaypoints.toMutableList().also { it.removeAt(waypointIndex) }
        updateNpc(npcIndex, npc.copy(patrolWaypoints = nextWps))
        statusMessage = "Removed waypoint #${waypointIndex + 1} from '${npc.id}'"
    }

    fun clearNpcWaypoints(npcIndex: Int) {
        val gp = document.gameplay ?: return
        val npc = gp.npcs.getOrNull(npcIndex) ?: return
        if (npc.patrolWaypoints.isEmpty()) return
        updateNpc(npcIndex, npc.copy(patrolWaypoints = emptyList()))
        statusMessage = "Cleared patrol waypoints for '${npc.id}'"
    }

    private fun uniqueInteractableId(
        existing: List<InteractableSpawn>,
        requested: String?,
        kind: InteractableKind,
    ): String {
        val used = existing.map { it.id.lowercase() }.toSet()
        val baseCandidate =
            requested
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { SourceTexture.sanitizeToIdentifier(it).lowercase() }
        if (baseCandidate != null && baseCandidate !in used) {
            return baseCandidate
        }
        val prefix = baseCandidate ?: kind.name.lowercase()
        var suffix = existing.size
        while ("${prefix}_$suffix".lowercase() in used) {
            suffix++
        }
        return "${prefix}_$suffix"
    }

    fun addInteractable(
        col: Int,
        row: Int,
        kind: InteractableKind = pendingInteractableKind,
        id: String? = null,
    ): Int? {
        if (!document.grid.isInBounds(col, row)) return null
        val curCount = document.gameplay?.interactables?.size ?: 0
        if (curCount >= 255) {
            reportError("Maximum of 255 interactables reached.")
            return null
        }
        showGameplayOverlay = true
        var addedIndex: Int? = null
        mutateDocument(status = "Added ${kind.name.lowercase()} at ($col, $row)") { doc ->
            val gp = doc.gameplay ?: createDefaultGameplayLayer(doc.grid)
            val resolvedId = uniqueInteractableId(gp.interactables, id, kind)
            val newInter =
                InteractableSpawn(
                    id = resolvedId,
                    col = col,
                    row = row,
                    kind = kind,
                )
            val nextList = gp.interactables + newInter
            addedIndex = nextList.lastIndex
            doc.copy(gameplay = gp.copy(interactables = nextList))
        }
        if (addedIndex != null) {
            selectedInteractableIndex = addedIndex
            selectedNpcIndex = null
            selectedCells = setOf(col to row)
        }
        return addedIndex
    }

    fun updateInteractable(
        index: Int,
        updated: InteractableSpawn,
    ) {
        val gp = document.gameplay ?: return
        if (index !in gp.interactables.indices) return
        val cols = document.grid.cols
        val rows = document.grid.rows
        val sanitized =
            updated.copy(
                id = updated.id.trim().ifBlank { gp.interactables[index].id },
                col = updated.col.coerceIn(0, cols - 1),
                row = updated.row.coerceIn(0, rows - 1),
                closedSpriteId = updated.closedSpriteId.coerceIn(0, 255),
                openSpriteId = updated.openSpriteId.coerceIn(0, 255),
                rewardValue = updated.rewardValue.coerceIn(0, 255),
                messageId = updated.messageId.coerceIn(0, 255),
            )
        mutateDocument(status = "Updated ${sanitized.kind.name.lowercase()} '${sanitized.id}'") { doc ->
            val cur = doc.gameplay ?: return@mutateDocument doc
            if (index !in cur.interactables.indices) return@mutateDocument doc
            val list = cur.interactables.toMutableList()
            list[index] = sanitized
            doc.copy(gameplay = cur.copy(interactables = list))
        }
    }

    fun removeInteractable(index: Int) {
        val gp = document.gameplay ?: return
        if (index !in gp.interactables.indices) return
        val removedId = gp.interactables[index].id
        mutateDocument(status = "Removed interactable '$removedId'") { doc ->
            val cur = doc.gameplay ?: return@mutateDocument doc
            if (index !in cur.interactables.indices) return@mutateDocument doc
            val list = cur.interactables.toMutableList()
            list.removeAt(index)
            doc.copy(gameplay = cur.copy(interactables = list))
        }
        val curSel = selectedInteractableIndex
        selectedInteractableIndex =
            when {
                curSel == null -> null
                curSel == index -> null
                curSel > index -> curSel - 1
                else -> curSel
            }
    }

    fun handleGameplayCellClick(
        col: Int,
        row: Int,
    ) {
        if (!document.grid.isInBounds(col, row)) return
        when (gameplayTool) {
            GameplayTool.SELECT_ENTITY -> {
                selectSingleCell(col, row)
            }
            GameplayTool.PAINT_WALKABILITY -> {
                if (document.gameplay == null) {
                    enableGameplayLayer()
                }
                setCellWalkabilityOverride(
                    col = col,
                    row = row,
                    walkable = paintWalkable,
                    moveCost = if (paintWalkable) paintMoveCost else 0,
                    flags = paintWalkFlags,
                )
                selectedCells = setOf(col to row)
            }
            GameplayTool.PLACE_PLAYER -> {
                if (document.gameplay == null) {
                    enableGameplayLayer()
                }
                val curSpawn = document.gameplay?.playerSpawn
                val nextFacing =
                    if (curSpawn != null && curSpawn.col == col && curSpawn.row == row) {
                        FacingDirection.fromId((curSpawn.facing.id + 1) and 3)
                    } else {
                        curSpawn?.facing ?: FacingDirection.SOUTH
                    }
                setPlayerSpawn(col, row, nextFacing)
                selectedNpcIndex = null
                selectedInteractableIndex = null
                selectedCells = setOf(col to row)
            }
            GameplayTool.PLACE_NPC -> {
                if (document.gameplay == null) {
                    enableGameplayLayer()
                }
                val existingNpcIdx =
                    document.gameplay
                        ?.npcs
                        ?.indexOfFirst { it.col == col && it.row == row }
                        ?.takeIf { it >= 0 }
                if (existingNpcIdx != null) {
                    selectNpc(existingNpcIdx)
                } else {
                    addNpc(col, row)
                }
            }
            GameplayTool.EDIT_WAYPOINTS -> {
                val activeNpcIdx = selectedNpcIndex
                val gp = document.gameplay
                if (activeNpcIdx != null && gp != null && activeNpcIdx in gp.npcs.indices) {
                    toggleOrAppendNpcWaypoint(activeNpcIdx, col, row)
                } else {
                    val npcAtCell = gp?.npcs?.indexOfFirst { it.col == col && it.row == row }?.takeIf { it >= 0 }
                    if (npcAtCell != null) {
                        selectNpc(npcAtCell)
                    } else {
                        statusMessage = "Select an NPC first before placing patrol waypoints"
                    }
                }
            }
            GameplayTool.PLACE_INTERACTABLE -> {
                if (document.gameplay == null) {
                    enableGameplayLayer()
                }
                val existingInterIdx =
                    document.gameplay
                        ?.interactables
                        ?.indexOfFirst { it.col == col && it.row == row }
                        ?.takeIf { it >= 0 }
                if (existingInterIdx != null) {
                    selectInteractable(existingInterIdx)
                } else {
                    addInteractable(col, row, kind = pendingInteractableKind)
                }
            }
        }
    }

    fun findNextFreeWalkableCell(): Pair<Int, Int> {
        val grid = document.grid
        val gp = document.gameplay
        val occupied = mutableSetOf<Pair<Int, Int>>()
        if (gp != null) {
            occupied.add(gp.playerSpawn.col to gp.playerSpawn.row)
            gp.npcs.forEach { occupied.add(it.col to it.row) }
            gp.interactables.forEach { occupied.add(it.col to it.row) }
        }
        val primary = selectedCells.firstOrNull()
        if (primary != null &&
            grid.isInBounds(primary.first, primary.second) &&
            primary !in occupied &&
            effectiveWalkabilityAt(primary.first, primary.second).walkable
        ) {
            return primary
        }
        for (r in 0 until grid.rows) {
            for (c in 0 until grid.cols) {
                val pos = c to r
                if (pos !in occupied && effectiveWalkabilityAt(c, r).walkable) {
                    return pos
                }
            }
        }
        return primary ?: (0 to 0)
    }

    // --- Terrain Cell Authoring Operations ---

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
        if (delta == 0) return
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
        assignTextureToSlot(activeTextureSlot, texName)
    }

    fun assignTextureToSlot(
        slot: ActiveTextureSlot,
        texName: String?,
    ) {
        activeTextureSlot = slot
        brushCell =
            when (slot) {
                ActiveTextureSlot.TOP -> brushCell.copy(topTex = texName)
                ActiveTextureSlot.SKIRT -> brushCell.copy(skirtTex = texName)
                ActiveTextureSlot.PANEL -> brushCell.copy(panelTex = texName)
            }
        if (activeTool == LevelTool.SELECT && selectedCells.isNotEmpty()) {
            updateSelectedCells("Assigned ${slot.name.lowercase()} texture '${texName ?: "none"}'") { cell ->
                when (slot) {
                    ActiveTextureSlot.TOP -> cell.copy(topTex = texName)
                    ActiveTextureSlot.SKIRT -> cell.copy(skirtTex = texName)
                    ActiveTextureSlot.PANEL -> cell.copy(panelTex = texName)
                }
            }
        }
    }

    fun expandTextureSlot(slot: ActiveTextureSlot) {
        activeTextureSlot = slot
        expandedTextureSlots = expandedTextureSlots + slot
    }

    fun toggleTextureSlotExpanded(slot: ActiveTextureSlot) {
        activeTextureSlot = slot
        expandedTextureSlots =
            if (slot in expandedTextureSlots) {
                expandedTextureSlots - slot
            } else {
                expandedTextureSlots + slot
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
            val resizedGrid = doc.grid.resized(c, r, fill)
            val resizedGameplay =
                doc.gameplay?.let { gp ->
                    gp.copy(
                        walkabilityOverrides = gp.walkabilityOverrides.filter { resizedGrid.isInBounds(it.col, it.row) },
                    )
                }
            doc.copy(grid = resizedGrid, gameplay = resizedGameplay)
        }
        clampSelectionToBounds()
    }

    fun promptLinkAtlas() {
        platform.requestOpenFile(
            title = "Link Texture Atlas",
            initialDir = studio?.lastImportDirectory ?: currentLevelFile?.parentFile?.absolutePath,
            allowedExtensions = setOf(SFX_PROJ_EXTENSION, SFX_ATLAS_EXTENSION),
        ) { picked ->
            if (picked != null) {
                linkAtlasFile(picked)
            }
        }
    }

    fun linkAtlasFile(atlasFile: File) {
        val resolvedDoc = resolveAtlasDocument(explicitAtlasFile = atlasFile)
        if (resolvedDoc == null) {
            reportError("Atlas file '${atlasFile.path}' does not exist or could not be loaded.")
            return
        }
        unsavedAtlasFile = atlasFile
        invalidateDiskAtlasCache()
        val relPath =
            currentLevelFile?.let { computeRelativePath(it, atlasFile) }
                ?: atlasFile.path
        mutateDocument(status = "Linked atlas '${atlasFile.name}'") { doc ->
            val firstTex =
                resolvedDoc
                    .descriptorTable()
                    .getOrNull()
                    ?.entries
                    ?.firstOrNull()
                    ?.name
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

    // --- Camera & Grid Viewport Navigation ---

    fun rotateCameraClockwise(steps: Int = 1) {
        cameraRevealTarget = null
        cameraYaw = (((cameraYaw + steps) % 120) + 120) % 120
        syncCamToScreen()
    }

    fun rotateCameraCounterClockwise(steps: Int = 1) {
        cameraRevealTarget = null
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
        cameraRevealTarget = null
        cameraPanX = (cameraPanX + deltaPanX).coerceIn(-MAX_PAN_PIXELS, MAX_PAN_PIXELS)
        cameraPanY = (cameraPanY + deltaPanY).coerceIn(-MAX_PAN_PIXELS, MAX_PAN_PIXELS)
        syncScreenToCam()
    }

    fun resetCamera() {
        cameraRevealTarget = null
        cameraYaw = 15
        cameraPanX = 0
        cameraPanY = 0
        camXQ6 = 0
        camZQ6 = 0
    }

    fun panGridBy(delta: Offset) {
        gridPanOffset =
            Offset(
                x = (gridPanOffset.x + delta.x).coerceIn(-MAX_GRID_PAN_PX, MAX_GRID_PAN_PX),
                y = (gridPanOffset.y + delta.y).coerceIn(-MAX_GRID_PAN_PX, MAX_GRID_PAN_PX),
            )
    }

    fun resetGridView() {
        gridZoom = 0f
        gridPanOffset = Offset.Zero
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
        val gp = document.gameplay
        if (gp == null) {
            selectedNpcIndex = null
            selectedInteractableIndex = null
        } else {
            val npcSel = selectedNpcIndex
            if (npcSel != null && npcSel !in gp.npcs.indices) {
                selectedNpcIndex = gp.npcs.indices.lastOrNull()
            }
            val interSel = selectedInteractableIndex
            if (interSel != null && interSel !in gp.interactables.indices) {
                selectedInteractableIndex = gp.interactables.indices.lastOrNull()
            }
        }
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
            strokeSnapshot = null
            document = loaded
            savedDocument = loaded
            historyRevision++
            showGameplayOverlay = loaded.gameplay != null
            activeDiagnosticIndex = 0
            brushCell = loaded.grid.cells.firstOrNull() ?: MapCell()
            selectedCells = setOf(0 to 0)
            syncGameplayEntitySelectionForCell(0, 0)
            ensureCellVisibleInPreview(0, 0, animate = false)
            hasUnsavedChanges = false
            lastError = null
            statusMessage = "Opened level '${file.name}'"
            true
        } catch (e: Exception) {
            reportError("Failed to open level '${file.name}': ${e.message}")
            false
        }

    fun promptImportCa65Map() {
        platform.requestOpenFile(
            title = "Import Legacy ca65 Map (.i)",
            initialDir = studio?.lastImportDirectory ?: currentLevelFile?.parentFile?.absolutePath,
            allowedExtensions = setOf("i"),
        ) { picked ->
            if (picked != null) {
                picked.parentFile?.absolutePath?.let { studio?.updateLastImportDirectory(it) }
                importCa65Map(picked)
            }
        }
    }

    fun importCa65Map(
        ca65File: File,
        explicitAtlasFile: File? = null,
    ): Boolean =
        try {
            val atlasFile = explicitAtlasFile ?: resolveAtlasFile()
            val atlasDoc = resolveAtlasDocument(explicitAtlasFile = atlasFile)
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
                    atlas = atlasDoc?.atlas,
                    levelName = document.name.ifBlank { ca65File.nameWithoutExtension },
                )
            mutateDocument(status = "Imported '${ca65File.name}' (${imported.grid.cols}x${imported.grid.rows})") {
                imported
            }
            brushCell = imported.grid.cells.firstOrNull() ?: MapCell()
            selectedCells = setOf(0 to 0)
            syncGameplayEntitySelectionForCell(0, 0)
            ensureCellVisibleInPreview(0, 0, animate = false)
            lastError = null
            true
        } catch (e: Exception) {
            reportError("Failed to import '${ca65File.name}': ${e.message}")
            false
        }

    fun requestSave(): Boolean = studio?.saveSession(this) ?: save()

    fun requestSaveAction() {
        if (studio != null) {
            studio.requestSaveSession(this)
        } else {
            save()
        }
    }

    override fun save(): Boolean {
        val target = currentLevelFile ?: return false
        return saveAs(target)
    }

    override fun saveAs(file: File): Boolean =
        try {
            val resolvedAtlasFile = resolveAtlasFile()
            val atlasDoc = resolveAtlasDocument()
            val updatedDoc =
                if (resolvedAtlasFile != null) {
                    document.copy(
                        name = file.nameWithoutExtension,
                        atlasPath = computeRelativePath(file, resolvedAtlasFile),
                    )
                } else {
                    document.copy(name = file.nameWithoutExtension)
                }

            if (atlasDoc == null) {
                LevelDocumentService.saveAuthoringJson(file, updatedDoc)
                document = updatedDoc
                savedDocument = updatedDoc
                currentLevelFile = file
                hasUnsavedChanges = false
                reportError(
                    "Saved '${file.name}', but binary .map export was skipped because no valid Texture Atlas is linked.",
                )
                statusMessage = "Saved '${file.name}' (.map skipped — no linked atlas)"
                return true
            }

            val validation = LevelValidator.validate(document = updatedDoc, atlasDocument = atlasDoc)
            if (!validation.valid) {
                val errSummary =
                    validation.errors
                        .take(3)
                        .joinToString("\n") { "- [${it.code}] ${it.message}" }
                // Still save authoring .sfxlevel JSON so work is never lost, while reporting why .map export was skipped
                LevelDocumentService.saveAuthoringJson(file, updatedDoc)
                document = updatedDoc
                savedDocument = updatedDoc
                currentLevelFile = file
                hasUnsavedChanges = false
                reportError("Saved '${file.name}', but binary .map export was skipped due to validation errors:\n$errSummary")
                statusMessage = "Saved '${file.name}' (with ${validation.errors.size} error(s))"
                true
            } else {
                LevelDocumentService.saveAndExport(
                    levelFile = file,
                    document = updatedDoc,
                    atlasDoc = atlasDoc,
                    previewYaw = cameraYaw,
                )
                document = updatedDoc
                savedDocument = updatedDoc
                currentLevelFile = file
                hasUnsavedChanges = false
                lastError = null
                val warnSuffix =
                    if (validation.warnings.isNotEmpty()) {
                        " (${validation.warnings.size} warning(s))"
                    } else {
                        ""
                    }
                val artifactsLabel =
                    if (updatedDoc.gameplay != null) {
                        ".map, .gmap, .i, _gameplay.i, _preview.png"
                    } else {
                        ".map, .i, _preview.png"
                    }
                statusMessage = "Saved and packed '${file.name}' ($artifactsLabel)$warnSuffix"
                true
            }
        } catch (e: Exception) {
            reportError("Failed to save '${file.name}': ${e.message}")
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
        private const val MAX_GRID_PAN_PX = 1600f
        private const val MIN_VISIBLE_PIXELS = 48
        private const val GOOD_VISIBLE_PIXELS = 120

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
