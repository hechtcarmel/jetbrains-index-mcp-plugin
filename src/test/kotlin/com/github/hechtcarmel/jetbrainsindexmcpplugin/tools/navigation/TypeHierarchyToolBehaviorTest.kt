package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume

/**
 * Behavior coverage for `ide_type_hierarchy` supertype reporting on Java hierarchies.
 *
 * For an interface, `PsiClass.getSuperClass()` returns `java.lang.Object`, so
 * `JavaTypeHierarchyHandler.getSupertypes` takes the unresolved-extends fallback branch, which
 * reports each extended superinterface — and `psiClass.interfaces` returns that same set. Without
 * the fallback-dedup guard, every extended interface was reported twice: once with its transitive
 * supertype chain and once with none.
 */
class TypeHierarchyToolBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
    }

    override fun tearDown() {
        try {
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testInterfaceExtendsInterfaceReportsEachSuperinterfaceOnce() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        writeProjectFile(
            "src/hier/GrandParent.java",
            """
                package hier;

                public interface GrandParent {
                }
            """.trimIndent()
        )
        writeProjectFile(
            "src/hier/Parent.java",
            """
                package hier;

                public interface Parent extends GrandParent {
                }
            """.trimIndent()
        )
        writeProjectFile(
            "src/hier/Child.java",
            """
                package hier;

                public interface Child extends Parent {
                }
            """.trimIndent()
        )

        val result = TypeHierarchyTool().execute(project, buildJsonObject {
            put("className", "hier.Child")
        })
        assertToolSucceeded("type hierarchy should succeed for hier.Child", result)

        val hierarchy = json.decodeFromString<TypeHierarchyResult>(toolText(result))
        val supertypeNames = hierarchy.supertypes.map { it.name }
        val parentEntries = hierarchy.supertypes.filter { it.name == "hier.Parent" }
        assertEquals(
            "hier.Parent must appear exactly once in Child's supertypes; got $supertypeNames",
            1,
            parentEntries.size
        )

        // The surviving entry must be the informative one — carrying Parent's own transitive
        // supertype chain, not the interfaces-loop duplicate whose nested supertypes are null.
        val nested = parentEntries.single().supertypes?.map { it.name }.orEmpty()
        assertTrue(
            "The single Parent entry must carry its own supertype hier.GrandParent; got $nested",
            nested.contains("hier.GrandParent")
        )
    }

    fun testDiamondHierarchyKeepsDirectlyImplementedInterface() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        writeProjectFile(
            "src/diamond/Baz.java",
            """
                package diamond;

                public interface Baz {
                }
            """.trimIndent()
        )
        writeProjectFile(
            "src/diamond/Bar.java",
            """
                package diamond;

                public class Bar implements Baz {
                }
            """.trimIndent()
        )
        writeProjectFile(
            "src/diamond/Foo.java",
            """
                package diamond;

                public class Foo extends Bar implements Baz {
                }
            """.trimIndent()
        )

        val result = TypeHierarchyTool().execute(project, buildJsonObject {
            put("className", "diamond.Foo")
        })
        assertToolSucceeded("type hierarchy should succeed for diamond.Foo", result)

        val hierarchy = json.decodeFromString<TypeHierarchyResult>(toolText(result))
        val supertypeNames = hierarchy.supertypes.map { it.name }

        // Bar's own recursion already visited Baz. Foo's *directly implemented* Baz must still
        // be reported — a global-visited guard on the interfaces loop would wrongly drop it.
        assertEquals(
            "diamond.Bar must appear exactly once in Foo's supertypes; got $supertypeNames",
            1,
            supertypeNames.count { it == "diamond.Bar" }
        )
        assertEquals(
            "Foo's directly implemented diamond.Baz must appear exactly once; got $supertypeNames",
            1,
            supertypeNames.count { it == "diamond.Baz" }
        )
    }
}
