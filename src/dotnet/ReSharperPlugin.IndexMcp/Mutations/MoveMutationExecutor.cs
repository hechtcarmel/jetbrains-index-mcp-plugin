using System;
using System.IO;

namespace ReSharperPlugin.IndexMcp.Mutations;

internal static class MoveMutationExecutor
{
    public static VerifiedMutationOutcome ExecuteSemanticMove(string absoluteFilePath, string destinationDirectory)
    {
        var plan = MoveMutationPlanner.PlanSemanticMove(absoluteFilePath, destinationDirectory);
        if (!plan.CanProceed)
            return ToBlockedOutcome(plan.Resolution, plan.NewPath ?? absoluteFilePath);

        var oldPath = plan.OldPath!;
        var newPath = plan.NewPath!;
        if (string.Equals(oldPath, newPath, StringComparison.OrdinalIgnoreCase))
        {
            return MutationVerificationService.NoOp(
                "File move refused because the destination directory already matches the current location (no-op move).");
        }

        if (File.Exists(newPath))
            return MutationVerificationService.Blocked($"Semantic move failed because destination '{newPath}' already exists.");

        _ = destinationDirectory;
        return MutationVerificationService.Unsupported(
            "Semantic move requires the Rider backend MoveToFolderWorkflow/MoveToFolderDataProvider lane. The standalone executor is fail-closed and will not perform manual filesystem, namespace, using, or string rewrite mutations.");
    }

    private static VerifiedMutationOutcome ToBlockedOutcome(ExactTargetResolution resolution, string newPath)
    {
        _ = newPath;
        return string.Equals(resolution.Status, MutationResolutionStatuses.Unsupported, StringComparison.OrdinalIgnoreCase)
            ? MutationVerificationService.Unsupported(resolution.Message ?? "Semantic move was rejected before mutation.")
            : MutationVerificationService.Blocked(resolution.Message ?? "Semantic move was rejected before mutation.");
    }
}
