package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Platform tests that exercise the rename target-selection and rename-mode fixes against **real**
 * (non-Rider) Java PSI rather than mockk fakes. These run on IntelliJ IC in CI and are the
 * authoritative non-Rider verification for the PR #170 rename regressions:
 *
 *  - Reg 1: a cursor on a *usage* must rename the referenced declaration, not the enclosing method.
 *  - Reg 2: an explicit `line:0, column:0` must fail closed as an invalid position, never silently
 *    become a (destructive) file rename.
 */
class RenameSymbolToolRealPsiTest : BasePlatformTestCase() {

    private val demoSource = """
        package demo;

        public class Demo {
            void process() {}

            void caller() {
                process();
            }
        }
    """.trimIndent()

    fun testRenameCandidateOnUsageSelectsReferencedDeclarationNotEnclosingMethod() {
        val psiFile = myFixture.addFileToProject("demo/Demo.java", demoSource)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val text = psiFile.text

        // Offset inside the `process` identifier of the *call* `process();` (a usage, not a declaration).
        val usageOffset = text.indexOf("process();") + 1
        val leaf = psiFile.findElementAt(usageOffset)
        assertNotNull("Expected a leaf token at the usage offset", leaf)

        val resolution = RenameSymbolTool.inspectNamedElementCandidateForRename(leaf!!, requestedOffset = usageOffset)
        val candidate = resolution.candidate as? PsiNamedElement

        assertNotNull("Expected a rename candidate for the usage, reason=${resolution.selectedReason}", candidate)
        assertEquals(
            "Cursor on a usage must rename the referenced method, not the enclosing caller() " +
                "(reason=${resolution.selectedReason})",
            "process",
            candidate!!.name
        )
        // Prove the *branch*, not just the outcome: selection must come from the resolved reference
        // under the cursor (regression 1), and the target must be the method declaration.
        assertTrue(
            "Expected reference-resolved selection, got reason=${resolution.selectedReason}",
            resolution.selectedReason?.startsWith("selected reference-resolved declaration") == true
        )
        assertTrue("Expected the resolved method declaration", candidate is PsiMethod)
    }

    fun testRenameCandidateOnDeclarationNameSelectsThatDeclaration() {
        val psiFile = myFixture.addFileToProject("demo/Demo.java", demoSource)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val text = psiFile.text

        // Offset inside the `process` identifier of the *declaration* `void process() {}`.
        val declOffset = text.indexOf("void process()") + "void process".length - 1
        val leaf = psiFile.findElementAt(declOffset)
        assertNotNull("Expected a leaf token at the declaration offset", leaf)

        val resolution = RenameSymbolTool.inspectNamedElementCandidateForRename(leaf!!, requestedOffset = declOffset)
        val candidate = resolution.candidate as? PsiNamedElement

        assertNotNull("Expected a rename candidate for the declaration, reason=${resolution.selectedReason}", candidate)
        assertEquals(
            "Cursor on a declaration name must rename that declaration (reason=${resolution.selectedReason})",
            "process",
            candidate!!.name
        )
        // Prove the *branch*: a declaration whose own name identifier is under the cursor wins directly.
        assertTrue(
            "Expected enclosing-declaration selection, got reason=${resolution.selectedReason}",
            resolution.selectedReason?.startsWith("selected enclosing named element") == true
        )
        assertTrue("Expected the method declaration", candidate is PsiMethod)
    }

    fun testRenameRejectsExplicitZeroPositionAsInvalidPositionNotFileRename() = runBlocking {
        val tool = RenameSymbolTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "demo/Demo.java")
            put("line", 0)
            put("column", 0)
            put("newName", "Renamed")
        })

        assertTrue("Explicit line:0,column:0 must error, not silently file-rename", result.isError)
        val text = (result.content.first() as ContentBlock.Text).text
        assertTrue("Should report an invalid position error, got: $text", text.contains("Invalid position"))
    }
}
