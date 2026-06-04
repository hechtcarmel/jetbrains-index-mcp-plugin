package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

object PsiSourcePosition {

    data class Position(
        val line: Int,
        val column: Int
    )

    internal fun sourceOffset(element: PsiElement): Int? {
        return element.textOffset.takeIf { it >= 0 }
    }

    fun line(project: Project, element: PsiElement): Int? {
        return position(project, element)?.line
    }

    fun column(project: Project, element: PsiElement): Int? {
        return position(project, element)?.column
    }

    fun position(project: Project, element: PsiElement): Position? {
        val located = locate(project, element) ?: return null
        val lineIndex = located.document.getLineNumber(located.offset)
        val lineStartOffset = located.document.getLineStartOffset(lineIndex)

        return Position(
            line = lineIndex + 1,
            column = located.offset - lineStartOffset + 1
        )
    }

    private fun locate(project: Project, element: PsiElement): LocatedOffset? {
        val offset = sourceOffset(element) ?: return null
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        if (offset > document.textLength) return null

        return LocatedOffset(document, offset)
    }

    private data class LocatedOffset(
        val document: Document,
        val offset: Int
    )
}
