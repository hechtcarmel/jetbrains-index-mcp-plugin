package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.facet.Facet
import com.intellij.facet.FacetConfiguration
import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.PersistentLibraryKind
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.testFramework.IndexingTestUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.nio.file.Path

class KotlinJsHierarchyBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("Real Kotlin plugin required", PluginDetectors.kotlin.isAvailable)
        configureJsModule()
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("jsMain")
        writeProjectFile("jsMain/Elements.kt", """
            package jsprobe
            open class Base : RuntimeException() {
                open fun ping(value: String): String = value
            }
            class Child : Base() {
                override fun ping(value: String): String = super.ping(value)
            }
        """.trimIndent())
        SymbolIdRegistry.getInstance().resetSession()
        ReadAction.run<Throwable> {
            val file = requireNotNull(LocalFileSystem.getInstance().findFileByPath("${project.basePath}/jsMain/Elements.kt"))
            val psi = requireNotNull(PsiManager.getInstance(project).findFile(file))
            val base = (psi.javaClass.getMethod("getDeclarations").invoke(psi) as List<*>).first() as com.intellij.psi.PsiElement
            assertNull("The regression must run on JS PSI without a JVM light class", PsiUtils.resolveAsPsiClass(base))
        }
    }

    override fun tearDown() {
        try {
            LanguageHandlerRegistry.clear()
            SymbolIdRegistry.getInstance().resetSession()
            runInEdtAndWait {
                WriteAction.run<Throwable> {
                    val manager = FacetManager.getInstance(module)
                    manager.createModifiableModel().apply {
                        manager.allFacets.filter { it.type.stringId == "kotlin-language" }.forEach { removeFacet(it) }
                        commit()
                    }
                    ModuleRootModificationUtil.updateModel(module) { model ->
                        model.moduleLibraryTable.getLibraryByName("kotlin-stdlib-js")?.let { model.moduleLibraryTable.removeLibrary(it) }
                    }
                }
            }
        } finally { super.tearDown() }
    }

    fun testTypeHierarchyResolvesJsPosition() = runBlocking {
        val result = TypeHierarchyTool().execute(project, buildJsonObject {
            put("file", "jsMain/Elements.kt"); put("line", 5); put("column", 7)
        })
        assertToolSucceeded("JS type hierarchy", result)
        val hierarchy = Json.parseToJsonElement(toolText(result)).jsonObject
        assertEquals("jsprobe.Child", hierarchy.getValue("element").jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue(toolText(result), hierarchy.getValue("supertypes").jsonArray.any {
            it.jsonObject.getValue("name").jsonPrimitive.content == "jsprobe.Base"
        })
    }

    fun testImplementationsResolveJsClass() = runBlocking {
        val result = FindImplementationsTool().execute(project, buildJsonObject {
            put("file", "jsMain/Elements.kt"); put("line", 2); put("column", 12)
        })
        assertToolSucceeded("JS class implementations", result)
        assertEquals(listOf("jsprobe.Child"), body(result).getValue("implementations").jsonArray.map {
            it.jsonObject.getValue("name").jsonPrimitive.content
        })
    }

    fun testSuperMethodsResolveActualJsOverride() = runBlocking {
        val result = FindSuperMethodsTool().execute(project, buildJsonObject {
            put("file", "jsMain/Elements.kt"); put("line", 6); put("column", 18)
        })
        assertToolSucceeded("JS overridden method", result)
        val hierarchy = body(result).getValue("hierarchy").jsonArray
        assertEquals(1, hierarchy.size)
        assertEquals("jsprobe.Base", hierarchy.single().jsonObject.getValue("containingClass").jsonPrimitive.content)
        assertEquals("ping", hierarchy.single().jsonObject.getValue("name").jsonPrimitive.content)
    }

    fun testFullyQualifiedClassLookupIsExact() = runBlocking {
        writeProjectFile("jsMain/Other.kt", "package other\nopen class Base")
        val result = TypeHierarchyTool().execute(project, buildJsonObject { put("className", "jsprobe.Base") })
        assertToolSucceeded("JS fully qualified class lookup", result)
        assertEquals("jsprobe.Base", body(result).getValue("element").jsonObject.getValue("name").jsonPrimitive.content)
        val missing = TypeHierarchyTool().execute(project, buildJsonObject { put("className", "missing.Base") })
        assertToolFailed("Do not substitute a class from another package", missing)
    }

    fun testNativeAbstractAndSealedClassesKeepTheirKinds() = runBlocking {
        writeProjectFile("jsMain/Kinds.kt", "package jsprobe\nabstract class AbstractBase\nsealed class SealedBase")
        for (name in listOf("AbstractBase", "SealedBase")) {
            val result = TypeHierarchyTool().execute(project, buildJsonObject { put("className", "jsprobe.$name") })
            assertToolSucceeded("Resolve native JS $name", result)
            assertEquals("ABSTRACT_CLASS", body(result).getValue("element").jsonObject.getValue("kind").jsonPrimitive.content)
        }
    }

    fun testSymbolSearchHandleResolvesJsHierarchyAndKeepsIdentity() = runBlocking {
        val found = FindSymbolTool().execute(project, buildJsonObject { put("query", "Base"); put("language", "Kotlin") })
        assertToolSucceeded("Find native JS symbol", found)
        val handle = body(found).getValue("symbols").jsonArray.single().jsonObject.getValue("symbolId").jsonPrimitive.content
        val hierarchy = TypeHierarchyTool().execute(project, buildJsonObject { put("symbolId", handle) })
        assertToolSucceeded("Native JS type handle", hierarchy)
        assertEquals(handle, body(hierarchy).getValue("element").jsonObject.getValue("symbolId").jsonPrimitive.content)
        assertEquals("jsprobe.Child", body(hierarchy).getValue("subtypes").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        val info = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", handle) })
        assertToolSucceeded("Hierarchy preserves original class handle", info)
        assertEquals("Base", body(info).getValue("name").jsonPrimitive.content)
    }

    fun testNativeMethodHandlesRoundTripThroughBothTools() = runBlocking {
        val supers = FindSuperMethodsTool().execute(project, buildJsonObject {
            put("file", "jsMain/Elements.kt"); put("line", 6); put("column", 18)
        })
        assertToolSucceeded("Discover native JS method handles", supers)
        val baseHandle = body(supers).getValue("hierarchy").jsonArray.single().jsonObject.getValue("symbolId").jsonPrimitive.content
        val original = ReadAction.compute<com.intellij.psi.PsiElement, Throwable> {
            SymbolIdRegistry.getInstance().resolve(project, baseHandle).getOrThrow()
        }
        val implementations = FindImplementationsTool().execute(project, buildJsonObject { put("symbolId", baseHandle) })
        assertToolSucceeded("Find JS method overrides by handle", implementations)
        val child = body(implementations).getValue("implementations").jsonArray.single().jsonObject
        assertEquals("jsprobe.Child.ping", child.getValue("name").jsonPrimitive.content)
        val again = FindSuperMethodsTool().execute(project, buildJsonObject { put("symbolId", child.getValue("symbolId").jsonPrimitive.content) })
        assertToolSucceeded("Implementation handle resolves back to JS override", again)
        val returnedHandle = body(again).getValue("hierarchy").jsonArray.single().jsonObject.getValue("symbolId").jsonPrimitive.content
        ReadAction.run<Throwable> {
            assertSame(original, SymbolIdRegistry.getInstance().resolve(project, baseHandle).getOrThrow())
            assertSame(original, SymbolIdRegistry.getInstance().resolve(project, returnedHandle).getOrThrow())
        }
    }

    fun testClassPositionIsNotASuperMethodTarget() = runBlocking {
        val result = FindSuperMethodsTool().execute(project, buildJsonObject {
            put("file", "jsMain/Elements.kt"); put("line", 5); put("column", 7)
        })
        assertToolFailed("A class is not an overridden method", result)
        assertTrue(toolText(result), toolText(result).startsWith("No method found"))
    }

    fun testMethodReferenceResolvesTheCalleeInsteadOfTheEnclosingFunction() = runBlocking {
        writeProjectFile("jsMain/Use.kt", "package jsprobe\nfun use(child: Child): String = child.ping(\"hello\")")
        val result = FindSuperMethodsTool().execute(project, buildJsonObject {
            put("file", "jsMain/Use.kt"); put("line", 2); put("column", 39)
        })
        assertToolSucceeded("Resolve a JS method call", result)
        assertEquals("jsprobe.Child", body(result).getValue("method").jsonObject.getValue("containingClass").jsonPrimitive.content)
        assertEquals("jsprobe.Base", body(result).getValue("hierarchy").jsonArray.single().jsonObject.getValue("containingClass").jsonPrimitive.content)
    }

    fun testLocalValueInOverrideBodyResolvesTheEnclosingMethod() = runBlocking {
        writeProjectFile("jsMain/Local.kt", """
            package jsprobe
            class LocalChild : Base() {
                override fun ping(value: String): String {
                    val answer = value
                    return answer
                }
            }
        """.trimIndent())
        val result = FindSuperMethodsTool().execute(project, buildJsonObject {
            put("file", "jsMain/Local.kt"); put("line", 4); put("column", 13)
        })
        assertToolSucceeded("A local value is not a JVM or JS method", result)
        assertEquals("ping", body(result).getValue("method").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("jsprobe.Base", body(result).getValue("hierarchy").jsonArray.single().jsonObject.getValue("containingClass").jsonPrimitive.content)
    }

    fun testOverloadsUseSemanticOverrideIdentity() = runBlocking {
        writeProjectFile("jsMain/Overloads.kt", """
            package jsprobe
            open class Overloaded {
                open fun work(value: String): String = value
                open fun work(value: Int): Int = value
            }
            class IntChild : Overloaded() {
                override fun work(value: Int): Int = value
            }
        """.trimIndent())
        val stringOverload = FindImplementationsTool().execute(project, buildJsonObject {
            put("file", "jsMain/Overloads.kt"); put("line", 3); put("column", 14)
        })
        assertToolSucceeded("Search a non-overridden JS overload", stringOverload)
        assertTrue(body(stringOverload).getValue("implementations").jsonArray.isEmpty())
        val intOverride = FindSuperMethodsTool().execute(project, buildJsonObject {
            put("file", "jsMain/Overloads.kt"); put("line", 7); put("column", 18)
        })
        assertToolSucceeded("Resolve overridden JS overload", intOverride)
        val parent = body(intOverride).getValue("hierarchy").jsonArray.single().jsonObject
        assertEquals("4", parent.getValue("line").jsonPrimitive.content)
    }

    fun testLibrarySupertypesRequireLibraryScope() = runBlocking {
        val tool = TypeHierarchyTool()
        val projectOnly = tool.execute(project, buildJsonObject { put("className", "jsprobe.Base") })
        assertToolSucceeded("Resolve JS root with project scope", projectOnly)
        assertTrue(body(projectOnly).getValue("supertypes").jsonArray.isEmpty())
        val all = tool.execute(project, buildJsonObject { put("className", "jsprobe.Base"); put("scope", "project_and_libraries") })
        assertToolSucceeded("Resolve real JS klib supertype", all)
        assertEquals("kotlin.RuntimeException", body(all).getValue("supertypes").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
    }

    fun testTypeAliasResolvesToSemanticSuperclass() = runBlocking {
        writeProjectFile("jsMain/Alias.kt", "package jsprobe\ntypealias Alias = Base\nclass AliasChild : Alias()")
        val result = TypeHierarchyTool().execute(project, buildJsonObject { put("className", "jsprobe.AliasChild") })
        assertToolSucceeded("Resolve JS type alias", result)
        assertEquals("jsprobe.Base", body(result).getValue("supertypes").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
    }

    fun testPagedJsSubtypesCanCrossLegacyHundredResultLimit() = runBlocking {
        writeProjectFile("jsMain/Wide.kt", "package jsprobe\n" + (1..105).joinToString("\n") { "class Wide$it : Base()" })
        val tool = TypeHierarchyTool()
        var arguments = buildJsonObject { put("className", "jsprobe.Base"); put("maxNodes", 25) }
        val names = mutableSetOf<String>()
        var pages = 0
        while (true) {
            val result = tool.execute(project, arguments)
            assertToolSucceeded("Read JS hierarchy page", result)
            val value = body(result)
            val nodes = value.getValue("subtypes").jsonArray
            assertTrue(nodes.size <= 25)
            for (node in nodes) assertTrue("A JS subtype must appear once", names.add(node.jsonObject.getValue("name").jsonPrimitive.content))
            val cursor = value["cursor"]?.jsonPrimitive?.contentOrNull ?: break
            assertTrue("JS hierarchy pagination must finish", ++pages < 10)
            arguments = buildJsonObject { put("cursor", cursor); put("maxNodes", 25) }
        }
        assertEquals(106, names.size)
    }

    private fun body(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): JsonObject =
        Json.parseToJsonElement(toolText(result)).jsonObject

    @Suppress("UNCHECKED_CAST")
    private fun configureJsModule() {
        val facetTypeClass = Class.forName("org.jetbrains.kotlin.idea.facet.KotlinFacetType")
        val companion = facetTypeClass.getField("Companion").get(null)
        val type = companion.javaClass.getMethod("getINSTANCE").invoke(companion) as FacetType<Facet<FacetConfiguration>, FacetConfiguration>
        val configuration = type.createDefaultConfiguration()
        val settings = configuration.javaClass.getMethod("getSettings").invoke(configuration)
        val platformsClass = Class.forName("org.jetbrains.kotlin.platform.js.JsPlatforms")
        val platform = platformsClass.getMethod("getDefaultJsPlatform").invoke(platformsClass.getField("INSTANCE").get(null))
        settings.javaClass.methods.single { it.name == "setTargetPlatform" }.invoke(settings, platform)
        settings.javaClass.getMethod("setUseProjectSettings", Boolean::class.javaPrimitiveType).invoke(settings, false)
        val klib = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib-js.klib")
        assertTrue("Bundled JS stdlib klib required: $klib", klib.toFile().isFile)
        val kindClass = Class.forName("org.jetbrains.kotlin.idea.base.platforms.KotlinJavaScriptLibraryKind")
        val kind = kindClass.getField("INSTANCE").get(null) as PersistentLibraryKind<*>
        runInEdtAndWait {
            WriteAction.run<Throwable> {
                val manager = FacetManager.getInstance(module)
                val facet = manager.createFacet(type, "Kotlin", configuration, null)
                manager.createModifiableModel().apply { addFacet(facet); commit() }
                ModuleRootModificationUtil.updateModel(module) { model ->
                    val table = model.moduleLibraryTable.modifiableModel
                    val library = table.createLibrary("kotlin-stdlib-js", kind)
                    library.modifiableModel.apply {
                        addRoot(VfsUtil.getUrlForLibraryRoot(klib.toFile()), OrderRootType.CLASSES)
                        commit()
                    }
                    table.commit()
                }
            }
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }
}
