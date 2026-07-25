package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ListTestsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testIntegration.TestFramework
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Behavior coverage for `ide_list_tests`.
 *
 * Before this file the tool had zero references anywhere in `src/test` — no schema assertion, no
 * registration assertion, nothing. It could have been deleted outright and the suite would have
 * stayed green.
 *
 * It is also the only tool whose correctness depends on a *foreign* extension point: it reports
 * whatever `com.intellij.testFramework` extensions claim is a test, rather than pattern-matching
 * method names itself. So the fixture stands up real JUnit3 and JUnit4 classes and checks the tool
 * agrees with the IDE — including the cases where a method *looks* like a test and is not one.
 */
class ListTestsToolBehaviorTest : McpPlatformTestCase() {

    private companion object {
        const val TEST_SOURCES = "src/test/java"
        const val MAIN_SOURCES = "src/main/java"
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        assertTrue(
            "ide_list_tests reads the com.intellij.testFramework extension point. The Java plugin " +
                "declares that EP but ships no implementations, so without the JUnit plugin on the " +
                "test classpath the tool can only ever answer \"No test frameworks are registered\" " +
                "and nothing below tests anything. If this fails, testBundledPlugin(\"JUnit\") is " +
                "missing from build.gradle.kts. Registered frameworks: " +
                TestFramework.EXTENSION_NAME.extensionList.map { it.name },
            TestFramework.EXTENSION_NAME.extensionList.any { it.name == "JUnit4" }
        )

        addSourceRoot(TEST_SOURCES, isTestSource = true)
        // The fixture runs without a JDK or a JUnit jar, so `@Test` and `extends TestCase` would
        // not resolve. Minimal stubs inside the test source root make them resolvable; neither
        // declares a method, so neither shows up as a discovered test.
        writeProjectFile(
            "$TEST_SOURCES/org/junit/Test.java", """
            package org.junit;

            public @interface Test {
            }
            """.trimIndent()
        )
        writeProjectFile(
            "$TEST_SOURCES/junit/framework/TestCase.java", """
            package junit.framework;

            public abstract class TestCase {
            }
            """.trimIndent()
        )
    }

    // ── JUnit4 ──────────────────────────────────────────────────────────────────────────

    /**
     * `testNotAnnotated` is the load-bearing part of this fixture: it is named like a test and is
     * not one. Any drift from "ask the extension point" to "match `test*`" reports it and fails.
     */
    fun testDiscoversAnAnnotatedMethodAndIgnoresOneMerelyNamedLikeATest() = runBlocking {
        val source = """
            package sample;

            import org.junit.Test;

            public class CalculatorTest {
                @Test
                public void testAddsTwoNumbers() {
                }

                public void testNotAnnotated() {
                }

                private void helper() {
                }
            }
        """.trimIndent()
        writeProjectFile("$TEST_SOURCES/sample/CalculatorTest.java", source)

        val entries = listTests(file = "$TEST_SOURCES/sample/CalculatorTest.java")

        assertEquals(
            "Only the @Test-annotated method is a test. Got: ${entries.map { it.methodName }}",
            listOf("testAddsTwoNumbers"),
            entries.map { it.methodName }
        )
        val entry = entries.single()
        assertEquals("JUnit4", entry.framework)
        assertEquals(
            "className must be fully qualified — ide_run_tests takes it verbatim",
            "sample.CalculatorTest",
            entry.className
        )
        assertEquals("sample.CalculatorTest.testAddsTwoNumbers", entry.displayName)
        assertEquals("$TEST_SOURCES/sample/CalculatorTest.java", entry.file)
        assertEquals(
            "line must point at the test method, not the class declaration",
            lineOf(source, "public void testAddsTwoNumbers"),
            entry.line
        )
    }

    /**
     * The framework name is read off the extension that claimed the method, so a JUnit3 class must
     * report `JUnit3`. A hardcoded `"JUnit4"` — or a framework list consulted in the wrong order —
     * fails here while the JUnit4 test above still passes.
     */
    fun testReportsTheFrameworkThatActuallyClaimedEachMethod() = runBlocking {
        writeProjectFile(
            "$TEST_SOURCES/sample/LegacyTest.java", """
            package sample;

            import junit.framework.TestCase;

            public class LegacyTest extends TestCase {
                public void testLegacyStyle() {
                }
            }
            """.trimIndent()
        )
        writeProjectFile(
            "$TEST_SOURCES/sample/ModernTest.java", """
            package sample;

            public class ModernTest {
                @org.junit.Test
                public void testModernStyle() {
                }
            }
            """.trimIndent()
        )

        val byMethod = listTests().associate { it.methodName to it.framework }

        assertEquals(
            "A class extending junit.framework.TestCase is claimed by JUnit3. Got: $byMethod",
            "JUnit3",
            byMethod["testLegacyStyle"]
        )
        assertEquals(
            "An @org.junit.Test method is claimed by JUnit4. Got: $byMethod",
            "JUnit4",
            byMethod["testModernStyle"]
        )
    }

    // ── Scope ───────────────────────────────────────────────────────────────────────────

    fun testFileParameterRestrictsTheScanToThatFileOnly() = runBlocking {
        writeTwoTestClasses()

        val entries = listTests(file = "$TEST_SOURCES/sample/AlphaTest.java")

        assertEquals(
            "The file parameter must narrow the scan. Got: ${entries.map { it.displayName }}",
            listOf("sample.AlphaTest.testAlpha"),
            entries.map { it.displayName }
        )
    }

    fun testWholeProjectScanCoversEveryTestSourceFile() = runBlocking {
        writeTwoTestClasses()

        val displayNames = listTests().map { it.displayName }.sorted()

        assertEquals(
            listOf("sample.AlphaTest.testAlpha", "sample.BetaTest.testBeta"),
            displayNames
        )
    }

    /**
     * The complement of the scan test above: a test class sitting in production sources is not
     * listed.
     *
     * Note what enforces that, because it is not what it looks like. `JUnitUtil` refuses any class
     * that is not under test sources, so all four frameworks answer `isTestMethod = false` here on
     * their own — verified by probe. `ListTestsTool`'s own `isInTestSourceContent` filter is
     * therefore a scan-cost optimization, not the thing keeping this result empty, and removing it
     * does *not* fail this test. What does fail it is the tool growing its own name-based
     * heuristic instead of asking the extension point, which is the regression worth catching.
     */
    fun testWholeProjectScanSkipsTestClassesLivingInProductionSources() = runBlocking {
        addSourceRoot(MAIN_SOURCES, isTestSource = false)
        writeProjectFile(
            "$MAIN_SOURCES/sample/MisplacedTest.java", """
            package sample;

            public class MisplacedTest {
                @org.junit.Test
                public void testInProductionSources() {
                }
            }
            """.trimIndent()
        )
        writeProjectFile(
            "$TEST_SOURCES/sample/AlphaTest.java", """
            package sample;

            public class AlphaTest {
                @org.junit.Test
                public void testAlpha() {
                }
            }
            """.trimIndent()
        )

        val displayNames = listTests().map { it.displayName }

        assertEquals(
            "Only test-source content is scanned. Got: $displayNames",
            listOf("sample.AlphaTest.testAlpha"),
            displayNames
        )
    }

    // ── Contract edges ──────────────────────────────────────────────────────────────────

    /**
     * An unresolvable `file` must be an error. Reporting an empty list instead would tell an agent
     * "this file has no tests" about a file that does not exist.
     */
    fun testUnresolvableFileIsAnErrorNotAnEmptyList() = runBlocking {
        val result = ListTestsTool().execute(project, buildJsonObject {
            put("file", "$TEST_SOURCES/sample/DoesNotExist.java")
        })

        assertToolFailed("A missing file must not be reported as 'no tests here'", result)
        assertTrue(
            "The error must name the path that could not be resolved. Got: ${toolText(result)}",
            toolText(result).contains("$TEST_SOURCES/sample/DoesNotExist.java")
        )
    }

    fun testFileWithNoTestsIsAnEmptySuccessNotAnError() = runBlocking {
        writeProjectFile(
            "$TEST_SOURCES/sample/PlainHelper.java", """
            package sample;

            public class PlainHelper {
                public void notATest() {
                }
            }
            """.trimIndent()
        )

        val result = ListTestsTool().execute(project, buildJsonObject {
            put("file", "$TEST_SOURCES/sample/PlainHelper.java")
        })

        assertToolSucceeded("A file without tests is a legitimate empty answer", result)
        val payload = decode(result)
        assertEquals(emptyList<TestEntry>(), payload.tests)
        assertEquals(0, payload.count)
        assertFalse("An empty result is not a truncated one", payload.truncated)
    }

    /**
     * Truncation has to be visible: an agent that reads 500 of 501 tests and believes it has them
     * all will silently skip one. `truncated` is asserted false everywhere else in this class, so
     * both directions of the flag are covered.
     */
    fun testExceedingTheCapTruncatesAndSaysSo() = runBlocking {
        val cap = ListTestsTool.MAX_TESTS
        val methods = (1..cap + 1).joinToString("\n") {
            "    @org.junit.Test\n    public void testGenerated$it() {\n    }\n"
        }
        writeProjectFile(
            "$TEST_SOURCES/sample/BulkTest.java",
            "package sample;\n\npublic class BulkTest {\n$methods}\n"
        )

        val payload = decode(
            ListTestsTool().execute(project, buildJsonObject {
                put("file", "$TEST_SOURCES/sample/BulkTest.java")
            })
        )

        assertTrue("${cap + 1} discovered tests must be reported as truncated", payload.truncated)
        assertEquals("The page must be capped at MAX_TESTS", cap, payload.count)
        assertEquals("count must agree with the number of entries returned", cap, payload.tests.size)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────

    private fun decode(result: ToolCallResult): ListTestsResult = json.decodeFromString(toolText(result))

    private suspend fun listTests(file: String? = null): List<TestEntry> {
        val result = ListTestsTool().execute(project, buildJsonObject {
            file?.let { put("file", it) }
        })
        assertToolSucceeded("list_tests should succeed", result)
        val payload = decode(result)
        assertEquals("count must agree with the number of entries returned", payload.tests.size, payload.count)
        return payload.tests
    }

    private fun writeTwoTestClasses() {
        writeProjectFile(
            "$TEST_SOURCES/sample/AlphaTest.java", """
            package sample;

            public class AlphaTest {
                @org.junit.Test
                public void testAlpha() {
                }
            }
            """.trimIndent()
        )
        writeProjectFile(
            "$TEST_SOURCES/sample/BetaTest.java", """
            package sample;

            public class BetaTest {
                @org.junit.Test
                public void testBeta() {
                }
            }
            """.trimIndent()
        )
    }

    /**
     * `McpPlatformTestCase.registerSourceRoot` only registers production roots, and
     * `ide_list_tests` filters on `isInTestSourceContent`, so the root type has to be selectable.
     * Idempotent because the light fixture's module is shared across the methods of this class.
     */
    private fun addSourceRoot(relativePath: String, isTestSource: Boolean) {
        val basePath = requireNotNull(project.basePath) { "Project base path is null" }
        val path = Path.of(basePath, relativePath)
        Files.createDirectories(path)
        val root = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())) {
            "Failed to refresh VFS for source root $path"
        }
        if (ModuleRootManager.getInstance(module).sourceRoots.none { it.path == root.path }) {
            PsiTestUtil.addSourceRoot(module, root, isTestSource)
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun lineOf(source: String, needle: String): Int =
        source.lineSequence().indexOfFirst { it.contains(needle) } + 1
}
