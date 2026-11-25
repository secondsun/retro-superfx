package dev.secondsun.sfxstudio.desktop.awt

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.DialogProperties
import dev.secondsun.sfxstudio.core.image.ArgbImage
import dev.secondsun.sfxstudio.desktop.shell.StudioPlatform
import java.io.File

internal fun createDefaultStudioPlatform(): StudioPlatform = FallbackInMemoryStudioPlatform()

internal fun ArgbImage.toStudioImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

internal fun studioModalDialogProperties(
    @Suppress("UNUSED_PARAMETER") scrimColor: Color,
): DialogProperties =
    DialogProperties(
        usePlatformDefaultWidth = false,
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
    )

internal const val STUDIO_MODAL_DRAWS_OWN_SCRIM: Boolean = true

@Composable
internal fun PlatformStudioTooltip(
    @Suppress("UNUSED_PARAMETER") tooltip: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    content()
}

private class FallbackInMemoryStudioPlatform : StudioPlatform {
    override val isMacOs: Boolean = false
    private val prefs = LinkedHashMap<String, String>()

    override fun chooseOpenFile(
        title: String,
        initialDir: String?,
        allowedExtensions: Set<String>,
    ): File? = null

    override fun chooseSaveFile(
        title: String,
        defaultFileName: String,
        initialDir: String?,
    ): File? = null

    override fun chooseDirectory(
        title: String,
        initialDir: String?,
    ): File? = null

    override fun copyToClipboard(text: String) = Unit

    override fun getPreference(
        key: String,
        default: String?,
    ): String? = prefs[key] ?: default

    override fun setPreference(
        key: String,
        value: String,
    ) {
        prefs[key] = value
    }
}
