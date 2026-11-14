package dev.secondsun.sfxstudio.core.ops

import dev.secondsun.sfxstudio.core.atlas.AtlasDiagnostic
import dev.secondsun.sfxstudio.core.atlas.AtlasDocument
import dev.secondsun.sfxstudio.core.atlas.AtlasExportSpec
import dev.secondsun.sfxstudio.core.atlas.AtlasValidationResult
import dev.secondsun.sfxstudio.core.atlas.ConflictResolution
import dev.secondsun.sfxstudio.core.atlas.ExportedAssets
import dev.secondsun.sfxstudio.core.atlas.PlacementPolicy
import dev.secondsun.sfxstudio.core.level.Ca65MapImporter
import dev.secondsun.sfxstudio.core.level.CellPatch
import dev.secondsun.sfxstudio.core.level.DiagnosticSeverity
import dev.secondsun.sfxstudio.core.level.ExportedLevelAssets
import dev.secondsun.sfxstudio.core.level.LevelDiagnostic
import dev.secondsun.sfxstudio.core.level.LevelDocument
import dev.secondsun.sfxstudio.core.level.LevelDocumentService
import dev.secondsun.sfxstudio.core.level.LevelValidationResult
import dev.secondsun.sfxstudio.core.level.LevelValidator
import dev.secondsun.sfxstudio.core.level.MapCell
import dev.secondsun.sfxstudio.core.level.MapGrid
import dev.secondsun.sfxstudio.core.level.applyPatches
import java.nio.file.Files
import java.nio.file.Path

data class AtlasOperationResult(
    val valid: Boolean,
    val document: AtlasDocument? = null,
    val projectFile: Path,
    val descriptorCount: Int = 0,
    val validation: AtlasValidationResult,
    val exportedAssets: ExportedAssets? = null,
    val exportedFiles: List<String> = emptyList(),
    val message: String,
)

data class LevelOperationResult(
    val valid: Boolean,
    val document: LevelDocument? = null,
    val levelFile: Path,
    val validation: LevelValidationResult,
    val updatedCells: Int = 0,
    val exportedAssets: ExportedLevelAssets? = null,
    val exportedFiles: List<String> = emptyList(),
    val message: String,
)

/**
 * Pure, stdio-quiet orchestration facade shared across `CliMain` (`sfx-studio-cli`) and
 * `StudioMcpServer` (`StudioMCP`).
 *
 * Centralizes [PathPolicy] path resolution, [AtlasDocument] / [LevelDocument] persistence,
 * [LevelValidator] checks, and atomic companion binary exports without writing to `System.out`.
 */
class StudioOperations(
    val pathPolicy: PathPolicy = PathPolicy.UNRESTRICTED,
) {
    fun validateAtlas(projectPath: String): AtlasOperationResult {
        val projPath = pathPolicy.resolve(projectPath)
        val projFile = projPath.toFile()
        if (!projFile.exists()) {
            return atlasErrorResult(
                projPath = projPath,
                code = "PROJECT_FILE_NOT_FOUND",
                message = "Project file '${projFile.path}' does not exist.",
            )
        }

        val doc =
            try {
                AtlasDocument.load(projFile)
            } catch (e: Exception) {
                return atlasErrorResult(
                    projPath = projPath,
                    code = "INVALID_PROJECT_FILE",
                    message = "Failed to load project '${projFile.path}': ${e.message}",
                )
            }

        val validation = doc.validate(warnBank71Overflow = false)
        if (!validation.valid) {
            return AtlasOperationResult(
                valid = false,
                document = doc,
                projectFile = projPath,
                descriptorCount = 0,
                validation = validation,
                message = validation.errors.joinToString("; ") { it.message },
            )
        }

        val count = doc.descriptorTable().getOrThrow().size
        return AtlasOperationResult(
            valid = true,
            document = doc,
            projectFile = projPath,
            descriptorCount = count,
            validation = validation,
            message = "Validation passed: $count descriptors valid.",
        )
    }

    fun packAtlas(
        projectPath: String,
        outputDir: String? = null,
        autoPack: Boolean = false,
    ): AtlasOperationResult {
        val projPath = pathPolicy.resolve(projectPath)
        val outDirPath = outputDir?.let { pathPolicy.resolve(it) }
        val projFile = projPath.toFile()
        if (!projFile.exists()) {
            return atlasErrorResult(
                projPath = projPath,
                code = "PROJECT_FILE_NOT_FOUND",
                message = "Project file '${projFile.path}' does not exist.",
            )
        }

        var doc =
            try {
                AtlasDocument.load(projFile)
            } catch (e: Exception) {
                return atlasErrorResult(
                    projPath = projPath,
                    code = "INVALID_PROJECT_FILE",
                    message = "Failed to load project '${projFile.path}': ${e.message}",
                )
            }

        if (autoPack) {
            doc =
                doc.autoPack().getOrElse { err ->
                    return atlasErrorResult(
                        projPath = projPath,
                        code = "AUTO_PACK_FAILED",
                        message = err.message ?: "Failed to auto-pack textures",
                        document = doc,
                    )
                }
        }

        val validation = doc.validate(warnBank71Overflow = false)
        if (!validation.valid) {
            return AtlasOperationResult(
                valid = false,
                document = doc,
                projectFile = projPath,
                descriptorCount = 0,
                validation = validation,
                message = validation.errors.joinToString("; ") { it.message },
            )
        }

        val baseName = projFile.nameWithoutExtension
        val spec = AtlasExportSpec(baseName)
        val assets =
            if (outDirPath != null && !autoPack) {
                doc.exportOnly(outputDir = outDirPath.toFile(), spec = spec)
            } else {
                doc.saveAndExport(documentFile = projFile, outputDir = outDirPath?.toFile(), spec = spec)
            }

        val count = doc.descriptorTable().getOrThrow().size
        val exportedFiles =
            buildList {
                if (outDirPath == null || autoPack) {
                    add(projPath)
                }
                add(assets.graphicsBinFile.toPath())
                add(assets.paletteBinFile.toPath())
                assets.tableBinFile?.let { add(it.toPath()) }
                add(assets.indexFile.toPath())
                add(assets.pngFile.toPath())
            }.map { pathPolicy.formatForOutput(it) }

        return AtlasOperationResult(
            valid = true,
            document = doc,
            projectFile = projPath,
            descriptorCount = count,
            validation = validation,
            exportedAssets = assets,
            exportedFiles = exportedFiles,
            message = "Successfully packed '$baseName' ($count descriptors).",
        )
    }

    fun importAtlasImages(
        projectPath: String,
        imagePaths: List<String>,
        conflictResolution: ConflictResolution = ConflictResolution.FAIL,
        placementPolicy: PlacementPolicy = PlacementPolicy.STRICT_BOUNDS,
    ): AtlasOperationResult {
        val projPath = pathPolicy.resolve(projectPath)
        val projFile = projPath.toFile()
        if (!projFile.exists()) {
            return atlasErrorResult(
                projPath = projPath,
                code = "PROJECT_FILE_NOT_FOUND",
                message = "Project file '${projFile.path}' does not exist.",
            )
        }
        if (imagePaths.isEmpty()) {
            return atlasErrorResult(
                projPath = projPath,
                code = "NO_IMAGES_SPECIFIED",
                message = "No image paths specified for import.",
            )
        }

        val imageFiles =
            imagePaths.map { raw ->
                val resolved = pathPolicy.resolve(raw).toFile()
                if (!resolved.exists()) {
                    throw IllegalArgumentException("Image file '${resolved.path}' not found")
                }
                resolved
            }

        val initialDoc = AtlasDocument.load(projFile)
        val updatedDoc =
            initialDoc
                .importFiles(
                    files = imageFiles,
                    conflictResolution = conflictResolution,
                    placementPolicy = placementPolicy,
                ).getOrElse { err ->
                    return atlasErrorResult(
                        projPath = projPath,
                        code = "IMPORT_FAILED",
                        message = err.message ?: "Failed to import images",
                        document = initialDoc,
                    )
                }

        val validation = updatedDoc.validate(warnBank71Overflow = false)
        if (!validation.valid) {
            return AtlasOperationResult(
                valid = false,
                document = updatedDoc,
                projectFile = projPath,
                validation = validation,
                message = validation.errors.joinToString("; ") { it.message },
            )
        }

        val baseName = projFile.nameWithoutExtension
        val assets = updatedDoc.saveAndExport(projFile)
        val count = updatedDoc.descriptorTable().getOrThrow().size
        val exportedFiles =
            buildList {
                add(projPath)
                add(assets.graphicsBinFile.toPath())
                add(assets.paletteBinFile.toPath())
                assets.tableBinFile?.let { add(it.toPath()) }
                add(assets.indexFile.toPath())
                add(assets.pngFile.toPath())
            }.map { pathPolicy.formatForOutput(it) }

        return AtlasOperationResult(
            valid = true,
            document = updatedDoc,
            projectFile = projPath,
            descriptorCount = count,
            validation = validation,
            exportedAssets = assets,
            exportedFiles = exportedFiles,
            message = "Successfully imported ${imageFiles.size} images into '$baseName' ($count descriptors).",
        )
    }

    fun createLevel(
        levelPath: String,
        atlasPath: String,
        cols: Int = 8,
        rows: Int = 8,
        defaultTopTex: String? = null,
        defaultSkirtTex: String? = null,
        baseHeight: Int = 0,
        yaw: Int = 15,
    ): LevelOperationResult {
        val levelFilePath = pathPolicy.resolve(levelPath)
        val atlasFilePath = pathPolicy.resolve(atlasPath)
        val atlasFile = atlasFilePath.toFile()
        if (!atlasFile.exists()) {
            return levelErrorResult(
                levelFilePath = levelFilePath,
                code = "ATLAS_FILE_NOT_FOUND",
                message = "Atlas file '${atlasFile.path}' does not exist.",
            )
        }

        val atlasDoc = AtlasDocument.load(atlasFile)
        val firstTex =
            atlasDoc
                .descriptorTable()
                .getOrNull()
                ?.entries
                ?.firstOrNull()
                ?.name
        val topTex = defaultTopTex ?: firstTex
        val skirtTex = defaultSkirtTex ?: topTex
        val relAtlasPath = computeRelativeAtlasPath(levelFilePath, atlasFilePath, atlasPath)

        val levelFile = levelFilePath.toFile()
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

        val validation = LevelValidator.validate(doc, atlasDoc)
        if (!validation.valid) {
            return LevelOperationResult(
                valid = false,
                document = doc,
                levelFile = levelFilePath,
                validation = validation,
                message = "Level creation failed validation (${validation.errors.size} error(s)).",
            )
        }

        val exported =
            LevelDocumentService.saveAndExport(
                levelFile = levelFile,
                document = doc,
                atlasDoc = atlasDoc,
                outputDir = null,
                previewYaw = yaw,
            )
        val exportedFiles = formatLevelAssets(exported)

        return LevelOperationResult(
            valid = true,
            document = doc,
            levelFile = levelFilePath,
            validation = validation,
            exportedAssets = exported,
            exportedFiles = exportedFiles,
            message = "Created level '${doc.name}' (${cols}x$rows).",
        )
    }

    fun setLevelCells(
        levelPath: String,
        patches: List<CellPatch>,
        resizeCols: Int? = null,
        resizeRows: Int? = null,
        autoPack: Boolean = true,
        yaw: Int = 15,
    ): LevelOperationResult {
        val levelFilePath = pathPolicy.resolve(levelPath)
        val levelFile = levelFilePath.toFile()
        if (!levelFile.exists()) {
            return levelErrorResult(
                levelFilePath = levelFilePath,
                code = "LEVEL_FILE_NOT_FOUND",
                message = "Level file '${levelFile.path}' does not exist.",
            )
        }

        val loadedDoc = LevelDocumentService.loadLevel(levelFile)
        val atlasFilePath = pathPolicy.resolveRelativeTo(levelFilePath, loadedDoc.atlasPath)

        var grid = loadedDoc.grid
        if (resizeCols != null || resizeRows != null) {
            val defaultFill = grid.cells.firstOrNull() ?: MapCell()
            grid = grid.resized(resizeCols ?: grid.cols, resizeRows ?: grid.rows, defaultFill)
        }

        val (patchedGrid, updatedCellCount) = grid.applyPatches(patches)
        val updatedDoc = loadedDoc.copy(grid = patchedGrid)

        val atlasFile = atlasFilePath.toFile()
        if (!atlasFile.exists()) {
            return levelErrorResult(
                levelFilePath = levelFilePath,
                code = "ATLAS_FILE_NOT_FOUND",
                message = "Atlas file '${atlasFile.path}' does not exist.",
                document = updatedDoc,
            )
        }

        val atlasDoc = AtlasDocument.load(atlasFile)
        val validation = LevelValidator.validate(updatedDoc, atlasDoc)
        if (!validation.valid) {
            return LevelOperationResult(
                valid = false,
                document = updatedDoc,
                levelFile = levelFilePath,
                validation = validation,
                updatedCells = updatedCellCount,
                message = "Cell update failed validation (${validation.errors.size} error(s)).",
            )
        }

        val (exported, exportedFiles) =
            if (autoPack) {
                val assets =
                    LevelDocumentService.saveAndExport(
                        levelFile = levelFile,
                        document = updatedDoc,
                        atlasDoc = atlasDoc,
                        outputDir = null,
                        previewYaw = yaw,
                    )
                assets to formatLevelAssets(assets)
            } else {
                LevelDocumentService.saveAuthoringJson(levelFile, updatedDoc)
                null to listOf(pathPolicy.formatForOutput(levelFilePath))
            }

        return LevelOperationResult(
            valid = true,
            document = updatedDoc,
            levelFile = levelFilePath,
            validation = validation,
            updatedCells = updatedCellCount,
            exportedAssets = exported,
            exportedFiles = exportedFiles,
            message = "Updated $updatedCellCount cell(s) in '${updatedDoc.name}'.",
        )
    }

    fun validateLevel(levelPath: String): LevelOperationResult {
        val levelFilePath = pathPolicy.resolve(levelPath)
        val levelFile = levelFilePath.toFile()
        if (!levelFile.exists()) {
            return levelErrorResult(
                levelFilePath = levelFilePath,
                code = "LEVEL_FILE_NOT_FOUND",
                message = "Level file '${levelFile.path}' does not exist.",
            )
        }

        val doc =
            try {
                LevelDocumentService.loadLevel(levelFile)
            } catch (e: Exception) {
                return levelErrorResult(
                    levelFilePath = levelFilePath,
                    code = "INVALID_LEVEL_FILE",
                    message = "Failed to parse level '${levelFile.path}': ${e.message}",
                )
            }

        if (doc.atlasPath.isBlank()) {
            return levelErrorResult(
                levelFilePath = levelFilePath,
                code = "MISSING_ATLAS_PATH",
                message = "Level Document '${doc.name}' has an empty atlasPath.",
                document = doc,
            )
        }

        val atlasFilePath = pathPolicy.resolveRelativeTo(levelFilePath, doc.atlasPath)
        if (!Files.exists(atlasFilePath)) {
            return levelErrorResult(
                levelFilePath = levelFilePath,
                code = "ATLAS_FILE_NOT_FOUND",
                message = "Atlas file '${doc.atlasPath}' resolved to '$atlasFilePath' which does not exist.",
                document = doc,
            )
        }

        val atlasDoc =
            try {
                AtlasDocument.load(atlasFilePath.toFile())
            } catch (e: Exception) {
                return levelErrorResult(
                    levelFilePath = levelFilePath,
                    code = "INVALID_ATLAS_FILE",
                    message = "Failed to load atlas '$atlasFilePath': ${e.message}",
                    document = doc,
                )
            }

        val validation = LevelValidator.validate(doc, atlasDoc)
        val message =
            if (validation.valid) {
                "Validation passed (${validation.warnings.size} warning(s))."
            } else {
                "Validation failed with ${validation.errors.size} error(s)."
            }
        return LevelOperationResult(
            valid = validation.valid,
            document = doc,
            levelFile = levelFilePath,
            validation = validation,
            message = message,
        )
    }

    /**
     * Validates a Level Document against its shared atlas and exports companion runtime files
     * (`.map`, `.i`, `_preview.png`) into `outputDir ?: levelFile.parent` without mutating or
     * rewriting the source `.sfxlevel` file.
     */
    fun packLevel(
        levelPath: String,
        outputDir: String? = null,
        yaw: Int = 15,
    ): LevelOperationResult {
        val levelFilePath = pathPolicy.resolve(levelPath)
        val outDirPath = outputDir?.let { pathPolicy.resolve(it) }
        val levelFile = levelFilePath.toFile()
        if (!levelFile.exists()) {
            return levelErrorResult(
                levelFilePath = levelFilePath,
                code = "LEVEL_FILE_NOT_FOUND",
                message = "Level file '${levelFile.path}' does not exist.",
            )
        }

        val doc = LevelDocumentService.loadLevel(levelFile)
        val atlasFilePath = pathPolicy.resolveRelativeTo(levelFilePath, doc.atlasPath)
        val atlasFile = atlasFilePath.toFile()
        if (!atlasFile.exists()) {
            return levelErrorResult(
                levelFilePath = levelFilePath,
                code = "ATLAS_FILE_NOT_FOUND",
                message = "Atlas file '${atlasFile.path}' does not exist.",
                document = doc,
            )
        }

        val atlasDoc = AtlasDocument.load(atlasFile)
        val validation = LevelValidator.validate(doc, atlasDoc)
        if (!validation.valid) {
            return LevelOperationResult(
                valid = false,
                document = doc,
                levelFile = levelFilePath,
                validation = validation,
                message = "Level pack failed validation (${validation.errors.size} error(s)).",
            )
        }

        val targetDir = (outDirPath ?: levelFilePath.parent ?: levelFilePath).toFile()
        val exported =
            LevelDocumentService.exportCompanionAssets(
                document = doc,
                atlasDoc = atlasDoc,
                outputDir = targetDir,
                baseName = levelFile.nameWithoutExtension,
                previewYaw = yaw,
                sourceLevelFile = null,
            )

        return LevelOperationResult(
            valid = true,
            document = doc,
            levelFile = levelFilePath,
            validation = validation,
            exportedAssets = exported,
            exportedFiles = formatLevelAssets(exported),
            message = "Successfully packed level '${doc.name}' (${doc.grid.cols}x${doc.grid.rows}).",
        )
    }

    fun importCa65Level(
        ca65Path: String,
        atlasPath: String,
        levelPath: String,
        yaw: Int = 15,
    ): LevelOperationResult {
        val ca65FilePath = pathPolicy.resolve(ca65Path)
        val atlasFilePath = pathPolicy.resolve(atlasPath)
        val levelFilePath = pathPolicy.resolve(levelPath)

        val ca65File = ca65FilePath.toFile()
        if (!ca65File.exists()) {
            return levelErrorResult(
                levelFilePath = levelFilePath,
                code = "CA65_FILE_NOT_FOUND",
                message = "CA65 map file '${ca65File.path}' does not exist.",
            )
        }
        val atlasFile = atlasFilePath.toFile()
        if (!atlasFile.exists()) {
            return levelErrorResult(
                levelFilePath = levelFilePath,
                code = "ATLAS_FILE_NOT_FOUND",
                message = "Atlas file '${atlasFile.path}' does not exist.",
            )
        }

        val atlasDoc = AtlasDocument.load(atlasFile)
        val relAtlasPath = computeRelativeAtlasPath(levelFilePath, atlasFilePath, atlasPath)
        val levelFile = levelFilePath.toFile()
        val doc =
            Ca65MapImporter.importFile(
                ca65File = ca65File,
                atlasPath = relAtlasPath,
                atlas = atlasDoc.atlas,
                levelName = levelFile.nameWithoutExtension,
            )

        val validation = LevelValidator.validate(doc, atlasDoc)
        if (!validation.valid) {
            return LevelOperationResult(
                valid = false,
                document = doc,
                levelFile = levelFilePath,
                validation = validation,
                message = "Imported ca65 map failed validation (${validation.errors.size} error(s)).",
            )
        }

        val exported =
            LevelDocumentService.saveAndExport(
                levelFile = levelFile,
                document = doc,
                atlasDoc = atlasDoc,
                outputDir = null,
                previewYaw = yaw,
            )
        return LevelOperationResult(
            valid = true,
            document = doc,
            levelFile = levelFilePath,
            validation = validation,
            exportedAssets = exported,
            exportedFiles = formatLevelAssets(exported),
            message = "Imported '${ca65File.name}' into '${doc.name}' (${doc.grid.cols}x${doc.grid.rows}).",
        )
    }

    private fun computeRelativeAtlasPath(
        levelFilePath: Path,
        atlasFilePath: Path,
        fallbackRaw: String,
    ): String {
        val levelDir = levelFilePath.toAbsolutePath().normalize().parent ?: return fallbackRaw
        val targetAtlas = atlasFilePath.toAbsolutePath().normalize()
        return try {
            levelDir.relativize(targetAtlas).toString().replace('\\', '/')
        } catch (_: IllegalArgumentException) {
            fallbackRaw
        }
    }

    private fun formatLevelAssets(assets: ExportedLevelAssets): List<String> =
        buildList {
            assets.levelFile?.let { add(it.toPath()) }
            add(assets.mapBinFile.toPath())
            add(assets.headerFile.toPath())
            assets.previewPngFile?.let { add(it.toPath()) }
        }.map { pathPolicy.formatForOutput(it) }

    private fun atlasErrorResult(
        projPath: Path,
        code: String,
        message: String,
        document: AtlasDocument? = null,
    ): AtlasOperationResult {
        val diag = AtlasDiagnostic(DiagnosticSeverity.ERROR, code, message)
        return AtlasOperationResult(
            valid = false,
            document = document,
            projectFile = projPath,
            validation = AtlasValidationResult(valid = false, errors = listOf(diag), warnings = emptyList()),
            message = message,
        )
    }

    private fun levelErrorResult(
        levelFilePath: Path,
        code: String,
        message: String,
        document: LevelDocument? = null,
    ): LevelOperationResult {
        val diag = LevelDiagnostic(DiagnosticSeverity.ERROR, code, message)
        return LevelOperationResult(
            valid = false,
            document = document,
            levelFile = levelFilePath,
            validation = LevelValidationResult(valid = false, errors = listOf(diag), warnings = emptyList()),
            message = message,
        )
    }
}
