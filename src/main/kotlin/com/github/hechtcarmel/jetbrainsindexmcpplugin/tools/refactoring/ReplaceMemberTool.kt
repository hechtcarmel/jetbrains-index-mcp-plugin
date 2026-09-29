package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.UnifiedTargetArguments
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ResolvedSymbolInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.*
import com.intellij.openapi.project.Project
import com.intellij.psi.SmartPointerManager
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ReplaceMemberTool : AbstractMcpTool() {

    override val name = ToolNames.REPLACE_MEMBER

    override val description = """
        Replace the body of a method/function or the initializer of a field/property.

        For methods: replaces the code between { and }. The content should NOT include surrounding braces.
        For fields/properties: replaces the initializer expression (after =).
        Auto-reformats the changed range by default.

        Target the declaration with nested target ({symbolId}, {position: {file, line, column}},
        or {qualifiedName, language}), top-level symbolId, top-level language + symbol, or the
        legacy file + member selector. Do not combine selector forms. class is optional for
        top-level members (Kotlin).
        For overloaded methods, use parameterCount or line to disambiguate.

        Examples:
        - {"symbolId": "<opaque-id>", "content": "return 42;"}
        - {"target": {"qualifiedName": "com.example.Main#getName()", "language": "Java"}, "content": "return this.name;"}
        - {"file": "src/Main.java", "class": "Main", "member": "getName", "content": "return this.name;"}
        - {"file": "src/Config.kt", "member": "defaultPort", "content": "8080"}
    """.trimIndent()

    override val inputSchema = SchemaBuilder.tool()
        .projectPath()
        .target()
        .symbolId()
        .languageAndSymbol(required = false)
        .file(required = false, description = "Path to file relative to project root. Required with member selectors; omit when symbolId is used.")
        .stringProperty(ParamNames.CLASS, "Class/interface name containing the member. Optional for top-level members (Kotlin).")
        .stringProperty(ParamNames.MEMBER, "Name of the method, function, field, or property to replace the body/initializer of. Required unless symbolId is used.")
        .intProperty(ParamNames.PARAMETER_COUNT, "Number of parameters (for disambiguating overloaded methods).")
        .intProperty(ParamNames.LINE, "1-based line number of the member (for disambiguation when multiple members share the same name).")
        .stringProperty(ParamNames.CONTENT, "The new body content (without surrounding braces for methods) or new initializer expression.", required = true)
        .booleanProperty(ParamNames.REFORMAT, "Auto-reformat the changed range and optimize imports. Default: true.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val requestedSymbolId = optionalStringArg(arguments, ParamNames.SYMBOL_ID)
        val hasQualifiedTarget = optionalStringArg(arguments, ParamNames.LANGUAGE) != null ||
            optionalStringArg(arguments, ParamNames.SYMBOL) != null
        val hasStructuredTarget = optionalStringArg(arguments, UnifiedTargetArguments.NORMALIZED_VARIANT) != null
        val memberName = optionalStringArg(arguments, ParamNames.MEMBER) ?: "<symbolId>"
        val content = arguments[ParamNames.CONTENT]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: content")
        val className = MemberEditingUtils.getOptionalString(arguments, ParamNames.CLASS)
        val parameterCount = MemberEditingUtils.getOptionalInt(arguments, ParamNames.PARAMETER_COUNT)
        val line = MemberEditingUtils.getOptionalInt(arguments, ParamNames.LINE)
        val reformat = MemberEditingUtils.getOptionalBoolean(arguments, ParamNames.REFORMAT)
        val hasLegacyMemberSelector = listOf(ParamNames.FILE, ParamNames.CLASS, ParamNames.MEMBER).any {
            optionalStringArg(arguments, it) != null
        } || parameterCount != null || line != null

        // Discover external files before the read action, where synchronous VFS refresh is unsafe.
        // Normalized position targets need the same discovery as legacy member selectors.
        val coordinateFile = if (requestedSymbolId == null && !hasQualifiedTarget) {
            optionalStringArg(arguments, ParamNames.FILE)?.let { resolveFile(project, it) }
        } else null
        coordinateFile?.let { file -> syncFileForEdit(project, file)?.let { return it } }

        suspend fun prepare(): Result<MemberEditPreparation> = suspendingReadAction {
            if (requestedSymbolId != null || hasQualifiedTarget || hasStructuredTarget) {
                if (!hasStructuredTarget && hasLegacyMemberSelector) {
                    Result.failure(IllegalArgumentException(ErrorMessages.SYMBOL_ID_AND_OTHER_TARGET_EXCLUSIVE))
                } else {
                    prepareMemberEditBySemanticTarget(project, arguments, requestedSymbolId)
                }
            } else {
                val filePath = optionalStringArg(arguments, ParamNames.FILE)
                    ?: return@suspendingReadAction Result.failure(
                        IllegalArgumentException("Missing required parameter: ${ParamNames.FILE}")
                    )
                if (memberName == "<symbolId>") {
                    return@suspendingReadAction Result.failure(
                        IllegalArgumentException("Missing required parameter: ${ParamNames.MEMBER}")
                    )
                }
                val virtualFile = coordinateFile
                    ?: return@suspendingReadAction Result.failure(
                        IllegalArgumentException("File not found: $filePath")
                    )
                if (!virtualFile.isWritable) {
                    return@suspendingReadAction Result.failure(
                        IllegalArgumentException("File is read-only and cannot be modified: ${virtualFile.path}")
                    )
                }
                prepareMemberEdit(project, virtualFile, filePath, className, memberName, parameterCount, line)
            }
        }

        var prep = prepare()
        // A symbolId or qualified name identifies the file only once resolved. Sync it now, and
        // resolve again if the refresh loaded a newer version of the file from disk.
        if (coordinateFile == null) {
            prep.getOrNull()?.let { resolved ->
                val stamp = resolved.document.modificationStamp
                resolved.psiFile.virtualFile?.let { file -> syncFileForEdit(project, file)?.let { return it } }
                if (resolved.document.modificationStamp != stamp) prep = prepare()
            }
        }

        return when {
            prep.isFailure -> prep.exceptionOrNull()!!.let { handleError(it, memberName) }
            else -> {
                val p = prep.getOrThrow()
                applyBodyReplacement(project, p, content, reformat, requestedSymbolId)
            }
        }
    }

    private fun prepareMemberEditBySemanticTarget(
        project: Project,
        arguments: JsonObject,
        symbolId: String?
    ): Result<MemberEditPreparation> {
        val rawElement = resolveElementFromArguments(project, arguments, allowSymbolId = true)
            .getOrElse { return Result.failure(it) }
        // Position selectors start at a leaf PSI token; resolve that token to the exact semantic
        // declaration. Other selector variants already return declarations directly.
        val declaration = if (
            optionalStringArg(arguments, UnifiedTargetArguments.NORMALIZED_VARIANT) == UnifiedTargetArguments.POSITION
        ) {
            PsiUtils.resolveTargetElement(rawElement) ?: rawElement
        } else {
            rawElement
        }
        val element = MemberEditingUtils.resolveEditableSourceTarget(declaration).getOrElse {
            return Result.failure(it)
        }
        val psiFile = element.containingFile
            ?: return Result.failure(
                IllegalArgumentException(symbolId?.let(ErrorMessages::symbolIdExpired) ?: "Target has no source file")
            )
        val virtualFile = psiFile.virtualFile
            ?: return Result.failure(
                IllegalArgumentException(symbolId?.let(ErrorMessages::symbolIdExpired) ?: "Target has no editable source file")
            )
        if (!virtualFile.isWritable) {
            return Result.failure(IllegalArgumentException("File is read-only and cannot be modified: ${virtualFile.path}"))
        }
        val resolver = MemberEditingUtils.getResolver(psiFile, project)
            ?: return Result.failure(
                IllegalArgumentException("Member editing not supported for ${psiFile.language.displayName}. Supported: Java, Kotlin.")
            )
        val member = resolver.resolveMember(element)
            ?: return Result.failure(IllegalArgumentException("Target does not identify an editable Java/Kotlin member"))
        if (member.kind == "class") {
            return Result.failure(
                IllegalArgumentException(
                    "Cannot replace body of a class declaration. Use ide_edit_member to replace the entire class declaration."
                )
            )
        }
        val document = MemberEditingUtils.getDocument(psiFile)
            ?: return Result.failure(IllegalArgumentException("Cannot get document for file: ${virtualFile.path}"))
        return Result.success(
            MemberEditPreparation(psiFile, document, member, ProjectUtils.getToolFilePath(project, virtualFile))
        )
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
        reformat: Boolean,
        requestedSymbolId: String?
    ): CallToolResult {
        val member = prep.member
        val pointer = suspendingReadAction {
            SmartPointerManager.getInstance(project)
                .createSmartPsiElementPointer(member.element)
                .withOriginalFileIdentity()
        }
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
        var editApplied = false
        var updatedSymbol: ResolvedSymbolInfo? = null

        try {
            val saveError = suspendingWriteActionAndSave(project, "Replace member body: ${member.name}", prep.document) {
                // Rebind after entering the write action. Offsets captured during preparation can
                // become stale if the declaration itself changes before this action starts; merely
                // shifting them by the declaration's start delta does not cover body/header edits.
                MemberEditingUtils.commitDocuments(project)
                val currentElement = pointer.element
                val currentMember = currentElement
                    ?.takeIf { it.isValid }
                    ?.let { MemberEditingUtils.getResolver(prep.psiFile, project)?.resolveMember(it) }
                if (currentMember == null) {
                    error =
                        "PSI element for '${member.name}' is no longer valid. The document may have been modified externally — retry the operation."
                    return@suspendingWriteActionAndSave
                }
                val bodyStart = currentMember.bodyStartOffset
                val bodyEnd = currentMember.bodyEndOffset
                val currentRange = currentElement.textRange
                if (bodyStart == null || bodyEnd == null) {
                    error =
                        "Member '${member.name}' no longer has a body/initializer to replace. Retry after rediscovering the declaration."
                    return@suspendingWriteActionAndSave
                }

                val docLength = prep.document.textLength
                if (bodyStart < 0 || bodyEnd > docLength || bodyStart > bodyEnd) {
                    error =
                        "Body offsets [${bodyStart}, ${bodyEnd}) are out of bounds (document length: ${docLength}). The document may have been modified externally — retry the operation."
                    return@suspendingWriteActionAndSave
                }

                prep.document.replaceString(bodyStart, bodyEnd, content)
                editApplied = true
                val editedRange = prep.document.createRangeMarker(bodyStart, bodyStart + content.length)
                try {
                    MemberEditingUtils.commitDocuments(project)
                    if (reformat) {
                        // Format the complete declaration, including its closing delimiter. A range
                        // ending at the inserted text leaves Kotlin's `return 21}` unchanged.
                        val declarationEnd = currentRange.endOffset + content.length - (bodyEnd - bodyStart)
                        MemberEditingUtils.reformatRange(project, prep.psiFile, currentRange.startOffset, declarationEnd)
                        MemberEditingUtils.commitDocuments(project)
                    }
                    // Formatting changes body length, and import optimization can move the whole
                    // declaration. Report the final PSI body, with a tracked range if PSI was lost.
                    val updatedMember = pointer.element?.let {
                        MemberEditingUtils.getResolver(prep.psiFile, project)?.resolveMember(it)
                    }
                    val finalStart = updatedMember?.bodyStartOffset
                        ?: if (editedRange.isValid) editedRange.startOffset else bodyStart
                    val finalEnd = updatedMember?.bodyEndOffset
                        ?: if (editedRange.isValid) editedRange.endOffset else bodyStart + content.length
                    startLine = MemberEditingUtils.safeLineNumber(prep.document, finalStart)
                    endLine = MemberEditingUtils.safeLineNumber(prep.document, finalEnd)
                } finally {
                    editedRange.dispose()
                }
            }

            if (error != null) {
                return createErrorResult(error!!)
            }
            saveError?.let { return it }

            updatedSymbol = suspendingReadAction {
                pointer.element?.let { resolvedSymbolInfo(project, it, requestedSymbolId) }
            }

            return createJsonResult(
                MemberEditResult(
                    success = true,
                    file = prep.relativePath,
                    message = "Replaced body of ${member.kind} '${member.name}'" + if (updatedSymbol == null) {
                        ". The symbol handle could not be restored; rediscover the edited declaration before another refactoring."
                    } else "",
                    startLine = startLine,
                    endLine = endLine,
                    updatedSymbol = updatedSymbol
                )
            )
        } finally {
            // Once the edit is visible, a failed rebind must not leave the caller's handle free
            // to resolve through a stale smart pointer or to a different PSI declaration.
            if (editApplied && updatedSymbol == null && requestedSymbolId != null) {
                SymbolIdRegistry.getInstance().invalidate(requestedSymbolId)
            }
        }
    }

    private fun handleError(error: Throwable, memberName: String): CallToolResult {
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
