package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Abstract base class for all refactoring tools.
 *
 * Provides common utilities for:
 * - Finding named elements at positions
 * - Tracking affected files
 *
 * ## Two-Phase Refactoring Pattern
 *
 * All refactoring tools should follow this pattern to avoid UI freezes:
 *
 * 1. **Background Phase (Read Action)**: Collect all data needed for the refactoring
 *    - Find elements, search for references, etc.
 *    - This can be slow and runs in background
 *
 * 2. **EDT Phase (Write Action)**: Apply pre-computed changes
 *    - Use `withContext(Dispatchers.EDT)` + `WriteCommandAction`
 *    - Should be fast - only apply changes, no searching
 *
 * Example:
 * ```kotlin
 * override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
 *     // Phase 1: Background - collect data
 *     val data = readAction { collectData(project, ...) }
 *
 *     // Phase 2: EDT - apply changes quickly
 *     withContext(Dispatchers.EDT) {
 *         WriteCommandAction.writeCommandAction(project)
 *             .run<Throwable> { applyChanges(data) }
 *     }
 * }
 * ```
 */
abstract class AbstractRefactoringTool : AbstractMcpTool() {

    /**
     * Finds the named element at the given position (or its parent if the element is not named).
     */
    protected fun findNamedElement(
        project: Project,
        file: String,
        line: Int,
        column: Int
    ): PsiNamedElement? {
        val element = findPsiElement(project, file, line, column) ?: return null
        return findNamedElement(element)
    }

    /**
     * Finds the named element from the given PSI element (traverses up if needed).
     * Excludes PsiFile to prevent accidental file deletion when targeting whitespace/comments.
     */
    protected fun findNamedElement(element: PsiElement): PsiNamedElement? {
        if (element is PsiNamedElement && element !is PsiFile && element.name != null) {
            return element
        }
        val parent = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
        // Don't return PsiFile as a deletable element, and ensure it has a name
        return if (parent is PsiFile || parent?.name == null) null else parent
    }

    /**
     * Tracks a file as affected by the refactoring.
     */
    protected fun trackAffectedFile(
        project: Project,
        file: VirtualFile,
        collector: MutableSet<String>
    ) {
        collector.add(getRelativePath(project, file))
    }
}
