package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.project.Project
import io.mockk.mockk
import junit.framework.TestCase
import java.lang.reflect.Proxy

class BuildListenerUtilsUnitTest : TestCase() {

    fun testExtractFailureMessagesFromFinishFailureResult() {
        val failure = proxy("com.intellij.build.events.Failure") { methodName ->
            when (methodName) {
                "getMessage" -> "compiler failed"
                "getDescription" -> "missing symbol details"
                "getCauses" -> emptyList<Any>()
                "getError", "getNotification", "getNavigatable" -> null
                else -> defaultProxyValue(methodName)
            }
        }
        val failureResult = proxy("com.intellij.build.events.FailureResult") { methodName ->
            when (methodName) {
                "getFailures" -> listOf(failure)
                else -> defaultProxyValue(methodName)
            }
        }
        val finishEvent = finishEventWithResult(failureResult)

        val messages = BuildListenerUtils.extractFailureMessages(finishEvent, mockk<Project>(relaxed = true))

        assertEquals(1, messages.size)
        assertEquals("ERROR", messages[0].category)
        assertTrue(messages[0].message.contains("compiler failed"))
        assertTrue(messages[0].message.contains("missing symbol details"))
    }

    fun testExtractFailureMessagesIncludesCauses() {
        val cause = proxy("com.intellij.build.events.Failure") { methodName ->
            when (methodName) {
                "getMessage" -> "root cause"
                "getDescription" -> null
                "getCauses" -> emptyList<Any>()
                "getError", "getNotification", "getNavigatable" -> null
                else -> defaultProxyValue(methodName)
            }
        }
        val failure = proxy("com.intellij.build.events.Failure") { methodName ->
            when (methodName) {
                "getMessage" -> "top level failure"
                "getDescription" -> null
                "getCauses" -> listOf(cause)
                "getError", "getNotification", "getNavigatable" -> null
                else -> defaultProxyValue(methodName)
            }
        }
        val failureResult = proxy("com.intellij.build.events.FailureResult") { methodName ->
            when (methodName) {
                "getFailures" -> listOf(failure)
                else -> defaultProxyValue(methodName)
            }
        }

        val messages = BuildListenerUtils.extractFailureMessages(
            finishEventWithResult(failureResult),
            mockk<Project>(relaxed = true)
        )

        assertEquals(listOf("top level failure", "root cause"), messages.map { it.message })
    }

    fun testExtractFailureMessagesIgnoresNonFailureFinishEvent() {
        val eventResult = proxy("com.intellij.build.events.EventResult") { methodName ->
            defaultProxyValue(methodName)
        }

        val messages = BuildListenerUtils.extractFailureMessages(
            finishEventWithResult(eventResult),
            mockk<Project>(relaxed = true)
        )

        assertTrue(messages.isEmpty())
    }

    fun testIsFailureResultEvent() {
        val failureResult = proxy("com.intellij.build.events.FailureResult") { methodName ->
            when (methodName) {
                "getFailures" -> emptyList<Any>()
                else -> defaultProxyValue(methodName)
            }
        }
        val eventResult = proxy("com.intellij.build.events.EventResult") { methodName ->
            defaultProxyValue(methodName)
        }

        assertTrue(BuildListenerUtils.isFailureResultEvent(finishEventWithResult(failureResult)))
        assertFalse(BuildListenerUtils.isFailureResultEvent(finishEventWithResult(eventResult)))
    }

    fun testExtractRawOutputFromOutputBuildEvent() {
        val outputEvent = proxy("com.intellij.build.events.OutputBuildEvent") { methodName ->
            when (methodName) {
                "getMessage" -> "compiler output"
                "isStdOut" -> true
                "getOutputType" -> null
                else -> defaultProxyValue(methodName)
            }
        }

        assertEquals("compiler output", BuildListenerUtils.extractRawOutput(outputEvent))
    }

    private fun finishEventWithResult(result: Any): Any =
        proxy("com.intellij.build.events.FinishEvent") { methodName ->
            when (methodName) {
                "getResult" -> result
                else -> defaultProxyValue(methodName)
            }
        }

    private fun proxy(className: String, handler: (String) -> Any?): Any {
        val targetClass = Class.forName(className)
        return Proxy.newProxyInstance(targetClass.classLoader, arrayOf(targetClass)) { proxy, method, _ ->
            when (method.name) {
                "toString" -> "Proxy(${targetClass.simpleName})"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> false
                else -> handler(method.name)
            }
        }
    }

    private fun defaultProxyValue(methodName: String): Any? =
        when (methodName) {
            "getId" -> "id"
            "getParentId" -> null
            "getEventTime" -> 0L
            "getMessage" -> ""
            "getHint" -> null
            "getDescription" -> null
            else -> null
        }
}
