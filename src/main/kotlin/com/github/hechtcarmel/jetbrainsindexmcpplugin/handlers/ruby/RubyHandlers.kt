package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

/**
 * Registration entry point for Ruby language handlers.
 *
 * This class is loaded via reflection when the Ruby plugin is available.
 * It registers all Ruby-specific handlers with the [LanguageHandlerRegistry].
 *
 * ## Ruby PSI Classes Used (via reflection)
 *
 * - `org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.classes.RClass` - Ruby class declarations
 * - `org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.modules.RModule` - Ruby module declarations
 * - `org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.methods.RMethod` - Ruby method declarations
 *
 * ## Ruby Language Semantics
 *
 * - **Inheritance**: `class Child < Parent` (single inheritance only)
 * - **Mixins**: `include MyModule` or `extend MyModule` — Ruby's equivalent of interface implementation
 * - **Modules**: serve as both namespaces and mixins; treated as "interface-like" for
 *   `ide_find_implementations` and `ide_type_hierarchy`
 * - **Super methods**: `super` calls parent class method or nearest included module method
 * - **File structure**: classes, modules, and top-level methods
 *
 * ## Supported Plugin IDs
 *
 * - `org.jetbrains.plugins.ruby` - JetBrains Ruby plugin (RubyMine, IntelliJ IDEA Ultimate)
 */
object RubyHandlers {

    private val LOG = logger<RubyHandlers>()

    /**
     * Registers all Ruby handlers with the registry.
     *
     * Called via reflection from [LanguageHandlerRegistry].
     */
    @JvmStatic
    fun register(registry: LanguageHandlerRegistry) {
        if (!PluginDetectors.ruby.isAvailable) {
            LOG.info("Ruby plugin not available, skipping Ruby handler registration")
            return
        }

        try {
            // Verify Ruby PSI classes are accessible before registering
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.classes.RClass")
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.methods.RMethod")

            registry.registerTypeHierarchyHandler(RubyTypeHierarchyHandler())
            registry.registerCallHierarchyHandler(RubyCallHierarchyHandler())
            registry.registerImplementationsHandler(RubyImplementationsHandler())
            registry.registerSuperMethodsHandler(RubySuperMethodsHandler())
            registry.registerStructureHandler(RubyStructureHandler())
            registry.registerSymbolReferenceHandler(RubySymbolReferenceHandler())

            LOG.info("Registered Ruby handlers (6 handlers)")
        } catch (e: ClassNotFoundException) {
            LOG.warn("Ruby PSI classes not found, skipping registration: ${e.message}")
        } catch (e: Exception) {
            LOG.warn("Failed to register Ruby handlers: ${e.message}")
        }
    }
}

// ── Base handler ─────────────────────────────────────────────────────────────

/**
 * Base class for Ruby handlers with common utilities.
 *
 * Uses reflection to access Ruby PSI classes to avoid compile-time dependencies
 * on the Ruby plugin. This ensures the MCP plugin runs in any JetBrains IDE,
 * and Ruby-specific features activate only when the Ruby plugin is present.
 *
 * ## Reflection Method Name Assumptions
 *
 * The following method names are inferred from the Ruby plugin's PSI conventions:
 * - `getName()` — from PsiNamedElement, present on RClass / RModule / RMethod
 * - `getFullyQualifiedName()` — FQN of a class/module (e.g. "Namespace::ClassName")
 * - `getSuperClass()` — returns the RClass that is this class's direct superclass, or null
 * - `getSuperClassName()` — returns the superclass name as a String before resolution
 * - `getIncludedModules()` — returns a Collection of RModule included via include/extend
 *
 * All reflection calls are wrapped in `runCatching` so wrong/missing method names
 * degrade gracefully to null rather than crashing.
 */
abstract class BaseRubyHandler<T> : LanguageHandler<T> {

    protected val LOG = logger<BaseRubyHandler<*>>()

    // ── Lazy PSI class references (reflection; no compile-time dep) ───────────

    internal val rClassClass: Class<*>? by lazy {
        loadClass("org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.classes.RClass")
    }

    internal val rModuleClass: Class<*>? by lazy {
        loadClass("org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.modules.RModule")
    }

    internal val rMethodClass: Class<*>? by lazy {
        loadClass("org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.methods.RMethod")
    }

    // ── Language / type guards ─────────────────────────────────────────────────

    internal fun isRubyLanguage(element: PsiElement): Boolean =
        element.language.id.equals("ruby", ignoreCase = true)

    internal fun isRClass(e: PsiElement): Boolean = rClassClass?.isInstance(e) == true

    internal fun isRModule(e: PsiElement): Boolean = rModuleClass?.isInstance(e) == true

    internal fun isRMethod(e: PsiElement): Boolean = rMethodClass?.isInstance(e) == true

    internal fun isRClassOrModule(e: PsiElement): Boolean = isRClass(e) || isRModule(e)

    // ── PSI utility methods via reflection ─────────────────────────────────────

    /**
     * Returns the declared name of an RClass, RModule, or RMethod via reflection.
     */
    internal fun getName(e: PsiElement): String? = runCatching {
        e.javaClass.getMethod("getName").invoke(e) as? String
    }.getOrNull()

    /**
     * Returns the fully-qualified name (e.g. "Namespace::ClassName").
     *
     * Primary strategy: walk up the PSI parent chain collecting RClass/RModule names and
     * join them with `::`. This is reliable regardless of whether the Ruby plugin exposes
     * `getFullyQualifiedName()` as a stable API.
     *
     * Fallback: reflection on `getFullyQualifiedName()` / `getFQN()` for any future
     * Ruby plugin version that does expose these methods.
     */
    internal fun getFqn(e: PsiElement): String? {
        // Primary: reconstruct FQN from the PSI ancestor chain.
        val psiDerived = computeFqnFromPsiAncestors(e)
        if (psiDerived != null) return psiDerived

        // Fallback: reflection (may work in some Ruby plugin versions).
        return runCatching {
            e.javaClass.getMethod("getFullyQualifiedName").invoke(e) as? String
        }.recoverCatching {
            e.javaClass.getMethod("getFQN").invoke(e) as? String
        }.getOrNull()
    }

    /**
     * Reconstructs the fully-qualified Ruby name for an RClass or RModule by walking
     * up the PSI ancestor chain and collecting the names of every enclosing
     * RClass/RModule node.
     *
     * Example: `RModule(Concerns) > RModule(Auditable)` → `"Concerns::Auditable"`
     */
    internal fun computeFqnFromPsiAncestors(e: PsiElement): String? {
        if (!isRClassOrModule(e)) return null
        val ownName = getName(e) ?: return null
        val segments = mutableListOf(ownName)
        var parent = e.parent
        while (parent != null) {
            if (isRClassOrModule(parent)) {
                getName(parent)?.let { segments.add(0, it) }
            }
            parent = parent.parent
        }
        return segments.joinToString("::")
    }

    /**
     * Returns the direct superclass element of an RClass, or null.
     * In Ruby, only classes can have a superclass (modules use mixins instead).
     */
    internal fun getSuperClass(rClass: PsiElement): PsiElement? = runCatching {
        rClass.javaClass.getMethod("getSuperClass").invoke(rClass) as? PsiElement
    }.getOrNull()

    /**
     * Returns the superclass name string (before resolution), or null.
     * Useful for building hierarchy signatures when the superclass PSI is unavailable.
     */
    internal fun getSuperClassName(rClass: PsiElement): String? = runCatching {
        rClass.javaClass.getMethod("getSuperClassName").invoke(rClass) as? String
    }.getOrNull()

    /**
     * Returns the list of modules included/extended by this class or module.
     * Ruby: `include MyModule` or `extend MyModule`.
     * These are treated as the Ruby equivalent of interface implementations.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun getIncludedModules(container: PsiElement): List<PsiElement> =
        runCatching {
            val method = container.javaClass.getMethod("getIncludedModules")
            (method.invoke(container) as? Collection<*>)?.filterIsInstance<PsiElement>()
                ?: emptyList()
        }.getOrDefault(emptyList())

    // ── Real Ruby-plugin resolution APIs (reflection) ──────────────────────────
    //
    // The Ruby plugin does NOT expose inheritance via simple PSI getters
    // (RClass has getPsiSuperClass()/getSuperClassFQN(), not getSuperClass();
    // there is no getIncludedModules()). Inheritance, mixins, and overrides are
    // resolved through the plugin's resolution utilities and Symbol model.
    // These helpers call those canonical APIs reflectively and fall back to the
    // direct getters above (used by unit-test stubs / hypothetical older plugins)
    // when the utility classes are unavailable.

    /**
     * Resolves the direct superclass(es) of an RClass via
     * `RubyClassResolveUtil.resolveSuperClass(RClass, anchor)`.
     * Falls back to [getSuperClass] reflection when the util is unavailable.
     */
    internal fun resolveSuperClasses(rClass: PsiElement): List<PsiElement> {
        val viaUtil = runCatching {
            val util = Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.impl.RubyClassResolveUtil")
            val m = util.getMethod("resolveSuperClass", rClassClass, PsiElement::class.java)
            @Suppress("UNCHECKED_CAST")
            (m.invoke(null, rClass, rClass) as? List<PsiElement>)
        }.getOrNull()
        if (!viaUtil.isNullOrEmpty()) return viaUtil
        return listOfNotNull(getSuperClass(rClass))
    }

    /**
     * Resolves modules mixed into [container] via `include`/`prepend`/`extend`,
     * using the Ruby Symbol model (`SymbolUtil.getSymbolByContainer` →
     * `Symbol.getInheritanceInfo`). Falls back to [getIncludedModules] reflection.
     */
    internal fun resolveIncludedModules(container: PsiElement): List<PsiElement> {
        val viaSymbol = runCatching { resolveIncludedModulesViaSymbol(container) }.getOrDefault(emptyList())
        if (viaSymbol.isNotEmpty()) return viaSymbol
        return getIncludedModules(container)
    }

    private fun resolveIncludedModulesViaSymbol(container: PsiElement): List<PsiElement> {
        val elementWithFqn =
            Class.forName("org.jetbrains.plugins.ruby.ruby.codeInsight.resolve.scope.RElementWithFQN")
        if (!elementWithFqn.isInstance(container)) return emptyList()

        val symbolUtil =
            Class.forName("org.jetbrains.plugins.ruby.ruby.codeInsight.symbols.structure.SymbolUtil")
        val symbol = symbolUtil.getMethod("getSymbolByContainer", elementWithFqn)
            .invoke(null, container) ?: return emptyList()

        val symbolClass =
            Class.forName("org.jetbrains.plugins.ruby.ruby.codeInsight.symbols.structure.Symbol")
        val info = symbolClass.getMethod("getInheritanceInfo", PsiElement::class.java)
            .invoke(symbol, container) ?: return emptyList()

        val calls = mutableListOf<PsiElement>()
        for (accessor in listOf("getIncludes", "getPrepends", "getExtends")) {
            (info.javaClass.getMethod(accessor).invoke(info) as? List<*>)
                ?.filterIsInstance<PsiElement>()
                ?.let { calls.addAll(it) }
        }
        return calls.mapNotNull { resolveIncludeCallToModule(it) }.distinct()
    }

    /** Resolves an `include SomeModule` call's argument reference to the module PSI. */
    private fun resolveIncludeCallToModule(call: PsiElement): PsiElement? {
        val resolved = call.references.asSequence().mapNotNull { it.resolve() }.firstOrNull()
            ?: PsiTreeUtil.collectElements(call) { it.reference != null }
                .firstNotNullOfOrNull { it.reference?.resolve() }
            ?: return null
        return if (isRClassOrModule(resolved)) resolved
        else generateSequence(resolved.parent) { it.parent }.firstOrNull { isRClassOrModule(it) }
    }

    /**
     * Returns the parent methods that [method] overrides/implements, via
     * `RubyOverrideImplementUtil.getOverriddenMethods(RMethod)`. This traverses the
     * superclass chain and included modules using the Ruby Symbol model.
     */
    internal fun getOverriddenMethods(method: PsiElement): List<PsiElement> = runCatching {
        val util = Class.forName("org.jetbrains.plugins.ruby.ruby.codeInsight.RubyOverrideImplementUtil")
        @Suppress("UNCHECKED_CAST")
        (util.getMethod("getOverriddenMethods", rMethodClass).invoke(null, method) as? List<PsiElement>)
            ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * Returns classes/modules that subclass [container] or `include`/`extend` it (when it
     * is a module), via `RubyOverrideImplementUtil.getOverridingElements(RContainer)`.
     */
    internal fun getOverridingContainers(container: PsiElement): List<PsiElement> = runCatching {
        val util = Class.forName("org.jetbrains.plugins.ruby.ruby.codeInsight.RubyOverrideImplementUtil")
        val rContainer = Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.holders.RContainer")
        (util.getMethod("getOverridingElements", rContainer).invoke(null, container) as? Collection<*>)
            ?.filterIsInstance<PsiElement>() ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * Returns methods that override [method], via
     * `RubyOverridingMethodsSearch.search(RMethod)`.
     */
    internal fun getOverridingMethods(method: PsiElement): List<PsiElement> = runCatching {
        val search =
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.search.overriding.RubyOverridingMethodsSearch")
        val query = search.getMethod("search", rMethodClass).invoke(null, method)
        (query.javaClass.getMethod("findAll").invoke(query) as? Collection<*>)
            ?.filterIsInstance<PsiElement>() ?: emptyList()
    }.getOrDefault(emptyList())

    // ── Hierarchy helpers ──────────────────────────────────────────────────────

    /**
     * Walks up the PSI tree to find the nearest containing RClass or RModule.
     */
    internal fun findContainer(element: PsiElement): PsiElement? {
        var parent = element.parent
        while (parent != null) {
            if (isRClass(parent) || isRModule(parent)) return parent
            parent = parent.parent
        }
        return null
    }

    /**
     * Finds all direct methods with [name] declared inside [container] (non-recursive).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun findMethodByName(container: PsiElement, name: String): List<PsiElement> {
        val cls = rMethodClass ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(container, cls as Class<PsiElement>)
            .filter { getName(it) == name }
    }

    /**
     * BFS-collects ancestors of [container]: superclass first, then included modules,
     * then their ancestors. Stops at cycle or depth limit.
     */
    internal fun collectAncestors(container: PsiElement, maxAncestors: Int = 20): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        val visited = mutableSetOf<PsiElement>()
        val queue = ArrayDeque<PsiElement>()
        queue.add(container)

        while (queue.isNotEmpty() && result.size < maxAncestors) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue

            if (isRClass(current)) {
                getSuperClass(current)?.let { superClass ->
                    result.add(superClass)
                    queue.add(superClass)
                }
            }
            getIncludedModules(current).forEach { mod ->
                result.add(mod)
                queue.add(mod)
            }
        }

        return result
    }

    // ── Navigation helpers ─────────────────────────────────────────────────────

    internal fun getRelativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String =
        ProjectUtils.getToolFilePath(project, file)

    internal fun getLineNumber(project: Project, element: PsiElement): Int? {
        val containingFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    internal fun getColumnNumber(project: Project, element: PsiElement): Int? {
        val containingFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile) ?: return null
        val lineNumber = document.getLineNumber(element.textOffset)
        return element.textOffset - document.getLineStartOffset(lineNumber) + 1
    }

    // ── Private utilities ──────────────────────────────────────────────────────

    private fun loadClass(fqn: String): Class<*>? = try {
        Class.forName(fqn)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: Exception) {
        null
    }
}

// ── TypeHierarchyHandler ──────────────────────────────────────────────────────

/**
 * Ruby implementation of [TypeHierarchyHandler].
 *
 * For a Ruby class, shows:
 * - **Supertypes**: the superclass (`class Child < Parent`) and any included modules
 * - **Subtypes**: subclasses (via DefinitionsScopedSearch) and classes/modules that include this module
 *
 * Ruby modules are treated as interface analogues; classes that `include`/`extend` a module
 * are reported as subtypes of that module.
 */
class RubyTypeHierarchyHandler : BaseRubyHandler<TypeHierarchyData>(), TypeHierarchyHandler {

    override val languageId = "ruby"

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && isRubyLanguage(element)

    override fun isAvailable(): Boolean = PluginDetectors.ruby.isAvailable && rClassClass != null

    override fun getTypeHierarchy(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean,
    ): TypeHierarchyData? {
        // Accept cursor on an RClass/RModule or inside one
        val target = element.takeIf { isRClassOrModule(it) }
            ?: findContainer(element)?.takeIf { isRClassOrModule(it) }
            ?: return null

        val elementData = toTypeElementData(target, project) ?: return null
        val supertypes = buildSupertypes(target, project)
        val subtypes = buildSubtypes(target, project, scope, excludeGenerated)

        return TypeHierarchyData(element = elementData, supertypes = supertypes, subtypes = subtypes)
    }

    private fun buildSupertypes(element: PsiElement, project: Project): List<TypeElementData> {
        val results = mutableListOf<TypeElementData>()

        // Superclass (classes only — modules don't inherit). Resolved via the Ruby
        // plugin's RubyClassResolveUtil (RClass exposes no getSuperClass()).
        if (isRClass(element)) {
            resolveSuperClasses(element).forEach { sc ->
                toTypeElementData(sc, project)?.let { results.add(it) }
            }
        }

        // Included/prepended/extended modules (both classes and modules can mix in modules),
        // resolved through the Ruby Symbol inheritance model.
        resolveIncludedModules(element).forEach { mod ->
            toTypeElementData(mod, project)?.let { results.add(it) }
        }

        return results.distinctBy { Triple(it.name, it.file, it.line) }
    }

    private fun buildSubtypes(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean,
    ): List<TypeElementData> {
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)
        val results = mutableListOf<TypeElementData>()

        try {
            DefinitionsScopedSearch.search(element, searchScope).forEach(Processor { definition ->
                if (isRClassOrModule(definition)) {
                    toTypeElementData(definition, project)?.let { results.add(it) }
                }
                results.size < 50
            })
        } catch (e: Exception) {
            LOG.debug("DefinitionsScopedSearch failed for Ruby type hierarchy: ${e.message}")
        }

        return results
    }

    private fun toTypeElementData(element: PsiElement, project: Project): TypeElementData? {
        val name = getName(element) ?: return null
        val file = element.containingFile?.virtualFile?.let { getRelativePath(project, it) }
        val line = getLineNumber(project, element)
        val kind = if (isRClass(element)) "CLASS" else "MODULE"

        return TypeElementData(
            name = name,
            qualifiedName = getFqn(element),
            file = file,
            line = line,
            kind = kind,
            language = "Ruby",
        )
    }
}

// ── CallHierarchyHandler ──────────────────────────────────────────────────────

/**
 * Ruby implementation of [CallHierarchyHandler].
 *
 * - **Callers** direction: uses [ReferencesSearch] (platform API, works for any language
 *   that registers a find-usages provider — the Ruby plugin registers `RubyFindUsagesProvider`)
 * - **Callees** direction: best-effort; requires a Ruby call-expression PSI class that is
 *   discovered at runtime. Returns empty if the class is unavailable.
 */
class RubyCallHierarchyHandler : BaseRubyHandler<CallHierarchyData>(), CallHierarchyHandler {

    companion object {
        private const val MAX_RESULTS_PER_LEVEL = 20
        private const val MAX_DEPTH = 3
    }

    override val languageId = "ruby"

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && isRubyLanguage(element)

    override fun isAvailable(): Boolean = PluginDetectors.ruby.isAvailable && rMethodClass != null

    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean,
    ): CallHierarchyData? {
        val method = findContainingRMethod(element) ?: return null
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)

        val elementData = toCallElementData(method, project) ?: return null

        val calls = when (direction.lowercase()) {
            "callers" -> findCallers(method, project, searchScope, depth.coerceIn(1, MAX_DEPTH))
            else -> emptyList() // callees: requires Ruby call-expression class; not yet implemented
        }

        return CallHierarchyData(element = elementData, calls = calls)
    }

    @Suppress("UNCHECKED_CAST")
    private fun findContainingRMethod(element: PsiElement): PsiElement? {
        if (isRMethod(element)) return element
        val cls = rMethodClass ?: return null
        return PsiTreeUtil.getParentOfType(element, cls as Class<out PsiElement>)
    }

    private fun findCallers(
        method: PsiElement,
        project: Project,
        scope: GlobalSearchScope,
        depth: Int,
    ): List<CallElementData> {
        if (depth <= 0) return emptyList()

        val results = mutableListOf<CallElementData>()

        ReferencesSearch.search(method, scope).forEach(Processor { reference ->
            val callerMethod = findContainingRMethod(reference.element)
            if (callerMethod != null) {
                toCallElementData(callerMethod, project)?.let { callData ->
                    val children =
                        if (depth > 1) findCallers(callerMethod, project, scope, depth - 1) else null
                    results.add(callData.copy(children = children))
                }
            }
            results.size < MAX_RESULTS_PER_LEVEL
        })

        return results
    }

    private fun toCallElementData(element: PsiElement, project: Project): CallElementData? {
        val name = getName(element) ?: return null
        val file = element.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: return null
        val line = getLineNumber(project, element) ?: 0
        val column = getColumnNumber(project, element) ?: 1

        return CallElementData(name = name, file = file, line = line, column = column, language = "Ruby")
    }
}

// ── ImplementationsHandler ────────────────────────────────────────────────────

/**
 * Ruby implementation of [ImplementationsHandler].
 *
 * - For an **RClass**: finds all subclasses via [DefinitionsScopedSearch]
 * - For an **RModule**: finds all classes that `include`/`extend` it (module = interface)
 * - For an **RMethod**: finds the containing class/module, finds all subtypes, and
 *   returns methods with the same name declared in each subtype
 */
class RubyImplementationsHandler : BaseRubyHandler<List<ImplementationData>>(), ImplementationsHandler {

    override val languageId = "ruby"

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && isRubyLanguage(element)

    override fun isAvailable(): Boolean = PluginDetectors.ruby.isAvailable && rClassClass != null

    override fun findImplementations(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean,
    ): List<ImplementationData>? {
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)

        // Resolve to a class/module container
        val (target, searchForMethod) = when {
            isRClassOrModule(element) -> element to false
            isRMethod(element) -> (findContainer(element) ?: return null) to true
            else -> {
                // Cursor may be on a keyword or identifier token — walk up to nearest container.
                val container = findContainer(element) ?: return null
                container to false
            }
        }

        val methodName = if (searchForMethod) getName(element) else null

        val results = mutableListOf<ImplementationData>()

        if (searchForMethod && methodName != null) {
            // Methods overriding this method, via RubyOverridingMethodsSearch.
            for (method in getOverridingMethods(element)) {
                toImplementationData(method, project, "METHOD")?.let { results.add(it) }
                if (results.size >= 100) return results
            }
        } else {
            // Classes/modules that subclass or include/extend the target, via
            // RubyOverrideImplementUtil.getOverridingElements. Falls back to
            // DefinitionsScopedSearch when the util yields nothing.
            val subtypes = getOverridingContainers(target).ifEmpty {
                collectSubtypesViaDefinitionsSearch(target, searchScope)
            }
            for (subtype in subtypes) {
                if (!isRClassOrModule(subtype)) continue
                val kind = if (isRClass(subtype)) "CLASS" else "MODULE"
                toImplementationData(subtype, project, kind)?.let { results.add(it) }
                if (results.size >= 100) return results
            }
        }

        return results
    }

    private fun collectSubtypesViaDefinitionsSearch(
        target: PsiElement,
        searchScope: GlobalSearchScope,
    ): List<PsiElement> {
        val subtypes = mutableListOf<PsiElement>()
        try {
            DefinitionsScopedSearch.search(target, searchScope).forEach(Processor { definition ->
                if (isRClassOrModule(definition)) subtypes.add(definition)
                subtypes.size < 100
            })
        } catch (e: Exception) {
            LOG.debug("DefinitionsScopedSearch failed for Ruby implementations: ${e.message}")
        }
        return subtypes
    }

    private fun toImplementationData(element: PsiElement, project: Project, kind: String): ImplementationData? {
        val name = getName(element) ?: return null
        val file = element.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: return null
        val line = getLineNumber(project, element) ?: 0
        val column = getColumnNumber(project, element) ?: 1

        return ImplementationData(name = name, file = file, line = line, column = column, kind = kind, language = "Ruby")
    }
}

// ── SuperMethodsHandler ───────────────────────────────────────────────────────

/**
 * Ruby implementation of [SuperMethodsHandler].
 *
 * Walks up the ancestry chain of the containing class/module (BFS: superclass first,
 * then included modules, then their ancestors) and collects methods with the same name.
 *
 * Ruby modules included via `include` are reported as interface-like containers
 * (`isInterface = true`) in the result.
 */
class RubySuperMethodsHandler : BaseRubyHandler<SuperMethodsData>(), SuperMethodsHandler {

    override val languageId = "ruby"

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && isRubyLanguage(element)

    override fun isAvailable(): Boolean = PluginDetectors.ruby.isAvailable && rMethodClass != null

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        // Accept cursor directly on an RMethod, or anywhere inside one (e.g. on the `def`
        // keyword or the method-name identifier).
        val method = if (isRMethod(element)) element else {
            generateSequence(element.parent) { it.parent }.firstOrNull { isRMethod(it) }
        } ?: return null
        val methodName = getName(method) ?: return null
        val container = findContainer(method) ?: return null

        val methodData = toMethodData(method, project, container) ?: return null
        val hierarchy = mutableListOf<SuperMethodData>()

        // Primary: ask the Ruby plugin for the overridden (parent) methods directly.
        // This walks the superclass chain and included modules via the Symbol model.
        getOverriddenMethods(method).forEachIndexed { index, superMethod ->
            if (hierarchy.size >= 10) return@forEachIndexed
            val superContainer = findContainer(superMethod) ?: superMethod
            toSuperMethodData(superMethod, project, superContainer, index + 1)?.let {
                hierarchy.add(it)
            }
        }

        // Fallback: manual ancestor walk (used when the util is unavailable, e.g. unit tests).
        if (hierarchy.isEmpty()) {
            collectAncestors(container).forEachIndexed { index, ancestor ->
                if (hierarchy.size >= 10) return@forEachIndexed
                findMethodByName(ancestor, methodName).firstOrNull()?.let { superMethod ->
                    toSuperMethodData(superMethod, project, ancestor, index + 1)?.let {
                        hierarchy.add(it)
                    }
                }
            }
        }

        return SuperMethodsData(method = methodData, hierarchy = hierarchy)
    }

    private fun toMethodData(method: PsiElement, project: Project, container: PsiElement): MethodData? {
        val name = getName(method) ?: return null
        val file = method.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: return null
        val line = getLineNumber(project, method) ?: 0
        val column = getColumnNumber(project, method) ?: 1
        val className = getName(container) ?: "?"

        return MethodData(
            name = name,
            signature = name,
            containingClass = className,
            file = file,
            line = line,
            column = column,
            language = "Ruby",
        )
    }

    private fun toSuperMethodData(
        method: PsiElement,
        project: Project,
        container: PsiElement,
        depth: Int,
    ): SuperMethodData? {
        val name = getName(method) ?: return null
        val file = method.containingFile?.virtualFile?.let { getRelativePath(project, it) }
        val line = getLineNumber(project, method)
        val column = getColumnNumber(project, method)
        val className = getName(container) ?: "?"
        val isModule = isRModule(container)

        return SuperMethodData(
            name = name,
            signature = name,
            containingClass = className,
            containingClassKind = if (isModule) "MODULE" else "CLASS",
            file = file,
            line = line,
            column = column,
            // Ruby modules serve as the closest analogue to Java interfaces
            isInterface = isModule,
            depth = depth,
            language = "Ruby",
        )
    }
}

// ── StructureHandler ──────────────────────────────────────────────────────────

/**
 * Ruby implementation of [StructureHandler].
 *
 * Extracts the hierarchical structure of a `.rb` file:
 * - Top-level classes (with nested classes, modules, methods)
 * - Top-level modules (with nested classes, modules, methods)
 * - Top-level standalone method definitions (`def foo; end` at file scope)
 *
 * Uses [PsiTreeUtil.findChildrenOfType] with reflection-resolved Ruby PSI classes.
 */
class RubyStructureHandler : BaseRubyHandler<List<StructureNode>>(), StructureHandler {

    override val languageId = "ruby"

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && isRubyLanguage(element)

    override fun isAvailable(): Boolean = PluginDetectors.ruby.isAvailable && rClassClass != null

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        if (!file.language.id.equals("ruby", ignoreCase = true)) return emptyList()

        val structure = mutableListOf<StructureNode>()

        try {
            // Top-level classes
            rClassClass?.let { cls ->
                @Suppress("UNCHECKED_CAST")
                PsiTreeUtil.findChildrenOfType(file, cls as Class<PsiElement>)
                    .filter { isDirectChildOf(it, file) }
                    .forEach { structure.add(extractContainerStructure(it, project)) }
            }

            // Top-level modules
            rModuleClass?.let { cls ->
                @Suppress("UNCHECKED_CAST")
                PsiTreeUtil.findChildrenOfType(file, cls as Class<PsiElement>)
                    .filter { isDirectChildOf(it, file) }
                    .forEach { structure.add(extractContainerStructure(it, project)) }
            }

            // Top-level standalone methods
            rMethodClass?.let { cls ->
                @Suppress("UNCHECKED_CAST")
                PsiTreeUtil.findChildrenOfType(file, cls as Class<PsiElement>)
                    .filter { isDirectChildOf(it, file) }
                    .forEach { structure.add(extractMethodStructure(it, project)) }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to extract Ruby file structure: ${e.message}")
        }

        return structure.sortedBy { it.line }
    }

    private fun extractContainerStructure(container: PsiElement, project: Project): StructureNode {
        val name = getName(container) ?: "?"
        val line = getLineNumber(project, container) ?: 0
        val kind = if (isRClass(container)) StructureKind.CLASS else StructureKind.MODULE
        val children = mutableListOf<StructureNode>()

        // Nested classes
        rClassClass?.let { cls ->
            @Suppress("UNCHECKED_CAST")
            PsiTreeUtil.findChildrenOfType(container, cls as Class<PsiElement>)
                .filter { isDirectChildOf(it, container) }
                .forEach { children.add(extractContainerStructure(it, project)) }
        }

        // Nested modules
        rModuleClass?.let { cls ->
            @Suppress("UNCHECKED_CAST")
            PsiTreeUtil.findChildrenOfType(container, cls as Class<PsiElement>)
                .filter { isDirectChildOf(it, container) }
                .forEach { children.add(extractContainerStructure(it, project)) }
        }

        // Methods
        rMethodClass?.let { cls ->
            @Suppress("UNCHECKED_CAST")
            PsiTreeUtil.findChildrenOfType(container, cls as Class<PsiElement>)
                .filter { isDirectChildOf(it, container) }
                .forEach { children.add(extractMethodStructure(it, project)) }
        }

        val signature = buildContainerSignature(container)

        return StructureNode(
            name = name,
            kind = kind,
            modifiers = emptyList(),
            signature = signature,
            line = line,
            children = children.sortedBy { it.line },
        )
    }

    private fun extractMethodStructure(method: PsiElement, project: Project): StructureNode {
        val name = getName(method) ?: "?"
        val line = getLineNumber(project, method) ?: 0

        return StructureNode(
            name = name,
            kind = StructureKind.METHOD,
            modifiers = emptyList(),
            signature = name,
            line = line,
        )
    }

    /**
     * Returns true only when [element]'s nearest class/module ancestor is exactly [parent].
     * This prevents double-reporting elements nested inside inner classes.
     */
    private fun isDirectChildOf(element: PsiElement, parent: PsiElement): Boolean {
        var current = element.parent
        while (current != null) {
            if (current == parent) return true
            if (isRClass(current) || isRModule(current)) return false
            current = current.parent
        }
        return false
    }

    private fun buildContainerSignature(container: PsiElement): String {
        val name = getName(container) ?: return ""
        val keyword = if (isRClass(container)) "class" else "module"
        val superName = if (isRClass(container)) getSuperClassName(container) else null
        return if (superName != null) "$keyword $name < $superName" else "$keyword $name"
    }
}

// ── SymbolReferenceHandler ────────────────────────────────────────────────────

/**
 * Ruby implementation of [SymbolReferenceHandler].
 *
 * Resolves Ruby symbol reference strings to PSI elements so that tools like
 * `ide_find_references`, `ide_call_hierarchy`, etc. can accept `language+symbol`
 * instead of `file+line+column`.
 *
 * ## Supported Formats
 *
 * | Format | Example | Resolves to |
 * |--------|---------|-------------|
 * | `ClassName` | `UserService` | RClass |
 * | `Namespace::ClassName` | `Services::UserService` | RClass (namespaced) |
 * | `ModuleName` | `Authenticatable` | RModule |
 * | `ClassName#method_name` | `UserService#find` | RMethod |
 * | `ClassName#method?` | `User#admin?` | RMethod (predicate) |
 * | `ClassName#method!` | `User#save!` | RMethod (bang) |
 *
 * ## Resolution Strategy
 *
 * Uses [RubyGotoClassContributor] (registered by the Ruby plugin) via reflection to look up
 * classes and modules by simple name, then filters by FQN when a namespace is specified.
 * Falls back to `null` (caller should use `file+line+column`) if resolution fails.
 */
class RubySymbolReferenceHandler : BaseRubyHandler<PsiNamedElement>(), SymbolReferenceHandler {

    companion object {
        /**
         * Accepts: ClassName, Namespace::ClassName, ClassName#method, ClassName#method?, ClassName#method!
         * Class/module segments start with uppercase; method names start with lowercase or underscore.
         */
        val RUBY_SYMBOL_PATTERN: Regex = Regex(
            "^[A-Z][A-Za-z0-9_]*(?:::[A-Z][A-Za-z0-9_]*)*(?:#[a-z_][A-Za-z0-9_?!]*)?$"
        )
    }

    override val languageId = "ruby"
    override val languageName = "Ruby"

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && isRubyLanguage(element)

    override fun isAvailable(): Boolean = PluginDetectors.ruby.isAvailable && rClassClass != null

    override fun resolveSymbol(project: Project, symbol: String): Result<PsiNamedElement> {
        if (!RUBY_SYMBOL_PATTERN.matches(symbol)) {
            return Result.failure(
                IllegalArgumentException(
                    "Unsupported Ruby symbol format: '$symbol'. " +
                        "Expected formats: 'ClassName', 'Namespace::ClassName', " +
                        "'ClassName#method_name'. Use file+line+column for other elements."
                )
            )
        }

        val (className, methodName) = parseRubySymbol(symbol)

        val container = findClassOrModuleByName(project, className)
            ?: return Result.failure(
                NoSuchElementException(
                    "Ruby class/module not found: '$className'. Use file+line+column instead."
                )
            )

        if (methodName == null) {
            return Result.success(container as PsiNamedElement)
        }

        val method = findMethodByName(container, methodName).firstOrNull()
            ?: return Result.failure(
                NoSuchElementException(
                    "Method '$methodName' not found in '$className'. Use file+line+column instead."
                )
            )

        return Result.success(method as PsiNamedElement)
    }

    // ── Parsing ────────────────────────────────────────────────────────────────

    /**
     * Splits a Ruby symbol string into (className, methodName?).
     * Example: "Services::UserService#find" → ("Services::UserService", "find")
     */
    internal fun parseRubySymbol(symbol: String): Pair<String, String?> {
        val hashIndex = symbol.indexOf('#')
        return if (hashIndex == -1) {
            symbol to null
        } else {
            symbol.substring(0, hashIndex) to symbol.substring(hashIndex + 1)
        }
    }

    // ── Class/module lookup ────────────────────────────────────────────────────

    private fun findClassOrModuleByName(project: Project, className: String): PsiElement? {
        val simpleName = className.substringAfterLast("::")

        val candidates = lookupViaContributor(project, simpleName)

        // If namespace is specified, filter by FQN using the PSI-ancestor-computed name.
        return if ("::" in className) {
            candidates.firstOrNull { el ->
                // Prefer PSI-derived FQN (reliable); fall back to simple reflection.
                val fqn = getFqn(el) ?: getName(el) ?: return@firstOrNull false
                fqn == className || fqn.endsWith("::$className")
            }
        } else {
            candidates.firstOrNull()
        }
    }

    /**
     * Uses [RubyGotoClassContributor] (via reflection) to resolve a simple class/module name
     * to PSI elements, then falls back to [RubyGotoSymbolContributor] for modules that the
     * class contributor does not index.
     */
    private fun lookupViaContributor(project: Project, simpleName: String): List<PsiElement> {
        val fromClass = lookupViaNamedContributor(
            project, simpleName,
            "org.jetbrains.plugins.ruby.ruby.actions.RubyGotoClassContributor"
        )
        if (fromClass.isNotEmpty()) return fromClass

        // Fallback: RubyGotoSymbolContributor covers modules that GotoClass omits.
        return lookupViaNamedContributor(
            project, simpleName,
            "org.jetbrains.plugins.ruby.ruby.actions.RubyGotoSymbolContributor"
        )
    }

    private fun lookupViaNamedContributor(
        project: Project,
        simpleName: String,
        contributorFqn: String,
    ): List<PsiElement> {
        return try {
            val contributorClass = Class.forName(contributorFqn)
            val contributor = contributorClass.getDeclaredConstructor().newInstance()
            val results = mutableListOf<PsiElement>()
            val params = FindSymbolParameters.simple(project, false)

            val processMethod = contributorClass.methods.firstOrNull { m ->
                m.name == "processElementsWithName" && m.parameterCount == 3
            } ?: return emptyList()

            processMethod.invoke(contributor, simpleName, Processor { item: Any ->
                if (item is PsiElement && isRClassOrModule(item)) {
                    results.add(item)
                }
                results.size < 20
            }, params)

            results
        } catch (e: Exception) {
            LOG.debug("$contributorFqn lookup failed for '$simpleName': ${e.message}")
            emptyList()
        }
    }
}
