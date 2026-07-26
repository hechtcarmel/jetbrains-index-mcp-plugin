package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindClassResult
import com.intellij.openapi.roots.LanguageLevelProjectExtension
import com.intellij.pom.java.LanguageLevel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume

/**
 * Verifies ide_find_class reports the declaration form (`SymbolMatch.kind`) semantically.
 *
 * The pre-fix implementation classified by the runtime PSI implementation class name, and for
 * Java every declaration form (class, interface, enum, @interface, record) is a `PsiClassImpl`,
 * so everything came back as "CLASS". These tests query each declaration form through the full
 * tool path and assert the exact kind, so they fail if the semantic probes are removed. The
 * plain-class case pins the fallback so an inverted fix cannot pass either.
 */
class FindClassKindBehaviorTest : McpPlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private suspend fun kindOf(name: String): String {
        val result = FindClassTool().execute(project, buildJsonObject {
            put("query", name)
            put("matchMode", "exact")
        })
        assertToolSucceeded("ide_find_class should succeed for '$name'", result)
        val parsed = json.decodeFromString<FindClassResult>(toolText(result))
        val match = parsed.classes.firstOrNull { it.name == name }
        assertNotNull(
            "Expected '$name' in ide_find_class results, got: ${parsed.classes.map { it.name }}",
            match
        )
        return match!!.kind
    }

    fun testKindReflectsJavaDeclarationForm() = runBlocking {
        registerSourceRoot("kinds-src")
        writeProjectFile("kinds-src/com/example/Payment.java", """
            package com.example;

            public interface Payment {}
        """.trimIndent())
        writeProjectFile("kinds-src/com/example/Color.java", """
            package com.example;

            public enum Color { RED }
        """.trimIndent())
        writeProjectFile("kinds-src/com/example/Marker.java", """
            package com.example;

            public @interface Marker {}
        """.trimIndent())
        writeProjectFile("kinds-src/com/example/Simple.java", """
            package com.example;

            public class Simple {}
        """.trimIndent())

        assertEquals("Interface should be reported as INTERFACE", "INTERFACE", kindOf("Payment"))
        assertEquals("Enum should be reported as ENUM", "ENUM", kindOf("Color"))
        assertEquals("Annotation type should be reported as ANNOTATION", "ANNOTATION", kindOf("Marker"))
        assertEquals("Plain class should still be reported as CLASS", "CLASS", kindOf("Simple"))
    }

    fun testRecordKind() = runBlocking {
        Assume.assumeTrue(
            "Project language level must support records (JDK 16+)",
            LanguageLevelProjectExtension.getInstance(project).languageLevel.isAtLeast(LanguageLevel.JDK_16)
        )
        registerSourceRoot("kinds-record")
        writeProjectFile("kinds-record/com/example/Point.java", """
            package com.example;

            public record Point(int x, int y) {}
        """.trimIndent())

        assertEquals("Record should be reported as RECORD", "RECORD", kindOf("Point"))
    }
}
