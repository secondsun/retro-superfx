package dev.secondsun.sfxstudio.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import dev.secondsun.sfxstudio.desktop.ui.PlatformDpi
import dev.secondsun.sfxstudio.desktop.ui.theme.AccentColorPreset
import dev.secondsun.sfxstudio.desktop.ui.theme.ThemeModePreference
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
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

        fun menuShortcut(
            key: Key,
            shift: Boolean = false,
        ): KeyShortcut =
            if (studioState.platform.isMacOs) {
                KeyShortcut(key, meta = true, shift = shift)
            } else {
                KeyShortcut(key, ctrl = true, shift = shift)
            }

        val windowState =
            rememberWindowState(
                width = 1280.dp,
                height = 840.dp,
                position = WindowPosition(Alignment.Center),
            )

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
                            (1280 * detected).dp,
                            (840 * detected).dp,
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
            onPreviewKeyEvent = { event ->
                val primaryMod = event.isCtrlPressed || event.isMetaPressed
                if (event.type == KeyEventType.KeyDown && primaryMod && event.key == Key.Y) {
                    studioState.redoActiveSession()
                    true
                } else {
                    false
                }
            },
        ) {
            val systemDensity = LocalDensity.current
            val effectiveDensity =
                remember(systemDensity, studioState.uiScaleMultiplier) {
                    Density(
                        density = systemDensity.density * studioState.uiScaleMultiplier,
                        fontScale = systemDensity.fontScale * studioState.uiScaleMultiplier,
                    )
                }

            MenuBar {
                Menu("File", mnemonic = 'F') {
                    Item("New Atlas", shortcut = menuShortcut(Key.N)) {
                        studioState.newAtlasSession()
                    }
                    Item("New Level...", shortcut = menuShortcut(Key.N, shift = true)) {
                        studioState.showNewLevelModal = true
                    }
                    Item("Open Document...", shortcut = menuShortcut(Key.O)) {
                        studioState.promptOpenDocument()
                    }
                    Item("Save", shortcut = menuShortcut(Key.S)) {
                        studioState.requestSaveActiveSession()
                    }
                    Item("Save As...", shortcut = menuShortcut(Key.S, shift = true)) {
                        studioState.requestSaveActiveSessionAs()
                    }
                    Item("Close Tab", shortcut = menuShortcut(Key.W)) {
                        if (studioState.openSessions.isNotEmpty()) {
                            studioState.requestCloseSession(studioState.activeSessionIndex)
                        }
                    }
                    Separator()
                    Item("Export Assets...", shortcut = menuShortcut(Key.E)) {
                        (studioState.activeSession as? AtlasEditorState)?.showExportModal = true
                    }
                    Separator()
                    Item("Import Bitmap...", shortcut = menuShortcut(Key.I)) {
                        (studioState.activeSession as? AtlasEditorState)?.promptImportFile()
                    }
                    Separator()
                    Item("About") {
                        studioState.showAboutModal = true
                    }
                    Separator()
                    Item("Exit", shortcut = menuShortcut(Key.Q)) {
                        if (studioState.hasAnyUnsavedChanges) {
                            studioState.showExitConfirmationModal = true
                        } else {
                            exitProcess(0)
                        }
                    }
                }

                Menu("Edit", mnemonic = 'E') {
                    Item(
                        "Undo",
                        enabled = activeSession?.canUndo == true,
                        shortcut = menuShortcut(Key.Z),
                    ) {
                        studioState.undoActiveSession()
                    }
                    Item(
                        "Redo",
                        enabled = activeSession?.canRedo == true,
                        shortcut = menuShortcut(Key.Z, shift = true),
                    ) {
                        studioState.redoActiveSession()
                    }
                }

                Menu("View", mnemonic = 'V') {
                    Item("Zoom In (UI)", shortcut = menuShortcut(Key.Equals)) {
                        studioState.zoomInUi()
                    }
                    Item("Zoom Out (UI)", shortcut = menuShortcut(Key.Minus)) {
                        studioState.zoomOutUi()
                    }
                    Item("Reset UI Scale (100%)", shortcut = menuShortcut(Key.Zero)) {
                        studioState.resetUiScale()
                    }
                    Separator()
                    Item("UI Scale: 100%") { studioState.setUiScale(1.0f) }
                    Item("UI Scale: 125%") { studioState.setUiScale(1.25f) }
                    Item("UI Scale: 150%") { studioState.setUiScale(1.5f) }
                    Item("UI Scale: 175%") { studioState.setUiScale(1.75f) }
                    Item("UI Scale: 200%") { studioState.setUiScale(2.0f) }
                    Item("UI Scale: 250%") { studioState.setUiScale(2.5f) }
                    Separator()
                    Menu("Appearance") {
                        for (mode in ThemeModePreference.entries) {
                            val activeMark = if (studioState.themeModePreference == mode) "✓ " else ""
                            Item("${activeMark}Theme: ${mode.displayName}") {
                                studioState.updateThemeModePreference(mode)
                            }
                        }
                    }
                    Menu("Accent Color") {
                        for (preset in AccentColorPreset.entries) {
                            val activeMark = if (studioState.accentColorPreset == preset) "✓ " else ""
                            Item("$activeMark${preset.displayName}") {
                                studioState.updateAccentColorPreset(preset)
                            }
                        }
                    }
                }
            }

            // Live refresh OS dark/light mode & system accent whenever window regains focus
            DisposableEffect(window) {
                val listener =
                    object : WindowFocusListener {
                        override fun windowGainedFocus(e: WindowEvent?) {
                            if (studioState.themeModePreference == ThemeModePreference.SYSTEM ||
                                studioState.accentColorPreset == AccentColorPreset.SYSTEM
                            ) {
                                studioState.refreshSystemAppearance()
                            }
                        }

                        override fun windowLostFocus(e: WindowEvent?) = Unit
                    }
                window.addWindowFocusListener(listener)
                onDispose {
                    window.removeWindowFocusListener(listener)
                }
            }

            CompositionLocalProvider(LocalDensity provides effectiveDensity) {
                StudioRootContent(
                    studioState = studioState,
                    onConfirmExit = ::exitApplication,
                )
            }
        }
    }
}
