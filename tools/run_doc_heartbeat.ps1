param(
    [string]$Message = "Checkpoint heartbeat anti-crash.",
    [string]$Author = "Codex",
    [string]$SuiviPath = ".\SUIVI_SESSIONS_ROADMAP.md",
    [int]$IntervalMinutes = 60,
    [int]$Iterations = 1,
    [switch]$Utc
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($IntervalMinutes -lt 1) {
    throw "IntervalMinutes must be >= 1"
}
if ($Iterations -lt 1) {
    throw "Iterations must be >= 1"
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$checkpointScript = Join-Path $scriptRoot "append_doc_checkpoint.ps1"

for ($i = 1; $i -le $Iterations; $i++) {
    $iterationMessage = if ($Iterations -gt 1) {
        "{0} (heartbeat {1}/{2})" -f $Message, $i, $Iterations
    } else {
        $Message
    }

    $args = @{
        Message = $iterationMessage
        Author = $Author
        Status = "in_progress"
        SuiviPath = $SuiviPath
    }
    if ($Utc) {
        $args.Utc = $true
    }
    & $checkpointScript @args

    if ($i -lt $Iterations) {
        Start-Sleep -Seconds ($IntervalMinutes * 60)
    }
}

Write-Host ("Heartbeat completed: {0} checkpoint(s) written." -f $Iterations)
