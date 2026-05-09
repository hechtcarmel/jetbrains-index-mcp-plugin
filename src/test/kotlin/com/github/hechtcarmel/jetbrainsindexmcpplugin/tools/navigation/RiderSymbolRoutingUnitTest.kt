package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import junit.framework.TestCase
import java.io.File

class RiderSymbolRoutingUnitTest : TestCase() {

    fun testDefinitionUsesRiderBackendForCSharpAndFSharpSymbolMode() {
        val source = navigationSource("FindDefinitionTool.kt")

        assertContainsForRiderLanguages(FindDefinitionTool().description, "FindDefinitionTool description")
        assertContains(
            source,
            "RiderBackendSemanticService.findDefinition(",
            "FindDefinitionTool should call the Rider backend-native definition path"
        )
        assertContains(
            source,
            "language = optionalStringArg(arguments, ParamNames.LANGUAGE)",
            "FindDefinitionTool should normalize blank requested language before calling the Rider backend-native definition path"
        )
        assertContains(
            source,
            "symbol = optionalStringArg(arguments, ParamNames.SYMBOL)",
            "FindDefinitionTool should normalize blank requested symbol before calling the Rider backend-native definition path"
        )
        assertContains(
            source,
            "line = optionalIntArg(arguments, ParamNames.LINE)",
            "FindDefinitionTool should normalize blank numeric position arguments before calling the Rider backend-native definition path"
        )
        assertFalse(
            "FindDefinitionTool should not reserve Rider backend definition lookup for position-mode only; that silently falls back for Rider C#/F# symbol-mode",
            source.contains("if (resolveLookupMode(arguments) == LookupModeState.POSITION)")
        )
    }

    fun testReferencesUseRiderBackendForCSharpAndFSharpSymbolMode() {
        val source = navigationSource("FindUsagesTool.kt")

        assertContainsForRiderLanguages(FindUsagesTool().description, "FindUsagesTool description")
        assertContains(
            source,
            "RiderBackendSemanticService.findReferences(",
            "FindUsagesTool should call the Rider backend-native references path"
        )
        assertContains(
            source,
            "language = optionalStringArg(arguments, ParamNames.LANGUAGE)",
            "FindUsagesTool should normalize blank requested language before calling the Rider backend-native references path"
        )
        assertContains(
            source,
            "symbol = optionalStringArg(arguments, ParamNames.SYMBOL)",
            "FindUsagesTool should normalize blank requested symbol before calling the Rider backend-native references path"
        )
        assertContains(
            source,
            "line = optionalIntArg(arguments, ParamNames.LINE)",
            "FindUsagesTool should normalize blank numeric position arguments before calling the Rider backend-native references path"
        )
        assertFalse(
            "FindUsagesTool should not reserve Rider backend reference lookup for position-mode only; that silently falls back for Rider C#/F# symbol-mode",
            source.contains("if (resolveLookupMode(arguments) == LookupModeState.POSITION)")
        )
    }

    fun testImplementationsUseBackendNativeRiderSymbolRoutingForCSharpAndFSharp() {
        val source = navigationSource("FindImplementationsTool.kt")

        assertContainsForRiderLanguages(FindImplementationsTool().description, "FindImplementationsTool description")
        assertContains(
            source,
            "optionalStringArg(arguments, ParamNames.LANGUAGE)",
            "FindImplementationsTool should read the requested Rider symbol-mode language instead of trusting resolved PSI language ids"
        )
        assertContains(
            source,
            "optionalStringArg(arguments, ParamNames.SYMBOL)",
            "FindImplementationsTool should read the requested Rider symbol-mode symbol before generic PSI fallback"
        )
        assertBackendNativeRiderSymbolRouting(
            source = source,
            toolName = "FindImplementationsTool",
            fallbackMarker = "val element = resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true)"
        )
    }

    fun testCallHierarchyUsesBackendNativeRiderSymbolRoutingForCSharpAndFSharp() {
        val source = navigationSource("CallHierarchyTool.kt")

        assertContainsForRiderLanguages(CallHierarchyTool().description, "CallHierarchyTool description")
        assertContains(
            source,
            "optionalStringArg(arguments, ParamNames.LANGUAGE)",
            "CallHierarchyTool should read the requested Rider symbol-mode language instead of trusting resolved PSI language ids"
        )
        assertContains(
            source,
            "optionalStringArg(arguments, ParamNames.SYMBOL)",
            "CallHierarchyTool should read the requested Rider symbol-mode symbol before generic PSI fallback"
        )
        assertBackendNativeRiderSymbolRouting(
            source = source,
            toolName = "CallHierarchyTool",
            fallbackMarker = "val element = resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true)"
        )
    }

    fun testCallHierarchyRiderSymbolRoutingIsGatedBySharedLookupMode() {
        val source = navigationSource("CallHierarchyTool.kt")

        assertContains(
            source,
            "val isRiderSymbolMode = resolveLookupMode(arguments) == LookupModeState.SYMBOL",
            "CallHierarchyTool should derive Rider symbol routing from shared lookup-mode resolution so complete position targets win over dual-mode requests"
        )
    }

    fun testSuperMethodsUseBackendNativeRiderSymbolRoutingForCSharpAndFSharp() {
        val source = navigationSource("FindSuperMethodsTool.kt")

        assertContainsForRiderLanguages(FindSuperMethodsTool().description, "FindSuperMethodsTool description")
        assertContains(
            source,
            "optionalStringArg(arguments, ParamNames.LANGUAGE)",
            "FindSuperMethodsTool should read the requested Rider symbol-mode language instead of trusting resolved PSI language ids"
        )
        assertContains(
            source,
            "optionalStringArg(arguments, ParamNames.SYMBOL)",
            "FindSuperMethodsTool should read the requested Rider symbol-mode symbol before generic PSI fallback"
        )
        assertBackendNativeRiderSymbolRouting(
            source = source,
            toolName = "FindSuperMethodsTool",
            fallbackMarker = "val element = resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true)"
        )
    }

    fun testRiderSymbolModeToolsDoNotAdvertiseGenericEmptyLanguageFailures() {
        val expectations = listOf(
            "FindImplementationsTool.kt" to "No implementations handler available for language: ${'$'}{element.language.id}",
            "CallHierarchyTool.kt" to "No call hierarchy handler available for language: ${'$'}{element.language.id}",
            "FindSuperMethodsTool.kt" to "No super methods handler available for language: ${'$'}{element.language.id}"
        )

        expectations.forEach { (fileName, genericFailure) ->
            val source = navigationSource(fileName)
            assertFalse(
                "$fileName should not keep the generic handler failure that can surface Rider symbol-mode as language '.' or blank",
                source.contains(genericFailure)
            )
        }
    }

    fun testRiderSymbolModeUnsupportedMessagesStayDeterministicForPositionMappedTools() {
        val expectations = listOf(
            "FindImplementationsTool.kt" to
                "Rider C#/F# symbol-mode implementations require backend-native symbol resolution and are unsupported for symbol requests the backend cannot map to source positions.",
            "CallHierarchyTool.kt" to
                "Rider C#/F# symbol-mode call hierarchy requires backend-native symbol resolution and is unsupported for symbol requests the backend cannot map to source positions.",
            "FindSuperMethodsTool.kt" to
                "Rider C#/F# symbol-mode super methods require backend-native symbol resolution and are unsupported for symbol requests the backend cannot map to source positions."
        )

        expectations.forEach { (fileName, message) ->
            assertContains(
                navigationSource(fileName),
                message,
                "$fileName should keep a deterministic Rider unsupported message when backend symbol resolution cannot map to a source position"
            )
        }
    }

    fun testRiderDefinitionAndReferencesUnsupportedMessagesStayDeterministic() {
        val expectations = listOf(
            "FindDefinitionTool.kt" to
                "Rider C#/F# symbol-mode definition requires the Rider backend-native path and is unsupported when that backend is unavailable.",
            "FindUsagesTool.kt" to
                "Rider C#/F# symbol-mode references require the Rider backend-native path and are unsupported when that backend is unavailable."
        )

        expectations.forEach { (fileName, message) ->
            assertContains(
                navigationSource(fileName),
                message,
                "$fileName should keep a deterministic Rider unsupported message instead of falling back to generic symbol-mode failures"
            )
        }
    }

    fun testCallHierarchyUsesSharedSymbolResolutionPath() {
        val source = navigationSource("CallHierarchyTool.kt")

        assertFalse(
            "CallHierarchyTool should not keep the Rider rewrite workaround",
            source.contains("rewriteSymbolArgumentsForRider")
        )
        assertTrue(
            "CallHierarchyTool should resolve Rider language+symbol requests through shared argument resolution",
            source.contains("resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true)")
        )
        assertTrue(
            "CallHierarchyTool description should mention SymbolReferenceHandler support",
            CallHierarchyTool().description.contains("SymbolReferenceHandler")
        )
    }

    fun testImplementationsUsesSharedSymbolResolutionPath() {
        val source = navigationSource("FindImplementationsTool.kt")

        assertFalse(
            "FindImplementationsTool should not keep the Rider rewrite workaround",
            source.contains("rewriteSymbolArgumentsForRider")
        )
        assertTrue(
            "FindImplementationsTool should resolve Rider language+symbol requests through shared argument resolution",
            source.contains("resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true)")
        )
        assertTrue(
            "FindImplementationsTool description should mention SymbolReferenceHandler support",
            FindImplementationsTool().description.contains("SymbolReferenceHandler")
        )
    }

    fun testSuperMethodsUsesSharedSymbolResolutionPath() {
        val source = navigationSource("FindSuperMethodsTool.kt")

        assertFalse(
            "FindSuperMethodsTool should not keep the Rider rewrite workaround",
            source.contains("rewriteSymbolArgumentsForRider")
        )
        assertTrue(
            "FindSuperMethodsTool should resolve Rider language+symbol requests through shared argument resolution",
            source.contains("resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true)")
        )
        assertTrue(
            "FindSuperMethodsTool description should mention SymbolReferenceHandler support",
            FindSuperMethodsTool().description.contains("SymbolReferenceHandler")
        )
    }

    fun testDefinitionUsesSharedSymbolResolutionPath() {
        val source = navigationSource("FindDefinitionTool.kt")

        assertTrue(
            "FindDefinitionTool should attempt Rider backend-native definition for symbol-mode requests",
            source.contains("RiderBackendSemanticService.findDefinition(")
        )
        assertTrue(
            "FindDefinitionTool should resolve Rider language+symbol requests through shared argument resolution",
            source.contains("resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true)")
        )
        assertTrue(
            "FindDefinitionTool should not gate Rider backend definition lookup to position-mode only",
            source.contains("val requestedLanguage = optionalStringArg(arguments, ParamNames.LANGUAGE)") &&
            source.contains("val isRiderSymbolMode = resolveLookupMode(arguments) == LookupModeState.SYMBOL")
        )
        assertFalse(
            "FindDefinitionTool description should not claim Java-only symbol-mode support",
            FindDefinitionTool().description.contains("currently supported for Java only")
        )
    }

    fun testReferencesUseSharedSymbolResolutionPath() {
        val source = navigationSource("FindUsagesTool.kt")

        assertTrue(
            "FindUsagesTool should attempt Rider backend-native references for symbol-mode requests",
            source.contains("RiderBackendSemanticService.findReferences(")
        )
        assertTrue(
            "FindUsagesTool should resolve Rider language+symbol requests through shared argument resolution",
            source.contains("resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true)")
        )
        assertTrue(
            "FindUsagesTool should not gate Rider backend reference lookup to position-mode only",
            source.contains("val requestedLanguage = optionalStringArg(arguments, ParamNames.LANGUAGE)") &&
            source.contains("val isRiderSymbolMode = resolveLookupMode(arguments) == LookupModeState.SYMBOL")
        )
        assertFalse(
            "FindUsagesTool description should not claim Java-only symbol-mode support",
            FindUsagesTool().description.contains("currently supported for Java only")
        )
    }

    fun testNavigationToolDescriptionsNoLongerClaimJavaOnlySymbolSupport() {
        val descriptions = listOf(
            CallHierarchyTool().description,
            FindImplementationsTool().description,
            FindSuperMethodsTool().description,
            FindDefinitionTool().description,
            FindUsagesTool().description
        )

        descriptions.forEach { description ->
            assertFalse(
                "Descriptions should not claim Java-only symbol-mode support",
                description.contains("currently supported for Java only")
            )
            assertTrue(
                "Descriptions should explain shared symbol-handler based support",
                description.contains("SymbolReferenceHandler")
            )
            assertTrue(
                "Descriptions should mention Rider C#/F# symbol-mode support",
                description.contains("Rider C#/F#")
            )
        }
    }

    private fun navigationSource(fileName: String): String {
        return File("src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/navigation/$fileName").readText()
    }

    private fun assertBackendNativeRiderSymbolRouting(source: String, toolName: String, fallbackMarker: String) {
        val riderBackendMarker = "RiderBackendSemanticService.resolveSymbolToPosition("
        val explicitUnsupportedMarker = "unsupported"

        val backendIndex = source.indexOf(riderBackendMarker)
        val fallbackIndex = source.indexOf(fallbackMarker)
        val explicitUnsupportedIndex = source.indexOf(explicitUnsupportedMarker)

        val hasBackendNativeRouting = backendIndex >= 0 && (fallbackIndex < 0 || backendIndex < fallbackIndex)
        val hasExplicitUnsupportedException = explicitUnsupportedIndex >= 0 && source.contains("Rider C#/F#")

        assertTrue(
            "$toolName should either route Rider C#/F# symbol-mode through RiderBackendSemanticService.resolveSymbolToPosition before generic PSI fallback, or name an explicit Rider symbol-mode unsupported exception",
            hasBackendNativeRouting || hasExplicitUnsupportedException
        )
    }

    private fun assertContainsForRiderLanguages(description: String, label: String) {
        assertContains(description, "SymbolReferenceHandler", "$label should mention SymbolReferenceHandler support")
        assertContains(description, "Rider C#/F#", "$label should mention Rider C#/F# support")
        assertContains(description, "C#", "$label should mention C# explicitly")
        assertContains(description, "F#", "$label should mention F# explicitly")
    }

    private fun assertContains(source: String, needle: String, message: String) {
        assertTrue(message, source.contains(needle))
    }
}
