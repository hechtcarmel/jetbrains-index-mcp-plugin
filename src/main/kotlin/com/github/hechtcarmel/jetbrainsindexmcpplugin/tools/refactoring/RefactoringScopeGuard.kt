package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.RefactoringBundle
import com.intellij.usageView.UsageInfo

/**
 * Pre-flight writability check for the *full* refactoring scope (issue #310).
 *
 * `BaseRefactoringProcessor.run()` routes read-only affected files through
 * `ReadonlyStatusHandler`, which in a real IDE shows a modal "these files are
 * read-only" dialog. A headless MCP call then blocks the EDT until the client
 * times out. Checking every file discovered by `findUsages()` *before* running
 * the processor turns that hang into an actionable error listing the files.
 *
 * The per-target `isWritable` checks in the individual tools still run first;
 * this guard covers referencing files (generated sources, SQL migrations,
 * files from other content roots).
 *
 * The pre-check's usage search must never run on the EDT — route it through
 * [computeUsagesOffEdt] (issue #357).
 */
internal object RefactoringScopeGuard {
    private val LOG = Logger.getInstance(RefactoringScopeGuard::class.java)

    /**
     * Runs the pre-check usage search off the EDT.
     *
     * The Kotlin K2 Analysis API forbids resolution on the EDT, so a `findUsages()`
     * call made directly from the tools' EDT execution phase fails for every Kotlin
     * target with "Analysis is not allowed: Called in the EDT thread" (issue #357).
     * This runs the search the same way `BaseRefactoringProcessor.doRun()` runs its
     * own: called on the EDT, the search executes on a pooled thread under a read
     * action while a modal progress pumps events (so no new PSI-mutation window
     * opens between processor setup and `run()`); called on a background thread, it
     * executes under a plain read action.
     *
     * Returns null when the search is cancelled — callers fail open, skipping the
     * pre-check, because `run()` immediately repeats the same search under its own
     * cancellable progress. Search failures propagate to the caller unchanged.
     */
    fun computeUsagesOffEdt(project: Project, findUsages: () -> Array<UsageInfo>?): Array<UsageInfo>? {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            return ReadAction.compute<Array<UsageInfo>?, RuntimeException> { findUsages() }
        }
        val search = ThrowableComputable<Array<UsageInfo>?, RuntimeException> {
            ReadAction.compute<Array<UsageInfo>?, RuntimeException> { findUsages() }
        }
        return try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                search,
                RefactoringBundle.message("progress.text"),
                true,
                project
            )
        } catch (_: ProcessCanceledException) {
            LOG.info("Read-only scope pre-check cancelled — proceeding without it")
            null
        }
    }

    /** Relative paths of read-only files among the usages' containing files. */
    fun readOnlyFilesIn(project: Project, usages: Array<UsageInfo>): List<String> =
        usages.asSequence()
            .mapNotNull { it.virtualFile }
            .distinct()
            .filterNot { it.isWritable }
            .map { ProjectUtils.getToolFilePath(project, it) }
            .distinct()
            .sorted()
            .toList()

    /**
     * Invokes the processor's `findUsages()` reflectively. `BaseRefactoringProcessor`
     * declares it protected and some subclasses (e.g. `ChangeSignatureProcessorBase`)
     * keep it that way. Returns null when reflection fails — callers fail open,
     * keeping the tool functional at the cost of the pre-check.
     */
    fun findUsagesReflectively(processor: BaseRefactoringProcessor): Array<UsageInfo>? {
        return try {
            var cls: Class<*>? = processor.javaClass
            while (cls != null) {
                val method = try {
                    cls.getDeclaredMethod("findUsages")
                } catch (_: NoSuchMethodException) {
                    cls = cls.superclass
                    continue
                }
                method.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                return method.invoke(processor) as? Array<UsageInfo>
            }
            null
        } catch (e: Exception) {
            LOG.warn("Read-only scope pre-check skipped — findUsages() reflection failed: ${e.message}")
            null
        }
    }

    /** Builds the error message for a scope blocked by [readOnlyFiles]. */
    fun blockedMessage(readOnlyFiles: List<String>): String =
        "Blocked by read-only files in the refactoring scope: " +
            readOnlyFiles.joinToString(", ") +
            ". Make these files writable, or remove them from the scope " +
            "(for generated sources: run a clean build and ide_sync_files; " +
            "or exclude the directory from the project's content roots), then retry."
}
