package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CreateFileBehaviorTest : BasePlatformTestCase() {

    fun testCreateNewJavaFile() = runBlocking {
        val result = CreateFileTool().execute(project, buildJsonObject {
            put("file", "src/NewClass.java")
            put("content", "public class NewClass {\n    public void hello() {}\n}")
        })

        assertFalse("Create should succeed: ${(result.content.singleOrNull() as? TextContent)?.text}", result.isFailure)
        val basePath = requireNotNull(project.basePath)
        assertTrue("File should exist on disk", Files.exists(Path.of(basePath, "src/NewClass.java")))
        val content = Files.readString(Path.of(basePath, "src/NewClass.java"))
        assertTrue("Content should match", content.contains("public class NewClass"))
    }

    fun testCreateFileAlreadyExistsFails() = runBlocking {
        val basePath = requireNotNull(project.basePath)
        val path = Path.of(basePath, "src/Existing.java")
        Files.createDirectories(path.parent)
        Files.writeString(path, "public class Existing {}")

        val result = CreateFileTool().execute(project, buildJsonObject {
            put("file", "src/Existing.java")
            put("content", "public class Replaced {}")
        })

        assertTrue("Should fail for existing file", result.isFailure)
        val text = (result.content.singleOrNull() as? TextContent)?.text ?: ""
        assertTrue("Error should mention existing: $text", text.contains("already exists"))
    }

    fun testCreateFileEmptyPathFails() = runBlocking {
        val result = CreateFileTool().execute(project, buildJsonObject {
            put("file", "")
            put("content", "content")
        })

        assertTrue("Should fail for empty path", result.isFailure)
    }

    fun testCreateFileMissingParamsFails() = runBlocking {
        val result = CreateFileTool().execute(project, buildJsonObject {})
        assertTrue("Should fail for missing params", result.isFailure)
    }

    fun testCreateFilePersistsToDisk() = runBlocking {
        val result = CreateFileTool().execute(project, buildJsonObject {
            put("file", "src/Persisted.java")
            put("content", "public class Persisted { int x = 42; }")
        })

        assertFalse("Create should succeed", result.isFailure)
        val basePath = requireNotNull(project.basePath)
        val diskFile = Path.of(basePath, "src/Persisted.java")
        assertTrue("File must exist on disk (not just VFS)", Files.exists(diskFile))
        val diskContent = Files.readString(diskFile)
        assertTrue("Disk content must match", diskContent.contains("int x = 42"))
    }

    fun testCreateFileCreatesDirectories() = runBlocking {
        val result = CreateFileTool().execute(project, buildJsonObject {
            put("file", "src/deep/nested/path/NewFile.java")
            put("content", "package deep.nested.path;\npublic class NewFile {}")
        })

        assertFalse("Create with nested dirs should succeed", result.isFailure)
        val basePath = requireNotNull(project.basePath)
        assertTrue("File should exist", Files.exists(Path.of(basePath, "src/deep/nested/path/NewFile.java")))
    }

    /**
     * The creation must reach the VFS as a *refresh* event. VcsVFSListener ignores refresh
     * events, so a refresh-originated file never hits the "When files are created" VCS
     * confirmation — the app-modal "Add File to Git" dialog (which freezes the EDT and hangs
     * every in-flight MCP call) when set to Ask, or a silent git stage when set to Add
     * silently. A revert to createChildData() delivers a non-refresh event and fails here.
     */
    fun testCreationArrivesAsRefreshEventSoVcsConfirmationIsBypassed() = runBlocking {
        // Cache the parent directory in the VFS first. Against an uncached parent the refresh
        // merely *discovers* the file (no event at all — also VCS-invisible), but in a real IDE
        // session source directories are always cached, and that cached-parent path is exactly
        // the one that used to reach the VCS confirmation. Forcing the cache makes the test pin
        // the production-relevant path deterministically.
        val basePath = requireNotNull(project.basePath)
        val srcDir = Path.of(basePath, "src")
        Files.createDirectories(srcDir)
        val parentVf = requireNotNull(
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(srcDir)
        )
        parentVf.children

        val createEvents = mutableListOf<Pair<String, Boolean>>()
        project.messageBus.connect(testRootDisposable).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    events.filterIsInstance<VFileCreateEvent>()
                        .forEach { createEvents.add(it.path to it.isFromRefresh) }
                }
            }
        )

        val result = CreateFileTool().execute(project, buildJsonObject {
            put("file", "src/VcsInvisible.java")
            put("content", "public class VcsInvisible {}")
        })

        assertFalse("Create should succeed", result.isFailure)
        val ourEvents = createEvents.filter { it.first.endsWith("VcsInvisible.java") }
        assertTrue("Creation must publish a VFS create event", ourEvents.isNotEmpty())
        assertTrue(
            "Create event must be refresh-originated so VcsVFSListener ignores it; got: $ourEvents",
            ourEvents.all { it.second }
        )
    }

    fun testCreateFileWithProjectPathSubdirectoryUsesItAsBase() = runBlocking {
        val basePath = requireNotNull(project.basePath)
        val subDir = java.nio.file.Path.of(basePath, "module-a")
        java.nio.file.Files.createDirectories(subDir.resolve("src"))

        val result = CreateFileTool().execute(project, buildJsonObject {
            put("file", "src/ModuleService.java")
            put("content", "package src;\npublic class ModuleService {}")
            put("project_path", subDir.toString())
        })

        assertFalse("Create with project_path subdirectory should succeed", result.isFailure)
        assertTrue(
            "File should be created under subdirectory",
            java.nio.file.Files.exists(subDir.resolve("src/ModuleService.java"))
        )
        assertFalse(
            "File should NOT be created directly under project basePath",
            java.nio.file.Files.exists(java.nio.file.Path.of(basePath, "src/ModuleService.java"))
        )
    }

    fun testCreateFileWithTraversalInProjectPathIsRejected() = runBlocking {
        val basePath = requireNotNull(project.basePath)

        val result = CreateFileTool().execute(project, buildJsonObject {
            put("file", "evil.java")
            put("content", "public class Evil {}")
            put("project_path", "$basePath/../../../../../../tmp")
        })

        assertTrue("Traversal in project_path should be rejected", result.isFailure)
    }

    fun testCreateFileWithProjectPathOutsideProjectIsRejected() = runBlocking {
        val result = CreateFileTool().execute(project, buildJsonObject {
            put("file", "evil.java")
            put("content", "public class Evil {}")
            put("project_path", "/tmp")
        })

        assertTrue("project_path outside project should be rejected", result.isFailure)
    }
}
