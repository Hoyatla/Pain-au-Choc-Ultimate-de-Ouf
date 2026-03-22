param(
    [string]$CandidateRoot = ".\run\beta_candidates",
    [string]$ReportsDir = ".\run\pauc_reports",
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
    [switch]$SyncTelemetryToRepo,
    [string]$TelemetrySyncDestination = ".\run\pauc_telemetry",
    [bool]$SyncTelemetrySegments = $true,
    [switch]$SyncTelemetryCaptureState,
    [string]$ShaderpacksDir = "",
    [string]$ResultsPath = ".\RESULTATS_TESTS_AB_PAUC.csv",
    [string]$AbCampaignStatusScriptPath = ".\tools\ab_campaign_status.ps1",
    [string]$VerifyCandidateScriptPath = ".\tools\verify_beta_candidate.ps1",
    [string]$SuiviPath = ".\SUIVI_SESSIONS_ROADMAP.md",
    [int]$DocFreshnessMaxAgeMinutes = 60,
    [int]$ReadinessThreshold = 80,
    [double]$ReadinessSkippedWeightFactor = 0.5,
    [double]$PreflightMinAbCompletionPercent = 100.0,
    [int]$MinPressureSamplesForServerGovernor = 5,
    [double]$FrameMsP95Max = 20.0,
    [double]$FrameMsP99Max = 60.0,
    [double]$MsptP95Max = 60.0,
    [switch]$StrictPreflight,
    [switch]$StrictReadiness,
    [switch]$SkipPreflight,
    [switch]$SkipJarBuild,
    [switch]$SkipActionPlan,
    [switch]$SkipChecksums,
    [switch]$SkipProfileCopy,
    [switch]$SkipVerification,
    [switch]$KeepFailedCandidate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PreflightMinAbCompletionPercent -lt 0.0 -or $PreflightMinAbCompletionPercent -gt 100.0) {
    throw "PreflightMinAbCompletionPercent must be between 0 and 100"
}
if ($MinPressureSamplesForServerGovernor -lt 1) {
    throw "MinPressureSamplesForServerGovernor must be >= 1"
}
if ($FrameMsP95Max -le 0.0) {
    throw "FrameMsP95Max must be > 0"
}
if ($FrameMsP99Max -le 0.0) {
    throw "FrameMsP99Max must be > 0"
}
if ($MsptP95Max -le 0.0) {
    throw "MsptP95Max must be > 0"
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

function Get-GradlePropertyValue {
    param(
        [string]$FilePath,
        [string]$Key,
        [string]$DefaultValue = ""
    )
    if (-not (Test-Path -LiteralPath $FilePath)) {
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

function Resolve-ShaderpacksPath {
    param([string]$RequestedPath)
    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        if (Test-Path -LiteralPath $RequestedPath) {
            return (Resolve-Path -LiteralPath $RequestedPath).Path
        }
        return $null
    }

    if (Test-Path -LiteralPath ".\run\shaderpacks") {
        return (Resolve-Path -LiteralPath ".\run\shaderpacks").Path
    }
    if (Test-Path -LiteralPath ".\shaderpacks") {
        return (Resolve-Path -LiteralPath ".\shaderpacks").Path
    }
    return $null
}

function Get-LastExitCodeOrZero {
    $exitVar = Get-Variable -Name LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue
    if ($null -eq $exitVar) {
        return 0
    }
    return [int]$exitVar.Value
}

function Resolve-OptionalScriptPath {
    param(
        [string]$ScriptPath,
        [string]$RepoRoot
    )
    if ([string]::IsNullOrWhiteSpace($ScriptPath)) {
        return $null
    }
    if (Test-Path -LiteralPath $ScriptPath) {
        return (Resolve-Path -LiteralPath $ScriptPath).Path
    }
    $candidate = Join-Path $RepoRoot $ScriptPath
    if (Test-Path -LiteralPath $candidate) {
        return (Resolve-Path -LiteralPath $candidate).Path
    }
    return $null
}

function Invoke-GitText {
    param([string[]]$Arguments)
    try {
        $output = & git @Arguments 2>$null
        if ((Get-LastExitCodeOrZero) -ne 0) {
            return ""
        }
        if ($output -is [System.Array]) {
            return (($output -join "`n").Trim())
        }
        return [string]$output
    } catch {
        return ""
    }
}

function Get-OptionalReadinessProperty {
    param(
        [object]$ReadinessObject,
        [string]$PropertyName,
        [object]$DefaultValue = ""
    )
    if ($null -eq $ReadinessObject -or [string]::IsNullOrWhiteSpace($PropertyName)) {
        return $DefaultValue
    }
    $property = $ReadinessObject.PSObject.Properties[$PropertyName]
    if ($null -eq $property -or $null -eq $property.Value) {
        return $DefaultValue
    }
    return $property.Value
}

function To-RelativeCandidatePath {
    param(
        [string]$CandidateRootPath,
        [string]$TargetPath
    )
    $root = [System.IO.Path]::GetFullPath($CandidateRootPath).TrimEnd('\', '/')
    $full = [System.IO.Path]::GetFullPath($TargetPath)
    if ($full.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($root.Length).TrimStart('\', '/')
    }
    return [System.IO.Path]::GetFileName($TargetPath)
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $scriptRoot "..")).Path
$preflightScript = Join-Path $scriptRoot "run_phase6_preflight.ps1"
$readinessScript = Join-Path $scriptRoot "assess_beta_readiness.ps1"
$gradlePropsPath = Join-Path $repoRoot "gradle.properties"
$verifyScriptResolved = Resolve-OptionalScriptPath -ScriptPath $VerifyCandidateScriptPath -RepoRoot $repoRoot

Push-Location $repoRoot
try {
    $candidateDir = ""
    $candidateDirCreated = $false
    try {
        New-Item -ItemType Directory -Path $CandidateRoot -Force | Out-Null
        New-Item -ItemType Directory -Path $ReportsDir -Force | Out-Null

        if (-not $SkipPreflight) {
            $effectiveMetricsTailSeconds = $MetricsTailSeconds
            if ($StrictPreflight -and $effectiveMetricsTailSeconds -le 0 -and $MetricsTailSamples -le 0 -and -not $UseFullMetricsHistory) {
                $effectiveMetricsTailSeconds = 600
            }

            $preflightArgs = @{
                ReportDir = $ReportsDir
            MetricsPath = $MetricsPath
            MetricsWarmupTrimSeconds = $MetricsWarmupTrimSeconds
            CheckDocFreshness = $true
            DocFreshnessMaxAgeMinutes = $DocFreshnessMaxAgeMinutes
            CheckAbMatrix = $true
            CheckAbProgress = $true
            MinAbCompletionPercent = $PreflightMinAbCompletionPercent
            MinPressureSamplesForServerGovernor = $MinPressureSamplesForServerGovernor
            FrameMsP95Max = $FrameMsP95Max
            FrameMsP99Max = $FrameMsP99Max
            MsptP95Max = $MsptP95Max
            ResultsPath = $ResultsPath
                PrismRoot = $PrismRoot
                PrismInstanceName = $PrismInstanceName
                WriteDocCheckpoint = $true
                CheckpointSuiviPath = $SuiviPath
                CheckpointAuthor = "Codex"
                CheckpointMessage = "Beta candidate preflight checkpoint."
            }
        if ($DisableAutoMetricsDiscovery) {
            $preflightArgs.DisableAutoMetricsDiscovery = $true
        }
        if ($UseFullMetricsHistory) {
            $preflightArgs.UseFullMetricsHistory = $true
        }
        if ($effectiveMetricsTailSeconds -gt 0) {
            $preflightArgs.MetricsTailSeconds = $effectiveMetricsTailSeconds
        }
        if ($MetricsTailSamples -gt 0) {
            $preflightArgs.MetricsTailSamples = $MetricsTailSamples
        }
        if ($MaxMetricsAgeMinutes -gt 0) {
            $preflightArgs.MaxMetricsAgeMinutes = $MaxMetricsAgeMinutes
        }
        $preflightArgs.MetricsCodeDriftToleranceMinutes = $MetricsCodeDriftToleranceMinutes
        if (-not [string]::IsNullOrWhiteSpace($RequiredTelemetrySchemaVersion)) {
            $preflightArgs.RequiredTelemetrySchemaVersion = $RequiredTelemetrySchemaVersion
        }
        if ($StrictMetricsFreshness) {
            $preflightArgs.StrictMetricsFreshness = $true
        }
        if ($SyncTelemetryToRepo) {
            $preflightArgs.SyncTelemetryToRepo = $true
            $preflightArgs.TelemetrySyncDestination = $TelemetrySyncDestination
            $preflightArgs.SyncTelemetrySegments = $SyncTelemetrySegments
                if ($SyncTelemetryCaptureState) {
                    $preflightArgs.SyncTelemetryCaptureState = $true
                }
            }
            $resolvedShaderpacks = Resolve-ShaderpacksPath -RequestedPath $ShaderpacksDir
            if ($null -eq $resolvedShaderpacks) {
                $preflightArgs.SkipShaderCheck = $true
            } else {
                $preflightArgs.ShaderpacksDir = $resolvedShaderpacks
            }

            if ($StrictPreflight) {
                $preflightArgs.StrictDocFreshness = $true
                $preflightArgs.StrictAbMatrix = $true
                $preflightArgs.StrictAbProgress = $true
                $preflightArgs.StrictDrsDeferredSafety = $true
                $preflightArgs.StrictSoakStability = $true
                $preflightArgs.StrictCompileWarnings = $true
                $preflightArgs.StrictMetricsFreshness = $true
                if (-not $preflightArgs.ContainsKey("MaxMetricsAgeMinutes")) {
                    $preflightArgs.MaxMetricsAgeMinutes = 240
                }
                if (-not $preflightArgs.ContainsKey("SkipKpiGate")) {
                    $preflightArgs.StrictKpiGate = $true
                }
            }

            & $preflightScript @preflightArgs
            $preflightExitCode = Get-LastExitCodeOrZero
            if ($preflightExitCode -ne 0) {
                throw "Preflight failed with exit code $preflightExitCode"
            }
        }

        $latestReport = Get-ChildItem -LiteralPath $ReportsDir -File -Filter "phase6_preflight_*.md" |
                Sort-Object Name -Descending |
                Select-Object -First 1
        if ($null -eq $latestReport) {
            throw "No preflight report found in $ReportsDir"
        }

        $timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd_HHmmss_fff")
        $candidateDir = Join-Path $CandidateRoot ("beta_candidate_{0}" -f $timestamp)
        New-Item -ItemType Directory -Path $candidateDir -Force | Out-Null
        $candidateDirCreated = $true

        $readinessJsonPath = Join-Path $candidateDir "beta_readiness.json"
        $readinessArgs = @{
            ReportPath = $latestReport.FullName
            MinReadinessPercent = $ReadinessThreshold
            SkippedWeightFactor = $ReadinessSkippedWeightFactor
            OutJsonPath = $readinessJsonPath
            PassThru = $true
        }
        if ($StrictReadiness) {
            $readinessArgs.FailBelowThreshold = $true
        }

        $readinessResult = & $readinessScript @readinessArgs
        $readinessExitCode = Get-LastExitCodeOrZero
        if ($readinessExitCode -ne 0) {
            throw "Readiness assessment failed with exit code $readinessExitCode"
        }
        if ($readinessResult -is [System.Array]) {
            $readinessResult = $readinessResult | Select-Object -Last 1
        }
        if ($null -eq $readinessResult -or $null -eq $readinessResult.decision) {
            throw "Readiness assessment did not return a valid result object"
        }

    $abCampaignSummary = $null
    $abCampaignScriptResolved = $null
    $candidateCampaignScript = Join-Path $repoRoot $AbCampaignStatusScriptPath
    if (Test-Path -LiteralPath $AbCampaignStatusScriptPath) {
        $abCampaignScriptResolved = (Resolve-Path -LiteralPath $AbCampaignStatusScriptPath).Path
    } elseif (Test-Path -LiteralPath $candidateCampaignScript) {
        $abCampaignScriptResolved = (Resolve-Path -LiteralPath $candidateCampaignScript).Path
    }

    if ($null -ne $abCampaignScriptResolved -and (Test-Path -LiteralPath $ResultsPath)) {
        try {
            $abCampaignSummary = & $abCampaignScriptResolved -ResultsPath $ResultsPath -PassThru
            if ($abCampaignSummary -is [System.Array]) {
                $abCampaignSummary = $abCampaignSummary | Select-Object -Last 1
            }
        } catch {
            Write-Warning ("Unable to collect A/B campaign summary: {0}" -f $_.Exception.Message)
        }
    }

    if (-not $SkipJarBuild) {
        & .\gradlew.bat jar
        $jarExitCode = Get-LastExitCodeOrZero
        if ($jarExitCode -ne 0) {
            throw "Jar build failed with exit code $jarExitCode"
        }
    }

    $artifactId = Get-GradlePropertyValue -FilePath $gradlePropsPath -Key "mod_artifact_id" -DefaultValue "pauc"
    $modVersion = Get-GradlePropertyValue -FilePath $gradlePropsPath -Key "mod_version" -DefaultValue ""
    $jarPattern = if ([string]::IsNullOrWhiteSpace($modVersion)) {
        "$artifactId*.jar"
    } else {
        "$artifactId-$modVersion*.jar"
    }

    $jarCandidate = Get-ChildItem -LiteralPath ".\build\libs" -File -Filter $jarPattern |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1

    if ($null -eq $jarCandidate) {
        $jarCandidate = Get-ChildItem -LiteralPath ".\build\libs" -File -Filter "*.jar" |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
    }
    if ($null -eq $jarCandidate) {
        throw "No jar artifact found in build/libs"
    }

    $jarTargetPath = Join-Path $candidateDir $jarCandidate.Name
    Copy-Item -LiteralPath $jarCandidate.FullName -Destination $jarTargetPath -Force

    $reportTargetPath = Join-Path $candidateDir $latestReport.Name
    Copy-Item -LiteralPath $latestReport.FullName -Destination $reportTargetPath -Force
    $candidateDirResolved = (Resolve-Path -LiteralPath $candidateDir).Path
    $jarHash = (Get-FileHash -LiteralPath $jarTargetPath -Algorithm SHA256).Hash.ToUpperInvariant()

    $abCampaignJsonPath = ""
    if ($null -ne $abCampaignSummary) {
        $abCampaignJsonPath = Join-Path $candidateDir "ab_campaign_status.json"
        $abCampaignSummary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $abCampaignJsonPath
    }

    $profilesDirPath = ""
    if (-not $SkipProfileCopy) {
        $profileSourceDir = Join-Path $repoRoot "tools"
        if (Test-Path -LiteralPath $profileSourceDir -PathType Container) {
            $profileFiles = @(Get-ChildItem -LiteralPath $profileSourceDir -File -Filter "pauc_profile_*.properties")
            if ($profileFiles.Count -gt 0) {
                $profilesDirPath = Join-Path $candidateDir "profiles"
                New-Item -ItemType Directory -Path $profilesDirPath -Force | Out-Null
                foreach ($profileFile in $profileFiles) {
                    Copy-Item -LiteralPath $profileFile.FullName -Destination (Join-Path $profilesDirPath $profileFile.Name) -Force
                }
            }
        }
    }

    $actionPlanPath = ""
    if (-not $SkipActionPlan) {
        $actionPlanPath = Join-Path $candidateDir "BETA_ACTIONS.md"
        $actionLines = New-Object System.Collections.Generic.List[string]
        $actionLines.Add("# PauC Beta Actions")
        $actionLines.Add("")
        $actionLines.Add(("- Timestamp UTC: {0}" -f (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")))
        $actionLines.Add(("- Current decision: {0}" -f $readinessResult.decision))
        $actionLines.Add(("- Readiness: {0}% (threshold {1}%)" -f $readinessResult.readiness_percent, $readinessResult.threshold_percent))
        if (-not [string]::IsNullOrWhiteSpace([string]$readinessResult.notes)) {
            $actionLines.Add(("- Notes: {0}" -f $readinessResult.notes))
        }
        $actionLines.Add("")

        if ($readinessResult.decision -eq "ready_for_beta") {
            $actionLines.Add("## Next")
            $actionLines.Add("- Candidate is ready for beta validation in game.")
            $actionLines.Add("- Run validation matrix: V1 produit, V2 QA in-game, V3 hardware/drivers.")
            $serverGovernorStatus = [string](Get-OptionalReadinessProperty -ReadinessObject $readinessResult -PropertyName "server_governor_health" -DefaultValue "")
            $serverGovernorInsufficientPressure = [string](Get-OptionalReadinessProperty -ReadinessObject $readinessResult -PropertyName "server_governor_skipped_for_insufficient_pressure" -DefaultValue "false")
            if ($serverGovernorStatus.Trim().ToLowerInvariant().StartsWith("skipped")) {
                if ($serverGovernorInsufficientPressure.Trim().ToLowerInvariant() -eq "true") {
                    $actionLines.Add("- Advisory: server governor coverage is partial on this candidate (insufficient pressure samples).")
                    $actionLines.Add("- Advisory action: run one loaded gameplay capture and re-run strict candidate to validate server pressure path.")
                } else {
                    $actionLines.Add("- Advisory: server governor gate is skipped on this candidate. Check preflight artifacts before release sign-off.")
                }
            }
        } else {
            $actionLines.Add("## Next")
            if ([int]$readinessResult.blocking_issues_count -gt 0 -and -not [string]::IsNullOrWhiteSpace([string]$readinessResult.blocking_issues)) {
                $actionLines.Add(("- Blocking gates failing: {0}" -f $readinessResult.blocking_issues))
            }
            $gateLabels = @(
                @{ Key = "documentation_freshness"; Label = "Documentation freshness" },
                @{ Key = "compile"; Label = "Compile" },
                @{ Key = "compile_warnings"; Label = "Compile warnings" },
                @{ Key = "shader_compatibility"; Label = "Shader compatibility" },
                @{ Key = "metrics_summary"; Label = "Metrics summary" },
                @{ Key = "server_governor_health"; Label = "Server governor health" },
                @{ Key = "chunk_compile_health"; Label = "Chunk compile health" },
                @{ Key = "drs_deferred_safety"; Label = "DRS/deferred safety" },
                @{ Key = "soak_stability"; Label = "Soak stability" },
                @{ Key = "kpi_gate"; Label = "KPI gate" },
                @{ Key = "ab_audit"; Label = "A/B audit" },
                @{ Key = "ab_progress"; Label = "A/B progress" }
            )
            $gateIssues = New-Object System.Collections.Generic.List[string]
            foreach ($gate in $gateLabels) {
                $rawValue = [string]$readinessResult.($gate.Key)
                if ([string]::IsNullOrWhiteSpace($rawValue)) {
                    continue
                }
                $normalized = $rawValue.Trim().ToLowerInvariant()
                $isPass = ($normalized -eq "pass" -or $normalized -eq "ok")
                if ($isPass) {
                    continue
                }
                $gateIssues.Add(("{0}: {1}" -f $gate.Label, $rawValue))
            }
            if ($gateIssues.Count -gt 0) {
                $actionLines.Add("- Gate statuses to review:")
                foreach ($gateIssue in $gateIssues) {
                    $actionLines.Add(("- {0}" -f $gateIssue))
                }
            }
            if ($null -ne $abCampaignSummary) {
                $actionLines.Add(("- A/B completion: {0}% ({1}/{2} cells)" -f $abCampaignSummary.completion_percent, $abCampaignSummary.filled_cells, $abCampaignSummary.total_cells))
                if (-not [string]::IsNullOrWhiteSpace([string]$abCampaignSummary.next_scene)) {
                    $actionLines.Add(("- Next missing cell: {0} / {1}" -f $abCampaignSummary.next_scene, $abCampaignSummary.next_profile))
                }
                if (-not [string]::IsNullOrWhiteSpace([string]$abCampaignSummary.next_prepare_command)) {
                    $actionLines.Add(("- Prepare next A/B run: {0}" -f $abCampaignSummary.next_prepare_command))
                }
                if (-not [string]::IsNullOrWhiteSpace([string]$abCampaignSummary.next_finish_command)) {
                    $actionLines.Add(("- Finish next A/B run: {0}" -f $abCampaignSummary.next_finish_command))
                }
            } else {
                $actionLines.Add("- A/B campaign summary unavailable. Verify RESULTATS_TESTS_AB_PAUC.csv then run:")
                $actionLines.Add("- .\tools\ab_campaign_status.ps1")
            }
            $actionLines.Add("- Re-run strict candidate once blockers are cleared:")
            $actionLines.Add("- .\tools\build_beta_candidate.ps1 -StrictPreflight -StrictReadiness")
        }
        Set-Content -LiteralPath $actionPlanPath -Value $actionLines
    }

    $gitBranch = Invoke-GitText -Arguments @("rev-parse", "--abbrev-ref", "HEAD")
    $gitCommit = Invoke-GitText -Arguments @("rev-parse", "HEAD")
    $gitStatus = Invoke-GitText -Arguments @("status", "--short")
    $gitDirty = -not [string]::IsNullOrWhiteSpace($gitStatus)
    $candidateManifestJsonPath = Join-Path $candidateDir "candidate_manifest.json"
    $candidateManifest = [PSCustomObject]@{
        timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        candidate_dir = $candidateDirResolved
        jar_name = $jarCandidate.Name
        jar_sha256 = $jarHash
        preflight_report_name = $latestReport.Name
        readiness_json_name = "beta_readiness.json"
        readiness_decision = $readinessResult.decision
        readiness_percent = $readinessResult.readiness_percent
        readiness_threshold_percent = $readinessResult.threshold_percent
        readiness_notes = $readinessResult.notes
        readiness_server_governor_status = [string](Get-OptionalReadinessProperty -ReadinessObject $readinessResult -PropertyName "server_governor_health" -DefaultValue "")
        readiness_server_governor_skip_issue = [string](Get-OptionalReadinessProperty -ReadinessObject $readinessResult -PropertyName "server_governor_skip_issue" -DefaultValue "")
        readiness_server_governor_skipped_for_insufficient_pressure = [bool](Get-OptionalReadinessProperty -ReadinessObject $readinessResult -PropertyName "server_governor_skipped_for_insufficient_pressure" -DefaultValue $false)
        readiness_blocking_gates_enabled = [bool]$readinessResult.blocking_gates_enabled
        readiness_blocking_gate_keys = [string]$readinessResult.blocking_gate_keys
        readiness_blocking_issues_count = [int]$readinessResult.blocking_issues_count
        readiness_blocking_issues = [string]$readinessResult.blocking_issues
        ab_completion_percent = if ($null -eq $abCampaignSummary) { "" } else { $abCampaignSummary.completion_percent }
        git_branch = $gitBranch
        git_commit = $gitCommit
        git_dirty = $gitDirty
        git_status_short = $gitStatus
    }
    $candidateManifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $candidateManifestJsonPath

    $checksumsPath = ""
    $checksumTargets = New-Object System.Collections.Generic.List[string]
    if (-not $SkipChecksums) {
        $checksumsPath = Join-Path $candidateDir "SHA256SUMS.txt"
        $checksumTargets.Add($jarTargetPath)
        $checksumTargets.Add($reportTargetPath)
        $checksumTargets.Add($readinessJsonPath)
        $checksumTargets.Add($candidateManifestJsonPath)
        if (-not [string]::IsNullOrWhiteSpace($abCampaignJsonPath)) {
            $checksumTargets.Add($abCampaignJsonPath)
        }
        if (-not [string]::IsNullOrWhiteSpace($actionPlanPath)) {
            $checksumTargets.Add($actionPlanPath)
        }
        if (-not [string]::IsNullOrWhiteSpace($profilesDirPath)) {
            $profileTargets = @(Get-ChildItem -LiteralPath $profilesDirPath -File -Filter "*.properties")
            foreach ($profileTarget in $profileTargets) {
                $checksumTargets.Add($profileTarget.FullName)
            }
        }
    }

    $manifestPath = Join-Path $candidateDir "BETA_CANDIDATE.md"
    $manifestLines = New-Object System.Collections.Generic.List[string]
    $manifestLines.Add("# PauC Beta Candidate")
    $manifestLines.Add("")
    $manifestLines.Add(("- Timestamp UTC: {0}" -f (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")))
    $manifestLines.Add(("- Candidate directory: {0}" -f (Resolve-Path -LiteralPath $candidateDir).Path))
    $manifestLines.Add("")
    $manifestLines.Add("## Artifacts")
    $manifestLines.Add(("- Jar: {0}" -f $jarCandidate.Name))
    $manifestLines.Add(("- Preflight report: {0}" -f $latestReport.Name))
    $manifestLines.Add("- Readiness JSON: beta_readiness.json")
    $manifestLines.Add("- Candidate manifest JSON: candidate_manifest.json")
    if (-not [string]::IsNullOrWhiteSpace($checksumsPath)) {
        $manifestLines.Add("- SHA256 checksums: SHA256SUMS.txt")
    }
    if (-not [string]::IsNullOrWhiteSpace($abCampaignJsonPath)) {
        $manifestLines.Add("- A/B campaign JSON: ab_campaign_status.json")
    }
    if (-not [string]::IsNullOrWhiteSpace($actionPlanPath)) {
        $manifestLines.Add("- Action plan: BETA_ACTIONS.md")
    }
    if (-not [string]::IsNullOrWhiteSpace($profilesDirPath)) {
        $manifestLines.Add("- Profile presets: profiles/*.properties")
    }
    $manifestLines.Add("")
    $manifestLines.Add("## Readiness")
    $manifestLines.Add(("- Decision: {0}" -f $readinessResult.decision))
    $manifestLines.Add(("- Readiness: {0}%" -f $readinessResult.readiness_percent))
    $manifestLines.Add(("- Threshold: {0}%" -f $readinessResult.threshold_percent))
    if ([int]$readinessResult.blocking_issues_count -gt 0 -and -not [string]::IsNullOrWhiteSpace([string]$readinessResult.blocking_issues)) {
        $manifestLines.Add(("- Blocking gates: {0}" -f $readinessResult.blocking_issues))
    }
    $manifestLines.Add(("- Notes: {0}" -f $readinessResult.notes))
    $readinessServerGovernorStatus = [string](Get-OptionalReadinessProperty -ReadinessObject $readinessResult -PropertyName "server_governor_health" -DefaultValue "")
    $readinessServerGovernorIssue = [string](Get-OptionalReadinessProperty -ReadinessObject $readinessResult -PropertyName "server_governor_skip_issue" -DefaultValue "")
    if (-not [string]::IsNullOrWhiteSpace($readinessServerGovernorStatus)) {
        $manifestLines.Add(("- Server governor health: {0}" -f $readinessServerGovernorStatus))
    }
    if (-not [string]::IsNullOrWhiteSpace($readinessServerGovernorIssue)) {
        $manifestLines.Add(("- Server governor details: {0}" -f $readinessServerGovernorIssue))
    }
    $manifestLines.Add(("- Jar SHA256: {0}" -f $jarHash))
    if (-not [string]::IsNullOrWhiteSpace($gitCommit)) {
        $manifestLines.Add(("- Git: branch={0} commit={1} dirty={2}" -f $gitBranch, $gitCommit, $gitDirty))
    }
    if ($null -ne $abCampaignSummary) {
        $manifestLines.Add(("- A/B completion: {0}% ({1}/{2})" -f $abCampaignSummary.completion_percent, $abCampaignSummary.filled_cells, $abCampaignSummary.total_cells))
        if (-not [string]::IsNullOrWhiteSpace([string]$abCampaignSummary.next_scene)) {
            $manifestLines.Add(("- A/B next cell: {0}/{1}" -f $abCampaignSummary.next_scene, $abCampaignSummary.next_profile))
        }
    }
    Set-Content -LiteralPath $manifestPath -Value $manifestLines

    if (-not $SkipChecksums) {
        $checksumTargets.Add($manifestPath)
        $checksumLines = New-Object System.Collections.Generic.List[string]
        foreach ($targetPath in $checksumTargets) {
            if (-not (Test-Path -LiteralPath $targetPath -PathType Leaf)) {
                continue
            }
            $hash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash.ToUpperInvariant()
            $relativeName = To-RelativeCandidatePath -CandidateRootPath $candidateDir -TargetPath $targetPath
            $checksumLines.Add(("{0} *{1}" -f $hash, $relativeName))
        }
        Set-Content -LiteralPath $checksumsPath -Value $checksumLines
    }

    $verificationResult = $null
    if (-not $SkipVerification) {
        if ($null -eq $verifyScriptResolved) {
            throw "Verification script not found: $VerifyCandidateScriptPath"
        }
        $verifyArgs = @{
            CandidateDir = $candidateDir
            PassThru = $true
            FailOnIssues = $true
        }
        if (-not $SkipChecksums) {
            $verifyArgs.RequireExtendedArtifacts = $true
        } else {
            $verifyArgs.SkipChecksumValidation = $true
        }
        $verificationResult = & $verifyScriptResolved @verifyArgs
        $verifyExitCode = Get-LastExitCodeOrZero
        if ($verifyExitCode -ne 0) {
            throw "Candidate verification failed with exit code $verifyExitCode"
        }
        if ($verificationResult -is [System.Array]) {
            $verificationResult = $verificationResult | Select-Object -Last 1
        }
    }

        Write-Host ""
        Write-Host "Beta candidate ready"
        Write-Host ("- Directory: {0}" -f (Resolve-Path -LiteralPath $candidateDir).Path)
        Write-Host ("- Jar: {0}" -f $jarCandidate.Name)
        Write-Host ("- Decision: {0} ({1}%)" -f $readinessResult.decision, $readinessResult.readiness_percent)
        if ($null -ne $verificationResult) {
            Write-Host ("- Verification: {0}" -f $verificationResult.overall_status)
        }
    } catch {
        if (-not $KeepFailedCandidate -and $candidateDirCreated -and (Test-Path -LiteralPath $candidateDir -PathType Container)) {
            try {
                Remove-Item -LiteralPath $candidateDir -Recurse -Force -ErrorAction Stop
            } catch {
                Write-Warning ("Unable to clean failed candidate directory '{0}': {1}" -f $candidateDir, $_.Exception.Message)
            }
        }
        throw
    }
} finally {
    Pop-Location
}
