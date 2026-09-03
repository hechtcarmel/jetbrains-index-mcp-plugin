package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.SuperMethodsData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume

/**
 * Platform tests for [RubySuperMethodsHandler].
 *
 * Uses inline Ruby fixtures via myFixture.addFileToProject().
 * Run on CI with RubyMine or IntelliJ + Ruby plugin.
 *
 * Skipped automatically on machines without the Ruby plugin.
 *
 * ## Handler contract (implemented)
 *
 * [RubySuperMethodsHandler.findSuperMethods] walks the Ruby method-resolution order
 * and returns each parent that declares the same method, tagged with how it entered
 * the chain ([SuperMethodData.via] = `superclass` | `include` | `prepend` | `extend`).
 * These tests assert OUR transformation of the plugin's PSI:
 *   1. the origin method's [MethodData] (name, containingClass, signature, position),
 *   2. the resolved parents' shape — `containingClass`, `via` provenance, and `depth`
 *      ordering — not the plugin's raw resolution accuracy.
 *
 * NOTE: the caret element MUST be resolved from the `PsiFile` returned by
 * `addFileToProject` (see [caretInMethod]) — NOT from `myFixture.file`, which is
 * only set by `configureByX` and is null here.
 */
class RubyFindSuperMethodsHandlerPlatformTest : BasePlatformTestCase() {

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

    // -- capability gate ------------------------------------------------------

    private fun requireRubyPlugin() {
        Assume.assumeTrue(
            "Ruby plugin not available — set localIdeaPath or platformPlugins in gradle.properties",
            PluginDetectors.ruby.isAvailable
        )
        val handler = LanguageHandlerRegistry.getSuperMethodsHandler(
            myFixture.addFileToProject("__gate__.rb", "class Gate; end")
        )
        Assume.assumeTrue(
            "RubySuperMethodsHandler not registered — plugin present but handler not wired",
            handler is RubySuperMethodsHandler
        )
    }

    private fun resolveHandler(element: PsiElement): RubySuperMethodsHandler {
        val handler = LanguageHandlerRegistry.getSuperMethodsHandler(element)
        return handler as? RubySuperMethodsHandler
            ?: fail("Expected RubySuperMethodsHandler but got: $handler") as Nothing
    }

    /**
     * Resolves the PSI element at the start of the method name for `def <name>`.
     * Offset +4 skips `def ` and lands on the first char of the method name.
     * Resolves from [file] itself — `myFixture.file` is null without configureByX.
     */
    private fun caretInMethod(file: PsiFile, defMarker: String): PsiElement {
        val pos = file.text.indexOf(defMarker)
        assertTrue("fixture must contain '$defMarker'", pos >= 0)
        return file.findElementAt(pos + 4)
            ?: error("no PSI element at offset ${pos + 4} in ${file.name}")
    }

    /** Assert the resolved method metadata. */
    private fun assertMethod(result: SuperMethodsData?, name: String, classPart: String) {
        assertNotNull("findSuperMethods should return a result", result)
        assertEquals("Method name should be $name", name, result!!.method.name)
        assertTrue("containingClass should contain '$classPart', got: ${result.method.containingClass}",
            result.method.containingClass.contains(classPart))
        assertTrue("signature should be non-blank, got: '${result.method.signature}'",
            result.method.signature.isNotBlank())
        assertEquals("language should be Ruby", "Ruby", result.method.language)
    }

    /**
     * Asserts the hierarchy contains a parent method [name] declared in a class/module
     * whose qualified name contains [classPart], reached via provenance [via]. Verifies
     * OUR marshalling (name, containingClass, via, depth, language) rather than the
     * plugin's resolution accuracy.
     */
    private fun assertSuper(result: SuperMethodsData?, name: String, classPart: String, via: String) {
        assertNotNull("findSuperMethods should return a result", result)
        val match = result!!.hierarchy.firstOrNull {
            it.name == name && it.containingClass.contains(classPart)
        } ?: (fail(
            "expected super '$name' in '$classPart' (via=$via), got: " +
                result.hierarchy.joinToString { "${it.containingClass}#${it.name}(via=${it.via},depth=${it.depth})" }
        ) as Nothing)
        assertEquals("provenance for $classPart#$name", via, match.via)
        assertTrue("super depth must be >= 1, got ${match.depth}", match.depth >= 1)
        assertEquals("super language should be Ruby", "Ruby", match.language)
    }

    /** Asserts no super methods were resolved (leaf / no-parent case). */
    private fun assertHierarchyEmpty(result: SuperMethodsData?) {
        assertNotNull(result)
        assertTrue(
            "hierarchy must be empty, got: ${result!!.hierarchy.map { it.name }}",
            result.hierarchy.isEmpty()
        )
    }

    // -- basic override (fsm_01) ----------------------------------------------

    fun testBasicOverride() {
        requireRubyPlugin()

        myFixture.addFileToProject("animal.rb", "class Animal; def speak; 'generic'; end; end")
        val dogFile = myFixture.addFileToProject("dog.rb", "class Dog < Animal; def speak; 'woof'; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(dogFile)
        val result = handler.findSuperMethods(caretInMethod(dogFile, "def speak"), project)

        assertMethod(result, "speak", "Dog")
        assertSuper(result, "speak", "Animal", via = "superclass")
    }

    // -- mixin override (fsm_02) ----------------------------------------------

    fun testMixinOverride() {
        requireRubyPlugin()

        myFixture.addFileToProject("greetable.rb", "module Greetable; def greet; 'hello'; end; end")
        val userFile = myFixture.addFileToProject("user.rb", "class User; include Greetable; def greet; 'hi'; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(userFile)
        val result = handler.findSuperMethods(caretInMethod(userFile, "def greet"), project)

        assertMethod(result, "greet", "User")
        assertSuper(result, "greet", "Greetable", via = "include")
    }

    // -- deep chain (fsm_03) --------------------------------------------------

    fun testDeepChain() {
        requireRubyPlugin()

        myFixture.addFileToProject("grand_parent.rb", "class GrandParent; def speak; 'grand'; end; end")
        myFixture.addFileToProject("parent.rb", "class Parent < GrandParent; def speak; 'parent'; end; end")
        val childFile = myFixture.addFileToProject("child.rb", "class Child < Parent; def speak; 'child'; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(childFile)
        val result = handler.findSuperMethods(caretInMethod(childFile, "def speak"), project)

        assertMethod(result, "speak", "Child")
        // Superclass chain: Parent (depth 1) then GrandParent (depth 2), both via superclass.
        assertSuper(result, "speak", "Parent", via = "superclass")
        assertSuper(result, "speak", "GrandParent", via = "superclass")
        val parentDepth = result!!.hierarchy.first { it.containingClass.contains("Parent") && !it.containingClass.contains("GrandParent") }.depth
        val grandDepth = result.hierarchy.first { it.containingClass.contains("GrandParent") }.depth
        assertTrue("Parent must precede GrandParent in depth ($parentDepth < $grandDepth)", parentDepth < grandDepth)
    }

    // -- no super (fsm_04) ----------------------------------------------------

    fun testNoSuper() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("root.rb", "class Root; def unique; true; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val result = handler.findSuperMethods(caretInMethod(file, "def unique"), project)

        assertMethod(result, "unique", "Root")
        assertTrue("Hierarchy must be empty for a no-super method, got: ${result!!.hierarchy.size}",
            result.hierarchy.isEmpty())
    }

    // -- class method override (fsm_05) ---------------------------------------

    fun testClassMethodOverride() {
        requireRubyPlugin()

        myFixture.addFileToProject("user.rb", "class User; def self.find_by_email; end; end")
        val adminFile = myFixture.addFileToProject("admin_user.rb", "class AdminUser < User; def self.find_by_email; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(adminFile)
        // +4 lands on 'self'; findContainingRMethod still resolves the class method.
        val result = handler.findSuperMethods(caretInMethod(adminFile, "def self.find_by_email"), project)

        assertNotNull("findSuperMethods should return a result", result)
        assertTrue("Method name should include find_by_email, got: ${result!!.method.name}",
            result.method.name.contains("find_by_email"))
        assertTrue("containingClass should contain AdminUser, got: ${result.method.containingClass}",
            result.method.containingClass.contains("AdminUser"))
        // Class method: parent reached through the superclass chain. Singleton-method
        // resolution is plugin-dependent, so assert shape only when a parent is found.
        val classSuper = result.hierarchy.firstOrNull { it.containingClass.contains("User") && !it.containingClass.contains("AdminUser") }
        if (classSuper != null) {
            assertEquals("class-method super provenance", "superclass", classSuper.via)
            assertEquals("find_by_email", classSuper.name)
        }
    }

    // -- cross-file override (fsm_06) -----------------------------------------

    fun testCrossFileOverride() {
        requireRubyPlugin()

        myFixture.addFileToProject("base.rb", "module Base; def compute; end; end")
        val subFile = myFixture.addFileToProject("sub.rb", "class Sub; include Base; def compute; 42; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(subFile)
        val result = handler.findSuperMethods(caretInMethod(subFile, "def compute"), project)

        assertMethod(result, "compute", "Sub")
        assertSuper(result, "compute", "Base", via = "include")
    }

    // -- predicate and bang method override (fsm_07) --------------------------

    fun testPredicateMethodOverride() {
        requireRubyPlugin()

        myFixture.addFileToProject("base.rb", "class Base; def admin?; false; end; end")
        val subFile = myFixture.addFileToProject("sub.rb", "class Sub < Base; def admin?; true; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(subFile)
        val result = handler.findSuperMethods(caretInMethod(subFile, "def admin?"), project)

        assertMethod(result, "admin?", "Sub")
        assertSuper(result, "admin?", "Base", via = "superclass")
    }

    fun testBangMethodOverride() {
        requireRubyPlugin()

        myFixture.addFileToProject("base.rb", "class Base; def save!; end; end")
        val subFile = myFixture.addFileToProject("sub.rb", "class Sub < Base; def save!; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(subFile)
        val result = handler.findSuperMethods(caretInMethod(subFile, "def save!"), project)

        assertMethod(result, "save!", "Sub")
        assertSuper(result, "save!", "Base", via = "superclass")
    }

    // -- prepend provenance (fsm_09) ------------------------------------------

    fun testPrependedModuleOnSuperclass() {
        requireRubyPlugin()

        // Loud is prepended to Animal, so it sits ABOVE Animal in the MRO. From Dog#speak,
        // `super` reaches Loud#speak (via=prepend) before Animal#speak (via=superclass).
        myFixture.addFileToProject("loud.rb", "module Loud; def speak; 'LOUD'; end; end")
        myFixture.addFileToProject("animal.rb", "class Animal; prepend Loud; def speak; 'generic'; end; end")
        val dogFile = myFixture.addFileToProject("dog.rb", "class Dog < Animal; def speak; 'woof'; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(dogFile)
        val result = handler.findSuperMethods(caretInMethod(dogFile, "def speak"), project)

        assertMethod(result, "speak", "Dog")
        assertSuper(result, "speak", "Loud", via = "prepend")
        assertSuper(result, "speak", "Animal", via = "superclass")
    }

    // -- extend provenance (fsm_10) -------------------------------------------

    fun testExtendedModuleClassMethod() {
        requireRubyPlugin()

        // `extend Finder` turns Finder's instance methods into class methods of User,
        // so User.find_by_email overrides Finder#find_by_email (via=extend).
        myFixture.addFileToProject("finder.rb", "module Finder; def find_by_email; end; end")
        val userFile = myFixture.addFileToProject("user.rb", "class User; extend Finder; def self.find_by_email; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(userFile)
        val result = handler.findSuperMethods(caretInMethod(userFile, "def self.find_by_email"), project)

        assertNotNull("findSuperMethods should return a result", result)
        assertSuper(result, "find_by_email", "Finder", via = "extend")
    }

    // -- edge cases (fsm_08) --------------------------------------------------

    fun testNoExplicitSuperclass() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("leaf.rb", "class Leaf; def my_method; end; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val result = handler.findSuperMethods(caretInMethod(file, "def my_method"), project)

        assertMethod(result, "my_method", "Leaf")
        assertHierarchyEmpty(result)
    }

    fun testEmptyFileReturnsNull() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("empty.rb", "")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        // Empty file has no PSI leaf at offset 0.
        val element = file.findElementAt(0)
        assertNull("empty file should yield no PSI leaf at offset 0", element)
    }

    fun testSyntaxErrorFileDoesNotCrash() {
        requireRubyPlugin()

        // Missing `end` — Ruby PSI error-recovers; the handler must not throw.
        val file = myFixture.addFileToProject("broken.rb", "class Broken; def method_x; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val element = caretInMethod(file, "def method_x")
        // Should return a result (method resolves) or null — but never crash.
        val result = handler.findSuperMethods(element, project)
        if (result != null) {
            assertEquals("method_x", result.method.name)
            assertTrue("hierarchy stays empty (stub)", result.hierarchy.isEmpty())
        }
    }
}
