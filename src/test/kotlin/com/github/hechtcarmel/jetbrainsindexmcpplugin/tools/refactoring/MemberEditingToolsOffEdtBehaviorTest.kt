package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs the member-editing tools from a background thread, the way production MCP calls
 * arrive (Ktor CIO worker threads — `McpToolDispatcher.withIdeModality` only attaches a
 * modality context element, it never dispatches to the EDT).
 *
 * The rest of the suite executes tools via `runBlocking` on the EDT, where
 * `AbstractMcpTool.edtAction` short-circuits to the current thread. That masked a real
 * production crash: `FileDocumentManager.saveAllDocuments()` called bare after
 * `suspendingWriteAction` returned, i.e. on the worker thread, where the platform throws
 * its write-intent threading assertion — after the edit had already been applied in
 * memory. These tests fail with that assertion if the EDT dispatch around the save is
 * removed.
 */
class MemberEditingToolsOffEdtBehaviorTest : McpPlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    /** Reads off disk, so the assertions also prove the tool flushed the document. */
    private fun readProjectFileFromDisk(relativePath: String): String =
        Files.readString(Path.of(requireNotNull(project.basePath), relativePath))

    fun testEditMemberExecutedOffEdtSucceedsAndSavesToDisk() = runBlocking {
        writeProjectFile("src/OffEdtEdit.java", """
            public class OffEdtEdit {
                public int answer() {
                    return 41;
                }
            }
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/OffEdtEdit.java")
            put("class", "OffEdtEdit")
            put("member", "answer")
            put("content", "public int answer() {\n        return 42;\n    }")
        })

        assertToolSucceeded("Edit member must succeed when called from a background thread", result)
        assertTrue(
            "The edited content must be saved to disk",
            readProjectFileFromDisk("src/OffEdtEdit.java").contains("return 42;")
        )
    }

    fun testInsertMemberExecutedOffEdtSucceedsAndSavesToDisk() = runBlocking {
        writeProjectFile("src/OffEdtInsert.java", """
            public class OffEdtInsert {
                public void existing() {}
            }
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/OffEdtInsert.java")
            put("class", "OffEdtInsert")
            put("content", "public void inserted() {}")
        })

        assertToolSucceeded("Insert member must succeed when called from a background thread", result)
        val content = readProjectFileFromDisk("src/OffEdtInsert.java")
        assertTrue("The inserted member must be saved to disk", content.contains("inserted()"))
        assertTrue("The existing member must survive", content.contains("existing()"))
    }

    fun testReplaceMemberExecutedOffEdtSucceedsAndSavesToDisk() = runBlocking {
        writeProjectFile("src/OffEdtReplace.java", """
            public class OffEdtReplace {
                public int add(int a, int b) {
                    return a + b;
                }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/OffEdtReplace.java")
            put("class", "OffEdtReplace")
            put("member", "add")
            put("content", "\n        return a + b + 1;\n    ")
        })

        assertToolSucceeded("Replace member must succeed when called from a background thread", result)
        val content = readProjectFileFromDisk("src/OffEdtReplace.java")
        assertTrue("The replaced body must be saved to disk", content.contains("a + b + 1"))
        assertTrue("The signature must survive", content.contains("public int add(int a, int b)"))
    }
}
