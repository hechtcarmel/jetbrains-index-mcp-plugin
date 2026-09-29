package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.scala

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.HierarchyPageRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyDirection
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.QueryExecutor
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path

class ScalaHandlersTest : McpPlatformTestCase() {

    private companion object {
        const val FIXTURE_SOURCE_ROOT = "src/test/testData/scala"
        const val FIXTURE_PROJECT_ROOT = "src/scalaFixtures"

        /** Java and Scala resolve each other by package, so interop fixtures sit in their package directory. */
        const val INTEROP_PACKAGE_DIR = "fixture/scala2/interop"
    }

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

    fun testTypeHierarchyReportsScalaKinds() {
        requireScalaCapability("testTypeHierarchyReportsScalaKinds")

        val modelsFixture = addScalaFixture("scala2-models.scala")
        val typeHandler = ScalaTypeHierarchyHandler()

        val baseService = elementAt(modelsFixture.psiFile, modelsFixture.source, "abstract class BaseService")
        val baseHierarchy = typeHandler.getTypeHierarchy(
            baseService, project, BuiltInSearchScope.PROJECT_FILES,
            excludeGenerated = false, directOnly = false, direction = null, page = null
        )
        assertNotNull("BaseService hierarchy should resolve", baseHierarchy)
        assertEquals("ABSTRACT_CLASS", baseHierarchy!!.element.kind)
        val subtypes = baseHierarchy.subtypes.map { it.name }
        assertTrue("Employee should be a subtype of BaseService", subtypes.any { it.contains("Employee") })
        assertTrue("Contractor should be a subtype of BaseService", subtypes.any { it.contains("Contractor") })

        val employeeType = elementAt(modelsFixture.psiFile, modelsFixture.source, "case class Employee")
        val employeeHierarchy = typeHandler.getTypeHierarchy(
            employeeType, project, BuiltInSearchScope.PROJECT_FILES,
            excludeGenerated = false, directOnly = false, direction = null, page = null
        )
        assertNotNull("Employee hierarchy should resolve", employeeHierarchy)
        assertEquals(
            "A case class keeps the shared CLASS kind, as a Kotlin data class does",
            "CLASS", employeeHierarchy!!.element.kind
        )
        assertEquals("Scala", employeeHierarchy.element.language)
        assertNotNull("Employee hierarchy root must keep a pointerTarget", employeeHierarchy.element.pointerTarget)
        val baseSupertype = employeeHierarchy.supertypes.singleOrNull { it.name.contains("BaseService") }
        assertNotNull(
            "Employee should include BaseService in supertypes: ${employeeHierarchy.supertypes.map { it.name }}",
            baseSupertype
        )
        assertEquals("ABSTRACT_CLASS", baseSupertype!!.kind)
        assertTrue(
            "Worker should appear as a TRAIT above BaseService: ${baseSupertype.supertypes?.map { "${it.kind}:${it.name}" }}",
            baseSupertype.supertypes.orEmpty().any { it.name.contains("Worker") && it.kind == "TRAIT" }
        )
    }

    fun testTypeHierarchyPagingUsesDirectSupertypesAndNextOffset() {
        requireScalaCapability("testTypeHierarchyPagingUsesDirectSupertypesAndNextOffset")

        val modelsFixture = addScalaFixture("scala2-models.scala")
        val typeHandler = ScalaTypeHierarchyHandler()
        val baseService = elementAt(modelsFixture.psiFile, modelsFixture.source, "abstract class BaseService")
        val first = typeHandler.getTypeHierarchy(
            baseService, project, BuiltInSearchScope.PROJECT_FILES,
            excludeGenerated = false, directOnly = true, direction = TypeHierarchyDirection.SUBTYPE,
            page = HierarchyPageRequest(offset = 0, limit = 1)
        )
        assertNotNull("Paged BaseService hierarchy should resolve", first)
        assertEquals(1, first!!.subtypes.size)
        assertNotNull("paged subtype must keep a pointerTarget", first.subtypes.single().pointerTarget)
        assertEquals("look-ahead must report another direct subtype", 1, first.nextOffset)

        val second = typeHandler.getTypeHierarchy(
            baseService, project, BuiltInSearchScope.PROJECT_FILES,
            excludeGenerated = false, directOnly = true, direction = TypeHierarchyDirection.SUBTYPE,
            page = HierarchyPageRequest(offset = 1, limit = 1)
        )
        assertNotNull("Second page should resolve", second)
        assertEquals(1, second!!.subtypes.size)
        assertNull(second.nextOffset)
    }

    fun testFindImplementationsForTraitAndMethod() {
        requireScalaCapability("testFindImplementationsForTraitAndMethod")

        val modelsFixture = addScalaFixture("scala2-models.scala")
        val implHandler = ScalaImplementationsHandler()

        val workerTrait = elementAt(modelsFixture.psiFile, modelsFixture.source, "trait Worker")
        val typeImplementations = implHandler.findImplementations(
            workerTrait, project, BuiltInSearchScope.PROJECT_FILES, excludeGenerated = false
        )
        assertNotNull("Worker trait implementations should resolve", typeImplementations)
        val implementationNames = typeImplementations!!.map { it.name }
        assertTrue("Employee should implement Worker", implementationNames.any { it.contains("Employee") })
        assertTrue("Contractor should implement Worker", implementationNames.any { it.contains("Contractor") })
        assertTrue("Scala implementations report Scala", typeImplementations.all { it.language == "Scala" })

        val workMethod = elementAt(
            modelsFixture.psiFile,
            modelsFixture.source,
            "def work(task: String): String = s\"${'$'}name:${'$'}task\""
        )
        val methodImplementations = implHandler.findImplementations(
            workMethod, project, BuiltInSearchScope.PROJECT_FILES, excludeGenerated = false
        )
        assertNotNull("Worker.work implementations should resolve", methodImplementations)
        val methodNames = methodImplementations!!.map { it.name }
        assertTrue("Employee.work override should be present", methodNames.any { it.contains("Employee.work") })
        assertTrue("Contractor.work override should be present", methodNames.any { it.contains("Contractor.work") })
    }

    fun testCallHierarchyFindsScalaCallersAndCallees() {
        requireScalaCapability("testCallHierarchyFindsScalaCallersAndCallees")

        val modelsFixture = addScalaFixture("scala2-models.scala")
        val usageFixture = addScalaFixture("scala2-usage.scala")
        val callHandler = ScalaCallHierarchyHandler()

        val generateMethod = elementAt(usageFixture.psiFile, usageFixture.source, "def generate(emp: Employee): String = emp.doWork()")
        val callers = callHandler.getCallHierarchy(
            generateMethod,
            project,
            direction = "callers",
            depth = 2,
            scope = BuiltInSearchScope.PROJECT_FILES,
            excludeGenerated = false,
            page = null
        )
        assertNotNull("Callers hierarchy should resolve", callers)
        val callerNames = callers!!.calls.map { it.name }
        assertTrue(
            "BatchProcessor.process calls ReportGenerator.generate: $callerNames",
            "BatchProcessor.process" in callerNames
        )

        val doWorkMethod = elementAt(modelsFixture.psiFile, modelsFixture.source, "def doWork(): String = work(\"task\")")
        val callees = callHandler.getCallHierarchy(
            doWorkMethod,
            project,
            direction = "callees",
            depth = 2,
            scope = BuiltInSearchScope.PROJECT_FILES,
            excludeGenerated = false,
            page = null
        )
        assertNotNull("Callees hierarchy should resolve", callees)
        assertTrue(
            "Employee.work should appear as a callee of doWork",
            callees!!.calls.any { it.name.contains("Employee.work") }
        )
    }

    fun testScalaObjectsReportSourceNames() {
        requireScalaCapability("testScalaObjectsReportSourceNames")

        val callsFixture = addScalaFixture("scala2-calls.scala")
        val target = elementAt(callsFixture.psiFile, callsFixture.source, "object Target")

        val hierarchy = ScalaTypeHierarchyHandler().getTypeHierarchy(
            target, project, BuiltInSearchScope.PROJECT_FILES,
            excludeGenerated = false, directOnly = true, direction = null, page = null
        )
        assertNotNull("Target hierarchy should resolve", hierarchy)
        assertEquals(
            "Not the JVM qualified name fixture.scala2.calls.Target$",
            "fixture.scala2.calls.Target", hierarchy!!.element.name
        )
        assertEquals("fixture.scala2.calls.Target", hierarchy.element.qualifiedName)
        assertEquals("OBJECT", hierarchy.element.kind)
    }

    fun testCallerPageCountsDistinctCallersNotCallSites() {
        requireScalaCapability("testCallerPageCountsDistinctCallersNotCallSites")

        val callsFixture = addScalaFixture("scala2-calls.scala")
        val ping = elementAt(callsFixture.psiFile, callsFixture.source, "def ping()")
        val handler = ScalaCallHierarchyHandler()

        // CallerA.thrice has three call sites. Capping call sites before de-duplicating callers
        // returned a short page, which the tool reads as the end of the level.
        val wholeLevel = handler.getCallHierarchy(
            ping, project, "callers", 1, BuiltInSearchScope.PROJECT_FILES, false,
            HierarchyPageRequest(offset = 0, limit = 3)
        )
        assertNotNull("Paged callers should resolve", wholeLevel)
        assertEquals(
            setOf("CallerA.thrice", "CallerB.once", "CallerC.alsoOnce"),
            wholeLevel!!.calls.map { it.name }.toSet()
        )
        assertNull("All three distinct callers fit in the page", wholeLevel.nextOffset)

        val firstPage = handler.getCallHierarchy(
            ping, project, "callers", 1, BuiltInSearchScope.PROJECT_FILES, false,
            HierarchyPageRequest(offset = 0, limit = 2)
        )
        assertNotNull("First page should resolve", firstPage)
        assertEquals(2, firstPage!!.calls.size)
        assertEquals("The look-ahead must report the third caller", 2, firstPage.nextOffset)
    }

    fun testCalleesIncludeParameterlessAndInfixCalls() {
        requireScalaCapability("testCalleesIncludeParameterlessAndInfixCalls")

        val callsFixture = addScalaFixture("scala2-calls.scala")
        val handler = ScalaCallHierarchyHandler()

        val total = elementAt(callsFixture.psiFile, callsFixture.source, "def total()")
        val totalCallees = handler.getCallHierarchy(
            total, project, "callees", 1, BuiltInSearchScope.PROJECT_FILES, false, null
        )!!.calls.map { it.name }
        assertTrue("f(...) application: $totalCallees", "Counter.combine" in totalCallees)
        assertTrue("parameterless call: $totalCallees", "Counter.current" in totalCallees)
        assertFalse(
            "Synthetic Int.+ has no source file and must not be reported: $totalCallees",
            totalCallees.any { it == "+" || it.endsWith(".+") }
        )

        val combine = elementAt(callsFixture.psiFile, callsFixture.source, "def combine(")
        val combineCallees = handler.getCallHierarchy(
            combine, project, "callees", 1, BuiltInSearchScope.PROJECT_FILES, false, null
        )!!.calls.map { it.name }
        assertTrue("infix call: $combineCallees", "Counter.plus" in combineCallees)
    }

    fun testSuperMethodsIncludesTraitAndAbstractClassChain() {
        requireScalaCapability("testSuperMethodsIncludesTraitAndAbstractClassChain")

        val modelsFixture = addScalaFixture("scala2-models.scala")
        val superMethodsHandler = ScalaSuperMethodsHandler()

        val employeeWork = elementAt(
            modelsFixture.psiFile,
            modelsFixture.source,
            "override def work(task: String): String = s\"${'$'}name handled ${'$'}task\""
        )
        val result = superMethodsHandler.findSuperMethods(employeeWork, project)
        assertNotNull("Super methods hierarchy should resolve", result)

        val hierarchy = result!!.hierarchy
        assertTrue(
            "BaseService.work should appear in super method chain",
            hierarchy.any { it.containingClass.contains("BaseService") && !it.isInterface }
        )
        assertTrue(
            "Worker.work should appear as trait-based super method",
            hierarchy.any { it.containingClass.contains("Worker") && it.isInterface && it.containingClassKind == "TRAIT" }
        )
    }

    fun testJavaImplementationsOfScalaTraitReportTheirOwnLanguage() {
        requireScalaCapability("testJavaImplementationsOfScalaTraitReportTheirOwnLanguage")

        val interop = addInteropFixture()
        val labeled = elementAt(interop.psiFile, interop.source, "trait Labeled")

        val hierarchy = ScalaTypeHierarchyHandler().getTypeHierarchy(
            labeled, project, BuiltInSearchScope.PROJECT_FILES,
            excludeGenerated = false, directOnly = false, direction = TypeHierarchyDirection.SUBTYPE, page = null
        )
        assertNotNull("Labeled hierarchy should resolve", hierarchy)
        val javaSubtype = hierarchy!!.subtypes.singleOrNull { it.name.endsWith("JavaLabeled") }
        assertNotNull(
            "A Java class implementing a Scala trait is a subtype: ${hierarchy.subtypes.map { it.name }}",
            javaSubtype
        )
        assertEquals("Java", javaSubtype!!.language)
        assertEquals("CLASS", javaSubtype.kind)

        val implementations = ScalaImplementationsHandler().findImplementations(
            labeled, project, BuiltInSearchScope.PROJECT_FILES, excludeGenerated = false
        )
        assertTrue(
            "Java implementation of a Scala trait: ${implementations?.map { "${it.language}:${it.name}" }}",
            implementations.orEmpty().any { it.name.endsWith("JavaLabeled") && it.language == "Java" }
        )
    }

    fun testSuperMethodInJavaInterfaceReportsItsClass() {
        requireScalaCapability("testSuperMethodInJavaInterfaceReportsItsClass")

        val interop = addInteropFixture()
        val greet = elementAt(interop.psiFile, interop.source, "override def greet")

        val result = ScalaSuperMethodsHandler().findSuperMethods(greet, project)
        assertNotNull("Super methods of ScalaGreeter.greet should resolve", result)
        val javaSuper = result!!.hierarchy.singleOrNull { it.name == "greet" }
        assertNotNull("Greeter.greet (Java) is the super method: ${result.hierarchy}", javaSuper)
        assertEquals("fixture.scala2.interop.Greeter", javaSuper!!.containingClass)
        assertEquals("INTERFACE", javaSuper.containingClassKind)
        assertTrue("A Java interface method is an interface method", javaSuper.isInterface)
        assertEquals("Java", javaSuper.language)

        val scalaGreeter = elementAt(interop.psiFile, interop.source, "class ScalaGreeter")
        val supertypes = ScalaTypeHierarchyHandler().getTypeHierarchy(
            scalaGreeter, project, BuiltInSearchScope.PROJECT_FILES,
            excludeGenerated = false, directOnly = true, direction = TypeHierarchyDirection.SUPERTYPE, page = null
        )!!.supertypes
        val greeter = supertypes.singleOrNull { it.name == "fixture.scala2.interop.Greeter" }
        assertNotNull("The Java interface is a supertype: ${supertypes.map { it.name }}", greeter)
        assertEquals("INTERFACE", greeter!!.kind)
        assertEquals("Java", greeter.language)
    }

    fun testStructureMapsScalaDeclarationsOntoSharedKinds() {
        requireScalaCapability("testStructureMapsScalaDeclarationsOntoSharedKinds")

        val modelsFixture = addScalaFixture("scala2-models.scala")
        val usageFixture = addScalaFixture("scala2-usage.scala")
        val structureHandler = ScalaStructureHandler()

        val modelNodes = structureHandler.getFileStructure(modelsFixture.psiFile, project)
        assertTrue(
            "Worker trait should be present as TRAIT",
            modelNodes.any { it.name == "Worker" && it.kind == StructureKind.TRAIT }
        )
        val employee = modelNodes.singleOrNull { it.name == "Employee" }
        assertNotNull("Employee should be present: ${modelNodes.map { it.name }}", employee)
        assertEquals("A case class is CLASS, as a Kotlin data class is", StructureKind.CLASS, employee!!.kind)
        assertTrue("The case keyword travels in modifiers: ${employee.modifiers}", "case" in employee.modifiers)
        val baseService = modelNodes.single { it.name == "BaseService" }
        assertTrue("BaseService is abstract, not a case class: ${baseService.modifiers}",
            "abstract" in baseService.modifiers && "case" !in baseService.modifiers)

        val usageNodes = structureHandler.getFileStructure(usageFixture.psiFile, project)
        val runnerLikeObject = usageNodes.firstOrNull { node ->
            node.kind == StructureKind.OBJECT && node.children.any { it.kind == StructureKind.METHOD && it.name == "runAll" }
        }
        assertNotNull(
            "Usage structure should include an object containing runAll. Top-level nodes: ${usageNodes.map { "${it.kind}:${it.name}" }}",
            runnerLikeObject
        )
        assertEquals("An object keeps its source name, not the JVM name ServiceRunner$", "ServiceRunner", runnerLikeObject!!.name)

        // Regression coverage: val/var names must resolve to their real declared name,
        // not fall back to "unknown" (ScValueOrVariable does not itself implement
        // PsiNamedElement; the name lives on declaredElements).
        val properties = runnerLikeObject.children.filter { it.kind == StructureKind.PROPERTY }
        assertTrue(
            "No val/var member should fall back to 'unknown'. Properties: ${properties.map { "${it.modifiers}:${it.name}" }}",
            properties.isNotEmpty() && properties.none { it.name == "unknown" }
        )
        assertTrue("defaultTask is a val", properties.any { it.name == "defaultTask" && "val" in it.modifiers })
        assertTrue("runCount is a var", properties.any { it.name == "runCount" && "var" in it.modifiers })
        assertTrue(
            "Multi-name val declaration (`val minRetries, maxRetries = 3`) should emit both names",
            properties.any { it.name == "minRetries" && "val" in it.modifiers } &&
                properties.any { it.name == "maxRetries" && "val" in it.modifiers }
        )

        val everyNode = flatten(modelNodes) + flatten(usageNodes)
        assertTrue(
            "Every node reports where it ends: ${everyNode.filter { it.endLine == null }.map { it.name }}",
            everyNode.all { node -> node.endLine.let { it != null && it >= node.line } }
        )
        val runAll = runnerLikeObject.children.single { it.name == "runAll" }
        assertTrue("runAll spans several lines: ${runAll.line}-${runAll.endLine}", runAll.endLine!! > runAll.line)
    }

    fun testTypeHierarchyPropagatesCancellation() {
        requireScalaCapability("testTypeHierarchyPropagatesCancellation")

        val modelsFixture = addScalaFixture("scala2-models.scala")
        val baseService = elementAt(modelsFixture.psiFile, modelsFixture.source, "abstract class BaseService")
        val expected = ProcessCanceledException()
        val executor = QueryExecutor<PsiClass, ClassInheritorsSearch.SearchParameters> { _, _ -> throw expected }
        ClassInheritorsSearch.EP_NAME.point.registerExtension(executor, testRootDisposable)

        try {
            val result = ScalaTypeHierarchyHandler().getTypeHierarchy(
                baseService, project, BuiltInSearchScope.PROJECT_FILES,
                excludeGenerated = false, directOnly = false, direction = TypeHierarchyDirection.SUBTYPE, page = null
            )
            fail("Cancellation must propagate so the read action restarts, not return a hierarchy: $result")
        } catch (actual: ProcessCanceledException) {
            assertSame(expected, actual)
        }
    }

    fun testCallersPropagateCancellation() {
        requireScalaCapability("testCallersPropagateCancellation")

        val callsFixture = addScalaFixture("scala2-calls.scala")
        val ping = elementAt(callsFixture.psiFile, callsFixture.source, "def ping()")
        val expected = ProcessCanceledException()
        val executor = QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> { _, _ -> throw expected }
        ReferencesSearch.EP_NAME.point.registerExtension(executor, testRootDisposable)

        try {
            val result = ScalaCallHierarchyHandler().getCallHierarchy(
                ping, project, "callers", 1, BuiltInSearchScope.PROJECT_FILES, false, null
            )
            fail("Cancellation must propagate so the read action restarts, not return callers: $result")
        } catch (actual: ProcessCanceledException) {
            assertSame(expected, actual)
        }
    }

    fun testScalaPluginApiMismatchIsAnExplicitError() {
        requireScalaCapability("testScalaPluginApiMismatchIsAnExplicitError")

        val probe = object : BaseScalaHandler<Unit>() {
            fun run(): Unit = scalaApiBoundary("type hierarchy") {
                throw NoSuchMethodError("ScTemplateDefinition.supers()")
            }
        }

        try {
            probe.run()
            fail("A LinkageError from Scala-plugin API drift must surface as a tool error")
        } catch (e: ScalaPluginApiMismatchException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("Scala type hierarchy failed"))
            assertTrue(e.cause is NoSuchMethodError)
        }
    }

    private data class ScalaFixture(val source: String, val psiFile: PsiFile)

    private fun addScalaFixture(relativePath: String): ScalaFixture {
        val sourcePath = Path.of(FIXTURE_SOURCE_ROOT).resolve(relativePath)
        val source = Files.readString(sourcePath)
        val projectRelativePath = "$FIXTURE_PROJECT_ROOT/$relativePath"
        val psiFile = myFixture.addFileToProject(projectRelativePath, source)
        return ScalaFixture(source = source, psiFile = psiFile)
    }

    /** A Scala trait implemented from Java, and a Scala class implementing a Java interface. */
    private fun addInteropFixture(): ScalaFixture {
        myFixture.addFileToProject(
            "$INTEROP_PACKAGE_DIR/Greeter.java",
            """
                package fixture.scala2.interop;

                public interface Greeter {
                    int greet();
                }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "$INTEROP_PACKAGE_DIR/JavaLabeled.java",
            """
                package fixture.scala2.interop;

                public class JavaLabeled implements Labeled {
                    public int label() {
                        return 2;
                    }
                }
            """.trimIndent()
        )
        val source = """
            package fixture.scala2.interop

            trait Labeled {
              def label: Int
            }

            class ScalaGreeter extends Greeter {
              override def greet(): Int = 1
            }
        """.trimIndent()
        return ScalaFixture(source, myFixture.addFileToProject("$INTEROP_PACKAGE_DIR/Interop.scala", source))
    }

    private fun flatten(nodes: List<StructureNode>): List<StructureNode> =
        nodes.flatMap { listOf(it) + flatten(it.children) }

    private fun elementAt(psiFile: PsiFile, source: String, needle: String): PsiElement {
        val offset = source.indexOf(needle)
        check(offset >= 0) { "Could not find marker '$needle' in ${psiFile.name}" }
        return psiFile.findElementAt(offset) ?: error("No PSI element at offset $offset for marker '$needle'")
    }

    private fun requireScalaCapability(testName: String) {
        Assume.assumeTrue("$testName: skipped - Scala plugin not available", PluginDetectors.scala.isAvailable)

        val scalaPsiAvailable = try {
            Class.forName("org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        Assume.assumeTrue("$testName: skipped - Scala PSI classes unavailable", scalaPsiAvailable)

        val hasScalaTypeHierarchy = LanguageHandlerRegistry.getSupportedLanguagesForTypeHierarchy().contains("Scala")
        Assume.assumeTrue("$testName: skipped - Scala handlers are not registered", hasScalaTypeHierarchy)
    }
}
