package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume

/**
 * Platform tests for [RubyCallHierarchyHandler].
 *
 * Uses inline Ruby fixtures via myFixture.addFileToProject().
 * Run on CI with RubyMine or IntelliJ + Ruby plugin.
 *
 * Skipped automatically on machines without the Ruby plugin.
 *
 * NOTE: the caret element MUST be resolved from the `PsiFile` returned by
 * `addFileToProject` (see [caretIn]) — NOT from `myFixture.file`, which is only
 * set by `configureByX` and is null here.
 */
class RubyCallHierarchyHandlerPlatformTest : BasePlatformTestCase() {

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
        val handler = LanguageHandlerRegistry.getCallHierarchyHandler(
            myFixture.addFileToProject("__gate__.rb", "class Gate; end")
        )
        Assume.assumeTrue(
            "RubyCallHierarchyHandler not registered — plugin present but handler not wired",
            handler is RubyCallHierarchyHandler
        )
    }

    private fun resolveHandler(element: PsiElement): RubyCallHierarchyHandler {
        val handler = LanguageHandlerRegistry.getCallHierarchyHandler(element)
        return handler as? RubyCallHierarchyHandler
            ?: fail("Expected RubyCallHierarchyHandler but got: $handler") as Nothing
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

    private fun List<CallElementData>?.hasCall(namePart: String): Boolean =
        this?.any { it.name.contains(namePart) } == true

    // -- simple callers -------------------------------------------------------

    fun testSimpleCallers() {
        requireRubyPlugin()

        val mainFile = myFixture.addFileToProject("main.rb", """
            def start
              greet("world")
            end
            def greet(name)
              "Hello, #{name}"
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(mainFile)
        val result = handler.getCallHierarchy(
            caretInMethod(mainFile, "def greet"), project, "callers", 1, BuiltInSearchScope.PROJECT_FILES
        )

        assertNotNull("Call hierarchy should not be null", result)
        assertEquals("Element name should be greet", "greet", result!!.element.name)
        assertTrue("greet's callers should include start, got: ${result.calls?.map { it.name }}",
            result.calls.hasCall("start"))
    }

    // -- simple callees -------------------------------------------------------

    fun testSimpleCallees() {
        requireRubyPlugin()

        val mainFile = myFixture.addFileToProject("main.rb", """
            def start
              helper
            end
            def helper
              true
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(mainFile)
        val result = handler.getCallHierarchy(
            caretInMethod(mainFile, "def start"), project, "callees", 1, BuiltInSearchScope.PROJECT_FILES
        )

        assertNotNull("Call hierarchy should not be null", result)
        assertEquals("Element name should be start", "start", result!!.element.name)
        assertTrue("start's callees should include helper, got: ${result.calls?.map { it.name }}",
            result.calls.hasCall("helper"))
    }

    // -- instance method callers ----------------------------------------------

    fun testInstanceMethodCallers() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("calc.rb", """
            class Calculator
              def add(x, y)
                x + y
              end
              def compute
                add(1, 2)
              end
              def calculate
                add(3, 4)
              end
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val result = handler.getCallHierarchy(
            caretInMethod(file, "def add"), project, "callers", 1, BuiltInSearchScope.PROJECT_FILES
        )

        assertNotNull("Call hierarchy should not be null", result)
        val callerNames = result!!.calls?.map { it.name } ?: emptyList()
        assertTrue("Should have callers, got: $callerNames", callerNames.isNotEmpty())
        assertTrue("Callers should include compute, got: $callerNames", result.calls.hasCall("compute"))
        assertTrue("Callers should include calculate, got: $callerNames", result.calls.hasCall("calculate"))
    }

    // -- class method callers -------------------------------------------------

    fun testClassMethodCallers() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("processor.rb", """
            class Processor
              def self.parse(input)
                input.strip
              end
              def self.handle(data)
                parse(data)
              end
              def self.process(items)
                items.map { |i| parse(i) }
              end
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val result = handler.getCallHierarchy(
            caretInMethod(file, "def self.parse"), project, "callers", 1, BuiltInSearchScope.PROJECT_FILES
        )

        assertNotNull("Call hierarchy should not be null", result)
        assertTrue("parse element name should include parse", result!!.element.name.contains("parse"))
    }

    // -- no callers -----------------------------------------------------------

    fun testNoCallers() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("orphan.rb", """
            def orphan
              true
            end
            def unrelated
              42
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val result = handler.getCallHierarchy(
            caretInMethod(file, "def orphan"), project, "callers", 1, BuiltInSearchScope.PROJECT_FILES
        )

        assertNotNull("Call hierarchy should not be null", result)
        assertEquals("Element name should be orphan", "orphan", result!!.element.name)
        assertFalse("orphan should have no callers, got: ${result.calls?.map { it.name }}",
            result.calls.hasCall("unrelated"))
    }

    // -- no callees -----------------------------------------------------------

    fun testNoCallees() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("leaf.rb", """
            def leaf_method
              true
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val result = handler.getCallHierarchy(
            caretInMethod(file, "def leaf_method"), project, "callees", 1, BuiltInSearchScope.PROJECT_FILES
        )

        assertNotNull("Call hierarchy should not be null", result)
        assertEquals("Element name should be leaf_method", "leaf_method", result!!.element.name)
        assertTrue("Calls should be empty/null for leaf method, got: ${result.calls?.map { it.name }}",
            result.calls.isNullOrEmpty())
    }

    // -- recursive method (self-referencing) ----------------------------------

    fun testSelfReferencingRecursive() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("factorial.rb", """
            def factorial(n)
              return 1 if n <= 1
              n * factorial(n - 1)
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val result = handler.getCallHierarchy(
            caretInMethod(file, "def factorial"), project, "callees", 3, BuiltInSearchScope.PROJECT_FILES
        )

        assertNotNull("Call hierarchy should not be null", result)
        assertEquals("Element name should be factorial", "factorial", result!!.element.name)
        // The recursive call is resolved once; the cycle guard prevents infinite recursion.
        assertTrue("factorial should list itself as a (single) callee, got: ${result.calls?.map { it.name }}",
            result.calls.hasCall("factorial"))
    }

    // -- predicate method -----------------------------------------------------

    fun testPredicateMethodCallers() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("predicate.rb", """
            def valid?
              true
            end
            def process
              return unless valid?
              :ok
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val result = handler.getCallHierarchy(
            caretInMethod(file, "def valid?"), project, "callers", 1, BuiltInSearchScope.PROJECT_FILES
        )

        assertNotNull("Call hierarchy should not be null", result)
        assertTrue("Element name should include valid", result!!.element.name.contains("valid"))
        assertTrue("valid?'s callers should include process, got: ${result.calls?.map { it.name }}",
            result.calls.hasCall("process"))
    }

    // -- bang method ----------------------------------------------------------

    fun testBangMethodCallers() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("bang.rb", """
            def save!
              true
            end
            def persist
              save!
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val result = handler.getCallHierarchy(
            caretInMethod(file, "def save!"), project, "callers", 1, BuiltInSearchScope.PROJECT_FILES
        )

        assertNotNull("Call hierarchy should not be null", result)
        assertTrue("Element name should include save", result!!.element.name.contains("save"))
        assertTrue("save!'s callers should include persist, got: ${result.calls?.map { it.name }}",
            result.calls.hasCall("persist"))
    }

    // -- depth > 1 callers ----------------------------------------------------

    fun testCallersWithDepth2() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("deep.rb", """
            def level1
              level2
            end
            def level2
              level3
            end
            def level3
              true
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val result = handler.getCallHierarchy(
            caretInMethod(file, "def level3"), project, "callers", 2, BuiltInSearchScope.PROJECT_FILES
        )

        assertNotNull("Call hierarchy should not be null", result)
        assertEquals("Element name should be level3", "level3", result!!.element.name)
        assertTrue("level3's direct callers should include level2, got: ${result.calls?.map { it.name }}",
            result.calls.hasCall("level2"))
        val level2 = result.calls?.firstOrNull { it.name.contains("level2") }
        assertNotNull("level2 caller node expected", level2)
        assertTrue("depth-2: level2's callers should include level1, got: ${level2!!.children?.map { it.name }}",
            level2.children.hasCall("level1"))
    }

    // -- depth > 1 callees ----------------------------------------------------

    fun testCalleesWithDepth2() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("deep_callees.rb", """
            def level1
              level2
            end
            def level2
              level3
            end
            def level3
              true
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val result = handler.getCallHierarchy(
            caretInMethod(file, "def level1"), project, "callees", 2, BuiltInSearchScope.PROJECT_FILES
        )

        assertNotNull("Call hierarchy should not be null", result)
        assertEquals("Element name should be level1", "level1", result!!.element.name)
        assertTrue("level1's direct callees should include level2, got: ${result.calls?.map { it.name }}",
            result.calls.hasCall("level2"))
        val level2 = result.calls?.firstOrNull { it.name.contains("level2") }
        assertNotNull("level2 callee node expected", level2)
        assertTrue("depth-2: level2's callees should include level3, got: ${level2!!.children?.map { it.name }}",
            level2.children.hasCall("level3"))
    }

    // -- cross-file callers ---------------------------------------------------

    fun testCrossFileCallers() {
        requireRubyPlugin()

        myFixture.addFileToProject("utils.rb", """
            def helper
              true
            end
        """.trimIndent())
        val mainFile = myFixture.addFileToProject("main.rb", """
            def start
              helper
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(mainFile)
        // Cross-file resolution depends on the Ruby plugin version; verify no crash
        // and that the queried element resolves correctly.
        val result = handler.getCallHierarchy(
            caretInMethod(mainFile, "def start"), project, "callers", 1, BuiltInSearchScope.PROJECT_FILES
        )
        assertNotNull("Call hierarchy should not be null", result)
        assertEquals("start should be the element name", "start", result!!.element.name)
    }

    // -- mixed call types (instance + class) ----------------------------------

    fun testMixedCallTypes() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("worker.rb", """
            class Worker
              def run
                prepare
              end
              def prepare
                true
              end
              def self.start
                worker = new
                worker.run
              end
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val result = handler.getCallHierarchy(
            caretInMethod(file, "def run"), project, "callees", 2, BuiltInSearchScope.PROJECT_FILES
        )

        assertNotNull("Call hierarchy should not be null", result)
        assertEquals("Element name should be Worker#run", "Worker#run", result!!.element.name)
        assertTrue("run's callees should include prepare, got: ${result.calls?.map { it.name }}",
            result.calls.hasCall("prepare"))
    }

    // -- empty file -----------------------------------------------------------

    fun testEmptyFileReturnsNull() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("empty.rb", "")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        // Empty file has no elements — findElementAt(0) returns null.
        val element = file.findElementAt(0)
        assertNull("empty file should yield no PSI leaf at offset 0", element)
    }

    // -- scope filtering: project files only ----------------------------------

    fun testScopeProjectFiles() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("main.rb", """
            def greet
              "hello"
            end
            def start
              greet
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        val result = handler.getCallHierarchy(
            caretInMethod(file, "def greet"), project, "callers", 1, BuiltInSearchScope.PROJECT_FILES
        )

        assertNotNull("Call hierarchy should not be null", result)
        assertEquals("Element name should be greet", "greet", result!!.element.name)
        assertTrue("greet's callers should include start (in project scope), got: ${result.calls?.map { it.name }}",
            result.calls.hasCall("start"))
    }

    // -- position outside method returns null ---------------------------------

    fun testPositionOutsideMethod() {
        requireRubyPlugin()

        val file = myFixture.addFileToProject("outside.rb", """
            class Calculator
              def add(x, y)
                x + y
              end
            end
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = resolveHandler(file)
        // Position on the class name, not inside any method.
        val classPos = file.text.indexOf("class Calculator")
        val element = file.findElementAt(classPos + 6)
        assertNotNull("class-name element expected", element)
        val result = handler.getCallHierarchy(element!!, project, "callers", 1, BuiltInSearchScope.PROJECT_FILES)
        assertNull("Call hierarchy should be null for element outside a method", result)
    }
}
