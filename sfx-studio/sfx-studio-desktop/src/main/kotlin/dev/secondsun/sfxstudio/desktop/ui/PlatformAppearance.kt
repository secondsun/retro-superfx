package dev.secondsun.sfxstudio.desktop.ui

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.roundToInt

/**
 * Cross-platform detector for OS light/dark color-scheme preference and system accent color across
 * Linux (XDG Desktop Portal, GNOME/GTK, KDE Plasma), macOS (`defaults`), and Windows (`reg query`).
 *
 * All parsing routines are pure functions exposed for deterministic headless unit testing.
 */
object PlatformAppearance {
    private const val SUBPROCESS_TIMEOUT_MS = 450L

    fun detectSystemDarkMode(): Boolean? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> detectMacOsDarkMode()
            os.contains("win") -> detectWindowsDarkMode()
            else -> detectLinuxDarkMode()
        }
    }

    fun detectSystemAccentColorArgb(): Int? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> detectMacOsAccentColor()
            os.contains("win") -> detectWindowsAccentColor()
            else -> detectLinuxAccentColor()
        }
    }

    // --- macOS ---

    private fun detectMacOsDarkMode(): Boolean? {
        val (code, out) = runCommand("defaults", "read", "-g", "AppleInterfaceStyle") ?: return null
        return parseMacOsInterfaceStyle(code, out)
    }

    private fun detectMacOsAccentColor(): Int? {
        val accentRes = runCommand("defaults", "read", "-g", "AppleAccentColor")
        if (accentRes != null) {
            parseMacOsAccentColor(accentRes.first, accentRes.second)?.let { return it }
        }
        val highlightRes = runCommand("defaults", "read", "-g", "AppleHighlightColor")
        if (highlightRes != null && highlightRes.first == 0) {
            parseMacOsHighlightColor(highlightRes.second)?.let { return it }
        }
        return if (accentRes != null) 0xFF007AFF.toInt() else null
    }

    fun parseMacOsInterfaceStyle(
        exitCode: Int,
        stdout: String,
    ): Boolean {
        if (exitCode != 0) {
            // Absent AppleInterfaceStyle key means Light appearance on macOS
            return false
        }
        return stdout.trim().equals("Dark", ignoreCase = true)
    }

    fun parseMacOsAccentColor(
        exitCode: Int,
        stdout: String,
    ): Int? {
        if (exitCode != 0) return null
        return when (stdout.trim().toIntOrNull()) {
            -1 -> 0xFF8E8E93.toInt() // Graphite
            0 -> 0xFFFF3B30.toInt() // Red
            1 -> 0xFFFF9500.toInt() // Orange
            2 -> 0xFFFFCC00.toInt() // Yellow
            3 -> 0xFF28CD41.toInt() // Green
            4 -> 0xFF007AFF.toInt() // Blue
            5 -> 0xFFAF52DE.toInt() // Purple
            6 -> 0xFFFF2D55.toInt() // Pink
            else -> null
        }
    }

    fun parseMacOsHighlightColor(stdout: String): Int? {
        val parts = stdout.trim().split(Regex("\\s+"))
        if (parts.size < 3) return null
        val r = parts[0].toDoubleOrNull() ?: return null
        val g = parts[1].toDoubleOrNull() ?: return null
        val b = parts[2].toDoubleOrNull() ?: return null
        if (r !in 0.0..1.0 || g !in 0.0..1.0 || b !in 0.0..1.0) return null
        return packRgb((r * 255.0).roundToInt(), (g * 255.0).roundToInt(), (b * 255.0).roundToInt())
    }

    // --- Windows 10 / 11 ---

    private fun detectWindowsDarkMode(): Boolean? {
        val (code, out) =
            runCommand(
                "reg",
                "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v",
                "AppsUseLightTheme",
            ) ?: return null
        if (code != 0) return null
        return parseWindowsAppsUseLightTheme(out)
    }

    private fun detectWindowsAccentColor(): Int? {
        val dwm =
            runCommand(
                "reg",
                "query",
                "HKCU\\Software\\Microsoft\\Windows\\DWM",
                "/v",
                "AccentColor",
            )
        if (dwm != null && dwm.first == 0) {
            parseWindowsAbgrDword(dwm.second, "AccentColor")?.let { return it }
        }
        val explorer =
            runCommand(
                "reg",
                "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Accent",
                "/v",
                "AccentColorMenu",
            )
        if (explorer != null && explorer.first == 0) {
            parseWindowsAbgrDword(explorer.second, "AccentColorMenu")?.let { return it }
        }
        return null
    }

    fun parseWindowsAppsUseLightTheme(stdout: String): Boolean? {
        val matcher =
            Pattern
                .compile("(?i)AppsUseLightTheme\\s+REG_DWORD\\s+(0x[0-9a-f]+|[0-9]+)")
                .matcher(stdout)
        if (!matcher.find()) return null
        val raw = matcher.group(1).trim()
        val num =
            if (raw.startsWith("0x", ignoreCase = true)) {
                raw.substring(2).toLongOrNull(16)
            } else {
                raw.toLongOrNull()
            } ?: return null
        return num == 0L
    }

    /**
     * Windows DWM `AccentColor` / `AccentColorMenu` stores `0xAABBGGRR` little-endian ABGR DWORDs.
     */
    fun parseWindowsAbgrDword(
        stdout: String,
        valueName: String = "AccentColor",
    ): Int? {
        val matcher =
            Pattern
                .compile("(?i)${Pattern.quote(valueName)}\\s+REG_DWORD\\s+(0x[0-9a-f]+|[0-9]+)")
                .matcher(stdout)
        if (!matcher.find()) return null
        val raw = matcher.group(1).trim()
        val dword =
            if (raw.startsWith("0x", ignoreCase = true)) {
                raw.substring(2).toLongOrNull(16)
            } else {
                raw.toLongOrNull()
            } ?: return null
        val r = (dword and 0xFFL).toInt()
        val g = ((dword shr 8) and 0xFFL).toInt()
        val b = ((dword shr 16) and 0xFFL).toInt()
        return packRgb(r, g, b)
    }

    // --- Linux (XDG Portal, GNOME/GTK, KDE Plasma) ---

    private fun detectLinuxDarkMode(): Boolean? {
        parseGtkThemeEnv(System.getenv("GTK_THEME"))?.let { return it }

        // 1. Freedesktop XDG Desktop Portal
        val portal =
            runCommand(
                "gdbus",
                "call",
                "--session",
                "--dest",
                "org.freedesktop.portal.Desktop",
                "--object-path",
                "/org/freedesktop/portal/desktop",
                "--method",
                "org.freedesktop.portal.Settings.Read",
                "org.freedesktop.appearance",
                "color-scheme",
            )
        if (portal != null && portal.first == 0) {
            parseFreedesktopColorScheme(portal.second)?.let { return it }
        }

        // 2. GNOME / Cinnamon / MATE gsettings
        val gsScheme = runCommand("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
        val gsTheme = runCommand("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")
        parseGnomeColorScheme(
            colorSchemeOut = gsScheme?.takeIf { it.first == 0 }?.second,
            gtkThemeOut = gsTheme?.takeIf { it.first == 0 }?.second,
        )?.let { return it }

        // 3. KDE Plasma kdeglobals
        readUserConfigFile(".config/kdeglobals")?.let { content ->
            parseKdeDarkMode(content)?.let { return it }
        }

        // 4. GTK 4 / GTK 3 settings.ini
        readUserConfigFile(".config/gtk-4.0/settings.ini")?.let { content ->
            parseGtkSettingsIniDarkMode(content)?.let { return it }
        }
        readUserConfigFile(".config/gtk-3.0/settings.ini")?.let { content ->
            parseGtkSettingsIniDarkMode(content)?.let { return it }
        }

        return null
    }

    private fun detectLinuxAccentColor(): Int? {
        // 1. Freedesktop XDG Desktop Portal 1.18+ (org.freedesktop.appearance accent-color)
        val portal =
            runCommand(
                "gdbus",
                "call",
                "--session",
                "--dest",
                "org.freedesktop.portal.Desktop",
                "--object-path",
                "/org/freedesktop/portal/desktop",
                "--method",
                "org.freedesktop.portal.Settings.Read",
                "org.freedesktop.appearance",
                "accent-color",
            )
        if (portal != null && portal.first == 0) {
            parseFreedesktopAccentColor(portal.second)?.let { return it }
        }

        // 2. GNOME 47+ accent-color setting
        val gnomeAccent = runCommand("gsettings", "get", "org.gnome.desktop.interface", "accent-color")
        if (gnomeAccent != null && gnomeAccent.first == 0) {
            parseGnomeAccentColor(gnomeAccent.second)?.let { return it }
        }

        // 3. KDE Plasma kdeglobals AccentColor / Selection BackgroundNormal
        readUserConfigFile(".config/kdeglobals")?.let { content ->
            parseKdeAccentColor(content)?.let { return it }
        }

        return null
    }

    fun parseGtkThemeEnv(gtkThemeEnv: String?): Boolean? {
        val raw = gtkThemeEnv?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty()) return null
        return when {
            raw.endsWith(":dark") || raw.contains("-dark") || raw.endsWith("dark") -> true
            raw.endsWith(":light") || raw.contains("-light") -> false
            else -> null
        }
    }

    fun parseFreedesktopColorScheme(stdout: String): Boolean? {
        // Matches e.g. "(<<uint32 1>>,)" or "(<uint32 2>,)"
        val matcher = Pattern.compile("uint32\\s+([0-9]+)").matcher(stdout)
        if (!matcher.find()) return null
        return when (matcher.group(1).toIntOrNull()) {
            1 -> true
            2 -> false
            else -> null
        }
    }

    fun parseFreedesktopAccentColor(stdout: String): Int? {
        // Matches 3 normalized doubles inside portal tuple: "(<<(0.2078, 0.5176, 0.8941)>>,)"
        val matcher =
            Pattern
                .compile("\\(\\s*([0-9]*\\.?[0-9]+)\\s*,\\s*([0-9]*\\.?[0-9]+)\\s*,\\s*([0-9]*\\.?[0-9]+)\\s*\\)")
                .matcher(stdout)
        if (!matcher.find()) return null
        val r = matcher.group(1).toDoubleOrNull() ?: return null
        val g = matcher.group(2).toDoubleOrNull() ?: return null
        val b = matcher.group(3).toDoubleOrNull() ?: return null
        if (r !in 0.0..1.0 || g !in 0.0..1.0 || b !in 0.0..1.0) return null
        return packRgb((r * 255.0).roundToInt(), (g * 255.0).roundToInt(), (b * 255.0).roundToInt())
    }

    fun parseGnomeColorScheme(
        colorSchemeOut: String?,
        gtkThemeOut: String?,
    ): Boolean? {
        val scheme = colorSchemeOut?.trim()?.trim('\'', '"')?.lowercase()
        when (scheme) {
            "prefer-dark" -> return true
            "prefer-light" -> return false
        }
        val theme = gtkThemeOut?.trim()?.trim('\'', '"')?.lowercase()
        if (!theme.isNullOrBlank()) {
            return theme.contains("dark")
        }
        return if (scheme == "default") false else null
    }

    fun parseGnomeAccentColor(stdout: String): Int? =
        when (stdout.trim().trim('\'', '"').lowercase()) {
            "blue" -> 0xFF3584E4.toInt()
            "teal" -> 0xFF2190A4.toInt()
            "green" -> 0xFF3A944A.toInt()
            "yellow" -> 0xFFC88800.toInt()
            "orange" -> 0xFFED5B00.toInt()
            "red" -> 0xFFE62D42.toInt()
            "pink" -> 0xFFD56199.toInt()
            "purple" -> 0xFF9141AC.toInt()
            "slate" -> 0xFF6F8396.toInt()
            else -> null
        }

    fun parseKdeDarkMode(content: String): Boolean? {
        val sections = parseIniSections(content)
        sections["Colors:Window"]?.get("BackgroundNormal")?.let { rgbStr ->
            parseCsvRgb(rgbStr)?.let { (r, g, b) ->
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                return luma < 128
            }
        }
        val scheme = sections["General"]?.get("ColorScheme")?.lowercase()
        if (!scheme.isNullOrBlank()) {
            return scheme.contains("dark")
        }
        return null
    }

    fun parseKdeAccentColor(content: String): Int? {
        val sections = parseIniSections(content)
        sections["General"]?.get("AccentColor")?.let { rgbStr ->
            parseCsvRgb(rgbStr)?.let { (r, g, b) -> return packRgb(r, g, b) }
        }
        sections["Colors:Selection"]?.get("BackgroundNormal")?.let { rgbStr ->
            parseCsvRgb(rgbStr)?.let { (r, g, b) -> return packRgb(r, g, b) }
        }
        return null
    }

    fun parseGtkSettingsIniDarkMode(content: String): Boolean? {
        val matcher =
            Pattern
                .compile("(?im)^\\s*gtk-application-prefer-dark-theme\\s*=\\s*(\\S+)\\s*$")
                .matcher(content)
        if (!matcher.find()) return null
        return when (matcher.group(1).lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }
    }

    // --- Internal Helpers ---

    private fun parseIniSections(content: String): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, MutableMap<String, String>>()
        var curSection = ""
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                curSection = line.substring(1, line.length - 1).trim()
                out.putIfAbsent(curSection, LinkedHashMap())
            } else {
                val eq = line.indexOf('=')
                if (eq > 0) {
                    val k = line.substring(0, eq).trim()
                    val v = line.substring(eq + 1).trim()
                    out.getOrPut(curSection) { LinkedHashMap() }[k] = v
                }
            }
        }
        return out
    }

    private fun parseCsvRgb(raw: String): Triple<Int, Int, Int>? {
        val parts = raw.split(',').map { it.trim().toIntOrNull() }
        if (parts.size < 3) return null
        val r = parts[0] ?: return null
        val g = parts[1] ?: return null
        val b = parts[2] ?: return null
        if (r !in 0..255 || g !in 0..255 || b !in 0..255) return null
        return Triple(r, g, b)
    }

    private fun packRgb(
        r: Int,
        g: Int,
        b: Int,
    ): Int =
        (0xFF shl 24) or
            ((r.coerceIn(0, 255)) shl 16) or
            ((g.coerceIn(0, 255)) shl 8) or
            b.coerceIn(0, 255)

    private fun readUserConfigFile(relPath: String): String? =
        try {
            val home = System.getProperty("user.home") ?: return null
            val f = File(home, relPath)
            if (f.isFile && f.canRead()) f.readText() else null
        } catch (_: Throwable) {
            null
        }

    private fun runCommand(vararg args: String): Pair<Int, String>? =
        try {
            val proc =
                ProcessBuilder(*args)
                    .redirectErrorStream(true)
                    .start()
            val finished = proc.waitFor(SUBPROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                null
            } else {
                val out = proc.inputStream.bufferedReader().readText()
                proc.exitValue() to out
            }
        } catch (_: Throwable) {
            null
        }
}
