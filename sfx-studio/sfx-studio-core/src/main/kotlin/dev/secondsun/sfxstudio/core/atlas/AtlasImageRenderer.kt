package dev.secondsun.sfxstudio.core.atlas

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object AtlasImageRenderer {
    /**
     * Renders a SourceTexture into a BufferedImage using the specified palette.
     * Slot 0 is rendered as ARGB transparent (0x00000000).
     */
    fun renderToBufferedImage(
        texture: SourceTexture,
        palette: SnesPalette,
    ): BufferedImage {
        val image = BufferedImage(texture.width, texture.height, BufferedImage.TYPE_INT_ARGB)
        for (v in 0 until texture.height) {
            for (u in 0 until texture.width) {
                val slot = texture.getPixel(u, v)
                val argb =
                    if (slot == 0) {
                        0x00000000 // Transparent
                    } else {
                        palette[slot].argb
                    }
                image.setRGB(u, v, argb)
            }
        }
        return image
    }

    /**
     * Renders the entire TextureAtlas to a BufferedImage.
     */
    fun renderAtlasToBufferedImage(
        atlas: TextureAtlas,
        palette: SnesPalette,
    ): BufferedImage {
        val image = BufferedImage(atlas.width, atlas.height, BufferedImage.TYPE_INT_ARGB)
        val binary = atlas.generateBinary()
        for (y in 0 until atlas.height) {
            val rowOffset = y * atlas.width
            for (x in 0 until atlas.width) {
                val slot = binary[rowOffset + x].toInt() and 0x0F
                val argb =
                    if (slot == 0) {
                        0x00000000 // Transparent
                    } else {
                        palette[slot].argb
                    }
                image.setRGB(x, y, argb)
            }
        }
        return image
    }

    /**
     * Exports the entire TextureAtlas to a PNG file with transparency on slot 0.
     */
    fun writeAtlasPng(
        atlas: TextureAtlas,
        palette: SnesPalette,
        destinationFile: File,
    ) {
        val image = renderAtlasToBufferedImage(atlas, palette)
        destinationFile.parentFile?.mkdirs()
        ImageIO.write(image, "PNG", destinationFile)
    }
}
