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
 * Pure (non-fixture) unit tests for [RubyCallHierarchyHandler].
 *
 * These run without the Ruby plugin — no PSI, no IDE. They test handler
 * metadata, `canHandle` gating, registry wiring, and the null contract when
 * no containing method can be resolved.
 *
 * **Intentionally NOT tested here** (real behavior lives in the platform test):
 *   - `getMethodKey` format, the `visited` cycle-guard, and MAX_* caps — these
 *     depend on real Ruby PSI (`findContainingRClassOrRModule`, line numbers).
 *     Asserting them via local set/string copies is a coverage illusion, and
 *     asserting them via private-method reflection is brittle. See
 *     [RubyCallHierarchyHandlerPlatformTest].
 */
class RubyCallHierarchyHandlerUnitTest : TestCase() {

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.clear()
    }

    override fun tearDown() {
        super.tearDown()
        LanguageHandlerRegistry.clear()
    }

    // ── Metadata ───────────────────────────────────────────────────────────────

    fun testLanguageIdIsRuby() {
        assertEquals("languageId must be 'Ruby'", "Ruby", RubyCallHierarchyHandler().languageId)
    }

    fun testIsAvailableWithoutPlugin() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns false
        every { PluginDetectors.ruby } returns rubyDetector
        try {
            assertFalse("isAvailable should be false without Ruby plugin", RubyCallHierarchyHandler().isAvailable())
        } finally {
            unmockkObject(PluginDetectors)
        }
    }

    // ── canHandle gating ────────────────────────────────────────────────────────

    fun testCanHandleReturnsFalseWithoutRubyPlugin() {
        // isAvailable() short-circuits — canHandle is false without touching language.
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns false
        every { PluginDetectors.ruby } returns rubyDetector
        try {
            assertFalse("canHandle must be false without Ruby plugin",
                RubyCallHierarchyHandler().canHandle(mockk<com.intellij.psi.PsiElement>()))
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
                RubyCallHierarchyHandler().canHandle(element))
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
                RubyCallHierarchyHandler().canHandle(element))
        } finally {
            unmockkObject(PluginDetectors)
        }
    }

    // ── Registry Wiring ────────────────────────────────────────────────────────

    fun testRegistryExposesRubyCallHierarchyWhenForcedAvailable() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns true
        every { PluginDetectors.ruby } returns rubyDetector
        LanguageHandlerRegistry.clear()
        try {
            LanguageHandlerRegistry.registerCallHierarchyHandler(RubyCallHierarchyHandler())
            assertTrue(
                "'Ruby' must be advertised as a supported call-hierarchy language",
                LanguageHandlerRegistry.getSupportedLanguagesForCallHierarchy().contains("Ruby")
            )
        } finally {
            LanguageHandlerRegistry.clear()
            unmockkObject(PluginDetectors)
        }
    }

    // ── Null contract without Ruby PSI ────────────────────────────────────────

    fun testGetCallHierarchyReturnsNullWithoutRubyPlugin() {
        // rMethodClass lazy val stays null (Class.forName fails), so
        // findContainingRMethod returns null and getCallHierarchy returns null.
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns true
        every { PluginDetectors.ruby } returns rubyDetector
        try {
            val handler = RubyCallHierarchyHandler()
            val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
            // PsiTreeUtil.getParentOfType walks parent chain — stub parent to null.
            val element = mockk<com.intellij.psi.PsiElement>(relaxUnitFun = true) {
                every { parent } returns null
            }
            assertNull("getCallHierarchy must return null when findContainingRMethod fails",
                handler.getCallHierarchy(element, project, "callers", 1))
        } finally {
            unmockkObject(PluginDetectors)
        }
    }

    fun testGetCallHierarchyReturnsNullForNonRubyElement() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns true
        every { PluginDetectors.ruby } returns rubyDetector
        try {
            val handler = RubyCallHierarchyHandler()
            val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
            val element = mockk<com.intellij.psi.PsiElement>(relaxUnitFun = true) {
                every { parent } returns null
            }
            assertNull("getCallHierarchy must return null when no containing method found",
                handler.getCallHierarchy(element, project, "callees", 1))
        } finally {
            unmockkObject(PluginDetectors)
        }
    }
}
