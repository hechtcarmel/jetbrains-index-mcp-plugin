package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class CallHierarchyTool : AbstractMcpTool() {

    override val name = "ide_call_hierarchy"

    override val description = """
        Analyzes method call relationships recursively to build a call hierarchy tree.

        REQUIRED: file + line + column to identify the method, plus direction.

        PARAMETERS:
        - direction: 'callers' = methods that call this method, 'callees' = methods this method calls
        - depth: how many levels deep to traverse (default: 3, max: 5)

        RETURNS: Tree structure with method signatures, file locations, and nested children.

        EXAMPLE: {"file": "src/main/java/com/example/Service.java", "line": 42, "column": 10, "direction": "callers"}
        EXAMPLE with depth: {"file": "src/Service.java", "line": 42, "column": 10, "direction": "callees", "depth": 2}
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
            putJsonObject("direction") {
                put("type", "string")
                put("description", "Direction: 'callers' (methods that call this method) or 'callees' (methods this method calls)")
                putJsonArray("enum") {
                    add(JsonPrimitive("callers"))
                    add(JsonPrimitive("callees"))
                }
            }
            putJsonObject("depth") {
                put("type", "integer")
                put("description", "How many levels deep to traverse the call hierarchy (default: 3, max: 5)")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file"))
            add(JsonPrimitive("line"))
            add(JsonPrimitive("column"))
            add(JsonPrimitive("direction"))
        }
    }

    companion object {
        private const val DEFAULT_DEPTH = 3
        private const val MAX_DEPTH = 5
        private const val MAX_RESULTS_PER_LEVEL = 20
    }

    override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: line")
        val column = arguments["column"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: column")
        val direction = arguments["direction"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: direction")
        val depth = (arguments["depth"]?.jsonPrimitive?.int ?: DEFAULT_DEPTH).coerceIn(1, MAX_DEPTH)

        if (direction !in listOf("callers", "callees")) {
            return createErrorResult("direction must be 'callers' or 'callees'")
        }

        requireSmartMode(project)

        return readAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@readAction createErrorResult("No element found at position $file:$line:$column")

            val method = findContainingMethod(element)
                ?: return@readAction createErrorResult("No method found at position")

            val methodElement = createCallElement(project, method)
            val visited = mutableSetOf<String>()

            val calls = if (direction == "callers") {
                findCallersRecursive(project, method, depth, visited)
            } else {
                findCalleesRecursive(project, method, depth, visited)
            }

            createJsonResult(CallHierarchyResult(
                element = methodElement,
                calls = calls
            ))
        }
    }

    private fun findContainingMethod(element: PsiElement): PsiMethod? {
        // First check if we're directly on a method
        if (element is PsiMethod) return element

        // Try to find a named element that might be a method name
        var current: PsiElement? = element
        while (current != null) {
            if (current is PsiMethod) {
                return current
            }
            // Check if parent is a method (we might be on the method name)
            if (current.parent is PsiMethod) {
                return current.parent as PsiMethod
            }
            current = current.parent
        }
        return null
    }

    private fun findCallersRecursive(
        project: Project,
        method: PsiMethod,
        depth: Int,
        visited: MutableSet<String>
    ): List<CallElement> {
        if (depth <= 0) return emptyList()

        val methodKey = getMethodKey(method)
        if (methodKey in visited) return emptyList()
        visited.add(methodKey)

        return try {
            MethodReferencesSearch.search(method)
                .findAll()
                .take(MAX_RESULTS_PER_LEVEL)
                .mapNotNull { reference ->
                    val refElement = reference.element
                    val containingMethod = PsiTreeUtil.getParentOfType(refElement, PsiMethod::class.java)

                    if (containingMethod != null && containingMethod != method) {
                        val children = if (depth > 1) {
                            findCallersRecursive(project, containingMethod, depth - 1, visited)
                        } else {
                            null
                        }
                        createCallElement(project, containingMethod, children)
                    } else {
                        null
                    }
                }
                .distinctBy { it.name + it.file + it.line }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun findCalleesRecursive(
        project: Project,
        method: PsiMethod,
        depth: Int,
        visited: MutableSet<String>
    ): List<CallElement> {
        if (depth <= 0) return emptyList()

        val methodKey = getMethodKey(method)
        if (methodKey in visited) return emptyList()
        visited.add(methodKey)

        val callees = mutableListOf<CallElement>()

        try {
            method.body?.let { body ->
                PsiTreeUtil.findChildrenOfType(body, com.intellij.psi.PsiMethodCallExpression::class.java)
                    .take(MAX_RESULTS_PER_LEVEL)
                    .forEach { methodCall ->
                        val calledMethod = methodCall.resolveMethod()
                        if (calledMethod != null) {
                            val children = if (depth > 1) {
                                findCalleesRecursive(project, calledMethod, depth - 1, visited)
                            } else {
                                null
                            }
                            val element = createCallElement(project, calledMethod, children)
                            if (callees.none { it.name == element.name && it.file == element.file && it.line == element.line }) {
                                callees.add(element)
                            }
                        } else {
                            // Fallback: can't resolve method, but report the call expression text
                            val callText = methodCall.methodExpression.referenceName ?: methodCall.text.take(50)
                            val unresolvedElement = CallElement(
                                name = "$callText(...) [unresolved]",
                                file = "unknown",
                                line = 0,
                                children = null
                            )
                            if (callees.none { it.name == unresolvedElement.name }) {
                                callees.add(unresolvedElement)
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            // Handle gracefully
        }

        return callees
    }

    private fun getMethodKey(method: PsiMethod): String {
        val className = method.containingClass?.qualifiedName ?: ""
        val methodName = method.name
        val params = method.parameterList.parameters.joinToString(",") { param ->
            try {
                param.type.canonicalText
            } catch (e: Exception) {
                param.name ?: "?"
            }
        }
        return "$className.$methodName($params)"
    }

    private fun createCallElement(
        project: Project,
        method: PsiMethod,
        children: List<CallElement>? = null
    ): CallElement {
        val containingFile = method.containingFile?.virtualFile
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
            append("(")
            append(method.parameterList.parameters.joinToString(", ") { param ->
                try {
                    param.type.presentableText
                } catch (e: Exception) {
                    param.name ?: "?"
                }
            })
            append(")")
        }

        return CallElement(
            name = methodName,
            file = containingFile?.let { getRelativePath(project, it) } ?: "unknown",
            line = lineNumber,
            children = children?.takeIf { it.isNotEmpty() }
        )
    }
}
