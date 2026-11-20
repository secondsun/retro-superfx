package dev.secondsun.sfxstudio.core.level

import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cardinal facing direction for spawns and entities (`0=N, 1=E, 2=S, 3=W`).
 */
enum class FacingDirection(
    val id: Int,
) {
    NORTH(0),
    EAST(1),
    SOUTH(2),
    WEST(3),
    ;

    companion object {
        fun fromId(id: Int): FacingDirection =
            entries.find { it.id == id }
                ?: throw IllegalArgumentException("Unknown FacingDirection id: $id (expected 0..3)")

        fun fromSymbol(raw: String): FacingDirection =
            when (raw.trim().uppercase()) {
                "N", "NORTH", "0" -> NORTH
                "E", "EAST", "1" -> EAST
                "S", "SOUTH", "2" -> SOUTH
                "W", "WEST", "3" -> WEST
                else -> throw IllegalArgumentException("Unknown FacingDirection: '$raw'")
            }
    }
}

/**
 * Cell coordinate `(col, row)` in a [MapGrid].
 */
data class GridCoord(
    val col: Int,
    val row: Int,
)

/**
 * Per-cell walkability/cost override overriding the default derived from [ShapeArchetype].
 *
 * Packed `.gmap` walkability byte layout:
 * - Bit `0` (`$01`): walkable (`1` = walkable, `0` = blocked)
 * - Bits `1..3` (`$0E`, shifted `<< 1`): movement cost (`0..7`)
 * - Bits `4..7` (`$F0`, shifted `<< 4`): custom terrain/hazard flags (`0..15`)
 */
data class WalkabilityOverride(
    val col: Int,
    val row: Int,
    val walkable: Boolean,
    val moveCost: Int = if (walkable) 1 else 0,
    val flags: Int = 0,
) {
    fun toPackedByte(): Int {
        val walkBit = if (walkable) GameplayBinarySerializer.WALK_FLAG_WALKABLE else 0
        val costBits = (moveCost and 0x07) shl GameplayBinarySerializer.WALK_COST_SHIFT
        val extraBits = (flags and 0x0F) shl GameplayBinarySerializer.WALK_EXTRA_SHIFT
        return walkBit or costBits or extraBits
    }
}

data class PlayerSpawn(
    val col: Int,
    val row: Int,
    val facing: FacingDirection = FacingDirection.SOUTH,
)

enum class NpcAiType(
    val id: Int,
) {
    GUARD(0),
    PATROL(1),
    ;

    companion object {
        fun fromId(id: Int): NpcAiType =
            entries.find { it.id == id }
                ?: throw IllegalArgumentException("Unknown NpcAiType id: $id")

        fun fromSymbol(raw: String): NpcAiType =
            when (raw.trim().uppercase()) {
                "GUARD", "0" -> GUARD
                "PATROL", "1" -> PATROL
                else -> throw IllegalArgumentException("Unknown NpcAiType: '$raw' (expected 'guard' or 'patrol')")
            }
    }
}

data class NpcSpawn(
    val id: String,
    val col: Int,
    val row: Int,
    val spriteId: Int = 0,
    val aiType: NpcAiType = NpcAiType.GUARD,
    val aggroRange: Int = 3,
    val patrolWaypoints: List<GridCoord> = emptyList(),
    val hp: Int = 10,
    val atk: Int = 3,
    val def: Int = 1,
    val speed: Int = 2,
    val movePoints: Int = 3,
)

enum class InteractableKind(
    val id: Int,
) {
    CHEST(0),
    SWITCH(1),
    SIGN(2),
    ;

    companion object {
        fun fromId(id: Int): InteractableKind =
            entries.find { it.id == id }
                ?: throw IllegalArgumentException("Unknown InteractableKind id: $id")

        fun fromSymbol(raw: String): InteractableKind =
            when (raw.trim().uppercase()) {
                "CHEST", "0" -> CHEST
                "SWITCH", "1" -> SWITCH
                "SIGN", "2" -> SIGN
                else -> throw IllegalArgumentException("Unknown InteractableKind: '$raw' (expected 'chest', 'switch', or 'sign')")
            }
    }
}

enum class RewardKind(
    val id: Int,
) {
    NONE(0),
    ATK_BUFF(1),
    HP_HEAL(2),
    ;

    companion object {
        fun fromId(id: Int): RewardKind =
            entries.find { it.id == id }
                ?: throw IllegalArgumentException("Unknown RewardKind id: $id")

        fun fromSymbol(raw: String): RewardKind =
            when (raw.trim().uppercase()) {
                "NONE", "", "0" -> NONE
                "ATK_BUFF", "1" -> ATK_BUFF
                "HP_HEAL", "2" -> HP_HEAL
                else -> throw IllegalArgumentException("Unknown RewardKind: '$raw' (expected 'none', 'atk_buff', or 'hp_heal')")
            }
    }
}

data class InteractableSpawn(
    val id: String,
    val col: Int,
    val row: Int,
    val kind: InteractableKind = InteractableKind.CHEST,
    val closedSpriteId: Int = 0,
    val openSpriteId: Int = 0,
    val rewardKind: RewardKind = RewardKind.NONE,
    val rewardValue: Int = 0,
    val messageId: Int = 0,
)

/**
 * Typed S-CPU gameplay layer authored alongside a [LevelDocument]'s visual [MapGrid].
 *
 * Exported into companion `<name>.gmap` and `<name>_gameplay.i` files without altering the
 * 8-byte-per-cell GSU `<name>.map` binary format.
 */
data class GameplayLayer(
    val walkabilityOverrides: List<WalkabilityOverride> = emptyList(),
    val playerSpawn: PlayerSpawn,
    val npcs: List<NpcSpawn> = emptyList(),
    val interactables: List<InteractableSpawn> = emptyList(),
)

// Internal Gson DTOs for `.sfxlevel` JSON persistence
internal data class GridCoordDto(
    var col: Int = 0,
    var row: Int = 0,
)

internal data class WalkabilityOverrideDto(
    var col: Int = 0,
    var row: Int = 0,
    var walkable: Boolean = true,
    var moveCost: Int? = null,
    var flags: Int = 0,
)

internal data class PlayerSpawnDto(
    var col: Int = 0,
    var row: Int = 0,
    var facing: String? = "south",
)

internal data class NpcSpawnDto(
    var id: String? = null,
    var col: Int = 0,
    var row: Int = 0,
    var spriteId: Int = 0,
    var aiType: String? = "guard",
    var aggroRange: Int = 3,
    var patrolWaypoints: List<GridCoordDto>? = null,
    var hp: Int = 10,
    var atk: Int = 3,
    var def: Int = 1,
    var speed: Int = 2,
    var movePoints: Int = 3,
)

internal data class InteractableSpawnDto(
    var id: String? = null,
    var col: Int = 0,
    var row: Int = 0,
    var kind: String? = "chest",
    var closedSpriteId: Int = 0,
    var openSpriteId: Int = 0,
    var rewardKind: String? = "none",
    var rewardValue: Int = 0,
    var messageId: Int = 0,
)

internal data class GameplayLayerDto(
    var walkabilityOverrides: List<WalkabilityOverrideDto>? = null,
    var playerSpawn: PlayerSpawnDto? = null,
    var npcs: List<NpcSpawnDto>? = null,
    var interactables: List<InteractableSpawnDto>? = null,
)

data class ParsedGmapBinary(
    val magic: Int,
    val version: Int,
    val cols: Int,
    val rows: Int,
    val playerSpawn: PlayerSpawn,
    val walkabilityGrid: ByteArray,
    val npcs: List<NpcSpawn>,
    val interactables: List<InteractableSpawn>,
)

/**
 * Serializes and deserializes S-CPU companion `<LEVEL>.gmap` binaries.
 *
 * Binary layout (little-endian):
 * - **Header (16 bytes, offsets `0..15`)**:
 *   - `+0..+1`: magic `"GM"` (`0x47, 0x4D`, LE `$4D47`)
 *   - `+2`: `version` (`u8` = `1`)
 *   - `+3`: `cols` (`u8`)
 *   - `+4`: `rows` (`u8`)
 *   - `+5`: `player_col` (`u8`)
 *   - `+6`: `player_row` (`u8`)
 *   - `+7`: `player_facing` (`u8`: `0=N, 1=E, 2=S, 3=W`)
 *   - `+8`: `npc_count` (`u8`)
 *   - `+9`: `interactable_count` (`u8`)
 *   - `+10..+11`: `walk_grid_offset` (`u16` LE = `16`)
 *   - `+12..+13`: `npc_table_offset` (`u16` LE = `16 + cols * rows`)
 *   - `+14..+15`: `interactable_table_offset` (`u16` LE = `16 + cols * rows + npc_count * 20`)
 * - **Walkability Grid (`cols * rows` bytes, starting at `walk_grid_offset`)**:
 *   - 1 byte per cell (`row * cols + col`): bit 0 = walkable, bits 1..3 = moveCost (`0..7`), bits 4..7 = custom flags.
 * - **NPC Spawn Table (`npc_count * 20` bytes, starting at `npc_table_offset`)**:
 *   - Fixed 20-byte record: `col, row, sprite_id, ai_type, aggro_range, hp, atk, def, speed, move_points, waypoint_count, reserved(0), wp0_col, wp0_row, wp1_col, wp1_row, wp2_col, wp2_row, wp3_col, wp3_row` (`0xFF` for unused waypoints).
 * - **Interactable Table (`interactable_count * 8` bytes, starting at `interactable_table_offset`)**:
 *   - Fixed 8-byte record: `col, row, kind, closed_sprite_id, open_sprite_id, reward_kind, reward_value, message_id`.
 */
object GameplayBinarySerializer {
    const val GMAP_MAGIC: Int = 0x4D47 // "GM" little-endian
    const val GMAP_VERSION: Int = 1
    const val GMAP_HEADER_SIZE: Int = 16
    const val GMAP_NPC_RECORD_SIZE: Int = 20
    const val GMAP_INTERACTABLE_RECORD_SIZE: Int = 8
    const val MAX_WAYPOINTS: Int = 4
    const val UNUSED_WAYPOINT_COORD: Int = 0xFF

    const val WALK_FLAG_WALKABLE: Int = 0x01
    const val WALK_COST_MASK: Int = 0x0E
    const val WALK_COST_SHIFT: Int = 1
    const val WALK_EXTRA_MASK: Int = 0xF0
    const val WALK_EXTRA_SHIFT: Int = 4

    /**
     * Default walkability derived from a cell's [ShapeArchetype]:
     * Liquid / Pillar / Foliage are non-walkable; Flat / Stairs / Wedges are walkable.
     */
    fun defaultWalkableFor(archetype: ShapeArchetype): Boolean =
        when (archetype) {
            ShapeArchetype.PRIM_LIQUID_SHALLOW,
            ShapeArchetype.PRIM_LIQUID_DEEP,
            ShapeArchetype.PRIM_PILLAR,
            ShapeArchetype.PRIM_FOLIAGE_CROSS,
            -> false
            else -> true
        }

    fun defaultPackedWalkByteFor(archetype: ShapeArchetype): Int =
        if (defaultWalkableFor(archetype)) {
            WALK_FLAG_WALKABLE or (1 shl WALK_COST_SHIFT)
        } else {
            0x00
        }

    /**
     * Computes the effective per-cell walkability byte array (`cols * rows`) combining
     * [ShapeArchetype] defaults with explicit [GameplayLayer.walkabilityOverrides].
     */
    fun buildWalkabilityGrid(
        grid: MapGrid,
        gameplay: GameplayLayer,
    ): ByteArray {
        val out = ByteArray(grid.cols * grid.rows)
        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val cell = grid.cellAt(col, row)
                out[row * grid.cols + col] = defaultPackedWalkByteFor(cell.archetype).toByte()
            }
        }
        for (override in gameplay.walkabilityOverrides) {
            if (grid.isInBounds(override.col, override.row)) {
                out[override.row * grid.cols + override.col] = override.toPackedByte().toByte()
            }
        }
        return out
    }

    fun byteSize(
        grid: MapGrid,
        gameplay: GameplayLayer,
    ): Int =
        GMAP_HEADER_SIZE +
            grid.cols * grid.rows +
            gameplay.npcs.size * GMAP_NPC_RECORD_SIZE +
            gameplay.interactables.size * GMAP_INTERACTABLE_RECORD_SIZE

    fun serialize(
        grid: MapGrid,
        gameplay: GameplayLayer,
    ): ByteArray {
        require(gameplay.npcs.size <= 255) { "Too many NPCs (${gameplay.npcs.size}, max 255)" }
        require(gameplay.interactables.size <= 255) {
            "Too many interactables (${gameplay.interactables.size}, max 255)"
        }

        val walkGrid = buildWalkabilityGrid(grid, gameplay)
        val walkGridOffset = GMAP_HEADER_SIZE
        val npcTableOffset = walkGridOffset + walkGrid.size
        val interactableTableOffset = npcTableOffset + gameplay.npcs.size * GMAP_NPC_RECORD_SIZE
        val totalBytes = interactableTableOffset + gameplay.interactables.size * GMAP_INTERACTABLE_RECORD_SIZE

        val buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)

        // 16-byte header
        buf.putShort(GMAP_MAGIC.toShort())
        buf.put(GMAP_VERSION.toByte())
        buf.put((grid.cols and 0xFF).toByte())
        buf.put((grid.rows and 0xFF).toByte())
        buf.put((gameplay.playerSpawn.col and 0xFF).toByte())
        buf.put((gameplay.playerSpawn.row and 0xFF).toByte())
        buf.put((gameplay.playerSpawn.facing.id and 0xFF).toByte())
        buf.put((gameplay.npcs.size and 0xFF).toByte())
        buf.put((gameplay.interactables.size and 0xFF).toByte())
        buf.putShort((walkGridOffset and 0xFFFF).toShort())
        buf.putShort((npcTableOffset and 0xFFFF).toShort())
        buf.putShort((interactableTableOffset and 0xFFFF).toShort())

        // Walkability grid
        buf.put(walkGrid)

        // NPC records (20 bytes each)
        for (npc in gameplay.npcs) {
            require(npc.patrolWaypoints.size <= MAX_WAYPOINTS) {
                "NPC '${npc.id}' has ${npc.patrolWaypoints.size} waypoints (max $MAX_WAYPOINTS)"
            }
            buf.put((npc.col and 0xFF).toByte())
            buf.put((npc.row and 0xFF).toByte())
            buf.put((npc.spriteId and 0xFF).toByte())
            buf.put((npc.aiType.id and 0xFF).toByte())
            buf.put((npc.aggroRange and 0xFF).toByte())
            buf.put((npc.hp and 0xFF).toByte())
            buf.put((npc.atk and 0xFF).toByte())
            buf.put((npc.def and 0xFF).toByte())
            buf.put((npc.speed and 0xFF).toByte())
            buf.put((npc.movePoints and 0xFF).toByte())
            buf.put((npc.patrolWaypoints.size and 0xFF).toByte())
            buf.put(0) // reserved
            for (wpIdx in 0 until MAX_WAYPOINTS) {
                val wp = npc.patrolWaypoints.getOrNull(wpIdx)
                if (wp != null) {
                    buf.put((wp.col and 0xFF).toByte())
                    buf.put((wp.row and 0xFF).toByte())
                } else {
                    buf.put(UNUSED_WAYPOINT_COORD.toByte())
                    buf.put(UNUSED_WAYPOINT_COORD.toByte())
                }
            }
        }

        // Interactable records (8 bytes each)
        for (inter in gameplay.interactables) {
            buf.put((inter.col and 0xFF).toByte())
            buf.put((inter.row and 0xFF).toByte())
            buf.put((inter.kind.id and 0xFF).toByte())
            buf.put((inter.closedSpriteId and 0xFF).toByte())
            buf.put((inter.openSpriteId and 0xFF).toByte())
            buf.put((inter.rewardKind.id and 0xFF).toByte())
            buf.put((inter.rewardValue and 0xFF).toByte())
            buf.put((inter.messageId and 0xFF).toByte())
        }

        return buf.array()
    }

    fun deserialize(bytes: ByteArray): ParsedGmapBinary {
        require(bytes.size >= GMAP_HEADER_SIZE) {
            "Binary .gmap file must be at least $GMAP_HEADER_SIZE bytes, got ${bytes.size}"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.short.toInt() and 0xFFFF
        require(magic == GMAP_MAGIC) {
            String.format("Invalid .gmap magic: expected \$%04X, got \$%04X", GMAP_MAGIC, magic)
        }
        val version = buf.get().toInt() and 0xFF
        require(version == GMAP_VERSION) { "Unsupported .gmap version: $version" }
        val cols = buf.get().toInt() and 0xFF
        val rows = buf.get().toInt() and 0xFF
        val playerCol = buf.get().toInt() and 0xFF
        val playerRow = buf.get().toInt() and 0xFF
        val playerFacing = FacingDirection.fromId(buf.get().toInt() and 0xFF)
        val npcCount = buf.get().toInt() and 0xFF
        val interactableCount = buf.get().toInt() and 0xFF
        val walkGridOffset = buf.short.toInt() and 0xFFFF
        val npcTableOffset = buf.short.toInt() and 0xFFFF
        val interactableTableOffset = buf.short.toInt() and 0xFFFF

        val walkLen = cols * rows
        require(walkGridOffset + walkLen <= bytes.size) { "Truncated .gmap walkability grid" }
        val walkGrid = bytes.copyOfRange(walkGridOffset, walkGridOffset + walkLen)

        val npcs = ArrayList<NpcSpawn>(npcCount)
        require(npcTableOffset + npcCount * GMAP_NPC_RECORD_SIZE <= bytes.size) { "Truncated .gmap NPC table" }
        buf.position(npcTableOffset)
        for (i in 0 until npcCount) {
            val col = buf.get().toInt() and 0xFF
            val row = buf.get().toInt() and 0xFF
            val spriteId = buf.get().toInt() and 0xFF
            val aiType = NpcAiType.fromId(buf.get().toInt() and 0xFF)
            val aggroRange = buf.get().toInt() and 0xFF
            val hp = buf.get().toInt() and 0xFF
            val atk = buf.get().toInt() and 0xFF
            val def = buf.get().toInt() and 0xFF
            val speed = buf.get().toInt() and 0xFF
            val movePoints = buf.get().toInt() and 0xFF
            val wpCount = buf.get().toInt() and 0xFF
            buf.get() // reserved
            val waypoints = ArrayList<GridCoord>(wpCount)
            for (wpIdx in 0 until MAX_WAYPOINTS) {
                val wc = buf.get().toInt() and 0xFF
                val wr = buf.get().toInt() and 0xFF
                if (wpIdx < wpCount) {
                    waypoints.add(GridCoord(wc, wr))
                }
            }
            npcs.add(
                NpcSpawn(
                    id = "npc_$i",
                    col = col,
                    row = row,
                    spriteId = spriteId,
                    aiType = aiType,
                    aggroRange = aggroRange,
                    patrolWaypoints = waypoints,
                    hp = hp,
                    atk = atk,
                    def = def,
                    speed = speed,
                    movePoints = movePoints,
                ),
            )
        }

        val interactables = ArrayList<InteractableSpawn>(interactableCount)
        require(interactableTableOffset + interactableCount * GMAP_INTERACTABLE_RECORD_SIZE <= bytes.size) {
            "Truncated .gmap interactable table"
        }
        buf.position(interactableTableOffset)
        for (i in 0 until interactableCount) {
            val col = buf.get().toInt() and 0xFF
            val row = buf.get().toInt() and 0xFF
            val kind = InteractableKind.fromId(buf.get().toInt() and 0xFF)
            val closedSpriteId = buf.get().toInt() and 0xFF
            val openSpriteId = buf.get().toInt() and 0xFF
            val rewardKind = RewardKind.fromId(buf.get().toInt() and 0xFF)
            val rewardValue = buf.get().toInt() and 0xFF
            val messageId = buf.get().toInt() and 0xFF
            interactables.add(
                InteractableSpawn(
                    id = "interactable_$i",
                    col = col,
                    row = row,
                    kind = kind,
                    closedSpriteId = closedSpriteId,
                    openSpriteId = openSpriteId,
                    rewardKind = rewardKind,
                    rewardValue = rewardValue,
                    messageId = messageId,
                ),
            )
        }

        return ParsedGmapBinary(
            magic = magic,
            version = version,
            cols = cols,
            rows = rows,
            playerSpawn = PlayerSpawn(playerCol, playerRow, playerFacing),
            walkabilityGrid = walkGrid,
            npcs = npcs,
            interactables = interactables,
        )
    }
}

/**
 * Generates constants-only ca65 assembly include (`<LEVEL>_gameplay.i`) documenting `.gmap`
 * struct offsets, enum values, and level-specific table offsets and entity indices.
 */
object GameplayHeaderGenerator {
    fun generate(
        levelName: String,
        grid: MapGrid,
        gameplay: GameplayLayer,
    ): String {
        val prefix = SourceTexture.sanitizeToIdentifier(levelName).uppercase()
        val guard = "__${prefix}_GAMEPLAY_I_DEFINED__"
        val walkOffset = GameplayBinarySerializer.GMAP_HEADER_SIZE
        val npcOffset = walkOffset + grid.cols * grid.rows
        val interactOffset = npcOffset + gameplay.npcs.size * GameplayBinarySerializer.GMAP_NPC_RECORD_SIZE
        val totalBytes = GameplayBinarySerializer.byteSize(grid, gameplay)

        return buildString {
            appendLine(";; Auto-generated by SuperFX Studio Gameplay Packer")
            appendLine(
                ";; Level: $levelName (${grid.cols}x${grid.rows}, " +
                    "${gameplay.npcs.size} NPC(s), ${gameplay.interactables.size} interactable(s), $totalBytes bytes)",
            )
            appendLine()
            appendLine(".ifndef ::__GMAP_FORMAT_CONSTANTS_DEFINED__")
            appendLine("::__GMAP_FORMAT_CONSTANTS_DEFINED__ = 1")
            appendLine()
            appendLine("GMAP_MAGIC = \$4D47")
            appendLine("GMAP_VERSION = ${GameplayBinarySerializer.GMAP_VERSION}")
            appendLine("GMAP_HEADER_SIZE = ${GameplayBinarySerializer.GMAP_HEADER_SIZE}")
            appendLine("GMAP_NPC_RECORD_SIZE = ${GameplayBinarySerializer.GMAP_NPC_RECORD_SIZE}")
            appendLine("GMAP_INTERACTABLE_RECORD_SIZE = ${GameplayBinarySerializer.GMAP_INTERACTABLE_RECORD_SIZE}")
            appendLine("GMAP_MAX_WAYPOINTS = ${GameplayBinarySerializer.MAX_WAYPOINTS}")
            appendLine("GMAP_UNUSED_WAYPOINT = \$FF")
            appendLine()
            appendLine(";; Walkability byte bitfields")
            appendLine("GMAP_WALK_FLAG_WALKABLE = \$01")
            appendLine("GMAP_WALK_COST_MASK = \$0E")
            appendLine("GMAP_WALK_COST_SHIFT = 1")
            appendLine("GMAP_WALK_EXTRA_MASK = \$F0")
            appendLine("GMAP_WALK_EXTRA_SHIFT = 4")
            appendLine()
            appendLine(";; Facing enum")
            appendLine("GMAP_FACING_NORTH = ${FacingDirection.NORTH.id}")
            appendLine("GMAP_FACING_EAST = ${FacingDirection.EAST.id}")
            appendLine("GMAP_FACING_SOUTH = ${FacingDirection.SOUTH.id}")
            appendLine("GMAP_FACING_WEST = ${FacingDirection.WEST.id}")
            appendLine()
            appendLine(";; NPC AI enum")
            appendLine("GMAP_AI_GUARD = ${NpcAiType.GUARD.id}")
            appendLine("GMAP_AI_PATROL = ${NpcAiType.PATROL.id}")
            appendLine()
            appendLine(";; Interactable & Reward enums")
            appendLine("GMAP_INTERACT_CHEST = ${InteractableKind.CHEST.id}")
            appendLine("GMAP_INTERACT_SWITCH = ${InteractableKind.SWITCH.id}")
            appendLine("GMAP_INTERACT_SIGN = ${InteractableKind.SIGN.id}")
            appendLine("GMAP_REWARD_NONE = ${RewardKind.NONE.id}")
            appendLine("GMAP_REWARD_ATK_BUFF = ${RewardKind.ATK_BUFF.id}")
            appendLine("GMAP_REWARD_HP_HEAL = ${RewardKind.HP_HEAL.id}")
            appendLine()
            appendLine(";; Header struct offsets (16 bytes)")
            appendLine("GMAP_HDR_OFF_MAGIC = 0")
            appendLine("GMAP_HDR_OFF_VERSION = 2")
            appendLine("GMAP_HDR_OFF_COLS = 3")
            appendLine("GMAP_HDR_OFF_ROWS = 4")
            appendLine("GMAP_HDR_OFF_PLAYER_COL = 5")
            appendLine("GMAP_HDR_OFF_PLAYER_ROW = 6")
            appendLine("GMAP_HDR_OFF_PLAYER_FACING = 7")
            appendLine("GMAP_HDR_OFF_NPC_COUNT = 8")
            appendLine("GMAP_HDR_OFF_INTERACTABLE_COUNT = 9")
            appendLine("GMAP_HDR_OFF_WALK_GRID_OFFSET = 10")
            appendLine("GMAP_HDR_OFF_NPC_TABLE_OFFSET = 12")
            appendLine("GMAP_HDR_OFF_INTERACTABLE_TABLE_OFFSET = 14")
            appendLine()
            appendLine(";; NPC record struct offsets (20 bytes)")
            appendLine("GMAP_NPC_OFF_COL = 0")
            appendLine("GMAP_NPC_OFF_ROW = 1")
            appendLine("GMAP_NPC_OFF_SPRITE_ID = 2")
            appendLine("GMAP_NPC_OFF_AI_TYPE = 3")
            appendLine("GMAP_NPC_OFF_AGGRO_RANGE = 4")
            appendLine("GMAP_NPC_OFF_HP = 5")
            appendLine("GMAP_NPC_OFF_ATK = 6")
            appendLine("GMAP_NPC_OFF_DEF = 7")
            appendLine("GMAP_NPC_OFF_SPEED = 8")
            appendLine("GMAP_NPC_OFF_MOVE_POINTS = 9")
            appendLine("GMAP_NPC_OFF_WAYPOINT_COUNT = 10")
            appendLine("GMAP_NPC_OFF_RESERVED = 11")
            appendLine("GMAP_NPC_OFF_WAYPOINTS = 12")
            appendLine()
            appendLine(";; Interactable record struct offsets (8 bytes)")
            appendLine("GMAP_INTERACT_OFF_COL = 0")
            appendLine("GMAP_INTERACT_OFF_ROW = 1")
            appendLine("GMAP_INTERACT_OFF_KIND = 2")
            appendLine("GMAP_INTERACT_OFF_CLOSED_SPRITE_ID = 3")
            appendLine("GMAP_INTERACT_OFF_OPEN_SPRITE_ID = 4")
            appendLine("GMAP_INTERACT_OFF_REWARD_KIND = 5")
            appendLine("GMAP_INTERACT_OFF_REWARD_VALUE = 6")
            appendLine("GMAP_INTERACT_OFF_MESSAGE_ID = 7")
            appendLine()
            appendLine(".endif ; __GMAP_FORMAT_CONSTANTS_DEFINED__")
            appendLine()
            appendLine(".ifndef ::$guard")
            appendLine("::$guard = 1")
            appendLine()
            appendLine("${prefix}_GMAP_BYTE_SIZE = $totalBytes")
            appendLine("${prefix}_PLAYER_SPAWN_COL = ${gameplay.playerSpawn.col}")
            appendLine("${prefix}_PLAYER_SPAWN_ROW = ${gameplay.playerSpawn.row}")
            appendLine("${prefix}_PLAYER_SPAWN_FACING = ${gameplay.playerSpawn.facing.id}")
            appendLine("${prefix}_NPC_COUNT = ${gameplay.npcs.size}")
            appendLine("${prefix}_INTERACTABLE_COUNT = ${gameplay.interactables.size}")
            appendLine("${prefix}_WALK_GRID_OFFSET = $walkOffset")
            appendLine("${prefix}_NPC_TABLE_OFFSET = $npcOffset")
            appendLine("${prefix}_INTERACTABLE_TABLE_OFFSET = $interactOffset")
            if (gameplay.npcs.isNotEmpty()) {
                appendLine()
                appendLine(";; NPC indices")
                for ((idx, npc) in gameplay.npcs.withIndex()) {
                    val sym = SourceTexture.sanitizeToIdentifier(npc.id).uppercase()
                    appendLine("${prefix}_NPC_${sym}_IDX = $idx")
                }
            }
            if (gameplay.interactables.isNotEmpty()) {
                appendLine()
                appendLine(";; Interactable indices")
                for ((idx, inter) in gameplay.interactables.withIndex()) {
                    val sym = SourceTexture.sanitizeToIdentifier(inter.id).uppercase()
                    appendLine("${prefix}_INTERACTABLE_${sym}_IDX = $idx")
                }
            }
            appendLine()
            appendLine(".endif ; $guard")
        }
    }
}
