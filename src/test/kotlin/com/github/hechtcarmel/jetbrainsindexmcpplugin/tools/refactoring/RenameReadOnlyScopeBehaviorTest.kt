package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * When a rename's usage scope includes read-only files (generated metamodel classes,
 * Flyway migrations, files from other content roots), the tool must return an error
 * listing the blocking files instead of showing a modal dialog that hangs headless
 * MCP sessions.
 */
class RenameReadOnlyScopeBehaviorTest : McpPlatformTestCase() {

    private val readOnlyFiles = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()

    override fun setUp() {
        super.setUp()
        registerSourceRoot("src")
    }

    override fun tearDown() {
        try {
            for (vf in readOnlyFiles) {
                runCatching {
                    File(vf.path).setWritable(true)
                    vf.refresh(false, false)
                }
            }
            readOnlyFiles.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun markReadOnly(relativePath: String): com.intellij.openapi.vfs.VirtualFile {
        val basePath = requireNotNull(project.basePath)
        val vf = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/$relativePath")
        ) { "Cannot find VFS entry for $relativePath" }
        File(vf.path).setWritable(false)
        vf.refresh(false, false)
        readOnlyFiles.add(vf)
        return vf
    }

    fun testRenameFailsWithReadOnlyUsageSiteInsteadOfShowingDialog() = runBlocking {
        writeProjectFile("src/Api.java", """
            public class Api {
                public String greet() { return "hello"; }
            }
        """.trimIndent())

        writeProjectFile("src/Caller.java", """
            public class Caller {
                String s = new Api().greet();
            }
        """.trimIndent())

        markReadOnly("src/Caller.java")

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/Api.java")
            put("line", 2)
            put("column", 19)
            put("newName", "sayHello")
        })

        assertToolFailed("Rename should fail when usage sites are read-only", result)
        val text = toolText(result)
        assertTrue(
            "Error must mention read-only usage files (pre-check), was: $text",
            text.contains("read-only files in rename scope", ignoreCase = true)
        )
        assertTrue(
            "Error must list the specific blocking file, was: $text",
            text.contains("Caller.java")
        )
        assertFileContains("src/Api.java", "public String greet()")
    }
}
