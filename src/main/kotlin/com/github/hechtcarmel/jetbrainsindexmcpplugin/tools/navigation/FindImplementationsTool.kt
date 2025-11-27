package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationLocation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationResult
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class FindImplementationsTool : AbstractMcpTool() {

    override val name = "ide_find_implementations"

    override val description = """
        Finds all implementations of an interface, abstract class, or abstract/interface method.

        REQUIRED: file + line + column to identify the interface/abstract class/method.

        RETURNS: All implementing classes or overriding methods with file locations and line numbers.

        EXAMPLE: {"file": "src/main/java/com/example/Repository.java", "line": 8, "column": 18}
        This finds all classes implementing the interface at line 8, or all methods overriding the method.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_path") {
                put("type", "string")
                put("description", "Absolute path to the project root. Required when multiple projects are open.")
            }
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

            // Try to find a method first
            val method = findContainingMethod(element)
            if (method != null) {
                val implementations = findMethodImplementations(project, method)
                return@readAction createJsonResult(ImplementationResult(
                    implementations = implementations,
                    totalCount = implementations.size
                ))
            }

            // Otherwise try to find a class/interface
            val psiClass = findContainingClass(element)
            if (psiClass != null) {
                val implementations = findClassImplementations(project, psiClass)
                return@readAction createJsonResult(ImplementationResult(
                    implementations = implementations,
                    totalCount = implementations.size
                ))
            }

            createErrorResult("No method or class found at position")
        }
    }

    private fun findContainingMethod(element: PsiElement): PsiMethod? {
        if (element is PsiMethod) return element

        var current: PsiElement? = element
        while (current != null) {
            if (current is PsiMethod) {
                return current
            }
            if (current.parent is PsiMethod) {
                return current.parent as PsiMethod
            }
            current = current.parent
        }
        return null
    }

    private fun findContainingClass(element: PsiElement): PsiClass? {
        if (element is PsiClass) return element

        var current: PsiElement? = element
        while (current != null) {
            if (current is PsiClass) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun findMethodImplementations(project: Project, method: PsiMethod): List<ImplementationLocation> {
        return try {
            OverridingMethodsSearch.search(method)
                .findAll()
                .take(100)
                .mapNotNull { overridingMethod ->
                    createImplementationLocation(project, overridingMethod, "METHOD")
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun findClassImplementations(project: Project, psiClass: PsiClass): List<ImplementationLocation> {
        return try {
            // true = search all inheritors (not just direct)
            ClassInheritorsSearch.search(psiClass, true)
                .findAll()
                .take(100)
                .mapNotNull { inheritor ->
                    val document = inheritor.containingFile?.let {
                        PsiDocumentManager.getInstance(project).getDocument(it)
                    }

                    val lineNumber = document?.let { doc ->
                        doc.getLineNumber(inheritor.textOffset) + 1
                    } ?: 0

                    val virtualFile = inheritor.containingFile?.virtualFile

                    val kind = when {
                        inheritor.isInterface -> "INTERFACE"
                        inheritor.isEnum -> "ENUM"
                        inheritor.hasModifierProperty("abstract") -> "ABSTRACT_CLASS"
                        else -> "CLASS"
                    }

                    ImplementationLocation(
                        name = inheritor.qualifiedName ?: inheritor.name ?: "unknown",
                        file = virtualFile?.let { getRelativePath(project, it) } ?: "unknown",
                        line = lineNumber,
                        kind = kind
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun createImplementationLocation(project: Project, method: PsiMethod, kind: String): ImplementationLocation? {
        val containingFile = method.containingFile?.virtualFile ?: return null
        val document = method.containingFile?.let {
            PsiDocumentManager.getInstance(project).getDocument(it)
        }

        val lineNumber = document?.let { doc ->
            doc.getLineNumber(method.textOffset) + 1
        } ?: 0

        val methodName = buildString {
            method.containingClass?.name?.let { className ->
                append(className)
                append(".")
            }
            append(method.name)
        }

        return ImplementationLocation(
            name = methodName,
            file = getRelativePath(project, containingFile),
            line = lineNumber,
            kind = kind
        )
    }
}
