package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

class JavaMemberResolver(private val project: Project) : MemberResolver {
    override val languageId = "JAVA"

    override fun isAvailable(): Boolean = PluginDetectors.java.isAvailable

    override fun findClass(psiFile: PsiFile, className: String?): PsiElement? {
        if (psiFile !is PsiJavaFile) return null

        if (className == null) {
            val topLevelClasses = psiFile.classes.toList()
            return when {
                topLevelClasses.size == 1 -> topLevelClasses[0]
                else -> psiFile
            }
        }

        return findClassRecursive(psiFile.classes.toList(), className)
    }

    private fun findClassRecursive(classes: List<PsiClass>, name: String): PsiClass? {
        for (cls in classes) {
            if (cls.name == name) return cls
            val inner = findClassRecursive(cls.innerClasses.toList(), name)
            if (inner != null) return inner
        }
        return null
    }

    override fun findMembers(scope: PsiElement, memberName: String): List<ResolvedMember> {
        val results = mutableListOf<ResolvedMember>()

        if (scope is PsiJavaFile) {
            for (cls in scope.classes) {
                if (cls.name == memberName) {
                    resolveClass(cls)?.let { results.add(it) }
                }
            }
            return results
        }

        if (scope is PsiClass) {
            if (scope.name == memberName) {
                resolveClass(scope)?.let { results.add(it) }
            }
            for (field in scope.fields) {
                if (field.name == memberName) {
                    resolveField(field)?.let { results.add(it) }
                }
            }
            for (constructor in scope.constructors) {
                if (constructor.name == memberName || memberName == scope.name) {
                    resolveMethod(constructor)?.let { results.add(it) }
                }
            }
            for (method in scope.methods) {
                if (!method.isConstructor && method.name == memberName) {
                    resolveMethod(method)?.let { results.add(it) }
                }
            }
            for (innerClass in scope.innerClasses) {
                if (innerClass.name == memberName) {
                    resolveClass(innerClass)?.let { results.add(it) }
                }
            }
            if (memberName == "static" || memberName == "<clinit>") {
                for (initializer in scope.initializers) {
                    if (initializer.hasModifierProperty(PsiModifier.STATIC)) {
                        resolveInitializer(initializer)?.let { results.add(it) }
                    }
                }
            }
        }

        return results
    }

    override fun getInsertionOffset(scope: PsiElement, position: String, anchor: ResolvedMember?): Int? {
        if (scope !is PsiClass) return null

        return when (position) {
            "before" -> {
                requireNotNull(anchor) { "anchor required for 'before' position" }
                anchor.startOffset
            }
            "after" -> {
                requireNotNull(anchor) { "anchor required for 'after' position" }
                anchor.endOffset
            }
            "first" -> {
                val lBrace = scope.lBrace
                if (lBrace != null) lBrace.textRange.endOffset else scope.textRange.startOffset
            }
            else -> {
                val rBrace = scope.rBrace
                if (rBrace != null) rBrace.textRange.startOffset else scope.textRange.endOffset
            }
        }
    }

    private fun resolveMethod(method: PsiMethod): ResolvedMember? {
        val line = MemberResolverUtils.getLineNumber(project, method) ?: return null
        val body = method.body
        return ResolvedMember(
            element = method,
            name = method.name,
            kind = if (method.isConstructor) "constructor" else "method",
            signature = buildMethodSignature(method),
            parameterCount = method.parameterList.parametersCount,
            startOffset = method.textRange.startOffset,
            endOffset = method.textRange.endOffset,
            bodyStartOffset = body?.lBrace?.let { it.textRange.endOffset },
            bodyEndOffset = body?.rBrace?.let { it.textRange.startOffset },
            line = line
        )
    }

    private fun resolveField(field: PsiField): ResolvedMember? {
        val line = MemberResolverUtils.getLineNumber(project, field) ?: return null
        val initializer = field.initializer
        return ResolvedMember(
            element = field,
            name = field.name,
            kind = "field",
            signature = field.type.presentableText,
            parameterCount = null,
            startOffset = field.textRange.startOffset,
            endOffset = field.textRange.endOffset,
            bodyStartOffset = initializer?.textRange?.startOffset,
            bodyEndOffset = initializer?.textRange?.endOffset,
            line = line
        )
    }

    private fun resolveClass(cls: PsiClass): ResolvedMember? {
        val line = MemberResolverUtils.getLineNumber(project, cls) ?: return null
        return ResolvedMember(
            element = cls,
            name = cls.name ?: "anonymous",
            kind = "class",
            signature = null,
            parameterCount = null,
            startOffset = cls.textRange.startOffset,
            endOffset = cls.textRange.endOffset,
            bodyStartOffset = cls.lBrace?.let { it.textRange.endOffset },
            bodyEndOffset = cls.rBrace?.let { it.textRange.startOffset },
            line = line
        )
    }

    private fun resolveInitializer(initializer: PsiClassInitializer): ResolvedMember? {
        val line = MemberResolverUtils.getLineNumber(project, initializer) ?: return null
        val body = initializer.body
        return ResolvedMember(
            element = initializer,
            name = "static",
            kind = "initializer",
            signature = null,
            parameterCount = null,
            startOffset = initializer.textRange.startOffset,
            endOffset = initializer.textRange.endOffset,
            bodyStartOffset = body.lBrace?.let { it.textRange.endOffset },
            bodyEndOffset = body.rBrace?.let { it.textRange.startOffset },
            line = line
        )
    }

    private fun buildMethodSignature(method: PsiMethod): String {
        val params = method.parameterList.parameters.joinToString(", ") {
            "${it.type.presentableText} ${it.name}"
        }
        val returnType = if (method.isConstructor) "" else "${method.returnType?.presentableText ?: "void"} "
        return "$returnType${method.name}($params)"
    }
}
