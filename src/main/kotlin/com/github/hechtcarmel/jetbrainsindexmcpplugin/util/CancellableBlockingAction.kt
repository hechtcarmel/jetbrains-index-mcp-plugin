package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.util.Computable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * Bridges coroutine cancellation to blocking platform APIs that use a ProgressIndicator.
 * Indicator cancellation stops analysis loops; interruption also releases interruptible waits
 * such as WriteAction.computeAndWait. Wait for the worker to exit before releasing its caller's
 * locks, so a timed-out analysis cannot overlap the next main-pass run.
 */
internal suspend fun <T> cancellableBlockingAction(action: () -> T): T = coroutineScope {
    val indicator = ProgressIndicatorBase()
    val cancellationRelay = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            indicator.cancel()
        }
    }
    try {
        runInterruptible(Dispatchers.IO) {
            ProgressManager.getInstance().runProcess(Computable { action() }, indicator)
        }
    } catch (e: Exception) {
        // Platform code may wrap an interrupted wait (or indicator cancellation) in another
        // exception. Restore our coroutine's cancellation, including its owning timeout, before
        // that worker failure can mask it. An active caller keeps the original failure: an
        // InterruptedException in the cause chain alone does not establish a request timeout.
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        cancellationRelay.cancel()
    }
}
