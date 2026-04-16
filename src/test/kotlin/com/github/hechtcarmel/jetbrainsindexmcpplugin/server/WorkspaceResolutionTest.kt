package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.testFramework.PsiTestUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Platform-dependent tests for workspace project resolution.
 * Tests that the MCP server correctly resolves projects in workspace scenarios
 * where sub-projects are represented as modules with different content roots.
 */
class WorkspaceResolutionTest : BasePlatformTestCase() {

    private lateinit var handler: JsonRpcHandler
    private lateinit var toolRegistry: ToolRegistry
    private var originalAvailableProjectsMode: McpSettings.AvailableProjectsMode? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun setUp() {
        super.setUp()
        toolRegistry = ToolRegistry()
        toolRegistry.registerBuiltInTools()
        handler = JsonRpcHandler(toolRegistry)
        originalAvailableProjectsMode = McpSettings.getInstance().availableProjectsMode
    }

    override fun tearDown() {
        try {
            originalAvailableProjectsMode?.let { McpSettings.getInstance().availableProjectsMode = it }
        } finally {
            super.tearDown()
        }
    }

    /**
     * Tests that a tool call resolves correctly when project_path matches
     * a module content root (simulating workspace sub-project access).
     */
    fun testToolCallWithModuleContentRootPath() = runBlocking {
        val contentRoots = ProjectUtils.getModuleContentRoots(project)
        if (contentRoots.isEmpty()) return@runBlocking

        val contentRoot = contentRoots.first()

        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = "tools/call",
            params = buildJsonObject {
                put("name", ToolNames.INDEX_STATUS)
                put("arguments", buildJsonObject {
                    put("project_path", contentRoot)
                })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("Module content root path should not return JSON-RPC error", response.error)
        assertNotNull("Should return result", response.result)

        val result = json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
        assertFalse("Tool should succeed with module content root path", result.isError)
    }

    /**
     * Tests that a tool call resolves correctly when project_path is a
     * subdirectory of an open project's basePath.
     */
    fun testToolCallWithSubdirectoryOfProject() = runBlocking {
        val projectPath = project.basePath ?: return@runBlocking
        val subPath = "$projectPath/src"

        val request = JsonRpcRequest(
            id = JsonPrimitive(2),
            method = "tools/call",
            params = buildJsonObject {
                put("name", ToolNames.INDEX_STATUS)
                put("arguments", buildJsonObject {
                    put("project_path", subPath)
                })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("Subdirectory path should not return JSON-RPC error", response.error)
        assertNotNull("Should return result", response.result)

        val result = json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
        assertFalse("Tool should succeed with subdirectory of project", result.isError)
    }

    /**
     * Tests that an invalid path still returns a proper error with available_projects.
     */
    fun testInvalidPathReturnsAvailableProjects() = runBlocking {
        val errorJson = requestInvalidPathErrorJson()

        assertEquals("project_not_found", errorJson["error"]?.jsonPrimitive?.content)
        assertNotNull("Should include available_projects", errorJson["available_projects"])

        val availableProjects = errorJson["available_projects"]!!.jsonArray
        assertTrue("available_projects should not be empty", availableProjects.isNotEmpty())
    }

    fun testCompactAvailableProjectsModeOmitsWorkspaceSubProjects() = runBlocking {
        val extraContentRoot = addWorkspaceSubProjectContentRoot()
        McpSettings.getInstance().availableProjectsMode = McpSettings.AvailableProjectsMode.COMPACT

        val errorJson = requestInvalidPathErrorJson()
        val availableProjects = errorJson["available_projects"]!!.jsonArray
        val availableProjectPaths = availableProjects.mapNotNull { it.jsonObject["path"]?.jsonPrimitive?.content }

        assertTrue("Top-level project root should still be returned", availableProjectPaths.contains(project.basePath))
        assertFalse("Compact mode should omit workspace sub-project entries", availableProjectPaths.contains(extraContentRoot.path))
        assertTrue(
            "Compact mode should omit workspace metadata from project entries",
            availableProjects.none { it.jsonObject.containsKey("workspace") }
        )
    }

    /**
     * Tests that ProjectUtils.getModuleContentRoots returns at least one root
     * for a project with modules.
     */
    fun testGetModuleContentRootsReturnsRoots() {
        val roots = ProjectUtils.getModuleContentRoots(project)
        assertNotNull("Content roots should not be null", roots)
        // In a test fixture, there should be at least one content root
        assertTrue("Should have at least one content root", roots.isNotEmpty())
    }

    /**
     * Tests that ProjectUtils.isProjectFile correctly identifies files
     * under module content roots.
     */
    fun testIsProjectFileWorksWithContentRoots() {
        val roots = ProjectUtils.getModuleContentRoots(project)
        if (roots.isEmpty()) return

        val testFile = myFixture.addFileToProject("TestFile.txt", "test content")
        val virtualFile = testFile.virtualFile

        assertTrue(
            "File under content root should be recognized as project file",
            ProjectUtils.isProjectFile(project, virtualFile)
        )
    }

    private fun requestInvalidPathErrorJson() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(3),
            method = "tools/call",
            params = buildJsonObject {
                put("name", ToolNames.INDEX_STATUS)
                put("arguments", buildJsonObject {
                    put("project_path", "/completely/invalid/path")
                })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("Should not return JSON-RPC level error", response.error)
        assertNotNull("Should return result", response.result)

        val result = json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
        assertTrue("Tool should return error for completely invalid path", result.isError)

        val content = result.content.firstOrNull()
        assertNotNull("Should have error content", content)

        return@runBlocking json.parseToJsonElement(
            (content as? com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock.Text)?.text ?: ""
        ).jsonObject
    }

    private fun addWorkspaceSubProjectContentRoot(): VirtualFile {
        val contentRoot = myFixture.tempDirFixture.findOrCreateDir("workspace-subproject")
        PsiTestUtil.addContentRoot(module, contentRoot)
        return contentRoot
    }
}
