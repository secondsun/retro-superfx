package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.ui.TextureComposeRenderer

@Composable
fun LeftPane(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .width(360.dp)
                .background(Color(0xFF222428))
                .border(width = 1.dp, color = Color(0xFF33363D)),
    ) {
        // --- Top Half: Source Textures ---
        SourceTexturesSection(state, modifier = Modifier.weight(1f))

        Divider(color = Color(0xFF444850), thickness = 2.dp)

        // --- Bottom Half: Palette Editor ---
        PaletteSection(state, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SourceTexturesSection(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Source Textures (${state.atlas.textures.size})",
                style = MaterialTheme.typography.subtitle1,
                color = Color.White,
            )

            Button(
                onClick = {
                    state.promptImportFile()
                },
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3B82F6)),
            ) {
                Text("Import...", color = Color.White, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.atlas.textures.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No textures imported.\nClick 'Import...' to add power-of-two bitmaps.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(state.atlas.textures) { index, tex ->
                    val isSelected = state.selectedTextureIndex == index
                    val itemBg = if (isSelected) Color(0xFF1E3A8A) else Color(0xFF2A2D34)

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(itemBg)
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF60A5FA) else Color(0xFF3F444E),
                                    shape = RoundedCornerShape(4.dp),
                                ).clickable { state.selectedTextureIndex = index }
                                .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val bitmap = TextureComposeRenderer.renderToImageBitmap(tex, state.palette)
                        Image(
                            bitmap = bitmap,
                            contentDescription = tex.name,
                            modifier =
                                Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFF181A1F))
                                    .border(1.dp, Color.Gray, RoundedCornerShape(2.dp)),
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tex.name,
                                color = Color.White,
                                style = MaterialTheme.typography.body2,
                            )
                            Text(
                                text = "${tex.width}x${tex.height} at (${tex.x}, ${tex.y})",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                            )
                        }

                        IconButton(
                            onClick = { state.editingTextureIndex = index },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit Texture",
                                tint = Color.LightGray,
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        IconButton(
                            onClick = { state.deleteTexture(index) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete Texture",
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteSection(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "SNES Palette (16 Colors)",
                style = MaterialTheme.typography.subtitle1,
                color = Color.White,
            )

            Button(
                onClick = { state.quantizePalette() },
                enabled = state.atlas.textures.isNotEmpty() || state.palette.assignedCount > 0,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4B5563)),
            ) {
                Text("Quantize 15c", color = Color.White, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 16 swatches (2 rows of 8)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in 0..1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (col in 0..7) {
                        val slot = row * 8 + col
                        val isSelected = state.selectedPaletteSlot == slot
                        val color = state.palette[slot]
                        val isAssigned = state.palette.isAssigned(slot)

                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (slot == 0) Color.Transparent else Color(color.rgb888 or (0xFF shl 24)),
                                    ).border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color =
                                            if (isSelected) {
                                                Color.Yellow
                                            } else if (isAssigned) {
                                                Color.White
                                            } else {
                                                Color(0xFF6B7280)
                                            },
                                        shape = RoundedCornerShape(3.dp),
                                    ).clickable { state.selectedPaletteSlot = slot },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (slot == 0) {
                                Text("T", color = Color.Gray, fontSize = 10.sp)
                            } else if (color.r == 1 && color.g == 0 && color.b == 0) {
                                Text("01", color = Color.White, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val curSlot = state.selectedPaletteSlot
        if (curSlot == 0) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Palette slot 0 is locked as Transparent ($0000).\n" +
                        "Any texel with index 0 will render transparent in IsoEngine.",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                )
            }
        } else {
            val color = state.palette[curSlot]
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF1E2024), RoundedCornerShape(4.dp))
                        .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Slot $curSlot: ${color.hexString}",
                        color = Color.White,
                        fontSize = 13.sp,
                    )

                    Button(
                        onClick = { state.requestUnassignSlot(curSlot) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFDC2626)),
                        modifier = Modifier.height(26.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text("Unassign", color = Color.White, fontSize = 11.sp)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "R: ${color.r}",
                        color = Color(0xFFF87171),
                        fontSize = 11.sp,
                        modifier = Modifier.width(36.dp),
                    )
                    Slider(
                        value = color.r.toFloat(),
                        onValueChange = { state.updatePaletteSlot(curSlot, color.copy(r = it.toInt())) },
                        valueRange = 0f..31f,
                        steps = 30,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "G: ${color.g}",
                        color = Color(0xFF4ADE80),
                        fontSize = 11.sp,
                        modifier = Modifier.width(36.dp),
                    )
                    Slider(
                        value = color.g.toFloat(),
                        onValueChange = { state.updatePaletteSlot(curSlot, color.copy(g = it.toInt())) },
                        valueRange = 0f..31f,
                        steps = 30,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "B: ${color.b}",
                        color = Color(0xFF60A5FA),
                        fontSize = 11.sp,
                        modifier = Modifier.width(36.dp),
                    )
                    Slider(
                        value = color.b.toFloat(),
                        onValueChange = { state.updatePaletteSlot(curSlot, color.copy(b = it.toInt())) },
                        valueRange = 0f..31f,
                        steps = 30,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
