package com.github.hechtcarmel.jetbrainsindexmcpplugin.models

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.*
import junit.framework.TestCase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ToolModelsUnitTest : TestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // PositionInput tests

    fun testPositionInputSerialization() {
        val input = PositionInput(
            file = "src/main/kotlin/Main.kt",
            line = 10,
            column = 5
        )

        val serialized = json.encodeToString(input)
        val deserialized = json.decodeFromString<PositionInput>(serialized)

        assertEquals("src/main/kotlin/Main.kt", deserialized.file)
        assertEquals(10, deserialized.line)
        assertEquals(5, deserialized.column)
    }

    // UsageLocation tests

    fun testUsageLocationSerialization() {
        val location = UsageLocation(
            file = "src/Service.kt",
            line = 25,
            column = 12,
            context = "val service = UserService()",
            type = "METHOD_CALL"
        )

        val serialized = json.encodeToString(location)
        val deserialized = json.decodeFromString<UsageLocation>(serialized)

        assertEquals("src/Service.kt", deserialized.file)
        assertEquals(25, deserialized.line)
        assertEquals(12, deserialized.column)
        assertEquals("val service = UserService()", deserialized.context)
        assertEquals("METHOD_CALL", deserialized.type)
    }

    // FindUsagesResult tests

    fun testFindUsagesResultSerialization() {
        val result = FindUsagesResult(
            usages = listOf(
                UsageLocation("file1.kt", 10, 5, "context1", "REFERENCE"),
                UsageLocation("file2.kt", 20, 8, "context2", "METHOD_CALL")
            ),
            totalCount = 2
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<FindUsagesResult>(serialized)

        assertEquals(2, deserialized.usages.size)
        assertEquals(2, deserialized.totalCount)
    }

    fun testFindUsagesResultEmpty() {
        val result = FindUsagesResult(usages = emptyList(), totalCount = 0)

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<FindUsagesResult>(serialized)

        assertTrue(deserialized.usages.isEmpty())
        assertEquals(0, deserialized.totalCount)
    }

    // DefinitionResult tests

    fun testDefinitionResultSerialization() {
        val result = DefinitionResult(
            file = "src/model/User.kt",
            line = 5,
            column = 1,
            preview = "data class User(val name: String)",
            symbolName = "User"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<DefinitionResult>(serialized)

        assertEquals("src/model/User.kt", deserialized.file)
        assertEquals(5, deserialized.line)
        assertEquals(1, deserialized.column)
        assertEquals("data class User(val name: String)", deserialized.preview)
        assertEquals("User", deserialized.symbolName)
    }

    // TypeHierarchyResult tests

    fun testTypeHierarchyResultSerialization() {
        val result = TypeHierarchyResult(
            element = TypeElement("MyService", "src/MyService.kt", "CLASS"),
            supertypes = listOf(
                TypeElement("BaseService", "src/BaseService.kt", "CLASS"),
                TypeElement("Service", null, "INTERFACE")
            ),
            subtypes = listOf(
                TypeElement("SpecialService", "src/SpecialService.kt", "CLASS")
            )
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<TypeHierarchyResult>(serialized)

        assertEquals("MyService", deserialized.element.name)
        assertEquals(2, deserialized.supertypes.size)
        assertEquals(1, deserialized.subtypes.size)
    }

    // TypeElement tests

    fun testTypeElementWithNestedSupertypes() {
        val element = TypeElement(
            name = "Child",
            file = "Child.kt",
            kind = "CLASS",
            supertypes = listOf(
                TypeElement(
                    name = "Parent",
                    file = "Parent.kt",
                    kind = "CLASS",
                    supertypes = listOf(
                        TypeElement("GrandParent", "GrandParent.kt", "CLASS")
                    )
                )
            )
        )

        val serialized = json.encodeToString(element)
        val deserialized = json.decodeFromString<TypeElement>(serialized)

        assertEquals("Child", deserialized.name)
        assertNotNull(deserialized.supertypes)
        assertEquals(1, deserialized.supertypes!!.size)
        assertEquals("Parent", deserialized.supertypes!![0].name)
        assertEquals(1, deserialized.supertypes!![0].supertypes!!.size)
    }

    fun testTypeElementWithNullFile() {
        val element = TypeElement(
            name = "Serializable",
            file = null,
            kind = "INTERFACE"
        )

        val serialized = json.encodeToString(element)
        val deserialized = json.decodeFromString<TypeElement>(serialized)

        assertEquals("Serializable", deserialized.name)
        assertNull(deserialized.file)
        assertEquals("INTERFACE", deserialized.kind)
    }

    // CallHierarchyResult tests

    fun testCallHierarchyResultSerialization() {
        val result = CallHierarchyResult(
            element = CallElement("processData", "src/Processor.kt", 50),
            calls = listOf(
                CallElement("validateInput", "src/Validator.kt", 30),
                CallElement("saveResult", "src/Repository.kt", 100)
            )
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<CallHierarchyResult>(serialized)

        assertEquals("processData", deserialized.element.name)
        assertEquals(2, deserialized.calls.size)
    }

    // CallElement tests

    fun testCallElementWithChildren() {
        val element = CallElement(
            name = "main",
            file = "Main.kt",
            line = 5,
            children = listOf(
                CallElement("init", "Init.kt", 10, children = listOf(
                    CallElement("loadConfig", "Config.kt", 15)
                )),
                CallElement("run", "Runner.kt", 20)
            )
        )

        val serialized = json.encodeToString(element)
        val deserialized = json.decodeFromString<CallElement>(serialized)

        assertEquals("main", deserialized.name)
        assertNotNull(deserialized.children)
        assertEquals(2, deserialized.children!!.size)
        assertEquals(1, deserialized.children!![0].children!!.size)
    }

    // ImplementationResult tests

    fun testImplementationResultSerialization() {
        val result = ImplementationResult(
            implementations = listOf(
                ImplementationLocation("UserRepositoryImpl", "src/impl/UserRepositoryImpl.kt", 15, "CLASS"),
                ImplementationLocation("MockUserRepository", "src/test/MockUserRepository.kt", 8, "CLASS")
            ),
            totalCount = 2
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<ImplementationResult>(serialized)

        assertEquals(2, deserialized.implementations.size)
        assertEquals(2, deserialized.totalCount)
        assertEquals("UserRepositoryImpl", deserialized.implementations[0].name)
    }

    // ImplementationLocation tests

    fun testImplementationLocationSerialization() {
        val location = ImplementationLocation(
            name = "ServiceImpl",
            file = "src/ServiceImpl.java",
            line = 10,
            kind = "CLASS"
        )

        val serialized = json.encodeToString(location)
        val deserialized = json.decodeFromString<ImplementationLocation>(serialized)

        assertEquals("ServiceImpl", deserialized.name)
        assertEquals("src/ServiceImpl.java", deserialized.file)
        assertEquals(10, deserialized.line)
        assertEquals("CLASS", deserialized.kind)
    }

    // DiagnosticsResult tests

    fun testDiagnosticsResultSerialization() {
        val result = DiagnosticsResult(
            problems = listOf(
                ProblemInfo("Unused variable", "WARNING", "Main.kt", 10, 5, 10, 15)
            ),
            intentions = listOf(
                IntentionInfo("Add import", "Import the required class")
            ),
            problemCount = 1,
            intentionCount = 1
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<DiagnosticsResult>(serialized)

        assertEquals(1, deserialized.problemCount)
        assertEquals(1, deserialized.intentionCount)
        assertEquals("Unused variable", deserialized.problems[0].message)
        assertEquals("Add import", deserialized.intentions[0].name)
    }

    // ProblemInfo tests

    fun testProblemInfoWithNullEndPositions() {
        val problem = ProblemInfo(
            message = "Syntax error",
            severity = "ERROR",
            file = "Broken.kt",
            line = 5,
            column = 10,
            endLine = null,
            endColumn = null
        )

        val serialized = json.encodeToString(problem)
        val deserialized = json.decodeFromString<ProblemInfo>(serialized)

        assertEquals("Syntax error", deserialized.message)
        assertEquals("ERROR", deserialized.severity)
        assertNull(deserialized.endLine)
        assertNull(deserialized.endColumn)
    }

    fun testProblemInfoAllSeverities() {
        val severities = listOf("ERROR", "WARNING", "WEAK_WARNING", "INFO")

        severities.forEach { severity ->
            val problem = ProblemInfo("Test", severity, "file.kt", 1, 1, 1, 10)
            val serialized = json.encodeToString(problem)
            val deserialized = json.decodeFromString<ProblemInfo>(serialized)
            assertEquals(severity, deserialized.severity)
        }
    }

    // IntentionInfo tests

    fun testIntentionInfoWithDescription() {
        val intention = IntentionInfo(
            name = "Convert to expression body",
            description = "Simplify function by converting to expression body"
        )

        val serialized = json.encodeToString(intention)
        val deserialized = json.decodeFromString<IntentionInfo>(serialized)

        assertEquals("Convert to expression body", deserialized.name)
        assertEquals("Simplify function by converting to expression body", deserialized.description)
    }

    fun testIntentionInfoWithoutDescription() {
        val intention = IntentionInfo(
            name = "Quick fix",
            description = null
        )

        val serialized = json.encodeToString(intention)
        val deserialized = json.decodeFromString<IntentionInfo>(serialized)

        assertEquals("Quick fix", deserialized.name)
        assertNull(deserialized.description)
    }

    // RefactoringResult tests

    fun testRefactoringResultSuccess() {
        val result = RefactoringResult(
            success = true,
            affectedFiles = listOf("src/Main.kt", "src/Service.kt", "test/MainTest.kt"),
            changesCount = 5,
            message = "Renamed 'foo' to 'bar' in 3 files"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<RefactoringResult>(serialized)

        assertTrue(deserialized.success)
        assertEquals(3, deserialized.affectedFiles.size)
        assertEquals(5, deserialized.changesCount)
        assertTrue(deserialized.message.contains("Renamed"))
    }

    fun testRefactoringResultFailure() {
        val result = RefactoringResult(
            success = false,
            affectedFiles = emptyList(),
            changesCount = 0,
            message = "Cannot rename: symbol has usages in read-only files"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<RefactoringResult>(serialized)

        assertFalse(deserialized.success)
        assertTrue(deserialized.affectedFiles.isEmpty())
        assertEquals(0, deserialized.changesCount)
    }

    // IndexStatusResult tests

    fun testIndexStatusResultSmartMode() {
        val result = IndexStatusResult(
            isDumbMode = false,
            isIndexing = false,
            indexingProgress = null
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<IndexStatusResult>(serialized)

        assertFalse(deserialized.isDumbMode)
        assertFalse(deserialized.isIndexing)
        assertNull(deserialized.indexingProgress)
    }

    fun testIndexStatusResultDumbMode() {
        val result = IndexStatusResult(
            isDumbMode = true,
            isIndexing = true,
            indexingProgress = 0.75
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<IndexStatusResult>(serialized)

        assertTrue(deserialized.isDumbMode)
        assertTrue(deserialized.isIndexing)
        assertEquals(0.75, deserialized.indexingProgress!!, 0.001)
    }

    // FindSymbolResult tests

    fun testFindSymbolResultSerialization() {
        val result = FindSymbolResult(
            symbols = listOf(
                SymbolMatch("UserService", "com.example.UserService", "CLASS", "src/UserService.kt", 10, null),
                SymbolMatch("findById", "com.example.UserRepository.findById", "METHOD", "src/UserRepository.kt", 25, "UserRepository")
            ),
            totalCount = 2,
            query = "User"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<FindSymbolResult>(serialized)

        assertEquals(2, deserialized.symbols.size)
        assertEquals(2, deserialized.totalCount)
        assertEquals("User", deserialized.query)
    }

    fun testFindSymbolResultEmpty() {
        val result = FindSymbolResult(symbols = emptyList(), totalCount = 0, query = "NonExistent")

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<FindSymbolResult>(serialized)

        assertTrue(deserialized.symbols.isEmpty())
        assertEquals(0, deserialized.totalCount)
        assertEquals("NonExistent", deserialized.query)
    }

    // SymbolMatch tests

    fun testSymbolMatchSerialization() {
        val match = SymbolMatch(
            name = "UserService",
            qualifiedName = "com.example.service.UserService",
            kind = "INTERFACE",
            file = "src/main/java/com/example/service/UserService.java",
            line = 12,
            containerName = null
        )

        val serialized = json.encodeToString(match)
        val deserialized = json.decodeFromString<SymbolMatch>(serialized)

        assertEquals("UserService", deserialized.name)
        assertEquals("com.example.service.UserService", deserialized.qualifiedName)
        assertEquals("INTERFACE", deserialized.kind)
        assertEquals("src/main/java/com/example/service/UserService.java", deserialized.file)
        assertEquals(12, deserialized.line)
        assertNull(deserialized.containerName)
    }

    fun testSymbolMatchWithContainer() {
        val match = SymbolMatch(
            name = "findById",
            qualifiedName = "com.example.UserRepository.findById",
            kind = "METHOD",
            file = "src/UserRepository.kt",
            line = 25,
            containerName = "UserRepository"
        )

        val serialized = json.encodeToString(match)
        val deserialized = json.decodeFromString<SymbolMatch>(serialized)

        assertEquals("findById", deserialized.name)
        assertEquals("UserRepository", deserialized.containerName)
    }

    fun testSymbolMatchAllKinds() {
        val kinds = listOf("CLASS", "INTERFACE", "ENUM", "ANNOTATION", "RECORD", "ABSTRACT_CLASS", "METHOD", "FIELD")

        kinds.forEach { kind ->
            val match = SymbolMatch("Test", "com.Test", kind, "file.kt", 1, null)
            val serialized = json.encodeToString(match)
            val deserialized = json.decodeFromString<SymbolMatch>(serialized)
            assertEquals(kind, deserialized.kind)
        }
    }

    // SuperMethodsResult tests

    fun testSuperMethodsResultSerialization() {
        val result = SuperMethodsResult(
            method = MethodInfo(
                name = "findUser",
                signature = "findUser(String id): User",
                containingClass = "com.example.UserServiceImpl",
                file = "src/UserServiceImpl.kt",
                line = 25
            ),
            hierarchy = listOf(
                SuperMethodInfo(
                    name = "findUser",
                    signature = "findUser(String id): User",
                    containingClass = "com.example.UserService",
                    containingClassKind = "INTERFACE",
                    file = "src/UserService.kt",
                    line = 15,
                    isInterface = true,
                    depth = 1
                )
            ),
            totalCount = 1
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<SuperMethodsResult>(serialized)

        assertEquals("findUser", deserialized.method.name)
        assertEquals(1, deserialized.hierarchy.size)
        assertEquals(1, deserialized.totalCount)
        assertEquals(1, deserialized.hierarchy[0].depth)
    }

    fun testSuperMethodsResultEmpty() {
        val result = SuperMethodsResult(
            method = MethodInfo("helperMethod", "helperMethod(): void", "com.example.Service", "file.kt", 50),
            hierarchy = emptyList(),
            totalCount = 0
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<SuperMethodsResult>(serialized)

        assertTrue(deserialized.hierarchy.isEmpty())
        assertEquals(0, deserialized.totalCount)
    }

    // MethodInfo tests

    fun testMethodInfoSerialization() {
        val info = MethodInfo(
            name = "processData",
            signature = "processData(List<String> data): Result",
            containingClass = "com.example.DataProcessor",
            file = "src/DataProcessor.kt",
            line = 100
        )

        val serialized = json.encodeToString(info)
        val deserialized = json.decodeFromString<MethodInfo>(serialized)

        assertEquals("processData", deserialized.name)
        assertEquals("processData(List<String> data): Result", deserialized.signature)
        assertEquals("com.example.DataProcessor", deserialized.containingClass)
        assertEquals("src/DataProcessor.kt", deserialized.file)
        assertEquals(100, deserialized.line)
    }

    // SuperMethodInfo tests

    fun testSuperMethodInfoSerialization() {
        val info = SuperMethodInfo(
            name = "save",
            signature = "save(Entity entity): void",
            containingClass = "com.example.Repository",
            containingClassKind = "INTERFACE",
            file = "src/Repository.kt",
            line = 8,
            isInterface = true,
            depth = 3
        )

        val serialized = json.encodeToString(info)
        val deserialized = json.decodeFromString<SuperMethodInfo>(serialized)

        assertEquals("save", deserialized.name)
        assertEquals("INTERFACE", deserialized.containingClassKind)
        assertTrue(deserialized.isInterface)
        assertEquals(3, deserialized.depth)
    }

    fun testSuperMethodInfoWithNullFile() {
        val info = SuperMethodInfo(
            name = "toString",
            signature = "toString(): String",
            containingClass = "java.lang.Object",
            containingClassKind = "CLASS",
            file = null,
            line = null,
            isInterface = false,
            depth = 2
        )

        val serialized = json.encodeToString(info)
        val deserialized = json.decodeFromString<SuperMethodInfo>(serialized)

        assertEquals("toString", deserialized.name)
        assertNull(deserialized.file)
        assertNull(deserialized.line)
        assertFalse(deserialized.isInterface)
    }

    fun testSuperMethodInfoMultiLevelHierarchy() {
        val hierarchy = listOf(
            SuperMethodInfo("save", "save(E e): void", "AbstractRepo", "ABSTRACT_CLASS", "file1.kt", 20, false, 1),
            SuperMethodInfo("save", "save(E e): void", "BaseRepo", "ABSTRACT_CLASS", "file2.kt", 15, false, 2),
            SuperMethodInfo("save", "save(E e): void", "Repository", "INTERFACE", "file3.kt", 8, true, 3)
        )

        val result = SuperMethodsResult(
            method = MethodInfo("save", "save(User u): void", "UserRepo", "file0.kt", 30),
            hierarchy = hierarchy,
            totalCount = 3
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<SuperMethodsResult>(serialized)

        assertEquals(3, deserialized.hierarchy.size)
        assertEquals(1, deserialized.hierarchy[0].depth)
        assertEquals(2, deserialized.hierarchy[1].depth)
        assertEquals(3, deserialized.hierarchy[2].depth)
        assertFalse(deserialized.hierarchy[0].isInterface)
        assertTrue(deserialized.hierarchy[2].isInterface)
    }

    // FindClassResult tests

    fun testFindClassResultSerialization() {
        val result = FindClassResult(
            classes = listOf(
                SymbolMatch("UserService", "com.example.UserService", "CLASS", "src/UserService.kt", 10, null, "Kotlin"),
                SymbolMatch("UserRepository", "com.example.UserRepository", "INTERFACE", "src/UserRepository.kt", 15, null, "Kotlin")
            ),
            totalCount = 2,
            query = "User"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<FindClassResult>(serialized)

        assertEquals(2, deserialized.classes.size)
        assertEquals(2, deserialized.totalCount)
        assertEquals("User", deserialized.query)
        assertEquals("UserService", deserialized.classes[0].name)
    }

    fun testFindClassResultEmpty() {
        val result = FindClassResult(classes = emptyList(), totalCount = 0, query = "NonExistent")

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<FindClassResult>(serialized)

        assertTrue(deserialized.classes.isEmpty())
        assertEquals(0, deserialized.totalCount)
        assertEquals("NonExistent", deserialized.query)
    }

    // FindFileResult tests

    fun testFindFileResultSerialization() {
        val result = FindFileResult(
            files = listOf(
                FileMatch("UserService.kt", "src/main/kotlin/UserService.kt", "src/main/kotlin"),
                FileMatch("build.gradle", "build.gradle", "")
            ),
            totalCount = 2,
            query = "User"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<FindFileResult>(serialized)

        assertEquals(2, deserialized.files.size)
        assertEquals(2, deserialized.totalCount)
        assertEquals("User", deserialized.query)
        assertEquals("UserService.kt", deserialized.files[0].name)
    }

    fun testFindFileResultEmpty() {
        val result = FindFileResult(files = emptyList(), totalCount = 0, query = "NonExistent.txt")

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<FindFileResult>(serialized)

        assertTrue(deserialized.files.isEmpty())
        assertEquals(0, deserialized.totalCount)
    }

    // FileMatch tests

    fun testFileMatchSerialization() {
        val match = FileMatch(
            name = "Main.kt",
            path = "src/main/kotlin/Main.kt",
            directory = "src/main/kotlin"
        )

        val serialized = json.encodeToString(match)
        val deserialized = json.decodeFromString<FileMatch>(serialized)

        assertEquals("Main.kt", deserialized.name)
        assertEquals("src/main/kotlin/Main.kt", deserialized.path)
        assertEquals("src/main/kotlin", deserialized.directory)
    }

    fun testFileMatchRootDirectory() {
        val match = FileMatch(
            name = "README.md",
            path = "README.md",
            directory = ""
        )

        val serialized = json.encodeToString(match)
        val deserialized = json.decodeFromString<FileMatch>(serialized)

        assertEquals("README.md", deserialized.name)
        assertEquals("README.md", deserialized.path)
        assertEquals("", deserialized.directory)
    }

    // SearchTextResult tests

    fun testSearchTextResultSerialization() {
        val result = SearchTextResult(
            matches = listOf(
                TextMatch("src/Main.kt", 10, 5, "val config = ConfigManager()", "CODE"),
                TextMatch("src/Service.kt", 25, 12, "// TODO: refactor this", "COMMENT")
            ),
            totalCount = 2,
            query = "config"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<SearchTextResult>(serialized)

        assertEquals(2, deserialized.matches.size)
        assertEquals(2, deserialized.totalCount)
        assertEquals("config", deserialized.query)
        assertEquals("src/Main.kt", deserialized.matches[0].file)
    }

    fun testSearchTextResultEmpty() {
        val result = SearchTextResult(matches = emptyList(), totalCount = 0, query = "NonExistentTerm")

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<SearchTextResult>(serialized)

        assertTrue(deserialized.matches.isEmpty())
        assertEquals(0, deserialized.totalCount)
    }

    // TextMatch tests

    fun testTextMatchSerialization() {
        val match = TextMatch(
            file = "src/Processor.kt",
            line = 50,
            column = 15,
            context = "val result = processor.process(data)",
            contextType = "CODE"
        )

        val serialized = json.encodeToString(match)
        val deserialized = json.decodeFromString<TextMatch>(serialized)

        assertEquals("src/Processor.kt", deserialized.file)
        assertEquals(50, deserialized.line)
        assertEquals(15, deserialized.column)
        assertEquals("val result = processor.process(data)", deserialized.context)
        assertEquals("CODE", deserialized.contextType)
    }

    fun testTextMatchAllContextTypes() {
        val contextTypes = listOf("CODE", "COMMENT", "STRING_LITERAL")

        contextTypes.forEach { contextType ->
            val match = TextMatch("file.kt", 1, 1, "test context", contextType)
            val serialized = json.encodeToString(match)
            val deserialized = json.decodeFromString<TextMatch>(serialized)
            assertEquals(contextType, deserialized.contextType)
        }
    }
}
