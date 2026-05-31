<#
.SYNOPSIS
    Live smoke test for the IDE Index MCP plugin running inside IntelliJ IDEA (the non-Rider lane).

.DESCRIPTION
    Mirrors scripts/rider-live-smoke.ps1 but targets IntelliJ IDEA Ultimate and a Kotlin/Java
    codebase instead of Rider + Clipthrough (C#). It:
      1. Stops any running IntelliJ, installs the freshly built plugin ZIP, and relaunches IntelliJ
         on the plugin repo itself (a real Kotlin/Gradle project).
      2. Waits for the MCP endpoint (default port 29170) and for the IDE to reach smart mode.
      3. Runs the universal + Kotlin/Java navigation tool matrix against known repo symbols.
      4. Optionally (-TestRename / -TestSafeDelete) exercises the mutating tools against dedicated
         throwaway scratch files under src/test/kotlin/.../smoke/ which are created, verified, and
         deleted by this script. It NEVER mutates tracked sources and NEVER runs 'git reset --hard'.

    Symbol positions are computed dynamically from the source text, so the matrix tolerates line
    drift as the repo evolves (unlike the hard-coded line numbers in the Rider smoke).

.EXAMPLE
    pwsh -NoProfile -File scripts\intellij-live-smoke.ps1 `
        -PluginZip "build\distributions\jetbrains-index-mcp-plugin-4.20.1.zip" `
        -TestRename -TestSafeDelete -ExpectedVersion 4.20.1 `
        -InitialDelaySeconds 60 -StartupWaitSeconds 240
#>
param(
    [Parameter(Mandatory = $true)]
    [string] $PluginZip,

    [string] $IdeaExe = "$env:LOCALAPPDATA\Programs\IntelliJ IDEA Ultimate\bin\idea64.exe",
    [string] $PluginDir = "$env:APPDATA\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-index-mcp-plugin",
    # Default project = this plugin repo (parent of the scripts/ directory).
    [string] $ProjectPath = (Split-Path -Parent $PSScriptRoot),
    [string] $Endpoint = "http://127.0.0.1:29170/index-mcp/streamable-http",
    [int] $InitialDelaySeconds = 60,
    [int] $StartupWaitSeconds = 240,
    [int] $SmartModeWaitSeconds = 360,
    [switch] $TestRename,
    [switch] $TestSafeDelete,
    [string] $ExpectedVersion
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "Run this script with PowerShell 7+ (pwsh) so MCP HTTP requests do not hang during IDE startup."
}

# Scratch source package for mutation tests (an indexed test source root, untracked, auto-cleaned).
$ScratchRelDir = "src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/smoke"
$ScratchAbsDir = Join-Path $ProjectPath $ScratchRelDir

function Stop-Idea {
    Get-CimInstance Win32_Process |
        Where-Object { $_.ExecutablePath -eq $IdeaExe -or $_.Name -eq "idea64.exe" } |
        ForEach-Object {
            Write-Host "Stopping IntelliJ PID $($_.ProcessId)"
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
                clientInfo = @{ name = "intellij-live-smoke"; version = "1" }
            }
        } catch {
            $lastError = $_
            Start-Sleep -Seconds 3
        }
    }

    throw "MCP endpoint did not become ready within $StartupWaitSeconds seconds. Last error: $lastError"
}

function Wait-SmartMode {
    # IntelliJ + Gradle import indexes for a while after launch; most tools need smart mode.
    $deadline = (Get-Date).AddSeconds($SmartModeWaitSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $status = Invoke-Tool -Id 2 -Name "ide_index_status" -Arguments @{ project_path = $ProjectPath }
            if (-not $status.result.isError) {
                $json = (Get-ToolText $status) | ConvertFrom-Json
                if (-not $json.isDumbMode -and -not $json.isIndexing) {
                    Write-Host "PASS ide_index_status (smart mode)"
                    return
                }
                Write-Host "Waiting for smart mode (dumb=$($json.isDumbMode) indexing=$($json.isIndexing))..."
            }
        } catch {
            Write-Host "Waiting for index status endpoint..."
        }
        Start-Sleep -Seconds 5
    }
    throw "IDE did not reach smart mode within $SmartModeWaitSeconds seconds."
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

function Assert-ToolText {
    # Lenient assertion for tools whose payload shape varies (hierarchies): just require success
    # and that the raw response text contains an expected substring.
    param(
        [string] $Name,
        [object] $Response,
        [string] $Needle
    )

    if ($Response.result.isError) {
        throw "$Name failed: $(Get-ToolText $Response)"
    }
    $text = Get-ToolText $Response
    if ($text -notmatch [regex]::Escape($Needle)) {
        throw "$Name response did not contain '$Needle': $text"
    }
    Write-Host "PASS $Name"
}

function Get-ExpectedPluginVersion {
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $gradleProps = Join-Path $repoRoot "gradle.properties"
    if (-not (Test-Path $gradleProps)) { return $null }
    $line = Select-String -Path $gradleProps -Pattern '^\s*pluginVersion\s*=\s*(.+?)\s*$' | Select-Object -First 1
    if (-not $line) { return $null }
    return $line.Matches[0].Groups[1].Value
}

function Get-TokenPosition {
    # Returns @{ line; column } (1-based) of $Token on the first line that contains $LineNeedle.
    # Used to keep the matrix robust against line drift in an actively-developed repo.
    param(
        [string] $AbsFile,
        [string] $LineNeedle,
        [string] $Token,
        [int] $Occurrence = 1
    )
    $lines = Get-Content -LiteralPath $AbsFile
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $needleIdx = $lines[$i].IndexOf($LineNeedle)
        if ($needleIdx -lt 0) { continue }
        $tokIdx = $lines[$i].IndexOf($Token, $needleIdx)
        if ($tokIdx -ge 0) {
            return @{ line = $i + 1; column = $tokIdx + 1 }
        }
    }
    throw "Token '$Token' (on a line containing '$LineNeedle') not found in $AbsFile"
}

function Assert-FileContains {
    param([string] $AbsFile, [string] $Needle, [string] $Context)
    $content = Get-Content -LiteralPath $AbsFile -Raw
    if ($content -notmatch [regex]::Escape($Needle)) {
        throw "$Context expected file to contain '$Needle' but it did not. Content:`n$content"
    }
}

function Assert-FileNotContains {
    param([string] $AbsFile, [string] $Needle, [string] $Context)
    $content = Get-Content -LiteralPath $AbsFile -Raw
    if ($content -match [regex]::Escape($Needle)) {
        throw "$Context expected file to NOT contain '$Needle' but it did. Content:`n$content"
    }
}

function Remove-Scratch {
    if (Test-Path $ScratchAbsDir) {
        Remove-Item -Path $ScratchAbsDir -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "Removed scratch dir $ScratchRelDir"
    }
}

# ── Pre-flight ───────────────────────────────────────────────────────────────
if (-not (Test-Path $PluginZip)) { throw "Plugin ZIP not found: $PluginZip" }
if (-not (Test-Path $IdeaExe))   { throw "IntelliJ executable not found: $IdeaExe" }
if (-not (Test-Path $ProjectPath)) { throw "Project path not found: $ProjectPath" }

# Safety: refuse to clobber an existing (possibly tracked) smoke package.
if (($TestRename -or $TestSafeDelete) -and (Test-Path $ScratchAbsDir)) {
    $tracked = (git -C $ProjectPath ls-files -- $ScratchRelDir 2>$null)
    if ($tracked) {
        throw "Scratch dir $ScratchRelDir already exists and is tracked by git. Refusing to overwrite. Remove it first."
    }
    Remove-Scratch
}

Stop-Idea
Start-Sleep -Seconds 6
Install-Plugin

Write-Host "Starting IntelliJ with project $ProjectPath"
Start-Process -FilePath $IdeaExe -ArgumentList @($ProjectPath)
Start-Sleep -Seconds $InitialDelaySeconds

$initialize = Wait-McpInitialize
$reportedVersion = $initialize.result.serverInfo.version
Write-Host "MCP version $reportedVersion"

$expectedVersion = if ($ExpectedVersion) { $ExpectedVersion } else { Get-ExpectedPluginVersion }
if ($expectedVersion) {
    if ($reportedVersion -ne $expectedVersion) {
        throw "MCP version mismatch: server reported '$reportedVersion' but expected '$expectedVersion'. The install pipeline may have loaded a stale build, or McpConstants.getServerVersion()/SERVER_VERSION_FALLBACK is out of sync with gradle.properties:pluginVersion."
    }
    Write-Host "PASS version matches expected $expectedVersion"
} else {
    Write-Warning "Expected plugin version could not be determined; skipping version assertion."
}

Wait-SmartMode

# Repo anchor files (relative to project root).
$renameToolRel   = "src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/refactoring/RenameSymbolTool.kt"
$abstractToolRel = "src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/AbstractMcpTool.kt"
$mcpToolRel      = "src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/McpTool.kt"
$renameToolAbs   = Join-Path $ProjectPath $renameToolRel
$abstractToolAbs = Join-Path $ProjectPath $abstractToolRel
$mcpToolAbs      = Join-Path $ProjectPath $mcpToolRel

# ── Read-only matrix ─────────────────────────────────────────────────────────

$findClass = Invoke-Tool -Id 10 -Name "ide_find_class" -Arguments @{
    project_path = $ProjectPath; query = "RenameSymbolTool"; language = "Kotlin"
    matchMode = "substring"; scope = "project_production_files"; pageSize = 5
}
Assert-ToolOk -Name "ide_find_class production Kotlin" -Response $findClass -Predicate {
    param($json) ($json.classes | Where-Object { $_.name -eq "RenameSymbolTool" }).Count -gt 0
}

$findFile = Invoke-Tool -Id 11 -Name "ide_find_file" -Arguments @{
    project_path = $ProjectPath; query = "RenameSymbolTool.kt"; pageSize = 5
}
Assert-ToolOk -Name "ide_find_file basic" -Response $findFile -Predicate {
    param($json) ($json.files | Where-Object { $_.name -eq "RenameSymbolTool.kt" }).Count -gt 0
}

$searchPage1 = Invoke-Tool -Id 12 -Name "ide_search_text" -Arguments @{
    project_path = $ProjectPath; query = "RenameSymbolTool"; pageSize = 5
}
Assert-ToolOk -Name "ide_search_text page 1" -Response $searchPage1 -Predicate {
    param($json) $json.matches.Count -gt 0 -and $json.totalCount -gt 0
}
$searchPage1Json = (Get-ToolText $searchPage1) | ConvertFrom-Json
if ($searchPage1Json.nextCursor) {
    $searchPage2 = Invoke-Tool -Id 13 -Name "ide_search_text" -Arguments @{
        project_path = $ProjectPath; cursor = $searchPage1Json.nextCursor
    }
    Assert-ToolOk -Name "ide_search_text page 2 via cursor" -Response $searchPage2 -Predicate {
        param($json) $json.matches.Count -gt 0 -and -not $json.stale
    }
} else {
    Write-Host "SKIP ide_search_text page 2: only one page of results."
}

# find_definition (position): cursor on the `AbstractMcpTool` supertype reference in RenameSymbolTool.kt
$pos = Get-TokenPosition -AbsFile $renameToolAbs -LineNeedle "class RenameSymbolTool" -Token "AbstractMcpTool"
$definition = Invoke-Tool -Id 14 -Name "ide_find_definition" -Arguments @{
    project_path = $ProjectPath; file = $renameToolRel; line = $pos.line; column = $pos.column
}
Assert-ToolOk -Name "ide_find_definition position Kotlin cross-file" -Response $definition -Predicate {
    param($json) $json.file -like "*AbstractMcpTool.kt" -and $json.symbolName -eq "AbstractMcpTool"
}

# find_references (position): cursor on the `AbstractMcpTool` declaration; it is subclassed widely.
$pos = Get-TokenPosition -AbsFile $abstractToolAbs -LineNeedle "abstract class AbstractMcpTool" -Token "AbstractMcpTool"
$references = Invoke-Tool -Id 15 -Name "ide_find_references" -Arguments @{
    project_path = $ProjectPath; file = $abstractToolRel; line = $pos.line; column = $pos.column; pageSize = 10
}
Assert-ToolOk -Name "ide_find_references position Kotlin" -Response $references -Predicate {
    param($json) $json.totalCount -gt 0
}

# type_hierarchy: AbstractMcpTool subtypes should include RenameSymbolTool.
$typeHierarchy = Invoke-Tool -Id 16 -Name "ide_type_hierarchy" -Arguments @{
    project_path = $ProjectPath; file = $abstractToolRel; line = $pos.line; column = $pos.column
}
Assert-ToolText -Name "ide_type_hierarchy Kotlin subtypes" -Response $typeHierarchy -Needle "RenameSymbolTool"

# find_implementations: McpTool interface implementations should include AbstractMcpTool.
$pos = Get-TokenPosition -AbsFile $mcpToolAbs -LineNeedle "interface McpTool" -Token "McpTool"
$implementations = Invoke-Tool -Id 17 -Name "ide_find_implementations" -Arguments @{
    project_path = $ProjectPath; file = $mcpToolRel; line = $pos.line; column = $pos.column; pageSize = 20
}
Assert-ToolText -Name "ide_find_implementations Kotlin interface" -Response $implementations -Needle "AbstractMcpTool"

# find_super_methods: RenameSymbolTool.doExecute overrides AbstractMcpTool/McpTool.doExecute.
$pos = Get-TokenPosition -AbsFile $renameToolAbs -LineNeedle "override suspend fun doExecute" -Token "doExecute"
$superMethods = Invoke-Tool -Id 18 -Name "ide_find_super_methods" -Arguments @{
    project_path = $ProjectPath; file = $renameToolRel; line = $pos.line; column = $pos.column
}
Assert-ToolText -Name "ide_find_super_methods Kotlin override" -Response $superMethods -Needle "doExecute"

# call_hierarchy: callers of doExecute (invoked by the abstract execute()).
$callHierarchy = Invoke-Tool -Id 19 -Name "ide_call_hierarchy" -Arguments @{
    project_path = $ProjectPath; file = $renameToolRel; line = $pos.line; column = $pos.column
    direction = "callers"; maxDepth = 2
}
if ($callHierarchy.result.isError) {
    throw "ide_call_hierarchy callers Kotlin failed: $(Get-ToolText $callHierarchy)"
}
Write-Host "PASS ide_call_hierarchy callers Kotlin"

$syncFiles = Invoke-Tool -Id 20 -Name "ide_sync_files" -Arguments @{ project_path = $ProjectPath }
Assert-ToolOk -Name "ide_sync_files whole project" -Response $syncFiles -Predicate {
    param($json) $json.syncedAll -eq $true
}

$diagnostics = Invoke-Tool -Id 21 -Name "ide_diagnostics" -Arguments @{
    project_path = $ProjectPath; file = $mcpToolRel; severity = "errors"
}
Assert-ToolOk -Name "ide_diagnostics file-level no errors" -Response $diagnostics -Predicate {
    param($json) $json.problemCount -ne $null -and $json.problemCount -eq 0
}

# ── Mutation matrix (scratch files only) ─────────────────────────────────────

if ($TestRename -or $TestSafeDelete) {
    try {
        New-Item -ItemType Directory -Force -Path $ScratchAbsDir | Out-Null

        if ($TestRename) {
            $renameRel = "$ScratchRelDir/SmokeRenameTarget.kt"
            $renameAbs = Join-Path $ScratchAbsDir "SmokeRenameTarget.kt"
            @'
package com.github.hechtcarmel.jetbrainsindexmcpplugin.smoke

class SmokeRenameTarget {
    fun smokeTarget(): Int = 1

    fun smokeCaller(): Int = smokeTarget()
}
'@ | Set-Content -LiteralPath $renameAbs -Encoding UTF8

            # Make the IDE pick up the externally-created file before refactoring.
            $sync = Invoke-Tool -Id 30 -Name "ide_sync_files" -Arguments @{
                project_path = $ProjectPath; paths = @($renameRel)
            }
            if ($sync.result.isError) { throw "ide_sync_files (scratch rename) failed: $(Get-ToolText $sync)" }
            Wait-SmartMode

            # Reg 1 live: rename at the USAGE `smokeTarget()` inside smokeCaller. The referenced
            # method declaration must be renamed (not the enclosing smokeCaller).
            $pos = Get-TokenPosition -AbsFile $renameAbs -LineNeedle "= smokeTarget()" -Token "smokeTarget"
            $rename = Invoke-Tool -Id 31 -Name "ide_refactor_rename" -Arguments @{
                project_path = $ProjectPath; file = $renameRel; line = $pos.line; column = $pos.column
                newName = "smokeRenamed"
            }
            if ($rename.result.isError) { throw "ide_refactor_rename (usage) failed: $(Get-ToolText $rename)" }
            Write-Host "PASS ide_refactor_rename Kotlin usage rename applied"

            Start-Sleep -Seconds 2
            Assert-FileContains    -AbsFile $renameAbs -Needle "fun smokeRenamed()" -Context "rename-at-usage (declaration)"
            Assert-FileContains    -AbsFile $renameAbs -Needle "= smokeRenamed()"   -Context "rename-at-usage (call site)"
            Assert-FileNotContains -AbsFile $renameAbs -Needle "smokeTarget"         -Context "rename-at-usage (old name gone)"
            Assert-FileContains    -AbsFile $renameAbs -Needle "fun smokeCaller()"   -Context "rename-at-usage (enclosing method untouched)"
            Write-Host "PASS ide_refactor_rename renamed the referenced declaration, not the enclosing method (Reg 1 live)"

            # Reg 2 live: explicit line:0,column:0 must error as invalid position, not file-rename.
            $before = Get-Content -LiteralPath $renameAbs -Raw
            $zeroPos = Invoke-Tool -Id 32 -Name "ide_refactor_rename" -Arguments @{
                project_path = $ProjectPath; file = $renameRel; line = 0; column = 0; newName = "SmokeRenamedFile"
            }
            if (-not $zeroPos.result.isError) {
                throw "ide_refactor_rename line:0,column:0 unexpectedly succeeded: $(Get-ToolText $zeroPos)"
            }
            if ((Get-ToolText $zeroPos) -notmatch "Invalid position") {
                throw "ide_refactor_rename line:0,column:0 did not report an invalid position: $(Get-ToolText $zeroPos)"
            }
            $after = Get-Content -LiteralPath $renameAbs -Raw
            if ($before -ne $after) { throw "ide_refactor_rename line:0,column:0 mutated the file (should be non-destructive)." }
            Write-Host "PASS ide_refactor_rename rejects line:0,column:0 as invalid position, non-destructive (Reg 2 live)"
        }

        if ($TestSafeDelete) {
            $deleteRel = "$ScratchRelDir/SmokeDeleteTarget.kt"
            $deleteAbs = Join-Path $ScratchAbsDir "SmokeDeleteTarget.kt"
            @'
package com.github.hechtcarmel.jetbrainsindexmcpplugin.smoke

class SmokeDeleteTarget {
    fun usedSymbol(): Int = 7

    fun consumer(): Int = usedSymbol()
}
'@ | Set-Content -LiteralPath $deleteAbs -Encoding UTF8

            $sync = Invoke-Tool -Id 40 -Name "ide_sync_files" -Arguments @{
                project_path = $ProjectPath; paths = @($deleteRel)
            }
            if ($sync.result.isError) { throw "ide_sync_files (scratch delete) failed: $(Get-ToolText $sync)" }
            Wait-SmartMode

            # Safe-delete a symbol that HAS a usage (consumer): must be blocked & non-destructive.
            $before = Get-Content -LiteralPath $deleteAbs -Raw
            $pos = Get-TokenPosition -AbsFile $deleteAbs -LineNeedle "fun usedSymbol()" -Token "usedSymbol"
            $safeDelete = Invoke-Tool -Id 41 -Name "ide_refactor_safe_delete" -Arguments @{
                project_path = $ProjectPath; file = $deleteRel; line = $pos.line; column = $pos.column
            }
            Assert-ToolOk -Name "ide_refactor_safe_delete blocked by usage" -Response $safeDelete -Predicate {
                param($json) $json.canDelete -eq $false -and $json.usageCount -ge 1
            }
            $after = Get-Content -LiteralPath $deleteAbs -Raw
            if ($before -ne $after) { throw "ide_refactor_safe_delete (blocked) mutated the file (should be non-destructive)." }
            Write-Host "PASS ide_refactor_safe_delete blocked path is non-destructive"
        }
    } finally {
        Remove-Scratch
        # Let the IDE forget the deleted scratch files.
        try { Invoke-Tool -Id 99 -Name "ide_sync_files" -Arguments @{ project_path = $ProjectPath } | Out-Null } catch { }
    }
}

Write-Host "All IntelliJ live smoke checks passed."
