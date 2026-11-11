package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState

@Composable
fun UnassignPromptModal(state: AtlasEditorState) {
    val slot = state.unassignPromptSlot ?: return
    var targetSlot by remember { mutableStateOf(0) } // Default to transparent or slot 1
    var dropdownExpanded by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { state.unassignPromptSlot = null }) {
        Surface(
            modifier = Modifier.width(440.dp).wrapContentHeight(),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF22242B),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Slot $slot is in Use",
                    style = MaterialTheme.typography.h6,
                    color = Color.White,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text =
                        "Palette slot $slot (${state.palette[slot].hexString}) is currently referenced by pixels in your " +
                            "imported textures. Select a slot to remap those pixels to before unassigning:",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Remap Pixels to:", color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(8.dp))

                    Box {
                        Button(
                            onClick = { dropdownExpanded = true },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF374151)),
                        ) {
                            Text(
                                if (targetSlot == 0) {
                                    "Slot 0 (Transparent)"
                                } else {
                                    "Slot $targetSlot (${state.palette[targetSlot].hexString})"
                                },
                                color = Color.White,
                                fontSize = 11.sp,
                            )
                        }

                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                            modifier = Modifier.background(Color(0xFF2A2D34)),
                        ) {
                            for (s in 0..15) {
                                if (s == slot) continue
                                DropdownMenuItem(
                                    onClick = {
                                        targetSlot = s
                                        dropdownExpanded = false
                                    },
                                ) {
                                    Text(
                                        if (s == 0) {
                                            "Slot 0 (Transparent)"
                                        } else {
                                            "Slot $s (${state.palette[s].hexString})"
                                        },
                                        color = Color.White,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { state.unassignPromptSlot = null }) {
                        Text("Cancel", color = Color.LightGray)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { state.confirmUnassignSlot(slot, targetSlot) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFDC2626)),
                    ) {
                        Text("Remap & Unassign", color = Color.White)
                    }
                }
            }
        }
    }
}
