param(
    [string]$ResultsPath = ".\RESULTATS_TESTS_AB_PAUC.csv",
    [string]$OutCsvPath = "",
    [string[]]$ExpectedScenes = @("scene_1_village", "scene_2_fast_move", "scene_3_combat_particles", "scene_4_modded_base"),
    [string[]]$RequiredProfiles = @("A_baseline", "B1_stable", "B2_aggressive"),
    [switch]$FailIfIncomplete,
    [switch]$PassThru
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

$matrixRows = New-Object System.Collections.Generic.List[object]
$missingRows = New-Object System.Collections.Generic.List[object]
$filledCells = 0

foreach ($scene in $ExpectedScenes) {
    foreach ($profile in $RequiredProfiles) {
        $cellRows = @($rows | Where-Object { $_.scene -eq $scene -and $_.profile -eq $profile })
        $filled = $false
        $fpsAvg = ""
        $fps1pct = ""

        foreach ($cellRow in $cellRows) {
            if (Is-Number $cellRow.fps_avg) {
                $filled = $true
                $fpsAvg = $cellRow.fps_avg
                $fps1pct = $cellRow.fps_1pct_low
                break
            }
        }

        if ($filled) {
            $filledCells++
        } else {
            $missingRows.Add([PSCustomObject]@{
                    scene = $scene
                    profile = $profile
                })
        }

        $matrixRows.Add([PSCustomObject]@{
                scene = $scene
                profile = $profile
                rows_found = $cellRows.Count
                filled = $filled
                fps_avg = $fpsAvg
                fps_1pct_low = $fps1pct
            })
    }
}

$totalCells = $ExpectedScenes.Count * $RequiredProfiles.Count
$missingCells = $totalCells - $filledCells
$completionPercent = [Math]::Round((100.0 * $filledCells) / [Math]::Max(1, $totalCells), 1)
$overallStatus = if ($missingCells -eq 0) { "pass" } else { "incomplete" }
$nextMissing = if ($missingRows.Count -gt 0) { $missingRows[0] } else { $null }

$nextStartCommand = ""
$nextDirectCommand = ""
$nextPrepareCommand = ".\tools\ab_campaign_next.ps1 -ApplyProfile -StartCapture"
$nextFinishCommand = ".\tools\ab_mark_finish.ps1"
if ($null -ne $nextMissing) {
    $nextStartCommand = ".\tools\ab_mark_start.ps1 -Scene {0} -Profile {1}" -f $nextMissing.scene, $nextMissing.profile
    $nextDirectCommand = ".\tools\append_ab_result_from_metrics.ps1 -Scene {0} -Profile {1} -LastSeconds 180" -f $nextMissing.scene, $nextMissing.profile
}

$summary = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    expected_scenes = $ExpectedScenes.Count
    required_profiles = ($RequiredProfiles -join "|")
    total_cells = $totalCells
    filled_cells = $filledCells
    missing_cells = $missingCells
    completion_percent = $completionPercent
    next_scene = if ($null -eq $nextMissing) { "" } else { $nextMissing.scene }
    next_profile = if ($null -eq $nextMissing) { "" } else { $nextMissing.profile }
    next_start_command = $nextStartCommand
    next_direct_command = $nextDirectCommand
    next_prepare_command = if ($null -eq $nextMissing) { "" } else { $nextPrepareCommand }
    next_finish_command = if ($null -eq $nextMissing) { "" } else { $nextFinishCommand }
    overall_status = $overallStatus
    source = (Resolve-Path -LiteralPath $ResultsPath).Path
}

Write-Host ""
Write-Host "PauC A/B campaign status"
Write-Host "------------------------"
if (-not $PassThru) {
    $matrixRows | Format-Table -AutoSize
}
Write-Host ""
if (-not $PassThru) {
    $summary | Format-List
}

if (-not [string]::IsNullOrWhiteSpace($OutCsvPath)) {
    $exportExists = Test-Path -LiteralPath $OutCsvPath
    $summary | Export-Csv -LiteralPath $OutCsvPath -NoTypeInformation -Append:$exportExists
    Write-Host ""
    Write-Host ("Campaign summary appended to: {0}" -f $OutCsvPath)
}

if ($FailIfIncomplete -and $missingCells -gt 0) {
    throw ("A/B campaign incomplete: {0}/{1} cells filled ({2}%)." -f $filledCells, $totalCells, $completionPercent)
}

if ($PassThru) {
    Write-Output $summary
}
