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

    fun resolveGlobalScope(project: Project, scope: BuiltInSearchScope): GlobalSearchScope = when (scope) {
        BuiltInSearchScope.PROJECT_FILES -> GlobalSearchScope.projectScope(project)
        BuiltInSearchScope.PROJECT_AND_LIBRARIES -> GlobalSearchScope.allScope(project)
        BuiltInSearchScope.PROJECT_PRODUCTION_FILES -> {
            val fileIndex = ProjectRootManager.getInstance(project).fileIndex
            projectContentScope(project) { file ->
                if (fileIndex.isInSourceContent(file)) {
                    !fileIndex.isInTestSourceContent(file)
                } else {
                    !isLikelyTestFile(file)
                }
            }
        }
        BuiltInSearchScope.PROJECT_TEST_FILES -> {
            val fileIndex = ProjectRootManager.getInstance(project).fileIndex
            projectContentScope(project) { file -> fileIndex.isInTestSourceContent(file) || isLikelyTestFile(file) }
        }
    }

    fun resolveSearchScope(project: Project, scope: BuiltInSearchScope): SearchScope =
        resolveGlobalScope(project, scope)

    private fun projectContentScope(
        project: Project,
        predicate: (VirtualFile) -> Boolean
    ): GlobalSearchScope {
        val baseScope = GlobalSearchScope.projectScope(project)
        return object : DelegatingGlobalSearchScope(baseScope) {
            override fun contains(file: VirtualFile): Boolean = super.contains(file) && predicate(file)
        }
    }

    private fun isLikelyTestFile(file: VirtualFile): Boolean {
        val path = file.path.replace('\\', '/')
        return path.contains(".Tests/", ignoreCase = true) ||
            path.contains("/Tests/", ignoreCase = true) ||
            path.contains(".Test/", ignoreCase = true) ||
            path.contains("/Test/", ignoreCase = true)
    }
}
