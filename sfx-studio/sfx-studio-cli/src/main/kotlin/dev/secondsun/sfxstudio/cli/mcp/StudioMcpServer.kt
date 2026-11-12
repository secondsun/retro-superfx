package dev.secondsun.sfxstudio.cli.mcp

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import dev.secondsun.sfxstudio.core.atlas.ImportResult
import dev.secondsun.sfxstudio.core.atlas.MedianCutQuantizer
import dev.secondsun.sfxstudio.core.atlas.ProjectService
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.TextureImporter
import dev.secondsun.sfxstudio.core.level.Ca65MapImporter
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.EdgePanels
import dev.secondsun.sfxstudio.core.level.LevelDiagnostic
import dev.secondsun.sfxstudio.core.level.LevelDocument
import dev.secondsun.sfxstudio.core.level.LevelDocumentService
import dev.secondsun.sfxstudio.core.level.LevelValidator
import dev.secondsun.sfxstudio.core.level.MapCell
import dev.secondsun.sfxstudio.core.level.MapGrid
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.StdioServerTransport
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.modelcontextprotocol.spec.ServerMcpTransport
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Structured response for sfx_pack_textures MCP tool invocations.
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
 * Structured response for sfx_manage_level MCP tool invocations.
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

/**
 * Model Context Protocol (MCP) server for SuperFX Studio, exposing texture atlas packing,
 * image import, isometric level authoring, and validation tools over stdio or custom transports.
 */
class StudioMcpServer(
    transport: ServerMcpTransport? = null,
    val workspaceRoot: Path? = null,
) : AutoCloseable {
    private val objectMapper: ObjectMapper =
        ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)

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
     * Resolves a path against workspaceRoot (if relative) and ensures it resides within the workspace boundary.
     * Throws [SecurityException] if outside workspace boundary.
     */
    fun resolveAndValidatePath(rawPath: String): Path {
        val raw = Path.of(rawPath)
        val resolved =
            if (raw.isAbsolute) {
                raw.normalize()
            } else {
                (workspaceRoot ?: Path.of("").toAbsolutePath()).resolve(raw).normalize()
            }

        if (workspaceRoot != null) {
            val canonicalRoot = workspaceRoot.toAbsolutePath().normalize()
            val canonicalTarget = resolved.toAbsolutePath().normalize()
            if (!canonicalTarget.startsWith(canonicalRoot)) {
                throw SecurityException(
                    "Access denied: path '$rawPath' resolved to '$canonicalTarget' which is outside workspace root '$canonicalRoot'",
                )
            }
        }
        return resolved
    }

    private fun formatPath(file: File): String {
        val path = file.toPath().toAbsolutePath().normalize()
        return if (workspaceRoot != null && path.startsWith(workspaceRoot.toAbsolutePath().normalize())) {
            workspaceRoot
                .toAbsolutePath()
                .normalize()
                .relativize(path)
                .toString()
        } else {
            path.toString()
        }
    }

    private fun computeRelativeAtlasPath(
        levelFile: File,
        atlasFile: File,
    ): String {
        val levelDir = (levelFile.absoluteFile.parentFile ?: File(".").absoluteFile).toPath().normalize()
        val targetAtlas = atlasFile.absoluteFile.toPath().normalize()
        return try {
            levelDir.relativize(targetAtlas).toString().replace('\\', '/')
        } catch (_: IllegalArgumentException) {
            atlasFile.path
        }
    }

    /**
     * Executes an MCP tool by name with arguments and returns CallToolResult.
     */
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

    private fun handleManageLevel(args: Map<String, Any?>): CallToolResult {
        val rawLevelPath = (args["level_path"] ?: args["levelPath"]) as? String
        if (rawLevelPath.isNullOrBlank()) {
            return CallToolResult(
                listOf(TextContent("Missing required argument 'level_path' or 'levelPath'")),
                true,
            )
        }

        val levelFile = resolveAndValidatePath(rawLevelPath).toFile()
        val action = ((args["action"] as? String) ?: "pack").lowercase()

        return when (action) {
            "create" -> handleLevelCreate(rawLevelPath, levelFile, args)
            "set_cells" -> handleLevelSetCells(rawLevelPath, levelFile, args)
            "pack" -> handleLevelPack(rawLevelPath, levelFile, args)
            "validate" -> handleLevelValidate(rawLevelPath, levelFile)
            "import_ca65" -> handleLevelImportCa65(rawLevelPath, levelFile, args)
            else ->
                CallToolResult(
                    listOf(
                        TextContent(
                            "Unknown action: '$action'. Supported actions: create, set_cells, pack, validate, import_ca65",
                        ),
                    ),
                    true,
                )
        }
    }

    private fun handleLevelCreate(
        rawLevelPath: String,
        levelFile: File,
        args: Map<String, Any?>,
    ): CallToolResult {
        val rawAtlasPath = (args["atlas_path"] ?: args["atlasPath"]) as? String
        if (rawAtlasPath.isNullOrBlank()) {
            return CallToolResult(
                listOf(TextContent("Missing required argument 'atlas_path' or 'atlasPath' for 'create' action")),
                true,
            )
        }

        val atlasFile = resolveAndValidatePath(rawAtlasPath).toFile()
        if (!atlasFile.exists()) {
            val resp =
                ManageLevelResponse(
                    status = "FAIL",
                    valid = false,
                    action = "create",
                    levelPath = rawLevelPath,
                    message = "Atlas file '${atlasFile.path}' does not exist.",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val cols = (args["cols"] as? Number)?.toInt() ?: 8
        val rows = (args["rows"] as? Number)?.toInt() ?: 8
        val baseHeight = ((args["base_height"] ?: args["baseHeight"]) as? Number)?.toInt() ?: 0
        val yaw = (args["yaw"] as? Number)?.toInt() ?: 15

        val (atlas, palette, _) = ProjectService.loadProject(atlasFile)
        val descriptors = atlas.resolvedDescriptors()
        val defaultTex = descriptors.firstOrNull()?.name
        val topTex = ((args["default_top_tex"] ?: args["defaultTopTex"]) as? String) ?: defaultTex
        val skirtTex = ((args["default_skirt_tex"] ?: args["defaultSkirtTex"]) as? String) ?: topTex
        val relAtlasPath = computeRelativeAtlasPath(levelFile, atlasFile)

        val doc =
            LevelDocument(
                name = levelFile.nameWithoutExtension,
                atlasPath = relAtlasPath,
                grid =
                    MapGrid.filled(
                        cols = cols,
                        rows = rows,
                        defaultCell = MapCell(baseHeight = baseHeight, topTex = topTex, skirtTex = skirtTex),
                    ),
            )

        val validation = LevelValidator.validate(levelFile, doc, overrideAtlas = atlas)
        if (!validation.valid) {
            val resp =
                ManageLevelResponse(
                    status = "FAIL",
                    valid = false,
                    action = "create",
                    levelPath = rawLevelPath,
                    cols = cols,
                    rows = rows,
                    errors = validation.errors,
                    warnings = validation.warnings.takeIf { it.isNotEmpty() },
                    message = "Level creation failed validation (${validation.errors.size} error(s)).",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val exported =
            LevelDocumentService.saveLevel(
                levelFile = levelFile,
                document = doc,
                atlas = atlas,
                palette = palette,
                previewYaw = yaw,
            )

        val exportedFiles =
            buildList {
                add(exported.levelFile)
                add(exported.mapBinFile)
                add(exported.headerFile)
                exported.previewPngFile?.let { add(it) }
            }.map { formatPath(it) }

        val resp =
            ManageLevelResponse(
                status = "SUCCESS",
                valid = true,
                action = "create",
                levelPath = rawLevelPath,
                cols = cols,
                rows = rows,
                mapByteSize = doc.grid.byteSize,
                warnings = validation.warnings.takeIf { it.isNotEmpty() },
                exportedFiles = exportedFiles,
                message = "Created level '${doc.name}' (${cols}x$rows).",
            )
        return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
    }

    private fun handleLevelSetCells(
        rawLevelPath: String,
        levelFile: File,
        args: Map<String, Any?>,
    ): CallToolResult {
        if (!levelFile.exists()) {
            val resp =
                ManageLevelResponse(
                    status = "FAIL",
                    valid = false,
                    action = "set_cells",
                    levelPath = rawLevelPath,
                    message = "Level file '${levelFile.path}' does not exist.",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val rawCells = args["cells"] as? List<*>
        val resizeCols = ((args["resize_cols"] ?: args["resizeCols"]) as? Number)?.toInt()
        val resizeRows = ((args["resize_rows"] ?: args["resizeRows"]) as? Number)?.toInt()
        if (rawCells.isNullOrEmpty() && resizeCols == null && resizeRows == null) {
            return CallToolResult(
                listOf(TextContent("Missing non-empty 'cells' list (or resize dimensions) for 'set_cells' action")),
                true,
            )
        }

        val loadedDoc = LevelDocumentService.loadLevel(levelFile)
        val resolvedAtlasFile =
            resolveAndValidatePath(
                LevelDocumentService.resolveAtlasFile(levelFile, loadedDoc.atlasPath).path,
            ).toFile()

        var grid = loadedDoc.grid
        if (resizeCols != null || resizeRows != null) {
            val defaultFill = grid.cells.firstOrNull() ?: MapCell()
            grid = grid.resized(resizeCols ?: grid.cols, resizeRows ?: grid.rows, defaultFill)
        }

        var updatedCellCount = 0
        for (rawPatch in rawCells.orEmpty()) {
            val patch =
                rawPatch as? Map<*, *>
                    ?: throw IllegalArgumentException("Each entry in 'cells' must be an object")
            val colSingle = (patch["col"] as? Number)?.toInt()
            val rowSingle = (patch["row"] as? Number)?.toInt()
            val colStart = ((patch["col_start"] ?: patch["colStart"]) as? Number)?.toInt() ?: colSingle
            val colEnd = ((patch["col_end"] ?: patch["colEnd"]) as? Number)?.toInt() ?: colSingle
            val rowStart = ((patch["row_start"] ?: patch["rowStart"]) as? Number)?.toInt() ?: rowSingle
            val rowEnd = ((patch["row_end"] ?: patch["rowEnd"]) as? Number)?.toInt() ?: rowSingle

            if (colStart == null || colEnd == null || rowStart == null || rowEnd == null) {
                throw IllegalArgumentException(
                    "Cell patch must specify (col, row) or (col_start..col_end, row_start..row_end)",
                )
            }

            val updates = mutableMapOf<Pair<Int, Int>, MapCell>()
            for (r in rowStart..rowEnd) {
                for (c in colStart..colEnd) {
                    val prev = grid.cellAt(c, r)
                    updates[c to r] = applyCellPatch(prev, patch)
                    updatedCellCount++
                }
            }
            grid = grid.withCells(updates)
        }

        val updatedDoc = loadedDoc.copy(grid = grid)
        val autoPack = ((args["auto_pack"] ?: args["autoPack"]) as? Boolean) ?: true
        val yaw = (args["yaw"] as? Number)?.toInt() ?: 15

        if (!resolvedAtlasFile.exists()) {
            val resp =
                ManageLevelResponse(
                    status = "FAIL",
                    valid = false,
                    action = "set_cells",
                    levelPath = rawLevelPath,
                    cols = grid.cols,
                    rows = grid.rows,
                    message = "Atlas file '${resolvedAtlasFile.path}' does not exist.",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val (atlas, palette, _) = ProjectService.loadProject(resolvedAtlasFile)
        val validation = LevelValidator.validate(levelFile, updatedDoc, overrideAtlas = atlas)
        if (!validation.valid) {
            val resp =
                ManageLevelResponse(
                    status = "FAIL",
                    valid = false,
                    action = "set_cells",
                    levelPath = rawLevelPath,
                    cols = grid.cols,
                    rows = grid.rows,
                    errors = validation.errors,
                    warnings = validation.warnings.takeIf { it.isNotEmpty() },
                    message = "Cell update failed validation (${validation.errors.size} error(s)).",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val exportedFiles =
            if (autoPack) {
                val exported =
                    LevelDocumentService.saveLevel(
                        levelFile = levelFile,
                        document = updatedDoc,
                        atlas = atlas,
                        palette = palette,
                        previewYaw = yaw,
                    )
                buildList {
                    add(exported.levelFile)
                    add(exported.mapBinFile)
                    add(exported.headerFile)
                    exported.previewPngFile?.let { add(it) }
                }.map { formatPath(it) }
            } else {
                levelFile.parentFile?.mkdirs()
                levelFile.writeText(LevelDocumentService.toJson(updatedDoc))
                listOf(formatPath(levelFile))
            }

        val resp =
            ManageLevelResponse(
                status = "SUCCESS",
                valid = true,
                action = "set_cells",
                levelPath = rawLevelPath,
                cols = grid.cols,
                rows = grid.rows,
                mapByteSize = grid.byteSize,
                warnings = validation.warnings.takeIf { it.isNotEmpty() },
                exportedFiles = exportedFiles,
                message = "Updated $updatedCellCount cell(s) in '${updatedDoc.name}'.",
            )
        return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
    }

    private fun applyCellPatch(
        prev: MapCell,
        patch: Map<*, *>,
    ): MapCell {
        val baseHeight =
            ((patch["base_height"] ?: patch["baseHeight"]) as? Number)?.toInt() ?: prev.baseHeight

        val archetype =
            when (val rawArch = patch["archetype"]) {
                null -> prev.archetype
                is Number -> ShapeArchetype.fromId(rawArch.toInt())
                else -> ShapeArchetype.fromSymbol(rawArch.toString())
            }

        val rotation =
            when (val rawRot = patch["rotation"]) {
                null -> prev.rotation
                is Number -> CellRotation.fromDegreesOrIndex(rawRot.toInt())
                else -> CellRotation.fromSymbol(rawRot.toString())
            }

        val hasTopKey = patch.containsKey("top_tex") || patch.containsKey("topTex")
        val topTex =
            if (hasTopKey) {
                ((patch["top_tex"] ?: patch["topTex"]) as? String)?.takeIf { it.isNotBlank() }
            } else {
                prev.topTex
            }

        val hasSkirtKey = patch.containsKey("skirt_tex") || patch.containsKey("skirtTex")
        val skirtTex =
            if (hasSkirtKey) {
                ((patch["skirt_tex"] ?: patch["skirtTex"]) as? String)?.takeIf { it.isNotBlank() }
            } else {
                prev.skirtTex
            }

        val hasPanelTexKey = patch.containsKey("panel_tex") || patch.containsKey("panelTex")
        val panelTex =
            if (hasPanelTexKey) {
                ((patch["panel_tex"] ?: patch["panelTex"]) as? String)?.takeIf { it.isNotBlank() }
            } else {
                prev.panelTex
            }

        val hasTallKey = patch.containsKey("tall_2h") || patch.containsKey("tall2h")
        val tall2h =
            if (hasTallKey) {
                ((patch["tall_2h"] ?: patch["tall2h"]) as? Boolean) ?: false
            } else {
                prev.panels.tall2h
            }

        val panels =
            if (patch.containsKey("panels")) {
                val rawList = (patch["panels"] as? List<*>)?.map { it.toString() }.orEmpty()
                EdgePanels.fromEdgeNames(rawList, tall2h)
            } else if (hasTallKey) {
                prev.panels.copy(tall2h = prev.panels.hasAny && tall2h)
            } else {
                prev.panels
            }

        return MapCell(
            baseHeight = baseHeight,
            archetype = archetype,
            rotation = rotation,
            topTex = topTex,
            skirtTex = skirtTex,
            panelTex = panelTex,
            panels = panels,
        )
    }

    private fun handleLevelPack(
        rawLevelPath: String,
        levelFile: File,
        args: Map<String, Any?>,
    ): CallToolResult {
        if (!levelFile.exists()) {
            val resp =
                ManageLevelResponse(
                    status = "FAIL",
                    valid = false,
                    action = "pack",
                    levelPath = rawLevelPath,
                    message = "Level file '${levelFile.path}' does not exist.",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val rawOutputDir = (args["output_dir"] ?: args["outputDir"]) as? String
        val outputDir = rawOutputDir?.let { resolveAndValidatePath(it).toFile() }
        val yaw = (args["yaw"] as? Number)?.toInt() ?: 15

        val doc = LevelDocumentService.loadLevel(levelFile)
        val resolvedAtlasFile =
            resolveAndValidatePath(
                LevelDocumentService.resolveAtlasFile(levelFile, doc.atlasPath).path,
            ).toFile()
        if (!resolvedAtlasFile.exists()) {
            val resp =
                ManageLevelResponse(
                    status = "FAIL",
                    valid = false,
                    action = "pack",
                    levelPath = rawLevelPath,
                    cols = doc.grid.cols,
                    rows = doc.grid.rows,
                    message = "Atlas file '${resolvedAtlasFile.path}' does not exist.",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val (atlas, palette, _) = ProjectService.loadProject(resolvedAtlasFile)
        val validation = LevelValidator.validate(levelFile, doc, overrideAtlas = atlas)
        if (!validation.valid) {
            val resp =
                ManageLevelResponse(
                    status = "FAIL",
                    valid = false,
                    action = "pack",
                    levelPath = rawLevelPath,
                    cols = doc.grid.cols,
                    rows = doc.grid.rows,
                    errors = validation.errors,
                    warnings = validation.warnings.takeIf { it.isNotEmpty() },
                    message = "Level pack failed validation (${validation.errors.size} error(s)).",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val exported =
            LevelDocumentService.saveLevel(
                levelFile = levelFile,
                document = doc,
                atlas = atlas,
                palette = palette,
                outputDir = outputDir,
                previewYaw = yaw,
            )
        val exportedFiles =
            buildList {
                add(exported.levelFile)
                add(exported.mapBinFile)
                add(exported.headerFile)
                exported.previewPngFile?.let { add(it) }
            }.map { formatPath(it) }

        val resp =
            ManageLevelResponse(
                status = "SUCCESS",
                valid = true,
                action = "pack",
                levelPath = rawLevelPath,
                cols = doc.grid.cols,
                rows = doc.grid.rows,
                mapByteSize = doc.grid.byteSize,
                warnings = validation.warnings.takeIf { it.isNotEmpty() },
                exportedFiles = exportedFiles,
                message = "Successfully packed level '${doc.name}' (${doc.grid.cols}x${doc.grid.rows}).",
            )
        return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
    }

    private fun handleLevelValidate(
        rawLevelPath: String,
        levelFile: File,
    ): CallToolResult {
        if (!levelFile.exists()) {
            val resp =
                ManageLevelResponse(
                    status = "FAIL",
                    valid = false,
                    action = "validate",
                    levelPath = rawLevelPath,
                    message = "Level file '${levelFile.path}' does not exist.",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val doc =
            try {
                LevelDocumentService.loadLevel(levelFile)
            } catch (e: Exception) {
                val resp =
                    ManageLevelResponse(
                        status = "FAIL",
                        valid = false,
                        action = "validate",
                        levelPath = rawLevelPath,
                        message = "Failed to parse level '${levelFile.path}': ${e.message}",
                    )
                return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
            }

        resolveAndValidatePath(LevelDocumentService.resolveAtlasFile(levelFile, doc.atlasPath).path)

        val validation = LevelValidator.validate(levelFile, doc)
        val resp =
            ManageLevelResponse(
                status = if (validation.valid) "SUCCESS" else "FAIL",
                valid = validation.valid,
                action = "validate",
                levelPath = rawLevelPath,
                cols = doc.grid.cols,
                rows = doc.grid.rows,
                mapByteSize = doc.grid.byteSize,
                errors = validation.errors.takeIf { it.isNotEmpty() },
                warnings = validation.warnings.takeIf { it.isNotEmpty() },
                message =
                    if (validation.valid) {
                        "Validation passed (${validation.warnings.size} warning(s))."
                    } else {
                        "Validation failed with ${validation.errors.size} error(s)."
                    },
            )
        return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
    }

    private fun handleLevelImportCa65(
        rawLevelPath: String,
        levelFile: File,
        args: Map<String, Any?>,
    ): CallToolResult {
        val rawCa65Path = (args["ca65_path"] ?: args["ca65Path"]) as? String
        val rawAtlasPath = (args["atlas_path"] ?: args["atlasPath"]) as? String
        if (rawCa65Path.isNullOrBlank() || rawAtlasPath.isNullOrBlank()) {
            return CallToolResult(
                listOf(TextContent("Missing required 'ca65_path' or 'atlas_path' for 'import_ca65' action")),
                true,
            )
        }

        val ca65File = resolveAndValidatePath(rawCa65Path).toFile()
        val atlasFile = resolveAndValidatePath(rawAtlasPath).toFile()
        if (!ca65File.exists()) {
            val resp =
                ManageLevelResponse(
                    status = "FAIL",
                    valid = false,
                    action = "import_ca65",
                    levelPath = rawLevelPath,
                    message = "CA65 map file '${ca65File.path}' does not exist.",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }
        if (!atlasFile.exists()) {
            val resp =
                ManageLevelResponse(
                    status = "FAIL",
                    valid = false,
                    action = "import_ca65",
                    levelPath = rawLevelPath,
                    message = "Atlas file '${atlasFile.path}' does not exist.",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val yaw = (args["yaw"] as? Number)?.toInt() ?: 15
        val (atlas, palette, _) = ProjectService.loadProject(atlasFile)
        val relAtlasPath = computeRelativeAtlasPath(levelFile, atlasFile)
        val doc =
            Ca65MapImporter.importFile(
                ca65File = ca65File,
                atlasPath = relAtlasPath,
                atlas = atlas,
                levelName = levelFile.nameWithoutExtension,
            )

        val validation = LevelValidator.validate(levelFile, doc, overrideAtlas = atlas)
        if (!validation.valid) {
            val resp =
                ManageLevelResponse(
                    status = "FAIL",
                    valid = false,
                    action = "import_ca65",
                    levelPath = rawLevelPath,
                    cols = doc.grid.cols,
                    rows = doc.grid.rows,
                    errors = validation.errors,
                    warnings = validation.warnings.takeIf { it.isNotEmpty() },
                    message = "Imported ca65 map failed validation (${validation.errors.size} error(s)).",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val exported =
            LevelDocumentService.saveLevel(
                levelFile = levelFile,
                document = doc,
                atlas = atlas,
                palette = palette,
                previewYaw = yaw,
            )
        val exportedFiles =
            buildList {
                add(exported.levelFile)
                add(exported.mapBinFile)
                add(exported.headerFile)
                exported.previewPngFile?.let { add(it) }
            }.map { formatPath(it) }

        val resp =
            ManageLevelResponse(
                status = "SUCCESS",
                valid = true,
                action = "import_ca65",
                levelPath = rawLevelPath,
                cols = doc.grid.cols,
                rows = doc.grid.rows,
                mapByteSize = doc.grid.byteSize,
                warnings = validation.warnings.takeIf { it.isNotEmpty() },
                exportedFiles = exportedFiles,
                message = "Imported '${ca65File.name}' into '${doc.name}' (${doc.grid.cols}x${doc.grid.rows}).",
            )
        return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
    }

    private fun handlePackTextures(args: Map<String, Any?>): CallToolResult {
        val rawProjectPath = (args["project_path"] ?: args["projectPath"]) as? String
        if (rawProjectPath.isNullOrBlank()) {
            return CallToolResult(
                listOf(TextContent("Missing required argument 'project_path' or 'projectPath'")),
                true,
            )
        }

        val projectPath = resolveAndValidatePath(rawProjectPath)
        val projectFile = projectPath.toFile()
        val action = ((args["action"] as? String) ?: "pack").lowercase()

        return when (action) {
            "validate" -> handleValidate(rawProjectPath, projectFile)
            "pack" -> handlePack(rawProjectPath, projectFile, args)
            "import" -> handleImport(rawProjectPath, projectFile, args)
            else -> {
                CallToolResult(
                    listOf(TextContent("Unknown action: '$action'. Supported actions: pack, import, validate")),
                    true,
                )
            }
        }
    }

    private fun handleValidate(
        rawProjectPath: String,
        projectFile: File,
    ): CallToolResult {
        if (!projectFile.exists()) {
            val resp =
                PackTexturesResponse(
                    status = "FAIL",
                    valid = false,
                    action = "validate",
                    projectPath = rawProjectPath,
                    message = "Project file '${projectFile.path}' does not exist.",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val (atlas, _, _) =
            try {
                ProjectService.loadProject(projectFile)
            } catch (e: Exception) {
                val resp =
                    PackTexturesResponse(
                        status = "FAIL",
                        valid = false,
                        action = "validate",
                        projectPath = rawProjectPath,
                        message = "Failed to load project '${projectFile.path}': ${e.message}",
                    )
                return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
            }

        // 1. Validate textures within bounds
        for (tex in atlas.textures) {
            if (!tex.isWithinBounds(atlas.width, atlas.height)) {
                val resp =
                    PackTexturesResponse(
                        status = "FAIL",
                        valid = false,
                        action = "validate",
                        projectPath = rawProjectPath,
                        atlasWidth = atlas.width,
                        atlasHeight = atlas.height,
                        textureCount = atlas.textures.size,
                        sliceCount = atlas.slices.size,
                        aliasCount = atlas.aliases.size,
                        message =
                            "Texture '${tex.name}' exceeds atlas bounds: " +
                                "(${tex.x}, ${tex.y}) + (${tex.width}x${tex.height}) > (${atlas.width}x${atlas.height})",
                    )
                return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
            }
        }

        // 2. Validate collisions
        for (i in atlas.textures.indices) {
            for (j in i + 1 until atlas.textures.size) {
                if (atlas.textures[i].intersects(atlas.textures[j])) {
                    val resp =
                        PackTexturesResponse(
                            status = "FAIL",
                            valid = false,
                            action = "validate",
                            projectPath = rawProjectPath,
                            atlasWidth = atlas.width,
                            atlasHeight = atlas.height,
                            textureCount = atlas.textures.size,
                            sliceCount = atlas.slices.size,
                            aliasCount = atlas.aliases.size,
                            message = "Collision detected between '${atlas.textures[i].name}' and '${atlas.textures[j].name}'",
                        )
                    return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
                }
            }
        }

        // 3. Validate descriptors (slices and aliases)
        val descriptors =
            try {
                atlas.resolvedDescriptors()
            } catch (e: Exception) {
                val resp =
                    PackTexturesResponse(
                        status = "FAIL",
                        valid = false,
                        action = "validate",
                        projectPath = rawProjectPath,
                        atlasWidth = atlas.width,
                        atlasHeight = atlas.height,
                        textureCount = atlas.textures.size,
                        sliceCount = atlas.slices.size,
                        aliasCount = atlas.aliases.size,
                        message = "Descriptor resolution failed: ${e.message}",
                    )
                return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
            }

        val resp =
            PackTexturesResponse(
                status = "SUCCESS",
                valid = true,
                action = "validate",
                projectPath = rawProjectPath,
                atlasWidth = atlas.width,
                atlasHeight = atlas.height,
                textureCount = atlas.textures.size,
                sliceCount = atlas.slices.size,
                aliasCount = atlas.aliases.size,
                descriptorCount = descriptors.size,
                message = "Validation passed: ${descriptors.size} descriptors valid.",
            )
        return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
    }

    private fun handlePack(
        rawProjectPath: String,
        projectFile: File,
        args: Map<String, Any?>,
    ): CallToolResult {
        if (!projectFile.exists()) {
            val resp =
                PackTexturesResponse(
                    status = "FAIL",
                    valid = false,
                    action = "pack",
                    projectPath = rawProjectPath,
                    message = "Project file '${projectFile.path}' does not exist.",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val autoPack = ((args["auto_pack"] ?: args["autoPack"]) as? Boolean) ?: false
        val rawOutputDir = (args["output_dir"] ?: args["outputDir"]) as? String
        val outputDir = rawOutputDir?.let { resolveAndValidatePath(it).toFile() }

        val (loadedAtlas, palette, gridSnap) = ProjectService.loadProject(projectFile)
        var atlas = loadedAtlas

        if (autoPack) {
            val packed = atlas.autoPack(gridSnap)
            if (packed == null) {
                val resp =
                    PackTexturesResponse(
                        status = "FAIL",
                        valid = false,
                        action = "pack",
                        projectPath = rawProjectPath,
                        message = "Could not auto-pack textures into ${atlas.width}x${atlas.height} atlas with grid $gridSnap",
                    )
                return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
            }
            atlas = packed
        }

        // Validate bounds
        for (tex in atlas.textures) {
            if (!tex.isWithinBounds(atlas.width, atlas.height)) {
                val resp =
                    PackTexturesResponse(
                        status = "FAIL",
                        valid = false,
                        action = "pack",
                        projectPath = rawProjectPath,
                        message =
                            "Texture '${tex.name}' exceeds atlas bounds: " +
                                "(${tex.x}, ${tex.y}) + (${tex.width}x${tex.height}) > (${atlas.width}x${atlas.height})",
                    )
                return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
            }
        }

        // Validate collisions
        for (i in atlas.textures.indices) {
            for (j in i + 1 until atlas.textures.size) {
                if (atlas.textures[i].intersects(atlas.textures[j])) {
                    val resp =
                        PackTexturesResponse(
                            status = "FAIL",
                            valid = false,
                            action = "pack",
                            projectPath = rawProjectPath,
                            message = "Collision detected between '${atlas.textures[i].name}' and '${atlas.textures[j].name}'",
                        )
                    return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
                }
            }
        }

        val descriptors = atlas.resolvedDescriptors()
        val targetFile = if (outputDir != null) File(outputDir, projectFile.name) else projectFile
        val projectName = projectFile.nameWithoutExtension

        ProjectService.saveProject(targetFile, projectName, atlas, palette, gridSnap)

        val parentDir = targetFile.parentFile ?: File(".")
        val exportedFiles =
            listOf(
                targetFile,
                File(parentDir, "$projectName.bin"),
                File(parentDir, "$projectName.pal"),
                File(parentDir, "$projectName.tab"),
                File(parentDir, "$projectName.i"),
                File(parentDir, "${projectName}_atlas.png"),
            ).map { formatPath(it) }

        val resp =
            PackTexturesResponse(
                status = "SUCCESS",
                valid = true,
                action = "pack",
                projectPath = rawProjectPath,
                atlasWidth = atlas.width,
                atlasHeight = atlas.height,
                textureCount = atlas.textures.size,
                sliceCount = atlas.slices.size,
                aliasCount = atlas.aliases.size,
                descriptorCount = descriptors.size,
                exportedFiles = exportedFiles,
                message = "Successfully packed '$projectName' (${descriptors.size} descriptors).",
            )
        return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
    }

    private fun handleImport(
        rawProjectPath: String,
        projectFile: File,
        args: Map<String, Any?>,
    ): CallToolResult {
        if (!projectFile.exists()) {
            val resp =
                PackTexturesResponse(
                    status = "FAIL",
                    valid = false,
                    action = "import",
                    projectPath = rawProjectPath,
                    message = "Project file '${projectFile.path}' does not exist.",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val autoQuantize = ((args["auto_quantize"] ?: args["autoQuantize"]) as? Boolean) ?: false
        val snapPalette = ((args["snap_palette"] ?: args["snapPalette"]) as? Boolean) ?: false
        val rawImagePaths = (args["image_paths"] ?: args["imagePaths"]) as? List<*>

        if (rawImagePaths.isNullOrEmpty()) {
            val resp =
                PackTexturesResponse(
                    status = "FAIL",
                    valid = false,
                    action = "import",
                    projectPath = rawProjectPath,
                    message = "No image paths specified for import.",
                )
            return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
        }

        val imageFiles =
            rawImagePaths.map { item ->
                val imgPathStr = item.toString()
                val resolvedImgPath = resolveAndValidatePath(imgPathStr)
                val file = resolvedImgPath.toFile()
                if (!file.exists()) {
                    throw IllegalArgumentException("Image file '${file.path}' not found")
                }
                file
            }

        val (loadedAtlas, loadedPalette, gridSnap) = ProjectService.loadProject(projectFile)
        var atlas = loadedAtlas
        var palette = loadedPalette

        for (imgFile in imageFiles) {
            when (val result = TextureImporter.importFromFile(imgFile, palette)) {
                is ImportResult.Success -> {
                    val pos =
                        atlas.findPlacement(result.texture, gridSnap)
                            ?: return CallToolResult(
                                listOf(
                                    TextContent(
                                        objectMapper.writeValueAsString(
                                            PackTexturesResponse(
                                                status = "FAIL",
                                                valid = false,
                                                action = "import",
                                                projectPath = rawProjectPath,
                                                message =
                                                    "Could not find free placement for '${result.texture.name}' " +
                                                        "in ${atlas.width}x${atlas.height} atlas",
                                            ),
                                        ),
                                    ),
                                ),
                                false,
                            )
                    atlas = atlas.withTexture(result.texture.copy(x = pos.first, y = pos.second))
                    palette = result.updatedPalette
                }
                is ImportResult.NeedsRemapping -> {
                    if (snapPalette) {
                        val mapping = mutableMapOf<SnesColor, Int>()
                        for (color in result.uniqueColors) {
                            val existing = palette.findExactIndex(color)
                            mapping[color] = existing ?: palette.findNearestAssignedIndex(color)
                        }
                        val tex =
                            TextureImporter.createTextureFromImage(
                                image = result.rawImage,
                                name = result.suggestedName,
                                colorMapping = mapping,
                                fallbackPalette = palette,
                            )
                        val pos =
                            atlas.findPlacement(tex, gridSnap)
                                ?: return CallToolResult(
                                    listOf(
                                        TextContent(
                                            objectMapper.writeValueAsString(
                                                PackTexturesResponse(
                                                    status = "FAIL",
                                                    valid = false,
                                                    action = "import",
                                                    projectPath = rawProjectPath,
                                                    message = "Could not find free placement for '${tex.name}' in atlas",
                                                ),
                                            ),
                                        ),
                                    ),
                                    false,
                                )
                        atlas = atlas.withTexture(tex.copy(x = pos.first, y = pos.second))
                    } else if (autoQuantize) {
                        val allColors = mutableListOf<SnesColor>()
                        for (tex in atlas.textures) {
                            for (v in 0 until tex.height) {
                                for (u in 0 until tex.width) {
                                    val slot = tex.getPixel(u, v)
                                    if (slot != 0) allColors.add(palette[slot])
                                }
                            }
                        }
                        allColors.addAll(result.uniqueColors)
                        val newPalette = MedianCutQuantizer.quantize(allColors)

                        val remappedTextures =
                            atlas.textures.map { tex ->
                                val remapMap = mutableMapOf<Int, Int>()
                                for (slot in 1..15) {
                                    remapMap[slot] = newPalette.findNearestAssignedIndex(palette[slot])
                                }
                                tex.remapPixels(remapMap)
                            }

                        val incomingMapping = mutableMapOf<SnesColor, Int>()
                        for (color in result.uniqueColors) {
                            incomingMapping[color] = newPalette.findNearestAssignedIndex(color)
                        }
                        val newTex =
                            TextureImporter.createTextureFromImage(
                                image = result.rawImage,
                                name = result.suggestedName,
                                colorMapping = incomingMapping,
                                fallbackPalette = newPalette,
                            )

                        palette = newPalette
                        val tempAtlas = atlas.copy(textures = remappedTextures)
                        val pos =
                            tempAtlas.findPlacement(newTex, gridSnap)
                                ?: return CallToolResult(
                                    listOf(
                                        TextContent(
                                            objectMapper.writeValueAsString(
                                                PackTexturesResponse(
                                                    status = "FAIL",
                                                    valid = false,
                                                    action = "import",
                                                    projectPath = rawProjectPath,
                                                    message = "Could not find free placement for '${newTex.name}' in quantized atlas",
                                                ),
                                            ),
                                        ),
                                    ),
                                    false,
                                )
                        atlas = tempAtlas.withTexture(newTex.copy(x = pos.first, y = pos.second))
                    } else {
                        return CallToolResult(
                            listOf(
                                TextContent(
                                    objectMapper.writeValueAsString(
                                        PackTexturesResponse(
                                            status = "FAIL",
                                            valid = false,
                                            action = "import",
                                            projectPath = rawProjectPath,
                                            message =
                                                "Image '${imgFile.name}' has unmapped colors: " +
                                                    "${result.unmappedColors}. Pass snap_palette or auto_quantize.",
                                        ),
                                    ),
                                ),
                            ),
                            false,
                        )
                    }
                }
                is ImportResult.Error -> {
                    return CallToolResult(
                        listOf(
                            TextContent(
                                objectMapper.writeValueAsString(
                                    PackTexturesResponse(
                                        status = "FAIL",
                                        valid = false,
                                        action = "import",
                                        projectPath = rawProjectPath,
                                        message = "Failed to import '${imgFile.name}': ${result.message}",
                                    ),
                                ),
                            ),
                        ),
                        false,
                    )
                }
            }
        }

        val projectName = projectFile.nameWithoutExtension
        ProjectService.saveProject(projectFile, projectName, atlas, palette, gridSnap)
        val descriptors = atlas.resolvedDescriptors()

        val parentDir = projectFile.parentFile ?: File(".")
        val exportedFiles =
            listOf(
                projectFile,
                File(parentDir, "$projectName.bin"),
                File(parentDir, "$projectName.pal"),
                File(parentDir, "$projectName.tab"),
                File(parentDir, "$projectName.i"),
                File(parentDir, "${projectName}_atlas.png"),
            ).map { formatPath(it) }

        val resp =
            PackTexturesResponse(
                status = "SUCCESS",
                valid = true,
                action = "import",
                projectPath = rawProjectPath,
                atlasWidth = atlas.width,
                atlasHeight = atlas.height,
                textureCount = atlas.textures.size,
                sliceCount = atlas.slices.size,
                aliasCount = atlas.aliases.size,
                descriptorCount = descriptors.size,
                exportedFiles = exportedFiles,
                message = "Successfully imported ${imageFiles.size} images into '$projectName' (${descriptors.size} descriptors).",
            )
        return CallToolResult(listOf(TextContent(objectMapper.writeValueAsString(resp))), false)
    }

    /**
     * Waits until the server is closed.
     */
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
    "output_dir": {
      "type": "string",
      "description": "Optional alternate directory for exported files"
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
    "default_top_tex": {
      "type": "string",
      "description": "Default symbolic top texture name for 'create'"
    },
    "default_skirt_tex": {
      "type": "string",
      "description": "Default symbolic skirt texture name for 'create'"
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
