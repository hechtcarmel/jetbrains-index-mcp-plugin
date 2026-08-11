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
     * Nothing is enrolled by default, so `managed` must be false and the `mode` key must be
     * absent rather than present-and-null — agents branch on it.
     */
    fun testUnmanagedProjectOmitsModeEntirely() = runBlocking {
        val result = ProjectStatusTool().execute(project, buildJsonObject { })

        val payload = json.parseToJsonElement(toolText(result)).jsonObject
        val self = payload["projects"]!!.jsonArray.map { it.jsonObject }
            .single { it["path"]?.jsonPrimitive?.content == project.basePath }

        assertFalse("Nothing is enrolled by default, so nothing is managed", self["managed"]!!.jsonPrimitive.boolean)
        assertNull("mode must be omitted for unmanaged projects, not serialized as null", self["mode"])
    }

    /**
     * The light fixture has exactly one open, unmanaged project, so the interesting rows
     * (managed, closed, managed-and-closed) have to be synthesized — otherwise every count is
     * either 1 or 0 and the summary arithmetic is untestable. `managed_closed` in particular is
     * `0 == 0` for any predicate you write when there is only one row.
     */
    private fun withSyntheticManagedProjects(
        count: Int,
        closedCount: Int = count,
        lifecycleEnabled: Boolean = true,
        body: (paths: List<String>) -> Unit
    ) {
        val settings = McpSettings.getInstance()
        val service = ProjectModeService.getInstance()
        val previousLifecycle = settings.lifecycleEnabled
        val previousState = service.getAllManagedModes().keys.toSet()
        val tempDirs = (1..count).map { java.nio.file.Files.createTempDirectory("aaa-lifecycle-synth-$it") }
        val paths = tempDirs.map { it.toAbsolutePath().toString() }
        val closedPaths = paths.take(closedCount).toSet()
        try {
            settings.lifecycleEnabled = lifecycleEnabled
            service.loadState(
                ProjectModeService.State(
                    closedProjectPaths = ConcurrentHashMap.newKeySet<String>().apply { addAll(closedPaths) },
                    managedProjectPaths = ConcurrentHashMap.newKeySet<String>().apply { addAll(paths) }
                )
            )
            body(paths)
        } finally {
            service.loadState(ProjectModeService.State())
            settings.lifecycleEnabled = previousLifecycle
            tempDirs.forEach { it.toFile().deleteRecursively() }
            assertTrue(
                "Lifecycle state must be restored so later tests are unaffected",
                service.getAllManagedModes().keys.none { it !in previousState }
            )
        }
    }

    fun testManagedButClosedProjectIsReportedWithItsMode() = runBlocking {
        withSyntheticManagedProjects(1) { paths ->
            val result = runBlocking { ProjectStatusTool().execute(project, buildJsonObject { }) }
            assertToolSucceeded("project_status should succeed", result)

            val projects = json.parseToJsonElement(toolText(result)).jsonObject["projects"]!!
                .jsonArray.map { it.jsonObject }
            val closed = projects.singleOrNull { it["path"]?.jsonPrimitive?.content == paths[0] }
            assertNotNull("A managed-but-closed project must still be reported", closed)
            assertFalse("It is not open", closed!!["open"]!!.jsonPrimitive.boolean)
            assertTrue("It is managed", closed["managed"]!!.jsonPrimitive.boolean)
            assertEquals(
                "A managed project must report its lifecycle mode, lowercased",
                "closed",
                closed["mode"]?.jsonPrimitive?.content
            )
            assertEquals(
                "Name falls back to the last path segment",
                paths[0].substringAfterLast("/"),
                closed["name"]!!.jsonPrimitive.content
            )
        }
    }

    fun testSummaryCountsAgreeWithTheProjectRows() = runBlocking {
        withSyntheticManagedProjects(2) { _ ->
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
            assertTrue(
                "The summary must surface the lifecycle toggle state",
                summary["lifecycle_enabled"]!!.jsonPrimitive.boolean
            )
            assertNull("No note when lifecycle automation is enabled", summary["note"])
        }
    }

    /**
     * `lifecycleEnabled` pauses automation; it never un-enrolls projects — the settings toggle
     * flips only the flag, while releasing enrollments is a separate explicit action. So the
     * registry read must be unconditional: a project enrolled earlier must still report
     * managed=true while the toggle is off, exactly as `ide_get_project_modes` already does.
     * Gating the read behind the toggle made the two tools disagree on the same registry.
     */
    fun testManagedProjectsAreReportedEvenWhenLifecycleAutomationIsDisabled() = runBlocking {
        withSyntheticManagedProjects(
            1,
            closedCount = 0,
            lifecycleEnabled = false
        ) { paths ->
            val result = runBlocking { ProjectStatusTool().execute(project, buildJsonObject { }) }
            assertToolSucceeded("project_status should succeed", result)

            val payload = json.parseToJsonElement(toolText(result)).jsonObject
            val projects = payload["projects"]!!.jsonArray.map { it.jsonObject }
            val enrolled = projects.singleOrNull {
                it["path"]?.jsonPrimitive?.content == paths[0]
            }
            assertNotNull("An enrolled project must be reported even while automation is paused", enrolled)
            assertTrue(
                "Enrollment is persisted state, not gated by the lifecycle toggle",
                enrolled!!["managed"]!!.jsonPrimitive.boolean
            )
            assertEquals(
                "A managed path that was never closed falls back to background mode",
                "background",
                enrolled["mode"]?.jsonPrimitive?.content
            )

            val summary = payload["summary"]!!.jsonObject
            assertEquals("The enrolled project counts as managed", 1, summary["managed"]!!.jsonPrimitive.int)
            assertFalse(
                "lifecycle_enabled must report the toggle as off",
                summary["lifecycle_enabled"]!!.jsonPrimitive.boolean
            )
            assertNotNull(
                "Disabled-but-enrolled state must carry an explanatory note",
                summary["note"]
            )

            // Cross-tool agreement: ide_get_project_modes reads the same registry unconditionally,
            // so both tools must report the same managed set in the same state.
            val modesResult = runBlocking { GetProjectModesTool().execute(project, buildJsonObject { }) }
            assertToolSucceeded("get_project_modes should succeed", modesResult)
            val managedInModes = json.parseToJsonElement(toolText(modesResult)).jsonObject["managed_projects"]!!
                .jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }.toSet()
            val managedInStatus = projects.filter { it["managed"]!!.jsonPrimitive.boolean }
                .map { it["path"]!!.jsonPrimitive.content }.toSet()
            assertEquals(
                "Both lifecycle tools must agree on which projects are managed",
                managedInModes,
                managedInStatus
            )
        }
    }

    fun testOpenProjectsAreListedBeforeClosedOnes() = runBlocking {
        // The synthetic path prefix "aaa-" sorts before the fixture project by name, so a
        // name-only sort would put the closed row first. Only the open-first ordering yields
        // [true, false].
        withSyntheticManagedProjects(1) { _ ->
            val result = runBlocking { ProjectStatusTool().execute(project, buildJsonObject { }) }

            val openFlags = json.parseToJsonElement(toolText(result)).jsonObject["projects"]!!
                .jsonArray.map { it.jsonObject["open"]!!.jsonPrimitive.boolean }

            assertEquals(
                "Open projects must sort ahead of closed ones so the useful rows come first",
                listOf(true, false),
                openFlags
            )
        }
    }
}
