package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.psi.PsiElement
import com.intellij.util.containers.MultiMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RenameSymbolToolBehaviorTest : McpPlatformTestCase() {

    fun testExplicitFileRenameIgnoresMalformedCoordinatesDuringFullToolExecution() = runBlocking {
        writeProjectFile(
            "docs/readme.txt",
            "Rename me through file mode.\n"
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "docs/readme.txt")
            put("targetType", "file")
            put("line", JsonPrimitive("not-a-number"))
            put("column", JsonPrimitive("still-not-a-number"))
            put("newName", "readme-renamed.txt")
        })

        assertToolSucceeded("Explicit file rename should ignore malformed line/column values", result)
        assertProjectFileAbsent("docs/readme.txt")
        assertProjectFileExists("docs/readme-renamed.txt")
        assertFileContains("docs/readme-renamed.txt", "Rename me through file mode.")
    }

    // ── Java: symbol rename ──

    fun testJavaRenameMethodUpdatesCallSitesWithinFile() = runBlocking {
        writeProjectFile(
            "src/UserService.java", """
            public class UserService {
                public String getDisplayName() {
                    return "name";
                }
                public String show() {
                    return getDisplayName();
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/UserService.java")
            put("line", 2)
            put("column", 19)
            put("newName", "getFullName")
        })

        assertToolSucceeded("Java method rename should succeed", result)
        assertRenamedInFile("src/UserService.java", "getDisplayName", "getFullName")
        assertFileContains("src/UserService.java", "public String getFullName()")
        assertFileContains("src/UserService.java", "return getFullName();")
    }

    fun testJavaRenameFieldUpdatesReferencesWithinFile() = runBlocking {
        writeProjectFile(
            "src/FieldRenameTarget.java", """
            public class FieldRenameTarget {
                public int count = 0;
                public void increment() {
                    count = count + 1;
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/FieldRenameTarget.java")
            put("line", 2)
            put("column", 16)
            put("newName", "total")
        })

        assertToolSucceeded("Java field rename should succeed", result)
        assertFileContains("src/FieldRenameTarget.java", "public int total = 0;")
        assertFileContains("src/FieldRenameTarget.java", "total = total + 1;")
        assertFileDoesNotContain("src/FieldRenameTarget.java", "int count")
        assertFileDoesNotContain("src/FieldRenameTarget.java", "count = count")
    }

    fun testJavaRenameClassRenamesFile() = runBlocking {
        writeProjectFile(
            "src/OldName.java", """
            public class OldName {
                public void doWork() {}
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/OldName.java")
            put("line", 1)
            put("column", 14)
            put("newName", "NewName")
        })

        assertToolSucceeded("Java class rename should succeed", result)
        assertProjectFileAbsent("src/OldName.java")
        assertProjectFileExists("src/NewName.java")
        assertFileContains("src/NewName.java", "public class NewName {")
        assertFileDoesNotContain("src/NewName.java", "OldName")
    }

    fun testJavaRenameParameterUpdatesUsagesInBody() = runBlocking {
        writeProjectFile(
            "src/Processor.java", """
            public class Processor {
                public String process(String input) {
                    return input.trim();
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/Processor.java")
            put("line", 2)
            put("column", 34)
            put("newName", "rawValue")
        })

        assertToolSucceeded("Java parameter rename should succeed", result)
        assertFileContains("src/Processor.java", "public String process(String rawValue)")
        assertFileContains("src/Processor.java", "return rawValue.trim();")
        assertFileDoesNotContain("src/Processor.java", "input")
    }

    // ── Java: cross-file symbol rename ──
    //
    // Cross-file reference updating is the whole point of routing a rename through the IDE
    // index, so the referencing file — not just the declaring one — carries the assertions.
    // A source root is required for `ReferencesSearch` to see the second file at all.

    fun testJavaRenameMethodUpdatesCallSiteInAnotherFile() = runBlocking {
        registerSourceRoot("cross-method-src")
        writeProjectFile(
            "cross-method-src/crossmethod/Greeter.java", """
            package crossmethod;

            public class Greeter {
                public String getDisplayName() {
                    return "name";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "cross-method-src/crossmethod/GreeterClient.java", """
            package crossmethod;

            public class GreeterClient {
                public String describe(Greeter greeter) {
                    return greeter.getDisplayName();
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "cross-method-src/crossmethod/Greeter.java")
            put("line", 4)
            put("column", 19)
            put("newName", "getFullName")
        })

        assertToolSucceeded("Cross-file Java method rename should succeed", result)
        assertRenamedInFile("cross-method-src/crossmethod/Greeter.java", "getDisplayName", "getFullName")
        assertRenamedInFile("cross-method-src/crossmethod/GreeterClient.java", "getDisplayName", "getFullName")
        assertFileContains("cross-method-src/crossmethod/GreeterClient.java", "return greeter.getFullName();")
    }

    fun testJavaRenameClassUpdatesReferencingFileAndRenamesSourceFile() = runBlocking {
        registerSourceRoot("cross-class-src")
        writeProjectFile(
            "cross-class-src/crossclass/LegacyReport.java", """
            package crossclass;

            public class LegacyReport {
                public String title() {
                    return "report";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "cross-class-src/crossclass/ReportPrinter.java", """
            package crossclass;

            public class ReportPrinter {
                public String print(LegacyReport source) {
                    return source.title();
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "cross-class-src/crossclass/LegacyReport.java")
            put("line", 3)
            put("column", 14)
            put("newName", "ArchivedReport")
        })

        assertToolSucceeded("Cross-file Java class rename should succeed", result)
        assertProjectFileAbsent("cross-class-src/crossclass/LegacyReport.java")
        assertProjectFileExists("cross-class-src/crossclass/ArchivedReport.java")
        assertFileContains("cross-class-src/crossclass/ArchivedReport.java", "public class ArchivedReport {")
        assertRenamedInFile("cross-class-src/crossclass/ReportPrinter.java", "LegacyReport", "ArchivedReport")
        assertFileContains("cross-class-src/crossclass/ReportPrinter.java", "public String print(ArchivedReport source)")
    }

    // ── Java: file rename mode ──
    //
    // Renaming a Java file without renaming its matching public class leaves
    // `public class Foo` inside `Bar.java` — guaranteed non-compiling output. File mode must
    // retarget onto the class (which renames the file and all references), but ONLY when the
    // class name matches the file base name; otherwise a plain file rename must be preserved.

    fun testJavaFileRenameRetargetsToMatchingClassAndUpdatesReferences() = runBlocking {
        registerSourceRoot("file-rename-src")
        writeProjectFile(
            "file-rename-src/filerename/ExternalCaller.java", """
            package filerename;

            public class ExternalCaller {
                public String call() {
                    return "called";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "file-rename-src/filerename/CallerClient.java", """
            package filerename;

            public class CallerClient {
                public String use(ExternalCaller caller) {
                    return caller.call();
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "file-rename-src/filerename/ExternalCaller.java")
            put("targetType", "file")
            put("newName", "ExternalGreetingCaller.java")
        })

        assertToolSucceeded("Java file rename with matching class should succeed", result)
        assertProjectFileAbsent("file-rename-src/filerename/ExternalCaller.java")
        assertProjectFileExists("file-rename-src/filerename/ExternalGreetingCaller.java")
        assertRenamedInFile(
            "file-rename-src/filerename/ExternalGreetingCaller.java",
            "ExternalCaller",
            "ExternalGreetingCaller"
        )
        assertFileContains(
            "file-rename-src/filerename/ExternalGreetingCaller.java",
            "public class ExternalGreetingCaller {"
        )
        assertRenamedInFile(
            "file-rename-src/filerename/CallerClient.java",
            "ExternalCaller",
            "ExternalGreetingCaller"
        )
    }

    fun testJavaFileRenameWithMismatchedClassNameFallsBackToPlainFileRename() = runBlocking {
        writeProjectFile(
            "src/misc/Helpers.java", """
            class HelperImpl {
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/misc/Helpers.java")
            put("targetType", "file")
            put("newName", "Utility.java")
        })

        assertToolSucceeded("Mismatched-class Java file rename should fall back to plain file rename", result)
        assertProjectFileAbsent("src/misc/Helpers.java")
        assertProjectFileExists("src/misc/Utility.java")
        assertFileContains("src/misc/Utility.java", "class HelperImpl")
        assertFileDoesNotContain("src/misc/Utility.java", "class Utility")
    }

    // ── Conflict error sanitization ──
    //
    // Java's conflict messages are built for the IDE's HTML ConflictsDialog: element names
    // arrive wrapped in <b><code>…</code></b>. A method-onto-existing-method rename is used
    // here because ConflictsUtil.checkMethodConflicts produces exactly that markup, so this
    // test fails when the sanitization is removed.

    fun testJavaRenameConflictErrorContainsNoHtmlMarkup() = runBlocking {
        writeProjectFile(
            "src/ConflictHost.java", """
            public class ConflictHost {
                public String getName() {
                    return "a";
                }
                public String getTitle() {
                    return "b";
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/ConflictHost.java")
            put("targetType", "symbol")
            put("line", 5)
            put("column", 19)
            put("newName", "getName")
        })

        assertToolFailed("Renaming a method onto an existing method name should report a conflict", result)
        val message = toolText(result)
        assertTrue("Expected a name-conflict error, got: $message", message.contains("Name conflict"))
        assertTrue("Conflict message should name the conflicting method, got: $message", message.contains("getName"))
        assertFalse("Conflict message must not contain '<b>': $message", message.contains("<b>"))
        assertFalse("Conflict message must not contain '<code>': $message", message.contains("<code>"))
        assertFalse("Conflict message must not contain '&lt;': $message", message.contains("&lt;"))
    }

    // ── Silent platform aborts must not be reported as success ──

    /**
     * The regression test for the silent-abort bug. In production,
     * `BaseRefactoringProcessor.run()` returns normally on abort paths (conflicts dialog
     * cancelled, read-only files) — verified against the platform bytecode. In unit-test
     * mode the platform converts every such abort into an exception before `run()` returns,
     * so the silent return is reproduced through the tool's `processorRunHook` instead.
     * Without post-run verification the tool reported `success: true` with the target's file
     * in `affectedFiles` for a rename that changed nothing; this test fails with "expected
     * an error but tool succeeded" if the verification is removed.
     */
    fun testSilentlyAbortedRenameReportsErrorInsteadOfSuccess() = runBlocking {
        writeProjectFile(
            "src/AbortRename.java", """
            public class AbortRename {
                public String process(String input) {
                    return input.trim();
                }
            }
        """.trimIndent()
        )

        val tool = RenameSymbolTool()
        tool.processorRunHook = { /* BaseRefactoringProcessor.run() returning without applying */ }

        val result = tool.execute(project, buildJsonObject {
            put("file", "src/AbortRename.java")
            put("targetType", "symbol")
            put("line", 2)
            put("column", 19)
            put("newName", "processRenamed")
        })

        assertToolFailed("An aborted rename must not be reported as success", result)
        assertTrue(
            "Expected the honest abort error, got: ${toolText(result)}",
            toolText(result).contains("was not applied")
        )
        assertFileContains("src/AbortRename.java", "public String process(String input)")
        assertFileDoesNotContain("src/AbortRename.java", "processRenamed")
    }

    /**
     * In production, `RenameProcessor.preprocessUsages` builds the conflicts dialog directly
     * via `prepareConflictsDialog` — it never routes through the overridable `showConflicts` —
     * and an unanswered or cancelled dialog silently aborts the rename. The headless
     * processor therefore overrides `prepareConflictsDialog` to proceed and capture the
     * sanitized conflicts. That path is unreachable end-to-end in unit-test mode
     * (`preprocessUsages` throws `ConflictsInTestsException` first), so the override is
     * exercised directly. If it is removed, the base implementation constructs the real
     * Swing conflicts dialog, which fails in headless tests, and `capturedConflicts` stays
     * empty.
     */
    fun testHeadlessRenameProcessorProceedsThroughConflictsDialogAndCapturesSanitizedConflicts() {
        val psiFile = myFixture.addFileToProject("conflictdialog/Host.java", "public class Host {}")
        val psiClass = com.intellij.psi.util.PsiTreeUtil
            .findChildOfType(psiFile, com.intellij.psi.PsiClass::class.java)
            ?: error("Fixture class not found")

        val processor = HeadlessRenameProcessor(project, psiClass, "RenamedHost", false, false)
        val conflicts = MultiMap<PsiElement, String>()
        conflicts.putValue(psiClass, "Field <b><code>bar</code></b> will hide the local variable")

        val dialog = processor.prepareConflictsDialog(conflicts, null)

        assertTrue("The headless conflicts dialog must proceed with the rename", dialog.showAndGet())
        assertFalse("The headless conflicts dialog must not request the conflicts view", dialog.isShowConflicts())
        assertEquals(
            listOf("Field bar will hide the local variable"),
            processor.capturedConflicts
        )
    }
}
