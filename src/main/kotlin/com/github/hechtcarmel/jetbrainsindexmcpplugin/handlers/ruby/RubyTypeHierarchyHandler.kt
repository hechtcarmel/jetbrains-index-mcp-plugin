package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.createNavigationSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.shouldIncludeNavigationElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope

/**
 * Ruby implementation of [TypeHierarchyHandler].
 *
 * Produces type hierarchy trees for Ruby classes and modules. Supports:
 * - Single inheritance: `class Child < Parent`
 * - Module mixins: `include`, `extend`
 * - Both RClass and RModule as hierarchy roots
 * - Recursive supertype traversal with cycle detection and max depth guard
 * - Subtype discovery via index enumeration and mixin reverse lookup
 *
 * See [ruby/research/RubyTypeHierarchyHandler.md] for full API analysis.
 */
class RubyTypeHierarchyHandler : BaseRubyHandler<TypeHierarchyData>(), TypeHierarchyHandler {

    companion object {
        private const val MAX_HIERARCHY_DEPTH = 50
        private const val MAX_SUBTYPES = 100
    }

    override val languageId = "Ruby"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isRubyLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.ruby.isAvailable

    override fun getTypeHierarchy(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean
    ): TypeHierarchyData? {
        val rContainer = findContainingRClassOrRModule(element)
            ?: firstClassOrModuleInFile(element)
            ?: return null
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)

        val fqnStr = getRubyQualifiedName(rContainer)
        val nameStr = getName(rContainer) ?: "unknown"

        val supertypes = getSupertypes(project, rContainer, searchScope)
        val subtypes = getSubtypes(project, rContainer, fqnStr, searchScope)

        return TypeHierarchyData(
            element = TypeElementData(
                name = fqnStr ?: nameStr,
                qualifiedName = fqnStr,
                file = rContainer.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, rContainer),
                kind = if (isRClass(rContainer)) "CLASS" else "MODULE",
                language = "Ruby"
            ),
            supertypes = supertypes,
            subtypes = subtypes
        )
    }

    // ── Supertypes ───────────────────────────────────────────────────────────────

    /**
     * When [element] is a whole [PsiFile] (or a leaf with no enclosing class/module),
     * returns the first `RClass`/`RModule` declared in the file, in document order.
     *
     * Production callers always pass a resolved position or a class element, so this
     * only affects whole-file inputs; it lets "type hierarchy of the class in this file"
     * resolve to the primary declaration and yields null for a file with no class/module.
     */
    private fun firstClassOrModuleInFile(element: PsiElement): PsiElement? {
        val file = element as? com.intellij.psi.PsiFile ?: element.containingFile ?: return null
        val rClass = rClassClass ?: return null
        val rModule = rModuleClass ?: return null
        return com.intellij.psi.util.PsiTreeUtil
            .collectElementsOfType(file, PsiElement::class.java)
            .firstOrNull { rClass.isInstance(it) || rModule.isInstance(it) }
    }

    private fun getSupertypes(
        project: Project,
        element: PsiElement,
        searchScope: GlobalSearchScope,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 0
    ): List<TypeElementData> {
        if (depth > MAX_HIERARCHY_DEPTH) return emptyList()

        val fqnStr = getRubyQualifiedName(element)
        val nameStr = getName(element) ?: return emptyList()
        val key = fqnStr ?: nameStr
        if (!visited.add(key)) return emptyList()

        val supertypes = mutableListOf<TypeElementData>()

        // ── Superclass (RClass only) ──────────────────────────────────
        if (isRClass(element)) {
            val superFqn = rClassGetSuperClassFQN(element)
            if (superFqn != null && superFqn !in visited) {
                var superClass = resolveByFQNRelative(project, element, superFqn, searchScope)
                if (superClass == null) {
                    superClass = resolveByFQNRelative(project, element, superFqn, GlobalSearchScope.projectScope(project))
                }
                if (superClass != null && shouldIncludeNavigationElement(searchScope, superClass)) {
                    val superSuper = getSupertypes(project, superClass, searchScope, visited, depth + 1)
                    supertypes.add(TypeElementData(
                        name = getRubyQualifiedName(superClass) ?: getName(superClass) ?: superFqn,
                        qualifiedName = getRubyQualifiedName(superClass),
                        file = superClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, superClass),
                        kind = "CLASS",
                        language = "Ruby",
                        supertypes = superSuper.takeIf { it.isNotEmpty() }
                    ))
                }
            }
        }

        // ── Included / extended / prepended modules ───────────────────
        val mixinFqns = getIncludedModuleFQNs(project, element) +
            getExtendedModuleFQNs(project, element) +
            getPrependedModuleFQNs(project, element)
        for (modFqn in mixinFqns) {
            if (modFqn in visited) continue
            val modElement = resolveByFQNRelative(project, element, modFqn, searchScope)
                ?: resolveByFQNRelative(project, element, modFqn, GlobalSearchScope.projectScope(project))
                ?: continue
            if (!shouldIncludeNavigationElement(searchScope, modElement)) continue
            supertypes.add(TypeElementData(
                name = getRubyQualifiedName(modElement) ?: modFqn,
                qualifiedName = getRubyQualifiedName(modElement),
                file = modElement.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, modElement),
                kind = "MODULE",
                language = "Ruby"
            ))
        }

        return supertypes
    }

    // ── Subtypes ─────────────────────────────────────────────────────────────────

    private fun getSubtypes(
        project: Project,
        element: PsiElement,
        fqnStr: String?,
        searchScope: GlobalSearchScope
    ): List<TypeElementData> {
        val targetFqn = fqnStr ?: return emptyList()
        val results = mutableListOf<TypeElementData>()

        // Strategy 1: Class inheritance — find classes whose superclass FQN matches
        val classSubtypes = findSubtypesForFQN(project, targetFqn, searchScope)
        for (subtype in classSubtypes) {
            if (results.size >= MAX_SUBTYPES) break
            val subName = getRubyQualifiedName(subtype) ?: getName(subtype) ?: continue
            results.add(TypeElementData(
                name = subName,
                qualifiedName = getRubyQualifiedName(subtype),
                file = subtype.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, subtype),
                kind = if (isRClass(subtype)) "CLASS" else "MODULE",
                language = "Ruby"
            ))
        }

        // Strategy 2: Module mixins — classes/modules that include this element
        // Uses RubyOverrideImplementUtil.getOverridingElements (symbol tree, O(1))
        val overridingElements = getOverridingElementsViaOverrideUtil(element, project)
        for (overrider in overridingElements) {
            if (results.size >= MAX_SUBTYPES) break
            if (overrider == element) continue
            val ovName = getRubyQualifiedName(overrider) ?: getName(overrider) ?: continue
            if (results.any { it.qualifiedName == getRubyQualifiedName(overrider) }) continue
            results.add(TypeElementData(
                name = ovName,
                qualifiedName = getRubyQualifiedName(overrider),
                file = overrider.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, overrider),
                kind = if (isRClass(overrider)) "CLASS" else "MODULE",
                language = "Ruby"
            ))
        }

        return results.take(MAX_SUBTYPES)
    }
}