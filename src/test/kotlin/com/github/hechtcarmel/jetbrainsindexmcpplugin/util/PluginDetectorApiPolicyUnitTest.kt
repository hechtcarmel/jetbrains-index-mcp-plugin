package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Paths

class PluginDetectorApiPolicyUnitTest : TestCase() {

    fun testPluginDetectorDoesNotUseRejectedInternalPluginManagerApi() {
        val source = Files.readString(Paths.get(
            "src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/util/PluginDetector.kt"
        ))

        val lines = source.lineSequence().map { it.trim() }.toList()

        assertFalse(lines.contains("import com.intellij.ide.plugins.PluginManager"))
        assertFalse(source.contains("findEnabledPlugin"))
    }
}
