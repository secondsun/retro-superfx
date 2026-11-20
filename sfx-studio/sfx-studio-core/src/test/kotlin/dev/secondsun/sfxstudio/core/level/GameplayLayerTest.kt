package dev.secondsun.sfxstudio.core.level

import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GameplayLayerTest {
    private fun buildSampleAtlasDoc(): AtlasDocument {
        val grass = SourceTexture("grass", 32, 32, 0, 0, ByteArray(32 * 32) { 1 })
        val stone = SourceTexture("stone", 32, 32, 32, 0, ByteArray(32 * 32) { 2 })
        val water = SourceTexture("water", 32, 32, 0, 32, ByteArray(32 * 32) { 3 })
        val atlas = TextureAtlas(width = 64, height = 64, textures = listOf(grass, stone, water))
        val palette =
            SnesPalette()
                .withSlot(1, SnesColor.fromRgb888(40, 180, 60))
                .withSlot(2, SnesColor.fromRgb888(140, 140, 150))
                .withSlot(3, SnesColor.fromRgb888(40, 110, 220))
        return AtlasDocument(name = "ATLAS", atlas = atlas, palette = palette)
    }

    private fun buildSampleGrid(): MapGrid {
        var grid =
            MapGrid.filled(
                cols = 6,
                rows = 5,
                defaultCell = MapCell(baseHeight = 0, archetype = ShapeArchetype.PRIM_FLAT, topTex = "grass", skirtTex = "stone"),
            )
        // Add a non-walkable liquid cell at (2, 2), pillar at (3, 2), foliage at (4, 2)
        grid =
            grid.withCell(
                2,
                2,
                MapCell(baseHeight = 0, archetype = ShapeArchetype.PRIM_LIQUID_DEEP, topTex = "water", skirtTex = "stone"),
            )
        grid =
            grid.withCell(
                3,
                2,
                MapCell(baseHeight = 0, archetype = ShapeArchetype.PRIM_PILLAR, topTex = "stone", skirtTex = "stone"),
            )
        grid =
            grid.withCell(
                4,
                2,
                MapCell(
                    baseHeight = 0,
                    archetype = ShapeArchetype.PRIM_FOLIAGE_CROSS,
                    topTex = "grass",
                    skirtTex = "stone",
                    panelTex = "grass",
                ),
            )
        return grid
    }

    @Test
    fun testBackwardCompatibilityWithoutGameplayLayer(
        @TempDir tempDir: File,
    ) {
        val atlasDoc = buildSampleAtlasDoc()
        val levelFile = File(tempDir, "legacy.sfxlevel")
        val doc =
            LevelDocument(
                name = "legacy",
                atlasPath = "ATLAS.sfxproj",
                grid = buildSampleGrid(),
                gameplayLayers = mapOf("spawnPoints" to listOf(mapOf("col" to 1, "row" to 1))),
            )

        val validation = LevelValidator.validate(doc, atlasDoc)
        assertTrue(validation.valid)

        val exported = LevelDocumentService.saveAndExport(levelFile, doc, atlasDoc)
        assertTrue(exported.mapBinFile.exists())
        assertTrue(exported.headerFile.exists())
        assertNull(exported.gmapBinFile)
        assertNull(exported.gameplayHeaderFile)
        assertFalse(File(tempDir, "legacy.gmap").exists())
        assertFalse(File(tempDir, "legacy_gameplay.i").exists())

        val reloaded = LevelDocumentService.loadLevel(levelFile)
        assertNull(reloaded.gameplay)
        assertNotNull(reloaded.gameplayLayers)
        assertArrayEquals(
            LevelBinarySerializer.serialize(doc.grid, atlasDoc.atlas),
            exported.mapBinFile.readBytes(),
        )
    }

    @Test
    fun testTypedGameplayJsonRoundTripAndGmapBinaryLayout(
        @TempDir tempDir: File,
    ) {
        val atlasDoc = buildSampleAtlasDoc()
        val grid = buildSampleGrid()
        val gameplay =
            GameplayLayer(
                walkabilityOverrides =
                    listOf(
                        // Override cell (0, 1) to slow mud (walkable=true, moveCost=3, flags=2)
                        WalkabilityOverride(col = 0, row = 1, walkable = true, moveCost = 3, flags = 2),
                        // Override cell (5, 4) to blocked invisible barrier
                        WalkabilityOverride(col = 5, row = 4, walkable = false, moveCost = 0, flags = 1),
                    ),
                playerSpawn = PlayerSpawn(col = 0, row = 0, facing = FacingDirection.EAST),
                npcs =
                    listOf(
                        NpcSpawn(
                            id = "goblin_scout",
                            col = 1,
                            row = 3,
                            spriteId = 2,
                            aiType = NpcAiType.PATROL,
                            aggroRange = 4,
                            patrolWaypoints = listOf(GridCoord(1, 3), GridCoord(1, 4), GridCoord(2, 4)),
                            hp = 18,
                            atk = 5,
                            def = 2,
                            speed = 3,
                            movePoints = 4,
                        ),
                        NpcSpawn(
                            id = "bridge_guard",
                            col = 5,
                            row = 1,
                            spriteId = 3,
                            aiType = NpcAiType.GUARD,
                            aggroRange = 2,
                            patrolWaypoints = emptyList(),
                            hp = 30,
                            atk = 7,
                            def = 4,
                            speed = 1,
                            movePoints = 2,
                        ),
                    ),
                interactables =
                    listOf(
                        InteractableSpawn(
                            id = "hidden_chest",
                            col = 4,
                            row = 0,
                            kind = InteractableKind.CHEST,
                            closedSpriteId = 10,
                            openSpriteId = 11,
                            rewardKind = RewardKind.ATK_BUFF,
                            rewardValue = 3,
                            messageId = 5,
                        ),
                        InteractableSpawn(
                            id = "herb_shrine",
                            col = 0,
                            row = 4,
                            kind = InteractableKind.SWITCH,
                            closedSpriteId = 12,
                            openSpriteId = 13,
                            rewardKind = RewardKind.HP_HEAL,
                            rewardValue = 15,
                            messageId = 6,
                        ),
                    ),
            )

        val doc =
            LevelDocument(
                name = "courtyard_rpg",
                atlasPath = "ATLAS.sfxproj",
                grid = grid,
                gameplay = gameplay,
            )

        val validation = LevelValidator.validate(doc, atlasDoc)
        assertTrue(validation.valid, "Expected valid gameplay level, got errors: ${validation.errors}")

        val levelFile = File(tempDir, "courtyard_rpg.sfxlevel")
        val exported = LevelDocumentService.saveAndExport(levelFile, doc, atlasDoc)

        // GSU .map binary must be 100% identical to a visual-only level
        val visualOnlyMap = LevelBinarySerializer.serialize(grid, atlasDoc.atlas)
        assertArrayEquals(visualOnlyMap, exported.mapBinFile.readBytes())

        // JSON round-trip
        val reloaded = LevelDocumentService.loadLevel(levelFile)
        assertEquals(gameplay, reloaded.gameplay)

        // Companion .gmap and _gameplay.i files emitted
        val gmapFile = exported.gmapBinFile
        val gameplayHeaderFile = exported.gameplayHeaderFile
        assertNotNull(gmapFile)
        assertNotNull(gameplayHeaderFile)
        assertTrue(gmapFile!!.exists())
        assertTrue(gameplayHeaderFile!!.exists())

        val gmapBytes = gmapFile.readBytes()
        // Expected size: 16 header + 30 walk cells + 2*20 NPCs + 2*8 interactables = 102 bytes
        val expectedSize = 16 + (6 * 5) + (2 * 20) + (2 * 8)
        assertEquals(expectedSize, gmapBytes.size)

        // Verify raw header bytes
        assertEquals('G'.code, gmapBytes[0].toInt() and 0xFF)
        assertEquals('M'.code, gmapBytes[1].toInt() and 0xFF)
        assertEquals(1, gmapBytes[2].toInt() and 0xFF) // version
        assertEquals(6, gmapBytes[3].toInt() and 0xFF) // cols
        assertEquals(5, gmapBytes[4].toInt() and 0xFF) // rows
        assertEquals(0, gmapBytes[5].toInt() and 0xFF) // player_col
        assertEquals(0, gmapBytes[6].toInt() and 0xFF) // player_row
        assertEquals(FacingDirection.EAST.id, gmapBytes[7].toInt() and 0xFF)
        assertEquals(2, gmapBytes[8].toInt() and 0xFF) // npc_count
        assertEquals(2, gmapBytes[9].toInt() and 0xFF) // interactable_count

        // Verify walkability defaults + overrides
        val parsed = GameplayBinarySerializer.deserialize(gmapBytes)
        assertEquals(GameplayBinarySerializer.GMAP_MAGIC, parsed.magic)
        assertEquals(PlayerSpawn(0, 0, FacingDirection.EAST), parsed.playerSpawn)
        // Flat cell (0,0) -> walkable(1) | cost 1 (<<1) = 0x03
        assertEquals(0x03, parsed.walkabilityGrid[0 * 6 + 0].toInt() and 0xFF)
        // Liquid (2,2), Pillar (3,2), Foliage (4,2) -> 0x00
        assertEquals(0x00, parsed.walkabilityGrid[2 * 6 + 2].toInt() and 0xFF)
        assertEquals(0x00, parsed.walkabilityGrid[2 * 6 + 3].toInt() and 0xFF)
        assertEquals(0x00, parsed.walkabilityGrid[2 * 6 + 4].toInt() and 0xFF)
        // Override (0,1): walkable=1 | (3<<1)=6 | (2<<4)=0x20 => 0x27
        assertEquals(0x27, parsed.walkabilityGrid[1 * 6 + 0].toInt() and 0xFF)
        // Override (5,4): walkable=0 | (0<<1) | (1<<4)=0x10 => 0x10
        assertEquals(0x10, parsed.walkabilityGrid[4 * 6 + 5].toInt() and 0xFF)

        // Verify deserialized NPCs and Interactables
        assertEquals(2, parsed.npcs.size)
        assertEquals(1, parsed.npcs[0].col)
        assertEquals(3, parsed.npcs[0].row)
        assertEquals(NpcAiType.PATROL, parsed.npcs[0].aiType)
        assertEquals(listOf(GridCoord(1, 3), GridCoord(1, 4), GridCoord(2, 4)), parsed.npcs[0].patrolWaypoints)
        assertEquals(18, parsed.npcs[0].hp)
        assertEquals(5, parsed.npcs[0].atk)
        // Unused 4th waypoint padded with 0xFF in raw bytes
        val npc0Base = 16 + 30
        assertEquals(0xFF, gmapBytes[npc0Base + 18].toInt() and 0xFF)
        assertEquals(0xFF, gmapBytes[npc0Base + 19].toInt() and 0xFF)

        assertEquals(2, parsed.interactables.size)
        assertEquals(InteractableKind.CHEST, parsed.interactables[0].kind)
        assertEquals(RewardKind.ATK_BUFF, parsed.interactables[0].rewardKind)
        assertEquals(3, parsed.interactables[0].rewardValue)
        assertEquals(5, parsed.interactables[0].messageId)

        // Verify generated ca65 header
        val headerText = gameplayHeaderFile.readText()
        assertTrue(headerText.contains("GMAP_MAGIC = \$4D47"))
        assertTrue(headerText.contains("COURTYARD_RPG_GMAP_BYTE_SIZE = $expectedSize"))
        assertTrue(headerText.contains("COURTYARD_RPG_NPC_COUNT = 2"))
        assertTrue(headerText.contains("COURTYARD_RPG_INTERACTABLE_COUNT = 2"))
        assertTrue(headerText.contains("COURTYARD_RPG_NPC_GOBLIN_SCOUT_IDX = 0"))
        assertTrue(headerText.contains("COURTYARD_RPG_INTERACTABLE_HIDDEN_CHEST_IDX = 0"))
    }

    @Test
    fun testGameplayValidationErrors() {
        val atlasDoc = buildSampleAtlasDoc()
        val grid = buildSampleGrid()

        // 1. Out-of-bounds spawns & waypoints
        val oobDoc =
            LevelDocument(
                name = "bad_oob",
                atlasPath = "ATLAS.sfxproj",
                grid = grid,
                gameplay =
                    GameplayLayer(
                        walkabilityOverrides = listOf(WalkabilityOverride(9, 0, true)),
                        playerSpawn = PlayerSpawn(6, 0),
                        npcs =
                            listOf(
                                NpcSpawn(
                                    id = "bad_npc",
                                    col = -1,
                                    row = 0,
                                    patrolWaypoints = listOf(GridCoord(0, 5)),
                                    hp = 0,
                                ),
                            ),
                        interactables =
                            listOf(
                                InteractableSpawn(id = "bad_chest", col = 0, row = 5),
                            ),
                    ),
            )
        val oobResult = LevelValidator.validate(oobDoc, atlasDoc)
        assertFalse(oobResult.valid)
        val oobCodes = oobResult.errors.map { it.code }.toSet()
        assertTrue("GAMEPLAY_WALKABILITY_OUT_OF_BOUNDS" in oobCodes)
        assertTrue("PLAYER_SPAWN_OUT_OF_BOUNDS" in oobCodes)
        assertTrue("NPC_SPAWN_OUT_OF_BOUNDS" in oobCodes)
        assertTrue("NPC_WAYPOINT_OUT_OF_BOUNDS" in oobCodes)
        assertTrue("NPC_STAT_OUT_OF_RANGE" in oobCodes)
        assertTrue("INTERACTABLE_OUT_OF_BOUNDS" in oobCodes)

        // 2. Non-walkable cells, overlaps, duplicate IDs, >4 waypoints
        val invalidPlacementDoc =
            LevelDocument(
                name = "bad_placement",
                atlasPath = "ATLAS.sfxproj",
                grid = grid,
                gameplay =
                    GameplayLayer(
                        playerSpawn = PlayerSpawn(2, 2), // liquid cell!
                        npcs =
                            listOf(
                                NpcSpawn(
                                    id = "dup",
                                    col = 2,
                                    row = 2, // overlaps playerSpawn + liquid
                                    patrolWaypoints =
                                        listOf(
                                            GridCoord(0, 0),
                                            GridCoord(1, 0),
                                            GridCoord(3, 2), // pillar!
                                            GridCoord(0, 1),
                                            GridCoord(1, 1), // 5th waypoint (> 4)
                                        ),
                                ),
                                NpcSpawn(id = "dup", col = 0, row = 0),
                            ),
                        interactables =
                            listOf(
                                InteractableSpawn(id = "chest1", col = 0, row = 0), // overlaps npc #2
                            ),
                    ),
            )
        val placementResult = LevelValidator.validate(invalidPlacementDoc, atlasDoc)
        assertFalse(placementResult.valid)
        val placementCodes = placementResult.errors.map { it.code }.toSet()
        assertTrue("PLAYER_SPAWN_ON_NON_WALKABLE" in placementCodes)
        assertTrue("NPC_SPAWN_ON_NON_WALKABLE" in placementCodes)
        assertTrue("NPC_OVERLAPS_PLAYER_SPAWN" in placementCodes)
        assertTrue("NPC_TOO_MANY_WAYPOINTS" in placementCodes)
        assertTrue("NPC_WAYPOINT_ON_NON_WALKABLE" in placementCodes)
        assertTrue("DUPLICATE_NPC_ID" in placementCodes)
        assertTrue("INTERACTABLE_OVERLAPS_NPC" in placementCodes)
    }
}
