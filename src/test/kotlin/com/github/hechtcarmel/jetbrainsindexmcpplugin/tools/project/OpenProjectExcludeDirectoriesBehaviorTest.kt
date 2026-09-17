package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.suppressWorkspaceModelErrors
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Behavior coverage for `ide_open_project`'s `excludeDirectories` parameter.
 *
 * Uses [suppressWorkspaceModelErrors] because `addExcludeFolder` triggers a Workspace Model
 * event handler that fails with `NoSuchMethodError` due to a Kotlin version mismatch in the
 * test sandbox — the exclusion still works, but the logged error would otherwise fail the test.
 */
class OpenProjectExcludeDirectoriesBehaviorTest : McpPlatformTestCase() {

    fun testExcludesDirectoryAndReportsIt() = suppressWorkspaceModelErrors {
        writeProjectFile("wksp/plans.md", "plan content")

        val result = runBlocking {
            OpenProjectTool().execute(project, buildJsonObject {
                put("path", project.basePath!!)
                put("excludeDirectories", buildJsonArray {
                    add(JsonPrimitive("wksp"))
                })
            })
        }

        assertToolSucceeded("Should succeed with excludeDirectories", result)
        val text = toolText(result)
        assertTrue(
            "Response should report 'wksp' as excluded, was: $text",
            text.contains("Excluded: wksp") || text.contains("Excluded:.*wksp".toRegex())
        )

        val modules = ModuleManager.getInstance(project).modules
        assertTrue("Should have at least one module", modules.isNotEmpty())
        val excludeUrls = modules.flatMap { module ->
            ModuleRootManager.getInstance(module).contentEntries.flatMap { it.excludeFolderUrls }
        }
        assertTrue(
            "wksp should be in the excluded folder URLs: $excludeUrls",
            excludeUrls.any { it.contains("/wksp") }
        )
    }

    fun testAlreadyExcludedDirectoryIsReportedCorrectly() = suppressWorkspaceModelErrors {
        writeProjectFile("wksp/plans.md", "plan content")

        // First call excludes
        runBlocking {
            OpenProjectTool().execute(project, buildJsonObject {
                put("path", project.basePath!!)
                put("excludeDirectories", buildJsonArray {
                    add(JsonPrimitive("wksp"))
                })
            })
        }

        // Second call should report already excluded
        val result = runBlocking {
            OpenProjectTool().execute(project, buildJsonObject {
                put("path", project.basePath!!)
                put("excludeDirectories", buildJsonArray {
                    add(JsonPrimitive("wksp"))
                })
            })
        }

        assertToolSucceeded("Second call should succeed", result)
        val text = toolText(result)
        assertTrue(
            "Response should report 'wksp' as already excluded, was: $text",
            text.contains("Already excluded: wksp") || text.contains("Already excluded:.*wksp".toRegex())
        )
    }

    fun testNotFoundDirectoryIsReported() {
        val result = runBlocking {
            OpenProjectTool().execute(project, buildJsonObject {
                put("path", project.basePath!!)
                put("excludeDirectories", buildJsonArray {
                    add(JsonPrimitive("nonexistent_dir_xyz"))
                })
            })
        }

        assertToolSucceeded("Should succeed even with not-found dirs", result)
        val text = toolText(result)
        assertTrue(
            "Response should report 'nonexistent_dir_xyz' as not found, was: $text",
            text.contains("Not found: nonexistent_dir_xyz") || text.contains("Not found:.*nonexistent_dir_xyz".toRegex())
        )
    }

    fun testBlankEntryIsRejected() {
        val result = runBlocking {
            OpenProjectTool().execute(project, buildJsonObject {
                put("path", project.basePath!!)
                put("excludeDirectories", buildJsonArray {
                    add(JsonPrimitive(""))
                })
            })
        }

        assertToolFailed("Blank exclude entry should be rejected", result)
        assertTrue(
            "Error should mention blank or '..'",
            toolText(result).contains("must not be blank")
        )
    }

    fun testDotDotEntryIsRejected() {
        val result = runBlocking {
            OpenProjectTool().execute(project, buildJsonObject {
                put("path", project.basePath!!)
                put("excludeDirectories", buildJsonArray {
                    add(JsonPrimitive("../escape"))
                })
            })
        }

        assertToolFailed("Path traversal should be rejected", result)
        assertTrue(
            "Error should mention '..'",
            toolText(result).contains("'..'")
        )
    }
}
