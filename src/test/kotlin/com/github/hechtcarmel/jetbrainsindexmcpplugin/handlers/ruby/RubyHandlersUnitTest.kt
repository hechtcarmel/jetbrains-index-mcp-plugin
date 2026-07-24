package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetector
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import junit.framework.TestCase

class RubyHandlersUnitTest : TestCase() {

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.clear()
    }

    override fun tearDown() {
        super.tearDown()
        LanguageHandlerRegistry.clear()
    }

    fun testSymbolReferenceHandlerReturnsFailureOnEmptySymbol() {
        val handler = RubySymbolReferenceHandler()
        val result = handler.resolveSymbol(mockk(relaxed = true), "")
        assertTrue("resolveSymbol should return Failure for empty symbol, was: $result", result.isFailure)
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue("Expected 'does not match expected Ruby symbol format' in error message, got: $msg", msg.contains("does not match expected Ruby symbol format"))
    }

    fun testRegistryExposesRubySymbolReferenceWhenForcedAvailable() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns true
        every { PluginDetectors.ruby } returns rubyDetector
        LanguageHandlerRegistry.clear()
        try {
            LanguageHandlerRegistry.registerSymbolReferenceHandler(RubySymbolReferenceHandler())
            assertTrue(
                "'Ruby' must be advertised as a supported symbol-reference language",
                LanguageHandlerRegistry.getSupportedLanguageNamesForSymbolReference().contains("Ruby")
            )
            val h = LanguageHandlerRegistry.getSymbolReferenceHandlerByLanguageName("Ruby")
            assertNotNull("Ruby symbol reference handler should be retrievable from the registry", h)
            assertEquals("Ruby handler should report languageName as 'Ruby', was: ${h?.languageName}", "Ruby", h?.languageName)
        } finally {
            LanguageHandlerRegistry.clear()
            unmockkObject(PluginDetectors)
        }
    }
}