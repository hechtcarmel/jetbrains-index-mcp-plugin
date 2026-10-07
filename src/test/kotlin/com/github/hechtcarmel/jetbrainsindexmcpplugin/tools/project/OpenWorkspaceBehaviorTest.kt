package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Issue #436: agents called ide_open_workspace once per repository, or once per subset of the
 * same repositories, and every call opened another window and left another directory under
 * `ide-workspaces/`. These tests cover the two guards that stop that without opening a window:
 * a single Maven project is redirected to ide_open_project, and projects already modules of an
 * open project reuse it.
 *
 * Every call passes a short `timeoutSeconds`, so a regression that reaches the real open path
 * fails quickly instead of waiting out the 600-second default.
 */
class OpenWorkspaceBehaviorTest : McpPlatformTestCase() {

    private val addedContentRoots = mutableListOf<VirtualFile>()

    override fun tearDown() {
        try {
            addedContentRoots.forEach { root ->
                WriteAction.run<Exception> { PsiTestUtil.removeContentEntry(module, root) }
            }
            addedContentRoots.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testSingleModuleIsRejectedAndRedirectedToOpenProject() {
        val solo = mavenProjectDir("solo")

        val (result, newWorkspaces) = callTracking(buildJsonObject {
            put("modules", buildJsonArray { add(JsonPrimitive(solo.absolutePath)) })
            put("timeoutSeconds", 5)
        })

        assertToolFailed("A one-project workspace must be refused", result)
        val text = toolText(result)
        assertTrue("Should name the project, was: $text", text.contains(solo.absolutePath))
        assertTrue(
            "Should hand the agent a ready ide_open_project call, was: $text",
            text.contains("ide_open_project") && text.contains("\"autoLink\":true")
        )
        assertEquals("No workspace directory may be created for a refused call", emptySet<String>(), newWorkspaces)
    }

    fun testPathScanFindingOneMavenProjectIsRejected() {
        val only = mavenProjectDir("scan-root/only")
        val scanRoot = only.parentFile

        val (result, newWorkspaces) = callTracking(buildJsonObject {
            put("path", scanRoot.absolutePath)
            put("timeoutSeconds", 5)
        })

        assertToolFailed("A directory scan that finds one project must be refused", result)
        val text = toolText(result)
        assertTrue(
            "Should say the scan found only one project, was: $text",
            text.contains("Only one Maven project was found under") && text.contains(only.canonicalPath)
        )
        assertTrue("Should point at ide_open_project, was: $text", text.contains("ide_open_project"))
        assertEquals("No workspace directory may be created for a refused call", emptySet<String>(), newWorkspaces)
    }

    fun testModulesAlreadyInAnOpenProjectReuseIt() {
        val repoA = mavenProjectDir("repo-a")
        val repoB = mavenProjectDir("repo-b")
        addContentRoot(repoA)
        addContentRoot(repoB)

        val (result, newWorkspaces) = callTracking(buildJsonObject {
            put("modules", buildJsonArray {
                add(JsonPrimitive(repoB.absolutePath))
                add(JsonPrimitive(repoA.absolutePath))
            })
            put("timeoutSeconds", 10)
        })

        assertToolSucceeded("Projects already open as modules should reuse that window", result)
        val text = toolText(result)
        assertTrue(
            "Should report reusing the open project '${project.name}', was: $text",
            text.contains("Reused the open project '${project.name}'")
        )
        assertTrue(
            "Should list both repositories and how to target them, was: $text",
            text.contains(repoA.absolutePath) && text.contains(repoB.absolutePath) && text.contains("project_path")
        )
        assertEquals("Reuse must not create a workspace directory", emptySet<String>(), newWorkspaces)
    }

    fun testSingleModuleAlreadyInAnOpenProjectIsReusedRatherThanRejected() {
        val repoA = mavenProjectDir("repo-a")
        addContentRoot(repoA)

        val (result, newWorkspaces) = callTracking(buildJsonObject {
            put("modules", buildJsonArray { add(JsonPrimitive(repoA.absolutePath)) })
            put("timeoutSeconds", 10)
        })

        assertToolSucceeded(
            "A project that is already open needs no new window, and no redirect to ide_open_project " +
                "either: that would open it a second time",
            result
        )
        assertTrue(
            "Should report reusing the open project, was: ${toolText(result)}",
            toolText(result).contains("Reused the open project '${project.name}'")
        )
        assertEquals("Reuse must not create a workspace directory", emptySet<String>(), newWorkspaces)
    }

    fun testAnOpenProjectCoversARequestOnlyWhenItContainsEveryRequestedProject() {
        val repoA = mavenProjectDir("repo-a")
        val repoC = mavenProjectDir("repo-c")
        addContentRoot(repoA)
        val a = ProjectUtils.canonicalNormalizedPath(repoA.absolutePath)
        val c = ProjectUtils.canonicalNormalizedPath(repoC.absolutePath)

        assertSame(project, ProjectUtils.findOpenProjectContainingRoots(setOf(a)))
        assertNull(
            "repo-c is not a module of any open project, so reusing this one would silently drop it",
            ProjectUtils.findOpenProjectContainingRoots(setOf(a, c))
        )
    }

    private fun mavenProjectDir(relativePath: String): File {
        val pom = writeProjectFile(
            "$relativePath/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>test</groupId>
              <artifactId>${relativePath.substringAfterLast('/')}</artifactId>
              <version>1.0</version>
            </project>
            """.trimIndent()
        )
        return pom.parent.toFile()
    }

    private fun addContentRoot(dir: File) {
        val root = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(dir.absolutePath)) {
            "Failed to refresh VFS for $dir"
        }
        PsiTestUtil.addContentRoot(module, root)
        addedContentRoots += root
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** Runs the tool and returns its result plus any workspace directories the call created. */
    private fun callTracking(arguments: JsonObject): Pair<CallToolResult, Set<String>> {
        val before = workspaceDirectories()
        val result = runBlocking { OpenWorkspaceTool().execute(project, arguments) }
        return result to (workspaceDirectories() - before)
    }

    private fun workspaceDirectories(): Set<String> =
        File(PathManager.getSystemPath(), "ide-workspaces").list()?.toSet() ?: emptySet()
}
