using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using JetBrains.Application.DataContext;
using JetBrains.Application.Parts;
using JetBrains.Application.Progress;
using JetBrains.Application.Threading;
using JetBrains.Core;
using JetBrains.DocumentModel;
using JetBrains.Lifetimes;
using JetBrains.ProjectModel;
using JetBrains.ProjectModel.DataContext;
using JetBrains.ReSharper.Resources.Shell;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.Caches;
using JetBrains.ReSharper.Psi.CSharp;
using JetBrains.ReSharper.Psi.CSharp.Tree;
using JetBrains.ReSharper.Psi.DataContext;
using JetBrains.ReSharper.Psi.Files;
using JetBrains.ReSharper.Psi.Modules;
using JetBrains.ReSharper.Psi.Search;
using JetBrains.ReSharper.Psi.Tree;
using JetBrains.ReSharper.Psi.Transactions;
using JetBrains.ReSharper.Psi.Util;
using JetBrains.ReSharper.Feature.Services.Refactorings;
using JetBrains.ReSharper.Feature.Services.Refactorings.Specific.Rename;
using JetBrains.ReSharper.Feature.Services.Protocol;
using JetBrains.ReSharper.Refactorings.Rename;
using JetBrains.Rider.Model;
using JetBrains.Rider.Model.IndexMcp;
using JetBrains.Rd.Tasks;
using JetBrains.Util;
using JetBrains.Util.dataStructures.TypedIntrinsics;
using JetBrains.Util.EventBus;
using JetBrains.Util.Threading.Tasks;

namespace ReSharperPlugin.IndexMcp;

/// <summary>
/// Main backend host for the IDE Index MCP Server protocol.
///
/// Handles all code intelligence RPC calls from the Kotlin frontend by using
/// ReSharper's full semantic model for C# and F# code.
/// </summary>
[SolutionComponent(Instantiation.ContainerAsyncPrimaryThread)]
public class IndexMcpBackendHost
{
    private readonly ISolution _solution;
    private readonly IShellLocks _shellLocks;
    private readonly RenameRefactoringService _renameRefactoringService;
    private const string BackendVersion = "4.18.0";
    private const int MaxResults = 200;

    public IndexMcpBackendHost(
        Lifetime lifetime,
        ISolution solution,
        IShellLocks shellLocks,
        RenameRefactoringService renameRefactoringService)
    {
        _solution = solution;
        _shellLocks = shellLocks;
        _renameRefactoringService = renameRefactoringService;
        var model = solution.GetProtocolSolution().GetIndexMcpModel();

        model.GetBackendStatus.SetAsync(HandleGetBackendStatus);
        model.FindTypes.SetAsync(HandleFindTypes);
        model.FindDefinition.SetAsync(HandleFindDefinition);
        model.FindReferences.SetAsync(HandleFindReferences);
        model.ResolveSymbol.SetAsync(HandleResolveSymbol);
        model.ResolveSymbolIndexed.SetAsync(HandleResolveSymbolIndexed);
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
            var plan = BuildFindTypesSearchPlan(request.Language, request.Scope, request.MatchMode, request.Query);

            var declaredTypes = plan.UseProjectDeclaredTypeScan
                ? EnumerateDeclaredTypeElements(lt, plan.AllowedProjectFileExtensions)
                : Enumerable.Empty<ITypeElement>();

            var exactQualifiedProjectTypes = plan.UseExactQualifiedProjectLookup
                ? EnumerateProjectQualifiedTypeElements(lt, request.Query, plan.AllowedProjectFileExtensions)
                : Enumerable.Empty<ITypeElement>();

            var indexedTypes = plan.UseIndexedTypeFallback
                ? EnumerateIndexedTypeElements(lt, request.Query, request.MatchMode)
                : Enumerable.Empty<ITypeElement>();

            var results = exactQualifiedProjectTypes
                .Concat(declaredTypes)
                .Concat(indexedTypes)
                .Where(type => MatchesName(type.ShortName, request.Query, request.MatchMode) ||
                               MatchesQualifiedName(GetQualifiedName(type), request.Query, request.MatchMode))
                .Where(type => MatchesLanguage(type, request.Language))
                .Where(type => MatchesScope(type, request.Scope))
                .OrderBy(type => MatchRank(type.ShortName, request.Query, request.MatchMode))
                .ThenBy(BestDeclarationRank)
                .ThenBy(type => IsTestPath(GetDeclarationPath(type)) ? 1 : 0)
                .ThenBy(type => type.ShortName, StringComparer.OrdinalIgnoreCase)
                .Select(ToSymbolInfo)
                .Concat(EnumerateStandardDotNetTypeSymbols(request.Query, request.MatchMode, request.Scope, request.Language))
                .GroupBy(symbol => $"{symbol.QualifiedName}:{symbol.FilePath}:{symbol.Line}")
                .Select(group => group.First())
                .ToList();

            return new RdFindTypesResult(results.Take(Math.Min(request.Limit, MaxResults)).ToList(), results.Count);
        });
    }

    private static FindTypesSearchPlan BuildFindTypesSearchPlan(
        string? language,
        string scope,
        string matchMode,
        string query)
    {
        var normalizedLanguage = NormalizeLanguage(language);
        var isProjectFilesScope = scope.Equals("project_files", StringComparison.OrdinalIgnoreCase);
        var isExactQualifiedLookup = matchMode.Equals("exact", StringComparison.OrdinalIgnoreCase) &&
                                     query.Contains('.', StringComparison.Ordinal) &&
                                     normalizedLanguage != null &&
                                     isProjectFilesScope;

        return new FindTypesSearchPlan(
            allowedProjectFileExtensions: isProjectFilesScope ? GetProjectFileExtensions(normalizedLanguage) : null,
            useProjectDeclaredTypeScan: !isExactQualifiedLookup,
            useExactQualifiedProjectLookup: isExactQualifiedLookup,
            useIndexedTypeFallback: scope == "project_and_libraries");
    }

    private static FindReferencesResolutionPlan BuildFindReferencesResolutionPlan(
        string scope,
        ParsedRiderSymbol parsedSymbol)
    {
        var normalizedLanguage = NormalizeLanguage(parsedSymbol.Language);
        var isProjectFilesScope = scope.Equals("project_files", StringComparison.OrdinalIgnoreCase);
        var useProjectQualifiedTypeLookup = normalizedLanguage == "F#" &&
                                            isProjectFilesScope &&
                                            parsedSymbol.MemberName == null &&
                                            parsedSymbol.ContainerQualifiedName.Contains('.', StringComparison.Ordinal);
        var rejectUnboundedReferenceSearch = useProjectQualifiedTypeLookup;

        return new FindReferencesResolutionPlan(
            allowedProjectFileExtensions: useProjectQualifiedTypeLookup ? GetProjectFileExtensions(normalizedLanguage) : null,
            useProjectQualifiedTypeLookup: useProjectQualifiedTypeLookup,
            allowLibraryFallback: !useProjectQualifiedTypeLookup,
            rejectUnboundedReferenceSearch: rejectUnboundedReferenceSearch);
    }

    private static void EnsureFindReferencesSearchIsSupported(
        FindReferencesResolutionPlan plan,
        ParsedRiderSymbol parsedSymbol,
        string scope)
    {
        if (!plan.RejectUnboundedReferenceSearch)
            return;

        throw new InvalidOperationException(
            $"F# symbol-based find_references for type-only qualified symbols in scope '{scope}' is temporarily blocked because Rider backend global reference search is unbounded and can exceed the RD timeout. Use a position-based target or a more specific member symbol instead.");
    }

    private static void EnsureFindReferencesIndexedTargetIsSupported(
        string? language,
        string symbol,
        string scope)
    {
        var parseResult = ParseIndexedSymbol(language, symbol);
        if (!parseResult.IsSuccess || parseResult.Symbol == null)
            return;

        var plan = BuildFindReferencesResolutionPlan(scope, parseResult.Symbol);
        EnsureFindReferencesSearchIsSupported(plan, parseResult.Symbol, scope);
    }

    private static IEnumerable<RdSymbolInfo> EnumerateStandardDotNetTypeSymbols(
        string query,
        string matchMode,
        string scope,
        string? language)
    {
        if (scope != "project_and_libraries") yield break;
        if (!string.IsNullOrWhiteSpace(language) &&
            !language.Equals("C#", StringComparison.OrdinalIgnoreCase) &&
            !language.Equals("CSharp", StringComparison.OrdinalIgnoreCase) &&
            !language.Equals("F#", StringComparison.OrdinalIgnoreCase) &&
            !language.Equals("FSharp", StringComparison.OrdinalIgnoreCase))
            yield break;

        foreach (var type in StandardDotNetTypes)
        {
            if (!MatchesName(type.Name, query, matchMode) &&
                !MatchesName(type.QualifiedName, query, matchMode))
                continue;

            yield return new RdSymbolInfo(
                name: type.Name,
                qualifiedName: type.QualifiedName,
                kind: type.Kind,
                filePath: null,
                line: null,
                column: null,
                language: "C#",
                signature: null,
                modifiers: new List<string> { "public", "library" });
        }
    }

    // Single source of truth for the curated BCL/standard .NET type list surfaced through
    // ide_find_class with scope=project_and_libraries. Frontend (Kotlin) intentionally does NOT
    // duplicate this list — backend merges it into the response so the frontend is a thin pass-through.
    private static readonly IReadOnlyList<(string Name, string QualifiedName, string Kind)> StandardDotNetTypes =
        new List<(string, string, string)>
        {
            ("Object", "System.Object", "CLASS"),
            ("String", "System.String", "CLASS"),
            ("Console", "System.Console", "CLASS"),
            ("DateTime", "System.DateTime", "STRUCT"),
            ("DateTimeOffset", "System.DateTimeOffset", "STRUCT"),
            ("Guid", "System.Guid", "STRUCT"),
            ("Uri", "System.Uri", "CLASS"),
            ("Exception", "System.Exception", "CLASS"),
            ("Task", "System.Threading.Tasks.Task", "CLASS"),
            ("ValueTask", "System.Threading.Tasks.ValueTask", "STRUCT"),
            ("Enumerable", "System.Linq.Enumerable", "CLASS"),
            ("List", "System.Collections.Generic.List", "CLASS"),
            ("Dictionary", "System.Collections.Generic.Dictionary", "CLASS"),
            ("HashSet", "System.Collections.Generic.HashSet", "CLASS"),
            ("Queue", "System.Collections.Generic.Queue", "CLASS"),
            ("Stack", "System.Collections.Generic.Stack", "CLASS"),
            ("IEnumerable", "System.Collections.IEnumerable", "INTERFACE"),
            ("IEnumerable", "System.Collections.Generic.IEnumerable", "INTERFACE"),
            ("ICollection", "System.Collections.Generic.ICollection", "INTERFACE"),
            ("IList", "System.Collections.Generic.IList", "INTERFACE"),
            ("IReadOnlyCollection", "System.Collections.Generic.IReadOnlyCollection", "INTERFACE"),
            ("IReadOnlyList", "System.Collections.Generic.IReadOnlyList", "INTERFACE"),
            ("IDictionary", "System.Collections.Generic.IDictionary", "INTERFACE"),
            ("IReadOnlyDictionary", "System.Collections.Generic.IReadOnlyDictionary", "INTERFACE")
        };

    private IEnumerable<ITypeElement> EnumerateIndexedTypeElements(Lifetime lt, string query, string matchMode)
    {
        foreach (var libraryScope in new[] { LibrarySymbolScope.REFERENCED, LibrarySymbolScope.TRANSITIVE, LibrarySymbolScope.FULL })
        {
            EnsureLifetimeAlive(lt);
            foreach (var element in EnumerateSymbolScopeTypes(
                          _solution.GetPsiServices().Symbols.GetSymbolScope(libraryScope, false),
                          query,
                         matchMode))
            {
                yield return element;
            }
        }

        var seenModules = new HashSet<IPsiModule>();
        foreach (var module in EnumerateProjectPsiModules(lt, null).Where(module => seenModules.Add(module)))
        {
            EnsureLifetimeAlive(lt);
            foreach (var element in EnumeratePredefinedTypeElements(module))
                yield return element;

            var symbolScope = _solution.GetPsiServices().Symbols.GetSymbolScope(module, true, false);
            foreach (var element in EnumerateSymbolScopeTypes(symbolScope, query, matchMode))
                yield return element;
        }
    }

    private static IEnumerable<ITypeElement> EnumeratePredefinedTypeElements(IPsiModule module)
    {
        var predefinedType = typeof(PredefinedType).GetConstructor(
            BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance,
            null,
            new[] { typeof(IPsiModule) },
            null)?.Invoke(new object[] { module });
        if (predefinedType == null) yield break;
        foreach (var property in typeof(PredefinedType).GetProperties(BindingFlags.Public | BindingFlags.Instance))
        {
            if (!typeof(IDeclaredType).IsAssignableFrom(property.PropertyType)) continue;
            if (property.GetValue(predefinedType) is not IDeclaredType declaredType) continue;
            var typeElement = declaredType.GetTypeElement();
            if (typeElement != null) yield return typeElement;
        }
    }

    private static IEnumerable<ITypeElement> EnumerateSymbolScopeTypes(ISymbolScope symbolScope, string query, string matchMode)
    {
        if (query.Contains('.', StringComparison.Ordinal))
        {
            foreach (var element in symbolScope.GetElementsByQualifiedName(query).OfType<ITypeElement>())
                yield return element;
        }

        foreach (var shortName in symbolScope.GetAllShortNames().Where(name => MatchesName(name, query, matchMode)))
        {
            foreach (var element in symbolScope.GetElementsByShortName(shortName).OfType<ITypeElement>())
                yield return element;
        }
    }

    private Task<RdDefinitionResult?> HandleFindDefinition(Lifetime lt, RdFindDefinitionRequest request)
    {
        return Task.Run(() =>
        {
            var element = ResolveTarget(request.Target);
            if (element == null) return (RdDefinitionResult?)null;

            var navigationElement = PickPreferredDeclaration(element);
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
            if (!string.IsNullOrWhiteSpace(request.Target.Language) &&
                !string.IsNullOrWhiteSpace(request.Target.Symbol))
            {
                EnsureFindReferencesIndexedTargetIsSupported(
                    request.Target.Language,
                    request.Target.Symbol,
                    request.Scope);
            }

            var element = ResolveTargetForFindReferences(lt, request.Target, request.Scope);
            if (element == null) return new RdFindReferencesResult(new List<RdReferenceInfo>(), 0);

            var references = FindReferences(lt, element)
                .Select(ToReferenceInfo)
                .Where(reference => reference != null)
                .Cast<RdReferenceInfo>()
                .Where(reference => MatchesScope(reference.FilePath, request.Scope))
                .GroupBy(reference => $"{reference.FilePath}:{reference.Line}:{reference.Column}")
                .Select(group => group.First())
                .ToList();

            return new RdFindReferencesResult(references.Take(Math.Min(request.Limit, MaxResults)).ToList(), references.Count);
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

    private Task<RdResolveSymbolIndexedResult> HandleResolveSymbolIndexed(Lifetime lt, RdResolveSymbolIndexedRequest request)
    {
        return Task.Run(() => ResolveSymbolIndexed(request.Language, request.Symbol).ToRdResult());
    }

    // ── Rename Symbol ───────────────────────────────────────────────────────

    private Task<RdRenameSymbolResult?> HandleRenameSymbol(
        Lifetime lt, RdRenameSymbolRequest request)
    {
        OuterLifetime outerLifetime = lt;
        RdRenameSymbolResult? result = null;
        return ExecuteWriteLockedRename(outerLifetime, () => { result = ExecuteRenameSymbol(request); })
            .ContinueWith<RdRenameSymbolResult?>(task =>
            {
                if (!task.IsFaulted)
                {
                    return result;
                }

                var exception = task.Exception?.GetBaseException();
                return new RdRenameSymbolResult(
                    false,
                    "",
                    request.NewName,
                    new List<string>(),
                    0,
                    exception == null
                        ? "ReSharper backend rename failed while acquiring/executing the write lock."
                        : $"ReSharper backend rename failed while acquiring/executing the write lock: {exception.GetType().Name}: {exception.Message}");
            });
    }

    private Task ExecuteWriteLockedRename(OuterLifetime outerLifetime, Action action)
    {
        var shellLocksEx = typeof(IShellLocks).Assembly.GetType("JetBrains.Application.Threading.IShellLocksEx")
            ?? throw new InvalidOperationException("Unable to locate IShellLocksEx in the Rider runtime.");

        var writeLockAsync = shellLocksEx.GetMethods(BindingFlags.Public | BindingFlags.Static)
            .FirstOrDefault(method =>
            {
                if (method.Name != "ExecuteOrQueueWriteLockAsyncEx" || method.IsGenericMethodDefinition)
                {
                    return false;
                }

                var parameters = method.GetParameters();
                return parameters.Length == 8 &&
                       parameters[0].ParameterType == typeof(IShellLocks) &&
                       parameters[1].ParameterType == typeof(OuterLifetime) &&
                       parameters[2].ParameterType == typeof(Action) &&
                       parameters[3].ParameterType == typeof(TimeSpan) &&
                       parameters[4].ParameterType == typeof(TaskPriority) &&
                       parameters[5].ParameterType == typeof(bool) &&
                       parameters[6].ParameterType == typeof(string) &&
                       parameters[7].ParameterType == typeof(string);
            });

        if (writeLockAsync != null)
        {
            return (Task)writeLockAsync.Invoke(
                null,
                new object[] { _shellLocks, outerLifetime, action, TimeSpan.FromSeconds(30), TaskPriority.Normal, false, "", "" })!;
        }

        var writeLockWhenAvailable = shellLocksEx.GetMethods(BindingFlags.Public | BindingFlags.Static)
            .FirstOrDefault(method =>
            {
                if (method.Name != "ExecuteOrQueueWithWriteLockWhenAvailableEx" || method.IsGenericMethodDefinition)
                {
                    return false;
                }

                var parameters = method.GetParameters();
                return parameters.Length == 7 &&
                       parameters[0].ParameterType == typeof(IShellLocks) &&
                       parameters[1].ParameterType == typeof(OuterLifetime) &&
                       parameters[2].ParameterType == typeof(string) &&
                       parameters[3].ParameterType == typeof(Action) &&
                       parameters[4].ParameterType == typeof(TimeSpan) &&
                       parameters[5].ParameterType == typeof(string) &&
                       parameters[6].ParameterType == typeof(string);
            });

        if (writeLockWhenAvailable != null)
        {
            return (Task)writeLockWhenAvailable.Invoke(
                null,
                new object[] { _shellLocks, outerLifetime, "Index MCP Rider rename", action, TimeSpan.FromSeconds(30), "", "" })!;
        }

        throw new MissingMethodException(
            "No compatible Rider/ReSharper write-lock API was found for headless symbol rename.");
    }

    private RdRenameSymbolResult ExecuteRenameSymbol(RdRenameSymbolRequest request)
    {
        // Use the stricter resolver: rename must act on the symbol literally at the requested
        // coordinates. If the local declaration's textual name disagrees with the resolved type
        // element's ShortName (e.g. desynced AXAML/code-behind partial classes), the resolver will
        // reject that candidate, returning null and surfacing a clearer error here.
        var localDeclarationNode = ResolveDeclarationNodeAt(request.Position);
        var element = localDeclarationNode != null && TryGetDeclaredElement(localDeclarationNode, out var localElement)
            ? localElement
            : ResolveDeclaredElementAt(request.Position, requireConsistentLocalDeclaration: true);
        if (element == null)
        {
            return new RdRenameSymbolResult(
                    false,
                    "",
                    request.NewName,
                    new List<string>(),
                    0,
                    $"No C#/F# symbol found at {request.Position.FilePath}:{request.Position.Line}:{request.Position.Column}, " +
                    "or the local declaration's textual name disagrees with the resolved type element " +
                    "(this can happen with desynced partial-class pairs such as AXAML/code-behind " +
                    "where one partial was renamed without the other). No files were modified.");
        }

        var oldName = element.ShortName;
        var localName = GetDeclarationNodeName(localDeclarationNode);
        if (!string.IsNullOrEmpty(localName))
            oldName = localName;

        // Bug guard: a no-op rename (oldName == newName) cannot be detected by the file-content
        // polling oracle (the new name is always present, since it equals the existing name), so it
        // would falsely report success without actually doing anything. Fail explicitly instead.
        if (string.Equals(oldName, request.NewName, StringComparison.Ordinal))
        {
            return new RdRenameSymbolResult(
                    false,
                    oldName,
                    request.NewName,
                    new List<string>(),
                    0,
                    $"Rename refused: new name '{request.NewName}' equals current name '{oldName}' (no-op rename). " +
                    "No files were modified.");
        }

        var availability = _renameRefactoringService.CheckRenameAvailability(element);
        if (availability != RenameAvailabilityCheckResult.CanBeRenamed)
        {
            return new RdRenameSymbolResult(
                    false,
                    oldName,
                    request.NewName,
                    new List<string>(),
                    0,
                    $"ReSharper reports that '{oldName}' cannot be renamed ({availability}). No files were modified.");
        }

        var affectedFiles = GetPotentiallyAffectedFiles(element);
        AddAffectedFile(affectedFiles, localDeclarationNode?.GetSourceFile()?.GetLocation().FullPath);

        // Capture per-file count of the OLD identifier (as a standalone identifier, not substring).
        // The success oracle below uses this snapshot: a real rename must reduce the old-name count
        // in at least one affected file. Substring-only checks (the v4.19.0/v4.19.1 oracle) gave
        // false positives whenever the new name was a substring of the old name (e.g. revert
        // Foo→Bar→Foo where Bar contains Foo, or any AXAML/code-behind partial pair).
        var oldNameCountsBefore = SnapshotIdentifierCounts(affectedFiles, oldName);

        try
        {
            var workflowMessage = TryExecuteDrivenRename(element, request.NewName);

            if (!string.IsNullOrWhiteSpace(workflowMessage))
            {
                return new RdRenameSymbolResult(
                        false,
                        oldName,
                        request.NewName,
                        affectedFiles,
                        0,
                        $"ReSharper rename did not update affected files: {workflowMessage}");
            }

            if (!RenameChangedAffectedFiles(oldNameCountsBefore, oldName))
            {
                return new RdRenameSymbolResult(
                        false,
                        oldName,
                        request.NewName,
                        affectedFiles,
                        0,
                        $"ReSharper rename completed without error, but no affected file's count of the identifier '{oldName}' decreased. The rename did not actually rewrite anything on disk.");
            }

            return new RdRenameSymbolResult(
                    true,
                    oldName,
                    request.NewName,
                    affectedFiles,
                    affectedFiles.Count,
                    $"Renamed '{oldName}' to '{request.NewName}' using ReSharper backend rename.");
        }
        catch (Exception ex)
        {
            return new RdRenameSymbolResult(
                    false,
                    oldName,
                    request.NewName,
                    new List<string>(),
                    0,
                    $"ReSharper backend rename failed: {ex.GetType().Name}: {ex.Message}");
        }
    }

    private string? TryExecuteDrivenRename(
        IDeclaredElement element,
        string newName)
    {
        try
        {
            var dataProvider = new RenameDataProvider(element, newName);
            SetRuntimeProperty(dataProvider, "CanBeLocal", false);

            var model = new CustomRenameModel();
            SetRuntimeProperty(model, "HasUI", false);
            SetRuntimeProperty(model, "QuickRename", false);
            SetRuntimeProperty(model, "CreateRenameConfirmationPage", false);
            SetRuntimeProperty(model, "ChangeTextOccurrences", false);
            SetRuntimeProperty(model, "RenameDerived", true);
            SetRuntimeProperty(model, "Bulk", false);
            SetRuntimeProperty(dataProvider, "Model", model);

            var driver = ExecuteRenameWorkflow(element, dataProvider);

            var conflicts = driver.Conflicts.ToList();
            if (conflicts.Count > 0)
                return string.Join("; ", conflicts.Select(conflict => conflict.Description).Where(description => !string.IsNullOrWhiteSpace(description)));

            return null;
        }
        catch (TargetInvocationException ex) when (ex.InnerException != null)
        {
            var stack = ex.InnerException.StackTrace?.Split(new[] { Environment.NewLine }, StringSplitOptions.None)
                .Take(6);
            var stackText = stack == null ? "" : $" Stack: {string.Join(" | ", stack)}";
            return $"ReSharper driven rename failed: {ex.InnerException.GetType().Name}: {ex.InnerException.Message}{stackText}";
        }
        catch (Exception ex)
        {
            var stack = ex.StackTrace?.Split(new[] { Environment.NewLine }, StringSplitOptions.None)
                .Take(8);
            var stackText = stack == null ? "" : $" Stack: {string.Join(" | ", stack)}";
            return $"ReSharper driven rename failed: {ex.GetType().Name}: {ex.Message}{stackText}";
        }
    }

    private RefactoringDriverWithConflicts ExecuteRenameWorkflow(
        IDeclaredElement element,
        RenameDataProvider dataProvider)
    {
        using var lifetimeDefinition = Lifetime.Define(_solution.GetSolutionLifetimes().UntilSolutionCloseLifetime);
        using var compilationContext = CompilationContextCookie.GetExplicitUniversalContextIfNotSet();
        var dataContext = CreateRenameDataContext(element, dataProvider, lifetimeDefinition);
        var workflow = new RenameWorkflow(_solution, "Index MCP Rider rename")
        {
            EventBus = Shell.Instance.GetComponent<IEventBus>(),
            WorkflowExecuterLifetime = lifetimeDefinition.Lifetime
        };

        if (!workflow.Initialize(dataContext))
            throw new InvalidOperationException("ReSharper rename workflow is not available for the selected symbol.");

        ProcessWorkflowPages(workflow);

        var driver = new RefactoringDriverWithConflicts(new RefactoringDriverStorage());
        var executer = workflow.CreateRefactoring(driver)
                       ?? throw new InvalidOperationException("ReSharper rename workflow did not create a refactoring executer.");

        if (!workflow.PreExecute(NoOpProgressIndicator.Instance))
            throw new InvalidOperationException("ReSharper rename workflow PreExecute returned false.");

        var executed = PsiTransactionCookie.ExecuteConditionally(
            _solution.GetPsiServices(),
            () => executer.Execute(NoOpProgressIndicator.Instance),
            "Index MCP Rider rename");
        if (!executed)
            throw new InvalidOperationException("ReSharper rename workflow Execute returned false.");

        if (!workflow.PostExecute(NoOpProgressIndicator.Instance))
            throw new InvalidOperationException("ReSharper rename workflow PostExecute returned false.");

        workflow.SuccessfulFinish(NoOpProgressIndicator.Instance);
        return driver;
    }

    private IDataContext CreateRenameDataContext(
        IDeclaredElement element,
        RenameDataProvider dataProvider,
        LifetimeDefinition lifetimeDefinition)
    {
        ICollection<IDeclaredElement> declaredElements = new List<IDeclaredElement> { element };
        var rules = DataRules.AddRule("IndexMcpRename", PsiDataConstants.DECLARED_ELEMENTS, declaredElements)
            .AddRule("IndexMcpRename", PsiDataConstants.DECLARED_ELEMENTS_FROM_ALL_CONTEXTS, declaredElements)
            .AddRule("IndexMcpRename", ProjectModelDataConstants.SOLUTION, _solution)
            .AddRule("IndexMcpRename", RenameRefactoringService.RenameDataProvider, dataProvider);
        return _solution.GetComponent<DataContexts>().CreateWithDataRules(lifetimeDefinition.Lifetime, rules);
    }

    private static void ProcessWorkflowPages(IRefactoringWorkflow workflow)
    {
        var page = workflow.FirstPendingRefactoringPage;
        var guard = 0;
        while (page != null)
        {
            if (++guard > 32)
                throw new InvalidOperationException("ReSharper rename workflow produced too many non-UI pages.");
            if (!page.Initialize(NoOpProgressIndicator.Instance))
                throw new InvalidOperationException($"ReSharper rename page '{page.Title}' failed to initialize.");
            page = page.Commit(NoOpProgressIndicator.Instance);
        }
    }

    private static void SetRuntimeProperty(object target, string propertyName, object value)
    {
        var property = target.GetType().GetProperty(propertyName, BindingFlags.Instance | BindingFlags.Public);
        if (property == null || !property.CanWrite)
            throw new MissingMethodException(target.GetType().FullName, $"set_{propertyName}");
        property.SetValue(target, value);
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
            var effectiveLimit = GetEffectiveResultLimit(request.Limit);
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
                    return FindExecution.Continue;
                },
                searchDomain,
                NoOpProgressIndicator.Instance);

            var orderedImplementations = implementations
                .GroupBy(symbol => $"{symbol.QualifiedName}:{symbol.FilePath}:{symbol.Line}:{symbol.Column}")
                .Select(group => group.First())
                .OrderBy(symbol => symbol.QualifiedName, StringComparer.OrdinalIgnoreCase)
                .ThenBy(symbol => symbol.Signature ?? string.Empty, StringComparer.OrdinalIgnoreCase)
                .ThenBy(symbol => symbol.FilePath ?? string.Empty, StringComparer.OrdinalIgnoreCase)
                .ThenBy(symbol => symbol.Line ?? int.MaxValue)
                .ThenBy(symbol => symbol.Column ?? int.MaxValue)
                .Take(effectiveLimit)
                .ToList();

            return (RdImplementationsResult?)new RdImplementationsResult(orderedImplementations);
        });
    }

    // ── Call Hierarchy ──────────────────────────────────────────────────────

    private Task<RdCallHierarchyResult?> HandleGetCallHierarchy(
        Lifetime lt, RdCallHierarchyRequest request)
    {
        return Task.Run(() =>
        {
            var resolutionPlan = BuildCallHierarchyResolutionPlan(request.Target);
            var callableTarget = resolutionPlan.UseDeclarationOnlyFastPath
                ? ResolveCallableDeclarationTargetAt(new RdSourcePosition(
                    request.Target.FilePath!,
                    request.Target.Line!.Value,
                    request.Target.Column!.Value))
                : ResolveCallableTarget(ResolveTarget(request.Target));
            if (callableTarget == null) return null;

            var effectiveLimit = GetEffectiveResultLimit(request.Limit);

            var root = ToSymbolInfo(callableTarget.Element);
            var callElements = new List<IDeclaredElement>();

            if (request.Direction == "callers")
            {
                var searchDomain = _solution.GetPsiServices().SearchDomainFactory
                    .CreateSearchDomain(_solution, false);
                var seenCallKeys = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                var consumer = new FindResultConsumer<List<FindResult>>(
                    result => new List<FindResult> { result },
                    results =>
                    {
                        EnsureLifetimeAlive(lt);
                        foreach (var result in results)
                        {
                            EnsureLifetimeAlive(lt);
                            if (result is not FindResultReference referenceResult) continue;

                            var containingCallable = ResolveContainingCallableElement(referenceResult.Reference.GetTreeNode());
                            if (containingCallable == null) continue;

                            var added = seenCallKeys.Add(GetDeclaredElementIdentityKey(containingCallable));
                            if (!added) continue;

                            callElements.Add(containingCallable);
                            if (callElements.Count >= effectiveLimit)
                                return FindExecution.Stop;
                        }

                        return FindExecution.Continue;
                    });
                _solution.GetPsiServices().Finder.FindReferences(
                    callableTarget.Element,
                    searchDomain,
                    consumer,
                    NoOpProgressIndicator.Instance,
                    false);
            }
            else if (request.Direction == "callees")
            {
                foreach (var node in EnumerateTreeNodes(callableTarget.TraversalRoot))
                {
                    foreach (var reference in node.GetReferences())
                    {
                        var invokedCallable = ResolveCallableTarget(reference.Resolve().DeclaredElement)?.Element;
                        if (invokedCallable != null && !Equals(invokedCallable, callableTarget.Element))
                            callElements.Add(invokedCallable);
                    }
                }
            }

            var orderedCalls = OrderDeclaredElementsDeterministically(callElements)
                .Take(effectiveLimit)
                .Select(ToSymbolInfo)
                .ToList();

            return (RdCallHierarchyResult?)new RdCallHierarchyResult(root, orderedCalls);
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

    private IDeclaredElement? ResolveTargetForFindReferences(Lifetime lt, RdSemanticTarget target, string scope)
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
            return ResolveSymbolForFindReferences(lt, target.Language, target.Symbol, scope);
        }

        return null;
    }

    private static int GetEffectiveResultLimit(int requestedLimit)
    {
        if (requestedLimit <= 0)
            return MaxResults;

        return Math.Min(requestedLimit, MaxResults);
    }

    private static CallHierarchyResolutionPlan BuildCallHierarchyResolutionPlan(RdSemanticTarget target)
    {
        var isPositionTarget = !string.IsNullOrWhiteSpace(target.FilePath) &&
                               target.Line.HasValue &&
                               target.Column.HasValue;
        var fileExtension = Path.GetExtension(target.FilePath ?? string.Empty);
        var isFSharpPositionTarget = isPositionTarget &&
                                     FSharpProjectFileExtensions.Contains(fileExtension, StringComparer.OrdinalIgnoreCase);

        return new CallHierarchyResolutionPlan(
            isFSharpPositionTarget,
            UseDeclarationOnlyFastPath: isFSharpPositionTarget);
    }

    private static IEnumerable<IDeclaredElement> OrderDeclaredElementsDeterministically(
        IEnumerable<IDeclaredElement> elements)
    {
        return elements
            .GroupBy(GetDeclaredElementIdentityKey)
            .Select(group => group.First())
            .OrderBy(BestDeclarationRank)
            .ThenBy(GetQualifiedName, StringComparer.OrdinalIgnoreCase)
            .ThenBy(GetMemberSignatureSortKey, StringComparer.OrdinalIgnoreCase)
            .ThenBy(GetDeclarationPath, StringComparer.OrdinalIgnoreCase);
    }

    private static string GetDeclaredElementIdentityKey(IDeclaredElement element)
    {
        return $"{GetQualifiedName(element)}:{GetMemberSignatureSortKey(element)}:{GetDeclarationPath(element)}";
    }

    private static CallableTarget? ResolveCallableTarget(IDeclaredElement? element)
    {
        if (element == null || !IsSupportedCallableElement(element))
            return null;

        if (element is IMethod method)
        {
            var methodDeclaration = method.GetDeclarations().OfType<IMethodDeclaration>().FirstOrDefault();
            if (methodDeclaration != null)
                return new CallableTarget(method, methodDeclaration.Body as ITreeNode ?? methodDeclaration);
        }

        var declaration = element.GetDeclarations().FirstOrDefault();
        if (declaration == null || !IsSupportedCallableDeclaration(declaration, element))
            return null;

        return new CallableTarget(element, declaration);
    }

    private CallableTarget? ResolveCallableDeclarationTargetAt(RdSourcePosition position)
    {
        var declarationNode = ResolveCallableDeclarationNodeAt(position);
        if (declarationNode == null || !TryGetDeclaredElement(declarationNode, out var declaredElement))
            return null;

        return ResolveCallableTarget(declaredElement);
    }

    private ITreeNode? ResolveCallableDeclarationNodeAt(RdSourcePosition position)
    {
        var psiFile = GetPsiFileForPath(position.FilePath);
        if (psiFile == null) return null;

        var sourceFile = psiFile.GetSourceFile();
        var document = sourceFile?.Document;
        if (document == null) return null;

        var line = Math.Max(0, position.Line - 1);
        var col = Math.Max(0, position.Column - 1);
        var offset = document.GetLineStartOffset((Int32<DocLine>)line) + col;

        foreach (var candidateOffset in CandidateOffsets(offset))
        {
            var node = psiFile.FindNodeAt(TreeTextRange.FromLength(new TreeOffset(candidateOffset), 1));
            if (node == null) continue;

            var callableDeclaration = ResolveNearestCallableDeclarationNode(node);
            if (callableDeclaration != null)
                return callableDeclaration;
        }

        return null;
    }

    private static ITreeNode? ResolveNearestCallableDeclarationNode(ITreeNode node)
    {
        for (var current = node; current != null; current = current.Parent)
        {
            if (current is not IDeclaration declaration || declaration.DeclaredElement == null)
                continue;

            if (IsSupportedCallableDeclaration(declaration, declaration.DeclaredElement))
                return declaration;
        }

        return null;
    }

    private static bool IsSupportedCallableElement(IDeclaredElement element)
    {
        if (element is IMethod || element is IFunction)
            return true;

        return element is IParametersOwner &&
               element is not IProperty &&
               element is not IField &&
               element is not IEvent;
    }

    private static bool IsSupportedCallableDeclaration(IDeclaration declaration, IDeclaredElement element)
    {
        return declaration is IMethodDeclaration ||
               declaration is IFunctionDeclaration ||
               IsSupportedCallableElement(element);
    }

    private static IDeclaredElement? ResolveContainingCallableElement(ITreeNode node)
    {
        for (var current = node; current != null; current = current.Parent)
        {
            if (current is IMethodDeclaration methodDeclaration && methodDeclaration.DeclaredElement != null)
                return methodDeclaration.DeclaredElement;

            if (current is IFunctionDeclaration functionDeclaration)
            {
                var declaredElement = ((IDeclaration)functionDeclaration).DeclaredElement;
                if (declaredElement != null && IsSupportedCallableElement(declaredElement))
                    return declaredElement;
            }

            if (current is IDeclaration declaration && declaration.DeclaredElement != null &&
                IsSupportedCallableElement(declaration.DeclaredElement))
            {
                return declaration.DeclaredElement;
            }
        }

        return null;
    }

    private static IEnumerable<ITreeNode> EnumerateTreeNodes(ITreeNode root)
    {
        yield return root;

        foreach (var child in root.Children())
        {
            foreach (var descendant in EnumerateTreeNodes(child))
                yield return descendant;
        }
    }

    private IDeclaredElement? ResolveSymbol(string? language, string symbol)
    {
        return ResolveSymbolIndexed(language, symbol).TryGetElement();
    }

    private IDeclaredElement? ResolveSymbolForFindReferences(Lifetime lt, string? language, string symbol, string scope)
    {
        EnsureFindReferencesIndexedTargetIsSupported(language, symbol, scope);

        var parseResult = ParseIndexedSymbol(language, symbol);
        if (!parseResult.IsSuccess)
            return parseResult.ToResolution().TryGetElement();

        var parsed = parseResult.Symbol!;
        var plan = BuildFindReferencesResolutionPlan(scope, parsed);
        if (!plan.UseProjectQualifiedTypeLookup)
            return ResolveSymbolIndexed(language, symbol).TryGetElement();

        var projectCandidates = EnumerateProjectQualifiedTypeElements(
                lt,
                parsed.ContainerQualifiedName,
                plan.AllowedProjectFileExtensions)
            .Where(type => MatchesLanguage(type, parsed.Language))
            .Where(type => MatchesQualifiedName(GetQualifiedName(type), parsed.ContainerQualifiedName, "exact"))
            .Cast<IDeclaredElement>()
            .ToList();

        var resolution = ResolveSingleMatch(
            OrderDeclaredElementsDeterministically(projectCandidates).ToList(),
            $"Multiple Rider declarations match container '{parsed.ContainerQualifiedName}'.",
            $"No Rider declaration matches container '{parsed.ContainerQualifiedName}'.");

        if (resolution.TryGetElement() != null)
            return resolution.TryGetElement();

        if (!plan.AllowLibraryFallback)
            return null;

        return ResolveSymbolIndexed(language, symbol).TryGetElement();
    }

    private IndexedSymbolResolution ResolveSymbolIndexed(string? language, string symbol)
    {
        var parseResult = ParseIndexedSymbol(language, symbol);
        if (!parseResult.IsSuccess)
            return parseResult.ToResolution();

        var parsed = parseResult.Symbol!;
        var containers = ResolveContainerCandidates(parsed.Language, parsed.ContainerQualifiedName);
        if (containers.Count == 0)
        {
            return IndexedSymbolResolution.Unresolved(
                $"No Rider declaration matches container '{parsed.ContainerQualifiedName}'.");
        }

        if (parsed.MemberName == null)
        {
            return ResolveSingleMatch(
                containers,
                $"Multiple Rider declarations match container '{parsed.ContainerQualifiedName}'.",
                $"No Rider declaration matches container '{parsed.ContainerQualifiedName}'.");
        }

        var supportedContainers = containers
            .OfType<ITypeElement>()
            .ToList();

        if (supportedContainers.Count == 0)
        {
            return IndexedSymbolResolution.Unsupported(
                $"Container '{parsed.ContainerQualifiedName}' does not support member lookup.");
        }

        var members = supportedContainers
            .SelectMany(container => ResolveMemberCandidates(container, parsed))
            .Distinct()
            .OrderBy(BestDeclarationRank)
            .ThenBy(GetQualifiedName, StringComparer.OrdinalIgnoreCase)
            .ThenBy(GetMemberSignatureSortKey, StringComparer.OrdinalIgnoreCase)
            .ThenBy(GetDeclarationPath, StringComparer.OrdinalIgnoreCase)
            .ToList();

        return ResolveSingleMatch(
            members,
            $"Multiple Rider declarations match symbol '{symbol}'.",
            $"No Rider declaration matches symbol '{symbol}'.");
    }

    private List<IDeclaredElement> ResolveContainerCandidates(string language, string containerQualifiedName)
    {
        var normalizedContainer = NormalizeQualifiedName(containerQualifiedName);
        var projectMatches = EnumerateProjectPsiModules(Lifetime.Eternal, null)
            .Distinct()
            .SelectMany(module => ResolveContainerCandidatesFromScope(
                _solution.GetPsiServices().Symbols.GetSymbolScope(module, true, false),
                normalizedContainer,
                language))
            .Distinct()
            .OrderBy(BestDeclarationRank)
            .ThenBy(GetQualifiedName, StringComparer.OrdinalIgnoreCase)
            .ThenBy(GetDeclarationPath, StringComparer.OrdinalIgnoreCase)
            .ToList();

        if (projectMatches.Count > 0)
            return projectMatches;

        return new[] { LibrarySymbolScope.REFERENCED, LibrarySymbolScope.TRANSITIVE, LibrarySymbolScope.FULL }
            .SelectMany(scopeKind => ResolveContainerCandidatesFromScope(
                _solution.GetPsiServices().Symbols.GetSymbolScope(scopeKind, false),
                normalizedContainer,
                language))
            .Distinct()
            .OrderBy(BestDeclarationRank)
            .ThenBy(GetQualifiedName, StringComparer.OrdinalIgnoreCase)
            .ThenBy(GetDeclarationPath, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    private IEnumerable<IDeclaredElement> ResolveContainerCandidatesFromScope(
        ISymbolScope symbolScope,
        string normalizedContainer,
        string language)
    {
        foreach (var candidate in EnumerateQualifiedNameCandidates(normalizedContainer)
                     .SelectMany(symbolScope.GetElementsByQualifiedName)
                     .Where(element => MatchesLanguage(element, language))
                     .Where(element => NormalizeQualifiedName(GetQualifiedName(element))
                         .Equals(normalizedContainer, StringComparison.OrdinalIgnoreCase)))
        {
            yield return candidate;
        }
    }

    private static IEnumerable<string> EnumerateQualifiedNameCandidates(string normalizedQualifiedName)
    {
        yield return normalizedQualifiedName;

        var lastDot = normalizedQualifiedName.LastIndexOf('.');
        if (lastDot > 0)
        {
            yield return normalizedQualifiedName.Substring(0, lastDot) + "+" + normalizedQualifiedName[(lastDot + 1)..];
        }
    }

    private static IndexedSymbolResolution ResolveSingleMatch(
        IReadOnlyList<IDeclaredElement> candidates,
        string ambiguousMessage,
        string unresolvedMessage)
    {
        if (candidates.Count == 0)
            return IndexedSymbolResolution.Unresolved(unresolvedMessage);

        var preferredTypeCandidates = candidates
            .OfType<ITypeElement>()
            .Cast<IDeclaredElement>()
            .ToList();

        if (preferredTypeCandidates.Count > 0)
        {
            if (preferredTypeCandidates.Count == 1)
                return IndexedSymbolResolution.Success(preferredTypeCandidates[0]);

            return IndexedSymbolResolution.Ambiguous(ambiguousMessage);
        }

        if (candidates.All(candidate => candidate is INamespace))
            return IndexedSymbolResolution.Unresolved(unresolvedMessage);

        if (candidates.Count == 1)
            return IndexedSymbolResolution.Success(candidates[0]);

        return IndexedSymbolResolution.Ambiguous(ambiguousMessage);
    }

    private static IEnumerable<IDeclaredElement> ResolveMemberCandidates(ITypeElement container, ParsedRiderSymbol parsed)
    {
        foreach (var member in container.GetMembers())
        {
            if (!MatchesMemberName(member, parsed))
                continue;

            if (!MatchesParameterContract(member, parsed.ParameterTypes))
                continue;

            yield return member;
        }
    }

    private static bool MatchesMemberName(ITypeMember member, ParsedRiderSymbol parsed)
    {
        if (parsed.IsConstructor)
            return member is IConstructor;

        return NormalizeSimpleName(member.ShortName)
            .Equals(NormalizeSimpleName(parsed.MemberName!), StringComparison.OrdinalIgnoreCase);
    }

    private static bool MatchesParameterContract(IDeclaredElement element, IReadOnlyList<string>? parameterTypes)
    {
        if (parameterTypes == null)
            return true;

        if (element is not IParametersOwner parametersOwner)
            return false;

        var parameters = parametersOwner.Parameters;
        if (parameters.Count != parameterTypes.Count)
            return false;

        for (var i = 0; i < parameters.Count; i++)
        {
            var parameter = parameters[i];
            if (!ParameterTypeMatches(parameter.Type, parameterTypes[i], element.PresentationLanguage?.Name))
                return false;
        }

        return true;
    }

    private static bool ParameterTypeMatches(IType declaredType, string requestedType, string? languageName)
    {
        var normalizedRequested = CanonicalizeTypeName(requestedType);
        if (string.IsNullOrEmpty(normalizedRequested))
            return false;

        foreach (var candidate in GetParameterComparisonNames(declaredType, languageName))
        {
            if (CanonicalizeTypeName(candidate).Equals(normalizedRequested, StringComparison.OrdinalIgnoreCase))
                return true;
        }

        return false;
    }

    private static IEnumerable<string> GetParameterComparisonNames(IType declaredType, string? languageName)
    {
        yield return declaredType.GetPresentableName(CSharpLanguage.Instance!);

        if (declaredType is IDeclaredType namedType && namedType.GetTypeElement() is { } typeElement)
        {
            yield return typeElement.ShortName;
            yield return typeElement.GetClrName().FullName;
        }
    }

    private static IndexedSymbolParseResult ParseIndexedSymbol(string? language, string symbol)
    {
        if (!IsSupportedRiderLanguage(language))
        {
            return IndexedSymbolParseResult.Unsupported(
                $"Rider indexed symbol resolution supports only C# and F#, got '{language ?? "<null>"}'.");
        }

        if (string.IsNullOrWhiteSpace(symbol))
            return IndexedSymbolParseResult.Invalid("Rider symbol cannot be empty.");

        var trimmed = symbol.Trim();
        if (!TrySplitMemberPart(trimmed, out var containerPart, out var memberPart, out var splitError))
            return IndexedSymbolParseResult.Invalid(splitError!);

        containerPart = NormalizeQualifiedName(containerPart);
        if (string.IsNullOrWhiteSpace(containerPart))
            return IndexedSymbolParseResult.Invalid("Rider symbol must include a container name.");

        if (memberPart == null)
            return IndexedSymbolParseResult.Success(new ParsedRiderSymbol(language!, containerPart, null, null, false));

        if (!TryParseMemberPart(containerPart, memberPart, out var parsedMemberName, out var parameterTypes, out var isConstructor, out var memberError))
            return IndexedSymbolParseResult.Invalid(memberError!);

        return IndexedSymbolParseResult.Success(new ParsedRiderSymbol(language!, containerPart, parsedMemberName, parameterTypes, isConstructor));
    }

    private static bool TrySplitMemberPart(string symbol, out string containerPart, out string? memberPart, out string? error)
    {
        containerPart = string.Empty;
        memberPart = null;
        error = null;

        var separatorIndex = symbol.IndexOf('#');
        if (separatorIndex < 0)
        {
            if (symbol.Contains('(') || symbol.Contains(')'))
            {
                error = "Type-only Rider symbols cannot contain a parameter list.";
                return false;
            }

            containerPart = symbol;
            return true;
        }

        if (symbol.IndexOf('#', separatorIndex + 1) >= 0)
        {
            error = "Rider symbols may contain at most one '#' separator.";
            return false;
        }

        containerPart = symbol[..separatorIndex];
        memberPart = symbol[(separatorIndex + 1)..];
        if (string.IsNullOrWhiteSpace(containerPart) || string.IsNullOrWhiteSpace(memberPart))
        {
            error = "Rider member symbols must include both container and member names.";
            return false;
        }

        return true;
    }

    private static bool TryParseMemberPart(
        string containerPart,
        string memberPart,
        out string memberName,
        out IReadOnlyList<string>? parameterTypes,
        out bool isConstructor,
        out string? error)
    {
        memberName = string.Empty;
        parameterTypes = null;
        isConstructor = false;
        error = null;

        var openParenIndex = memberPart.IndexOf('(');
        if (openParenIndex < 0)
        {
            memberName = memberPart.Trim();
        }
        else
        {
            var closeParenIndex = memberPart.LastIndexOf(')');
            if (closeParenIndex != memberPart.Length - 1 || closeParenIndex < openParenIndex)
            {
                error = "Rider symbol parameter list must end at the final ')'.";
                return false;
            }

            memberName = memberPart[..openParenIndex].Trim();
            var rawParameters = memberPart.Substring(openParenIndex + 1, closeParenIndex - openParenIndex - 1);
            if (!TryParseParameterList(rawParameters, out var parsedParameters, out error))
                return false;
            parameterTypes = parsedParameters;
        }

        if (string.IsNullOrWhiteSpace(memberName))
        {
            error = "Rider member symbol is missing the member name.";
            return false;
        }

        var normalizedMemberName = NormalizeSimpleName(memberName);
        var normalizedContainerLeaf = NormalizeSimpleName(GetContainerLeafName(containerPart));
        isConstructor = normalizedMemberName.Equals(".ctor", StringComparison.OrdinalIgnoreCase) ||
                        normalizedMemberName.Equals(normalizedContainerLeaf, StringComparison.OrdinalIgnoreCase);
        memberName = isConstructor ? ".ctor" : memberName;
        return true;
    }

    private static bool TryParseParameterList(string rawParameters, out IReadOnlyList<string> parameterTypes, out string? error)
    {
        parameterTypes = Array.Empty<string>();
        error = null;

        if (string.IsNullOrWhiteSpace(rawParameters))
            return true;

        var parts = new List<string>();
        var start = 0;
        var angleDepth = 0;
        var bracketDepth = 0;

        for (var i = 0; i < rawParameters.Length; i++)
        {
            switch (rawParameters[i])
            {
                case '<': angleDepth++; break;
                case '>': angleDepth--; break;
                case '[': bracketDepth++; break;
                case ']': bracketDepth--; break;
                case ',' when angleDepth == 0 && bracketDepth == 0:
                    var segment = rawParameters.Substring(start, i - start).Trim();
                    if (string.IsNullOrWhiteSpace(segment))
                    {
                        error = "Rider symbol parameter list contains an empty parameter type.";
                        return false;
                    }
                    parts.Add(segment);
                    start = i + 1;
                    break;
            }

            if (angleDepth < 0 || bracketDepth < 0)
            {
                error = "Rider symbol parameter list is malformed.";
                return false;
            }
        }

        if (angleDepth != 0 || bracketDepth != 0)
        {
            error = "Rider symbol parameter list is malformed.";
            return false;
        }

        var lastSegment = rawParameters[start..].Trim();
        if (string.IsNullOrWhiteSpace(lastSegment))
        {
            error = "Rider symbol parameter list contains an empty parameter type.";
            return false;
        }

        parts.Add(lastSegment);
        parameterTypes = parts;
        return true;
    }

    private static bool IsSupportedRiderLanguage(string? language)
    {
        return language != null &&
               (language.Equals("C#", StringComparison.OrdinalIgnoreCase) ||
                language.Equals("F#", StringComparison.OrdinalIgnoreCase));
    }

    private static string GetContainerLeafName(string containerQualifiedName)
    {
        var normalized = NormalizeQualifiedName(containerQualifiedName);
        var separatorIndex = Math.Max(normalized.LastIndexOf('.'), normalized.LastIndexOf('+'));
        return separatorIndex >= 0 ? normalized[(separatorIndex + 1)..] : normalized;
    }

    private static string NormalizeQualifiedName(string value)
    {
        var withoutGlobalPrefix = value.Trim()
            .Replace("global::", string.Empty, StringComparison.OrdinalIgnoreCase)
            .Replace("global.", string.Empty, StringComparison.OrdinalIgnoreCase);
        return CanonicalizeTypeName(withoutGlobalPrefix);
    }

    private static string NormalizeSimpleName(string value)
    {
        var normalized = RemoveGenericPayload(value)
            .Replace("::", ".", StringComparison.Ordinal)
            .Replace(" ", string.Empty, StringComparison.Ordinal)
            .Trim();
        return Regex.Replace(normalized, @"`\d+", string.Empty);
    }

    private static string CanonicalizeTypeName(string value)
    {
        var normalized = NormalizeSimpleName(value)
            .Replace("+", ".", StringComparison.Ordinal);

        return BuiltInTypeAliases.TryGetValue(normalized, out var canonical)
            ? canonical
            : normalized;
    }

    private static string RemoveGenericPayload(string value)
    {
        if (string.IsNullOrEmpty(value))
            return string.Empty;

        var result = new System.Text.StringBuilder(value.Length);
        var depth = 0;
        foreach (var ch in value)
        {
            switch (ch)
            {
                case '<':
                    depth++;
                    break;
                case '>':
                    if (depth > 0) depth--;
                    break;
                default:
                    if (depth == 0)
                        result.Append(ch);
                    break;
            }
        }

        return result.ToString();
    }

    private static string GetMemberSignatureSortKey(IDeclaredElement element)
    {
        if (element is not IParametersOwner parametersOwner)
            return element.ShortName;

        var parameterNames = parametersOwner.Parameters
            .Select(parameter => CanonicalizeTypeName(parameter.Type.GetPresentableName(CSharpLanguage.Instance!)));
        return $"{element.ShortName}({string.Join(",", parameterNames)})";
    }

    private static readonly IReadOnlyDictionary<string, string> BuiltInTypeAliases =
        new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["bool"] = "System.Boolean",
            ["byte"] = "System.Byte",
            ["sbyte"] = "System.SByte",
            ["short"] = "System.Int16",
            ["ushort"] = "System.UInt16",
            ["int"] = "System.Int32",
            ["uint"] = "System.UInt32",
            ["long"] = "System.Int64",
            ["ulong"] = "System.UInt64",
            ["float"] = "System.Single",
            ["double"] = "System.Double",
            ["decimal"] = "System.Decimal",
            ["char"] = "System.Char",
            ["string"] = "System.String",
            ["object"] = "System.Object",
            ["unit"] = "Microsoft.FSharp.Core.Unit",
            ["nativeint"] = "System.IntPtr",
            ["unativeint"] = "System.UIntPtr"
        };

    private IEnumerable<IDeclaredElement> EnumerateDeclaredElements()
    {
        foreach (var psiFile in EnumerateProjectPsiFiles(Lifetime.Eternal, null))
        {
            var elements = new List<IDeclaredElement>();
            CollectDeclaredElements(psiFile, elements);
            foreach (var element in elements) yield return element;
        }
    }

    private static readonly string[] FSharpProjectFileExtensions = [".fs", ".fsi", ".fsx"];

    private sealed record CallHierarchyResolutionPlan(
        bool IsFSharpPositionTarget,
        bool UseDeclarationOnlyFastPath);

    private IEnumerable<ITypeElement> EnumerateDeclaredTypeElements(Lifetime lt, IReadOnlyList<string>? allowedProjectFileExtensions)
    {
        foreach (var psiFile in EnumerateProjectPsiFiles(lt, allowedProjectFileExtensions))
        {
            EnsureLifetimeAlive(lt);
            var elements = new List<IDeclaredElement>();
            CollectDeclaredElements(psiFile, elements);
            foreach (var element in elements.OfType<ITypeElement>())
                yield return element;
        }
    }

    private IEnumerable<ITypeElement> EnumerateProjectQualifiedTypeElements(
        Lifetime lt,
        string qualifiedName,
        IReadOnlyList<string>? allowedProjectFileExtensions)
    {
        var seenModules = new HashSet<IPsiModule>();
        foreach (var module in EnumerateProjectPsiModules(lt, allowedProjectFileExtensions).Where(module => seenModules.Add(module)))
        {
            EnsureLifetimeAlive(lt);
            var symbolScope = _solution.GetPsiServices().Symbols.GetSymbolScope(module, true, false);
            foreach (var element in EnumerateQualifiedTypeCandidates(
                         qualifiedName,
                         candidate => symbolScope.GetElementsByQualifiedName(candidate).OfType<ITypeElement>()))
                yield return element;
        }
    }

    private static IEnumerable<ITypeElement> EnumerateQualifiedTypeCandidates(
        string qualifiedName,
        Func<string, IEnumerable<ITypeElement>> lookup)
    {
        var seen = new HashSet<ITypeElement>();
        foreach (var candidate in EnumerateQualifiedNameCandidates(qualifiedName))
        {
            foreach (var element in lookup(candidate))
            {
                if (seen.Add(element))
                    yield return element;
            }
        }
    }

    private IEnumerable<IFile> EnumerateProjectPsiFiles(Lifetime lt, IReadOnlyList<string>? allowedProjectFileExtensions)
    {
        foreach (var project in _solution.GetAllProjects())
        {
            EnsureLifetimeAlive(lt);
            foreach (var projectFile in project.GetAllProjectFiles())
            {
                EnsureLifetimeAlive(lt);
                foreach (var sourceFile in projectFile.ToSourceFiles())
                {
                    EnsureLifetimeAlive(lt);
                    if (!MatchesProjectFileExtension(sourceFile.GetLocation().FullPath, allowedProjectFileExtensions))
                        continue;

                    var psiFile = sourceFile.GetPrimaryPsiFile();
                    if (psiFile != null) yield return psiFile;
                }
            }
        }
    }

    private IEnumerable<IPsiModule> EnumerateProjectPsiModules(Lifetime lt, IReadOnlyList<string>? allowedProjectFileExtensions)
    {
        foreach (var project in _solution.GetAllProjects())
        {
            EnsureLifetimeAlive(lt);
            foreach (var projectFile in project.GetAllProjectFiles())
            {
                EnsureLifetimeAlive(lt);
                foreach (var sourceFile in projectFile.ToSourceFiles())
                {
                    EnsureLifetimeAlive(lt);
                    if (!MatchesProjectFileExtension(sourceFile.GetLocation().FullPath, allowedProjectFileExtensions))
                        continue;

                    yield return sourceFile.PsiModule;
                }
            }
        }
    }

    private static void CollectDeclaredElements(ITreeNode node, List<IDeclaredElement> elements)
    {
        if (TryGetDeclaredElement(node, out var declaredElement))
            elements.Add(declaredElement);

        foreach (var child in node.Children())
            CollectDeclaredElements(child, elements);
    }

    private static bool MatchesLanguage(IDeclaredElement element, string? language)
    {
        var normalizedLanguage = NormalizeLanguage(language);
        if (normalizedLanguage == null) return true;
        var presentationLanguage = element.PresentationLanguage?.Name;
        if (presentationLanguage != null && presentationLanguage.Equals(normalizedLanguage, StringComparison.OrdinalIgnoreCase))
            return true;

        var filePath = PickPreferredDeclaration(element)?.GetSourceFile()?.GetLocation().FullPath ?? "";
        if (string.IsNullOrWhiteSpace(filePath))
            return normalizedLanguage.Equals("C#", StringComparison.OrdinalIgnoreCase);

        return normalizedLanguage.Equals("C#", StringComparison.OrdinalIgnoreCase) && filePath.EndsWith(".cs", StringComparison.OrdinalIgnoreCase) ||
               normalizedLanguage.Equals("F#", StringComparison.OrdinalIgnoreCase) && (
                   filePath.EndsWith(".fs", StringComparison.OrdinalIgnoreCase) ||
                   filePath.EndsWith(".fsi", StringComparison.OrdinalIgnoreCase) ||
                   filePath.EndsWith(".fsx", StringComparison.OrdinalIgnoreCase));
    }

    private static string? NormalizeLanguage(string? language)
    {
        if (string.IsNullOrWhiteSpace(language))
            return null;

        if (language.Equals("C#", StringComparison.OrdinalIgnoreCase) ||
            language.Equals("CSharp", StringComparison.OrdinalIgnoreCase))
            return "C#";

        if (language.Equals("F#", StringComparison.OrdinalIgnoreCase) ||
            language.Equals("FSharp", StringComparison.OrdinalIgnoreCase))
            return "F#";

        return language;
    }

    private static IReadOnlyList<string>? GetProjectFileExtensions(string? normalizedLanguage)
    {
        return normalizedLanguage switch
        {
            "C#" => new[] { ".cs" },
            "F#" => new[] { ".fs", ".fsi", ".fsx" },
            _ => null
        };
    }

    private static bool MatchesProjectFileExtension(string path, IReadOnlyList<string>? allowedProjectFileExtensions)
    {
        if (allowedProjectFileExtensions == null || allowedProjectFileExtensions.Count == 0)
            return true;

        return allowedProjectFileExtensions.Any(extension => path.EndsWith(extension, StringComparison.OrdinalIgnoreCase));
    }

    private static void EnsureLifetimeAlive(Lifetime lt)
    {
        if (!lt.IsAlive)
            throw new OperationCanceledException("Index MCP Rider backend request was cancelled.");
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

    private static bool MatchesQualifiedName(string declaredName, string query, string matchMode)
    {
        if (MatchesName(declaredName, query, matchMode))
            return true;

        return MatchesName(NormalizeQualifiedName(declaredName), NormalizeQualifiedName(query), matchMode);
    }

    private static int MatchRank(string name, string query, string matchMode)
    {
        if (name.Equals(query, StringComparison.OrdinalIgnoreCase)) return 0;
        if (name.StartsWith(query, StringComparison.OrdinalIgnoreCase)) return 1;
        if (GetCamelCase(name).Equals(query, StringComparison.OrdinalIgnoreCase)) return 2;
        if (matchMode.Equals("exact", StringComparison.OrdinalIgnoreCase)) return 3;
        return 4;
    }

    private static bool MatchesScope(IDeclaredElement element, string scope) =>
        MatchesScope(GetDeclarationPath(element), scope);

    private static bool MatchesScope(string? path, string scope)
    {
        if (string.IsNullOrWhiteSpace(path)) return true;
        var isTest = IsTestPath(path);
        return scope switch
        {
            "project_test_files" => isTest,
            "project_production_files" => !isTest,
            _ => true
        };
    }

    private static string? GetDeclarationPath(IDeclaredElement element) =>
        PickPreferredDeclaration(element)?.GetSourceFile()?.GetLocation().FullPath;

    // Lower rank = better. Used to prefer hand-written code-behind partials
    // (e.g. MainWindow.axaml.cs) over generator output and over synthetic
    // XAML/AXAML partials whose source file may have an empty path.
    //
    // Detection layers (cheapest first):
    //   - empty path                                                -> 4
    //   - PSI source file IsGeneratedFile / IsNonUserFile           -> 3
    //   - path heuristic (belt-and-braces): /obj/, /generated/,     -> 3
    //     *.g.cs, *.g.i.cs (IsGeneratedFile is the authoritative
    //     signal; broad substring patterns avoided to prevent
    //     false positives on legitimate user folders)
    //   - .cs / .fs / .fsi / .fsx hand-written file                 -> 0
    //   - any other on-disk path (XAML-paired synthetic partial,
    //     non-source documents)                                     -> 1
    private static int DeclarationRank(IDeclaration? declaration)
    {
        var sourceFile = declaration?.GetSourceFile();
        var path = sourceFile?.GetLocation().FullPath ?? "";
        if (string.IsNullOrEmpty(path)) return 4;

        var props = sourceFile!.Properties;
        if (props.IsGeneratedFile || props.IsNonUserFile) return 3;

        var normalized = path.Replace('\\', '/');
        // Path heuristics are belt-and-braces (IsGeneratedFile/IsNonUserFile
        // is the authoritative signal above). Keep only patterns that are
        // unambiguous: a project would not contain a hand-written file under
        // /obj/ or /generated/, and *.g.cs / *.g.i.cs are conventional
        // generator output extensions. Avoid broad substrings like
        // ".namegenerator/" that could match legitimate user folders.
        var isGenerated = normalized.Contains("/obj/", StringComparison.OrdinalIgnoreCase) ||
                          normalized.Contains("/generated/", StringComparison.OrdinalIgnoreCase) ||
                          path.EndsWith(".g.cs", StringComparison.OrdinalIgnoreCase) ||
                          path.EndsWith(".g.i.cs", StringComparison.OrdinalIgnoreCase);
        if (isGenerated) return 3;

        var isCsOrFs = path.EndsWith(".cs", StringComparison.OrdinalIgnoreCase) ||
                       path.EndsWith(".fs", StringComparison.OrdinalIgnoreCase) ||
                       path.EndsWith(".fsi", StringComparison.OrdinalIgnoreCase) ||
                       path.EndsWith(".fsx", StringComparison.OrdinalIgnoreCase);
        return isCsOrFs ? 0 : 1;
    }

    private static int BestDeclarationRank(IDeclaredElement element) =>
        element.GetDeclarations().Select(DeclarationRank).DefaultIfEmpty(4).Min();

    private static IDeclaration? PickPreferredDeclaration(IDeclaredElement element) =>
        element.GetDeclarations().OrderBy(DeclarationRank).FirstOrDefault();

    private static bool IsTestPath(string path)
    {
        var normalized = path.Replace('\\', '/');
        return normalized.Contains(".Tests/", StringComparison.OrdinalIgnoreCase) ||
               normalized.Contains("/Tests/", StringComparison.OrdinalIgnoreCase) ||
               normalized.Contains(".Test/", StringComparison.OrdinalIgnoreCase) ||
               normalized.Contains("/Test/", StringComparison.OrdinalIgnoreCase);
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

        var sourceFile = declaration.GetSourceFile();
        var document = sourceFile?.Document;
        if (document == null) return text.Split('\n').FirstOrDefault() ?? text;

        // Compact preview window: 2 lines above and below the declaration's start line, line-numbered.
        // The ±2 window is intentional and independent of `maxPreviewLines` (which only governs the
        // truncation of the full-element preview above). This gives just enough context for an LLM to
        // identify the symbol's signature without dumping the whole class body.
        const int CompactPreviewContextLines = 2;
        var startLine = (int)document.GetCoordsByOffset(declaration.GetNavigationRange().StartOffset.Offset).Line;
        var sourceLines = document.GetText().Split('\n');
        var previewStartLine = Math.Max(0, startLine - CompactPreviewContextLines);
        var previewEndLine = Math.Min(sourceLines.Length - 1, startLine + CompactPreviewContextLines);
        var previewLines = new List<string>();
        for (var line = previewStartLine; line <= previewEndLine; line++)
        {
            previewLines.Add($"{line + 1}: {sourceLines[line].TrimEnd('\r')}");
        }

        return string.Join("\n", previewLines);
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
        return ResolveDeclaredElementAt(position, requireConsistentLocalDeclaration: false);
    }

    /// <summary>
    /// Position-based PSI resolver. When <paramref name="requireConsistentLocalDeclaration"/> is
    /// true, additionally enforces that if the symbol is resolved via a local <see cref="IDeclaration"/>
    /// at the requested position, the local declaration's textual name matches the resolved
    /// <see cref="IDeclaredElement.ShortName"/>. This guards against partial-class drift (e.g. an
    /// AXAML/code-behind pair whose names have desynced) where the resolver would otherwise return a
    /// unified type element whose ShortName disagrees with the local source text — a footgun for
    /// rename callers that expect to act on what's literally at the requested coordinates.
    /// </summary>
    private IDeclaredElement? ResolveDeclaredElementAt(RdSourcePosition position, bool requireConsistentLocalDeclaration)
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
            if (resolved == null) continue;

            if (requireConsistentLocalDeclaration)
            {
                var localName = GetContainingDeclarationName(node);
                if (!string.IsNullOrEmpty(localName) &&
                    !string.Equals(localName, resolved.ShortName, StringComparison.Ordinal))
                {
                    // The local declaration token at this position uses a different name than the
                    // resolved element. Skip this candidate; another offset (or a higher caller)
                    // will surface a clearer error.
                    continue;
                }
            }

            return resolved;
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

        var declarationNode = node.GetContainingNode<IDeclaration>() as ITreeNode
                              ?? node.GetContainingNode<ITypeDeclaration>() as ITreeNode;
        return declarationNode != null && TryGetDeclaredElement(declarationNode, out var declaredElement)
            ? declaredElement
            : null;
    }

    private static bool TryGetDeclaredElement(ITreeNode node, out IDeclaredElement declaredElement)
    {
        if (node is IDeclaration declaration && declaration.DeclaredElement != null)
        {
            declaredElement = declaration.DeclaredElement;
            return true;
        }

        if (node is ITypeDeclaration typeDeclaration && typeDeclaration.DeclaredElement != null)
        {
            declaredElement = typeDeclaration.DeclaredElement;
            return true;
        }

        declaredElement = null!;
        return false;
    }

    private static string? GetContainingDeclarationName(ITreeNode node)
    {
        var declaration = node.GetContainingNode<IDeclaration>();
        if (declaration != null) return declaration.DeclaredName;

        var typeDeclaration = node.GetContainingNode<ITypeDeclaration>();
        return typeDeclaration?.DeclaredName;
    }

    private ITreeNode? ResolveDeclarationNodeAt(RdSourcePosition position)
    {
        var psiFile = GetPsiFileForPath(position.FilePath);
        if (psiFile == null) return null;

        var sourceFile = psiFile.GetSourceFile();
        var document = sourceFile?.Document;
        if (document == null) return null;

        var line = Math.Max(0, position.Line - 1);
        var col = Math.Max(0, position.Column - 1);
        var offset = document.GetLineStartOffset((Int32<DocLine>)line) + col;

        foreach (var candidateOffset in CandidateOffsets(offset))
        {
            var node = psiFile.FindNodeAt(TreeTextRange.FromLength(new TreeOffset(candidateOffset), 1));
            if (node == null) continue;

            var typeDeclaration = node.GetContainingNode<ITypeDeclaration>();
            if (typeDeclaration != null)
                return typeDeclaration as ITreeNode;

            var declaration = node.GetContainingNode<IDeclaration>();
            if (declaration != null)
                return declaration as ITreeNode;
        }

        return null;
    }

    private static string? GetDeclarationNodeName(ITreeNode? node)
    {
        if (node is ITypeDeclaration typeDeclaration) return typeDeclaration.DeclaredName;
        if (node is IDeclaration declaration) return declaration.DeclaredName;
        return null;
    }

    private List<JetBrains.ReSharper.Psi.Resolve.IReference> FindReferences(Lifetime lt, IDeclaredElement element)
    {
        var searchDomain = _solution.GetPsiServices().SearchDomainFactory
            .CreateSearchDomain(_solution, false);
        var referenceResults = new List<FindResult>();
        var consumer = new FindResultConsumer<List<FindResult>>(
            result => new List<FindResult> { result },
            results =>
            {
                EnsureLifetimeAlive(lt);
                foreach (var result in results)
                {
                    EnsureLifetimeAlive(lt);
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

    private List<string> GetPotentiallyAffectedFiles(IDeclaredElement element)
    {
        return element.GetDeclarations()
            .Select(declaration => declaration.GetSourceFile()?.GetLocation().FullPath)
            .Concat(FindReferences(Lifetime.Eternal, element)
                .Select(reference => reference.GetTreeNode().GetSourceFile()?.GetLocation().FullPath))
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Cast<string>()
            .ToList();
    }

    private static void AddAffectedFile(List<string> affectedFiles, string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return;
        if (affectedFiles.Contains(path, StringComparer.OrdinalIgnoreCase)) return;
        affectedFiles.Add(path);
    }

    /// <summary>
    /// Snapshot the per-file occurrence count of <paramref name="identifier"/> as a standalone
    /// identifier (word-boundary regex) before a rename runs. Used by
    /// <see cref="RenameChangedAffectedFiles"/> to detect whether the rename actually rewrote any
    /// occurrences. Files that don't exist on disk yet are skipped (count == 0 is recorded only for
    /// existing files; missing files surface in <c>RenameChangedAffectedFiles</c> as "file disappeared").
    /// </summary>
    private static Dictionary<string, int> SnapshotIdentifierCounts(IEnumerable<string> affectedFiles, string identifier)
    {
        var pattern = BuildIdentifierRegex(identifier);
        var snapshot = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        foreach (var path in affectedFiles
                     .Where(p => !string.IsNullOrWhiteSpace(p))
                     .Distinct(StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                if (!File.Exists(path)) continue;
                var text = File.ReadAllText(path);
                snapshot[path] = pattern.Matches(text).Count;
            }
            catch (IOException)
            {
                // Treat unreadable files as "we cannot prove anything about them"; they are excluded
                // from the snapshot, so they cannot signal success either. The other affected files
                // will carry the oracle.
            }
        }
        return snapshot;
    }

    /// <summary>
    /// Polls the affected files for evidence that <paramref name="oldName"/> was rewritten. A file
    /// is considered "changed by rename" when either:
    /// <list type="bullet">
    ///   <item>the file no longer exists on disk (renamed/moved by the refactoring), OR</item>
    ///   <item>the current word-boundary occurrence count of <paramref name="oldName"/> is strictly
    ///         less than the snapshot count.</item>
    /// </list>
    /// Returns true as soon as ANY snapshotted file shows a decrease (or disappears).
    /// </summary>
    private static bool RenameChangedAffectedFiles(IReadOnlyDictionary<string, int> oldNameCountsBefore, string oldName)
    {
        if (oldNameCountsBefore.Count == 0) return false;

        var pattern = BuildIdentifierRegex(oldName);
        for (var attempt = 0; attempt < 50; attempt++)
        {
            if (oldNameCountsBefore.Any(kv =>
                {
                    if (!File.Exists(kv.Key)) return true; // disappeared = rename-on-disk
                    try
                    {
                        var text = File.ReadAllText(kv.Key);
                        return pattern.Matches(text).Count < kv.Value;
                    }
                    catch (IOException)
                    {
                        // Transient IO during ReSharper write; treat as "no evidence yet" and keep polling.
                        return false;
                    }
                }))
            {
                return true;
            }

            Task.Delay(100).GetAwaiter().GetResult();
        }

        return false;
    }

    private static Regex BuildIdentifierRegex(string identifier)
    {
        return new Regex(@"\b" + Regex.Escape(identifier) + @"\b", RegexOptions.Compiled);
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

        var psiFiles = new List<IFile>();
        foreach (var projectFile in projectFiles)
        {
            foreach (var sourceFile in projectFile.ToSourceFiles())
            {
                var psiFile = sourceFile.GetPrimaryPsiFile();
                if (psiFile != null && !psiFiles.Contains(psiFile))
                    psiFiles.Add(psiFile);
            }
        }

        return psiFiles.FirstOrDefault(ContainsDeclaredElement) ?? psiFiles.FirstOrDefault();
    }

    private static bool ContainsDeclaredElement(ITreeNode node)
    {
        if (TryGetDeclaredElement(node, out _)) return true;

        foreach (var child in node.Children())
        {
            if (ContainsDeclaredElement(child)) return true;
        }

        return false;
    }

    internal static RdSymbolInfo ToSymbolInfo(IDeclaredElement element)
    {
        var declaration = PickPreferredDeclaration(element);

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
        IFunction => "METHOD",
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
        if (TryGetDeclaredElement(node, out var element))
        {
            var kind = GetElementKind(element);
            if (kind != "UNKNOWN")
            {
                var sourceFile = node.GetSourceFile();
                var document = sourceFile?.Document;
                var line = 1;
                if (document != null)
                {
                    var offset = node.GetNavigationRange().StartOffset.Offset;
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
            CollectStructureNodes(child, nodes, TryGetDeclaredElement(node, out _) ? depth + 1 : depth);
        }
    }
}

internal sealed class FindTypesSearchPlan
{
    public FindTypesSearchPlan(
        IReadOnlyList<string>? allowedProjectFileExtensions,
        bool useProjectDeclaredTypeScan,
        bool useExactQualifiedProjectLookup,
        bool useIndexedTypeFallback)
    {
        AllowedProjectFileExtensions = allowedProjectFileExtensions;
        UseProjectDeclaredTypeScan = useProjectDeclaredTypeScan;
        UseExactQualifiedProjectLookup = useExactQualifiedProjectLookup;
        UseIndexedTypeFallback = useIndexedTypeFallback;
    }

    public IReadOnlyList<string>? AllowedProjectFileExtensions { get; }
    public bool UseProjectDeclaredTypeScan { get; }
    public bool UseExactQualifiedProjectLookup { get; }
    public bool UseIndexedTypeFallback { get; }
}

internal sealed class FindReferencesResolutionPlan
{
    public FindReferencesResolutionPlan(
        IReadOnlyList<string>? allowedProjectFileExtensions,
        bool useProjectQualifiedTypeLookup,
        bool allowLibraryFallback,
        bool rejectUnboundedReferenceSearch)
    {
        AllowedProjectFileExtensions = allowedProjectFileExtensions;
        UseProjectQualifiedTypeLookup = useProjectQualifiedTypeLookup;
        AllowLibraryFallback = allowLibraryFallback;
        RejectUnboundedReferenceSearch = rejectUnboundedReferenceSearch;
    }

    public IReadOnlyList<string>? AllowedProjectFileExtensions { get; }
    public bool UseProjectQualifiedTypeLookup { get; }
    public bool AllowLibraryFallback { get; }
    public bool RejectUnboundedReferenceSearch { get; }
}

internal sealed class CallableTarget
{
    public CallableTarget(IDeclaredElement element, ITreeNode traversalRoot)
    {
        Element = element;
        TraversalRoot = traversalRoot;
    }

    public IDeclaredElement Element { get; }
    public ITreeNode TraversalRoot { get; }
}

internal sealed class ParsedRiderSymbol
{
    public ParsedRiderSymbol(
        string language,
        string containerQualifiedName,
        string? memberName,
        IReadOnlyList<string>? parameterTypes,
        bool isConstructor)
    {
        Language = language;
        ContainerQualifiedName = containerQualifiedName;
        MemberName = memberName;
        ParameterTypes = parameterTypes;
        IsConstructor = isConstructor;
    }

    public string Language { get; }
    public string ContainerQualifiedName { get; }
    public string? MemberName { get; }
    public IReadOnlyList<string>? ParameterTypes { get; }
    public bool IsConstructor { get; }
}

internal sealed class IndexedSymbolResolution
{
    private IndexedSymbolResolution(string status, string? message, IDeclaredElement? element)
    {
        Status = status;
        Message = message;
        Element = element;
    }

    public string Status { get; }
    public string? Message { get; }
    public IDeclaredElement? Element { get; }

    public static IndexedSymbolResolution Success(IDeclaredElement element) =>
        new("success", null, element);

    public static IndexedSymbolResolution Invalid(string message) =>
        new("invalid_symbol", message, null);

    public static IndexedSymbolResolution Unresolved(string message) =>
        new("unresolved_symbol", message, null);

    public static IndexedSymbolResolution Ambiguous(string message) =>
        new("ambiguous_match", message, null);

    public static IndexedSymbolResolution Unsupported(string message) =>
        new("unsupported_target", message, null);

    public IDeclaredElement? TryGetElement() => Status == "success" ? Element : null;

    public RdResolveSymbolIndexedResult ToRdResult() =>
        new(Status, Message, Element == null ? null : IndexMcpBackendHost.ToSymbolInfo(Element));
}

internal sealed class IndexedSymbolParseResult
{
    private IndexedSymbolParseResult(bool isSuccess, ParsedRiderSymbol? symbol, IndexedSymbolResolution? failure)
    {
        IsSuccess = isSuccess;
        Symbol = symbol;
        Failure = failure;
    }

    public bool IsSuccess { get; }
    public ParsedRiderSymbol? Symbol { get; }
    public IndexedSymbolResolution? Failure { get; }

    public static IndexedSymbolParseResult Success(ParsedRiderSymbol symbol) =>
        new(true, symbol, null);

    public static IndexedSymbolParseResult Invalid(string message) =>
        new(false, null, IndexedSymbolResolution.Invalid(message));

    public static IndexedSymbolParseResult Unsupported(string message) =>
        new(false, null, IndexedSymbolResolution.Unsupported(message));

    public IndexedSymbolResolution ToResolution() => Failure ?? IndexedSymbolResolution.Invalid("Rider symbol parsing failed.");
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
