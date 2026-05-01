package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.IdeProductInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

/**
 * Registration entry point for Rider C# and F# language handlers.
 *
 * Rider's C# and F# semantic model is served by the ReSharper backend rather than normal frontend
 * PSI classes. These handlers intentionally avoid compile-time dependencies on Rider/ReSharper
 * classes and use Rider's frontend navigation bridge (references, definitions, navigation items,
 * and language metadata) when Rider is running.
 */
object DotNetHandlers {
    private val LOG = logger<DotNetHandlers>()

    @JvmStatic
    fun register(registry: LanguageHandlerRegistry) {
        if (!BaseDotNetHandler.isRiderAvailable()) {
            LOG.info("Rider not available, skipping .NET handler registration")
            return
        }

        registry.registerTypeHierarchyHandler(CSharpTypeHierarchyHandler())
        registry.registerImplementationsHandler(CSharpImplementationsHandler())
        registry.registerCallHierarchyHandler(CSharpCallHierarchyHandler())
        registry.registerSuperMethodsHandler(CSharpSuperMethodsHandler())
        registry.registerStructureHandler(CSharpStructureHandler())

        registry.registerTypeHierarchyHandler(FSharpTypeHierarchyHandler())
        registry.registerImplementationsHandler(FSharpImplementationsHandler())
        registry.registerCallHierarchyHandler(FSharpCallHierarchyHandler())
        registry.registerSuperMethodsHandler(FSharpSuperMethodsHandler())
        registry.registerStructureHandler(FSharpStructureHandler())

        LOG.info("Registered Rider .NET handlers for C# and F#")
    }
}

abstract class BaseDotNetHandler<T>(
    final override val languageId: String,
    protected val displayLanguage: String,
    private val supportedExtensions: Set<String>,
    private val supportedLanguageIds: Set<String>,
) : LanguageHandler<T> {
    protected val LOG = logger<BaseDotNetHandler<*>>()

    companion object {
        private const val MAX_PARENT_DEPTH = 40
        private const val MAX_RESULTS = 100
        // Ignore tiny leaf-token text like identifiers/operators when deriving declaration headers.
        private const val MIN_USEFUL_HEADER_LENGTH = 8
        // Cap headers to a single readable declaration line and avoid embedding large method bodies.
        private const val MAX_DECLARATION_HEADER_LENGTH = 240
        private val TYPE_KEYWORDS = setOf("class", "interface", "struct", "enum", "record", "delegate", "type")
        private val CALLABLE_KEYWORDS = setOf("void", "async", "member", "let", "override")
        private val MODIFIERS = setOf("public", "private", "protected", "internal", "static", "abstract", "sealed", "virtual", "override", "async", "partial", "readonly")

        fun isRiderAvailable(): Boolean =
            PluginDetectors.rider.isAvailable || IdeProductInfo.detectIdeProduct() == IdeProductInfo.IdeProduct.RIDER
    }

    override fun isAvailable(): Boolean = isRiderAvailable()

    override fun canHandle(element: PsiElement): Boolean = isAvailable() && isDotNetElement(element)

    protected fun isDotNetElement(element: PsiElement): Boolean {
        val langId = element.language.id
        if (supportedLanguageIds.any { it.equals(langId, ignoreCase = true) }) return true
        val ext = element.containingFile?.virtualFile?.extension?.lowercase()
        return ext in supportedExtensions
    }

    /**
     * Resolves a source position to the best available named .NET target.
     *
     * Resolution order is direct named element, semantic reference target/navigation element, then
     * nearest named parent. This covers both declarations and references in Rider's frontend PSI.
     *
     * @param element The frontend PSI element at or near the requested position.
     * @return The best named declaration/reference target, or null when no .NET named target exists.
     */
    protected fun resolveNamedTarget(element: PsiElement): PsiNamedElement? {
        (element as? PsiNamedElement)?.takeIf { isDotNetElement(it) }?.let { return it }
        element.reference?.resolve()?.let { resolved ->
            (resolved.navigationElement as? PsiNamedElement)?.takeIf { isDotNetElement(it) }?.let { return it }
            (resolved as? PsiNamedElement)?.takeIf { isDotNetElement(it) }?.let { return it }
        }

        var current: PsiElement? = element
        var depth = 0
        while (current != null && depth < MAX_PARENT_DEPTH) {
            (current as? PsiNamedElement)?.takeIf { isDotNetElement(it) }?.let { return it }
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * Resolves the nearest type-level declaration for a source position.
     *
     * The method first resolves references to their target, then walks parents until it finds a
     * class/interface/struct/enum/record/delegate/type declaration according to Rider metadata or
     * declaration text heuristics.
     *
     * @param element The frontend PSI element at or near the requested position.
     * @return The nearest type-like named element, or null when the position is outside a type.
     */
    protected fun resolveTypeElement(element: PsiElement): PsiNamedElement? {
        val named = resolveNamedTarget(element) ?: return null
        if (classifyNamedElement(named).isType) return named

        var current = named.parent
        var depth = 0
        while (current != null && depth < MAX_PARENT_DEPTH) {
            (current as? PsiNamedElement)?.takeIf { classifyNamedElement(it).isType }?.let { return it }
            current = current.parent
            depth++
        }
        return named.takeIf { looksLikeTypeName(it.name) }
    }

    /**
     * Resolves the nearest callable declaration for a source position.
     *
     * The method handles references and declarations, then walks parents to locate methods,
     * constructors, F# members/functions, or similar callable elements.
     *
     * @param element The frontend PSI element at or near the requested position.
     * @return The nearest callable named element, or null when no callable can be resolved.
     */
    protected fun resolveCallableElement(element: PsiElement): PsiNamedElement? {
        val named = resolveNamedTarget(element) ?: return null
        if (classifyNamedElement(named).isCallable) return named

        var current = named.parent
        var depth = 0
        while (current != null && depth < MAX_PARENT_DEPTH) {
            (current as? PsiNamedElement)?.takeIf { classifyNamedElement(it).isCallable }?.let { return it }
            current = current.parent
            depth++
        }
        return named
    }

    protected fun getRelativePath(project: Project, element: PsiElement): String? =
        element.containingFile?.virtualFile?.let { ProjectUtils.getToolFilePath(project, it) }

    protected fun getLineNumber(project: Project, element: PsiElement): Int? {
        val file = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    protected fun getColumnNumber(project: Project, element: PsiElement): Int? {
        val file = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val line = document.getLineNumber(element.textOffset)
        return element.textOffset - document.getLineStartOffset(line) + 1
    }

    protected fun toTypeElementData(project: Project, element: PsiNamedElement, includeSupertypes: Boolean = false): TypeElementData {
        val kind = classifyNamedElement(element).typeKind
        return TypeElementData(
            name = qualifiedName(element),
            qualifiedName = qualifiedName(element),
            file = getRelativePath(project, element),
            line = getLineNumber(project, element),
            kind = kind,
            language = displayLanguage,
            supertypes = if (includeSupertypes) parseDeclaredSupertypes(element).map { name ->
                TypeElementData(
                    name = name,
                    qualifiedName = name,
                    file = null,
                    line = null,
                    kind = "TYPE",
                    language = displayLanguage
                )
            } else null
        )
    }

    protected fun toImplementationData(project: Project, element: PsiElement): ImplementationData? {
        val named = (element.navigationElement as? PsiNamedElement) ?: element as? PsiNamedElement ?: return null
        if (!isDotNetElement(named)) return null
        val file = getRelativePath(project, named) ?: return null
        return ImplementationData(
            name = qualifiedName(named),
            file = file,
            line = getLineNumber(project, named) ?: return null,
            column = getColumnNumber(project, named) ?: 1,
            kind = classifyNamedElement(named).typeKind.lowercase(),
            language = displayLanguage
        )
    }

    protected fun toCallElementData(project: Project, element: PsiNamedElement, children: List<CallElementData>? = null): CallElementData? {
        val file = getRelativePath(project, element) ?: return null
        return CallElementData(
            name = qualifiedName(element),
            file = file,
            line = getLineNumber(project, element) ?: return null,
            column = getColumnNumber(project, element) ?: 1,
            language = displayLanguage,
            children = children
        )
    }

    protected fun findDefinitions(element: PsiNamedElement, project: Project, scope: BuiltInSearchScope): List<PsiElement> {
        val searchScope = createNavigationSearchScope(project, scope)
        val results = mutableListOf<PsiElement>()
        DefinitionsScopedSearch.search(element, searchScope).forEach(Processor { definition ->
            if (definition != element && isDotNetElement(definition)) {
                results.add(definition.navigationElement ?: definition)
            }
            results.size < MAX_RESULTS
        })
        return results.distinctBy { "${it.containingFile?.virtualFile?.path}:${it.textOffset}" }
    }

    protected fun findReferences(element: PsiNamedElement, project: Project, scope: BuiltInSearchScope): List<PsiElement> {
        val searchScope = createNavigationSearchScope(project, scope)
        val results = mutableListOf<PsiElement>()
        ReferencesSearch.search(element, searchScope).forEach(Processor { reference ->
            val refElement = reference.element
            if (isDotNetElement(refElement)) results.add(refElement)
            results.size < MAX_RESULTS
        })
        return results
    }

    protected fun findContainingCallable(element: PsiElement): PsiNamedElement? {
        var current: PsiElement? = element
        var depth = 0
        while (current != null && depth < MAX_PARENT_DEPTH) {
            (current as? PsiNamedElement)?.takeIf { isDotNetElement(it) && classifyNamedElement(it).isCallable }?.let { return it }
            current = current.parent
            depth++
        }
        return null
    }

    protected fun collectCallees(element: PsiNamedElement, project: Project, scope: BuiltInSearchScope, depth: Int): List<CallElementData> {
        if (depth <= 0) return emptyList()
        val searchScope = createNavigationSearchScope(project, scope)
        val calls = linkedMapOf<String, CallElementData>()
        element.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(child: PsiElement) {
                if (calls.size >= MAX_RESULTS) return
                val resolved = child.reference?.resolve()?.navigationElement as? PsiNamedElement
                if (resolved != null && resolved != element && isDotNetElement(resolved)) {
                    val file = resolved.containingFile?.virtualFile
                    if (file != null && searchScope.contains(file)) {
                        toCallElementData(project, resolved)?.let {
                            calls.putIfAbsent("${it.file}:${it.line}:${it.column}:${it.name}", it)
                        }
                    }
                }
                super.visitElement(child)
            }
        })
        return calls.values.toList()
    }

    protected fun qualifiedName(element: PsiNamedElement): String {
        getStringNoArg(element, "getQualifiedName")?.let { return it }
        getStringNoArg(element, "getPresentableText")?.let { return it }
        return element.name ?: element.text.take(80).lineSequence().firstOrNull().orEmpty().trim().ifBlank { "unknown" }
    }

    protected fun getSignature(element: PsiNamedElement): String? =
        getStringNoArg(element, "getSignature")
            ?: element.text.lineSequence().firstOrNull()?.trim()?.take(160)

    /**
     * Parses declared supertypes from source declaration text.
     *
     * Supports C# base/interface lists after `:` and F# inherited types declared with `inherit`.
     * Returned names may be unresolved because Rider exposes full semantic hierarchy data through
     * backend services that are not directly linked from this frontend-only plugin.
     */
    protected fun parseDeclaredSupertypes(element: PsiElement): List<String> {
        val header = declarationHeader(element)
        val csharp = header.substringAfter(":", missingDelimiterValue = "")
            .substringBefore("{")
            .takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim().substringBefore("(").substringBefore(" where ") }
            ?: emptyList()

        val fsharp = Regex("""\binherit\s+([A-Za-z_][\w.`<>]*)""")
            .findAll(header)
            .map { it.groupValues[1] }
            .toList()

        return (csharp + fsharp).filter { it.isNotBlank() }.distinct()
    }

    /**
     * Classifies a Rider frontend named element using available metadata and declaration text.
     *
     * The classification is heuristic by design: it detects type declarations, callable elements,
     * and file-structure node kinds without depending on Rider/ReSharper implementation classes.
     *
     * @param element The Rider frontend named element to classify.
     * @return A compact description of type/callable status and file-structure kind.
     */
    protected fun classifyNamedElement(element: PsiNamedElement): DotNetElementKind {
        val header = declarationHeader(element).lowercase()
        val rawKind = getStringNoArg(element, "getKind")?.lowercase().orEmpty()
        val name = element.name.orEmpty()

        val typeKind = when {
            "interface" in header || "interface" in rawKind -> "INTERFACE"
            "struct" in header || "struct" in rawKind -> "STRUCT"
            "enum" in header || "enum" in rawKind -> "ENUM"
            "record" in header || "record" in rawKind -> "RECORD"
            "delegate" in header || "delegate" in rawKind -> "DELEGATE"
            Regex("""\btype\s+${Regex.escape(name)}\b""").containsMatchIn(header) -> "TYPE"
            "class" in header || "class" in rawKind -> "CLASS"
            else -> "TYPE"
        }

        val isType = TYPE_KEYWORDS.any { Regex("""\b$it\b""").containsMatchIn(header) } ||
            listOf("class", "interface", "struct", "enum", "record", "delegate").any { it in rawKind }

        val isCallable = !isType && ("(" in header || CALLABLE_KEYWORDS.any { Regex("""\b$it\b""").containsMatchIn(header) })
        val structureKind = when {
            isType -> when (typeKind) {
                "INTERFACE" -> StructureKind.INTERFACE
                "STRUCT", "RECORD" -> StructureKind.RECORD
                "ENUM" -> StructureKind.ENUM
                else -> StructureKind.CLASS
            }
            isCallable -> if (name == element.containingFile?.virtualFile?.nameWithoutExtension) StructureKind.CONSTRUCTOR else StructureKind.METHOD
            Regex("""\b(namespace|module)\b""").containsMatchIn(header) -> StructureKind.NAMESPACE
            else -> StructureKind.PROPERTY
        }
        return DotNetElementKind(isType = isType, isCallable = isCallable, typeKind = typeKind, structureKind = structureKind)
    }

    protected fun extractModifiers(element: PsiNamedElement): List<String> {
        val header = declarationHeader(element).lowercase()
        return MODIFIERS.filter { Regex("""\b$it\b""").containsMatchIn(header) }
    }

    private fun declarationHeader(element: PsiElement): String {
        val text = element.text.lineSequence().firstOrNull()?.trim().orEmpty()
        if (text.length > MIN_USEFUL_HEADER_LENGTH && text.length <= MAX_DECLARATION_HEADER_LENGTH) return text
        val parentText = element.parent?.text?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return parentText.take(MAX_DECLARATION_HEADER_LENGTH)
    }

    private fun getStringNoArg(target: Any, methodName: String): String? =
        try {
            target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                ?.invoke(target) as? String
        } catch (_: ReflectiveOperationException) {
            null
        }

    private fun looksLikeTypeName(name: String?): Boolean =
        !name.isNullOrBlank() && name.first().isUpperCase()

    protected data class DotNetElementKind(
        val isType: Boolean,
        val isCallable: Boolean,
        val typeKind: String,
        val structureKind: StructureKind,
    )
}

abstract class DotNetTypeHierarchyHandler(
    languageId: String,
    displayLanguage: String,
    supportedExtensions: Set<String>,
    supportedLanguageIds: Set<String>,
) : BaseDotNetHandler<TypeHierarchyData>(languageId, displayLanguage, supportedExtensions, supportedLanguageIds), TypeHierarchyHandler {
    override fun getTypeHierarchy(element: PsiElement, project: Project, scope: BuiltInSearchScope): TypeHierarchyData? {
        val typeElement = resolveTypeElement(element) ?: return null
        val subtypes = findDefinitions(typeElement, project, scope).mapNotNull {
            (it as? PsiNamedElement)?.let { named -> toTypeElementData(project, named, includeSupertypes = true) }
        }

        return TypeHierarchyData(
            element = toTypeElementData(project, typeElement, includeSupertypes = true),
            supertypes = parseDeclaredSupertypes(typeElement).map { name ->
                TypeElementData(name = name, qualifiedName = name, file = null, line = null, kind = "TYPE", language = displayLanguage)
            },
            subtypes = subtypes
        )
    }
}

abstract class DotNetImplementationsHandler(
    languageId: String,
    displayLanguage: String,
    supportedExtensions: Set<String>,
    supportedLanguageIds: Set<String>,
) : BaseDotNetHandler<List<ImplementationData>>(languageId, displayLanguage, supportedExtensions, supportedLanguageIds), ImplementationsHandler {
    override fun findImplementations(element: PsiElement, project: Project, scope: BuiltInSearchScope): List<ImplementationData>? {
        val target = resolveNamedTarget(element) ?: return null
        return findDefinitions(target, project, scope).mapNotNull { toImplementationData(project, it) }
    }
}

abstract class DotNetCallHierarchyHandler(
    languageId: String,
    displayLanguage: String,
    supportedExtensions: Set<String>,
    supportedLanguageIds: Set<String>,
) : BaseDotNetHandler<CallHierarchyData>(languageId, displayLanguage, supportedExtensions, supportedLanguageIds), CallHierarchyHandler {
    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int,
        scope: BuiltInSearchScope
    ): CallHierarchyData? {
        val callable = resolveCallableElement(element) ?: return null
        val root = toCallElementData(project, callable) ?: return null
        val calls = when (direction) {
            "callers" -> findReferences(callable, project, scope)
                .mapNotNull { findContainingCallable(it) }
                .distinctBy { "${it.containingFile?.virtualFile?.path}:${it.textOffset}" }
                .mapNotNull { caller -> toCallElementData(project, caller) }
            "callees" -> collectCallees(callable, project, scope, depth)
            else -> emptyList()
        }
        return CallHierarchyData(root, calls)
    }
}

abstract class DotNetSuperMethodsHandler(
    languageId: String,
    displayLanguage: String,
    supportedExtensions: Set<String>,
    supportedLanguageIds: Set<String>,
) : BaseDotNetHandler<SuperMethodsData>(languageId, displayLanguage, supportedExtensions, supportedLanguageIds), SuperMethodsHandler {
    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val method = resolveCallableElement(element) ?: return null
        val containingType = resolveTypeElement(method)
        val superTypes = containingType?.let { parseDeclaredSupertypes(it) }.orEmpty()
        val methodData = MethodData(
            name = method.name ?: "unknown",
            signature = getSignature(method) ?: method.name ?: "unknown",
            containingClass = containingType?.let { qualifiedName(it) } ?: "unknown",
            file = getRelativePath(project, method) ?: return null,
            line = getLineNumber(project, method) ?: return null,
            column = getColumnNumber(project, method) ?: 1,
            language = languageId
        )
        return SuperMethodsData(
            method = methodData,
            hierarchy = superTypes.mapIndexed { index, superType ->
                SuperMethodData(
                    name = method.name ?: "unknown",
                    signature = methodData.signature,
                    containingClass = superType,
                    containingClassKind = "TYPE",
                    file = null,
                    line = null,
                    column = null,
                    isInterface = false,
                    depth = index + 1,
                    language = languageId
                )
            }
        )
    }
}

abstract class DotNetStructureHandler(
    languageId: String,
    displayLanguage: String,
    supportedExtensions: Set<String>,
    supportedLanguageIds: Set<String>,
) : BaseDotNetHandler<List<StructureNode>>(languageId, displayLanguage, supportedExtensions, supportedLanguageIds), StructureHandler {
    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        val candidates = mutableListOf<Pair<PsiNamedElement, StructureNode>>()
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                val named = element as? PsiNamedElement
                if (named != null && isDotNetElement(named)) {
                    val kind = classifyNamedElement(named)
                    if (kind.structureKind != StructureKind.UNKNOWN && named.name != null) {
                        candidates.add(named to StructureNode(
                            name = named.name!!,
                            kind = kind.structureKind,
                            modifiers = extractModifiers(named),
                            signature = getSignature(named),
                            line = getLineNumber(project, named) ?: 1
                        ))
                    }
                }
                super.visitElement(element)
            }
        })

        val distinct = candidates.distinctBy { (element, node) ->
            "${element.textRange.startOffset}:${node.name}:${node.kind}"
        }.sortedBy { it.first.textRange.startOffset }

        return buildStructureTree(distinct)
    }

    private fun buildStructureTree(items: List<Pair<PsiNamedElement, StructureNode>>): List<StructureNode> {
        fun childrenOf(parent: PsiNamedElement?): List<StructureNode> {
            val parentRange = parent?.textRange
            return items
                .filter { (element, _) ->
                    if (parent == null) {
                        items.none { (candidateParent, _) ->
                            candidateParent != element &&
                                candidateParent.textRange.contains(element.textRange) &&
                                candidateParent.textRange.length > element.textRange.length
                        }
                    } else {
                        parentRange != null &&
                            parent != element &&
                            parentRange.contains(element.textRange) &&
                            items.none { (candidateParent, _) ->
                                candidateParent != parent &&
                                    candidateParent != element &&
                                    parentRange.contains(candidateParent.textRange) &&
                                    candidateParent.textRange.contains(element.textRange) &&
                                    candidateParent.textRange.length > element.textRange.length
                            }
                    }
                }
                .map { (element, node) -> node.copy(children = childrenOf(element)) }
        }
        return childrenOf(null)
    }
}

private val CSHARP_EXTENSIONS = setOf("cs", "csx")
private val FSHARP_EXTENSIONS = setOf("fs", "fsi", "fsx")
private val CSHARP_LANGUAGE_IDS = setOf("C#", "CSHARP", "CSharp", "CSharpLanguage")
private val FSHARP_LANGUAGE_IDS = setOf("F#", "FSHARP", "FSharp", "FSharpLanguage")

class CSharpTypeHierarchyHandler : DotNetTypeHierarchyHandler("C#", "C#", CSHARP_EXTENSIONS, CSHARP_LANGUAGE_IDS)
class CSharpImplementationsHandler : DotNetImplementationsHandler("C#", "C#", CSHARP_EXTENSIONS, CSHARP_LANGUAGE_IDS)
class CSharpCallHierarchyHandler : DotNetCallHierarchyHandler("C#", "C#", CSHARP_EXTENSIONS, CSHARP_LANGUAGE_IDS)
class CSharpSuperMethodsHandler : DotNetSuperMethodsHandler("C#", "C#", CSHARP_EXTENSIONS, CSHARP_LANGUAGE_IDS)
class CSharpStructureHandler : DotNetStructureHandler("C#", "C#", CSHARP_EXTENSIONS, CSHARP_LANGUAGE_IDS)

class FSharpTypeHierarchyHandler : DotNetTypeHierarchyHandler("F#", "F#", FSHARP_EXTENSIONS, FSHARP_LANGUAGE_IDS)
class FSharpImplementationsHandler : DotNetImplementationsHandler("F#", "F#", FSHARP_EXTENSIONS, FSHARP_LANGUAGE_IDS)
class FSharpCallHierarchyHandler : DotNetCallHierarchyHandler("F#", "F#", FSHARP_EXTENSIONS, FSHARP_LANGUAGE_IDS)
class FSharpSuperMethodsHandler : DotNetSuperMethodsHandler("F#", "F#", FSHARP_EXTENSIONS, FSHARP_LANGUAGE_IDS)
class FSharpStructureHandler : DotNetStructureHandler("F#", "F#", FSHARP_EXTENSIONS, FSHARP_LANGUAGE_IDS)
