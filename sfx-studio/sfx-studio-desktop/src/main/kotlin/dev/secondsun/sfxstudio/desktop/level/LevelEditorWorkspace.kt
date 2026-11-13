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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
import kotlinx.coroutines.delay
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.roundToInt

enum class IsoCameraControlAction(
    val symbol: String,
    val keyLabel: String,
) {
    ROTATE_CW("↻", "Q"),
    PAN_UP("↑", "W"),
    ROTATE_CCW("↺", "E"),
    PAN_NEG_X("←", "A"),
    PAN_DOWN("↓", "S"),
    PAN_POS_X("→", "D"),
    ;

    fun perform(state: LevelEditorState) {
        when (this) {
            ROTATE_CW -> state.rotateCameraClockwise()
            PAN_UP -> state.panCameraUp()
            ROTATE_CCW -> state.rotateCameraCounterClockwise()
            PAN_NEG_X -> state.panCameraNegativeX()
            PAN_DOWN -> state.panCameraDown()
            PAN_POS_X -> state.panCameraPositiveX()
        }
    }
}

internal fun mapKeyToCameraAction(key: Key): IsoCameraControlAction? =
    when (key) {
        Key.Q -> IsoCameraControlAction.ROTATE_CW
        Key.W, Key.DirectionUp -> IsoCameraControlAction.PAN_UP
        Key.E -> IsoCameraControlAction.ROTATE_CCW
        Key.A, Key.DirectionLeft -> IsoCameraControlAction.PAN_NEG_X
        Key.S, Key.DirectionDown -> IsoCameraControlAction.PAN_DOWN
        Key.D, Key.DirectionRight -> IsoCameraControlAction.PAN_POS_X
        else -> null
    }

@Composable
fun LevelEditorWorkspace(state: LevelEditorState) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(state.sessionId) {
        focusRequester.requestFocus()
    }

    var heldCameraKeys by remember { mutableStateOf(emptySet<Key>()) }
    var pressedMouseCameraAction by remember { mutableStateOf<IsoCameraControlAction?>(null) }
    var showTexturePickerModal by remember { mutableStateOf(false) }

    val activeCameraActions: Set<IsoCameraControlAction> =
        remember(heldCameraKeys, pressedMouseCameraAction) {
            buildSet {
                for (k in heldCameraKeys) {
                    mapKeyToCameraAction(k)?.let { add(it) }
                }
                pressedMouseCameraAction?.let { add(it) }
            }
        }

    LaunchedEffect(activeCameraActions, state) {
        if (activeCameraActions.isEmpty()) return@LaunchedEffect
        delay(140L)
        while (true) {
            for (act in activeCameraActions) {
                act.perform(state)
            }
            delay(33L)
        }
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
                    val camAction = mapKeyToCameraAction(event.key)
                    if (event.type == KeyEventType.KeyUp) {
                        if (event.key in heldCameraKeys) {
                            heldCameraKeys = heldCameraKeys - event.key
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                    if (camAction != null && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
                        if (event.key !in heldCameraKeys) {
                            heldCameraKeys = heldCameraKeys + event.key
                            camAction.perform(state)
                        }
                        return@onPreviewKeyEvent true
                    }

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
                        else -> false
                    }
                },
    ) {
        LevelTopToolbar(state)
        DiagnosticsBanner(state, validation)

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LevelLeftPaletteDock(state = state)

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF121418)),
            ) {
                CenterPreviewTabBar(state = state)

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF121418)),
                ) {
                    when (state.centerViewMode) {
                        LevelCenterViewMode.SPLIT -> {
                            Row(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1.15f).fillMaxHeight()) {
                                    Iso3DViewport(
                                        state = state,
                                        atlas = atlas,
                                        palette = palette,
                                        activeCameraActions = activeCameraActions,
                                        onCameraButtonPressChange = { pressedMouseCameraAction = it },
                                    )
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
                        LevelCenterViewMode.ISO_3D ->
                            Iso3DViewport(
                                state = state,
                                atlas = atlas,
                                palette = palette,
                                activeCameraActions = activeCameraActions,
                                onCameraButtonPressChange = { pressedMouseCameraAction = it },
                            )
                        LevelCenterViewMode.TOP_DOWN_2D -> TopDownGridViewport(state, atlas, palette, validation)
                    }
                }
            }

            LevelRightInspectorDock(
                state = state,
                descriptors = descriptors,
                validation = validation,
                onOpenTexturePicker = { slot ->
                    state.activeTextureSlot = slot
                    showTexturePickerModal = true
                },
            )
        }
    }

    if (showTexturePickerModal) {
        TextureSlotPickerModal(
            state = state,
            atlas = atlas,
            palette = palette,
            descriptors = descriptors,
            onDismiss = { showTexturePickerModal = false },
        )
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
        ToolbarActionButton("Undo", enabled = state.canUndo) { state.undo() }
        ToolbarActionButton("Redo", enabled = state.canRedo) { state.redo() }

        Spacer(modifier = Modifier.weight(1f))

        ToolbarActionButton("Import .i") {
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
private fun CenterPreviewTabBar(state: LevelEditorState) {
    val tabs =
        listOf(
            LevelCenterViewMode.SPLIT to "Split",
            LevelCenterViewMode.ISO_3D to "3D Iso",
            LevelCenterViewMode.TOP_DOWN_2D to "2D Grid",
        )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(Color(0xFF191B21))
                .border(1.dp, Color(0xFF282B33))
                .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((mode, label) in tabs) {
            val selected = state.centerViewMode == mode
            Box(
                modifier =
                    Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (selected) Color(0xFF121418) else Color(0xFF20232B))
                        .border(
                            width = 1.dp,
                            color = if (selected) Color(0xFF3B82F6) else Color(0xFF2A2E38),
                            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                        ).clickable { state.centerViewMode = mode }
                        .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) Color.White else Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
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
private fun LevelLeftPaletteDock(state: LevelEditorState) {
    Column(
        modifier =
            Modifier
                .width(216.dp)
                .fillMaxHeight()
                .background(Color(0xFF1D1F26))
                .border(1.dp, Color(0xFF2B2E36))
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // GIMP-style Toolbox grid
        Text("Toolbox", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GimpToolButton(
                icon = "⬚",
                label = "Select",
                shortcut = "V",
                selected = state.activeTool == LevelTool.SELECT,
                modifier = Modifier.weight(1f),
                onClick = { state.activeTool = LevelTool.SELECT },
            )
            GimpToolButton(
                icon = "✎",
                label = "Brush",
                shortcut = "B",
                selected = state.activeTool == LevelTool.BRUSH,
                modifier = Modifier.weight(1f),
                onClick = { state.activeTool = LevelTool.BRUSH },
            )
            GimpToolButton(
                icon = "💧",
                label = "Pick",
                shortcut = "I",
                selected = state.activeTool == LevelTool.EYEDROPPER,
                modifier = Modifier.weight(1f),
                onClick = { state.activeTool = LevelTool.EYEDROPPER },
            )
        }

        Divider(color = Color(0xFF2C2F37))

        // GIMP-style Tool Options
        val toolTitle =
            when (state.activeTool) {
                LevelTool.SELECT -> "Tool Options: Select"
                LevelTool.BRUSH -> "Tool Options: Brush"
                LevelTool.EYEDROPPER -> "Tool Options: Eyedropper"
            }
        Text(toolTitle, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

        Text("Brush Layer Mask", color = Color(0xFF94A3B8), fontSize = 10.sp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolOptionToggleRow("Height", state.brushMask.applyHeight) {
                state.brushMask = state.brushMask.copy(applyHeight = !state.brushMask.applyHeight)
            }
            ToolOptionToggleRow("Shape & Rotation", state.brushMask.applyArchetypeAndRotation) {
                state.brushMask =
                    state.brushMask.copy(
                        applyArchetypeAndRotation = !state.brushMask.applyArchetypeAndRotation,
                    )
            }
            ToolOptionToggleRow("Top Texture", state.brushMask.applyTopTex) {
                state.brushMask = state.brushMask.copy(applyTopTex = !state.brushMask.applyTopTex)
            }
            ToolOptionToggleRow("Skirt Texture", state.brushMask.applySkirtTex) {
                state.brushMask = state.brushMask.copy(applySkirtTex = !state.brushMask.applySkirtTex)
            }
            ToolOptionToggleRow("Panel & Flags", state.brushMask.applyPanelTexAndFlags) {
                state.brushMask =
                    state.brushMask.copy(applyPanelTexAndFlags = !state.brushMask.applyPanelTexAndFlags)
            }
        }

        Divider(color = Color(0xFF2C2F37))

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
                Text(
                    text = atlasLabel,
                    color = Color(0xFF67E8F9),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            ToolbarActionButton("Link") {
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
    }
}

@Composable
private fun GimpToolButton(
    icon: String,
    label: String,
    shortcut: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(5.dp))
                .background(if (selected) Color(0xFF253B5E) else Color(0xFF16181D))
                .border(
                    width = 1.dp,
                    color = if (selected) Color(0xFF60A5FA) else Color(0xFF2E323C),
                    shape = RoundedCornerShape(5.dp),
                ).clickable(onClick = onClick)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(icon, color = Color.White, fontSize = 15.sp)
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFFCBD5E1),
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            text = "[$shortcut]",
            color = if (selected) Color(0xFF93C5FD) else Color(0xFF64748B),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ToolOptionToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) Color(0xFF202B3D) else Color(0xFF16181D))
                .border(
                    width = 1.dp,
                    color = if (checked) Color(0xFF3B82F6) else Color(0xFF2A2D34),
                    shape = RoundedCornerShape(4.dp),
                ).clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (checked) Color.White else Color(0xFF94A3B8),
            fontSize = 11.sp,
        )
        Text(
            text = if (checked) "ON" else "OFF",
            color = if (checked) Color(0xFF60A5FA) else Color(0xFF64748B),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun Iso3DViewport(
    state: LevelEditorState,
    atlas: TextureAtlas?,
    palette: SnesPalette?,
    activeCameraActions: Set<IsoCameraControlAction> = emptySet(),
    onCameraButtonPressChange: (IsoCameraControlAction?) -> Unit = {},
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
        remember(state.document.grid, atlas, palette, state.cameraYaw, state.cameraPanX, state.cameraPanY) {
            IsoSoftwareRenderer.render(
                grid = state.document.grid,
                atlas = atlas,
                palette = palette,
                yawIndex = state.cameraYaw,
                panX = state.cameraPanX,
                panY = state.cameraPanY,
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
            text =
                "3D Software Preview (256x160 • Yaw ${state.cameraYaw * 3}° • " +
                    "Pan ${state.cameraPanX}, ${state.cameraPanY})",
            color = Color(0xFF94A3B8),
            fontSize = 10.sp,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .background(Color(0xAA111215), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        IsoCameraControlsOverlay(
            state = state,
            activeActions = activeCameraActions,
            onButtonPressChange = onCameraButtonPressChange,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
        )
    }
}

@Composable
private fun IsoCameraControlsOverlay(
    state: LevelEditorState,
    activeActions: Set<IsoCameraControlAction>,
    onButtonPressChange: (IsoCameraControlAction?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val row1 =
        listOf(
            IsoCameraControlAction.ROTATE_CW,
            IsoCameraControlAction.PAN_UP,
            IsoCameraControlAction.ROTATE_CCW,
        )
    val row2 =
        listOf(
            IsoCameraControlAction.PAN_NEG_X,
            IsoCameraControlAction.PAN_DOWN,
            IsoCameraControlAction.PAN_POS_X,
        )

    Column(
        modifier =
            modifier
                .background(Color(0x99111318), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0x88334155), RoundedCornerShape(6.dp))
                .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in listOf(row1, row2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (action in row) {
                    val isActive = action in activeActions
                    IsoCameraOverlayButton(
                        action = action,
                        isActive = isActive,
                        onPressStart = {
                            action.perform(state)
                            onButtonPressChange(action)
                        },
                        onPressEnd = {
                            onButtonPressChange(null)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun IsoCameraOverlayButton(
    action: IsoCameraControlAction,
    isActive: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
) {
    val bgColor = if (isActive) Color(0xCC2563EB) else Color(0xAA1E222B)
    val borderColor = if (isActive) Color(0xEE60A5FA) else Color(0xAA3B4252)
    val textColor = if (isActive) Color.White else Color(0xFFE2E8F0)

    Box(
        modifier =
            Modifier
                .size(width = 44.dp, height = 28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(4.dp))
                .pointerInput(action) {
                    detectTapGestures(
                        onPress = {
                            onPressStart()
                            try {
                                tryAwaitRelease()
                            } finally {
                                onPressEnd()
                            }
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${action.symbol} ${action.keyLabel}",
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
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
    onOpenTexturePicker: (ActiveTextureSlot) -> Unit,
) {
    val grid = state.document.grid
    val primaryCoord = state.selectedCells.firstOrNull()
    val inspectedCell = state.primarySelectedCell() ?: state.brushCell
    var archetypeMenuExpanded by remember { mutableStateOf(false) }

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

        // Base Height Stepper (unified with [ / ] keyboard shortcuts)
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
                ToolbarActionButton("- [[]") { state.stepSelectedHeight(-1) }
                ToolbarActionButton("+ []]") { state.stepSelectedHeight(1) }
            }
        }

        // Shape Archetype dropdown
        Text("Shape Archetype", color = Color(0xFF94A3B8), fontSize = 11.sp)
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF16181D))
                        .border(1.dp, Color(0xFF3B3F4A), RoundedCornerShape(4.dp))
                        .clickable { archetypeMenuExpanded = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${inspectedCell.archetype.id}. ${inspectedCell.archetype.displayName}",
                    color = Color.White,
                    fontSize = 11.sp,
                )
                Text("▾", color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
            DropdownMenu(
                expanded = archetypeMenuExpanded,
                onDismissRequest = { archetypeMenuExpanded = false },
                modifier = Modifier.background(Color(0xFF22242B)),
            ) {
                for (arch in ShapeArchetype.entries) {
                    DropdownMenuItem(
                        onClick = {
                            state.setSelectedArchetype(arch)
                            archetypeMenuExpanded = false
                        },
                    ) {
                        Text(
                            text = "${arch.id}. ${arch.displayName}",
                            color = if (arch == inspectedCell.archetype) Color(0xFF60A5FA) else Color.White,
                            fontSize = 11.sp,
                            fontWeight =
                                if (arch == inspectedCell.archetype) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                        )
                    }
                }
            }
        }

        // Rotation selector (unified with Rot 90° [R])
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Rotation", color = Color(0xFF94A3B8), fontSize = 11.sp)
            ToolbarActionButton("↻ 90° [R]") { state.rotateSelectedClockwise() }
        }
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

        // Assigned Textures summary (click any slot to open picker popup)
        Text("Cell Texture Slots", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        InspectorTextureSlotRow(
            label = "Top",
            texName = inspectedCell.topTex,
            active = state.activeTextureSlot == ActiveTextureSlot.TOP,
            onClick = { onOpenTexturePicker(ActiveTextureSlot.TOP) },
        )
        InspectorTextureSlotRow(
            label = "Skirt",
            texName = inspectedCell.skirtTex,
            active = state.activeTextureSlot == ActiveTextureSlot.SKIRT,
            onClick = { onOpenTexturePicker(ActiveTextureSlot.SKIRT) },
        )
        InspectorTextureSlotRow(
            label = "Panel",
            texName = inspectedCell.panelTex,
            active = state.activeTextureSlot == ActiveTextureSlot.PANEL,
            onClick = { onOpenTexturePicker(ActiveTextureSlot.PANEL) },
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
private fun TextureSlotPickerModal(
    state: LevelEditorState,
    atlas: TextureAtlas?,
    palette: SnesPalette?,
    descriptors: List<TextureDescriptorInfo>,
    onDismiss: () -> Unit,
) {
    val slotLabel =
        when (state.activeTextureSlot) {
            ActiveTextureSlot.TOP -> "Top"
            ActiveTextureSlot.SKIRT -> "Skirt"
            ActiveTextureSlot.PANEL -> "Panel"
        }
    val activeSelectedName =
        when (state.activeTextureSlot) {
            ActiveTextureSlot.TOP -> state.primarySelectedCell()?.topTex ?: state.brushCell.topTex
            ActiveTextureSlot.SKIRT -> state.primarySelectedCell()?.skirtTex ?: state.brushCell.skirtTex
            ActiveTextureSlot.PANEL -> state.primarySelectedCell()?.panelTex ?: state.brushCell.panelTex
        }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(380.dp),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF22242B),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Select $slotLabel Texture",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.activeTextureSlot == ActiveTextureSlot.PANEL) {
                        ToolbarActionButton("Clear (none)") {
                            state.assignTextureToActiveSlot(null)
                            onDismiss()
                        }
                    }
                }

                if (atlas == null || palette == null || descriptors.isEmpty()) {
                    Text(
                        text = "Link an Atlas Document (.sfxproj) to browse textures.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                    )
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
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
                                        ).clickable {
                                            state.assignTextureToActiveSlot(desc.name)
                                            onDismiss()
                                        }.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (previewBmp != null) {
                                    Image(
                                        bitmap = previewBmp,
                                        contentDescription = desc.name,
                                        filterQuality = FilterQuality.None,
                                        modifier =
                                            Modifier
                                                .size(28.dp)
                                                .background(Color(0xFF0B0C0E))
                                                .border(1.dp, Color(0xFF334155)),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = desc.name,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Text(
                                        text = "${desc.width}x${desc.height} (#$descIdx)",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Color(0xFF94A3B8))
                    }
                }
            }
        }
    }
}
