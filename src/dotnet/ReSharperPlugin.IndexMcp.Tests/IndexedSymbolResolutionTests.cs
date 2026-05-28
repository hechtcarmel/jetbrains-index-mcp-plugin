using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
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
    private static readonly string RepositoryRoot = FindRepositoryRoot();
    private static readonly string MutationWorkspaceRoot = Path.Combine(
        RepositoryRoot,
        "src",
        "dotnet",
        "ReSharperPlugin.IndexMcp.Tests",
        "testData",
        "CSharpProductionReadiness",
        "MutationWorkspace");

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

    [TestCase("System.String")]
    [TestCase("System.Collections.IEnumerable")]
    [TestCase("System.Collections.Generic.IEnumerable")]
    public void ResolveSingleMatch_FSharpClrPredefinedWhitelist_DisambiguatesDuplicateSourceLessMatches(string qualifiedTypeName)
    {
        var firstCandidate = CreateTypeElementCandidate(
            qualifiedTypeName.Split('.').Last(),
            qualifiedName: qualifiedTypeName,
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });
        var secondCandidate = CreateTypeElementCandidate(
            qualifiedTypeName.Split('.').Last(),
            qualifiedName: qualifiedTypeName,
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });

        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            new List<IDeclaredElement> { firstCandidate, secondCandidate },
            "ambiguous",
            "unresolved",
            "F#",
            qualifiedTypeName,
            true);

        Assert.Multiple(() =>
        {
            Assert.That(GetResolutionStatus(resolution), Is.EqualTo("success"));
            Assert.That(GetProperty<object>(resolution, "Element"), Is.SameAs(firstCandidate));
        });
    }

    [Test]
    public void ResolveSingleMatch_FSharpClrPredefinedWhitelist_PrefersExactClrNameBeforeArityVariant()
    {
        var exactCandidate = CreateTypeElementCandidate(
            "IEnumerable",
            qualifiedName: "System.Collections.Generic.IEnumerable",
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });
        var arityCandidate = CreateTypeElementCandidate(
            "IEnumerable",
            qualifiedName: "System.Collections.Generic.IEnumerable`1",
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });

        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            new List<IDeclaredElement> { arityCandidate, exactCandidate },
            "ambiguous",
            "unresolved",
            "F#",
            "System.Collections.Generic.IEnumerable",
            true);

        Assert.Multiple(() =>
        {
            Assert.That(GetResolutionStatus(resolution), Is.EqualTo("success"));
            Assert.That(GetProperty<object>(resolution, "Element"), Is.SameAs(exactCandidate));
        });
    }

    [Test]
    public void ResolveSingleMatch_FSharpClrPredefinedDisambiguation_KeepsAmbiguousForNonWhitelistSymbols()
    {
        var firstCandidate = CreateTypeElementCandidate("Lens", qualifiedName: "FSharpPlus.Lens");
        var secondCandidate = CreateTypeElementCandidate("Lens", qualifiedName: "FSharpPlus.Lens");

        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            new List<IDeclaredElement> { firstCandidate, secondCandidate },
            "ambiguous",
            "unresolved",
            "F#",
            "FSharpPlus.Lens",
            true);

        Assert.That(GetResolutionStatus(resolution), Is.EqualTo("ambiguous_match"));
    }

    [Test]
    public void ResolveSingleMatch_FSharpClrPredefinedDisambiguation_KeepsAmbiguousForMultipleSourceBackedWhitelistCandidates()
    {
        var firstCandidate = CreateTypeElementCandidate(
            "String",
            qualifiedName: "System.String",
            declarations: new List<IDeclaration> { CreateDeclarationWithSourcePath("C:/repo/src/A.fs") },
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });
        var secondCandidate = CreateTypeElementCandidate(
            "String",
            qualifiedName: "System.String",
            declarations: new List<IDeclaration> { CreateDeclarationWithSourcePath("C:/repo/src/B.fs") },
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });

        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            new List<IDeclaredElement> { firstCandidate, secondCandidate },
            "ambiguous",
            "unresolved",
            "F#",
            "System.String",
            true);

        Assert.That(GetResolutionStatus(resolution), Is.EqualTo("ambiguous_match"));
    }

    [Test]
    public void ResolveSingleMatch_FSharpClrPredefinedDisambiguation_KeepsAmbiguousForMixedSourceLessAndSourceBackedCandidates()
    {
        var sourceLessCanonicalCandidate = CreateTypeElementCandidate(
            "String",
            qualifiedName: "System.String",
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });
        var sourceBackedCandidate = CreateTypeElementCandidate(
            "String",
            qualifiedName: "System.String",
            declarations: new List<IDeclaration> { CreateDeclarationWithSourcePath("C:/repo/src/String.fs") },
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });

        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            new List<IDeclaredElement> { sourceLessCanonicalCandidate, sourceBackedCandidate },
            "ambiguous",
            "unresolved",
            "F#",
            "System.String",
            true);

        Assert.That(GetResolutionStatus(resolution), Is.EqualTo("ambiguous_match"));
    }

    [Test]
    public void ResolveSingleMatch_FSharpClrPredefinedDisambiguation_KeepsUnresolvedForMissingCandidates()
    {
        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            new List<IDeclaredElement>(),
            "ambiguous",
            "unresolved",
            "F#",
            "System.String",
            true);

        Assert.That(GetResolutionStatus(resolution), Is.EqualTo("unresolved_symbol"));
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
    public void ParseIndexedSymbol_AcceptsFSharpProductTypeOnlySymbol()
    {
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "WebApplication1.Models.Product");

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(parseResult, "IsSuccess"), Is.True);

            var parsedSymbol = GetProperty<object>(parseResult, "Symbol");
            Assert.That(GetProperty<string>(parsedSymbol, "ContainerQualifiedName"), Is.EqualTo("WebApplication1.Models.Product"));
            Assert.That(GetProperty<string?>(parsedSymbol, "MemberName"), Is.Null);
        });
    }

    [Test]
    public void ResolveSingleMatch_FSharpProductTypeSymbolPrefersTypeElementOverNamespaceFallbacks()
    {
        var namespaceCandidate = CreateNamespaceElement("WebApplication1.Models.Product");
        var typeCandidate = CreateTypeElementCandidate("Product");

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

    [TestCase("WebApplication1.Services.IProductService")]
    [TestCase("WebApplication1.Models.IProduct")]
    public void ResolveSingleMatch_FSharpImplementationTargetSymbolsResolveAsTypes(string qualifiedTypeName)
    {
        var namespaceCandidate = CreateNamespaceElement(qualifiedTypeName);
        var typeCandidate = CreateTypeElementCandidate(qualifiedTypeName.Split('.').Last());

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

    [TestCase("WebApplication1.Services.IProductService")]
    [TestCase("WebApplication1.Models.IProduct")]
    public void ResolveSingleMatch_FSharpImplementationTargetSymbolsRejectNamespaceOnlyFallbacks(string qualifiedTypeName)
    {
        var namespaceCandidate = CreateNamespaceElement(qualifiedTypeName);

        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            new List<IDeclaredElement> { namespaceCandidate },
            "ambiguous",
            "unresolved");

        Assert.That(GetResolutionStatus(resolution), Is.EqualTo("unresolved_symbol"));
    }

    [Test]
    public void ResolveSingleMatch_FSharpIProductImplementationPositionModeParityRemainsTypeBased()
    {
        var namespaceCandidate = CreateNamespaceElement("WebApplication1.Models.IProduct");
        var interfaceType = CreateTypeElementCandidate("IProduct");

        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            new List<IDeclaredElement> { namespaceCandidate, interfaceType },
            "ambiguous",
            "unresolved");

        Assert.Multiple(() =>
        {
            Assert.That(GetResolutionStatus(resolution), Is.EqualTo("success"));
            Assert.That(GetProperty<object>(resolution, "Element"), Is.SameAs(interfaceType));
        });
    }

    [Test]
    public void ParseIndexedSymbol_AcceptsFSharpConcreteServiceMemberSymbol()
    {
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "WebApplication1.Services.ProductService#GetAllProducts");

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(parseResult, "IsSuccess"), Is.True);

            var parsedSymbol = GetProperty<object>(parseResult, "Symbol");
            Assert.That(GetProperty<string>(parsedSymbol, "ContainerQualifiedName"), Is.EqualTo("WebApplication1.Services.ProductService"));
            Assert.That(GetProperty<string?>(parsedSymbol, "MemberName"), Is.EqualTo("GetAllProducts"));
            Assert.That(GetProperty<IReadOnlyList<string>?>(parsedSymbol, "ParameterTypes"), Is.Null);
        });
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
    public void ResolveMemberCandidates_ResolvesFSharpInterfaceMemberDeterministically()
    {
        var matchingMember = CreateTypeMember("GetAllProducts");
        var otherMember = CreateTypeMember("GetProduct", "string");
        var container = CreateTypeElement(matchingMember, otherMember);
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "WebApplication1.Services.IProductService#GetAllProducts");
        var parsedSymbol = GetProperty<object>(parseResult, "Symbol");

        var candidates = ((IEnumerable)InvokePrivateStatic("ResolveMemberCandidates", container, parsedSymbol))
            .Cast<object>()
            .ToList();

        Assert.That(candidates, Is.EqualTo(new[] { matchingMember }));
    }

    [Test]
    public void ResolveMemberCandidates_ResolvesFSharpConcreteServiceMemberDeterministically()
    {
        var matchingMember = CreateTypeMember("GetAllProducts");
        var otherMember = CreateTypeMember("GetProduct", "string");
        var container = CreateTypeElement(matchingMember, otherMember);
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "WebApplication1.Services.ProductService#GetAllProducts");
        var parsedSymbol = GetProperty<object>(parseResult, "Symbol");

        var candidates = ((IEnumerable)InvokePrivateStatic("ResolveMemberCandidates", container, parsedSymbol))
            .Cast<object>()
            .ToList();

        Assert.That(candidates, Is.EqualTo(new[] { matchingMember }));
    }

    [Test]
    public void ResolveSingleMatch_FSharpConcreteSuperMethodTargetRemainsMemberScoped()
    {
        var matchingMember = CreateTypeMember("GetAllProducts");
        var otherMember = CreateTypeMember("GetProduct", "string");
        var container = CreateTypeElement(matchingMember, otherMember);
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "WebApplication1.Services.ProductService#GetAllProducts");
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
    public void ResolveMemberCandidates_ResolvesFSharpModuleFunctionDeterministically()
    {
        var matchingMember = CreateTypeMember("myFunction", "System.String");
        var otherMember = CreateTypeMember("myFunction", "int");
        var container = CreateTypeElement(matchingMember, otherMember);
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "MyNamespace.MyModule#myFunction(string)");
        var parsedSymbol = GetProperty<object>(parseResult, "Symbol");

        var candidates = ((IEnumerable)InvokePrivateStatic("ResolveMemberCandidates", container, parsedSymbol))
            .Cast<object>()
            .ToList();

        Assert.That(candidates, Is.EqualTo(new[] { matchingMember }));
    }

    [Test]
    public void ResolveMemberCandidates_FSharpInterfaceMemberAmbiguityRemainsDeterministic()
    {
        var firstCandidate = CreateTypeMember("Process", "string");
        var secondCandidate = CreateTypeMember("Process", "string");
        var container = CreateTypeElement(firstCandidate, secondCandidate);
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "MyApp.IService#Process(string)");
        var parsedSymbol = GetProperty<object>(parseResult, "Symbol");

        var candidates = ((IEnumerable)InvokePrivateStatic("ResolveMemberCandidates", container, parsedSymbol))
            .Cast<IDeclaredElement>()
            .ToList();

        var resolution = InvokePrivateStatic(
            "ResolveSingleMatch",
            candidates,
            "ambiguous",
            "unresolved");

        Assert.That(GetResolutionStatus(resolution), Is.EqualTo("ambiguous_match"));
    }

    [Test]
    public void ResolveCallableTarget_AcceptsFSharpStyleStandaloneCallables()
    {
        var declaration = CreateDeclaration();
        var callable = CreateDeclaredElement(
            "Standalone",
            declarations: new List<IDeclaration> { declaration },
            handlers: new Dictionary<string, object?>
            {
                ["get_Parameters"] = new List<IParameter>(),
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });

        var callableTarget = InvokePrivateStatic("ResolveCallableTarget", callable);

        Assert.That(callableTarget, Is.Not.Null);
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

    [Test]
    public void ResolveCallableTarget_FSharpProductsControllerIndexPositionModeParityRemainsCallable()
    {
        var indexMethod = CreateTypeMember("Index", declarations: new List<IDeclaration> { CreateDeclaration() });
        var otherMethod = CreateTypeMember("Details", declarations: new List<IDeclaration> { CreateDeclaration() }, parameterTypes: "int");
        var container = CreateTypeElement(indexMethod, otherMethod);
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "WebApplication1.Controllers.ProductsController#Index");
        var parsedSymbol = GetProperty<object>(parseResult, "Symbol");

        var resolvedMember = ((IEnumerable)InvokePrivateStatic("ResolveMemberCandidates", container, parsedSymbol))
            .Cast<IDeclaredElement>()
            .Single();

        var callableTarget = InvokePrivateStatic("ResolveCallableTarget", resolvedMember);

        Assert.That(callableTarget, Is.Not.Null);
    }

    [Test]
    public void ResolveSingleMatch_FSharpExplicitInterfaceSuperMethodTargetRemainsMemberScoped()
    {
        var matchingMember = CreateTypeMember("GetAllProducts");
        var otherMember = CreateTypeMember("GetProduct", "string");
        var container = CreateTypeElement(matchingMember, otherMember);
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "WebApplication1.Services.IProductService#GetAllProducts");
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
    public void InspectSymbolRenameServiceExecutionPlan_FailsClosedWhenOnlyTextControlBoundOrUndocumentedEntrypointsExist()
    {
        var plan = InvokePrivateStatic("InspectSymbolRenameServiceExecutionPlan");
        var signatures = GetProperty<IReadOnlyList<string>>(plan, "AvailableMethodSignatures");

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(plan, "IsSupported"), Is.False,
                "The spike must fail closed until a headless service-backed rename entry point is proven in this runtime.");
            Assert.That(GetProperty<bool>(plan, "RequiresTextControl"), Is.True,
                "The discovered documented service lane still requires ITextControl, which is unavailable in the headless mutation flow.");
            Assert.That(GetProperty<string?>(plan, "SelectedMethodSignature"), Does.Contain("JetBrains.TextControl.ITextControl"));
            Assert.That(GetProperty<string?>(plan, "SelectedMethodSignature"), Does.Not.Contain("RenameFromContext("),
                "A discoverable IDataContext overload is NOT sufficient proof that backend symbol rename is safe to restore in production.");
            Assert.That(signatures, Has.Some.Contains("System.String Rename(").And.Contains("JetBrains.TextControl.ITextControl"));
            Assert.That(signatures, Has.Some.Contains("RenameAndGetConflicts").And.Contains("JetBrains.TextControl.ITextControl"));
            Assert.That(signatures, Has.Some.Contains("RenameFromDrivenContext").And.Contains("JetBrains.TextControl.ITextControl"));
            Assert.That(signatures, Has.Some.Contains("RenameFromContext(JetBrains.Application.DataContext.IDataContext context)"),
                "Keep the raw runtime surface visible in the trace even when the lane remains unsupported.");
            Assert.That(GetProperty<string>(plan, "Message"),
                Does.Contain("fail-closed")
                    .And.Contain("ITextControl")
                    .And.Contain("RenameWorkflow.Initialize")
                    .And.Contain("workflow.construct.end")
                    .And.Contain("workflow.initialize.end"));
        });
    }

    [TestCase("Renames/RenameTargets.cs", 9, 13, "local", "total")]
    [TestCase("Renames/RenameTargets.cs", 7, 33, "parameter", "increment")]
    [TestCase("Renames/RenameTargets.cs", 5, 17, "member", "_counter")]
    [TestCase("Renames/TypeRenameTarget.cs", 3, 21, "type", "TypeRenameTarget")]
    public void PlanExactSymbolRename_EmitsExactTargetMetadata(string relativePath, int line, int column, string expectedTargetKind, string expectedName)
    {
        var plannerType = BackendHostType.Assembly.GetType("ReSharperPlugin.IndexMcp.Mutations.RenameMutationPlanner");
        Assert.That(plannerType, Is.Not.Null, "Missing RenameMutationPlanner type for exact-target backend planning.");

        var method = plannerType!.GetMethod("PlanExactSymbolRename", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static);
        Assert.That(method, Is.Not.Null, "Missing RenameMutationPlanner.PlanExactSymbolRename method.");

        var plan = method!.Invoke(null, new object[]
        {
            Path.Combine(MutationWorkspaceRoot, relativePath.Replace('/', Path.DirectorySeparatorChar)),
            line,
            column
        });

        Assert.That(plan, Is.Not.Null, "Exact symbol rename planning must remain available as backend target-resolution authority.");

        var resolution = GetProperty<object>(plan!, "Resolution");
        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<string>(plan!, "OperationKind"), Is.EqualTo("symbol"));
            Assert.That(GetProperty<string>(resolution, "Status"), Is.EqualTo("resolved"));
            Assert.That(GetProperty<string?>(resolution, "TargetKind"), Is.EqualTo(expectedTargetKind));
            Assert.That(GetProperty<string?>(resolution, "ResolvedName"), Is.EqualTo(expectedName));
            Assert.That(GetProperty<string?>(resolution, "SourceTokenText"), Is.EqualTo(expectedName),
                "Backend exact-target planning must preserve the declaration token it resolved so the frontend can reject widening.");
            Assert.That(GetProperty<string?>(resolution, "Message"), Does.Contain("Resolved exact").And.Contain(expectedTargetKind));
            Assert.That(GetProperty<string?>(plan!, "OldPath"), Does.EndWith(relativePath.Replace('/', Path.DirectorySeparatorChar)));
            Assert.That(GetProperty<string?>(plan!, "NewPath"), Does.EndWith(relativePath.Replace('/', Path.DirectorySeparatorChar)));
        });
    }

    [Test]
    public void SymbolRenameLane_NoLongerInvokesManualRenameWorkflowForSymbols()
    {
        var sourcePath = Path.GetFullPath(Path.Combine(
            AppContext.BaseDirectory,
            "..", "..", "..", "..",
            "ReSharperPlugin.IndexMcp",
            "IndexMcpBackendHost.cs"));
        var source = File.ReadAllText(sourcePath);

        Assert.Multiple(() =>
        {
            Assert.That(source, Does.Contain("service-rename.inspect"),
                "The symbol rename lane should emit explicit service-inspection trace stages for smoke validation.");
            Assert.That(source, Does.Contain("service-rename.text-control.acquire"),
                "Regression guard: symbol rename should first probe for an editor-backed ITextControl before fail-closing.");
            Assert.That(source, Does.Contain("TryExecuteTextControlRename(element, newName, targetTextControl)"),
                "Regression guard: the Rider backend must attempt RenameRefactoringService through an acquired ITextControl when one exists.");
            Assert.That(source, Does.Not.Contain("var driver = ExecuteRenameWorkflow(element, dataProvider, trace);"),
                "Regression guard: symbol rename must not re-enter the manual RenameWorkflow.Initialize lane in this spike.");
        });
    }

    [Test]
    public void RenameRefactoringService_Rename_NullTriplet_FailsFast()
    {
        var method = GetPublicStaticMethod(
            typeof(JetBrains.ReSharper.Feature.Services.Refactorings.Specific.Rename.RenameRefactoringService),
            "Rename",
            3);

        var outcome = CaptureInvocationFailure(method, null, null, null);

        TestContext.Progress.WriteLine($"PROBE Rename(null,null,null): elapsedMs={outcome.ElapsedMilliseconds}, exception={outcome.Exception.GetType().FullName}: {outcome.Exception.Message}");

        Assert.That(outcome.ElapsedMilliseconds, Is.LessThan(500));
        Assert.That(outcome.Exception, Is.Not.Null);
    }

    [Test]
    public void RenameRefactoringService_RenameAndGetConflicts_NullTriplet_FailsFast()
    {
        var method = GetPublicStaticMethod(
            typeof(JetBrains.ReSharper.Feature.Services.Refactorings.Specific.Rename.RenameRefactoringService),
            "RenameAndGetConflicts",
            3);

        var outcome = CaptureInvocationFailure(method, null, null, null);

        TestContext.Progress.WriteLine($"PROBE RenameAndGetConflicts(null,null,null): elapsedMs={outcome.ElapsedMilliseconds}, exception={outcome.Exception.GetType().FullName}: {outcome.Exception.Message}");

        Assert.That(outcome.ElapsedMilliseconds, Is.LessThan(500));
        Assert.That(outcome.Exception, Is.Not.Null);
    }

    [Test]
    public void RenameRefactoringService_RenameFromContext_NullContext_FailsFast()
    {
        var method = GetPublicStaticMethod(
            typeof(JetBrains.ReSharper.Feature.Services.Refactorings.Specific.Rename.RenameRefactoringService),
            "RenameFromContext",
            1);

        var outcome = CaptureInvocationFailure(method, new object?[] { null });

        TestContext.Progress.WriteLine($"PROBE RenameFromContext(null): elapsedMs={outcome.ElapsedMilliseconds}, exception={outcome.Exception.GetType().FullName}: {outcome.Exception.Message}");

        Assert.That(outcome.ElapsedMilliseconds, Is.LessThan(500));
        Assert.That(outcome.Exception, Is.Not.Null);
    }

    [Test]
    public void RenameRefactoringService_RenameFromDrivenContext_NullQuintet_FailsFast_AndStillRequiresDriverAndTextControl()
    {
        var method = GetPublicStaticMethod(
            typeof(JetBrains.ReSharper.Feature.Services.Refactorings.Specific.Rename.RenameRefactoringService),
            "RenameFromDrivenContext",
            5);

        var outcome = CaptureInvocationFailure(method, null, null, null, null, null);
        var parameters = method.GetParameters();

        TestContext.Progress.WriteLine(
            $"PROBE RenameFromDrivenContext(null,...): elapsedMs={outcome.ElapsedMilliseconds}, exception={outcome.Exception.GetType().FullName}: {outcome.Exception.Message}, driverType={parameters[0].ParameterType.FullName}, driverIsInterface={parameters[0].ParameterType.IsInterface}, textControlType={parameters[3].ParameterType.FullName}, textControlIsInterface={parameters[3].ParameterType.IsInterface}");

        Assert.That(outcome.ElapsedMilliseconds, Is.LessThan(500));
        Assert.That(outcome.Exception, Is.Not.Null);
        Assert.That(parameters[0].ParameterType.IsInterface, Is.True);
        Assert.That(parameters[3].ParameterType.FullName, Is.EqualTo("JetBrains.TextControl.ITextControl"));
        Assert.That(parameters[3].ParameterType.IsInterface, Is.True);
    }

    [Test]
    public void DescribeFindReferencesResolvedTarget_SourceLessClrType_EmitsExplicitMetadata()
    {
        var libraryType = CreateTypeElementCandidate(
            "String",
            qualifiedName: "System.String",
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });

        var description = (string)InvokePrivateStatic("DescribeFindReferencesResolvedTarget", libraryType, "clr_predefined_fallback")!;

        Assert.That(description, Does.Contain("origin=clr_predefined_fallback"));
        Assert.That(description, Does.Contain("qualifiedName=System.String"));
        Assert.That(description, Does.Contain("elementType=ITypeElement"));
        Assert.That(description, Does.Contain("presentationLanguage="));
        Assert.That(description, Does.Contain("hasSourcePath=false"));
        Assert.That(description, Does.Contain("sourceFilePath=<absent>"));
    }

    [Test]
    public void FormatFindReferencesUnresolvedTargetMessage_UsesExplicitResolutionStatusInsteadOfZeroResults()
    {
        var resolutionType = BackendHostType.Assembly.GetType("ReSharperPlugin.IndexMcp.IndexedSymbolResolution");
        Assert.That(resolutionType, Is.Not.Null, "Missing type 'IndexedSymbolResolution'.");

        var unresolvedFactory = resolutionType!.GetMethod("Unresolved", BindingFlags.Public | BindingFlags.Static);
        Assert.That(unresolvedFactory, Is.Not.Null, "Missing factory 'IndexedSymbolResolution.Unresolved'.");

        var unresolved = unresolvedFactory!.Invoke(null, new object[]
        {
            "No Rider declaration matches container 'System.String'."
        });

        var message = (string)InvokePrivateStatic(
            "FormatFindReferencesUnresolvedTargetMessage",
            "F#",
            "System.String",
            "project_and_libraries",
            unresolved!)!;

        Assert.That(message, Does.Contain("status=unresolved_symbol"));
        Assert.That(message, Does.Contain("language=F#"));
        Assert.That(message, Does.Contain("symbol=System.String"));
        Assert.That(message, Does.Contain("scope=project_and_libraries"));
        Assert.That(message, Does.Contain("No Rider declaration matches container 'System.String'."));
    }

    [Test]
    public void DetectFSharpCapability_MissingPluginZone_ReturnsUnavailableDiagnostic()
    {
        Func<string, Type?> typeResolver = _ => null;

        var capability = InvokePrivateStatic("DetectFSharpCapability", typeResolver);

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(capability!, "IsAvailable"), Is.False);
            Assert.That(GetProperty<string>(capability!, "FailureStatus"), Is.EqualTo("unsupported_language_capability"));
            Assert.That(GetProperty<string>(capability!, "FailureMessage"), Does.Contain("F# find_references is unavailable"));
        });
    }

    [Test]
    public void DetectFSharpCapability_PluginZoneAvailable_ReturnsAvailableCapability()
    {
        Func<string, Type?> typeResolver = _ => typeof(object);

        var capability = InvokePrivateStatic("DetectFSharpCapability", typeResolver);

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(capability!, "IsAvailable"), Is.True);
            Assert.That(GetProperty<string?>(capability!, "FailureStatus"), Is.Null);
            Assert.That(GetProperty<string?>(capability!, "FailureMessage"), Is.Null);
        });
    }

    [Test]
    public void GetPresentableTypeName_FSharpPreferredLanguage_PrefersResolvedFSharpPresentation()
    {
        var declaredType = CreateType("csharp-name");
        var fsharpLanguage = new object();

        var presentableName = (string)InvokePrivateStatic(
            "GetPresentableTypeName",
            declaredType,
            "F#",
            (Func<string, object?>)(_ => fsharpLanguage),
            (Func<IType, object, string?>)((_, language) => ReferenceEquals(language, fsharpLanguage) ? "fsharp-name" : "unexpected"))!;

        Assert.That(presentableName, Is.EqualTo("fsharp-name"));
    }

    [Test]
    public void GetPresentableTypeName_FSharpPreferredLanguage_FallsBackDeterministicallyWhenUnavailable()
    {
        var declaredType = CreateType("csharp-name");
        var invokerCalled = false;

        var presentableName = (string)InvokePrivateStatic(
            "GetPresentableTypeName",
            declaredType,
            "F#",
            (Func<string, object?>)(_ => null),
            (Func<IType, object, string?>)((_, _) =>
            {
                invokerCalled = true;
                return "unexpected";
            }))!;

        Assert.Multiple(() =>
        {
            Assert.That(presentableName, Is.EqualTo("csharp-name"));
            Assert.That(invokerCalled, Is.False);
        });
    }

    [Test]
    public void ResolveRequestedPresentationLanguage_FSharpPreferredLanguage_PrefersResolvedFSharpLanguage()
    {
        var fsharpLanguage = new object();

        var presentationLanguage = InvokePrivateStatic(
            "ResolveRequestedPresentationLanguage",
            "F#",
            (Func<string, object?>)(_ => fsharpLanguage));

        Assert.That(presentationLanguage, Is.SameAs(fsharpLanguage));
    }

    [Test]
    public void ResolveRequestedPresentationLanguage_FSharpPreferredLanguage_FallsBackToCSharpWhenUnavailable()
    {
        var presentationLanguage = InvokePrivateStatic(
            "ResolveRequestedPresentationLanguage",
            "F#",
            (Func<string, object?>)(_ => null));

        Assert.That(presentationLanguage, Is.SameAs(CSharpLanguage.Instance));
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
    public void ResolveFindReferencesCapabilityFailure_FSharpPositionTargetMissingCapability_ReturnsDeterministicFailure()
    {
        Func<string, Type?> typeResolver = _ => null;

        var failure = InvokePrivateStatic(
            "ResolveFindReferencesCapabilityFailure",
            new RdSemanticTarget("src/FSharpPlus/Lens.fs", 44, 1, null, null),
            typeResolver);

        Assert.Multiple(() =>
        {
            Assert.That(failure, Is.Not.Null);
            Assert.That(GetProperty<string>(failure!, "Status"), Is.EqualTo("unsupported_language_capability"));
            Assert.That(GetProperty<string>(failure!, "Origin"), Is.EqualTo("fsharp_capability"));
            Assert.That(GetProperty<string>(failure!, "Message"), Does.Contain("F# find_references is unavailable"));
        });
    }

    [Test]
    public void ResolveFindReferencesCapabilityFailure_FSharpSymbolTargetMissingCapability_ReturnsDeterministicFailure()
    {
        Func<string, Type?> typeResolver = _ => null;

        var failure = InvokePrivateStatic(
            "ResolveFindReferencesCapabilityFailure",
            new RdSemanticTarget(null, null, null, "F#", "FSharpPlus.Lens#view"),
            typeResolver);

        Assert.Multiple(() =>
        {
            Assert.That(failure, Is.Not.Null);
            Assert.That(GetProperty<string>(failure!, "Status"), Is.EqualTo("unsupported_language_capability"));
            Assert.That(GetProperty<string>(failure!, "Origin"), Is.EqualTo("fsharp_capability"));
            Assert.That(GetProperty<string>(failure!, "Message"), Does.Contain("F# find_references is unavailable"));
        });
    }

    [Test]
    public void ResolveFindReferencesCapabilityFailure_CSharpPositionTarget_IgnoresMissingFSharpCapability()
    {
        Func<string, Type?> typeResolver = _ => null;

        var failure = InvokePrivateStatic(
            "ResolveFindReferencesCapabilityFailure",
            new RdSemanticTarget("src/Demo/Service.cs", 10, 5, null, null),
            typeResolver);

        Assert.That(failure, Is.Null);
    }

    [Test]
    public void ShouldRaiseFindReferencesTargetResolutionFailure_FSharpCapabilityFailureOnPositionTarget_IsTrue()
    {
        var shouldRaise = (bool)InvokePrivateStatic(
            "ShouldRaiseFindReferencesTargetResolutionFailure",
            new RdSemanticTarget("src/FSharpPlus/Lens.fs", 44, 1, null, null),
            "unsupported_language_capability")!;

        Assert.That(shouldRaise, Is.True);
    }

    [TestCase("unsupported_target")]
    [TestCase("ambiguous_match")]
    [TestCase("unresolved_target")]
    public void ShouldRaiseFindReferencesTargetResolutionFailure_FSharpPositionSafetyFailure_IsTrue(string status)
    {
        var shouldRaise = (bool)InvokePrivateStatic(
            "ShouldRaiseFindReferencesTargetResolutionFailure",
            new RdSemanticTarget("src/FSharpPlus/Lens.fs", 44, 1, null, null),
            status)!;

        Assert.That(shouldRaise, Is.True);
    }

    [Test]
    public void FormatFindReferencesTargetResolutionFailureMessage_PositionTarget_UsesExplicitPositionContext()
    {
        var message = (string)InvokePrivateStatic(
            "FormatFindReferencesTargetResolutionFailureMessage",
            new RdSemanticTarget("src/FSharpPlus/Lens.fs", 44, 1, null, null),
            "project_files",
            "unsupported_language_capability",
            "F# find_references is unavailable because the Rider F# plugin APIs are not loaded.",
            "fsharp_capability")!;

        Assert.That(message, Does.Contain("status=unsupported_language_capability"));
        Assert.That(message, Does.Contain("target=src/FSharpPlus/Lens.fs:44:1"));
        Assert.That(message, Does.Contain("origin=fsharp_capability"));
    }

    [Test]
    public void FormatBoundedFindReferencesSkipMessage_IncludesSearchRouteAndTargetContext()
    {
        var libraryType = CreateTypeElementCandidate(
            "String",
            qualifiedName: "System.String",
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });

        var message = (string)InvokePrivateStatic(
            "FormatBoundedFindReferencesSkipMessage",
            "bounded-file",
            libraryType,
            "cancellation",
            1,
            3,
            "src/A.fs",
            "System.TimeoutException",
            "usage cache cold",
            1200L,
            400L,
            0)!;

        Assert.That(message, Does.Contain("searchRoute=bounded-file"));
        Assert.That(message, Does.Contain("qualifiedName=System.String"));
        Assert.That(message, Does.Contain("presentationLanguage="));
        Assert.That(message, Does.Contain("category=cancellation"));
        Assert.That(message, Does.Contain("descriptor=src/A.fs"));
        Assert.That(message, Does.Contain("collectedCount=0"));
    }

    [Test]
    public void BuildCallHierarchyResolutionPlan_FSharpPositionTarget_UsesDeclarationOnlyFastPath()
    {
        var plan = InvokePrivateStatic(
            "BuildCallHierarchyResolutionPlan",
            new RdSemanticTarget("src/FSharpPlus/Lens.fs", 44, 1, null, null));

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(plan!, "UseDeclarationOnlyFastPath"), Is.True);
            Assert.That(GetProperty<bool>(plan!, "IsFSharpPositionTarget"), Is.True);
        });
    }

    [Test]
    public void BuildCallHierarchyResolutionPlan_CSharpPositionTarget_PreservesGeneralResolutionPath()
    {
        var plan = InvokePrivateStatic(
            "BuildCallHierarchyResolutionPlan",
            new RdSemanticTarget("src/Demo/Service.cs", 10, 5, null, null));

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(plan!, "UseDeclarationOnlyFastPath"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "IsFSharpPositionTarget"), Is.False);
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
        var groupByIndex = source.IndexOf(".GroupBy(reference =>", StringComparison.Ordinal);
        var takeIndex = source.IndexOf(".Take(effectiveLimit)", StringComparison.Ordinal);
        var orderByIndex = groupByIndex >= 0
            ? source.IndexOf(".OrderBy(", groupByIndex, StringComparison.Ordinal)
            : -1;

        Assert.Multiple(() =>
        {
            Assert.That(source, Does.Contain("GroupBy(reference =>")
                .And.Contain("Take(effectiveLimit)"),
                "Regression guard: Rider reference results still need an explicit distinct phase before limiting.");
            Assert.That(orderByIndex >= 0 && orderByIndex < takeIndex,
                Is.True,
                "Reference rows should be ordered deterministically after deduplication and before truncation; backend enumeration order is not a stable API contract.");
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
    public void BuildCallHierarchyResolutionPlan_FSharpSymbolTarget_DoesNotUseDeclarationOnlyFastPath()
    {
        var plan = InvokePrivateStatic(
            "BuildCallHierarchyResolutionPlan",
            new RdSemanticTarget(null, null, null, "F#", "Demo.Module#run"));

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(plan!, "UseDeclarationOnlyFastPath"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "IsFSharpPositionTarget"), Is.False);
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
            Assert.That(source, Does.Contain("ResolveCallHierarchyTarget(request.Target, resolutionPlan)"),
                "Call hierarchy should resolve semantic Rider symbol targets through a backend-native target resolver instead of relying on frontend source-position rewriting.");
            Assert.That(source, Does.Not.Contain("ResolveCallableTarget(ResolveTarget(request.Target))"),
                "Regression guard: general call hierarchy resolution should no longer inline the old declaration-only path for semantic symbol targets.");
        });
    }

    [Test]
    public void ResolveNearestCallableDeclarationNode_FSharpMemberInsideType_PrefersCallableDeclarationOverContainingType()
    {
        var callableElement = CreateDeclaredElement("Run", declarations: new List<IDeclaration>());
        var typeElement = CreateTypeElementCandidate("Container");
        var typeDeclaration = CreateTypeDeclaration(typeElement);
        var callableDeclaration = CreateDeclaration(callableElement, typeDeclaration);
        var nestedNode = CreateTreeNode(callableDeclaration);

        var resolvedNode = InvokePrivateStatic("ResolveNearestCallableDeclarationNode", nestedNode);

        Assert.That(resolvedNode, Is.SameAs(callableDeclaration));
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
    public void ResolveNearestCallableDeclarationNode_FSharpNonCallablePosition_ReturnsNull()
    {
        var typeElement = CreateTypeElementCandidate("Container");
        var typeDeclaration = CreateTypeDeclaration(typeElement);
        var nestedNode = CreateTreeNode(typeDeclaration);

        var resolvedNode = InvokePrivateStatic("ResolveNearestCallableDeclarationNode", nestedNode);

        Assert.That(resolvedNode, Is.Null);
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
    public void BuildFindTypesSearchPlan_FSharpProjectFiles_UsesOnlyFSharpFilesAndSkipsExpensiveFallback()
    {
        var plan = InvokePrivateStatic("BuildFindTypesSearchPlan", "F#", "project_files", "exact", "FSharpPlus.Lens.Lens");

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(plan!, "UseProjectDeclaredTypeScan"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "UseExactQualifiedProjectLookup"), Is.True);
            Assert.That(GetProperty<bool>(plan!, "UseIndexedTypeFallback"), Is.False);
            Assert.That(GetProperty<IReadOnlyList<string>>(plan!, "AllowedProjectFileExtensions"),
                Is.EqualTo(new[] { ".fs", ".fsi", ".fsx" }));
        });
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
    public void BuildFindTypesSearchPlan_FSharpProjectAndLibraries_KeepsLibraryFallback()
    {
        var plan = InvokePrivateStatic("BuildFindTypesSearchPlan", "F#", "project_and_libraries", "exact", "FSharpPlus.Lens.Lens");

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(plan!, "UseProjectDeclaredTypeScan"), Is.True);
            Assert.That(GetProperty<bool>(plan!, "UseExactQualifiedProjectLookup"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "UseIndexedTypeFallback"), Is.True);
            Assert.That(GetProperty<IReadOnlyList<string>?>(plan!, "AllowedProjectFileExtensions"), Is.Null);
        });
    }

    [Test]
    public void BuildFindReferencesResolutionPlan_FSharpProjectFilesTypeOnlyQualified_UsesBoundedProjectSearchWithoutRejecting()
    {
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "FSharpPlus.Lens");
        var parsedSymbol = GetProperty<object>(parseResult!, "Symbol");

        var plan = InvokePrivateStatic("BuildFindReferencesResolutionPlan", "project_files", parsedSymbol);

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<string>(plan!, "TargetKind"), Is.EqualTo("symbol"));
            Assert.That(GetProperty<bool>(plan!, "IsFSharpPositionTarget"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "IsFSharpSymbolTarget"), Is.True);
            Assert.That(GetProperty<bool>(plan!, "UseProjectQualifiedTypeLookup"), Is.True);
            Assert.That(GetProperty<bool>(plan!, "UseBoundedProjectFileSearch"), Is.True);
            Assert.That(GetProperty<bool>(plan!, "AllowLibraryFallback"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "RejectUnboundedReferenceSearch"), Is.False);
            Assert.That(GetProperty<IReadOnlyList<string>?>(plan!, "AllowedProjectFileExtensions"), Is.EqualTo(new[] { ".fs", ".fsi", ".fsx" }));
        });
    }

    [Test]
    public void BuildFindReferencesResolutionPlan_FSharpPositionTarget_ProjectFiles_UsesDedicatedPlanningMetadata()
    {
        var target = new RdSemanticTarget("src/FSharpPlus/Lens.fs", 44, 1, null, null);

        var plan = InvokePrivateStatic("BuildFindReferencesResolutionPlan", target, "project_files", null);

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<string>(plan!, "TargetKind"), Is.EqualTo("position"));
            Assert.That(GetProperty<bool>(plan!, "IsFSharpPositionTarget"), Is.True);
            Assert.That(GetProperty<bool>(plan!, "IsFSharpSymbolTarget"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "UseBoundedProjectFileSearch"), Is.True);
            Assert.That(GetProperty<bool>(plan!, "UseProjectQualifiedTypeLookup"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "AllowLibraryFallback"), Is.False);
            Assert.That(GetProperty<IReadOnlyList<string>?>(plan!, "AllowedProjectFileExtensions"), Is.EqualTo(new[] { ".fs", ".fsi", ".fsx" }));
        });
    }

    [Test]
    public void ClassifyFSharpPositionTargetResolution_SingleSafeCandidate_ReturnsSuccess()
    {
        var target = new RdSemanticTarget("src/FSharpPlus/Lens.fs", 44, 1, null, null);
        var safeCandidate = CreateTypeMember("view");

        var resolution = InvokePrivateStatic(
            "ClassifyFSharpPositionTargetResolution",
            target,
            new List<IDeclaredElement> { safeCandidate },
            safeCandidate);

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<string>(resolution!, "Status"), Is.EqualTo("success"));
            Assert.That(GetProperty<string>(resolution!, "Origin"), Is.EqualTo("fsharp_position_safe"));
            Assert.That(GetProperty<IDeclaredElement>(resolution!, "Element"), Is.SameAs(safeCandidate));
        });
    }

    [Test]
    public void ClassifyFSharpPositionTargetResolution_MultipleSafeCandidates_ReturnsAmbiguous()
    {
        var target = new RdSemanticTarget("src/FSharpPlus/Lens.fs", 44, 1, null, null);
        var firstCandidate = CreateTypeMember("view");
        var secondCandidate = CreateTypeElementCandidate("Lens", qualifiedName: "FSharpPlus.Lens");

        var resolution = InvokePrivateStatic(
            "ClassifyFSharpPositionTargetResolution",
            target,
            new List<IDeclaredElement> { firstCandidate, secondCandidate },
            firstCandidate);

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<string>(resolution!, "Status"), Is.EqualTo("ambiguous_match"));
            Assert.That(GetProperty<string>(resolution!, "Origin"), Is.EqualTo("fsharp_position_safe"));
            Assert.That(GetProperty<string>(resolution!, "Message"), Does.Contain("multiple safe semantic targets"));
        });
    }

    [Test]
    public void ClassifyFSharpPositionTargetResolution_FallbackOnly_ReturnsUnsupported()
    {
        var target = new RdSemanticTarget("src/FSharpPlus/Lens.fs", 44, 1, null, null);
        var fallbackCandidate = CreateTypeElementCandidate("Lens", qualifiedName: "FSharpPlus.Lens");

        var resolution = InvokePrivateStatic(
            "ClassifyFSharpPositionTargetResolution",
            target,
            new List<IDeclaredElement>(),
            fallbackCandidate);

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<string>(resolution!, "Status"), Is.EqualTo("unsupported_target"));
            Assert.That(GetProperty<string>(resolution!, "Origin"), Is.EqualTo("fsharp_position_safe"));
            Assert.That(GetProperty<string>(resolution!, "Message"), Does.Contain("unsafe fallback"));
        });
    }

    [Test]
    public void ClassifyFSharpPositionTargetResolution_NoCandidates_ReturnsUnresolved()
    {
        var target = new RdSemanticTarget("src/FSharpPlus/Lens.fs", 44, 1, null, null);

        var resolution = InvokePrivateStatic(
            "ClassifyFSharpPositionTargetResolution",
            target,
            new List<IDeclaredElement>(),
            null);

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<string>(resolution!, "Status"), Is.EqualTo("unresolved_target"));
            Assert.That(GetProperty<string>(resolution!, "Origin"), Is.EqualTo("fsharp_position_safe"));
            Assert.That(GetProperty<string>(resolution!, "Message"), Does.Contain("No safe F# semantic target"));
        });
    }

    [Test]
    public void EnsureFindReferencesSearchIsSupported_FSharpProjectFilesTypeOnlyQualified_DoesNotFastFail()
    {
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "FSharpPlus.Lens");
        var parsedSymbol = GetProperty<object>(parseResult!, "Symbol");
        var plan = InvokePrivateStatic("BuildFindReferencesResolutionPlan", "project_files", parsedSymbol);

        Assert.DoesNotThrow(() =>
            InvokePrivateStatic("EnsureFindReferencesSearchIsSupported", plan!, parsedSymbol, "project_files"));
    }

    [Test]
    public void BatchSourceFilePathsForBoundedFindReferences_PerFileMode_DeduplicatesAndOrdersDeterministically()
    {
        var batches = (IEnumerable<IEnumerable<string>>)InvokePrivateStatic(
            "BatchSourceFilePathsForBoundedFindReferences",
            new[] { "b.fs", "A.fs", "a.fs", "c.fsx" },
            1)!;

        Assert.That(
            batches.Select(batch => batch.ToArray()).ToArray(),
            Is.EqualTo(new[]
            {
                new[] { "A.fs" },
                new[] { "b.fs" },
                new[] { "c.fsx" }
            }));
    }

    [Test]
    public void BatchSourceFilePathsForBoundedFindReferences_SmallBatchMode_GroupsOrderedPaths()
    {
        var batches = (IEnumerable<IEnumerable<string>>)InvokePrivateStatic(
            "BatchSourceFilePathsForBoundedFindReferences",
            new[] { "d.fsi", "b.fs", "c.fsx", "A.fs" },
            2)!;

        Assert.That(
            batches.Select(batch => batch.ToArray()).ToArray(),
            Is.EqualTo(new[]
            {
                new[] { "A.fs", "b.fs" },
                new[] { "c.fsx", "d.fsi" }
            }));
    }

    [Test]
    public void ClassifyBoundedFindReferencesException_DirectOperationCanceled_IsCancellation()
    {
        var classified = InvokePrivateStatic(
            "ClassifyBoundedFindReferencesException",
            new OperationCanceledException("cancelled"));

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<string>(classified!, "Category"), Is.EqualTo("cancellation"));

            var exception = GetProperty<Exception>(classified!, "Exception");
            Assert.That(exception, Is.TypeOf<OperationCanceledException>());
            Assert.That(exception.Message, Is.EqualTo("cancelled"));
        });
    }

    [Test]
    public void ClassifyBoundedFindReferencesException_AggregateWrappedCancellation_IsCancellation()
    {
        var classified = InvokePrivateStatic(
            "ClassifyBoundedFindReferencesException",
            new AggregateException(new OperationCanceledException("wrapped cancellation")));

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<string>(classified!, "Category"), Is.EqualTo("cancellation"));

            var exception = GetProperty<Exception>(classified!, "Exception");
            Assert.That(exception, Is.TypeOf<OperationCanceledException>());
            Assert.That(exception.Message, Is.EqualTo("wrapped cancellation"));
        });
    }

    [Test]
    public void FindReferences_DeadlineProgressIndicator_CancelsExpiredSearch()
    {
        var indicator = CreateDeadlineProgressIndicator(DateTime.UtcNow.AddSeconds(-1));
        var throwIfExpired = indicator.GetType().GetMethod("ThrowIfExpired", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
        Assert.That(throwIfExpired, Is.Not.Null, "Missing method 'ThrowIfExpired'.");

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(indicator, "IsCanceled"), Is.True);
            Assert.That(
                () => throwIfExpired!.Invoke(indicator, Array.Empty<object?>()),
                Throws.TypeOf<TargetInvocationException>()
                    .With.InnerException.TypeOf<TimeoutException>()
                    .With.InnerException.Message.Contains("Rider F# find_references requires warmed ReSharper usage caches"));
        });
    }

    [Test]
    public void FindReferencesBounded_ExpiredDeadline_RethrowsTimeoutInsteadOfContinuing()
    {
        var indicator = CreateDeadlineProgressIndicator(DateTime.UtcNow.AddSeconds(-1));

        var exception = Assert.Throws<TargetInvocationException>(() =>
            InvokePrivateStatic(
                "RethrowIfBoundedFindReferencesDeadlineExpired",
                new OperationCanceledException("search cancelled"),
                indicator));

        Assert.That(exception!.InnerException, Is.TypeOf<TimeoutException>());
        Assert.That(exception.InnerException!.Message, Does.Contain("module/type-only project_files searches"));
    }

    [Test]
    public void RunBoundedFindReferencesStage_ExpiredDeadline_RethrowsTimeoutOnCancellation()
    {
        var indicator = CreateDeadlineProgressIndicator(DateTime.UtcNow.AddSeconds(-1));
        var method = BackendHostType.GetMethods(BindingFlags.NonPublic | BindingFlags.Static)
            .Single(candidate => candidate.Name == "RunBoundedFindReferencesStage" &&
                                 candidate.GetParameters().Length == 2 &&
                                 candidate.GetParameters()[1].ParameterType == typeof(Action));

        var exception = Assert.Throws<TargetInvocationException>(() =>
            method.Invoke(null, new object?[]
            {
                indicator,
                (Action)(() => throw new OperationCanceledException("search cancelled"))
            }));

        Assert.That(exception!.InnerException, Is.TypeOf<TimeoutException>());
        Assert.That(exception.InnerException!.Message, Does.Contain("module/type-only project_files searches"));
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

    [Test]
    public void FormatSkippedFindReferencesDescriptors_TruncatesDeterministically()
    {
        var summary = (string)InvokePrivateStatic(
            "FormatSkippedFindReferencesDescriptors",
            new[]
            {
                "docsrc/content/lens.fsx",
                "src/other.fs",
                "src/third.fsx",
                "src/fourth.fs"
            },
            3)!;

        Assert.That(
            summary,
            Is.EqualTo("docsrc/content/lens.fsx, src/other.fs, src/third.fsx (+1 more)"));
    }

    [Test]
    public void BuildFindReferencesResolutionPlan_FSharpProjectAndLibraries_KeepsLegacyFallback()
    {
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "FSharpPlus.Lens");
        var parsedSymbol = GetProperty<object>(parseResult!, "Symbol");

        var plan = InvokePrivateStatic("BuildFindReferencesResolutionPlan", "project_and_libraries", parsedSymbol);

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(plan!, "UseProjectQualifiedTypeLookup"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "UseBoundedProjectFileSearch"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "AllowLibraryFallback"), Is.True);
            Assert.That(GetProperty<bool>(plan!, "RejectUnboundedReferenceSearch"), Is.False);
            Assert.That(GetProperty<IReadOnlyList<string>?>(plan!, "AllowedProjectFileExtensions"), Is.Null);
        });
    }

    [Test]
    public void BuildFindReferencesResolutionPlan_CSharpProjectFiles_PreservesLegacyBehavior()
    {
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "C#", "Demo.Service");
        var parsedSymbol = GetProperty<object>(parseResult!, "Symbol");

        var plan = InvokePrivateStatic("BuildFindReferencesResolutionPlan", "project_files", parsedSymbol);

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<string>(plan!, "TargetKind"), Is.EqualTo("symbol"));
            Assert.That(GetProperty<bool>(plan!, "IsFSharpPositionTarget"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "IsFSharpSymbolTarget"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "UseProjectQualifiedTypeLookup"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "UseBoundedProjectFileSearch"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "AllowLibraryFallback"), Is.True);
            Assert.That(GetProperty<bool>(plan!, "RejectUnboundedReferenceSearch"), Is.False);
            Assert.That(GetProperty<IReadOnlyList<string>?>(plan!, "AllowedProjectFileExtensions"), Is.Null);
        });
    }

    [Test]
    public void BuildFindReferencesResolutionPlan_CSharpPositionTarget_PreservesLegacyBaselineRoute()
    {
        var target = new RdSemanticTarget("src/Demo/Service.cs", 10, 5, null, null);

        var plan = InvokePrivateStatic("BuildFindReferencesResolutionPlan", target, "project_files", null);

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<string>(plan!, "TargetKind"), Is.EqualTo("position"));
            Assert.That(GetProperty<bool>(plan!, "IsFSharpPositionTarget"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "IsFSharpSymbolTarget"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "UseProjectQualifiedTypeLookup"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "UseBoundedProjectFileSearch"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "AllowLibraryFallback"), Is.True);
            Assert.That(GetProperty<IReadOnlyList<string>?>(plan!, "AllowedProjectFileExtensions"), Is.Null);
        });
    }

    [Test]
    public void BuildFindReferencesResolutionPlan_FSharpProjectFilesMemberSymbol_DoesNotUseBoundedTypeOnlyPath()
    {
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "FSharpPlus.Lens#map");
        var parsedSymbol = GetProperty<object>(parseResult!, "Symbol");

        var plan = InvokePrivateStatic("BuildFindReferencesResolutionPlan", "project_files", parsedSymbol);

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<bool>(plan!, "UseBoundedProjectFileSearch"), Is.False);
            Assert.That(GetProperty<bool>(plan!, "RejectUnboundedReferenceSearch"), Is.False);
            Assert.That(GetProperty<IReadOnlyList<string>?>(plan!, "AllowedProjectFileExtensions"), Is.Null);
        });
    }

    [Test]
    public void EnsureFindReferencesIndexedTargetIsSupported_FSharpProjectFilesTypeOnlyQualified_DoesNotFastFail()
    {
        Assert.DoesNotThrow(() =>
            InvokePrivateStatic("EnsureFindReferencesIndexedTargetIsSupported", "F#", "FSharpPlus.Lens", "project_files"));
    }

    [Test]
    public void EnsureFindReferencesSearchIsSupported_CSharpProjectFilesTypeOnly_DoesNotFastFail()
    {
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "C#", "Demo.Service");
        var parsedSymbol = GetProperty<object>(parseResult!, "Symbol");
        var plan = InvokePrivateStatic("BuildFindReferencesResolutionPlan", "project_files", parsedSymbol);

        Assert.DoesNotThrow(() =>
            InvokePrivateStatic("EnsureFindReferencesSearchIsSupported", plan!, parsedSymbol, "project_files"));
    }

    [Test]
    public void EnsureFindReferencesSearchIsSupported_FSharpProjectFilesMemberSymbol_DoesNotFastFail()
    {
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "FSharpPlus.Lens#map");
        var parsedSymbol = GetProperty<object>(parseResult!, "Symbol");
        var plan = InvokePrivateStatic("BuildFindReferencesResolutionPlan", "project_files", parsedSymbol);

        Assert.DoesNotThrow(() =>
            InvokePrivateStatic("EnsureFindReferencesSearchIsSupported", plan!, parsedSymbol, "project_files"));
    }

    [Test]
    public void EnsureFindReferencesSearchIsSupported_FSharpProjectAndLibrariesTypeOnly_DoesNotFastFail()
    {
        var parseResult = InvokePrivateStatic("ParseIndexedSymbol", "F#", "FSharpPlus.Lens");
        var parsedSymbol = GetProperty<object>(parseResult!, "Symbol");
        var plan = InvokePrivateStatic("BuildFindReferencesResolutionPlan", "project_and_libraries", parsedSymbol);

        Assert.DoesNotThrow(() =>
            InvokePrivateStatic("EnsureFindReferencesSearchIsSupported", plan!, parsedSymbol, "project_and_libraries"));
    }

    [TestCase("invalid_symbol", "Rider symbol cannot be empty.")]
    [TestCase("unresolved_symbol", "No Rider declaration matches symbol 'FSharpPlus.Lens#view'.")]
    [TestCase("ambiguous_match", "Multiple Rider declarations match symbol 'FSharpPlus.Lens#view'.")]
    [TestCase("unsupported_target", "Container 'FSharpPlus.Lens' does not support member lookup.")]
    public void DecorateFSharpSymbolModeResolution_LimitationStatusesRecommendPositionLookup(string status, string baseMessage)
    {
        var decorated = InvokePrivateStatic(
            "DecorateFSharpSymbolModeResolution",
            CreateIndexedSymbolResolution(status, baseMessage),
            "F#",
            "FSharpPlus.Lens#view");

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<string>(decorated!, "Status"), Is.EqualTo(status));
            Assert.That(GetProperty<string>(decorated!, "Message"), Does.Contain(baseMessage));
            Assert.That(GetProperty<string>(decorated!, "Message"), Does.Contain("best-effort"));
            Assert.That(GetProperty<string>(decorated!, "Message"), Does.Contain("file + line + column"));
        });
    }

    [Test]
    public void DecorateFSharpSymbolModeResolution_SuccessLeavesResolutionUntouched()
    {
        var resolvedElement = CreateTypeMember("view");
        var success = CreateIndexedSymbolResolution("success", null, resolvedElement);

        var decorated = InvokePrivateStatic(
            "DecorateFSharpSymbolModeResolution",
            success,
            "F#",
            "FSharpPlus.Lens#view");

        Assert.Multiple(() =>
        {
            Assert.That(GetProperty<string>(decorated!, "Status"), Is.EqualTo("success"));
            Assert.That(GetProperty<string?>(decorated!, "Message"), Is.Null);
            Assert.That(GetProperty<object>(decorated!, "Element"), Is.SameAs(resolvedElement));
        });
    }

    [Test]
    public void EnumerateQualifiedNameCandidates_FSharpTypeOnlySymbol_IncludesClrNestedVariant()
    {
        var candidates = ((IEnumerable)InvokePrivateStatic("EnumerateQualifiedNameCandidates", "FSharpPlus.Lens")!)
            .Cast<string>()
            .ToList();

        Assert.That(candidates, Is.EqualTo(new[]
        {
            "FSharpPlus.Lens",
            "FSharpPlus+Lens"
        }));
    }

    [Test]
    public void EnumerateQualifiedNameCandidates_FSharpTypeName_IncludesClrNestedVariant()
    {
        var candidates = ((IEnumerable)InvokePrivateStatic("EnumerateQualifiedNameCandidates", "FSharpPlus.Lens.Lens")!)
            .Cast<string>()
            .ToList();

        Assert.That(candidates, Is.EqualTo(new[]
        {
            "FSharpPlus.Lens.Lens",
            "FSharpPlus.Lens+Lens"
        }));
    }

    [Test]
    public void EnumerateQualifiedTypeCandidates_UsesAllQualifiedNameVariantsAndDeduplicates()
    {
        var clrNestedType = CreateTypeElementCandidate("Lens");
        var sourceNamedType = CreateTypeElementCandidate("Lens");
        var resolved = ((IEnumerable)InvokePrivateStatic(
                "EnumerateQualifiedTypeCandidates",
                "FSharpPlus.Lens.Lens",
                (Func<string, IEnumerable<ITypeElement>>)(candidate => candidate switch
                {
                    "FSharpPlus.Lens.Lens" => new[] { sourceNamedType, clrNestedType },
                    "FSharpPlus.Lens+Lens" => new[] { clrNestedType },
                    _ => Array.Empty<ITypeElement>()
                }))!)
            .Cast<ITypeElement>()
            .ToList();

        Assert.That(resolved, Is.EqualTo(new[] { sourceNamedType, clrNestedType }));
    }

    [TestCase("FSharpPlus.Lens+Lens", "FSharpPlus.Lens.Lens", true)]
    [TestCase("FSharpPlus.Lens.Lens", "FSharpPlus.Lens.Lens", true)]
    [TestCase("FSharpPlus.Lens.Other", "FSharpPlus.Lens.Lens", false)]
    public void MatchesQualifiedName_NormalizesSafeFSharpClrVariants(string declaredName, string query, bool expected)
    {
        var matches = (bool)InvokePrivateStatic("MatchesQualifiedName", declaredName, query, "exact")!;

        Assert.That(matches, Is.EqualTo(expected));
    }

    [TestCase("System.String")]
    [TestCase("System.Collections.IEnumerable")]
    [TestCase("System.Collections.Generic.IEnumerable")]
    public void MatchesLanguage_FSharp_AllowsSourceLessClrLibraryTypes(string qualifiedTypeName)
    {
        var libraryType = CreateTypeElementCandidate(
            qualifiedTypeName.Split('.').Last(),
            qualifiedTypeName,
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });

        var matches = (bool)InvokePrivateStatic("MatchesLanguage", libraryType, "F#")!;

        Assert.That(matches, Is.True);
    }

    [Test]
    public void MatchesLanguageForSourcePath_FSharp_RejectsSourceBackedCSharpFiles()
    {
        var matches = (bool)InvokePrivateStatic("MatchesLanguageForSourcePath", "C:/repo/src/Demo/Service.cs", "F#")!;

        Assert.That(matches, Is.False);
    }

    [TestCase("System.String")]
    [TestCase("System.Collections.IEnumerable")]
    [TestCase("System.Collections.Generic.IEnumerable")]
    public void ResolveProjectQualifiedTypeCandidatesCore_FSharpClrTypeFallback_UsesFallbackWhenProjectLookupMisses(string qualifiedTypeName)
    {
        var fallbackType = CreateTypeElementCandidate(
            qualifiedTypeName.Split('.').Last(),
            qualifiedName: qualifiedTypeName,
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });

        var resolved = ((IEnumerable)InvokePrivateStatic(
                "ResolveProjectQualifiedTypeCandidatesCore",
                "F#",
                qualifiedTypeName,
                (Func<IEnumerable<ITypeElement>>)(() => Array.Empty<ITypeElement>()),
                (Func<IEnumerable<IDeclaredElement>>)(() => new[] { fallbackType }))!)
            .Cast<IDeclaredElement>()
            .ToList();

        Assert.That(resolved, Is.EqualTo(new[] { fallbackType }));
    }

    [TestCase("System.String")]
    [TestCase("System.Collections.IEnumerable")]
    [TestCase("System.Collections.Generic.IEnumerable")]
    public void ResolveContainerCandidatesCore_FSharpClrTypeFallback_UsesFallbackWhenPrimaryLookupMisses(string qualifiedTypeName)
    {
        var fallbackType = CreateTypeElementCandidate(
            qualifiedTypeName.Split('.').Last(),
            qualifiedName: qualifiedTypeName,
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });

        var resolved = ((IEnumerable)InvokePrivateStatic(
                "ResolveContainerCandidatesCore",
                "F#",
                qualifiedTypeName,
                (Func<IEnumerable<IDeclaredElement>>)(() => Array.Empty<IDeclaredElement>()),
                (Func<IEnumerable<IDeclaredElement>>)(() => new[] { fallbackType }))!)
            .Cast<IDeclaredElement>()
            .ToList();

        Assert.That(resolved, Is.EqualTo(new[] { fallbackType }));
    }

    [Test]
    public void ResolveContainerCandidatesCore_CSharpPrimaryMatch_DoesNotInvokeClrFallback()
    {
        var primaryType = CreateTypeElementCandidate("String", qualifiedName: "System.String");
        var fallbackInvoked = false;

        var resolved = ((IEnumerable)InvokePrivateStatic(
                "ResolveContainerCandidatesCore",
                "C#",
                "System.String",
                (Func<IEnumerable<IDeclaredElement>>)(() => new[] { primaryType }),
                (Func<IEnumerable<IDeclaredElement>>)(() =>
                {
                    fallbackInvoked = true;
                    return Array.Empty<IDeclaredElement>();
                }))!)
            .Cast<IDeclaredElement>()
            .ToList();

        Assert.Multiple(() =>
        {
            Assert.That(resolved, Is.EqualTo(new[] { primaryType }));
            Assert.That(fallbackInvoked, Is.False);
        });
    }

    [Test]
    public void EnumerateClrPredefinedLookupNames_GenericIEnumerable_AddsArityVariant()
    {
        var candidates = ((IEnumerable)InvokePrivateStatic(
                "EnumerateClrPredefinedLookupNames",
                "System.Collections.Generic.IEnumerable")!)
            .Cast<string>()
            .ToList();

        Assert.That(candidates, Is.EqualTo(new[]
        {
            "System.Collections.Generic.IEnumerable",
            "System.Collections.Generic.IEnumerable`1"
        }));
    }

    [TestCase("System.String")]
    [TestCase("System.Collections.IEnumerable")]
    [TestCase("System.Collections.Generic.IEnumerable")]
    public void ResolveContainerCandidatesFromScope_FSharp_PreservesSourceLessClrMatches(string qualifiedTypeName)
    {
        var backendHost = CreateUninitializedBackendHost();
        var libraryType = CreateTypeElementCandidate(
            qualifiedTypeName.Split('.').Last(),
            qualifiedTypeName,
            handlers: new Dictionary<string, object?>
            {
                ["get_PresentationLanguage"] = CSharpLanguage.Instance
            });
        var symbolScope = CreateSymbolScope(qualifiedTypeName, libraryType);

        var resolved = ((IEnumerable)InvokePrivateInstance(
                backendHost,
                "ResolveContainerCandidatesFromScope",
                symbolScope,
                qualifiedTypeName,
                "F#")!)
            .Cast<IDeclaredElement>()
            .ToList();

        Assert.That(resolved, Is.EqualTo(new[] { libraryType }));
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

    private static MethodInfo GetPublicStaticMethod(Type declaringType, string methodName, int parameterCount)
    {
        var methods = declaringType.GetMethods(BindingFlags.Public | BindingFlags.Static)
            .Where(method => method.Name == methodName)
            .ToList();
        Assert.That(methods, Is.Not.Empty, $"Missing public static method '{methodName}' on {declaringType.FullName}.");

        var method = methods.Single(candidate => candidate.GetParameters().Length == parameterCount);
        return method;
    }

    private static (Exception Exception, long ElapsedMilliseconds) CaptureInvocationFailure(MethodInfo method, params object?[] args)
    {
        var stopwatch = Stopwatch.StartNew();
        var exception = Assert.Throws<TargetInvocationException>(() => method.Invoke(null, args));
        stopwatch.Stop();

        return (exception!.InnerException ?? exception, stopwatch.ElapsedMilliseconds);
    }

    private static object CreateUninitializedBackendHost()
    {
        return FormatterServices.GetUninitializedObject(BackendHostType);
    }

    private static object CreateDeadlineProgressIndicator(DateTime deadlineUtc)
    {
        var indicatorType = BackendHostType.Assembly.GetType("ReSharperPlugin.IndexMcp.DeadlineProgressIndicator");
        Assert.That(indicatorType, Is.Not.Null, "Missing type 'DeadlineProgressIndicator'.");

        var constructor = indicatorType!.GetConstructor(
            BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
            null,
            new[] { typeof(DateTime), typeof(string) },
            null);
        Assert.That(constructor, Is.Not.Null, "Missing DeadlineProgressIndicator(DateTime, string) constructor.");

        return constructor!.Invoke(new object[]
        {
            deadlineUtc,
            "Rider F# find_references requires warmed ReSharper usage caches; module/type-only project_files searches should retry after IDE warm-up or use a position/member target."
        });
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

    private static string FindRepositoryRoot()
    {
        var current = new DirectoryInfo(TestContext.CurrentContext.TestDirectory);
        while (current != null)
        {
            var candidate = Path.Combine(current.FullName, "src", "dotnet", "ReSharperPlugin.IndexMcp.Tests", "testData", "CSharpProductionReadiness", "MutationWorkspace", "fixture-index.json");
            if (File.Exists(candidate))
                return current.FullName;

            current = current.Parent;
        }

        throw new AssertionException("Could not locate repository root for C# production-readiness fixtures.");
    }
}
