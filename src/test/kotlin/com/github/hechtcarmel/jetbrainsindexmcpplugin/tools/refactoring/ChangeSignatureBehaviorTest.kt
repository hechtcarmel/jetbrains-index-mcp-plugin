package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

class ChangeSignatureBehaviorTest : McpPlatformTestCase() {

    /**
     * Caller updating is the whole point of Change Signature, so it is asserted in both
     * directions.
     *
     * The source root registration is load-bearing, not decoration:
     * `ChangeSignatureProcessor.findUsages` is index-backed and the default `project_files` scope
     * only covers content roots. Without it the tool reports `success: true, changesCount: 1`,
     * rewrites the declaration, and leaves `process("hello")` untouched — non-compiling Java that
     * a one-directional `contains("boolean validate")` assertion happily certifies.
     */
    fun testChangeSignatureAddsParameterAndUpdatesCallSite() = runBlocking {
        registerSourceRoot("sig-src")
        writeProjectFile("sig-src/SigService.java", """
            public class SigService {
                public String process(String input) {
                    return input.trim();
                }
                public void caller() {
                    String result = process("hello");
                }
            }
        """.trimIndent())

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "sig-src/SigService.java")
            put("line", 2)
            put("column", 19)
            put("newParameters", buildJsonArray {
                add(buildJsonObject { put("oldIndex", 0); put("name", "input"); put("type", "String") })
                add(buildJsonObject { put("oldIndex", -1); put("name", "validate"); put("type", "boolean"); put("defaultValue", "true") })
            })
        })

        assertToolSucceeded("Change signature should succeed", result)
        assertFileContains("sig-src/SigService.java", "public String process(String input, boolean validate)")
        assertFileContains("sig-src/SigService.java", "process(\"hello\", true)")
        assertFileDoesNotContain("sig-src/SigService.java", "process(\"hello\")")
    }

    fun testChangeSignatureUpdatesCallSiteInAnotherFile() = runBlocking {
        registerSourceRoot("sig-cross")
        writeProjectFile("sig-cross/Formatter.java", """
            public class Formatter {
                public String format(String raw) {
                    return raw.trim();
                }
            }
        """.trimIndent())
        writeProjectFile("sig-cross/FormatterClient.java", """
            public class FormatterClient {
                public String run(Formatter formatter) {
                    return formatter.format("value");
                }
            }
        """.trimIndent())

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "sig-cross/Formatter.java")
            put("line", 2)
            put("column", 19)
            put("newParameters", buildJsonArray {
                add(buildJsonObject { put("oldIndex", 0); put("name", "raw"); put("type", "String") })
                add(buildJsonObject { put("oldIndex", -1); put("name", "upper"); put("type", "boolean"); put("defaultValue", "false") })
            })
        })

        assertToolSucceeded("Cross-file change signature should succeed", result)
        assertFileContains("sig-cross/Formatter.java", "public String format(String raw, boolean upper)")
        assertFileContains("sig-cross/FormatterClient.java", "formatter.format(\"value\", false)")
        assertFileDoesNotContain("sig-cross/FormatterClient.java", "formatter.format(\"value\")")
    }

    fun testChangeSignatureChangesReturnType() = runBlocking {
        writeProjectFile("src/SigConverter.java", """
            public class SigConverter {
                public String convert(int value) {
                    return String.valueOf(value);
                }
            }
        """.trimIndent())

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "src/SigConverter.java")
            put("line", 2)
            put("column", 19)
            put("newReturnType", "int")
            put("newParameters", buildJsonArray {
                add(buildJsonObject { put("oldIndex", 0); put("name", "value"); put("type", "int") })
            })
        })

        assertToolSucceeded("Change return type should succeed", result)
        val content = readProjectFileVfs("src/SigConverter.java")
        assertTrue("Return type should be int: $content", content.contains("public int convert"))
    }

    fun testChangeSignatureOnNonMethodFails() = runBlocking {
        writeProjectFile("src/SigNotAMethod.java", """
            public class SigNotAMethod {
                private int count = 0;
            }
        """.trimIndent())

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "src/SigNotAMethod.java")
            put("line", 2)
            put("column", 17)
            put("newReturnType", "String")
        })

        assertToolFailed("Change signature on a field should fail", result)
        assertEquals(
            "No method found at line 2, column 17. Position the cursor on a method name.",
            toolText(result)
        )
    }

    /**
     * A read-only target file makes the IDE abort the refactoring. In production that abort is
     * silent (`BaseRefactoringProcessor.doRun` returns without applying anything); in unit-test
     * mode the platform surfaces it as an exception instead. Either way the tool must report an
     * error, never success. Both directions are asserted: the tool fails AND the file is
     * untouched.
     */
    fun testChangeSignatureOnReadOnlyFileReportsError() = runBlocking {
        registerSourceRoot("sig-ro")
        val path = writeProjectFile("sig-ro/RoService.java", """
            public class RoService {
                public String process(String input) {
                    return input.trim();
                }
                public void caller() {
                    String result = process("hello");
                }
            }
        """.trimIndent())

        val virtualFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString()))
        WriteAction.runAndWait<IOException> { virtualFile.isWritable = false }
        try {
            val result = ChangeSignatureTool().execute(project, buildJsonObject {
                put("file", "sig-ro/RoService.java")
                put("line", 2)
                put("column", 19)
                put("newName", "processRenamed")
            })

            assertToolFailed("Change signature on a read-only file must report an error", result)
            assertFileContains("sig-ro/RoService.java", "public String process(String input)")
            assertFileDoesNotContain("sig-ro/RoService.java", "processRenamed")
        } finally {
            WriteAction.runAndWait<IOException> { virtualFile.isWritable = true }
        }
    }

    /**
     * The regression test for the silent-abort bug. In production,
     * `BaseRefactoringProcessor.run()` returns normally on abort paths (read-only target file,
     * conflict dialog dismissed, dumb mode) — verified against the platform bytecode. In
     * unit-test mode the platform converts every such abort into an exception before `run()`
     * returns, so the silent return is reproduced through the tool's `processorRunHook`
     * instead. Without post-run verification the tool reported
     * `success: true` for the aborted run; this test fails with "expected an error but tool
     * succeeded" if the verification is removed.
     */
    fun testSilentlyAbortedChangeSignatureReportsErrorInsteadOfSuccess() = runBlocking {
        registerSourceRoot("sig-abort")
        writeProjectFile("sig-abort/AbortSig.java", """
            public class AbortSig {
                public String process(String input) {
                    return input.trim();
                }
            }
        """.trimIndent())

        val tool = ChangeSignatureTool()
        tool.processorRunHook = { /* BaseRefactoringProcessor.run() returning without applying */ }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sig-abort/AbortSig.java")
            put("line", 2)
            put("column", 19)
            put("newName", "processRenamed")
        })

        assertToolFailed("An aborted change signature must not be reported as success", result)
        assertTrue(
            "Expected the honest abort error, got: ${toolText(result)}",
            toolText(result).contains("did not apply")
        )
        assertFileContains("sig-abort/AbortSig.java", "public String process(String input)")
        assertFileDoesNotContain("sig-abort/AbortSig.java", "processRenamed")
    }

    /**
     * The conservative side of the post-run verification: when at least one requested aspect
     * reached the PSI, the tool must keep reporting success exactly as before, even if another
     * requested aspect did not. Here the "processor" applies only the rename and skips the
     * return-type change; treating that partial application as a failed refactoring would be
     * a false negative.
     */
    fun testPartiallyAppliedChangeSignatureStillReportsSuccess() = runBlocking {
        registerSourceRoot("sig-partial")
        val path = writeProjectFile("sig-partial/PartialSig.java", """
            public class PartialSig {
                public String process(String input) {
                    return input.trim();
                }
            }
        """.trimIndent())

        val virtualFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString()))
        val tool = ChangeSignatureTool()
        tool.processorRunHook = {
            val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
            val method = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)
                .first { it.name == "process" }
            WriteCommandAction.runWriteCommandAction(project) { method.setName("processRenamed") }
        }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sig-partial/PartialSig.java")
            put("line", 2)
            put("column", 19)
            put("newName", "processRenamed")
            put("newReturnType", "int")
        })

        assertToolSucceeded("A partially applied change signature must still report success", result)
        assertFileContains("sig-partial/PartialSig.java", "processRenamed")
    }

    fun testChangeSignatureRequiresAtLeastOneChange() = runBlocking {
        writeProjectFile("src/SigNoChange.java", """
            public class SigNoChange {
                public void doWork() {}
            }
        """.trimIndent())

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "src/SigNoChange.java")
            put("line", 2)
            put("column", 17)
        })

        assertToolFailed("Should require at least one change", result)
        assertEquals(
            "At least one change is required: newName, newReturnType, newVisibility, or newParameters.",
            toolText(result)
        )
    }
}
