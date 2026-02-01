package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform tests for [PsiUtils] reference resolution functionality.
 *
 * Tests verify that [PsiUtils.resolveTargetElement] correctly handles:
 * - Method call references (resolves to the called method)
 * - Field access references (resolves to the field)
 * - Declaration positions (returns the declaration itself)
 */
class PsiUtilsTest : BasePlatformTestCase() {

    fun testResolveTargetElement_MethodCallResolvesToDeclaration() {
        // Create a service class with a method
        myFixture.configureByText(
            "Service.java",
            """
            public class Service {
                public void doWork() {}
            }
            """.trimIndent()
        )

        // Create a caller class that uses the service
        val callerFile = myFixture.configureByText(
            "Caller.java",
            """
            public class Caller {
                private Service service = new Service();
                public void call() {
                    service.do<caret>Work();
                }
            }
            """.trimIndent()
        )

        val element = callerFile.findElementAt(myFixture.caretOffset)
        assertNotNull("Should find element at caret", element)

        val resolved = PsiUtils.resolveTargetElement(element!!)
        assertNotNull("Should resolve to declaration", resolved)
        assertTrue("Should resolve to PsiMethod", resolved is PsiMethod)
        assertEquals("doWork", (resolved as PsiMethod).name)
    }

    fun testResolveTargetElement_FieldAccessResolvesToField() {
        // Create a class with a field
        myFixture.configureByText(
            "Data.java",
            """
            public class Data {
                public String name;
            }
            """.trimIndent()
        )

        // Create a class that accesses the field
        val accessorFile = myFixture.configureByText(
            "Accessor.java",
            """
            public class Accessor {
                public void access() {
                    Data data = new Data();
                    String n = data.na<caret>me;
                }
            }
            """.trimIndent()
        )

        val element = accessorFile.findElementAt(myFixture.caretOffset)
        assertNotNull("Should find element at caret", element)

        val resolved = PsiUtils.resolveTargetElement(element!!)
        assertNotNull("Should resolve to declaration", resolved)
        assertTrue("Should resolve to PsiField", resolved is PsiField)
        assertEquals("name", (resolved as PsiField).name)
    }

    fun testResolveTargetElement_OnDeclarationReturnsItself() {
        val file = myFixture.configureByText(
            "MyClass.java",
            """
            public class MyClass {
                public void my<caret>Method() {}
            }
            """.trimIndent()
        )

        val element = file.findElementAt(myFixture.caretOffset)
        assertNotNull("Should find element at caret", element)

        val resolved = PsiUtils.resolveTargetElement(element!!)
        assertNotNull("Should resolve declaration", resolved)
        assertTrue("Should be PsiMethod", resolved is PsiMethod)
        assertEquals("myMethod", (resolved as PsiMethod).name)
    }

    fun testResolveTargetElement_ClassReferenceResolvesToClass() {
        // Create a target class
        myFixture.configureByText(
            "TargetClass.java",
            """
            public class TargetClass {
                public void method() {}
            }
            """.trimIndent()
        )

        // Create a class that references TargetClass
        val usageFile = myFixture.configureByText(
            "Usage.java",
            """
            public class Usage {
                private Target<caret>Class target;
            }
            """.trimIndent()
        )

        val element = usageFile.findElementAt(myFixture.caretOffset)
        assertNotNull("Should find element at caret", element)

        val resolved = PsiUtils.resolveTargetElement(element!!)
        assertNotNull("Should resolve to class", resolved)
        assertTrue("Should be PsiClass", resolved is PsiClass)
        assertEquals("TargetClass", (resolved as PsiClass).name)
    }

    fun testFindReferenceInParent_MaxDepthRespected() {
        val file = myFixture.configureByText(
            "Test.java",
            """
            public class Test {
                public void method() {
                    int x<caret> = 5;
                }
            }
            """.trimIndent()
        )

        val element = file.findElementAt(myFixture.caretOffset)
        assertNotNull("Should find element at caret", element)

        // With depth 0, should return null (no search)
        val ref0 = PsiUtils.findReferenceInParent(element!!, 0)
        assertNull("Depth 0 should return null", ref0)
    }

    fun testFindReferenceInParent_ReturnsNullAtRoot() {
        val file = myFixture.configureByText(
            "Root.java",
            """
            public class Ro<caret>ot {}
            """.trimIndent()
        )

        val element = file.findElementAt(myFixture.caretOffset)
        assertNotNull("Should find element at caret", element)

        // Walk up parent chain - at a class declaration, there's no reference to find
        // (The element IS the declaration, not a reference to one)
        val ref = PsiUtils.findReferenceInParent(element!!, 100)
        assertNull("Declaration should not have a reference in parent chain", ref)
    }

    fun testResolveTargetElement_UnresolvableReturnsNamedElement() {
        // Create a file with a local variable that has no reference
        val file = myFixture.configureByText(
            "Local.java",
            """
            public class Local {
                public void method() {
                    int local<caret>Var = 42;
                }
            }
            """.trimIndent()
        )

        val element = file.findElementAt(myFixture.caretOffset)
        assertNotNull("Should find element at caret", element)

        // When on a declaration (not a reference), should return the named element
        val resolved = PsiUtils.resolveTargetElement(element!!)
        assertNotNull("Should return named element for declaration", resolved)
    }
}
