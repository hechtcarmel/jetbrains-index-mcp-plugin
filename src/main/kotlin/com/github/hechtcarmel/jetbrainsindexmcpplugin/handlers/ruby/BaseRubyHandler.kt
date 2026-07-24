package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope

/**
 * Base class for Ruby handlers with common utilities.
 *
 * Uses reflection to access Ruby PSI classes to avoid compile-time dependencies.
 */
abstract class BaseRubyHandler<T> : LanguageHandler<T> {

    companion object {
        /**
         * Extracts module names following a `callName` (`include`/`extend`/`prepend`)
         * from raw source text using the same regex as [getModuleFQNsViaPsiTextWalk].
         *
         * Returns the raw group-1 matches in source order (duplicates preserved;
         * callers dedup). Visible for testing so the tertiary text-scan fallback
         * can be exercised without PSI, an index, or the Ruby plugin.
         */
        internal fun extractModuleNamesFromText(sourceText: String, callName: String): List<String> {
            val pattern = Regex("""\b$callName\s+([A-Z][A-Za-z_:]*)\b""")
            return pattern.findAll(sourceText).map { it.groupValues[1] }.toList()
        }
    }

    protected fun isRubyLanguage(element: PsiElement): Boolean {
        return element.language.id.equals("ruby", ignoreCase = true)
    }

    protected val rClassClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.classes.RClass")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val rModuleClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.modules.RModule")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val rMethodClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.methods.RMethod")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val rCallExpressionClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.callExpressions.RCallExpression")
        } catch (e: ClassNotFoundException) {
            try {
                Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.RCallExpression")
            } catch (e2: ClassNotFoundException) {
                try {
                    Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.RCallExpression")
                } catch (e3: ClassNotFoundException) {
                    null
                }
            }
        }
    }

    protected val rCallClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.methodCall.RCall")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val rContainerClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.holders.RContainer")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val rubyOverrideImplementUtilClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.codeInsight.RubyOverrideImplementUtil")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val rubyGotoClassContributorClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.navigation.RubyGotoClassContributor")
        } catch (e: ClassNotFoundException) {
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

    protected fun isRClass(element: PsiElement): Boolean {
        return rClassClass?.isInstance(element) == true
    }

    protected fun isRModule(element: PsiElement): Boolean {
        return rModuleClass?.isInstance(element) == true
    }

    protected fun isRMethod(element: PsiElement): Boolean {
        return rMethodClass?.isInstance(element) == true
    }

    protected fun findContainingRClassOrRModule(element: PsiElement): PsiElement? {
        if (isRClass(element) || isRModule(element)) return element
        val rClass = rClassClass ?: return null
        val rModule = rModuleClass ?: return null

        @Suppress("UNCHECKED_CAST")
        val classResult = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            element, rClass as Class<out PsiElement>
        )
        if (classResult != null) return classResult

        @Suppress("UNCHECKED_CAST")
        return com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            element, rModule as Class<out PsiElement>
        )
    }

    protected fun findContainingRMethod(element: PsiElement): PsiElement? {
        if (isRMethod(element)) return element
        val rMethod = rMethodClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            element, rMethod as Class<out PsiElement>
        )
    }

    protected fun getName(element: PsiElement): String? {
        return try {
            val method = element.javaClass.getMethod("getName")
            method.invoke(element) as? String
        } catch (e: Exception) {
            null
        }
    }

    protected fun getQualifiedName(element: PsiElement): String? {
        return try {
            val method = element.javaClass.getMethod("getFullyQualifiedName")
            method.invoke(element) as? String
        } catch (e: Exception) {
            null
        }
    }

    // ── Ruby-specific FQN and reflection utilities ───────────────────────────────

    protected val fqnClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.codeInsight.symbols.fqn.FQN")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val rubyInheritanceIndexClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.indexes.RubyInheritanceIndex")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val rubyClassModuleNameIndexClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.indexes.RubyClassModuleNameIndex")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val rubyIncludedExtendedFQNIndexClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.indexes.RubyIncludedExtendedFQNIndex")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val rubyInheritanceResolutionIndexClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.indexes.RubyInheritanceResolutionIndex")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val rubyIncludeExtendCallTypesClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.methodCall.RubyIncludeExtendCallTypes")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val rubyInheritanceResolutionForSuperClassesClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.indexes.RubyInheritanceResolutionIndex\$ForSuperClasses")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    /**
     * Gets the Ruby qualified name for an RClass or RModule element.
     *
     * Tries multiple strategies:
     * 1. `getFQN()` → `getFullPath()` (RClass via RElementWithFQN)
     * 2. `getQualifiedName()` (RModule's direct method)
     * 3. `getFullyQualifiedName()` (fallback)
     * 4. `getName()` (last resort)
     * 5. Reconstruct from containing modules (for nested classes)
     */
    protected fun getRubyQualifiedName(element: PsiElement): String? {
        // Strategy 1: getFQN() -> getFullPath() (works for RClass via RElementWithFQN)
        // Only return if the FQN contains "::" (fully qualified). For short names like "User",
        // fall through to let Strategy 4 (parent walk) reconstruct the namespace-qualified name.
        try {
            val fqnObj = element.javaClass.getMethod("getFQN").invoke(element)
            if (fqnObj != null) {
                val fqn = fqnObj.javaClass.getMethod("getFullPath").invoke(fqnObj) as? String
                if (fqn != null && fqn.contains("::")) return fqn
            }
        } catch (_: Exception) {}

        // Strategy 2: getQualifiedName() (works for RModule)
        try {
            val method = element.javaClass.getMethod("getQualifiedName")
            val result = method.invoke(element) as? String
            if (result != null && result.contains("::")) return result
        } catch (_: Exception) {}

        // Strategy 3: getFullyQualifiedName() (generic fallback), only if it contains "::"
        getQualifiedName(element)?.let { if (it.contains("::")) return it }

        // Strategy 4: Reconstruct from containing modules (for nested classes)
        // When Ruby plugin returns just the short name for nested classes, walk up the PSI tree
        // to find containing modules and build the qualified name.
        // Runs BEFORE bare getName() fallback so namespaced classes get their full FQN.
        val baseName = element.javaClass.getMethod("getName").invoke(element) as? String
        if (baseName != null && baseName.contains("::")) return baseName
        if (baseName != null) {
            val parentModules = mutableListOf<String>()
            var parent = element.parent
            while (parent != null) {
                try {
                    if (rModuleClass?.isInstance(parent) == true) {
                        val parentName = parent.javaClass.getMethod("getName").invoke(parent) as? String
                        if (parentName != null && parentName.isNotEmpty()) {
                            parentModules.add(0, parentName)
                        }
                    }
                } catch (_: Exception) {}
                parent = parent.parent
            }
            if (parentModules.isNotEmpty()) {
                return parentModules.joinToString("::") + "::" + baseName
            }
        }

        // Strategy 5: getName() (last resort)
        getName(element)?.let { if (it.isNotEmpty()) return it }
        
        return null
    }

    /**
     * Reconstructs a fully qualified name from a base name and ancestor module names.
     *
     * The ancestorNames list is in innermost-first order (PSI parent walk order).
     * This matches the unit test expectations for [reconstructFqn].
     *
     * Examples:
     * - `reconstructFqn("User", emptyList())` → "User"
     * - `reconstructFqn("User", listOf("Admin"))` → "Admin::User"
     * - `reconstructFqn("C", listOf("B", "A"))` → "A::B::C"
     */
    protected fun reconstructFqn(name: String, ancestorNames: List<String>): String {
        if (name.isEmpty()) return ancestorNames.joinToString("::")
        if (ancestorNames.isEmpty()) return name
        val prefix = ancestorNames.reversed().joinToString("::")
        return "$prefix::$name"
    }

    /**
     * Gets the superclass FQN string from an RClass via getSuperClassFQN().
     */
    protected fun rClassGetSuperClassFQN(element: PsiElement): String? {
        val fqnClass = fqnClass ?: return null
        return try {
            val method = element.javaClass.getMethod("getSuperClassFQN")
            val fqnObj = method.invoke(element) ?: return null
            // Check for INVALID sentinel: FQN.same(fqn, "")
            val sameMethod = fqnClass.getMethod("same", fqnClass, String::class.java)
            if (sameMethod.invoke(null, fqnObj, "") as? Boolean == true) return null
            fqnClass.getMethod("getFullPath").invoke(fqnObj) as? String
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolves a Ruby element by its OWN FQN string.
     *
     * Strategy 1: RubyClassModuleNameIndex by short name, filtered by full FQN match.
     *   - Always indexes every class/module by short name — reliable.
     *   - Multiple candidates may share the same short name (namespaced classes), so filter.
     *
     * Strategy 2: RubyInheritanceResolutionIndex (own-FQN → element).
     *   - Conditional indexing (shouldSinkTopLevel guard) — may miss some classes.
     *
     * NOTE: Do NOT use RubyInheritanceIndex here — that index maps superclass-short-name →
     * subclass elements (getElements("Animal") returns Dog/Cat that INHERIT from Animal,
     * not Animal itself).
     *
     * Both strategies attempt to find the element by its own FQN. Strategy 1 uses
     * short-name index lookup filtered by full FQN; Strategy 2 uses the
     * RubyInheritanceResolutionIndex which maps FQN → element directly.
     */
    protected fun resolveByFQN(
        project: Project,
        fqnStr: String,
        searchScope: GlobalSearchScope
    ): PsiElement? {
        val shortName = fqnStr.substringAfterLast("::")

        // Strategy 1: RubyClassModuleNameIndex — look up by short name, filter by full FQN
        val nameIndexClass = rubyClassModuleNameIndexClass
        if (nameIndexClass != null) {
            try {
                val findMethod = nameIndexClass.getMethod(
                    "find",
                    Project::class.java,
                    String::class.java,
                    GlobalSearchScope::class.java
                )
                val candidates = findMethod.invoke(null, project, shortName, searchScope) as? Collection<*>
                // Prefer exact full-FQN match
                val exactMatch = candidates?.filterIsInstance<PsiElement>()?.firstOrNull { candidate ->
                    getRubyQualifiedName(candidate) == fqnStr
                }
                if (exactMatch != null) return exactMatch
            } catch (_: Exception) {}
        }

        // Strategy 2: RubyInheritanceResolutionIndex (maps own-FQN → element)
        val resolutionIndexClass = rubyInheritanceResolutionIndexClass
        if (resolutionIndexClass != null) {
            val fqnClass = fqnClass
            if (fqnClass != null) {
                try {
                    val getInstance = resolutionIndexClass.getMethod("getInstance")
                    val instance = getInstance.invoke(null)
                    val of = fqnClass.getMethod("of", String::class.java)
                    val fqnObj = of.invoke(null, fqnStr)
                    val getElements = instance.javaClass.getMethod(
                        "getElements",
                        Project::class.java,
                        SearchScope::class.java,
                        fqnClass
                    )
                    val results = getElements.invoke(instance, project, searchScope, fqnObj) as? Collection<*>
                    val match = results?.firstOrNull() as? PsiElement
                    if (match != null) return match
                } catch (_: Exception) {}
            }
        }

        return null
    }

    /**
     * Resolves a module element from its FQN and builds a [TypeElementData] with kind MODULE.
     *
     * Tries [resolveByFQN] with the given search scope first, then falls back to
     * [GlobalSearchScope.projectScope] to catch modules outside the current scope.
     */
    /**
     * Resolves a class/module reference [refFqn] written at [referrer], honoring Ruby
     * lexical scope: tries the referrer's enclosing namespaces innermost→outermost, then
     * the bare/absolute name.
     *
     * Fixes `class Dog < Animal` inside `module NS`, where the Ruby plugin's
     * `getSuperClassFQN()` returns the unqualified "Animal" but the target's full FQN is
     * "NS::Animal". For a top-level referrer this degrades to a plain [resolveByFQN].
     */
    protected fun resolveByFQNRelative(
        project: Project,
        referrer: PsiElement,
        refFqn: String,
        searchScope: GlobalSearchScope
    ): PsiElement? {
        var ns = getRubyQualifiedName(referrer)?.substringBeforeLast("::", "") ?: ""
        while (ns.isNotEmpty()) {
            resolveByFQN(project, "$ns::$refFqn", searchScope)?.let { return it }
            ns = ns.substringBeforeLast("::", "")
        }
        return resolveByFQN(project, refFqn, searchScope)
    }

    protected fun resolveModuleFqn(
        project: Project,
        modFqn: String,
        searchScope: GlobalSearchScope
    ): PsiElement? {
        var modElement = resolveByFQN(project, modFqn, searchScope)
        if (modElement == null) {
            val projectScope = GlobalSearchScope.projectScope(project)
            modElement = resolveByFQN(project, modFqn, projectScope)
        }
        return modElement
    }

    /**
     * Gets the FQNs of modules included via `include` in this class/module.
     *
     * Uses getIncludedModules() directly via reflection for simplicity and reliability.
     * Falls back to processCallsOfType if getIncludedModules() is not available.
     */
    protected fun getIncludedModuleFQNs(project: Project, element: PsiElement): List<String> {
        val fqnClass = fqnClass ?: return emptyList()
        // Try getIncludedModules() method first. On RClass, returns Collection<RElementWithFQN>.
        // On RModule, may return Collection<String> (FQNs directly) — handle both.
        runCatching {
            val getIncludedModulesMethod = element.javaClass.getMethod("getIncludedModules")
            val modules = getIncludedModulesMethod.invoke(element) as? Collection<*>
            modules?.mapNotNull { mod ->
                when (mod) {
                    is String -> mod.takeIf { it.isNotEmpty() }
                    else -> {
                        runCatching {
                            mod?.javaClass?.getMethod("getFQN")?.let { method ->
                                val fqnObj = method.invoke(mod)
                                if (fqnObj != null) {
                                    fqnClass.getMethod("getFullPath").invoke(fqnObj) as? String
                                } else null
                            } ?: run {
                                mod?.javaClass?.getMethod("getQualifiedName")?.let { method ->
                                    method.invoke(mod) as? String
                                }
                            }
                        }.getOrNull()
                    }
                }
            }?.filter { it != null && it.isNotEmpty() }
        }.getOrNull()?.let { if (it.isNotEmpty()) return it }

        // Fallback: collect RCall PSI descendants (works for both RClass and RModule)
        val viaCollect = getModuleFQNsViaPsiCollection(element, "INCLUDE_CALL")
        if (viaCollect.isNotEmpty()) return viaCollect

        // Last resort: PSI text walk
        return getModuleFQNsViaPsiTextWalk(project, element, "include")
    }
    
    /**
     * Gets module FQNs by collecting RCall PSI descendants and extracting include/extend data.
     *
     * Unlike the removed `processCallsOfType`-based approach (only available on RClass,
     * not RModule), this method uses `PsiTreeUtil.collectElementsOfType` to find all
     * RCall descendants of the element regardless of its type. This makes it work for
     * both RClass and RModule.
     *
     * For each matching RCall, uses [FQNCallType.getCallData] to extract resolved FQNs,
     * the same reflection path confirmed by decompilation.
     */
    private fun getModuleFQNsViaPsiCollection(
        element: PsiElement,
        callTypeFieldName: String  // "INCLUDE_CALL" or "EXTEND_CALL"
    ): List<String> {
        val rCall = rCallClass ?: return emptyList()
        val callTypesClass = rubyIncludeExtendCallTypesClass ?: return emptyList()
        val fqn = fqnClass ?: return emptyList()
        return try {
            val callTypeField = callTypesClass.getField(callTypeFieldName)
            val callTypeInstance = callTypeField.get(null)

            val getCallDataMethod = callTypeInstance.javaClass.methods
                .firstOrNull { it.name == "getCallData" && it.parameterCount == 1 }
                ?: return emptyList()

            @Suppress("UNCHECKED_CAST")
            val calls = com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(
                element, rCall as Class<out PsiElement>
            )

            val results = mutableListOf<String>()
            val expectedCommand = when (callTypeFieldName) {
                "INCLUDE_CALL" -> "include"
                "EXTEND_CALL" -> "extend"
                "PREPEND_CALL" -> "prepend"
                else -> return emptyList()
            }

            for (call in calls) {
                try {
                    val command = call.javaClass.getMethod("getCommand").invoke(call) as? String ?: continue
                    if (command != expectedCommand) continue

                    val fqns = getCallDataMethod.invoke(callTypeInstance, call) as? Collection<*>
                    fqns?.forEach { fqnObj ->
                        if (fqnObj != null) {
                            val path = fqn.getMethod("getFullPath").invoke(fqnObj) as? String
                            if (path != null) results.add(path)
                        }
                    }
                } catch (_: Exception) {}
            }

            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Gets the FQNs of modules extended via `extend` in this class/module.
     */

    protected fun getExtendedModuleFQNs(project: Project, element: PsiElement): List<String> {
        // Primary: collect RCall PSI descendants (works for both RClass and RModule)
        val viaCollect = getModuleFQNsViaPsiCollection(element, "EXTEND_CALL")
        if (viaCollect.isNotEmpty()) return viaCollect

        // Last resort: PSI text walk
        return getModuleFQNsViaPsiTextWalk(project, element, "extend")
    }

    /**
     * Gets the FQNs of modules prepended via `prepend` in this class/module.
     *
     * Mirrors [getExtendedModuleFQNs] with PREPEND_CALL, so that `prepend Auditable`
     * shows `Auditable` as a supertype.
     */
    protected fun getPrependedModuleFQNs(project: Project, element: PsiElement): List<String> {
        // Primary: collect RCall PSI descendants (works for both RClass and RModule)
        val viaCollect = getModuleFQNsViaPsiCollection(element, "PREPEND_CALL")
        if (viaCollect.isNotEmpty()) return viaCollect

        // Last resort: PSI text walk
        return getModuleFQNsViaPsiTextWalk(project, element, "prepend")
    }

    /**
     * Gets FQNs by scanning PSI descendants for `callName ModuleName` text patterns.
     *
     * Works as tertiary fallback when processCallsOfType reflection fails (e.g., on RModule
     * where the include/extend call processing API may differ from RClass). Resolves each
     * module name via RubyClassModuleNameIndex.
     */
    private fun getModuleFQNsViaPsiTextWalk(
        project: Project,
        element: PsiElement,
        callName: String
    ): List<String> {
        val nameIndexClass = rubyClassModuleNameIndexClass ?: return emptyList()
        val fqnClass = fqnClass ?: return emptyList()
        val results = mutableListOf<String>()
        val seenFqns = mutableSetOf<String>()
        val projectScope = GlobalSearchScope.projectScope(project)

        // Scope the scan to the element's own text, not the containing file,
        // to avoid picking up `include`/`extend`/`prepend` calls from unrelated
        // classes/modules in the same file.
        val sourceText: String = element.text

        for (moduleName in extractModuleNamesFromText(sourceText, callName)) {
            if (moduleName !in seenFqns) {
                seenFqns.add(moduleName)
                runCatching {
                    val findMethod = nameIndexClass.getMethod(
                        "find", Project::class.java, String::class.java, GlobalSearchScope::class.java
                    )
                    @Suppress("UNCHECKED_CAST")
                    val candidates = findMethod.invoke(null, project, moduleName, projectScope) as? Collection<*>
                    candidates?.forEach { candidate ->
                        if (candidate is PsiElement) {
                            runCatching {
                                val fqnObj = candidate.javaClass.getMethod("getFQN").invoke(candidate)
                                if (fqnObj != null) {
                                    val path = fqnClass.getMethod("getFullPath").invoke(fqnObj) as? String
                                    if (path != null && path !in seenFqns) {
                                        seenFqns.add(path)
                                        results.add(path)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (results.size >= 20) break
        }

        return results
    }

    /**
     * Finds all classes that inherit from the given FQN.
     *
     * Uses RubyInheritanceIndex which maps superclass-SHORT-NAME → subclass elements.
     * Queries by short name (last segment of fqnStr) to get candidates, then filters
     * by full FQN match on getSuperClassFQN() to handle namespaced superclasses correctly.
     *
     * Falls back to iterating RubyClassModuleNameIndex if RubyInheritanceIndex is unavailable.
     */
    protected fun findSubtypesForFQN(
        project: Project,
        fqnStr: String,
        searchScope: GlobalSearchScope
    ): List<PsiElement> {
        val fqnClass = fqnClass ?: return emptyList()
        val forSuperClassesClass = rubyInheritanceResolutionForSuperClassesClass
        val inheritanceIndexClass = rubyInheritanceIndexClass

        // Fast path: use RubyInheritanceResolutionIndex$ForSuperClasses via StubIndex
        // Maps superclass-FQN → subclass RClass elements directly — no post-filter needed.
        // Uses processElements() because getElements() has overload ambiguity in Kotlin.
        // Also tries "Object::" + fqnStr as fallback since the index stores keys in both forms
        // (e.g., both "File" and "Object::File") — confirmed by index explorer.
        if (forSuperClassesClass != null) {
            val results = try {
                val keyField = forSuperClassesClass.getField("KEY")
                @Suppress("UNCHECKED_CAST")
                val indexKey = keyField.get(null) as? com.intellij.psi.stubs.StubIndexKey<String, PsiElement> ?: null
                if (indexKey != null) {
                    val stubIndex = com.intellij.psi.stubs.StubIndex.getInstance()
                    val directResults = mutableListOf<PsiElement>()
                    stubIndex.processElements(
                        indexKey, fqnStr, project, searchScope,
                        PsiElement::class.java,
                        com.intellij.util.CommonProcessors.CollectProcessor(directResults)
                    )
                    if (directResults.isNotEmpty()) {
                        directResults.take(100)
                    } else {
                        // Fallback: try with "Object::" prefix for top-level classes
                        val prefixedResults = mutableListOf<PsiElement>()
                        stubIndex.processElements(
                            indexKey, "Object::$fqnStr", project, searchScope,
                            PsiElement::class.java,
                            com.intellij.util.CommonProcessors.CollectProcessor(prefixedResults)
                        )
                        if (prefixedResults.isNotEmpty()) {
                            prefixedResults.take(100)
                        } else {
                            // Namespaced fallback: the index keys subclasses by the superclass
                            // reference AS WRITTEN (often unqualified, e.g. "Dog" for
                            // `class ServiceDog < Dog` inside `module NS`). Query by short name,
                            // then confirm each candidate's superclass resolves (lexically) to fqnStr.
                            val shortName = fqnStr.substringAfterLast("::")
                            if (shortName != fqnStr) {
                                val shortResults = mutableListOf<PsiElement>()
                                stubIndex.processElements(
                                    indexKey, shortName, project, searchScope,
                                    PsiElement::class.java,
                                    com.intellij.util.CommonProcessors.CollectProcessor(shortResults)
                                )
                                shortResults.filter { cand ->
                                    val sfqn = rClassGetSuperClassFQN(cand) ?: return@filter false
                                    val resolved = resolveByFQNRelative(project, cand, sfqn, searchScope)
                                        ?: return@filter false
                                    getRubyQualifiedName(resolved) == fqnStr
                                }.take(100)
                            } else emptyList()
                        }
                    }
                } else null
            } catch (_: Exception) { null }
            if (results != null && results.isNotEmpty()) return results
        }

        // Secondary: fall back to RubyInheritanceIndex (maps FQN of class → element)
        // Requires post-filter by superclass FQN to find subclasses correctly.
        if (inheritanceIndexClass != null) {
            try {
                val getInstanceMethod = inheritanceIndexClass.getMethod("getInstance")
                val instance = getInstanceMethod.invoke(null)

                val shortName = fqnStr.substringAfterLast("::")
                val ofMethod = fqnClass.getMethod("of", String::class.java)
                val fqnObj = ofMethod.invoke(null, shortName)

                val getElementsMethod = instance.javaClass.getMethod(
                    "getElements",
                    Project::class.java,
                    SearchScope::class.java,
                    fqnClass
                )
                val candidates = getElementsMethod.invoke(instance, project, searchScope, fqnObj) as? Collection<*>

                val results = candidates?.filterIsInstance<PsiElement>()?.filter { candidate ->
                    rClassGetSuperClassFQN(candidate) == fqnStr
                }?.take(100)
                if (results != null && results.isNotEmpty()) return results
            } catch (_: Exception) {}
        }

        // Tertiary fallback: iterate all class/module names via RubyClassModuleNameIndex
        val indexClass = rubyClassModuleNameIndexClass ?: return emptyList()
        return try {
            val stubIndex = com.intellij.psi.stubs.StubIndex.getInstance()
            val keyField = indexClass.getField("KEY")
            val key = keyField.get(null) as? com.intellij.psi.stubs.StubIndexKey<String, *> ?: return emptyList()

            val results = mutableListOf<PsiElement>()
            val allKeys = stubIndex.getAllKeys(key, project)

            for (name in allKeys) {
                if (results.size >= 100) break
                try {
                    val findMethod = indexClass.getMethod(
                        "find",
                        Project::class.java,
                        String::class.java,
                        GlobalSearchScope::class.java
                    )
                    val candidates = findMethod.invoke(null, project, name, searchScope) as? Collection<*>
                    candidates?.forEach { candidate ->
                        if (results.size >= 100) return@forEach
                        if (candidate is PsiElement) {
                            val superFqn = rClassGetSuperClassFQN(candidate)
                            if (superFqn == fqnStr) {
                                results.add(candidate)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Finds all classes/modules that include a module with the given FQN.
     *
     * Uses RubyIncludedExtendedFQNIndex.getOnIncludedElements().
     */

    /**
     * Finds all classes/modules that include/extend/prepend the given module,
     * using the Ruby plugin's symbol system (same as IDE's "Find Implementations").
     *
     * Calls [RubyOverrideImplementUtil.getOverridingElements] via reflection.
     * This is backed by the indexed symbol tree (v2 ClassModuleSymbol) and is
     * instant — unlike the exhaustive [RubyClassModuleNameIndex] scan.
     *
     * Returns the containing RContainer elements (RClass or RModule) that
     * include, extend, or prepend the target module.
     */
    protected fun getOverridingElementsViaOverrideUtil(
        element: PsiElement,
        project: Project
    ): List<PsiElement> {
        val utilClass = rubyOverrideImplementUtilClass ?: return emptyList()
        val containerClass = rContainerClass ?: return emptyList()

        if (!containerClass.isInstance(element)) return emptyList()

        return try {
            val method = utilClass.getMethod("getOverridingElements", containerClass)
            val result = method.invoke(null, containerClass.cast(element)) as? Collection<*>
            result?.filterIsInstance<PsiElement>()?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Gets the overridden methods (parent methods) of the given RMethod via
     * [RubyOverrideImplementUtil.getOverriddenMethods].
     *
     * Returns the RMethod elements that the given method overrides (e.g., parent class
     * methods with the same name). Walks up the inheritance chain via the indexed
     * symbol tree — instant, O(1).
     *
     * @param rMethod The RMethod PSI element to find overridden methods for
     * @return List of overridden RMethod elements
     */
    protected fun getOverriddenMethodsViaOverrideUtil(
        rMethod: PsiElement
    ): List<PsiElement> {
        val utilClass = rubyOverrideImplementUtilClass ?: return emptyList()
        val rMethodClass = rMethodClass ?: return emptyList()
        if (!rMethodClass.isInstance(rMethod)) return emptyList()

        return try {
            val method = utilClass.getMethod("getOverriddenMethods", rMethodClass)
            val result = method.invoke(null, rMethod) as? Collection<*>
            result?.filterIsInstance<PsiElement>()?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}