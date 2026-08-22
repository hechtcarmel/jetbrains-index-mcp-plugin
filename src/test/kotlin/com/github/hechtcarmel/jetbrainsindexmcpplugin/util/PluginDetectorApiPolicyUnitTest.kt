package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Guards plugin-detection code against APIs the JetBrains Marketplace plugin
 * verifier rejects.
 *
 * `PluginManager.findEnabledPlugin` was rejected in review; `PluginManagerCore.getPlugin`
 * is `@ApiStatus.Internal` as of 2026.2 and fails the "Verify plugin" CI job with
 * `INTERNAL_API_USAGES`. Detection must go through `PluginManagerCore.isLoaded`/`isDisabled`
 * only (see [PluginDetector]).
 */
class PluginDetectorApiPolicyUnitTest : TestCase() {

    fun testPluginDetectorDoesNotUseRejectedInternalPluginManagerApi() {
        assertNoRejectedApiUsage(
            "src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/util/PluginDetector.kt"
        )
    }

    private fun assertNoRejectedApiUsage(relativePath: String) {
        val source = Files.readString(Paths.get(relativePath))
        val lines = source.lineSequence().map { it.trim() }.toList()

        assertFalse(
            "$relativePath must not import com.intellij.ide.plugins.PluginManager",
            lines.contains("import com.intellij.ide.plugins.PluginManager")
        )
        assertFalse(
            "$relativePath must not call PluginManager.findEnabledPlugin",
            source.contains("findEnabledPlugin")
        )
        assertFalse(
            "$relativePath must not call the @ApiStatus.Internal PluginManagerCore.getPlugin",
            source.contains("PluginManagerCore.getPlugin(")
        )
    }
}
