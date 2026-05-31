using JetBrains.Application.BuildScript.Application.Zones;
using JetBrains.ReSharper.TestFramework;

namespace ReSharperPlugin.IndexMcp.Tests;

/// <summary>
/// Test environment zone marker for IndexMcp backend tests.
/// </summary>
[ZoneMarker]
public class ZoneMarker : IRequire<PsiFeatureTestZone>
{
}
