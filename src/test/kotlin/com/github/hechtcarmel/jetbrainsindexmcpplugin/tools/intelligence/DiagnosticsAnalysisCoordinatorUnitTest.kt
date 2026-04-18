package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import junit.framework.TestCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections

class DiagnosticsAnalysisCoordinatorUnitTest : TestCase() {

    fun testSerializesConcurrentMainPassCallers() = runBlocking {
        val coordinator = DiagnosticsAnalysisCoordinator()
        val started = CompletableDeferred<Unit>()
        val releaseFirstCaller = CompletableDeferred<Unit>()
        val events = Collections.synchronizedList(mutableListOf<String>())

        coroutineScope {
            val first = async {
                coordinator.withMainPassLock {
                    events.add("first-start")
                    started.complete(Unit)
                    releaseFirstCaller.await()
                    events.add("first-end")
                    "first"
                }
            }

            started.await()

            val second = async {
                withTimeout(500) {
                    coordinator.withMainPassLock {
                        events.add("second-start")
                        "second"
                    }
                }
            }

            delay(50)
            assertFalse("Second caller should still be queued behind the global lock", second.isCompleted)

            releaseFirstCaller.complete(Unit)

            assertEquals("first", first.await())
            assertEquals("second", second.await())
        }

        assertEquals(
            listOf("first-start", "first-end", "second-start"),
            events
        )
    }
}
