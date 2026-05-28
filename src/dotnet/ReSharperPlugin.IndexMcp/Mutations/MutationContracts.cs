using System;

namespace ReSharperPlugin.IndexMcp.Mutations;

internal enum MutationTargetKind
{
    Unknown,
    Local,
    Parameter,
    Member,
    Type,
    File
}

internal static class MutationTargetKindNames
{
    public static string ToContractValue(this MutationTargetKind kind)
        => kind switch
        {
            MutationTargetKind.Local => "local",
            MutationTargetKind.Parameter => "parameter",
            MutationTargetKind.Member => "member",
            MutationTargetKind.Type => "type",
            MutationTargetKind.File => "file",
            _ => "unknown"
        };
}

internal static class MutationResolutionStatuses
{
    public const string Resolved = "resolved";
    public const string Ambiguous = "ambiguous";
    public const string Mismatch = "mismatch";
    public const string Unsupported = "unsupported";
}

internal static class MutationOutcomeStatuses
{
    public const string Success = "success";
    public const string NoOp = "no_op";
    public const string Blocked = "blocked";
    public const string Unsupported = "unsupported";
    public const string VerificationLimited = "verification_limited";
    public const string VerificationFailed = "verification_failed";
}

internal static class MutationVerificationStatuses
{
    public const string Verified = "verified";
    public const string Limited = "limited";
    public const string Failed = "failed";
    public const string NotRun = "not_run";
}

internal sealed record SourceToken(string Text, int StartColumn, int EndColumn)
{
    public bool Contains(int column)
        => column >= StartColumn && column <= EndColumn;
}

internal sealed record ExactTargetResolution(
    string Status,
    string? TargetKind,
    string? ResolvedName,
    string? SourceTokenText,
    string? Message)
{
    public static ExactTargetResolution Success(MutationTargetKind targetKind, string resolvedName, string sourceTokenText, string? message = null)
        => new(
            MutationResolutionStatuses.Resolved,
            targetKind.ToContractValue(),
            resolvedName,
            sourceTokenText,
            message ?? $"Resolved exact {targetKind.ToContractValue()} target '{resolvedName}'.");

    public static ExactTargetResolution Failure(string status, string message, string? targetKind = null, string? resolvedName = null, string? sourceTokenText = null)
        => new(status, targetKind, resolvedName, sourceTokenText, message);
}
