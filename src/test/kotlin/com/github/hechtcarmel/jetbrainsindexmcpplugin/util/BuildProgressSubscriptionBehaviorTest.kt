package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.intellij.build.BuildProgressListener
import com.intellij.build.events.BuildEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.registerOrReplaceServiceInstance
import java.io.File
import java.lang.reflect.Proxy

/**
 * ide_build_project and ide_diagnostics capture build output by reflectively subscribing a
 * BuildProgressListener to the IDE's build-output view managers. Subscribing to
 * `BuildViewManager` alone misses IDEs whose builds publish to their own view-manager service —
 * CLion's CMake builds — which is how issue #213's failed builds came back with an empty
 * message list.
 *
 * The CLion service cannot be on the test classpath, so these tests register a service of the
 * exact same observable shape (`addListener(BuildProgressListener, Disposable)`, the
 * `BuildProgressObservable` surface every `AbstractViewManager` subclass inherits) under an
 * extra class name and drive the production subscription path against it end to end.
 */
class BuildProgressSubscriptionBehaviorTest : McpPlatformTestCase() {

    /**
     * Same observable surface as `com.intellij.build.AbstractViewManager`. The parameter types
     * are the real platform types, so the reflective signature match in
     * [BuildListenerUtils.subscribeToBuildProgressListener] is exercised against the shape it
     * must handle in a real IDE.
     */
    class FakeBuildOutputViewManager {
        private val listeners = mutableListOf<BuildProgressListener>()

        @Suppress("unused") // invoked reflectively by BuildListenerUtils
        fun addListener(listener: BuildProgressListener, disposable: Disposable) {
            listeners.add(listener)
            Disposer.register(disposable, Disposable { listeners.remove(listener) })
        }

        fun fire(buildId: Any, event: BuildEvent) {
            listeners.toList().forEach { it.onEvent(buildId, event) }
        }

        fun hasListeners(): Boolean = listeners.isNotEmpty()
    }

    private fun registerFakeManager(): FakeBuildOutputViewManager {
        val fake = FakeBuildOutputViewManager()
        project.registerOrReplaceServiceInstance(FakeBuildOutputViewManager::class.java, fake, testRootDisposable)
        return fake
    }

    private fun subscribeCapturing(
        classNames: List<String>,
        captured: MutableList<BuildMessage>
    ): Disposable? =
        BuildListenerUtils.subscribeToBuildProgressListener(
            project,
            testRootDisposable,
            viewManagerClassNames = classNames
        ) { _, event ->
            BuildListenerUtils.extractBuildMessage(event, project)?.let { captured.add(it) }
        }

    private fun errorEventAt(relativePath: String, startLine: Int, startColumn: Int, message: String): BuildEvent {
        val filePosition = com.intellij.build.FilePosition(File(project.basePath, relativePath), startLine, startColumn)
        val eventClass = Class.forName("com.intellij.build.events.FileMessageEvent")
        val kind = Class.forName("com.intellij.build.events.MessageEvent\$Kind")
            .enumConstants.first { it.toString() == "ERROR" }
        return Proxy.newProxyInstance(eventClass.classLoader, arrayOf(eventClass)) { proxyObj, method, args ->
            when (method.name) {
                "toString" -> "FileMessageEvent-proxy"
                "hashCode" -> System.identityHashCode(proxyObj)
                "equals" -> proxyObj === args?.get(0)
                "getMessage" -> message
                "getKind" -> kind
                "getFilePosition" -> filePosition
                "getId" -> "event-id"
                "getEventTime" -> 0L
                else -> null
            }
        } as BuildEvent
    }

    fun testEventsFromExtraViewManagerAreCaptured() {
        val fake = registerFakeManager()
        val captured = mutableListOf<BuildMessage>()

        val subscription = subscribeCapturing(
            listOf("com.example.absent.NoSuchViewManager", FakeBuildOutputViewManager::class.java.name),
            captured
        )

        assertNotNull("subscription must succeed via the extra view manager", subscription)
        assertTrue("the fake view manager must have received the listener", fake.hasListeners())

        fake.fire("build-1", errorEventAt("src/main.cpp", 6, 4, "use of undeclared identifier 'foo'"))

        assertEquals("the error event must reach the capture callback", 1, captured.size)
        val message = captured.single()
        assertEquals("ERROR", message.category)
        assertEquals("use of undeclared identifier 'foo'", message.message)
        assertEquals("src/main.cpp", message.file)
        assertEquals("FilePosition is 0-based, BuildMessage 1-based", 7, message.line)
        assertEquals(5, message.column)
    }

    fun testDisposingTheSubscriptionStopsDelivery() {
        val fake = registerFakeManager()
        val captured = mutableListOf<BuildMessage>()

        val subscription = subscribeCapturing(listOf(FakeBuildOutputViewManager::class.java.name), captured)
        assertNotNull(subscription)

        Disposer.dispose(subscription!!)
        fake.fire("build-2", errorEventAt("src/other.cpp", 0, 0, "late event"))

        assertTrue("events after disposal must not be delivered", captured.isEmpty())
    }

    /**
     * The default class-name list must subscribe to the platform's real `BuildViewManager`
     * service — and skip the IDE-specific managers absent from the test IDE without failing.
     * A null here means the reflective lookup no longer matches the real service's
     * `addListener(BuildProgressListener, Disposable)`.
     */
    fun testDefaultSubscriptionAttachesToRealBuildViewManager() {
        val subscription = BuildListenerUtils.subscribeToBuildProgressListener(
            project,
            testRootDisposable
        ) { _, _ -> }

        assertNotNull("default subscription must attach to com.intellij.build.BuildViewManager", subscription)
    }
}
