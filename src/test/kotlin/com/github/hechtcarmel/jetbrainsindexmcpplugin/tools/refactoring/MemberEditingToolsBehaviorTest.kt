package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume

class MemberEditingToolsBehaviorTest : BasePlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun writeProjectFile(relativePath: String, content: String): Path {
        val basePath = requireNotNull(project.basePath)
        val path = Path.of(basePath, relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())) {
            "Failed to refresh VFS for test file $path"
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return path
    }

    private fun readProjectFile(relativePath: String): String {
        val basePath = requireNotNull(project.basePath)
        return Files.readString(Path.of(basePath, relativePath))
    }

    private fun parseResult(result: com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult): MemberEditResult {
        return json.decodeFromString<MemberEditResult>((result.content.single() as ContentBlock.Text).text)
    }

    // ── Java: ide_replace_member ──

    fun testJavaReplaceMethodBody() = runBlocking {
        writeProjectFile("src/Calculator.java", """
            public class Calculator {
                public int add(int a, int b) {
                    return a + b;
                }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/Calculator.java")
            put("class", "Calculator")
            put("member", "add")
            put("content", "\n        return a + b + 1;\n    ")
        })

        assertFalse("Replace body should succeed", result.isError)
        val payload = parseResult(result)
        assertTrue(payload.success)

        val content = readProjectFile("src/Calculator.java")
        assertTrue("File should contain new body", content.contains("a + b + 1"))
        assertTrue("File should still have method signature", content.contains("public int add(int a, int b)"))
    }

    fun testJavaReplaceFieldInitializer() = runBlocking {
        writeProjectFile("src/Config.java", """
            public class Config {
                private int timeout = 30;
                public String getName() { return "config"; }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/Config.java")
            put("class", "Config")
            put("member", "timeout")
            put("content", "60")
        })

        assertFalse("Replace field initializer should succeed", result.isError)
        val content = readProjectFile("src/Config.java")
        assertTrue("Field should have new value", content.contains("60"))
    }

    fun testJavaReplaceMemberNotFound() = runBlocking {
        writeProjectFile("src/Empty.java", """
            public class Empty {
                public void doWork() {}
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/Empty.java")
            put("class", "Empty")
            put("member", "nonExistent")
            put("content", "return;")
        })

        assertTrue("Should fail for missing member", result.isError || run {
            val text = (result.content.single() as ContentBlock.Text).text
            text.contains("member_not_found")
        })
    }

    fun testJavaReplaceOverloadedMethodDisambiguatesByParameterCount() = runBlocking {
        writeProjectFile("src/Overloaded.java", """
            public class Overloaded {
                public void process(String s) {
                    System.out.println(s);
                }
                public void process(String s, int n) {
                    System.out.println(s + n);
                }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/Overloaded.java")
            put("class", "Overloaded")
            put("member", "process")
            put("parameterCount", 1)
            put("content", "\n        System.out.println(\"replaced\");\n    ")
        })

        assertFalse("Disambiguated replace should succeed", result.isError)
        val content = readProjectFile("src/Overloaded.java")
        assertTrue("Single-param method should be replaced", content.contains("replaced"))
        assertTrue("Two-param method should be unchanged", content.contains("s + n"))
    }

    fun testJavaReplaceOverloadedMethodReturnsAmbiguousError() = runBlocking {
        writeProjectFile("src/Ambiguous.java", """
            public class Ambiguous {
                public void run(String s) {}
                public void run(int n) {}
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/Ambiguous.java")
            put("class", "Ambiguous")
            put("member", "run")
            put("content", "return;")
        })

        val text = (result.content.single() as ContentBlock.Text).text
        assertTrue("Should return ambiguous error", text.contains("ambiguous_member"))
        assertTrue("Should list candidates", text.contains("candidates"))
    }

    // ── Java: ide_edit_member ──

    fun testJavaEditMemberReplacesEntireDeclaration() = runBlocking {
        writeProjectFile("src/Service.java", """
            public class Service {
                public String getName() {
                    return "old";
                }
            }
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/Service.java")
            put("class", "Service")
            put("member", "getName")
            put("content", "public String getFullName() {\n        return \"new\";\n    }")
        })

        assertFalse("Edit member should succeed", result.isError)
        val content = readProjectFile("src/Service.java")
        assertTrue("Should have new method name", content.contains("getFullName"))
        assertFalse("Old method name should be gone", content.contains("getName"))
        assertTrue("Should have new body", content.contains("\"new\""))
    }

    // ── Java: ide_insert_member ──

    fun testJavaInsertMemberAtEnd() = runBlocking {
        writeProjectFile("src/Base.java", """
            public class Base {
                public void existing() {}
            }
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/Base.java")
            put("class", "Base")
            put("content", "public void newMethod() {\n        System.out.println(\"inserted\");\n    }")
        })

        assertFalse("Insert should succeed", result.isError)
        val content = readProjectFile("src/Base.java")
        assertTrue("Should contain new method", content.contains("newMethod"))
        assertTrue("Should still contain existing method", content.contains("existing"))
    }

    fun testJavaInsertMemberBeforeAnchor() = runBlocking {
        writeProjectFile("src/Ordered.java", """
            public class Ordered {
                public void alpha() {}
                public void gamma() {}
            }
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/Ordered.java")
            put("class", "Ordered")
            put("content", "public void beta() {}")
            put("position", "before")
            put("anchor", "gamma")
        })

        assertFalse("Insert before should succeed", result.isError)
        val content = readProjectFile("src/Ordered.java")
        assertTrue("Should contain beta", content.contains("beta"))
        val betaPos = content.indexOf("beta")
        val gammaPos = content.indexOf("gamma")
        assertTrue("beta should appear before gamma", betaPos < gammaPos)
    }

    fun testJavaInsertMemberAfterAnchor() = runBlocking {
        writeProjectFile("src/AfterTest.java", """
            public class AfterTest {
                public void first() {}
                public void third() {}
            }
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/AfterTest.java")
            put("class", "AfterTest")
            put("content", "public void second() {}")
            put("position", "after")
            put("anchor", "first")
        })

        assertFalse("Insert after should succeed", result.isError)
        val content = readProjectFile("src/AfterTest.java")
        val secondPos = content.indexOf("second")
        val firstPos = content.indexOf("first")
        val thirdPos = content.indexOf("third")
        assertTrue("second should appear after first", secondPos > firstPos)
        assertTrue("second should appear before third", secondPos < thirdPos)
    }

    // ── Java: ide_edit_member replaces large method with short one without IndexOutOfBoundsException ──

    fun testEditMemberShorterReplacementDoesNotThrow() = runBlocking {
        val longBody = (1..20).joinToString("\n") { "                    System.out.println(\"line$it\");" }
        writeProjectFile("src/Shrink.java",
            "public class Shrink {\n    public void verbose() {\n$longBody\n    }\n}\n")

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/Shrink.java")
            put("class", "Shrink")
            put("member", "verbose")
            put("content", "public void verbose() { }")
        })

        assertFalse("Edit should succeed without IndexOutOfBoundsException: ${(result.content.singleOrNull() as? ContentBlock.Text)?.text}", result.isError)
        val parsed = json.decodeFromString<MemberEditResult>((result.content.single() as ContentBlock.Text).text)
        assertTrue("startLine must be positive", parsed.startLine!! > 0)
        assertTrue("endLine must be >= startLine", parsed.endLine!! >= parsed.startLine!!)
        assertTrue("endLine must not exceed document line count", parsed.endLine!! <= 10)
    }

    // ── Java: ide_file_structure endLine ──

    fun testJavaFileStructureIncludesEndLine() = runBlocking {
        com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry.registerHandlers()
        if (!com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry.hasStructureHandlers()) {
            System.err.println("testJavaFileStructureIncludesEndLine: skipped - no structure handlers available")
            Assume.assumeTrue("Required class not available in test sandbox", false)
        }

        writeProjectFile("src/Structured.java", """
            public class Structured {
                private int count = 0;

                public void longMethod() {
                    int a = 1;
                    int b = 2;
                    int c = a + b;
                    System.out.println(c);
                }

                public void shortMethod() {
                    return;
                }
            }
        """.trimIndent())

        val tool = try {
            Class.forName("com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FileStructureTool")
                .getDeclaredConstructor().newInstance() as com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
        } catch (_: Exception) {
            Assume.assumeTrue("FileStructureTool not available in test sandbox", false)
            return@runBlocking
        }

        val result = tool.execute(project, buildJsonObject {
            put("file", "src/Structured.java")
        })

        val text = (result.content.single() as ContentBlock.Text).text
        assertFalse("File structure should succeed: $text", result.isError)
        assertTrue("Should contain 'lines' (endLine present): $text", text.contains("lines"))
        assertTrue("Should contain longMethod", text.contains("longMethod"))
        assertTrue("Should contain shortMethod", text.contains("shortMethod"))
    }

    // ── Java: error cases ──

    fun testClassNotFoundReturnsError() = runBlocking {
        writeProjectFile("src/Solo.java", """
            public class Solo {
                public void method() {}
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/Solo.java")
            put("class", "NonExistent")
            put("member", "method")
            put("content", "return;")
        })

        assertTrue("Should fail for missing class", result.isError || run {
            val text = (result.content.single() as ContentBlock.Text).text
            text.contains("not found")
        })
    }

    fun testAbstractMethodHasNoBodyToReplace() = runBlocking {
        writeProjectFile("src/AbstractService.java", """
            public abstract class AbstractService {
                public abstract void process();
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/AbstractService.java")
            put("class", "AbstractService")
            put("member", "process")
            put("content", "System.out.println(\"hi\");")
        })

        val text = (result.content.single() as ContentBlock.Text).text
        assertTrue("Should indicate no body", text.contains("no body") || text.contains("ide_edit_member"))
    }

    fun testFileNotFoundReturnsError() = runBlocking {
        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/DoesNotExist.java")
            put("class", "Foo")
            put("member", "bar")
            put("content", "return;")
        })

        assertTrue("Should fail for missing file", result.isError)
    }

    // ── Java: insert without class specified ──

    fun testJavaInsertWithoutClassInfersSingleClass() = runBlocking {
        writeProjectFile("src/SingleClass.java", """
            public class SingleClass {
                public void existing() {}
            }
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/SingleClass.java")
            put("content", "public void added() {}")
        })

        assertFalse("Insert without class should succeed for single-class file", result.isError)
        val content = readProjectFile("src/SingleClass.java")
        assertTrue("Method should be inside the class", content.contains("added"))
        val addedPos = content.indexOf("added")
        val closingBrace = content.lastIndexOf("}")
        assertTrue("Method should be before the class closing brace", addedPos < closingBrace)
    }

    fun testJavaInsertWithoutClassAndMultipleClassesFails() = runBlocking {
        writeProjectFile("src/MultiClass.java", """
            class First {}
            class Second {}
        """.trimIndent())

        val result = InsertMemberTool().execute(project, buildJsonObject {
            put("file", "src/MultiClass.java")
            put("content", "public void ambiguous() {}")
        })

        val text = (result.content.single() as ContentBlock.Text).text
        assertTrue("Should fail for ambiguous class scope",
            result.isError || text.contains("not found") || text.contains("Cannot insert"))
    }

    // ── Java: class/interface declaration editing ──

    fun testJavaEditClassDeclarationAddsTypeParameter() = runBlocking {
        writeProjectFile("src/GenericTarget.java", """
            public interface GenericTarget {
                void process();
            }
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/GenericTarget.java")
            put("class", "GenericTarget")
            put("member", "GenericTarget")
            put("content", "public interface GenericTarget<T> {\n    T process();\n}")
        })

        assertFalse("Edit class declaration should succeed", result.isError)
        val content = readProjectFile("src/GenericTarget.java")
        assertTrue("Should have type parameter", content.contains("GenericTarget<T>"))
        assertTrue("Should have updated method", content.contains("T process()"))
    }

    fun testJavaEditClassDeclarationChangesImplements() = runBlocking {
        writeProjectFile("src/ImplTarget.java", """
            public class ImplTarget {
                public void run() {}
            }
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/ImplTarget.java")
            put("class", "ImplTarget")
            put("member", "ImplTarget")
            put("content", "public class ImplTarget implements Runnable {\n    public void run() {}\n}")
        })

        assertFalse("Edit class implements should succeed", result.isError)
        val content = readProjectFile("src/ImplTarget.java")
        assertTrue("Should have implements", content.contains("implements Runnable"))
    }

    fun testJavaEditTopLevelClassWithoutClassParam() = runBlocking {
        writeProjectFile("src/TopLevel.java", """
            public class TopLevel {
                public void method() {}
            }
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/TopLevel.java")
            put("member", "TopLevel")
            put("content", "public abstract class TopLevel {\n    public abstract void method();\n}")
        })

        assertFalse("Edit top-level class without class param should succeed", result.isError)
        val content = readProjectFile("src/TopLevel.java")
        assertTrue("Should be abstract", content.contains("abstract class TopLevel"))
    }

    // ── Java: record declaration editing ──

    fun testJavaEditRecordDeclaration() = runBlocking {
        writeProjectFile("src/RecordTarget.java", """
            public record RecordTarget(String name, int age) {
                public String displayName() {
                    return name;
                }
            }
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/RecordTarget.java")
            put("class", "RecordTarget")
            put("member", "RecordTarget")
            put("content", "public record RecordTarget(String name, int age, String email) implements Serializable {\n    public String displayName() {\n        return name + \" <\" + email + \">\";\n    }\n}")
        })

        assertFalse("Edit record declaration should succeed: ${(result.content.singleOrNull() as? ContentBlock.Text)?.text}", result.isError)
        val content = readProjectFile("src/RecordTarget.java")
        assertTrue("Should have new component", content.contains("String email"))
        assertTrue("Should have implements", content.contains("implements Serializable"))
        assertTrue("Should have updated method", content.contains("email"))
        assertFalse("Should not have nested record", content.contains("record RecordTarget(String name, int age) {"))
    }

    // ── Java: static initializer block ──

    fun testJavaReplaceStaticInitializerBody() = runBlocking {
        writeProjectFile("src/WithStaticInit.java", """
            public class WithStaticInit {
                private static int value;
                static {
                    value = 42;
                }
                public static int getValue() { return value; }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/WithStaticInit.java")
            put("class", "WithStaticInit")
            put("member", "static")
            put("content", "\n        value = 99;\n    ")
        })

        assertFalse("Replace static init body should succeed: ${(result.content.singleOrNull() as? ContentBlock.Text)?.text}", result.isError)
        val content = readProjectFile("src/WithStaticInit.java")
        assertTrue("Should contain new value", content.contains("99"))
        assertFalse("Old value should be gone", content.contains("42"))
    }

    fun testJavaEditStaticInitializerFull() = runBlocking {
        writeProjectFile("src/WithStaticInit2.java", """
            public class WithStaticInit2 {
                private static String label;
                static {
                    label = "old";
                }
            }
        """.trimIndent())

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", "src/WithStaticInit2.java")
            put("class", "WithStaticInit2")
            put("member", "static")
            put("content", "static {\n        label = \"new\";\n        System.out.println(label);\n    }")
        })

        assertFalse("Edit static init should succeed", result.isError)
        val content = readProjectFile("src/WithStaticInit2.java")
        assertTrue("Should contain new body", content.contains("\"new\""))
        assertTrue("Should contain println", content.contains("System.out.println"))
    }

    // ── Java: auto-import after edit ──

    fun testJavaEditMemberWithReformatDoesNotCrash() = runBlocking {
        writeProjectFile("src/ImportTest.java", """
            import java.util.List;
            import java.util.Map;

            public class ImportTest {
                public List<String> getItems() {
                    return null;
                }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/ImportTest.java")
            put("class", "ImportTest")
            put("member", "getItems")
            put("content", "\n        return null;\n    ")
            put("reformat", true)
        })

        assertFalse("Replace with reformat+import optimization should succeed", result.isError)
        val payload = parseResult(result)
        assertTrue(payload.success)
    }
}
