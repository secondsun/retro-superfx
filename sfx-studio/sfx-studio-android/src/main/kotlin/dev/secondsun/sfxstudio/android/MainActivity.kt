package dev.secondsun.sfxstudio.android

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.secondsun.sfxstudio.core.image.ArgbImage
import dev.secondsun.sfxstudio.core.image.StudioImageCodec
import dev.secondsun.sfxstudio.desktop.StudioRootContent
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import dev.secondsun.sfxstudio.desktop.ui.theme.AccentColorPreset
import dev.secondsun.sfxstudio.desktop.ui.theme.ThemeModePreference
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private var pendingSafOpenCallback: ((File?) -> Unit)? = null
    private var pendingSafSaveCallback: ((File?) -> Unit)? = null
    private var pendingSafSaveFileName: String = "untitled.sfxproj"

    private lateinit var studioPlatform: AndroidStudioPlatform
    private var studioStateRef: StudioState? = null

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val callback = pendingSafOpenCallback
            pendingSafOpenCallback = null
            if (callback == null) return@registerForActivityResult
            if (uri == null) {
                callback(null)
                return@registerForActivityResult
            }
            val imported = copySafUriToWorkspaceImports(uri)
            callback(imported)
        }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
            val callback = pendingSafSaveCallback
            val fallbackName = pendingSafSaveFileName
            pendingSafSaveCallback = null
            if (callback == null) return@registerForActivityResult
            if (uri == null) {
                callback(null)
                return@registerForActivityResult
            }
            val displayName = queryDisplayName(uri) ?: fallbackName
            val exportsDir = File(studioPlatform.workspaceRoot, "Exports").apply { mkdirs() }
            val stagedFile = File(exportsDir, displayName)
            callback(stagedFile)
            if (stagedFile.isFile) {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        stagedFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerAndroidBitmapCodec()
        enableEdgeToEdge()

        val workspaceRoot = File(filesDir, "workspace").apply { mkdirs() }
        val starterFiles = seedBundledSamplesIfNeeded(workspaceRoot)

        studioPlatform =
            AndroidStudioPlatform(
                context = this,
                workspaceRoot = workspaceRoot,
                onLaunchSystemOpenPicker = { allowedExts, onResult ->
                    pendingSafOpenCallback = onResult
                    val mimeTypes = resolveMimeTypesForExtensions(allowedExts)
                    openDocumentLauncher.launch(mimeTypes)
                },
                onLaunchSystemSavePicker = { suggestedName, onResult ->
                    pendingSafSaveFileName = suggestedName
                    pendingSafSaveCallback = onResult
                    createDocumentLauncher.launch(suggestedName)
                },
            )

        val state =
            StudioState(
                initialFiles = starterFiles,
                platform = studioPlatform,
            )
        studioStateRef = state

        setContent {
            val isDark = state.resolvedColors.isDark
            LaunchedEffect(isDark) {
                val barStyle =
                    if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        )
                    }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
            }

            val systemDensity = LocalDensity.current
            val effectiveDensity =
                remember(systemDensity, state.uiScaleMultiplier) {
                    Density(
                        density = systemDensity.density * state.uiScaleMultiplier,
                        fontScale = systemDensity.fontScale * state.uiScaleMultiplier,
                    )
                }

            BackHandler(enabled = state.hasAnyUnsavedChanges && !state.showExitConfirmationModal) {
                state.showExitConfirmationModal = true
            }

            CompositionLocalProvider(LocalDensity provides effectiveDensity) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(state.resolvedColors.bgApp)
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    StudioRootContent(
                        studioState = state,
                        onConfirmExit = { finish() },
                    )
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        studioStateRef?.refreshSystemAppearance()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val state = studioStateRef ?: return
            if (state.themeModePreference == ThemeModePreference.SYSTEM ||
                state.accentColorPreset == AccentColorPreset.SYSTEM
            ) {
                state.refreshSystemAppearance()
            }
        }
    }

    private fun registerAndroidBitmapCodec() {
        StudioImageCodec.registerBackend(
            object : StudioImageCodec.Backend {
                override fun read(file: File): ArgbImage? {
                    val opts =
                        BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inPremultiplied = false
                        }
                    val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
                    val w = bmp.width
                    val h = bmp.height
                    val hasAlpha = bmp.hasAlpha()
                    val pixels = IntArray(w * h)
                    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                    bmp.recycle()
                    return ArgbImage(width = w, height = h, pixels = pixels, hasAlpha = hasAlpha)
                }

                override fun writePng(
                    image: ArgbImage,
                    destinationFile: File,
                ) {
                    destinationFile.parentFile?.mkdirs()
                    val bmp = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
                    FileOutputStream(destinationFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    bmp.recycle()
                }
            },
        )
    }

    private fun seedBundledSamplesIfNeeded(workspaceRoot: File): List<File> {
        val bundledRelativePaths =
            listOf(
                "IsoRPGDemo/DEMO_ATLAS.sfxproj",
                "IsoRPGDemo/demo_level.sfxlevel",
                "ProtagonistRoom/PROTAGONIST_ROOM_ATLAS.sfxproj",
                "ProtagonistRoom/protagonist_room.sfxlevel",
                "IsoEngine/TEXTURES.sfxproj",
            )
        for (rel in bundledRelativePaths) {
            val target = File(workspaceRoot, rel)
            if (!target.exists()) {
                try {
                    assets.open("samples/$rel").use { input ->
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            input.copyTo(out)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        // Seed sample power-of-two PNG tile for immediate Import... & Palette Remapping testing
        val sampleTile = File(workspaceRoot, "SampleTiles/stained_glass_window.png")
        if (!sampleTile.exists()) {
            try {
                val img = ArgbImage(32, 32)
                for (y in 0 until 32) {
                    for (x in 0 until 32) {
                        val border = x < 2 || x >= 30 || y < 2 || y >= 30 || x == 15 || y == 15
                        val argb =
                            if (border) {
                                0xFF282034.toInt()
                            } else {
                                val r = (64 + (x * 6)).coerceAtMost(248)
                                val g = (40 + (y * 6)).coerceAtMost(248)
                                val b = (140 + ((x + y) * 2)).coerceAtMost(248)
                                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        img.setRGB(x, y, argb)
                    }
                }
                StudioImageCodec.writePng(img, sampleTile)
            } catch (_: Exception) {
            }
        }

        File(workspaceRoot, "Exports").mkdirs()
        File(workspaceRoot, "Imports").mkdirs()

        val demoAtlas = File(workspaceRoot, "IsoRPGDemo/DEMO_ATLAS.sfxproj")
        val demoLevel = File(workspaceRoot, "IsoRPGDemo/demo_level.sfxlevel")
        return listOf(demoAtlas, demoLevel).filter { it.isFile }
    }

    private fun copySafUriToWorkspaceImports(uri: Uri): File? =
        try {
            val displayName = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}"
            val cleanName = displayName.replace('/', '_').replace('\\', '_')
            val importsDir = File(studioPlatform.workspaceRoot, "Imports").apply { mkdirs() }
            val dest = File(importsDir, cleanName)
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out ->
                    input.copyTo(out)
                }
            } ?: return null
            dest
        } catch (_: Exception) {
            null
        }

    private fun queryDisplayName(uri: Uri): String? =
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }

    private fun resolveMimeTypesForExtensions(allowedExts: Set<String>): Array<String> {
        if (allowedExts.isEmpty()) return arrayOf("*/*")
        val normalized = allowedExts.map { it.lowercase() }.toSet()
        val imageExts = setOf("png", "bmp", "gif", "jpg", "jpeg", "webp")
        return if (normalized.all { it in imageExts }) {
            arrayOf("image/*")
        } else {
            arrayOf("*/*")
        }
    }
}
