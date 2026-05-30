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
    [switch] $TestSafeDelete,
    [switch] $ResetWorkspace,
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

function Reset-WorkspaceClean {
    if (-not (Test-Path (Join-Path $ProjectPath ".git"))) {
        Write-Host "Workspace at $ProjectPath is not a git repo; skipping pre-flight clean."
        return
    }
    Push-Location $ProjectPath
    try {
        $status = git status --porcelain 2>$null
        if ($status) {
            if (-not $ResetWorkspace) {
                Write-Host "Workspace at $ProjectPath is dirty:"
                $status | ForEach-Object { Write-Host $_ }
                throw "Refusing to run 'git reset --hard' without -ResetWorkspace. Re-run with -ResetWorkspace only on a disposable/throwaway checkout."
            }
            Write-Host "Workspace at $ProjectPath was dirty; running 'git reset --hard' to reset."
            git reset --hard 2>&1 | Out-Null
            # Remove smoke-rename artefacts that survive 'git reset --hard' (untracked new paths).
            $smokeArtefacts = @(
                "Clipthrough/Views/MainWindowSmokeRenamed.axaml",
                "Clipthrough/Views/MainWindowSmokeRenamed.axaml.cs"
            )
            foreach ($rel in $smokeArtefacts) {
                $abs = Join-Path $ProjectPath $rel
                if (Test-Path $abs) {
                    Remove-Item -Path $abs -Force -ErrorAction SilentlyContinue
                    Write-Host "Removed stale smoke artefact $rel"
                }
            }
            $stillDirty = git status --porcelain 2>$null | Where-Object {
                $_ -notmatch '^\?\? (\.agents/|\.github/skills/|jetmove/)$'
            }
            if ($stillDirty) {
                throw "Workspace still dirty after reset:`n$($stillDirty -join "`n")"
            }
        }
        Write-Host "Workspace clean."
    } finally {
        Pop-Location
    }
}

function Assert-PathUnchanged {
    # Guards "blocked"/"fail-closed" mutation tests: the named tool reported it did NOT
    # mutate, so the working tree for these paths must be byte-for-byte unchanged. Catches
    # partial mutations that slip through before a tool reports blocked.
    param(
        [string] $Name,
        [string[]] $RelativePaths
    )
    if (-not (Test-Path (Join-Path $ProjectPath ".git"))) {
        Write-Host "SKIP $Name no-mutation check: $ProjectPath is not a git repo."
        return
    }
    Push-Location $ProjectPath
    try {
        foreach ($rel in $RelativePaths) {
            $dirty = git status --porcelain -- $rel 2>$null
            if ($dirty) {
                throw "$Name was expected to be non-mutating, but '$rel' changed on disk:`n$dirty"
            }
        }
    } finally {
        Pop-Location
    }
    Write-Host "PASS $Name left $($RelativePaths -join ', ') unchanged on disk"
}

if (-not (Test-Path $PluginZip)) { throw "Plugin ZIP not found: $PluginZip" }
if (-not (Test-Path $RiderExe)) { throw "Rider executable not found: $RiderExe" }
if (-not (Test-Path $SolutionPath)) { throw "Solution not found: $SolutionPath" }

Stop-Rider
Start-Sleep -Seconds 6
Reset-WorkspaceClean
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

# Finding #11: SERVER_DESCRIPTION must advertise "C# (Rider)" after F# was dropped in 4.20.0.
# The description rides on initialize.result.serverInfo.description (JsonRpcHandler), so the
# live initialize response is the authoritative source.
$serverDescription = [string]$initialize.result.serverInfo.description
if ($serverDescription -notmatch "C# \(Rider\)") {
    throw "MCP serverInfo.description should advertise 'C# (Rider)' but was: $serverDescription"
}
if ($serverDescription -match "C#/F#") {
    throw "MCP serverInfo.description still advertises the removed 'C#/F#' Rider support: $serverDescription"
}
Write-Host "PASS serverInfo.description advertises C# (Rider) without stale F#"

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
    param($json) $json.file -like "*MainWindowViewModel.cs" -and $json.symbolName -eq "MainWindowViewModel"
}

# Reg 2 (fixed in 4.20.1): dotted Class.Property form is split on the last '.' and
# re-resolved as a member of the LHS type via TrySplitDottedSymbolAsMember.
$regDottedPropDef = Invoke-Tool -Id 71 -Name "ide_find_definition" -Arguments @{
    project_path = $ProjectPath
    language = "C#"
    symbol = "Clipthrough.ViewModels.MainWindowViewModel.IsBusy"
}
Assert-ToolOk -Name "ide_find_definition Class.Prop dotted symbol" -Response $regDottedPropDef -Predicate {
    param($json) $json.file -like "*MainWindowViewModel.cs" -and $json.symbolName -eq "IsBusy"
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

# Finding #10: ide_find_definition must surface the backend's errorMessage for an
# unresolvable C# symbol instead of swallowing it behind a generic fallback. The response
# must be a clean tool error with a non-empty, sanitized message (no RdFault / backend
# source path leak), mirroring the find_references contract above.
$missingDef = Invoke-Tool -Id 73 -Name "ide_find_definition" -Arguments @{
    project_path = $ProjectPath
    language = "C#"
    symbol = "DoesNotExistAnywhereInClipthrough"
}
$missingDefText = Get-ToolText $missingDef
if (-not $missingDef.result.isError) {
    throw "ide_find_definition unresolved symbol: expected tool error response, got success: $missingDefText"
}
if ($missingDefText -match "IndexMcpBackendHost\.cs" -or $missingDefText -match "RdFault") {
    throw "REGRESSION ide_find_definition error leaks backend RdFault or source path: $missingDefText"
}
if ([string]::IsNullOrWhiteSpace($missingDefText)) {
    throw "ide_find_definition unresolved symbol returned an empty error message; backend errorMessage is not being surfaced."
}
Write-Host "PASS ide_find_definition unresolved symbol surfaces sanitized error"

# Finding #7: C# member/constructor matching is case-sensitive Ordinal. The real member is
# `TryConnectClipListScrollViewer`; a wrong-case `tryConnectClipListScrollViewer` must NOT
# resolve via a case-insensitive fallback. Hedge: tolerate success ONLY if the backend
# echoes the exact requested casing (i.e. it genuinely found that spelling, not a fuzzy
# fall-through to the real PascalCase member).
$wrongCaseDef = Invoke-Tool -Id 74 -Name "ide_find_definition" -Arguments @{
    project_path = $ProjectPath
    language = "C#"
    symbol = "Clipthrough.Views.MainWindow#tryConnectClipListScrollViewer"
}
if ($wrongCaseDef.result.isError) {
    Write-Host "PASS ide_find_definition wrong-case C# member rejected (case-sensitive)"
} else {
    $wrongCaseJson = (Get-ToolText $wrongCaseDef) | ConvertFrom-Json
    if ($wrongCaseJson.symbolName -ceq "tryConnectClipListScrollViewer") {
        Write-Host "PASS ide_find_definition wrong-case resolved to exact requested casing only"
    } else {
        throw "REGRESSION ide_find_definition resolved wrong-case 'tryConnectClipListScrollViewer' to '$($wrongCaseJson.symbolName)'; case-insensitive member matching regressed."
    }
}

# Finding #4: ide_find_references applies `scope` BEFORE truncation and reports a scope-aware
# totalCount. This is scope-sanity coverage (not truncation-boundary coverage): assert the
# counts are monotonic across widening scopes and that the production-only result never
# includes a reference from a test project directory.
$refSymbol = "Clipthrough.Views.MainWindow#TryConnectClipListScrollViewer"
$refProd = Invoke-Tool -Id 75 -Name "ide_find_references" -Arguments @{
    project_path = $ProjectPath; language = "C#"; symbol = $refSymbol
    scope = "project_production_files"; pageSize = 50
}
$refAll = Invoke-Tool -Id 76 -Name "ide_find_references" -Arguments @{
    project_path = $ProjectPath; language = "C#"; symbol = $refSymbol
    scope = "project_files"; pageSize = 50
}
$refLibs = Invoke-Tool -Id 77 -Name "ide_find_references" -Arguments @{
    project_path = $ProjectPath; language = "C#"; symbol = $refSymbol
    scope = "project_and_libraries"; pageSize = 50
}
$refProdJson = (Get-ToolText $refProd) | ConvertFrom-Json
$refAllJson  = (Get-ToolText $refAll)  | ConvertFrom-Json
$refLibsJson = (Get-ToolText $refLibs) | ConvertFrom-Json
foreach ($pair in @(@{ n = "production"; j = $refProdJson }, @{ n = "project"; j = $refAllJson }, @{ n = "libraries"; j = $refLibsJson })) {
    if ($pair.j.totalCount -lt 0) {
        throw "ide_find_references scope $($pair.n): totalCount should be >= 0, got $($pair.j.totalCount)."
    }
}
if (-not ($refProdJson.totalCount -le $refAllJson.totalCount -and $refAllJson.totalCount -le $refLibsJson.totalCount)) {
    throw "ide_find_references scope counts not monotonic: production=$($refProdJson.totalCount) project=$($refAllJson.totalCount) libraries=$($refLibsJson.totalCount). Scope may be applied after truncation."
}
$prodTestLeak = @($refProdJson.references | Where-Object { $_.file -match '(?i)[\\/][^\\/]*Tests?[\\/]' })
if ($prodTestLeak.Count -gt 0) {
    throw "ide_find_references project_production_files leaked test-project references: $(($prodTestLeak | ForEach-Object { $_.file }) -join ', ')"
}
Write-Host "PASS ide_find_references scope filtering monotonic + production excludes test paths (prod=$($refProdJson.totalCount) project=$($refAllJson.totalCount) libs=$($refLibsJson.totalCount))"

# Findings #1 (git reset gating), #2 (sandbox DLL dir) and #9 (version constant sync) are not
# directly observable as MCP tool calls: #1/#2 are exercised by this script's own pre-flight
# and install steps, and #9 is covered by the version assertion above.

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

    # Finding #5: ide_find_implementations / ide_type_hierarchy honor `scope` (the scope filter
    # is applied before truncation). Production scope must still surface the production
    # implementation `ClipStoreService`; test scope must NOT return that production class
    # (an empty result is acceptable — the contract only guarantees production code is excluded).
    $implProdScope = Invoke-Tool -Id 84 -Name "ide_find_implementations" -Arguments @{
        project_path = $ProjectPath
        file = "Clipthrough/Services/Storage/IClipStoreService.cs"
        line = 8
        column = 18
        scope = "project_production_files"
        pageSize = 10
    }
    Assert-ToolOk -Name "ide_find_implementations production scope C#" -Response $implProdScope -Predicate {
        param($json) ($json.implementations | Where-Object { $_.name -eq "ClipStoreService" }).Count -gt 0
    }

    $implTestScope = Invoke-Tool -Id 85 -Name "ide_find_implementations" -Arguments @{
        project_path = $ProjectPath
        file = "Clipthrough/Services/Storage/IClipStoreService.cs"
        line = 8
        column = 18
        scope = "project_test_files"
        pageSize = 10
    }
    Assert-ToolOk -Name "ide_find_implementations test scope excludes production C#" -Response $implTestScope -Predicate {
        param($json) @($json.implementations | Where-Object { $_.name -eq "ClipStoreService" }).Count -eq 0
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

        # Finding #3 (fail-closed Rider C# rename planning) is NOT exercised live here.
        # The backend-rename lane was removed; C# rename now always goes through the Rider
        # frontend-automation lane (`RenameSymbolTool.shouldUseRiderFrontendRenameAutomation`),
        # which resolves a caret UP to the nearest
        # renamable declaration (e.g. a caret on the `: Window` base-type token resolves to the
        # enclosing class `MainWindow` and renames it). There is therefore no file+line+column that
        # deterministically AND safely drives the #3 fall-through guard without risking an unwanted
        # mutation of this real repo. #3 is covered comprehensively by unit tests instead:
        #   RenameSymbolToolExperimentalActionUnitTest  (planning failure -> unsupported_context,
        #                                                 missing-editor / automation-timeout fail-closed)
        #   RenameSymbolToolRoutingUnitTest             (zero-change summary -> fail closed)
        #   RenameSymbolToolTargetResolutionUnitTest    (container-like candidates fail closed)
        #
        # The check below is a GENERIC name-validation / no-mutation smoke (NOT #3 coverage): an
        # invalid C# identifier must be rejected before any rename engine runs, leaving the file
        # untouched. This is mutation-safe because identifier validation precedes Rider planning.
        $renameInvalid = Invoke-Tool -Id 83 -Name "ide_refactor_rename" -Arguments @{
            project_path = $ProjectPath
            file = "Clipthrough/Views/MainWindow.axaml.cs"
            line = 19
            column = 22
            newName = "123 Not A Valid Identifier"
        }
        $renameInvalidText = Get-ToolText $renameInvalid
        $renameInvalidRejected = $renameInvalid.result.isError
        if (-not $renameInvalidRejected) {
            $renameInvalidJson = $renameInvalidText | ConvertFrom-Json
            if ($renameInvalidJson.success -eq $true) {
                throw "ide_refactor_rename accepted an invalid C# identifier '123 Not A Valid Identifier' and reported success: $renameInvalidText"
            }
        }
        if ($renameInvalidText -match "IndexMcpBackendHost\.cs" -or $renameInvalidText -match "RdFault") {
            throw "REGRESSION ide_refactor_rename rejection path leaks backend RdFault or source path: $renameInvalidText"
        }
        Assert-PathUnchanged -Name "ide_refactor_rename invalid-name rejection" -RelativePaths @("Clipthrough/Views/MainWindow.axaml.cs")
        Write-Host "PASS ide_refactor_rename rejects invalid identifier without mutation (generic; #3 is unit-covered)"

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

# Finding #8: ide_refactor_safe_delete uses a semantic reference search (the regex scan was
# removed) and BLOCKS deletion when real usages exist. `IClipStoreService` is implemented by
# `ClipStoreService` and consumed via DI, so a non-forced safe delete must report blocked with
# concrete blocking usages — and crucially must NOT mutate the workspace. Gated behind
# -TestSafeDelete because it drives real Rider refactoring machinery; the blocked path is
# non-destructive and verified so via a git no-mutation check.
if ($TestSafeDelete) {
    $safeDeleteTarget = "Clipthrough/Services/Storage/IClipStoreService.cs"
    $safeDeleteAbs = Join-Path $ProjectPath $safeDeleteTarget
    if (-not (Test-Path $safeDeleteAbs)) {
        throw "ide_refactor_safe_delete precondition: target $safeDeleteAbs not found."
    }
    $safeDelete = Invoke-Tool -Id 80 -Name "ide_refactor_safe_delete" -Arguments @{
        project_path = $ProjectPath
        file = $safeDeleteTarget
        line = 8
        column = 18
        force = $false
    }
    $safeDeleteText = Get-ToolText $safeDelete
    if ($safeDeleteText -match "IndexMcpBackendHost\.cs" -or $safeDeleteText -match "RdFault") {
        throw "REGRESSION ide_refactor_safe_delete leaks backend RdFault or source path: $safeDeleteText"
    }
    # Blocked may surface either as a tool error or as a structured canDelete=false payload.
    $safeDeleteBlocked = $false
    if ($safeDelete.result.isError) {
        $safeDeleteBlocked = $true
    } else {
        $safeDeleteJson = $safeDeleteText | ConvertFrom-Json
        $usageCount = if ($null -ne $safeDeleteJson.usageCount) { [int]$safeDeleteJson.usageCount } else { @($safeDeleteJson.blockingUsages).Count }
        if ($safeDeleteJson.canDelete -eq $false -or $safeDeleteJson.status -eq "blocked" -or $usageCount -gt 0) {
            $safeDeleteBlocked = $true
            if ($usageCount -le 0) {
                throw "ide_refactor_safe_delete reported blocked but listed no blocking usages; semantic usage search may be broken: $safeDeleteText"
            }
            Write-Host "PASS ide_refactor_safe_delete reported blocked with $usageCount blocking usage(s)"
        }
    }
    if (-not $safeDeleteBlocked) {
        throw "ide_refactor_safe_delete of in-use interface IClipStoreService was expected to be BLOCKED, got: $safeDeleteText"
    }
    if (-not (Test-Path $safeDeleteAbs)) {
        throw "ide_refactor_safe_delete BLOCKED path deleted $safeDeleteAbs anyway; safe delete is not fail-safe."
    }
    Assert-PathUnchanged -Name "ide_refactor_safe_delete blocked" -RelativePaths @($safeDeleteTarget)
    Write-Host "PASS ide_refactor_safe_delete blocked path is non-destructive"
} else {
    Write-Host "SKIP ide_refactor_safe_delete blocked check: pass -TestSafeDelete to exercise finding #8."
}

Write-Host "All Rider live smoke checks passed."
