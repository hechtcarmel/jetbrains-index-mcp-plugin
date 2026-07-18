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

class ReplaceMemberTool : AbstractMcpTool() {

    override val name = ToolNames.REPLACE_MEMBER

    override val description = """
        Replace the body of a method/function or the initializer of a field/property.

        For methods: replaces the code between { and }. The content should NOT include surrounding braces.
        For fields/properties: replaces the initializer expression (after =).
        Auto-reformats the changed range by default.

        Requires: file (always), member (always). class is optional for top-level members (Kotlin).
        For overloaded methods, use parameterCount or line to disambiguate.

        Examples:
        - {"file": "src/Main.java", "class": "Main", "member": "getName", "content": "return this.name;"}
        - {"file": "src/Config.kt", "member": "defaultPort", "content": "8080"}
    """.trimIndent()

    override val inputSchema = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to file relative to project root. REQUIRED.")
        .stringProperty(ParamNames.CLASS, "Class/interface name containing the member. Optional for top-level members (Kotlin).")
        .stringProperty(ParamNames.MEMBER, "Name of the method, function, field, or property to replace the body/initializer of.", required = true)
        .intProperty(ParamNames.PARAMETER_COUNT, "Number of parameters (for disambiguating overloaded methods).")
        .intProperty(ParamNames.LINE, "1-based line number of the member (for disambiguation when multiple members share the same name).")
        .stringProperty(ParamNames.CONTENT, "The new body content (without surrounding braces for methods) or new initializer expression.", required = true)
        .booleanProperty(ParamNames.REFORMAT, "Auto-reformat the changed range and optimize imports. Default: true.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments[ParamNames.FILE]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val memberName = arguments[ParamNames.MEMBER]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: member")
        val content = arguments[ParamNames.CONTENT]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: content")
        val className = MemberEditingUtils.getOptionalString(arguments, ParamNames.CLASS)
        val parameterCount = MemberEditingUtils.getOptionalInt(arguments, ParamNames.PARAMETER_COUNT)
        val line = MemberEditingUtils.getOptionalInt(arguments, ParamNames.LINE)
        val reformat = MemberEditingUtils.getOptionalBoolean(arguments, ParamNames.REFORMAT)

        val virtualFile = resolveFile(project, filePath)
            ?: return createErrorResult("File not found: $filePath")

        val prep = suspendingReadAction {
            prepareMemberEdit(project, virtualFile, filePath, className, memberName, parameterCount, line)
        }

        return when {
            prep.isFailure -> prep.exceptionOrNull()!!.let { handleError(it, memberName) }
            else -> {
                val p = prep.getOrThrow()
                applyBodyReplacement(project, p, content, reformat)
            }
        }
    }

    private fun prepareMemberEdit(
        project: Project,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        filePath: String,
        className: String?,
        memberName: String,
        parameterCount: Int?,
        line: Int?
    ): Result<MemberEditPreparation> {
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

        val members = resolver.findMembers(scope, memberName)
        val disambiguated = MemberResolverUtils.disambiguate(members, memberName, parameterCount, line)
        if (disambiguated.isFailure) return Result.failure(disambiguated.exceptionOrNull()!!)

        val member = disambiguated.getOrThrow()
        if (member.kind == "class") {
            return Result.failure(Exception(
                "Cannot replace body of a class declaration. Use ide_edit_member to replace the entire class declaration, or specify parameterCount to target a constructor."
            ))
        }
        val document = MemberEditingUtils.getDocument(psiFile)
            ?: return Result.failure(Exception("Cannot get document for file: $filePath"))

        val relativePath = ProjectUtils.getToolFilePath(project, psiFile.virtualFile)
        return Result.success(MemberEditPreparation(psiFile, document, member, relativePath))
    }

    private suspend fun applyBodyReplacement(
        project: Project,
        prep: MemberEditPreparation,
        content: String,
        reformat: Boolean
    ): ToolCallResult {
        val member = prep.member
        val originalBodyStart = member.bodyStartOffset
        val originalBodyEnd = member.bodyEndOffset

        if (originalBodyStart == null || originalBodyEnd == null) {
            return createErrorResult(
                "Member '${member.name}' has no body/initializer to replace. Use ide_edit_member for full replacement."
            )
        }

        var startLine = 0
        var endLine = 0
        var error: String? = null

        suspendingWriteAction(project, "Replace member body: ${member.name}") {
            if (!member.element.isValid) {
                error =
                    "PSI element for '${member.name}' is no longer valid. The document may have been modified externally — retry the operation."
                return@suspendingWriteAction
            }
            val currentRange = member.element.textRange
            val delta = currentRange.startOffset - member.startOffset
            val bodyStart = originalBodyStart + delta
            val bodyEnd = originalBodyEnd + delta

            val docLength = prep.document.textLength
            if (bodyStart < 0 || bodyEnd > docLength || bodyStart > bodyEnd) {
                error =
                    "Body offsets [${bodyStart}, ${bodyEnd}) are out of bounds (document length: ${docLength}). The document may have been modified externally — retry the operation."
                return@suspendingWriteAction
            }

            prep.document.replaceString(bodyStart, bodyEnd, content)
            MemberEditingUtils.commitDocuments(project)
            if (reformat) {
                MemberEditingUtils.reformatRange(project, prep.psiFile, bodyStart, bodyStart + content.length)
                MemberEditingUtils.commitDocuments(project)
            }
            startLine = MemberEditingUtils.safeLineNumber(prep.document, bodyStart)
            endLine = MemberEditingUtils.safeLineNumber(prep.document, bodyStart + content.length)
        }

        if (error != null) {
            return createErrorResult(error!!)
        }

        MemberEditingUtils.saveToDisk()

        return createJsonResult(
            MemberEditResult(
                success = true,
                file = prep.relativePath,
                message = "Replaced body of ${member.kind} '${member.name}'",
                startLine = startLine,
                endLine = endLine
            )
        )
    }

    private fun handleError(error: Throwable, memberName: String): ToolCallResult {
        return when (error) {
            is MemberNotFoundException -> createJsonResult(MemberErrorResult(
                error = "member_not_found",
                member = memberName,
                hint = "Member '$memberName' not found in the specified scope."
            ))
            is AmbiguousMemberException -> createJsonResult(MemberErrorResult(
                error = "ambiguous_member",
                member = memberName,
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
