package dev.secondsun.sfxstudio.desktop.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.secondsun.sfxstudio.core.atlas.AtlasImageRenderer
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import java.awt.image.BufferedImage
import java.io.File

object TextureComposeRenderer {
    /**
     * Renders a SourceTexture into a BufferedImage using the specified palette.
     * Slot 0 is rendered as ARGB transparent (0x00000000).
     */
    fun renderToBufferedImage(
        texture: SourceTexture,
        palette: SnesPalette,
    ): BufferedImage = AtlasImageRenderer.renderToBufferedImage(texture, palette)

    /**
     * Converts a SourceTexture to Compose ImageBitmap.
     */
    fun renderToImageBitmap(
        texture: SourceTexture,
        palette: SnesPalette,
    ): ImageBitmap = renderToBufferedImage(texture, palette).toComposeImageBitmap()

    /**
     * Renders the entire TextureAtlas to a BufferedImage.
     */
    fun renderAtlasToBufferedImage(
        atlas: TextureAtlas,
        palette: SnesPalette,
    ): BufferedImage = AtlasImageRenderer.renderAtlasToBufferedImage(atlas, palette)

    /**
     * Converts the entire TextureAtlas to Compose ImageBitmap.
     */
    fun renderAtlasToImageBitmap(
        atlas: TextureAtlas,
        palette: SnesPalette,
    ): ImageBitmap = renderAtlasToBufferedImage(atlas, palette).toComposeImageBitmap()

    /**
     * Exports the entire TextureAtlas to a PNG file with transparency on slot 0.
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
