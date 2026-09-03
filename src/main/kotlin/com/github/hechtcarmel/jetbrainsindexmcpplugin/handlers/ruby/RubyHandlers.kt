package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.diagnostic.logger

/**
 * Registration entry point for Ruby language handlers.
 *
 * This class is loaded via reflection when the Ruby plugin is available.
 * It registers all Ruby-specific handlers with the [LanguageHandlerRegistry].
 *
 * ## Ruby PSI Classes Used (via reflection)
 *
 * - `org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.classes.RClass` — Ruby class declarations
 * - `org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.modules.RModule` — Ruby module declarations
 * - `org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.methods.RMethod` — Ruby method declarations
 * - `org.jetbrains.plugins.ruby.ruby.lang.psi.callExpressions.RCallExpression` — Method call expressions
 */
object RubyHandlers {

    private val LOG = logger<RubyHandlers>()

    /**
     * Registers all Ruby handlers with the registry.
     *
     * Called via reflection from [LanguageHandlerRegistry].
     */
    @JvmStatic
    fun register(registry: LanguageHandlerRegistry) {
        if (!PluginDetectors.ruby.isAvailable) {
            LOG.info("Ruby plugin not available, skipping Ruby handler registration")
            return
        }

        try {
            // Verify Ruby PSI classes are accessible before registering
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.classes.RClass")
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.modules.RModule")
            Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.methods.RMethod")

            registry.registerTypeHierarchyHandler(RubyTypeHierarchyHandler())
            registry.registerCallHierarchyHandler(RubyCallHierarchyHandler())
            registry.registerSuperMethodsHandler(RubySuperMethodsHandler())
            registry.registerStructureHandler(RubyStructureHandler())
            registry.registerSymbolReferenceHandler(RubySymbolReferenceHandler())

            LOG.info("Registered Ruby handlers")
        } catch (e: ClassNotFoundException) {
            LOG.warn("Ruby PSI classes not found, skipping registration: ${e.message}")
        } catch (e: Exception) {
            LOG.warn("Failed to register Ruby handlers: ${e.message}")
        }
    }
}