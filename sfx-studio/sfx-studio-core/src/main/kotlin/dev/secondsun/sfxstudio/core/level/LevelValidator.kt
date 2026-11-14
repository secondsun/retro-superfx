package dev.secondsun.sfxstudio.core.level

import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.DescriptorLookup
import dev.secondsun.sfxstudio.core.atlas.DescriptorTable
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.atlas.TextureDescriptorInfo

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
}

data class LevelDiagnostic(
    val severity: DiagnosticSeverity,
    val code: String,
    val message: String,
    val col: Int? = null,
    val row: Int? = null,
)

data class LevelValidationResult(
    val valid: Boolean,
    val errors: List<LevelDiagnostic>,
    val warnings: List<LevelDiagnostic>,
) {
    val allDiagnostics: List<LevelDiagnostic>
        get() = errors + warnings
}

/**
 * Pure in-process validator checking [LevelDocument] invariants against an explicitly supplied
 * [TextureAtlas] / [AtlasDocument] and IsoEngine / Bank `$71` rules.
 *
 * Disk and workspace security resolution of `document.atlasPath` belongs exclusively to
 * `StudioOperations` / `StudioState`.
 */
object LevelValidator {
    const val BANK_71_MAX_ATLAS_BYTES = 32_768
    const val MAX_BASE_HEIGHT = 31

    fun validate(
        document: LevelDocument,
        atlasDocument: AtlasDocument,
        maxAtlasBytes: Int = BANK_71_MAX_ATLAS_BYTES,
    ): LevelValidationResult = validate(document = document, atlas = atlasDocument.atlas, maxAtlasBytes = maxAtlasBytes)

    fun validate(
        document: LevelDocument,
        atlas: TextureAtlas,
        maxAtlasBytes: Int = BANK_71_MAX_ATLAS_BYTES,
    ): LevelValidationResult {
        val errors = mutableListOf<LevelDiagnostic>()
        val warnings = mutableListOf<LevelDiagnostic>()

        if (atlas.sizeBytes > maxAtlasBytes) {
            errors.add(
                LevelDiagnostic(
                    severity = DiagnosticSeverity.ERROR,
                    code = "ATLAS_EXCEEDS_BANK_71",
                    message =
                        "Atlas is ${atlas.width}x${atlas.height} (${atlas.sizeBytes} bytes), " +
                            "exceeding Bank \$71 limit of $maxAtlasBytes bytes.",
                ),
            )
        }

        val table =
            DescriptorTable.fromAtlas(atlas).getOrElse { e ->
                errors.add(
                    LevelDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "ATLAS_DESCRIPTOR_ERROR",
                        message = "Failed to resolve atlas descriptors: ${e.message}",
                    ),
                )
                return LevelValidationResult(valid = false, errors = errors, warnings = warnings)
            }

        val grid = document.grid
        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val cell = grid.cellAt(col, row)

                if (cell.baseHeight !in 0..MAX_BASE_HEIGHT) {
                    errors.add(
                        LevelDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "HEIGHT_OUT_OF_RANGE",
                            message = "Cell ($col, $row) baseHeight ${cell.baseHeight} must be in 0..$MAX_BASE_HEIGHT.",
                            col = col,
                            row = row,
                        ),
                    )
                }

                // 1. Top texture: Absent -> WARNING (MISSING_TOP_TEXTURE), Unknown -> ERROR
                when (val topLookup = table.lookup(cell.topTex)) {
                    DescriptorLookup.Absent -> {
                        warnings.add(
                            LevelDiagnostic(
                                severity = DiagnosticSeverity.WARNING,
                                code = "MISSING_TOP_TEXTURE",
                                message = "Cell ($col, $row) has no assigned topTex; defaulting to descriptor slot 0.",
                                col = col,
                                row = row,
                            ),
                        )
                    }
                    is DescriptorLookup.Unknown -> {
                        errors.add(
                            LevelDiagnostic(
                                severity = DiagnosticSeverity.ERROR,
                                code = "UNKNOWN_TOP_TEXTURE",
                                message = "Cell ($col, $row) references unknown topTex '${topLookup.rawSymbol}'.",
                                col = col,
                                row = row,
                            ),
                        )
                    }
                    is DescriptorLookup.Found -> {}
                }

                // 2. Skirt texture: Absent inherits topTex cleanly; Unknown -> ERROR
                when (val skirtLookup = table.lookup(cell.skirtTex)) {
                    DescriptorLookup.Absent, is DescriptorLookup.Found -> {}
                    is DescriptorLookup.Unknown -> {
                        errors.add(
                            LevelDiagnostic(
                                severity = DiagnosticSeverity.ERROR,
                                code = "UNKNOWN_SKIRT_TEXTURE",
                                message = "Cell ($col, $row) references unknown skirtTex '${skirtLookup.rawSymbol}'.",
                                col = col,
                                row = row,
                            ),
                        )
                    }
                }

                // 3. Panel texture: Absent warns only when Edge Panels or Foliage Cross are active; Unknown -> ERROR
                val panelDesc: TextureDescriptorInfo? =
                    when (val panelLookup = table.lookup(cell.panelTex)) {
                        DescriptorLookup.Absent -> {
                            if (cell.panels.hasAny || cell.archetype == ShapeArchetype.PRIM_FOLIAGE_CROSS) {
                                warnings.add(
                                    LevelDiagnostic(
                                        severity = DiagnosticSeverity.WARNING,
                                        code = "MISSING_PANEL_TEXTURE",
                                        message = "Cell ($col, $row) uses Edge Panels or Foliage Cross without an assigned panelTex.",
                                        col = col,
                                        row = row,
                                    ),
                                )
                            }
                            null
                        }
                        is DescriptorLookup.Unknown -> {
                            errors.add(
                                LevelDiagnostic(
                                    severity = DiagnosticSeverity.ERROR,
                                    code = "UNKNOWN_PANEL_TEXTURE",
                                    message = "Cell ($col, $row) references unknown panelTex '${panelLookup.rawSymbol}'.",
                                    col = col,
                                    row = row,
                                ),
                            )
                            null
                        }
                        is DescriptorLookup.Found -> panelLookup.descriptor
                    }

                // Non-square aspect hints for 1h parapets and 16x32 foliage fins
                if (panelDesc != null) {
                    if (cell.panels.hasAny && !cell.panels.tall2h && panelDesc.height > 16) {
                        warnings.add(
                            LevelDiagnostic(
                                severity = DiagnosticSeverity.WARNING,
                                code = "PANEL_1H_CROPPED_TEXTURE",
                                message =
                                    "Cell ($col, $row) uses 1h Edge Panel with ${panelDesc.width}x${panelDesc.height} " +
                                        "texture '${panelDesc.name}'; bottom ${panelDesc.height - 16}px will be cropped (32x16 slice recommended).",
                                col = col,
                                row = row,
                            ),
                        )
                    }
                    if (cell.archetype == ShapeArchetype.PRIM_FOLIAGE_CROSS && panelDesc.width > 16) {
                        warnings.add(
                            LevelDiagnostic(
                                severity = DiagnosticSeverity.WARNING,
                                code = "FOLIAGE_FIN_CROPPED_TEXTURE",
                                message =
                                    "Cell ($col, $row) uses PRIM_FOLIAGE_CROSS with ${panelDesc.width}x${panelDesc.height} " +
                                        "texture '${panelDesc.name}'; right ${panelDesc.width - 16}px will be cropped (16x32 slice recommended).",
                                col = col,
                                row = row,
                            ),
                        )
                    }
                }

                // Liquid flushness check: PRIM_LIQUID emits no vertical side walls
                if (cell.archetype.isLiquid) {
                    val waterSurface = cell.baseHeight + cell.archetype.heightSpan1h
                    val neighbors =
                        listOf(
                            Triple(col, row - 1, "North"),
                            Triple(col + 1, row, "East"),
                            Triple(col, row + 1, "South"),
                            Triple(col - 1, row, "West"),
                        )
                    for ((nc, nr, dir) in neighbors) {
                        if (!grid.isInBounds(nc, nr)) {
                            if (waterSurface > 0) {
                                warnings.add(
                                    LevelDiagnostic(
                                        severity = DiagnosticSeverity.WARNING,
                                        code = "UNFLUSH_LIQUID_SURFACE",
                                        message =
                                            "Cell ($col, $row) ${cell.archetype.name} water surface (${waterSurface}h) " +
                                                "sits on the $dir map border (0h) with no side wall.",
                                        col = col,
                                        row = row,
                                    ),
                                )
                            }
                        } else {
                            val neighbor = grid.cellAt(nc, nr)
                            val neighborMaxTop = neighborTopHeight(neighbor)
                            if (waterSurface > neighborMaxTop) {
                                warnings.add(
                                    LevelDiagnostic(
                                        severity = DiagnosticSeverity.WARNING,
                                        code = "UNFLUSH_LIQUID_SURFACE",
                                        message =
                                            "Cell ($col, $row) ${cell.archetype.name} water surface (${waterSurface}h) " +
                                                "floats above $dir neighbor ($nc, $nr) top (${neighborMaxTop}h).",
                                        col = col,
                                        row = row,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        return LevelValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
        )
    }

    private fun neighborTopHeight(cell: MapCell): Int =
        when (cell.archetype) {
            ShapeArchetype.PRIM_FLAT,
            ShapeArchetype.PRIM_PILLAR,
            ShapeArchetype.PRIM_FOLIAGE_CROSS,
            -> cell.baseHeight
            else -> cell.baseHeight + cell.archetype.heightSpan1h
        }
}
