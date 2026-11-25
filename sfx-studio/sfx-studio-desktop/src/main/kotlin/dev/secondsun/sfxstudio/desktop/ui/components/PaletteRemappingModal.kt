package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.ui.TextureComposeRenderer
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioBadge
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioModalDialog
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSectionHeader
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.HairlineEdge
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.checkerboardBackground
import dev.secondsun.sfxstudio.desktop.ui.theme.drawEdgeHairline

@Composable
fun PaletteRemappingModal(state: AtlasEditorState) {
    val pending = state.pendingImport ?: return
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    val manualMapping =
        remember(pending) {
            mutableStateMapOf<SnesColor, Int>().apply {
                putAll(pending.initialMapping)
            }
        }

    val currentMappingSnapshot = manualMapping.toMap()
    val incomingPreview =
        remember(pending, currentMappingSnapshot, state.palette) {
            pending.previewTexture(currentMappingSnapshot, state.palette)
        }

    StudioModalDialog(
        onDismissRequest = { state.pendingImport = null },
        title = "Resolve Palette Conflict",
        subtitle = "Importing '${pending.suggestedName}' (${pending.width}x${pending.height})",
        preferredWidth = 680.dp,
        footerLeading = {
            Text(
                text = "Palette: ${state.palette.assignedCount}/15 assigned",
                style = typography.monoCaption,
                color = colors.textMuted,
            )
        },
        footerActions = {
            StudioButton(
                label = "Cancel",
                variant = StudioButtonVariant.Ghost,
                onClick = { state.pendingImport = null },
            )
            StudioButton(
                label = "Apply Mapping",
                variant = StudioButtonVariant.Primary,
                onClick = { state.commitManualImport(manualMapping.toMap()) },
            )
        },
    ) {
        // 1. Conflict Warning Callout
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(metrics.radiusSM))
                    .background(colors.semanticWarningSubtle)
                    .drawEdgeHairline(HairlineEdge.LEFT, colors.semanticWarning, strokeDp = 3.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = StudioIcons.StatusWarn,
                contentDescription = null,
                tint = colors.semanticWarning,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text =
                    if (pending.unmappedColors.isNotEmpty()) {
                        "${pending.unmappedColors.size} incoming color(s) exceed open slots in the 15-color opaque SNES palette."
                    } else {
                        "Review incoming BGR555 color-to-slot assignments for '${pending.suggestedName}'."
                    },
                style = typography.bodyRegular,
                color = colors.semanticWarningText,
            )
        }

        // 2. Unclipped Live Comparison Strip (106dp tall so labels never clip descenders)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioSectionHeader(
                title = "Live Preview & Existing Atlas Tiles",
                badge = "${state.atlas.textures.size + 1}",
            )

            LazyRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(106.dp)
                        .clip(RoundedCornerShape(metrics.radiusSM))
                        .background(colors.surface0)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusSM))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    val incomingBitmap =
                        remember(incomingPreview, state.palette) {
                            TextureComposeRenderer.renderToImageBitmap(incomingPreview, state.palette)
                        }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(metrics.radiusXS))
                                        .checkerboardBackground(4.dp, colors.checkerDark, colors.checkerLight)
                                        .border(1.5.dp, colors.accentHover, RoundedCornerShape(metrics.radiusXS)),
                            ) {
                                Image(
                                    bitmap = incomingBitmap,
                                    contentDescription = incomingPreview.name,
                                    filterQuality = FilterQuality.None,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                StudioBadge(
                                    text = "NEW",
                                    textColor = colors.onAccent,
                                    backgroundColor = colors.accentPrimary,
                                )
                                Text(
                                    text = incomingPreview.name,
                                    style = typography.caption,
                                    color = colors.accentHover,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 96.dp),
                                )
                            }
                        }

                        if (state.atlas.textures.isNotEmpty()) {
                            Box(
                                modifier =
                                    Modifier
                                        .width(1.dp)
                                        .height(72.dp)
                                        .background(colors.borderSubtle),
                            )
                        }
                    }
                }

                items(state.atlas.textures) { tex ->
                    val bitmap =
                        remember(tex, state.palette) {
                            TextureComposeRenderer.renderToImageBitmap(tex, state.palette)
                        }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(56.dp)
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
                        Text(
                            text = tex.name,
                            style = typography.caption,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 84.dp),
                        )
                    }
                }
            }
        }

        // 3. Neutral Secondary Quick Resolution Action Cards
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val sideBySide = maxWidth >= 480.dp
            if (sideBySide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuickResolutionCard(
                        title = "Auto-Snap to Existing Palette",
                        description = "Keep existing palette untouched; map new pixels to nearest BGR555 slot.",
                        buttonLabel = "Auto-Snap & Import",
                        onClick = { state.commitSnapImport() },
                        modifier = Modifier.weight(1f),
                    )
                    QuickResolutionCard(
                        title = "Global 15-Color Re-Quantize",
                        description = "Recompute optimal 15-color Median-Cut across all textures + new image.",
                        buttonLabel = "Re-Quantize All & Import",
                        onClick = { state.commitGlobalQuantizeImport() },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickResolutionCard(
                        title = "Auto-Snap to Existing Palette",
                        description = "Keep existing palette untouched; map new pixels to nearest BGR555 slot.",
                        buttonLabel = "Auto-Snap & Import",
                        onClick = { state.commitSnapImport() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    QuickResolutionCard(
                        title = "Global 15-Color Re-Quantize",
                        description = "Recompute optimal 15-color Median-Cut across all textures + new image.",
                        buttonLabel = "Re-Quantize All & Import",
                        onClick = { state.commitGlobalQuantizeImport() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // 4. Manual Incoming Color-to-Slot Mapping List with 4x4 Visual Swatch Grid Popover
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioSectionHeader(
                title = "Manual Incoming Color Mapping",
                badge = "${pending.uniqueColors.size}",
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(metrics.radiusSM))
                        .background(colors.surface0)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusSM))
                        .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (color in pending.uniqueColors) {
                    val targetSlot = manualMapping[color] ?: 1
                    val isUnmapped = color in pending.unmappedColors

                    ManualColorMapRow(
                        incomingColor = color,
                        isUnmapped = isUnmapped,
                        targetSlot = targetSlot,
                        state = state,
                        onSelectSlot = { newSlot -> manualMapping[color] = newSlot },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickResolutionCard(
    title: String,
    description: String,
    buttonLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val cardShape = RoundedCornerShape(metrics.radiusSM)

    Column(
        modifier =
            modifier
                .clip(cardShape)
                .background(colors.surface2, cardShape)
                .border(1.dp, colors.borderSubtle, cardShape)
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = description,
            style = typography.caption,
            color = colors.textSecondary,
        )
        StudioButton(
            label = buttonLabel,
            variant = StudioButtonVariant.Secondary,
            compact = true,
            onClick = onClick,
        )
    }
}

@Composable
private fun ManualColorMapRow(
    incomingColor: SnesColor,
    isUnmapped: Boolean,
    targetSlot: Int,
    state: AtlasEditorState,
    onSelectSlot: (Int) -> Unit,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    var pickerOpen by remember { mutableStateOf(false) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(metrics.radiusXS))
                .background(colors.surface1)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(metrics.radiusXS))
                        .background(Color(incomingColor.argb))
                        .border(1.dp, colors.swatchInnerRim, RoundedCornerShape(metrics.radiusXS)),
            )
            Text(
                text = incomingColor.hexString,
                style = typography.monoBody,
                color = colors.textPrimary,
            )
            if (isUnmapped) {
                StudioBadge(
                    text = "OVERFLOW",
                    textColor = colors.semanticWarningText,
                    backgroundColor = colors.semanticWarningSubtle,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = StudioIcons.ArrowRight,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(13.dp),
            )

            Box {
                val slotColor = state.palette[targetSlot]
                val btnShape = RoundedCornerShape(metrics.radiusSM)
                Row(
                    modifier =
                        Modifier
                            .height(metrics.controlHeightSM)
                            .clip(btnShape)
                            .background(colors.surface2, btnShape)
                            .border(1.dp, colors.borderSubtle, btnShape)
                            .clickable { pickerOpen = true }
                            .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .let { mod ->
                                    if (targetSlot == 0) {
                                        mod.checkerboardBackground(3.dp, colors.checkerDark, colors.checkerLight)
                                    } else {
                                        mod.background(Color(slotColor.argb))
                                    }
                                }.border(1.dp, colors.swatchInnerRim, RoundedCornerShape(3.dp)),
                    )
                    Text(
                        text =
                            if (targetSlot == 0) {
                                "Slot 0 ($0000)"
                            } else {
                                "Slot $targetSlot (${slotColor.hexString})"
                            },
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

                // 4x4 Visual Swatch Grid Popover
                DropdownMenu(
                    expanded = pickerOpen,
                    onDismissRequest = { pickerOpen = false },
                    modifier = Modifier.background(colors.surface2),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Select Target Palette Slot",
                            style = typography.caption,
                            color = colors.textSecondary,
                        )
                        for (r in 0..3) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (c in 0..3) {
                                    val s = r * 4 + c
                                    val sCol = state.palette[s]
                                    val selected = s == targetSlot
                                    val cellShape = RoundedCornerShape(metrics.radiusXS)
                                    Column(
                                        modifier =
                                            Modifier
                                                .width(54.dp)
                                                .clip(cellShape)
                                                .background(
                                                    if (selected) colors.accentSubtle else colors.surface1,
                                                    cellShape,
                                                ).border(
                                                    width = if (selected) 1.5.dp else 1.dp,
                                                    color = if (selected) colors.accentHover else colors.borderSubtle,
                                                    shape = cellShape,
                                                ).clickable {
                                                    onSelectSlot(s)
                                                    pickerOpen = false
                                                }.padding(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(18.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .let { mod ->
                                                        if (s == 0) {
                                                            mod.checkerboardBackground(
                                                                3.dp,
                                                                colors.checkerDark,
                                                                colors.checkerLight,
                                                            )
                                                        } else {
                                                            mod.background(Color(sCol.argb))
                                                        }
                                                    }.border(1.dp, colors.swatchInnerRim, RoundedCornerShape(2.dp)),
                                        )
                                        Text(
                                            text = if (s == 0) "0 Trans" else "#$s",
                                            style = typography.microBadge,
                                            color = colors.textPrimary,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
