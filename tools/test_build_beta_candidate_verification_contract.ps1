param(
    [string]$BuildScriptPath = "",
    [string]$ReportsRoot = ".\run\pauc_reports",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($BuildScriptPath)) {
    $BuildScriptPath = Join-Path $PSScriptRoot "build_beta_candidate.ps1"
}
if (-not (Test-Path -LiteralPath $BuildScriptPath -PathType Leaf)) {
    throw "build_beta_candidate script not found: $BuildScriptPath"
}

function Get-LastExitCodeOrZero {
    $exitVar = Get-Variable -Name LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue
    if ($null -eq $exitVar -or $null -eq $exitVar.Value) {
        return 0
    }
    return [int]$exitVar.Value
}

function Reset-LastExitCode {
    Set-Variable -Name LASTEXITCODE -Scope Global -Value 0
}

function New-MinimalPreflightReport {
    param([string]$ReportPath)

    $lines = @(
        "# PauC Phase 6 Preflight Report"
        ""
        "- Documentation freshness: ok"
        "- Compile: ok"
        "- Compile warnings: pass"
        "- Shader compatibility: ok"
        "- Metrics summary: ok"
        "- Server governor health: pass"
        "- Chunk compile health: pass"
        "- DRS/deferred safety: pass"
        "- Soak stability: pass"
        "- KPI gate: pass"
        "- A/B audit: pass"
        "- A/B progress: pass"
    )
    Set-Content -LiteralPath $ReportPath -Value $lines -Encoding UTF8
}

function Get-MockVerifyScriptContent {
    param(
        [string]$Mode,
        [string]$MarkerPath
    )

    $escapedMarkerPath = $MarkerPath.Replace("'", "''")
    $header = @"
param(
    [string]`$CandidateDir,
    [switch]`$SkipChecksumValidation,
    [switch]`$RequireExtendedArtifacts,
    [switch]`$FailOnIssues,
    [switch]`$PassThru,
    [switch]`$SuppressConsoleSummary
)

'verify-invoked' | Set-Content -LiteralPath '$escapedMarkerPath' -Encoding UTF8
"@

    switch ($Mode) {
        "none" {
            return $header
        }
        "multiple" {
            return ($header + @"

Write-Output ([PSCustomObject]@{
    overall_status = 'pass'
})
Write-Output ([PSCustomObject]@{
    overall_status = 'pass'
})
"@)
        }
        "missing_status" {
            return ($header + @"

Write-Output ([PSCustomObject]@{
    candidate_dir = `$CandidateDir
    issue_count = 0
    warning_count = 0
})
"@)
        }
        "valid" {
            return ($header + @"

Write-Output ([PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    candidate_dir = `$CandidateDir
    jar_count = 1
    preflight_report_count = 1
    issue_count = 0
    warning_count = 0
    issues_list = @()
    warnings_list = @()
    issues = ''
    warnings = ''
    overall_status = 'pass'
})
"@)
        }
        default {
            throw "Unsupported mock verify mode: $Mode"
        }
    }
}

if (-not (Test-Path -LiteralPath $ReportsRoot)) {
    New-Item -ItemType Directory -Path $ReportsRoot -Force | Out-Null
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss_fff"
$sessionDir = Join-Path $ReportsRoot ("build_beta_candidate_contract_selftest_{0}" -f $stamp)
New-Item -ItemType Directory -Path $sessionDir -Force | Out-Null

$cases = @(
    [PSCustomObject]@{
        name = "verify_output_none"
        mode = "none"
        expect_throw = $true
        expected_error_contains = "unexpected pipeline output count: 0"
    },
    [PSCustomObject]@{
        name = "verify_output_multiple"
        mode = "multiple"
        expect_throw = $true
        expected_error_contains = "unexpected pipeline output count: 2"
    },
    [PSCustomObject]@{
        name = "verify_output_missing_status"
        mode = "missing_status"
        expect_throw = $true
        expected_error_contains = "missing 'overall_status' property"
    },
    [PSCustomObject]@{
        name = "verify_output_valid_single"
        mode = "valid"
        expect_throw = $false
        expected_error_contains = ""
    }
)

$results = New-Object System.Collections.Generic.List[object]

foreach ($case in $cases) {
    $caseRoot = Join-Path $sessionDir $case.name
    $reportsDir = Join-Path $caseRoot "reports"
    $candidateRoot = Join-Path $caseRoot "candidates"
    $resultsPath = Join-Path $caseRoot "missing_results.csv"
    $verifyScriptPath = Join-Path $caseRoot "verify_candidate_mock.ps1"
    $markerPath = Join-Path $caseRoot "verify_invoked.marker"

    New-Item -ItemType Directory -Path $caseRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $reportsDir -Force | Out-Null
    New-Item -ItemType Directory -Path $candidateRoot -Force | Out-Null

    $reportPath = Join-Path $reportsDir "phase6_preflight_20990101_000000_000.md"
    New-MinimalPreflightReport -ReportPath $reportPath

    $mockScript = Get-MockVerifyScriptContent -Mode ([string]$case.mode) -MarkerPath $markerPath
    Set-Content -LiteralPath $verifyScriptPath -Value $mockScript -Encoding UTF8

    $exceptionMessage = ""
    $exitCode = 0
    $checks = New-Object System.Collections.Generic.List[string]
    $passed = $true

    try {
        & $BuildScriptPath `
            -CandidateRoot $candidateRoot `
            -ReportsDir $reportsDir `
            -ResultsPath $resultsPath `
            -SkipPreflight `
            -SkipJarBuild `
            -SkipActionPlan `
            -SkipProfileCopy `
            -SkipChecksums `
            -KeepFailedCandidate `
            -VerifyCandidateScriptPath $verifyScriptPath
        $exitCode = Get-LastExitCodeOrZero
        if ($exitCode -ne 0) {
            throw "build_beta_candidate exited with non-zero code: $exitCode"
        }
    } catch {
        $exceptionMessage = [string]$_.Exception.Message
        $exitCode = if (Get-LastExitCodeOrZero -ne 0) { Get-LastExitCodeOrZero } else { 1 }
    } finally {
        Reset-LastExitCode
    }

    $didThrow = -not [string]::IsNullOrWhiteSpace($exceptionMessage)
    if ($didThrow -ne [bool]$case.expect_throw) {
        $checks.Add("unexpected_throw_behavior")
        $passed = $false
    }

    if (-not [string]::IsNullOrWhiteSpace([string]$case.expected_error_contains)) {
        if (-not $didThrow -or ($exceptionMessage -notlike ("*{0}*" -f [string]$case.expected_error_contains))) {
            $checks.Add("unexpected_error_message")
            $passed = $false
        }
    }

    $markerExists = Test-Path -LiteralPath $markerPath -PathType Leaf
    if (-not $markerExists) {
        $checks.Add("verify_script_not_invoked")
        $passed = $false
    }

    $candidateDirs = @(
        Get-ChildItem -LiteralPath $candidateRoot -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name
    )
    if ($candidateDirs.Count -lt 1) {
        $checks.Add("candidate_not_created")
        $passed = $false
    }

    if (-not $didThrow) {
        $latestCandidate = $candidateDirs | Select-Object -Last 1
        if ($null -eq $latestCandidate) {
            $checks.Add("missing_latest_candidate")
            $passed = $false
        } else {
            $requiredFiles = @(
                "BETA_CANDIDATE.md",
                "beta_readiness.json",
                "candidate_manifest.json"
            )
            foreach ($requiredFile in $requiredFiles) {
                $requiredPath = Join-Path $latestCandidate.FullName $requiredFile
                if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
                    $checks.Add(("missing_required_file_{0}" -f $requiredFile))
                    $passed = $false
                }
            }

            $candidateJarFiles = @(Get-ChildItem -LiteralPath $latestCandidate.FullName -File -Filter "*.jar" -ErrorAction SilentlyContinue)
            if ($candidateJarFiles.Count -lt 1) {
                $checks.Add("missing_candidate_jar")
                $passed = $false
            }
        }
    }

    $results.Add([PSCustomObject]@{
            name = $case.name
            passed = $passed
            did_throw = $didThrow
            exit_code = $exitCode
            marker_exists = $markerExists
            candidate_dir_count = $candidateDirs.Count
            error = $exceptionMessage
            checks = @($checks.ToArray())
        })
}

$resultArray = @($results.ToArray())
$failed = @($resultArray | Where-Object { -not [bool]$_.passed })
$report = [PSCustomObject]@{
    generated_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    build_script_path = (Resolve-Path -LiteralPath $BuildScriptPath).Path
    total_cases = $resultArray.Count
    passed_cases = $resultArray.Count - $failed.Count
    failed_cases = $failed.Count
    session_dir = (Resolve-Path -LiteralPath $sessionDir).Path
    results = $resultArray
}

$reportPath = Join-Path $sessionDir "build_beta_candidate_contract_selftest_summary.json"
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8

Write-Host ""
Write-Host "build_beta_candidate verification contract self-test"
Write-Host "---------------------------------------------------"
Write-Host ("Cases: total={0}, passed={1}, failed={2}" -f $report.total_cases, $report.passed_cases, $report.failed_cases)
Write-Host ("Session: {0}" -f $report.session_dir)
Write-Host ""
@($resultArray) | Select-Object name, passed, did_throw, marker_exists, candidate_dir_count, error | Format-Table -AutoSize
Write-Host ""
Write-Host ("Report: {0}" -f (Resolve-Path -LiteralPath $reportPath).Path)

if ($PassThru) {
    Write-Output $report
}

if ($report.failed_cases -gt 0) {
    throw ("build_beta_candidate verification contract self-test failed ({0}/{1} failed)" -f $report.failed_cases, $report.total_cases)
}

