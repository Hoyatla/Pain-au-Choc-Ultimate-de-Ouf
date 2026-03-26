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
    if ([bool]$result.autopilot_executed) {
        Add-CaseCheck -Checks $Checks -CaseName $CaseName -Message "autopilot_executed_should_be_false"
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
        })
}

$smokeInvocationError = ""
$smokePipelineItems = @()
$copyInvocationError = ""
$copyPipelineItems = @()
$fixturePrismRoot = Join-Path $sessionDir "prism_fixture"
$fixtureInstanceName = "ci_capture_auto_dot"
$fixtureMinecraftDir = Join-Path (Join-Path $fixturePrismRoot $fixtureInstanceName) ".minecraft"
New-Item -ItemType Directory -Path $fixtureMinecraftDir -Force | Out-Null
$expectedDeployedJarPath = Join-Path (Join-Path $fixtureMinecraftDir "mods") (Split-Path -Path $resolvedJarPath -Leaf)

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
                -PassThru
        )
    } catch {
        $copyInvocationError = [string]$_.Exception.Message
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

Add-ResultChecks `
    -CaseName "smoke_no_copy" `
    -PipelineItems $smokePipelineItems `
    -ExpectedJarPath $resolvedJarPath `
    -RequiredProperties $requiredProperties `
    -ExpectJarCopy $false `
    -ExpectedJarDeployedPath "" `
    -Checks $checks

Add-ResultChecks `
    -CaseName "copy_dot_minecraft" `
    -PipelineItems $copyPipelineItems `
    -ExpectedJarPath $resolvedJarPath `
    -RequiredProperties $requiredProperties `
    -ExpectJarCopy $true `
    -ExpectedJarDeployedPath $expectedDeployedJarPath `
    -Checks $checks

$passed = ($checks.Count -eq 0)
$report = [PSCustomObject]@{
    generated_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    script_path = (Resolve-Path -LiteralPath $PipelineScriptPath).Path
    metrics_path = $resolvedMetricsPath
    jar_path = $resolvedJarPath
    smoke_pipeline_output_count = $smokePipelineItems.Count
    copy_pipeline_output_count = $copyPipelineItems.Count
    fixture_prism_root = $fixturePrismRoot
    fixture_instance_name = $fixtureInstanceName
    fixture_expected_jar_deployed_path = $expectedDeployedJarPath
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
