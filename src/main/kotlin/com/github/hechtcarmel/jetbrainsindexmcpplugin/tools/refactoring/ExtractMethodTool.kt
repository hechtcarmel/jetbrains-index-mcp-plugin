package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.extractMethod.ExtractMethodHandler
import com.intellij.refactoring.extractMethod.ExtractMethodProcessor
import com.intellij.refactoring.extractMethod.PrepareFailedException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class ExtractMethodTool : AbstractRefactoringTool() {

    override val name = "ide_refactor_extract_method"

    override val description = """
        Extracts a code block into a new method. Supports Ctrl+Z undo.

        REQUIRED: file + startLine + endLine to select the code block, plus methodName.

        WARNING: This modifies files. Returns new method location and affected files.

        EXAMPLE: {"file": "src/main/java/com/example/Service.java", "startLine": 45, "endLine": 60, "methodName": "processUserData"}
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
            putJsonObject("startLine") {
                put("type", "integer")
                put("description", "1-based line number where the code block starts. REQUIRED.")
            }
            putJsonObject("endLine") {
                put("type", "integer")
                put("description", "1-based line number where the code block ends (inclusive). REQUIRED.")
            }
            putJsonObject("methodName") {
                put("type", "string")
                put("description", "Name for the new method. REQUIRED.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file"))
            add(JsonPrimitive("startLine"))
            add(JsonPrimitive("endLine"))
            add(JsonPrimitive("methodName"))
        }
    }

    override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val startLine = arguments["startLine"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: startLine")
        val endLine = arguments["endLine"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: endLine")
        val methodName = arguments["methodName"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: methodName")

        if (methodName.isBlank()) {
            return createErrorResult("methodName cannot be blank")
        }

        if (startLine > endLine) {
            return createErrorResult("startLine cannot be greater than endLine")
        }

        if (startLine < 1) {
            return createErrorResult("startLine must be at least 1")
        }

        requireSmartMode(project)

        val psiFile = readAction { getPsiFile(project, file) }
            ?: return createErrorResult("File not found: $file")

        val document = readAction {
            PsiDocumentManager.getInstance(project).getDocument(psiFile)
        } ?: return createErrorResult("Could not get document for file")

        // Validate line numbers
        val lineCount = readAction { document.lineCount }
        if (endLine > lineCount) {
            return createErrorResult("endLine ($endLine) exceeds file line count ($lineCount)")
        }

        // Calculate selection range
        val startOffset = readAction { document.getLineStartOffset(startLine - 1) }
        val endOffset = readAction { document.getLineEndOffset(endLine - 1) }

        // Create an editor for the refactoring
        var editor: Editor? = null
        var success = false
        var errorMessage: String? = null
        var newMethodLine: Int? = null
        val affectedFiles = mutableSetOf<String>()

        try {
            ApplicationManager.getApplication().invokeAndWait {
                editor = EditorFactory.getInstance().createEditor(document, project)
            }

            val ed = editor ?: return createErrorResult("Could not create editor")

            // Set selection
            ApplicationManager.getApplication().invokeAndWait {
                ed.selectionModel.setSelection(startOffset, endOffset)
                ed.caretModel.moveToOffset(startOffset)
            }

            // Perform extraction
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    WriteCommandAction.writeCommandAction(project)
                        .withName("Extract Method: $methodName")
                        .withGroupId("MCP Refactoring")
                        .run<Throwable> {
                            // Find elements in range
                            val elements = findElementsInRange(psiFile, startOffset, endOffset)

                            if (elements.isEmpty()) {
                                errorMessage = "No extractable code found in the specified range"
                                return@run
                            }

                            // Create and configure the processor
                            val processor = ExtractMethodProcessor(
                                project,
                                ed,
                                elements.toTypedArray(),
                                null, // forcedReturnType
                                "Extract Method",
                                methodName,
                                "" // helpId
                            )

                            // Prepare the extraction
                            try {
                                if (!processor.prepare()) {
                                    errorMessage = "Cannot extract method from the selected code"
                                    return@run
                                }
                            } catch (e: PrepareFailedException) {
                                errorMessage = e.message ?: "Cannot extract method from the selected code"
                                return@run
                            }

                            // Configure the processor
                            processor.setMethodVisibility(com.intellij.psi.PsiModifier.PRIVATE)

                            // Perform the extraction
                            processor.doRefactoring()

                            // Track affected file
                            psiFile.virtualFile?.let { vf ->
                                trackAffectedFile(project, vf, affectedFiles)
                            }

                            // Get the location of the new method
                            processor.extractedMethod?.let { extractedMethod ->
                                val methodDocument = PsiDocumentManager.getInstance(project).getDocument(extractedMethod.containingFile)
                                newMethodLine = methodDocument?.getLineNumber(extractedMethod.textOffset)?.plus(1)
                            }

                            // Commit and save
                            PsiDocumentManager.getInstance(project).commitAllDocuments()
                            FileDocumentManager.getInstance().saveAllDocuments()

                            success = true
                        }
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Extract method failed"
                }
            }

            return if (success) {
                createJsonResult(
                    ExtractMethodResult(
                        success = true,
                        affectedFiles = affectedFiles.toList(),
                        changesCount = 1,
                        message = "Successfully extracted method '$methodName'",
                        newMethodLocation = newMethodLine?.let {
                            MethodLocation(file = file, line = it)
                        }
                    )
                )
            } else {
                createErrorResult("Extract method failed: ${errorMessage ?: "Unknown error"}")
            }

        } catch (e: Exception) {
            return createErrorResult("Extract method failed: ${e.message}")
        } finally {
            editor?.let { ed ->
                ApplicationManager.getApplication().invokeAndWait {
                    EditorFactory.getInstance().releaseEditor(ed)
                }
            }
        }
    }

    private fun findElementsInRange(psiFile: PsiFile, startOffset: Int, endOffset: Int): List<PsiElement> {
        val elements = mutableListOf<PsiElement>()
        var element = psiFile.findElementAt(startOffset)

        while (element != null && element.textRange.startOffset < endOffset) {
            // Get the statement-level element
            val statement = getStatementElement(element)
            if (statement != null &&
                statement.textRange.startOffset >= startOffset &&
                statement.textRange.endOffset <= endOffset &&
                !elements.contains(statement)
            ) {
                elements.add(statement)
            }
            element = PsiTreeUtil.nextLeaf(element)
        }

        // If no statements found, try to find a single expression
        if (elements.isEmpty()) {
            val startElement = psiFile.findElementAt(startOffset)
            val endElement = psiFile.findElementAt(endOffset - 1)
            if (startElement != null && endElement != null) {
                val commonParent = PsiTreeUtil.findCommonParent(startElement, endElement)
                if (commonParent != null && commonParent != psiFile) {
                    elements.add(commonParent)
                }
            }
        }

        return elements
    }

    private fun getStatementElement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            // Look for statement-level elements
            val className = current.javaClass.simpleName
            if (className.contains("Statement") ||
                className.contains("Expression") && current.parent?.javaClass?.simpleName?.contains("Statement") == true
            ) {
                return current
            }
            current = current.parent
        }
        return null
    }
}

@Serializable
data class ExtractMethodResult(
    val success: Boolean,
    val affectedFiles: List<String>,
    val changesCount: Int,
    val message: String,
    val newMethodLocation: MethodLocation? = null
)

@Serializable
data class MethodLocation(
    val file: String,
    val line: Int
)
