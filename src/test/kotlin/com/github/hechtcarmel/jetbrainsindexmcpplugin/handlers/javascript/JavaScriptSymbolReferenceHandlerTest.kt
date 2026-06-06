package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class JavaScriptSymbolReferenceHandlerTest : BasePlatformTestCase() {

    private companion object {
        const val FIXTURE_SOURCE_ROOT = "src/test/testData/javascript/webstormIntegration"
        const val FIXTURE_PROJECT_ROOT = "src/webstormIntegration"
    }

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
        // This test was previously documenting broken behavior (false ambiguous_match).
        // It now asserts correct behavior: direct-file precedence resolves to foo.ts,
        // not ambiguous_match, when both foo.ts and foo/index.ts export the same name.
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

        assertTrue("Should resolve to direct file (direct-file precedence), not ambiguous_match", result.isSuccess)
        val element = result.getOrThrow()
        assertNamed(element, "formatValue")
        assertContainingFileSuffix(element, "src/utils/format.ts")
    }

    fun testResolveDefaultExportClassForm() {
        if (!requireJsTsCapability("testResolveDefaultExportClassForm")) return

        addWebstormIntegrationFixture("export-default-class.ts")

        val result = handler.resolveSymbol(project, fixtureSymbol("export-default-class.ts", "default"))

        assertTrue("Should resolve export default class form", result.isSuccess)
        val element = result.getOrThrow()
        assertNamed(element, "MyWidget")
    }

    fun testResolveDirectFilePrecedenceOverIndex() {
        if (!requireJsTsCapability("testResolveDirectFilePrecedenceOverIndex")) return

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

        assertTrue("Should resolve to direct file when both foo.ts and foo/index.ts export the same name", result.isSuccess)
        val element = result.getOrThrow()
        assertNamed(element, "formatValue")
        assertContainingFileSuffix(element, "src/utils/format.ts")
    }

    fun testResolveIndexFileWhenDirectNotExists() {
        if (!requireJsTsCapability("testResolveIndexFileWhenDirectNotExists")) return

        myFixture.addFileToProject(
            "src/utils/format/index.ts",
            """
            export function formatValue(input: string): string {
                return input.toUpperCase();
            }
            """.trimIndent()
        )

        val result = handler.resolveSymbol(project, "src/utils/format#formatValue")

        assertTrue("Should resolve to index file when only foo/index.ts exists (fallback path)", result.isSuccess)
        val element = result.getOrThrow()
        assertNamed(element, "formatValue")
        assertContainingFileSuffix(element, "src/utils/format/index.ts")
    }

    fun testResolveWorkspacePrefixedModulePathUsesProjectRootBeforeNestedContentRoot() {
        if (!requireJsTsCapability("testResolveWorkspacePrefixedModulePathUsesProjectRootBeforeNestedContentRoot")) return

        myFixture.addFileToProject(
            "packages/app/src/user.ts",
            """
            export class User {
                readonly source = "project-root";
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "packages/app/packages/app/src/user.ts",
            """
            export class User {
                readonly source = "duplicated-module-root";
            }
            """.trimIndent()
        )
        val nestedContentRoot = myFixture.tempDirFixture.findOrCreateDir("packages/app")
        PsiTestUtil.addContentRoot(module, nestedContentRoot)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val result = handler.resolveSymbol(project, "packages/app/src/user#User")

        assertTrue("Workspace-prefixed symbol should resolve successfully", result.isSuccess)
        assertContainingFileSuffix(result.getOrThrow(), "packages/app/src/user.ts")
        assertFalse(
            "Resolver must not prepend the nested module root to an already workspace-prefixed path",
            result.getOrThrow().containingFile.virtualFile.path.replace('\\', '/')
                .endsWith("packages/app/packages/app/src/user.ts")
        )
    }

    fun testResolveOverloadedExportFixtureCoverageHook() {
        if (!requireJsTsCapability("testResolveOverloadedExportFixtureCoverageHook")) return

        addWebstormIntegrationFixture("overloads/overloaded-export.ts")

        val result = handler.resolveSymbol(project, fixtureSymbol("overloads/overloaded-export.ts", "getProjectId"))

        assertTrue("Overloaded exported functions should resolve through fixture-backed coverage", result.isSuccess)
        val element = result.getOrThrow()
        assertNamed(element, "getProjectId")
        assertContainingFileSuffix(element, "overloads/overloaded-export.ts")
        assertTrue("Overload resolution should prefer the concrete implementation declaration", element.text.contains("readProjectIdFromConfig"))
    }

    fun testResolveRealisticMultiNamedIndexBarrelFixtureCoverageHook() {
        if (!requireJsTsCapability("testResolveRealisticMultiNamedIndexBarrelFixtureCoverageHook")) return

        addWebstormIntegrationFixture("barrels/realistic/config/loader.ts")
        addWebstormIntegrationFixture("barrels/realistic/config/index.ts")

        val result = handler.resolveSymbol(project, fixtureSymbol("barrels/realistic/config/index.ts", "loadPluginConfig"))

        assertTrue("Realistic multi-named index barrel should resolve successfully", result.isSuccess)
        assertContainingFileSuffix(result.getOrThrow(), "barrels/realistic/config/loader.ts")
    }

    fun testResolveNamedBarrelFixtureCoverageHook() {
        if (!requireJsTsCapability("testResolveNamedBarrelFixtureCoverageHook")) return

        addWebstormIntegrationFixture("barrels/plugin-config.ts")
        addWebstormIntegrationFixture("barrels/named-barrel.ts")

        val result = handler.resolveSymbol(project, fixtureSymbol("barrels/named-barrel.ts", "loadPluginConfig"))

        assertTrue("Named re-export barrel fixture should be covered explicitly", result.isSuccess)
        assertNamed(result.getOrThrow(), "loadPluginConfig")
    }

    fun testResolveExportStarBarrelFixtureCoverageHook() {
        if (!requireJsTsCapability("testResolveExportStarBarrelFixtureCoverageHook")) return

        addWebstormIntegrationFixture("barrels/plugin-config.ts")
        addWebstormIntegrationFixture("barrels/export-star-barrel.ts")

        val result = handler.resolveSymbol(project, fixtureSymbol("barrels/export-star-barrel.ts", "loadPluginConfig"))

        assertTrue("Export-star barrel fixture should be covered explicitly", result.isSuccess)
        assertNamed(result.getOrThrow(), "loadPluginConfig")
    }

    fun testResolveBarrelFixturesRemainDisambiguatedAcrossSameNamedExports() {
        if (!requireJsTsCapability("testResolveBarrelFixturesRemainDisambiguatedAcrossSameNamedExports")) return

        addWebstormIntegrationFixture("barrels/plugin-config.ts")
        addWebstormIntegrationFixture("barrels/named-barrel.ts")
        addWebstormIntegrationFixture("barrels/unrelated-plugin-config.ts")
        addWebstormIntegrationFixture("barrels/unrelated-barrel.ts")

        val namedResult = handler.resolveSymbol(project, fixtureSymbol("barrels/named-barrel.ts", "loadPluginConfig"))
        val unrelatedResult = handler.resolveSymbol(project, fixtureSymbol("barrels/unrelated-barrel.ts", "loadPluginConfig"))

        assertTrue("Named barrel should resolve successfully", namedResult.isSuccess)
        assertTrue("Unrelated same-named barrel should resolve successfully", unrelatedResult.isSuccess)
        assertContainingFileSuffix(namedResult.getOrThrow(), "barrels/plugin-config.ts")
        assertContainingFileSuffix(unrelatedResult.getOrThrow(), "barrels/unrelated-plugin-config.ts")
    }

    fun testResolveUnsupportedGrammarCoverageHookForFixtureGuidance() {
        if (!requireJsTsCapability("testResolveUnsupportedGrammarCoverageHookForFixtureGuidance")) return

        val symbol = "$FIXTURE_PROJECT_ROOT/overloads/overloaded-export#getProjectId(string)"
        val result = handler.resolveSymbol(project, symbol)

        assertTrue("Unsupported fixture grammar should fail deterministically", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("Should preserve unsupported_grammar prefix", message.startsWith("unsupported_grammar:"))
        assertTrue("Coverage hook should keep accepted-form guidance visible", message.contains("modulePath#exportName"))
    }

    private fun addWebstormIntegrationFixture(relativePath: String) {
        val sourcePath = Path.of(FIXTURE_SOURCE_ROOT).resolve(relativePath)
        val targetPath = "$FIXTURE_PROJECT_ROOT/$relativePath"
        myFixture.addFileToProject(targetPath, Files.readString(sourcePath))
    }

    private fun fixtureSymbol(relativePath: String, exportName: String): String {
        return "${fixtureModulePath(relativePath)}#$exportName"
    }

    private fun fixtureModulePath(relativePath: String): String {
        return "$FIXTURE_PROJECT_ROOT/${relativePath.removeJsTsExtension()}"
    }

    private fun String.removeJsTsExtension(): String {
        return removeSuffix(".d.ts")
            .removeSuffix(".ts")
            .removeSuffix(".tsx")
            .removeSuffix(".js")
            .removeSuffix(".jsx")
            .removeSuffix(".mjs")
            .removeSuffix(".cjs")
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

    private fun assertContainingFileSuffix(element: PsiNamedElement, expectedSuffix: String) {
        val filePath = element.containingFile?.virtualFile?.path?.replace('\\', '/')
        assertTrue(
            "Expected containing file to end with $expectedSuffix but was $filePath",
            filePath?.endsWith(expectedSuffix) == true
        )
    }
}
