package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.java.JpsJavaExtensionService

class BuiltInSearchScopeResolverUnitTest : BasePlatformTestCase() {

    fun testWireValuesAreStable() {
        assertEquals(
            listOf(
                "project_files",
                "project_and_libraries",
                "project_production_files",
                "project_test_files"
            ),
            BuiltInSearchScope.values().map { it.wireValue }
        )
    }

    fun testParseUsesDefaultWhenScopeIsMissing() {
        val arguments = buildJsonObject { }

        val resolved = BuiltInSearchScopeResolver.parse(arguments, BuiltInSearchScope.PROJECT_AND_LIBRARIES)

        assertEquals(BuiltInSearchScope.PROJECT_AND_LIBRARIES, resolved)
    }

    fun testParseRejectsInvalidScopeWithSupportedValues() {
        val arguments = buildJsonObject {
            put("scope", "totally_invalid")
        }

        val error = try {
            BuiltInSearchScopeResolver.parse(arguments, BuiltInSearchScope.PROJECT_FILES)
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertNotNull("Expected invalid scope to be rejected", error)
        val message = error!!.message ?: ""
        assertTrue(message.contains("totally_invalid"))
        assertTrue(message.contains("project_files"))
        assertTrue(message.contains("project_and_libraries"))
        assertTrue(message.contains("project_production_files"))
        assertTrue(message.contains("project_test_files"))
    }

    fun testResolveGlobalScopeFiltersProductionAndTestRoots() {
        val prod = createSourceRoot("prod", isTestSource = false)
        val test = createSourceRoot("test", isTestSource = true)

        val prodFile = createProjectFile(prod, "sample/ProdOnly.kt", "class ProdOnly")
        val testFile = createProjectFile(test, "sample/TestOnly.kt", "class TestOnly")

        val productionScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, BuiltInSearchScope.PROJECT_PRODUCTION_FILES)
        val testScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, BuiltInSearchScope.PROJECT_TEST_FILES)

        assertTrue(productionScope.contains(prodFile.virtualFile))
        assertFalse(productionScope.contains(testFile.virtualFile))
        assertFalse(testScope.contains(prodFile.virtualFile))
        assertTrue(testScope.contains(testFile.virtualFile))
    }

    fun testResolveSearchScopeReturnsGlobalScopeForBuiltInValues() {
        val prod = createSourceRoot("prod-search", isTestSource = false)
        val prodFile = createProjectFile(prod, "sample/Searchable.kt", "class Searchable")

        val scope = BuiltInSearchScopeResolver.resolveSearchScope(project, BuiltInSearchScope.PROJECT_FILES)

        assertTrue(scope is GlobalSearchScope)
        assertTrue((scope as GlobalSearchScope).contains(prodFile.virtualFile))
    }

    fun testResolveGlobalScopeExcludesGeneratedSourcesByDefault() {
        val prod = createSourceRoot("gen-prod", isTestSource = false)
        val genRoot = createGeneratedSourceRoot("gen-build/generated")

        val handWritten = createProjectFile(prod, "sample/HandWritten.kt", "class HandWritten")
        val generated = createProjectFile(genRoot, "sample/Generated_Factory.kt", "class Generated_Factory")

        // Default (excludeGenerated = true): hand-written stays, generated is filtered out.
        val defaultScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, BuiltInSearchScope.PROJECT_FILES)
        assertTrue("Hand-written source must remain in default scope", defaultScope.contains(handWritten.virtualFile))
        assertFalse("Generated source must be excluded by default", defaultScope.contains(generated.virtualFile))

        // Opt-out (excludeGenerated = false): generated becomes visible again.
        val inclusiveScope = BuiltInSearchScopeResolver.resolveGlobalScope(
            project,
            BuiltInSearchScope.PROJECT_FILES,
            excludeGenerated = false
        )
        assertTrue("Hand-written source must remain when generated are included", inclusiveScope.contains(handWritten.virtualFile))
        assertTrue("Generated source must be visible when excludeGenerated=false", inclusiveScope.contains(generated.virtualFile))
    }

    private fun createSourceRoot(name: String, isTestSource: Boolean): Path {
        val path = Path.of(project.basePath ?: error("Project base path is required")).resolve(name)
        Files.createDirectories(path)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ?: error("Failed to refresh $name source root")
        PsiTestUtil.addSourceRoot(module, virtualFile, isTestSource)
        return path
    }

    private fun createGeneratedSourceRoot(name: String): Path {
        val path = Path.of(project.basePath ?: error("Project base path is required")).resolve(name)
        Files.createDirectories(path)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ?: error("Failed to refresh $name generated source root")
        // Register as a generated source root so ProjectFileIndex.isInGeneratedSources() reports true,
        // mirroring how Gradle import marks build/generated/ksp output.
        val properties = JpsJavaExtensionService.getInstance()
            .createSourceRootProperties("", true /* forGeneratedSources */)
        PsiTestUtil.addSourceRoot(module, virtualFile, JavaSourceRootType.SOURCE, properties)
        return path
    }

    private fun createProjectFile(root: Path, relativePath: String, content: String) = run {
        val filePath = root.resolve(relativePath)
        Files.createDirectories(filePath.parent)
        Files.writeString(filePath, content)

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath)
            ?: error("Failed to refresh $relativePath")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        PsiManager.getInstance(project).findFile(virtualFile)
            ?: error("Failed to create PSI for $relativePath")
    }
}
