package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Callers of a recursive method include the method itself, as in the IDE's Call Hierarchy.
 *
 * The handler dropped every caller equal to the searched method, so `depth(node.left)` inside
 * `depth` disappeared. The exclusion still has to hold for a `super.greet()` call inside an
 * override: that call targets the super method, so the override is not its own caller.
 */
class CallHierarchyRecursionBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("recursion-src")
        writeProjectFile(
            "recursion-src/recursion/Tree.java",
            //language=java
            """
            package recursion;

            public class Tree {
                Tree left;
                Tree right;

                static int depth(Tree node) {
                    if (node == null) return 0;
                    return 1 + depth(node.left) + depth(node.right);
                }

                static int report(Tree root) {
                    return depth(root);
                }
            }
            """.trimIndent()
        )
        writeProjectFile(
            "recursion-src/recursion/Greeter.java",
            //language=java
            """
            package recursion;

            public class Greeter {
                public void greet() {
                }
            }
            """.trimIndent()
        )
        writeProjectFile(
            "recursion-src/recursion/LoudGreeter.java",
            //language=java
            """
            package recursion;

            public class LoudGreeter extends Greeter {
                @Override
                public void greet() {
                    super.greet();
                }
            }
            """.trimIndent()
        )
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    private fun callers(symbol: String): Set<String> = runBlocking {
        val result = CallHierarchyTool().execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", symbol)
            put("direction", "callers")
            put("depth", 1)
        })
        assertToolSucceeded("callers of $symbol", result)
        json.decodeFromString<CallHierarchyResult>(toolText(result)).calls.map { it.name }.toSet()
    }

    fun testRecursiveMethodIsReportedAmongItsCallers() {
        assertEquals(
            setOf("Tree.depth(Tree)", "Tree.report(Tree)"),
            callers("recursion.Tree#depth(recursion.Tree)")
        )
    }

    fun testSuperCallFromOverrideIsNotARecursiveCall() {
        assertEquals(
            "super.greet() calls Greeter.greet(), not LoudGreeter.greet()",
            emptySet<String>(),
            callers("recursion.LoudGreeter#greet()")
        )
        assertEquals(setOf("LoudGreeter.greet()"), callers("recursion.Greeter#greet()"))
    }
}
