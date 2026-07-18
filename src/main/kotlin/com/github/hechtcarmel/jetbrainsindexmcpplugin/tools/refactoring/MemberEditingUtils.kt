package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.*
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class MemberEditResult(
    val success: Boolean,
    val file: String,
    val message: String,
    val startLine: Int? = null,
    val endLine: Int? = null
)

@Serializable
data class MemberErrorResult(
    val error: String,
    val member: String? = null,
    val candidates: List<MemberCandidate>? = null,
    val hint: String? = null
)

@Serializable
data class MemberCandidate(
    val name: String,
    val kind: String,
    val signature: String? = null,
    val parameterCount: Int? = null,
    val line: Int
)

data class MemberEditPreparation(
    val psiFile: PsiFile,
    val document: Document,
    val member: ResolvedMember,
    val relativePath: String
)

data class InsertPreparation(
    val psiFile: PsiFile,
    val document: Document,
    val insertionOffset: Int,
    val relativePath: String
)

object MemberEditingUtils {

    fun resolvePsiFile(project: Project, filePath: String, virtualFile: com.intellij.openapi.vfs.VirtualFile?): PsiFile? {
        val vFile = virtualFile ?: return null
        return PsiManager.getInstance(project).findFile(vFile)
    }

    fun getResolver(psiFile: PsiFile, project: Project): MemberResolver? {
        return MemberResolverFactory.createResolver(psiFile, project)
    }

    fun getDocument(psiFile: PsiFile): Document? {
        return PsiDocumentManager.getInstance(psiFile.project).getDocument(psiFile)
    }

    fun reformatRange(project: Project, psiFile: PsiFile, startOffset: Int, endOffset: Int) {
        val clampedEnd = minOf(endOffset, psiFile.textLength)
        val clampedStart = minOf(startOffset, clampedEnd)
        if (clampedStart < clampedEnd) {
            CodeStyleManager.getInstance(project).reformatText(psiFile, clampedStart, clampedEnd)
        }
        try {
            com.intellij.codeInsight.actions.OptimizeImportsProcessor(project, psiFile).runWithoutProgress()
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
        }
    }

    fun commitDocuments(project: Project) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    fun saveToDisk() {
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    fun safeLineNumber(document: Document, offset: Int): Int {
        val clamped = offset.coerceIn(0, maxOf(0, document.textLength - 1))
        return document.getLineNumber(clamped) + 1
    }

    fun getOptionalString(arguments: JsonObject, key: String): String? {
        return arguments[key]?.jsonPrimitive?.content
    }

    fun getOptionalInt(arguments: JsonObject, key: String): Int? {
        return arguments[key]?.jsonPrimitive?.int
    }

    fun getOptionalBoolean(arguments: JsonObject, key: String, default: Boolean = true): Boolean {
        return arguments[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: default
    }

}
