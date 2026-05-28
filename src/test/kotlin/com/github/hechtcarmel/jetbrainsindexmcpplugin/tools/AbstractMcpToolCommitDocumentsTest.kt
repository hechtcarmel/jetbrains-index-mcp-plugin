package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class AbstractMcpToolCommitDocumentsTest : BasePlatformTestCase() {

    fun testCommitDocumentsCanRunFromBackgroundCoroutine() {
        val psiFile = myFixture.addFileToProject("src/Sample.kt", "class Sample")
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        assertNotNull("Expected document for test PSI file", document)
        val documentToCommit = document!!
        WriteCommandAction.runWriteCommandAction(project) {
            documentToCommit.insertString(documentToCommit.textLength, "\nfun added() = Unit\n")
        }
        assertTrue(
            "Test setup should leave a document change for commitDocuments to commit",
            PsiDocumentManager.getInstance(project).isUncommited(documentToCommit)
        )

        PlatformTestUtil.callOnBgtSynchronously({
            runBlocking {
                withContext(ModalityState.any().asContextElement()) {
                    ExposedCommitTool().commitFromTest(project)
                }
            }
        }, 10)

        assertFalse(
            "Document changes should be committed to PSI",
            PsiDocumentManager.getInstance(project).isUncommited(documentToCommit)
        )
    }

    private class ExposedCommitTool : AbstractMcpTool() {
        override val name: String = "test_commit_documents"
        override val description: String = "Test helper"
        override val inputSchema: JsonObject = buildJsonObject { }
        override val requiresPsiSync: Boolean = false

        suspend fun commitFromTest(project: Project) {
            commitDocuments(project)
        }

        override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
            commitDocuments(project)
            return createSuccessResult("ok")
        }
    }
}
