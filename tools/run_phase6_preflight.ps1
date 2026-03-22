param(
    [string]$ReportDir = ".\run\pauc_reports",
    [string]$ShaderpacksDir = "",
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$PrismRoot = "$env:APPDATA\PrismLauncher\instances",
    [string]$PrismInstanceName = "",
    [switch]$DisableAutoMetricsDiscovery,
    [switch]$UseFullMetricsHistory,
    [int]$MetricsWarmupTrimSeconds = 60,
    [int]$MetricsTailSeconds = 0,
    [int]$MetricsTailSamples = 0,
    [int]$MaxMetricsAgeMinutes = 0,
    [int]$MetricsCodeDriftToleranceMinutes = 2,
    [string]$RequiredTelemetrySchemaVersion = "20260318_shadowv2",
    [switch]$StrictMetricsFreshness,
    [switch]$UseWindowedMetricsForSignalChecks,
    [switch]$SyncTelemetryToRepo,
    [string]$TelemetrySyncDestination = ".\run\pauc_telemetry",
    [bool]$SyncTelemetrySegments = $true,
    [switch]$SyncTelemetryCaptureState,
    [string]$ResultsPath = ".\RESULTATS_TESTS_AB_PAUC.csv",
    [string]$CheckpointSuiviPath = ".\SUIVI_SESSIONS_ROADMAP.md",
    [string]$CheckpointAuthor = "Codex",
    [string]$CheckpointMessage = "Checkpoint preflight QA phase 6.",
    [switch]$WriteDocCheckpoint,
    [switch]$CheckDocFreshness,
    [int]$DocFreshnessMaxAgeMinutes = 60,
    [switch]$StrictDocFreshness,
    [switch]$SkipCompile,
    [switch]$SkipCompileWarningCheck,
    [switch]$SkipShaderCheck,
    [switch]$SkipMetrics,
    [switch]$SkipServerGovernorCheck,
    [switch]$SkipChunkCompileCheck,
    [switch]$SkipDrsDeferredSafetyCheck,
    [switch]$SkipSoakStabilityCheck,
    [switch]$SkipKpiGate,
    [switch]$CheckAbMatrix,
    [switch]$CheckAbProgress,
    [switch]$StrictAbMatrix,
    [switch]$StrictAbProgress,
    [double]$MinAbCompletionPercent = 100.0,
    [switch]$StrictServerGovernor,
    [switch]$StrictChunkCompile,
    [switch]$StrictDrsDeferredSafety,
    [switch]$StrictSoakStability,
    [switch]$StrictCompileWarnings,
    [switch]$StrictKpiGate,
    [switch]$IncludeZip = $true,
    [double]$ServerPressureThreshold = 1.0,
    [int]$MinPressureSamplesForServerGovernor = 10,
    [double]$MinSimDistanceDropRatioUnderPressure = 0.05,
    [double]$MinNavRunRatioUnderPressure = 0.15,
    [double]$MaxNavRunRatioUnderPressure = 0.95,
    [double]$MinChunkCompileBudgetPreviewAvg = 4.0,
    [double]$MaxChunkCompileBackpressureAvg = 0.45,
    [double]$MaxChunkCompileBackpressureP95 = 0.90,
    [double]$MaxChunkBuilderBackpressureAvg = 0.65,
    [double]$MaxChunkBuilderBackpressureP95 = 0.95,
    [double]$MaxChunkBuilderPendingAvg = 18.0,
    [double]$MaxChunkBuilderPendingP95 = 48.0,
    [int]$MinDeferredSamplesForDrsSafety = 5,
    [double]$MaxDrsActiveRatioWhenDeferred = 1.0,
    [double]$MinDeferredSafetyReasonRatio = 0.0,
    [int]$MinSoakSamples = 240,
    [int]$MinSoakDurationSeconds = 480,
    [double]$MaxSoakQualityLevelTransitionsPerMinute = 1.0,
    [double]$MaxSoakQualityTargetTransitionsPerMinute = 1.0,
    [double]$MaxSoakAutoQualityAdjustmentsPerMinute = 1.0,
    [double]$MaxSoakSimDistanceTransitionsPerMinute = 1.2,
    [double]$MaxSoakSimDistanceAdjustmentsPerMinute = 1.2,
    [double]$MaxSoakSimDistanceOscillationsPerMinute = 0.6,
    [double]$MaxSoakStreamRadiusTransitionsPerMinute = 0.4,
    [double]$MaxSoakDeferredTogglesPerMinute = 0.3,
    [double]$MaxSoakShaderRouteTransitionsPerMinute = 0.3,
    [int]$MaxCompileWarningCount = 0,
    [double]$FrameMsP95Max = 20.0,
    [double]$FrameMsP99Max = 60.0,
    [double]$MsptP95Max = 60.0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($MinAbCompletionPercent -lt 0.0 -or $MinAbCompletionPercent -gt 100.0) {
    throw "MinAbCompletionPercent must be between 0 and 100"
}
if ($MinDeferredSamplesForDrsSafety -lt 1) {
    throw "MinDeferredSamplesForDrsSafety must be >= 1"
}
if ($MaxDrsActiveRatioWhenDeferred -lt 0.0 -or $MaxDrsActiveRatioWhenDeferred -gt 1.0) {
    throw "MaxDrsActiveRatioWhenDeferred must be between 0.0 and 1.0"
}
if ($MinDeferredSafetyReasonRatio -lt 0.0 -or $MinDeferredSafetyReasonRatio -gt 1.0) {
    throw "MinDeferredSafetyReasonRatio must be between 0.0 and 1.0"
}
if ($MaxCompileWarningCount -lt 0) {
    throw "MaxCompileWarningCount must be >= 0"
}
if ($MinPressureSamplesForServerGovernor -lt 1) {
    throw "MinPressureSamplesForServerGovernor must be >= 1"
}
if ($MetricsTailSeconds -lt 0) {
    throw "MetricsTailSeconds must be >= 0"
}
if ($MetricsWarmupTrimSeconds -lt 0) {
    throw "MetricsWarmupTrimSeconds must be >= 0"
}
if ($MetricsTailSamples -lt 0) {
    throw "MetricsTailSamples must be >= 0"
}
if ($MaxMetricsAgeMinutes -lt 0) {
    throw "MaxMetricsAgeMinutes must be >= 0"
}
if ($MetricsCodeDriftToleranceMinutes -lt 0) {
    throw "MetricsCodeDriftToleranceMinutes must be >= 0"
}
if ($MinSoakSamples -lt 1) {
    throw "MinSoakSamples must be >= 1"
}
if ($MinSoakDurationSeconds -lt 0) {
    throw "MinSoakDurationSeconds must be >= 0"
}

function Reset-LastExitCode {
    $exitVar = Get-Variable -Name LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue
    if ($null -ne $exitVar) {
        $global:LASTEXITCODE = 0
    }
}

function Get-LastExitCodeOrZero {
    $exitVar = Get-Variable -Name LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue
    if ($null -eq $exitVar) {
        return 0
    }
    return [int]$exitVar.Value
}

function Get-LatestWriteTimeUtc {
    param([string[]]$Paths)

    $latest = [datetime]::MinValue
    if ($null -eq $Paths -or $Paths.Count -eq 0) {
        return $latest
    }

    foreach ($path in $Paths) {
        if ([string]::IsNullOrWhiteSpace($path)) {
            continue
        }
        if (-not (Test-Path -LiteralPath $path)) {
            continue
        }

        $item = Get-Item -LiteralPath $path
        if ($item -is [System.IO.DirectoryInfo]) {
            $candidates = Get-ChildItem -LiteralPath $path -File -Recurse -ErrorAction SilentlyContinue
            foreach ($candidate in $candidates) {
                if ($candidate.LastWriteTimeUtc -gt $latest) {
                    $latest = $candidate.LastWriteTimeUtc
                }
            }
        } else {
            if ($item.LastWriteTimeUtc -gt $latest) {
                $latest = $item.LastWriteTimeUtc
            }
        }
    }

    return $latest
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
            session_start_seconds = [double]::NaN
            session_end_seconds = [double]::NaN
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

            $firstSeconds = 0.0
            $lastSeconds = 0.0
            $firstSecondsOk = $false
            $lastSecondsOk = $false
            if ($sessionRows[0].PSObject.Properties.Name -contains "session_seconds") {
                $firstSecondsOk = Try-ParseInvariantDouble -Value $sessionRows[0].session_seconds -ParsedValue ([ref]$firstSeconds)
                $lastSecondsOk = Try-ParseInvariantDouble -Value $sessionRows[-1].session_seconds -ParsedValue ([ref]$lastSeconds)
            }

            return [PSCustomObject]@{
                rows = $sessionRows
                applied = $true
                reason = "latest_timestamp_segment"
                session_start_seconds = if ($firstSecondsOk) { [Math]::Round($firstSeconds, 3) } else { [double]::NaN }
                session_end_seconds = if ($lastSecondsOk) { [Math]::Round($lastSeconds, 3) } else { [double]::NaN }
            }
        }
    }

    if (-not ($Rows[0].PSObject.Properties.Name -contains "session_seconds")) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = "timestamp_and_session_seconds_unavailable"
            session_start_seconds = [double]::NaN
            session_end_seconds = [double]::NaN
        }
    }

    $selected = New-Object System.Collections.Generic.List[object]
    $lastSessionSeconds = 0.0
    $hasLastSessionSeconds = $false

    for ($i = $Rows.Count - 1; $i -ge 0; $i--) {
        $row = $Rows[$i]
        $currentSeconds = 0.0
        if (-not (Try-ParseInvariantDouble -Value $row.session_seconds -ParsedValue ([ref]$currentSeconds))) {
            if ($selected.Count -gt 0) {
                break
            }
            continue
        }

        if (-not $hasLastSessionSeconds) {
            $selected.Add($row)
            $lastSessionSeconds = $currentSeconds
            $hasLastSessionSeconds = $true
            continue
        }

        if ($currentSeconds -le ($lastSessionSeconds + 0.001)) {
            $selected.Add($row)
            $lastSessionSeconds = $currentSeconds
        } else {
            break
        }
    }

    if ($selected.Count -eq 0) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = "session_seconds_unusable"
            session_start_seconds = [double]::NaN
            session_end_seconds = [double]::NaN
        }
    }

    $sessionRows = $selected.ToArray()
    [Array]::Reverse($sessionRows)

    $firstSeconds = 0.0
    $lastSeconds = 0.0
    $firstSecondsOk = Try-ParseInvariantDouble -Value $sessionRows[0].session_seconds -ParsedValue ([ref]$firstSeconds)
    $lastSecondsOk = Try-ParseInvariantDouble -Value $sessionRows[-1].session_seconds -ParsedValue ([ref]$lastSeconds)

    return [PSCustomObject]@{
        rows = $sessionRows
        applied = $true
        reason = "latest_session_seconds_segment"
        session_start_seconds = if ($firstSecondsOk) { [Math]::Round($firstSeconds, 3) } else { [double]::NaN }
        session_end_seconds = if ($lastSecondsOk) { [Math]::Round($lastSeconds, 3) } else { [double]::NaN }
    }
}

function Select-MetricsTailBySeconds {
    param(
        [object[]]$Rows,
        [int]$TailSeconds
    )

    if ($null -eq $Rows -or $Rows.Count -eq 0 -or $TailSeconds -le 0) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = "not_requested_or_empty"
            from_session_seconds = [double]::NaN
            to_session_seconds = [double]::NaN
        }
    }

    if (-not ($Rows[0].PSObject.Properties.Name -contains "session_seconds")) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = "session_seconds_missing"
            from_session_seconds = [double]::NaN
            to_session_seconds = [double]::NaN
        }
    }

    $endSeconds = 0.0
    if (-not (Try-ParseInvariantDouble -Value $Rows[-1].session_seconds -ParsedValue ([ref]$endSeconds))) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = "end_session_seconds_unusable"
            from_session_seconds = [double]::NaN
            to_session_seconds = [double]::NaN
        }
    }

    $startThreshold = [Math]::Max(0.0, $endSeconds - $TailSeconds)
    $tailRows = New-Object System.Collections.Generic.List[object]
    foreach ($row in $Rows) {
        $currentSeconds = 0.0
        if (-not (Try-ParseInvariantDouble -Value $row.session_seconds -ParsedValue ([ref]$currentSeconds))) {
            continue
        }
        if ($currentSeconds -ge $startThreshold -and $currentSeconds -le ($endSeconds + 0.001)) {
            $tailRows.Add($row)
        }
    }

    if ($tailRows.Count -eq 0) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = "no_rows_in_tail_window"
            from_session_seconds = [Math]::Round($startThreshold, 3)
            to_session_seconds = [Math]::Round($endSeconds, 3)
        }
    }

    return [PSCustomObject]@{
        rows = $tailRows.ToArray()
        applied = $true
        reason = "tail_seconds"
        from_session_seconds = [Math]::Round($startThreshold, 3)
        to_session_seconds = [Math]::Round($endSeconds, 3)
    }
}

function Select-MetricsAfterWarmup {
    param(
        [object[]]$Rows,
        [int]$WarmupTrimSeconds
    )

    if ($null -eq $Rows -or $Rows.Count -eq 0 -or $WarmupTrimSeconds -le 0) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = "not_requested_or_empty"
            trim_before_seconds = [double]::NaN
        }
    }

    if (-not ($Rows[0].PSObject.Properties.Name -contains "session_seconds")) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = "session_seconds_missing"
            trim_before_seconds = [double]::NaN
        }
    }

    $firstSeconds = 0.0
    if (-not (Try-ParseInvariantDouble -Value $Rows[0].session_seconds -ParsedValue ([ref]$firstSeconds))) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = "first_session_seconds_unusable"
            trim_before_seconds = [double]::NaN
        }
    }

    $trimThreshold = [Math]::Max(0.0, $firstSeconds + $WarmupTrimSeconds)
    $trimmedRows = New-Object System.Collections.Generic.List[object]
    foreach ($row in $Rows) {
        $currentSeconds = 0.0
        if (-not (Try-ParseInvariantDouble -Value $row.session_seconds -ParsedValue ([ref]$currentSeconds))) {
            continue
        }
        if ($currentSeconds -ge $trimThreshold) {
            $trimmedRows.Add($row)
        }
    }

    if ($trimmedRows.Count -eq 0) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = "no_rows_after_warmup_trim"
            trim_before_seconds = [Math]::Round($trimThreshold, 3)
        }
    }

    return [PSCustomObject]@{
        rows = $trimmedRows.ToArray()
        applied = $true
        reason = "warmup_trim"
        trim_before_seconds = [Math]::Round($trimThreshold, 3)
    }
}

function Select-MetricsTailBySamples {
    param(
        [object[]]$Rows,
        [int]$TailSamples
    )

    if ($null -eq $Rows -or $Rows.Count -eq 0 -or $TailSamples -le 0) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = "not_requested_or_empty"
        }
    }

    if ($Rows.Count -le $TailSamples) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $true
            reason = "tail_samples_all_rows"
        }
    }

    return [PSCustomObject]@{
        rows = @($Rows | Select-Object -Last $TailSamples)
        applied = $true
        reason = "tail_samples"
    }
}

function Select-RepresentativeMetricsRows {
    param(
        [object[]]$Rows,
        [int]$MinRowsForRepresentativeWindow = 120,
        [double]$MinKeepRatio = 0.60
    )

    if ($null -eq $Rows -or $Rows.Count -eq 0) {
        return [PSCustomObject]@{
            rows = @()
            applied = $false
            reason = "empty"
            dropped_background_cap_samples = 0
            dropped_tier3_crisis_samples = 0
        }
    }

    $filtered = New-Object System.Collections.Generic.List[object]
    $droppedBackgroundCap = 0
    $droppedTier3Crisis = 0
    foreach ($row in $Rows) {
        $frameMs = 0.0
        $fpsSmoothed = 0.0
        $targetFrameMs = 0.0
        $msptSmoothed = 0.0
        $serverPressure = 0.0
        $mitigationTier = 0.0

        $frameOk = Try-ParseInvariantDouble -Value $row.frame_ms -ParsedValue ([ref]$frameMs)
        $fpsOk = Try-ParseInvariantDouble -Value $row.fps_smoothed -ParsedValue ([ref]$fpsSmoothed)
        $targetOk = Try-ParseInvariantDouble -Value $row.target_frame_ms -ParsedValue ([ref]$targetFrameMs)
        $msptOk = Try-ParseInvariantDouble -Value $row.mspt_smoothed -ParsedValue ([ref]$msptSmoothed)
        $serverPressureOk = Try-ParseInvariantDouble -Value $row.server_pressure -ParsedValue ([ref]$serverPressure)
        $mitigationTierOk = Try-ParseInvariantDouble -Value $row.server_mitigation_tier -ParsedValue ([ref]$mitigationTier)
        $governorMode = if ($row.PSObject.Properties.Name -contains "governor_mode") { [string]$row.governor_mode } else { "" }

        $isBackgroundCapSample = $frameOk -and $fpsOk -and $targetOk -and $msptOk -and $serverPressureOk `
            -and $frameMs -ge 49.6 -and $frameMs -le 50.4 `
            -and $fpsSmoothed -ge 19.6 -and $fpsSmoothed -le 20.4 `
            -and $targetFrameMs -gt 0.0 -and $targetFrameMs -le 10.5 `
            -and $msptSmoothed -le 40.0 `
            -and $serverPressure -le 1.0
        if ($isBackgroundCapSample) {
            $droppedBackgroundCap++
            continue
        }

        $isTier3CrisisSample = $serverPressureOk -and $mitigationTierOk `
            -and $serverPressure -ge 3.0 `
            -and $mitigationTier -ge 3.0 `
            -and $governorMode -eq "CRISIS"
        if ($isTier3CrisisSample) {
            $droppedTier3Crisis++
            continue
        }

        $filtered.Add($row)
    }

    $droppedTotal = $droppedBackgroundCap + $droppedTier3Crisis
    if ($droppedTotal -eq 0) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = "no_non_representative_rows"
            dropped_background_cap_samples = 0
            dropped_tier3_crisis_samples = 0
        }
    }

    $keptCount = $filtered.Count
    $requiredRows = [Math]::Min($Rows.Count, [Math]::Max(1, $MinRowsForRepresentativeWindow))
    $keepRatio = if ($Rows.Count -gt 0) { $keptCount / $Rows.Count } else { 0.0 }
    $lowRatioMinRows = [Math]::Max($requiredRows, [int][Math]::Ceiling($requiredRows / [Math]::Max(0.01, $MinKeepRatio)))
    if ($keptCount -lt $requiredRows -or ($keepRatio -lt $MinKeepRatio -and $keptCount -lt $lowRatioMinRows)) {
        return [PSCustomObject]@{
            rows = $Rows
            applied = $false
            reason = ("insufficient_representative_rows kept={0}/{1} ratio={2}" -f $keptCount, $Rows.Count, [Math]::Round($keepRatio, 3))
            dropped_background_cap_samples = $droppedBackgroundCap
            dropped_tier3_crisis_samples = $droppedTier3Crisis
        }
    }

    return [PSCustomObject]@{
        rows = $filtered.ToArray()
        applied = $true
        reason = ("representative_filter bg_cap={0}, tier3_crisis={1}" -f $droppedBackgroundCap, $droppedTier3Crisis)
        dropped_background_cap_samples = $droppedBackgroundCap
        dropped_tier3_crisis_samples = $droppedTier3Crisis
    }
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $scriptRoot "..")).Path
$checkpointScriptPath = Join-Path $scriptRoot "append_doc_checkpoint.ps1"
$docFreshnessScriptPath = Join-Path $scriptRoot "verify_doc_freshness.ps1"
$abAuditScriptPath = Join-Path $scriptRoot "audit_ab_results.ps1"
$abCampaignScriptPath = Join-Path $scriptRoot "ab_campaign_status.ps1"
$serverGovernorScriptPath = Join-Path $scriptRoot "evaluate_server_governor_health.ps1"
$chunkCompileScriptPath = Join-Path $scriptRoot "evaluate_chunk_compile_health.ps1"
$drsDeferredSafetyScriptPath = Join-Path $scriptRoot "evaluate_drs_deferred_safety.ps1"
$soakStabilityScriptPath = Join-Path $scriptRoot "evaluate_soak_stability.ps1"
$compileWarningsScriptPath = Join-Path $scriptRoot "evaluate_compile_warnings.ps1"
$metricsResolverScriptPath = Join-Path $scriptRoot "resolve_pauc_metrics_path.ps1"
$telemetrySyncScriptPath = Join-Path $scriptRoot "sync_pauc_telemetry.ps1"

Push-Location $repoRoot
try {
    if ($WriteDocCheckpoint) {
        & $checkpointScriptPath -Message $CheckpointMessage -Author $CheckpointAuthor -Status "in_progress" -SuiviPath $CheckpointSuiviPath -Utc
    }

    $timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd_HHmmss_fff")
    $resolvedReportDir = Join-Path $repoRoot $ReportDir
    New-Item -ItemType Directory -Path $resolvedReportDir -Force | Out-Null

    $compileLogPath = Join-Path $resolvedReportDir ("preflight_compile_{0}.log" -f $timestamp)
    $compileWarningsLogPath = Join-Path $resolvedReportDir ("preflight_compile_warnings_{0}.log" -f $timestamp)
    $shaderLogPath = Join-Path $resolvedReportDir ("preflight_shader_{0}.log" -f $timestamp)
    $metricsLogPath = Join-Path $resolvedReportDir ("preflight_metrics_{0}.log" -f $timestamp)
    $kpiLogPath = Join-Path $resolvedReportDir ("preflight_kpi_{0}.log" -f $timestamp)
    $serverGovernorLogPath = Join-Path $resolvedReportDir ("preflight_server_governor_{0}.log" -f $timestamp)
    $chunkCompileLogPath = Join-Path $resolvedReportDir ("preflight_chunk_compile_{0}.log" -f $timestamp)
    $drsDeferredSafetyLogPath = Join-Path $resolvedReportDir ("preflight_drs_deferred_safety_{0}.log" -f $timestamp)
    $soakStabilityLogPath = Join-Path $resolvedReportDir ("preflight_soak_stability_{0}.log" -f $timestamp)
    $docLogPath = Join-Path $resolvedReportDir ("preflight_doc_{0}.log" -f $timestamp)
    $abLogPath = Join-Path $resolvedReportDir ("preflight_ab_{0}.log" -f $timestamp)
    $abProgressLogPath = Join-Path $resolvedReportDir ("preflight_ab_progress_{0}.log" -f $timestamp)
    $shaderCsvPath = Join-Path $resolvedReportDir ("preflight_shader_{0}.csv" -f $timestamp)
    $metricsCsvPath = Join-Path $resolvedReportDir ("preflight_metrics_{0}.csv" -f $timestamp)
    $compileWarningsCsvPath = Join-Path $resolvedReportDir ("preflight_compile_warnings_{0}.csv" -f $timestamp)
    $kpiCsvPath = Join-Path $resolvedReportDir ("preflight_kpi_{0}.csv" -f $timestamp)
    $serverGovernorCsvPath = Join-Path $resolvedReportDir ("preflight_server_governor_{0}.csv" -f $timestamp)
    $chunkCompileCsvPath = Join-Path $resolvedReportDir ("preflight_chunk_compile_{0}.csv" -f $timestamp)
    $drsDeferredSafetyCsvPath = Join-Path $resolvedReportDir ("preflight_drs_deferred_safety_{0}.csv" -f $timestamp)
    $soakStabilityCsvPath = Join-Path $resolvedReportDir ("preflight_soak_stability_{0}.csv" -f $timestamp)
    $abCsvPath = Join-Path $resolvedReportDir ("preflight_ab_{0}.csv" -f $timestamp)
    $abProgressCsvPath = Join-Path $resolvedReportDir ("preflight_ab_progress_{0}.csv" -f $timestamp)
    $reportPath = Join-Path $resolvedReportDir ("phase6_preflight_{0}.md" -f $timestamp)

    $compileStatus = "skipped"
    $compileWarningsStatus = "skipped"
    $shaderStatus = "skipped"
    $metricsStatus = "skipped"
    $serverGovernorStatus = "skipped"
    $chunkCompileStatus = "skipped"
    $drsDeferredSafetyStatus = "skipped"
    $soakStabilityStatus = "skipped"
    $kpiStatus = "skipped"
    $docStatus = "skipped"
    $abStatus = "skipped"
    $abProgressStatus = "skipped"
    $abCompletionPercent = $null

    if ($CheckDocFreshness) {
        Write-Host ""
        Write-Host "[Phase6] Documentation freshness check..."
        try {
            $docArgs = @{
                SuiviPath = $CheckpointSuiviPath
                MaxAgeMinutes = $DocFreshnessMaxAgeMinutes
            }
            if ($StrictDocFreshness) {
                $docArgs.FailIfStale = $true
            }
            & $docFreshnessScriptPath @docArgs *>&1 | Tee-Object -FilePath $docLogPath
            $docStatus = "ok"
        } catch {
            $_ | Out-String | Tee-Object -FilePath $docLogPath -Append | Out-Null
            $docStatus = "fail"
        }
    }

    if (-not $SkipCompile) {
        Write-Host ""
        Write-Host "[Phase6] Compile check..."
        try {
            $nativePrefVar = Get-Variable -Name PSNativeCommandUseErrorActionPreference -Scope Global -ErrorAction SilentlyContinue
            $restoreNativePref = $false
            $previousNativePref = $false
            if ($null -ne $nativePrefVar) {
                $previousNativePref = [bool]$nativePrefVar.Value
                $global:PSNativeCommandUseErrorActionPreference = $false
                $restoreNativePref = $true
            }

            try {
                Reset-LastExitCode
                & .\gradlew.bat compileJava -x test *>&1 | Tee-Object -FilePath $compileLogPath
                if ((Get-LastExitCodeOrZero) -eq 0) {
                    $compileStatus = "ok"
                } else {
                    $compileStatus = "fail"
                }
            } finally {
                if ($restoreNativePref) {
                    $global:PSNativeCommandUseErrorActionPreference = $previousNativePref
                }
            }
        } catch {
            $_ | Out-String | Tee-Object -FilePath $compileLogPath -Append | Out-Null
            $compileStatus = "fail"
        }
    }

    if (-not $SkipCompileWarningCheck) {
        if ($compileStatus -eq "ok") {
            if (-not (Test-Path -LiteralPath $compileLogPath)) {
                $compileWarningsStatus = "skipped (compile log missing)"
            } else {
                Write-Host ""
                Write-Host "[Phase6] Compile warnings..."
                try {
                    $compileWarningsArgs = @{
                        CompileLogPath = $compileLogPath
                        OutCsvPath = $compileWarningsCsvPath
                        MaxWarningCount = $MaxCompileWarningCount
                    }
                    if ($StrictCompileWarnings) {
                        $compileWarningsArgs.FailOnIssues = $true
                    }
                    & $compileWarningsScriptPath @compileWarningsArgs *>&1 | Tee-Object -FilePath $compileWarningsLogPath
                    if (Test-Path -LiteralPath $compileWarningsCsvPath) {
                        $compileWarningRows = @(Import-Csv -LiteralPath $compileWarningsCsvPath)
                        if ($compileWarningRows.Count -gt 0) {
                            $compileWarningsStatus = ($compileWarningRows | Select-Object -Last 1).overall_status
                        } else {
                            $compileWarningsStatus = "ok"
                        }
                    } else {
                        $compileWarningsStatus = "ok"
                    }
                } catch {
                    $_ | Out-String | Tee-Object -FilePath $compileWarningsLogPath -Append | Out-Null
                    if (Test-Path -LiteralPath $compileWarningsCsvPath) {
                        $compileWarningRows = @(Import-Csv -LiteralPath $compileWarningsCsvPath)
                        if ($compileWarningRows.Count -gt 0) {
                            $compileWarningsStatus = ($compileWarningRows | Select-Object -Last 1).overall_status
                        } else {
                            $compileWarningsStatus = "fail"
                        }
                    } else {
                        $compileWarningsStatus = "fail"
                    }
                }
            }
        } elseif ($compileStatus -eq "fail") {
            $compileWarningsStatus = "skipped (compile failed)"
        } else {
            $compileWarningsStatus = "skipped (compile skipped)"
        }
    }

    $resolvedShaderpacksDir = $null
    if (-not $SkipShaderCheck) {
        if ([string]::IsNullOrWhiteSpace($ShaderpacksDir)) {
            if (Test-Path -LiteralPath ".\run\shaderpacks") {
                $resolvedShaderpacksDir = (Resolve-Path -LiteralPath ".\run\shaderpacks").Path
            } elseif (Test-Path -LiteralPath ".\shaderpacks") {
                $resolvedShaderpacksDir = (Resolve-Path -LiteralPath ".\shaderpacks").Path
            }
        } else {
            if (Test-Path -LiteralPath $ShaderpacksDir) {
                $resolvedShaderpacksDir = (Resolve-Path -LiteralPath $ShaderpacksDir).Path
            }
        }

        if ($null -eq $resolvedShaderpacksDir) {
            $shaderStatus = "skipped (shaderpacks dir missing)"
        } else {
            Write-Host ""
            Write-Host "[Phase6] Shaderpack compatibility check..."
            try {
                Reset-LastExitCode
                & (Join-Path $scriptRoot "check_shaderpack_compat.ps1") `
                    -ShaderpacksDir $resolvedShaderpacksDir `
                    -OutCsvPath $shaderCsvPath `
                    -IncludeZip:$IncludeZip *>&1 | Tee-Object -FilePath $shaderLogPath
                if ((Get-LastExitCodeOrZero) -eq 0) {
                    $shaderStatus = "ok"
                } else {
                    $shaderStatus = "fail"
                }
            } catch {
                $_ | Out-String | Tee-Object -FilePath $shaderLogPath -Append | Out-Null
                $shaderStatus = "fail"
            }
        }
    }

    $resolvedMetricsPath = $null
    $metricsPathForChecks = $null
    $metricsPathForSignalChecks = $null
    $metricsSource = "missing"
    $metricsWindowSummaryLine = "- Metrics window: n/a"
    $metricsFreshnessStatus = "n/a"
    $metricsFreshnessSummaryLine = "- Metrics freshness: n/a"
    $metricsLatestSampleUtc = [datetime]::MinValue
    $metricsSchemaVersionDetected = ""
    $metricsSchemaIsCurrent = $false
    $metricsSchemaColumnPresent = $false
    $selectedMetricsRows = @()
    $rawMetricsRows = @()
    $metricsSyncSummaryLine = if ($SyncTelemetryToRepo) {
        "- Metrics sync: requested, waiting for metrics source"
    } else {
        "- Metrics sync: disabled"
    }
    $preferPrismMetrics = -not [string]::IsNullOrWhiteSpace($PrismInstanceName)
    $shouldResolveMetrics = -not $DisableAutoMetricsDiscovery `
        -and (Test-Path -LiteralPath $metricsResolverScriptPath -PathType Leaf) `
        -and ($preferPrismMetrics -or -not (Test-Path -LiteralPath $MetricsPath -PathType Leaf))
    if ($shouldResolveMetrics) {
        try {
            $metricsResolution = & $metricsResolverScriptPath `
                -PreferredPath $MetricsPath `
                -PrismRoot $PrismRoot `
                -InstanceName $PrismInstanceName `
                -SearchAllPrismInstances:$true `
                -PassThru

            if ($metricsResolution -is [System.Array]) {
                $metricsResolution = $metricsResolution | Select-Object -Last 1
            }

            if ($null -ne $metricsResolution -and [bool]$metricsResolution.resolved) {
                $candidateMetricsPath = [string]$metricsResolution.metrics_path
                if (-not [string]::IsNullOrWhiteSpace($candidateMetricsPath) -and (Test-Path -LiteralPath $candidateMetricsPath -PathType Leaf)) {
                    $resolvedMetricsPath = (Resolve-Path -LiteralPath $candidateMetricsPath).Path
                    $metricsSource = [string]$metricsResolution.source
                }
            }
        } catch {
            $_ | Out-String | Tee-Object -FilePath $metricsLogPath -Append | Out-Null
        }
    }
    if ($null -eq $resolvedMetricsPath -and (Test-Path -LiteralPath $MetricsPath -PathType Leaf)) {
        $resolvedMetricsPath = (Resolve-Path -LiteralPath $MetricsPath).Path
        $metricsSource = "input"
    }

    if ($SyncTelemetryToRepo) {
        if ($null -eq $resolvedMetricsPath) {
            $metricsSyncSummaryLine = "- Metrics sync: requested but source unavailable"
        } elseif (-not (Test-Path -LiteralPath $telemetrySyncScriptPath -PathType Leaf)) {
            $metricsSyncSummaryLine = "- Metrics sync: requested but sync script missing"
        } else {
            try {
                $syncArgs = @{
                    MetricsPath = $resolvedMetricsPath
                    DestinationDir = $TelemetrySyncDestination
                    PrismRoot = $PrismRoot
                    InstanceName = $PrismInstanceName
                    CopySegments = $SyncTelemetrySegments
                    Force = $false
                    PassThru = $true
                }
                if ($DisableAutoMetricsDiscovery) {
                    $syncArgs.DisableAutoMetricsDiscovery = $true
                }
                if ($SyncTelemetryCaptureState) {
                    $syncArgs.IncludeCaptureState = $true
                }

                $syncResult = & $telemetrySyncScriptPath @syncArgs
                if ($syncResult -is [System.Array]) {
                    $syncResult = $syncResult | Select-Object -Last 1
                }

                $syncTargetPath = if ($null -eq $syncResult) { "" } else { [string]$syncResult.target_metrics_path }
                if (-not [string]::IsNullOrWhiteSpace($syncTargetPath) -and (Test-Path -LiteralPath $syncTargetPath -PathType Leaf)) {
                    $resolvedMetricsPath = (Resolve-Path -LiteralPath $syncTargetPath).Path
                    $metricsSource = "synced"
                }

                if ($null -eq $syncResult) {
                    $metricsSyncSummaryLine = "- Metrics sync: requested but no summary returned"
                } else {
                    $metricsSyncSummaryLine = ("- Metrics sync: runtime={0}, segments copied={1}/{2}, target={3}" -f `
                            $syncResult.runtime_metrics_action,
                            $syncResult.segment_files_copied,
                            $syncResult.segment_files_found,
                            $syncResult.target_metrics_path)
                }
            } catch {
                $_ | Out-String | Tee-Object -FilePath $metricsLogPath -Append | Out-Null
                $metricsSyncSummaryLine = "- Metrics sync: failed"
            }
        }
    }

    if ($null -ne $resolvedMetricsPath) {
        $metricsPathForChecks = $resolvedMetricsPath
        $metricsPathForSignalChecks = $resolvedMetricsPath
        $metricsWindowParts = New-Object System.Collections.Generic.List[string]
        try {
            $rawMetricsRows = @(Import-Csv -LiteralPath $resolvedMetricsPath)
            $rawMetricsRowCount = $rawMetricsRows.Count
            $selectedMetricsRows = $rawMetricsRows

            if (-not $UseFullMetricsHistory) {
                $latestSessionSelection = Select-LatestMetricsSessionRows -Rows $selectedMetricsRows
                $selectedMetricsRows = @($latestSessionSelection.rows)
                if ([bool]$latestSessionSelection.applied) {
                    $metricsWindowParts.Add(("latest_session ({0} -> {1} s)" -f $latestSessionSelection.session_start_seconds, $latestSessionSelection.session_end_seconds))
                } else {
                    $metricsWindowParts.Add(("latest_session skipped: {0}" -f $latestSessionSelection.reason))
                }
            } else {
                $metricsWindowParts.Add("full_history")
            }

            if ($MetricsWarmupTrimSeconds -gt 0) {
                $warmupSelection = Select-MetricsAfterWarmup -Rows $selectedMetricsRows -WarmupTrimSeconds $MetricsWarmupTrimSeconds
                $selectedMetricsRows = @($warmupSelection.rows)
                if ([bool]$warmupSelection.applied) {
                    $metricsWindowParts.Add(("warmup_trim={0}s (keep >= {1}s)" -f $MetricsWarmupTrimSeconds, $warmupSelection.trim_before_seconds))
                } else {
                    $metricsWindowParts.Add(("warmup_trim skipped: {0}" -f $warmupSelection.reason))
                }
            }

            if ($MetricsTailSeconds -gt 0) {
                $tailSecondsSelection = Select-MetricsTailBySeconds -Rows $selectedMetricsRows -TailSeconds $MetricsTailSeconds
                $selectedMetricsRows = @($tailSecondsSelection.rows)
                if ([bool]$tailSecondsSelection.applied) {
                    $metricsWindowParts.Add(("tail_seconds={0} ({1} -> {2} s)" -f $MetricsTailSeconds, $tailSecondsSelection.from_session_seconds, $tailSecondsSelection.to_session_seconds))
                } else {
                    $metricsWindowParts.Add(("tail_seconds skipped: {0}" -f $tailSecondsSelection.reason))
                }
            }

            if ($MetricsTailSamples -gt 0) {
                $tailSamplesSelection = Select-MetricsTailBySamples -Rows $selectedMetricsRows -TailSamples $MetricsTailSamples
                $selectedMetricsRows = @($tailSamplesSelection.rows)
                if ([bool]$tailSamplesSelection.applied) {
                    $metricsWindowParts.Add(("tail_samples={0}" -f $MetricsTailSamples))
                } else {
                    $metricsWindowParts.Add(("tail_samples skipped: {0}" -f $tailSamplesSelection.reason))
                }
            }

            $representativeSelection = Select-RepresentativeMetricsRows -Rows $selectedMetricsRows
            $droppedRepresentativeSamples = [int]$representativeSelection.dropped_background_cap_samples + [int]$representativeSelection.dropped_tier3_crisis_samples
            if ([bool]$representativeSelection.applied) {
                $selectedMetricsRows = @($representativeSelection.rows)
                $metricsWindowParts.Add(("representative_filter bg_cap={0}, tier3_crisis={1}" -f `
                            $representativeSelection.dropped_background_cap_samples,
                            $representativeSelection.dropped_tier3_crisis_samples))
            } elseif ($droppedRepresentativeSamples -gt 0) {
                $metricsWindowParts.Add(("representative_filter skipped: {0}" -f $representativeSelection.reason))
            }

            $selectedMetricsRowCount = $selectedMetricsRows.Count
            if ($selectedMetricsRowCount -eq 0) {
                $metricsWindowParts.Add("selected_rows=0 -> fallback to full source")
                $metricsPathForChecks = $resolvedMetricsPath
            } elseif ($selectedMetricsRowCount -lt $rawMetricsRowCount) {
                $filteredMetricsPath = Join-Path $resolvedReportDir ("preflight_metrics_input_{0}.csv" -f $timestamp)
                $selectedMetricsRows | Export-Csv -LiteralPath $filteredMetricsPath -NoTypeInformation
                $metricsPathForChecks = $filteredMetricsPath
                $metricsSource = "{0} (filtered)" -f $metricsSource
                $metricsWindowParts.Add(("rows={0}/{1}" -f $selectedMetricsRowCount, $rawMetricsRowCount))
            } else {
                $metricsWindowParts.Add(("rows={0}/{1}" -f $selectedMetricsRowCount, $rawMetricsRowCount))
            }

            if ($rawMetricsRowCount -gt 0) {
                $metricsSchemaColumnPresent = ($rawMetricsRows[0].PSObject.Properties.Name -contains "telemetry_schema_version")
                if ($metricsSchemaColumnPresent) {
                    $schemaCandidates = @(
                        $selectedMetricsRows |
                            ForEach-Object { [string]$_.telemetry_schema_version } |
                            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
                            Select-Object -Unique
                    )
                    if ($schemaCandidates.Count -eq 0) {
                        $schemaCandidates = @(
                            $rawMetricsRows |
                                ForEach-Object { [string]$_.telemetry_schema_version } |
                                Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
                                Select-Object -Unique
                        )
                    }
                    if ($schemaCandidates.Count -gt 0) {
                        $metricsSchemaVersionDetected = [string]($schemaCandidates | Select-Object -Last 1)
                    }
                }
            }

            if ([string]::IsNullOrWhiteSpace($RequiredTelemetrySchemaVersion)) {
                $metricsSchemaIsCurrent = $true
            } else {
                $metricsSchemaIsCurrent = $metricsSchemaVersionDetected -eq $RequiredTelemetrySchemaVersion
            }
            if (-not [string]::IsNullOrWhiteSpace($metricsSchemaVersionDetected)) {
                $metricsWindowParts.Add(("schema={0}" -f $metricsSchemaVersionDetected))
            } elseif ($metricsSchemaColumnPresent) {
                $metricsWindowParts.Add("schema=blank")
            } else {
                $metricsWindowParts.Add("schema=missing")
            }
        } catch {
            $_ | Out-String | Tee-Object -FilePath $metricsLogPath -Append | Out-Null
            $metricsPathForChecks = $resolvedMetricsPath
            $metricsWindowParts.Add("filter_error -> fallback to full source")
        }

            if ($metricsPathForChecks -ne $resolvedMetricsPath -and -not $UseWindowedMetricsForSignalChecks) {
                $metricsPathForSignalChecks = $resolvedMetricsPath
                $metricsWindowParts.Add("signal_checks=full_history")
            } else {
                $metricsPathForSignalChecks = $metricsPathForChecks
                $metricsWindowParts.Add("signal_checks=windowed")
            }

            $latestSampleRow = $null
            if ($selectedMetricsRows.Count -gt 0) {
                $latestSampleRow = $selectedMetricsRows | Select-Object -Last 1
            } elseif ($rawMetricsRows.Count -gt 0) {
                $latestSampleRow = $rawMetricsRows | Select-Object -Last 1
            }
            if ($null -ne $latestSampleRow -and ($latestSampleRow.PSObject.Properties.Name -contains "timestamp")) {
                $latestSampleTimestamp = [datetime]::MinValue
                if (Try-ParseMetricsTimestamp -Value $latestSampleRow.timestamp -ParsedTimestamp ([ref]$latestSampleTimestamp)) {
                    if ($latestSampleTimestamp.Kind -eq [System.DateTimeKind]::Unspecified) {
                        $latestSampleTimestamp = [datetime]::SpecifyKind($latestSampleTimestamp, [System.DateTimeKind]::Local)
                    }
                    $metricsLatestSampleUtc = $latestSampleTimestamp.ToUniversalTime()
                }
            }

            if ($metricsWindowParts.Count -eq 0) {
                $metricsWindowSummaryLine = "- Metrics window: full source"
            } else {
                $metricsWindowSummaryLine = "- Metrics window: $($metricsWindowParts -join '; ')"
        }

        try {
            $metricsItem = Get-Item -LiteralPath $resolvedMetricsPath
            $metricsLastWriteUtc = $metricsItem.LastWriteTimeUtc
            $metricsReferenceUtc = $metricsLastWriteUtc
            $metricsTimestampBasis = "file_write_utc"
            if ($metricsLatestSampleUtc -gt [datetime]::MinValue) {
                $metricsReferenceUtc = $metricsLatestSampleUtc
                $metricsTimestampBasis = "latest_sample_utc"
            }
            $nowUtc = (Get-Date).ToUniversalTime()
            $metricsAgeMinutes = [Math]::Round(($nowUtc - $metricsReferenceUtc).TotalMinutes, 2)
            if ($metricsAgeMinutes -lt 0.0) {
                $metricsAgeMinutes = 0.0
            }
            $freshnessIssues = New-Object System.Collections.Generic.List[string]

            if ($MaxMetricsAgeMinutes -gt 0 -and $metricsAgeMinutes -gt $MaxMetricsAgeMinutes) {
                $freshnessIssues.Add(("{0} age {1}m > {2}m" -f $metricsTimestampBasis, $metricsAgeMinutes, $MaxMetricsAgeMinutes))
            }

            if (-not [string]::IsNullOrWhiteSpace($RequiredTelemetrySchemaVersion) -and -not $metricsSchemaIsCurrent) {
                if (-not $metricsSchemaColumnPresent) {
                    $freshnessIssues.Add(("telemetry schema missing (required {0})" -f $RequiredTelemetrySchemaVersion))
                } elseif ([string]::IsNullOrWhiteSpace($metricsSchemaVersionDetected)) {
                    $freshnessIssues.Add(("telemetry schema blank (required {0})" -f $RequiredTelemetrySchemaVersion))
                } else {
                    $freshnessIssues.Add(("telemetry schema {0} != {1}" -f $metricsSchemaVersionDetected, $RequiredTelemetrySchemaVersion))
                }
            }

            $latestSourceWriteUtc = Get-LatestWriteTimeUtc -Paths @(
                (Join-Path $repoRoot "src\main\java"),
                (Join-Path $repoRoot "src\main\resources"),
                (Join-Path $repoRoot "build.gradle"),
                (Join-Path $repoRoot "gradle.properties")
            )
            $codeDriftMinutes = [double]::NaN
            if ($latestSourceWriteUtc -gt [datetime]::MinValue) {
                $codeDriftMinutes = [Math]::Round(($latestSourceWriteUtc - $metricsReferenceUtc).TotalMinutes, 2)
                if ($codeDriftMinutes -gt $MetricsCodeDriftToleranceMinutes) {
                    $freshnessIssues.Add(("source newer by {0}m (tolerance {1}m)" -f $codeDriftMinutes, $MetricsCodeDriftToleranceMinutes))
                }
            }

            if ($freshnessIssues.Count -eq 0) {
                $metricsFreshnessStatus = "fresh"
            } else {
                $metricsFreshnessStatus = "stale"
            }

            $metricsTimestampLabel = $metricsReferenceUtc.ToString("yyyy-MM-ddTHH:mm:ssZ")
            if ($latestSourceWriteUtc -gt [datetime]::MinValue) {
                $sourceTimestampLabel = $latestSourceWriteUtc.ToString("yyyy-MM-ddTHH:mm:ssZ")
                $driftLabel = if ([double]::IsNaN($codeDriftMinutes)) { "n/a" } else { $codeDriftMinutes }
                if ($freshnessIssues.Count -eq 0) {
                    $metricsFreshnessSummaryLine = ("- Metrics freshness: {0}; metrics_utc={1}; basis={2}; age_minutes={3}; latest_source_utc={4}; drift_minutes={5}" -f `
                            $metricsFreshnessStatus, $metricsTimestampLabel, $metricsTimestampBasis, $metricsAgeMinutes, $sourceTimestampLabel, $driftLabel)
                } else {
                    $metricsFreshnessSummaryLine = ("- Metrics freshness: {0}; metrics_utc={1}; basis={2}; age_minutes={3}; latest_source_utc={4}; drift_minutes={5}; issues={6}" -f `
                            $metricsFreshnessStatus, $metricsTimestampLabel, $metricsTimestampBasis, $metricsAgeMinutes, $sourceTimestampLabel, $driftLabel, ($freshnessIssues -join "; "))
                }
            } else {
                if ($freshnessIssues.Count -eq 0) {
                    $metricsFreshnessSummaryLine = ("- Metrics freshness: {0}; metrics_utc={1}; basis={2}; age_minutes={3}; latest_source_utc=unavailable" -f `
                            $metricsFreshnessStatus, $metricsTimestampLabel, $metricsTimestampBasis, $metricsAgeMinutes)
                } else {
                    $metricsFreshnessSummaryLine = ("- Metrics freshness: {0}; metrics_utc={1}; basis={2}; age_minutes={3}; latest_source_utc=unavailable; issues={4}" -f `
                            $metricsFreshnessStatus, $metricsTimestampLabel, $metricsTimestampBasis, $metricsAgeMinutes, ($freshnessIssues -join "; "))
                }
            }
        } catch {
            $metricsFreshnessStatus = "unknown"
            $metricsFreshnessSummaryLine = "- Metrics freshness: unknown (unable to evaluate)"
            $_ | Out-String | Tee-Object -FilePath $metricsLogPath -Append | Out-Null
        }
    }

    if (-not $SkipMetrics) {

        if ($null -eq $metricsPathForChecks) {
            $metricsStatus = "skipped (metrics file missing)"
        } else {
            Write-Host ""
            Write-Host "[Phase6] Runtime metrics summary..."
            try {
                Reset-LastExitCode
                & (Join-Path $scriptRoot "summarize_pauc_metrics.ps1") `
                    -MetricsPath $metricsPathForChecks `
                    -OutCsvPath $metricsCsvPath *>&1 | Tee-Object -FilePath $metricsLogPath
                if ((Get-LastExitCodeOrZero) -eq 0) {
                    $metricsStatus = "ok"
                } else {
                    $metricsStatus = "fail"
                }
            } catch {
                $_ | Out-String | Tee-Object -FilePath $metricsLogPath -Append | Out-Null
                $metricsStatus = "fail"
            }
        }
    }

    if (-not $SkipKpiGate) {
        if ($null -eq $metricsPathForChecks) {
            $kpiStatus = "skipped (metrics file missing)"
        } else {
            Write-Host ""
            Write-Host "[Phase6] KPI gate evaluation..."
            try {
                $kpiArgs = @{
                    MetricsPath = $metricsPathForChecks
                    OutCsvPath = $kpiCsvPath
                    FrameMsP95Max = $FrameMsP95Max
                    FrameMsP99Max = $FrameMsP99Max
                    MsptP95Max = $MsptP95Max
                }
                if ($StrictKpiGate) {
                    $kpiArgs.FailOnBreach = $true
                }
                & (Join-Path $scriptRoot "evaluate_pauc_kpi_gate.ps1") @kpiArgs *>&1 | Tee-Object -FilePath $kpiLogPath
                if (Test-Path -LiteralPath $kpiCsvPath) {
                    $kpiRows = @(Import-Csv -LiteralPath $kpiCsvPath)
                    if ($kpiRows.Count -gt 0) {
                        $kpiStatus = ($kpiRows | Select-Object -Last 1).overall_status
                    } else {
                        $kpiStatus = "ok"
                    }
                } else {
                    $kpiStatus = "ok"
                }
            } catch {
                $_ | Out-String | Tee-Object -FilePath $kpiLogPath -Append | Out-Null
                if (Test-Path -LiteralPath $kpiCsvPath) {
                    $kpiRows = @(Import-Csv -LiteralPath $kpiCsvPath)
                    if ($kpiRows.Count -gt 0) {
                        $kpiStatus = ($kpiRows | Select-Object -Last 1).overall_status
                    } else {
                        $kpiStatus = "fail"
                    }
                } else {
                    $kpiStatus = "fail"
                }
            }
        }
    }

    if (-not $SkipServerGovernorCheck) {
        if ($null -eq $metricsPathForChecks) {
            $serverGovernorStatus = "skipped (metrics file missing)"
        } else {
            Write-Host ""
            Write-Host "[Phase6] Server governor health..."
            try {
                $serverGovernorArgs = @{
                    MetricsPath = $metricsPathForChecks
                    OutCsvPath = $serverGovernorCsvPath
                    PressureThreshold = $ServerPressureThreshold
                    MinPressureSamplesForEvaluation = $MinPressureSamplesForServerGovernor
                    MinSimDistanceDropRatioUnderPressure = $MinSimDistanceDropRatioUnderPressure
                    MinNavRunRatioUnderPressure = $MinNavRunRatioUnderPressure
                    MaxNavRunRatioUnderPressure = $MaxNavRunRatioUnderPressure
                }
                if ($StrictServerGovernor) {
                    $serverGovernorArgs.FailOnIssues = $true
                }
                & $serverGovernorScriptPath @serverGovernorArgs *>&1 | Tee-Object -FilePath $serverGovernorLogPath
                if (Test-Path -LiteralPath $serverGovernorCsvPath) {
                    $serverRows = @(Import-Csv -LiteralPath $serverGovernorCsvPath)
                    if ($serverRows.Count -gt 0) {
                        $serverGovernorStatus = ($serverRows | Select-Object -Last 1).overall_status
                    } else {
                        $serverGovernorStatus = "ok"
                    }
                } else {
                    $serverGovernorStatus = "ok"
                }
            } catch {
                $_ | Out-String | Tee-Object -FilePath $serverGovernorLogPath -Append | Out-Null
                if (Test-Path -LiteralPath $serverGovernorCsvPath) {
                    $serverRows = @(Import-Csv -LiteralPath $serverGovernorCsvPath)
                    if ($serverRows.Count -gt 0) {
                        $serverGovernorStatus = ($serverRows | Select-Object -Last 1).overall_status
                    } else {
                        $serverGovernorStatus = "fail"
                    }
                } else {
                    $serverGovernorStatus = "fail"
                }
            }
        }
    }

    if (-not $SkipChunkCompileCheck) {
        if ($null -eq $metricsPathForChecks) {
            $chunkCompileStatus = "skipped (metrics file missing)"
        } else {
            Write-Host ""
            Write-Host "[Phase6] Chunk compile health..."
            try {
                $chunkCompileArgs = @{
                    MetricsPath = $metricsPathForChecks
                    OutCsvPath = $chunkCompileCsvPath
                    MinBudgetPreviewAvg = $MinChunkCompileBudgetPreviewAvg
                    MaxCompileBackpressureAvg = $MaxChunkCompileBackpressureAvg
                    MaxCompileBackpressureP95 = $MaxChunkCompileBackpressureP95
                    MaxBuilderBackpressureAvg = $MaxChunkBuilderBackpressureAvg
                    MaxBuilderBackpressureP95 = $MaxChunkBuilderBackpressureP95
                    MaxBuilderPendingAvg = $MaxChunkBuilderPendingAvg
                    MaxBuilderPendingP95 = $MaxChunkBuilderPendingP95
                }
                if ($StrictChunkCompile) {
                    $chunkCompileArgs.FailOnIssues = $true
                }
                & $chunkCompileScriptPath @chunkCompileArgs *>&1 | Tee-Object -FilePath $chunkCompileLogPath
                if (Test-Path -LiteralPath $chunkCompileCsvPath) {
                    $chunkRows = @(Import-Csv -LiteralPath $chunkCompileCsvPath)
                    if ($chunkRows.Count -gt 0) {
                        $chunkCompileStatus = ($chunkRows | Select-Object -Last 1).overall_status
                    } else {
                        $chunkCompileStatus = "ok"
                    }
                } else {
                    $chunkCompileStatus = "ok"
                }
            } catch {
                $_ | Out-String | Tee-Object -FilePath $chunkCompileLogPath -Append | Out-Null
                if (Test-Path -LiteralPath $chunkCompileCsvPath) {
                    $chunkRows = @(Import-Csv -LiteralPath $chunkCompileCsvPath)
                    if ($chunkRows.Count -gt 0) {
                        $chunkCompileStatus = ($chunkRows | Select-Object -Last 1).overall_status
                    } else {
                        $chunkCompileStatus = "fail"
                    }
                } else {
                    $chunkCompileStatus = "fail"
                }
            }
        }
    }

    if (-not $SkipDrsDeferredSafetyCheck) {
        if ($null -eq $metricsPathForChecks) {
            $drsDeferredSafetyStatus = "skipped (metrics file missing)"
        } else {
            Write-Host ""
            Write-Host "[Phase6] DRS/deferred safety..."
            try {
                $drsDeferredArgs = @{
                    MetricsPath = $metricsPathForChecks
                    OutCsvPath = $drsDeferredSafetyCsvPath
                    MinDeferredSamples = $MinDeferredSamplesForDrsSafety
                    MaxDrsActiveRatioWhenDeferred = $MaxDrsActiveRatioWhenDeferred
                    MinDeferredSafetyReasonRatio = $MinDeferredSafetyReasonRatio
                }
                if ($StrictDrsDeferredSafety) {
                    $drsDeferredArgs.FailOnIssues = $true
                }
                & $drsDeferredSafetyScriptPath @drsDeferredArgs *>&1 | Tee-Object -FilePath $drsDeferredSafetyLogPath
                if (Test-Path -LiteralPath $drsDeferredSafetyCsvPath) {
                    $drsRows = @(Import-Csv -LiteralPath $drsDeferredSafetyCsvPath)
                    if ($drsRows.Count -gt 0) {
                        $drsDeferredSafetyStatus = ($drsRows | Select-Object -Last 1).overall_status
                    } else {
                        $drsDeferredSafetyStatus = "ok"
                    }
                } else {
                    $drsDeferredSafetyStatus = "ok"
                }
            } catch {
                $_ | Out-String | Tee-Object -FilePath $drsDeferredSafetyLogPath -Append | Out-Null
                if (Test-Path -LiteralPath $drsDeferredSafetyCsvPath) {
                    $drsRows = @(Import-Csv -LiteralPath $drsDeferredSafetyCsvPath)
                    if ($drsRows.Count -gt 0) {
                        $drsDeferredSafetyStatus = ($drsRows | Select-Object -Last 1).overall_status
                    } else {
                        $drsDeferredSafetyStatus = "fail"
                    }
                } else {
                    $drsDeferredSafetyStatus = "fail"
                }
            }
        }
    }

    if (-not $SkipSoakStabilityCheck) {
        if ($null -eq $metricsPathForSignalChecks) {
            $soakStabilityStatus = "skipped (metrics file missing)"
        } else {
            Write-Host ""
            Write-Host "[Phase6] Soak stability..."
            try {
                $soakArgs = @{
                    MetricsPath = $metricsPathForSignalChecks
                    OutCsvPath = $soakStabilityCsvPath
                    MinSessionSamples = $MinSoakSamples
                    MinSessionDurationSeconds = $MinSoakDurationSeconds
                    MaxQualityLevelTransitionsPerMinute = $MaxSoakQualityLevelTransitionsPerMinute
                    MaxQualityTargetTransitionsPerMinute = $MaxSoakQualityTargetTransitionsPerMinute
                    MaxAutoQualityAdjustmentsPerMinute = $MaxSoakAutoQualityAdjustmentsPerMinute
                    MaxSimDistanceTransitionsPerMinute = $MaxSoakSimDistanceTransitionsPerMinute
                    MaxSimDistanceAdjustmentsPerMinute = $MaxSoakSimDistanceAdjustmentsPerMinute
                    MaxSimDistanceOscillationsPerMinute = $MaxSoakSimDistanceOscillationsPerMinute
                    MaxStreamRadiusTransitionsPerMinute = $MaxSoakStreamRadiusTransitionsPerMinute
                    MaxDeferredActiveTogglesPerMinute = $MaxSoakDeferredTogglesPerMinute
                    MaxShaderRouteTransitionsPerMinute = $MaxSoakShaderRouteTransitionsPerMinute
                }
                if ($StrictSoakStability) {
                    $soakArgs.FailOnIssues = $true
                }
                & $soakStabilityScriptPath @soakArgs *>&1 | Tee-Object -FilePath $soakStabilityLogPath
                if (Test-Path -LiteralPath $soakStabilityCsvPath) {
                    $soakRows = @(Import-Csv -LiteralPath $soakStabilityCsvPath)
                    if ($soakRows.Count -gt 0) {
                        $soakStabilityStatus = ($soakRows | Select-Object -Last 1).overall_status
                    } else {
                        $soakStabilityStatus = "ok"
                    }
                } else {
                    $soakStabilityStatus = "ok"
                }
            } catch {
                $_ | Out-String | Tee-Object -FilePath $soakStabilityLogPath -Append | Out-Null
                if (Test-Path -LiteralPath $soakStabilityCsvPath) {
                    $soakRows = @(Import-Csv -LiteralPath $soakStabilityCsvPath)
                    if ($soakRows.Count -gt 0) {
                        $soakStabilityStatus = ($soakRows | Select-Object -Last 1).overall_status
                    } else {
                        $soakStabilityStatus = "fail"
                    }
                } else {
                    $soakStabilityStatus = "fail"
                }
            }
        }
    }

    if ($CheckAbMatrix) {
        Write-Host ""
        Write-Host "[Phase6] A/B matrix audit..."
        try {
            $abArgs = @{
                ResultsPath = $ResultsPath
                OutCsvPath = $abCsvPath
            }
            if ($StrictAbMatrix) {
                $abArgs.FailOnIssues = $true
            }
            & $abAuditScriptPath @abArgs *>&1 | Tee-Object -FilePath $abLogPath
            if (Test-Path -LiteralPath $abCsvPath) {
                $abRows = @(Import-Csv -LiteralPath $abCsvPath)
                if ($abRows.Count -gt 0) {
                    $abStatus = ($abRows | Select-Object -Last 1).overall_status
                } else {
                    $abStatus = "ok"
                }
            } else {
                $abStatus = "ok"
            }
        } catch {
            $_ | Out-String | Tee-Object -FilePath $abLogPath -Append | Out-Null
            if (Test-Path -LiteralPath $abCsvPath) {
                $abRows = @(Import-Csv -LiteralPath $abCsvPath)
                if ($abRows.Count -gt 0) {
                    $abStatus = ($abRows | Select-Object -Last 1).overall_status
                } else {
                    $abStatus = "fail"
                }
            } else {
                $abStatus = "fail"
            }
        }
    }

    if ($CheckAbProgress -or $CheckAbMatrix) {
        Write-Host ""
        Write-Host "[Phase6] A/B campaign progress..."
        try {
            if (-not (Test-Path -LiteralPath $ResultsPath)) {
                $abProgressStatus = "skipped (results file missing)"
            } else {
                & $abCampaignScriptPath `
                    -ResultsPath $ResultsPath `
                    -OutCsvPath $abProgressCsvPath *>&1 | Tee-Object -FilePath $abProgressLogPath
                if (Test-Path -LiteralPath $abProgressCsvPath) {
                    $abProgressRows = @(Import-Csv -LiteralPath $abProgressCsvPath)
                    if ($abProgressRows.Count -gt 0) {
                        $lastAbProgress = $abProgressRows | Select-Object -Last 1
                        $abProgressStatus = $lastAbProgress.overall_status
                        $completionRaw = [string]$lastAbProgress.completion_percent
                        if (-not [string]::IsNullOrWhiteSpace($completionRaw)) {
                            $completionParsed = 0.0
                            if ([double]::TryParse(
                                    $completionRaw.Replace(",", "."),
                                    [System.Globalization.NumberStyles]::Float,
                                    [System.Globalization.CultureInfo]::InvariantCulture,
                                    [ref]$completionParsed
                                )) {
                                $abCompletionPercent = $completionParsed
                            }
                        }
                    } else {
                        $abProgressStatus = "ok"
                    }
                } else {
                    $abProgressStatus = "ok"
                }
            }
        } catch {
            $_ | Out-String | Tee-Object -FilePath $abProgressLogPath -Append | Out-Null
            if (Test-Path -LiteralPath $abProgressCsvPath) {
                $abProgressRows = @(Import-Csv -LiteralPath $abProgressCsvPath)
                if ($abProgressRows.Count -gt 0) {
                    $lastAbProgress = $abProgressRows | Select-Object -Last 1
                    $abProgressStatus = $lastAbProgress.overall_status
                } else {
                    $abProgressStatus = "fail"
                }
            } else {
                $abProgressStatus = "fail"
            }
        }
    }

    if ($StrictMetricsFreshness -and $metricsFreshnessStatus -eq "stale") {
        if (-not ($metricsStatus -like "skipped*")) {
            $metricsStatus = "fail"
        }
    }

    $shaderSummaryLine = "- Shader summary: n/a"
    if (Test-Path -LiteralPath $shaderCsvPath) {
        $shaderRows = @(Import-Csv -LiteralPath $shaderCsvPath)
        if ($shaderRows.Count -gt 0) {
            $strictFail = @($shaderRows | Where-Object { $_.strict_status -eq "fail" }).Count
            $strictWarn = @($shaderRows | Where-Object { $_.strict_status -eq "warn" }).Count
            $balancedFail = @($shaderRows | Where-Object { $_.balanced_status -eq "fail" }).Count
            $balancedWarn = @($shaderRows | Where-Object { $_.balanced_status -eq "warn" }).Count
            $fastFail = @($shaderRows | Where-Object { $_.fast_status -eq "fail" }).Count
            $fastWarn = @($shaderRows | Where-Object { $_.fast_status -eq "warn" }).Count
            $shaderSummaryLine = ("- Shader summary: packs={0}, strict(fail={1},warn={2}), balanced(fail={3},warn={4}), fast(fail={5},warn={6})" -f `
                    $shaderRows.Count, $strictFail, $strictWarn, $balancedFail, $balancedWarn, $fastFail, $fastWarn)
        }
    }

    $compileWarningsSummaryLine = "- Compile warnings: n/a"
    if (Test-Path -LiteralPath $compileWarningsCsvPath) {
        $compileWarningRows = @(Import-Csv -LiteralPath $compileWarningsCsvPath)
        if ($compileWarningRows.Count -gt 0) {
            $lastCompileWarnings = $compileWarningRows | Select-Object -Last 1
            $compileWarningsSummaryLine = ("- Compile warnings: status={0}, count={1}, threshold={2}, issues={3}" -f `
                    $lastCompileWarnings.overall_status,
                    $lastCompileWarnings.warning_count,
                    $lastCompileWarnings.max_warning_count,
                    $lastCompileWarnings.issue_count)
        }
    }

    $metricsSummaryLines = @("- Metrics summary: n/a")
    if (Test-Path -LiteralPath $metricsCsvPath) {
        $metricRows = @(Import-Csv -LiteralPath $metricsCsvPath)
        if ($metricRows.Count -gt 0) {
            $last = $metricRows | Select-Object -Last 1
            $metricsSummaryLines = @(
                    ("- Metrics summary: fps_avg={0}, fps_1pct_low={1}, frame_ms_p95={2}, frame_ms_p99={3}, mspt_p95={4}" -f `
                            $last.fps_avg, $last.fps_1pct_low, $last.frame_ms_p95, $last.frame_ms_p99, $last.mspt_p95)
            )
            $hasCompileMetrics = ($last.PSObject.Properties.Name -contains "chunk_compile_budget_preview_avg") `
                -and ($last.PSObject.Properties.Name -contains "chunk_compile_backpressure_avg") `
                -and ($last.PSObject.Properties.Name -contains "chunk_builder_backpressure_avg") `
                -and ($last.PSObject.Properties.Name -contains "chunk_builder_pending_avg")
            if ($hasCompileMetrics) {
                $metricsSummaryLines += ("- Metrics compile: budget_avg={0}, compile_backpressure_avg={1}, builder_backpressure_avg={2}, builder_pending_avg={3}" -f `
                        $last.chunk_compile_budget_preview_avg,
                        $last.chunk_compile_backpressure_avg,
                        $last.chunk_builder_backpressure_avg,
                        $last.chunk_builder_pending_avg)
            }
        }
    }
    $metricsSourceSummaryLine = if ($null -eq $metricsPathForChecks) {
        "- Metrics source: unavailable"
    } elseif ($metricsPathForChecks -eq $resolvedMetricsPath) {
        "- Metrics source: $metricsSource -> $resolvedMetricsPath"
    } else {
        "- Metrics source: $metricsSource -> $resolvedMetricsPath (effective: $metricsPathForChecks)"
    }

    $kpiSummaryLine = "- KPI gate: n/a"
    if (Test-Path -LiteralPath $kpiCsvPath) {
        $kpiRows = @(Import-Csv -LiteralPath $kpiCsvPath)
        if ($kpiRows.Count -gt 0) {
            $lastKpi = $kpiRows | Select-Object -Last 1
            $kpiSummaryLine = ("- KPI gate: status={0}, frame_ms_p95={1}/{2}, frame_ms_p99={3}/{4}, mspt_p95={5}/{6}" -f `
                    $lastKpi.overall_status,
                    $lastKpi.frame_ms_p95,
                    $lastKpi.target_frame_ms_p95_max,
                    $lastKpi.frame_ms_p99,
                    $lastKpi.target_frame_ms_p99_max,
                    $lastKpi.mspt_p95,
                    $lastKpi.target_mspt_p95_max)
        }
    }

    $serverGovernorSummaryLine = "- Server governor: n/a"
    if (Test-Path -LiteralPath $serverGovernorCsvPath) {
        $serverRows = @(Import-Csv -LiteralPath $serverGovernorCsvPath)
        if ($serverRows.Count -gt 0) {
            $lastServer = $serverRows | Select-Object -Last 1
            $serverGovernorSummaryLine = ("- Server governor: status={0}, mitigation_samples={1}, emergency_samples={2}, sim_drop_ratio={3}, nav_ratio={4}, issues={5}" -f `
                    $lastServer.overall_status,
                    $lastServer.mitigation_active_samples,
                    $lastServer.emergency_samples,
                    $lastServer.sim_distance_drop_ratio_under_pressure,
                    $lastServer.mob_nav_run_ratio_avg_under_pressure,
                    $lastServer.issue_count)
        }
    }

    $chunkCompileSummaryLine = "- Chunk compile: n/a"
    if (Test-Path -LiteralPath $chunkCompileCsvPath) {
        $chunkRows = @(Import-Csv -LiteralPath $chunkCompileCsvPath)
        if ($chunkRows.Count -gt 0) {
            $lastChunk = $chunkRows | Select-Object -Last 1
            $chunkCompileSummaryLine = ("- Chunk compile: status={0}, budget_avg={1}, compile_bp_avg={2}, compile_bp_p95={3}, builder_bp_avg={4}, builder_pending_p95={5}, issues={6}" -f `
                    $lastChunk.overall_status,
                    $lastChunk.budget_preview_avg,
                    $lastChunk.compile_backpressure_avg,
                    $lastChunk.compile_backpressure_p95,
                    $lastChunk.builder_backpressure_avg,
                    $lastChunk.builder_pending_p95,
                    $lastChunk.issue_count)
        }
    }

    $drsDeferredSafetySummaryLine = "- DRS/deferred safety: n/a"
    if (Test-Path -LiteralPath $drsDeferredSafetyCsvPath) {
        $drsRows = @(Import-Csv -LiteralPath $drsDeferredSafetyCsvPath)
        if ($drsRows.Count -gt 0) {
            $lastDrs = $drsRows | Select-Object -Last 1
            $drsDeferredSafetySummaryLine = ("- DRS/deferred safety: status={0}, deferred_samples={1}, drs_active_ratio={2}, safety_reason_ratio={3}, issues={4}" -f `
                    $lastDrs.overall_status,
                    $lastDrs.deferred_samples,
                    $lastDrs.drs_active_ratio_when_deferred,
                    $lastDrs.deferred_safety_reason_ratio,
                    $lastDrs.issue_count)
        }
    }

    $soakStabilitySummaryLine = "- Soak stability: n/a"
    if (Test-Path -LiteralPath $soakStabilityCsvPath) {
        $soakRows = @(Import-Csv -LiteralPath $soakStabilityCsvPath)
        if ($soakRows.Count -gt 0) {
            $lastSoak = $soakRows | Select-Object -Last 1
            $soakStabilitySummaryLine = ("- Soak stability: status={0}, duration_s={1}, quality_transitions_per_min={2}, sim_distance_adjustments_per_min={3}, issues={4}" -f `
                    $lastSoak.overall_status,
                    $lastSoak.session_duration_seconds,
                    $lastSoak.quality_level_transitions_per_min,
                    $lastSoak.sim_distance_adjustments_per_min,
                    $lastSoak.issue_count)
        }
    }

    $abSummaryLine = "- A/B audit: n/a"
    if (Test-Path -LiteralPath $abCsvPath) {
        $abRows = @(Import-Csv -LiteralPath $abCsvPath)
        if ($abRows.Count -gt 0) {
            $lastAb = $abRows | Select-Object -Last 1
            $abSummaryLine = ("- A/B audit: status={0}, scenes_pass={1}/{2}, rows_with_fps={3}/{4}, issues={5}" -f `
                    $lastAb.overall_status,
                    $lastAb.scenes_pass,
                    $lastAb.scenes_checked,
                    $lastAb.rows_with_fps,
                    $lastAb.rows_total,
                    $lastAb.issue_count)
        }
    }

    $abProgressSummaryLine = "- A/B progress: n/a"
    if (Test-Path -LiteralPath $abProgressCsvPath) {
        $abProgressRows = @(Import-Csv -LiteralPath $abProgressCsvPath)
        if ($abProgressRows.Count -gt 0) {
            $lastAbProgress = $abProgressRows | Select-Object -Last 1
            $nextCell = if ([string]::IsNullOrWhiteSpace($lastAbProgress.next_scene)) {
                "none"
            } else {
                "{0}/{1}" -f $lastAbProgress.next_scene, $lastAbProgress.next_profile
            }
            $abProgressSummaryLine = ("- A/B progress: completion={0}%, filled={1}/{2}, missing={3}, next={4}" -f `
                    $lastAbProgress.completion_percent,
                    $lastAbProgress.filled_cells,
                    $lastAbProgress.total_cells,
                    $lastAbProgress.missing_cells,
                    $nextCell)
        }
    }

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# Phase 6 Preflight Report")
    $lines.Add("")
    $lines.Add(("- Timestamp UTC: {0}" -f (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")))
    $lines.Add(("- Repository: {0}" -f $repoRoot))
    $lines.Add("")
    $lines.Add("## Status")
    $lines.Add(("- Documentation freshness: {0}" -f $docStatus))
    $lines.Add(("- Compile: {0}" -f $compileStatus))
    $lines.Add(("- Compile warnings: {0}" -f $compileWarningsStatus))
    $lines.Add(("- Shader compatibility: {0}" -f $shaderStatus))
    $lines.Add(("- Metrics summary: {0}" -f $metricsStatus))
    $lines.Add(("- Metrics freshness: {0}" -f $metricsFreshnessStatus))
    $lines.Add(("- Server governor health: {0}" -f $serverGovernorStatus))
    $lines.Add(("- Chunk compile health: {0}" -f $chunkCompileStatus))
    $lines.Add(("- DRS/deferred safety: {0}" -f $drsDeferredSafetyStatus))
    $lines.Add(("- Soak stability: {0}" -f $soakStabilityStatus))
    $lines.Add(("- KPI gate: {0}" -f $kpiStatus))
    $lines.Add(("- A/B audit: {0}" -f $abStatus))
    $lines.Add(("- A/B progress: {0}" -f $abProgressStatus))
    $lines.Add("")
    $lines.Add("## Highlights")
    $lines.Add($compileWarningsSummaryLine)
    $lines.Add($shaderSummaryLine)
    foreach ($metricLine in $metricsSummaryLines) {
        $lines.Add($metricLine)
    }
    $lines.Add($metricsSourceSummaryLine)
    $lines.Add($metricsFreshnessSummaryLine)
    $lines.Add($metricsWindowSummaryLine)
    $lines.Add($metricsSyncSummaryLine)
    $lines.Add($kpiSummaryLine)
    $lines.Add($serverGovernorSummaryLine)
    $lines.Add($chunkCompileSummaryLine)
    $lines.Add($drsDeferredSafetySummaryLine)
    $lines.Add($soakStabilitySummaryLine)
    $lines.Add($abSummaryLine)
    $lines.Add($abProgressSummaryLine)
    $lines.Add("")
    $lines.Add("## Artifacts")
    $lines.Add(("- Documentation log: {0}" -f $docLogPath))
    $lines.Add(("- Compile log: {0}" -f $compileLogPath))
    $lines.Add(("- Compile warnings log: {0}" -f $compileWarningsLogPath))
    $lines.Add(("- Shader log: {0}" -f $shaderLogPath))
    $lines.Add(("- Metrics log: {0}" -f $metricsLogPath))
    $lines.Add(("- KPI log: {0}" -f $kpiLogPath))
    $lines.Add(("- Server governor log: {0}" -f $serverGovernorLogPath))
    $lines.Add(("- Chunk compile log: {0}" -f $chunkCompileLogPath))
    $lines.Add(("- DRS/deferred safety log: {0}" -f $drsDeferredSafetyLogPath))
    $lines.Add(("- Soak stability log: {0}" -f $soakStabilityLogPath))
    $lines.Add(("- A/B log: {0}" -f $abLogPath))
    $lines.Add(("- A/B progress log: {0}" -f $abProgressLogPath))
    $lines.Add(("- Shader CSV: {0}" -f $shaderCsvPath))
    $lines.Add(("- Metrics CSV: {0}" -f $metricsCsvPath))
    $lines.Add(("- Compile warnings CSV: {0}" -f $compileWarningsCsvPath))
    $lines.Add(("- KPI CSV: {0}" -f $kpiCsvPath))
    $lines.Add(("- Server governor CSV: {0}" -f $serverGovernorCsvPath))
    $lines.Add(("- Chunk compile CSV: {0}" -f $chunkCompileCsvPath))
    $lines.Add(("- DRS/deferred safety CSV: {0}" -f $drsDeferredSafetyCsvPath))
    $lines.Add(("- Soak stability CSV: {0}" -f $soakStabilityCsvPath))
    $lines.Add(("- A/B CSV: {0}" -f $abCsvPath))
    $lines.Add(("- A/B progress CSV: {0}" -f $abProgressCsvPath))

    Set-Content -LiteralPath $reportPath -Value $lines

    Write-Host ""
    Write-Host ("Phase 6 preflight report: {0}" -f $reportPath)

    $kpiHardFail = $StrictKpiGate -and $kpiStatus -eq "fail"
    $compileWarningsHardFail = $StrictCompileWarnings -and -not ($compileWarningsStatus -eq "pass" -or $compileWarningsStatus -eq "ok")
    $serverGovernorHardFail = $StrictServerGovernor -and -not ($serverGovernorStatus -eq "pass" -or $serverGovernorStatus -eq "ok")
    $chunkCompileHardFail = $StrictChunkCompile -and -not ($chunkCompileStatus -eq "pass" -or $chunkCompileStatus -eq "ok")
    $drsDeferredSafetyHardFail = $StrictDrsDeferredSafety -and -not ($drsDeferredSafetyStatus -eq "pass" -or $drsDeferredSafetyStatus -eq "ok")
    $soakStabilityHardFail = $StrictSoakStability -and -not ($soakStabilityStatus -eq "pass" -or $soakStabilityStatus -eq "ok")
    $abHardFail = $StrictAbMatrix -and $abStatus -eq "fail"
    $abProgressHardFail = $false
    if ($StrictAbProgress) {
        $abProgressCompletionFail = $null -eq $abCompletionPercent -or $abCompletionPercent -lt $MinAbCompletionPercent
        $abProgressStatusFail = -not ($abProgressStatus -eq "pass" -or $abProgressStatus -eq "ok")
        $abProgressHardFail = $abProgressStatusFail -or $abProgressCompletionFail
    }
    $docHardFail = $StrictDocFreshness -and $docStatus -eq "fail"
    if ($docHardFail -or $abHardFail -or $abProgressHardFail -or $compileWarningsHardFail -or $serverGovernorHardFail -or $chunkCompileHardFail -or $drsDeferredSafetyHardFail -or $soakStabilityHardFail -or $compileStatus -eq "fail" -or $shaderStatus -eq "fail" -or $metricsStatus -eq "fail" -or $kpiHardFail) {
        exit 1
    }
} finally {
    Pop-Location
}
