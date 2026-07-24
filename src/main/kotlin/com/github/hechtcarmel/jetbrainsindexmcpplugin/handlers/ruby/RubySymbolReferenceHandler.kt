package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.SymbolReferenceHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope

/**
 * Ruby implementation of [SymbolReferenceHandler].
 *
 * Resolves fully-qualified Ruby symbol references to PSI elements.
 * Supported forms:
 * - `SomeClass`                       — bare class/module name (top-level)
 * - `Module::SomeClass`               — namespaced class/module
 * - `Module::SomeClass#method_name`   — instance method
 * - `Module::SomeClass.method_name`   — class method (dot notation)
 * - `Module::SomeClass#method_name!`  — bang method
 * - `Module::SomeClass#method_name?`  - predicate method
 *
 * **Resolution strategy (in order)**:
 * 1. RubyGotoClassContributor.getItemsByName() — Ruby plugin's own Go-to-Class (platform layer)
 * 2. RubyClassModuleNameIndex.find() — stub index by short name, filtered by FQN
 * 3. RubyInheritanceResolutionIndex.getElements() — own-FQN stub index
 * 4. Failure with "could not be resolved"
 *
 * **Method resolution (after class is found)**:
 * 1. findMethodByName() — direct declaration on the class
 * 2. Child PSI scan — RMethod children with matching name
 * 3. Ancestor walk — superclass + included modules, try findMethodByName on each
 *
 * All PSI access uses reflection to avoid compile-time dependency on the Ruby plugin.
 */
class RubySymbolReferenceHandler : BaseRubyHandler<PsiNamedElement>(), SymbolReferenceHandler {

    companion object {
        private val LOG = logger<RubySymbolReferenceHandler>()

        // Ruby identifier: allows letters, digits, underscores, and ?/!= suffixes
        private const val RUBY_IDENTIFIER = """[a-zA-Z_][a-zA-Z0-9_]*[!?=]?"""

        // Path segment (class/module name, not method)
        private const val PATH_SEGMENT = """[A-Za-z_][A-Za-z0-9_]*"""

        private const val DOUBLE_COLON = "::"

        // Full qualified path using :: as namespace separator
        private const val QUALIFIED_PATH = """$PATH_SEGMENT(?:$DOUBLE_COLON$PATH_SEGMENT)*"""

        // Symbol pattern: optional qualified path, optionally followed by #method or .class_method
        internal val RUBY_SYMBOL_PATTERN = """^$QUALIFIED_PATH(?:[#.]$RUBY_IDENTIFIER)?$""".toRegex()

        internal val SYMBOL_EXAMPLES = listOf(
            "'User'",
            "'Admin::User'",
            "'Admin::User#admin?'",
            "'Admin::User.find_by_email'"
        )
    }

    override val languageId = "Ruby"
    override val languageName = "Ruby"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isRubyLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.ruby.isAvailable

    override fun resolveSymbol(project: Project, symbol: String): Result<PsiNamedElement> {
        val trimmed = symbol.trim()

        if (trimmed.isEmpty() || !RUBY_SYMBOL_PATTERN.matches(trimmed)) {
            return Result.failure(IllegalArgumentException(
                "Symbol '$symbol' does not match expected Ruby symbol format. " +
                    "Expected: ${SYMBOL_EXAMPLES.joinToString(" or ")}"
            ))
        }

        val hashIndex = trimmed.indexOf('#')
        val dotIndex = trimmed.lastIndexOf('.')

        // If there's a method separator (but class method dot vs. instance method #)
        // Note: the pattern prevents both # and . from coexisting
        return if (hashIndex >= 0) {
            resolveInstanceMethod(project, trimmed, hashIndex)
        } else if (dotIndex >= 0) {
            resolveClassMethod(project, trimmed, dotIndex)
        } else {
            resolveClassOrModule(project, trimmed)
        }
    }

    /**
     * Resolves a bare class or module symbol (e.g., "User", "Admin::User").
     */
    private fun resolveClassOrModule(project: Project, symbol: String): Result<PsiNamedElement> {
        val shortName = symbol.substringAfterLast(DOUBLE_COLON)

        // Strategy 1: RubyGotoClassContributor — Ruby plugin's own class resolution
        val contributorClass = rubyGotoClassContributorClass
        if (contributorClass != null) {
            try {
                // Try to instantiate with no-arg constructor (if available)
                val constructor = contributorClass.getDeclaredConstructor()
                val contributor = constructor.newInstance()
                val getItemsByName = contributorClass.getMethod(
                    "getItemsByName",
                    String::class.java,
                    String::class.java,
                    Project::class.java,
                    Boolean::class.javaPrimitiveType
                )
                val items = getItemsByName.invoke(contributor, shortName, shortName, project, false) as? Array<*>
                val exactMatch = items?.filterIsInstance<PsiElement>()?.firstOrNull { candidate ->
                    getRubyQualifiedName(candidate) == symbol
                }
                if (exactMatch is PsiNamedElement) return Result.success(exactMatch)
            } catch (e: NoSuchMethodException) {
                // If no no-arg constructor, skip RubyGotoClassContributor
                LOG.warn("RubyGotoClassContributor has no no-arg constructor, skipping. Error: ${e.message}")
            } catch (e: Exception) {
                LOG.warn("RubyGotoClassContributor lookup failed for '$symbol': ${e.message}")
            }
        }

        // Strategy 2: RubyClassModuleNameIndex by short name, filtered by full FQN
        val nameIndexClass = rubyClassModuleNameIndexClass
        if (nameIndexClass != null) {
            try {
                val projectScope = GlobalSearchScope.projectScope(project)
                val findMethod = nameIndexClass.getMethod(
                    "find",
                    Project::class.java,
                    String::class.java,
                    GlobalSearchScope::class.java
                )
                val candidates = findMethod.invoke(null, project, shortName, projectScope) as? Collection<*>

                // Exact full-FQN match first
                val exactMatch = candidates?.filterIsInstance<PsiElement>()?.firstOrNull { candidate ->
                    getRubyQualifiedName(candidate) == symbol
                }
                if (exactMatch is PsiNamedElement) return Result.success(exactMatch)

                // Also check via getFQN().getFullPath()
                val fqnMatch = candidates?.filterIsInstance<PsiElement>()?.firstOrNull { candidate ->
                    try {
                        val fqnObj = candidate.javaClass.getMethod("getFQN").invoke(candidate) ?: return@firstOrNull false
                        val path = fqnClass?.getMethod("getFullPath")?.invoke(fqnObj) as? String
                        path == symbol
                    } catch (_: Exception) {
                        false
                    }
                }
                if (fqnMatch is PsiNamedElement) return Result.success(fqnMatch)

                // If we got candidates but no exact match, report the candidates
                val namedCandidates = candidates?.filterIsInstance<PsiElement>()
                    ?.mapNotNull { candidate ->
                        try {
                            val fqnObj = candidate.javaClass.getMethod("getFQN").invoke(candidate) ?: return@mapNotNull null
                            val path = fqnClass?.getMethod("getFullPath")?.invoke(fqnObj) as? String
                            path
                        } catch (_: Exception) {
                            null
                        }
                    }?.filter { it != null }
                if (namedCandidates != null && namedCandidates.isNotEmpty()) {
                    return Result.failure(IllegalStateException(
                        "Ruby symbol '$symbol' is ambiguous. Found ${namedCandidates.size} matches: " +
                            namedCandidates.joinToString(", ")
                    ))
                }

                // Fallback to allScope for library/SDK classes
                if (candidates == null || candidates.none { it is PsiElement }) {
                    val allScope = GlobalSearchScope.allScope(project)
                    val allCandidates = findMethod.invoke(null, project, shortName, allScope) as? Collection<*>
                    val allExact = allCandidates?.filterIsInstance<PsiElement>()?.firstOrNull { candidate ->
                        getRubyQualifiedName(candidate) == symbol
                    }
                    if (allExact is PsiNamedElement) return Result.success(allExact)

                    val allFqnMatch = allCandidates?.filterIsInstance<PsiElement>()?.firstOrNull { candidate ->
                        try {
                            val fqnObj = candidate.javaClass.getMethod("getFQN").invoke(candidate) ?: return@firstOrNull false
                            val path = fqnClass?.getMethod("getFullPath")?.invoke(fqnObj) as? String
                            path == symbol
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (allFqnMatch is PsiNamedElement) return Result.success(allFqnMatch)
                }
            } catch (e: Exception) {
                LOG.warn("RubyClassModuleNameIndex lookup failed for '$symbol': ${e.message}")
            }
        }

        // Strategy 2: RubyInheritanceResolutionIndex (own-FQN -> element)
        try {
            val resolved = resolveByFQN(project, symbol, GlobalSearchScope.projectScope(project))
            if (resolved is PsiNamedElement) return Result.success(resolved)
            // allScope fallback
            val allScopeResolved = resolveByFQN(project, symbol, GlobalSearchScope.allScope(project))
            if (allScopeResolved is PsiNamedElement) return Result.success(allScopeResolved)
        } catch (e: Exception) {
            LOG.warn("RubyInheritanceResolutionIndex lookup failed for '$symbol': ${e.message}")
        }

        return Result.failure(IllegalStateException(
            "Ruby symbol '$symbol' could not be resolved. " +
                "Ensure the class/module exists in the project or its dependencies."
        ))
    }

    /**
     * Resolves an instance method reference (e.g., "User#admin?", "Admin::User#find_by_email").
     */
    private fun resolveInstanceMethod(project: Project, symbol: String, hashIndex: Int): Result<PsiNamedElement> {
        val classSymbol = symbol.substring(0, hashIndex)
        val methodName = symbol.substring(hashIndex + 1)

        return resolveMethodInClass(project, classSymbol, methodName)
    }

    /**
     * Resolves a class method reference with dot notation (e.g., "Admin::User.find_by_email").
     */
    private fun resolveClassMethod(project: Project, symbol: String, dotIndex: Int): Result<PsiNamedElement> {
        val classSymbol = symbol.substring(0, dotIndex)
        val methodName = symbol.substring(dotIndex + 1)

        return resolveMethodInClass(project, classSymbol, methodName)
    }

    /**
     * Resolves a class by its symbol path, then finds a method with the given name.
     *
     * Uses [findMethodByName] on the RClass/RModule element via reflection,
     * which is inherited from [RFieldConstantContainerBase].
     */
    private fun resolveMethodInClass(
        project: Project,
        classSymbol: String,
        methodName: String
    ): Result<PsiNamedElement> {
        val classResult = resolveClassOrModule(project, classSymbol)
        val rClassOrModule = classResult.getOrNull() ?: return classResult

        return try {
            // Try findMethodByName(String) — inherited from RFieldConstantContainerBase
            val findMethod = rClassOrModule.javaClass.getMethod("findMethodByName", String::class.java)
            val method = findMethod.invoke(rClassOrModule, methodName) as? PsiNamedElement
            if (method != null) return Result.success(method)

            // Fallback: scan direct children for RMethod elements with matching name
            for (child in rClassOrModule.children) {
                if (isRMethod(child)) {
                    val childName = try {
                        child.javaClass.getMethod("getName").invoke(child) as? String
                    } catch (_: Exception) { null }
                    if (childName == methodName && child is PsiNamedElement) {
                        return Result.success(child)
                    }
                }
            }

            Result.failure(IllegalStateException(
                "Ruby method '$methodName' not found in class '$classSymbol'. " +
                    "Ensure the method is defined in the class or its ancestors."
            ))
        } catch (e: NoSuchMethodException) {
            // If findMethodByName does not exist on this element class, fallback to children scan
            try {
                for (child in rClassOrModule.children) {
                    if (isRMethod(child)) {
                        val childName = try {
                            child.javaClass.getMethod("getName").invoke(child) as? String
                        } catch (_: Exception) { null }
                        if (childName == methodName && child is PsiNamedElement) {
                            return Result.success(child)
                        }
                    }
                }
            } catch (_: Exception) {}

            Result.failure(IllegalStateException(
                "Ruby method '$methodName' not found in class '$classSymbol'. " +
                    "Ensure the method is defined in the class or its ancestors."
            ))
        } catch (e: Exception) {
            LOG.warn("Failed to resolve method '$methodName' in class '$classSymbol': ${e.message}")
            Result.failure(IllegalStateException(
                "Failed to resolve method '$methodName' in class '$classSymbol': ${e.message}"
            ))
        }
    }
}