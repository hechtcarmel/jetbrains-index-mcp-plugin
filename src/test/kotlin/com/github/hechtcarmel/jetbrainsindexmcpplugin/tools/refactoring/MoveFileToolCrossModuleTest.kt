package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.suppressWorkspaceModelErrors
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.text
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * The layout from issue #360: three modules, two of which map the same Java package, and a
 * third that consumes that package through a wildcard import.
 *
 * Extends [HeavyPlatformTestCase] because the scenario needs real modules with dependencies
 * between them, which the light fixture forbids ("Adding modules is not permitted in light
 * tests").
 */
class MoveFileToolCrossModuleTest : HeavyPlatformTestCase() {

    private companion object {
        /** `JavaModuleType`'s id; spelled out because `ModuleTypeId` is not on the test classpath. */
        const val JAVA_MODULE_TYPE_ID = "JAVA_MODULE"
    }

    private val basePath: Path
        get() = Path.of(requireNotNull(myProject.basePath) { "Heavy test project has no base path" })

    /**
     * Creates `<name>/src` on disk and registers it as the content and source root of a new
     * Java module.
     */
    private fun addJavaModule(name: String): Module {
        val src = basePath.resolve(name).resolve("src")
        Files.createDirectories(src)
        val srcVf = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(src)) {
            "Failed to refresh VFS for source root $src"
        }
        val module = runWriteAction {
            ModuleManager.getInstance(myProject)
                .newModule(basePath.resolve(name).resolve("$name.iml").toString(), JAVA_MODULE_TYPE_ID)
        }
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.addContentEntry(srcVf).addSourceFolder(srcVf, false)
        }
        return module
    }

    private fun writeFile(relativePath: String, content: String): Path {
        val path = basePath.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)) {
            "Failed to refresh VFS for $path"
        }
        return path
    }

    private fun readFile(relativePath: String): String {
        val path = basePath.resolve(relativePath)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        return Files.readString(path)
    }

    /** Resolves the first non-import code reference named [referenceName] in [relativePath]. */
    private fun resolveReference(relativePath: String, referenceName: String): Path? {
        val virtualFile = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(basePath.resolve(relativePath))
        )
        val psiFile = PsiManager.getInstance(myProject).findFile(virtualFile) as PsiJavaFile
        val reference = PsiTreeUtil.findChildrenOfType(psiFile, PsiJavaCodeReferenceElement::class.java)
            .firstOrNull { it.referenceName == referenceName && PsiTreeUtil.getParentOfType(it, PsiImportStatementBase::class.java) == null }
            ?: error("No reference named '$referenceName' in $relativePath")
        return reference.resolve()?.containingFile?.virtualFile?.toNioPath()
    }

    // Moving Foo from module-a to module-b does not change its fully qualified name, so the
    // consumer in module-c is correct exactly as written: its wildcard import must survive and
    // must still resolve Foo — now in module-b. The issue reported the import being removed
    // and the consumer failing to compile at the next build.
    fun testSamePackageMoveBetweenModulesKeepsWildcardImportInConsumerModule() = suppressWorkspaceModelErrors {
        val moduleA = addJavaModule("module-a")
        val moduleB = addJavaModule("module-b")
        val moduleC = addJavaModule("module-c")
        ModuleRootModificationUtil.addDependency(moduleC, moduleA)
        ModuleRootModificationUtil.addDependency(moduleC, moduleB)

        writeFile(
            "module-a/src/com/example/pkg/Foo.java", """
            package com.example.pkg;

            public class Foo {
                public String describe() {
                    return "foo";
                }
            }
        """.trimIndent()
        )
        writeFile(
            "module-c/src/com/example/client/Client.java", """
            package com.example.client;

            import com.example.pkg.*;

            public class Client {
                public String describe() {
                    return new Foo().describe();
                }
            }
        """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(myProject)

        val result = runBlocking {
            MoveFileTool().execute(myProject, buildJsonObject {
                put("file", "module-a/src/com/example/pkg/Foo.java")
                put("destination", "module-b/src/com/example/pkg")
            })
        }

        assertFalse("Cross-module same-package move should succeed: ${result.text}", result.isFailure)
        assertFalse(
            "Foo.java must be gone from module-a",
            Files.exists(basePath.resolve("module-a/src/com/example/pkg/Foo.java"))
        )
        val movedPath = basePath.resolve("module-b/src/com/example/pkg/Foo.java")
        assertTrue("Foo.java must exist in module-b", Files.exists(movedPath))
        assertTrue(
            "The moved file keeps its package",
            readFile("module-b/src/com/example/pkg/Foo.java").contains("package com.example.pkg;")
        )

        val client = readFile("module-c/src/com/example/client/Client.java")
        assertTrue(
            "The wildcard import for the unchanged package must survive the move.\n--- result ---\n${result.text}\n--- Client.java ---\n$client",
            client.contains("import com.example.pkg.*;")
        )
        assertTrue("The consumer's call site must be untouched:\n$client", client.contains("new Foo().describe()"))
        assertFalse(
            "A same-package move must not expand references to fully qualified names:\n$client",
            client.contains("com.example.pkg.Foo")
        )
        val resolvedPath = resolveReference("module-c/src/com/example/client/Client.java", "Foo")
        assertNotNull("Foo in module-c must still resolve after the move", resolvedPath)
        assertTrue(
            "Foo in module-c resolves into $resolvedPath, expected the moved file in module-b",
            resolvedPath!!.endsWith(Path.of("module-b/src/com/example/pkg/Foo.java"))
        )
    }
}
