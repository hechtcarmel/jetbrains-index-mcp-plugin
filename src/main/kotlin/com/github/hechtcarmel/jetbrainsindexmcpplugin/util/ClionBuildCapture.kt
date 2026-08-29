package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.messages.Topic
import com.intellij.util.ui.UIUtil
import java.lang.reflect.Method

/**
 * Captures CLion's CMake build results (issue #213).
 *
 * CLion's CMake build predates the `com.intellij.build` framework and bypasses it completely:
 * no `AbstractViewManager` receives its events, so the BuildViewManager subscription that covers
 * Gradle/Maven observes nothing, and with no Java plugin the JPS channel is dead too — a failed
 * CLion build used to come back `success: false` with an empty message list. What CLion does
 * expose (verified against CLion 2025.3):
 *
 * - `com.jetbrains.cidr.execution.build.CidrBuildListener.TOPIC` — a project-level message-bus
 *   topic. `beforeStarted(CidrBuildEvent)` / `afterFinished(CidrBuildEvent, CidrBuildResult)`
 *   carry the build id, success/canceled flags, error/warning counts and a summary message —
 *   but no per-file diagnostics.
 * - The build log itself — a `ConsoleViewImpl` inside a Content tab of the "Messages" tool
 *   window (`CMakeBuild.createBuildListenerAndConsole` prints the compiler output there). Its
 *   text is what [BuildOutputParser] needs to produce positioned MSVC/Clang/CMake diagnostics.
 *
 * Both hooks are resolved reflectively (CLion's classes are not on this plugin's classpath and
 * live in CLion's own plugin classloader) and degrade to no-ops in other IDEs.
 */
object ClionBuildCapture {

    private val LOG = logger<ClionBuildCapture>()

    private const val CIDR_BUILD_LISTENER_CLASS = "com.jetbrains.cidr.execution.build.CidrBuildListener"
    private const val CLION_BUILD_UTIL_CLASS = "com.jetbrains.cidr.cpp.execution.build.CLionBuildUtil"

    /** Where CLion puts its build log tabs ([com.intellij.openapi.wm.ToolWindowId.MESSAGES_WINDOW]). */
    private const val MESSAGES_TOOL_WINDOW_ID = "Messages"

    private val cidrBuildListenerClass: Class<*>? by lazy {
        BuildListenerUtils.findClassAcrossPlugins(CIDR_BUILD_LISTENER_CLASS)
    }

    private val buildSessionIdMethod: Method? by lazy {
        try {
            BuildListenerUtils.findClassAcrossPlugins(CLION_BUILD_UTIL_CLASS)
                ?.getMethod("getBuildSessionId", Content::class.java)
        } catch (e: Exception) {
            LOG.debug("CLionBuildUtil.getBuildSessionId not resolvable", e)
            null
        }
    }

    fun isAvailable(): Boolean = cidrBuildListenerClass != null

    /**
     * Subscribes to `CidrBuildListener.TOPIC` on [connection].
     *
     * Returns the [ClionBuildOutcome] accumulator the subscription feeds, or null when CLion's
     * build classes are not present in the running IDE. [onSessionFinished] (optional) fires
     * once per build session — when every cidr build that started has finished.
     */
    @Suppress("UNCHECKED_CAST")
    fun subscribe(
        connection: MessageBusConnection,
        onSessionFinished: ((ClionBuildOutcome) -> Unit)? = null
    ): ClionBuildOutcome? {
        val listenerClass = cidrBuildListenerClass ?: return null
        try {
            val topic = listenerClass.getField("TOPIC").get(null) as? Topic<Any> ?: return null
            val outcome = ClionBuildOutcome()

            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { proxyObj, method, args ->
                when (method.name) {
                    "equals" -> proxyObj === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxyObj)
                    "toString" -> "CidrBuildListener-proxy"
                    "beforeStarted" -> {
                        try {
                            outcome.buildStarted(buildIdOf(args?.getOrNull(0)))
                        } catch (_: Exception) { }
                        null
                    }
                    "afterFinished" -> {
                        try {
                            val result = args?.getOrNull(1)
                            val sessionFinished = outcome.buildFinished(
                                buildId = buildIdOf(args?.getOrNull(0)),
                                succeeded = invokeBoolean(result, "getSucceeded"),
                                canceled = invokeBoolean(result, "getCanceled"),
                                errors = invokeInt(result, "getErrors"),
                                warnings = invokeInt(result, "getWarnings"),
                                message = invokeString(result, "getMessage")
                            )
                            if (sessionFinished && onSessionFinished != null) {
                                try {
                                    onSessionFinished(outcome)
                                } catch (e: Exception) {
                                    LOG.warn("CLion build session callback failed", e)
                                }
                            }
                        } catch (_: Exception) { }
                        null
                    }
                    else -> null
                }
            }

            connection.subscribe(topic, proxy)
            LOG.debug("Subscribed to CidrBuildListener.TOPIC for CLion build results")
            return outcome
        } catch (e: Exception) {
            LOG.warn("Failed to subscribe to CidrBuildListener.TOPIC", e)
            return null
        }
    }

    /**
     * Collects the text of CLion's build log console(s) from the Messages tool window.
     *
     * Tabs whose build-session id matches one of [outcome]'s build ids are preferred; when none
     * match (id lookup unavailable, or the ids drifted across CLion versions) every console tab
     * in the window is read, since CLion replaces a profile's tab on each new build. Returns at
     * most [maxChars] characters, keeping the tail — compiler errors cluster at the end of a
     * failed build's output.
     */
    fun collectConsoleOutput(project: Project, outcome: ClionBuildOutcome?, maxChars: Int): String {
        val collected = StringBuilder()
        try {
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    val toolWindow = ToolWindowManager.getInstance(project)
                        .getToolWindow(MESSAGES_TOOL_WINDOW_ID) ?: return@invokeAndWait
                    val contents = toolWindow.contentManager.contents
                    val buildIds = outcome?.buildIds() ?: emptyList()
                    val matching = if (buildIds.isEmpty()) {
                        emptyList()
                    } else {
                        contents.filter { content ->
                            val sessionId = sessionIdOf(content)
                            sessionId != null && buildIds.any { it == sessionId }
                        }
                    }
                    for (content in matching.ifEmpty { contents.toList() }) {
                        val text = consoleTextOf(content) ?: continue
                        if (text.isBlank()) continue
                        if (collected.isNotEmpty()) collected.append('\n')
                        collected.append(text)
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to read CLion build console output", e)
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to reach the EDT for CLion build console output", e)
        }
        return if (collected.length > maxChars) {
            collected.substring(collected.length - maxChars)
        } else {
            collected.toString()
        }
    }

    private fun consoleTextOf(content: Content): String? {
        return try {
            val console = UIUtil.findComponentOfType(content.component, ConsoleViewImpl::class.java) ?: return null
            console.flushDeferredText()
            console.text
        } catch (e: Exception) {
            LOG.debug("Failed to read a Messages tool window console", e)
            null
        }
    }

    private fun sessionIdOf(content: Content): Any? =
        try {
            buildSessionIdMethod?.invoke(null, content)
        } catch (_: Exception) {
            null
        }

    private fun buildIdOf(event: Any?): Any? =
        try {
            event?.javaClass?.getMethod("getBuildId")?.invoke(event)
        } catch (_: Exception) {
            null
        }

    private fun invokeBoolean(target: Any?, methodName: String): Boolean? =
        try {
            target?.javaClass?.getMethod(methodName)?.invoke(target) as? Boolean
        } catch (_: Exception) {
            null
        }

    private fun invokeInt(target: Any?, methodName: String): Int? =
        try {
            target?.javaClass?.getMethod(methodName)?.invoke(target) as? Int
        } catch (_: Exception) {
            null
        }

    private fun invokeString(target: Any?, methodName: String): String? =
        try {
            target?.javaClass?.getMethod(methodName)?.invoke(target) as? String
        } catch (_: Exception) {
            null
        }
}
