# IDE Index MCP Server — EDT Freeze Watchdog
# Installed by the IDE Index MCP plugin. Do not edit — reinstall to update.
#
# Detects frozen IntelliJ EDT (Event Dispatch Thread) via jstack and
# auto-restarts the IDE. The MCP HTTP server stays responsive when the
# EDT is frozen, so HTTP health checks cannot detect UI freezes — only
# jstack thread analysis can.
#
# Run via Task Scheduler every minute (with 30s repeat interval).

$Jstack = "__JSTACK_PATH__"
$IdeLaunchCmd = "__IDE_LAUNCH_CMD__"
$IdePidPattern = "__IDE_PID_PATTERN__"
$FailThreshold = 2
$WatchdogDir = Join-Path $env:USERPROFILE ".ide-index-mcp\watchdog"
$StateFile = Join-Path $WatchdogDir "failures"
$LogFile = Join-Path $WatchdogDir "watchdog.log"

if (-not (Test-Path $WatchdogDir)) {
    New-Item -ItemType Directory -Path $WatchdogDir -Force | Out-Null
}

function Log($msg) {
    "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss'): $msg" | Out-File -Append -Encoding UTF8 $LogFile
}

function Get-Failures {
    if (Test-Path $StateFile) {
        try { [int](Get-Content $StateFile -ErrorAction Stop) } catch { 0 }
    } else { 0 }
}

function Set-Failures($n) { $n | Out-File -Encoding UTF8 $StateFile }

# Trim log to last 1000 lines periodically
if ((Test-Path $LogFile) -and ((Get-Content $LogFile | Measure-Object -Line).Lines -gt 2000)) {
    Get-Content $LogFile | Select-Object -Last 1000 | Out-File -Encoding UTF8 $LogFile
}

# Find IDE process
$ideProcess = Get-Process | Where-Object { $_.ProcessName -match $IdePidPattern } | Select-Object -First 1
if (-not $ideProcess) {
    Set-Failures 0
    exit
}

# Verify jstack exists
if (-not (Test-Path $Jstack)) {
    exit
}

# jstack the process
try {
    $jstackJob = Start-Job -ScriptBlock {
        param($j, $p) & $j $p 2>&1 | Out-String
    } -ArgumentList $Jstack, $ideProcess.Id
    $completed = Wait-Job $jstackJob -Timeout 10
    if (-not $completed) {
        Stop-Job $jstackJob
        Remove-Job $jstackJob -Force
        Log "jstack timed out — process may be frozen"
        exit
    }
    $jstackOutput = Receive-Job $jstackJob
    Remove-Job $jstackJob
} catch {
    exit
}

# Extract EDT thread block
$edtMatch = [regex]::Match($jstackOutput, '(?s)(AWT-EventQueue.*?)(?=\r?\n\r?\n|\r?\n")')
if (-not $edtMatch.Success) { exit }
$edtDump = $edtMatch.Value

# Check if EDT is in normal idle state
if ($edtDump -match "EventQueue\.getNextEvent|NonBlockingFlushQueue") {
    $failures = Get-Failures
    if ($failures -gt 0) {
        Log "Recovered after $failures failure(s)"
        Set-Failures 0
    }
    exit
}

# Check EDT thread state — only flag WAITING or BLOCKED
$edtFirstLine = ($edtDump -split "`n")[0]
if ($edtFirstLine -notmatch "WAITING|BLOCKED|parking|waiting on") {
    # EDT is RUNNABLE — doing work, not frozen
    Set-Failures 0
    exit
}

# EDT is frozen
$failures = (Get-Failures) + 1
$topFrame = ($edtDump -split "`n" | Where-Object { $_ -match "at " } | Select-Object -First 1)
if ($topFrame) { $topFrame = $topFrame.Trim() }
Set-Failures $failures
Log "EDT frozen: $topFrame ($failures/$FailThreshold)"

if ($failures -ge $FailThreshold) {
    Log "RESTARTING IDE — EDT frozen for $FailThreshold consecutive checks"
    Stop-Process -Id $ideProcess.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    Start-Process -FilePath $IdeLaunchCmd
    Log "IDE relaunched"
    Set-Failures 0
}
