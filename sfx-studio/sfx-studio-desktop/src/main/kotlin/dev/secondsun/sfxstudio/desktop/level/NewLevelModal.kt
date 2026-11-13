package dev.secondsun.sfxstudio.desktop.level

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.secondsun.sfxstudio.core.level.MapGrid
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun NewLevelModal(studio: StudioState) {
    if (!studio.showNewLevelModal) return

    val initialAtlasPath =
        remember {
            studio.openSessions
                .filterIsInstance<AtlasEditorState>()
                .firstNotNullOfOrNull { it.documentFile?.absolutePath }
                ?: ""
        }

    var levelName by remember { mutableStateOf("courtyard") }
    var atlasPath by remember { mutableStateOf(initialAtlasPath) }
    var colsText by remember { mutableStateOf("8") }
    var rowsText by remember { mutableStateOf("8") }

    val cols = colsText.toIntOrNull()?.coerceIn(MapGrid.MIN_DIM, MapGrid.MAX_DIM) ?: 8
    val rows = rowsText.toIntOrNull()?.coerceIn(MapGrid.MIN_DIM, MapGrid.MAX_DIM) ?: 8

    Dialog(onDismissRequest = { studio.showNewLevelModal = false }) {
        Surface(
            modifier = Modifier.width(520.dp).wrapContentHeight(),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF22242B),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "New Isometric Level",
                    style = MaterialTheme.typography.h6,
                    color = Color.White,
                )
                Text(
                    text = "Create a Level Document (.sfxlevel) linked to a shared Texture Atlas",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Level Name:", color = Color.White, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = levelName,
                    onValueChange = { levelName = it },
                    singleLine = true,
                    colors =
                        TextFieldDefaults.outlinedTextFieldColors(
                            textColor = Color.White,
                            cursorColor = Color.White,
                            focusedBorderColor = Color(0xFF3B82F6),
                            unfocusedBorderColor = Color(0xFF4B5563),
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text("Shared Atlas (.sfxproj / .sfxatlas):", color = Color.White, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = atlasPath,
                        onValueChange = { atlasPath = it },
                        singleLine = true,
                        colors =
                            TextFieldDefaults.outlinedTextFieldColors(
                                textColor = Color.White,
                                cursorColor = Color.White,
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedBorderColor = Color(0xFF4B5563),
                            ),
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val dialog = FileDialog(null as Frame?, "Select Texture Atlas", FileDialog.LOAD)
                            dialog.setFilenameFilter { _, name ->
                                name.endsWith(".sfxproj", ignoreCase = true) ||
                                    name.endsWith(".sfxatlas", ignoreCase = true)
                            }
                            dialog.isVisible = true
                            val picked = dialog.file?.let { File(dialog.directory, it) }
                            if (picked != null) {
                                atlasPath = picked.absolutePath
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3B82F6)),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text("Browse...", color = Color.White, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Columns (${MapGrid.MIN_DIM}..${MapGrid.MAX_DIM}):", color = Color.White, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = colsText,
                            onValueChange = { colsText = it.filter { ch -> ch.isDigit() } },
                            singleLine = true,
                            colors =
                                TextFieldDefaults.outlinedTextFieldColors(
                                    textColor = Color.White,
                                    cursorColor = Color.White,
                                    focusedBorderColor = Color(0xFF3B82F6),
                                    unfocusedBorderColor = Color(0xFF4B5563),
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Rows (${MapGrid.MIN_DIM}..${MapGrid.MAX_DIM}):", color = Color.White, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = rowsText,
                            onValueChange = { rowsText = it.filter { ch -> ch.isDigit() } },
                            singleLine = true,
                            colors =
                                TextFieldDefaults.outlinedTextFieldColors(
                                    textColor = Color.White,
                                    cursorColor = Color.White,
                                    focusedBorderColor = Color(0xFF3B82F6),
                                    unfocusedBorderColor = Color(0xFF4B5563),
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { studio.showNewLevelModal = false }) {
                        Text("Cancel", color = Color(0xFF94A3B8))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
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
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF059669)),
                    ) {
                        Text("Create Level", color = Color.White)
                    }
                }
            }
        }
    }
}
