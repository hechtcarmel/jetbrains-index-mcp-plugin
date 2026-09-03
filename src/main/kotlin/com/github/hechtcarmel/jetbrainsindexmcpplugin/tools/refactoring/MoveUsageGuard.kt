package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * Observes the files an IDE file move is about to rewrite and repairs damage the move
 * leaves behind.
 *
 * `MoveFilesOrDirectoriesProcessor` re-binds every reference to the moved file's declarations
 * while it runs `performRefactoring`. That rewrite is a black box to the tool: the language
 * handler decides what to do with each usage, and the tool only sees the resulting documents.
 * A guard gets the list of files the processor is about to touch *before* the rewrite and a
 * callback *after* it, inside the same write action, so it can compare the two states and
 * put back what should never have been removed.
 *
 * Implementations are language-specific and may reference language-plugin PSI; the interface
 * itself only uses platform types so the move tool stays loadable in every IDE.
 */
internal interface MoveUsageGuard {

    /**
     * Called inside the move's write action, immediately before references are retargeted.
     *
     * @param usageFiles every file that holds a usage the processor is going to rewrite. The
     *   moved file itself can be among them when it references its own declarations.
     */
    fun beforeRetarget(usageFiles: Collection<PsiFile>)

    /**
     * Called inside the same write action, after every usage has been retargeted.
     *
     * @return one entry per file the guard had to repair; empty when the move left every
     *   snapshotted file intact.
     */
    fun afterRetarget(): List<RestoredImports>
}

/**
 * Imports the guard put back into [file] because the move removed them.
 *
 * @param imports the import texts as they appear in the language, e.g. `com.example.pkg.*`.
 */
internal data class RestoredImports(val file: VirtualFile, val imports: List<String>)
