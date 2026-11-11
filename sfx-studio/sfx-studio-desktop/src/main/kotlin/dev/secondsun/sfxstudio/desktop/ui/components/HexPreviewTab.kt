package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
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
fun HexPreviewTab(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    val binBytes = remember(state.atlas) { state.atlas.generateBinary() }
    val palBytes = remember(state.palette) { state.palette.toBgr555Binary() }

    // Precalculate byte offset ranges belonging to selected texture
    val selectedOffsets =
        remember(state.atlas, state.selectedTextureIndex) {
            val set = mutableSetOf<Int>()
            val tex = state.selectedTexture
            if (tex != null) {
                for (v in 0 until tex.height) {
                    val rowStart = (tex.y + v) * state.atlas.width + tex.x
                    for (u in 0 until tex.width) {
                        set.add(rowStart + u)
                    }
                }
            }
            set
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color(0xFF181A1F))
                .padding(12.dp),
    ) {
        // --- Palette Binary Header ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "SNES BGR555 Palette Binary (${palBytes.size} bytes / 16 words)",
                style = MaterialTheme.typography.subtitle2,
                color = Color.White,
            )

            Button(
                onClick = { state.promptExportPaletteBin() },
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF059669)),
                modifier = Modifier.height(26.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("Export Palette (.bin)...", color = Color.White, fontSize = 11.sp)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF22242B), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF33363D), RoundedCornerShape(4.dp))
                    .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (slot in 0..15) {
                val low = palBytes[slot * 2].toInt() and 0xFF
                val high = palBytes[slot * 2 + 1].toInt() and 0xFF
                val word = (high shl 8) or low
                val isSelected = state.selectedPaletteSlot == slot

                Text(
                    text = String.format("%04X", word),
                    color = if (isSelected) Color.Yellow else Color(0xFF93C5FD),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Divider(color = Color(0xFF33363D))
        Spacer(modifier = Modifier.height(8.dp))

        // --- Atlas Binary Dump ---
        val selectedTexName = state.selectedTexture?.name
        val highlightInfo = if (selectedTexName != null) " (Highlighting '$selectedTexName')" else ""

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Atlas Graphics Binary Dump (${binBytes.size} bytes)$highlightInfo",
                style = MaterialTheme.typography.subtitle2,
                color = Color.White,
            )

            Button(
                onClick = { state.promptExportGraphicsBin() },
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF059669)),
                modifier = Modifier.height(26.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("Export Graphics (.bin)...", color = Color.White, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Hex Viewer Table (16 bytes per line)
        val numRows = (binBytes.size + 15) / 16
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E2026), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF33363D), RoundedCornerShape(4.dp))
                    .padding(8.dp),
        ) {
            items(numRows) { row ->
                val offset = row * 16

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Address offset
                    Text(
                        text = String.format("%04X: ", offset),
                        color = Color(0xFF64748B),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.width(56.dp),
                    )

                    // 16 Hex bytes
                    Row(
                        modifier = Modifier.width(380.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (col in 0..15) {
                            val byteIndex = offset + col
                            if (byteIndex < binBytes.size) {
                                val b = binBytes[byteIndex].toInt() and 0xFF
                                val isHighlighted = selectedOffsets.contains(byteIndex)

                                val textColor =
                                    when {
                                        isHighlighted -> Color(0xFFFBBF24) // Gold for selected texture
                                        b == 0 -> Color(0xFF475569) // Dim gray for 0x00
                                        else -> Color(0xFFE2E8F0) // White for active texels
                                    }

                                Text(
                                    text = String.format("%02X", b),
                                    color = textColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                )
                            } else {
                                Spacer(modifier = Modifier.width(18.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // ASCII representation
                    Row {
                        for (col in 0..15) {
                            val byteIndex = offset + col
                            if (byteIndex < binBytes.size) {
                                val b = binBytes[byteIndex].toInt() and 0xFF
                                val ch = if (b in 32..126) b.toChar() else '.'
                                val isHighlighted = selectedOffsets.contains(byteIndex)

                                Text(
                                    text = ch.toString(),
                                    color = if (isHighlighted) Color(0xFFFBBF24) else Color(0xFF94A3B8),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
