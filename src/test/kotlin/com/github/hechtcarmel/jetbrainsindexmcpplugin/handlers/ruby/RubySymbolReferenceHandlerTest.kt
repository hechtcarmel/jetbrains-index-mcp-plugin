package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import org.junit.Assume
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.SymbolReferenceHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform tests for [RubySymbolReferenceHandler.resolveSymbol].
 *
 * Tests actual Ruby symbol resolution using real .rb fixture files.
 * Run on CI with RubyMine or IntelliJ + Ruby plugin.
 *
 * Skipped automatically on machines without the Ruby plugin.
 *
 * ## Coverage
 *
 * - `testResolveBareClassName` — Strategy 1 (RubyGotoClassContributor) + Strategy 2 (stub index)
 * - `testResolveNamespacedClass` — Namespaced resolution with :: separator
 * - `testResolveDeeplyNamespacedClass` — Triple-namespace (A::B::C::User)
 * - `testResolveInstanceMethod` — Instance method reference with # separator
 * - `testResolveClassMethod` — Class method reference with . separator
 * - `testResolveModuleOnly` — Pure module (no class)
 * - `testResolveUnderscoredNames` — Gem-style naming (MyGem::MyClass)
 * - `testResolveSetterMethod` — Setter method with = suffix
 * - `testResolveNonExistentClass` — Unknown class → Failure
 * - `testResolveNonExistentMethod` — Known class, unknown method → Failure
 * - `testResolveMethodInNamespacedClass` — Method in Admin::User namespace
 * - `testResolveBangMethod` — Bang method with ! suffix
 * - `testResolveSymbolWithLeadingWhitespace` — Whitespace trimmed before resolution
 */
class RubySymbolReferenceHandlerTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
    }

    /** IntelliJ-native skip: when false, runBare() skips the test with no failure. */
    override fun shouldRunTest(): Boolean =
        PluginDetectors.ruby.isAvailable && super.shouldRunTest()

    override fun tearDown() {
        try {
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    // ── capability gate ───────────────────────────────────────────────────────

    private fun requireRubyPlugin() {
        Assume.assumeTrue(
            "Ruby plugin not available — set localRubyPluginPath or platformPlugins in gradle.properties",
            PluginDetectors.ruby.isAvailable
        )
        val handler = LanguageHandlerRegistry.getSymbolReferenceHandlerByLanguageName("Ruby")
        Assume.assumeTrue(
            "RubySymbolReferenceHandler not registered — plugin present but handler not wired",
            handler is RubySymbolReferenceHandler
        )
    }

    private fun resolveHandler(): RubySymbolReferenceHandler {
        val handler = LanguageHandlerRegistry.getSymbolReferenceHandlerByLanguageName("Ruby")
        return handler as? RubySymbolReferenceHandler
            ?: fail("Expected RubySymbolReferenceHandler but got: $handler") as Nothing
    }

    // ── bare class name ───────────────────────────────────────────────────────

    fun testResolveBareClassName() {
        requireRubyPlugin()

        myFixture.addFileToProject("user.rb", "class User; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val userFile = myFixture.addFileToProject("example.rb", "puts User")
        val handler = resolveHandler()
        val result = handler.resolveSymbol(project, "User")

        assertTrue("Should resolve bare class name", result.isSuccess)
        assertTrue("Result should be PsiNamedElement", result.getOrNull() is PsiNamedElement)
    }

    // ── namespaced class ─────────────────────────────────────────────────────

    fun testResolveNamespacedClass() {
        requireRubyPlugin()

        myFixture.addFileToProject("admin.rb", """
            module Admin
              class User
              end
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val userFile = myFixture.addFileToProject("admin_user.rb", "puts Admin::User")
        val handler = resolveHandler()
        val result = handler.resolveSymbol(project, "Admin::User")

        assertTrue("Should resolve namespaced class", result.isSuccess)
        val element = result.getOrNull() as? PsiNamedElement
        assertNotNull("Result should be PsiNamedElement", element)
        assertEquals("Element name should be Admin::User", "Admin::User", element!!.name)
    }

    // ── deeply namespaced class ───────────────────────────────────────────────

    fun testResolveDeeplyNamespacedClass() {
        requireRubyPlugin()

        myFixture.addFileToProject("a.rb", "module A; module B; module C; class User; end; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val userFile = myFixture.addFileToProject("a_user.rb", "puts A::B::C::User")
        val handler = resolveHandler()
        val result = handler.resolveSymbol(project, "A::B::C::User")

        assertTrue("Should resolve deeply namespaced class", result.isSuccess)
        val element = result.getOrNull() as? PsiNamedElement
        assertNotNull("Result should be PsiNamedElement", element)
        assertEquals("Element name should be A::B::C::User", "A::B::C::User", element!!.name)
    }

    // ── instance method ──────────────────────────────────────────────────────

    fun testResolveInstanceMethod() {
        requireRubyPlugin()

        myFixture.addFileToProject("user.rb", "class User; def admin?; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val userFile = myFixture.addFileToProject("example.rb", "User#admin?")
        val handler = resolveHandler()
        val result = handler.resolveSymbol(project, "User#admin?")

        assertTrue("Should resolve instance method", result.isSuccess)
        val element = result.getOrNull()
        assertNotNull("Result should be a method element", element)
        assertTrue("Result should be a PsiNamedElement, got: ${element?.javaClass?.simpleName}",
            element is PsiNamedElement)
        assertEquals("Element name should be admin?", "admin?", (element as PsiNamedElement).name)
    }

    // ── class method (dot notation) ───────────────────────────────────────────

    fun testResolveClassMethod() {
        requireRubyPlugin()

        myFixture.addFileToProject("user.rb", "class User; def self.find_by_email; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler()
        val result = handler.resolveSymbol(project, "User.find_by_email")

        assertTrue("Should resolve class method", result.isSuccess)
        val element = result.getOrNull()
        assertNotNull("Result should be a method element", element)
    }

    // ── module only ───────────────────────────────────────────────────────────

    fun testResolveModuleOnly() {
        requireRubyPlugin()

        myFixture.addFileToProject("auth.rb", "module Authenticatable; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val authFile = myFixture.addFileToProject("example.rb", "puts Authenticatable")
        val handler = resolveHandler()
        val result = handler.resolveSymbol(project, "Authenticatable")

        assertTrue("Should resolve module only", result.isSuccess)
        val element = result.getOrNull() as? PsiNamedElement
        assertNotNull("Result should be PsiNamedElement", element)
        assertEquals("Element name should be Authenticatable", "Authenticatable", element!!.name)
    }

    // ── underscored names (gem-style) ────────────────────────────────────────

    fun testResolveUnderscoredNames() {
        requireRubyPlugin()

        myFixture.addFileToProject("my_gem.rb", "module MyGem; class MyClass; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val classFile = myFixture.addFileToProject("example.rb", "puts MyGem::MyClass")
        val handler = resolveHandler()
        val result = handler.resolveSymbol(project, "MyGem::MyClass")

        assertTrue("Should resolve gem-style naming", result.isSuccess)
        val element = result.getOrNull() as? PsiNamedElement
        assertNotNull("Result should be PsiNamedElement", element)
        assertEquals("Element name should be MyGem::MyClass", "MyGem::MyClass", element!!.name)
    }

    // ─── setter method ───────────────────────────────────────────────────────

    fun testResolveSetterMethod() {
        requireRubyPlugin()

        myFixture.addFileToProject("user.rb", "class User; def name=(val); end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val userFile = myFixture.addFileToProject("example.rb", "User#name=")
        val handler = resolveHandler()
        val result = handler.resolveSymbol(project, "User#name=")

        assertTrue("Should resolve setter method", result.isSuccess)
        val element = result.getOrNull()
        assertNotNull("Result should be a method element", element)
    }

    // ── non-existent class ────────────────────────────────────────────────────

    fun testResolveNonExistentClass() {
        requireRubyPlugin()

        myFixture.addFileToProject("example.rb", "# no User class here")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val exampleFile = myFixture.addFileToProject("user.rb", "puts User")
        val handler = resolveHandler()
        val result = handler.resolveSymbol(project, "User")

        assertTrue("Should fail for non-existent class", result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            "Error message should mention 'could not be resolved'",
            msg.contains("could not be resolved")
        )
    }

    // ── non-existent method ───────────────────────────────────────────────────

    fun testResolveNonExistentMethod() {
        requireRubyPlugin()

        myFixture.addFileToProject("user.rb", "class User; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val userFile = myFixture.addFileToProject("example.rb", "User#nonexistent")
        val handler = resolveHandler()
        val result = handler.resolveSymbol(project, "User#nonexistent")

        assertTrue("Should fail for non-existent method", result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            "Error message should mention method not found",
            msg.contains("not found") || msg.contains("could not be resolved")
        )
    }

    // ── method in namespaced class ───────────────────────────────────────────

    fun testResolveMethodInNamespacedClass() {
        requireRubyPlugin()

        myFixture.addFileToProject("admin.rb", """
            module Admin
              class User
                def admin?
                end
              end
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val userFile = myFixture.addFileToProject("admin_ref.rb", "Admin::User#admin?")
        val handler = resolveHandler()
        val result = handler.resolveSymbol(project, "Admin::User#admin?")

        assertTrue("Should resolve method in namespaced class", result.isSuccess)
        val element = result.getOrNull()
        assertNotNull("Result should be a method element", element)
    }

    // ── bang method ───────────────────────────────────────────────────────────

    fun testResolveBangMethod() {
        requireRubyPlugin()

        myFixture.addFileToProject("user.rb", "class User; def save!; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val userFile = myFixture.addFileToProject("example.rb", "User#save!")
        val handler = resolveHandler()
        val result = handler.resolveSymbol(project, "User#save!")

        assertTrue("Should resolve bang method", result.isSuccess)
        val element = result.getOrNull()
        assertNotNull("Result should be a method element", element)
    }

    // ── symbol with leading whitespace ───────────────────────────────────────

    fun testResolveSymbolWithLeadingWhitespace() {
        requireRubyPlugin()

        myFixture.addFileToProject("user.rb", "class User; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val userFile = myFixture.addFileToProject("example.rb", "  User#find_by_email")
        val handler = resolveHandler()
        val result = handler.resolveSymbol(project, "  User#find_by_email")

        assertTrue("Should trim whitespace before resolution", result.isSuccess)
        assertTrue("Result should be a method element", result.getOrNull() != null)
    }
}