param(
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$OutCsvPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Parse-DoubleValue {
    param(
        [object]$Value,
        [double]$Fallback = 0.0
    )
    if ($null -eq $Value) {
        return $Fallback
    }

    $raw = $Value.ToString().Trim()
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return $Fallback
    }

    $normalized = $raw.Replace(",", ".")
    $parsed = 0.0
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

function To-DoubleArray {
    param(
        [object[]]$Values,
        [double]$Fallback = 0.0
    )

    $buffer = New-Object System.Collections.Generic.List[double]
    foreach ($value in $Values) {
        $buffer.Add((Parse-DoubleValue -Value $value -Fallback $Fallback))
    }
    return [double[]]$buffer
}

function Get-Percentile {
    param(
        [double[]]$SortedValues,
        [double]$Percentile
    )
    if ($SortedValues.Count -eq 0) {
        return [double]::NaN
    }
    if ($SortedValues.Count -eq 1) {
        return $SortedValues[0]
    }

    $p = [Math]::Max(0.0, [Math]::Min(1.0, $Percentile / 100.0))
    $position = ($SortedValues.Count - 1) * $p
    $lowerIndex = [Math]::Floor($position)
    $upperIndex = [Math]::Ceiling($position)
    if ($lowerIndex -eq $upperIndex) {
        return $SortedValues[$lowerIndex]
    }

    $weight = $position - $lowerIndex
    return $SortedValues[$lowerIndex] + (($SortedValues[$upperIndex] - $SortedValues[$lowerIndex]) * $weight)
}

if (-not (Test-Path -LiteralPath $MetricsPath)) {
    throw "Metrics file not found: $MetricsPath"
}

$rows = @(Import-Csv -LiteralPath $MetricsPath)
if (-not $rows -or $rows.Count -eq 0) {
    throw "Metrics file is empty: $MetricsPath"
}

$fpsRaw = To-DoubleArray ($rows | Select-Object -ExpandProperty fps_raw)
$frameMs = To-DoubleArray ($rows | Select-Object -ExpandProperty frame_ms)
$jitterMs = To-DoubleArray ($rows | Select-Object -ExpandProperty jitter_ms)
$mspt = To-DoubleArray ($rows | Select-Object -ExpandProperty mspt_smoothed)
$sessionSeconds = To-DoubleArray ($rows | Select-Object -ExpandProperty session_seconds)
$hasUploadBacklog = ($rows[0].PSObject.Properties.Name -contains "upload_backlog")
$hasVisibleCulled = ($rows[0].PSObject.Properties.Name -contains "visible_culled") -and ($rows[0].PSObject.Properties.Name -contains "visible_sections")
$hasVisibleBlockEntities = ($rows[0].PSObject.Properties.Name -contains "visible_block_entities") -and ($rows[0].PSObject.Properties.Name -contains "global_block_entities")
$hasChunkCompileTelemetry = ($rows[0].PSObject.Properties.Name -contains "chunk_compile_budget_preview") `
    -and ($rows[0].PSObject.Properties.Name -contains "chunk_compile_backpressure") `
    -and ($rows[0].PSObject.Properties.Name -contains "chunk_builder_backpressure") `
    -and ($rows[0].PSObject.Properties.Name -contains "chunk_builder_pending")
$hasDeferredWarnings = ($rows[0].PSObject.Properties.Name -contains "deferred_warnings")
$hasServerMitigation = ($rows[0].PSObject.Properties.Name -contains "server_mitigation_tier")
$hasSimDistanceApplied = ($rows[0].PSObject.Properties.Name -contains "sim_distance_applied")
$hasSimDistanceCooldown = ($rows[0].PSObject.Properties.Name -contains "sim_distance_cooldown")
$hasSimDistanceTps = ($rows[0].PSObject.Properties.Name -contains "sim_distance_last_tps")
$hasSimDistanceAdjustments = ($rows[0].PSObject.Properties.Name -contains "sim_distance_adjustments")
$hasMobCadence = ($rows[0].PSObject.Properties.Name -contains "mob_selector_cadence") -and ($rows[0].PSObject.Properties.Name -contains "mob_navigation_cadence")
$hasMobRunRatios = ($rows[0].PSObject.Properties.Name -contains "mob_target_run_ratio") -and ($rows[0].PSObject.Properties.Name -contains "mob_goal_run_ratio") -and ($rows[0].PSObject.Properties.Name -contains "mob_nav_run_ratio")

if ($hasUploadBacklog) {
    $uploadBacklog = To-DoubleArray ($rows | Select-Object -ExpandProperty upload_backlog)
}
if ($hasVisibleCulled) {
    $visibleSections = To-DoubleArray ($rows | Select-Object -ExpandProperty visible_sections)
    $visibleCulled = To-DoubleArray ($rows | Select-Object -ExpandProperty visible_culled)
}
if ($hasVisibleBlockEntities) {
    $visibleBlockEntities = To-DoubleArray ($rows | Select-Object -ExpandProperty visible_block_entities)
    $globalBlockEntities = To-DoubleArray ($rows | Select-Object -ExpandProperty global_block_entities)
}
if ($hasChunkCompileTelemetry) {
    $chunkCompileBudgetPreview = To-DoubleArray ($rows | Select-Object -ExpandProperty chunk_compile_budget_preview)
    $chunkCompileBackpressure = To-DoubleArray ($rows | Select-Object -ExpandProperty chunk_compile_backpressure)
    $chunkBuilderBackpressure = To-DoubleArray ($rows | Select-Object -ExpandProperty chunk_builder_backpressure)
    $chunkBuilderPending = To-DoubleArray ($rows | Select-Object -ExpandProperty chunk_builder_pending)
}
if ($hasDeferredWarnings) {
    $deferredWarnings = To-DoubleArray ($rows | Select-Object -ExpandProperty deferred_warnings)
}
if ($hasServerMitigation) {
    $serverMitigation = To-DoubleArray ($rows | Select-Object -ExpandProperty server_mitigation_tier)
}
if ($hasSimDistanceApplied) {
    $simDistanceApplied = To-DoubleArray ($rows | Select-Object -ExpandProperty sim_distance_applied)
}
if ($hasSimDistanceCooldown) {
    $simDistanceCooldown = To-DoubleArray ($rows | Select-Object -ExpandProperty sim_distance_cooldown)
}
if ($hasSimDistanceTps) {
    $simDistanceTps = To-DoubleArray ($rows | Select-Object -ExpandProperty sim_distance_last_tps)
}
if ($hasSimDistanceAdjustments) {
    $simDistanceAdjustments = To-DoubleArray ($rows | Select-Object -ExpandProperty sim_distance_adjustments)
}
if ($hasMobCadence) {
    $mobSelectorCadence = To-DoubleArray ($rows | Select-Object -ExpandProperty mob_selector_cadence)
    $mobNavigationCadence = To-DoubleArray ($rows | Select-Object -ExpandProperty mob_navigation_cadence)
}
if ($hasMobRunRatios) {
    $mobTargetRunRatio = To-DoubleArray ($rows | Select-Object -ExpandProperty mob_target_run_ratio)
    $mobGoalRunRatio = To-DoubleArray ($rows | Select-Object -ExpandProperty mob_goal_run_ratio)
    $mobNavRunRatio = To-DoubleArray ($rows | Select-Object -ExpandProperty mob_nav_run_ratio)
}

[Array]::Sort($fpsRaw)
[Array]::Sort($frameMs)
[Array]::Sort($jitterMs)
[Array]::Sort($mspt)
[Array]::Sort($sessionSeconds)
if ($hasUploadBacklog) {
    [Array]::Sort($uploadBacklog)
}
if ($hasDeferredWarnings) {
    [Array]::Sort($deferredWarnings)
}
if ($hasVisibleBlockEntities) {
    [Array]::Sort($visibleBlockEntities)
    [Array]::Sort($globalBlockEntities)
}
if ($hasChunkCompileTelemetry) {
    [Array]::Sort($chunkCompileBudgetPreview)
    [Array]::Sort($chunkCompileBackpressure)
    [Array]::Sort($chunkBuilderBackpressure)
    [Array]::Sort($chunkBuilderPending)
}
if ($hasServerMitigation) {
    [Array]::Sort($serverMitigation)
}
if ($hasSimDistanceApplied) {
    [Array]::Sort($simDistanceApplied)
}
if ($hasSimDistanceCooldown) {
    [Array]::Sort($simDistanceCooldown)
}
if ($hasSimDistanceTps) {
    [Array]::Sort($simDistanceTps)
}
if ($hasSimDistanceAdjustments) {
    [Array]::Sort($simDistanceAdjustments)
}
if ($hasMobCadence) {
    [Array]::Sort($mobSelectorCadence)
    [Array]::Sort($mobNavigationCadence)
}
if ($hasMobRunRatios) {
    [Array]::Sort($mobTargetRunRatio)
    [Array]::Sort($mobGoalRunRatio)
    [Array]::Sort($mobNavRunRatio)
}

$fpsAvg = (($fpsRaw | Measure-Object -Average).Average)
$fps1pctLow = Get-Percentile -SortedValues $fpsRaw -Percentile 1
$frameP95 = Get-Percentile -SortedValues $frameMs -Percentile 95
$frameP99 = Get-Percentile -SortedValues $frameMs -Percentile 99
$jitterP95 = Get-Percentile -SortedValues $jitterMs -Percentile 95
$msptAvg = (($mspt | Measure-Object -Average).Average)
$msptP95 = Get-Percentile -SortedValues $mspt -Percentile 95
$durationSeconds = $sessionSeconds[-1]

$summary = [ordered]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    samples = $rows.Count
    duration_seconds = [Math]::Round($durationSeconds, 3)
    fps_avg = [Math]::Round($fpsAvg, 3)
    fps_1pct_low = [Math]::Round($fps1pctLow, 3)
    frame_ms_p95 = [Math]::Round($frameP95, 3)
    frame_ms_p99 = [Math]::Round($frameP99, 3)
    jitter_ms_p95 = [Math]::Round($jitterP95, 3)
    mspt_avg = [Math]::Round($msptAvg, 3)
    mspt_p95 = [Math]::Round($msptP95, 3)
    source = (Resolve-Path -LiteralPath $MetricsPath).Path
}

if ($hasUploadBacklog) {
    $summary.upload_backlog_avg = [Math]::Round((($uploadBacklog | Measure-Object -Average).Average), 3)
    $summary.upload_backlog_p95 = [Math]::Round((Get-Percentile -SortedValues $uploadBacklog -Percentile 95), 3)
}

if ($hasVisibleCulled) {
    $culledRatios = for ($i = 0; $i -lt $visibleSections.Count; $i++) {
        $visible = [Math]::Max(1.0, $visibleSections[$i])
        $visibleCulled[$i] / $visible
    }
    $culledRatios = [double[]]$culledRatios
    [Array]::Sort($culledRatios)
    $summary.visible_culled_ratio_avg = [Math]::Round((($culledRatios | Measure-Object -Average).Average), 4)
    $summary.visible_culled_ratio_p95 = [Math]::Round((Get-Percentile -SortedValues $culledRatios -Percentile 95), 4)
}
if ($hasVisibleBlockEntities) {
    $summary.visible_block_entities_avg = [Math]::Round((($visibleBlockEntities | Measure-Object -Average).Average), 3)
    $summary.visible_block_entities_p95 = [Math]::Round((Get-Percentile -SortedValues $visibleBlockEntities -Percentile 95), 3)
    $summary.global_block_entities_avg = [Math]::Round((($globalBlockEntities | Measure-Object -Average).Average), 3)
    $summary.global_block_entities_p95 = [Math]::Round((Get-Percentile -SortedValues $globalBlockEntities -Percentile 95), 3)
}
if ($hasChunkCompileTelemetry) {
    $summary.chunk_compile_budget_preview_avg = [Math]::Round((($chunkCompileBudgetPreview | Measure-Object -Average).Average), 3)
    $summary.chunk_compile_budget_preview_p95 = [Math]::Round((Get-Percentile -SortedValues $chunkCompileBudgetPreview -Percentile 95), 3)
    $summary.chunk_compile_backpressure_avg = [Math]::Round((($chunkCompileBackpressure | Measure-Object -Average).Average), 4)
    $summary.chunk_compile_backpressure_p95 = [Math]::Round((Get-Percentile -SortedValues $chunkCompileBackpressure -Percentile 95), 4)
    $summary.chunk_builder_backpressure_avg = [Math]::Round((($chunkBuilderBackpressure | Measure-Object -Average).Average), 4)
    $summary.chunk_builder_backpressure_p95 = [Math]::Round((Get-Percentile -SortedValues $chunkBuilderBackpressure -Percentile 95), 4)
    $summary.chunk_builder_pending_avg = [Math]::Round((($chunkBuilderPending | Measure-Object -Average).Average), 3)
    $summary.chunk_builder_pending_p95 = [Math]::Round((Get-Percentile -SortedValues $chunkBuilderPending -Percentile 95), 3)
}

if ($hasDeferredWarnings) {
    $summary.deferred_warnings_avg = [Math]::Round((($deferredWarnings | Measure-Object -Average).Average), 3)
    $summary.deferred_warnings_p95 = [Math]::Round((Get-Percentile -SortedValues $deferredWarnings -Percentile 95), 3)
}

if ($hasServerMitigation) {
    $summary.server_mitigation_avg = [Math]::Round((($serverMitigation | Measure-Object -Average).Average), 3)
    $summary.server_mitigation_p95 = [Math]::Round((Get-Percentile -SortedValues $serverMitigation -Percentile 95), 3)
}
if ($hasSimDistanceApplied) {
    $summary.sim_distance_applied_avg = [Math]::Round((($simDistanceApplied | Measure-Object -Average).Average), 3)
    $summary.sim_distance_applied_min = [Math]::Round((($simDistanceApplied | Measure-Object -Minimum).Minimum), 3)
}
if ($hasSimDistanceCooldown) {
    $summary.sim_distance_cooldown_avg = [Math]::Round((($simDistanceCooldown | Measure-Object -Average).Average), 3)
    $summary.sim_distance_cooldown_p95 = [Math]::Round((Get-Percentile -SortedValues $simDistanceCooldown -Percentile 95), 3)
}
if ($hasSimDistanceTps) {
    $summary.sim_distance_tps_avg = [Math]::Round((($simDistanceTps | Measure-Object -Average).Average), 3)
    $summary.sim_distance_tps_p05 = [Math]::Round((Get-Percentile -SortedValues $simDistanceTps -Percentile 5), 3)
}
if ($hasSimDistanceAdjustments) {
    $summary.sim_distance_adjustments_max = [Math]::Round((($simDistanceAdjustments | Measure-Object -Maximum).Maximum), 3)
}
if ($hasMobCadence) {
    $summary.mob_selector_cadence_avg = [Math]::Round((($mobSelectorCadence | Measure-Object -Average).Average), 3)
    $summary.mob_navigation_cadence_avg = [Math]::Round((($mobNavigationCadence | Measure-Object -Average).Average), 3)
}
if ($hasMobRunRatios) {
    $summary.mob_target_run_ratio_avg = [Math]::Round((($mobTargetRunRatio | Measure-Object -Average).Average), 4)
    $summary.mob_goal_run_ratio_avg = [Math]::Round((($mobGoalRunRatio | Measure-Object -Average).Average), 4)
    $summary.mob_nav_run_ratio_avg = [Math]::Round((($mobNavRunRatio | Measure-Object -Average).Average), 4)
}

$summary = [PSCustomObject]$summary

Write-Host ""
Write-Host "PauC metrics summary"
Write-Host "--------------------"
$summary | Format-List

if (-not [string]::IsNullOrWhiteSpace($OutCsvPath)) {
    $exportExists = Test-Path -LiteralPath $OutCsvPath
    $summary | Export-Csv -LiteralPath $OutCsvPath -NoTypeInformation -Append:$exportExists
    Write-Host ""
    Write-Host "Summary appended to: $OutCsvPath"
}
