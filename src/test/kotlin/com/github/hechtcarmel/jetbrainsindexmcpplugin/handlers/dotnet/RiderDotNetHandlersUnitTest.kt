package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import junit.framework.TestCase
import java.io.File

/**
 * Unit tests for the Rider protocol-based .NET handlers.
 *
 * Tests the reflection-based data conversion and tree reconstruction logic
 * without requiring a running Rider instance or actual rd protocol connection.
 */
class RiderDotNetHandlersUnitTest : TestCase() {

    fun testRiderEnvironmentDetection_notInRider() {
        // In a test environment (IC-based), Rider classes aren't available
        val handler = RiderCSharpTypeHierarchyHandler()
        assertFalse("Should not be available outside Rider", handler.isAvailable())
    }

    fun testRiderFSharpEnvironmentDetection_notInRider() {
        val handler = RiderFSharpImplementationsHandler()
        assertFalse("Should not be available outside Rider", handler.isAvailable())
    }

    fun testLanguageIds() {
        assertEquals("C#", RiderCSharpTypeHierarchyHandler().languageId)
        assertEquals("C#", RiderCSharpImplementationsHandler().languageId)
        assertEquals("C#", RiderCSharpCallHierarchyHandler().languageId)
        assertEquals("C#", RiderCSharpSuperMethodsHandler().languageId)
        assertEquals("C#", RiderCSharpSymbolReferenceHandler().languageId)
        assertEquals("C#", RiderCSharpStructureHandler().languageId)

        assertEquals("F#", RiderFSharpTypeHierarchyHandler().languageId)
        assertEquals("F#", RiderFSharpImplementationsHandler().languageId)
        assertEquals("F#", RiderFSharpCallHierarchyHandler().languageId)
        assertEquals("F#", RiderFSharpSuperMethodsHandler().languageId)
        assertEquals("F#", RiderFSharpSymbolReferenceHandler().languageId)
        assertEquals("F#", RiderFSharpStructureHandler().languageId)
    }

    fun testAllHandlerTypesInstantiate() {
        // Verify all concrete handler classes can be instantiated without errors
        val csharpHandlers = listOf(
            RiderCSharpTypeHierarchyHandler(),
            RiderCSharpImplementationsHandler(),
            RiderCSharpCallHierarchyHandler(),
            RiderCSharpSuperMethodsHandler(),
            RiderCSharpSymbolReferenceHandler(),
            RiderCSharpStructureHandler()
        )
        assertEquals(6, csharpHandlers.size)

        val fsharpHandlers = listOf(
            RiderFSharpTypeHierarchyHandler(),
            RiderFSharpImplementationsHandler(),
            RiderFSharpCallHierarchyHandler(),
            RiderFSharpSuperMethodsHandler(),
            RiderFSharpSymbolReferenceHandler(),
            RiderFSharpStructureHandler()
        )
        assertEquals(6, fsharpHandlers.size)
    }

    fun testRegistrationSkipsWhenNotInRider() {
        // RiderDotNetHandlers.register should gracefully skip when not in Rider
        val registry = LanguageHandlerRegistry
        registry.clear()

        // Should not throw even though we're not in Rider
        RiderDotNetHandlers.register(registry)

        // No handlers should be registered since Rider classes aren't available
        assertFalse(registry.hasTypeHierarchyHandlers())
        assertFalse(registry.hasImplementationsHandlers())
        assertFalse(registry.hasCallHierarchyHandlers())
        assertFalse(registry.hasSuperMethodsHandlers())
        assertFalse(registry.hasStructureHandlers())
        assertTrue("Symbol handlers should not be registered outside Rider", registry.getSupportedLanguageNamesForSymbolReference().isEmpty())
    }

    fun testRegistrationAddsSymbolHandlersWhenRiderEnvironmentAvailable() {
        mockkObject(RiderEnvironment)
        try {
            every { RiderEnvironment.isAvailable } returns true
            every { RiderEnvironment.isProtocolGenerated } returns true

            val registry = LanguageHandlerRegistry
            registry.clear()
            RiderDotNetHandlers.register(registry)

            assertEquals(listOf("C#", "F#"), registry.getSupportedLanguageNamesForSymbolReference().sorted())
        } finally {
            LanguageHandlerRegistry.clear()
            unmockkObject(RiderEnvironment)
        }
    }

    fun testCSharpParserSupportsConstructorAliasAndGenericNormalization() {
        val parsed = RiderSymbolParser.parse("C#", "global::Demo.Outer`1+Inner#Inner(string, List<int>)").getOrThrow()

        assertEquals("Demo.Outer.Inner", parsed.containerQualifiedName)
        assertEquals(".ctor", parsed.memberName)
        assertTrue(parsed.isConstructor)
        assertEquals(listOf("System.String", "List"), parsed.parameterTypes)
        assertEquals("Demo.Outer.Inner#.ctor(System.String,List)", parsed.normalizedSymbol)
    }

    fun testFSharpParserSupportsModuleMemberSymbols() {
        val parsed = RiderSymbolParser.parse("F#", "Demo.Module#run<int>(unit)").getOrThrow()

        assertEquals("Demo.Module", parsed.containerQualifiedName)
        assertEquals("run", parsed.memberName)
        assertFalse(parsed.isConstructor)
        assertEquals(listOf("Microsoft.FSharp.Core.Unit"), parsed.parameterTypes)
        assertEquals("Demo.Module#run(Microsoft.FSharp.Core.Unit)", parsed.normalizedSymbol)
    }

    fun testParserRejectsMalformedRiderSymbols() {
        val failure = RiderSymbolParser.parse("C#", "Demo.Type#").exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is IllegalArgumentException)
    }

    fun testRiderRequestLimitsMatchBridgeConstants() {
        assertEquals(500, IMPLEMENTATIONS_RESULT_LIMIT)
        assertEquals(20, CALL_HIERARCHY_RESULT_LIMIT)
    }

    fun testRdCallTimeoutsUseInteractiveHierarchyBudgetButKeepRenameLongRunning() {
        assertEquals(30L, RdProtocolBridge.timeoutSecondsForCall("getCallHierarchy"))
        assertEquals(300L, RdProtocolBridge.timeoutSecondsForCall("renameSymbol"))
        assertEquals(60L, RdProtocolBridge.timeoutSecondsForCall("findReferences"))
    }

    fun testRegeneratedMutationAndSearchRequestsRemainConstructibleThroughBridgeReflection() {
        val findSymbolsRequest = requireRdStruct(
            "RdFindSymbolsRequest",
            "ReadOnlyBaselineService",
            BuiltInSearchScope.PROJECT_FILES.wireValue,
            "C#",
            25
        )
        assertEquals("ReadOnlyBaselineService", rdProperty(findSymbolsRequest, "query"))
        assertEquals(BuiltInSearchScope.PROJECT_FILES.wireValue, rdProperty(findSymbolsRequest, "scope"))
        assertEquals("C#", rdProperty(findSymbolsRequest, "language"))
        assertEquals(25, rdProperty(findSymbolsRequest, "limit"))

        val renameFileRequest = requireRdStruct("RdRenameFileRequest", "src/Service.cs", "ServiceRenamed.cs")
        assertEquals("src/Service.cs", rdProperty(renameFileRequest, "filePath"))
        assertEquals("ServiceRenamed.cs", rdProperty(renameFileRequest, "newName"))

        val moveFileRequest = requireRdStruct("RdMoveFileRequest", "src/Service.cs", "src/Moved")
        assertEquals("src/Service.cs", rdProperty(moveFileRequest, "filePath"))
        assertEquals("src/Moved", rdProperty(moveFileRequest, "destinationDirectory"))

        val semanticTarget = requireRdStruct("RdSemanticTarget", "src/Service.cs", 12, 9, "C#", "Demo.Service#Run()")
        val safeDeleteRequest = requireRdStruct("RdSafeDeleteRequest", semanticTarget, "symbol", false)
        assertSame(semanticTarget, rdProperty(safeDeleteRequest, "target"))
        assertEquals("symbol", rdProperty(safeDeleteRequest, "targetType"))
        assertEquals(false, rdProperty(safeDeleteRequest, "force"))
    }

    fun testRegeneratedResultsExposeAdditiveFieldsWithoutBreakingLegacyMutationContracts() {
        val symbolInfo = requireRdStruct(
            "RdSymbolInfo",
            "ReadOnlyBaselineService",
            "Demo.ReadOnlyBaselineService",
            "CLASS",
            "src/Service.cs",
            7,
            14,
            "C#",
            null,
            listOf("public")
        )
        val findSymbolsResult = requireRdStruct("RdFindSymbolsResult", listOf(symbolInfo), 1)
        assertEquals(1, rdProperty(findSymbolsResult, "totalCount"))
        val symbols = rdProperty(findSymbolsResult, "symbols") as List<*>
        assertEquals(1, symbols.size)
        assertEquals("ReadOnlyBaselineService", rdProperty(symbols.single()!!, "name"))
        assertEquals("Demo.ReadOnlyBaselineService", rdProperty(symbols.single()!!, "qualifiedName"))

        val verification = requireRdStruct(
            "RdMutationVerification",
            "verification_limited",
            listOf("rename_applied", "usage_scan"),
            listOf("Closed-file diagnostics are supplementary only")
        )
        val renameSymbolResult = requireRdStruct(
            "RdRenameSymbolResult",
            true,
            "OldName",
            "NewName",
            listOf("src/Service.cs"),
            1,
            "Rename applied with bounded verification.",
            "verification_limited",
            verification
        )
        assertEquals(true, rdProperty(renameSymbolResult, "success"))
        assertEquals("OldName", rdProperty(renameSymbolResult, "oldName"))
        assertEquals("NewName", rdProperty(renameSymbolResult, "newName"))
        assertEquals(listOf("src/Service.cs"), rdProperty(renameSymbolResult, "affectedFiles"))
        assertEquals(1, rdProperty(renameSymbolResult, "changesCount"))
        assertEquals("Rename applied with bounded verification.", rdProperty(renameSymbolResult, "message"))
        assertEquals("verification_limited", rdProperty(renameSymbolResult, "status"))
        assertEquals("verification_limited", rdProperty(verification, "status"))
        assertEquals(listOf("rename_applied", "usage_scan"), rdProperty(verification, "checksRun"))
        assertEquals(listOf("Closed-file diagnostics are supplementary only"), rdProperty(verification, "warnings"))
        assertSame(verification, rdProperty(renameSymbolResult, "verification"))

        val renameFileResult = requireRdStruct(
            "RdRenameFileResult",
            true,
            "src/Service.cs",
            "src/ServiceRenamed.cs",
            listOf("src/Service.cs", "src/Consumer.cs"),
            2,
            "File rename applied.",
            "success",
            verification
        )
        assertEquals("src/Service.cs", rdProperty(renameFileResult, "oldPath"))
        assertEquals("src/ServiceRenamed.cs", rdProperty(renameFileResult, "newPath"))
        assertEquals("success", rdProperty(renameFileResult, "status"))
        assertSame(verification, rdProperty(renameFileResult, "verification"))

        val moveFileResult = requireRdStruct(
            "RdMoveFileResult",
            true,
            "src/Service.cs",
            "src/Moved/Service.cs",
            listOf("src/Service.cs", "src/Consumer.cs"),
            2,
            "Move applied.",
            "success",
            verification
        )
        assertEquals("src/Service.cs", rdProperty(moveFileResult, "oldPath"))
        assertEquals("src/Moved/Service.cs", rdProperty(moveFileResult, "newPath"))
        assertEquals("success", rdProperty(moveFileResult, "status"))
        assertSame(verification, rdProperty(moveFileResult, "verification"))

        val blockedUsage = requireRdStruct(
            "RdSafeDeleteBlockedUsage",
            "src/Consumer.cs",
            18,
            13,
            "Service.Run()",
            "method_call"
        )
        val safeDeleteResult = requireRdStruct(
            "RdSafeDeleteResult",
            false,
            listOf("src/Service.cs"),
            0,
            "Deletion blocked by usages.",
            "blocked",
            listOf(blockedUsage),
            verification
        )
        assertEquals(false, rdProperty(safeDeleteResult, "success"))
        assertEquals(listOf("src/Service.cs"), rdProperty(safeDeleteResult, "affectedFiles"))
        assertEquals(0, rdProperty(safeDeleteResult, "changesCount"))
        assertEquals("Deletion blocked by usages.", rdProperty(safeDeleteResult, "message"))
        assertEquals("blocked", rdProperty(safeDeleteResult, "status"))
        val blockedUsages = rdProperty(safeDeleteResult, "blockedUsages") as List<*>
        assertEquals(1, blockedUsages.size)
        assertEquals("src/Consumer.cs", rdProperty(blockedUsages.single()!!, "filePath"))
        assertEquals(18, rdProperty(blockedUsages.single()!!, "line"))
        assertEquals(13, rdProperty(blockedUsages.single()!!, "column"))
        assertEquals("Service.Run()", rdProperty(blockedUsages.single()!!, "context"))
        assertEquals("method_call", rdProperty(blockedUsages.single()!!, "kind"))
        assertSame(verification, rdProperty(safeDeleteResult, "verification"))
    }

    fun testBackendMutationResultMapperParsesStructuredRenameDiagnosticsFromMessage() {
        val verification = requireRdStruct(
            "RdMutationVerification",
            "verification_limited",
            listOf("rename_execution", "post_change_semantics"),
            listOf("Closed-file diagnostics are supplementary only")
        )
        val renameSymbolResult = requireRdStruct(
            "RdRenameSymbolResult",
            false,
            "GetAllProducts",
            "GetProducts",
            emptyList<String>(),
            0,
            "ReSharper reports that 'GetAllProducts' cannot be renamed (CannotBeRenamed). No files were modified. [backendSymbolRename: resolutionStatus=success, targetKind=member, resolvedName=GetAllProducts, sourceTokenText=GetAllProducts, executionHint=frontend_editor_backed_exact_target_only, unsupportedReason=rename_availability_CannotBeRenamed, traceStages=plan.end>target-resolution.bound>availability.end]",
            "unsupported_context",
            verification
        )

        val mapped = RiderBackendMutationResultMapper.fromRdResult(renameSymbolResult)

        assertFalse(mapped.success)
        assertEquals("unsupported_context", mapped.status)
        assertEquals(0, mapped.changesCount)
        assertEquals(
            "ReSharper reports that 'GetAllProducts' cannot be renamed (CannotBeRenamed). No files were modified.",
            mapped.message
        )
        assertEquals("verification_limited", mapped.verification?.status)
        assertEquals(listOf("rename_execution", "post_change_semantics"), mapped.verification?.checksRun)
        assertEquals(listOf("Closed-file diagnostics are supplementary only"), mapped.verification?.warnings)
        assertEquals("success", mapped.renameDiagnostics?.resolutionStatus)
        assertEquals("member", mapped.renameDiagnostics?.targetKind)
        assertEquals("GetAllProducts", mapped.renameDiagnostics?.resolvedName)
        assertEquals("GetAllProducts", mapped.renameDiagnostics?.sourceTokenText)
        assertEquals("frontend_editor_backed_exact_target_only", mapped.renameDiagnostics?.executionHint)
        assertEquals("rename_availability_CannotBeRenamed", mapped.renameDiagnostics?.unsupportedReason)
        assertEquals(
            listOf("plan.end", "target-resolution.bound", "availability.end"),
            mapped.renameDiagnostics?.traceStages
        )
    }

    fun testBackendMutationResultMapperKeepsFileRenamePayloadUnchangedWhenNoDiagnosticsSuffixExists() {
        val verification = requireRdStruct(
            "RdMutationVerification",
            "success",
            listOf("rename_applied"),
            emptyList<String>()
        )
        val renameFileResult = requireRdStruct(
            "RdRenameFileResult",
            true,
            "src/Service.cs",
            "src/ServiceRenamed.cs",
            listOf("src/Service.cs", "src/Consumer.cs"),
            2,
            "File rename applied.",
            "success",
            verification
        )

        val mapped = RiderBackendMutationResultMapper.fromRdResult(renameFileResult)

        assertTrue(mapped.success)
        assertEquals("success", mapped.status)
        assertEquals("File rename applied.", mapped.message)
        assertEquals(listOf("src/Service.cs", "src/Consumer.cs"), mapped.affectedFiles)
        assertEquals(2, mapped.changesCount)
        assertNull(mapped.renameDiagnostics)
        assertEquals("success", mapped.verification?.status)
        assertEquals(listOf("rename_applied"), mapped.verification?.checksRun)
        assertEquals(emptyList<String>(), mapped.verification?.warnings)
    }

    fun testRdTimeoutMessageIsExplicitAboutOperationAndCallName() {
        val timeout = RdCallOutcome.Timeout(callName = "findReferences", timeoutSeconds = 60L)

        assertEquals(
            "Rider backend timed out while finding references after 60s (rd call 'findReferences').",
            timeout.toUserMessage("finding references")
        )
    }

    fun testFindReferencesFailurePassesThroughExplicitColdCacheMessage() {
        mockkObject(RdProtocolBridge)
        try {
            val project = mockk<Project>()
            val semanticTarget = Any()
            val request = Any()
            val failureMessage = "Rider F# find_references requires warmed ReSharper usage caches; module/type-only project_files searches should retry after IDE warm-up or use a position/member target."

            every { RdProtocolBridge.getModel(project) } returns Any()
            every {
                RdProtocolBridge.createStruct(
                    "$MODEL_PKG.RdSemanticTarget",
                    null,
                    null,
                    null,
                    "F#",
                    "FSharpPlus.Lens"
                )
            } returns semanticTarget
            every {
                RdProtocolBridge.createStruct(
                    "$MODEL_PKG.RdFindReferencesRequest",
                    semanticTarget,
                    BuiltInSearchScope.PROJECT_FILES.wireValue,
                    100
                )
            } returns request
            every {
                RdProtocolBridge.invokeCallResult(any(), "findReferences", request)
            } returns RdCallOutcome.Failure(
                callName = "findReferences",
                cause = IllegalStateException(failureMessage)
            )

            val result = RiderBackendSemanticService.findReferences(
                project = project,
                file = null,
                line = null,
                column = null,
                language = "F#",
                symbol = "FSharpPlus.Lens",
                scope = BuiltInSearchScope.PROJECT_FILES,
                limit = 100
            )

            assertTrue(result.handled)
            assertEquals(failureMessage, result.errorMessage)
            assertNull(result.value)
        } finally {
            unmockkObject(RdProtocolBridge)
        }
    }

    fun testFindTypesAcceptsUppercaseCSharpAliasAndDeduplicatesBackendMatches() {
        mockkObject(RdProtocolBridge)
        try {
            val project = mockk<Project>()
            val request = Any()
            val model = Any()
            val backendPath = ""

            every { RdProtocolBridge.getModel(project) } returns model
            every {
                RdProtocolBridge.createStruct(
                    "$MODEL_PKG.RdFindTypesRequest",
                    "ReadOnlyBaselineService",
                    "substring",
                    BuiltInSearchScope.PROJECT_FILES.wireValue,
                    "CSHARP",
                    any()
                )
            } returns request
            every {
                RdProtocolBridge.invokeCall(model, "findTypes", request)
            } returns TestFindTypesResult(
                types = listOf(
                    TestRdSymbol(
                        name = "ReadOnlyBaselineService",
                        qualifiedName = "Demo.ReadOnlyBaselineService",
                        kind = "CLASS",
                        filePath = backendPath,
                        line = 7,
                        column = 14,
                        containerName = "Demo"
                    ),
                    TestRdSymbol(
                        name = "ReadOnlyBaselineService",
                        qualifiedName = "Demo.ReadOnlyBaselineService",
                        kind = "CLASS",
                        filePath = backendPath,
                        line = 7,
                        column = 14,
                        containerName = "Demo"
                    )
                )
            )

            val result = RiderBackendSemanticService.findTypes(
                project = project,
                query = "ReadOnlyBaselineService",
                matchMode = "substring",
                scope = BuiltInSearchScope.PROJECT_FILES,
                language = "CSHARP",
                limit = 25
            )

            assertTrue(result.handled)
            assertNull(result.errorMessage)
            val match = result.value?.singleOrNull()
            assertNotNull(match)
            assertEquals("ReadOnlyBaselineService", match?.name)
            assertEquals("Demo.ReadOnlyBaselineService", match?.qualifiedName)
            assertEquals("", match?.file)
        } finally {
            unmockkObject(RdProtocolBridge)
        }
    }

    fun testFindDefinitionMapsCSharpBackendPreviewAndAstPath() {
        mockkObject(RdProtocolBridge)
        try {
            val project = mockk<Project>()
            val semanticTarget = Any()
            val request = Any()
            val model = Any()
            val backendPath = ""

            every { RdProtocolBridge.getModel(project) } returns model
            every {
                RdProtocolBridge.createStruct(
                    "$MODEL_PKG.RdSemanticTarget",
                    null,
                    null,
                    null,
                    "C#",
                    "Demo.ReadOnlyBaselineService#Run(System.String)"
                )
            } returns semanticTarget
            every {
                RdProtocolBridge.createStruct(
                    "$MODEL_PKG.RdFindDefinitionRequest",
                    semanticTarget,
                    false,
                    50
                )
            } returns request
            every {
                RdProtocolBridge.invokeCall(model, "findDefinition", request)
            } returns TestFindDefinitionResult(
                definition = TestRdDefinition(
                    filePath = backendPath,
                    line = 12,
                    column = 19,
                    name = "Run"
                ),
                astPath = listOf("class ReadOnlyBaselineService", "method Run"),
                preview = "public void Run(string input)"
            )

            val result = RiderBackendSemanticService.findDefinition(
                project = project,
                file = null,
                line = null,
                column = null,
                language = "C#",
                symbol = "Demo.ReadOnlyBaselineService#Run(System.String)",
                fullElementPreview = false,
                maxPreviewLines = 50
            )

            assertTrue(result.handled)
            assertNull(result.errorMessage)
            assertEquals(
                DefinitionResult(
                    file = "",
                    line = 12,
                    column = 19,
                    preview = "public void Run(string input)",
                    symbolName = "Run",
                    astPath = listOf("class ReadOnlyBaselineService", "method Run")
                ),
                result.value
            )
        } finally {
            unmockkObject(RdProtocolBridge)
        }
    }

    fun testSemanticTargetPrefersPositionWhenDualModeInputIsComplete() {
        val projectRoot = createTempDir(prefix = "rider-semantic-target-")
        val sourceFile = File(projectRoot, "src/Service.cs").apply {
            parentFile.mkdirs()
            writeText("class Service {}")
        }
        val project = mockk<Project>()
        every { project.basePath } returns projectRoot.absolutePath

        val target = invokeCreateSemanticTarget(
            project = project,
            file = "src/Service.cs",
            line = 12,
            column = 9,
            language = "C#",
            symbol = "Demo.Service#Run()"
        )

        assertNotNull(target)
        assertEquals(sourceFile.canonicalPath, rdProperty(target!!, "filePath"))
        assertEquals(12, rdProperty(target, "line"))
        assertEquals(9, rdProperty(target, "column"))
        assertNull(rdProperty(target, "language"))
        assertNull(rdProperty(target, "symbol"))
    }

    fun testSemanticTargetTreatsBlankSymbolFieldsAsOmittedWhenPositionIsComplete() {
        val projectRoot = createTempDir(prefix = "rider-semantic-target-")
        val sourceFile = File(projectRoot, "src/Service.cs").apply {
            parentFile.mkdirs()
            writeText("class Service {}")
        }
        val project = mockk<Project>()
        every { project.basePath } returns projectRoot.absolutePath

        val target = invokeCreateSemanticTarget(
            project = project,
            file = "src/Service.cs",
            line = 7,
            column = 3,
            language = "   ",
            symbol = "\t"
        )

        assertNotNull(target)
        assertEquals(sourceFile.canonicalPath, rdProperty(target!!, "filePath"))
        assertEquals(7, rdProperty(target, "line"))
        assertEquals(3, rdProperty(target, "column"))
        assertNull(rdProperty(target, "language"))
        assertNull(rdProperty(target, "symbol"))
    }

    fun testSemanticTargetUsesSymbolModeWhenPositionIsIncomplete() {
        val project = mockk<Project>()
        every { project.basePath } returns null

        val target = invokeCreateSemanticTarget(
            project = project,
            file = "   ",
            line = null,
            column = 9,
            language = " C# ",
            symbol = " Demo.Service#Run() "
        )

        assertNotNull(target)
        assertNull(rdProperty(target!!, "file"))
        assertNull(rdProperty(target, "line"))
        assertNull(rdProperty(target, "column"))
        assertEquals("C#", rdProperty(target, "language"))
        assertEquals("Demo.Service#Run()", rdProperty(target, "symbol"))
    }

    // ── Regression Tests for Deterministic C#/F# Symbol Parsing ──────────────────

    fun testCSharpTypeOnlySymbol_RagasaWebServices_WhiteList() {
        // C# type-only symbol: RagasaWebServices.WhiteList
        val parsed = RiderSymbolParser.parse("C#", "RagasaWebServices.WhiteList").getOrThrow()

        assertEquals("RagasaWebServices.WhiteList", parsed.containerQualifiedName)
        assertNull(parsed.memberName)
        assertNull(parsed.parameterTypes)
        assertFalse(parsed.isConstructor)
        assertEquals("RagasaWebServices.WhiteList", parsed.normalizedSymbol)
    }

    fun testCSharpParameterizedMemberSymbol_SPRestData_getData() {
        // C# parameterized member symbol with fully qualified parameter types
        val parsed = RiderSymbolParser.parse(
            "C#",
            "RagasaWebServices.SPRestData#getData(System.String,System.String,System.Net.Http.HttpClient)"
        ).getOrThrow()

        assertEquals("RagasaWebServices.SPRestData", parsed.containerQualifiedName)
        assertEquals("getData", parsed.memberName)
        assertFalse(parsed.isConstructor)
        assertEquals(listOf("System.String", "System.String", "System.Net.Http.HttpClient"), parsed.parameterTypes)
        assertEquals("RagasaWebServices.SPRestData#getData(System.String,System.String,System.Net.Http.HttpClient)", parsed.normalizedSymbol)
    }

    fun testFSharpInterfaceMemberSymbol_IProductService_GetAllProducts() {
        // F# fully qualified interface member symbol
        val parsed = RiderSymbolParser.parse("F#", "WebApplication1.Services.IProductService#GetAllProducts").getOrThrow()

        assertEquals("WebApplication1.Services.IProductService", parsed.containerQualifiedName)
        assertEquals("GetAllProducts", parsed.memberName)
        assertFalse(parsed.isConstructor)
        assertNull(parsed.parameterTypes)
        assertEquals("WebApplication1.Services.IProductService#GetAllProducts", parsed.normalizedSymbol)
    }

    fun testFSharpTypeOnlySymbol_IProduct() {
        val parsed = RiderSymbolParser.parse("F#", "WebApplication1.Models.IProduct").getOrThrow()

        assertEquals("WebApplication1.Models.IProduct", parsed.containerQualifiedName)
        assertNull(parsed.memberName)
        assertNull(parsed.parameterTypes)
        assertFalse(parsed.isConstructor)
        assertEquals("WebApplication1.Models.IProduct", parsed.normalizedSymbol)
    }

    fun testFSharpConcreteMemberSymbol_ProductService_GetAllProducts() {
        val parsed = RiderSymbolParser.parse("F#", "WebApplication1.Services.ProductService#GetAllProducts").getOrThrow()

        assertEquals("WebApplication1.Services.ProductService", parsed.containerQualifiedName)
        assertEquals("GetAllProducts", parsed.memberName)
        assertNull(parsed.parameterTypes)
        assertFalse(parsed.isConstructor)
        assertEquals("WebApplication1.Services.ProductService#GetAllProducts", parsed.normalizedSymbol)
    }

    fun testFSharpCallableMemberSymbol_ProductsController_Index() {
        val parsed = RiderSymbolParser.parse("F#", "WebApplication1.Controllers.ProductsController#Index").getOrThrow()

        assertEquals("WebApplication1.Controllers.ProductsController", parsed.containerQualifiedName)
        assertEquals("Index", parsed.memberName)
        assertNull(parsed.parameterTypes)
        assertFalse(parsed.isConstructor)
        assertEquals("WebApplication1.Controllers.ProductsController#Index", parsed.normalizedSymbol)
    }

    fun testFSharpModuleFunctionSymbol_FullyQualifiedContainer() {
        // F# module/function form with fully qualified container
        val parsed = RiderSymbolParser.parse("F#", "MyNamespace.MyModule#myFunction(string)").getOrThrow()

        assertEquals("MyNamespace.MyModule", parsed.containerQualifiedName)
        assertEquals("myFunction", parsed.memberName)
        assertFalse(parsed.isConstructor)
        assertEquals(listOf("System.String"), parsed.parameterTypes)
        assertEquals("MyNamespace.MyModule#myFunction(System.String)", parsed.normalizedSymbol)
    }

    fun testCSharpConstructorSymbol_WithGenericContainer() {
        // C# constructor with generic container type
        val parsed = RiderSymbolParser.parse("C#", "Demo.Generic`1#.ctor(int)").getOrThrow()

        assertEquals("Demo.Generic", parsed.containerQualifiedName)
        assertEquals(".ctor", parsed.memberName)
        assertTrue(parsed.isConstructor)
        assertEquals(listOf("System.Int32"), parsed.parameterTypes)
        assertEquals("Demo.Generic#.ctor(System.Int32)", parsed.normalizedSymbol)
    }

    fun testFSharpInterfaceMemberWithParameters() {
        // F# interface member with parameters
        val parsed = RiderSymbolParser.parse("F#", "MyApp.IService#Process(string,int)").getOrThrow()

        assertEquals("MyApp.IService", parsed.containerQualifiedName)
        assertEquals("Process", parsed.memberName)
        assertFalse(parsed.isConstructor)
        assertEquals(listOf("System.String", "System.Int32"), parsed.parameterTypes)
        assertEquals("MyApp.IService#Process(System.String,System.Int32)", parsed.normalizedSymbol)
    }

    fun testCSharpNestedTypeSymbol() {
        // C# nested type symbol (type-only, no member)
        val parsed = RiderSymbolParser.parse("C#", "Outer.Inner.Nested").getOrThrow()

        assertEquals("Outer.Inner.Nested", parsed.containerQualifiedName)
        assertNull(parsed.memberName)
        assertNull(parsed.parameterTypes)
        assertFalse(parsed.isConstructor)
        assertEquals("Outer.Inner.Nested", parsed.normalizedSymbol)
    }

    fun testFSharpUnionCaseSymbol() {
        // F# union case (type-only, no member)
        val parsed = RiderSymbolParser.parse("F#", "MyNamespace.MyUnion").getOrThrow()

        assertEquals("MyNamespace.MyUnion", parsed.containerQualifiedName)
        assertNull(parsed.memberName)
        assertNull(parsed.parameterTypes)
        assertFalse(parsed.isConstructor)
        assertEquals("MyNamespace.MyUnion", parsed.normalizedSymbol)
    }

    fun testCSharpPropertySymbol() {
        // C# property symbol (no parameters)
        val parsed = RiderSymbolParser.parse("C#", "MyApp.MyClass#MyProperty").getOrThrow()

        assertEquals("MyApp.MyClass", parsed.containerQualifiedName)
        assertEquals("MyProperty", parsed.memberName)
        assertFalse(parsed.isConstructor)
        assertNull(parsed.parameterTypes)
        assertEquals("MyApp.MyClass#MyProperty", parsed.normalizedSymbol)
    }

    fun testFSharpRecordFieldSymbol() {
        // F# record field symbol (no parameters)
        val parsed = RiderSymbolParser.parse("F#", "MyApp.MyRecord#field").getOrThrow()

        assertEquals("MyApp.MyRecord", parsed.containerQualifiedName)
        assertEquals("field", parsed.memberName)
        assertFalse(parsed.isConstructor)
        assertNull(parsed.parameterTypes)
        assertEquals("MyApp.MyRecord#field", parsed.normalizedSymbol)
    }

    private data class TestFindTypesResult(val types: List<TestRdSymbol>)

    private data class TestRdSymbol(
        val name: String,
        val qualifiedName: String?,
        val kind: String,
        val filePath: String,
        val line: Int,
        val column: Int,
        val containerName: String?
    )

    private data class TestFindDefinitionResult(
        val definition: TestRdDefinition,
        val astPath: List<String>,
        val preview: String
    )

    private data class TestRdDefinition(
        val filePath: String,
        val line: Int,
        val column: Int,
        val name: String
    )

    private fun requireRdStruct(simpleName: String, vararg args: Any?): Any {
        val value = RdProtocolBridge.createStruct("$MODEL_PKG.$simpleName", *args)
        assertNotNull("Expected $simpleName to remain constructible after RD regeneration", value)
        return value!!
    }

    private fun invokeCreateSemanticTarget(
        project: Project,
        file: String?,
        line: Int?,
        column: Int?,
        language: String?,
        symbol: String?
    ): Any? {
        val method = RiderBackendSemanticService::class.java.getDeclaredMethod(
            "createSemanticTarget",
            Project::class.java,
            String::class.java,
            Int::class.javaObjectType,
            Int::class.javaObjectType,
            String::class.java,
            String::class.java
        ).apply {
            isAccessible = true
        }
        return method.invoke(RiderBackendSemanticService, project, file, line, column, language, symbol)
    }

    private fun rdProperty(instance: Any, name: String): Any? = RdProtocolBridge.getProperty(instance, name)
}
