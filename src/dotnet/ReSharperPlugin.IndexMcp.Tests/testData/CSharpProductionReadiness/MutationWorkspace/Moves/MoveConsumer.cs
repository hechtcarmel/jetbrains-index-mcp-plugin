using ModelDescriptions.Source;

namespace ModelDescriptions.Consumers;

public sealed class MoveConsumer
{
    public string Read() => new MoveTarget().Describe();
}
