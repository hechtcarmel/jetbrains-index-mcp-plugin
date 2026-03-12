package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamer

/**
 * Forces IntelliJ's automatic renamers to apply without opening modal UI.
 */
internal class HeadlessRenameProcessor(
    project: Project,
    element: PsiElement,
    newName: String,
    searchInComments: Boolean,
    searchTextOccurrences: Boolean
) : RenameProcessor(project, element, newName, searchInComments, searchTextOccurrences) {

    override fun showAutomaticRenamingDialog(automaticVariableRenamer: AutomaticRenamer): Boolean {
        for (element in automaticVariableRenamer.elements) {
            val suggestedName = automaticVariableRenamer.getNewName(element) ?: continue
            val namedElement = element as? PsiNamedElement ?: continue
            automaticVariableRenamer.setRename(namedElement, suggestedName)
        }
        return true
    }
}
