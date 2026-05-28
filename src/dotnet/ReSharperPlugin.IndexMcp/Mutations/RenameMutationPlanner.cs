using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;

namespace ReSharperPlugin.IndexMcp.Mutations;

internal sealed record RenameMutationPlan(
    string OperationKind,
    ExactTargetResolution Resolution,
    string? OldPath,
    string? NewPath)
{
    public bool CanProceed => string.Equals(Resolution.Status, MutationResolutionStatuses.Resolved, StringComparison.OrdinalIgnoreCase);
}

internal static class RenameMutationPlanner
{
    private static readonly Regex TypeDeclarationPattern = new(@"\b(?:class|interface|record|struct|enum)\s+(?<name>@?[A-Za-z_][A-Za-z0-9_]*)", RegexOptions.Compiled);

    public static RenameMutationPlan PlanExactSymbolRename(string absoluteFilePath, int line, int column)
    {
        var token = ExactTargetResolver.TryReadSourceToken(absoluteFilePath, line, column);
        if (token == null)
        {
            return new RenameMutationPlan(
                "symbol",
                ExactTargetResolution.Failure(
                    MutationResolutionStatuses.Ambiguous,
                    "Exact symbol rename requires the caret to point to the precise declaration name token. Unsafe widening is rejected before mutation."),
                absoluteFilePath,
                absoluteFilePath);
        }

        var resolution = ExactTargetResolver.ResolveRenameTarget(absoluteFilePath, line, column, token.Text);
        return new RenameMutationPlan("symbol", resolution, absoluteFilePath, absoluteFilePath);
    }

    public static RenameMutationPlan PlanExactFileRename(string absoluteFilePath, string newName)
    {
        var currentFileName = Path.GetFileName(absoluteFilePath);
        var newPath = CombinePath(Path.GetDirectoryName(absoluteFilePath), newName);
        var fileResolution = ExactTargetResolver.ResolveFileTarget(absoluteFilePath, currentFileName);
        if (!string.Equals(fileResolution.Status, MutationResolutionStatuses.Resolved, StringComparison.OrdinalIgnoreCase))
            return new RenameMutationPlan("file", fileResolution, absoluteFilePath, newPath);

        return new RenameMutationPlan("file", fileResolution, absoluteFilePath, newPath);
    }

    internal static IReadOnlyList<string> ReadDeclaredTypeNames(string absoluteFilePath)
    {
        if (!File.Exists(absoluteFilePath))
            return Array.Empty<string>();

        var text = File.ReadAllText(absoluteFilePath);
        return TypeDeclarationPattern.Matches(text)
            .Cast<Match>()
            .Select(match => match.Groups["name"].Value)
            .Where(name => !string.IsNullOrWhiteSpace(name))
            .Distinct(StringComparer.Ordinal)
            .ToArray();
    }

    private static string CombinePath(string? directory, string fileName)
        => string.IsNullOrWhiteSpace(directory)
            ? fileName
            : Path.Combine(directory, fileName);
}
