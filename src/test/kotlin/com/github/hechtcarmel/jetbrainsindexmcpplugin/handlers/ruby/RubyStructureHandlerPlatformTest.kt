package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume

/**
 * Platform tests for [RubyStructureHandler].
 *
 * Uses inline Ruby fixtures via myFixture.addFileToProject().
 * Run on CI with RubyMine or IntelliJ + Ruby plugin.
 *
 * Skipped automatically on machines without the Ruby plugin.
 */
class RubyStructureHandlerPlatformTest : BasePlatformTestCase() {

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
        val handler = LanguageHandlerRegistry.getStructureHandler(
            myFixture.addFileToProject("__gate__.rb", "class Gate; end")
        )
        Assume.assumeTrue(
            "RubyStructureHandler not registered — plugin present but handler not wired",
            handler is RubyStructureHandler
        )
    }

    private fun resolveHandler() = RubyStructureHandler()

    // ── simple class ──────────────────────────────────────────────────────────

    fun testSimpleClass() {
        requireRubyPlugin()
        val file = myFixture.addFileToProject("simple.rb", "class Simple; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler()
        val nodes = handler.getFileStructure(file, project)

        assertEquals("Should have one top-level element, got: ${nodes.size}", 1, nodes.size)
        assertEquals("Element should be Simple, got: ${nodes[0].name}", "Simple", nodes[0].name)
        assertEquals("Kind should be CLASS, got: ${nodes[0].kind}", "CLASS", nodes[0].kind.name)
        assertEquals("Should have no children, got: ${nodes[0].children.size}", 0, nodes[0].children.size)
    }

    // ── simple module ─────────────────────────────────────────────────────────

    fun testSimpleModule() {
        requireRubyPlugin()
        val file = myFixture.addFileToProject("simple_module.rb", "module MyModule; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler()
        val nodes = handler.getFileStructure(file, project)

        assertEquals("Should have one top-level element, got: ${nodes.size}", 1, nodes.size)
        assertEquals("Element should be MyModule, got: ${nodes[0].name}", "MyModule", nodes[0].name)
        assertEquals("Kind should be MODULE, got: ${nodes[0].kind}", "MODULE", nodes[0].kind.name)
    }

    // ── class with inheritance ────────────────────────────────────────────────

    fun testClassWithInheritance() {
        requireRubyPlugin()
        myFixture.addFileToProject("animal.rb", "class Animal; end")
        val file = myFixture.addFileToProject("dog.rb", "class Dog < Animal; end")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler()
        val nodes = handler.getFileStructure(file, project)

        assertEquals("Should have one top-level element, got: ${nodes.size}", 1, nodes.size)
        assertEquals("Element should be Dog, got: ${nodes[0].name}", "Dog", nodes[0].name)
        assertNotNull("Should have a signature (superclass)", nodes[0].signature)
        assertTrue("Signature should mention Animal, got: ${nodes[0].signature}",
            nodes[0].signature!!.contains("Animal"))
    }

    // ── class with methods ────────────────────────────────────────────────────

    fun testClassWithMethods() {
        requireRubyPlugin()
        val file = myFixture.addFileToProject("calc.rb", """
            class Calculator
              def add(x, y)
                x + y
              end
              def compute
                add(1, 2)
              end
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler()
        val nodes = handler.getFileStructure(file, project)

        assertEquals("Should have one class, got: ${nodes.size}", 1, nodes.size)
        assertEquals("Class should be Calculator, got: ${nodes[0].name}", "Calculator", nodes[0].name)
        assertEquals("Should have 2 methods, got: ${nodes[0].children.size}",
            2, nodes[0].children.size)
        assertEquals("First method should be add, got: ${nodes[0].children[0].name}",
            "add", nodes[0].children[0].name)
        assertEquals("Second method should be compute, got: ${nodes[0].children[1].name}",
            "compute", nodes[0].children[1].name)
        // Signature derivation against real RMethod text (end-to-end).
        assertEquals("add signature should be (x, y), got: ${nodes[0].children[0].signature}",
            "(x, y)", nodes[0].children[0].signature)
        assertEquals("compute signature should be (), got: ${nodes[0].children[1].signature}",
            "()", nodes[0].children[1].signature)
    }

    // ── module with methods ───────────────────────────────────────────────────

    fun testModuleWithMethods() {
        requireRubyPlugin()
        val file = myFixture.addFileToProject("greetable.rb", """
            module Greetable
              def greet
                "hello"
              end
              def farewell(name)
                "bye \#{name}"
              end
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler()
        val nodes = handler.getFileStructure(file, project)

        assertEquals("Should have one module, got: ${nodes.size}", 1, nodes.size)
        assertEquals("Module should be Greetable, got: ${nodes[0].name}", "Greetable", nodes[0].name)
        assertEquals("Kind should be MODULE, got: ${nodes[0].kind}", "MODULE", nodes[0].kind.name)
        assertTrue("Should have at least 1 method, got: ${nodes[0].children.size}",
            nodes[0].children.isNotEmpty())
    }

    // ── multiple top-level classes ────────────────────────────────────────────

    fun testMultipleTopLevelClasses() {
        requireRubyPlugin()
        val file = myFixture.addFileToProject("multi.rb", """
            class First
              def one; end
            end
            class Second
              def two; end
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler()
        val nodes = handler.getFileStructure(file, project)

        assertEquals("Should have 2 top-level classes, got: ${nodes.size}", 2, nodes.size)
        assertEquals("First should be First, got: ${nodes[0].name}", "First", nodes[0].name)
        assertEquals("Second should be Second, got: ${nodes[1].name}", "Second", nodes[1].name)
    }

    // ── nested modules ────────────────────────────────────────────────────────

    fun testNestedModules() {
        requireRubyPlugin()
        val file = myFixture.addFileToProject("nested.rb", """
            module Outer
              module Inner
                class Nested
                  def inside
                    true
                  end
                end
              end
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler()
        val nodes = handler.getFileStructure(file, project)

        assertEquals("Should have 1 top-level module, got: ${nodes.size}", 1, nodes.size)
        assertEquals("Module should be Outer, got: ${nodes[0].name}", "Outer", nodes[0].name)
        assertEquals("Kind should be MODULE, got: ${nodes[0].kind}", "MODULE", nodes[0].kind.name)

        // Outer should have Inner as a child
        assertTrue("Outer should have at least 1 child, got: ${nodes[0].children.size}",
            nodes[0].children.isNotEmpty())
        val inner = nodes[0].children[0]
        assertEquals("Inner module should be named Inner, got: ${inner.name}", "Inner", inner.name)
        assertEquals("Inner should be a MODULE, got: ${inner.kind}", "MODULE", inner.kind.name)

        // Inner should have Nested as a child
        assertTrue("Inner should have at least 1 child, got: ${inner.children.size}",
            inner.children.isNotEmpty())
        val nested = inner.children[0]
        assertEquals("Nested class should be named Nested, got: ${nested.name}", "Nested", nested.name)
        assertEquals("Nested should be a CLASS, got: ${nested.kind}", "CLASS", nested.kind.name)

        // Nested should have inside as a method
        assertTrue("Nested should have at least 1 child, got: ${nested.children.size}",
            nested.children.isNotEmpty())
        assertEquals("Method should be inside, got: ${nested.children[0].name}", "inside", nested.children[0].name)
    }

    // ── empty file ────────────────────────────────────────────────────────────

    fun testEmptyFile() {
        requireRubyPlugin()
        val file = myFixture.addFileToProject("empty.rb", "")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler()
        val nodes = handler.getFileStructure(file, project)

        assertEquals("Empty file should have no elements, got: ${nodes.size}", 0, nodes.size)
    }

    // ── class with class methods ──────────────────────────────────────────────

    fun testClassWithClassMethods() {
        requireRubyPlugin()
        val file = myFixture.addFileToProject("class_methods.rb", """
            class Calculator
              def self.square(x)
                x * x
              end
              def add(x, y)
                x + y
              end
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler()
        val nodes = handler.getFileStructure(file, project)

        assertEquals("Should have one class, got: ${nodes.size}", 1, nodes.size)
        assertEquals("Should have 2 methods, got: ${nodes[0].children.size}",
            2, nodes[0].children.size)
        // Children should be sorted by line, so square comes first, then add
        assertEquals("First method should be square, got: ${nodes[0].children[0].name}",
            "square", nodes[0].children[0].name)
        assertEquals("Second method should be add, got: ${nodes[0].children[1].name}",
            "add", nodes[0].children[1].name)
        // `self.` class methods carry the "self" modifier; instance methods do not.
        assertTrue("square should carry the 'self' modifier, got: ${nodes[0].children[0].modifiers}",
            nodes[0].children[0].modifiers.contains("self"))
        assertEquals("square signature should be (x), got: ${nodes[0].children[0].signature}",
            "(x)", nodes[0].children[0].signature)
        assertFalse("add (instance method) should NOT carry 'self', got: ${nodes[0].children[1].modifiers}",
            nodes[0].children[1].modifiers.contains("self"))
    }

    // ── mixed class and module top-level ──────────────────────────────────────

    fun testMixedClassAndModule() {
        requireRubyPlugin()
        val file = myFixture.addFileToProject("mixed.rb", """
            module Services
            end
            class Application
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler()
        val nodes = handler.getFileStructure(file, project)

        assertEquals("Should have 2 top-level elements, got: ${nodes.size}", 2, nodes.size)
        assertEquals("First should be module Services, got: ${nodes[0].name}",
            "Services", nodes[0].name)
        assertEquals("First kind should be MODULE, got: ${nodes[0].kind}",
            "MODULE", nodes[0].kind.name)
        assertEquals("Second should be class Application, got: ${nodes[1].name}",
            "Application", nodes[1].name)
        assertEquals("Second kind should be CLASS, got: ${nodes[1].kind}",
            "CLASS", nodes[1].kind.name)
    }
}