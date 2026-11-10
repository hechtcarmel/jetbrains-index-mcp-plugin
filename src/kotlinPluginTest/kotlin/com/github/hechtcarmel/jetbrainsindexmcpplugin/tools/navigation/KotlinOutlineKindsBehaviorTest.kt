package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileStructureResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.application.ReadAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Run with `-PkotlinPluginTests=true`; the default test task intentionally excludes this source set.
 * Uses real Kotlin PSI without compile-time Kotlin plugin types.
 */
class KotlinOutlineKindsBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("The opt-in runtime must load real Kotlin PSI", PluginDetectors.kotlin.isAvailable)
        SymbolIdRegistry.getInstance().resetSession()
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("outline-review-kotlin")
    }

    override fun tearDown() {
        try {
            SymbolIdRegistry.getInstance().resetSession()
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testSemanticKotlinKindsRetainAllDeclarations() = runBlocking {
        val file = "outline-review-kotlin/Kinds.kt"
        writeProjectFile(file, """
            abstract class AbstractThing
            sealed class SealedThing
            interface Contract
            annotation class Tag
            enum class Choice { FIRST }
            object Singleton
            class Ordinary
        """.trimIndent())
        val result = FileStructureTool().execute(project, buildJsonObject {
            put("file", file)
            put("includeSymbolIds", true)
        })
        assertToolSucceeded("extract Kotlin kinds", result)
        val payload = json.decodeFromString<FileStructureResult>(toolText(result))
        assertEquals(
            listOf("CLASS", "CLASS", "INTERFACE", "ANNOTATION", "ENUM", "OBJECT", "CLASS"),
            payload.nodes.map { it.kind.name }
        )
        ReadAction.run<RuntimeException> {
            payload.nodes.forEach {
                val psi = SymbolIdRegistry.getInstance().resolve(project, requireNotNull(it.symbolId)).getOrThrow()
                assertEquals(it.name, psi.javaClass.getMethod("getName").invoke(psi))
            }
        }
    }

}
