package dev.secondsun.sfxstudio.core.level

import dev.secondsun.sfxstudio.core.atlas.DescriptorLookup
import dev.secondsun.sfxstudio.core.atlas.DescriptorTable
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import java.io.File

/**
 * Imports legacy hand-authored ca65 map include files (e.g. `showcase_map.i`, `starship_map.i`)
 * containing `def_cell` directives into a [LevelDocument].
 */
object Ca65MapImporter {
    private val COLS_REGEX = Regex("""^\s*(?:[A-Za-z0-9_]*_)?COLS\s*=\s*(\$[0-9A-Fa-f]+|\d+)""")
    private val ROWS_REGEX = Regex("""^\s*(?:[A-Za-z0-9_]*_)?ROWS\s*=\s*(\$[0-9A-Fa-f]+|\d+)""")
    private val DEF_CELL_REGEX = Regex("""^\s*def_cell\s+(.+)$""", RegexOption.IGNORE_CASE)

    fun importFile(
        ca65File: File,
        atlasPath: String,
        atlas: TextureAtlas? = null,
        levelName: String = ca65File.nameWithoutExtension,
    ): LevelDocument =
        importSource(
            source = ca65File.readText(),
            atlasPath = atlasPath,
            atlas = atlas,
            levelName = levelName,
        )

    fun importSource(
        source: String,
        atlasPath: String,
        atlas: TextureAtlas? = null,
        levelName: String = "imported_level",
    ): LevelDocument {
        val table = atlas?.let { DescriptorTable.fromAtlas(it).getOrThrow() }
        var cols: Int? = null
        var rows: Int? = null
        val cells = mutableListOf<MapCell>()

        for ((lineIdx, rawLine) in source.lines().withIndex()) {
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue

            COLS_REGEX.find(line)?.let {
                cols = parseIntLiteral(it.groupValues[1])
                return@let
            }
            ROWS_REGEX.find(line)?.let {
                rows = parseIntLiteral(it.groupValues[1])
                return@let
            }

            val defMatch = DEF_CELL_REGEX.find(line)
            if (defMatch != null) {
                val args = splitTopLevelCommaArgs(defMatch.groupValues[1])
                require(args.size >= 6) {
                    "Line ${lineIdx + 1}: expected at least 6 arguments to def_cell, got ${args.size}: '$rawLine'"
                }
                val baseHeight = parseIntLiteral(args[0])
                val (archetype, rotation) = parseArchetypeAndRotation(args[1])
                val topTex = resolveImportedTexture(args[2], table, zeroIsAbsent = false)
                val skirtTex = resolveImportedTexture(args[3], table, zeroIsAbsent = false)
                val panels = parseEdgePanels(args[5])
                val rawPanelTex = resolveImportedTexture(args[4], table, zeroIsAbsent = true)
                val panelTex =
                    if (rawPanelTex == null && !panels.hasAny && archetype != ShapeArchetype.PRIM_FOLIAGE_CROSS) {
                        null
                    } else {
                        rawPanelTex
                    }

                cells.add(
                    MapCell(
                        baseHeight = baseHeight,
                        archetype = archetype,
                        rotation = rotation,
                        topTex = topTex,
                        skirtTex = skirtTex,
                        panelTex = panelTex,
                        panels = panels,
                    ),
                )
            }
        }

        require(cells.isNotEmpty()) {
            "No def_cell directives found in ca65 map source"
        }

        val resolvedCols = cols ?: error("Missing MAP_COLS definition in ca65 map source")
        val resolvedRows = rows ?: error("Missing MAP_ROWS definition in ca65 map source")
        require(cells.size == resolvedCols * resolvedRows) {
            "Expected ${resolvedCols * resolvedRows} def_cell entries (${resolvedCols}x$resolvedRows), found ${cells.size}"
        }

        return LevelDocument(
            name = levelName,
            atlasPath = atlasPath,
            grid = MapGrid(cols = resolvedCols, rows = resolvedRows, cells = cells),
        )
    }

    private fun stripComment(line: String): String {
        val semi = line.indexOf(';')
        return if (semi >= 0) line.substring(0, semi) else line
    }

    private fun splitTopLevelCommaArgs(argList: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in argList) {
            when (ch) {
                '(' -> {
                    depth++
                    current.append(ch)
                }
                ')' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    current.append(ch)
                }
                ',' -> {
                    if (depth == 0) {
                        result.add(current.toString().trim())
                        current.setLength(0)
                    } else {
                        current.append(ch)
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString().trim())
        }
        return result
    }

    private fun parseIntLiteral(token: String): Int {
        val clean = unwrapParens(token.trim())
        return when {
            clean.startsWith("$") -> clean.substring(1).toInt(16)
            clean.startsWith("0x", ignoreCase = true) -> clean.substring(2).toInt(16)
            clean.startsWith("%") -> clean.substring(1).toInt(2)
            else -> clean.toInt()
        }
    }

    private fun unwrapParens(expr: String): String {
        var cur = expr.trim()
        while (cur.startsWith("(") && cur.endsWith(")")) {
            var depth = 0
            var wrapsAll = true
            for (i in 0 until cur.length - 1) {
                if (cur[i] == '(') depth++
                if (cur[i] == ')') depth--
                if (depth == 0) {
                    wrapsAll = false
                    break
                }
            }
            if (wrapsAll) {
                cur = cur.substring(1, cur.length - 1).trim()
            } else {
                break
            }
        }
        return cur
    }

    private fun parseArchetypeAndRotation(expr: String): Pair<ShapeArchetype, CellRotation> {
        val clean = unwrapParens(expr)
        val parts = clean.split('|').map { unwrapParens(it).trim() }.filter { it.isNotEmpty() }

        var archetype: ShapeArchetype? = null
        var rotation: CellRotation = CellRotation.ROT_0

        for (part in parts) {
            val upper = part.uppercase()
            when {
                upper.startsWith("ROT_") -> rotation = CellRotation.fromSymbol(upper)
                upper.startsWith("PRIM_") -> archetype = ShapeArchetype.fromSymbol(upper)
                else -> {
                    val num = parseIntLiteral(part)
                    archetype = ShapeArchetype.fromId(num and 0x3F)
                    rotation = CellRotation.fromIndex((num ushr 6) and 0x03)
                }
            }
        }

        return (archetype ?: ShapeArchetype.PRIM_FLAT) to rotation
    }

    private fun parseEdgePanels(expr: String): EdgePanels {
        val clean = unwrapParens(expr)
        val parts = clean.split('|').map { unwrapParens(it).trim() }.filter { it.isNotEmpty() }

        var flags = 0
        for (part in parts) {
            when (val upper = part.uppercase()) {
                "PANEL_NONE", "0", "$00" -> {}
                "PANEL_N" -> flags = flags or EdgePanels.PANEL_N
                "PANEL_E" -> flags = flags or EdgePanels.PANEL_E
                "PANEL_S" -> flags = flags or EdgePanels.PANEL_S
                "PANEL_W" -> flags = flags or EdgePanels.PANEL_W
                "PANEL_TALL_2H" -> flags = flags or EdgePanels.PANEL_TALL_2H
                else -> {
                    if (upper.firstOrNull()?.isDigit() == true || upper.startsWith("$") || upper.startsWith("%")) {
                        flags = flags or parseIntLiteral(part)
                    } else {
                        throw IllegalArgumentException("Unknown edge panel token '$part' in '$expr'")
                    }
                }
            }
        }
        return EdgePanels.fromFlagsByte(flags)
    }

    private fun resolveImportedTexture(
        expr: String,
        table: DescriptorTable?,
        zeroIsAbsent: Boolean = false,
    ): String? {
        val clean = unwrapParens(expr).trim()
        if (table != null) {
            return when (val lookup = table.lookup(clean, allowNumericIndex = true, zeroIsAbsent = zeroIsAbsent)) {
                DescriptorLookup.Absent -> null
                is DescriptorLookup.Found -> lookup.descriptor.name
                is DescriptorLookup.Unknown ->
                    throw IllegalArgumentException("Unknown texture symbol '${lookup.rawSymbol}' in ca65 map")
            }
        }

        if (clean.isEmpty() || clean.equals("TEX_NONE", ignoreCase = true) || clean.equals("NONE", ignoreCase = true)) {
            return null
        }

        val numericId =
            when {
                clean.startsWith("$") -> clean.substring(1).toIntOrNull(16)
                clean.startsWith("0x", ignoreCase = true) -> clean.substring(2).toIntOrNull(16)
                else -> clean.toIntOrNull()
            }
        if (numericId != null) {
            return if (numericId == 0) null else numericId.toString()
        }

        return DescriptorTable.stripTexturePrefix(clean).lowercase()
    }
}
