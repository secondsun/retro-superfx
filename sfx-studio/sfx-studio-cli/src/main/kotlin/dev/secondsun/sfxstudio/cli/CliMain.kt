package dev.secondsun.sfxstudio.cli

import com.fasterxml.jackson.core.type.TypeReference
import dev.secondsun.sfxstudio.cli.mcp.CellPatchDto
import dev.secondsun.sfxstudio.cli.mcp.StudioMcpServer
import dev.secondsun.sfxstudio.core.atlas.ConflictResolution
import dev.secondsun.sfxstudio.core.atlas.PlacementPolicy
import dev.secondsun.sfxstudio.core.level.CellPatch
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.ops.PathPolicy
import dev.secondsun.sfxstudio.core.ops.StudioOperations
import java.io.File
import java.nio.file.Path
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

/**
 * Thin command-line adapter delegating all SuperFX Studio atlas and level operations to [StudioOperations].
 */
object CliMain {
    private val operations = StudioOperations(PathPolicy.UNRESTRICTED)

    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = execute(args)
        if (exitCode != 0) {
            exitProcess(exitCode)
        }
    }

    /**
     * Executes CLI commands without calling [exitProcess], returning the exit status code.
     * `0` indicates success, `1` indicates invalid syntax or unknown command, `2` indicates runtime/validation error.
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
                        "set-cells", "set_cells" -> runLevelSetCells(subArgs.drop(1))
                        "pack" -> runLevelPack(subArgs.drop(1))
                        "validate" -> runLevelValidate(subArgs.drop(1))
                        "import-ca65", "import_ca65" -> runLevelImportCa65(subArgs.drop(1))
                        "import-fft", "import_fft" -> runLevelImportFft(subArgs.drop(1))
                        else -> {
                            System.err.println("Unknown level subcommand: '${subArgs[0]}'")
                            printUsage()
                            1
                        }
                    }
                }
                "mesh" -> {
                    val subArgs = args.drop(1)
                    if (subArgs.isEmpty() || subArgs[0] in listOf("help", "--help", "-h")) {
                        printUsage()
                        return if (subArgs.isEmpty()) 1 else 0
                    }
                    when (subArgs[0]) {
                        "import-fft", "import_fft" -> runMeshImportFft(subArgs.drop(1))
                        else -> {
                            System.err.println("Unknown mesh subcommand: '${subArgs[0]}'")
                            printUsage()
                            1
                        }
                    }
                }
                "sprite" -> {
                    val subArgs = args.drop(1)
                    if (subArgs.isEmpty() || subArgs[0] in listOf("help", "--help", "-h")) {
                        printUsage()
                        return if (subArgs.isEmpty()) 1 else 0
                    }
                    when (subArgs[0]) {
                        "pack" -> runSpritePack(subArgs.drop(1))
                        else -> {
                            System.err.println("Unknown sprite subcommand: '${subArgs[0]}'")
                            printUsage()
                            1
                        }
                    }
                }
                "pack" -> runPack(args.drop(1))
                "import" -> runImport(args.drop(1))
                "validate" -> runValidate(args.drop(1))
                else -> {
                    System.err.println("Unknown command: '$first'")
                    printUsage()
                    1
                }
            }
        } catch (e: Exception) {
            System.err.println("Error executing '$first': ${e.message}")
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
        Runtime.getRuntime().addShutdownHook(Thread { server.close() })
        server.await()
        return 0
    }

    private fun printUsage() {
        println(
            """
            SuperFX Studio CLI

            Usage:
              atlas pack [<project.sfxproj|.sfxatlas>] [--project <project>] [--output-dir <dir>] [--auto-pack]
                Validates manifest, resolves virtual slices and aliases, optionally auto-packs,
                and exports companion binaries (.bin, .pal, .tab, .i, _atlas.png).

              atlas import [<project.sfxproj|.sfxatlas>] [--project <project>] <images...> [--auto-quantize] [--snap-palette] [--auto-grow]
                Imports one or more image files (PNG/BMP) into the project, mapping or quantizing
                colors to the SNES palette, places them (optionally growing up to 256x256), and saves.

              atlas validate [<project.sfxproj|.sfxatlas>] [--project <project>]
                Validates dimensions, bounds, coordinate collisions, slices, aliases, and <=256 descriptors.

              level create [<level.sfxlevel>] [--project <level>] --atlas <atlas.sfxproj> [--cols <4..16>] [--rows <4..16>] [--top-tex <name>] [--skirt-tex <name>] [--height <0..31>] [--yaw <0..119>]
                Creates a new isometric Level Document linked to an atlas and exports .map, .i, and _preview.png.

              level set-cells [<level.sfxlevel>] [--project <level>] [--cells-json <json|@file>] [--cell <c,r> | --rect <c0,r0,c1,r1>] [--height <0..31>] [--archetype <PRIM_*>] [--rot <0|90|180|270>] [--top-tex <name|NONE>] [--skirt-tex <name|NONE>] [--panel-tex <name|NONE>] [--panels <N,E,S,W|NONE>] [--tall-2h [true|false]] [--resize <CxR>] [--no-pack] [--yaw <0..119>]
                Applies batch JSON and/or flag-driven cell/region edits (and optional grid resize) to a Level Document.

              level pack [<level.sfxlevel>] [--project <level>] [--output-dir <dir>] [--yaw <0..119>]
                Validates a Level Document against its shared atlas and exports .map, .i, and _preview.png.

              level validate [<level.sfxlevel>] [--project <level>]
                Validates grid bounds, symbolic texture names, Bank $71 atlas budget, and geometry rules.

              level import-ca65 <input.i> --atlas <atlas.sfxproj> --output <level.sfxlevel> [--yaw <0..119>]
                Imports a legacy ca65 def_cell map include into a .sfxlevel document and exports companion files.

              level import-fft <iso.bin> --map <0..125> --atlas <atlas.sfxproj> --output <level.sfxlevel> [--yaw <0..119>]
                Extracts a Final Fantasy Tactics stage into a 15-color <=32KB atlas and .sfxlevel document.

              mesh import-fft <iso.bin> --map <0..125> --atlas-prefix <PREFIX> --output <scene.sfxmesh> [--yaw <0..119>] [--scale <mult>]
                Extracts a Final Fantasy Tactics stage into a hybrid flat + hero-textured 3D mesh (.msh, .bin, .tab, .pal).

              sprite pack <sheet.png> [--output-dir <dir>] [--name <base>] [--frame-width <16>] [--frame-height <24>] [--no-quantize] [--alpha-threshold <128>] [--transparent-rgb <RRGGBB>]
                Compiles a PNG sprite sheet into SNES 4bpp OBJ tiles (.chr), BGR555 palette (.pal), 1-bit Sprite Masks (.msk), and ca65 include (.i).

              --mcp [--workspace <path>]
                Starts the Model Context Protocol (MCP) stdio server for SuperFX texture atlas and level tools.

            Legacy Aliases:
              pack ...        -> atlas pack ...
              import ...      -> atlas import ...
              validate ...    -> atlas validate ...
            """.trimIndent(),
        )
    }

    private fun extractTargetAndRest(
        args: List<String>,
        targetFlags: Set<String>,
        valueFlags: Set<String> = emptySet(),
    ): Pair<String?, List<String>> {
        var flaggedTarget: String? = null
        val afterFlagPass = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val eqTarget = targetFlags.firstOrNull { arg.startsWith("$it=") }
            when {
                arg in targetFlags -> {
                    if (i + 1 >= args.size) throw IllegalArgumentException("Missing value after $arg")
                    flaggedTarget = args[++i]
                }
                eqTarget != null -> flaggedTarget = arg.substringAfter("$eqTarget=")
                arg in valueFlags -> {
                    afterFlagPass.add(arg)
                    if (i + 1 < args.size) afterFlagPass.add(args[++i])
                }
                else -> afterFlagPass.add(arg)
            }
            i++
        }
        if (flaggedTarget != null) {
            return flaggedTarget to afterFlagPass
        }

        var positionalTarget: String? = null
        val rest = mutableListOf<String>()
        var j = 0
        while (j < afterFlagPass.size) {
            val arg = afterFlagPass[j]
            when {
                arg in valueFlags -> {
                    rest.add(arg)
                    if (j + 1 < afterFlagPass.size) rest.add(afterFlagPass[++j])
                }
                !arg.startsWith("-") && positionalTarget == null -> positionalTarget = arg
                else -> rest.add(arg)
            }
            j++
        }
        return positionalTarget to rest
    }

    private fun runPack(args: List<String>): Int {
        val (projectPath, rest) =
            extractTargetAndRest(args, setOf("--project"), setOf("--output-dir"))
        if (projectPath == null) {
            System.err.println(
                "Usage: atlas pack [<project.sfxproj|.sfxatlas>] [--project <project>] [--output-dir <dir>] [--auto-pack]",
            )
            return 1
        }

        var outputDir: String? = null
        var autoPack = false

        var i = 0
        while (i < rest.size) {
            when (rest[i]) {
                "--output-dir" -> {
                    i++
                    if (i < rest.size) outputDir = rest[i]
                }
                "--auto-pack" -> autoPack = true
                else -> System.err.println("Warning: ignoring unknown argument '${rest[i]}'")
            }
            i++
        }

        val res = operations.packAtlas(projectPath, outputDir = outputDir, autoPack = autoPack)
        if (!res.valid) {
            throw IllegalStateException(res.message)
        }
        val doc = checkNotNull(res.document)
        println("Successfully packed '${doc.name}':")
        println("  Atlas dimensions: ${doc.atlas.width}x${doc.atlas.height} (${doc.atlas.sizeBytes} bytes)")
        println("  Base textures:    ${doc.atlas.textures.size}")
        println("  Virtual slices:   ${doc.atlas.slices.size}")
        println("  Aliases:          ${doc.atlas.aliases.size}")
        println("  Total descriptors in TEXTURE_TABLE: ${res.descriptorCount}")
        return 0
    }

    private fun runImport(args: List<String>): Int {
        val (projectPath, rest) = extractTargetAndRest(args, setOf("--project"))
        if (projectPath == null || rest.none { !it.startsWith("-") }) {
            System.err.println(
                "Usage: atlas import [<project.sfxproj|.sfxatlas>] [--project <project>] <images...> [--auto-quantize] [--snap-palette] [--auto-grow]",
            )
            return 1
        }

        var autoQuantize = false
        var snapPalette = false
        var autoGrow = false
        val imagePaths = mutableListOf<String>()

        for (arg in rest) {
            when (arg) {
                "--auto-quantize" -> autoQuantize = true
                "--snap-palette" -> snapPalette = true
                "--auto-grow" -> autoGrow = true
                else -> {
                    if (arg.startsWith("-")) {
                        System.err.println("Warning: ignoring unknown flag '$arg'")
                    } else {
                        imagePaths.add(arg)
                    }
                }
            }
        }

        val resolution =
            when {
                snapPalette -> ConflictResolution.SNAP_TO_PALETTE
                autoQuantize -> ConflictResolution.GLOBAL_QUANTIZE
                else -> ConflictResolution.FAIL
            }
        val placement = if (autoGrow) PlacementPolicy.AUTO_GROW_MAX_256 else PlacementPolicy.STRICT_BOUNDS

        val res =
            operations.importAtlasImages(
                projectPath = projectPath,
                imagePaths = imagePaths,
                conflictResolution = resolution,
                placementPolicy = placement,
            )
        if (!res.valid) {
            throw IllegalStateException(res.message)
        }
        println("Import complete: ${res.message}")
        return 0
    }

    private fun runValidate(args: List<String>): Int {
        val (projectPath, rest) = extractTargetAndRest(args, setOf("--project"))
        if (projectPath == null) {
            System.err.println("Usage: atlas validate [<project.sfxproj|.sfxatlas>] [--project <project>]")
            return 1
        }
        for (arg in rest) {
            System.err.println("Warning: ignoring unknown argument '$arg'")
        }

        val res = operations.validateAtlas(projectPath)
        if (!res.valid) {
            for (err in res.validation.errors) {
                System.err.println("  Error [${err.code}]: ${err.message}")
            }
            throw IllegalStateException(res.message)
        }
        println("Validation passed: OK (${res.descriptorCount} descriptors)")
        return 0
    }

    private fun runLevelCreate(args: List<String>): Int {
        val (levelPath, rest) =
            extractTargetAndRest(
                args,
                setOf("--project", "--level"),
                setOf("--atlas", "--cols", "--rows", "--height", "--base-height", "--top-tex", "--skirt-tex", "--yaw"),
            )
        if (levelPath == null) {
            System.err.println(
                "Usage: level create [<level.sfxlevel>] [--project <level>] --atlas <atlas.sfxproj> [--cols <4..16>] [--rows <4..16>] [--top-tex <name>] [--skirt-tex <name>]",
            )
            return 1
        }

        var atlasPath: String? = null
        var cols = 8
        var rows = 8
        var baseHeight = 0
        var topTex: String? = null
        var skirtTex: String? = null
        var yaw = 15

        var i = 0
        while (i < rest.size) {
            when (rest[i]) {
                "--atlas" -> if (++i < rest.size) atlasPath = rest[i]
                "--cols" -> if (++i < rest.size) cols = rest[i].toInt()
                "--rows" -> if (++i < rest.size) rows = rest[i].toInt()
                "--height", "--base-height" -> if (++i < rest.size) baseHeight = rest[i].toInt()
                "--top-tex" -> if (++i < rest.size) topTex = rest[i]
                "--skirt-tex" -> if (++i < rest.size) skirtTex = rest[i]
                "--yaw" -> if (++i < rest.size) yaw = rest[i].toInt()
                else -> System.err.println("Warning: ignoring unknown argument '${rest[i]}'")
            }
            i++
        }

        val reqAtlas =
            atlasPath ?: run {
                System.err.println("Missing required flag: --atlas <atlas.sfxproj>")
                return 1
            }

        val res =
            operations.createLevel(
                levelPath = levelPath,
                atlasPath = reqAtlas,
                cols = cols,
                rows = rows,
                defaultTopTex = topTex,
                defaultSkirtTex = skirtTex,
                baseHeight = baseHeight,
                yaw = yaw,
            )
        for (warn in res.validation.warnings) {
            println("Warning [${warn.code}]: ${warn.message}")
        }
        if (!res.valid) {
            throw IllegalStateException(res.message)
        }
        println(res.message)
        return 0
    }

    private fun runLevelSetCells(args: List<String>): Int {
        val (levelPath, rest) =
            extractTargetAndRest(
                args,
                setOf("--project", "--level"),
                setOf(
                    "--cells-json",
                    "--cell",
                    "--rect",
                    "--height",
                    "--base-height",
                    "--archetype",
                    "--rot",
                    "--rotation",
                    "--top-tex",
                    "--skirt-tex",
                    "--panel-tex",
                    "--panels",
                    "--resize",
                    "--yaw",
                ),
            )
        if (levelPath == null) {
            System.err.println(
                "Usage: level set-cells [<level.sfxlevel>] [--project <level>] [--cells-json <json|@file>] [--cell <c,r> | --rect <c0,r0,c1,r1>] ...",
            )
            return 1
        }

        val patches = mutableListOf<CellPatch>()
        var resizeCols: Int? = null
        var resizeRows: Int? = null
        var autoPack = true
        var yaw = 15

        // Single-patch CLI flag accumulators
        var singleCol: Int? = null
        var singleRow: Int? = null
        var rectC0: Int? = null
        var rectR0: Int? = null
        var rectC1: Int? = null
        var rectR1: Int? = null
        var flagHeight: Int? = null
        var flagArchetype: String? = null
        var flagRot: Int? = null
        var flagTopTex: String? = null
        var hasTopTex = false
        var flagSkirtTex: String? = null
        var hasSkirtTex = false
        var flagPanelTex: String? = null
        var hasPanelTex = false
        var flagPanels: List<String>? = null
        var flagTall2h: Boolean? = null

        var i = 0
        while (i < rest.size) {
            when (val flag = rest[i]) {
                "--cells-json" -> {
                    require(++i < rest.size) { "Missing value after --cells-json" }
                    patches.addAll(parseCellsJsonArg(rest[i]))
                }
                "--cell" -> {
                    require(++i < rest.size) { "Missing <c,r> value after --cell" }
                    val parts = rest[i].split(',').map { it.trim().toInt() }
                    require(parts.size == 2) { "Expected --cell <col,row>, got '${rest[i]}'" }
                    singleCol = parts[0]
                    singleRow = parts[1]
                }
                "--rect" -> {
                    require(++i < rest.size) { "Missing <c0,r0,c1,r1> value after --rect" }
                    val parts = rest[i].split(',').map { it.trim().toInt() }
                    require(parts.size == 4) { "Expected --rect <c0,r0,c1,r1>, got '${rest[i]}'" }
                    rectC0 = parts[0]
                    rectR0 = parts[1]
                    rectC1 = parts[2]
                    rectR1 = parts[3]
                }
                "--height", "--base-height" -> {
                    require(++i < rest.size) { "Missing value after $flag" }
                    flagHeight = rest[i].toInt()
                }
                "--archetype" -> {
                    require(++i < rest.size) { "Missing value after --archetype" }
                    flagArchetype = rest[i]
                }
                "--rot", "--rotation" -> {
                    require(++i < rest.size) { "Missing value after $flag" }
                    flagRot = CellRotation.fromSymbol(rest[i]).degrees
                }
                "--top-tex" -> {
                    require(++i < rest.size) { "Missing value after --top-tex" }
                    flagTopTex = rest[i]
                    hasTopTex = true
                }
                "--skirt-tex" -> {
                    require(++i < rest.size) { "Missing value after --skirt-tex" }
                    flagSkirtTex = rest[i]
                    hasSkirtTex = true
                }
                "--panel-tex" -> {
                    require(++i < rest.size) { "Missing value after --panel-tex" }
                    flagPanelTex = rest[i]
                    hasPanelTex = true
                }
                "--panels" -> {
                    require(++i < rest.size) { "Missing value after --panels" }
                    flagPanels =
                        rest[i]
                            .split(',', '|')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                }
                "--tall-2h" -> {
                    if (i + 1 < rest.size && rest[i + 1].lowercase() in setOf("true", "false")) {
                        flagTall2h = rest[++i].toBooleanStrict()
                    } else {
                        flagTall2h = true
                    }
                }
                "--no-tall-2h" -> flagTall2h = false
                "--resize" -> {
                    require(++i < rest.size) { "Missing <CxR> value after --resize" }
                    val parts = rest[i].lowercase().split('x', ',').map { it.trim().toInt() }
                    require(parts.size == 2) { "Expected --resize <CxR>, got '${rest[i]}'" }
                    resizeCols = parts[0]
                    resizeRows = parts[1]
                }
                "--no-pack" -> autoPack = false
                "--yaw" -> {
                    require(++i < rest.size) { "Missing value after --yaw" }
                    yaw = rest[i].toInt()
                }
                else -> throw IllegalArgumentException("Unknown argument '$flag' for level set-cells")
            }
            i++
        }

        val hasFlagTarget = singleCol != null || rectC0 != null
        val hasFlagAttrs =
            flagHeight != null ||
                flagArchetype != null ||
                flagRot != null ||
                hasTopTex ||
                hasSkirtTex ||
                hasPanelTex ||
                flagPanels != null ||
                flagTall2h != null

        if (hasFlagAttrs && !hasFlagTarget) {
            throw IllegalArgumentException("Cell attribute flags require --cell <c,r> or --rect <c0,r0,c1,r1>")
        }

        if (hasFlagTarget) {
            patches.add(
                CellPatch(
                    col = singleCol,
                    row = singleRow,
                    colStart = rectC0,
                    colEnd = rectC1,
                    rowStart = rectR0,
                    rowEnd = rectR1,
                    baseHeight = flagHeight,
                    archetype = flagArchetype,
                    rotation = flagRot,
                    topTex = flagTopTex,
                    updateTopTex = hasTopTex,
                    skirtTex = flagSkirtTex,
                    updateSkirtTex = hasSkirtTex,
                    panelTex = flagPanelTex,
                    updatePanelTex = hasPanelTex,
                    panels = flagPanels,
                    tall2h = flagTall2h,
                ),
            )
        }

        if (patches.isEmpty() && resizeCols == null && resizeRows == null) {
            System.err.println("Nothing to update: pass --cells-json, --cell / --rect, or --resize <CxR>.")
            return 1
        }

        val res =
            operations.setLevelCells(
                levelPath = levelPath,
                patches = patches,
                resizeCols = resizeCols,
                resizeRows = resizeRows,
                autoPack = autoPack,
                yaw = yaw,
            )
        for (warn in res.validation.warnings) {
            println("Warning [${warn.code}]: ${warn.message}")
        }
        if (!res.valid) {
            for (err in res.validation.errors) {
                System.err.println("Error [${err.code}]: ${err.message}")
            }
            throw IllegalStateException(res.message)
        }
        println(res.message)
        return 0
    }

    private fun parseCellsJsonArg(rawArg: String): List<CellPatch> {
        val jsonText =
            if (rawArg.startsWith("@")) {
                val file = File(rawArg.substring(1))
                require(file.exists()) { "Cells JSON file '${file.path}' does not exist" }
                file.readText()
            } else {
                rawArg
            }
        val mapper = StudioMcpServer.createStrictMapper()
        val trimmed = jsonText.trim()
        val dtos: List<CellPatchDto> =
            if (trimmed.startsWith("[")) {
                mapper.readValue(trimmed, object : TypeReference<List<CellPatchDto>>() {})
            } else {
                listOf(mapper.readValue(trimmed, CellPatchDto::class.java))
            }
        return dtos.map { it.toDomain() }
    }

    private fun runLevelPack(args: List<String>): Int {
        val (levelPath, rest) =
            extractTargetAndRest(args, setOf("--project", "--level"), setOf("--output-dir", "--yaw"))
        if (levelPath == null) {
            System.err.println(
                "Usage: level pack [<level.sfxlevel>] [--project <level>] [--output-dir <dir>] [--yaw <0..119>]",
            )
            return 1
        }

        var outputDir: String? = null
        var yaw = 15

        var i = 0
        while (i < rest.size) {
            when (rest[i]) {
                "--output-dir" -> if (++i < rest.size) outputDir = rest[i]
                "--yaw" -> if (++i < rest.size) yaw = rest[i].toInt()
                else -> System.err.println("Warning: ignoring unknown argument '${rest[i]}'")
            }
            i++
        }

        val res = operations.packLevel(levelPath, outputDir = outputDir, yaw = yaw)
        for (warn in res.validation.warnings) {
            println("Warning [${warn.code}]: ${warn.message}")
        }
        if (!res.valid) {
            throw IllegalStateException(res.message)
        }
        println(res.message)
        return 0
    }

    private fun runLevelValidate(args: List<String>): Int {
        val (levelPath, rest) = extractTargetAndRest(args, setOf("--project", "--level"))
        if (levelPath == null) {
            System.err.println("Usage: level validate [<level.sfxlevel>] [--project <level>]")
            return 1
        }
        for (arg in rest) {
            System.err.println("Warning: ignoring unknown argument '$arg'")
        }

        val res = operations.validateLevel(levelPath)
        for (warn in res.validation.warnings) {
            println("  Warning [${warn.code}]: ${warn.message}")
        }
        if (!res.valid) {
            for (err in res.validation.errors) {
                System.err.println("  Error [${err.code}]: ${err.message}")
            }
            throw IllegalStateException(res.message)
        }
        println("Validation passed: OK (${res.validation.warnings.size} warning(s))")
        return 0
    }

    private fun runLevelImportCa65(args: List<String>): Int {
        if (args.isEmpty()) {
            System.err.println(
                "Usage: level import-ca65 <input.i> --atlas <atlas.sfxproj> --output <level.sfxlevel> [--yaw <0..119>]",
            )
            return 1
        }

        val ca65Path = args[0]
        var atlasPath: String? = null
        var outputPath: String? = null
        var yaw = 15

        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--atlas" -> if (++i < args.size) atlasPath = args[i]
                "--output" -> if (++i < args.size) outputPath = args[i]
                "--yaw" -> if (++i < args.size) yaw = args[i].toInt()
                else -> System.err.println("Warning: ignoring unknown argument '${args[i]}'")
            }
            i++
        }

        val reqAtlas =
            atlasPath ?: run {
                System.err.println("Missing required flag: --atlas <atlas.sfxproj>")
                return 1
            }
        val reqOutput =
            outputPath ?: run {
                System.err.println("Missing required flag: --output <level.sfxlevel>")
                return 1
            }

        val res =
            operations.importCa65Level(
                ca65Path = ca65Path,
                atlasPath = reqAtlas,
                levelPath = reqOutput,
                yaw = yaw,
            )
        for (warn in res.validation.warnings) {
            println("Warning [${warn.code}]: ${warn.message}")
        }
        if (!res.valid) {
            throw IllegalStateException(res.message)
        }
        println(res.message)
        return 0
    }

    private fun runLevelImportFft(args: List<String>): Int {
        val (isoPath, rest) =
            extractTargetAndRest(
                args,
                setOf("--iso"),
                setOf("--map", "--atlas", "--output", "--level", "--yaw"),
            )
        if (isoPath == null) {
            System.err.println(
                "Usage: level import-fft <iso.bin> --map <0..125> --atlas <atlas.sfxproj> --output <level.sfxlevel> [--yaw <0..119>]",
            )
            return 1
        }

        var mapId: Int? = null
        var atlasPath: String? = null
        var outputPath: String? = null
        var yaw = 15

        var i = 0
        while (i < rest.size) {
            when (rest[i]) {
                "--map" -> if (++i < rest.size) mapId = rest[i].toInt()
                "--atlas" -> if (++i < rest.size) atlasPath = rest[i]
                "--output", "--level" -> if (++i < rest.size) outputPath = rest[i]
                "--yaw" -> if (++i < rest.size) yaw = rest[i].toInt()
                else -> System.err.println("Warning: ignoring unknown argument '${rest[i]}'")
            }
            i++
        }

        val reqMap =
            mapId ?: run {
                System.err.println("Missing required flag: --map <0..125>")
                return 1
            }
        val reqAtlas =
            atlasPath ?: run {
                System.err.println("Missing required flag: --atlas <atlas.sfxproj>")
                return 1
            }
        val reqOutput =
            outputPath ?: run {
                System.err.println("Missing required flag: --output <level.sfxlevel>")
                return 1
            }

        val res =
            operations.importFftLevel(
                isoPath = isoPath,
                mapId = reqMap,
                atlasPath = reqAtlas,
                levelPath = reqOutput,
                yaw = yaw,
            )
        for (warn in res.validation.warnings) {
            println("Warning [${warn.code}]: ${warn.message}")
        }
        if (!res.valid) {
            for (err in res.validation.errors) {
                System.err.println("Error [${err.code}]: ${err.message}")
            }
            throw IllegalStateException(res.message)
        }
        println(res.message)
        return 0
    }

    private fun runMeshImportFft(args: List<String>): Int {
        val (isoPath, rest) =
            extractTargetAndRest(
                args,
                setOf("--iso"),
                setOf("--map", "--atlas-prefix", "--output", "--mesh", "--yaw", "--scale"),
            )
        if (isoPath == null) {
            System.err.println(
                "Usage: mesh import-fft <iso.bin> --map <0..125> --atlas-prefix <PREFIX> --output <scene.sfxmesh> [--yaw <0..119>] [--scale <mult>]",
            )
            return 1
        }

        var mapId: Int? = null
        var atlasPrefix: String? = null
        var outputPath: String? = null
        var yaw: Int? = null
        var scale = 1.0

        var i = 0
        while (i < rest.size) {
            when (rest[i]) {
                "--map" -> if (++i < rest.size) mapId = rest[i].toInt()
                "--atlas-prefix" -> if (++i < rest.size) atlasPrefix = rest[i]
                "--output", "--mesh" -> if (++i < rest.size) outputPath = rest[i]
                "--yaw" -> if (++i < rest.size) yaw = rest[i].toInt()
                "--scale" -> if (++i < rest.size) scale = rest[i].toDouble()
                else -> System.err.println("Warning: ignoring unknown argument '${rest[i]}'")
            }
            i++
        }

        val reqMap =
            mapId ?: run {
                System.err.println("Missing required flag: --map <0..125>")
                return 1
            }
        val reqPrefix =
            atlasPrefix ?: run {
                System.err.println("Missing required flag: --atlas-prefix <PREFIX>")
                return 1
            }
        val reqOutput =
            outputPath ?: run {
                System.err.println("Missing required flag: --output <scene.sfxmesh>")
                return 1
            }

        val res =
            operations.importFftMesh(
                isoPath = isoPath,
                mapId = reqMap,
                atlasPrefix = reqPrefix,
                outputMeshPath = reqOutput,
                yaw = yaw,
                scale = scale,
            )
        for (warn in res.validation.warnings) {
            println("Warning: $warn")
        }
        if (!res.valid) {
            for (err in res.validation.errors) {
                System.err.println("Error: $err")
            }
            throw IllegalStateException(res.message)
        }
        println(res.message)
        return 0
    }

    private fun runSpritePack(args: List<String>): Int {
        val (imagePath, rest) =
            extractTargetAndRest(
                args,
                setOf("--input", "--sheet", "--image"),
                setOf("--output-dir", "--name", "--frame-width", "--frame-height", "--alpha-threshold", "--transparent-rgb"),
            )
        if (imagePath == null) {
            System.err.println(
                "Usage: sprite pack <sheet.png> [--output-dir <dir>] [--name <base>] [--frame-width <16>] [--frame-height <24>] [--no-quantize] [--alpha-threshold <128>] [--transparent-rgb <RRGGBB>]",
            )
            return 1
        }

        var outputDir: String? = null
        var name: String? = null
        var frameWidth = 16
        var frameHeight = 24
        var autoQuantize = true
        var alphaThreshold = 128
        var transparentRgb: Int? = null

        var i = 0
        while (i < rest.size) {
            when (rest[i]) {
                "--output-dir" -> if (++i < rest.size) outputDir = rest[i]
                "--name" -> if (++i < rest.size) name = rest[i]
                "--frame-width" -> if (++i < rest.size) frameWidth = rest[i].toInt()
                "--frame-height" -> if (++i < rest.size) frameHeight = rest[i].toInt()
                "--no-quantize" -> autoQuantize = false
                "--alpha-threshold" -> if (++i < rest.size) alphaThreshold = rest[i].toInt()
                "--transparent-rgb" -> {
                    if (++i < rest.size) {
                        val cleaned =
                            rest[i]
                                .removePrefix("#")
                                .removePrefix("0x")
                                .removePrefix("0X")
                                .removePrefix("$")
                        transparentRgb = cleaned.toInt(16) and 0x00FFFFFF
                    }
                }
                else -> System.err.println("Warning: ignoring unknown argument '${rest[i]}'")
            }
            i++
        }

        val res =
            operations.packSpriteSheet(
                imagePath = imagePath,
                outputDir = outputDir,
                name = name,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                autoQuantize = autoQuantize,
                alphaThreshold = alphaThreshold,
                transparentRgb = transparentRgb,
            )
        if (!res.valid) {
            throw IllegalStateException(res.message)
        }
        println(res.message)
        return 0
    }
}

fun main(args: Array<String>) {
    CliMain.main(args)
}
