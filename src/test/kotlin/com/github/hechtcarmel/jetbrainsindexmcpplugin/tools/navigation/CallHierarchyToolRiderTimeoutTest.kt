package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendTimeoutException
import junit.framework.TestCase

class CallHierarchyToolRiderTimeoutTest : TestCase() {

    fun testRiderSymbolModeValidationRejectsDottedCSharpMemberWithGuidance() {
        val message = CallHierarchyTool.riderSymbolValidationMessage("C#", "Demo.Service.Run")

        assertNotNull(message)
        val text = message ?: return
        assertTrue(text.contains("Demo.Service#Run"))
        assertTrue(text.contains("#"))
    }

    fun testRiderSymbolModeValidationAllowsCallableCSharpHashSyntax() {
        assertNull(CallHierarchyTool.riderSymbolValidationMessage("C#", "Demo.Service#Run"))
        assertNull(CallHierarchyTool.riderSymbolValidationMessage("C#", "Demo.Service#Run(System.String)"))
    }

    fun testTimeoutMessageIsExplicitAndNotNoMethodFallback() {
        val timeout = RiderBackendTimeoutException(
            callName = "getCallHierarchy",
            timeoutSeconds = 30L,
            operation = "resolving call hierarchy"
        )

        val message = CallHierarchyTool.riderTimeoutMessage(timeout)

        assertTrue(message.contains("timed out while resolving call hierarchy"))
        assertFalse(message == CallHierarchyTool.noCallableMessage(isSymbolMode = false))
    }
}
