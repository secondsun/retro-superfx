package dev.secondsun.sfxstudio.core.sprite

import dev.secondsun.sfxstudio.core.atlas.MedianCutQuantizer
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.image.ArgbImage
import dev.secondsun.sfxstudio.core.image.StudioImageCodec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compilation options for [SpriteSheetCompiler].
 *
 * Defaults target `16x24` `IsoRPGDemo` character/entity OAM metasprites (`16x16` + `16x8`).
 */
data class SpriteSheetSpec(
    val frameWidth: Int = 16,
    val frameHeight: Int = 24,
    val alphaThreshold: Int = 128,
    val transparentRgb: Int? = null,
    val autoQuantize: Boolean = true,
) {
    init {
        require(frameWidth in 8..255 && frameWidth % 8 == 0) {
            "frameWidth must be a multiple of 8 in 8..255, got $frameWidth"
        }
        require(frameHeight in 8..255 && frameHeight % 8 == 0) {
            "frameHeight must be a multiple of 8 in 8..255, got $frameHeight"
        }
        require(alphaThreshold in 1..255) {
            "alphaThreshold must be in 1..255, got $alphaThreshold"
        }
    }

    val tilesX: Int
        get() = frameWidth / 8

    val tilesY: Int
        get() = frameHeight / 8

    val tilesPerFrame: Int
        get() = tilesX * tilesY

    val maskRowBytes: Int
        get() = frameWidth / 8

    val maskFrameBytes: Int
        get() = maskRowBytes * frameHeight
}

/**
 * Single compiled sprite frame holding its 4-bit indexed pixels, SNES planar 4bpp OBJ tiles,
 * exact 1-bit Sprite Mask rows, and offsets within the compiled sheet binaries.
 */
data class CompiledSpriteFrame(
    val index: Int,
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val chrBytes: ByteArray,
    val maskBytes: ByteArray,
    val chrTileIndex: Int,
    val maskByteOffset: Int,
)

/**
 * Complete compiled output of a PNG sprite sheet: SNES 4bpp OBJ `.chr`, 32-byte BGR555 `.pal`,
 * 1-bit Sprite Mask `.msk`, and ca65 symbolic include `.i`.
 */
data class CompiledSpriteSheet(
    val name: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val frameCount: Int,
    val tilesX: Int,
    val tilesY: Int,
    val tilesPerFrame: Int,
    val maskRowBytes: Int,
    val maskFrameBytes: Int,
    val palette: SnesPalette,
    val frames: List<CompiledSpriteFrame>,
    val chrBytes: ByteArray,
    val palBytes: ByteArray,
    val mskBytes: ByteArray,
    val includeText: String,
)

data class ExportedSpriteAssets(
    val chrFile: File,
    val palFile: File,
    val mskFile: File,
    val includeFile: File,
)

data class ParsedSpriteMaskBinary(
    val magic: Int,
    val frameCount: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val rowBytes: Int,
    val frameBytes: Int,
    val frameOffsets: List<Int>,
    val frameMasks: List<ByteArray>,
)

/**
 * Packs and parses 1-bit-per-pixel Sprite Mask (`.msk`) binaries for ADR 0005 under-`BG1` OAM cutouts.
 *
 * Binary layout (little-endian):
 * - Header (8 bytes, offsets `0..7`):
 *   - `+0..+1`: magic `"SM"` (`0x53, 0x4D`, little-endian `$4D53`)
 *   - `+2`: `frame_count` (`u8`, `1..255`)
 *   - `+3`: `frame_width` (`u8`, pixels, multiple of 8)
 *   - `+4`: `frame_height` (`u8`, pixels, multiple of 8)
 *   - `+5`: `row_bytes` (`u8`, `frame_width / 8`)
 *   - `+6..+7`: `frame_bytes` (`u16` LE, `row_bytes * frame_height`, e.g. `48` for `16x24`)
 * - Frame Offset Table (`frame_count * 2` bytes, starting at offset `8`):
 *   - `frame_count` x `u16` LE byte offsets from start of `.msk` to each frame's packed 1-bit rows.
 * - Packed 1-bit Mask Data (`frame_count * frame_bytes` bytes, starting at `8 + frame_count * 2`):
 *   - Row-major (`y = 0..frame_height-1`), left-to-right (`bx = 0..row_bytes-1`).
 *   - **MSB-first**: bit 7 (`$80`) = leftmost pixel (`x = bx * 8 + 0`), bit 0 (`$01`) = rightmost pixel (`x = bx * 8 + 7`).
 *   - `1` = opaque pixel (punch `color 0` in `BG1`), `0` = transparent (skip).
 */
object SpriteMaskPacker {
    const val MSK_MAGIC: Int = 0x4D53 // "SM" little-endian
    const val MSK_HEADER_SIZE: Int = 8
    const val MAX_FRAMES: Int = 255

    /**
     * Packs an indexed-color frame (`0` = transparent, `1..15` = opaque) into MSB-first 1bpp rows.
     */
    fun packFrameMask(
        pixels: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        require(width > 0 && width % 8 == 0) { "Mask width must be a positive multiple of 8, got $width" }
        require(height > 0) { "Mask height must be positive, got $height" }
        require(pixels.size == width * height) {
            "Expected ${width * height} pixels for ${width}x$height frame, got ${pixels.size}"
        }

        val rowBytes = width / 8
        val out = ByteArray(rowBytes * height)
        for (y in 0 until height) {
            val pixelRowBase = y * width
            val outRowBase = y * rowBytes
            for (bx in 0 until rowBytes) {
                var maskByte = 0
                val xBase = bx * 8
                for (bit in 0 until 8) {
                    val colorIndex = pixels[pixelRowBase + xBase + bit].toInt() and 0xFF
                    if (colorIndex != 0) {
                        maskByte = maskByte or (1 shl (7 - bit))
                    }
                }
                out[outRowBase + bx] = maskByte.toByte()
            }
        }
        return out
    }

    /**
     * Builds the complete `.msk` binary from pre-packed per-frame 1-bit masks.
     */
    fun buildMskBinary(
        frameWidth: Int,
        frameHeight: Int,
        frameMasks: List<ByteArray>,
    ): ByteArray {
        require(frameMasks.isNotEmpty() && frameMasks.size <= MAX_FRAMES) {
            "Frame count must be in 1..$MAX_FRAMES, got ${frameMasks.size}"
        }
        require(frameWidth in 8..255 && frameWidth % 8 == 0) {
            "frameWidth must be a multiple of 8 in 8..255, got $frameWidth"
        }
        require(frameHeight in 8..255 && frameHeight % 8 == 0) {
            "frameHeight must be a multiple of 8 in 8..255, got $frameHeight"
        }

        val frameCount = frameMasks.size
        val rowBytes = frameWidth / 8
        val frameBytes = rowBytes * frameHeight
        for ((idx, mask) in frameMasks.withIndex()) {
            require(mask.size == frameBytes) {
                "Frame $idx mask size mismatch: expected $frameBytes bytes, got ${mask.size}"
            }
        }

        val dataOffset = MSK_HEADER_SIZE + frameCount * 2
        val totalBytes = dataOffset + frameCount * frameBytes
        require(totalBytes <= 0xFFFF) {
            "Sprite mask binary ($totalBytes bytes) exceeds 16-bit offset table limit (65535 bytes)"
        }

        val buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(MSK_MAGIC.toShort())
        buf.put((frameCount and 0xFF).toByte())
        buf.put((frameWidth and 0xFF).toByte())
        buf.put((frameHeight and 0xFF).toByte())
        buf.put((rowBytes and 0xFF).toByte())
        buf.putShort((frameBytes and 0xFFFF).toShort())

        for (i in 0 until frameCount) {
            val offset = dataOffset + i * frameBytes
            buf.putShort((offset and 0xFFFF).toShort())
        }

        for (mask in frameMasks) {
            buf.put(mask)
        }

        return buf.array()
    }

    /**
     * Parses a `.msk` binary back into structured fields for round-trip verification.
     */
    fun parseMskBinary(bytes: ByteArray): ParsedSpriteMaskBinary {
        require(bytes.size >= MSK_HEADER_SIZE) {
            "Sprite mask binary must be at least $MSK_HEADER_SIZE bytes, got ${bytes.size}"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.short.toInt() and 0xFFFF
        require(magic == MSK_MAGIC) {
            String.format("Invalid .msk magic: expected \$%04X, got \$%04X", MSK_MAGIC, magic)
        }
        val frameCount = buf.get().toInt() and 0xFF
        val frameWidth = buf.get().toInt() and 0xFF
        val frameHeight = buf.get().toInt() and 0xFF
        val rowBytes = buf.get().toInt() and 0xFF
        val frameBytes = buf.short.toInt() and 0xFFFF

        val expectedMinSize = MSK_HEADER_SIZE + frameCount * 2
        require(bytes.size >= expectedMinSize) {
            "Truncated .msk offset table: need at least $expectedMinSize bytes, got ${bytes.size}"
        }

        val offsets = List(frameCount) { buf.short.toInt() and 0xFFFF }
        val masks =
            offsets.mapIndexed { idx, off ->
                require(off + frameBytes <= bytes.size) {
                    "Frame $idx mask range [$off, ${off + frameBytes}) exceeds binary size ${bytes.size}"
                }
                bytes.copyOfRange(off, off + frameBytes)
            }

        return ParsedSpriteMaskBinary(
            magic = magic,
            frameCount = frameCount,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            rowBytes = rowBytes,
            frameBytes = frameBytes,
            frameOffsets = offsets,
            frameMasks = masks,
        )
    }
}

/**
 * Compiles PNG sprite sheets into SNES planar 4bpp OBJ tiles (`.chr`), 32-byte BGR555 palettes
 * (`.pal`), pixel-exact 1-bit Sprite Masks (`.msk`), and ca65 symbolic includes (`.i`).
 */
object SpriteSheetCompiler {
    const val SNES_4BPP_TILE_BYTES: Int = 32

    fun compileFile(
        pngFile: File,
        spec: SpriteSheetSpec = SpriteSheetSpec(),
        name: String = pngFile.nameWithoutExtension,
    ): CompiledSpriteSheet {
        require(pngFile.exists()) { "Sprite sheet file '${pngFile.path}' does not exist" }
        val image =
            StudioImageCodec.read(pngFile)
                ?: throw IllegalArgumentException("Failed to decode image '${pngFile.path}'")
        return compile(image = image, name = name, spec = spec)
    }

    fun compile(
        image: ArgbImage,
        name: String,
        spec: SpriteSheetSpec = SpriteSheetSpec(),
    ): CompiledSpriteSheet {
        require(name.isNotBlank()) { "Sprite sheet name must not be blank" }
        require(image.width > 0 && image.height > 0) {
            "Sprite sheet image must have positive dimensions, got ${image.width}x${image.height}"
        }
        require(image.width % spec.frameWidth == 0 && image.height % spec.frameHeight == 0) {
            "Sprite sheet dimensions (${image.width}x${image.height}) must be exact multiples of " +
                "frame size (${spec.frameWidth}x${spec.frameHeight})"
        }

        val cols = image.width / spec.frameWidth
        val rows = image.height / spec.frameHeight
        val frameCount = cols * rows
        require(frameCount in 1..SpriteMaskPacker.MAX_FRAMES) {
            "Sprite sheet frame count must be in 1..${SpriteMaskPacker.MAX_FRAMES}, got $frameCount"
        }

        val hasAlpha = image.hasAlpha
        val transparentRgbMasked = spec.transparentRgb?.and(0x00FFFFFF)

        // Pass 1: Extract per-frame SnesColor? grids (null = transparent, non-null = opaque BGR555)
        val rawFrames = ArrayList<Array<SnesColor?>>(frameCount)
        val allOpaqueColors = ArrayList<SnesColor>()

        for (fy in 0 until rows) {
            for (fx in 0 until cols) {
                val frameColors = arrayOfNulls<SnesColor>(spec.frameWidth * spec.frameHeight)
                val originX = fx * spec.frameWidth
                val originY = fy * spec.frameHeight
                for (y in 0 until spec.frameHeight) {
                    for (x in 0 until spec.frameWidth) {
                        val argb = image.getRGB(originX + x, originY + y)
                        val alpha = if (hasAlpha) (argb ushr 24) and 0xFF else 0xFF
                        val rgb24 = argb and 0x00FFFFFF
                        val isTransparent =
                            alpha < spec.alphaThreshold ||
                                (transparentRgbMasked != null && rgb24 == transparentRgbMasked)
                        if (!isTransparent) {
                            val r = (argb ushr 16) and 0xFF
                            val g = (argb ushr 8) and 0xFF
                            val b = argb and 0xFF
                            val snesColor = SnesColor.fromRgb888(r, g, b, clampBlack = true)
                            frameColors[y * spec.frameWidth + x] = snesColor
                            allOpaqueColors.add(snesColor)
                        }
                    }
                }
                rawFrames.add(frameColors)
            }
        }

        // Pass 2: Build or quantize 15-color + transparent SNES BGR555 palette
        val palette = buildPalette(name, allOpaqueColors, spec.autoQuantize)
        val palBytes = palette.toBgr555Binary()

        // Pass 3: Map indexed pixels, encode 4bpp planar tiles, and pack 1-bit masks
        val dataOffset = SpriteMaskPacker.MSK_HEADER_SIZE + frameCount * 2
        val compiledFrames = ArrayList<CompiledSpriteFrame>(frameCount)
        val chrOut = ByteArray(frameCount * spec.tilesPerFrame * SNES_4BPP_TILE_BYTES)
        var chrCursor = 0

        for ((frameIdx, rawColors) in rawFrames.withIndex()) {
            val pixels = ByteArray(spec.frameWidth * spec.frameHeight)
            for (i in rawColors.indices) {
                val color = rawColors[i]
                pixels[i] =
                    if (color == null) {
                        0
                    } else {
                        val slot = palette.findExactIndex(color) ?: palette.findNearestAssignedIndex(color)
                        (slot and 0x0F).toByte()
                    }
            }

            val frameChr = encodeFrame4bpp(pixels, spec.frameWidth, spec.frameHeight)
            System.arraycopy(frameChr, 0, chrOut, chrCursor, frameChr.size)
            chrCursor += frameChr.size

            val frameMask = SpriteMaskPacker.packFrameMask(pixels, spec.frameWidth, spec.frameHeight)
            val chrTileIndex = frameIdx * spec.tilesPerFrame
            val maskByteOffset = dataOffset + frameIdx * spec.maskFrameBytes

            compiledFrames.add(
                CompiledSpriteFrame(
                    index = frameIdx,
                    width = spec.frameWidth,
                    height = spec.frameHeight,
                    pixels = pixels,
                    chrBytes = frameChr,
                    maskBytes = frameMask,
                    chrTileIndex = chrTileIndex,
                    maskByteOffset = maskByteOffset,
                ),
            )
        }

        val mskBytes =
            SpriteMaskPacker.buildMskBinary(
                frameWidth = spec.frameWidth,
                frameHeight = spec.frameHeight,
                frameMasks = compiledFrames.map { it.maskBytes },
            )

        val includeText =
            generateInclude(
                name = name,
                spec = spec,
                frames = compiledFrames,
                chrByteSize = chrOut.size,
                mskByteSize = mskBytes.size,
            )

        return CompiledSpriteSheet(
            name = name,
            frameWidth = spec.frameWidth,
            frameHeight = spec.frameHeight,
            frameCount = frameCount,
            tilesX = spec.tilesX,
            tilesY = spec.tilesY,
            tilesPerFrame = spec.tilesPerFrame,
            maskRowBytes = spec.maskRowBytes,
            maskFrameBytes = spec.maskFrameBytes,
            palette = palette,
            frames = compiledFrames,
            chrBytes = chrOut,
            palBytes = palBytes,
            mskBytes = mskBytes,
            includeText = includeText,
        )
    }

    fun export(
        compiled: CompiledSpriteSheet,
        outputDir: File,
        baseName: String = compiled.name,
    ): ExportedSpriteAssets {
        outputDir.mkdirs()
        val chrFile = File(outputDir, "$baseName.chr")
        val palFile = File(outputDir, "$baseName.pal")
        val mskFile = File(outputDir, "$baseName.msk")
        val includeFile = File(outputDir, "$baseName.i")

        chrFile.writeBytes(compiled.chrBytes)
        palFile.writeBytes(compiled.palBytes)
        mskFile.writeBytes(compiled.mskBytes)
        includeFile.writeText(compiled.includeText)

        return ExportedSpriteAssets(
            chrFile = chrFile,
            palFile = palFile,
            mskFile = mskFile,
            includeFile = includeFile,
        )
    }

    /**
     * Encodes an indexed-color frame (`width x height`, multiples of 8) into row-major SNES planar
     * 4bpp 8x8 tiles (32 bytes per tile).
     */
    fun encodeFrame4bpp(
        pixels: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        require(width > 0 && width % 8 == 0) { "Width must be a positive multiple of 8, got $width" }
        require(height > 0 && height % 8 == 0) { "Height must be a positive multiple of 8, got $height" }
        val tilesX = width / 8
        val tilesY = height / 8
        val out = ByteArray(tilesX * tilesY * SNES_4BPP_TILE_BYTES)
        var cursor = 0
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val tile = encodeSnes4bppTile(pixels, stride = width, tileX = tx, tileY = ty)
                System.arraycopy(tile, 0, out, cursor, SNES_4BPP_TILE_BYTES)
                cursor += SNES_4BPP_TILE_BYTES
            }
        }
        return out
    }

    /**
     * Encodes a single 8x8 region at `(tileX * 8, tileY * 8)` into 32 bytes of SNES planar 4bpp
     * data: bytes `0..15` = interleaved bitplanes 0/1, bytes `16..31` = interleaved bitplanes 2/3.
     */
    fun encodeSnes4bppTile(
        pixels: ByteArray,
        stride: Int,
        tileX: Int,
        tileY: Int,
    ): ByteArray {
        val tile = ByteArray(SNES_4BPP_TILE_BYTES)
        val baseX = tileX * 8
        val baseY = tileY * 8
        for (py in 0 until 8) {
            var p0 = 0
            var p1 = 0
            var p2 = 0
            var p3 = 0
            val rowOffset = (baseY + py) * stride + baseX
            for (px in 0 until 8) {
                val colorIndex = pixels[rowOffset + px].toInt() and 0x0F
                val bit = 1 shl (7 - px)
                if ((colorIndex and 0x01) != 0) p0 = p0 or bit
                if ((colorIndex and 0x02) != 0) p1 = p1 or bit
                if ((colorIndex and 0x04) != 0) p2 = p2 or bit
                if ((colorIndex and 0x08) != 0) p3 = p3 or bit
            }
            tile[py * 2] = p0.toByte()
            tile[py * 2 + 1] = p1.toByte()
            tile[16 + py * 2] = p2.toByte()
            tile[16 + py * 2 + 1] = p3.toByte()
        }
        return tile
    }

    /**
     * Decodes a 32-byte SNES planar 4bpp 8x8 tile into 64 row-major 4-bit color indices (`0..15`).
     */
    fun decodeSnes4bppTile(
        tileBytes: ByteArray,
        offset: Int = 0,
    ): ByteArray {
        require(offset >= 0 && offset + SNES_4BPP_TILE_BYTES <= tileBytes.size) {
            "Insufficient bytes for 4bpp tile at offset $offset (size=${tileBytes.size})"
        }
        val pixels = ByteArray(64)
        for (py in 0 until 8) {
            val p0 = tileBytes[offset + py * 2].toInt() and 0xFF
            val p1 = tileBytes[offset + py * 2 + 1].toInt() and 0xFF
            val p2 = tileBytes[offset + 16 + py * 2].toInt() and 0xFF
            val p3 = tileBytes[offset + 16 + py * 2 + 1].toInt() and 0xFF
            for (px in 0 until 8) {
                val shift = 7 - px
                val b0 = (p0 ushr shift) and 1
                val b1 = (p1 ushr shift) and 1
                val b2 = (p2 ushr shift) and 1
                val b3 = (p3 ushr shift) and 1
                pixels[py * 8 + px] = (b0 or (b1 shl 1) or (b2 shl 2) or (b3 shl 3)).toByte()
            }
        }
        return pixels
    }

    private fun buildPalette(
        name: String,
        allOpaqueColors: List<SnesColor>,
        autoQuantize: Boolean,
    ): SnesPalette {
        val uniqueOpaque = allOpaqueColors.distinct()
        if (uniqueOpaque.isEmpty()) {
            var pal = SnesPalette()
            for (i in 1..15) {
                pal = pal.unassignSlot(i)
            }
            return pal
        }
        if (uniqueOpaque.size > 15 && !autoQuantize) {
            throw IllegalArgumentException(
                "Sprite sheet '$name' contains ${uniqueOpaque.size} unique opaque BGR555 colors " +
                    "(maximum 15 opaque + slot 0 transparent); enable auto-quantize to reduce.",
            )
        }
        return MedianCutQuantizer.quantize(allOpaqueColors)
    }

    private fun generateInclude(
        name: String,
        spec: SpriteSheetSpec,
        frames: List<CompiledSpriteFrame>,
        chrByteSize: Int,
        mskByteSize: Int,
    ): String {
        val prefix = SourceTexture.sanitizeToIdentifier(name).uppercase()
        val guard = "__${prefix}_SPRITE_I_DEFINED__"
        val dataOffset = SpriteMaskPacker.MSK_HEADER_SIZE + frames.size * 2
        return buildString {
            appendLine(";; Auto-generated by SuperFX Studio Sprite Sheet & Mask Packer")
            appendLine(";; Sprite Sheet: $name (${frames.size} frame(s), ${spec.frameWidth}x${spec.frameHeight} px)")
            appendLine(";;")
            appendLine(";; .chr layout: SNES planar 4bpp 8x8 tiles, row-major within each frame")
            appendLine(";;   (${spec.tilesX}x${spec.tilesY} = ${spec.tilesPerFrame} tiles/frame, 32 bytes/tile)")
            appendLine(";; .pal layout: 32 bytes (16 x BGR555 LE words; index 0 = transparent \$0000)")
            appendLine(";; .msk layout: 8-byte header + ${frames.size} x u16 LE frame offset table + 1bpp rows")
            appendLine(";;   Header: +0..+1 magic \"SM\" (\$4D53), +2 frame_count, +3 frame_width,")
            appendLine(";;           +4 frame_height, +5 row_bytes, +6..+7 frame_bytes (u16 LE)")
            appendLine(";;   Bit order: MSB-first (bit 7 = leftmost pixel of each 8-pixel byte)")
            appendLine(";;   Bit value: 1 = opaque pixel (punch color 0 in BG1), 0 = transparent (skip)")
            appendLine()
            appendLine(".ifndef ::$guard")
            appendLine("::$guard = 1")
            appendLine()
            appendLine("${prefix}_FRAME_COUNT = ${frames.size}")
            appendLine("${prefix}_FRAME_WIDTH = ${spec.frameWidth}")
            appendLine("${prefix}_FRAME_HEIGHT = ${spec.frameHeight}")
            appendLine("${prefix}_TILES_X = ${spec.tilesX}")
            appendLine("${prefix}_TILES_Y = ${spec.tilesY}")
            appendLine("${prefix}_TILES_PER_FRAME = ${spec.tilesPerFrame}")
            appendLine("${prefix}_TOTAL_TILES = ${frames.size * spec.tilesPerFrame}")
            appendLine("${prefix}_CHR_BYTE_SIZE = $chrByteSize")
            appendLine("${prefix}_PAL_BYTE_SIZE = 32")
            appendLine("${prefix}_MSK_MAGIC = \$4D53")
            appendLine("${prefix}_MSK_HEADER_SIZE = ${SpriteMaskPacker.MSK_HEADER_SIZE}")
            appendLine("${prefix}_MSK_TABLE_OFFSET = ${SpriteMaskPacker.MSK_HEADER_SIZE}")
            appendLine("${prefix}_MSK_DATA_OFFSET = $dataOffset")
            appendLine("${prefix}_MSK_ROW_BYTES = ${spec.maskRowBytes}")
            appendLine("${prefix}_MSK_FRAME_BYTES = ${spec.maskFrameBytes}")
            appendLine("${prefix}_MSK_BYTE_SIZE = $mskByteSize")
            appendLine()
            appendLine(";; Per-frame OAM tile indices & .msk byte offsets")
            for (frame in frames) {
                appendLine("${prefix}_FRAME_${frame.index}_TILE = ${frame.chrTileIndex}")
                appendLine("${prefix}_FRAME_${frame.index}_MSK_OFFSET = ${frame.maskByteOffset}")
            }
            appendLine()
            appendLine(".endif ; $guard")
        }
    }
}
