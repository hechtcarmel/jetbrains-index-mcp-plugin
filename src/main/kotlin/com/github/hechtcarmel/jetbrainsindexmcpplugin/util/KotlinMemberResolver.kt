package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class KotlinMemberResolver(private val project: Project) : MemberResolver {

    companion object {
        private val LOG = logger<KotlinMemberResolver>()

        private val ktFileClass: Class<*>? by lazy {
            try { Class.forName("org.jetbrains.kotlin.psi.KtFile") } catch (_: ClassNotFoundException) { null }
        }
        private val ktClassClass: Class<*>? by lazy {
            try { Class.forName("org.jetbrains.kotlin.psi.KtClass") } catch (_: ClassNotFoundException) { null }
        }
        private val ktClassOrObjectClass: Class<*>? by lazy {
            try { Class.forName("org.jetbrains.kotlin.psi.KtClassOrObject") } catch (_: ClassNotFoundException) { null }
        }
        private val ktNamedFunctionClass: Class<*>? by lazy {
            try { Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction") } catch (_: ClassNotFoundException) { null }
        }
        private val ktPropertyClass: Class<*>? by lazy {
            try { Class.forName("org.jetbrains.kotlin.psi.KtProperty") } catch (_: ClassNotFoundException) { null }
        }
        private val ktObjectDeclarationClass: Class<*>? by lazy {
            try { Class.forName("org.jetbrains.kotlin.psi.KtObjectDeclaration") } catch (_: ClassNotFoundException) { null }
        }
        private val ktNamedDeclarationClass: Class<*>? by lazy {
            try { Class.forName("org.jetbrains.kotlin.psi.KtNamedDeclaration") } catch (_: ClassNotFoundException) { null }
        }
        private val ktClassBodyClass: Class<*>? by lazy {
            try { Class.forName("org.jetbrains.kotlin.psi.KtClassBody") } catch (_: ClassNotFoundException) { null }
        }
    }

    override val languageId = "kotlin"

    override fun isAvailable(): Boolean = PluginDetectors.kotlin.isAvailable && ktFileClass != null

    override fun findClass(psiFile: PsiFile, className: String?): PsiElement? {
        if (ktFileClass?.isInstance(psiFile) != true) return null

        if (className == null) {
            val declarations = try { getDeclarations(psiFile) } catch (_: Exception) { emptyList() }
            val classes = declarations.filter {
                ktClassClass?.isInstance(it) == true || ktObjectDeclarationClass?.isInstance(it) == true
            }
            return when {
                classes.size == 1 -> classes[0]
                else -> psiFile
            }
        }

        return try {
            val declarations = getDeclarations(psiFile)
            findClassInDeclarations(declarations, className)
        } catch (e: Exception) {
            LOG.debug("Failed to find Kotlin class: ${e.message}")
            null
        }
    }

    private fun findClassInDeclarations(declarations: List<PsiElement>, name: String): PsiElement? {
        for (decl in declarations) {
            if ((ktClassClass?.isInstance(decl) == true || ktObjectDeclarationClass?.isInstance(decl) == true)
                && getName(decl) == name) {
                return decl
            }
            if (ktClassClass?.isInstance(decl) == true || ktObjectDeclarationClass?.isInstance(decl) == true) {
                val bodyDecls = getBodyDeclarations(decl)
                val found = findClassInDeclarations(bodyDecls, name)
                if (found != null) return found
            }
        }
        return null
    }

    override fun findMembers(scope: PsiElement, memberName: String): List<ResolvedMember> {
        val declarations = if (ktFileClass?.isInstance(scope) == true) {
            getDeclarations(scope)
        } else if (ktClassOrObjectClass?.isInstance(scope) == true) {
            getBodyDeclarations(scope)
        } else {
            return emptyList()
        }

        val results = declarations.filter { getName(it) == memberName }.mapNotNull { resolveDeclaration(it) }
        if (results.isEmpty() && ktClassOrObjectClass?.isInstance(scope) == true && getName(scope) == memberName) {
            return listOfNotNull(resolveDeclaration(scope))
        }
        return results
    }

    override fun getInsertionOffset(scope: PsiElement, position: String, anchor: ResolvedMember?): Int? {
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
                if (ktClassOrObjectClass?.isInstance(scope) == true) {
                    val body = getBody(scope) ?: return null
                    val lBrace = try {
                        body.javaClass.getMethod("getLBrace").invoke(body) as? PsiElement
                    } catch (_: Exception) { null }
                    lBrace?.textRange?.endOffset ?: return null
                } else scope.textRange.startOffset
            }
            else -> {
                if (ktClassOrObjectClass?.isInstance(scope) == true) {
                    val body = getBody(scope) ?: return null
                    val rBrace = try {
                        body.javaClass.getMethod("getRBrace").invoke(body) as? PsiElement
                    } catch (_: Exception) { null }
                        rBrace?.textRange?.startOffset ?: return null
                } else scope.textRange.endOffset
            }
        }
    }

    private fun resolveDeclaration(element: PsiElement): ResolvedMember? {
        val name = getName(element) ?: return null
        val line = MemberResolverUtils.getLineNumber(project, element) ?: return null

        return when {
            ktNamedFunctionClass?.isInstance(element) == true -> {
                val bodyExpr = getBodyExpression(element)
                val blockBody = getBlockBody(element)
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = "function",
                    signature = buildFunctionSignature(element),
                    parameterCount = getParameterCount(element),
                    startOffset = element.textRange.startOffset,
                    endOffset = element.textRange.endOffset,
                    bodyStartOffset = blockBody?.first ?: bodyExpr?.textRange?.startOffset,
                    bodyEndOffset = blockBody?.second ?: bodyExpr?.textRange?.endOffset,
                    line = line
                )
            }
            ktPropertyClass?.isInstance(element) == true -> {
                val initializer = getInitializer(element)
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = "property",
                    signature = getTypeReference(element),
                    parameterCount = null,
                    startOffset = element.textRange.startOffset,
                    endOffset = element.textRange.endOffset,
                    bodyStartOffset = initializer?.textRange?.startOffset,
                    bodyEndOffset = initializer?.textRange?.endOffset,
                    line = line
                )
            }
            ktClassClass?.isInstance(element) == true || ktObjectDeclarationClass?.isInstance(element) == true -> {
                val body = getBody(element)
                ResolvedMember(
                    element = element,
                    name = name,
                    kind = if (ktObjectDeclarationClass?.isInstance(element) == true) "object" else "class",
                    signature = null,
                    parameterCount = null,
                    startOffset = element.textRange.startOffset,
                    endOffset = element.textRange.endOffset,
                    bodyStartOffset = body?.let { getLBraceEnd(it) },
                    bodyEndOffset = body?.let { getRBraceStart(it) },
                    line = line
                )
            }
            else -> null
        }
    }

    private fun getDeclarations(element: PsiElement): List<PsiElement> {
        return try {
            val method = element.javaClass.getMethod("getDeclarations")
            @Suppress("UNCHECKED_CAST")
            (method.invoke(element) as? List<PsiElement>) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getBodyDeclarations(classElement: PsiElement): List<PsiElement> {
        val body = getBody(classElement) ?: return emptyList()
        return try {
            val method = body.javaClass.getMethod("getDeclarations")
            @Suppress("UNCHECKED_CAST")
            (method.invoke(body) as? List<PsiElement>) ?: emptyList()
        } catch (_: Exception) {
            body.children.toList()
        }
    }

    private fun getBody(element: PsiElement): PsiElement? {
        return try {
            element.javaClass.getMethod("getBody").invoke(element) as? PsiElement
        } catch (_: Exception) {
            null
        }
    }

    private fun getBodyExpression(function: PsiElement): PsiElement? {
        return try {
            function.javaClass.getMethod("getBodyExpression").invoke(function) as? PsiElement
        } catch (_: Exception) {
            null
        }
    }

    private fun getBlockBody(function: PsiElement): Pair<Int, Int>? {
        val bodyExpr = getBodyExpression(function) ?: return null
        val ktBlockExprClass = try {
            Class.forName("org.jetbrains.kotlin.psi.KtBlockExpression")
        } catch (_: ClassNotFoundException) { return null }

        if (!ktBlockExprClass.isInstance(bodyExpr)) return null

        return try {
            val lBrace = bodyExpr.javaClass.getMethod("getLBrace").invoke(bodyExpr) as? PsiElement
            val rBrace = bodyExpr.javaClass.getMethod("getRBrace").invoke(bodyExpr) as? PsiElement
            if (lBrace != null && rBrace != null) {
                Pair(lBrace.textRange.endOffset, rBrace.textRange.startOffset)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun getInitializer(property: PsiElement): PsiElement? {
        return try {
            property.javaClass.getMethod("getInitializer").invoke(property) as? PsiElement
        } catch (_: Exception) {
            null
        }
    }

    private fun getName(element: PsiElement): String? {
        if (ktNamedDeclarationClass?.isInstance(element) != true) return null
        return try {
            element.javaClass.getMethod("getName").invoke(element) as? String
        } catch (_: Exception) {
            null
        }
    }

    private fun getParameterCount(function: PsiElement): Int? {
        return try {
            val paramList = function.javaClass.getMethod("getValueParameterList").invoke(function) as? PsiElement
                ?: return 0
            val params = paramList.javaClass.getMethod("getParameters").invoke(paramList) as? List<*>
            params?.size ?: 0
        } catch (_: Exception) {
            null
        }
    }

    private fun getTypeReference(property: PsiElement): String? {
        return try {
            val typeRef = property.javaClass.getMethod("getTypeReference").invoke(property) as? PsiElement
            typeRef?.text
        } catch (_: Exception) {
            null
        }
    }

    private fun buildFunctionSignature(function: PsiElement): String {
        return try {
            val paramList = function.javaClass.getMethod("getValueParameterList").invoke(function) as? PsiElement
            val params = if (paramList != null) {
                val parameters = paramList.javaClass.getMethod("getParameters").invoke(paramList) as? List<*>
                parameters?.filterIsInstance<PsiElement>()?.joinToString(", ") { it.text } ?: ""
            } else ""
            val name = getName(function) ?: "unknown"
            "$name($params)"
        } catch (_: Exception) {
            getName(function) ?: "unknown"
        }
    }

    private fun getLBraceEnd(body: PsiElement): Int? {
        return try {
            val lBrace = body.javaClass.getMethod("getLBrace").invoke(body) as? PsiElement
            lBrace?.textRange?.endOffset
        } catch (_: Exception) { null }
    }

    private fun getRBraceStart(body: PsiElement): Int? {
        return try {
            val rBrace = body.javaClass.getMethod("getRBrace").invoke(body) as? PsiElement
            rBrace?.textRange?.startOffset
        } catch (_: Exception) { null }
    }
}
