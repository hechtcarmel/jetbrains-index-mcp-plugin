package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.toArgumentFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

/**
 * Registration entry point for JavaScript/TypeScript language handlers.
 *
 * This class is loaded via reflection when the JavaScript plugin is available.
 * It registers all JavaScript/TypeScript-specific handlers with the [LanguageHandlerRegistry].
 *
 * ## JavaScript PSI Classes Used (via reflection)
 *
 * - `com.intellij.lang.javascript.psi.JSClass` - JS/TS class declarations (ES6+)
 * - `com.intellij.lang.javascript.psi.JSFunction` - Function/method declarations
 * - `com.intellij.lang.javascript.psi.ecmal4.JSClass` - TypeScript class declarations
 * - `com.intellij.lang.javascript.psi.JSCallExpression` - Function/method calls
 *
 * ## Supported Languages
 *
 * - JavaScript (ES5, ES6+)
 * - TypeScript
 * - JSX / TSX
 * - Flow
 */
object JavaScriptHandlers {

    private val LOG = logger<JavaScriptHandlers>()

    /**
     * Registers all JavaScript/TypeScript handlers with the registry.
     *
     * Called via reflection from [LanguageHandlerRegistry].
     */
    @JvmStatic
    fun register(registry: LanguageHandlerRegistry) {
        if (!PluginDetectors.javaScript.isAvailable) {
            LOG.info("JavaScript plugin not available, skipping JavaScript handler registration")
            return
        }

        try {
            // Verify JavaScript classes are accessible before registering
            Class.forName("com.intellij.lang.javascript.psi.JSFunction")

            registry.registerTypeHierarchyHandler(JavaScriptTypeHierarchyHandler())
            registry.registerImplementationsHandler(JavaScriptImplementationsHandler())
            registry.registerCallHierarchyHandler(JavaScriptCallHierarchyHandler())
            registry.registerSuperMethodsHandler(JavaScriptSuperMethodsHandler())
            registry.registerStructureHandler(JavaScriptStructureHandler())
            registry.registerSymbolReferenceHandler(JavaScriptSymbolReferenceHandler())

            // Also register for TypeScript (uses same handlers)
            registry.registerTypeHierarchyHandler(TypeScriptTypeHierarchyHandler())
            registry.registerImplementationsHandler(TypeScriptImplementationsHandler())
            registry.registerCallHierarchyHandler(TypeScriptCallHierarchyHandler())
            registry.registerSuperMethodsHandler(TypeScriptSuperMethodsHandler())
            registry.registerStructureHandler(TypeScriptStructureHandler())
            registry.registerSymbolReferenceHandler(TypeScriptSymbolReferenceHandler())

            LOG.info("Registered JavaScript and TypeScript handlers")
        } catch (e: ClassNotFoundException) {
            LOG.warn("JavaScript PSI classes not found, skipping registration: ${e.message}")
        } catch (e: Exception) {
            LOG.warn("Failed to register JavaScript handlers: ${e.message}")
        }
    }
}

internal sealed interface JsTsSymbolTarget {
    val modulePath: String

    data class NamedExport(
        override val modulePath: String,
        val exportName: String
    ) : JsTsSymbolTarget

    data class DefaultExport(
        override val modulePath: String
    ) : JsTsSymbolTarget

    data class ClassMember(
        override val modulePath: String,
        val className: String,
        val memberName: String
    ) : JsTsSymbolTarget
}

private val JS_TS_IDENTIFIER_REGEX = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
private val JS_TS_ALLOWED_EXTENSIONS = listOf("ts", "tsx", "js", "jsx", "mjs", "cjs")
private val JS_TS_ALLOWED_EXTENSIONS_SET = JS_TS_ALLOWED_EXTENSIONS.toSet()

internal fun parseJsTsSymbolTarget(symbol: String): Result<JsTsSymbolTarget> {
    if (symbol.isBlank() || symbol != symbol.trim()) {
        return ErrorMessages.jsTsUnsupportedGrammar(symbol).toArgumentFailure()
    }

    val hashIndex = symbol.indexOf('#')
    if (hashIndex <= 0 || hashIndex != symbol.lastIndexOf('#') || hashIndex == symbol.lastIndex) {
        return ErrorMessages.jsTsUnsupportedGrammar(symbol).toArgumentFailure()
    }

    val modulePath = symbol.substring(0, hashIndex)
    val target = symbol.substring(hashIndex + 1)

    if (!isValidModulePath(modulePath)) {
        return ErrorMessages.jsTsUnsupportedGrammar(symbol).toArgumentFailure()
    }

    if (target == "default") {
        return Result.success(JsTsSymbolTarget.DefaultExport(modulePath))
    }

    if (!target.contains('.')) {
        return if (isValidJsTsIdentifier(target)) {
            Result.success(JsTsSymbolTarget.NamedExport(modulePath, target))
        } else {
            ErrorMessages.jsTsUnsupportedGrammar(symbol).toArgumentFailure()
        }
    }

    val dotIndex = target.indexOf('.')
    if (dotIndex <= 0 || dotIndex != target.lastIndexOf('.') || dotIndex == target.lastIndex) {
        return ErrorMessages.jsTsUnsupportedGrammar(symbol).toArgumentFailure()
    }

    val className = target.substring(0, dotIndex)
    val memberName = target.substring(dotIndex + 1)
    if (!isValidJsTsIdentifier(className) || !isValidJsTsIdentifier(memberName)) {
        return ErrorMessages.jsTsUnsupportedGrammar(symbol).toArgumentFailure()
    }

    return Result.success(JsTsSymbolTarget.ClassMember(modulePath, className, memberName))
}

private fun isValidModulePath(modulePath: String): Boolean {
    if (modulePath.isBlank()) return false
    if (modulePath.contains('#')) return false
    return modulePath.none { it.isWhitespace() }
}

private fun isValidJsTsIdentifier(value: String): Boolean = JS_TS_IDENTIFIER_REGEX.matches(value)

internal data class JsTsModuleCandidate(
    val relativePath: String,
    val isIndexCandidate: Boolean
)

internal fun expandJsTsModuleCandidates(modulePath: String): List<JsTsModuleCandidate> {
    val normalized = modulePath.replace('\\', '/').trim('/').removeSuffix("/")
    if (normalized.isBlank()) return emptyList()

    val lastSegment = normalized.substringAfterLast('/')
    val explicitExt = lastSegment.substringAfterLast('.', missingDelimiterValue = "")
    if (explicitExt.isNotBlank() && explicitExt in JS_TS_ALLOWED_EXTENSIONS_SET) {
        return listOf(JsTsModuleCandidate(normalized, isIndexCandidate = false))
    }

    val directFiles = JS_TS_ALLOWED_EXTENSIONS.map { ext ->
        JsTsModuleCandidate("$normalized.$ext", isIndexCandidate = false)
    }
    val indexFiles = JS_TS_ALLOWED_EXTENSIONS.map { ext ->
        JsTsModuleCandidate("$normalized/index.$ext", isIndexCandidate = true)
    }
    return directFiles + indexFiles
}

/**
 * Applies Node.js module-resolution precedence: when the same export name is found in both
 * a direct file (`foo.ts`) and an index file (`foo/index.ts`), discard the index candidates.
 *
 * This mirrors Node.js module resolution: `require('./foo')` resolves `foo.ts` before
 * `foo/index.ts`. Without this filter, [deterministicSingleMatchOrFailure] would return
 * `ambiguous_match` — a false error that blocks agents unnecessarily.
 *
 * The filter is a no-op when only index candidates exist (fallback path is preserved).
 * Genuine ambiguity between two non-index files is also preserved (the filter only removes
 * index candidates when at least one non-index candidate matched).
 *
 * @param moduleCandidates The full list of module candidates from [expandJsTsModuleCandidates]
 *   used to distinguish direct vs. index paths.
 */
internal fun List<Pair<String, PsiNamedElement>>.applyDirectFilePrecedence(
    moduleCandidates: List<JsTsModuleCandidate>
): List<Pair<String, PsiNamedElement>> {
    if (isEmpty()) return this
    val directPaths = moduleCandidates.filter { !it.isIndexCandidate }.map { it.relativePath }.toSet()
    val indexPaths = moduleCandidates.filter { it.isIndexCandidate }.map { it.relativePath }.toSet()
    // A candidate key is "$relativePath#exportName". Check if the path portion of the key
    // matches a direct or index candidate path.
    val hasDirectMatch = any { (key, _) -> directPaths.any { directPath -> key.startsWith("$directPath#") } }
    return if (hasDirectMatch) {
        filter { (key, _) -> !indexPaths.any { indexPath -> key.startsWith("$indexPath#") } }
    } else {
        this
    }
}

internal fun deterministicSingleMatchOrFailure(
    symbol: String,
    candidates: List<Pair<String, PsiNamedElement>>
): Result<PsiNamedElement> {
    return when (candidates.size) {
        0 -> ErrorMessages.jsTsNotFound(symbol).toArgumentFailure()
        1 -> Result.success(candidates.first().second)
        else -> ErrorMessages.jsTsAmbiguousMatch(symbol, candidates.map { it.first }).toArgumentFailure()
    }
}

internal fun resolveJsTsSearchRoots(basePath: String?, moduleRoots: List<String>): List<String> {
    return (listOfNotNull(basePath) + moduleRoots).distinct()
}

private sealed interface OverloadedExportResolution {
    data class PreferImplementation(val implementation: PsiNamedElement) : OverloadedExportResolution
    data object NoSupportedImplementation : OverloadedExportResolution
    data object NotOverloadOrAmbiguous : OverloadedExportResolution
}

private fun selectOverloadedNamedExport(matches: List<PsiNamedElement>): OverloadedExportResolution {
    if (matches.isEmpty() || matches.any { !isFunctionLike(it) }) {
        return OverloadedExportResolution.NotOverloadOrAmbiguous
    }

    val implementations = matches.filter { hasFunctionImplementation(it) }
    return when (implementations.size) {
        1 -> OverloadedExportResolution.PreferImplementation(implementations.single())
        0 -> OverloadedExportResolution.NoSupportedImplementation
        else -> OverloadedExportResolution.NotOverloadOrAmbiguous
    }
}

private fun normalizeOverloadedNamedExportCandidates(
    symbol: String,
    candidates: List<Pair<String, PsiNamedElement>>
): Result<List<Pair<String, PsiNamedElement>>> {
    if (candidates.isEmpty()) {
        return Result.success(emptyList())
    }

    val normalized = mutableListOf<Pair<String, PsiNamedElement>>()
    candidates
        .groupBy { it.first }
        .toSortedMap()
        .values
        .forEach { groupedCandidates ->
            if (groupedCandidates.size == 1) {
                normalized += groupedCandidates.single()
                return@forEach
            }

            when (val overloadSelection = selectOverloadedNamedExport(groupedCandidates.map { it.second })) {
                is OverloadedExportResolution.PreferImplementation -> {
                    normalized += groupedCandidates.first().first to overloadSelection.implementation
                }

                OverloadedExportResolution.NoSupportedImplementation -> Unit
                OverloadedExportResolution.NotOverloadOrAmbiguous -> {
                    return Result.failure(
                        IllegalArgumentException(
                            ErrorMessages.jsTsAmbiguousMatch(symbol, groupedCandidates.map { it.first })
                        )
                    )
                }
            }
        }

    return Result.success(normalized)
}

internal fun isJsTsElementOrFile(element: PsiElement): Boolean {
    val elementLanguageId = element.language.id
    if (elementLanguageId in setOf("JavaScript", "TypeScript", "ECMAScript 6", "JSX Harmony", "TypeScript JSX")) {
        return true
    }

    val containingFile = element.containingFile ?: return false
    val fileLanguageId = containingFile.language.id
    if (fileLanguageId in setOf("JavaScript", "TypeScript", "ECMAScript 6", "JSX Harmony", "TypeScript JSX")) {
        return true
    }

    val extension = containingFile.virtualFile?.extension?.lowercase()
    return extension in JS_TS_ALLOWED_EXTENSIONS_SET
}

private fun findSameFileFunctionLikeCandidates(file: PsiFile, name: String): List<PsiNamedElement> {
    val matches = mutableListOf<PsiNamedElement>()
    PsiTreeUtil.processElements(file) { candidate ->
        val named = candidate as? PsiNamedElement
        if (named != null && named.name == name && isFunctionLike(named)) {
            matches.add(named)
        }
        true
    }
    return matches
}

internal fun resolveJsTsCallHierarchySeed(element: PsiElement): PsiElement {
    val namedElement = sequence<PsiElement> {
        var current: PsiElement? = element
        while (current != null) {
            yield(current)
            current = current.parent
        }
    }
        .filterIsInstance<PsiNamedElement>()
        .firstOrNull(::isFunctionLike)
        ?: return element

    if (hasFunctionImplementation(namedElement)) {
        return namedElement
    }

    val exportName = namedElement.name ?: return namedElement
    val psiFile = namedElement.containingFile ?: return namedElement
    val matches = findSameFileFunctionLikeCandidates(psiFile, exportName)
        .ifEmpty {
            mutableListOf<PsiNamedElement>().apply {
                PsiTreeUtil.processElements(psiFile) { candidate ->
                    val named = candidate as? PsiNamedElement
                    if (named != null && named.name == exportName && isExportCandidate(named)) {
                        add(named)
                    }
                    true
                }
            }
        }

    return when (val overloadSelection = selectOverloadedNamedExport(matches)) {
        is OverloadedExportResolution.PreferImplementation -> overloadSelection.implementation
        OverloadedExportResolution.NoSupportedImplementation,
        OverloadedExportResolution.NotOverloadOrAmbiguous -> namedElement
    }
}

private fun isExportCandidate(named: PsiNamedElement): Boolean {
    val cls = named.javaClass
    val className = cls.name
    if (className.contains("JSImportSpecifier") || className.contains("ES6Export")) return true
    return callBooleanMethod(named, "isExported") == true || callBooleanMethod(named, "isExport") == true
}

private fun isFunctionLike(named: PsiNamedElement): Boolean {
    val className = named.javaClass.name
    if (className.contains("JSFunction")) return true
    return callBooleanMethod(named, "isFunction") == true
}

private fun hasFunctionImplementation(named: PsiNamedElement): Boolean {
    val source = named.text?.trim().orEmpty()
    val textHasImplementation = hasImplementationBodyByText(source)
    if (!textHasImplementation && source.endsWith(";")) {
        return false
    }

    for (methodName in listOf(
        "getBody",
        "getBlock",
        "getFunctionBody",
        "getBodyBlock",
        "getStatementBlock",
        "getBlockStatement",
        "getStatementsBlock"
    )) {
        try {
            val method = named.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: continue
            if (containsImplementationBody(method.invoke(named))) {
                return true
            }
        } catch (_: Exception) {
            // Keep probing other body-accessor variants exposed by different JS/TS PSI implementations.
        }
    }
    if (callBooleanMethod(named, "hasBody") == true || callBooleanMethod(named, "hasBlockBody") == true) {
        return true
    }
    return textHasImplementation
}

private fun containsImplementationBody(value: Any?): Boolean {
    return when (value) {
        null -> false
        is PsiElement -> !value.text.isNullOrBlank()
        is Array<*> -> value.any(::containsImplementationBody)
        is Iterable<*> -> value.any(::containsImplementationBody)
        else -> false
    }
}

internal fun hasImplementationBodyByText(text: String?): Boolean {
    val source = text?.trim().orEmpty()
    if (source.isBlank()) return false
    // TypeScript overload signatures can contain top-level braces inside object-literal return types,
    // e.g. `function f(): { id: string };`. A trailing semicolon marks the declaration as body-less
    // unless this is an arrow function with an expression or block body.
    if (source.endsWith(";") && !hasTopLevelArrowImplementationBody(source)) {
        return false
    }

    var parenDepth = 0
    var braceDepth = 0
    var bracketDepth = 0
    var inSingleQuote = false
    var inDoubleQuote = false
    var inTemplate = false
    var escaped = false

    for (char in source) {
        if (escaped) {
            escaped = false
            continue
        }

        when {
            inSingleQuote -> {
                when (char) {
                    '\\' -> escaped = true
                    '\'' -> inSingleQuote = false
                }
                continue
            }

            inDoubleQuote -> {
                when (char) {
                    '\\' -> escaped = true
                    '"' -> inDoubleQuote = false
                }
                continue
            }

            inTemplate -> {
                when (char) {
                    '\\' -> escaped = true
                    '`' -> inTemplate = false
                }
                continue
            }
        }

        when (char) {
            '\'' -> inSingleQuote = true
            '"' -> inDoubleQuote = true
            '`' -> inTemplate = true
            '(' -> parenDepth++
            ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
            '[' -> bracketDepth++
            ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
            '{' -> {
                if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    return true
                }
                braceDepth++
            }

            '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            ';' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return false
        }
    }

    return source.endsWith("}") && !source.endsWith(";")
}

private fun hasTopLevelArrowImplementationBody(text: String): Boolean {
    var parenDepth = 0
    var braceDepth = 0
    var bracketDepth = 0
    var inSingleQuote = false
    var inDoubleQuote = false
    var inTemplate = false
    var escaped = false

    var index = 0
    while (index < text.length) {
        val char = text[index]

        if (escaped) {
            escaped = false
            index++
            continue
        }

        when {
            inSingleQuote -> {
                when (char) {
                    '\\' -> escaped = true
                    '\'' -> inSingleQuote = false
                }
                index++
                continue
            }

            inDoubleQuote -> {
                when (char) {
                    '\\' -> escaped = true
                    '"' -> inDoubleQuote = false
                }
                index++
                continue
            }

            inTemplate -> {
                when (char) {
                    '\\' -> escaped = true
                    '`' -> inTemplate = false
                }
                index++
                continue
            }
        }

        when (char) {
            '\'' -> inSingleQuote = true
            '"' -> inDoubleQuote = true
            '`' -> inTemplate = true
            '(' -> parenDepth++
            ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
            '[' -> bracketDepth++
            ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
            '{' -> braceDepth++
            '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
            '=' -> {
                val isTopLevelArrow =
                    parenDepth == 0 && braceDepth == 0 && bracketDepth == 0 && index + 1 < text.length && text[index + 1] == '>'
                if (isTopLevelArrow) {
                    val bodyStart = text.indexOfFirstNonWhitespace(index + 2)
                    if (bodyStart == -1) return false
                    return text[bodyStart] != ';'
                }
            }
        }

        index++
    }

    return false
}

private fun String.indexOfFirstNonWhitespace(startIndex: Int): Int {
    var index = startIndex
    while (index < length) {
        if (!this[index].isWhitespace()) return index
        index++
    }
    return -1
}

private fun callBooleanMethod(target: Any, methodName: String): Boolean? {
    return try {
        val m = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: return null
        m.invoke(target) as? Boolean
    } catch (_: Exception) {
        null
    }
}

private fun invokeNoArgMethod(target: Any, methodName: String): Any? {
    return try {
        val m = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: return null
        m.invoke(target)
    } catch (_: Exception) { null }
}

private fun isJavaScriptOrTypeScriptTypeAliasClass(type: Class<*>?): Boolean {
    if (type == null) return false
    if (type.name.contains("TypeScriptTypeAlias") || type.simpleName.contains("TypeScriptTypeAlias")) {
        return true
    }
    return type.interfaces.any(::isJavaScriptOrTypeScriptTypeAliasClass) || isJavaScriptOrTypeScriptTypeAliasClass(type.superclass)
}


private fun hasTypeAliasNodeType(element: PsiElement): Boolean {
    val nodeType = element.node?.elementType?.toString()?.uppercase() ?: return false
    return nodeType.contains("TYPE_ALIAS")
}

class JavaScriptSymbolReferenceHandler(
    private val classLookup: (String) -> Class<*>? = { fqcn ->
        try {
            Class.forName(fqcn)
        } catch (_: ClassNotFoundException) {
            null
        }
    }
) : BaseJavaScriptHandler<PsiNamedElement>(), SymbolReferenceHandler {

    override val languageId = "JavaScript"
    override val languageName = "JavaScript"

    override fun canHandle(element: PsiElement): Boolean = isAvailable() && isJavaScriptLanguage(element)

    override fun isAvailable(): Boolean = PluginDetectors.javaScript.isAvailable

    override fun resolveSymbol(project: Project, symbol: String): Result<PsiNamedElement> {
        val target = parseJsTsSymbolTarget(symbol).getOrElse { return Result.failure(it) }
        ensureCapabilityAvailable()?.let { return Result.failure(it) }

        val psiManager = PsiManager.getInstance(project)
        val roots = resolveJsTsSearchRoots(project.basePath, ProjectUtils.getModuleContentRoots(project))
        val candidates = mutableListOf<Pair<String, PsiNamedElement>>()
        val moduleCandidates = expandJsTsModuleCandidates(target.modulePath)

        for (moduleCandidate in moduleCandidates) {
            candidates += resolveNamedElementsFromCandidate(project, psiManager, roots, moduleCandidate, target)
        }

        // Apply Node.js module-resolution precedence: direct file (foo.ts) wins over index
        // file (foo/index.ts) when both export the same name. This prevents false
        // ambiguous_match errors in the common pattern of foo.ts + foo/index.ts coexisting.
        val filteredCandidates = candidates.applyDirectFilePrecedence(moduleCandidates)

        val normalizedCandidates = normalizeOverloadedNamedExportCandidates(symbol, filteredCandidates).getOrElse {
            return Result.failure(it)
        }

        return deterministicSingleMatchOrFailure(symbol, normalizedCandidates)
    }

    private fun ensureCapabilityAvailable(): IllegalArgumentException? {
        val hasPsiCore = classLookup("com.intellij.lang.javascript.psi.JSNamedElement") != null ||
            classLookup("com.intellij.lang.javascript.psi.JSFile") != null
        return if (hasPsiCore) null
        else IllegalArgumentException(ErrorMessages.jsTsUnsupportedLanguageCapability("JavaScript PSI classes are unavailable"))
    }

    private fun resolveNamedElementsFromCandidate(
        project: Project,
        psiManager: PsiManager,
        roots: List<String>,
        moduleCandidate: JsTsModuleCandidate,
        target: JsTsSymbolTarget
    ): List<Pair<String, PsiNamedElement>> {
        val virtualFile = resolveVirtualFile(roots, moduleCandidate.relativePath) ?: return emptyList()
        if (!ProjectUtils.isAccessibleFile(project, virtualFile)) return emptyList()
        val psiFile = psiManager.findFile(virtualFile) ?: return emptyList()
        val matches = when (target) {
            is JsTsSymbolTarget.NamedExport -> findNamedExports(psiFile, target.exportName)
            is JsTsSymbolTarget.DefaultExport -> listOfNotNull(findDefaultExport(psiFile))
            is JsTsSymbolTarget.ClassMember -> listOfNotNull(findClassMember(psiFile, target.className, target.memberName))
        }
        return matches.map { buildCandidateKey(moduleCandidate.relativePath, target, it) to it }
    }

    private fun resolveVirtualFile(roots: List<String>, relativePath: String): com.intellij.openapi.vfs.VirtualFile? {
        val fs = LocalFileSystem.getInstance()
        for (root in roots) {
            val fullPath = "$root/$relativePath"
            val found = fs.findFileByPath(fullPath)
            if (found != null) return found
        }
        return null
    }

    private fun findNamedExports(file: PsiFile, exportName: String): List<PsiNamedElement> {
        val matches = mutableListOf<PsiNamedElement>()
        PsiTreeUtil.processElements(file) { element ->
            val named = element as? PsiNamedElement
            if (named != null && named.name == exportName && isExportCandidate(named)) {
                matches.add(named)
            }
            true
        }
        if (matches.size <= 1) {
            return matches
        }

        // TypeScript overloads produce multiple exported declarations with the same name.
        // When exactly one declaration has an implementation body, prefer that concrete
        // declaration instead of surfacing the declaration-only overload signatures.
        when (val overloadSelection = selectOverloadedNamedExport(matches)) {
            is OverloadedExportResolution.PreferImplementation -> return listOf(overloadSelection.implementation)
            OverloadedExportResolution.NoSupportedImplementation -> return emptyList()
            OverloadedExportResolution.NotOverloadOrAmbiguous -> Unit
        }

        return matches
    }

    private fun buildCandidateKey(
        relativePath: String,
        target: JsTsSymbolTarget,
        resolved: PsiNamedElement
    ): String {
        return when (target) {
            is JsTsSymbolTarget.NamedExport -> "$relativePath#${resolved.name ?: target.exportName}"
            is JsTsSymbolTarget.DefaultExport -> "$relativePath#default"
            is JsTsSymbolTarget.ClassMember -> "$relativePath#${target.className}.${resolved.name ?: target.memberName}"
        }
    }

    private fun findDefaultExport(file: PsiFile): PsiNamedElement? {
        val matches = mutableListOf<PsiNamedElement>()
        PsiTreeUtil.processElements(file) { element ->
            val named = element as? PsiNamedElement
            if (named != null && isDefaultExportCandidate(named)) {
                matches.add(named)
            }
            true
        }
        return matches.singleOrNull()
    }

    private fun findClassMember(file: PsiFile, className: String, memberName: String): PsiNamedElement? {
        val classCandidates = findClassCandidates(file, className)
        if (classCandidates.size != 1) return null
        val classElement = classCandidates.single()

        findMethodInClass(classElement, memberName)?.let { method ->
            return method as? PsiNamedElement
        }

        val memberMatches = mutableListOf<PsiNamedElement>()
        PsiTreeUtil.processElements(classElement) { element ->
            val named = element as? PsiNamedElement
            if (named != null && named.name == memberName && isClassMemberLike(named)) {
                memberMatches.add(named)
            }
            true
        }
        return memberMatches.singleOrNull()
    }

    private fun findClassCandidates(file: PsiFile, className: String): List<PsiElement> {
        val candidates = mutableListOf<PsiElement>()
        val seen = mutableSetOf<PsiElement>()
        fun addCandidate(candidate: PsiElement) {
            if (seen.add(candidate)) {
                candidates.add(candidate)
            }
        }

        PsiTreeUtil.processElements(file) { element ->
            val named = element as? PsiNamedElement
            if (named != null && named.name == className && isClassLike(named)) {
                addCandidate(named)
            } else if (named != null && named.name == className) {
                val containingClass = findContainingJSClass(named)
                if (containingClass != null && getName(containingClass) == className) {
                    addCandidate(containingClass)
                }
            }
            true
        }
        if (candidates.isNotEmpty()) return candidates

        PsiTreeUtil.processElements(file) { element ->
            val named = element as? PsiNamedElement
            if (named != null && named.name == className && isClassOrInterfaceDeclaration(named)) {
                addCandidate(named)
            }
            true
        }
        return candidates
    }

    private fun isDefaultExportCandidate(named: PsiNamedElement): Boolean {
        val className = named.javaClass.name
        if (className.contains("ES6ExportDefaultAssignment") || className.contains("ES6ExportDefaultDeclaration")) {
            return true
        }
        return callBooleanMethod(named, "isDefaultExport") == true
    }

    private fun isClassLike(named: PsiNamedElement): Boolean {
        val className = named.javaClass.name
        if (className.contains("JSClass")) return true
        if (isJSClass(named)) return true
        return callBooleanMethod(named, "isClass") == true
    }

    private fun isClassOrInterfaceDeclaration(named: PsiNamedElement): Boolean {
        if (isClassLike(named)) return true
        val className = named.javaClass.name
        return className.contains("TypeScriptClass") ||
            className.contains("TypeScriptInterface") ||
            callBooleanMethod(named, "isInterface") == true
    }

    private fun isClassMemberLike(named: PsiNamedElement): Boolean {
        val className = named.javaClass.name
        if (className.contains("JSFunction") || className.contains("JSField") || className.contains("TypeScriptField")) {
            return true
        }
        return callBooleanMethod(named, "isFunction") == true || callBooleanMethod(named, "isField") == true
    }

}

class TypeScriptSymbolReferenceHandler : SymbolReferenceHandler by JavaScriptSymbolReferenceHandler() {
    override val languageId = "TypeScript"
    override val languageName = "TypeScript"
}

/**
 * Base class for JavaScript/TypeScript handlers with common utilities.
 *
 * Uses reflection to access JavaScript PSI classes to avoid compile-time dependencies.
 */
abstract class BaseJavaScriptHandler<T> : LanguageHandler<T> {

    protected val LOG = logger<BaseJavaScriptHandler<*>>()

    /**
     * Checks if the element is from a JavaScript/TypeScript language.
     */
    protected fun isJavaScriptLanguage(element: PsiElement): Boolean {
        val langId = element.language.id
        return langId == "JavaScript" || langId == "TypeScript" ||
            langId == "ECMAScript 6" || langId == "JSX Harmony" ||
            langId == "TypeScript JSX"
    }

    protected val jsClassClass: Class<*>? by lazy {
        try {
            // Try ES6 class first (com.intellij.lang.javascript.psi.ecmal4.JSClass)
            Class.forName("com.intellij.lang.javascript.psi.ecmal4.JSClass")
        } catch (_: ClassNotFoundException) {
            try {
                Class.forName("com.intellij.lang.javascript.psi.JSClass")
            } catch (_: ClassNotFoundException) {
                LOG.debug("JSClass not found")
                null
            }
        }
    }

    protected val jsFunctionClass: Class<*>? by lazy {
        try {
            Class.forName("com.intellij.lang.javascript.psi.JSFunction")
        } catch (_: ClassNotFoundException) {
            LOG.debug("JSFunction not found")
            null
        }
    }

    protected val jsCallExpressionClass: Class<*>? by lazy {
        try {
            Class.forName("com.intellij.lang.javascript.psi.JSCallExpression")
        } catch (_: ClassNotFoundException) {
            LOG.debug("JSCallExpression not found")
            null
        }
    }

    protected val jsVariableClass: Class<*>? by lazy {
        try {
            Class.forName("com.intellij.lang.javascript.psi.JSVariable")
        } catch (_: ClassNotFoundException) {
            LOG.debug("JSVariable not found")
            null
        }
    }

    protected fun getRelativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String {
        return ProjectUtils.getToolFilePath(project, file)
    }

    protected fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    protected fun getColumnNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val lineNumber = document.getLineNumber(element.textOffset)
        return element.textOffset - document.getLineStartOffset(lineNumber) + 1
    }

    /**
     * Detects the language name from element.
     */
    protected fun getLanguageName(element: PsiElement): String {
        return when (element.language.id) {
            "TypeScript" -> "TypeScript"
            "TypeScript JSX" -> "TypeScript"
            "JavaScript" -> "JavaScript"
            "ECMAScript 6" -> "JavaScript"
            "JSX Harmony" -> "JavaScript"
            else -> "JavaScript"
        }
    }

    /**
     * Checks if element is a JSClass using reflection.
     */
    protected fun isJSClass(element: PsiElement): Boolean {
        return jsClassClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a JSFunction using reflection.
     */
    protected fun isJSFunction(element: PsiElement): Boolean {
        return jsFunctionClass?.isInstance(element) == true
    }

    /**
     * Finds containing JSClass using reflection.
     */
    protected fun findContainingJSClass(element: PsiElement): PsiElement? {
        if (isJSClass(element)) return element
        val jsClass = jsClassClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, jsClass as Class<out PsiElement>)
    }

    /**
     * Finds containing JSFunction using reflection.
     */
    protected fun findContainingJSFunction(element: PsiElement): PsiElement? {
        if (isJSFunction(element)) return element
        val jsFunction = jsFunctionClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, jsFunction as Class<out PsiElement>)
    }

    /**
     * Gets the name of a JS element via reflection.
     */
    protected fun getName(element: PsiElement): String? {
        return try {
            val method = element.javaClass.getMethod("getName")
            method.invoke(element) as? String
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Gets the qualified name of a JSClass via reflection.
     */
    protected fun getQualifiedName(element: PsiElement): String? {
        return try {
            val method = element.javaClass.getMethod("getQualifiedName")
            method.invoke(element) as? String
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Gets the kind of a JS class (class, interface, etc.)
     */
    protected fun getClassKind(jsClass: PsiElement): String {
        return try {
            val isInterfaceMethod = jsClass.javaClass.getMethod("isInterface")
            val isInterface = isInterfaceMethod.invoke(jsClass) as? Boolean ?: false
            if (isInterface) "INTERFACE" else "CLASS"
        } catch (_: Exception) {
            "CLASS"
        }
    }

    /**
     * Detects TypeScript `type` aliases, which can surface through the JSClass PSI path.
     */
    protected fun isTypeScriptTypeAlias(element: PsiElement): Boolean {
        if (callBooleanMethod(element, "isTypeAlias") == true) return true
        if (isJavaScriptOrTypeScriptTypeAliasClass(element.javaClass)) return true
        if (hasTypeAliasNodeType(element)) return true
        return false
    }

    /**
     * Gets superclasses/interfaces of a JSClass via reflection.
     */
    protected fun getSuperClasses(jsClass: PsiElement): Array<*>? {
        return try {
            val method = jsClass.javaClass.getMethod("getSuperClasses")
            method.invoke(jsClass) as? Array<*>
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Gets implemented interfaces of a JSClass via reflection.
     */
    protected fun getImplementedInterfaces(jsClass: PsiElement): Array<*>? {
        return try {
            val method = jsClass.javaClass.getMethod("getImplementedInterfaces")
            method.invoke(jsClass) as? Array<*>
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Finds a method in a JS class by name.
     */
    protected fun findMethodInClass(jsClass: PsiElement, methodName: String): PsiElement? {
        return try {
            val findFunctionMethod = jsClass.javaClass.getMethod("findFunctionByName", String::class.java)
            findFunctionMethod.invoke(jsClass, methodName) as? PsiElement
        } catch (_: Exception) {
            try {
                val getFunctionsMethod = jsClass.javaClass.getMethod("getFunctions")
                val functions = getFunctionsMethod.invoke(jsClass) as? Array<*> ?: return null
                functions.filterIsInstance<PsiElement>().find { getName(it) == methodName }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * JavaScript implementation of [TypeHierarchyHandler].
 */
class JavaScriptTypeHierarchyHandler : BaseJavaScriptHandler<TypeHierarchyData>(), TypeHierarchyHandler {

    companion object {
        private const val MAX_HIERARCHY_DEPTH = 50
    }

    override val languageId = "JavaScript"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isJavaScriptLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.javaScript.isAvailable && jsFunctionClass != null

    override fun getTypeHierarchy(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope
    ): TypeHierarchyData? {
        val jsClass = findContainingJSClass(element) ?: return null
        LOG.debug("Getting type hierarchy for JS class: ${getName(jsClass)}")
        val searchScope = createNavigationSearchScope(project, scope)

        val supertypes = getSupertypes(project, jsClass, searchScope = searchScope)
        val subtypes = getSubtypes(project, jsClass, searchScope)

        LOG.debug("Found ${supertypes.size} supertypes and ${subtypes.size} subtypes")

        return TypeHierarchyData(
            element = TypeElementData(
                name = getQualifiedName(jsClass) ?: getName(jsClass) ?: "unknown",
                qualifiedName = getQualifiedName(jsClass),
                file = jsClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, jsClass),
                kind = getClassKind(jsClass),
                language = getLanguageName(jsClass)
            ),
            supertypes = supertypes,
            subtypes = subtypes
        )
    }

    private fun getSupertypes(
        project: Project,
        jsClass: PsiElement,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 0,
        searchScope: GlobalSearchScope
    ): List<TypeElementData> {
        if (depth > MAX_HIERARCHY_DEPTH) return emptyList()

        val className = getQualifiedName(jsClass) ?: getName(jsClass) ?: return emptyList()
        if (className in visited) return emptyList()
        visited.add(className)

        val supertypes = mutableListOf<TypeElementData>()

        try {
            // Get superclasses
            val superClasses = getSuperClasses(jsClass)
            superClasses?.filterIsInstance<PsiElement>()?.forEach { superClass ->
                val superName = getQualifiedName(superClass) ?: getName(superClass)
                if (superName != null && shouldIncludeNavigationElement(searchScope, superClass)) {
                    val superSupertypes = getSupertypes(project, superClass, visited, depth + 1, searchScope)
                    supertypes.add(TypeElementData(
                        name = superName,
                        qualifiedName = getQualifiedName(superClass),
                        file = superClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, superClass),
                        kind = getClassKind(superClass),
                        language = getLanguageName(superClass),
                        supertypes = superSupertypes.takeIf { it.isNotEmpty() }
                    ))
                }
            }

            // Get implemented interfaces
            val interfaces = getImplementedInterfaces(jsClass)
            interfaces?.filterIsInstance<PsiElement>()?.forEach { iface ->
                val ifaceName = getQualifiedName(iface) ?: getName(iface)
                if (
                    ifaceName != null &&
                    ifaceName !in visited &&
                    shouldIncludeNavigationElement(searchScope, iface)
                ) {
                    val ifaceSupertypes = getSupertypes(project, iface, visited, depth + 1, searchScope)
                    supertypes.add(TypeElementData(
                        name = ifaceName,
                        qualifiedName = getQualifiedName(iface),
                        file = iface.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, iface),
                        kind = "INTERFACE",
                        language = getLanguageName(iface),
                        supertypes = ifaceSupertypes.takeIf { it.isNotEmpty() }
                    ))
                }
            }
        } catch (e: Exception) {
            LOG.debug("Error getting supertypes: ${e.message}")
        }

        return supertypes
    }

    private fun getSubtypes(
        project: Project,
        jsClass: PsiElement,
        searchScope: GlobalSearchScope
    ): List<TypeElementData> {
        // Strategy 1: Try JSInheritorsSearch (JavaScript plugin API)
        try {
            val result = searchUsingJSInheritorsSearch(project, jsClass, searchScope)
            if (result.isNotEmpty()) {
                LOG.debug("Found ${result.size} subtypes via JSInheritorsSearch")
                return result
            }
        } catch (e: Exception) {
            LOG.debug("JSInheritorsSearch failed: ${e.message}")
        }

        // Strategy 2: Try DefinitionsScopedSearch (Platform API)
        try {
            val result = searchSubtypesUsingDefinitionsScopedSearch(project, jsClass, searchScope)
            if (result.isNotEmpty()) {
                LOG.debug("Found ${result.size} subtypes via DefinitionsScopedSearch")
                return result
            }
        } catch (e: Exception) {
            LOG.debug("DefinitionsScopedSearch failed: ${e.message}")
        }

        LOG.debug("No subtypes found")
        return emptyList()
    }

    private fun searchUsingJSInheritorsSearch(
        project: Project,
        jsClass: PsiElement,
        searchScope: GlobalSearchScope
    ): List<TypeElementData> {
        val searchClass = Class.forName("com.intellij.lang.javascript.psi.resolve.JSInheritorsSearch")
        val searchMethod = searchClass.getMethod("search", jsClassClass)
        val query = searchMethod.invoke(null, jsClass)

        val results = mutableListOf<TypeElementData>()
        val forEachMethod = query.javaClass.getMethod("forEach", Processor::class.java)
        forEachMethod.invoke(query, Processor<Any> { inheritor ->
            if (inheritor is PsiElement && shouldIncludeNavigationElement(searchScope, inheritor)) {
                results.add(TypeElementData(
                    name = getQualifiedName(inheritor) ?: getName(inheritor) ?: "unknown",
                    qualifiedName = getQualifiedName(inheritor),
                    file = inheritor.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                    line = getLineNumber(project, inheritor),
                    kind = getClassKind(inheritor),
                    language = getLanguageName(inheritor)
                ))
            }
            results.size < 100
        })

        return results
    }

    private fun searchSubtypesUsingDefinitionsScopedSearch(
        project: Project,
        jsClass: PsiElement,
        searchScope: GlobalSearchScope
    ): List<TypeElementData> {
        val results = mutableListOf<TypeElementData>()

        DefinitionsScopedSearch.search(jsClass, searchScope).forEach(Processor { definition ->
            if (definition != jsClass && isJSClass(definition) && shouldIncludeNavigationElement(searchScope, definition)) {
                results.add(TypeElementData(
                    name = getQualifiedName(definition) ?: getName(definition) ?: "unknown",
                    qualifiedName = getQualifiedName(definition),
                    file = definition.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                    line = getLineNumber(project, definition),
                    kind = getClassKind(definition),
                    language = getLanguageName(definition)
                ))
            }
            results.size < 100
        })

        return results
    }
}

/**
 * JavaScript implementation of [ImplementationsHandler].
 */
class JavaScriptImplementationsHandler : BaseJavaScriptHandler<List<ImplementationData>>(), ImplementationsHandler {

    override val languageId = "JavaScript"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isJavaScriptLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.javaScript.isAvailable && jsFunctionClass != null

    override fun findImplementations(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope
    ): List<ImplementationData>? {
        LOG.debug("Finding implementations for element at ${element.containingFile?.name}")
        val searchScope = createNavigationSearchScope(project, scope)

        val jsFunction = findContainingJSFunction(element)
        if (jsFunction != null) {
            val containingClass = findContainingJSClass(jsFunction)
            if (containingClass != null) {
                LOG.debug("Finding method implementations for ${getName(jsFunction)}")
                return findMethodImplementations(project, jsFunction, searchScope)
            }
        }

        val jsClass = findContainingJSClass(element)
        if (jsClass != null) {
            LOG.debug("Finding class implementations for ${getName(jsClass)}")
            return findClassImplementations(project, jsClass, searchScope)
        }

        return null
    }

    private fun findMethodImplementations(
        project: Project,
        jsFunction: PsiElement,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        // Strategy 1: Try JSFunctionOverridingSearch
        try {
            val result = searchUsingJSFunctionOverridingSearch(project, jsFunction, searchScope)
            if (result.isNotEmpty()) {
                LOG.debug("Found ${result.size} implementations via JSFunctionOverridingSearch")
                return result
            }
        } catch (e: Exception) {
            LOG.debug("JSFunctionOverridingSearch failed: ${e.message}")
        }

        // Strategy 2: Try DefinitionsScopedSearch (Platform API)
        try {
            val result = searchImplementationsUsingDefinitionsScopedSearch(project, jsFunction, searchScope)
            if (result.isNotEmpty()) {
                LOG.debug("Found ${result.size} implementations via DefinitionsScopedSearch")
                return result
            }
        } catch (e: Exception) {
            LOG.debug("DefinitionsScopedSearch failed: ${e.message}")
        }

        LOG.debug("No implementations found")
        return emptyList()
    }

    private fun searchUsingJSFunctionOverridingSearch(
        project: Project,
        jsFunction: PsiElement,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        val searchClass = Class.forName("com.intellij.lang.javascript.psi.resolve.JSFunctionOverridingSearch")
        val searchMethod = searchClass.getMethod("search", jsFunctionClass)
        val query = searchMethod.invoke(null, jsFunction)

        val results = mutableListOf<ImplementationData>()
        val forEachMethod = query.javaClass.getMethod("forEach", Processor::class.java)
        forEachMethod.invoke(query, Processor<Any> { overridingMethod ->
            if (overridingMethod is PsiElement && shouldIncludeNavigationElement(searchScope, overridingMethod)) {
                val file = overridingMethod.containingFile?.virtualFile
                if (file != null) {
                    val containingClass = findContainingJSClass(overridingMethod)
                    val className = containingClass?.let { getName(it) } ?: ""
                    val methodName = getName(overridingMethod) ?: "unknown"
                    results.add(ImplementationData(
                        name = if (className.isNotEmpty()) "$className.$methodName" else methodName,
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, overridingMethod) ?: 0,
                        column = getColumnNumber(project, overridingMethod) ?: 0,
                        kind = "METHOD",
                        language = getLanguageName(overridingMethod)
                    ))
                }
            }
            results.size < 100
        })

        return results
    }

    private fun findClassImplementations(
        project: Project,
        jsClass: PsiElement,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        // Strategy 1: Try JSInheritorsSearch
        try {
            val result = searchClassUsingJSInheritorsSearch(project, jsClass, searchScope)
            if (result.isNotEmpty()) {
                LOG.debug("Found ${result.size} class implementations via JSInheritorsSearch")
                return result
            }
        } catch (e: Exception) {
            LOG.debug("JSInheritorsSearch failed: ${e.message}")
        }

        // Strategy 2: Try DefinitionsScopedSearch (Platform API)
        try {
            val result = searchImplementationsUsingDefinitionsScopedSearch(project, jsClass, searchScope)
            if (result.isNotEmpty()) {
                LOG.debug("Found ${result.size} class implementations via DefinitionsScopedSearch")
                return result
            }
        } catch (e: Exception) {
            LOG.debug("DefinitionsScopedSearch failed: ${e.message}")
        }

        LOG.debug("No class implementations found")
        return emptyList()
    }

    private fun searchClassUsingJSInheritorsSearch(
        project: Project,
        jsClass: PsiElement,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        val searchClass = Class.forName("com.intellij.lang.javascript.psi.resolve.JSInheritorsSearch")
        val searchMethod = searchClass.getMethod("search", jsClassClass)
        val query = searchMethod.invoke(null, jsClass)

        val results = mutableListOf<ImplementationData>()
        val forEachMethod = query.javaClass.getMethod("forEach", Processor::class.java)
        forEachMethod.invoke(query, Processor<Any> { inheritor ->
            if (inheritor is PsiElement && shouldIncludeNavigationElement(searchScope, inheritor)) {
                val file = inheritor.containingFile?.virtualFile
                if (file != null) {
                    results.add(ImplementationData(
                        name = getQualifiedName(inheritor) ?: getName(inheritor) ?: "unknown",
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, inheritor) ?: 0,
                        column = getColumnNumber(project, inheritor) ?: 0,
                        kind = getClassKind(inheritor),
                        language = getLanguageName(inheritor)
                    ))
                }
            }
            results.size < 100
        })

        return results
    }

    private fun searchImplementationsUsingDefinitionsScopedSearch(
        project: Project,
        element: PsiElement,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()

        DefinitionsScopedSearch.search(element, searchScope).forEach(Processor { definition ->
            if (definition != element && shouldIncludeNavigationElement(searchScope, definition)) {
                val file = definition.containingFile?.virtualFile
                if (file != null) {
                    val kind = when {
                        isJSClass(definition) -> getClassKind(definition)
                        isJSFunction(definition) -> "METHOD"
                        else -> "UNKNOWN"
                    }
                    results.add(ImplementationData(
                        name = getQualifiedName(definition) ?: getName(definition) ?: "unknown",
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, definition) ?: 0,
                        column = getColumnNumber(project, definition) ?: 0,
                        kind = kind,
                        language = getLanguageName(definition)
                    ))
                }
            }
            results.size < 100
        })

        return results
    }
}

/**
 * JavaScript implementation of [CallHierarchyHandler].
 */
class JavaScriptCallHierarchyHandler : BaseJavaScriptHandler<CallHierarchyData>(), CallHierarchyHandler {

    private enum class CallerReferenceSource {
        DIRECT,
        BARREL_TRAVERSAL
    }

    private data class CollectedCallerReference(
        val reference: com.intellij.psi.PsiReference,
        val source: CallerReferenceSource
    )

    private data class PrioritizedCallerResult(
        val call: CallElementData,
        val source: CallerReferenceSource
    )

    companion object {
        private const val MAX_RESULTS_PER_LEVEL = 20
        private const val MAX_STACK_DEPTH = 50
        private const val MAX_SUPER_METHODS = 10
        // Keep breadth-first traversal bounded, but leave enough headroom for real-world
        // directory barrels that co-export multiple names before the caller import hop.
        private const val MAX_BARREL_SYMBOLS_TO_TRAVERSE = 40
        private const val MAX_BARREL_REFERENCES_TO_COLLECT = 120
    }

    override val languageId = "JavaScript"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isJavaScriptLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.javaScript.isAvailable && jsFunctionClass != null

    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int,
        scope: BuiltInSearchScope
    ): CallHierarchyData? {
        val jsFunction = findContainingJSFunction(resolveJsTsCallHierarchySeed(element)) ?: return null
        LOG.debug("Getting call hierarchy for ${getName(jsFunction)}, direction=$direction, depth=$depth")
        val searchScope = createNavigationSearchScope(project, scope)

        val visited = mutableSetOf<String>()
        val calls = if (direction == "callers") {
            findCallersRecursive(project, jsFunction, depth, visited, searchScope = searchScope)
                .map(PrioritizedCallerResult::call)
        } else {
            findCalleesRecursive(project, jsFunction, depth, visited, searchScope = searchScope)
        }

        LOG.debug("Found ${calls.size} ${direction}")

        return CallHierarchyData(
            element = createCallElement(project, jsFunction),
            calls = calls
        )
    }

    private fun findAllSuperMethods(project: Project, jsFunction: PsiElement): Set<PsiElement> {
        val superMethods = mutableSetOf<PsiElement>()
        val visited = mutableSetOf<String>()
        findSuperMethodsRecursive(project, jsFunction, superMethods, visited)
        return superMethods.take(MAX_SUPER_METHODS).toSet()
    }

    private fun findSuperMethodsRecursive(
        project: Project,
        jsFunction: PsiElement,
        result: MutableSet<PsiElement>,
        visited: MutableSet<String>
    ) {
        val containingClass = findContainingJSClass(jsFunction) ?: return
        val methodName = getName(jsFunction) ?: return

        val superClasses = getSuperClasses(containingClass)
        superClasses?.filterIsInstance<PsiElement>()?.forEach { superClass ->
            val superClassName = getQualifiedName(superClass) ?: getName(superClass)
            val key = "$superClassName.$methodName"
            if (key in visited) return@forEach
            visited.add(key)

            val superMethod = findMethodInClass(superClass, methodName)
            if (superMethod != null) {
                result.add(superMethod)
                findSuperMethodsRecursive(project, superMethod, result, visited)
            }
        }

        val interfaces = getImplementedInterfaces(containingClass)
        interfaces?.filterIsInstance<PsiElement>()?.forEach { iface ->
            val ifaceName = getQualifiedName(iface) ?: getName(iface)
            val key = "$ifaceName.$methodName"
            if (key in visited) return@forEach
            visited.add(key)

            val superMethod = findMethodInClass(iface, methodName)
            if (superMethod != null) {
                result.add(superMethod)
            }
        }
    }

    private fun findCallersRecursive(
        project: Project,
        jsFunction: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0,
        searchScope: GlobalSearchScope
    ): List<PrioritizedCallerResult> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val functionKey = getFunctionKey(jsFunction)
        if (functionKey in visited) return emptyList()
        visited.add(functionKey)

        return try {
            // Collect all methods to search: current method + all super methods it overrides
            val methodsToSearch = mutableSetOf(jsFunction)
            methodsToSearch.addAll(findAllSuperMethods(project, jsFunction))

            // Traverse direct references first, then follow named re-exports / export-star
            // barrels in a bounded breadth-first way. This keeps caller discovery aligned
            // with reference-search behavior without treating unrelated symbols from the same
            // barrel as callers.
            val allReferences = collectCallerReferences(methodsToSearch, searchScope)

            LOG.debug("Found ${allReferences.size} references for ${getName(jsFunction)}")

            val resultsByKey = LinkedHashMap<String, PrioritizedCallerResult>()
            for (reference in allReferences) {
                val refElement = reference.reference.element
                val containingFunction = findContainingCallable(refElement)
                if (containingFunction != null && containingFunction != jsFunction && !methodsToSearch.contains(containingFunction)) {
                    val children = if (depth > 1) {
                        findCallersRecursive(project, containingFunction, depth - 1, visited, stackDepth + 1, searchScope)
                    } else null
                    if (shouldIncludeNavigationElement(searchScope, containingFunction)) {
                        putCallerResult(
                            resultsByKey,
                            getFunctionKey(containingFunction),
                            createCallElement(project, containingFunction, children?.map(PrioritizedCallerResult::call)),
                            reference.source
                        )
                    } else if (children != null) {
                        children.forEach { child ->
                            putCallerResult(resultsByKey, getCallElementKey(child.call), child.call, child.source)
                        }
                    }
                }
            }

            buildVisibleCallerResults(resultsByKey)
        } catch (e: Exception) {
            LOG.warn("Error finding callers: ${e.message}")
            emptyList()
        }
    }

    private fun putCallerResult(
        resultsByKey: MutableMap<String, PrioritizedCallerResult>,
        key: String,
        call: CallElementData,
        source: CallerReferenceSource
    ) {
        val existing = resultsByKey[key]
        if (existing == null || source == CallerReferenceSource.BARREL_TRAVERSAL && existing.source != CallerReferenceSource.BARREL_TRAVERSAL) {
            resultsByKey[key] = PrioritizedCallerResult(call, source)
        }
    }

    private fun buildVisibleCallerResults(resultsByKey: LinkedHashMap<String, PrioritizedCallerResult>): List<PrioritizedCallerResult> {
        val prioritized = resultsByKey.values.filter { it.source == CallerReferenceSource.BARREL_TRAVERSAL }
        if (prioritized.size >= MAX_RESULTS_PER_LEVEL) {
            return prioritized.take(MAX_RESULTS_PER_LEVEL)
        }

        val direct = resultsByKey.values.filter { it.source == CallerReferenceSource.DIRECT }
        return buildList(MAX_RESULTS_PER_LEVEL) {
            addAll(prioritized)
            addAll(direct.take(MAX_RESULTS_PER_LEVEL - size))
        }
    }

    private fun collectCallerReferences(
        methodsToSearch: Set<PsiElement>,
        searchScope: GlobalSearchScope
    ): List<CollectedCallerReference> {
        val pendingSymbols = ArrayDeque<PsiNamedElement>()
        methodsToSearch
            .asSequence()
            .filterIsInstance<PsiNamedElement>()
            .sortedBy { getPsiTraversalKey(it) }
            .forEach(pendingSymbols::addLast)

        val rootSymbolKeys = pendingSymbols.mapTo(hashSetOf()) { getPsiTraversalKey(it) }
        val visitedSymbols = linkedSetOf<String>()
        val queuedSymbols = pendingSymbols.mapTo(linkedSetOf()) { getPsiTraversalKey(it) }
        val referencesByKey = linkedMapOf<String, CollectedCallerReference>()

        while (
            pendingSymbols.isNotEmpty() &&
            visitedSymbols.size < MAX_BARREL_SYMBOLS_TO_TRAVERSE &&
            referencesByKey.size < MAX_BARREL_REFERENCES_TO_COLLECT
        ) {
            val symbol = pendingSymbols.removeFirst()
            val symbolKey = getPsiTraversalKey(symbol)
            queuedSymbols.remove(symbolKey)
            if (!visitedSymbols.add(symbolKey)) continue

            val referenceSource = if (symbolKey in rootSymbolKeys) {
                CallerReferenceSource.DIRECT
            } else {
                CallerReferenceSource.BARREL_TRAVERSAL
            }

            ReferencesSearch.search(symbol, searchScope).forEach(Processor { reference ->
                val referenceKey = buildReferenceKey(reference)
                if (putCollectedReference(referencesByKey, referenceKey, reference, referenceSource)) {
                    enqueueBarrelTraversalCandidates(
                        pendingSymbols,
                        queuedSymbols,
                        visitedSymbols,
                        findBarrelTraversalCandidates(reference.element, methodsToSearch, symbol)
                    )
                }
                referencesByKey.size < MAX_BARREL_REFERENCES_TO_COLLECT
            })

            val containingFile = symbol.containingFile
            if (containingFile != null && !symbol.name.isNullOrBlank()) {
                ReferencesSearch.search(containingFile, searchScope).forEach(Processor { reference ->
                    val referenceKey = buildReferenceKey(reference)
                    if (putCollectedReference(referencesByKey, referenceKey, reference, referenceSource)) {
                        enqueueBarrelTraversalCandidates(
                            pendingSymbols,
                            queuedSymbols,
                            visitedSymbols,
                            findBarrelTraversalCandidates(reference.element, methodsToSearch, symbol)
                        )
                    }
                    referencesByKey.size < MAX_BARREL_REFERENCES_TO_COLLECT
                })
            }
        }

        return referencesByKey.values.toList()
    }

    private fun putCollectedReference(
        referencesByKey: MutableMap<String, CollectedCallerReference>,
        referenceKey: String,
        reference: com.intellij.psi.PsiReference,
        source: CallerReferenceSource
    ): Boolean {
        val existing = referencesByKey[referenceKey]
        if (existing == null) {
            referencesByKey[referenceKey] = CollectedCallerReference(reference, source)
            return true
        }

        if (source == CallerReferenceSource.BARREL_TRAVERSAL && existing.source != CallerReferenceSource.BARREL_TRAVERSAL) {
            referencesByKey[referenceKey] = CollectedCallerReference(reference, source)
        }
        return false
    }

    private fun enqueueBarrelTraversalCandidates(
        pendingSymbols: ArrayDeque<PsiNamedElement>,
        queuedSymbols: MutableSet<String>,
        visitedSymbols: Set<String>,
        candidates: List<PsiNamedElement>
    ) {
        for (candidate in candidates) {
            val candidateKey = getPsiTraversalKey(candidate)
            if (
                candidateKey !in visitedSymbols &&
                candidateKey !in queuedSymbols &&
                queuedSymbols.size + visitedSymbols.size < MAX_BARREL_SYMBOLS_TO_TRAVERSE
            ) {
                pendingSymbols.addLast(candidate)
                queuedSymbols.add(candidateKey)
            }
        }
    }

    private fun findBarrelTraversalCandidates(
        referenceElement: PsiElement,
        methodsToSearch: Set<PsiElement>,
        searchedSymbol: PsiNamedElement
    ): List<PsiNamedElement> {
        if (findContainingCallable(referenceElement) != null) return emptyList()

        val candidates = linkedMapOf<String, PsiNamedElement>()
        val targetSymbols = methodsToSearch + searchedSymbol
        findSemanticImportExportBindings(referenceElement, targetSymbols, searchedSymbol).forEach { bindingCandidate ->
            candidates[getPsiTraversalKey(bindingCandidate)] = bindingCandidate
        }

        if (candidates.isEmpty()) {
            findContextBindingsByName(referenceElement, searchedSymbol.name).forEach { bindingCandidate ->
                candidates.putIfAbsent(getPsiTraversalKey(bindingCandidate), bindingCandidate)
            }
        }

        if (candidates.isEmpty()) {
            val bindingCandidate = findNearestImportExportBinding(referenceElement, methodsToSearch)
            if (bindingCandidate != null) {
                candidates[getPsiTraversalKey(bindingCandidate)] = bindingCandidate
            }
        }

        if (isExportStarContext(referenceElement)) {
            val symbolNames = buildRelatedSymbolNames(searchedSymbol, methodsToSearch)
            findMatchingExports(referenceElement.containingFile, symbolNames).forEach { exportCandidate ->
                val exportKey = getPsiTraversalKey(exportCandidate)
                if (exportKey !in candidates && exportCandidate !in methodsToSearch) {
                    candidates[exportKey] = exportCandidate
                }
            }
        }

        return candidates.values.toList()
    }

    private fun findContextBindingsByName(
        referenceElement: PsiElement,
        targetName: String?
    ): List<PsiNamedElement> {
        val normalizedTargetName = targetName?.takeIf { it.isNotBlank() } ?: return emptyList()
        val context = findNearestImportExportContext(referenceElement) ?: return emptyList()
        val candidates = linkedMapOf<String, PsiNamedElement>()

        PsiTreeUtil.processElements(context) { element ->
            val named = element as? PsiNamedElement ?: return@processElements true
            if (!looksLikeImportExportBinding(named)) {
                return@processElements true
            }

            if (named.name == normalizedTargetName || named.text.orEmpty().contains(normalizedTargetName)) {
                candidates.putIfAbsent(getPsiTraversalKey(named), named)
            }
            candidates.size < MAX_RESULTS_PER_LEVEL
        }

        return candidates.values.toList()
    }

    private fun findSemanticImportExportBindings(
        referenceElement: PsiElement,
        targetSymbols: Set<PsiElement>,
        searchedSymbol: PsiNamedElement
    ): List<PsiNamedElement> {
        val context = findNearestImportExportContext(referenceElement) ?: return emptyList()
        val searchedName = searchedSymbol.name ?: return emptyList()
        val candidates = linkedMapOf<String, PsiNamedElement>()

        PsiTreeUtil.processElements(context) { element ->
            val named = element as? PsiNamedElement ?: return@processElements true
            if (named in targetSymbols || named.name.isNullOrBlank() || !looksLikeImportExportBinding(named)) {
                return@processElements true
            }

            val candidateName = named.name.orEmpty()
            val candidateText = named.text.orEmpty()
            if (candidateName != searchedName && !candidateText.contains(searchedName)) {
                return@processElements true
            }

            if (!bindingSemanticallyTargets(named, targetSymbols)) {
                return@processElements true
            }

            candidates.putIfAbsent(getPsiTraversalKey(named), named)
            candidates.size < MAX_RESULTS_PER_LEVEL
        }

        return candidates.values.toList()
    }

    private fun findNearestImportExportContext(referenceElement: PsiElement): PsiElement? {
        var current: PsiElement? = referenceElement
        while (current != null && current !is PsiFile) {
            if (isModuleImportExportDeclaration(current)) return current
            current = current.parent
        }
        return null
    }

    private fun isModuleImportExportDeclaration(element: PsiElement): Boolean {
        val className = element.javaClass.name
        if (!(className.contains("Import") || className.contains("Export"))) return false
        if (invokeNoArgMethod(element, "getFromClause") != null) return true
        return element.children.any { it.javaClass.name.contains("FromClause") }
    }

    private fun bindingSemanticallyTargets(candidate: PsiNamedElement, targetSymbols: Set<PsiElement>): Boolean {
        val targetKeys = targetSymbols.mapTo(hashSetOf()) { getPsiTraversalKey(it) }
        return candidate.references.any { reference ->
            val resolved = reference.resolve() ?: return@any false
            resolved in targetSymbols || getPsiTraversalKey(resolved) in targetKeys
        }
    }

    private fun buildRelatedSymbolNames(searchedSymbol: PsiNamedElement, methodsToSearch: Set<PsiElement>): Set<String> {
        return buildSet {
            searchedSymbol.name?.takeIf { it.isNotBlank() }?.let(::add)
            methodsToSearch.asSequence()
                .filterIsInstance<PsiNamedElement>()
                .mapNotNull { it.name?.takeIf(String::isNotBlank) }
                .forEach(::add)
        }
    }

    private fun findNearestImportExportBinding(
        referenceElement: PsiElement,
        methodsToSearch: Set<PsiElement>
    ): PsiNamedElement? {
        var current: PsiElement? = referenceElement
        while (current != null && current !is PsiFile) {
            val named = current as? PsiNamedElement
            if (
                named != null &&
                named !in methodsToSearch &&
                !named.name.isNullOrBlank() &&
                looksLikeImportExportBinding(named)
            ) {
                return named
            }
            current = current.parent
        }
        return null
    }

    private fun looksLikeImportExportBinding(named: PsiNamedElement): Boolean {
        val className = named.javaClass.name
        if (
            className.contains("JSImportSpecifier") ||
            className.contains("JSImportBinding") ||
            className.contains("ImportedBinding") ||
            className.contains("ES6ImportSpecifier") ||
            className.contains("ES6ExportSpecifier") ||
            className.contains("ExportSpecifier")
        ) {
            return true
        }
        return callBooleanMethod(named, "isImported") == true ||
            callBooleanMethod(named, "isExported") == true ||
            callBooleanMethod(named, "isExport") == true
    }

    private fun isExportStarContext(referenceElement: PsiElement): Boolean {
        var current: PsiElement? = referenceElement
        while (current != null && current !is PsiFile) {
            if (isExportStarDeclaration(current)) return true
            current = current.parent
        }
        return false
    }

    private fun isExportStarDeclaration(element: PsiElement): Boolean {
        if (!element.javaClass.name.contains("ES6ExportDeclaration")) return false
        if (invokeNoArgMethod(element, "getFromClause") == null) return false
        val specifiers = invokeNoArgMethod(element, "getExportSpecifiers")
        val count = when (specifiers) {
            is Array<*> -> specifiers.size
            is Collection<*> -> specifiers.size
            else -> return false
        }
        return count == 0
    }

    private fun findMatchingExports(file: PsiFile?, symbolNames: Set<String>): List<PsiNamedElement> {
        if (file == null || symbolNames.isEmpty()) return emptyList()

        val exports = linkedMapOf<String, PsiNamedElement>()
        PsiTreeUtil.processElements(file) { element ->
            val named = element as? PsiNamedElement
            val exportName = named?.name
            if (
                named != null &&
                !exportName.isNullOrBlank() &&
                exportName in symbolNames &&
                isExportCandidate(named)
            ) {
                exports.putIfAbsent(getPsiTraversalKey(named), named)
            }
            exports.size < MAX_RESULTS_PER_LEVEL
        }
        return exports.values.toList()
    }

    private fun buildReferenceKey(reference: com.intellij.psi.PsiReference): String {
        val element = reference.element
        val range = element.textRange
        val path = element.containingFile?.virtualFile?.path ?: ""
        return "$path:${range?.startOffset ?: -1}:${range?.endOffset ?: -1}:${element.text}"
    }

    private fun getPsiTraversalKey(element: PsiElement): String {
        val path = element.containingFile?.virtualFile?.path ?: ""
        val range = element.textRange
        val name = (element as? PsiNamedElement)?.name ?: ""
        return "$path:${range?.startOffset ?: -1}:${range?.endOffset ?: -1}:${element.javaClass.name}:$name"
    }

    /**
     * Find the containing callable for a reference element.
     * Tries JSFunction first, but only if it has a non-empty name.
     * Anonymous arrow functions (e.g. `const App = () => ...`) are skipped and the
     * enclosing JSVariable is returned instead so the caller name resolves correctly.
     */
    private fun findContainingCallable(element: PsiElement): PsiElement? {
        val containingFunction = findContainingJSFunction(element)
        if (containingFunction != null && !getName(containingFunction).isNullOrBlank()) {
            return containingFunction
        }
        val jsVar = jsVariableClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, jsVar as Class<out PsiElement>)
    }

    private fun findCalleesRecursive(
        project: Project,
        jsFunction: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0,
        searchScope: GlobalSearchScope
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val functionKey = getFunctionKey(jsFunction)
        if (functionKey in visited) return emptyList()
        visited.add(functionKey)

        val callees = mutableListOf<CallElementData>()
        try {
            val jsCallExpr = jsCallExpressionClass ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val callExpressions = PsiTreeUtil.findChildrenOfType(jsFunction, jsCallExpr as Class<out PsiElement>)

            callExpressions.take(MAX_RESULTS_PER_LEVEL).forEach { callExpr ->
                val calledFunction = resolveCallExpression(callExpr)
                if (calledFunction != null && isJSFunction(calledFunction)) {
                    val children = if (depth > 1) {
                        findCalleesRecursive(project, calledFunction, depth - 1, visited, stackDepth + 1, searchScope)
                    } else null
                    if (shouldIncludeNavigationElement(searchScope, calledFunction)) {
                        val element = createCallElement(project, calledFunction, children)
                        if (callees.none { it.name == element.name && it.file == element.file }) {
                            callees.add(element)
                        }
                    } else if (children != null) {
                        children.forEach { child ->
                            if (callees.none { it.name == child.name && it.file == child.file }) {
                                callees.add(child)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LOG.debug("Error finding callees: ${e.message}")
        }
        return callees
    }

    private fun resolveCallExpression(callExpr: PsiElement): PsiElement? {
        return try {
            val methodExprMethod = callExpr.javaClass.getMethod("getMethodExpression")
            val methodExpr = methodExprMethod.invoke(callExpr) as? PsiElement ?: return null

            val referenceMethod = methodExpr.javaClass.getMethod("getReference")
            val reference = referenceMethod.invoke(methodExpr) as? com.intellij.psi.PsiReference
            reference?.resolve()
        } catch (_: Exception) {
            null
        }
    }

    private fun getFunctionKey(jsFunction: PsiElement): String {
        val containingClass = findContainingJSClass(jsFunction)
        val className = containingClass?.let { getQualifiedName(it) ?: getName(it) } ?: ""
        val functionName = getName(jsFunction) ?: ""
        val file = jsFunction.containingFile?.virtualFile?.path ?: ""
        return "$file:$className.$functionName"
    }

    private fun getCallElementKey(element: CallElementData): String {
        return "${element.file}:${element.line}:${element.column}:${element.name}"
    }

    private fun createCallElement(project: Project, jsFunction: PsiElement, children: List<CallElementData>? = null): CallElementData {
        val file = jsFunction.containingFile?.virtualFile
        val containingClass = findContainingJSClass(jsFunction)
        val className = containingClass?.let { getName(it) }
        val functionName = getName(jsFunction) ?: "unknown"

        val name = if (className != null) "$className.$functionName" else functionName

        return CallElementData(
            name = name,
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, jsFunction) ?: 0,
            column = getColumnNumber(project, jsFunction) ?: 0,
            language = getLanguageName(jsFunction),
            children = children?.takeIf { it.isNotEmpty() }
        )
    }
}

/**
 * JavaScript implementation of [SuperMethodsHandler].
 */
class JavaScriptSuperMethodsHandler : BaseJavaScriptHandler<SuperMethodsData>(), SuperMethodsHandler {

    override val languageId = "JavaScript"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isJavaScriptLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.javaScript.isAvailable && jsFunctionClass != null

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val jsFunction = findContainingJSFunction(element) ?: return null
        val containingClass = findContainingJSClass(jsFunction) ?: return null

        LOG.debug("Finding super methods for ${getName(jsFunction)} in ${getName(containingClass)}")

        val file = jsFunction.containingFile?.virtualFile
        val methodData = MethodData(
            name = getName(jsFunction) ?: "unknown",
            signature = buildMethodSignature(jsFunction),
            containingClass = getQualifiedName(containingClass) ?: getName(containingClass) ?: "unknown",
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, jsFunction) ?: 0,
            column = getColumnNumber(project, jsFunction) ?: 0,
            language = getLanguageName(jsFunction)
        )

        val hierarchy = buildHierarchy(project, jsFunction)
        LOG.debug("Found ${hierarchy.size} super methods")

        return SuperMethodsData(
            method = methodData,
            hierarchy = hierarchy
        )
    }

    private fun buildHierarchy(
        project: Project,
        jsFunction: PsiElement,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 1
    ): List<SuperMethodData> {
        val hierarchy = mutableListOf<SuperMethodData>()

        try {
            val containingClass = findContainingJSClass(jsFunction) ?: return emptyList()
            val methodName = getName(jsFunction) ?: return emptyList()

            // Get superclasses and look for methods with the same name
            val superClasses = getSuperClasses(containingClass)
            superClasses?.filterIsInstance<PsiElement>()?.forEach { superClass ->
                val superClassName = getQualifiedName(superClass) ?: getName(superClass)
                val key = "$superClassName.$methodName"
                if (key in visited) return@forEach
                visited.add(key)

                val superMethod = findMethodInClass(superClass, methodName)
                if (superMethod != null) {
                    val file = superMethod.containingFile?.virtualFile

                    hierarchy.add(SuperMethodData(
                        name = methodName,
                        signature = buildMethodSignature(superMethod),
                        containingClass = superClassName ?: "unknown",
                        containingClassKind = getClassKind(superClass),
                        file = file?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, superMethod),
                        column = getColumnNumber(project, superMethod),
                        isInterface = getClassKind(superClass) == "INTERFACE",
                        depth = depth,
                        language = getLanguageName(superMethod)
                    ))

                    hierarchy.addAll(buildHierarchy(project, superMethod, visited, depth + 1))
                }
            }

            // Also check implemented interfaces
            val interfaces = getImplementedInterfaces(containingClass)
            interfaces?.filterIsInstance<PsiElement>()?.forEach { iface ->
                val ifaceName = getQualifiedName(iface) ?: getName(iface)
                val key = "$ifaceName.$methodName"
                if (key in visited) return@forEach
                visited.add(key)

                val superMethod = findMethodInClass(iface, methodName)
                if (superMethod != null) {
                    val file = superMethod.containingFile?.virtualFile

                    hierarchy.add(SuperMethodData(
                        name = methodName,
                        signature = buildMethodSignature(superMethod),
                        containingClass = ifaceName ?: "unknown",
                        containingClassKind = "INTERFACE",
                        file = file?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, superMethod),
                        column = getColumnNumber(project, superMethod),
                        isInterface = true,
                        depth = depth,
                        language = getLanguageName(superMethod)
                    ))
                }
            }
        } catch (e: Exception) {
            LOG.debug("Error building hierarchy: ${e.message}")
        }

        return hierarchy
    }

    private fun buildMethodSignature(jsFunction: PsiElement): String {
        return try {
            val getParameterListMethod = jsFunction.javaClass.getMethod("getParameterList")
            val parameterList = getParameterListMethod.invoke(jsFunction)
            val getParametersMethod = parameterList.javaClass.getMethod("getParameters")
            val parameters = getParametersMethod.invoke(parameterList) as? Array<*> ?: emptyArray<Any>()

            val params = parameters.filterIsInstance<PsiElement>().mapNotNull { param ->
                try {
                    val getNameMethod = param.javaClass.getMethod("getName")
                    val name = getNameMethod.invoke(param) as? String

                    val type = try {
                        val getTypeMethod = param.javaClass.getMethod("getType")
                        val typeElement = getTypeMethod.invoke(param)
                        typeElement?.toString()
                    } catch (_: Exception) {
                        null
                    }

                    if (type != null) "$name: $type" else name
                } catch (_: Exception) {
                    null
                }
            }.joinToString(", ")

            val functionName = getName(jsFunction) ?: "unknown"

            val returnType = try {
                val getReturnTypeMethod = jsFunction.javaClass.getMethod("getReturnType")
                val returnTypeElement = getReturnTypeMethod.invoke(jsFunction)
                returnTypeElement?.toString()
            } catch (_: Exception) {
                null
            }

            if (returnType != null) {
                "$functionName($params): $returnType"
            } else {
                "$functionName($params)"
            }
        } catch (_: Exception) {
            getName(jsFunction) ?: "unknown"
        }
    }
}

// TypeScript handlers delegate to JavaScript handlers

class TypeScriptTypeHierarchyHandler : TypeHierarchyHandler by JavaScriptTypeHierarchyHandler() {
    override val languageId = "TypeScript"
}

class TypeScriptImplementationsHandler : ImplementationsHandler by JavaScriptImplementationsHandler() {
    override val languageId = "TypeScript"
}

class TypeScriptCallHierarchyHandler : CallHierarchyHandler by JavaScriptCallHierarchyHandler() {
    override val languageId = "TypeScript"
}

class TypeScriptSuperMethodsHandler : SuperMethodsHandler by JavaScriptSuperMethodsHandler() {
    override val languageId = "TypeScript"
}

/**
 * JavaScript implementation of [StructureHandler].
 *
 * Extracts the hierarchical structure of JavaScript source files including
 * classes, functions, variables, and their nesting relationships.
 *
 * Uses reflection to access JavaScript PSI classes to avoid compile-time dependencies.
 */
class JavaScriptStructureHandler : BaseJavaScriptHandler<List<StructureNode>>(), StructureHandler {

    override val languageId = "JavaScript"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isJavaScriptLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.javaScript.isAvailable && jsFunctionClass != null

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        val structure = mutableListOf<StructureNode>()

        try {
            // Check if the file is a JavaScript/TypeScript file
            val jsFileClass = try {
                Class.forName("com.intellij.lang.javascript.psi.JSFile")
            } catch (_: ClassNotFoundException) {
                null
            }
            if (jsFileClass != null && !jsFileClass.isInstance(file)) {
                LOG.debug("File is not a JSFile: ${file.javaClass.name}, language: ${file.language.id}")
                return emptyList()
            }

             // Find top-level classes
             if (jsClassClass != null) {
                 @Suppress("UNCHECKED_CAST")
                 val classes = PsiTreeUtil.findChildrenOfType(file, jsClassClass as Class<PsiElement>)
                 classes.forEach { jsClass ->
                     if (isTopLevel(jsClass, file)) {
                         structure.add(extractClassStructure(jsClass, project))
                     }
                 }
             }

             // Find top-level functions
             if (jsFunctionClass != null) {
                 @Suppress("UNCHECKED_CAST")
                 val functions = PsiTreeUtil.findChildrenOfType(file, jsFunctionClass as Class<PsiElement>)
                 functions.forEach { jsFunction ->
                     if (isTopLevel(jsFunction, file)) {
                         structure.add(extractFunctionStructure(jsFunction, project))
                     }
                 }
             }

             // Find top-level variables
             if (jsVariableClass != null) {
                 @Suppress("UNCHECKED_CAST")
                 val variables = PsiTreeUtil.findChildrenOfType(file, jsVariableClass as Class<PsiElement>)
                 variables.forEach { jsVariable ->
                     if (isTopLevel(jsVariable, file)) {
                         structure.add(extractVariableStructure(jsVariable, project))
                     }
                 }
             }

        } catch (e: ClassNotFoundException) {
            LOG.warn("JavaScript PSI class not found: ${e.message}")
        } catch (e: Exception) {
            LOG.warn("Failed to extract JavaScript file structure: ${e.message}, ${e.javaClass.simpleName}")
        }

        return structure.sortedBy { it.line }
    }

    private fun isTopLevel(element: PsiElement, file: PsiFile): Boolean {
        var current: PsiElement? = element.parent
        while (current != null && current != file) {
            if (isJSClass(current) || isJSFunction(current)) {
                return false
            }
            current = current.parent
        }
        return true
    }

    private fun extractClassStructure(jsClass: PsiElement, project: Project): StructureNode {
        val children = mutableListOf<StructureNode>()

        try {
            // Get class methods via getFunctions()
            val getFunctionsMethod = jsClass.javaClass.getMethod("getFunctions")
            val functions = getFunctionsMethod.invoke(jsClass) as? Array<*> ?: emptyArray<Any?>()
            for (func in functions) {
                if (func is PsiElement) {
                    children.add(extractMethodStructure(func, project))
                }
            }
        } catch (_: Exception) {
            // getFunctions() not available, try children scan
             try {
                 if (jsFunctionClass != null) {
                     @Suppress("UNCHECKED_CAST")
                     val methods = PsiTreeUtil.findChildrenOfType(jsClass, jsFunctionClass as Class<PsiElement>)
                     methods.forEach { method ->
                         if (method.parent == jsClass || method.parent?.parent == jsClass) {
                             children.add(extractMethodStructure(method, project))
                         }
                     }
                 }
             } catch (_: Exception) {
                 // Ignore
             }
        }

        try {
            // Get class fields via getFields()
            val getFieldsMethod = jsClass.javaClass.getMethod("getFields")
            val fields = getFieldsMethod.invoke(jsClass) as? Array<*> ?: emptyArray<Any?>()
            for (field in fields) {
                if (field is PsiElement) {
                    children.add(extractFieldStructure(field, project))
                }
            }
        } catch (_: Exception) {
            // getFields() not available, skip
        }

        val name = getName(jsClass) ?: "unknown"
        val kind = when {
            isTypeScriptTypeAlias(jsClass) -> StructureKind.TYPE_ALIAS
            getClassKind(jsClass) == "INTERFACE" -> StructureKind.INTERFACE
            else -> StructureKind.CLASS
        }

        return StructureNode(
            name = name,
            kind = kind,
            modifiers = getJavaScriptModifiers(jsClass),
            signature = buildClassSignature(jsClass),
            line = getLineNumber(project, jsClass) ?: 0,
            children = children.sortedBy { it.line }
        )
    }

    private fun extractMethodStructure(jsFunction: PsiElement, project: Project): StructureNode {
        val name = getName(jsFunction) ?: "unknown"
        return StructureNode(
            name = name,
            kind = StructureKind.METHOD,
            modifiers = getJavaScriptModifiers(jsFunction),
            signature = buildFunctionSignature(jsFunction),
            line = getLineNumber(project, jsFunction) ?: 0
        )
    }

    private fun extractFunctionStructure(jsFunction: PsiElement, project: Project): StructureNode {
        val name = getName(jsFunction) ?: "unknown"
        return StructureNode(
            name = name,
            kind = StructureKind.FUNCTION,
            modifiers = getJavaScriptModifiers(jsFunction),
            signature = buildFunctionSignature(jsFunction),
            line = getLineNumber(project, jsFunction) ?: 0
        )
    }

    private fun extractVariableStructure(jsVariable: PsiElement, project: Project): StructureNode {
        val name = getName(jsVariable) ?: "unknown"
        return StructureNode(
            name = name,
            kind = StructureKind.VARIABLE,
            modifiers = emptyList(),
            signature = null,
            line = getLineNumber(project, jsVariable) ?: 0
        )
    }

    private fun extractFieldStructure(field: PsiElement, project: Project): StructureNode {
        val name = getName(field) ?: "unknown"
        return StructureNode(
            name = name,
            kind = StructureKind.FIELD,
            modifiers = getJavaScriptModifiers(field),
            signature = null,
            line = getLineNumber(project, field) ?: 0
        )
    }

    private fun getJavaScriptModifiers(element: PsiElement): List<String> {
        val modifiers = mutableListOf<String>()
        try {
            val attrListMethod = element.javaClass.getMethod("getAttributeList")
            val attrList = attrListMethod.invoke(element) ?: return modifiers

            val accessTypeMethod = attrList.javaClass.getMethod("getAccessType")
            val accessType = accessTypeMethod.invoke(attrList)
            val accessName = accessType?.toString()?.lowercase()
            if (accessName != null && accessName != "public" && accessName != "package_local") {
                modifiers.add(accessName)
            }

            try {
                val isStaticMethod = attrList.javaClass.getMethod("hasModifier", String::class.java)
                if (isStaticMethod.invoke(attrList, "static") as? Boolean == true) {
                    modifiers.add("static")
                }
                if (isStaticMethod.invoke(attrList, "async") as? Boolean == true) {
                    modifiers.add("async")
                }
                if (isStaticMethod.invoke(attrList, "abstract") as? Boolean == true) {
                    modifiers.add("abstract")
                }
            } catch (_: Exception) {
                // hasModifier not available
            }
        } catch (_: Exception) {
            // No attribute list available
        }
        return modifiers
    }

    private fun buildClassSignature(jsClass: PsiElement): String {
        return try {
            val superClasses = getSuperClasses(jsClass)
            if (superClasses != null && superClasses.isNotEmpty()) {
                val names = superClasses.filterIsInstance<PsiElement>().mapNotNull {
                    getQualifiedName(it) ?: getName(it)
                }
                if (names.isNotEmpty()) "extends ${names.joinToString(", ")}" else ""
            } else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun buildFunctionSignature(jsFunction: PsiElement): String {
        return try {
            val getParameterListMethod = jsFunction.javaClass.getMethod("getParameterList")
            val parameterList = getParameterListMethod.invoke(jsFunction) ?: return "()"
            val getParametersMethod = parameterList.javaClass.getMethod("getParameters")
            val parameters = getParametersMethod.invoke(parameterList) as? Array<*> ?: return "()"

            val params = parameters.filterIsInstance<PsiElement>().mapNotNull { param ->
                try {
                    val getNameMethod = param.javaClass.getMethod("getName")
                    getNameMethod.invoke(param) as? String
                } catch (_: Exception) {
                    null
                }
            }.joinToString(", ")

            "($params)"
        } catch (_: Exception) {
            "()"
        }
    }
}

/**
 * TypeScript implementation of [StructureHandler].
 * Delegates to [JavaScriptStructureHandler] with TypeScript language ID.
 */
class TypeScriptStructureHandler : StructureHandler by JavaScriptStructureHandler() {
    override val languageId = "TypeScript"
}
