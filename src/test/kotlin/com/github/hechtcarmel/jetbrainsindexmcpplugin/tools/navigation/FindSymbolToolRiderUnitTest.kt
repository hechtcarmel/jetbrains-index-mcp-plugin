package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import junit.framework.TestCase
import kotlinx.serialization.json.jsonObject
import java.io.File

class FindSymbolToolRiderUnitTest : TestCase() {

    fun testFindSymbolToolContractsCanonicalCSharpAliasNormalization() {
        val source = findSymbolSource()

        assertContains(
            source,
            "C#",
            "FindSymbolTool should explicitly recognize the canonical C# alias"
        )
        assertContains(
            source,
            "CSharp",
            "FindSymbolTool should explicitly recognize the CSharp alias form"
        )
        assertContains(
            source,
            "CSHARP",
            "FindSymbolTool should explicitly recognize the uppercase CSHARP alias form"
        )
    }

    fun testFindSymbolToolContractsRiderBackendLaneForAcceptedCSharpAliases() {
        val source = findSymbolSource()

        assertContains(
            source,
            "RiderBackendSemanticService.findSymbols(",
            "Accepted C# aliases should route ide_find_symbol through the Rider backend symbol lane"
        )
    }

    fun testFindSymbolToolPreservesGenericPopupSearchForNonCSharpRequests() {
        val source = findSymbolSource()

        assertContains(
            source,
            "OptimizedSymbolSearch.search(",
            "Language-agnostic and non-C# requests should keep the generic popup-backed symbol search fallback"
        )
    }

    fun testFindSymbolToolSchemaKeepsLanguageFilterForCrossLanguageFallbacks() {
        val properties = FindSymbolTool().inputSchema[SchemaConstants.PROPERTIES]?.jsonObject

        assertNotNull("FindSymbolTool schema should keep the language filter parameter", properties?.get(ParamNames.LANGUAGE))
    }

    private fun findSymbolSource(): String {
        return File("src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/navigation/FindSymbolTool.kt").readText()
    }

    private fun assertContains(source: String, needle: String, message: String) {
        assertTrue(message, source.contains(needle))
    }
}
