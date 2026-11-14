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

@Composable
fun ExitConfirmationModal(
    studioState: StudioState,
    onConfirmExit: () -> Unit,
) {
    if (!studioState.showExitConfirmationModal) return

    val dirtySessions = studioState.openSessions.filter { it.hasUnsavedChanges }
    val dirtyTitles =
        if (dirtySessions.isNotEmpty()) {
            dirtySessions.joinToString(", ") { "'${it.displayTitle}'" }
        } else {
            "open documents"
        }

    Dialog(onDismissRequest = { studioState.showExitConfirmationModal = false }) {
        Surface(
            modifier = Modifier.width(480.dp).wrapContentHeight(),
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
                    text = "You have unsaved changes in $dirtyTitles. Do you want to save before exiting?",
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
                    TextButton(
                        onClick = { studioState.showExitConfirmationModal = false },
                    ) {
                        Text("Cancel", color = Color(0xFF94A3B8))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedButton(
                        onClick = {
                            studioState.showExitConfirmationModal = false
                            onConfirmExit()
                        },
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFEF4444),
                            ),
                    ) {
                        Text("Discard & Exit", color = Color(0xFFEF4444))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (studioState.saveAllDirtySessions()) {
                                studioState.showExitConfirmationModal = false
                                onConfirmExit()
                            }
                        },
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = Color(0xFF3B82F6),
                            ),
                    ) {
                        Text("Save & Exit", color = Color.White)
                    }
                }
            }
        }
    }
}
