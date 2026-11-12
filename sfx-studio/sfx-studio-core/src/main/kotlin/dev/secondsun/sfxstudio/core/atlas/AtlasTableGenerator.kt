package dev.secondsun.sfxstudio.core.atlas

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Generates and parses relocatable runtime Texture Table (`.tab`) binaries for staging in
 * SuperFX Game Pak RAM Bank `$71` (ADR 0007).
 *
 * Binary layout (all fields unsigned 16-bit little-endian words):
 * - Header (8 bytes):
 *   - `+0`: `magic` (`0x5453`, ASCII `"ST"`)
 *   - `+2`: `entryCount` (`0..256`)
 *   - `+4`: `atlasWidth` (`16, 32, 64, 128, 256`)
 *   - `+6`: `strideShift` (`log2(atlasWidth)`)
 * - Descriptor entries (`entryCount * 8` bytes):
 *   - `+0`: `atlasOffset` (`0`-based byte offset in `.bin`, equal to Bank `$71:0000+` pointer)
 *   - `+2`: `uMask` (`width - 1`)
 *   - `+4`: `vMaskShifted` (`(height - 1) << strideShift`)
 *   - `+6`: `vOffsetMask` (`((height - 1) << strideShift) | ((1 << strideShift) - 1)`)
 */
object AtlasTableGenerator {
    const val MAGIC: Int = 0x5453 // 'S' (0x53), 'T' (0x54) little-endian
    const val HEADER_BYTES: Int = 8
    const val ENTRY_BYTES: Int = 8
    const val MAX_ENTRIES: Int = 256

    data class TableEntry(
        val atlasOffset: Int,
        val uMask: Int,
        val vMaskShifted: Int,
        val vOffsetMask: Int,
    )

    data class ParsedTable(
        val magic: Int,
        val entryCount: Int,
        val atlasWidth: Int,
        val strideShift: Int,
        val entries: List<TableEntry>,
    )

    fun generate(atlas: TextureAtlas): ByteArray {
        val descriptors = atlas.resolvedDescriptors()
        require(descriptors.size <= MAX_ENTRIES) {
            "Texture Table supports at most $MAX_ENTRIES descriptors, got ${descriptors.size}"
        }

        val strideShift = atlas.strideShift
        val fracMask = (1 shl strideShift) - 1
        val totalBytes = HEADER_BYTES + descriptors.size * ENTRY_BYTES
        val buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)

        buf.putShort(MAGIC.toShort())
        buf.putShort(descriptors.size.toShort())
        buf.putShort(atlas.width.toShort())
        buf.putShort(strideShift.toShort())

        for (desc in descriptors) {
            val vMaskShifted = desc.vMask shl strideShift
            val vOffsetMask = vMaskShifted or fracMask
            buf.putShort((desc.romOffset and 0xFFFF).toShort())
            buf.putShort((desc.uMask and 0xFFFF).toShort())
            buf.putShort((vMaskShifted and 0xFFFF).toShort())
            buf.putShort((vOffsetMask and 0xFFFF).toShort())
        }

        return buf.array()
    }

    fun parse(bytes: ByteArray): ParsedTable {
        require(bytes.size >= HEADER_BYTES) {
            "Texture Table (.tab) must be at least $HEADER_BYTES bytes, got ${bytes.size}"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.short.toInt() and 0xFFFF
        require(magic == MAGIC) {
            String.format("Invalid Texture Table magic: expected 0x%04X, got 0x%04X", MAGIC, magic)
        }
        val entryCount = buf.short.toInt() and 0xFFFF
        val atlasWidth = buf.short.toInt() and 0xFFFF
        val strideShift = buf.short.toInt() and 0xFFFF
        val expectedBytes = HEADER_BYTES + entryCount * ENTRY_BYTES
        require(bytes.size == expectedBytes) {
            "Texture Table size mismatch for $entryCount entries: expected $expectedBytes bytes, got ${bytes.size}"
        }

        val entries =
            List(entryCount) {
                TableEntry(
                    atlasOffset = buf.short.toInt() and 0xFFFF,
                    uMask = buf.short.toInt() and 0xFFFF,
                    vMaskShifted = buf.short.toInt() and 0xFFFF,
                    vOffsetMask = buf.short.toInt() and 0xFFFF,
                )
            }

        return ParsedTable(
            magic = magic,
            entryCount = entryCount,
            atlasWidth = atlasWidth,
            strideShift = strideShift,
            entries = entries,
        )
    }
}
