package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope

internal fun shouldIncludeNavigationElement(
    searchScope: GlobalSearchScope,
    element: PsiElement,
): Boolean {
    val file = element.containingFile?.virtualFile ?: return true
    return shouldIncludeNavigationFile(searchScope, file)
}

internal fun shouldIncludeNavigationFile(
    searchScope: GlobalSearchScope,
    file: VirtualFile,
): Boolean = searchScope.contains(file)

internal fun createNavigationSearchScope(
    project: com.intellij.openapi.project.Project,
    scope: BuiltInSearchScope,
    excludeGenerated: Boolean = false,
): GlobalSearchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, scope, excludeGenerated)
