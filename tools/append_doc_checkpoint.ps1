param(
    [string]$Message = "Checkpoint automatique de session.",
    [string]$Author = "Codex",
    [string]$Status = "in_progress",
    [string]$SuiviPath = ".\SUIVI_SESSIONS_ROADMAP.md",
    [switch]$Utc
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $SuiviPath)) {
    throw "Suivi file not found: $SuiviPath"
}

$now = if ($Utc) { (Get-Date).ToUniversalTime() } else { Get-Date }
$timeZoneLabel = if ($Utc) { "UTC" } else { "Local" }
$dateLabel = $now.ToString("yyyy-MM-dd")
$timeLabel = $now.ToString("HH:mm:ss")
$statusValue = if ([string]::IsNullOrWhiteSpace($Status)) { "in_progress" } else { $Status }
$messageValue = if ([string]::IsNullOrWhiteSpace($Message)) { "Checkpoint automatique de session." } else { $Message.Trim() }
$authorValue = if ([string]::IsNullOrWhiteSpace($Author)) { "Codex" } else { $Author.Trim() }

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("")
$lines.Add(("## Checkpoint {0} {1} ({2}) - {3}" -f $dateLabel, $timeLabel, $timeZoneLabel, $authorValue))
$lines.Add("")
$lines.Add(("- Statut: {0}" -f $statusValue))
$lines.Add(("- Note: {0}" -f $messageValue))
$lines.Add(("- Prochaine action: poursuivre la roadmap et revalider build/tests pertinents."))

Add-Content -LiteralPath $SuiviPath -Value $lines

Write-Host ("Checkpoint appended to: {0}" -f (Resolve-Path -LiteralPath $SuiviPath).Path)
Write-Host ("Timestamp: {0} {1} ({2})" -f $dateLabel, $timeLabel, $timeZoneLabel)
