package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.core.atlas.DescriptorAlias
import dev.secondsun.sfxstudio.core.atlas.DescriptorTable
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.ui.TextureComposeRenderer
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioBadge
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioModalDialog
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioTextField
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.checkerboardBackground

private data class AliasTargetOption(
    val name: String,
    val kindLabel: String,
    val slotIndex: Int?,
    val previewTexture: SourceTexture?,
)

@Composable
fun AliasEditorModal(state: AtlasEditorState) {
    if (!state.showAliasModal) return

    val editingIdx = state.editingAliasIndex
    val existingAlias = editingIdx?.let { state.atlas.aliases.getOrNull(it) }
    val table = remember(state.atlas) { state.atlas.descriptorTable().getOrNull() }

    val targetOptions =
        remember(state.atlas, editingIdx, table) {
            buildList {
                for (tex in state.atlas.textures) {
                    val idx = table?.entries?.indexOfFirst { it.name.equals(tex.name, ignoreCase = true) }?.takeIf { it >= 0 }
                    add(AliasTargetOption(tex.name, "TEX", idx, tex))
                }
                for (slice in state.atlas.slices) {
                    val idx = table?.entries?.indexOfFirst { it.name.equals(slice.name, ignoreCase = true) }?.takeIf { it >= 0 }
                    val desc = if (idx != null) table.entries.getOrNull(idx) else null
                    val preview = if (desc != null && table != null) table.extractPreviewTexture(desc) else null
                    add(AliasTargetOption(slice.name, "SLICE", idx, preview))
                }
                for ((aIdx, alias) in state.atlas.aliases.withIndex()) {
                    if (aIdx == editingIdx) continue
                    val idx = table?.entries?.indexOfFirst { it.name.equals(alias.name, ignoreCase = true) }?.takeIf { it >= 0 }
                    val desc = if (idx != null) table.entries.getOrNull(idx) else null
                    val preview = if (desc != null && table != null) table.extractPreviewTexture(desc) else null
                    add(AliasTargetOption(alias.name, "ALIAS", idx, preview))
                }
            }
        }

    if (targetOptions.isEmpty()) {
        state.closeAliasModal()
        return
    }

    val defaultTarget =
        remember(existingAlias, targetOptions, state.selectedSliceName, state.selectedTexture) {
            existingAlias?.target
                ?: state.selectedSliceName
                ?: state.selectedTexture?.name
                ?: targetOptions.first().name
        }

    val defaultName =
        remember(existingAlias, defaultTarget, state.atlas) {
            if (existingAlias != null) {
                existingAlias.name
            } else {
                var suffix = 1
                var cand = "${defaultTarget}_alias"
                while (
                    state.atlas.textures.any { it.name.equals(cand, ignoreCase = true) } ||
                    state.atlas.slices.any { it.name.equals(cand, ignoreCase = true) } ||
                    state.atlas.aliases.any { it.name.equals(cand, ignoreCase = true) }
                ) {
                    suffix++
                    cand = "${defaultTarget}_alias_$suffix"
                }
                cand
            }
        }

    var aliasName by remember(existingAlias, defaultName) { mutableStateOf(defaultName) }
    var selectedTarget by remember(existingAlias, defaultTarget) { mutableStateOf(defaultTarget) }
    var filterQuery by remember { mutableStateOf("") }

    val trimmedName = aliasName.trim()
    val nameError =
        remember(trimmedName, editingIdx, state.atlas) {
            val conflictTex = state.atlas.textures.any { it.name.equals(trimmedName, ignoreCase = true) }
            val conflictSlice = state.atlas.slices.any { it.name.equals(trimmedName, ignoreCase = true) }
            val conflictAlias =
                state.atlas.aliases.withIndex().any { (idx, a) ->
                    idx != editingIdx && a.name.equals(trimmedName, ignoreCase = true)
                }
            when {
                trimmedName.isEmpty() -> "Alias symbol name is required"
                !SourceTexture.isValidCa65Identifier(trimmedName) ->
                    "Must match ca65 identifier syntax ([A-Za-z_][A-Za-z0-9_]*)"
                conflictTex || conflictSlice || conflictAlias ->
                    "Identifier '$trimmedName' collides with an existing descriptor (case-insensitive)"
                else -> null
            }
        }

    val cycleOrResolutionError =
        remember(trimmedName, selectedTarget, nameError, editingIdx, state.atlas) {
            if (nameError != null || selectedTarget.isBlank()) {
                null
            } else {
                val candidateAlias = DescriptorAlias(name = trimmedName, target = selectedTarget)
                val nextAliases =
                    if (editingIdx != null && editingIdx in state.atlas.aliases.indices) {
                        val oldName = state.atlas.aliases[editingIdx].name
                        state.atlas.aliases.mapIndexed { idx, existing ->
                            when {
                                idx == editingIdx -> candidateAlias
                                !oldName.equals(trimmedName, ignoreCase = true) &&
                                    existing.target.equals(oldName, ignoreCase = true) ->
                                    existing.copy(target = trimmedName)
                                else -> existing
                            }
                        }
                    } else {
                        state.atlas.aliases + candidateAlias
                    }
                val nextOrder =
                    if (editingIdx != null && editingIdx in state.atlas.aliases.indices) {
                        val oldName = state.atlas.aliases[editingIdx].name
                        state.atlas.descriptorOrder?.map { if (it.equals(oldName, ignoreCase = true)) trimmedName else it }
                    } else {
                        state.atlas.descriptorOrder?.let { it + trimmedName }
                    }
                val testAtlas = state.atlas.copy(aliases = nextAliases, descriptorOrder = nextOrder)
                DescriptorTable.fromAtlas(testAtlas).exceptionOrNull()?.message
            }
        }

    val filteredTargets =
        remember(targetOptions, filterQuery) {
            val q = filterQuery.trim()
            if (q.isEmpty()) {
                targetOptions
            } else {
                targetOptions.filter { it.name.contains(q, ignoreCase = true) }
            }
        }

    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val canCommit = nameError == null && cycleOrResolutionError == null && selectedTarget.isNotBlank()

    StudioModalDialog(
        onDismissRequest = { state.closeAliasModal() },
        title = if (existingAlias != null) "Edit Descriptor Alias" else "New Descriptor Alias",
        subtitle = "Symbolic ca65 descriptor entry referencing an existing tile or slice",
        preferredWidth = 460.dp,
        footerActions = {
            StudioButton(
                label = "Cancel",
                variant = StudioButtonVariant.Ghost,
                onClick = { state.closeAliasModal() },
            )
            StudioButton(
                label = if (existingAlias != null) "Save Alias" else "Create Alias",
                variant = StudioButtonVariant.Primary,
                icon = StudioIcons.AliasLink,
                enabled = canCommit,
                onClick = {
                    val candidate = DescriptorAlias(name = trimmedName, target = selectedTarget)
                    if (editingIdx != null) {
                        state.updateAlias(editingIdx, candidate)
                    } else {
                        state.addAlias(candidate)
                    }
                },
            )
        },
    ) {
        // 1. Alias Symbol Field
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Alias Symbol Name", style = typography.bodyMedium, color = colors.textSecondary)
            StudioTextField(
                value = aliasName,
                onValueChange = { aliasName = it },
                placeholder = "e.g. hero_trim_alias",
                monospace = true,
                isError = nameError != null,
                errorText = nameError,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 2. Target Descriptor Picker
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Target Descriptor", style = typography.bodyMedium, color = colors.textSecondary)
            if (targetOptions.size > 6) {
                StudioTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    placeholder = "Filter descriptors...",
                    leadingIcon = StudioIcons.Search,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(metrics.radiusSM))
                        .background(colors.surface0)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusSM))
                        .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (opt in filteredTargets) {
                    val isSelected = opt.name.equals(selectedTarget, ignoreCase = true)
                    val rowShape = RoundedCornerShape(metrics.radiusXS)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 34.dp)
                                .clip(rowShape)
                                .background(if (isSelected) colors.accentSubtle else Color.Transparent, rowShape)
                                .let { mod ->
                                    if (isSelected) mod.border(1.dp, colors.accentBorder, rowShape) else mod
                                }.clickable { selectedTarget = opt.name }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(metrics.radiusXS))
                                    .checkerboardBackground(3.dp, colors.checkerDark, colors.checkerLight)
                                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusXS)),
                        ) {
                            if (opt.previewTexture != null) {
                                val bmp =
                                    remember(opt.previewTexture, state.palette) {
                                        TextureComposeRenderer.renderToImageBitmap(opt.previewTexture, state.palette)
                                    }
                                Image(
                                    bitmap = bmp,
                                    contentDescription = opt.name,
                                    filterQuality = FilterQuality.None,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        if (opt.slotIndex != null) {
                            StudioBadge(
                                text = String.format("$%02X", opt.slotIndex),
                                monospace = true,
                            )
                        }

                        Text(
                            text = opt.name,
                            style = typography.monoBody,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        val (badgeFg, badgeBg) =
                            when (opt.kindLabel) {
                                "SLICE" -> colors.sliceCyan to colors.sliceCyan.copy(alpha = 0.18f)
                                "ALIAS" -> colors.aliasAmber to colors.aliasAmber.copy(alpha = 0.18f)
                                else -> colors.textSecondary to colors.surface3
                            }
                        StudioBadge(
                            text = opt.kindLabel,
                            textColor = badgeFg,
                            backgroundColor = badgeBg,
                        )

                        if (isSelected) {
                            Icon(
                                imageVector = StudioIcons.Check,
                                contentDescription = "Selected",
                                tint = colors.accentHover,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        if (cycleOrResolutionError != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(metrics.radiusSM))
                        .background(colors.semanticErrorSubtle)
                        .border(1.dp, colors.semanticError.copy(alpha = 0.5f), RoundedCornerShape(metrics.radiusSM))
                        .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = StudioIcons.StatusError,
                    contentDescription = null,
                    tint = colors.semanticError,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = cycleOrResolutionError,
                    style = typography.caption,
                    color = colors.semanticErrorText,
                )
            }
        }
    }
}
