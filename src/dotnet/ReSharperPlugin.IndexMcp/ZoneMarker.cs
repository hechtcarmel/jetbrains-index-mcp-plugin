using JetBrains.Application.BuildScript.Application.Zones;
using JetBrains.Application.Environment;
using JetBrains.ReSharper.Feature.Services;
using JetBrains.ReSharper.Psi.CSharp;

namespace ReSharperPlugin.IndexMcp;

/// <summary>
/// Local F# capability zone. The actual Rider F# activation contract is IFSharpPluginZone,
/// but that type lives in the optional F# plugin assemblies rather than the base SDK.
/// This local zone lets the backend isolate F# activation without gating C#-only loading.
/// </summary>
[ZoneDefinition]
public interface IIndexMcpFSharpZone : IZone
{
}

/// <summary>
/// Activates the local F# capability zone only when the Rider F# plugin zone type is present.
/// This keeps the optional F# dependency localized to this file.
/// </summary>
[ZoneActivator]
public class IndexMcpFSharpZoneActivator : IActivate<IIndexMcpFSharpZone>
{
    private const string FSharpPluginZoneTypeName =
        "JetBrains.ReSharper.Plugins.FSharp.IFSharpPluginZone, JetBrains.ReSharper.Plugins.FSharp.Common";

    public bool ActivatorEnabled()
    {
        return Type.GetType(FSharpPluginZoneTypeName, throwOnError: false) != null;
    }
}

/// <summary>
/// Zone marker for the IndexMcp ReSharper plugin.
/// Requires C# language support and PSI features.
/// </summary>
[ZoneMarker]
public class ZoneMarker : IRequire<ILanguageCSharpZone>, IRequire<ICodeEditingZone>
{
}
