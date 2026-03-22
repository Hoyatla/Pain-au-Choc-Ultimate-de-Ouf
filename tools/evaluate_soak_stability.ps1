param(
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$OutCsvPath = "",
    [int]$MinSessionSamples = 240,
    [int]$MinSessionDurationSeconds = 480,
    [double]$MaxQualityLevelTransitionsPerMinute = 1.0,
    [double]$MaxQualityTargetTransitionsPerMinute = 1.0,
    [double]$MaxAutoQualityAdjustmentsPerMinute = 1.0,
    [double]$MaxSimDistanceTransitionsPerMinute = 1.2,
    [double]$MaxSimDistanceAdjustmentsPerMinute = 1.2,
    [double]$MaxSimDistanceOscillationsPerMinute = 0.6,
    [double]$MaxStreamRadiusTransitionsPerMinute = 0.4,
    [double]$MaxDeferredActiveTogglesPerMinute = 0.3,
    [double]$MaxShaderRouteTransitionsPerMinute = 0.3,
    [switch]$FailOnIssues
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($MinSessionSamples -lt 1) {
    throw "MinSessionSamples must be >= 1"
}
if ($MinSessionDurationSeconds -lt 0) {
    throw "MinSessionDurationSeconds must be >= 0"
}

function Try-ParseInvariantDouble {
    param(
        [object]$Value,
        [ref]$ParsedValue
    )
    $ParsedValue.Value = 0.0
    if ($null -eq $Value) {
        return $false
    }
    $raw = $Value.ToString().Trim()
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return $false
    }
    $normalized = $raw.Replace(",", ".")
    $localParsed = 0.0
    $ok = [double]::TryParse(
        $normalized,
        [System.Globalization.NumberStyles]::Float,
        [System.Globalization.CultureInfo]::InvariantCulture,
        [ref]$localParsed
    )
    if ($ok) {
        $ParsedValue.Value = $localParsed
    }
    return $ok
}

function Try-ParseMetricsTimestamp {
    param(
        [object]$Value,
        [ref]$ParsedTimestamp
    )
    $ParsedTimestamp.Value = [datetime]::MinValue
    if ($null -eq $Value) {
        return $false
    }
    $raw = $Value.ToString().Trim()
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return $false
    }

    $formats = @(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss.fff",
        "yyyy-MM-ddTHH:mm:ss",
        "yyyy-MM-ddTHH:mm:ss.fff",
        "yyyy-MM-ddTHH:mm:ssZ",
        "yyyy-MM-ddTHH:mm:ss.fffZ"
    )
    $localTimestamp = [datetime]::MinValue
    if ([datetime]::TryParseExact(
            $raw,
            $formats,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::AllowWhiteSpaces,
            [ref]$localTimestamp
        )) {
        $ParsedTimestamp.Value = $localTimestamp
        return $true
    }

    $ok = [datetime]::TryParse(
        $raw,
        [System.Globalization.CultureInfo]::InvariantCulture,
        [System.Globalization.DateTimeStyles]::AllowWhiteSpaces,
        [ref]$localTimestamp
    )
    if ($ok) {
        $ParsedTimestamp.Value = $localTimestamp
    }
    return $ok
}

function Select-LatestMetricsSessionRows {
    param(
        [object[]]$Rows,
        [double]$MaxTimestampGapSeconds = 15.0
    )

    if ($null -eq $Rows -or $Rows.Count -eq 0) {
        return [PSCustomObject]@{
            rows = @()
            applied = $false
            reason = "empty"
            start_timestamp_utc = ""
            end_timestamp_utc = ""
        }
    }

    $hasTimestampColumn = ($Rows[0].PSObject.Properties.Name -contains "timestamp")
    if ($hasTimestampColumn) {
        $selectedByTimestamp = New-Object System.Collections.Generic.List[object]
        $lastAcceptedTimestamp = [datetime]::MinValue
        $hasLastAcceptedTimestamp = $false

        for ($i = $Rows.Count - 1; $i -ge 0; $i--) {
            $row = $Rows[$i]
            $currentTimestamp = [datetime]::MinValue
            if (-not (Try-ParseMetricsTimestamp -Value $row.timestamp -ParsedTimestamp ([ref]$currentTimestamp))) {
                if ($selectedByTimestamp.Count -gt 0) {
                    break
                }
                continue
            }

            if (-not $hasLastAcceptedTimestamp) {
                $selectedByTimestamp.Add($row)
                $lastAcceptedTimestamp = $currentTimestamp
                $hasLastAcceptedTimestamp = $true
                continue
            }

            $gapSeconds = ($lastAcceptedTimestamp - $currentTimestamp).TotalSeconds
            if ($gapSeconds -lt 0.0) {
                break
            }
            if ($gapSeconds -le $MaxTimestampGapSeconds) {
                $selectedByTimestamp.Add($row)
                $lastAcceptedTimestamp = $currentTimestamp
            } else {
                break
            }
        }

        if ($selectedByTimestamp.Count -gt 0) {
            $sessionRows = $selectedByTimestamp.ToArray()
            [Array]::Reverse($sessionRows)

            $startTimestamp = [datetime]::MinValue
            $endTimestamp = [datetime]::MinValue
            $hasStartTimestamp = Try-ParseMetricsTimestamp -Value $sessionRows[0].timestamp -ParsedTimestamp ([ref]$startTimestamp)
            $hasEndTimestamp = Try-ParseMetricsTimestamp -Value $sessionRows[-1].timestamp -ParsedTimestamp ([ref]$endTimestamp)

            return [PSCustomObject]@{
                rows = $sessionRows
                applied = $true
                reason = "latest_timestamp_segment"
                start_timestamp_utc = if ($hasStartTimestamp) { $startTimestamp.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") } else { "" }
                end_timestamp_utc = if ($hasEndTimestamp) { $endTimestamp.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") } else { "" }
            }
        }
    }

    if (-not ($Rows[0].PSObject.Properties.Name -contains "session_seconds")) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = "timestamp_and_session_seconds_unavailable"
            start_timestamp_utc = ""
            end_timestamp_utc = ""
        }
    }

    $selectedBySession = New-Object System.Collections.Generic.List[object]
    $lastSessionSeconds = 0.0
    $hasLastSessionSeconds = $false

    for ($i = $Rows.Count - 1; $i -ge 0; $i--) {
        $row = $Rows[$i]
        $currentSeconds = 0.0
        if (-not (Try-ParseInvariantDouble -Value $row.session_seconds -ParsedValue ([ref]$currentSeconds))) {
            if ($selectedBySession.Count -gt 0) {
                break
            }
            continue
        }

        if (-not $hasLastSessionSeconds) {
            $selectedBySession.Add($row)
            $lastSessionSeconds = $currentSeconds
            $hasLastSessionSeconds = $true
            continue
        }

        if ($currentSeconds -le ($lastSessionSeconds + 0.001)) {
            $selectedBySession.Add($row)
            $lastSessionSeconds = $currentSeconds
        } else {
            break
        }
    }

    if ($selectedBySession.Count -eq 0) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = "session_seconds_unusable"
            start_timestamp_utc = ""
            end_timestamp_utc = ""
        }
    }

    $sessionRows = $selectedBySession.ToArray()
    [Array]::Reverse($sessionRows)
    return [PSCustomObject]@{
        rows = $sessionRows
        applied = $true
        reason = "latest_session_seconds_segment"
        start_timestamp_utc = ""
        end_timestamp_utc = ""
    }
}

function Normalize-MetricToken {
    param(
        [object]$Value,
        [switch]$NormalizeNumeric,
        [switch]$NormalizeBoolean
    )
    if ($null -eq $Value) {
        return ""
    }

    $raw = $Value.ToString().Trim()
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return ""
    }

    if ($NormalizeBoolean) {
        switch ($raw.ToLowerInvariant()) {
            "true" { return "1" }
            "false" { return "0" }
            "1" { return "1" }
            "0" { return "0" }
        }
        return $raw.ToLowerInvariant()
    }

    if ($NormalizeNumeric) {
        $parsed = 0.0
        if (Try-ParseInvariantDouble -Value $raw -ParsedValue ([ref]$parsed)) {
            return $parsed.ToString("0.######", [System.Globalization.CultureInfo]::InvariantCulture)
        }
    }

    return $raw
}

function Get-TransitionStats {
    param(
        [object[]]$Rows,
        [string]$ColumnName,
        [switch]$NormalizeNumeric,
        [switch]$NormalizeBoolean
    )

    if ($null -eq $Rows -or $Rows.Count -eq 0) {
        return [PSCustomObject]@{
            available = $false
            transitions = 0
            oscillations = 0
            sequence_count = 0
        }
    }
    if (-not ($Rows[0].PSObject.Properties.Name -contains $ColumnName)) {
        return [PSCustomObject]@{
            available = $false
            transitions = 0
            oscillations = 0
            sequence_count = 0
        }
    }

    $sequence = New-Object System.Collections.Generic.List[string]
    $transitions = 0
    $previous = ""
    $hasPrevious = $false

    foreach ($row in $Rows) {
        $current = Normalize-MetricToken -Value $row.$ColumnName -NormalizeNumeric:$NormalizeNumeric -NormalizeBoolean:$NormalizeBoolean
        if (-not $hasPrevious) {
            $sequence.Add($current)
            $previous = $current
            $hasPrevious = $true
            continue
        }

        if ($current -ne $previous) {
            $transitions++
            $sequence.Add($current)
            $previous = $current
        }
    }

    $oscillations = 0
    for ($i = 2; $i -lt $sequence.Count; $i++) {
        if ($sequence[$i] -eq $sequence[$i - 2] -and $sequence[$i] -ne $sequence[$i - 1]) {
            $oscillations++
        }
    }

    return [PSCustomObject]@{
        available = $true
        transitions = $transitions
        oscillations = $oscillations
        sequence_count = $sequence.Count
    }
}

function Get-CounterDelta {
    param(
        [object[]]$Rows,
        [string]$ColumnName
    )

    if ($null -eq $Rows -or $Rows.Count -eq 0) {
        return [PSCustomObject]@{
            available = $false
            delta = 0.0
        }
    }
    if (-not ($Rows[0].PSObject.Properties.Name -contains $ColumnName)) {
        return [PSCustomObject]@{
            available = $false
            delta = 0.0
        }
    }

    $first = 0.0
    $last = 0.0
    $firstOk = Try-ParseInvariantDouble -Value $Rows[0].$ColumnName -ParsedValue ([ref]$first)
    $lastOk = Try-ParseInvariantDouble -Value $Rows[-1].$ColumnName -ParsedValue ([ref]$last)
    if (-not $firstOk -or -not $lastOk) {
        return [PSCustomObject]@{
            available = $false
            delta = 0.0
        }
    }

    return [PSCustomObject]@{
        available = $true
        delta = [Math]::Max(0.0, $last - $first)
    }
}

function Get-RatePerMinute {
    param(
        [double]$Count,
        [double]$DurationSeconds
    )
    if ($DurationSeconds -le 0.0) {
        return 0.0
    }
    return $Count / ($DurationSeconds / 60.0)
}

if (-not (Test-Path -LiteralPath $MetricsPath -PathType Leaf)) {
    throw "Metrics file not found: $MetricsPath"
}

$rows = @(Import-Csv -LiteralPath $MetricsPath)
if ($rows.Count -eq 0) {
    throw "Metrics file is empty: $MetricsPath"
}

$sessionSelection = Select-LatestMetricsSessionRows -Rows $rows
$sessionRows = @($sessionSelection.rows)
$sampleCount = $sessionRows.Count

$sessionStartSeconds = [double]::NaN
$sessionEndSeconds = [double]::NaN
$durationSeconds = [double]::NaN
if ($sampleCount -gt 0 -and ($sessionRows[0].PSObject.Properties.Name -contains "session_seconds")) {
    $startSeconds = 0.0
    $endSeconds = 0.0
    $startOk = Try-ParseInvariantDouble -Value $sessionRows[0].session_seconds -ParsedValue ([ref]$startSeconds)
    $endOk = Try-ParseInvariantDouble -Value $sessionRows[-1].session_seconds -ParsedValue ([ref]$endSeconds)
    if ($startOk -and $endOk) {
        $sessionStartSeconds = $startSeconds
        $sessionEndSeconds = $endSeconds
        $durationSeconds = [Math]::Max(0.0, $endSeconds - $startSeconds)
    }
}
if ([double]::IsNaN($durationSeconds) -and -not [string]::IsNullOrWhiteSpace($sessionSelection.start_timestamp_utc) -and -not [string]::IsNullOrWhiteSpace($sessionSelection.end_timestamp_utc)) {
    $startUtc = [datetime]::MinValue
    $endUtc = [datetime]::MinValue
    $startOk = [datetime]::TryParse($sessionSelection.start_timestamp_utc, [ref]$startUtc)
    $endOk = [datetime]::TryParse($sessionSelection.end_timestamp_utc, [ref]$endUtc)
    if ($startOk -and $endOk) {
        $durationSeconds = [Math]::Max(0.0, ($endUtc - $startUtc).TotalSeconds)
    }
}
if ([double]::IsNaN($durationSeconds)) {
    $durationSeconds = if ($sampleCount -gt 0) { [Math]::Max(0.0, [double]($sampleCount - 1)) } else { 0.0 }
}

$issues = New-Object System.Collections.Generic.List[string]
$overallStatus = "pass"

if ($sampleCount -lt $MinSessionSamples) {
    $overallStatus = "skipped"
    $issues.Add(("insufficient session samples ({0} < {1})" -f $sampleCount, $MinSessionSamples))
}
if ($durationSeconds -lt $MinSessionDurationSeconds) {
    $overallStatus = "skipped"
    $issues.Add(("insufficient session duration ({0:0.###}s < {1}s)" -f $durationSeconds, $MinSessionDurationSeconds))
}

$keyColumns = @(
    "quality_level",
    "quality_target",
    "auto_quality_adjustments",
    "sim_distance_applied",
    "sim_distance_adjustments",
    "stream_radius"
)
$missingColumns = New-Object System.Collections.Generic.List[string]
if ($sampleCount -gt 0) {
    $availableColumns = @($sessionRows[0].PSObject.Properties.Name)
    foreach ($column in $keyColumns) {
        if ($availableColumns -notcontains $column) {
            $missingColumns.Add($column)
        }
    }
}
if ($missingColumns.Count -gt 0) {
    $overallStatus = "skipped"
    $issues.Add(("missing telemetry columns: {0}" -f ($missingColumns -join ", ")))
}

$qualityLevelStats = Get-TransitionStats -Rows $sessionRows -ColumnName "quality_level"
$qualityTargetStats = Get-TransitionStats -Rows $sessionRows -ColumnName "quality_target"
$simDistanceStats = Get-TransitionStats -Rows $sessionRows -ColumnName "sim_distance_applied" -NormalizeNumeric
$streamRadiusStats = Get-TransitionStats -Rows $sessionRows -ColumnName "stream_radius" -NormalizeNumeric
$deferredStats = Get-TransitionStats -Rows $sessionRows -ColumnName "deferred_active" -NormalizeBoolean
$shaderRouteStats = Get-TransitionStats -Rows $sessionRows -ColumnName "shader_route"
$autoQualityCounter = Get-CounterDelta -Rows $sessionRows -ColumnName "auto_quality_adjustments"
$simDistanceCounter = Get-CounterDelta -Rows $sessionRows -ColumnName "sim_distance_adjustments"

$qualityLevelTransitionsPerMin = Get-RatePerMinute -Count ([double]$qualityLevelStats.transitions) -DurationSeconds $durationSeconds
$qualityTargetTransitionsPerMin = Get-RatePerMinute -Count ([double]$qualityTargetStats.transitions) -DurationSeconds $durationSeconds
$autoQualityAdjustmentsPerMin = Get-RatePerMinute -Count $autoQualityCounter.delta -DurationSeconds $durationSeconds
$simDistanceTransitionsPerMin = Get-RatePerMinute -Count ([double]$simDistanceStats.transitions) -DurationSeconds $durationSeconds
$simDistanceAdjustmentsPerMin = Get-RatePerMinute -Count $simDistanceCounter.delta -DurationSeconds $durationSeconds
$simDistanceOscillationsPerMin = Get-RatePerMinute -Count ([double]$simDistanceStats.oscillations) -DurationSeconds $durationSeconds
$streamRadiusTransitionsPerMin = Get-RatePerMinute -Count ([double]$streamRadiusStats.transitions) -DurationSeconds $durationSeconds
$deferredActiveTogglesPerMin = Get-RatePerMinute -Count ([double]$deferredStats.transitions) -DurationSeconds $durationSeconds
$shaderRouteTransitionsPerMin = Get-RatePerMinute -Count ([double]$shaderRouteStats.transitions) -DurationSeconds $durationSeconds

if ($overallStatus -ne "skipped") {
    if ($qualityLevelTransitionsPerMin -gt $MaxQualityLevelTransitionsPerMinute) {
        $issues.Add(("quality level transitions too high ({0:0.###}/min > {1:0.###}/min)" -f $qualityLevelTransitionsPerMin, $MaxQualityLevelTransitionsPerMinute))
    }
    if ($qualityTargetTransitionsPerMin -gt $MaxQualityTargetTransitionsPerMinute) {
        $issues.Add(("quality target transitions too high ({0:0.###}/min > {1:0.###}/min)" -f $qualityTargetTransitionsPerMin, $MaxQualityTargetTransitionsPerMinute))
    }
    if ($autoQualityAdjustmentsPerMin -gt $MaxAutoQualityAdjustmentsPerMinute) {
        $issues.Add(("auto quality adjustments too high ({0:0.###}/min > {1:0.###}/min)" -f $autoQualityAdjustmentsPerMin, $MaxAutoQualityAdjustmentsPerMinute))
    }
    if ($simDistanceTransitionsPerMin -gt $MaxSimDistanceTransitionsPerMinute) {
        $issues.Add(("sim distance transitions too high ({0:0.###}/min > {1:0.###}/min)" -f $simDistanceTransitionsPerMin, $MaxSimDistanceTransitionsPerMinute))
    }
    if ($simDistanceAdjustmentsPerMin -gt $MaxSimDistanceAdjustmentsPerMinute) {
        $issues.Add(("sim distance adjustments too high ({0:0.###}/min > {1:0.###}/min)" -f $simDistanceAdjustmentsPerMin, $MaxSimDistanceAdjustmentsPerMinute))
    }
    if ($simDistanceOscillationsPerMin -gt $MaxSimDistanceOscillationsPerMinute) {
        $issues.Add(("sim distance oscillations too high ({0:0.###}/min > {1:0.###}/min)" -f $simDistanceOscillationsPerMin, $MaxSimDistanceOscillationsPerMinute))
    }
    if ($streamRadiusTransitionsPerMin -gt $MaxStreamRadiusTransitionsPerMinute) {
        $issues.Add(("stream radius transitions too high ({0:0.###}/min > {1:0.###}/min)" -f $streamRadiusTransitionsPerMin, $MaxStreamRadiusTransitionsPerMinute))
    }
    if ($deferredStats.available -and $deferredActiveTogglesPerMin -gt $MaxDeferredActiveTogglesPerMinute) {
        $issues.Add(("deferred toggles too high ({0:0.###}/min > {1:0.###}/min)" -f $deferredActiveTogglesPerMin, $MaxDeferredActiveTogglesPerMinute))
    }
    if ($shaderRouteStats.available -and $shaderRouteTransitionsPerMin -gt $MaxShaderRouteTransitionsPerMinute) {
        $issues.Add(("shader route transitions too high ({0:0.###}/min > {1:0.###}/min)" -f $shaderRouteTransitionsPerMin, $MaxShaderRouteTransitionsPerMinute))
    }

    if ($issues.Count -gt 0) {
        $overallStatus = "warn"
    }
}

$result = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    sample_count = $sampleCount
    min_session_samples = $MinSessionSamples
    min_session_duration_seconds = $MinSessionDurationSeconds
    session_duration_seconds = [Math]::Round($durationSeconds, 3)
    session_start_seconds = if ([double]::IsNaN($sessionStartSeconds)) { "" } else { [Math]::Round($sessionStartSeconds, 3) }
    session_end_seconds = if ([double]::IsNaN($sessionEndSeconds)) { "" } else { [Math]::Round($sessionEndSeconds, 3) }
    quality_level_transitions = $qualityLevelStats.transitions
    quality_level_transitions_per_min = [Math]::Round($qualityLevelTransitionsPerMin, 4)
    quality_target_transitions = $qualityTargetStats.transitions
    quality_target_transitions_per_min = [Math]::Round($qualityTargetTransitionsPerMin, 4)
    auto_quality_adjustments_delta = [Math]::Round($autoQualityCounter.delta, 3)
    auto_quality_adjustments_per_min = [Math]::Round($autoQualityAdjustmentsPerMin, 4)
    sim_distance_transitions = $simDistanceStats.transitions
    sim_distance_transitions_per_min = [Math]::Round($simDistanceTransitionsPerMin, 4)
    sim_distance_oscillations = $simDistanceStats.oscillations
    sim_distance_oscillations_per_min = [Math]::Round($simDistanceOscillationsPerMin, 4)
    sim_distance_adjustments_delta = [Math]::Round($simDistanceCounter.delta, 3)
    sim_distance_adjustments_per_min = [Math]::Round($simDistanceAdjustmentsPerMin, 4)
    stream_radius_transitions = $streamRadiusStats.transitions
    stream_radius_transitions_per_min = [Math]::Round($streamRadiusTransitionsPerMin, 4)
    deferred_active_toggles = if ($deferredStats.available) { $deferredStats.transitions } else { "" }
    deferred_active_toggles_per_min = if ($deferredStats.available) { [Math]::Round($deferredActiveTogglesPerMin, 4) } else { "" }
    shader_route_transitions = if ($shaderRouteStats.available) { $shaderRouteStats.transitions } else { "" }
    shader_route_transitions_per_min = if ($shaderRouteStats.available) { [Math]::Round($shaderRouteTransitionsPerMin, 4) } else { "" }
    max_quality_level_transitions_per_min = [Math]::Round($MaxQualityLevelTransitionsPerMinute, 4)
    max_quality_target_transitions_per_min = [Math]::Round($MaxQualityTargetTransitionsPerMinute, 4)
    max_auto_quality_adjustments_per_min = [Math]::Round($MaxAutoQualityAdjustmentsPerMinute, 4)
    max_sim_distance_transitions_per_min = [Math]::Round($MaxSimDistanceTransitionsPerMinute, 4)
    max_sim_distance_adjustments_per_min = [Math]::Round($MaxSimDistanceAdjustmentsPerMinute, 4)
    max_sim_distance_oscillations_per_min = [Math]::Round($MaxSimDistanceOscillationsPerMinute, 4)
    max_stream_radius_transitions_per_min = [Math]::Round($MaxStreamRadiusTransitionsPerMinute, 4)
    max_deferred_active_toggles_per_min = [Math]::Round($MaxDeferredActiveTogglesPerMinute, 4)
    max_shader_route_transitions_per_min = [Math]::Round($MaxShaderRouteTransitionsPerMinute, 4)
    issue_count = $issues.Count
    issues = ($issues -join "; ")
    overall_status = $overallStatus
    source = (Resolve-Path -LiteralPath $MetricsPath).Path
}

Write-Host ""
Write-Host "PauC soak stability gate"
Write-Host "------------------------"
$result | Format-List

if (-not [string]::IsNullOrWhiteSpace($OutCsvPath)) {
    $exportExists = Test-Path -LiteralPath $OutCsvPath
    $result | Export-Csv -LiteralPath $OutCsvPath -NoTypeInformation -Append:$exportExists
    Write-Host ("Soak stability summary appended to: {0}" -f $OutCsvPath)
}

if ($FailOnIssues -and $overallStatus -ne "pass") {
    throw ("Soak stability gate failed: status={0}; issues={1}" -f $overallStatus, $result.issues)
}

