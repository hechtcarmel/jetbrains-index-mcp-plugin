package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Issue #430: write tools reported success while the file on disk never changed.
 *
 * The IDE re-reads a file another program modified only on a VFS refresh — when its window is
 * activated, or every ~15 s in the background. An agent that edits a file with its own tools and
 * then edits it again through MCP inside that window hands the tool a stale Document. The tool
 * edited it, and `FileDocumentManager` then declined the save as a memory/disk conflict without
 * telling anyone.
 *
 * Each test puts the file in that state by writing it straight to disk, bypassing the VFS, and
 * reads the result back off disk. Runs off the EDT like production MCP calls, so the tools' EDT
 * hops are real dispatches.
 */
class ExternalDiskChangeWriteBehaviorTest : McpPlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    private var priorSyncExternalChanges = false

    override fun setUp() {
        super.setUp()
        // The opt-in project-wide refresh would hide the stale state these tests need.
        priorSyncExternalChanges = McpSettings.getInstance().syncExternalChanges
        McpSettings.getInstance().syncExternalChanges = false
    }

    override fun tearDown() {
        try {
            McpSettings.getInstance().syncExternalChanges = priorSyncExternalChanges
        } finally {
            super.tearDown()
        }
    }

    // ── The edit lands on disk on top of the other program's change ─────────────────────

    fun testReplaceTextSavesOverUnrefreshedExternalChange() = runBlocking {
        val file = "src/StaleReplace.java"
        writeProjectFile(file, "class StaleReplace { int a = 1; int b = 1; }")
        writeBehindTheIdesBack(file, "class StaleReplace { int a = 2; int b = 1; }")

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", file)
            put("searchText", "int b = 1;")
            put("replaceText", "int b = 3;")
        })

        assertToolSucceeded("Replace must succeed on a file changed behind the IDE's back", result)
        assertEquals("class StaleReplace { int a = 2; int b = 3; }", readFromDisk(file))
    }

    fun testEditMemberSavesOverUnrefreshedExternalChange() = runBlocking {
        val file = "src/StaleEdit.java"
        writeProjectFile(file, "public class StaleEdit {\n    int keep() { return 1; }\n    int work() { return 1; }\n}\n")
        writeBehindTheIdesBack(file, "public class StaleEdit {\n    int keep() { return 5; }\n    int work() { return 1; }\n}\n")

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", file)
            put("class", "StaleEdit")
            put("member", "work")
            put("content", "int work() { return 2; }")
            put("reformat", false)
        })

        assertToolSucceeded("Edit member must succeed on a file changed behind the IDE's back", result)
        assertOnDisk(file, contains = listOf("int keep() { return 5; }", "int work() { return 2; }"), absent = "return 1;")
    }

    fun testInsertMemberSavesOverUnrefreshedExternalChange() = runBlocking {
        val file = "src/StaleInsert.java"
        writeProjectFile(file, "public class StaleInsert {\n    void a() {}\n}\n")
        writeBehindTheIdesBack(file, "public class StaleInsert {\n    void a() {}\n    void external() {}\n}\n")

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", file)
            put("class", "StaleInsert")
            put("position", "last")
            put("content", "void inserted() {}")
            put("reformat", false)
        })

        assertToolSucceeded("Insert member must succeed on a file changed behind the IDE's back", result)
        assertOnDisk(file, contains = listOf("void external() {}", "void inserted() {}"))
    }

    fun testReplaceMemberSavesOverUnrefreshedExternalChange() = runBlocking {
        val file = "src/StaleBody.java"
        writeProjectFile(file, "public class StaleBody {\n    int keep() { return 1; }\n    int work() { return 1; }\n}\n")
        writeBehindTheIdesBack(file, "public class StaleBody {\n    int keep() { return 5; }\n    int work() { return 1; }\n}\n")

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", file)
            put("class", "StaleBody")
            put("member", "work")
            put("content", " return 2; ")
            put("reformat", false)
        })

        assertToolSucceeded("Replace member must succeed on a file changed behind the IDE's back", result)
        assertOnDisk(file, contains = listOf("int keep() { return 5; }", "return 2;"), absent = "return 1;")
    }

    /**
     * A qualified name locates the file only once resolved, against the stale PSI. The external
     * version swaps the methods, so the stale `work` sits where `keep` now is: the tool must resolve
     * again after loading the new version, or it replaces the wrong declaration.
     */
    fun testEditMemberByQualifiedNameResolvesAgainstTheReloadedFile() = runBlocking {
        registerSourceRoot("stale-qualified")
        val file = "stale-qualified/stale/QualifiedStale.java"
        writeProjectFile(
            file,
            "package stale;\n\npublic class QualifiedStale {\n    int keep() { return 1; }\n    int work() { return 1; }\n}\n"
        )
        writeBehindTheIdesBack(
            file,
            "package stale;\n\npublic class QualifiedStale {\n    int work() { return 1; }\n    int keep() { return 5; }\n}\n"
        )

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "stale.QualifiedStale#work()")
            put("content", "int work() { return 2; }")
            put("reformat", false)
        })

        assertToolSucceeded("Edit member by qualified name must succeed on a file changed behind the IDE's back", result)
        assertEquals(
            "package stale;\n\npublic class QualifiedStale {\n    int work() { return 2; }\n    int keep() { return 5; }\n}\n",
            readFromDisk(file)
        )
    }

    // ── A write that cannot land is reported, never reported as success ─────────────────

    /**
     * Unsaved IDE changes plus a newer version on disk is a genuine conflict only the user can
     * settle; the tool must neither overwrite the user's typing nor report success.
     *
     * The tool runs inside one EDT event so the test can settle the conflict the refused save
     * registered before the platform's prompt for it runs: in tests that prompt throws.
     */
    fun testUnsavedIdeChangesConflictingWithDiskAreNotEdited() {
        val file = "src/Conflict.java"
        writeProjectFile(file, "class Conflict { int a = 1; }")
        val document = cachedDocument(file)
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText("class Conflict { int a = 1; int typed = 0; }")
            }
        }
        writeBehindTheIdesBack(file, "class Conflict { int a = 2; }")

        lateinit var result: CallToolResult
        lateinit var documentAfterTool: String
        ApplicationManager.getApplication().invokeAndWait {
            result = runBlocking {
                ReplaceTextInFileTool().execute(project, buildJsonObject {
                    put("file", file)
                    put("searchText", "int a = 1;")
                    put("replaceText", "int a = 3;")
                })
            }
            documentAfterTool = document.text
            FileDocumentManager.getInstance().reloadFromDisk(document)
        }
        // Let the platform process the conflict it recorded before the next test touches the VFS.
        ApplicationManager.getApplication().invokeAndWait {}

        assertToolFailed("A file with conflicting unsaved IDE changes must not be edited", result)
        assertTrue("Error must name the unsaved IDE changes: ${toolText(result)}", toolText(result).contains("unsaved changes"))
        assertEquals("The user's unsaved typing must be left alone", "class Conflict { int a = 1; int typed = 0; }", documentAfterTool)
        assertEquals("The other program's version stays on disk", "class Conflict { int a = 2; }", readFromDisk(file))
    }

    /**
     * The file changes on disk after the tool prepared its edit but before it saves — the race
     * [syncFileForEdit][com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool] cannot
     * close. The platform declines the save; the tool must say so and drop the edit, rather than
     * leave it in memory for a later autosave to write or for a conflict prompt to block on.
     */
    fun testDeclinedSaveIsReportedAndTheEditDiscarded() = runBlocking {
        val file = "src/Race.java"
        writeProjectFile(file, "public class Race {\n    void a() {}\n}\n")
        val virtualFile = projectVirtualFile(file)
        val prep = ReadAction.compute<InsertPreparation, Throwable> {
            val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
            val document = requireNotNull(PsiDocumentManager.getInstance(project).getDocument(psiFile))
            InsertPreparation(psiFile, document, document.text.lastIndexOf('}'), file)
        }
        val external = "public class Race {\n    void a() {}\n    void external() {}\n}\n"
        writeBehindTheIdesBack(file, external)

        val result = InsertMemberTool().applyInsertion(project, prep, "void inserted() {}", reformat = false)

        assertToolFailed("A save the IDE declined must not be reported as success", result)
        assertTrue("Error must say the edit was not saved: ${toolText(result)}", toolText(result).contains("was not saved"))
        assertEquals("The other program's version stays on disk", external, readFromDisk(file))
        assertEquals("The declined edit must not linger in memory", external, textOf(prep.document))
        assertFalse(
            "Nothing may be left for autosave to write later",
            FileDocumentManager.getInstance().isDocumentUnsaved(prep.document)
        )
        // Let the platform process the conflict it recorded; it must find nothing left to prompt about.
        ApplicationManager.getApplication().invokeAndWait {}
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────

    private fun projectPath(relativePath: String): Path = Path.of(requireNotNull(project.basePath), relativePath)

    private fun projectVirtualFile(relativePath: String): VirtualFile =
        requireNotNull(LocalFileSystem.getInstance().findFileByPath(projectPath(relativePath).toString()))

    private fun readFromDisk(relativePath: String): String = Files.readString(projectPath(relativePath))

    private fun textOf(document: Document): String = ReadAction.compute<String, Throwable> { document.text }

    private fun cachedDocument(relativePath: String): Document {
        val virtualFile = projectVirtualFile(relativePath)
        return ReadAction.compute<Document, Throwable> {
            requireNotNull(FileDocumentManager.getInstance().getDocument(virtualFile))
        }
    }

    /**
     * Rewrites [relativePath] the way another program would: straight to disk, with no VFS refresh,
     * and with a modification time the VFS has not recorded. Asserts the IDE still holds its own
     * version afterwards, so each test starts from the stale state rather than passing vacuously.
     */
    private fun writeBehindTheIdesBack(relativePath: String, content: String) {
        val virtualFile = projectVirtualFile(relativePath)
        val document = cachedDocument(relativePath)
        val ideText = textOf(document)
        val path = projectPath(relativePath)
        Files.writeString(path, content)
        Files.setLastModifiedTime(path, FileTime.fromMillis(virtualFile.timeStamp + 10_000))

        assertEquals("Precondition: the IDE must not have seen the external write", ideText, textOf(document))
        assertTrue(
            "Precondition: the VFS must still record the old modification time",
            virtualFile.timeStamp != Files.getLastModifiedTime(path).toMillis()
        )
    }

    private fun assertOnDisk(relativePath: String, contains: List<String>, absent: String? = null) {
        val text = readFromDisk(relativePath)
        contains.forEach { assertTrue("Expected '$it' on disk.\n--- disk ---\n$text", text.contains(it)) }
        if (absent != null) assertFalse("Expected no '$absent' on disk.\n--- disk ---\n$text", text.contains(absent))
    }
}
