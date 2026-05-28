using System;
using System.IO;

namespace ReSharperPlugin.IndexMcp.Mutations;

internal sealed record MoveMutationPlan(
    string OperationKind,
    ExactTargetResolution Resolution,
    string? OldPath,
    string? NewPath)
{
    public bool CanProceed => string.Equals(Resolution.Status, MutationResolutionStatuses.Resolved, StringComparison.OrdinalIgnoreCase);
}

internal static class MoveMutationPlanner
{
    public static MoveMutationPlan PlanSemanticMove(string absoluteFilePath, string destinationDirectory)
    {
        var fileName = Path.GetFileName(absoluteFilePath);
        var newPath = Path.Combine(destinationDirectory, fileName);
        var fileResolution = ExactTargetResolver.ResolveFileTarget(absoluteFilePath, fileName);
        if (!string.Equals(fileResolution.Status, MutationResolutionStatuses.Resolved, StringComparison.OrdinalIgnoreCase))
            return new MoveMutationPlan("move", fileResolution, absoluteFilePath, newPath);

        if (!File.Exists(absoluteFilePath))
        {
            return new MoveMutationPlan(
                "move",
                ExactTargetResolution.Failure(
                    MutationResolutionStatuses.Unsupported,
                    $"Semantic move failed because '{absoluteFilePath}' does not exist.",
                    targetKind: MutationTargetKind.File.ToContractValue(),
                    resolvedName: fileName,
                    sourceTokenText: fileName),
                absoluteFilePath,
                newPath);
        }

        if (Path.GetDirectoryName(absoluteFilePath) is { } currentDirectory &&
            string.Equals(
                currentDirectory.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar),
                destinationDirectory.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar),
                StringComparison.OrdinalIgnoreCase))
        {
            return new MoveMutationPlan(
                "move",
                ExactTargetResolution.Success(MutationTargetKind.File, fileName, fileName, "Resolved exact file target for semantic move execution."),
                absoluteFilePath,
                absoluteFilePath);
        }

        return new MoveMutationPlan(
            "move",
            ExactTargetResolution.Success(
                MutationTargetKind.File,
                fileName,
                fileName,
                "Resolved exact file target for Rider MoveToFolder workflow evaluation."),
            absoluteFilePath,
            newPath);
    }
}
