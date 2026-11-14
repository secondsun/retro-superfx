package dev.secondsun.sfxstudio.desktop.shell

import java.io.File

class FakeStudioPlatform(
    override var isMacOs: Boolean = false,
) : StudioPlatform {
    val openFileQueue = ArrayDeque<File?>()
    val saveFileQueue = ArrayDeque<File?>()
    val directoryQueue = ArrayDeque<File?>()

    val openDialogTitles = mutableListOf<String>()
    val openDialogAllowedExtensions = mutableListOf<Set<String>>()
    val saveDialogTitles = mutableListOf<String>()
    val saveDialogDefaultNames = mutableListOf<String>()
    val directoryDialogTitles = mutableListOf<String>()

    var clipboardText: String? = null
    val preferences = mutableMapOf<String, String>()

    override fun chooseOpenFile(
        title: String,
        initialDir: String?,
        allowedExtensions: Set<String>,
    ): File? {
        openDialogTitles.add(title)
        openDialogAllowedExtensions.add(allowedExtensions)
        return openFileQueue.removeFirstOrNull()
    }

    override fun chooseSaveFile(
        title: String,
        defaultFileName: String,
        initialDir: String?,
    ): File? {
        saveDialogTitles.add(title)
        saveDialogDefaultNames.add(defaultFileName)
        return saveFileQueue.removeFirstOrNull()
    }

    override fun chooseDirectory(
        title: String,
        initialDir: String?,
    ): File? {
        directoryDialogTitles.add(title)
        return directoryQueue.removeFirstOrNull()
    }

    override fun copyToClipboard(text: String) {
        clipboardText = text
    }

    override fun getPreference(
        key: String,
        default: String?,
    ): String? = preferences[key] ?: default

    override fun setPreference(
        key: String,
        value: String,
    ) {
        preferences[key] = value
    }
}
