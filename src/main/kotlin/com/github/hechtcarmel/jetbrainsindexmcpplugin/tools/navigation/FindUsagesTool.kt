package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.UsageTypes
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.UsageLocation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class FindUsagesTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_MAX_RESULTS = 100
        private const val MAX_ALLOWED_RESULTS = 500
    }

    override val name = ToolNames.FIND_REFERENCES

    override val description = """
        Find all references to a symbol across the project. Use when you need to understand how a class, method, field, or variable is used before modifying or removing it.

        Returns: file paths, line numbers, context snippets, and reference types (method_call, field_access, import, etc.).

        Parameters: file + line + column (required), maxResults (optional, default: 100, max: 500).

        Example: {"file": "src/UserService.java", "line": 25, "column": 18}
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
                put(SchemaConstants.DESCRIPTION, "1-based line number where the symbol is located. REQUIRED.")
            }
            putJsonObject(ParamNames.COLUMN) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based column number within the line. REQUIRED.")
            }
            putJsonObject("maxResults") {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "Maximum number of references to return. Default: $DEFAULT_MAX_RESULTS, max: $MAX_ALLOWED_RESULTS.")
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
        val maxResults = (arguments["maxResults"]?.jsonPrimitive?.int ?: DEFAULT_MAX_RESULTS)
            .coerceIn(1, MAX_ALLOWED_RESULTS)

        requireSmartMode(project)

        return suspendingReadAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@suspendingReadAction createErrorResult(ErrorMessages.noElementAtPosition(file, line, column))

            // Resolve the target element using semantic reference resolution.
            // This correctly handles method calls (resolves to the called method)
            // vs declarations (returns the declaration itself).
            val targetElement = PsiUtils.resolveTargetElement(element)
                ?: return@suspendingReadAction createErrorResult(ErrorMessages.NO_NAMED_ELEMENT)

            // Lock-free concurrent collection - ReferencesSearch may invoke processor from multiple threads
            val usages = ConcurrentLinkedQueue<UsageLocation>()
            val count = AtomicInteger(0)

            // Process references with cancellation support and early termination
            ReferencesSearch.search(targetElement).forEach(Processor { reference ->
                ProgressManager.checkCanceled() // Allow cancellation between iterations

                // Fast path: already at limit
                if (count.get() >= maxResults) {
                    return@Processor false
                }

                val refElement = reference.element
                val refFile = refElement.containingFile?.virtualFile
                if (refFile != null) {
                    val document = PsiDocumentManager.getInstance(project)
                        .getDocument(refElement.containingFile)
                    if (document != null) {
                        val lineNumber = document.getLineNumber(refElement.textOffset) + 1
                        val columnNumber = refElement.textOffset -
                            document.getLineStartOffset(lineNumber - 1) + 1

                        val lineText = document.getText(
                            TextRange(
                                document.getLineStartOffset(lineNumber - 1),
                                document.getLineEndOffset(lineNumber - 1)
                            )
                        ).trim()

                        // Optimistically claim a slot via CAS increment
                        val slot = count.incrementAndGet()
                        if (slot <= maxResults) {
                            usages.add(UsageLocation(
                                file = getRelativePath(project, refFile),
                                line = lineNumber,
                                column = columnNumber,
                                context = lineText,
                                type = classifyUsage(refElement)
                            ))
                        }
                        return@Processor slot < maxResults
                    }
                }
                true
            })

            val usagesList = usages.toList()
            createJsonResult(FindUsagesResult(
                usages = usagesList,
                totalCount = usagesList.size
            ))
        }
    }

    private fun classifyUsage(element: PsiElement): String {
        val parent = element.parent
        val parentClass = parent?.javaClass?.simpleName ?: "Unknown"

        return when {
            parentClass.contains("MethodCall") -> UsageTypes.METHOD_CALL
            parentClass.contains("Reference") -> UsageTypes.REFERENCE
            parentClass.contains("Field") -> UsageTypes.FIELD_ACCESS
            parentClass.contains("Import") -> UsageTypes.IMPORT
            parentClass.contains("Parameter") -> UsageTypes.PARAMETER
            parentClass.contains("Variable") -> UsageTypes.VARIABLE
            else -> UsageTypes.REFERENCE
        }
    }
}
