param(
    [string]$AutopilotScriptPath = "",
    [string]$ReportsRoot = ".\run\pauc_reports",
    [string]$PrismRoot = "",
    [string]$InstanceName = "ci_selftest_missing_logs",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($AutopilotScriptPath)) {
    $AutopilotScriptPath = Join-Path $PSScriptRoot "run_roadmap_autopilot.ps1"
}
if (-not (Test-Path -LiteralPath $AutopilotScriptPath -PathType Leaf)) {
    throw "Autopilot script not found: $AutopilotScriptPath"
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

if (-not (Test-Path -LiteralPath $ReportsRoot)) {
    New-Item -Path $ReportsRoot -ItemType Directory -Force | Out-Null
}

$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd_HHmmss_fff")
$sessionDir = Join-Path $ReportsRoot ("autopilot_fail_gate_selftest_{0}" -f $stamp)
New-Item -Path $sessionDir -ItemType Directory -Force | Out-Null

if ([string]::IsNullOrWhiteSpace($PrismRoot)) {
    # Intentionally absent path so error-sorting pass hits deterministic missing-log behavior.
    $PrismRoot = Join-Path $sessionDir "missing_prism_root"
}

$cases = @(
    [PSCustomObject]@{
        name = "baseline_no_fail_gates"
        gate_args = @{
            RunErrorSortingPass = $false
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
    },
    [PSCustomObject]@{
        name = "blocking_gate_forces_error_sorting"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingBlockingPatterns = $true
        }
        expected_reason = "error_sorting_blocking_patterns"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
    },
    [PSCustomObject]@{
        name = "status_gate_forces_error_sorting"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingStatusNotPass = $true
        }
        expected_reason = "error_sorting_status_not_pass"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
    },
    [PSCustomObject]@{
        name = "noise_fail_gate_forces_error_sorting"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingNoiseFail = $true
        }
        expected_reason = "error_sorting_noise_status_unavailable_for_fail"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
    },
    [PSCustomObject]@{
        name = "strict_bundle_enables_error_sorting_gates"
        gate_args = @{
            EnableStrictCiFailGates = $true
            StrictCiForceSummaryOutputCompress = $false
        }
        expected_reason = "pending_metrics_decision"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $true
        expected_fail_on_error_sorting_blocking_patterns = $true
        expected_fail_on_error_sorting_noise_fail = $true
    }
)

$results = New-Object System.Collections.Generic.List[object]

foreach ($case in $cases) {
    $summaryPath = Join-Path $sessionDir ("{0}.json" -f $case.name)
    $caseLogPath = Join-Path $sessionDir ("{0}.log" -f $case.name)
    $runArgs = @{
        OneShot = $true
        PrismRoot = $PrismRoot
        InstanceName = $InstanceName
        SummaryOutputPath = $summaryPath
        ReportsDir = $sessionDir
        CandidateRoot = (Join-Path $sessionDir "missing_candidates")
        MetricsPath = (Join-Path $sessionDir "missing_runtime_metrics.csv")
        StatePath = (Join-Path $sessionDir "missing_ab_capture_state.json")
        AutopilotStatePath = (Join-Path $sessionDir ("autopilot_state_{0}.json" -f $case.name))
        DisableAutoMetricsDiscovery = $true
        AutoSyncModJarToPrism = $false
    }
    foreach ($entry in $case.gate_args.GetEnumerator()) {
        $runArgs[$entry.Key] = $entry.Value
    }

    $exceptionMessage = ""
    $exitCode = 0
    Reset-LastExitCode
    try {
        & $AutopilotScriptPath @runArgs *> $caseLogPath
        $exitCode = Get-LastExitCodeOrZero
    } catch {
        $exceptionMessage = $_.Exception.Message
        $exitCode = Get-LastExitCodeOrZero
        if ($exitCode -eq 0) {
            $exitCode = 1
        }
    } finally {
        Reset-LastExitCode
    }

    $summary = $null
    if (Test-Path -LiteralPath $summaryPath -PathType Leaf) {
        try {
            $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
        } catch {
            $summary = $null
        }
    }

    $actualReason = if ($null -eq $summary) { "" } else { [string]$summary.autopilot_failure_reason }
    $actualFailed = if ($null -eq $summary) { ($exitCode -ne 0) } else { [bool]$summary.autopilot_failed }
    $actualForced = if ($null -eq $summary) { $false } else { [bool]$summary.error_sorting_pass_forced_by_fail_gate }
    $actualStrictCi = if ($null -eq $summary) { $false } else { [bool]$summary.strict_ci_fail_gates_enabled }
    $actualFailOnBlocking = if ($null -eq $summary) { $false } else { [bool]$summary.fail_on_error_sorting_blocking_patterns }
    $actualFailOnNoiseFail = if ($null -eq $summary) { $false } else { [bool]$summary.fail_on_error_sorting_noise_fail }

    $checks = New-Object System.Collections.Generic.List[string]
    $passed = $true

    if ($case.expect_exit_nonzero -and $exitCode -eq 0) {
        $checks.Add("expected_nonzero_exit")
        $passed = $false
    } elseif (-not $case.expect_exit_nonzero -and $exitCode -ne 0) {
        $checks.Add("expected_zero_exit")
        $passed = $false
    }
    if ($actualReason -ne [string]$case.expected_reason) {
        $checks.Add("unexpected_reason")
        $passed = $false
    }
    if ($actualFailed -ne [bool]$case.expect_failed) {
        $checks.Add("unexpected_failed_flag")
        $passed = $false
    }
    if ($actualForced -ne [bool]$case.expect_forced_error_sorting) {
        $checks.Add("unexpected_error_sorting_force_flag")
        $passed = $false
    }
    if ($null -ne $case.expected_strict_ci -and $actualStrictCi -ne [bool]$case.expected_strict_ci) {
        $checks.Add("unexpected_strict_ci_flag")
        $passed = $false
    }
    if ($null -ne $case.expected_fail_on_error_sorting_blocking_patterns -and `
            $actualFailOnBlocking -ne [bool]$case.expected_fail_on_error_sorting_blocking_patterns) {
        $checks.Add("unexpected_fail_on_error_sorting_blocking_patterns_flag")
        $passed = $false
    }
    if ($null -ne $case.expected_fail_on_error_sorting_noise_fail -and `
            $actualFailOnNoiseFail -ne [bool]$case.expected_fail_on_error_sorting_noise_fail) {
        $checks.Add("unexpected_fail_on_error_sorting_noise_fail_flag")
        $passed = $false
    }

    $results.Add([PSCustomObject]@{
            name = $case.name
            passed = $passed
            exit_code = $exitCode
            autopilot_failed = $actualFailed
            autopilot_failure_reason = $actualReason
            error_sorting_pass_forced_by_fail_gate = $actualForced
            strict_ci_fail_gates_enabled = $actualStrictCi
            fail_on_error_sorting_blocking_patterns = $actualFailOnBlocking
            fail_on_error_sorting_noise_fail = $actualFailOnNoiseFail
            summary_path = if (Test-Path -LiteralPath $summaryPath -PathType Leaf) { (Resolve-Path -LiteralPath $summaryPath).Path } else { "" }
            case_log_path = if (Test-Path -LiteralPath $caseLogPath -PathType Leaf) { (Resolve-Path -LiteralPath $caseLogPath).Path } else { "" }
            checks = @($checks.ToArray())
            exception = $exceptionMessage
        })
}

$passedCount = @($results | Where-Object { [bool]$_.passed }).Count
$failedCount = $results.Count - $passedCount

$report = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    autopilot_script_path = (Resolve-Path -LiteralPath $AutopilotScriptPath).Path
    session_dir = (Resolve-Path -LiteralPath $sessionDir).Path
    total_cases = $results.Count
    passed_cases = $passedCount
    failed_cases = $failedCount
    results = @($results.ToArray())
}

$reportPath = Join-Path $sessionDir "autopilot_fail_gate_selftest_summary.json"
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8

Write-Host ""
Write-Host "Autopilot fail-gate self-test"
Write-Host "-----------------------------"
Write-Host ("Cases: total={0}, passed={1}, failed={2}" -f $report.total_cases, $report.passed_cases, $report.failed_cases)
Write-Host ("Case logs directory: {0}" -f (Resolve-Path -LiteralPath $sessionDir).Path)
Write-Host ""
@($results.ToArray()) | Select-Object name, passed, exit_code, autopilot_failure_reason, strict_ci_fail_gates_enabled, error_sorting_pass_forced_by_fail_gate, case_log_path | Format-Table -AutoSize
Write-Host ""
Write-Host ("Report: {0}" -f (Resolve-Path -LiteralPath $reportPath).Path)

if ($failedCount -gt 0) {
    throw ("Autopilot fail-gate self-test failed: {0} case(s) failed." -f $failedCount)
}

if ($PassThru) {
    $report
}
