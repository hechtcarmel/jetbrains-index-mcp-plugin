package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Behavior coverage for `ide_refactor_safe_delete`.
 *
 * This tool is enabled by default and deletes source code, yet the only executions in the suite
 * were two error paths (missing arguments, missing file). Nothing proved it ever deletes anything,
 * and — worse for a tool whose selling point is the word "safe" — nothing proved it refuses when
 * the target is still referenced.
 *
 * Every fixture registers its own source root: without one `ReferencesSearch` sees no second file,
 * the usage scan comes back empty, and a "safe" delete happily removes a symbol that is still
 * called. A refusal test built on an unregistered root would pass for exactly that reason.
 */
class SafeDeleteToolBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decodeRefactoring(text: String): RefactoringResult = json.decodeFromString(text)

    private fun decodeSymbolBlocked(text: String): SafeDeleteBlockedResult = json.decodeFromString(text)

    private fun decodeFileBlocked(text: String): SafeDeleteFileBlockedResult = json.decodeFromString(text)

    private fun decodeNoSymbolFound(text: String): NoSymbolFoundResult = json.decodeFromString(text)

    // ── Success: the symbol is really gone ──

    fun testUnusedJavaMethodIsRemovedAndItsSiblingSurvives() = runBlocking {
        registerSourceRoot("sd-symbol-src")
        writeProjectFile(
            "sd-symbol-src/housekeeping/Housekeeping.java", """
            package housekeeping;

            public class Housekeeping {
                public String keep() {
                    return "keep";
                }

                public String unusedHelper() {
                    return "unused";
                }
            }
        """.trimIndent()
        )

        assertFileContains("sd-symbol-src/housekeeping/Housekeeping.java", "unusedHelper")

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-symbol-src/housekeeping/Housekeeping.java")
            put("line", 8)
            put("column", 19)
        })

        assertToolSucceeded("Deleting an unreferenced method should succeed", result)
        val payload = decodeRefactoring(toolText(result))
        assertTrue("Payload must report success", payload.success)
        assertEquals(listOf("sd-symbol-src/housekeeping/Housekeeping.java"), payload.affectedFiles)
        assertEquals("Successfully deleted 'unusedHelper'", payload.message)

        assertFileDoesNotContain("sd-symbol-src/housekeeping/Housekeeping.java", "unusedHelper")
        assertFileContains("sd-symbol-src/housekeeping/Housekeeping.java", "public String keep()")
        assertFileContains("sd-symbol-src/housekeeping/Housekeeping.java", "return \"keep\";")
    }

    fun testUnreferencedJavaFileIsDeletedFromDisk() = runBlocking {
        registerSourceRoot("sd-file-src")
        writeProjectFile(
            "sd-file-src/housekeeping/UnusedUtils.java", """
            package housekeeping;

            public class UnusedUtils {
                public static String helper() {
                    return "helper";
                }
            }
        """.trimIndent()
        )
        assertProjectFileExists("sd-file-src/housekeeping/UnusedUtils.java")

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-file-src/housekeeping/UnusedUtils.java")
            put("target_type", "file")
        })

        assertToolSucceeded("Deleting an unreferenced file should succeed", result)
        val payload = decodeRefactoring(toolText(result))
        assertTrue("Payload must report success", payload.success)
        assertEquals(listOf("sd-file-src/housekeeping/UnusedUtils.java"), payload.affectedFiles)
        assertEquals(
            "Successfully deleted file 'UnusedUtils.java' (contained 1 symbol(s) with no external usages)",
            payload.message
        )
        assertProjectFileAbsent("sd-file-src/housekeeping/UnusedUtils.java")
    }

    // ── Self-contained symbols: references inside the deleted element must not block ──
    //
    // The references vanish together with the element, so counting them as blocking
    // usages pushes agents toward force=true for perfectly safe deletions. File-delete
    // mode always excluded same-file usages; symbol mode used to count them.

    fun testSelfRecursiveMethodDeletesWithoutForce() = runBlocking {
        registerSourceRoot("sd-selfref-src")
        writeProjectFile(
            "sd-selfref-src/selfref/MathUtil.java", """
            package selfref;

            public class MathUtil {
                public String keep() {
                    return "keep";
                }

                private int fact(int n) {
                    return n <= 1 ? 1 : fact(n - 1);
                }
            }
        """.trimIndent()
        )

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-selfref-src/selfref/MathUtil.java")
            put("line", 8)
            put("column", 17)
        })

        assertToolSucceeded("A self-recursive method with no external callers must delete without force", result)
        val payload = decodeRefactoring(toolText(result))
        assertTrue("Payload must report success", payload.success)
        assertEquals("Successfully deleted 'fact'", payload.message)
        assertFileDoesNotContain("sd-selfref-src/selfref/MathUtil.java", "fact")
        assertFileContains("sd-selfref-src/selfref/MathUtil.java", "public String keep()")
    }

    fun testClassWhoseOnlyReferencesAreItsOwnFactoryDeletesWithoutForce() = runBlocking {
        registerSourceRoot("sd-selffactory-src")
        writeProjectFile(
            "sd-selffactory-src/selffactory/Widget.java", """
            package selffactory;

            public class Widget {
                public static Widget create() {
                    return new Widget();
                }
            }

            class WidgetSibling {
            }
        """.trimIndent()
        )

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-selffactory-src/selffactory/Widget.java")
            put("line", 3)
            put("column", 14)
        })

        assertToolSucceeded("A class referenced only from inside itself must delete without force", result)
        val payload = decodeRefactoring(toolText(result))
        assertTrue("Payload must report success", payload.success)
        assertEquals("Successfully deleted 'Widget'", payload.message)
        assertFileDoesNotContain("sd-selffactory-src/selffactory/Widget.java", "class Widget {")
        assertFileDoesNotContain("sd-selffactory-src/selffactory/Widget.java", "new Widget()")
        assertFileContains("sd-selffactory-src/selffactory/Widget.java", "class WidgetSibling")
    }

    // ── Refusal: the blocking usage is named and nothing is deleted ──

    fun testReferencedJavaMethodIsRefusedAndTheCallSiteIsReported() = runBlocking {
        registerSourceRoot("sd-blocked-src")
        writeProjectFile(
            "sd-blocked-src/blocked/PaymentGateway.java", """
            package blocked;

            public class PaymentGateway {
                public String charge() {
                    return "charged";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "sd-blocked-src/blocked/CheckoutService.java", """
            package blocked;

            public class CheckoutService {
                public String checkout(PaymentGateway gateway) {
                    return gateway.charge();
                }
            }
        """.trimIndent()
        )

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-blocked-src/blocked/PaymentGateway.java")
            put("line", 4)
            put("column", 19)
        })

        assertToolSucceeded("A refusal is a structured answer, not a protocol error", result)
        val payload = decodeSymbolBlocked(toolText(result))
        assertFalse("canDelete must be false while a call site exists", payload.canDelete)
        assertEquals("charge", payload.elementName)
        assertEquals("method", payload.elementType)
        assertEquals(1, payload.usageCount)
        assertEquals(
            "The blocking call site must be named so the agent can fix it. Got: ${payload.blockingUsages}",
            listOf("sd-blocked-src/blocked/CheckoutService.java"),
            payload.blockingUsages.map { it.file }
        )
        val usage = payload.blockingUsages.single()
        assertEquals(5, usage.line)
        assertEquals("return gateway.charge();", usage.context)
        assertEquals(
            "Cannot delete 'charge': found 1 usage(s). Use force=true to delete anyway.",
            payload.message
        )

        assertFileContains("sd-blocked-src/blocked/PaymentGateway.java", "public String charge()")
        assertFileContains("sd-blocked-src/blocked/CheckoutService.java", "return gateway.charge();")
    }

    fun testReferencedJavaFileIsRefusedAndTheReferencingFileIsReported() = runBlocking {
        registerSourceRoot("sd-fileblocked-src")
        writeProjectFile(
            "sd-fileblocked-src/fileblocked/ReportFormatter.java", """
            package fileblocked;

            public class ReportFormatter {
                public String format() {
                    return "report";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "sd-fileblocked-src/fileblocked/ReportPrinter.java", """
            package fileblocked;

            public class ReportPrinter {
                public String print(ReportFormatter formatter) {
                    return formatter.format();
                }
            }
        """.trimIndent()
        )

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-fileblocked-src/fileblocked/ReportFormatter.java")
            put("target_type", "file")
        })

        assertToolSucceeded("A refusal is a structured answer, not a protocol error", result)
        val payload = decodeFileBlocked(toolText(result))
        assertFalse("canDelete must be false while the file's class is referenced", payload.canDelete)
        assertEquals("ReportFormatter.java", payload.fileName)
        assertEquals(1, payload.symbolCount)
        assertEquals(1, payload.externalUsageCount)
        val usage = payload.blockingUsages.single()
        assertEquals("sd-fileblocked-src/fileblocked/ReportPrinter.java", usage.file)
        assertEquals("public String print(ReportFormatter formatter) {", usage.context)
        assertEquals(
            "Cannot delete file 'ReportFormatter.java': found 1 external usage(s) of symbols in " +
                "this file. Use force=true to delete anyway.",
            payload.message
        )

        assertProjectFileExists("sd-fileblocked-src/fileblocked/ReportFormatter.java")
        assertFileContains("sd-fileblocked-src/fileblocked/ReportFormatter.java", "public String format()")
    }

    /**
     * `force=true` is the documented escape hatch, and it is the only argument that can turn a
     * refusal into a deletion. Left unexercised, dropping the flag from the blocking check would
     * be indistinguishable from honouring it.
     */
    fun testForceDeletesAReferencedMethodAndSaysTheUsagesMayBeBroken() = runBlocking {
        registerSourceRoot("sd-force-src")
        writeProjectFile(
            "sd-force-src/forced/PaymentGateway.java", """
            package forced;

            public class PaymentGateway {
                public String charge() {
                    return "charged";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "sd-force-src/forced/CheckoutService.java", """
            package forced;

            public class CheckoutService {
                public String checkout(PaymentGateway gateway) {
                    return gateway.charge();
                }
            }
        """.trimIndent()
        )

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-force-src/forced/PaymentGateway.java")
            put("line", 4)
            put("column", 19)
            put("force", true)
        })

        assertToolSucceeded("force=true must delete despite usages", result)
        val payload = decodeRefactoring(toolText(result))
        assertEquals(
            "Force-deleted 'charge' (had 1 usage(s) that may now be broken)",
            payload.message
        )
        assertFileDoesNotContain("sd-force-src/forced/PaymentGateway.java", "charge()")
        assertFileContains("sd-force-src/forced/PaymentGateway.java", "public class PaymentGateway")
    }

    /**
     * Targeting whitespace must not fall back to deleting the enclosing file. `findNamedElement`
     * excludes `PsiFile` precisely to prevent that, and this is the only test that would notice if
     * the exclusion were dropped.
     */
    fun testWhitespacePositionSuggestsNearbySymbolsInsteadOfDeletingTheFile() = runBlocking {
        registerSourceRoot("sd-suggest-src")
        writeProjectFile(
            "sd-suggest-src/suggest/Suggestable.java", """
            package suggest;

            public class Suggestable {
                public String keep() {
                    return "keep";
                }
            }
        """.trimIndent()
        )

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-suggest-src/suggest/Suggestable.java")
            put("line", 2)
            put("column", 1)
        })

        assertToolSucceeded("A miss returns suggestions, not a protocol error", result)
        val payload = decodeNoSymbolFound(toolText(result))
        assertEquals("No symbol found at line 2, column 1 (found whitespace)", payload.error)
        assertEquals("whitespace", payload.position.elementType)
        assertTrue(
            "Nearby declarations must be offered. Got: ${payload.suggestions}",
            payload.suggestions.any { it.name == "keep" && it.type == "method" }
        )

        assertProjectFileExists("sd-suggest-src/suggest/Suggestable.java")
        assertFileContains("sd-suggest-src/suggest/Suggestable.java", "public String keep()")
    }

    // ── Stale PSI between the usage check (phase 1) and the write action (phase 2) ──
    //
    // The write action used to skip the delete when the element had been invalidated, yet
    // still report "Successfully deleted". These tests recreate the file in the window
    // between the two phases (via the tool's @TestOnly hook) and require an explicit
    // error, with the source text untouched.

    fun testStaleSymbolElementIsAnErrorNotASilentSuccess() = runBlocking {
        registerSourceRoot("sd-stale-src")
        writeProjectFile(
            "sd-stale-src/stale/Stale.java", """
            package stale;

            public class Stale {
                public String unusedHelper() {
                    return "unused";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.beforeDeletionHook = { recreateFileOnDisk("sd-stale-src/stale/Stale.java") }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sd-stale-src/stale/Stale.java")
            put("line", 4)
            put("column", 19)
        })

        assertToolFailed("An invalidated element must be an error, not 'Successfully deleted'", result)
        val text = toolText(result)
        assertTrue(
            "Error must say the element went stale and ask for a retry. Got: $text",
            text.contains("no longer valid") && text.contains("Retry")
        )
        assertFileContains("sd-stale-src/stale/Stale.java", "unusedHelper")
    }

    fun testStaleFileElementIsAnErrorNotASilentSuccess() = runBlocking {
        registerSourceRoot("sd-stalefile-src")
        writeProjectFile(
            "sd-stalefile-src/stalefile/StaleFile.java", """
            package stalefile;

            public class StaleFile {
                public String helper() {
                    return "helper";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.beforeDeletionHook = { recreateFileOnDisk("sd-stalefile-src/stalefile/StaleFile.java") }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sd-stalefile-src/stalefile/StaleFile.java")
            put("target_type", "file")
        })

        assertToolFailed("An invalidated file must be an error, not 'Successfully deleted'", result)
        val text = toolText(result)
        assertTrue(
            "Error must say the file went stale and ask for a retry. Got: $text",
            text.contains("no longer valid") && text.contains("Retry")
        )
        assertProjectFileExists("sd-stalefile-src/stalefile/StaleFile.java")
        assertFileContains("sd-stalefile-src/stalefile/StaleFile.java", "class StaleFile")
    }

    // ── A failed usage search must refuse the delete, never report "no usages" ──
    //
    // findUsages used to swallow every exception and return the partial (usually empty)
    // list, so a broken search made any symbol look safe to delete.

    fun testUsageSearchFailureRefusesSymbolDeleteWithoutForce() = runBlocking {
        registerSourceRoot("sd-searchfail-src")
        writeProjectFile(
            "sd-searchfail-src/searchfail/SearchFail.java", """
            package searchfail;

            public class SearchFail {
                public String unusedHelper() {
                    return "unused";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.usageSearchHook = { throw IllegalStateException("simulated search failure") }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sd-searchfail-src/searchfail/SearchFail.java")
            put("line", 4)
            put("column", 19)
        })

        assertToolFailed("A failed usage search must refuse the delete", result)
        val text = toolText(result)
        assertTrue("Error must name the failure. Got: $text", text.contains("Usage search failed"))
        assertTrue("Error must carry the underlying reason. Got: $text", text.contains("simulated search failure"))
        assertTrue("Error must offer the force=true escape hatch. Got: $text", text.contains("force=true"))
        assertFileContains("sd-searchfail-src/searchfail/SearchFail.java", "unusedHelper")
    }

    fun testUsageSearchFailureRefusesFileDeleteWithoutForce() = runBlocking {
        registerSourceRoot("sd-filesearchfail-src")
        writeProjectFile(
            "sd-filesearchfail-src/filesearchfail/FileSearchFail.java", """
            package filesearchfail;

            public class FileSearchFail {
                public String helper() {
                    return "helper";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.usageSearchHook = { throw IllegalStateException("simulated search failure") }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sd-filesearchfail-src/filesearchfail/FileSearchFail.java")
            put("target_type", "file")
        })

        assertToolFailed("A failed usage search must refuse the file delete", result)
        val text = toolText(result)
        assertTrue("Error must name the failure. Got: $text", text.contains("Usage search failed"))
        assertTrue("Error must offer the force=true escape hatch. Got: $text", text.contains("force=true"))
        assertProjectFileExists("sd-filesearchfail-src/filesearchfail/FileSearchFail.java")
        assertFileContains("sd-filesearchfail-src/filesearchfail/FileSearchFail.java", "class FileSearchFail")
    }

    /**
     * force=true means "delete regardless of usages", so a failed usage search must not
     * block it — otherwise the documented escape hatch in the refusal message would be
     * a dead end.
     */
    fun testUsageSearchFailureWithForceStillDeletesTheSymbol() = runBlocking {
        registerSourceRoot("sd-forcefail-src")
        writeProjectFile(
            "sd-forcefail-src/forcefail/ForceFail.java", """
            package forcefail;

            public class ForceFail {
                public String unusedHelper() {
                    return "unused";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.usageSearchHook = { throw IllegalStateException("simulated search failure") }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sd-forcefail-src/forcefail/ForceFail.java")
            put("line", 4)
            put("column", 19)
            put("force", true)
        })

        assertToolSucceeded("force=true must delete even when the usage search fails", result)
        val payload = decodeRefactoring(toolText(result))
        assertTrue("Payload must report success", payload.success)
        assertFileDoesNotContain("sd-forcefail-src/forcefail/ForceFail.java", "unusedHelper")
        assertFileContains("sd-forcefail-src/forcefail/ForceFail.java", "public class ForceFail")
    }

    fun testUsageSearchFailureWithForceStillDeletesTheFile() = runBlocking {
        registerSourceRoot("sd-forcefilefail-src")
        writeProjectFile(
            "sd-forcefilefail-src/forcefilefail/ForceFileFail.java", """
            package forcefilefail;

            public class ForceFileFail {
                public String helper() {
                    return "helper";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.usageSearchHook = { throw IllegalStateException("simulated search failure") }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sd-forcefilefail-src/forcefilefail/ForceFileFail.java")
            put("target_type", "file")
            put("force", true)
        })

        assertToolSucceeded("force=true must delete the file even when the usage search fails", result)
        val payload = decodeRefactoring(toolText(result))
        assertTrue("Payload must report success", payload.success)
        assertProjectFileAbsent("sd-forcefilefail-src/forcefilefail/ForceFileFail.java")
    }

    /**
     * [IndexNotReadyException] thrown mid-search (dumb mode starting after the smart-mode
     * gate) must reach [com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool]'s
     * translation into the standard dumb-mode retry error instead of being swallowed into
     * an empty usage list followed by a delete.
     */
    fun testDumbModeDuringUsageSearchSurfacesTheRetryErrorInsteadOfDeleting() = runBlocking {
        registerSourceRoot("sd-dumbfail-src")
        writeProjectFile(
            "sd-dumbfail-src/dumbfail/DumbFail.java", """
            package dumbfail;

            public class DumbFail {
                public String unusedHelper() {
                    return "unused";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.usageSearchHook = { throw IndexNotReadyException.create() }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sd-dumbfail-src/dumbfail/DumbFail.java")
            put("line", 4)
            put("column", 19)
        })

        assertToolFailed("Dumb mode during the usage search must not end in a delete", result)
        assertTrue(
            "The standard dumb-mode retry guidance must surface. Got: ${toolText(result)}",
            toolText(result).contains("ide_index_status")
        )
        assertFileContains("sd-dumbfail-src/dumbfail/DumbFail.java", "unusedHelper")
    }

    fun testProcessCancellationDuringUsageSearchIsNotSwallowedIntoADelete() {
        registerSourceRoot("sd-pcefail-src")
        writeProjectFile(
            "sd-pcefail-src/pcefail/PceFail.java", """
            package pcefail;

            public class PceFail {
                public String unusedHelper() {
                    return "unused";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.usageSearchHook = { throw ProcessCanceledException() }

        val thrown = runCatching {
            runBlocking {
                tool.execute(project, buildJsonObject {
                    put("file", "sd-pcefail-src/pcefail/PceFail.java")
                    put("line", 4)
                    put("column", 19)
                })
            }
        }.exceptionOrNull()

        assertNotNull("Cancellation must propagate, not turn into a successful delete", thrown)
        assertFileContains("sd-pcefail-src/pcefail/PceFail.java", "unusedHelper")
    }

    /**
     * Deletes and recreates [relativePath] with identical content. Every PSI element the
     * tool captured during its preparation phase belongs to the old, now-deleted file and
     * is therefore invalid — while the source text is still on disk, exactly like an
     * external tool rewriting the file mid-operation.
     */
    private fun recreateFileOnDisk(relativePath: String) {
        val basePath = requireNotNull(project.basePath)
        val content = readProjectFileVfs(relativePath)
        val virtualFile = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/$relativePath")
        ) { "Missing test file $relativePath" }
        ApplicationManager.getApplication().runWriteAction { virtualFile.delete(this) }
        writeProjectFile(relativePath, content)
    }
}
