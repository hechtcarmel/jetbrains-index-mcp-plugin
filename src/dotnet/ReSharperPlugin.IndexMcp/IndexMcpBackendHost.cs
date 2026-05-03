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
            var declaredTypes = EnumerateDeclaredElements()
                .OfType<ITypeElement>();

            var indexedTypes = request.Scope == "project_and_libraries"
                ? EnumerateIndexedTypeElements(request.Query, request.MatchMode)
                : Enumerable.Empty<ITypeElement>();

            var results = declaredTypes
                .Concat(indexedTypes)
                .Where(type => MatchesName(type.ShortName, request.Query, request.MatchMode) ||
                               MatchesName(type.GetClrName().FullName, request.Query, request.MatchMode))
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

    private IEnumerable<ITypeElement> EnumerateIndexedTypeElements(string query, string matchMode)
    {
        foreach (var libraryScope in new[] { LibrarySymbolScope.REFERENCED, LibrarySymbolScope.TRANSITIVE, LibrarySymbolScope.FULL })
        {
            foreach (var element in EnumerateSymbolScopeTypes(
                         _solution.GetPsiServices().Symbols.GetSymbolScope(libraryScope, false),
                         query,
                         matchMode))
            {
                yield return element;
            }
        }

        var seenModules = new HashSet<IPsiModule>();
        foreach (var module in EnumerateProjectPsiModules().Where(module => seenModules.Add(module)))
        {
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
            var element = ResolveTarget(request.Target);
            if (element == null) return new RdFindReferencesResult(new List<RdReferenceInfo>(), 0);

            var references = FindReferences(element)
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
            var element = ResolveTarget(request.Target);
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
            .Where(element =>
                NormalizeSymbolName(GetQualifiedName(element)).Equals(normalized, StringComparison.OrdinalIgnoreCase) ||
                NormalizeSymbolName(element.ShortName).Equals(normalized, StringComparison.OrdinalIgnoreCase))
            // Prefer partials whose primary declaration lives in a real hand-written
            // .cs/.fs file over source-generator output (obj/, *.g.cs) and over
            // synthetic XAML/AXAML partials whose source file may have an empty
            // location. Without this, AXAML-paired classes resolve to the
            // generator partial and HandleFindDefinition returns file:"".
            .OrderBy(BestDeclarationRank)
            .FirstOrDefault();
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

    private IEnumerable<IPsiModule> EnumerateProjectPsiModules()
    {
        foreach (var project in _solution.GetAllProjects())
        {
            foreach (var projectFile in project.GetAllProjectFiles())
            {
                foreach (var sourceFile in projectFile.ToSourceFiles())
                {
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
        if (string.IsNullOrWhiteSpace(language)) return true;
        var presentationLanguage = element.PresentationLanguage?.Name;
        if (presentationLanguage != null && presentationLanguage.Equals(language, StringComparison.OrdinalIgnoreCase))
            return true;

        var filePath = PickPreferredDeclaration(element)?.GetSourceFile()?.GetLocation().FullPath ?? "";
        if (string.IsNullOrWhiteSpace(filePath))
            return language.Equals("C#", StringComparison.OrdinalIgnoreCase);

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

    private List<string> GetPotentiallyAffectedFiles(IDeclaredElement element)
    {
        return element.GetDeclarations()
            .Select(declaration => declaration.GetSourceFile()?.GetLocation().FullPath)
            .Concat(FindReferences(element)
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

    private static RdSymbolInfo ToSymbolInfo(IDeclaredElement element)
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
