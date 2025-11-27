package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DiagnosticsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.IntentionInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProblemInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class GetDiagnosticsTool : AbstractMcpTool() {

    override val name = "ide_diagnostics"

    override val description = """
        Analyzes a file for code problems and available intentions/improvements.
        Runs IntelliJ's code inspections to detect errors, warnings, and code quality issues.
        Also returns available intention actions (code improvements) at a specific position.
        Use when checking for compilation errors, potential bugs, or code style violations.
        Use when exploring available code transformations or improvements.
        Returns problems with severity (ERROR, WARNING, WEAK_WARNING, INFO) and available intentions.
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
                put("description", "1-based line number for intention lookup (optional, defaults to 1)")
            }
            putJsonObject("column") {
                put("type", "integer")
                put("description", "1-based column number for intention lookup (optional, defaults to 1)")
            }
            putJsonObject("startLine") {
                put("type", "integer")
                put("description", "1-based start line for filtering problems (optional)")
            }
            putJsonObject("endLine") {
                put("type", "integer")
                put("description", "1-based end line for filtering problems (optional)")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file"))
        }
    }

    override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments["line"]?.jsonPrimitive?.int ?: 1
        val column = arguments["column"]?.jsonPrimitive?.int ?: 1
        val startLine = arguments["startLine"]?.jsonPrimitive?.int
        val endLine = arguments["endLine"]?.jsonPrimitive?.int

        requireSmartMode(project)

        return readAction {
            val psiFile = getPsiFile(project, file)
                ?: return@readAction createErrorResult("File not found: $file")

            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@readAction createErrorResult("Could not get document for file")

            // Collect problems from inspections
            val problems = mutableListOf<ProblemInfo>()

            try {
                val inspectionManager = InspectionManager.getInstance(project)
                val profile = InspectionProjectProfileManager.getInstance(project).currentProfile

                val tools = profile.getAllEnabledInspectionTools(project)

                tools.forEach { toolWrapper ->
                    try {
                        val tool = toolWrapper.tool
                        if (tool is LocalInspectionTool) {
                            val holder = ProblemsHolder(inspectionManager, psiFile, false)

                            val visitor = tool.buildVisitor(holder, false)
                            psiFile.accept(visitor)

                            holder.results.forEach { problemDescriptor ->
                                val element = problemDescriptor.psiElement ?: return@forEach
                                val problemOffset = element.textOffset
                                val problemLine = document.getLineNumber(problemOffset) + 1
                                val problemColumn = problemOffset - document.getLineStartOffset(problemLine - 1) + 1

                                // Apply line filter
                                if (startLine != null && problemLine < startLine) return@forEach
                                if (endLine != null && problemLine > endLine) return@forEach

                                val endOffset = element.textOffset + element.textLength
                                val endLineNum = document.getLineNumber(endOffset) + 1
                                val endColumnNum = endOffset - document.getLineStartOffset(endLineNum - 1) + 1

                                val severity = when (problemDescriptor.highlightType) {
                                    ProblemHighlightType.ERROR, ProblemHighlightType.GENERIC_ERROR -> "ERROR"
                                    ProblemHighlightType.WARNING, ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> "WARNING"
                                    ProblemHighlightType.WEAK_WARNING -> "WEAK_WARNING"
                                    else -> "INFO"
                                }

                                problems.add(ProblemInfo(
                                    message = problemDescriptor.descriptionTemplate ?: "Unknown problem",
                                    severity = severity,
                                    file = file,
                                    line = problemLine,
                                    column = problemColumn,
                                    endLine = endLineNum,
                                    endColumn = endColumnNum
                                ))
                            }
                        }
                    } catch (_: Exception) {
                        // Individual inspection might fail
                    }
                }
            } catch (_: Exception) {
                // Analysis might fail, continue with partial results
            }

            // Collect intentions at the specified position
            val intentions = mutableListOf<IntentionInfo>()

            try {
                val offset = getOffset(document, line, column) ?: 0

                // Get highlights at the position from daemon
                DaemonCodeAnalyzerEx.processHighlights(
                    document,
                    project,
                    HighlightSeverity.INFORMATION,
                    offset,
                    offset + 1
                ) { highlightInfo ->
                    highlightInfo.findRegisteredQuickFix<IntentionAction> { descriptor, _ ->
                        val action = descriptor.action
                        if (action.isAvailable(project, null, psiFile)) {
                            intentions.add(IntentionInfo(
                                name = action.text,
                                description = action.familyName.takeIf { it != action.text }
                            ))
                        }
                        null
                    }
                    true
                }

                // Also check for general intention actions
                val element = psiFile.findElementAt(offset)
                if (element != null) {
                    IntentionManager.getInstance()
                        .getAvailableIntentions()
                        .filter { it.isAvailable(project, null, psiFile) }
                        .take(20)
                        .forEach { action ->
                            intentions.add(IntentionInfo(
                                name = action.text,
                                description = action.familyName.takeIf { it != action.text }
                            ))
                        }
                }
            } catch (_: Exception) {
                // Intention discovery might fail
            }

            createJsonResult(DiagnosticsResult(
                problems = problems.distinctBy { "${it.line}:${it.column}:${it.message}" },
                intentions = intentions.distinctBy { it.name },
                problemCount = problems.size,
                intentionCount = intentions.size
            ))
        }
    }
}

