param(
    [Parameter(Mandatory = $true)][string]$CandidateDir,
    [switch]$SkipChecksumValidation,
    [switch]$RequireExtendedArtifacts,
    [switch]$FailOnIssues,
    [switch]$PassThru,
    [switch]$SuppressConsoleSummary
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-CandidatePath {
    param([string]$PathValue)
    if (-not (Test-Path -LiteralPath $PathValue)) {
        throw "Candidate directory not found: $PathValue"
    }
    $resolved = Resolve-Path -LiteralPath $PathValue
    if (-not (Test-Path -LiteralPath $resolved.Path -PathType Container)) {
        throw "Candidate path is not a directory: $PathValue"
    }
    return $resolved.Path
}

function Parse-ChecksumsFile {
    param([string]$ChecksumsPath)
    $map = @{}
    if (-not (Test-Path -LiteralPath $ChecksumsPath)) {
        return $map
    }
    foreach ($line in Get-Content -LiteralPath $ChecksumsPath) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        if ($line -match '^([A-Fa-f0-9]{64}) \*(.+)$') {
            $relative = $Matches[2].Trim()
            $map[$relative] = $Matches[1].ToUpperInvariant()
        }
    }
    return $map
}

$candidatePath = Resolve-CandidatePath -PathValue $CandidateDir
$issues = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]

$manifestMdPath = Join-Path $candidatePath "BETA_CANDIDATE.md"
$readinessJsonPath = Join-Path $candidatePath "beta_readiness.json"
$checksumsPath = Join-Path $candidatePath "SHA256SUMS.txt"
$manifestJsonPath = Join-Path $candidatePath "candidate_manifest.json"
$profilesDirPath = Join-Path $candidatePath "profiles"

if (-not (Test-Path -LiteralPath $manifestMdPath)) {
    $issues.Add("missing BETA_CANDIDATE.md")
}
if (-not (Test-Path -LiteralPath $readinessJsonPath)) {
    $issues.Add("missing beta_readiness.json")
}

$jarFiles = @(Get-ChildItem -LiteralPath $candidatePath -File -Filter "*.jar" | Sort-Object Name)
if ($jarFiles.Count -eq 0) {
    $issues.Add("no jar artifact found in candidate directory")
}

$preflightReports = @(Get-ChildItem -LiteralPath $candidatePath -File -Filter "phase6_preflight_*.md")
if ($preflightReports.Count -eq 0) {
    $issues.Add("no phase6 preflight report found in candidate directory")
}

$readiness = $null
if (Test-Path -LiteralPath $readinessJsonPath) {
    try {
        $readiness = Get-Content -LiteralPath $readinessJsonPath -Raw | ConvertFrom-Json
        if ($null -eq $readiness.decision) {
            $issues.Add("beta_readiness.json missing decision")
        }
        if ($null -eq $readiness.readiness_percent) {
            $issues.Add("beta_readiness.json missing readiness_percent")
        }
    } catch {
        $issues.Add(("beta_readiness.json parse error: {0}" -f $_.Exception.Message))
    }
}

$manifestJson = $null
if (Test-Path -LiteralPath $manifestJsonPath) {
    try {
        $manifestJson = Get-Content -LiteralPath $manifestJsonPath -Raw | ConvertFrom-Json
    } catch {
        $issues.Add(("candidate_manifest.json parse error: {0}" -f $_.Exception.Message))
    }
} elseif ($RequireExtendedArtifacts) {
    $issues.Add("missing candidate_manifest.json")
}

if ($RequireExtendedArtifacts -and -not (Test-Path -LiteralPath $checksumsPath)) {
    $issues.Add("missing SHA256SUMS.txt")
}

if ($RequireExtendedArtifacts -and -not (Test-Path -LiteralPath $profilesDirPath -PathType Container)) {
    $issues.Add("missing profiles directory")
}

if ((Test-Path -LiteralPath $checksumsPath) -and -not $SkipChecksumValidation) {
    $checksumMap = Parse-ChecksumsFile -ChecksumsPath $checksumsPath
    if ($checksumMap.Count -eq 0) {
        $issues.Add("SHA256SUMS.txt has no valid checksum entries")
    } else {
        foreach ($entry in $checksumMap.GetEnumerator()) {
            $relativePath = $entry.Key
            $expectedHash = $entry.Value
            $targetPath = Join-Path $candidatePath $relativePath
            if (-not (Test-Path -LiteralPath $targetPath -PathType Leaf)) {
                $issues.Add(("checksum target missing: {0}" -f $relativePath))
                continue
            }
            $actualHash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash.ToUpperInvariant()
            if ($actualHash -ne $expectedHash) {
                $issues.Add(("checksum mismatch: {0}" -f $relativePath))
            }
        }
    }
} elseif ($RequireExtendedArtifacts -and -not $SkipChecksumValidation) {
    $issues.Add("checksum validation requested but SHA256SUMS.txt is missing")
}

if ($null -ne $manifestJson) {
    $manifestJarName = [string]$manifestJson.jar_name
    $manifestJarHash = [string]$manifestJson.jar_sha256
    $manifestJarFile = $null

    if (-not [string]::IsNullOrWhiteSpace($manifestJarName)) {
        $manifestJarFile = $jarFiles | Where-Object { [string]$_.Name -eq $manifestJarName } | Select-Object -First 1
        if ($null -eq $manifestJarFile) {
            $issues.Add(("candidate_manifest.json jar_name not found in candidate directory ({0})" -f $manifestJarName))
        }
    } elseif ($jarFiles.Count -eq 1) {
        $manifestJarFile = $jarFiles[0]
    }

    if ($null -ne $readiness) {
        if ([string]$manifestJson.readiness_decision -ne [string]$readiness.decision) {
            $issues.Add("candidate_manifest.json readiness_decision differs from beta_readiness.json decision")
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($manifestJarHash) -and $jarFiles.Count -gt 0) {
        if ($null -eq $manifestJarFile) {
            if ($jarFiles.Count -gt 1) {
                $issues.Add("candidate_manifest.json jar_sha256 cannot be validated without jar_name when multiple jar files are present")
            }
        } else {
            $jarHash = (Get-FileHash -LiteralPath $manifestJarFile.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
            if ($manifestJarHash.ToUpperInvariant() -ne $jarHash) {
                $issues.Add("candidate_manifest.json jar_sha256 mismatch")
            }
        }
    }
}

if ($jarFiles.Count -gt 1) {
    $warnings.Add("multiple jar files found in candidate directory")
}
if ($preflightReports.Count -gt 1) {
    $warnings.Add("multiple preflight reports found in candidate directory")
}

$overallStatus = if ($issues.Count -gt 0) { "fail" } elseif ($warnings.Count -gt 0) { "warn" } else { "pass" }
$issuesArray = @($issues.ToArray())
$warningsArray = @($warnings.ToArray())
$result = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    candidate_dir = $candidatePath
    jar_count = $jarFiles.Count
    preflight_report_count = $preflightReports.Count
    issue_count = $issuesArray.Count
    warning_count = $warningsArray.Count
    issues_list = $issuesArray
    warnings_list = $warningsArray
    issues = ($issuesArray -join "; ")
    warnings = ($warningsArray -join "; ")
    overall_status = $overallStatus
}

if (-not $SuppressConsoleSummary) {
    Write-Host ""
    Write-Host "PauC beta candidate verification"
    Write-Host "--------------------------------"
    $result | Format-List | Out-Host
}

if ($FailOnIssues -and $overallStatus -ne "pass") {
    throw ("Candidate verification failed: status={0}; issues={1}; warnings={2}" -f $overallStatus, $result.issues, $result.warnings)
}

if ($PassThru) {
    Write-Output $result
}
