package dev.secondsun.sfxstudio.core.atlas

import dev.secondsun.sfxstudio.core.level.EdgePanels
import dev.secondsun.sfxstudio.core.level.IsoSoftwareRenderer
import dev.secondsun.sfxstudio.core.level.LevelBinarySerializer
import dev.secondsun.sfxstudio.core.level.LevelDocument
import dev.secondsun.sfxstudio.core.level.LevelValidator
import dev.secondsun.sfxstudio.core.level.MapCell
import dev.secondsun.sfxstudio.core.level.MapGrid
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DescriptorTableResolutionTest {
    private fun sampleAtlasAndPalette(): Pair<TextureAtlas, SnesPalette> {
        // Slot 0 descriptor: bright red ("lava") so any accidental slot-0 fallback is unmistakably visible!
        val lava = SourceTexture("lava", 32, 32, 0, 0, ByteArray(32 * 32) { 1 })
        // Slot 1 descriptor: bright green ("grass")
        val grass = SourceTexture("grass", 32, 32, 32, 0, ByteArray(32 * 32) { 2 })
        // Slot 2 descriptor: blue ("stone"), plus a 32x16 slice ("parapet") and chained alias ("wall_cap")
        val stone =
            SourceTexture(
                "stone",
                32,
                32,
                0,
                32,
                ByteArray(32 * 32) { idx -> if (idx < 32 * 16) 3 else 4 },
            )
        val parapet = VirtualSlice("parapet", "stone", 0, 0, 32, 16)
        val wallCap = DescriptorAlias("wall_cap", "parapet")

        val atlas =
            TextureAtlas(
                width = 64,
                height = 64,
                textures = listOf(lava, grass, stone),
                slices = listOf(parapet),
                aliases = listOf(wallCap),
                descriptorOrder = listOf("lava", "grass", "stone", "parapet", "wall_cap"),
            )
        val palette =
            SnesPalette()
                .withSlot(1, SnesColor(31, 0, 0), assigned = true) // Red
                .withSlot(2, SnesColor(0, 31, 0), assigned = true) // Green
                .withSlot(3, SnesColor(0, 0, 31), assigned = true) // Blue (top half of stone)
                .withSlot(4, SnesColor(31, 31, 0), assigned = true) // Yellow (bottom half of stone)
        return atlas to palette
    }

    @Test
    fun testCanonicalSymbolLookupPrefixesCaseAndNumericModes() {
        val (atlas, _) = sampleAtlasAndPalette()
        val table = atlas.descriptorTable().getOrThrow()

        // Absent sentinels
        for (sentinel in listOf(null, "", "  ", "NONE", "none", "TEX_NONE", "tex_none", "TEXTURE_ID_NONE")) {
            assertEquals(DescriptorLookup.Absent, table.lookup(sentinel), "Expected Absent for '$sentinel'")
        }
        assertEquals(DescriptorLookup.Absent, table.lookup("0", allowNumericIndex = false))

        // Numeric mode for Ca65MapImporter (top/skirt "0" -> slot 0; panel "0" -> Absent)
        val slot0Lookup = table.lookup("0", allowNumericIndex = true, zeroIsAbsent = false)
        assertTrue(slot0Lookup is DescriptorLookup.Found && slot0Lookup.index == 0)
        assertEquals(DescriptorLookup.Absent, table.lookup("0", allowNumericIndex = true, zeroIsAbsent = true))
        val slot3Lookup = table.lookup("3", allowNumericIndex = true, zeroIsAbsent = true)
        assertTrue(slot3Lookup is DescriptorLookup.Found && slot3Lookup.descriptor.name == "parapet")

        // Prefix stripping (TEXTURE_ID_ and TEX_) + case-insensitivity
        for (variant in listOf("grass", "GRASS", "TEXTURE_ID_GRASS", "texture_id_grass", "TEX_GRASS", "tex_grass")) {
            val res = table.lookup(variant)
            assertTrue(res is DescriptorLookup.Found, "Expected Found for '$variant'")
            assertEquals(1, (res as DescriptorLookup.Found).index)
            assertEquals("grass", res.descriptor.name)
        }

        // Unknown symbol
        val unknown = table.lookup("TEXTURE_ID_DOES_NOT_EXIST")
        assertTrue(unknown is DescriptorLookup.Unknown)
    }

    @Test
    fun testExtractPixelsAndTabRoundTrip() {
        val (atlas, _) = sampleAtlasAndPalette()
        val table = atlas.descriptorTable().getOrThrow()

        // wall_cap aliases parapet (top 32x16 of stone -> all pixels must be slot 3, never slot 4)
        val wallCapDesc = (table.lookup("wall_cap") as DescriptorLookup.Found).descriptor
        val pixels = table.extractPixels(wallCapDesc)
        assertEquals(32 * 16, pixels.size)
        assertTrue(pixels.all { it.toInt() == 3 })

        val tabBytes = table.toTabBinary()
        val parsed = DescriptorTable.parseTabBinary(tabBytes)
        assertEquals(DescriptorTable.TAB_MAGIC, parsed.magic)
        assertEquals(5, parsed.entryCount)
        assertEquals(64, parsed.atlasWidth)
        assertEquals(6, parsed.strideShift)
    }

    @Test
    fun testAbsentSkirtInheritsTopTexInBothSerializerAndRenderer() {
        val (atlas, palette) = sampleAtlasAndPalette()
        // Elevated cell (baseHeight = 3) with topTex = "grass" (slot 1, green) and skirtTex = null (Absent).
        // Slot 0 is "lava" (red)! Both .map serializer and IsoSoftwareRenderer MUST inherit "grass" (1), NOT slot 0!
        val implicitSkirtGrid =
            MapGrid.filled(
                cols = 4,
                rows = 4,
                defaultCell = MapCell(baseHeight = 3, topTex = "TEXTURE_ID_GRASS", skirtTex = null),
            )
        val explicitSkirtGrid =
            MapGrid.filled(
                cols = 4,
                rows = 4,
                defaultCell = MapCell(baseHeight = 3, topTex = "grass", skirtTex = "grass"),
            )

        val implicitMapBytes = LevelBinarySerializer.serialize(implicitSkirtGrid, atlas)
        val explicitMapBytes = LevelBinarySerializer.serialize(explicitSkirtGrid, atlas)
        assertArrayEquals(
            explicitMapBytes,
            implicitMapBytes,
            "skirtTex = Absent must serialize identical skirtId (= topId = 1) as explicit skirtTex = topTex",
        )
        // Verify cell 0 topId (offset 10) == 1 and skirtId (offset 11) == 1 (never 0!)
        assertEquals(1, implicitMapBytes[10].toInt() and 0xFF)
        assertEquals(1, implicitMapBytes[11].toInt() and 0xFF)

        val implicitRender = IsoSoftwareRenderer.render(implicitSkirtGrid, atlas, palette, yawIndex = 15)
        val explicitRender = IsoSoftwareRenderer.render(explicitSkirtGrid, atlas, palette, yawIndex = 15)
        val implicitRgb = IntArray(implicitRender.width * implicitRender.height)
        val explicitRgb = IntArray(explicitRender.width * explicitRender.height)
        implicitRender.image.getRGB(0, 0, implicitRender.width, implicitRender.height, implicitRgb, 0, implicitRender.width)
        explicitRender.image.getRGB(0, 0, explicitRender.width, explicitRender.height, explicitRgb, 0, explicitRender.width)
        assertArrayEquals(
            explicitRgb,
            implicitRgb,
            "IsoSoftwareRenderer with skirtTex = Absent must match explicit skirtTex = topTex pixel-for-pixel",
        )
    }

    @Test
    fun testAbsentPanelRendersNothingAndWarnsOnlyWhenActive() {
        val (atlas, palette) = sampleAtlasAndPalette()

        // Base grid without panels vs grid with North/East/South/West panels enabled but panelTex = null
        val noPanelFlagsGrid =
            MapGrid.filled(
                cols = 4,
                rows = 4,
                defaultCell = MapCell(baseHeight = 1, topTex = "grass", skirtTex = "grass", panelTex = null),
            )
        val activePanelsNoTexGrid =
            MapGrid.filled(
                cols = 4,
                rows = 4,
                defaultCell =
                    MapCell(
                        baseHeight = 1,
                        topTex = "grass",
                        skirtTex = "grass",
                        panelTex = null,
                        panels = EdgePanels(north = true, east = true, south = true, west = true, tall2h = true),
                    ),
            )

        // 1. Validator: no warnings when panels are inactive; MISSING_PANEL_TEXTURE warning when panels are active
        val cleanVal = LevelValidator.validate(LevelDocument("clean", "a.sfxproj", noPanelFlagsGrid), atlas)
        assertTrue(cleanVal.valid)
        assertTrue(cleanVal.warnings.isEmpty())

        val panelWarnVal = LevelValidator.validate(LevelDocument("warn", "a.sfxproj", activePanelsNoTexGrid), atlas)
        assertTrue(panelWarnVal.valid)
        assertTrue(panelWarnVal.warnings.any { it.code == "MISSING_PANEL_TEXTURE" })

        // 2. Renderer: active edge panel flags with panelTex = null MUST NOT phantom-render slot 0 ("lava" red)!
        val baseRender = IsoSoftwareRenderer.render(noPanelFlagsGrid, atlas, palette, yawIndex = 15)
        val noTexPanelRender = IsoSoftwareRenderer.render(activePanelsNoTexGrid, atlas, palette, yawIndex = 15)
        val baseRgb = IntArray(baseRender.width * baseRender.height)
        val panelRgb = IntArray(noTexPanelRender.width * noTexPanelRender.height)
        baseRender.image.getRGB(0, 0, baseRender.width, baseRender.height, baseRgb, 0, baseRender.width)
        noTexPanelRender.image.getRGB(0, 0, noTexPanelRender.width, noTexPanelRender.height, panelRgb, 0, noTexPanelRender.width)
        assertArrayEquals(
            baseRgb,
            panelRgb,
            "panelTex = Absent must render nothing rather than phantom-rendering slot 0 ('lava')",
        )

        // 3. TopTex = Absent emits MISSING_TOP_TEXTURE warning and defaults to slot 0
        val missingTopGrid = MapGrid.filled(4, 4, MapCell(baseHeight = 0, topTex = null, skirtTex = null))
        val missingTopVal = LevelValidator.validate(LevelDocument("missing_top", "a.sfxproj", missingTopGrid), atlas)
        assertTrue(missingTopVal.valid, "Missing topTex is a WARNING, not a hard error")
        assertTrue(missingTopVal.warnings.any { it.code == "MISSING_TOP_TEXTURE" })
        val missingTopBytes = LevelBinarySerializer.serialize(missingTopGrid, atlas)
        assertEquals(0, missingTopBytes[10].toInt() and 0xFF)
        assertEquals(0, missingTopBytes[11].toInt() and 0xFF)
        assertFalse(missingTopVal.errors.isNotEmpty())
    }
}
