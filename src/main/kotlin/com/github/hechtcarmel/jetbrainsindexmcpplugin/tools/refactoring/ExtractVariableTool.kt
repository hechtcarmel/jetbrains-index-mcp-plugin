package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class ExtractVariableTool : AbstractRefactoringTool() {

    override val name = "ide_refactor_extract_variable"

    override val description = """
        Extracts an expression into a new local variable. Supports Ctrl+Z undo.

        REQUIRED: file + line + column to identify the expression, plus variableName.
        OPTIONAL: replaceAll=true to replace all identical occurrences.

        WARNING: This modifies files. Returns variable declaration location.

        EXAMPLE: {"file": "src/main/java/MyClass.java", "line": 25, "column": 20, "variableName": "userCount"}
        EXAMPLE with replaceAll: {"file": "src/main/java/MyClass.java", "line": 25, "column": 20, "variableName": "userCount", "replaceAll": true}
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
                put("description", "1-based line number where the expression is located. REQUIRED.")
            }
            putJsonObject("column") {
                put("type", "integer")
                put("description", "1-based column number. REQUIRED.")
            }
            putJsonObject("variableName") {
                put("type", "string")
                put("description", "Name for the new variable. REQUIRED.")
            }
            putJsonObject("replaceAll") {
                put("type", "boolean")
                put("description", "Replace all identical occurrences. Optional, default: false.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file"))
            add(JsonPrimitive("line"))
            add(JsonPrimitive("column"))
            add(JsonPrimitive("variableName"))
        }
    }

    override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: line")
        val column = arguments["column"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: column")
        val variableName = arguments["variableName"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: variableName")
        val replaceAll = arguments["replaceAll"]?.jsonPrimitive?.content?.toBoolean() ?: false

        if (variableName.isBlank()) {
            return createErrorResult("variableName cannot be blank")
        }

        requireSmartMode(project)

        val psiFile = readAction { getPsiFile(project, file) }
            ?: return createErrorResult("File not found: $file")

        val document = readAction {
            PsiDocumentManager.getInstance(project).getDocument(psiFile)
        } ?: return createErrorResult("Could not get document for file")

        // Find the expression at the position
        val offset = readAction { getOffset(document, line, column) }
            ?: return createErrorResult("Invalid position: line $line, column $column")

        val expression = readAction {
            val element = psiFile.findElementAt(offset)
            findExpression(element)
        } ?: return createErrorResult("No extractable expression found at the specified position")

        val expressionText = readAction { expression.text }

        // Create an editor for the refactoring
        var editor: Editor? = null
        var success = false
        var errorMessage: String? = null
        var variableLine: Int? = null
        val affectedFiles = mutableSetOf<String>()

        try {
            ApplicationManager.getApplication().invokeAndWait {
                editor = EditorFactory.getInstance().createEditor(document, project)
            }

            val ed = editor ?: return createErrorResult("Could not create editor")

            // Set selection to the expression
            val exprRange = readAction { expression.textRange }
            ApplicationManager.getApplication().invokeAndWait {
                ed.selectionModel.setSelection(exprRange.startOffset, exprRange.endOffset)
                ed.caretModel.moveToOffset(exprRange.startOffset)
            }

            // Perform extraction
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    WriteCommandAction.writeCommandAction(project)
                        .withName("Extract Variable: $variableName")
                        .withGroupId("MCP Refactoring")
                        .run<Throwable> {
                            // Directly manipulate PSI for variable extraction
                            performSimpleExtraction(project, psiFile, expression, variableName, replaceAll)

                            // Track affected file
                            psiFile.virtualFile?.let { vf ->
                                trackAffectedFile(project, vf, affectedFiles)
                            }

                            // Commit and save
                            PsiDocumentManager.getInstance(project).commitAllDocuments()
                            FileDocumentManager.getInstance().saveAllDocuments()

                            // Variable declaration will be at or before the original expression line
                            variableLine = line

                            success = true
                        }
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Extract variable failed"
                }
            }

            return if (success) {
                createJsonResult(
                    ExtractVariableResult(
                        success = true,
                        affectedFiles = affectedFiles.toList(),
                        changesCount = 1,
                        message = "Successfully extracted expression to variable '$variableName'",
                        variableDeclaration = variableLine?.let {
                            VariableLocation(file = file, line = it, name = variableName)
                        }
                    )
                )
            } else {
                createErrorResult("Extract variable failed: ${errorMessage ?: "Unknown error"}")
            }

        } catch (e: Exception) {
            return createErrorResult("Extract variable failed: ${e.message}")
        } finally {
            editor?.let { ed ->
                ApplicationManager.getApplication().invokeAndWait {
                    EditorFactory.getInstance().releaseEditor(ed)
                }
            }
        }
    }

    private fun findExpression(element: PsiElement?): PsiExpression? {
        if (element == null) return null

        // First check if the element itself is an expression
        if (element is PsiExpression) {
            return element
        }

        // Try to find an expression parent
        var current: PsiElement? = element
        while (current != null) {
            if (current is PsiExpression) {
                return current
            }
            current = current.parent
        }

        // Try looking for expression in ancestors
        return PsiTreeUtil.getParentOfType(element, PsiExpression::class.java)
    }

    private fun performSimpleExtraction(
        project: Project,
        psiFile: com.intellij.psi.PsiFile,
        expression: PsiExpression,
        variableName: String,
        replaceAll: Boolean
    ) {
        // Get the expression type
        val expressionType = expression.type

        // Find the containing statement
        val containingStatement = PsiTreeUtil.getParentOfType(
            expression,
            com.intellij.psi.PsiStatement::class.java,
            com.intellij.psi.PsiLocalVariable::class.java
        )

        if (containingStatement == null) {
            throw IllegalStateException("Cannot find containing statement for expression")
        }

        // Create the variable declaration
        val factory = com.intellij.psi.JavaPsiFacade.getElementFactory(project)
        val typeText = expressionType?.canonicalText ?: "var"
        val expressionText = expression.text

        // Create declaration statement
        val declarationText = "$typeText $variableName = $expressionText;"
        val declaration = factory.createStatementFromText(declarationText, containingStatement)

        // Insert declaration before the containing statement
        val parent = containingStatement.parent
        parent.addBefore(declaration, containingStatement)

        // Replace the expression with a reference to the variable
        val variableRef = factory.createExpressionFromText(variableName, expression)
        expression.replace(variableRef)

        // Optionally replace all other occurrences
        if (replaceAll) {
            val scope = PsiTreeUtil.getParentOfType(
                containingStatement,
                com.intellij.psi.PsiMethod::class.java,
                com.intellij.psi.PsiClassInitializer::class.java
            )
            if (scope != null) {
                replaceAllOccurrences(scope, expressionText, variableName, factory)
            }
        }
    }

    private fun replaceAllOccurrences(
        scope: PsiElement,
        expressionText: String,
        variableName: String,
        factory: com.intellij.psi.PsiElementFactory
    ) {
        val expressions = PsiTreeUtil.findChildrenOfType(scope, PsiExpression::class.java)
        for (expr in expressions) {
            if (expr.text == expressionText && expr.isValid) {
                try {
                    val ref = factory.createExpressionFromText(variableName, expr)
                    expr.replace(ref)
                } catch (e: Exception) {
                    // Skip if replacement fails
                }
            }
        }
    }
}

@Serializable
data class ExtractVariableResult(
    val success: Boolean,
    val affectedFiles: List<String>,
    val changesCount: Int,
    val message: String,
    val variableDeclaration: VariableLocation? = null
)

@Serializable
data class VariableLocation(
    val file: String,
    val line: Int,
    val name: String
)
