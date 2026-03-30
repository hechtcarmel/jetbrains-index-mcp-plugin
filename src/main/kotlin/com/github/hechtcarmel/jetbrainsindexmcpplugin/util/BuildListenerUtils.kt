package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.MessageBusConnection

/**
 * Utility for subscribing to IDE build events via reflection.
 *
 * Extracts the shared reflection-based listener setup so that both
 * [com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.BuildProjectTool]
 * and other services can reuse it without duplication.
 */
object BuildListenerUtils {

    private val LOG = logger<BuildListenerUtils>()

    // Cached reflection classes for build events
    private val messageEventClass: Class<*>? by lazy {
        try { Class.forName("com.intellij.build.events.MessageEvent") } catch (_: ClassNotFoundException) { null }
    }
    private val fileMessageEventClass: Class<*>? by lazy {
        try { Class.forName("com.intellij.build.events.FileMessageEvent") } catch (_: ClassNotFoundException) { null }
    }
    private val outputEventClass: Class<*>? by lazy {
        try { Class.forName("com.intellij.build.events.OutputBuildEvent") } catch (_: ClassNotFoundException) { null }
    }

    /**
     * Subscribes to Gradle/Maven build events via [com.intellij.build.BuildProgressListener].
     *
     * Uses reflection to avoid compile-time dependency on the build API.
     * The [onEvent] callback receives both the buildId and the event object.
     *
     * @param project The project to subscribe on
     * @param parentDisposable Parent disposable for lifecycle management
     * @param onEvent Callback invoked for each build event with (buildId, event)
     * @return A [Disposable] to unsubscribe, or null if subscription failed
     */
    fun subscribeToBuildProgressListener(
        project: Project,
        parentDisposable: Disposable,
        onEvent: (buildId: Any, event: Any) -> Unit
    ): Disposable? {
        try {
            val buildViewManagerClass = Class.forName("com.intellij.build.BuildViewManager")
            val listenerClass = Class.forName("com.intellij.build.BuildProgressListener")

            val buildViewManager = project.getService(buildViewManagerClass) ?: return null
            val disposable = Disposer.newDisposable(parentDisposable, "BuildListenerUtils-buildEvents")

            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                if (method.name == "onEvent" && args != null && args.size >= 2) {
                    val buildId = args[0] ?: return@newProxyInstance null
                    val event = args[1] ?: return@newProxyInstance null
                    try {
                        onEvent(buildId, event)
                    } catch (_: Exception) { }
                }
                null
            }

            val addListenerMethod = buildViewManagerClass.methods.find {
                it.name == "addListener" && it.parameterCount == 2
            }
            addListenerMethod?.invoke(buildViewManager, proxy, disposable)

            LOG.debug("Subscribed to BuildProgressListener for build events")
            return disposable
        } catch (e: ClassNotFoundException) {
            LOG.debug("BuildViewManager not available, build events will not be captured")
            return null
        } catch (e: Exception) {
            LOG.warn("Failed to subscribe to BuildProgressListener", e)
            return null
        }
    }

    /**
     * Subscribes to JPS compiler events via [com.intellij.openapi.compiler.CompilationStatusListener].
     *
     * Uses reflection to avoid compile-time dependency on the compiler API.
     * The [onCompilationFinished] callback receives the CompileContext object.
     *
     * @param connection The message bus connection to subscribe on
     * @param onCompilationFinished Callback invoked with the CompileContext when compilation finishes
     * @return true if subscription succeeded, false otherwise
     */
    @Suppress("UNCHECKED_CAST")
    fun subscribeToCompilationStatus(
        connection: MessageBusConnection,
        onCompilationFinished: (compileContext: Any) -> Unit
    ): Boolean {
        try {
            val compilerTopicsClass = Class.forName("com.intellij.openapi.compiler.CompilerTopics")
            val compilationStatusField = compilerTopicsClass.getField("COMPILATION_STATUS")
            val topic = compilationStatusField.get(null) as com.intellij.util.messages.Topic<Any>

            val listenerClass = Class.forName("com.intellij.openapi.compiler.CompilationStatusListener")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                if (method.name == "compilationFinished" && args != null && args.size >= 4) {
                    val compileContext = args.getOrNull(3) ?: return@newProxyInstance null
                    onCompilationFinished(compileContext)
                }
                null
            }

            val subscribeMethod = connection.javaClass.methods.find {
                it.name == "subscribe" && it.parameterCount == 2
            }
            subscribeMethod?.invoke(connection, topic, proxy)

            LOG.debug("Subscribed to CompilationStatusListener for structured build messages")
            return true
        } catch (e: ClassNotFoundException) {
            LOG.debug("CompilerTopics not available (no Java plugin), structured messages will be empty")
            return false
        } catch (e: Exception) {
            LOG.warn("Failed to subscribe to CompilationStatusListener", e)
            return false
        }
    }

    /**
     * Extracts a [BuildMessage] from a build event (MessageEvent/FileMessageEvent).
     *
     * Only extracts ERROR and WARNING messages. Returns null for other event types
     * or severity levels.
     *
     * @param event The build event object
     * @param project The project for relativizing file paths
     * @return A [BuildMessage] if the event is an error/warning, null otherwise
     */
    fun extractBuildMessage(event: Any, project: Project): BuildMessage? {
        val msgEventClass = messageEventClass ?: return null
        val fileMsgEventClass = fileMessageEventClass ?: return null

        if (!msgEventClass.isInstance(event)) return null

        val kindEnum = event.javaClass.getMethod("getKind").invoke(event)
        val kindName = kindEnum.toString()

        if (kindName != "ERROR" && kindName != "WARNING") return null

        val message = event.javaClass.getMethod("getMessage").invoke(event) as? String ?: return null
        var file: String? = null
        var line: Int? = null
        var column: Int? = null

        if (fileMsgEventClass.isInstance(event)) {
            try {
                val filePosition = event.javaClass.getMethod("getFilePosition").invoke(event)
                if (filePosition != null) {
                    val posFile = filePosition.javaClass.getMethod("getFile").invoke(filePosition) as? java.io.File
                    file = posFile?.absolutePath?.let { ProjectUtils.getRelativePath(project, it) }
                    line = (filePosition.javaClass.getMethod("getStartLine").invoke(filePosition) as? Int)?.plus(1)
                    column = (filePosition.javaClass.getMethod("getStartColumn").invoke(filePosition) as? Int)?.plus(1)
                }
            } catch (_: Exception) { }
        }

        return BuildMessage(
            category = kindName,
            message = message,
            file = file,
            line = line,
            column = column
        )
    }

    /**
     * Extracts raw text from an OutputBuildEvent.
     *
     * @param event The build event object
     * @return The output text if the event is an OutputBuildEvent, null otherwise
     */
    fun extractRawOutput(event: Any): String? {
        val outEventClass = outputEventClass ?: return null
        if (!outEventClass.isInstance(event)) return null

        return try {
            val text = event.javaClass.getMethod("getMessage").invoke(event) as? String
            if (text.isNullOrBlank()) null else text
        } catch (_: Exception) { null }
    }

    /**
     * Extracts ERROR and WARNING messages from a CompileContext.
     *
     * @param compileContext The CompileContext object from JPS compilation
     * @param project The project for relativizing file paths
     * @return List of [BuildMessage] for errors and warnings
     */
    @Suppress("UNCHECKED_CAST")
    fun extractCompilerMessages(compileContext: Any, project: Project): List<BuildMessage> {
        val result = mutableListOf<BuildMessage>()
        try {
            val categoryClass = Class.forName("com.intellij.openapi.compiler.CompilerMessageCategory")
            val getMessagesMethod = compileContext.javaClass.getMethod("getMessages", categoryClass)

            for (categoryName in listOf("ERROR", "WARNING")) {
                val category = java.lang.Enum.valueOf(
                    categoryClass as Class<out Enum<*>>,
                    categoryName
                )
                val compilerMessages = getMessagesMethod.invoke(compileContext, category) as? Array<*> ?: continue

                for (msg in compilerMessages) {
                    if (msg == null) continue
                    val msgClass = msg.javaClass

                    val messageText = msgClass.getMethod("getMessage").invoke(msg) as? String ?: continue
                    val virtualFile = msgClass.getMethod("getVirtualFile").invoke(msg) as? com.intellij.openapi.vfs.VirtualFile
                    val filePath = virtualFile?.let { ProjectUtils.getRelativePath(project, it) }

                    var line: Int? = null
                    var column: Int? = null
                    try {
                        val navigatable = msgClass.getMethod("getNavigatable").invoke(msg)
                        if (navigatable != null) {
                            val openFileDescriptor = navigatable as? com.intellij.openapi.fileEditor.OpenFileDescriptor
                            if (openFileDescriptor != null) {
                                line = openFileDescriptor.line + 1
                                column = openFileDescriptor.column + 1
                            }
                        }
                    } catch (_: Exception) { }

                    result.add(BuildMessage(
                        category = categoryName,
                        message = messageText,
                        file = filePath,
                        line = line,
                        column = column
                    ))
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to extract compiler messages", e)
        }
        return result
    }

    /**
     * Extracts raw INFORMATION and STATISTICS output from a CompileContext.
     *
     * @param compileContext The CompileContext object from JPS compilation
     * @return Concatenated raw output string
     */
    @Suppress("UNCHECKED_CAST")
    fun extractCompilerRawOutput(compileContext: Any): String {
        val rawOutput = StringBuilder()
        try {
            val categoryClass = Class.forName("com.intellij.openapi.compiler.CompilerMessageCategory")
            val getMessagesMethod = compileContext.javaClass.getMethod("getMessages", categoryClass)

            for (categoryName in listOf("INFORMATION", "STATISTICS")) {
                try {
                    val category = java.lang.Enum.valueOf(
                        categoryClass as Class<out Enum<*>>,
                        categoryName
                    )
                    val infoMessages = getMessagesMethod.invoke(compileContext, category) as? Array<*> ?: continue
                    for (msg in infoMessages) {
                        if (msg == null) continue
                        val text = msg.javaClass.getMethod("getMessage").invoke(msg) as? String ?: continue
                        rawOutput.append(text).append('\n')
                    }
                } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to extract compiler raw output", e)
        }
        return rawOutput.toString()
    }
}
