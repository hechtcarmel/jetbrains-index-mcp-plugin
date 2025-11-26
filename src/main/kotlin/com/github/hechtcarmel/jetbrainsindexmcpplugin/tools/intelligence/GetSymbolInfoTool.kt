package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolInfoResult
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class GetSymbolInfoTool : AbstractMcpTool() {

    override val name = "get_symbol_info"

    override val description = """
        Get information about a symbol including its name, kind, type, documentation, and modifiers.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("file") {
                put("type", "string")
                put("description", "Path to the file relative to project root")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line number")
            }
            putJsonObject("column") {
                put("type", "integer")
                put("description", "1-based column number")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file"))
            add(JsonPrimitive("line"))
            add(JsonPrimitive("column"))
        }
    }

    override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: line")
        val column = arguments["column"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: column")

        requireSmartMode(project)

        return readAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@readAction createErrorResult("No element found at position $file:$line:$column")

            // Try to resolve to the definition
            val targetElement = resolveToDefinition(element)
                ?: return@readAction createErrorResult("Could not resolve symbol")

            val name = if (targetElement is PsiNamedElement) {
                targetElement.name ?: "unknown"
            } else {
                targetElement.text.take(50)
            }

            val kind = getElementKind(targetElement)
            val type = getElementType(targetElement)
            val modifiers = getElementModifiers(targetElement)
            val documentation = getDocumentation(targetElement)
            val containingClass = getContainingClassName(targetElement)

            val targetFile = targetElement.containingFile?.virtualFile
            val targetLine = targetFile?.let { vf ->
                PsiDocumentManager.getInstance(project)
                    .getDocument(targetElement.containingFile)
                    ?.let { doc -> doc.getLineNumber(targetElement.textOffset) + 1 }
            }

            createJsonResult(SymbolInfoResult(
                name = name,
                kind = kind,
                type = type,
                documentation = documentation,
                modifiers = modifiers,
                file = targetFile?.let { getRelativePath(project, it) },
                line = targetLine,
                containingClass = containingClass
            ))
        }
    }

    private fun resolveToDefinition(element: PsiElement): PsiElement? {
        // Try to resolve reference
        val reference = element.reference ?: findReferenceInParent(element)
        if (reference != null) {
            val resolved = reference.resolve()
            if (resolved != null) return resolved
        }

        // Otherwise, find the nearest named element
        return findNamedElement(element)
    }

    private fun findReferenceInParent(element: PsiElement): PsiReference? {
        var current: PsiElement? = element
        repeat(3) {
            current = current?.parent ?: return null
            current?.reference?.let { return it }
        }
        return null
    }

    private fun findNamedElement(element: PsiElement): PsiNamedElement? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is PsiNamedElement && current.name != null) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun getElementKind(element: PsiElement): String {
        val className = element.javaClass.simpleName
        return when {
            className.contains("Class") -> "CLASS"
            className.contains("Interface") -> "INTERFACE"
            className.contains("Method") || className.contains("Function") -> "METHOD"
            className.contains("Field") -> "FIELD"
            className.contains("Variable") || className.contains("Parameter") -> "VARIABLE"
            className.contains("Property") -> "PROPERTY"
            className.contains("Enum") -> "ENUM"
            className.contains("Package") -> "PACKAGE"
            else -> "UNKNOWN"
        }
    }

    private fun getElementType(element: PsiElement): String? {
        // Try to get type information using reflection (works for Java/Kotlin PSI elements)
        return try {
            val typeMethod = element.javaClass.methods.find {
                it.name == "getType" || it.name == "getReturnType"
            }
            typeMethod?.invoke(element)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun getElementModifiers(element: PsiElement): List<String> {
        val modifiers = mutableListOf<String>()

        // Try common modifier checks
        try {
            val hasModifier = element.javaClass.methods.find { it.name == "hasModifierProperty" }
            if (hasModifier != null) {
                listOf("public", "private", "protected", "static", "final", "abstract", "open").forEach { modifier ->
                    try {
                        if (hasModifier.invoke(element, modifier) == true) {
                            modifiers.add(modifier)
                        }
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }

        return modifiers
    }

    private fun getDocumentation(element: PsiElement): String? {
        return try {
            val providers = DocumentationProvider.EP_NAME.extensionList
            for (provider in providers) {
                val doc = provider.generateDoc(element, element)
                if (doc != null) {
                    // Strip HTML tags for cleaner output
                    return doc.replace(Regex("<[^>]*>"), "").trim().take(500)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun getContainingClassName(element: PsiElement): String? {
        var current: PsiElement? = element.parent
        while (current != null) {
            if (current.javaClass.simpleName.contains("Class") && current is PsiNamedElement) {
                return current.name
            }
            current = current.parent
        }
        return null
    }
}
