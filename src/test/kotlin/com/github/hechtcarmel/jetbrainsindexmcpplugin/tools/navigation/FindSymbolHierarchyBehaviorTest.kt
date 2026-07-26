package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindSymbolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume

/**
 * Behavior coverage for the hierarchy-dedup complement in `ide_find_symbol`.
 *
 * The platform's Go to Symbol stack (`DefaultSymbolNavigationContributor`) suppresses, for
 * unqualified patterns, any method whose super method is also in scope and matches the same
 * pattern. Without `OptimizedSymbolSearch.complementSuppressedOverrides`, an interface method and
 * its implementation that share a name collapse to just the interface method, silently dropping
 * the implementation from tool results.
 */
class FindSymbolHierarchyBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private val interfaceSource = """
        package dao;

        public interface TRCDao {
            java.util.List<String> getAllPublishers();
        }
    """.trimIndent()

    private val implSource = """
        package dao;

        public class TRCDaoImpl implements TRCDao {
            public static final String GET_ALL_PUBLISHERS = "q";

            @Override
            public java.util.List<String> getAllPublishers() {
                return null;
            }
        }
    """.trimIndent()

    private fun writeFixture() {
        registerSourceRoot("src")
        writeProjectFile("src/dao/TRCDao.java", interfaceSource)
        writeProjectFile("src/dao/TRCDaoImpl.java", implSource)
    }

    /** 1-based line of the `getAllPublishers` name identifier within [source]. */
    private fun declarationLine(source: String): Int {
        val offset = source.indexOf("getAllPublishers")
        assertTrue("Fixture must contain the method declaration", offset >= 0)
        return source.substring(0, offset).count { it == '\n' } + 1
    }

    fun testUnqualifiedQueryReturnsBothInterfaceMethodAndImplementation() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        writeFixture()

        val result = FindSymbolTool().execute(project, buildJsonObject {
            put("query", "getAllPublishers")
            put("pageSize", 50)
        })
        assertToolSucceeded("find_symbol should succeed for an unqualified method query", result)

        val payload = json.decodeFromString<FindSymbolResult>(toolText(result))
        val methodLocations = payload.symbols
            .filter { it.name == "getAllPublishers" }
            .map { it.file.substringAfterLast('/') to it.line }
            .toSet()

        assertTrue(
            "Interface declaration TRCDao.getAllPublishers must be in the results; got ${payload.symbols}",
            (("TRCDao.java" to declarationLine(interfaceSource)) in methodLocations)
        )
        assertTrue(
            "Implementation TRCDaoImpl.getAllPublishers must be in the results — the platform's " +
                "hierarchy dedup suppresses it without the complement; got ${payload.symbols}",
            (("TRCDaoImpl.java" to declarationLine(implSource)) in methodLocations)
        )

        // The complement must not double-add anything: every result stays unique by coordinate,
        // including the similarly-named GET_ALL_PUBLISHERS field if the matcher surfaced it.
        val coordinateKeys = payload.symbols.map { "${it.file}:${it.line}:${it.column}:${it.name}" }
        assertEquals(
            "Results must remain deduplicated by file:line:column:name; got ${payload.symbols}",
            coordinateKeys.distinct().size,
            coordinateKeys.size
        )
    }

    fun testQualifiedQueryDoesNotTriggerComplement() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        writeFixture()

        val result = FindSymbolTool().execute(project, buildJsonObject {
            put("query", "TRCDaoImpl.getAllPublishers")
        })
        assertToolSucceeded("find_symbol should succeed for a qualified method query", result)

        val payload = json.decodeFromString<FindSymbolResult>(toolText(result))
        val methodFiles = payload.symbols
            .filter { it.name == "getAllPublishers" }
            .map { it.file.substringAfterLast('/') }

        assertTrue(
            "Qualified query must resolve the implementation; got ${payload.symbols}",
            methodFiles.contains("TRCDaoImpl.java")
        )
        assertFalse(
            "Qualified query must keep filtering by qualifier — the interface method must NOT " +
                "leak in via the complement; got ${payload.symbols}",
            methodFiles.contains("TRCDao.java")
        )
    }
}
