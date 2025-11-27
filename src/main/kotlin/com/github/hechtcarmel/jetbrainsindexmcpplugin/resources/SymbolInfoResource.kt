package com.github.hechtcarmel.jetbrainsindexmcpplugin.resources

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ResourceUris
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ResourceContent
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SymbolInfoResource : McpResource {

    override val uri = ResourceUris.SYMBOL_INFO_PATTERN

    override val name = "Symbol Info"

    override val description = "Get detailed information about a symbol by its fully qualified name"

    override val mimeType = "application/json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    override suspend fun read(project: Project): ResourceContent {
        return ResourceContent(
            uri = uri,
            mimeType = mimeType,
            text = json.encodeToString(SymbolInfoError(
                error = "fqn_required",
                message = "Please provide a fully qualified name. URI format: symbol://info/{fqn}"
            ))
        )
    }

    suspend fun readWithFqn(project: Project, fqn: String): ResourceContent {
        return ReadAction.compute<ResourceContent, Throwable> {
            if (DumbService.isDumb(project)) {
                return@compute createErrorResponse(ErrorMessages.INDEX_NOT_READY)
            }

            // Try to find the symbol
            val psiFacade = JavaPsiFacade.getInstance(project)
            val scope = GlobalSearchScope.allScope(project)

            // First try to find as a class
            val psiClass = psiFacade.findClass(fqn, scope)
            if (psiClass != null) {
                return@compute createClassResponse(project, psiClass)
            }

            // Try to find as a method or field (format: Class#member or Class.member)
            val memberSeparatorIndex = maxOf(fqn.lastIndexOf('#'), fqn.lastIndexOf('.'))
            if (memberSeparatorIndex > 0) {
                val className = fqn.substring(0, memberSeparatorIndex)
                val memberName = fqn.substring(memberSeparatorIndex + 1)

                val containingClass = psiFacade.findClass(className, scope)
                if (containingClass != null) {
                    // Try to find method
                    val method = containingClass.findMethodsByName(memberName, false).firstOrNull()
                    if (method != null) {
                        return@compute createMethodResponse(project, method)
                    }

                    // Try to find field
                    val field = containingClass.findFieldByName(memberName, false)
                    if (field != null) {
                        return@compute createFieldResponse(project, field)
                    }
                }
            }

            createErrorResponse("Symbol not found: $fqn")
        }
    }

    private fun createClassResponse(project: Project, psiClass: PsiClass): ResourceContent {
        val document = psiClass.containingFile?.let {
            PsiDocumentManager.getInstance(project).getDocument(it)
        }
        val line = document?.let { it.getLineNumber(psiClass.textOffset) + 1 }

        val basePath = project.basePath ?: ""
        val relativePath = psiClass.containingFile?.virtualFile?.path
            ?.removePrefix(basePath)
            ?.removePrefix("/")

        val kind = when {
            psiClass.isInterface -> "INTERFACE"
            psiClass.isEnum -> "ENUM"
            psiClass.isAnnotationType -> "ANNOTATION"
            psiClass.isRecord -> "RECORD"
            else -> "CLASS"
        }

        val result = SymbolInfoResult(
            name = psiClass.name ?: "unknown",
            qualifiedName = psiClass.qualifiedName,
            kind = kind,
            type = psiClass.superClass?.qualifiedName,
            modifiers = getModifiers(psiClass),
            documentation = getDocumentation(psiClass),
            file = relativePath,
            line = line,
            containingClass = psiClass.containingClass?.qualifiedName,
            members = getMemberSummary(psiClass)
        )

        return ResourceContent(
            uri = "${ResourceUris.SYMBOL_INFO_PREFIX}${psiClass.qualifiedName}",
            mimeType = mimeType,
            text = json.encodeToString(result)
        )
    }

    private fun createMethodResponse(project: Project, method: PsiMethod): ResourceContent {
        val document = method.containingFile?.let {
            PsiDocumentManager.getInstance(project).getDocument(it)
        }
        val line = document?.let { it.getLineNumber(method.textOffset) + 1 }

        val basePath = project.basePath ?: ""
        val relativePath = method.containingFile?.virtualFile?.path
            ?.removePrefix(basePath)
            ?.removePrefix("/")

        val fqn = "${method.containingClass?.qualifiedName}#${method.name}"

        val signature = buildString {
            append(method.name)
            append("(")
            append(method.parameterList.parameters.joinToString(", ") {
                "${it.name}: ${it.type.presentableText}"
            })
            append("): ")
            append(method.returnType?.presentableText ?: "void")
        }

        val result = SymbolInfoResult(
            name = method.name,
            qualifiedName = fqn,
            kind = if (method.isConstructor) "CONSTRUCTOR" else "METHOD",
            type = method.returnType?.presentableText,
            modifiers = getModifiers(method),
            documentation = getDocumentation(method),
            file = relativePath,
            line = line,
            containingClass = method.containingClass?.qualifiedName,
            signature = signature
        )

        return ResourceContent(
            uri = "${ResourceUris.SYMBOL_INFO_PREFIX}$fqn",
            mimeType = mimeType,
            text = json.encodeToString(result)
        )
    }

    private fun createFieldResponse(project: Project, field: PsiField): ResourceContent {
        val document = field.containingFile?.let {
            PsiDocumentManager.getInstance(project).getDocument(it)
        }
        val line = document?.let { it.getLineNumber(field.textOffset) + 1 }

        val basePath = project.basePath ?: ""
        val relativePath = field.containingFile?.virtualFile?.path
            ?.removePrefix(basePath)
            ?.removePrefix("/")

        val fqn = "${field.containingClass?.qualifiedName}#${field.name}"

        val result = SymbolInfoResult(
            name = field.name,
            qualifiedName = fqn,
            kind = "FIELD",
            type = field.type.presentableText,
            modifiers = getModifiers(field),
            documentation = getDocumentation(field),
            file = relativePath,
            line = line,
            containingClass = field.containingClass?.qualifiedName
        )

        return ResourceContent(
            uri = "${ResourceUris.SYMBOL_INFO_PREFIX}$fqn",
            mimeType = mimeType,
            text = json.encodeToString(result)
        )
    }

    private fun createErrorResponse(message: String): ResourceContent {
        return ResourceContent(
            uri = uri,
            mimeType = "application/json",
            text = json.encodeToString(SymbolInfoError(
                error = "symbol_not_found",
                message = message
            ))
        )
    }

    private fun getModifiers(element: PsiModifierListOwner): List<String> {
        val modifiers = mutableListOf<String>()
        val modifierList = element.modifierList ?: return modifiers

        listOf(
            "public", "private", "protected",
            "static", "final", "abstract",
            "native", "synchronized", "transient", "volatile"
        ).forEach { modifier ->
            if (modifierList.hasModifierProperty(modifier)) {
                modifiers.add(modifier)
            }
        }

        return modifiers
    }

    private fun getDocumentation(element: PsiNamedElement): String? {
        return try {
            val providers = DocumentationProvider.EP_NAME.extensionList
            for (provider in providers) {
                val doc = provider.generateDoc(element, element)
                if (doc != null) {
                    return doc.replace(Regex("<[^>]*>"), "").trim().take(1000)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun getMemberSummary(psiClass: PsiClass): MemberSummary {
        return MemberSummary(
            fields = psiClass.fields.size,
            methods = psiClass.methods.size,
            innerClasses = psiClass.innerClasses.size,
            constructors = psiClass.constructors.size
        )
    }

    @Serializable
    data class SymbolInfoResult(
        val name: String,
        val qualifiedName: String?,
        val kind: String,
        val type: String?,
        val modifiers: List<String>,
        val documentation: String?,
        val file: String?,
        val line: Int?,
        val containingClass: String?,
        val signature: String? = null,
        val members: MemberSummary? = null
    )

    @Serializable
    data class MemberSummary(
        val fields: Int,
        val methods: Int,
        val innerClasses: Int,
        val constructors: Int
    )

    @Serializable
    data class SymbolInfoError(
        val error: String,
        val message: String
    )
}
