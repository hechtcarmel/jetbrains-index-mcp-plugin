package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.system.measureTimeMillis

class CancellableBlockingActionTest : McpPlatformTestCase() {

    fun testReturnsBlockingResultOffEdt() = runBlocking {
        assertEquals("result", cancellableBlockingAction {
            assertFalse(ApplicationManager.getApplication().isDispatchThread)
            assertNotNull(ProgressManager.getInstance().progressIndicator)
            "result"
        })
    }

    fun testTimeoutInterruptsBlockingWaitAndWaitsForWorkerExit() = runBlocking {
        val entered = AtomicBoolean()
        val exited = AtomicBoolean()
        val elapsed = measureTimeMillis {
            val result = withTimeoutOrNull(300L) {
                cancellableBlockingAction {
                    entered.set(true)
                    try {
                        CountDownLatch(1).await(5, TimeUnit.SECONDS)
                    } finally {
                        exited.set(true)
                    }
                }
            }
            assertNull("The blocking wait must time out", result)
        }
        assertTrue("The blocking operation must actually run", entered.get())
        assertTrue("Cancellation must join the worker before returning", exited.get())
        assertTrue("Cancellation took ${elapsed}ms", elapsed < 3_000L)
    }

    fun testTimeoutCancelsPlatformIndicator() = runBlocking {
        val observedCancellation = AtomicBoolean()
        val elapsed = measureTimeMillis {
            assertNull(withTimeoutOrNull(300L) {
                cancellableBlockingAction {
                    val indicator = requireNotNull(ProgressManager.getInstance().progressIndicator)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    // Deliberately use the indicator, not a suspending coroutine primitive.
                    // An interrupt alone cannot stop this platform-style analysis loop.
                    while (!indicator.isCanceled && System.nanoTime() < deadline) {
                        Thread.interrupted()
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
                    }
                    observedCancellation.set(indicator.isCanceled)
                    indicator.checkCanceled()
                }
            })
        }
        assertTrue("Coroutine cancellation must reach the platform indicator", observedCancellation.get())
        assertTrue("Cancellation took ${elapsed}ms", elapsed < 3_000L)
    }

    fun testIndependentPlatformCancellationIsPreserved() = runBlocking {
        val cancellation = ProcessCanceledException()
        try {
            cancellableBlockingAction<Unit> { throw cancellation }
            fail("Expected platform cancellation")
        } catch (actual: ProcessCanceledException) {
            assertSame(cancellation, actual)
        }
    }

    fun testTimeoutRestoresCancellationWhenPlatformWrapsInterruption() = runBlocking {
        val entered = AtomicBoolean()
        val exited = AtomicBoolean()
        val result = withTimeoutOrNull(300L) {
            cancellableBlockingAction {
                entered.set(true)
                try {
                    CountDownLatch(1).await(5, TimeUnit.SECONDS)
                } catch (interrupted: InterruptedException) {
                    throw RuntimeException("Platform wrapper", IllegalStateException(interrupted))
                } finally {
                    exited.set(true)
                }
            }
        }
        assertNull("The owning timeout must win over a wrapped worker interruption", result)
        assertTrue("The failure injection must actually execute", entered.get())
        assertTrue("The worker must exit before returning", exited.get())
    }

    fun testParentCancellationIsPreservedWhenPlatformWrapsInterruption() = runBlocking {
        withTimeout(5_000L) {
            supervisorScope {
                val entered = CompletableDeferred<Unit>()
                val exited = AtomicBoolean()
                val request = async {
                    cancellableBlockingAction {
                        entered.complete(Unit)
                        try {
                            CountDownLatch(1).await(5, TimeUnit.SECONDS)
                        } catch (interrupted: InterruptedException) {
                            throw RuntimeException("Platform wrapper", interrupted)
                        } finally {
                            exited.set(true)
                        }
                    }
                }
                entered.await()
                request.cancel(CancellationException("Caller cancelled diagnostics"))
                try {
                    request.await()
                    fail("Expected caller cancellation")
                } catch (cancelled: CancellationException) {
                    assertEquals("Caller cancelled diagnostics", cancelled.message)
                }
                assertTrue("Cancellation must join the worker", exited.get())
            }
        }
    }

    fun testActiveCallerKeepsWrappedInterruptionAsWorkerFailure() = runBlocking {
        val failure = RuntimeException("Independent worker failure", InterruptedException())
        try {
            cancellableBlockingAction<Unit> { throw failure }
            fail("Expected the original worker failure")
        } catch (actual: RuntimeException) {
            assertPreservedWorkerFailure(failure, actual)
        }
    }

    fun testActiveCallerKeepsOrdinaryWorkerFailure() = runBlocking {
        val failure = IllegalStateException("Analyzer failed")
        try {
            cancellableBlockingAction<Unit> { throw failure }
            fail("Expected the original worker failure")
        } catch (actual: IllegalStateException) {
            assertPreservedWorkerFailure(failure, actual)
        }
    }

    fun testConcurrentCallsKeepIndependentCancellationState() = runBlocking {
        supervisorScope {
            val entered = CompletableDeferred<Unit>()
            val timingOut = async {
                withTimeoutOrNull(300L) {
                    cancellableBlockingAction {
                        entered.complete(Unit)
                        try {
                            CountDownLatch(1).await(5, TimeUnit.SECONDS)
                        } catch (interrupted: InterruptedException) {
                            throw RuntimeException(interrupted)
                        }
                    }
                }
            }
            withTimeout(5_000L) { entered.await() }
            val failure = RuntimeException("Other request", InterruptedException())
            val independent = async {
                runCatching { cancellableBlockingAction<Unit> { throw failure } }
            }
            assertPreservedWorkerFailure(failure, requireNotNull(independent.await().exceptionOrNull()))
            assertNull("Only the owning request timed out", timingOut.await())
        }
    }

    private fun assertPreservedWorkerFailure(expected: Throwable, actual: Throwable) {
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        // Coroutine stack-trace recovery can copy the exception while retaining the original
        // instance in its cause chain. Do not require identity of the recovered outer exception.
        assertTrue("The original worker failure must survive coroutine recovery",
            generateSequence(actual) { it.cause }.any { it === expected })
    }
}
