package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState

@Composable
fun IndexPreviewTab(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    val previewResult =
        remember(state.document) {
            state.document.renderIndexPreview(state.projectName)
        }
    val indexContent =
        previewResult.getOrElse { err ->
            ";; Cannot generate ca65 Atlas Index (.i) until descriptor errors are resolved:\n" +
                ";; ${err.message}"
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color(0xFF181A1F))
                .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ca65 Atlas Index (${state.projectName}.i)",
                style = MaterialTheme.typography.subtitle1,
                color = Color.White,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { state.promptExportIndexFile() },
                    enabled = previewResult.isSuccess,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF059669)),
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("Export .i File...", color = Color.White, fontSize = 12.sp)
                }

                Button(
                    onClick = { state.copyTextToClipboard(indexContent) },
                    enabled = previewResult.isSuccess,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3B82F6)),
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("Copy to Clipboard", color = Color.White, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E2026), RoundedCornerShape(4.dp))
                    .border(
                        1.dp,
                        if (previewResult.isSuccess) Color(0xFF33363D) else Color(0xFFEF4444),
                        RoundedCornerShape(4.dp),
                    ).padding(12.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = indexContent,
                color = if (previewResult.isSuccess) Color(0xFFE2E8F0) else Color(0xFFFCA5A5),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}
