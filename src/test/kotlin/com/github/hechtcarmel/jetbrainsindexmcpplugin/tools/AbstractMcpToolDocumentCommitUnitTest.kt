package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import junit.framework.TestCase
import java.io.File

class AbstractMcpToolDocumentCommitUnitTest : TestCase() {

    fun testCommitDocumentsDoesNotUseTransactionGuardSubmitTransactionAndWait() {
        val source = abstractMcpToolSource()

        assertFalse(
            "commitDocuments must not call TransactionGuard.submitTransactionAndWait from MCP background threads",
            source.contains("TransactionGuard.getInstance().submitTransactionAndWait")
        )
    }

    fun testCommitDocumentsUsesEdtWriteCommandToCommitAllDocuments() {
        val source = abstractMcpToolSource()

        assertTrue(
            "commitDocuments should switch to EDT before committing documents",
            source.contains("edtAction {")
        )
        assertTrue(
            "commitDocuments should keep commitAllDocuments inside a WriteCommandAction",
            source.contains("WriteCommandAction.runWriteCommandAction(project)")
        )
        assertTrue(
            "commitDocuments must still commit all project documents",
            source.contains("PsiDocumentManager.getInstance(project).commitAllDocuments()")
        )
    }

    private fun abstractMcpToolSource(): String {
        return File("src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/AbstractMcpTool.kt").readText()
    }
}
