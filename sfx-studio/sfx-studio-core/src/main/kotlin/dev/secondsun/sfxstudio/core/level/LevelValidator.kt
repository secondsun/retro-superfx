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
                    is DescriptorLookup.SolidColor -> {
                        if (topLookup.paletteSlot == 0) {
                            warnings.add(
                                LevelDiagnostic(
                                    severity = DiagnosticSeverity.WARNING,
                                    code = "TRANSPARENT_SOLID_COLOR",
                                    message =
                                        "Cell ($col, $row) topTex uses transparent solid color ${topLookup.canonicalName} (\$F0); " +
                                            "SuperFX plot skips palette slot 0.",
                                    col = col,
                                    row = row,
                                ),
                            )
                        }
                    }
                    is DescriptorLookup.Found -> {}
                }

                // 2. Skirt texture: Absent inherits topTex cleanly; Unknown -> ERROR
                when (val skirtLookup = table.lookup(cell.skirtTex)) {
                    DescriptorLookup.Absent, is DescriptorLookup.Found -> {}
                    is DescriptorLookup.SolidColor -> {
                        if (skirtLookup.paletteSlot == 0) {
                            warnings.add(
                                LevelDiagnostic(
                                    severity = DiagnosticSeverity.WARNING,
                                    code = "TRANSPARENT_SOLID_COLOR",
                                    message =
                                        "Cell ($col, $row) skirtTex uses transparent solid color ${skirtLookup.canonicalName} (\$F0); " +
                                            "SuperFX plot skips palette slot 0.",
                                    col = col,
                                    row = row,
                                ),
                            )
                        }
                    }
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
                        is DescriptorLookup.SolidColor -> {
                            if (panelLookup.paletteSlot == 0 &&
                                (cell.panels.hasAny || cell.archetype == ShapeArchetype.PRIM_FOLIAGE_CROSS)
                            ) {
                                warnings.add(
                                    LevelDiagnostic(
                                        severity = DiagnosticSeverity.WARNING,
                                        code = "TRANSPARENT_SOLID_COLOR",
                                        message =
                                            "Cell ($col, $row) panelTex uses transparent solid color " +
                                                "${panelLookup.canonicalName} (\$F0); SuperFX plot skips palette slot 0.",
                                        col = col,
                                        row = row,
                                    ),
                                )
                            }
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

        document.gameplay?.let { gameplay ->
            validateGameplay(grid, gameplay, errors)
        }

        return LevelValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
        )
    }

    private fun validateGameplay(
        grid: MapGrid,
        gameplay: GameplayLayer,
        errors: MutableList<LevelDiagnostic>,
    ) {
        val seenOverrides = mutableSetOf<Pair<Int, Int>>()
        for (ov in gameplay.walkabilityOverrides) {
            if (!grid.isInBounds(ov.col, ov.row)) {
                errors.add(
                    LevelDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "GAMEPLAY_WALKABILITY_OUT_OF_BOUNDS",
                        message =
                            "Walkability override at (${ov.col}, ${ov.row}) is out of bounds (${grid.cols}x${grid.rows}).",
                        col = ov.col,
                        row = ov.row,
                    ),
                )
            } else if (!seenOverrides.add(ov.col to ov.row)) {
                errors.add(
                    LevelDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "DUPLICATE_WALKABILITY_OVERRIDE",
                        message = "Duplicate walkability override at (${ov.col}, ${ov.row}).",
                        col = ov.col,
                        row = ov.row,
                    ),
                )
            }
            if (ov.moveCost !in 0..7) {
                errors.add(
                    LevelDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "GAMEPLAY_INVALID_MOVE_COST",
                        message = "Walkability override at (${ov.col}, ${ov.row}) moveCost ${ov.moveCost} must be in 0..7.",
                        col = ov.col,
                        row = ov.row,
                    ),
                )
            }
            if (ov.flags !in 0..15) {
                errors.add(
                    LevelDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "GAMEPLAY_INVALID_WALK_FLAGS",
                        message = "Walkability override at (${ov.col}, ${ov.row}) flags ${ov.flags} must be in 0..15.",
                        col = ov.col,
                        row = ov.row,
                    ),
                )
            }
        }

        val walkGrid = GameplayBinarySerializer.buildWalkabilityGrid(grid, gameplay)

        fun isCellWalkable(
            col: Int,
            row: Int,
        ): Boolean =
            grid.isInBounds(col, row) &&
                ((walkGrid[row * grid.cols + col].toInt() and GameplayBinarySerializer.WALK_FLAG_WALKABLE) != 0)

        val spawn = gameplay.playerSpawn
        if (!grid.isInBounds(spawn.col, spawn.row)) {
            errors.add(
                LevelDiagnostic(
                    severity = DiagnosticSeverity.ERROR,
                    code = "PLAYER_SPAWN_OUT_OF_BOUNDS",
                    message = "Player spawn (${spawn.col}, ${spawn.row}) is out of bounds (${grid.cols}x${grid.rows}).",
                    col = spawn.col,
                    row = spawn.row,
                ),
            )
        } else if (!isCellWalkable(spawn.col, spawn.row)) {
            errors.add(
                LevelDiagnostic(
                    severity = DiagnosticSeverity.ERROR,
                    code = "PLAYER_SPAWN_ON_NON_WALKABLE",
                    message = "Player spawn (${spawn.col}, ${spawn.row}) sits on a non-walkable cell.",
                    col = spawn.col,
                    row = spawn.row,
                ),
            )
        }

        if (gameplay.npcs.size > 255) {
            errors.add(
                LevelDiagnostic(
                    severity = DiagnosticSeverity.ERROR,
                    code = "TOO_MANY_NPCS",
                    message = "Gameplay layer defines ${gameplay.npcs.size} NPCs (maximum 255).",
                ),
            )
        }

        val playerPos = spawn.col to spawn.row
        val seenNpcIds = mutableSetOf<String>()
        val npcPositions = mutableMapOf<Pair<Int, Int>, String>()

        for (npc in gameplay.npcs) {
            if (npc.id.isBlank()) {
                errors.add(
                    LevelDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "BLANK_NPC_ID",
                        message = "NPC at (${npc.col}, ${npc.row}) has a blank id.",
                        col = npc.col,
                        row = npc.row,
                    ),
                )
            } else if (!seenNpcIds.add(npc.id)) {
                errors.add(
                    LevelDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "DUPLICATE_NPC_ID",
                        message = "Duplicate NPC id '${npc.id}'.",
                        col = npc.col,
                        row = npc.row,
                    ),
                )
            }

            if (!grid.isInBounds(npc.col, npc.row)) {
                errors.add(
                    LevelDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "NPC_SPAWN_OUT_OF_BOUNDS",
                        message = "NPC '${npc.id}' spawn (${npc.col}, ${npc.row}) is out of bounds (${grid.cols}x${grid.rows}).",
                        col = npc.col,
                        row = npc.row,
                    ),
                )
            } else {
                if (!isCellWalkable(npc.col, npc.row)) {
                    errors.add(
                        LevelDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "NPC_SPAWN_ON_NON_WALKABLE",
                            message = "NPC '${npc.id}' spawn (${npc.col}, ${npc.row}) sits on a non-walkable cell.",
                            col = npc.col,
                            row = npc.row,
                        ),
                    )
                }
                val pos = npc.col to npc.row
                if (pos == playerPos) {
                    errors.add(
                        LevelDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "NPC_OVERLAPS_PLAYER_SPAWN",
                            message = "NPC '${npc.id}' at (${npc.col}, ${npc.row}) overlaps playerSpawn.",
                            col = npc.col,
                            row = npc.row,
                        ),
                    )
                }
                val existing = npcPositions.putIfAbsent(pos, npc.id)
                if (existing != null) {
                    errors.add(
                        LevelDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "NPC_OVERLAPS_NPC",
                            message = "NPC '${npc.id}' at (${npc.col}, ${npc.row}) overlaps NPC '$existing'.",
                            col = npc.col,
                            row = npc.row,
                        ),
                    )
                }
            }

            if (npc.patrolWaypoints.size > GameplayBinarySerializer.MAX_WAYPOINTS) {
                errors.add(
                    LevelDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "NPC_TOO_MANY_WAYPOINTS",
                        message =
                            "NPC '${npc.id}' defines ${npc.patrolWaypoints.size} patrol waypoints " +
                                "(maximum ${GameplayBinarySerializer.MAX_WAYPOINTS}).",
                        col = npc.col,
                        row = npc.row,
                    ),
                )
            }
            for ((wpIdx, wp) in npc.patrolWaypoints.withIndex()) {
                if (!grid.isInBounds(wp.col, wp.row)) {
                    errors.add(
                        LevelDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "NPC_WAYPOINT_OUT_OF_BOUNDS",
                            message =
                                "NPC '${npc.id}' waypoint #$wpIdx (${wp.col}, ${wp.row}) " +
                                    "is out of bounds (${grid.cols}x${grid.rows}).",
                            col = wp.col,
                            row = wp.row,
                        ),
                    )
                } else if (!isCellWalkable(wp.col, wp.row)) {
                    errors.add(
                        LevelDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "NPC_WAYPOINT_ON_NON_WALKABLE",
                            message =
                                "NPC '${npc.id}' waypoint #$wpIdx (${wp.col}, ${wp.row}) sits on a non-walkable cell.",
                            col = wp.col,
                            row = wp.row,
                        ),
                    )
                }
            }

            if (npc.spriteId !in 0..255 ||
                npc.aggroRange !in 0..255 ||
                npc.hp !in 1..255 ||
                npc.atk !in 0..255 ||
                npc.def !in 0..255 ||
                npc.speed !in 0..255 ||
                npc.movePoints !in 0..255
            ) {
                errors.add(
                    LevelDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "NPC_STAT_OUT_OF_RANGE",
                        message = "NPC '${npc.id}' has stat/sprite field outside valid byte range (hp: 1..255, others: 0..255).",
                        col = npc.col,
                        row = npc.row,
                    ),
                )
            }
        }

        if (gameplay.interactables.size > 255) {
            errors.add(
                LevelDiagnostic(
                    severity = DiagnosticSeverity.ERROR,
                    code = "TOO_MANY_INTERACTABLES",
                    message = "Gameplay layer defines ${gameplay.interactables.size} interactables (maximum 255).",
                ),
            )
        }

        val seenInteractableIds = mutableSetOf<String>()
        val interactablePositions = mutableMapOf<Pair<Int, Int>, String>()

        for (inter in gameplay.interactables) {
            if (inter.id.isBlank()) {
                errors.add(
                    LevelDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "BLANK_INTERACTABLE_ID",
                        message = "Interactable at (${inter.col}, ${inter.row}) has a blank id.",
                        col = inter.col,
                        row = inter.row,
                    ),
                )
            } else if (!seenInteractableIds.add(inter.id)) {
                errors.add(
                    LevelDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "DUPLICATE_INTERACTABLE_ID",
                        message = "Duplicate interactable id '${inter.id}'.",
                        col = inter.col,
                        row = inter.row,
                    ),
                )
            }

            if (!grid.isInBounds(inter.col, inter.row)) {
                errors.add(
                    LevelDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "INTERACTABLE_OUT_OF_BOUNDS",
                        message =
                            "Interactable '${inter.id}' (${inter.col}, ${inter.row}) " +
                                "is out of bounds (${grid.cols}x${grid.rows}).",
                        col = inter.col,
                        row = inter.row,
                    ),
                )
            } else {
                val pos = inter.col to inter.row
                if (pos == playerPos) {
                    errors.add(
                        LevelDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "INTERACTABLE_OVERLAPS_PLAYER_SPAWN",
                            message = "Interactable '${inter.id}' at (${inter.col}, ${inter.row}) overlaps playerSpawn.",
                            col = inter.col,
                            row = inter.row,
                        ),
                    )
                }
                val overlappingNpc = npcPositions[pos]
                if (overlappingNpc != null) {
                    errors.add(
                        LevelDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "INTERACTABLE_OVERLAPS_NPC",
                            message =
                                "Interactable '${inter.id}' at (${inter.col}, ${inter.row}) overlaps NPC '$overlappingNpc'.",
                            col = inter.col,
                            row = inter.row,
                        ),
                    )
                }
                val existing = interactablePositions.putIfAbsent(pos, inter.id)
                if (existing != null) {
                    errors.add(
                        LevelDiagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            code = "INTERACTABLE_OVERLAPS_INTERACTABLE",
                            message =
                                "Interactable '${inter.id}' at (${inter.col}, ${inter.row}) " +
                                    "overlaps interactable '$existing'.",
                            col = inter.col,
                            row = inter.row,
                        ),
                    )
                }
            }

            if (inter.closedSpriteId !in 0..255 ||
                inter.openSpriteId !in 0..255 ||
                inter.rewardValue !in 0..255 ||
                inter.messageId !in 0..255
            ) {
                errors.add(
                    LevelDiagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        code = "INTERACTABLE_FIELD_OUT_OF_RANGE",
                        message = "Interactable '${inter.id}' has sprite/reward/message field outside valid 0..255 range.",
                        col = inter.col,
                        row = inter.row,
                    ),
                )
            }
        }
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
