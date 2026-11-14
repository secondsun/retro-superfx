package dev.secondsun.sfxstudio.core.level

/**
 * Declarative single-cell or rectangular region patch applied to a [MapGrid].
 *
 * Shared across `StudioOperations`, `CliMain` (`level set-cells`), and `StudioMcpServer` (`sfx_manage_level`).
 * Nullable texture fields use explicit update flags ([updateTopTex], [updateSkirtTex], [updatePanelTex])
 * so callers can distinguish leaving a texture slot unchanged (`update* = false`) from clearing a slot
 * to `null` / `"NONE"` (`update* = true`).
 */
data class CellPatch(
    val col: Int? = null,
    val row: Int? = null,
    val colStart: Int? = null,
    val colEnd: Int? = null,
    val rowStart: Int? = null,
    val rowEnd: Int? = null,
    val baseHeight: Int? = null,
    val archetype: String? = null,
    val rotation: Int? = null,
    val topTex: String? = null,
    val updateTopTex: Boolean = topTex != null,
    val skirtTex: String? = null,
    val updateSkirtTex: Boolean = skirtTex != null,
    val panelTex: String? = null,
    val updatePanelTex: Boolean = panelTex != null,
    val panels: List<String>? = null,
    val tall2h: Boolean? = null,
) {
    fun resolveColumnRange(): IntRange {
        val c0 = colStart ?: col ?: throw IllegalArgumentException("CellPatch missing 'col' or 'colStart'")
        val c1 = colEnd ?: col ?: throw IllegalArgumentException("CellPatch missing 'col' or 'colEnd'")
        require(c0 <= c1) { "Invalid column range $c0..$c1 (colStart must be <= colEnd)" }
        return c0..c1
    }

    fun resolveRowRange(): IntRange {
        val r0 = rowStart ?: row ?: throw IllegalArgumentException("CellPatch missing 'row' or 'rowStart'")
        val r1 = rowEnd ?: row ?: throw IllegalArgumentException("CellPatch missing 'row' or 'rowEnd'")
        require(r0 <= r1) { "Invalid row range $r0..$r1 (rowStart must be <= rowEnd)" }
        return r0..r1
    }

    fun applyTo(existing: MapCell): MapCell {
        val nextHeight = baseHeight ?: existing.baseHeight
        val nextArch = archetype?.let { ShapeArchetype.fromSymbol(it) } ?: existing.archetype
        val nextRot = rotation?.let { CellRotation.fromDegreesOrIndex(it) } ?: existing.rotation
        val nextTop = if (updateTopTex) normalizeOptionalTexture(topTex) else existing.topTex
        val nextSkirt = if (updateSkirtTex) normalizeOptionalTexture(skirtTex) else existing.skirtTex
        val nextPanelTex = if (updatePanelTex) normalizeOptionalTexture(panelTex) else existing.panelTex
        val nextPanels =
            when {
                panels != null -> EdgePanels.fromEdgeNames(panels, tall2h ?: existing.panels.tall2h)
                tall2h != null -> existing.panels.copy(tall2h = existing.panels.hasAny && tall2h)
                else -> existing.panels
            }

        return MapCell(
            baseHeight = nextHeight,
            archetype = nextArch,
            rotation = nextRot,
            topTex = nextTop,
            skirtTex = nextSkirt,
            panelTex = nextPanelTex,
            panels = nextPanels,
        )
    }

    companion object {
        fun normalizeOptionalTexture(raw: String?): String? {
            val clean = raw?.trim()
            if (clean.isNullOrEmpty() ||
                clean.equals("NONE", ignoreCase = true) ||
                clean.equals("TEX_NONE", ignoreCase = true) ||
                clean.equals("TEXTURE_ID_NONE", ignoreCase = true)
            ) {
                return null
            }
            return clean
        }
    }
}

/**
 * Applies a sequence of [CellPatch] mutations to this [MapGrid], returning the updated [MapGrid]
 * together with the count of cell updates performed.
 */
fun MapGrid.applyPatches(patches: List<CellPatch>): Pair<MapGrid, Int> {
    if (patches.isEmpty()) return this to 0
    val mutableCells = cells.toMutableList()
    var updatedCount = 0

    for (patch in patches) {
        val colRange = patch.resolveColumnRange()
        val rowRange = patch.resolveRowRange()
        for (r in rowRange) {
            for (c in colRange) {
                require(isInBounds(c, r)) {
                    "Cell coordinate ($c, $r) out of bounds (${cols}x$rows)"
                }
                val idx = r * cols + c
                mutableCells[idx] = patch.applyTo(mutableCells[idx])
                updatedCount++
            }
        }
    }

    return copy(cells = mutableCells) to updatedCount
}
