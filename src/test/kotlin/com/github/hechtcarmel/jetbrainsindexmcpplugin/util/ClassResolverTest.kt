package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.PsiElement
import com.intellij.testFramework.DumbModeTestUtils

class ClassResolverTest : McpPlatformTestCase() {

    fun testFindClassByNameResolvesInSmartMode() {
        myFixture.addFileToProject("com/example/Foo.java", "package com.example;\npublic class Foo {}")

        val resolved = ReadAction.compute<PsiElement?, Throwable> {
            ClassResolver.findClassByName(project, "com.example.Foo")
        }

        assertNotNull("Class should resolve by FQN in smart mode", resolved)
    }

    fun testFindClassByNameInDumbModePropagatesIndexNotReadyInsteadOfNull() {
        myFixture.addFileToProject("com/example/Bar.java", "package com.example;\npublic class Bar {}")

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            try {
                val result = ReadAction.compute<PsiElement?, Throwable> {
                    ClassResolver.findClassByName(project, "com.example.Bar")
                }
                fail(
                    "Expected IndexNotReadyException during dumb mode, but got: $result — " +
                        "swallowing it misreports 'Class not found' while the IDE is indexing"
                )
            } catch (expected: IndexNotReadyException) {
                // AbstractMcpTool.execute converts this into the retry-after-indexing error.
            }
        }
    }
}
