package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JavaScriptSymbolReferenceHandlerTest : BasePlatformTestCase() {

    private val handler = JavaScriptSymbolReferenceHandler()

    fun testResolveNamedExportSuccess() {
        if (!requireJsTsCapability("testResolveNamedExportSuccess")) return

        myFixture.addFileToProject(
            "src/utils/date.ts",
            """
            export function formatDate(input: string): string {
                return input;
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "src/utils/date#formatDate")

        assertTrue("Should resolve named export", result.isSuccess)
        val element = result.getOrThrow()
        assertNamed(element, "formatDate")
    }

    fun testResolveDefaultExportSuccess() {
        if (!requireJsTsCapability("testResolveDefaultExportSuccess")) return

        myFixture.addFileToProject(
            "src/App.tsx",
            """
            export default function App() {
                return null;
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "src/App#default")

        assertTrue("Should resolve default export", result.isSuccess)
        val element = result.getOrThrow()
        assertNamed(element, "App")
    }

    fun testResolveClassMemberSuccess() {
        if (!requireJsTsCapability("testResolveClassMemberSuccess")) return

        myFixture.addFileToProject(
            "src/domain/User.ts",
            """
            export class User {
                fullName(): string {
                    return "John Doe";
                }
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "src/domain/User#User.fullName")

        assertTrue("Should resolve class member", result.isSuccess)
        val element = result.getOrThrow()
        assertNamed(element, "fullName")
    }

    fun testResolveNotFoundDeterministicFailure() {
        if (!requireJsTsCapability("testResolveNotFoundDeterministicFailure")) return

        myFixture.addFileToProject(
            "src/utils/date.ts",
            """
            export function formatDate(input: string): string {
                return input;
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "src/utils/date#missingExport")

        assertTrue("Should fail for missing export", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("Should return deterministic not_found error", message.startsWith("not_found:"))
    }

    fun testResolveAmbiguousMatchDeterministicFailure() {
        if (!requireJsTsCapability("testResolveAmbiguousMatchDeterministicFailure")) return

        myFixture.addFileToProject(
            "src/utils/format.ts",
            """
            export function formatValue(input: string): string {
                return input;
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "src/utils/format/index.ts",
            """
            export function formatValue(input: string): string {
                return input.toUpperCase();
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "src/utils/format#formatValue")

        assertTrue("Should fail for ambiguous module candidates", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("Should return deterministic ambiguous_match error", message.startsWith("ambiguous_match:"))
        assertTrue("Should include direct candidate", message.contains("src/utils/format.ts"))
        assertTrue("Should include index candidate", message.contains("src/utils/format/index.ts"))
    }

    private fun requireJsTsCapability(testName: String): Boolean {
        if (!PluginDetectors.javaScript.isAvailable) {
            System.err.println("$testName: skipped - JavaScript plugin not available")
            return false
        }
        return try {
            Class.forName("com.intellij.lang.javascript.psi.JSNamedElement")
            true
        } catch (_: ClassNotFoundException) {
            System.err.println("$testName: skipped - JavaScript PSI classes unavailable")
            false
        }
    }

    private fun assertNamed(element: PsiNamedElement, expected: String) {
        assertEquals(expected, element.name)
    }
}
