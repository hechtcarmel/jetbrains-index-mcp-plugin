package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.nio.file.Files
import java.nio.file.Path

class JavaScriptSymbolReferenceHandlerTest : McpPlatformTestCase() {

    private companion object {
        const val FIXTURE_SOURCE_ROOT = "src/test/testData/javascript/webstormIntegration"
        const val FIXTURE_PROJECT_ROOT = "src/webstormIntegration"
    }

    private val handler = JavaScriptSymbolReferenceHandler()

    fun testResolveNamedExportSuccess() {
        writeProjectFile(
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
        writeProjectFile(
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
        writeProjectFile(
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
        writeProjectFile(
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
        writeProjectFile(
            "src/utils/format.ts",
            """
            export function formatValue(input: string): string {
                return input;
            }
            """.trimIndent()
        )
        writeProjectFile(
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
        addWebstormIntegrationFixture("export-default-class.ts")

        val result = handler.resolveSymbol(project, fixtureSymbol("export-default-class.ts", "default"))

        assertTrue("Should resolve export default class form", result.isSuccess)
        val element = result.getOrThrow()
        assertNamed(element, "MyWidget")
    }

    fun testResolveDirectFilePrecedenceOverIndex() {
        writeProjectFile(
            "src/utils/format.ts",
            """
            export function formatValue(input: string): string {
                return input;
            }
            """.trimIndent()
        )
        writeProjectFile(
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
        // Fixtures now live on the real filesystem under a shared light project, so this
        // "only foo/index.ts exists" scenario needs a directory no other test writes a
        // sibling foo.ts into.
        writeProjectFile(
            "src/indexFallback/format/index.ts",
            """
            export function formatValue(input: string): string {
                return input.toUpperCase();
            }
            """.trimIndent()
        )
        assertProjectFileAbsent("src/indexFallback/format.ts")

        val result = handler.resolveSymbol(project, "src/indexFallback/format#formatValue")

        assertTrue("Should resolve to index file when only foo/index.ts exists (fallback path)", result.isSuccess)
        val element = result.getOrThrow()
        assertNamed(element, "formatValue")
        assertContainingFileSuffix(element, "src/indexFallback/format/index.ts")
    }

    fun testResolveWorkspacePrefixedModulePathUsesProjectRootBeforeNestedContentRoot() {
        writeProjectFile(
            "packages/app/src/user.ts",
            """
            export class User {
                readonly source = "project-root";
            }
            """.trimIndent()
        )
        writeProjectFile(
            "packages/app/packages/app/src/user.ts",
            """
            export class User {
                readonly source = "duplicated-module-root";
            }
            """.trimIndent()
        )
        val basePath = requireNotNull(project.basePath) { "Project base path is null" }
        val nestedContentRoot = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/packages/app")
        ) { "Nested content root packages/app was not created on disk" }
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
        addWebstormIntegrationFixture("overloads/overloaded-export.ts")

        val result = handler.resolveSymbol(project, fixtureSymbol("overloads/overloaded-export.ts", "getProjectId"))

        assertTrue("Overloaded exported functions should resolve through fixture-backed coverage", result.isSuccess)
        val element = result.getOrThrow()
        assertNamed(element, "getProjectId")
        assertContainingFileSuffix(element, "overloads/overloaded-export.ts")
        assertTrue("Overload resolution should prefer the concrete implementation declaration", element.text.contains("readProjectIdFromConfig"))
    }

    fun testResolveRealisticMultiNamedIndexBarrelFixtureCoverageHook() {
        addWebstormIntegrationFixture("barrels/realistic/config/loader.ts")
        addWebstormIntegrationFixture("barrels/realistic/config/index.ts")

        val result = handler.resolveSymbol(project, fixtureSymbol("barrels/realistic/config/index.ts", "loadPluginConfig"))

        assertTrue("Realistic multi-named index barrel should resolve successfully", result.isSuccess)
        assertContainingFileSuffix(result.getOrThrow(), "barrels/realistic/config/loader.ts")
    }

    fun testResolveNamedBarrelFixtureCoverageHook() {
        addWebstormIntegrationFixture("barrels/plugin-config.ts")
        addWebstormIntegrationFixture("barrels/named-barrel.ts")

        val result = handler.resolveSymbol(project, fixtureSymbol("barrels/named-barrel.ts", "loadPluginConfig"))

        assertTrue("Named re-export barrel fixture should be covered explicitly", result.isSuccess)
        assertNamed(result.getOrThrow(), "loadPluginConfig")
        assertContainingFileSuffix(result.getOrThrow(), "barrels/plugin-config.ts")
    }

    fun testResolveExportStarBarrelFixtureCoverageHook() {
        addWebstormIntegrationFixture("barrels/plugin-config.ts")
        addWebstormIntegrationFixture("barrels/export-star-barrel.ts")

        val result = handler.resolveSymbol(project, fixtureSymbol("barrels/export-star-barrel.ts", "loadPluginConfig"))

        assertTrue("Export-star barrel fixture should be covered explicitly", result.isSuccess)
        assertNamed(result.getOrThrow(), "loadPluginConfig")
        assertContainingFileSuffix(result.getOrThrow(), "barrels/plugin-config.ts")
    }

    fun testResolveBarrelFixturesRemainDisambiguatedAcrossSameNamedExports() {
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
        val symbol = "$FIXTURE_PROJECT_ROOT/overloads/overloaded-export#getProjectId(string)"
        val result = handler.resolveSymbol(project, symbol)

        assertTrue("Unsupported fixture grammar should fail deterministically", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("Should preserve unsupported_grammar prefix", message.startsWith("unsupported_grammar:"))
        assertTrue("Coverage hook should keep accepted-form guidance visible", message.contains("modulePath#exportName"))
    }

    private var fixtureRootRegistered = false

    /**
     * Materializes a JS/TS fixture on the real filesystem under the project root.
     *
     * The fixture root is registered so JS/TS import and re-export resolution — which the
     * barrel fixtures depend on — sees the files as project sources.
     * See [com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase].
     */
    private fun addWebstormIntegrationFixture(relativePath: String) {
        if (!fixtureRootRegistered) {
            registerSourceRoot(FIXTURE_PROJECT_ROOT)
            fixtureRootRegistered = true
        }
        val sourcePath = Path.of(FIXTURE_SOURCE_ROOT).resolve(relativePath)
        writeProjectFile("$FIXTURE_PROJECT_ROOT/$relativePath", Files.readString(sourcePath))
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
