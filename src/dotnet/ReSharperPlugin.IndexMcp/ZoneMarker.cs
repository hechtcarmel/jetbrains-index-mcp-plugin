using JetBrains.Application.BuildScript.Application.Zones;
using JetBrains.ReSharper.Feature.Services;
using JetBrains.ReSharper.Psi.CSharp;

namespace ReSharperPlugin.IndexMcp;

/// <summary>
/// Zone marker for the IndexMcp ReSharper plugin.
/// Requires C# language support and PSI features.
/// </summary>
[ZoneMarker]
public class ZoneMarker : IRequire<ILanguageCSharpZone>, IRequire<ICodeEditingZone>
{
}
