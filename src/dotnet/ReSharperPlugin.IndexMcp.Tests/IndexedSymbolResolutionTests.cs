using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using System.Runtime.CompilerServices;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.CSharp;
using JetBrains.ReSharper.Psi.Tree;
using JetBrains.Rider.Model.IndexMcp;
using JetBrains.UI.RichText;
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
        var otherMethod = CreateTypeMember("Details", new List<IDeclaration> { CreateDeclaration() }, "int");
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

    private static string GetResolutionStatus(object? resolution)
    {
        Assert.That(resolution, Is.Not.Null);
        return GetProperty<string>(resolution!, "Status");
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

    private static IDeclaration CreateDeclaration()
    {
        return ProxyFactory.Create<IDeclaration>(new Dictionary<string, object?>());
    }

    private static ITypeElement CreateTypeElement(params ITypeMember[] members)
    {
        return ProxyFactory.Create<ITypeElement>(new Dictionary<string, object?>
        {
            ["GetMembers"] = members.ToList()
        });
    }

    private static ITypeElement CreateTypeElementCandidate(string shortName)
    {
        return ProxyFactory.Create<ITypeElement>(new Dictionary<string, object?>
        {
            ["get_ShortName"] = shortName,
            ["GetMembers"] = new List<ITypeMember>()
        });
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
        params string[] parameterTypes)
    {
        var parameters = parameterTypes.Select(CreateParameter).ToList();
        return ProxyFactory.Create<ITestTypeMember>(
            new Dictionary<string, object?>
            {
                ["get_ShortName"] = shortName,
                ["GetDeclarations"] = declarations ?? new List<IDeclaration>(),
                ["get_PresentationLanguage"] = CSharpLanguage.Instance,
                ["get_Parameters"] = parameters
            });
    }

    private static ITypeMember CreateTypeMember(string shortName, params string[] parameterTypes)
    {
        return CreateTypeMember(shortName, null, parameterTypes);
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
            var proxy = DispatchProxy.Create<T, InterfaceDispatchProxy>();
            var dispatchProxy = (InterfaceDispatchProxy)(object)proxy;
            dispatchProxy.Configure(typeof(T), handlers);
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
