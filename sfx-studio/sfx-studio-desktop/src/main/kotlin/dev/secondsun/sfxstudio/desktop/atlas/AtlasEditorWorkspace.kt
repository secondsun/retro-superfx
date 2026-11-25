package dev.secondsun.sfxstudio.desktop.atlas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.core.level.DiagnosticSeverity
import dev.secondsun.sfxstudio.desktop.ui.components.AliasEditorModal
import dev.secondsun.sfxstudio.desktop.ui.components.AtlasCompactBottomSheet
import dev.secondsun.sfxstudio.desktop.ui.components.CascadeDeleteModal
import dev.secondsun.sfxstudio.desktop.ui.components.ExportModal
import dev.secondsun.sfxstudio.desktop.ui.components.HexPreviewTab
import dev.secondsun.sfxstudio.desktop.ui.components.IndexPreviewTab
import dev.secondsun.sfxstudio.desktop.ui.components.LayoutTab
import dev.secondsun.sfxstudio.desktop.ui.components.LeftPane
import dev.secondsun.sfxstudio.desktop.ui.components.PaletteRemappingModal
import dev.secondsun.sfxstudio.desktop.ui.components.SliceEditorModal
import dev.secondsun.sfxstudio.desktop.ui.components.TextureEditModal
import dev.secondsun.sfxstudio.desktop.ui.components.UnassignPromptModal
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioDiagnosticItem
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioDiagnosticsBanner
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioViewportClass

@Composable
fun AtlasEditorWorkspace(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val viewport = StudioTheme.viewport
    val isCompact = viewport.viewportClass == StudioViewportClass.COMPACT
    val showLeftDock = state.studio?.showLeftDock != false
    val showBottomSheet = state.studio?.showCompactBottomSheet != false

    val validation =
        remember(state.document) {
            state.currentValidation()
        }

    val diagnosticItems =
        remember(validation, state.atlas) {
            validation.allDiagnostics.map { diag ->
                StudioDiagnosticItem(
                    isError = diag.severity == DiagnosticSeverity.ERROR,
                    code = diag.code,
                    message = diag.message,
                    onClick = {
                        val matchTexIdx =
                            state.atlas.textures.indexOfFirst { tex ->
                                diag.message.contains("'${tex.name}'")
                            }
                        if (matchTexIdx >= 0) {
                            state.selectedTextureIndex = matchTexIdx
                            state.selectedSliceName = null
                        }
                    },
                )
            }
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.bgCanvas),
    ) {
        StudioDiagnosticsBanner(
            items = diagnosticItems,
            activeIndex = state.activeDiagnosticIndex,
            onActiveIndexChange = { state.activeDiagnosticIndex = it },
        )

        if (isCompact) {
            // Compact (< 840dp): Full-width active stage above docked 3-tab Bottom Sheet
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AtlasActiveTabContent(state)
                }
                if (showBottomSheet) {
                    AtlasCompactBottomSheet(
                        state = state,
                        modifier = Modifier.height(350.dp).fillMaxWidth(),
                    )
                }
            }
        } else {
            // Expanded (>= 1280dp) & Medium (840-1279dp): Left Catalog Dock + Active Stage
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (showLeftDock) {
                    LeftPane(state = state)
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    AtlasActiveTabContent(state)
                }
            }
        }

        // --- Atlas Session Modals ---
        TextureEditModal(state)
        PaletteRemappingModal(state)
        UnassignPromptModal(state)
        ExportModal(state)
        SliceEditorModal(state)
        AliasEditorModal(state)
        CascadeDeleteModal(state)
    }
}

@Composable
private fun AtlasActiveTabContent(state: AtlasEditorState) {
    when (state.activeTab) {
        AtlasEditorTab.LAYOUT -> LayoutTab(state)
        AtlasEditorTab.HEX_PREVIEW -> HexPreviewTab(state)
        AtlasEditorTab.INDEX_PREVIEW -> IndexPreviewTab(state)
    }
}
