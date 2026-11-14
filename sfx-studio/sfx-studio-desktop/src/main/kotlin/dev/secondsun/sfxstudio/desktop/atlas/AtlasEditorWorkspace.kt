package dev.secondsun.sfxstudio.desktop.atlas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.secondsun.sfxstudio.core.atlas.AtlasValidationResult
import dev.secondsun.sfxstudio.desktop.ui.components.ExportModal
import dev.secondsun.sfxstudio.desktop.ui.components.HexPreviewTab
import dev.secondsun.sfxstudio.desktop.ui.components.IndexPreviewTab
import dev.secondsun.sfxstudio.desktop.ui.components.LayoutTab
import dev.secondsun.sfxstudio.desktop.ui.components.LeftPane
import dev.secondsun.sfxstudio.desktop.ui.components.PaletteRemappingModal
import dev.secondsun.sfxstudio.desktop.ui.components.TextureEditModal
import dev.secondsun.sfxstudio.desktop.ui.components.UnassignPromptModal

@Composable
fun AtlasEditorWorkspace(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    val validation =
        remember(state.document) {
            state.currentValidation()
        }

    Column(modifier = modifier.fillMaxSize().background(Color(0xFF181A1F))) {
        AtlasDiagnosticsBanner(validation)

        // Main split pane: Left (Textures + Palette), Right (3 Tabs)
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LeftPane(state = state)

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Right Tab Selector & Export Button
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF22242B)),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TabRow(
                        selectedTabIndex = state.activeTab.ordinal,
                        modifier = Modifier.weight(1f),
                        backgroundColor = Color(0xFF22242B),
                        contentColor = Color(0xFF60A5FA),
                    ) {
                        Tab(
                            selected = state.activeTab == AtlasEditorTab.LAYOUT,
                            onClick = { state.activeTab = AtlasEditorTab.LAYOUT },
                            text = { Text("Layout Editor") },
                        )
                        Tab(
                            selected = state.activeTab == AtlasEditorTab.HEX_PREVIEW,
                            onClick = { state.activeTab = AtlasEditorTab.HEX_PREVIEW },
                            text = { Text("Hex Preview") },
                        )
                        Tab(
                            selected = state.activeTab == AtlasEditorTab.INDEX_PREVIEW,
                            onClick = { state.activeTab = AtlasEditorTab.INDEX_PREVIEW },
                            text = { Text("Index File Preview (.i)") },
                        )
                    }

                    Button(
                        onClick = { state.showExportModal = true },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF059669)),
                        modifier = Modifier.padding(end = 12.dp).height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Text("Export Assets...", color = Color.White, fontSize = 12.sp)
                    }
                }

                // Tab Content
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (state.activeTab) {
                        AtlasEditorTab.LAYOUT -> LayoutTab(state)
                        AtlasEditorTab.HEX_PREVIEW -> HexPreviewTab(state)
                        AtlasEditorTab.INDEX_PREVIEW -> IndexPreviewTab(state)
                    }
                }
            }
        }

        // --- Dialog Modals ---
        TextureEditModal(state)
        PaletteRemappingModal(state)
        UnassignPromptModal(state)
        ExportModal(state)
    }
}

@Composable
private fun AtlasDiagnosticsBanner(validation: AtlasValidationResult) {
    if (validation.errors.isEmpty() && validation.warnings.isEmpty()) return
    val top = validation.errors.firstOrNull() ?: validation.warnings.first()
    val isErr = validation.errors.isNotEmpty()
    val bg = if (isErr) Color(0xFF3B181E) else Color(0xFF362B14)
    val border = if (isErr) Color(0xFFEF4444) else Color(0xFFF59E0B)
    val textCol = if (isErr) Color(0xFFFCA5A5) else Color(0xFFFDE68A)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(bg)
                .border(1.dp, border)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "[${top.code}] ${top.message}",
            color = textCol,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${validation.errors.size} error(s), ${validation.warnings.size} warning(s)",
            color = textCol,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
