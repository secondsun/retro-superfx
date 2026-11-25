package dev.secondsun.sfxstudio.desktop.level

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.secondsun.sfxstudio.core.level.FacingDirection
import dev.secondsun.sfxstudio.core.level.GameplayBinarySerializer
import dev.secondsun.sfxstudio.core.level.GameplayLayer
import dev.secondsun.sfxstudio.core.level.InteractableKind
import dev.secondsun.sfxstudio.core.level.IsoRenderResult
import dev.secondsun.sfxstudio.core.level.IsoSoftwareRenderer
import dev.secondsun.sfxstudio.core.level.MapGrid
import dev.secondsun.sfxstudio.core.level.NpcAiType
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioColors
import kotlin.math.hypot

internal fun DrawScope.drawTopDownGameplayOverlay(
    grid: MapGrid,
    gameplay: GameplayLayer,
    layout: GridViewportLayout,
    selectedCells: Set<Pair<Int, Int>>,
    selectedNpcIndex: Int?,
    selectedInteractableIndex: Int?,
    colors: StudioColors,
    textMeasurer: TextMeasurer,
) {
    val cellPix = layout.cellPix
    val walkGrid = GameplayBinarySerializer.buildWalkabilityGrid(grid, gameplay)
    val overridesByCoord = gameplay.walkabilityOverrides.associateBy { it.col to it.row }

    val occupiedCoords = HashSet<Pair<Int, Int>>()
    occupiedCoords.add(gameplay.playerSpawn.col to gameplay.playerSpawn.row)
    for (npc in gameplay.npcs) {
        occupiedCoords.add(npc.col to npc.row)
    }
    for (inter in gameplay.interactables) {
        occupiedCoords.add(inter.col to inter.row)
    }

    val badgeTextStyle =
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )

    // 1. Walkability & Move-Cost Layer
    for (r in 0 until grid.rows) {
        for (c in 0 until grid.cols) {
            val x = layout.originX + c * cellPix
            val y = layout.originY + r * cellPix
            val packed = walkGrid[r * grid.cols + c].toInt() and 0xFF
            val walkable = (packed and GameplayBinarySerializer.WALK_FLAG_WALKABLE) != 0
            val cost = (packed and GameplayBinarySerializer.WALK_COST_MASK) ushr GameplayBinarySerializer.WALK_COST_SHIFT
            val flags = (packed and GameplayBinarySerializer.WALK_EXTRA_MASK) ushr GameplayBinarySerializer.WALK_EXTRA_SHIFT
            val hasExplicitOverride = (c to r) in overridesByCoord

            if (!walkable) {
                clipRect(
                    left = x + 2f,
                    top = y + 2f,
                    right = x + cellPix - 2f,
                    bottom = y + cellPix - 2f,
                ) {
                    drawRect(
                        color = colors.semanticError.copy(alpha = 0.16f),
                        topLeft = Offset(x + 2f, y + 2f),
                        size = Size(cellPix - 4f, cellPix - 4f),
                    )
                    val step = (cellPix * 0.28f).coerceAtLeast(6f)
                    var d = -cellPix
                    while (d < cellPix * 2f) {
                        drawLine(
                            color = colors.semanticError.copy(alpha = 0.34f),
                            start = Offset(x + d, y + cellPix),
                            end = Offset(x + d + cellPix, y),
                            strokeWidth = 1.2f,
                        )
                        d += step
                    }
                }
                if (hasExplicitOverride && cellPix >= 16f) {
                    val bx = x + cellPix - 6f
                    val by = y + cellPix - 6f
                    drawCircle(color = colors.bgCanvas.copy(alpha = 0.85f), radius = 4.5f, center = Offset(bx, by))
                    drawLine(colors.semanticError, Offset(bx - 2.2f, by - 2.2f), Offset(bx + 2.2f, by + 2.2f), strokeWidth = 1.5f)
                    drawLine(colors.semanticError, Offset(bx + 2.2f, by - 2.2f), Offset(bx - 2.2f, by + 2.2f), strokeWidth = 1.5f)
                }
            } else if ((cost > 1 || flags > 0) && cellPix >= 20f) {
                val label = if (flags > 0 && cost <= 1) "F$flags" else cost.toString()
                val measured = textMeasurer.measure(label, style = badgeTextStyle)
                val pw = measured.size.width + 6f
                val ph = measured.size.height + 2f
                val px = x + cellPix - pw - 2.5f
                val py = y + cellPix - ph - 2.5f
                drawRoundRect(
                    color = colors.bgCanvas.copy(alpha = 0.88f),
                    topLeft = Offset(px, py),
                    size = Size(pw, ph),
                    cornerRadius = CornerRadius(3f, 3f),
                )
                drawRoundRect(
                    color = colors.aliasAmber.copy(alpha = 0.85f),
                    topLeft = Offset(px, py),
                    size = Size(pw, ph),
                    cornerRadius = CornerRadius(3f, 3f),
                    style = Stroke(width = 1f),
                )
                drawText(
                    textLayoutResult = measured,
                    color = colors.aliasAmber,
                    topLeft = Offset(px + 3f, py + 1f),
                )
            } else if (hasExplicitOverride && cellPix >= 16f) {
                drawCircle(
                    color = colors.semanticSuccess,
                    radius = 2.6f,
                    center = Offset(x + cellPix - 5.5f, y + cellPix - 5.5f),
                )
            }
        }
    }

    fun cellCenter(
        c: Int,
        r: Int,
    ): Offset = Offset(layout.originX + (c + 0.5f) * cellPix, layout.originY + (r + 0.5f) * cellPix)

    // 2. Patrol Waypoint Closed Loops & Numbered Nodes
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f)
    for ((npcIdx, npc) in gameplay.npcs.withIndex()) {
        if (npc.patrolWaypoints.isEmpty()) continue
        val isFocused = (selectedNpcIndex == npcIdx) || ((npc.col to npc.row) in selectedCells)
        val routeColor = colors.waypointGold.copy(alpha = if (isFocused) 0.96f else 0.52f)
        val strokeW = if (isFocused) 2.2f else 1.5f

        val validWps = npc.patrolWaypoints.filter { grid.isInBounds(it.col, it.row) }
        if (validWps.size >= 2) {
            for (i in validWps.indices) {
                val a = cellCenter(validWps[i].col, validWps[i].row)
                val nextWp = validWps[(i + 1) % validWps.size]
                val b = cellCenter(nextWp.col, nextWp.row)
                drawLine(
                    color = routeColor,
                    start = a,
                    end = b,
                    strokeWidth = strokeW,
                    pathEffect = dashEffect,
                    cap = StrokeCap.Round,
                )
                drawMidpointArrowhead(a, b, routeColor, sizePx = (cellPix * 0.16f).coerceIn(4f, 6.5f))
            }
        } else if (validWps.size == 1 && grid.isInBounds(npc.col, npc.row)) {
            val a = cellCenter(npc.col, npc.row)
            val b = cellCenter(validWps[0].col, validWps[0].row)
            if (hypot(b.x - a.x, b.y - a.y) > 2f) {
                drawLine(
                    color = routeColor,
                    start = a,
                    end = b,
                    strokeWidth = strokeW,
                    pathEffect = dashEffect,
                )
            }
        }

        val wpRadius = (cellPix * 0.18f).coerceIn(5.5f, 8.5f)
        for ((wpIdx, wp) in npc.patrolWaypoints.withIndex()) {
            if (!grid.isInBounds(wp.col, wp.row)) continue
            val baseCenter = cellCenter(wp.col, wp.row)
            val badgeCenter =
                if ((wp.col to wp.row) in occupiedCoords) {
                    Offset(baseCenter.x + cellPix * 0.25f, baseCenter.y - cellPix * 0.25f)
                } else {
                    baseCenter
                }
            drawCircle(
                color = colors.bgCanvas,
                radius = wpRadius + 1.4f,
                center = badgeCenter,
            )
            drawCircle(
                color = if (isFocused) colors.waypointGold else colors.waypointGold.copy(alpha = 0.8f),
                radius = wpRadius,
                center = badgeCenter,
            )
            val numLayout = textMeasurer.measure("${wpIdx + 1}", style = badgeTextStyle)
            drawText(
                textLayoutResult = numLayout,
                color = if (colors.isDark) Color(0xFF0A0C10) else Color.White,
                topLeft =
                    Offset(
                        badgeCenter.x - numLayout.size.width * 0.5f,
                        badgeCenter.y - numLayout.size.height * 0.5f,
                    ),
            )
        }
    }

    // 3. Interactable Tokens (Chest / Switch / Sign)
    for ((interIdx, inter) in gameplay.interactables.withIndex()) {
        if (!grid.isInBounds(inter.col, inter.row)) continue
        val center = cellCenter(inter.col, inter.row)
        val half = (cellPix * 0.24f).coerceIn(5.5f, 11f)
        val isSelected = (selectedInteractableIndex == interIdx) || ((inter.col to inter.row) in selectedCells)
        val tokenColor =
            when (inter.kind) {
                InteractableKind.CHEST -> colors.interactAmber
                InteractableKind.SWITCH -> colors.interactViolet
                InteractableKind.SIGN -> colors.interactEmerald
            }

        if (isSelected) {
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(center.x - half - 2.5f, center.y - half - 2.5f),
                size = Size((half + 2.5f) * 2f, (half + 2.5f) * 2f),
                cornerRadius = CornerRadius(5f, 5f),
                style = Stroke(width = 2f),
            )
        }
        drawRoundRect(
            color = colors.bgCanvas.copy(alpha = 0.9f),
            topLeft = Offset(center.x - half - 1f, center.y - half - 1f),
            size = Size((half + 1f) * 2f, (half + 1f) * 2f),
            cornerRadius = CornerRadius(4f, 4f),
        )
        drawRoundRect(
            color = tokenColor,
            topLeft = Offset(center.x - half, center.y - half),
            size = Size(half * 2f, half * 2f),
            cornerRadius = CornerRadius(3.5f, 3.5f),
        )
        // Crisp inner vector glyph
        when (inter.kind) {
            InteractableKind.CHEST -> {
                drawLine(
                    color = colors.bgCanvas,
                    start = Offset(center.x - half * 0.65f, center.y - half * 0.1f),
                    end = Offset(center.x + half * 0.65f, center.y - half * 0.1f),
                    strokeWidth = 1.5f,
                )
                drawRect(
                    color = colors.bgCanvas,
                    topLeft = Offset(center.x - 1.8f, center.y - 2.2f),
                    size = Size(3.6f, 4.4f),
                )
            }
            InteractableKind.SWITCH -> {
                drawLine(
                    color = colors.bgCanvas,
                    start = Offset(center.x - half * 0.55f, center.y + half * 0.45f),
                    end = Offset(center.x + half * 0.55f, center.y + half * 0.45f),
                    strokeWidth = 1.6f,
                )
                drawLine(
                    color = colors.bgCanvas,
                    start = Offset(center.x - half * 0.1f, center.y + half * 0.45f),
                    end = Offset(center.x + half * 0.4f, center.y - half * 0.45f),
                    strokeWidth = 1.6f,
                )
            }
            InteractableKind.SIGN -> {
                drawLine(
                    color = colors.bgCanvas,
                    start = Offset(center.x, center.y - half * 0.55f),
                    end = Offset(center.x, center.y + half * 0.6f),
                    strokeWidth = 1.5f,
                )
                drawRect(
                    color = colors.bgCanvas,
                    topLeft = Offset(center.x - half * 0.55f, center.y - half * 0.45f),
                    size = Size(half * 1.1f, half * 0.55f),
                )
            }
        }
    }

    // 4. NPC Spawn Tokens
    for ((npcIdx, npc) in gameplay.npcs.withIndex()) {
        if (!grid.isInBounds(npc.col, npc.row)) continue
        val center = cellCenter(npc.col, npc.row)
        val radius = (cellPix * 0.27f).coerceIn(6f, 12f)
        val isSelected = (selectedNpcIndex == npcIdx) || ((npc.col to npc.row) in selectedCells)

        if (isSelected) {
            drawCircle(
                color = Color.White,
                radius = radius + 3.2f,
                center = center,
                style = Stroke(width = 2.2f),
            )
        }
        drawCircle(
            color = colors.bgCanvas.copy(alpha = 0.92f),
            radius = radius + 1.2f,
            center = center,
        )
        if (npc.aiType == NpcAiType.PATROL) {
            val diamond =
                Path().apply {
                    moveTo(center.x, center.y - radius)
                    lineTo(center.x + radius, center.y)
                    lineTo(center.x, center.y + radius)
                    lineTo(center.x - radius, center.y)
                    close()
                }
            drawPath(diamond, color = colors.npcRose)
            drawCircle(color = colors.bgCanvas, radius = (radius * 0.32f).coerceAtLeast(1.8f), center = center)
        } else {
            drawCircle(color = colors.npcRose, radius = radius, center = center)
            val cross = radius * 0.42f
            drawLine(
                colors.bgCanvas,
                Offset(center.x - cross, center.y - cross),
                Offset(center.x + cross, center.y + cross),
                strokeWidth = 1.6f,
            )
            drawLine(
                colors.bgCanvas,
                Offset(center.x + cross, center.y - cross),
                Offset(center.x - cross, center.y + cross),
                strokeWidth = 1.6f,
            )
        }
    }

    // 5. Player Spawn Shield + Cardinal Facing Pointer
    val spawn = gameplay.playerSpawn
    if (grid.isInBounds(spawn.col, spawn.row)) {
        val center = cellCenter(spawn.col, spawn.row)
        val radius = (cellPix * 0.28f).coerceIn(6.5f, 12.5f)
        val isSelected = (spawn.col to spawn.row) in selectedCells && selectedNpcIndex == null && selectedInteractableIndex == null

        if (isSelected) {
            drawCircle(
                color = Color.White,
                radius = radius + 3.4f,
                center = center,
                style = Stroke(width = 2f),
            )
        }
        drawCircle(
            color = colors.bgCanvas.copy(alpha = 0.92f),
            radius = radius + 1.3f,
            center = center,
        )
        drawCircle(
            color = colors.playerSpawnCyan,
            radius = radius,
            center = center,
        )
        // Inner shield chevron
        val shield =
            Path().apply {
                moveTo(center.x, center.y - radius * 0.5f)
                lineTo(center.x + radius * 0.45f, center.y - radius * 0.15f)
                lineTo(center.x, center.y + radius * 0.52f)
                lineTo(center.x - radius * 0.45f, center.y - radius * 0.15f)
                close()
            }
        drawPath(shield, color = colors.bgCanvas)

        // Cardinal facing pointer triangle outside the shield
        val (dirX, dirY) =
            when (spawn.facing) {
                FacingDirection.NORTH -> 0f to -1f
                FacingDirection.EAST -> 1f to 0f
                FacingDirection.SOUTH -> 0f to 1f
                FacingDirection.WEST -> -1f to 0f
            }
        val perpX = -dirY
        val perpY = dirX
        val tipDist = radius + (cellPix * 0.16f).coerceIn(3.5f, 6.5f)
        val baseDist = radius + 0.8f
        val wing = (radius * 0.46f).coerceAtLeast(3f)
        val pointer =
            Path().apply {
                moveTo(center.x + dirX * tipDist, center.y + dirY * tipDist)
                lineTo(center.x + dirX * baseDist + perpX * wing, center.y + dirY * baseDist + perpY * wing)
                lineTo(center.x + dirX * baseDist - perpX * wing, center.y + dirY * baseDist - perpY * wing)
                close()
            }
        drawPath(pointer, color = colors.bgCanvas)
        drawPath(pointer, color = colors.playerSpawnCyan)
    }
}

internal fun DrawScope.drawIso3DGameplayOverlay(
    grid: MapGrid,
    gameplay: GameplayLayer,
    renderResult: IsoRenderResult,
    layout: IsoViewportLayout,
    cameraYaw: Int,
    selectedCells: Set<Pair<Int, Int>>,
    selectedNpcIndex: Int?,
    selectedInteractableIndex: Int?,
    colors: StudioColors,
    textMeasurer: TextMeasurer,
) {
    fun projectCellSurface(
        col: Int,
        row: Int,
    ): Offset? {
        if (!grid.isInBounds(col, row)) return null
        val cellIdx = row * grid.cols + col
        val poly = renderResult.cellFloorPolygons.getOrNull(cellIdx) ?: return null
        if (poly.size < 4) return null
        val cell = grid.cellAt(col, row)
        val avgX = (poly[0].x + poly[1].x + poly[2].x + poly[3].x) * 0.25f
        val slopeLift =
            when (cell.archetype) {
                ShapeArchetype.PRIM_FLAT,
                ShapeArchetype.PRIM_PILLAR,
                ShapeArchetype.PRIM_FOLIAGE_CROSS,
                -> 0f
                else -> cell.archetype.heightSpan1h * 0.45f * IsoSoftwareRenderer.STEP_1H_PIXELS
            }
        val avgY = (poly[0].y + poly[1].y + poly[2].y + poly[3].y) * 0.25f - slopeLift
        return Offset(
            x = layout.offX + avgX * layout.scale,
            y = layout.offY + avgY * layout.scale,
        )
    }

    val labelStyle =
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
        )
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
    val pinLift = (8f * (layout.scale / 2f).coerceIn(0.75f, 1.5f))

    clipRect(
        left = layout.offX.toFloat(),
        top = layout.offY.toFloat(),
        right = (layout.offX + layout.drawW).toFloat(),
        bottom = (layout.offY + layout.drawH).toFloat(),
    ) {
        // 1. 3D Patrol Waypoint Loops
        for ((npcIdx, npc) in gameplay.npcs.withIndex()) {
            if (npc.patrolWaypoints.isEmpty()) continue
            val isFocused = (selectedNpcIndex == npcIdx) || ((npc.col to npc.row) in selectedCells)
            val routeColor = colors.waypointGold.copy(alpha = if (isFocused) 0.95f else 0.55f)
            val pts = npc.patrolWaypoints.mapNotNull { wp -> projectCellSurface(wp.col, wp.row) }
            if (pts.size >= 2) {
                for (i in pts.indices) {
                    val a = pts[i]
                    val b = pts[(i + 1) % pts.size]
                    drawLine(
                        color = routeColor,
                        start = a,
                        end = b,
                        strokeWidth = if (isFocused) 2.2f else 1.5f,
                        pathEffect = dashEffect,
                        cap = StrokeCap.Round,
                    )
                    drawMidpointArrowhead(a, b, routeColor, sizePx = 5f)
                }
            }
            for ((wpIdx, wp) in npc.patrolWaypoints.withIndex()) {
                val pt = projectCellSurface(wp.col, wp.row) ?: continue
                drawCircle(colors.bgCanvas, radius = 6.8f, center = pt)
                drawCircle(colors.waypointGold, radius = 5.6f, center = pt)
                val numLayout = textMeasurer.measure("${wpIdx + 1}", style = labelStyle)
                drawText(
                    textLayoutResult = numLayout,
                    color = if (colors.isDark) Color(0xFF0A0C10) else Color.White,
                    topLeft = Offset(pt.x - numLayout.size.width * 0.5f, pt.y - numLayout.size.height * 0.5f),
                )
            }
        }

        // 2. 3D Interactable Pins
        for ((interIdx, inter) in gameplay.interactables.withIndex()) {
            val floorPt = projectCellSurface(inter.col, inter.row) ?: continue
            val headPt = Offset(floorPt.x, floorPt.y - pinLift)
            val isSelected = (selectedInteractableIndex == interIdx) || ((inter.col to inter.row) in selectedCells)
            val color =
                when (inter.kind) {
                    InteractableKind.CHEST -> colors.interactAmber
                    InteractableKind.SWITCH -> colors.interactViolet
                    InteractableKind.SIGN -> colors.interactEmerald
                }
            drawCircle(colors.bgCanvas.copy(alpha = 0.65f), radius = 3f, center = floorPt)
            drawLine(color, floorPt, headPt, strokeWidth = 1.8f)
            if (isSelected) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(headPt.x - 7.5f, headPt.y - 7.5f),
                    size = Size(15f, 15f),
                    cornerRadius = CornerRadius(4f, 4f),
                    style = Stroke(width = 1.8f),
                )
            }
            drawRoundRect(
                color = colors.bgCanvas,
                topLeft = Offset(headPt.x - 6f, headPt.y - 6f),
                size = Size(12f, 12f),
                cornerRadius = CornerRadius(3f, 3f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(headPt.x - 5f, headPt.y - 5f),
                size = Size(10f, 10f),
                cornerRadius = CornerRadius(2.5f, 2.5f),
            )
            if (isSelected) {
                drawFloatingPinTag(headPt, inter.id, color, colors, textMeasurer, labelStyle)
            }
        }

        // 3. 3D NPC Pins
        for ((npcIdx, npc) in gameplay.npcs.withIndex()) {
            val floorPt = projectCellSurface(npc.col, npc.row) ?: continue
            val headPt = Offset(floorPt.x, floorPt.y - pinLift - 2f)
            val isSelected = (selectedNpcIndex == npcIdx) || ((npc.col to npc.row) in selectedCells)

            drawCircle(colors.bgCanvas.copy(alpha = 0.65f), radius = 3.2f, center = floorPt)
            drawLine(colors.npcRose, floorPt, headPt, strokeWidth = 2f)
            if (isSelected) {
                drawCircle(Color.White, radius = 8.5f, center = headPt, style = Stroke(width = 2f))
            }
            drawCircle(colors.bgCanvas, radius = 6.8f, center = headPt)
            val diamond =
                Path().apply {
                    moveTo(headPt.x, headPt.y - 5.8f)
                    lineTo(headPt.x + 5.8f, headPt.y)
                    lineTo(headPt.x, headPt.y + 5.8f)
                    lineTo(headPt.x - 5.8f, headPt.y)
                    close()
                }
            drawPath(diamond, color = colors.npcRose)
            if (isSelected) {
                drawFloatingPinTag(headPt, npc.id, colors.npcRose, colors, textMeasurer, labelStyle)
            }
        }

        // 4. 3D Player Spawn Pin + Camera-Yaw Facing Vector
        val spawn = gameplay.playerSpawn
        val playerFloor = projectCellSurface(spawn.col, spawn.row)
        if (playerFloor != null) {
            val headPt = Offset(playerFloor.x, playerFloor.y - pinLift - 3f)
            val basis = IsoSoftwareRenderer.computeBasis(cameraYaw)
            val (rawDx, rawDy) =
                when (spawn.facing) {
                    FacingDirection.NORTH -> -basis.szX.toFloat() to -basis.szY.toFloat()
                    FacingDirection.EAST -> basis.sxX.toFloat() to basis.sxY.toFloat()
                    FacingDirection.SOUTH -> basis.szX.toFloat() to basis.szY.toFloat()
                    FacingDirection.WEST -> -basis.sxX.toFloat() to -basis.sxY.toFloat()
                }
            val len = hypot(rawDx, rawDy).coerceAtLeast(0.001f)
            val ux = rawDx / len
            val uy = rawDy / len
            val arrowEnd = Offset(playerFloor.x + ux * 14f, playerFloor.y + uy * 14f)

            drawLine(
                color = colors.playerSpawnCyan,
                start = playerFloor,
                end = arrowEnd,
                strokeWidth = 2.2f,
                cap = StrokeCap.Round,
            )
            drawMidpointArrowhead(playerFloor, arrowEnd, colors.playerSpawnCyan, sizePx = 5f, atEnd = true)

            drawCircle(colors.bgCanvas.copy(alpha = 0.7f), radius = 3.2f, center = playerFloor)
            drawLine(colors.playerSpawnCyan, playerFloor, headPt, strokeWidth = 2f)
            drawCircle(colors.bgCanvas, radius = 7.2f, center = headPt)
            drawCircle(colors.playerSpawnCyan, radius = 6f, center = headPt)
            drawCircle(colors.bgCanvas, radius = 2.2f, center = headPt)
        }
    }
}

private fun DrawScope.drawFloatingPinTag(
    headPt: Offset,
    text: String,
    accent: Color,
    colors: StudioColors,
    textMeasurer: TextMeasurer,
    style: TextStyle,
) {
    if (text.isBlank()) return
    val measured = textMeasurer.measure(text, style = style)
    val pw = measured.size.width + 10f
    val ph = measured.size.height + 4f
    val topLeft = Offset(headPt.x - pw * 0.5f, headPt.y - ph - 9f)
    drawRoundRect(
        color = colors.bgCanvas.copy(alpha = 0.92f),
        topLeft = topLeft,
        size = Size(pw, ph),
        cornerRadius = CornerRadius(4f, 4f),
    )
    drawRoundRect(
        color = accent,
        topLeft = topLeft,
        size = Size(pw, ph),
        cornerRadius = CornerRadius(4f, 4f),
        style = Stroke(width = 1.2f),
    )
    drawText(
        textLayoutResult = measured,
        color = colors.textPrimary,
        topLeft = Offset(topLeft.x + 5f, topLeft.y + 2f),
    )
}

private fun DrawScope.drawMidpointArrowhead(
    a: Offset,
    b: Offset,
    color: Color,
    sizePx: Float,
    atEnd: Boolean = false,
) {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val dist = hypot(dx, dy)
    if (dist < 6f) return
    val ux = dx / dist
    val uy = dy / dist
    val px = -uy
    val py = ux
    val tip = if (atEnd) b else Offset((a.x + b.x) * 0.5f + ux * (sizePx * 0.5f), (a.y + b.y) * 0.5f + uy * (sizePx * 0.5f))
    val base = Offset(tip.x - ux * sizePx, tip.y - uy * sizePx)
    val wing = sizePx * 0.62f
    val arrow =
        Path().apply {
            moveTo(base.x + px * wing, base.y + py * wing)
            lineTo(tip.x, tip.y)
            lineTo(base.x - px * wing, base.y - py * wing)
        }
    drawPath(
        path = arrow,
        color = color,
        style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
