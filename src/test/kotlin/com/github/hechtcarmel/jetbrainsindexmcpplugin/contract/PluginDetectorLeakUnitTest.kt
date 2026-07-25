package com.github.hechtcarmel.jetbrainsindexmcpplugin.contract

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import junit.framework.TestCase

/**
 * Guards against test-tree classes impersonating language plugins.
 *
 * [com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetector] falls back to
 * `Class.forName(fallbackClass)` when a plugin ID is not loaded. Any class placed in the test
 * source tree at one of those FQNs therefore makes the corresponding detector report the plugin
 * as *available*, for the whole fork — the result is cached `by lazy` on an `object`.
 *
 * This is not theoretical. `src/test/kotlin/com/jetbrains/python/psi/PyClass.kt` used to exist at
 * exactly the FQN the Python detector probes. Every test ran with `python.isAvailable == true`,
 * so Python handlers registered against a handful of hand-written stubs while the real index
 * classes (`PyClassNameIndex`, `PyClassInheritorsSearch`, `PyFile`) were absent — a state no
 * shipped IDE can reach, and one in which the tests validated an invented API.
 *
 * Fake PSI types belong in the test's own package, duck-typed, as
 * [com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.php.PhpSymbolReferenceHandlerUnitTest]
 * and the Python symbol-reference tests now do.
 */
class PluginDetectorLeakUnitTest : TestCase() {

    fun testDetectorsReportOnlyPluginsActuallyOnTheTestClasspath() {
        // Declared in gradle.properties platformBundledPlugins.
        assertTrue(
            "Java plugin should be available — it is declared in platformBundledPlugins",
            PluginDetectors.java.isAvailable
        )
        assertTrue(
            "JavaScript plugin should be available — it is declared in platformBundledPlugins. " +
                "If this fails, the JS/TS tests are silently testing nothing.",
            PluginDetectors.javaScript.isAvailable
        )

        // Not declared. If any of these flips to true, something in the test tree is squatting
        // on the detector's fallback FQN.
        assertFalse(
            "Python must NOT appear available: no Python plugin is on the test classpath. " +
                "A true here means a test-tree class is sitting at com.jetbrains.python.psi.PyClass.",
            PluginDetectors.python.isAvailable
        )
        assertFalse(
            "PHP must NOT appear available: no PHP plugin is on the test classpath.",
            PluginDetectors.php.isAvailable
        )
    }
}
