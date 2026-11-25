package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioModalDialog
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.checkerboardBackground

@Composable
fun UnassignPromptModal(state: AtlasEditorState) {
    val slot = state.unassignPromptSlot ?: return
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    var targetSlot by remember(slot) { mutableStateOf(0) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    val inUseNames =
        remember(slot, state.atlas.textures) {
            state.atlas.textures
                .filter { it.usedPaletteIndices().contains(slot) }
                .map { it.name }
        }

    StudioModalDialog(
        onDismissRequest = { state.unassignPromptSlot = null },
        title = "Palette Slot $slot is in Use",
        subtitle = "Remap referenced texels before unassigning ${state.palette[slot].hexString}",
        preferredWidth = 460.dp,
        footerActions = {
            StudioButton(
                label = "Cancel",
                variant = StudioButtonVariant.Ghost,
                onClick = { state.unassignPromptSlot = null },
            )
            StudioButton(
                label = "Remap & Unassign",
                variant = StudioButtonVariant.DangerSolid,
                icon = StudioIcons.Trash,
                onClick = { state.confirmUnassignSlot(slot, targetSlot) },
            )
        },
    ) {
        Text(
            text =
                if (inUseNames.isNotEmpty()) {
                    "Palette slot $slot (${state.palette[slot].hexString}) is referenced by ${inUseNames.size} texture(s): " +
                        "${inUseNames.joinToString(", ")}. Choose a replacement slot before unassigning:"
                } else {
                    "Choose a replacement palette slot for pixels referencing slot $slot:"
                },
            style = typography.bodyRegular,
            color = colors.textSecondary,
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Remap Pixels To", style = typography.bodyMedium, color = colors.textSecondary)

            Box(modifier = Modifier.fillMaxWidth()) {
                val targetCol = state.palette[targetSlot]
                val btnShape = RoundedCornerShape(metrics.radiusSM)
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(metrics.controlHeight)
                            .clip(btnShape)
                            .background(colors.surface0, btnShape)
                            .border(1.dp, colors.borderSubtle, btnShape)
                            .clickable { dropdownExpanded = true }
                            .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .let { mod ->
                                    if (targetSlot == 0) {
                                        mod.checkerboardBackground(3.dp, colors.checkerDark, colors.checkerLight)
                                    } else {
                                        mod.background(Color(targetCol.argb))
                                    }
                                }.border(1.dp, colors.swatchInnerRim, RoundedCornerShape(3.dp)),
                    )
                    Text(
                        text =
                            if (targetSlot == 0) {
                                "Slot 0 (Transparent $0000)"
                            } else {
                                "Slot $targetSlot (${targetCol.hexString})"
                            },
                        style = typography.monoBody,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = StudioIcons.ChevronDown,
                        contentDescription = null,
                        tint = colors.textMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }

                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier.background(colors.surface2),
                ) {
                    for (s in 0..15) {
                        if (s == slot) continue
                        val sCol = state.palette[s]
                        DropdownMenuItem(
                            onClick = {
                                targetSlot = s
                                dropdownExpanded = false
                            },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(16.dp)
                                            .clip(RoundedCornerShape(3.dp))
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
                                            }.border(1.dp, colors.swatchInnerRim, RoundedCornerShape(3.dp)),
                                )
                                Text(
                                    text =
                                        if (s == 0) {
                                            "Slot 0 (Transparent $0000)"
                                        } else {
                                            "Slot $s (${sCol.hexString})"
                                        },
                                    style = typography.monoCaption,
                                    color = colors.textPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
