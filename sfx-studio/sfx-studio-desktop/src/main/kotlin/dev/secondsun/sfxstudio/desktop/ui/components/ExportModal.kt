package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.core.atlas.DescriptorTable
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioBadge
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioModalDialog
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSectionHeader
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioTextField
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import java.io.File

@Composable
fun ExportModal(state: AtlasEditorState) {
    if (!state.showExportModal) return

    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    val defaultDir =
        remember {
            state.lastExportDirectory
                ?: state.currentProjectFile?.parentFile?.absolutePath
                ?: state.lastImportDirectory
                ?: File(".").absolutePath
        }

    var exportDir by remember { mutableStateOf(defaultDir) }
    var baseName by remember { mutableStateOf(state.projectName) }

    var incGraphics by remember { mutableStateOf(true) }
    var incPalette by remember { mutableStateOf(true) }
    var incTable by remember { mutableStateOf(true) }
    var incIndex by remember { mutableStateOf(true) }
    var incPng by remember { mutableStateOf(true) }

    val cleanBase =
        remember(baseName) {
            SourceTexture.sanitizeToIdentifier(baseName.trim().ifEmpty { "TEXTURES" })
        }

    val totalDescriptors = state.atlas.textures.size + state.atlas.slices.size + state.atlas.aliases.size
    val tabBytes = DescriptorTable.TAB_HEADER_BYTES + totalDescriptors * DescriptorTable.TAB_ENTRY_BYTES
    val runtimePayloadBytes =
        (if (incGraphics) state.atlas.sizeBytes else 0) +
            (if (incPalette) 32 else 0) +
            (if (incTable) tabBytes else 0)

    val payloadLabel =
        if (runtimePayloadBytes >= 1024) {
            String.format("Runtime payload: %.1f KB", runtimePayloadBytes / 1024f)
        } else {
            "Runtime payload: $runtimePayloadBytes B"
        }

    val canExport = (incGraphics || incPalette || incTable || incIndex || incPng) && exportDir.isNotBlank()

    StudioModalDialog(
        onDismissRequest = { state.showExportModal = false },
        title = "Export Texture Assets",
        subtitle = "Write runtime binaries, ca65 header, and preview sheet",
        preferredWidth = 580.dp,
        footerLeading = {
            Text(
                text = payloadLabel,
                style = typography.monoCaption,
                color = colors.textMuted,
            )
        },
        footerActions = {
            StudioButton(
                label = "Cancel",
                variant = StudioButtonVariant.Ghost,
                onClick = { state.showExportModal = false },
            )
            StudioButton(
                label = "Export Selected",
                variant = StudioButtonVariant.Primary,
                icon = StudioIcons.Export,
                enabled = canExport,
                onClick = {
                    val targetDir = File(exportDir.trim())
                    val ok =
                        state.exportAllAssets(
                            destinationDir = targetDir,
                            baseName = cleanBase,
                            graphicsBinFileName = "$cleanBase.bin",
                            paletteBinFileName = "$cleanBase.pal",
                            tableBinFileName = "$cleanBase.tab",
                            indexFileName = "$cleanBase.i",
                            pngFileName = "${cleanBase}_atlas.png",
                            includeGraphicsBin = incGraphics,
                            includePaletteBin = incPalette,
                            includeTableBin = incTable,
                            includeIndexFile = incIndex,
                            includePng = incPng,
                        )
                    if (ok) {
                        state.showExportModal = false
                    }
                },
            )
        },
    ) {
        // 1. Export Directory (StudioTextField — zero glyph clipping)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Export Directory", style = typography.bodyMedium, color = colors.textSecondary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StudioTextField(
                    value = exportDir,
                    onValueChange = { exportDir = it },
                    monospace = true,
                    placeholder = "/path/to/export",
                    modifier = Modifier.weight(1f),
                )
                StudioButton(
                    label = "Browse...",
                    variant = StudioButtonVariant.Secondary,
                    icon = StudioIcons.FolderOpen,
                    onClick = {
                        state.requestExportDirectory(exportDir) { chosen ->
                            if (chosen != null) {
                                exportDir = chosen.absolutePath
                            }
                        }
                    },
                )
            }
        }

        // 2. Base Symbol / Filename Prefix
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Base Name", style = typography.bodyMedium, color = colors.textSecondary)
            StudioTextField(
                value = baseName,
                onValueChange = { baseName = it },
                monospace = true,
                placeholder = "TEXTURES",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 3. Companion Files Checklist
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioSectionHeader(
                title = "Files to Generate",
                badge = "$cleanBase.*",
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(metrics.radiusSM))
                        .background(colors.surface0)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusSM)),
            ) {
                ExportFileRow(
                    checked = incGraphics,
                    onCheckedChange = { incGraphics = it },
                    fileName = "$cleanBase.bin",
                    roleBadge = "GSU RAM",
                    description = "Packed texture atlas binary (${state.atlas.sizeBytes} bytes, 1 byte/texel)",
                    showBottomDivider = true,
                )
                ExportFileRow(
                    checked = incPalette,
                    onCheckedChange = { incPalette = it },
                    fileName = "$cleanBase.pal",
                    roleBadge = "CGRAM",
                    description = "SNES BGR555 palette binary (32 bytes, 16 words)",
                    showBottomDivider = true,
                )
                ExportFileRow(
                    checked = incTable,
                    onCheckedChange = { incTable = it },
                    fileName = "$cleanBase.tab",
                    roleBadge = "Bank $71",
                    description = "Relocatable runtime Texture Table ($tabBytes bytes, $totalDescriptors entries)",
                    showBottomDivider = true,
                )
                ExportFileRow(
                    checked = incIndex,
                    onCheckedChange = { incIndex = it },
                    fileName = "$cleanBase.i",
                    roleBadge = "ca65",
                    description = "Assembly header with stride shifts, bitmasks, and TEXTURE_TABLE",
                    showBottomDivider = true,
                )
                ExportFileRow(
                    checked = incPng,
                    onCheckedChange = { incPng = it },
                    fileName = "${cleanBase}_atlas.png",
                    roleBadge = "Preview",
                    description = "ARGB layout sheet (${state.atlas.width}x${state.atlas.height}, transparent Slot 0)",
                    showBottomDivider = false,
                )
            }
        }
    }
}

@Composable
private fun ExportFileRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    fileName: String,
    roleBadge: String,
    description: String,
    showBottomDivider: Boolean,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onCheckedChange(!checked) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val boxShape = RoundedCornerShape(metrics.radiusXS)
            Box(
                modifier =
                    Modifier
                        .size(18.dp)
                        .clip(boxShape)
                        .background(if (checked) colors.accentPrimary else colors.surface1, boxShape)
                        .border(
                            1.dp,
                            if (checked) colors.accentHover else colors.borderStrong,
                            boxShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Icon(
                        imageVector = StudioIcons.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = fileName,
                        style = typography.monoBody,
                        color = if (checked) colors.sliceCyan else colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StudioBadge(
                        text = roleBadge,
                        textColor = if (checked) colors.textSecondary else colors.textMuted,
                        backgroundColor = colors.surface2,
                    )
                }
                Text(
                    text = description,
                    style = typography.caption,
                    color = if (checked) colors.textSecondary else colors.textMuted,
                )
            }
        }

        if (showBottomDivider) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.borderSubtle),
            )
        }
    }
}
