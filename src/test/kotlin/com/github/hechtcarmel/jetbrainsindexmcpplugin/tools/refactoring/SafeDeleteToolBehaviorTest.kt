package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
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
}
