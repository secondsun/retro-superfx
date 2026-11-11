package dev.secondsun.sfxstudio.cli.mcp

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import dev.secondsun.sfxstudio.core.atlas.ImportResult
import dev.secondsun.sfxstudio.core.atlas.MedianCutQuantizer
import dev.secondsun.sfxstudio.core.atlas.ProjectService
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.TextureImporter
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
 * Model Context Protocol (MCP) server for SuperFX Studio, exposing texture atlas packing,
 * image import, and validation tools over stdio or custom transports.
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
                        "exporting companion binaries (.bin, .pal), ca65 assembly index (.i), and layout PNG.",
                    SFX_PACK_TEXTURES_SCHEMA,
                )

            val capabilities = ServerCapabilities.builder().tools(true).build()

            this.syncServer =
                McpServer
                    .sync(transport)
                    .serverInfo("sfx-studio", "1.0.0-SNAPSHOT")
                    .capabilities(capabilities)
                    .tool(packTexturesTool) { args -> executeTool("sfx_pack_textures", args) }
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
