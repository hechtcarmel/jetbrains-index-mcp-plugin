package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object BuiltInSearchScopeResolver {

    fun parse(arguments: JsonObject, defaultScope: BuiltInSearchScope): BuiltInSearchScope {
        val rawScope = arguments[ParamNames.SCOPE]?.jsonPrimitive?.content ?: return defaultScope
        return BuiltInSearchScope.fromWireValue(rawScope) ?: throw IllegalArgumentException(
            "Unsupported scope '$rawScope'. Supported values: ${BuiltInSearchScope.supportedWireValues().joinToString(", ")}"
        )
    }

    /**
     * Resolve [scope] to a [GlobalSearchScope].
     *
     * When [excludeGenerated] is true, the result additionally excludes IDE-recognized
     * generated sources (KSP/Dagger/annotation-processor output). This keeps reference
     * results — especially `ide_find_references` on heavily-injected symbols — focused on
     * hand-written code instead of paginating through hundreds of generated DI factories.
     *
     * The default is false (include generated): each tool decides its own default and
     * exposes an `includeGenerated` parameter, so the shared resolver never silently drops
     * generated sources unless a caller opts in. `ide_find_references` defaults to excluding;
     * search and hierarchy tools default to including (e.g. so a type hierarchy still shows a
     * generated supertype).
     */
    fun resolveGlobalScope(
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean = false,
    ): GlobalSearchScope {
        val base = when (scope) {
            BuiltInSearchScope.PROJECT_FILES -> GlobalSearchScope.projectScope(project)
            BuiltInSearchScope.PROJECT_AND_LIBRARIES -> GlobalSearchScope.allScope(project)
            BuiltInSearchScope.PROJECT_PRODUCTION_FILES -> {
                val fileIndex = ProjectRootManager.getInstance(project).fileIndex
                projectContentScope(project) { file ->
                    fileIndex.isInSourceContent(file) && !fileIndex.isInTestSourceContent(file)
                }
            }
            BuiltInSearchScope.PROJECT_TEST_FILES -> {
                val fileIndex = ProjectRootManager.getInstance(project).fileIndex
                projectContentScope(project) { file -> fileIndex.isInTestSourceContent(file) }
            }
        }
        return GeneratedSourcesExcludingScope.wrap(project, base, excludeGenerated)
    }

    fun resolveSearchScope(
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean = false,
    ): SearchScope =
        resolveGlobalScope(project, scope, excludeGenerated)

    private fun projectContentScope(
        project: Project,
        predicate: (VirtualFile) -> Boolean
    ): GlobalSearchScope {
        val baseScope = GlobalSearchScope.projectScope(project)
        return object : DelegatingGlobalSearchScope(baseScope) {
            override fun contains(file: VirtualFile): Boolean = super.contains(file) && predicate(file)
        }
    }
}
