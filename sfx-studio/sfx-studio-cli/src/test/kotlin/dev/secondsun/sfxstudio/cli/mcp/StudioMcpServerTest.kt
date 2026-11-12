package dev.secondsun.sfxstudio.cli.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import dev.secondsun.sfxstudio.core.atlas.DescriptorAlias
import dev.secondsun.sfxstudio.core.atlas.ProjectService
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.atlas.SnesPalette
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.TextureAtlas
import dev.secondsun.sfxstudio.core.atlas.VirtualSlice
import dev.secondsun.sfxstudio.core.level.CellRotation
import dev.secondsun.sfxstudio.core.level.LevelDocumentService
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import io.modelcontextprotocol.spec.McpSchema.TextContent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import javax.imageio.ImageIO

class StudioMcpServerTest {
    private val objectMapper = ObjectMapper()
    private var server: StudioMcpServer? = null

    @AfterEach
    fun tearDown() {
        server?.close()
    }

    private fun createSampleProject(
        dir: File,
        baseName: String = "SAMPLE_ATLAS",
    ): File {
        val projFile = File(dir, "$baseName.sfxproj")
        val pixels = ByteArray(16 * 16) { 1 }
        val tex = SourceTexture("box", 16, 16, 0, 0, pixels)
        val slice = VirtualSlice("box_slice", "box", 0, 0, 16, 16)
        val alias = DescriptorAlias("box_alias", "box")

        val atlas =
            TextureAtlas(
                width = 32,
                height = 32,
                textures = listOf(tex),
                slices = listOf(slice),
                aliases = listOf(alias),
            )
        val palette = SnesPalette().withSlot(1, SnesColor(31, 0, 0), assigned = true)

        ProjectService.saveProject(projFile, baseName, atlas, palette, 16)
        return projFile
    }

    @Test
    fun testValidateValidProject(
        @TempDir tempDir: File,
    ) {
        val projFile = createSampleProject(tempDir, "TEST_VALID")
        val s = StudioMcpServer(transport = null, workspaceRoot = tempDir.toPath())
        server = s

        val result =
            s.executeTool(
                "sfx_pack_textures",
                mapOf(
                    "project_path" to projFile.name,
                    "action" to "validate",
                ),
            )

        assertFalse(result.isError)
        val text = (result.content[0] as TextContent).text
        val json = objectMapper.readTree(text)
        assertEquals("SUCCESS", json.get("status").asText())
        assertTrue(json.get("valid").asBoolean())
        assertEquals("validate", json.get("action").asText())
        assertEquals(3, json.get("descriptorCount").asInt())
    }

    @Test
    fun testValidateCollisionReturnsValidFalse(
        @TempDir tempDir: File,
    ) {
        val projFile = File(tempDir, "COLLISION.sfxproj")
        val pixels = ByteArray(16 * 16) { 1 }
        val tex1 = SourceTexture("box1", 16, 16, 0, 0, pixels)
        val tex2 = SourceTexture("box2", 16, 16, 0, 0, pixels)

        val atlas =
            TextureAtlas(
                width = 32,
                height = 32,
                textures = listOf(tex1, tex2),
            )
        val palette = SnesPalette().withSlot(1, SnesColor(31, 0, 0), assigned = true)
        ProjectService.saveProject(projFile, "COLLISION", atlas, palette, 16)

        val s = StudioMcpServer(transport = null, workspaceRoot = tempDir.toPath())
        server = s

        val result =
            s.executeTool(
                "sfx_pack_textures",
                mapOf(
                    "project_path" to projFile.name,
                    "action" to "validate",
                ),
            )

        assertFalse(result.isError)
        val text = (result.content[0] as TextContent).text
        val json = objectMapper.readTree(text)
        assertEquals("FAIL", json.get("status").asText())
        assertFalse(json.get("valid").asBoolean())
        assertTrue(json.get("message").asText().contains("Collision detected"))
    }

    @Test
    fun testValidateOutOfBoundsReturnsValidFalse(
        @TempDir tempDir: File,
    ) {
        val projFile = File(tempDir, "OOB.sfxproj")
        val dto =
            dev.secondsun.sfxstudio.core.atlas.ProjectDto(
                name = "OOB",
                atlasWidth = 32,
                atlasHeight = 32,
                gridSnap = 16,
                palette =
                    listOf(
                        dev.secondsun.sfxstudio.core.atlas
                            .ProjectColorDto(1, 31, 0, 0, true),
                    ),
                textures =
                    listOf(
                        dev.secondsun.sfxstudio.core.atlas
                            .ProjectTextureDto("oob_box", 16, 16, 24, 24, "01".repeat(256)),
                    ),
            )
        projFile.writeText(
            com.google.gson
                .Gson()
                .toJson(dto),
        )

        val s = StudioMcpServer(transport = null, workspaceRoot = tempDir.toPath())
        server = s

        val result =
            s.executeTool(
                "sfx_pack_textures",
                mapOf(
                    "project_path" to projFile.name,
                    "action" to "validate",
                ),
            )

        assertFalse(result.isError)
        val text = (result.content[0] as TextContent).text
        val json = objectMapper.readTree(text)
        assertEquals("FAIL", json.get("status").asText())
        assertFalse(json.get("valid").asBoolean())
        assertTrue(json.get("message").asText().contains("exceeds atlas bounds"))
    }

    @Test
    fun testPackOnTempProjectExportsFiles(
        @TempDir tempDir: File,
    ) {
        val projFile = createSampleProject(tempDir, "TEST_PACK")
        val s = StudioMcpServer(transport = null, workspaceRoot = tempDir.toPath())
        server = s

        val result =
            s.executeTool(
                "sfx_pack_textures",
                mapOf(
                    "project_path" to projFile.name,
                    "action" to "pack",
                ),
            )

        assertFalse(result.isError)
        val text = (result.content[0] as TextContent).text
        val json = objectMapper.readTree(text)
        assertEquals("SUCCESS", json.get("status").asText())
        assertTrue(json.get("valid").asBoolean())
        assertEquals(3, json.get("descriptorCount").asInt())

        assertTrue(File(tempDir, "TEST_PACK.bin").exists())
        assertTrue(File(tempDir, "TEST_PACK.pal").exists())
        assertTrue(File(tempDir, "TEST_PACK.tab").exists())
        assertTrue(File(tempDir, "TEST_PACK.i").exists())
        assertTrue(File(tempDir, "TEST_PACK_atlas.png").exists())
    }

    @Test
    fun testProjectPathAlias(
        @TempDir tempDir: File,
    ) {
        val projFile = createSampleProject(tempDir, "TEST_ALIAS_PROP")
        val s = StudioMcpServer(transport = null, workspaceRoot = tempDir.toPath())
        server = s

        // Uses camelCase projectPath instead of project_path
        val result =
            s.executeTool(
                "sfx_pack_textures",
                mapOf(
                    "projectPath" to projFile.name,
                    "action" to "validate",
                ),
            )

        assertFalse(result.isError)
        val text = (result.content[0] as TextContent).text
        val json = objectMapper.readTree(text)
        assertEquals("SUCCESS", json.get("status").asText())
        assertTrue(json.get("valid").asBoolean())
    }

    @Test
    fun testImportWithSnapPalette(
        @TempDir tempDir: File,
    ) {
        val projFile = createSampleProject(tempDir, "TEST_IMPORT_MCP")

        val imgFile = File(tempDir, "imported.png")
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, 16, 16)
        g.dispose()
        ImageIO.write(img, "PNG", imgFile)

        val s = StudioMcpServer(transport = null, workspaceRoot = tempDir.toPath())
        server = s

        val result =
            s.executeTool(
                "sfx_pack_textures",
                mapOf(
                    "project_path" to projFile.name,
                    "action" to "import",
                    "image_paths" to listOf(imgFile.name),
                    "snap_palette" to true,
                ),
            )

        assertFalse(result.isError)
        val text = (result.content[0] as TextContent).text
        val json = objectMapper.readTree(text)
        assertEquals("SUCCESS", json.get("status").asText())
        assertTrue(json.get("valid").asBoolean())
        assertEquals(2, json.get("textureCount").asInt())
    }

    @Test
    fun testManageLevelCreateSetCellsValidateAndPack(
        @TempDir tempDir: File,
    ) {
        val projFile = createSampleProject(tempDir, "LEVEL_MCP_ATLAS")
        val s = StudioMcpServer(transport = null, workspaceRoot = tempDir.toPath())
        server = s

        // 1. Create 10x8 level
        val createRes =
            s.executeTool(
                "sfx_manage_level",
                mapOf(
                    "action" to "create",
                    "level_path" to "levels/garden.sfxlevel",
                    "atlas_path" to projFile.name,
                    "cols" to 10,
                    "rows" to 8,
                    "default_top_tex" to "box",
                    "default_skirt_tex" to "box_alias",
                ),
            )
        assertFalse(createRes.isError)
        val createJson = objectMapper.readTree((createRes.content[0] as TextContent).text)
        assertEquals("SUCCESS", createJson.get("status").asText())
        assertTrue(createJson.get("valid").asBoolean())
        assertEquals(10, createJson.get("cols").asInt())
        assertEquals(8, createJson.get("rows").asInt())
        assertEquals(8 + 80 * 8, createJson.get("mapByteSize").asInt())
        assertTrue(File(tempDir, "levels/garden.sfxlevel").exists())
        assertTrue(File(tempDir, "levels/garden.map").exists())
        assertTrue(File(tempDir, "levels/garden.i").exists())
        assertTrue(File(tempDir, "levels/garden_preview.png").exists())

        // 2. Patch region + individual cell
        val setRes =
            s.executeTool(
                "sfx_manage_level",
                mapOf(
                    "action" to "set_cells",
                    "level_path" to "levels/garden.sfxlevel",
                    "cells" to
                        listOf(
                            mapOf(
                                "col_start" to 2,
                                "col_end" to 4,
                                "row_start" to 1,
                                "row_end" to 3,
                                "base_height" to 2,
                            ),
                            mapOf(
                                "col" to 1,
                                "row" to 2,
                                "base_height" to 0,
                                "archetype" to "PRIM_STAIRS",
                                "rotation" to 270,
                                "panel_tex" to "box_slice",
                                "panels" to listOf("N", "S"),
                            ),
                        ),
                ),
            )
        assertFalse(setRes.isError)
        val setJson = objectMapper.readTree((setRes.content[0] as TextContent).text)
        assertEquals("SUCCESS", setJson.get("status").asText())
        assertTrue(setJson.get("valid").asBoolean())

        val reloaded = LevelDocumentService.loadLevel(File(tempDir, "levels/garden.sfxlevel"))
        assertEquals(2, reloaded.grid.cellAt(3, 2).baseHeight)
        assertEquals(ShapeArchetype.PRIM_STAIRS, reloaded.grid.cellAt(1, 2).archetype)
        assertEquals(CellRotation.ROT_270, reloaded.grid.cellAt(1, 2).rotation)
        assertTrue(
            reloaded.grid
                .cellAt(1, 2)
                .panels.north,
        )
        assertTrue(
            reloaded.grid
                .cellAt(1, 2)
                .panels.south,
        )

        // 3. Reject unknown texture name cleanly with structured FAIL
        val badRes =
            s.executeTool(
                "sfx_manage_level",
                mapOf(
                    "action" to "set_cells",
                    "level_path" to "levels/garden.sfxlevel",
                    "cells" to
                        listOf(
                            mapOf(
                                "col" to 0,
                                "row" to 0,
                                "top_tex" to "nonexistent_tex",
                            ),
                        ),
                ),
            )
        assertFalse(badRes.isError)
        val badJson = objectMapper.readTree((badRes.content[0] as TextContent).text)
        assertEquals("FAIL", badJson.get("status").asText())
        assertFalse(badJson.get("valid").asBoolean())
        assertTrue(badJson.get("errors").size() >= 1)

        // 4. Validate and pack
        val valRes =
            s.executeTool(
                "sfx_manage_level",
                mapOf(
                    "action" to "validate",
                    "level_path" to "levels/garden.sfxlevel",
                ),
            )
        assertFalse(valRes.isError)
        val valJson = objectMapper.readTree((valRes.content[0] as TextContent).text)
        assertEquals("SUCCESS", valJson.get("status").asText())
        assertTrue(valJson.get("valid").asBoolean())
    }

    @Test
    fun testWorkspaceSecurityBoundaryRejection(
        @TempDir tempDir: File,
    ) {
        val workspaceDir = File(tempDir, "workspace")
        val outsideDir = File(tempDir, "outside")
        workspaceDir.mkdirs()
        outsideDir.mkdirs()

        createSampleProject(outsideDir, "OUTSIDE")

        val s = StudioMcpServer(transport = null, workspaceRoot = workspaceDir.toPath())
        server = s

        val result =
            s.executeTool(
                "sfx_pack_textures",
                mapOf(
                    "project_path" to "../outside/OUTSIDE.sfxproj",
                    "action" to "validate",
                ),
            )

        assertTrue(result.isError)
        val text = (result.content[0] as TextContent).text
        assertTrue(text.contains("Access denied"))

        val levelEscape =
            s.executeTool(
                "sfx_manage_level",
                mapOf(
                    "level_path" to "inside.sfxlevel",
                    "atlas_path" to "../outside/OUTSIDE.sfxproj",
                    "action" to "create",
                ),
            )
        assertTrue(levelEscape.isError)
        assertTrue((levelEscape.content[0] as TextContent).text.contains("Access denied"))
    }

    @Test
    fun testStdioJsonRpcRoundtrip() {
        val launcher = File("/home/summers/Projects/retro-superfx/sfx-studio/scripts/run-mcp.sh")
        assertTrue(launcher.exists() && launcher.canExecute())

        val pb = ProcessBuilder(launcher.absolutePath)
        pb.directory(File("/home/summers/Projects/retro-superfx"))
        val proc = pb.start()

        val writer = PrintWriter(proc.outputStream, true)
        val reader = BufferedReader(InputStreamReader(proc.inputStream))

        try {
            // 1. Initialize request
            writer.println(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0"}}}""",
            )

            val initRespLine = reader.readLine()
            assertNotNull(initRespLine, "Expected initialize response line")
            val initJson = objectMapper.readTree(initRespLine)
            assertEquals("2.0", initJson.get("jsonrpc")?.asText())
            assertEquals(1, initJson.get("id")?.asInt())

            // 2. Initialized notification
            writer.println("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

            // 3. Tools list request
            writer.println("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
            val listRespLine = reader.readLine()
            assertNotNull(listRespLine, "Expected tools/list response line")
            val listJson = objectMapper.readTree(listRespLine)
            val tools = checkNotNull(listJson.get("result")?.get("tools"))
            assertTrue(tools.isArray)
            val hasPackTextures = (0 until tools.size()).any { tools.get(it).get("name")?.asText() == "sfx_pack_textures" }
            val hasManageLevel = (0 until tools.size()).any { tools.get(it).get("name")?.asText() == "sfx_manage_level" }
            assertTrue(hasPackTextures, "Expected sfx_pack_textures in tools/list")
            assertTrue(hasManageLevel, "Expected sfx_manage_level in tools/list")

            // 4. Tools call request: validate TEXTURES.sfxproj
            writer.println(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"sfx_pack_textures","arguments":{"project_path":"snes-sfx-demo/X-GSU/IsoEngine/Data/TEXTURES.sfxproj","action":"validate"}}}""",
            )
            val callRespLine = reader.readLine()
            assertNotNull(callRespLine, "Expected tools/call response line")
            val callJson = objectMapper.readTree(callRespLine)
            val resultNode = callJson.get("result")
            assertNotNull(resultNode)
            assertFalse(resultNode.get("isError")?.asBoolean() ?: false)

            val contentNode =
                resultNode
                    .get("content")
                    ?.get(0)
                    ?.get("text")
                    ?.asText()
            assertNotNull(contentNode)
            val domainPayload = objectMapper.readTree(contentNode)
            assertEquals("SUCCESS", domainPayload.get("status")?.asText())
            assertTrue(domainPayload.get("valid")?.asBoolean() ?: false)
            assertEquals(15, domainPayload.get("descriptorCount")?.asInt())
        } finally {
            proc.destroy()
            proc.waitFor()
        }
    }
}
