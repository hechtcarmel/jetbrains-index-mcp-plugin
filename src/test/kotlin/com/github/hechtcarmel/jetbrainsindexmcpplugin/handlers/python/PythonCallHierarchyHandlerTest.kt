package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.python

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallHierarchyData
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.hierarchy.call.PyStaticCallHierarchyUtil
import com.jetbrains.python.psi.PyFunction
import io.mockk.every
import io.mockk.mockk

class PythonCallHierarchyHandlerTest : BasePlatformTestCase() {

    override fun tearDown() {
        PyStaticCallHierarchyUtil.callers = emptyMap()
        super.tearDown()
    }

    fun testCallersUsePyStaticCallHierarchy() {
        val psiFile = myFixture.addFileToProject(
            "sample.py",
            """
            def helper_one(x: int) -> int:
                return x + 1


            def helper_two(x: int) -> int:
                return x * 2


            def target(value: int) -> int:
                a = helper_one(value)
                b = helper_two(a)
                return b


            def alpha() -> int:
                return target(1)


            def beta() -> int:
                return target(2)


            def gamma() -> int:
                return target(3)
            """.trimIndent()
        )

        val target = mockPyFunction("target", psiFile)
        val alpha = mockPyFunction("alpha", psiFile)
        val beta = mockPyFunction("beta", psiFile)
        val gamma = mockPyFunction("gamma", psiFile)

        val usage = mockk<PsiElement>(relaxed = true)
        PyStaticCallHierarchyUtil.callers = linkedMapOf(
            alpha to listOf(usage),
            beta to listOf(usage),
            gamma to listOf(usage)
        )

        val result = PythonCallHierarchyHandler().getCallHierarchy(
            element = target,
            project = project,
            direction = "callers",
            depth = 1
        )

        assertCallNames(result, listOf("alpha", "beta", "gamma"))
    }

    private fun assertCallNames(result: CallHierarchyData?, expectedNames: List<String>) {
        assertNotNull("Call hierarchy should be returned", result)
        assertEquals(expectedNames, result!!.calls.map { it.name })
    }

    private fun mockPyFunction(name: String, psiFile: PsiFile): PyFunction {
        val document = psiFile.viewProvider.document ?: error("Expected a document for ${psiFile.name}")
        val offset = document.text.indexOf("def $name")
        check(offset >= 0) { "Could not find function $name in ${psiFile.name}" }

        return mockk(relaxed = true) {
            every { getName() } returns name
            every { containingFile } returns psiFile
            every { textOffset } returns offset
            every { parent } returns null
        }
    }
}
