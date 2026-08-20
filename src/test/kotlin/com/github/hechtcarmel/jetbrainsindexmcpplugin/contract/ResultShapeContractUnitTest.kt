package com.github.hechtcarmel.jetbrainsindexmcpplugin.contract

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.UsageTypes
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ActiveFileInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildProjectResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DiagnosticsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileCoverageInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileStructureResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindClassResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindSymbolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ResolvedSymbolInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.GetActiveFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationLocation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.IndexStatusResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.IntentionInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ListTestsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.MethodInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.OpenFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.PositionInput
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProblemInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProjectDiagnosticsInProgressResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProjectDiagnosticsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ReadFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildInProgressResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.LinkInProgressResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RunTestsInProgressResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RunTestsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SearchTextResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SuperMethodInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SuperMethodsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SyncFilesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestResultInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestRunEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestSummary
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TextMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.UsageLocation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ChangeSignatureTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ConversionStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ConversionSummary
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.CreateFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.FileConversionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.JavaToKotlinConversionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.MemberCandidate
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.MemberEditResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.MemberErrorResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.NoSymbolFoundResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.PositionInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ReplaceTextInFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.SafeDeleteBlockedResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.SafeDeleteFileBlockedResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.StructuralSearchReplaceTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.SymbolInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.SymbolSuggestion
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.UsageInfo
import junit.framework.TestCase
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Golden snapshot of the MCP tool RESPONSE surface: the wire key set, JSON value kind,
 * nullability and optionality of every serializable result model, plus every enum's wire values
 * and the usage-type literals emitted by `ide_find_references`.
 *
 * This is a *contract with MCP clients*. Result models use plain Kotlin property names as their
 * wire keys — only [TestStatus] and `ContentBlock` carry `@SerialName` — so renaming
 * `UsageLocation.file` to `.path` is a source-compatible refactor that breaks every client
 * parsing a `find_references` response. Nothing else in this suite notices: the golden tool
 * manifest ([ToolManifestContractUnitTest]) pins tool INPUTS only.
 *
 * The models are enumerated by hand in [PINNED_SHAPES] rather than discovered by scanning the
 * classpath: a discovery bug silently shrinks coverage, whereas an omission from a hand-written
 * list is visible in review. [testPinnedListCoversTheResultSurface] guards the list's size.
 *
 * Every field of every pinned instance is populated with a non-default, non-null value
 * ([testEveryPinnedFieldIsPopulated] enforces this). That matters because production serializes
 * with `encodeDefaults = true`: a field left at its default or at `null` would still appear, but
 * populating everything makes the *complete* key set — including optional pagination fields —
 * part of the snapshot.
 *
 * Changing the snapshot is sometimes correct, but it must always be deliberate: regenerate with
 *
 * ```
 * ./gradlew test -Ptier=unit --tests "*ResultShapeContractUnitTest" -Dcontract.update=true
 * ```
 *
 * and treat the diff as the list of breaking changes the release notes owe clients.
 */
class ResultShapeContractUnitTest : TestCase() {

    private companion object {
        const val GOLDEN_RESOURCE = "contract/result-shapes.txt"
        const val GOLDEN_SOURCE_PATH = "src/test/resources/contract/result-shapes.txt"
        const val UPDATE_PROPERTY = "contract.update"

        /**
         * Lower bound on pinned models. Deliberately well below the actual count so that adding a
         * model does not force a churn edit here, while a refactor that guts the list still fails.
         */
        const val MIN_PINNED_SHAPES = 20

        /**
         * Usage-type literals emitted in [UsageLocation.type]. Written out as name/value pairs so
         * that renaming a constant is a compile error here and changing its wire value is a diff
         * in the golden file.
         */
        val PINNED_USAGE_TYPES = listOf(
            "METHOD_CALL" to UsageTypes.METHOD_CALL,
            "REFERENCE" to UsageTypes.REFERENCE,
            "FIELD_ACCESS" to UsageTypes.FIELD_ACCESS,
            "IMPORT" to UsageTypes.IMPORT,
            "PARAMETER" to UsageTypes.PARAMETER,
            "VARIABLE" to UsageTypes.VARIABLE,
        )
    }

    /** Mirrors `AbstractMcpTool.json`, which is `protected` and so not reachable from a test. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private sealed class Shape {
        abstract val name: String

        class Struct(
            override val name: String,
            val encoded: JsonObject,
            val descriptor: SerialDescriptor
        ) : Shape()

        class EnumWire(
            override val name: String,
            val wireValues: List<Pair<String, String>>
        ) : Shape()
    }

    private val pinnedShapes: List<Shape> by lazy { buildPinnedShapes() }

    fun testResultShapesMatchGoldenSnapshot() {
        val actual = renderShapes()

        if (System.getProperty(UPDATE_PROPERTY) == "true") {
            val target = File(GOLDEN_SOURCE_PATH)
            target.parentFile.mkdirs()
            target.writeText(actual)
            fail(
                "Golden result shapes regenerated at $GOLDEN_SOURCE_PATH. " +
                    "Review the diff, then re-run without -D$UPDATE_PROPERTY."
            )
        }

        val expected = readGolden()
        if (expected == actual) return

        val actualFile = File("build/result-shapes-actual.txt")
        actualFile.parentFile.mkdirs()
        actualFile.writeText(actual)

        fail(
            buildString {
                appendLine("A tool RESPONSE shape changed — MCP clients parse these keys.")
                appendLine()
                appendLine(describeDifference(expected, actual))
                appendLine()
                appendLine("If this change is intended, regenerate the golden file:")
                appendLine("  ./gradlew test -Ptier=unit --tests \"*ResultShapeContractUnitTest\" -Dcontract.update=true")
                appendLine("Actual shapes written to: ${actualFile.path}")
            }
        )
    }

    fun testPinnedListCoversTheResultSurface() {
        assertTrue(
            "PINNED_SHAPES is empty — the snapshot would pin nothing at all.",
            pinnedShapes.isNotEmpty()
        )
        assertTrue(
            "Only ${pinnedShapes.size} shapes pinned, expected at least $MIN_PINNED_SHAPES. " +
                "Models were removed from the pinned list without removing them from src/main.",
            pinnedShapes.size >= MIN_PINNED_SHAPES
        )

        val duplicates = pinnedShapes.groupBy { it.name }.filterValues { it.size > 1 }.keys.sorted()
        assertEquals(
            "Duplicate entries in the pinned list — one of them is redundant.",
            emptyList<String>(),
            duplicates
        )
    }

    /**
     * Guards the "populate everything" rule the snapshot depends on: a model added to the pinned
     * list with a nullable field left `null` would pin an incomplete key set for that field's
     * type and hide a later rename.
     */
    fun testEveryPinnedFieldIsPopulated() {
        val unpopulated = pinnedShapes.filterIsInstance<Shape.Struct>().flatMap { shape ->
            shape.encoded.entries
                .filter { (_, value) -> isEmptyOrNull(value) }
                .map { (key, _) -> "${shape.name}.$key" }
        }.sorted()

        assertEquals(
            "These pinned fields serialize to null or an empty collection, so their shape is not " +
                "actually pinned. Populate them with a non-default value.",
            emptyList<String>(),
            unpopulated
        )
    }

    private fun isEmptyOrNull(value: JsonElement): Boolean = when (value) {
        is JsonNull -> true
        is JsonArray -> value.isEmpty()
        is JsonObject -> value.isEmpty()
        else -> false
    }

    /**
     * Renders the pinned shapes as stable, human-diffable text: one line per wire key, so a
     * review diff shows exactly which model and which key changed.
     */
    private fun renderShapes(): String {
        val shapes = pinnedShapes.sortedBy { it.name }
        return buildString {
            appendLine("# MCP tool response shapes — golden snapshot")
            appendLine("# Regenerate: ./gradlew test -Ptier=unit --tests \"*ResultShapeContractUnitTest\" -Dcontract.update=true")
            appendLine("# shapes: ${shapes.size}")
            shapes.forEach { shape ->
                appendLine()
                when (shape) {
                    is Shape.Struct -> {
                        appendLine("## ${shape.name}")
                        shape.encoded.keys.sorted().forEach { key ->
                            appendLine("  $key: ${renderField(shape, key)}")
                        }
                    }

                    is Shape.EnumWire -> {
                        appendLine("## ${shape.name} (enum)")
                        shape.wireValues.forEach { (constant, wire) ->
                            appendLine("  $constant -> \"$wire\"")
                        }
                    }
                }
            }
            appendLine()
            appendLine("## UsageTypes (ide_find_references \"type\" literals)")
            PINNED_USAGE_TYPES.forEach { (constant, wire) ->
                appendLine("  $constant -> \"$wire\"")
            }
        }
    }

    private fun renderField(shape: Shape.Struct, key: String): String {
        val index = shape.descriptor.getElementIndex(key)
        require(index >= 0) { "Serialized key '$key' has no descriptor element in ${shape.name}" }
        val nullable = if (shape.descriptor.getElementDescriptor(index).isNullable) "?" else ""
        val optional = if (shape.descriptor.isElementOptional(index)) " (optional)" else ""
        return "${jsonKindOf(shape.encoded.getValue(key))}$nullable$optional"
    }

    private fun jsonKindOf(element: JsonElement): String = when (element) {
        is JsonNull -> "null"
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonPrimitive -> when {
            element.isString -> "string"
            element.content == "true" || element.content == "false" -> "boolean"
            else -> "number"
        }
    }

    private fun <T> struct(serializer: KSerializer<T>, value: T): Shape.Struct = Shape.Struct(
        name = simpleName(serializer.descriptor.serialName),
        encoded = json.encodeToJsonElement(serializer, value).jsonObject,
        descriptor = serializer.descriptor
    )

    private fun <T : Enum<T>> enumWire(serializer: KSerializer<T>, entries: List<T>): Shape.EnumWire =
        Shape.EnumWire(
            name = simpleName(serializer.descriptor.serialName),
            wireValues = entries.map { entry ->
                entry.name to json.encodeToJsonElement(serializer, entry).jsonPrimitive.content
            }
        )

    /** Drops the package qualifier but keeps any outer class, so nested models stay unambiguous. */
    private fun simpleName(serialName: String): String =
        serialName.split('.').dropWhile { it.first().isLowerCase() }.joinToString(".")

    private fun readGolden(): String {
        val stream = javaClass.classLoader.getResourceAsStream(GOLDEN_RESOURCE)
            ?: fail(
                "Golden result shapes not found on the test classpath ($GOLDEN_RESOURCE). " +
                    "Generate it with -D$UPDATE_PROPERTY=true."
            ).let { error("unreachable") }
        return stream.bufferedReader().use { it.readText() }
    }

    /**
     * Reports the difference per shape rather than as a first-differing-line, because renaming one
     * wire key shifts every following line of a sorted block and a line-oriented diff then names
     * an unrelated key.
     */
    private fun describeDifference(expected: String, actual: String): String {
        val expectedShapes = shapesIn(expected)
        val actualShapes = shapesIn(actual)
        val removed = (expectedShapes.keys - actualShapes.keys).sorted()
        val added = (actualShapes.keys - expectedShapes.keys).sorted()

        return buildString {
            if (removed.isNotEmpty()) appendLine("REMOVED shapes: ${removed.joinToString(", ")}")
            if (added.isNotEmpty()) appendLine("ADDED shapes:   ${added.joinToString(", ")}")
            (expectedShapes.keys intersect actualShapes.keys).sorted().forEach { name ->
                val before = expectedShapes.getValue(name)
                val after = actualShapes.getValue(name)
                if (before == after) return@forEach
                appendLine("CHANGED shape $name:")
                (before - after.toSet()).forEach { appendLine("  - $it") }
                (after - before.toSet()).forEach { appendLine("  + $it") }
            }
        }
    }

    private fun shapesIn(rendered: String): Map<String, List<String>> {
        val shapes = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        rendered.lines().forEach { line ->
            when {
                line.startsWith("## ") -> current = shapes.getOrPut(line.removePrefix("## ")) { mutableListOf() }
                line.startsWith("  ") -> current?.add(line.trim())
            }
        }
        return shapes
    }

    private fun buildPinnedShapes(): List<Shape> {
        val usageLocation = UsageLocation(
            file = "src/main/java/com/example/Service.java",
            line = 42,
            column = 17,
            context = "service.handle(request);",
            type = UsageTypes.METHOD_CALL,
            astPath = listOf("PsiClass:Service", "PsiMethod:handle")
        )
        val symbolMatch = SymbolMatch(
            name = "handle",
            qualifiedName = "com.example.Service#handle",
            kind = "method",
            file = "src/main/java/com/example/Service.java",
            line = 42,
            column = 17,
            containerName = "Service",
            language = "JAVA"
        )
        val buildMessage = BuildMessage(
            category = "ERROR",
            message = "cannot find symbol",
            file = "src/main/java/com/example/Service.java",
            line = 42,
            column = 17
        )
        val problemInfo = ProblemInfo(
            message = "Unused import",
            severity = "WARNING",
            file = "src/main/java/com/example/Service.java",
            line = 3,
            column = 1,
            endLine = 3,
            endColumn = 24
        )
        val intentionInfo = IntentionInfo(name = "Remove unused import", description = "Deletes the import")
        val fileCoverageInfo = FileCoverageInfo(
            file = "src/main/java/com/example/Service.java",
            state = "timed_out",
            reason = "File analysis timed out."
        )
        val testResultInfo = TestResultInfo(
            name = "testHandle",
            suite = "com.example.ServiceTest",
            status = "failed",
            durationMs = 137L,
            errorMessage = "expected:<1> but was:<2>",
            stacktrace = "at com.example.ServiceTest.testHandle(ServiceTest.java:21)",
            file = "src/test/java/com/example/ServiceTest.java",
            line = 21
        )
        val testSummary = TestSummary(
            total = 9,
            passed = 7,
            failed = 1,
            ignored = 1,
            runConfigName = "ServiceTest"
        )
        val typeElement = TypeElement(
            name = "Service",
            file = "src/main/java/com/example/Service.java",
            kind = "class",
            language = "JAVA",
            supertypes = listOf(
                TypeElement(
                    name = "AbstractService",
                    file = "src/main/java/com/example/AbstractService.java",
                    kind = "class",
                    language = "JAVA",
                    supertypes = null
                )
            )
        )
        val callElement = CallElement(
            name = "handle",
            file = "src/main/java/com/example/Service.java",
            line = 42,
            column = 17,
            language = "JAVA",
            children = listOf(
                CallElement(
                    name = "validate",
                    file = "src/main/java/com/example/Service.java",
                    line = 51,
                    column = 9,
                    language = "JAVA",
                    children = null
                )
            )
        )
        val implementationLocation = ImplementationLocation(
            name = "DefaultService",
            file = "src/main/java/com/example/DefaultService.java",
            line = 12,
            column = 14,
            kind = "class",
            language = "JAVA"
        )
        val methodInfo = MethodInfo(
            name = "handle",
            signature = "handle(Request): Response",
            containingClass = "com.example.Service",
            file = "src/main/java/com/example/Service.java",
            line = 42,
            column = 17,
            language = "JAVA"
        )
        val usageInfo = UsageInfo(
            file = "src/main/java/com/example/Caller.java",
            line = 8,
            column = 5,
            context = "new Service().handle(request);"
        )
        val memberCandidate = MemberCandidate(
            name = "handle",
            kind = "method",
            signature = "handle(Request)",
            parameterCount = 1,
            line = 42
        )
        val structureNode = StructureNode(
            name = "Service",
            kind = StructureKind.CLASS,
            modifiers = listOf("public", "final"),
            signature = "class Service",
            line = 12,
            endLine = 88,
            children = listOf(
                StructureNode(
                    name = "handle",
                    kind = StructureKind.METHOD,
                    modifiers = listOf("public"),
                    signature = "handle(Request): Response",
                    line = 42,
                    endLine = 55,
                    children = listOf()
                )
            )
        )
        val ssrMatch = StructuralSearchReplaceTool.SsrMatch(
            file = "src/main/java/com/example/Service.java",
            line = 42,
            matchedText = "service.handle(request)"
        )
        val fileConversionResult = FileConversionResult(
            requestedPath = "src/main/java/com/example/Service.java",
            status = ConversionStatus.CONVERTED,
            kotlinFile = "src/main/java/com/example/Service.kt",
            linesConverted = 88,
            javaFileDeleted = true,
            reason = "converted with 1 warning"
        )
        val conversionSummary = ConversionSummary(
            totalRequested = 3,
            converted = 2,
            skipped = 1,
            failed = 1
        )

        return listOf(
            struct(PositionInput.serializer(), PositionInput(file = "src/main/java/com/example/Service.java", line = 42, column = 17)),
            struct(UsageLocation.serializer(), usageLocation),
            struct(
                FindUsagesResult.serializer(),
                FindUsagesResult(
                    usages = listOf(usageLocation),
                    totalCount = 3,
                    truncated = true,
                    nextCursor = "Y3Vyc29yOjE6MjU",
                    hasMore = true,
                    totalCollected = 120,
                    offset = 25,
                    pageSize = 25,
                    stale = true,
                    resolvedSymbol = ResolvedSymbolInfo(
                        name = "handle",
                        kind = "method",
                        container = "com.example.Service",
                        file = "src/main/java/com/example/Service.java",
                        line = 42
                    ),
                    totalIsExact = false
                )
            ),
            struct(
                DefinitionResult.serializer(),
                DefinitionResult(
                    file = "src/main/java/com/example/Service.java",
                    line = 42,
                    column = 17,
                    preview = "public Response handle(Request request) {",
                    symbolName = "handle",
                    astPath = listOf("PsiClass:Service", "PsiMethod:handle")
                )
            ),
            struct(
                ReadFileResult.serializer(),
                ReadFileResult(
                    file = "src/main/java/com/example/Service.java",
                    content = "package com.example;\n",
                    language = "JAVA",
                    lineCount = 88,
                    startLine = 10,
                    endLine = 60,
                    isLibraryFile = true
                )
            ),
            struct(
                TypeHierarchyResult.serializer(),
                TypeHierarchyResult(
                    element = typeElement,
                    supertypes = listOf(typeElement),
                    subtypes = listOf(typeElement)
                )
            ),
            struct(TypeElement.serializer(), typeElement),
            struct(
                CallHierarchyResult.serializer(),
                CallHierarchyResult(element = callElement, calls = listOf(callElement))
            ),
            struct(CallElement.serializer(), callElement),
            struct(
                ImplementationResult.serializer(),
                ImplementationResult(
                    implementations = listOf(implementationLocation),
                    totalCount = 4,
                    nextCursor = "Y3Vyc29yOjI6MTA",
                    hasMore = true,
                    totalCollected = 40,
                    offset = 10,
                    pageSize = 10,
                    stale = true
                )
            ),
            struct(ImplementationLocation.serializer(), implementationLocation),
            struct(
                DiagnosticsResult.serializer(),
                DiagnosticsResult(
                    problems = listOf(problemInfo),
                    intentions = listOf(intentionInfo),
                    problemCount = 1,
                    intentionCount = 1,
                    analysisFresh = true,
                    analysisTimedOut = true,
                    analysisMessage = "analysis finished",
                    analysisMode = "closed_batch",
                    buildErrors = listOf(buildMessage),
                    buildErrorCount = 1,
                    buildWarningCount = 2,
                    buildErrorsTruncated = true,
                    buildTimestamp = 1_753_400_000_000L,
                    testResults = listOf(testResultInfo),
                    testSummary = testSummary,
                    testResultsTruncated = true
                )
            ),
            struct(ProblemInfo.serializer(), problemInfo),
            struct(IntentionInfo.serializer(), intentionInfo),
            struct(TestResultInfo.serializer(), testResultInfo),
            struct(TestSummary.serializer(), testSummary),
            struct(
                ProjectDiagnosticsResult.serializer(),
                ProjectDiagnosticsResult(
                    complete = false,
                    status = "completed",
                    filesConsidered = 12,
                    filesAnalyzed = 9,
                    filesAnalyzedOpenDaemon = 1,
                    filesAnalyzedClosedBatch = 8,
                    filesTimedOut = 1,
                    filesFailed = 1,
                    filesSkipped = 1,
                    filesNotAnalyzed = 0,
                    incompleteFiles = listOf(fileCoverageInfo),
                    incompleteFilesTruncated = true,
                    problems = listOf(problemInfo),
                    problemCount = 3,
                    errorCount = 1,
                    warningCount = 2,
                    problemsTruncated = true,
                    durationMs = 5_150L,
                    analysisMessage = "Closed files use the IDE's public batch analysis."
                )
            ),
            struct(FileCoverageInfo.serializer(), fileCoverageInfo),
            struct(
                ProjectDiagnosticsInProgressResult.serializer(),
                ProjectDiagnosticsInProgressResult(
                    status = "running",
                    analysisId = "9b2e4c1a-0000-0000-0000-000000000000",
                    elapsedSeconds = 61,
                    filesProcessed = 40,
                    filesConsidered = 120,
                    timeoutSeconds = 600,
                    message = "Analysis is still executing"
                )
            ),
            struct(
                RefactoringResult.serializer(),
                RefactoringResult(
                    success = true,
                    affectedFiles = listOf("src/main/java/com/example/Service.java"),
                    changesCount = 3,
                    message = "Renamed 'handle' to 'process'",
                    warnings = listOf("1 usage in a comment was not updated"),
                    unretargetedImporters = listOf("src/main/java/com/example/Caller.java")
                )
            ),
            struct(
                IndexStatusResult.serializer(),
                IndexStatusResult(isDumbMode = true, isIndexing = true, indexingProgress = 0.42)
            ),
            struct(
                SyncFilesResult.serializer(),
                SyncFilesResult(
                    syncedPaths = listOf("/Users/dev/project/src"),
                    syncedAll = true,
                    message = "Synced 1 path"
                )
            ),
            struct(BuildMessage.serializer(), buildMessage),
            struct(
                BuildProjectResult.serializer(),
                BuildProjectResult(
                    success = true,
                    aborted = true,
                    errors = 1,
                    warnings = 2,
                    buildMessages = listOf(buildMessage),
                    truncated = true,
                    rawOutput = "BUILD FAILED",
                    durationMs = 4_213L
                )
            ),
            struct(
                BuildInProgressResult.serializer(),
                BuildInProgressResult(
                    status = "running",
                    buildId = "7a1d2c3b-0000-0000-0000-000000000000",
                    elapsedSeconds = 61,
                    timeoutSeconds = 600,
                    message = "Build is still executing"
                )
            ),
            struct(
                LinkInProgressResult.serializer(),
                LinkInProgressResult(
                    status = "running",
                    linkId = "a1b2c3d4",
                    systemName = "Maven",
                    elapsedSeconds = 12,
                    message = "Maven link in progress"
                )
            ),
            struct(
                FindSymbolResult.serializer(),
                FindSymbolResult(
                    symbols = listOf(symbolMatch),
                    totalCount = 2,
                    query = "handle",
                    nextCursor = "Y3Vyc29yOjM6NTA",
                    hasMore = true,
                    totalCollected = 200,
                    offset = 50,
                    pageSize = 50,
                    stale = true
                )
            ),
            struct(SymbolMatch.serializer(), symbolMatch),
            struct(
                SuperMethodsResult.serializer(),
                SuperMethodsResult(
                    method = methodInfo,
                    hierarchy = listOf(
                        SuperMethodInfo(
                            name = "handle",
                            signature = "handle(Request): Response",
                            containingClass = "com.example.AbstractService",
                            containingClassKind = "class",
                            file = "src/main/java/com/example/AbstractService.java",
                            line = 19,
                            column = 21,
                            isInterface = true,
                            depth = 2,
                            language = "JAVA"
                        )
                    ),
                    totalCount = 1
                )
            ),
            struct(MethodInfo.serializer(), methodInfo),
            struct(
                SuperMethodInfo.serializer(),
                SuperMethodInfo(
                    name = "handle",
                    signature = "handle(Request): Response",
                    containingClass = "com.example.AbstractService",
                    containingClassKind = "class",
                    file = "src/main/java/com/example/AbstractService.java",
                    line = 19,
                    column = 21,
                    isInterface = true,
                    depth = 2,
                    language = "JAVA"
                )
            ),
            struct(
                FindClassResult.serializer(),
                FindClassResult(
                    classes = listOf(symbolMatch),
                    totalCount = 2,
                    query = "Service",
                    nextCursor = "Y3Vyc29yOjQ6MjA",
                    hasMore = true,
                    totalCollected = 80,
                    offset = 20,
                    pageSize = 20,
                    stale = true
                )
            ),
            struct(
                FindFileResult.serializer(),
                FindFileResult(
                    files = listOf(
                        FileMatch(
                            name = "Service.java",
                            path = "src/main/java/com/example/Service.java",
                            directory = "src/main/java/com/example"
                        )
                    ),
                    totalCount = 2,
                    query = "Service",
                    nextCursor = "Y3Vyc29yOjU6MzA",
                    hasMore = true,
                    totalCollected = 60,
                    offset = 30,
                    pageSize = 30,
                    stale = true
                )
            ),
            struct(
                FileMatch.serializer(),
                FileMatch(
                    name = "Service.java",
                    path = "src/main/java/com/example/Service.java",
                    directory = "src/main/java/com/example"
                )
            ),
            struct(
                ActiveFileInfo.serializer(),
                ActiveFileInfo(
                    file = "src/main/java/com/example/Service.java",
                    line = 42,
                    column = 17,
                    selectedText = "handle",
                    hasSelection = true,
                    language = "JAVA"
                )
            ),
            struct(
                GetActiveFileResult.serializer(),
                GetActiveFileResult(
                    activeFiles = listOf(
                        ActiveFileInfo(
                            file = "src/main/java/com/example/Service.java",
                            line = 42,
                            column = 17,
                            selectedText = "handle",
                            hasSelection = true,
                            language = "JAVA"
                        )
                    )
                )
            ),
            struct(
                OpenFileResult.serializer(),
                OpenFileResult(
                    file = "src/main/java/com/example/Service.java",
                    opened = true,
                    message = "Opened at 42:17"
                )
            ),
            struct(
                TestEntry.serializer(),
                TestEntry(
                    framework = "JUnit5",
                    className = "com.example.ServiceTest",
                    methodName = "testHandle",
                    displayName = "ServiceTest.testHandle",
                    file = "src/test/java/com/example/ServiceTest.java",
                    line = 21
                )
            ),
            struct(
                ListTestsResult.serializer(),
                ListTestsResult(
                    tests = listOf(
                        TestEntry(
                            framework = "JUnit5",
                            className = "com.example.ServiceTest",
                            methodName = "testHandle",
                            displayName = "ServiceTest.testHandle",
                            file = "src/test/java/com/example/ServiceTest.java",
                            line = 21
                        )
                    ),
                    count = 1,
                    truncated = true
                )
            ),
            enumWire(TestStatus.serializer(), TestStatus.entries),
            struct(
                TestRunEntry.serializer(),
                TestRunEntry(
                    name = "com.example.ServiceTest.testHandle",
                    status = TestStatus.FAILED,
                    errorMessage = "expected:<1> but was:<2>",
                    stackTrace = "java.lang.AssertionError: expected:<1> but was:<2>\n" +
                            "\tat com.example.ServiceTest.testHandle(ServiceTest.java:21)"
                )
            ),
            struct(
                RunTestsResult.serializer(),
                RunTestsResult(
                    success = true,
                    timedOut = true,
                    noTestsFound = true,
                    exitCode = 1,
                    passed = 7,
                    failed = 1,
                    errors = 1,
                    total = 9,
                    tests = listOf(
                        TestRunEntry(
                            name = "com.example.ServiceTest.testHandle",
                            status = TestStatus.FAILED,
                            errorMessage = "expected:<1> but was:<2>"
                        )
                    )
                )
            ),
            struct(
                RunTestsInProgressResult.serializer(),
                RunTestsInProgressResult(
                    status = "running",
                    runId = "6f9c1f6e-0000-0000-0000-000000000000",
                    configName = "com.example.ServiceTest",
                    elapsedSeconds = 61,
                    timeoutSeconds = 7200,
                    message = "Test run 'com.example.ServiceTest' is still executing"
                )
            ),
            struct(
                SearchTextResult.serializer(),
                SearchTextResult(
                    matches = listOf(
                        TextMatch(
                            file = "src/main/java/com/example/Service.java",
                            line = 42,
                            column = 17,
                            context = "service.handle(request);",
                            contextType = "CODE"
                        )
                    ),
                    totalCount = 2,
                    query = "handle",
                    nextCursor = "Y3Vyc29yOjY6MTU",
                    hasMore = true,
                    totalCollected = 45,
                    offset = 15,
                    pageSize = 15,
                    stale = true
                )
            ),
            struct(
                TextMatch.serializer(),
                TextMatch(
                    file = "src/main/java/com/example/Service.java",
                    line = 42,
                    column = 17,
                    context = "service.handle(request);",
                    contextType = "CODE"
                )
            ),
            struct(StructureNode.serializer(), structureNode),
            enumWire(StructureKind.serializer(), StructureKind.entries),
            struct(
                FileStructureResult.serializer(),
                FileStructureResult(
                    file = "src/main/java/com/example/Service.java",
                    language = "JAVA",
                    structure = "Service\n  handle(Request): Response"
                )
            ),
            struct(
                SafeDeleteBlockedResult.serializer(),
                SafeDeleteBlockedResult(
                    canDelete = true,
                    elementName = "handle",
                    elementType = "method",
                    usageCount = 2,
                    blockingUsages = listOf(usageInfo),
                    message = "2 usages block deletion"
                )
            ),
            struct(UsageInfo.serializer(), usageInfo),
            struct(
                NoSymbolFoundResult.serializer(),
                NoSymbolFoundResult(
                    error = "No named symbol at 42:17",
                    position = PositionInfo(line = 42, column = 17, elementType = "PsiWhiteSpace"),
                    suggestions = listOf(
                        SymbolSuggestion(name = "handle", type = "method", line = 42, column = 21, distance = 4)
                    ),
                    hint = "Point at the identifier, not the whitespace"
                )
            ),
            struct(
                PositionInfo.serializer(),
                PositionInfo(line = 42, column = 17, elementType = "PsiWhiteSpace")
            ),
            struct(
                SymbolSuggestion.serializer(),
                SymbolSuggestion(name = "handle", type = "method", line = 42, column = 21, distance = 4)
            ),
            struct(
                SymbolInfo.serializer(),
                SymbolInfo(name = "handle", type = "method", line = 42, column = 21)
            ),
            struct(
                SafeDeleteFileBlockedResult.serializer(),
                SafeDeleteFileBlockedResult(
                    canDelete = true,
                    fileName = "Service.java",
                    symbolCount = 3,
                    externalUsageCount = 2,
                    blockingUsages = listOf(usageInfo),
                    message = "2 external usages block deletion"
                )
            ),
            struct(
                MemberEditResult.serializer(),
                MemberEditResult(
                    success = true,
                    file = "src/main/java/com/example/Service.java",
                    message = "Replaced 'handle'",
                    startLine = 42,
                    endLine = 55
                )
            ),
            struct(
                MemberErrorResult.serializer(),
                MemberErrorResult(
                    error = "Member not found",
                    member = "handle",
                    candidates = listOf(memberCandidate),
                    hint = "Pass the exact signature"
                )
            ),
            struct(MemberCandidate.serializer(), memberCandidate),
            struct(StructuralSearchReplaceTool.SsrMatch.serializer(), ssrMatch),
            struct(
                StructuralSearchReplaceTool.SsrResult.serializer(),
                StructuralSearchReplaceTool.SsrResult(
                    success = true,
                    matchCount = 3,
                    replacedCount = 3,
                    matches = listOf(ssrMatch),
                    message = "Replaced 3 matches"
                )
            ),
            struct(
                CreateFileTool.CreateFileResult.serializer(),
                CreateFileTool.CreateFileResult(
                    success = true,
                    file = "src/main/java/com/example/Service.java",
                    message = "Created 1 file"
                )
            ),
            struct(
                ChangeSignatureTool.ChangeSignatureResult.serializer(),
                ChangeSignatureTool.ChangeSignatureResult(
                    success = true,
                    file = "src/main/java/com/example/Service.java",
                    message = "Signature changed",
                    affectedFiles = listOf("src/main/java/com/example/Caller.java"),
                    changesCount = 2
                )
            ),
            struct(
                ReplaceTextInFileTool.ReplaceTextResult.serializer(),
                ReplaceTextInFileTool.ReplaceTextResult(
                    success = true,
                    file = "src/main/java/com/example/Service.java",
                    replacements = 4,
                    message = "Replaced 4 occurrences",
                    affectedLines = listOf(10, 24)
                )
            ),
            enumWire(ConversionStatus.serializer(), ConversionStatus.entries),
            struct(FileConversionResult.serializer(), fileConversionResult),
            struct(
                JavaToKotlinConversionResult.serializer(),
                JavaToKotlinConversionResult(
                    files = listOf(fileConversionResult),
                    summary = conversionSummary
                )
            ),
            struct(ConversionSummary.serializer(), conversionSummary),
        )
    }
}
