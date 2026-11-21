package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `ide_find_references` on an interface method includes calls that resolve to an override.
 *
 * `pricing.total()` with `pricing: BasePricing`, and the unqualified `total()` inside
 * `BasePricing`, resolve to the override `BasePricing.total()`, yet they are usages of
 * `Pricing.total()` — the IDE's Find Usages and jdtls both report them. The tool used an
 * exact-target `ReferencesSearch` and returned nothing here; it now uses `MethodReferencesSearch`.
 */
class FindUsagesOverrideCallsBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("override-calls-src")
        writeProjectFile(
            "override-calls-src/overridecalls/Pricing.java",
            //language=java
            """
            package overridecalls;

            public interface Pricing {
                double total();
            }
            """.trimIndent()
        )
        writeProjectFile(
            "override-calls-src/overridecalls/BasePricing.java",
            //language=java
            """
            package overridecalls;

            public abstract class BasePricing implements Pricing {
                @Override
                public abstract double total();

                public double withTax() {
                    return total() * 2;
                }
            }
            """.trimIndent()
        )
        writeProjectFile(
            "override-calls-src/overridecalls/Checkout.java",
            //language=java
            """
            package overridecalls;

            public class Checkout {
                double pay(BasePricing pricing) {
                    return pricing.total();
                }
            }
            """.trimIndent()
        )
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    fun testInterfaceMethodReferencesIncludeCallsResolvingToTheOverride() = runBlocking {
        // Line 4, column 12: `total` in `    double total();`
        val result = FindUsagesTool().execute(project, buildJsonObject {
            put("file", "override-calls-src/overridecalls/Pricing.java")
            put("line", 4)
            put("column", 12)
        })
        assertToolSucceeded("find_references on Pricing.total()", result)
        val parsed = json.decodeFromString<FindUsagesResult>(toolText(result))

        assertEquals("total", parsed.resolvedSymbol?.name)
        assertEquals(
            "Calls through the BasePricing override are usages of Pricing.total(); got: ${parsed.usages}",
            listOf("BasePricing.java:8", "Checkout.java:5"),
            parsed.usages.map { "${it.file.substringAfterLast('/')}:${it.line}" }.sorted()
        )
    }
}
