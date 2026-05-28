using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using JetBrains.Application.DataContext;
using JetBrains.Application.Parts;
using JetBrains.Application.Progress;
using JetBrains.Application.Threading;
using JetBrains.Core;
using JetBrains.DocumentModel;
using JetBrains.DocumentManagers.Transactions;
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
using ReSharperPlugin.IndexMcp.Mutations;

namespace ReSharperPlugin.IndexMcp;

/// <summary>
/// Main backend host for the IDE Index MCP Server protocol.
///
/// Handles all code intelligence RPC calls from the Kotlin frontend by using
/// ReSharper's full semantic model for C# code.
/// </summary>
[SolutionComponent(Instantiation.ContainerAsyncPrimaryThread)]
public class IndexMcpBackendHost
{
    private readonly ISolution _solution;
    private readonly IShellLocks _shellLocks;
    private readonly RenameRefactoringService _renameRefactoringService;
    private const string BackendVersion = "4.18.0";
    private const int MaxResults = 200;
    private const int TextControlPollTimeoutMs = 10_000;
    private const int TextControlPollIntervalMs = 250;
    private static readonly Lazy<MethodInfo?> ourGetPresentableNameMethod =
        new(() => typeof(IType).GetMethods(BindingFlags.Instance | BindingFlags.Public)
            .FirstOrDefault(method => method.Name == nameof(IType.GetPresentableName) &&
                                      method.GetParameters().Length == 1));

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
        model.FindSymbols.SetAsync(HandleFindSymbols);
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
        model.RenameFile.SetAsync(HandleRenameFile);
        model.MoveFile.SetAsync(HandleMoveFile);
        model.SafeDelete.SetAsync(HandleSafeDelete);
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
        return Task.Run(() => ExecuteUnderReadLock(() =>
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
                .Where(type => MatchesScope(type, request.Scope, includeUnknownProjectCandidates: true))
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
        }));
    }

    private Task<RdFindSymbolsResult> HandleFindSymbols(Lifetime lt, RdFindSymbolsRequest request)
    {
        return Task.Run(() => ExecuteUnderReadLock(() =>
        {
            var normalizedLanguage = NormalizeLanguage(request.Language);
            if (normalizedLanguage != "C#")
                return new RdFindSymbolsResult(new List<RdSymbolInfo>(), 0);

            var allowedProjectFileExtensions = GetProjectFileExtensions(normalizedLanguage);
            var declaredProjectSymbols = EnumerateDeclaredSymbolElements(lt, allowedProjectFileExtensions);
            var indexedLibrarySymbols = request.Scope.Equals("project_and_libraries", StringComparison.OrdinalIgnoreCase)
                ? EnumerateIndexedSymbolElements(lt, request.Query, normalizedLanguage)
                : Enumerable.Empty<IDeclaredElement>();

            return BuildFindSymbolsResult(
                declaredProjectSymbols.Concat(indexedLibrarySymbols),
                request.Query,
                request.Scope,
                request.Language,
                GetEffectiveResultLimit(request.Limit));
        }));
    }

    private static RdFindSymbolsResult BuildFindSymbolsResult(
        IEnumerable<IDeclaredElement> candidates,
        string query,
        string scope,
        string? language,
        int limit)
    {
        var results = candidates
            .Where(IsSupportedFindSymbolElement)
            .Where(element => MatchesFindSymbolQuery(element, query))
            .Where(element => MatchesLanguage(element, language))
            .Where(element => MatchesScope(element, scope, includeUnknownProjectCandidates: true))
            .OrderBy(element => MatchRank(element.ShortName, query, "substring"))
            .ThenBy(BestDeclarationRank)
            .ThenBy(element => IsTestPath(GetDeclarationPath(element)) ? 1 : 0)
            .ThenBy(GetQualifiedName, StringComparer.OrdinalIgnoreCase)
            .ThenBy(GetMemberSignatureSortKey, StringComparer.OrdinalIgnoreCase)
            .ThenBy(GetDeclarationPath, StringComparer.OrdinalIgnoreCase)
            .Select(ToSymbolInfo)
            .GroupBy(symbol => $"{symbol.Kind}:{symbol.QualifiedName}:{symbol.FilePath}:{symbol.Line}:{symbol.Column}:{symbol.Signature}")
            .Select(group => group.First())
            .ToList();

        return new RdFindSymbolsResult(results.Take(limit).ToList(), results.Count);
    }

    private static bool IsSupportedFindSymbolElement(IDeclaredElement element)
    {
        return GetElementKind(element) != "UNKNOWN";
    }

    private static bool MatchesFindSymbolQuery(IDeclaredElement element, string query)
    {
        return MatchesName(element.ShortName, query, "substring") ||
               MatchesQualifiedName(GetQualifiedName(element), query, "substring");
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
        _ = scope;
        _ = parsedSymbol;
        return new FindReferencesResolutionPlan(targetKind: "symbol");
    }

    private static FindReferencesResolutionPlan BuildFindReferencesResolutionPlan(
        RdSemanticTarget target,
        string scope,
        ParsedRiderSymbol? parsedSymbol)
    {
        _ = scope;
        if (!string.IsNullOrWhiteSpace(target.FilePath) &&
            target.Line.HasValue &&
            target.Column.HasValue)
        {
            return new FindReferencesResolutionPlan(targetKind: "position");
        }

        if (!string.IsNullOrWhiteSpace(target.Language) &&
            !string.IsNullOrWhiteSpace(target.Symbol))
        {
            return parsedSymbol != null
                ? BuildFindReferencesResolutionPlan(scope, parsedSymbol)
                : new FindReferencesResolutionPlan(targetKind: "symbol");
        }

        return new FindReferencesResolutionPlan(targetKind: "unsupported");
    }

    private static IEnumerable<RdSymbolInfo> EnumerateStandardDotNetTypeSymbols(
        string query,
        string matchMode,
        string scope,
        string? language)
    {
        if (scope != "project_and_libraries") yield break;
        if (!string.IsNullOrWhiteSpace(language) && NormalizeLanguage(language) != "C#")
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
        return Task.Run(() => ExecuteUnderReadLock<RdDefinitionResult?>(() =>
        {
            var element = ResolveTarget(request.Target);
            if (element == null) return null;

            var navigationElement = PickPreferredDeclaration(element);
            var location = BuildDefinitionLocation(element, navigationElement);
            var locationKind = location.LocationKind;
            var locationDisplayName = location.LocationDisplayName;
            var preview = navigationElement != null
                ? BuildPreview(navigationElement, request.FullElementPreview, request.MaxPreviewLines)
                : string.Empty;
            return new RdDefinitionResult(
                ToSymbolInfo(element),
                preview,
                navigationElement != null ? BuildAstPath(navigationElement) : new List<string>(),
                locationKind,
                locationDisplayName);
        }));
    }

    private Task<RdFindReferencesResult> HandleFindReferences(Lifetime lt, RdFindReferencesRequest request)
    {
        return Task.Run(() => ExecuteUnderReadLock(() =>
        {
            var effectiveLimit = GetEffectiveResultLimit(request.Limit);
            var targetResolution = ResolveTargetForFindReferences(lt, request.Target, request.Scope);
            var element = targetResolution.TryGetElement();
            if (element == null)
            {
                // Surface unresolved-target failures via the result's `message` field rather than
                // raising an exception. Throwing here turned into an RdFault that leaked the
                // backend stack trace (incl. source paths) all the way to the MCP client.
                // Empty references + non-null message is the contract the Kotlin side uses to
                // present a clean error message; null message means "no results, no failure".
                var unresolvedMessage = ShouldRaiseFindReferencesTargetResolutionFailure(request.Target, targetResolution.Status)
                    ? FormatFindReferencesTargetResolutionFailureMessage(
                        request.Target,
                        request.Scope,
                        targetResolution.Status,
                        targetResolution.Message,
                        targetResolution.Origin)
                    : null;
                return new RdFindReferencesResult(new List<RdReferenceInfo>(), 0, unresolvedMessage);
            }

            var orderedReferences = FindReferences(lt, element, effectiveLimit)
                .Select(ToReferenceInfo)
                .Where(reference => reference != null)
                .Cast<RdReferenceInfo>()
                .Where(reference => MatchesPathScope(reference.FilePath, request.Scope))
                .GroupBy(reference => GetReferenceIdentityKey(reference), StringComparer.OrdinalIgnoreCase)
                .Select(group => group
                    .OrderBy(ReferenceLocationBucket)
                    .ThenBy(reference => NormalizeReferencePath(reference.FilePath), StringComparer.OrdinalIgnoreCase)
                    .ThenBy(reference => reference.Line)
                    .ThenBy(reference => reference.Column)
                    .ThenBy(reference => reference.Kind, StringComparer.OrdinalIgnoreCase)
                    .ThenBy(reference => reference.Context, StringComparer.OrdinalIgnoreCase)
                    .First())
                .OrderBy(ReferenceLocationBucket)
                .ThenBy(reference => NormalizeReferencePath(reference.FilePath), StringComparer.OrdinalIgnoreCase)
                .ThenBy(reference => reference.Line)
                .ThenBy(reference => reference.Column)
                .ThenBy(reference => reference.Kind, StringComparer.OrdinalIgnoreCase)
                .ThenBy(reference => reference.Context, StringComparer.OrdinalIgnoreCase)
                .ToList();

            return new RdFindReferencesResult(orderedReferences.Take(effectiveLimit).ToList(), orderedReferences.Count, null);
        }));
    }

    private Task<RdSymbolInfo?> HandleResolveSymbol(Lifetime lt, RdResolveSymbolRequest request)
    {
        return Task.Run(() => ExecuteUnderReadLock<RdSymbolInfo?>(() =>
        {
            var element = ResolveSymbol(request.Language, request.Symbol);
            return element == null ? null : ToSymbolInfo(element);
        }));
    }

    private Task<RdResolveSymbolIndexedResult> HandleResolveSymbolIndexed(Lifetime lt, RdResolveSymbolIndexedRequest request)
    {
        return Task.Run(() => ExecuteUnderReadLock(() => ResolveSymbolIndexed(request.Language, request.Symbol).ToRdResult()));
    }

    // ── Rename Symbol ───────────────────────────────────────────────────────

    private Task<RdRenameSymbolResult?> HandleRenameSymbol(
        Lifetime lt, RdRenameSymbolRequest request)
    {
        var preAcquiredTextControl = TryAcquireTextControlBeforeWriteLock(request);
        OuterLifetime outerLifetime = lt;
        RdRenameSymbolResult? result = null;
        return ExecuteWriteLockedMutation(
            outerLifetime,
            work: () => { result = ExecuteRenameSymbol(request, preAcquiredTextControl); },
            getResult: () => result,
            operationName: "rename",
            toBlockedResult: blocked => blocked.ToRenameSymbolResult("", request.NewName));
    }

    // ── Read Lock Helper ─────────────────────────────────────────────────────
    //
    // ReSharper requires a read lock when traversing PSI / running searches off the EDT.
    // Without it, indexer-backed reads (FindInheritors, ReferencesSearch, type/symbol lookup,
    // etc.) can race with model changes and either throw or return inconsistent data.
    //
    // All HandleX read endpoints route their bodies through ExecuteUnderReadLock to acquire
    // the cookie on the background Task.Run thread. Mutation endpoints continue to use
    // ExecuteWriteLockedRename, which acquires the write lock instead.
    private static T ExecuteUnderReadLock<T>(Func<T> action)
    {
        using (ReadLockCookie.Create())
        {
            return action();
        }
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

    /// <summary>
    /// Run a mutation under the ReSharper write lock and convert any fault into a Blocked
    /// outcome shaped for the calling endpoint. The three mutation endpoints (rename symbol,
    /// rename file, safe delete) previously duplicated this 12-line ContinueWith / Blocked
    /// pattern; this helper centralises the message wording and outcome mapping.
    /// </summary>
    /// <param name="operationName">Wording inserted into the blocked message (e.g. "rename",
    /// "file rename", "safe delete"). Used in: "ReSharper backend {operationName} failed
    /// while acquiring/executing the write lock".</param>
    private Task<TResult?> ExecuteWriteLockedMutation<TResult>(
        OuterLifetime outerLifetime,
        Action work,
        Func<TResult?> getResult,
        string operationName,
        Func<VerifiedMutationOutcome, TResult?> toBlockedResult)
    {
        return ExecuteWriteLockedRename(outerLifetime, work)
            .ContinueWith<TResult?>(task =>
            {
                if (!task.IsFaulted)
                    return getResult();

                var exception = task.Exception?.GetBaseException();
                var blockedMessage = exception == null
                    ? $"ReSharper backend {operationName} failed while acquiring/executing the write lock."
                    : $"ReSharper backend {operationName} failed while acquiring/executing the write lock: {exception.GetType().Name}: {exception.Message}";
                return toBlockedResult(MutationVerificationService.Blocked(blockedMessage));
            });
    }

    private RdRenameSymbolResult ExecuteRenameSymbol(
        RdRenameSymbolRequest request,
        object? preAcquiredTextControl)
    {
        var planStopwatch = Stopwatch.StartNew();
        var renamePlan = RenameMutationPlanner.PlanExactSymbolRename(
            request.Position.FilePath,
            request.Position.Line,
            request.Position.Column);
        planStopwatch.Stop();

        if (!renamePlan.CanProceed)
        {
            return ToRenameBlockedResult(renamePlan.Resolution, request.NewName);
        }

        var resolveStopwatch = Stopwatch.StartNew();
        var localDeclarationNode = ResolveExactDeclarationNodeAt(
            request.Position,
            renamePlan.Resolution.ResolvedName!,
            renamePlan.Resolution.TargetKind);
        resolveStopwatch.Stop();
        if (localDeclarationNode == null || !TryGetDeclaredElement(localDeclarationNode, out var element))
        {
            return MutationVerificationService
                .Blocked(
                    AppendSymbolRenameDiagnostics(
                        $"Exact symbol rename preflight resolved '{renamePlan.Resolution.ResolvedName}', but the Rider backend could not bind the exact declaration node at {request.Position.FilePath}:{request.Position.Line}:{request.Position.Column} without widening. No files were modified.",
                        renamePlan.Resolution,
                        executionHint: "frontend_editor_backed_exact_target_only",
                        unsupportedReason: "exact_declaration_node_binding_failed",
                        "plan.end",
                        "target-resolution.end",
                        "target-resolution.blocked"))
                .ToRenameSymbolResult(renamePlan.Resolution.ResolvedName ?? string.Empty, request.NewName);
        }

        var oldName = renamePlan.Resolution.ResolvedName!;

        if (string.Equals(oldName, request.NewName, StringComparison.Ordinal))
        {
            return MutationVerificationService
                .NoOp(
                    AppendSymbolRenameDiagnostics(
                        $"Rename refused: new name '{request.NewName}' equals current name '{oldName}' (no-op rename). No files were modified.",
                        renamePlan.Resolution,
                        executionHint: "no_mutation_required",
                        unsupportedReason: "requested_name_matches_current_name",
                        "plan.end",
                        "target-resolution.bound",
                        "no-op"))
                .ToRenameSymbolResult(oldName, request.NewName);
        }

        var availability = _renameRefactoringService.CheckRenameAvailability(element);
        if (availability != RenameAvailabilityCheckResult.CanBeRenamed)
        {
            return MutationVerificationService
                .Unsupported(AppendSymbolRenameDiagnostics(
                    $"ReSharper reports that '{oldName}' cannot be renamed ({availability}). No files were modified.",
                    renamePlan.Resolution,
                    executionHint: "frontend_editor_backed_exact_target_only",
                    unsupportedReason: $"rename_availability_{availability}",
                    "plan.end",
                    "target-resolution.bound",
                    "availability.end"))
                .ToRenameSymbolResult(oldName, request.NewName);
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
            var renameExecution = TryExecuteDrivenRename(element, request.NewName, preAcquiredTextControl);

            if (!renameExecution.Succeeded)
            {
                if (renameExecution.IsUnsupported)
                {
                    return MutationVerificationService
                        .Unsupported(renameExecution.Message)
                        .ToRenameSymbolResult(oldName, request.NewName);
                }

                return MutationVerificationService
                    .VerificationFailed(
                        affectedFiles,
                        affectedFiles.Count,
                        AppendSymbolRenameDiagnostics(
                            $"ReSharper rename did not update affected files: {renameExecution.Message}",
                            renamePlan.Resolution,
                            executionHint: "frontend_editor_backed_exact_target_only",
                            unsupportedReason: renameExecution.IsUnsupported
                                ? "service_inspection_rejected_backend_symbol_lane"
                                : "backend_symbol_execution_failed_without_mutation",
                            "plan.end",
                            "target-resolution.bound",
                            "service-rename.inspect",
                            "service-rename.failure"),
                        new[] { "rename_execution", "post_change_semantics" },
                        renameExecution.Message)
                    .ToRenameSymbolResult(oldName, request.NewName);
            }

            // Re-snapshot the affected file set after the rename succeeds. The pre-rename
            // GetPotentiallyAffectedFiles call (line 643) only enumerates declarations and
            // references reachable from the IDeclaredElement at that point; partial-class
            // siblings, AXAML code-behind pairs, and tests that the rename refactoring
            // touched but that weren't yet bound to the element can be missed. Widening
            // here means the response payload reflects what actually changed on disk.
            // Best-effort: any failure (element invalidated, indexes not warm) is silently
            // ignored and the pre-rename list stands.
            try
            {
                if (element.IsValid())
                {
                    foreach (var path in GetPotentiallyAffectedFiles(element))
                        AddAffectedFile(affectedFiles, path);
                }
            }
            catch
            {
                // best-effort widening
            }

            if (!RenameChangedAffectedFiles(oldNameCountsBefore, oldName))
            {
                return MutationVerificationService
                    .NoOp(
                        AppendSymbolRenameDiagnostics(
                            $"ReSharper rename completed without error, but no affected file's count of the identifier '{oldName}' decreased. The rename did not actually rewrite anything on disk (no-op outcome).",
                            renamePlan.Resolution,
                            executionHint: "frontend_editor_backed_exact_target_only",
                            unsupportedReason: "no_observable_identifier_mutation",
                            "plan.end",
                            "target-resolution.bound",
                            "service-rename.inspect",
                            "verification"))
                    .ToRenameSymbolResult(oldName, request.NewName);
            }

            return MutationVerificationService
                .Success(
                    affectedFiles,
                    affectedFiles.Count,
                    AppendSymbolRenameDiagnostics(
                        $"Renamed '{oldName}' to '{request.NewName}' using ReSharper backend rename with verified semantic updates.",
                        renamePlan.Resolution,
                        executionHint: "frontend_editor_backed_exact_target_preferred_when_backend_lane_is_unsupported",
                        unsupportedReason: "none",
                        "plan.end",
                        "target-resolution.bound",
                        "service-rename.inspect",
                        "success"))
                .ToRenameSymbolResult(oldName, request.NewName);
        }
        catch (Exception ex)
        {
            return MutationVerificationService
                .Blocked(AppendSymbolRenameDiagnostics(
                    $"ReSharper backend rename failed: {ex.GetType().Name}: {ex.Message}",
                    renamePlan.Resolution,
                    executionHint: "frontend_editor_backed_exact_target_only",
                    unsupportedReason: "backend_symbol_lane_exception",
                    "plan.end",
                    "target-resolution.bound",
                    "execute-rename"))
                .ToRenameSymbolResult(oldName, request.NewName);
        }
    }

    private Task<RdRenameFileResult?> HandleRenameFile(Lifetime lt, RdRenameFileRequest request)
    {
        OuterLifetime outerLifetime = lt;
        var renamePlan = RenameMutationPlanner.PlanExactFileRename(request.FilePath, request.NewName);
        var oldPath = renamePlan.OldPath ?? request.FilePath;
        var newPath = renamePlan.NewPath ?? CombinePath(Path.GetDirectoryName(oldPath), request.NewName);

        if (string.Equals(Path.GetFileName(oldPath), request.NewName, StringComparison.OrdinalIgnoreCase))
        {
            return Task.FromResult<RdRenameFileResult?>(
                MutationVerificationService
                    .NoOp($"File rename refused because '{request.NewName}' already matches the current file name (no-op file rename).")
                    .ToRenameFileResult(oldPath, oldPath));
        }

        if (!renamePlan.CanProceed)
        {
            return Task.FromResult<RdRenameFileResult?>(ToRenameFileBlockedResult(renamePlan.Resolution, oldPath, newPath));
        }

        if (!TryValidateHeadlessFileRenameAvailability(oldPath, out var availabilityFailure))
        {
            return Task.FromResult<RdRenameFileResult?>(
                MutationVerificationService
                    .Unsupported(availabilityFailure)
                    .ToRenameFileResult(oldPath, newPath));
        }

        RdRenameFileResult? result = null;
        return ExecuteWriteLockedMutation(
            outerLifetime,
            work: () => { result = ExecuteRenameFile(renamePlan); },
            getResult: () => result,
            operationName: "file rename",
            toBlockedResult: blocked => blocked.ToRenameFileResult(oldPath, newPath));
    }

    private Task<RdMoveFileResult?> HandleMoveFile(Lifetime lt, RdMoveFileRequest request)
    {
        var oldPath = request.FilePath;
        var currentDirectory = Path.GetDirectoryName(oldPath);
        var newPath = CombinePath(request.DestinationDirectory, Path.GetFileName(oldPath));

        if (PathsEqual(currentDirectory, request.DestinationDirectory))
        {
            return Task.FromResult<RdMoveFileResult?>(
                MutationVerificationService
                    .NoOp("File move refused because the destination directory already matches the current location (no-op move).")
                    .ToMoveFileResult(oldPath, oldPath));
        }

        var movePlan = MoveMutationPlanner.PlanSemanticMove(request.FilePath, request.DestinationDirectory);
        if (!movePlan.CanProceed)
            return Task.FromResult<RdMoveFileResult?>(ToMoveBlockedResult(movePlan.Resolution, oldPath, newPath));

        return Task.FromResult<RdMoveFileResult?>(
            MutationVerificationService
                .Unsupported(
                    "Rider frontend dialog automation owns Rider move execution. " +
                    "The backend MoveToFolder workflow lane remains disabled because its headless execution could not be made reliable enough to verify safely.")
                .ToMoveFileResult(oldPath, newPath));
    }

    private static string CombinePath(string? directory, string fileName)
        => string.IsNullOrWhiteSpace(directory)
            ? fileName
            : Path.Combine(directory, fileName);

    private RdRenameFileResult ExecuteRenameFile(RenameMutationPlan renamePlan)
    {
        var oldPath = renamePlan.OldPath!;
        var newPath = renamePlan.NewPath!;
        var declaredTypeNamesBefore = RenameMutationPlanner.ReadDeclaredTypeNames(oldPath);

        if (!TryValidateHeadlessFileRenameAvailability(oldPath, out var availabilityFailure))
        {
            return MutationVerificationService
                .Unsupported(availabilityFailure)
                .ToRenameFileResult(oldPath, newPath);
        }

        var projectFile = GetProjectFileForPath(oldPath);
        if (projectFile == null)
        {
            return MutationVerificationService
                .Blocked($"Rider file rename resolved '{oldPath}', but the backend could not bind it to a project file for RenameDataProvider/RenameWorkflow execution. No files were modified.")
                .ToRenameFileResult(oldPath, newPath);
        }

        var expectedNamespace = FileMutationSemantics.TryReadNamespaceOrDefault(oldPath, projectFile.Location.FullPath);

        try
        {
            var workflowMessage = TryExecuteDrivenFileRename(projectFile, renamePlan.NewPath!);
            if (!string.IsNullOrWhiteSpace(workflowMessage))
            {
                return MutationVerificationService
                    .Unsupported(workflowMessage)
                    .ToRenameFileResult(oldPath, newPath);
            }

            if (File.Exists(oldPath) || !File.Exists(newPath))
            {
                return MutationVerificationService
                    .VerificationFailed(
                        new[] { oldPath, newPath },
                        File.Exists(newPath) ? 1 : 0,
                        "Rider file rename workflow reported completion, but the on-disk file path transition could not be confirmed.",
                        new[] { "rename_execution", "file_path_transition" },
                        "expected the old path to disappear and the new path to exist after the workflow finished")
                    .ToRenameFileResult(oldPath, newPath);
            }

            var declaredTypeNamesAfter = RenameMutationPlanner.ReadDeclaredTypeNames(newPath);
            var declaredTypeIdentityFailure = VerifyFileRenameDeclaredTypeIdentity(
                declaredTypeNamesBefore,
                declaredTypeNamesAfter,
                newPath);
            if (declaredTypeIdentityFailure != null)
                return declaredTypeIdentityFailure.ToRenameFileResult(oldPath, newPath);

            var semanticEvidence = CollectFileMutationSemanticEvidence(newPath, declaredTypeNamesBefore);

            return MutationVerificationService
                .ConfirmSemanticFileMutationProofWithEvidence(
                    newPath,
                    projectFile.Location.FullPath,
                    expectedNamespace,
                    semanticEvidence.ConfirmedAffectedFiles,
                    semanticEvidence.ConfirmedReferenceFiles,
                    declaredTypeNamesBefore,
                    semanticEvidence,
                    "Renamed the file through the Rider RenameDataProvider/RenameWorkflow lane and confirmed the file-scoped mutation path.")
                .ToRenameFileResult(oldPath, newPath);
        }
        catch (Exception ex)
        {
            return MutationVerificationService
                .Blocked($"Rider file rename workflow failed: {ex.GetType().Name}: {ex.Message}")
                .ToRenameFileResult(oldPath, newPath);
        }
    }

    private static VerifiedMutationOutcome? VerifyFileRenameDeclaredTypeIdentity(
        IReadOnlyList<string> declaredTypeNamesBefore,
        IReadOnlyList<string> declaredTypeNamesAfter,
        string newPath)
    {
        var normalizedBefore = NormalizeDeclaredTypeNames(declaredTypeNamesBefore);
        var normalizedAfter = NormalizeDeclaredTypeNames(declaredTypeNamesAfter);
        if (normalizedBefore.SequenceEqual(normalizedAfter, StringComparer.Ordinal))
            return null;

        return MutationVerificationService.VerificationFailed(
            new[] { newPath },
            File.Exists(newPath) ? 1 : 0,
            "Rider file rename changed declared type identity, so the backend cannot prove file-only semantics.",
            new[] { "rename_execution", "file_path_transition", "declared_type_identity" },
            $"declared type names changed during file rename: before [{FormatDeclaredTypeNames(normalizedBefore)}], after [{FormatDeclaredTypeNames(normalizedAfter)}]");
    }

    private static IReadOnlyList<string> NormalizeDeclaredTypeNames(IEnumerable<string>? typeNames)
    {
        return (typeNames ?? Array.Empty<string>())
            .Where(name => !string.IsNullOrWhiteSpace(name))
            .Select(name => name.Trim().TrimStart('@'))
            .Distinct(StringComparer.Ordinal)
            .OrderBy(name => name, StringComparer.Ordinal)
            .ToArray();
    }

    private static string FormatDeclaredTypeNames(IEnumerable<string> typeNames)
    {
        var normalized = NormalizeDeclaredTypeNames(typeNames);
        return normalized.Count == 0
            ? "<none>"
            : string.Join(", ", normalized);
    }

    private Task<RdSafeDeleteResult?> HandleSafeDelete(Lifetime lt, RdSafeDeleteRequest request)
    {
        OuterLifetime outerLifetime = lt;
        var targetFilePath = request.Target.FilePath;
        if (string.IsNullOrWhiteSpace(targetFilePath) || !request.Target.Line.HasValue || !request.Target.Column.HasValue)
        {
            return Task.FromResult<RdSafeDeleteResult?>(
                MutationVerificationService
                    .Unsupported("Rider safe delete requires a file/line/column target in the backend mutation lane.")
                    .ToSafeDeleteResult());
        }

        RdSafeDeleteResult? result = null;
        return ExecuteWriteLockedMutation(
            outerLifetime,
            work: () =>
            {
                result = string.Equals(request.TargetType, "file", StringComparison.OrdinalIgnoreCase)
                    ? SafeDeleteMutationExecutor.ExecuteFileSafeDelete(targetFilePath, request.Force)
                    : SafeDeleteMutationExecutor.ExecuteSafeDelete(
                        targetFilePath,
                        request.Target.Line.Value,
                        request.Target.Column.Value,
                        request.Target.Symbol ?? string.Empty,
                        request.Force);
            },
            getResult: () => result,
            operationName: "safe delete",
            toBlockedResult: blocked => blocked.ToSafeDeleteResult());
    }

    private RdRenameSymbolResult ToRenameBlockedResult(ExactTargetResolution resolution, string newName)
    {
        var outcome = string.Equals(resolution.Status, MutationResolutionStatuses.Unsupported, StringComparison.OrdinalIgnoreCase)
            ? MutationVerificationService.Unsupported(AppendSymbolRenameDiagnostics(
                resolution.Message ?? "Exact symbol rename was rejected before mutation.",
                resolution,
                executionHint: "frontend_editor_backed_exact_target_only",
                unsupportedReason: ClassifyExactTargetUnsupportedReason(resolution),
                "plan.end",
                "plan.blocked"))
            : MutationVerificationService.Blocked(AppendSymbolRenameDiagnostics(
                resolution.Message ?? "Exact symbol rename was rejected before mutation.",
                resolution,
                executionHint: "frontend_editor_backed_exact_target_only",
                unsupportedReason: ClassifyExactTargetUnsupportedReason(resolution),
                "plan.end",
                "plan.blocked"));
        return outcome.ToRenameSymbolResult(resolution.ResolvedName ?? string.Empty, newName);
    }

    private static string AppendSymbolRenameDiagnostics(
        string message,
        ExactTargetResolution resolution,
        string executionHint,
        string unsupportedReason,
        params string[] traceStages)
    {
        var diagnostics = new List<string>
        {
            $"resolutionStatus={resolution.Status}",
            $"targetKind={resolution.TargetKind ?? "<null>"}",
            $"resolvedName={resolution.ResolvedName ?? "<null>"}",
            $"sourceTokenText={resolution.SourceTokenText ?? "<null>"}",
            $"executionHint={executionHint}",
            $"unsupportedReason={unsupportedReason}",
            $"traceStages={string.Join(">", traceStages.Where(stage => !string.IsNullOrWhiteSpace(stage)).Distinct(StringComparer.Ordinal))}"
        };

        return $"{message} [backendSymbolRename: {string.Join(", ", diagnostics)}]";
    }

    private static string ClassifyExactTargetUnsupportedReason(ExactTargetResolution resolution)
    {
        return resolution.Status switch
        {
            MutationResolutionStatuses.Mismatch => "requested_symbol_mismatch",
            MutationResolutionStatuses.Ambiguous => "exact_target_ambiguous",
            MutationResolutionStatuses.Unsupported => "exact_target_unsupported",
            _ => "exact_target_rejected"
        };
    }

    private RdRenameFileResult ToRenameFileBlockedResult(ExactTargetResolution resolution, string oldPath, string newPath)
    {
        var outcome = string.Equals(resolution.Status, MutationResolutionStatuses.Unsupported, StringComparison.OrdinalIgnoreCase)
            ? MutationVerificationService.Unsupported(resolution.Message ?? "Exact file rename was rejected before mutation.")
            : MutationVerificationService.Blocked(resolution.Message ?? "Exact file rename was rejected before mutation.");
        return outcome.ToRenameFileResult(oldPath, newPath);
    }

    private RdMoveFileResult ToMoveBlockedResult(ExactTargetResolution resolution, string oldPath, string newPath)
    {
        var outcome = string.Equals(resolution.Status, MutationResolutionStatuses.Unsupported, StringComparison.OrdinalIgnoreCase)
            ? MutationVerificationService.Unsupported(resolution.Message ?? "Semantic move was rejected before mutation.")
            : MutationVerificationService.Blocked(resolution.Message ?? "Semantic move was rejected before mutation.");
        return outcome.ToMoveFileResult(oldPath, newPath);
    }

    private static bool PathsEqual(string? left, string? right)
        => string.Equals(
            TrimDirectorySeparator(left),
            TrimDirectorySeparator(right),
            StringComparison.OrdinalIgnoreCase);

    private static string? TrimDirectorySeparator(string? path)
        => string.IsNullOrWhiteSpace(path)
            ? path
            : path.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);

    private bool TryValidateHeadlessFileRenameAvailability(string filePath, out string failureMessage)
    {
        if (_solution == null || _shellLocks == null || _renameRefactoringService == null)
        {
            failureMessage = "File rename is fail-closed because the Rider RenameRefactoringService workflow is unavailable in this headless context. The backend will not fall back to symbol rename, token targeting, or raw filesystem rename.";
            return false;
        }

        if (string.IsNullOrWhiteSpace(filePath) || !File.Exists(filePath))
        {
            failureMessage = $"File rename is fail-closed because '{filePath}' is unavailable for workflow-backed execution.";
            return false;
        }

        failureMessage = string.Empty;
        return true;
    }

    private RenameExecutionAttempt TryExecuteDrivenRename(
        IDeclaredElement element,
        string newName,
        object? preAcquiredTextControl)
    {
        try
        {
            var targetTextControl = preAcquiredTextControl;

            if (targetTextControl != null)
            {
                var textControlRename = TryExecuteTextControlRename(element, newName, targetTextControl);

                if (textControlRename.Succeeded)
                    return textControlRename;
            }

            var plan = InspectSymbolRenameServiceExecutionPlan();

            if (!plan.IsSupported)
                return RenameExecutionAttempt.Unsupported(plan.Message);

            if (plan.RequiresTextControl)
                return RenameExecutionAttempt.Unsupported(plan.Message);

            return RenameExecutionAttempt.Unsupported(plan.Message);
        }
        catch (Exception ex)
        {
            return RenameExecutionAttempt.Unsupported(
                $"Symbol rename is fail-closed because RenameRefactoringService inspection failed: {ex.GetType().Name}: {ex.Message}");
        }
    }

    private object? TryFindTextControlForElement(IDeclaredElement element)
    {
        try
        {
            var declaration = element.GetDeclarations().FirstOrDefault();
            var document = declaration?.GetSourceFile()?.Document;
            if (document == null)
                return null;

            var textControlManagerType = TryResolveRuntimeType("JetBrains.TextControl.ITextControlManager");
            if (textControlManagerType == null)
                return null;

            var textControlManager = TryGetRuntimeComponent(textControlManagerType);
            if (textControlManager == null)
                return null;

            return TryFindTextControlForDocument(textControlManagerType, textControlManager, document);
        }
        catch
        {
            return null;
        }
    }

    private object? TryAcquireTextControlBeforeWriteLock(RdRenameSymbolRequest request)
    {
        try
        {
            var renamePlan = RenameMutationPlanner.PlanExactSymbolRename(
                request.Position.FilePath,
                request.Position.Line,
                request.Position.Column);
            if (!renamePlan.CanProceed)
                return null;

            var localDeclarationNode = ResolveExactDeclarationNodeAt(
                request.Position,
                renamePlan.Resolution.ResolvedName!,
                renamePlan.Resolution.TargetKind);
            if (localDeclarationNode == null || !TryGetDeclaredElement(localDeclarationNode, out var element))
                return null;

            return TryFindTextControlForElement(element);
        }
        catch
        {
            return null;
        }
    }

    private object? TryFindTextControlForDocument(Type textControlManagerType, object textControlManager, object document)
    {
        var stopwatch = Stopwatch.StartNew();

        while (true)
        {
            var textControl = TryEnumerateMatchingTextControl(textControlManagerType, textControlManager, document);
            if (textControl != null)
                return textControl;

            if (stopwatch.ElapsedMilliseconds >= TextControlPollTimeoutMs)
                return null;

            Thread.Sleep(TextControlPollIntervalMs);
        }
    }

    private static object? TryEnumerateMatchingTextControl(Type textControlManagerType, object textControlManager, object document)
    {
        var textControlsProperty = textControlManagerType.GetProperty("TextControls", BindingFlags.Public | BindingFlags.Instance);
        if (textControlsProperty?.GetValue(textControlManager) is not IEnumerable textControls)
            return null;

        foreach (var textControl in textControls)
        {
            if (textControl == null)
                continue;

            var candidateDocument = textControl.GetType().GetProperty("Document", BindingFlags.Public | BindingFlags.Instance)?.GetValue(textControl);
            if (ReferenceEquals(candidateDocument, document) || Equals(candidateDocument, document))
                return textControl;
        }

        return null;
    }

    private RenameExecutionAttempt TryExecuteTextControlRename(IDeclaredElement element, string newName, object targetTextControl)
    {
        try
        {
            var declaration = element.GetDeclarations().FirstOrDefault();
            if (declaration == null)
                return RenameExecutionAttempt.Unsupported("No declaration was available for ITextControl-backed rename execution.");

            var caret = targetTextControl.GetType().GetProperty("Caret", BindingFlags.Public | BindingFlags.Instance)?.GetValue(targetTextControl);
            if (caret == null)
                return RenameExecutionAttempt.Unsupported("The matched ITextControl does not expose a caret required for rename positioning.");

            var moveToMethod = caret.GetType().GetMethods(BindingFlags.Public | BindingFlags.Instance)
                .FirstOrDefault(method =>
                {
                    if (!string.Equals(method.Name, "MoveTo", StringComparison.Ordinal))
                        return false;

                    var parameters = method.GetParameters();
                    return parameters.Length == 2 &&
                           parameters[0].ParameterType == typeof(int) &&
                           string.Equals(parameters[1].ParameterType.FullName, "JetBrains.TextControl.CaretVisualPlacement", StringComparison.Ordinal);
                });

            if (moveToMethod == null)
                return RenameExecutionAttempt.Unsupported("The matched ITextControl caret does not expose MoveTo(int, CaretVisualPlacement).");

            var placementType = moveToMethod.GetParameters()[1].ParameterType;
            var dontScrollIfVisible = Enum.Parse(placementType, "DontScrollIfVisible");
            var offset = declaration.GetNavigationRange().StartOffset.Offset;
            moveToMethod.Invoke(caret, new[] { (object)offset, dontScrollIfVisible });

            var dataProvider = new RenameDataProvider(element, newName);
            var renameMethod = typeof(RenameRefactoringService)
                .GetMethods(BindingFlags.Public | BindingFlags.Static | BindingFlags.Instance)
                .FirstOrDefault(method =>
                {
                    if (!string.Equals(method.Name, "Rename", StringComparison.Ordinal) || method.IsGenericMethodDefinition)
                        return false;

                    var parameters = method.GetParameters();
                    return parameters.Length == 3 &&
                           parameters[0].ParameterType == typeof(ISolution) &&
                           typeof(RenameDataProvider).IsAssignableFrom(parameters[1].ParameterType) &&
                           string.Equals(parameters[2].ParameterType.FullName, "JetBrains.TextControl.ITextControl", StringComparison.Ordinal);
                });

            if (renameMethod == null)
                return RenameExecutionAttempt.Unsupported("RenameRefactoringService.Rename(solution, provider, textControl) is unavailable in this Rider runtime.");

            var renameTarget = renameMethod.IsStatic ? null : _renameRefactoringService;
            var renameResult = renameMethod.Invoke(renameTarget, new[] { (object)_solution, dataProvider, targetTextControl });
            return RenameExecutionAttempt.Success($"ITextControl-backed RenameRefactoringService invocation completed using {FormatMethodSignature(renameMethod)} (result={(renameResult == null ? "<null>" : renameResult.ToString())}).");
        }
        catch (TargetInvocationException ex) when (ex.InnerException != null)
        {
            return RenameExecutionAttempt.Unsupported($"ITextControl-backed rename threw {ex.InnerException.GetType().Name}: {ex.InnerException.Message}");
        }
        catch (Exception ex)
        {
            return RenameExecutionAttempt.Unsupported($"ITextControl-backed rename failed: {ex.GetType().Name}: {ex.Message}");
        }
    }

    private object? TryGetRuntimeComponent(Type componentType)
    {
        var component = TryGetRuntimeComponent(_solution, componentType);
        return component ?? TryGetRuntimeComponent(Shell.Instance, componentType);
    }

    private static object? TryGetRuntimeComponent(object host, Type componentType)
    {
        try
        {
            var getComponentMethod = host.GetType().GetMethods(BindingFlags.Public | BindingFlags.Instance)
                .FirstOrDefault(method =>
                    string.Equals(method.Name, "GetComponent", StringComparison.Ordinal) &&
                    method.IsGenericMethodDefinition &&
                    method.GetParameters().Length == 0);

            return getComponentMethod?.MakeGenericMethod(componentType).Invoke(host, Array.Empty<object>());
        }
        catch
        {
            return null;
        }
    }

    private static Type? TryResolveRuntimeType(string fullName)
    {
        return Type.GetType(fullName)
               ?? AppDomain.CurrentDomain.GetAssemblies()
                   .Select(assembly => assembly.GetType(fullName, false))
                   .FirstOrDefault(type => type != null);
    }

    private static string DescribeReflectedTextControl(object? textControl)
    {
        if (textControl == null)
            return "<null>";

        try
        {
            var document = textControl.GetType().GetProperty("Document", BindingFlags.Public | BindingFlags.Instance)?.GetValue(textControl);
            var documentPath = document?.GetType().GetProperty("Moniker", BindingFlags.Public | BindingFlags.Instance)?.GetValue(document) as string;
            return $"type={textControl.GetType().FullName}, document={documentPath ?? "<unknown>"}";
        }
        catch
        {
            return $"type={textControl.GetType().FullName}";
        }
    }

    private static RenameServiceExecutionPlan InspectSymbolRenameServiceExecutionPlan()
    {
        var renameMethods = typeof(RenameRefactoringService)
            .GetMethods(BindingFlags.Public | BindingFlags.Static | BindingFlags.DeclaredOnly)
            .Where(method => method.Name.StartsWith("Rename", StringComparison.Ordinal))
            .OrderBy(method => method.Name, StringComparer.Ordinal)
            .ThenBy(method => method.GetParameters().Length)
            .ToList();

        var signatures = renameMethods
            .Select(FormatMethodSignature)
            .Distinct(StringComparer.Ordinal)
            .ToList();

        var selectedMethod = renameMethods.FirstOrDefault(MethodRequiresTextControl);
        var documentedMethod = renameMethods.FirstOrDefault(method =>
            string.Equals(method.Name, "RenameAndGetConflicts", StringComparison.Ordinal) && MethodRequiresTextControl(method));

        var message =
            "Symbol rename is fail-closed in this Rider runtime. " +
            $"RenameRefactoringService exposes: {string.Join(" || ", signatures)}. " +
            $"The documented service lane is {(documentedMethod == null ? "not discoverable" : "bound to '" + FormatMethodSignature(documentedMethod) + "'")}, " +
            "which still requires JetBrains.TextControl.ITextControl. " +
            "No public headless service overload or custom host entry point analogous to the existing MoveToFolderWorkflow + SimpleWorkflowHost lane was confirmed for symbol rename. " +
            "The backend will not fall back to manual RenameWorkflow.Initialize because runtime trace evidence showed it hangs after 'workflow.construct.end' and never reaches 'workflow.initialize.end'. No files were modified.";

        return RenameServiceExecutionPlan.Unsupported(
            selectedMethod == null ? null : FormatMethodSignature(selectedMethod),
            selectedMethod != null,
            signatures,
            message);
    }

    private static bool MethodRequiresTextControl(MethodInfo method)
        => method.GetParameters().Any(parameter =>
            string.Equals(parameter.ParameterType.FullName, "JetBrains.TextControl.ITextControl", StringComparison.Ordinal));

    private static string FormatMethodSignature(MethodInfo method)
    {
        var parameters = method.GetParameters()
            .Select(parameter => $"{parameter.ParameterType.FullName ?? parameter.ParameterType.Name} {parameter.Name}");
        return $"{method.ReturnType.FullName ?? method.ReturnType.Name} {method.Name}({string.Join(", ", parameters)})";
    }

    private string? TryExecuteDrivenFileRename(IProjectFile projectFile, string newPath)
    {
        try
        {
            var newName = Path.GetFileName(newPath);
            if (string.IsNullOrWhiteSpace(newName))
                return "Rider file rename rejected the requested destination because the new file name was empty.";

            var fileRenameBinding = CreateRuntimeFileRenameDataProvider(projectFile, newName, out var providerFailure);
            if (fileRenameBinding == null)
                return providerFailure;

            var driver = ExecuteRenameWorkflow(fileRenameBinding.DataProvider, lifetime => CreateFileRenameDataContext(projectFile, fileRenameBinding.DataProvider, lifetime));
            var conflicts = driver.Conflicts.ToList();
            if (conflicts.Count > 0)
                return string.Join("; ", conflicts.Select(conflict => conflict.Description).Where(description => !string.IsNullOrWhiteSpace(description)));

            return null;
        }
        catch (TargetInvocationException ex) when (ex.InnerException != null)
        {
            return $"Rider file rename workflow failed: {ex.InnerException.GetType().Name}: {ex.InnerException.Message}";
        }
        catch (Exception ex)
        {
            return $"Rider file rename workflow failed: {ex.GetType().Name}: {ex.Message}";
        }
    }

    private RefactoringDriverWithConflicts ExecuteRenameWorkflow(
        IDeclaredElement element,
        RenameDataProvider dataProvider)
        => ExecuteRenameWorkflow(dataProvider, lifetimeDefinition => CreateRenameDataContext(element, dataProvider, lifetimeDefinition));

    private RefactoringDriverWithConflicts ExecuteRenameWorkflow(
        RenameDataProvider dataProvider,
        Func<LifetimeDefinition, IDataContext> dataContextFactory)
    {
        using var lifetimeDefinition = Lifetime.Define(_solution.GetSolutionLifetimes().UntilSolutionCloseLifetime);
        using var compilationContext = CompilationContextCookie.GetExplicitUniversalContextIfNotSet();
        var dataContext = dataContextFactory(lifetimeDefinition);
        var workflow = new RenameWorkflow(_solution, "Index MCP Rider rename")
        {
            EventBus = Shell.Instance.GetComponent<IEventBus>(),
            WorkflowExecuterLifetime = lifetimeDefinition.Lifetime
        };

        var initialized = workflow.Initialize(dataContext);
        if (!initialized)
            throw new InvalidOperationException("ReSharper rename workflow is not available for the selected symbol.");

        ProcessWorkflowPages(workflow);

        var driver = new RefactoringDriverWithConflicts(new RefactoringDriverStorage());
        var executer = workflow.CreateRefactoring(driver)
                       ?? throw new InvalidOperationException("ReSharper rename workflow did not create a refactoring executer.");

        var preExecuted = workflow.PreExecute(NoOpProgressIndicator.Instance);
        if (!preExecuted)
            throw new InvalidOperationException("ReSharper rename workflow PreExecute returned false.");

        var executed = PsiTransactionCookie.ExecuteConditionally(
            _solution.GetPsiServices(),
            () => executer.Execute(NoOpProgressIndicator.Instance),
            "Index MCP Rider rename");
        if (!executed)
            throw new InvalidOperationException("ReSharper rename workflow Execute returned false.");

        var postExecuted = workflow.PostExecute(NoOpProgressIndicator.Instance);
        if (!postExecuted)
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

    private IDataContext CreateFileRenameDataContext(
        IProjectFile projectFile,
        RenameDataProvider dataProvider,
        LifetimeDefinition lifetimeDefinition)
    {
        var rules = DataRules
            .AddRule("IndexMcpRenameFile", ProjectModelDataConstants.PROJECT_MODEL_ELEMENTS, new IProjectModelElement[] { projectFile })
            .AddRule("IndexMcpRenameFile", ProjectModelDataConstants.SOLUTION, _solution)
            .AddRule("IndexMcpRenameFile", RenameRefactoringService.RenameDataProvider, dataProvider);
        return _solution.GetComponent<DataContexts>().CreateWithDataRules(lifetimeDefinition.Lifetime, rules);
    }

    private RuntimeFileRenameProviderBinding? CreateRuntimeFileRenameDataProvider(IProjectFile projectFile, string newName, out string failureMessage)
    {
        failureMessage = string.Empty;

        var targetBinding = RuntimeRenameTargetBinding.Bind(typeof(RenameDataProvider), projectFile, newName);
        if (!targetBinding.IsSupported || targetBinding.Provider is not RenameDataProvider dataProvider)
        {
            failureMessage = targetBinding.FailureMessage ??
                             "File rename is fail-closed because the Rider runtime could not bind the project file target to RenameDataProvider.";
            return null;
        }

        TrySetRuntimeProperty(dataProvider, "CanBeLocal", false);

        object model;
        try
        {
            model = new CustomRenameModel();
        }
        catch (Exception ex)
        {
            failureMessage = $"File rename is fail-closed because the Rider rename model could not be created headlessly: {ex.GetType().Name}: {ex.Message}";
            return null;
        }

        try
        {
            SetRuntimeProperty(model, "HasUI", false);
            SetRuntimeProperty(model, "QuickRename", false);
            SetRuntimeProperty(model, "CreateRenameConfirmationPage", false);
            SetRuntimeProperty(model, "ChangeTextOccurrences", false);
            SetRuntimeProperty(model, "RenameDerived", false);
            SetRuntimeProperty(model, "Bulk", false);
            if (!TrySetRuntimeProperty(model, "RenameFile", true))
            {
                failureMessage = "File rename is fail-closed because the Rider rename model does not expose RenameFile in this runtime. The backend will not risk symbol rename widening without explicit file-lane support.";
                return null;
            }

            SetRuntimeProperty(dataProvider, "Model", model);
        }
        catch (Exception ex)
        {
            failureMessage = $"File rename is fail-closed because the Rider rename model could not be configured for file-scoped execution: {ex.GetType().Name}: {ex.Message}";
            return null;
        }

        return new RuntimeFileRenameProviderBinding(dataProvider, targetBinding.Kind);
    }

    private sealed record RuntimeFileRenameProviderBinding(
        RenameDataProvider DataProvider,
        RuntimeRenameTargetBindingKind BindingKind);

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

    private static bool TrySetRuntimeProperty(object target, string propertyName, object value)
    {
        var property = target.GetType().GetProperty(propertyName, BindingFlags.Instance | BindingFlags.Public);
        if (property == null || !property.CanWrite)
            return false;

        property.SetValue(target, value);
        return true;
    }

    private static string? GetContainingProjectFilePath(IProjectFile projectFile)
        => projectFile.GetProject()?.ProjectFileLocation.FullPath;

    // ── Type Hierarchy ──────────────────────────────────────────────────────

    private Task<RdTypeHierarchyResult?> HandleGetTypeHierarchy(
        Lifetime lt, RdTypeHierarchyRequest request)
    {
        return Task.Run(() => ExecuteUnderReadLock<RdTypeHierarchyResult?>(() =>
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

            return new RdTypeHierarchyResult(
                ToSymbolInfo(typeElement),
                supertypes,
                subtypes);
        }));
    }

    // ── Find Implementations ────────────────────────────────────────────────

    private Task<RdImplementationsResult?> HandleFindImplementations(
        Lifetime lt, RdImplementationsRequest request)
    {
        return Task.Run(() => ExecuteUnderReadLock<RdImplementationsResult?>(() =>
        {
            var effectiveLimit = GetEffectiveResultLimit(request.Limit);
            var element = ResolveDeclaredElementAt(request.Position);
            if (element == null) return null;

            return element switch
            {
                ITypeElement typeElement => FindTypeImplementations(typeElement, effectiveLimit),
                IOverridableMember overridable => FindMemberImplementations(overridable, effectiveLimit),
                _ => null
            };
        }));
    }

    private RdImplementationsResult FindTypeImplementations(ITypeElement typeElement, int effectiveLimit)
    {
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

        return BuildImplementationsResult(implementations, effectiveLimit);
    }

    private RdImplementationsResult FindMemberImplementations(IOverridableMember target, int effectiveLimit)
    {
        var containingType = target.GetContainingType();
        if (containingType == null)
            return new RdImplementationsResult(new List<RdSymbolInfo>());

        var implementations = new List<RdSymbolInfo>();
        var searchDomain = _solution.GetPsiServices().SearchDomainFactory
            .CreateSearchDomain(_solution, false);
        var declaredType = TypeFactory.CreateType(containingType);

        _solution.GetPsiServices().Finder.FindInheritors(
            declaredType,
            declaredInheritor =>
            {
                var implType = declaredInheritor.GetTypeElement();
                if (implType == null) return FindExecution.Continue;
                foreach (var member in implType.GetMembers())
                {
                    if (member is IOverridableMember overridable && overridable.OverridesOrImplements(target))
                        implementations.Add(ToSymbolInfo(member));
                }
                return FindExecution.Continue;
            },
            searchDomain,
            NoOpProgressIndicator.Instance);

        return BuildImplementationsResult(implementations, effectiveLimit);
    }

    private static RdImplementationsResult BuildImplementationsResult(
        IEnumerable<RdSymbolInfo> implementations, int effectiveLimit)
    {
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

        return new RdImplementationsResult(orderedImplementations);
    }

    // ── Call Hierarchy ──────────────────────────────────────────────────────

    private Task<RdCallHierarchyResult?> HandleGetCallHierarchy(
        Lifetime lt, RdCallHierarchyRequest request)
    {
        return Task.Run(() => ExecuteUnderReadLock<RdCallHierarchyResult?>(() =>
        {
            var callableTarget = ResolveCallHierarchyTarget(request.Target);
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
                            if (!MatchesScope(containingCallable, request.Scope)) continue;

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
                        if (invokedCallable != null &&
                            !Equals(invokedCallable, callableTarget.Element) &&
                            MatchesScope(invokedCallable, request.Scope))
                            callElements.Add(invokedCallable);
                    }
                }
            }

            var orderedCalls = OrderDeclaredElementsDeterministically(callElements)
                .Take(effectiveLimit)
                .Select(ToSymbolInfo)
                .ToList();
            var message = BuildCallHierarchyMessage(request.Direction, orderedCalls.Count);

            return new RdCallHierarchyResult(root, orderedCalls, message);
        }));
    }

    // ── Super Methods ───────────────────────────────────────────────────────

    private Task<RdSuperMethodsResult?> HandleFindSuperMethods(
        Lifetime lt, RdSuperMethodsRequest request)
    {
        return Task.Run(() => ExecuteUnderReadLock<RdSuperMethodsResult?>(() =>
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

            return new RdSuperMethodsResult(methodInfo, hierarchy);
        }));
    }

    // ── File Structure ──────────────────────────────────────────────────────

    private Task<RdFileStructureResult?> HandleGetFileStructure(
        Lifetime lt, RdFileStructureRequest request)
    {
        return Task.Run(() => ExecuteUnderReadLock<RdFileStructureResult?>(() =>
        {
            var psiFile = GetPsiFileForPath(request.FilePath);
            if (psiFile == null) return null;

            var nodes = new List<RdFlatStructureNode>();
            CollectStructureNodes(psiFile, nodes, 0);
            return new RdFileStructureResult(nodes);
        }));
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

    private FindReferencesTargetResolution ResolveTargetForFindReferences(
        Lifetime lt,
        RdSemanticTarget target,
        string scope)
    {
        if (!string.IsNullOrWhiteSpace(target.FilePath) &&
            target.Line.HasValue &&
            target.Column.HasValue)
        {
            var resolvedElement = ResolveDeclaredElementAt(new RdSourcePosition(
                target.FilePath,
                target.Line.Value,
                target.Column.Value));
            return resolvedElement == null
                ? FindReferencesTargetResolution.Failure(
                    "unresolved_target",
                    $"No Rider declaration matches position '{target.FilePath}:{target.Line}:{target.Column}'.",
                    "position")
                : FindReferencesTargetResolution.Success(resolvedElement, "position");
        }

        if (!string.IsNullOrWhiteSpace(target.Language) &&
            !string.IsNullOrWhiteSpace(target.Symbol))
        {
            return ResolveSymbolForFindReferences(lt, target.Language, target.Symbol, scope);
        }

        return FindReferencesTargetResolution.Failure(
            "unsupported_target",
            "Rider find_references requires either a position target or a language+symbol target.",
            "unsupported");
    }



    private static int GetEffectiveResultLimit(int requestedLimit)
    {
        if (requestedLimit <= 0)
            return MaxResults;

        return Math.Min(requestedLimit, MaxResults);
    }

    private CallableTarget? ResolveCallHierarchyTarget(RdSemanticTarget target)
    {
        if (!string.IsNullOrWhiteSpace(target.FilePath) &&
            target.Line.HasValue &&
            target.Column.HasValue)
        {
            return ResolveCallableTarget(ResolveTarget(target)) ??
                   ResolveCallableDeclarationTargetAt(new RdSourcePosition(
                       target.FilePath,
                       target.Line.Value,
                       target.Column.Value));
        }

        return ResolveCallableTarget(ResolveTarget(target));
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

    private static List<RdReferenceInfo> OrderReferenceInfosDeterministically(IEnumerable<RdReferenceInfo> references)
    {
        return references
            .GroupBy(GetReferenceIdentityKey, StringComparer.OrdinalIgnoreCase)
            .Select(group => group
                .OrderBy(ReferenceLocationBucket)
                .ThenBy(reference => NormalizeReferencePath(reference.FilePath), StringComparer.OrdinalIgnoreCase)
                .ThenBy(reference => reference.Line)
                .ThenBy(reference => reference.Column)
                .ThenBy(reference => reference.Kind, StringComparer.OrdinalIgnoreCase)
                .ThenBy(reference => reference.Context, StringComparer.OrdinalIgnoreCase)
                .First())
            .OrderBy(ReferenceLocationBucket)
            .ThenBy(reference => NormalizeReferencePath(reference.FilePath), StringComparer.OrdinalIgnoreCase)
            .ThenBy(reference => reference.Line)
            .ThenBy(reference => reference.Column)
            .ThenBy(reference => reference.Kind, StringComparer.OrdinalIgnoreCase)
            .ThenBy(reference => reference.Context, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    private static string GetReferenceIdentityKey(RdReferenceInfo reference)
    {
        return HasConcreteReferenceLocation(reference)
            ? $"{NormalizeReferencePath(reference.FilePath)}:{reference.Line}:{reference.Column}:{reference.Kind}"
            : $"<source-unavailable>:{reference.Kind}:{reference.Context}:{string.Join(">", reference.AstPath)}";
    }

    private static int ReferenceLocationBucket(RdReferenceInfo reference) => HasConcreteReferenceLocation(reference) ? 0 : 1;

    private static bool HasConcreteReferenceLocation(RdReferenceInfo reference) =>
        !string.IsNullOrWhiteSpace(reference.FilePath) && reference.Line > 0 && reference.Column > 0;

    private static string NormalizeReferencePath(string? path) => string.IsNullOrWhiteSpace(path) ? "~" : path.Trim();

    private static string? BuildCallHierarchyMessage(string direction, int resultCount)
    {
        if (!string.Equals(direction, "callers", StringComparison.OrdinalIgnoreCase) || resultCount > 0)
            return null;

        return "No static callers were found. For framework-routed endpoints (ASP.NET/WebAPI routing, reflection, or other framework dispatch), this is a static-analysis limitation and does not imply backend failure.";
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

    private FindReferencesTargetResolution ResolveSymbolForFindReferences(
        Lifetime lt,
        string? language,
        string symbol,
        string scope)
    {
        _ = lt;
        _ = scope;
        var parseResult = ParseIndexedSymbol(language, symbol);
        if (!parseResult.IsSuccess)
            return FindReferencesTargetResolution.FromResolution(parseResult.ToResolution(), "parse");

        return ResolveSymbolIndexedForFindReferences(language, symbol);
    }



    private IndexedSymbolResolution ResolveSymbolIndexed(string? language, string symbol)
    {
        var parseResult = ParseIndexedSymbol(language, symbol);
        if (!parseResult.IsSuccess)
            return parseResult.ToResolution();

        var parsed = parseResult.Symbol!;
        var primary = ResolveParsedSymbol(parsed, symbol);
        if (primary.TryGetElement() != null)
            return primary;

        // Fallback for unresolved dotted bare symbols ("Class.Property", "Class.Method"):
        // re-attempt resolution by splitting on the last dot and treating the right side
        // as a member of the left-side type. Only kicks in when the user did not already
        // use the '#' separator (parsed.MemberName == null) and the input has at least
        // one '.', so that bare short names and explicit member forms are unaffected.
        if (parsed.MemberName == null
            && TrySplitDottedSymbolAsMember(parsed.ContainerQualifiedName, out var splitContainer, out var splitMember))
        {
            var altSymbol = splitContainer + "#" + splitMember;
            var altResolution = ResolveParsedSymbolFromString(parsed.Language, altSymbol);
            if (altResolution.TryGetElement() != null)
                return altResolution;
        }

        return primary;
    }

    private IndexedSymbolResolution ResolveParsedSymbolFromString(string language, string symbol)
    {
        var parseResult = ParseIndexedSymbol(language, symbol);
        if (!parseResult.IsSuccess)
            return parseResult.ToResolution();
        return ResolveParsedSymbol(parseResult.Symbol!, symbol);
    }

    private IndexedSymbolResolution ResolveParsedSymbol(ParsedRiderSymbol parsed, string symbol)
    {
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

    /// <summary>
    /// Split a bare dotted symbol on its last '.' into a container + member candidate. Returns
    /// false when there is no '.' (i.e. nothing to split). Used by both the find_definition and
    /// find_references resolvers to recover from "Class.Property" / "Class.Method" forms that
    /// previously only succeeded when written as "Class#Property".
    /// </summary>
    private static bool TrySplitDottedSymbolAsMember(string container, out string splitContainer, out string splitMember)
    {
        splitContainer = string.Empty;
        splitMember = string.Empty;

        var lastDot = container.LastIndexOf('.');
        if (lastDot <= 0 || lastDot >= container.Length - 1)
            return false;

        splitContainer = container.Substring(0, lastDot);
        splitMember = container.Substring(lastDot + 1);
        return !string.IsNullOrWhiteSpace(splitContainer) && !string.IsNullOrWhiteSpace(splitMember);
    }

    private List<IDeclaredElement> ResolveContainerCandidates(string language, string containerQualifiedName)
    {
        var normalizedContainer = NormalizeQualifiedName(containerQualifiedName);
        return FilterAndOrderContainerCandidates(
            EnumerateContainerCandidatesAcrossScopes(normalizedContainer, language),
            normalizedContainer,
            language);
    }

    /// <summary>
    /// Walk all candidate symbol scopes — project modules first, then library scopes —
    /// in priority order, returning every container that matches <paramref name="normalizedContainer"/>
    /// in either qualified or short-name form. Shared between the find_definition and
    /// find_references resolvers (the latter delegates to <see cref="ResolveSymbolIndexed"/>).
    /// </summary>
    private IEnumerable<IDeclaredElement> EnumerateContainerCandidatesAcrossScopes(
        string normalizedContainer,
        string language)
    {
        var projectMatches = EnumerateProjectPsiModules(Lifetime.Eternal, null)
            .Distinct()
            .SelectMany(module => ResolveContainerCandidatesFromScopeWithFallback(
                _solution.GetPsiServices().Symbols.GetSymbolScope(module, true, false),
                normalizedContainer,
                language))
            .ToList();

        if (projectMatches.Count > 0)
            return projectMatches;

        return new[] { LibrarySymbolScope.REFERENCED, LibrarySymbolScope.TRANSITIVE, LibrarySymbolScope.FULL }
            .SelectMany(scopeKind => ResolveContainerCandidatesFromScopeWithFallback(
                _solution.GetPsiServices().Symbols.GetSymbolScope(scopeKind, false),
                normalizedContainer,
                language))
            .ToList();
    }

    private FindReferencesTargetResolution ResolveSymbolIndexedForFindReferences(string? language, string symbol)
    {
        var parseResult = ParseIndexedSymbol(language, symbol);
        if (!parseResult.IsSuccess)
            return FindReferencesTargetResolution.FromResolution(parseResult.ToResolution(), "parse");

        // The find-references resolver used to duplicate the entire flow in
        // ResolveSymbolIndexed (container lookup + member matching + ordering + single-match
        // selection + dotted-split fallback) just to wrap the outcome in a different result
        // type. Both surfaces share the same lookup semantics now, so wrap the canonical
        // resolver instead. We lose the "dotted_member_split" origin label on success
        // (acceptable: that label was only surfaced in success-path diagnostic text, not in
        // any contract-bearing field).
        return FindReferencesTargetResolution.FromResolution(
            ResolveSymbolIndexed(language, symbol),
            "primary_lookup");
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

    /// <summary>
    /// FQN-first container lookup with a short-name fallback. For dotted inputs (which already
    /// cover their nested-type variant via <see cref="EnumerateQualifiedNameCandidates"/>) we
    /// keep the existing FQN-only behaviour. For bare single-segment inputs (no '.') we also
    /// walk <see cref="ISymbolScope.GetAllShortNames"/> and resolve any case-insensitive match
    /// via <see cref="ISymbolScope.GetElementsByShortName"/>. This is the symbol-resolution
    /// analogue of the short-name walk used by <c>find_class</c> at
    /// <see cref="EnumerateSymbolScopeDeclaredElements"/>.
    /// </summary>
    private IEnumerable<IDeclaredElement> ResolveContainerCandidatesFromScopeWithFallback(
        ISymbolScope symbolScope,
        string normalizedContainer,
        string language)
    {
        var seen = new HashSet<IDeclaredElement>();
        foreach (var candidate in ResolveContainerCandidatesFromScope(symbolScope, normalizedContainer, language))
        {
            if (seen.Add(candidate))
                yield return candidate;
        }

        if (seen.Count > 0 || normalizedContainer.Contains('.', StringComparison.Ordinal))
            yield break;

        foreach (var shortName in symbolScope.GetAllShortNames()
                     .Where(name => name.Equals(normalizedContainer, StringComparison.OrdinalIgnoreCase)))
        {
            foreach (var element in symbolScope.GetElementsByShortName(shortName))
            {
                if (seen.Add(element) && MatchesLanguage(element, language))
                    yield return element;
            }
        }
    }

    private static List<IDeclaredElement> FilterAndOrderContainerCandidates(
        IEnumerable<IDeclaredElement> candidates,
        string normalizedContainer,
        string language)
    {
        return candidates
            .Where(element => MatchesContainerCandidate(element, normalizedContainer, language))
            .Distinct()
            .OrderBy(BestDeclarationRank)
            .ThenBy(GetQualifiedName, StringComparer.OrdinalIgnoreCase)
            .ThenBy(GetDeclarationPath, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    private static bool MatchesContainerCandidate(IDeclaredElement element, string normalizedContainer, string language)
    {
        if (!MatchesLanguage(element, language) || !HasCompatibleSourceLanguage(element, language))
            return false;

        var elementFqn = NormalizeQualifiedName(GetQualifiedName(element));
        if (elementFqn.Equals(normalizedContainer, StringComparison.OrdinalIgnoreCase))
            return true;

        // Accept short-name matches: when the user wrote a bare single-segment name (e.g.
        // "MainWindowViewModel"), accept a candidate whose unqualified name matches even if
        // its FQN includes a namespace. Only applies when the request has no '.' so we never
        // weaken explicit FQN constraints.
        if (normalizedContainer.Contains('.', StringComparison.Ordinal))
            return false;

        var shortName = element.ShortName;
        return !string.IsNullOrEmpty(shortName)
               && string.Equals(shortName, normalizedContainer, StringComparison.OrdinalIgnoreCase);
    }

    private static bool HasCompatibleSourceLanguage(IDeclaredElement element, string language)
    {
        var normalizedLanguage = NormalizeLanguage(language);
        if (normalizedLanguage == null)
            return true;

        var declarationPaths = element.GetDeclarations()
            .Select(declaration => declaration.GetSourceFile()?.GetLocation().FullPath)
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        if (declarationPaths.Count == 0)
            return true;

        return declarationPaths.All(path => MatchesLanguageForSourcePath(path, normalizedLanguage));
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
        yield return GetPresentableTypeName(declaredType, languageName);

        if (declaredType is IDeclaredType namedType && namedType.GetTypeElement() is { } typeElement)
        {
            yield return typeElement.ShortName;
            yield return typeElement.GetClrName().FullName;
        }
    }

    private static string GetPresentableTypeName(
        IType declaredType,
        string? preferredLanguageName,
        Func<string, object?>? presentationLanguageResolver = null,
        Func<IType, object, string?>? presentableNameInvoker = null)
    {
        var presentationLanguage = ResolveRequestedPresentationLanguage(preferredLanguageName, presentationLanguageResolver);
        if (!ReferenceEquals(presentationLanguage, CSharpLanguage.Instance!))
        {
            var resolvedName = (presentableNameInvoker ?? InvokePresentableTypeName)(declaredType, presentationLanguage);
            if (!string.IsNullOrWhiteSpace(resolvedName))
                return resolvedName;
        }

        return declaredType.GetPresentableName(CSharpLanguage.Instance!).ToString();
    }

    private static object ResolveRequestedPresentationLanguage(
        string? preferredLanguageName,
        Func<string, object?>? presentationLanguageResolver = null)
    {
        _ = preferredLanguageName;
        _ = presentationLanguageResolver;
        return CSharpLanguage.Instance!;
    }

    private static string? InvokePresentableTypeName(IType declaredType, object presentationLanguage)
    {
        var method = ourGetPresentableNameMethod.Value;
        if (method == null)
            return null;

        var parameterType = method.GetParameters()[0].ParameterType;
        if (!parameterType.IsInstanceOfType(presentationLanguage))
            return null;

        return method.Invoke(declaredType, new[] { presentationLanguage })?.ToString();
    }

    private static IndexedSymbolParseResult ParseIndexedSymbol(string? language, string symbol)
    {
        var normalizedLanguage = NormalizeLanguage(language);
        if (!IsSupportedRiderLanguage(language))
        {
            return IndexedSymbolParseResult.Unsupported(
                $"Rider indexed symbol resolution supports only C#, got '{language ?? "<null>"}'.");
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
            return IndexedSymbolParseResult.Success(new ParsedRiderSymbol(normalizedLanguage!, containerPart, null, null, false));

        if (!TryParseMemberPart(containerPart, memberPart, out var parsedMemberName, out var parameterTypes, out var isConstructor, out var memberError))
            return IndexedSymbolParseResult.Invalid(memberError!);

        return IndexedSymbolParseResult.Success(new ParsedRiderSymbol(normalizedLanguage!, containerPart, parsedMemberName, parameterTypes, isConstructor));
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
        return NormalizeLanguage(language) == "C#";
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

    private static string NormalizeClrQualifiedNamePreservingArity(string value)
    {
        return value.Trim()
            .Replace("global::", string.Empty, StringComparison.OrdinalIgnoreCase)
            .Replace("global.", string.Empty, StringComparison.OrdinalIgnoreCase)
            .Replace("+", ".", StringComparison.Ordinal);
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

        var presentationLanguage = element.PresentationLanguage?.Name;
        var parameterNames = parametersOwner.Parameters
            .Select(parameter => CanonicalizeTypeName(GetPresentableTypeName(parameter.Type, presentationLanguage)));
        return $"{element.ShortName}({string.Join(",", parameterNames)})";
    }

    private static string? BuildPresentableSignature(IDeclaredElement element)
    {
        if (element is not IParametersOwner parametersOwner)
            return null;

        var presentationLanguage = element.PresentationLanguage?.Name;
        return $"{element.ShortName}({string.Join(", ", parametersOwner.Parameters.Select(parameter => GetPresentableTypeName(parameter.Type, presentationLanguage)))})";
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
            ["object"] = "System.Object"
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

    private IEnumerable<IDeclaredElement> EnumerateDeclaredSymbolElements(Lifetime lt, IReadOnlyList<string>? allowedProjectFileExtensions)
    {
        foreach (var psiFile in EnumerateProjectPsiFiles(lt, allowedProjectFileExtensions))
        {
            EnsureLifetimeAlive(lt);
            var elements = new List<IDeclaredElement>();
            CollectDeclaredElements(psiFile, elements);
            foreach (var element in elements)
                yield return element;
        }
    }

    private IEnumerable<IDeclaredElement> EnumerateIndexedSymbolElements(Lifetime lt, string query, string language)
    {
        foreach (var libraryScope in new[] { LibrarySymbolScope.REFERENCED, LibrarySymbolScope.TRANSITIVE, LibrarySymbolScope.FULL })
        {
            EnsureLifetimeAlive(lt);
            foreach (var element in EnumerateSymbolScopeDeclaredElements(
                         _solution.GetPsiServices().Symbols.GetSymbolScope(libraryScope, false),
                         query,
                         language))
            {
                yield return element;
            }
        }
    }

    private static IEnumerable<IDeclaredElement> EnumerateSymbolScopeDeclaredElements(
        ISymbolScope symbolScope,
        string query,
        string language)
    {
        var seen = new HashSet<IDeclaredElement>();

        if (query.Contains('.', StringComparison.Ordinal))
        {
            foreach (var element in symbolScope.GetElementsByQualifiedName(query))
            {
                if (seen.Add(element) && MatchesLanguage(element, language))
                    yield return element;
            }
        }

        foreach (var shortName in symbolScope.GetAllShortNames().Where(name => MatchesName(name, query, "substring")))
        {
            foreach (var element in symbolScope.GetElementsByShortName(shortName))
            {
                if (seen.Add(element) && MatchesLanguage(element, language))
                    yield return element;
            }
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
        foreach (var sourceFile in EnumerateProjectSourceFiles(lt, allowedProjectFileExtensions))
        {
            var psiFile = sourceFile.GetPrimaryPsiFile();
            if (psiFile != null) yield return psiFile;
        }
    }

    private IEnumerable<IPsiModule> EnumerateProjectPsiModules(Lifetime lt, IReadOnlyList<string>? allowedProjectFileExtensions)
    {
        foreach (var sourceFile in EnumerateProjectSourceFiles(lt, allowedProjectFileExtensions))
            yield return sourceFile.PsiModule;
    }

    private IEnumerable<IPsiSourceFile> EnumerateProjectSourceFiles(Lifetime lt, IReadOnlyList<string>? allowedProjectFileExtensions)
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

                    yield return sourceFile;
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
        var normalizedLanguage = NormalizeLanguage(language);
        if (normalizedLanguage == null) return false;
        var presentationLanguage = element.PresentationLanguage?.Name;
        if (presentationLanguage != null && presentationLanguage.Equals(normalizedLanguage, StringComparison.OrdinalIgnoreCase))
            return true;

        var filePath = PickPreferredDeclaration(element)?.GetSourceFile()?.GetLocation().FullPath ?? "";
        return MatchesLanguageForSourcePath(filePath, normalizedLanguage);
    }

    private static bool MatchesLanguageForSourcePath(string? filePath, string normalizedLanguage)
    {
        if (string.IsNullOrWhiteSpace(filePath))
        {
            // CLR/BCL/library declarations commonly resolve without a source file. Keep strict
            // file-extension filtering for source-backed declarations, but do not discard
            // source-less library symbols for C# indexed lookups.
            return normalizedLanguage.Equals("C#", StringComparison.OrdinalIgnoreCase);
        }

        return normalizedLanguage.Equals("C#", StringComparison.OrdinalIgnoreCase) &&
               filePath.EndsWith(".cs", StringComparison.OrdinalIgnoreCase);
    }

    private static string? NormalizeLanguage(string? language)
    {
        if (string.IsNullOrWhiteSpace(language))
            return null;

        var normalized = language.Trim();
        if (normalized.Equals("C#", StringComparison.OrdinalIgnoreCase) ||
            normalized.Equals("CSharp", StringComparison.OrdinalIgnoreCase) ||
            normalized.Equals("cs", StringComparison.OrdinalIgnoreCase) ||
            normalized.Equals("C-Sharp", StringComparison.OrdinalIgnoreCase))
            return "C#";

        return null;
    }

    private static string? InferFindReferencesTargetLanguage(RdSemanticTarget target)
    {
        var normalizedLanguage = NormalizeLanguage(target.Language);
        if (normalizedLanguage != null)
            return normalizedLanguage;

        var fileExtension = Path.GetExtension(target.FilePath ?? string.Empty);
        return fileExtension.Equals(".cs", StringComparison.OrdinalIgnoreCase)
            ? "C#"
            : null;
    }

    private static IReadOnlyList<string>? GetProjectFileExtensions(string? normalizedLanguage)
    {
        return normalizedLanguage switch
        {
            "C#" => new[] { ".cs" },
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

    private static bool MatchesScope(IDeclaredElement? element, string scope) =>
        MatchesScope(element, scope, includeUnknownProjectCandidates: false);

    private static bool MatchesScope(
        IDeclaredElement? element,
        string scope,
        bool includeUnknownProjectCandidates)
    {
        if (element == null)
            return MatchesPathScope(null, scope);

        var path = GetDeclarationPath(element);
        if (!string.IsNullOrWhiteSpace(path))
            return MatchesPathScope(path, scope);

        return scope switch
        {
            "project_and_libraries" => true,
            "project_test_files" => false,
            _ => includeUnknownProjectCandidates
        };
    }

    private static bool MatchesPathScope(string? path, string scope)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            return scope.Equals("project_and_libraries", StringComparison.OrdinalIgnoreCase);
        }

        var isTest = IsTestPath(path);
        return scope switch
        {
            "project_and_libraries" => true,
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
    //   - .cs hand-written file                                     -> 0
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

        var isCs = path.EndsWith(".cs", StringComparison.OrdinalIgnoreCase);
        return isCs ? 0 : 1;
    }

    private static int BestDeclarationRank(IDeclaredElement element) =>
        element.GetDeclarations().Select(DeclarationRank).DefaultIfEmpty(4).Min();

    private static IDeclaration? PickPreferredDeclaration(IDeclaredElement element) =>
        element.GetDeclarations().OrderBy(DeclarationRank).FirstOrDefault();

    private static DefinitionLocationInfo BuildDefinitionLocation(IDeclaredElement element, IDeclaration? declaration)
    {
        if (declaration == null)
        {
            return new DefinitionLocationInfo(
                LocationKind: "sourceUnavailable",
                LocationDisplayName: GetQualifiedName(element));
        }

        var sourceFile = declaration.GetSourceFile();
        var fullPath = sourceFile?.GetLocation().FullPath;
        if (!string.IsNullOrWhiteSpace(fullPath))
        {
            return new DefinitionLocationInfo(
                LocationKind: IsDecompiledLocation(fullPath) ? "decompiled" : "source",
                LocationDisplayName: fullPath);
        }

        return new DefinitionLocationInfo(
            LocationKind: "metadata",
            LocationDisplayName: GetQualifiedName(element));
    }

    private static bool IsDecompiledLocation(string fullPath)
    {
        var normalized = fullPath.Replace('\\', '/');
        return normalized.Contains("/metadata/", StringComparison.OrdinalIgnoreCase) ||
               normalized.Contains("/decompiled/", StringComparison.OrdinalIgnoreCase) ||
               normalized.Contains("/assembly explorer/", StringComparison.OrdinalIgnoreCase);
    }

    private readonly record struct DefinitionLocationInfo(string LocationKind, string? LocationDisplayName);

    private static bool IsTestPath(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return false;
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



    private static IDeclaredElement? TryResolveDirectReferenceElement(ITreeNode node)
    {
        for (ITreeNode? current = node; current != null; current = current.Parent)
        {
            foreach (var reference in current.GetReferences())
            {
                var resolved = reference.Resolve().DeclaredElement;
                if (resolved != null)
                    return resolved;
            }

            if (current is IReferenceExpression referenceExpression)
            {
                var resolved = referenceExpression.Reference?.Resolve().DeclaredElement;
                if (resolved != null)
                    return resolved;
            }

            if (current is IDeclaration or ITypeDeclaration)
                break;
        }

        return null;
    }

    private static IDeclaredElement? TryResolveDeclarationNameElement(ITreeNode node)
    {
        var nodeText = node.GetText().Trim();
        if (string.IsNullOrEmpty(nodeText))
            return null;

        var declaration = node.GetContainingNode<IDeclaration>();
        if (declaration != null &&
            string.Equals(nodeText, declaration.DeclaredName, StringComparison.Ordinal) &&
            declaration.DeclaredElement != null)
        {
            return declaration.DeclaredElement;
        }

        var typeDeclaration = node.GetContainingNode<ITypeDeclaration>();
        if (typeDeclaration != null &&
            string.Equals(nodeText, typeDeclaration.DeclaredName, StringComparison.Ordinal) &&
            typeDeclaration.DeclaredElement != null)
        {
            return typeDeclaration.DeclaredElement;
        }

        return null;
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
        var resolvedReference = TryResolveDirectReferenceElement(node);
        if (resolvedReference != null)
            return resolvedReference;

        return TryResolveDeclarationNameElement(node);
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

    private ITreeNode? ResolveExactDeclarationNodeAt(RdSourcePosition position, string expectedName, string? expectedTargetKind)
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

            var declaration = node.GetContainingNode<IDeclaration>();
            if (declaration != null && string.Equals(declaration.DeclaredName, expectedName, StringComparison.Ordinal))
                return declaration as ITreeNode;

            if (string.Equals(expectedTargetKind, MutationTargetKind.Type.ToContractValue(), StringComparison.OrdinalIgnoreCase))
            {
                var typeDeclaration = node.GetContainingNode<ITypeDeclaration>();
                if (typeDeclaration != null && string.Equals(typeDeclaration.DeclaredName, expectedName, StringComparison.Ordinal))
                    return typeDeclaration as ITreeNode;
            }
        }

        return null;
    }

    private static string? GetDeclarationNodeName(ITreeNode? node)
    {
        if (node is ITypeDeclaration typeDeclaration) return typeDeclaration.DeclaredName;
        if (node is IDeclaration declaration) return declaration.DeclaredName;
        return null;
    }

    private List<JetBrains.ReSharper.Psi.Resolve.IReference> FindReferences(
        Lifetime lt,
        IDeclaredElement element,
        int limit,
        string searchMode = "legacy")
    {
        var searchDomain = _solution.GetPsiServices().SearchDomainFactory
            .CreateSearchDomain(_solution, false);
        return FindReferences(lt, element, searchDomain, limit, searchMode: searchMode);
    }














    private List<JetBrains.ReSharper.Psi.Resolve.IReference> FindReferences(
        Lifetime lt,
        IDeclaredElement element,
        ISearchDomain searchDomain,
        int limit,
        string searchMode = "legacy",
        IProgressIndicator? progressIndicator = null)
    {
        var referenceResults = new List<FindResult>();
        var effectiveProgressIndicator = ResolveFindReferencesProgressIndicator(progressIndicator);
        var consumer = new FindResultConsumer<List<FindResult>>(
            result => new List<FindResult> { result },
            results =>
            {
                EnsureLifetimeAlive(lt);
                foreach (var result in results)
                {
                    EnsureLifetimeAlive(lt);
                    referenceResults.Add(result);
                    if (referenceResults.Count >= limit)
                        return FindExecution.Stop;
                }
                return FindExecution.Continue;
            });

        var findStopwatch = Stopwatch.StartNew();
        try
        {
            _solution.GetPsiServices().Finder.FindReferences(
                element,
                searchDomain,
                consumer,
                effectiveProgressIndicator,
                false);
        }
        catch (Exception ex)
        {
            findStopwatch.Stop();
            throw;
        }
        findStopwatch.Stop();

        return referenceResults
            .OfType<FindResultReference>()
            .Select(result => result.Reference)
            .Where(reference => reference.IsValid())
            .ToList();
    }

    private static IProgressIndicator ResolveFindReferencesProgressIndicator(IProgressIndicator? progressIndicator)
    {
        return progressIndicator ?? NoOpProgressIndicator.Instance;
    }

    private static string FormatExtensions(IReadOnlyList<string>? extensions)
    {
        return extensions == null || extensions.Count == 0
            ? "<none>"
            : string.Join(",", extensions);
    }

    private static string DescribeElement(IDeclaredElement? element)
    {
        if (element == null)
            return "<null>";

        var kind = element.GetType().Name;
        var qualifiedName = GetQualifiedName(element);
        return string.IsNullOrWhiteSpace(qualifiedName)
            ? kind
            : $"{kind}:{qualifiedName}";
    }

    private static string DescribeFindReferencesResolvedTarget(IDeclaredElement element, string origin)
    {
        ArgumentNullException.ThrowIfNull(element);

        var declaration = PickPreferredDeclaration(element);
        var sourceFilePath = declaration?.GetSourceFile()?.GetLocation().FullPath;
        var elementType = element is ITypeElement ? "ITypeElement" : "IDeclaredElement";
        var hasSourcePath = !string.IsNullOrWhiteSpace(sourceFilePath) ? "true" : "false";

        return $"origin={origin}, qualifiedName={GetQualifiedName(element)}, kind={GetElementKind(element)}, " +
               $"elementType={elementType}, presentationLanguage={element.PresentationLanguage?.Name ?? "<null>"}, " +
               $"hasSourcePath={hasSourcePath}, sourceFilePath={sourceFilePath ?? "<absent>"}";
    }

    private static string FormatFindReferencesUnresolvedTargetMessage(
        string? language,
        string symbol,
        string scope,
        string status,
        string? message,
        string origin)
    {
        return $"find_references target_resolution_failed status={status}, origin={origin}, language={language ?? "<null>"}, " +
               $"symbol={symbol}, scope={scope}, message={message ?? "<none>"}";
    }

    private static string FormatFindReferencesTargetResolutionFailureMessage(
        RdSemanticTarget target,
        string scope,
        string status,
        string? message,
        string origin)
    {
        if (IsFindReferencesSymbolTarget(target))
        {
            return FormatFindReferencesUnresolvedTargetMessage(
                target.Language,
                target.Symbol ?? "<null>",
                scope,
                status,
                message,
                origin);
        }

        var position = !string.IsNullOrWhiteSpace(target.FilePath) && target.Line.HasValue && target.Column.HasValue
            ? $"{target.FilePath}:{target.Line}:{target.Column}"
            : "<unknown>";

        return $"find_references target_resolution_failed status={status}, origin={origin}, target={position}, " +
               $"scope={scope}, message={message ?? "<none>"}";
    }


    private static bool IsFindReferencesSymbolTarget(RdSemanticTarget target)
    {
        return !string.IsNullOrWhiteSpace(target.Language) && !string.IsNullOrWhiteSpace(target.Symbol);
    }

    private static bool ShouldRaiseFindReferencesTargetResolutionFailure(RdSemanticTarget target, string status)
    {
        return IsFindReferencesSymbolTarget(target) ||
               status.Equals("unavailable_feature", StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsFindReferencesPositionTarget(RdSemanticTarget target)
    {
        return !string.IsNullOrWhiteSpace(target.FilePath) &&
               target.Line.HasValue &&
               target.Column.HasValue;
    }


    private sealed class RenameExecutionAttempt
    {
        private RenameExecutionAttempt(bool succeeded, bool isUnsupported, string message)
        {
            Succeeded = succeeded;
            IsUnsupported = isUnsupported;
            Message = message;
        }

        public bool Succeeded { get; }

        public bool IsUnsupported { get; }

        public string Message { get; }

        public static RenameExecutionAttempt Success(string message = "")
            => new(true, false, message);

        public static RenameExecutionAttempt Unsupported(string message)
            => new(false, true, message);
    }

    private sealed class RenameServiceExecutionPlan
    {
        private RenameServiceExecutionPlan(bool isSupported, string? selectedMethodSignature, bool requiresTextControl,
            IReadOnlyList<string> availableMethodSignatures, string message)
        {
            IsSupported = isSupported;
            SelectedMethodSignature = selectedMethodSignature;
            RequiresTextControl = requiresTextControl;
            AvailableMethodSignatures = availableMethodSignatures;
            Message = message;
        }

        public bool IsSupported { get; }

        public string? SelectedMethodSignature { get; }

        public bool RequiresTextControl { get; }

        public IReadOnlyList<string> AvailableMethodSignatures { get; }

        public string Message { get; }

        public static RenameServiceExecutionPlan Unsupported(string? selectedMethodSignature, bool requiresTextControl,
            IReadOnlyList<string> availableMethodSignatures, string message)
            => new(false, selectedMethodSignature, requiresTextControl, availableMethodSignatures, message);
    }


    private sealed class FindReferencesTargetResolution
    {
        private FindReferencesTargetResolution(string status, string? message, IDeclaredElement? element, string origin)
        {
            Status = status;
            Message = message;
            Element = element;
            Origin = origin;
        }

        public string Status { get; }
        public string? Message { get; }
        public IDeclaredElement? Element { get; }
        public string Origin { get; }

        public static FindReferencesTargetResolution Success(IDeclaredElement element, string origin) =>
            new("success", null, element, origin);

        public static FindReferencesTargetResolution Failure(string status, string? message, string origin) =>
            new(status, message, null, origin);

        public static FindReferencesTargetResolution FromResolution(IndexedSymbolResolution resolution, string origin)
        {
            ArgumentNullException.ThrowIfNull(resolution);
            return resolution.TryGetElement() != null
                ? Success(resolution.TryGetElement()!, origin)
                : Failure(resolution.Status, resolution.Message, origin);
        }

        public IDeclaredElement? TryGetElement() => Status == "success" ? Element : null;
    }

    private List<string> GetPotentiallyAffectedFiles(IDeclaredElement element)
    {
        return element.GetDeclarations()
            .Select(declaration => declaration.GetSourceFile()?.GetLocation().FullPath)
            .Concat(FindReferences(Lifetime.Eternal, element, MaxResults)
                .Select(reference => reference.GetTreeNode().GetSourceFile()?.GetLocation().FullPath))
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Cast<string>()
            .ToList();
    }

    private MutationSemanticEvidence CollectFileMutationSemanticEvidence(string primaryFilePath, IReadOnlyList<string> declaredTypeNames)
    {
        var projectFile = GetProjectFileForPath(primaryFilePath);
        var psiFile = GetPsiFileForPath(primaryFilePath);
        var confirmedAffectedFiles = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var confirmedReferenceFiles = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        if (projectFile != null && File.Exists(primaryFilePath))
            confirmedAffectedFiles.Add(primaryFilePath);

        if (psiFile != null)
        {
            foreach (var element in GetDeclaredTypeElementsForFile(psiFile, primaryFilePath, declaredTypeNames))
            {
                foreach (var affectedFile in GetPotentiallyAffectedFiles(element))
                {
                    if (string.IsNullOrWhiteSpace(affectedFile))
                        continue;

                    confirmedAffectedFiles.Add(affectedFile);
                    if (!PathsEqual(affectedFile, primaryFilePath))
                        confirmedReferenceFiles.Add(affectedFile);
                }
            }
        }

        return MutationSemanticEvidence.Create(
            projectFile != null,
            psiFile != null,
            confirmedAffectedFiles,
            confirmedReferenceFiles);
    }

    private IEnumerable<ITypeElement> GetDeclaredTypeElementsForFile(IFile psiFile, string primaryFilePath, IReadOnlyList<string> declaredTypeNames)
    {
        var declaredElements = new List<IDeclaredElement>();
        CollectDeclaredElements(psiFile, declaredElements);
        var expectedNames = new HashSet<string>(declaredTypeNames ?? Array.Empty<string>(), StringComparer.Ordinal);

        return declaredElements
            .OfType<ITypeElement>()
            .Where(element => ElementDeclaredInFile(element, primaryFilePath))
            .Where(element => expectedNames.Count == 0 || expectedNames.Contains(element.ShortName))
            .Distinct();
    }

    private static bool ElementDeclaredInFile(IDeclaredElement element, string primaryFilePath)
    {
        return element.GetDeclarations()
            .Select(declaration => declaration.GetSourceFile()?.GetLocation().FullPath)
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Any(path => PathsEqual(path, primaryFilePath));
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

    private IProjectFile? GetProjectFileForPath(string filePath)
    {
        if (string.IsNullOrWhiteSpace(filePath) || _solution == null)
            return null;

        var normalizedPath = filePath.Replace('/', Path.DirectorySeparatorChar);
        var vfp = VirtualFileSystemPath.Parse(normalizedPath, InteractionContext.SolutionContext);
        return _solution.FindProjectItemsByLocation(vfp)
            .OfType<IProjectFile>()
            .FirstOrDefault();
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
        var signature = BuildPresentableSignature(element);

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

                var signature = BuildPresentableSignature(element);

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
    public FindReferencesResolutionPlan(string targetKind)
    {
        TargetKind = targetKind;
    }

    public string TargetKind { get; }
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

