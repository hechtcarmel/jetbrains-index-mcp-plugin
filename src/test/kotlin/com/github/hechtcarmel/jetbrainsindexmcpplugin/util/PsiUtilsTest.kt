package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Platform tests for [PsiUtils] reference resolution functionality.
 *
 * Tests verify that [PsiUtils.resolveTargetElement] correctly handles:
 * - Method call references (resolves to the called method)
 * - Field access references (resolves to the field)
 * - Declaration positions (returns the declaration itself)
 */
class PsiUtilsTest : BasePlatformTestCase() {

    fun testResolveTargetElement_MethodCallResolvesToDeclaration() {
        // Create a service class with a method
        myFixture.configureByText(
            "Service.java",
            """
            public class Service {
                public void doWork() {}
            }
            """.trimIndent()
        )

        // Create a caller class that uses the service
        val callerFile = myFixture.configureByText(
            "Caller.java",
            """
            public class Caller {
                private Service service = new Service();
                public void call() {
                    service.do<caret>Work();
                }
            }
            """.trimIndent()
        )

        val element = callerFile.findElementAt(myFixture.caretOffset)
        assertNotNull("Should find element at caret", element)

        val resolved = PsiUtils.resolveTargetElement(element!!)
        assertNotNull("Should resolve to declaration", resolved)
        assertTrue("Should resolve to PsiMethod", resolved is PsiMethod)
        assertEquals("doWork", (resolved as PsiMethod).name)
    }

    fun testResolveTargetElement_FieldAccessResolvesToField() {
        // Create a class with a field
        myFixture.configureByText(
            "Data.java",
            """
            public class Data {
                public String name;
            }
            """.trimIndent()
        )

        // Create a class that accesses the field
        val accessorFile = myFixture.configureByText(
            "Accessor.java",
            """
            public class Accessor {
                public void access() {
                    Data data = new Data();
                    String n = data.na<caret>me;
                }
            }
            """.trimIndent()
        )

        val element = accessorFile.findElementAt(myFixture.caretOffset)
        assertNotNull("Should find element at caret", element)

        val resolved = PsiUtils.resolveTargetElement(element!!)
        assertNotNull("Should resolve to declaration", resolved)
        assertTrue("Should resolve to PsiField", resolved is PsiField)
        assertEquals("name", (resolved as PsiField).name)
    }

    fun testResolveTargetElement_OnDeclarationReturnsItself() {
        val file = myFixture.configureByText(
            "MyClass.java",
            """
            public class MyClass {
                public void my<caret>Method() {}
            }
            """.trimIndent()
        )

        val element = file.findElementAt(myFixture.caretOffset)
        assertNotNull("Should find element at caret", element)

        val resolved = PsiUtils.resolveTargetElement(element!!)
        assertNotNull("Should resolve declaration", resolved)
        assertTrue("Should be PsiMethod", resolved is PsiMethod)
        assertEquals("myMethod", (resolved as PsiMethod).name)
    }

    fun testResolveTargetElement_ClassReferenceResolvesToClass() {
        // Create a target class
        myFixture.configureByText(
            "TargetClass.java",
            """
            public class TargetClass {
                public void method() {}
            }
            """.trimIndent()
        )

        // Create a class that references TargetClass
        val usageFile = myFixture.configureByText(
            "Usage.java",
            """
            public class Usage {
                private Target<caret>Class target;
            }
            """.trimIndent()
        )

        val element = usageFile.findElementAt(myFixture.caretOffset)
        assertNotNull("Should find element at caret", element)

        val resolved = PsiUtils.resolveTargetElement(element!!)
        assertNotNull("Should resolve to class", resolved)
        assertTrue("Should be PsiClass", resolved is PsiClass)
        assertEquals("TargetClass", (resolved as PsiClass).name)
    }

    fun testFindReferenceInParent_MaxDepthRespected() {
        val file = myFixture.configureByText(
            "Test.java",
            """
            public class Test {
                public void method() {
                    int x<caret> = 5;
                }
            }
            """.trimIndent()
        )

        val element = file.findElementAt(myFixture.caretOffset)
        assertNotNull("Should find element at caret", element)

        // With depth 0, should return null (no search)
        val ref0 = PsiUtils.findReferenceInParent(element!!, 0)
        assertNull("Depth 0 should return null", ref0)
    }

    fun testFindReferenceInParent_ReturnsNullAtRoot() {
        val file = myFixture.configureByText(
            "Root.java",
            """
            public class Ro<caret>ot {}
            """.trimIndent()
        )

        val element = file.findElementAt(myFixture.caretOffset)
        assertNotNull("Should find element at caret", element)

        // Walk up parent chain - at a class declaration, there's no reference to find
        // (The element IS the declaration, not a reference to one)
        val ref = PsiUtils.findReferenceInParent(element!!, 100)
        assertNull("Declaration should not have a reference in parent chain", ref)
    }

    fun testResolveTargetElement_UnresolvableReturnsNamedElement() {
        // Create a file with a local variable that has no reference
        val file = myFixture.configureByText(
            "Local.java",
            """
            public class Local {
                public void method() {
                    int local<caret>Var = 42;
                }
            }
            """.trimIndent()
        )

        val element = file.findElementAt(myFixture.caretOffset)
        assertNotNull("Should find element at caret", element)

        // When on a declaration (not a reference), should return the named element
        val resolved = PsiUtils.resolveTargetElement(element!!)
        assertNotNull("Should return named element for declaration", resolved)
    }

    fun testGetFileContentByLines_ClampsToDocumentRange() {
        val psiFile = myFixture.configureByText(
            "Lines.java",
            """
            public class Lines {
                public void a() {}
                public void b() {}
                public void c() {}
            }
            """.trimIndent()
        )

        val virtualFile = psiFile.virtualFile
        val content = PsiUtils.getFileContentByLines(project, virtualFile, 2, 3)
        assertNotNull("Content should be returned", content)
        assertEquals("    public void a() {}\n    public void b() {}", content)

        val outOfRange = PsiUtils.getFileContentByLines(project, virtualFile, 10, 12)
        assertEquals("Out-of-range lines should return empty string", "", outOfRange)
    }

    fun testResolveVirtualFileAnywhere_ResolvesJarEntry() {
        val tempDir = Files.createTempDirectory("jetbrains-index-mcp").toFile()
        val jarFile = Files.createTempFile(tempDir.toPath(), "sample", ".jar").toFile()
        val entryPath = "com/example/Sample.txt"

        JarOutputStream(FileOutputStream(jarFile)).use { jarStream ->
            jarStream.putNextEntry(JarEntry(entryPath))
            jarStream.write("hello".toByteArray())
            jarStream.closeEntry()
        }

        // Ensure VFS sees the jar
        LocalFileSystem.getInstance().refreshAndFindFileByPath(jarFile.absolutePath)

        // Use forward slashes in jar URL — backslashes produce invalid URLs on Windows
        val jarUrlPath = jarFile.absolutePath.replace('\\', '/')
        ModuleRootModificationUtil.addModuleLibrary(module, "jar://$jarUrlPath!/")

        val resolved = PsiUtils.resolveVirtualFileAnywhere(project, "${jarFile.absolutePath}!/$entryPath")
        assertNotNull("Jar entry should resolve to a VirtualFile", resolved)
        assertEquals("Sample.txt", resolved?.name)

        val resolvedByUrl = PsiUtils.resolveVirtualFileAnywhere(project, "jar://${jarFile.absolutePath}!/$entryPath")
        assertNotNull("Jar URL should resolve to a VirtualFile", resolvedByUrl)
        assertEquals("Sample.txt", resolvedByUrl?.name)
    }

    fun testResolveVirtualFileAnywhere_BlocksJarNotInProjectLibraries() {
        val tempDir = Files.createTempDirectory("jetbrains-index-mcp").toFile()
        val jarFile = Files.createTempFile(tempDir.toPath(), "sample", ".jar").toFile()
        val entryPath = "com/example/Sample.txt"

        JarOutputStream(FileOutputStream(jarFile)).use { jarStream ->
            jarStream.putNextEntry(JarEntry(entryPath))
            jarStream.write("hello".toByteArray())
            jarStream.closeEntry()
        }

        // Ensure VFS sees the jar
        LocalFileSystem.getInstance().refreshAndFindFileByPath(jarFile.absolutePath)

        val resolved = PsiUtils.resolveVirtualFileAnywhere(project, "${jarFile.absolutePath}!/$entryPath")
        assertNull("JAR not in project libraries should be blocked", resolved)

        val resolvedByUrl = PsiUtils.resolveVirtualFileAnywhere(project, "jar://${jarFile.absolutePath}!/$entryPath")
        assertNull("JAR URL not in project libraries should be blocked", resolvedByUrl)
    }

    fun testResolveVirtualFileAnywhere_BlocksLocalFileOutsideProjectAndLibraries() {
        val outsideFile = Files.createTempFile("jetbrains-index-mcp-outside", ".txt").toFile()
        try {
            outsideFile.writeText("secret")
            // Make the file visible to the VFS so a missing containment guard would resolve it.
            assertNotNull(
                "Test setup: VFS must see the outside file",
                LocalFileSystem.getInstance().refreshAndFindFileByPath(outsideFile.absolutePath)
            )

            val resolved = PsiUtils.resolveVirtualFileAnywhere(project, outsideFile.absolutePath)
            assertNull("Local file outside project content roots and libraries should be blocked", resolved)
        } finally {
            outsideFile.delete()
        }
    }

    fun testResolveVirtualFileAnywhere_BlocksTildeExpandedHomePath() {
        val homeDir = Path.of(System.getProperty("user.home"))
        val homeFile = Files.createTempFile(homeDir, "jetbrains-index-mcp-home", ".txt").toFile()
        try {
            homeFile.writeText("secret")
            assertNotNull(
                "Test setup: VFS must see the home file",
                LocalFileSystem.getInstance().refreshAndFindFileByPath(homeFile.absolutePath)
            )

            val resolved = PsiUtils.resolveVirtualFileAnywhere(project, "~/${homeFile.name}")
            assertNull("~-expanded path outside the project should be blocked", resolved)
        } finally {
            homeFile.delete()
        }
    }

    fun testResolveVirtualFileAnywhere_ResolvesLibraryRootFileOutsideProject() {
        val libDir = Files.createTempDirectory("jetbrains-index-mcp-lib").toFile()
        val libFile = File(libDir, "Library.txt")
        libFile.writeText("library content")
        LocalFileSystem.getInstance().refreshAndFindFileByPath(libFile.absolutePath)

        // Register the directory as a module library root — files under it are dependency
        // files and must stay readable even though they live outside the project.
        ModuleRootModificationUtil.addModuleLibrary(module, "file://${libDir.absolutePath.replace('\\', '/')}")

        val resolved = PsiUtils.resolveVirtualFileAnywhere(project, libFile.absolutePath)
        assertNotNull("File under a registered library root must stay readable", resolved)
        assertEquals("Library.txt", resolved?.name)
    }
}
