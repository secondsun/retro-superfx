package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioBadge
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSectionHeader
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.checkerboardBackground
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HexPreviewTab(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    val binBytes = remember(state.atlas) { state.atlas.generateBinary() }
    val palBytes = remember(state.palette) { state.palette.toBgr555Binary() }

    val activeSlice =
        remember(state.atlas.slices, state.selectedSliceName) {
            val sName = state.selectedSliceName
            if (sName == null) {
                null
            } else {
                state.atlas.slices.find { it.name.equals(sName, ignoreCase = true) }
            }
        }

    val highlightMeta =
        remember(state.atlas, state.selectedTextureIndex, activeSlice) {
            val offsets = HashSet<Int>()
            var firstOffset: Int? = null
            var label: String? = null
            var isSlice = false

            if (activeSlice != null) {
                val parent =
                    state.atlas.textures.find {
                        it.name.equals(activeSlice.sourceTexture, ignoreCase = true)
                    }
                if (parent != null) {
                    isSlice = true
                    label = "${activeSlice.name} (${activeSlice.width * activeSlice.height} B)"
                    val baseX = parent.x + activeSlice.x
                    val baseY = parent.y + activeSlice.y
                    firstOffset = baseY * state.atlas.width + baseX
                    for (v in 0 until activeSlice.height) {
                        val rowStart = (baseY + v) * state.atlas.width + baseX
                        for (u in 0 until activeSlice.width) {
                            offsets.add(rowStart + u)
                        }
                    }
                }
            } else {
                val tex = state.selectedTexture
                if (tex != null) {
                    label = "${tex.name} (${tex.width * tex.height} B)"
                    firstOffset = tex.y * state.atlas.width + tex.x
                    for (v in 0 until tex.height) {
                        val rowStart = (tex.y + v) * state.atlas.width + tex.x
                        for (u in 0 until tex.width) {
                            offsets.add(rowStart + u)
                        }
                    }
                }
            }
            Triple(offsets, firstOffset, label to isSlice)
        }

    val selectedOffsets = highlightMeta.first
    val firstHighlightOffset = highlightMeta.second
    val highlightLabel = highlightMeta.third.first
    val highlightIsSlice = highlightMeta.third.second

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.bgCanvas)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // --- 1. SNES BGR555 Palette Binary Strip ---
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioSectionHeader(
                title = "SNES BGR555 Palette Binary",
                badge = "${palBytes.size} B · 16 words",
                trailing = {
                    StudioButton(
                        label = "Export .pal...",
                        variant = StudioButtonVariant.Secondary,
                        compact = true,
                        icon = StudioIcons.Export,
                        onClick = { state.promptExportPaletteBin() },
                    )
                },
            )

            FlowRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(metrics.radiusSM))
                        .background(colors.surface0)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusSM))
                        .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (slot in 0..15) {
                    val low = palBytes[slot * 2].toInt() and 0xFF
                    val high = palBytes[slot * 2 + 1].toInt() and 0xFF
                    val word = (high shl 8) or low
                    val isSelected = state.selectedPaletteSlot == slot
                    val slotColor = state.palette[slot]
                    val pillShape = RoundedCornerShape(metrics.radiusXS)

                    Row(
                        modifier =
                            Modifier
                                .clip(pillShape)
                                .background(
                                    if (isSelected) colors.accentSubtle else colors.surface1,
                                    pillShape,
                                ).border(
                                    1.dp,
                                    if (isSelected) colors.accentBorder else colors.borderSubtle,
                                    pillShape,
                                ).clickable { state.selectedPaletteSlot = slot }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .let { mod ->
                                        if (slot == 0) {
                                            mod.checkerboardBackground(2.dp, colors.checkerDark, colors.checkerLight)
                                        } else {
                                            mod.background(Color(slotColor.argb))
                                        }
                                    }.border(0.75.dp, colors.swatchInnerRim, CircleShape),
                        )
                        Text(
                            text = String.format("$%04X", word),
                            style = typography.monoCaption,
                            color = if (isSelected) colors.accentHover else colors.textPrimary,
                        )
                    }
                }
            }
        }

        // --- 2. Responsive Atlas Graphics Binary Hex Table ---
        BoxWithConstraints(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            val bytesPerRow = if (maxWidth >= 540.dp) 16 else 8
            val showAscii = maxWidth >= 660.dp
            val numRows = (binBytes.size + bytesPerRow - 1) / bytesPerRow
            val accentCol = if (highlightIsSlice) colors.sliceCyan else colors.aliasAmber

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StudioSectionHeader(
                    title = "Atlas Graphics Binary",
                    badge = "${binBytes.size} B",
                    trailing = {
                        if (highlightLabel != null) {
                            StudioBadge(
                                text = highlightLabel,
                                textColor = accentCol,
                                backgroundColor = accentCol.copy(alpha = 0.16f),
                                monospace = true,
                            )
                            if (firstHighlightOffset != null) {
                                StudioButton(
                                    label = "Jump",
                                    variant = StudioButtonVariant.Ghost,
                                    compact = true,
                                    onClick = {
                                        val targetRow = (firstHighlightOffset / bytesPerRow).coerceIn(0, (numRows - 1).coerceAtLeast(0))
                                        coroutineScope.launch {
                                            listState.scrollToItem(targetRow)
                                        }
                                    },
                                )
                            }
                        }
                        StudioButton(
                            label = "Export .bin...",
                            variant = StudioButtonVariant.Secondary,
                            compact = true,
                            icon = StudioIcons.Export,
                            onClick = { state.promptExportGraphicsBin() },
                        )
                    },
                )

                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(metrics.radiusSM))
                            .background(colors.surface0)
                            .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusSM))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(numRows) { row ->
                        val offset = row * bytesPerRow
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = String.format("$%04X", offset),
                                style = typography.monoBody,
                                color = colors.textMuted,
                                modifier = Modifier.width(54.dp),
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                for (col in 0 until bytesPerRow) {
                                    if (col == 8 && bytesPerRow == 16) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    val byteIndex = offset + col
                                    if (byteIndex < binBytes.size) {
                                        val b = binBytes[byteIndex].toInt() and 0xFF
                                        val isHighlighted = selectedOffsets.contains(byteIndex)
                                        val cellBg =
                                            if (isHighlighted) accentCol.copy(alpha = 0.16f) else Color.Transparent
                                        val textCol =
                                            when {
                                                isHighlighted ->
                                                    if (highlightIsSlice) colors.sliceCyan else colors.semanticWarningText
                                                b == 0 -> colors.textMuted
                                                else -> colors.textPrimary
                                            }
                                        Box(
                                            modifier =
                                                Modifier
                                                    .width(21.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(cellBg)
                                                    .padding(vertical = 1.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = String.format("%02X", b),
                                                style = typography.monoBody,
                                                color = textCol,
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.width(21.dp))
                                    }
                                }
                            }

                            if (showAscii) {
                                Spacer(modifier = Modifier.width(14.dp))
                                val asciiLine =
                                    buildString(bytesPerRow) {
                                        for (col in 0 until bytesPerRow) {
                                            val byteIndex = offset + col
                                            if (byteIndex < binBytes.size) {
                                                val b = binBytes[byteIndex].toInt() and 0x0F
                                                val ch =
                                                    when {
                                                        b == 0 -> '.'
                                                        b < 10 -> ('0' + b)
                                                        else -> ('A' + (b - 10))
                                                    }
                                                append(ch)
                                            }
                                        }
                                    }
                                Text(
                                    text = asciiLine,
                                    style = typography.monoBody,
                                    color = colors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
