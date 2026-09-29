package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.UnifiedTargetArguments
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ResolvedSymbolInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
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
        It must contain exactly one syntactically valid declaration of the original category,
        optionally surrounded by comments. Nested declarations are allowed. Invalid or ambiguous
        content is rejected before editing; use ide_refactor_safe_delete for deletion.
        Auto-reformats the changed range by default.

        Target the declaration with nested target ({symbolId}, {position: {file, line, column}},
        or {qualifiedName, language}), top-level symbolId, top-level language + symbol, or the
        legacy file + member selector. Do not combine selector forms.
        Examples:
        - {"symbolId": "<opaque-id>", "content": "public void renamed() {}"}
        - {"target": {"position": {"file": "src/Main.java", "line": 12, "column": 17}}, "content": "public void renamed() {}"}
        - {"file": "src/Main.java", "class": "Main", "member": "process", "content": "public void process(String input, boolean validate) {\n    if (validate) check(input);\n}"}
        - {"file": "src/Config.kt", "class": "Config", "member": "timeout", "content": "val timeout: Duration = Duration.ofSeconds(30)"}
        - {"file": "src/Service.java", "member": "Service", "content": "public class Service<T> implements Serializable { ... }"}
    """.trimIndent()

    override val inputSchema = SchemaBuilder.tool()
        .projectPath()
        .target()
        .symbolId()
        .languageAndSymbol(required = false)
        .file(required = false, description = "Path to file relative to project root. Required with member selectors; omit when symbolId is used.")
        .stringProperty(ParamNames.CLASS, "Class/interface name containing the member. Optional for top-level members (Kotlin).")
        .stringProperty(ParamNames.MEMBER, "Name of the method, function, field, or property to replace entirely. Required unless symbolId is used.")
        .intProperty(ParamNames.PARAMETER_COUNT, "Number of parameters (for disambiguating overloaded methods).")
        .intProperty(ParamNames.LINE, "1-based line number of the member (for disambiguation when multiple members share the same name).")
        .stringProperty(ParamNames.CONTENT, "Exactly one complete, syntactically valid declaration of the original category, including modifiers, type, name, and body. Surrounding comments are allowed.", required = true)
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
        if (content.isBlank()) {
            return createErrorResult("content must not be empty. To delete a member, use ide_refactor_safe_delete.")
        }
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
                applyFullReplacement(project, p, content, reformat, requestedSymbolId)
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
        // A position resolves initially to the leaf token under the cursor. Convert that token to
        // its semantic declaration before asking the language-specific member resolver; symbolId
        // and qualified-name selectors already resolve directly to their declaration.
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
        val document = MemberEditingUtils.getDocument(psiFile)
            ?: return Result.failure(Exception("Cannot get document for file: $filePath"))

        val relativePath = ProjectUtils.getToolFilePath(project, psiFile.virtualFile)
        return Result.success(MemberEditPreparation(psiFile, document, member, relativePath))
    }

    private suspend fun applyFullReplacement(
        project: Project,
        prep: MemberEditPreparation,
        content: String,
        reformat: Boolean,
        requestedSymbolId: String?
    ): CallToolResult {
        val member = prep.member
        var startLine = 0
        var endLine = 0
        var error: String? = null
        var updatedSymbol: ResolvedSymbolInfo? = null

        val saveError = suspendingWriteActionAndSave(project, "Edit member: ${member.name}", prep.document) {
            if (!member.element.isValid) {
                error =
                    "PSI element for '${member.name}' is no longer valid. The document may have been modified externally — retry the operation."
                return@suspendingWriteActionAndSave
            }
            val range = member.element.textRange
            val startOffset = range.startOffset
            val endOffset = range.endOffset
            val replacementRange = TextRange(startOffset, startOffset + content.length)
            val declarationType = member.element.node?.elementType
            if (declarationType == null) {
                error = "Cannot identify the declaration syntax for '${member.name}'. Rediscover the member and retry."
                return@suspendingWriteActionAndSave
            }
            val isConstructor = (member.element as? PsiMethod)?.isConstructor

            // Parse the replacement in its real syntactic context before changing the document.
            // A comment, malformed declaration, or several sibling declarations cannot identify
            // one replacement symbol. Nested declarations inside its body are still allowed.
            val previewText = prep.document.text.replaceRange(startOffset, endOffset, content)
            val previewFile = PsiFileFactory.getInstance(project)
                .createFileFromText(prep.psiFile.name, prep.psiFile.fileType, previewText)
            val preview = findReplacementDeclaration(previewFile, replacementRange, declarationType, isConstructor)
            if (preview == null) {
                error = "content must contain exactly one complete ${member.kind} declaration, optionally " +
                    "surrounded by comments. To delete a member, use ide_refactor_safe_delete."
                return@suspendingWriteActionAndSave
            }

            prep.document.replaceString(startOffset, endOffset, content)
            try {
                MemberEditingUtils.commitDocuments(project)
                // Only the exact newly committed declaration is eligible for rebinding. Never
                // reuse the old pointer or walk from its former offset to a named parent.
                val replacement = findReplacementDeclaration(
                    prep.psiFile, replacementRange, declarationType, isConstructor
                )?.takeIf { it.textRange == preview.textRange && it.text == preview.text }
                val replacementPointer = replacement?.let {
                    SmartPointerManager.getInstance(project).createSmartPsiElementPointer(it)
                }
                val editedRange = prep.document.createRangeMarker(replacementRange.startOffset, replacementRange.endOffset)
                try {
                    if (reformat) {
                        MemberEditingUtils.reformatRange(
                            project, prep.psiFile, replacementRange.startOffset, replacementRange.endOffset
                        )
                        MemberEditingUtils.commitDocuments(project)
                    }
                    val updated = replacementPointer?.element?.takeIf {
                        it.isValid && matchesDeclarationType(it, declarationType, isConstructor)
                    }
                    if (updated != null) {
                        updatedSymbol = resolvedSymbolInfo(project, updated, requestedSymbolId)
                    }
                    val finalRange = if (editedRange.isValid) {
                        TextRange(editedRange.startOffset, editedRange.endOffset)
                    } else {
                        updated?.textRange ?: replacementRange
                    }
                    startLine = MemberEditingUtils.safeLineNumber(prep.document, finalRange.startOffset)
                    endLine = MemberEditingUtils.safeLineNumber(prep.document, finalRange.endOffset)
                } finally {
                    editedRange.dispose()
                }
            } finally {
                // An applied edit can outlive pointer restoration (or formatting can fail).
                // Never leave the requested ID available to a different declaration afterward.
                if (updatedSymbol == null && requestedSymbolId != null) {
                    SymbolIdRegistry.getInstance().invalidate(requestedSymbolId)
                }
            }
        }

        if (error != null) {
            return createErrorResult(error!!)
        }
        saveError?.let {
            // The handle was rebound to a declaration that never reached disk.
            requestedSymbolId?.let { id -> SymbolIdRegistry.getInstance().invalidate(id) }
            return it
        }

        return createJsonResult(
            MemberEditResult(
                success = true,
                file = prep.relativePath,
                message = "Replaced ${member.kind} '${member.name}' entirely" + if (updatedSymbol == null) {
                    ". The symbol handle could not be restored; rediscover the edited declaration before another refactoring."
                } else "",
                startLine = startLine,
                endLine = endLine,
                updatedSymbol = updatedSymbol
            )
        )
    }

    private fun findReplacementDeclaration(
        file: PsiFile,
        range: TextRange,
        declarationType: IElementType,
        isConstructor: Boolean?
    ): PsiElement? {
        val firstTokenOffset = skipTrivia(file, range.startOffset, range.endOffset)
        if (firstTokenOffset >= range.endOffset) return null
        val leaf = file.findElementAt(firstTokenOffset) ?: return null
        val declaration = generateSequence(leaf) { it.parent }
            .takeWhile { it !is PsiFile && range.contains(it.textRange) }
            .lastOrNull { matchesDeclarationType(it, declarationType, isConstructor) }
            ?: return null
        if (skipTrivia(file, range.startOffset, declaration.textRange.startOffset) != declaration.textRange.startOffset ||
            skipTrivia(file, declaration.textRange.endOffset, range.endOffset) != range.endOffset ||
            PsiTreeUtil.findChildOfType(declaration, PsiErrorElement::class.java) != null
        ) {
            return null
        }
        return declaration
    }

    private fun matchesDeclarationType(
        element: PsiElement,
        declarationType: IElementType,
        isConstructor: Boolean?
    ): Boolean = element.node?.elementType == declarationType &&
        (isConstructor == null || (element as? PsiMethod)?.isConstructor == isConstructor)

    private fun skipTrivia(file: PsiFile, startOffset: Int, endOffset: Int): Int {
        var offset = startOffset
        while (offset < endOffset) {
            val element = file.findElementAt(offset) ?: break
            val comment = PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false)
            offset = when {
                element is PsiWhiteSpace -> minOf(element.textRange.endOffset, endOffset)
                comment != null && comment.textRange.startOffset >= startOffset &&
                    comment.textRange.endOffset <= endOffset -> comment.textRange.endOffset
                else -> break
            }
        }
        return offset
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
