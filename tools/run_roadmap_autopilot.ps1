param(
    [string]$ResultsPath = ".\RESULTATS_TESTS_AB_PAUC.csv",
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$StatePath = ".\run\pauc_telemetry\ab_capture_state.json",
    [string]$AutopilotStatePath = ".\run\pauc_telemetry\roadmap_autopilot_state.json",
    [string]$SummaryOutputPath = "",
    [switch]$SummaryOutputCompress,
    [string]$CandidateRoot = ".\run\beta_candidates",
    [string]$ReportsDir = ".\run\pauc_reports",
    [string]$PrismRoot = "$env:APPDATA\PrismLauncher\instances",
    [string]$InstanceName = "1.20.1(1)",
    [string]$Build = "",
    [int]$PollIntervalSeconds = 30,
    [int]$MaxDurationMinutes = 120,
    [int]$MinRowsForCaptureFinish = 120,
    [int]$MinMetricsRowsForCandidatePreflight = 120,
    [int]$MinMetricsDurationSecondsForCandidatePreflight = 480,
    [switch]$OneShot,
    [switch]$DisableAutoMetricsDiscovery,
    [bool]$AutoApplyProfileForNext = $true,
    [switch]$SyncTelemetryToRepo,
    [switch]$SyncTelemetryCaptureState,
    [string]$TelemetrySyncDestination = ".\run\pauc_telemetry",
    [double]$FrameMsP95Max = 20.0,
    [double]$FrameMsP99Max = 60.0,
    [double]$MsptP95Max = 60.0,
    [int]$CandidateMetricsWarmupTrimSeconds = 60,
    [int]$CandidateMetricsTailSeconds = 0,
    [int]$CandidateMetricsTailSamples = 0,
    [switch]$CandidateUseFullMetricsHistory,
    [bool]$PreferCachedDecisionOnBuildFailure = $true,
    [int]$MaxCachedCandidateAgeMinutes = 0,
    [bool]$EnableStrictCandidateWindowRetry = $true,
    [int]$StrictCandidateRetryTailSeconds = 600,
    [bool]$RetryStrictCandidateOnlyOnKpiFailure = $true,
    [int]$MaxMetricsAgeMinutes = 240,
    [int]$MetricsCodeDriftToleranceMinutes = 2,
    [string]$RequiredTelemetrySchemaVersion = "20260318_shadowv2",
    [bool]$AutoSyncModJarToPrism = $true,
    [switch]$BuildJarBeforeSync,
    [bool]$EnforceFreshCachedCandidateForStartupSync = $true,
    [switch]$FailOnStartupSyncStaleCacheBlock,
    [switch]$FailOnPendingMetricsDecision,
    [switch]$FailOnNoEffectiveDecision,
    [switch]$FailOnEffectiveDecisionNotReadyForBeta,
    [switch]$FailOnCachedDecisionSource,
    [switch]$FailOnPrismJarSyncNotSynced,
    [switch]$FailOnSummaryOutputWriteError,
    [bool]$RunErrorSortingPass = $true,
    [bool]$ErrorSortingIncludeWarnings = $true,
    [int]$ErrorSortingTopN = 25,
    [int]$ErrorSortingNoiseWarnHitsTotal = 500,
    [int]$ErrorSortingNoiseFailHitsTotal = 2000,
    [switch]$FailOnErrorSortingStatusNotPass,
    [switch]$FailOnErrorSortingNoiseWarn,
    [switch]$FailOnErrorSortingBlockingPatterns,
    [switch]$FailOnErrorSortingNoiseFail
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PollIntervalSeconds -lt 1) {
    throw "PollIntervalSeconds must be >= 1"
}
if ($MaxDurationMinutes -lt 1) {
    throw "MaxDurationMinutes must be >= 1"
}
if ($MinRowsForCaptureFinish -lt 1) {
    throw "MinRowsForCaptureFinish must be >= 1"
}
if ($MinMetricsRowsForCandidatePreflight -lt 1) {
    throw "MinMetricsRowsForCandidatePreflight must be >= 1"
}
if ($MinMetricsDurationSecondsForCandidatePreflight -lt 0) {
    throw "MinMetricsDurationSecondsForCandidatePreflight must be >= 0"
}
if ($CandidateMetricsWarmupTrimSeconds -lt 0) {
    throw "CandidateMetricsWarmupTrimSeconds must be >= 0"
}
if ($CandidateMetricsTailSeconds -lt 0) {
    throw "CandidateMetricsTailSeconds must be >= 0"
}
if ($CandidateMetricsTailSamples -lt 0) {
    throw "CandidateMetricsTailSamples must be >= 0"
}
if ($StrictCandidateRetryTailSeconds -lt 0) {
    throw "StrictCandidateRetryTailSeconds must be >= 0"
}
if ($MaxCachedCandidateAgeMinutes -lt 0) {
    throw "MaxCachedCandidateAgeMinutes must be >= 0"
}
if ($MaxMetricsAgeMinutes -lt 0) {
    throw "MaxMetricsAgeMinutes must be >= 0"
}
if ($MetricsCodeDriftToleranceMinutes -lt 0) {
    throw "MetricsCodeDriftToleranceMinutes must be >= 0"
}
if ($ErrorSortingTopN -lt 1) {
    throw "ErrorSortingTopN must be >= 1"
}
if ($ErrorSortingNoiseWarnHitsTotal -lt 0) {
    throw "ErrorSortingNoiseWarnHitsTotal must be >= 0"
}
if ($ErrorSortingNoiseFailHitsTotal -lt 0) {
    throw "ErrorSortingNoiseFailHitsTotal must be >= 0"
}
if ($ErrorSortingNoiseFailHitsTotal -lt $ErrorSortingNoiseWarnHitsTotal) {
    throw "ErrorSortingNoiseFailHitsTotal must be >= ErrorSortingNoiseWarnHitsTotal"
}

function Get-LastExitCodeOrZero {
    $exitVar = Get-Variable -Name LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue
    if ($null -eq $exitVar) {
        return 0
    }
    return [int]$exitVar.Value
}

function Reset-LastExitCode {
    Set-Variable -Name LASTEXITCODE -Scope Global -Value 0
}

function Resolve-ScriptPath {
    param(
        [string]$PathValue,
        [string]$RepoRoot
    )

    if (Test-Path -LiteralPath $PathValue) {
        return (Resolve-Path -LiteralPath $PathValue).Path
    }
    $candidate = Join-Path $RepoRoot $PathValue
    if (Test-Path -LiteralPath $candidate) {
        return (Resolve-Path -LiteralPath $candidate).Path
    }
    throw "Script not found: $PathValue"
}

function Get-LastOutputObject {
    param([object]$Value)

    if ($null -eq $Value) {
        return $null
    }
    if ($Value -is [System.Array]) {
        if ($Value.Count -eq 0) {
            return $null
        }
        return ($Value | Select-Object -Last 1)
    }
    return $Value
}

function Read-AutopilotState {
    param(
        [string]$FilePath
    )

    $defaultState = [PSCustomObject]@{
        last_processed_metrics_signature = ""
        last_processed_at_utc = ""
        last_processed_metrics_path = ""
        last_candidate_dir = ""
        last_candidate_decision = ""
        last_candidate_readiness_percent = ""
        last_candidate_timestamp_utc = ""
    }

    if ([string]::IsNullOrWhiteSpace($FilePath) -or -not (Test-Path -LiteralPath $FilePath -PathType Leaf)) {
        return $defaultState
    }

    function Get-ParsedStringProperty {
        param(
            [object]$ParsedObject,
            [string]$PropertyName
        )
        if ($null -eq $ParsedObject -or [string]::IsNullOrWhiteSpace($PropertyName)) {
            return ""
        }
        $property = $ParsedObject.PSObject.Properties[$PropertyName]
        if ($null -eq $property -or $null -eq $property.Value) {
            return ""
        }
        return [string]$property.Value
    }

    try {
        $raw = Get-Content -LiteralPath $FilePath -Raw
        if ([string]::IsNullOrWhiteSpace($raw)) {
            return $defaultState
        }
        $parsed = $raw | ConvertFrom-Json
        if ($null -eq $parsed) {
            return $defaultState
        }
        return [PSCustomObject]@{
            last_processed_metrics_signature = Get-ParsedStringProperty -ParsedObject $parsed -PropertyName "last_processed_metrics_signature"
            last_processed_at_utc = Get-ParsedStringProperty -ParsedObject $parsed -PropertyName "last_processed_at_utc"
            last_processed_metrics_path = Get-ParsedStringProperty -ParsedObject $parsed -PropertyName "last_processed_metrics_path"
            last_candidate_dir = Get-ParsedStringProperty -ParsedObject $parsed -PropertyName "last_candidate_dir"
            last_candidate_decision = Get-ParsedStringProperty -ParsedObject $parsed -PropertyName "last_candidate_decision"
            last_candidate_readiness_percent = Get-ParsedStringProperty -ParsedObject $parsed -PropertyName "last_candidate_readiness_percent"
            last_candidate_timestamp_utc = Get-ParsedStringProperty -ParsedObject $parsed -PropertyName "last_candidate_timestamp_utc"
        }
    } catch {
        Write-Warning ("Autopilot state read failed ({0}): {1}" -f $FilePath, $_.Exception.Message)
        return $defaultState
    }
}

function Write-AutopilotState {
    param(
        [string]$FilePath,
        [object]$State
    )

    if ([string]::IsNullOrWhiteSpace($FilePath) -or $null -eq $State) {
        return
    }

    $parent = Split-Path -Parent $FilePath
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $State | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $FilePath -Encoding UTF8
}

function Get-MetricsSessionSignature {
    param(
        [object]$SessionStats
    )

    if ($null -eq $SessionStats) {
        return ""
    }

    $latestTimestampUtc = [string]$SessionStats.latest_timestamp_utc
    if ([string]::IsNullOrWhiteSpace($latestTimestampUtc)) {
        $latestTimestampUtc = "none"
    }
    $schemaVersion = [string]$SessionStats.schema_version
    if ([string]::IsNullOrWhiteSpace($schemaVersion)) {
        $schemaVersion = "none"
    }
    $durationSeconds = 0.0
    if ($null -ne $SessionStats.duration_seconds) {
        $durationSeconds = [Math]::Round([double]$SessionStats.duration_seconds, 3)
    }
    $durationText = $durationSeconds.ToString([System.Globalization.CultureInfo]::InvariantCulture)

    return ("{0}|rows={1}|duration={2}|end={3}|timestamp={4}|schema={5}" -f
            [string]$SessionStats.metrics_path,
            [int]$SessionStats.row_count,
            $durationText,
            [string]$SessionStats.session_end_seconds,
            $latestTimestampUtc,
            $schemaVersion)
}

function Write-CachedCandidateStatus {
    param(
        [string]$Decision,
        [string]$ReadinessPercent,
        [string]$CandidateDir,
        [string]$ServerGovernorHealth = "",
        [string]$ServerGovernorInsufficientPressure = ""
    )

    if ([string]::IsNullOrWhiteSpace($Decision)) {
        return $false
    }

    $readinessLabel = if ([string]::IsNullOrWhiteSpace($ReadinessPercent)) { "n/a" } else { $ReadinessPercent }
    $candidateLabel = if ([string]::IsNullOrWhiteSpace($CandidateDir)) { "n/a" } else { $CandidateDir }
    $coverageLabel = ""
    if (-not [string]::IsNullOrWhiteSpace($ServerGovernorHealth) -and $ServerGovernorHealth.Trim().ToLowerInvariant().StartsWith("skipped")) {
        if ($ServerGovernorInsufficientPressure.Trim().ToLowerInvariant() -eq "true") {
            $coverageLabel = " (server governor coverage partial: insufficient pressure samples)"
        } else {
            $coverageLabel = " (server governor gate skipped)"
        }
    }
    Write-Host ("[Autopilot] Last cached candidate remains: {0} ({1}%) at {2}{3}" -f `
            $Decision,
            $readinessLabel,
            $candidateLabel,
            $coverageLabel)
    return $true
}

function Get-LatestCandidateSnapshot {
    param(
        [string]$CandidateRootPath
    )

    if ([string]::IsNullOrWhiteSpace($CandidateRootPath) -or -not (Test-Path -LiteralPath $CandidateRootPath -PathType Container)) {
        return $null
    }

    $latestCandidate = Get-ChildItem -LiteralPath $CandidateRootPath -Directory -Filter "beta_candidate_*" -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            Select-Object -First 1
    if ($null -eq $latestCandidate) {
        return $null
    }

    $decision = ""
    $readinessPercent = ""
    $timestampUtc = ""
    $serverGovernorHealth = ""
    $serverGovernorInsufficientPressure = ""
    $readinessPath = Join-Path $latestCandidate.FullName "beta_readiness.json"
    if (Test-Path -LiteralPath $readinessPath -PathType Leaf) {
        try {
            $readiness = Get-Content -LiteralPath $readinessPath -Raw | ConvertFrom-Json
            $decision = [string]$readiness.decision
            $readinessPercent = [string]$readiness.readiness_percent
            $timestampUtc = [string]$readiness.timestamp_utc
            $serverGovernorHealth = [string]$readiness.server_governor_health
            $serverGovernorInsufficientPressure = [string]$readiness.server_governor_skipped_for_insufficient_pressure
        } catch {
            Write-Warning ("Autopilot cached candidate snapshot parse failed ({0}): {1}" -f $readinessPath, $_.Exception.Message)
        }
    }

    return [PSCustomObject]@{
        candidate_dir = [string]$latestCandidate.FullName
        decision = $decision
        readiness_percent = $readinessPercent
        timestamp_utc = $timestampUtc
        server_governor_health = $serverGovernorHealth
        server_governor_skipped_for_insufficient_pressure = $serverGovernorInsufficientPressure
    }
}

function Get-GradlePropertyValue {
    param(
        [string]$FilePath,
        [string]$Key,
        [string]$DefaultValue = ""
    )
    if (-not (Test-Path -LiteralPath $FilePath -PathType Leaf)) {
        return $DefaultValue
    }
    $prefix = "$Key="
    foreach ($line in Get-Content -LiteralPath $FilePath) {
        if ($line.StartsWith($prefix)) {
            return $line.Substring($prefix.Length).Trim()
        }
    }
    return $DefaultValue
}

function Sync-LatestModJarToPrism {
    param(
        [string]$RepoRoot,
        [string]$PrismRootPath,
        [string]$PrismInstanceName,
        [bool]$BuildJar
    )

    if ([string]::IsNullOrWhiteSpace($PrismInstanceName)) {
        Write-Host "[Autopilot] Jar sync skipped (instance name empty)."
        return [PSCustomObject]@{
            synced = $false
            source = "build_libs"
            jar_path = ""
            jar_sha256 = ""
        }
    }

    $instanceMinecraftDir = Join-Path (Join-Path $PrismRootPath $PrismInstanceName) "minecraft"
    if (-not (Test-Path -LiteralPath $instanceMinecraftDir -PathType Container)) {
        Write-Warning ("[Autopilot] Jar sync skipped: instance path not found: {0}" -f $instanceMinecraftDir)
        return [PSCustomObject]@{
            synced = $false
            source = "build_libs"
            jar_path = ""
            jar_sha256 = ""
        }
    }

    if ($BuildJar) {
        Write-Host "[Autopilot] Building jar before Prism sync..."
        & .\gradlew.bat jar
        if ((Get-LastExitCodeOrZero) -ne 0) {
            throw "gradlew jar failed during Prism sync"
        }
    }

    $gradlePropsPath = Join-Path $RepoRoot "gradle.properties"
    $artifactId = Get-GradlePropertyValue -FilePath $gradlePropsPath -Key "mod_artifact_id" -DefaultValue "pauc"
    $modVersion = Get-GradlePropertyValue -FilePath $gradlePropsPath -Key "mod_version" -DefaultValue ""
    $jarPattern = if ([string]::IsNullOrWhiteSpace($modVersion)) {
        "$artifactId*.jar"
    } else {
        "$artifactId-$modVersion*.jar"
    }

    $libsDir = Join-Path $RepoRoot "build\libs"
    if (-not (Test-Path -LiteralPath $libsDir -PathType Container)) {
        Write-Warning ("[Autopilot] Jar sync skipped: build/libs missing ({0})." -f $libsDir)
        return [PSCustomObject]@{
            synced = $false
            source = "build_libs"
            jar_path = ""
            jar_sha256 = ""
        }
    }

    $jarCandidate = Get-ChildItem -LiteralPath $libsDir -File -Filter $jarPattern |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    if ($null -eq $jarCandidate) {
        $jarCandidate = Get-ChildItem -LiteralPath $libsDir -File -Filter "*.jar" |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
    }
    if ($null -eq $jarCandidate) {
        Write-Warning "[Autopilot] Jar sync skipped: no jar found in build/libs."
        return [PSCustomObject]@{
            synced = $false
            source = "build_libs"
            jar_path = ""
            jar_sha256 = ""
        }
    }

    $modsDir = Join-Path $instanceMinecraftDir "mods"
    New-Item -ItemType Directory -Path $modsDir -Force | Out-Null
    $destinationPath = Join-Path $modsDir $jarCandidate.Name
    Copy-Item -LiteralPath $jarCandidate.FullName -Destination $destinationPath -Force

    $jarHash = (Get-FileHash -LiteralPath $jarCandidate.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
    Write-Host ("[Autopilot] Prism jar sync: {0} -> {1}" -f $jarCandidate.Name, $destinationPath)
    Write-Host ("[Autopilot] Prism jar sha256: {0}" -f $jarHash)
    return [PSCustomObject]@{
        synced = $true
        source = "build_libs"
        jar_path = $destinationPath
        jar_sha256 = $jarHash
    }
}

function Sync-CandidateModJarToPrism {
    param(
        [string]$CandidateDir,
        [string]$RepoRoot,
        [string]$PrismRootPath,
        [string]$PrismInstanceName
    )

    if ([string]::IsNullOrWhiteSpace($CandidateDir) -or -not (Test-Path -LiteralPath $CandidateDir -PathType Container)) {
        Write-Warning ("[Autopilot] Candidate jar sync skipped: candidate directory not found ({0})." -f $CandidateDir)
        return [PSCustomObject]@{
            synced = $false
            source = "candidate"
            jar_path = ""
            jar_sha256 = ""
        }
    }

    if ([string]::IsNullOrWhiteSpace($PrismInstanceName)) {
        Write-Host "[Autopilot] Candidate jar sync skipped (instance name empty)."
        return [PSCustomObject]@{
            synced = $false
            source = "candidate"
            jar_path = ""
            jar_sha256 = ""
        }
    }

    $instanceMinecraftDir = Join-Path (Join-Path $PrismRootPath $PrismInstanceName) "minecraft"
    if (-not (Test-Path -LiteralPath $instanceMinecraftDir -PathType Container)) {
        Write-Warning ("[Autopilot] Candidate jar sync skipped: instance path not found: {0}" -f $instanceMinecraftDir)
        return [PSCustomObject]@{
            synced = $false
            source = "candidate"
            jar_path = ""
            jar_sha256 = ""
        }
    }

    $gradlePropsPath = Join-Path $RepoRoot "gradle.properties"
    $artifactId = Get-GradlePropertyValue -FilePath $gradlePropsPath -Key "mod_artifact_id" -DefaultValue "pauc"
    $modVersion = Get-GradlePropertyValue -FilePath $gradlePropsPath -Key "mod_version" -DefaultValue ""
    $jarPattern = if ([string]::IsNullOrWhiteSpace($modVersion)) {
        "$artifactId*.jar"
    } else {
        "$artifactId-$modVersion*.jar"
    }

    $jarCandidate = Get-ChildItem -LiteralPath $CandidateDir -File -Filter $jarPattern |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    if ($null -eq $jarCandidate) {
        $jarCandidate = Get-ChildItem -LiteralPath $CandidateDir -File -Filter "*.jar" |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
    }
    if ($null -eq $jarCandidate) {
        Write-Warning ("[Autopilot] Candidate jar sync skipped: no jar found in {0}." -f $CandidateDir)
        return [PSCustomObject]@{
            synced = $false
            source = "candidate"
            jar_path = ""
            jar_sha256 = ""
        }
    }

    $modsDir = Join-Path $instanceMinecraftDir "mods"
    New-Item -ItemType Directory -Path $modsDir -Force | Out-Null
    $destinationPath = Join-Path $modsDir $jarCandidate.Name
    Copy-Item -LiteralPath $jarCandidate.FullName -Destination $destinationPath -Force

    $jarHash = (Get-FileHash -LiteralPath $jarCandidate.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
    Write-Host ("[Autopilot] Prism candidate jar sync: {0} -> {1}" -f $jarCandidate.Name, $destinationPath)
    Write-Host ("[Autopilot] Prism candidate jar sha256: {0}" -f $jarHash)
    return [PSCustomObject]@{
        synced = $true
        source = "candidate"
        jar_path = $destinationPath
        jar_sha256 = $jarHash
    }
}

function Add-CandidateBuildAttemptConfig {
    param(
        [System.Collections.Generic.List[object]]$Configs,
        [System.Collections.Generic.HashSet[string]]$Keys,
        [string]$Name,
        [bool]$UseFullMetricsHistory,
        [int]$TailSeconds,
        [int]$TailSamples,
        [int]$WarmupTrimSeconds
    )

    if ($null -eq $Configs -or $null -eq $Keys) {
        return
    }

    $attemptKey = ("full={0}|tail_s={1}|tail_n={2}|warmup={3}" -f `
            [bool]$UseFullMetricsHistory,
            [int]$TailSeconds,
            [int]$TailSamples,
            [int]$WarmupTrimSeconds)
    if (-not $Keys.Add($attemptKey)) {
        return
    }

    $Configs.Add([PSCustomObject]@{
            name = $Name
            use_full_metrics_history = [bool]$UseFullMetricsHistory
            tail_seconds = [int]$TailSeconds
            tail_samples = [int]$TailSamples
            warmup_trim_seconds = [int]$WarmupTrimSeconds
        }) | Out-Null
}

function Get-LatestPreflightKpiStatus {
    param(
        [string]$ReportsDirPath
    )

    $defaultResult = [PSCustomObject]@{
        report_path = ""
        kpi_status = ""
    }
    if ([string]::IsNullOrWhiteSpace($ReportsDirPath) -or -not (Test-Path -LiteralPath $ReportsDirPath -PathType Container)) {
        return $defaultResult
    }

    $latestReport = Get-ChildItem -LiteralPath $ReportsDirPath -File -Filter "phase6_preflight_*.md" -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            Select-Object -First 1
    if ($null -eq $latestReport) {
        return $defaultResult
    }

    $kpiStatus = ""
    $fallbackKpiStatus = ""
    try {
        foreach ($line in Get-Content -LiteralPath $latestReport.FullName) {
            if ($line -match "^\-\s*KPI gate:\s*status\s*=\s*([^,\s]+)") {
                $kpiStatus = $matches[1].Trim()
                break
            }
            if ([string]::IsNullOrWhiteSpace($fallbackKpiStatus) -and $line -match "^\-\s*KPI gate:\s*(.+)$") {
                $fallbackKpiStatus = $matches[1].Trim()
            }
        }
        if ([string]::IsNullOrWhiteSpace($kpiStatus)) {
            $kpiStatus = $fallbackKpiStatus
        }
    } catch {
        Write-Warning ("[Autopilot] Unable to parse KPI status from preflight report {0}: {1}" -f $latestReport.FullName, $_.Exception.Message)
    }

    return [PSCustomObject]@{
        report_path = $latestReport.FullName
        kpi_status = $kpiStatus
    }
}

function Test-IsKpiFailStatus {
    param(
        [string]$StatusLabel
    )

    if ([string]::IsNullOrWhiteSpace($StatusLabel)) {
        return $false
    }

    $normalized = $StatusLabel.Trim().ToLowerInvariant()
    if ($normalized -eq "fail" -or $normalized.StartsWith("fail ")) {
        return $true
    }
    if ($normalized.StartsWith("status=fail")) {
        return $true
    }
    if ($normalized -match "(^|[\s,;])status=fail($|[\s,;])") {
        return $true
    }
    return $false
}

function Convert-ToStatusToken {
    param(
        [string]$Value
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return ""
    }

    $normalized = $Value.Trim().ToLowerInvariant()
    $normalized = $normalized -replace "[^a-z0-9]+", "_"
    $normalized = $normalized.Trim("_")
    return $normalized
}

function Resolve-MetricsPath {
    param(
        [string]$PreferredPath,
        [string]$ResolverScriptPath,
        [string]$PrismRootPath,
        [string]$PrismInstanceName,
        [bool]$DisableAutoDiscovery
    )

    $preferPrismMetrics = -not [string]::IsNullOrWhiteSpace($PrismInstanceName)
    $shouldResolveMetrics = -not $DisableAutoDiscovery `
        -and (Test-Path -LiteralPath $ResolverScriptPath -PathType Leaf) `
        -and ($preferPrismMetrics -or -not (Test-Path -LiteralPath $PreferredPath -PathType Leaf))
    if ($shouldResolveMetrics) {
        try {
            Reset-LastExitCode
            $resolution = & $ResolverScriptPath `
                -PreferredPath $PreferredPath `
                -PrismRoot $PrismRootPath `
                -InstanceName $PrismInstanceName `
                -SearchAllPrismInstances:$true `
                -PassThru
            if ($resolution -is [System.Array]) {
                $resolution = $resolution | Select-Object -Last 1
            }
            if ($null -ne $resolution -and [bool]$resolution.resolved) {
                $candidate = [string]$resolution.metrics_path
                if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
                    return (Resolve-Path -LiteralPath $candidate).Path
                }
            }
        } catch {
            Write-Warning ("Autopilot metrics auto-discovery failed: {0}" -f $_.Exception.Message)
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($PreferredPath) -and (Test-Path -LiteralPath $PreferredPath -PathType Leaf)) {
        return (Resolve-Path -LiteralPath $PreferredPath).Path
    }

    return $null
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

function Get-CachedCandidateFreshness {
    param(
        [string]$TimestampUtc,
        [int]$MaxAgeMinutes
    )

    $result = [PSCustomObject]@{
        is_fresh = $true
        age_minutes = $null
        status = "fresh"
        max_age_minutes = [int]$MaxAgeMinutes
    }

    $parsedTimestamp = [datetime]::MinValue
    $hasTimestamp = Try-ParseMetricsTimestamp -Value $TimestampUtc -ParsedTimestamp ([ref]$parsedTimestamp)
    if ($hasTimestamp) {
        if ($parsedTimestamp.Kind -eq [System.DateTimeKind]::Unspecified) {
            $parsedTimestamp = [datetime]::SpecifyKind($parsedTimestamp, [System.DateTimeKind]::Utc)
        } else {
            $parsedTimestamp = $parsedTimestamp.ToUniversalTime()
        }
        $ageMinutes = ((Get-Date).ToUniversalTime() - $parsedTimestamp).TotalMinutes
        if ($ageMinutes -lt 0) {
            $ageMinutes = 0
        }
        $result.age_minutes = [Math]::Round($ageMinutes, 2)
    }

    if ($MaxAgeMinutes -le 0) {
        $result.is_fresh = $true
        $result.status = if ($hasTimestamp) { "fresh_no_limit" } else { "age_check_disabled" }
        return $result
    }

    if (-not $hasTimestamp) {
        $result.is_fresh = $false
        $result.status = if ([string]::IsNullOrWhiteSpace($TimestampUtc)) {
            "missing_timestamp"
        } else {
            "invalid_timestamp"
        }
        return $result
    }

    if ([double]$result.age_minutes -le [double]$MaxAgeMinutes) {
        $result.is_fresh = $true
        $result.status = "fresh"
    } else {
        $result.is_fresh = $false
        $result.status = "stale_age_exceeded"
    }
    return $result
}

function Get-LatestMetricsSessionStats {
    param(
        [string]$MetricsFilePath,
        [double]$MaxTimestampGapSeconds = 15.0,
        [string]$RequiredSchemaVersion = ""
    )

    if ([string]::IsNullOrWhiteSpace($MetricsFilePath) -or -not (Test-Path -LiteralPath $MetricsFilePath -PathType Leaf)) {
        return [PSCustomObject]@{
            resolved = $false
            reason = "missing_metrics_path"
            row_count = 0
            duration_seconds = 0.0
            session_start_seconds = [double]::NaN
            session_end_seconds = [double]::NaN
            latest_timestamp_utc = ""
            metrics_path = $MetricsFilePath
            schema_present = $false
            schema_version = ""
            schema_current = $false
        }
    }

    $rows = @(Import-Csv -LiteralPath $MetricsFilePath)
    if ($rows.Count -eq 0) {
        return [PSCustomObject]@{
            resolved = $false
            reason = "empty_metrics_file"
            row_count = 0
            duration_seconds = 0.0
            session_start_seconds = [double]::NaN
            session_end_seconds = [double]::NaN
            latest_timestamp_utc = ""
            metrics_path = $MetricsFilePath
            schema_present = $false
            schema_version = ""
            schema_current = $false
        }
    }

    $hasTimestamp = $rows[0].PSObject.Properties.Name -contains "timestamp"
    $hasSessionSeconds = $rows[0].PSObject.Properties.Name -contains "session_seconds"
    $selectedRows = New-Object System.Collections.Generic.List[object]

    if ($hasTimestamp) {
        $lastAcceptedTimestamp = [datetime]::MinValue
        $hasLastAcceptedTimestamp = $false
        for ($i = $rows.Count - 1; $i -ge 0; $i--) {
            $currentTimestamp = [datetime]::MinValue
            if (-not (Try-ParseMetricsTimestamp -Value $rows[$i].timestamp -ParsedTimestamp ([ref]$currentTimestamp))) {
                if ($selectedRows.Count -gt 0) {
                    break
                }
                continue
            }

            if (-not $hasLastAcceptedTimestamp) {
                $selectedRows.Add($rows[$i])
                $lastAcceptedTimestamp = $currentTimestamp
                $hasLastAcceptedTimestamp = $true
                continue
            }

            $gapSeconds = ($lastAcceptedTimestamp - $currentTimestamp).TotalSeconds
            if ($gapSeconds -lt 0.0) {
                break
            }
            if ($gapSeconds -le $MaxTimestampGapSeconds) {
                $selectedRows.Add($rows[$i])
                $lastAcceptedTimestamp = $currentTimestamp
            } else {
                break
            }
        }
    } elseif ($hasSessionSeconds) {
        $lastSessionSeconds = 0.0
        $hasLastSessionSeconds = $false
        for ($i = $rows.Count - 1; $i -ge 0; $i--) {
            $currentSeconds = 0.0
            if (-not (Try-ParseInvariantDouble -Value $rows[$i].session_seconds -ParsedValue ([ref]$currentSeconds))) {
                if ($selectedRows.Count -gt 0) {
                    break
                }
                continue
            }
            if (-not $hasLastSessionSeconds) {
                $selectedRows.Add($rows[$i])
                $lastSessionSeconds = $currentSeconds
                $hasLastSessionSeconds = $true
                continue
            }
            if ($currentSeconds -le ($lastSessionSeconds + 0.001)) {
                $selectedRows.Add($rows[$i])
                $lastSessionSeconds = $currentSeconds
            } else {
                break
            }
        }
    } else {
        foreach ($row in $rows) {
            $selectedRows.Add($row)
        }
    }

    if ($selectedRows.Count -eq 0) {
        return [PSCustomObject]@{
            resolved = $false
            reason = "unable_to_resolve_latest_session"
            row_count = 0
            duration_seconds = 0.0
            session_start_seconds = [double]::NaN
            session_end_seconds = [double]::NaN
            latest_timestamp_utc = ""
            metrics_path = (Resolve-Path -LiteralPath $MetricsFilePath).Path
            schema_present = $false
            schema_version = ""
            schema_current = $false
        }
    }

    $sessionRows = $selectedRows.ToArray()
    [Array]::Reverse($sessionRows)
    $hasSchemaColumn = $sessionRows[0].PSObject.Properties.Name -contains "telemetry_schema_version"
    $schemaVersion = ""
    if ($hasSchemaColumn) {
        $schemaValues = @(
            $sessionRows |
                ForEach-Object { [string]$_.telemetry_schema_version } |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
                Select-Object -Unique
        )
        if ($schemaValues.Count -gt 0) {
            $schemaVersion = [string]($schemaValues | Select-Object -Last 1)
        }
    }
    $schemaCurrent = if ([string]::IsNullOrWhiteSpace($RequiredSchemaVersion)) {
        $true
    } else {
        $hasSchemaColumn -and ($schemaVersion -eq $RequiredSchemaVersion)
    }
    $firstSessionSeconds = 0.0
    $lastSessionSeconds = 0.0
    $hasFirst = $hasSessionSeconds -and (Try-ParseInvariantDouble -Value $sessionRows[0].session_seconds -ParsedValue ([ref]$firstSessionSeconds))
    $hasLast = $hasSessionSeconds -and (Try-ParseInvariantDouble -Value $sessionRows[-1].session_seconds -ParsedValue ([ref]$lastSessionSeconds))

    $durationSeconds = 0.0
    if ($hasFirst -and $hasLast) {
        $durationSeconds = [Math]::Max(0.0, $lastSessionSeconds - $firstSessionSeconds)
    } else {
        $durationSeconds = [double]$sessionRows.Count
    }
    $latestTimestampUtc = ""
    if ($hasTimestamp) {
        $latestTimestamp = [datetime]::MinValue
        if (Try-ParseMetricsTimestamp -Value $sessionRows[-1].timestamp -ParsedTimestamp ([ref]$latestTimestamp)) {
            $latestTimestampUtc = $latestTimestamp.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        }
    }

    return [PSCustomObject]@{
        resolved = $true
        reason = "latest_session"
        row_count = $sessionRows.Count
        duration_seconds = [Math]::Round($durationSeconds, 3)
        session_start_seconds = if ($hasFirst) { [Math]::Round($firstSessionSeconds, 3) } else { [double]::NaN }
        session_end_seconds = if ($hasLast) { [Math]::Round($lastSessionSeconds, 3) } else { [double]::NaN }
        latest_timestamp_utc = $latestTimestampUtc
        metrics_path = (Resolve-Path -LiteralPath $MetricsFilePath).Path
        schema_present = $hasSchemaColumn
        schema_version = $schemaVersion
        schema_current = $schemaCurrent
    }
}

function Get-CampaignStatus {
    param(
        [string]$CampaignStatusScript,
        [string]$ResultsFilePath
    )

    Reset-LastExitCode
    $status = & $CampaignStatusScript -ResultsPath $ResultsFilePath -PassThru
    if ($status -is [System.Array]) {
        $status = $status | Select-Object -Last 1
    }
    if ($null -eq $status) {
        throw "Unable to compute campaign status"
    }
    return $status
}

function Get-ActiveCaptureInfo {
    param(
        [string]$StateFilePath,
        [string]$DefaultMetricsPath,
        [string]$ResolverScriptPath,
        [string]$PrismRootPath,
        [string]$PrismInstanceName,
        [bool]$DisableAutoDiscovery
    )

    if (-not (Test-Path -LiteralPath $StateFilePath -PathType Leaf)) {
        return $null
    }

    $state = Get-Content -LiteralPath $StateFilePath -Raw | ConvertFrom-Json
    $stateMetricsPath = [string]$state.metrics_path
    $preferredMetricsPath = if ([string]::IsNullOrWhiteSpace($stateMetricsPath)) { $DefaultMetricsPath } else { $stateMetricsPath }
    $resolvedMetricsPath = Resolve-MetricsPath `
        -PreferredPath $preferredMetricsPath `
        -ResolverScriptPath $ResolverScriptPath `
        -PrismRootPath $PrismRootPath `
        -PrismInstanceName $PrismInstanceName `
        -DisableAutoDiscovery $DisableAutoDiscovery

    if ($null -eq $resolvedMetricsPath) {
        throw "Active capture found but metrics file is unavailable."
    }

    $rows = @(Import-Csv -LiteralPath $resolvedMetricsPath)
    $startCount = [int]$state.start_row_count
    $newRows = [Math]::Max(0, $rows.Count - $startCount)

    return [PSCustomObject]@{
        scene = [string]$state.scene
        profile = [string]$state.profile
        build = [string]$state.build
        start_row_count = $startCount
        current_row_count = $rows.Count
        new_rows = $newRows
        metrics_path = $resolvedMetricsPath
    }
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$candidateRootResolved = if ([System.IO.Path]::IsPathRooted($CandidateRoot)) {
    $CandidateRoot
} else {
    Join-Path $repoRoot $CandidateRoot
}
$campaignStatusScript = Resolve-ScriptPath -PathValue ".\tools\ab_campaign_status.ps1" -RepoRoot $repoRoot
$campaignNextScript = Resolve-ScriptPath -PathValue ".\tools\ab_campaign_next.ps1" -RepoRoot $repoRoot
$markFinishScript = Resolve-ScriptPath -PathValue ".\tools\ab_mark_finish.ps1" -RepoRoot $repoRoot
$buildCandidateScript = Resolve-ScriptPath -PathValue ".\tools\build_beta_candidate.ps1" -RepoRoot $repoRoot
$metricsResolverScript = Resolve-ScriptPath -PathValue ".\tools\resolve_pauc_metrics_path.ps1" -RepoRoot $repoRoot
$errorSortingScript = $null
if ($RunErrorSortingPass) {
    $errorSortingScript = Resolve-ScriptPath -PathValue ".\tools\run_error_sorting_pass.ps1" -RepoRoot $repoRoot
}
$autopilotStatePathResolved = if ([System.IO.Path]::IsPathRooted($AutopilotStatePath)) {
    $AutopilotStatePath
} else {
    Join-Path $repoRoot $AutopilotStatePath
}
$summaryOutputPathResolved = if ([string]::IsNullOrWhiteSpace($SummaryOutputPath)) {
    ""
} else {
    $summaryPathCandidate = if ([System.IO.Path]::IsPathRooted($SummaryOutputPath)) {
        $SummaryOutputPath
    } else {
        Join-Path $repoRoot $SummaryOutputPath
    }
    try {
        [System.IO.Path]::GetFullPath($summaryPathCandidate)
    } catch {
        $summaryPathCandidate
    }
}
$autopilotState = Read-AutopilotState -FilePath $autopilotStatePathResolved
$lastProcessedMetricsSignature = [string]$autopilotState.last_processed_metrics_signature
$cachedCandidateDir = [string]$autopilotState.last_candidate_dir
$cachedCandidateDecision = [string]$autopilotState.last_candidate_decision
$cachedCandidateReadiness = [string]$autopilotState.last_candidate_readiness_percent
$cachedCandidateTimestampUtc = [string]$autopilotState.last_candidate_timestamp_utc
$cachedCandidateServerGovernorHealth = ""
$cachedCandidateServerGovernorInsufficientPressure = ""
$cachedCandidateUpdatedFromSnapshot = $false
$latestCandidateSnapshot = Get-LatestCandidateSnapshot -CandidateRootPath $candidateRootResolved
if ($null -ne $latestCandidateSnapshot -and -not [string]::IsNullOrWhiteSpace([string]$latestCandidateSnapshot.decision)) {
    $latestCandidateDir = [string]$latestCandidateSnapshot.candidate_dir
    if ([string]::IsNullOrWhiteSpace($cachedCandidateDecision) -or `
            [string]::IsNullOrWhiteSpace($cachedCandidateDir) -or `
            ($latestCandidateDir -ne $cachedCandidateDir)) {
        $cachedCandidateDir = $latestCandidateDir
        $cachedCandidateDecision = [string]$latestCandidateSnapshot.decision
        $cachedCandidateReadiness = [string]$latestCandidateSnapshot.readiness_percent
        $cachedCandidateTimestampUtc = [string]$latestCandidateSnapshot.timestamp_utc
        $cachedCandidateServerGovernorHealth = [string]$latestCandidateSnapshot.server_governor_health
        $cachedCandidateServerGovernorInsufficientPressure = [string]$latestCandidateSnapshot.server_governor_skipped_for_insufficient_pressure
        $cachedCandidateUpdatedFromSnapshot = $true
    } else {
        $cachedCandidateServerGovernorHealth = [string]$latestCandidateSnapshot.server_governor_health
        $cachedCandidateServerGovernorInsufficientPressure = [string]$latestCandidateSnapshot.server_governor_skipped_for_insufficient_pressure
    }
}
if ($cachedCandidateUpdatedFromSnapshot) {
    $autopilotState = [PSCustomObject]@{
        last_processed_metrics_signature = [string]$autopilotState.last_processed_metrics_signature
        last_processed_at_utc = [string]$autopilotState.last_processed_at_utc
        last_processed_metrics_path = [string]$autopilotState.last_processed_metrics_path
        last_candidate_dir = $cachedCandidateDir
        last_candidate_decision = $cachedCandidateDecision
        last_candidate_readiness_percent = $cachedCandidateReadiness
        last_candidate_timestamp_utc = $cachedCandidateTimestampUtc
    }
    Write-AutopilotState -FilePath $autopilotStatePathResolved -State $autopilotState
}

$cachedCandidateFreshness = Get-CachedCandidateFreshness `
    -TimestampUtc $cachedCandidateTimestampUtc `
    -MaxAgeMinutes $MaxCachedCandidateAgeMinutes
$cachedCandidateIsFresh = [bool]$cachedCandidateFreshness.is_fresh
$cachedCandidateAgeMinutes = $cachedCandidateFreshness.age_minutes
$cachedCandidateFreshnessStatus = [string]$cachedCandidateFreshness.status
$cachedCandidateEligibleForUse = ($cachedCandidateIsFresh -and -not [string]::IsNullOrWhiteSpace($cachedCandidateDecision))
if (-not $cachedCandidateIsFresh -and -not [string]::IsNullOrWhiteSpace($cachedCandidateDecision)) {
    $cachedAgeLabel = if ($null -eq $cachedCandidateAgeMinutes) { "unknown" } else { [string]$cachedCandidateAgeMinutes }
    Write-Warning ("[Autopilot] Cached candidate ignored: freshness={0}, age_minutes={1}, max_age_minutes={2}." -f `
            $cachedCandidateFreshnessStatus,
            $cachedAgeLabel,
            $MaxCachedCandidateAgeMinutes)
}

$deadline = (Get-Date).AddMinutes($MaxDurationMinutes)
$iteration = 0
$lastAction = "none"
$finalDecision = ""
$finalReadiness = ""
$finalCandidateDir = ""
$latestMetricsRows = 0
$latestMetricsDurationSeconds = 0.0
$latestMetricsPath = ""
$latestMetricsTimestampUtc = ""
$errorSortingStatus = "not_run"
$errorSortingBlockingHits = 0
$errorSortingKnownNoiseHits = 0
$errorSortingKnownNoiseStatus = "not_run"
$errorSortingKnownNoiseWarnHitsTotal = $ErrorSortingNoiseWarnHitsTotal
$errorSortingKnownNoiseFailHitsTotal = $ErrorSortingNoiseFailHitsTotal
$errorSortingTriageEvents = 0
$errorSortingTriageUniqueSignatures = 0
$errorSortingReportMdPath = ""
$errorSortingReportJsonPath = ""
$prismJarSyncStatus = if ($AutoSyncModJarToPrism) { "not_run" } else { "disabled" }
$prismJarSyncSource = ""
$prismJarSyncPath = ""
$prismJarSyncSha256 = ""
$prismJarSyncSkipReason = ""
$prismStartupSyncBlockedByStaleCache = $false
$startupSyncStaleCacheBlockTriggered = $false
$strictCandidateAttemptCount = 0
$strictCandidateRetryUsed = $false
$strictCandidateSuccessAttempt = ""
$strictCandidateAttemptsSummary = ""
$strictCandidateLastError = ""
$strictCandidateRetrySuppressedReason = ""
$strictCandidateRetryKpiStatus = ""
$strictCandidateRetryKpiReportPath = ""
$strictCandidateRetryKpiEvaluated = $false

Push-Location $repoRoot
try {
    if ($AutoSyncModJarToPrism) {
        $startupSyncResult = $null
        $startupSyncBlockedByStaleCachedCandidate = $false
        $cachedDecisionLabel = if ([string]::IsNullOrWhiteSpace($cachedCandidateDecision)) {
            ""
        } else {
            $cachedCandidateDecision.Trim().ToLowerInvariant()
        }

        if ([bool]$EnforceFreshCachedCandidateForStartupSync -and `
                -not $BuildJarBeforeSync -and `
                $cachedDecisionLabel -eq "ready_for_beta" -and `
                -not [string]::IsNullOrWhiteSpace($cachedCandidateDecision) -and `
                -not $cachedCandidateIsFresh -and `
                $MaxCachedCandidateAgeMinutes -gt 0) {
            $startupSyncBlockedByStaleCachedCandidate = $true
            $prismStartupSyncBlockedByStaleCache = $true
            $startupSyncStaleCacheBlockTriggered = $true
            $prismJarSyncStatus = "skipped"
            $prismJarSyncSource = "none"
            $prismJarSyncPath = ""
            $prismJarSyncSha256 = ""
            $prismJarSyncSkipReason = "stale_cached_candidate_startup_sync_blocked"
            $cachedAgeLabel = if ($null -eq $cachedCandidateAgeMinutes) { "unknown" } else { [string]$cachedCandidateAgeMinutes }
            Write-Warning ("[Autopilot] Startup Prism sync skipped: cached ready candidate is stale (age_minutes={0}, max_age_minutes={1})." -f `
                    $cachedAgeLabel,
                    $MaxCachedCandidateAgeMinutes)
        }

        if (-not $startupSyncBlockedByStaleCachedCandidate -and `
                -not $BuildJarBeforeSync -and `
                $cachedDecisionLabel -eq "ready_for_beta" -and `
                $cachedCandidateIsFresh -and `
                -not [string]::IsNullOrWhiteSpace($cachedCandidateDir)) {
            $startupSyncResult = Sync-CandidateModJarToPrism `
                -CandidateDir $cachedCandidateDir `
                -RepoRoot $repoRoot `
                -PrismRootPath $PrismRoot `
                -PrismInstanceName $InstanceName
        }
        $startupJarSynced = ($null -ne $startupSyncResult -and [bool]$startupSyncResult.synced)
        if (-not $startupSyncBlockedByStaleCachedCandidate -and -not $startupJarSynced) {
            $startupSyncResult = Sync-LatestModJarToPrism `
                -RepoRoot $repoRoot `
                -PrismRootPath $PrismRoot `
                -PrismInstanceName $InstanceName `
                -BuildJar:$BuildJarBeforeSync
        }
        if (-not $startupSyncBlockedByStaleCachedCandidate -and $null -ne $startupSyncResult) {
            $prismJarSyncStatus = if ([bool]$startupSyncResult.synced) { "synced" } else { "skipped" }
            $prismJarSyncSource = [string]$startupSyncResult.source
            $prismJarSyncPath = [string]$startupSyncResult.jar_path
            $prismJarSyncSha256 = [string]$startupSyncResult.jar_sha256
            if ([bool]$startupSyncResult.synced) {
                $prismJarSyncSkipReason = ""
            } elseif ([string]::IsNullOrWhiteSpace($prismJarSyncSkipReason)) {
                $prismJarSyncSkipReason = "startup_sync_not_synced"
            }
        }
    }

    while ($true) {
        $iteration++
        $now = Get-Date
        Write-Host ""
        Write-Host ("[Autopilot] Iteration {0} at {1}" -f $iteration, $now.ToString("yyyy-MM-dd HH:mm:ss"))

        $campaignStatus = Get-CampaignStatus -CampaignStatusScript $campaignStatusScript -ResultsFilePath $ResultsPath
        $missingCells = [int]$campaignStatus.missing_cells

        $captureInfo = Get-ActiveCaptureInfo `
            -StateFilePath $StatePath `
            -DefaultMetricsPath $MetricsPath `
            -ResolverScriptPath $metricsResolverScript `
            -PrismRootPath $PrismRoot `
            -PrismInstanceName $InstanceName `
            -DisableAutoDiscovery:$DisableAutoMetricsDiscovery

        if ($null -ne $captureInfo) {
            Write-Host ("[Autopilot] Active capture {0}/{1}: +{2} rows ({3} -> {4})" -f `
                    $captureInfo.scene,
                    $captureInfo.profile,
                    $captureInfo.new_rows,
                    $captureInfo.start_row_count,
                    $captureInfo.current_row_count)

            if ($captureInfo.new_rows -ge $MinRowsForCaptureFinish) {
                Write-Host ("[Autopilot] Finishing capture (min rows met: {0})." -f $MinRowsForCaptureFinish)
                $finishArgs = @{
                    MetricsPath = $captureInfo.metrics_path
                    StatePath = $StatePath
                    ResultsPath = $ResultsPath
                    MinNewRows = $MinRowsForCaptureFinish
                    AutoPrepareNext = $true
                    ShowCampaignStatus = $true
                    PrismRoot = $PrismRoot
                    InstanceName = $InstanceName
                }
                if (-not [string]::IsNullOrWhiteSpace($Build)) {
                    $finishArgs.Build = $Build
                }
                if ($AutoApplyProfileForNext) {
                    $finishArgs.ApplyProfileForNext = $true
                }
                if ($DisableAutoMetricsDiscovery) {
                    $finishArgs.DisableAutoMetricsDiscovery = $true
                }

                Reset-LastExitCode
                & $markFinishScript @finishArgs
                if ((Get-LastExitCodeOrZero) -ne 0) {
                    throw "ab_mark_finish failed"
                }
                $lastAction = "capture_finished"
            } else {
                $remaining = [Math]::Max(0, $MinRowsForCaptureFinish - $captureInfo.new_rows)
                Write-Host ("[Autopilot] Waiting for more metrics rows before finish: {0} remaining." -f $remaining)
                $lastAction = "waiting_capture_rows"
            }
        } elseif ($missingCells -gt 0) {
            Write-Host ("[Autopilot] Campaign incomplete ({0} missing). Preparing next capture." -f $missingCells)
            $nextArgs = @{
                ResultsPath = $ResultsPath
                StartCapture = $true
                OverwriteCapture = $true
                MetricsPath = $MetricsPath
                StatePath = $StatePath
                PrismRoot = $PrismRoot
                InstanceName = $InstanceName
            }
            if (-not [string]::IsNullOrWhiteSpace($Build)) {
                $nextArgs.Build = $Build
            }
            if ($AutoApplyProfileForNext) {
                $nextArgs.ApplyProfile = $true
            }
            if ($DisableAutoMetricsDiscovery) {
                $nextArgs.DisableAutoMetricsDiscovery = $true
            }

            Reset-LastExitCode
            & $campaignNextScript @nextArgs
            if ((Get-LastExitCodeOrZero) -ne 0) {
                throw "ab_campaign_next failed"
            }
            $lastAction = "capture_started"
        } else {
            $latestMetricsPath = Resolve-MetricsPath `
                -PreferredPath $MetricsPath `
                -ResolverScriptPath $metricsResolverScript `
                -PrismRootPath $PrismRoot `
                -PrismInstanceName $InstanceName `
                -DisableAutoDiscovery:$DisableAutoMetricsDiscovery
            $latestSessionStats = Get-LatestMetricsSessionStats `
                -MetricsFilePath $latestMetricsPath `
                -RequiredSchemaVersion $RequiredTelemetrySchemaVersion
            $latestMetricsRows = [int]$latestSessionStats.row_count
            $latestMetricsDurationSeconds = [double]$latestSessionStats.duration_seconds
            $latestMetricsPath = [string]$latestSessionStats.metrics_path
            $latestMetricsTimestampUtc = [string]$latestSessionStats.latest_timestamp_utc

            if (-not [bool]$latestSessionStats.resolved) {
                Write-Host ("[Autopilot] Campaign complete but metrics session unavailable ({0}). Waiting for telemetry." -f $latestSessionStats.reason)
                if ($cachedCandidateIsFresh -and (Write-CachedCandidateStatus `
                        -Decision $cachedCandidateDecision `
                        -ReadinessPercent $cachedCandidateReadiness `
                        -CandidateDir $cachedCandidateDir `
                        -ServerGovernorHealth $cachedCandidateServerGovernorHealth `
                        -ServerGovernorInsufficientPressure $cachedCandidateServerGovernorInsufficientPressure)) {
                    $finalCandidateDir = $cachedCandidateDir
                }
                $lastAction = "waiting_candidate_metrics"
                $finalDecision = "pending_metrics"
            } elseif ($latestMetricsRows -lt $MinMetricsRowsForCandidatePreflight -or $latestMetricsDurationSeconds -lt $MinMetricsDurationSecondsForCandidatePreflight) {
                Write-Host ("[Autopilot] Campaign complete but latest metrics session is too short: rows={0}/{1}, duration={2}s/{3}s. Waiting." -f `
                        $latestMetricsRows,
                        $MinMetricsRowsForCandidatePreflight,
                        [Math]::Round($latestMetricsDurationSeconds, 1),
                        $MinMetricsDurationSecondsForCandidatePreflight)
                if ($cachedCandidateIsFresh -and (Write-CachedCandidateStatus `
                        -Decision $cachedCandidateDecision `
                        -ReadinessPercent $cachedCandidateReadiness `
                        -CandidateDir $cachedCandidateDir `
                        -ServerGovernorHealth $cachedCandidateServerGovernorHealth `
                        -ServerGovernorInsufficientPressure $cachedCandidateServerGovernorInsufficientPressure)) {
                    $finalCandidateDir = $cachedCandidateDir
                }
                $lastAction = "waiting_candidate_metrics"
                $finalDecision = "pending_metrics"
            } elseif (-not [bool]$latestSessionStats.schema_current) {
                $detectedSchema = if ([string]::IsNullOrWhiteSpace([string]$latestSessionStats.schema_version)) {
                    if ([bool]$latestSessionStats.schema_present) { "blank" } else { "missing" }
                } else {
                    [string]$latestSessionStats.schema_version
                }
                Write-Host ("[Autopilot] Campaign complete but latest metrics schema is outdated: detected={0}, required={1}. Waiting for runtime restart." -f `
                        $detectedSchema,
                        $RequiredTelemetrySchemaVersion)
                if ($cachedCandidateIsFresh -and (Write-CachedCandidateStatus -Decision $cachedCandidateDecision -ReadinessPercent $cachedCandidateReadiness -CandidateDir $cachedCandidateDir)) {
                    $finalCandidateDir = $cachedCandidateDir
                }
                $lastAction = "waiting_candidate_metrics_schema"
                $finalDecision = "pending_metrics"
            } else {
                $currentMetricsSignature = Get-MetricsSessionSignature -SessionStats $latestSessionStats
                if (-not [string]::IsNullOrWhiteSpace($lastProcessedMetricsSignature) -and $currentMetricsSignature -eq $lastProcessedMetricsSignature) {
                    $latestLabel = if ([string]::IsNullOrWhiteSpace($latestMetricsTimestampUtc)) { "unknown" } else { $latestMetricsTimestampUtc }
                    Write-Host ("[Autopilot] Campaign complete but no new telemetry since last candidate attempt (latest={0}). Waiting for fresh gameplay capture." -f $latestLabel)
                    if ($cachedCandidateIsFresh -and (Write-CachedCandidateStatus `
                            -Decision $cachedCandidateDecision `
                            -ReadinessPercent $cachedCandidateReadiness `
                            -CandidateDir $cachedCandidateDir `
                            -ServerGovernorHealth $cachedCandidateServerGovernorHealth `
                            -ServerGovernorInsufficientPressure $cachedCandidateServerGovernorInsufficientPressure)) {
                        $finalCandidateDir = $cachedCandidateDir
                    }
                    $lastAction = "waiting_candidate_metrics_new"
                    $finalDecision = "pending_metrics"
                } else {
                    Write-Host "[Autopilot] Campaign complete. Running strict beta candidate pipeline."
                    $candidateArgsBase = @{
                        CandidateRoot = $CandidateRoot
                        ReportsDir = $ReportsDir
                        MetricsPath = $MetricsPath
                        PrismRoot = $PrismRoot
                        PrismInstanceName = $InstanceName
                        ResultsPath = $ResultsPath
                        StrictPreflight = $true
                        StrictReadiness = $true
                        StrictMetricsFreshness = $true
                        MaxMetricsAgeMinutes = $MaxMetricsAgeMinutes
                        MetricsCodeDriftToleranceMinutes = $MetricsCodeDriftToleranceMinutes
                        RequiredTelemetrySchemaVersion = $RequiredTelemetrySchemaVersion
                        FrameMsP95Max = $FrameMsP95Max
                        FrameMsP99Max = $FrameMsP99Max
                        MsptP95Max = $MsptP95Max
                    }
                    if ($DisableAutoMetricsDiscovery) {
                        $candidateArgsBase.DisableAutoMetricsDiscovery = $true
                    }
                    if ($SyncTelemetryToRepo) {
                        $candidateArgsBase.SyncTelemetryToRepo = $true
                        $candidateArgsBase.TelemetrySyncDestination = $TelemetrySyncDestination
                        $candidateArgsBase.SyncTelemetryCaptureState = $SyncTelemetryCaptureState
                    }

                    $attemptConfigs = New-Object 'System.Collections.Generic.List[object]'
                    $attemptKeys = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
                    Add-CandidateBuildAttemptConfig `
                        -Configs $attemptConfigs `
                        -Keys $attemptKeys `
                        -Name "primary" `
                        -UseFullMetricsHistory:([bool]$CandidateUseFullMetricsHistory) `
                        -TailSeconds $CandidateMetricsTailSeconds `
                        -TailSamples $CandidateMetricsTailSamples `
                        -WarmupTrimSeconds $CandidateMetricsWarmupTrimSeconds

                    if ($EnableStrictCandidateWindowRetry) {
                        if (-not [bool]$CandidateUseFullMetricsHistory) {
                            Add-CandidateBuildAttemptConfig `
                                -Configs $attemptConfigs `
                                -Keys $attemptKeys `
                                -Name "full_history_retry" `
                                -UseFullMetricsHistory:$true `
                                -TailSeconds 0 `
                                -TailSamples 0 `
                                -WarmupTrimSeconds $CandidateMetricsWarmupTrimSeconds
                        } elseif ($StrictCandidateRetryTailSeconds -gt 0) {
                            Add-CandidateBuildAttemptConfig `
                                -Configs $attemptConfigs `
                                -Keys $attemptKeys `
                                -Name "tail_retry" `
                                -UseFullMetricsHistory:$false `
                                -TailSeconds $StrictCandidateRetryTailSeconds `
                                -TailSamples 0 `
                                -WarmupTrimSeconds $CandidateMetricsWarmupTrimSeconds
                        }
                    }

                    $existingCandidates = @(Get-ChildItem -LiteralPath $CandidateRoot -Directory -Filter "beta_candidate_*" -ErrorAction SilentlyContinue)
                    $existingCandidateSet = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
                    foreach ($candidate in $existingCandidates) {
                        [void]$existingCandidateSet.Add([System.IO.Path]::GetFullPath($candidate.FullName))
                    }

                    $strictCandidateAttemptCount = 0
                    $strictCandidateRetryUsed = $false
                    $strictCandidateSuccessAttempt = ""
                    $strictCandidateLastError = ""
                    $strictCandidateAttemptsSummary = ""
                    $strictCandidateRetrySuppressedReason = ""
                    $strictCandidateRetryKpiStatus = ""
                    $strictCandidateRetryKpiReportPath = ""
                    $strictCandidateRetryKpiEvaluated = $false
                    $attemptSummaryItems = New-Object 'System.Collections.Generic.List[string]'

                    $buildExitCode = 1
                    $candidateForDecision = $null
                    foreach ($attemptConfig in $attemptConfigs) {
                        $strictCandidateAttemptCount++
                        $attemptArgs = @{}
                        foreach ($arg in $candidateArgsBase.GetEnumerator()) {
                            $attemptArgs[$arg.Key] = $arg.Value
                        }

                        $attemptArgs.MetricsWarmupTrimSeconds = [int]$attemptConfig.warmup_trim_seconds
                        if ([bool]$attemptConfig.use_full_metrics_history) {
                            $attemptArgs.UseFullMetricsHistory = $true
                        }
                        if ([int]$attemptConfig.tail_seconds -gt 0) {
                            $attemptArgs.MetricsTailSeconds = [int]$attemptConfig.tail_seconds
                        }
                        if ([int]$attemptConfig.tail_samples -gt 0) {
                            $attemptArgs.MetricsTailSamples = [int]$attemptConfig.tail_samples
                        }

                        $attemptName = [string]$attemptConfig.name
                        Write-Host ("[Autopilot] Strict candidate attempt {0}/{1}: profile={2}, full_history={3}, tail_seconds={4}, tail_samples={5}, warmup_trim={6}" -f `
                                $strictCandidateAttemptCount,
                                $attemptConfigs.Count,
                                $attemptName,
                                [bool]$attemptConfig.use_full_metrics_history,
                                [int]$attemptConfig.tail_seconds,
                                [int]$attemptConfig.tail_samples,
                                [int]$attemptConfig.warmup_trim_seconds)

                        $attemptExitCode = 0
                        $attemptErrorMessage = ""
                        try {
                            Reset-LastExitCode
                            & $buildCandidateScript @attemptArgs
                            $attemptExitCode = Get-LastExitCodeOrZero
                        } catch {
                            $attemptExitCode = if ((Get-LastExitCodeOrZero) -eq 0) { 1 } else { Get-LastExitCodeOrZero }
                            $attemptErrorMessage = $_.Exception.Message
                            Write-Warning ("Strict beta candidate attempt {0} failed: {1}" -f $attemptName, $attemptErrorMessage)
                        }

                        if ($attemptExitCode -eq 0) {
                            $buildExitCode = 0
                            $strictCandidateSuccessAttempt = $attemptName
                            $attemptSummaryItems.Add(("{0}:success(exit=0)" -f $attemptName)) | Out-Null

                            $allCandidates = @(Get-ChildItem -LiteralPath $CandidateRoot -Directory -Filter "beta_candidate_*" -ErrorAction SilentlyContinue |
                                    Sort-Object Name -Descending)
                            $newCandidate = $null
                            foreach ($candidate in $allCandidates) {
                                $candidateKey = [System.IO.Path]::GetFullPath($candidate.FullName)
                                if (-not $existingCandidateSet.Contains($candidateKey)) {
                                    $newCandidate = $candidate
                                    break
                                }
                            }

                            if ($null -ne $newCandidate) {
                                $candidateForDecision = $newCandidate
                            } elseif ($allCandidates.Count -gt 0) {
                                $candidateForDecision = $allCandidates[0]
                                Write-Warning "[Autopilot] No new beta candidate detected after successful build. Falling back to latest candidate."
                            }
                            break
                        } else {
                            $buildExitCode = $attemptExitCode
                            $strictCandidateLastError = $attemptErrorMessage
                            $attemptSummaryItems.Add(("{0}:failed(exit={1})" -f $attemptName, $attemptExitCode)) | Out-Null
                            if ($strictCandidateAttemptCount -lt $attemptConfigs.Count) {
                                $shouldRetry = $true
                                if ($RetryStrictCandidateOnlyOnKpiFailure) {
                                    $kpiProbe = Get-LatestPreflightKpiStatus -ReportsDirPath $ReportsDir
                                    $strictCandidateRetryKpiEvaluated = $true
                                    $strictCandidateRetryKpiStatus = [string]$kpiProbe.kpi_status
                                    $strictCandidateRetryKpiReportPath = [string]$kpiProbe.report_path
                                    $kpiStatusLabel = [string]$kpiProbe.kpi_status
                                    $kpiStatusNormalized = if ([string]::IsNullOrWhiteSpace($kpiStatusLabel)) {
                                        ""
                                    } else {
                                        $kpiStatusLabel.Trim().ToLowerInvariant()
                                    }
                                    if (-not (Test-IsKpiFailStatus -StatusLabel $kpiStatusLabel)) {
                                        $shouldRetry = $false
                                        $strictCandidateRetrySuppressedReason = if ([string]::IsNullOrWhiteSpace($kpiStatusLabel)) {
                                            "kpi_status_unavailable"
                                        } else {
                                            $statusToken = Convert-ToStatusToken -Value $kpiStatusNormalized
                                            if ([string]::IsNullOrWhiteSpace($statusToken)) {
                                                "kpi_status_unknown"
                                            } else {
                                                ("kpi_status_{0}" -f $statusToken)
                                            }
                                        }
                                        $reportLabel = if ([string]::IsNullOrWhiteSpace([string]$kpiProbe.report_path)) {
                                            "n/a"
                                        } else {
                                            [string]$kpiProbe.report_path
                                        }
                                        $statusLabel = if ([string]::IsNullOrWhiteSpace($kpiStatusLabel)) {
                                            "unknown"
                                        } else {
                                            $kpiStatusLabel
                                        }
                                        Write-Warning ("[Autopilot] Strict candidate retry skipped: latest preflight KPI status is '{0}' ({1})." -f $statusLabel, $reportLabel)
                                    }
                                }
                                if ($shouldRetry) {
                                    Write-Warning "[Autopilot] Strict candidate attempt failed. Retrying with alternate metrics window."
                                } else {
                                    break
                                }
                            }
                        }
                    }
                    $strictCandidateRetryUsed = ($strictCandidateAttemptCount -gt 1)
                    if ($attemptSummaryItems.Count -gt 0) {
                        $strictCandidateAttemptsSummary = [string]::Join("; ", $attemptSummaryItems.ToArray())
                    } else {
                        $strictCandidateAttemptsSummary = ""
                    }
                    $lastAction = "beta_candidate_attempt"

                    if ($null -ne $candidateForDecision) {
                        $finalCandidateDir = $candidateForDecision.FullName
                        $readinessPath = Join-Path $candidateForDecision.FullName "beta_readiness.json"
                        if (Test-Path -LiteralPath $readinessPath -PathType Leaf) {
                            $readiness = Get-Content -LiteralPath $readinessPath -Raw | ConvertFrom-Json
                            $finalDecision = [string]$readiness.decision
                            $finalReadiness = [string]$readiness.readiness_percent
                            Write-Host ("[Autopilot] Latest candidate decision: {0} ({1}%)." -f $finalDecision, $finalReadiness)
                            $cachedCandidateDir = $finalCandidateDir
                            $cachedCandidateDecision = $finalDecision
                            $cachedCandidateReadiness = $finalReadiness
                            $cachedCandidateTimestampUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
                            $cachedCandidateServerGovernorHealth = [string]$readiness.server_governor_health
                            $cachedCandidateServerGovernorInsufficientPressure = [string]$readiness.server_governor_skipped_for_insufficient_pressure
                            $cachedCandidateFreshness = Get-CachedCandidateFreshness `
                                -TimestampUtc $cachedCandidateTimestampUtc `
                                -MaxAgeMinutes $MaxCachedCandidateAgeMinutes
                            $cachedCandidateIsFresh = [bool]$cachedCandidateFreshness.is_fresh
                            $cachedCandidateAgeMinutes = $cachedCandidateFreshness.age_minutes
                            $cachedCandidateFreshnessStatus = [string]$cachedCandidateFreshness.status
                        }
                    } elseif ($buildExitCode -ne 0) {
                        $finalCandidateDir = ""
                        $finalDecision = "candidate_build_failed"
                        $finalReadiness = ""
                        $attemptsLabel = if ($strictCandidateAttemptCount -gt 0) { $strictCandidateAttemptCount } else { 1 }
                        Write-Host ("[Autopilot] Strict candidate build failed after {0} attempt(s). Candidate decision is unavailable for this run." -f $attemptsLabel)
                    }

                    $lastProcessedMetricsSignature = $currentMetricsSignature
                    $autopilotState = [PSCustomObject]@{
                        last_processed_metrics_signature = $currentMetricsSignature
                        last_processed_at_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
                        last_processed_metrics_path = $latestMetricsPath
                        last_candidate_dir = $cachedCandidateDir
                        last_candidate_decision = $cachedCandidateDecision
                        last_candidate_readiness_percent = $cachedCandidateReadiness
                        last_candidate_timestamp_utc = $cachedCandidateTimestampUtc
                    }
                    Write-AutopilotState -FilePath $autopilotStatePathResolved -State $autopilotState

                    if ($AutoSyncModJarToPrism -and $buildExitCode -eq 0) {
                        try {
                            $candidateSyncResult = $null
                            if ($null -ne $candidateForDecision) {
                                $candidateSyncResult = Sync-CandidateModJarToPrism `
                                    -CandidateDir $candidateForDecision.FullName `
                                    -RepoRoot $repoRoot `
                                    -PrismRootPath $PrismRoot `
                                    -PrismInstanceName $InstanceName
                            }
                            $candidateJarSynced = ($null -ne $candidateSyncResult -and [bool]$candidateSyncResult.synced)
                            if (-not $candidateJarSynced) {
                                $candidateSyncResult = Sync-LatestModJarToPrism `
                                    -RepoRoot $repoRoot `
                                    -PrismRootPath $PrismRoot `
                                    -PrismInstanceName $InstanceName `
                                    -BuildJar:$false
                            }
                            if ($null -ne $candidateSyncResult) {
                                $prismJarSyncStatus = if ([bool]$candidateSyncResult.synced) { "synced" } else { "skipped" }
                                $prismJarSyncSource = [string]$candidateSyncResult.source
                                $prismJarSyncPath = [string]$candidateSyncResult.jar_path
                                $prismJarSyncSha256 = [string]$candidateSyncResult.jar_sha256
                                if ([bool]$candidateSyncResult.synced) {
                                    $prismJarSyncSkipReason = ""
                                } else {
                                    $prismJarSyncSkipReason = "post_build_sync_not_synced"
                                }
                            }
                        } catch {
                            Write-Warning ("[Autopilot] Post-build Prism sync failed: {0}" -f $_.Exception.Message)
                            $prismJarSyncStatus = "error"
                            $prismJarSyncSource = ""
                            $prismJarSyncPath = ""
                            $prismJarSyncSha256 = ""
                            $prismJarSyncSkipReason = "post_build_sync_error"
                        }
                    }

                    if ($buildExitCode -eq 0 -and $RunErrorSortingPass) {
                        try {
                            $errorSortingArgs = @{
                                InstanceName = $InstanceName
                                PrismInstancesRoot = $PrismRoot
                                OutDir = $ReportsDir
                                TopN = $ErrorSortingTopN
                                KnownNoiseWarnHitsTotal = $ErrorSortingNoiseWarnHitsTotal
                                KnownNoiseFailHitsTotal = $ErrorSortingNoiseFailHitsTotal
                                RunQuarantine = $true
                                PassThru = $true
                            }
                            if ($ErrorSortingIncludeWarnings) {
                                $errorSortingArgs.IncludeWarnings = $true
                            }
                            if ($FailOnErrorSortingBlockingPatterns) {
                                $errorSortingArgs.FailOnBlocking = $true
                            }
                            if ($FailOnErrorSortingNoiseFail) {
                                $errorSortingArgs.FailOnNoiseFail = $true
                            }

                            Reset-LastExitCode
                            $errorSortingRaw = & $errorSortingScript @errorSortingArgs
                            $errorSortingExitCode = Get-LastExitCodeOrZero
                            if ($errorSortingExitCode -ne 0) {
                                throw ("run_error_sorting_pass exited with code {0}" -f $errorSortingExitCode)
                            }
                            $errorSortingSummary = Get-LastOutputObject -Value $errorSortingRaw
                            if ($null -eq $errorSortingSummary) {
                                $errorSortingStatus = "missing_output"
                                Write-Warning "[Autopilot] Error sorting pass returned no summary object."
                            } else {
                                $errorSortingStatus = [string]$errorSortingSummary.overall_status
                                $errorSortingBlockingHits = [int]$errorSortingSummary.blocking_hits_total
                                $errorSortingKnownNoiseHits = [int]$errorSortingSummary.known_noise_hits_total
                                $errorSortingKnownNoiseStatus = [string]$errorSortingSummary.known_noise_status
                                $errorSortingKnownNoiseWarnHitsTotal = [int]$errorSortingSummary.known_noise_warn_hits_total
                                $errorSortingKnownNoiseFailHitsTotal = [int]$errorSortingSummary.known_noise_fail_hits_total
                                $errorSortingTriageEvents = [int]$errorSortingSummary.triage_total_events
                                $errorSortingTriageUniqueSignatures = [int]$errorSortingSummary.triage_unique_signatures
                                $errorSortingReportMdPath = [string]$errorSortingSummary.report_md_path
                                $errorSortingReportJsonPath = [string]$errorSortingSummary.report_json_path
                                Write-Host ("[Autopilot] Error sorting pass: status={0}, blocking_hits={1}, known_noise={2} ({3})" -f `
                                        $errorSortingStatus,
                                        $errorSortingBlockingHits,
                                        $errorSortingKnownNoiseHits,
                                        $errorSortingKnownNoiseStatus)
                            }
                        } catch {
                            $errorSortingStatus = "error"
                            $errorSortingKnownNoiseStatus = "error"
                            Write-Warning ("[Autopilot] Error sorting pass failed: {0}" -f $_.Exception.Message)
                            if ($FailOnErrorSortingBlockingPatterns -or $FailOnErrorSortingNoiseFail) {
                                $buildExitCode = 1
                            }
                        }
                    }

                    if ($buildExitCode -eq 0 -and $finalDecision -eq "ready_for_beta") {
                        break
                    }
                    if ($buildExitCode -ne 0) {
                        $lastAction = "beta_candidate_failed"
                        break
                    }

                    # Campaign complete and candidate still blocked: stop this run to avoid
                    # tight rebuild loops. Relaunch autopilot after new gameplay capture.
                    $lastAction = "beta_candidate_blocked"
                    break
                }
            }
        }

        if ($OneShot) {
            break
        }
        if ((Get-Date) -ge $deadline) {
            break
        }

        Start-Sleep -Seconds $PollIntervalSeconds
    }

    if ($RunErrorSortingPass -and $errorSortingStatus -eq "not_run") {
        try {
            $errorSortingArgs = @{
                InstanceName = $InstanceName
                PrismInstancesRoot = $PrismRoot
                OutDir = $ReportsDir
                TopN = $ErrorSortingTopN
                KnownNoiseWarnHitsTotal = $ErrorSortingNoiseWarnHitsTotal
                KnownNoiseFailHitsTotal = $ErrorSortingNoiseFailHitsTotal
                RunQuarantine = $true
                PassThru = $true
            }
            if ($ErrorSortingIncludeWarnings) {
                $errorSortingArgs.IncludeWarnings = $true
            }
            if ($FailOnErrorSortingBlockingPatterns) {
                $errorSortingArgs.FailOnBlocking = $true
            }
            if ($FailOnErrorSortingNoiseFail) {
                $errorSortingArgs.FailOnNoiseFail = $true
            }

            Reset-LastExitCode
            $errorSortingRaw = & $errorSortingScript @errorSortingArgs
            $errorSortingExitCode = Get-LastExitCodeOrZero
            if ($errorSortingExitCode -ne 0) {
                throw ("run_error_sorting_pass exited with code {0}" -f $errorSortingExitCode)
            }
            $errorSortingSummary = Get-LastOutputObject -Value $errorSortingRaw
            if ($null -eq $errorSortingSummary) {
                $errorSortingStatus = "missing_output"
                Write-Warning "[Autopilot] Error sorting pass returned no summary object."
            } else {
                $errorSortingStatus = [string]$errorSortingSummary.overall_status
                $errorSortingBlockingHits = [int]$errorSortingSummary.blocking_hits_total
                $errorSortingKnownNoiseHits = [int]$errorSortingSummary.known_noise_hits_total
                $errorSortingKnownNoiseStatus = [string]$errorSortingSummary.known_noise_status
                $errorSortingKnownNoiseWarnHitsTotal = [int]$errorSortingSummary.known_noise_warn_hits_total
                $errorSortingKnownNoiseFailHitsTotal = [int]$errorSortingSummary.known_noise_fail_hits_total
                $errorSortingTriageEvents = [int]$errorSortingSummary.triage_total_events
                $errorSortingTriageUniqueSignatures = [int]$errorSortingSummary.triage_unique_signatures
                $errorSortingReportMdPath = [string]$errorSortingSummary.report_md_path
                $errorSortingReportJsonPath = [string]$errorSortingSummary.report_json_path
                Write-Host ("[Autopilot] Error sorting pass: status={0}, blocking_hits={1}, known_noise={2} ({3})" -f `
                        $errorSortingStatus,
                        $errorSortingBlockingHits,
                        $errorSortingKnownNoiseHits,
                        $errorSortingKnownNoiseStatus)
            }
        } catch {
            $errorSortingStatus = "error"
            $errorSortingKnownNoiseStatus = "error"
            Write-Warning ("[Autopilot] Error sorting pass failed: {0}" -f $_.Exception.Message)
            if ($FailOnErrorSortingBlockingPatterns -or $FailOnErrorSortingNoiseFail) {
                throw
            }
        }
    }

$cachedCandidateFreshness = Get-CachedCandidateFreshness `
    -TimestampUtc $cachedCandidateTimestampUtc `
    -MaxAgeMinutes $MaxCachedCandidateAgeMinutes
$cachedCandidateIsFresh = [bool]$cachedCandidateFreshness.is_fresh
$cachedCandidateAgeMinutes = $cachedCandidateFreshness.age_minutes
$cachedCandidateFreshnessStatus = [string]$cachedCandidateFreshness.status
$cachedCandidateEligibleForUse = ($cachedCandidateIsFresh -and -not [string]::IsNullOrWhiteSpace($cachedCandidateDecision))

$result = [PSCustomObject]@{
        timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        iterations = $iteration
        one_shot = [bool]$OneShot
        last_action = $lastAction
        final_decision = $finalDecision
        final_readiness_percent = $finalReadiness
        final_candidate_dir = $finalCandidateDir
        latest_metrics_rows = $latestMetricsRows
        latest_metrics_duration_seconds = [Math]::Round($latestMetricsDurationSeconds, 3)
        latest_metrics_timestamp_utc = $latestMetricsTimestampUtc
        latest_metrics_path = $latestMetricsPath
        target_frame_ms_p95_max = $FrameMsP95Max
        target_frame_ms_p99_max = $FrameMsP99Max
        target_mspt_p95_max = $MsptP95Max
        candidate_metrics_warmup_trim_seconds = $CandidateMetricsWarmupTrimSeconds
        candidate_metrics_tail_seconds = $CandidateMetricsTailSeconds
        candidate_metrics_tail_samples = $CandidateMetricsTailSamples
        candidate_use_full_metrics_history = [bool]$CandidateUseFullMetricsHistory
        enable_strict_candidate_window_retry = [bool]$EnableStrictCandidateWindowRetry
        strict_candidate_retry_tail_seconds = $StrictCandidateRetryTailSeconds
        retry_strict_candidate_only_on_kpi_failure = [bool]$RetryStrictCandidateOnlyOnKpiFailure
        strict_candidate_attempt_count = $strictCandidateAttemptCount
        strict_candidate_retry_used = $strictCandidateRetryUsed
        strict_candidate_success_attempt = $strictCandidateSuccessAttempt
        strict_candidate_attempts_summary = $strictCandidateAttemptsSummary
        strict_candidate_last_error = $strictCandidateLastError
        strict_candidate_retry_suppressed_reason = $strictCandidateRetrySuppressedReason
        strict_candidate_retry_kpi_evaluated = $strictCandidateRetryKpiEvaluated
        strict_candidate_retry_kpi_status = $strictCandidateRetryKpiStatus
        strict_candidate_retry_kpi_report_path = $strictCandidateRetryKpiReportPath
        error_sorting_status = $errorSortingStatus
        error_sorting_blocking_hits = $errorSortingBlockingHits
        error_sorting_known_noise_hits = $errorSortingKnownNoiseHits
        error_sorting_known_noise_status = $errorSortingKnownNoiseStatus
        error_sorting_known_noise_warn_hits_total = $errorSortingKnownNoiseWarnHitsTotal
        error_sorting_known_noise_fail_hits_total = $errorSortingKnownNoiseFailHitsTotal
        error_sorting_triage_total_events = $errorSortingTriageEvents
        error_sorting_triage_unique_signatures = $errorSortingTriageUniqueSignatures
        error_sorting_report_md_path = $errorSortingReportMdPath
        error_sorting_report_json_path = $errorSortingReportJsonPath
        cached_candidate_decision = $cachedCandidateDecision
        cached_candidate_readiness_percent = $cachedCandidateReadiness
        cached_candidate_dir = $cachedCandidateDir
        cached_candidate_timestamp_utc = $cachedCandidateTimestampUtc
        max_cached_candidate_age_minutes = $MaxCachedCandidateAgeMinutes
        cached_candidate_is_fresh = $cachedCandidateIsFresh
        cached_candidate_eligible_for_use = $cachedCandidateEligibleForUse
        cached_candidate_age_minutes = $cachedCandidateAgeMinutes
        cached_candidate_freshness_status = $cachedCandidateFreshnessStatus
        cached_candidate_server_governor_health = $cachedCandidateServerGovernorHealth
        cached_candidate_server_governor_skipped_for_insufficient_pressure = $cachedCandidateServerGovernorInsufficientPressure
        prism_jar_sync_status = $prismJarSyncStatus
        prism_jar_sync_source = $prismJarSyncSource
        prism_jar_sync_path = $prismJarSyncPath
        prism_jar_sync_sha256 = $prismJarSyncSha256
        prism_jar_sync_skip_reason = $prismJarSyncSkipReason
        prism_startup_sync_blocked_by_stale_cache = $prismStartupSyncBlockedByStaleCache
        fail_on_pending_metrics_decision = [bool]$FailOnPendingMetricsDecision
        fail_on_no_effective_decision = [bool]$FailOnNoEffectiveDecision
        fail_on_effective_decision_not_ready_for_beta = [bool]$FailOnEffectiveDecisionNotReadyForBeta
        fail_on_cached_decision_source = [bool]$FailOnCachedDecisionSource
        fail_on_prism_jar_sync_not_synced = [bool]$FailOnPrismJarSyncNotSynced
        fail_on_summary_output_write_error = [bool]$FailOnSummaryOutputWriteError
        fail_on_startup_sync_stale_cache_block = [bool]$FailOnStartupSyncStaleCacheBlock
        fail_on_error_sorting_status_not_pass = [bool]$FailOnErrorSortingStatusNotPass
        fail_on_error_sorting_noise_warn = [bool]$FailOnErrorSortingNoiseWarn
        enforce_fresh_cached_candidate_for_startup_sync = [bool]$EnforceFreshCachedCandidateForStartupSync
        prefer_cached_decision_on_build_failure = [bool]$PreferCachedDecisionOnBuildFailure
        decision_source = "none"
        effective_decision = ""
        effective_readiness_percent = ""
        decision_freshness = "unknown"
        decision_override_reason = ""
        autopilot_failure_reason = ""
        autopilot_failed = $false
        summary_output_path = $summaryOutputPathResolved
        summary_output_compressed = [bool]$SummaryOutputCompress
        summary_output_written = $false
        summary_output_written_utc = ""
        summary_output_error = ""
        state_path = $StatePath
        autopilot_state_path = $autopilotStatePathResolved
        results_path = $ResultsPath
    }

    if (-not [string]::IsNullOrWhiteSpace($result.final_decision) -and $result.final_decision -ne "pending_metrics") {
        if ($result.final_decision -eq "candidate_build_failed" -and `
                [bool]$PreferCachedDecisionOnBuildFailure -and `
                [bool]$result.cached_candidate_eligible_for_use) {
            $result.decision_source = "cached_candidate_fallback"
            $result.effective_decision = $result.cached_candidate_decision
            $result.effective_readiness_percent = $result.cached_candidate_readiness_percent
            $result.decision_freshness = "fresh_failure_cached_fallback"
            $result.decision_override_reason = "fresh_candidate_build_failed_using_cached_candidate"
        } else {
            $result.decision_source = "fresh_candidate"
            $result.effective_decision = $result.final_decision
            $result.effective_readiness_percent = $result.final_readiness_percent
            $result.decision_freshness = "fresh"
            if ($result.final_decision -eq "candidate_build_failed" -and `
                    [bool]$PreferCachedDecisionOnBuildFailure -and `
                    -not [bool]$result.cached_candidate_is_fresh -and `
                    -not [string]::IsNullOrWhiteSpace($result.cached_candidate_decision)) {
                $result.decision_freshness = "fresh_failure_cached_candidate_stale"
                $result.decision_override_reason = "cached_candidate_not_fresh_for_build_failure_fallback"
            } elseif ($result.final_decision -eq "candidate_build_failed" -and `
                    [bool]$PreferCachedDecisionOnBuildFailure -and `
                    [string]::IsNullOrWhiteSpace($result.cached_candidate_decision)) {
                $result.decision_freshness = "fresh_failure_no_cached_candidate"
                $result.decision_override_reason = "no_cached_candidate_available_for_build_failure_fallback"
            } elseif ($result.final_decision -eq "candidate_build_failed" -and `
                    -not [bool]$PreferCachedDecisionOnBuildFailure) {
                $result.decision_freshness = "fresh_failure_no_fallback"
                $result.decision_override_reason = "cached_fallback_disabled_on_build_failure"
            }
        }
    } elseif ([bool]$result.cached_candidate_eligible_for_use) {
        $result.decision_source = "cached_candidate"
        $result.effective_decision = $result.cached_candidate_decision
        $result.effective_readiness_percent = $result.cached_candidate_readiness_percent
        $result.decision_freshness = "stale_metrics_cached_candidate"
    } elseif (-not [string]::IsNullOrWhiteSpace($result.cached_candidate_decision) -and -not [bool]$result.cached_candidate_is_fresh) {
        $result.decision_source = "none"
        $result.effective_decision = ""
        $result.effective_readiness_percent = ""
        $result.decision_freshness = "stale_candidate_ignored"
        $result.decision_override_reason = "cached_candidate_not_fresh"
    } else {
        $result.decision_source = "none"
        $result.effective_decision = ""
        $result.effective_readiness_percent = ""
        $result.decision_freshness = "no_candidate"
    }

    if ([bool]$FailOnPendingMetricsDecision -and $result.final_decision -eq "pending_metrics") {
        $result.autopilot_failure_reason = "pending_metrics_decision"
    } elseif ([bool]$FailOnNoEffectiveDecision -and [string]::IsNullOrWhiteSpace($result.effective_decision)) {
        $result.autopilot_failure_reason = "missing_effective_decision"
    } elseif ([bool]$FailOnCachedDecisionSource -and `
            ($result.decision_source -eq "cached_candidate" -or $result.decision_source -eq "cached_candidate_fallback")) {
        $result.autopilot_failure_reason = "cached_decision_source_used"
    } elseif ([bool]$FailOnErrorSortingStatusNotPass -and `
            $result.error_sorting_status -ne "pass") {
        $result.autopilot_failure_reason = "error_sorting_status_not_pass"
    } elseif ([bool]$FailOnErrorSortingNoiseWarn -and `
            ($result.error_sorting_known_noise_status -eq "warn" -or `
                $result.error_sorting_known_noise_status -eq "fail" -or `
                $result.error_sorting_known_noise_status -eq "error")) {
        $result.autopilot_failure_reason = "error_sorting_noise_warn_or_worse"
    } elseif ([bool]$FailOnErrorSortingNoiseWarn -and `
            $result.error_sorting_known_noise_status -ne "pass") {
        $result.autopilot_failure_reason = "error_sorting_noise_status_unavailable"
    } elseif ([bool]$FailOnPrismJarSyncNotSynced -and `
            [bool]$AutoSyncModJarToPrism -and `
            $result.prism_jar_sync_status -ne "synced") {
        $result.autopilot_failure_reason = "prism_jar_sync_not_synced"
    } elseif ([bool]$FailOnEffectiveDecisionNotReadyForBeta -and `
            -not [string]::IsNullOrWhiteSpace($result.effective_decision) -and `
            $result.effective_decision.Trim().ToLowerInvariant() -ne "ready_for_beta") {
        $result.autopilot_failure_reason = "effective_decision_not_ready_for_beta"
    } elseif ([bool]$FailOnStartupSyncStaleCacheBlock -and $startupSyncStaleCacheBlockTriggered) {
        $result.autopilot_failure_reason = "startup_sync_stale_cache_blocked"
    } else {
        $result.autopilot_failure_reason = ""
    }
    $result.autopilot_failed = -not [string]::IsNullOrWhiteSpace($result.autopilot_failure_reason)

    if (-not [string]::IsNullOrWhiteSpace($summaryOutputPathResolved)) {
        try {
            $summaryOutputDir = Split-Path -Path $summaryOutputPathResolved -Parent
            if (-not [string]::IsNullOrWhiteSpace($summaryOutputDir)) {
                New-Item -ItemType Directory -Path $summaryOutputDir -Force | Out-Null
            }
            $result.summary_output_written = $true
            $result.summary_output_written_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
            $result.summary_output_error = ""
            $summaryJson = if ($SummaryOutputCompress) {
                $result | ConvertTo-Json -Depth 12 -Compress
            } else {
                $result | ConvertTo-Json -Depth 12
            }
            $summaryJson | Set-Content -LiteralPath $summaryOutputPathResolved -Encoding utf8
        } catch {
            $result.summary_output_written = $false
            $result.summary_output_written_utc = ""
            $result.summary_output_error = $_.Exception.Message
            Write-Warning ("[Autopilot] Failed to write summary output file: {0} ({1})" -f $summaryOutputPathResolved, $_.Exception.Message)
        }
    }
    if ([bool]$FailOnSummaryOutputWriteError -and `
            -not [string]::IsNullOrWhiteSpace($result.summary_output_error) -and `
            [string]::IsNullOrWhiteSpace($result.autopilot_failure_reason)) {
        $result.autopilot_failure_reason = "summary_output_write_error"
        $result.autopilot_failed = $true
    }

    Reset-LastExitCode
    Write-Host ""
    Write-Host "Roadmap autopilot summary"
    Write-Host "-------------------------"
    $result | Format-List

    if (-not [string]::IsNullOrWhiteSpace($result.autopilot_failure_reason)) {
        switch ($result.autopilot_failure_reason) {
            "pending_metrics_decision" {
                throw "Final decision is pending_metrics while FailOnPendingMetricsDecision is enabled."
            }
            "missing_effective_decision" {
                throw "Effective decision is empty while FailOnNoEffectiveDecision is enabled."
            }
            "cached_decision_source_used" {
                throw ("Decision source is '{0}' while FailOnCachedDecisionSource is enabled." -f $result.decision_source)
            }
            "error_sorting_status_not_pass" {
                throw ("Error sorting status is '{0}' (blocking_hits={1}, known_noise_status={2}) while FailOnErrorSortingStatusNotPass is enabled." -f `
                        $result.error_sorting_status,
                        $result.error_sorting_blocking_hits,
                        $result.error_sorting_known_noise_status)
            }
            "error_sorting_noise_warn_or_worse" {
                throw ("Error sorting known noise status is '{0}' (hits={1}) while FailOnErrorSortingNoiseWarn is enabled." -f `
                        $result.error_sorting_known_noise_status,
                        $result.error_sorting_known_noise_hits)
            }
            "error_sorting_noise_status_unavailable" {
                throw ("Error sorting known noise status is '{0}' while FailOnErrorSortingNoiseWarn is enabled. Ensure RunErrorSortingPass is active and summary output is available." -f `
                        $result.error_sorting_known_noise_status)
            }
            "prism_jar_sync_not_synced" {
                throw ("Prism jar sync status is '{0}' (source='{1}', reason='{2}') while FailOnPrismJarSyncNotSynced is enabled." -f `
                        $result.prism_jar_sync_status,
                        $result.prism_jar_sync_source,
                        $result.prism_jar_sync_skip_reason)
            }
            "summary_output_write_error" {
                throw ("Summary output write failed ({0}) while FailOnSummaryOutputWriteError is enabled." -f $result.summary_output_error)
            }
            "effective_decision_not_ready_for_beta" {
                throw ("Effective decision is '{0}' while FailOnEffectiveDecisionNotReadyForBeta is enabled." -f $result.effective_decision)
            }
            "startup_sync_stale_cache_blocked" {
                throw "Startup Prism sync was blocked because cached candidate is stale."
            }
            default {
                throw ("Autopilot failed because {0}" -f $result.autopilot_failure_reason)
            }
        }
    }
} finally {
    Pop-Location
}
