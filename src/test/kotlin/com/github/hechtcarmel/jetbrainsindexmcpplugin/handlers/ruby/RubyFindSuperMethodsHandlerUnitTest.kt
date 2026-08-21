package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetector
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import junit.framework.TestCase

/**
 * Pure (non-fixture) unit tests for [RubySuperMethodsHandler].
 *
 * These run without the Ruby plugin — no PSI, no IDE. They test handler
 * metadata, `canHandle` gating, registry wiring, and the null contract when
 * no containing method can be resolved.
 *
 * **Intentionally NOT tested here** (real behavior lives in the platform test):
 *   - `getMethodKey` format, the `visited`/`visitedClasses` cycle-guards, and
 *     the MAX_SUPER_METHODS cap — these depend on real Ruby PSI. Asserting them
 *     via local set/string copies is a coverage illusion. See
 *     [RubyFindSuperMethodsHandlerPlatformTest].
 */
class RubyFindSuperMethodsHandlerUnitTest : TestCase() {

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.clear()
    }

    override fun tearDown() {
        super.tearDown()
        LanguageHandlerRegistry.clear()
    }

    // -- Metadata -------------------------------------------------------------

    fun testLanguageIdIsRuby() {
        assertEquals("languageId must be 'Ruby'", "Ruby", RubySuperMethodsHandler().languageId)
    }

    fun testIsAvailableWithoutPlugin() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns false
        every { PluginDetectors.ruby } returns rubyDetector
        try {
            assertFalse("isAvailable should be false without Ruby plugin", RubySuperMethodsHandler().isAvailable())
        } finally {
            unmockkObject(PluginDetectors)
        }
    }

    // -- canHandle gating -----------------------------------------------------

    fun testCanHandleReturnsFalseWithoutRubyPlugin() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns false
        every { PluginDetectors.ruby } returns rubyDetector
        try {
            assertFalse("canHandle must be false without Ruby plugin",
                RubySuperMethodsHandler().canHandle(mockk<com.intellij.psi.PsiElement>()))
        } finally {
            unmockkObject(PluginDetectors)
        }
    }

    fun testCanHandleRejectsNonRubyLanguage() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns true
        every { PluginDetectors.ruby } returns rubyDetector
        try {
            val element = mockk<com.intellij.psi.PsiElement> {
                every { language } returns mockk { every { id } returns "kotlin" }
            }
            assertFalse("canHandle must return false for non-Ruby element",
                RubySuperMethodsHandler().canHandle(element))
        } finally {
            unmockkObject(PluginDetectors)
        }
    }

    fun testCanHandleReturnsTrueForRubyLanguageElement() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns true
        every { PluginDetectors.ruby } returns rubyDetector
        try {
            val element = mockk<com.intellij.psi.PsiElement> {
                every { language } returns mockk { every { id } returns "ruby" }
            }
            assertTrue("canHandle must return true for Ruby element with plugin available",
                RubySuperMethodsHandler().canHandle(element))
        } finally {
            unmockkObject(PluginDetectors)
        }
    }

    // -- Registry Wiring ------------------------------------------------------

    fun testRegistryExposesRubyFindSuperMethodsWhenForcedAvailable() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns true
        every { PluginDetectors.ruby } returns rubyDetector
        LanguageHandlerRegistry.clear()
        try {
            LanguageHandlerRegistry.registerSuperMethodsHandler(RubySuperMethodsHandler())
            assertTrue(
                "'Ruby' must be advertised as a supported find-super-methods language",
                LanguageHandlerRegistry.getSupportedLanguagesForSuperMethods().contains("Ruby")
            )
        } finally {
            LanguageHandlerRegistry.clear()
            unmockkObject(PluginDetectors)
        }
    }

    // -- Null contract without Ruby PSI ---------------------------------------

    fun testFindSuperMethodsReturnsNullWithoutRubyPlugin() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns true
        every { PluginDetectors.ruby } returns rubyDetector
        try {
            val handler = RubySuperMethodsHandler()
            val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
            val element = mockk<com.intellij.psi.PsiElement>(relaxUnitFun = true) {
                every { parent } returns null
            }
            assertNull("findSuperMethods must return null when resolveToRMethod fails",
                handler.findSuperMethods(element, project))
        } finally {
            unmockkObject(PluginDetectors)
        }
    }

    fun testFindSuperMethodsReturnsNullForNonRubyElement() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns true
        every { PluginDetectors.ruby } returns rubyDetector
        try {
            val handler = RubySuperMethodsHandler()
            val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
            val element = mockk<com.intellij.psi.PsiElement>(relaxUnitFun = true) {
                every { parent } returns null
            }
            assertNull("findSuperMethods must return null when no containing method found",
                handler.findSuperMethods(element, project))
        } finally {
            unmockkObject(PluginDetectors)
        }
    }
}
