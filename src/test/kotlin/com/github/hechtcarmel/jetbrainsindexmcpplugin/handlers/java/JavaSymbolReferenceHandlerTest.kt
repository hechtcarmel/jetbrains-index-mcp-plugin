package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JavaSymbolReferenceHandlerTest : BasePlatformTestCase() {

    private val handler = JavaSymbolReferenceHandler()

    // ── resolveSymbol: class resolution ────────────────────────────────────────

    fun testResolveClassByFqn() {
        myFixture.configureByText(
            "MyService.java",
            """
            package com.example;
            public class MyService {}
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.MyService")
        assertTrue("Should succeed", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiClass", element is PsiClass)
        assertEquals("MyService", element.name)
    }

    fun testResolveNestedClass() {
        myFixture.configureByText(
            "Outer.java",
            """
            package com.example;
            public class Outer {
                public static class Inner {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Outer.Inner")
        assertTrue("Should succeed", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiClass", element is PsiClass)
        assertEquals("Inner", element.name)
    }

    fun testResolveClassNotFound() {
        val result = handler.resolveSymbol(project, "com.example.NonExistent")
        assertTrue("Should fail", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("Should mention type not found", message.contains("not found"))
    }

    // ── resolveSymbol: field resolution ────────────────────────────────────────

    fun testResolveField() {
        myFixture.configureByText(
            "Data.java",
            """
            package com.example;
            public class Data {
                public String name;
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Data#name")
        assertTrue("Should succeed", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiField", element is PsiField)
        assertEquals("name", element.name)
    }

    fun testResolveInheritedField() {
        myFixture.addFileToProject(
            "com/example/Base.java",
            """
            package com.example;
            public class Base {
                public int count;
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/Child.java",
            """
            package com.example;
            public class Child extends Base {}
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Child#count")
        assertTrue("Should succeed", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiField", element is PsiField)
        assertEquals("count", element.name)
    }

    // ── resolveSymbol: method resolution ───────────────────────────────────────

    fun testResolveMethodNoParams() {
        myFixture.configureByText(
            "Service.java",
            """
            package com.example;
            public class Service {
                public void doWork() {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Service#doWork()")
        assertTrue("Should succeed", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("doWork", element.name)
    }

    fun testResolveMethodWithPrimitiveParam() {
        myFixture.configureByText(
            "Calc.java",
            """
            package com.example;
            public class Calc {
                public int add(int a) { return a; }
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Calc#add(int)")
        assertTrue("Should succeed", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("add", element.name)
    }

    fun testResolveMethodWithMultipleParams() {
        myFixture.configureByText(
            "Util.java",
            """
            package com.example;
            public class Util {
                public void process(int x, String y) {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Util#process(int, String)")
        assertTrue("Should succeed", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("process", element.name)
    }

    fun testResolveMethodWithVarargsParam() {
        myFixture.configureByText(
            "Logger.java",
            """
            package com.example;
            public class Logger {
                public void warn(String message, Object... args) {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Logger#warn(String, Object...)")
        assertTrue("Should succeed", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("warn", element.name)
        assertEquals("Object...", (element as PsiMethod).parameterList.parameters[1].type.presentableText)
    }

    fun testResolveMethodByNameOnlyWhenUnambiguous() {
        myFixture.configureByText(
            "Simple.java",
            """
            package com.example;
            public class Simple {
                public void run() {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Simple#run")
        assertTrue("Should succeed when method is unambiguous", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("run", element.name)
    }

    // ── resolveSymbol: overloaded methods ──────────────────────────────────────

    fun testResolveOverloadedMethodByParams() {
        myFixture.configureByText(
            "Overloaded.java",
            """
            package com.example;
            public class Overloaded {
                public void act(int x) {}
                public void act(String s) {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Overloaded#act(String)")
        assertTrue("Should succeed", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("act", element.name)
        assertEquals(1, (element as PsiMethod).parameterList.parameters.size)
        assertEquals("String", element.parameterList.parameters[0].type.presentableText)
    }

    fun testResolveOverloadedMethodByNameFailsWithOverloadList() {
        myFixture.configureByText(
            "Ambiguous.java",
            """
            package com.example;
            public class Ambiguous {
                public void act(int x) {}
                public void act(String s) {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Ambiguous#act")
        assertTrue("Should fail due to ambiguity", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("Should mention multiple methods", message.contains("Multiple methods"))
        assertTrue("Should list act(int)", message.contains("act(int)"))
        assertTrue("Should list act(String)", message.contains("act(String)"))
    }

    fun testResolveOverloadedMethodByNameListsAllOverloads() {
        myFixture.configureByText(
            "MultiOverload.java",
            """
            package com.example;
            public class MultiOverload {
                public void process() {}
                public void process(int x) {}
                public void process(int x, String y) {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.MultiOverload#process")
        assertTrue("Should fail due to ambiguity", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("Should list process()", message.contains("process()"))
        assertTrue("Should list process(int)", message.contains("process(int)"))
        assertTrue("Should list process(int, String)", message.contains("process(int, String)"))
    }

    fun testResolveMethodWrongParamsListsAvailableOverloads() {
        myFixture.configureByText(
            "Mismatch.java",
            """
            package com.example;
            public class Mismatch {
                public void doIt(int x) {}
                public void doIt(double x, String y) {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Mismatch#doIt(String)")
        assertTrue("Should fail for wrong param types", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("Should mention no methods match", message.contains("No methods match"))
        assertTrue("Should list doIt(int)", message.contains("doIt(int)"))
        assertTrue("Should list doIt(double, String)", message.contains("doIt(double, String)"))
    }

    fun testResolveMethodWrongParamCountListsAvailableOverloads() {
        myFixture.configureByText(
            "ParamCount.java",
            """
            package com.example;
            public class ParamCount {
                public void exec(int a, int b, int c) {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.ParamCount#exec(int)")
        assertTrue("Should fail", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("Should list exec(int, int, int)", message.contains("exec(int, int, int)"))
    }

    fun testResolveMethodUsesCanonicalNameForAmbiguousTypes() {
        // Two classes with the same simple name in different packages
        myFixture.addFileToProject(
            "com/alpha/Token.java",
            """
            package com.alpha;
            public class Token {}
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/beta/Token.java",
            """
            package com.beta;
            public class Token {}
            """.trimIndent()
        )
        myFixture.configureByText(
            "Disambiguate.java",
            """
            package com.example;
            public class Disambiguate {
                public void handle(com.alpha.Token x) {}
                public void handle(com.beta.Token x) {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Disambiguate#handle")
        assertTrue("Should fail due to ambiguity", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        // presentableText is "Token" for both, so canonical FQNs should be used
        assertTrue("Should list handle(com.alpha.Token)", message.contains("handle(com.alpha.Token)"))
        assertTrue("Should list handle(com.beta.Token)", message.contains("handle(com.beta.Token)"))
    }

    // ── resolveSymbol: member not found ────────────────────────────────────────

    fun testResolveMemberNotFound() {
        myFixture.configureByText(
            "Empty.java",
            """
            package com.example;
            public class Empty {}
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Empty#nonExistent")
        assertTrue("Should fail", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("Should mention member not found", message.contains("No member"))
    }

    // ── resolveSymbol: invalid format ──────────────────────────────────────────

    fun testResolveInvalidFormatReturnsFailure() {
        val result = handler.resolveSymbol(project, "not-a-valid-symbol")
        assertTrue("Should fail", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("Should mention format", message.contains("does not match expected format"))
    }

    // ── resolveSymbol: generics stripping ──────────────────────────────────────

    fun testResolveMethodWithGenericParams() {
        myFixture.configureByText(
            "GenericService.java",
            """
            package com.example;
            import java.util.List;
            public class GenericService {
                public void process(List items) {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.GenericService#process(List<String>)")
        assertTrue("Should succeed after stripping generics", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("process", element.name)
    }

    // ── resolveSymbol: inherited methods ───────────────────────────────────────

    fun testResolveInheritedMethod() {
        myFixture.addFileToProject(
            "com/example/BaseClass.java",
            """
            package com.example;
            public class BaseClass {
                public void baseMethod() {}
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/DerivedClass.java",
            """
            package com.example;
            public class DerivedClass extends BaseClass {}
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.DerivedClass#baseMethod()")
        assertTrue("Should resolve inherited method", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("baseMethod", element.name)
    }

    fun testResolveOverriddenMethodReturnsDerivedVersion() {
        myFixture.addFileToProject(
            "com/example/Animal.java",
            """
            package com.example;
            public class Animal {
                public String speak() { return ""; }
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/Dog.java",
            """
            package com.example;
            public class Dog extends Animal {
                @Override
                public String speak() { return "woof"; }
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Dog#speak()")
        assertTrue("Should succeed without ambiguity", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("speak", element.name)
        // Should resolve to Dog's override, not Animal's base method
        val containingClass = (element as PsiMethod).containingClass
        assertEquals("Dog", containingClass?.name)
    }

    fun testResolveOverriddenOverloadedMethodReturnsDerivedVersion() {
        myFixture.addFileToProject(
            "com/example/BaseProcessor.java",
            """
            package com.example;
            public class BaseProcessor {
                public void handle(int x) {}
                public void handle(String s) {}
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/DerivedProcessor.java",
            """
            package com.example;
            public class DerivedProcessor extends BaseProcessor {
                @Override
                public void handle(String s) {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.DerivedProcessor#handle(String)")
        assertTrue("Should succeed", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("handle", element.name)
        // Should resolve to DerivedProcessor's override, not BaseProcessor's
        val containingClass = (element as PsiMethod).containingClass
        assertEquals("DerivedProcessor", containingClass?.name)
        assertEquals(1, (element as PsiMethod).parameterList.parameters.size)
        assertEquals("String", element.parameterList.parameters[0].type.presentableText)
    }

    // ── resolveSymbol: constructor resolution ────────────────────────────────────

    fun testResolveDefaultConstructor() {
        myFixture.configureByText(
            "Widget.java",
            """
            package com.example;
            public class Widget {
                public Widget() {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Widget#Widget()")
        assertTrue("Should succeed", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertTrue("Should be constructor", (element as PsiMethod).isConstructor)
    }

    fun testResolveImplicitDefaultConstructorFails() {
        myFixture.configureByText(
            "Widget.java",
            """
            package com.example;
            public class Widget {}
            """.trimIndent()
        )

        // PSI only represents explicitly declared constructors
        val result = handler.resolveSymbol(project, "com.example.Widget#Widget()")
        assertTrue("Should fail for implicit constructor", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("Should mention member not found", message.contains("No member"))
    }

    fun testResolveConstructorWithParams() {
        myFixture.configureByText(
            "Widget.java",
            """
            package com.example;
            public class Widget {
                public Widget(String name, int size) {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Widget#Widget(String, int)")
        assertTrue("Should succeed", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertTrue("Should be constructor", (element as PsiMethod).isConstructor)
        assertEquals(2, element.parameterList.parameters.size)
    }

    fun testResolveOverloadedConstructorByParams() {
        myFixture.configureByText(
            "Widget.java",
            """
            package com.example;
            public class Widget {
                public Widget() {}
                public Widget(String name) {}
                public Widget(String name, int size) {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Widget#Widget(String)")
        assertTrue("Should succeed", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be constructor", (element as PsiMethod).isConstructor)
        assertEquals(1, element.parameterList.parameters.size)
        assertEquals("String", element.parameterList.parameters[0].type.presentableText)
    }

    fun testResolveOverloadedConstructorByNameFailsWithOverloadList() {
        myFixture.configureByText(
            "Widget.java",
            """
            package com.example;
            public class Widget {
                public Widget() {}
                public Widget(String name) {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Widget#Widget")
        assertTrue("Should fail due to ambiguity", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("Should mention multiple methods", message.contains("Multiple methods"))
        assertTrue("Should list Widget()", message.contains("Widget()"))
        assertTrue("Should list Widget(String)", message.contains("Widget(String)"))
    }

    fun testResolveConstructorWrongParamsListsOverloads() {
        myFixture.configureByText(
            "Widget.java",
            """
            package com.example;
            public class Widget {
                public Widget(int x) {}
                public Widget(String s, int x) {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Widget#Widget(double)")
        assertTrue("Should fail", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("Should mention no methods match", message.contains("No methods match"))
        assertTrue("Should list Widget(int)", message.contains("Widget(int)"))
        assertTrue("Should list Widget(String, int)", message.contains("Widget(String, int)"))
    }

    // ── resolveSymbol: enum constant as field ──────────────────────────────────

    fun testResolveEnumConstant() {
        myFixture.configureByText(
            "Color.java",
            """
            package com.example;
            public enum Color {
                RED, GREEN, BLUE
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Color#RED")
        assertTrue("Should resolve enum constant", result.isSuccess)
        val element = result.getOrThrow()
        assertEquals("RED", element.name)
    }

    // ── resolveSymbol: access modifier visibility ─────────────────────────────

    fun testResolvePrivateField() {
        myFixture.configureByText(
            "Secret.java",
            """
            package com.example;
            public class Secret {
                private String hidden;
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Secret#hidden")
        assertTrue("Should resolve private field", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiField", element is PsiField)
        assertEquals("hidden", element.name)
    }

    fun testResolveProtectedField() {
        myFixture.configureByText(
            "Parent.java",
            """
            package com.example;
            public class Parent {
                protected int score;
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Parent#score")
        assertTrue("Should resolve protected field", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiField", element is PsiField)
        assertEquals("score", element.name)
    }

    fun testResolvePackagePrivateField() {
        myFixture.configureByText(
            "Internal.java",
            """
            package com.example;
            public class Internal {
                String label;
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Internal#label")
        assertTrue("Should resolve package-private field", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiField", element is PsiField)
        assertEquals("label", element.name)
    }

    fun testResolvePrivateMethod() {
        myFixture.configureByText(
            "Helper.java",
            """
            package com.example;
            public class Helper {
                private void internalWork() {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Helper#internalWork()")
        assertTrue("Should resolve private method", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("internalWork", element.name)
    }

    fun testResolveProtectedMethod() {
        myFixture.configureByText(
            "Framework.java",
            """
            package com.example;
            public class Framework {
                protected void hook() {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Framework#hook()")
        assertTrue("Should resolve protected method", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("hook", element.name)
    }

    fun testResolvePrivateInnerClass() {
        myFixture.configureByText(
            "Container.java",
            """
            package com.example;
            public class Container {
                private static class Node {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Container.Node")
        assertTrue("Should resolve private inner class", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiClass", element is PsiClass)
        assertEquals("Node", element.name)
    }

    fun testResolveProtectedInnerClass() {
        myFixture.configureByText(
            "Enclosing.java",
            """
            package com.example;
            public class Enclosing {
                protected static class Entry {}
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.Enclosing.Entry")
        assertTrue("Should resolve protected inner class", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiClass", element is PsiClass)
        assertEquals("Entry", element.name)
    }

    // ── resolveSymbol: protected inherited members via derived class ──────────

    fun testResolveProtectedInheritedFieldViaDerivedClass() {
        myFixture.addFileToProject(
            "com/example/AbstractBase.java",
            """
            package com.example;
            public class AbstractBase {
                protected String config;
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/ConcreteChild.java",
            """
            package com.example;
            public class ConcreteChild extends AbstractBase {}
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.ConcreteChild#config")
        assertTrue("Should resolve protected inherited field via derived class", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiField", element is PsiField)
        assertEquals("config", element.name)
    }

    fun testResolveProtectedInheritedMethodViaDerivedClass() {
        myFixture.addFileToProject(
            "com/example/AbstractService.java",
            """
            package com.example;
            public class AbstractService {
                protected void initialize() {}
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/ConcreteService.java",
            """
            package com.example;
            public class ConcreteService extends AbstractService {}
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.ConcreteService#initialize()")
        assertTrue("Should resolve protected inherited method via derived class", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("initialize", element.name)
    }

    fun testResolveProtectedInheritedMethodWithParamsViaDerivedClass() {
        myFixture.addFileToProject(
            "com/example/BaseHandler.java",
            """
            package com.example;
            public class BaseHandler {
                protected void process(String input, int retries) {}
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/SpecificHandler.java",
            """
            package com.example;
            public class SpecificHandler extends BaseHandler {}
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "com.example.SpecificHandler#process(String, int)")
        assertTrue("Should resolve protected inherited method with params", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("process", element.name)
        assertEquals(2, (element as PsiMethod).parameterList.parameters.size)
    }

    fun testPackagePrivateFieldNotInheritedAcrossPackages() {
        myFixture.addFileToProject(
            "com/base/Base.java",
            """
            package com.base;
            public class Base {
                String hidden;
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/derived/Derived.java",
            """
            package com.derived;
            import com.base.Base;
            public class Derived extends Base {}
            """.trimIndent()
        )

        // Package-private fields are not inherited across packages
        val result = handler.resolveSymbol(project, "com.derived.Derived#hidden")
        assertTrue("Should fail — package-private field not visible across packages", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("Should mention member not found", message.contains("No member"))
    }

    fun testPackagePrivateMethodNotInheritedAcrossPackages() {
        myFixture.addFileToProject(
            "com/base/BaseService.java",
            """
            package com.base;
            public class BaseService {
                void internalProcess() {}
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/derived/DerivedService.java",
            """
            package com.derived;
            import com.base.BaseService;
            public class DerivedService extends BaseService {}
            """.trimIndent()
        )

        // Package-private methods are not inherited across packages
        val result = handler.resolveSymbol(project, "com.derived.DerivedService#internalProcess()")
        assertTrue("Should fail — package-private method not visible across packages", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("Should mention member not found", message.contains("No member"))
    }

    fun testPackagePrivateFieldInheritedWithinSamePackage() {
        myFixture.addFileToProject(
            "com/example/PkgBase.java",
            """
            package com.example;
            public class PkgBase {
                String shared;
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/PkgChild.java",
            """
            package com.example;
            public class PkgChild extends PkgBase {}
            """.trimIndent()
        )

        // Package-private fields ARE inherited within the same package
        val result = handler.resolveSymbol(project, "com.example.PkgChild#shared")
        assertTrue("Should succeed — package-private field visible within same package", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiField", element is PsiField)
        assertEquals("shared", element.name)
    }

    fun testPackagePrivateMethodInheritedWithinSamePackage() {
        myFixture.addFileToProject(
            "com/example/PkgBaseService.java",
            """
            package com.example;
            public class PkgBaseService {
                void internalWork() {}
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/PkgChildService.java",
            """
            package com.example;
            public class PkgChildService extends PkgBaseService {}
            """.trimIndent()
        )

        // Package-private methods ARE inherited within the same package
        val result = handler.resolveSymbol(project, "com.example.PkgChildService#internalWork()")
        assertTrue("Should succeed — package-private method visible within same package", result.isSuccess)
        val element = result.getOrThrow()
        assertTrue("Should be PsiMethod", element is PsiMethod)
        assertEquals("internalWork", element.name)
    }

    fun testPrivateFieldNotInheritedByDerivedClass() {
        myFixture.addFileToProject(
            "com/example/PrivateBase.java",
            """
            package com.example;
            public class PrivateBase {
                private String secret;
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/PrivateDerived.java",
            """
            package com.example;
            public class PrivateDerived extends PrivateBase {}
            """.trimIndent()
        )

        // Private fields are not inherited — should not be accessible via derived class
        val result = handler.resolveSymbol(project, "com.example.PrivateDerived#secret")
        assertTrue("Should fail — private fields are not inherited", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("Should mention member not found", message.contains("No member"))
    }

    fun testPrivateMethodNotInheritedByDerivedClass() {
        myFixture.addFileToProject(
            "com/example/PrivateMethodBase.java",
            """
            package com.example;
            public class PrivateMethodBase {
                private void secretMethod() {}
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/PrivateMethodDerived.java",
            """
            package com.example;
            public class PrivateMethodDerived extends PrivateMethodBase {}
            """.trimIndent()
        )

        // Private methods are not inherited — should not be accessible via derived class
        val result = handler.resolveSymbol(project, "com.example.PrivateMethodDerived#secretMethod()")
        assertTrue("Should fail — private methods are not inherited", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("Should mention member not found", message.contains("No member"))
    }
}
