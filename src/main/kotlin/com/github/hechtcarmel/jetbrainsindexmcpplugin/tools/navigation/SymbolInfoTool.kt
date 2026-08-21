package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.OptimizedSymbolSearch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolInfoResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ResolvedSignature
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiSourcePosition
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.SymbolDocumentation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.SymbolSignatureResolver
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.usageView.UsageViewUtil
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * `ide_symbol_info` — the resolved declaration at a position, without reading the file.
 *
 * `ide_find_definition` answers "where is it"; this answers "what is it". The difference is
 * resolution: a definition preview is source text, so `handle(Request r)` comes back exactly as
 * written, with `Request` ambiguous between every import in scope and no doc comment attached.
 * Here the parameter type is the one the compiler resolved.
 */
class SymbolInfoTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_MAX_DOC_LENGTH = 4000
        private const val MAX_ALLOWED_DOC_LENGTH = 20000
        private const val TRUNCATION_MARKER = "\n… (documentation truncated)"
    }

    override val name = ToolNames.SYMBOL_INFO

    override val description = """
        Get the resolved signature and documentation of the symbol at a position, without reading the file. Use before changing a call site, picking an overload, or checking what a parameter actually accepts.

        Returns: name, kind, qualified name, resolved signature, structured parameters (name + type), return type, modifiers, visibility, containing declaration, the doc comment as plain text, and the declaration's file/line/column.

        Type resolution: for Java, every parameter and return type is expanded to its fully qualified name — `java.util.List<com.example.model.Request>` for a parameter written as `List<Request>`. Other languages fall back to the signature their own Quick Documentation renders, where type names may stay short. The `signatureSource` field reports which happened: `java_psi` (fully resolved, with structured `parameters`), `quick_navigation` (IDE-rendered), or `element_text` (raw declaration line, when no documentation provider answers).

        Prefer this over ide_find_definition + reading the file when you only need the signature or the docs.

        Target (mutually exclusive):
        - file + line + column: position-based lookup, so overloads are addressable
        - language + symbol: fully qualified symbol reference (supported languages: ${supportedSymbolReferenceLanguagesDescription()})

        Example: {"file": "src/Main.java", "line": 15, "column": 10}
        Example: {"language": "Java", "symbol": "com.example.MyClass#processData(String)"}
        Example: {"file": "src/Main.java", "line": 15, "column": 10, "includeDoc": false}
        """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .file(required = false, description = "Project-relative file path, or a dependency/library absolute path or jar:// URL previously returned by the plugin. Required for position-based lookup.")
        .lineAndColumn(required = false)
        .languageAndSymbol(required = false)
        .booleanProperty(ParamNames.INCLUDE_DOC, "Include the rendered doc comment. Set false when only the signature is needed. Optional, defaults to true.")
        .intProperty(ParamNames.MAX_DOC_LENGTH, "Maximum characters of documentation to return; longer docs are truncated and documentationTruncated is set. Default: 4000, max: 20000.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val includeDoc = arguments[ParamNames.INCLUDE_DOC]?.jsonPrimitive?.booleanOrNull ?: true
        val maxDocLength = (arguments[ParamNames.MAX_DOC_LENGTH]?.jsonPrimitive?.int ?: DEFAULT_MAX_DOC_LENGTH)
            .coerceIn(1, MAX_ALLOWED_DOC_LENGTH)

        requireSmartMode(project)

        return suspendingReadAction {
            val element = resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true).getOrElse {
                return@suspendingReadAction createErrorResult(it.message ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL)
            }

            // Symbol-based resolution hands back the declaration; position-based resolution hands
            // back the leaf token under the cursor, which still has to be resolved to one.
            val resolvedElement = element as? PsiNamedElement
                ?: (PsiUtils.resolveTargetElement(element)
                    ?: return@suspendingReadAction createErrorResult(ErrorMessages.SYMBOL_NOT_RESOLVED))

            // Prefer the source declaration over a compiled stand-in, so docs and line numbers
            // come from the .java/.kt file when library sources are attached.
            val target = PsiUtils.resolveNavigationTarget(resolvedElement)

            // The leaf the caller pointed at, when that is not the declaration itself. Java's
            // documentation provider uses it to substitute type arguments, so `list.get(0)` on a
            // List<Request> reports Request rather than the type variable E.
            val originalElement = element.takeIf { it !== target }

            createJsonResult(buildResult(project, target, originalElement, includeDoc, maxDocLength))
        }
    }

    private fun buildResult(
        project: Project,
        target: PsiElement,
        originalElement: PsiElement?,
        includeDoc: Boolean,
        maxDocLength: Int
    ): SymbolInfoResult {
        val name = (target as? PsiNamedElement)?.name ?: target.text.orEmpty().take(80)
        val resolved = SymbolSignatureResolver.resolve(target, originalElement)
        val documentation = if (includeDoc) SymbolDocumentation.renderedDoc(target, originalElement) else null
        val truncatedDocumentation = documentation?.takeIf { it.length > maxDocLength }
            ?.let { it.take(maxDocLength) + TRUNCATION_MARKER }

        val virtualFile = target.containingFile?.virtualFile
        // The shared helper, not an inline getLineNumber: it rejects a negative textOffset and an
        // offset past the document end, both of which make Document.getLineNumber throw. A document
        // can legitimately run ahead of PSI here, since PSI sync is off by default.
        val position = PsiSourcePosition.position(project, target)

        return SymbolInfoResult(
            name = name,
            kind = UsageViewUtil.getType(target).takeIf { it.isNotBlank() },
            qualifiedName = qualifiedName(target, name, resolved),
            signature = resolved.signature,
            signatureSource = resolved.source,
            parameters = resolved.parameters,
            returnType = resolved.returnType,
            typeParameters = resolved.typeParameters,
            thrownTypes = resolved.thrownTypes,
            modifiers = resolved.modifiers,
            visibility = resolved.visibility,
            containingDeclaration = resolved.containingDeclaration,
            documentation = truncatedDocumentation ?: documentation,
            documentationTruncated = truncatedDocumentation != null,
            file = virtualFile?.let { getRelativePath(project, it) },
            line = position?.line,
            column = position?.column,
            language = OptimizedSymbolSearch.getLanguageName(target)
        )
    }

    /**
     * The symbol's own qualified name for a type, or `Container#member` for a member — the
     * format this plugin's `symbol` parameter accepts, so the value can be passed straight back
     * to `ide_find_references` or `ide_call_hierarchy`.
     *
     * A callable carries its parameter list, because the bare `Container#name` form is ambiguous
     * exactly where this tool is most useful: `JavaSymbolReferenceHandler` rejects it with
     * "Multiple methods match" as soon as the name is overloaded. The types are the same resolved
     * canonical names reported in `parameters`, which that resolver accepts alongside short names.
     */
    private fun qualifiedName(target: PsiElement, name: String, resolved: ResolvedSignature): String? {
        PsiUtils.qualifiedName(target)?.let { return it }
        val container = resolved.containingDeclaration ?: return null
        // parameters is populated only on the java_psi path, and only for callables — a field or a
        // non-Java declaration must not grow an empty argument list it cannot be looked up by.
        val member = resolved.parameters
            ?.joinToString(", ", "$name(", ")") { it.type }
            ?: name
        return "$container#$member"
    }
}
