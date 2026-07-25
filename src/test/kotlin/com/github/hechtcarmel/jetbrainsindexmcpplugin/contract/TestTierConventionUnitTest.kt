package com.github.hechtcarmel.jetbrainsindexmcpplugin.contract

import junit.framework.TestCase
import java.io.File
import java.nio.file.Files

/**
 * Enforces the test tier split that `./gradlew test -Ptier=unit|platform` relies on.
 *
 * The tiers are selected purely by class-name suffix, so a misnamed class silently lands in the
 * wrong tier. That is not hypothetical: `BuiltInSearchScopeResolverUnitTest` extended
 * `BasePlatformTestCase` and did real indexing, which meant the "fast, no IDE needed" command
 * booted the full platform.
 */
class TestTierConventionUnitTest : TestCase() {

    private companion object {
        const val PLATFORM_BASE = "com.intellij.testFramework.fixtures.BasePlatformTestCase"
    }

    fun testNoUnitTestExtendsBasePlatformTestCase() {
        val offenders = testClasses()
            .filter { it.simpleName.endsWith("UnitTest") }
            .filter { it.isPlatformTest() }
            .map { it.name }
            .sorted()

        assertEquals(
            "A *UnitTest must not require the IntelliJ Platform fixture — it lands in the fast " +
                "tier, where booting the platform defeats the split. Rename these to *Test, or " +
                "drop the BasePlatformTestCase dependency.",
            emptyList<String>(),
            offenders
        )
    }

    fun testConventionScannerActuallyFoundClasses() {
        // Without this, a broken scanner would make the rule above vacuously green.
        val classes = testClasses()
        assertTrue(
            "Scanner found no test classes — the convention check above would pass vacuously.",
            classes.size > 50
        )
        assertTrue(
            "Scanner found no platform tests, so isPlatformTest() is likely broken.",
            classes.any { it.isPlatformTest() }
        )
    }

    private fun Class<*>.isPlatformTest(): Boolean {
        var current: Class<*>? = superclass
        while (current != null) {
            if (current.name == PLATFORM_BASE) return true
            current = current.superclass
        }
        return false
    }

    /**
     * Loads every compiled test class from this class's own output root, so the scan does not
     * depend on the working directory.
     *
     * The root is derived from this class's own `.class` resource rather than from
     * `protectionDomain.codeSource`, which is null under IntelliJ's `PathClassLoader`.
     */
    private fun testClasses(): List<Class<*>> {
        val ownResource = javaClass.name.replace('.', '/') + ".class"
        val url = javaClass.classLoader.getResource(ownResource) ?: return emptyList()
        if (url.protocol != "file") return emptyList()
        val ownClassFile = File(url.toURI())
        // Walk up one directory per package segment to reach the output root.
        var root = ownClassFile.parentFile
        repeat(javaClass.name.count { it == '.' }) { root = root.parentFile }
        if (!root.isDirectory) return emptyList()

        return Files.walk(root.toPath()).use { paths ->
            paths.filter { it.toString().endsWith(".class") }
                .map { root.toPath().relativize(it).toString() }
                .map { it.removeSuffix(".class").replace(File.separatorChar, '.') }
                .filter { it.endsWith("Test") && !it.contains('$') }
                .toList()
        }.mapNotNull { name ->
            runCatching { Class.forName(name, false, javaClass.classLoader) }.getOrNull()
        }
    }
}
