package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

data class ResolvedMember(
    val element: PsiElement,
    val name: String,
    val kind: String,
    val signature: String?,
    val parameterCount: Int?,
    val startOffset: Int,
    val endOffset: Int,
    val bodyStartOffset: Int?,
    val bodyEndOffset: Int?,
    val line: Int
)

interface MemberResolver {
    val languageId: String
    fun isAvailable(): Boolean
    fun findClass(psiFile: PsiFile, className: String?): PsiElement?
    fun findMembers(scope: PsiElement, memberName: String): List<ResolvedMember>
    fun getInsertionOffset(scope: PsiElement, position: String, anchor: ResolvedMember?): Int?
}

object MemberResolverFactory {
    fun createResolver(psiFile: PsiFile, project: Project): MemberResolver? {
        return when (psiFile.language.id) {
            "JAVA" -> if (PluginDetectors.java.isAvailable) JavaMemberResolver(project) else null
            "kotlin" -> if (PluginDetectors.kotlin.isAvailable) KotlinMemberResolver(project) else null
            else -> null
        }
    }
}

object MemberResolverUtils {
    fun getLineNumber(project: Project, element: PsiElement): Int? {
        val file = element.containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val offset = element.textOffset
        if (offset > document.textLength) return null
        return document.getLineNumber(offset) + 1
    }

    fun getEndLineNumber(project: Project, element: PsiElement): Int? {
        val file = element.containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val endOffset = element.textRange?.endOffset ?: return null
        if (endOffset > document.textLength) return null
        return document.getLineNumber(endOffset - 1) + 1
    }

    fun disambiguate(
        members: List<ResolvedMember>,
        memberName: String,
        parameterCount: Int?,
        line: Int?
    ): Result<ResolvedMember> {
        if (members.isEmpty()) {
            return Result.failure(MemberNotFoundException(memberName))
        }
        if (members.size == 1) {
            return Result.success(members[0])
        }

        var filtered = members
        if (parameterCount != null) {
            filtered = filtered.filter { it.parameterCount == parameterCount }
        }
        if (line != null) {
            filtered = filtered.filter { it.line == line }
        }

        if (filtered.size > 1 && parameterCount == null && line == null) {
            val classDecl = filtered.singleOrNull { it.kind == "class" }
            if (classDecl != null) return Result.success(classDecl)
        }

        return when {
            filtered.isEmpty() -> Result.failure(
                AmbiguousMemberException(memberName, members, "No match with the given constraints.")
            )
            filtered.size == 1 -> Result.success(filtered[0])
            else -> Result.failure(
                AmbiguousMemberException(memberName, filtered, "Specify parameterCount or line to disambiguate.")
            )
        }
    }
}

class MemberNotFoundException(val memberName: String) : Exception("Member '$memberName' not found")

class AmbiguousMemberException(
    val memberName: String,
    val candidates: List<ResolvedMember>,
    val hint: String
) : Exception("Multiple members named '$memberName' found. $hint")

class MemberClassNotFoundException(val className: String, val availableClasses: List<String>) :
    Exception("Class '$className' not found")
