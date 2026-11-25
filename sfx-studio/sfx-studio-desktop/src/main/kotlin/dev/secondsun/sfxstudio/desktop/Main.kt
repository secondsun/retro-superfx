package dev.secondsun.sfxstudio.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorTab
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorWorkspace
import dev.secondsun.sfxstudio.desktop.level.LevelCenterViewMode
import dev.secondsun.sfxstudio.desktop.level.LevelEditorState
import dev.secondsun.sfxstudio.desktop.level.LevelEditorWorkspace
import dev.secondsun.sfxstudio.desktop.level.NewLevelModal
import dev.secondsun.sfxstudio.desktop.shell.EditorSession
import dev.secondsun.sfxstudio.desktop.shell.LevelEditMode
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import dev.secondsun.sfxstudio.desktop.shell.TouchModePreference
import dev.secondsun.sfxstudio.desktop.ui.PlatformDpi
import dev.secondsun.sfxstudio.desktop.ui.components.AboutModal
import dev.secondsun.sfxstudio.desktop.ui.components.CloseTabConfirmationModal
import dev.secondsun.sfxstudio.desktop.ui.components.ExitConfirmationModal
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.SegmentItem
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioIconButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioModalDialog
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSegmentedControl
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.AccentColorPreset
import dev.secondsun.sfxstudio.desktop.ui.theme.HairlineEdge
import dev.secondsun.sfxstudio.desktop.ui.theme.ProvideStudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioViewportClass
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioViewportInfo
import dev.secondsun.sfxstudio.desktop.ui.theme.ThemeModePreference
import dev.secondsun.sfxstudio.desktop.ui.theme.drawEdgeHairline
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
                        studioState.saveActiveSession()
                    }
                    Item("Save As...", shortcut = menuShortcut(Key.S, shift = true)) {
                        studioState.saveActiveSessionAs()
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

@Composable
fun StudioRootContent(
    studioState: StudioState,
    onConfirmExit: () -> Unit = {},
) {
    val effectiveDensity = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportClass = StudioViewportClass.fromWidth(maxWidth)
        val touchComfort = studioState.resolveTouchComfort(viewportClass)
        val viewportInfo =
            StudioViewportInfo(
                viewportClass = viewportClass,
                widthDp = maxWidth,
                heightDp = maxHeight,
                touchComfort = touchComfort,
            )

        ProvideStudioTheme(
            viewport = viewportInfo,
            colors = studioState.resolvedColors,
        ) {
            val colors = StudioTheme.colors
            val metrics = StudioTheme.metrics
            val typography = StudioTheme.typography

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(colors.bgApp),
            ) {
                StudioHeader(studioState = studioState)

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (val session = studioState.activeSession) {
                        is AtlasEditorState -> AtlasEditorWorkspace(session)
                        is LevelEditorState -> LevelEditorWorkspace(session)
                        null -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "No document open",
                                        style = typography.bodyRegular,
                                        color = colors.textSecondary,
                                    )
                                    StudioButton(
                                        label = "New Atlas Document",
                                        variant = StudioButtonVariant.Primary,
                                        icon = StudioIcons.DocAtlas,
                                        onClick = { studioState.newAtlasSession() },
                                    )
                                }
                            }
                        }
                        else -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Unknown session type: ${session::class.simpleName}",
                                    style = typography.bodyRegular,
                                    color = colors.textPrimary,
                                )
                            }
                        }
                    }
                }

                // --- Studio Status Bar (Single-edge top hairline, overflow-safe) ---
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(metrics.statusBarHeight)
                            .background(colors.bgCanvas)
                            .drawEdgeHairline(HairlineEdge.TOP, colors.borderSubtle)
                            .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = studioState.activeSession?.statusMessage ?: "Ready",
                        style = typography.caption,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val summary = studioState.activeSession?.statusSummary.orEmpty()
                        if (summary.isNotEmpty()) {
                            Text(
                                text = summary,
                                style = typography.monoCaption,
                                color = colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Box(
                                modifier =
                                    Modifier
                                        .width(1.dp)
                                        .height(12.dp)
                                        .background(colors.borderSubtle),
                            )
                        }

                        val pct = Math.round(effectiveDensity.density * 100)
                        Text(
                            text = "UI $pct%",
                            style = typography.monoCaption,
                            color = if (colors.isDark) colors.accentHover else colors.accentPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }

            // --- Shell Modals ---
            AboutModal(studioState)
            NewLevelModal(studioState)
            ExitConfirmationModal(studioState, onConfirmExit = onConfirmExit)
            CloseTabConfirmationModal(studioState)

            studioState.errorMessage?.let { errorText ->
                StudioModalDialog(
                    onDismissRequest = { studioState.errorMessage = null },
                    title = "Notice",
                    preferredWidth = 480.dp,
                    footerActions = {
                        StudioButton(
                            label = "OK",
                            variant = StudioButtonVariant.Primary,
                            onClick = { studioState.errorMessage = null },
                        )
                    },
                ) {
                    Text(
                        text = errorText,
                        style = typography.bodyRegular,
                        color = colors.semanticErrorText,
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioHeader(studioState: StudioState) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val viewport = StudioTheme.viewport
    val isCompact = viewport.viewportClass == StudioViewportClass.COMPACT
    val isExpanded = viewport.viewportClass == StudioViewportClass.EXPANDED

    var newMenuExpanded by remember { mutableStateOf(false) }
    var scaleMenuExpanded by remember { mutableStateOf(false) }
    var appearanceMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // --- Tier 1: Document Tab Strip + Global Ergonomics ---
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(metrics.tabStripHeight)
                    .background(colors.bgApp)
                    .drawEdgeHairline(HairlineEdge.BOTTOM, colors.borderSubtle)
                    .padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Brand emblem
            Box(
                modifier =
                    Modifier
                        .width(38.dp)
                        .fillMaxHeight()
                        .drawEdgeHairline(HairlineEdge.RIGHT, colors.borderSubtle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = StudioIcons.BrandChip,
                    contentDescription = "SuperFX Studio",
                    tint = colors.accentHover,
                    modifier = Modifier.size(18.dp),
                )
            }

            // Scrollable Document Tabs + [ + ▾ ]
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                studioState.openSessions.forEachIndexed { index, session ->
                    StudioDocumentTab(
                        session = session,
                        isActive = index == studioState.activeSessionIndex,
                        onSelect = { studioState.selectSession(index) },
                        onClose = { studioState.requestCloseSession(index) },
                    )
                }

                // [ + ▾ ] New / Open Quick Menu
                Box(modifier = Modifier.padding(horizontal = 4.dp)) {
                    StudioButton(
                        label = if (isCompact) "+" else "New",
                        variant = StudioButtonVariant.Ghost,
                        compact = true,
                        icon = if (isCompact) null else StudioIcons.Plus,
                        tooltip = "New or Open Document",
                        onClick = { newMenuExpanded = true },
                    )
                    DropdownMenu(
                        expanded = newMenuExpanded,
                        onDismissRequest = { newMenuExpanded = false },
                        modifier = Modifier.background(colors.surface2),
                    ) {
                        DropdownMenuItem(
                            onClick = {
                                newMenuExpanded = false
                                studioState.newAtlasSession()
                            },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(StudioIcons.DocAtlas, null, tint = colors.sliceCyan, modifier = Modifier.size(15.dp))
                                Text("New Atlas Document", style = typography.bodyMedium, color = colors.textPrimary)
                            }
                        }
                        DropdownMenuItem(
                            onClick = {
                                newMenuExpanded = false
                                studioState.showNewLevelModal = true
                            },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(StudioIcons.DocLevel, null, tint = colors.interactEmerald, modifier = Modifier.size(15.dp))
                                Text("New Level Document...", style = typography.bodyMedium, color = colors.textPrimary)
                            }
                        }
                        DropdownMenuItem(
                            onClick = {
                                newMenuExpanded = false
                                studioState.promptOpenDocument()
                            },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(StudioIcons.FolderOpen, null, tint = colors.accentHover, modifier = Modifier.size(15.dp))
                                Text("Open Document...", style = typography.bodyMedium, color = colors.textPrimary)
                            }
                        }
                    }
                }
            }

            // Right-Pinned Global Ergonomics Controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val touchLabel =
                    when (studioState.touchModePreference) {
                        TouchModePreference.AUTO -> if (viewport.touchComfort) "Touch (Auto)" else "Desktop"
                        TouchModePreference.TOUCH -> "Touch"
                        TouchModePreference.COMPACT -> "Compact"
                    }
                if (isCompact) {
                    StudioIconButton(
                        icon = StudioIcons.TouchMode,
                        contentDescription = "Touch Comfort Mode: $touchLabel",
                        selected = viewport.touchComfort,
                        size = metrics.controlHeightSM,
                        onClick = { studioState.cycleTouchModePreference() },
                    )
                } else {
                    StudioButton(
                        label = touchLabel,
                        variant = if (viewport.touchComfort) StudioButtonVariant.Secondary else StudioButtonVariant.Ghost,
                        compact = true,
                        icon = StudioIcons.TouchMode,
                        tooltip = "Cycle Touch Comfort Mode (Auto / Touch / Compact)",
                        onClick = { studioState.cycleTouchModePreference() },
                    )
                }

                // Appearance (Light / Dark / System) & Accent Color Picker
                Box {
                    val themeIcon = if (colors.isDark) StudioIcons.ThemeMoon else StudioIcons.ThemeSun
                    val themeLabel =
                        when (studioState.themeModePreference) {
                            ThemeModePreference.SYSTEM -> if (colors.isDark) "Dark (Auto)" else "Light (Auto)"
                            ThemeModePreference.DARK -> "Dark"
                            ThemeModePreference.LIGHT -> "Light"
                        }
                    StudioButton(
                        label = if (isCompact) "" else themeLabel,
                        variant = StudioButtonVariant.Ghost,
                        compact = true,
                        icon = themeIcon,
                        badgeDotColor = colors.accentPrimary,
                        tooltip = "Appearance & System Accent Color",
                        onClick = { appearanceMenuExpanded = true },
                    )
                    DropdownMenu(
                        expanded = appearanceMenuExpanded,
                        onDismissRequest = { appearanceMenuExpanded = false },
                        modifier = Modifier.background(colors.surface1),
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                            Text(
                                text = "COLOR THEME",
                                style = typography.sectionHeader,
                                color = colors.textMuted,
                            )
                        }
                        for (mode in ThemeModePreference.entries) {
                            val selected = studioState.themeModePreference == mode
                            DropdownMenuItem(
                                onClick = {
                                    studioState.updateThemeModePreference(mode)
                                },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector =
                                            when (mode) {
                                                ThemeModePreference.LIGHT -> StudioIcons.ThemeSun
                                                ThemeModePreference.DARK -> StudioIcons.ThemeMoon
                                                ThemeModePreference.SYSTEM -> StudioIcons.PaletteSwatch
                                            },
                                        contentDescription = null,
                                        tint = if (selected) colors.accentPrimary else colors.textSecondary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = mode.displayName,
                                        style = if (selected) typography.bodyMedium else typography.bodyRegular,
                                        color = if (selected) colors.accentPrimary else colors.textPrimary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (selected) {
                                        Icon(
                                            imageVector = StudioIcons.Check,
                                            contentDescription = null,
                                            tint = colors.accentPrimary,
                                            modifier = Modifier.size(13.dp),
                                        )
                                    }
                                }
                            }
                        }

                        Divider(color = colors.borderSubtle)

                        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                            Text(
                                text = "ACCENT COLOR",
                                style = typography.sectionHeader,
                                color = colors.textMuted,
                            )
                        }
                        for (preset in AccentColorPreset.entries) {
                            val selected = studioState.accentColorPreset == preset
                            val swatchArgb =
                                preset.fallbackArgb
                                    ?: studioState.detectedSystemAccentArgb
                                    ?: dev.secondsun.sfxstudio.desktop.ui.theme.StudioColors.DEFAULT_ACCENT_ARGB
                            DropdownMenuItem(
                                onClick = {
                                    studioState.updateAccentColorPreset(preset)
                                },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(Color(swatchArgb)),
                                    )
                                    Text(
                                        text = preset.displayName,
                                        style = if (selected) typography.bodyMedium else typography.bodyRegular,
                                        color = if (selected) colors.accentPrimary else colors.textPrimary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (selected) {
                                        Icon(
                                            imageVector = StudioIcons.Check,
                                            contentDescription = null,
                                            tint = colors.accentPrimary,
                                            modifier = Modifier.size(13.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Box {
                    val scalePct = Math.round(studioState.uiScaleMultiplier * 100)
                    StudioButton(
                        label = "$scalePct%",
                        variant = StudioButtonVariant.Ghost,
                        compact = true,
                        icon = if (isCompact) null else StudioIcons.UiScale,
                        tooltip = "Adjust UI Scale",
                        onClick = { scaleMenuExpanded = true },
                    )
                    DropdownMenu(
                        expanded = scaleMenuExpanded,
                        onDismissRequest = { scaleMenuExpanded = false },
                        modifier = Modifier.background(colors.surface2),
                    ) {
                        DropdownMenuItem(
                            onClick = {
                                studioState.zoomOutUi()
                            },
                        ) {
                            Text("Zoom Out (-10%)", style = typography.bodyRegular, color = colors.textPrimary)
                        }
                        DropdownMenuItem(
                            onClick = {
                                studioState.zoomInUi()
                            },
                        ) {
                            Text("Zoom In (+10%)", style = typography.bodyRegular, color = colors.textPrimary)
                        }
                        for (preset in listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)) {
                            val pct = Math.round(preset * 100)
                            DropdownMenuItem(
                                onClick = {
                                    scaleMenuExpanded = false
                                    studioState.setUiScale(preset)
                                },
                            ) {
                                Text("Scale: $pct%", style = typography.bodyRegular, color = colors.textPrimary)
                            }
                        }
                    }
                }

                StudioIconButton(
                    icon = StudioIcons.InfoQuestion,
                    contentDescription = "About SuperFX Studio",
                    size = metrics.controlHeightSM,
                    onClick = { studioState.showAboutModal = true },
                )
            }
        }

        // --- Tier 2: Unified Context Command Bar ---
        val session = studioState.activeSession
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(metrics.commandBarHeight)
                    .background(colors.surface1)
                    .drawEdgeHairline(HairlineEdge.BOTTOM, colors.borderSubtle)
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left Cluster: Dock Toggle, File I/O, Undo/Redo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StudioIconButton(
                    icon = if (isCompact) StudioIcons.DockBottom else StudioIcons.DockLeft,
                    contentDescription = if (isCompact) "Toggle Bottom Dock" else "Toggle Left Dock",
                    selected = if (isCompact) studioState.showCompactBottomSheet else studioState.showLeftDock,
                    onClick = {
                        if (isCompact) {
                            studioState.showCompactBottomSheet = !studioState.showCompactBottomSheet
                        } else {
                            studioState.showLeftDock = !studioState.showLeftDock
                        }
                    },
                )

                if (isExpanded) {
                    StudioButton(
                        label = "Open",
                        variant = StudioButtonVariant.Ghost,
                        icon = StudioIcons.FolderOpen,
                        shortcut = "Ctrl+O",
                        onClick = { studioState.promptOpenDocument() },
                    )
                    StudioButton(
                        label = "Save",
                        variant = StudioButtonVariant.Secondary,
                        icon = StudioIcons.Save,
                        badgeDotColor = if (session?.hasUnsavedChanges == true) colors.semanticWarning else null,
                        shortcut = "Ctrl+S",
                        onClick = { studioState.saveActiveSession() },
                    )
                } else {
                    StudioIconButton(
                        icon = StudioIcons.FolderOpen,
                        contentDescription = "Open Document",
                        shortcut = "Ctrl+O",
                        onClick = { studioState.promptOpenDocument() },
                    )
                    StudioIconButton(
                        icon = StudioIcons.Save,
                        contentDescription = "Save Document",
                        tint = if (session?.hasUnsavedChanges == true) colors.semanticWarning else null,
                        shortcut = "Ctrl+S",
                        onClick = { studioState.saveActiveSession() },
                    )
                }

                StudioIconButton(
                    icon = StudioIcons.SaveAs,
                    contentDescription = "Save As...",
                    shortcut = "Ctrl+Shift+S",
                    onClick = { studioState.saveActiveSessionAs() },
                )

                Box(
                    modifier =
                        Modifier
                            .padding(horizontal = 2.dp)
                            .width(1.dp)
                            .height(18.dp)
                            .background(colors.borderSubtle),
                )

                StudioIconButton(
                    icon = StudioIcons.Undo,
                    contentDescription = "Undo",
                    enabled = session?.canUndo == true,
                    shortcut = "Ctrl+Z",
                    onClick = { studioState.undoActiveSession() },
                )
                StudioIconButton(
                    icon = StudioIcons.Redo,
                    contentDescription = "Redo",
                    enabled = session?.canRedo == true,
                    shortcut = "Ctrl+Shift+Z",
                    onClick = { studioState.redoActiveSession() },
                )
            }

            // Center Cluster: Active Session Mode Switchers
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (session) {
                    is AtlasEditorState -> {
                        StudioSegmentedControl(
                            items =
                                listOf(
                                    SegmentItem(AtlasEditorTab.LAYOUT, "Layout"),
                                    SegmentItem(AtlasEditorTab.HEX_PREVIEW, "Hex"),
                                    SegmentItem(
                                        AtlasEditorTab.INDEX_PREVIEW,
                                        if (isCompact) ".i" else "Index (.i)",
                                    ),
                                ),
                            selectedValue = session.activeTab,
                            onSelect = { session.activeTab = it },
                            compact = isCompact,
                        )
                    }
                    is LevelEditorState -> {
                        val entityBadge =
                            if (session.hasGameplayLayer && session.gameplayEntityCount > 0) {
                                session.gameplayEntityCount.toString()
                            } else {
                                null
                            }
                        StudioSegmentedControl(
                            items =
                                listOf(
                                    SegmentItem(LevelEditMode.TERRAIN, "Terrain"),
                                    SegmentItem(
                                        LevelEditMode.GAMEPLAY,
                                        "Gameplay",
                                        badge = entityBadge,
                                    ),
                                ),
                            selectedValue = session.editMode,
                            onSelect = { session.editMode = it },
                            prominent = true,
                            compact = isCompact,
                        )
                        if (session.editMode == LevelEditMode.TERRAIN) {
                            StudioIconButton(
                                icon = if (session.showGameplayOverlay) StudioIcons.EyeVisible else StudioIcons.EyeHidden,
                                contentDescription = "Toggle Gameplay Overlay",
                                selected = session.showGameplayOverlay,
                                onClick = { session.toggleGameplayOverlay() },
                            )
                        }

                        StudioSegmentedControl(
                            items =
                                listOf(
                                    SegmentItem(LevelCenterViewMode.SPLIT, "Split"),
                                    SegmentItem(LevelCenterViewMode.ISO_3D, if (isCompact) "3D" else "3D Iso"),
                                    SegmentItem(LevelCenterViewMode.TOP_DOWN_2D, if (isCompact) "2D" else "2D Grid"),
                                ),
                            selectedValue = session.centerViewMode,
                            onSelect = { session.centerViewMode = it },
                            compact = isCompact,
                        )
                    }
                    else -> {}
                }
            }

            // Right Cluster: Session Primary CTA + Right Dock Toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (session) {
                    is AtlasEditorState -> {
                        if (isCompact) {
                            StudioIconButton(
                                icon = StudioIcons.Import,
                                contentDescription = "Import Bitmap...",
                                onClick = { session.promptImportFile() },
                            )
                        } else {
                            StudioButton(
                                label = "Import...",
                                variant = StudioButtonVariant.Secondary,
                                icon = StudioIcons.Import,
                                onClick = { session.promptImportFile() },
                            )
                        }
                        StudioButton(
                            label = if (isCompact) "Export" else "Export Assets...",
                            variant = StudioButtonVariant.Primary,
                            icon = StudioIcons.Export,
                            onClick = { session.showExportModal = true },
                        )
                    }
                    is LevelEditorState -> {
                        if (isCompact) {
                            StudioIconButton(
                                icon = StudioIcons.Import,
                                contentDescription = "Import ca65 Map (.i)...",
                                onClick = { session.promptImportCa65Map() },
                            )
                        } else {
                            StudioButton(
                                label = "Import .i...",
                                variant = StudioButtonVariant.Secondary,
                                icon = StudioIcons.Import,
                                onClick = { session.promptImportCa65Map() },
                            )
                        }
                        val packLabel =
                            when {
                                isCompact -> "Pack"
                                session.document.gameplay != null -> "Save & Pack (.map+.gmap)"
                                else -> "Save & Pack .map"
                            }
                        StudioButton(
                            label = packLabel,
                            variant = StudioButtonVariant.Primary,
                            icon = StudioIcons.Export,
                            onClick = { session.requestSave() },
                        )
                        if (!isCompact) {
                            StudioIconButton(
                                icon = StudioIcons.DockRight,
                                contentDescription = "Toggle Inspector Dock",
                                selected = studioState.showRightDock,
                                onClick = { studioState.showRightDock = !studioState.showRightDock },
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun StudioDocumentTab(
    session: EditorSession,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val interactionSource = remember(session.sessionId) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val isLevel = session is LevelEditorState

    val bg =
        when {
            isActive -> colors.surface1
            hovered -> colors.surface0
            else -> colors.bgApp
        }

    Row(
        modifier =
            Modifier
                .fillMaxHeight()
                .widthIn(min = 112.dp, max = 220.dp)
                .background(bg)
                .let { mod ->
                    if (isActive) {
                        mod.drawEdgeHairline(HairlineEdge.TOP, colors.accentPrimary, strokeDp = 2.dp)
                    } else {
                        mod
                    }
                }.drawEdgeHairline(HairlineEdge.RIGHT, colors.borderSubtle)
                .hoverable(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onSelect,
                ).padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (isLevel) StudioIcons.DocLevel else StudioIcons.DocAtlas,
            contentDescription = null,
            tint = if (isLevel) colors.interactEmerald else colors.sliceCyan,
            modifier = Modifier.size(14.dp),
        )

        Text(
            text = session.displayTitle,
            style = if (isActive) typography.bodyMedium else typography.bodyRegular,
            color = if (isActive) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        if (session.hasUnsavedChanges) {
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(colors.semanticWarning),
            )
        }

        val closeHoverSource = remember(session.sessionId) { MutableInteractionSource() }
        val closeHovered by closeHoverSource.collectIsHoveredAsState()
        Box(
            modifier =
                Modifier
                    .size(metrics.tabCloseHitSize)
                    .clip(RoundedCornerShape(metrics.radiusXS))
                    .background(if (closeHovered) colors.surface3 else colors.bgApp.copy(alpha = 0f))
                    .hoverable(closeHoverSource)
                    .clickable(
                        interactionSource = closeHoverSource,
                        indication = null,
                        onClick = onClose,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = StudioIcons.Close,
                contentDescription = "Close tab",
                tint = if (closeHovered || isActive) colors.textPrimary else colors.textMuted,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}
