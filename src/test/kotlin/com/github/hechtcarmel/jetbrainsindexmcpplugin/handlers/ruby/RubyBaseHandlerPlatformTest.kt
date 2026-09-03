package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform tests for [BaseRubyHandler] and [RubyTypeHierarchyHandler].
 *
 * Exercises the actual handler methods against real Ruby PSI — no local
 * reimplementations. Protected methods are accessed via [TestBaseRubyHandler],
 * a minimal concrete subclass of [BaseRubyHandler] that exposes them as public.
 * Integration tests for TYPE_HIERARCHY go through the registered handler from
 * [LanguageHandlerRegistry.getTypeHierarchyHandler] — no subclass of
 * [RubyTypeHierarchyHandler] needed (it is final).
 *
 * NOTE: This is a PLATFORM test (extends [BasePlatformTestCase]); it must NOT
 * be named `*UnitTest` or the local `*UnitTest*` gradle filter would select it
 * and hang on headless machines. Requires the Ruby plugin. On CI, add to
 * gradle.properties:
 *   platformPlugins=org.jetbrains.plugins.ruby:253.31033.53
 *
 * Gated at runtime: tests skip gracefully if plugin is absent.
 */
class RubyBaseHandlerPlatformTest : BasePlatformTestCase() {

    private val RUBY_FIXTURE = """
        module AnimalKingdom
          module BaseModule
            def base_helper; end
          end

          class Animal
            include BaseModule
            extend BaseModule

            def speak
              "generic"
            end
          end

          class Dog < Animal
            def speak
              "woof"
            end
          end

          class Cat < Animal
            def speak
              "meow"
            end
          end

          class ServiceDog < Dog; end

          module Swimmable
            def swim; end
          end

          class Duck
            include Swimmable
            prepend Swimmable
            extend  Swimmable
          end
        end
    """.trimIndent()

    /**
     * Minimal concrete subclass of [BaseRubyHandler] that exposes protected
     * methods as public. Only used for testing [BaseRubyHandler] methods —
     * hierarchy integration tests go through the registered handler.
     */
    class TestBaseRubyHandler : BaseRubyHandler<Any?>() {
        override val languageId = "Ruby"
        override fun canHandle(element: PsiElement): Boolean = isRubyLanguage(element)
        override fun isAvailable(): Boolean = true

        fun isRubyLanguagePublic(el: PsiElement): Boolean = isRubyLanguage(el)
        fun isRClassPublic(el: PsiElement): Boolean = isRClass(el)
        fun isRModulePublic(el: PsiElement): Boolean = isRModule(el)
        fun isRMethodPublic(el: PsiElement): Boolean = isRMethod(el)

        fun getNamePublic(el: PsiElement): String? = getName(el)
        fun getQualifiedNamePublic(el: PsiElement): String? = getQualifiedName(el)
        fun getRubyQualifiedNamePublic(el: PsiElement): String? = getRubyQualifiedName(el)

        fun findContainingRClassOrRModulePublic(el: PsiElement): PsiElement? =
            findContainingRClassOrRModule(el)
        fun findContainingRMethodPublic(el: PsiElement): PsiElement? =
            findContainingRMethod(el)

        fun getSuperClassFQNPublic(el: PsiElement): String? = rClassGetSuperClassFQN(el)
        fun reconstructFqnPublic(name: String, ancestors: List<String>): String =
            reconstructFqn(name, ancestors)

        fun getIncludedModuleFQNsPublic(project: com.intellij.openapi.project.Project, el: PsiElement): List<String> =
            getIncludedModuleFQNs(project, el)
        fun getExtendedModuleFQNsPublic(project: com.intellij.openapi.project.Project, el: PsiElement): List<String> =
            getExtendedModuleFQNs(project, el)
        fun getPrependedModuleFQNsPublic(project: com.intellij.openapi.project.Project, el: PsiElement): List<String> =
            getPrependedModuleFQNs(project, el)

        fun getLineNumberPublic(project: com.intellij.openapi.project.Project, el: PsiElement): Int? =
            getLineNumber(project, el)

        fun resolveByFQNPublic(project: com.intellij.openapi.project.Project, fqn: String, scope: GlobalSearchScope): PsiElement? =
            resolveByFQN(project, fqn, scope)
        fun findSubtypesForFQNPublic(project: com.intellij.openapi.project.Project, fqn: String, scope: GlobalSearchScope): List<PsiElement> =
            findSubtypesForFQN(project, fqn, scope)
        fun getOverridingElementsPublic(el: PsiElement, project: com.intellij.openapi.project.Project): List<PsiElement> =
            getOverridingElementsViaOverrideUtil(el, project)
        fun getOverriddenMethodsPublic(method: PsiElement): List<PsiElement> =
            getOverriddenMethodsViaOverrideUtil(method)
    }

    private lateinit var handler: TestBaseRubyHandler
    private lateinit var psiFile: PsiFile
    private lateinit var virtualFile: VirtualFile

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        handler = TestBaseRubyHandler()

        virtualFile = myFixture.addFileToProject("unit_test.rb", RUBY_FIXTURE).virtualFile
        psiFile = myFixture.psiManager.findFile(virtualFile)!!
        IndexingTestUtil.waitUntilIndexesAreReady(myFixture.project)
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Find the PSI element for a class or module by name. */
    private fun findClassOrModule(name: String): PsiElement {
        val found = collectDescendants(psiFile).firstOrNull { el ->
            try {
                val n = handler.getNamePublic(el)
                n == name && (handler.isRClassPublic(el) || handler.isRModulePublic(el))
            } catch (_: Exception) { false }
        }
        return found ?: error("'$name' not found in fixture PSI")
    }

    /** Find a method by name inside a container. */
    private fun findMethod(container: PsiElement, name: String): PsiElement {
        return collectDescendants(container).firstOrNull { el ->
            try {
                handler.getNamePublic(el) == name && handler.isRMethodPublic(el)
            } catch (_: Exception) { false }
        } ?: error("method '$name' not found in ${handler.getNamePublic(container)}")
    }

    private fun collectDescendants(root: PsiElement): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        val stack = java.util.ArrayDeque<PsiElement>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val el = stack.removeLast()
            result.add(el)
            val children = el.children
            for (i in children.indices.reversed()) stack.add(children[i])
        }
        return result
    }

    private fun projectScope() = GlobalSearchScope.projectScope(myFixture.project)

    /**
     * Get the registered [TypeHierarchyHandler] for [psiFile], cast to the
     * concrete [RubyTypeHierarchyHandler] so we can call [TypeHierarchyHandler.getTypeHierarchy].
     */
    private fun getRegisteredHierarchyHandler(): TypeHierarchyHandler {
        val registered = LanguageHandlerRegistry.getTypeHierarchyHandler(psiFile)
            ?: error("No TypeHierarchyHandler registered for .rb file")
        assertTrue("Expected RubyTypeHierarchyHandler, got: ${registered::class}",
            registered is RubyTypeHierarchyHandler)
        return registered
    }

    // ── isRubyLanguage ──────────────────────────────────────────────────────

    fun testIsRubyLanguageReturnsTrueForRubyPsi() {
        val animal = findClassOrModule("Animal")
        assertTrue("Animal should be Ruby", handler.isRubyLanguagePublic(animal))
    }

    // ── PSI type checks ────────────────────────────────────────────────────

    fun testDetectsClassVsModule() {
        val animal = findClassOrModule("Animal")
        val base  = findClassOrModule("BaseModule")

        assertTrue("Animal is RClass",  handler.isRClassPublic(animal))
        assertFalse("Animal is not RModule", handler.isRModulePublic(animal))
        assertFalse("BaseModule is not RClass",  handler.isRClassPublic(base))
        assertTrue("BaseModule is RModule", handler.isRModulePublic(base))
    }

    // ── findContainingRClassOrRModule ───────────────────────────────────────

    fun testFindContainingClassForMethod() {
        val speak = findMethod(findClassOrModule("Animal"), "speak")
        val container = handler.findContainingRClassOrRModulePublic(speak)
        assertNotNull("speak() should have a containing class", container)
        assertEquals("Animal", handler.getNamePublic(container!!))
    }

    // ── getName / getQualifiedName ──────────────────────────────────────────

    fun testGetNameForClass() {
        val dog = findClassOrModule("Dog")
        assertEquals("Dog", handler.getNamePublic(dog))
    }

    fun testGetNameForModule() {
        val base = findClassOrModule("BaseModule")
        assertEquals("BaseModule", handler.getNamePublic(base))
    }

    fun testGetNameForMethod() {
        val animal = findClassOrModule("Animal")
        val speak = findMethod(animal, "speak")
        assertEquals("speak", handler.getNamePublic(speak))
    }

    // ── getRubyQualifiedName ────────────────────────────────────────────────

    fun testFqnForTopLevelClass() {
        val dog = findClassOrModule("Dog")
        val fqn = handler.getRubyQualifiedNamePublic(dog)
        assertNotNull("Dog should have FQN", fqn)
        assertTrue("Dog FQN should end with Dog, got: $fqn",
            fqn!!.endsWith("Dog"))
    }

    fun testFqnForNestedModule() {
        val base = findClassOrModule("BaseModule")
        val fqn = handler.getRubyQualifiedNamePublic(base)
        assertNotNull("BaseModule should have FQN", fqn)
        assertTrue("BaseModule FQN should contain AnimalKingdom, got: $fqn",
            fqn!!.contains("AnimalKingdom"))
    }

    fun testFqnForNestedInsideModule() {
        val animal = findClassOrModule("Animal")
        val fqn = handler.getRubyQualifiedNamePublic(animal)
        assertNotNull("Animal should have FQN", fqn)
        assertTrue("Animal FQN should contain AnimalKingdom, got: $fqn",
            fqn!!.contains("AnimalKingdom"))
    }

    // ── rClassGetSuperClassFQN ──────────────────────────────────────────────

    fun testGetSuperClassFqnFromChild() {
        val dog = findClassOrModule("Dog")
        val superFqn = handler.getSuperClassFQNPublic(dog)
        assertNotNull("Dog superclass should be non-null", superFqn)
        assertTrue("Dog extends Animal, got: $superFqn",
            superFqn!!.endsWith("Animal"))
    }

    fun testGetSuperClassFqnFromGrandchild() {
        val serviceDog = findClassOrModule("ServiceDog")
        val superFqn = handler.getSuperClassFQNPublic(serviceDog)
        assertNotNull("ServiceDog superclass should be non-null", superFqn)
        assertTrue("ServiceDog extends Dog, got: $superFqn",
            superFqn!!.endsWith("Dog"))
    }

    fun testGetSuperClassFqnFromBaseClass() {
        val animal = findClassOrModule("Animal")
        val superFqn = handler.getSuperClassFQNPublic(animal)
        // Animal doesn't explicitly inherit — superclass is Object (via FQN) or null
        if (superFqn != null) {
            assertTrue("Animal implicit superclass should be Object, got: $superFqn",
                superFqn.endsWith("Object"))
        }
        // null is also acceptable (means no explicit superclass detected)
    }

    // ── getIncludedModuleFQNs ───────────────────────────────────────────────

    fun testGetIncludedModulesForAnimal() {
        val animal = findClassOrModule("Animal")
        val included = handler.getIncludedModuleFQNsPublic(myFixture.project, animal)
        assertTrue("Animal should include BaseModule, got: $included",
            included.any { it.contains("BaseModule") })
    }

    fun testGetIncludedModulesForDuck() {
        val duck = findClassOrModule("Duck")
        val included = handler.getIncludedModuleFQNsPublic(myFixture.project, duck)
        assertTrue("Duck should include Swimmable, got: $included",
            included.any { it.contains("Swimmable") })
    }

    fun testGetIncludedModulesReturnsEmptyForModuleWithoutIncludes() {
        val base = findClassOrModule("BaseModule")
        val included = handler.getIncludedModuleFQNsPublic(myFixture.project, base)
        assertTrue("BaseModule has no includes, got: $included", included.isEmpty())
    }

    // ── getExtendedModuleFQNs ───────────────────────────────────────────────

    fun testGetExtendedModulesForAnimal() {
        val animal = findClassOrModule("Animal")
        val extended = handler.getExtendedModuleFQNsPublic(myFixture.project, animal)
        assertTrue("Animal should extend BaseModule, got: $extended",
            extended.any { it.contains("BaseModule") })
    }

    fun testGetExtendedModulesReturnsEmptyForSubclassWithoutExtend() {
        val dog = findClassOrModule("Dog")
        val extended = handler.getExtendedModuleFQNsPublic(myFixture.project, dog)
        assertTrue("Dog has no extends, got: $extended", extended.isEmpty())
    }

    // ── getPrependedModuleFQNs ──────────────────────────────────────────────

    fun testGetPrependedModulesForDuck() {
        val duck = findClassOrModule("Duck")
        val prepended = handler.getPrependedModuleFQNsPublic(myFixture.project, duck)
        assertTrue("Duck should prepend Swimmable, got: $prepended",
            prepended.any { it.contains("Swimmable") })
    }

    fun testGetPrependedModulesReturnsEmptyForClassWithoutPrepend() {
        val animal = findClassOrModule("Animal")
        val prepended = handler.getPrependedModuleFQNsPublic(myFixture.project, animal)
        assertTrue("Animal has no prepends, got: $prepended", prepended.isEmpty())
    }

    // ── getLineNumber ───────────────────────────────────────────────────────

    fun testGetLineNumberReturnsPositive() {
        val animal = findClassOrModule("Animal")
        val line = handler.getLineNumberPublic(myFixture.project, animal)
        assertNotNull("Animal should have a line number", line)
        assertTrue("Line should be positive, got: $line", line!! > 0)
    }

    fun testLineNumberNestedAfterParent() {
        val animal = findClassOrModule("Animal")
        val dog = findClassOrModule("Dog")
        val animalLine = handler.getLineNumberPublic(myFixture.project, animal)!!
        val dogLine = handler.getLineNumberPublic(myFixture.project, dog)!!
        assertTrue("Dog ($dogLine) should be after Animal ($animalLine)", dogLine > animalLine)
    }

    // ── reconstructFqn (calling the real method on BaseRubyHandler) ──────────

    fun testReconstructFqnBareName() {
        assertEquals("User", handler.reconstructFqnPublic("User", emptyList()))
    }

    fun testReconstructFqnOneAncestor() {
        assertEquals("Admin::User", handler.reconstructFqnPublic("User", listOf("Admin")))
    }

    fun testReconstructFqnDeepNesting() {
        // innermost-first ancestor list: [B, A] → A::B::C
        assertEquals("A::B::C", handler.reconstructFqnPublic("C", listOf("B", "A")))
    }

    fun testReconstructFqnEmptyName() {
        assertEquals("Admin", handler.reconstructFqnPublic("", listOf("Admin")))
    }

    fun testReconstructFqnBothEmpty() {
        assertEquals("", handler.reconstructFqnPublic("", emptyList()))
    }

    // ── Type hierarchy integration (through registered handler) ──────────────

    fun testTypeHierarchyDogHasSuperAndSub() {
        val dog = findClassOrModule("Dog")
        val hierarchy = getRegisteredHierarchyHandler()
        val result = hierarchy.getTypeHierarchy(dog, myFixture.project, BuiltInSearchScope.PROJECT_FILES, false)
        assertNotNull("Dog should have hierarchy", result)
        assertEquals("Dog element should be Dog",
            true, result!!.element.name.endsWith("Dog"))
        assertTrue("Dog should have supertype (Animal), got: ${result.supertypes.size}",
            result.supertypes.isNotEmpty())
        assertTrue("Dog should have subtype (ServiceDog), got: ${result.subtypes.size}",
            result.subtypes.isNotEmpty())
    }

    fun testTypeHierarchyAnimalHasSubtypes() {
        val animal = findClassOrModule("Animal")
        val hierarchy = getRegisteredHierarchyHandler()
        val result = hierarchy.getTypeHierarchy(animal, myFixture.project, BuiltInSearchScope.PROJECT_FILES, false)
        assertNotNull("Animal should have hierarchy", result)
        assertTrue("Animal should have subtypes (Dog, Cat), got: ${result!!.subtypes.size}",
            result.subtypes.size >= 2)
    }

    fun testTypeHierarchyAnimalHasModuleSupertype() {
        val animal = findClassOrModule("Animal")
        val hierarchy = getRegisteredHierarchyHandler()
        val result = hierarchy.getTypeHierarchy(animal, myFixture.project, BuiltInSearchScope.PROJECT_FILES, false)
        assertNotNull("Animal should have hierarchy", result)
        // Animal includes BaseModule — should appear as supertype with kind MODULE
        val moduleSuper = result!!.supertypes.firstOrNull { it.kind == "MODULE" }
        assertNotNull("Animal should have a MODULE supertype (BaseModule), got: ${result.supertypes}", moduleSuper)
    }

    fun testTypeHierarchyDeepInheritance() {
        val serviceDog = findClassOrModule("ServiceDog")
        val hierarchy = getRegisteredHierarchyHandler()
        val result = hierarchy.getTypeHierarchy(serviceDog, myFixture.project, BuiltInSearchScope.PROJECT_FILES, false)
        assertNotNull("ServiceDog should have hierarchy", result)
        // ServiceDog → Dog → Animal
        assertTrue("ServiceDog supertypes should include Dog, got: ${result!!.supertypes.map { it.name }}",
            result.supertypes.any { it.name.endsWith("Dog") })
    }

    fun testTypeHierarchyModuleHasNoSuperclass() {
        val base = findClassOrModule("BaseModule")
        val hierarchy = getRegisteredHierarchyHandler()
        val result = hierarchy.getTypeHierarchy(base, myFixture.project, BuiltInSearchScope.PROJECT_FILES, false)
        assertNotNull("BaseModule should have hierarchy", result)
        // Module has no superclass supertype, only include/extend/prepend
        val classSuper = result!!.supertypes.firstOrNull { it.kind == "CLASS" }
        assertNull("BaseModule should not have CLASS supertype, got: ${result.supertypes}", classSuper)
    }

    // ── Registration gate ───────────────────────────────────────────────────

    fun testRubyHandlerIsRegistered() {
        val registered = LanguageHandlerRegistry.getTypeHierarchyHandler(
            myFixture.addFileToProject("__gate__.rb", "class Gate; end")
        )
        assertTrue("TypeHierarchyHandler for .rb should be RubyTypeHierarchyHandler",
            registered is RubyTypeHierarchyHandler)
    }
}