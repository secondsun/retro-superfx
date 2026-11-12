package dev.secondsun.sfxstudio.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.secondsun.sfxstudio.core.level.SFX_LEVEL_EXTENSION
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorWorkspace
import dev.secondsun.sfxstudio.desktop.level.LevelEditorState
import dev.secondsun.sfxstudio.desktop.level.LevelEditorWorkspace
import dev.secondsun.sfxstudio.desktop.level.NewLevelModal
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import dev.secondsun.sfxstudio.desktop.ui.PlatformDpi
import dev.secondsun.sfxstudio.desktop.ui.components.AboutModal
import dev.secondsun.sfxstudio.desktop.ui.components.CloseTabConfirmationModal
import dev.secondsun.sfxstudio.desktop.ui.components.ExitConfirmationModal
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    PlatformDpi.init()

    val fileArgs =
        args
            .filter { !it.startsWith("-") }
            .map { File(it) }
            .filter { it.exists() && it.isFile }

    application {
        val studioState = remember { StudioState(initialFiles = fileArgs) }

        val windowState =
            rememberWindowState(
                width = 1200.dp,
                height = 800.dp,
                position = WindowPosition(Alignment.Center),
            )

        // Fallback: If AWT failed to apply high-DPI scaling natively on Linux/X11,
        // automatically scale the initial window size and Compose UI density.
        LaunchedEffect(Unit) {
            val detected = PlatformDpi.detectedPlatformScale.toFloat()
            if (detected > 1.2f) {
                val awtScale =
                    try {
                        java.awt.GraphicsEnvironment
                            .getLocalGraphicsEnvironment()
                            .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
                            .toFloat()
                    } catch (_: Throwable) {
                        1.0f
                    }

                if (awtScale <= 1.01f) {
                    windowState.size =
                        DpSize(
                            (1200 * detected).dp,
                            (800 * detected).dp,
                        )
                    studioState.uiScaleMultiplier = detected
                }
            }
        }

        val activeSession = studioState.activeSession
        val windowTitle =
            if (activeSession != null) {
                "SuperFX Studio — ${activeSession.displayTitle}${if (activeSession.hasUnsavedChanges) " *" else ""}"
            } else {
                "SuperFX Studio"
            }

        Window(
            onCloseRequest = {
                if (studioState.hasAnyUnsavedChanges) {
                    studioState.showExitConfirmationModal = true
                } else {
                    exitApplication()
                }
            },
            title = windowTitle,
            state = windowState,
        ) {
            val systemDensity = LocalDensity.current
            val effectiveDensity =
                remember(systemDensity, studioState.uiScaleMultiplier) {
                    Density(
                        density = systemDensity.density * studioState.uiScaleMultiplier,
                        fontScale = systemDensity.fontScale * studioState.uiScaleMultiplier,
                    )
                }

            // --- Native Menu Bar ---
            MenuBar {
                Menu("File", mnemonic = 'F') {
                    Item("New Atlas", shortcut = KeyShortcut(Key.N, ctrl = true)) {
                        studioState.newAtlasSession()
                    }

                    Item("New Level...", shortcut = KeyShortcut(Key.N, ctrl = true, shift = true)) {
                        studioState.showNewLevelModal = true
                    }

                    Item("Open Document...", shortcut = KeyShortcut(Key.O, ctrl = true)) {
                        val dialog = FileDialog(null as Frame?, "Open SuperFX Document", FileDialog.LOAD)
                        dialog.setFilenameFilter { _, name ->
                            name.endsWith(".sfxproj", ignoreCase = true) ||
                                name.endsWith(".sfxatlas", ignoreCase = true) ||
                                name.endsWith(".$SFX_LEVEL_EXTENSION", ignoreCase = true)
                        }
                        dialog.isVisible = true
                        val file = dialog.file?.let { File(dialog.directory, it) }
                        if (file != null) {
                            studioState.openDocument(file)
                        }
                    }

                    Item("Save", shortcut = KeyShortcut(Key.S, ctrl = true)) {
                        val session = studioState.activeSession
                        if (session != null) {
                            if (session.documentFile != null) {
                                session.save()
                            } else {
                                val defaultExt = if (session is LevelEditorState) SFX_LEVEL_EXTENSION else "sfxproj"
                                val dialog = FileDialog(null as Frame?, "Save Document", FileDialog.SAVE)
                                dialog.file = "${session.displayTitle}.$defaultExt"
                                dialog.isVisible = true
                                val file = dialog.file?.let { File(dialog.directory, it) }
                                if (file != null) {
                                    session.saveAs(file)
                                }
                            }
                        }
                    }

                    Item("Save As...") {
                        val session = studioState.activeSession
                        if (session != null) {
                            val defaultExt = if (session is LevelEditorState) SFX_LEVEL_EXTENSION else "sfxproj"
                            val dialog = FileDialog(null as Frame?, "Save Document As", FileDialog.SAVE)
                            dialog.file = "${session.displayTitle}.$defaultExt"
                            dialog.isVisible = true
                            val file = dialog.file?.let { File(dialog.directory, it) }
                            if (file != null) {
                                session.saveAs(file)
                            }
                        }
                    }

                    Item("Close Tab", shortcut = KeyShortcut(Key.W, ctrl = true)) {
                        if (studioState.openSessions.isNotEmpty()) {
                            studioState.requestCloseSession(studioState.activeSessionIndex)
                        }
                    }

                    Separator()

                    Item("Export Assets...", shortcut = KeyShortcut(Key.E, ctrl = true)) {
                        (studioState.activeSession as? AtlasEditorState)?.showExportModal = true
                    }

                    Separator()

                    Item("Import Bitmap...", shortcut = KeyShortcut(Key.I, ctrl = true)) {
                        (studioState.activeSession as? AtlasEditorState)?.promptImportFile()
                    }

                    Separator()

                    Item("About") {
                        studioState.showAboutModal = true
                    }

                    Separator()

                    Item("Exit", shortcut = KeyShortcut(Key.Q, ctrl = true)) {
                        if (studioState.hasAnyUnsavedChanges) {
                            studioState.showExitConfirmationModal = true
                        } else {
                            exitProcess(0)
                        }
                    }
                }

                Menu("View", mnemonic = 'V') {
                    Item("Zoom In (UI)", shortcut = KeyShortcut(Key.Equals, ctrl = true)) {
                        studioState.zoomInUi()
                    }

                    Item("Zoom Out (UI)", shortcut = KeyShortcut(Key.Minus, ctrl = true)) {
                        studioState.zoomOutUi()
                    }

                    Item("Reset UI Scale (100%)", shortcut = KeyShortcut(Key.Zero, ctrl = true)) {
                        studioState.resetUiScale()
                    }

                    Separator()

                    Item("UI Scale: 100%") { studioState.setUiScale(1.0f) }
                    Item("UI Scale: 125%") { studioState.setUiScale(1.25f) }
                    Item("UI Scale: 150%") { studioState.setUiScale(1.5f) }
                    Item("UI Scale: 175%") { studioState.setUiScale(1.75f) }
                    Item("UI Scale: 200%") { studioState.setUiScale(2.0f) }
                    Item("UI Scale: 250%") { studioState.setUiScale(2.5f) }
                }
            }

            // --- Dark Material Theme Application ---
            CompositionLocalProvider(LocalDensity provides effectiveDensity) {
                MaterialTheme(
                    colors =
                        darkColors(
                            primary = Color(0xFF3B82F6),
                            primaryVariant = Color(0xFF1D4ED8),
                            secondary = Color(0xFF10B981),
                            background = Color(0xFF181A1F),
                            surface = Color(0xFF22242B),
                            onPrimary = Color.White,
                            onSecondary = Color.White,
                            onBackground = Color.White,
                            onSurface = Color.White,
                        ),
                ) {
                    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF181A1F))) {
                        // --- Multi-Document Tab Bar ---
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .background(Color(0xFF14161B))
                                    .border(1.dp, Color(0xFF262930))
                                    .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            studioState.openSessions.forEachIndexed { index, session ->
                                val isActive = index == studioState.activeSessionIndex
                                val tabBg = if (isActive) Color(0xFF22242B) else Color(0xFF181A1F)
                                val tabBorder = if (isActive) Color(0xFF3B82F6) else Color(0xFF2E323B)

                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxHeight()
                                            .background(tabBg)
                                            .border(width = 1.dp, color = tabBorder)
                                            .clickable { studioState.selectSession(index) }
                                            .padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val titleText =
                                        "${session.displayTitle}${if (session.hasUnsavedChanges) " *" else ""}"
                                    Text(
                                        text = titleText,
                                        color = if (isActive) Color.White else Color(0xFF94A3B8),
                                        fontSize = 12.sp,
                                    )

                                    // Close button (×)
                                    IconButton(
                                        onClick = { studioState.requestCloseSession(index) },
                                        modifier = Modifier.size(18.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close tab",
                                            tint = if (isActive) Color(0xFFCBD5E1) else Color(0xFF64748B),
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                }
                            }

                            // New Tab (+) Button
                            IconButton(
                                onClick = { studioState.newAtlasSession() },
                                modifier = Modifier.padding(start = 4.dp).size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "New Atlas tab",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        // --- Active Document Workspace ---
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (val session = studioState.activeSession) {
                                is AtlasEditorState -> AtlasEditorWorkspace(session)
                                is LevelEditorState -> LevelEditorWorkspace(session)
                                null -> {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("No document open", color = Color.Gray, fontSize = 14.sp)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Button(onClick = { studioState.newAtlasSession() }) {
                                                Text("Create New Atlas")
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Unknown session type: ${session::class.simpleName}", color = Color.White)
                                    }
                                }
                            }
                        }

                        // --- Studio Status Bar ---
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(26.dp)
                                    .background(Color(0xFF111215))
                                    .border(1.dp, Color(0xFF222428))
                                    .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = studioState.activeSession?.statusMessage ?: "Ready",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val pct = Math.round(effectiveDensity.density * 100)
                                Text(
                                    text = "DPI Scale: $pct%",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp,
                                )

                                Text(
                                    text = studioState.activeSession?.statusSummary ?: "",
                                    color = Color(0xFF64748B),
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }

                    // --- Modals ---
                    AboutModal(studioState)
                    NewLevelModal(studioState)
                    ExitConfirmationModal(studioState, onConfirmExit = ::exitApplication)
                    CloseTabConfirmationModal(studioState)

                    // Studio Error Dialog
                    studioState.errorMessage?.let { errorText ->
                        AlertDialog(
                            onDismissRequest = { studioState.errorMessage = null },
                            title = { Text("Notice", color = Color.White) },
                            text = { Text(errorText, color = Color(0xFFEF4444)) },
                            confirmButton = {
                                Button(
                                    onClick = { studioState.errorMessage = null },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3B82F6)),
                                ) {
                                    Text("OK", color = Color.White)
                                }
                            },
                            backgroundColor = Color(0xFF22242B),
                            shape = RoundedCornerShape(8.dp),
                        )
                    }
                }
            }
        }
    }
}
