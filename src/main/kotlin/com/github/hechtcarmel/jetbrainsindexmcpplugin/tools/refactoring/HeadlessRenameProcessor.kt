package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ConflictMessages
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.ConflictsDialogBase
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

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

    /**
     * Sanitized conflict messages collected instead of showing the conflicts dialog.
     * Surfaced by [RenameSymbolTool] as result warnings.
     */
    val capturedConflicts = mutableListOf<String>()

    override fun showAutomaticRenamingDialog(automaticVariableRenamer: AutomaticRenamer): Boolean {
        for (element in automaticVariableRenamer.elements) {
            val suggestedName = automaticVariableRenamer.getNewName(element) ?: continue
            val namedElement = element as? PsiNamedElement ?: continue
            automaticVariableRenamer.setRename(namedElement, suggestedName)
        }
        return true
    }

    /**
     * `RenameProcessor.preprocessUsages` builds the conflicts dialog directly through this
     * method (it never routes through the overridable `showConflicts`), and on cancel the
     * whole refactoring silently aborts. Returning a stub that always proceeds keeps the
     * rename headless — matching [showAutomaticRenamingDialog] and `HeadlessMoveProcessor` —
     * while the sanitized conflicts are captured for the tool's result warnings.
     *
     * Public (base is protected) so tests can exercise it: in unit-test mode
     * `preprocessUsages` throws `ConflictsInTestsException` before ever reaching this path.
     */
    public override fun prepareConflictsDialog(
        conflicts: MultiMap<PsiElement, String>,
        usages: Array<out UsageInfo>?
    ): ConflictsDialogBase {
        capturedConflicts.addAll(ConflictMessages.sanitizeAll(conflicts.values()))
        return object : ConflictsDialogBase {
            override fun setCommandName(commandName: String?) {}
            override fun showAndGet(): Boolean = true
            override fun isShowConflicts(): Boolean = false
        }
    }
}
