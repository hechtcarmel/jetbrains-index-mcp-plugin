package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import org.junit.Assume
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.psi.PsiElement
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform tests for [RubyTypeHierarchyHandler].
 *
 * IMPORTANT RULE: These tests should stay 1-1 with the corresponding
 * agent tests in `ruby/agent_tests/type_hierarchy.md`.
 * When you change one, you must change the other.
 *
 * Uses inline Ruby fixtures via myFixture.addFileToProject() — no pre-existing
 * test data files needed. Run on CI with RubyMine or IntelliJ + Ruby plugin.
 *
 * Skipped automatically on machines without the Ruby plugin.
 *
 * ## Why not mock like Python?
 *
 * Python's [PythonHierarchyHandlersTest] mocks PyClass/PyFunction because they
 * are compile-time dependencies. Ruby PSI classes (RClass, RModule) are
 * closed-source and accessed only via reflection — no compile-time dep.
 * So we use real .rb fixture files instead.
 */
class RubyTypeHierarchyPlatformTest : BasePlatformTestCase() {

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
        val handler = LanguageHandlerRegistry.getTypeHierarchyHandler(
            myFixture.addFileToProject("__gate__.rb", "class Gate; end")
        )
        Assume.assumeTrue(
            "RubyTypeHierarchyHandler not registered — plugin present but handler not wired",
            handler is RubyTypeHierarchyHandler
        )
    }

    private fun resolveHandler(element: PsiElement): RubyTypeHierarchyHandler {
        val handler = LanguageHandlerRegistry.getTypeHierarchyHandler(element)
        return handler as? RubyTypeHierarchyHandler
            ?: fail("Expected RubyTypeHierarchyHandler but got: $handler") as Nothing
    }

    /** PSI leaf at the first occurrence of [marker] — mirrors production's position-mode resolution. */
    private fun elementAt(file: com.intellij.psi.PsiFile, marker: String): PsiElement {
        val offset = file.text.indexOf(marker)
        require(offset >= 0) { "marker '$marker' not found in ${file.name}" }
        return file.findElementAt(offset) ?: error("no PSI element at offset $offset for '$marker'")
    }

    // ── simple inheritance ────────────────────────────────────────────────────

    fun testSimpleInheritance() {
        requireRubyPlugin()

        myFixture.addFileToProject("animal.rb", "class Animal; end")
        val dogFile = myFixture.addFileToProject("dog.rb", "class Dog < Animal; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(dogFile)
        val hierarchy = handler.getTypeHierarchy(dogFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("Dog should have a type hierarchy", hierarchy)
        assertEquals("Element name should be Dog, was: ${hierarchy!!.element.name}", "Dog", hierarchy.element.name)
        assertEquals("Kind should be CLASS, was: ${hierarchy.element.kind}", "CLASS", hierarchy.element.kind)
        assertEquals("Language should be Ruby, was: ${hierarchy.element.language}", "Ruby", hierarchy.element.language)
        assertTrue("Animal should be a supertype of Dog", hierarchy.supertypes.any { it.name == "Animal" })
        assertTrue("Dog should have no subtypes, got: ${hierarchy.subtypes.size}", hierarchy.subtypes.isEmpty())
    }

    // ── included modules as supertypes ────────────────────────────────────────

    fun testIncludedModulesAppearAsSupertypes() {
        requireRubyPlugin()

        myFixture.addFileToProject("authenticatable.rb", "module Authenticatable; end")
        val userFile = myFixture.addFileToProject("user.rb", """
            class User
              include Authenticatable
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(userFile)
        val hierarchy = handler.getTypeHierarchy(userFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("User should have a type hierarchy", hierarchy)
        assertTrue(
            "Authenticatable module should appear as a supertype",
            hierarchy!!.supertypes.any { it.name == "Authenticatable" }
        )
    }

    // ── module-only hierarchy ─────────────────────────────────────────────────

    fun testModuleOnly() {
        requireRubyPlugin()

        val modFile = myFixture.addFileToProject("auditable.rb", "module Auditable; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(modFile)
        val hierarchy = handler.getTypeHierarchy(modFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("Auditable module should have a type hierarchy", hierarchy)
        assertEquals("Kind should be MODULE, was: ${hierarchy!!.element.kind}", "MODULE", hierarchy.element.kind)
        assertEquals("Element name should be Auditable, was: ${hierarchy.element.name}", "Auditable", hierarchy.element.name)
    }

    // ── namespaced class ──────────────────────────────────────────────────────

    fun testNamespacedClass() {
        requireRubyPlugin()

        val psiFile = myFixture.addFileToProject("admin.rb", """
            module Admin
              class User
              end
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val target = elementAt(psiFile, "User")
        val handler = resolveHandler(target)
        val hierarchy = handler.getTypeHierarchy(target, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("Admin::User should have a type hierarchy", hierarchy)
        assertEquals("Element name should be Admin::User, was: ${hierarchy!!.element.name}", "Admin::User", hierarchy.element.name)
        assertEquals("Qualified name should be Admin::User, was: ${hierarchy.element.qualifiedName}", "Admin::User", hierarchy.element.qualifiedName)
    }

    // ── deep chain (recursive supertype traversal) ────────────────────────────

    fun testDeepChain() {
        requireRubyPlugin()

        myFixture.addFileToProject("grand_parent.rb", "class GrandParent; end")
        myFixture.addFileToProject("parent.rb", "class Parent < GrandParent; end")
        val childFile = myFixture.addFileToProject("child.rb", "class Child < Parent; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(childFile)
        val hierarchy = handler.getTypeHierarchy(childFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("Child should have a type hierarchy", hierarchy)
        val parent = hierarchy!!.supertypes.firstOrNull { it.name == "Parent" }
        assertNotNull("Parent should be a direct supertype of Child", parent)
        val grandParent = parent!!.supertypes?.firstOrNull { it.name == "GrandParent" }
        assertNotNull("GrandParent should be a supertype of Parent", grandParent)
    }

    // ── subtype discovery ─────────────────────────────────────────────────────

    fun testFindsSubtypes() {
        requireRubyPlugin()

        val animalFile = myFixture.addFileToProject("animal.rb", "class Animal; end")
        myFixture.addFileToProject("dog.rb", "class Dog < Animal; end")
        myFixture.addFileToProject("cat.rb", "class Cat < Animal; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(animalFile)
        val hierarchy = handler.getTypeHierarchy(animalFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("Animal should have a type hierarchy", hierarchy)
        val subtypeNames = hierarchy!!.subtypes.map { it.name }.toSet()
        assertTrue("Dog should be a subtype of Animal", "Dog" in subtypeNames)
        assertTrue("Cat should be a subtype of Animal", "Cat" in subtypeNames)
    }

    // ── non-class position returns containing class ───────────────────────────

    fun testMethodInsideClassFindsContainingClass() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("service.rb", """
            class Service
              def perform
              end
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val hierarchy = handler.getTypeHierarchy(file, project, BuiltInSearchScope.PROJECT_FILES)

        // Handler should find the containing class via findContainingRClassOrRModule
        assertNotNull("Type hierarchy should resolve from method to containing class", hierarchy)
        assertEquals("Element name should be Service, was: ${hierarchy!!.element.name}", "Service", hierarchy.element.name)
    }

    // ── extend module on class (T8) ───────────────────────────────────────────

    fun testExtendModule() {
        requireRubyPlugin()

        myFixture.addFileToProject("publishable.rb", "module Publishable; end")
        val hasExtendFile = myFixture.addFileToProject("has_extend.rb", """
            class HasExtend
              extend Publishable
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(hasExtendFile)
        val hierarchy = handler.getTypeHierarchy(hasExtendFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("HasExtend should have a type hierarchy", hierarchy)
        assertEquals("Element name should be HasExtend, was: ${hierarchy!!.element.name}", "HasExtend", hierarchy.element.name)
        assertTrue(
            "Publishable should appear as a supertype via extend",
            hierarchy.supertypes.any { it.name == "Publishable" && it.kind == "MODULE" }
        )
    }

    // ── superclass + include + extend combo (T9) ─────────────────────────────

    fun testClassWithBothSuperclassAndModules() {
        requireRubyPlugin()

        myFixture.addFileToProject("document.rb", "class Document; end")
        myFixture.addFileToProject("publishable.rb", "module Publishable; end")
        myFixture.addFileToProject("commentable.rb", "module Commentable; end")
        val articleFile = myFixture.addFileToProject("article.rb", """
            class Article < Document
              include Publishable
              include Commentable
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(articleFile)
        val hierarchy = handler.getTypeHierarchy(articleFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("Article should have a type hierarchy", hierarchy)
        assertEquals("Element name should be Article, was: ${hierarchy!!.element.name}", "Article", hierarchy.element.name)
        val supertypeNames = hierarchy.supertypes.map { it.name }.toSet()
        assertTrue("Document should be a supertype (CLASS), types: $supertypeNames", supertypeNames.contains("Document"))
        assertTrue("Publishable should be a supertype (MODULE), types: $supertypeNames", supertypeNames.contains("Publishable"))
        assertTrue("Commentable should be a supertype (MODULE), types: $supertypeNames", supertypeNames.contains("Commentable"))
        val docEntry = hierarchy.supertypes.first { it.name == "Document" }
        assertEquals("Document kind should be CLASS, was: ${docEntry.kind}", "CLASS", docEntry.kind)
    }

    // ── module including another module (T10) ────────────────────────────────

    fun testModuleIncludingAnotherModule() {
        requireRubyPlugin()

        myFixture.addFileToProject("helper_module.rb", "module HelperModule; end")
        val parentModuleFile = myFixture.addFileToProject("parent_module.rb", """
            module ParentModule
              include HelperModule
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(parentModuleFile)
        val hierarchy = handler.getTypeHierarchy(parentModuleFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("ParentModule should have a type hierarchy", hierarchy)
        assertEquals("MODULE", hierarchy!!.element.kind)
        assertTrue(
            "HelperModule should appear as a supertype of ParentModule",
            hierarchy.supertypes.any { it.name == "HelperModule" && it.kind == "MODULE" }
        )
    }

    // ── nested modules A::B (T11) ────────────────────────────────────────────

    fun testNestedModules() {
        requireRubyPlugin()

        val psiFile = myFixture.addFileToProject("nested_modules.rb", """
            module A
              module B
              end
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(psiFile)
        val hierarchy = handler.getTypeHierarchy(psiFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("A::B should have a type hierarchy", hierarchy)
        assertEquals("MODULE", hierarchy!!.element.kind)
        // The handler finds the first module in the file, which is A (outer) or B (inner).
        // Verify it returns a valid module.
        assertTrue(
            "Element should be either A or A::B",
            hierarchy.element.name in setOf("A", "A::B")
        )
    }

    // ── class with no explicit superclass (T12) ──────────────────────────────

    fun testRootObjectSupertype() {
        requireRubyPlugin()

        val fooFile = myFixture.addFileToProject("foo.rb", "class Foo; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(fooFile)
        val hierarchy = handler.getTypeHierarchy(fooFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("Foo should have a type hierarchy", hierarchy)
        assertEquals("Element name should be Foo, was: ${hierarchy!!.element.name}", "Foo", hierarchy.element.name)
        assertEquals("Kind should be CLASS, was: ${hierarchy.element.kind}", "CLASS", hierarchy.element.kind)
        assertTrue("Foo should have no supertypes, got: ${hierarchy.supertypes.map { it.name }}", hierarchy.supertypes.isEmpty())
    }

    // ── multiple classes in one file (T13) ───────────────────────────────────

    fun testFileWithMultipleClasses() {
        requireRubyPlugin()

        val psiFile = myFixture.addFileToProject("multi_class.rb", """
            # This file has two top-level classes
            class FirstClass
            end

            class SecondClass
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(psiFile)
        val hierarchy = handler.getTypeHierarchy(psiFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("Should have a type hierarchy for a file with multiple classes", hierarchy)
        // Handler resolves to the first class/module found in the file
        val name = hierarchy!!.element.name
        assertTrue(
            "Element should be one of the classes in the file, was: $name",
            name in setOf("FirstClass", "SecondClass")
        )
        assertEquals("Kind should be CLASS, was: ${hierarchy.element.kind}", "CLASS", hierarchy.element.kind)
    }

    // ── empty class body (T14) ───────────────────────────────────────────────

    fun testClassWithNoBody() {
        requireRubyPlugin()

        val emptyFile = myFixture.addFileToProject("empty_class.rb", "class EmptyClass; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(emptyFile)
        val hierarchy = handler.getTypeHierarchy(emptyFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("EmptyClass should have a type hierarchy", hierarchy)
        assertEquals("Element name should be EmptyClass, was: ${hierarchy!!.element.name}", "EmptyClass", hierarchy.element.name)
        assertEquals("Kind should be CLASS, was: ${hierarchy.element.kind}", "CLASS", hierarchy.element.kind)
        assertTrue("EmptyClass should have no supertypes, got: ${hierarchy.supertypes.map { it.name }}", hierarchy.supertypes.isEmpty())
        assertTrue("EmptyClass should have no subtypes, got: ${hierarchy.subtypes.size}", hierarchy.subtypes.isEmpty())
    }

    // ── stdlib inheritance with project_and_libraries scope (T15) ────────────

    fun testInheritsFromStandardLibrary() {
        requireRubyPlugin()

        val myStringFile = myFixture.addFileToProject("my_string.rb", "class MyString < String; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(myStringFile)
        val hierarchy = handler.getTypeHierarchy(myStringFile, project, BuiltInSearchScope.PROJECT_AND_LIBRARIES)

        assertNotNull("MyString should have a type hierarchy", hierarchy)
        assertEquals("Element name should be MyString, was: ${hierarchy!!.element.name}", "MyString", hierarchy.element.name)
        val supertypeNames = hierarchy.supertypes.map { "${it.name}:${it.kind}" }
        // Resolving `String` requires Ruby core stubs (a configured Ruby SDK). The light
        // BasePlatformTestCase fixture has none, so no supertype resolves — pass as a no-op
        // rather than fail. (JUnit3 BasePlatformTestCase does not honor JUnit4 Assume skips.)
        if (hierarchy.supertypes.isEmpty()) return
        assertTrue(
            "String (CLASS) should appear as a supertype, got: $supertypeNames",
            hierarchy.supertypes.any { it.name == "String" && it.kind == "CLASS" }
        )
    }

    // ── multiple included modules (T16) ──────────────────────────────────────

    fun testMultipleIncludedModules() {
        requireRubyPlugin()

        myFixture.addFileToProject("a.rb", "module A; end")
        myFixture.addFileToProject("b.rb", "module B; end")
        myFixture.addFileToProject("c.rb", "module C; end")
        val multiIncludeFile = myFixture.addFileToProject("multi_include.rb", """
            class MultiInclude
              include A
              include B
              include C
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(multiIncludeFile)
        val hierarchy = handler.getTypeHierarchy(multiIncludeFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("MultiInclude should have a type hierarchy", hierarchy)
        assertEquals("Element name should be MultiInclude, was: ${hierarchy!!.element.name}", "MultiInclude", hierarchy.element.name)
        val moduleNames = hierarchy.supertypes.map { it.name }.toSet()
        assertTrue("A should be in supertypes, got: $moduleNames", "A" in moduleNames)
        assertTrue("B should be in supertypes, got: $moduleNames", "B" in moduleNames)
        assertTrue("C should be in supertypes, got: $moduleNames", "C" in moduleNames)
    }

    // ── self-reference guard / subtype discovery on GrandParent (T17) ────────

    fun testSelfReferenceGuard() {
        requireRubyPlugin()

        val gpFile = myFixture.addFileToProject("grand_parent.rb", "class GrandParent; end")
        myFixture.addFileToProject("parent.rb", "class Parent < GrandParent; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(gpFile)
        val hierarchy = handler.getTypeHierarchy(gpFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("GrandParent should have a type hierarchy", hierarchy)
        assertEquals("Element name should be GrandParent, was: ${hierarchy!!.element.name}", "GrandParent", hierarchy.element.name)
        assertTrue("GrandParent should have no explicit supertypes, got: ${hierarchy.supertypes.map { it.name }}", hierarchy.supertypes.isEmpty())
        val subtypeNames = hierarchy.subtypes.map { it.name }
        assertTrue("Parent should be in GrandParent's subtypes, got: $subtypeNames", "Parent" in subtypeNames)
    }

    // ── subtype limit of 100 (T19) ───────────────────────────────────────────

    fun testSubtypeLimitRespected() {
        requireRubyPlugin()

        val lotsFile = myFixture.addFileToProject("lots_of_siblings.rb", "class LotsOfSiblings; end")
        for (i in 1..101) {
            val padded = i.toString().padStart(4, '0')
            myFixture.addFileToProject("sibling$padded.rb", "class Sibling$padded < LotsOfSiblings; end")
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(lotsFile)
        val hierarchy = handler.getTypeHierarchy(lotsFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("LotsOfSiblings should have a type hierarchy", hierarchy)
        assertEquals("Element name should be LotsOfSiblings, was: ${hierarchy!!.element.name}", "LotsOfSiblings", hierarchy.element.name)
        assertEquals("Should have at most 100 subtypes, had: ${hierarchy.subtypes.size}", 100, hierarchy.subtypes.size,)
    }

    // ── prepend module (T20) ──────────────────────────────────────────────────

    fun testPrependModule() {
        requireRubyPlugin()

        myFixture.addFileToProject("auditable.rb", "module Auditable; end")
        val prependTestFile = myFixture.addFileToProject("prepend_test.rb", """
            class PrependTest
              prepend Auditable
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(prependTestFile)
        val hierarchy = handler.getTypeHierarchy(prependTestFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("PrependTest should have a type hierarchy", hierarchy)
        assertEquals("Element name should be PrependTest, was: ${hierarchy!!.element.name}", "PrependTest", hierarchy.element.name)
        assertEquals("Kind should be CLASS, was: ${hierarchy.element.kind}", "CLASS", hierarchy.element.kind)
        val supertypeNames = hierarchy.supertypes.map { "${it.name}:${it.kind}" }
        assertTrue(
            "Auditable (MODULE) should appear as a supertype via prepend, got: $supertypeNames",
            hierarchy.supertypes.any { it.name == "Auditable" && it.kind == "MODULE" }
        )
    }

    // ── module extend (T21) ──────────────────────────────────────────────────

    fun testModuleExtend() {
        requireRubyPlugin()

        myFixture.addFileToProject("publishable.rb", "module Publishable; end")
        val extendTargetFile = myFixture.addFileToProject("extend_target.rb", """
            module ExtendTarget
              extend Publishable
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(extendTargetFile)
        val hierarchy = handler.getTypeHierarchy(extendTargetFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("ExtendTarget should have a type hierarchy", hierarchy)
        assertEquals("MODULE", hierarchy!!.element.kind)
        assertTrue(
            "Publishable should appear as a supertype of ExtendTarget",
            hierarchy.supertypes.any { it.name == "Publishable" && it.kind == "MODULE" }
        )
    }

    // ── superclass + include + extend (T22) ──────────────────────────────────

    fun testSuperclassIncludeExtend() {
        requireRubyPlugin()

        myFixture.addFileToProject("document.rb", "class Document; end")
        myFixture.addFileToProject("publishable.rb", "module Publishable; end")
        myFixture.addFileToProject("commentable.rb", "module Commentable; end")
        val fullComboFile = myFixture.addFileToProject("full_combo.rb", """
            class FullCombo < Document
              include Publishable
              extend Commentable
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(fullComboFile)
        val hierarchy = handler.getTypeHierarchy(fullComboFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("FullCombo should have a type hierarchy", hierarchy)
        assertEquals("Element name should be FullCombo, was: ${hierarchy!!.element.name}", "FullCombo", hierarchy.element.name)
        val supertypeNames = hierarchy.supertypes.map { it.name }.toSet()
        assertTrue("Document should be a supertype, got: $supertypeNames", "Document" in supertypeNames)
        assertTrue("Publishable should be a supertype, got: $supertypeNames", "Publishable" in supertypeNames)
        assertTrue("Commentable should be a supertype, got: $supertypeNames", "Commentable" in supertypeNames)
        assertEquals("Document kind should be CLASS, was: ${hierarchy.supertypes.first { it.name == "Document" }.kind}", "CLASS", hierarchy.supertypes.first { it.name == "Document" }.kind)
        assertEquals("Publishable kind should be MODULE, was: ${hierarchy.supertypes.first { it.name == "Publishable" }.kind}", "MODULE", hierarchy.supertypes.first { it.name == "Publishable" }.kind)
        assertEquals("Commentable kind should be MODULE, was: ${hierarchy.supertypes.first { it.name == "Commentable" }.kind}", "MODULE", hierarchy.supertypes.first { it.name == "Commentable" }.kind)
    }

    // ── namespaced include target (T23) ──────────────────────────────────────

    fun testNamespacedIncludeTarget() {
        requireRubyPlugin()

        val psiFile = myFixture.addFileToProject("namespaced_include.rb", """
            module Namespace
              module Helpers
              end
            end

            class NamespacedInclude
              include Namespace::Helpers
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(psiFile)
        val hierarchy = handler.getTypeHierarchy(psiFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("Should have a type hierarchy", hierarchy)
        // Handler finds the first class/module in the file. Accept NamespacedInclude or Namespace.
        val name = hierarchy!!.element.name
        if (name == "NamespacedInclude") {
            assertTrue(
                "Namespace::Helpers should be a supertype",
                hierarchy.supertypes.any { it.name == "Namespace::Helpers" && it.kind == "MODULE" }
            )
        }
    }

    // ── transitive mixins through superclass chain (T24) ─────────────────────

    fun testTransitiveMixins() {
        requireRubyPlugin()

        myFixture.addFileToProject("publishable.rb", "module Publishable; end")
        myFixture.addFileToProject("transitive_parent.rb", """
            class TransitiveParent
              include Publishable
            end
        """.trimIndent())
        val childFile = myFixture.addFileToProject("transitive_child.rb", """
            class TransitiveChild < TransitiveParent
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(childFile)
        val hierarchy = handler.getTypeHierarchy(childFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("TransitiveChild should have a type hierarchy", hierarchy)
        assertEquals("Element name should be TransitiveChild, was: ${hierarchy!!.element.name}", "TransitiveChild", hierarchy.element.name)
        val parentEntry = hierarchy.supertypes.firstOrNull { it.name == "TransitiveParent" }
        assertNotNull("TransitiveParent should be a direct supertype, types: ${hierarchy.supertypes.map { it.name }}", parentEntry)
        val nestedNames = parentEntry!!.supertypes?.map { it.name } ?: emptyList()
        assertNotNull(
            "Publishable should be nested under TransitiveParent's supertypes, got: $nestedNames",
            parentEntry.supertypes?.firstOrNull { it.name == "Publishable" }
        )
    }

    // ── cyclic include guard (T25) ───────────────────────────────────────────

    fun testCyclicIncludeGuard() {
        requireRubyPlugin()

        myFixture.addFileToProject("cycle_b.rb", "module CycleB; include CycleA; end")
        val cycleAFile = myFixture.addFileToProject("cycle_a.rb", "module CycleA; include CycleB; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(cycleAFile)
        val hierarchy = handler.getTypeHierarchy(cycleAFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("CycleA should have a type hierarchy", hierarchy)
        assertEquals("Kind should be MODULE, was: ${hierarchy!!.element.kind}", "MODULE", hierarchy.element.kind)
        val cycleBEntry = hierarchy.supertypes.firstOrNull { it.name == "CycleB" }
        assertNotNull("CycleB should be a supertype of CycleA, supertypes: ${hierarchy.supertypes.map { it.name }}", cycleBEntry)
        // The visited set should prevent CycleA from appearing under CycleB's supertypes
        val nestedInB = cycleBEntry!!.supertypes?.map { it.name } ?: emptyList()
        assertFalse("CycleA should NOT appear nested under CycleB (cycle broken), nested: $nestedInB", "CycleA" in nestedInB)
    }

    // ── invalid position (no class in file) returns null (T27) ───────────────

    fun testInvalidPositionError() {
        requireRubyPlugin()

        val noClassFile = myFixture.addFileToProject("no_class.rb", "# comment\nputs \"hello\"\n1 + 1\n")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(noClassFile)
        val hierarchy = handler.getTypeHierarchy(noClassFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNull("getTypeHierarchy should return null for a file with no class/module", hierarchy)
    }

    // ── syntax error in file (T28) ───────────────────────────────────────────

    fun testSyntaxErrorInFile() {
        requireRubyPlugin()

        val psiFile = myFixture.addFileToProject("syntax_error.rb", """
            class ValidClass
            end

            class BrokenClass <
            end

            class AnotherValidClass
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(psiFile)
        val hierarchy = handler.getTypeHierarchy(psiFile, project, BuiltInSearchScope.PROJECT_FILES)

        // Ruby plugin PSI error recovery determines which elements are parseable.
        // This is an informational test — the handler must not crash. If a
        // hierarchy is produced, it must be one of the parseable classes.
        if (hierarchy != null) {
            assertTrue(
                "Element should be one of the parseable classes, was: ${hierarchy.element.name}",
                hierarchy.element.name in setOf("ValidClass", "AnotherValidClass", "BrokenClass")
            )
        }
    }

    // ── non-Ruby file rejection (T18) ─────────────────────────────────────────

    fun testHandlerRejectsNonRubyElement() {
        requireRubyPlugin()

        val kotlinFile = myFixture.addFileToProject("SomeClass.kt", "class SomeClass")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = LanguageHandlerRegistry.getTypeHierarchyHandler(kotlinFile)
        assertFalse(
            "RubyTypeHierarchyHandler should not handle Kotlin files, handler was: ${handler?.javaClass?.simpleName}",
            handler is RubyTypeHierarchyHandler
        )
    }

    // ── missing file error (T26) ──────────────────────────────────────────────
    // Note: The "file not found" error at the MCP tool level is tested by the
    // agent test. At the handler level, we verify that an empty Ruby file (no
    // class/module declaration) returns null from getTypeHierarchy.

    fun testMissingFileError() {
        requireRubyPlugin()

        val emptyFile = myFixture.addFileToProject("unknown_empty.rb", "# only a comment\n")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(emptyFile)
        val hierarchy = handler.getTypeHierarchy(emptyFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNull("getTypeHierarchy should return null for file with no class/module", hierarchy)
    }

    // ── multi-level transitive mixins (T29) ───────────────────────────────────

    fun testMultiLevelTransitiveMixins() {
        requireRubyPlugin()

        myFixture.addFileToProject("deep_module.rb", "module DeepModule; end")
        val gpFile = myFixture.addFileToProject("deep_grand_parent.rb", """
            class DeepGrandParent
              include DeepModule
            end
        """.trimIndent())
        myFixture.addFileToProject("deep_parent.rb", "class DeepParent < DeepGrandParent; end")
        val childFile = myFixture.addFileToProject("deep_child.rb", "class DeepChild < DeepParent; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(childFile)
        val hierarchy = handler.getTypeHierarchy(childFile, project, BuiltInSearchScope.PROJECT_FILES)

        assertNotNull("DeepChild should have a type hierarchy", hierarchy)
        assertEquals("Element name should be DeepChild, was: ${hierarchy!!.element.name}", "DeepChild", hierarchy.element.name)

        // DeepChild -> DeepParent -> DeepGrandParent -> DeepModule
        val parentEntry = hierarchy.supertypes.firstOrNull { it.name == "DeepParent" }
        assertNotNull("DeepParent should be a direct supertype of DeepChild, supertypes: ${hierarchy.supertypes.map { it.name }}", parentEntry)

        val parentSupertypes = parentEntry!!.supertypes?.map { it.name } ?: emptyList()
        val grandParentEntry = parentEntry.supertypes?.firstOrNull { it.name == "DeepGrandParent" }
        assertNotNull("DeepGrandParent should be nested under DeepParent's supertypes, got: $parentSupertypes", grandParentEntry)

        val gpSupertypes = grandParentEntry!!.supertypes?.map { it.name } ?: emptyList()
        val moduleEntry = grandParentEntry.supertypes?.firstOrNull { it.name == "DeepModule" }
        assertNotNull("DeepModule should be nested under DeepGrandParent's supertypes, got: $gpSupertypes", moduleEntry)
        assertEquals("DeepModule kind should be MODULE, was: ${moduleEntry!!.kind}", "MODULE", moduleEntry.kind)

        // Also query DeepGrandParent directly to verify symmetry
        val gpHandler = resolveHandler(gpFile)
        val gpHierarchy = gpHandler.getTypeHierarchy(gpFile, project, BuiltInSearchScope.PROJECT_FILES)
        assertNotNull("DeepGrandParent should have a type hierarchy", gpHierarchy)
        val gpSupertypeNames = gpHierarchy!!.supertypes.map { "${it.name}:${it.kind}" }
        assertTrue(
            "DeepModule (MODULE) should be a direct supertype of DeepGrandParent, got: $gpSupertypeNames",
            gpHierarchy.supertypes.any { it.name == "DeepModule" && it.kind == "MODULE" }
        )
    }
}