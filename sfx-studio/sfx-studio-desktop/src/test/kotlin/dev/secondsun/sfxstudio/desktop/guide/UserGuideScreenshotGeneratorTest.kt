package dev.secondsun.sfxstudio.desktop.guide

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.secondsun.sfxstudio.core.atlas.DescriptorAlias
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.VirtualSlice
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.EdgePanels
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import dev.secondsun.sfxstudio.desktop.StudioRootContent
import dev.secondsun.sfxstudio.desktop.atlas.AtlasCatalogTab
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorTab
import dev.secondsun.sfxstudio.desktop.level.LevelCenterViewMode
import dev.secondsun.sfxstudio.desktop.level.LevelEditorState
import dev.secondsun.sfxstudio.desktop.level.LevelTool
import dev.secondsun.sfxstudio.desktop.shell.FakeStudioPlatform
import dev.secondsun.sfxstudio.desktop.shell.LevelEditMode
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import dev.secondsun.sfxstudio.desktop.ui.theme.AccentColorPreset
import dev.secondsun.sfxstudio.desktop.ui.theme.ThemeModePreference
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class UserGuideScreenshotGeneratorTest {
    data class ViewportSpec(
        val id: String,
        val width: Int,
        val height: Int,
        val density: Float = 1.0f,
    )

    companion object {
        val DESKTOP = ViewportSpec("desktop-1600x960", 1600, 960)
        val TABLET_LANDSCAPE = ViewportSpec("tablet-landscape-1024x768", 1024, 768)
        val TABLET_PORTRAIT = ViewportSpec("tablet-portrait-768x1024", 768, 1024)
        val ALL_VIEWPORTS = listOf(DESKTOP, TABLET_LANDSCAPE, TABLET_PORTRAIT)
    }

    private fun resolveUmbrellaRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "snes-sfx-demo").isDirectory && File(dir, "sfx-studio").isDirectory) {
                return dir
            }
            dir = dir.parentFile
        }
        error("Could not locate retro-superfx umbrella root from ${File(".").canonicalPath}")
    }

    private fun renderSceneBytes(
        studioState: StudioState,
        viewport: ViewportSpec,
    ): ByteArray {
        val effectiveDensity =
            Density(
                density = viewport.density * studioState.uiScaleMultiplier,
                fontScale = viewport.density * studioState.uiScaleMultiplier,
            )
        val scene =
            ImageComposeScene(
                width = viewport.width,
                height = viewport.height,
                density = Density(viewport.density),
            ) {
                CompositionLocalProvider(LocalDensity provides effectiveDensity) {
                    StudioRootContent(studioState = studioState)
                }
            }
        return try {
            // Render twice so remembered layouts/bitmaps settle cleanly
            scene.render(0L)
            val skiaImage = scene.render(16_000_000L)
            val data =
                skiaImage.encodeToData(EncodedImageFormat.PNG)
                    ?: error("Failed to encode Skia Image to PNG")
            data.bytes
        } finally {
            scene.close()
        }
    }

    private fun writeScreenshot(
        outputDir: File,
        name: String,
        studioState: StudioState,
        viewport: ViewportSpec,
    ): File {
        outputDir.mkdirs()
        val outFile = File(outputDir, "${name}_${viewport.id}.png")
        val bytes = renderSceneBytes(studioState, viewport)
        outFile.writeBytes(bytes)
        assertTrue(outFile.length() > 1024, "Expected non-trivial PNG for ${outFile.name}")
        return outFile
    }

    private fun createRichConflictBitmap(tempDir: File): File {
        val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val border = x == 0 || y == 0 || x == 31 || y == 31
                val transparentCorner = (x < 3 && y < 3) || (x > 28 && y < 3)
                if (transparentCorner) {
                    img.setRGB(x, y, 0x00000000)
                } else if (border) {
                    img.setRGB(x, y, (0xFF shl 24) or 0x181828)
                } else {
                    // Generate > 20 distinct 5-bit quantized RGB colors to trigger NeedsRemapping
                    val bucket = ((x / 4) + (y / 4) * 8) % 24
                    val r = ((bucket * 37 + 40) % 240)
                    val g = ((bucket * 73 + 60) % 240)
                    val b = ((bucket * 113 + 80) % 240)
                    img.setRGB(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
                }
            }
        }
        val file = File(tempDir, "stained_glass_window.png")
        ImageIO.write(img, "png", file)
        return file
    }

    private fun activeGuidePhase(): String =
        (System.getProperty("sfx.guide.phase") ?: System.getenv("SFX_GUIDE_PHASE"))
            ?.trim()
            .orEmpty()

    @Test
    fun smokeRenderDesktopAndTabletPortrait() {
        val root = resolveUmbrellaRoot()
        val phase = activeGuidePhase()
        if (phase.isNotEmpty()) {
            // Covered by full generation test below when phase is active
            return
        }
        val demoLevel = File(root, "snes-sfx-demo/X-GSU/IsoRPGDemo/Data/demo_level.sfxlevel")
        val studio = StudioState(initialFiles = listOf(demoLevel), platform = FakeStudioPlatform())
        val desktopPng = renderSceneBytes(studio, DESKTOP)
        val portraitPng = renderSceneBytes(studio, TABLET_PORTRAIT)
        assertNotNull(desktopPng)
        assertTrue(desktopPng.size > 2048)
        assertTrue(portraitPng.size > 2048)
    }

    @Test
    fun generateUserGuideScreenshotSuite(
        @TempDir tempDir: File,
    ) {
        val phase = activeGuidePhase()
        if (phase.isEmpty()) {
            return
        }

        val root = resolveUmbrellaRoot()
        val outDir = File(root, "sfx-studio/docs/user-guide/screenshots/$phase")
        outDir.mkdirs()

        val protagAtlas = File(root, "snes-sfx-demo/X-GSU/ProtagonistRoom/Data/PROTAGONIST_ROOM_ATLAS.sfxproj")
        val protagLevel = File(root, "snes-sfx-demo/X-GSU/ProtagonistRoom/Data/protagonist_room.sfxlevel")
        val demoAtlas = File(root, "snes-sfx-demo/X-GSU/IsoRPGDemo/Data/DEMO_ATLAS.sfxproj")
        val demoLevel = File(root, "snes-sfx-demo/X-GSU/IsoRPGDemo/Data/demo_level.sfxlevel")
        val isoEngineAtlas = File(root, "snes-sfx-demo/X-GSU/IsoEngine/Data/TEXTURES.sfxproj")

        // UC1: Multi-Document Shell & Empty/Onboarding State
        run {
            val studio = StudioState(initialFiles = listOf(protagAtlas, protagLevel), platform = FakeStudioPlatform())
            val freshAtlas = studio.newAtlasSession()
            freshAtlas.statusMessage = "New untitled Atlas Document ready — import bitmaps or link a level"
            for (vp in ALL_VIEWPORTS) {
                writeScreenshot(outDir, "uc01-shell-onboarding", studio, vp)
            }
        }

        // UC2: Importing a Rich >15-Color Bitmap & Resolving Palette Conflicts
        run {
            val studio = StudioState(initialFiles = listOf(protagAtlas), platform = FakeStudioPlatform())
            val atlasState = studio.activeSession as AtlasEditorState
            val richPng = createRichConflictBitmap(tempDir)
            atlasState.importFile(richPng)
            assertNotNull(atlasState.pendingImport, "Expected PendingTextureImport for >15-color PNG")
            for (vp in ALL_VIEWPORTS) {
                writeScreenshot(outDir, "uc02-palette-remapping-modal", studio, vp)
            }
        }

        // UC3: Source Texture Pixel Editing & BGR555 Palette Tuning
        run {
            val studio = StudioState(initialFiles = listOf(demoAtlas), platform = FakeStudioPlatform())
            val atlasState = studio.activeSession as AtlasEditorState
            atlasState.selectedPaletteSlot = 4
            atlasState.selectedTextureIndex = 0
            atlasState.editingTextureIndex = 0
            writeScreenshot(outDir, "uc03-pixel-editor-modal", studio, DESKTOP)
            writeScreenshot(outDir, "uc03-pixel-editor-modal", studio, TABLET_PORTRAIT)
        }

        // UC4a: Atlas Layout Packing, Bank $71 Warning, Virtual Slices & Descriptor Aliases
        run {
            val studio = StudioState(initialFiles = listOf(isoEngineAtlas), platform = FakeStudioPlatform())
            val atlasState = studio.activeSession as AtlasEditorState
            val firstTex = atlasState.atlas.textures.first()
            if (firstTex.width >= 16 && firstTex.height >= 16) {
                val withSlice =
                    atlasState.document
                        .addSlice(
                            VirtualSlice(
                                name = "${firstTex.name}_top_half",
                                sourceTexture = firstTex.name,
                                x = 0,
                                y = 0,
                                width = 16,
                                height = 16,
                            ),
                        ).getOrNull()
                if (withSlice != null) {
                    val withAlias =
                        withSlice
                            .addAlias(
                                DescriptorAlias(
                                    name = "hero_trim_alias",
                                    target = "${firstTex.name}_top_half",
                                ),
                            ).getOrNull()
                    if (withAlias != null) {
                        atlasState.atlas = withAlias.atlas
                    }
                }
            }
            atlasState.selectedTextureIndex = 0
            atlasState.zoom = 2f
            for (vp in ALL_VIEWPORTS) {
                writeScreenshot(outDir, "uc04a-atlas-layout-and-slices", studio, vp)
            }
            if (phase != "before" && atlasState.atlas.slices.isNotEmpty()) {
                atlasState.catalogTab = AtlasCatalogTab.DESCRIPTORS
                atlasState.editingSliceIndex = 0
                atlasState.showSliceModal = true
                writeScreenshot(outDir, "uc04e-atlas-slice-editor-modal", studio, DESKTOP)
                writeScreenshot(outDir, "uc04e-atlas-slice-editor-modal", studio, TABLET_PORTRAIT)
            }
        }

        // UC4b: Hex Preview, ca65 Index Preview (.i), and Export Modal
        run {
            val studio = StudioState(initialFiles = listOf(protagAtlas), platform = FakeStudioPlatform())
            val atlasState = studio.activeSession as AtlasEditorState
            atlasState.selectedTextureIndex = 0
            atlasState.activeTab = AtlasEditorTab.HEX_PREVIEW
            writeScreenshot(outDir, "uc04b-atlas-hex-preview", studio, DESKTOP)
            atlasState.activeTab = AtlasEditorTab.INDEX_PREVIEW
            writeScreenshot(outDir, "uc04c-atlas-index-preview", studio, DESKTOP)
            atlasState.showExportModal = true
            writeScreenshot(outDir, "uc04d-atlas-export-modal", studio, DESKTOP)
            writeScreenshot(outDir, "uc04d-atlas-export-modal", studio, TABLET_PORTRAIT)
        }

        // UC5: Creating a Linked Level & Cross-Tab Live Unsaved Atlas Editing
        run {
            val studio = StudioState(initialFiles = listOf(demoAtlas, demoLevel), platform = FakeStudioPlatform())
            val atlasTab = studio.openSessions[0] as AtlasEditorState
            // Tweak a grass/stone palette color in Tab 0 without saving to disk
            atlasTab.updatePaletteSlot(2, SnesColor(28, 18, 6))
            studio.selectSession(1)
            studio.showNewLevelModal = true
            writeScreenshot(outDir, "uc05-new-level-and-live-atlas", studio, DESKTOP)
            writeScreenshot(outDir, "uc05-new-level-and-live-atlas", studio, TABLET_PORTRAIT)
        }

        // UC6: 3D/2D Isometric Terrain Sculpting, Brush Layer Masks & Camera Controls
        run {
            val studio = StudioState(initialFiles = listOf(protagAtlas, protagLevel), platform = FakeStudioPlatform())
            studio.selectSession(1)
            val levelState = studio.activeSession as LevelEditorState
            levelState.activeTool = LevelTool.BRUSH
            levelState.selectRect(2, 2, 4, 3)
            levelState.centerViewMode = LevelCenterViewMode.SPLIT
            for (vp in ALL_VIEWPORTS) {
                writeScreenshot(outDir, "uc06-level-terrain-sculpting", studio, vp)
            }
        }

        // UC7: Tactical Gameplay Layer Authoring (IsoRPGDemo: Spawns, Patrols, Chests, Walkability)
        run {
            val studio = StudioState(initialFiles = listOf(demoAtlas, demoLevel), platform = FakeStudioPlatform())
            studio.selectSession(1)
            val levelState = studio.activeSession as LevelEditorState
            levelState.selectSingleCell(4, 6) // patrol_slime cell in demo_level.sfxlevel
            levelState.centerViewMode = LevelCenterViewMode.SPLIT
            for (vp in ALL_VIEWPORTS) {
                writeScreenshot(outDir, "uc07-gameplay-layer-authoring", studio, vp)
            }
            if (phase != "before") {
                levelState.editMode = LevelEditMode.GAMEPLAY
                writeScreenshot(outDir, "uc07b-gameplay-edit-mode", studio, DESKTOP)
                writeScreenshot(outDir, "uc07b-gameplay-edit-mode", studio, TABLET_PORTRAIT)
            }
        }

        // UC8: Validation Diagnostics Banner & Click-to-Jump Error Resolution
        run {
            val studio = StudioState(initialFiles = listOf(protagAtlas, protagLevel), platform = FakeStudioPlatform())
            studio.selectSession(1)
            val levelState = studio.activeSession as LevelEditorState
            levelState.selectSingleCell(3, 3)
            levelState.updateSelectedCells("Introduce invalid texture & panel config") { cell ->
                cell.copy(
                    archetype = ShapeArchetype.PRIM_STAIRS,
                    rotation = CellRotation.ROT_90,
                    topTex = "MISSING_TEX_SYMBOL",
                    panels = EdgePanels(north = true, east = true, tall2h = true),
                    panelTex = null,
                )
            }
            writeScreenshot(outDir, "uc08-validation-diagnostics", studio, DESKTOP)
            writeScreenshot(outDir, "uc08-validation-diagnostics", studio, TABLET_PORTRAIT)
        }

        // UC9: Cross-Platform Light Mode & System Accent Colors
        if (phase != "before") {
            run {
                // Simulate OS Light Mode + macOS/GNOME Purple System Accent on Level + Gameplay
                val lightPurplePlatform =
                    FakeStudioPlatform(
                        systemDarkMode = false,
                        systemAccentColorArgb = 0xFF8B5CF6.toInt(),
                    )
                val studio = StudioState(initialFiles = listOf(demoAtlas, demoLevel), platform = lightPurplePlatform)
                studio.selectSession(1)
                val levelState = studio.activeSession as LevelEditorState
                levelState.editMode = LevelEditMode.GAMEPLAY
                levelState.selectSingleCell(4, 6)
                writeScreenshot(outDir, "uc09a-light-mode-system-purple-level", studio, DESKTOP)
                writeScreenshot(outDir, "uc09a-light-mode-system-purple-level", studio, TABLET_PORTRAIT)
            }

            run {
                // Simulate OS Light Mode + Teal/Emerald System Accent on Atlas + Virtual Slices
                val lightEmeraldPlatform =
                    FakeStudioPlatform(
                        systemDarkMode = false,
                        systemAccentColorArgb = 0xFF10B981.toInt(),
                    )
                val studio = StudioState(initialFiles = listOf(isoEngineAtlas), platform = lightEmeraldPlatform)
                val atlasState = studio.activeSession as AtlasEditorState
                val firstTex = atlasState.atlas.textures.first()
                atlasState.addSlice(
                    VirtualSlice(
                        name = "${firstTex.name}_top_half",
                        sourceTexture = firstTex.name,
                        x = 0,
                        y = 0,
                        width = 16,
                        height = 16,
                    ),
                )
                atlasState.addAlias(DescriptorAlias("hero_trim_alias", "${firstTex.name}_top_half"))
                atlasState.selectedTextureIndex = 0
                writeScreenshot(outDir, "uc09b-light-mode-system-emerald-atlas", studio, DESKTOP)
                writeScreenshot(outDir, "uc09b-light-mode-system-emerald-atlas", studio, TABLET_PORTRAIT)

                // Dark Mode + Warm Rose/Amber System Accent
                studio.updateThemeModePreference(ThemeModePreference.DARK)
                studio.updateAccentColorPreset(AccentColorPreset.ROSE)
                writeScreenshot(outDir, "uc09c-dark-mode-rose-accent-atlas", studio, DESKTOP)
            }
        }
    }
}
