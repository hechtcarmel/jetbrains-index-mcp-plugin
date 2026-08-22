package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.scala

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path

class ScalaHandlersTest : BasePlatformTestCase() {

    private companion object {
        const val FIXTURE_SOURCE_ROOT = "src/test/testData/scala"
        const val FIXTURE_PROJECT_ROOT = "src/scalaFixtures"
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

    fun testTypeHierarchyIncludesTraitAndCaseClassKinds() {
        requireScalaCapability("testTypeHierarchyIncludesTraitAndCaseClassKinds")

        val modelsFixture = addScalaFixture("scala2-models.scala")
        val typeHandler = ScalaTypeHierarchyHandler()

        val baseService = elementAt(modelsFixture.psiFile, modelsFixture.source, "abstract class BaseService")
        val baseHierarchy = typeHandler.getTypeHierarchy(baseService, project, BuiltInSearchScope.PROJECT_FILES)
        assertNotNull("BaseService hierarchy should resolve", baseHierarchy)
        val subtypes = baseHierarchy!!.subtypes.map { it.name }
        assertTrue("Employee should be a subtype of BaseService", subtypes.any { it.contains("Employee") })
        assertTrue("Contractor should be a subtype of BaseService", subtypes.any { it.contains("Contractor") })

        val employeeType = elementAt(modelsFixture.psiFile, modelsFixture.source, "case class Employee")
        val employeeHierarchy = typeHandler.getTypeHierarchy(employeeType, project, BuiltInSearchScope.PROJECT_FILES)
        assertNotNull("Employee hierarchy should resolve", employeeHierarchy)
        assertEquals("CASE_CLASS", employeeHierarchy!!.element.kind)
        assertTrue(
            "Employee should include BaseService in supertypes",
            employeeHierarchy.supertypes.any { it.name.contains("BaseService") && it.kind == "CLASS" }
        )
    }

    fun testFindImplementationsForTraitAndMethod() {
        requireScalaCapability("testFindImplementationsForTraitAndMethod")

        val modelsFixture = addScalaFixture("scala2-models.scala")
        val implHandler = ScalaImplementationsHandler()

        val workerTrait = elementAt(modelsFixture.psiFile, modelsFixture.source, "trait Worker")
        val typeImplementations = implHandler.findImplementations(workerTrait, project, BuiltInSearchScope.PROJECT_FILES)
        assertNotNull("Worker trait implementations should resolve", typeImplementations)
        val implementationNames = typeImplementations!!.map { it.name }
        assertTrue("Employee should implement Worker", implementationNames.any { it.contains("Employee") })
        assertTrue("Contractor should implement Worker", implementationNames.any { it.contains("Contractor") })

        val workMethod = elementAt(
            modelsFixture.psiFile,
            modelsFixture.source,
            "def work(task: String): String = s\"${'$'}name:${'$'}task\""
        )
        val methodImplementations = implHandler.findImplementations(workMethod, project, BuiltInSearchScope.PROJECT_FILES)
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
            scope = BuiltInSearchScope.PROJECT_FILES
        )
        assertNotNull("Callers hierarchy should resolve", callers)
        assertTrue(
            "Callers for ReportGenerator.generate should not be empty",
            callers!!.calls.isNotEmpty()
        )

        val doWorkMethod = elementAt(modelsFixture.psiFile, modelsFixture.source, "def doWork(): String = work(\"task\")")
        val callees = callHandler.getCallHierarchy(
            doWorkMethod,
            project,
            direction = "callees",
            depth = 2,
            scope = BuiltInSearchScope.PROJECT_FILES
        )
        assertNotNull("Callees hierarchy should resolve", callees)
        assertTrue(
            "Employee.work should appear as a callee of doWork",
            callees!!.calls.any { it.name.contains("Employee.work") }
        )
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

    fun testStructureIncludesScalaSpecificKindsAndMembers() {
        requireScalaCapability("testStructureIncludesScalaSpecificKindsAndMembers")

        val modelsFixture = addScalaFixture("scala2-models.scala")
        val usageFixture = addScalaFixture("scala2-usage.scala")
        val structureHandler = ScalaStructureHandler()

        val modelNodes = structureHandler.getFileStructure(modelsFixture.psiFile, project)
        assertTrue(
            "Worker trait should be present as TRAIT",
            modelNodes.any { it.name == "Worker" && it.kind == StructureKind.TRAIT }
        )
        assertTrue(
            "Employee should be present as CASE_CLASS",
            modelNodes.any { it.name == "Employee" && it.kind == StructureKind.CASE_CLASS }
        )

        val usageNodes = structureHandler.getFileStructure(usageFixture.psiFile, project)
        val runnerLikeObject = usageNodes.firstOrNull { node ->
            node.kind == StructureKind.OBJECT && node.children.any { it.kind == StructureKind.METHOD && it.name == "runAll" }
        }
        assertNotNull(
            "Usage structure should include an object containing runAll. Top-level nodes: ${usageNodes.map { "${it.kind}:${it.name}" }}",
            runnerLikeObject
        )
        assertTrue("Runner-like object should contain method member", runnerLikeObject!!.children.any { it.kind == StructureKind.METHOD && it.name == "runAll" })
        assertTrue("Runner-like object should expose non-method members", runnerLikeObject.children.any { it.kind != StructureKind.METHOD })

        // Regression coverage: val/var names must resolve to their real declared name,
        // not fall back to "unknown" (ScValueOrVariable does not itself implement
        // PsiNamedElement; the name lives on declaredElements).
        val fieldNodes = runnerLikeObject.children.filter { it.kind == StructureKind.VAL || it.kind == StructureKind.VAR }
        assertTrue(
            "No val/var member should fall back to 'unknown'. Field nodes: ${fieldNodes.map { "${it.kind}:${it.name}" }}",
            fieldNodes.none { it.name == "unknown" }
        )
        assertTrue("defaultTask val should resolve its real name", fieldNodes.any { it.kind == StructureKind.VAL && it.name == "defaultTask" })
        assertTrue("runCount var should resolve its real name", fieldNodes.any { it.kind == StructureKind.VAR && it.name == "runCount" })
        assertTrue(
            "Multi-name val declaration (`val minRetries, maxRetries = 3`) should emit both names",
            fieldNodes.any { it.kind == StructureKind.VAL && it.name == "minRetries" } &&
                fieldNodes.any { it.kind == StructureKind.VAL && it.name == "maxRetries" }
        )
    }

    private data class ScalaFixture(val source: String, val psiFile: PsiFile)

    private fun addScalaFixture(relativePath: String): ScalaFixture {
        val sourcePath = Path.of(FIXTURE_SOURCE_ROOT).resolve(relativePath)
        val source = Files.readString(sourcePath)
        val projectRelativePath = "$FIXTURE_PROJECT_ROOT/$relativePath"
        val psiFile = myFixture.addFileToProject(projectRelativePath, source)
        return ScalaFixture(source = source, psiFile = psiFile)
    }

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
