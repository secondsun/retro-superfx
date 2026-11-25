package dev.secondsun.sfxstudio.desktop.awt

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.secondsun.sfxstudio.core.image.ArgbImage
import dev.secondsun.sfxstudio.desktop.shell.StudioPlatform
import java.awt.image.BufferedImage

internal fun createDefaultStudioPlatform(): StudioPlatform = AwtStudioPlatform()

internal fun ArgbImage.toStudioImageBitmap(): ImageBitmap {
    val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    buffered.setRGB(0, 0, width, height, pixels, 0, width)
    return buffered.toComposeImageBitmap()
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun studioModalDialogProperties(scrimColor: Color): DialogProperties =
    DialogProperties(
        usePlatformDefaultWidth = false,
        scrimColor = scrimColor,
    )

internal const val STUDIO_MODAL_DRAWS_OWN_SCRIM: Boolean = false

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlatformStudioTooltip(
    tooltip: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = tooltip,
        delayMillis = 450,
        tooltipPlacement =
            TooltipPlacement.CursorPoint(
                offset = DpOffset(0.dp, 16.dp),
            ),
        content = content,
    )
}
