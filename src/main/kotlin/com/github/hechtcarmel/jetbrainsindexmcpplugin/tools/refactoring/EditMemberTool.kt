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

class EditMemberTool : AbstractMcpTool() {

    override val name = ToolNames.EDIT_MEMBER

    override val description = """
        Replace an entire member or class declaration with new content.

        Replaces the complete declaration from the first modifier/annotation through the closing brace.
        Use for method signature changes, field rewrites, or class/interface declaration changes
        (adding type parameters, changing extends/implements, adding annotations).

        To edit a class/interface declaration itself, set member to the class name:
        {"file": "src/Worker.java", "class": "Worker", "member": "Worker", "content": "public interface Worker<T> extends Runnable { ... }"}

        The content should be the complete replacement including modifiers, type, name, and body.
        Auto-reformats the changed range by default.

        Examples:
        - {"file": "src/Main.java", "class": "Main", "member": "process", "content": "public void process(String input, boolean validate) {\n    if (validate) check(input);\n}"}
        - {"file": "src/Config.kt", "class": "Config", "member": "timeout", "content": "val timeout: Duration = Duration.ofSeconds(30)"}
        - {"file": "src/Service.java", "member": "Service", "content": "public class Service<T> implements Serializable { ... }"}
    """.trimIndent()

    override val inputSchema = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to file relative to project root. REQUIRED.")
        .stringProperty(ParamNames.CLASS, "Class/interface name containing the member. Optional for top-level members (Kotlin).")
        .stringProperty(ParamNames.MEMBER, "Name of the method, function, field, or property to replace entirely.", required = true)
        .intProperty(ParamNames.PARAMETER_COUNT, "Number of parameters (for disambiguating overloaded methods).")
        .intProperty(ParamNames.LINE, "1-based line number of the member (for disambiguation when multiple members share the same name).")
        .stringProperty(ParamNames.CONTENT, "The complete replacement member declaration including modifiers, type, name, and body.", required = true)
        .booleanProperty(ParamNames.REFORMAT, "Auto-reformat the changed range and optimize imports. Default: true.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments[ParamNames.FILE]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val memberName = arguments[ParamNames.MEMBER]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: member")
        val content = arguments[ParamNames.CONTENT]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: content")
        if (content.isBlank()) {
            return createErrorResult("content must not be empty. To delete a member, use ide_refactor_safe_delete.")
        }
        val className = MemberEditingUtils.getOptionalString(arguments, ParamNames.CLASS)
        val parameterCount = MemberEditingUtils.getOptionalInt(arguments, ParamNames.PARAMETER_COUNT)
        val line = MemberEditingUtils.getOptionalInt(arguments, ParamNames.LINE)
        val reformat = MemberEditingUtils.getOptionalBoolean(arguments, ParamNames.REFORMAT)

        val virtualFile = resolveFile(project, filePath)
            ?: return createErrorResult("File not found: $filePath")

        ensureWritable(virtualFile, filePath)?.let { return it }

        val prep = suspendingReadAction {
            prepareMemberEdit(project, virtualFile, filePath, className, memberName, parameterCount, line)
        }

        return when {
            prep.isFailure -> prep.exceptionOrNull()!!.let { handleError(it, memberName) }
            else -> {
                val p = prep.getOrThrow()
                applyFullReplacement(project, p, content, reformat)
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
        val document = MemberEditingUtils.getDocument(psiFile)
            ?: return Result.failure(Exception("Cannot get document for file: $filePath"))

        val relativePath = ProjectUtils.getToolFilePath(project, psiFile.virtualFile)
        return Result.success(MemberEditPreparation(psiFile, document, member, relativePath))
    }

    private suspend fun applyFullReplacement(
        project: Project,
        prep: MemberEditPreparation,
        content: String,
        reformat: Boolean
    ): ToolCallResult {
        val member = prep.member

        var startLine = 0
        var endLine = 0
        var error: String? = null

        suspendingWriteAction(project, "Edit member: ${member.name}") {
            if (!member.element.isValid) {
                error =
                    "PSI element for '${member.name}' is no longer valid. The document may have been modified externally — retry the operation."
                return@suspendingWriteAction
            }
            val range = member.element.textRange
            val startOffset = range.startOffset
            val endOffset = range.endOffset

            prep.document.replaceString(startOffset, endOffset, content)
            MemberEditingUtils.commitDocuments(project)
            if (reformat) {
                MemberEditingUtils.reformatRange(project, prep.psiFile, startOffset, startOffset + content.length)
                MemberEditingUtils.commitDocuments(project)
            }
            startLine = MemberEditingUtils.safeLineNumber(prep.document, startOffset)
            endLine = MemberEditingUtils.safeLineNumber(prep.document, startOffset + content.length)
        }

        if (error != null) {
            return createErrorResult(error!!)
        }

        MemberEditingUtils.saveToDisk()

        return createJsonResult(
            MemberEditResult(
                success = true,
                file = prep.relativePath,
                message = "Replaced ${member.kind} '${member.name}' entirely",
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
