package dev.secondsun.sfxstudio.desktop.level

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.core.atlas.SFX_ATLAS_EXTENSION
import dev.secondsun.sfxstudio.core.atlas.SFX_PROJ_EXTENSION
import dev.secondsun.sfxstudio.core.level.MapGrid
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioBadge
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioModalDialog
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioNumberStepper
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioTextField
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewLevelModal(studio: StudioState) {
    if (!studio.showNewLevelModal) return

    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    val openAtlasTabs =
        remember(studio.openSessions.size) {
            studio.openSessions.filterIsInstance<AtlasEditorState>()
        }

    val initialAtlasPath =
        remember {
            openAtlasTabs.firstNotNullOfOrNull { it.documentFile?.absolutePath } ?: ""
        }

    var levelName by remember { mutableStateOf("courtyard") }
    var atlasPath by remember { mutableStateOf(initialAtlasPath) }
    var cols by remember { mutableStateOf(8) }
    var rows by remember { mutableStateOf(8) }

    val mapByteSize = MapGrid.HEADER_BYTES + cols * rows * MapGrid.CELL_BYTES

    StudioModalDialog(
        onDismissRequest = { studio.showNewLevelModal = false },
        title = "New Isometric Level",
        subtitle = "Create a Level Document (.sfxlevel) linked to a shared Texture Atlas",
        preferredWidth = 520.dp,
        footerLeading = {
            StudioBadge(
                text = "${cols}x$rows · $mapByteSize B (.map)",
                textColor = colors.interactEmerald,
                backgroundColor = colors.interactEmerald.copy(alpha = 0.16f),
                monospace = true,
            )
        },
        footerActions = {
            StudioButton(
                label = "Cancel",
                variant = StudioButtonVariant.Ghost,
                onClick = { studio.showNewLevelModal = false },
            )
            StudioButton(
                label = "Create Level",
                variant = StudioButtonVariant.Primary,
                icon = StudioIcons.DocLevel,
                onClick = {
                    val cleanName = levelName.trim().ifBlank { "untitled_level" }
                    val atlasFile = atlasPath.trim().takeIf { it.isNotBlank() }?.let { File(it) }
                    studio.newLevelSession(
                        name = cleanName,
                        atlasFile = atlasFile,
                        cols = cols,
                        rows = rows,
                    )
                    studio.showNewLevelModal = false
                },
            )
        },
    ) {
        // 1. Level Name
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Level Symbol / Name",
                style = typography.bodyMedium,
                color = colors.textSecondary,
            )
            StudioTextField(
                value = levelName,
                onValueChange = { levelName = it },
                placeholder = "courtyard",
                monospace = true,
                leadingIcon = StudioIcons.DocLevel,
            )
        }

        // 2. Shared Texture Atlas + Open-Tab Quick Link Chips
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Shared Texture Atlas (.sfxproj / .sfxatlas)",
                style = typography.bodyMedium,
                color = colors.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StudioTextField(
                    value = atlasPath,
                    onValueChange = { atlasPath = it },
                    placeholder = "path/to/TEXTURES.sfxproj",
                    monospace = true,
                    modifier = Modifier.weight(1f),
                )
                StudioButton(
                    label = "Browse...",
                    variant = StudioButtonVariant.Secondary,
                    icon = StudioIcons.FolderOpen,
                    onClick = {
                        val picked =
                            studio.platform.chooseOpenFile(
                                title = "Select Texture Atlas",
                                initialDir = studio.lastImportDirectory ?: studio.lastExportDirectory,
                                allowedExtensions = setOf(SFX_PROJ_EXTENSION, SFX_ATLAS_EXTENSION),
                            )
                        if (picked != null) {
                            atlasPath = picked.absolutePath
                        }
                    },
                )
            }

            if (openAtlasTabs.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Link open tab:",
                        style = typography.caption,
                        color = colors.textMuted,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    for (tab in openAtlasTabs) {
                        val tabPath = tab.documentFile?.absolutePath.orEmpty()
                        val isSelected =
                            if (tabPath.isNotEmpty()) {
                                atlasPath.trim() == tabPath
                            } else {
                                atlasPath.isBlank()
                            }
                        val chipShape = RoundedCornerShape(metrics.radiusPill)
                        val tabTitle = tab.documentFile?.name ?: "${tab.displayTitle} (unsaved)"
                        Row(
                            modifier =
                                Modifier
                                    .clip(chipShape)
                                    .background(
                                        if (isSelected) colors.accentSubtle else colors.surface2,
                                        chipShape,
                                    ).border(
                                        1.dp,
                                        if (isSelected) colors.accentBorder else colors.borderSubtle,
                                        chipShape,
                                    ).clickable {
                                        atlasPath = tabPath
                                    }.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = StudioIcons.DocAtlas,
                                contentDescription = null,
                                tint = colors.sliceCyan,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = tabTitle,
                                style = typography.monoCaption,
                                color = if (isSelected) colors.textPrimary else colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (tab.hasUnsavedChanges) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(colors.semanticWarning),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Grid Dimensions (4..16) + Quick Presets + Byte Summary
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Map Dimensions (${MapGrid.MIN_DIM}..${MapGrid.MAX_DIM})",
                    style = typography.bodyMedium,
                    color = colors.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((presetCols, presetRows) in listOf(8 to 8, 12 to 12, 16 to 16)) {
                        val isPresetSelected = cols == presetCols && rows == presetRows
                        StudioButton(
                            label = "${presetCols}x$presetRows",
                            variant = if (isPresetSelected) StudioButtonVariant.Secondary else StudioButtonVariant.Ghost,
                            compact = true,
                            onClick = {
                                cols = presetCols
                                rows = presetRows
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Columns", style = typography.caption, color = colors.textMuted)
                    StudioNumberStepper(
                        value = cols,
                        onValueChange = { cols = it },
                        range = MapGrid.MIN_DIM..MapGrid.MAX_DIM,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Rows", style = typography.caption, color = colors.textMuted)
                    StudioNumberStepper(
                        value = rows,
                        onValueChange = { rows = it },
                        range = MapGrid.MIN_DIM..MapGrid.MAX_DIM,
                    )
                }
            }

            val summaryShape = RoundedCornerShape(metrics.radiusSM)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(summaryShape)
                        .background(colors.surface0, summaryShape)
                        .border(1.dp, colors.borderSubtle, summaryShape)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Bank \$71 Runtime Map Payload",
                    style = typography.caption,
                    color = colors.textSecondary,
                )
                Text(
                    text = "${MapGrid.HEADER_BYTES}B hdr + ${cols * rows} x ${MapGrid.CELL_BYTES}B = $mapByteSize bytes",
                    style = typography.monoCaption,
                    color = colors.textPrimary,
                )
            }
        }
    }
}
