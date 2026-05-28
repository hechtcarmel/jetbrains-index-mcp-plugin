using NUnit.Framework;

namespace ReSharperPlugin.IndexMcp.Tests;

[TestFixture]
public class RuntimeRenameTargetBindingTests
{
    [Test]
    public void Bind_PrefersTargetConstructor_WhenAvailable()
    {
        var target = new FakeTarget();

        var result = RuntimeRenameTargetBinding.Bind(typeof(ConstructorBoundProvider), target, "Renamed.cs");

        Assert.Multiple(() =>
        {
            Assert.That(result.IsSupported, Is.True);
            Assert.That(result.Kind, Is.EqualTo(RuntimeRenameTargetBindingKind.Constructor));
            Assert.That(result.Provider, Is.TypeOf<ConstructorBoundProvider>());
            Assert.That(((ConstructorBoundProvider)result.Provider!).RenameTarget, Is.SameAs(target));
            Assert.That(((ConstructorBoundProvider)result.Provider!).NewName, Is.EqualTo("Renamed.cs"));
        });
    }

    [Test]
    public void Bind_UsesWritableRenameTargetSetter_WhenAvailable()
    {
        var target = new FakeTarget();

        var result = RuntimeRenameTargetBinding.Bind(typeof(SetterBoundProvider), target, "Renamed.cs");

        Assert.Multiple(() =>
        {
            Assert.That(result.IsSupported, Is.True);
            Assert.That(result.Kind, Is.EqualTo(RuntimeRenameTargetBindingKind.PropertySetter));
            Assert.That(result.Provider, Is.TypeOf<SetterBoundProvider>());
            Assert.That(((SetterBoundProvider)result.Provider!).RenameTarget, Is.SameAs(target));
            Assert.That(((SetterBoundProvider)result.Provider!).NewName, Is.EqualTo("Renamed.cs"));
        });
    }

    [Test]
    public void Bind_AllowsContextOnlyWorkflow_WhenRenameTargetSetterIsMissing()
    {
        var target = new FakeTarget();

        var result = RuntimeRenameTargetBinding.Bind(typeof(GetOnlyRenameTargetProvider), target, "Renamed.cs");

        Assert.Multiple(() =>
        {
            Assert.That(result.IsSupported, Is.True);
            Assert.That(result.Kind, Is.EqualTo(RuntimeRenameTargetBindingKind.ContextOnly));
            Assert.That(result.Provider, Is.TypeOf<GetOnlyRenameTargetProvider>());
            Assert.That(((GetOnlyRenameTargetProvider)result.Provider!).NewName, Is.EqualTo("Renamed.cs"));
            Assert.That(result.FailureMessage, Is.Null);
        });
    }

    [Test]
    public void Bind_FailsClosed_WhenNoCompatibleProviderShapeExists()
    {
        var target = new FakeTarget();

        var result = RuntimeRenameTargetBinding.Bind(typeof(UnsupportedProvider), target, "Renamed.cs");

        Assert.Multiple(() =>
        {
            Assert.That(result.IsSupported, Is.False);
            Assert.That(result.Kind, Is.EqualTo(RuntimeRenameTargetBindingKind.Unsupported));
            Assert.That(result.Provider, Is.Null);
            Assert.That(result.FailureMessage, Does.Contain("fail-closed").And.Contain("constructor(target, newName)").And.Contain("context-only"));
        });
    }

    private sealed class FakeTarget;

    private sealed class ConstructorBoundProvider
    {
        public ConstructorBoundProvider(FakeTarget renameTarget, string newName)
        {
            RenameTarget = renameTarget;
            NewName = newName;
        }

        public FakeTarget RenameTarget { get; }
        public string NewName { get; }
    }

    private sealed class SetterBoundProvider
    {
        public SetterBoundProvider(string newName) => NewName = newName;

        public FakeTarget? RenameTarget { get; set; }
        public string NewName { get; }
    }

    private sealed class GetOnlyRenameTargetProvider
    {
        public GetOnlyRenameTargetProvider(string newName) => NewName = newName;

        public FakeTarget? RenameTarget => null;
        public string NewName { get; }
    }

    private sealed class UnsupportedProvider
    {
        public UnsupportedProvider(int value) => Value = value;

        public int Value { get; }
    }
}
