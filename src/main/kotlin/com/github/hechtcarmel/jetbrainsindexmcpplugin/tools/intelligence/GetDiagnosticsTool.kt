package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DiagnosticsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.IntentionInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProblemInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * MCP tool that analyzes files for code problems and available intentions.
 * 
 * This tool leverages IntelliJ's daemon code analyzer to detect:
 * - Compilation errors
 * - Code warnings and weak warnings
 * - Available quick fixes and intentions
 * 
 * For files not currently open in the editor, the tool temporarily opens them
 * to trigger daemon analysis, then closes them after collecting results.
 */
class GetDiagnosticsTool : AbstractMcpTool() {

    companion object {
        private const val MAX_PROBLEMS = 100
        private const val MAX_INTENTIONS = 50
        private const val DAEMON_ANALYSIS_WAIT_MS = 500L
    }

    override val name = "ide_diagnostics"

    override val description = """
        Analyzes a file for code problems (errors, warnings) and available quick fixes/intentions.

        REQUIRED: file - the file to analyze.
        OPTIONAL: line + column - position for intention lookup; startLine/endLine - filter problems to line range.

        RETURNS: List of problems with severity and available intentions/quick fixes.

        EXAMPLE (whole file): {"file": "src/main/java/com/example/MyClass.java"}
        EXAMPLE (specific position): {"file": "src/main/java/MyClass.java", "line": 25, "column": 10}
        EXAMPLE (line range): {"file": "src/main/java/MyClass.java", "startLine": 10, "endLine": 50}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_path") {
                put("type", "string")
                put("description", "Absolute path to project root. Only needed when multiple projects are open in IDE.")
            }
            putJsonObject("file") {
                put("type", "string")
                put("description", "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). REQUIRED.")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line number for intention lookup. Optional, defaults to 1.")
            }
            putJsonObject("column") {
                put("type", "integer")
                put("description", "1-based column number for intention lookup. Optional, defaults to 1.")
            }
            putJsonObject("startLine") {
                put("type", "integer")
                put("description", "Filter problems to start from this line. Optional.")
            }
            putJsonObject("endLine") {
                put("type", "integer")
                put("description", "Filter problems to end at this line. Optional.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file"))
        }
    }

    override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        // Parse arguments
        val filePath = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments["line"]?.jsonPrimitive?.int ?: 1
        val column = arguments["column"]?.jsonPrimitive?.int ?: 1
        val startLine = arguments["startLine"]?.jsonPrimitive?.int
        val endLine = arguments["endLine"]?.jsonPrimitive?.int

        requireSmartMode(project)
        
        // Resolve the virtual file
        val virtualFile = resolveVirtualFile(project, filePath)
            ?: return createErrorResult("File not found: $filePath")

        // Ensure file is open for daemon analysis
        val fileEditorManager = FileEditorManager.getInstance(project)
        val wasAlreadyOpen = fileEditorManager.isFileOpen(virtualFile)
        
        if (!wasAlreadyOpen) {
            openFileForAnalysis(fileEditorManager, virtualFile)
        }

        return try {
            analyzeFile(project, fileEditorManager, virtualFile, filePath, line, column, startLine, endLine)
        } finally {
            if (!wasAlreadyOpen) {
                closeFile(fileEditorManager, virtualFile)
            }
        }
    }

    // ========== File Management ==========

    private fun resolveVirtualFile(project: Project, filePath: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        val fullPath = if (filePath.startsWith("/")) filePath else "$basePath/$filePath"
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
    }

    private suspend fun openFileForAnalysis(fileEditorManager: FileEditorManager, virtualFile: VirtualFile) {
        ApplicationManager.getApplication().invokeAndWait {
            fileEditorManager.openFile(virtualFile, false)
        }
        // Wait for daemon to start analyzing
        delay(DAEMON_ANALYSIS_WAIT_MS)
    }

    private fun closeFile(fileEditorManager: FileEditorManager, virtualFile: VirtualFile) {
        ApplicationManager.getApplication().invokeAndWait {
            fileEditorManager.closeFile(virtualFile)
        }
    }

    // ========== Analysis ==========

    private suspend fun analyzeFile(
        project: Project,
        fileEditorManager: FileEditorManager,
        virtualFile: VirtualFile,
        filePath: String,
        line: Int,
        column: Int,
        startLine: Int?,
        endLine: Int?
    ): ToolCallResult = readAction {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return@readAction createErrorResult("Could not parse file: $filePath")

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return@readAction createErrorResult("Could not get document for file")

        val editor = fileEditorManager.getEditors(virtualFile)
            .filterIsInstance<TextEditor>()
            .firstOrNull()
            ?.editor

        val problems = collectProblems(project, document, filePath, startLine, endLine)
        val intentions = collectIntentions(project, psiFile, document, editor, line, column)

        createJsonResult(DiagnosticsResult(
            problems = problems,
            intentions = intentions,
            problemCount = problems.size,
            intentionCount = intentions.size
        ))
    }

    // ========== Problem Collection ==========

    private fun collectProblems(
        project: Project,
        document: Document,
        filePath: String,
        startLine: Int?,
        endLine: Int?
    ): List<ProblemInfo> {
        val problems = mutableListOf<ProblemInfo>()
        
        try {
            DaemonCodeAnalyzerEx.processHighlights(
                document,
                project,
                HighlightSeverity.INFORMATION,
                0,
                document.textLength
            ) { highlightInfo ->
                if (highlightInfo.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal) {
                    val problem = highlightInfo.toProblemInfo(document, filePath)
                    
                    // Apply line filter
                    val inRange = (startLine == null || problem.line >= startLine) &&
                                  (endLine == null || problem.line <= endLine)
                    
                    if (inRange) {
                        problems.add(problem)
                    }
                }
                problems.size < MAX_PROBLEMS
            }
        } catch (_: Exception) {
            // Daemon analysis might not be available
        }
        
        return problems.distinctBy { "${it.line}:${it.column}:${it.message}" }
    }

    private fun HighlightInfo.toProblemInfo(document: Document, filePath: String): ProblemInfo {
        val problemLine = document.getLineNumber(startOffset) + 1
        val problemColumn = startOffset - document.getLineStartOffset(problemLine - 1) + 1
        val endLineNum = document.getLineNumber(endOffset) + 1
        val endColumnNum = endOffset - document.getLineStartOffset(endLineNum - 1) + 1
        
        val severityString = when {
            severity.myVal >= HighlightSeverity.ERROR.myVal -> "ERROR"
            severity.myVal >= HighlightSeverity.WARNING.myVal -> "WARNING"
            severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal -> "WEAK_WARNING"
            else -> "INFO"
        }
        
        return ProblemInfo(
            message = description ?: "Unknown problem",
            severity = severityString,
            file = filePath,
            line = problemLine,
            column = problemColumn,
            endLine = endLineNum,
            endColumn = endColumnNum
        )
    }

    // ========== Intention Collection ==========

    private fun collectIntentions(
        project: Project,
        psiFile: PsiFile,
        document: Document,
        editor: Editor?,
        line: Int,
        column: Int
    ): List<IntentionInfo> {
        val intentions = mutableListOf<IntentionInfo>()
        
        try {
            val offset = getOffset(document, line, column) ?: 0
            
            // Collect quick fixes from highlights at this position
            if (editor != null) {
                collectQuickFixes(project, document, editor, psiFile, offset, intentions)
            }

            // Collect general intention actions
            if (psiFile.findElementAt(offset) != null) {
                collectGeneralIntentions(project, editor, psiFile, intentions)
            }
        } catch (_: Exception) {
            // Intention discovery might fail
        }
        
        return intentions.distinctBy { it.name }
    }

    private fun collectQuickFixes(
        project: Project,
        document: Document,
        editor: Editor,
        psiFile: PsiFile,
        offset: Int,
        intentions: MutableList<IntentionInfo>
    ) {
        DaemonCodeAnalyzerEx.processHighlights(
            document,
            project,
            HighlightSeverity.INFORMATION,
            offset,
            offset + 1
        ) { highlightInfo ->
            highlightInfo.findRegisteredQuickFix<Any> { descriptor, _ ->
                val action = descriptor.action
                try {
                    if (action.isAvailable(project, editor, psiFile)) {
                        intentions.add(IntentionInfo(
                            name = action.text,
                            description = action.familyName.takeIf { it != action.text }
                        ))
                    }
                } catch (_: Exception) {
                    // Availability check might fail
                }
                null
            }
            true
        }
    }

    private fun collectGeneralIntentions(
        project: Project,
        editor: Editor?,
        psiFile: PsiFile,
        intentions: MutableList<IntentionInfo>
    ) {
        IntentionManager.getInstance()
            .getAvailableIntentions()
            .take(MAX_INTENTIONS)
            .forEach { action ->
                try {
                    val isAvailable = action.isAvailable(project, editor, psiFile)
                    if (isAvailable) {
                        intentions.add(IntentionInfo(
                            name = action.text,
                            description = action.familyName.takeIf { it != action.text }
                        ))
                    }
                } catch (_: Exception) {
                    // Individual intention check might fail
                }
            }
    }
}
