param(
    [Parameter(Mandatory = $true)]
    [string] $PluginZip,

    [string] $RiderExe = "$env:LOCALAPPDATA\Programs\Rider\bin\rider64.exe",
    [string] $PluginDir = "$env:APPDATA\JetBrains\Rider2026.1\plugins\jetbrains-index-mcp-plugin",
    [string] $SolutionPath = "$env:USERPROFILE\programming\Clipthrough\Clipthrough.slnx",
    [string] $ProjectPath = "$env:USERPROFILE\programming\Clipthrough",
    [string] $Endpoint = "http://127.0.0.1:29182/index-mcp/streamable-http",
    [int] $InitialDelaySeconds = 30,
    [int] $StartupWaitSeconds = 60,
    [switch] $FullMatrix,
    [switch] $TestRename,
    [string] $ExpectedVersion
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

function Assert-ToolError {
    param(
        [string] $Name,
        [object] $Response,
        [scriptblock] $Predicate
    )

    if (-not $Response.result.isError) {
        throw "$Name unexpectedly succeeded: $(Get-ToolText $Response)"
    }

    $text = Get-ToolText $Response
    if (-not (& $Predicate $text)) {
        throw "$Name returned unexpected error: $text"
    }

    Write-Host "PASS $Name"
}

function Get-ExpectedPluginVersion {
    # Read pluginVersion from gradle.properties next to this script (../gradle.properties).
    # Lets the smoke catch SERVER_VERSION drift like the 4.18.0 / 4.20.0 mismatch from May 2026.
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $gradleProps = Join-Path $repoRoot "gradle.properties"
    if (-not (Test-Path $gradleProps)) {
        return $null
    }
    $line = Select-String -Path $gradleProps -Pattern '^\s*pluginVersion\s*=\s*(.+?)\s*$' | Select-Object -First 1
    if (-not $line) { return $null }
    return $line.Matches[0].Groups[1].Value
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
$reportedVersion = $initialize.result.serverInfo.version
Write-Host "MCP version $reportedVersion"

$expectedVersion = if ($ExpectedVersion) { $ExpectedVersion } else { Get-ExpectedPluginVersion }
if ($expectedVersion) {
    if ($reportedVersion -ne $expectedVersion) {
        throw "MCP version mismatch: server reported '$reportedVersion' but expected '$expectedVersion'. Either the install pipeline loaded a stale build or McpConstants.getServerVersion() / SERVER_VERSION_FALLBACK is out of sync with gradle.properties:pluginVersion."
    }
    Write-Host "PASS version matches expected $expectedVersion"
} else {
    Write-Warning "Expected plugin version could not be determined (no -ExpectedVersion and no readable gradle.properties); skipping version assertion."
}

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
    symbol = "Clipthrough.Views.MainWindow#TryConnectClipListScrollViewer"
}
Assert-ToolOk -Name "ide_find_definition symbol C# member" -Response $definition -Predicate {
    param($json) $json.file -like "*MainWindow.axaml.cs" -and $json.symbolName -eq "TryConnectClipListScrollViewer"
}

$references = Invoke-Tool -Id 5 -Name "ide_find_references" -Arguments @{
    project_path = $ProjectPath
    language = "C#"
    symbol = "Clipthrough.Views.MainWindow#TryConnectClipListScrollViewer"
    pageSize = 5
}
Assert-ToolOk -Name "ide_find_references symbol C# member" -Response $references -Predicate {
    param($json) $json.totalCount -ge 0
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

# Universal, non-mutating tools that should be in every smoke run regardless of -FullMatrix.
# These were absent from the May 2026 smoke and caused gaps to surface only via manual
# integration testing.

$syncFiles = Invoke-Tool -Id 50 -Name "ide_sync_files" -Arguments @{
    project_path = $ProjectPath
}
Assert-ToolOk -Name "ide_sync_files whole project" -Response $syncFiles -Predicate {
    param($json) $json.syncedAll -eq $true
}

$findFile = Invoke-Tool -Id 51 -Name "ide_find_file" -Arguments @{
    project_path = $ProjectPath
    query = "MainWindow.axaml.cs"
    pageSize = 5
}
Assert-ToolOk -Name "ide_find_file basic" -Response $findFile -Predicate {
    param($json) ($json.files | Where-Object { $_.name -eq "MainWindow.axaml.cs" }).Count -gt 0
}

$searchTextPage1 = Invoke-Tool -Id 52 -Name "ide_search_text" -Arguments @{
    project_path = $ProjectPath
    query = "MainWindow"
    pageSize = 5
}
Assert-ToolOk -Name "ide_search_text page 1" -Response $searchTextPage1 -Predicate {
    param($json) $json.matches.Count -gt 0 -and $json.totalCount -gt 0
}

$searchTextPage1Json = (Get-ToolText $searchTextPage1) | ConvertFrom-Json
if ($searchTextPage1Json.nextCursor) {
    $searchTextPage2 = Invoke-Tool -Id 53 -Name "ide_search_text" -Arguments @{
        project_path = $ProjectPath
        cursor = $searchTextPage1Json.nextCursor
    }
    # The May 2026 report flagged page 2 returning stale:true without edits as a quirk.
    # Assert stale stays false here so any regression to a false-positive stale flag fails fast.
    Assert-ToolOk -Name "ide_search_text page 2 via cursor" -Response $searchTextPage2 -Predicate {
        param($json) $json.matches.Count -gt 0 -and -not $json.stale
    }
} else {
    Write-Host "SKIP ide_search_text page 2: only one page of results."
}

$diagnostics = Invoke-Tool -Id 54 -Name "ide_diagnostics" -Arguments @{
    project_path = $ProjectPath
    file = "Clipthrough/Views/MainWindow.axaml.cs"
    severity = "errors"
}
Assert-ToolOk -Name "ide_diagnostics file-level no errors" -Response $diagnostics -Predicate {
    param($json) $json.problemCount -ne $null -and $json.problemCount -eq 0
}

# Previously known-regression block, now flipped to success assertions after the
# v4.20.1 backend fixes (bare-class / dotted-property symbol resolution, sanitized
# find_references failures, widened rename affectedFiles).

# Reg 1 (fixed in 4.20.1): bare class symbol now resolves through the short-name
# fallback in ResolveContainerCandidatesFromScopeWithFallback.
$regBareClassDef = Invoke-Tool -Id 70 -Name "ide_find_definition" -Arguments @{
    project_path = $ProjectPath
    language = "C#"
    symbol = "MainWindowViewModel"
}
Assert-ToolOk -Name "ide_find_definition bare class symbol" -Response $regBareClassDef -Predicate {
    param($json) $json.locations -ne $null -and @($json.locations).Count -ge 1
}

# Reg 2 (fixed in 4.20.1): dotted Class.Property form is split on the last '.' and
# re-resolved as a member of the LHS type via TrySplitDottedSymbolAsMember.
$regDottedPropDef = Invoke-Tool -Id 71 -Name "ide_find_definition" -Arguments @{
    project_path = $ProjectPath
    language = "C#"
    symbol = "Clipthrough.ViewModels.MainWindowViewModel.IsBusy"
}
Assert-ToolOk -Name "ide_find_definition Class.Prop dotted symbol" -Response $regDottedPropDef -Predicate {
    param($json) $json.locations -ne $null -and @($json.locations).Count -ge 1
}

# Reg 3 (fixed in 4.20.1): unresolved find_references targets now return a graceful
# RdFindReferencesResult with an empty references list and a sanitized message; the
# Kotlin handler surfaces it as a normal tool error. No backend file path or RdFault
# may appear in the response text.
$regBareClassRefs = Invoke-Tool -Id 72 -Name "ide_find_references" -Arguments @{
    project_path = $ProjectPath
    language = "C#"
    symbol = "DoesNotExistAnywhereInClipthrough"
    pageSize = 5
}
$regBareClassRefsText = Get-ToolText $regBareClassRefs
if ($regBareClassRefsText -match "IndexMcpBackendHost\.cs" -or $regBareClassRefsText -match "RdFault") {
    throw "REGRESSION ide_find_references symbol error still leaks backend RdFault or source path: $regBareClassRefsText"
}
if (-not $regBareClassRefs.result.isError) {
    throw "ide_find_references unresolved symbol: expected tool error response, got success: $regBareClassRefsText"
}
Write-Host "PASS ide_find_references unresolved symbol sanitized"

if ($FullMatrix) {
    $findClassExact = Invoke-Tool -Id 8 -Name "ide_find_class" -Arguments @{
        project_path = $ProjectPath
        query = "MainWindow"
        language = "C#"
        matchMode = "exact"
        scope = "project_production_files"
        pageSize = 5
    }
    Assert-ToolOk -Name "ide_find_class exact C#" -Response $findClassExact -Predicate {
        param($json) ($json.classes | Where-Object { $_.name -eq "MainWindow" }).Count -eq 1
    }

    $findClassPrefix = Invoke-Tool -Id 9 -Name "ide_find_class" -Arguments @{
        project_path = $ProjectPath
        query = "MainWindow"
        language = "C#"
        matchMode = "prefix"
        scope = "project_production_files"
        pageSize = 5
    }
    Assert-ToolOk -Name "ide_find_class prefix C#" -Response $findClassPrefix -Predicate {
        param($json) ($json.classes | Where-Object { $_.name -eq "MainWindowViewModel" }).Count -gt 0
    }

    $findClassCamelCase = Invoke-Tool -Id 10 -Name "ide_find_class" -Arguments @{
        project_path = $ProjectPath
        query = "MWVM"
        language = "C#"
        matchMode = "substring"
        scope = "project_production_files"
        pageSize = 5
    }
    Assert-ToolOk -Name "ide_find_class camelCase C#" -Response $findClassCamelCase -Predicate {
        param($json) ($json.classes | Where-Object { $_.name -eq "MainWindowViewModel" }).Count -gt 0
    }

    $findClassWildcard = Invoke-Tool -Id 11 -Name "ide_find_class" -Arguments @{
        project_path = $ProjectPath
        query = "*Window"
        language = "C#"
        matchMode = "substring"
        scope = "project_production_files"
        pageSize = 5
    }
    Assert-ToolOk -Name "ide_find_class wildcard C#" -Response $findClassWildcard -Predicate {
        param($json) ($json.classes | Where-Object { $_.name -eq "MainWindow" }).Count -gt 0
    }

    $findClassTests = Invoke-Tool -Id 12 -Name "ide_find_class" -Arguments @{
        project_path = $ProjectPath
        query = "MainWindow"
        language = "C#"
        matchMode = "substring"
        scope = "project_test_files"
        pageSize = 5
    }
    Assert-ToolOk -Name "ide_find_class test scope C#" -Response $findClassTests -Predicate {
        param($json)
        ($json.classes | Where-Object { $_.name -eq "MainWindowHeadlessTests" }).Count -gt 0 -and
            ($json.classes | Where-Object { $_.name -eq "MainWindow" }).Count -eq 0
    }

    $definitionPosition = Invoke-Tool -Id 13 -Name "ide_find_definition" -Arguments @{
        project_path = $ProjectPath
        file = "Clipthrough/Views/MainWindow.axaml.cs"
        line = 19
        column = 22
    }
    Assert-ToolOk -Name "ide_find_definition position C#" -Response $definitionPosition -Predicate {
        param($json) $json.file -like "*MainWindow.axaml.cs" -and $json.symbolName -eq "MainWindow"
    }

    $definitionMember = Invoke-Tool -Id 14 -Name "ide_find_definition" -Arguments @{
        project_path = $ProjectPath
        language = "C#"
        symbol = "Clipthrough.Views.MainWindow#TryConnectClipListScrollViewer"
    }
    Assert-ToolOk -Name "ide_find_definition member symbol C#" -Response $definitionMember -Predicate {
        param($json) $json.file -like "*MainWindow.axaml.cs" -and $json.symbolName -eq "TryConnectClipListScrollViewer"
    }

    $definitionProperty = Invoke-Tool -Id 15 -Name "ide_find_definition" -Arguments @{
        project_path = $ProjectPath
        language = "C#"
        symbol = "Clipthrough.ViewModels.MainWindowViewModel#IsBusy"
    }
    Assert-ToolOk -Name "ide_find_definition property symbol C#" -Response $definitionProperty -Predicate {
        param($json) $json.file -like "*MainWindowViewModel.cs" -and $json.symbolName -eq "IsBusy"
    }

    $methodReferences = Invoke-Tool -Id 16 -Name "ide_find_references" -Arguments @{
        project_path = $ProjectPath
        file = "Clipthrough/Views/MainWindow.axaml.cs"
        line = 89
        column = 18
        pageSize = 5
    }
    Assert-ToolOk -Name "ide_find_references method position C#" -Response $methodReferences -Predicate {
        param($json) $json.totalCount -gt 0
    }

    $typeHierarchyClass = Invoke-Tool -Id 17 -Name "ide_type_hierarchy" -Arguments @{
        project_path = $ProjectPath
        file = "Clipthrough/Views/MainWindow.axaml.cs"
        line = 19
        column = 22
    }
    Assert-ToolOk -Name "ide_type_hierarchy class C#" -Response $typeHierarchyClass -Predicate {
        param($json) ($json.supertypes | Where-Object { $_.name -eq "Window" }).Count -gt 0
    }

    $typeHierarchyInterface = Invoke-Tool -Id 18 -Name "ide_type_hierarchy" -Arguments @{
        project_path = $ProjectPath
        file = "Clipthrough/Services/Storage/IClipStoreService.cs"
        line = 8
        column = 18
    }
    Assert-ToolOk -Name "ide_type_hierarchy interface C#" -Response $typeHierarchyInterface -Predicate {
        param($json) ($json.subtypes | Where-Object { $_.name -eq "ClipStoreService" }).Count -gt 0
    }

    $superMethods = Invoke-Tool -Id 19 -Name "ide_find_super_methods" -Arguments @{
        project_path = $ProjectPath
        file = "Clipthrough/App.axaml.cs"
        line = 54
        column = 26
    }
    Assert-ToolOk -Name "ide_find_super_methods override C#" -Response $superMethods -Predicate {
        param($json)
        ($json.hierarchy | Where-Object {
            $_.name -eq "Initialize" -and $_.containingClass -eq "Avalonia.Application"
        }).Count -gt 0
    }

    $findFileWildcard = Invoke-Tool -Id 60 -Name "ide_find_file" -Arguments @{
        project_path = $ProjectPath
        query = "*ViewModel.cs"
        pageSize = 10
    }
    Assert-ToolOk -Name "ide_find_file wildcard" -Response $findFileWildcard -Predicate {
        param($json) ($json.files | Where-Object { $_.name -eq "MainWindowViewModel.cs" }).Count -gt 0
    }

    $callHierarchySymbol = Invoke-Tool -Id 61 -Name "ide_call_hierarchy" -Arguments @{
        project_path = $ProjectPath
        language = "C#"
        symbol = "Clipthrough.Views.MainWindow#TryConnectClipListScrollViewer"
        direction = "callers"
        maxDepth = 2
    }
    Assert-ToolOk -Name "ide_call_hierarchy symbol C#" -Response $callHierarchySymbol -Predicate {
        param($json) $json.calls -ne $null
    }

    if ($TestRename) {
        $renamedFile = Join-Path $ProjectPath "Clipthrough/Views/MainWindowSmokeRenamed.axaml.cs"
        $originalFile = Join-Path $ProjectPath "Clipthrough/Views/MainWindow.axaml.cs"

        $rename = Invoke-Tool -Id 20 -Name "ide_refactor_rename" -Arguments @{
            project_path = $ProjectPath
            file = "Clipthrough/Views/MainWindow.axaml.cs"
            line = 19
            column = 22
            newName = "MainWindowSmokeRenamed"
        }
        Assert-ToolOk -Name "ide_refactor_rename C# apply" -Response $rename -Predicate {
            param($json) $json.success -eq $true
        }
        # Reg 4 (fixed in 4.20.1): affectedFiles is re-snapshotted post-rename so the
        # payload reports all files actually touched on disk (5+ for an AXAML/code-behind
        # rename), not just the pre-rename declaration set.
        $reportedAffected = if ($rename.result.isError) { 0 } else {
            $payload = (Get-ToolText $rename) | ConvertFrom-Json
            if ($payload.affectedFiles) { @($payload.affectedFiles).Count } else { 0 }
        }
        if ($reportedAffected -lt 2) {
            throw "ide_refactor_rename affectedFiles: expected >= 2 files reported, got $reportedAffected. Backend post-rename re-snapshot may not be wired up."
        }
        Write-Host "PASS ide_refactor_rename affectedFiles reports $reportedAffected file(s)"
        if (-not (Test-Path $renamedFile)) {
            throw "ide_refactor_rename C# apply: expected $renamedFile on disk after rename."
        }
        if (Test-Path $originalFile) {
            throw "ide_refactor_rename C# apply: original $originalFile still present after rename."
        }
        Write-Host "PASS ide_refactor_rename C# apply on-disk verification"

        # After the apply step the file lives at MainWindowSmokeRenamed.axaml.cs. The class
        # declaration in that file moves to roughly the same line/column, so we point the
        # revert at the renamed file path.
        $renameBack = Invoke-Tool -Id 21 -Name "ide_refactor_rename" -Arguments @{
            project_path = $ProjectPath
            file = "Clipthrough/Views/MainWindowSmokeRenamed.axaml.cs"
            line = 19
            column = 22
            newName = "MainWindow"
        }
        Assert-ToolOk -Name "ide_refactor_rename C# revert" -Response $renameBack -Predicate {
            param($json) $json.success -eq $true
        }
        if (-not (Test-Path $originalFile)) {
            throw "ide_refactor_rename C# revert: expected $originalFile on disk after revert."
        }
        if (Test-Path $renamedFile) {
            throw "ide_refactor_rename C# revert: renamed $renamedFile still present after revert."
        }
        Write-Host "PASS ide_refactor_rename C# revert on-disk verification"

        # Cross-verify by re-querying ide_find_class — the renamed name must no longer exist and
        # the original must be back.
        $verifyOriginal = Invoke-Tool -Id 22 -Name "ide_find_class" -Arguments @{
            project_path = $ProjectPath
            query        = "MainWindow"
            matchMode    = "exact"
            language     = "C#"
        }
        Assert-ToolOk -Name "ide_find_class MainWindow exists after revert" -Response $verifyOriginal -Predicate {
            param($json) @($json.classes | Where-Object {
                $_.name -eq "MainWindow" -and $_.file -like "*MainWindow.axaml.cs*"
            }).Count -ge 1
        }
        $verifyRenamed = Invoke-Tool -Id 23 -Name "ide_find_class" -Arguments @{
            project_path = $ProjectPath
            query        = "MainWindowSmokeRenamed"
            matchMode    = "exact"
            language     = "C#"
        }
        Assert-ToolOk -Name "ide_find_class MainWindowSmokeRenamed gone after revert" -Response $verifyRenamed -Predicate {
            param($json) @($json.classes | Where-Object { $_.name -eq "MainWindowSmokeRenamed" }).Count -eq 0
        }
    } else {
        Write-Host "SKIP ide_refactor_rename C# apply: pass -TestRename only for disposable project copies."
    }
}

Write-Host "All Rider live smoke checks passed."
