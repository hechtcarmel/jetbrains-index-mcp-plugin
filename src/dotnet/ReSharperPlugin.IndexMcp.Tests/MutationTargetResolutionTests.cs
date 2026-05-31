using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.Json;
using NUnit.Framework;

namespace ReSharperPlugin.IndexMcp.Tests;

[TestFixture]
public class MutationTargetResolutionTests
{
    private static readonly string RepositoryRoot = FindRepositoryRoot();
    private static readonly string WorkspaceRoot = Path.Combine(RepositoryRoot,
        "src", "dotnet", "ReSharperPlugin.IndexMcp.Tests", "testData", "CSharpProductionReadiness", "MutationWorkspace");
    private static readonly Lazy<MutationFixtureCatalog> FixtureCatalog = new(LoadFixtureCatalog);
    private static readonly Type BackendAssemblyMarker = typeof(IndexMcpBackendHost);
    private const string ResolverMethodName = "ResolveContract";

    [TestCase("localRename")]
    [TestCase("parameterRename")]
    [TestCase("memberRename")]
    [TestCase("typeRename")]
    public void ExactFixtureTargets_ResolveToExactKinds(string fixtureName)
    {
        var fixture = GetCase(fixtureName);

        var result = ResolveContract(fixture, fixture.Column, fixture.Symbol);

        Assert.Multiple(() =>
        {
            Assert.That(IsSuccess(result.Status), Is.True, result.Message);
            Assert.That(NormalizeKind(result.TargetKind), Is.EqualTo(fixture.Kind), result.Message);
            Assert.That(result.ResolvedName, Is.EqualTo(fixture.Symbol), result.Message);
            Assert.That(result.SourceTokenText, Is.EqualTo(fixture.Symbol), result.Message);
        });
    }

    [TestCase("localRename")]
    [TestCase("parameterRename")]
    public void WhitespaceBeforeExactToken_FailsClosed(string fixtureName)
    {
        var fixture = GetCase(fixtureName);
        var whitespaceColumn = fixture.WhitespaceBeforeColumn ?? fixture.Column - 1;

        Assert.That(GetCharacterAt(fixture.File, fixture.Line, whitespaceColumn), Is.EqualTo(' '),
            $"Fixture '{fixtureName}' must point to whitespace for ambiguity coverage.");

        var result = ResolveContract(fixture, whitespaceColumn, fixture.Symbol);

        Assert.Multiple(() =>
        {
            Assert.That(IsSuccess(result.Status), Is.False, "Ambiguous caret positions must fail closed.");
            Assert.That(ContainsAny(result, "ambiguous", "whitespace", "exact", "precise"), Is.True,
                $"Expected ambiguous-caret evidence, got status='{result.Status}', message='{result.Message}'.");
        });
    }

    [TestCase("localRename")]
    [TestCase("memberRename")]
    [TestCase("typeRename")]
    public void RequestedSymbolMismatch_FailsClosed(string fixtureName)
    {
        var fixture = GetCase(fixtureName);
        var mismatchedSymbol = fixture.Symbol + "_mismatch";

        var result = ResolveContract(fixture, fixture.Column, mismatchedSymbol);

        Assert.Multiple(() =>
        {
            Assert.That(IsSuccess(result.Status), Is.False, "Token/name mismatches must fail closed.");
            Assert.That(ContainsAny(result, "mismatch", "exact", "token", "name"), Is.True,
                $"Expected mismatch evidence, got status='{result.Status}', message='{result.Message}'.");
        });
    }

    [TestCase("memberRename")]
    [TestCase("typeRename")]
    public void NonNameTokens_DoNotWidenToContainingDeclaration(string fixtureName)
    {
        var fixture = GetCase(fixtureName);
        Assert.That(fixture.NonNameTokenColumn, Is.Not.Null,
            $"Fixture '{fixtureName}' must provide a non-name token column for widen-rejection coverage.");

        var result = ResolveContract(fixture, fixture.NonNameTokenColumn!.Value, fixture.Symbol);

        Assert.Multiple(() =>
        {
            Assert.That(IsSuccess(result.Status), Is.False, "Non-name tokens must not resolve as exact rename targets.");
            Assert.That(NormalizeKind(result.TargetKind), Is.Not.EqualTo(fixture.Kind),
                "Resolver widened to the containing declaration instead of failing closed.");
            Assert.That(ContainsAny(result, "widen", "exact", "token", "mismatch", "containing"), Is.True,
                $"Expected widen-rejection evidence, got status='{result.Status}', message='{result.Message}'.");
        });
    }

    private static MutationFixtureCase GetCase(string fixtureName)
    {
        if (!FixtureCatalog.Value.Cases.TryGetValue(fixtureName, out var fixture))
            throw new AssertionException($"Fixture '{fixtureName}' was not found in fixture-index.json.");

        return fixture with { Name = fixtureName };
    }

    private static ContractResult ResolveContract(MutationFixtureCase fixture, int column, string requestedSymbol)
    {
        var resolverType = BackendAssemblyMarker.Assembly.GetType("ReSharperPlugin.IndexMcp.Mutations.ExactTargetResolver");
        Assert.That(resolverType, Is.Not.Null,
            "Task 3.1 must add ReSharperPlugin.IndexMcp.Mutations.ExactTargetResolver.");

        var method = resolverType!
            .GetMethods(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static)
            .FirstOrDefault(IsSupportedResolverMethod);
        Assert.That(method, Is.Not.Null,
            "ExactTargetResolver must expose a static ResolveContract method for fixture-backed contract tests.");

        var rawResult = method!.GetParameters().Length == 5
            ? method.Invoke(null, new object?[] { WorkspaceRoot, fixture.File, fixture.Line, column, requestedSymbol })
            : method.Invoke(null, new object?[] { Path.Combine(WorkspaceRoot, fixture.File), fixture.Line, column, requestedSymbol });

        return ReadContractResult(rawResult);
    }

    private static bool IsSupportedResolverMethod(MethodInfo method)
    {
        if (!string.Equals(method.Name, ResolverMethodName, StringComparison.Ordinal))
            return false;

        var parameters = method.GetParameters();
        return parameters.Length switch
        {
            5 => parameters[0].ParameterType == typeof(string) &&
                 parameters[1].ParameterType == typeof(string) &&
                 parameters[2].ParameterType == typeof(int) &&
                 parameters[3].ParameterType == typeof(int) &&
                 parameters[4].ParameterType == typeof(string),
            4 => parameters[0].ParameterType == typeof(string) &&
                 parameters[1].ParameterType == typeof(int) &&
                 parameters[2].ParameterType == typeof(int) &&
                 parameters[3].ParameterType == typeof(string),
            _ => false
        };
    }

    private static ContractResult ReadContractResult(object? rawResult)
    {
        Assert.That(rawResult, Is.Not.Null,
            "Exact target contract method returned null; it must return a structured result.");

        var resultType = rawResult!.GetType();
        return new ContractResult(
            GetPropertyValue(resultType, rawResult, true, "Status", "Outcome", "ResolutionStatus") ?? string.Empty,
            GetPropertyValue(resultType, rawResult, false, "TargetKind", "Kind", "SymbolKind"),
            GetPropertyValue(resultType, rawResult, false, "ResolvedName", "DeclaredName", "SymbolName"),
            GetPropertyValue(resultType, rawResult, false, "SourceTokenText", "SourceToken", "TokenText"),
            GetPropertyValue(resultType, rawResult, false, "Message", "Reason", "Diagnostic"));
    }

    private static string? GetPropertyValue(Type resultType, object instance, bool required, params string[] candidates)
    {
        foreach (var candidate in candidates)
        {
            var property = resultType.GetProperty(candidate, BindingFlags.Public | BindingFlags.Instance);
            if (property == null)
                continue;

            return property.GetValue(instance)?.ToString();
        }

        if (required)
        {
            throw new AssertionException(
                $"Result type '{resultType.FullName}' is missing one of the required properties: {string.Join(", ", candidates)}.");
        }

        return null;
    }

    private static bool IsSuccess(string? status)
        => string.Equals(status, "success", StringComparison.OrdinalIgnoreCase) ||
           string.Equals(status, "resolved", StringComparison.OrdinalIgnoreCase);

    private static string? NormalizeKind(string? kind)
    {
        if (string.IsNullOrWhiteSpace(kind))
            return null;

        return kind.Trim().ToLowerInvariant() switch
        {
            "localvariable" or "local_variable" => "local",
            "field" or "property" or "method" => "member",
            "class" or "interface" or "record" => "type",
            var other => other
        };
    }

    private static bool ContainsAny(ContractResult result, params string[] keywords)
    {
        var haystacks = new[] { result.Status, result.Message, result.TargetKind, result.SourceTokenText }
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .Select(value => value!)
            .ToArray();

        return keywords.Any(keyword => haystacks.Any(value => value.Contains(keyword, StringComparison.OrdinalIgnoreCase)));
    }

    private static char GetCharacterAt(string relativeFilePath, int line, int column)
    {
        var absoluteFilePath = Path.Combine(WorkspaceRoot, relativeFilePath);
        var lineText = File.ReadLines(absoluteFilePath).ElementAt(line - 1);
        return lineText[column - 1];
    }

    private static MutationFixtureCatalog LoadFixtureCatalog()
    {
        var fixtureIndexPath = Path.Combine(WorkspaceRoot, "fixture-index.json");
        var json = File.ReadAllText(fixtureIndexPath);
        var options = new JsonSerializerOptions { PropertyNameCaseInsensitive = true };
        var catalog = JsonSerializer.Deserialize<MutationFixtureCatalog>(json, options);
        return catalog ?? throw new AssertionException("Failed to deserialize C# production-readiness fixture index.");
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

        throw new AssertionException("Could not locate repository root for MutationWorkspace fixtures.");
    }

    private sealed record ContractResult(string Status, string? TargetKind, string? ResolvedName, string? SourceTokenText, string? Message);

    private sealed record MutationFixtureCatalog(Dictionary<string, MutationFixtureCase> Cases);

    private sealed record MutationFixtureCase(
        string File,
        string Kind,
        int Line,
        int Column,
        string Symbol,
        string NewName,
        int? WhitespaceBeforeColumn = null,
        int? NonNameTokenColumn = null)
    {
        public string Name { get; init; } = string.Empty;
    }
}
