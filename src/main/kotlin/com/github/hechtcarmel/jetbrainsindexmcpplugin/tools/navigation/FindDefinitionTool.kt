package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class FindDefinitionTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_MAX_PREVIEW_LINES = 50
        private const val MAX_ALLOWED_PREVIEW_LINES = 500
    }

    override val name = ToolNames.FIND_DEFINITION

    override val description = """
        Navigate to where a symbol is defined (Go to Definition). Use when you see a symbol reference and need to find its declaration—works for classes, methods, variables, imports.

        Returns: file path, line/column of definition, code preview, and symbol name.

        Target (mutually exclusive):
        - file + line + column: position-based lookup
        - language + symbol: fully qualified symbol reference (currently supported for Java only)

        Example: {"file": "src/Main.java", "line": 15, "column": 10}
        Example: {"language": "Java", "symbol": "com.example.MyClass#processData(String)"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(required = false, description = "Project-relative file path, or a dependency/library absolute path or jar:// URL previously returned by the plugin. Required for position-based lookup.")
        .lineAndColumn(required = false)
        .languageAndSymbol(required = false)
        .booleanProperty(ParamNames.FULL_ELEMENT_PREVIEW, "If true, returns the complete element code instead of a preview snippet. Optional, defaults to false.")
        .intProperty(ParamNames.MAX_PREVIEW_LINES, "Maximum lines for fullElementPreview. Truncates large classes/functions. Default: 50, Max: 500. Only used when fullElementPreview=true.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val fullElementPreview = arguments[ParamNames.FULL_ELEMENT_PREVIEW]?.jsonPrimitive?.content?.toBoolean() ?: false
        val maxPreviewLines = (arguments[ParamNames.MAX_PREVIEW_LINES]?.jsonPrimitive?.int ?: DEFAULT_MAX_PREVIEW_LINES)
            .coerceIn(1, MAX_ALLOWED_PREVIEW_LINES)

        requireSmartMode(project)

        return suspendingReadAction {
            val element = resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true).getOrElse {
                return@suspendingReadAction createErrorResult(it.message ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL)
            }

            // Symbol-based resolution returns the declaration directly (PsiNamedElement).
            // Position-based resolution returns a leaf token that needs reference resolution.
            val resolvedElement = element as? PsiNamedElement
                ?: (PsiUtils.resolveTargetElement(element)
                    ?: return@suspendingReadAction createErrorResult(ErrorMessages.SYMBOL_NOT_RESOLVED))

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
                    symbolName = effectiveTarget.name,
                    astPath = PsiUtils.getAstPath(effectiveTarget)
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
                            symbolName = qualifiedName,
                            astPath = emptyList()
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
                symbolName = symbolName,
                astPath = PsiUtils.getAstPath(effectiveTarget)
            ))
        }
    }
}
