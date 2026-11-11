package dev.secondsun.sfxstudio.desktop.ui.components

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
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun CloseTabConfirmationModal(studioState: StudioState) {
    val pendingIdx = studioState.pendingCloseSessionIndex ?: return
    val session =
        studioState.openSessions.getOrNull(pendingIdx) ?: run {
            studioState.pendingCloseSessionIndex = null
            return
        }

    Dialog(onDismissRequest = { studioState.pendingCloseSessionIndex = null }) {
        Surface(
            modifier = Modifier.width(460.dp).wrapContentHeight(),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF22242B),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Unsaved Changes",
                    style = MaterialTheme.typography.h6,
                    color = Color.White,
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "You have unsaved changes in '${session.displayTitle}'. Do you want to save before closing this tab?",
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Cancel
                    TextButton(
                        onClick = { studioState.pendingCloseSessionIndex = null },
                    ) {
                        Text("Cancel", color = Color(0xFF94A3B8))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Discard and Close
                    OutlinedButton(
                        onClick = {
                            studioState.closeSession(pendingIdx)
                        },
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFEF4444),
                            ),
                    ) {
                        Text("Don't Save", color = Color(0xFFEF4444))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Save and Close
                    Button(
                        onClick = {
                            val saved =
                                if (session.documentFile != null) {
                                    session.save()
                                } else {
                                    val dialog = FileDialog(null as Frame?, "Save Document", FileDialog.SAVE)
                                    dialog.file = "${session.displayTitle}.sfxproj"
                                    dialog.isVisible = true
                                    val file = dialog.file?.let { File(dialog.directory, it) }
                                    if (file != null) {
                                        session.saveAs(file)
                                    } else {
                                        false
                                    }
                                }
                            if (saved) {
                                studioState.closeSession(pendingIdx)
                            }
                        },
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = Color(0xFF3B82F6),
                            ),
                    ) {
                        Text("Save", color = Color.White)
                    }
                }
            }
        }
    }
}
