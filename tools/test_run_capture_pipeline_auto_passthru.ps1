param(
    [string]$PipelineScriptPath = "",
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$JarPath = "",
    [string]$ReportsRoot = ".\run\pauc_reports",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-PathFromRepo {
    param(
        [string]$PathValue,
        [string]$RepoRoot,
        [string]$PathType
    )

    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return $null
    }

    $candidates = New-Object System.Collections.Generic.List[string]
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        $candidates.Add($PathValue)
    } else {
        $candidates.Add($PathValue)
        $candidates.Add((Join-Path $RepoRoot $PathValue))
    }

    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        if ($PathType -eq "Leaf" -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
        if ($PathType -eq "Container" -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    return $null
}

function Resolve-LatestJarPath {
    param([string]$RepoRoot)

    $libsDir = Join-Path $RepoRoot "build\libs"
    if (-not (Test-Path -LiteralPath $libsDir -PathType Container)) {
        throw "build/libs directory not found: $libsDir"
    }

    $jar = Get-ChildItem -LiteralPath $libsDir -File |
        Where-Object { $_.Name -like "pauc-ultimate-de-ouf-*-ultimate.jar" } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "No PauC jar artifact found under $libsDir"
    }

    return $jar.FullName
}

if ([string]::IsNullOrWhiteSpace($PipelineScriptPath)) {
    $PipelineScriptPath = Join-Path $PSScriptRoot "run_capture_pipeline_auto.ps1"
}
if (-not (Test-Path -LiteralPath $PipelineScriptPath -PathType Leaf)) {
    throw "run_capture_pipeline_auto script not found: $PipelineScriptPath"
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$resolvedMetricsPath = Resolve-PathFromRepo -PathValue $MetricsPath -RepoRoot $repoRoot -PathType "Leaf"
if ($null -eq $resolvedMetricsPath) {
    throw "Metrics file not found: $MetricsPath"
}

$resolvedJarPath = if ([string]::IsNullOrWhiteSpace($JarPath)) {
    Resolve-LatestJarPath -RepoRoot $repoRoot
} else {
    $customJarPath = Resolve-PathFromRepo -PathValue $JarPath -RepoRoot $repoRoot -PathType "Leaf"
    if ($null -eq $customJarPath) {
        throw "Jar file not found: $JarPath"
    }
    $customJarPath
}

$resolvedReportsRoot = Resolve-PathFromRepo -PathValue $ReportsRoot -RepoRoot $repoRoot -PathType "Container"
if ($null -eq $resolvedReportsRoot) {
    $resolvedReportsRoot = Join-Path $repoRoot "run\pauc_reports"
    New-Item -ItemType Directory -Path $resolvedReportsRoot -Force | Out-Null
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss_fff"
$sessionDir = Join-Path $resolvedReportsRoot ("capture_pipeline_auto_passthru_selftest_{0}" -f $stamp)
New-Item -ItemType Directory -Path $sessionDir -Force | Out-Null
$reportPath = Join-Path $sessionDir "capture_pipeline_auto_passthru_selftest_summary.json"

$checks = New-Object System.Collections.Generic.List[string]
$caseSummaries = New-Object System.Collections.Generic.List[object]

$requiredProperties = @(
    "timestamp_utc",
    "repo_root",
    "prism_root",
    "instance_name",
    "metrics_path",
    "metrics_source",
    "metrics_rows_before",
    "metrics_rows_after",
    "metrics_new_rows",
    "frame_ms_p95_max",
    "frame_ms_p99_max",
    "mspt_p95_max",
    "min_metrics_duration_seconds_for_candidate_preflight",
    "candidate_min_soak_duration_seconds",
    "error_sorting_noise_warn_hits_total",
    "error_sorting_noise_fail_hits_total",
    "jar_path",
    "jar_sha256",
    "jar_copied_to_mods",
    "jar_deployed_path",
    "preflight_executed",
    "preflight_exit_code",
    "preflight_report_path",
    "candidate_executed",
    "candidate_exit_code",
    "candidate_dir",
    "candidate_readiness_decision",
    "autopilot_executed",
    "autopilot_exit_code",
    "autopilot_summary_path",
    "autopilot_script_path",
    "autopilot_allow_one_shot_metrics_signature_replay",
    "autopilot_failed",
    "autopilot_failure_reason",
    "autopilot_effective_decision",
    "errors"
)

function Add-CaseCheck {
    param(
        [System.Collections.Generic.List[string]]$Checks,
        [string]$CaseName,
        [string]$Message
    )

    $Checks.Add(("{0}:{1}" -f $CaseName, $Message))
}

function Add-ResultChecks {
    param(
        [string]$CaseName,
        [object[]]$PipelineItems,
        [string]$ExpectedJarPath,
        [string[]]$RequiredProperties,
        [bool]$ExpectJarCopy,
        [string]$ExpectedJarDeployedPath,
        [double]$ExpectedFrameMsP95Max,
        [double]$ExpectedFrameMsP99Max,
        [double]$ExpectedMsptP95Max,
        [int]$ExpectedMinMetricsDurationSecondsForCandidatePreflight,
        [int]$ExpectedCandidateMinSoakDurationSeconds,
        [int]$ExpectedNoiseWarnHitsTotal,
        [int]$ExpectedNoiseFailHitsTotal,
        [bool]$ExpectedAutopilotExecuted,
        [string]$ExpectedAutopilotSummaryPath,
        [string]$ExpectedAutopilotScriptPath,
        [bool]$ExpectedAllowOneShotMetricsSignatureReplay,
        [System.Collections.Generic.List[string]]$Checks
    )

    if ($PipelineItems.Count -ne 1) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("unexpected_pipeline_output_count:{0}" -f $PipelineItems.Count)
    }

    $formatItems = @(
        $PipelineItems |
            Where-Object {
                $null -ne $_ -and
                $_.GetType().FullName -like "Microsoft.PowerShell.Commands.Internal.Format.*"
            }
    )
    if ($formatItems.Count -gt 0) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("format_objects_leaked_to_pipeline:{0}" -f $formatItems.Count)
    }

    $result = if ($PipelineItems.Count -gt 0) { $PipelineItems[$PipelineItems.Count - 1] } else { $null }
    if ($null -eq $result) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "missing_passthru_result_object"
        return
    }

    foreach ($propertyName in $requiredProperties) {
        if ($null -eq $result.PSObject.Properties[$propertyName]) {
            Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("missing_property:{0}" -f $propertyName)
        }
    }

    if ([bool]$result.preflight_executed) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "preflight_executed_should_be_false"
    }
    if ([bool]$result.candidate_executed) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "candidate_executed_should_be_false"
    }
    if ([bool]$result.autopilot_executed -ne $ExpectedAutopilotExecuted) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("autopilot_executed_mismatch:{0}!={1}" -f [bool]$result.autopilot_executed, $ExpectedAutopilotExecuted)
    }

    if ([int]$result.preflight_exit_code -ne 0) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("unexpected_preflight_exit_code:{0}" -f [int]$result.preflight_exit_code)
    }
    if ([int]$result.candidate_exit_code -ne 0) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("unexpected_candidate_exit_code:{0}" -f [int]$result.candidate_exit_code)
    }
    if ([int]$result.autopilot_exit_code -ne 0) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("unexpected_autopilot_exit_code:{0}" -f [int]$result.autopilot_exit_code)
    }
    if ([bool]$result.autopilot_executed -and [string]::IsNullOrWhiteSpace([string]$result.autopilot_summary_path)) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "autopilot_summary_path_empty_when_executed"
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedAutopilotSummaryPath)) {
        if (-not (Test-Path -LiteralPath $ExpectedAutopilotSummaryPath -PathType Leaf)) {
            Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "expected_autopilot_summary_path_missing"
        } else {
            $resolvedExpectedAutopilotSummaryPath = (Resolve-Path -LiteralPath $ExpectedAutopilotSummaryPath).Path
            if (-not [string]::Equals([string]$result.autopilot_summary_path, $resolvedExpectedAutopilotSummaryPath, [System.StringComparison]::OrdinalIgnoreCase)) {
                Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "autopilot_summary_path_mismatch"
            }
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedAutopilotScriptPath)) {
        if (-not (Test-Path -LiteralPath $ExpectedAutopilotScriptPath -PathType Leaf)) {
            Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "expected_autopilot_script_path_missing"
        } else {
            $resolvedExpectedAutopilotScriptPath = (Resolve-Path -LiteralPath $ExpectedAutopilotScriptPath).Path
            if (-not [string]::Equals([string]$result.autopilot_script_path, $resolvedExpectedAutopilotScriptPath, [System.StringComparison]::OrdinalIgnoreCase)) {
                Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "autopilot_script_path_mismatch"
            }
        }
    }
    if ([double]$result.frame_ms_p95_max -ne $ExpectedFrameMsP95Max) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("frame_ms_p95_max_mismatch:{0}!={1}" -f [double]$result.frame_ms_p95_max, $ExpectedFrameMsP95Max)
    }
    if ([double]$result.frame_ms_p99_max -ne $ExpectedFrameMsP99Max) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("frame_ms_p99_max_mismatch:{0}!={1}" -f [double]$result.frame_ms_p99_max, $ExpectedFrameMsP99Max)
    }
    if ([double]$result.mspt_p95_max -ne $ExpectedMsptP95Max) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("mspt_p95_max_mismatch:{0}!={1}" -f [double]$result.mspt_p95_max, $ExpectedMsptP95Max)
    }
    if ([int]$result.min_metrics_duration_seconds_for_candidate_preflight -ne $ExpectedMinMetricsDurationSecondsForCandidatePreflight) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("min_metrics_duration_seconds_for_candidate_preflight_mismatch:{0}!={1}" -f [int]$result.min_metrics_duration_seconds_for_candidate_preflight, $ExpectedMinMetricsDurationSecondsForCandidatePreflight)
    }
    if ([int]$result.candidate_min_soak_duration_seconds -ne $ExpectedCandidateMinSoakDurationSeconds) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("candidate_min_soak_duration_seconds_mismatch:{0}!={1}" -f [int]$result.candidate_min_soak_duration_seconds, $ExpectedCandidateMinSoakDurationSeconds)
    }
    if ([int]$result.error_sorting_noise_warn_hits_total -ne $ExpectedNoiseWarnHitsTotal) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("error_sorting_noise_warn_hits_total_mismatch:{0}!={1}" -f [int]$result.error_sorting_noise_warn_hits_total, $ExpectedNoiseWarnHitsTotal)
    }
    if ([int]$result.error_sorting_noise_fail_hits_total -ne $ExpectedNoiseFailHitsTotal) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("error_sorting_noise_fail_hits_total_mismatch:{0}!={1}" -f [int]$result.error_sorting_noise_fail_hits_total, $ExpectedNoiseFailHitsTotal)
    }
    if ([int]$result.error_sorting_noise_fail_hits_total -lt [int]$result.error_sorting_noise_warn_hits_total) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "error_sorting_noise_fail_hits_total_lt_warn_total"
    }
    if ([bool]$result.autopilot_allow_one_shot_metrics_signature_replay -ne $ExpectedAllowOneShotMetricsSignatureReplay) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("autopilot_allow_one_shot_metrics_signature_replay_mismatch:{0}!={1}" -f [bool]$result.autopilot_allow_one_shot_metrics_signature_replay, $ExpectedAllowOneShotMetricsSignatureReplay)
    }

    $expectedNewRows = [Math]::Max(0, [int]$result.metrics_rows_after - [int]$result.metrics_rows_before)
    if ([int]$result.metrics_new_rows -ne $expectedNewRows) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("metrics_new_rows_mismatch:{0}!={1}" -f [int]$result.metrics_new_rows, $expectedNewRows)
    }

    if ([string]::IsNullOrWhiteSpace([string]$result.metrics_path)) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "metrics_path_empty"
    }

    $resolvedJarExpected = (Resolve-Path -LiteralPath $ExpectedJarPath).Path
    if (-not [string]::Equals([string]$result.jar_path, $resolvedJarExpected, [System.StringComparison]::OrdinalIgnoreCase)) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "jar_path_mismatch"
    }

    $errorsArray = @($result.errors)
    if ($errorsArray.Count -ne 0) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("expected_no_errors_but_found:{0}" -f $errorsArray.Count)
    }

    if ($ExpectJarCopy) {
        if (-not [bool]$result.jar_copied_to_mods) {
            Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "jar_copied_to_mods_should_be_true"
        }
        if ([string]::IsNullOrWhiteSpace($ExpectedJarDeployedPath)) {
            Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "expected_jar_deployed_path_empty"
        } elseif (-not [string]::Equals([string]$result.jar_deployed_path, $ExpectedJarDeployedPath, [System.StringComparison]::OrdinalIgnoreCase)) {
            Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "jar_deployed_path_mismatch"
        }
        if (-not (Test-Path -LiteralPath $ExpectedJarDeployedPath -PathType Leaf)) {
            Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "jar_deployed_file_missing"
        }
    } else {
        if ([bool]$result.jar_copied_to_mods) {
            Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "jar_copied_to_mods_should_be_false"
        }
    }

    try {
        $jsonPayload = $PipelineItems | ConvertTo-Json -Depth 10 -ErrorAction Stop
        $parsedJson = $jsonPayload | ConvertFrom-Json -ErrorAction Stop
        $hasMetricsPath = $false
        if ($parsedJson -is [System.Array]) {
            if ($parsedJson.Count -eq 1 -and $null -ne $parsedJson[0].PSObject.Properties["metrics_path"]) {
                $hasMetricsPath = $true
            }
        } elseif ($null -ne $parsedJson) {
            if ($null -ne $parsedJson.PSObject.Properties["metrics_path"]) {
                $hasMetricsPath = $true
            }
        }
        if (-not $hasMetricsPath) {
            Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "json_roundtrip_missing_metrics_path"
        }
    } catch {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message ("json_roundtrip_error:{0}" -f $_.Exception.Message)
    }

    $caseSummaries.Add([PSCustomObject]@{
            case_name = $CaseName
            pipeline_output_count = $PipelineItems.Count
            jar_copied_to_mods = [bool]$result.jar_copied_to_mods
            jar_deployed_path = [string]$result.jar_deployed_path
            frame_ms_p95_max = [double]$result.frame_ms_p95_max
            frame_ms_p99_max = [double]$result.frame_ms_p99_max
            mspt_p95_max = [double]$result.mspt_p95_max
            min_metrics_duration_seconds_for_candidate_preflight = [int]$result.min_metrics_duration_seconds_for_candidate_preflight
            candidate_min_soak_duration_seconds = [int]$result.candidate_min_soak_duration_seconds
            error_sorting_noise_warn_hits_total = [int]$result.error_sorting_noise_warn_hits_total
            error_sorting_noise_fail_hits_total = [int]$result.error_sorting_noise_fail_hits_total
            autopilot_executed = [bool]$result.autopilot_executed
            autopilot_summary_path = [string]$result.autopilot_summary_path
            autopilot_script_path = [string]$result.autopilot_script_path
            autopilot_allow_one_shot_metrics_signature_replay = [bool]$result.autopilot_allow_one_shot_metrics_signature_replay
        })

    return $result
}

$smokeInvocationError = ""
$smokePipelineItems = @()
$copyInvocationError = ""
$copyPipelineItems = @()
$autopilotInvocationError = ""
$autopilotPipelineItems = @()
$defaultFrameMsP95Max = 20.0
$defaultFrameMsP99Max = 60.0
$defaultMsptP95Max = 60.0
$defaultMinMetricsDurationSecondsForCandidatePreflight = 480
$defaultCandidateMinSoakDurationSeconds = 480
$defaultNoiseWarnHitsTotal = 500
$defaultNoiseFailHitsTotal = 2000
$overrideFrameMsP95Max = 33.0
$overrideFrameMsP99Max = 77.0
$overrideMsptP95Max = 88.0
$overrideMinMetricsDurationSecondsForCandidatePreflight = 240
$overrideCandidateMinSoakDurationSeconds = 240
$overrideNoiseWarnHitsTotal = 123
$overrideNoiseFailHitsTotal = 456
$fixturePrismRoot = Join-Path $sessionDir "prism_fixture"
$fixtureInstanceName = "ci_capture_auto_dot"
$fixtureMinecraftDir = Join-Path (Join-Path $fixturePrismRoot $fixtureInstanceName) ".minecraft"
New-Item -ItemType Directory -Path $fixtureMinecraftDir -Force | Out-Null
$expectedDeployedJarPath = Join-Path (Join-Path $fixtureMinecraftDir "mods") (Split-Path -Path $resolvedJarPath -Leaf)
$autopilotStubPath = Join-Path $sessionDir "autopilot_stub.ps1"
$autopilotProbePath = Join-Path $sessionDir "autopilot_stub_probe.json"
$autopilotSummaryPath = Join-Path $sessionDir "autopilot_stub_summary.json"

$autopilotStubContent = @'
param(
    [switch]$OneShot,
    [string]$MetricsPath = "",
    [string]$PrismRoot = "",
    [string]$InstanceName = "",
    [string]$SummaryOutputPath = "",
    [switch]$SummaryOutputCompress,
    [double]$FrameMsP95Max = 0.0,
    [double]$FrameMsP99Max = 0.0,
    [double]$MsptP95Max = 0.0,
    [int]$MinMetricsDurationSecondsForCandidatePreflight = 0,
    [int]$CandidateMinSoakDurationSeconds = 0,
    [int]$ErrorSortingNoiseWarnHitsTotal = 0,
    [int]$ErrorSortingNoiseFailHitsTotal = 0,
    [switch]$EnableStrictCiFailGates,
    [switch]$AllowOneShotMetricsSignatureReplay
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$probePath = [Environment]::GetEnvironmentVariable("PAUC_AUTOPILOT_STUB_PROBE_PATH", "Process")
if ([string]::IsNullOrWhiteSpace($probePath)) {
    throw "PAUC_AUTOPILOT_STUB_PROBE_PATH is required"
}

$probe = [PSCustomObject]@{
    one_shot = [bool]$OneShot
    metrics_path = [string]$MetricsPath
    prism_root = [string]$PrismRoot
    instance_name = [string]$InstanceName
    summary_output_path = [string]$SummaryOutputPath
    summary_output_compress = [bool]$SummaryOutputCompress
    frame_ms_p95_max = [double]$FrameMsP95Max
    frame_ms_p99_max = [double]$FrameMsP99Max
    mspt_p95_max = [double]$MsptP95Max
    min_metrics_duration_seconds_for_candidate_preflight = [int]$MinMetricsDurationSecondsForCandidatePreflight
    candidate_min_soak_duration_seconds = [int]$CandidateMinSoakDurationSeconds
    error_sorting_noise_warn_hits_total = [int]$ErrorSortingNoiseWarnHitsTotal
    error_sorting_noise_fail_hits_total = [int]$ErrorSortingNoiseFailHitsTotal
    strict_ci_fail_gates_enabled = [bool]$EnableStrictCiFailGates
    allow_one_shot_metrics_signature_replay = [bool]$AllowOneShotMetricsSignatureReplay
}
$probe | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $probePath -Encoding UTF8

$summary = [PSCustomObject]@{
    autopilot_failed = $false
    autopilot_failure_reason = ""
    effective_decision = "ready_for_beta"
}
$summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $SummaryOutputPath -Encoding UTF8
$global:LASTEXITCODE = 0
'@
$autopilotStubContent | Set-Content -LiteralPath $autopilotStubPath -Encoding UTF8

Push-Location $repoRoot
try {
    try {
        $smokePipelineItems = @(
            & $PipelineScriptPath `
                -MetricsPath $resolvedMetricsPath `
                -JarPath $resolvedJarPath `
                -BuildJar:$false `
                -CopyJarToInstance:$false `
                -WaitForFreshMetrics:$false `
                -RunPreflight:$false `
                -RunCandidate:$false `
                -RunAutopilot:$false `
                -PassThru
        )
    } catch {
        $smokeInvocationError = [string]$_.Exception.Message
    }

    try {
        $copyPipelineItems = @(
            & $PipelineScriptPath `
                -MetricsPath $resolvedMetricsPath `
                -JarPath $resolvedJarPath `
                -PrismRoot $fixturePrismRoot `
                -InstanceName $fixtureInstanceName `
                -BuildJar:$false `
                -CopyJarToInstance:$true `
                -WaitForFreshMetrics:$false `
                -RunPreflight:$false `
                -RunCandidate:$false `
                -RunAutopilot:$false `
                -FrameMsP95Max $overrideFrameMsP95Max `
                -FrameMsP99Max $overrideFrameMsP99Max `
                -MsptP95Max $overrideMsptP95Max `
                -MinMetricsDurationSecondsForCandidatePreflight $overrideMinMetricsDurationSecondsForCandidatePreflight `
                -CandidateMinSoakDurationSeconds $overrideCandidateMinSoakDurationSeconds `
                -ErrorSortingNoiseWarnHitsTotal $overrideNoiseWarnHitsTotal `
                -ErrorSortingNoiseFailHitsTotal $overrideNoiseFailHitsTotal `
                -AutopilotAllowOneShotMetricsSignatureReplay:$true `
                -PassThru
        )
    } catch {
        $copyInvocationError = [string]$_.Exception.Message
    }

    $previousStubProbePath = [Environment]::GetEnvironmentVariable("PAUC_AUTOPILOT_STUB_PROBE_PATH", "Process")
    try {
        [Environment]::SetEnvironmentVariable("PAUC_AUTOPILOT_STUB_PROBE_PATH", $autopilotProbePath, "Process")
        try {
            $autopilotPipelineItems = @(
                & $PipelineScriptPath `
                    -MetricsPath $resolvedMetricsPath `
                    -JarPath $resolvedJarPath `
                    -BuildJar:$false `
                    -CopyJarToInstance:$false `
                    -WaitForFreshMetrics:$false `
                    -RunPreflight:$false `
                    -RunCandidate:$false `
                    -RunAutopilot:$true `
                    -EnableStrictCiFailGates:$true `
                    -AutopilotAllowOneShotMetricsSignatureReplay:$true `
                    -FrameMsP95Max $overrideFrameMsP95Max `
                    -FrameMsP99Max $overrideFrameMsP99Max `
                    -MsptP95Max $overrideMsptP95Max `
                    -MinMetricsDurationSecondsForCandidatePreflight $overrideMinMetricsDurationSecondsForCandidatePreflight `
                    -CandidateMinSoakDurationSeconds $overrideCandidateMinSoakDurationSeconds `
                    -ErrorSortingNoiseWarnHitsTotal $overrideNoiseWarnHitsTotal `
                    -ErrorSortingNoiseFailHitsTotal $overrideNoiseFailHitsTotal `
                    -SummaryOutputPath $autopilotSummaryPath `
                    -AutopilotScriptPath $autopilotStubPath `
                    -PassThru
            )
        } catch {
            $autopilotInvocationError = [string]$_.Exception.Message
        }
    } finally {
        [Environment]::SetEnvironmentVariable("PAUC_AUTOPILOT_STUB_PROBE_PATH", $previousStubProbePath, "Process")
    }
} finally {
    Pop-Location
}

if (-not [string]::IsNullOrWhiteSpace($smokeInvocationError)) {
    Add-CaseCheck -Checks $checks -CaseName "smoke_no_copy" -Message ("invocation_error:{0}" -f $smokeInvocationError)
}
if (-not [string]::IsNullOrWhiteSpace($copyInvocationError)) {
    Add-CaseCheck -Checks $checks -CaseName "copy_dot_minecraft" -Message ("invocation_error:{0}" -f $copyInvocationError)
}
if (-not [string]::IsNullOrWhiteSpace($autopilotInvocationError)) {
    Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message ("invocation_error:{0}" -f $autopilotInvocationError)
}

$smokeResult = Add-ResultChecks `
    -CaseName "smoke_no_copy" `
    -PipelineItems $smokePipelineItems `
    -ExpectedJarPath $resolvedJarPath `
    -RequiredProperties $requiredProperties `
    -ExpectJarCopy $false `
    -ExpectedJarDeployedPath "" `
    -ExpectedFrameMsP95Max $defaultFrameMsP95Max `
    -ExpectedFrameMsP99Max $defaultFrameMsP99Max `
    -ExpectedMsptP95Max $defaultMsptP95Max `
    -ExpectedMinMetricsDurationSecondsForCandidatePreflight $defaultMinMetricsDurationSecondsForCandidatePreflight `
    -ExpectedCandidateMinSoakDurationSeconds $defaultCandidateMinSoakDurationSeconds `
    -ExpectedNoiseWarnHitsTotal $defaultNoiseWarnHitsTotal `
    -ExpectedNoiseFailHitsTotal $defaultNoiseFailHitsTotal `
    -ExpectedAutopilotExecuted $false `
    -ExpectedAutopilotSummaryPath "" `
    -ExpectedAutopilotScriptPath "" `
    -ExpectedAllowOneShotMetricsSignatureReplay $false `
    -Checks $checks

$copyResult = Add-ResultChecks `
    -CaseName "copy_dot_minecraft" `
    -PipelineItems $copyPipelineItems `
    -ExpectedJarPath $resolvedJarPath `
    -RequiredProperties $requiredProperties `
    -ExpectJarCopy $true `
    -ExpectedJarDeployedPath $expectedDeployedJarPath `
    -ExpectedFrameMsP95Max $overrideFrameMsP95Max `
    -ExpectedFrameMsP99Max $overrideFrameMsP99Max `
    -ExpectedMsptP95Max $overrideMsptP95Max `
    -ExpectedMinMetricsDurationSecondsForCandidatePreflight $overrideMinMetricsDurationSecondsForCandidatePreflight `
    -ExpectedCandidateMinSoakDurationSeconds $overrideCandidateMinSoakDurationSeconds `
    -ExpectedNoiseWarnHitsTotal $overrideNoiseWarnHitsTotal `
    -ExpectedNoiseFailHitsTotal $overrideNoiseFailHitsTotal `
    -ExpectedAutopilotExecuted $false `
    -ExpectedAutopilotSummaryPath "" `
    -ExpectedAutopilotScriptPath "" `
    -ExpectedAllowOneShotMetricsSignatureReplay $true `
    -Checks $checks

$autopilotResult = Add-ResultChecks `
    -CaseName "autopilot_stub_propagation" `
    -PipelineItems $autopilotPipelineItems `
    -ExpectedJarPath $resolvedJarPath `
    -RequiredProperties $requiredProperties `
    -ExpectJarCopy $false `
    -ExpectedJarDeployedPath "" `
    -ExpectedFrameMsP95Max $overrideFrameMsP95Max `
    -ExpectedFrameMsP99Max $overrideFrameMsP99Max `
    -ExpectedMsptP95Max $overrideMsptP95Max `
    -ExpectedMinMetricsDurationSecondsForCandidatePreflight $overrideMinMetricsDurationSecondsForCandidatePreflight `
    -ExpectedCandidateMinSoakDurationSeconds $overrideCandidateMinSoakDurationSeconds `
    -ExpectedNoiseWarnHitsTotal $overrideNoiseWarnHitsTotal `
    -ExpectedNoiseFailHitsTotal $overrideNoiseFailHitsTotal `
    -ExpectedAutopilotExecuted $true `
    -ExpectedAutopilotSummaryPath $autopilotSummaryPath `
    -ExpectedAutopilotScriptPath $autopilotStubPath `
    -ExpectedAllowOneShotMetricsSignatureReplay $true `
    -Checks $checks

if ($null -eq $autopilotResult) {
    Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "missing_autopilot_result"
} else {
    if ([bool]$autopilotResult.autopilot_failed) {
        Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "autopilot_failed_should_be_false"
    }
    if (-not [string]::Equals([string]$autopilotResult.autopilot_effective_decision, "ready_for_beta", [System.StringComparison]::OrdinalIgnoreCase)) {
        Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message ("autopilot_effective_decision_mismatch:{0}" -f [string]$autopilotResult.autopilot_effective_decision)
    }
}

if (-not (Test-Path -LiteralPath $autopilotProbePath -PathType Leaf)) {
    Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "autopilot_probe_missing"
} else {
    try {
        $autopilotProbe = Get-Content -LiteralPath $autopilotProbePath -Raw | ConvertFrom-Json
        $resolvedExpectedAutopilotSummaryPath = (Resolve-Path -LiteralPath $autopilotSummaryPath).Path
        if (-not [bool]$autopilotProbe.one_shot) {
            Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "probe_one_shot_should_be_true"
        }
        if (-not [bool]$autopilotProbe.summary_output_compress) {
            Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "probe_summary_output_compress_should_be_true"
        }
        if (-not [bool]$autopilotProbe.strict_ci_fail_gates_enabled) {
            Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "probe_strict_ci_fail_gates_should_be_true"
        }
        if (-not [bool]$autopilotProbe.allow_one_shot_metrics_signature_replay) {
            Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "probe_allow_replay_should_be_true"
        }
        if ([double]$autopilotProbe.frame_ms_p95_max -ne $overrideFrameMsP95Max) {
            Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "probe_frame_ms_p95_max_mismatch"
        }
        if ([double]$autopilotProbe.frame_ms_p99_max -ne $overrideFrameMsP99Max) {
            Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "probe_frame_ms_p99_max_mismatch"
        }
        if ([double]$autopilotProbe.mspt_p95_max -ne $overrideMsptP95Max) {
            Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "probe_mspt_p95_max_mismatch"
        }
        if ([int]$autopilotProbe.min_metrics_duration_seconds_for_candidate_preflight -ne $overrideMinMetricsDurationSecondsForCandidatePreflight) {
            Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "probe_min_metrics_duration_seconds_for_candidate_preflight_mismatch"
        }
        if ([int]$autopilotProbe.candidate_min_soak_duration_seconds -ne $overrideCandidateMinSoakDurationSeconds) {
            Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "probe_candidate_min_soak_duration_seconds_mismatch"
        }
        if ([int]$autopilotProbe.error_sorting_noise_warn_hits_total -ne $overrideNoiseWarnHitsTotal) {
            Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "probe_noise_warn_hits_total_mismatch"
        }
        if ([int]$autopilotProbe.error_sorting_noise_fail_hits_total -ne $overrideNoiseFailHitsTotal) {
            Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "probe_noise_fail_hits_total_mismatch"
        }
        if (-not [string]::Equals([string]$autopilotProbe.metrics_path, $resolvedMetricsPath, [System.StringComparison]::OrdinalIgnoreCase)) {
            Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "probe_metrics_path_mismatch"
        }
        if (-not [string]::Equals([string]$autopilotProbe.summary_output_path, $resolvedExpectedAutopilotSummaryPath, [System.StringComparison]::OrdinalIgnoreCase)) {
            Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message "probe_summary_output_path_mismatch"
        }
    } catch {
        Add-CaseCheck -Checks $checks -CaseName "autopilot_stub_propagation" -Message ("autopilot_probe_parse_error:{0}" -f $_.Exception.Message)
    }
}

$passed = ($checks.Count -eq 0)
$report = [PSCustomObject]@{
    generated_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    script_path = (Resolve-Path -LiteralPath $PipelineScriptPath).Path
    metrics_path = $resolvedMetricsPath
    jar_path = $resolvedJarPath
    smoke_pipeline_output_count = $smokePipelineItems.Count
    copy_pipeline_output_count = $copyPipelineItems.Count
    autopilot_pipeline_output_count = $autopilotPipelineItems.Count
    fixture_prism_root = $fixturePrismRoot
    fixture_instance_name = $fixtureInstanceName
    fixture_expected_jar_deployed_path = $expectedDeployedJarPath
    autopilot_stub_path = $autopilotStubPath
    autopilot_probe_path = $autopilotProbePath
    autopilot_summary_path = $autopilotSummaryPath
    cases = @($caseSummaries.ToArray())
    passed = $passed
    checks = @($checks.ToArray())
    session_dir = (Resolve-Path -LiteralPath $sessionDir).Path
}

$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8

Write-Host ""
Write-Host "run_capture_pipeline_auto passthru self-test"
Write-Host "--------------------------------------------"
Write-Host ("script_path: {0}" -f $report.script_path)
Write-Host ("metrics_path: {0}" -f $report.metrics_path)
Write-Host ("jar_path: {0}" -f $report.jar_path)
Write-Host ("smoke_pipeline_output_count: {0}" -f $report.smoke_pipeline_output_count)
Write-Host ("copy_pipeline_output_count: {0}" -f $report.copy_pipeline_output_count)
Write-Host ("status: {0}" -f $(if ($passed) { "pass" } else { "fail" }))
Write-Host ("report: {0}" -f (Resolve-Path -LiteralPath $reportPath).Path)

if ($PassThru) {
    Write-Output $report
}

if (-not $passed) {
    Write-Host "checks:"
    foreach ($check in $report.checks) {
        Write-Host ("- {0}" -f $check)
    }
    throw ("run_capture_pipeline_auto passthru self-test failed ({0} checks)" -f $checks.Count)
}
