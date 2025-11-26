package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ThreadingUtils {

    suspend fun <T> readActionSuspend(action: () -> T): T {
        return withContext(Dispatchers.Default) {
            ReadAction.compute<T, Throwable>(action)
        }
    }

    suspend fun writeActionSuspend(
        project: Project,
        commandName: String,
        action: () -> Unit
    ) {
        withContext(Dispatchers.Main) {
            WriteCommandAction.runWriteCommandAction(
                project,
                commandName,
                null,
                { action() }
            )
        }
    }

    fun runOnEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }

    fun <T> runOnEdtAndWait(action: () -> T): T {
        var result: T? = null
        var exception: Throwable? = null

        ApplicationManager.getApplication().invokeAndWait {
            try {
                result = action()
            } catch (e: Throwable) {
                exception = e
            }
        }

        exception?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    suspend fun <T> runWhenSmart(
        project: Project,
        action: () -> T
    ): T {
        return suspendCancellableCoroutine { continuation ->
            DumbService.getInstance(project).runWhenSmart {
                try {
                    val result = ReadAction.compute<T, Throwable>(action)
                    continuation.resume(result)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    fun isDumbMode(project: Project): Boolean {
        return DumbService.isDumb(project)
    }

    fun <T> computeInReadAction(action: () -> T): T {
        return ReadAction.compute<T, Throwable>(action)
    }

    fun runInWriteAction(project: Project, commandName: String, action: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project, commandName, null, { action() })
    }
}
