package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Behavior coverage for `ide_project_status`.
 *
 * This tool is enabled by default and is how an agent discovers which projects exist and what
 * their paths are — yet before this file, `ProjectStatusTool` had zero references anywhere in
 * `src/test`. A refactor could have deleted it outright and every test would still have passed.
 */
class ProjectStatusToolBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    fun testReportsTheOpenTestProjectWithItsRealPath() = runBlocking {
        val result = ProjectStatusTool().execute(project, buildJsonObject { })

        assertToolSucceeded("project_status should succeed", result)
        val payload = json.parseToJsonElement(toolText(result)).jsonObject
        val projects = payload["projects"]!!.jsonArray.map { it.jsonObject }

        val self = projects.singleOrNull { it["path"]?.jsonPrimitive?.content == project.basePath }
        assertNotNull(
            "The project under test must appear exactly once, keyed by its base path. Got: " +
                projects.map { it["path"]?.jsonPrimitive?.content },
            self
        )
        assertTrue("The project under test is open", self!!["open"]!!.jsonPrimitive.boolean)
        assertEquals(project.name, self["name"]!!.jsonPrimitive.content)
    }

    /**
     * With lifecycle management disabled (the default) nothing is enrolled, so `managed` must be
     * false and the `mode` key must be absent rather than present-and-null — agents branch on it.
     */
    fun testUnmanagedProjectOmitsModeEntirely() = runBlocking {
        val result = ProjectStatusTool().execute(project, buildJsonObject { })

        val payload = json.parseToJsonElement(toolText(result)).jsonObject
        val self = payload["projects"]!!.jsonArray.map { it.jsonObject }
            .single { it["path"]?.jsonPrimitive?.content == project.basePath }

        assertFalse("Lifecycle is off by default, so nothing is managed", self["managed"]!!.jsonPrimitive.boolean)
        assertNull("mode must be omitted for unmanaged projects, not serialized as null", self["mode"])
    }

    fun testSummaryCountsAgreeWithTheProjectRows() = runBlocking {
        val result = ProjectStatusTool().execute(project, buildJsonObject { })

        val payload = json.parseToJsonElement(toolText(result)).jsonObject
        val projects = payload["projects"]!!.jsonArray.map { it.jsonObject }
        val summary = payload["summary"]!!.jsonObject

        fun flag(row: kotlinx.serialization.json.JsonObject, key: String) = row[key]!!.jsonPrimitive.boolean

        assertEquals("total must equal the row count", projects.size, summary["total"]!!.jsonPrimitive.int)
        assertEquals(
            "open must equal the number of rows with open=true",
            projects.count { flag(it, "open") },
            summary["open"]!!.jsonPrimitive.int
        )
        assertEquals(
            "managed must equal the number of rows with managed=true",
            projects.count { flag(it, "managed") },
            summary["managed"]!!.jsonPrimitive.int
        )
        assertEquals(
            "open_not_managed must equal open rows that are unmanaged",
            projects.count { flag(it, "open") && !flag(it, "managed") },
            summary["open_not_managed"]!!.jsonPrimitive.int
        )
        assertEquals(
            "managed_closed must equal managed rows that are not open",
            projects.count { flag(it, "managed") && !flag(it, "open") },
            summary["managed_closed"]!!.jsonPrimitive.int
        )
    }

    fun testOpenProjectsAreListedBeforeClosedOnes() = runBlocking {
        val result = ProjectStatusTool().execute(project, buildJsonObject { })

        val projects = json.parseToJsonElement(toolText(result)).jsonObject["projects"]!!
            .jsonArray.map { it.jsonObject["open"]!!.jsonPrimitive.boolean }

        assertEquals(
            "Open projects must sort ahead of closed ones so the useful rows come first",
            projects.sortedByDescending { it },
            projects
        )
    }
}
