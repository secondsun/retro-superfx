package dev.secondsun.sfxstudio.desktop.level

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.core.atlas.DescriptorLookup
import dev.secondsun.sfxstudio.core.atlas.DescriptorTable
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.TextureDescriptorInfo
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.DiagnosticSeverity
import dev.secondsun.sfxstudio.core.level.EdgePanels
import dev.secondsun.sfxstudio.core.level.InteractableKind
import dev.secondsun.sfxstudio.core.level.LevelDiagnostic
import dev.secondsun.sfxstudio.core.level.LevelValidationResult
import dev.secondsun.sfxstudio.core.level.LevelValidator
import dev.secondsun.sfxstudio.core.level.MapGrid
import dev.secondsun.sfxstudio.core.level.NpcAiType
import dev.secondsun.sfxstudio.core.level.RewardKind
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import dev.secondsun.sfxstudio.desktop.shell.LevelEditMode
import dev.secondsun.sfxstudio.desktop.ui.TextureComposeRenderer
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.SegmentItem
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioBadge
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioKeycap
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioNumberStepper
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSectionHeader
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSegmentedControl
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioTextField
import dev.secondsun.sfxstudio.desktop.ui.icons.ArchetypeIcon
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.HairlineEdge
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioViewportClass
import dev.secondsun.sfxstudio.desktop.ui.theme.checkerboardBackground
import dev.secondsun.sfxstudio.desktop.ui.theme.drawEdgeHairline

@Composable
internal fun LevelTerrainInspectorContent(
    state: LevelEditorState,
    descriptorTable: DescriptorTable?,
    palette: SnesPalette?,
    descriptors: List<TextureDescriptorInfo>,
    validation: LevelValidationResult,
) {
    var mapGridExpanded by remember { mutableStateOf(true) }
    var cellPropsExpanded by remember { mutableStateOf(true) }
    var texSlotsExpanded by remember { mutableStateOf(true) }
    var edgePanelsExpanded by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TacticalEntitySummaryCard(state = state)

        TerrainMapGridSection(
            state = state,
            expanded = mapGridExpanded,
            onToggleExpand = { mapGridExpanded = !mapGridExpanded },
        )

        TerrainCellGeometrySection(
            state = state,
            expanded = cellPropsExpanded,
            onToggleExpand = { cellPropsExpanded = !cellPropsExpanded },
            topDivider = true,
        )

        TerrainTextureSlotsSection(
            state = state,
            descriptorTable = descriptorTable,
            palette = palette,
            descriptors = descriptors,
            expanded = texSlotsExpanded,
            onToggleExpand = { texSlotsExpanded = !texSlotsExpanded },
            topDivider = true,
        )

        TerrainEdgePanelsSection(
            state = state,
            expanded = edgePanelsExpanded,
            onToggleExpand = { edgePanelsExpanded = !edgePanelsExpanded },
            topDivider = true,
        )

        TerrainCellDiagnosticsSection(
            state = state,
            validation = validation,
            topDivider = true,
        )
    }
}

/**
 * Out-of-the-box Tactical Entity summary card displayed at the top of the Terrain Inspector
 * whenever the selected cell `(c, r)` hosts an NPC, PlayerSpawn, Interactable, or WalkabilityOverride.
 */
@Composable
internal fun TacticalEntitySummaryCard(state: LevelEditorState) {
    val gameplay = state.document.gameplay ?: return
    if (state.editMode != LevelEditMode.TERRAIN) return
    val primary = state.selectedCells.firstOrNull() ?: return
    val (col, row) = primary

    val npc = gameplay.npcs.firstOrNull { it.col == col && it.row == row }
    val inter = gameplay.interactables.firstOrNull { it.col == col && it.row == row }
    val isPlayer = gameplay.playerSpawn.col == col && gameplay.playerSpawn.row == row
    val walkOv = gameplay.walkabilityOverrides.firstOrNull { it.col == col && it.row == row }

    if (npc == null && inter == null && !isPlayer && walkOv == null) return

    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val shape = RoundedCornerShape(StudioTheme.metrics.radiusMD)

    val accentColor =
        when {
            npc != null -> colors.npcRose
            isPlayer -> colors.playerSpawnCyan
            inter != null ->
                when (inter.kind) {
                    InteractableKind.CHEST -> colors.interactAmber
                    InteractableKind.SWITCH -> colors.interactViolet
                    InteractableKind.SIGN -> colors.interactEmerald
                }
            walkOv?.walkable == false -> colors.semanticError
            else -> colors.aliasAmber
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surface2, shape)
                .border(1.dp, accentColor.copy(alpha = 0.45f), shape)
                .drawEdgeHairline(HairlineEdge.LEFT, accentColor, strokeDp = 3.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                val headerIcon =
                    when {
                        npc != null ->
                            if (npc.aiType == NpcAiType.PATROL) {
                                StudioIcons.EntityNpcPatrol
                            } else {
                                StudioIcons.EntityNpcGuard
                            }
                        isPlayer -> StudioIcons.EntityPlayer
                        inter != null ->
                            when (inter.kind) {
                                InteractableKind.CHEST -> StudioIcons.EntityChest
                                InteractableKind.SWITCH -> StudioIcons.EntitySwitch
                                InteractableKind.SIGN -> StudioIcons.EntitySign
                            }
                        walkOv?.walkable == false -> StudioIcons.WalkableBlocked
                        else -> StudioIcons.WalkableCheck
                    }
                Icon(
                    imageVector = headerIcon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "Tactical @ ($col, $row)",
                    style = typography.sectionHeader,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StudioButton(
                label = "Edit Gameplay",
                variant = StudioButtonVariant.Secondary,
                compact = true,
                icon = StudioIcons.ArrowRight,
                onClick = { state.editMode = LevelEditMode.GAMEPLAY },
            )
        }

        if (npc != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = npc.id,
                    style = typography.monoBody,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StudioBadge(
                    text = npc.aiType.name,
                    textColor = colors.npcRose,
                    backgroundColor = colors.npcRose.copy(alpha = 0.18f),
                )
            }
            Text(
                text = "HP ${npc.hp}  ATK ${npc.atk}  DEF ${npc.def}  SPD ${npc.speed}  MOV ${npc.movePoints}",
                style = typography.monoCaption,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (npc.patrolWaypoints.isNotEmpty()) {
                val chain = npc.patrolWaypoints.joinToString(" -> ") { "(${it.col},${it.row})" }
                Text(
                    text = "Patrol (${npc.patrolWaypoints.size}): $chain",
                    style = typography.monoCaption,
                    color = colors.waypointGold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isPlayer) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Player Spawn",
                    style = typography.bodyMedium,
                    color = colors.textPrimary,
                )
                StudioBadge(
                    text = "FACING ${gameplay.playerSpawn.facing.name}",
                    textColor = colors.playerSpawnCyan,
                    backgroundColor = colors.playerSpawnCyan.copy(alpha = 0.18f),
                )
            }
        }

        if (inter != null) {
            val rewardSummary =
                when (inter.rewardKind) {
                    RewardKind.NONE -> "Msg #${inter.messageId}"
                    RewardKind.ATK_BUFF -> "ATK +${inter.rewardValue}"
                    RewardKind.HP_HEAL -> "HP +${inter.rewardValue}"
                }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = inter.id,
                    style = typography.monoBody,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StudioBadge(
                    text = "${inter.kind.name} · $rewardSummary",
                    textColor = accentColor,
                    backgroundColor = accentColor.copy(alpha = 0.18f),
                )
            }
        }

        if (walkOv != null && npc == null && !isPlayer && inter == null) {
            Text(
                text =
                    if (walkOv.walkable) {
                        "Walkability Override: Walkable (Cost ${walkOv.moveCost}, Flags ${walkOv.flags})"
                    } else {
                        "Walkability Override: Blocked (Flags ${walkOv.flags})"
                    },
                style = typography.caption,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
internal fun TerrainMapGridSection(
    state: LevelEditorState,
    expanded: Boolean = true,
    onToggleExpand: (() -> Unit)? = null,
    topDivider: Boolean = false,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val grid = state.document.grid

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StudioSectionHeader(
            title = "Map Grid",
            badge = "${grid.cols}x${grid.rows} (${grid.byteSize}B)",
            collapsible = onToggleExpand != null,
            expanded = expanded,
            onToggleExpand = onToggleExpand,
            topDivider = topDivider,
        )
        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Columns", style = typography.caption, color = colors.textSecondary)
                    StudioNumberStepper(
                        value = grid.cols,
                        onValueChange = { state.resizeGrid(it, grid.rows) },
                        range = MapGrid.MIN_DIM..MapGrid.MAX_DIM,
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Rows", style = typography.caption, color = colors.textSecondary)
                    StudioNumberStepper(
                        value = grid.rows,
                        onValueChange = { state.resizeGrid(grid.cols, it) },
                        range = MapGrid.MIN_DIM..MapGrid.MAX_DIM,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TerrainCellGeometrySection(
    state: LevelEditorState,
    expanded: Boolean = true,
    onToggleExpand: (() -> Unit)? = null,
    topDivider: Boolean = false,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val primaryCoord = state.selectedCells.firstOrNull()
    val inspectedCell = state.primarySelectedCell() ?: state.brushCell
    var archetypeMenuExpanded by remember { mutableStateOf(false) }

    val selHeader =
        when {
            state.selectedCells.size > 1 -> "${state.selectedCells.size} Cells Selected"
            primaryCoord != null -> "Cell (${primaryCoord.first}, ${primaryCoord.second})"
            else -> "Brush Defaults"
        }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioSectionHeader(
            title = selHeader,
            collapsible = onToggleExpand != null,
            expanded = expanded,
            onToggleExpand = onToggleExpand,
            topDivider = topDivider,
        )

        if (expanded) {
            // 1. Elevation Stepper + Separate [ / ] Keycaps
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("Elevation", style = typography.caption, color = colors.textSecondary)
                    Text(
                        text = "${inspectedCell.baseHeight}h (${inspectedCell.baseHeight * 16}wu)",
                        style = typography.monoCaption,
                        color = colors.textPrimary,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StudioKeycap("[")
                    StudioNumberStepper(
                        value = inspectedCell.baseHeight,
                        onValueChange = { next ->
                            state.stepSelectedHeight(next - inspectedCell.baseHeight)
                        },
                        range = 0..LevelValidator.MAX_BASE_HEIGHT,
                        suffix = "h",
                    )
                    StudioKeycap("]")
                }
            }

            // 2. Shape Archetype Selector with Isometric Wireframe Icons
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Shape Archetype", style = typography.caption, color = colors.textSecondary)
                Box(modifier = Modifier.fillMaxWidth()) {
                    val triggerInteraction = remember { MutableInteractionSource() }
                    val triggerHovered by triggerInteraction.collectIsHoveredAsState()
                    val triggerShape = RoundedCornerShape(metrics.radiusSM)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(metrics.controlHeight)
                                .clip(triggerShape)
                                .background(colors.surface0, triggerShape)
                                .border(
                                    1.dp,
                                    if (archetypeMenuExpanded || triggerHovered) colors.borderStrong else colors.borderSubtle,
                                    triggerShape,
                                ).hoverable(triggerInteraction)
                                .clickable(
                                    interactionSource = triggerInteraction,
                                    indication = null,
                                ) { archetypeMenuExpanded = true }
                                .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            ArchetypeIcon(archetype = inspectedCell.archetype)
                            Text(
                                text = "${inspectedCell.archetype.id}. ${inspectedCell.archetype.displayName}",
                                style = typography.bodyRegular,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = StudioIcons.ChevronDown,
                            contentDescription = "Select archetype",
                            tint = colors.textMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    }

                    DropdownMenu(
                        expanded = archetypeMenuExpanded,
                        onDismissRequest = { archetypeMenuExpanded = false },
                        modifier =
                            Modifier
                                .width(256.dp)
                                .heightIn(max = 340.dp)
                                .background(colors.surface2),
                    ) {
                        for (arch in ShapeArchetype.entries) {
                            val isSelected = arch == inspectedCell.archetype
                            DropdownMenuItem(
                                onClick = {
                                    state.setSelectedArchetype(arch)
                                    archetypeMenuExpanded = false
                                },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    ArchetypeIcon(
                                        archetype = arch,
                                        tint = if (isSelected) colors.accentHover else colors.textSecondary,
                                    )
                                    Text(
                                        text = "${arch.id}. ${arch.displayName}",
                                        style = if (isSelected) typography.bodyMedium else typography.bodyRegular,
                                        color = if (isSelected) colors.accentHover else colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Rotation Segmented Control + 90° CW [R] Button
            val isSymmetric = inspectedCell.archetype.isRotationallySymmetric
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Rotation", style = typography.caption, color = colors.textSecondary)
                        if (isSymmetric) {
                            StudioBadge("Symmetric")
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        StudioKeycap("R")
                        StudioButton(
                            label = "90°",
                            variant = StudioButtonVariant.Secondary,
                            compact = true,
                            icon = StudioIcons.RotateCw,
                            onClick = { state.rotateSelectedClockwise() },
                        )
                    }
                }
                StudioSegmentedControl(
                    items =
                        CellRotation.entries.map { rot ->
                            SegmentItem(
                                value = rot,
                                label = "${rot.degrees}°",
                                enabled = !isSymmetric,
                            )
                        },
                    selectedValue = inspectedCell.rotation,
                    onSelect = { state.setSelectedRotation(it) },
                    compact = true,
                )
            }
        }
    }
}

@Composable
internal fun TerrainTextureSlotsSection(
    state: LevelEditorState,
    descriptorTable: DescriptorTable?,
    palette: SnesPalette?,
    descriptors: List<TextureDescriptorInfo>,
    expanded: Boolean = true,
    onToggleExpand: (() -> Unit)? = null,
    topDivider: Boolean = false,
) {
    val inspectedCell = state.primarySelectedCell() ?: state.brushCell
    val slotRows =
        listOf(
            Triple(ActiveTextureSlot.TOP, "Top", inspectedCell.topTex),
            Triple(ActiveTextureSlot.SKIRT, "Skirt", inspectedCell.skirtTex),
            Triple(ActiveTextureSlot.PANEL, "Panel", inspectedCell.panelTex),
        )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StudioSectionHeader(
            title = "Cell Texture Slots",
            badge = if (descriptors.isNotEmpty()) "${descriptors.size}" else null,
            collapsible = onToggleExpand != null,
            expanded = expanded,
            onToggleExpand = onToggleExpand,
            topDivider = topDivider,
        )

        if (expanded) {
            for ((slot, label, texName) in slotRows) {
                val isSlotExpanded = slot in state.expandedTextureSlots
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InspectorTextureSlotRow(
                        slot = slot,
                        label = label,
                        texName = texName,
                        expanded = isSlotExpanded,
                        palette = palette,
                        descriptorTable = descriptorTable,
                        onClick = { state.toggleTextureSlotExpanded(slot) },
                    )
                    if (isSlotExpanded) {
                        InlineTextureSlotPicker(
                            slot = slot,
                            selectedTexName = texName,
                            state = state,
                            descriptorTable = descriptorTable,
                            palette = palette,
                            descriptors = descriptors,
                        )
                    }
                }
            }

            if (descriptors.isNotEmpty() && state.selectedCells.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                StudioButton(
                    label = "Stamp Brush onto Selection",
                    variant = StudioButtonVariant.Secondary,
                    compact = true,
                    icon = StudioIcons.ToolBrush,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { state.applyBrushToSelection() },
                )
            }
        }
    }
}

@Composable
private fun InspectorTextureSlotRow(
    slot: ActiveTextureSlot,
    label: String,
    texName: String?,
    expanded: Boolean,
    palette: SnesPalette?,
    descriptorTable: DescriptorTable?,
    onClick: () -> Unit,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val interactionSource = remember(slot) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val lookup = descriptorTable?.lookup(texName)
    val isUnknown = lookup is DescriptorLookup.Unknown
    val foundDesc = (lookup as? DescriptorLookup.Found)?.descriptor
    val solidSlot =
        (lookup as? DescriptorLookup.SolidColor)?.paletteSlot
            ?: texName?.let { DescriptorTable.parseSolidColorSlot(it) }

    val shape = RoundedCornerShape(metrics.radiusSM)
    val bg =
        when {
            isUnknown -> colors.semanticErrorSubtle
            expanded -> colors.surface3
            hovered -> colors.surface3.copy(alpha = 0.75f)
            else -> colors.surface2
        }
    val borderCol =
        when {
            isUnknown -> colors.semanticError
            expanded -> colors.accentBorder
            else -> null
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = metrics.controlHeight)
                .clip(shape)
                .background(bg, shape)
                .let { mod ->
                    if (borderCol != null) mod.border(1.dp, borderCol, shape) else mod
                }.hoverable(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) StudioIcons.ChevronDown else StudioIcons.ChevronRight,
                contentDescription = null,
                tint = if (expanded) colors.accentHover else colors.textMuted,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = label,
                style = typography.bodyMedium,
                color = colors.textSecondary,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
        ) {
            when {
                isUnknown -> {
                    Icon(
                        imageVector = StudioIcons.StatusError,
                        contentDescription = "Missing descriptor",
                        tint = colors.semanticError,
                        modifier = Modifier.size(15.dp),
                    )
                }
                foundDesc != null && palette != null -> {
                    val bmp =
                        remember(descriptorTable, palette, foundDesc) {
                            TextureComposeRenderer.renderToImageBitmap(
                                descriptorTable.extractPreviewTexture(foundDesc),
                                palette,
                            )
                        }
                    Image(
                        bitmap = bmp,
                        contentDescription = foundDesc.name,
                        filterQuality = FilterQuality.None,
                        modifier =
                            Modifier
                                .size(20.dp)
                                .checkerboardBackground(cellSize = 3.dp)
                                .border(1.dp, colors.borderSubtle),
                    )
                }
                solidSlot != null && palette != null -> {
                    Box(
                        modifier =
                            Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .let { mod ->
                                    if (solidSlot == 0) {
                                        mod.checkerboardBackground(cellSize = 3.dp)
                                    } else {
                                        mod.background(Color(palette[solidSlot].argb))
                                    }
                                }.border(1.dp, colors.swatchInnerRim, RoundedCornerShape(3.dp)),
                    )
                }
            }

            val displayText =
                when {
                    !texName.isNullOrBlank() -> texName
                    slot == ActiveTextureSlot.SKIRT -> "(inherit top)"
                    else -> "(none)"
                }
            val textColor =
                when {
                    isUnknown -> colors.semanticErrorText
                    solidSlot != null -> colors.aliasAmber
                    !texName.isNullOrBlank() -> colors.textPrimary
                    else -> colors.textMuted
                }
            Text(
                text = displayText,
                style = typography.monoCaption,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InlineTextureSlotPicker(
    slot: ActiveTextureSlot,
    selectedTexName: String?,
    state: LevelEditorState,
    descriptorTable: DescriptorTable?,
    palette: SnesPalette?,
    descriptors: List<TextureDescriptorInfo>,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    var filterQuery by remember(slot) { mutableStateOf("") }

    val activeLookup = descriptorTable?.lookup(selectedTexName)
    val selectedIndex = (activeLookup as? DescriptorLookup.Found)?.index
    val selectedSolidSlot =
        (activeLookup as? DescriptorLookup.SolidColor)?.paletteSlot
            ?: selectedTexName?.let { DescriptorTable.parseSolidColorSlot(it) }

    val cardShape = RoundedCornerShape(metrics.radiusSM)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(colors.surface0, cardShape)
                .border(1.dp, colors.borderSubtle, cardShape)
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (slot != ActiveTextureSlot.TOP) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                StudioButton(
                    label = if (slot == ActiveTextureSlot.SKIRT) "Inherit Top" else "Clear (none)",
                    variant = StudioButtonVariant.Ghost,
                    compact = true,
                    icon = StudioIcons.Close,
                    onClick = { state.assignTextureToSlot(slot, null) },
                )
            }
        }

        if (palette != null) {
            Text(
                text = "SOLID COLORS (\$F0-\$FF)",
                style = typography.sectionHeader,
                color = colors.textMuted,
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (rowIdx in 0..1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        for (colIdx in 0..7) {
                            val paletteSlot = rowIdx * 8 + colIdx
                            val isSolidSelected = selectedSolidSlot == paletteSlot
                            val swatchColor =
                                if (paletteSlot == 0) Color.Transparent else Color(palette[paletteSlot].argb)
                            val label = String.format("%X", paletteSlot)
                            val luma =
                                swatchColor.red * 0.299f +
                                    swatchColor.green * 0.587f +
                                    swatchColor.blue * 0.114f
                            val swShape = RoundedCornerShape(3.dp)
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .height(if (metrics.touchComfort) 28.dp else 22.dp)
                                        .clip(swShape)
                                        .let { mod ->
                                            if (paletteSlot == 0) {
                                                mod.checkerboardBackground(cellSize = 3.dp)
                                            } else {
                                                mod.background(swatchColor)
                                            }
                                        }.border(
                                            width = if (isSolidSelected) 2.dp else 1.dp,
                                            color = if (isSolidSelected) colors.accentHover else colors.swatchInnerRim,
                                            shape = swShape,
                                        ).clickable {
                                            state.assignTextureToSlot(
                                                slot,
                                                DescriptorTable.solidColorSymbol(paletteSlot),
                                            )
                                        },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    style = typography.microBadge,
                                    color = if (paletteSlot == 0 || luma < 0.55f) Color.White else Color.Black,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (descriptorTable == null || palette == null || descriptors.isEmpty()) {
            Text(
                text = "Link an Atlas Document (.sfxproj) to browse textures.",
                style = typography.caption,
                color = colors.textSecondary,
            )
        } else {
            if (descriptors.size > 6) {
                StudioTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    placeholder = "Filter descriptors...",
                    monospace = true,
                    leadingIcon = StudioIcons.Search,
                )
            }

            val trimmedFilter = filterQuery.trim().lowercase()
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for ((descIdx, desc) in descriptors.withIndex()) {
                    if (trimmedFilter.isNotEmpty() && !desc.name.lowercase().contains(trimmedFilter)) {
                        continue
                    }
                    val isSelected = descIdx == selectedIndex
                    val previewBmp =
                        remember(descriptorTable, palette, desc) {
                            TextureComposeRenderer.renderToImageBitmap(
                                descriptorTable.extractPreviewTexture(desc),
                                palette,
                            )
                        }
                    val rowInteraction = remember(desc.name) { MutableInteractionSource() }
                    val rowHovered by rowInteraction.collectIsHoveredAsState()
                    val rowShape = RoundedCornerShape(metrics.radiusXS)
                    val rowBg =
                        when {
                            isSelected -> colors.accentSubtle
                            rowHovered -> colors.surface2
                            else -> Color.Transparent
                        }

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(rowShape)
                                .background(rowBg, rowShape)
                                .let { mod ->
                                    if (isSelected) mod.border(1.dp, colors.accentBorder, rowShape) else mod
                                }.hoverable(rowInteraction)
                                .clickable(
                                    interactionSource = rowInteraction,
                                    indication = null,
                                ) {
                                    state.assignTextureToSlot(slot, desc.name)
                                }.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Image(
                            bitmap = previewBmp,
                            contentDescription = desc.name,
                            filterQuality = FilterQuality.None,
                            modifier =
                                Modifier
                                    .size(26.dp)
                                    .checkerboardBackground(cellSize = 3.dp)
                                    .border(1.dp, colors.borderSubtle),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = desc.name,
                                style = typography.monoCaption,
                                color = if (isSelected) colors.accentHover else colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${desc.width}x${desc.height}",
                                style = typography.caption,
                                color = colors.textMuted,
                            )
                        }
                        StudioBadge(
                            text = String.format("\$%02X", descIdx),
                            monospace = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TerrainEdgePanelsSection(
    state: LevelEditorState,
    expanded: Boolean = true,
    onToggleExpand: (() -> Unit)? = null,
    topDivider: Boolean = false,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val inspectedCell = state.primarySelectedCell() ?: state.brushCell
    val panels = inspectedCell.panels
    val activeSummary =
        if (panels.hasAny) {
            panels.toEdgeNames().joinToString("+") + if (panels.tall2h) " (2h)" else " (1h)"
        } else {
            null
        }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioSectionHeader(
            title = "Edge Panels",
            badge = activeSummary,
            collapsible = onToggleExpand != null,
            expanded = expanded,
            onToggleExpand = onToggleExpand,
            topDivider = topDivider,
            trailing = {
                if (panels.hasAny) {
                    StudioButton(
                        label = "Clear",
                        variant = StudioButtonVariant.Ghost,
                        compact = true,
                        onClick = { state.updateSelectedPanels { EdgePanels() } },
                    )
                }
            },
        )

        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EdgePanelsCompassWidget(
                    panels = panels,
                    onToggleNorth = { state.updateSelectedPanels { it.copy(north = !it.north) } },
                    onToggleEast = { state.updateSelectedPanels { it.copy(east = !it.east) } },
                    onToggleSouth = { state.updateSelectedPanels { it.copy(south = !it.south) } },
                    onToggleWest = { state.updateSelectedPanels { it.copy(west = !it.west) } },
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        EdgeTogglePill("N", panels.north, panels.tall2h, Modifier.weight(1f)) {
                            state.updateSelectedPanels { it.copy(north = !it.north) }
                        }
                        EdgeTogglePill("E", panels.east, panels.tall2h, Modifier.weight(1f)) {
                            state.updateSelectedPanels { it.copy(east = !it.east) }
                        }
                        EdgeTogglePill("S", panels.south, panels.tall2h, Modifier.weight(1f)) {
                            state.updateSelectedPanels { it.copy(south = !it.south) }
                        }
                        EdgeTogglePill("W", panels.west, panels.tall2h, Modifier.weight(1f)) {
                            state.updateSelectedPanels { it.copy(west = !it.west) }
                        }
                    }

                    val tallShape = RoundedCornerShape(StudioTheme.metrics.radiusSM)
                    val tallEnabled = panels.hasAny
                    val tallActive = panels.hasAny && panels.tall2h
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(StudioTheme.metrics.controlHeightSM)
                                .alpha(if (tallEnabled) 1f else 0.45f)
                                .clip(tallShape)
                                .background(
                                    if (tallActive) colors.aliasAmber.copy(alpha = 0.2f) else colors.surface2,
                                    tallShape,
                                ).let { mod ->
                                    if (tallActive) {
                                        mod.border(1.dp, colors.aliasAmber.copy(alpha = 0.7f), tallShape)
                                    } else {
                                        mod
                                    }
                                }.let { mod ->
                                    if (tallEnabled) {
                                        mod.clickable { state.updateSelectedPanels { it.copy(tall2h = !it.tall2h) } }
                                    } else {
                                        mod
                                    }
                                }.padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Tall 2h (32px)",
                            style = typography.caption,
                            color = if (tallActive) colors.aliasAmber else colors.textSecondary,
                        )
                        Text(
                            text = if (tallActive) "2H" else "1H",
                            style = typography.monoCaption,
                            color = if (tallActive) colors.aliasAmber else colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EdgeTogglePill(
    label: String,
    active: Boolean,
    tall2h: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val accent = if (tall2h) colors.aliasAmber else colors.sliceCyan
    val shape = RoundedCornerShape(metrics.radiusXS)
    Box(
        modifier =
            modifier
                .height(metrics.controlHeightSM)
                .clip(shape)
                .background(if (active) accent.copy(alpha = 0.2f) else colors.surface2, shape)
                .let { mod ->
                    if (active) mod.border(1.dp, accent.copy(alpha = 0.8f), shape) else mod
                }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = typography.monoCaption,
            color = if (active) accent else colors.textSecondary,
        )
    }
}

@Composable
private fun EdgePanelsCompassWidget(
    panels: EdgePanels,
    onToggleNorth: () -> Unit,
    onToggleEast: () -> Unit,
    onToggleSouth: () -> Unit,
    onToggleWest: () -> Unit,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val activeCol = if (panels.tall2h) colors.aliasAmber else colors.sliceCyan
    val boxShape = RoundedCornerShape(8.dp)

    Box(
        modifier =
            Modifier
                .size(74.dp)
                .clip(boxShape)
                .background(colors.surface0, boxShape)
                .border(1.dp, colors.borderSubtle, boxShape)
                .padding(5.dp),
    ) {
        // Center tile square
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.surface1)
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (panels.hasAny) (if (panels.tall2h) "2h" else "1h") else "--",
                style = typography.microBadge,
                color = if (panels.hasAny) activeCol else colors.textMuted,
            )
        }

        // North edge bar
        CompassEdgeBar(
            label = "N",
            active = panels.north,
            activeColor = activeCol,
            onClick = onToggleNorth,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .width(42.dp)
                    .height(12.dp),
        )

        // South edge bar
        CompassEdgeBar(
            label = "S",
            active = panels.south,
            activeColor = activeCol,
            onClick = onToggleSouth,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(42.dp)
                    .height(12.dp),
        )

        // West edge bar
        CompassEdgeBar(
            label = "W",
            active = panels.west,
            activeColor = activeCol,
            onClick = onToggleWest,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .width(12.dp)
                    .height(42.dp),
        )

        // East edge bar
        CompassEdgeBar(
            label = "E",
            active = panels.east,
            activeColor = activeCol,
            onClick = onToggleEast,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(12.dp)
                    .height(42.dp),
        )
    }
}

@Composable
private fun CompassEdgeBar(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val interactionSource = remember(label) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(3.dp)
    val bg =
        when {
            active -> activeColor
            hovered -> colors.surface3
            else -> colors.surface2
        }
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(bg, shape)
                .hoverable(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = typography.microBadge,
            color = if (active) colors.bgCanvas else colors.textSecondary,
        )
    }
}

@Composable
internal fun TerrainCellDiagnosticsSection(
    state: LevelEditorState,
    validation: LevelValidationResult,
    showAllWhenNoCellMatch: Boolean = false,
    topDivider: Boolean = false,
) {
    val primaryCoord = state.selectedCells.firstOrNull()
    val cellDiags =
        if (primaryCoord != null) {
            validation.allDiagnostics.filter { it.col == primaryCoord.first && it.row == primaryCoord.second }
        } else {
            emptyList()
        }
    val diagsToRender =
        if (cellDiags.isNotEmpty()) {
            cellDiags
        } else if (showAllWhenNoCellMatch) {
            validation.allDiagnostics
        } else {
            emptyList()
        }

    if (diagsToRender.isEmpty()) return

    val headerTitle =
        if (cellDiags.isNotEmpty() && primaryCoord != null) {
            "Cell (${primaryCoord.first}, ${primaryCoord.second}) Diagnostics"
        } else {
            "Level Diagnostics"
        }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StudioSectionHeader(
            title = headerTitle,
            badge = diagsToRender.size.toString(),
            topDivider = topDivider,
        )
        for (diag in diagsToRender) {
            DiagnosticActionCard(state = state, diagnostic = diag)
        }
    }
}

@Composable
internal fun DiagnosticActionCard(
    state: LevelEditorState,
    diagnostic: LevelDiagnostic,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val viewport = StudioTheme.viewport
    val isError = diagnostic.severity == DiagnosticSeverity.ERROR
    val accent = if (isError) colors.semanticError else colors.semanticWarning
    val bg = if (isError) colors.semanticErrorSubtle else colors.semanticWarningSubtle
    val textCol = if (isError) colors.semanticErrorText else colors.semanticWarningText
    val shape = RoundedCornerShape(StudioTheme.metrics.radiusSM)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bg, shape)
                .drawEdgeHairline(HairlineEdge.LEFT, accent, strokeDp = 3.dp)
                .let { mod ->
                    val dc = diagnostic.col
                    val dr = diagnostic.row
                    if (dc != null && dr != null) {
                        mod.clickable { state.selectSingleCell(dc, dr) }
                    } else {
                        mod
                    }
                }.padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (isError) StudioIcons.StatusError else StudioIcons.StatusWarn,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            StudioBadge(
                text = diagnostic.code,
                textColor = textCol,
                backgroundColor = accent.copy(alpha = 0.2f),
                monospace = true,
            )
        }
        Text(
            text = diagnostic.message,
            style = typography.caption,
            color = textCol,
        )

        // Contextual 1-Click Quick-Fix Actions
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (diagnostic.code) {
                "UNKNOWN_TOP_TEXTURE", "MISSING_TOP_TEXTURE" -> {
                    StudioButton(
                        label = "Pick Top Texture",
                        variant = StudioButtonVariant.Secondary,
                        compact = true,
                        onClick = {
                            if (diagnostic.col != null && diagnostic.row != null) {
                                state.selectSingleCell(diagnostic.col!!, diagnostic.row!!)
                            }
                            state.editMode = LevelEditMode.TERRAIN
                            state.expandTextureSlot(ActiveTextureSlot.TOP)
                            if (viewport.viewportClass == StudioViewportClass.COMPACT) {
                                state.compactInspectorTab = CompactInspectorTab.TEXTURES
                            }
                        },
                    )
                }
                "UNKNOWN_SKIRT_TEXTURE" -> {
                    StudioButton(
                        label = "Pick Skirt Texture",
                        variant = StudioButtonVariant.Secondary,
                        compact = true,
                        onClick = {
                            if (diagnostic.col != null && diagnostic.row != null) {
                                state.selectSingleCell(diagnostic.col!!, diagnostic.row!!)
                            }
                            state.editMode = LevelEditMode.TERRAIN
                            state.expandTextureSlot(ActiveTextureSlot.SKIRT)
                            if (viewport.viewportClass == StudioViewportClass.COMPACT) {
                                state.compactInspectorTab = CompactInspectorTab.TEXTURES
                            }
                        },
                    )
                    StudioButton(
                        label = "Inherit Top",
                        variant = StudioButtonVariant.Ghost,
                        compact = true,
                        onClick = {
                            if (diagnostic.col != null && diagnostic.row != null) {
                                state.selectSingleCell(diagnostic.col!!, diagnostic.row!!)
                            }
                            state.assignTextureToSlot(ActiveTextureSlot.SKIRT, null)
                        },
                    )
                }
                "MISSING_PANEL_TEXTURE", "UNKNOWN_PANEL_TEXTURE" -> {
                    StudioButton(
                        label = "Pick Panel Texture",
                        variant = StudioButtonVariant.Secondary,
                        compact = true,
                        onClick = {
                            if (diagnostic.col != null && diagnostic.row != null) {
                                state.selectSingleCell(diagnostic.col!!, diagnostic.row!!)
                            }
                            state.editMode = LevelEditMode.TERRAIN
                            state.expandTextureSlot(ActiveTextureSlot.PANEL)
                            if (viewport.viewportClass == StudioViewportClass.COMPACT) {
                                state.compactInspectorTab = CompactInspectorTab.TEXTURES
                            }
                        },
                    )
                    StudioButton(
                        label = "Clear Panels",
                        variant = StudioButtonVariant.Ghost,
                        compact = true,
                        onClick = {
                            if (diagnostic.col != null && diagnostic.row != null) {
                                state.selectSingleCell(diagnostic.col!!, diagnostic.row!!)
                            }
                            state.updateSelectedPanels { EdgePanels() }
                        },
                    )
                }
                "MISSING_ATLAS_PATH", "ATLAS_NOT_FOUND" -> {
                    StudioButton(
                        label = "Link Atlas...",
                        variant = StudioButtonVariant.Secondary,
                        compact = true,
                        icon = StudioIcons.DocAtlas,
                        onClick = { state.promptLinkAtlas() },
                    )
                }
            }
        }
    }
}
