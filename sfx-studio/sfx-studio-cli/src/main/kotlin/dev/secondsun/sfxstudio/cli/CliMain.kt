package dev.secondsun.sfxstudio.cli

import dev.secondsun.sfxstudio.cli.mcp.StudioMcpServer
import dev.secondsun.sfxstudio.core.atlas.ImportResult
import dev.secondsun.sfxstudio.core.atlas.MedianCutQuantizer
import dev.secondsun.sfxstudio.core.atlas.ProjectService
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.TextureImporter
import dev.secondsun.sfxstudio.core.level.Ca65MapImporter
import dev.secondsun.sfxstudio.core.level.LevelDocument
import dev.secondsun.sfxstudio.core.level.LevelDocumentService
import dev.secondsun.sfxstudio.core.level.LevelValidator
import dev.secondsun.sfxstudio.core.level.MapCell
import dev.secondsun.sfxstudio.core.level.MapGrid
import java.io.File
import java.nio.file.Path
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

object CliMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = execute(args)
        if (exitCode != 0) {
            exitProcess(exitCode)
        }
    }

    /**
     * Executes the CLI commands without calling [exitProcess], returning the exit status code.
     * 0 indicates success, 1 indicates invalid syntax or unknown command, 2 indicates runtime error.
     */
    @JvmStatic
    fun execute(args: Array<String>): Int {
        if (args.isEmpty()) {
            printUsage()
            return 1
        }

        if (args.contains("--mcp")) {
            return runMcp(args)
        }

        val first = args[0]
        if (first in listOf("help", "--help", "-h")) {
            printUsage()
            return 0
        }

        try {
            return when (first) {
                "atlas" -> {
                    val subArgs = args.drop(1)
                    if (subArgs.isEmpty() || subArgs[0] in listOf("help", "--help", "-h")) {
                        printUsage()
                        return if (subArgs.isEmpty()) 1 else 0
                    }
                    when (subArgs[0]) {
                        "pack" -> runPack(subArgs.drop(1))
                        "import" -> runImport(subArgs.drop(1))
                        "validate" -> runValidate(subArgs.drop(1))
                        else -> {
                            System.err.println("Unknown atlas subcommand: '${subArgs[0]}'")
                            printUsage()
                            1
                        }
                    }
                }
                "level" -> {
                    val subArgs = args.drop(1)
                    if (subArgs.isEmpty() || subArgs[0] in listOf("help", "--help", "-h")) {
                        printUsage()
                        return if (subArgs.isEmpty()) 1 else 0
                    }
                    when (subArgs[0]) {
                        "create" -> runLevelCreate(subArgs.drop(1))
                        "pack" -> runLevelPack(subArgs.drop(1))
                        "validate" -> runLevelValidate(subArgs.drop(1))
                        "import-ca65" -> runLevelImportCa65(subArgs.drop(1))
                        else -> {
                            System.err.println("Unknown level subcommand: '${subArgs[0]}'")
                            printUsage()
                            1
                        }
                    }
                }
                "pack" -> runPack(args.drop(1).toList())
                "import" -> runImport(args.drop(1).toList())
                "validate" -> runValidate(args.drop(1).toList())
                else -> {
                    System.err.println("Unknown command: '$first'")
                    printUsage()
                    1
                }
            }
        } catch (e: Exception) {
            System.err.println("Error executing '$first': ${e.message}")
            e.printStackTrace(System.err)
            return 2
        }
    }

    private fun runMcp(args: Array<String>): Int {
        var workspaceRoot: Path? = null
        var i = 0
        while (i < args.size) {
            if (args[i] == "--workspace" && i + 1 < args.size) {
                workspaceRoot = Path.of(args[i + 1])
                i++
            }
            i++
        }

        val root =
            workspaceRoot?.toAbsolutePath()?.normalize()
                ?: Path.of("").toAbsolutePath().normalize()

        // Pure JSON-RPC over stdout: redirect JUL to System.err
        val rootLogger = Logger.getLogger("")
        for (handler in rootLogger.handlers) {
            rootLogger.removeHandler(handler)
        }
        val consoleHandler = ConsoleHandler()
        consoleHandler.level = Level.WARNING
        rootLogger.addHandler(consoleHandler)
        rootLogger.level = Level.WARNING

        val server = StudioMcpServer.createStdio(root)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                server.close()
            },
        )

        server.await()
        return 0
    }

    private fun printUsage() {
        println(
            """
            SuperFX Studio CLI

            Usage:
              atlas pack <project.sfxproj|.sfxatlas> [--output-dir <dir>] [--auto-pack]
                Loads manifest, resolves all virtual slices and aliases, optionally auto-packs,
                and exports companion binaries (.bin, .pal, .tab), assembly index (.i), layout PNG,
                and saves the project.

              atlas import <project.sfxproj|.sfxatlas> <images...> [--auto-quantize] [--snap-palette]
                Imports one or more image files (PNG/BMP) into the project, mapping or
                quantizing colors to the SNES palette, automatically places them, and saves.

              atlas validate <project.sfxproj|.sfxatlas>
                Validates dimensions, bounds, coordinate collisions, slices, and aliases.

              level create <level.sfxlevel> --atlas <atlas.sfxproj> [--cols <4..16>] [--rows <4..16>] [--top-tex <name>] [--skirt-tex <name>]
                Creates a new isometric Level Document linked to an atlas and exports .map, .i, and _preview.png.

              level pack <level.sfxlevel> [--output-dir <dir>] [--yaw <0..119>]
                Validates a Level Document against its shared atlas and exports .map, .i, and _preview.png.

              level validate <level.sfxlevel>
                Validates grid bounds, symbolic texture names, Bank $71 atlas budget, and geometry rules.

              level import-ca65 <input.i> --atlas <atlas.sfxproj> --output <level.sfxlevel> [--yaw <0..119>]
                Imports a legacy ca65 def_cell map include into a .sfxlevel document and exports companion files.

              --mcp [--workspace <path>]
                Starts the Model Context Protocol (MCP) stdio server for SuperFX texture atlas and level tools.

            Legacy Aliases:
              pack ...        -> atlas pack ...
              import ...      -> atlas import ...
              validate ...    -> atlas validate ...
            """.trimIndent(),
        )
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

    private fun runLevelCreate(args: List<String>): Int {
        if (args.isEmpty()) {
            System.err.println(
                "Usage: level create <level.sfxlevel> --atlas <atlas.sfxproj> [--cols <4..16>] [--rows <4..16>] [--top-tex <name>] [--skirt-tex <name>]",
            )
            return 1
        }

        val levelFile = File(args[0])
        var atlasFile: File? = null
        var cols = 8
        var rows = 8
        var topTex: String? = null
        var skirtTex: String? = null
        var yaw = 15

        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--atlas" -> {
                    i++
                    if (i < args.size) atlasFile = File(args[i])
                }
                "--cols" -> {
                    i++
                    if (i < args.size) cols = args[i].toInt()
                }
                "--rows" -> {
                    i++
                    if (i < args.size) rows = args[i].toInt()
                }
                "--top-tex" -> {
                    i++
                    if (i < args.size) topTex = args[i]
                }
                "--skirt-tex" -> {
                    i++
                    if (i < args.size) skirtTex = args[i]
                }
                "--yaw" -> {
                    i++
                    if (i < args.size) yaw = args[i].toInt()
                }
                else -> System.err.println("Warning: ignoring unknown argument '${args[i]}'")
            }
            i++
        }

        val reqAtlasFile =
            atlasFile ?: run {
                System.err.println("Missing required flag: --atlas <atlas.sfxproj>")
                return 1
            }
        if (!reqAtlasFile.exists()) {
            throw IllegalArgumentException("Atlas file '${reqAtlasFile.path}' does not exist.")
        }

        val (atlas, palette, _) = ProjectService.loadProject(reqAtlasFile)
        val descriptors = atlas.resolvedDescriptors()
        val defaultTex = descriptors.firstOrNull()?.name
        val resolvedTop = topTex ?: defaultTex
        val resolvedSkirt = skirtTex ?: resolvedTop
        val relAtlasPath = computeRelativeAtlasPath(levelFile, reqAtlasFile)

        val doc =
            LevelDocument(
                name = levelFile.nameWithoutExtension,
                atlasPath = relAtlasPath,
                grid =
                    MapGrid.filled(
                        cols = cols,
                        rows = rows,
                        defaultCell = MapCell(baseHeight = 0, topTex = resolvedTop, skirtTex = resolvedSkirt),
                    ),
            )

        val validation = LevelValidator.validate(levelFile, doc, overrideAtlas = atlas)
        if (!validation.valid) {
            val msgs = validation.errors.joinToString("; ") { "[${it.code}] ${it.message}" }
            throw IllegalStateException("Level validation failed: $msgs")
        }
        for (warn in validation.warnings) {
            println("Warning [${warn.code}]: ${warn.message}")
        }

        val exported =
            LevelDocumentService.saveLevel(
                levelFile = levelFile,
                document = doc,
                atlas = atlas,
                palette = palette,
                previewYaw = yaw,
            )
        println("Created level '${doc.name}' (${cols}x$rows, ${doc.grid.byteSize} bytes):")
        println("  Manifest: ${exported.levelFile.absolutePath}")
        println("  Binary:   ${exported.mapBinFile.absolutePath}")
        println("  Header:   ${exported.headerFile.absolutePath}")
        exported.previewPngFile?.let { println("  Preview:  ${it.absolutePath}") }
        return 0
    }

    private fun runLevelPack(args: List<String>): Int {
        if (args.isEmpty()) {
            System.err.println("Usage: level pack <level.sfxlevel> [--output-dir <dir>] [--yaw <0..119>]")
            return 1
        }

        val levelFile = File(args[0])
        if (!levelFile.exists()) {
            throw IllegalArgumentException("Level file '${levelFile.path}' does not exist.")
        }

        var outputDir: File? = null
        var yaw = 15

        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--output-dir" -> {
                    i++
                    if (i < args.size) outputDir = File(args[i])
                }
                "--yaw" -> {
                    i++
                    if (i < args.size) yaw = args[i].toInt()
                }
                else -> System.err.println("Warning: ignoring unknown argument '${args[i]}'")
            }
            i++
        }

        val doc = LevelDocumentService.loadLevel(levelFile)
        val atlasFile = LevelDocumentService.resolveAtlasFile(levelFile, doc.atlasPath)
        if (!atlasFile.exists()) {
            throw IllegalStateException("Atlas file '${doc.atlasPath}' resolved to '${atlasFile.path}' does not exist.")
        }
        val (atlas, palette, _) = ProjectService.loadProject(atlasFile)

        val validation = LevelValidator.validate(levelFile, doc, overrideAtlas = atlas)
        for (warn in validation.warnings) {
            println("Warning [${warn.code}]: ${warn.message}")
        }
        if (!validation.valid) {
            val msgs = validation.errors.joinToString("; ") { "[${it.code}] ${it.message}" }
            throw IllegalStateException("Level validation failed: $msgs")
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
        println("Successfully packed level '${doc.name}' (${doc.grid.cols}x${doc.grid.rows}, ${doc.grid.byteSize} bytes):")
        println("  Binary:  ${exported.mapBinFile.absolutePath}")
        println("  Header:  ${exported.headerFile.absolutePath}")
        exported.previewPngFile?.let { println("  Preview: ${it.absolutePath}") }
        return 0
    }

    private fun runLevelValidate(args: List<String>): Int {
        if (args.isEmpty()) {
            System.err.println("Usage: level validate <level.sfxlevel>")
            return 1
        }

        val levelFile = File(args[0])
        if (!levelFile.exists()) {
            throw IllegalArgumentException("Level file '${levelFile.path}' does not exist.")
        }

        val doc = LevelDocumentService.loadLevel(levelFile)
        val validation = LevelValidator.validate(levelFile, doc)
        println("Validating level '${levelFile.name}' (${doc.grid.cols}x${doc.grid.rows})...")
        for (warn in validation.warnings) {
            println("  Warning [${warn.code}]: ${warn.message}")
        }
        if (!validation.valid) {
            for (err in validation.errors) {
                System.err.println("  Error [${err.code}]: ${err.message}")
            }
            throw IllegalStateException("Validation failed with ${validation.errors.size} error(s).")
        }
        println("Validation passed: OK (${validation.warnings.size} warning(s))")
        return 0
    }

    private fun runLevelImportCa65(args: List<String>): Int {
        if (args.isEmpty()) {
            System.err.println(
                "Usage: level import-ca65 <input.i> --atlas <atlas.sfxproj> --output <level.sfxlevel> [--yaw <0..119>]",
            )
            return 1
        }

        val inputFile = File(args[0])
        if (!inputFile.exists()) {
            throw IllegalArgumentException("Input ca65 file '${inputFile.path}' does not exist.")
        }

        var atlasFile: File? = null
        var outputFile: File? = null
        var yaw = 15

        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--atlas" -> {
                    i++
                    if (i < args.size) atlasFile = File(args[i])
                }
                "--output" -> {
                    i++
                    if (i < args.size) outputFile = File(args[i])
                }
                "--yaw" -> {
                    i++
                    if (i < args.size) yaw = args[i].toInt()
                }
                else -> System.err.println("Warning: ignoring unknown argument '${args[i]}'")
            }
            i++
        }

        val reqAtlasFile =
            atlasFile ?: run {
                System.err.println("Missing required flag: --atlas <atlas.sfxproj>")
                return 1
            }
        val reqOutputFile =
            outputFile ?: run {
                System.err.println("Missing required flag: --output <level.sfxlevel>")
                return 1
            }
        if (!reqAtlasFile.exists()) {
            throw IllegalArgumentException("Atlas file '${reqAtlasFile.path}' does not exist.")
        }

        val (atlas, palette, _) = ProjectService.loadProject(reqAtlasFile)
        val relAtlasPath = computeRelativeAtlasPath(reqOutputFile, reqAtlasFile)
        val doc =
            Ca65MapImporter.importFile(
                ca65File = inputFile,
                atlasPath = relAtlasPath,
                atlas = atlas,
                levelName = reqOutputFile.nameWithoutExtension,
            )

        val validation = LevelValidator.validate(reqOutputFile, doc, overrideAtlas = atlas)
        for (warn in validation.warnings) {
            println("Warning [${warn.code}]: ${warn.message}")
        }
        if (!validation.valid) {
            val msgs = validation.errors.joinToString("; ") { "[${it.code}] ${it.message}" }
            throw IllegalStateException("Imported level failed validation: $msgs")
        }

        val exported =
            LevelDocumentService.saveLevel(
                levelFile = reqOutputFile,
                document = doc,
                atlas = atlas,
                palette = palette,
                previewYaw = yaw,
            )
        println("Imported '${inputFile.name}' -> '${exported.levelFile.path}' (${doc.grid.cols}x${doc.grid.rows}):")
        println("  Binary:  ${exported.mapBinFile.absolutePath}")
        println("  Header:  ${exported.headerFile.absolutePath}")
        exported.previewPngFile?.let { println("  Preview: ${it.absolutePath}") }
        return 0
    }

    private fun runPack(args: List<String>): Int {
        if (args.isEmpty()) {
            System.err.println("Usage: atlas pack <project.sfxproj|.sfxatlas> [--output-dir <dir>] [--auto-pack]")
            return 1
        }

        val projectFile = File(args[0])
        if (!projectFile.exists()) {
            throw IllegalArgumentException("Project file '${projectFile.path}' does not exist.")
        }

        var outputDir: File? = null
        var autoPack = false

        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--output-dir" -> {
                    i++
                    if (i < args.size) outputDir = File(args[i])
                }
                "--auto-pack" -> autoPack = true
                else -> System.err.println("Warning: ignoring unknown argument '${args[i]}'")
            }
            i++
        }

        val (loadedAtlas, palette, gridSnap) = ProjectService.loadProject(projectFile)
        var atlas = loadedAtlas

        if (autoPack) {
            val packed =
                atlas.autoPack(gridSnap)
                    ?: throw IllegalStateException(
                        "Could not auto-pack textures into ${atlas.width}x${atlas.height} atlas with grid $gridSnap",
                    )
            atlas = packed
        }

        val targetFile = if (outputDir != null) File(outputDir, projectFile.name) else projectFile
        val projectName = projectFile.nameWithoutExtension

        ProjectService.saveProject(targetFile, projectName, atlas, palette, gridSnap)

        val descriptors = atlas.resolvedDescriptors()
        println("Successfully packed '$projectName':")
        println("  Atlas dimensions: ${atlas.width}x${atlas.height} (${atlas.sizeBytes} bytes)")
        println("  Base textures:    ${atlas.textures.size}")
        println("  Virtual slices:   ${atlas.slices.size}")
        println("  Aliases:          ${atlas.aliases.size}")
        println("  Total descriptors in TEXTURE_TABLE: ${descriptors.size}")
        println("  Saved manifest:   ${targetFile.absolutePath}")
        return 0
    }

    private fun runImport(args: List<String>): Int {
        if (args.size < 2) {
            System.err.println(
                "Usage: atlas import <project.sfxproj|.sfxatlas> <images...> [--auto-quantize] [--snap-palette]",
            )
            return 1
        }

        val projectFile = File(args[0])
        if (!projectFile.exists()) {
            throw IllegalArgumentException("Project file '${projectFile.path}' does not exist.")
        }

        var autoQuantize = false
        var snapPalette = false
        val imageFiles = mutableListOf<File>()

        for (j in 1 until args.size) {
            val arg = args[j]
            when (arg) {
                "--auto-quantize" -> autoQuantize = true
                "--snap-palette" -> snapPalette = true
                else -> {
                    if (arg.startsWith("-")) {
                        System.err.println("Warning: ignoring unknown flag '$arg'")
                    } else {
                        val imgFile = File(arg)
                        if (!imgFile.exists()) throw IllegalArgumentException("Image file '${imgFile.path}' not found")
                        imageFiles.add(imgFile)
                    }
                }
            }
        }

        if (imageFiles.isEmpty()) {
            throw IllegalArgumentException("No image files specified for import")
        }

        val (loadedAtlas, loadedPalette, gridSnap) = ProjectService.loadProject(projectFile)
        var atlas = loadedAtlas
        var palette = loadedPalette

        for (imgFile in imageFiles) {
            println("Importing '${imgFile.name}'...")
            when (val result = TextureImporter.importFromFile(imgFile, palette)) {
                is ImportResult.Success -> {
                    val pos =
                        atlas.findPlacement(result.texture, gridSnap)
                            ?: throw IllegalStateException(
                                "Could not find free placement for '${result.texture.name}' in ${atlas.width}x${atlas.height} atlas",
                            )
                    atlas = atlas.withTexture(result.texture.copy(x = pos.first, y = pos.second))
                    palette = result.updatedPalette
                    println("  Placed '${result.texture.name}' at (${pos.first}, ${pos.second})")
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
                                ?: throw IllegalStateException("Could not find free placement for '${tex.name}' in atlas")
                        atlas = atlas.withTexture(tex.copy(x = pos.first, y = pos.second))
                        println("  Snapped colors for '${tex.name}' and placed at (${pos.first}, ${pos.second})")
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
                                ?: throw IllegalStateException("Could not find free placement for '${newTex.name}' in quantized atlas")
                        atlas = tempAtlas.withTexture(newTex.copy(x = pos.first, y = pos.second))
                        println(
                            "  Quantized 15-color palette, remapped textures, and placed '${newTex.name}' at (${pos.first}, ${pos.second})",
                        )
                    } else {
                        throw IllegalStateException(
                            "Image '${imgFile.name}' has unmapped colors: ${result.unmappedColors}. Pass --snap-palette or --auto-quantize.",
                        )
                    }
                }
                is ImportResult.Error -> throw IllegalArgumentException("Failed to import '${imgFile.name}': ${result.message}")
            }
        }

        val projectName = projectFile.nameWithoutExtension
        ProjectService.saveProject(projectFile, projectName, atlas, palette, gridSnap)
        println("Import complete. Saved project to '${projectFile.path}'.")
        return 0
    }

    private fun runValidate(args: List<String>): Int {
        if (args.isEmpty()) {
            System.err.println("Usage: atlas validate <project.sfxproj|.sfxatlas>")
            return 1
        }

        val projectFile = File(args[0])
        if (!projectFile.exists()) {
            throw IllegalArgumentException("Project file '${projectFile.path}' not found")
        }

        val (atlas, _, gridSnap) = ProjectService.loadProject(projectFile)
        println("Validating '${projectFile.name}'...")
        println("  Atlas dimensions: ${atlas.width}x${atlas.height}, grid: ${gridSnap}px")

        // 1. Validate textures within bounds
        for (tex in atlas.textures) {
            if (!tex.isWithinBounds(atlas.width, atlas.height)) {
                throw IllegalStateException(
                    "Texture '${tex.name}' exceeds atlas bounds: " +
                        "(${tex.x}, ${tex.y}) + (${tex.width}x${tex.height}) > (${atlas.width}x${atlas.height})",
                )
            }
        }

        // 2. Validate collisions
        for (i in atlas.textures.indices) {
            for (j in i + 1 until atlas.textures.size) {
                if (atlas.textures[i].intersects(atlas.textures[j])) {
                    throw IllegalStateException("Collision detected between '${atlas.textures[i].name}' and '${atlas.textures[j].name}'")
                }
            }
        }

        // 3. Validate descriptors (slices and aliases)
        val descriptors = atlas.resolvedDescriptors()
        println("  Resolved ${descriptors.size} descriptors successfully.")
        println("Validation passed: OK")
        return 0
    }
}

fun main(args: Array<String>) {
    CliMain.main(args)
}
