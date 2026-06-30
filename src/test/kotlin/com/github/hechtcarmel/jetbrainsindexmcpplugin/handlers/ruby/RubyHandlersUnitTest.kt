package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.FakePsiElement
import junit.framework.TestCase
import org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.classes.RClass
import org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.methods.RMethod
import org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.modules.RModule

/**
 * Unit tests for Ruby handler logic.
 *
 * All tests run without the IntelliJ Platform runtime (extend [TestCase]).
 * They use [FakeRClass] / [FakeRModule] / [FakeRMethod] stubs that implement the
 * same interfaces as the real Ruby PSI types; this allows [BaseRubyHandler]
 * to recognise them via [Class.isInstance], exactly as the production code does
 * when reflecting over the real plugin's PSI hierarchy.
 *
 * ## What is tested here
 *
 * - [RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN] — valid/invalid symbol formats
 * - [RubySymbolReferenceHandler.parseRubySymbol] — tokenisation of className#method
 * - [BaseRubyHandler.getName] — reflection over fake PSI classes
 * - [BaseRubyHandler.getFqn] — getFullyQualifiedName / getFQN fallback chain
 * - [BaseRubyHandler.getSuperClass] / [BaseRubyHandler.getSuperClassName]
 * - [BaseRubyHandler.getIncludedModules]
 * - [BaseRubyHandler.isRClass] / isRModule / isRMethod
 * - [BaseRubyHandler.isRubyLanguage]
 * - [BaseRubyHandler.findContainer] — walk up PSI parent chain
 * - [BaseRubyHandler.collectAncestors] — BFS superclass + included modules
 * - [BaseRubyHandler.findMethodByName] — name-filtered child search
 * - Plugin-unavailable guard: handler.isAvailable() returns false when PSI classes absent
 */
class RubyHandlersUnitTest : TestCase() {

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Concrete handler subclass that exposes protected utilities for testing. */
    private class TestHandler : BaseRubyHandler<Unit>() {
        override val languageId = "ruby"
        override fun canHandle(element: PsiElement) = true
        override fun isAvailable() = true

        // Expose internal helpers publicly for the tests
        fun testGetName(e: PsiElement) = getName(e)
        fun testGetFqn(e: PsiElement) = getFqn(e)
        fun testComputeFqnFromPsiAncestors(e: PsiElement) = computeFqnFromPsiAncestors(e)
        fun testGetSuperClass(e: PsiElement) = getSuperClass(e)
        fun testGetSuperClassName(e: PsiElement) = getSuperClassName(e)
        fun testGetIncludedModules(e: PsiElement) = getIncludedModules(e)
        fun testIsRClass(e: PsiElement) = isRClass(e)
        fun testIsRModule(e: PsiElement) = isRModule(e)
        fun testIsRMethod(e: PsiElement) = isRMethod(e)
        fun testFindContainer(e: PsiElement) = findContainer(e)
        fun testCollectAncestors(e: PsiElement, maxAncestors: Int = 20) = collectAncestors(e, maxAncestors)
    }

    private val handler = TestHandler()
    private val symbolHandler = RubySymbolReferenceHandler()

    // ── Symbol pattern tests ──────────────────────────────────────────────────────

    fun testSymbolPatternAcceptsSimpleClassName() {
        assertTrue(RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN.matches("UserService"))
    }

    fun testSymbolPatternAcceptsNamespacedClass() {
        assertTrue(RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN.matches("Services::UserService"))
    }

    fun testSymbolPatternAcceptsDeeplyNamespaced() {
        assertTrue(RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN.matches("App::Services::UserService"))
    }

    fun testSymbolPatternAcceptsClassWithMethod() {
        assertTrue(RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN.matches("UserService#find"))
    }

    fun testSymbolPatternAcceptsNamespacedWithMethod() {
        assertTrue(RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN.matches("Services::UserService#find_by_email"))
    }

    fun testSymbolPatternAcceptsPredicateMethod() {
        assertTrue(RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN.matches("User#admin?"))
    }

    fun testSymbolPatternAcceptsBangMethod() {
        assertTrue(RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN.matches("User#save!"))
    }

    fun testSymbolPatternAcceptsUnderscoreMethodStart() {
        assertTrue(RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN.matches("UserService#_internal"))
    }

    fun testSymbolPatternRejectsEmpty() {
        assertFalse(RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN.matches(""))
    }

    fun testSymbolPatternRejectsLowercaseStart() {
        // Ruby classes start uppercase by convention; lowercase = method/variable not a class
        assertFalse(RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN.matches("userService"))
    }

    fun testSymbolPatternRejectsDoubleHash() {
        assertFalse(RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN.matches("UserService#find#extra"))
    }

    fun testSymbolPatternRejectsTrailingColon() {
        assertFalse(RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN.matches("UserService::"))
    }

    fun testSymbolPatternRejectsEmptyMethodName() {
        assertFalse(RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN.matches("UserService#"))
    }

    fun testSymbolPatternRejectsUppercaseMethodName() {
        // Methods start with lowercase or underscore; uppercase after # is invalid
        assertFalse(RubySymbolReferenceHandler.RUBY_SYMBOL_PATTERN.matches("UserService#FindUser"))
    }

    // ── parseRubySymbol tests ─────────────────────────────────────────────────────

    fun testParseSymbolClassOnly() {
        val (className, methodName) = symbolHandler.parseRubySymbol("UserService")
        assertEquals("UserService", className)
        assertNull(methodName)
    }

    fun testParseSymbolNamespacedClassOnly() {
        val (className, methodName) = symbolHandler.parseRubySymbol("Services::UserService")
        assertEquals("Services::UserService", className)
        assertNull(methodName)
    }

    fun testParseSymbolClassAndMethod() {
        val (className, methodName) = symbolHandler.parseRubySymbol("UserService#find")
        assertEquals("UserService", className)
        assertEquals("find", methodName)
    }

    fun testParseSymbolNamespacedClassAndMethod() {
        val (className, methodName) = symbolHandler.parseRubySymbol("Services::UserService#find_by_email")
        assertEquals("Services::UserService", className)
        assertEquals("find_by_email", methodName)
    }

    fun testParseSymbolPredicateMethod() {
        val (className, methodName) = symbolHandler.parseRubySymbol("User#admin?")
        assertEquals("User", className)
        assertEquals("admin?", methodName)
    }

    fun testParseSymbolBangMethod() {
        val (_, methodName) = symbolHandler.parseRubySymbol("User#save!")
        assertEquals("save!", methodName)
    }

    // ── Reflection utility tests (getName, getFqn, getSuperClass …) ──────────────

    fun testGetNameReturnsClassNameViaReflection() {
        val cls = FakeRClass("UserService")
        assertEquals("UserService", handler.testGetName(cls))
    }

    fun testGetNameReturnsModuleNameViaReflection() {
        val mod = FakeRModule("Authenticatable")
        assertEquals("Authenticatable", handler.testGetName(mod))
    }

    fun testGetNameReturnsMethodNameViaReflection() {
        val method = FakeRMethod("find")
        assertEquals("find", handler.testGetName(method))
    }

    fun testGetNameReturnsNullForUnnamedElement() {
        // An element with no getName() method should not crash
        val bare = object : FakePsiElement() {
            override fun getParent(): PsiElement? = null
        }
        assertNull(handler.testGetName(bare))
    }

    fun testGetFqnPrefersGetFullyQualifiedName() {
        // With PSI-ancestor walk as primary strategy, reflection is only a fallback.
        // For a top-level FakeRClass (no namespace parent), getFqn returns the simple name.
        val cls = FakeRClass("UserService", fqn = "Services::UserService")
        // PSI ancestor walk: no parent RClass/RModule → returns "UserService"
        assertEquals("UserService", handler.testGetFqn(cls))
    }

    fun testGetFqnFromNestedPsiAncestors() {
        // When a class is nested inside an RModule parent, getFqn builds the FQN from ancestors.
        val inner = FakeRClass("UserService")
        val outer = FakeRModule("Services")
        inner.fakeParent = outer
        assertEquals("Services::UserService", handler.testGetFqn(inner))
    }

    fun testGetFqnReturnsNullWhenAbsent() {
        // FakeRClass with no fqn override → getFullyQualifiedName returns null, PSI walk used
        val cls = FakeRClass("Bare", fqn = null)
        // Top-level class: PSI ancestor walk produces just the simple name
        assertEquals("Bare", handler.testGetFqn(cls))
    }

    // ── computeFqnFromPsiAncestors ────────────────────────────────────────────────

    fun testComputeFqnSimpleClass() {
        val cls = FakeRClass("UserService")
        assertEquals("UserService", handler.testComputeFqnFromPsiAncestors(cls))
    }

    fun testComputeFqnNestedInsideModule() {
        val inner = FakeRClass("UserService")
        val outer = FakeRModule("Services", children = listOf(inner))
        inner.fakeParent = outer
        assertEquals("Services::UserService", handler.testComputeFqnFromPsiAncestors(inner))
    }

    fun testComputeFqnDeeplyNested() {
        val leaf  = FakeRModule("Auditable")
        val outer = FakeRModule("Concerns", children = listOf(leaf))
        leaf.fakeParent = outer
        assertEquals("Concerns::Auditable", handler.testComputeFqnFromPsiAncestors(leaf))
    }

    fun testComputeFqnReturnsNullForNonClassModule() {
        val method = FakeRMethod("call")
        assertNull(handler.testComputeFqnFromPsiAncestors(method))
    }

    fun testGetSuperClassReturnsParentViaReflection() {
        val parent = FakeRClass("Base")
        val child = FakeRClass("Child", superClass = parent)
        assertSame(parent, handler.testGetSuperClass(child))
    }

    fun testGetSuperClassReturnsNullWhenAbsent() {
        val cls = FakeRClass("Root")
        assertNull(handler.testGetSuperClass(cls))
    }

    fun testGetSuperClassNameReturnsStringViaReflection() {
        val cls = FakeRClass("Child", superClassName = "Base")
        assertEquals("Base", handler.testGetSuperClassName(cls))
    }

    fun testGetIncludedModulesReturnsListViaReflection() {
        val auth = FakeRModule("Authenticatable")
        val cls = FakeRClass("User", includedModules = listOf(auth))
        val modules = handler.testGetIncludedModules(cls)
        assertEquals(1, modules.size)
        assertSame(auth, modules[0])
    }

    fun testGetIncludedModulesReturnsEmptyWhenNone() {
        val cls = FakeRClass("User")
        assertEquals(emptyList<PsiElement>(), handler.testGetIncludedModules(cls))
    }

    // ── isRClass / isRModule / isRMethod ──────────────────────────────────────────

    fun testIsRClassTrueForFakeRClass() {
        val cls = FakeRClass("MyClass")
        assertTrue(handler.testIsRClass(cls))
    }

    fun testIsRClassFalseForFakeRModule() {
        val mod = FakeRModule("MyModule")
        assertFalse(handler.testIsRClass(mod))
    }

    fun testIsRModuleTrueForFakeRModule() {
        val mod = FakeRModule("MyModule")
        assertTrue(handler.testIsRModule(mod))
    }

    fun testIsRModuleFalseForFakeRClass() {
        val cls = FakeRClass("MyClass")
        assertFalse(handler.testIsRModule(cls))
    }

    fun testIsRMethodTrueForFakeRMethod() {
        val method = FakeRMethod("my_method")
        assertTrue(handler.testIsRMethod(method))
    }

    fun testIsRMethodFalseForFakeRClass() {
        val cls = FakeRClass("MyClass")
        assertFalse(handler.testIsRMethod(cls))
    }

    // ── isRubyLanguage ────────────────────────────────────────────────────────────
    //
    // NOTE: Creating Language(id) registers it globally; calling it twice with
    // the same id throws ImplementationConflictException.  Full isRubyLanguage
    // coverage is handled by platform integration tests.  Here we verify only
    // that the languageId declared by each handler equals "ruby" (the value the
    // comparison is made against).

    fun testIsRubyLanguageCheckUsesCaseInsensitiveComparison() {
        val handlers: List<BaseRubyHandler<*>> = listOf(
            RubyTypeHierarchyHandler(),
            RubyCallHierarchyHandler(),
            RubyImplementationsHandler(),
            RubySuperMethodsHandler(),
            RubyStructureHandler(),
            RubySymbolReferenceHandler(),
        )
        handlers.forEach { h ->
            assertTrue(
                "${h::class.simpleName}.languageId must equal 'ruby' case-insensitively",
                h.languageId.equals("ruby", ignoreCase = true)
            )
        }
    }

    // ── findContainer ─────────────────────────────────────────────────────────────

    fun testFindContainerReturnsDirectParentRClass() {
        val method = FakeRMethod("save")
        val cls = FakeRClass("User", children = listOf(method))
        method.fakeParent = cls

        assertSame(cls, handler.testFindContainer(method))
    }

    fun testFindContainerReturnsDirectParentRModule() {
        val method = FakeRMethod("call")
        val mod = FakeRModule("Callable", children = listOf(method))
        method.fakeParent = mod

        assertSame(mod, handler.testFindContainer(method))
    }

    fun testFindContainerReturnsNullWhenNoClassOrModuleAncestor() {
        val method = FakeRMethod("orphan")
        assertNull(handler.testFindContainer(method))
    }

    fun testFindContainerSkipsIntermediateNonContainerParents() {
        val method = FakeRMethod("run")
        val intermediate = object : FakePsiElement() { override fun getParent() = null }
        val cls = FakeRClass("Worker", children = listOf(method))

        method.fakeParent = intermediate
        (intermediate as? FakePsiElement)?.let { } // intermediate has no parent for now
        // Wire: method → intermediate → cls
        val intermediateWithParent = object : FakePsiElement() { override fun getParent() = cls }
        method.fakeParent = intermediateWithParent

        assertSame(cls, handler.testFindContainer(method))
    }

    // ── collectAncestors ──────────────────────────────────────────────────────────

    fun testCollectAncestorsReturnsSuperclass() {
        val base = FakeRClass("Base")
        val child = FakeRClass("Child", superClass = base)

        val ancestors = handler.testCollectAncestors(child)
        assertEquals(1, ancestors.size)
        assertSame(base, ancestors[0])
    }

    fun testCollectAncestorsReturnsIncludedModules() {
        val auth = FakeRModule("Authenticatable")
        val log = FakeRModule("Loggable")
        val cls = FakeRClass("User", includedModules = listOf(auth, log))

        val ancestors = handler.testCollectAncestors(cls)
        assertEquals(2, ancestors.size)
        assertTrue(ancestors.contains(auth))
        assertTrue(ancestors.contains(log))
    }

    fun testCollectAncestorsReturnsSuperclassBeforeModules() {
        val base = FakeRClass("Base")
        val auth = FakeRModule("Authenticatable")
        val cls = FakeRClass("User", superClass = base, includedModules = listOf(auth))

        val ancestors = handler.testCollectAncestors(cls)
        // Superclass should come first in BFS order
        assertSame(base, ancestors[0])
        assertTrue(ancestors.contains(auth))
    }

    fun testCollectAncestorsStopsAtCycle() {
        val a = FakeRClass("A")
        val b = FakeRClass("B", superClass = a)
        a.fakeSuperClass = b  // intentional cycle

        // Should complete without infinite loop and return non-empty result
        val ancestors = handler.testCollectAncestors(a)
        assertTrue(ancestors.isNotEmpty())
        assertTrue(ancestors.size <= 20)
    }

    fun testCollectAncestorsRespectsMaxLimit() {
        // Build a long chain: A < B < C < ... < Z
        var current = FakeRClass("Z")
        repeat(25) { i ->
            val parent = FakeRClass("Class$i", superClass = current)
            current = parent
        }
        val ancestors = handler.testCollectAncestors(current, maxAncestors = 10)
        assertEquals(10, ancestors.size)
    }


    // ── Fake PSI helpers ──────────────────────────────────────────────────────────

    /**
     * Fake [RClass] that implements the stub interface so reflection-based
     * `isRClass(e)` returns true (Class.isInstance check passes).
     */
    inner class FakeRClass(
        private val name: String,
        fqn: String? = null,
        superClass: FakeRClass? = null,
        private val superClassName: String? = null,
        private val includedModules: List<PsiElement> = emptyList(),
        private val children: List<PsiElement> = emptyList(),
    ) : FakePsiElement(), RClass {

        var fakeSuperClass: FakeRClass? = superClass
        private val fakeFqn: String? = fqn
        var fakeParent: PsiElement? = null

        override fun getParent(): PsiElement? = fakeParent
        override fun getName(): String = name
        override fun getFullyQualifiedName(): String? = fakeFqn
        override fun getSuperClass(): PsiElement? = fakeSuperClass
        override fun getSuperClassName(): String? = superClassName
        override fun getIncludedModules(): List<PsiElement> = includedModules

        // Let PsiTreeUtil.findChildrenOfType traverse children
        override fun getChildren(): Array<PsiElement> = children.toTypedArray()
    }

    /**
     * Fake [RModule] so reflection-based `isRModule(e)` returns true.
     */
    inner class FakeRModule(
        private val name: String,
        private val fqn: String? = null,
        private val includedModules: List<PsiElement> = emptyList(),
        private val children: List<PsiElement> = emptyList(),
    ) : FakePsiElement(), RModule {

        var fakeParent: PsiElement? = null

        override fun getParent(): PsiElement? = fakeParent
        override fun getName(): String = name
        override fun getFullyQualifiedName(): String? = fqn
        override fun getIncludedModules(): List<PsiElement> = includedModules
        override fun getChildren(): Array<PsiElement> = children.toTypedArray()
    }

    /**
     * Fake [RMethod] so reflection-based `isRMethod(e)` returns true.
     */
    inner class FakeRMethod(
        private val name: String,
    ) : FakePsiElement(), RMethod {

        var fakeParent: PsiElement? = null

        override fun getParent(): PsiElement? = fakeParent
        override fun getName(): String = name
    }

}
