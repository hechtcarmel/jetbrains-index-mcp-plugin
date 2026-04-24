package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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
    private val symbolReferenceHandlers = ConcurrentHashMap<String, SymbolReferenceHandler>()
    private val superMethodsHandlers = ConcurrentHashMap<String, SuperMethodsHandler>()
    private val structureHandlers = ConcurrentHashMap<String, StructureHandler>()

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

        for (reg in handlerRegistrations) {
            registerLanguageHandlers(reg.className, reg.displayName)
        }

        LOG.info("Language handlers registered: " +
            "TypeHierarchy=${typeHierarchyHandlers.size}, " +
            "Implementations=${implementationsHandlers.size}, " +
            "CallHierarchy=${callHierarchyHandlers.size}, " +
            "SymbolSearch=${symbolSearchHandlers.size}, " +
            "SymbolReference=${symbolReferenceHandlers.size}, " +
            "SuperMethods=${superMethodsHandlers.size}, " +
            "Structure=${structureHandlers.size}")
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
        symbolReferenceHandlers.clear()
        superMethodsHandlers.clear()
        structureHandlers.clear()
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

    fun registerSymbolReferenceHandler(handler: SymbolReferenceHandler) {
        symbolReferenceHandlers[handler.languageId] = handler
        LOG.info("Registered SymbolReferenceHandler for ${handler.languageId}")
    }

    fun registerSuperMethodsHandler(handler: SuperMethodsHandler) {
        superMethodsHandlers[handler.languageId] = handler
        LOG.info("Registered SuperMethodsHandler for ${handler.languageId}")
    }

    fun registerStructureHandler(handler: StructureHandler) {
        structureHandlers[handler.languageId] = handler
        LOG.info("Registered StructureHandler for ${handler.languageId}")
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
     * Gets a structure handler for the given file.
     */
    fun getStructureHandler(file: PsiFile): StructureHandler? {
        val language = file.language

        // Try exact language match first
        structureHandlers[language.id]?.let { handler ->
            if (handler.isAvailable()) return handler
        }

        // Try case-insensitive match (for Python: "Python" vs "python")
        val caseInsensitiveMatch = structureHandlers.entries.firstOrNull { (langId, _) ->
            langId.equals(language.id, ignoreCase = true)
        }?.value
        if (caseInsensitiveMatch != null && caseInsensitiveMatch.isAvailable()) {
            return caseInsensitiveMatch
        }

        // Try base language
        language.baseLanguage?.let { baseLanguage ->
            structureHandlers[baseLanguage.id]?.let { handler ->
                if (handler.isAvailable()) return handler
            }

            // Try case-insensitive match for base language
            val baseCaseInsensitiveMatch = structureHandlers.entries.firstOrNull { (langId, _) ->
                langId.equals(baseLanguage.id, ignoreCase = true)
            }?.value
            if (baseCaseInsensitiveMatch != null && baseCaseInsensitiveMatch.isAvailable()) {
                return baseCaseInsensitiveMatch
            }
        }

        return null
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
    fun hasStructureHandlers(): Boolean = structureHandlers.values.any { it.isAvailable() }

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

    fun getSupportedLanguagesForSymbolReference(): List<String> =
        symbolReferenceHandlers.filter { it.value.isAvailable() }.keys.toList()

    fun getSupportedLanguagesForStructure(): List<String> =
        structureHandlers.filter { it.value.isAvailable() }.keys.toList()

    /**
     * Gets a list of human-readable language names that have symbol reference handlers available.
     *
     * @return A list of language names (e.g., "Java", "Python") that have available symbol reference handlers.
     */
    fun getSupportedLanguageNamesForSymbolReference(): List<String> {
        return symbolReferenceHandlers.filter { it.value.isAvailable() }.map { it.value.languageName }
    }

    /**
     * Gets a symbol reference handler for the given language name.
     *
     * @param languageName The language name (e.g., "Java", "Kotlin")
     * @return The handler if available, or null if no handler exists for the language
     */
    fun getSymbolReferenceHandlerByLanguageName(languageName: String): SymbolReferenceHandler? {
        return symbolReferenceHandlers.values.firstOrNull {
            it.isAvailable() && it.languageName.equals(languageName, ignoreCase = true)
        }
    }

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

    private data class HandlerRegistration(val className: String, val displayName: String)

    private val handlerRegistrations = listOf(
        HandlerRegistration("com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java.JavaHandlers", "Java"),
        HandlerRegistration("com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.python.PythonHandlers", "Python"),
        HandlerRegistration("com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript.JavaScriptHandlers", "JavaScript"),
        HandlerRegistration("com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.go.GoHandlers", "Go"),
        HandlerRegistration("com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.php.PhpHandlers", "PHP"),
        HandlerRegistration("com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.rust.RustHandlers", "Rust"),
        HandlerRegistration("com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.markdown.MarkdownHandlers", "Markdown"),
    )

    private fun registerLanguageHandlers(className: String, displayName: String) {
        try {
            val handlerClass = Class.forName(className)
            val registerMethod = handlerClass.getMethod("register", LanguageHandlerRegistry::class.java)
            registerMethod.invoke(null, this)
            LOG.info("$displayName handlers registered")
        } catch (e: ClassNotFoundException) {
            LOG.info("$displayName handlers not available ($displayName plugin not installed)")
        } catch (e: Exception) {
            LOG.warn("Failed to register $displayName handlers: ${e.message}", e)
        }
    }
}
