param(
    [Parameter(Mandatory = $true)]
    [string] $PluginZip
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $PluginZip)) {
    throw "Plugin ZIP not found: $PluginZip"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $PluginZip))
try {
    $entries = $zip.Entries | ForEach-Object { $_.FullName }

    $requiredZipEntries = @(
        "jetbrains-index-mcp-plugin/dotnet/ReSharperPlugin.IndexMcp.dll",
        "jetbrains-index-mcp-plugin/dotnet/ReSharperPlugin.IndexMcp.pdb"
    )

    foreach ($entry in $requiredZipEntries) {
        if ($entries -notcontains $entry) {
            throw "Missing required Rider package entry: $entry"
        }
        Write-Host "PASS package entry $entry"
    }

    $pluginJar = $zip.Entries |
        Where-Object { $_.FullName -match "^jetbrains-index-mcp-plugin/lib/jetbrains-index-mcp-plugin-.*\.jar$" } |
        Select-Object -First 1
    if (-not $pluginJar) {
        throw "Missing plugin implementation JAR in $PluginZip"
    }

    $tempJar = Join-Path ([System.IO.Path]::GetTempPath()) ("index-mcp-plugin-" + [System.Guid]::NewGuid() + ".jar")
    try {
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($pluginJar, $tempJar)
        $jar = [System.IO.Compression.ZipFile]::OpenRead($tempJar)
        try {
            $jarEntries = $jar.Entries | ForEach-Object { $_.FullName }
            $requiredJarEntries = @(
                "com/jetbrains/rd/ide/model/IndexMcpModel.class",
                "com/jetbrains/rd/ide/model/IndexMcpModel_GeneratedKt.class",
                "com/jetbrains/rider/plugins/indexmcp/IndexMcpProtocolListener.class"
            )

            foreach ($entry in $requiredJarEntries) {
                if ($jarEntries -notcontains $entry) {
                    throw "Missing required Rider generated class in plugin JAR: $entry"
                }
                Write-Host "PASS jar entry $entry"
            }
        } finally {
            $jar.Dispose()
        }
    } finally {
        Remove-Item $tempJar -Force -ErrorAction SilentlyContinue
    }
} finally {
    $zip.Dispose()
}

Write-Host "All Rider package assertions passed."
