package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import com.intellij.platform.ide.progress.ModalTaskOwner.project
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FindUsagesRecordComponentBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
    }

    private fun writeRecordFixture() {
        registerSourceRoot("record-src")
        writeProjectFile(
            "record-src/example/Config.java",
            //language=java
            """
            package example;

            public record Config(String name, int timeout) {}
            """.trimIndent()
        )
        writeProjectFile(
            "record-src/example/Service.java",
            //language=java
            """
            package example;

            public class Service {
                public void run(Config config) {
                    config.name();
                    config.name();
                }
            }
            """.trimIndent()
        )
    }

    fun testRecordComponentPositionBasedFindsAccessorCallSites() =
        // Column 29 lands on `name` in `public record Config(String name, int timeout) {}`
        testRecordComponentSymbol(buildJsonObject {
            put("file", "record-src/example/Config.java")
            put("line", 3)
            put("column", 29)
        })

    fun testRecordComponentSymbolBasedFindsAccessorCallSites() =
        testRecordComponentSymbol(buildJsonObject {
            put("language", "Java")
            put("symbol", "example.Config#name")
        })

    private fun testRecordComponentSymbol(args: JsonObject) = runBlocking {
        writeRecordFixture()

        val result = FindUsagesTool().execute(project, args)
        assertToolSucceeded("find_references (symbol) on Java record component", result)
        val parsed = json.decodeFromString<FindUsagesResult>(toolText(result))

        assertEquals(
            "Accessor call sites must be returned; got: ${parsed.usages}",
            2, parsed.totalCount
        )
        assertEquals(
            "kind must be record component for symbol-based lookup after fix",
            "record component",
            parsed.resolvedSymbol?.kind
        )
        assertTrue(parsed.usages.all { it.file.endsWith("Service.java") })
    }
}
