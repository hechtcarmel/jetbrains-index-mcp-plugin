package com.github.hechtcarmel.jetbrainsindexmcpplugin.contract

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import junit.framework.TestCase
import java.io.File

/**
 * Golden snapshot of the MCP tool INPUT surface: each registered tool's name, description and
 * complete input schema.
 *
 * This is the regression net for large refactors. A single assertion covers every registered
 * tool, every schema property, every type, every enum and every `required` array — so deleting a
 * `register(...)` call, renaming a parameter, flipping a type, or emptying a description all
 * fail here rather than silently shipping.
 *
 * Two limits worth knowing before trusting it:
 * - It covers the tools that register headlessly — currently 47 of the 50 in [ToolNames.ALL].
 *   The three in [NOT_REGISTRABLE_HEADLESS] are guarded by set-equality in
 *   [testEveryDeclaredToolNameIsRegistered] instead, so they cannot vanish unnoticed, but their
 *   schemas are not snapshotted.
 * - It covers INPUTS only. Response shapes are pinned separately by
 *   [ResultShapeContractUnitTest].
 *
 * The manifest is a *contract with MCP clients*. Changing it is sometimes correct, but it must
 * always be deliberate: regenerate with
 *
 * ```
 * ./gradlew test -Ptier=unit --tests "*ToolManifestContractUnitTest" -Dcontract.update=true
 * ```
 *
 * and review the resulting diff as part of the change.
 */
class ToolManifestContractUnitTest : TestCase() {

    private companion object {
        const val GOLDEN_RESOURCE = "contract/tool-manifest.json"
        const val GOLDEN_SOURCE_PATH = "src/test/resources/contract/tool-manifest.json"
        const val UPDATE_PROPERTY = "contract.update"

        /**
         * Languages pinned for schema generation.
         *
         * [com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder.languageAndSymbol]
         * reads the global [LanguageHandlerRegistry] while building the schema, and `inputSchema`
         * is a `val`, so a tool's schema is frozen to whatever the registry held when the tool was
         * constructed. Without pinning, the manifest would depend on which test class ran first.
         */
        val PINNED_LANGUAGES = listOf("Java", "Kotlin", "JavaScript", "TypeScript")

        /**
         * Tools in [ToolNames.ALL] that legitimately do not register in the test JVM because
         * their guarding plugin is absent from the test platform.
         *
         * `ide_convert_java_to_kotlin` needs the Kotlin plugin; `ide_import_modules` and
         * `ide_open_workspace` need the Maven plugin. Neither is on the test classpath.
         *
         * Keeping this list explicit means a tool that silently stops registering for any *other*
         * reason still fails the build.
         */
        val NOT_REGISTRABLE_HEADLESS = setOf(
            ToolNames.CONVERT_JAVA_TO_KOTLIN,
            ToolNames.IMPORT_MODULES,
            ToolNames.OPEN_WORKSPACE,
        )
    }

    override fun setUp() {
        super.setUp()
        mockkObject(LanguageHandlerRegistry)
        every { LanguageHandlerRegistry.getSupportedLanguageNamesForSymbolReference() } returns PINNED_LANGUAGES
    }

    override fun tearDown() {
        try {
            unmockkObject(LanguageHandlerRegistry)
        } finally {
            super.tearDown()
        }
    }

    fun testToolManifestMatchesGoldenSnapshot() {
        val actual = renderManifest()

        if (System.getProperty(UPDATE_PROPERTY) == "true") {
            val target = File(GOLDEN_SOURCE_PATH)
            target.parentFile.mkdirs()
            target.writeText(actual)
            fail(
                "Golden manifest regenerated at $GOLDEN_SOURCE_PATH. " +
                    "Review the diff, then re-run without -D$UPDATE_PROPERTY."
            )
        }

        val expected = readGolden()
        if (expected == actual) return

        val actualFile = File("build/tool-manifest-actual.json")
        actualFile.parentFile.mkdirs()
        actualFile.writeText(actual)

        fail(
            buildString {
                appendLine("The MCP tool manifest changed.")
                appendLine()
                appendLine(describeDifference(expected, actual))
                appendLine()
                appendLine("If this change is intended, regenerate the golden file:")
                appendLine("  ./gradlew test -Ptier=unit --tests \"*ToolManifestContractUnitTest\" -Dcontract.update=true")
                appendLine("Actual manifest written to: ${actualFile.path}")
            }
        )
    }

    /**
     * Guards the manifest against a tool that disappears from the registry *and* from the
     * golden file in the same commit, which would otherwise leave the snapshot self-consistent
     * and green.
     */
    fun testEveryDeclaredToolNameIsRegistered() {
        val registered = buildRegistry().getAllTools().map { it.name }.toSet()
        val expected = ToolNames.ALL.toSet() - NOT_REGISTRABLE_HEADLESS

        val missing = (expected - registered).sorted()
        val unexpected = (registered - ToolNames.ALL.toSet()).sorted()

        assertEquals(
            "Tools declared in ToolNames.ALL but not registered by registerBuiltInTools(). " +
                "Either register them, or add them to NOT_REGISTRABLE_HEADLESS with a reason.",
            emptyList<String>(),
            missing
        )
        assertEquals(
            "Tools registered but absent from ToolNames.ALL. Add them to ToolNames.ALL.",
            emptyList<String>(),
            unexpected
        )
    }

    fun testAllowlistedToolsAreGenuinelyAbsentNotSilentlyRegistered() {
        val registered = buildRegistry().getAllTools().map { it.name }.toSet()
        val stale = NOT_REGISTRABLE_HEADLESS.filter { it in registered }.sorted()

        assertEquals(
            "These tools are on NOT_REGISTRABLE_HEADLESS but DO register in tests. " +
                "Remove them from the allowlist so they are covered by the manifest.",
            emptyList<String>(),
            stale
        )
    }

    private fun buildRegistry(): ToolRegistry = ToolRegistry().apply { registerBuiltInTools() }

    /**
     * Renders the manifest as stable, human-diffable text.
     *
     * Deliberately not JSON: a line-oriented format means a review diff shows exactly which
     * tool and which schema line changed, instead of one reflowed blob.
     */
    private fun renderManifest(): String {
        val tools = buildRegistry().getAllTools().sortedBy { it.name }
        return buildString {
            appendLine("# MCP tool manifest — golden snapshot")
            appendLine("# Regenerate: ./gradlew test -Ptier=unit --tests \"*ToolManifestContractUnitTest\" -Dcontract.update=true")
            appendLine("# tools: ${tools.size}")
            tools.forEach { tool ->
                appendLine()
                appendLine("## ${tool.name}")
                appendLine("description:")
                tool.description.trim().lines().forEach { appendLine("  $it") }
                appendLine("schema:")
                appendLine(canonicalJson(tool.inputSchema, indent = "  "))
            }
        }
    }

    private fun readGolden(): String {
        val stream = javaClass.classLoader.getResourceAsStream(GOLDEN_RESOURCE)
            ?: fail(
                "Golden manifest not found on the test classpath ($GOLDEN_RESOURCE). " +
                    "Generate it with -D$UPDATE_PROPERTY=true."
            ).let { error("unreachable") }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun describeDifference(expected: String, actual: String): String {
        val expectedLines = expected.lines()
        val actualLines = actual.lines()
        val firstDiff = expectedLines.zip(actualLines).indexOfFirst { (e, a) -> e != a }

        val expectedTools = toolNamesIn(expected)
        val actualTools = toolNamesIn(actual)
        val removed = (expectedTools - actualTools).sorted()
        val added = (actualTools - expectedTools).sorted()

        return buildString {
            if (removed.isNotEmpty()) appendLine("REMOVED tools: ${removed.joinToString(", ")}")
            if (added.isNotEmpty()) appendLine("ADDED tools:   ${added.joinToString(", ")}")
            if (firstDiff >= 0) {
                appendLine("First differing line (${firstDiff + 1}):")
                appendLine("  expected: ${expectedLines[firstDiff]}")
                appendLine("  actual:   ${actualLines[firstDiff]}")
            } else if (expectedLines.size != actualLines.size) {
                appendLine("Line count differs: expected ${expectedLines.size}, actual ${actualLines.size}")
            }
        }
    }

    private fun toolNamesIn(manifest: String): Set<String> =
        manifest.lines().filter { it.startsWith("## ") }.map { it.removePrefix("## ") }.toSet()

    /**
     * Serializes JSON with object keys sorted, so the snapshot depends on schema content rather
     * than on map iteration order.
     */
    private fun canonicalJson(element: kotlinx.serialization.json.JsonElement, indent: String): String =
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                if (element.isEmpty()) "$indent{}" else buildString {
                    appendLine("$indent{")
                    element.entries.sortedBy { it.key }.forEachIndexed { i, (key, value) ->
                        val rendered = canonicalJson(value, "$indent  ")
                        val comma = if (i == element.size - 1) "" else ","
                        if (rendered.trimStart().startsWith("{") || rendered.trimStart().startsWith("[")) {
                            appendLine("$indent  ${quote(key)}:")
                            appendLine("$rendered$comma")
                        } else {
                            appendLine("$indent  ${quote(key)}: ${rendered.trimStart()}$comma")
                        }
                    }
                    append("$indent}")
                }
            }

            is kotlinx.serialization.json.JsonArray -> {
                if (element.isEmpty()) "$indent[]" else buildString {
                    appendLine("$indent[")
                    element.forEachIndexed { i, value ->
                        val comma = if (i == element.size - 1) "" else ","
                        appendLine("${canonicalJson(value, "$indent  ")}$comma")
                    }
                    append("$indent]")
                }
            }

            else -> "$indent$element"
        }

    private fun quote(value: String) = "\"$value\""
}
