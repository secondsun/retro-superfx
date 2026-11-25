package dev.secondsun.sfxstudio.core.atlas

import dev.secondsun.sfxstudio.core.image.ArgbImage
import dev.secondsun.sfxstudio.core.image.StudioImageCodec
import java.io.File

object AtlasImageRenderer {
    /**
     * Renders a [SourceTexture] into an [ArgbImage] using the specified palette.
     * Slot 0 is rendered as ARGB transparent (`0x00000000`).
     */
    fun renderToArgbImage(
        texture: SourceTexture,
        palette: SnesPalette,
    ): ArgbImage {
        val pixels = IntArray(texture.width * texture.height)
        for (v in 0 until texture.height) {
            val rowOffset = v * texture.width
            for (u in 0 until texture.width) {
                val slot = texture.getPixel(u, v)
                pixels[rowOffset + u] = if (slot == 0) 0x00000000 else palette[slot].argb
            }
        }
        return ArgbImage(width = texture.width, height = texture.height, pixels = pixels)
    }

    /**
     * Renders the entire [TextureAtlas] to an [ArgbImage].
     */
    fun renderAtlasToArgbImage(
        atlas: TextureAtlas,
        palette: SnesPalette,
    ): ArgbImage {
        val pixels = IntArray(atlas.width * atlas.height)
        val binary = atlas.generateBinary()
        for (y in 0 until atlas.height) {
            val rowOffset = y * atlas.width
            for (x in 0 until atlas.width) {
                val slot = binary[rowOffset + x].toInt() and 0x0F
                pixels[rowOffset + x] = if (slot == 0) 0x00000000 else palette[slot].argb
            }
        }
        return ArgbImage(width = atlas.width, height = atlas.height, pixels = pixels)
    }

    /**
     * Exports the entire [TextureAtlas] to a PNG file with transparency on slot 0.
     */
    fun writeAtlasPng(
        atlas: TextureAtlas,
        palette: SnesPalette,
        destinationFile: File,
    ) {
        val image = renderAtlasToArgbImage(atlas, palette)
        StudioImageCodec.writePng(image, destinationFile)
    }
}
