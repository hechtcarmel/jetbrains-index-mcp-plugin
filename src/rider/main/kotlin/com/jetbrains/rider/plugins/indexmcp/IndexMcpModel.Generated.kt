@file:Suppress("EXPERIMENTAL_API_USAGE","EXPERIMENTAL_UNSIGNED_LITERALS","PackageDirectoryMismatch","UnusedImport","unused","LocalVariableName","CanBeVal","PropertyName","EnumEntryName","ClassName","ObjectPropertyName","UnnecessaryVariable","SpellCheckingInspection")
package com.jetbrains.rider.plugins.indexmcp.model

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
 * #### Generated from [IndexMcpModel.kt:19]
 */
class IndexMcpModel private constructor(
    private val _getBackendStatus: RdCall<Unit, RdBackendStatusResult>,
    private val _findTypes: RdCall<RdFindTypesRequest, RdFindTypesResult>,
    private val _findDefinition: RdCall<RdFindDefinitionRequest, RdDefinitionResult?>,
    private val _findReferences: RdCall<RdFindReferencesRequest, RdFindReferencesResult>,
    private val _resolveSymbol: RdCall<RdResolveSymbolRequest, RdSymbolInfo?>,
    private val _getTypeHierarchy: RdCall<RdTypeHierarchyRequest, RdTypeHierarchyResult?>,
    private val _findImplementations: RdCall<RdImplementationsRequest, RdImplementationsResult?>,
    private val _getCallHierarchy: RdCall<RdCallHierarchyRequest, RdCallHierarchyResult?>,
    private val _findSuperMethods: RdCall<RdSuperMethodsRequest, RdSuperMethodsResult?>,
    private val _getFileStructure: RdCall<RdFileStructureRequest, RdFileStructureResult?>,
    private val _renameSymbol: RdCall<RdRenameSymbolRequest, RdRenameSymbolResult?>
) : RdExtBase() {
    //companion
    
    companion object : ISerializersOwner {
        
        override fun registerSerializersCore(serializers: ISerializers)  {
            val classLoader = javaClass.classLoader
            serializers.register(LazyCompanionMarshaller(RdId(-5977976679503629463), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdSourcePosition"))
            serializers.register(LazyCompanionMarshaller(RdId(4375242365750196220), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdSemanticTarget"))
            serializers.register(LazyCompanionMarshaller(RdId(-1313392469938805269), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdSymbolInfo"))
            serializers.register(LazyCompanionMarshaller(RdId(5994498188749596030), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdBackendStatusResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-4108457705209229324), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdFindTypesRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(4627919189821581848), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdFindTypesResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-869352042608296866), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdFindDefinitionRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(3534513108899072789), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdDefinitionResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-3032379611242238732), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdReferenceInfo"))
            serializers.register(LazyCompanionMarshaller(RdId(7995059252749119881), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdFindReferencesRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(7993636522934682083), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdFindReferencesResult"))
            serializers.register(LazyCompanionMarshaller(RdId(8323260431033600144), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdResolveSymbolRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(-1227020239591611591), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdTypeHierarchyRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(4720868785486666291), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdTypeHierarchyResult"))
            serializers.register(LazyCompanionMarshaller(RdId(4312643375883653203), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdImplementationsRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(3709455090907832793), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdImplementationsResult"))
            serializers.register(LazyCompanionMarshaller(RdId(1059166656247531101), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdCallHierarchyRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(-5321339677649567089), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdCallHierarchyResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-3118716768245179149), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdSuperMethodsRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(-6175457339077949787), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdSuperMethodInfo"))
            serializers.register(LazyCompanionMarshaller(RdId(-3670941329370988231), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdSuperMethodsResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-6049031805937391491), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdFileStructureRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(-994497033408018441), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdFlatStructureNode"))
            serializers.register(LazyCompanionMarshaller(RdId(1590038723070745711), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdFileStructureResult"))
            serializers.register(LazyCompanionMarshaller(RdId(-1370157819129083564), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdRenameSymbolRequest"))
            serializers.register(LazyCompanionMarshaller(RdId(-5994761243749120840), classLoader, "com.jetbrains.rider.plugins.indexmcp.model.RdRenameSymbolResult"))
        }
        
        
        @JvmStatic
        @JvmName("internalCreateModel")
        @Deprecated("Use create instead", ReplaceWith("create(lifetime, protocol)"))
        internal fun createModel(lifetime: Lifetime, protocol: IProtocol): IndexMcpModel  {
            @Suppress("DEPRECATION")
            return create(lifetime, protocol)
        }
        
        @JvmStatic
        @Deprecated("Use protocol.indexMcpModel or revise the extension scope instead", ReplaceWith("protocol.indexMcpModel"))
        fun create(lifetime: Lifetime, protocol: IProtocol): IndexMcpModel  {
            return IndexMcpModel()
        }
        
        private val __RdDefinitionResultNullableSerializer = RdDefinitionResult.nullable()
        private val __RdSymbolInfoNullableSerializer = RdSymbolInfo.nullable()
        private val __RdTypeHierarchyResultNullableSerializer = RdTypeHierarchyResult.nullable()
        private val __RdImplementationsResultNullableSerializer = RdImplementationsResult.nullable()
        private val __RdCallHierarchyResultNullableSerializer = RdCallHierarchyResult.nullable()
        private val __RdSuperMethodsResultNullableSerializer = RdSuperMethodsResult.nullable()
        private val __RdFileStructureResultNullableSerializer = RdFileStructureResult.nullable()
        private val __RdRenameSymbolResultNullableSerializer = RdRenameSymbolResult.nullable()
        
        const val serializationHash = 1737773450648360157L
        
    }
    override val serializersOwner: ISerializersOwner get() = IndexMcpModel
    override val serializationHash: Long get() = IndexMcpModel.serializationHash
    
    //fields
    val getBackendStatus: IRdCall<Unit, RdBackendStatusResult> get() = _getBackendStatus
    val findTypes: IRdCall<RdFindTypesRequest, RdFindTypesResult> get() = _findTypes
    val findDefinition: IRdCall<RdFindDefinitionRequest, RdDefinitionResult?> get() = _findDefinition
    val findReferences: IRdCall<RdFindReferencesRequest, RdFindReferencesResult> get() = _findReferences
    val resolveSymbol: IRdCall<RdResolveSymbolRequest, RdSymbolInfo?> get() = _resolveSymbol
    val getTypeHierarchy: IRdCall<RdTypeHierarchyRequest, RdTypeHierarchyResult?> get() = _getTypeHierarchy
    val findImplementations: IRdCall<RdImplementationsRequest, RdImplementationsResult?> get() = _findImplementations
    val getCallHierarchy: IRdCall<RdCallHierarchyRequest, RdCallHierarchyResult?> get() = _getCallHierarchy
    val findSuperMethods: IRdCall<RdSuperMethodsRequest, RdSuperMethodsResult?> get() = _findSuperMethods
    val getFileStructure: IRdCall<RdFileStructureRequest, RdFileStructureResult?> get() = _getFileStructure
    val renameSymbol: IRdCall<RdRenameSymbolRequest, RdRenameSymbolResult?> get() = _renameSymbol
    //methods
    //initializer
    init {
        bindableChildren.add("getBackendStatus" to _getBackendStatus)
        bindableChildren.add("findTypes" to _findTypes)
        bindableChildren.add("findDefinition" to _findDefinition)
        bindableChildren.add("findReferences" to _findReferences)
        bindableChildren.add("resolveSymbol" to _resolveSymbol)
        bindableChildren.add("getTypeHierarchy" to _getTypeHierarchy)
        bindableChildren.add("findImplementations" to _findImplementations)
        bindableChildren.add("getCallHierarchy" to _getCallHierarchy)
        bindableChildren.add("findSuperMethods" to _findSuperMethods)
        bindableChildren.add("getFileStructure" to _getFileStructure)
        bindableChildren.add("renameSymbol" to _renameSymbol)
    }
    
    //secondary constructor
    private constructor(
    ) : this(
        RdCall<Unit, RdBackendStatusResult>(FrameworkMarshallers.Void, RdBackendStatusResult),
        RdCall<RdFindTypesRequest, RdFindTypesResult>(RdFindTypesRequest, RdFindTypesResult),
        RdCall<RdFindDefinitionRequest, RdDefinitionResult?>(RdFindDefinitionRequest, __RdDefinitionResultNullableSerializer),
        RdCall<RdFindReferencesRequest, RdFindReferencesResult>(RdFindReferencesRequest, RdFindReferencesResult),
        RdCall<RdResolveSymbolRequest, RdSymbolInfo?>(RdResolveSymbolRequest, __RdSymbolInfoNullableSerializer),
        RdCall<RdTypeHierarchyRequest, RdTypeHierarchyResult?>(RdTypeHierarchyRequest, __RdTypeHierarchyResultNullableSerializer),
        RdCall<RdImplementationsRequest, RdImplementationsResult?>(RdImplementationsRequest, __RdImplementationsResultNullableSerializer),
        RdCall<RdCallHierarchyRequest, RdCallHierarchyResult?>(RdCallHierarchyRequest, __RdCallHierarchyResultNullableSerializer),
        RdCall<RdSuperMethodsRequest, RdSuperMethodsResult?>(RdSuperMethodsRequest, __RdSuperMethodsResultNullableSerializer),
        RdCall<RdFileStructureRequest, RdFileStructureResult?>(RdFileStructureRequest, __RdFileStructureResultNullableSerializer),
        RdCall<RdRenameSymbolRequest, RdRenameSymbolResult?>(RdRenameSymbolRequest, __RdRenameSymbolResultNullableSerializer)
    )
    
    //equals trait
    //hash code trait
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("IndexMcpModel (")
        printer.indent {
            print("getBackendStatus = "); _getBackendStatus.print(printer); println()
            print("findTypes = "); _findTypes.print(printer); println()
            print("findDefinition = "); _findDefinition.print(printer); println()
            print("findReferences = "); _findReferences.print(printer); println()
            print("resolveSymbol = "); _resolveSymbol.print(printer); println()
            print("getTypeHierarchy = "); _getTypeHierarchy.print(printer); println()
            print("findImplementations = "); _findImplementations.print(printer); println()
            print("getCallHierarchy = "); _getCallHierarchy.print(printer); println()
            print("findSuperMethods = "); _findSuperMethods.print(printer); println()
            print("getFileStructure = "); _getFileStructure.print(printer); println()
            print("renameSymbol = "); _renameSymbol.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    override fun deepClone(): IndexMcpModel   {
        return IndexMcpModel(
            _getBackendStatus.deepClonePolymorphic(),
            _findTypes.deepClonePolymorphic(),
            _findDefinition.deepClonePolymorphic(),
            _findReferences.deepClonePolymorphic(),
            _resolveSymbol.deepClonePolymorphic(),
            _getTypeHierarchy.deepClonePolymorphic(),
            _findImplementations.deepClonePolymorphic(),
            _getCallHierarchy.deepClonePolymorphic(),
            _findSuperMethods.deepClonePolymorphic(),
            _getFileStructure.deepClonePolymorphic(),
            _renameSymbol.deepClonePolymorphic()
        )
    }
    //contexts
    //threading
    override val extThreading: ExtThreadingKind get() = ExtThreadingKind.Default
}
val IProtocol.indexMcpModel get() = getOrCreateExtension(IndexMcpModel::class) { @Suppress("DEPRECATION") IndexMcpModel.create(lifetime, this) }



/**
 * #### Generated from [IndexMcpModel.kt:56]
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
 * #### Generated from [IndexMcpModel.kt:141]
 */
data class RdCallHierarchyRequest (
    val position: RdSourcePosition,
    val direction: String,
    val depth: Int,
    val scope: String
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdCallHierarchyRequest> {
        override val _type: KClass<RdCallHierarchyRequest> = RdCallHierarchyRequest::class
        override val id: RdId get() = RdId(1059166656247531101)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdCallHierarchyRequest  {
            val position = RdSourcePosition.read(ctx, buffer)
            val direction = buffer.readString()
            val depth = buffer.readInt()
            val scope = buffer.readString()
            return RdCallHierarchyRequest(position, direction, depth, scope)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdCallHierarchyRequest)  {
            RdSourcePosition.write(ctx, buffer, value.position)
            buffer.writeString(value.direction)
            buffer.writeInt(value.depth)
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
        
        other as RdCallHierarchyRequest
        
        if (position != other.position) return false
        if (direction != other.direction) return false
        if (depth != other.depth) return false
        if (scope != other.scope) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + position.hashCode()
        __r = __r*31 + direction.hashCode()
        __r = __r*31 + depth.hashCode()
        __r = __r*31 + scope.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdCallHierarchyRequest (")
        printer.indent {
            print("position = "); position.print(printer); println()
            print("direction = "); direction.print(printer); println()
            print("depth = "); depth.print(printer); println()
            print("scope = "); scope.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:148]
 */
data class RdCallHierarchyResult (
    val root: RdSymbolInfo,
    val calls: List<RdSymbolInfo>
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdCallHierarchyResult> {
        override val _type: KClass<RdCallHierarchyResult> = RdCallHierarchyResult::class
        override val id: RdId get() = RdId(-5321339677649567089)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdCallHierarchyResult  {
            val root = RdSymbolInfo.read(ctx, buffer)
            val calls = buffer.readList { RdSymbolInfo.read(ctx, buffer) }
            return RdCallHierarchyResult(root, calls)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdCallHierarchyResult)  {
            RdSymbolInfo.write(ctx, buffer, value.root)
            buffer.writeList(value.calls) { v -> RdSymbolInfo.write(ctx, buffer, v) }
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
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + root.hashCode()
        __r = __r*31 + calls.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdCallHierarchyResult (")
        printer.indent {
            print("root = "); root.print(printer); println()
            print("calls = "); calls.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:84]
 */
data class RdDefinitionResult (
    val definition: RdSymbolInfo,
    val preview: String,
    val astPath: List<String>
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
            return RdDefinitionResult(definition, preview, astPath)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdDefinitionResult)  {
            RdSymbolInfo.write(ctx, buffer, value.definition)
            buffer.writeString(value.preview)
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
        
        other as RdDefinitionResult
        
        if (definition != other.definition) return false
        if (preview != other.preview) return false
        if (astPath != other.astPath) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + definition.hashCode()
        __r = __r*31 + preview.hashCode()
        __r = __r*31 + astPath.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdDefinitionResult (")
        printer.indent {
            print("definition = "); definition.print(printer); println()
            print("preview = "); preview.print(printer); println()
            print("astPath = "); astPath.print(printer); println()
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
 * #### Generated from [IndexMcpModel.kt:189]
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
 * #### Generated from [IndexMcpModel.kt:78]
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
 * #### Generated from [IndexMcpModel.kt:99]
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
 * #### Generated from [IndexMcpModel.kt:105]
 */
data class RdFindReferencesResult (
    val references: List<RdReferenceInfo>,
    val totalCount: Int
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdFindReferencesResult> {
        override val _type: KClass<RdFindReferencesResult> = RdFindReferencesResult::class
        override val id: RdId get() = RdId(7993636522934682083)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdFindReferencesResult  {
            val references = buffer.readList { RdReferenceInfo.read(ctx, buffer) }
            val totalCount = buffer.readInt()
            return RdFindReferencesResult(references, totalCount)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdFindReferencesResult)  {
            buffer.writeList(value.references) { v -> RdReferenceInfo.write(ctx, buffer, v) }
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
        
        other as RdFindReferencesResult
        
        if (references != other.references) return false
        if (totalCount != other.totalCount) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + references.hashCode()
        __r = __r*31 + totalCount.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdFindReferencesResult (")
        printer.indent {
            print("references = "); references.print(printer); println()
            print("totalCount = "); totalCount.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:65]
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
 * #### Generated from [IndexMcpModel.kt:73]
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
 * #### Generated from [IndexMcpModel.kt:180]
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
 * #### Generated from [IndexMcpModel.kt:130]
 */
data class RdImplementationsRequest (
    val position: RdSourcePosition,
    val scope: String
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdImplementationsRequest> {
        override val _type: KClass<RdImplementationsRequest> = RdImplementationsRequest::class
        override val id: RdId get() = RdId(4312643375883653203)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdImplementationsRequest  {
            val position = RdSourcePosition.read(ctx, buffer)
            val scope = buffer.readString()
            return RdImplementationsRequest(position, scope)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdImplementationsRequest)  {
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
        
        other as RdImplementationsRequest
        
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
        printer.println("RdImplementationsRequest (")
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
 * #### Generated from [IndexMcpModel.kt:135]
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
 * #### Generated from [IndexMcpModel.kt:90]
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
 * #### Generated from [IndexMcpModel.kt:195]
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
 * #### Generated from [IndexMcpModel.kt:200]
 */
data class RdRenameSymbolResult (
    val success: Boolean,
    val oldName: String,
    val newName: String,
    val affectedFiles: List<String>,
    val changesCount: Int,
    val message: String
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
            return RdRenameSymbolResult(success, oldName, newName, affectedFiles, changesCount, message)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdRenameSymbolResult)  {
            buffer.writeBool(value.success)
            buffer.writeString(value.oldName)
            buffer.writeString(value.newName)
            buffer.writeList(value.affectedFiles) { v -> buffer.writeString(v) }
            buffer.writeInt(value.changesCount)
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
        
        other as RdRenameSymbolResult
        
        if (success != other.success) return false
        if (oldName != other.oldName) return false
        if (newName != other.newName) return false
        if (affectedFiles != other.affectedFiles) return false
        if (changesCount != other.changesCount) return false
        if (message != other.message) return false
        
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
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [IndexMcpModel.kt:110]
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
 * #### Generated from [IndexMcpModel.kt:34]
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
 * #### Generated from [IndexMcpModel.kt:28]
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
 * #### Generated from [IndexMcpModel.kt:159]
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
 * #### Generated from [IndexMcpModel.kt:155]
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
 * #### Generated from [IndexMcpModel.kt:167]
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
 * #### Generated from [IndexMcpModel.kt:42]
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
 * #### Generated from [IndexMcpModel.kt:117]
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
 * #### Generated from [IndexMcpModel.kt:122]
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
