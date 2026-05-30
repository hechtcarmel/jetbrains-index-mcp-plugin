using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using JetBrains.Rider.Model.IndexMcp;

namespace ReSharperPlugin.IndexMcp.Mutations;

internal static class SafeDeleteMutationExecutor
{
    public static RdSafeDeleteResult ExecuteSafeDelete(string absoluteFilePath, int line, int column, string requestedSymbol, bool force, IReadOnlyList<RdSafeDeleteBlockedUsage> externalUsages)
    {
        if (string.IsNullOrWhiteSpace(requestedSymbol))
            requestedSymbol = ExactTargetResolver.TryReadSourceToken(absoluteFilePath, line, column)?.Text ?? string.Empty;

        var resolution = ExactTargetResolver.ResolveContract(absoluteFilePath, line, column, requestedSymbol);
        if (!string.Equals(resolution.Status, MutationResolutionStatuses.Resolved, StringComparison.OrdinalIgnoreCase))
            return ToBlockedResult(resolution, Array.Empty<RdSafeDeleteBlockedUsage>());

        if (!string.Equals(resolution.TargetKind, MutationTargetKind.Type.ToContractValue(), StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(resolution.TargetKind, MutationTargetKind.File.ToContractValue(), StringComparison.OrdinalIgnoreCase))
        {
            return MutationVerificationService
                .Unsupported("Rider safe delete currently supports exact type/file targets only. The backend refused to widen deletion semantics.")
                .ToSafeDeleteResult();
        }

        return ExecuteTypeDelete(absoluteFilePath, requestedSymbol, force, resolution, requestedSymbol, externalUsages);
    }

    public static RdSafeDeleteResult ExecuteFileSafeDelete(string absoluteFilePath, bool force, IReadOnlyList<RdSafeDeleteBlockedUsage> externalUsages)
    {
        var fileName = Path.GetFileName(absoluteFilePath);
        var resolution = ExactTargetResolver.ResolveFileTarget(absoluteFilePath, fileName);
        if (!string.Equals(resolution.Status, MutationResolutionStatuses.Resolved, StringComparison.OrdinalIgnoreCase))
            return ToBlockedResult(resolution, Array.Empty<RdSafeDeleteBlockedUsage>());

        return ExecuteTypeDelete(absoluteFilePath, Path.GetFileNameWithoutExtension(absoluteFilePath), force, resolution, fileName, externalUsages);
    }

    private static RdSafeDeleteResult ExecuteTypeDelete(string absoluteFilePath, string requestedSymbol, bool force, ExactTargetResolution resolution, string displayName, IReadOnlyList<RdSafeDeleteBlockedUsage> externalUsages)
    {

        var blockedUsages = externalUsages as List<RdSafeDeleteBlockedUsage> ?? externalUsages.ToList();
        if (blockedUsages.Count > 0 && !force)
        {
            var outcome = MutationVerificationService.Blocked(
                $"Safe delete was blocked because '{displayName}' still has external usages.");
            return outcome.ToSafeDeleteResult(blockedUsages);
        }

        var typeNames = RenameMutationPlanner.ReadDeclaredTypeNames(absoluteFilePath);
        if (typeNames.Count != 1 || !string.Equals(typeNames[0], requestedSymbol, StringComparison.Ordinal))
        {
            return MutationVerificationService
                .Unsupported("Rider safe delete refused to delete a symbol whose file contains multiple or non-matching type declarations.")
                .ToSafeDeleteResult(blockedUsages);
        }

        try
        {
            File.Delete(absoluteFilePath);
            var message = blockedUsages.Count == 0
                ? $"Safely deleted '{displayName}' after confirming no external usages remained."
                : $"Force deleted '{displayName}' after reporting remaining usages.";

            var outcome = MutationVerificationService.CreateOutcome(
                mutationApplied: true,
                affectedFiles: new[] { absoluteFilePath },
                changesCount: 1,
                message: message,
                status: MutationOutcomeStatuses.Success);

            return outcome.ToSafeDeleteResult(blockedUsages.Count == 0 ? Array.Empty<RdSafeDeleteBlockedUsage>() : blockedUsages);
        }
        catch (Exception ex)
        {
            return MutationVerificationService
                .Blocked($"ReSharper backend safe delete failed: {ex.GetType().Name}: {ex.Message}")
                .ToSafeDeleteResult(blockedUsages);
        }
    }

    private static RdSafeDeleteResult ToBlockedResult(ExactTargetResolution resolution, IEnumerable<RdSafeDeleteBlockedUsage> blockedUsages)
    {
        var outcome = string.Equals(resolution.Status, MutationResolutionStatuses.Unsupported, StringComparison.OrdinalIgnoreCase)
            ? MutationVerificationService.Unsupported(resolution.Message ?? "Safe delete was rejected before mutation.")
            : MutationVerificationService.Blocked(resolution.Message ?? "Safe delete was rejected before mutation.");
        return outcome.ToSafeDeleteResult(blockedUsages);
    }
}
