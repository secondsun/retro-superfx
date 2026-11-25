package dev.secondsun.sfxstudio.desktop.level

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenu
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.secondsun.sfxstudio.core.atlas.DescriptorLookup
import dev.secondsun.sfxstudio.core.atlas.DescriptorTable
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.atlas.TextureDescriptorInfo
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.DiagnosticSeverity
import dev.secondsun.sfxstudio.core.level.InteractableKind
import dev.secondsun.sfxstudio.core.level.IsoRenderResult
import dev.secondsun.sfxstudio.core.level.IsoSoftwareRenderer
import dev.secondsun.sfxstudio.core.level.LevelValidationResult
import dev.secondsun.sfxstudio.desktop.awt.toStudioImageBitmap
import dev.secondsun.sfxstudio.desktop.shell.LevelEditMode
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.SegmentItem
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioBadge
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioDiagnosticItem
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioDiagnosticsBanner
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioIconButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioKeycap
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioNumberStepper
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioPanelSheet
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSectionHeader
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSegmentedControl
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.HairlineEdge
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioViewportClass
import dev.secondsun.sfxstudio.desktop.ui.theme.drawEdgeHairline
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

enum class IsoCameraControlAction(
    val keyLabel: String,
    val description: String,
) {
    ROTATE_CW("Q", "Orbit Camera Clockwise"),
    PAN_UP("W", "Pan Camera Up"),
    ROTATE_CCW("E", "Orbit Camera Counter-Clockwise"),
    PAN_NEG_X("A", "Pan Camera Left"),
    PAN_DOWN("S", "Pan Camera Down"),
    PAN_POS_X("D", "Pan Camera Right"),
    ;

    val icon: ImageVector
        get() =
            when (this) {
                ROTATE_CW -> StudioIcons.RotateCw
                PAN_UP -> StudioIcons.ArrowUp
                ROTATE_CCW -> StudioIcons.RotateCcw
                PAN_NEG_X -> StudioIcons.ArrowLeft
                PAN_DOWN -> StudioIcons.ArrowDown
                PAN_POS_X -> StudioIcons.ArrowRight
            }

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
    val colors = StudioTheme.colors
    val viewport = StudioTheme.viewport
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(state.sessionId) {
        focusRequester.requestFocus()
    }

    DisposableEffect(state) {
        state.animateCameraReveal = true
        onDispose {
            state.animateCameraReveal = false
            state.clearCameraRevealTarget()
        }
    }

    LaunchedEffect(state.cameraRevealTarget, state) {
        val target = state.cameraRevealTarget ?: return@LaunchedEffect
        val startYaw = state.cameraYaw
        val startPanX = state.cameraPanX
        val startPanY = state.cameraPanY

        var dYaw = (target.yaw - startYaw) % 120
        if (dYaw > 60) dYaw -= 120
        if (dYaw < -60) dYaw += 120
        val dPanX = target.panX - startPanX
        val dPanY = target.panY - startPanY

        if (dYaw == 0 && dPanX == 0 && dPanY == 0) {
            state.clearCameraRevealTarget()
            return@LaunchedEffect
        }

        val durationMs =
            if (dYaw != 0) {
                (240L + abs(dYaw) * 3L).coerceAtMost(420L)
            } else {
                220L
            }
        val frameMs = 16L
        val steps = (durationMs / frameMs).toInt().coerceAtLeast(1)

        for (step in 1..steps) {
            delay(frameMs)
            val t = step.toFloat() / steps.toFloat()
            val eased = 1f - (1f - t) * (1f - t) * (1f - t)
            val curYaw = startYaw + (dYaw * eased).roundToInt()
            val curPanX = startPanX + (dPanX * eased).roundToInt()
            val curPanY = startPanY + (dPanY * eased).roundToInt()
            state.setCameraImmediate(curYaw, curPanX, curPanY)
        }
        state.setCameraImmediate(target.yaw, target.panX, target.panY)
        state.clearCameraRevealTarget()
    }

    var heldCameraKeys by remember { mutableStateOf(emptySet<Key>()) }
    var pressedMouseCameraAction by remember { mutableStateOf<IsoCameraControlAction?>(null) }
    var spacePanHeld by remember { mutableStateOf(false) }

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
    val descriptorTable =
        remember(atlas) {
            atlas?.descriptorTable()?.getOrNull()
        }
    val descriptors = descriptorTable?.entries.orEmpty()
    val validation =
        remember(state.document, atlas) {
            state.currentValidation()
        }

    val diagnosticItems =
        remember(validation, state) {
            validation.allDiagnostics.map { diag ->
                StudioDiagnosticItem(
                    isError = diag.severity == DiagnosticSeverity.ERROR,
                    code = diag.code,
                    message = diag.message,
                    onClick = {
                        val dc = diag.col
                        val dr = diag.row
                        if (dc != null && dr != null) {
                            state.selectSingleCell(dc, dr)
                        }
                    },
                )
            }
        }

    val showLeftDock = state.studio?.showLeftDock != false
    val showRightDock = state.studio?.showRightDock != false
    val showBottomSheet = state.studio?.showCompactBottomSheet != false

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.bgApp)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp) {
                        if (event.key == Key.Spacebar && spacePanHeld) {
                            spacePanHeld = false
                            return@onPreviewKeyEvent true
                        }
                        if (event.key in heldCameraKeys) {
                            heldCameraKeys = heldCameraKeys - event.key
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }.onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val primaryMod = event.isCtrlPressed || event.isMetaPressed
                    val noMod = !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed

                    if (noMod && event.key == Key.Spacebar) {
                        spacePanHeld = true
                        return@onKeyEvent true
                    }

                    val camAction = mapKeyToCameraAction(event.key)
                    if (camAction != null && noMod) {
                        if (event.key !in heldCameraKeys) {
                            heldCameraKeys = heldCameraKeys + event.key
                            camAction.perform(state)
                        }
                        return@onKeyEvent true
                    }

                    when {
                        primaryMod && event.key == Key.S && !event.isShiftPressed -> {
                            state.requestSave()
                            true
                        }
                        noMod && event.key == Key.V -> {
                            if (state.editMode == LevelEditMode.GAMEPLAY) {
                                state.gameplayPanActive = false
                                state.gameplayTool = GameplayTool.SELECT_ENTITY
                            } else {
                                state.activeTool = LevelTool.SELECT
                            }
                            true
                        }
                        noMod && event.key == Key.B -> {
                            if (state.editMode == LevelEditMode.GAMEPLAY) {
                                state.gameplayPanActive = false
                                state.gameplayTool = GameplayTool.PAINT_WALKABILITY
                            } else {
                                state.activeTool = LevelTool.BRUSH
                            }
                            true
                        }
                        noMod && event.key == Key.I && state.editMode == LevelEditMode.TERRAIN -> {
                            state.activeTool = LevelTool.EYEDROPPER
                            true
                        }
                        noMod && event.key == Key.H -> {
                            if (state.editMode == LevelEditMode.GAMEPLAY) {
                                state.gameplayPanActive = !state.gameplayPanActive
                            } else {
                                state.activeTool = LevelTool.PAN
                            }
                            true
                        }
                        noMod && event.key == Key.P && state.editMode == LevelEditMode.GAMEPLAY -> {
                            state.gameplayPanActive = false
                            state.gameplayTool = GameplayTool.PLACE_PLAYER
                            true
                        }
                        noMod && event.key == Key.N && state.editMode == LevelEditMode.GAMEPLAY -> {
                            state.gameplayPanActive = false
                            state.gameplayTool = GameplayTool.PLACE_NPC
                            true
                        }
                        noMod && event.key == Key.M && state.editMode == LevelEditMode.GAMEPLAY -> {
                            state.gameplayPanActive = false
                            state.gameplayTool = GameplayTool.EDIT_WAYPOINTS
                            true
                        }
                        noMod && event.key == Key.O && state.editMode == LevelEditMode.GAMEPLAY -> {
                            state.gameplayPanActive = false
                            state.gameplayTool = GameplayTool.PLACE_INTERACTABLE
                            true
                        }
                        noMod && event.key == Key.R -> {
                            state.rotateSelectedClockwise()
                            true
                        }
                        noMod && event.key == Key.LeftBracket -> {
                            state.stepSelectedHeight(-1)
                            true
                        }
                        noMod && event.key == Key.RightBracket -> {
                            state.stepSelectedHeight(1)
                            true
                        }
                        else -> false
                    }
                },
    ) {
        StudioDiagnosticsBanner(
            items = diagnosticItems,
            activeIndex = state.activeDiagnosticIndex,
            onActiveIndexChange = { state.activeDiagnosticIndex = it },
        )

        when (viewport.viewportClass) {
            StudioViewportClass.EXPANDED -> {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (showLeftDock) {
                        LevelLeftDock(state = state)
                    }

                    LevelCenterStage(
                        state = state,
                        atlas = atlas,
                        palette = palette,
                        descriptorTable = descriptorTable,
                        validation = validation,
                        activeCameraActions = activeCameraActions,
                        onCameraButtonPressChange = { pressedMouseCameraAction = it },
                        spacePanHeld = spacePanHeld,
                        verticalSplit = false,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )

                    if (showRightDock) {
                        LevelRightInspectorSheet(
                            state = state,
                            descriptorTable = descriptorTable,
                            palette = palette,
                            descriptors = descriptors,
                            validation = validation,
                            widthDp = 280.dp,
                        )
                    }
                }
            }

            StudioViewportClass.MEDIUM -> {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (showLeftDock) {
                        LevelToolRail(state = state)
                    }

                    LevelCenterStage(
                        state = state,
                        atlas = atlas,
                        palette = palette,
                        descriptorTable = descriptorTable,
                        validation = validation,
                        activeCameraActions = activeCameraActions,
                        onCameraButtonPressChange = { pressedMouseCameraAction = it },
                        spacePanHeld = spacePanHeld,
                        verticalSplit = false,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )

                    if (showRightDock) {
                        LevelRightInspectorSheet(
                            state = state,
                            descriptorTable = descriptorTable,
                            palette = palette,
                            descriptors = descriptors,
                            validation = validation,
                            widthDp = 268.dp,
                        )
                    }
                }
            }

            StudioViewportClass.COMPACT -> {
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        LevelToolRail(state = state)

                        LevelCenterStage(
                            state = state,
                            atlas = atlas,
                            palette = palette,
                            descriptorTable = descriptorTable,
                            validation = validation,
                            activeCameraActions = activeCameraActions,
                            onCameraButtonPressChange = { pressedMouseCameraAction = it },
                            spacePanHeld = spacePanHeld,
                            verticalSplit = true,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }

                    if (showBottomSheet) {
                        CompactBottomInspectorSheet(
                            state = state,
                            descriptorTable = descriptorTable,
                            palette = palette,
                            descriptors = descriptors,
                            validation = validation,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelCenterStage(
    state: LevelEditorState,
    atlas: TextureAtlas?,
    palette: SnesPalette?,
    descriptorTable: DescriptorTable?,
    validation: LevelValidationResult,
    activeCameraActions: Set<IsoCameraControlAction>,
    onCameraButtonPressChange: (IsoCameraControlAction?) -> Unit,
    spacePanHeld: Boolean,
    verticalSplit: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    Box(
        modifier =
            modifier
                .background(colors.bgCanvas)
                .clipToBounds(),
    ) {
        when (state.centerViewMode) {
            LevelCenterViewMode.SPLIT -> {
                if (verticalSplit) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            Iso3DViewport(
                                state = state,
                                atlas = atlas,
                                palette = palette,
                                activeCameraActions = activeCameraActions,
                                onCameraButtonPressChange = onCameraButtonPressChange,
                                spacePanHeld = spacePanHeld,
                            )
                        }
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(colors.borderSubtle),
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            TopDownGridViewport(
                                state = state,
                                descriptorTable = descriptorTable,
                                palette = palette,
                                validation = validation,
                                spacePanHeld = spacePanHeld,
                            )
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1.15f).fillMaxHeight()) {
                            Iso3DViewport(
                                state = state,
                                atlas = atlas,
                                palette = palette,
                                activeCameraActions = activeCameraActions,
                                onCameraButtonPressChange = onCameraButtonPressChange,
                                spacePanHeld = spacePanHeld,
                            )
                        }
                        Box(
                            modifier =
                                Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(colors.borderSubtle),
                        )
                        Box(modifier = Modifier.weight(0.85f).fillMaxHeight()) {
                            TopDownGridViewport(
                                state = state,
                                descriptorTable = descriptorTable,
                                palette = palette,
                                validation = validation,
                                spacePanHeld = spacePanHeld,
                            )
                        }
                    }
                }
            }
            LevelCenterViewMode.ISO_3D -> {
                Iso3DViewport(
                    state = state,
                    atlas = atlas,
                    palette = palette,
                    activeCameraActions = activeCameraActions,
                    onCameraButtonPressChange = onCameraButtonPressChange,
                    spacePanHeld = spacePanHeld,
                )
            }
            LevelCenterViewMode.TOP_DOWN_2D -> {
                TopDownGridViewport(
                    state = state,
                    descriptorTable = descriptorTable,
                    palette = palette,
                    validation = validation,
                    spacePanHeld = spacePanHeld,
                )
            }
        }
    }
}

// --- Left Dock (Expanded 208dp) & Compact Vertical Tool Rail (Medium/Compact 52dp) ---

@Composable
private fun LevelLeftDock(state: LevelEditorState) {
    StudioPanelSheet(
        edge = HairlineEdge.RIGHT,
        modifier =
            Modifier
                .width(208.dp)
                .fillMaxHeight(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.editMode == LevelEditMode.TERRAIN) {
                StudioSectionHeader(title = "Terrain Tools")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        LabeledToolTile(
                            icon = StudioIcons.ToolSelect,
                            label = "Select",
                            shortcut = "V",
                            selected = state.activeTool == LevelTool.SELECT,
                            modifier = Modifier.weight(1f),
                        ) { state.activeTool = LevelTool.SELECT }
                        LabeledToolTile(
                            icon = StudioIcons.ToolBrush,
                            label = "Brush",
                            shortcut = "B",
                            selected = state.activeTool == LevelTool.BRUSH,
                            modifier = Modifier.weight(1f),
                        ) { state.activeTool = LevelTool.BRUSH }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        LabeledToolTile(
                            icon = StudioIcons.ToolEyedropper,
                            label = "Pick",
                            shortcut = "I",
                            selected = state.activeTool == LevelTool.EYEDROPPER,
                            modifier = Modifier.weight(1f),
                        ) { state.activeTool = LevelTool.EYEDROPPER }
                        LabeledToolTile(
                            icon = StudioIcons.ToolPanHand,
                            label = "Pan",
                            shortcut = "H",
                            selected = state.activeTool == LevelTool.PAN,
                            modifier = Modifier.weight(1f),
                        ) { state.activeTool = LevelTool.PAN }
                    }
                }

                BrushLayerMaskCard(state = state, topDivider = true)
            } else {
                StudioSectionHeader(title = "Gameplay Tools")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        LabeledToolTile(
                            icon = StudioIcons.ToolSelect,
                            label = "Select",
                            shortcut = "V",
                            selected = !state.gameplayPanActive && state.gameplayTool == GameplayTool.SELECT_ENTITY,
                            modifier = Modifier.weight(1f),
                        ) {
                            state.gameplayPanActive = false
                            state.gameplayTool = GameplayTool.SELECT_ENTITY
                        }
                        LabeledToolTile(
                            icon = StudioIcons.WalkableCheck,
                            label = "Walk",
                            shortcut = "B",
                            selected = !state.gameplayPanActive && state.gameplayTool == GameplayTool.PAINT_WALKABILITY,
                            modifier = Modifier.weight(1f),
                        ) {
                            state.gameplayPanActive = false
                            state.gameplayTool = GameplayTool.PAINT_WALKABILITY
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        LabeledToolTile(
                            icon = StudioIcons.EntityPlayer,
                            label = "Player",
                            shortcut = "P",
                            selected = !state.gameplayPanActive && state.gameplayTool == GameplayTool.PLACE_PLAYER,
                            modifier = Modifier.weight(1f),
                        ) {
                            state.gameplayPanActive = false
                            state.gameplayTool = GameplayTool.PLACE_PLAYER
                        }
                        LabeledToolTile(
                            icon = StudioIcons.EntityNpcPatrol,
                            label = "NPC",
                            shortcut = "N",
                            selected = !state.gameplayPanActive && state.gameplayTool == GameplayTool.PLACE_NPC,
                            modifier = Modifier.weight(1f),
                        ) {
                            state.gameplayPanActive = false
                            state.gameplayTool = GameplayTool.PLACE_NPC
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        LabeledToolTile(
                            icon = StudioIcons.EntityWaypoint,
                            label = "Patrol",
                            shortcut = "M",
                            selected = !state.gameplayPanActive && state.gameplayTool == GameplayTool.EDIT_WAYPOINTS,
                            modifier = Modifier.weight(1f),
                        ) {
                            state.gameplayPanActive = false
                            state.gameplayTool = GameplayTool.EDIT_WAYPOINTS
                        }
                        LabeledToolTile(
                            icon = StudioIcons.EntityChest,
                            label = "Object",
                            shortcut = "O",
                            selected = !state.gameplayPanActive && state.gameplayTool == GameplayTool.PLACE_INTERACTABLE,
                            modifier = Modifier.weight(1f),
                        ) {
                            state.gameplayPanActive = false
                            state.gameplayTool = GameplayTool.PLACE_INTERACTABLE
                        }
                    }
                    LabeledToolTile(
                        icon = StudioIcons.ToolPanHand,
                        label = "Pan Viewports",
                        shortcut = "H",
                        selected = state.gameplayPanActive,
                        horizontal = true,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        state.gameplayPanActive = !state.gameplayPanActive
                    }
                }

                GameplayBrushToolOptionsCard(state = state, topDivider = true)
            }

            SharedAtlasStatusCard(state = state, topDivider = true)
        }
    }
}

@Composable
private fun LevelToolRail(state: LevelEditorState) {
    val colors = StudioTheme.colors
    var optionsPopoverExpanded by remember { mutableStateOf(false) }

    StudioPanelSheet(
        edge = HairlineEdge.RIGHT,
        modifier =
            Modifier
                .width(52.dp)
                .fillMaxHeight(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.editMode == LevelEditMode.TERRAIN) {
                StudioIconButton(
                    icon = StudioIcons.ToolSelect,
                    contentDescription = "Select Tool",
                    shortcut = "V",
                    selected = state.activeTool == LevelTool.SELECT,
                    onClick = { state.activeTool = LevelTool.SELECT },
                )
                StudioIconButton(
                    icon = StudioIcons.ToolBrush,
                    contentDescription = "Brush Tool",
                    shortcut = "B",
                    selected = state.activeTool == LevelTool.BRUSH,
                    onClick = { state.activeTool = LevelTool.BRUSH },
                )
                StudioIconButton(
                    icon = StudioIcons.ToolEyedropper,
                    contentDescription = "Pick Cell into Brush",
                    shortcut = "I",
                    selected = state.activeTool == LevelTool.EYEDROPPER,
                    onClick = { state.activeTool = LevelTool.EYEDROPPER },
                )
                StudioIconButton(
                    icon = StudioIcons.ToolPanHand,
                    contentDescription = "Pan Viewports",
                    shortcut = "H",
                    selected = state.activeTool == LevelTool.PAN,
                    onClick = { state.activeTool = LevelTool.PAN },
                )
            } else {
                StudioIconButton(
                    icon = StudioIcons.ToolSelect,
                    contentDescription = "Select Entity",
                    shortcut = "V",
                    selected = !state.gameplayPanActive && state.gameplayTool == GameplayTool.SELECT_ENTITY,
                    onClick = {
                        state.gameplayPanActive = false
                        state.gameplayTool = GameplayTool.SELECT_ENTITY
                    },
                )
                StudioIconButton(
                    icon = StudioIcons.WalkableCheck,
                    contentDescription = "Paint Walkability & Cost",
                    shortcut = "B",
                    selected = !state.gameplayPanActive && state.gameplayTool == GameplayTool.PAINT_WALKABILITY,
                    onClick = {
                        state.gameplayPanActive = false
                        state.gameplayTool = GameplayTool.PAINT_WALKABILITY
                    },
                )
                StudioIconButton(
                    icon = StudioIcons.EntityPlayer,
                    contentDescription = "Place Player Spawn",
                    shortcut = "P",
                    selected = !state.gameplayPanActive && state.gameplayTool == GameplayTool.PLACE_PLAYER,
                    onClick = {
                        state.gameplayPanActive = false
                        state.gameplayTool = GameplayTool.PLACE_PLAYER
                    },
                )
                StudioIconButton(
                    icon = StudioIcons.EntityNpcPatrol,
                    contentDescription = "Place NPC Spawn",
                    shortcut = "N",
                    selected = !state.gameplayPanActive && state.gameplayTool == GameplayTool.PLACE_NPC,
                    onClick = {
                        state.gameplayPanActive = false
                        state.gameplayTool = GameplayTool.PLACE_NPC
                    },
                )
                StudioIconButton(
                    icon = StudioIcons.EntityWaypoint,
                    contentDescription = "Edit Patrol Waypoints",
                    shortcut = "M",
                    selected = !state.gameplayPanActive && state.gameplayTool == GameplayTool.EDIT_WAYPOINTS,
                    onClick = {
                        state.gameplayPanActive = false
                        state.gameplayTool = GameplayTool.EDIT_WAYPOINTS
                    },
                )
                StudioIconButton(
                    icon = StudioIcons.EntityChest,
                    contentDescription = "Place Interactable",
                    shortcut = "O",
                    selected = !state.gameplayPanActive && state.gameplayTool == GameplayTool.PLACE_INTERACTABLE,
                    onClick = {
                        state.gameplayPanActive = false
                        state.gameplayTool = GameplayTool.PLACE_INTERACTABLE
                    },
                )
                StudioIconButton(
                    icon = StudioIcons.ToolPanHand,
                    contentDescription = "Pan Viewports",
                    shortcut = "H",
                    selected = state.gameplayPanActive,
                    onClick = { state.gameplayPanActive = !state.gameplayPanActive },
                )
            }

            Box(
                modifier =
                    Modifier
                        .width(28.dp)
                        .height(1.dp)
                        .background(colors.borderSubtle),
            )

            // Mask / Tool Options Popover Button
            Box {
                StudioIconButton(
                    icon = StudioIcons.PixelGrid,
                    contentDescription = "Brush Mask & Tool Options",
                    selected = optionsPopoverExpanded,
                    onClick = { optionsPopoverExpanded = true },
                )
                DropdownMenu(
                    expanded = optionsPopoverExpanded,
                    onDismissRequest = { optionsPopoverExpanded = false },
                    modifier =
                        Modifier
                            .width(228.dp)
                            .background(colors.surface2),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.editMode == LevelEditMode.TERRAIN) {
                            BrushLayerMaskCard(state = state, topDivider = false)
                        } else {
                            GameplayBrushToolOptionsCard(state = state, topDivider = false)
                        }
                    }
                }
            }

            // Linked Atlas Quick Action
            val hasAtlas = state.resolveAtlasDocument() != null
            StudioIconButton(
                icon = StudioIcons.DocAtlas,
                contentDescription = "Link Shared Atlas...",
                tint = if (hasAtlas) colors.sliceCyan else colors.semanticError,
                onClick = { state.promptLinkAtlas() },
            )
        }
    }
}

@Composable
private fun LabeledToolTile(
    icon: ImageVector,
    label: String,
    shortcut: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val interactionSource = remember(label) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(metrics.radiusSM)
    val bg =
        when {
            selected -> colors.accentSubtle
            hovered -> colors.surface2
            else -> Color.Transparent
        }

    if (horizontal) {
        Row(
            modifier =
                modifier
                    .height(metrics.controlHeight)
                    .clip(shape)
                    .background(bg, shape)
                    .let { mod ->
                        if (selected) mod.border(1.dp, colors.accentBorder, shape) else mod
                    }.hoverable(interactionSource)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    ).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (selected) colors.accentHover else colors.textSecondary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = label,
                    style = typography.caption,
                    color = if (selected) colors.textPrimary else colors.textSecondary,
                )
            }
            StudioKeycap(shortcut)
        }
    } else {
        Column(
            modifier =
                modifier
                    .clip(shape)
                    .background(bg, shape)
                    .let { mod ->
                        if (selected) mod.border(1.dp, colors.accentBorder, shape) else mod
                    }.hoverable(interactionSource)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    ).padding(vertical = 7.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) colors.accentHover else colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label,
                    style = typography.caption,
                    color = if (selected) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                )
                StudioKeycap(shortcut)
            }
        }
    }
}

@Composable
private fun BrushLayerMaskCard(
    state: LevelEditorState,
    topDivider: Boolean,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val mask = state.brushMask
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StudioSectionHeader(
            title = "Mask",
            badge = "${mask.enabledCount}/5",
            topDivider = topDivider,
            trailing = {
                Text(
                    text = "All",
                    style = typography.caption,
                    color = colors.accentHover,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                state.brushMask =
                                    BrushLayerMask(
                                        applyHeight = true,
                                        applyArchetypeAndRotation = true,
                                        applyTopTex = true,
                                        applySkirtTex = true,
                                        applyPanelTexAndFlags = true,
                                    )
                            }.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                Text(
                    text = "None",
                    style = typography.caption,
                    color = colors.textMuted,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                state.brushMask =
                                    BrushLayerMask(
                                        applyHeight = false,
                                        applyArchetypeAndRotation = false,
                                        applyTopTex = false,
                                        applySkirtTex = false,
                                        applyPanelTexAndFlags = false,
                                    )
                            }.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            BrushMaskChip(
                label = "Height",
                checked = mask.applyHeight,
                modifier = Modifier.weight(1f),
            ) {
                state.brushMask = mask.copy(applyHeight = !mask.applyHeight)
            }
            BrushMaskChip(
                label = "Shape",
                checked = mask.applyArchetypeAndRotation,
                modifier = Modifier.weight(1f),
            ) {
                state.brushMask = mask.copy(applyArchetypeAndRotation = !mask.applyArchetypeAndRotation)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            BrushMaskChip(
                label = "Top Tex",
                checked = mask.applyTopTex,
                modifier = Modifier.weight(1f),
            ) {
                state.brushMask = mask.copy(applyTopTex = !mask.applyTopTex)
            }
            BrushMaskChip(
                label = "Skirt",
                checked = mask.applySkirtTex,
                modifier = Modifier.weight(1f),
            ) {
                state.brushMask = mask.copy(applySkirtTex = !mask.applySkirtTex)
            }
        }
        BrushMaskChip(
            label = "Panels & Flags",
            checked = mask.applyPanelTexAndFlags,
            modifier = Modifier.fillMaxWidth(),
        ) {
            state.brushMask = mask.copy(applyPanelTexAndFlags = !mask.applyPanelTexAndFlags)
        }
    }
}

@Composable
private fun BrushMaskChip(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val interactionSource = remember(label) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(metrics.radiusSM)
    val bg =
        when {
            checked -> colors.accentSubtle
            hovered -> colors.surface3
            else -> colors.surface2
        }

    Row(
        modifier =
            modifier
                .height(metrics.controlHeightSM)
                .clip(shape)
                .background(bg, shape)
                .let { mod ->
                    if (checked) mod.border(1.dp, colors.accentBorder, shape) else mod
                }.hoverable(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onToggle,
                ).padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (checked) {
            Icon(
                imageVector = StudioIcons.Check,
                contentDescription = null,
                tint = colors.accentHover,
                modifier = Modifier.size(11.dp),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(colors.textMuted.copy(alpha = 0.5f)),
            )
        }
        Text(
            text = label,
            style = typography.caption,
            color = if (checked) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GameplayBrushToolOptionsCard(
    state: LevelEditorState,
    topDivider: Boolean,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StudioSectionHeader(title = "Brush Options", topDivider = topDivider)
        when (state.gameplayTool) {
            GameplayTool.PAINT_WALKABILITY -> {
                StudioSegmentedControl(
                    items =
                        listOf(
                            SegmentItem(true, "Walkable"),
                            SegmentItem(false, "Blocked"),
                        ),
                    selectedValue = state.paintWalkable,
                    onSelect = { state.paintWalkable = it },
                    compact = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Cost", style = typography.caption, color = colors.textSecondary)
                    StudioNumberStepper(
                        value = state.paintMoveCost,
                        enabled = state.paintWalkable,
                        range = 0..7,
                        onValueChange = { state.paintMoveCost = it },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Flags", style = typography.caption, color = colors.textSecondary)
                    StudioNumberStepper(
                        value = state.paintWalkFlags,
                        range = 0..15,
                        onValueChange = { state.paintWalkFlags = it },
                    )
                }
            }
            GameplayTool.PLACE_INTERACTABLE -> {
                Text("New Object Kind", style = typography.caption, color = colors.textSecondary)
                StudioSegmentedControl(
                    items =
                        listOf(
                            SegmentItem(InteractableKind.CHEST, "Chest"),
                            SegmentItem(InteractableKind.SWITCH, "Switch"),
                            SegmentItem(InteractableKind.SIGN, "Sign"),
                        ),
                    selectedValue = state.pendingInteractableKind,
                    onSelect = { state.pendingInteractableKind = it },
                    compact = true,
                )
            }
            GameplayTool.EDIT_WAYPOINTS -> {
                Text(
                    text = "Tap grid cells to toggle up to 4 patrol waypoints on the selected NPC.",
                    style = typography.caption,
                    color = colors.waypointGold,
                )
            }
            else -> {
                Text(
                    text = "Click any cell or entity marker to inspect and edit tactical properties.",
                    style = typography.caption,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun SharedAtlasStatusCard(
    state: LevelEditorState,
    topDivider: Boolean,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val resolvedDoc = state.resolveAtlasDocument()
    val isLiveTab = state.isLinkedAtlasOpenInTab()
    val atlasLabel =
        state.resolveAtlasFile()?.name
            ?: resolvedDoc?.name
            ?: state.document.atlasPath.ifBlank { "Unlinked" }
    val cardShape = RoundedCornerShape(StudioTheme.metrics.radiusSM)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StudioSectionHeader(
            title = "Shared Atlas",
            topDivider = topDivider,
            trailing = {
                if (isLiveTab && resolvedDoc != null) {
                    StudioBadge(
                        text = "Live Tab",
                        textColor = colors.semanticSuccess,
                        backgroundColor = colors.semanticSuccessSubtle,
                    )
                }
            },
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(colors.surface2, cardShape)
                    .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = StudioIcons.DocAtlas,
                    contentDescription = null,
                    tint = if (resolvedDoc != null) colors.sliceCyan else colors.semanticError,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = atlasLabel,
                    style = typography.monoCaption,
                    color = if (resolvedDoc != null) colors.textPrimary else colors.semanticErrorText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            StudioButton(
                label = "Link Atlas...",
                variant = StudioButtonVariant.Secondary,
                compact = true,
                icon = StudioIcons.FolderOpen,
                modifier = Modifier.fillMaxWidth(),
                onClick = { state.promptLinkAtlas() },
            )
        }
    }
}

// --- Right Inspector Sheet (Expanded/Medium) & Docked Bottom Inspector Sheet (Compact) ---

@Composable
private fun LevelRightInspectorSheet(
    state: LevelEditorState,
    descriptorTable: DescriptorTable?,
    palette: SnesPalette?,
    descriptors: List<TextureDescriptorInfo>,
    validation: LevelValidationResult,
    widthDp: androidx.compose.ui.unit.Dp,
) {
    StudioPanelSheet(
        edge = HairlineEdge.LEFT,
        modifier =
            Modifier
                .width(widthDp)
                .fillMaxHeight(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
        ) {
            if (state.editMode == LevelEditMode.GAMEPLAY) {
                LevelGameplayInspectorContent(
                    state = state,
                    validation = validation,
                )
            } else {
                LevelTerrainInspectorContent(
                    state = state,
                    descriptorTable = descriptorTable,
                    palette = palette,
                    descriptors = descriptors,
                    validation = validation,
                )
            }
        }
    }
}

@Composable
private fun CompactBottomInspectorSheet(
    state: LevelEditorState,
    descriptorTable: DescriptorTable?,
    palette: SnesPalette?,
    descriptors: List<TextureDescriptorInfo>,
    validation: LevelValidationResult,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val gpCount = state.gameplayEntityCount
    val diagCount = validation.allDiagnostics.size

    StudioPanelSheet(
        edge = HairlineEdge.TOP,
        isBottomSheet = true,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(330.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .drawEdgeHairline(HairlineEdge.BOTTOM, colors.borderSubtle)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StudioSegmentedControl(
                items =
                    listOf(
                        SegmentItem(CompactInspectorTab.TERRAIN, "Terrain"),
                        SegmentItem(CompactInspectorTab.TEXTURES, "Textures"),
                        SegmentItem(
                            CompactInspectorTab.GAMEPLAY,
                            "Gameplay",
                            badge = if (gpCount > 0) gpCount.toString() else null,
                        ),
                        SegmentItem(
                            CompactInspectorTab.ALERTS,
                            "Alerts",
                            badge = if (diagCount > 0) diagCount.toString() else null,
                        ),
                    ),
                selectedValue = state.compactInspectorTab,
                onSelect = { tab ->
                    state.compactInspectorTab = tab
                    if (tab == CompactInspectorTab.GAMEPLAY) {
                        state.editMode = LevelEditMode.GAMEPLAY
                    } else if (state.editMode == LevelEditMode.GAMEPLAY) {
                        state.editMode = LevelEditMode.TERRAIN
                    }
                },
                compact = true,
            )

            SharedAtlasCompactPill(state)
        }

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            when (state.compactInspectorTab) {
                CompactInspectorTab.TERRAIN -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TacticalEntitySummaryCard(state = state)
                            TerrainCellGeometrySection(state = state)
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TerrainCellDiagnosticsSection(state = state, validation = validation)
                            TerrainMapGridSection(state = state)
                            TerrainEdgePanelsSection(state = state, topDivider = true)
                        }
                    }
                }
                CompactInspectorTab.TEXTURES -> {
                    TerrainTextureSlotsSection(
                        state = state,
                        descriptorTable = descriptorTable,
                        palette = palette,
                        descriptors = descriptors,
                    )
                }
                CompactInspectorTab.GAMEPLAY -> {
                    LevelGameplayInspectorContent(
                        state = state,
                        validation = validation,
                    )
                }
                CompactInspectorTab.ALERTS -> {
                    if (validation.allDiagnostics.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 12.dp),
                        ) {
                            Icon(
                                imageVector = StudioIcons.StatusOk,
                                contentDescription = null,
                                tint = colors.semanticSuccess,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "All IsoEngine terrain and .gmap gameplay checks passed.",
                                style = typography.bodyRegular,
                                color = colors.textSecondary,
                            )
                        }
                    } else {
                        TerrainCellDiagnosticsSection(
                            state = state,
                            validation = validation,
                            showAllWhenNoCellMatch = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedAtlasCompactPill(state: LevelEditorState) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val resolvedDoc = state.resolveAtlasDocument()
    val name = state.resolveAtlasFile()?.name ?: resolvedDoc?.name ?: "Link Atlas"
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surface2)
                .clickable { state.promptLinkAtlas() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = StudioIcons.DocAtlas,
            contentDescription = null,
            tint = if (resolvedDoc != null) colors.sliceCyan else colors.semanticError,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = name,
            style = typography.monoCaption,
            color = if (resolvedDoc != null) colors.textSecondary else colors.semanticErrorText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- 3D Isometric Software Viewport ---

@Composable
private fun Iso3DViewport(
    state: LevelEditorState,
    atlas: TextureAtlas?,
    palette: SnesPalette?,
    activeCameraActions: Set<IsoCameraControlAction> = emptySet(),
    onCameraButtonPressChange: (IsoCameraControlAction?) -> Unit = {},
    spacePanHeld: Boolean = false,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val textMeasurer = rememberTextMeasurer()

    if (atlas == null || palette == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(colors.bgCanvas).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = StudioIcons.DocAtlas,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = "3D Isometric Viewport requires a linked Atlas Document",
                    style = typography.bodyRegular,
                    color = colors.textSecondary,
                )
                StudioButton(
                    label = "Link Shared Atlas...",
                    variant = StudioButtonVariant.Secondary,
                    icon = StudioIcons.FolderOpen,
                    onClick = { state.promptLinkAtlas() },
                )
            }
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
            renderResult.image.toStudioImageBitmap()
        }

    val panMode = state.isPanToolActive || spacePanHeld
    val currentRenderResult by rememberUpdatedState(renderResult)
    val currentEditMode by rememberUpdatedState(state.editMode)
    val currentActiveTool by rememberUpdatedState(state.activeTool)
    val currentGameplayTool by rememberUpdatedState(state.gameplayTool)
    val currentPanMode by rememberUpdatedState(panMode)
    val currentIsoZoom by rememberUpdatedState(state.isoZoom)
    val currentGrid by rememberUpdatedState(state.document.grid)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.bgCanvas)
                .clipToBounds()
                .padding(8.dp),
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { pos ->
                            if (currentPanMode) return@detectTapGestures
                            val rr = currentRenderResult
                            val gr = currentGrid
                            val (imgX, imgY) =
                                mapCanvasToRenderPixel(
                                    pos = pos,
                                    canvasW = size.width,
                                    canvasH = size.height,
                                    imgW = rr.width,
                                    imgH = rr.height,
                                    zoom = currentIsoZoom,
                                )
                            val idx = rr.pickCellIndex(imgX, imgY)
                            if (idx >= 0) {
                                val col = idx % gr.cols
                                val row = idx / gr.cols
                                state.handleCellInteraction(col, row, isDragContinue = false)
                            }
                        }
                    }.pointerInput(Unit) {
                        var dragStartCoord: Pair<Int, Int>? = null
                        var panAccumX = 0f
                        var panAccumY = 0f
                        detectDragGestures(
                            onDragStart = { pos ->
                                panAccumX = 0f
                                panAccumY = 0f
                                if (currentPanMode) return@detectDragGestures
                                val rr = currentRenderResult
                                val gr = currentGrid
                                val (imgX, imgY) =
                                    mapCanvasToRenderPixel(
                                        pos = pos,
                                        canvasW = size.width,
                                        canvasH = size.height,
                                        imgW = rr.width,
                                        imgH = rr.height,
                                        zoom = currentIsoZoom,
                                    )
                                val idx = rr.pickCellIndex(imgX, imgY)
                                if (idx >= 0) {
                                    val c = idx % gr.cols
                                    val r = idx / gr.cols
                                    dragStartCoord = c to r
                                    if (currentEditMode == LevelEditMode.GAMEPLAY) {
                                        if (currentGameplayTool == GameplayTool.PAINT_WALKABILITY) {
                                            state.beginStroke()
                                        }
                                        state.handleCellInteraction(c, r, isDragContinue = false)
                                    } else if (currentActiveTool == LevelTool.BRUSH) {
                                        state.beginStroke()
                                        state.paintCell(c, r, recordImmediateUndo = false)
                                    } else if (currentActiveTool == LevelTool.SELECT) {
                                        state.selectSingleCell(c, r)
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val rr = currentRenderResult
                                val gr = currentGrid
                                if (currentPanMode) {
                                    val layout =
                                        computeIsoViewportLayout(
                                            size.width,
                                            size.height,
                                            rr.width,
                                            rr.height,
                                            currentIsoZoom,
                                        )
                                    panAccumX += dragAmount.x / layout.scale
                                    panAccumY += dragAmount.y / layout.scale
                                    val stepX = panAccumX.toInt()
                                    val stepY = panAccumY.toInt()
                                    if (stepX != 0 || stepY != 0) {
                                        panAccumX -= stepX
                                        panAccumY -= stepY
                                        state.panCameraBy(stepX, stepY)
                                    }
                                    return@detectDragGestures
                                }

                                val (imgX, imgY) =
                                    mapCanvasToRenderPixel(
                                        pos = change.position,
                                        canvasW = size.width,
                                        canvasH = size.height,
                                        imgW = rr.width,
                                        imgH = rr.height,
                                        zoom = currentIsoZoom,
                                    )
                                val idx = rr.pickCellIndex(imgX, imgY)
                                if (idx >= 0) {
                                    val c = idx % gr.cols
                                    val r = idx / gr.cols
                                    if (currentEditMode == LevelEditMode.GAMEPLAY) {
                                        state.handleCellInteraction(c, r, isDragContinue = true)
                                    } else if (currentActiveTool == LevelTool.BRUSH) {
                                        state.paintCell(c, r, recordImmediateUndo = false)
                                    } else if (currentActiveTool == LevelTool.SELECT) {
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
            val layout =
                computeIsoViewportLayout(
                    canvasW = size.width.roundToInt(),
                    canvasH = size.height.roundToInt(),
                    imgW = renderResult.width,
                    imgH = renderResult.height,
                    zoom = state.isoZoom,
                )

            drawRect(
                color = colors.bgCanvas,
                topLeft = Offset(layout.offX.toFloat(), layout.offY.toFloat()),
                size = Size(layout.drawW.toFloat(), layout.drawH.toFloat()),
            )

            // Crisp 0dp nearest-neighbor SuperFX software preview
            drawImage(
                image = composeBitmap,
                dstOffset = IntOffset(layout.offX, layout.offY),
                dstSize = IntSize(layout.drawW, layout.drawH),
                filterQuality = FilterQuality.None,
            )

            // Subtle frame around the 256x160 SNES viewport
            drawRect(
                color = colors.borderSubtle,
                topLeft = Offset(layout.offX.toFloat(), layout.offY.toFloat()),
                size = Size(layout.drawW.toFloat(), layout.drawH.toFloat()),
                style = Stroke(width = 1f),
            )

            val cols = state.document.grid.cols
            clipRect(
                left = layout.offX.toFloat(),
                top = layout.offY.toFloat(),
                right = (layout.offX + layout.drawW).toFloat(),
                bottom = (layout.offY + layout.drawH).toFloat(),
            ) {
                for ((selC, selR) in state.selectedCells) {
                    val cellIdx = selR * cols + selC
                    if (cellIdx in renderResult.cellFloorPolygons.indices) {
                        val poly = renderResult.cellFloorPolygons[cellIdx]
                        if (poly.size >= 4) {
                            val path =
                                Path().apply {
                                    moveTo(layout.offX + poly[0].x * layout.scale, layout.offY + poly[0].y * layout.scale)
                                    for (k in 1 until poly.size) {
                                        lineTo(layout.offX + poly[k].x * layout.scale, layout.offY + poly[k].y * layout.scale)
                                    }
                                    close()
                                }
                            drawPath(
                                path = path,
                                color = if (colors.isDark) colors.accentHover else colors.accentPrimary,
                                style = Stroke(width = 2f),
                            )
                        }
                    }
                }
            }

            val gameplay = state.document.gameplay
            if (state.showGameplayOverlay && gameplay != null) {
                drawIso3DGameplayOverlay(
                    grid = state.document.grid,
                    gameplay = gameplay,
                    renderResult = renderResult,
                    layout = layout,
                    cameraYaw = state.cameraYaw,
                    selectedCells = state.selectedCells,
                    selectedNpcIndex = state.selectedNpcIndex,
                    selectedInteractableIndex = state.selectedInteractableIndex,
                    colors = colors,
                    textMeasurer = textMeasurer,
                )
            }
        }

        // Collision-safe Top HUD Bar (Telemetry Pill + Zoom Pod)
        Row(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewportHudPill(
                text = "3D Iso · 256x160 · Yaw ${state.cameraYaw * 3}° · Pan (${state.cameraPanX}, ${state.cameraPanY})",
                modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp),
            )

            ViewportZoomPod(
                currentZoom = state.isoZoom,
                onSelectZoom = { state.isoZoom = it },
                onReset = {
                    state.isoZoom = 0f
                    state.resetCamera()
                },
            )
        }

        // Bottom-Right Tactile Camera Orbit/Pan Pod
        IsoCameraControlsOverlay(
            state = state,
            activeActions = activeCameraActions,
            onButtonPressChange = onCameraButtonPressChange,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

@Composable
private fun ViewportHudPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val shape = RoundedCornerShape(StudioTheme.metrics.radiusSM)
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(colors.bgApp.copy(alpha = 0.86f), shape)
                .border(1.dp, colors.borderSubtle, shape)
                .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = typography.monoCaption,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ViewportZoomPod(
    currentZoom: Float,
    onSelectZoom: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val podShape = RoundedCornerShape(StudioTheme.metrics.radiusSM)
    val presets =
        listOf(
            0f to "Fit",
            1f to "1x",
            2f to "2x",
            3f to "3x",
        )

    Row(
        modifier =
            modifier
                .clip(podShape)
                .background(colors.bgApp.copy(alpha = 0.88f), podShape)
                .border(1.dp, colors.borderSubtle, podShape)
                .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((zoomVal, label) in presets) {
            val isSelected = abs(currentZoom - zoomVal) < 0.05f
            val itemShape = RoundedCornerShape(StudioTheme.metrics.radiusXS)
            Box(
                modifier =
                    Modifier
                        .height(22.dp)
                        .clip(itemShape)
                        .background(if (isSelected) colors.surface3 else Color.Transparent, itemShape)
                        .clickable { onSelectZoom(zoomVal) }
                        .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = typography.monoCaption,
                    color = if (isSelected) colors.textPrimary else colors.textMuted,
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(StudioTheme.metrics.radiusXS))
                    .clickable(onClick = onReset),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = StudioIcons.ZoomFit,
                contentDescription = "Reset Viewport",
                tint = colors.textSecondary,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun IsoCameraControlsOverlay(
    state: LevelEditorState,
    activeActions: Set<IsoCameraControlAction>,
    onButtonPressChange: (IsoCameraControlAction?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val podShape = RoundedCornerShape(metrics.radiusMD)
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
                .clip(podShape)
                .background(colors.surface1.copy(alpha = 0.88f), podShape)
                .border(1.dp, colors.borderStrong, podShape)
                .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (row in listOf(row1, row2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (action in row) {
                    val isActive = action in activeActions
                    IsoCameraOverlayButton(
                        action = action,
                        isActive = isActive,
                        touchComfort = metrics.touchComfort,
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
    touchComfort: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val btnW = if (touchComfort) 44.dp else 36.dp
    val btnH = if (touchComfort) 36.dp else 26.dp
    val shape = RoundedCornerShape(StudioTheme.metrics.radiusXS)
    val bg = if (isActive) colors.accentPrimary else colors.surface2
    val fg = if (isActive) colors.onAccent else colors.textPrimary

    Row(
        modifier =
            Modifier
                .size(width = btnW, height = btnH)
                .clip(shape)
                .background(bg, shape)
                .let { mod ->
                    if (isActive) mod.border(1.dp, colors.accentHover, shape) else mod
                }.pointerInput(action) {
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
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = action.description,
            tint = fg,
            modifier = Modifier.size(12.dp),
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = action.keyLabel,
            style = typography.monoCaption,
            color = if (isActive) colors.onAccent else colors.textMuted,
        )
    }
}

// --- 2D Top-Down Grid Viewport with Coordinate Rulers & Gameplay Overlay ---

@Composable
private fun TopDownGridViewport(
    state: LevelEditorState,
    descriptorTable: DescriptorTable?,
    palette: SnesPalette?,
    validation: LevelValidationResult,
    spacePanHeld: Boolean = false,
) {
    val colors = StudioTheme.colors
    val grid = state.document.grid
    val textMeasurer = rememberTextMeasurer()
    val panMode = state.isPanToolActive || spacePanHeld

    // Precompute average RGB swatch per descriptor index directly via DescriptorTable.extractPixels
    val texTintByIndex =
        remember(descriptorTable, palette) {
            if (descriptorTable == null || palette == null) {
                emptyMap()
            } else {
                buildMap {
                    for ((idx, desc) in descriptorTable.entries.withIndex()) {
                        val pixels = descriptorTable.extractPixels(desc)
                        var rSum = 0L
                        var gSum = 0L
                        var bSum = 0L
                        var count = 0L
                        for (b in pixels) {
                            val slot = b.toInt() and 0x0F
                            if (slot != 0) {
                                val rgb = palette[slot].rgb888
                                rSum += (rgb shr 16) and 0xFF
                                gSum += (rgb shr 8) and 0xFF
                                bSum += rgb and 0xFF
                                count++
                            }
                        }
                        if (count > 0) {
                            put(
                                idx,
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

    val rulerStyle =
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
        )
    val elevStyle =
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
        )

    val currentGrid by rememberUpdatedState(grid)
    val currentEditMode by rememberUpdatedState(state.editMode)
    val currentActiveTool by rememberUpdatedState(state.activeTool)
    val currentGameplayTool by rememberUpdatedState(state.gameplayTool)
    val currentPanMode by rememberUpdatedState(panMode)
    val currentGridZoom by rememberUpdatedState(state.gridZoom)
    val currentGridPanOffset by rememberUpdatedState(state.gridPanOffset)
    var gridCanvasSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(state.gridZoom, grid.cols, grid.rows, gridCanvasSize) {
        if (gridCanvasSize.width > 0 && gridCanvasSize.height > 0) {
            state.clampGridPan(gridCanvasSize.width, gridCanvasSize.height)
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.bgCanvas)
                .clipToBounds()
                .padding(8.dp),
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { sz ->
                        gridCanvasSize = sz
                        if (sz.width > 0 && sz.height > 0) {
                            state.clampGridPan(sz.width, sz.height)
                        }
                    }.pointerInput(Unit) {
                        detectTapGestures { pos ->
                            if (currentPanMode) return@detectTapGestures
                            val gr = currentGrid
                            val coord =
                                mapCanvasToGridCoord(
                                    pos = pos,
                                    canvasW = size.width,
                                    canvasH = size.height,
                                    cols = gr.cols,
                                    rows = gr.rows,
                                    zoom = currentGridZoom,
                                    panOffset = currentGridPanOffset,
                                )
                            if (coord != null) {
                                state.handleCellInteraction(coord.first, coord.second, isDragContinue = false)
                            }
                        }
                    }.pointerInput(Unit) {
                        var startCoord: Pair<Int, Int>? = null
                        detectDragGestures(
                            onDragStart = { pos ->
                                if (currentPanMode) return@detectDragGestures
                                val gr = currentGrid
                                val coord =
                                    mapCanvasToGridCoord(
                                        pos = pos,
                                        canvasW = size.width,
                                        canvasH = size.height,
                                        cols = gr.cols,
                                        rows = gr.rows,
                                        zoom = currentGridZoom,
                                        panOffset = currentGridPanOffset,
                                    )
                                if (coord != null) {
                                    startCoord = coord
                                    if (currentEditMode == LevelEditMode.GAMEPLAY) {
                                        if (currentGameplayTool == GameplayTool.PAINT_WALKABILITY) {
                                            state.beginStroke()
                                        }
                                        state.handleCellInteraction(coord.first, coord.second, isDragContinue = false)
                                    } else if (currentActiveTool == LevelTool.BRUSH) {
                                        state.beginStroke()
                                        state.paintCell(coord.first, coord.second, recordImmediateUndo = false)
                                    } else if (currentActiveTool == LevelTool.SELECT) {
                                        state.selectSingleCell(coord.first, coord.second)
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (currentPanMode) {
                                    state.panGridBy(dragAmount, canvasW = size.width, canvasH = size.height)
                                    return@detectDragGestures
                                }
                                val gr = currentGrid
                                val coord =
                                    mapCanvasToGridCoord(
                                        pos = change.position,
                                        canvasW = size.width,
                                        canvasH = size.height,
                                        cols = gr.cols,
                                        rows = gr.rows,
                                        zoom = currentGridZoom,
                                        panOffset = currentGridPanOffset,
                                    )
                                if (coord != null) {
                                    if (currentEditMode == LevelEditMode.GAMEPLAY) {
                                        state.handleCellInteraction(coord.first, coord.second, isDragContinue = true)
                                    } else if (currentActiveTool == LevelTool.BRUSH) {
                                        state.paintCell(coord.first, coord.second, recordImmediateUndo = false)
                                    } else if (currentActiveTool == LevelTool.SELECT) {
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
            val layout =
                computeGridViewportLayout(
                    canvasW = size.width.roundToInt(),
                    canvasH = size.height.roundToInt(),
                    cols = grid.cols,
                    rows = grid.rows,
                    zoom = state.gridZoom,
                    panOffset = state.gridPanOffset,
                )
            val cellPix = layout.cellPix
            val selectedCols = state.selectedCells.mapTo(HashSet()) { it.first }
            val selectedRows = state.selectedCells.mapTo(HashSet()) { it.second }

            val selectionAccent = if (colors.isDark) colors.accentHover else colors.accentPrimary

            // 1. Top Column Ruler (0 .. cols-1)
            for (c in 0 until grid.cols) {
                val cx = layout.originX + (c + 0.5f) * cellPix
                val rLayout = textMeasurer.measure(c.toString(), style = rulerStyle)
                val ry = (layout.originY - rLayout.size.height - 3f).coerceAtLeast(24f)
                if (cx in 0f..size.width) {
                    drawText(
                        textLayoutResult = rLayout,
                        color = if (c in selectedCols) selectionAccent else colors.textMuted,
                        topLeft = Offset(cx - rLayout.size.width * 0.5f, ry),
                    )
                }
            }

            // 2. Left Row Ruler (0 .. rows-1)
            for (r in 0 until grid.rows) {
                val cy = layout.originY + (r + 0.5f) * cellPix
                val rLayout = textMeasurer.measure(r.toString(), style = rulerStyle)
                val rx = (layout.originX - rLayout.size.width - 5f).coerceAtLeast(2f)
                if (cy in 24f..size.height) {
                    drawText(
                        textLayoutResult = rLayout,
                        color = if (r in selectedRows) selectionAccent else colors.textMuted,
                        topLeft = Offset(rx, cy - rLayout.size.height * 0.5f),
                    )
                }
            }

            // 3. Sharp 0dp Cells, Perimeter EdgePanels, Elevation & Slope Vectors
            for (r in 0 until grid.rows) {
                for (c in 0 until grid.cols) {
                    val cell = grid.cellAt(c, r)
                    val x = layout.originX + c * cellPix
                    val y = layout.originY + r * cellPix
                    val baseCol =
                        when (val lookup = descriptorTable?.lookup(cell.topTex)) {
                            is DescriptorLookup.Found -> texTintByIndex[lookup.index] ?: colors.surface2
                            is DescriptorLookup.SolidColor ->
                                if (lookup.paletteSlot == 0 || palette == null) {
                                    colors.surface2
                                } else {
                                    Color(palette[lookup.paletteSlot].argb)
                                }
                            is DescriptorLookup.Unknown -> colors.semanticErrorSubtle
                            else -> colors.surface2
                        }
                    val shadeBoost = (cell.baseHeight * 0.04f).coerceAtMost(0.35f)
                    val shaded =
                        Color(
                            red = (baseCol.red + shadeBoost).coerceIn(0f, 1f),
                            green = (baseCol.green + shadeBoost).coerceIn(0f, 1f),
                            blue = (baseCol.blue + shadeBoost).coerceIn(0f, 1f),
                        )

                    drawRect(
                        color = shaded,
                        topLeft = Offset(x + 0.75f, y + 0.75f),
                        size = Size((cellPix - 1.5f).coerceAtLeast(1f), (cellPix - 1.5f).coerceAtLeast(1f)),
                    )

                    // Perimeter Edge Panels
                    val panelColor = if (cell.panels.tall2h) colors.aliasAmber else colors.sliceCyan
                    val tickThick = (cellPix * 0.11f).coerceIn(2f, 4.5f)
                    if (cell.panels.north) {
                        drawRect(panelColor, Offset(x + 1.5f, y + 1.5f), Size(cellPix - 3f, tickThick))
                    }
                    if (cell.panels.south) {
                        drawRect(panelColor, Offset(x + 1.5f, y + cellPix - 1.5f - tickThick), Size(cellPix - 3f, tickThick))
                    }
                    if (cell.panels.west) {
                        drawRect(panelColor, Offset(x + 1.5f, y + 1.5f), Size(tickThick, cellPix - 3f))
                    }
                    if (cell.panels.east) {
                        drawRect(panelColor, Offset(x + cellPix - 1.5f - tickThick, y + 1.5f), Size(tickThick, cellPix - 3f))
                    }

                    val cellLuma = shaded.red * 0.299f + shaded.green * 0.587f + shaded.blue * 0.114f
                    val cellGlyphInk =
                        if (cellLuma > 0.58f) {
                            Color(0xFF0F172A).copy(alpha = 0.82f)
                        } else {
                            Color.White.copy(alpha = 0.85f)
                        }

                    // Elevation micro-badge (1h, 2h...) when cell is large enough
                    if (cell.baseHeight > 0 && cellPix >= 26f) {
                        val hLayout = textMeasurer.measure("${cell.baseHeight}h", style = elevStyle)
                        drawText(
                            textLayoutResult = hLayout,
                            color = cellGlyphInk,
                            topLeft = Offset(x + 3f, y + 2f),
                        )
                    }

                    // Crisp Vector Direction Arrow for Asymmetric Slopes / Stairs
                    if (!cell.archetype.isRotationallySymmetric) {
                        val cx = x + cellPix * 0.5f
                        val cy = y + cellPix * 0.5f
                        val rad = (cellPix * 0.24f).coerceIn(3.5f, 9f)
                        val (dx, dy) =
                            when (cell.rotation) {
                                CellRotation.ROT_0 -> 0f to -1f
                                CellRotation.ROT_90 -> 1f to 0f
                                CellRotation.ROT_180 -> 0f to 1f
                                CellRotation.ROT_270 -> -1f to 0f
                            }
                        val px = -dy
                        val py = dx
                        val tail = Offset(cx - dx * rad * 0.65f, cy - dy * rad * 0.65f)
                        val tip = Offset(cx + dx * rad, cy + dy * rad)
                        val wing = rad * 0.55f
                        drawLine(
                            color = cellGlyphInk,
                            start = tail,
                            end = tip,
                            strokeWidth = 1.6f,
                            cap = StrokeCap.Round,
                        )
                        val head =
                            Path().apply {
                                moveTo(tip.x - dx * wing + px * wing * 0.7f, tip.y - dy * wing + py * wing * 0.7f)
                                lineTo(tip.x, tip.y)
                                lineTo(tip.x - dx * wing - px * wing * 0.7f, tip.y - dy * wing - py * wing * 0.7f)
                            }
                        drawPath(
                            path = head,
                            color = cellGlyphInk,
                            style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }

                    // Diagnostic cell outline
                    when (state.hasDiagnosticAt(c, r, validation)) {
                        DiagnosticSeverity.ERROR ->
                            drawRect(
                                color = colors.semanticError,
                                topLeft = Offset(x + 1f, y + 1f),
                                size = Size(cellPix - 2f, cellPix - 2f),
                                style = Stroke(width = 2f),
                            )
                        DiagnosticSeverity.WARNING ->
                            drawRect(
                                color = colors.semanticWarning,
                                topLeft = Offset(x + 1f, y + 1f),
                                size = Size(cellPix - 2f, cellPix - 2f),
                                style = Stroke(width = 1.5f),
                            )
                        null -> {}
                    }
                }
            }

            // 4. Tactical Gameplay Overlay (Walkability, Costs, Patrol Loops, Spawns, Objects)
            val gameplay = state.document.gameplay
            if (state.showGameplayOverlay && gameplay != null) {
                drawTopDownGameplayOverlay(
                    grid = grid,
                    gameplay = gameplay,
                    layout = layout,
                    selectedCells = state.selectedCells,
                    selectedNpcIndex = state.selectedNpcIndex,
                    selectedInteractableIndex = state.selectedInteractableIndex,
                    colors = colors,
                    textMeasurer = textMeasurer,
                )
            }

            // 5. Crisp Selection Frame on top
            for ((selC, selR) in state.selectedCells) {
                if (grid.isInBounds(selC, selR)) {
                    val x = layout.originX + selC * cellPix
                    val y = layout.originY + selR * cellPix
                    drawRect(
                        color = selectionAccent,
                        topLeft = Offset(x + 1.2f, y + 1.2f),
                        size = Size((cellPix - 2.4f).coerceAtLeast(1f), (cellPix - 2.4f).coerceAtLeast(1f)),
                        style = Stroke(width = 2.2f),
                    )
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewportHudPill(
                text = "2D Grid · ${grid.cols}x${grid.rows} · N Up",
                modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp),
            )

            ViewportZoomPod(
                currentZoom = state.gridZoom,
                onSelectZoom = { zoomVal ->
                    state.gridZoom = zoomVal
                    if (zoomVal <= 0f) {
                        state.gridPanOffset = Offset.Zero
                    }
                },
                onReset = { state.resetGridView() },
            )
        }
    }
}
