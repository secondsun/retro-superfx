package dev.secondsun.sfxstudio.core.level

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.DescriptorLookup
import dev.secondsun.sfxstudio.core.atlas.DescriptorTable
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import java.io.File
import java.io.InputStream
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SFX_LEVEL_EXTENSION = "sfxlevel"

/**
 * The 12 shape archetype variants supported by IsoEngine / gsu_iso (`bits [5:0]` of `archetype_id`).
 */
enum class ShapeArchetype(
    val id: Int,
    val displayName: String,
    val heightSpan1h: Int,
    val usesSkirtSlot: Boolean,
    val usesPanelSlot: Boolean,
    val isLiquid: Boolean = false,
    val isRotationallySymmetric: Boolean = false,
) {
    PRIM_FLAT(0, "Flat Block", 0, usesSkirtSlot = true, usesPanelSlot = false, isRotationallySymmetric = true),
    PRIM_WEDGE_STRAIGHT(1, "Straight Wedge (+1h)", 1, usesSkirtSlot = true, usesPanelSlot = false),
    PRIM_WEDGE_STRAIGHT_2H(2, "Straight Wedge (+2h)", 2, usesSkirtSlot = true, usesPanelSlot = false),
    PRIM_WEDGE_OUTER(3, "Outer Corner Wedge (+1h)", 1, usesSkirtSlot = true, usesPanelSlot = false),
    PRIM_WEDGE_OUTER_2H(4, "Outer Corner Wedge (+2h)", 2, usesSkirtSlot = true, usesPanelSlot = false),
    PRIM_WEDGE_INNER(5, "Inner Corner Wedge (+1h)", 1, usesSkirtSlot = true, usesPanelSlot = false),
    PRIM_WEDGE_INNER_2H(6, "Inner Corner Wedge (+2h)", 2, usesSkirtSlot = true, usesPanelSlot = false),
    PRIM_STAIRS(7, "Stairs (+2h)", 2, usesSkirtSlot = true, usesPanelSlot = false),
    PRIM_LIQUID_SHALLOW(
        8,
        "Shallow Liquid (+1h)",
        1,
        usesSkirtSlot = true,
        usesPanelSlot = false,
        isLiquid = true,
        isRotationallySymmetric = true,
    ),
    PRIM_LIQUID_DEEP(
        9,
        "Deep Liquid (+2h)",
        2,
        usesSkirtSlot = true,
        usesPanelSlot = false,
        isLiquid = true,
        isRotationallySymmetric = true,
    ),
    PRIM_PILLAR(10, "Inset Pillar (+2h)", 2, usesSkirtSlot = true, usesPanelSlot = false, isRotationallySymmetric = true),
    PRIM_FOLIAGE_CROSS(11, "Foliage Cross (+2h)", 2, usesSkirtSlot = true, usesPanelSlot = true, isRotationallySymmetric = true),
    ;

    companion object {
        fun fromId(id: Int): ShapeArchetype =
            entries.find { it.id == id }
                ?: throw IllegalArgumentException("Unknown ShapeArchetype ID: $id")

        fun fromSymbol(raw: String): ShapeArchetype {
            val trimmed = raw.trim().uppercase()
            entries.find { it.name == trimmed }?.let { return it }
            trimmed.toIntOrNull()?.let { return fromId(it) }
            throw IllegalArgumentException("Unknown ShapeArchetype symbol: '$raw'")
        }
    }
}

/**
 * 90-degree clockwise cell rotation (`bits [7:6]` of `archetype_id`).
 */
enum class CellRotation(
    val index: Int,
    val degrees: Int,
) {
    ROT_0(0, 0),
    ROT_90(1, 90),
    ROT_180(2, 180),
    ROT_270(3, 270),
    ;

    val encodedBits: Int
        get() = index shl 6

    fun nextClockwise(): CellRotation = fromIndex((index + 1) and 3)

    fun prevCounterClockwise(): CellRotation = fromIndex((index + 3) and 3)

    companion object {
        fun fromIndex(index: Int): CellRotation =
            entries.find { it.index == index }
                ?: throw IllegalArgumentException("Invalid rotation index: $index (must be 0..3)")

        fun fromDegreesOrIndex(value: Int): CellRotation =
            when (value) {
                0 -> ROT_0
                1, 90 -> ROT_90
                2, 180 -> ROT_180
                3, 270 -> ROT_270
                else -> throw IllegalArgumentException("Invalid rotation value: $value (expected 0/90/180/270 or 0..3)")
            }

        fun fromSymbol(raw: String): CellRotation {
            val trimmed = raw.trim().uppercase()
            entries.find { it.name == trimmed }?.let { return it }
            trimmed
                .removeSuffix("DEG")
                .trim()
                .toIntOrNull()
                ?.let { return fromDegreesOrIndex(it) }
            throw IllegalArgumentException("Unknown CellRotation symbol: '$raw'")
        }
    }
}

/**
 * Optional perimeter Edge Panels along the cell's N/E/S/W borders (`panel_flags` byte).
 */
data class EdgePanels(
    val north: Boolean = false,
    val east: Boolean = false,
    val south: Boolean = false,
    val west: Boolean = false,
    val tall2h: Boolean = false,
) {
    val hasAny: Boolean
        get() = north || east || south || west

    fun toFlagsByte(): Int {
        var flags = 0
        if (north) flags = flags or PANEL_N
        if (east) flags = flags or PANEL_E
        if (south) flags = flags or PANEL_S
        if (west) flags = flags or PANEL_W
        if (tall2h && hasAny) flags = flags or PANEL_TALL_2H
        return flags
    }

    fun toEdgeNames(): List<String> =
        buildList {
            if (north) add("N")
            if (east) add("E")
            if (south) add("S")
            if (west) add("W")
        }

    fun rotatedClockwise(): EdgePanels =
        copy(
            north = west,
            east = north,
            south = east,
            west = south,
        )

    companion object {
        const val PANEL_NONE = 0x00
        const val PANEL_N = 1 shl 0
        const val PANEL_E = 1 shl 1
        const val PANEL_S = 1 shl 2
        const val PANEL_W = 1 shl 3
        const val PANEL_TALL_2H = 1 shl 4

        fun fromFlagsByte(flags: Int): EdgePanels {
            val north = (flags and PANEL_N) != 0
            val east = (flags and PANEL_E) != 0
            val south = (flags and PANEL_S) != 0
            val west = (flags and PANEL_W) != 0
            val hasEdges = north || east || south || west
            return EdgePanels(
                north = north,
                east = east,
                south = south,
                west = west,
                tall2h = hasEdges && (flags and PANEL_TALL_2H) != 0,
            )
        }

        fun fromEdgeNames(
            names: List<String>?,
            tall2h: Boolean = false,
        ): EdgePanels {
            if (names.isNullOrEmpty()) return EdgePanels()
            var n = false
            var e = false
            var s = false
            var w = false
            var tall = tall2h
            for (raw in names) {
                when (raw.trim().uppercase()) {
                    "N", "NORTH", "PANEL_N" -> n = true
                    "E", "EAST", "PANEL_E" -> e = true
                    "S", "SOUTH", "PANEL_S" -> s = true
                    "W", "WEST", "PANEL_W" -> w = true
                    "2H", "TALL", "TALL_2H", "PANEL_TALL_2H" -> tall = true
                    "NONE", "PANEL_NONE", "" -> {}
                    else -> throw IllegalArgumentException("Unknown edge panel flag: '$raw'")
                }
            }
            val hasEdges = n || e || s || w
            return EdgePanels(north = n, east = e, south = s, west = w, tall2h = hasEdges && tall)
        }
    }
}

/**
 * Single authored cell within an isometric [MapGrid].
 *
 * Textures (`topTex`, `skirtTex`, `panelTex`) store symbolic descriptor names from the shared
 * Atlas Document so repacking or reordering the atlas never invalidates authored levels.
 */
data class MapCell(
    val baseHeight: Int = 0,
    val archetype: ShapeArchetype = ShapeArchetype.PRIM_FLAT,
    val rotation: CellRotation = CellRotation.ROT_0,
    val topTex: String? = null,
    val skirtTex: String? = null,
    val panelTex: String? = null,
    val panels: EdgePanels = EdgePanels(),
) {
    val encodedArchetypeByte: Int
        get() = rotation.encodedBits or archetype.id

    fun rotatedClockwise(): MapCell =
        copy(
            rotation = if (archetype.isRotationallySymmetric) CellRotation.ROT_0 else rotation.nextClockwise(),
            panels = panels.rotatedClockwise(),
        )
}

/**
 * Fixed-dimension 2D grid (`cols x rows`, `4..16` per axis) of [MapCell]s.
 */
data class MapGrid(
    val cols: Int,
    val rows: Int,
    val cells: List<MapCell>,
) {
    init {
        require(cols in MIN_DIM..MAX_DIM) {
            "MapGrid cols must be in $MIN_DIM..$MAX_DIM, got $cols"
        }
        require(rows in MIN_DIM..MAX_DIM) {
            "MapGrid rows must be in $MIN_DIM..$MAX_DIM, got $rows"
        }
        require(cells.size == cols * rows) {
            "MapGrid cells size must equal cols * rows (${cols * rows}), got ${cells.size}"
        }
    }

    val byteSize: Int
        get() = HEADER_BYTES + cols * rows * CELL_BYTES

    fun isInBounds(
        col: Int,
        row: Int,
    ): Boolean = col in 0 until cols && row in 0 until rows

    fun cellAt(
        col: Int,
        row: Int,
    ): MapCell {
        require(isInBounds(col, row)) { "Cell ($col, $row) out of bounds ($cols x $rows)" }
        return cells[row * cols + col]
    }

    fun baseHeightOrZero(
        col: Int,
        row: Int,
    ): Int = if (isInBounds(col, row)) cellAt(col, row).baseHeight else 0

    fun withCell(
        col: Int,
        row: Int,
        cell: MapCell,
    ): MapGrid {
        require(isInBounds(col, row)) { "Cell ($col, $row) out of bounds ($cols x $rows)" }
        val updated = cells.toMutableList()
        updated[row * cols + col] = cell
        return copy(cells = updated)
    }

    fun withCells(updates: Map<Pair<Int, Int>, MapCell>): MapGrid {
        if (updates.isEmpty()) return this
        val updated = cells.toMutableList()
        for ((coord, cell) in updates) {
            val (col, row) = coord
            require(isInBounds(col, row)) { "Cell ($col, $row) out of bounds ($cols x $rows)" }
            updated[row * cols + col] = cell
        }
        return copy(cells = updated)
    }

    fun resized(
        newCols: Int,
        newRows: Int,
        fillCell: MapCell = MapCell(),
    ): MapGrid {
        val next =
            List(newCols * newRows) { idx ->
                val c = idx % newCols
                val r = idx / newCols
                if (isInBounds(c, r)) cellAt(c, r) else fillCell
            }
        return MapGrid(cols = newCols, rows = newRows, cells = next)
    }

    companion object {
        const val MIN_DIM = 4
        const val MAX_DIM = 16
        const val HEADER_BYTES = 8
        const val CELL_BYTES = 8

        fun filled(
            cols: Int,
            rows: Int,
            defaultCell: MapCell = MapCell(),
        ): MapGrid = MapGrid(cols = cols, rows = rows, cells = List(cols * rows) { defaultCell })
    }
}

/**
 * Authored isometric Level Document (`.sfxlevel`), pairing a visual [MapGrid] with a shared
 * Atlas Document (`atlasPath`) and optional future S-CPU `gameplayLayers`.
 */
data class LevelDocument(
    val name: String,
    val atlasPath: String,
    val grid: MapGrid,
    val gameplayLayers: Map<String, Any?>? = null,
)

internal data class LevelCellDto(
    var col: Int? = null,
    var row: Int? = null,
    var baseHeight: Int = 0,
    var archetype: String? = ShapeArchetype.PRIM_FLAT.name,
    var rotation: Int = 0,
    var topTex: String? = null,
    var skirtTex: String? = null,
    var panelTex: String? = null,
    var panels: List<String>? = null,
    var tall2h: Boolean = false,
    var panelFlags: Int? = null,
)

internal data class LevelDocumentDto(
    var name: String? = null,
    var cols: Int = 0,
    var rows: Int = 0,
    var atlasPath: String? = null,
    var cells: List<LevelCellDto>? = null,
    var gameplayLayers: Map<String, Any?>? = null,
)

data class ExportedLevelAssets(
    val levelFile: File?,
    val mapBinFile: File,
    val headerFile: File,
    val previewPngFile: File?,
)

/**
 * Serializes and deserializes IsoEngine-compatible `<LEVEL>.map` binary files
 * (`8-byte map_header + cols * rows * 8-byte map_cell`).
 */
object LevelBinarySerializer {
    fun serialize(
        grid: MapGrid,
        atlas: TextureAtlas,
    ): ByteArray {
        val table = DescriptorTable.fromAtlas(atlas).getOrThrow()
        val availableNames = lazy { table.entries.joinToString { it.name } }
        val buf = ByteBuffer.allocate(grid.byteSize).order(ByteOrder.LITTLE_ENDIAN)

        // 8-byte map_header: cols (u8), rows (u8), origin_x (i16 = 0), origin_z (i16 = 0), reserved (u16 = 0)
        buf.put((grid.cols and 0xFF).toByte())
        buf.put((grid.rows and 0xFF).toByte())
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)

        for (cell in grid.cells) {
            val topId =
                when (val res = table.lookup(cell.topTex)) {
                    DescriptorLookup.Absent -> 0
                    is DescriptorLookup.Found -> res.index
                    is DescriptorLookup.Unknown ->
                        throw IllegalArgumentException(
                            "Unknown top texture symbol '${res.rawSymbol}' (available descriptors: ${availableNames.value})",
                        )
                }
            val skirtId =
                when (val res = table.lookup(cell.skirtTex)) {
                    DescriptorLookup.Absent -> topId
                    is DescriptorLookup.Found -> res.index
                    is DescriptorLookup.Unknown ->
                        throw IllegalArgumentException(
                            "Unknown skirt texture symbol '${res.rawSymbol}' (available descriptors: ${availableNames.value})",
                        )
                }
            val panelId =
                when (val res = table.lookup(cell.panelTex)) {
                    DescriptorLookup.Absent -> 0
                    is DescriptorLookup.Found -> res.index
                    is DescriptorLookup.Unknown ->
                        throw IllegalArgumentException(
                            "Unknown panel texture symbol '${res.rawSymbol}' (available descriptors: ${availableNames.value})",
                        )
                }

            buf.put((cell.baseHeight and 0xFF).toByte())
            buf.put((cell.encodedArchetypeByte and 0xFF).toByte())
            buf.put((topId and 0xFF).toByte())
            buf.put((skirtId and 0xFF).toByte())
            buf.put((panelId and 0xFF).toByte())
            buf.put((cell.panels.toFlagsByte() and 0xFF).toByte())
            buf.put(0) // drop_ns: derived at render time; authored as 0
            buf.put(0) // drop_ew: derived at render time; authored as 0
        }

        return buf.array()
    }

    fun deserialize(
        bytes: ByteArray,
        atlas: TextureAtlas,
    ): MapGrid {
        require(bytes.size >= MapGrid.HEADER_BYTES) {
            "Binary .map file must be at least ${MapGrid.HEADER_BYTES} bytes, got ${bytes.size}"
        }
        val cols = bytes[0].toInt() and 0xFF
        val rows = bytes[1].toInt() and 0xFF
        val expected = MapGrid.HEADER_BYTES + cols * rows * MapGrid.CELL_BYTES
        require(bytes.size == expected) {
            "Binary .map size mismatch for ${cols}x$rows: expected $expected bytes, got ${bytes.size}"
        }
        val descriptors = DescriptorTable.fromAtlas(atlas).getOrThrow().entries

        val cells =
            List(cols * rows) { idx ->
                val off = MapGrid.HEADER_BYTES + idx * MapGrid.CELL_BYTES
                val baseHeight = bytes[off].toInt() and 0xFF
                val archByte = bytes[off + 1].toInt() and 0xFF
                val topId = bytes[off + 2].toInt() and 0xFF
                val skirtId = bytes[off + 3].toInt() and 0xFF
                val panelId = bytes[off + 4].toInt() and 0xFF
                val panelFlags = bytes[off + 5].toInt() and 0xFF

                val archetype = ShapeArchetype.fromId(archByte and 0x3F)
                val rotation = CellRotation.fromIndex((archByte ushr 6) and 0x03)
                val panels = EdgePanels.fromFlagsByte(panelFlags)

                val topName = descriptors.getOrNull(topId)?.name
                val skirtName = descriptors.getOrNull(skirtId)?.name
                val panelName =
                    if (panelId == 0 && !panels.hasAny && archetype != ShapeArchetype.PRIM_FOLIAGE_CROSS) {
                        null
                    } else {
                        descriptors.getOrNull(panelId)?.name
                    }

                MapCell(
                    baseHeight = baseHeight,
                    archetype = archetype,
                    rotation = rotation,
                    topTex = topName,
                    skirtTex = skirtName,
                    panelTex = panelName,
                    panels = panels,
                )
            }

        return MapGrid(cols = cols, rows = rows, cells = cells)
    }
}

/**
 * Generates constants-only ca65 assembly header (`<LEVEL>.i`) containing grid dimensions
 * and byte size without emitting `.segment` data.
 */
object LevelHeaderGenerator {
    fun generate(
        levelName: String,
        grid: MapGrid,
    ): String {
        val prefix = SourceTexture.sanitizeToIdentifier(levelName).uppercase()
        val guard = "__${prefix}_LEVEL_I_DEFINED__"
        return buildString {
            appendLine(";; Auto-generated by SuperFX Studio Level Packer")
            appendLine(";; Level: $levelName (${grid.cols}x${grid.rows}, ${grid.byteSize} bytes)")
            appendLine()
            appendLine(".ifndef ::$guard")
            appendLine("::$guard = 1")
            appendLine()
            appendLine("${prefix}_COLS = ${grid.cols}")
            appendLine("${prefix}_ROWS = ${grid.rows}")
            appendLine("${prefix}_MAP_BYTE_SIZE = ${grid.byteSize}")
            appendLine()
            appendLine(".endif ; $guard")
        }
    }
}

/**
 * Persistence and export service for `.sfxlevel` documents.
 */
object LevelDocumentService {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun resolveAtlasFile(
        levelFile: File,
        atlasPath: String,
    ): File {
        val raw = File(atlasPath)
        if (raw.isAbsolute) return raw.normalize()
        val baseDir = levelFile.absoluteFile.parentFile ?: File(".").absoluteFile
        return File(baseDir, atlasPath).normalize()
    }

    internal fun toDto(document: LevelDocument): LevelDocumentDto {
        val cellDtos =
            List(document.grid.cols * document.grid.rows) { idx ->
                val col = idx % document.grid.cols
                val row = idx / document.grid.cols
                val cell = document.grid.cells[idx]
                val edgeNames = cell.panels.toEdgeNames()
                LevelCellDto(
                    col = col,
                    row = row,
                    baseHeight = cell.baseHeight,
                    archetype = cell.archetype.name,
                    rotation = cell.rotation.degrees,
                    topTex = cell.topTex,
                    skirtTex = cell.skirtTex,
                    panelTex = cell.panelTex,
                    panels = if (edgeNames.isNotEmpty()) edgeNames else null,
                    tall2h = cell.panels.tall2h,
                )
            }
        return LevelDocumentDto(
            name = document.name,
            cols = document.grid.cols,
            rows = document.grid.rows,
            atlasPath = document.atlasPath,
            cells = cellDtos,
            gameplayLayers = document.gameplayLayers,
        )
    }

    fun toJson(document: LevelDocument): String = gson.toJson(toDto(document))

    fun loadLevel(levelFile: File): LevelDocument = loadLevel(levelFile.readText())

    fun loadLevel(reader: Reader): LevelDocument = loadLevel(reader.readText())

    fun loadLevel(stream: InputStream): LevelDocument = loadLevel(stream.bufferedReader(Charsets.UTF_8).readText())

    fun loadLevel(json: String): LevelDocument {
        val dto = gson.fromJson(json, LevelDocumentDto::class.java)
        require(dto.cols in MapGrid.MIN_DIM..MapGrid.MAX_DIM) {
            "Level cols must be in ${MapGrid.MIN_DIM}..${MapGrid.MAX_DIM}, got ${dto.cols}"
        }
        require(dto.rows in MapGrid.MIN_DIM..MapGrid.MAX_DIM) {
            "Level rows must be in ${MapGrid.MIN_DIM}..${MapGrid.MAX_DIM}, got ${dto.rows}"
        }

        val cellsArray = Array(dto.cols * dto.rows) { MapCell() }
        for ((idx, cellDto) in dto.cells.orEmpty().withIndex()) {
            val col = cellDto.col ?: (idx % dto.cols)
            val row = cellDto.row ?: (idx / dto.cols)
            require(col in 0 until dto.cols && row in 0 until dto.rows) {
                "Cell coordinate ($col, $row) out of bounds (${dto.cols}x${dto.rows})"
            }
            val rawFlags = cellDto.panelFlags
            val panels =
                if (rawFlags != null) {
                    EdgePanels.fromFlagsByte(rawFlags)
                } else {
                    EdgePanels.fromEdgeNames(cellDto.panels, cellDto.tall2h)
                }
            cellsArray[row * dto.cols + col] =
                MapCell(
                    baseHeight = cellDto.baseHeight,
                    archetype = ShapeArchetype.fromSymbol(cellDto.archetype ?: ShapeArchetype.PRIM_FLAT.name),
                    rotation = CellRotation.fromDegreesOrIndex(cellDto.rotation),
                    topTex = cellDto.topTex?.takeIf { it.isNotBlank() },
                    skirtTex = cellDto.skirtTex?.takeIf { it.isNotBlank() },
                    panelTex = cellDto.panelTex?.takeIf { it.isNotBlank() },
                    panels = panels,
                )
        }

        return LevelDocument(
            name = dto.name ?: "untitled_level",
            atlasPath = dto.atlasPath.orEmpty(),
            grid = MapGrid(cols = dto.cols, rows = dto.rows, cells = cellsArray.toList()),
            gameplayLayers = dto.gameplayLayers,
        )
    }

    fun saveAuthoringJson(
        levelFile: File,
        document: LevelDocument,
    ) {
        levelFile.parentFile?.mkdirs()
        levelFile.writeText(toJson(document))
    }

    /**
     * Exports companion runtime assets (`.map`, `.i`, `_preview.png`) into [outputDir] using the
     * explicitly supplied [atlasDoc], without mutating or touching any `.sfxlevel` file on disk.
     */
    fun exportCompanionAssets(
        document: LevelDocument,
        atlasDoc: AtlasDocument,
        outputDir: File,
        baseName: String = document.name,
        previewYaw: Int = 15,
        sourceLevelFile: File? = null,
    ): ExportedLevelAssets {
        val mapBytes = LevelBinarySerializer.serialize(document.grid, atlasDoc.atlas)
        val headerText = LevelHeaderGenerator.generate(baseName, document.grid)

        outputDir.mkdirs()
        val headerFile = File(outputDir, "$baseName.i")
        headerFile.writeText(headerText)

        val mapBinFile = File(outputDir, "$baseName.map")
        mapBinFile.writeBytes(mapBytes)

        val png = File(outputDir, "${baseName}_preview.png")
        IsoSoftwareRenderer.writePreviewPng(
            grid = document.grid,
            atlas = atlasDoc.atlas,
            palette = atlasDoc.palette,
            destinationFile = png,
            yawIndex = previewYaw,
        )

        return ExportedLevelAssets(
            levelFile = sourceLevelFile,
            mapBinFile = mapBinFile,
            headerFile = headerFile,
            previewPngFile = png,
        )
    }

    /**
     * Saves `.sfxlevel` JSON to [levelFile] in place and exports companion runtime assets
     * (`.map`, `.i`, `_preview.png`) to `outputDir ?: levelFile.parentFile` using the explicitly
     * supplied [atlasDoc] (zero implicit disk atlas lookups).
     */
    fun saveAndExport(
        levelFile: File,
        document: LevelDocument,
        atlasDoc: AtlasDocument,
        outputDir: File? = null,
        previewYaw: Int = 15,
    ): ExportedLevelAssets {
        val mapBytes = LevelBinarySerializer.serialize(document.grid, atlasDoc.atlas)
        val baseName = levelFile.nameWithoutExtension
        val headerText = LevelHeaderGenerator.generate(baseName, document.grid)
        val json = toJson(document)

        levelFile.parentFile?.mkdirs()
        levelFile.writeText(json)

        val destDir = outputDir ?: levelFile.parentFile ?: File(".")
        destDir.mkdirs()

        val headerFile = File(destDir, "$baseName.i")
        headerFile.writeText(headerText)

        val mapBinFile = File(destDir, "$baseName.map")
        mapBinFile.writeBytes(mapBytes)

        val png = File(destDir, "${baseName}_preview.png")
        IsoSoftwareRenderer.writePreviewPng(
            grid = document.grid,
            atlas = atlasDoc.atlas,
            palette = atlasDoc.palette,
            destinationFile = png,
            yawIndex = previewYaw,
        )

        return ExportedLevelAssets(
            levelFile = levelFile,
            mapBinFile = mapBinFile,
            headerFile = headerFile,
            previewPngFile = png,
        )
    }
}
