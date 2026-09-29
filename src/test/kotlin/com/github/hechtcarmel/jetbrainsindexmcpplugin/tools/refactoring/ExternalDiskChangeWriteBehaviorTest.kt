package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
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
import kotlinx.serialization.json.buildJsonArray
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
 * Multi-file refactorings hit the same veto in every stale file they update, and their usage
 * search also misses references that exist only in the newer disk version.
 *
 * Each test puts a file in that state by writing it straight to disk, bypassing the VFS, and
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
        LanguageHandlerRegistry.registerHandlers()
        try {
            editMemberByQualifiedNameAfterExternalSwap()
        } finally {
            LanguageHandlerRegistry.clear()
        }
    }

    private suspend fun editMemberByQualifiedNameAfterExternalSwap() {
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
     *
     * The body sits in `runBlocking` like the other tests: a lambda directly in a JUnit 3 test
     * method compiles to a `test…$lambda$N` method, which the runner then reports as a test.
     */
    fun testUnsavedIdeChangesConflictingWithDiskAreNotEdited() = runBlocking {
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

    // ── Multi-file refactorings update stale files on disk, including usages only there ─

    fun testRenameUpdatesACallerChangedBehindTheIdesBack() = runBlocking {
        registerSourceRoot("stale-rename")
        val target = "stale-rename/sr/StaleTarget.java"
        val declaration = "    public static void work() {}"
        writeProjectFile(target, "package sr;\n\npublic class StaleTarget {\n$declaration\n}\n")
        val caller = "stale-rename/sr/StaleCaller.java"
        writeProjectFile(caller, "package sr;\n\nclass StaleCaller {\n    void a() { StaleTarget.work(); }\n}\n")
        writeBehindTheIdesBack(
            caller,
            "package sr;\n\nclass StaleCaller {\n    void a() { StaleTarget.work(); }\n    void b() { StaleTarget.work(); }\n}\n"
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", target)
            put("line", 4)
            put("column", declaration.indexOf("work") + 1)
            put("newName", "run")
            put("relatedRenamingStrategy", "none")
        })

        assertToolSucceeded("Rename must succeed when a caller changed behind the IDE's back", result)
        assertEquals(
            "package sr;\n\nclass StaleCaller {\n    void a() { StaleTarget.run(); }\n    void b() { StaleTarget.run(); }\n}\n",
            readFromDisk(caller)
        )
        assertOnDisk(target, contains = listOf("public static void run()"), absent = "work")
    }

    fun testChangeSignatureUpdatesACallerChangedBehindTheIdesBack() = runBlocking {
        registerSourceRoot("stale-sig")
        val target = "stale-sig/StaleFormatter.java"
        val declaration = "    public static String format(String raw) {"
        writeProjectFile(target, "public class StaleFormatter {\n$declaration\n        return raw;\n    }\n}\n")
        val caller = "stale-sig/StaleSigCaller.java"
        writeProjectFile(caller, "public class StaleSigCaller {\n    String a() { return StaleFormatter.format(\"a\"); }\n}\n")
        writeBehindTheIdesBack(
            caller,
            "public class StaleSigCaller {\n    String a() { return StaleFormatter.format(\"a\"); }\n" +
                "    String b() { return StaleFormatter.format(\"b\"); }\n}\n"
        )

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", target)
            put("line", 2)
            put("column", declaration.indexOf("format") + 1)
            put("newParameters", buildJsonArray {
                add(buildJsonObject { put("oldIndex", 0); put("name", "raw"); put("type", "String") })
                add(buildJsonObject {
                    put("oldIndex", -1); put("name", "strict"); put("type", "boolean"); put("defaultValue", "false")
                })
            })
        })

        assertToolSucceeded("Change signature must succeed when a caller changed behind the IDE's back", result)
        assertOnDisk(
            caller,
            contains = listOf("StaleFormatter.format(\"a\", false)", "StaleFormatter.format(\"b\", false)"),
            absent = "(\"b\")"
        )
    }

    fun testMoveUpdatesAnImporterChangedBehindTheIdesBack() = runBlocking {
        registerSourceRoot("stale-move")
        writeProjectFile("stale-move/staleorigin/StaleMoved.java", "package staleorigin;\n\npublic class StaleMoved {}\n")
        val client = "stale-move/staleconsumer/StaleClient.java"
        writeProjectFile(
            client,
            "package staleconsumer;\n\nimport staleorigin.StaleMoved;\n\npublic class StaleClient {\n    StaleMoved a;\n}\n"
        )
        writeBehindTheIdesBack(
            client,
            "package staleconsumer;\n\nimport staleorigin.StaleMoved;\n\npublic class StaleClient {\n" +
                "    StaleMoved a;\n    StaleMoved b;\n}\n"
        )

        val result = MoveFileTool().execute(project, buildJsonObject {
            put("file", "stale-move/staleorigin/StaleMoved.java")
            put("destination", "stale-move/staletarget")
        })

        assertToolSucceeded("Move must succeed when an importer changed behind the IDE's back", result)
        assertOnDisk(client, contains = listOf("import staletarget.StaleMoved;", "StaleMoved b;"), absent = "staleorigin")
    }

    fun testSafeDeleteRemovesAMemberFromAFileChangedBehindTheIdesBack() = runBlocking {
        registerSourceRoot("stale-sd")
        val file = "stale-sd/stalesd/StaleHelper.java"
        val declaration = "    public String unused() {"
        writeProjectFile(file, "package stalesd;\n\npublic class StaleHelper {\n$declaration\n        return \"unused\";\n    }\n}\n")
        writeBehindTheIdesBack(
            file,
            "package stalesd;\n\npublic class StaleHelper {\n$declaration\n        return \"unused\";\n    }\n" +
                "    public String external() {\n        return \"external\";\n    }\n}\n"
        )

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", file)
            put("line", 4)
            put("column", declaration.indexOf("unused") + 1)
        })

        assertToolSucceeded("Safe delete must succeed on a file changed behind the IDE's back", result)
        assertOnDisk(file, contains = listOf("public String external()"), absent = "unused")
    }

    fun testStructuralReplaceRewritesAMatchThatExistsOnlyOnDisk() = runBlocking {
        registerSourceRoot("stale-ssr")
        val file = "stale-ssr/StaleSsr.java"
        writeProjectFile(file, "public class StaleSsr {\n    void a() { StaleSsrSink.write(\"a\"); }\n}\n")
        writeBehindTheIdesBack(
            file,
            "public class StaleSsr {\n    void a() { StaleSsrSink.write(\"a\"); }\n" +
                "    void b() { StaleSsrSink.write(\"b\"); }\n}\n"
        )

        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {
            put("searchPattern", "StaleSsrSink.write(\$x\$)")
            put("replacePattern", "StaleSsrSink.send(\$x\$)")
            put("filePattern", "*.java")
        })

        assertToolSucceeded("Structural replace must succeed on a file changed behind the IDE's back", result)
        assertOnDisk(
            file,
            contains = listOf("StaleSsrSink.send(\"a\");", "StaleSsrSink.send(\"b\");"),
            absent = "write("
        )
    }

    /**
     * The file changes on disk after the refactoring synced the project but before it saves. The
     * platform declines the save; the tool must report the file rather than success, and reload
     * it so its unsaved change cannot be written later or block tool calls behind a prompt.
     */
    fun testRefactoringReportsAFileWhoseSaveWasDeclined() = runBlocking {
        registerSourceRoot("race-sd")
        val file = "race-sd/racesd/RaceHelper.java"
        val declaration = "    public String unused() {"
        writeProjectFile(file, "package racesd;\n\npublic class RaceHelper {\n$declaration\n        return \"unused\";\n    }\n}\n")
        val external = "package racesd;\n\npublic class RaceHelper {\n$declaration\n        return \"unused\";\n    }\n" +
            "    public String external() {\n        return \"external\";\n    }\n}\n"
        val tool = SafeDeleteTool()
        tool.beforeDeletionHook = { writeBehindTheIdesBack(file, external) }

        val result = tool.execute(project, buildJsonObject {
            put("file", file)
            put("line", 4)
            put("column", declaration.indexOf("unused") + 1)
        })

        assertToolFailed("A refactoring whose save was declined must not report success", result)
        assertTrue("Error must name the file: ${toolText(result)}", toolText(result).contains(file))
        assertTrue("Error must say the changes did not land: ${toolText(result)}", toolText(result).contains("did not reach"))
        assertEquals("The other program's version stays on disk", external, readFromDisk(file))
        val document = cachedDocument(file)
        assertEquals("The declined change must not linger in memory", external, textOf(document))
        assertFalse("Nothing may be left for autosave to write later", FileDocumentManager.getInstance().isDocumentUnsaved(document))
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
