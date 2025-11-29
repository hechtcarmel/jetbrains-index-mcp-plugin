package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for language-specific handlers.
 *
 * The registry manages the lifecycle of language handlers and provides methods
 * to find the appropriate handler for a given PSI element or language.
 *
 * ## Handler Registration
 *
 * Handlers are registered at startup based on plugin availability:
 *
 * ```kotlin
 * LanguageHandlerRegistry.registerHandlers()
 * ```
 *
 * ## Finding Handlers
 *
 * To find a handler for a specific element:
 *
 * ```kotlin
 * val handler = LanguageHandlerRegistry.getHandler<TypeHierarchyHandler>(element)
 * ```
 *
 * @see LanguageHandler
 */
object LanguageHandlerRegistry {

    private val LOG = logger<LanguageHandlerRegistry>()

    // Handler registries by type
    private val typeHierarchyHandlers = ConcurrentHashMap<String, TypeHierarchyHandler>()
    private val implementationsHandlers = ConcurrentHashMap<String, ImplementationsHandler>()
    private val callHierarchyHandlers = ConcurrentHashMap<String, CallHierarchyHandler>()
    private val symbolSearchHandlers = ConcurrentHashMap<String, SymbolSearchHandler>()
    private val superMethodsHandlers = ConcurrentHashMap<String, SuperMethodsHandler>()

    // Track if handlers have been registered
    private var initialized = false

    /**
     * Registers all available language handlers.
     *
     * This method discovers and registers handlers based on available plugins.
     * It's called during plugin startup.
     */
    @Synchronized
    fun registerHandlers() {
        if (initialized) return
        initialized = true

        LOG.info("Registering language handlers...")

        // Register Java handlers
        registerJavaHandlers()

        // Register Python handlers
        registerPythonHandlers()

        // Register JavaScript/TypeScript handlers
        registerJavaScriptHandlers()

        LOG.info("Language handlers registered: " +
            "TypeHierarchy=${typeHierarchyHandlers.size}, " +
            "Implementations=${implementationsHandlers.size}, " +
            "CallHierarchy=${callHierarchyHandlers.size}, " +
            "SymbolSearch=${symbolSearchHandlers.size}, " +
            "SuperMethods=${superMethodsHandlers.size}")
    }

    /**
     * Clears all registered handlers. Used for testing.
     */
    @Synchronized
    fun clear() {
        typeHierarchyHandlers.clear()
        implementationsHandlers.clear()
        callHierarchyHandlers.clear()
        symbolSearchHandlers.clear()
        superMethodsHandlers.clear()
        initialized = false
    }

    // Registration methods

    fun registerTypeHierarchyHandler(handler: TypeHierarchyHandler) {
        typeHierarchyHandlers[handler.languageId] = handler
        LOG.info("Registered TypeHierarchyHandler for ${handler.languageId}")
    }

    fun registerImplementationsHandler(handler: ImplementationsHandler) {
        implementationsHandlers[handler.languageId] = handler
        LOG.info("Registered ImplementationsHandler for ${handler.languageId}")
    }

    fun registerCallHierarchyHandler(handler: CallHierarchyHandler) {
        callHierarchyHandlers[handler.languageId] = handler
        LOG.info("Registered CallHierarchyHandler for ${handler.languageId}")
    }

    fun registerSymbolSearchHandler(handler: SymbolSearchHandler) {
        symbolSearchHandlers[handler.languageId] = handler
        LOG.info("Registered SymbolSearchHandler for ${handler.languageId}")
    }

    fun registerSuperMethodsHandler(handler: SuperMethodsHandler) {
        superMethodsHandlers[handler.languageId] = handler
        LOG.info("Registered SuperMethodsHandler for ${handler.languageId}")
    }

    // Handler lookup methods

    /**
     * Gets a type hierarchy handler for the given element.
     */
    fun getTypeHierarchyHandler(element: PsiElement): TypeHierarchyHandler? {
        return findHandler(element, typeHierarchyHandlers)
    }

    /**
     * Gets an implementations handler for the given element.
     */
    fun getImplementationsHandler(element: PsiElement): ImplementationsHandler? {
        return findHandler(element, implementationsHandlers)
    }

    /**
     * Gets a call hierarchy handler for the given element.
     */
    fun getCallHierarchyHandler(element: PsiElement): CallHierarchyHandler? {
        return findHandler(element, callHierarchyHandlers)
    }

    /**
     * Gets a super methods handler for the given element.
     */
    fun getSuperMethodsHandler(element: PsiElement): SuperMethodsHandler? {
        return findHandler(element, superMethodsHandlers)
    }

    /**
     * Gets all available symbol search handlers.
     *
     * Unlike other handlers, symbol search aggregates results from all languages,
     * so we return all available handlers.
     */
    fun getAllSymbolSearchHandlers(): List<SymbolSearchHandler> {
        return symbolSearchHandlers.values.filter { it.isAvailable() }
    }

    /**
     * Checks if any handlers are available for the given handler type.
     */
    fun hasTypeHierarchyHandlers(): Boolean = typeHierarchyHandlers.values.any { it.isAvailable() }
    fun hasImplementationsHandlers(): Boolean = implementationsHandlers.values.any { it.isAvailable() }
    fun hasCallHierarchyHandlers(): Boolean = callHierarchyHandlers.values.any { it.isAvailable() }
    fun hasSymbolSearchHandlers(): Boolean = symbolSearchHandlers.values.any { it.isAvailable() }
    fun hasSuperMethodsHandlers(): Boolean = superMethodsHandlers.values.any { it.isAvailable() }

    /**
     * Gets a list of languages that have handlers for the given handler type.
     */
    fun getSupportedLanguagesForTypeHierarchy(): List<String> =
        typeHierarchyHandlers.filter { it.value.isAvailable() }.keys.toList()

    fun getSupportedLanguagesForImplementations(): List<String> =
        implementationsHandlers.filter { it.value.isAvailable() }.keys.toList()

    fun getSupportedLanguagesForCallHierarchy(): List<String> =
        callHierarchyHandlers.filter { it.value.isAvailable() }.keys.toList()

    fun getSupportedLanguagesForSymbolSearch(): List<String> =
        symbolSearchHandlers.filter { it.value.isAvailable() }.keys.toList()

    fun getSupportedLanguagesForSuperMethods(): List<String> =
        superMethodsHandlers.filter { it.value.isAvailable() }.keys.toList()

    // Private helper methods

    private fun <T : LanguageHandler<*>> findHandler(
        element: PsiElement,
        handlers: Map<String, T>
    ): T? {
        val language = detectLanguage(element)

        // Try exact language match first
        handlers[language.id]?.let { handler ->
            if (handler.isAvailable() && handler.canHandle(element)) {
                return handler
            }
        }

        // Try base language (e.g., TypeScript -> JavaScript)
        language.baseLanguage?.let { baseLanguage ->
            handlers[baseLanguage.id]?.let { handler ->
                if (handler.isAvailable() && handler.canHandle(element)) {
                    return handler
                }
            }
        }

        // Try all handlers that can handle this element
        return handlers.values.firstOrNull { handler ->
            handler.isAvailable() && handler.canHandle(element)
        }
    }

    /**
     * Detects the language of a PSI element.
     */
    private fun detectLanguage(element: PsiElement): Language {
        return element.language
    }

    // Handler registration implementations

    private fun registerJavaHandlers() {
        try {
            // Load Java handlers via reflection to avoid compile-time dependency
            val javaHandlerClass = Class.forName(
                "com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java.JavaHandlers"
            )
            val registerMethod = javaHandlerClass.getMethod("register", LanguageHandlerRegistry::class.java)
            registerMethod.invoke(null, this)
            LOG.info("Java handlers registered")
        } catch (e: ClassNotFoundException) {
            LOG.info("Java handlers not available (Java plugin not installed)")
        } catch (e: Exception) {
            LOG.warn("Failed to register Java handlers: ${e.message}")
        }
    }

    private fun registerPythonHandlers() {
        try {
            // Load Python handlers via reflection
            val pythonHandlerClass = Class.forName(
                "com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.python.PythonHandlers"
            )
            val registerMethod = pythonHandlerClass.getMethod("register", LanguageHandlerRegistry::class.java)
            registerMethod.invoke(null, this)
            LOG.info("Python handlers registered")
        } catch (e: ClassNotFoundException) {
            LOG.info("Python handlers not available (Python plugin not installed)")
        } catch (e: Exception) {
            LOG.warn("Failed to register Python handlers: ${e.message}")
        }
    }

    private fun registerJavaScriptHandlers() {
        try {
            // Load JavaScript handlers via reflection
            val jsHandlerClass = Class.forName(
                "com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript.JavaScriptHandlers"
            )
            val registerMethod = jsHandlerClass.getMethod("register", LanguageHandlerRegistry::class.java)
            registerMethod.invoke(null, this)
            LOG.info("JavaScript handlers registered")
        } catch (e: ClassNotFoundException) {
            LOG.info("JavaScript handlers not available (JavaScript plugin not installed)")
        } catch (e: Exception) {
            LOG.warn("Failed to register JavaScript handlers: ${e.message}")
        }
    }
}
