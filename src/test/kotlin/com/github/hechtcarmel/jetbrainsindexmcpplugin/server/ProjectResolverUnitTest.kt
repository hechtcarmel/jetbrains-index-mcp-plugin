package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import junit.framework.TestCase
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProjectResolverUnitTest : TestCase() {

    fun testNormalizePathRemovesTrailingSlash() {
        assertEquals("/home/user/project", ProjectResolver.normalizePath("/home/user/project/"))
        assertEquals("/home/user/project", ProjectResolver.normalizePath("/home/user/project"))
    }

    fun testNormalizePathConvertsBackslashes() {
        assertEquals("C:/Users/project", ProjectResolver.normalizePath("C:\\Users\\project"))
        assertEquals("C:/Users/project", ProjectResolver.normalizePath("C:\\Users\\project\\"))
    }

    fun testNormalizePathHandlesEmpty() {
        assertEquals("", ProjectResolver.normalizePath(""))
    }

    fun testNormalizePathHandlesMixedSeparators() {
        assertEquals("C:/Users/project/src", ProjectResolver.normalizePath("C:\\Users/project\\src/"))
    }

    fun testBuildAvailableProjectsJsonExpandedIncludesWorkspaceSubProjects() {
        val entries = listOf(
            AvailableProjectEntry(name = "workspace-root", path = "/repo"),
            AvailableProjectEntry(name = "module-a", path = "/repo/module-a", workspace = "workspace-root")
        )

        val result = buildAvailableProjectsJson(entries, includeWorkspaceSubProjects = true)

        assertEquals(2, result.size)
        assertEquals("workspace-root", result[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("/repo", result[0].jsonObject["path"]?.jsonPrimitive?.content)
        assertNull("Top-level project should not have workspace field", result[0].jsonObject["workspace"])
        assertEquals("module-a", result[1].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("/repo/module-a", result[1].jsonObject["path"]?.jsonPrimitive?.content)
        assertEquals("workspace-root", result[1].jsonObject["workspace"]?.jsonPrimitive?.content)
    }

    fun testBuildAvailableProjectsJsonCompactExcludesWorkspaceSubProjects() {
        val entries = listOf(
            AvailableProjectEntry(name = "workspace-root", path = "/repo"),
            AvailableProjectEntry(name = "module-a", path = "/repo/module-a", workspace = "workspace-root"),
            AvailableProjectEntry(name = "module-b", path = "/repo/module-b", workspace = "workspace-root")
        )

        val result = buildAvailableProjectsJson(entries, includeWorkspaceSubProjects = false)

        assertEquals("Compact mode should only include top-level entries", 1, result.size)
        val topLevel = result.first().jsonObject
        assertEquals("workspace-root", topLevel["name"]?.jsonPrimitive?.content)
        assertEquals("/repo", topLevel["path"]?.jsonPrimitive?.content)
        assertNull("Compact entry should not carry workspace metadata", topLevel["workspace"])
    }

    fun testBuildAvailableProjectsJsonHandlesEmptyEntries() {
        val result = buildAvailableProjectsJson(emptyList(), includeWorkspaceSubProjects = true)
        assertEquals(0, result.size)
    }
}
