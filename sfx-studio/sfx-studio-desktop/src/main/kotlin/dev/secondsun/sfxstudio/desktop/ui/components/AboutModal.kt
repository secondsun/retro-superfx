package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.shell.StudioState

@Composable
fun AboutModal(studioState: StudioState) {
    if (!studioState.showAboutModal) return

    Dialog(onDismissRequest = { studioState.showAboutModal = false }) {
        Surface(
            modifier = Modifier.width(520.dp).height(440.dp),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF22242B),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    text = "SuperFX Studio",
                    style = MaterialTheme.typography.h5,
                    color = Color.White,
                )

                Text(
                    text = "Version 1.0 — Graphical Atlas, Palette & Multi-Document Studio for SuperFX",
                    color = Color(0xFF93C5FD),
                    fontSize = 12.sp,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .background(Color(0xFF16181D), RoundedCornerShape(4.dp))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "About This Program",
                        style = MaterialTheme.typography.subtitle2,
                        color = Color.White,
                    )
                    Text(
                        text =
                            "SuperFX Studio provides an extensible multi-document workspace for SuperFX game development. " +
                                "It converts, layouts, and packs power-of-two source bitmap graphics into an unpacked " +
                                "1-byte-per-texel Texture Atlas (.bin), a 32-byte SNES BGR555 palette (.pal with $0001 black), " +
                                "and ca65 assembly include file (.i) with texture_descriptor tables for SuperFX polygon rasterization.",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Open Source License",
                        style = MaterialTheme.typography.subtitle2,
                        color = Color.White,
                    )
                    Text(
                        text =
                            """
                            Licensed under the Apache License, Version 2.0 (the "License");
                            you may not use this file except in compliance with the License.
                            You may obtain a copy of the License at

                                http://www.apache.org/licenses/LICENSE-2.0

                            Unless required by applicable law or agreed to in writing, software
                            distributed under the License is distributed on an "AS IS" BASIS,
                            WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
                            See the License for the specific language governing permissions and
                            limitations under the License.
                            """.trimIndent(),
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Third-Party Components",
                        style = MaterialTheme.typography.subtitle2,
                        color = Color.White,
                    )
                    Text(
                        text =
                            "• JetBrains Compose Multiplatform Desktop (Apache 2.0)\n" +
                                "• JetBrains Skiko Graphics Engine (Apache 2.0)\n" +
                                "• Kotlin Standard Library (Apache 2.0)\n" +
                                "• Google Gson (Apache 2.0)\n" +
                                "• JUnit Jupiter (EPL 2.0)",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { studioState.showAboutModal = false },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3B82F6)),
                    ) {
                        Text("Close", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun AboutModal(state: AtlasEditorState) {
    if (state.studio != null) {
        AboutModal(state.studio)
    } else {
        if (!state.showAboutModal) return
        Dialog(onDismissRequest = { state.showAboutModal = false }) {
            Surface(
                modifier = Modifier.width(520.dp).height(440.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF22242B),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Text(
                        text = "SuperFX Studio",
                        style = MaterialTheme.typography.h5,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { state.showAboutModal = false }) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
