using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using JetBrains.Application.Parts;
using JetBrains.Application.Progress;
using JetBrains.Core;
using JetBrains.DocumentModel;
using JetBrains.Lifetimes;
using JetBrains.ProjectModel;
using JetBrains.ReSharper.Resources.Shell;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.CSharp;
using JetBrains.ReSharper.Psi.CSharp.Tree;
using JetBrains.ReSharper.Psi.Files;
using JetBrains.ReSharper.Psi.Modules;
using JetBrains.ReSharper.Psi.Search;
using JetBrains.ReSharper.Psi.Tree;
using JetBrains.ReSharper.Psi.Util;
using JetBrains.Rider.Model.IndexMcp;
using JetBrains.Rd.Tasks;
using JetBrains.Util;
using JetBrains.Util.dataStructures.TypedIntrinsics;

namespace ReSharperPlugin.IndexMcp;

/// <summary>
/// Main backend host for the IDE Index MCP Server protocol.
///
/// Handles all code intelligence RPC calls from the Kotlin frontend by using
/// ReSharper's full semantic model for C# and F# code.
/// </summary>
[SolutionComponent(Instantiation.DemandAnyThreadSafe)]
public class IndexMcpBackendHost
{
    private readonly ISolution _solution;
    private const string BackendVersion = "4.18.7";
    private const int MaxResults = 200;

    public IndexMcpBackendHost(ISolution solution, IndexMcpModel model, Lifetime lifetime)
    {
        _solution = solution;

        model.GetBackendStatus.SetAsync(HandleGetBackendStatus);
        model.FindTypes.SetAsync(HandleFindTypes);
        model.FindDefinition.SetAsync(HandleFindDefinition);
        model.FindReferences.SetAsync(HandleFindReferences);
        model.ResolveSymbol.SetAsync(HandleResolveSymbol);
        model.GetTypeHierarchy.SetAsync(HandleGetTypeHierarchy);
        model.FindImplementations.SetAsync(HandleFindImplementations);
        model.GetCallHierarchy.SetAsync(HandleGetCallHierarchy);
        model.FindSuperMethods.SetAsync(HandleFindSuperMethods);
        model.GetFileStructure.SetAsync(HandleGetFileStructure);
        model.RenameSymbol.SetAsync(HandleRenameSymbol);
    }

    // ── Backend Status ──────────────────────────────────────────────────────

    private Task<RdBackendStatusResult> HandleGetBackendStatus(Lifetime lt, Unit request)
    {
        return Task.FromResult(new RdBackendStatusResult(
            BackendVersion,
            true,
            _solution.GetPsiServices() != null,
            "Rider Index MCP ReSharper backend is loaded and rd endpoints are registered"));
    }

    // ── Universal Navigation/Search ─────────────────────────────────────────

    private Task<RdFindTypesResult> HandleFindTypes(Lifetime lt, RdFindTypesRequest request)
    {
        return Task.Run(() =>
        {
            var results = EnumerateDeclaredElements()
                .OfType<ITypeElement>()
                .Where(type => MatchesName(type.ShortName, request.Query, request.MatchMode) ||
                               MatchesName(type.GetClrName().FullName, request.Query, request.MatchMode))
                .Where(type => MatchesLanguage(type, request.Language))
                .Select(ToSymbolInfo)
                .GroupBy(symbol => $"{symbol.QualifiedName}:{symbol.FilePath}:{symbol.Line}")
                .Select(group => group.First())
                .Take(Math.Min(request.Limit, MaxResults))
                .ToList();

            return new RdFindTypesResult(results, results.Count);
        });
    }

    private Task<RdDefinitionResult?> HandleFindDefinition(Lifetime lt, RdFindDefinitionRequest request)
    {
        return Task.Run(() =>
        {
            var element = ResolveTarget(request.Target);
            if (element == null) return (RdDefinitionResult?)null;

            var navigationElement = element.GetDeclarations().FirstOrDefault();
            if (navigationElement == null) return (RdDefinitionResult?)null;

            var preview = BuildPreview(navigationElement, request.FullElementPreview, request.MaxPreviewLines);
            return (RdDefinitionResult?)new RdDefinitionResult(
                ToSymbolInfo(element),
                preview,
                BuildAstPath(navigationElement));
        });
    }

    private Task<RdFindReferencesResult> HandleFindReferences(Lifetime lt, RdFindReferencesRequest request)
    {
        return Task.Run(() =>
        {
            var element = ResolveTarget(request.Target);
            if (element == null) return new RdFindReferencesResult(new List<RdReferenceInfo>(), 0);

            var references = FindReferences(element)
                .Take(Math.Min(request.Limit, MaxResults))
                .Select(ToReferenceInfo)
                .Where(reference => reference != null)
                .Cast<RdReferenceInfo>()
                .GroupBy(reference => $"{reference.FilePath}:{reference.Line}:{reference.Column}")
                .Select(group => group.First())
                .ToList();

            return new RdFindReferencesResult(references, references.Count);
        });
    }

    private Task<RdSymbolInfo?> HandleResolveSymbol(Lifetime lt, RdResolveSymbolRequest request)
    {
        return Task.Run(() =>
        {
            var element = ResolveSymbol(request.Language, request.Symbol);
            return element == null ? null : ToSymbolInfo(element);
        });
    }

    // ── Rename Symbol ───────────────────────────────────────────────────────

    private Task<RdRenameSymbolResult?> HandleRenameSymbol(
        Lifetime lt, RdRenameSymbolRequest request)
    {
        return Task.Run(() =>
        {
            var element = ResolveDeclaredElementAt(request.Position);
            if (element == null)
            {
                return (RdRenameSymbolResult?)new RdRenameSymbolResult(
                    false,
                    "",
                    request.NewName,
                    new List<string>(),
                    0,
                    "No declared C#/F# symbol found at position");
            }

            var oldName = element.ShortName;
            if (oldName == request.NewName)
            {
                return (RdRenameSymbolResult?)new RdRenameSymbolResult(
                    false,
                    oldName,
                    request.NewName,
                    new List<string>(),
                    0,
                    "New name is the same as the current name");
            }

            var references = FindReferences(element);
            var affectedFiles = new HashSet<string>(
                element.GetDeclarations()
                    .Select(d => d.GetSourceFile()?.GetLocation().FullPath)
                    .Where(path => !string.IsNullOrEmpty(path))!);

            foreach (var reference in references)
            {
                var sourceFile = reference.GetTreeNode().GetSourceFile();
                var path = sourceFile?.GetLocation().FullPath;
                if (!string.IsNullOrEmpty(path)) affectedFiles.Add(path);
            }

            var changes = 0;
            WriteLockCookie.Execute(() =>
            {
                foreach (var declaration in element.GetDeclarations())
                {
                    declaration.SetName(request.NewName);
                    changes++;
                }

                foreach (var reference in references)
                {
                    if (!reference.IsValid()) continue;
                    reference.BindTo(element);
                    changes++;
                }
            });

            return (RdRenameSymbolResult?)new RdRenameSymbolResult(
                true,
                oldName,
                request.NewName,
                affectedFiles.ToList(),
                changes,
                $"Renamed '{oldName}' to '{request.NewName}' using ReSharper backend");
        });
    }

    // ── Type Hierarchy ──────────────────────────────────────────────────────

    private Task<RdTypeHierarchyResult?> HandleGetTypeHierarchy(
        Lifetime lt, RdTypeHierarchyRequest request)
    {
        return Task.Run(() =>
        {
            var typeElement = ResolveTypeElementAt(request.Position);
            if (typeElement == null) return null;

            var supertypes = typeElement.GetAllSuperTypes()
                .Select(t => t.GetTypeElement())
                .Where(te => te != null)
                .Select(te => ToSymbolInfo(te!))
                .Take(MaxResults)
                .ToList();

            var subtypes = new List<RdSymbolInfo>();
            var searchDomain = _solution.GetPsiServices().SearchDomainFactory
                .CreateSearchDomain(_solution, false);

            // Use FinderExtensions.FindInheritors with IDeclaredType + Func callback
            var declaredType = TypeFactory.CreateType(typeElement);
            _solution.GetPsiServices().Finder.FindInheritors(
                declaredType,
                declaredInheritor =>
                {
                    var subType = declaredInheritor.GetTypeElement();
                    if (subType != null)
                        subtypes.Add(ToSymbolInfo(subType));
                    return subtypes.Count < MaxResults
                        ? FindExecution.Continue
                        : FindExecution.Stop;
                },
                searchDomain,
                NoOpProgressIndicator.Instance);

            return (RdTypeHierarchyResult?)new RdTypeHierarchyResult(
                ToSymbolInfo(typeElement),
                supertypes,
                subtypes);
        });
    }

    // ── Find Implementations ────────────────────────────────────────────────

    private Task<RdImplementationsResult?> HandleFindImplementations(
        Lifetime lt, RdImplementationsRequest request)
    {
        return Task.Run(() =>
        {
            var element = ResolveDeclaredElementAt(request.Position);
            if (element == null) return null;

            var typeElement = element as ITypeElement
                ?? (element as ITypeMember)?.ContainingType;
            if (typeElement == null) return null;

            var implementations = new List<RdSymbolInfo>();
            var searchDomain = _solution.GetPsiServices().SearchDomainFactory
                .CreateSearchDomain(_solution, false);

            var declaredType = TypeFactory.CreateType(typeElement);
            _solution.GetPsiServices().Finder.FindInheritors(
                declaredType,
                declaredInheritor =>
                {
                    var implType = declaredInheritor.GetTypeElement();
                    if (implType is IClass cls && !cls.IsAbstract)
                        implementations.Add(ToSymbolInfo(implType));
                    else if (implType is IStruct)
                        implementations.Add(ToSymbolInfo(implType));
                    return implementations.Count < MaxResults
                        ? FindExecution.Continue
                        : FindExecution.Stop;
                },
                searchDomain,
                NoOpProgressIndicator.Instance);

            return (RdImplementationsResult?)new RdImplementationsResult(implementations);
        });
    }

    // ── Call Hierarchy ──────────────────────────────────────────────────────

    private Task<RdCallHierarchyResult?> HandleGetCallHierarchy(
        Lifetime lt, RdCallHierarchyRequest request)
    {
        return Task.Run(() =>
        {
            var element = ResolveDeclaredElementAt(request.Position);
            if (element is not IMethod method) return null;

            var root = ToSymbolInfo(method);
            var calls = new List<RdSymbolInfo>();

            if (request.Direction == "callers")
            {
                var searchDomain = _solution.GetPsiServices().SearchDomainFactory
                    .CreateSearchDomain(_solution, false);
                var referenceResults = new List<FindResult>();
                var consumer = new FindResultConsumer<List<FindResult>>(
                    result => new List<FindResult> { result },
                    results =>
                    {
                        foreach (var result in results)
                        {
                            referenceResults.Add(result);
                            if (referenceResults.Count >= MaxResults)
                                return FindExecution.Stop;
                        }
                        return FindExecution.Continue;
                    });
                _solution.GetPsiServices().Finder.FindReferences(
                    method,
                    searchDomain,
                    consumer,
                    NoOpProgressIndicator.Instance,
                    false);

                var seen = new HashSet<string>();
                foreach (var result in referenceResults)
                {
                    if (result is not FindResultReference referenceResult) continue;
                    var containingMethod = referenceResult.Reference.GetTreeNode()
                        .GetContainingNode<IMethodDeclaration>()?.DeclaredElement;
                    if (containingMethod == null) continue;
                    var key = GetQualifiedName(containingMethod);
                    if (seen.Add(key))
                        calls.Add(ToSymbolInfo(containingMethod));
                }
            }
            else if (request.Direction == "callees")
            {
                var methodDecl = method.GetDeclarations().FirstOrDefault() as IMethodDeclaration;
                if (methodDecl?.Body != null)
                {
                    var seen = new HashSet<string>();
                    foreach (var invocation in methodDecl.Body.Descendants<IInvocationExpression>())
                    {
                        var invokedMethod = invocation.Reference?.Resolve().DeclaredElement as IMethod;
                        if (invokedMethod != null)
                        {
                            var key = GetQualifiedName(invokedMethod);
                            if (seen.Add(key) && calls.Count < MaxResults)
                                calls.Add(ToSymbolInfo(invokedMethod));
                        }
                    }
                }
            }

            return (RdCallHierarchyResult?)new RdCallHierarchyResult(root, calls);
        });
    }

    // ── Super Methods ───────────────────────────────────────────────────────

    private Task<RdSuperMethodsResult?> HandleFindSuperMethods(
        Lifetime lt, RdSuperMethodsRequest request)
    {
        return Task.Run(() =>
        {
            var element = ResolveDeclaredElementAt(request.Position);
            if (element is not IOverridableMember overridable) return null;

            var methodInfo = ToSymbolInfo(overridable);
            var hierarchy = new List<RdSuperMethodInfo>();
            var depth = 0;

            foreach (var superMember in overridable.GetAllSuperMembers())
            {
                depth++;
                var superElement = superMember.Element;
                var containingType = superElement.ContainingType;
                if (containingType == null) continue;

                hierarchy.Add(new RdSuperMethodInfo(
                    symbol: ToSymbolInfo(superElement),
                    containingTypeName: containingType.GetClrName().FullName,
                    containingTypeKind: GetTypeKind(containingType),
                    isInterface: containingType is IInterface,
                    depth: depth));
            }

            return (RdSuperMethodsResult?)new RdSuperMethodsResult(methodInfo, hierarchy);
        });
    }

    // ── File Structure ──────────────────────────────────────────────────────

    private Task<RdFileStructureResult?> HandleGetFileStructure(
        Lifetime lt, RdFileStructureRequest request)
    {
        return Task.Run(() =>
        {
            var psiFile = GetPsiFileForPath(request.FilePath);
            if (psiFile == null) return null;

            var nodes = new List<RdFlatStructureNode>();
            CollectStructureNodes(psiFile, nodes, 0);
            return (RdFileStructureResult?)new RdFileStructureResult(nodes);
        });
    }

    // ── Private Helpers ─────────────────────────────────────────────────────

    private IDeclaredElement? ResolveTarget(RdSemanticTarget target)
    {
        if (!string.IsNullOrWhiteSpace(target.FilePath) &&
            target.Line.HasValue &&
            target.Column.HasValue)
        {
            return ResolveDeclaredElementAt(new RdSourcePosition(
                target.FilePath,
                target.Line.Value,
                target.Column.Value));
        }

        if (!string.IsNullOrWhiteSpace(target.Language) &&
            !string.IsNullOrWhiteSpace(target.Symbol))
        {
            return ResolveSymbol(target.Language, target.Symbol);
        }

        return null;
    }

    private IDeclaredElement? ResolveSymbol(string? language, string symbol)
    {
        var normalized = NormalizeSymbolName(symbol);
        return EnumerateDeclaredElements()
            .Where(element => MatchesLanguage(element, language))
            .FirstOrDefault(element =>
                NormalizeSymbolName(GetQualifiedName(element)).Equals(normalized, StringComparison.OrdinalIgnoreCase) ||
                NormalizeSymbolName(element.ShortName).Equals(normalized, StringComparison.OrdinalIgnoreCase));
    }

    private IEnumerable<IDeclaredElement> EnumerateDeclaredElements()
    {
        foreach (var psiFile in EnumerateProjectPsiFiles())
        {
            var elements = new List<IDeclaredElement>();
            CollectDeclaredElements(psiFile, elements);
            foreach (var element in elements) yield return element;
        }
    }

    private IEnumerable<IFile> EnumerateProjectPsiFiles()
    {
        foreach (var project in _solution.GetAllProjects())
        {
            foreach (var projectFile in project.GetAllProjectFiles())
            {
                foreach (var sourceFile in projectFile.ToSourceFiles())
                {
                    var psiFile = sourceFile.GetPrimaryPsiFile();
                    if (psiFile != null) yield return psiFile;
                }
            }
        }
    }

    private static void CollectDeclaredElements(ITreeNode node, List<IDeclaredElement> elements)
    {
        if (node is IDeclaration declaration && declaration.DeclaredElement != null)
            elements.Add(declaration.DeclaredElement);

        foreach (var child in node.Children())
            CollectDeclaredElements(child, elements);
    }

    private static bool MatchesLanguage(IDeclaredElement element, string? language)
    {
        if (string.IsNullOrWhiteSpace(language)) return true;
        var presentationLanguage = element.PresentationLanguage?.Name;
        if (presentationLanguage != null && presentationLanguage.Equals(language, StringComparison.OrdinalIgnoreCase))
            return true;

        var filePath = element.GetDeclarations().FirstOrDefault()?.GetSourceFile()?.GetLocation().FullPath ?? "";
        return language.Equals("C#", StringComparison.OrdinalIgnoreCase) && filePath.EndsWith(".cs", StringComparison.OrdinalIgnoreCase) ||
               language.Equals("F#", StringComparison.OrdinalIgnoreCase) && (
                   filePath.EndsWith(".fs", StringComparison.OrdinalIgnoreCase) ||
                   filePath.EndsWith(".fsi", StringComparison.OrdinalIgnoreCase) ||
                   filePath.EndsWith(".fsx", StringComparison.OrdinalIgnoreCase));
    }

    private static bool MatchesName(string name, string query, string matchMode)
    {
        if (string.IsNullOrWhiteSpace(query)) return false;
        if (query.Contains('*'))
        {
            var pattern = "^" + Regex.Escape(query).Replace("\\*", ".*") + "$";
            return Regex.IsMatch(name, pattern, RegexOptions.IgnoreCase);
        }

        return matchMode.ToLowerInvariant() switch
        {
            "exact" => name.Equals(query, StringComparison.OrdinalIgnoreCase),
            "prefix" => name.StartsWith(query, StringComparison.OrdinalIgnoreCase),
            _ => name.Contains(query, StringComparison.OrdinalIgnoreCase) ||
                 GetCamelCase(name).Contains(query, StringComparison.OrdinalIgnoreCase)
        };
    }

    private static string GetCamelCase(string name)
    {
        return new string(name.Where(char.IsUpper).ToArray());
    }

    private static string NormalizeSymbolName(string value)
    {
        return value.Replace("#", ".", StringComparison.Ordinal)
            .Replace("::", ".", StringComparison.Ordinal)
            .Trim();
    }

    private static string BuildPreview(IDeclaration declaration, bool fullElementPreview, int maxPreviewLines)
    {
        var text = declaration.GetText();
        if (fullElementPreview)
        {
            var lines = text.Split('\n');
            return lines.Length <= maxPreviewLines
                ? text
                : string.Join("\n", lines.Take(maxPreviewLines)) +
                  $"\n// ... truncated ({lines.Length} total lines, showing {maxPreviewLines})";
        }

        return text;
    }

    private static List<string> BuildAstPath(IDeclaration declaration)
    {
        var path = new List<string>();
        for (ITreeNode? node = declaration; node != null; node = node.Parent)
        {
            if (node is IDeclaration parentDeclaration && parentDeclaration.DeclaredElement != null)
                path.Add(GetElementKind(parentDeclaration.DeclaredElement));
        }
        path.Reverse();
        return path;
    }

    private static RdReferenceInfo? ToReferenceInfo(JetBrains.ReSharper.Psi.Resolve.IReference reference)
    {
        var node = reference.GetTreeNode();
        var sourceFile = node.GetSourceFile();
        var document = sourceFile?.Document;
        var filePath = sourceFile?.GetLocation().FullPath;
        if (document == null || string.IsNullOrEmpty(filePath)) return null;

        var offset = node.GetNavigationRange().StartOffset.Offset;
        var coords = document.GetCoordsByOffset(offset);
        return new RdReferenceInfo(
            filePath,
            (int)coords.Line + 1,
            (int)coords.Column + 1,
            node.GetText().Trim(),
            "reference",
            new List<string>());
    }

    private ITypeElement? ResolveTypeElementAt(RdSourcePosition position)
    {
        var element = ResolveDeclaredElementAt(position);
        return element as ITypeElement
            ?? (element as ITypeMember)?.ContainingType;
    }

    private IDeclaredElement? ResolveDeclaredElementAt(RdSourcePosition position)
    {
        var psiFile = GetPsiFileForPath(position.FilePath);
        if (psiFile == null) return null;

        var sourceFile = psiFile.GetSourceFile();
        if (sourceFile == null) return null;

        var document = sourceFile.Document;
        var line = Math.Max(0, position.Line - 1); // Convert 1-based to 0-based
        var col = Math.Max(0, position.Column - 1);

        var docLine = (Int32<DocLine>)line;
        var offset = document.GetLineStartOffset(docLine) + col;
        foreach (var candidateOffset in CandidateOffsets(offset))
        {
            var node = psiFile.FindNodeAt(TreeTextRange.FromLength(new TreeOffset(candidateOffset), 1));
            if (node == null) continue;

            var resolved = ResolveFromNode(node);
            if (resolved != null) return resolved;
        }

        return null;
    }

    private static IEnumerable<int> CandidateOffsets(int offset)
    {
        yield return offset;
        for (var delta = 1; delta <= 3; delta++)
        {
            if (offset - delta >= 0) yield return offset - delta;
            yield return offset + delta;
        }
    }

    private static IDeclaredElement? ResolveFromNode(ITreeNode node)
    {
        foreach (var reference in node.GetReferences())
        {
            var resolved = reference.Resolve().DeclaredElement;
            if (resolved != null) return resolved;
        }

        if (node.GetContainingNode<IReferenceExpression>()?.Reference is { } referenceExpression)
        {
            var resolved = referenceExpression.Resolve().DeclaredElement;
            if (resolved != null) return resolved;
        }

        return node.GetContainingNode<IDeclaration>()?.DeclaredElement;
    }

    private List<JetBrains.ReSharper.Psi.Resolve.IReference> FindReferences(IDeclaredElement element)
    {
        var searchDomain = _solution.GetPsiServices().SearchDomainFactory
            .CreateSearchDomain(_solution, false);
        var referenceResults = new List<FindResult>();
        var consumer = new FindResultConsumer<List<FindResult>>(
            result => new List<FindResult> { result },
            results =>
            {
                foreach (var result in results)
                {
                    referenceResults.Add(result);
                    if (referenceResults.Count >= MaxResults)
                        return FindExecution.Stop;
                }
                return FindExecution.Continue;
            });

        _solution.GetPsiServices().Finder.FindReferences(
            element,
            searchDomain,
            consumer,
            NoOpProgressIndicator.Instance,
            false);

        return referenceResults
            .OfType<FindResultReference>()
            .Select(result => result.Reference)
            .Where(reference => reference.IsValid())
            .ToList();
    }

    private IFile? GetPsiFileForPath(string filePath)
    {
        if (string.IsNullOrWhiteSpace(filePath)) return null;

        var normalizedPath = filePath.Replace('/', Path.DirectorySeparatorChar);
        var vfp = VirtualFileSystemPath.Parse(normalizedPath, InteractionContext.SolutionContext);
        
        // Find the project file for this path
        var projectFiles = _solution.FindProjectItemsByLocation(vfp)
            .OfType<IProjectFile>()
            .ToList();

        if (projectFiles.Count == 0) return null;

        var projectFile = projectFiles.First();
        var sourceFiles = projectFile.ToSourceFiles();
        var sourceFile = sourceFiles.FirstOrDefault();
        return sourceFile?.GetPrimaryPsiFile();
    }

    private static RdSymbolInfo ToSymbolInfo(IDeclaredElement element)
    {
        var declarations = element.GetDeclarations();
        var declaration = declarations.FirstOrDefault();

        string? filePath = null;
        int? line = null;
        int? column = null;

        if (declaration != null)
        {
            var sourceFile = declaration.GetSourceFile();
            filePath = sourceFile?.GetLocation().FullPath;

            var document = sourceFile?.Document;
            if (document != null)
            {
                var offset = declaration.GetNavigationRange().StartOffset.Offset;
                var coords = document.GetCoordsByOffset(offset);
                line = (int)coords.Line + 1; // Convert to 1-based
                column = (int)coords.Column + 1;
            }
        }

        var language = element.PresentationLanguage?.Name ?? "C#";
        var signature = element is IParametersOwner paramOwner
            ? $"{element.ShortName}({string.Join(", ", paramOwner.Parameters.Select(p => p.Type.GetPresentableName(CSharpLanguage.Instance!)))})"
            : null;

        return new RdSymbolInfo(
            name: element.ShortName,
            qualifiedName: GetQualifiedName(element),
            kind: GetElementKind(element),
            filePath: filePath,
            line: line,
            column: column,
            language: language,
            signature: signature,
            modifiers: GetModifiers(element));
    }

    private static string GetQualifiedName(IDeclaredElement element)
    {
        if (element is ITypeElement typeElement)
            return typeElement.GetClrName().FullName;
        if (element is ITypeMember member)
            return $"{member.ContainingType?.GetClrName().FullName}.{member.ShortName}";
        return element.ShortName;
    }

    private static string GetElementKind(IDeclaredElement element) => element switch
    {
        IInterface => "INTERFACE",
        IStruct => "STRUCT",
        IEnum => "ENUM",
        IDelegate => "DELEGATE",
        IClass cls => cls.IsAbstract ? "ABSTRACT_CLASS" : "CLASS",
        IConstructor => "CONSTRUCTOR",
        IMethod => "METHOD",
        IProperty => "PROPERTY",
        IField => "FIELD",
        IEvent => "EVENT",
        _ => "UNKNOWN"
    };

    private static string GetTypeKind(ITypeElement element) => element switch
    {
        IInterface => "INTERFACE",
        IStruct => "STRUCT",
        IEnum => "ENUM",
        IDelegate => "DELEGATE",
        IClass => "CLASS",
        _ => "TYPE"
    };

    private static List<string> GetModifiers(IDeclaredElement element)
    {
        var modifiers = new List<string>();
        if (element is IModifiersOwner owner)
        {
            if (owner.IsAbstract) modifiers.Add("abstract");
            if (owner.IsSealed) modifiers.Add("sealed");
            if (owner.IsStatic) modifiers.Add("static");
            if (owner.IsVirtual) modifiers.Add("virtual");
            if (owner.IsOverride) modifiers.Add("override");

            switch (owner.GetAccessRights())
            {
                case AccessRights.PUBLIC: modifiers.Add("public"); break;
                case AccessRights.PRIVATE: modifiers.Add("private"); break;
                case AccessRights.PROTECTED: modifiers.Add("protected"); break;
                case AccessRights.INTERNAL: modifiers.Add("internal"); break;
                case AccessRights.PROTECTED_OR_INTERNAL: modifiers.Add("protected internal"); break;
                case AccessRights.PROTECTED_AND_INTERNAL: modifiers.Add("private protected"); break;
            }
        }
        return modifiers;
    }

    private void CollectStructureNodes(ITreeNode node, List<RdFlatStructureNode> nodes, int depth)
    {
        if (node is IDeclaration declaration && declaration.DeclaredElement != null)
        {
            var element = declaration.DeclaredElement;
            var kind = GetElementKind(element);
            if (kind != "UNKNOWN")
            {
                var sourceFile = declaration.GetSourceFile();
                var document = sourceFile?.Document;
                var line = 1;
                if (document != null)
                {
                    var offset = declaration.GetNavigationRange().StartOffset.Offset;
                    line = (int)document.GetCoordsByOffset(offset).Line + 1;
                }

                var signature = element is IParametersOwner paramOwner
                    ? $"{element.ShortName}({string.Join(", ", paramOwner.Parameters.Select(p => p.Type.GetPresentableName(CSharpLanguage.Instance!)))})"
                    : null;

                nodes.Add(new RdFlatStructureNode(
                    name: element.ShortName,
                    kind: kind,
                    signature: signature,
                    modifiers: GetModifiers(element),
                    line: line,
                    depth: depth));
            }
        }

        foreach (var child in node.Children())
        {
            CollectStructureNodes(child, nodes, node is IDeclaration ? depth + 1 : depth);
        }
    }
}

/// <summary>
/// Simple no-op progress indicator for API calls that require one.
/// </summary>
internal sealed class NoOpProgressIndicator : IProgressIndicator
{
    public static readonly NoOpProgressIndicator Instance = new();

    private NoOpProgressIndicator() { }

    public bool IsCanceled { get; set; }
    public string? TaskName { get; set; }
    public string? CurrentItemText { get; set; }

    public void Start(int totalWork) { }
    public void Stop() { }
    public void Advance(double work) { }
    public void Dispose() { }
}
