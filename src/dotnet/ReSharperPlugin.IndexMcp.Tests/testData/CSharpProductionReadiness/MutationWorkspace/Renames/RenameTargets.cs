namespace CSharpProductionReadiness.Renames;

public sealed class RenameTargets
{
    private int _counter = 1;

    public int ComputeTotal(int increment)
    {
        var total = _counter + increment;
        return total + increment;
    }
}
