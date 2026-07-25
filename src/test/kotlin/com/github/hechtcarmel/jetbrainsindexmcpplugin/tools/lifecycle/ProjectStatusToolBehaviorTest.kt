package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectModeService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * The light fixture has exactly one open, unmanaged project, so the interesting rows
     * (managed, closed, managed-and-closed) have to be synthesized — otherwise every count is
     * either 1 or 0 and the summary arithmetic is untestable. `managed_closed` in particular is
     * `0 == 0` for any predicate you write when there is only one row.
     */
    private fun withSyntheticManagedProjects(vararg closedPaths: String, body: () -> Unit) {
        val settings = McpSettings.getInstance()
        val service = ProjectModeService.getInstance()
        val previousLifecycle = settings.lifecycleEnabled
        val previousState = service.getAllManagedModes().keys.toSet()
        try {
            settings.lifecycleEnabled = true
            service.loadState(
                ProjectModeService.State(
                    closedProjectPaths = ConcurrentHashMap.newKeySet<String>().apply { addAll(closedPaths) },
                    managedProjectPaths = ConcurrentHashMap.newKeySet<String>().apply { addAll(closedPaths) }
                )
            )
            body()
        } finally {
            service.loadState(ProjectModeService.State())
            settings.lifecycleEnabled = previousLifecycle
            assertTrue(
                "Lifecycle state must be restored so later tests are unaffected",
                service.getAllManagedModes().keys.none { it !in previousState }
            )
        }
    }

    fun testManagedButClosedProjectIsReportedWithItsMode() = runBlocking {
        withSyntheticManagedProjects("/synthetic/closed-project") {
            val result = runBlocking { ProjectStatusTool().execute(project, buildJsonObject { }) }
            assertToolSucceeded("project_status should succeed", result)

            val projects = json.parseToJsonElement(toolText(result)).jsonObject["projects"]!!
                .jsonArray.map { it.jsonObject }
            val closed = projects.singleOrNull { it["path"]?.jsonPrimitive?.content == "/synthetic/closed-project" }
            assertNotNull("A managed-but-closed project must still be reported", closed)
            assertFalse("It is not open", closed!!["open"]!!.jsonPrimitive.boolean)
            assertTrue("It is managed", closed["managed"]!!.jsonPrimitive.boolean)
            assertEquals(
                "A managed project must report its lifecycle mode, lowercased",
                "closed",
                closed["mode"]?.jsonPrimitive?.content
            )
            assertEquals("Name falls back to the last path segment", "closed-project", closed["name"]!!.jsonPrimitive.content)
        }
    }

    fun testSummaryCountsAgreeWithTheProjectRows() = runBlocking {
        withSyntheticManagedProjects("/synthetic/closed-a", "/synthetic/closed-b") {
            val result = runBlocking { ProjectStatusTool().execute(project, buildJsonObject { }) }
            val payload = json.parseToJsonElement(toolText(result)).jsonObject
            val projects = payload["projects"]!!.jsonArray.map { it.jsonObject }
            val summary = payload["summary"]!!.jsonObject

            fun flag(row: kotlinx.serialization.json.JsonObject, key: String) = row[key]!!.jsonPrimitive.boolean

            // Guards the arithmetic below against collapsing to trivially-equal zeroes.
            assertEquals("Fixture should yield the open project plus two synthetic closed ones", 3, projects.size)
            assertEquals("Exactly one row is open", 1, projects.count { flag(it, "open") })
            assertEquals("Exactly two rows are managed", 2, projects.count { flag(it, "managed") })

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
                2,
                summary["managed_closed"]!!.jsonPrimitive.int
            )
        }
    }

    fun testOpenProjectsAreListedBeforeClosedOnes() = runBlocking {
        withSyntheticManagedProjects("/synthetic/aaa-closed-sorts-first-alphabetically") {
            val result = runBlocking { ProjectStatusTool().execute(project, buildJsonObject { }) }

            val openFlags = json.parseToJsonElement(toolText(result)).jsonObject["projects"]!!
                .jsonArray.map { it.jsonObject["open"]!!.jsonPrimitive.boolean }

            // The synthetic path sorts before the fixture project by name, so a name-only sort
            // would put the closed row first. Only the open-first ordering yields [true, false].
            assertEquals(
                "Open projects must sort ahead of closed ones so the useful rows come first",
                listOf(true, false),
                openFlags
            )
        }
    }
}
