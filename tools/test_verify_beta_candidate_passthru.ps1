param(
    [string]$VerifyScriptPath = "",
    [string]$CandidateDir = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($VerifyScriptPath)) {
    $VerifyScriptPath = Join-Path $PSScriptRoot "verify_beta_candidate.ps1"
}
if (-not (Test-Path -LiteralPath $VerifyScriptPath -PathType Leaf)) {
    throw "verify_beta_candidate script not found: $VerifyScriptPath"
}

function Resolve-CandidateDirectory {
    param([string]$CandidatePathHint)

    if (-not [string]::IsNullOrWhiteSpace($CandidatePathHint)) {
        if (-not (Test-Path -LiteralPath $CandidatePathHint -PathType Container)) {
            throw "Candidate directory not found: $CandidatePathHint"
        }
        return (Resolve-Path -LiteralPath $CandidatePathHint).Path
    }

    $repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
    $betaCandidatesRoot = Join-Path $repoRoot "run\beta_candidates"
    if (-not (Test-Path -LiteralPath $betaCandidatesRoot -PathType Container)) {
        throw "Beta candidates directory not found: $betaCandidatesRoot"
    }

    $latestCandidate = Get-ChildItem -LiteralPath $betaCandidatesRoot -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "BETA_CANDIDATE.md") -PathType Leaf } |
        Sort-Object Name -Descending |
        Select-Object -First 1

    if ($null -eq $latestCandidate) {
        throw "No candidate directory with BETA_CANDIDATE.md found under $betaCandidatesRoot"
    }

    return $latestCandidate.FullName
}

$resolvedCandidateDir = Resolve-CandidateDirectory -CandidatePathHint $CandidateDir
$pipelineItems = @(& $VerifyScriptPath -CandidateDir $resolvedCandidateDir -PassThru -SuppressConsoleSummary)
$checks = New-Object System.Collections.Generic.List[string]

if ($pipelineItems.Count -ne 1) {
    $checks.Add(("unexpected_pipeline_output_count:{0}" -f $pipelineItems.Count))
}

$formatItems = @(
    $pipelineItems |
        Where-Object {
            $null -ne $_ -and
            $_.GetType().FullName -like "Microsoft.PowerShell.Commands.Internal.Format.*"
        }
)
if ($formatItems.Count -gt 0) {
    $checks.Add(("format_objects_leaked_to_pipeline:{0}" -f $formatItems.Count))
}

$result = if ($pipelineItems.Count -gt 0) { $pipelineItems[$pipelineItems.Count - 1] } else { $null }
if ($null -eq $result) {
    $checks.Add("missing_passthru_result_object")
}

$requiredProperties = @(
    "timestamp_utc",
    "candidate_dir",
    "jar_count",
    "preflight_report_count",
    "issue_count",
    "warning_count",
    "issues_list",
    "warnings_list",
    "issues",
    "warnings",
    "overall_status"
)

if ($null -ne $result) {
    foreach ($propertyName in $requiredProperties) {
        if ($null -eq $result.PSObject.Properties[$propertyName]) {
            $checks.Add(("missing_property:{0}" -f $propertyName))
        }
    }

    $statusValue = [string]$result.overall_status
    if (@("pass", "warn", "fail") -notcontains $statusValue) {
        $checks.Add(("unexpected_overall_status:{0}" -f $statusValue))
    }

    $issueList = @($result.issues_list)
    $warningList = @($result.warnings_list)
    if ([int]$result.issue_count -ne $issueList.Count) {
        $checks.Add(("issue_count_mismatch:{0}!={1}" -f [int]$result.issue_count, $issueList.Count))
    }
    if ([int]$result.warning_count -ne $warningList.Count) {
        $checks.Add(("warning_count_mismatch:{0}!={1}" -f [int]$result.warning_count, $warningList.Count))
    }
}

try {
    $jsonPayload = $pipelineItems | ConvertTo-Json -Depth 8 -ErrorAction Stop
    $parsedJson = $jsonPayload | ConvertFrom-Json -ErrorAction Stop
    $parsedHasStatus = $false
    if ($parsedJson -is [System.Array]) {
        if ($parsedJson.Count -eq 1 -and $null -ne $parsedJson[0].PSObject.Properties["overall_status"]) {
            $parsedHasStatus = $true
        }
    } elseif ($null -ne $parsedJson) {
        if ($null -ne $parsedJson.PSObject.Properties["overall_status"]) {
            $parsedHasStatus = $true
        }
    }
    if (-not $parsedHasStatus) {
        $checks.Add("json_roundtrip_missing_overall_status")
    }
} catch {
    $checks.Add(("json_roundtrip_error:{0}" -f $_.Exception.Message))
}

$passed = ($checks.Count -eq 0)

Write-Host ""
Write-Host "verify_beta_candidate passthru self-test"
Write-Host "---------------------------------------"
Write-Host ("candidate_dir: {0}" -f $resolvedCandidateDir)
Write-Host ("pipeline_output_count: {0}" -f $pipelineItems.Count)
Write-Host ("status: {0}" -f $(if ($passed) { "pass" } else { "fail" }))

if (-not $passed) {
    Write-Host "checks:"
    foreach ($item in $checks) {
        Write-Host ("- {0}" -f $item)
    }
    throw ("verify_beta_candidate passthru self-test failed ({0} checks)" -f $checks.Count)
}
