namespace CSharpProductionReadiness.FileRename;

public sealed class FileRenamePayload
{
    public static FileRenamePayload Create() => new();

    public string Name => "file-only";
}
