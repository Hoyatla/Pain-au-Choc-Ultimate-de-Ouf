param(
    [Parameter(Mandatory = $true)][string]$CompileLogPath,
    [string]$OutCsvPath = "",
    [int]$MaxWarningCount = 0,
    [switch]$FailOnIssues
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($MaxWarningCount -lt 0) {
    throw "MaxWarningCount must be >= 0"
}

if (-not (Test-Path -LiteralPath $CompileLogPath)) {
    throw "Compile log not found: $CompileLogPath"
}

$lines = @(Get-Content -LiteralPath $CompileLogPath)
if ($lines.Count -eq 0) {
    throw "Compile log is empty: $CompileLogPath"
}

$summaryWarningCount = $null
for ($i = $lines.Count - 1; $i -ge 0; $i--) {
    $line = [string]$lines[$i]
    if ($line -match '^\s*([0-9]+)\s+warning(s)?\s*$') {
        $summaryWarningCount = [int]$Matches[1]
        break
    }
}

$detailWarningLines = @($lines | Where-Object {
        $line = [string]$_
        $line -match '^\s*.+:\s*warning:'
    })

$warningCount = if ($null -ne $summaryWarningCount) {
    $summaryWarningCount
} else {
    $detailWarningLines.Count
}

$issues = New-Object System.Collections.Generic.List[string]
$overallStatus = "pass"
if ($warningCount -gt $MaxWarningCount) {
    $overallStatus = "warn"
    $issues.Add(("compile warnings exceed threshold: {0} > {1}" -f $warningCount, $MaxWarningCount))
}

$warningPreview = @($detailWarningLines | Select-Object -First 3) -join " | "

$result = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    warning_count = $warningCount
    max_warning_count = $MaxWarningCount
    detail_warning_lines = $detailWarningLines.Count
    issue_count = $issues.Count
    issues = ($issues -join "; ")
    warning_preview = $warningPreview
    overall_status = $overallStatus
    source = (Resolve-Path -LiteralPath $CompileLogPath).Path
}

Write-Host ""
Write-Host "PauC compile warnings gate"
Write-Host "--------------------------"
$result | Format-List

if (-not [string]::IsNullOrWhiteSpace($OutCsvPath)) {
    $exportExists = Test-Path -LiteralPath $OutCsvPath
    $result | Export-Csv -LiteralPath $OutCsvPath -NoTypeInformation -Append:$exportExists
    Write-Host ("Compile warnings summary appended to: {0}" -f $OutCsvPath)
}

if ($FailOnIssues -and $overallStatus -ne "pass") {
    throw ("Compile warnings gate failed: status={0}; issues={1}" -f $overallStatus, $result.issues)
}

