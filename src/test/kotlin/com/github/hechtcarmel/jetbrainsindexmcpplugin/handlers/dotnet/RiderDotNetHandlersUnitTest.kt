package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import junit.framework.TestCase

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

    fun testRdTimeoutMessageIsExplicitAboutOperationAndCallName() {
        val timeout = RdCallOutcome.Timeout(callName = "findReferences", timeoutSeconds = 60L)

        assertEquals(
            "Rider backend timed out while finding references after 60s (rd call 'findReferences').",
            timeout.toUserMessage("finding references")
        )
    }

    fun testFindReferencesFailureReturnsExplicitErrorMessage() {
        mockkObject(RdProtocolBridge)
        try {
            val project = mockk<Project>()
            val semanticTarget = Any()
            val request = Any()
            val failureMessage = "F# symbol-based find_references for type-only qualified symbols in scope 'project_files' is temporarily blocked..."

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
            assertNotNull(result.errorMessage)
            assertTrue(result.errorMessage!!.contains(failureMessage))
            assertNull(result.value)
        } finally {
            unmockkObject(RdProtocolBridge)
        }
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
}
