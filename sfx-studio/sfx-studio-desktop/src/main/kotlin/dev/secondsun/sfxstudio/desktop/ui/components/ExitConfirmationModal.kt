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
fun ExitConfirmationModal(
    studioState: StudioState,
    onConfirmExit: () -> Unit,
) {
    if (!studioState.showExitConfirmationModal) return

    val dirtySessions = studioState.openSessions.filter { it.hasUnsavedChanges }
    val dirtyTitles =
        if (dirtySessions.isNotEmpty()) {
            dirtySessions.joinToString(", ") { "'${it.displayTitle}'" }
        } else {
            "open documents"
        }

    StudioModalDialog(
        onDismissRequest = { studioState.showExitConfirmationModal = false },
        title = "Save Changes Before Exiting?",
        subtitle = "${dirtySessions.size} modified document(s)",
        preferredWidth = 480.dp,
        footerActions = {
            StudioButton(
                label = "Cancel",
                variant = StudioButtonVariant.Ghost,
                onClick = { studioState.showExitConfirmationModal = false },
            )
            StudioButton(
                label = "Discard & Exit",
                variant = StudioButtonVariant.DangerSolid,
                onClick = {
                    studioState.showExitConfirmationModal = false
                    onConfirmExit()
                },
            )
            StudioButton(
                label = "Save & Exit",
                variant = StudioButtonVariant.Primary,
                icon = StudioIcons.Save,
                onClick = {
                    if (studioState.saveAllDirtySessions()) {
                        studioState.showExitConfirmationModal = false
                        onConfirmExit()
                    }
                },
            )
        },
    ) {
        Text(
            text = "You have unsaved changes in $dirtyTitles. Save all changes before exiting SuperFX Studio?",
            style = StudioTheme.typography.bodyRegular,
            color = StudioTheme.colors.textPrimary,
        )
    }
}
