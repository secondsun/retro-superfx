package dev.secondsun.sfxstudio.core.atlas

/**
 * Tri-state outcome of resolving a symbolic (or optional numeric) texture reference against a [DescriptorTable].
 */
sealed interface DescriptorLookup {
    /**
     * Explicit absence of a texture (`null`, blank, `"NONE"`, `"TEX_NONE"`, or `"0"` when numeric indexing is disabled
     * or zero-as-absent is requested).
     */
    data object Absent : DescriptorLookup

    /**
     * Resolved descriptor at 0-based `TEXTURE_TABLE` [index].
     */
    data class Found(
        val index: Int,
        val descriptor: TextureDescriptorInfo,
    ) : DescriptorLookup

    /**
     * Unrecognized texture symbol or out-of-range numeric index.
     */
    data class Unknown(
        val rawSymbol: String,
    ) : DescriptorLookup
}

/**
 * Canonical resolved Texture Descriptor table for a [TextureAtlas].
 *
 * Owns symbolic and numeric descriptor lookup (stripping both `TEXTURE_ID_` and `TEX_` prefixes
 * case-insensitively), direct sub-rectangle texel extraction without regenerating the full atlas
 * binary, and relocatable Bank `$71` `.tab` serialization/parsing.
 */
class DescriptorTable internal constructor(
    val atlasWidth: Int,
    val atlasHeight: Int,
    val strideShift: Int,
    val entries: List<TextureDescriptorInfo>,
    private val backingRegions: Map<String, BackingRegion>,
) {
    internal data class BackingRegion(
        val source: SourceTexture,
        val offsetX: Int,
        val offsetY: Int,
        val width: Int,
        val height: Int,
    )

    data class TabEntry(
        val atlasOffset: Int,
        val uMask: Int,
        val vMaskShifted: Int,
        val vOffsetMask: Int,
    )

    data class ParsedTab(
        val magic: Int,
        val entryCount: Int,
        val atlasWidth: Int,
        val strideShift: Int,
        val entries: List<TabEntry>,
    )

    val size: Int
        get() = entries.size

    /**
     * Resolves [symbol] to [DescriptorLookup.Absent], [DescriptorLookup.Found], or [DescriptorLookup.Unknown].
     *
     * @param symbol Raw symbolic name (`"stone"`, `"TEX_STONE"`, `"TEXTURE_ID_STONE"`, `"NONE"`, `"0"`, etc.).
     * @param allowNumericIndex When `true` (used by legacy ca65 `def_cell` import), decimal or `$hex`/`0xHex`
     *   literals index directly into [entries]. When `false`, `"0"` resolves to [DescriptorLookup.Absent] and
     *   other numeric strings resolve as [DescriptorLookup.Unknown] unless matching a valid identifier.
     * @param zeroIsAbsent When `true` alongside [allowNumericIndex], numeric literal `0` (`"0"`, `"$00"`, `"0x0"`)
     *   resolves to [DescriptorLookup.Absent] instead of `entries[0]` (matching ca65 `panelTex = 0` semantics).
     */
    fun lookup(
        symbol: String?,
        allowNumericIndex: Boolean = false,
        zeroIsAbsent: Boolean = false,
    ): DescriptorLookup {
        if (symbol == null) return DescriptorLookup.Absent
        val clean = symbol.trim()
        if (clean.isEmpty() ||
            clean.equals("NONE", ignoreCase = true) ||
            clean.equals("TEX_NONE", ignoreCase = true) ||
            clean.equals("TEXTURE_ID_NONE", ignoreCase = true)
        ) {
            return DescriptorLookup.Absent
        }

        if (!allowNumericIndex && clean == "0") {
            return DescriptorLookup.Absent
        }

        if (allowNumericIndex) {
            val numericId = parseOptionalIntLiteral(clean)
            if (numericId != null) {
                if (numericId == 0 && zeroIsAbsent) {
                    return DescriptorLookup.Absent
                }
                val entry = entries.getOrNull(numericId)
                return if (entry != null) {
                    DescriptorLookup.Found(numericId, entry)
                } else {
                    DescriptorLookup.Unknown(clean)
                }
            }
        }

        // 1. Direct case-insensitive match (preserves literal names starting with `tex_`)
        val directIdx = entries.indexOfFirst { it.name.equals(clean, ignoreCase = true) }
        if (directIdx >= 0) {
            return DescriptorLookup.Found(directIdx, entries[directIdx])
        }

        // 2. Strip canonical ca65 prefixes (`TEXTURE_ID_` or `TEX_`) and match case-insensitively
        val stripped = stripTexturePrefix(clean)
        if (stripped.isEmpty()) {
            return DescriptorLookup.Unknown(clean)
        }
        val strippedIdx = entries.indexOfFirst { it.name.equals(stripped, ignoreCase = true) }
        if (strippedIdx >= 0) {
            return DescriptorLookup.Found(strippedIdx, entries[strippedIdx])
        }

        return DescriptorLookup.Unknown(clean)
    }

    /**
     * Extracts the `width * height` 4-bit indexed texels for [descriptor] directly from its backing
     * [SourceTexture] without allocating or scanning the full [TextureAtlas] binary.
     */
    fun extractPixels(descriptor: TextureDescriptorInfo): ByteArray {
        val region = backingRegions[descriptor.name.uppercase()]
        if (region != null) {
            if (region.offsetX == 0 &&
                region.offsetY == 0 &&
                region.width == region.source.width &&
                region.height == region.source.height
            ) {
                return region.source.indexedPixels.copyOf()
            }
            val out = ByteArray(region.width * region.height)
            for (row in 0 until region.height) {
                val srcPos = (region.offsetY + row) * region.source.width + region.offsetX
                val dstPos = row * region.width
                System.arraycopy(region.source.indexedPixels, srcPos, out, dstPos, region.width)
            }
            return out
        }
        return ByteArray(descriptor.width * descriptor.height)
    }

    /**
     * Builds a standalone [SourceTexture] containing [descriptor]'s extracted pixels for UI swatch rendering.
     */
    fun extractPreviewTexture(descriptor: TextureDescriptorInfo): SourceTexture =
        SourceTexture(
            name = descriptor.name,
            width = descriptor.width,
            height = descriptor.height,
            x = 0,
            y = 0,
            indexedPixels = extractPixels(descriptor),
        )

    /**
     * Generates the relocatable runtime `.tab` binary for this descriptor table.
     */
    fun toTabBinary(): ByteArray = AtlasTableGenerator.generate(this)

    companion object {
        const val MAX_ENTRIES: Int = AtlasTableGenerator.MAX_ENTRIES
        const val TAB_MAGIC: Int = AtlasTableGenerator.MAGIC
        const val TAB_HEADER_BYTES: Int = AtlasTableGenerator.HEADER_BYTES
        const val TAB_ENTRY_BYTES: Int = AtlasTableGenerator.ENTRY_BYTES

        /**
         * Strips a single leading `TEXTURE_ID_` or `TEX_` prefix (case-insensitively) from [raw].
         */
        fun stripTexturePrefix(raw: String): String {
            val trimmed = raw.trim()
            return when {
                trimmed.startsWith("TEXTURE_ID_", ignoreCase = true) -> trimmed.substring("TEXTURE_ID_".length)
                trimmed.startsWith("TEX_", ignoreCase = true) -> trimmed.substring("TEX_".length)
                else -> trimmed
            }
        }

        /**
         * Parses a relocatable `.tab` binary into its header metadata and 8-byte descriptor entries.
         */
        fun parseTabBinary(bytes: ByteArray): ParsedTab = AtlasTableGenerator.parse(bytes)

        /**
         * Builds and validates a [DescriptorTable] from [atlas], returning `Result.failure` if any
         * identifier collision, out-of-bounds slice, unknown/cyclic alias, `descriptorOrder` mismatch,
         * or `> 256` descriptor overflow is present.
         */
        fun fromAtlas(atlas: TextureAtlas): Result<DescriptorTable> =
            runCatching {
                buildFromAtlasOrThrow(atlas)
            }

        internal fun buildFromAtlasOrThrow(atlas: TextureAtlas): DescriptorTable {
            val seenUpper = LinkedHashMap<String, String>() // UPPER -> originalName
            val descriptorByUpper = LinkedHashMap<String, TextureDescriptorInfo>()
            val backingByUpper = LinkedHashMap<String, BackingRegion>()
            val textureByUpper = LinkedHashMap<String, SourceTexture>()

            fun registerUniqueName(
                name: String,
                kind: String,
            ): String {
                val upper = name.uppercase()
                val existing = seenUpper[upper]
                require(existing == null) {
                    "Duplicate ca65 descriptor identifier '$name' ($kind) collides with '$existing'"
                }
                seenUpper[upper] = name
                return upper
            }

            // 1. Base textures
            for (tex in atlas.textures) {
                val upper = registerUniqueName(tex.name, "texture")
                textureByUpper[upper] = tex
                val romOffset = tex.y * atlas.width + tex.x
                descriptorByUpper[upper] =
                    TextureDescriptorInfo(
                        name = tex.name,
                        x = tex.x,
                        y = tex.y,
                        width = tex.width,
                        height = tex.height,
                        romOffset = romOffset,
                        uMask = tex.uMask,
                        vMask = tex.vMask,
                        widthShift = tex.widthShift,
                        comment = "${tex.name} (${tex.width}x${tex.height})",
                    )
                backingByUpper[upper] =
                    BackingRegion(
                        source = tex,
                        offsetX = 0,
                        offsetY = 0,
                        width = tex.width,
                        height = tex.height,
                    )
            }

            // 2. Virtual slices
            for (slice in atlas.slices) {
                val upper = registerUniqueName(slice.name, "slice")
                val parent =
                    textureByUpper[slice.sourceTexture.uppercase()]
                        ?: error("Virtual slice '${slice.name}' references non-existent source texture '${slice.sourceTexture}'")
                require(
                    slice.x >= 0 &&
                        slice.y >= 0 &&
                        slice.x + slice.width <= parent.width &&
                        slice.y + slice.height <= parent.height,
                ) {
                    "Virtual slice '${slice.name}' is out of bounds of parent texture '${parent.name}'"
                }
                val sliceX = parent.x + slice.x
                val sliceY = parent.y + slice.y
                val romOffset = sliceY * atlas.width + sliceX
                descriptorByUpper[upper] =
                    TextureDescriptorInfo(
                        name = slice.name,
                        x = sliceX,
                        y = sliceY,
                        width = slice.width,
                        height = slice.height,
                        romOffset = romOffset,
                        uMask = slice.uMask,
                        vMask = slice.vMask,
                        widthShift = slice.widthShift,
                        comment = "${slice.name} (${slice.width}x${slice.height})",
                    )
                backingByUpper[upper] =
                    BackingRegion(
                        source = parent,
                        offsetX = slice.x,
                        offsetY = slice.y,
                        width = slice.width,
                        height = slice.height,
                    )
            }

            // 3. Aliases (supports chained non-cyclic aliases in any declaration order; rejects cycles)
            val aliasByUpper = LinkedHashMap<String, DescriptorAlias>()
            for (alias in atlas.aliases) {
                val upper = registerUniqueName(alias.name, "alias")
                aliasByUpper[upper] = alias
            }

            fun resolveAlias(
                alias: DescriptorAlias,
                chain: LinkedHashSet<String>,
            ): Pair<TextureDescriptorInfo, BackingRegion> {
                val upper = alias.name.uppercase()
                descriptorByUpper[upper]?.let { existing ->
                    return existing to backingByUpper.getValue(upper)
                }
                if (!chain.add(upper)) {
                    error("Cyclic descriptor alias detected: ${(chain + alias.name).joinToString(" -> ")}")
                }
                val targetUpper = alias.target.uppercase()
                if (targetUpper in chain) {
                    error("Cyclic descriptor alias detected: ${(chain + alias.target).joinToString(" -> ")}")
                }
                val (targetDesc, targetBacking) =
                    when {
                        descriptorByUpper.containsKey(targetUpper) ->
                            descriptorByUpper.getValue(targetUpper) to backingByUpper.getValue(targetUpper)
                        aliasByUpper.containsKey(targetUpper) ->
                            resolveAlias(aliasByUpper.getValue(targetUpper), chain)
                        else ->
                            error("Descriptor alias '${alias.name}' references unknown target '${alias.target}'")
                    }
                val resolved =
                    targetDesc.copy(
                        name = alias.name,
                        comment = "${alias.name} (${targetDesc.width}x${targetDesc.height})",
                    )
                descriptorByUpper[upper] = resolved
                backingByUpper[upper] = targetBacking
                return resolved to targetBacking
            }

            for (alias in atlas.aliases) {
                resolveAlias(alias, LinkedHashSet())
            }

            // 4. Determine final ordering & validate descriptorOrder completeness/uniqueness
            val orderedEntries: List<TextureDescriptorInfo> =
                if (atlas.descriptorOrder != null) {
                    val orderSeen = LinkedHashSet<String>()
                    val list =
                        atlas.descriptorOrder.map { rawName ->
                            val upper = rawName.uppercase()
                            val desc =
                                descriptorByUpper[upper]
                                    ?: error("Descriptor '$rawName' in descriptorOrder is not defined in textures, slices, or aliases")
                            require(orderSeen.add(upper)) {
                                "Duplicate descriptor '$rawName' in descriptorOrder"
                            }
                            desc
                        }
                    val omitted = seenUpper.keys.filter { it !in orderSeen }.map { seenUpper.getValue(it) }
                    require(omitted.isEmpty()) {
                        "descriptorOrder omits defined descriptor(s): ${omitted.joinToString(", ")}"
                    }
                    list
                } else {
                    atlas.textures.map { descriptorByUpper.getValue(it.name.uppercase()) } +
                        atlas.aliases.map { descriptorByUpper.getValue(it.name.uppercase()) } +
                        atlas.slices.map { descriptorByUpper.getValue(it.name.uppercase()) }
                }

            require(orderedEntries.size <= MAX_ENTRIES) {
                "Texture Table supports at most $MAX_ENTRIES descriptors, got ${orderedEntries.size}"
            }

            return DescriptorTable(
                atlasWidth = atlas.width,
                atlasHeight = atlas.height,
                strideShift = atlas.strideShift,
                entries = orderedEntries,
                backingRegions = backingByUpper,
            )
        }

        private fun parseOptionalIntLiteral(token: String): Int? =
            when {
                token.startsWith("$") -> token.substring(1).toIntOrNull(16)
                token.startsWith("0x", ignoreCase = true) -> token.substring(2).toIntOrNull(16)
                else -> token.toIntOrNull()
            }
    }
}
