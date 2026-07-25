package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Behavior coverage for `ide_optimize_imports`, which had no test references of any kind — not a
 * schema assertion, not a registration assertion, nothing. Deleting the class would have left the
 * suite green.
 *
 * The imported classes are declared inside the fixture instead of being pulled from the JDK, and
 * the fixture directory is registered as a source root, so `deps.Kept` genuinely resolves. An
 * unresolvable import is not a reliable subject: the platform keeps imports it cannot analyse, so
 * the "unused import removed" assertion would be testing resolution failure rather than the tool.
 */
class OptimizeImportsToolBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun writeDependencies(root: String) {
        writeProjectFile(
            "$root/deps/Kept.java", """
            package deps;

            public class Kept {
                public String value() {
                    return "kept";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "$root/deps/Dropped.java", """
            package deps;

            public class Dropped {
            }
        """.trimIndent()
        )
    }

    fun testUnusedImportIsRemovedWhileTheUsedImportIsKept() = runBlocking {
        registerSourceRoot("oi-src")
        writeDependencies("oi-src")
        writeProjectFile(
            "oi-src/app/ImportUser.java", """
            package app;

            import deps.Dropped;
            import deps.Kept;

            public class ImportUser {
                public String describe() {
                    return new Kept().value();
                }
            }
        """.trimIndent()
        )

        assertFileContains("oi-src/app/ImportUser.java", "import deps.Dropped;")

        val result = OptimizeImportsTool().execute(project, buildJsonObject {
            put("file", "oi-src/app/ImportUser.java")
        })

        assertToolSucceeded("optimize_imports should succeed on a resolvable Java file", result)
        val payload = json.decodeFromString<RefactoringResult>(toolText(result))
        assertTrue("Payload must report success", payload.success)
        assertEquals(listOf("oi-src/app/ImportUser.java"), payload.affectedFiles)
        assertEquals("Optimized imports in oi-src/app/ImportUser.java", payload.message)

        assertFileDoesNotContain("oi-src/app/ImportUser.java", "import deps.Dropped;")
        assertFileContains("oi-src/app/ImportUser.java", "import deps.Kept;")
        assertFileContains("oi-src/app/ImportUser.java", "return new Kept().value();")
    }

    /**
     * The tool's documented contract is "does NOT reformat code". Swapping the processor for
     * `ReformatCodeProcessor` would still remove the unused import, so the odd body spacing is
     * what distinguishes the two.
     */
    fun testImportOptimizationLeavesBodyFormattingUntouched() = runBlocking {
        registerSourceRoot("oi-noformat-src")
        writeDependencies("oi-noformat-src")
        writeProjectFile(
            "oi-noformat-src/app/SloppyFormatting.java", """
            package app;

            import deps.Dropped;
            import deps.Kept;

            public class SloppyFormatting {
                public String describe() {
                        return    new Kept().value()   ;
                }
            }
        """.trimIndent()
        )

        val result = OptimizeImportsTool().execute(project, buildJsonObject {
            put("file", "oi-noformat-src/app/SloppyFormatting.java")
        })

        assertToolSucceeded("optimize_imports should succeed", result)
        assertFileDoesNotContain("oi-noformat-src/app/SloppyFormatting.java", "import deps.Dropped;")
        assertFileContains("oi-noformat-src/app/SloppyFormatting.java", "        return    new Kept().value()   ;")
    }

    fun testMissingFileIsReportedAsAnError() = runBlocking {
        val result = OptimizeImportsTool().execute(project, buildJsonObject {
            put("file", "oi-src/app/DoesNotExist.java")
        })

        assertToolFailed("A file that does not exist cannot be optimized", result)
        assertEquals("File not found: oi-src/app/DoesNotExist.java", toolText(result))
    }
}
