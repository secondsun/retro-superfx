package dev.secondsun.sfxstudio.core.atlas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class AtlasDocumentTest {
    private fun fullPalette(): SnesPalette {
        var pal = SnesPalette()
        for (slot in 1..15) {
            pal = pal.withSlot(slot, SnesColor(slot, slot, slot), assigned = true)
        }
        return pal
    }

    private fun solidArgbImage(
        w: Int,
        h: Int,
        colors: List<SnesColor>,
    ): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = colors[(y * w + x) % colors.size]
                img.setRGB(x, y, c.argb)
            }
        }
        return img
    }

    @Test
    fun testSnesColorAndPaletteInvariants() {
        val black = SnesColor.fromRgb888(0, 0, 0)
        assertEquals(1, black.r)
        assertEquals(0, black.g)
        assertEquals(0, black.b)
        assertEquals(0x0001, black.bgr555)

        val palette = SnesPalette()
        val bytes = palette.toBgr555Binary()
        assertEquals(32, bytes.size)
        assertEquals(0.toByte(), bytes[0])
        assertEquals(0.toByte(), bytes[1])
        assertEquals(0x01.toByte(), bytes[2])
        assertEquals(0x00.toByte(), bytes[3])
    }

    @Test
    fun testLoadPreservesCustomDocumentNameAndPixelsAsciiPrecedence(
        @TempDir tempDir: File,
    ) {
        val projFile = File(tempDir, "disk_filename.sfxproj")
        val tex = SourceTexture("tile", 16, 16, 0, 0, ByteArray(16 * 16) { 2 })
        val doc =
            AtlasDocument(
                name = "CUSTOM_ROM_SYMBOL",
                atlas = TextureAtlas(32, 32, listOf(tex)),
                palette = SnesPalette().withSlot(2, SnesColor(31, 0, 0), assigned = true),
                gridSnap = 8,
            )

        doc.saveAndExport(projFile)
        val loaded = AtlasDocument.load(projFile)
        assertEquals("CUSTOM_ROM_SYMBOL", loaded.name, "dto.name must be preserved rather than overwritten with filename")
        assertEquals(8, loaded.gridSnap)

        // Mutate pixelsAscii in JSON while leaving pixelsHex stale
        val editedJson = projFile.readText().replace("2".repeat(16), "7".repeat(16))
        projFile.writeText(editedJson)
        val reloaded = AtlasDocument.load(projFile)
        assertEquals(7, reloaded.atlas.textures[0].getPixel(0, 0))
    }

    @Test
    fun testSaveAndExportWithOutputDirDoesNotCopySfxprojAndPreValidatesAtomically(
        @TempDir tempDir: File,
    ) {
        val srcDir = File(tempDir, "src").apply { mkdirs() }
        val outDir = File(tempDir, "dist")
        val projFile = File(srcDir, "HERO_ATLAS.sfxproj")
        val tex = SourceTexture("hero", 16, 16, 0, 0, ByteArray(16 * 16) { 1 })
        val doc =
            AtlasDocument(
                name = "HERO_ATLAS",
                atlas = TextureAtlas(32, 32, listOf(tex)),
                palette = SnesPalette().withSlot(1, SnesColor(31, 31, 0), assigned = true),
            )

        doc.saveAndExport(documentFile = projFile, outputDir = outDir)

        assertTrue(projFile.exists(), "Source .sfxproj must be saved in place")
        assertFalse(
            File(outDir, "HERO_ATLAS.sfxproj").exists(),
            "saveAndExport must NOT copy .sfxproj into --output-dir",
        )
        assertTrue(File(outDir, "HERO_ATLAS.bin").exists())
        assertTrue(File(outDir, "HERO_ATLAS.pal").exists())
        assertTrue(File(outDir, "HERO_ATLAS.tab").exists())
        assertTrue(File(outDir, "HERO_ATLAS.i").exists())
        assertTrue(File(outDir, "HERO_ATLAS_atlas.png").exists())

        // Attempting saveAndExport on an invalid document must fail BEFORE touching disk (zero torn writes)
        val badDir = File(tempDir, "torn_check")
        val badProj = File(badDir, "BROKEN.sfxproj")
        val colliding =
            doc.copy(
                atlas =
                    TextureAtlas(
                        width = 32,
                        height = 32,
                        textures =
                            listOf(
                                SourceTexture("a", 16, 16, 0, 0, ByteArray(256)),
                                SourceTexture("b", 16, 16, 0, 0, ByteArray(256)),
                            ),
                    ),
            )
        val res = runCatching { colliding.saveAndExport(badProj) }
        assertTrue(res.isFailure)
        assertFalse(badProj.exists(), "Invalid document must not write a partial .sfxproj")
        assertFalse(File(badDir, "BROKEN.bin").exists())
    }

    @Test
    fun testValidationCatchesAllStructuralAndBank71Invariants() {
        val baseTex = SourceTexture("stone", 32, 32, 0, 0, ByteArray(32 * 32) { 1 })

        // 1. Case-insensitive duplicate across texture/slice/alias
        val dupDoc =
            AtlasDocument(
                atlas =
                    TextureAtlas(
                        width = 64,
                        height = 64,
                        textures = listOf(baseTex),
                        slices = listOf(VirtualSlice("STONE", "stone", 0, 0, 16, 16)),
                    ),
            )
        val dupVal = dupDoc.validate()
        assertFalse(dupVal.valid)
        assertTrue(dupVal.errors.any { it.code == "DUPLICATE_IDENTIFIER" })

        // 2. Slice out of bounds + unknown slice parent
        val sliceErrDoc =
            AtlasDocument(
                atlas =
                    TextureAtlas(
                        width = 64,
                        height = 64,
                        textures = listOf(baseTex),
                        slices =
                            listOf(
                                VirtualSlice("oob_slice", "stone", 24, 0, 16, 16),
                                VirtualSlice("orphan_slice", "missing_parent", 0, 0, 16, 16),
                            ),
                    ),
            )
        val sliceVal = sliceErrDoc.validate()
        assertTrue(sliceVal.errors.any { it.code == "SLICE_OUT_OF_BOUNDS" })
        assertTrue(sliceVal.errors.any { it.code == "UNKNOWN_SLICE_SOURCE" })

        // 3. Cyclic alias vs valid multi-hop chained alias
        val cyclicDoc =
            AtlasDocument(
                atlas =
                    TextureAtlas(
                        width = 64,
                        height = 64,
                        textures = listOf(baseTex),
                        aliases =
                            listOf(
                                DescriptorAlias("alias_a", "alias_b"),
                                DescriptorAlias("alias_b", "alias_a"),
                            ),
                    ),
            )
        assertTrue(cyclicDoc.validate().errors.any { it.code == "CYCLIC_ALIAS" })
        assertTrue(cyclicDoc.renderIndexPreview().isFailure, "renderIndexPreview must return Result.failure instead of crashing")

        val chainedDoc =
            AtlasDocument(
                atlas =
                    TextureAtlas(
                        width = 64,
                        height = 64,
                        textures = listOf(baseTex),
                        aliases =
                            listOf(
                                DescriptorAlias("hop1", "stone"),
                                DescriptorAlias("hop2", "hop1"),
                            ),
                    ),
            )
        assertTrue(chainedDoc.validate().valid, "Non-cyclic multi-hop alias chain must validate cleanly")
        assertTrue(chainedDoc.renderIndexPreview().isSuccess)

        // 4. Incomplete / duplicate / unknown descriptorOrder
        val badOrderDoc =
            AtlasDocument(
                atlas =
                    TextureAtlas(
                        width = 64,
                        height = 64,
                        textures = listOf(baseTex, SourceTexture("grass", 32, 32, 32, 0, ByteArray(32 * 32))),
                        descriptorOrder = listOf("stone", "stone", "ghost"),
                    ),
            )
        val orderVal = badOrderDoc.validate()
        assertTrue(orderVal.errors.any { it.code == "DUPLICATE_DESCRIPTOR_ORDER" })
        assertTrue(orderVal.errors.any { it.code == "UNKNOWN_DESCRIPTOR_ORDER_ENTRY" })
        assertTrue(orderVal.errors.any { it.code == "INCOMPLETE_DESCRIPTOR_ORDER" })

        // 5. > 256 descriptors (u8 .map limit)
        val aliases256 = (1..256).map { DescriptorAlias("alias_$it", "stone") }
        val tooManyDoc =
            AtlasDocument(
                atlas = TextureAtlas(width = 64, height = 64, textures = listOf(baseTex), aliases = aliases256),
            )
        assertTrue(tooManyDoc.validate().errors.any { it.code == "TOO_MANY_DESCRIPTORS" })

        // 6. Bank $71 >32 KB warning on 256x256
        val atlas64k = AtlasDocument(atlas = TextureAtlas(width = 256, height = 256, textures = listOf(baseTex)))
        assertTrue(atlas64k.validate(warnBank71Overflow = false).warnings.isEmpty())
        assertTrue(atlas64k.validate(warnBank71Overflow = true).warnings.any { it.code == "ATLAS_EXCEEDS_BANK_71" })
    }

    @Test
    fun testRenameCascadesAndRemoveTextureProtectsDependents() {
        val stone = SourceTexture("stone", 32, 32, 0, 0, ByteArray(32 * 32) { 1 })
        val grass = SourceTexture("grass", 32, 32, 32, 0, ByteArray(32 * 32) { 2 })
        val parapet = VirtualSlice("parapet", "stone", 0, 0, 32, 16)
        val cobble = DescriptorAlias("cobble", "stone")
        val cobbleAlt = DescriptorAlias("cobble_alt", "parapet")
        val doc =
            AtlasDocument(
                atlas =
                    TextureAtlas(
                        width = 64,
                        height = 64,
                        textures = listOf(stone, grass),
                        slices = listOf(parapet),
                        aliases = listOf(cobble, cobbleAlt),
                        descriptorOrder = listOf("stone", "grass", "parapet", "cobble", "cobble_alt"),
                    ),
            )

        // Case-insensitive conflict rejected
        assertTrue(doc.renameTexture(0, "GRASS").isFailure)
        assertTrue(doc.renameTexture(0, "PARAPET").isFailure)

        // Valid rename cascades across slice, alias, and descriptorOrder
        val renamed = doc.renameTexture(0, "granite").getOrThrow()
        assertEquals("granite", renamed.atlas.textures[0].name)
        assertEquals("granite", renamed.atlas.slices[0].sourceTexture)
        assertEquals("granite", renamed.atlas.aliases[0].target)
        assertEquals(listOf("granite", "grass", "parapet", "cobble", "cobble_alt"), renamed.atlas.descriptorOrder)
        assertTrue(renamed.validate().valid)

        // Removing referenced texture fails by default and names all dependents (including transitive alias)
        val rejected = renamed.removeTexture(0, cascadeDependents = false)
        assertTrue(rejected.isFailure)
        val msg = rejected.exceptionOrNull()!!.message!!
        assertTrue(msg.contains("parapet"))
        assertTrue(msg.contains("cobble"))
        assertTrue(msg.contains("cobble_alt"))

        // Removing unreferenced texture ("grass") succeeds and prunes descriptorOrder
        val withoutGrass = renamed.removeTexture(1, cascadeDependents = false).getOrThrow()
        assertEquals(listOf("granite"), withoutGrass.atlas.textures.map { it.name })
        assertEquals(listOf("granite", "parapet", "cobble", "cobble_alt"), withoutGrass.atlas.descriptorOrder)
        assertTrue(withoutGrass.validate().valid)

        // Cascade remove removes granite + its slices & aliases cleanly
        val cascaded = withoutGrass.removeTexture(0, cascadeDependents = true).getOrThrow()
        assertTrue(cascaded.atlas.textures.isEmpty())
        assertTrue(cascaded.atlas.slices.isEmpty())
        assertTrue(cascaded.atlas.aliases.isEmpty())
        assertTrue(cascaded.atlas.descriptorOrder!!.isEmpty())
    }

    @Test
    fun testStandaloneQuantizePaletteNeverInsertsSentinelTexture() {
        var pal = SnesPalette()
        for (slot in 1..15) {
            pal = pal.withSlot(slot, SnesColor(slot * 2, 30 - slot, slot), assigned = true)
        }
        val tex = SourceTexture("wall", 16, 16, 0, 0, ByteArray(256) { ((it % 15) + 1).toByte() })
        val doc = AtlasDocument(atlas = TextureAtlas(32, 32, listOf(tex)), palette = pal)

        val quantized = doc.quantizePalette()
        assertEquals(1, quantized.atlas.textures.size)
        assertFalse(quantized.atlas.textures.any { it.name == "ALL" })
        assertEquals("wall", quantized.atlas.textures[0].name)
    }

    @Test
    fun testPendingTextureImportAndPlacementPoliciesNeverStackAtOrigin() {
        // Fill 15-color palette so any new color triggers NeedsRemapping
        val pal = fullPalette()
        val existing = SourceTexture("base", 16, 16, 0, 0, ByteArray(256) { 1 })
        val smallDoc = AtlasDocument(atlas = TextureAtlas(16, 16, listOf(existing)), palette = pal, gridSnap = 16)

        val unmappedImg = solidArgbImage(16, 16, listOf(SnesColor(31, 0, 12)))

        // STRICT_BOUNDS rejects overflow on commit
        val remappingOutcome = smallDoc.beginImportImage(unmappedImg, "extra", PlacementPolicy.STRICT_BOUNDS)
        assertTrue(remappingOutcome is AtlasImportOutcome.NeedsRemapping)
        val pending = (remappingOutcome as AtlasImportOutcome.NeedsRemapping).pending
        assertTrue(pending.commitManual(smallDoc, pending.initialMapping, PlacementPolicy.STRICT_BOUNDS).isFailure)

        // AUTO_GROW_MAX_256 grows the 16x16 canvas instead of stacking at (0, 0)
        val grown = pending.commitManual(smallDoc, pending.initialMapping, PlacementPolicy.AUTO_GROW_MAX_256).getOrThrow()
        assertEquals(2, grown.atlas.textures.size)
        assertFalse(grown.atlas.textures[0].intersects(grown.atlas.textures[1]))
        assertTrue(grown.validate().valid)

        // Verify 256x128 (32 KB Bank $71 limit) is chosen BEFORE 256x256 (64 KB) when growing a 128x128 full canvas
        val four64 =
            listOf(
                SourceTexture("t0", 64, 64, 0, 0, ByteArray(4096) { 1 }),
                SourceTexture("t1", 64, 64, 64, 0, ByteArray(4096) { 1 }),
                SourceTexture("t2", 64, 64, 0, 64, ByteArray(4096) { 1 }),
                SourceTexture("t3", 64, 64, 64, 64, ByteArray(4096) { 1 }),
            )
        val full128 = AtlasDocument(atlas = TextureAtlas(128, 128, four64), palette = pal)
        val fifth = SourceTexture("t4", 64, 64, 0, 0, ByteArray(4096) { 1 })
        val grownBank71 = full128.placeNewTexture(fifth, PlacementPolicy.AUTO_GROW_MAX_256).getOrThrow()
        assertEquals(32_768, grownBank71.atlas.sizeBytes, "Should expand to 32 KB (256x128 or 128x256) before 64 KB (256x256)")
        assertTrue(grownBank71.validate(warnBank71Overflow = true).warnings.isEmpty())

        // Fill a 256x256 canvas completely and verify PendingTextureImport fails cleanly rather than placing at (0, 0)
        val sixteen64 =
            buildList {
                var idx = 0
                for (gy in 0 until 4) {
                    for (gx in 0 until 4) {
                        add(SourceTexture("full_${idx++}", 64, 64, gx * 64, gy * 64, ByteArray(4096) { 1 }))
                    }
                }
            }
        val full256 = AtlasDocument(atlas = TextureAtlas(256, 256, sixteen64), palette = pal)
        val overflowPending =
            (full256.beginImportImage(unmappedImg, "overflow_tile") as AtlasImportOutcome.NeedsRemapping).pending
        assertTrue(overflowPending.commitSnap(full256, PlacementPolicy.AUTO_GROW_MAX_256).isFailure)
        assertTrue(overflowPending.commitGlobalQuantize(full256, PlacementPolicy.AUTO_GROW_MAX_256).isFailure)
    }

    @Test
    fun testSourceTextureFloodFillAndSampleProject(
        @TempDir tempDir: File,
    ) {
        val pixels = ByteArray(16 * 16) { 0 }
        for (y in 0 until 16) pixels[y * 16 + 8] = 1
        val tex = SourceTexture("test", 16, 16, 0, 0, pixels)
        val filled = tex.floodFill(2, 2, 5)
        assertEquals(5, filled.getPixel(2, 2))
        assertEquals(1, filled.getPixel(8, 2))
        assertEquals(0, filled.getPixel(12, 2))

        val resourceStream = javaClass.getResourceAsStream("/TEXTURES.sfxproj")
        assertNotNull(resourceStream)
        val sampleDoc = AtlasDocument.load(resourceStream!!)
        assertTrue(sampleDoc.validate().valid)

        val outProj = File(tempDir, "scene.$SFX_ATLAS_EXTENSION")
        sampleDoc.saveAndExport(outProj)
        assertTrue(File(tempDir, "scene.bin").exists())
        assertTrue(File(tempDir, "scene.tab").exists())
        val img = ImageIO.read(File(tempDir, "scene_atlas.png"))
        assertEquals(sampleDoc.atlas.width, img.width)
    }
}
