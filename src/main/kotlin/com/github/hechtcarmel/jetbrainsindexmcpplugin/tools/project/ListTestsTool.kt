package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ListTestsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testIntegration.TestFramework
import kotlinx.serialization.json.JsonObject

class ListTestsTool : AbstractMcpTool() {

    companion object {
        private const val MAX_TESTS = 500
    }

    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.LIST_TESTS

    override val description = """
        List all test methods discovered by the IDE's test framework extension points (JUnit, TestNG, etc.).

        Returns: list of test entries with className, methodName, file path, and line number.
        The className is fully qualified when the language exposes it (e.g. Java, Kotlin, PHP), so it
        can be passed directly to ide_run_tests.
        Note: requires smart mode (IDE indexing must be complete).

        Parameters: project_path (required when multiple projects are open), file (optional — if given, lists only tests in that file; otherwise scans all test sources).

        Example: {} or {"file": "src/test/kotlin/com/example/MyTest.kt"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(
            required = false,
            description = "Path to a specific test file relative to project root. If omitted, lists all tests in the project."
        )
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)

        val frameworks = TestFramework.EXTENSION_NAME.extensionList
        if (frameworks.isEmpty()) {
            return createErrorResult("No test frameworks are registered in this IDE session.")
        }

        val filePath = optionalStringArg(arguments, ParamNames.FILE)

        val tests = suspendingReadAction {
            collectTests(project, frameworks, filePath)
        }

        return createJsonResult(
            ListTestsResult(tests = tests, count = tests.size, truncated = tests.size >= MAX_TESTS)
        )
    }

    /**
     * Walks the requested test sources and returns every discovered test method, capped at
     * [MAX_TESTS] (a result of that size indicates collection stopped early).
     */
    private fun collectTests(
        project: Project,
        frameworks: List<TestFramework>,
        filePath: String?
    ): List<TestEntry> {
        val docManager = PsiDocumentManager.getInstance(project)
        val results = mutableListOf<TestEntry>()

        fun scan(psiFile: PsiFile) {
            val document = docManager.getDocument(psiFile) ?: return
            val relativePath = getRelativePath(project, psiFile.virtualFile)

            psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    checkCanceled()
                    val entry = toTestEntry(element, frameworks, psiFile, document, relativePath)
                    if (entry != null) {
                        results.add(entry)
                        if (results.size >= MAX_TESTS) {
                            stopWalking()
                            return
                        }
                    }
                    super.visitElement(element)
                }
            })
        }

        if (filePath != null) {
            getPsiFile(project, filePath)?.let(::scan)
        } else {
            val psiManager = PsiManager.getInstance(project)
            val fileIndex = ProjectFileIndex.getInstance(project)
            fileIndex.iterateContent { vf ->
                if (!vf.isDirectory && fileIndex.isInTestSourceContent(vf)) {
                    psiManager.findFile(vf)?.let(::scan)
                }
                results.size < MAX_TESTS // keep iterating files until the cap is hit
            }
        }

        return results
    }

    /**
     * Builds a [TestEntry] if [element] is a test method for any registered framework, else null.
     */
    private fun toTestEntry(
        element: PsiElement,
        frameworks: List<TestFramework>,
        psiFile: PsiFile,
        document: Document,
        relativePath: String
    ): TestEntry? {
        val framework = frameworks.firstOrNull { fw ->
            try { fw.isTestMethod(element) }
            catch (e: ProcessCanceledException) { throw e }
            catch (e: Exception) { false }
        } ?: return null
        val methodName = (element as? PsiNamedElement)?.name ?: return null
        val className = findContainingClassName(element) ?: psiFile.name.substringBeforeLast('.')
        val line = document.getLineNumber(element.textOffset) + 1

        return TestEntry(
            framework = framework.name,
            className = className,
            methodName = methodName,
            displayName = "$className.$methodName",
            file = relativePath,
            line = line
        )
    }

    /**
     * Name of the nearest enclosing named element (the test's class), fully qualified when the
     * language's PSI exposes one (see [PsiUtils.qualifiedName]); otherwise the simple name.
     */
    private fun findContainingClassName(element: PsiElement): String? {
        val owner = PsiUtils.findNamedElement(element.parent) ?: return null
        return PsiUtils.qualifiedName(owner) ?: owner.name
    }
}
