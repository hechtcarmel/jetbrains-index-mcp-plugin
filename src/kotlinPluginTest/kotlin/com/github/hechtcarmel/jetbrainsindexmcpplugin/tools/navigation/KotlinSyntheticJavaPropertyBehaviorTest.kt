package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Kotlin reads a Java getter with property syntax: `account.owner` calls `Account.getOwner()`.
 *
 * - `ide_find_references` on `getOwner()` returned nothing: plain `ReferencesSearch` looks for the
 *   text `getOwner`, and the Kotlin plugin reports synthetic-property uses only through
 *   `MethodReferencesSearch`, the query the IDE's Find Usages runs.
 * - `ide_call_hierarchy` callees of a Kotlin function omitted the getter, because only
 *   `KtCallExpression`s were inspected and a property access is a name reference.
 */
class KotlinSyntheticJavaPropertyBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("Real Kotlin runtime required", PluginDetectors.kotlin.isAvailable)
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("synthetic-prop-src")
        writeProjectFile("synthetic-prop-src/syntheticprop/Account.java", """
            package syntheticprop;

            public class Account {
                private final String owner;

                public Account(String owner) {
                    this.owner = owner;
                }

                public String getOwner() {
                    return owner;
                }
            }
        """.trimIndent())
        writeProjectFile("synthetic-prop-src/syntheticprop/Statement.kt", """
            package syntheticprop

            class Statement {
                fun header(account: Account): String {
                    val owner = account.owner
                    return owner
                }
            }
        """.trimIndent())
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    fun testPositionOnJavaGetterFindsKotlinPropertySyntaxUse() =
        // Line 10, column 19: `getOwner` in `    public String getOwner() {`
        assertGetterUsedFromKotlin(buildJsonObject {
            put("file", "synthetic-prop-src/syntheticprop/Account.java")
            put("line", 10)
            put("column", 19)
        })

    fun testQualifiedJavaGetterFindsKotlinPropertySyntaxUse() =
        assertGetterUsedFromKotlin(buildJsonObject {
            put("language", "Java")
            put("symbol", "syntheticprop.Account#getOwner()")
        })

    private fun assertGetterUsedFromKotlin(args: JsonObject) = runBlocking {
        val result = FindUsagesTool().execute(project, args)
        assertToolSucceeded("find_references on Account.getOwner()", result)
        val parsed = json.decodeFromString<FindUsagesResult>(toolText(result))

        assertEquals("getOwner", parsed.resolvedSymbol?.name)
        assertEquals(
            "`account.owner` in Statement.kt is a use of getOwner(); got: ${parsed.usages}",
            listOf("synthetic-prop-src/syntheticprop/Statement.kt:5"),
            parsed.usages.map { "${it.file}:${it.line}" }
        )
    }

    fun testKotlinCalleesIncludeJavaGetterReadAsProperty() = runBlocking {
        // Line 4, column 9: `header` in `    fun header(account: Account): String {`
        val result = CallHierarchyTool().execute(project, buildJsonObject {
            put("file", "synthetic-prop-src/syntheticprop/Statement.kt")
            put("line", 4)
            put("column", 9)
            put("direction", "callees")
            put("depth", 1)
        })
        assertToolSucceeded("callees of Statement.header", result)
        val parsed = json.decodeFromString<CallHierarchyResult>(toolText(result))

        assertEquals(listOf("Account.getOwner()"), parsed.calls.map { it.name })
    }
}
