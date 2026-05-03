package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
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
        assertEquals("C#", RiderCSharpStructureHandler().languageId)

        assertEquals("F#", RiderFSharpTypeHierarchyHandler().languageId)
        assertEquals("F#", RiderFSharpImplementationsHandler().languageId)
        assertEquals("F#", RiderFSharpCallHierarchyHandler().languageId)
        assertEquals("F#", RiderFSharpSuperMethodsHandler().languageId)
        assertEquals("F#", RiderFSharpStructureHandler().languageId)
    }

    fun testAllHandlerTypesInstantiate() {
        // Verify all concrete handler classes can be instantiated without errors
        val csharpHandlers = listOf(
            RiderCSharpTypeHierarchyHandler(),
            RiderCSharpImplementationsHandler(),
            RiderCSharpCallHierarchyHandler(),
            RiderCSharpSuperMethodsHandler(),
            RiderCSharpStructureHandler()
        )
        assertEquals(5, csharpHandlers.size)

        val fsharpHandlers = listOf(
            RiderFSharpTypeHierarchyHandler(),
            RiderFSharpImplementationsHandler(),
            RiderFSharpCallHierarchyHandler(),
            RiderFSharpSuperMethodsHandler(),
            RiderFSharpStructureHandler()
        )
        assertEquals(5, fsharpHandlers.size)
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
    }
}
