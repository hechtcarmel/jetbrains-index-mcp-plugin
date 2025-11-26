package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions.IndexNotReadyException
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

abstract class AbstractMcpTool : McpTool {

    protected val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    protected fun requireSmartMode(project: Project) {
        if (DumbService.isDumb(project)) {
            throw IndexNotReadyException("IDE is in dumb mode, indexes not available")
        }
    }

    protected fun <T> readAction(action: () -> T): T {
        return ReadAction.compute<T, Throwable>(action)
    }

    protected fun writeAction(project: Project, commandName: String, action: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project, commandName, null, { action() })
    }

    protected fun resolveFile(project: Project, relativePath: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        val fullPath = if (relativePath.startsWith("/")) relativePath else "$basePath/$relativePath"
        return LocalFileSystem.getInstance().findFileByPath(fullPath)
    }

    protected fun getPsiFile(project: Project, relativePath: String): PsiFile? {
        val virtualFile = resolveFile(project, relativePath) ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile)
    }

    protected fun findPsiElement(
        project: Project,
        file: String,
        line: Int,
        column: Int
    ): PsiElement? {
        val psiFile = getPsiFile(project, file) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        val offset = getOffset(document, line, column) ?: return null
        return psiFile.findElementAt(offset)
    }

    protected fun getOffset(document: Document, line: Int, column: Int): Int? {
        val lineIndex = line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(lineIndex)
        val lineEndOffset = document.getLineEndOffset(lineIndex)
        val columnOffset = column - 1

        val offset = lineStartOffset + columnOffset
        return if (offset <= lineEndOffset) offset else lineEndOffset
    }

    protected fun getLineText(document: Document, line: Int): String {
        val lineIndex = line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) return ""

        val startOffset = document.getLineStartOffset(lineIndex)
        val endOffset = document.getLineEndOffset(lineIndex)
        return document.getText(TextRange(startOffset, endOffset))
    }

    protected fun getRelativePath(project: Project, virtualFile: VirtualFile): String {
        val basePath = project.basePath ?: return virtualFile.path
        return virtualFile.path.removePrefix(basePath).removePrefix("/")
    }

    protected fun createSuccessResult(text: String): ToolCallResult {
        return ToolCallResult(
            content = listOf(ContentBlock.Text(text = text)),
            isError = false
        )
    }

    protected fun createErrorResult(message: String): ToolCallResult {
        return ToolCallResult(
            content = listOf(ContentBlock.Text(text = message)),
            isError = true
        )
    }

    protected inline fun <reified T> createJsonResult(data: T): ToolCallResult {
        val jsonText = json.encodeToString(data)
        return ToolCallResult(
            content = listOf(ContentBlock.Text(text = jsonText)),
            isError = false
        )
    }
}
