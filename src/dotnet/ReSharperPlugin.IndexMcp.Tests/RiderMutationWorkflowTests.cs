using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.Serialization;
using System.Text.Json;
using JetBrains.Lifetimes;
using JetBrains.Rider.Model.IndexMcp;
using NUnit.Framework;

namespace ReSharperPlugin.IndexMcp.Tests;

[TestFixture]
public class RiderMutationWorkflowTests
{
    private static readonly Type BackendAssemblyMarker = typeof(IndexMcpBackendHost);
    private static readonly string RepositoryRoot = FindRepositoryRoot();
    private static readonly string WorkspaceRoot = Path.Combine(RepositoryRoot,
        "src", "dotnet", "ReSharperPlugin.IndexMcp.Tests", "testData", "CSharpProductionReadiness", "MutationWorkspace");
    private static readonly Lazy<MutationFixtureCatalog> FixtureCatalog = new(LoadFixtureCatalog);

    [TestCaseSource(nameof(OutcomeScenarios))]
    public void MutationOutcomes_ReportBoundedStatusSemantics(MutationOutcomeScenario scenario)
    {
        var outcome = ReadOutcome(CreateNormalizedOutcome(scenario));

        Assert.Multiple(() =>
        {
            Assert.That(outcome.Status, Is.EqualTo(scenario.ExpectedStatus),
                "Mutation workflows must expose the canonical external status taxonomy instead of leaking legacy backend labels.");
            Assert.That(outcome.Success, Is.EqualTo(scenario.ExpectedSuccess),
                "Canonical non-success Rider mutation outcomes MUST NOT remain optimistic success=true responses.");
            Assert.That(outcome.ChangesCount, Is.EqualTo(scenario.ExpectedChangesCount),
                "Status semantics must agree with observable change counts.");
            Assert.That(outcome.AffectedFiles.Any(), Is.EqualTo(scenario.ExpectsAffectedFiles),
                "Mutation reporting must distinguish bounded no-op/blocked outcomes from applied edits.");
            Assert.That(outcome.Message, Is.Not.Null.And.Not.Empty);
            Assert.That(outcome.Message, Does.Contain(scenario.RequiredMessageKeyword).IgnoreCase,
                "Outcome messages must preserve bounded status evidence for smoke validation and agent use.");
        });

        if (scenario.ExpectedVerificationStatus == null)
        {
            Assert.That(outcome.Verification, Is.Null,
                "Only verification-bounded mutation outcomes should require verification summary evidence.");
            return;
        }

        Assert.That(outcome.Verification, Is.Not.Null,
            "Verification-bounded mutation outcomes must include a structured verification summary.");

        Assert.Multiple(() =>
        {
            Assert.That(outcome.Verification!.Status, Is.EqualTo(scenario.ExpectedVerificationStatus));
            Assert.That(outcome.Verification.Warnings, Has.Some.Contains(scenario.RequiredVerificationKeyword!).IgnoreCase,
                "Verification summaries must explain why a bounded status did not remain an unqualified success.");
        });
    }

    [TestCase("localRename", "local")]
    [TestCase("parameterRename", "parameter")]
    [TestCase("memberRename", "member")]
    [TestCase("typeRename", "type")]
    public void SymbolRenamePlan_UsesExactSymbolLane(string fixtureName, string expectedKind)
    {
        var fixture = GetCase(fixtureName);
        var plan = PlanSymbolRename(Path.Combine(WorkspaceRoot, fixture.File), fixture.Line!.Value, fixture.Column!.Value);

        Assert.Multiple(() =>
        {
            Assert.That(plan.OperationKind, Is.EqualTo("symbol"));
            Assert.That(plan.Status, Is.EqualTo("resolved"), plan.Message);
            Assert.That(plan.TargetKind, Is.EqualTo(expectedKind), plan.Message);
            Assert.That(plan.ResolvedName, Is.EqualTo(fixture.Symbol), plan.Message);
            Assert.That(plan.SourceTokenText, Is.EqualTo(fixture.Symbol),
                "Exact-target planning must preserve the declaration token text so frontend execution can reject widened Rider mutations.");
            Assert.That(plan.Message, Does.Contain("Resolved exact").And.Contain(expectedKind));
            Assert.That(NormalizePathSeparators(plan.OldPath), Does.EndWith(NormalizePathSeparators(fixture.File)));
            Assert.That(NormalizePathSeparators(plan.NewPath), Does.EndWith(NormalizePathSeparators(fixture.File)));
        });
    }

    [Test]
    public void SymbolRenamePlan_RejectsUnsafeWideningBeforeMutation()
    {
        var fixture = GetCase("memberRename");
        var plan = PlanSymbolRename(Path.Combine(WorkspaceRoot, fixture.File), fixture.Line!.Value, fixture.NonNameTokenColumn!.Value);

        Assert.Multiple(() =>
        {
            Assert.That(plan.OperationKind, Is.EqualTo("symbol"));
            Assert.That(plan.Status, Is.Not.EqualTo("resolved"));
            Assert.That(plan.TargetKind, Is.Not.EqualTo("type"));
            Assert.That(plan.Message, Does.Contain("widen").IgnoreCase.Or.Contain("exact").IgnoreCase);
        });
    }

    [Test]
    public void FileRenamePlan_UsesDistinctFileLaneWithoutTypeWidening()
    {
        var safeFixture = GetCase("fileRename");
        var safePlan = PlanFileRename(Path.Combine(WorkspaceRoot, safeFixture.File), safeFixture.NewName!);

        Assert.Multiple(() =>
        {
            Assert.That(safePlan.OperationKind, Is.EqualTo("file"));
            Assert.That(safePlan.TargetKind, Is.EqualTo("file"), safePlan.Message);
            Assert.That(safePlan.ResolvedName, Is.EqualTo(Path.GetFileName(safeFixture.File)), safePlan.Message);
            Assert.That(safePlan.NewPath, Does.EndWith(safeFixture.NewName));
        });

        if (string.Equals(safePlan.Status, "unsupported_context", StringComparison.OrdinalIgnoreCase)
            || string.Equals(safePlan.Status, "unsupported", StringComparison.OrdinalIgnoreCase))
        {
            Assert.Multiple(() =>
            {
                Assert.That(safePlan.Status, Is.EqualTo("unsupported_context"),
                    "File rename fail-closed outcomes must surface the canonical unsupported_context status.");
                Assert.That(safePlan.Message,
                    Does.Contain("fail-closed").IgnoreCase
                        .Or.Contain("workflow").IgnoreCase
                        .Or.Contain("symbol rename").IgnoreCase);
            });
        }
        else
        {
            Assert.That(safePlan.Status, Is.EqualTo("resolved"), safePlan.Message);
        }

        var sameBaseNameTypePath = Path.Combine(WorkspaceRoot, GetCase("typeRename").File);
        var sameBaseNameTypePlan = PlanFileRename(sameBaseNameTypePath, "RenamedTypeTarget.cs");

        Assert.Multiple(() =>
        {
            Assert.That(sameBaseNameTypePlan.OperationKind, Is.EqualTo("file"));
            Assert.That(sameBaseNameTypePlan.Status, Is.EqualTo("resolved"), sameBaseNameTypePlan.Message);
            Assert.That(sameBaseNameTypePlan.TargetKind, Is.EqualTo("file"));
            Assert.That(sameBaseNameTypePlan.ResolvedName, Is.EqualTo("TypeRenameTarget.cs"));
            Assert.That(sameBaseNameTypePlan.NewPath, Does.EndWith("RenamedTypeTarget.cs"));
        });
    }

    [Test]
    public void FileRenameExecution_SameBaseNameTypePreservesDeclaredTypeIdentityOrFailsClosed()
    {
        var fixture = GetCase("typeRename");
        using var workspace = CreateWorkspaceCopy();
        var sourcePath = Path.Combine(workspace.RootPath, fixture.File);
        var renamedPath = Path.Combine(Path.GetDirectoryName(sourcePath)!, "RenamedTypeTarget.cs");
        var consumerPath = Path.Combine(workspace.RootPath, "Renames", "TypeRenameConsumer.cs");
        var originalSourceText = File.ReadAllText(sourcePath);
        var originalConsumerText = File.ReadAllText(consumerPath);
        var outcome = ReadOutcome(ExecuteFileRename(sourcePath, "RenamedTypeTarget.cs"));

        if (string.Equals(outcome.Status, "unsupported_context", StringComparison.OrdinalIgnoreCase)
            || string.Equals(outcome.Status, "unsupported", StringComparison.OrdinalIgnoreCase))
        {
            Assert.Multiple(() =>
            {
                Assert.That(outcome.Status, Is.EqualTo("unsupported_context"));
                Assert.That(File.Exists(sourcePath), Is.True);
                Assert.That(File.Exists(renamedPath), Is.False);
                Assert.That(File.ReadAllText(sourcePath), Is.EqualTo(originalSourceText));
                Assert.That(File.ReadAllText(consumerPath), Is.EqualTo(originalConsumerText));
            });
            return;
        }

        var renamedFileText = File.ReadAllText(renamedPath);
        var consumerText = File.ReadAllText(consumerPath);

        Assert.Multiple(() =>
        {
            Assert.That(File.Exists(sourcePath), Is.False);
            Assert.That(File.Exists(renamedPath), Is.True);
            Assert.That(renamedFileText, Does.Contain("class TypeRenameTarget"));
            Assert.That(renamedFileText, Does.Not.Contain("class RenamedTypeTarget"));
            Assert.That(consumerText, Does.Contain("TypeRenameTarget"));
            Assert.That(consumerText, Does.Not.Contain("RenamedTypeTarget"));
        });
    }

    [Test]
    public void FileRenameExecution_UsesFilePathLaneAndPreservesDeclaredTypeOrFailsClosed()
    {
        var fixture = GetCase("fileRename");
        using var workspace = CreateWorkspaceCopy();
        var sourcePath = Path.Combine(workspace.RootPath, fixture.File);
        var renamedPath = Path.Combine(Path.GetDirectoryName(sourcePath)!, fixture.NewName!);
        var consumerPath = Path.Combine(workspace.RootPath, "FileRename", "FileRenameConsumer.cs");
        var originalSourceText = File.ReadAllText(sourcePath);
        var originalConsumerText = File.ReadAllText(consumerPath);
        var outcome = ReadOutcome(ExecuteFileRename(sourcePath, fixture.NewName!));
        var prohibitedTypeRename = fixture.ProhibitedTypeRename ?? Path.GetFileNameWithoutExtension(fixture.NewName!);

        if (string.Equals(outcome.Status, "unsupported_context", StringComparison.OrdinalIgnoreCase)
            || string.Equals(outcome.Status, "unsupported", StringComparison.OrdinalIgnoreCase))
        {
            Assert.Multiple(() =>
            {
                Assert.That(outcome.Status, Is.EqualTo("unsupported_context"),
                    "Fail-closed file rename rejection must expose unsupported_context instead of the legacy unsupported label.");
                Assert.That(File.Exists(sourcePath), Is.True,
                    "Fail-closed file rename rejection must leave the source file untouched.");
                Assert.That(File.Exists(renamedPath), Is.False,
                    "Fail-closed file rename rejection must not leave a partially renamed file behind.");
                Assert.That(File.ReadAllText(sourcePath), Is.EqualTo(originalSourceText));
                Assert.That(File.ReadAllText(consumerPath), Is.EqualTo(originalConsumerText));
                Assert.That(outcome.ChangesCount, Is.EqualTo(0));
                Assert.That(outcome.AffectedFiles, Is.Empty);
                Assert.That(outcome.Message,
                    Does.Contain("fail-closed").IgnoreCase
                        .Or.Contain("unsupported").IgnoreCase
                        .Or.Contain("workflow").IgnoreCase);
                Assert.That(outcome.Message,
                    Does.Not.Contain("caret").IgnoreCase
                        .And.Not.Contain("line").IgnoreCase
                        .And.Not.Contain("column").IgnoreCase
                        .And.Not.Contain("exact declaration name token").IgnoreCase);
            });
            return;
        }

        var renamedFileText = File.ReadAllText(renamedPath);
        var consumerText = File.ReadAllText(consumerPath);

        Assert.Multiple(() =>
        {
            Assert.That(outcome.Status,
                Is.EqualTo("success").Or.EqualTo("failed"),
                outcome.Message);
            Assert.That(File.Exists(sourcePath), Is.False,
                "Applied file rename must remove the original file path.");
            Assert.That(File.Exists(renamedPath), Is.True,
                "Applied file rename must create the requested destination file.");
            Assert.That(renamedFileText, Does.Contain($"class {fixture.PreserveTypeName!}"),
                "File-lane rename must preserve the declared type name instead of widening into a type rename.");
            Assert.That(renamedFileText, Does.Not.Contain($"class {prohibitedTypeRename}"),
                "Regression guard: file rename must not widen into renaming the declared type to the new file base name.");
            Assert.That(consumerText, Does.Contain(fixture.PreserveTypeName!),
                "Consumer code must keep referencing the original declared type after a file-scoped rename.");
            Assert.That(consumerText, Does.Not.Contain(prohibitedTypeRename),
                "Regression guard: consumer code must not be widened into the renamed file base name.");
            Assert.That(consumerText, Does.Contain(fixture.ConsumerSentinel!),
                "Consumer sentinel must remain tied to the original declared type when only the file path changes.");
        });

        var semanticProof = ReadOutcome(ConfirmSemanticFileMutationProof(
            renamedPath,
            Path.Combine(workspace.RootPath, "MutationWorkspace.csproj"),
            expectedNamespace: "CSharpProductionReadiness.FileRename",
            affectedFiles: outcome.AffectedFiles,
            referenceFiles: new[] { consumerPath },
            referenceTokens: new[] { fixture.PreserveTypeName!, fixture.ConsumerSentinel! }));

        AssertSemanticProofMatchesOutcome(outcome, semanticProof,
            "Diagnostics-only file rename verification MUST NOT report success without namespace/default-namespace, affected-file, and resolvable-reference evidence.");
    }

    [Test]
    public void SemanticMovePlan_UsesDistinctMoveLane()
    {
        var fixture = GetCase("semanticMove");
        var plan = PlanMove(Path.Combine(WorkspaceRoot, fixture.File), Path.Combine(WorkspaceRoot, fixture.Destination!));

        Assert.Multiple(() =>
        {
            Assert.That(plan.OperationKind, Is.EqualTo("move"));
            Assert.That(plan.TargetKind, Is.EqualTo("file"), plan.Message);
            Assert.That(NormalizePathSeparators(plan.OldPath), Does.EndWith(NormalizePathSeparators(fixture.File)));
            Assert.That(NormalizePathSeparators(plan.NewPath), Does.EndWith(NormalizePathSeparators($"{fixture.Destination}/MoveTarget.cs")));
        });

        if (string.Equals(plan.Status, "unsupported_context", StringComparison.OrdinalIgnoreCase)
            || string.Equals(plan.Status, "unsupported", StringComparison.OrdinalIgnoreCase))
        {
            Assert.Multiple(() =>
            {
                Assert.That(plan.Status, Is.EqualTo("unsupported_context"),
                    "Semantic move fail-closed outcomes must surface the canonical unsupported_context status.");
                Assert.That(plan.Message,
                    Does.Contain("fail-closed").IgnoreCase
                        .Or.Contain("workflow").IgnoreCase
                        .Or.Contain("manual rewrite").IgnoreCase);
            });
            return;
        }

        Assert.That(plan.Status, Is.EqualTo("resolved"), plan.Message);
    }

    [Test]
    public void SemanticMoveExecution_GuardsInstalledRuntimeDuplicateNamespaceSignatureOrFailsClosed()
    {
        var fixture = GetCase("semanticMove");
        using var workspace = CreateWorkspaceCopy();
        var sourcePath = Path.Combine(workspace.RootPath, fixture.File);
        var destinationPath = Path.Combine(workspace.RootPath, fixture.Destination!);
        var consumerPath = Path.Combine(workspace.RootPath, "Moves", "MoveConsumer.cs");
        var originalSourceText = File.ReadAllText(sourcePath);
        var originalConsumerText = File.ReadAllText(consumerPath);

        var outcome = ReadOutcome(ExecuteMove(sourcePath, destinationPath));
        var movedFilePath = Path.Combine(destinationPath, Path.GetFileName(sourcePath));

        if (string.Equals(outcome.Status, "unsupported_context", StringComparison.OrdinalIgnoreCase)
            || string.Equals(outcome.Status, "unsupported", StringComparison.OrdinalIgnoreCase))
        {
            Assert.Multiple(() =>
            {
                Assert.That(outcome.Status, Is.EqualTo("unsupported_context"),
                    "Fail-closed move rejection must expose unsupported_context instead of the legacy unsupported label.");
                Assert.That(File.Exists(sourcePath), Is.True,
                    "Fail-closed move rejection must leave the source file untouched.");
                Assert.That(File.Exists(movedFilePath), Is.False,
                    "Fail-closed move rejection must not leave a partially moved destination file behind.");
                Assert.That(File.ReadAllText(sourcePath), Is.EqualTo(originalSourceText));
                Assert.That(File.ReadAllText(consumerPath), Is.EqualTo(originalConsumerText));
                Assert.That(outcome.ChangesCount, Is.EqualTo(0));
                Assert.That(outcome.AffectedFiles, Is.Empty);
                Assert.That(outcome.Message,
                    Does.Contain("fail-closed").IgnoreCase
                        .Or.Contain("unsupported").IgnoreCase
                        .Or.Contain("workflow").IgnoreCase);
            });
            return;
        }

        var movedFileText = File.ReadAllText(movedFilePath);
        var consumerText = File.ReadAllText(consumerPath);
        var expectedConsumerUsing = $"using {fixture.ExpectedNamespaceAfterMove!};";
        var duplicateConsumerUsing = $"using {fixture.DuplicatedNamespaceAfterMove!};";

        Assert.Multiple(() =>
        {
            Assert.That(outcome.Status,
                Is.EqualTo("success").Or.EqualTo("failed"),
                outcome.Message);
            Assert.That(File.Exists(sourcePath), Is.False,
                "Semantic move must not leave the original file behind after applying filesystem changes.");
            Assert.That(File.Exists(movedFilePath), Is.True,
                "Semantic move must place the file in the requested destination when it applies changes.");
            Assert.That(movedFileText, Does.Contain(fixture.ExpectedNamespaceAfterMove!),
                "Moved file must update to the destination namespace when the backend applies a move.");
            Assert.That(movedFileText, Does.Not.Contain(fixture.DuplicatedNamespaceAfterMove!),
                "Regression guard: the moved file must not land with the installed-runtime duplicated namespace signature.");
            Assert.That(consumerText, Does.Contain(expectedConsumerUsing),
                "Consumer imports must update to the moved namespace when the backend applies a move.");
            Assert.That(consumerText, Does.Not.Contain(duplicateConsumerUsing),
                "Regression guard: consumer using directives must not collapse into the duplicated namespace signature.");
            Assert.That(CountOccurrences(consumerText, fixture.ReferenceSentinel ?? "MoveTarget"), Is.GreaterThan(0),
                "Regression guard: consumer references must not collapse to zero after a move mutation is applied.");
        });

        var semanticProof = ReadOutcome(ConfirmSemanticFileMutationProof(
            movedFilePath,
            Path.Combine(workspace.RootPath, "MutationWorkspace.csproj"),
            expectedNamespace: fixture.ExpectedNamespaceAfterMove,
            affectedFiles: outcome.AffectedFiles,
            referenceFiles: new[] { consumerPath },
            referenceTokens: new[] { expectedConsumerUsing, fixture.ReferenceSentinel ?? "MoveTarget" }));

        AssertSemanticProofMatchesOutcome(outcome, semanticProof,
            "Diagnostics-only move verification MUST NOT report success without destination namespace, affected-file, and resolvable-reference evidence.");
    }

    [Test]
    public void FileMutationSemanticProof_DowngradesDiagnosticsOnlyEvidence()
    {
        using var workspace = CreateWorkspaceCopy();
        var projectFile = Path.Combine(workspace.RootPath, "MutationWorkspace.csproj");
        var renamedPath = Path.Combine(workspace.RootPath, "FileRename", "RenamedFileOnly.cs");
        var sourcePath = Path.Combine(workspace.RootPath, "FileRename", "FileRenameCandidate.cs");
        File.Move(sourcePath, renamedPath);

        var outcome = ReadOutcome(ConfirmSemanticFileMutationProof(
            renamedPath,
            projectFile,
            expectedNamespace: "CSharpProductionReadiness.FileRename",
            affectedFiles: Array.Empty<string>(),
            referenceFiles: Array.Empty<string>(),
            referenceTokens: new[] { "FileRenamePayload" }));

        Assert.Multiple(() =>
        {
            Assert.That(outcome.Status, Is.EqualTo("failed"), outcome.Message);
            Assert.That(outcome.Verification, Is.Not.Null);
            Assert.That(outcome.Verification!.ChecksRun, Has.Some.EqualTo("closed_file_diagnostics"));
            Assert.That(outcome.Verification.ChecksRun, Has.Some.EqualTo("project_model_reresolution"));
            Assert.That(outcome.Verification.ChecksRun, Has.Some.EqualTo("psi_reresolution"));
            Assert.That(outcome.Verification.ChecksRun, Has.Some.EqualTo("namespace_default_namespace_confirmation"));
            Assert.That(outcome.Verification.Warnings, Has.Some.Contains("project model").IgnoreCase);
            Assert.That(outcome.Verification.Warnings, Has.Some.Contains("PSI").IgnoreCase);
            Assert.That(outcome.Verification.Warnings, Has.Some.Contains("affected-file").IgnoreCase);
            Assert.That(outcome.Verification.Warnings, Has.Some.Contains("resolvable post-mutation references").IgnoreCase);
        });
    }

    [Test]
    public void FileMutationSemanticProof_FailsWhenNamespaceConfirmationContradictsMutation()
    {
        using var workspace = CreateWorkspaceCopy();
        var projectFile = Path.Combine(workspace.RootPath, "MutationWorkspace.csproj");
        var movedFilePath = Path.Combine(workspace.RootPath, "Moves", "SmokeMove", "MoveTarget.cs");
        var sourcePath = Path.Combine(workspace.RootPath, "Moves", "Source", "MoveTarget.cs");
        var consumerPath = Path.Combine(workspace.RootPath, "Moves", "MoveConsumer.cs");

        Directory.CreateDirectory(Path.GetDirectoryName(movedFilePath)!);
        File.Move(sourcePath, movedFilePath);
        File.WriteAllText(movedFilePath, File.ReadAllText(movedFilePath).Replace("ModelDescriptions.Source", "ModelDescriptions.SmokeMove.SmokeMove", StringComparison.Ordinal));

        var outcome = ReadOutcome(ConfirmSemanticFileMutationProof(
            movedFilePath,
            projectFile,
            expectedNamespace: "ModelDescriptions.SmokeMove",
            affectedFiles: new[] { movedFilePath, consumerPath },
            referenceFiles: new[] { consumerPath },
            referenceTokens: new[] { "using ModelDescriptions.SmokeMove;", "MoveTarget" }));

        Assert.Multiple(() =>
        {
            Assert.That(outcome.Status, Is.EqualTo("failed"), outcome.Message);
            Assert.That(outcome.Verification, Is.Not.Null);
            Assert.That(outcome.Verification!.Warnings, Has.Some.Contains("namespace/default-namespace confirmation mismatch").IgnoreCase);
        });
    }

    [Test]
    public void FileMutationSemanticProof_RequiresNamespaceAffectedFilesAndResolvableReferencesForSuccess()
    {
        using var workspace = CreateWorkspaceCopy();
        var projectFile = Path.Combine(workspace.RootPath, "MutationWorkspace.csproj");
        var renamedPath = Path.Combine(workspace.RootPath, "FileRename", "RenamedFileOnly.cs");
        var sourcePath = Path.Combine(workspace.RootPath, "FileRename", "FileRenameCandidate.cs");
        var consumerPath = Path.Combine(workspace.RootPath, "FileRename", "FileRenameConsumer.cs");
        File.Move(sourcePath, renamedPath);
        var semanticEvidence = CreateSemanticEvidence(
            projectModelReResolved: true,
            psiReResolved: true,
            confirmedAffectedFiles: new[] { renamedPath, consumerPath },
            confirmedReferenceFiles: new[] { consumerPath });

        var outcome = ReadOutcome(ConfirmSemanticFileMutationProofWithEvidence(
            renamedPath,
            projectFile,
            expectedNamespace: "CSharpProductionReadiness.FileRename",
            affectedFiles: new[] { renamedPath, consumerPath },
            referenceFiles: new[] { consumerPath },
            referenceTokens: new[] { "FileRenamePayload", "nameof(FileRenamePayload)" },
            semanticEvidence));

        Assert.Multiple(() =>
        {
            Assert.That(outcome.Status, Is.EqualTo("success"), outcome.Message);
            Assert.That(outcome.Verification, Is.Null,
                "Semantic post-check success should only be emitted after namespace/default-namespace, affected-file, and reference proof is present.");
            Assert.That(outcome.AffectedFiles, Has.Member(renamedPath));
            Assert.That(outcome.AffectedFiles, Has.Member(consumerPath));
        });
    }

    [Test]
    public void SafeDeleteBlocked_ReturnsBlockingUsageEvidence()
    {
        var fixture = GetCase("safeDeleteBlocked");
        var outcome = ReadSafeDeleteOutcome(ExecuteSafeDelete(
            Path.Combine(WorkspaceRoot, fixture.File),
            fixture.Line!.Value,
            fixture.Column!.Value,
            fixture.Symbol!,
            force: false));

        Assert.Multiple(() =>
        {
            Assert.That(outcome.Status, Is.EqualTo("blocked"), outcome.Message);
            Assert.That(outcome.BlockedUsages, Is.Not.Empty,
                "Safe delete MUST return blocking usage evidence when external usages remain.");
            Assert.That(outcome.BlockedUsages[0].FilePath.Replace('\\', '/'), Does.EndWith("SafeDelete/BlockedDeletionConsumer.cs"));
            Assert.That(outcome.BlockedUsages[0].Context, Does.Contain("BlockedDeletionTarget"));
        });
    }

    [Test]
    public void SafeDeleteAllowed_DeletesTargetWhenNoUsagesRemain()
    {
        var fixture = GetCase("safeDeleteAllowed");
        using var workspace = CreateWorkspaceCopy();
        var targetPath = Path.Combine(workspace.RootPath, fixture.File);

        var outcome = ReadSafeDeleteOutcome(ExecuteSafeDelete(
            targetPath,
            fixture.Line!.Value,
            fixture.Column!.Value,
            fixture.Symbol!,
            force: false));

        Assert.Multiple(() =>
        {
            Assert.That(outcome.Status, Is.EqualTo("success"), outcome.Message);
            Assert.That(File.Exists(targetPath), Is.False, "Safe delete success must remove the deletable target file.");
            Assert.That(outcome.BlockedUsages, Is.Empty);
        });
    }

    private static IEnumerable<TestCaseData> OutcomeScenarios()
    {
        yield return new TestCaseData(new MutationOutcomeScenario(
                InputStatus: "success",
                MutationApplied: true,
                ExpectedStatus: "success",
                ExpectedSuccess: true,
                ExpectedChangesCount: 2,
                ExpectsAffectedFiles: true,
                Message: "Rename completed with verified semantic updates.",
                RequiredMessageKeyword: "verified"))
            .SetName("success_applied_edit_is_reported_as_success");

        yield return new TestCaseData(new MutationOutcomeScenario(
                InputStatus: "success",
                MutationApplied: true,
                ExpectedStatus: "no_op",
                ExpectedSuccess: false,
                ExpectedChangesCount: 0,
                ExpectsAffectedFiles: false,
                Message: "Rename resolved exactly but produced no-op output and zero rewritten files.",
                RequiredMessageKeyword: "no-op"))
            .SetName("zero_change_outcome_is_reported_as_no_op");

        yield return new TestCaseData(new MutationOutcomeScenario(
                InputStatus: "blocked",
                MutationApplied: false,
                ExpectedStatus: "needs_active_editor",
                ExpectedSuccess: false,
                ExpectedChangesCount: 0,
                ExpectsAffectedFiles: false,
                Message: "Rider symbol rename requires an active editor before the exact target can be mutated safely.",
                RequiredMessageKeyword: "active editor"))
            .SetName("active_editor_requirement_is_reported_as_needs_active_editor");

        yield return new TestCaseData(new MutationOutcomeScenario(
                InputStatus: "blocked",
                MutationApplied: false,
                ExpectedStatus: "conflict",
                ExpectedSuccess: false,
                ExpectedChangesCount: 0,
                ExpectsAffectedFiles: false,
                Message: "Rider symbol rename would require a preview/conflict dialog and the non-interactive policy blocked it.",
                RequiredMessageKeyword: "preview"))
            .SetName("interaction_required_block_is_reported_as_conflict");

        yield return new TestCaseData(new MutationOutcomeScenario(
                InputStatus: "blocked",
                MutationApplied: false,
                ExpectedStatus: "unsupported_context",
                ExpectedSuccess: false,
                ExpectedChangesCount: 0,
                ExpectsAffectedFiles: false,
                Message: "Rider symbol rename stayed fail-closed because the execution context is unsupported for bounded mutation.",
                RequiredMessageKeyword: "unsupported"))
            .SetName("generic_fail_closed_block_is_reported_as_unsupported_context");

        yield return new TestCaseData(new MutationOutcomeScenario(
                InputStatus: "unsupported",
                MutationApplied: false,
                ExpectedStatus: "unsupported_context",
                ExpectedSuccess: false,
                ExpectedChangesCount: 0,
                ExpectsAffectedFiles: false,
                Message: "Semantic move is unsupported for this target scope.",
                RequiredMessageKeyword: "unsupported"))
            .SetName("unsafe_capability_gap_is_reported_as_unsupported_context");

        yield return new TestCaseData(new MutationOutcomeScenario(
                InputStatus: "verification_limited",
                MutationApplied: true,
                ExpectedStatus: "failed",
                ExpectedSuccess: false,
                ExpectedChangesCount: 1,
                ExpectsAffectedFiles: true,
                Message: "Rename applied but verification remained limited to closed-file diagnostics.",
                RequiredMessageKeyword: "limited",
                InputVerificationStatus: "limited",
                ExpectedVerificationStatus: "limited",
                RequiredVerificationKeyword: "closed-file"))
            .SetName("bounded_verification_is_normalized_to_failed_without_false_success");

        yield return new TestCaseData(new MutationOutcomeScenario(
                InputStatus: "verification_failed",
                MutationApplied: true,
                ExpectedStatus: "failed",
                ExpectedSuccess: false,
                ExpectedChangesCount: 1,
                ExpectsAffectedFiles: true,
                Message: "Move applied edits but verification failed to confirm semantic safety.",
                RequiredMessageKeyword: "failed",
                InputVerificationStatus: "failed",
                ExpectedVerificationStatus: "failed",
                RequiredVerificationKeyword: "semantic"))
            .SetName("verification_failure_downgrades_to_failed");
    }

    private static object CreateNormalizedOutcome(MutationOutcomeScenario scenario)
    {
        var backendAssembly = BackendAssemblyMarker.Assembly;
        var affectedFiles = scenario.ExpectsAffectedFiles
            ? new List<string> { "C:/repo/src/FileA.cs", "C:/repo/src/FileB.cs" }
            : new List<string>();

        var verificationType = backendAssembly.GetType("ReSharperPlugin.IndexMcp.Mutations.MutationVerification");
        Assert.That(verificationType, Is.Not.Null,
            "Task 2.1/3.2 must provide a mutation verification result shape for bounded mutation outcomes.");

        var verification = scenario.InputVerificationStatus == null
            ? null
            : CreateVerification(backendAssembly, scenario.InputVerificationStatus, scenario.RequiredVerificationKeyword!);

        return InvokeStaticRequired(
            "ReSharperPlugin.IndexMcp.Mutations.MutationVerificationService",
            "CreateOutcome",
            new[]
            {
                typeof(bool),
                typeof(IEnumerable<string>),
                typeof(int),
                typeof(string),
                typeof(string),
                verificationType!
            },
            new object[]
            {
                scenario.MutationApplied,
                affectedFiles,
                scenario.ExpectedChangesCount,
                scenario.Message,
                scenario.InputStatus,
                verification
            });
    }

    private static object CreateVerification(Assembly backendAssembly, string status, string warningKeyword)
    {
        var verificationType = backendAssembly.GetType("ReSharperPlugin.IndexMcp.Mutations.MutationVerification");
        Assert.That(verificationType, Is.Not.Null,
            "Task 2.1/3.2 must provide a mutation verification result shape for bounded mutation outcomes.");

        var verification = FormatterServices.GetUninitializedObject(verificationType!);
        SetMemberValue(verificationType!, verification, true, status, "Status");
        SetMemberValue(verificationType!, verification, false, new List<string> { "target_exactness", "post_change_semantics" }, "ChecksRun");
        SetMemberValue(verificationType!, verification, false, new List<string> { $"warning: {warningKeyword} evidence only" }, "Warnings");
        return verification;
    }

    private static MutationFixtureCase GetCase(string fixtureName)
    {
        if (!FixtureCatalog.Value.Cases.TryGetValue(fixtureName, out var fixture))
            throw new AssertionException($"Fixture '{fixtureName}' was not found in fixture-index.json.");

        return fixture with { Name = fixtureName };
    }

    private static PlannedRename ReadPlan(object rawPlan)
    {
        var planType = rawPlan.GetType();
        var resolution = ReadObject(planType, rawPlan, true, "Resolution")
                         ?? throw new AssertionException("Rename plan is missing Resolution.");
        var resolutionType = resolution.GetType();

        return new PlannedRename(
            ReadString(planType, rawPlan, true, "OperationKind")!,
            ReadString(resolutionType, resolution, true, "Status")!,
            ReadString(resolutionType, resolution, false, "TargetKind"),
            ReadString(resolutionType, resolution, false, "ResolvedName"),
            ReadString(resolutionType, resolution, false, "SourceTokenText"),
            ReadString(resolutionType, resolution, false, "Message") ?? string.Empty,
            ReadString(planType, rawPlan, false, "OldPath"),
            ReadString(planType, rawPlan, false, "NewPath"));
    }

    private static PlannedRename PlanSymbolRename(string absoluteFilePath, int line, int column)
        => InvokePlanner("PlanExactSymbolRename", new object[] { absoluteFilePath, line, column });

    private static PlannedRename PlanFileRename(string absoluteFilePath, string newName)
        => InvokePlanner("PlanExactFileRename", new object[] { absoluteFilePath, newName });

    private static PlannedRename PlanMove(string absoluteFilePath, string destinationDirectory)
        => InvokePlannerType(
            "ReSharperPlugin.IndexMcp.Mutations.MoveMutationPlanner",
            "PlanSemanticMove",
            new object[] { absoluteFilePath, destinationDirectory });

    private static PlannedRename InvokePlanner(string methodName, object[] args)
        => InvokePlannerType(
            "ReSharperPlugin.IndexMcp.Mutations.RenameMutationPlanner",
            methodName,
            args);

    private static PlannedRename InvokePlannerType(string plannerTypeName, string methodName, object[] args)
    {
        var plannerType = BackendAssemblyMarker.Assembly.GetType(plannerTypeName);
        Assert.That(plannerType, Is.Not.Null,
            $"Planner type '{plannerTypeName}' was not found.");

        var method = plannerType!.GetMethod(methodName, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static);
        Assert.That(method, Is.Not.Null,
            $"Rename planner method '{methodName}' was not found.");

        var rawPlan = method!.Invoke(null, args);
        Assert.That(rawPlan, Is.Not.Null, $"Rename planner method '{methodName}' returned null.");
        return ReadPlan(rawPlan!);
    }

    private static object ExecuteMove(string absoluteFilePath, string destinationDirectory)
        => InvokeStaticRequired(
            "ReSharperPlugin.IndexMcp.Mutations.MoveMutationExecutor",
            "ExecuteSemanticMove",
            new object[] { absoluteFilePath, destinationDirectory });

    private static object ExecuteFileRename(string absoluteFilePath, string newName)
    {
        var host = FormatterServices.GetUninitializedObject(BackendAssemblyMarker);
        var method = BackendAssemblyMarker.GetMethod("HandleRenameFile", BindingFlags.Instance | BindingFlags.NonPublic);
        Assert.That(method, Is.Not.Null, "IndexMcpBackendHost.HandleRenameFile was not found.");

        var task = method!.Invoke(host, new object[] { Lifetime.Eternal, new RdRenameFileRequest(absoluteFilePath, newName) })
                   as Task<RdRenameFileResult?>;
        Assert.That(task, Is.Not.Null, "HandleRenameFile must return Task<RdRenameFileResult?>.");

        return task!.GetAwaiter().GetResult()
               ?? throw new AssertionException("HandleRenameFile returned null.");
    }

    private static object ConfirmSemanticFileMutationProof(
        string primaryFilePath,
        string projectFilePath,
        string? expectedNamespace,
        IEnumerable<string> affectedFiles,
        IEnumerable<string> referenceFiles,
        IEnumerable<string> referenceTokens)
    {
        return InvokeStaticRequired(
            "ReSharperPlugin.IndexMcp.Mutations.MutationVerificationService",
            "ConfirmSemanticFileMutationProof",
            new object[]
            {
                primaryFilePath,
                projectFilePath,
                expectedNamespace,
                affectedFiles.ToArray(),
                referenceFiles.ToArray(),
                referenceTokens.ToArray(),
                "Semantic file mutation proof confirmed after bounded post-check."
            });
    }

    private static object ConfirmSemanticFileMutationProofWithEvidence(
        string primaryFilePath,
        string projectFilePath,
        string? expectedNamespace,
        IEnumerable<string> affectedFiles,
        IEnumerable<string> referenceFiles,
        IEnumerable<string> referenceTokens,
        object semanticEvidence)
    {
        return InvokeStaticRequired(
            "ReSharperPlugin.IndexMcp.Mutations.MutationVerificationService",
            "ConfirmSemanticFileMutationProofWithEvidence",
            new[]
            {
                typeof(string),
                typeof(string),
                typeof(string),
                typeof(IEnumerable<string>),
                typeof(IEnumerable<string>),
                typeof(IEnumerable<string>),
                semanticEvidence.GetType(),
                typeof(string)
            },
            new object[]
            {
                primaryFilePath,
                projectFilePath,
                expectedNamespace,
                affectedFiles.ToArray(),
                referenceFiles.ToArray(),
                referenceTokens.ToArray(),
                semanticEvidence,
                "Semantic file mutation proof confirmed after bounded post-check."
            });
    }

    private static object ExecuteSafeDelete(string absoluteFilePath, int line, int column, string symbol, bool force)
        => InvokeStaticRequired(
            "ReSharperPlugin.IndexMcp.Mutations.SafeDeleteMutationExecutor",
            "ExecuteSafeDelete",
            new object[] { absoluteFilePath, line, column, symbol, force });

    private static object InvokeStaticRequired(string typeName, string methodName, object[] args)
        => InvokeStaticRequired(typeName, methodName, parameterTypes: null, args);

    private static object InvokeStaticRequired(string typeName, string methodName, Type[]? parameterTypes, object[] args)
    {
        var type = BackendAssemblyMarker.Assembly.GetType(typeName);
        Assert.That(type, Is.Not.Null, $"Type '{typeName}' was not found.");

        var method = parameterTypes == null
            ? type!.GetMethod(methodName, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static)
            : type!.GetMethod(methodName, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static, null, parameterTypes, null);
        Assert.That(method, Is.Not.Null, $"Method '{typeName}.{methodName}' was not found.");

        var result = method!.Invoke(null, args);
        Assert.That(result, Is.Not.Null, $"Method '{typeName}.{methodName}' returned null.");
        return result!;
    }

    private static object CreateSemanticEvidence(
        bool projectModelReResolved,
        bool psiReResolved,
        IEnumerable<string> confirmedAffectedFiles,
        IEnumerable<string> confirmedReferenceFiles)
    {
        return InvokeStaticRequired(
            "ReSharperPlugin.IndexMcp.Mutations.MutationSemanticEvidence",
            "Create",
            new[]
            {
                typeof(bool),
                typeof(bool),
                typeof(IEnumerable<string>),
                typeof(IEnumerable<string>)
            },
            new object[]
            {
                projectModelReResolved,
                psiReResolved,
                confirmedAffectedFiles.ToArray(),
                confirmedReferenceFiles.ToArray()
            });
    }

    private static MutationOutcome ReadOutcome(object rawOutcome)
    {
        var type = rawOutcome.GetType();
        var success = ReadBool(type, rawOutcome, true, "Success");
        var status = ReadString(type, rawOutcome, true, "Status");
        var message = ReadString(type, rawOutcome, true, "Message");
        var changesCount = ReadInt(type, rawOutcome, true, "ChangesCount");
        var affectedFiles = ReadStringList(type, rawOutcome, false, "AffectedFiles");
        var verification = ReadVerification(type, rawOutcome);

        return new MutationOutcome(success, status!, changesCount, affectedFiles, message!, verification);
    }

    private static MutationVerificationSummary? ReadVerification(Type outcomeType, object outcome)
    {
        var verificationObject = ReadObject(outcomeType, outcome, false, "Verification");
        if (verificationObject == null)
            return null;

        var verificationType = verificationObject.GetType();
        return new MutationVerificationSummary(
            ReadString(verificationType, verificationObject, true, "Status")!,
            ReadStringList(verificationType, verificationObject, false, "ChecksRun"),
            ReadStringList(verificationType, verificationObject, false, "Warnings"));
    }

    private static SafeDeleteOutcome ReadSafeDeleteOutcome(object rawOutcome)
    {
        var type = rawOutcome.GetType();
        return new SafeDeleteOutcome(
            ReadString(type, rawOutcome, true, "Status")!,
            ReadInt(type, rawOutcome, true, "ChangesCount"),
            ReadStringList(type, rawOutcome, false, "AffectedFiles"),
            ReadString(type, rawOutcome, true, "Message")!,
            ReadBlockedUsages(type, rawOutcome),
            ReadVerification(type, rawOutcome));
    }

    private static IReadOnlyList<BlockedUsageSummary> ReadBlockedUsages(Type outcomeType, object outcome)
    {
        var usages = ReadMemberValue(outcomeType, outcome, false, "BlockedUsages");
        if (usages is not IEnumerable enumerable)
            return Array.Empty<BlockedUsageSummary>();

        return enumerable.Cast<object>()
            .Select(item =>
            {
                var itemType = item.GetType();
                return new BlockedUsageSummary(
                    ReadString(itemType, item, true, "FilePath")!,
                    ReadInt(itemType, item, true, "Line"),
                    ReadInt(itemType, item, true, "Column"),
                    ReadString(itemType, item, true, "Context")!,
                    ReadString(itemType, item, true, "Kind")!);
            })
            .ToArray();
    }

    private static string? ReadString(Type type, object instance, bool required, params string[] memberNames)
        => ReadMemberValue(type, instance, required, memberNames)?.ToString();

    private static int ReadInt(Type type, object instance, bool required, params string[] memberNames)
    {
        var value = ReadMemberValue(type, instance, required, memberNames);
        return value switch
        {
            int typed => typed,
            null when !required => 0,
            null => throw new AssertionException($"Missing required integer member on '{type.FullName}'."),
            _ => Convert.ToInt32(value)
        };
    }

    private static bool ReadBool(Type type, object instance, bool required, params string[] memberNames)
    {
        var value = ReadMemberValue(type, instance, required, memberNames);
        return value switch
        {
            bool typed => typed,
            null when !required => false,
            null => throw new AssertionException($"Missing required boolean member on '{type.FullName}'."),
            _ => Convert.ToBoolean(value)
        };
    }

    private static IReadOnlyList<string> ReadStringList(Type type, object instance, bool required, params string[] memberNames)
    {
        var value = ReadMemberValue(type, instance, required, memberNames);
        if (value == null)
            return Array.Empty<string>();

        if (value is IEnumerable<string> typed)
            return typed.ToArray();

        if (value is IEnumerable enumerable)
            return enumerable.Cast<object?>().Select(item => item?.ToString() ?? string.Empty).ToArray();

        throw new AssertionException($"Member on '{type.FullName}' is not a string collection.");
    }

    private static object? ReadObject(Type type, object instance, bool required, params string[] memberNames)
        => ReadMemberValue(type, instance, required, memberNames);

    private static object? ReadMemberValue(Type type, object instance, bool required, params string[] memberNames)
    {
        foreach (var memberName in memberNames)
        {
            var property = type.GetProperty(memberName, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
            if (property != null)
                return property.GetValue(instance);

            var field = type.GetField(memberName, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)
                        ?? type.GetField($"<{memberName}>k__BackingField", BindingFlags.Instance | BindingFlags.NonPublic);
            if (field != null)
                return field.GetValue(instance);
        }

        if (required)
        {
            throw new AssertionException(
                $"Result type '{type.FullName}' is missing one of the required members: {string.Join(", ", memberNames)}.");
        }

        return null;
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

    private static string NormalizePathSeparators(string? path)
        => (path ?? string.Empty).Replace('\\', '/');

    private static int CountOccurrences(string content, string token)
    {
        if (string.IsNullOrEmpty(content) || string.IsNullOrEmpty(token))
            return 0;

        var count = 0;
        var index = 0;
        while ((index = content.IndexOf(token, index, StringComparison.Ordinal)) >= 0)
        {
            count++;
            index += token.Length;
        }

        return count;
    }

    private static void AssertSemanticProofMatchesOutcome(MutationOutcome outcome, MutationOutcome semanticProof, string failureMessage)
    {
        var proofIsSuccess = string.Equals(semanticProof.Status, "success", StringComparison.OrdinalIgnoreCase);
        var outcomeClaimsSuccess = string.Equals(outcome.Status, "success", StringComparison.OrdinalIgnoreCase);

        if (proofIsSuccess)
        {
            Assert.That(outcomeClaimsSuccess || string.Equals(outcome.Status, "failed", StringComparison.OrdinalIgnoreCase),
                Is.True,
                "When semantic proof succeeds, the mutation outcome may remain bounded but must not contradict the confirmed evidence.");
            return;
        }

        Assert.That(outcomeClaimsSuccess, Is.False, failureMessage);
        Assert.That(outcome.Status,
            Is.EqualTo("failed").Or.EqualTo("unsupported_context"),
            failureMessage);
    }

    private static TemporaryWorkspace CreateWorkspaceCopy()
    {
        var tempRoot = Path.Combine(Path.GetTempPath(), "IndexMcpMutationTests", Guid.NewGuid().ToString("N"));
        CopyDirectory(WorkspaceRoot, tempRoot);
        return new TemporaryWorkspace(tempRoot);
    }

    private static void CopyDirectory(string sourceDirectory, string destinationDirectory)
    {
        Directory.CreateDirectory(destinationDirectory);

        foreach (var directory in Directory.GetDirectories(sourceDirectory, "*", SearchOption.AllDirectories))
            Directory.CreateDirectory(directory.Replace(sourceDirectory, destinationDirectory));

        foreach (var file in Directory.GetFiles(sourceDirectory, "*", SearchOption.AllDirectories))
            File.Copy(file, file.Replace(sourceDirectory, destinationDirectory), overwrite: true);
    }

    private static void SetMemberValue(Type type, object instance, bool required, object? value, params string[] memberNames)
    {
        foreach (var memberName in memberNames)
        {
            var property = type.GetProperty(memberName, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
            if (property?.SetMethod != null)
            {
                property.SetValue(instance, value);
                return;
            }

            var field = type.GetField(memberName, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)
                        ?? type.GetField($"<{memberName}>k__BackingField", BindingFlags.Instance | BindingFlags.NonPublic);
            if (field != null)
            {
                field.SetValue(instance, value);
                return;
            }
        }

        if (required)
        {
            throw new AssertionException(
                $"Result type '{type.FullName}' is missing one of the required writable members: {string.Join(", ", memberNames)}.");
        }
    }

    public sealed record MutationOutcomeScenario(
        string InputStatus,
        bool MutationApplied,
        string ExpectedStatus,
        bool ExpectedSuccess,
        int ExpectedChangesCount,
        bool ExpectsAffectedFiles,
        string Message,
        string RequiredMessageKeyword,
        string? InputVerificationStatus = null,
        string? ExpectedVerificationStatus = null,
        string? RequiredVerificationKeyword = null);

    private sealed record MutationOutcome(
        bool Success,
        string Status,
        int ChangesCount,
        IReadOnlyList<string> AffectedFiles,
        string Message,
        MutationVerificationSummary? Verification);

    private sealed record MutationVerificationSummary(
        string Status,
        IReadOnlyList<string> ChecksRun,
        IReadOnlyList<string> Warnings);

    private sealed record SafeDeleteOutcome(
        string Status,
        int ChangesCount,
        IReadOnlyList<string> AffectedFiles,
        string Message,
        IReadOnlyList<BlockedUsageSummary> BlockedUsages,
        MutationVerificationSummary? Verification);

    private sealed record BlockedUsageSummary(
        string FilePath,
        int Line,
        int Column,
        string Context,
        string Kind);

    private sealed record PlannedRename(
        string OperationKind,
        string Status,
        string? TargetKind,
        string? ResolvedName,
        string? SourceTokenText,
        string Message,
        string? OldPath,
        string? NewPath);

    private sealed record MutationFixtureCatalog(Dictionary<string, MutationFixtureCase> Cases);

    private sealed record MutationFixtureCase(
        string File,
        string? Kind,
        int? Line,
        int? Column,
        string? Symbol,
        string? NewName,
        string? Destination = null,
        string? ExpectedNamespaceAfterMove = null,
        string? DuplicatedNamespaceAfterMove = null,
        string? ReferenceSentinel = null,
        string? PreserveTypeName = null,
        string? ProhibitedTypeRename = null,
        string? ConsumerSentinel = null,
        int? WhitespaceBeforeColumn = null,
        int? NonNameTokenColumn = null)
    {
        public string Name { get; init; } = string.Empty;
    }

    private sealed class TemporaryWorkspace(string rootPath) : IDisposable
    {
        public string RootPath { get; } = rootPath;

        public void Dispose()
        {
            if (Directory.Exists(RootPath))
                Directory.Delete(RootPath, recursive: true);
        }
    }
}
