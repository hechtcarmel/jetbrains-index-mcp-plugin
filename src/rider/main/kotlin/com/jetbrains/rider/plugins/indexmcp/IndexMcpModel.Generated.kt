@file:Suppress("EXPERIMENTAL_API_USAGE","EXPERIMENTAL_UNSIGNED_LITERALS","PackageDirectoryMismatch","UnusedImport","unused","LocalVariableName","CanBeVal","PropertyName","EnumEntryName","ClassName","ObjectPropertyName","UnnecessaryVariable","SpellCheckingInspection")
package com.jetbrains.rd.ide.model

import com.jetbrains.rd.framework.*
import com.jetbrains.rd.framework.base.*
import com.jetbrains.rd.framework.impl.*

import com.jetbrains.rd.util.lifetime.*
import com.jetbrains.rd.util.reactive.*
import com.jetbrains.rd.util.string.*
import com.jetbrains.rd.util.*
import kotlin.time.Duration
import kotlin.reflect.KClass
import kotlin.jvm.JvmStatic



/**
 * #### Generated from [IndexMcpModel.kt:18]
 */
class IndexMcpModel private constructor(
    private val _getBackendStatus: RdCall<Unit, RdBackendStatusResult>,
    private val _findTypes: RdCall<RdFindTypesRequest, RdFindTypesResult>,
    private val _findSymbols: RdCall<RdFindSymbolsRequest, RdFindSymbolsResult>,
    private val _findDefinition: RdCall<RdFindDefinitionRequest, RdDefinitionResult?>,
    private val _findReferences: RdCall<RdFindReferencesRequest, RdFindReferencesResult>,
    private val _resolveSymbol: RdCall<RdResolveSymbolRequest, RdSymbolInfo?>,
    private val _resolveSymbolIndexed: RdCall<RdResolveSymbolIndexedRequest, RdResolveSymbolIndexedResult>,
    private val _getTypeHierarchy: RdCall<RdTypeHierarchyRequest, RdTypeHierarchyResult?>,
    private val _findImplementations: RdCall<RdImplementationsRequest, RdImplementationsResult?>,
    private val _getCallHierarchy: RdCall<RdCallHierarchyRequest, RdCallHierarchyResult?>,
    private val _findSuperMethods: RdCall<RdSuperMethodsRequest, RdSuperMethodsResult?>,
    private val _getFileStructure: RdCall<RdFileStructureRequest, RdFileStructureResult?>,
    private val _renameSymbol: RdCall<RdRenameSymbolRequest, RdRenameSymbolResult?>,
    private val _renameFile: RdCall<RdRenameFileRequest, RdRenameFileResult?>,
    private val _moveFile: RdCall<RdMoveFileRequest, RdMoveFileResult?>,
    private val _safeDelete: RdCall<RdSafeDeleteRequest, RdSafeDeleteResult?>
) : RdExtBase() {
    //companion
    
    companion object : ISerializersOwner {
        
        override fun registerSerializersCore(serializers: ISerializers)  {
            val classLoader = javaClass.classLoader
            serializers.register(LazyCompanionMarshaller(RdId(-5977976679503629463), classLoader, "com.jetbrains.rd.ide.model.RdSourcePosition"))
            serializers.register(LazyCompanionMarshaller(RdId(4375242365750196220), classLoader, "com.jetbrains.rd.ide.model.RdSemanticTarget"))
            serializers.register(LazyCompanionMarshaller(RdId(-1313392469938805269), classLoader, "com.jetbrains.rd.ide.model.RdSymbolInfo"))
            serializers.register(LazyCompanionMarshaller(RdId(5994498188749596030), classLoader, "com.jetbrains.rd.ide.model.RdBackendStatusResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-4108457705209229324), classLoader, "com.jetbrains.rd.ide.model.RdFindTypesRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(4627919189821581848), classLoader, "com.jetbrains.rd.ide.model.RdFindTypesResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-6674192697615603502), classLoader, "com.jetbrains.rd.ide.model.RdFindSymbolsRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(974815982251785594), classLoader, "com.jetbrains.rd.ide.model.RdFindSymbolsResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-869352042608296866), classLoader, "com.jetbrains.rd.ide.model.RdFindDefinitionRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(3534513108899072789), classLoader, "com.jetbrains.rd.ide.model.RdDefinitionResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-3032379611242238732), classLoader, "com.jetbrains.rd.ide.model.RdReferenceInfo"))
            serializers.register(LazyCompanionMarshaller(RdId(7995059252749119881), classLoader, "com.jetbrains.rd.ide.model.RdFindReferencesRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(7993636522934682083), classLoader, "com.jetbrains.rd.ide.model.RdFindReferencesResult"))
            serializers.register(LazyCompanionMarshaller(RdId(8323260431033600144), classLoader, "com.jetbrains.rd.ide.model.RdResolveSymbolRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(-7597324494782965955), classLoader, "com.jetbrains.rd.ide.model.RdResolveSymbolIndexedRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(-5005525067240565329), classLoader, "com.jetbrains.rd.ide.model.RdResolveSymbolIndexedResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-1227020239591611591), classLoader, "com.jetbrains.rd.ide.model.RdTypeHierarchyRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(4720868785486666291), classLoader, "com.jetbrains.rd.ide.model.RdTypeHierarchyResult"))
            serializers.register(LazyCompanionMarshaller(RdId(4312643375883653203), classLoader, "com.jetbrains.rd.ide.model.RdImplementationsRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(3709455090907832793), classLoader, "com.jetbrains.rd.ide.model.RdImplementationsResult"))
            serializers.register(LazyCompanionMarshaller(RdId(1059166656247531101), classLoader, "com.jetbrains.rd.ide.model.RdCallHierarchyRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(-5321339677649567089), classLoader, "com.jetbrains.rd.ide.model.RdCallHierarchyResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-3118716768245179149), classLoader, "com.jetbrains.rd.ide.model.RdSuperMethodsRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(-6175457339077949787), classLoader, "com.jetbrains.rd.ide.model.RdSuperMethodInfo"))
            serializers.register(LazyCompanionMarshaller(RdId(-3670941329370988231), classLoader, "com.jetbrains.rd.ide.model.RdSuperMethodsResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-6049031805937391491), classLoader, "com.jetbrains.rd.ide.model.RdFileStructureRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(-994497033408018441), classLoader, "com.jetbrains.rd.ide.model.RdFlatStructureNode"))
            serializers.register(LazyCompanionMarshaller(RdId(1590038723070745711), classLoader, "com.jetbrains.rd.ide.model.RdFileStructureResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-1370157819129083564), classLoader, "com.jetbrains.rd.ide.model.RdRenameSymbolRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(-5136722195302102871), classLoader, "com.jetbrains.rd.ide.model.RdMutationVerification"))
            serializers.register(LazyCompanionMarshaller(RdId(-5994761243749120840), classLoader, "com.jetbrains.rd.ide.model.RdRenameSymbolResult"))
            serializers.register(LazyCompanionMarshaller(RdId(3365879169746377776), classLoader, "com.jetbrains.rd.ide.model.RdRenameFileRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(3678914310064694876), classLoader, "com.jetbrains.rd.ide.model.RdRenameFileResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-4180692821025388419), classLoader, "com.jetbrains.rd.ide.model.RdMoveFileRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(-4895311142280643473), classLoader, "com.jetbrains.rd.ide.model.RdMoveFileResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-1384896468765046606), classLoader, "com.jetbrains.rd.ide.model.RdSafeDeleteRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(6616599642070861522), classLoader, "com.jetbrains.rd.ide.model.RdSafeDeleteBlockedUsage"))
            serializers.register(LazyCompanionMarshaller(RdId(-6590292944502201958), classLoader, "com.jetbrains.rd.ide.model.RdSafeDeleteResult"))
        }
        
        
        
        
        private val __RdDefinitionResultNullableSerializer = RdDefinitionResult.nullable()
        private val __RdSymbolInfoNullableSerializer = RdSymbolInfo.nullable()
        private val __RdTypeHierarchyResultNullableSerializer = RdTypeHierarchyResult.nullable()
        private val __RdImplementationsResultNullableSerializer = RdImplementationsResult.nullable()
        private val __RdCallHierarchyResultNullableSerializer = RdCallHierarchyResult.nullable()
        private val __RdSuperMethodsResultNullableSerializer = RdSuperMethodsResult.nullable()
        private val __RdFileStructureResultNullableSerializer = RdFileStructureResult.nullable()
        private val __RdRenameSymbolResultNullableSerializer = RdRenameSymbolResult.nullable()
        private val __RdRenameFileResultNullableSerializer = RdRenameFileResult.nullable()
        private val __RdMoveFileResultNullableSerializer = RdMoveFileResult.nullable()
        private val __RdSafeDeleteResultNullableSerializer = RdSafeDeleteResult.nullable()
        
        const val serializationHash = -5717211913063562566L
        
    }
    override val serializersOwner: ISerializersOwner get() = IndexMcpModel
    override val serializationHash: Long get() = IndexMcpModel.serializationHash
    
    //fields
    val getBackendStatus: IRdCall<Unit, RdBackendStatusResult> get() = _getBackendStatus
    val findTypes: IRdCall<RdFindTypesRequest, RdFindTypesResult> get() = _findTypes
    val findSymbols: IRdCall<RdFindSymbolsRequest, RdFindSymbolsResult> get() = _findSymbols
    val findDefinition: IRdCall<RdFindDefinitionRequest, RdDefinitionResult?> get() = _findDefinition
    val findReferences: IRdCall<RdFindReferencesRequest, RdFindReferencesResult> get() = _findReferences
    val resolveSymbol: IRdCall<RdResolveSymbolRequest, RdSymbolInfo?> get() = _resolveSymbol
    val resolveSymbolIndexed: IRdCall<RdResolveSymbolIndexedRequest, RdResolveSymbolIndexedResult> get() = _resolveSymbolIndexed
    val getTypeHierarchy: IRdCall<RdTypeHierarchyRequest, RdTypeHierarchyResult?> get() = _getTypeHierarchy
    val findImplementations: IRdCall<RdImplementationsRequest, RdImplementationsResult?> get() = _findImplementations
    val getCallHierarchy: IRdCall<RdCallHierarchyRequest, RdCallHierarchyResult?> get() = _getCallHierarchy
    val findSuperMethods: IRdCall<RdSuperMethodsRequest, RdSuperMethodsResult?> get() = _findSuperMethods
    val getFileStructure: IRdCall<RdFileStructureRequest, RdFileStructureResult?> get() = _getFileStructure
    val renameSymbol: IRdCall<RdRenameSymbolRequest, RdRenameSymbolResult?> get() = _renameSymbol
    val renameFile: IRdCall<RdRenameFileRequest, RdRenameFileResult?> get() = _renameFile
    val moveFile: IRdCall<RdMoveFileRequest, RdMoveFileResult?> get() = _moveFile
    val safeDelete: IRdCall<RdSafeDeleteRequest, RdSafeDeleteResult?> get() = _safeDelete
    //methods
    //initializer
    init {
        bindableChildren.add("getBackendStatus" to _getBackendStatus)
        bindableChildren.add("findTypes" to _findTypes)
        bindableChildren.add("findSymbols" to _findSymbols)
        bindableChildren.add("findDefinition" to _findDefinition)
        bindableChildren.add("findReferences" to _findReferences)
        bindableChildren.add("resolveSymbol" to _resolveSymbol)
        bindableChildren.add("resolveSymbolIndexed" to _resolveSymbolIndexed)
        bindableChildren.add("getTypeHierarchy" to _getTypeHierarchy)
        bindableChildren.add("findImplementations" to _findImplementations)
        bindableChildren.add("getCallHierarchy" to _getCallHierarchy)
        bindableChildren.add("findSuperMethods" to _findSuperMethods)
        bindableChildren.add("getFileStructure" to _getFileStructure)
        bindableChildren.add("renameSymbol" to _renameSymbol)
        bindableChildren.add("renameFile" to _renameFile)
        bindableChildren.add("moveFile" to _moveFile)
        bindableChildren.add("safeDelete" to _safeDelete)
    }
    
    //secondary constructor
    internal constructor(
    ) : this(
        RdCall<Unit, RdBackendStatusResult>(FrameworkMarshallers.Void, RdBackendStatusResult),
        RdCall<RdFindTypesRequest, RdFindTypesResult>(RdFindTypesRequest, RdFindTypesResult),
        RdCall<RdFindSymbolsRequest, RdFindSymbolsResult>(RdFindSymbolsRequest, RdFindSymbolsResult),
        RdCall<RdFindDefinitionRequest, RdDefinitionResult?>(RdFindDefinitionRequest, __RdDefinitionResultNullableSerializer),
        RdCall<RdFindReferencesRequest, RdFindReferencesResult>(RdFindReferencesRequest, RdFindReferencesResult),
        RdCall<RdResolveSymbolRequest, RdSymbolInfo?>(RdResolveSymbolRequest, __RdSymbolInfoNullableSerializer),
        RdCall<RdResolveSymbolIndexedRequest, RdResolveSymbolIndexedResult>(RdResolveSymbolIndexedRequest, RdResolveSymbolIndexedResult),
        RdCall<RdTypeHierarchyRequest, RdTypeHierarchyResult?>(RdTypeHierarchyRequest, __RdTypeHierarchyResultNullableSerializer),
        RdCall<RdImplementationsRequest, RdImplementationsResult?>(RdImplementationsRequest, __RdImplementationsResultNullableSerializer),
        RdCall<RdCallHierarchyRequest, RdCallHierarchyResult?>(RdCallHierarchyRequest, __RdCallHierarchyResultNullableSerializer),
        RdCall<RdSuperMethodsRequest, RdSuperMethodsResult?>(RdSuperMethodsRequest, __RdSuperMethodsResultNullableSerializer),
        RdCall<RdFileStructureRequest, RdFileStructureResult?>(RdFileStructureRequest, __RdFileStructureResultNullableSerializer),
        RdCall<RdRenameSymbolRequest, RdRenameSymbolResult?>(RdRenameSymbolRequest, __RdRenameSymbolResultNullableSerializer),
        RdCall<RdRenameFileRequest, RdRenameFileResult?>(RdRenameFileRequest, __RdRenameFileResultNullableSerializer),
        RdCall<RdMoveFileRequest, RdMoveFileResult?>(RdMoveFileRequest, __RdMoveFileResultNullableSerializer),
        RdCall<RdSafeDeleteRequest, RdSafeDeleteResult?>(RdSafeDeleteRequest, __RdSafeDeleteResultNullableSerializer)
    )
    
    //equals trait
    //hash code trait
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("IndexMcpModel (")
        printer.indent {
            print("getBackendStatus = "); _getBackendStatus.print(printer); println()
            print("findTypes = "); _findTypes.print(printer); println()
            print("findSymbols = "); _findSymbols.print(printer); println()
            print("findDefinition = "); _findDefinition.print(printer); println()
            print("findReferences = "); _findReferences.print(printer); println()
            print("resolveSymbol = "); _resolveSymbol.print(printer); println()
            print("resolveSymbolIndexed = "); _resolveSymbolIndexed.print(printer); println()
            print("getTypeHierarchy = "); _getTypeHierarchy.print(printer); println()
            print("findImplementations = "); _findImplementations.print(printer); println()
            print("getCallHierarchy = "); _getCallHierarchy.print(printer); println()
            print("findSuperMethods = "); _findSuperMethods.print(printer); println()
            print("getFileStructure = "); _getFileStructure.print(printer); println()
            print("renameSymbol = "); _renameSymbol.print(printer); println()
            print("renameFile = "); _renameFile.print(printer); println()
            print("moveFile = "); _moveFile.print(printer); println()
            print("safeDelete = "); _safeDelete.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    override fun deepClone(): IndexMcpModel   {
        return IndexMcpModel(
            _getBackendStatus.deepClonePolymorphic(),
            _findTypes.deepClonePolymorphic(),
            _findSymbols.deepClonePolymorphic(),
            _findDefinition.deepClonePolymorphic(),
            _findReferences.deepClonePolymorphic(),
            _resolveSymbol.deepClonePolymorphic(),
            _resolveSymbolIndexed.deepClonePolymorphic(),
            _getTypeHierarchy.deepClonePolymorphic(),
            _findImplementations.deepClonePolymorphic(),
            _getCallHierarchy.deepClonePolymorphic(),
            _findSuperMethods.deepClonePolymorphic(),
            _getFileStructure.deepClonePolymorphic(),
            _renameSymbol.deepClonePolymorphic(),
            _renameFile.deepClonePolymorphic(),
            _moveFile.deepClonePolymorphic(),
            _safeDelete.deepClonePolymorphic()
        )
    }
    //contexts
    //threading
    override val extThreading: ExtThreadingKind get() = ExtThreadingKind.Default
}
val Solution.indexMcpModel get() = getOrCreateExtension("indexMcpModel", ::IndexMcpModel)



/**
 * #### Generated from [IndexMcpModel.kt:54]
 */
data class RdBackendStatusResult (
    val backendVersion: String,
    val solutionLoaded: Boolean,
    val psiServicesAvailable: Boolean,
    val message: String
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdBackendStatusResult> {
        override val _type: KClass<RdBackendStatusResult> = RdBackendStatusResult::class
        override val id: RdId get() = RdId(5994498188749596030)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdBackendStatusResult  {
            val backendVersion = buffer.readString()
            val solutionLoaded = buffer.readBool()
            val psiServicesAvailable = buffer.readBool()
            val message = buffer.readString()
            return RdBackendStatusResult(backendVersion, solutionLoaded, psiServicesAvailable, message)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdBackendStatusResult)  {
            buffer.writeString(value.backendVersion)
            buffer.writeBool(value.solutionLoaded)
            buffer.writeBool(value.psiServicesAvailable)
            buffer.writeString(value.message)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdBackendStatusResult
        
        if (backendVersion != other.backendVersion) return false
        if (solutionLoaded != other.solutionLoaded) return false
        if (psiServicesAvailable != other.psiServicesAvailable) return false
        if (message != other.message) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + backendVersion.hashCode()
        __r = __r*31 + solutionLoaded.hashCode()
        __r = __r*31 + psiServicesAvailable.hashCode()
        __r = __r*31 + message.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdBackendStatusResult (")
        printer.indent {
            print("backendVersion = "); backendVersion.print(printer); println()
            print("solutionLoaded = "); solutionLoaded.print(printer); println()
            print("psiServicesAvailable = "); psiServicesAvailable.print(printer); println()
            print("message = "); message.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:165]
 */
data class RdCallHierarchyRequest (
    val target: RdSemanticTarget,
    val direction: String,
    val depth: Int,
    val scope: String,
    val limit: Int
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdCallHierarchyRequest> {
        override val _type: KClass<RdCallHierarchyRequest> = RdCallHierarchyRequest::class
        override val id: RdId get() = RdId(1059166656247531101)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdCallHierarchyRequest  {
            val target = RdSemanticTarget.read(ctx, buffer)
            val direction = buffer.readString()
            val depth = buffer.readInt()
            val scope = buffer.readString()
            val limit = buffer.readInt()
            return RdCallHierarchyRequest(target, direction, depth, scope, limit)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdCallHierarchyRequest)  {
            RdSemanticTarget.write(ctx, buffer, value.target)
            buffer.writeString(value.direction)
            buffer.writeInt(value.depth)
            buffer.writeString(value.scope)
            buffer.writeInt(value.limit)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdCallHierarchyRequest
        
        if (target != other.target) return false
        if (direction != other.direction) return false
        if (depth != other.depth) return false
        if (scope != other.scope) return false
        if (limit != other.limit) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + target.hashCode()
        __r = __r*31 + direction.hashCode()
        __r = __r*31 + depth.hashCode()
        __r = __r*31 + scope.hashCode()
        __r = __r*31 + limit.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdCallHierarchyRequest (")
        printer.indent {
            print("target = "); target.print(printer); println()
            print("direction = "); direction.print(printer); println()
            print("depth = "); depth.print(printer); println()
            print("scope = "); scope.print(printer); println()
            print("limit = "); limit.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:174]
 */
data class RdCallHierarchyResult (
    val root: RdSymbolInfo,
    val calls: List<RdSymbolInfo>,
    val message: String?
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdCallHierarchyResult> {
        override val _type: KClass<RdCallHierarchyResult> = RdCallHierarchyResult::class
        override val id: RdId get() = RdId(-5321339677649567089)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdCallHierarchyResult  {
            val root = RdSymbolInfo.read(ctx, buffer)
            val calls = buffer.readList { RdSymbolInfo.read(ctx, buffer) }
            val message = buffer.readNullable { buffer.readString() }
            return RdCallHierarchyResult(root, calls, message)
        }

        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdCallHierarchyResult)  {
            RdSymbolInfo.write(ctx, buffer, value.root)
            buffer.writeList(value.calls) { v -> RdSymbolInfo.write(ctx, buffer, v) }
            buffer.writeNullable(value.message) { buffer.writeString(it) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdCallHierarchyResult
        
        if (root != other.root) return false
        if (calls != other.calls) return false
        if (message != other.message) return false

        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + root.hashCode()
        __r = __r*31 + calls.hashCode()
        __r = __r*31 + (message?.hashCode() ?: 0)
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdCallHierarchyResult (")
        printer.indent {
            print("root = "); root.print(printer); println()
            print("calls = "); calls.print(printer); println()
            print("message = "); message.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:94]
 */
data class RdDefinitionResult (
    val definition: RdSymbolInfo,
    val preview: String,
    val astPath: List<String>,
    val locationKind: String,
    val locationDisplayName: String?
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdDefinitionResult> {
        override val _type: KClass<RdDefinitionResult> = RdDefinitionResult::class
        override val id: RdId get() = RdId(3534513108899072789)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdDefinitionResult  {
            val definition = RdSymbolInfo.read(ctx, buffer)
            val preview = buffer.readString()
            val astPath = buffer.readList { buffer.readString() }
            val locationKind = buffer.readString()
            val locationDisplayName = buffer.readNullable { buffer.readString() }
            return RdDefinitionResult(definition, preview, astPath, locationKind, locationDisplayName)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdDefinitionResult)  {
            RdSymbolInfo.write(ctx, buffer, value.definition)
            buffer.writeString(value.preview)
            buffer.writeList(value.astPath) { v -> buffer.writeString(v) }
            buffer.writeString(value.locationKind)
            buffer.writeNullable(value.locationDisplayName) { buffer.writeString(it) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdDefinitionResult
        
        if (definition != other.definition) return false
        if (preview != other.preview) return false
        if (astPath != other.astPath) return false
        if (locationKind != other.locationKind) return false
        if (locationDisplayName != other.locationDisplayName) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + definition.hashCode()
        __r = __r*31 + preview.hashCode()
        __r = __r*31 + astPath.hashCode()
        __r = __r*31 + locationKind.hashCode()
        __r = __r*31 + if (locationDisplayName != null) locationDisplayName.hashCode() else 0
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdDefinitionResult (")
        printer.indent {
            print("definition = "); definition.print(printer); println()
            print("preview = "); preview.print(printer); println()
            print("astPath = "); astPath.print(printer); println()
            print("locationKind = "); locationKind.print(printer); println()
            print("locationDisplayName = "); locationDisplayName.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:199]
 */
data class RdFileStructureRequest (
    val filePath: String
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdFileStructureRequest> {
        override val _type: KClass<RdFileStructureRequest> = RdFileStructureRequest::class
        override val id: RdId get() = RdId(-6049031805937391491)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdFileStructureRequest  {
            val filePath = buffer.readString()
            return RdFileStructureRequest(filePath)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdFileStructureRequest)  {
            buffer.writeString(value.filePath)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdFileStructureRequest
        
        if (filePath != other.filePath) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + filePath.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdFileStructureRequest (")
        printer.indent {
            print("filePath = "); filePath.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:214]
 */
data class RdFileStructureResult (
    val nodes: List<RdFlatStructureNode>
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdFileStructureResult> {
        override val _type: KClass<RdFileStructureResult> = RdFileStructureResult::class
        override val id: RdId get() = RdId(1590038723070745711)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdFileStructureResult  {
            val nodes = buffer.readList { RdFlatStructureNode.read(ctx, buffer) }
            return RdFileStructureResult(nodes)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdFileStructureResult)  {
            buffer.writeList(value.nodes) { v -> RdFlatStructureNode.write(ctx, buffer, v) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdFileStructureResult
        
        if (nodes != other.nodes) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + nodes.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdFileStructureResult (")
        printer.indent {
            print("nodes = "); nodes.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:88]
 */
data class RdFindDefinitionRequest (
    val target: RdSemanticTarget,
    val fullElementPreview: Boolean,
    val maxPreviewLines: Int
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdFindDefinitionRequest> {
        override val _type: KClass<RdFindDefinitionRequest> = RdFindDefinitionRequest::class
        override val id: RdId get() = RdId(-869352042608296866)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdFindDefinitionRequest  {
            val target = RdSemanticTarget.read(ctx, buffer)
            val fullElementPreview = buffer.readBool()
            val maxPreviewLines = buffer.readInt()
            return RdFindDefinitionRequest(target, fullElementPreview, maxPreviewLines)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdFindDefinitionRequest)  {
            RdSemanticTarget.write(ctx, buffer, value.target)
            buffer.writeBool(value.fullElementPreview)
            buffer.writeInt(value.maxPreviewLines)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdFindDefinitionRequest
        
        if (target != other.target) return false
        if (fullElementPreview != other.fullElementPreview) return false
        if (maxPreviewLines != other.maxPreviewLines) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + target.hashCode()
        __r = __r*31 + fullElementPreview.hashCode()
        __r = __r*31 + maxPreviewLines.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdFindDefinitionRequest (")
        printer.indent {
            print("target = "); target.print(printer); println()
            print("fullElementPreview = "); fullElementPreview.print(printer); println()
            print("maxPreviewLines = "); maxPreviewLines.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:111]
 */
data class RdFindReferencesRequest (
    val target: RdSemanticTarget,
    val scope: String,
    val limit: Int
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdFindReferencesRequest> {
        override val _type: KClass<RdFindReferencesRequest> = RdFindReferencesRequest::class
        override val id: RdId get() = RdId(7995059252749119881)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdFindReferencesRequest  {
            val target = RdSemanticTarget.read(ctx, buffer)
            val scope = buffer.readString()
            val limit = buffer.readInt()
            return RdFindReferencesRequest(target, scope, limit)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdFindReferencesRequest)  {
            RdSemanticTarget.write(ctx, buffer, value.target)
            buffer.writeString(value.scope)
            buffer.writeInt(value.limit)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdFindReferencesRequest
        
        if (target != other.target) return false
        if (scope != other.scope) return false
        if (limit != other.limit) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + target.hashCode()
        __r = __r*31 + scope.hashCode()
        __r = __r*31 + limit.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdFindReferencesRequest (")
        printer.indent {
            print("target = "); target.print(printer); println()
            print("scope = "); scope.print(printer); println()
            print("limit = "); limit.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:117]
 */
data class RdFindReferencesResult (
    val references: List<RdReferenceInfo>,
    val totalCount: Int,
    val message: String?
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdFindReferencesResult> {
        override val _type: KClass<RdFindReferencesResult> = RdFindReferencesResult::class
        override val id: RdId get() = RdId(7993636522934682083)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdFindReferencesResult  {
            val references = buffer.readList { RdReferenceInfo.read(ctx, buffer) }
            val totalCount = buffer.readInt()
            val message = buffer.readNullable { buffer.readString() }
            return RdFindReferencesResult(references, totalCount, message)
        }

        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdFindReferencesResult)  {
            buffer.writeList(value.references) { v -> RdReferenceInfo.write(ctx, buffer, v) }
            buffer.writeInt(value.totalCount)
            buffer.writeNullable(value.message) { buffer.writeString(it) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdFindReferencesResult
        
        if (references != other.references) return false
        if (totalCount != other.totalCount) return false
        if (message != other.message) return false

        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + references.hashCode()
        __r = __r*31 + totalCount.hashCode()
        __r = __r*31 + (message?.hashCode() ?: 0)
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdFindReferencesResult (")
        printer.indent {
            print("references = "); references.print(printer); println()
            print("totalCount = "); totalCount.print(printer); println()
            print("message = "); message.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:76]
 */
data class RdFindSymbolsRequest (
    val query: String,
    val scope: String,
    val language: String,
    val limit: Int
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdFindSymbolsRequest> {
        override val _type: KClass<RdFindSymbolsRequest> = RdFindSymbolsRequest::class
        override val id: RdId get() = RdId(-6674192697615603502)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdFindSymbolsRequest  {
            val query = buffer.readString()
            val scope = buffer.readString()
            val language = buffer.readString()
            val limit = buffer.readInt()
            return RdFindSymbolsRequest(query, scope, language, limit)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdFindSymbolsRequest)  {
            buffer.writeString(value.query)
            buffer.writeString(value.scope)
            buffer.writeString(value.language)
            buffer.writeInt(value.limit)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdFindSymbolsRequest
        
        if (query != other.query) return false
        if (scope != other.scope) return false
        if (language != other.language) return false
        if (limit != other.limit) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + query.hashCode()
        __r = __r*31 + scope.hashCode()
        __r = __r*31 + language.hashCode()
        __r = __r*31 + limit.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdFindSymbolsRequest (")
        printer.indent {
            print("query = "); query.print(printer); println()
            print("scope = "); scope.print(printer); println()
            print("language = "); language.print(printer); println()
            print("limit = "); limit.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:83]
 */
data class RdFindSymbolsResult (
    val symbols: List<RdSymbolInfo>,
    val totalCount: Int
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdFindSymbolsResult> {
        override val _type: KClass<RdFindSymbolsResult> = RdFindSymbolsResult::class
        override val id: RdId get() = RdId(974815982251785594)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdFindSymbolsResult  {
            val symbols = buffer.readList { RdSymbolInfo.read(ctx, buffer) }
            val totalCount = buffer.readInt()
            return RdFindSymbolsResult(symbols, totalCount)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdFindSymbolsResult)  {
            buffer.writeList(value.symbols) { v -> RdSymbolInfo.write(ctx, buffer, v) }
            buffer.writeInt(value.totalCount)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdFindSymbolsResult
        
        if (symbols != other.symbols) return false
        if (totalCount != other.totalCount) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + symbols.hashCode()
        __r = __r*31 + totalCount.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdFindSymbolsResult (")
        printer.indent {
            print("symbols = "); symbols.print(printer); println()
            print("totalCount = "); totalCount.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:63]
 */
data class RdFindTypesRequest (
    val query: String,
    val matchMode: String,
    val scope: String,
    val language: String?,
    val limit: Int
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdFindTypesRequest> {
        override val _type: KClass<RdFindTypesRequest> = RdFindTypesRequest::class
        override val id: RdId get() = RdId(-4108457705209229324)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdFindTypesRequest  {
            val query = buffer.readString()
            val matchMode = buffer.readString()
            val scope = buffer.readString()
            val language = buffer.readNullable { buffer.readString() }
            val limit = buffer.readInt()
            return RdFindTypesRequest(query, matchMode, scope, language, limit)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdFindTypesRequest)  {
            buffer.writeString(value.query)
            buffer.writeString(value.matchMode)
            buffer.writeString(value.scope)
            buffer.writeNullable(value.language) { buffer.writeString(it) }
            buffer.writeInt(value.limit)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdFindTypesRequest
        
        if (query != other.query) return false
        if (matchMode != other.matchMode) return false
        if (scope != other.scope) return false
        if (language != other.language) return false
        if (limit != other.limit) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + query.hashCode()
        __r = __r*31 + matchMode.hashCode()
        __r = __r*31 + scope.hashCode()
        __r = __r*31 + if (language != null) language.hashCode() else 0
        __r = __r*31 + limit.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdFindTypesRequest (")
        printer.indent {
            print("query = "); query.print(printer); println()
            print("matchMode = "); matchMode.print(printer); println()
            print("scope = "); scope.print(printer); println()
            print("language = "); language.print(printer); println()
            print("limit = "); limit.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:71]
 */
data class RdFindTypesResult (
    val types: List<RdSymbolInfo>,
    val totalCount: Int
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdFindTypesResult> {
        override val _type: KClass<RdFindTypesResult> = RdFindTypesResult::class
        override val id: RdId get() = RdId(4627919189821581848)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdFindTypesResult  {
            val types = buffer.readList { RdSymbolInfo.read(ctx, buffer) }
            val totalCount = buffer.readInt()
            return RdFindTypesResult(types, totalCount)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdFindTypesResult)  {
            buffer.writeList(value.types) { v -> RdSymbolInfo.write(ctx, buffer, v) }
            buffer.writeInt(value.totalCount)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdFindTypesResult
        
        if (types != other.types) return false
        if (totalCount != other.totalCount) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + types.hashCode()
        __r = __r*31 + totalCount.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdFindTypesResult (")
        printer.indent {
            print("types = "); types.print(printer); println()
            print("totalCount = "); totalCount.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:205]
 */
data class RdFlatStructureNode (
    val name: String,
    val kind: String,
    val signature: String?,
    val modifiers: List<String>,
    val line: Int,
    val depth: Int
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdFlatStructureNode> {
        override val _type: KClass<RdFlatStructureNode> = RdFlatStructureNode::class
        override val id: RdId get() = RdId(-994497033408018441)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdFlatStructureNode  {
            val name = buffer.readString()
            val kind = buffer.readString()
            val signature = buffer.readNullable { buffer.readString() }
            val modifiers = buffer.readList { buffer.readString() }
            val line = buffer.readInt()
            val depth = buffer.readInt()
            return RdFlatStructureNode(name, kind, signature, modifiers, line, depth)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdFlatStructureNode)  {
            buffer.writeString(value.name)
            buffer.writeString(value.kind)
            buffer.writeNullable(value.signature) { buffer.writeString(it) }
            buffer.writeList(value.modifiers) { v -> buffer.writeString(v) }
            buffer.writeInt(value.line)
            buffer.writeInt(value.depth)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdFlatStructureNode
        
        if (name != other.name) return false
        if (kind != other.kind) return false
        if (signature != other.signature) return false
        if (modifiers != other.modifiers) return false
        if (line != other.line) return false
        if (depth != other.depth) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + name.hashCode()
        __r = __r*31 + kind.hashCode()
        __r = __r*31 + if (signature != null) signature.hashCode() else 0
        __r = __r*31 + modifiers.hashCode()
        __r = __r*31 + line.hashCode()
        __r = __r*31 + depth.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdFlatStructureNode (")
        printer.indent {
            print("name = "); name.print(printer); println()
            print("kind = "); kind.print(printer); println()
            print("signature = "); signature.print(printer); println()
            print("modifiers = "); modifiers.print(printer); println()
            print("line = "); line.print(printer); println()
            print("depth = "); depth.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:153]
 */
data class RdImplementationsRequest (
    val position: RdSourcePosition,
    val scope: String,
    val limit: Int
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdImplementationsRequest> {
        override val _type: KClass<RdImplementationsRequest> = RdImplementationsRequest::class
        override val id: RdId get() = RdId(4312643375883653203)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdImplementationsRequest  {
            val position = RdSourcePosition.read(ctx, buffer)
            val scope = buffer.readString()
            val limit = buffer.readInt()
            return RdImplementationsRequest(position, scope, limit)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdImplementationsRequest)  {
            RdSourcePosition.write(ctx, buffer, value.position)
            buffer.writeString(value.scope)
            buffer.writeInt(value.limit)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdImplementationsRequest
        
        if (position != other.position) return false
        if (scope != other.scope) return false
        if (limit != other.limit) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + position.hashCode()
        __r = __r*31 + scope.hashCode()
        __r = __r*31 + limit.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdImplementationsRequest (")
        printer.indent {
            print("position = "); position.print(printer); println()
            print("scope = "); scope.print(printer); println()
            print("limit = "); limit.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:159]
 */
data class RdImplementationsResult (
    val implementations: List<RdSymbolInfo>
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdImplementationsResult> {
        override val _type: KClass<RdImplementationsResult> = RdImplementationsResult::class
        override val id: RdId get() = RdId(3709455090907832793)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdImplementationsResult  {
            val implementations = buffer.readList { RdSymbolInfo.read(ctx, buffer) }
            return RdImplementationsResult(implementations)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdImplementationsResult)  {
            buffer.writeList(value.implementations) { v -> RdSymbolInfo.write(ctx, buffer, v) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdImplementationsResult
        
        if (implementations != other.implementations) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + implementations.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdImplementationsResult (")
        printer.indent {
            print("implementations = "); implementations.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:258]
 */
data class RdMoveFileRequest (
    val filePath: String,
    val destinationDirectory: String
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdMoveFileRequest> {
        override val _type: KClass<RdMoveFileRequest> = RdMoveFileRequest::class
        override val id: RdId get() = RdId(-4180692821025388419)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdMoveFileRequest  {
            val filePath = buffer.readString()
            val destinationDirectory = buffer.readString()
            return RdMoveFileRequest(filePath, destinationDirectory)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdMoveFileRequest)  {
            buffer.writeString(value.filePath)
            buffer.writeString(value.destinationDirectory)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdMoveFileRequest
        
        if (filePath != other.filePath) return false
        if (destinationDirectory != other.destinationDirectory) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + filePath.hashCode()
        __r = __r*31 + destinationDirectory.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdMoveFileRequest (")
        printer.indent {
            print("filePath = "); filePath.print(printer); println()
            print("destinationDirectory = "); destinationDirectory.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:263]
 */
data class RdMoveFileResult (
    val success: Boolean,
    val oldPath: String,
    val newPath: String,
    val affectedFiles: List<String>,
    val changesCount: Int,
    val message: String,
    val status: String,
    val verification: RdMutationVerification?
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdMoveFileResult> {
        override val _type: KClass<RdMoveFileResult> = RdMoveFileResult::class
        override val id: RdId get() = RdId(-4895311142280643473)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdMoveFileResult  {
            val success = buffer.readBool()
            val oldPath = buffer.readString()
            val newPath = buffer.readString()
            val affectedFiles = buffer.readList { buffer.readString() }
            val changesCount = buffer.readInt()
            val message = buffer.readString()
            val status = buffer.readString()
            val verification = buffer.readNullable { RdMutationVerification.read(ctx, buffer) }
            return RdMoveFileResult(success, oldPath, newPath, affectedFiles, changesCount, message, status, verification)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdMoveFileResult)  {
            buffer.writeBool(value.success)
            buffer.writeString(value.oldPath)
            buffer.writeString(value.newPath)
            buffer.writeList(value.affectedFiles) { v -> buffer.writeString(v) }
            buffer.writeInt(value.changesCount)
            buffer.writeString(value.message)
            buffer.writeString(value.status)
            buffer.writeNullable(value.verification) { RdMutationVerification.write(ctx, buffer, it) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdMoveFileResult
        
        if (success != other.success) return false
        if (oldPath != other.oldPath) return false
        if (newPath != other.newPath) return false
        if (affectedFiles != other.affectedFiles) return false
        if (changesCount != other.changesCount) return false
        if (message != other.message) return false
        if (status != other.status) return false
        if (verification != other.verification) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + success.hashCode()
        __r = __r*31 + oldPath.hashCode()
        __r = __r*31 + newPath.hashCode()
        __r = __r*31 + affectedFiles.hashCode()
        __r = __r*31 + changesCount.hashCode()
        __r = __r*31 + message.hashCode()
        __r = __r*31 + status.hashCode()
        __r = __r*31 + if (verification != null) verification.hashCode() else 0
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdMoveFileResult (")
        printer.indent {
            print("success = "); success.print(printer); println()
            print("oldPath = "); oldPath.print(printer); println()
            print("newPath = "); newPath.print(printer); println()
            print("affectedFiles = "); affectedFiles.print(printer); println()
            print("changesCount = "); changesCount.print(printer); println()
            print("message = "); message.print(printer); println()
            print("status = "); status.print(printer); println()
            print("verification = "); verification.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:225]
 */
data class RdMutationVerification (
    val status: String,
    val checksRun: List<String>,
    val warnings: List<String>
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdMutationVerification> {
        override val _type: KClass<RdMutationVerification> = RdMutationVerification::class
        override val id: RdId get() = RdId(-5136722195302102871)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdMutationVerification  {
            val status = buffer.readString()
            val checksRun = buffer.readList { buffer.readString() }
            val warnings = buffer.readList { buffer.readString() }
            return RdMutationVerification(status, checksRun, warnings)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdMutationVerification)  {
            buffer.writeString(value.status)
            buffer.writeList(value.checksRun) { v -> buffer.writeString(v) }
            buffer.writeList(value.warnings) { v -> buffer.writeString(v) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdMutationVerification
        
        if (status != other.status) return false
        if (checksRun != other.checksRun) return false
        if (warnings != other.warnings) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + status.hashCode()
        __r = __r*31 + checksRun.hashCode()
        __r = __r*31 + warnings.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdMutationVerification (")
        printer.indent {
            print("status = "); status.print(printer); println()
            print("checksRun = "); checksRun.print(printer); println()
            print("warnings = "); warnings.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:102]
 */
data class RdReferenceInfo (
    val filePath: String,
    val line: Int,
    val column: Int,
    val context: String,
    val kind: String,
    val astPath: List<String>
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdReferenceInfo> {
        override val _type: KClass<RdReferenceInfo> = RdReferenceInfo::class
        override val id: RdId get() = RdId(-3032379611242238732)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdReferenceInfo  {
            val filePath = buffer.readString()
            val line = buffer.readInt()
            val column = buffer.readInt()
            val context = buffer.readString()
            val kind = buffer.readString()
            val astPath = buffer.readList { buffer.readString() }
            return RdReferenceInfo(filePath, line, column, context, kind, astPath)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdReferenceInfo)  {
            buffer.writeString(value.filePath)
            buffer.writeInt(value.line)
            buffer.writeInt(value.column)
            buffer.writeString(value.context)
            buffer.writeString(value.kind)
            buffer.writeList(value.astPath) { v -> buffer.writeString(v) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdReferenceInfo
        
        if (filePath != other.filePath) return false
        if (line != other.line) return false
        if (column != other.column) return false
        if (context != other.context) return false
        if (kind != other.kind) return false
        if (astPath != other.astPath) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + filePath.hashCode()
        __r = __r*31 + line.hashCode()
        __r = __r*31 + column.hashCode()
        __r = __r*31 + context.hashCode()
        __r = __r*31 + kind.hashCode()
        __r = __r*31 + astPath.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdReferenceInfo (")
        printer.indent {
            print("filePath = "); filePath.print(printer); println()
            print("line = "); line.print(printer); println()
            print("column = "); column.print(printer); println()
            print("context = "); context.print(printer); println()
            print("kind = "); kind.print(printer); println()
            print("astPath = "); astPath.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:242]
 */
data class RdRenameFileRequest (
    val filePath: String,
    val newName: String
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdRenameFileRequest> {
        override val _type: KClass<RdRenameFileRequest> = RdRenameFileRequest::class
        override val id: RdId get() = RdId(3365879169746377776)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdRenameFileRequest  {
            val filePath = buffer.readString()
            val newName = buffer.readString()
            return RdRenameFileRequest(filePath, newName)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdRenameFileRequest)  {
            buffer.writeString(value.filePath)
            buffer.writeString(value.newName)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdRenameFileRequest
        
        if (filePath != other.filePath) return false
        if (newName != other.newName) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + filePath.hashCode()
        __r = __r*31 + newName.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdRenameFileRequest (")
        printer.indent {
            print("filePath = "); filePath.print(printer); println()
            print("newName = "); newName.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:247]
 */
data class RdRenameFileResult (
    val success: Boolean,
    val oldPath: String,
    val newPath: String,
    val affectedFiles: List<String>,
    val changesCount: Int,
    val message: String,
    val status: String,
    val verification: RdMutationVerification?
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdRenameFileResult> {
        override val _type: KClass<RdRenameFileResult> = RdRenameFileResult::class
        override val id: RdId get() = RdId(3678914310064694876)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdRenameFileResult  {
            val success = buffer.readBool()
            val oldPath = buffer.readString()
            val newPath = buffer.readString()
            val affectedFiles = buffer.readList { buffer.readString() }
            val changesCount = buffer.readInt()
            val message = buffer.readString()
            val status = buffer.readString()
            val verification = buffer.readNullable { RdMutationVerification.read(ctx, buffer) }
            return RdRenameFileResult(success, oldPath, newPath, affectedFiles, changesCount, message, status, verification)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdRenameFileResult)  {
            buffer.writeBool(value.success)
            buffer.writeString(value.oldPath)
            buffer.writeString(value.newPath)
            buffer.writeList(value.affectedFiles) { v -> buffer.writeString(v) }
            buffer.writeInt(value.changesCount)
            buffer.writeString(value.message)
            buffer.writeString(value.status)
            buffer.writeNullable(value.verification) { RdMutationVerification.write(ctx, buffer, it) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdRenameFileResult
        
        if (success != other.success) return false
        if (oldPath != other.oldPath) return false
        if (newPath != other.newPath) return false
        if (affectedFiles != other.affectedFiles) return false
        if (changesCount != other.changesCount) return false
        if (message != other.message) return false
        if (status != other.status) return false
        if (verification != other.verification) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + success.hashCode()
        __r = __r*31 + oldPath.hashCode()
        __r = __r*31 + newPath.hashCode()
        __r = __r*31 + affectedFiles.hashCode()
        __r = __r*31 + changesCount.hashCode()
        __r = __r*31 + message.hashCode()
        __r = __r*31 + status.hashCode()
        __r = __r*31 + if (verification != null) verification.hashCode() else 0
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdRenameFileResult (")
        printer.indent {
            print("success = "); success.print(printer); println()
            print("oldPath = "); oldPath.print(printer); println()
            print("newPath = "); newPath.print(printer); println()
            print("affectedFiles = "); affectedFiles.print(printer); println()
            print("changesCount = "); changesCount.print(printer); println()
            print("message = "); message.print(printer); println()
            print("status = "); status.print(printer); println()
            print("verification = "); verification.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:220]
 */
data class RdRenameSymbolRequest (
    val position: RdSourcePosition,
    val newName: String
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdRenameSymbolRequest> {
        override val _type: KClass<RdRenameSymbolRequest> = RdRenameSymbolRequest::class
        override val id: RdId get() = RdId(-1370157819129083564)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdRenameSymbolRequest  {
            val position = RdSourcePosition.read(ctx, buffer)
            val newName = buffer.readString()
            return RdRenameSymbolRequest(position, newName)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdRenameSymbolRequest)  {
            RdSourcePosition.write(ctx, buffer, value.position)
            buffer.writeString(value.newName)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdRenameSymbolRequest
        
        if (position != other.position) return false
        if (newName != other.newName) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + position.hashCode()
        __r = __r*31 + newName.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdRenameSymbolRequest (")
        printer.indent {
            print("position = "); position.print(printer); println()
            print("newName = "); newName.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:231]
 */
data class RdRenameSymbolResult (
    val success: Boolean,
    val oldName: String,
    val newName: String,
    val affectedFiles: List<String>,
    val changesCount: Int,
    val message: String,
    val status: String,
    val verification: RdMutationVerification?
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdRenameSymbolResult> {
        override val _type: KClass<RdRenameSymbolResult> = RdRenameSymbolResult::class
        override val id: RdId get() = RdId(-5994761243749120840)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdRenameSymbolResult  {
            val success = buffer.readBool()
            val oldName = buffer.readString()
            val newName = buffer.readString()
            val affectedFiles = buffer.readList { buffer.readString() }
            val changesCount = buffer.readInt()
            val message = buffer.readString()
            val status = buffer.readString()
            val verification = buffer.readNullable { RdMutationVerification.read(ctx, buffer) }
            return RdRenameSymbolResult(success, oldName, newName, affectedFiles, changesCount, message, status, verification)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdRenameSymbolResult)  {
            buffer.writeBool(value.success)
            buffer.writeString(value.oldName)
            buffer.writeString(value.newName)
            buffer.writeList(value.affectedFiles) { v -> buffer.writeString(v) }
            buffer.writeInt(value.changesCount)
            buffer.writeString(value.message)
            buffer.writeString(value.status)
            buffer.writeNullable(value.verification) { RdMutationVerification.write(ctx, buffer, it) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdRenameSymbolResult
        
        if (success != other.success) return false
        if (oldName != other.oldName) return false
        if (newName != other.newName) return false
        if (affectedFiles != other.affectedFiles) return false
        if (changesCount != other.changesCount) return false
        if (message != other.message) return false
        if (status != other.status) return false
        if (verification != other.verification) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + success.hashCode()
        __r = __r*31 + oldName.hashCode()
        __r = __r*31 + newName.hashCode()
        __r = __r*31 + affectedFiles.hashCode()
        __r = __r*31 + changesCount.hashCode()
        __r = __r*31 + message.hashCode()
        __r = __r*31 + status.hashCode()
        __r = __r*31 + if (verification != null) verification.hashCode() else 0
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdRenameSymbolResult (")
        printer.indent {
            print("success = "); success.print(printer); println()
            print("oldName = "); oldName.print(printer); println()
            print("newName = "); newName.print(printer); println()
            print("affectedFiles = "); affectedFiles.print(printer); println()
            print("changesCount = "); changesCount.print(printer); println()
            print("message = "); message.print(printer); println()
            print("status = "); status.print(printer); println()
            print("verification = "); verification.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:127]
 */
data class RdResolveSymbolIndexedRequest (
    val language: String,
    val symbol: String
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdResolveSymbolIndexedRequest> {
        override val _type: KClass<RdResolveSymbolIndexedRequest> = RdResolveSymbolIndexedRequest::class
        override val id: RdId get() = RdId(-7597324494782965955)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdResolveSymbolIndexedRequest  {
            val language = buffer.readString()
            val symbol = buffer.readString()
            return RdResolveSymbolIndexedRequest(language, symbol)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdResolveSymbolIndexedRequest)  {
            buffer.writeString(value.language)
            buffer.writeString(value.symbol)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdResolveSymbolIndexedRequest
        
        if (language != other.language) return false
        if (symbol != other.symbol) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + language.hashCode()
        __r = __r*31 + symbol.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdResolveSymbolIndexedRequest (")
        printer.indent {
            print("language = "); language.print(printer); println()
            print("symbol = "); symbol.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:132]
 */
data class RdResolveSymbolIndexedResult (
    val status: String,
    val message: String?,
    val symbolInfo: RdSymbolInfo?
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdResolveSymbolIndexedResult> {
        override val _type: KClass<RdResolveSymbolIndexedResult> = RdResolveSymbolIndexedResult::class
        override val id: RdId get() = RdId(-5005525067240565329)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdResolveSymbolIndexedResult  {
            val status = buffer.readString()
            val message = buffer.readNullable { buffer.readString() }
            val symbolInfo = buffer.readNullable { RdSymbolInfo.read(ctx, buffer) }
            return RdResolveSymbolIndexedResult(status, message, symbolInfo)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdResolveSymbolIndexedResult)  {
            buffer.writeString(value.status)
            buffer.writeNullable(value.message) { buffer.writeString(it) }
            buffer.writeNullable(value.symbolInfo) { RdSymbolInfo.write(ctx, buffer, it) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdResolveSymbolIndexedResult
        
        if (status != other.status) return false
        if (message != other.message) return false
        if (symbolInfo != other.symbolInfo) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + status.hashCode()
        __r = __r*31 + if (message != null) message.hashCode() else 0
        __r = __r*31 + if (symbolInfo != null) symbolInfo.hashCode() else 0
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdResolveSymbolIndexedResult (")
        printer.indent {
            print("status = "); status.print(printer); println()
            print("message = "); message.print(printer); println()
            print("symbolInfo = "); symbolInfo.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:122]
 */
data class RdResolveSymbolRequest (
    val language: String,
    val symbol: String
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdResolveSymbolRequest> {
        override val _type: KClass<RdResolveSymbolRequest> = RdResolveSymbolRequest::class
        override val id: RdId get() = RdId(8323260431033600144)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdResolveSymbolRequest  {
            val language = buffer.readString()
            val symbol = buffer.readString()
            return RdResolveSymbolRequest(language, symbol)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdResolveSymbolRequest)  {
            buffer.writeString(value.language)
            buffer.writeString(value.symbol)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdResolveSymbolRequest
        
        if (language != other.language) return false
        if (symbol != other.symbol) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + language.hashCode()
        __r = __r*31 + symbol.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdResolveSymbolRequest (")
        printer.indent {
            print("language = "); language.print(printer); println()
            print("symbol = "); symbol.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:280]
 */
data class RdSafeDeleteBlockedUsage (
    val filePath: String,
    val line: Int,
    val column: Int,
    val context: String,
    val kind: String
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdSafeDeleteBlockedUsage> {
        override val _type: KClass<RdSafeDeleteBlockedUsage> = RdSafeDeleteBlockedUsage::class
        override val id: RdId get() = RdId(6616599642070861522)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdSafeDeleteBlockedUsage  {
            val filePath = buffer.readString()
            val line = buffer.readInt()
            val column = buffer.readInt()
            val context = buffer.readString()
            val kind = buffer.readString()
            return RdSafeDeleteBlockedUsage(filePath, line, column, context, kind)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdSafeDeleteBlockedUsage)  {
            buffer.writeString(value.filePath)
            buffer.writeInt(value.line)
            buffer.writeInt(value.column)
            buffer.writeString(value.context)
            buffer.writeString(value.kind)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdSafeDeleteBlockedUsage
        
        if (filePath != other.filePath) return false
        if (line != other.line) return false
        if (column != other.column) return false
        if (context != other.context) return false
        if (kind != other.kind) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + filePath.hashCode()
        __r = __r*31 + line.hashCode()
        __r = __r*31 + column.hashCode()
        __r = __r*31 + context.hashCode()
        __r = __r*31 + kind.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdSafeDeleteBlockedUsage (")
        printer.indent {
            print("filePath = "); filePath.print(printer); println()
            print("line = "); line.print(printer); println()
            print("column = "); column.print(printer); println()
            print("context = "); context.print(printer); println()
            print("kind = "); kind.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:274]
 */
data class RdSafeDeleteRequest (
    val target: RdSemanticTarget,
    val targetType: String,
    val force: Boolean
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdSafeDeleteRequest> {
        override val _type: KClass<RdSafeDeleteRequest> = RdSafeDeleteRequest::class
        override val id: RdId get() = RdId(-1384896468765046606)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdSafeDeleteRequest  {
            val target = RdSemanticTarget.read(ctx, buffer)
            val targetType = buffer.readString()
            val force = buffer.readBool()
            return RdSafeDeleteRequest(target, targetType, force)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdSafeDeleteRequest)  {
            RdSemanticTarget.write(ctx, buffer, value.target)
            buffer.writeString(value.targetType)
            buffer.writeBool(value.force)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdSafeDeleteRequest
        
        if (target != other.target) return false
        if (targetType != other.targetType) return false
        if (force != other.force) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + target.hashCode()
        __r = __r*31 + targetType.hashCode()
        __r = __r*31 + force.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdSafeDeleteRequest (")
        printer.indent {
            print("target = "); target.print(printer); println()
            print("targetType = "); targetType.print(printer); println()
            print("force = "); force.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:288]
 */
data class RdSafeDeleteResult (
    val success: Boolean,
    val affectedFiles: List<String>,
    val changesCount: Int,
    val message: String,
    val status: String,
    val blockedUsages: List<RdSafeDeleteBlockedUsage>,
    val verification: RdMutationVerification?
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdSafeDeleteResult> {
        override val _type: KClass<RdSafeDeleteResult> = RdSafeDeleteResult::class
        override val id: RdId get() = RdId(-6590292944502201958)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdSafeDeleteResult  {
            val success = buffer.readBool()
            val affectedFiles = buffer.readList { buffer.readString() }
            val changesCount = buffer.readInt()
            val message = buffer.readString()
            val status = buffer.readString()
            val blockedUsages = buffer.readList { RdSafeDeleteBlockedUsage.read(ctx, buffer) }
            val verification = buffer.readNullable { RdMutationVerification.read(ctx, buffer) }
            return RdSafeDeleteResult(success, affectedFiles, changesCount, message, status, blockedUsages, verification)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdSafeDeleteResult)  {
            buffer.writeBool(value.success)
            buffer.writeList(value.affectedFiles) { v -> buffer.writeString(v) }
            buffer.writeInt(value.changesCount)
            buffer.writeString(value.message)
            buffer.writeString(value.status)
            buffer.writeList(value.blockedUsages) { v -> RdSafeDeleteBlockedUsage.write(ctx, buffer, v) }
            buffer.writeNullable(value.verification) { RdMutationVerification.write(ctx, buffer, it) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdSafeDeleteResult
        
        if (success != other.success) return false
        if (affectedFiles != other.affectedFiles) return false
        if (changesCount != other.changesCount) return false
        if (message != other.message) return false
        if (status != other.status) return false
        if (blockedUsages != other.blockedUsages) return false
        if (verification != other.verification) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + success.hashCode()
        __r = __r*31 + affectedFiles.hashCode()
        __r = __r*31 + changesCount.hashCode()
        __r = __r*31 + message.hashCode()
        __r = __r*31 + status.hashCode()
        __r = __r*31 + blockedUsages.hashCode()
        __r = __r*31 + if (verification != null) verification.hashCode() else 0
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdSafeDeleteResult (")
        printer.indent {
            print("success = "); success.print(printer); println()
            print("affectedFiles = "); affectedFiles.print(printer); println()
            print("changesCount = "); changesCount.print(printer); println()
            print("message = "); message.print(printer); println()
            print("status = "); status.print(printer); println()
            print("blockedUsages = "); blockedUsages.print(printer); println()
            print("verification = "); verification.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:32]
 */
data class RdSemanticTarget (
    val filePath: String?,
    val line: Int?,
    val column: Int?,
    val language: String?,
    val symbol: String?
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdSemanticTarget> {
        override val _type: KClass<RdSemanticTarget> = RdSemanticTarget::class
        override val id: RdId get() = RdId(4375242365750196220)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdSemanticTarget  {
            val filePath = buffer.readNullable { buffer.readString() }
            val line = buffer.readNullable { buffer.readInt() }
            val column = buffer.readNullable { buffer.readInt() }
            val language = buffer.readNullable { buffer.readString() }
            val symbol = buffer.readNullable { buffer.readString() }
            return RdSemanticTarget(filePath, line, column, language, symbol)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdSemanticTarget)  {
            buffer.writeNullable(value.filePath) { buffer.writeString(it) }
            buffer.writeNullable(value.line) { buffer.writeInt(it) }
            buffer.writeNullable(value.column) { buffer.writeInt(it) }
            buffer.writeNullable(value.language) { buffer.writeString(it) }
            buffer.writeNullable(value.symbol) { buffer.writeString(it) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdSemanticTarget
        
        if (filePath != other.filePath) return false
        if (line != other.line) return false
        if (column != other.column) return false
        if (language != other.language) return false
        if (symbol != other.symbol) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + if (filePath != null) filePath.hashCode() else 0
        __r = __r*31 + if (line != null) line.hashCode() else 0
        __r = __r*31 + if (column != null) column.hashCode() else 0
        __r = __r*31 + if (language != null) language.hashCode() else 0
        __r = __r*31 + if (symbol != null) symbol.hashCode() else 0
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdSemanticTarget (")
        printer.indent {
            print("filePath = "); filePath.print(printer); println()
            print("line = "); line.print(printer); println()
            print("column = "); column.print(printer); println()
            print("language = "); language.print(printer); println()
            print("symbol = "); symbol.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:26]
 */
data class RdSourcePosition (
    val filePath: String,
    val line: Int,
    val column: Int
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdSourcePosition> {
        override val _type: KClass<RdSourcePosition> = RdSourcePosition::class
        override val id: RdId get() = RdId(-5977976679503629463)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdSourcePosition  {
            val filePath = buffer.readString()
            val line = buffer.readInt()
            val column = buffer.readInt()
            return RdSourcePosition(filePath, line, column)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdSourcePosition)  {
            buffer.writeString(value.filePath)
            buffer.writeInt(value.line)
            buffer.writeInt(value.column)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdSourcePosition
        
        if (filePath != other.filePath) return false
        if (line != other.line) return false
        if (column != other.column) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + filePath.hashCode()
        __r = __r*31 + line.hashCode()
        __r = __r*31 + column.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdSourcePosition (")
        printer.indent {
            print("filePath = "); filePath.print(printer); println()
            print("line = "); line.print(printer); println()
            print("column = "); column.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:184]
 */
data class RdSuperMethodInfo (
    val symbol: RdSymbolInfo,
    val containingTypeName: String,
    val containingTypeKind: String,
    val isInterface: Boolean,
    val depth: Int
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdSuperMethodInfo> {
        override val _type: KClass<RdSuperMethodInfo> = RdSuperMethodInfo::class
        override val id: RdId get() = RdId(-6175457339077949787)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdSuperMethodInfo  {
            val symbol = RdSymbolInfo.read(ctx, buffer)
            val containingTypeName = buffer.readString()
            val containingTypeKind = buffer.readString()
            val isInterface = buffer.readBool()
            val depth = buffer.readInt()
            return RdSuperMethodInfo(symbol, containingTypeName, containingTypeKind, isInterface, depth)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdSuperMethodInfo)  {
            RdSymbolInfo.write(ctx, buffer, value.symbol)
            buffer.writeString(value.containingTypeName)
            buffer.writeString(value.containingTypeKind)
            buffer.writeBool(value.isInterface)
            buffer.writeInt(value.depth)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdSuperMethodInfo
        
        if (symbol != other.symbol) return false
        if (containingTypeName != other.containingTypeName) return false
        if (containingTypeKind != other.containingTypeKind) return false
        if (isInterface != other.isInterface) return false
        if (depth != other.depth) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + symbol.hashCode()
        __r = __r*31 + containingTypeName.hashCode()
        __r = __r*31 + containingTypeKind.hashCode()
        __r = __r*31 + isInterface.hashCode()
        __r = __r*31 + depth.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdSuperMethodInfo (")
        printer.indent {
            print("symbol = "); symbol.print(printer); println()
            print("containingTypeName = "); containingTypeName.print(printer); println()
            print("containingTypeKind = "); containingTypeKind.print(printer); println()
            print("isInterface = "); isInterface.print(printer); println()
            print("depth = "); depth.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:180]
 */
data class RdSuperMethodsRequest (
    val position: RdSourcePosition
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdSuperMethodsRequest> {
        override val _type: KClass<RdSuperMethodsRequest> = RdSuperMethodsRequest::class
        override val id: RdId get() = RdId(-3118716768245179149)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdSuperMethodsRequest  {
            val position = RdSourcePosition.read(ctx, buffer)
            return RdSuperMethodsRequest(position)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdSuperMethodsRequest)  {
            RdSourcePosition.write(ctx, buffer, value.position)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdSuperMethodsRequest
        
        if (position != other.position) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + position.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdSuperMethodsRequest (")
        printer.indent {
            print("position = "); position.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:192]
 */
data class RdSuperMethodsResult (
    val method: RdSymbolInfo,
    val hierarchy: List<RdSuperMethodInfo>
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdSuperMethodsResult> {
        override val _type: KClass<RdSuperMethodsResult> = RdSuperMethodsResult::class
        override val id: RdId get() = RdId(-3670941329370988231)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdSuperMethodsResult  {
            val method = RdSymbolInfo.read(ctx, buffer)
            val hierarchy = buffer.readList { RdSuperMethodInfo.read(ctx, buffer) }
            return RdSuperMethodsResult(method, hierarchy)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdSuperMethodsResult)  {
            RdSymbolInfo.write(ctx, buffer, value.method)
            buffer.writeList(value.hierarchy) { v -> RdSuperMethodInfo.write(ctx, buffer, v) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdSuperMethodsResult
        
        if (method != other.method) return false
        if (hierarchy != other.hierarchy) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + method.hashCode()
        __r = __r*31 + hierarchy.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdSuperMethodsResult (")
        printer.indent {
            print("method = "); method.print(printer); println()
            print("hierarchy = "); hierarchy.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:40]
 */
data class RdSymbolInfo (
    val name: String,
    val qualifiedName: String,
    val kind: String,
    val filePath: String?,
    val line: Int?,
    val column: Int?,
    val language: String,
    val signature: String?,
    val modifiers: List<String>
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdSymbolInfo> {
        override val _type: KClass<RdSymbolInfo> = RdSymbolInfo::class
        override val id: RdId get() = RdId(-1313392469938805269)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdSymbolInfo  {
            val name = buffer.readString()
            val qualifiedName = buffer.readString()
            val kind = buffer.readString()
            val filePath = buffer.readNullable { buffer.readString() }
            val line = buffer.readNullable { buffer.readInt() }
            val column = buffer.readNullable { buffer.readInt() }
            val language = buffer.readString()
            val signature = buffer.readNullable { buffer.readString() }
            val modifiers = buffer.readList { buffer.readString() }
            return RdSymbolInfo(name, qualifiedName, kind, filePath, line, column, language, signature, modifiers)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdSymbolInfo)  {
            buffer.writeString(value.name)
            buffer.writeString(value.qualifiedName)
            buffer.writeString(value.kind)
            buffer.writeNullable(value.filePath) { buffer.writeString(it) }
            buffer.writeNullable(value.line) { buffer.writeInt(it) }
            buffer.writeNullable(value.column) { buffer.writeInt(it) }
            buffer.writeString(value.language)
            buffer.writeNullable(value.signature) { buffer.writeString(it) }
            buffer.writeList(value.modifiers) { v -> buffer.writeString(v) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdSymbolInfo
        
        if (name != other.name) return false
        if (qualifiedName != other.qualifiedName) return false
        if (kind != other.kind) return false
        if (filePath != other.filePath) return false
        if (line != other.line) return false
        if (column != other.column) return false
        if (language != other.language) return false
        if (signature != other.signature) return false
        if (modifiers != other.modifiers) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + name.hashCode()
        __r = __r*31 + qualifiedName.hashCode()
        __r = __r*31 + kind.hashCode()
        __r = __r*31 + if (filePath != null) filePath.hashCode() else 0
        __r = __r*31 + if (line != null) line.hashCode() else 0
        __r = __r*31 + if (column != null) column.hashCode() else 0
        __r = __r*31 + language.hashCode()
        __r = __r*31 + if (signature != null) signature.hashCode() else 0
        __r = __r*31 + modifiers.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdSymbolInfo (")
        printer.indent {
            print("name = "); name.print(printer); println()
            print("qualifiedName = "); qualifiedName.print(printer); println()
            print("kind = "); kind.print(printer); println()
            print("filePath = "); filePath.print(printer); println()
            print("line = "); line.print(printer); println()
            print("column = "); column.print(printer); println()
            print("language = "); language.print(printer); println()
            print("signature = "); signature.print(printer); println()
            print("modifiers = "); modifiers.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:140]
 */
data class RdTypeHierarchyRequest (
    val position: RdSourcePosition,
    val scope: String
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdTypeHierarchyRequest> {
        override val _type: KClass<RdTypeHierarchyRequest> = RdTypeHierarchyRequest::class
        override val id: RdId get() = RdId(-1227020239591611591)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdTypeHierarchyRequest  {
            val position = RdSourcePosition.read(ctx, buffer)
            val scope = buffer.readString()
            return RdTypeHierarchyRequest(position, scope)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdTypeHierarchyRequest)  {
            RdSourcePosition.write(ctx, buffer, value.position)
            buffer.writeString(value.scope)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdTypeHierarchyRequest
        
        if (position != other.position) return false
        if (scope != other.scope) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + position.hashCode()
        __r = __r*31 + scope.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdTypeHierarchyRequest (")
        printer.indent {
            print("position = "); position.print(printer); println()
            print("scope = "); scope.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:145]
 */
data class RdTypeHierarchyResult (
    val element: RdSymbolInfo,
    val supertypes: List<RdSymbolInfo>,
    val subtypes: List<RdSymbolInfo>
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdTypeHierarchyResult> {
        override val _type: KClass<RdTypeHierarchyResult> = RdTypeHierarchyResult::class
        override val id: RdId get() = RdId(4720868785486666291)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdTypeHierarchyResult  {
            val element = RdSymbolInfo.read(ctx, buffer)
            val supertypes = buffer.readList { RdSymbolInfo.read(ctx, buffer) }
            val subtypes = buffer.readList { RdSymbolInfo.read(ctx, buffer) }
            return RdTypeHierarchyResult(element, supertypes, subtypes)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdTypeHierarchyResult)  {
            RdSymbolInfo.write(ctx, buffer, value.element)
            buffer.writeList(value.supertypes) { v -> RdSymbolInfo.write(ctx, buffer, v) }
            buffer.writeList(value.subtypes) { v -> RdSymbolInfo.write(ctx, buffer, v) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdTypeHierarchyResult
        
        if (element != other.element) return false
        if (supertypes != other.supertypes) return false
        if (subtypes != other.subtypes) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + element.hashCode()
        __r = __r*31 + supertypes.hashCode()
        __r = __r*31 + subtypes.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdTypeHierarchyResult (")
        printer.indent {
            print("element = "); element.print(printer); println()
            print("supertypes = "); supertypes.print(printer); println()
            print("subtypes = "); subtypes.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}
