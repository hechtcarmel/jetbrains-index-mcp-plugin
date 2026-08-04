package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.util.Disposer
import javax.swing.JPanel

/**
 * ide_run_tests suppresses Run tool window activation via an ExecutionEnvironment callback that
 * overrides the descriptor flags (issue #278). This pins the two facts that make it work: the
 * platform descriptor defaults to activate-on-add (so there is something to suppress), and the
 * callback clears both the activation and focus flags before RunContentManager reads them.
 */
class RunTestsToolWindowActivationTest : McpPlatformTestCase() {

    fun testSuppressCallbackDisablesToolWindowActivationAndFocus() {
        val descriptor = RunContentDescriptor(null, null, JPanel(), "ide_run_tests")
        try {
            assertTrue(
                "Platform default changed: RunContentDescriptor no longer activates on add — " +
                        "re-evaluate whether ide_run_tests still needs to suppress activation",
                descriptor.isActivateToolWindowWhenAdded
            )
            RunTestsTool.suppressToolWindowActivation().processStarted(descriptor)
            assertFalse("Callback must clear isActivateToolWindowWhenAdded", descriptor.isActivateToolWindowWhenAdded)
            assertFalse("Callback must clear isAutoFocusContent", descriptor.isAutoFocusContent)
        } finally {
            Disposer.dispose(descriptor)
        }
    }

    fun testSuppressCallbackToleratesNullDescriptor() {
        // ExecutionManagerImpl invokes the environment callback with a null descriptor when the
        // starter fails, so the callback must not assume one exists.
        RunTestsTool.suppressToolWindowActivation().processStarted(null)
    }
}
