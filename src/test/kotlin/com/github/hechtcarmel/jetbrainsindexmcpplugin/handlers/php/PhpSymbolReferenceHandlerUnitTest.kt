package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.php

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.FakePsiElement
import junit.framework.TestCase

class PhpSymbolReferenceHandlerUnitTest : TestCase() {

    private val helper = TestPhpHandler()

    fun testPhpSymbolPatternAcceptsSupportedFormats() {
        val validSymbols = listOf(
            "\\App\\Service\\UserService",
            "App\\Service\\UserService",
            "\\App\\Service\\UserService::find",
            "\\App\\Service\\UserService::find()",
            "\\App\\Service\\UserService::find(int, string)",
            "\\App\\Service\\UserService::\$repository",
            "\\App\\Service\\UserService::ROLE_ADMIN",
            "\\App\\Service\\StatusEnum::ACTIVE"
        )

        validSymbols.forEach { symbol ->
            assertTrue("Expected PHP symbol pattern to accept: $symbol", PhpSymbolReferenceHandler.PHP_SYMBOL_PATTERN.matches(symbol))
        }
    }

    fun testPhpSymbolPatternRejectsUnsupportedFormats() {
        val invalidSymbols = listOf(
            "",
            "\\App\\Service\\",
            "\\App\\Service\\UserService::",
            "\\App\\Service\\UserService#find",
            "\\App\\Service\\UserService::\$",
            "\\App\\Service\\UserService::123INVALID"
        )

        invalidSymbols.forEach { symbol ->
            assertFalse("Expected PHP symbol pattern to reject: $symbol", PhpSymbolReferenceHandler.PHP_SYMBOL_PATTERN.matches(symbol))
        }
    }

    fun testDeclaredOnlyMethodLookupIgnoresInheritedMethods() {
        val parentMethod = FakePhpMethod("save")
        val parentClass = FakePhpClass("\\App\\BaseRepo", ownMethods = listOf(parentMethod))
        val childClass = FakePhpClass(
            "\\App\\ChildRepo",
            ownMethods = emptyList(),
            inheritedMethods = listOf(parentMethod),
            superClass = parentClass
        )

        assertNull("Declared-only lookup must not return inherited methods", helper.findOwn(childClass, "save"))
        assertSame("Inherited lookup should still support direct symbol resolution", parentMethod, helper.findInherited(childClass, "SAVE"))
        assertEquals("\\App\\BaseRepo", helper.findNearestClassDeclarationFqn(childClass, "save"))
    }

    fun testInterfaceHierarchyLookupReportsDeclaringInterface() {
        val parentMethod = FakePhpMethod("handle")
        val parentInterface = FakePhpClass("\\App\\Handler", ownMethods = listOf(parentMethod))
        val childInterface = FakePhpClass(
            "\\App\\ChildHandler",
            ownMethods = emptyList(),
            interfaces = arrayOf(parentInterface)
        )

        assertNull("Declared-only lookup must not return inherited interface methods", helper.findOwn(childInterface, "handle"))
        assertEquals("\\App\\Handler", helper.findNearestInterfaceDeclarationFqn(childInterface, "HANDLE"))
    }

    fun testEnumCaseLookup() {
        val caseActive = FakePhpEnumCase("ACTIVE")
        val caseInactive = FakePhpEnumCase("INACTIVE")
        val enumClass = FakePhpClass(
            "\\App\\StatusEnum",
            enumCases = listOf(caseActive, caseInactive)
        )

        assertSame("Enum case lookup must find ACTIVE", caseActive, helper.findEnumCase(enumClass, "ACTIVE"))
        assertSame("Enum case lookup must find INACTIVE", caseInactive, helper.findEnumCase(enumClass, "INACTIVE"))
        assertNull("Enum case lookup must return null for non-existent case", helper.findEnumCase(enumClass, "NONEXISTENT"))
        assertNull("Enum case lookup must return null for method name on enum", helper.findEnumCase(enumClass, "someMethod"))
    }

    fun testEnumCaseLookupOnNonEnumType() {
        // A regular class without getEnumCases() should not cause errors
        val regularClass = FakePhpClass("\\App\\RegularService")
        assertNull("Enum case lookup on regular class must return null", helper.findEnumCase(regularClass, "ANYTHING"))
    }

    private class TestPhpHandler : BasePhpHandler<Unit>() {
        override val languageId = "PHP"

        override fun canHandle(element: PsiElement): Boolean = true

        override fun isAvailable(): Boolean = true

        fun findOwn(phpClass: PsiElement, methodName: String): PsiElement? =
            findOwnMethodInClass(phpClass, methodName)

        fun findInherited(phpClass: PsiElement, methodName: String): PsiElement? =
            findMethodInClass(phpClass, methodName)

        fun findNearestClassDeclarationFqn(phpClass: PsiElement, methodName: String): String? =
            findOwnMethodInClassHierarchy(phpClass, methodName)?.declaringClass?.let { getFQN(it) }

        fun findNearestInterfaceDeclarationFqn(iface: PsiElement, methodName: String): String? =
            findOwnMethodInInterfaceHierarchy(iface, methodName)?.declaringClass?.let { getFQN(it) }

        fun findEnumCase(phpClass: PsiElement, caseName: String): PsiElement? =
            findEnumCaseInClass(phpClass, caseName)
    }

    private class FakePhpClass(
        private val fqn: String,
        private val ownMethods: List<PsiElement> = emptyList(),
        private val inheritedMethods: List<PsiElement> = emptyList(),
        private val superClass: PsiElement? = null,
        private val interfaces: Array<PsiElement> = emptyArray(),
        private val enumCases: List<PsiElement> = emptyList()
    ) : FakePsiElement() {
        override fun getParent(): PsiElement? = null

        override fun getName(): String = fqn.substringAfterLast("\\")

        fun getFQN(): String = fqn

        fun getOwnMethods(): Array<PsiElement> = ownMethods.toTypedArray()

        fun getMethods(): Array<PsiElement> = (ownMethods + inheritedMethods).toTypedArray()

        fun getSuperClass(): PsiElement? = superClass

        fun getImplementedInterfaces(): Array<PsiElement> = interfaces

        fun getEnumCases(): Array<PsiElement> = enumCases.toTypedArray()
    }

    private class FakePhpMethod(private val methodName: String) : FakePsiElement() {
        override fun getParent(): PsiElement? = null

        override fun getName(): String = methodName
    }

    private class FakePhpEnumCase(private val caseName: String) : FakePsiElement() {
        override fun getParent(): PsiElement? = null

        override fun getName(): String = caseName
    }
}
