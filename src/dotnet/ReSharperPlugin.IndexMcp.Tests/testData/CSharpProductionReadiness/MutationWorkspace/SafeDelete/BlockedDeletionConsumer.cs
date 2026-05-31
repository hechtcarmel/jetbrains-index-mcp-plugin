namespace CSharpProductionReadiness.SafeDelete;

public sealed class BlockedDeletionConsumer
{
    public string Read()
    {
        return new BlockedDeletionTarget().Describe();
    }
}
