package dev.secondsun.sfxstudio.desktop.ui

import androidx.compose.ui.graphics.ImageBitmap
import dev.secondsun.sfxstudio.core.atlas.AtlasImageRenderer
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.image.ArgbImage
import dev.secondsun.sfxstudio.desktop.awt.toStudioImageBitmap
import java.io.File

object TextureComposeRenderer {
    /**
     * Renders a [SourceTexture] into an [ArgbImage] using the specified palette.
     * Slot 0 is rendered as ARGB transparent (`0x00000000`).
     */
    fun renderToArgbImage(
        texture: SourceTexture,
        palette: SnesPalette,
    ): ArgbImage = AtlasImageRenderer.renderToArgbImage(texture, palette)

    /**
     * Converts a [SourceTexture] to Compose [ImageBitmap].
     */
    fun renderToImageBitmap(
        texture: SourceTexture,
        palette: SnesPalette,
    ): ImageBitmap = renderToArgbImage(texture, palette).toStudioImageBitmap()

    /**
     * Renders the entire [TextureAtlas] to an [ArgbImage].
     */
    fun renderAtlasToArgbImage(
        atlas: TextureAtlas,
        palette: SnesPalette,
    ): ArgbImage = AtlasImageRenderer.renderAtlasToArgbImage(atlas, palette)

    /**
     * Converts the entire [TextureAtlas] to Compose [ImageBitmap].
     */
    fun renderAtlasToImageBitmap(
        atlas: TextureAtlas,
        palette: SnesPalette,
    ): ImageBitmap = renderAtlasToArgbImage(atlas, palette).toStudioImageBitmap()

    /**
     * Exports the entire [TextureAtlas] to a PNG file with transparency on slot 0.
     */
    fun writeAtlasPng(
        atlas: TextureAtlas,
        palette: SnesPalette,
        destinationFile: File,
    ) = AtlasImageRenderer.writeAtlasPng(atlas, palette, destinationFile)
}

typealias TextureRenderer = TextureComposeRenderer

fun SourceTexture.toImageBitmap(palette: SnesPalette): ImageBitmap = TextureComposeRenderer.renderToImageBitmap(this, palette)

fun TextureAtlas.toImageBitmap(palette: SnesPalette): ImageBitmap = TextureComposeRenderer.renderAtlasToImageBitmap(this, palette)
