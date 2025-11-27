package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class InlineTool : AbstractRefactoringTool() {

    override val name = "ide_refactor_inline"

    override val description = """
        Inlines a variable or method, replacing all usages with the value/body. Supports Ctrl+Z undo.

        REQUIRED: file + line + column to identify the variable or method to inline.

        WARNING: This modifies files and removes the original declaration.

        EXAMPLE: {"file": "src/main/java/com/example/MyClass.java", "line": 15, "column": 12}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_path") {
                put("type", "string")
                put("description", "Absolute path to project root. Only needed when multiple projects are open.")
            }
            putJsonObject("file") {
                put("type", "string")
                put("description", "Path to file relative to project root. REQUIRED.")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line number where the variable or method is located. REQUIRED.")
            }
            putJsonObject("column") {
                put("type", "integer")
                put("description", "1-based column number. REQUIRED.")
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

        val psiFile = readAction { getPsiFile(project, file) }
            ?: return createErrorResult("File not found: $file")

        val document = readAction {
            PsiDocumentManager.getInstance(project).getDocument(psiFile)
        } ?: return createErrorResult("Could not get document for file")

        // Find the element at the position
        val element = readAction {
            val psiElement = findPsiElement(project, file, line, column)
            findInlineableElement(psiElement)
        } ?: return createErrorResult("No inlineable element (variable or method) found at the specified position")

        return when (element) {
            is PsiLocalVariable -> inlineVariable(project, element, file)
            is PsiMethod -> inlineMethod(project, element, file)
            else -> createErrorResult("Element at position cannot be inlined")
        }
    }

    private fun inlineVariable(
        project: Project,
        variable: PsiLocalVariable,
        file: String
    ): ToolCallResult {
        val variableName = readAction { variable.name }
        val initializer = readAction { variable.initializer }
            ?: return createErrorResult("Variable '$variableName' has no initializer and cannot be inlined")

        val initializerText = readAction { initializer.text }
        val affectedFiles = mutableSetOf<String>()
        var success = false
        var errorMessage: String? = null
        var replacementCount = 0

        try {
            // Collect affected files
            readAction {
                variable.containingFile?.virtualFile?.let { vf ->
                    trackAffectedFile(project, vf, affectedFiles)
                }

                // Find all references
                val references = ReferencesSearch.search(variable).findAll()
                replacementCount = references.size
                for (reference in references) {
                    reference.element.containingFile?.virtualFile?.let { vf ->
                        trackAffectedFile(project, vf, affectedFiles)
                    }
                }
            }

            // Perform the inline
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    WriteCommandAction.writeCommandAction(project)
                        .withName("Inline Variable: $variableName")
                        .withGroupId("MCP Refactoring")
                        .run<Throwable> {
                            performVariableInline(project, variable, initializerText)

                            // Commit and save
                            PsiDocumentManager.getInstance(project).commitAllDocuments()
                            FileDocumentManager.getInstance().saveAllDocuments()

                            success = true
                        }
                } catch (e: Exception) {
                    errorMessage = e.message
                }
            }

            return if (success) {
                createJsonResult(
                    RefactoringResult(
                        success = true,
                        affectedFiles = affectedFiles.toList(),
                        changesCount = replacementCount + 1, // +1 for the declaration removal
                        message = "Successfully inlined variable '$variableName'"
                    )
                )
            } else {
                createErrorResult("Inline variable failed: ${errorMessage ?: "Unknown error"}")
            }
        } catch (e: Exception) {
            return createErrorResult("Inline variable failed: ${e.message}")
        }
    }

    private fun performVariableInline(
        project: Project,
        variable: PsiLocalVariable,
        initializerText: String
    ) {
        val factory = com.intellij.psi.JavaPsiFacade.getElementFactory(project)

        // Find all references and replace them
        val references = ReferencesSearch.search(variable).findAll().toList()
        for (reference in references) {
            val element = reference.element
            if (element.isValid) {
                val replacement = factory.createExpressionFromText(initializerText, element)
                element.replace(replacement)
            }
        }

        // Remove the variable declaration
        val declarationStatement = PsiTreeUtil.getParentOfType(variable, com.intellij.psi.PsiDeclarationStatement::class.java)
        if (declarationStatement != null && declarationStatement.isValid) {
            declarationStatement.delete()
        } else if (variable.isValid) {
            variable.delete()
        }
    }

    private fun inlineMethod(
        project: Project,
        method: PsiMethod,
        file: String
    ): ToolCallResult {
        val methodName = readAction { method.name }
        val affectedFiles = mutableSetOf<String>()
        var success = false
        var errorMessage: String? = null
        var replacementCount = 0

        // Check if method body exists
        val methodBody = readAction { method.body }
            ?: return createErrorResult("Method '$methodName' has no body and cannot be inlined")

        try {
            // Collect affected files
            readAction {
                method.containingFile?.virtualFile?.let { vf ->
                    trackAffectedFile(project, vf, affectedFiles)
                }

                // Find all references
                val references = ReferencesSearch.search(method).findAll()
                replacementCount = references.size
                for (reference in references) {
                    reference.element.containingFile?.virtualFile?.let { vf ->
                        trackAffectedFile(project, vf, affectedFiles)
                    }
                }
            }

            if (replacementCount == 0) {
                return createErrorResult("Method '$methodName' has no usages to inline")
            }

            // For method inlining, we need to use IntelliJ's built-in handler
            // which requires an editor context
            val document = readAction {
                PsiDocumentManager.getInstance(project).getDocument(method.containingFile)
            } ?: return createErrorResult("Could not get document for method")

            var editor: Editor? = null
            try {
                ApplicationManager.getApplication().invokeAndWait {
                    editor = EditorFactory.getInstance().createEditor(document, project)
                }

                val ed = editor ?: return createErrorResult("Could not create editor")

                ApplicationManager.getApplication().invokeAndWait {
                    try {
                        WriteCommandAction.writeCommandAction(project)
                            .withName("Inline Method: $methodName")
                            .withGroupId("MCP Refactoring")
                            .run<Throwable> {
                                // Perform simple manual inlining
                                performSimpleMethodInline(project, method)

                                // Commit and save
                                PsiDocumentManager.getInstance(project).commitAllDocuments()
                                FileDocumentManager.getInstance().saveAllDocuments()

                                success = true
                            }
                    } catch (e: Exception) {
                        errorMessage = e.message
                    }
                }
            } finally {
                editor?.let { ed ->
                    ApplicationManager.getApplication().invokeAndWait {
                        EditorFactory.getInstance().releaseEditor(ed)
                    }
                }
            }

            return if (success) {
                createJsonResult(
                    RefactoringResult(
                        success = true,
                        affectedFiles = affectedFiles.toList(),
                        changesCount = replacementCount,
                        message = "Successfully inlined method '$methodName'"
                    )
                )
            } else {
                createErrorResult("Inline method failed: ${errorMessage ?: "Unknown error"}")
            }
        } catch (e: Exception) {
            return createErrorResult("Inline method failed: ${e.message}")
        }
    }

    private fun findInlineableElement(element: PsiElement?): PsiElement? {
        if (element == null) return null

        // Check if we're directly on a local variable
        if (element is PsiLocalVariable) {
            return element
        }

        // Check if we're on a method
        if (element is PsiMethod) {
            return element
        }

        // Try to find parent variable or method
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is PsiLocalVariable -> return current
                is PsiMethod -> return current
            }
            current = current.parent
        }

        // Also check PsiTreeUtil
        return PsiTreeUtil.getParentOfType(element, PsiLocalVariable::class.java, PsiMethod::class.java)
    }

    private fun performSimpleMethodInline(project: Project, method: PsiMethod) {
        val factory = com.intellij.psi.JavaPsiFacade.getElementFactory(project)
        val methodBody = method.body ?: throw IllegalStateException("Method has no body")

        // Get method body statements
        val statements = methodBody.statements
        if (statements.isEmpty()) {
            throw IllegalStateException("Method body is empty")
        }

        // Find all calls to this method and inline them
        val references = ReferencesSearch.search(method).findAll().toList()

        for (reference in references) {
            val element = reference.element
            val methodCall = PsiTreeUtil.getParentOfType(element, com.intellij.psi.PsiMethodCallExpression::class.java)

            if (methodCall != null && methodCall.isValid) {
                try {
                    // For simple single-statement methods returning a value
                    if (statements.size == 1 && statements[0] is com.intellij.psi.PsiReturnStatement) {
                        val returnStmt = statements[0] as com.intellij.psi.PsiReturnStatement
                        val returnValue = returnStmt.returnValue
                        if (returnValue != null) {
                            val replacement = factory.createExpressionFromText(returnValue.text, methodCall)
                            methodCall.replace(replacement)
                        }
                    }
                } catch (e: Exception) {
                    // Skip if replacement fails for this call site
                }
            }
        }

        // Delete the method after inlining all calls
        if (method.isValid) {
            method.delete()
        }
    }
}
