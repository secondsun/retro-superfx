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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.core.atlas.DescriptorAlias
import dev.secondsun.sfxstudio.core.atlas.DescriptorTable
import dev.secondsun.sfxstudio.core.atlas.VirtualSlice
import dev.secondsun.sfxstudio.desktop.atlas.AtlasCatalogTab
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.ui.TextureComposeRenderer
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.SegmentItem
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioBadge
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioIconButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioNumberStepper
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioPanelSheet
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSectionHeader
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSegmentedControl
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.HairlineEdge
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioViewportClass
import dev.secondsun.sfxstudio.desktop.ui.theme.checkerboardBackground
import dev.secondsun.sfxstudio.desktop.ui.theme.drawEdgeHairline
import kotlin.math.roundToInt

@Composable
fun LeftPane(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val viewport = StudioTheme.viewport
    val defaultWidth = if (viewport.viewportClass == StudioViewportClass.EXPANDED) 320.dp else 276.dp
    val totalDescriptors = state.atlas.textures.size + state.atlas.slices.size + state.atlas.aliases.size
    val activeTopTab =
        if (state.catalogTab == AtlasCatalogTab.PALETTE) {
            AtlasCatalogTab.TEXTURES
        } else {
            state.catalogTab
        }

    StudioPanelSheet(
        edge = HairlineEdge.RIGHT,
        modifier = modifier.width(defaultWidth).fillMaxHeight(),
    ) {
        // --- Top Half: Source Textures & Descriptor Table ---
        Column(
            modifier =
                Modifier
                    .weight(0.55f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StudioSegmentedControl(
                    items =
                        listOf(
                            SegmentItem(
                                value = AtlasCatalogTab.TEXTURES,
                                label = "Textures",
                                badge = "${state.atlas.textures.size}",
                            ),
                            SegmentItem(
                                value = AtlasCatalogTab.DESCRIPTORS,
                                label = "Descriptors",
                                badge = "$totalDescriptors",
                            ),
                        ),
                    selectedValue = activeTopTab,
                    onSelect = { state.catalogTab = it },
                    compact = true,
                )

                if (activeTopTab == AtlasCatalogTab.TEXTURES) {
                    if (viewport.viewportClass == StudioViewportClass.EXPANDED) {
                        StudioButton(
                            label = "Import...",
                            variant = StudioButtonVariant.Secondary,
                            compact = true,
                            icon = StudioIcons.Import,
                            onClick = { state.promptImportFile() },
                        )
                    } else {
                        StudioIconButton(
                            icon = StudioIcons.Import,
                            contentDescription = "Import Bitmap...",
                            size = StudioTheme.metrics.controlHeightSM,
                            onClick = { state.promptImportFile() },
                        )
                    }
                }
            }

            when (activeTopTab) {
                AtlasCatalogTab.TEXTURES ->
                    SourceTexturesSection(
                        state = state,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                AtlasCatalogTab.DESCRIPTORS, AtlasCatalogTab.PALETTE ->
                    DescriptorTableSection(
                        state = state,
                        showActionHeader = true,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
            }
        }

        // Single subtle hairline between Catalog and BGR555 Palette
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.borderSubtle),
        )

        // --- Bottom Half: SNES BGR555 Palette ---
        PaletteSection(
            state = state,
            isCompactSheet = false,
            modifier =
                Modifier
                    .weight(0.45f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun AtlasCompactBottomSheet(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val totalDescriptors = state.atlas.textures.size + state.atlas.slices.size + state.atlas.aliases.size

    StudioPanelSheet(
        edge = HairlineEdge.TOP,
        isBottomSheet = true,
        modifier = modifier,
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
                        SegmentItem(
                            value = AtlasCatalogTab.TEXTURES,
                            label = "Textures",
                            badge = "${state.atlas.textures.size}",
                        ),
                        SegmentItem(
                            value = AtlasCatalogTab.DESCRIPTORS,
                            label = "Descriptors",
                            badge = "$totalDescriptors",
                        ),
                        SegmentItem(
                            value = AtlasCatalogTab.PALETTE,
                            label = "Palette",
                            badge = "${state.palette.assignedCount}/15",
                        ),
                    ),
                selectedValue = state.catalogTab,
                onSelect = { state.catalogTab = it },
                compact = true,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (state.catalogTab) {
                    AtlasCatalogTab.TEXTURES -> {
                        StudioButton(
                            label = "Import...",
                            variant = StudioButtonVariant.Secondary,
                            compact = true,
                            icon = StudioIcons.Import,
                            onClick = { state.promptImportFile() },
                        )
                    }
                    AtlasCatalogTab.DESCRIPTORS -> {
                        DescriptorHeaderActions(state)
                    }
                    AtlasCatalogTab.PALETTE -> {
                        StudioButton(
                            label = "Quantize 15c",
                            variant = StudioButtonVariant.Secondary,
                            compact = true,
                            enabled = state.atlas.textures.isNotEmpty() || state.palette.assignedCount > 0,
                            onClick = { state.quantizePalette() },
                        )
                    }
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            when (state.catalogTab) {
                AtlasCatalogTab.TEXTURES ->
                    SourceTexturesSection(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                    )
                AtlasCatalogTab.DESCRIPTORS ->
                    DescriptorTableSection(
                        state = state,
                        showActionHeader = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                AtlasCatalogTab.PALETTE ->
                    PaletteSection(
                        state = state,
                        isCompactSheet = true,
                        modifier = Modifier.fillMaxSize(),
                    )
            }
        }
    }
}

private fun aliasResolvesToRoots(
    alias: DescriptorAlias,
    rootUpperSet: Set<String>,
    aliasByUpper: Map<String, DescriptorAlias>,
): Boolean {
    val visited = HashSet<String>()
    visited.add(alias.name.uppercase())
    var targetUpper = alias.target.uppercase()
    while (true) {
        if (targetUpper in rootUpperSet) return true
        if (!visited.add(targetUpper)) return false
        val next = aliasByUpper[targetUpper] ?: return false
        targetUpper = next.target.uppercase()
    }
}

@Composable
private fun SourceTexturesSection(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    if (state.atlas.textures.isEmpty()) {
        val dashColor = colors.borderStrong
        val radiusPx = metrics.radiusSM
        Box(
            modifier =
                modifier
                    .clip(RoundedCornerShape(radiusPx))
                    .background(colors.surface0)
                    .drawBehind {
                        drawRoundRect(
                            color = dashColor,
                            cornerRadius = CornerRadius(radiusPx.toPx(), radiusPx.toPx()),
                            style =
                                Stroke(
                                    width = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f),
                                ),
                        )
                    }.padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = StudioIcons.Import,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "No Source Textures yet — Import PNG/BMP tiles to populate this Atlas Document.",
                    style = typography.caption,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                StudioButton(
                    label = "Import Bitmaps...",
                    variant = StudioButtonVariant.Secondary,
                    compact = true,
                    icon = StudioIcons.Import,
                    onClick = { state.promptImportFile() },
                )
            }
        }
        return
    }

    val aliasByUpper =
        remember(state.atlas.aliases) {
            state.atlas.aliases.associateBy { it.name.uppercase() }
        }

    val actionBtnSize = if (metrics.touchComfort) 34.dp else 24.dp

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 4.dp),
    ) {
        itemsIndexed(state.atlas.textures) { index, tex ->
            val isSelected = state.selectedTextureIndex == index && state.selectedSliceName == null
            val interactionSource = remember(tex.name, index) { MutableInteractionSource() }
            val hovered by interactionSource.collectIsHoveredAsState()
            val rowShape = RoundedCornerShape(metrics.radiusSM)

            val rowBg =
                when {
                    isSelected -> colors.accentSubtle
                    hovered -> colors.surface2
                    else -> colors.surface2.copy(alpha = 0.45f)
                }

            val directSlices =
                remember(tex.name, state.atlas.slices) {
                    state.atlas.slices.withIndex().filter { (_, s) ->
                        s.sourceTexture.equals(tex.name, ignoreCase = true)
                    }
                }

            val relatedAliases =
                remember(tex.name, directSlices, state.atlas.aliases, aliasByUpper) {
                    val roots = HashSet<String>()
                    roots.add(tex.name.uppercase())
                    for ((_, s) in directSlices) {
                        roots.add(s.name.uppercase())
                    }
                    state.atlas.aliases.withIndex().filter { (_, a) ->
                        aliasResolvesToRoots(a, roots, aliasByUpper)
                    }
                }

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = metrics.listRowMinHeight)
                            .clip(rowShape)
                            .background(rowBg, rowShape)
                            .let { mod ->
                                if (isSelected) mod.border(1.dp, colors.accentBorder, rowShape) else mod
                            }.hoverable(interactionSource)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                            ) {
                                state.selectedTextureIndex = index
                                state.selectedSliceName = null
                                state.selectedDescriptorName = tex.name
                            }.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val bitmap =
                        remember(tex, state.palette) {
                            TextureComposeRenderer.renderToImageBitmap(tex, state.palette)
                        }
                    Box(
                        modifier =
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(metrics.radiusXS))
                                .checkerboardBackground(4.dp, colors.checkerDark, colors.checkerLight)
                                .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusXS)),
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = tex.name,
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = tex.name,
                            style = typography.monoBody,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${tex.width}x${tex.height} @ (${tex.x}, ${tex.y})",
                            style = typography.caption,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        StudioIconButton(
                            icon = StudioIcons.SliceRect,
                            contentDescription = "Add Virtual Slice",
                            size = actionBtnSize,
                            onClick = { state.openNewSliceModal(tex.name) },
                        )
                        StudioIconButton(
                            icon = StudioIcons.Edit,
                            contentDescription = "Edit Pixels",
                            size = actionBtnSize,
                            onClick = { state.editingTextureIndex = index },
                        )
                        StudioIconButton(
                            icon = StudioIcons.Trash,
                            contentDescription = "Delete Texture",
                            dangerOnHover = true,
                            tint = colors.textMuted,
                            size = actionBtnSize,
                            onClick = { state.requestDeleteTexture(index) },
                        )
                    }
                }

                // Inline Dependent Slices & Aliases Tree (Critical for UC4a Discoverability)
                if (directSlices.isNotEmpty() || relatedAliases.isNotEmpty()) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for ((sliceIdx, slice) in directSlices) {
                            DependentSliceRow(
                                slice = slice,
                                isSelected = state.selectedSliceName.equals(slice.name, ignoreCase = true),
                                onSelect = {
                                    state.selectedTextureIndex = index
                                    state.selectedSliceName = slice.name
                                    state.selectedDescriptorName = slice.name
                                },
                                onEdit = { state.openEditSliceModal(sliceIdx) },
                                onDelete = { state.requestDeleteSlice(sliceIdx) },
                            )
                        }
                        for ((aliasIdx, alias) in relatedAliases) {
                            DependentAliasRow(
                                alias = alias,
                                isSelected = state.selectedDescriptorName.equals(alias.name, ignoreCase = true),
                                onSelect = {
                                    state.selectedTextureIndex = index
                                    val matchingSlice =
                                        directSlices.firstOrNull { (_, s) ->
                                            s.name.equals(alias.target, ignoreCase = true)
                                        }
                                    state.selectedSliceName = matchingSlice?.value?.name
                                    state.selectedDescriptorName = alias.name
                                },
                                onEdit = { state.openEditAliasModal(aliasIdx) },
                                onDelete = { state.requestDeleteAlias(aliasIdx) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DependentSliceRow(
    slice: VirtualSlice,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val connectorColor = colors.borderStrong
    val rowShape = RoundedCornerShape(metrics.radiusXS)
    val microBtnSize = if (metrics.touchComfort) 28.dp else 20.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.width(12.dp).height(24.dp)) {
            val midY = size.height * 0.5f
            drawLine(connectorColor, Offset(2.dp.toPx(), 0f), Offset(2.dp.toPx(), midY), strokeWidth = 1.dp.toPx())
            drawLine(connectorColor, Offset(2.dp.toPx(), midY), Offset(size.width, midY), strokeWidth = 1.dp.toPx())
        }

        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .clip(rowShape)
                    .background(
                        if (isSelected) colors.sliceCyan.copy(alpha = 0.14f) else colors.surface0.copy(alpha = 0.7f),
                        rowShape,
                    ).let { mod ->
                        if (isSelected) mod.border(1.dp, colors.sliceCyan.copy(alpha = 0.55f), rowShape) else mod
                    }.clickable(onClick = onSelect)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            StudioBadge(
                text = "SLICE",
                textColor = colors.sliceCyan,
                backgroundColor = colors.sliceCyan.copy(alpha = 0.18f),
            )
            Text(
                text = slice.name,
                style = typography.monoCaption,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${slice.width}x${slice.height} +(${slice.x},${slice.y})",
                style = typography.caption,
                color = colors.textMuted,
                maxLines = 1,
            )
            StudioIconButton(
                icon = StudioIcons.Edit,
                contentDescription = "Edit Slice",
                size = microBtnSize,
                onClick = onEdit,
            )
            StudioIconButton(
                icon = StudioIcons.Trash,
                contentDescription = "Delete Slice",
                dangerOnHover = true,
                tint = colors.textMuted,
                size = microBtnSize,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun DependentAliasRow(
    alias: DescriptorAlias,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val connectorColor = colors.borderStrong
    val rowShape = RoundedCornerShape(metrics.radiusXS)
    val microBtnSize = if (metrics.touchComfort) 28.dp else 20.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.width(12.dp).height(24.dp)) {
            val midY = size.height * 0.5f
            drawLine(connectorColor, Offset(2.dp.toPx(), 0f), Offset(2.dp.toPx(), midY), strokeWidth = 1.dp.toPx())
            drawLine(connectorColor, Offset(2.dp.toPx(), midY), Offset(size.width, midY), strokeWidth = 1.dp.toPx())
        }

        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .clip(rowShape)
                    .background(
                        if (isSelected) colors.aliasAmber.copy(alpha = 0.14f) else colors.surface0.copy(alpha = 0.7f),
                        rowShape,
                    ).let { mod ->
                        if (isSelected) mod.border(1.dp, colors.aliasAmber.copy(alpha = 0.55f), rowShape) else mod
                    }.clickable(onClick = onSelect)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            StudioBadge(
                text = "ALIAS",
                textColor = colors.aliasAmber,
                backgroundColor = colors.aliasAmber.copy(alpha = 0.18f),
            )
            Text(
                text = alias.name,
                style = typography.monoCaption,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = StudioIcons.ArrowRight,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(10.dp),
            )
            Text(
                text = alias.target,
                style = typography.monoCaption,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StudioIconButton(
                icon = StudioIcons.Edit,
                contentDescription = "Edit Alias",
                size = microBtnSize,
                onClick = onEdit,
            )
            StudioIconButton(
                icon = StudioIcons.Trash,
                contentDescription = "Delete Alias",
                dangerOnHover = true,
                tint = colors.textMuted,
                size = microBtnSize,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun DescriptorHeaderActions(state: AtlasEditorState) {
    val totalDescriptors = state.atlas.textures.size + state.atlas.slices.size + state.atlas.aliases.size
    val isPinned = state.atlas.descriptorOrder != null

    StudioButton(
        label = "+ Slice",
        variant = StudioButtonVariant.Secondary,
        compact = true,
        icon = StudioIcons.SliceRect,
        enabled = state.atlas.textures.isNotEmpty(),
        onClick = { state.openNewSliceModal() },
    )
    StudioButton(
        label = "+ Alias",
        variant = StudioButtonVariant.Secondary,
        compact = true,
        icon = StudioIcons.AliasLink,
        enabled = totalDescriptors > 0,
        onClick = { state.openNewAliasModal() },
    )
    StudioButton(
        label = if (isPinned) "Pinned" else "Auto",
        variant = if (isPinned) StudioButtonVariant.Secondary else StudioButtonVariant.Ghost,
        compact = true,
        icon = if (isPinned) StudioIcons.PinLock else StudioIcons.PinUnlock,
        tooltip =
            if (isPinned) {
                "Order Pinned — click to reset to automatic order"
            } else {
                "Order Auto — click to pin current \$00..\$EF indices"
            },
        onClick = {
            if (isPinned) {
                state.clearDescriptorOrder()
            } else {
                state.pinDescriptorOrder()
            }
        },
    )
}

@Composable
private fun DescriptorTableSection(
    state: AtlasEditorState,
    showActionHeader: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val tableResult = remember(state.atlas) { state.atlas.descriptorTable() }
    val totalCount = state.atlas.textures.size + state.atlas.slices.size + state.atlas.aliases.size

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showActionHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DescriptorHeaderActions(state)
            }
        }

        // Budget Strip ($00..$EF)
        val lastHex = String.format("%02X", (totalCount - 1).coerceAtLeast(0))
        val fillFraction = (totalCount.toFloat() / DescriptorTable.MAX_ENTRIES.toFloat()).coerceIn(0f, 1f)
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(metrics.radiusXS))
                    .background(colors.surface0)
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusXS))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        if (totalCount == 0) {
                            "0 / ${DescriptorTable.MAX_ENTRIES} Descriptors"
                        } else {
                            "$totalCount / ${DescriptorTable.MAX_ENTRIES} (\$00..\$$lastHex)"
                        },
                    style = typography.monoCaption,
                    color = if (totalCount > DescriptorTable.MAX_ENTRIES) colors.semanticErrorText else colors.textSecondary,
                )
                Text(
                    text = "\$F0..\$FF Solid",
                    style = typography.monoCaption,
                    color = colors.textMuted,
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.surface2),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(fillFraction)
                            .fillMaxHeight()
                            .background(
                                if (totalCount > DescriptorTable.MAX_ENTRIES) colors.semanticError else colors.accentPrimary,
                            ),
                )
            }
        }

        tableResult.fold(
            onSuccess = { table ->
                if (table.entries.isEmpty()) {
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Import textures to populate TEXTURE_TABLE (\$00..\$EF).",
                            style = typography.caption,
                            color = colors.textMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    val btnSize = if (metrics.touchComfort) 32.dp else 22.dp
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        itemsIndexed(table.entries) { slotIdx, desc ->
                            val texIdx = state.atlas.textures.indexOfFirst { it.name.equals(desc.name, ignoreCase = true) }
                            val sliceIdx = state.atlas.slices.indexOfFirst { it.name.equals(desc.name, ignoreCase = true) }
                            val aliasIdx = state.atlas.aliases.indexOfFirst { it.name.equals(desc.name, ignoreCase = true) }
                            val isSelected = state.selectedDescriptorName.equals(desc.name, ignoreCase = true)
                            val rowShape = RoundedCornerShape(metrics.radiusSM)

                            val previewBmp =
                                remember(table, desc, state.palette) {
                                    TextureComposeRenderer.renderToImageBitmap(
                                        table.extractPreviewTexture(desc),
                                        state.palette,
                                    )
                                }

                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(rowShape)
                                        .background(
                                            if (isSelected) colors.accentSubtle else colors.surface2.copy(alpha = 0.45f),
                                            rowShape,
                                        ).let { mod ->
                                            if (isSelected) mod.border(1.dp, colors.accentBorder, rowShape) else mod
                                        }.clickable {
                                            state.selectedDescriptorName = desc.name
                                            when {
                                                texIdx >= 0 -> {
                                                    state.selectedTextureIndex = texIdx
                                                    state.selectedSliceName = null
                                                }
                                                sliceIdx >= 0 -> {
                                                    val parentName = state.atlas.slices[sliceIdx].sourceTexture
                                                    val pIdx =
                                                        state.atlas.textures.indexOfFirst {
                                                            it.name.equals(parentName, ignoreCase = true)
                                                        }
                                                    if (pIdx >= 0) state.selectedTextureIndex = pIdx
                                                    state.selectedSliceName = desc.name
                                                }
                                            }
                                        }.padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                StudioBadge(
                                    text = String.format("$%02X", slotIdx),
                                    monospace = true,
                                    textColor = colors.accentHover,
                                    backgroundColor = colors.surface0,
                                )

                                Box(
                                    modifier =
                                        Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(metrics.radiusXS))
                                            .checkerboardBackground(3.dp, colors.checkerDark, colors.checkerLight)
                                            .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusXS)),
                                ) {
                                    Image(
                                        bitmap = previewBmp,
                                        contentDescription = desc.name,
                                        filterQuality = FilterQuality.None,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(1.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Text(
                                            text = desc.name,
                                            style = typography.monoCaption,
                                            color = colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        val (kindText, kindFg, kindBg) =
                                            when {
                                                sliceIdx >= 0 ->
                                                    Triple("SLICE", colors.sliceCyan, colors.sliceCyan.copy(alpha = 0.18f))
                                                aliasIdx >= 0 ->
                                                    Triple("ALIAS", colors.aliasAmber, colors.aliasAmber.copy(alpha = 0.18f))
                                                else ->
                                                    Triple("TEX", colors.textSecondary, colors.surface3)
                                            }
                                        StudioBadge(
                                            text = kindText,
                                            textColor = kindFg,
                                            backgroundColor = kindBg,
                                        )
                                    }
                                    Text(
                                        text = String.format("off $%04X · %dx%d", desc.romOffset, desc.width, desc.height),
                                        style = typography.caption,
                                        color = colors.textMuted,
                                        maxLines = 1,
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                                ) {
                                    StudioIconButton(
                                        icon = StudioIcons.ArrowUp,
                                        contentDescription = "Move Descriptor Up",
                                        enabled = slotIdx > 0,
                                        size = btnSize,
                                        onClick = { state.moveDescriptorInOrder(slotIdx, slotIdx - 1) },
                                    )
                                    StudioIconButton(
                                        icon = StudioIcons.ArrowDown,
                                        contentDescription = "Move Descriptor Down",
                                        enabled = slotIdx < table.entries.lastIndex,
                                        size = btnSize,
                                        onClick = { state.moveDescriptorInOrder(slotIdx, slotIdx + 1) },
                                    )
                                    StudioIconButton(
                                        icon = StudioIcons.Edit,
                                        contentDescription = "Edit Descriptor",
                                        size = btnSize,
                                        onClick = {
                                            when {
                                                sliceIdx >= 0 -> state.openEditSliceModal(sliceIdx)
                                                aliasIdx >= 0 -> state.openEditAliasModal(aliasIdx)
                                                texIdx >= 0 -> state.editingTextureIndex = texIdx
                                            }
                                        },
                                    )
                                    StudioIconButton(
                                        icon = StudioIcons.Trash,
                                        contentDescription = "Delete Descriptor",
                                        dangerOnHover = true,
                                        tint = colors.textMuted,
                                        size = btnSize,
                                        onClick = {
                                            when {
                                                sliceIdx >= 0 -> state.requestDeleteSlice(sliceIdx)
                                                aliasIdx >= 0 -> state.requestDeleteAlias(aliasIdx)
                                                texIdx >= 0 -> state.requestDeleteTexture(texIdx)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            onFailure = { err ->
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(metrics.radiusSM))
                            .background(colors.semanticErrorSubtle)
                            .border(1.dp, colors.semanticError.copy(alpha = 0.5f), RoundedCornerShape(metrics.radiusSM))
                            .padding(10.dp),
                ) {
                    Text(
                        text = err.message ?: "Descriptor table resolution error",
                        style = typography.caption,
                        color = colors.semanticErrorText,
                    )
                }
            },
        )
    }
}

@Composable
private fun PaletteSection(
    state: AtlasEditorState,
    isCompactSheet: Boolean,
    modifier: Modifier = Modifier,
) {
    if (isCompactSheet) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PaletteSwatchMatrix(
                state = state,
                modifier = Modifier.weight(0.45f),
            )
            PaletteSlotInspector(
                state = state,
                modifier = Modifier.weight(0.55f).fillMaxHeight(),
            )
        }
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StudioSectionHeader(
                title = "SNES Palette",
                badge = "${state.palette.assignedCount}/15",
                trailing = {
                    StudioButton(
                        label = "Quantize 15c",
                        variant = StudioButtonVariant.Secondary,
                        compact = true,
                        enabled = state.atlas.textures.isNotEmpty() || state.palette.assignedCount > 0,
                        onClick = { state.quantizePalette() },
                    )
                },
            )

            PaletteSwatchMatrix(
                state = state,
                modifier = Modifier.fillMaxWidth(),
            )

            PaletteSlotInspector(
                state = state,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PaletteSwatchMatrix(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(metrics.radiusSM))
                .background(colors.surface0)
                .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusSM))
                .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in 0..1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (col in 0..7) {
                    val slot = row * 8 + col
                    val isSelected = state.selectedPaletteSlot == slot
                    val color = state.palette[slot]
                    val isAssigned = state.palette.isAssigned(slot)
                    val swatchShape = RoundedCornerShape(metrics.radiusXS)

                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(metrics.swatchHeight)
                                .clip(swatchShape)
                                .let { mod ->
                                    when {
                                        slot == 0 ->
                                            mod.checkerboardBackground(4.dp, colors.checkerDark, colors.checkerLight)
                                        isAssigned ->
                                            mod.background(Color(color.argb), swatchShape)
                                        else ->
                                            mod.background(colors.surface1, swatchShape)
                                    }
                                }.let { mod ->
                                    if (isSelected) {
                                        mod
                                            .border(2.dp, colors.accentHover, swatchShape)
                                            .padding(2.dp)
                                            .border(1.dp, colors.bgCanvas, swatchShape)
                                    } else {
                                        mod.border(
                                            1.dp,
                                            if (isAssigned) colors.swatchInnerRim else colors.borderSubtle,
                                            swatchShape,
                                        )
                                    }
                                }.clickable { state.selectedPaletteSlot = slot },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (slot == 0) {
                            val slashCol = colors.borderStrong
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawLine(
                                    color = slashCol,
                                    start = Offset(3.dp.toPx(), size.height - 3.dp.toPx()),
                                    end = Offset(size.width - 3.dp.toPx(), 3.dp.toPx()),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }
                            Text(
                                text = "0",
                                style = typography.microBadge,
                                color = colors.textMuted,
                            )
                        } else if (!isAssigned) {
                            val hatchCol = colors.borderSubtle
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawLine(
                                    color = hatchCol,
                                    start = Offset(2.dp.toPx(), size.height - 2.dp.toPx()),
                                    end = Offset(size.width - 2.dp.toPx(), 2.dp.toPx()),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }
                            Text(
                                text = "$slot",
                                style = typography.microBadge,
                                color = colors.textMuted.copy(alpha = 0.7f),
                            )
                        } else if (color.bgr555 == 0x0001) {
                            Text(
                                text = "$01",
                                style = typography.microBadge,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteSlotInspector(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val curSlot = state.selectedPaletteSlot
    val cardShape = RoundedCornerShape(metrics.radiusSM)

    if (curSlot == 0) {
        Box(
            modifier =
                modifier
                    .clip(cardShape)
                    .background(colors.surface0)
                    .border(1.dp, colors.borderSubtle, cardShape)
                    .padding(10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text =
                    "Slot 0 is hardware-locked to Transparent ($0000). " +
                        "Texels using index 0 render transparent in GSU / IsoEngine.",
                style = typography.caption,
                color = colors.textSecondary,
            )
        }
        return
    }

    val color = state.palette[curSlot]
    val isAssigned = state.palette.isAssigned(curSlot)

    Column(
        modifier =
            modifier
                .clip(cardShape)
                .background(colors.surface0)
                .border(1.dp, colors.borderSubtle, cardShape)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(metrics.radiusXS))
                            .background(Color(color.argb))
                            .border(1.dp, colors.swatchInnerRim, RoundedCornerShape(metrics.radiusXS)),
                )
                Text(
                    text = "Slot $curSlot",
                    style = typography.bodyMedium,
                    color = colors.textPrimary,
                )
                StudioBadge(
                    text = color.hexString,
                    monospace = true,
                    textColor = colors.accentHover,
                    backgroundColor = colors.surface2,
                )
                Text(
                    text = String.format("#%06X", color.rgb888),
                    style = typography.monoCaption,
                    color = colors.textMuted,
                )
            }

            StudioButton(
                label = "Unassign",
                variant = StudioButtonVariant.DangerGhost,
                compact = true,
                icon = StudioIcons.Trash,
                enabled = isAssigned,
                onClick = { state.requestUnassignSlot(curSlot) },
            )
        }

        BgrChannelSliderRow(
            channelLabel = "R",
            channelColor = if (colors.isDark) Color(0xFFF87171) else Color(0xFFDC2626),
            value = color.r,
            onBatchStart = { state.beginEditBatch() },
            onBatchEnd = { state.endEditBatch() },
            onValueChange = { newR -> state.updatePaletteSlot(curSlot, color.copy(r = newR)) },
        )

        BgrChannelSliderRow(
            channelLabel = "G",
            channelColor = if (colors.isDark) Color(0xFF4ADE80) else Color(0xFF16A34A),
            value = color.g,
            onBatchStart = { state.beginEditBatch() },
            onBatchEnd = { state.endEditBatch() },
            onValueChange = { newG -> state.updatePaletteSlot(curSlot, color.copy(g = newG)) },
        )

        BgrChannelSliderRow(
            channelLabel = "B",
            channelColor = if (colors.isDark) Color(0xFF60A5FA) else Color(0xFF2563EB),
            value = color.b,
            onBatchStart = { state.beginEditBatch() },
            onBatchEnd = { state.endEditBatch() },
            onValueChange = { newB -> state.updatePaletteSlot(curSlot, color.copy(b = newB)) },
        )
    }
}

@Composable
private fun BgrChannelSliderRow(
    channelLabel: String,
    channelColor: Color,
    value: Int,
    onBatchStart: () -> Unit,
    onBatchEnd: () -> Unit,
    onValueChange: (Int) -> Unit,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnBatchStart by rememberUpdatedState(onBatchStart)
    val currentOnBatchEnd by rememberUpdatedState(onBatchEnd)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = channelLabel,
            style = typography.monoCaption,
            color = channelColor,
            modifier = Modifier.width(14.dp),
        )

        val thumbRadius = if (metrics.touchComfort) 8.dp else 6.dp
        val trackHeight = 6.dp

        Canvas(
            modifier =
                Modifier
                    .weight(1f)
                    .height(metrics.controlHeightSM)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            currentOnBatchStart()
                            val pad = thumbRadius.toPx()
                            val usable = (size.width - pad * 2f).coerceAtLeast(1f)

                            fun xToStep(x: Float): Int {
                                val norm = ((x - pad) / usable).coerceIn(0f, 1f)
                                return (norm * 31f).roundToInt().coerceIn(0, 31)
                            }

                            currentOnValueChange(xToStep(down.position.x))
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    currentOnValueChange(xToStep(change.position.x))
                                }
                            } finally {
                                currentOnBatchEnd()
                            }
                        }
                    },
        ) {
            val pad = thumbRadius.toPx()
            val usable = (size.width - pad * 2f).coerceAtLeast(1f)
            val cy = size.height * 0.5f
            val trackH = trackHeight.toPx()
            val fraction = (value.coerceIn(0, 31) / 31f)
            val thumbX = pad + usable * fraction

            drawRoundRect(
                color = colors.surface2,
                topLeft = Offset(pad, cy - trackH * 0.5f),
                size = Size(usable, trackH),
                cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f),
            )
            if (thumbX > pad) {
                drawRoundRect(
                    color = channelColor.copy(alpha = 0.85f),
                    topLeft = Offset(pad, cy - trackH * 0.5f),
                    size = Size(thumbX - pad, trackH),
                    cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f),
                )
            }
            drawCircle(
                color = colors.bgCanvas,
                radius = thumbRadius.toPx() + 1.5.dp.toPx(),
                center = Offset(thumbX, cy),
            )
            drawCircle(
                color = channelColor,
                radius = thumbRadius.toPx(),
                center = Offset(thumbX, cy),
            )
        }

        StudioNumberStepper(
            value = value,
            onValueChange = onValueChange,
            range = 0..31,
        )
    }
}
