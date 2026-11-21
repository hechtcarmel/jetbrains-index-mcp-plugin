package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindClassResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `ide_find_class` for Kotlin declarations that are not `KtClassOrObject`.
 *
 * - File facades — the JVM class holding a file's top-level declarations (`TextToolsKt`, or the
 *   name chosen with `@file:JvmName`) — are what Java code imports and calls, but the Kotlin
 *   Go-to-Class contributor does not list them, so the tool found nothing. They are now returned
 *   with kind `FILE_FACADE`, pointing at the Kotlin file.
 * - A type alias was reported with kind `CLASS`; it is now `TYPE_ALIAS`.
 */
class KotlinFindClassKindBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("Real Kotlin runtime required", PluginDetectors.kotlin.isAvailable)
        registerSourceRoot("class-kind-kt-src")
        writeProjectFile("class-kind-kt-src/kindkt/TextTools.kt", """
            package kindkt

            fun shout(text: String): String = text + "!"
        """.trimIndent())
        writeProjectFile("class-kind-kt-src/kindkt/MathTools.kt", """
            @file:JvmName("KindMath")

            package kindkt

            fun twice(x: Int): Int = x * 2
        """.trimIndent())
        writeProjectFile("class-kind-kt-src/kindkt/Aliases.kt", """
            package kindkt

            typealias KindNames = List<String>
        """.trimIndent())
    }

    private fun exact(name: String, language: String? = null): List<SymbolMatch> = runBlocking {
        val result = FindClassTool().execute(project, buildJsonObject {
            put("query", name)
            put("matchMode", "exact")
            language?.let { put("language", it) }
        })
        assertToolSucceeded("ide_find_class '$name'", result)
        json.decodeFromString<FindClassResult>(toolText(result)).classes
    }

    fun testDefaultFileFacadeIsFound() {
        val match = exact("TextToolsKt").single()
        assertEquals("kindkt.TextToolsKt", match.qualifiedName)
        assertEquals("FILE_FACADE", match.kind)
        assertEquals("class-kind-kt-src/kindkt/TextTools.kt", match.file)
        assertEquals("Kotlin", match.language)
    }

    fun testJvmNameFileFacadeIsFound() {
        val match = exact("KindMath").single()
        assertEquals("kindkt.KindMath", match.qualifiedName)
        assertEquals("FILE_FACADE", match.kind)
        assertEquals("class-kind-kt-src/kindkt/MathTools.kt", match.file)
    }

    fun testFileFacadeRespectsLanguageFilter() {
        assertEquals(listOf("kindkt.KindMath"), exact("KindMath", language = "Kotlin").map { it.qualifiedName })
        assertEquals(emptyList<SymbolMatch>(), exact("KindMath", language = "Java"))
    }

    fun testTypeAliasKind() {
        assertEquals("TYPE_ALIAS", exact("KindNames").single().kind)
    }
}
