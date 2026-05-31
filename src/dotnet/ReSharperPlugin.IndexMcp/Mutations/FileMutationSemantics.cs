using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;

namespace ReSharperPlugin.IndexMcp.Mutations;

internal sealed record NamespaceConfirmation(
    string? DeclaredNamespace,
    string? DefaultNamespace)
{
    public string? EffectiveNamespace
        => !string.IsNullOrWhiteSpace(DeclaredNamespace)
            ? DeclaredNamespace
            : DefaultNamespace;
}

internal static class FileMutationSemantics
{
    private static readonly Regex FileScopedNamespacePattern = new(@"(?m)^\s*namespace\s+(?<name>[A-Za-z_][A-Za-z0-9_\.]*)\s*;", RegexOptions.Compiled);
    private static readonly Regex BlockNamespacePattern = new(@"(?m)^\s*namespace\s+(?<name>[A-Za-z_][A-Za-z0-9_\.]*)\s*\{", RegexOptions.Compiled);
    private static readonly Regex RootNamespacePattern = new(@"<RootNamespace>\s*(?<name>[^<]+?)\s*</RootNamespace>", RegexOptions.Compiled | RegexOptions.IgnoreCase);

    public static string? TryReadNamespace(string absoluteFilePath)
    {
        if (!File.Exists(absoluteFilePath))
            return null;

        var text = File.ReadAllText(absoluteFilePath);
        var fileScoped = FileScopedNamespacePattern.Match(text);
        if (fileScoped.Success)
            return fileScoped.Groups["name"].Value;

        var blockScoped = BlockNamespacePattern.Match(text);
        return blockScoped.Success ? blockScoped.Groups["name"].Value : null;
    }

    public static string? TryReadNamespaceOrDefault(string absoluteFilePath, string? projectFilePath)
        => ReadNamespaceConfirmation(absoluteFilePath, projectFilePath).EffectiveNamespace;

    public static NamespaceConfirmation ReadNamespaceConfirmation(string absoluteFilePath, string? projectFilePath)
        => new(
            TryReadNamespace(absoluteFilePath),
            TryComputeDefaultNamespace(projectFilePath, absoluteFilePath));

    public static bool FileContainsAllTokens(string absoluteFilePath, IEnumerable<string>? requiredTokens)
    {
        if (!File.Exists(absoluteFilePath))
            return false;

        var tokens = requiredTokens?
            .Where(token => !string.IsNullOrWhiteSpace(token))
            .Distinct(StringComparer.Ordinal)
            .ToArray();

        if (tokens == null || tokens.Length == 0)
            return false;

        var text = File.ReadAllText(absoluteFilePath);
        return tokens.All(token => text.Contains(token, StringComparison.Ordinal));
    }

    public static string? TryComputeMovedNamespace(string absoluteFilePath, string destinationDirectory, string oldNamespace, string projectRoot)
    {
        var currentDirectory = Path.GetDirectoryName(absoluteFilePath);
        if (string.IsNullOrWhiteSpace(currentDirectory) || string.IsNullOrWhiteSpace(oldNamespace))
            return null;

        var namespaceSegments = oldNamespace.Split('.', StringSplitOptions.RemoveEmptyEntries);
        var currentSegments = GetRelativeSegments(projectRoot, currentDirectory!);
        var destinationSegments = GetRelativeSegments(projectRoot, destinationDirectory);
        if (destinationSegments == null)
            return null;

        var suffixLength = FindMatchingSuffixLength(namespaceSegments, currentSegments);
        if (suffixLength <= 0)
            return null;

        var prefixSegments = namespaceSegments.Take(namespaceSegments.Length - suffixLength);
        return string.Join('.', prefixSegments.Concat(destinationSegments));
    }

    public static string? TryComputeMovedNamespaceFromProjectFile(string absoluteFilePath, string destinationDirectory, string oldNamespace, string? projectFilePath)
    {
        if (string.IsNullOrWhiteSpace(projectFilePath))
            return null;

        var projectDirectory = Path.GetDirectoryName(projectFilePath);
        return string.IsNullOrWhiteSpace(projectDirectory)
            ? null
            : TryComputeMovedNamespace(absoluteFilePath, destinationDirectory, oldNamespace, projectDirectory);
    }

    private static string? TryComputeDefaultNamespace(string? projectFilePath, string absoluteFilePath)
    {
        if (string.IsNullOrWhiteSpace(projectFilePath) || !File.Exists(projectFilePath))
            return null;

        var projectDirectory = Path.GetDirectoryName(projectFilePath);
        if (string.IsNullOrWhiteSpace(projectDirectory))
            return null;

        var rootNamespace = TryReadRootNamespace(projectFilePath) ?? Path.GetFileNameWithoutExtension(projectFilePath);
        if (string.IsNullOrWhiteSpace(rootNamespace))
            return null;

        var fileDirectory = Path.GetDirectoryName(absoluteFilePath);
        if (string.IsNullOrWhiteSpace(fileDirectory))
            return rootNamespace;

        var relativeSegments = GetRelativeSegments(projectDirectory, fileDirectory);
        if (relativeSegments == null || relativeSegments.Count == 0)
            return rootNamespace;

        return string.Join('.', new[] { rootNamespace }.Concat(relativeSegments));
    }

    private static string? TryReadRootNamespace(string projectFilePath)
    {
        var projectText = File.ReadAllText(projectFilePath);
        var match = RootNamespacePattern.Match(projectText);
        return match.Success ? match.Groups["name"].Value.Trim() : null;
    }

    private static IReadOnlyList<string>? GetRelativeSegments(string projectRoot, string targetDirectory)
    {
        var relative = Path.GetRelativePath(projectRoot, targetDirectory);
        if (relative.StartsWith("..", StringComparison.Ordinal))
            return null;

        return relative
            .Split(new[] { Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar }, StringSplitOptions.RemoveEmptyEntries)
            .ToArray();
    }

    private static int FindMatchingSuffixLength(IReadOnlyList<string> namespaceSegments, IReadOnlyList<string>? directorySegments)
    {
        if (directorySegments == null || directorySegments.Count == 0)
            return 0;

        var max = Math.Min(namespaceSegments.Count, directorySegments.Count);
        for (var length = max; length >= 1; length--)
        {
            var namespaceSuffix = namespaceSegments.Skip(namespaceSegments.Count - length);
            var directorySuffix = directorySegments.Skip(directorySegments.Count - length);
            if (namespaceSuffix.SequenceEqual(directorySuffix, StringComparer.OrdinalIgnoreCase))
                return length;
        }

        return 0;
    }
}
