package dev.secondsun.sfxstudio.core.atlas

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.secondsun.sfxstudio.core.level.DiagnosticSeverity
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.io.Reader
import javax.imageio.ImageIO

const val SFX_PROJ_EXTENSION = "sfxproj"
const val SFX_ATLAS_EXTENSION = "sfxatlas"

internal data class ProjectColorDto(
    var index: Int = 0,
    var r: Int = 0,
    var g: Int = 0,
    var b: Int = 0,
    var assigned: Boolean = false,
)

internal data class ProjectSliceDto(
    var name: String = "",
    var sourceTexture: String = "",
    var x: Int = 0,
    var y: Int = 0,
    var width: Int = 0,
    var height: Int = 0,
)

internal data class ProjectAliasDto(
    var name: String = "",
    var target: String = "",
)

internal data class ProjectTextureDto(
    var name: String = "",
    var width: Int = 0,
    var height: Int = 0,
    var x: Int = 0,
    var y: Int = 0,
    var pixelsHex: String = "",
    var pixelsAscii: String? = null,
)

internal data class ProjectDto(
    var name: String? = null,
    var atlasWidth: Int = 0,
    var atlasHeight: Int = 0,
    var gridSnap: Int = 0,
    var palette: List<ProjectColorDto>? = null,
    var textures: List<ProjectTextureDto>? = null,
    var slices: List<ProjectSliceDto>? = null,
    var aliases: List<ProjectAliasDto>? = null,
    var descriptorOrder: List<String>? = null,
)

/**
 * Policy governing how [AtlasDocument] places newly imported [SourceTexture]s when the current
 * canvas has no non-overlapping region left.
 */
enum class PlacementPolicy {
    /** Rejects placement if the texture does not fit inside the current `width x height` bounds. */
    STRICT_BOUNDS,

    /** Grows the atlas canvas in power-of-two steps up to `256x256` before failing. */
    AUTO_GROW_MAX_256,
}

/**
 * Headless conflict-resolution strategy when an imported bitmap contains more opaque colors than
 * unassigned slots in the 15-color [SnesPalette].
 */
enum class ConflictResolution {
    FAIL,
    SNAP_TO_PALETTE,
    GLOBAL_QUANTIZE,
}

data class AtlasDiagnostic(
    val severity: DiagnosticSeverity,
    val code: String,
    val message: String,
)

data class AtlasValidationResult(
    val valid: Boolean,
    val errors: List<AtlasDiagnostic>,
    val warnings: List<AtlasDiagnostic>,
) {
    val allDiagnostics: List<AtlasDiagnostic>
        get() = errors + warnings
}

data class AtlasExportSpec(
    val baseName: String,
    val graphicsBinFileName: String = "$baseName.bin",
    val paletteBinFileName: String = "$baseName.pal",
    val indexFileName: String = "$baseName.i",
    val pngFileName: String = "${baseName}_atlas.png",
    val tableBinFileName: String = "$baseName.tab",
    val includeGraphicsBin: Boolean = true,
    val includePaletteBin: Boolean = true,
    val includeIndexFile: Boolean = true,
    val includePng: Boolean = true,
    val includeTableBin: Boolean = true,
)

data class ExportedAssets(
    val graphicsBinFile: File,
    val paletteBinFile: File,
    val indexFile: File,
    val pngFile: File,
    val tableBinFile: File? = null,
)

/**
 * Outcome of initiating a single-image import into an [AtlasDocument].
 */
sealed interface AtlasImportOutcome {
    data class Imported(
        val document: AtlasDocument,
        val placedTexture: SourceTexture,
    ) : AtlasImportOutcome

    data class NeedsRemapping(
        val pending: PendingTextureImport,
    ) : AtlasImportOutcome

    data class Invalid(
        val message: String,
    ) : AtlasImportOutcome
}

/**
 * Capability handle encapsulating a bitmap awaiting interactive or policy-driven palette remapping.
 *
 * Keeps raw image buffers internal to `sfx-studio-core`, exposes live [SourceTexture] preview
 * generation, and commits atomically via [AtlasDocument.placeNewTexture] without ever falling
 * back to `(0, 0)` overlap placement.
 */
class PendingTextureImport internal constructor(
    private val rawImage: BufferedImage,
    val suggestedName: String,
    val uniqueColors: List<SnesColor>,
    val unmappedColors: List<SnesColor>,
    val initialMapping: Map<SnesColor, Int>,
) {
    val width: Int
        get() = rawImage.width

    val height: Int
        get() = rawImage.height

    fun defaultMappingFor(palette: SnesPalette): Map<SnesColor, Int> =
        uniqueColors.associateWith { color ->
            palette.findExactIndex(color) ?: palette.findNearestAssignedIndex(color)
        }

    fun previewTexture(
        mapping: Map<SnesColor, Int>,
        palette: SnesPalette,
    ): SourceTexture =
        TextureImporter.createTextureFromImage(
            image = rawImage,
            name = suggestedName,
            colorMapping = mapping,
            fallbackPalette = palette,
        )

    fun commitManual(
        document: AtlasDocument,
        mapping: Map<SnesColor, Int>,
        placementPolicy: PlacementPolicy = PlacementPolicy.AUTO_GROW_MAX_256,
    ): Result<AtlasDocument> {
        var updatedPalette = document.palette
        for ((color, slot) in mapping) {
            if (slot in 1..15 && !updatedPalette.isAssigned(slot)) {
                updatedPalette = updatedPalette.withSlot(slot, color, assigned = true)
            }
        }
        val tex = previewTexture(mapping, updatedPalette)
        return document.copy(palette = updatedPalette).placeNewTexture(tex, placementPolicy)
    }

    fun commitSnap(
        document: AtlasDocument,
        placementPolicy: PlacementPolicy = PlacementPolicy.AUTO_GROW_MAX_256,
    ): Result<AtlasDocument> {
        val snapMap = defaultMappingFor(document.palette)
        val tex = previewTexture(snapMap, document.palette)
        return document.placeNewTexture(tex, placementPolicy)
    }

    fun commitGlobalQuantize(
        document: AtlasDocument,
        placementPolicy: PlacementPolicy = PlacementPolicy.AUTO_GROW_MAX_256,
    ): Result<AtlasDocument> {
        val allOpaqueColors = ArrayList<SnesColor>()
        for (tex in document.atlas.textures) {
            for (b in tex.indexedPixels) {
                val slot = b.toInt() and 0x0F
                if (slot != 0) {
                    allOpaqueColors.add(document.palette[slot])
                }
            }
        }
        allOpaqueColors.addAll(TextureImporter.extractOpaquePixelColors(rawImage))

        val newPalette = MedianCutQuantizer.quantize(allOpaqueColors)
        val slotRemap =
            (1..15).associateWith { oldSlot ->
                val oldColor = document.palette[oldSlot]
                newPalette.findExactIndex(oldColor) ?: newPalette.findNearestAssignedIndex(oldColor)
            }
        val remappedExisting = document.atlas.textures.map { it.remapPixels(slotRemap) }

        val incomingMapping =
            uniqueColors.associateWith { color ->
                newPalette.findExactIndex(color) ?: newPalette.findNearestAssignedIndex(color)
            }
        val newTex =
            TextureImporter.createTextureFromImage(
                image = rawImage,
                name = suggestedName,
                colorMapping = incomingMapping,
                fallbackPalette = newPalette,
            )

        val quantizedDoc =
            document.copy(
                atlas = document.atlas.copy(textures = remappedExisting),
                palette = newPalette,
            )
        return quantizedDoc.placeNewTexture(newTex, placementPolicy)
    }
}

/**
 * Immutable value module representing an authored SuperFX Texture Atlas Document (`.sfxproj` / `.sfxatlas`).
 *
 * Consolidates persistence, invariant validation, cascading rename / safe delete, standalone 15-color
 * Median-Cut palette quantization, bitmap import (`PendingTextureImport`), safe index preview rendering,
 * and atomic companion binary export.
 */
data class AtlasDocument(
    val name: String = "TEXTURES",
    val atlas: TextureAtlas = TextureAtlas(128, 128),
    val palette: SnesPalette = SnesPalette(),
    val gridSnap: Int = 16,
) {
    /**
     * Validates all structural, identifier, coordinate, slice, alias, `descriptorOrder`, and table-size
     * invariants on this document.
     *
     * @param warnBank71Overflow When `true`, emits an `ATLAS_EXCEEDS_BANK_71` warning if the atlas canvas
     *   exceeds 32,768 bytes (`256x256` = 64 KB, exceeding Game Pak RAM Bank `$71`'s 32 KB atlas budget).
     */
    fun validate(warnBank71Overflow: Boolean = false): AtlasValidationResult {
        val errors = mutableListOf<AtlasDiagnostic>()
        val warnings = mutableListOf<AtlasDiagnostic>()

        // 1. Identifier validity & case-insensitive uniqueness across textures, slices, and aliases
        val seenByUpper = LinkedHashMap<String, Pair<String, String>>() // UPPER -> (kind, originalName)

        fun checkIdentifier(
            id: String,
            kind: String,
        ) {
            if (!SourceTexture.isValidCa65Identifier(id)) {
                errors.add(
                    AtlasDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "INVALID_IDENTIFIER",
                        message = "Invalid ca65 identifier '$id' on $kind.",
                    ),
                )
                return
            }
            val upper = id.uppercase()
            val prev = seenByUpper[upper]
            if (prev != null) {
                errors.add(
                    AtlasDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "DUPLICATE_IDENTIFIER",
                        message =
                            "Duplicate identifier '$id' ($kind) collides with ${prev.first} '${prev.second}' " +
                                "(ca65 constants are case-insensitive).",
                    ),
                )
            } else {
                seenByUpper[upper] = kind to id
            }
        }

        val textureByUpper = LinkedHashMap<String, SourceTexture>()
        for (tex in atlas.textures) {
            checkIdentifier(tex.name, "texture")
            textureByUpper.putIfAbsent(tex.name.uppercase(), tex)
        }
        for (slice in atlas.slices) {
            checkIdentifier(slice.name, "slice")
        }
        for (alias in atlas.aliases) {
            checkIdentifier(alias.name, "alias")
        }

        // 2. Texture bounds
        for (tex in atlas.textures) {
            if (!tex.isWithinBounds(atlas.width, atlas.height)) {
                errors.add(
                    AtlasDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "TEXTURE_OUT_OF_BOUNDS",
                        message =
                            "Texture '${tex.name}' exceeds atlas bounds: " +
                                "(${tex.x}, ${tex.y}) + (${tex.width}x${tex.height}) > (${atlas.width}x${atlas.height})",
                    ),
                )
            }
        }

        // 3. Pairwise texture coordinate collisions
        for (i in atlas.textures.indices) {
            for (j in i + 1 until atlas.textures.size) {
                val t1 = atlas.textures[i]
                val t2 = atlas.textures[j]
                if (t1.intersects(t2)) {
                    errors.add(
                        AtlasDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "TEXTURE_COLLISION",
                            message = "Collision detected between '${t1.name}' and '${t2.name}'",
                        ),
                    )
                }
            }
        }

        // 4. Virtual slice parent existence & sub-rectangle bounds
        val baseDefinedUpper = LinkedHashSet<String>(textureByUpper.keys)
        for (slice in atlas.slices) {
            val parent = textureByUpper[slice.sourceTexture.uppercase()]
            if (parent == null) {
                errors.add(
                    AtlasDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "UNKNOWN_SLICE_SOURCE",
                        message = "Virtual slice '${slice.name}' references non-existent source texture '${slice.sourceTexture}'",
                    ),
                )
            } else {
                baseDefinedUpper.add(slice.name.uppercase())
                if (slice.x < 0 ||
                    slice.y < 0 ||
                    slice.x + slice.width > parent.width ||
                    slice.y + slice.height > parent.height
                ) {
                    errors.add(
                        AtlasDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "SLICE_OUT_OF_BOUNDS",
                            message =
                                "Virtual slice '${slice.name}' (${slice.width}x${slice.height} at ${slice.x},${slice.y}) " +
                                    "is out of bounds of parent texture '${parent.name}' (${parent.width}x${parent.height})",
                        ),
                    )
                }
            }
        }

        // 5. Alias target existence & cycle detection
        val aliasByUpper = atlas.aliases.associateBy { it.name.uppercase() }
        for (alias in atlas.aliases) {
            val visited = LinkedHashSet<String>()
            visited.add(alias.name.uppercase())
            var currentTarget = alias.target
            while (true) {
                val targetUpper = currentTarget.uppercase()
                if (targetUpper in visited) {
                    errors.add(
                        AtlasDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "CYCLIC_ALIAS",
                            message =
                                "Cyclic descriptor alias '${alias.name}': " +
                                    "${(visited + currentTarget).joinToString(" -> ")}",
                        ),
                    )
                    break
                }
                if (targetUpper in baseDefinedUpper) {
                    break
                }
                val nextAlias = aliasByUpper[targetUpper]
                if (nextAlias != null) {
                    visited.add(targetUpper)
                    currentTarget = nextAlias.target
                } else {
                    errors.add(
                        AtlasDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "UNKNOWN_ALIAS_TARGET",
                            message = "Descriptor alias '${alias.name}' references unknown target '$currentTarget'",
                        ),
                    )
                    break
                }
            }
        }

        // 6. descriptorOrder completeness & uniqueness
        if (atlas.descriptorOrder != null) {
            val orderSeen = LinkedHashSet<String>()
            for (entry in atlas.descriptorOrder) {
                val upper = entry.uppercase()
                if (upper !in seenByUpper) {
                    errors.add(
                        AtlasDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "UNKNOWN_DESCRIPTOR_ORDER_ENTRY",
                            message = "Descriptor '$entry' in descriptorOrder is not defined in textures, slices, or aliases",
                        ),
                    )
                } else if (!orderSeen.add(upper)) {
                    errors.add(
                        AtlasDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "DUPLICATE_DESCRIPTOR_ORDER",
                            message = "Duplicate descriptor '$entry' in descriptorOrder",
                        ),
                    )
                }
            }
            val omitted = seenByUpper.keys.filter { it !in orderSeen }.map { seenByUpper.getValue(it).second }
            if (omitted.isNotEmpty()) {
                errors.add(
                    AtlasDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "INCOMPLETE_DESCRIPTOR_ORDER",
                        message = "descriptorOrder omits defined descriptor(s): ${omitted.joinToString(", ")}",
                    ),
                )
            }
        }

        // 7. Total descriptor count <= 256 (.tab / u8 .map limit)
        val totalDescriptors = atlas.textures.size + atlas.slices.size + atlas.aliases.size
        if (totalDescriptors > DescriptorTable.MAX_ENTRIES) {
            errors.add(
                AtlasDiagnostic(
                    severity = DiagnosticSeverity.ERROR,
                    code = "TOO_MANY_DESCRIPTORS",
                    message =
                        "Texture Table supports at most ${DescriptorTable.MAX_ENTRIES} descriptors, " +
                            "got $totalDescriptors",
                ),
            )
        }

        // 8. Optional Bank $71 >32 KB warning
        if (warnBank71Overflow && atlas.sizeBytes > BANK_71_MAX_ATLAS_BYTES) {
            warnings.add(
                AtlasDiagnostic(
                    severity = DiagnosticSeverity.WARNING,
                    code = "ATLAS_EXCEEDS_BANK_71",
                    message =
                        "Atlas is ${atlas.width}x${atlas.height} (${atlas.sizeBytes} bytes), exceeding the " +
                            "$BANK_71_MAX_ATLAS_BYTES-byte Bank \$71 runtime staging budget for IsoEngine levels.",
                ),
            )
        }

        return AtlasValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
        )
    }

    /**
     * Resolves the canonical [DescriptorTable] safely without throwing on invalid intermediate state.
     */
    fun descriptorTable(): Result<DescriptorTable> = DescriptorTable.fromAtlas(atlas)

    /**
     * Generates the ca65 assembly `.i` include preview safely without throwing on UI render threads.
     */
    fun renderIndexPreview(baseName: String = name): Result<String> =
        descriptorTable().map { table ->
            AtlasIndexGenerator.generate(atlas, table.entries, baseName)
        }

    /**
     * Standalone 15-color Median-Cut quantization across all existing [SourceTexture]s in this document.
     * Remaps every texture's indexed pixels onto the newly quantized [SnesPalette] without inserting
     * any sentinel texture.
     */
    fun quantizePalette(): AtlasDocument {
        val opaqueSamples = ArrayList<SnesColor>()
        for (tex in atlas.textures) {
            for (b in tex.indexedPixels) {
                val slot = b.toInt() and 0x0F
                if (slot != 0) {
                    opaqueSamples.add(palette[slot])
                }
            }
        }
        if (opaqueSamples.isEmpty()) {
            for (slot in 1..15) {
                if (palette.isAssigned(slot)) {
                    opaqueSamples.add(palette[slot])
                }
            }
        }
        if (opaqueSamples.isEmpty()) return this

        val newPalette = MedianCutQuantizer.quantize(opaqueSamples)
        val slotRemap =
            (1..15).associateWith { oldSlot ->
                val oldColor = palette[oldSlot]
                newPalette.findExactIndex(oldColor) ?: newPalette.findNearestAssignedIndex(oldColor)
            }
        val remappedTextures = atlas.textures.map { it.remapPixels(slotRemap) }
        return copy(
            atlas = atlas.copy(textures = remappedTextures),
            palette = newPalette,
        )
    }

    fun withGridSnap(newSnap: Int): AtlasDocument {
        require(newSnap in VALID_GRID_SNAPS) { "gridSnap must be one of $VALID_GRID_SNAPS, got $newSnap" }
        return copy(gridSnap = newSnap)
    }

    fun withPalette(newPalette: SnesPalette): AtlasDocument = copy(palette = newPalette)

    fun withPaletteColor(
        slot: Int,
        color: SnesColor,
        assigned: Boolean = true,
    ): AtlasDocument = copy(palette = palette.withSlot(slot, color, assigned = assigned))

    fun unassignPaletteSlot(slot: Int): AtlasDocument {
        if (slot !in 1..15) return this
        val newPal = palette.unassignSlot(slot)
        val newTextures = atlas.textures.map { it.remapPixels(mapOf(slot to 0)) }
        return copy(atlas = atlas.copy(textures = newTextures), palette = newPal)
    }

    fun moveTexture(
        index: Int,
        newX: Int,
        newY: Int,
    ): Result<AtlasDocument> =
        runCatching {
            require(index in atlas.textures.indices) { "Invalid texture index $index" }
            val tex = atlas.textures[index]
            require(atlas.canPlace(tex, newX, newY, excludeTextureIndex = index)) {
                "Cannot move '${tex.name}' to ($newX, $newY): out of bounds or collides with another texture"
            }
            copy(atlas = atlas.withUpdatedTexture(index, tex.copy(x = newX, y = newY)))
        }

    fun updateTexturePixels(
        index: Int,
        newPixels: ByteArray,
    ): Result<AtlasDocument> =
        runCatching {
            require(index in atlas.textures.indices) { "Invalid texture index $index" }
            val tex = atlas.textures[index]
            val updated = tex.copy(indexedPixels = newPixels.copyOf())
            copy(atlas = atlas.withUpdatedTexture(index, updated))
        }

    /**
     * Renames `atlas.textures[index]` to [newName] after verifying case-insensitive ca65 identifier
     * uniqueness, cascading the rename across [VirtualSlice.sourceTexture], [DescriptorAlias.target],
     * and [TextureAtlas.descriptorOrder].
     */
    fun renameTexture(
        index: Int,
        newName: String,
    ): Result<AtlasDocument> =
        runCatching {
            require(index in atlas.textures.indices) { "Invalid texture index $index" }
            val trimmed = newName.trim()
            require(SourceTexture.isValidCa65Identifier(trimmed)) {
                "Texture name '$trimmed' is not a valid ca65 identifier (must match [A-Za-z_][A-Za-z0-9_]*)"
            }
            val oldTex = atlas.textures[index]
            val oldName = oldTex.name

            val conflict = findCaseInsensitiveConflict(trimmed, excludeTextureIndex = index)
            require(conflict == null) {
                "Identifier '$trimmed' collides with existing descriptor '$conflict' (ca65 identifiers are case-insensitive)"
            }

            val updatedTextures = atlas.textures.toMutableList()
            updatedTextures[index] = oldTex.copy(name = trimmed)

            val updatedSlices =
                atlas.slices.map { slice ->
                    if (slice.sourceTexture.equals(oldName, ignoreCase = true)) {
                        slice.copy(sourceTexture = trimmed)
                    } else {
                        slice
                    }
                }
            val updatedAliases =
                atlas.aliases.map { alias ->
                    if (alias.target.equals(oldName, ignoreCase = true)) {
                        alias.copy(target = trimmed)
                    } else {
                        alias
                    }
                }
            val updatedOrder =
                atlas.descriptorOrder?.map { entry ->
                    if (entry.equals(oldName, ignoreCase = true)) trimmed else entry
                }

            copy(
                atlas =
                    atlas.copy(
                        textures = updatedTextures,
                        slices = updatedSlices,
                        aliases = updatedAliases,
                        descriptorOrder = updatedOrder,
                    ),
            )
        }

    /**
     * Removes `atlas.textures[index]`.
     *
     * When [cascadeDependents] is `false` (default), fails with a descriptive error if any
     * [VirtualSlice] or [DescriptorAlias] references the texture (directly or transitively).
     * Prunes removed descriptor names from [TextureAtlas.descriptorOrder].
     */
    fun removeTexture(
        index: Int,
        cascadeDependents: Boolean = false,
    ): Result<AtlasDocument> =
        runCatching {
            require(index in atlas.textures.indices) { "Invalid texture index $index" }
            val targetTex = atlas.textures[index]
            val removedUpper = LinkedHashSet<String>()
            removedUpper.add(targetTex.name.uppercase())

            val dependentSlices =
                atlas.slices.filter { it.sourceTexture.equals(targetTex.name, ignoreCase = true) }
            for (slice in dependentSlices) {
                removedUpper.add(slice.name.uppercase())
            }

            val dependentAliases = mutableListOf<DescriptorAlias>()
            var changed = true
            while (changed) {
                changed = false
                for (alias in atlas.aliases) {
                    val aliasUpper = alias.name.uppercase()
                    if (aliasUpper !in removedUpper && alias.target.uppercase() in removedUpper) {
                        removedUpper.add(aliasUpper)
                        dependentAliases.add(alias)
                        changed = true
                    }
                }
            }

            if ((dependentSlices.isNotEmpty() || dependentAliases.isNotEmpty()) && !cascadeDependents) {
                val refs =
                    buildList {
                        dependentSlices.forEach { add("slice '${it.name}'") }
                        dependentAliases.forEach { add("alias '${it.name}'") }
                    }
                error(
                    "Cannot delete texture '${targetTex.name}' because it is referenced by: " +
                        "${refs.joinToString(", ")}. Remove dependent descriptors first.",
                )
            }

            val nextTextures = atlas.textures.filterIndexed { i, _ -> i != index }
            val nextSlices = atlas.slices.filter { it.name.uppercase() !in removedUpper }
            val nextAliases = atlas.aliases.filter { it.name.uppercase() !in removedUpper }
            val nextOrder = atlas.descriptorOrder?.filter { it.uppercase() !in removedUpper }

            copy(
                atlas =
                    atlas.copy(
                        textures = nextTextures,
                        slices = nextSlices,
                        aliases = nextAliases,
                        descriptorOrder = nextOrder,
                    ),
            )
        }

    fun addSlice(slice: VirtualSlice): Result<AtlasDocument> =
        runCatching {
            ensureCanAddDescriptor(slice.name)
            val parent =
                atlas.textures.find { it.name.equals(slice.sourceTexture, ignoreCase = true) }
                    ?: error("Source texture '${slice.sourceTexture}' does not exist in atlas")
            require(
                slice.x >= 0 &&
                    slice.y >= 0 &&
                    slice.x + slice.width <= parent.width &&
                    slice.y + slice.height <= parent.height,
            ) {
                "Virtual slice '${slice.name}' is out of bounds of '${parent.name}' (${parent.width}x${parent.height})"
            }
            val canonicalSlice = slice.copy(sourceTexture = parent.name)
            val nextOrder = atlas.descriptorOrder?.let { it + canonicalSlice.name }
            copy(atlas = atlas.copy(slices = atlas.slices + canonicalSlice, descriptorOrder = nextOrder))
        }

    fun addAlias(alias: DescriptorAlias): Result<AtlasDocument> =
        runCatching {
            ensureCanAddDescriptor(alias.name)
            val targetExists =
                atlas.textures.any { it.name.equals(alias.target, ignoreCase = true) } ||
                    atlas.slices.any { it.name.equals(alias.target, ignoreCase = true) } ||
                    atlas.aliases.any { it.name.equals(alias.target, ignoreCase = true) }
            require(targetExists) {
                "Descriptor alias '${alias.name}' references unknown target '${alias.target}'"
            }
            val nextOrder = atlas.descriptorOrder?.let { it + alias.name }
            val candidate = copy(atlas = atlas.copy(aliases = atlas.aliases + alias, descriptorOrder = nextOrder))
            DescriptorTable.buildFromAtlasOrThrow(candidate.atlas)
            candidate
        }

    /**
     * Places [texture] into the atlas according to [policy], preserving existing texture coordinates
     * and growing the canvas up to `256x256` when [PlacementPolicy.AUTO_GROW_MAX_256] is active.
     * Never falls back to overlapping `(0, 0)` coordinates.
     */
    fun placeNewTexture(
        texture: SourceTexture,
        policy: PlacementPolicy = PlacementPolicy.AUTO_GROW_MAX_256,
    ): Result<AtlasDocument> =
        runCatching {
            ensureCanAddDescriptor(texture.name)

            val currentPos = atlas.findPlacement(texture, gridSnap)
            if (currentPos != null) {
                val placed = texture.copy(x = currentPos.first, y = currentPos.second)
                val nextOrder = atlas.descriptorOrder?.let { it + placed.name }
                return@runCatching copy(
                    atlas = atlas.copy(textures = atlas.textures + placed, descriptorOrder = nextOrder),
                )
            }

            if (policy == PlacementPolicy.STRICT_BOUNDS) {
                error(
                    "Could not find free placement for '${texture.name}' (${texture.width}x${texture.height}) " +
                        "in ${atlas.width}x${atlas.height} atlas",
                )
            }

            // Grow canvas in power-of-two steps sorted by byte size ascending (256x128 = 32 KB before 256x256 = 64 KB)
            val candidateDims =
                buildList {
                    for (w in SourceTexture.VALID_DIMENSIONS) {
                        for (h in SourceTexture.VALID_DIMENSIONS) {
                            if (w >= maxOf(atlas.width, texture.width) &&
                                h >= maxOf(atlas.height, texture.height) &&
                                (w > atlas.width || h > atlas.height)
                            ) {
                                add(w to h)
                            }
                        }
                    }
                }.sortedWith(
                    compareBy<Pair<Int, Int>> { it.first * it.second }
                        .thenBy { maxOf(it.first, it.second) }
                        .thenBy { it.second },
                )

            for ((candW, candH) in candidateDims) {
                val grownAtlas = atlas.copy(width = candW, height = candH)
                val grownPos = grownAtlas.findPlacement(texture, gridSnap)
                if (grownPos != null) {
                    val placed = texture.copy(x = grownPos.first, y = grownPos.second)
                    val nextOrder = grownAtlas.descriptorOrder?.let { it + placed.name }
                    return@runCatching copy(
                        atlas = grownAtlas.copy(textures = grownAtlas.textures + placed, descriptorOrder = nextOrder),
                    )
                }
            }

            error(
                "Could not find free placement for '${texture.name}' (${texture.width}x${texture.height}) " +
                    "even after expanding to maximum 256x256 atlas dimensions",
            )
        }

    fun beginImport(
        file: File,
        placementPolicy: PlacementPolicy = PlacementPolicy.AUTO_GROW_MAX_256,
    ): AtlasImportOutcome {
        val image =
            try {
                ImageIO.read(file) ?: return AtlasImportOutcome.Invalid("Could not decode image from '${file.name}'")
            } catch (e: Exception) {
                return AtlasImportOutcome.Invalid("Failed to read image '${file.name}': ${e.message}")
            }
        val suggestedName = SourceTexture.sanitizeToIdentifier(file.nameWithoutExtension)
        return beginImportImage(image, suggestedName, placementPolicy)
    }

    fun beginImportImage(
        image: BufferedImage,
        suggestedName: String,
        placementPolicy: PlacementPolicy = PlacementPolicy.AUTO_GROW_MAX_256,
    ): AtlasImportOutcome {
        val conflict = findCaseInsensitiveConflict(suggestedName)
        if (conflict != null) {
            return AtlasImportOutcome.Invalid(
                "Descriptor identifier '$suggestedName' collides with existing '$conflict' in atlas.",
            )
        }
        val totalDescriptors = atlas.textures.size + atlas.slices.size + atlas.aliases.size
        if (totalDescriptors >= DescriptorTable.MAX_ENTRIES) {
            return AtlasImportOutcome.Invalid(
                "Atlas already contains the maximum ${DescriptorTable.MAX_ENTRIES} descriptors.",
            )
        }

        return when (val res = TextureImporter.importFromImage(image, suggestedName, palette)) {
            is ImportResult.Success -> {
                val updatedDoc = copy(palette = res.updatedPalette)
                updatedDoc.placeNewTexture(res.texture, placementPolicy).fold(
                    onSuccess = { placedDoc ->
                        AtlasImportOutcome.Imported(placedDoc, placedDoc.atlas.textures.last())
                    },
                    onFailure = { err ->
                        AtlasImportOutcome.Invalid(err.message ?: "Failed to place '${res.texture.name}' in atlas")
                    },
                )
            }
            is ImportResult.NeedsRemapping -> {
                val initialMap =
                    res.uniqueColors.associateWith { color ->
                        palette.findExactIndex(color) ?: palette.findNearestAssignedIndex(color)
                    }
                AtlasImportOutcome.NeedsRemapping(
                    PendingTextureImport(
                        rawImage = res.rawImage,
                        suggestedName = res.suggestedName,
                        uniqueColors = res.uniqueColors,
                        unmappedColors = res.unmappedColors,
                        initialMapping = initialMap,
                    ),
                )
            }
            is ImportResult.Error -> AtlasImportOutcome.Invalid(res.message)
        }
    }

    /**
     * Headless batch bitmap import applying [conflictResolution] and [placementPolicy] atomically
     * across [files].
     */
    fun importFiles(
        files: List<File>,
        conflictResolution: ConflictResolution = ConflictResolution.FAIL,
        placementPolicy: PlacementPolicy = PlacementPolicy.STRICT_BOUNDS,
    ): Result<AtlasDocument> =
        runCatching {
            require(files.isNotEmpty()) { "No image files specified for import" }
            var current = this
            for (imgFile in files) {
                require(imgFile.exists()) { "Image file '${imgFile.path}' not found" }
                when (val outcome = current.beginImport(imgFile, placementPolicy)) {
                    is AtlasImportOutcome.Imported -> current = outcome.document
                    is AtlasImportOutcome.NeedsRemapping -> {
                        current =
                            when (conflictResolution) {
                                ConflictResolution.SNAP_TO_PALETTE ->
                                    outcome.pending.commitSnap(current, placementPolicy).getOrThrow()
                                ConflictResolution.GLOBAL_QUANTIZE ->
                                    outcome.pending.commitGlobalQuantize(current, placementPolicy).getOrThrow()
                                ConflictResolution.FAIL ->
                                    error(
                                        "Image '${imgFile.name}' has unmapped colors: " +
                                            "${outcome.pending.unmappedColors}. Pass snap_palette or auto_quantize.",
                                    )
                            }
                    }
                    is AtlasImportOutcome.Invalid ->
                        error("Failed to import '${imgFile.name}': ${outcome.message}")
                }
            }
            current
        }

    fun autoPack(): Result<AtlasDocument> =
        runCatching {
            val packed =
                atlas.autoPack(gridSnap)
                    ?: error(
                        "Could not auto-pack textures into ${atlas.width}x${atlas.height} atlas with grid $gridSnap",
                    )
            copy(atlas = packed)
        }

    fun autoFit(): AtlasDocument {
        val (fitW, fitH) = atlas.autoFitDimensions()
        return copy(atlas = atlas.copy(width = fitW, height = fitH))
    }

    fun resizeAtlas(
        newWidth: Int,
        newHeight: Int,
    ): Result<AtlasDocument> =
        runCatching {
            val resized = atlas.copy(width = newWidth, height = newHeight)
            val oob = resized.textures.firstOrNull { !it.isWithinBounds(newWidth, newHeight) }
            require(oob == null) {
                "Cannot resize atlas to ${newWidth}x$newHeight: texture '${oob!!.name}' at (${oob.x}, ${oob.y}) " +
                    "(${oob.width}x${oob.height}) would exceed atlas bounds"
            }
            copy(atlas = resized)
        }

    fun toJson(): String = gson.toJson(toDto())

    /**
     * Validates all invariants *before* touching the filesystem, then exports the selected companion
     * binaries (`.bin`, `.pal`, `.tab`, `.i`, `_atlas.png`) into [outputDir] without writing a `.sfxproj`.
     */
    fun exportOnly(
        outputDir: File,
        spec: AtlasExportSpec = AtlasExportSpec(name),
    ): ExportedAssets {
        val validation = validate(warnBank71Overflow = false)
        if (!validation.valid) {
            throw IllegalStateException(
                validation.errors.joinToString("; ") { "[${it.code}] ${it.message}" },
            )
        }
        val table = descriptorTable().getOrThrow()
        val binBytes = if (spec.includeGraphicsBin) atlas.generateBinary() else null
        val palBytes = if (spec.includePaletteBin) palette.toBgr555Binary() else null
        val indexText = if (spec.includeIndexFile) AtlasIndexGenerator.generate(atlas, table.entries, spec.baseName) else null
        val tabBytes = if (spec.includeTableBin) table.toTabBinary() else null

        outputDir.mkdirs()
        val gBin = File(outputDir, spec.graphicsBinFileName)
        if (binBytes != null) gBin.writeBytes(binBytes)

        val pBin = File(outputDir, spec.paletteBinFileName)
        if (palBytes != null) pBin.writeBytes(palBytes)

        val idx = File(outputDir, spec.indexFileName)
        if (indexText != null) idx.writeText(indexText)

        val png = File(outputDir, spec.pngFileName)
        if (spec.includePng) AtlasImageRenderer.writeAtlasPng(atlas, palette, png)

        val tBin = File(outputDir, spec.tableBinFileName)
        if (tabBytes != null) tBin.writeBytes(tabBytes)

        return ExportedAssets(
            graphicsBinFile = gBin,
            paletteBinFile = pBin,
            indexFile = idx,
            pngFile = png,
            tableBinFile = if (spec.includeTableBin) tBin else null,
        )
    }

    /**
     * Atomically validates all invariants *before* writing any file, persists the authoring JSON to
     * [documentFile] in place, and writes companion binaries (`.bin`, `.pal`, `.tab`, `.i`, `_atlas.png`)
     * to `outputDir ?: documentFile.parentFile` without copying `.sfxproj` into [outputDir].
     */
    fun saveAndExport(
        documentFile: File,
        outputDir: File? = null,
        spec: AtlasExportSpec = AtlasExportSpec(documentFile.nameWithoutExtension),
    ): ExportedAssets {
        val validation = validate(warnBank71Overflow = false)
        if (!validation.valid) {
            throw IllegalStateException(
                validation.errors.joinToString("; ") { "[${it.code}] ${it.message}" },
            )
        }

        // Materialize all outputs in memory first so no partial/torn writes can occur
        val table = descriptorTable().getOrThrow()
        val jsonDoc = if (name.isBlank()) copy(name = documentFile.nameWithoutExtension) else this
        val json = jsonDoc.toJson()
        val binBytes = if (spec.includeGraphicsBin) atlas.generateBinary() else null
        val palBytes = if (spec.includePaletteBin) palette.toBgr555Binary() else null
        val indexText = if (spec.includeIndexFile) AtlasIndexGenerator.generate(atlas, table.entries, spec.baseName) else null
        val tabBytes = if (spec.includeTableBin) table.toTabBinary() else null

        documentFile.parentFile?.mkdirs()
        documentFile.writeText(json)

        val destDir = outputDir ?: documentFile.parentFile ?: File(".")
        destDir.mkdirs()

        val gBin = File(destDir, spec.graphicsBinFileName)
        if (binBytes != null) gBin.writeBytes(binBytes)

        val pBin = File(destDir, spec.paletteBinFileName)
        if (palBytes != null) pBin.writeBytes(palBytes)

        val idx = File(destDir, spec.indexFileName)
        if (indexText != null) idx.writeText(indexText)

        val png = File(destDir, spec.pngFileName)
        if (spec.includePng) AtlasImageRenderer.writeAtlasPng(atlas, palette, png)

        val tBin = File(destDir, spec.tableBinFileName)
        if (tabBytes != null) tBin.writeBytes(tabBytes)

        return ExportedAssets(
            graphicsBinFile = gBin,
            paletteBinFile = pBin,
            indexFile = idx,
            pngFile = png,
            tableBinFile = if (spec.includeTableBin) tBin else null,
        )
    }

    private fun ensureCanAddDescriptor(candidateName: String) {
        val conflict = findCaseInsensitiveConflict(candidateName)
        require(conflict == null) {
            "Descriptor identifier '$candidateName' collides with existing '$conflict' (ca65 identifiers are case-insensitive)"
        }
        val total = atlas.textures.size + atlas.slices.size + atlas.aliases.size
        require(total < DescriptorTable.MAX_ENTRIES) {
            "Texture Table already has $total entries (maximum ${DescriptorTable.MAX_ENTRIES})"
        }
    }

    private fun findCaseInsensitiveConflict(
        candidateName: String,
        excludeTextureIndex: Int = -1,
    ): String? {
        for ((idx, tex) in atlas.textures.withIndex()) {
            if (idx == excludeTextureIndex) continue
            if (tex.name.equals(candidateName, ignoreCase = true)) return tex.name
        }
        atlas.slices.find { it.name.equals(candidateName, ignoreCase = true) }?.let { return it.name }
        atlas.aliases.find { it.name.equals(candidateName, ignoreCase = true) }?.let { return it.name }
        return null
    }

    private fun toDto(): ProjectDto {
        val paletteDtos =
            palette.slots.mapIndexed { idx, slot ->
                ProjectColorDto(
                    index = idx,
                    r = slot.color.r,
                    g = slot.color.g,
                    b = slot.color.b,
                    assigned = slot.isAssigned,
                )
            }

        val textureDtos =
            atlas.textures.map { tex ->
                ProjectTextureDto(
                    name = tex.name,
                    width = tex.width,
                    height = tex.height,
                    x = tex.x,
                    y = tex.y,
                    pixelsHex = pixelsToHex(tex.indexedPixels),
                    pixelsAscii = pixelsToAscii(tex.indexedPixels, tex.width, tex.height),
                )
            }

        val sliceDtos =
            atlas.slices.map {
                ProjectSliceDto(
                    name = it.name,
                    sourceTexture = it.sourceTexture,
                    x = it.x,
                    y = it.y,
                    width = it.width,
                    height = it.height,
                )
            }

        val aliasDtos =
            atlas.aliases.map {
                ProjectAliasDto(
                    name = it.name,
                    target = it.target,
                )
            }

        return ProjectDto(
            name = name,
            atlasWidth = atlas.width,
            atlasHeight = atlas.height,
            gridSnap = gridSnap,
            palette = paletteDtos,
            textures = textureDtos,
            slices = if (sliceDtos.isNotEmpty()) sliceDtos else null,
            aliases = if (aliasDtos.isNotEmpty()) aliasDtos else null,
            descriptorOrder = atlas.descriptorOrder,
        )
    }

    companion object {
        const val BANK_71_MAX_ATLAS_BYTES: Int = 32_768
        val VALID_GRID_SNAPS: Set<Int> = setOf(4, 8, 16, 32)
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        fun load(projectFile: File): AtlasDocument {
            val fallbackName = projectFile.nameWithoutExtension.ifBlank { "TEXTURES" }
            return load(projectFile.readText(), fallbackName = fallbackName)
        }

        fun load(reader: Reader): AtlasDocument = load(reader.readText())

        fun load(stream: InputStream): AtlasDocument = load(stream.bufferedReader(Charsets.UTF_8).readText())

        fun load(
            json: String,
            fallbackName: String = "TEXTURES",
        ): AtlasDocument {
            val dto =
                gson.fromJson(json, ProjectDto::class.java)
                    ?: error("Empty or invalid .sfxproj JSON document")

            var palette = SnesPalette()
            for (colorDto in dto.palette.orEmpty()) {
                if (colorDto.index in 1..15) {
                    palette =
                        palette.withSlot(
                            colorDto.index,
                            SnesColor(colorDto.r, colorDto.g, colorDto.b),
                            assigned = colorDto.assigned,
                        )
                }
            }

            val textures =
                dto.textures.orEmpty().map { texDto ->
                    val rawAscii = texDto.pixelsAscii
                    val pixels =
                        if (!rawAscii.isNullOrBlank()) {
                            asciiToPixels(rawAscii, texDto.width, texDto.height)
                        } else {
                            hexToPixels(texDto.pixelsHex, texDto.width, texDto.height)
                        }

                    SourceTexture(
                        name = texDto.name,
                        width = texDto.width,
                        height = texDto.height,
                        x = texDto.x,
                        y = texDto.y,
                        indexedPixels = pixels,
                    )
                }

            val slices =
                dto.slices?.map {
                    VirtualSlice(
                        name = it.name,
                        sourceTexture = it.sourceTexture,
                        x = it.x,
                        y = it.y,
                        width = it.width,
                        height = it.height,
                    )
                } ?: emptyList()

            val aliases =
                dto.aliases?.map {
                    DescriptorAlias(
                        name = it.name,
                        target = it.target,
                    )
                } ?: emptyList()

            val atlas =
                TextureAtlas(
                    width = dto.atlasWidth,
                    height = dto.atlasHeight,
                    textures = textures,
                    slices = slices,
                    aliases = aliases,
                    descriptorOrder = dto.descriptorOrder,
                )

            val resolvedName = dto.name?.takeIf { it.isNotBlank() } ?: fallbackName
            val resolvedSnap = if (dto.gridSnap > 0) dto.gridSnap else 16

            return AtlasDocument(
                name = resolvedName,
                atlas = atlas,
                palette = palette,
                gridSnap = resolvedSnap,
            )
        }

        fun pixelsToHex(pixels: ByteArray): String {
            val sb = StringBuilder(pixels.size * 2)
            for (b in pixels) {
                sb.append(String.format("%02X", b.toInt() and 0x0F))
            }
            return sb.toString()
        }

        fun hexToPixels(
            hex: String,
            width: Int,
            height: Int,
        ): ByteArray {
            val pixels = ByteArray(width * height)
            for (i in pixels.indices) {
                val hexByte =
                    if (i * 2 + 1 < hex.length) {
                        hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: 0
                    } else {
                        0
                    }
                pixels[i] = (hexByte and 0x0F).toByte()
            }
            return pixels
        }

        fun pixelsToAscii(
            pixels: ByteArray,
            width: Int,
            height: Int,
        ): String {
            val sb = StringBuilder()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val p = pixels[y * width + x].toInt() and 0x0F
                    val c =
                        when {
                            p == 0 -> '.'
                            p < 10 -> ('0' + p)
                            else -> ('A' + (p - 10))
                        }
                    sb.append(c)
                }
                sb.append("\n")
            }
            return sb.toString().trimEnd()
        }

        fun asciiToPixels(
            ascii: String,
            width: Int,
            height: Int,
        ): ByteArray {
            val clean = ascii.filter { !it.isWhitespace() }
            require(clean.length == width * height) {
                "ASCII pixel length must be ${width * height}, got ${clean.length}"
            }
            val pixels = ByteArray(clean.length)
            for (i in clean.indices) {
                val c = clean[i]
                pixels[i] =
                    when (c) {
                        '.' -> 0.toByte()
                        in '0'..'9' -> (c - '0').toByte()
                        in 'a'..'f' -> (c - 'a' + 10).toByte()
                        in 'A'..'F' -> (c - 'A' + 10).toByte()
                        else -> throw IllegalArgumentException("Invalid ASCII pixel character '$c' at index $i")
                    }
            }
            return pixels
        }
    }
}
