using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using JetBrains.Rider.Model.IndexMcp;

namespace ReSharperPlugin.IndexMcp.Mutations;

internal sealed record MutationVerification(
    string Status,
    IReadOnlyList<string> ChecksRun,
    IReadOnlyList<string> Warnings)
{
    public RdMutationVerification ToRdModel()
        => new(Status, ChecksRun.ToList(), Warnings.ToList());

    public static MutationVerification Limited(IEnumerable<string>? checksRun, params string[] warnings)
        => new(
            MutationVerificationStatuses.Limited,
            NormalizeList(checksRun),
            NormalizeList(warnings));

    public static MutationVerification Failed(IEnumerable<string>? checksRun, params string[] warnings)
        => new(
            MutationVerificationStatuses.Failed,
            NormalizeList(checksRun),
            NormalizeList(warnings));

    private static IReadOnlyList<string> NormalizeList(IEnumerable<string>? values)
    {
        if (values == null)
            return Array.Empty<string>();

        return values
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .Distinct(StringComparer.Ordinal)
            .ToList();
    }
}

internal sealed record VerifiedMutationOutcome(
    bool Success,
    string Status,
    List<string> AffectedFiles,
    int ChangesCount,
    string Message,
    MutationVerification? Verification)
{
    public RdRenameSymbolResult ToRenameSymbolResult(string oldName, string newName)
        => new(Success, oldName, newName, AffectedFiles, ChangesCount, Message, Status, Verification?.ToRdModel());

    public RdRenameFileResult ToRenameFileResult(string oldPath, string newPath)
        => new(Success, oldPath, newPath, AffectedFiles, ChangesCount, Message, Status, Verification?.ToRdModel());

    public RdMoveFileResult ToMoveFileResult(string oldPath, string newPath)
        => new(Success, oldPath, newPath, AffectedFiles, ChangesCount, Message, Status, Verification?.ToRdModel());

    public RdSafeDeleteResult ToSafeDeleteResult(IEnumerable<RdSafeDeleteBlockedUsage>? blockedUsages = null)
        => new(
            Success,
            AffectedFiles,
            ChangesCount,
            Message,
            Status,
            NormalizeBlockedUsages(blockedUsages),
            Verification?.ToRdModel());

    private static List<RdSafeDeleteBlockedUsage> NormalizeBlockedUsages(IEnumerable<RdSafeDeleteBlockedUsage>? blockedUsages)
        => blockedUsages?
            .Where(usage => usage != null)
            .ToList()
           ?? new List<RdSafeDeleteBlockedUsage>();
}

internal sealed record MutationSemanticEvidence(
    bool ProjectModelReResolved,
    bool PsiReResolved,
    IReadOnlyList<string> ConfirmedAffectedFiles,
    IReadOnlyList<string> ConfirmedReferenceFiles)
{
    public static MutationSemanticEvidence Create(
        bool projectModelReResolved,
        bool psiReResolved,
        IEnumerable<string>? confirmedAffectedFiles,
        IEnumerable<string>? confirmedReferenceFiles)
        => new(
            projectModelReResolved,
            psiReResolved,
            NormalizeList(confirmedAffectedFiles),
            NormalizeList(confirmedReferenceFiles));

    private static IReadOnlyList<string> NormalizeList(IEnumerable<string>? values)
    {
        if (values == null)
            return Array.Empty<string>();

        return values
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
    }
}

internal static class MutationVerificationService
{
    private const string NeedsActiveEditorStatus = "needs_active_editor";
    private const string ConflictStatus = "conflict";
    private const string UnsupportedContextStatus = "unsupported_context";
    private const string FailedStatus = "failed";

    public static VerifiedMutationOutcome Success(IEnumerable<string>? affectedFiles, int changesCount, string message)
        => CreateOutcome(true, affectedFiles, changesCount, message, MutationOutcomeStatuses.Success);

    public static VerifiedMutationOutcome ConfirmSemanticFileMutationProof(
        string primaryFilePath,
        string projectFilePath,
        string? expectedNamespace,
        IEnumerable<string>? affectedFiles,
        IEnumerable<string>? referenceFiles,
        IEnumerable<string>? referenceTokens,
        string successMessage)
        => ConfirmSemanticFileMutationProofWithEvidence(
            primaryFilePath,
            projectFilePath,
            expectedNamespace,
            affectedFiles,
            referenceFiles,
            referenceTokens,
            semanticEvidence: null,
            successMessage);

    public static VerifiedMutationOutcome ConfirmSemanticFileMutationProofWithEvidence(
        string primaryFilePath,
        string projectFilePath,
        string? expectedNamespace,
        IEnumerable<string>? affectedFiles,
        IEnumerable<string>? referenceFiles,
        IEnumerable<string>? referenceTokens,
        MutationSemanticEvidence? semanticEvidence,
        string successMessage)
    {
        var normalizedAffectedFiles = NormalizeAffectedFiles(affectedFiles);
        var checksRun = new List<string>
        {
            "closed_file_diagnostics",
            "project_model_reresolution",
            "psi_reresolution",
            "namespace_default_namespace_confirmation",
            "affected_file_evidence",
            "resolvable_post_mutation_references",
            "semantic_reference_confirmation"
        };

        var warnings = new List<string>();
        var failureReasons = new List<string>();
        var namespaceConfirmation = FileMutationSemantics.ReadNamespaceConfirmation(primaryFilePath, projectFilePath);
        var confirmedNamespaceOrDefault = namespaceConfirmation.EffectiveNamespace;

        if (semanticEvidence == null || !semanticEvidence.ProjectModelReResolved)
            warnings.Add("re-resolved project model references were unavailable after mutation; diagnostics cannot stand in for semantic proof");

        if (semanticEvidence == null || !semanticEvidence.PsiReResolved)
            warnings.Add("re-resolved PSI references were unavailable after mutation; diagnostics cannot stand in for semantic proof");

        if (string.IsNullOrWhiteSpace(confirmedNamespaceOrDefault))
        {
            warnings.Add("namespace/default-namespace confirmation was unavailable after mutation; closed-file diagnostics alone are insufficient");
        }
        else if (!string.IsNullOrWhiteSpace(expectedNamespace)
                 && !string.Equals(confirmedNamespaceOrDefault, expectedNamespace, StringComparison.Ordinal))
        {
            failureReasons.Add($"namespace/default-namespace confirmation mismatch: expected '{expectedNamespace}' but found '{confirmedNamespaceOrDefault}'");
        }

        var confirmedAffectedFiles = NormalizeStrings(semanticEvidence?.ConfirmedAffectedFiles);
        var affectedFileEvidence = normalizedAffectedFiles
            .Where(path => ConfirmedBySemanticEvidence(path, confirmedAffectedFiles))
            .Where(File.Exists)
            .ToList();
        if (affectedFileEvidence.Count == 0)
            warnings.Add("affected-file confirmation was unavailable after mutation; diagnostics cannot stand in for semantic proof");

        var requiredReferenceTokens = NormalizeStrings(referenceTokens);
        var confirmedReferenceFiles = NormalizeStrings(semanticEvidence?.ConfirmedReferenceFiles);
        var referenceEvidence = NormalizeStrings(referenceFiles)
            .Where(filePath => ConfirmedBySemanticEvidence(filePath, confirmedReferenceFiles))
            .Where(File.Exists)
            .Where(filePath => FileMutationSemantics.FileContainsAllTokens(filePath, requiredReferenceTokens))
            .ToList();

        if (referenceEvidence.Count == 0)
            warnings.Add("resolvable post-mutation references could not be semantically confirmed after mutation; diagnostics cannot stand in for semantic proof");

        if (failureReasons.Count > 0)
        {
            return VerificationFailed(
                affectedFileEvidence,
                affectedFileEvidence.Count,
                "Mutation applied edits but semantic post-checks contradicted the expected file mutation result.",
                checksRun,
                failureReasons.ToArray());
        }

        if (warnings.Count > 0)
        {
            return VerificationLimited(
                affectedFileEvidence,
                affectedFileEvidence.Count,
                "Mutation applied edits, but semantic post-checks remained bounded and could not prove safety beyond closed-file diagnostics.",
                checksRun,
                warnings.ToArray());
        }

        return Success(affectedFileEvidence, affectedFileEvidence.Count, successMessage);
    }

    public static VerifiedMutationOutcome NoOp(string message)
        => CreateOutcome(false, Array.Empty<string>(), 0, message, MutationOutcomeStatuses.NoOp);

    public static VerifiedMutationOutcome Blocked(string message)
        => CreateOutcome(false, Array.Empty<string>(), 0, message, MutationOutcomeStatuses.Blocked);

    public static VerifiedMutationOutcome Unsupported(string message)
        => CreateOutcome(false, Array.Empty<string>(), 0, message, MutationOutcomeStatuses.Unsupported);

    public static VerifiedMutationOutcome VerificationLimited(
        IEnumerable<string>? affectedFiles,
        int changesCount,
        string message,
        IEnumerable<string>? checksRun,
        params string[] warnings)
        => CreateOutcome(
            true,
            affectedFiles,
            changesCount,
            message,
            MutationOutcomeStatuses.VerificationLimited,
            MutationVerification.Limited(checksRun, warnings));

    public static VerifiedMutationOutcome VerificationFailed(
        IEnumerable<string>? affectedFiles,
        int changesCount,
        string message,
        IEnumerable<string>? checksRun,
        params string[] warnings)
        => CreateOutcome(
            true,
            affectedFiles,
            changesCount,
            message,
            MutationOutcomeStatuses.VerificationFailed,
            MutationVerification.Failed(checksRun, warnings));

    public static VerifiedMutationOutcome CreateOutcome(
        bool mutationApplied,
        IEnumerable<string>? affectedFiles,
        int changesCount,
        string message,
        string? status = null,
        MutationVerification? verification = null)
    {
        var normalizedFiles = NormalizeAffectedFiles(affectedFiles);
        var normalizedChangesCount = Math.Max(0, changesCount);
        var effectiveStatus = NormalizeStatus(status, mutationApplied, normalizedFiles, normalizedChangesCount, message, verification);
        var effectiveVerification = NormalizeVerification(status, effectiveStatus, verification);
        var keepObservableChanges = KeepsObservableChanges(effectiveStatus);

        return new VerifiedMutationOutcome(
            Success: IsSuccessStatus(effectiveStatus),
            Status: effectiveStatus,
            AffectedFiles: keepObservableChanges ? normalizedFiles : new List<string>(),
            ChangesCount: keepObservableChanges ? normalizedChangesCount : 0,
            Message: message,
            Verification: effectiveVerification);
    }

    private static string NormalizeStatus(
        string? status,
        bool mutationApplied,
        IReadOnlyCollection<string> affectedFiles,
        int changesCount,
        string message,
        MutationVerification? verification)
    {
        if (verification != null)
        {
            if (verification.Status.Equals(MutationVerificationStatuses.Failed, StringComparison.OrdinalIgnoreCase))
                return FailedStatus;
            if (verification.Status.Equals(MutationVerificationStatuses.Limited, StringComparison.OrdinalIgnoreCase))
                return FailedStatus;
        }

        var normalizedStatus = status?.Trim().ToLowerInvariant();
        var hasObservableChanges = changesCount > 0 || affectedFiles.Count > 0;

        if (!string.IsNullOrEmpty(normalizedStatus))
        {
            return normalizedStatus switch
            {
                MutationOutcomeStatuses.Success => hasObservableChanges
                    ? MutationOutcomeStatuses.Success
                    : MutationOutcomeStatuses.NoOp,
                MutationOutcomeStatuses.NoOp => MutationOutcomeStatuses.NoOp,
                MutationOutcomeStatuses.Blocked => NormalizeBlockedStatus(message),
                MutationOutcomeStatuses.Unsupported => UnsupportedContextStatus,
                MutationOutcomeStatuses.VerificationLimited => FailedStatus,
                MutationOutcomeStatuses.VerificationFailed => FailedStatus,
                NeedsActiveEditorStatus => NeedsActiveEditorStatus,
                ConflictStatus => ConflictStatus,
                UnsupportedContextStatus => UnsupportedContextStatus,
                FailedStatus => FailedStatus,
                _ => normalizedStatus
            };
        }

        if (!mutationApplied)
            return NormalizeBlockedStatus(message);

        return hasObservableChanges
            ? MutationOutcomeStatuses.Success
            : MutationOutcomeStatuses.NoOp;
    }

    private static MutationVerification? NormalizeVerification(string? requestedStatus, string effectiveStatus, MutationVerification? verification)
    {
        if (verification != null)
            return effectiveStatus == FailedStatus ? verification : null;

        var normalizedRequestedStatus = requestedStatus?.Trim().ToLowerInvariant();
        return normalizedRequestedStatus switch
        {
            MutationOutcomeStatuses.VerificationLimited when effectiveStatus == FailedStatus => MutationVerification.Limited(
                new[] { "post_change_semantics" },
                "verification remained limited"),
            MutationOutcomeStatuses.VerificationFailed when effectiveStatus == FailedStatus => MutationVerification.Failed(
                new[] { "post_change_semantics" },
                "semantic verification failed"),
            _ => null
        };
    }

    private static bool IsSuccessStatus(string status)
        => status == MutationOutcomeStatuses.Success;

    private static bool KeepsObservableChanges(string status)
        => status is MutationOutcomeStatuses.Success
            or FailedStatus;

    private static string NormalizeBlockedStatus(string message)
    {
        var normalizedMessage = message.Trim().ToLowerInvariant();

        if (normalizedMessage.Contains("external usages", StringComparison.Ordinal))
            return MutationOutcomeStatuses.Blocked;

        if (normalizedMessage.Contains("active editor", StringComparison.Ordinal)
            || normalizedMessage.Contains("focused surface", StringComparison.Ordinal)
            || normalizedMessage.Contains("editor-backed", StringComparison.Ordinal)
            || normalizedMessage.Contains("editor backed", StringComparison.Ordinal))
        {
            return NeedsActiveEditorStatus;
        }

        if (normalizedMessage.Contains("preview", StringComparison.Ordinal)
            || normalizedMessage.Contains("conflict", StringComparison.Ordinal)
            || normalizedMessage.Contains("chooser", StringComparison.Ordinal)
            || normalizedMessage.Contains("interaction-required", StringComparison.Ordinal)
            || normalizedMessage.Contains("interaction required", StringComparison.Ordinal)
            || normalizedMessage.Contains("non-interactive", StringComparison.Ordinal)
            || normalizedMessage.Contains("dialog", StringComparison.Ordinal))
        {
            return ConflictStatus;
        }

        return UnsupportedContextStatus;
    }

    private static List<string> NormalizeAffectedFiles(IEnumerable<string>? affectedFiles)
        => affectedFiles?
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList()
           ?? new List<string>();

    private static IReadOnlyList<string> NormalizeStrings(IEnumerable<string>? values)
    {
        if (values == null)
            return Array.Empty<string>();

        return values
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .Distinct(StringComparer.Ordinal)
            .ToList();
    }

    private static bool ConfirmedBySemanticEvidence(string path, IReadOnlyCollection<string> confirmedPaths)
        => confirmedPaths.Count > 0 && confirmedPaths.Contains(path, StringComparer.OrdinalIgnoreCase);
}
