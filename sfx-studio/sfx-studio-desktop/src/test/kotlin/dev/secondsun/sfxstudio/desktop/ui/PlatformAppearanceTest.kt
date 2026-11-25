package dev.secondsun.sfxstudio.desktop.ui

import androidx.compose.ui.graphics.Color
import dev.secondsun.sfxstudio.desktop.shell.FakeStudioPlatform
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import dev.secondsun.sfxstudio.desktop.ui.theme.AccentColorPreset
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioColors
import dev.secondsun.sfxstudio.desktop.ui.theme.ThemeModePreference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlatformAppearanceTest {
    @Test
    fun testMacOsDarkModeAndAccentParsers() {
        assertTrue(PlatformAppearance.parseMacOsInterfaceStyle(0, "Dark\n"))
        assertFalse(
            PlatformAppearance.parseMacOsInterfaceStyle(
                1,
                "The domain/default pair of (.GlobalPreferences, AppleInterfaceStyle) does not exist",
            ),
        )

        assertEquals(0xFF8E8E93.toInt(), PlatformAppearance.parseMacOsAccentColor(0, "-1"))
        assertEquals(0xFF007AFF.toInt(), PlatformAppearance.parseMacOsAccentColor(0, "4"))
        assertEquals(0xFFAF52DE.toInt(), PlatformAppearance.parseMacOsAccentColor(0, "5"))
        assertNull(PlatformAppearance.parseMacOsAccentColor(1, ""))

        assertEquals(
            0xFFB2D7FF.toInt(),
            PlatformAppearance.parseMacOsHighlightColor("0.698039 0.843137 1.000000 Blue"),
        )
    }

    @Test
    fun testWindowsRegistryLightThemeAndLittleEndianAbgrAccent() {
        val winDarkReg =
            """
            HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize
                AppsUseLightTheme    REG_DWORD    0x0
            """.trimIndent()
        val winLightReg =
            """
            HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize
                AppsUseLightTheme    REG_DWORD    0x1
            """.trimIndent()

        assertEquals(true, PlatformAppearance.parseWindowsAppsUseLightTheme(winDarkReg))
        assertEquals(false, PlatformAppearance.parseWindowsAppsUseLightTheme(winLightReg))

        // Windows DWM AccentColor 0xffd77800 is ABGR -> R=0x00, G=0x78, B=0xD7 (#0078D7)
        val dwmReg =
            """
            HKEY_CURRENT_USER\Software\Microsoft\Windows\DWM
                AccentColor    REG_DWORD    0xffd77800
            """.trimIndent()
        assertEquals(0xFF0078D7.toInt(), PlatformAppearance.parseWindowsAbgrDword(dwmReg, "AccentColor"))
    }

    @Test
    fun testLinuxPortalGnomeKdeAndGtkParsers() {
        // Freedesktop XDG Portal
        assertEquals(true, PlatformAppearance.parseFreedesktopColorScheme("(<<uint32 1>>,)"))
        assertEquals(false, PlatformAppearance.parseFreedesktopColorScheme("(<uint32 2>,)"))
        assertNull(PlatformAppearance.parseFreedesktopColorScheme("(<<uint32 0>>,)"))
        assertEquals(
            0xFF3584E4.toInt(),
            PlatformAppearance.parseFreedesktopAccentColor("(<<(0.207843, 0.517647, 0.894118)>>,)"),
        )

        // GNOME gsettings
        assertEquals(true, PlatformAppearance.parseGnomeColorScheme("'prefer-dark'", "'Adwaita'"))
        assertEquals(false, PlatformAppearance.parseGnomeColorScheme("'prefer-light'", "'Adwaita-dark'"))
        assertEquals(true, PlatformAppearance.parseGnomeColorScheme("'default'", "'Yaru-dark'"))
        assertEquals(false, PlatformAppearance.parseGnomeColorScheme("'default'", "'Adwaita'"))
        assertEquals(0xFF2190A4.toInt(), PlatformAppearance.parseGnomeAccentColor("'teal'\n"))
        assertEquals(0xFF9141AC.toInt(), PlatformAppearance.parseGnomeAccentColor("'purple'"))

        // KDE kdeglobals
        val kdeDark =
            """
            [General]
            ColorScheme=BreezeDark
            AccentColor=139,92,246

            [Colors:Window]
            BackgroundNormal=32,35,40
            """.trimIndent()
        assertEquals(true, PlatformAppearance.parseKdeDarkMode(kdeDark))
        assertEquals(0xFF8B5CF6.toInt(), PlatformAppearance.parseKdeAccentColor(kdeDark))

        val kdeLight =
            """
            [General]
            ColorScheme=BreezeLight

            [Colors:Window]
            BackgroundNormal=239,240,241

            [Colors:Selection]
            BackgroundNormal=61,174,233
            """.trimIndent()
        assertEquals(false, PlatformAppearance.parseKdeDarkMode(kdeLight))
        assertEquals(0xFF3DAEE9.toInt(), PlatformAppearance.parseKdeAccentColor(kdeLight))

        // GTK settings & env
        assertEquals(true, PlatformAppearance.parseGtkThemeEnv("Adwaita:dark"))
        assertEquals(false, PlatformAppearance.parseGtkThemeEnv("Breeze:light"))
        assertEquals(
            true,
            PlatformAppearance.parseGtkSettingsIniDarkMode("[Settings]\ngtk-application-prefer-dark-theme=1\n"),
        )
        assertEquals(
            false,
            PlatformAppearance.parseGtkSettingsIniDarkMode("[Settings]\ngtk-application-prefer-dark-theme=false\n"),
        )
    }

    @Test
    fun testStudioStateSystemThemeAndAccentLiveAdaptation() {
        val platform =
            FakeStudioPlatform(
                systemDarkMode = false,
                systemAccentColorArgb = 0xFF9141AC.toInt(), // GNOME Purple
            )
        val studio = StudioState(platform = platform)

        // Defaults to SYSTEM for both theme and accent
        assertEquals(ThemeModePreference.SYSTEM, studio.themeModePreference)
        assertEquals(AccentColorPreset.SYSTEM, studio.accentColorPreset)
        assertFalse(studio.effectiveIsDarkTheme)
        assertFalse(studio.resolvedColors.isDark)
        assertEquals(0xFF9141AC.toInt(), studio.effectiveAccentArgb)

        val lightBg = studio.resolvedColors.surface1
        val purpleAccent = studio.resolvedColors.accentPrimary

        // Simulate OS switching to Dark Mode + Rose Accent while app is running
        platform.systemDarkMode = true
        platform.systemAccentColorArgb = 0xFFF43F5E.toInt()
        studio.refreshSystemAppearance()

        assertTrue(studio.effectiveIsDarkTheme)
        assertTrue(studio.resolvedColors.isDark)
        assertNotEquals(lightBg, studio.resolvedColors.surface1)
        assertNotEquals(purpleAccent, studio.resolvedColors.accentPrimary)

        // Explicit user override persists to preferences
        studio.updateThemeModePreference(ThemeModePreference.LIGHT)
        studio.updateAccentColorPreset(AccentColorPreset.EMERALD)
        assertFalse(studio.effectiveIsDarkTheme)
        assertEquals("LIGHT", platform.preferences["themeModePreference"])
        assertEquals("EMERALD", platform.preferences["accentColorPreset"])
    }

    @Test
    fun testAccentDerivationEnsuresReadableOnAccentContrast() {
        // Bright yellow OS accent (#FFCC00) in Dark mode -> dark ink onAccent
        val brightYellowDark = StudioColors.fromAppearance(isDark = true, rawAccentArgb = 0xFFFFCC00.toInt())
        assertEquals(Color(0xFF0B0F17), brightYellowDark.onAccent)

        // Deep violet / cobalt accent in Light mode -> white ink onAccent
        val violetLight = StudioColors.fromAppearance(isDark = false, rawAccentArgb = 0xFF7C3AED.toInt())
        assertEquals(Color.White, violetLight.onAccent)
    }
}
