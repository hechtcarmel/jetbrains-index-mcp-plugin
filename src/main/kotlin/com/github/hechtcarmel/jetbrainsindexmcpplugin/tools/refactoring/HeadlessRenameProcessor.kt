package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ConflictMessages
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.refactoring.ConflictsDialogBase
import com.intellij.refactoring.RefactoringSettings
import com.intellij.refactoring.rename.RenameMatchingDeclarationInFileProcessor
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.rename.UnresolvableCollisionUsageInfo
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

/**
 * Forces IntelliJ's automatic renamers to apply without opening modal UI.
 */
internal class HeadlessRenameProcessor(
    project: Project,
    private val previewElement: PsiElement,
    private val previewNewName: String,
    private val searchInComments: Boolean,
    private val searchTextOccurrences: Boolean
) : RenameProcessor(project, previewElement, previewNewName, searchInComments, searchTextOccurrences) {

    companion object {
        /**
         * File processors can ask whether to rename a matching declaration from inside
         * prepareRenaming, before the overridable automatic-rename/conflict dialogs. The public
         * matching-declaration API lets preview fail closed before entering that hook, without
         * changing the IDE's shared preferences or silently dropping related declarations.
         * Also used by the tool's effective-filename probe.
         */
        fun checkPreviewPreparationIsHeadless(element: PsiElement) {
            if (element !is PsiFile) return
            val settings = RefactoringSettings.getInstance()
            if (!settings.RENAME_DECLARATION_WHEN_RENAME_FILE ||
                !settings.ASK_FOR_RENAME_DECLARATION_WHEN_RENAME_FILE) return

            // Keep this read action local: the subsequent native preparation may yield the EDT.
            ReadAction.run<RuntimeException> {
                for (processor in RenamePsiElementProcessor.allForElement(element)) {
                    val matchingProcessor = processor as? RenameMatchingDeclarationInFileProcessor ?: continue
                    if (matchingProcessor.findMatchingDeclaration(element) != null) {
                        error(
                            "Renaming '${element.name}' may require an interactive choice for its matching declaration. " +
                                "Choose the related-declaration rename behavior in the IDE and disable its confirmation " +
                                "before retrying the preview, or perform the rename in the IDE."
                        )
                    }
                }
            }
        }
    }

    private val configuredFactories = mutableListOf<AutomaticRenamerFactory>()
    private val previewRenamers = mutableListOf<AutomaticRenamer>()

    /**
     * Sanitized conflict messages collected instead of showing the conflicts dialog.
     * Surfaced by [RenameSymbolTool] as result warnings.
     */
    val capturedConflicts = mutableListOf<String>()

    override fun addRenamerFactory(factory: AutomaticRenamerFactory) {
        configuredFactories.add(factory)
        super.addRenamerFactory(factory)
    }

    /**
     * Mirrors the non-mutating first step of [RenameProcessor.doRun].  It is intentionally
     * separate from [findUsages], because the platform's public findUsages method does not call
     * prepareRenaming itself.
     *
     * Call this from the EDT without an enclosing read action, matching [RenameProcessor.doRun]:
     * language processors may run synchronous progress that temporarily yields the EDT, which is
     * forbidden while a caller-held read lock is active. A processor that cannot prepare its
     * complete rename map must make the caller fail the preview closed rather than report a
     * partial plan.
     */
    fun preparePreviewRenaming() {
        checkPreviewPreparationIsHeadless(previewElement)
        prepareRenaming(previewElement, previewNewName, myAllRenames)
    }

    /**
     * The read-only part of RenameProcessor.findUsages, retaining its public renamers for the
     * subsequent headless plan. Each configured or implicit factory sees the same per-element
     * usages as apply, once. No private platform fields or preprocess/UI hooks are needed.
     * Must run under a background read action.
     */
    fun findPreviewUsages(): Array<UsageInfo> {
        previewRenamers.clear()
        val factories = configuredFactories + AutomaticRenamerFactory.EP_NAME.extensionList.filter { it.optionName == null }
        return myAllRenames.toList().flatMap { (element, newName) ->
            val usages = RenameUtil.findUsages(element, newName, myRefactoringScope,
                searchInComments, searchTextOccurrences, myAllRenames)
            for (factory in factories) {
                if (factory.isApplicable(element)) {
                    previewRenamers.add(factory.createRenamer(element, newName, usages.toList()))
                }
            }
            usages.toList()
        }.distinct().toTypedArray()
    }

    /** Same selection as apply; called on EDT before the background automatic-usage search. */
    fun selectPreviewAutomaticRenames() {
        previewRenamers.filter { it.hasAnythingToRename() }.forEach(::selectSuggestedRenames)
    }

    fun findPreviewAutomaticUsages(): Array<UsageInfo> {
        val usages = mutableListOf<UsageInfo>()
        val skipped = mutableListOf<UnresolvableCollisionUsageInfo>()
        previewRenamers.forEach { it.findUsages(usages, searchInComments, searchTextOccurrences, skipped, myAllRenames) }
        // Skipped collisions must make a preview inapplicable instead of hiding missing edits.
        return (usages + skipped).distinct().toTypedArray()
    }

    /** Like preparePreviewRenaming, language preparation needs EDT without an outer read lock. */
    fun preparePreviewAutomaticRenames(): Map<PsiElement, String> {
        val additional = linkedMapOf<PsiElement, String>()
        for (renamer in previewRenamers) {
            for (element in renamer.elements) {
                val newName = renamer.getNewName(element) ?: continue
                checkPreviewPreparationIsHeadless(element)
                addElement(element, newName)
                prepareRenaming(element, newName, additional)
            }
        }
        myAllRenames.putAll(additional)
        return additional
    }

    fun findPreviewPreparedUsages(additional: Map<PsiElement, String>): Array<UsageInfo> =
        additional.flatMap { (element, newName) ->
            RenameUtil.findUsages(element, newName, myRefactoringScope,
                searchInComments, searchTextOccurrences, myAllRenames).toList()
        }.distinct().toTypedArray()

    private fun selectSuggestedRenames(renamer: AutomaticRenamer) {
        for (element in renamer.elements) {
            val suggestedName = renamer.getNewName(element) ?: continue
            renamer.setRename(element, suggestedName)
        }
    }

    /**
     * Collects the same non-UI rename conflicts used by the processor without calling
     * `preprocessUsages()` or `run()`. The former can open language-specific dialogs and the
     * latter mutates PSI, neither of which is permitted during a dry-run.
     */
    fun collectPreviewConflicts(usages: Array<UsageInfo>): List<String> {
        val conflicts = MultiMap<PsiElement, String>()
        RenameUtil.addConflictDescriptions(usages, conflicts)

        val allRenames = linkedMapOf<PsiElement, String>()
        for (element in elements) {
            allRenames[element] = getNewName(element)
        }
        allRenames.putIfAbsent(previewElement, previewNewName)

        // Java class setName also renames a matching source file. That implicit file change is
        // absent from prepareRenaming's map, but its destination must participate in the preview.
        for ((element, newName) in allRenames.toMap()) {
            if (element.language.id != "JAVA" || element !is PsiClass) continue
            val file = element.parent as? PsiJavaFile ?: continue
            if (file.name == "${element.name}.java") {
                allRenames.putIfAbsent(file, "$newName.java")
            }
        }

        // RenameProcessor's file-existence check is part of its apply path and may display UI or
        // perform VFS work. A preview needs the same fail-closed signal without entering that
        // path, so inspect only the current directory PSI under the caller's read action.
        for ((element, newName) in allRenames) {
            val file = element as? PsiFile ?: continue
            val existingSibling = file.virtualFile?.parent?.findChild(newName)
            if (existingSibling != null && existingSibling != file.virtualFile) {
                conflicts.putValue(
                    file,
                    "Cannot rename '${file.name}' to '$newName': a file or directory with that name already exists in its containing directory."
                )
            }
        }

        for ((element, newName) in allRenames) {
            RenamePsiElementProcessor.forElement(element)
                .findExistingNameConflicts(element, newName, conflicts, allRenames)
        }
        return ConflictMessages.sanitizeAll(conflicts.values())
    }

    override fun isPreviewUsages(usages: Array<out UsageInfo>): Boolean = false

    override fun showAutomaticRenamingDialog(automaticVariableRenamer: AutomaticRenamer): Boolean {
        selectSuggestedRenames(automaticVariableRenamer)
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
