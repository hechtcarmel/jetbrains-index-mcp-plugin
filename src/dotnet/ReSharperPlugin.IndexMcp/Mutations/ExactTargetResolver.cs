using System;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;

namespace ReSharperPlugin.IndexMcp.Mutations;

internal static class ExactTargetResolver
{
    private static readonly Regex TypeDeclarationPattern = new(@"\b(?:class|interface|record|struct|enum)\s+(?<name>@?[A-Za-z_][A-Za-z0-9_]*)", RegexOptions.Compiled);
    private static readonly Regex LocalDeclarationPattern = new(@"\b(?:var|[A-Za-z_][A-Za-z0-9_<>,\[\]?]*)\s+(?<name>@?[A-Za-z_][A-Za-z0-9_]*)\s*(?:=|;)", RegexOptions.Compiled);
    private static readonly Regex FieldDeclarationPattern = new(@"\b(?:(?:public|private|protected|internal|static|readonly|sealed|virtual|override|partial|new|unsafe|volatile|required|file)\s+)*(?:[A-Za-z_][A-Za-z0-9_<>,\[\]?]*)\s+(?<name>@?[A-Za-z_][A-Za-z0-9_]*)\s*(?:=|;|=>|\{)", RegexOptions.Compiled);
    private static readonly Regex MethodDeclarationPattern = new(@"\b(?:(?:public|private|protected|internal|static|virtual|override|sealed|partial|async|new|unsafe|extern)\s+)*(?:[A-Za-z_][A-Za-z0-9_<>,\[\]?]*)\s+(?<name>@?[A-Za-z_][A-Za-z0-9_]*)\s*\(", RegexOptions.Compiled);
    private static readonly Regex ParameterPattern = new(@"(?<name>@?[A-Za-z_][A-Za-z0-9_]*)\s*(?:,|\))", RegexOptions.Compiled);

    public static ExactTargetResolution ResolveExactTarget(string workspaceRoot, string relativeFilePath, int line, int column, string requestedSymbol)
        => ResolveExactTargetCore(Path.Combine(workspaceRoot, relativeFilePath), relativeFilePath, line, column, requestedSymbol);

    public static ExactTargetResolution ResolveExactTarget(string absoluteFilePath, int line, int column, string requestedSymbol)
        => ResolveExactTargetCore(absoluteFilePath, Path.GetFileName(absoluteFilePath), line, column, requestedSymbol);

    public static ExactTargetResolution ResolveRenameTarget(string workspaceRoot, string relativeFilePath, int line, int column, string requestedSymbol)
        => ResolveExactTarget(workspaceRoot, relativeFilePath, line, column, requestedSymbol);

    public static ExactTargetResolution ResolveRenameTarget(string absoluteFilePath, int line, int column, string requestedSymbol)
        => ResolveExactTarget(absoluteFilePath, line, column, requestedSymbol);

    public static ExactTargetResolution ResolveContract(string workspaceRoot, string relativeFilePath, int line, int column, string requestedSymbol)
        => ResolveExactTarget(workspaceRoot, relativeFilePath, line, column, requestedSymbol);

    public static ExactTargetResolution ResolveContract(string absoluteFilePath, int line, int column, string requestedSymbol)
        => ResolveExactTarget(absoluteFilePath, line, column, requestedSymbol);

    public static ExactTargetResolution ResolveFileTarget(string workspaceRoot, string relativeFilePath, string? requestedFileName = null)
        => ResolveFileTargetCore(Path.Combine(workspaceRoot, relativeFilePath), relativeFilePath, requestedFileName);

    public static ExactTargetResolution ResolveFileTarget(string absoluteFilePath, string? requestedFileName = null)
        => ResolveFileTargetCore(absoluteFilePath, Path.GetFileName(absoluteFilePath), requestedFileName);

    public static SourceToken? TryReadSourceToken(string absoluteFilePath, int lineNumber, int columnNumber)
    {
        if (!File.Exists(absoluteFilePath))
            return null;

        var lines = File.ReadAllLines(absoluteFilePath);
        if (lineNumber < 1 || lineNumber > lines.Length)
            return null;

        var line = lines[lineNumber - 1];
        if (columnNumber < 1 || columnNumber > line.Length)
            return null;

        return TryReadIdentifierToken(line, columnNumber);
    }

    private static ExactTargetResolution ResolveExactTargetCore(string absoluteFilePath, string logicalFilePath, int lineNumber, int columnNumber, string requestedSymbol)
    {
        if (!File.Exists(absoluteFilePath))
        {
            return ExactTargetResolution.Failure(
                MutationResolutionStatuses.Unsupported,
                $"Exact target resolution failed because '{logicalFilePath}' does not exist.");
        }

        var lines = File.ReadAllLines(absoluteFilePath);
        if (lineNumber < 1 || lineNumber > lines.Length)
        {
            return ExactTargetResolution.Failure(
                MutationResolutionStatuses.Ambiguous,
                $"Exact target resolution requires a valid 1-based line inside '{logicalFilePath}'.");
        }

        var line = lines[lineNumber - 1];
        if (columnNumber < 1 || columnNumber > line.Length)
        {
            return ExactTargetResolution.Failure(
                MutationResolutionStatuses.Ambiguous,
                $"Exact target resolution requires a valid 1-based column inside '{logicalFilePath}'.");
        }

        var currentChar = line[columnNumber - 1];
        if (char.IsWhiteSpace(currentChar))
        {
            return ExactTargetResolution.Failure(
                MutationResolutionStatuses.Ambiguous,
                "Ambiguous whitespace caret: exact mutation targets must point to the precise name token.");
        }

        var token = TryReadIdentifierToken(line, columnNumber);
        if (token == null)
        {
            return ExactTargetResolution.Failure(
                MutationResolutionStatuses.Unsupported,
                "Exact mutation targeting rejected a non-name token and refused to widen to the containing declaration.");
        }

        if (!string.Equals(token.Text, requestedSymbol, StringComparison.Ordinal))
        {
            return ExactTargetResolution.Failure(
                MutationResolutionStatuses.Mismatch,
                $"Requested token/name mismatch: expected '{requestedSymbol}', found exact token '{token.Text}'.",
                sourceTokenText: token.Text);
        }

        var braceDepth = CalculateBraceDepth(lines, lineNumber, token.StartColumn);
        var targetKind = ClassifyDeclaration(line, token, braceDepth);
        if (targetKind == MutationTargetKind.Unknown)
        {
            return ExactTargetResolution.Failure(
                MutationResolutionStatuses.Unsupported,
                "Exact mutation targeting rejected this token because it is not the declaration name and widening to a containing declaration is forbidden.",
                sourceTokenText: token.Text);
        }

        return ExactTargetResolution.Success(targetKind, token.Text, token.Text);
    }

    private static ExactTargetResolution ResolveFileTargetCore(string absoluteFilePath, string logicalFilePath, string? requestedFileName)
    {
        if (!File.Exists(absoluteFilePath))
        {
            return ExactTargetResolution.Failure(
                MutationResolutionStatuses.Unsupported,
                $"Exact file target resolution failed because '{logicalFilePath}' does not exist.");
        }

        var fileName = Path.GetFileName(absoluteFilePath);
        if (!string.IsNullOrWhiteSpace(requestedFileName) &&
            !string.Equals(fileName, requestedFileName, StringComparison.Ordinal))
        {
            return ExactTargetResolution.Failure(
                MutationResolutionStatuses.Mismatch,
                $"Requested file/name mismatch: expected '{requestedFileName}', found '{fileName}'.",
                targetKind: MutationTargetKind.File.ToContractValue(),
                resolvedName: fileName,
                sourceTokenText: fileName);
        }

        return ExactTargetResolution.Success(MutationTargetKind.File, fileName, fileName, "Resolved exact file target.");
    }

    private static MutationTargetKind ClassifyDeclaration(string line, SourceToken token, int braceDepth)
    {
        if (IsExactGroupMatch(TypeDeclarationPattern, line, token))
            return MutationTargetKind.Type;

        if (IsParameterDeclaration(line, token))
            return MutationTargetKind.Parameter;

        if (braceDepth >= 2 && IsExactGroupMatch(LocalDeclarationPattern, line, token))
            return MutationTargetKind.Local;

        if (braceDepth <= 1 && (IsExactGroupMatch(FieldDeclarationPattern, line, token) || IsExactGroupMatch(MethodDeclarationPattern, line, token)))
            return MutationTargetKind.Member;

        if (IsExactGroupMatch(LocalDeclarationPattern, line, token))
            return MutationTargetKind.Local;

        if (IsExactGroupMatch(FieldDeclarationPattern, line, token) || IsExactGroupMatch(MethodDeclarationPattern, line, token))
            return MutationTargetKind.Member;

        return MutationTargetKind.Unknown;
    }

    private static int CalculateBraceDepth(string[] lines, int lineNumber, int columnNumber)
    {
        var depth = 0;
        for (var index = 0; index < lineNumber - 1; index++)
            depth = UpdateBraceDepth(depth, lines[index]);

        var currentLinePrefix = lines[lineNumber - 1].Substring(0, Math.Max(0, columnNumber - 1));
        return UpdateBraceDepth(depth, currentLinePrefix);
    }

    private static int UpdateBraceDepth(int depth, string text)
    {
        foreach (var character in text)
        {
            if (character == '{')
                depth++;
            else if (character == '}')
                depth = Math.Max(0, depth - 1);
        }

        return depth;
    }

    private static bool IsParameterDeclaration(string line, SourceToken token)
    {
        var openParen = line.IndexOf('(');
        var closeParen = line.LastIndexOf(')');
        if (openParen < 0 || closeParen <= openParen)
            return false;

        var parameterText = line.Substring(openParen + 1, closeParen - openParen - 1);
        var precedingText = line.Substring(0, openParen);
        if (!MethodDeclarationPattern.IsMatch(precedingText + "("))
            return false;

        foreach (Match match in ParameterPattern.Matches(parameterText + ")"))
        {
            var group = match.Groups["name"];
            if (!group.Success)
                continue;

            var startColumn = openParen + group.Index + 2;
            var endColumn = startColumn + group.Length - 1;
            if (token.StartColumn == startColumn && token.EndColumn == endColumn)
                return true;
        }

        return false;
    }

    private static bool IsExactGroupMatch(Regex regex, string line, SourceToken token)
        => regex.Matches(line)
            .Cast<Match>()
            .Select(match => match.Groups["name"])
            .Any(group => group.Success &&
                          token.StartColumn == group.Index + 1 &&
                          token.EndColumn == group.Index + group.Length);

    private static SourceToken? TryReadIdentifierToken(string line, int columnNumber)
    {
        var index = columnNumber - 1;
        if (index < 0 || index >= line.Length || !IsIdentifierCharacter(line[index]))
            return null;

        var start = index;
        while (start > 0 && IsIdentifierCharacter(line[start - 1]))
            start--;

        var end = index;
        while (end + 1 < line.Length && IsIdentifierCharacter(line[end + 1]))
            end++;

        var tokenText = line.Substring(start, end - start + 1);
        return new SourceToken(tokenText, start + 1, end + 1);
    }

    private static bool IsIdentifierCharacter(char c)
        => char.IsLetterOrDigit(c) || c == '_' || c == '@';
}
