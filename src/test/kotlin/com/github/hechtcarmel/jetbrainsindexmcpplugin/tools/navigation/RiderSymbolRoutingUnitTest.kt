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
            "language = normalizedRequestedLanguage",
            "FindDefinitionTool should pass the normalized requested language before calling the Rider backend-native definition path"
        )
        assertContains(
            source,
            "symbol = optionalStringArg(arguments, ParamNames.SYMBOL)",
            "FindDefinitionTool should normalize blank requested symbol before calling the Rider backend-native definition path"
        )
        assertContains(
            source,
            "line = optionalPositionIntArg(arguments, ParamNames.LINE)",
            "FindDefinitionTool should normalize blank and non-positive numeric position arguments before calling the Rider backend-native definition path"
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
            "language = normalizedRequestedLanguage",
            "FindUsagesTool should pass the normalized requested language before calling the Rider backend-native references path"
        )
        assertContains(
            source,
            "symbol = optionalStringArg(arguments, ParamNames.SYMBOL)",
            "FindUsagesTool should normalize blank requested symbol before calling the Rider backend-native references path"
        )
        assertContains(
            source,
            "line = optionalPositionIntArg(arguments, ParamNames.LINE)",
            "FindUsagesTool should normalize blank and non-positive numeric position arguments before calling the Rider backend-native references path"
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

    fun testCallHierarchyNormalizesRiderLanguageAliasesBeforeEarlySymbolRouting() {
        val source = navigationSource("CallHierarchyTool.kt")

        assertContains(
            source,
            "normalizeAcceptedRiderLanguageAlias(requestedLanguage)",
            "CallHierarchyTool should normalize Rider language aliases before deciding whether C#/F# symbol-mode validation/backend routing applies"
        )
        assertFalse(
            "CallHierarchyTool should not hardcode only canonical Rider language ids for early symbol-mode routing",
            source.contains("requestedLanguage in setOf(\"C#\", \"F#\")")
        )
    }

    fun testRiderSymbolModeToolsNormalizeAliasesBeforeEarlyRouting() {
        val expectations = listOf(
            "FindDefinitionTool.kt",
            "FindUsagesTool.kt",
            "FindImplementationsTool.kt",
            "FindSuperMethodsTool.kt",
            "CallHierarchyTool.kt"
        )

        expectations.forEach { fileName ->
            val source = navigationSource(fileName)
            assertContains(
                source,
                "normalizeAcceptedRiderLanguageAlias(requestedLanguage)",
                "$fileName should normalize accepted Rider language aliases before deciding symbol-mode backend routing"
            )
            assertFalse(
                "$fileName should not hardcode only canonical Rider language ids for symbol-mode backend routing",
                source.contains("requestedLanguage in setOf(\"C#\", \"F#\")")
            )
        }
    }

    fun testCallHierarchyDescriptionMakesRiderCallerScopeSemanticsExplicit() {
        val description = CallHierarchyTool().description

        assertTrue(
            "CallHierarchyTool should keep the public scope names visible in the description",
            description.contains("project_files") && description.contains("project_and_libraries")
        )
        assertTrue(
            "CallHierarchyTool should explain that Rider C#/F# caller scope separation is only guaranteed where the backend can actually enforce project-only versus library-inclusive search domains.",
            description.contains("where backend support exists") ||
                description.contains("where backend APIs can enforce that distinction") ||
                description.contains("where the backend can enforce that distinction")
        )
    }

    fun testCallHierarchyDescriptionMakesFrameworkRoutedEmptyResultsActionableWithoutImplyingBackendFailure() {
        val description = CallHierarchyTool().description

        assertTrue(
            "CallHierarchyTool should explain that empty Rider caller results for framework-routed endpoints do not automatically mean the backend failed.",
            description.contains("does not imply backend failure") ||
                description.contains("does not imply a backend failure")
        )
        assertTrue(
            "CallHierarchyTool should point users toward framework routing/dispatch as the reason static callers can be empty.",
            description.contains("routing") ||
                description.contains("framework dispatch") ||
                description.contains("reflection")
        )
    }

    fun testFindUsagesDescriptionMakesRiderDeduplicationAndTruncationExplicit() {
        val description = FindUsagesTool().description

        assertTrue(
            "FindUsagesTool should document that Rider reference rows are deduplicated deterministically before result limiting so truncation is explainable.",
            description.contains("deduplicated deterministically") ||
                description.contains("deduplicated before pagination") ||
                description.contains("deduplicated before truncation")
        )
        assertTrue(
            "FindUsagesTool should make Rider truncation semantics explicit instead of leaving maxResults/pageSize behavior implicit.",
            description.contains("truncated") ||
                description.contains("truncation")
        )
    }

    fun testCallHierarchyRiderSymbolModeDoesNotRewriteSymbolsIntoSourcePositionsBeforeBackendRouting() {
        val source = navigationSource("CallHierarchyTool.kt")

        assertTrue(
            "CallHierarchyTool should route Rider C#/F# symbol-mode requests through the backend-native semantic call hierarchy service",
            source.contains("RiderBackendSemanticService.getCallHierarchy(")
        )
        assertFalse(
            "CallHierarchyTool should send Rider C#/F# symbol-mode requests to backend semantic call hierarchy directly instead of rewriting them through resolveSymbolToPosition first",
            source.contains("RiderBackendSemanticService.resolveSymbolToPosition(")
        )
        assertFalse(
            "CallHierarchyTool should not require a frontend PSI lookup via findNavigablePsiElement before Rider backend semantic call hierarchy can run",
            source.contains("findNavigablePsiElement(")
        )
        assertFalse(
            "CallHierarchyTool should not keep a source-position-mapping unsupported message once Rider symbol-mode call hierarchy is backend-native",
            source.contains("map to source positions")
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
            Triple(
                "FindImplementationsTool.kt",
                "Rider C# symbol-mode implementations require backend-native symbol resolution and are unsupported for symbol requests the backend cannot map to source positions.",
                null
            ),
            Triple(
                "CallHierarchyTool.kt",
                "Rider C# symbol-mode call hierarchy requires the Rider backend-native path and is unsupported when that backend cannot resolve a callable semantic target.",
                "RIDER_CALL_HIERARCHY_SYMBOL_MODE_UNSUPPORTED"
            ),
            Triple(
                "FindSuperMethodsTool.kt",
                "Rider C# symbol-mode super methods require backend-native symbol resolution and are unsupported for symbol requests the backend cannot map to source positions.",
                null
            )
        )

        expectations.forEach { (fileName, message, constantReference) ->
            val source = navigationSource(fileName)
            assertTrue(
                "$fileName should keep a deterministic Rider unsupported message when backend symbol resolution cannot resolve the requested callable target",
                source.contains(message) || (constantReference != null && source.contains(constantReference))
            )
        }
    }

    fun testRiderDefinitionAndReferencesUnsupportedMessagesStayDeterministic() {
        val expectations = listOf(
            "FindDefinitionTool.kt" to
                "Rider C# symbol-mode definition requires the Rider backend-native path and is unsupported when that backend is unavailable.",
            "FindUsagesTool.kt" to
                "Rider C# symbol-mode references require the Rider backend-native path and are unsupported when that backend is unavailable."
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
            "CallHierarchyTool description should mention C# (the supported Rider language)",
            CallHierarchyTool().description.contains("C#")
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
            "FindImplementationsTool description should mention C# (the supported Rider language)",
            FindImplementationsTool().description.contains("C#")
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
            "FindSuperMethodsTool description should mention C# (the supported Rider language)",
            FindSuperMethodsTool().description.contains("C#")
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
                "Descriptions should mention C# (the only Rider language supported)",
                description.contains("C#")
            )
        }
    }

    private fun navigationSource(fileName: String): String {
        return File("src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/navigation/$fileName").readText()
    }

    private fun assertBackendNativeRiderSymbolRouting(source: String, toolName: String, fallbackMarker: String) {
        val riderBackendMarkers = listOf(
            "RiderBackendSemanticService.resolveSymbolToPosition(",
            "RiderBackendSemanticService.getCallHierarchy("
        )
        val explicitUnsupportedMarker = "unsupported"

        val backendIndex = riderBackendMarkers
            .map { source.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: -1
        val fallbackIndex = source.indexOf(fallbackMarker)
        val explicitUnsupportedIndex = source.indexOf(explicitUnsupportedMarker)

        val hasBackendNativeRouting = backendIndex >= 0 && (fallbackIndex < 0 || backendIndex < fallbackIndex)
        val hasExplicitUnsupportedException = explicitUnsupportedIndex >= 0 && source.contains("Rider C#")

        assertTrue(
            "$toolName should either route Rider C# symbol-mode through RiderBackendSemanticService.resolveSymbolToPosition before generic PSI fallback, or name an explicit Rider symbol-mode unsupported exception",
            hasBackendNativeRouting || hasExplicitUnsupportedException
        )
    }

    private fun assertContainsForRiderLanguages(description: String, label: String) {
        assertContains(description, "C#", "$label should mention C#")
    }

    private fun assertContains(source: String, needle: String, message: String) {
        assertTrue(message, source.contains(needle))
    }
}
