package model.rider

import com.jetbrains.rd.generator.nova.*
import com.jetbrains.rd.generator.nova.PredefinedType.*
import com.jetbrains.rd.generator.nova.csharp.CSharp50Generator
import com.jetbrains.rider.model.nova.ide.SolutionModel

/**
 * Rider Protocol Model for IDE Index MCP Server.
 *
 * Defines the RPC contract between the Kotlin frontend (IntelliJ Platform) and the
 * C# backend (ReSharper) for code intelligence operations on C# and F# code.
 *
 * The frontend calls these methods from MCP tool handlers; the backend implements
 * them using ReSharper's full semantic model.
 */
@Suppress("unused")
object IndexMcpModel : Ext(SolutionModel.Solution) {

    init {
        setting(CSharp50Generator.Namespace, "JetBrains.Rider.Model.IndexMcp")
    }

    // ── Common data structures ──────────────────────────────────────────────

    private val RdSourcePosition = structdef {
        field("filePath", string)
        field("line", int)
        field("column", int)
    }

    private val RdSemanticTarget = structdef {
        field("filePath", string.nullable)
        field("line", int.nullable)
        field("column", int.nullable)
        field("language", string.nullable)
        field("symbol", string.nullable)
    }

    private val RdSymbolInfo = structdef {
        field("name", string)
        field("qualifiedName", string)
        field("kind", string)
        field("filePath", string.nullable)
        field("line", int.nullable)
        field("column", int.nullable)
        field("language", string)
        field("signature", string.nullable)
        field("modifiers", immutableList(string))
    }

    // ── Backend Status ──────────────────────────────────────────────────────

    private val RdBackendStatusResult = structdef {
        field("backendVersion", string)
        field("solutionLoaded", bool)
        field("psiServicesAvailable", bool)
        field("message", string)
    }

    // ── Universal Navigation/Search ─────────────────────────────────────────

    private val RdFindTypesRequest = structdef {
        field("query", string)
        field("matchMode", string)
        field("scope", string)
        field("language", string.nullable)
        field("limit", int)
    }

    private val RdFindTypesResult = structdef {
        field("types", immutableList(RdSymbolInfo))
        field("totalCount", int)
    }

    private val RdFindDefinitionRequest = structdef {
        field("target", RdSemanticTarget)
        field("fullElementPreview", bool)
        field("maxPreviewLines", int)
    }

    private val RdDefinitionResult = structdef {
        field("definition", RdSymbolInfo)
        field("preview", string)
        field("astPath", immutableList(string))
    }

    private val RdReferenceInfo = structdef {
        field("filePath", string)
        field("line", int)
        field("column", int)
        field("context", string)
        field("kind", string)
        field("astPath", immutableList(string))
    }

    private val RdFindReferencesRequest = structdef {
        field("target", RdSemanticTarget)
        field("scope", string)
        field("limit", int)
    }

    private val RdFindReferencesResult = structdef {
        field("references", immutableList(RdReferenceInfo))
        field("totalCount", int)
    }

    private val RdResolveSymbolRequest = structdef {
        field("language", string)
        field("symbol", string)
    }

    // ── Type Hierarchy ──────────────────────────────────────────────────────

    private val RdTypeHierarchyRequest = structdef {
        field("position", RdSourcePosition)
        field("scope", string)
    }

    private val RdTypeHierarchyResult = structdef {
        field("element", RdSymbolInfo)
        field("supertypes", immutableList(RdSymbolInfo))
        field("subtypes", immutableList(RdSymbolInfo))
    }

    // ── Find Implementations ────────────────────────────────────────────────

    private val RdImplementationsRequest = structdef {
        field("position", RdSourcePosition)
        field("scope", string)
    }

    private val RdImplementationsResult = structdef {
        field("implementations", immutableList(RdSymbolInfo))
    }

    // ── Call Hierarchy ──────────────────────────────────────────────────────

    private val RdCallHierarchyRequest = structdef {
        field("position", RdSourcePosition)
        field("direction", string)
        field("depth", int)
        field("scope", string)
    }

    private val RdCallHierarchyResult = structdef {
        field("root", RdSymbolInfo)
        field("calls", immutableList(RdSymbolInfo))
    }

    // ── Super Methods ───────────────────────────────────────────────────────

    private val RdSuperMethodsRequest = structdef {
        field("position", RdSourcePosition)
    }

    private val RdSuperMethodInfo = structdef {
        field("symbol", RdSymbolInfo)
        field("containingTypeName", string)
        field("containingTypeKind", string)
        field("isInterface", bool)
        field("depth", int)
    }

    private val RdSuperMethodsResult = structdef {
        field("method", RdSymbolInfo)
        field("hierarchy", immutableList(RdSuperMethodInfo))
    }

    // ── File Structure ──────────────────────────────────────────────────────

    private val RdFileStructureRequest = structdef {
        field("filePath", string)
    }

    // Flattened structure node (rd doesn't support recursive structdefs).
    // The frontend reconstructs the tree using the depth field.
    private val RdFlatStructureNode = structdef {
        field("name", string)
        field("kind", string)
        field("signature", string.nullable)
        field("modifiers", immutableList(string))
        field("line", int)
        field("depth", int)
    }

    private val RdFileStructureResult = structdef {
        field("nodes", immutableList(RdFlatStructureNode))
    }

    // ── Rename Symbol ───────────────────────────────────────────────────────

    private val RdRenameSymbolRequest = structdef {
        field("position", RdSourcePosition)
        field("newName", string)
    }

    private val RdRenameSymbolResult = structdef {
        field("success", bool)
        field("oldName", string)
        field("newName", string)
        field("affectedFiles", immutableList(string))
        field("changesCount", int)
        field("message", string)
    }

    // ── RPC Calls (frontend → backend) ──────────────────────────────────────

    init {
        call("getBackendStatus", void, RdBackendStatusResult)
        call("findTypes", RdFindTypesRequest, RdFindTypesResult)
        call("findDefinition", RdFindDefinitionRequest, RdDefinitionResult.nullable)
        call("findReferences", RdFindReferencesRequest, RdFindReferencesResult)
        call("resolveSymbol", RdResolveSymbolRequest, RdSymbolInfo.nullable)
        call("getTypeHierarchy", RdTypeHierarchyRequest, RdTypeHierarchyResult.nullable)
        call("findImplementations", RdImplementationsRequest, RdImplementationsResult.nullable)
        call("getCallHierarchy", RdCallHierarchyRequest, RdCallHierarchyResult.nullable)
        call("findSuperMethods", RdSuperMethodsRequest, RdSuperMethodsResult.nullable)
        call("getFileStructure", RdFileStructureRequest, RdFileStructureResult.nullable)
        call("renameSymbol", RdRenameSymbolRequest, RdRenameSymbolResult.nullable)
    }
}
