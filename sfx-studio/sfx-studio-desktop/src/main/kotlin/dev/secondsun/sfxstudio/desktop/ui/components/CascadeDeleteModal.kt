package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.atlas.CascadeTargetKind
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioBadge
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioModalDialog
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.HairlineEdge
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.drawEdgeHairline

@Composable
fun CascadeDeleteModal(state: AtlasEditorState) {
    val pending = state.pendingCascadeDelete ?: return
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    val kindNoun =
        when (pending.kind) {
            CascadeTargetKind.TEXTURE -> "texture"
            CascadeTargetKind.SLICE -> "virtual slice"
            CascadeTargetKind.ALIAS -> "descriptor alias"
        }

    val totalDeps = pending.dependentSlices.size + pending.dependentAliases.size

    StudioModalDialog(
        onDismissRequest = { state.cancelCascadeDelete() },
        title = "Delete Dependent Descriptors?",
        subtitle = "Removing $kindNoun '${pending.targetName}' affects $totalDeps dependent descriptor(s)",
        preferredWidth = 480.dp,
        footerActions = {
            StudioButton(
                label = "Cancel",
                variant = StudioButtonVariant.Ghost,
                onClick = { state.cancelCascadeDelete() },
            )
            StudioButton(
                label = "Delete & Cascade",
                variant = StudioButtonVariant.DangerSolid,
                icon = StudioIcons.Trash,
                onClick = { state.confirmCascadeDelete() },
            )
        },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(metrics.radiusSM))
                    .background(colors.semanticErrorSubtle)
                    .drawEdgeHairline(HairlineEdge.LEFT, colors.semanticError, strokeDp = 3.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = StudioIcons.StatusWarn,
                contentDescription = null,
                tint = colors.semanticError,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text =
                    "Deleting '${pending.targetName}' will also remove the following dependent descriptors " +
                        "and prune them from TEXTURE_TABLE:",
                style = typography.bodyRegular,
                color = colors.semanticErrorText,
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(metrics.radiusSM))
                    .background(colors.surface0)
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusSM))
                    .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (sliceName in pending.dependentSlices) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StudioBadge(
                        text = "SLICE",
                        textColor = colors.sliceCyan,
                        backgroundColor = colors.sliceCyan.copy(alpha = 0.18f),
                    )
                    Text(
                        text = sliceName,
                        style = typography.monoBody,
                        color = colors.textPrimary,
                    )
                }
            }
            for (aliasName in pending.dependentAliases) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StudioBadge(
                        text = "ALIAS",
                        textColor = colors.aliasAmber,
                        backgroundColor = colors.aliasAmber.copy(alpha = 0.18f),
                    )
                    Text(
                        text = aliasName,
                        style = typography.monoBody,
                        color = colors.textPrimary,
                    )
                }
            }
        }
    }
}
