package dev.secondsun.sfxstudio.desktop.level

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Divider
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.atlas.TextureDescriptorInfo
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.DiagnosticSeverity
import dev.secondsun.sfxstudio.core.level.IsoRenderResult
import dev.secondsun.sfxstudio.core.level.IsoSoftwareRenderer
import dev.secondsun.sfxstudio.core.level.LevelValidationResult
import dev.secondsun.sfxstudio.core.level.MapGrid
import dev.secondsun.sfxstudio.core.level.SFX_LEVEL_EXTENSION
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import dev.secondsun.sfxstudio.desktop.ui.TextureComposeRenderer
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.roundToInt

@Composable
fun LevelEditorWorkspace(state: LevelEditorState) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(state.sessionId) {
        focusRequester.requestFocus()
    }

    val resolvedPair = state.resolveAtlasAndPalette()
    val atlas = resolvedPair?.first
    val palette = resolvedPair?.second
    val descriptors =
        remember(atlas) {
            try {
                atlas?.resolvedDescriptors().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }
    val validation =
        remember(state.document, atlas) {
            state.currentValidation()
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFF181A1F))
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.isCtrlPressed && event.key == Key.Z && event.isShiftPressed -> {
                            state.redo()
                            true
                        }
                        event.isCtrlPressed && event.key == Key.Z -> {
                            state.undo()
                            true
                        }
                        event.isCtrlPressed && event.key == Key.Y -> {
                            state.redo()
                            true
                        }
                        !event.isCtrlPressed && event.key == Key.V -> {
                            state.activeTool = LevelTool.SELECT
                            true
                        }
                        !event.isCtrlPressed && event.key == Key.B -> {
                            state.activeTool = LevelTool.BRUSH
                            true
                        }
                        !event.isCtrlPressed && event.key == Key.I -> {
                            state.activeTool = LevelTool.EYEDROPPER
                            true
                        }
                        !event.isCtrlPressed && event.key == Key.R -> {
                            state.rotateSelectedClockwise()
                            true
                        }
                        !event.isCtrlPressed && event.key == Key.LeftBracket -> {
                            state.stepSelectedHeight(-1)
                            true
                        }
                        !event.isCtrlPressed && event.key == Key.RightBracket -> {
                            state.stepSelectedHeight(1)
                            true
                        }
                        !event.isCtrlPressed && event.key == Key.Q -> {
                            state.rotateCamera90CounterClockwise()
                            true
                        }
                        !event.isCtrlPressed && event.key == Key.E -> {
                            state.rotateCamera90Clockwise()
                            true
                        }
                        else -> false
                    }
                },
    ) {
        LevelTopToolbar(state)
        DiagnosticsBanner(state, validation)

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LevelLeftPaletteDock(
                state = state,
                atlas = atlas,
                palette = palette,
                descriptors = descriptors,
            )

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF121418)),
            ) {
                when (state.centerViewMode) {
                    LevelCenterViewMode.SPLIT -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1.15f).fillMaxHeight()) {
                                Iso3DViewport(state, atlas, palette)
                            }
                            Box(
                                modifier =
                                    Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(Color(0xFF2A2D34)),
                            )
                            Box(modifier = Modifier.weight(0.85f).fillMaxHeight()) {
                                TopDownGridViewport(state, atlas, palette, validation)
                            }
                        }
                    }
                    LevelCenterViewMode.ISO_3D -> Iso3DViewport(state, atlas, palette)
                    LevelCenterViewMode.TOP_DOWN_2D -> TopDownGridViewport(state, atlas, palette, validation)
                }
            }

            LevelRightInspectorDock(
                state = state,
                descriptors = descriptors,
                validation = validation,
            )
        }
    }

    state.errorMessage?.let { err ->
        AlertDialog(
            onDismissRequest = { state.errorMessage = null },
            title = { Text("Level Editor Notice", color = Color.White) },
            text = { Text(err, color = Color(0xFFFCA5A5), fontSize = 12.sp) },
            confirmButton = {
                Button(
                    onClick = { state.errorMessage = null },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3B82F6)),
                ) {
                    Text("OK", color = Color.White)
                }
            },
            backgroundColor = Color(0xFF22242B),
            shape = RoundedCornerShape(8.dp),
        )
    }
}

@Composable
private fun LevelTopToolbar(state: LevelEditorState) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(Color(0xFF1E2026))
                .border(1.dp, Color(0xFF2C2F37))
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Tool buttons
        ToolbarChip("Select [V]", state.activeTool == LevelTool.SELECT) {
            state.activeTool = LevelTool.SELECT
        }
        ToolbarChip("Brush [B]", state.activeTool == LevelTool.BRUSH) {
            state.activeTool = LevelTool.BRUSH
        }
        ToolbarChip("Pick [I]", state.activeTool == LevelTool.EYEDROPPER) {
            state.activeTool = LevelTool.EYEDROPPER
        }

        ToolbarDivider()

        ToolbarActionButton("Undo", enabled = state.canUndo) { state.undo() }
        ToolbarActionButton("Redo", enabled = state.canRedo) { state.redo() }

        ToolbarDivider()

        ToolbarActionButton("-1h ([)") { state.stepSelectedHeight(-1) }
        ToolbarActionButton("+1h (])") { state.stepSelectedHeight(1) }
        ToolbarActionButton("Rot 90° [R]") { state.rotateSelectedClockwise() }

        ToolbarDivider()

        ToolbarChip("Split", state.centerViewMode == LevelCenterViewMode.SPLIT) {
            state.centerViewMode = LevelCenterViewMode.SPLIT
        }
        ToolbarChip("3D Iso", state.centerViewMode == LevelCenterViewMode.ISO_3D) {
            state.centerViewMode = LevelCenterViewMode.ISO_3D
        }
        ToolbarChip("2D Grid", state.centerViewMode == LevelCenterViewMode.TOP_DOWN_2D) {
            state.centerViewMode = LevelCenterViewMode.TOP_DOWN_2D
        }

        ToolbarDivider()

        ToolbarActionButton("-90° [Q]") { state.rotateCamera90CounterClockwise() }
        Text(
            text = "Yaw ${state.cameraYaw * 3}°",
            color = Color(0xFFCBD5E1),
            fontSize = 11.sp,
            modifier = Modifier.width(56.dp),
        )
        Slider(
            value = state.cameraYaw.toFloat(),
            onValueChange = { state.cameraYaw = it.roundToInt().coerceIn(0, 119) },
            valueRange = 0f..119f,
            colors =
                SliderDefaults.colors(
                    thumbColor = Color(0xFF38BDF8),
                    activeTrackColor = Color(0xFF3B82F6),
                ),
            modifier = Modifier.width(100.dp),
        )
        ToolbarActionButton("+90° [E]") { state.rotateCamera90Clockwise() }

        Spacer(modifier = Modifier.weight(1f))

        ToolbarActionButton("Import .i...") {
            val dialog = FileDialog(null as Frame?, "Import Legacy ca65 Map (.i)", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name -> name.endsWith(".i", ignoreCase = true) }
            dialog.isVisible = true
            val picked = dialog.file?.let { File(dialog.directory, it) }
            if (picked != null) {
                state.importCa65Map(picked)
            }
        }

        Button(
            onClick = {
                val existing = state.currentLevelFile
                if (existing != null) {
                    state.saveAs(existing)
                } else {
                    val dialog = FileDialog(null as Frame?, "Save & Pack Level (.sfxlevel)", FileDialog.SAVE)
                    dialog.file = "${state.displayTitle}.$SFX_LEVEL_EXTENSION"
                    dialog.isVisible = true
                    val picked = dialog.file?.let { File(dialog.directory, it) }
                    if (picked != null) {
                        state.saveAs(picked)
                    }
                }
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF059669)),
            modifier = Modifier.height(28.dp),
        ) {
            Text("Save & Pack .map", color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DiagnosticsBanner(
    state: LevelEditorState,
    validation: LevelValidationResult,
) {
    if (validation.errors.isEmpty() && validation.warnings.isEmpty()) return
    val top = validation.errors.firstOrNull() ?: validation.warnings.first()
    val isErr = validation.errors.isNotEmpty()
    val bg = if (isErr) Color(0xFF3B181E) else Color(0xFF362B14)
    val border = if (isErr) Color(0xFFEF4444) else Color(0xFFF59E0B)
    val textCol = if (isErr) Color(0xFFFCA5A5) else Color(0xFFFDE68A)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(bg)
                .border(1.dp, border)
                .clickable {
                    val dc = top.col
                    val dr = top.row
                    if (dc != null && dr != null) {
                        state.selectSingleCell(dc, dr)
                    }
                }.padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "[${top.code}] ${top.message}",
            color = textCol,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${validation.errors.size} error(s), ${validation.warnings.size} warning(s)",
            color = textCol,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LevelLeftPaletteDock(
    state: LevelEditorState,
    atlas: TextureAtlas?,
    palette: SnesPalette?,
    descriptors: List<TextureDescriptorInfo>,
) {
    Column(
        modifier =
            Modifier
                .width(248.dp)
                .fillMaxHeight()
                .background(Color(0xFF1D1F26))
                .border(1.dp, Color(0xFF2B2E36))
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Linked Atlas card
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Shared Atlas", color = Color(0xFF94A3B8), fontSize = 10.sp)
                val atlasLabel =
                    state.resolveAtlasFile()?.name
                        ?: state.document.atlasPath.ifBlank { "(Unlinked)" }
                Text(atlasLabel, color = Color(0xFF67E8F9), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            ToolbarActionButton("Link...") {
                val dialog = FileDialog(null as Frame?, "Link Texture Atlas", FileDialog.LOAD)
                dialog.setFilenameFilter { _, name ->
                    name.endsWith(".sfxproj", ignoreCase = true) ||
                        name.endsWith(".sfxatlas", ignoreCase = true)
                }
                dialog.isVisible = true
                val picked = dialog.file?.let { File(dialog.directory, it) }
                if (picked != null) {
                    state.linkAtlasFile(picked)
                }
            }
        }

        Divider(color = Color(0xFF2C2F37))

        // Brush Layer Masks
        Text("Brush Layer Mask", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolbarChip("H", state.brushMask.applyHeight) {
                state.brushMask = state.brushMask.copy(applyHeight = !state.brushMask.applyHeight)
            }
            ToolbarChip("Shape", state.brushMask.applyArchetypeAndRotation) {
                state.brushMask =
                    state.brushMask.copy(
                        applyArchetypeAndRotation = !state.brushMask.applyArchetypeAndRotation,
                    )
            }
            ToolbarChip("Top", state.brushMask.applyTopTex) {
                state.brushMask = state.brushMask.copy(applyTopTex = !state.brushMask.applyTopTex)
            }
            ToolbarChip("Skirt", state.brushMask.applySkirtTex) {
                state.brushMask = state.brushMask.copy(applySkirtTex = !state.brushMask.applySkirtTex)
            }
            ToolbarChip("Panel", state.brushMask.applyPanelTexAndFlags) {
                state.brushMask = state.brushMask.copy(applyPanelTexAndFlags = !state.brushMask.applyPanelTexAndFlags)
            }
        }

        Divider(color = Color(0xFF2C2F37))

        // Active Texture Target Slot
        Text("Assign Texture Slot", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolbarChip("Top", state.activeTextureSlot == ActiveTextureSlot.TOP) {
                state.activeTextureSlot = ActiveTextureSlot.TOP
            }
            ToolbarChip("Skirt", state.activeTextureSlot == ActiveTextureSlot.SKIRT) {
                state.activeTextureSlot = ActiveTextureSlot.SKIRT
            }
            ToolbarChip("Panel", state.activeTextureSlot == ActiveTextureSlot.PANEL) {
                state.activeTextureSlot = ActiveTextureSlot.PANEL
            }
            if (state.activeTextureSlot == ActiveTextureSlot.PANEL) {
                ToolbarActionButton("Clear") { state.assignTextureToActiveSlot(null) }
            }
        }

        if (atlas == null || palette == null || descriptors.isEmpty()) {
            Text(
                text = "Link an Atlas Document (.sfxproj) to browse textures.",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
            )
        } else {
            val activeSelectedName =
                when (state.activeTextureSlot) {
                    ActiveTextureSlot.TOP -> state.primarySelectedCell()?.topTex ?: state.brushCell.topTex
                    ActiveTextureSlot.SKIRT -> state.primarySelectedCell()?.skirtTex ?: state.brushCell.skirtTex
                    ActiveTextureSlot.PANEL -> state.primarySelectedCell()?.panelTex ?: state.brushCell.panelTex
                }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((descIdx, desc) in descriptors.withIndex()) {
                    val isSelected = desc.name == activeSelectedName
                    val previewBmp =
                        remember(atlas, palette, desc) {
                            state
                                .extractDescriptorPreview(atlas, desc)
                                ?.let { TextureComposeRenderer.renderToImageBitmap(it, palette) }
                        }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) Color(0xFF25354D) else Color(0xFF16181D))
                                .border(
                                    1.dp,
                                    if (isSelected) Color(0xFF3B82F6) else Color(0xFF2A2D34),
                                    RoundedCornerShape(4.dp),
                                ).clickable { state.assignTextureToActiveSlot(desc.name) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (previewBmp != null) {
                            Image(
                                bitmap = previewBmp,
                                contentDescription = desc.name,
                                filterQuality = FilterQuality.None,
                                modifier =
                                    Modifier
                                        .size(24.dp)
                                        .background(Color(0xFF0B0C0E))
                                        .border(1.dp, Color(0xFF334155)),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(desc.name, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text(
                                "${desc.width}x${desc.height} (#$descIdx)",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }

        Divider(color = Color(0xFF2C2F37))

        // Shape Archetypes
        Text("Shape Archetypes (12)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        val activeArch =
            if (state.activeTool == LevelTool.SELECT) {
                state.primarySelectedCell()?.archetype ?: state.brushCell.archetype
            } else {
                state.brushCell.archetype
            }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (arch in ShapeArchetype.entries) {
                val selected = arch == activeArch
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (selected) Color(0xFF25354D) else Color(0xFF16181D))
                            .border(
                                1.dp,
                                if (selected) Color(0xFF3B82F6) else Color(0xFF2A2D34),
                                RoundedCornerShape(4.dp),
                            ).clickable { state.setSelectedArchetype(arch) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${arch.id}. ${arch.displayName}", color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun Iso3DViewport(
    state: LevelEditorState,
    atlas: TextureAtlas?,
    palette: SnesPalette?,
) {
    if (atlas == null || palette == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "3D Isometric Viewport requires a linked Atlas Document",
                color = Color(0xFF64748B),
                fontSize = 13.sp,
            )
        }
        return
    }

    val renderResult: IsoRenderResult =
        remember(state.document.grid, atlas, palette, state.cameraYaw) {
            IsoSoftwareRenderer.render(
                grid = state.document.grid,
                atlas = atlas,
                palette = palette,
                yawIndex = state.cameraYaw,
            )
        }
    val composeBitmap =
        remember(renderResult) {
            renderResult.image.toComposeImageBitmap()
        }

    var viewportSize by remember { mutableStateOf(IntSize(1, 1)) }

    Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(renderResult, state.activeTool, state.document.grid) {
                        detectTapGestures { pos ->
                            val (imgX, imgY) =
                                mapCanvasToRenderPixel(
                                    pos = pos,
                                    canvasW = size.width,
                                    canvasH = size.height,
                                    imgW = renderResult.width,
                                    imgH = renderResult.height,
                                    zoom = state.isoZoom,
                                )
                            val idx = renderResult.pickCellIndex(imgX, imgY)
                            if (idx >= 0) {
                                val col = idx % state.document.grid.cols
                                val row = idx / state.document.grid.cols
                                state.handleCellInteraction(col, row, isDragContinue = false)
                            }
                        }
                    }.pointerInput(renderResult, state.activeTool, state.document.grid) {
                        var dragStartCoord: Pair<Int, Int>? = null
                        detectDragGestures(
                            onDragStart = { pos ->
                                val (imgX, imgY) =
                                    mapCanvasToRenderPixel(
                                        pos = pos,
                                        canvasW = size.width,
                                        canvasH = size.height,
                                        imgW = renderResult.width,
                                        imgH = renderResult.height,
                                        zoom = state.isoZoom,
                                    )
                                val idx = renderResult.pickCellIndex(imgX, imgY)
                                if (idx >= 0) {
                                    val c = idx % state.document.grid.cols
                                    val r = idx / state.document.grid.cols
                                    dragStartCoord = c to r
                                    if (state.activeTool == LevelTool.BRUSH) {
                                        state.beginStroke()
                                        state.paintCell(c, r, recordImmediateUndo = false)
                                    } else if (state.activeTool == LevelTool.SELECT) {
                                        state.selectSingleCell(c, r)
                                    }
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val (imgX, imgY) =
                                    mapCanvasToRenderPixel(
                                        pos = change.position,
                                        canvasW = size.width,
                                        canvasH = size.height,
                                        imgW = renderResult.width,
                                        imgH = renderResult.height,
                                        zoom = state.isoZoom,
                                    )
                                val idx = renderResult.pickCellIndex(imgX, imgY)
                                if (idx >= 0) {
                                    val c = idx % state.document.grid.cols
                                    val r = idx / state.document.grid.cols
                                    if (state.activeTool == LevelTool.BRUSH) {
                                        state.paintCell(c, r, recordImmediateUndo = false)
                                    } else if (state.activeTool == LevelTool.SELECT) {
                                        val start = dragStartCoord ?: (c to r)
                                        state.selectRect(start.first, start.second, c, r)
                                    }
                                }
                            },
                            onDragEnd = {
                                dragStartCoord = null
                                state.endStroke()
                            },
                            onDragCancel = {
                                dragStartCoord = null
                                state.endStroke()
                            },
                        )
                    },
        ) {
            viewportSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val fitScale =
                minOf(
                    size.width / renderResult.width.toFloat(),
                    size.height / renderResult.height.toFloat(),
                ).coerceAtLeast(0.5f)
            val drawW = (renderResult.width * fitScale).roundToInt()
            val drawH = (renderResult.height * fitScale).roundToInt()
            val offX = ((size.width - drawW) / 2f).roundToInt()
            val offY = ((size.height - drawH) / 2f).roundToInt()

            drawRect(
                color = Color(0xFF0A0C10),
                topLeft = Offset(offX.toFloat(), offY.toFloat()),
                size = Size(drawW.toFloat(), drawH.toFloat()),
            )

            drawImage(
                image = composeBitmap,
                dstOffset = IntOffset(offX, offY),
                dstSize = IntSize(drawW, drawH),
                filterQuality = FilterQuality.None,
            )

            // Draw wireframe selection floor polygons
            val cols = state.document.grid.cols
            for ((selC, selR) in state.selectedCells) {
                val cellIdx = selR * cols + selC
                if (cellIdx in renderResult.cellFloorPolygons.indices) {
                    val poly = renderResult.cellFloorPolygons[cellIdx]
                    if (poly.size >= 4) {
                        val path =
                            Path().apply {
                                moveTo(offX + poly[0].x * fitScale, offY + poly[0].y * fitScale)
                                for (k in 1 until poly.size) {
                                    lineTo(offX + poly[k].x * fitScale, offY + poly[k].y * fitScale)
                                }
                                close()
                            }
                        drawPath(path = path, color = Color(0xFF38BDF8), style = Stroke(width = 2f))
                    }
                }
            }
        }

        Text(
            text = "3D Software Preview (256x160 • Yaw ${state.cameraYaw})",
            color = Color(0xFF94A3B8),
            fontSize = 10.sp,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .background(Color(0xAA111215), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun mapCanvasToRenderPixel(
    pos: Offset,
    canvasW: Int,
    canvasH: Int,
    imgW: Int,
    imgH: Int,
    @Suppress("UNUSED_PARAMETER") zoom: Float,
): Pair<Int, Int> {
    val fitScale = minOf(canvasW.toFloat() / imgW, canvasH.toFloat() / imgH).coerceAtLeast(0.5f)
    val drawW = imgW * fitScale
    val drawH = imgH * fitScale
    val offX = (canvasW - drawW) / 2f
    val offY = (canvasH - drawH) / 2f
    val px = ((pos.x - offX) / fitScale).toInt()
    val py = ((pos.y - offY) / fitScale).toInt()
    return px to py
}

@Composable
private fun TopDownGridViewport(
    state: LevelEditorState,
    atlas: TextureAtlas?,
    palette: SnesPalette?,
    validation: LevelValidationResult,
) {
    val grid = state.document.grid
    // Precompute average RGB swatch per descriptor for fast 2D grid tinting
    val texTintMap =
        remember(atlas, palette) {
            if (atlas == null || palette == null) {
                emptyMap()
            } else {
                buildMap {
                    val descriptors = runCatching { atlas.resolvedDescriptors() }.getOrDefault(emptyList())
                    val atlasBin = atlas.generateBinary()
                    for (desc in descriptors) {
                        var rSum = 0L
                        var gSum = 0L
                        var bSum = 0L
                        var count = 0L
                        for (v in 0 until desc.height) {
                            for (u in 0 until desc.width) {
                                val idx = desc.romOffset + v * atlas.width + u
                                if (idx in atlasBin.indices) {
                                    val slot = atlasBin[idx].toInt() and 0x0F
                                    if (slot != 0) {
                                        val rgb = palette[slot].rgb888
                                        rSum += (rgb shr 16) and 0xFF
                                        gSum += (rgb shr 8) and 0xFF
                                        bSum += rgb and 0xFF
                                        count++
                                    }
                                }
                            }
                        }
                        if (count > 0) {
                            put(
                                desc.name,
                                Color(
                                    red = (rSum / count).toInt(),
                                    green = (gSum / count).toInt(),
                                    blue = (bSum / count).toInt(),
                                ),
                            )
                        }
                    }
                }
            }
        }

    Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(grid, state.activeTool) {
                        detectTapGestures { pos ->
                            val coord = mapCanvasToGridCoord(pos, size.width, size.height, grid.cols, grid.rows)
                            if (coord != null) {
                                state.handleCellInteraction(coord.first, coord.second, isDragContinue = false)
                            }
                        }
                    }.pointerInput(grid, state.activeTool) {
                        var startCoord: Pair<Int, Int>? = null
                        detectDragGestures(
                            onDragStart = { pos ->
                                val coord = mapCanvasToGridCoord(pos, size.width, size.height, grid.cols, grid.rows)
                                if (coord != null) {
                                    startCoord = coord
                                    if (state.activeTool == LevelTool.BRUSH) {
                                        state.beginStroke()
                                        state.paintCell(coord.first, coord.second, recordImmediateUndo = false)
                                    } else if (state.activeTool == LevelTool.SELECT) {
                                        state.selectSingleCell(coord.first, coord.second)
                                    }
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val coord =
                                    mapCanvasToGridCoord(
                                        change.position,
                                        size.width,
                                        size.height,
                                        grid.cols,
                                        grid.rows,
                                    )
                                if (coord != null) {
                                    if (state.activeTool == LevelTool.BRUSH) {
                                        state.paintCell(coord.first, coord.second, recordImmediateUndo = false)
                                    } else if (state.activeTool == LevelTool.SELECT) {
                                        val s = startCoord ?: coord
                                        state.selectRect(s.first, s.second, coord.first, coord.second)
                                    }
                                }
                            },
                            onDragEnd = {
                                startCoord = null
                                state.endStroke()
                            },
                            onDragCancel = {
                                startCoord = null
                                state.endStroke()
                            },
                        )
                    },
        ) {
            val cellPix =
                minOf(
                    size.width / grid.cols.toFloat(),
                    size.height / grid.rows.toFloat(),
                ).coerceAtLeast(12f)
            val totalW = cellPix * grid.cols
            val totalH = cellPix * grid.rows
            val originX = (size.width - totalW) / 2f
            val originY = (size.height - totalH) / 2f

            for (r in 0 until grid.rows) {
                for (c in 0 until grid.cols) {
                    val cell = grid.cellAt(c, r)
                    val x = originX + c * cellPix
                    val y = originY + r * cellPix
                    val baseCol = texTintMap[cell.topTex] ?: Color(0xFF283040)
                    val shadeBoost = (cell.baseHeight * 0.04f).coerceAtMost(0.35f)
                    val shaded =
                        Color(
                            red = (baseCol.red + shadeBoost).coerceIn(0f, 1f),
                            green = (baseCol.green + shadeBoost).coerceIn(0f, 1f),
                            blue = (baseCol.blue + shadeBoost).coerceIn(0f, 1f),
                        )

                    drawRect(
                        color = shaded,
                        topLeft = Offset(x + 1f, y + 1f),
                        size = Size(cellPix - 2f, cellPix - 2f),
                    )

                    // Draw edge panel ticks along N/E/S/W borders
                    val panelColor = if (cell.panels.tall2h) Color(0xFFFBBF24) else Color(0xFF22D3EE)
                    val tickThick = (cellPix * 0.12f).coerceIn(2f, 5f)
                    if (cell.panels.north) {
                        drawRect(panelColor, Offset(x + 2f, y + 2f), Size(cellPix - 4f, tickThick))
                    }
                    if (cell.panels.south) {
                        drawRect(panelColor, Offset(x + 2f, y + cellPix - 2f - tickThick), Size(cellPix - 4f, tickThick))
                    }
                    if (cell.panels.west) {
                        drawRect(panelColor, Offset(x + 2f, y + 2f), Size(tickThick, cellPix - 4f))
                    }
                    if (cell.panels.east) {
                        drawRect(panelColor, Offset(x + cellPix - 2f - tickThick, y + 2f), Size(tickThick, cellPix - 4f))
                    }

                    // Draw rotation direction line for sloped/directional archetypes
                    if (!cell.archetype.isRotationallySymmetric) {
                        val cx = x + cellPix * 0.5f
                        val cy = y + cellPix * 0.5f
                        val rad = cellPix * 0.28f
                        val tip =
                            when (cell.rotation) {
                                CellRotation.ROT_0 -> Offset(cx, cy - rad)
                                CellRotation.ROT_90 -> Offset(cx + rad, cy)
                                CellRotation.ROT_180 -> Offset(cx, cy + rad)
                                CellRotation.ROT_270 -> Offset(cx - rad, cy)
                            }
                        drawLine(Color.White, Offset(cx, cy), tip, strokeWidth = 2f)
                        drawCircle(Color.White, radius = 2.5f, center = tip)
                    }

                    // Diagnostic outline
                    when (state.hasDiagnosticAt(c, r, validation)) {
                        DiagnosticSeverity.ERROR ->
                            drawRect(
                                color = Color(0xFFEF4444),
                                topLeft = Offset(x + 1f, y + 1f),
                                size = Size(cellPix - 2f, cellPix - 2f),
                                style = Stroke(width = 2f),
                            )
                        DiagnosticSeverity.WARNING ->
                            drawRect(
                                color = Color(0xFFF59E0B),
                                topLeft = Offset(x + 1f, y + 1f),
                                size = Size(cellPix - 2f, cellPix - 2f),
                                style = Stroke(width = 1.5f),
                            )
                        null -> {}
                    }

                    // Selection outline
                    if ((c to r) in state.selectedCells) {
                        drawRect(
                            color = Color(0xFF38BDF8),
                            topLeft = Offset(x + 1.5f, y + 1.5f),
                            size = Size(cellPix - 3f, cellPix - 3f),
                            style = Stroke(width = 2.5f),
                        )
                    }
                }
            }
        }

        Text(
            text = "2D Top-Down Grid (${grid.cols}x${grid.rows} • N is Up)",
            color = Color(0xFF94A3B8),
            fontSize = 10.sp,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .background(Color(0xAA111215), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun mapCanvasToGridCoord(
    pos: Offset,
    canvasW: Int,
    canvasH: Int,
    cols: Int,
    rows: Int,
): Pair<Int, Int>? {
    val cellPix = minOf(canvasW.toFloat() / cols, canvasH.toFloat() / rows).coerceAtLeast(12f)
    val originX = (canvasW - cellPix * cols) / 2f
    val originY = (canvasH - cellPix * rows) / 2f
    val c = ((pos.x - originX) / cellPix).toInt()
    val r = ((pos.y - originY) / cellPix).toInt()
    return if (c in 0 until cols && r in 0 until rows) c to r else null
}

@Composable
private fun LevelRightInspectorDock(
    state: LevelEditorState,
    descriptors: List<TextureDescriptorInfo>,
    validation: LevelValidationResult,
) {
    val grid = state.document.grid
    val primaryCoord = state.selectedCells.firstOrNull()
    val inspectedCell = state.primarySelectedCell() ?: state.brushCell

    Column(
        modifier =
            Modifier
                .width(264.dp)
                .fillMaxHeight()
                .background(Color(0xFF1D1F26))
                .border(1.dp, Color(0xFF2B2E36))
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Grid Dimensions section
        Text("Map Grid (${grid.cols}x${grid.rows})", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Cols: ${grid.cols}", color = Color(0xFFCBD5E1), fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolbarActionButton("-Col", enabled = grid.cols > MapGrid.MIN_DIM) {
                    state.resizeGrid(grid.cols - 1, grid.rows)
                }
                ToolbarActionButton("+Col", enabled = grid.cols < MapGrid.MAX_DIM) {
                    state.resizeGrid(grid.cols + 1, grid.rows)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Rows: ${grid.rows}", color = Color(0xFFCBD5E1), fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolbarActionButton("-Row", enabled = grid.rows > MapGrid.MIN_DIM) {
                    state.resizeGrid(grid.cols, grid.rows - 1)
                }
                ToolbarActionButton("+Row", enabled = grid.rows < MapGrid.MAX_DIM) {
                    state.resizeGrid(grid.cols, grid.rows + 1)
                }
            }
        }

        Divider(color = Color(0xFF2C2F37))

        // Selection Header
        val selHeader =
            when {
                state.selectedCells.size > 1 -> "${state.selectedCells.size} Cells Selected"
                primaryCoord != null -> "Cell (${primaryCoord.first}, ${primaryCoord.second})"
                else -> "Brush Defaults"
            }
        Text(selHeader, color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

        // Base Height Stepper
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Base Height: ${inspectedCell.baseHeight}h (${inspectedCell.baseHeight * 16}wu)",
                color = Color.White,
                fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolbarActionButton("-") { state.stepSelectedHeight(-1) }
                ToolbarActionButton("+") { state.stepSelectedHeight(1) }
            }
        }

        // Rotation selector
        Text("Rotation", color = Color(0xFF94A3B8), fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (rot in CellRotation.entries) {
                ToolbarChip(
                    label = "${rot.degrees}°",
                    selected = inspectedCell.rotation == rot,
                ) {
                    state.setSelectedRotation(rot)
                }
            }
        }

        Divider(color = Color(0xFF2C2F37))

        // Assigned Textures summary
        Text("Cell Texture Slots", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        InspectorTextureSlotRow(
            label = "Top",
            texName = inspectedCell.topTex,
            active = state.activeTextureSlot == ActiveTextureSlot.TOP,
            onClick = { state.activeTextureSlot = ActiveTextureSlot.TOP },
        )
        InspectorTextureSlotRow(
            label = "Skirt",
            texName = inspectedCell.skirtTex,
            active = state.activeTextureSlot == ActiveTextureSlot.SKIRT,
            onClick = { state.activeTextureSlot = ActiveTextureSlot.SKIRT },
        )
        InspectorTextureSlotRow(
            label = "Panel",
            texName = inspectedCell.panelTex,
            active = state.activeTextureSlot == ActiveTextureSlot.PANEL,
            onClick = { state.activeTextureSlot = ActiveTextureSlot.PANEL },
        )

        if (descriptors.isNotEmpty() && state.selectedCells.isNotEmpty()) {
            ToolbarActionButton("Stamp Brush -> Selection") {
                state.applyBrushToSelection()
            }
        }

        Divider(color = Color(0xFF2C2F37))

        // Perimeter Edge Panels
        Text("Perimeter Edge Panels", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolbarChip("N", inspectedCell.panels.north) {
                state.updateSelectedPanels { it.copy(north = !it.north) }
            }
            ToolbarChip("E", inspectedCell.panels.east) {
                state.updateSelectedPanels { it.copy(east = !it.east) }
            }
            ToolbarChip("S", inspectedCell.panels.south) {
                state.updateSelectedPanels { it.copy(south = !it.south) }
            }
            ToolbarChip("W", inspectedCell.panels.west) {
                state.updateSelectedPanels { it.copy(west = !it.west) }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.clickable {
                    state.updateSelectedPanels { it.copy(tall2h = !it.tall2h) }
                },
        ) {
            Checkbox(
                checked = inspectedCell.panels.tall2h,
                onCheckedChange = { checked ->
                    state.updateSelectedPanels { it.copy(tall2h = checked) }
                },
                enabled = inspectedCell.panels.hasAny,
                colors =
                    CheckboxDefaults.colors(
                        checkedColor = Color(0xFF3B82F6),
                        uncheckedColor = Color(0xFF64748B),
                    ),
            )
            Text("Tall 2h Wall Panel (32px)", color = Color(0xFFCBD5E1), fontSize = 11.sp)
        }

        // Per-cell diagnostics
        if (primaryCoord != null) {
            val cellDiags =
                validation.allDiagnostics.filter {
                    it.col == primaryCoord.first && it.row == primaryCoord.second
                }
            if (cellDiags.isNotEmpty()) {
                Divider(color = Color(0xFF2C2F37))
                Text("Cell Diagnostics", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                for (d in cellDiags) {
                    val col =
                        if (d.severity == DiagnosticSeverity.ERROR) Color(0xFFFCA5A5) else Color(0xFFFDE68A)
                    Text("• [${d.code}] ${d.message}", color = col, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun InspectorTextureSlotRow(
    label: String,
    texName: String?,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (active) Color(0xFF25354D) else Color(0xFF16181D))
                .border(1.dp, if (active) Color(0xFF3B82F6) else Color(0xFF2A2D34), RoundedCornerShape(4.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 11.sp)
        Text(
            text = texName ?: "(none)",
            color = if (texName != null) Color(0xFF67E8F9) else Color(0xFF64748B),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ToolbarChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .height(26.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (selected) Color(0xFF2563EB) else Color(0xFF282B33))
                .border(1.dp, if (selected) Color(0xFF60A5FA) else Color(0xFF3B3F4A), RoundedCornerShape(4.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun ToolbarActionButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .height(26.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (enabled) Color(0xFF282B33) else Color(0xFF1B1D23))
                .border(1.dp, Color(0xFF3B3F4A), RoundedCornerShape(4.dp))
                .let { if (enabled) it.clickable(onClick = onClick) else it }
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Color(0xFFE2E8F0) else Color(0xFF64748B), fontSize = 11.sp)
    }
}

@Composable
private fun ToolbarDivider() {
    Box(
        modifier =
            Modifier
                .width(1.dp)
                .height(22.dp)
                .background(Color(0xFF333742)),
    )
}
