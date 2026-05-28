namespace CSharpProductionReadiness.Renames;

public sealed class TypeRenameConsumer
{
    public string Render(TypeRenameTarget target)
    {
        return target.Format();
    }
}
