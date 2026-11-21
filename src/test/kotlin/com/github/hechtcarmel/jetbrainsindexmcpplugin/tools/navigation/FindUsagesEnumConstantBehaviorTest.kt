package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `ide_find_references` with the position on a Java enum constant's name.
 *
 * `PsiEnumConstant` has its own reference, to the enum's implicit constructor. Position
 * resolution used to walk up to that reference, so the search ran for the constructor and
 * returned the constant declarations (`RED`, `GREEN`) instead of the uses of `RED`. The
 * qualified-name path never had the bug; both are pinned so they stay in agreement.
 */
class FindUsagesEnumConstantBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("enum-const-src")
        writeProjectFile(
            "enum-const-src/enumconst/Signal.java",
            //language=java
            """
            package enumconst;

            public enum Signal {
                RED,
                GREEN;

                public Signal next() {
                    return this == RED ? GREEN : RED;
                }
            }
            """.trimIndent()
        )
        writeProjectFile(
            "enum-const-src/enumconst/Crossing.java",
            //language=java
            """
            package enumconst;

            public class Crossing {
                boolean stop(Signal signal) {
                    return signal == Signal.RED;
                }
            }
            """.trimIndent()
        )
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    fun testPositionOnEnumConstantFindsUsesOfTheConstant() =
        // Line 4, column 5: `RED` in `    RED,`
        assertUsesOfRed(buildJsonObject {
            put("file", "enum-const-src/enumconst/Signal.java")
            put("line", 4)
            put("column", 5)
        })

    fun testQualifiedEnumConstantFindsTheSameUses() =
        assertUsesOfRed(buildJsonObject {
            put("language", "Java")
            put("symbol", "enumconst.Signal#RED")
        })

    private fun assertUsesOfRed(args: JsonObject) = runBlocking {
        val result = FindUsagesTool().execute(project, args)
        assertToolSucceeded("find_references on enum constant RED", result)
        val parsed = json.decodeFromString<FindUsagesResult>(toolText(result))

        assertEquals("The searched symbol must be the constant", "RED", parsed.resolvedSymbol?.name)
        assertEquals("enum constant", parsed.resolvedSymbol?.kind)
        assertEquals(
            "Both uses in Signal.next() and the use in Crossing; got: ${parsed.usages}",
            listOf("Crossing.java:5", "Signal.java:8", "Signal.java:8"),
            parsed.usages.map { "${it.file.substringAfterLast('/')}:${it.line}" }.sorted()
        )
    }
}
