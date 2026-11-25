package dev.secondsun.sfxstudio.desktop.level

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.core.level.FacingDirection
import dev.secondsun.sfxstudio.core.level.GameplayBinarySerializer
import dev.secondsun.sfxstudio.core.level.GameplayLayer
import dev.secondsun.sfxstudio.core.level.InteractableKind
import dev.secondsun.sfxstudio.core.level.InteractableSpawn
import dev.secondsun.sfxstudio.core.level.LevelValidationResult
import dev.secondsun.sfxstudio.core.level.NpcAiType
import dev.secondsun.sfxstudio.core.level.NpcSpawn
import dev.secondsun.sfxstudio.core.level.PlayerSpawn
import dev.secondsun.sfxstudio.core.level.RewardKind
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.SegmentItem
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioBadge
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioIconButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioNumberStepper
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSectionHeader
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSegmentedControl
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioTextField
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioViewportClass

private enum class CellWalkChoice {
    DEFAULT,
    WALKABLE,
    BLOCKED,
}

@Composable
internal fun LevelGameplayInspectorContent(
    state: LevelEditorState,
    validation: LevelValidationResult,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val gameplay = state.document.gameplay

    if (gameplay == null) {
        val cardShape = RoundedCornerShape(StudioTheme.metrics.radiusMD)
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(colors.surface2, cardShape)
                    .border(1.dp, colors.borderSubtle, cardShape)
                    .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.playerSpawnCyan.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = StudioIcons.EntityPlayer,
                        contentDescription = null,
                        tint = colors.playerSpawnCyan,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Column {
                    Text(
                        text = "No Gameplay Layer (.gmap)",
                        style = typography.bodyMedium,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "S-CPU Tactical RPG Companion Data",
                        style = typography.caption,
                        color = colors.textMuted,
                    )
                }
            }
            Text(
                text =
                    "Enable a companion Gameplay Layer to author cell walkability overrides, movement costs, " +
                        "Player Spawn, Guard/Patrol NPCs with up to 4 waypoints, and Chest/Switch/Sign objects.",
                style = typography.caption,
                color = colors.textSecondary,
            )
            StudioButton(
                label = "Enable Gameplay Layer",
                variant = StudioButtonVariant.Primary,
                icon = StudioIcons.Plus,
                modifier = Modifier.fillMaxWidth(),
                onClick = { state.enableGameplayLayer() },
            )
        }
        return
    }

    var rosterExpanded by remember { mutableStateOf(true) }
    var walkExpanded by remember { mutableStateOf(true) }
    var confirmRemoveLayer by remember { mutableStateOf(false) }
    val isWideBottomSheet = StudioTheme.viewport.viewportClass == StudioViewportClass.COMPACT

    @Composable
    fun SelectedEntitySection(topDivider: Boolean) {
        val selNpcIdx = state.selectedNpcIndex
        val selInterIdx = state.selectedInteractableIndex
        when {
            selNpcIdx != null && selNpcIdx in gameplay.npcs.indices -> {
                NpcSpawnEditorSection(
                    state = state,
                    npcIndex = selNpcIdx,
                    npc = gameplay.npcs[selNpcIdx],
                    topDivider = topDivider,
                )
            }
            selInterIdx != null && selInterIdx in gameplay.interactables.indices -> {
                InteractableSpawnEditorSection(
                    state = state,
                    interactableIndex = selInterIdx,
                    inter = gameplay.interactables[selInterIdx],
                    topDivider = topDivider,
                )
            }
            else -> {
                PlayerSpawnEditorSection(
                    state = state,
                    spawn = gameplay.playerSpawn,
                    topDivider = topDivider,
                )
            }
        }
    }

    @Composable
    fun RemoveLayerFooter() {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!confirmRemoveLayer) {
                StudioButton(
                    label = "Remove Gameplay Layer...",
                    variant = StudioButtonVariant.DangerGhost,
                    compact = true,
                    icon = StudioIcons.Trash,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { confirmRemoveLayer = true },
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StudioButton(
                        label = "Cancel",
                        variant = StudioButtonVariant.Ghost,
                        compact = true,
                        modifier = Modifier.weight(1f),
                        onClick = { confirmRemoveLayer = false },
                    )
                    StudioButton(
                        label = "Confirm Remove .gmap",
                        variant = StudioButtonVariant.DangerSolid,
                        compact = true,
                        icon = StudioIcons.Trash,
                        modifier = Modifier.weight(1.4f),
                        onClick = {
                            confirmRemoveLayer = false
                            state.removeGameplayLayer()
                        },
                    )
                }
            }
        }
    }

    if (isWideBottomSheet) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GameplayEntityRosterSection(
                    state = state,
                    gameplay = gameplay,
                    expanded = rosterExpanded,
                    onToggleExpand = { rosterExpanded = !rosterExpanded },
                )
                GameplayCellWalkabilitySection(
                    state = state,
                    expanded = walkExpanded,
                    onToggleExpand = { walkExpanded = !walkExpanded },
                    topDivider = true,
                )
                RemoveLayerFooter()
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SelectedEntitySection(topDivider = false)
                TerrainCellDiagnosticsSection(
                    state = state,
                    validation = validation,
                    topDivider = true,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GameplayEntityRosterSection(
                state = state,
                gameplay = gameplay,
                expanded = rosterExpanded,
                onToggleExpand = { rosterExpanded = !rosterExpanded },
            )

            GameplayCellWalkabilitySection(
                state = state,
                expanded = walkExpanded,
                onToggleExpand = { walkExpanded = !walkExpanded },
                topDivider = true,
            )

            SelectedEntitySection(topDivider = true)

            TerrainCellDiagnosticsSection(
                state = state,
                validation = validation,
                topDivider = true,
            )

            RemoveLayerFooter()
        }
    }
}

@Composable
private fun GameplayEntityRosterSection(
    state: LevelEditorState,
    gameplay: GameplayLayer,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val gmapBytes = GameplayBinarySerializer.byteSize(state.document.grid, gameplay)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StudioSectionHeader(
            title = "Entity Roster",
            badge = "${gmapBytes}B .gmap",
            collapsible = true,
            expanded = expanded,
            onToggleExpand = onToggleExpand,
        )

        if (expanded) {
            val p = gameplay.playerSpawn
            val isPlayerSelected =
                state.selectedNpcIndex == null &&
                    state.selectedInteractableIndex == null &&
                    (p.col to p.row) in state.selectedCells

            RosterEntityRow(
                icon = StudioIcons.EntityPlayer,
                iconTint = colors.playerSpawnCyan,
                title = "Player Spawn",
                coordText = "(${p.col}, ${p.row})",
                badgeText = p.facing.name.take(1),
                selected = isPlayerSelected,
                onClick = { state.selectPlayerSpawn() },
            )

            // NPCs group
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "NPCs (${gameplay.npcs.size})",
                    style = typography.caption,
                    color = colors.textSecondary,
                )
                StudioButton(
                    label = "Add NPC",
                    variant = StudioButtonVariant.Secondary,
                    compact = true,
                    icon = StudioIcons.Plus,
                    onClick = {
                        val (nc, nr) = state.findNextFreeWalkableCell()
                        state.addNpc(nc, nr)
                    },
                )
            }
            for ((idx, npc) in gameplay.npcs.withIndex()) {
                val badge =
                    if (npc.patrolWaypoints.isNotEmpty()) {
                        "${npc.aiType.name} · ${npc.patrolWaypoints.size}WP"
                    } else {
                        npc.aiType.name
                    }
                RosterEntityRow(
                    icon = if (npc.aiType == NpcAiType.PATROL) StudioIcons.EntityNpcPatrol else StudioIcons.EntityNpcGuard,
                    iconTint = colors.npcRose,
                    title = npc.id,
                    coordText = "(${npc.col}, ${npc.row})",
                    badgeText = badge,
                    selected = state.selectedNpcIndex == idx,
                    onClick = { state.selectNpc(idx) },
                )
            }

            // Interactables group
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Objects (${gameplay.interactables.size})",
                    style = typography.caption,
                    color = colors.textSecondary,
                )
                StudioButton(
                    label = "Add Object",
                    variant = StudioButtonVariant.Secondary,
                    compact = true,
                    icon = StudioIcons.Plus,
                    onClick = {
                        val (nc, nr) = state.findNextFreeWalkableCell()
                        state.addInteractable(nc, nr)
                    },
                )
            }
            for ((idx, inter) in gameplay.interactables.withIndex()) {
                val (icon, tint) =
                    when (inter.kind) {
                        InteractableKind.CHEST -> StudioIcons.EntityChest to colors.interactAmber
                        InteractableKind.SWITCH -> StudioIcons.EntitySwitch to colors.interactViolet
                        InteractableKind.SIGN -> StudioIcons.EntitySign to colors.interactEmerald
                    }
                RosterEntityRow(
                    icon = icon,
                    iconTint = tint,
                    title = inter.id,
                    coordText = "(${inter.col}, ${inter.row})",
                    badgeText = inter.kind.name,
                    selected = state.selectedInteractableIndex == idx,
                    onClick = { state.selectInteractable(idx) },
                )
            }
        }
    }
}

@Composable
private fun RosterEntityRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    coordText: String,
    badgeText: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val interactionSource = remember(title, coordText) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(metrics.radiusSM)
    val bg =
        when {
            selected -> colors.accentSubtle
            hovered -> colors.surface3
            else -> colors.surface2
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = metrics.controlHeightSM)
                .clip(shape)
                .background(bg, shape)
                .let { mod ->
                    if (selected) mod.border(1.dp, colors.accentBorder, shape) else mod
                }.hoverable(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = title,
                style = typography.monoCaption,
                color = if (selected) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = coordText,
                style = typography.monoCaption,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        StudioBadge(text = badgeText)
    }
}

@Composable
private fun GameplayCellWalkabilitySection(
    state: LevelEditorState,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    topDivider: Boolean = false,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val primary = state.selectedCells.firstOrNull() ?: (0 to 0)
    val (col, row) = primary
    if (!state.document.grid.isInBounds(col, row)) return

    val override = state.walkabilityOverrideAt(col, row)
    val effective = state.effectiveWalkabilityAt(col, row)
    val currentChoice =
        when {
            override == null -> CellWalkChoice.DEFAULT
            override.walkable -> CellWalkChoice.WALKABLE
            else -> CellWalkChoice.BLOCKED
        }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StudioSectionHeader(
            title = "Cell ($col, $row) Walkability",
            badge = if (override != null) "Override" else "Default",
            collapsible = true,
            expanded = expanded,
            onToggleExpand = onToggleExpand,
            topDivider = topDivider,
        )

        if (expanded) {
            Text(
                text =
                    if (effective.walkable) {
                        "Effective: Walkable · Cost ${effective.moveCost} · Flags ${effective.flags}"
                    } else {
                        "Effective: Blocked · Flags ${effective.flags}"
                    },
                style = typography.caption,
                color = if (effective.walkable) colors.semanticSuccess else colors.semanticErrorText,
            )

            StudioSegmentedControl(
                items =
                    listOf(
                        SegmentItem(CellWalkChoice.DEFAULT, "Default"),
                        SegmentItem(CellWalkChoice.WALKABLE, "Walkable", icon = StudioIcons.WalkableCheck),
                        SegmentItem(CellWalkChoice.BLOCKED, "Blocked", icon = StudioIcons.WalkableBlocked),
                    ),
                selectedValue = currentChoice,
                onSelect = { choice ->
                    when (choice) {
                        CellWalkChoice.DEFAULT -> state.clearCellWalkabilityOverride(col, row)
                        CellWalkChoice.WALKABLE ->
                            state.setCellWalkabilityOverride(
                                col = col,
                                row = row,
                                walkable = true,
                                moveCost = (override?.moveCost?.takeIf { it > 0 } ?: 1),
                                flags = override?.flags ?: 0,
                            )
                        CellWalkChoice.BLOCKED ->
                            state.setCellWalkabilityOverride(
                                col = col,
                                row = row,
                                walkable = false,
                                moveCost = 0,
                                flags = override?.flags ?: 0,
                            )
                    }
                },
                compact = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Move Cost (0..7)", style = typography.caption, color = colors.textSecondary)
                    StudioNumberStepper(
                        value = effective.moveCost,
                        enabled = effective.walkable,
                        range = 0..7,
                        onValueChange = { nextCost ->
                            state.setCellWalkabilityOverride(
                                col = col,
                                row = row,
                                walkable = true,
                                moveCost = nextCost,
                                flags = effective.flags,
                            )
                        },
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Flags (0..15)", style = typography.caption, color = colors.textSecondary)
                    StudioNumberStepper(
                        value = effective.flags,
                        range = 0..15,
                        onValueChange = { nextFlags ->
                            state.setCellWalkabilityOverride(
                                col = col,
                                row = row,
                                walkable = effective.walkable,
                                moveCost = effective.moveCost,
                                flags = nextFlags,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerSpawnEditorSection(
    state: LevelEditorState,
    spawn: PlayerSpawn,
    topDivider: Boolean = false,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val grid = state.document.grid

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioSectionHeader(
            title = "Player Spawn",
            badge = "(${spawn.col}, ${spawn.row})",
            topDivider = topDivider,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Col", style = typography.caption, color = colors.textSecondary)
                StudioNumberStepper(
                    value = spawn.col,
                    range = 0 until grid.cols,
                    onValueChange = { nextCol ->
                        state.setPlayerSpawn(nextCol, spawn.row, spawn.facing)
                        state.selectedCells = setOf(nextCol to spawn.row)
                    },
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Row", style = typography.caption, color = colors.textSecondary)
                StudioNumberStepper(
                    value = spawn.row,
                    range = 0 until grid.rows,
                    onValueChange = { nextRow ->
                        state.setPlayerSpawn(spawn.col, nextRow, spawn.facing)
                        state.selectedCells = setOf(spawn.col to nextRow)
                    },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Facing Direction", style = typography.caption, color = colors.textSecondary)
            StudioSegmentedControl(
                items =
                    listOf(
                        SegmentItem(FacingDirection.NORTH, "N"),
                        SegmentItem(FacingDirection.EAST, "E"),
                        SegmentItem(FacingDirection.SOUTH, "S"),
                        SegmentItem(FacingDirection.WEST, "W"),
                    ),
                selectedValue = spawn.facing,
                onSelect = { state.setPlayerSpawn(spawn.col, spawn.row, it) },
                compact = true,
            )
        }
    }
}

@Composable
private fun NpcSpawnEditorSection(
    state: LevelEditorState,
    npcIndex: Int,
    npc: NpcSpawn,
    topDivider: Boolean = false,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val grid = state.document.grid
    val isPickingWaypoints = state.gameplayTool == GameplayTool.EDIT_WAYPOINTS

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioSectionHeader(
            title = "NPC Spawn",
            badge = "#$npcIndex",
            topDivider = topDivider,
            trailing = {
                StudioButton(
                    label = "Delete",
                    variant = StudioButtonVariant.DangerGhost,
                    compact = true,
                    icon = StudioIcons.Trash,
                    onClick = { state.removeNpc(npcIndex) },
                )
            },
        )

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("NPC Symbol ID", style = typography.caption, color = colors.textSecondary)
            StudioTextField(
                value = npc.id,
                onValueChange = { state.updateNpc(npcIndex, npc.copy(id = it)) },
                monospace = true,
                placeholder = "patrol_slime",
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Col", style = typography.caption, color = colors.textSecondary)
                StudioNumberStepper(
                    value = npc.col,
                    range = 0 until grid.cols,
                    onValueChange = { nextCol ->
                        state.updateNpc(npcIndex, npc.copy(col = nextCol))
                        state.selectedCells = setOf(nextCol to npc.row)
                    },
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Row", style = typography.caption, color = colors.textSecondary)
                StudioNumberStepper(
                    value = npc.row,
                    range = 0 until grid.rows,
                    onValueChange = { nextRow ->
                        state.updateNpc(npcIndex, npc.copy(row = nextRow))
                        state.selectedCells = setOf(npc.col to nextRow)
                    },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("AI Behavior", style = typography.caption, color = colors.textSecondary)
            StudioSegmentedControl(
                items =
                    listOf(
                        SegmentItem(NpcAiType.GUARD, "Guard", icon = StudioIcons.EntityNpcGuard),
                        SegmentItem(NpcAiType.PATROL, "Patrol", icon = StudioIcons.EntityNpcPatrol),
                    ),
                selectedValue = npc.aiType,
                onSelect = { state.updateNpc(npcIndex, npc.copy(aiType = it)) },
                compact = true,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Sprite ID", style = typography.caption, color = colors.textSecondary)
                StudioNumberStepper(
                    value = npc.spriteId,
                    range = 0..255,
                    onValueChange = { state.updateNpc(npcIndex, npc.copy(spriteId = it)) },
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Aggro", style = typography.caption, color = colors.textSecondary)
                StudioNumberStepper(
                    value = npc.aggroRange,
                    range = 0..255,
                    onValueChange = { state.updateNpc(npcIndex, npc.copy(aggroRange = it)) },
                )
            }
        }

        Text("Combat & Turn Stats", style = typography.caption, color = colors.textSecondary)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatStepperField("HP", npc.hp, 1..255, Modifier.weight(1f)) {
                state.updateNpc(npcIndex, npc.copy(hp = it))
            }
            StatStepperField("ATK", npc.atk, 0..255, Modifier.weight(1f)) {
                state.updateNpc(npcIndex, npc.copy(atk = it))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatStepperField("DEF", npc.def, 0..255, Modifier.weight(1f)) {
                state.updateNpc(npcIndex, npc.copy(def = it))
            }
            StatStepperField("SPD", npc.speed, 0..255, Modifier.weight(1f)) {
                state.updateNpc(npcIndex, npc.copy(speed = it))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatStepperField("MOV", npc.movePoints, 0..255, Modifier.weight(1f)) {
                state.updateNpc(npcIndex, npc.copy(movePoints = it))
            }
            Spacer(modifier = Modifier.weight(1f))
        }

        // Patrol Waypoint Chain Authoring (Max 4)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Patrol Waypoints (${npc.patrolWaypoints.size}/${GameplayBinarySerializer.MAX_WAYPOINTS})",
                    style = typography.caption,
                    color = colors.waypointGold,
                )
                if (npc.patrolWaypoints.isNotEmpty()) {
                    StudioButton(
                        label = "Clear",
                        variant = StudioButtonVariant.Ghost,
                        compact = true,
                        onClick = { state.clearNpcWaypoints(npcIndex) },
                    )
                }
            }

            StudioButton(
                label = if (isPickingWaypoints) "Picking Waypoints on Grid (Done)" else "Pick Waypoints on Grid",
                variant = if (isPickingWaypoints) StudioButtonVariant.Primary else StudioButtonVariant.Secondary,
                compact = true,
                icon = StudioIcons.EntityWaypoint,
                shortcut = "M",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    state.gameplayTool =
                        if (isPickingWaypoints) {
                            GameplayTool.SELECT_ENTITY
                        } else {
                            GameplayTool.EDIT_WAYPOINTS
                        }
                },
            )

            if (npc.patrolWaypoints.isEmpty()) {
                Text(
                    text = "Click 'Pick Waypoints on Grid' then tap up to 4 walkable cells to define a closed patrol loop.",
                    style = typography.caption,
                    color = colors.textMuted,
                )
            } else {
                for ((wpIdx, wp) in npc.patrolWaypoints.withIndex()) {
                    val wpShape = RoundedCornerShape(StudioTheme.metrics.radiusXS)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(wpShape)
                                .background(colors.surface0, wpShape)
                                .border(1.dp, colors.borderSubtle, wpShape)
                                .clickable { state.ensureCellVisibleInPreview(wp.col, wp.row) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(colors.waypointGold),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "${wpIdx + 1}",
                                    style = typography.microBadge,
                                    color = colors.bgCanvas,
                                )
                            }
                            Text(
                                text = "WP${wpIdx + 1} (${wp.col}, ${wp.row})",
                                style = typography.monoCaption,
                                color = colors.textPrimary,
                            )
                        }
                        StudioIconButton(
                            icon = StudioIcons.Close,
                            contentDescription = "Remove waypoint",
                            size = 22.dp,
                            dangerOnHover = true,
                            onClick = { state.removeNpcWaypoint(npcIndex, wpIdx) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatStepperField(
    label: String,
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = typography.monoCaption, color = colors.textMuted)
        StudioNumberStepper(
            value = value,
            range = range,
            onValueChange = onValueChange,
        )
    }
}

@Composable
private fun InteractableSpawnEditorSection(
    state: LevelEditorState,
    interactableIndex: Int,
    inter: InteractableSpawn,
    topDivider: Boolean = false,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val grid = state.document.grid

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioSectionHeader(
            title = "Interactable Object",
            badge = "#$interactableIndex",
            topDivider = topDivider,
            trailing = {
                StudioButton(
                    label = "Delete",
                    variant = StudioButtonVariant.DangerGhost,
                    compact = true,
                    icon = StudioIcons.Trash,
                    onClick = { state.removeInteractable(interactableIndex) },
                )
            },
        )

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Object Symbol ID", style = typography.caption, color = colors.textSecondary)
            StudioTextField(
                value = inter.id,
                onValueChange = { state.updateInteractable(interactableIndex, inter.copy(id = it)) },
                monospace = true,
                placeholder = "hidden_chest",
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Col", style = typography.caption, color = colors.textSecondary)
                StudioNumberStepper(
                    value = inter.col,
                    range = 0 until grid.cols,
                    onValueChange = { nextCol ->
                        state.updateInteractable(interactableIndex, inter.copy(col = nextCol))
                        state.selectedCells = setOf(nextCol to inter.row)
                    },
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Row", style = typography.caption, color = colors.textSecondary)
                StudioNumberStepper(
                    value = inter.row,
                    range = 0 until grid.rows,
                    onValueChange = { nextRow ->
                        state.updateInteractable(interactableIndex, inter.copy(row = nextRow))
                        state.selectedCells = setOf(inter.col to nextRow)
                    },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Object Kind", style = typography.caption, color = colors.textSecondary)
            StudioSegmentedControl(
                items =
                    listOf(
                        SegmentItem(InteractableKind.CHEST, "Chest", icon = StudioIcons.EntityChest),
                        SegmentItem(InteractableKind.SWITCH, "Switch", icon = StudioIcons.EntitySwitch),
                        SegmentItem(InteractableKind.SIGN, "Sign", icon = StudioIcons.EntitySign),
                    ),
                selectedValue = inter.kind,
                onSelect = { state.updateInteractable(interactableIndex, inter.copy(kind = it)) },
                compact = true,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Closed Sprite", style = typography.caption, color = colors.textSecondary)
                StudioNumberStepper(
                    value = inter.closedSpriteId,
                    range = 0..255,
                    onValueChange = { state.updateInteractable(interactableIndex, inter.copy(closedSpriteId = it)) },
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Open Sprite", style = typography.caption, color = colors.textSecondary)
                StudioNumberStepper(
                    value = inter.openSpriteId,
                    range = 0..255,
                    onValueChange = { state.updateInteractable(interactableIndex, inter.copy(openSpriteId = it)) },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Reward Type", style = typography.caption, color = colors.textSecondary)
            StudioSegmentedControl(
                items =
                    listOf(
                        SegmentItem(RewardKind.NONE, "None"),
                        SegmentItem(RewardKind.ATK_BUFF, "ATK+"),
                        SegmentItem(RewardKind.HP_HEAL, "Heal"),
                    ),
                selectedValue = inter.rewardKind,
                onSelect = { state.updateInteractable(interactableIndex, inter.copy(rewardKind = it)) },
                compact = true,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Reward Val", style = typography.caption, color = colors.textSecondary)
                StudioNumberStepper(
                    value = inter.rewardValue,
                    enabled = inter.rewardKind != RewardKind.NONE,
                    range = 0..255,
                    onValueChange = { state.updateInteractable(interactableIndex, inter.copy(rewardValue = it)) },
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Message ID", style = typography.caption, color = colors.textSecondary)
                StudioNumberStepper(
                    value = inter.messageId,
                    range = 0..255,
                    onValueChange = { state.updateInteractable(interactableIndex, inter.copy(messageId = it)) },
                )
            }
        }
    }
}
