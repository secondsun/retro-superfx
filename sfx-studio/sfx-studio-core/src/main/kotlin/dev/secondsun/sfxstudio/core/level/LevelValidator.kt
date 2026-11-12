package dev.secondsun.sfxstudio.core.level

import dev.secondsun.sfxstudio.core.atlas.ProjectService
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.atlas.TextureDescriptorInfo
import java.io.File

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
 * Validates [LevelDocument] invariants against its shared [TextureAtlas] and IsoEngine / Bank `$71` rules.
 */
object LevelValidator {
    const val BANK_71_MAX_ATLAS_BYTES = 32_768
    const val MAX_BASE_HEIGHT = 31

    fun validate(
        levelFile: File,
        document: LevelDocument,
        overrideAtlas: TextureAtlas? = null,
        maxAtlasBytes: Int = BANK_71_MAX_ATLAS_BYTES,
    ): LevelValidationResult {
        val atlas =
            overrideAtlas ?: run {
                if (document.atlasPath.isBlank()) {
                    return LevelValidationResult(
                        valid = false,
                        errors =
                            listOf(
                                LevelDiagnostic(
                                    severity = DiagnosticSeverity.ERROR,
                                    code = "MISSING_ATLAS_PATH",
                                    message = "Level Document '${document.name}' has an empty atlasPath.",
                                ),
                            ),
                        warnings = emptyList(),
                    )
                }
                val atlasFile = LevelDocumentService.resolveAtlasFile(levelFile, document.atlasPath)
                if (!atlasFile.exists()) {
                    return LevelValidationResult(
                        valid = false,
                        errors =
                            listOf(
                                LevelDiagnostic(
                                    severity = DiagnosticSeverity.ERROR,
                                    code = "ATLAS_FILE_NOT_FOUND",
                                    message = "Atlas file '${document.atlasPath}' resolved to '${atlasFile.path}' which does not exist.",
                                ),
                            ),
                        warnings = emptyList(),
                    )
                }
                try {
                    ProjectService.loadProject(atlasFile).first
                } catch (e: Exception) {
                    return LevelValidationResult(
                        valid = false,
                        errors =
                            listOf(
                                LevelDiagnostic(
                                    severity = DiagnosticSeverity.ERROR,
                                    code = "INVALID_ATLAS_FILE",
                                    message = "Failed to load atlas '${atlasFile.path}': ${e.message}",
                                ),
                            ),
                        warnings = emptyList(),
                    )
                }
            }

        return validate(document = document, atlas = atlas, maxAtlasBytes = maxAtlasBytes)
    }

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

        val descriptors =
            try {
                atlas.resolvedDescriptors()
            } catch (e: Exception) {
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

                // Validate symbolic texture references
                if (findDescriptor(cell.topTex, descriptors) == null && !isNullOrZeroSymbol(cell.topTex)) {
                    errors.add(
                        LevelDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "UNKNOWN_TOP_TEXTURE",
                            message = "Cell ($col, $row) references unknown topTex '${cell.topTex}'.",
                            col = col,
                            row = row,
                        ),
                    )
                }

                if (findDescriptor(cell.skirtTex, descriptors) == null && !isNullOrZeroSymbol(cell.skirtTex)) {
                    errors.add(
                        LevelDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "UNKNOWN_SKIRT_TEXTURE",
                            message = "Cell ($col, $row) references unknown skirtTex '${cell.skirtTex}'.",
                            col = col,
                            row = row,
                        ),
                    )
                }

                val panelDesc = findDescriptor(cell.panelTex, descriptors)
                if (panelDesc == null && !isNullOrZeroSymbol(cell.panelTex)) {
                    errors.add(
                        LevelDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "UNKNOWN_PANEL_TEXTURE",
                            message = "Cell ($col, $row) references unknown panelTex '${cell.panelTex}'.",
                            col = col,
                            row = row,
                        ),
                    )
                }

                // Missing panelTex when panels or foliage cross are active
                if ((cell.panels.hasAny || cell.archetype == ShapeArchetype.PRIM_FOLIAGE_CROSS) && isNullOrZeroSymbol(cell.panelTex)) {
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

    private fun isNullOrZeroSymbol(symbol: String?): Boolean =
        symbol.isNullOrBlank() ||
            symbol == "0" ||
            symbol.equals("NONE", ignoreCase = true) ||
            symbol.equals("TEX_NONE", ignoreCase = true)

    private fun findDescriptor(
        symbol: String?,
        descriptors: List<TextureDescriptorInfo>,
    ): TextureDescriptorInfo? {
        if (isNullOrZeroSymbol(symbol)) return null
        val clean = symbol!!.trim()
        descriptors.find { it.name.equals(clean, ignoreCase = true) }?.let { return it }
        val sanitized = SourceTexture.sanitizeToIdentifier(clean).removePrefix("TEXTURE_ID_").uppercase()
        return descriptors.find { SourceTexture.sanitizeToIdentifier(it.name).uppercase() == sanitized }
    }
}
