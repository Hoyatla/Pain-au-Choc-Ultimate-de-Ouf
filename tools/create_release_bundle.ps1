param(
    [string]$CandidateDir = "",
    [string]$ReportsDir = ".\run\pauc_reports",
    [string]$OutRoot = ".\run\releases",
    [string]$InstanceName = "test",
    [string]$PrismInstancesRoot = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LatestFileByPattern {
    param(
        [string]$Dir,
        [string]$Pattern
    )
    $items = @(Get-ChildItem -LiteralPath $Dir -File -Filter $Pattern -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending)
    if ($items.Count -eq 0) {
        return $null
    }
    return $items[0]
}

function Add-FileSha256Lines {
    param(
        [string]$BaseDir,
        [string]$OutputPath
    )

    $resolvedBaseDir = (Resolve-Path -LiteralPath $BaseDir).Path
    $lines = New-Object System.Collections.Generic.List[string]
    $files = @(Get-ChildItem -LiteralPath $resolvedBaseDir -Recurse -File | Sort-Object FullName)
    foreach ($file in $files) {
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash
        $relative = $file.FullName.Substring($resolvedBaseDir.Length).TrimStart('\', '/')
        $lines.Add(("{0} *{1}" -f $hash, $relative))
    }
    $lines | Set-Content -LiteralPath $OutputPath -Encoding UTF8
}

if ([string]::IsNullOrWhiteSpace($PrismInstancesRoot)) {
    $PrismInstancesRoot = Join-Path $env:APPDATA "PrismLauncher\instances"
}

if (-not (Test-Path -LiteralPath $ReportsDir)) {
    throw "ReportsDir not found: $ReportsDir"
}

if ([string]::IsNullOrWhiteSpace($CandidateDir)) {
    $candidateRoot = ".\run\beta_candidates"
    if (-not (Test-Path -LiteralPath $candidateRoot)) {
        throw "Candidate root not found: $candidateRoot"
    }
    $latestCandidate = Get-ChildItem -LiteralPath $candidateRoot -Directory |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $latestCandidate) {
        throw "No candidate found under: $candidateRoot"
    }
    $CandidateDir = $latestCandidate.FullName
}

$resolvedCandidate = (Resolve-Path -LiteralPath $CandidateDir).Path
$candidateName = Split-Path -Leaf $resolvedCandidate

if (-not (Test-Path -LiteralPath $OutRoot)) {
    New-Item -ItemType Directory -Path $OutRoot -Force | Out-Null
}

$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd_HHmmss_fff")
$bundleName = "pauc_release_{0}" -f $stamp
$bundleDir = Join-Path $OutRoot $bundleName
$bundleCandidateDir = Join-Path $bundleDir "candidate"
$bundleReportsDir = Join-Path $bundleDir "reports"
New-Item -ItemType Directory -Path $bundleCandidateDir -Force | Out-Null
New-Item -ItemType Directory -Path $bundleReportsDir -Force | Out-Null

Copy-Item -Path (Join-Path $resolvedCandidate "*") -Destination $bundleCandidateDir -Recurse -Force

$triageMd = Get-LatestFileByPattern -Dir $ReportsDir -Pattern "modpack_error_triage_*.md"
$triageJson = Get-LatestFileByPattern -Dir $ReportsDir -Pattern "modpack_error_triage_*.json"
$errorMd = Get-LatestFileByPattern -Dir $ReportsDir -Pattern "error_sorting_pass_*.md"
$errorJson = Get-LatestFileByPattern -Dir $ReportsDir -Pattern "error_sorting_pass_*.json"
$v3Md = Get-LatestFileByPattern -Dir $ReportsDir -Pattern "v3_hardware_driver_matrix_*.md"
$v3Json = Get-LatestFileByPattern -Dir $ReportsDir -Pattern "v3_hardware_driver_matrix_*.json"

$autopilot = $null
$autopilotPatterns = @(
    "autopilot_summary_live_strict_recheck_after_soak_attempt_*.json",
    "autopilot_summary_live_strict_after_world_start_*.json",
    "autopilot_summary*.json"
)
foreach ($pattern in $autopilotPatterns) {
    $candidate = Get-LatestFileByPattern -Dir $ReportsDir -Pattern $pattern
    if ($null -ne $candidate) {
        $autopilot = $candidate
        break
    }
}

$reportsToCopy = @($triageMd, $triageJson, $errorMd, $errorJson, $v3Md, $v3Json, $autopilot) | Where-Object { $null -ne $_ }
foreach ($report in $reportsToCopy) {
    Copy-Item -LiteralPath $report.FullName -Destination (Join-Path $bundleReportsDir $report.Name) -Force
}

$manifestPath = Join-Path $bundleCandidateDir "candidate_manifest.json"
$readinessPath = Join-Path $bundleCandidateDir "beta_readiness.json"
if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "Candidate manifest missing in bundle candidate: $manifestPath"
}
if (-not (Test-Path -LiteralPath $readinessPath)) {
    throw "beta_readiness missing in bundle candidate: $readinessPath"
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$readiness = Get-Content -LiteralPath $readinessPath -Raw | ConvertFrom-Json

$errorSummary = $null
if ($null -ne $errorJson) {
    $errorSummary = Get-Content -LiteralPath (Join-Path $bundleReportsDir $errorJson.Name) -Raw | ConvertFrom-Json
}

$v3Summary = $null
if ($null -ne $v3Json) {
    $v3Summary = Get-Content -LiteralPath (Join-Path $bundleReportsDir $v3Json.Name) -Raw | ConvertFrom-Json
}

$autopilotSummary = $null
if ($null -ne $autopilot) {
    $autopilotSummary = Get-Content -LiteralPath (Join-Path $bundleReportsDir $autopilot.Name) -Raw | ConvertFrom-Json
}

$instanceJarPath = Join-Path (Join-Path (Join-Path $PrismInstancesRoot $InstanceName) "minecraft\mods") ([string]$manifest.jar_name)
$instanceJarHash = ""
if (Test-Path -LiteralPath $instanceJarPath) {
    $instanceJarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $instanceJarPath).Hash
}

$waiversText = "none"
if ($null -ne $v3Summary -and $null -ne $v3Summary.waivers -and $v3Summary.waivers.Count -gt 0) {
    $waiversText = ($v3Summary.waivers -join ", ")
}

$strictLiveStatus = "unknown"
$strictLiveDecision = ""
$strictLiveFailGates = ""
if ($null -ne $autopilotSummary) {
    $strictLiveStatus = if ([bool]$autopilotSummary.autopilot_failed) { "fail" } else { "pass" }
    $strictLiveDecision = [string]$autopilotSummary.effective_decision
    $strictLiveFailGates = [string]$autopilotSummary.triggered_fail_gate_count
}

$releaseNotesLines = New-Object System.Collections.Generic.List[string]
$releaseNotesLines.Add("# PauC Release Bundle")
$releaseNotesLines.Add("")
$releaseNotesLines.Add(("- Timestamp UTC: {0}" -f (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")))
$releaseNotesLines.Add(('- Version jar: `{0}`' -f [string]$manifest.jar_name))
$releaseNotesLines.Add(('- Candidate source: `run/beta_candidates/{0}`' -f $candidateName))
$releaseNotesLines.Add(('- Decision: `{0}` (`{1}%`)' -f [string]$manifest.readiness_decision, [int]$manifest.readiness_percent))
$releaseNotesLines.Add(('- Instance cible: `PrismLauncher/instances/{0}`' -f $InstanceName))
$releaseNotesLines.Add("")
$releaseNotesLines.Add("## Validation Summary")
$releaseNotesLines.Add("")
$releaseNotesLines.Add(('- Preflight Phase 6: `{0}`' -f [string]$readiness.decision))
$releaseNotesLines.Add(('- KPI gate: `{0}`' -f [string]$readiness.kpi_gate))
$releaseNotesLines.Add(('- A/B audit: `{0}`' -f [string]$readiness.ab_audit))
$releaseNotesLines.Add(('- A/B progress: `{0}` (`{1}%`)' -f [string]$readiness.ab_progress, [string]$readiness.ab_completion_percent))
$releaseNotesLines.Add(('- Server governor: `{0}`' -f [string]$readiness.server_governor_health))
if ($null -ne $errorSummary) {
    $releaseNotesLines.Add(('- Error sorting pass: `{0}` (`blocking_hits_total={1}`, `known_noise_hits_total={2}`)' -f [string]$errorSummary.overall_status, [string]$errorSummary.blocking_hits_total, [string]$errorSummary.known_noise_hits_total))
}
if ($null -ne $v3Summary) {
    $releaseNotesLines.Add(('- V3 hardware/drivers: `{0}`' -f [string]$v3Summary.overall_status))
    $releaseNotesLines.Add(('  - waivers: `{0}`' -f $waiversText))
}
$releaseNotesLines.Add(('- Strict live autopilot: `{0}` (`effective_decision={1}`, `fail_gates={2}`)' -f $strictLiveStatus, $strictLiveDecision, $strictLiveFailGates))
$releaseNotesLines.Add("")
$releaseNotesLines.Add("## Integrity")
$releaseNotesLines.Add("")
$releaseNotesLines.Add(('- Candidate jar SHA-256: `{0}`' -f [string]$manifest.jar_sha256))
$releaseNotesLines.Add(('- Instance jar SHA-256: `{0}`' -f $instanceJarHash))
$releaseNotesLines.Add("")
$releaseNotesLines.Add("## Included Content")
$releaseNotesLines.Add("")
$releaseNotesLines.Add('- `candidate/*` (jar + manifests + checksums + profiles + preflight)')
foreach ($report in $reportsToCopy) {
    $releaseNotesLines.Add(('- `reports/{0}`' -f $report.Name))
}

$releaseNotesPath = Join-Path $bundleDir "RELEASE_NOTES.md"
$releaseNotesLines | Set-Content -LiteralPath $releaseNotesPath -Encoding UTF8

$shaPath = Join-Path $bundleDir "SHA256SUMS.txt"
Add-FileSha256Lines -BaseDir $bundleDir -OutputPath $shaPath

$zipPath = Join-Path $OutRoot ("{0}.zip" -f $bundleName)
if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}
Compress-Archive -Path (Join-Path $bundleDir "*") -DestinationPath $zipPath -Force

$zipHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipPath).Hash
$zipHashPath = Join-Path $OutRoot ("{0}.zip.sha256.txt" -f $bundleName)
("{0} *{1}" -f $zipHash, ("{0}.zip" -f $bundleName)) | Set-Content -LiteralPath $zipHashPath -Encoding UTF8

$summary = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    bundle_dir = (Resolve-Path -LiteralPath $bundleDir).Path
    zip_path = (Resolve-Path -LiteralPath $zipPath).Path
    zip_sha256 = $zipHash
    release_notes_path = (Resolve-Path -LiteralPath $releaseNotesPath).Path
    checksums_path = (Resolve-Path -LiteralPath $shaPath).Path
    candidate_dir = $resolvedCandidate
    candidate_name = $candidateName
    included_reports = @($reportsToCopy | ForEach-Object { $_.Name })
}

Write-Host ""
Write-Host "PauC release bundle"
Write-Host "-------------------"
$summary | Format-List
Write-Host ""
Write-Host ("ZIP sha256 file: {0}" -f (Resolve-Path -LiteralPath $zipHashPath).Path)

if ($PassThru) {
    $summary
}
