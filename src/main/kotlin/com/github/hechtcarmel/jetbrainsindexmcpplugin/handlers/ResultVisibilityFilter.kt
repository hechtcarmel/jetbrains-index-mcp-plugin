package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.TestSourcesFilter
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope

/**
 * Shared visibility rules for hierarchy/reference-style tools.
 *
 * Unlike [createFilteredScope], these filters are opt-in and only applied when the
 * caller explicitly disables libraries and/or tests. That preserves existing tool
 * behaviour when both flags are left at their new default value (`true`).
 */
private class VisibilityFilteredScope(
    baseScope: GlobalSearchScope,
    private val project: Project,
    private val includeLibraries: Boolean,
    private val includeTests: Boolean,
) : DelegatingGlobalSearchScope(baseScope) {

    override fun contains(file: VirtualFile): Boolean {
        if (!super.contains(file)) return false
        return shouldIncludeNavigationFile(project, file, includeLibraries, includeTests)
    }
}

internal fun shouldIncludeNavigationElement(
    project: Project,
    element: PsiElement,
    includeLibraries: Boolean,
    includeTests: Boolean,
): Boolean {
    val file = element.containingFile?.virtualFile ?: return true
    return shouldIncludeNavigationFile(project, file, includeLibraries, includeTests)
}

internal fun shouldIncludeNavigationFile(
    project: Project,
    file: VirtualFile,
    includeLibraries: Boolean,
    includeTests: Boolean,
): Boolean {
    if (!includeLibraries) {
        if (ProjectUtils.isProjectFile(project, file)) {
            val relativePath = ProjectUtils.getRelativePath(project, file)
            if (isExcludedPath(relativePath)) return false
        }

        if (ProjectUtils.isDependencyFile(project, file)) return false
        if (!ProjectUtils.isProjectFile(project, file)) return false
    }

    if (!includeTests && TestSourcesFilter.isTestSources(file, project)) {
        return false
    }

    return true
}

internal fun maybeCreateVisibilityFilteredScope(
    baseScope: GlobalSearchScope,
    project: Project,
    includeLibraries: Boolean,
    includeTests: Boolean,
): GlobalSearchScope {
    return if (includeLibraries && includeTests) {
        baseScope
    } else {
        VisibilityFilteredScope(baseScope, project, includeLibraries, includeTests)
    }
}
