package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
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

        assertFalse("Create should succeed: ${(result.content.singleOrNull() as? ContentBlock.Text)?.text}", result.isError)
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

        assertTrue("Should fail for existing file", result.isError)
        val text = (result.content.singleOrNull() as? ContentBlock.Text)?.text ?: ""
        assertTrue("Error should mention existing: $text", text.contains("already exists"))
    }

    fun testCreateFileEmptyPathFails() = runBlocking {
        val result = CreateFileTool().execute(project, buildJsonObject {
            put("file", "")
            put("content", "content")
        })

        assertTrue("Should fail for empty path", result.isError)
    }

    fun testCreateFileMissingParamsFails() = runBlocking {
        val result = CreateFileTool().execute(project, buildJsonObject {})
        assertTrue("Should fail for missing params", result.isError)
    }

    fun testCreateFilePersistsToDisk() = runBlocking {
        val result = CreateFileTool().execute(project, buildJsonObject {
            put("file", "src/Persisted.java")
            put("content", "public class Persisted { int x = 42; }")
        })

        assertFalse("Create should succeed", result.isError)
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

        assertFalse("Create with nested dirs should succeed", result.isError)
        val basePath = requireNotNull(project.basePath)
        assertTrue("File should exist", Files.exists(Path.of(basePath, "src/deep/nested/path/NewFile.java")))
    }
}
