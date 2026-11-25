package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.SFX_ATLAS_EXTENSION
import dev.secondsun.sfxstudio.core.atlas.SFX_PROJ_EXTENSION
import dev.secondsun.sfxstudio.desktop.atlas.AtlasCanvasTool
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.ui.TextureComposeRenderer
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.SegmentItem
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioBadge
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioIconButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSegmentedControl
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.HairlineEdge
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.checkerboardBackground
import dev.secondsun.sfxstudio.desktop.ui.theme.drawEdgeHairline
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun LayoutTab(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val isEmptyAtlas =
        state.atlas.textures.isEmpty() &&
            state.atlas.slices.isEmpty() &&
            state.atlas.aliases.isEmpty()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.bgCanvas),
    ) {
        LayoutToolbar(state)

        if (isEmptyAtlas) {
            AtlasOnboardingStage(
                state = state,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            AtlasCanvasStage(
                state = state,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayoutToolbar(state: AtlasEditorState) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    val sizeBytes = state.atlas.sizeBytes
    val kb = sizeBytes / 1024
    val sizeText = if (kb > 0) "$kb KB" else "$sizeBytes B"
    val overBank71 = sizeBytes > AtlasDocument.BANK_71_MAX_ATLAS_BYTES

    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.surface1)
                .drawEdgeHairline(HairlineEdge.BOTTOM, colors.borderSubtle)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 1. Canvas Tool Switcher (Move [V] | Pan [H])
        StudioSegmentedControl(
            items =
                listOf(
                    SegmentItem(AtlasCanvasTool.MOVE, "Move", icon = StudioIcons.ToolSelect),
                    SegmentItem(AtlasCanvasTool.PAN, "Pan", icon = StudioIcons.ToolPanHand),
                ),
            selectedValue = state.canvasTool,
            onSelect = { state.canvasTool = it },
            compact = true,
        )

        // 2. Sheet Dimensions, Auto-Fit/Pack & Bank $71 Budget
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DimensionDropdown("W", state.atlas.width) { newW ->
                state.setAtlasDimensions(newW, state.atlas.height)
            }
            Text("x", style = typography.monoCaption, color = colors.textMuted)
            DimensionDropdown("H", state.atlas.height) { newH ->
                state.setAtlasDimensions(state.atlas.width, newH)
            }

            StudioButton(
                label = "Auto-Fit",
                variant = StudioButtonVariant.Secondary,
                compact = true,
                onClick = { state.autoFit() },
            )
            StudioButton(
                label = "Auto-Pack",
                variant = StudioButtonVariant.Secondary,
                compact = true,
                onClick = { state.autoPack() },
            )

            StudioBadge(
                text = if (overBank71) "$sizeText > Bank $71" else "$sizeText / 32 KB",
                textColor = if (overBank71) colors.semanticWarningText else colors.semanticSuccess,
                backgroundColor = if (overBank71) colors.semanticWarningSubtle else colors.semanticSuccessSubtle,
                borderColor =
                    if (overBank71) {
                        colors.semanticWarning.copy(alpha = 0.45f)
                    } else {
                        colors.semanticSuccess.copy(alpha = 0.35f)
                    },
                monospace = true,
            )
        }

        // 3. Grid Snap Segmented Pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Snap", style = typography.caption, color = colors.textSecondary)
            StudioSegmentedControl(
                items = listOf(4, 8, 16).map { snap -> SegmentItem(snap, "${snap}px") },
                selectedValue = state.gridSnap,
                onSelect = { state.gridSnap = it },
                compact = true,
            )
        }

        // 4. Zoom Controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StudioIconButton(
                icon = StudioIcons.ZoomOut,
                contentDescription = "Zoom Out",
                size = metrics.controlHeightSM,
                enabled = state.zoom > 1f,
                onClick = { state.zoom = (state.zoom - 1f).coerceAtLeast(1f) },
            )
            StudioSegmentedControl(
                items = listOf(1f, 2f, 3f, 4f).map { z -> SegmentItem(z, "${z.toInt()}x") },
                selectedValue = state.zoom,
                onSelect = { state.zoom = it },
                compact = true,
            )
            StudioIconButton(
                icon = StudioIcons.ZoomIn,
                contentDescription = "Zoom In",
                size = metrics.controlHeightSM,
                enabled = state.zoom < 8f,
                onClick = { state.zoom = (state.zoom + 1f).coerceAtMost(8f) },
            )
            StudioIconButton(
                icon = StudioIcons.ZoomFit,
                contentDescription = "Reset Pan & Zoom",
                size = metrics.controlHeightSM,
                onClick = {
                    state.canvasPanOffset = Offset.Zero
                    state.zoom = 2f
                },
            )
        }
    }
}

@Composable
private fun DimensionDropdown(
    label: String,
    currentValue: Int,
    onSelected: (Int) -> Unit,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    var expanded by remember { mutableStateOf(false) }
    val dimensions = listOf(16, 32, 64, 128, 256)
    val pillShape = RoundedCornerShape(metrics.radiusSM)

    Box {
        Row(
            modifier =
                Modifier
                    .height(metrics.controlHeightSM)
                    .clip(pillShape)
                    .background(colors.surface0, pillShape)
                    .border(1.dp, colors.borderSubtle, pillShape)
                    .clickable { expanded = true }
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "$label:",
                style = typography.caption,
                color = colors.textMuted,
            )
            Text(
                text = "$currentValue",
                style = typography.monoCaption,
                color = colors.textPrimary,
            )
            Icon(
                imageVector = StudioIcons.ChevronDown,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(12.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colors.surface2),
        ) {
            for (dim in dimensions) {
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        onSelected(dim)
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${dim}px",
                            style = typography.monoBody,
                            color = if (dim == currentValue) colors.accentHover else colors.textPrimary,
                        )
                        if (dim == currentValue) {
                            Icon(
                                imageVector = StudioIcons.Check,
                                contentDescription = null,
                                tint = colors.accentHover,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AtlasOnboardingStage(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    BoxWithConstraints(
        modifier =
            modifier
                .background(colors.bgCanvas)
                .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        // Subtle blueprint coordinate grid backdrop
        val majorGridCol = colors.borderSubtle.copy(alpha = if (colors.isDark) 0.52f else 0.72f)
        val minorGridCol = colors.borderSubtle.copy(alpha = if (colors.isDark) 0.25f else 0.40f)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val minorStep = 24.dp.toPx()
            val majorStep = 96.dp.toPx()
            var x = 0f
            while (x < size.width) {
                val isMajor = ((x / majorStep) - (x / majorStep).roundToInt()).let { kotlin.math.abs(it) < 0.02f }
                drawLine(
                    color = if (isMajor) majorGridCol else minorGridCol,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
                x += minorStep
            }
            var y = 0f
            while (y < size.height) {
                val isMajor = ((y / majorStep) - (y / majorStep).roundToInt()).let { kotlin.math.abs(it) < 0.02f }
                drawLine(
                    color = if (isMajor) majorGridCol else minorGridCol,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                y += minorStep
            }
        }

        val useHorizontalDeck = maxWidth >= 660.dp

        Column(
            modifier =
                Modifier
                    .widthIn(max = 820.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Hero Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(metrics.radiusMD))
                            .background(colors.accentSubtle)
                            .border(1.dp, colors.accentBorder, RoundedCornerShape(metrics.radiusMD)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = StudioIcons.BrandChip,
                        contentDescription = null,
                        tint = colors.accentHover,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Text(
                    text = "SuperFX Asset & Level Studio",
                    style = typography.titleHero,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Pack 4-bit indexed BGR555 Texture Atlases and author isometric tactical Levels for GSU / FX3.",
                    style = typography.bodyRegular,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            // 3 Actionable Quick-Start Cards
            if (useHorizontalDeck) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OnboardingQuickCard(
                        icon = StudioIcons.Import,
                        iconTint = colors.accentHover,
                        title = "Import Source Textures",
                        description = "Import power-of-two .png / .bmp bitmaps (16-256px) with automatic BGR555 quantization.",
                        buttonLabel = "Import Bitmaps...",
                        primary = true,
                        onClick = { state.promptImportFile() },
                        modifier = Modifier.weight(1f),
                    )
                    if (state.studio != null) {
                        OnboardingQuickCard(
                            icon = StudioIcons.DocLevel,
                            iconTint = colors.interactEmerald,
                            title = "New Isometric Level",
                            description = "Create a 4x4 to 16x16 .sfxlevel terrain & .gmap tactical RPG document.",
                            buttonLabel = "Create Level...",
                            primary = false,
                            onClick = { state.studio.showNewLevelModal = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OnboardingQuickCard(
                        icon = StudioIcons.FolderOpen,
                        iconTint = colors.sliceCyan,
                        title = "Open Existing Project",
                        description = "Load an authored .sfxproj, .sfxatlas, or .sfxlevel file from disk.",
                        buttonLabel = "Open Document...",
                        primary = false,
                        onClick = {
                            if (state.studio != null) {
                                state.studio.promptOpenDocument()
                            } else {
                                state.platform.requestOpenFile(
                                    title = "Open Atlas Document",
                                    initialDir = state.lastImportDirectory,
                                    allowedExtensions = setOf(SFX_PROJ_EXTENSION, SFX_ATLAS_EXTENSION),
                                ) { picked ->
                                    if (picked != null) state.openProject(picked)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OnboardingQuickCard(
                        icon = StudioIcons.Import,
                        iconTint = colors.accentHover,
                        title = "Import Source Textures",
                        description = "Import power-of-two .png / .bmp bitmaps (16-256px) with automatic BGR555 quantization.",
                        buttonLabel = "Import Bitmaps...",
                        primary = true,
                        onClick = { state.promptImportFile() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.studio != null) {
                        OnboardingQuickCard(
                            icon = StudioIcons.DocLevel,
                            iconTint = colors.interactEmerald,
                            title = "New Isometric Level",
                            description = "Create a 4x4 to 16x16 .sfxlevel terrain & .gmap tactical RPG document.",
                            buttonLabel = "Create Level...",
                            primary = false,
                            onClick = { state.studio.showNewLevelModal = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OnboardingQuickCard(
                        icon = StudioIcons.FolderOpen,
                        iconTint = colors.sliceCyan,
                        title = "Open Existing Project",
                        description = "Load an authored .sfxproj, .sfxatlas, or .sfxlevel file from disk.",
                        buttonLabel = "Open Document...",
                        primary = false,
                        onClick = {
                            if (state.studio != null) {
                                state.studio.promptOpenDocument()
                            } else {
                                state.platform.requestOpenFile(
                                    title = "Open Atlas Document",
                                    initialDir = state.lastImportDirectory,
                                    allowedExtensions = setOf(SFX_PROJ_EXTENSION, SFX_ATLAS_EXTENSION),
                                ) { picked ->
                                    if (picked != null) state.openProject(picked)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Hardware Spec Strip
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StudioBadge(
                    text = "SNES BGR555 (Slot 0 = \$0000 Transparent, \$0001 Black)",
                    monospace = true,
                    backgroundColor = colors.surface1,
                    borderColor = colors.borderSubtle,
                )
                StudioBadge(
                    text = "Max 240 Descriptors (\$00-\$EF)",
                    monospace = true,
                    backgroundColor = colors.surface1,
                    borderColor = colors.borderSubtle,
                )
                StudioBadge(
                    text = "32 KB Bank \$71 Staging Budget",
                    monospace = true,
                    backgroundColor = colors.surface1,
                    borderColor = colors.borderSubtle,
                )
            }
        }
    }
}

@Composable
private fun OnboardingQuickCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    buttonLabel: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val cardShape = RoundedCornerShape(metrics.radiusMD)

    Column(
        modifier =
            modifier
                .clip(cardShape)
                .background(if (hovered) colors.surface3.copy(alpha = 0.7f) else colors.surface2, cardShape)
                .border(
                    1.dp,
                    if (hovered) colors.borderStrong else colors.borderSubtle,
                    cardShape,
                ).hoverable(interactionSource)
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(metrics.radiusSM))
                        .background(iconTint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = title,
                style = typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = description,
            style = typography.caption,
            color = colors.textSecondary,
        )

        Spacer(modifier = Modifier.height(2.dp))

        StudioButton(
            label = buttonLabel,
            variant = if (primary) StudioButtonVariant.Primary else StudioButtonVariant.Secondary,
            icon = icon,
            onClick = onClick,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AtlasCanvasStage(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val density = LocalDensity.current
    val zoom = state.zoom
    val rulerThickness = 18.dp
    val sheetWidthDp = (state.atlas.width * zoom).dp
    val sheetHeightDp = (state.atlas.height * zoom).dp
    val pixelsPerTexel = zoom * density.density

    var draggingTextureIndex by remember { mutableStateOf<Int?>(null) }
    var dragPixelOffset by remember { mutableStateOf(Offset.Zero) }

    val curTool by rememberUpdatedState(state.canvasTool)
    val curZoom by rememberUpdatedState(state.zoom)
    val curGridSnap by rememberUpdatedState(state.gridSnap)

    BoxWithConstraints(
        modifier =
            modifier
                .background(colors.bgCanvas)
                .clipToBounds()
                .pointerInput("scrollZoomPan") {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val scrollDelta = event.changes.firstOrNull()?.scrollDelta ?: continue
                                val ctrl = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                                if (ctrl) {
                                    val nextZ =
                                        if (scrollDelta.y < 0f) {
                                            (curZoom + 1f).coerceAtMost(8f)
                                        } else {
                                            (curZoom - 1f).coerceAtLeast(1f)
                                        }
                                    state.zoom = nextZ
                                } else {
                                    state.canvasPanOffset += Offset(-scrollDelta.x * 24f, -scrollDelta.y * 24f)
                                }
                            }
                        }
                    }
                }.pointerInput(Unit) {
                    // Empty-stage background pan gesture
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        down.consume()
                        var lastPos = down.position
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            val delta = ch.position - lastPos
                            lastPos = ch.position
                            ch.consume()
                            state.canvasPanOffset += delta
                        }
                    }
                },
    ) {
        val totalAssemblyW = rulerThickness + sheetWidthDp
        val totalAssemblyH = rulerThickness + sheetHeightDp

        // Center when fitting; anchor near top-left (14.dp) when sheet is taller/wider than stage so (0, 0) never clips
        val baseLeftDp = ((maxWidth - totalAssemblyW) * 0.5f).coerceAtLeast(14.dp)
        val baseTopDp = ((maxHeight - totalAssemblyH) * 0.5f).coerceAtLeast(14.dp)

        val baseLeftPx = with(density) { baseLeftDp.toPx() }
        val baseTopPx = with(density) { baseTopDp.toPx() }

        Box(
            modifier =
                Modifier
                    .offset {
                        IntOffset(
                            (baseLeftPx + state.canvasPanOffset.x).roundToInt(),
                            (baseTopPx + state.canvasPanOffset.y).roundToInt(),
                        )
                    }.size(totalAssemblyW, totalAssemblyH),
        ) {
            val tickStep =
                when {
                    zoom >= 3f -> 16
                    zoom >= 2f -> 32
                    else -> 64
                }

            // Top Coordinate Ruler
            Box(
                modifier =
                    Modifier
                        .offset(x = rulerThickness, y = 0.dp)
                        .size(sheetWidthDp, rulerThickness),
            ) {
                val tickCol = colors.borderStrong
                Canvas(modifier = Modifier.fillMaxSize()) {
                    var tx = 0
                    while (tx <= state.atlas.width) {
                        val px = (tx * zoom).dp.toPx()
                        drawLine(
                            color = tickCol,
                            start = Offset(px, size.height - 4.dp.toPx()),
                            end = Offset(px, size.height),
                            strokeWidth = 1f,
                        )
                        tx += tickStep
                    }
                }
                var tx = 0
                while (tx < state.atlas.width) {
                    Text(
                        text = "$tx",
                        style = typography.monoCaption.copy(fontSize = 9.sp, lineHeight = 11.sp),
                        color = colors.textMuted,
                        modifier = Modifier.offset(x = (tx * zoom).dp + 2.dp, y = 2.dp),
                    )
                    tx += tickStep
                }
            }

            // Left Coordinate Ruler
            Box(
                modifier =
                    Modifier
                        .offset(x = 0.dp, y = rulerThickness)
                        .size(rulerThickness, sheetHeightDp),
            ) {
                val tickCol = colors.borderStrong
                Canvas(modifier = Modifier.fillMaxSize()) {
                    var ty = 0
                    while (ty <= state.atlas.height) {
                        val py = (ty * zoom).dp.toPx()
                        drawLine(
                            color = tickCol,
                            start = Offset(size.width - 4.dp.toPx(), py),
                            end = Offset(size.width, py),
                            strokeWidth = 1f,
                        )
                        ty += tickStep
                    }
                }
                var ty = 0
                while (ty < state.atlas.height) {
                    Text(
                        text = "$ty",
                        style = typography.monoCaption.copy(fontSize = 9.sp, lineHeight = 11.sp),
                        color = colors.textMuted,
                        modifier = Modifier.offset(x = 1.dp, y = (ty * zoom).dp + 1.dp),
                    )
                    ty += tickStep
                }
            }

            // Main Pixel-Sharp Atlas Sheet (0dp texel corners)
            Box(
                modifier =
                    Modifier
                        .offset(x = rulerThickness, y = rulerThickness)
                        .size(sheetWidthDp, sheetHeightDp)
                        .checkerboardBackground(8.dp, colors.checkerDark, colors.checkerLight)
                        .border(1.dp, colors.borderStrong)
                        .pointerInput(state.atlas, pixelsPerTexel) {
                            awaitEachGesture {
                                val firstEvent = awaitPointerEvent()
                                val middleMouse = firstEvent.buttons.isTertiaryPressed
                                val down = firstEvent.changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
                                down.consume()

                                val startPos = down.position
                                val texX = (startPos.x / pixelsPerTexel).toInt()
                                val texY = (startPos.y / pixelsPerTexel).toInt()

                                if (curTool == AtlasCanvasTool.PAN || middleMouse) {
                                    var lastPos = startPos
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull() ?: break
                                        if (!ch.pressed) break
                                        val delta = ch.position - lastPos
                                        lastPos = ch.position
                                        ch.consume()
                                        state.canvasPanOffset += delta
                                    }
                                    return@awaitEachGesture
                                }

                                // Hit-test slices first for click selection, and topmost texture for drag
                                val hitSlice =
                                    state.atlas.slices.lastOrNull { slice ->
                                        val parent =
                                            state.atlas.textures.find {
                                                it.name.equals(slice.sourceTexture, ignoreCase = true)
                                            }
                                        parent != null &&
                                            texX in (parent.x + slice.x) until (parent.x + slice.x + slice.width) &&
                                            texY in (parent.y + slice.y) until (parent.y + slice.y + slice.height)
                                    }

                                var hitTexIdx: Int? = null
                                for (i in state.atlas.textures.indices
                                    .reversed()) {
                                    val tex = state.atlas.textures[i]
                                    if (texX in tex.x until (tex.x + tex.width) &&
                                        texY in tex.y until (tex.y + tex.height)
                                    ) {
                                        hitTexIdx = i
                                        break
                                    }
                                }

                                if (hitTexIdx == null) {
                                    // Dragging empty space inside the sheet pans cleanly
                                    state.selectedSliceName = null
                                    var lastPos = startPos
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull() ?: break
                                        if (!ch.pressed) break
                                        val delta = ch.position - lastPos
                                        lastPos = ch.position
                                        ch.consume()
                                        state.canvasPanOffset += delta
                                    }
                                } else {
                                    state.selectedTextureIndex = hitTexIdx
                                    state.selectedSliceName = hitSlice?.name
                                    state.selectedDescriptorName =
                                        hitSlice?.name ?: state.atlas.textures[hitTexIdx].name

                                    var movedBeyondSlop = false
                                    var accumDrag = Offset.Zero
                                    var lastPos = startPos

                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull() ?: break
                                        if (!ch.pressed) break
                                        val delta = ch.position - lastPos
                                        lastPos = ch.position
                                        ch.consume()
                                        accumDrag += delta
                                        if (!movedBeyondSlop && hypot(accumDrag.x, accumDrag.y) >= 4f) {
                                            movedBeyondSlop = true
                                            draggingTextureIndex = hitTexIdx
                                        }
                                        if (movedBeyondSlop) {
                                            dragPixelOffset = accumDrag
                                        }
                                    }

                                    if (movedBeyondSlop && hitTexIdx in state.atlas.textures.indices) {
                                        val tex = state.atlas.textures[hitTexIdx]
                                        val rawTargetX = tex.x + (dragPixelOffset.x / pixelsPerTexel).roundToInt()
                                        val rawTargetY = tex.y + (dragPixelOffset.y / pixelsPerTexel).roundToInt()
                                        state.moveTexture(hitTexIdx, rawTargetX, rawTargetY)
                                    }
                                    draggingTextureIndex = null
                                    dragPixelOffset = Offset.Zero
                                }
                            }
                        },
            ) {
                // Grid Snap Lines
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val gridPx = (curGridSnap * zoom).dp.toPx()
                    if (gridPx >= 4f) {
                        val numCols = (size.width / gridPx).toInt()
                        val numRows = (size.height / gridPx).toInt()
                        for (col in 1..numCols) {
                            val x = col * gridPx
                            if (x < size.width) {
                                drawLine(
                                    color = colors.pixelGridLine,
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = 1f,
                                )
                            }
                        }
                        for (row in 1..numRows) {
                            val y = row * gridPx
                            if (y < size.height) {
                                drawLine(
                                    color = colors.pixelGridLine,
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = 1f,
                                )
                            }
                        }
                    }
                }

                // Placed SourceTextures (0dp sharp texels, FilterQuality.None)
                state.atlas.textures.forEachIndexed { index, tex ->
                    val isBeingDragged = draggingTextureIndex == index
                    val isSelected = state.selectedTextureIndex == index && state.selectedSliceName == null

                    val currentX =
                        if (isBeingDragged) {
                            val rawX = tex.x + (dragPixelOffset.x / pixelsPerTexel).roundToInt()
                            (rawX / state.gridSnap) * state.gridSnap
                        } else {
                            tex.x
                        }

                    val currentY =
                        if (isBeingDragged) {
                            val rawY = tex.y + (dragPixelOffset.y / pixelsPerTexel).roundToInt()
                            (rawY / state.gridSnap) * state.gridSnap
                        } else {
                            tex.y
                        }

                    val isValidCandidate =
                        if (isBeingDragged) {
                            state.atlas.canPlace(tex, currentX, currentY, excludeTextureIndex = index)
                        } else {
                            true
                        }

                    val borderColor =
                        when {
                            isBeingDragged && !isValidCandidate -> colors.semanticError
                            isBeingDragged -> colors.semanticSuccess
                            isSelected -> colors.accentHover
                            else -> colors.canvasTileBorder
                        }

                    val texWidthDp = (tex.width * zoom).dp
                    val texHeightDp = (tex.height * zoom).dp
                    val bitmap =
                        remember(tex, state.palette) {
                            TextureComposeRenderer.renderToImageBitmap(tex, state.palette)
                        }

                    Box(
                        modifier =
                            Modifier
                                .offset(x = (currentX * zoom).dp, y = (currentY * zoom).dp)
                                .size(texWidthDp, texHeightDp)
                                .clipToBounds()
                                .border(
                                    width = if (isSelected || isBeingDragged) 2.dp else 1.dp,
                                    color = borderColor,
                                ),
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = tex.name,
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (isBeingDragged && !isValidCandidate) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(colors.semanticError.copy(alpha = 0.28f)),
                            )
                        }

                        if (texWidthDp >= 26.dp && texHeightDp >= 16.dp) {
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .padding(2.dp)
                                        .widthIn(max = (texWidthDp - 4.dp).coerceAtLeast(16.dp))
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(colors.canvasOverlayBg)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = tex.name,
                                    style = typography.monoCaption.copy(fontSize = 9.sp, lineHeight = 11.sp),
                                    color = colors.canvasOverlayText,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // VirtualSlice Dashed Cyan Overlays (Critical for UC4a)
                for (slice in state.atlas.slices) {
                    val parentIdx =
                        state.atlas.textures.indexOfFirst {
                            it.name.equals(slice.sourceTexture, ignoreCase = true)
                        }
                    if (parentIdx < 0) continue
                    val parent = state.atlas.textures[parentIdx]
                    val parentDragged = draggingTextureIndex == parentIdx

                    val parentX =
                        if (parentDragged) {
                            val rawX = parent.x + (dragPixelOffset.x / pixelsPerTexel).roundToInt()
                            (rawX / state.gridSnap) * state.gridSnap
                        } else {
                            parent.x
                        }
                    val parentY =
                        if (parentDragged) {
                            val rawY = parent.y + (dragPixelOffset.y / pixelsPerTexel).roundToInt()
                            (rawY / state.gridSnap) * state.gridSnap
                        } else {
                            parent.y
                        }

                    val sliceLeftDp = ((parentX + slice.x) * zoom).dp
                    val sliceTopDp = ((parentY + slice.y) * zoom).dp
                    val sliceWidthDp = (slice.width * zoom).dp
                    val sliceHeightDp = (slice.height * zoom).dp
                    val isSliceSelected = state.selectedSliceName.equals(slice.name, ignoreCase = true)
                    val sliceAccent = if (isSliceSelected) colors.aliasAmber else colors.sliceCyan

                    Box(
                        modifier =
                            Modifier
                                .offset(x = sliceLeftDp, y = sliceTopDp)
                                .size(sliceWidthDp, sliceHeightDp),
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRect(color = sliceAccent.copy(alpha = if (isSliceSelected) 0.16f else 0.08f))
                            val strokePx = if (isSliceSelected) 2.dp.toPx() else 1.5.dp.toPx()
                            drawRect(
                                color = sliceAccent,
                                style =
                                    Stroke(
                                        width = strokePx,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f),
                                    ),
                            )
                        }
                    }

                    // Crisp SLICE label pill anchored cleanly at the bottom edge of the slice sub-rect
                    val pillTopDp =
                        if (sliceTopDp + sliceHeightDp + 14.dp <= sheetHeightDp) {
                            sliceTopDp + sliceHeightDp + 1.dp
                        } else {
                            (sliceTopDp + sliceHeightDp - 13.dp).coerceAtLeast(0.dp)
                        }
                    val slicePillFg = if (isSliceSelected) Color(0xFFFBBF24) else Color(0xFF22D3EE)
                    Row(
                        modifier =
                            Modifier
                                .offset(x = sliceLeftDp + 1.dp, y = pillTopDp)
                                .widthIn(max = (sheetWidthDp - sliceLeftDp - 2.dp).coerceAtLeast(24.dp))
                                .clip(RoundedCornerShape(3.dp))
                                .background(colors.canvasOverlayBg)
                                .border(0.75.dp, slicePillFg.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = StudioIcons.SliceRect,
                            contentDescription = null,
                            tint = slicePillFg,
                            modifier = Modifier.size(9.dp),
                        )
                        Text(
                            text = slice.name,
                            style = typography.monoCaption.copy(fontSize = 8.5.sp, lineHeight = 11.sp),
                            color = slicePillFg,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
