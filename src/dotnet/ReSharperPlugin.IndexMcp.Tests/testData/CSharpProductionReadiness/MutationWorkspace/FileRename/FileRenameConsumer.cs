namespace CSharpProductionReadiness.FileRename;

public sealed class FileRenameConsumer
{
    public string Read(FileRenamePayload payload)
    {
        var recreated = FileRenamePayload.Create();
        return payload.Name + ":" + recreated.Name + ":" + nameof(FileRenamePayload);
    }
}
