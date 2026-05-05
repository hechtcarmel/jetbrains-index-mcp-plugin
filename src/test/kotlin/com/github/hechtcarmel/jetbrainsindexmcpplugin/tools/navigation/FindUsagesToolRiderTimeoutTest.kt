package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendSemanticService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FindUsagesToolRiderTimeoutTest : BasePlatformTestCase() {

    fun testRiderBackendTimeoutReturnsErrorInsteadOfEmptySuccess() = runBlocking {
        mockkObject(RiderBackendSemanticService)
        try {
            every {
                RiderBackendSemanticService.findReferences(
                    project = project,
                    file = null,
                    line = null,
                    column = null,
                    language = "F#",
                    symbol = "FSharpPlus.Lens",
                    scope = BuiltInSearchScope.PROJECT_FILES,
                    limit = any()
                )
            } returns RiderBackendResponse(
                handled = true,
                errorMessage = "Rider backend timed out while finding references after 60s (rd call 'findReferences')."
            )

            val result = FindUsagesTool().execute(project, buildJsonObject {
                put("language", JsonPrimitive("F#"))
                put("symbol", JsonPrimitive("FSharpPlus.Lens"))
            })

            assertTrue(result.isError)
            val text = (result.content.single() as ContentBlock.Text).text
            assertTrue(text.contains("timed out while finding references"))
            assertFalse(text.contains("\"usages\""))
        } finally {
            unmockkObject(RiderBackendSemanticService)
        }
    }
}
