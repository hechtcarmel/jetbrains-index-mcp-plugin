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

    fun testBuildAvailableProjectsJsonIncludesWorkspaceSubProjectsWhenEnabled() {
        val entries = listOf(
            AvailableProjectEntry(name = "workspace-root", path = "/repo"),
            AvailableProjectEntry(name = "module-a", path = "/repo/module-a", workspace = "workspace-root")
        )

        val result = buildAvailableProjectsJson(entries, includeWorkspaceSubProjects = true)

        assertEquals(2, result.size)
        assertNull(result[0].jsonObject["workspace"])
        assertEquals("workspace-root", result[1].jsonObject["workspace"]?.jsonPrimitive?.content)
    }

    fun testBuildAvailableProjectsJsonExcludesWorkspaceSubProjectsWhenDisabled() {
        val entries = listOf(
            AvailableProjectEntry(name = "workspace-root", path = "/repo"),
            AvailableProjectEntry(name = "module-a", path = "/repo/module-a", workspace = "workspace-root")
        )

        val result = buildAvailableProjectsJson(entries, includeWorkspaceSubProjects = false)

        assertEquals(1, result.size)
        val topLevelOnly = result.first().jsonObject
        assertEquals("workspace-root", topLevelOnly["name"]?.jsonPrimitive?.content)
        assertNull(topLevelOnly["workspace"])
    }
}
