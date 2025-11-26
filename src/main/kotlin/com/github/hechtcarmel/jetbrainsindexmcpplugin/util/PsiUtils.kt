package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil

object PsiUtils {

    fun findElementAtPosition(
        project: Project,
        file: String,
        line: Int,
        column: Int
    ): PsiElement? {
        val psiFile = getPsiFile(project, file) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        val lineIndex = line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(lineIndex)
        val lineEndOffset = document.getLineEndOffset(lineIndex)
        val offset = lineStartOffset + (column - 1)

        return if (offset <= lineEndOffset) {
            psiFile.findElementAt(offset)
        } else {
            null
        }
    }

    fun getPsiFile(project: Project, relativePath: String): PsiFile? {
        val virtualFile = getVirtualFile(project, relativePath) ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile)
    }

    fun getVirtualFile(project: Project, relativePath: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        val fullPath = if (relativePath.startsWith("/")) relativePath else "$basePath/$relativePath"
        return LocalFileSystem.getInstance().findFileByPath(fullPath)
    }

    fun getContainingClass(element: PsiElement): PsiClass? {
        return PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
    }

    fun getContainingMethod(element: PsiElement): PsiMethod? {
        return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
    }

    fun findNamedElement(element: PsiElement): PsiNamedElement? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is PsiNamedElement && current.name != null) {
                return current
            }
            current = current.parent
        }
        return null
    }

    fun extractDocumentation(element: PsiElement): String? {
        // Try to find doc comment
        val docComment = when (element) {
            is PsiMethod -> element.docComment
            is PsiClass -> element.docComment
            else -> null
        }

        return docComment?.text?.let { text ->
            // Clean up doc comment
            text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "/**" && it != "*/" }
                .joinToString("\n") { line ->
                    line.removePrefix("*").trim()
                }
        }
    }
}
