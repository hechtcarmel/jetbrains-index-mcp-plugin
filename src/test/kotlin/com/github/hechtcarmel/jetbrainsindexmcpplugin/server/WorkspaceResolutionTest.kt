package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpToolDispatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.text
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.testFramework.PsiTestUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

    private lateinit var dispatcher: McpToolDispatcher
    private var originalAvailableProjectsMode: McpSettings.AvailableProjectsMode? = null

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        dispatcher = McpToolDispatcher(ToolRegistry().apply { registerBuiltInTools() })
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
        assertTrue(
            "Light fixture must expose at least one module content root — skipping here would " +
                "silently retire the workspace sub-project resolution this test exists to cover",
            contentRoots.isNotEmpty()
        )

        val contentRoot = contentRoots.first()

        val result = dispatcher.call(
            ToolNames.INDEX_STATUS,
            buildJsonObject { put("project_path", contentRoot) }
        )

        assertFalse(
            "Tool should succeed with module content root path: ${'$'}{result.text}",
            result.isFailure
        )
    }

    /**
     * Tests that a tool call resolves correctly when project_path is a
     * subdirectory of an open project's basePath.
     */
    fun testToolCallWithSubdirectoryOfProject() = runBlocking {
        val projectPath = requireNotNull(project.basePath) {
            "Light fixture must have a base path; without one this test asserts nothing"
        }
        val subPath = "$projectPath/src"

        val result = dispatcher.call(
            ToolNames.INDEX_STATUS,
            buildJsonObject { put("project_path", subPath) }
        )

        assertFalse(
            "Tool should succeed with subdirectory of project: ${result.text}",
            result.isFailure
        )
    }

    /**
     * Tests that a tool call resolves correctly when project_path is a subdirectory of a
     * module content root that lives outside the project basePath. This is the
     * ide_open_workspace layout: the aggregator basePath is a generated directory that
     * shares no prefix with the real module roots, so only content-root prefix matching
     * can resolve sub-paths of a workspace sub-project.
     */
    fun testToolCallWithSubdirectoryOfWorkspaceContentRoot() = runBlocking {
        val contentRoot = addWorkspaceSubProjectContentRoot()
        val basePath = project.basePath
        assertFalse(
            "precondition: the workspace content root must live outside the project basePath — " +
                "otherwise this test passes via the basePath-subdirectory pass instead of the " +
                "content-root prefix pass it exists to cover",
            basePath != null && ProjectResolver.normalizePath(contentRoot.path)
                .startsWith("${ProjectResolver.normalizePath(basePath)}/")
        )
        val subDir = myFixture.tempDirFixture.findOrCreateDir("workspace-subproject/nested")

        val result = dispatcher.call(
            ToolNames.INDEX_STATUS,
            buildJsonObject { put("project_path", subDir.path) }
        )

        assertFalse(
            "Tool should succeed with a subdirectory of a workspace content root: ${result.text}",
            result.isFailure
        )
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
        assertTrue("Light fixture must expose at least one module content root", roots.isNotEmpty())

        val testFile = myFixture.addFileToProject("TestFile.txt", "test content")
        val virtualFile = testFile.virtualFile

        assertTrue(
            "File under content root should be recognized as project file",
            ProjectUtils.isProjectFile(project, virtualFile)
        )
        assertFalse(
            "A file outside every content root must not be reported as a project file, " +
                "otherwise the check above passes for a constant-true implementation",
            ProjectUtils.isProjectFile(project, LightVirtualFile("OutsideAnyContentRoot.txt", "x"))
        )
    }

    private fun requestInvalidPathErrorJson() = runBlocking {
        val result = dispatcher.call(
            ToolNames.INDEX_STATUS,
            buildJsonObject { put("project_path", "/completely/invalid/path") }
        )

        assertTrue("Tool should return error for completely invalid path", result.isFailure)

        return@runBlocking json.parseToJsonElement(result.text).jsonObject
    }

    private fun addWorkspaceSubProjectContentRoot(): VirtualFile {
        val contentRoot = myFixture.tempDirFixture.findOrCreateDir("workspace-subproject")
        PsiTestUtil.addContentRoot(module, contentRoot)
        return contentRoot
    }
}
