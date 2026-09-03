package com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil

import com.intellij.testFramework.LoggedErrorProcessor

/**
 * Runs [action] with the spurious `NoSuchMethodError` the Workspace Model logs when a module is
 * added in the test environment suppressed.
 *
 * The error comes from a Kotlin version mismatch between the test classpath and the bundled
 * platform (`WorkspaceModelImpl.logErrorOnEventHandling`); it is harmless, the module is still
 * created, but a logged error fails the test unless it is filtered here.
 */
fun <T> suppressWorkspaceModelErrors(action: () -> T): T {
    val token = LoggedErrorProcessor.executeWith(
        object : LoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<out String>,
                t: Throwable?
            ): MutableSet<Action> {
                if (t is NoSuchMethodError || message.contains("Workspace Model event handling")) {
                    return Action.NONE
                }
                return Action.ALL
            }
        }
    )
    try {
        return action()
    } finally {
        token.finish()
    }
}
