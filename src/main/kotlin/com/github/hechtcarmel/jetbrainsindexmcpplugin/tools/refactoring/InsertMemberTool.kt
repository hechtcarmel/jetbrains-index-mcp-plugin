package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.*
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class InsertMemberTool : AbstractMcpTool() {

    override val name = ToolNames.INSERT_MEMBER

    override val description = """
        Insert a new class member (method, field, constructor, inner class, etc.) at a structural position.

        The content should be the complete member declaration including any modifiers, type, name, and body.
        Auto-reformats the inserted range by default.

        Position is specified by an anchor member and a relative position (before/after).
        If no anchor is specified, inserts at the end of the class body (or end of file for top-level).

        Examples:
        - {"file": "src/Main.java", "class": "Main", "content": "public String getFullName() {\n    return firstName + \" \" + lastName;\n}", "position": "after", "anchor": "getLastName"}
        - {"file": "src/Main.java", "class": "Main", "content": "private int age;", "position": "before", "anchor": "getName"}
        - {"file": "src/utils.kt", "content": "fun helper(): String = \"help\""}
    """.trimIndent()

    override val inputSchema = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to file relative to project root. REQUIRED.")
        .stringProperty(ParamNames.CLASS, "Class/interface name to insert into. Optional for top-level members (Kotlin).")
        .stringProperty(ParamNames.CONTENT, "The complete member declaration to insert, including modifiers, type, name, and body.", required = true)
        .enumProperty(ParamNames.POSITION, "Where to insert relative to the anchor: 'before', 'after', 'first', or 'last' (default).", listOf("before", "after", "first", "last"))
        .stringProperty(ParamNames.ANCHOR, "Name of an existing member to insert before/after. Required when position is 'before' or 'after'.")
        .intProperty(ParamNames.ANCHOR_PARAMETER_COUNT, "Number of parameters on the anchor method (for disambiguating overloaded methods).")
        .intProperty(ParamNames.ANCHOR_LINE, "1-based line number of the anchor member (for disambiguation).")
        .booleanProperty(ParamNames.REFORMAT, "Auto-reformat the inserted range and optimize imports. Default: true.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments[ParamNames.FILE]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val content = arguments[ParamNames.CONTENT]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: content")
        if (content.isBlank()) {
            return createErrorResult("content must not be empty.")
        }
        val className = MemberEditingUtils.getOptionalString(arguments, ParamNames.CLASS)
        val position = MemberEditingUtils.getOptionalString(arguments, ParamNames.POSITION) ?: "last"
        val anchorName = MemberEditingUtils.getOptionalString(arguments, ParamNames.ANCHOR)
        val anchorParamCount = MemberEditingUtils.getOptionalInt(arguments, ParamNames.ANCHOR_PARAMETER_COUNT)
        val anchorLine = MemberEditingUtils.getOptionalInt(arguments, ParamNames.ANCHOR_LINE)
        val reformat = MemberEditingUtils.getOptionalBoolean(arguments, ParamNames.REFORMAT)

        if (position !in listOf("before", "after", "first", "last")) {
            return createErrorResult("Invalid position: '$position'. Must be one of: before, after, first, last.")
        }
        if ((position == "before" || position == "after") && anchorName == null) {
            return createErrorResult("Parameter 'anchor' is required when position is '$position'.")
        }

        val virtualFile = resolveFile(project, filePath)
            ?: return createErrorResult("File not found: $filePath")

        ensureWritable(virtualFile, filePath)?.let { return it }

        val prep = suspendingReadAction {
            prepareInsertion(project, virtualFile, filePath, className, position, anchorName, anchorParamCount, anchorLine)
        }

        return when {
            prep.isFailure -> prep.exceptionOrNull()!!.let { handleError(it) }
            else -> {
                val p = prep.getOrThrow()
                applyInsertion(project, p, content, reformat)
            }
        }
    }

    private fun prepareInsertion(
        project: Project,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        filePath: String,
        className: String?,
        position: String,
        anchorName: String?,
        anchorParamCount: Int?,
        anchorLine: Int?
    ): Result<InsertPreparation> {
        val psiFile = MemberEditingUtils.resolvePsiFile(project, filePath, virtualFile)
            ?: return Result.failure(Exception("File not found: $filePath"))

        val resolver = MemberEditingUtils.getResolver(psiFile, project)
            ?: return Result.failure(Exception("Member editing not supported for ${psiFile.language.displayName}. Supported: Java, Kotlin."))

        val scope = resolver.findClass(psiFile, className)
            ?: return Result.failure(
                MemberClassNotFoundException(
                    className ?: "",
                    emptyList()
                )
            )

        val anchor = if (anchorName != null) {
            val members = resolver.findMembers(scope, anchorName)
            val disambiguated = MemberResolverUtils.disambiguate(members, anchorName, anchorParamCount, anchorLine)
            if (disambiguated.isFailure) return Result.failure(disambiguated.exceptionOrNull()!!)
            disambiguated.getOrThrow()
        } else null

        val insertionOffset = resolver.getInsertionOffset(scope, position, anchor)
            ?: return Result.failure(Exception(
                if (scope is com.intellij.psi.PsiFile)
                    "Cannot determine insertion point. The file may have multiple classes — specify the 'class' parameter."
                else
                    "Cannot insert into this scope — the target class has no body. " +
                    "For body-less classes (e.g., data class, record), add a class body first or use ide_edit_member to replace the entire declaration."
            ))
        val document = MemberEditingUtils.getDocument(psiFile)
            ?: return Result.failure(Exception("Cannot get document for file: $filePath"))

        val relativePath = ProjectUtils.getToolFilePath(project, psiFile.virtualFile)
        return Result.success(InsertPreparation(psiFile, document, insertionOffset, relativePath))
    }

    private suspend fun applyInsertion(
        project: Project,
        prep: InsertPreparation,
        content: String,
        reformat: Boolean
    ): ToolCallResult {
        val insertText = "\n$content\n"

        var startLine = 0
        var endLine = 0

        suspendingWriteAction(project, "Insert member") {
            prep.document.insertString(prep.insertionOffset, insertText)
            MemberEditingUtils.commitDocuments(project)
            if (reformat) {
                MemberEditingUtils.reformatRange(
                    project, prep.psiFile, prep.insertionOffset, prep.insertionOffset + insertText.length
                )
                MemberEditingUtils.commitDocuments(project)
            }
            startLine = MemberEditingUtils.safeLineNumber(prep.document, prep.insertionOffset)
            endLine = MemberEditingUtils.safeLineNumber(prep.document, prep.insertionOffset + insertText.length)
        }
        MemberEditingUtils.saveToDisk()

        return createJsonResult(MemberEditResult(
            success = true,
            file = prep.relativePath,
            message = "Inserted member",
            startLine = startLine,
            endLine = endLine
        ))
    }

    private fun handleError(error: Throwable): ToolCallResult {
        return when (error) {
            is MemberNotFoundException -> createJsonResult(MemberErrorResult(
                error = "anchor_not_found",
                member = error.memberName,
                hint = "Anchor member '${error.memberName}' not found."
            ))
            is AmbiguousMemberException -> createJsonResult(MemberErrorResult(
                error = "ambiguous_anchor",
                member = error.memberName,
                candidates = error.candidates.map {
                    MemberCandidate(it.name, it.kind, it.signature, it.parameterCount, it.line)
                },
                hint = error.hint
            ))
            is MemberClassNotFoundException -> createErrorResult(
                if (error.className.isEmpty()) "Could not determine target class. The file may have multiple classes — specify the 'class' parameter."
                else "Class '${error.className}' not found in file."
            )
            else -> createErrorResult(error.message ?: "Unknown error")
        }
    }
}
