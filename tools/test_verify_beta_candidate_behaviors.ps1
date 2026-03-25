param(
    [string]$VerifyScriptPath = "",
    [string]$ReportsRoot = ".\run\pauc_reports",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($VerifyScriptPath)) {
    $VerifyScriptPath = Join-Path $PSScriptRoot "verify_beta_candidate.ps1"
}
if (-not (Test-Path -LiteralPath $VerifyScriptPath -PathType Leaf)) {
    throw "verify_beta_candidate script not found: $VerifyScriptPath"
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

function Get-RelativePathFromRoot {
    param(
        [string]$RootPath,
        [string]$TargetPath
    )

    $rootFull = (Resolve-Path -LiteralPath $RootPath).Path
    $targetFull = (Resolve-Path -LiteralPath $TargetPath).Path
    if (-not $targetFull.StartsWith($rootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Target path '$targetFull' is not under root '$rootFull'"
    }

    $relative = $targetFull.Substring($rootFull.Length).TrimStart('\', '/')
    return ($relative -replace '\\', '/')
}

function New-TestCandidateFixture {
    param(
        [string]$RootDir,
        [string]$Name,
        [bool]$MissingReadiness = $false,
        [bool]$ExtraJar = $false,
        [bool]$ChecksumMismatch = $false,
        [bool]$OmitManifestJarIdentity = $false
    )

    $candidateDir = Join-Path $RootDir $Name
    New-Item -ItemType Directory -Path $candidateDir -Force | Out-Null

    $jarPath = Join-Path $candidateDir "pauc-selftest.jar"
    "jar-bytes-$Name" | Set-Content -LiteralPath $jarPath -Encoding UTF8

    if ([bool]$ExtraJar) {
        $extraJarPath = Join-Path $candidateDir "pauc-selftest-extra.jar"
        "jar-extra-$Name" | Set-Content -LiteralPath $extraJarPath -Encoding UTF8
    }

    $manifestMdPath = Join-Path $candidateDir "BETA_CANDIDATE.md"
    @(
        "# Beta Candidate Self-Test"
        ""
        "- case: $Name"
    ) | Set-Content -LiteralPath $manifestMdPath -Encoding UTF8

    $preflightPath = Join-Path $candidateDir ("phase6_preflight_{0}.md" -f $Name)
    @(
        "# Preflight"
        ""
        "status: pass"
    ) | Set-Content -LiteralPath $preflightPath -Encoding UTF8

    $readinessPath = Join-Path $candidateDir "beta_readiness.json"
    if (-not [bool]$MissingReadiness) {
        $readinessPayload = [PSCustomObject]@{
            decision = "ready_for_beta"
            readiness_percent = 100
        }
        $readinessPayload | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $readinessPath -Encoding UTF8
    }

    $jarHash = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToUpperInvariant()
    $manifestJsonPath = Join-Path $candidateDir "candidate_manifest.json"
    $manifestJarName = if ([bool]$OmitManifestJarIdentity) { "" } else { "pauc-selftest.jar" }
    $manifestPayload = [PSCustomObject]@{
        readiness_decision = "ready_for_beta"
        jar_name = $manifestJarName
        jar_sha256 = $jarHash
    }
    $manifestPayload | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestJsonPath -Encoding UTF8

    $profilesDir = Join-Path $candidateDir "profiles"
    New-Item -ItemType Directory -Path $profilesDir -Force | Out-Null
    @(
        "profile.name=default"
        "profile.render_distance=16"
    ) | Set-Content -LiteralPath (Join-Path $profilesDir "pauc_profile_default.properties") -Encoding UTF8

    $checksumTargets = @(
        $jarPath,
        $manifestMdPath,
        $preflightPath,
        $manifestJsonPath
    )
    if (-not [bool]$MissingReadiness) {
        $checksumTargets += $readinessPath
    }
    $profileFile = Join-Path $profilesDir "pauc_profile_default.properties"
    if (Test-Path -LiteralPath $profileFile -PathType Leaf) {
        $checksumTargets += $profileFile
    }

    $checksumLines = New-Object System.Collections.Generic.List[string]
    foreach ($target in $checksumTargets) {
        $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToUpperInvariant()
        $relative = Get-RelativePathFromRoot -RootPath $candidateDir -TargetPath $target
        $checksumLines.Add(("{0} *{1}" -f $hash, $relative))
    }

    if ([bool]$ChecksumMismatch -and $checksumLines.Count -gt 0) {
        $firstLine = [string]$checksumLines[0]
        $separatorIndex = $firstLine.IndexOf(" *")
        if ($separatorIndex -gt 0) {
            $pathSuffix = $firstLine.Substring($separatorIndex)
            $checksumLines[0] = ("{0}{1}" -f ("0" * 64), $pathSuffix)
        }
    }

    Set-Content -LiteralPath (Join-Path $candidateDir "SHA256SUMS.txt") -Value $checksumLines -Encoding UTF8
    return $candidateDir
}

if (-not (Test-Path -LiteralPath $ReportsRoot)) {
    New-Item -Path $ReportsRoot -ItemType Directory -Force | Out-Null
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss_fff"
$sessionDir = Join-Path $ReportsRoot ("verify_beta_candidate_selftest_{0}" -f $stamp)
New-Item -Path $sessionDir -ItemType Directory -Force | Out-Null

$cases = @(
    [PSCustomObject]@{
        name = "pass_extended_artifacts"
        fixture = @{
            MissingReadiness = $false
            ExtraJar = $false
            ChecksumMismatch = $false
            OmitManifestJarIdentity = $false
        }
        verify_args = @{
            RequireExtendedArtifacts = $true
            PassThru = $true
        }
        expect_throw = $false
        expected_status = "pass"
        expected_issue_count = 0
        expected_warning_count = 0
        expect_output_count = 1
    },
    [PSCustomObject]@{
        name = "warn_multiple_jars"
        fixture = @{
            MissingReadiness = $false
            ExtraJar = $true
            ChecksumMismatch = $false
            OmitManifestJarIdentity = $false
        }
        verify_args = @{
            RequireExtendedArtifacts = $true
            PassThru = $true
        }
        expect_throw = $false
        expected_status = "warn"
        expected_issue_count = 0
        min_warning_count = 1
        expect_output_count = 1
    },
    [PSCustomObject]@{
        name = "fail_multi_jar_sha_without_name"
        fixture = @{
            MissingReadiness = $false
            ExtraJar = $true
            ChecksumMismatch = $false
            OmitManifestJarIdentity = $true
        }
        verify_args = @{
            RequireExtendedArtifacts = $true
            PassThru = $true
        }
        expect_throw = $false
        expected_status = "fail"
        min_issue_count = 1
        expect_output_count = 1
    },
    [PSCustomObject]@{
        name = "fail_missing_readiness"
        fixture = @{
            MissingReadiness = $true
            ExtraJar = $false
            ChecksumMismatch = $false
            OmitManifestJarIdentity = $false
        }
        verify_args = @{
            RequireExtendedArtifacts = $true
            PassThru = $true
        }
        expect_throw = $false
        expected_status = "fail"
        min_issue_count = 1
        expect_output_count = 1
    },
    [PSCustomObject]@{
        name = "fail_checksum_mismatch"
        fixture = @{
            MissingReadiness = $false
            ExtraJar = $false
            ChecksumMismatch = $true
            OmitManifestJarIdentity = $false
        }
        verify_args = @{
            RequireExtendedArtifacts = $true
            PassThru = $true
        }
        expect_throw = $false
        expected_status = "fail"
        min_issue_count = 1
        expect_output_count = 1
    },
    [PSCustomObject]@{
        name = "fail_on_issues_throws_on_warn"
        fixture = @{
            MissingReadiness = $false
            ExtraJar = $true
            ChecksumMismatch = $false
            OmitManifestJarIdentity = $false
        }
        verify_args = @{
            RequireExtendedArtifacts = $true
            PassThru = $true
            FailOnIssues = $true
        }
        expect_throw = $true
        expected_error_contains = "status=warn"
    },
    [PSCustomObject]@{
        name = "fail_on_issues_throws_on_fail"
        fixture = @{
            MissingReadiness = $true
            ExtraJar = $false
            ChecksumMismatch = $false
            OmitManifestJarIdentity = $false
        }
        verify_args = @{
            RequireExtendedArtifacts = $true
            PassThru = $true
            FailOnIssues = $true
        }
        expect_throw = $true
        expected_error_contains = "status=fail"
    }
)

$results = New-Object System.Collections.Generic.List[object]

foreach ($case in $cases) {
    $caseDir = Join-Path $sessionDir $case.name
    $fixtureDir = New-TestCandidateFixture `
        -RootDir $sessionDir `
        -Name $case.name `
        -MissingReadiness ([bool]$case.fixture.MissingReadiness) `
        -ExtraJar ([bool]$case.fixture.ExtraJar) `
        -ChecksumMismatch ([bool]$case.fixture.ChecksumMismatch) `
        -OmitManifestJarIdentity ([bool]$case.fixture.OmitManifestJarIdentity)

    $actualError = ""
    $actualExitCode = 0
    $actualOutputItems = @()
    $actualResult = $null
    $passed = $true
    $checks = New-Object System.Collections.Generic.List[string]

    try {
        $verifyArgs = @{}
        foreach ($entry in $case.verify_args.GetEnumerator()) {
            $verifyArgs[[string]$entry.Key] = $entry.Value
        }
        $verifyArgs.CandidateDir = $fixtureDir
        $verifyArgs.SuppressConsoleSummary = $true

        $actualOutputItems = @(& $VerifyScriptPath @verifyArgs)
        $actualExitCode = Get-LastExitCodeOrZero
        if ($actualOutputItems.Count -gt 0) {
            $actualResult = $actualOutputItems[$actualOutputItems.Count - 1]
        }
    } catch {
        $actualError = [string]$_.Exception.Message
        $actualExitCode = if (Get-LastExitCodeOrZero -ne 0) { Get-LastExitCodeOrZero } else { 1 }
    } finally {
        Reset-LastExitCode
    }

    $didThrow = -not [string]::IsNullOrWhiteSpace($actualError)
    if ($didThrow -ne [bool]$case.expect_throw) {
        $checks.Add("unexpected_throw_behavior")
        $passed = $false
    }

    if ($case.PSObject.Properties.Name -contains "expected_error_contains" -and `
            -not [string]::IsNullOrWhiteSpace([string]$case.expected_error_contains)) {
        if (-not $didThrow -or ($actualError -notlike ("*{0}*" -f [string]$case.expected_error_contains))) {
            $checks.Add("unexpected_error_message")
            $passed = $false
        }
    }

    if (-not $didThrow) {
        if ($null -eq $actualResult) {
            $checks.Add("missing_result_object")
            $passed = $false
        } else {
            $actualIssuesList = @($actualResult.issues_list)
            $actualWarningsList = @($actualResult.warnings_list)

            if ($case.PSObject.Properties.Name -contains "expected_status" -and `
                    [string]$actualResult.overall_status -ne [string]$case.expected_status) {
                $checks.Add("unexpected_overall_status")
                $passed = $false
            }
            if ($case.PSObject.Properties.Name -contains "expected_issue_count" -and `
                    [int]$actualResult.issue_count -ne [int]$case.expected_issue_count) {
                $checks.Add("unexpected_issue_count")
                $passed = $false
            }
            if ($case.PSObject.Properties.Name -contains "min_issue_count" -and `
                    [int]$actualResult.issue_count -lt [int]$case.min_issue_count) {
                $checks.Add("min_issue_count_not_met")
                $passed = $false
            }
            if ($case.PSObject.Properties.Name -contains "expected_warning_count" -and `
                    [int]$actualResult.warning_count -ne [int]$case.expected_warning_count) {
                $checks.Add("unexpected_warning_count")
                $passed = $false
            }
            if ($case.PSObject.Properties.Name -contains "min_warning_count" -and `
                    [int]$actualResult.warning_count -lt [int]$case.min_warning_count) {
                $checks.Add("min_warning_count_not_met")
                $passed = $false
            }

            if ([int]$actualResult.issue_count -ne $actualIssuesList.Count) {
                $checks.Add("issue_count_vs_list_mismatch")
                $passed = $false
            }
            if ([int]$actualResult.warning_count -ne $actualWarningsList.Count) {
                $checks.Add("warning_count_vs_list_mismatch")
                $passed = $false
            }

            $actualStatus = [string]$actualResult.overall_status
            if ($actualStatus -eq "pass" -and `
                    ([int]$actualResult.issue_count -ne 0 -or [int]$actualResult.warning_count -ne 0)) {
                $checks.Add("pass_status_with_nonzero_counts")
                $passed = $false
            }
            if ($actualStatus -eq "warn" -and `
                    ([int]$actualResult.issue_count -ne 0 -or [int]$actualResult.warning_count -lt 1)) {
                $checks.Add("warn_status_count_invariant_broken")
                $passed = $false
            }
            if ($actualStatus -eq "fail" -and [int]$actualResult.issue_count -lt 1) {
                $checks.Add("fail_status_without_issues")
                $passed = $false
            }
        }

        if ($case.PSObject.Properties.Name -contains "expect_output_count" -and `
                $actualOutputItems.Count -ne [int]$case.expect_output_count) {
            $checks.Add("unexpected_output_count")
            $passed = $false
        }
    }

    $results.Add([PSCustomObject]@{
            name = $case.name
            passed = $passed
            did_throw = $didThrow
            exit_code = $actualExitCode
            overall_status = if ($null -eq $actualResult) { "" } else { [string]$actualResult.overall_status }
            issue_count = if ($null -eq $actualResult) { -1 } else { [int]$actualResult.issue_count }
            warning_count = if ($null -eq $actualResult) { -1 } else { [int]$actualResult.warning_count }
            output_count = $actualOutputItems.Count
            error = $actualError
            checks = @($checks.ToArray())
        })

    if (Test-Path -LiteralPath $caseDir -PathType Container) {
        # no-op: caseDir is same as fixtureDir and intentionally kept for report inspection
        $null = $caseDir
    }
}

$resultArray = @($results.ToArray())
$failed = @($resultArray | Where-Object { -not [bool]$_.passed })
$report = [PSCustomObject]@{
    generated_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    verify_script_path = (Resolve-Path -LiteralPath $VerifyScriptPath).Path
    total_cases = $resultArray.Count
    passed_cases = $resultArray.Count - $failed.Count
    failed_cases = $failed.Count
    session_dir = (Resolve-Path -LiteralPath $sessionDir).Path
    results = $resultArray
}

$reportPath = Join-Path $sessionDir "verify_beta_candidate_selftest_summary.json"
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8

Write-Host ""
Write-Host "verify_beta_candidate behavior self-test"
Write-Host "---------------------------------------"
Write-Host ("Cases: total={0}, passed={1}, failed={2}" -f $report.total_cases, $report.passed_cases, $report.failed_cases)
Write-Host ("Session: {0}" -f $report.session_dir)
Write-Host ""
@($resultArray) | Select-Object name, passed, did_throw, overall_status, issue_count, warning_count, output_count | Format-Table -AutoSize
Write-Host ""
Write-Host ("Report: {0}" -f (Resolve-Path -LiteralPath $reportPath).Path)

if ($PassThru) {
    Write-Output $report
}

if ($report.failed_cases -gt 0) {
    throw ("verify_beta_candidate behavior self-test failed ({0}/{1} failed)" -f $report.failed_cases, $report.total_cases)
}
