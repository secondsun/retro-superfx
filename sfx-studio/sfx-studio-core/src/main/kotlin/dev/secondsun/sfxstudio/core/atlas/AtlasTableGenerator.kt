package dev.secondsun.sfxstudio.core.atlas

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Internal binary generator and parser for relocatable runtime Texture Table (`.tab`) binaries
 * staged in SuperFX Game Pak RAM Bank `$71` (ADR 0007).
 *
 * Public callers access `.tab` serialization and parsing through [DescriptorTable.toTabBinary]
 * and [DescriptorTable.parseTabBinary].
 */
internal object AtlasTableGenerator {
    const val MAGIC: Int = 0x5453 // 'S' (0x53), 'T' (0x54) little-endian
    const val HEADER_BYTES: Int = 8
    const val ENTRY_BYTES: Int = 8
    const val MAX_ENTRIES: Int = 256

    fun generate(atlas: TextureAtlas): ByteArray = generate(DescriptorTable.buildFromAtlasOrThrow(atlas))

    fun generate(table: DescriptorTable): ByteArray {
        val descriptors = table.entries
        require(descriptors.size <= MAX_ENTRIES) {
            "Texture Table supports at most $MAX_ENTRIES descriptors, got ${descriptors.size}"
        }

        val strideShift = table.strideShift
        val fracMask = (1 shl strideShift) - 1
        val totalBytes = HEADER_BYTES + descriptors.size * ENTRY_BYTES
        val buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)

        buf.putShort(MAGIC.toShort())
        buf.putShort(descriptors.size.toShort())
        buf.putShort(table.atlasWidth.toShort())
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

    fun parse(bytes: ByteArray): DescriptorTable.ParsedTab {
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
                DescriptorTable.TabEntry(
                    atlasOffset = buf.short.toInt() and 0xFFFF,
                    uMask = buf.short.toInt() and 0xFFFF,
                    vMaskShifted = buf.short.toInt() and 0xFFFF,
                    vOffsetMask = buf.short.toInt() and 0xFFFF,
                )
            }

        return DescriptorTable.ParsedTab(
            magic = magic,
            entryCount = entryCount,
            atlasWidth = atlasWidth,
            strideShift = strideShift,
            entries = entries,
        )
    }
}
