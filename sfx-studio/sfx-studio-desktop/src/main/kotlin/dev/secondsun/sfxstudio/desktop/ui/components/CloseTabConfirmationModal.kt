package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioModalDialog
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme

@Composable
fun CloseTabConfirmationModal(studioState: StudioState) {
    val pendingIdx = studioState.pendingCloseSessionIndex ?: return
    val session =
        studioState.openSessions.getOrNull(pendingIdx) ?: run {
            studioState.pendingCloseSessionIndex = null
            return
        }

    StudioModalDialog(
        onDismissRequest = { studioState.pendingCloseSessionIndex = null },
        title = "Unsaved Changes",
        subtitle = session.documentTypeLabel,
        preferredWidth = 460.dp,
        footerActions = {
            StudioButton(
                label = "Cancel",
                variant = StudioButtonVariant.Ghost,
                onClick = { studioState.pendingCloseSessionIndex = null },
            )
            StudioButton(
                label = "Don't Save",
                variant = StudioButtonVariant.DangerSolid,
                onClick = { studioState.closeSession(pendingIdx) },
            )
            StudioButton(
                label = "Save",
                variant = StudioButtonVariant.Primary,
                icon = StudioIcons.Save,
                onClick = {
                    studioState.requestSaveSession(session) { saved ->
                        if (saved) {
                            studioState.closeSession(pendingIdx)
                        }
                    }
                },
            )
        },
    ) {
        Text(
            text = "You have unsaved changes in '${session.displayTitle}'. Save changes before closing this tab?",
            style = StudioTheme.typography.bodyRegular,
            color = StudioTheme.colors.textPrimary,
        )
    }
}
