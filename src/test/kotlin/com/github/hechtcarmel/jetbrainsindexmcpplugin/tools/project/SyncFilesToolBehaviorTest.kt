package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SyncFilesResult
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

/**
 * Behavior coverage for `ide_sync_files`.
 *
 * This tool is enabled by default and previously had no execution coverage at all — only schema
 * and registration assertions. Its entire reason to exist is making externally-created files
 * visible to the IDE, so that is what these tests exercise: write a file behind the VFS's back,
 * then assert the tool surfaces it.
 */
class SyncFilesToolBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun decode(text: String): SyncFilesResult = json.decodeFromString(text)

    /** Writes straight to disk, deliberately bypassing the VFS refresh in `writeProjectFile`. */
    private fun writeBehindVfs(relativePath: String, content: String) {
        val basePath = requireNotNull(project.basePath)
        val path = java.nio.file.Path.of(basePath, relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    /**
     * The tool's entire reason to exist: a file created behind the IDE's back becomes visible.
     *
     * Exercised through the whole-project path rather than an explicit `paths` list. Resolving an
     * explicit path goes through `AbstractMcpTool.resolveFile`, whose `refreshAndFindFileByPath`
     * fallback is skipped whenever `isReadAccessAllowed` — always true on the EDT, where platform
     * tests run. In production MCP requests arrive on Ktor worker threads, so that fallback does
     * run; it simply cannot be reached from here.
     */
    fun testSyncMakesAnExternallyCreatedFileVisibleToTheVfs() = runBlocking {
        writeBehindVfs("synced/External.java", "public class External {}\n")
        assertNull(
            "Precondition: the file must be invisible to the VFS before syncing, " +
                "otherwise this test proves nothing",
            LocalFileSystem.getInstance().findFileByPath("${project.basePath}/synced/External.java")
        )

        val result = SyncFilesTool().execute(project, buildJsonObject { })

        assertToolSucceeded("sync_files should succeed", result)
        assertNotNull(
            "After ide_sync_files the externally created file must be resolvable through the VFS",
            LocalFileSystem.getInstance().findFileByPath("${project.basePath}/synced/External.java")
        )
    }

    fun testSyncReportsTheExactPathsItSynchronized() = runBlocking {
        writeProjectFile("synced/A.java", "public class A {}\n")
        writeProjectFile("synced/B.java", "public class B {}\n")

        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray {
                add(JsonPrimitive("synced/A.java"))
                add(JsonPrimitive("synced/B.java"))
            })
        })

        assertToolSucceeded("sync_files should succeed", result)
        val payload = decode(toolText(result))
        assertEquals(listOf("synced/A.java", "synced/B.java"), payload.syncedPaths)
        assertFalse("An explicit path list is not a whole-project sync", payload.syncedAll)
        assertEquals("Synchronized 2 path(s).", payload.message)
    }

    fun testSyncWithoutPathsSynchronizesTheWholeProject() = runBlocking {
        val result = SyncFilesTool().execute(project, buildJsonObject { })

        assertToolSucceeded("sync_files should succeed with no arguments", result)
        val payload = decode(toolText(result))
        assertTrue("Omitting paths must sync the whole project", payload.syncedAll)
        assertEquals(listOf(project.basePath), payload.syncedPaths)
        assertEquals("Synchronized entire project.", payload.message)
    }

    /**
     * A path that cannot be resolved must be reported, not silently dropped — otherwise an agent
     * sees success and concludes the file is now indexed.
     */
    fun testSyncNamesUnresolvablePathsInsteadOfSilentlyIgnoringThem() = runBlocking {
        writeProjectFile("synced/Present.java", "public class Present {}\n")

        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray {
                add(JsonPrimitive("synced/Present.java"))
                add(JsonPrimitive("synced/Missing.java"))
            })
        })

        assertToolSucceeded("Partial resolution is still a success", result)
        val payload = decode(toolText(result))
        assertEquals(listOf("synced/Present.java"), payload.syncedPaths)
        assertEquals(
            "Synchronized 1 of 2 requested path(s). Not found: synced/Missing.java.",
            payload.message
        )
    }

    fun testSyncWithEmptyPathListFallsBackToWholeProject() = runBlocking {
        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray { })
        })

        assertToolSucceeded("An empty path list should not error", result)
        val payload = decode(toolText(result))
        assertTrue("An empty list is treated as 'sync everything'", payload.syncedAll)
    }
}
