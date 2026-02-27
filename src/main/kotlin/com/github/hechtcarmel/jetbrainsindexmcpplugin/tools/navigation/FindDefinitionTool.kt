package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class FindDefinitionTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_MAX_PREVIEW_LINES = 50
        private const val MAX_ALLOWED_PREVIEW_LINES = 500
    }

    override val name = ToolNames.FIND_DEFINITION

    override val description = """
        Navigate to where a symbol is defined (Go to Definition). Use when you see a symbol reference and need to find its declaration—works for classes, methods, variables, imports.

        Returns: file path, line/column of definition, code preview, and symbol name.

        Parameters: file + line + column (required).

        Example: {"file": "src/Main.java", "line": 15, "column": 10}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
        putJsonObject(SchemaConstants.PROPERTIES) {
            putJsonObject(ParamNames.PROJECT_PATH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Absolute path to project root. Only needed when multiple projects are open in IDE.")
            }
            putJsonObject(ParamNames.FILE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). REQUIRED.")
            }
            putJsonObject(ParamNames.LINE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based line number where the symbol reference is located. REQUIRED.")
            }
            putJsonObject(ParamNames.COLUMN) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based column number within the line. REQUIRED.")
            }
            putJsonObject(ParamNames.FULL_ELEMENT_PREVIEW) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_BOOLEAN)
                put(SchemaConstants.DESCRIPTION, "If true, returns the complete element code instead of a preview snippet. Optional, defaults to false.")
            }
            putJsonObject(ParamNames.MAX_PREVIEW_LINES) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "Maximum lines for fullElementPreview. Truncates large classes/functions. Default: 50, Max: 500. Only used when fullElementPreview=true.")
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive(ParamNames.FILE))
            add(JsonPrimitive(ParamNames.LINE))
            add(JsonPrimitive(ParamNames.COLUMN))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments[ParamNames.FILE]?.jsonPrimitive?.content
            ?: return createErrorResult(ErrorMessages.missingRequiredParam(ParamNames.FILE))
        val line = arguments[ParamNames.LINE]?.jsonPrimitive?.int
            ?: return createErrorResult(ErrorMessages.missingRequiredParam(ParamNames.LINE))
        val column = arguments[ParamNames.COLUMN]?.jsonPrimitive?.int
            ?: return createErrorResult(ErrorMessages.missingRequiredParam(ParamNames.COLUMN))
        val fullElementPreview = arguments[ParamNames.FULL_ELEMENT_PREVIEW]?.jsonPrimitive?.content?.toBoolean() ?: false
        val maxPreviewLines = (arguments[ParamNames.MAX_PREVIEW_LINES]?.jsonPrimitive?.int ?: DEFAULT_MAX_PREVIEW_LINES)
            .coerceIn(1, MAX_ALLOWED_PREVIEW_LINES)

        requireSmartMode(project)

        return suspendingReadAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@suspendingReadAction createErrorResult(ErrorMessages.noElementAtPosition(file, line, column))

            // Use semantic reference resolution to find what this position refers to.
            // This correctly handles method calls (resolves to the called method)
            // vs declarations (returns the declaration itself).
            val resolvedElement = PsiUtils.resolveTargetElement(element)
                ?: return@suspendingReadAction createErrorResult(ErrorMessages.SYMBOL_NOT_RESOLVED)

            // Prefer source files (.java) over compiled files (.class) for library classes
            val targetElement = PsiUtils.getNavigationElement(resolvedElement)

            // Try the target element first, then its navigationElement (for Kotlin light classes
            // and import directives where the resolved element may be a compiled class without a virtual file)
            val effectiveTarget = if (targetElement.containingFile?.virtualFile != null) {
                targetElement
            } else {
                val navElement = targetElement.navigationElement
                if (navElement != targetElement && navElement.containingFile?.virtualFile != null) {
                    navElement
                } else {
                    targetElement
                }
            }

            // Handle package/directory references (e.g., cursor on package segment in import statement)
            if (effectiveTarget is PsiDirectory) {
                val dirPath = getRelativePath(project, effectiveTarget.virtualFile)
                return@suspendingReadAction createJsonResult(DefinitionResult(
                    file = dirPath,
                    line = 1,
                    column = 1,
                    preview = "Package directory: $dirPath",
                    symbolName = effectiveTarget.name
                ))
            }
            // PsiPackage is Java-plugin-only; use reflection to avoid NoClassDefFoundError in non-Java IDEs
            try {
                val psiPackageClass = Class.forName("com.intellij.psi.PsiPackage")
                if (psiPackageClass.isInstance(effectiveTarget)) {
                    val qualifiedName = psiPackageClass.getMethod("getQualifiedName")
                        .invoke(effectiveTarget) as? String ?: "unknown"
                    val dirs = psiPackageClass
                        .getMethod("getDirectories", GlobalSearchScope::class.java)
                        .invoke(effectiveTarget, GlobalSearchScope.projectScope(project)) as Array<*>
                    val dir = dirs.firstOrNull() as? PsiDirectory
                    if (dir != null) {
                        val dirPath = getRelativePath(project, dir.virtualFile)
                        return@suspendingReadAction createJsonResult(DefinitionResult(
                            file = dirPath,
                            line = 1,
                            column = 1,
                            preview = "Package: $qualifiedName",
                            symbolName = qualifiedName
                        ))
                    }
                }
            } catch (_: ClassNotFoundException) {
                // Java plugin not available — skip PsiPackage handling
            }

            val targetFile = effectiveTarget.containingFile?.virtualFile
                ?: return@suspendingReadAction createErrorResult(ErrorMessages.DEFINITION_FILE_NOT_FOUND)

            val document = PsiDocumentManager.getInstance(project)
                .getDocument(effectiveTarget.containingFile)
                ?: return@suspendingReadAction createErrorResult(ErrorMessages.DEFINITION_DOCUMENT_NOT_FOUND)

            val targetLine = document.getLineNumber(effectiveTarget.textOffset) + 1
            val targetColumn = effectiveTarget.textOffset -
                document.getLineStartOffset(targetLine - 1) + 1

            // Get preview - either full element code or a few lines around the definition
            val preview = if (fullElementPreview) {
                // Extract the complete element code, truncated to maxPreviewLines
                val fullText = effectiveTarget.text
                val lines = fullText.lines()
                if (lines.size > maxPreviewLines) {
                    lines.take(maxPreviewLines).joinToString("\n") +
                        "\n// ... truncated (${lines.size} total lines, showing $maxPreviewLines)"
                } else {
                    fullText
                }
            } else {
                // Original behavior: a few lines around the definition
                val previewStartLine = maxOf(0, targetLine - 2)
                val previewEndLine = minOf(document.lineCount - 1, targetLine + 2)

                (previewStartLine until previewEndLine).joinToString("\n") { lineIndex ->
                    val startOffset = document.getLineStartOffset(lineIndex)
                    val endOffset = document.getLineEndOffset(lineIndex)
                    "${lineIndex + 1}: ${document.getText(TextRange(startOffset, endOffset))}"
                }
            }

            val symbolName = if (effectiveTarget is PsiNamedElement) {
                effectiveTarget.name ?: "unknown"
            } else {
                effectiveTarget.text.take(50)
            }

            createJsonResult(DefinitionResult(
                file = getRelativePath(project, targetFile),
                line = targetLine,
                column = targetColumn,
                preview = preview,
                symbolName = symbolName
            ))
        }
    }
}
