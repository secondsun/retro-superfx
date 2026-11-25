package dev.secondsun.sfxstudio.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.core.atlas.SFX_ATLAS_EXTENSION
import dev.secondsun.sfxstudio.core.atlas.SFX_PROJ_EXTENSION
import dev.secondsun.sfxstudio.core.level.SFX_LEVEL_EXTENSION
import dev.secondsun.sfxstudio.desktop.shell.StudioPlatform
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioBadge
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioModalDialog
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSectionHeader
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioTextField
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import java.io.File

internal sealed class AndroidPickerRequest {
    abstract val title: String
    abstract val initialDir: File

    data class OpenFile(
        override val title: String,
        override val initialDir: File,
        val allowedExtensions: Set<String>,
        val onResult: (File?) -> Unit,
    ) : AndroidPickerRequest()

    data class SaveFile(
        override val title: String,
        override val initialDir: File,
        val defaultFileName: String,
        val onResult: (File?) -> Unit,
    ) : AndroidPickerRequest()

    data class SelectDirectory(
        override val title: String,
        override val initialDir: File,
        val onResult: (File?) -> Unit,
    ) : AndroidPickerRequest()
}

/**
 * Android & ChromeOS implementation of [StudioPlatform].
 *
 * Bridges Studio preferences to `SharedPreferences`, clipboard to `ClipboardManager`,
 * OS dark/light mode to `Configuration.uiMode`, system accent to Android 12+ Material You
 * `system_accent1_500`, and file/folder operations to a non-blocking Studio Workspace picker
 * paired with Android Storage Access Framework (SAF) system document launchers.
 */
class AndroidStudioPlatform(
    private val context: Context,
    val workspaceRoot: File,
    private val onLaunchSystemOpenPicker: (Set<String>, (File?) -> Unit) -> Unit = { _, cb -> cb(null) },
    private val onLaunchSystemSavePicker: (String, (File?) -> Unit) -> Unit = { _, cb -> cb(null) },
) : StudioPlatform {
    override val isMacOs: Boolean = false

    private val sharedPrefs by lazy {
        context.getSharedPreferences("sfx_studio_prefs", Context.MODE_PRIVATE)
    }

    internal var activePickerRequest by mutableStateOf<AndroidPickerRequest?>(null)

    private fun resolveStartDir(initialDir: String?): File {
        val candidate = initialDir?.let { File(it) }
        return if (candidate != null && candidate.exists() && candidate.isDirectory) {
            candidate
        } else {
            workspaceRoot.apply { mkdirs() }
        }
    }

    override fun chooseOpenFile(
        title: String,
        initialDir: String?,
        allowedExtensions: Set<String>,
    ): File? = null

    override fun chooseSaveFile(
        title: String,
        defaultFileName: String,
        initialDir: String?,
    ): File? = null

    override fun chooseDirectory(
        title: String,
        initialDir: String?,
    ): File? = resolveStartDir(initialDir)

    override fun requestOpenFile(
        title: String,
        initialDir: String?,
        allowedExtensions: Set<String>,
        onResult: (File?) -> Unit,
    ) {
        activePickerRequest =
            AndroidPickerRequest.OpenFile(
                title = title,
                initialDir = resolveStartDir(initialDir),
                allowedExtensions = allowedExtensions.map { it.trimStart('.').lowercase() }.toSet(),
                onResult = onResult,
            )
    }

    override fun requestSaveFile(
        title: String,
        defaultFileName: String,
        initialDir: String?,
        onResult: (File?) -> Unit,
    ) {
        activePickerRequest =
            AndroidPickerRequest.SaveFile(
                title = title,
                initialDir = resolveStartDir(initialDir),
                defaultFileName = defaultFileName,
                onResult = onResult,
            )
    }

    override fun requestDirectory(
        title: String,
        initialDir: String?,
        onResult: (File?) -> Unit,
    ) {
        activePickerRequest =
            AndroidPickerRequest.SelectDirectory(
                title = title,
                initialDir = resolveStartDir(initialDir),
                onResult = onResult,
            )
    }

    override fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("SuperFX Studio", text))
    }

    override fun getPreference(
        key: String,
        default: String?,
    ): String? = sharedPrefs.getString(key, default)

    override fun setPreference(
        key: String,
        value: String,
    ) {
        sharedPrefs.edit().putString(key, value).apply()
    }

    override fun detectSystemDarkMode(): Boolean {
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMask == Configuration.UI_MODE_NIGHT_YES
    }

    override fun detectSystemAccentColorArgb(): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val dynamicAccent = context.getColor(android.R.color.system_accent1_500)
                if ((dynamicAccent and 0x00FFFFFF) != 0) {
                    return dynamicAccent or -0x1000000
                }
            } catch (_: Throwable) {
            }
        }
        return try {
            val tv = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.colorAccent, tv, true)) {
                tv.data or -0x1000000
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Composable
    override fun RenderPlatformOverlays() {
        val req = activePickerRequest ?: return
        AndroidWorkspaceFilePickerModal(
            request = req,
            workspaceRoot = workspaceRoot,
            onDismiss = {
                activePickerRequest = null
                when (req) {
                    is AndroidPickerRequest.OpenFile -> req.onResult(null)
                    is AndroidPickerRequest.SaveFile -> req.onResult(null)
                    is AndroidPickerRequest.SelectDirectory -> req.onResult(null)
                }
            },
            onConfirmFile = { chosen ->
                activePickerRequest = null
                when (req) {
                    is AndroidPickerRequest.OpenFile -> req.onResult(chosen)
                    is AndroidPickerRequest.SaveFile -> req.onResult(chosen)
                    is AndroidPickerRequest.SelectDirectory -> req.onResult(chosen)
                }
            },
            onBrowseSystemOpen = { exts ->
                val callback = (req as? AndroidPickerRequest.OpenFile)?.onResult
                activePickerRequest = null
                if (callback != null) {
                    onLaunchSystemOpenPicker(exts, callback)
                }
            },
            onBrowseSystemSave = { suggestedName ->
                val callback = (req as? AndroidPickerRequest.SaveFile)?.onResult
                activePickerRequest = null
                if (callback != null) {
                    onLaunchSystemSavePicker(suggestedName, callback)
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AndroidWorkspaceFilePickerModal(
    request: AndroidPickerRequest,
    workspaceRoot: File,
    onDismiss: () -> Unit,
    onConfirmFile: (File) -> Unit,
    onBrowseSystemOpen: (Set<String>) -> Unit,
    onBrowseSystemSave: (String) -> Unit,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    var currentDir by remember(request) { mutableStateOf(request.initialDir) }
    var fileNameInput by remember(request) {
        mutableStateOf(
            (request as? AndroidPickerRequest.SaveFile)?.defaultFileName.orEmpty(),
        )
    }
    var creatingSubfolder by remember(request) { mutableStateOf(false) }
    var newFolderName by remember(request) { mutableStateOf("") }
    var dirRefreshTick by remember { mutableStateOf(0) }

    val entries =
        remember(currentDir, request, dirRefreshTick) {
            val all = currentDir.listFiles()?.toList().orEmpty()
            val dirs = all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            val files =
                when (request) {
                    is AndroidPickerRequest.SelectDirectory -> emptyList()
                    is AndroidPickerRequest.OpenFile ->
                        all
                            .filter { f ->
                                f.isFile &&
                                    (
                                        request.allowedExtensions.isEmpty() ||
                                            f.extension.lowercase() in request.allowedExtensions
                                    )
                            }.sortedBy { it.name.lowercase() }
                    is AndroidPickerRequest.SaveFile ->
                        all.filter { it.isFile }.sortedBy { it.name.lowercase() }
                }
            dirs + files
        }

    val quickFolders =
        remember(workspaceRoot, dirRefreshTick) {
            buildList {
                add("Workspace" to workspaceRoot)
                for (sub in listOf("IsoRPGDemo", "ProtagonistRoom", "IsoEngine", "SampleTiles", "Exports")) {
                    val dir = File(workspaceRoot, sub)
                    if (dir.isDirectory) add(sub to dir)
                }
            }
        }

    val relativePath =
        remember(currentDir, workspaceRoot) {
            val rootPath = workspaceRoot.absolutePath
            val curPath = currentDir.absolutePath
            if (curPath == rootPath) {
                "workspace/"
            } else if (curPath.startsWith("$rootPath/")) {
                "workspace/${curPath.removePrefix("$rootPath/")}/"
            } else {
                curPath
            }
        }

    StudioModalDialog(
        onDismissRequest = onDismiss,
        title = request.title,
        subtitle = relativePath,
        preferredWidth = 620.dp,
        footerLeading = {
            when (request) {
                is AndroidPickerRequest.OpenFile -> {
                    StudioButton(
                        label = "System Files...",
                        variant = StudioButtonVariant.Secondary,
                        icon = StudioIcons.Import,
                        onClick = { onBrowseSystemOpen(request.allowedExtensions) },
                    )
                }
                is AndroidPickerRequest.SaveFile -> {
                    StudioButton(
                        label = "System Files...",
                        variant = StudioButtonVariant.Secondary,
                        icon = StudioIcons.Export,
                        onClick = {
                            val targetName = fileNameInput.trim().ifEmpty { request.defaultFileName }
                            onBrowseSystemSave(targetName)
                        },
                    )
                }
                is AndroidPickerRequest.SelectDirectory -> {}
            }
        },
        footerActions = {
            StudioButton(
                label = "Cancel",
                variant = StudioButtonVariant.Ghost,
                onClick = onDismiss,
            )
            when (request) {
                is AndroidPickerRequest.SaveFile -> {
                    StudioButton(
                        label = "Save Here",
                        variant = StudioButtonVariant.Primary,
                        icon = StudioIcons.Save,
                        enabled = fileNameInput.trim().isNotEmpty(),
                        onClick = {
                            val clean = fileNameInput.trim()
                            if (clean.isNotEmpty()) {
                                currentDir.mkdirs()
                                onConfirmFile(File(currentDir, clean))
                            }
                        },
                    )
                }
                is AndroidPickerRequest.SelectDirectory -> {
                    StudioButton(
                        label = "Select Folder",
                        variant = StudioButtonVariant.Primary,
                        icon = StudioIcons.Check,
                        onClick = {
                            currentDir.mkdirs()
                            onConfirmFile(currentDir)
                        },
                    )
                }
                is AndroidPickerRequest.OpenFile -> {}
            }
        },
    ) {
        // Quick-Jump Workspace Folders + Parent / New Folder Controls
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val parentDir = currentDir.parentFile
            if (parentDir != null && currentDir.absolutePath != workspaceRoot.absolutePath) {
                StudioButton(
                    label = "Up (..)",
                    variant = StudioButtonVariant.Secondary,
                    compact = true,
                    onClick = { currentDir = parentDir },
                )
            }
            for ((label, dir) in quickFolders) {
                val active = currentDir.absolutePath == dir.absolutePath
                StudioButton(
                    label = label,
                    variant = if (active) StudioButtonVariant.Primary else StudioButtonVariant.Ghost,
                    compact = true,
                    onClick = { currentDir = dir },
                )
            }
            StudioButton(
                label = "+ Folder",
                variant = StudioButtonVariant.Ghost,
                compact = true,
                onClick = { creatingSubfolder = !creatingSubfolder },
            )
        }

        if (creatingSubfolder) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StudioTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = "new_folder_name",
                    monospace = true,
                    modifier = Modifier.weight(1f),
                )
                StudioButton(
                    label = "Create",
                    variant = StudioButtonVariant.Secondary,
                    enabled = newFolderName.trim().isNotEmpty(),
                    onClick = {
                        val clean = newFolderName.trim().replace('/', '_').replace('\\', '_')
                        if (clean.isNotEmpty()) {
                            val created = File(currentDir, clean)
                            created.mkdirs()
                            currentDir = created
                            newFolderName = ""
                            creatingSubfolder = false
                            dirRefreshTick++
                        }
                    },
                )
            }
        }

        if (request is AndroidPickerRequest.SaveFile) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("File Name", style = typography.bodyMedium, color = colors.textSecondary)
                StudioTextField(
                    value = fileNameInput,
                    onValueChange = { fileNameInput = it },
                    placeholder = request.defaultFileName,
                    monospace = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        StudioSectionHeader(
            title = if (request is AndroidPickerRequest.SelectDirectory) "Subfolders" else "Workspace Files",
            badge = "${entries.size}",
        )

        val listShape = RoundedCornerShape(metrics.radiusSM)
        if (entries.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(listShape)
                        .background(colors.surface0, listShape)
                        .border(1.dp, colors.borderSubtle, listShape)
                        .padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No matching items in this folder. Switch folders above or tap 'System Files...'.",
                    style = typography.caption,
                    color = colors.textMuted,
                )
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 300.dp)
                        .clip(listShape)
                        .background(colors.surface0, listShape)
                        .border(1.dp, colors.borderSubtle, listShape)
                        .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(entries, key = { it.absolutePath }) { item ->
                    val ext = item.extension.lowercase()
                    val rowIcon =
                        when {
                            item.isDirectory -> StudioIcons.FolderOpen
                            ext == SFX_LEVEL_EXTENSION -> StudioIcons.DocLevel
                            ext == SFX_PROJ_EXTENSION || ext == SFX_ATLAS_EXTENSION -> StudioIcons.DocAtlas
                            else -> StudioIcons.Import
                        }
                    val iconTint =
                        when {
                            item.isDirectory -> colors.accentHover
                            ext == SFX_LEVEL_EXTENSION -> colors.interactEmerald
                            ext == SFX_PROJ_EXTENSION || ext == SFX_ATLAS_EXTENSION -> colors.sliceCyan
                            else -> colors.aliasAmber
                        }
                    val rowShape = RoundedCornerShape(metrics.radiusXS)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(rowShape)
                                .background(colors.surface1, rowShape)
                                .border(1.dp, colors.borderSubtle, rowShape)
                                .clickable {
                                    if (item.isDirectory) {
                                        currentDir = item
                                    } else {
                                        when (request) {
                                            is AndroidPickerRequest.OpenFile -> onConfirmFile(item)
                                            is AndroidPickerRequest.SaveFile -> fileNameInput = item.name
                                            is AndroidPickerRequest.SelectDirectory -> {}
                                        }
                                    }
                                }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = rowIcon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (item.isDirectory) "${item.name}/" else item.name,
                            style = typography.monoBody,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (item.isFile) {
                            StudioBadge(
                                text = formatFileBytes(item.length()),
                                monospace = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileBytes(bytes: Long): String =
    when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
