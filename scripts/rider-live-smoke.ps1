param(
    [Parameter(Mandatory = $true)]
    [string] $PluginZip,

    [string] $RiderExe = "$env:LOCALAPPDATA\Programs\Rider\bin\rider64.exe",
    [string] $PluginDir = "$env:APPDATA\JetBrains\Rider2026.1\plugins\jetbrains-index-mcp-plugin",
    [string] $SolutionPath = "$env:USERPROFILE\programming\Clipthrough\Clipthrough.slnx",
    [string] $ProjectPath = "$env:USERPROFILE\programming\Clipthrough",
    [string] $Endpoint = "http://127.0.0.1:29182/index-mcp/streamable-http",
    [int] $InitialDelaySeconds = 30,
    [int] $StartupWaitSeconds = 60
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "Run this script with PowerShell 7+ (`pwsh`) so MCP HTTP requests do not hang during Rider startup."
}

function Stop-Rider {
    Get-CimInstance Win32_Process |
        Where-Object { $_.ExecutablePath -eq $RiderExe -or $_.Name -eq "rider64.exe" } |
        ForEach-Object {
            Write-Host "Stopping Rider PID $($_.ProcessId)"
            Stop-Process -Id $_.ProcessId -Force
        }
}

function Install-Plugin {
    $pluginsRoot = Split-Path -Parent $PluginDir
    $backupRoot = Join-Path $pluginsRoot "jetbrains-index-mcp-plugin-backups"
    New-Item -ItemType Directory -Force -Path $pluginsRoot | Out-Null
    New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null

    if (Test-Path $PluginDir) {
        $backupPath = Join-Path $backupRoot ("jetbrains-index-mcp-plugin-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
        Move-Item $PluginDir $backupPath
        Write-Host "Backed up existing plugin to $backupPath"
    }

    Expand-Archive -Path $PluginZip -DestinationPath $pluginsRoot -Force
    Write-Host "Installed $PluginZip"
}

function Invoke-Mcp {
    param(
        [int] $Id,
        [string] $Method,
        [hashtable] $Params,
        [int] $TimeoutSec = 120
    )

    $body = @{
        jsonrpc = "2.0"
        id = $Id
        method = $Method
        params = $Params
    } | ConvertTo-Json -Depth 30 -Compress

    Invoke-RestMethod -Method Post -Uri $Endpoint -ContentType "application/json" -Body $body -TimeoutSec $TimeoutSec
}

function Invoke-Tool {
    param(
        [int] $Id,
        [string] $Name,
        [hashtable] $Arguments
    )

    Invoke-Mcp -Id $Id -Method "tools/call" -Params @{ name = $Name; arguments = $Arguments }
}

function Wait-McpInitialize {
    $deadline = (Get-Date).AddSeconds($StartupWaitSeconds)
    $lastError = $null

    while ((Get-Date) -lt $deadline) {
        try {
            return Invoke-Mcp -Id 1 -Method "initialize" -TimeoutSec 5 -Params @{
                protocolVersion = "2025-03-26"
                capabilities = @{}
                clientInfo = @{ name = "rider-live-smoke"; version = "1" }
            }
        } catch {
            $lastError = $_
            Start-Sleep -Seconds 3
        }
    }

    throw "MCP endpoint did not become ready within $StartupWaitSeconds seconds. Last error: $lastError"
}

function Get-ToolText {
    param([object] $Response)
    $Response.result.content[0].text
}

function Assert-ToolOk {
    param(
        [string] $Name,
        [object] $Response,
        [scriptblock] $Predicate
    )

    if ($Response.result.isError) {
        throw "$Name failed: $(Get-ToolText $Response)"
    }

    $text = Get-ToolText $Response
    $json = $text | ConvertFrom-Json
    if (-not (& $Predicate $json)) {
        throw "$Name returned unexpected payload: $text"
    }

    Write-Host "PASS $Name"
}

if (-not (Test-Path $PluginZip)) { throw "Plugin ZIP not found: $PluginZip" }
if (-not (Test-Path $RiderExe)) { throw "Rider executable not found: $RiderExe" }
if (-not (Test-Path $SolutionPath)) { throw "Solution not found: $SolutionPath" }

Stop-Rider
Start-Sleep -Seconds 6
Install-Plugin

Write-Host "Starting Rider with $SolutionPath"
Start-Process -FilePath $RiderExe -ArgumentList @($SolutionPath)
Start-Sleep -Seconds $InitialDelaySeconds

$initialize = Wait-McpInitialize
Write-Host "MCP version $($initialize.result.serverInfo.version)"

$indexStatus = Invoke-Tool -Id 2 -Name "ide_index_status" -Arguments @{ project_path = $ProjectPath }
Assert-ToolOk -Name "ide_index_status" -Response $indexStatus -Predicate {
    param($json) -not $json.isDumbMode -and -not $json.isIndexing
}

$findClass = Invoke-Tool -Id 3 -Name "ide_find_class" -Arguments @{
    project_path = $ProjectPath
    query = "MainWindow"
    language = "C#"
    matchMode = "substring"
    scope = "project_production_files"
    pageSize = 5
}
Assert-ToolOk -Name "ide_find_class production C#" -Response $findClass -Predicate {
    param($json) ($json.classes | Where-Object { $_.name -eq "MainWindow" }).Count -gt 0
}

$definition = Invoke-Tool -Id 4 -Name "ide_find_definition" -Arguments @{
    project_path = $ProjectPath
    language = "C#"
    symbol = "MainWindow"
}
Assert-ToolOk -Name "ide_find_definition symbol C#" -Response $definition -Predicate {
    param($json) $json.file -like "*MainWindow.axaml.cs" -and $json.symbolName -eq "MainWindow"
}

$references = Invoke-Tool -Id 5 -Name "ide_find_references" -Arguments @{
    project_path = $ProjectPath
    language = "C#"
    symbol = "MainWindow"
    pageSize = 5
}
Assert-ToolOk -Name "ide_find_references symbol C#" -Response $references -Predicate {
    param($json) $json.totalCount -gt 0
}

$implementations = Invoke-Tool -Id 6 -Name "ide_find_implementations" -Arguments @{
    project_path = $ProjectPath
    file = "Clipthrough/Services/Storage/IClipStoreService.cs"
    line = 8
    column = 18
    pageSize = 10
}
Assert-ToolOk -Name "ide_find_implementations interface C#" -Response $implementations -Predicate {
    param($json) ($json.implementations | Where-Object { $_.name -eq "ClipStoreService" }).Count -gt 0
}

$callHierarchy = Invoke-Tool -Id 7 -Name "ide_call_hierarchy" -Arguments @{
    project_path = $ProjectPath
    file = "Clipthrough/Views/MainWindow.axaml.cs"
    line = 89
    column = 18
    direction = "callers"
    maxDepth = 2
}
Assert-ToolOk -Name "ide_call_hierarchy callers C#" -Response $callHierarchy -Predicate {
    param($json) $json.calls.Count -gt 0
}

Write-Host "All Rider live smoke checks passed."
