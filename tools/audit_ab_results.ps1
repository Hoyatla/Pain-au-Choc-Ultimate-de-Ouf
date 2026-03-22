param(
    [string]$ResultsPath = ".\RESULTATS_TESTS_AB_PAUC.csv",
    [string]$OutCsvPath = "",
    [string[]]$ExpectedScenes = @("scene_1_village", "scene_2_fast_move", "scene_3_combat_particles", "scene_4_modded_base"),
    [switch]$FailOnIssues
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Is-Number {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $false
    }
    $parsed = 0.0
    return [double]::TryParse(
        $Value,
        [System.Globalization.NumberStyles]::Float,
        [System.Globalization.CultureInfo]::InvariantCulture,
        [ref]$parsed
    )
}

if (-not (Test-Path -LiteralPath $ResultsPath)) {
    throw "Results file not found: $ResultsPath"
}

$rows = @(Import-Csv -LiteralPath $ResultsPath)
if (-not $rows -or $rows.Count -eq 0) {
    throw "Results file is empty: $ResultsPath"
}

$sceneRows = New-Object System.Collections.Generic.List[object]
$issues = New-Object System.Collections.Generic.List[string]

foreach ($scene in $ExpectedScenes) {
    $sceneData = @($rows | Where-Object { $_.scene -eq $scene })
    $baseline = $sceneData | Where-Object { $_.profile -eq "A_baseline" } | Select-Object -First 1
    $baselineFilled = $null -ne $baseline -and (Is-Number $baseline.fps_avg)

    $candidateRows = @($sceneData | Where-Object { $_.profile -like "B*" })
    $candidateFilledCount = 0
    foreach ($candidateRow in $candidateRows) {
        if (Is-Number $candidateRow.fps_avg) {
            $candidateFilledCount++
        }
    }
    $hasCandidate = $candidateFilledCount -gt 0

    if (-not $baselineFilled) {
        $issues.Add("scene '$scene': baseline missing or empty")
    }
    if (-not $hasCandidate) {
        $issues.Add("scene '$scene': no filled B profile")
    }

    $sceneRows.Add([PSCustomObject]@{
            scene = $scene
            baseline_filled = $baselineFilled
            candidate_rows = $candidateRows.Count
            candidate_filled = $candidateFilledCount
            row_count = $sceneData.Count
        })
}

$rowsWithFps = @($rows | Where-Object { Is-Number $_.fps_avg }).Count
$rowsMissingFps = $rows.Count - $rowsWithFps
$overallStatus = if ($issues.Count -eq 0) { "pass" } else { "fail" }
$scenesPass = @($sceneRows | Where-Object { $_.baseline_filled -and $_.candidate_filled -gt 0 }).Count

$summary = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    expected_scenes = $ExpectedScenes.Count
    scenes_checked = $sceneRows.Count
    scenes_pass = $scenesPass
    rows_total = $rows.Count
    rows_with_fps = $rowsWithFps
    rows_missing_fps = $rowsMissingFps
    issue_count = $issues.Count
    overall_status = $overallStatus
    source = (Resolve-Path -LiteralPath $ResultsPath).Path
}

Write-Host ""
Write-Host "PauC A/B matrix audit"
Write-Host "---------------------"
$sceneRows | Format-Table -AutoSize
Write-Host ""
$summary | Format-List

if ($issues.Count -gt 0) {
    Write-Host ""
    Write-Host "Issues:"
    foreach ($issue in $issues) {
        Write-Host ("- {0}" -f $issue)
    }
}

if (-not [string]::IsNullOrWhiteSpace($OutCsvPath)) {
    $exportExists = Test-Path -LiteralPath $OutCsvPath
    $summary | Export-Csv -LiteralPath $OutCsvPath -NoTypeInformation -Append:$exportExists
    Write-Host ""
    Write-Host ("Audit summary appended to: {0}" -f $OutCsvPath)
}

if ($FailOnIssues -and $issues.Count -gt 0) {
    throw ("A/B matrix audit failed: {0} issue(s)" -f $issues.Count)
}
