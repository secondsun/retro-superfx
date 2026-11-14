package dev.secondsun.sfxstudio.cli.mcp

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import dev.secondsun.sfxstudio.core.atlas.ConflictResolution
import dev.secondsun.sfxstudio.core.atlas.PlacementPolicy
import dev.secondsun.sfxstudio.core.level.CellPatch
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.LevelDiagnostic
import dev.secondsun.sfxstudio.core.ops.AtlasOperationResult
import dev.secondsun.sfxstudio.core.ops.LevelOperationResult
import dev.secondsun.sfxstudio.core.ops.PathPolicy
import dev.secondsun.sfxstudio.core.ops.StudioOperations
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.StdioServerTransport
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.modelcontextprotocol.spec.ServerMcpTransport
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Structured response for `sfx_pack_textures` MCP tool invocations.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PackTexturesResponse(
    val status: String,
    val valid: Boolean,
    val action: String,
    val projectPath: String,
    val atlasWidth: Int? = null,
    val atlasHeight: Int? = null,
    val textureCount: Int? = null,
    val sliceCount: Int? = null,
    val aliasCount: Int? = null,
    val descriptorCount: Int? = null,
    val exportedFiles: List<String>? = null,
    val message: String,
)

/**
 * Structured response for `sfx_manage_level` MCP tool invocations.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ManageLevelResponse(
    val status: String,
    val valid: Boolean,
    val action: String,
    val levelPath: String,
    val cols: Int? = null,
    val rows: Int? = null,
    val mapByteSize: Int? = null,
    val errors: List<LevelDiagnostic>? = null,
    val warnings: List<LevelDiagnostic>? = null,
    val exportedFiles: List<String>? = null,
    val message: String,
)

internal class PackTexturesRequest {
    @field:JsonProperty("project_path")
    @field:JsonAlias("projectPath")
    var projectPath: String? = null

    @field:JsonProperty("action")
    var action: String? = null

    @field:JsonProperty("auto_pack")
    @field:JsonAlias("autoPack")
    var autoPack: Boolean = false

    @field:JsonProperty("auto_grow")
    @field:JsonAlias("autoGrow")
    var autoGrow: Boolean = false

    @field:JsonProperty("output_dir")
    @field:JsonAlias("outputDir")
    var outputDir: String? = null

    @field:JsonProperty("image_paths")
    @field:JsonAlias("imagePaths")
    var imagePaths: List<String>? = null

    @field:JsonProperty("auto_quantize")
    @field:JsonAlias("autoQuantize")
    var autoQuantize: Boolean = false

    @field:JsonProperty("snap_palette")
    @field:JsonAlias("snapPalette")
    var snapPalette: Boolean = false
}

internal class CellPatchDto {
    @field:JsonProperty("col")
    var col: Int? = null

    @field:JsonProperty("row")
    var row: Int? = null

    @field:JsonProperty("col_start")
    @field:JsonAlias("colStart")
    var colStart: Int? = null

    @field:JsonProperty("col_end")
    @field:JsonAlias("colEnd")
    var colEnd: Int? = null

    @field:JsonProperty("row_start")
    @field:JsonAlias("rowStart")
    var rowStart: Int? = null

    @field:JsonProperty("row_end")
    @field:JsonAlias("rowEnd")
    var rowEnd: Int? = null

    @field:JsonProperty("base_height")
    @field:JsonAlias("baseHeight")
    var baseHeight: Int? = null

    var archetype: String? = null
        private set

    var rotation: Int? = null
        private set

    var topTex: String? = null
        private set
    var updateTopTex: Boolean = false
        private set

    var skirtTex: String? = null
        private set
    var updateSkirtTex: Boolean = false
        private set

    var panelTex: String? = null
        private set
    var updatePanelTex: Boolean = false
        private set

    @field:JsonProperty("panels")
    var panels: List<String>? = null

    @field:JsonProperty("tall_2h")
    @field:JsonAlias("tall2h")
    var tall2h: Boolean? = null

    @JsonSetter("archetype")
    fun assignArchetype(node: JsonNode?) {
        if (node == null || node.isNull) {
            archetype = null
        } else if (node.isTextual) {
            archetype = node.textValue()
        } else if (node.isIntegralNumber) {
            archetype = node.intValue().toString()
        } else {
            throw IllegalArgumentException("Expected string or integer for 'archetype', got ${node.nodeType}")
        }
    }

    @JsonSetter("rotation")
    fun assignRotation(node: JsonNode?) {
        if (node == null || node.isNull) {
            rotation = null
        } else if (node.isIntegralNumber) {
            rotation = node.intValue()
        } else if (node.isTextual) {
            rotation = CellRotation.fromSymbol(node.textValue()).degrees
        } else {
            throw IllegalArgumentException("Expected integer or ROT_* symbol for 'rotation', got ${node.nodeType}")
        }
    }

    @JsonSetter("top_tex")
    @JsonAlias("topTex")
    fun assignTopTex(value: String?) {
        topTex = value
        updateTopTex = true
    }

    @JsonSetter("skirt_tex")
    @JsonAlias("skirtTex")
    fun assignSkirtTex(value: String?) {
        skirtTex = value
        updateSkirtTex = true
    }

    @JsonSetter("panel_tex")
    @JsonAlias("panelTex")
    fun assignPanelTex(value: String?) {
        panelTex = value
        updatePanelTex = true
    }

    fun toDomain(): CellPatch =
        CellPatch(
            col = col,
            row = row,
            colStart = colStart,
            colEnd = colEnd,
            rowStart = rowStart,
            rowEnd = rowEnd,
            baseHeight = baseHeight,
            archetype = archetype,
            rotation = rotation,
            topTex = topTex,
            updateTopTex = updateTopTex,
            skirtTex = skirtTex,
            updateSkirtTex = updateSkirtTex,
            panelTex = panelTex,
            updatePanelTex = updatePanelTex,
            panels = panels,
            tall2h = tall2h,
        )
}

internal class ManageLevelRequest {
    @field:JsonProperty("level_path")
    @field:JsonAlias("levelPath")
    var levelPath: String? = null

    @field:JsonProperty("action")
    var action: String? = null

    @field:JsonProperty("atlas_path")
    @field:JsonAlias("atlasPath")
    var atlasPath: String? = null

    @field:JsonProperty("ca65_path")
    @field:JsonAlias("ca65Path")
    var ca65Path: String? = null

    @field:JsonProperty("cols")
    var cols: Int? = null

    @field:JsonProperty("rows")
    var rows: Int? = null

    @field:JsonProperty("base_height")
    @field:JsonAlias("baseHeight")
    var baseHeight: Int? = null

    @field:JsonProperty("default_top_tex")
    @field:JsonAlias("defaultTopTex")
    var defaultTopTex: String? = null

    @field:JsonProperty("default_skirt_tex")
    @field:JsonAlias("defaultSkirtTex")
    var defaultSkirtTex: String? = null

    @field:JsonProperty("cells")
    var cells: List<CellPatchDto>? = null

    @field:JsonProperty("resize_cols")
    @field:JsonAlias("resizeCols")
    var resizeCols: Int? = null

    @field:JsonProperty("resize_rows")
    @field:JsonAlias("resizeRows")
    var resizeRows: Int? = null

    @field:JsonProperty("auto_pack")
    @field:JsonAlias("autoPack")
    var autoPack: Boolean? = null

    @field:JsonProperty("output_dir")
    @field:JsonAlias("outputDir")
    var outputDir: String? = null

    @field:JsonProperty("yaw")
    var yaw: Int? = null
}

/**
 * Thin Model Context Protocol (MCP) adapter delegating all domain validation, packing, bitmap
 * import, and level editing operations to [StudioOperations] with a sandboxed [PathPolicy].
 */
class StudioMcpServer(
    transport: ServerMcpTransport? = null,
    val workspaceRoot: Path? = null,
) : AutoCloseable {
    private val objectMapper: ObjectMapper = createStrictMapper()
    private val operations: StudioOperations =
        StudioOperations(
            pathPolicy = workspaceRoot?.let { PathPolicy.sandboxed(it) } ?: PathPolicy.UNRESTRICTED,
        )

    private val syncServer: McpSyncServer?
    private val stopLatch = CountDownLatch(1)

    init {
        if (transport != null) {
            val packTexturesTool =
                Tool(
                    "sfx_pack_textures",
                    "Packs, updates, imports, or validates a SuperFX Texture Atlas (.sfxproj / .sfxatlas), " +
                        "exporting companion binaries (.bin, .pal, .tab), ca65 assembly index (.i), and layout PNG.",
                    SFX_PACK_TEXTURES_SCHEMA,
                )

            val manageLevelTool =
                Tool(
                    "sfx_manage_level",
                    "Creates, edits cells, validates, imports from ca65, or packs a SuperFX Isometric Level Document " +
                        "(.sfxlevel), exporting runtime binary (.map), constants header (.i), and software preview PNG.",
                    SFX_MANAGE_LEVEL_SCHEMA,
                )

            val capabilities = ServerCapabilities.builder().tools(true).build()
            this.syncServer =
                McpServer
                    .sync(transport)
                    .serverInfo("sfx-studio", "1.0.0-SNAPSHOT")
                    .capabilities(capabilities)
                    .tool(packTexturesTool) { args -> executeTool("sfx_pack_textures", args) }
                    .tool(manageLevelTool) { args -> executeTool("sfx_manage_level", args) }
                    .build()
        } else {
            this.syncServer = null
        }
    }

    /**
     * Resolves [rawPath] against [workspaceRoot] and enforces sandbox boundaries (including symlink checks).
     */
    fun resolveAndValidatePath(rawPath: String): Path = operations.pathPolicy.resolve(rawPath)

    fun executeTool(
        name: String,
        args: Map<String, Any?>,
    ): CallToolResult =
        try {
            when (name) {
                "sfx_pack_textures" -> handlePackTextures(args)
                "sfx_manage_level" -> handleManageLevel(args)
                else -> throw IllegalArgumentException("Unknown tool: '$name'")
            }
        } catch (se: SecurityException) {
            LOG.log(Level.WARNING, "Security boundary violation: ${se.message}")
            CallToolResult(listOf(TextContent(se.message ?: "Access denied")), true)
        } catch (t: Throwable) {
            LOG.log(Level.WARNING, "Error executing MCP tool '$name': ${t.message}", t)
            CallToolResult(listOf(TextContent("Error executing tool '$name': ${t.message}")), true)
        }

    private fun handlePackTextures(args: Map<String, Any?>): CallToolResult {
        val req = parseRequest(args, PackTexturesRequest::class.java)
        val rawProjectPath =
            req.projectPath?.takeIf { it.isNotBlank() }
                ?: return CallToolResult(listOf(TextContent("Missing required argument 'project_path' or 'projectPath'")), true)

        val action = (req.action ?: "pack").lowercase()
        val opResult =
            when (action) {
                "validate" -> operations.validateAtlas(rawProjectPath)
                "pack" ->
                    operations.packAtlas(
                        projectPath = rawProjectPath,
                        outputDir = req.outputDir,
                        autoPack = req.autoPack,
                    )
                "import" -> {
                    val resolution =
                        when {
                            req.snapPalette -> ConflictResolution.SNAP_TO_PALETTE
                            req.autoQuantize -> ConflictResolution.GLOBAL_QUANTIZE
                            else -> ConflictResolution.FAIL
                        }
                    val placement =
                        if (req.autoGrow) PlacementPolicy.AUTO_GROW_MAX_256 else PlacementPolicy.STRICT_BOUNDS
                    operations.importAtlasImages(
                        projectPath = rawProjectPath,
                        imagePaths = req.imagePaths.orEmpty(),
                        conflictResolution = resolution,
                        placementPolicy = placement,
                    )
                }
                else ->
                    return CallToolResult(
                        listOf(TextContent("Unknown action: '$action'. Supported actions: pack, import, validate")),
                        true,
                    )
            }

        return CallToolResult(
            listOf(TextContent(objectMapper.writeValueAsString(opResult.toMcpResponse(action, rawProjectPath)))),
            false,
        )
    }

    private fun handleManageLevel(args: Map<String, Any?>): CallToolResult {
        val req = parseRequest(args, ManageLevelRequest::class.java)
        val rawLevelPath =
            req.levelPath?.takeIf { it.isNotBlank() }
                ?: return CallToolResult(listOf(TextContent("Missing required argument 'level_path' or 'levelPath'")), true)

        val action = (req.action ?: "pack").lowercase()
        val opResult =
            when (action) {
                "create" -> {
                    val rawAtlas =
                        req.atlasPath?.takeIf { it.isNotBlank() }
                            ?: return CallToolResult(
                                listOf(TextContent("Missing required argument 'atlas_path' or 'atlasPath' for 'create' action")),
                                true,
                            )
                    operations.createLevel(
                        levelPath = rawLevelPath,
                        atlasPath = rawAtlas,
                        cols = req.cols ?: 8,
                        rows = req.rows ?: 8,
                        defaultTopTex = req.defaultTopTex,
                        defaultSkirtTex = req.defaultSkirtTex,
                        baseHeight = req.baseHeight ?: 0,
                        yaw = req.yaw ?: 15,
                    )
                }
                "set_cells" -> {
                    val patches = req.cells?.map { it.toDomain() }.orEmpty()
                    if (patches.isEmpty() && req.resizeCols == null && req.resizeRows == null) {
                        return CallToolResult(
                            listOf(TextContent("Missing non-empty 'cells' list (or resize dimensions) for 'set_cells' action")),
                            true,
                        )
                    }
                    operations.setLevelCells(
                        levelPath = rawLevelPath,
                        patches = patches,
                        resizeCols = req.resizeCols,
                        resizeRows = req.resizeRows,
                        autoPack = req.autoPack ?: true,
                        yaw = req.yaw ?: 15,
                    )
                }
                "pack" ->
                    operations.packLevel(
                        levelPath = rawLevelPath,
                        outputDir = req.outputDir,
                        yaw = req.yaw ?: 15,
                    )
                "validate" -> operations.validateLevel(rawLevelPath)
                "import_ca65" -> {
                    val rawCa65 = req.ca65Path?.takeIf { it.isNotBlank() }
                    val rawAtlas = req.atlasPath?.takeIf { it.isNotBlank() }
                    if (rawCa65 == null || rawAtlas == null) {
                        return CallToolResult(
                            listOf(TextContent("Missing required 'ca65_path' or 'atlas_path' for 'import_ca65' action")),
                            true,
                        )
                    }
                    operations.importCa65Level(
                        ca65Path = rawCa65,
                        atlasPath = rawAtlas,
                        levelPath = rawLevelPath,
                        yaw = req.yaw ?: 15,
                    )
                }
                else ->
                    return CallToolResult(
                        listOf(
                            TextContent(
                                "Unknown action: '$action'. Supported actions: create, set_cells, pack, validate, import_ca65",
                            ),
                        ),
                        true,
                    )
            }

        return CallToolResult(
            listOf(TextContent(objectMapper.writeValueAsString(opResult.toMcpResponse(action, rawLevelPath)))),
            false,
        )
    }

    private fun <T> parseRequest(
        args: Map<String, Any?>,
        clazz: Class<T>,
    ): T {
        val jsonBytes = objectMapper.writeValueAsBytes(args)
        return objectMapper.readValue(jsonBytes, clazz)
    }

    private fun AtlasOperationResult.toMcpResponse(
        action: String,
        rawProjectPath: String,
    ): PackTexturesResponse {
        val atlas = document?.atlas
        return PackTexturesResponse(
            status = if (valid) "SUCCESS" else "FAIL",
            valid = valid,
            action = action,
            projectPath = rawProjectPath,
            atlasWidth = atlas?.width,
            atlasHeight = atlas?.height,
            textureCount = atlas?.textures?.size,
            sliceCount = atlas?.slices?.size,
            aliasCount = atlas?.aliases?.size,
            descriptorCount = if (valid) descriptorCount else null,
            exportedFiles = exportedFiles.takeIf { it.isNotEmpty() },
            message = message,
        )
    }

    private fun LevelOperationResult.toMcpResponse(
        action: String,
        rawLevelPath: String,
    ): ManageLevelResponse {
        val grid = document?.grid
        // Distinguish validation errors from missing-file errors (preserving null errors on missing file)
        val fileNotFoundCodes = setOf("LEVEL_FILE_NOT_FOUND", "ATLAS_FILE_NOT_FOUND", "CA65_FILE_NOT_FOUND", "INVALID_LEVEL_FILE")
        val reportedErrors =
            validation.errors
                .takeIf { list ->
                    list.isNotEmpty() && (action == "validate" || list.none { it.code in fileNotFoundCodes })
                }
        return ManageLevelResponse(
            status = if (valid) "SUCCESS" else "FAIL",
            valid = valid,
            action = action,
            levelPath = rawLevelPath,
            cols = grid?.cols,
            rows = grid?.rows,
            mapByteSize = if (valid || action == "validate") grid?.byteSize else null,
            errors = reportedErrors,
            warnings = validation.warnings.takeIf { it.isNotEmpty() },
            exportedFiles = exportedFiles.takeIf { it.isNotEmpty() },
            message = message,
        )
    }

    fun await() {
        stopLatch.await()
    }

    override fun close() {
        try {
            syncServer?.close()
        } catch (e: Exception) {
            LOG.log(Level.FINE, "Error closing MCP sync server: ${e.message}", e)
        }
        stopLatch.countDown()
    }

    companion object {
        private val LOG = Logger.getLogger(StudioMcpServer::class.java.name)

        internal fun createStrictMapper(): ObjectMapper =
            JsonMapper
                .builder()
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build()

        private const val SFX_PACK_TEXTURES_SCHEMA = """{
  "type": "object",
  "properties": {
    "project_path": {
      "type": "string",
      "description": "Workspace-relative or absolute path to the .sfxproj or .sfxatlas manifest"
    },
    "projectPath": {
      "type": "string",
      "description": "Alias for project_path: path to the .sfxproj or .sfxatlas manifest"
    },
    "action": {
      "type": "string",
      "enum": ["pack", "import", "validate"],
      "description": "Action to perform: 'pack' (default), 'import', or 'validate'"
    },
    "auto_pack": {
      "type": "boolean",
      "description": "Whether to auto-pack textures into the atlas"
    },
    "autoPack": {
      "type": "boolean",
      "description": "Alias for auto_pack"
    },
    "auto_grow": {
      "type": "boolean",
      "description": "Whether 'import' may grow atlas dimensions up to 256x256 when full (default false)"
    },
    "autoGrow": {
      "type": "boolean",
      "description": "Alias for auto_grow"
    },
    "output_dir": {
      "type": "string",
      "description": "Optional alternate directory for exported companion files (.bin, .pal, .tab, .i, _atlas.png)"
    },
    "outputDir": {
      "type": "string",
      "description": "Alias for output_dir"
    },
    "image_paths": {
      "type": "array",
      "items": { "type": "string" },
      "description": "List of image paths to import (required for 'import' action)"
    },
    "imagePaths": {
      "type": "array",
      "items": { "type": "string" },
      "description": "Alias for image_paths"
    },
    "auto_quantize": {
      "type": "boolean",
      "description": "Execute global median-cut color quantization during import"
    },
    "autoQuantize": {
      "type": "boolean",
      "description": "Alias for auto_quantize"
    },
    "snap_palette": {
      "type": "boolean",
      "description": "Snap incoming colors to nearest assigned palette slots during import"
    },
    "snapPalette": {
      "type": "boolean",
      "description": "Alias for snap_palette"
    }
  }
}"""

        private const val SFX_MANAGE_LEVEL_SCHEMA = """{
  "type": "object",
  "properties": {
    "level_path": {
      "type": "string",
      "description": "Workspace-relative or absolute path to the .sfxlevel Level Document"
    },
    "levelPath": {
      "type": "string",
      "description": "Alias for level_path"
    },
    "action": {
      "type": "string",
      "enum": ["create", "set_cells", "pack", "validate", "import_ca65"],
      "description": "Action to perform: 'create', 'set_cells', 'pack' (default), 'validate', or 'import_ca65'"
    },
    "atlas_path": {
      "type": "string",
      "description": "Path to the shared .sfxproj / .sfxatlas manifest (required for 'create' and 'import_ca65')"
    },
    "atlasPath": {
      "type": "string",
      "description": "Alias for atlas_path"
    },
    "ca65_path": {
      "type": "string",
      "description": "Path to legacy ca65 def_cell map include .i file (required for 'import_ca65')"
    },
    "ca65Path": {
      "type": "string",
      "description": "Alias for ca65_path"
    },
    "cols": {
      "type": "integer",
      "description": "Grid column count (4..16, default 8) for 'create'"
    },
    "rows": {
      "type": "integer",
      "description": "Grid row count (4..16, default 8) for 'create'"
    },
    "base_height": {
      "type": "integer",
      "description": "Default cell base height (0..31, default 0) for 'create'"
    },
    "default_top_tex": {
      "type": "string",
      "description": "Default symbolic top texture name for 'create'"
    },
    "default_skirt_tex": {
      "type": "string",
      "description": "Default symbolic skirt texture name for 'create'"
    },
    "resize_cols": {
      "type": "integer",
      "description": "Optional resized column count (4..16) applied before 'set_cells'"
    },
    "resize_rows": {
      "type": "integer",
      "description": "Optional resized row count (4..16) applied before 'set_cells'"
    },
    "cells": {
      "type": "array",
      "description": "List of cell or region patch objects for 'set_cells'",
      "items": {
        "type": "object",
        "properties": {
          "col": { "type": "integer" },
          "row": { "type": "integer" },
          "col_start": { "type": "integer" },
          "col_end": { "type": "integer" },
          "row_start": { "type": "integer" },
          "row_end": { "type": "integer" },
          "base_height": { "type": "integer" },
          "archetype": { "type": "string" },
          "rotation": { "type": "integer" },
          "top_tex": { "type": "string" },
          "skirt_tex": { "type": "string" },
          "panel_tex": { "type": "string" },
          "panels": { "type": "array", "items": { "type": "string" } },
          "tall_2h": { "type": "boolean" }
        }
      }
    },
    "auto_pack": {
      "type": "boolean",
      "description": "Export .map, .i, and _preview.png automatically after 'set_cells' (default true)"
    },
    "output_dir": {
      "type": "string",
      "description": "Optional output directory for exported companion files during 'pack'"
    },
    "yaw": {
      "type": "integer",
      "description": "Camera yaw step (0..119, default 15) for software _preview.png render"
    }
  }
}"""

        @JvmStatic
        fun createStdio(workspaceRoot: Path? = null): StudioMcpServer {
            val root =
                workspaceRoot?.toAbsolutePath()?.normalize()
                    ?: Path.of("").toAbsolutePath().normalize()
            val mapper =
                ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            val transport = StdioServerTransport(mapper)
            return StudioMcpServer(transport, root)
        }
    }
}
