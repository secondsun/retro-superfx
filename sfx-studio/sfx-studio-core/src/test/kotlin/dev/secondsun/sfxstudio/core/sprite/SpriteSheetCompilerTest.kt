package dev.secondsun.sfxstudio.core.sprite

import dev.secondsun.sfxstudio.core.atlas.SnesColor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class SpriteSheetCompilerTest {
    @Test
    fun testSnes4bppBitplaneEncodingAndRoundTrip() {
        // 8x8 tile exercising every color index 0..15 across rows
        val pixels = ByteArray(64) { idx -> (idx and 0x0F).toByte() }
        val encoded = SpriteSheetCompiler.encodeSnes4bppTile(pixels, stride = 8, tileX = 0, tileY = 0)
        assertEquals(SpriteSheetCompiler.SNES_4BPP_TILE_BYTES, encoded.size)

        // Row 0 has pixels [0, 1, 2, 3, 4, 5, 6, 7]
        // plane 0 (bit 0): 0,1,0,1,0,1,0,1 -> 0b01010101 = 0x55
        // plane 1 (bit 1): 0,0,1,1,0,0,1,1 -> 0b00110011 = 0x33
        // plane 2 (bit 2): 0,0,0,0,1,1,1,1 -> 0b00001111 = 0x0F
        // plane 3 (bit 3): 0,0,0,0,0,0,0,0 -> 0b00000000 = 0x00
        assertEquals(0x55, encoded[0].toInt() and 0xFF)
        assertEquals(0x33, encoded[1].toInt() and 0xFF)
        assertEquals(0x0F, encoded[16].toInt() and 0xFF)
        assertEquals(0x00, encoded[17].toInt() and 0xFF)

        // Row 1 has pixels [8, 9, 10, 11, 12, 13, 14, 15] -> plane 3 = 0xFF
        assertEquals(0x55, encoded[2].toInt() and 0xFF)
        assertEquals(0x33, encoded[3].toInt() and 0xFF)
        assertEquals(0x0F, encoded[18].toInt() and 0xFF)
        assertEquals(0xFF, encoded[19].toInt() and 0xFF)

        val decoded = SpriteSheetCompiler.decodeSnes4bppTile(encoded)
        assertArrayEquals(pixels, decoded)
    }

    @Test
    fun testCompile16x24MultiFrameExactMaskParityAndPalette(
        @TempDir tempDir: File,
    ) {
        // 2 frames side-by-side: 32x24 ARGB PNG
        val img = BufferedImage(32, 24, BufferedImage.TYPE_INT_ARGB)
        // Frame 0 (x=0..15, y=0..23):
        // - (0, 0) pure black (0,0,0,255) -> clamped to BGR555 $0001
        // - (7, 0) red (248,0,0,255)
        // - (8, 0) green (0,248,0,255)
        // - (15, 23) blue (0,0,248,255)
        img.setRGB(0, 0, Color(0, 0, 0, 255).rgb)
        img.setRGB(7, 0, Color(248, 0, 0, 255).rgb)
        img.setRGB(8, 0, Color(0, 248, 0, 255).rgb)
        img.setRGB(15, 23, Color(0, 0, 248, 255).rgb)

        // Frame 1 (x=16..31, y=0..23):
        // - Full horizontal stripe across y=12 (x=0..15 local) in white (248,248,248,255)
        for (x in 0 until 16) {
            img.setRGB(16 + x, 12, Color(248, 248, 248, 255).rgb)
        }

        val pngFile = File(tempDir, "hero.png")
        ImageIO.write(img, "png", pngFile)

        val compiled = SpriteSheetCompiler.compileFile(pngFile, SpriteSheetSpec(frameWidth = 16, frameHeight = 24))
        assertEquals(2, compiled.frameCount)
        assertEquals(16, compiled.frameWidth)
        assertEquals(24, compiled.frameHeight)
        assertEquals(6, compiled.tilesPerFrame)
        assertEquals(2, compiled.maskRowBytes)
        assertEquals(48, compiled.maskFrameBytes)

        // Palette: 32 bytes, slot 0 = $0000, pure black clamped to $0001
        assertEquals(32, compiled.palBytes.size)
        assertEquals(0x00, compiled.palBytes[0].toInt() and 0xFF)
        assertEquals(0x00, compiled.palBytes[1].toInt() and 0xFF)
        val slot1Word = (compiled.palBytes[2].toInt() and 0xFF) or ((compiled.palBytes[3].toInt() and 0xFF) shl 8)
        assertEquals(SnesColor.OPAQUE_BLACK.bgr555, slot1Word)

        // CHR: 2 frames * 6 tiles * 32 bytes = 384 bytes
        assertEquals(2 * 6 * 32, compiled.chrBytes.size)

        // MSK: 8-byte header + 2*2 offset table + 2*48 mask bytes = 108 bytes
        assertEquals(8 + 4 + 96, compiled.mskBytes.size)
        val parsedMsk = SpriteMaskPacker.parseMskBinary(compiled.mskBytes)
        assertEquals(SpriteMaskPacker.MSK_MAGIC, parsedMsk.magic)
        assertEquals(2, parsedMsk.frameCount)
        assertEquals(16, parsedMsk.frameWidth)
        assertEquals(24, parsedMsk.frameHeight)
        assertEquals(2, parsedMsk.rowBytes)
        assertEquals(48, parsedMsk.frameBytes)
        assertEquals(listOf(12, 60), parsedMsk.frameOffsets)

        // Frame 0 row 0: pixels x=0 (bit 7) and x=7 (bit 0) -> 0x81; pixel x=8 (bit 7 of byte 1) -> 0x80
        assertEquals(0x81, parsedMsk.frameMasks[0][0].toInt() and 0xFF)
        assertEquals(0x80, parsedMsk.frameMasks[0][1].toInt() and 0xFF)
        // Frame 0 row 23: pixel x=15 (bit 0 of byte 1) -> 0x01
        assertEquals(0x00, parsedMsk.frameMasks[0][23 * 2].toInt() and 0xFF)
        assertEquals(0x01, parsedMsk.frameMasks[0][23 * 2 + 1].toInt() and 0xFF)

        // Frame 1 row 12: all 16 pixels opaque -> 0xFF, 0xFF
        assertEquals(0xFF, parsedMsk.frameMasks[1][12 * 2].toInt() and 0xFF)
        assertEquals(0xFF, parsedMsk.frameMasks[1][12 * 2 + 1].toInt() and 0xFF)

        // Verify 100% pixel-exact parity between decoded 4bpp .chr and 1-bit .msk across every pixel
        for (frame in compiled.frames) {
            val mask = parsedMsk.frameMasks[frame.index]
            for (ty in 0 until compiled.tilesY) {
                for (tx in 0 until compiled.tilesX) {
                    val tileIdx = frame.chrTileIndex + ty * compiled.tilesX + tx
                    val tilePixels =
                        SpriteSheetCompiler.decodeSnes4bppTile(
                            compiled.chrBytes,
                            tileIdx * SpriteSheetCompiler.SNES_4BPP_TILE_BYTES,
                        )
                    for (py in 0 until 8) {
                        for (px in 0 until 8) {
                            val x = tx * 8 + px
                            val y = ty * 8 + py
                            val chrColor = tilePixels[py * 8 + px].toInt() and 0x0F
                            val maskByte = mask[y * compiled.maskRowBytes + (x / 8)].toInt() and 0xFF
                            val maskBit = (maskByte ushr (7 - (x % 8))) and 1
                            assertEquals(
                                if (chrColor != 0) 1 else 0,
                                maskBit,
                                "Mask/CHR parity mismatch at frame=${frame.index} ($x, $y)",
                            )
                        }
                    }
                }
            }
        }

        // Verify ca65 include symbols
        assertTrue(compiled.includeText.contains("HERO_FRAME_COUNT = 2"))
        assertTrue(compiled.includeText.contains("HERO_FRAME_WIDTH = 16"))
        assertTrue(compiled.includeText.contains("HERO_FRAME_HEIGHT = 24"))
        assertTrue(compiled.includeText.contains("HERO_TILES_PER_FRAME = 6"))
        assertTrue(compiled.includeText.contains("HERO_MSK_FRAME_BYTES = 48"))
        assertTrue(compiled.includeText.contains("HERO_FRAME_0_TILE = 0"))
        assertTrue(compiled.includeText.contains("HERO_FRAME_1_TILE = 6"))
        assertTrue(compiled.includeText.contains("HERO_FRAME_0_MSK_OFFSET = 12"))
        assertTrue(compiled.includeText.contains("HERO_FRAME_1_MSK_OFFSET = 60"))
    }

    @Test
    fun testCompile16x16AndColorKeyTransparency() {
        // Opaque RGB image using magenta (0xFF00FF) as color-key transparency
        val img =
            dev.secondsun.sfxstudio.core.image
                .ArgbImage(16, 16, hasAlpha = false)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                img.setRGB(x, y, 0xFF00FF)
            }
        }
        img.setRGB(1, 2, 0x2080E0)
        img.setRGB(14, 15, 0xE04020)

        val compiled =
            SpriteSheetCompiler.compile(
                image = img,
                name = "chest",
                spec = SpriteSheetSpec(frameWidth = 16, frameHeight = 16, transparentRgb = 0xFF00FF),
            )
        assertEquals(1, compiled.frameCount)
        assertEquals(4, compiled.tilesPerFrame)
        assertEquals(32, compiled.maskFrameBytes)
        assertEquals(8 + 2 + 32, compiled.mskBytes.size)

        val mask = compiled.frames[0].maskBytes
        // (1, 2) -> bit 6 (0x40) of row 2 byte 0
        assertEquals(0x40, mask[2 * 2].toInt() and 0xFF)
        // (14, 15) -> bit 1 (0x02) of row 15 byte 1
        assertEquals(0x02, mask[15 * 2 + 1].toInt() and 0xFF)
    }

    @Test
    fun testQuantizationAndNoQuantizeRejection() {
        val img =
            dev.secondsun.sfxstudio.core.image
                .ArgbImage(16, 24)
        // Paint 20 distinct opaque BGR555 colors in row 0..1
        for (i in 0 until 20) {
            val r = (i * 12 + 8).coerceAtMost(248)
            val g = ((20 - i) * 12).coerceAtMost(248)
            val b = ((i * 23) % 248)
            img.setRGB(i % 16, i / 16, Color(r, g, b, 255).rgb)
        }

        assertThrows(IllegalArgumentException::class.java) {
            SpriteSheetCompiler.compile(
                image = img,
                name = "too_many",
                spec = SpriteSheetSpec(frameWidth = 16, frameHeight = 24, autoQuantize = false),
            )
        }

        val quantized =
            SpriteSheetCompiler.compile(
                image = img,
                name = "quantized",
                spec = SpriteSheetSpec(frameWidth = 16, frameHeight = 24, autoQuantize = true),
            )
        assertTrue(quantized.palette.assignedCount <= 15)
        // All 20 opaque pixels must remain non-zero and set in the mask
        val mask = quantized.frames[0].maskBytes
        assertEquals(0xFF, mask[0].toInt() and 0xFF)
        assertEquals(0xFF, mask[1].toInt() and 0xFF)
        assertEquals(0xF0, mask[2].toInt() and 0xFF)
        assertEquals(0x00, mask[3].toInt() and 0xFF)
    }

    @Test
    fun testDeterministicExport(
        @TempDir tempDir: File,
    ) {
        val img =
            dev.secondsun.sfxstudio.core.image
                .ArgbImage(16, 24)
        img.setRGB(3, 5, Color(100, 200, 50, 255).rgb)
        val compiled1 = SpriteSheetCompiler.compile(img, "slime")
        val compiled2 = SpriteSheetCompiler.compile(img, "slime")

        val out1 = File(tempDir, "run1")
        val out2 = File(tempDir, "run2")
        val exp1 = SpriteSheetCompiler.export(compiled1, out1)
        val exp2 = SpriteSheetCompiler.export(compiled2, out2)

        assertArrayEquals(exp1.chrFile.readBytes(), exp2.chrFile.readBytes())
        assertArrayEquals(exp1.palFile.readBytes(), exp2.palFile.readBytes())
        assertArrayEquals(exp1.mskFile.readBytes(), exp2.mskFile.readBytes())
        assertEquals(exp1.includeFile.readText(), exp2.includeFile.readText())
    }
}
