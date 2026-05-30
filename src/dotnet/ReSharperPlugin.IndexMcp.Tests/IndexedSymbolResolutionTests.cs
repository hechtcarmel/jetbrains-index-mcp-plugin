using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.Serialization;
using JetBrains.Core;
using JetBrains.Lifetimes;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.Caches;
using JetBrains.ReSharper.Psi.CSharp;
using JetBrains.ReSharper.Psi.Tree;
using JetBrains.Rider.Model.IndexMcp;
using JetBrains.UI.RichText;
using JetBrains.Util;
using NUnit.Framework;

namespace ReSharperPlugin.IndexMcp.Tests;

[TestFixture]
public class IndexedSymbolResolutionTests
{
    private static readonly Type BackendHostType = typeof(IndexMcpBackendHost);


    [Test]
    public void ParseIndexedSymbol_RejectsInvalidMemberSeparator()
    {
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "C#", "Demo.Type#");

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(parseResult, "IsSuccess"), Is.False);
            Assert.That(GetResolutionStatus(GetProperty<object>(parseResult, "Failure")), Is.EqualTo("invalid_symbol"));
        });
    }

    [Test]
    public void ResolveSingleMatch_ReturnsSuccessForUniqueCandidate()
    {
        var element = CreateDeclaredElement("Unique");

        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            new List<IDeclaredElement> { element },
            "ambiguous",
            "unresolved");

        Assert.Multiple(() =>
        {
            Assert.That(GetResolutionStatus(resolution), Is.EqualTo("success"));
            Assert.That(GetProperty<object>(resolution, "Element"), Is.SameAs(element));
        });
    }

    [Test]
    public void ResolveSingleMatch_ReturnsUnresolvedForMissingCandidates()
    {
        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            new List<IDeclaredElement>(),
            "ambiguous",
            "unresolved");

        Assert.That(GetResolutionStatus(resolution), Is.EqualTo("unresolved_symbol"));
    }

    [Test]
    public void ResolveSingleMatch_ReturnsAmbiguousForMultipleCandidates()
    {
        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            new List<IDeclaredElement> { CreateDeclaredElement("A"), CreateDeclaredElement("B") },
            "ambiguous",
            "unresolved");

        Assert.That(GetResolutionStatus(resolution), Is.EqualTo("ambiguous_match"));
    }

    [Test]
    public void FindDefinition_SourceLessResolvedSymbolsShouldNotShortCircuitToNull()
    {
        var sourcePath = Path.GetFullPath(Path.Combine(
            AppContext.BaseDirectory,
            "..", "..", "..", "..",
            "ReSharperPlugin.IndexMcp",
            "IndexMcpBackendHost.cs"));
        var source = File.ReadAllText(sourcePath);

        Assert.Multiple(() =>
        {
            Assert.That(source, Does.Not.Contain("if (navigationElement == null) return (RdDefinitionResult?)null;"),
                "Resolved framework/library symbols should be able to return a structured metadata/decompiled/source-unavailable definition result instead of failing when no source declaration exists.");
            Assert.That(source, Does.Contain("locationKind"),
                "Regression guard: the backend definition payload should describe whether Rider resolved source, metadata, decompiled, or source-unavailable navigation.");
        });
    }

    [Test]
    public void ResolveSingleMatch_TypeOnlySymbolPrefersTypeElementOverNamespaceFallbacks()
    {
        var namespaceCandidate = CreateNamespaceElement("RagasaWebServices.WhiteList");
        var typeCandidate = CreateTypeElementCandidate("WhiteList");

        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            new List<IDeclaredElement> { namespaceCandidate, typeCandidate },
            "ambiguous",
            "unresolved");

        Assert.Multiple(() =>
        {
            Assert.That(GetResolutionStatus(resolution), Is.EqualTo("success"));
            Assert.That(GetProperty<object>(resolution, "Element"), Is.SameAs(typeCandidate));
        });
    }

    [Test]
    public void ResolveSingleMatch_TypeOnlySymbolRejectsNamespaceOnlyFallback()
    {
        var namespaceCandidate = CreateNamespaceElement("RagasaWebServices.WhiteList");

        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            new List<IDeclaredElement> { namespaceCandidate },
            "ambiguous",
            "unresolved");

        Assert.That(GetResolutionStatus(resolution), Is.EqualTo("unresolved_symbol"));
    }

    [Test]
    public void ResolveMemberCandidates_DisambiguatesOverloadsUsingParameterContract()
    {
        var matchingMember = CreateTypeMember("Run", "string");
        var otherOverload = CreateTypeMember("Run", "int");
        var container = CreateTypeElement(matchingMember, otherOverload);
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "C#", "Demo.Service#Run(string)");
        var parsedSymbol = GetProperty<object>(parseResult, "Symbol");

        var candidates = ((IEnumerable)InvokePrivateStatic("ResolveMemberCandidates", container, parsedSymbol))
            .Cast<object>()
            .ToList();

        Assert.That(candidates, Is.EqualTo(new[] { matchingMember }));
    }

    [Test]
    public void ResolveSingleMatch_CSharpParameterizedMemberTargetRemainsMemberScoped()
    {
        var matchingMember = CreateTypeMember("Run", "string");
        var otherOverload = CreateTypeMember("Run", "int");
        var container = CreateTypeElement(matchingMember, otherOverload);
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "C#", "Demo.Service#Run(string)");
        var parsedSymbol = GetProperty<object>(parseResult, "Symbol");

        var candidates = ((IEnumerable)InvokePrivateStatic("ResolveMemberCandidates", container, parsedSymbol))
            .Cast<IDeclaredElement>()
            .ToList();

        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            candidates,
            "ambiguous",
            "unresolved");

        Assert.Multiple(() =>
        {
            Assert.That(GetResolutionStatus(resolution), Is.EqualTo("success"));
            Assert.That(GetProperty<object>(resolution, "Element"), Is.SameAs(matchingMember));
            Assert.That(GetProperty<object>(resolution, "Element"), Is.Not.InstanceOf<ITypeElement>());
        });
    }

    [Test]
    public void ResolveCallableTarget_RejectsNonCallableProperties()
    {
        var property = CreateDeclaredElement(
            "Value",
            kind: DeclaredElementKind.Property,
            handlers: new Dictionary<string, object?>
            {
                ["get_Parameters"] = new List<IParameter>(),
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });

        var callableTarget = InvokePrivateStatic("ResolveCallableTarget", property);

        Assert.That(callableTarget, Is.Null);
    }

    [TestCase(7)]
    [TestCase(200)]
    [TestCase(999)]
    public void ResultLimit_UsesImplementationsRequestLimit(int requestedLimit)
    {
        var request = new RdImplementationsRequest(new RdSourcePosition("demo.cs", 1, 1), "project_files", requestedLimit);

        var effectiveLimit = (int)InvokePrivateStatic("GetEffectiveResultLimit", request.Limit)!;

        Assert.That(effectiveLimit, Is.EqualTo(Math.Min(requestedLimit <= 0 ? 200 : requestedLimit, 200)));
    }

    [TestCase(5)]
    [TestCase(200)]
    [TestCase(500)]
    public void ResultLimit_UsesCallHierarchyRequestLimit(int requestedLimit)
    {
        var request = new RdCallHierarchyRequest(
            new RdSemanticTarget(null, null, null, "C#", "Demo.Service#Run"),
            "callers",
            1,
            "project_files",
            requestedLimit);

        var effectiveLimit = (int)InvokePrivateStatic("GetEffectiveResultLimit", request.Limit)!;

        Assert.That(effectiveLimit, Is.EqualTo(Math.Min(requestedLimit <= 0 ? 200 : requestedLimit, 200)));
    }

    [Test]
    public void GetPresentableTypeName_CSharpPreferredLanguage_PreservesCSharpPresentation()
    {
        var declaredType = CreateType("csharp-name");
        var resolverCalled = false;

        var presentableName = (string)InvokePrivateStatic(
            "GetPresentableTypeName",
            declaredType,
            "C#",
            (Func<string, object?>)(_ =>
            {
                resolverCalled = true;
                return new object();
            }),
            (Func<IType, object, string?>)((_, _) => "unexpected"))!;

        Assert.Multiple(() =>
        {
            Assert.That(presentableName, Is.EqualTo("csharp-name"));
            Assert.That(resolverCalled, Is.False);
        });
    }

    [Test]
    public void CallHierarchy_CSharpPositionTarget_GenericReturnTypePositions_ShouldFallbackToContainingDeclaration()
    {
        var sourcePath = Path.GetFullPath(Path.Combine(
            AppContext.BaseDirectory,
            "..", "..", "..", "..",
            "ReSharperPlugin.IndexMcp",
            "IndexMcpBackendHost.cs"));
        var source = File.ReadAllText(sourcePath);

        StringAssert.IsMatch(
            @"ResolveCallableTarget\(ResolveTarget\(target\)\)\s*\?\?\s*ResolveCallableDeclarationTargetAt\(|ResolveCallableDeclarationTargetAt\([^\)]*target\.[^\)]*\)\s*\?\?\s*ResolveCallableTarget\(ResolveTarget\(target\)\)",
            source,
            "C# position-based call hierarchy should keep semantic resolution for method identifiers AND fall back to the containing callable declaration when the caret lands on a generic return type token that resolves to a non-callable symbol.");
    }

    [Test]
    public void CallHierarchy_CSharpPositionTarget_MethodIdentifierPositions_ShouldRetainSemanticCallableResolutionPath()
    {
        var sourcePath = Path.GetFullPath(Path.Combine(
            AppContext.BaseDirectory,
            "..", "..", "..", "..",
            "ReSharperPlugin.IndexMcp",
            "IndexMcpBackendHost.cs"));
        var source = File.ReadAllText(sourcePath);

        Assert.That(source, Does.Contain("ResolveCallableTarget(ResolveTarget(target))"),
            "Method-identifier call hierarchy should keep the direct semantic callable resolution path; the generic-return fallback must extend position handling, not replace the existing happy path.");
    }

    [Test]
    public void CallHierarchy_CSharpPositionTarget_MethodIdentifierPositions_ShouldPreferDirectResolutionBeforeDeclarationFallback()
    {
        var sourcePath = Path.GetFullPath(Path.Combine(
            AppContext.BaseDirectory,
            "..", "..", "..", "..",
            "ReSharperPlugin.IndexMcp",
            "IndexMcpBackendHost.cs"));
        var source = File.ReadAllText(sourcePath);

        var positionBranchStart = source.IndexOf("if (!string.IsNullOrWhiteSpace(target.FilePath)", StringComparison.Ordinal);
        var positionBranchEnd = source.IndexOf("return ResolveCallableTarget(ResolveTarget(target));", positionBranchStart, StringComparison.Ordinal);
        var positionBranch = source.Substring(positionBranchStart, positionBranchEnd - positionBranchStart);
        var normalizedBranch = string.Join(" ", positionBranch
            .Split(new[] { ' ', '\r', '\n', '\t' }, StringSplitOptions.RemoveEmptyEntries));

        Assert.Multiple(() =>
        {
            Assert.That(positionBranchStart, Is.GreaterThanOrEqualTo(0), "Expected a dedicated position-target branch in ResolveCallHierarchyTarget.");
            Assert.That(positionBranchEnd, Is.GreaterThan(positionBranchStart), "Expected the C# position-target branch to end before the non-position return path.");
            Assert.That(normalizedBranch,
                Does.Contain("return ResolveCallableTarget(ResolveTarget(target)) ?? ResolveCallableDeclarationTargetAt(new RdSourcePosition("),
                "Expected the C# position-target branch to try direct semantic callable resolution first and only then fall back to the containing declaration.");
        });
    }

    [Test]
    public void MatchesScope_SourceLessPathsRemainHiddenFromProjectFilesUntilLibraryScopeIsRequested()
    {
        var projectOnly = (bool)InvokePrivateStatic("MatchesScope", null, "project_files")!;
        var projectAndLibraries = (bool)InvokePrivateStatic("MatchesScope", null, "project_and_libraries")!;

        Assert.Multiple(() =>
        {
            Assert.That(projectOnly, Is.False,
                "Source-less/dependency-backed declarations should not be treated as project_files because that makes Rider caller/reference scope separation loose and misleading.");
            Assert.That(projectAndLibraries, Is.True,
                "Source-less/dependency-backed declarations should remain visible once the caller explicitly opts into project_and_libraries.");
        });
    }

    [Test]
    public void CallHierarchy_CallersScopeHandlingShouldBeExplicitInsteadOfWholeSolutionWide()
    {
        var sourcePath = Path.GetFullPath(Path.Combine(
            AppContext.BaseDirectory,
            "..", "..", "..", "..",
            "ReSharperPlugin.IndexMcp",
            "IndexMcpBackendHost.cs"));
        var source = File.ReadAllText(sourcePath);

        Assert.Multiple(() =>
        {
            Assert.That(source, Does.Contain("request.Scope"),
                "Regression guard: Rider caller-scope behavior must be expressed explicitly in the backend instead of being implicit or undocumented.");
            Assert.That(
                source.Contains("MatchesScope(containingCallable, request.Scope)") ||
                source.Contains("CreateSearchDomainForCallHierarchy(request.Scope") ||
                source.Contains("CreateCallHierarchySearchDomain(request.Scope"),
                Is.True,
                "Rider caller search should either filter caller rows by request.Scope or build a scope-aware search domain instead of using a whole-solution domain for every scope.");
        });
    }

    [Test]
    public void FindReferences_ResultOrderingAndTruncationShouldBeDeterministicAfterDeduplication()
    {
        var sourcePath = Path.GetFullPath(Path.Combine(
            AppContext.BaseDirectory,
            "..", "..", "..", "..",
            "ReSharperPlugin.IndexMcp",
            "IndexMcpBackendHost.cs"));
        var source = File.ReadAllText(sourcePath);

        // Deduplication + deterministic ordering now live in OrderReferenceInfosDeterministically,
        // which CollectScopedReferenceInfos returns and HandleFindReferences truncates. The
        // ordering method must dedup (GroupBy on the reference identity key) before its final
        // deterministic OrderBy, and the caller must truncate the already-ordered list.
        var orderMethodIndex = source.IndexOf(
            "List<RdReferenceInfo> OrderReferenceInfosDeterministically", StringComparison.Ordinal);
        var dedupIndex = orderMethodIndex >= 0
            ? source.IndexOf(".GroupBy(GetReferenceIdentityKey", orderMethodIndex, StringComparison.Ordinal)
            : -1;
        var orderByIndex = dedupIndex >= 0
            ? source.IndexOf(".OrderBy(ReferenceLocationBucket)", dedupIndex, StringComparison.Ordinal)
            : -1;

        Assert.Multiple(() =>
        {
            Assert.That(orderMethodIndex >= 0 && dedupIndex >= 0,
                Is.True,
                "Regression guard: Rider reference results still need an explicit distinct phase (GroupBy on the reference identity key) before ordering.");
            Assert.That(orderByIndex >= 0 && dedupIndex < orderByIndex,
                Is.True,
                "Reference rows should be deduplicated and then ordered deterministically; backend enumeration order is not a stable API contract.");
            Assert.That(source, Does.Contain("orderedReferences.Take(effectiveLimit)"),
                "HandleFindReferences must truncate the already-ordered, scope-filtered reference list rather than raw enumeration order.");
        });
    }

    [Test]
    public void CallHierarchyAndReferenceProtocols_ShouldExposeActionableFrameworkRoutedMessaging()
    {
        var backendSourcePath = Path.GetFullPath(Path.Combine(
            AppContext.BaseDirectory,
            "..", "..", "..", "..",
            "ReSharperPlugin.IndexMcp",
            "IndexMcpBackendHost.cs"));
        var protocolSourcePath = Path.GetFullPath(Path.Combine(
            AppContext.BaseDirectory,
            "..", "..", "..", "..", "..", "..",
            "protocol",
            "src",
            "main",
            "kotlin",
            "model",
            "rider",
            "IndexMcpModel.kt"));

        var backendSource = File.ReadAllText(backendSourcePath);
        var protocolSource = File.ReadAllText(protocolSourcePath);

        Assert.Multiple(() =>
        {
            Assert.That(backendSource, Does.Contain("framework-routed")
                .Or.Contain("framework routed")
                .Or.Contain("does not imply backend failure"),
                "Backend/user-facing Rider messaging should explicitly explain that empty WebAPI/controller callers can be caused by framework routing rather than a backend failure.");
            Assert.That(protocolSource, Does.Contain("private val RdCallHierarchyResult = structdef {")
                .And.Contain("field(\"message\"")
                .Or.Contain("private val RdFindReferencesResult = structdef {\n        field(\"message\""),
                "Protocol results need an explicit message channel for actionable empty-result guidance instead of forcing the frontend to guess from an empty list.");
        });
    }

    [Test]
    public void CallHierarchy_SymbolTargetUsesSemanticResolutionInsteadOfFrontendSourceRewrite()
    {
        var sourcePath = Path.GetFullPath(Path.Combine(
            AppContext.BaseDirectory,
            "..", "..", "..", "..",
            "ReSharperPlugin.IndexMcp",
            "IndexMcpBackendHost.cs"));
        var source = File.ReadAllText(sourcePath);

        Assert.Multiple(() =>
        {
            Assert.That(source, Does.Contain("ResolveCallHierarchyTarget(request.Target)"),
                "Call hierarchy should resolve semantic Rider symbol targets through a backend-native target resolver instead of relying on frontend source-position rewriting.");
            Assert.That(source, Does.Not.Contain("ResolveCallableTarget(ResolveTarget(request.Target))"),
                "Regression guard: general call hierarchy resolution should no longer inline the old declaration-only path for semantic symbol targets.");
        });
    }

    [Test]
    public void CallHierarchy_CSharpPositionTarget_GenericReturnTypeToken_ShouldResolveNearestCallableDeclaration()
    {
        var callableElement = CreateDeclaredElement("LoadAsync", declarations: new List<IDeclaration>());
        var callableDeclaration = CreateDeclaration(callableElement, declaredName: "LoadAsync");
        var genericReturnTypeToken = CreateTreeNode(CreateTreeNode(callableDeclaration), new Dictionary<string, object?>
        {
            ["GetText"] = "Task"
        });

        var resolvedNode = InvokePrivateStatic("ResolveNearestCallableDeclarationNode", genericReturnTypeToken);

        Assert.That(resolvedNode, Is.SameAs(callableDeclaration),
            "When the caret lands on a generic return type token inside a method signature, call hierarchy fallback should climb to the containing callable declaration instead of failing or widening to an unrelated symbol.");
    }

    [Test]
    public void CallHierarchy_CSharpPositionTarget_NonCallableBodyToken_ShouldResolveContainingMethod()
    {
        var methodElement = CreateDeclaredElement("LoadAsync", declarations: new List<IDeclaration>());
        var methodDeclaration = CreateDeclaration(methodElement, declaredName: "LoadAsync");
        var statementNode = CreateTreeNode(methodDeclaration);
        var identifierToken = CreateTreeNode(statementNode, new Dictionary<string, object?>
        {
            ["GetText"] = "items"
        });

        var resolvedElement = InvokePrivateStatic("ResolveContainingCallableElement", identifierToken);

        Assert.That(resolvedElement, Is.SameAs(methodElement),
            "Non-callable tokens inside a method body should resolve to the containing method when call hierarchy is asked for a position target.");
    }

    [Test]
    public void CallHierarchy_CSharpPositionTarget_NonCallableSignatureToken_ShouldResolveContainingMethod()
    {
        var methodElement = CreateDeclaredElement("LoadAsync", declarations: new List<IDeclaration>());
        var methodDeclaration = CreateDeclaration(methodElement, declaredName: "LoadAsync");
        var parameterListNode = CreateTreeNode(methodDeclaration);
        var genericTypeToken = CreateTreeNode(parameterListNode, new Dictionary<string, object?>
        {
            ["GetText"] = "CancellationToken"
        });

        var resolvedElement = InvokePrivateStatic("ResolveContainingCallableElement", genericTypeToken);

        Assert.That(resolvedElement, Is.SameAs(methodElement),
            "Signature tokens that are not themselves callable declarations should still resolve to the containing method for position-based call hierarchy fallback.");
    }

    [Test]
    public void CallHierarchy_CSharpPositionTarget_PropertyTokenOutsideCallable_ShouldNotResolveContainingMethod()
    {
        var propertyElement = CreateDeclaredElement(
            "Current",
            kind: DeclaredElementKind.Property,
            handlers: new Dictionary<string, object?>
            {
                ["get_Parameters"] = new List<IParameter>()
            });
        var propertyDeclaration = CreateDeclaration(propertyElement, declaredName: "Current");
        var propertyToken = CreateTreeNode(propertyDeclaration, new Dictionary<string, object?>
        {
            ["GetText"] = "Current"
        });

        var resolvedElement = InvokePrivateStatic("ResolveContainingCallableElement", propertyToken);

        Assert.That(resolvedElement, Is.Null,
            "Property tokens outside any callable must not be widened to an unrelated method by position-based call hierarchy fallback.");
    }

    [Test]
    public void CallHierarchy_CSharpPositionTarget_FieldTokenOutsideCallable_ShouldNotResolveContainingMethod()
    {
        var fieldElement = ProxyFactory.Create<IField>(new Dictionary<string, object?>
        {
            ["get_ShortName"] = "_current",
            ["GetDeclarations"] = new List<IDeclaration>()
        });
        var fieldDeclaration = CreateDeclaration(fieldElement, declaredName: "_current");
        var fieldToken = CreateTreeNode(fieldDeclaration, new Dictionary<string, object?>
        {
            ["GetText"] = "_current"
        });

        var resolvedElement = InvokePrivateStatic("ResolveContainingCallableElement", fieldToken);

        Assert.That(resolvedElement, Is.Null,
            "Field tokens outside any callable must stay unresolved instead of being redirected to a containing callable that does not exist.");
    }

    [Test]
    public void TryResolveDeclarationNameElement_DoesNotWidenNonDeclarationTokenToContainingDeclaration()
    {
        var enclosingMethod = CreateDeclaredElement("getFromsharepointAhorroType");
        var declarationNode = CreateDeclaration(enclosingMethod);
        var unrelatedTokenNode = CreateTreeNode(declarationNode, new Dictionary<string, object?>
        {
            ["GetText"] = ","
        });

        var resolved = InvokePrivateStatic("TryResolveDeclarationNameElement", unrelatedTokenNode);

        Assert.That(resolved, Is.Null,
            "Declaration fallback must fail closed for non-name tokens instead of widening to the enclosing declaration.");
    }

    [Test]
    public void BuildFindTypesSearchPlan_CSharpProjectFiles_UsesOnlyCsFilesAndSkipsExpensiveFallback()
    {
        var plan = InvokePrivateStatic("BuildFindTypesSearchPlan", "C#", "project_files", "exact", "Demo.Service");

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(plan!, "UseProjectDeclaredTypeScan"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "UseExactQualifiedProjectLookup"), Is.True);
            Assert.That(GetProperty<bool>(plan!, "UseIndexedTypeFallback"), Is.False);
            Assert.That(GetProperty<IReadOnlyList<string>>(plan!, "AllowedProjectFileExtensions"),
                Is.EqualTo(new[] { ".cs" }));
        });
    }

    [Test]
    public void BuildFindTypesSearchPlan_ProjectFilesWithoutLanguage_PreservesLegacyEnumeration()
    {
        var plan = InvokePrivateStatic("BuildFindTypesSearchPlan", null, "project_files", "exact", "Demo.Service");

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(plan!, "UseProjectDeclaredTypeScan"), Is.True);
            Assert.That(GetProperty<bool>(plan!, "UseExactQualifiedProjectLookup"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "UseIndexedTypeFallback"), Is.False);
            Assert.That(GetProperty<IReadOnlyList<string>?>(plan!, "AllowedProjectFileExtensions"), Is.Null);
        });
    }

    [Test]
    public void ParseIndexedSymbol_CSharpAliases_CanonicalizeToCSharp()
    {
        foreach (var language in new[] { "C#", "CSharp", "CSHARP" })
        {
            var parseResult = InvokePrivateStatic("ParseIndexedSymbol", language, "Demo.Service#Run(string)");

            Assert.Multiple(() =>
            {
                Assert.That(GetProperty<bool>(parseResult!, "IsSuccess"), Is.True, $"Expected alias '{language}' to be accepted.");
                var parsedSymbol = GetProperty<object>(parseResult!, "Symbol");
                Assert.That(GetProperty<string>(parsedSymbol, "Language"), Is.EqualTo("C#"));
                Assert.That(GetProperty<string>(parsedSymbol, "ContainerQualifiedName"), Is.EqualTo("Demo.Service"));
                Assert.That(GetProperty<string>(parsedSymbol, "MemberName"), Is.EqualTo("Run"));
            });
        }
    }

    [Test]
    public void BuildFindSymbolsResult_CSharpAliases_PreserveMemberLevelResults()
    {
        var containingType = CreateTypeElementCandidate(
            "ReadOnlyBaselineService",
            qualifiedName: "Demo.ReadOnlyBaselineService",
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });
        var property = CreateDeclaredElement(
            shortName: "Current",
            declarations: new List<IDeclaration> { CreateDeclarationWithSourcePath("C:/repo/src/ReadOnlyBaselineService.cs") },
            kind: DeclaredElementKind.Property,
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance,
                ["get_ContainingType"] = containingType,
                ["get_Parameters"] = new List<IParameter>()
            });

        var aliasResults = new[]
        {
            (RdFindSymbolsResult)InvokePrivateStatic("BuildFindSymbolsResult", new List<IDeclaredElement> { property }, "Current", "project_files", "C#", 25)!,
            (RdFindSymbolsResult)InvokePrivateStatic("BuildFindSymbolsResult", new List<IDeclaredElement> { property }, "Current", "project_files", "CSharp", 25)!,
            (RdFindSymbolsResult)InvokePrivateStatic("BuildFindSymbolsResult", new List<IDeclaredElement> { property }, "Current", "project_files", "CSHARP", 25)!
        };

        Assert.That(aliasResults.Select(result => result.TotalCount).ToArray(), Is.EqualTo(new[] { 1, 1, 1 }));

        var firstSymbol = aliasResults[0].Symbols.Single();
        Assert.Multiple(() =>
        {
            Assert.That(firstSymbol.Name, Is.EqualTo("Current"));
            Assert.That(firstSymbol.QualifiedName, Is.EqualTo("Demo.ReadOnlyBaselineService.Current"));
            Assert.That(firstSymbol.Kind, Is.EqualTo("PROPERTY"));
            Assert.That(firstSymbol.Language, Is.EqualTo("C#"));
        });

        Assert.That(aliasResults[1].Symbols.Single(), Is.EqualTo(firstSymbol));
        Assert.That(aliasResults[2].Symbols.Single(), Is.EqualTo(firstSymbol));
    }

    [Test]
    public void FindReferences_LegacySearch_UsesNoOpProgressIndicator()
    {
        var method = BackendHostType.GetMethods(BindingFlags.NonPublic | BindingFlags.Static)
            .Single(candidate => candidate.Name == "ResolveFindReferencesProgressIndicator" && candidate.GetParameters().Length == 1);
        var resolved = method.Invoke(null, new object?[] { null });
        var noOpIndicatorType = BackendHostType.Assembly.GetType("ReSharperPlugin.IndexMcp.NoOpProgressIndicator");
        Assert.That(noOpIndicatorType, Is.Not.Null, "Missing type 'NoOpProgressIndicator'.");
        var noOpInstance = noOpIndicatorType!.GetField("Instance", BindingFlags.Public | BindingFlags.Static)?.GetValue(null);

        Assert.That(resolved, Is.SameAs(noOpInstance));
    }

    private static string GetResolutionStatus(object? resolution)
    {
        Assert.That(resolution, Is.Not.Null);
        return GetProperty<string>(resolution!, "Status");
    }

    private static object CreateIndexedSymbolResolution(string status, string? message, IDeclaredElement? element = null)
    {
        var resolutionType = BackendHostType.Assembly.GetType("ReSharperPlugin.IndexMcp.IndexedSymbolResolution");
        Assert.That(resolutionType, Is.Not.Null, "Missing type 'IndexedSymbolResolution'.");

        var constructor = resolutionType!.GetConstructor(
            BindingFlags.Instance | BindingFlags.NonPublic,
            null,
            new[] { typeof(string), typeof(string), typeof(IDeclaredElement) },
            null);
        Assert.That(constructor, Is.Not.Null, "Missing IndexedSymbolResolution(string, string?, IDeclaredElement?) constructor.");

        return constructor!.Invoke(new object?[] { status, message, element });
    }

    private static T GetProperty<T>(object target, string name)
    {
        var property = target.GetType().GetProperty(name, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
        Assert.That(property, Is.Not.Null, $"Missing property '{name}' on {target.GetType().FullName}.");
        return (T)property!.GetValue(target)!;
    }

    private static object? InvokePrivateStatic(string methodName, params object?[] args)
    {
        var methods = BackendHostType.GetMethods(BindingFlags.NonPublic | BindingFlags.Static)
            .Where(method => method.Name == methodName)
            .ToList();
        Assert.That(methods, Is.Not.Empty, $"Missing method '{methodName}'.");

        var methodInfo = methods.Single(method => method.GetParameters().Length == args.Length);
        return methodInfo.Invoke(null, args);
    }

    private static object? InvokePrivateInstance(object target, string methodName, params object?[] args)
    {
        var methods = BackendHostType.GetMethods(BindingFlags.NonPublic | BindingFlags.Instance)
            .Where(method => method.Name == methodName)
            .ToList();
        Assert.That(methods, Is.Not.Empty, $"Missing method '{methodName}'.");

        var methodInfo = methods.Single(method => method.GetParameters().Length == args.Length);
        return methodInfo.Invoke(target, args);
    }

    private static object? InvokePrivateNestedStatic(string nestedTypeName, string methodName, params object?[] args)
    {
        var nestedType = BackendHostType.GetNestedType(nestedTypeName, BindingFlags.NonPublic);
        Assert.That(nestedType, Is.Not.Null, $"Missing nested type '{nestedTypeName}'.");

        var methods = nestedType!.GetMethods(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static)
            .Where(method => method.Name == methodName)
            .ToList();
        Assert.That(methods, Is.Not.Empty, $"Missing method '{methodName}' on nested type '{nestedTypeName}'.");

        var methodInfo = methods.Single(method => method.GetParameters().Length == args.Length);
        return methodInfo.Invoke(null, args);
    }

    private static object CreateUninitializedBackendHost()
    {
        return FormatterServices.GetUninitializedObject(BackendHostType);
    }


    private static IDeclaredElement CreateDeclaredElement(
        string shortName,
        IList<IDeclaration>? declarations = null,
        DeclaredElementKind kind = DeclaredElementKind.Callable,
        IDictionary<string, object?>? handlers = null)
    {
        var configuredHandlers = new Dictionary<string, object?>(handlers ?? new Dictionary<string, object?>())
        {
            ["get_ShortName"] = shortName,
            ["GetDeclarations"] = declarations ?? new List<IDeclaration>()
        };

        return kind switch
        {
            DeclaredElementKind.Property => ProxyFactory.Create<ITestPropertyElement>(configuredHandlers),
            _ => ProxyFactory.Create<ITestCallableElement>(configuredHandlers)
        };
    }

    private static IDeclaration CreateDeclaration(IDeclaredElement? declaredElement = null, ITreeNode? parent = null, string? declaredName = null)
    {
        return ProxyFactory.Create<IDeclaration>(new Dictionary<string, object?>
        {
            ["get_DeclaredElement"] = declaredElement,
            ["get_Parent"] = parent,
            ["get_DeclaredName"] = declaredName
        });
    }

    private static IDeclaration CreateDeclarationWithSourcePath(string filePath, IDeclaredElement? declaredElement = null, ITreeNode? parent = null)
    {
        var sourceFile = ProxyFactory.Create<IPsiSourceFile>(new Dictionary<string, object?>
        {
            ["GetLocation"] = VirtualFileSystemPath.Parse(filePath, InteractionContext.SolutionContext)
        });

        return ProxyFactory.Create<IDeclaration>(new Dictionary<string, object?>
        {
            ["get_DeclaredElement"] = declaredElement,
            ["get_Parent"] = parent,
            ["GetSourceFile"] = sourceFile,
            ["get_SourceFile"] = sourceFile
        });
    }

    private static ITypeDeclaration CreateTypeDeclaration(IDeclaredElement? declaredElement = null, ITreeNode? parent = null, string? declaredName = null)
    {
        return ProxyFactory.Create<ITypeDeclaration>(new Dictionary<string, object?>
        {
            ["get_DeclaredElement"] = declaredElement,
            ["get_Parent"] = parent,
            ["get_DeclaredName"] = declaredName
        });
    }

    private static ITreeNode CreateTreeNode(ITreeNode? parent = null)
    {
        return ProxyFactory.Create<ITreeNode>(new Dictionary<string, object?>
        {
            ["get_Parent"] = parent
        });
    }

    private static ITreeNode CreateTreeNode(ITreeNode? parent, IDictionary<string, object?> handlers)
    {
        var configuredHandlers = new Dictionary<string, object?>(handlers, StringComparer.Ordinal)
        {
            ["get_Parent"] = parent
        };

        return ProxyFactory.Create<ITreeNode>(configuredHandlers);
    }

    private static ITypeElement CreateTypeElement(params ITypeMember[] members)
    {
        return ProxyFactory.Create<ITypeElement>(new Dictionary<string, object?>
        {
            ["GetMembers"] = members.ToList()
        });
    }

    private static ITypeElement CreateTypeElementCandidate(
        string shortName,
        string? qualifiedName = null,
        IList<IDeclaration>? declarations = null,
        IDictionary<string, object?>? handlers = null)
    {
        var configuredHandlers = new Dictionary<string, object?>(handlers ?? new Dictionary<string, object?>())
        {
            ["get_ShortName"] = shortName,
            ["GetMembers"] = new List<ITypeMember>(),
            ["GetDeclarations"] = declarations ?? new List<IDeclaration>(),
            ["GetClrName"] = CreateClrName(qualifiedName ?? shortName)
        };

        return ProxyFactory.Create<ITypeElement>(configuredHandlers);
    }

    private static ISymbolScope CreateSymbolScope(string qualifiedName, params IDeclaredElement[] elements)
    {
        return ProxyFactory.Create<ISymbolScope>(new Dictionary<string, object?>
        {
            ["GetElementsByQualifiedName"] = (Func<object?[], object?>)(args =>
            {
                var requestedName = args.FirstOrDefault() as string;
                return string.Equals(requestedName, qualifiedName, StringComparison.Ordinal)
                    ? elements.Cast<IClrDeclaredElement>().ToList()
                    : new List<IClrDeclaredElement>();
            })
        });
    }

    private static object CreateClrName(string qualifiedName)
    {
        var clrNameType = typeof(ITypeElement).GetMethod("GetClrName")!.ReturnType;

        if (clrNameType.IsInterface)
        {
            return ProxyFactory.Create(clrNameType, new Dictionary<string, object?>
            {
                ["get_FullName"] = qualifiedName,
                ["ToString"] = qualifiedName
            });
        }

        var stringConstructor = clrNameType.GetConstructor(new[] { typeof(string) });
        if (stringConstructor != null)
            return stringConstructor.Invoke(new object[] { qualifiedName });

        var parseMethod = clrNameType.GetMethod(
            "Parse",
            BindingFlags.Public | BindingFlags.Static,
            null,
            new[] { typeof(string) },
            null);
        if (parseMethod != null)
            return parseMethod.Invoke(null, new object[] { qualifiedName })!;

        Assert.Fail($"Unable to construct CLR name for '{qualifiedName}' using {clrNameType.FullName}.");
        return null!;
    }

    private static INamespace CreateNamespaceElement(string qualifiedName)
    {
        return ProxyFactory.Create<INamespace>(new Dictionary<string, object?>
        {
            ["get_QualifiedName"] = qualifiedName,
            ["get_ShortName"] = qualifiedName.Split('.').Last()
        });
    }

    private static ITypeMember CreateTypeMember(
        string shortName,
        IList<IDeclaration>? declarations = null,
        ITypeElement? containingType = null,
        params string[] parameterTypes)
    {
        var parameters = parameterTypes.Select(CreateParameter).ToList();
        return ProxyFactory.Create<ITestTypeMember>(
            new Dictionary<string, object?>
            {
                ["get_ShortName"] = shortName,
                ["GetDeclarations"] = declarations ?? new List<IDeclaration>(),
                ["get_PresentationLanguage"] = CSharpLanguage.Instance,
                ["get_ContainingType"] = containingType,
                ["get_Parameters"] = parameters
            });
    }

    private static ITypeMember CreateTypeMember(string shortName, params string[] parameterTypes)
    {
        return CreateTypeMember(shortName, null, null, parameterTypes);
    }

    private static IParameter CreateParameter(string presentableTypeName)
    {
        return ProxyFactory.Create<IParameter>(new Dictionary<string, object?>
        {
            ["get_Type"] = CreateType(presentableTypeName)
        });
    }

    private static IType CreateType(string presentableTypeName)
    {
        return ProxyFactory.Create<IType>(new Dictionary<string, object?>
        {
            ["GetPresentableName"] = (Func<object?[], object?>)(_ => new RichText(presentableTypeName))
        });
    }

    private static class ProxyFactory
    {
        public static T Create<T>(IDictionary<string, object?> handlers)
            where T : class
        {
            return (T)Create(typeof(T), handlers);
        }

        public static object Create(Type interfaceType, IDictionary<string, object?> handlers)
        {
            var createMethod = typeof(DispatchProxy)
                .GetMethods(BindingFlags.Public | BindingFlags.Static)
                .Single(method => method.Name == nameof(DispatchProxy.Create) &&
                                  method.IsGenericMethodDefinition &&
                                  method.GetGenericArguments().Length == 2);

            var proxy = createMethod.MakeGenericMethod(interfaceType, typeof(InterfaceDispatchProxy))
                .Invoke(null, null)!;
            var dispatchProxy = (InterfaceDispatchProxy)proxy;
            dispatchProxy.Configure(interfaceType, handlers);
            return proxy;
        }
    }

    private class InterfaceDispatchProxy : DispatchProxy
    {
        private Dictionary<string, object?> _handlers = new(StringComparer.Ordinal);
        private Type _primaryInterface = typeof(object);

        public void Configure(Type primaryInterface, IDictionary<string, object?> handlers)
        {
            _primaryInterface = primaryInterface;
            _handlers = new Dictionary<string, object?>(handlers, StringComparer.Ordinal);
        }

        protected override object? Invoke(MethodInfo? targetMethod, object?[]? args)
        {
            Assert.That(targetMethod, Is.Not.Null);

            if (targetMethod!.Name == "GetType")
                return typeof(object);

            if (targetMethod.Name == "ToString")
                return $"Proxy[{_primaryInterface.Name}]";

            if (targetMethod.Name == "GetHashCode")
                return RuntimeHelpers.GetHashCode(this);

            if (targetMethod.Name == "Equals")
                return ReferenceEquals(this, args?[0]);

            if (_handlers.TryGetValue(targetMethod.Name, out var handler))
            {
                return handler is Func<object?[], object?> func
                    ? func(args ?? Array.Empty<object?>())
                    : handler;
            }

            if (targetMethod.Name == "get_IsValid")
                return true;

            return GetDefaultValue(targetMethod.ReturnType);
        }

        private static object? GetDefaultValue(Type type)
        {
            if (type == typeof(void))
                return null;

            return type.IsValueType ? Activator.CreateInstance(type) : null;
        }
    }

    private enum DeclaredElementKind
    {
        Callable,
        Property
    }

    private interface ITestCallableElement : IDeclaredElement, IParametersOwner
    {
    }

    private interface ITestPropertyElement : IProperty, IParametersOwner
    {
    }

    private interface ITestTypeMember : ITypeMember, IParametersOwner
    {
    }

}
