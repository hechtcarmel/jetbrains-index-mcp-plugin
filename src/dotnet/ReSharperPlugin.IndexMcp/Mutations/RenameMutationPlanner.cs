using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;

namespace ReSharperPlugin.IndexMcp.Mutations;

internal static class RenameMutationPlanner
{
    internal static IReadOnlyList<string> ReadDeclaredTypeNames(string absoluteFilePath)
    {
        if (!File.Exists(absoluteFilePath))
            return Array.Empty<string>();

        var text = File.ReadAllText(absoluteFilePath);
        return ExactTargetResolver.TypeDeclarationPattern.Matches(text)
            .Cast<Match>()
            .Select(match => match.Groups["name"].Value)
            .Where(name => !string.IsNullOrWhiteSpace(name))
            .Distinct(StringComparer.Ordinal)
            .ToArray();
    }
}
