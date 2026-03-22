param(
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$OutCsvPath = "",
    [double]$PressureThreshold = 1.0,
    [int]$MinPressureSamplesForEvaluation = 10,
    [double]$MinSimDistanceDropRatioUnderPressure = 0.05,
    [double]$MinNavRunRatioUnderPressure = 0.15,
    [double]$MaxNavRunRatioUnderPressure = 0.95,
    [double]$MinNavCadenceForRunRatioCheck = 1.05,
    [switch]$FailOnIssues
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Parse-DoubleValue {
    param(
        [string]$Value,
        [double]$Fallback = 0.0
    )
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $Fallback
    }
    $parsed = 0.0
    $normalized = $Value.Replace(",", ".")
    if ([double]::TryParse(
            $normalized,
            [System.Globalization.NumberStyles]::Float,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [ref]$parsed
        )) {
        return $parsed
    }
    return $Fallback
}

function Get-Average {
    param([double[]]$Values)
    if ($null -eq $Values -or $Values.Count -eq 0) {
        return [double]::NaN
    }
    return (($Values | Measure-Object -Average).Average)
}

if (-not (Test-Path -LiteralPath $MetricsPath)) {
    throw "Metrics file not found: $MetricsPath"
}
if ($MinPressureSamplesForEvaluation -lt 1) {
    throw "MinPressureSamplesForEvaluation must be >= 1"
}

$rows = @(Import-Csv -LiteralPath $MetricsPath)
if (-not $rows -or $rows.Count -eq 0) {
    throw "Metrics file is empty: $MetricsPath"
}

$requiredColumns = @(
    "server_mitigation_tier",
    "sim_distance_applied",
    "sim_distance_base",
    "mob_target_run_ratio",
    "mob_goal_run_ratio",
    "mob_nav_run_ratio"
)
$availableColumns = $rows[0].PSObject.Properties.Name
$missingColumns = @($requiredColumns | Where-Object { $availableColumns -notcontains $_ })

$issues = New-Object System.Collections.Generic.List[string]
$overallStatus = "pass"
$samples = $rows.Count
$mitigationActiveSamples = 0
$emergencySamples = 0
$simDistanceDropSamples = 0
$simDistanceDropSamplesUnderPressure = 0
$simDistanceDropRatioUnderPressure = [double]::NaN
$targetRunRatioAvgUnderPressure = [double]::NaN
$goalRunRatioAvgUnderPressure = [double]::NaN
$navRunRatioAvgUnderPressure = [double]::NaN
$navCadenceAvgUnderPressure = [double]::NaN

if ($missingColumns.Count -gt 0) {
    $overallStatus = "skipped"
    $issues.Add(("missing telemetry columns: {0}" -f ($missingColumns -join ", ")))
} else {
    $targetRunRatiosUnderPressure = New-Object System.Collections.Generic.List[double]
    $goalRunRatiosUnderPressure = New-Object System.Collections.Generic.List[double]
    $navRunRatiosUnderPressure = New-Object System.Collections.Generic.List[double]
    $navCadenceUnderPressure = New-Object System.Collections.Generic.List[double]
    $hasEmergencyColumn = $availableColumns -contains "server_emergency_ticks"
    $hasNavCadenceColumn = $availableColumns -contains "mob_navigation_cadence"

    foreach ($row in $rows) {
        $mitigationTier = Parse-DoubleValue -Value $row.server_mitigation_tier -Fallback 0.0
        $simDistanceApplied = Parse-DoubleValue -Value $row.sim_distance_applied -Fallback 0.0
        $simDistanceBase = Parse-DoubleValue -Value $row.sim_distance_base -Fallback 0.0
        $targetRunRatio = Parse-DoubleValue -Value $row.mob_target_run_ratio -Fallback 1.0
        $goalRunRatio = Parse-DoubleValue -Value $row.mob_goal_run_ratio -Fallback 1.0
        $navRunRatio = Parse-DoubleValue -Value $row.mob_nav_run_ratio -Fallback 1.0
        $navCadence = if ($hasNavCadenceColumn) {
            Parse-DoubleValue -Value $row.mob_navigation_cadence -Fallback 1.0
        } else {
            1.0
        }
        $emergencyTicks = if ($hasEmergencyColumn) {
            Parse-DoubleValue -Value $row.server_emergency_ticks -Fallback 0.0
        } else {
            0.0
        }

        if ($simDistanceApplied -lt $simDistanceBase) {
            $simDistanceDropSamples++
        }

        if ($emergencyTicks -gt 0.0) {
            $emergencySamples++
        }

        if ($mitigationTier -ge $PressureThreshold) {
            $mitigationActiveSamples++
            if ($simDistanceApplied -lt $simDistanceBase) {
                $simDistanceDropSamplesUnderPressure++
            }
            $targetRunRatiosUnderPressure.Add($targetRunRatio)
            $goalRunRatiosUnderPressure.Add($goalRunRatio)
            $navRunRatiosUnderPressure.Add($navRunRatio)
            $navCadenceUnderPressure.Add($navCadence)
        }
    }

    if ($mitigationActiveSamples -gt 0) {
        $simDistanceDropRatioUnderPressure = $simDistanceDropSamplesUnderPressure / [double]$mitigationActiveSamples
        $targetRunRatioAvgUnderPressure = Get-Average -Values ([double[]]$targetRunRatiosUnderPressure)
        $goalRunRatioAvgUnderPressure = Get-Average -Values ([double[]]$goalRunRatiosUnderPressure)
        $navRunRatioAvgUnderPressure = Get-Average -Values ([double[]]$navRunRatiosUnderPressure)
        $navCadenceAvgUnderPressure = Get-Average -Values ([double[]]$navCadenceUnderPressure)
    }

    if ($mitigationActiveSamples -lt $MinPressureSamplesForEvaluation) {
        $overallStatus = "skipped"
        $issues.Add(("insufficient pressure samples for evaluation ({0} < {1})" -f $mitigationActiveSamples, $MinPressureSamplesForEvaluation))
    } else {
        if ($simDistanceDropRatioUnderPressure -lt $MinSimDistanceDropRatioUnderPressure) {
            $issues.Add(("sim distance drop ratio under pressure too low ({0:0.###} < {1:0.###})" -f $simDistanceDropRatioUnderPressure, $MinSimDistanceDropRatioUnderPressure))
        }
        $shouldCheckNavRunRatio = $true
        if ($hasNavCadenceColumn -and -not [double]::IsNaN($navCadenceAvgUnderPressure) -and $navCadenceAvgUnderPressure -lt $MinNavCadenceForRunRatioCheck) {
            $shouldCheckNavRunRatio = $false
        }

        if ($shouldCheckNavRunRatio) {
            if ($navRunRatioAvgUnderPressure -lt $MinNavRunRatioUnderPressure) {
                $issues.Add(("mob nav run ratio under pressure too low ({0:0.###} < {1:0.###})" -f $navRunRatioAvgUnderPressure, $MinNavRunRatioUnderPressure))
            }
            if ($navRunRatioAvgUnderPressure -gt $MaxNavRunRatioUnderPressure) {
                $issues.Add(("mob nav run ratio under pressure too high ({0:0.###} > {1:0.###})" -f $navRunRatioAvgUnderPressure, $MaxNavRunRatioUnderPressure))
            }
        }
    }

    if ($overallStatus -ne "skipped" -and $issues.Count -gt 0) {
        $overallStatus = "warn"
    }
}

$summary = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    samples = $samples
    pressure_threshold = [Math]::Round($PressureThreshold, 3)
    min_pressure_samples_for_evaluation = $MinPressureSamplesForEvaluation
    mitigation_active_samples = $mitigationActiveSamples
    emergency_samples = $emergencySamples
    sim_distance_drop_samples = $simDistanceDropSamples
    sim_distance_drop_samples_under_pressure = $simDistanceDropSamplesUnderPressure
    sim_distance_drop_ratio_under_pressure = if ([double]::IsNaN($simDistanceDropRatioUnderPressure)) { "" } else { [Math]::Round($simDistanceDropRatioUnderPressure, 4) }
    mob_target_run_ratio_avg_under_pressure = if ([double]::IsNaN($targetRunRatioAvgUnderPressure)) { "" } else { [Math]::Round($targetRunRatioAvgUnderPressure, 4) }
    mob_goal_run_ratio_avg_under_pressure = if ([double]::IsNaN($goalRunRatioAvgUnderPressure)) { "" } else { [Math]::Round($goalRunRatioAvgUnderPressure, 4) }
    mob_nav_run_ratio_avg_under_pressure = if ([double]::IsNaN($navRunRatioAvgUnderPressure)) { "" } else { [Math]::Round($navRunRatioAvgUnderPressure, 4) }
    mob_nav_cadence_avg_under_pressure = if ([double]::IsNaN($navCadenceAvgUnderPressure)) { "" } else { [Math]::Round($navCadenceAvgUnderPressure, 4) }
    min_nav_cadence_for_ratio_check = [Math]::Round($MinNavCadenceForRunRatioCheck, 3)
    issue_count = $issues.Count
    issues = ($issues -join "; ")
    overall_status = $overallStatus
    source = (Resolve-Path -LiteralPath $MetricsPath).Path
}

Write-Host ""
Write-Host "PauC server governor health"
Write-Host "---------------------------"
$summary | Format-List

if (-not [string]::IsNullOrWhiteSpace($OutCsvPath)) {
    $exportExists = Test-Path -LiteralPath $OutCsvPath
    $summary | Export-Csv -LiteralPath $OutCsvPath -NoTypeInformation -Append:$exportExists
    Write-Host ""
    Write-Host ("Server governor summary appended to: {0}" -f $OutCsvPath)
}

if ($FailOnIssues -and $overallStatus -ne "pass") {
    throw ("Server governor health not pass: overall_status={0}; issues={1}" -f $overallStatus, $summary.issues)
}
