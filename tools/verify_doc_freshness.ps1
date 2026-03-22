param(
    [string]$SuiviPath = ".\SUIVI_SESSIONS_ROADMAP.md",
    [int]$MaxAgeMinutes = 60,
    [switch]$FailIfStale
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($MaxAgeMinutes -lt 1) {
    throw "MaxAgeMinutes must be >= 1"
}
if (-not (Test-Path -LiteralPath $SuiviPath)) {
    throw "Suivi file not found: $SuiviPath"
}

$content = Get-Content -LiteralPath $SuiviPath
$regex = [regex]::new('^## Checkpoint (\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}) \((UTC|Local)\) - (.+)$')
$latestCheckpoint = $null

foreach ($line in $content) {
    $match = $regex.Match($line)
    if (-not $match.Success) {
        continue
    }

    $datePart = $match.Groups[1].Value
    $timePart = $match.Groups[2].Value
    $zonePart = $match.Groups[3].Value
    $authorPart = $match.Groups[4].Value
    $dateTimeText = "$datePart $timePart"

    $parsed = [datetime]::ParseExact($dateTimeText, "yyyy-MM-dd HH:mm:ss", [System.Globalization.CultureInfo]::InvariantCulture)
    $checkpointUtc = if ($zonePart -eq "UTC") {
        [datetime]::SpecifyKind($parsed, [System.DateTimeKind]::Utc)
    } else {
        [datetime]::SpecifyKind($parsed, [System.DateTimeKind]::Local).ToUniversalTime()
    }

    if ($null -eq $latestCheckpoint -or $checkpointUtc -gt $latestCheckpoint.utc) {
        $latestCheckpoint = [PSCustomObject]@{
            utc = $checkpointUtc
            zone = $zonePart
            author = $authorPart
            raw = $line
        }
    }
}

if ($null -eq $latestCheckpoint) {
    $message = "No checkpoint entries found in $SuiviPath"
    if ($FailIfStale) {
        throw $message
    }
    Write-Host $message
    exit 0
}

$nowUtc = (Get-Date).ToUniversalTime()
$ageMinutes = [math]::Round(($nowUtc - $latestCheckpoint.utc).TotalMinutes, 2)
$isFresh = $ageMinutes -le $MaxAgeMinutes
$status = if ($isFresh) { "fresh" } else { "stale" }

Write-Host ""
Write-Host "Documentation freshness check"
Write-Host "----------------------------"
Write-Host ("Status: {0}" -f $status)
Write-Host ("Last checkpoint UTC: {0}" -f $latestCheckpoint.utc.ToString("yyyy-MM-dd HH:mm:ss"))
Write-Host ("Last checkpoint author: {0}" -f $latestCheckpoint.author)
Write-Host ("Age minutes: {0}" -f $ageMinutes)
Write-Host ("Threshold minutes: {0}" -f $MaxAgeMinutes)

if (-not $isFresh -and $FailIfStale) {
    throw ("Documentation checkpoint stale: age={0}m threshold={1}m" -f $ageMinutes, $MaxAgeMinutes)
}
