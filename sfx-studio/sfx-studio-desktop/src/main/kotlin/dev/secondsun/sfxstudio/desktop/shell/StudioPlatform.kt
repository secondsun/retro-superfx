package dev.secondsun.sfxstudio.desktop.shell

import androidx.compose.runtime.Composable
import java.io.File

/**
 * Host platform seam isolating OS file dialogs, directory pickers, clipboard access,
 * user preferences, and platform appearance detection from [StudioState] and editor sessions.
 *
 * Desktop builds inject `AwtStudioPlatform`; Android builds inject `AndroidStudioPlatform`;
 * headless unit tests inject an in-memory fake.
 */
interface StudioPlatform {
    val isMacOs: Boolean

    fun chooseOpenFile(
        title: String,
        initialDir: String? = null,
        allowedExtensions: Set<String> = emptySet(),
    ): File?

    fun chooseSaveFile(
        title: String,
        defaultFileName: String,
        initialDir: String? = null,
    ): File?

    fun chooseDirectory(
        title: String,
        initialDir: String? = null,
    ): File?

    /**
     * Non-blocking file open request. Defaults to delegating to synchronous [chooseOpenFile]
     * on Desktop and headless unit tests; overridden on Android to present a non-blocking
     * Compose/SAF document picker without blocking the main Looper thread.
     */
    fun requestOpenFile(
        title: String,
        initialDir: String? = null,
        allowedExtensions: Set<String> = emptySet(),
        onResult: (File?) -> Unit,
    ) {
        onResult(chooseOpenFile(title, initialDir, allowedExtensions))
    }

    /**
     * Non-blocking file save request. Defaults to delegating to synchronous [chooseSaveFile].
     */
    fun requestSaveFile(
        title: String,
        defaultFileName: String,
        initialDir: String? = null,
        onResult: (File?) -> Unit,
    ) {
        onResult(chooseSaveFile(title, defaultFileName, initialDir))
    }

    /**
     * Non-blocking directory picker request. Defaults to delegating to synchronous [chooseDirectory].
     */
    fun requestDirectory(
        title: String,
        initialDir: String? = null,
        onResult: (File?) -> Unit,
    ) {
        onResult(chooseDirectory(title, initialDir))
    }

    fun copyToClipboard(text: String)

    fun getPreference(
        key: String,
        default: String? = null,
    ): String?

    fun setPreference(
        key: String,
        value: String,
    )

    /**
     * Queries the host OS for dark-mode preference (`true` = dark, `false` = light, `null` = unknown).
     */
    fun detectSystemDarkMode(): Boolean? = null

    /**
     * Queries the host OS for its active sRGB accent color (`0xFFRRGGBB`), or `null` if unavailable.
     */
    fun detectSystemAccentColorArgb(): Int? = null

    /**
     * Optional Composable overlay hook rendered inside `StudioRootContent` under `ProvideStudioTheme`
     * (used on Android/ChromeOS to host non-blocking workspace & SAF file dialogs).
     */
    @Composable
    fun RenderPlatformOverlays() = Unit
}
