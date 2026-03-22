param(
    [string]$InstanceName = "test",
    [string]$PrismInstancesRoot = "",
    [string[]]$LogPaths = @(),
    [string]$OutDir = ".\run\pauc_reports",
    [int]$TopN = 25,
    [switch]$IncludeWarnings,
    [string[]]$BlockingPatterns = @(
        "Parsing error loading recipe",
        "Invalid or unsupported recipe type",
        "Couldn't parse element loot_tables",
        "Expected name to be an item, was unknown string"
    ),
    [string[]]$KnownNoisePatterns = @(
        "Hanging entity at invalid position",
        "Can't keep up! Is the server overloaded?",
        "OpenGL debug message: id="
    ),
    [bool]$RunQuarantine = $true,
    [switch]$FailOnBlocking,
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($TopN -lt 1) {
    throw "TopN must be >= 1"
}

if ([string]::IsNullOrWhiteSpace($PrismInstancesRoot)) {
    $PrismInstancesRoot = Join-Path $env:APPDATA "PrismLauncher\instances"
}

if ($LogPaths.Count -eq 0) {
    $logsRoot = Join-Path $PrismInstancesRoot "$InstanceName\minecraft\logs"
    $LogPaths = @(
        (Join-Path $logsRoot "latest.log"),
        (Join-Path $logsRoot "debug.log")
    )
}

$resolvedLogs = @(
    $LogPaths |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { $_.Trim() } |
        Select-Object -Unique
)

$missingLogs = @($resolvedLogs | Where-Object { -not (Test-Path -LiteralPath $_) })
if ($missingLogs.Count -gt 0) {
    throw ("Missing log files: {0}" -f ($missingLogs -join ", "))
}

if (-not (Test-Path -LiteralPath $OutDir)) {
    New-Item -Path $OutDir -ItemType Directory -Force | Out-Null
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

function Get-PatternStats {
    param(
        [string[]]$Patterns,
        [string[]]$Paths,
        [int]$SampleLimit = 3
    )

    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($pattern in $Patterns) {
        if ([string]::IsNullOrWhiteSpace($pattern)) {
            continue
        }

        $totalCount = 0
        $files = New-Object System.Collections.Generic.List[string]
        $samples = New-Object System.Collections.Generic.List[string]
        foreach ($path in $Paths) {
            $matches = @(Select-String -LiteralPath $path -Pattern $pattern -SimpleMatch -ErrorAction SilentlyContinue)
            if ($matches.Count -eq 0) {
                continue
            }

            $totalCount += $matches.Count
            $files.Add([System.IO.Path]::GetFileName($path))
            foreach ($match in $matches) {
                if ($samples.Count -ge $SampleLimit) {
                    break
                }
                $samples.Add(("{0}:{1}" -f [System.IO.Path]::GetFileName($path), $match.LineNumber))
            }
        }

        $rows.Add([PSCustomObject]@{
                pattern = $pattern
                count = $totalCount
                files = @($files | Select-Object -Unique)
                sample_refs = @($samples | Select-Object -Unique)
            })
    }

    return $rows.ToArray()
}

function Resolve-TriageArtifactPaths {
    param([string]$TopCsvPath)

    $result = [PSCustomObject]@{
        top_csv_path = $TopCsvPath
        report_md_path = ""
        report_json_path = ""
    }
    if ([string]::IsNullOrWhiteSpace($TopCsvPath) -or -not (Test-Path -LiteralPath $TopCsvPath)) {
        return $result
    }

    $topCsvName = [System.IO.Path]::GetFileName($TopCsvPath)
    $dir = Split-Path -Parent $TopCsvPath
    if ($topCsvName -match "^modpack_error_triage_top_(.+)\.csv$") {
        $suffix = $matches[1]
        $candidateMd = Join-Path $dir ("modpack_error_triage_{0}.md" -f $suffix)
        $candidateJson = Join-Path $dir ("modpack_error_triage_{0}.json" -f $suffix)
        if (Test-Path -LiteralPath $candidateMd) {
            $result.report_md_path = (Resolve-Path -LiteralPath $candidateMd).Path
        }
        if (Test-Path -LiteralPath $candidateJson) {
            $result.report_json_path = (Resolve-Path -LiteralPath $candidateJson).Path
        }
    }
    return $result
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$triageScript = Join-Path $scriptRoot "triage_modpack_errors.ps1"
$quarantineScript = Join-Path $scriptRoot "quarantine_modpack_data_errors.ps1"

if (-not (Test-Path -LiteralPath $triageScript)) {
    throw "Missing script: $triageScript"
}
if ($RunQuarantine -and -not (Test-Path -LiteralPath $quarantineScript)) {
    throw "Missing script: $quarantineScript"
}

$triageArgs = @{
    InstanceName = $InstanceName
    PrismInstancesRoot = $PrismInstancesRoot
    LogPaths = $resolvedLogs
    TopN = $TopN
    OutDir = $OutDir
    PassThru = $true
}
if ($IncludeWarnings) {
    $triageArgs.IncludeWarnings = $true
}

$triageRaw = & $triageScript @triageArgs
$triageSummary = Get-LastOutputObject -Value $triageRaw
if ($null -eq $triageSummary) {
    throw "triage_modpack_errors.ps1 returned no summary object"
}

$triageArtifacts = Resolve-TriageArtifactPaths -TopCsvPath ([string]$triageSummary.top_csv_path)
$blockingRows = @(Get-PatternStats -Patterns $BlockingPatterns -Paths $resolvedLogs)
$noiseRows = @(Get-PatternStats -Patterns $KnownNoisePatterns -Paths $resolvedLogs)

$blockingHitsTotal = [int](($blockingRows | Measure-Object -Property count -Sum).Sum)
$noiseHitsTotal = [int](($noiseRows | Measure-Object -Property count -Sum).Sum)
$blockingTriggered = @($blockingRows | Where-Object { $_.count -gt 0 })
$noiseTriggered = @($noiseRows | Where-Object { $_.count -gt 0 })

$quarantineSummary = $null
if ($RunQuarantine) {
    $quarantineArgs = @{
        InstanceName = $InstanceName
        PrismInstancesRoot = $PrismInstancesRoot
        LogPaths = $resolvedLogs
        OutDir = $OutDir
        AdoptExisting = $true
        PassThru = $true
    }
    $quarantineRaw = & $quarantineScript @quarantineArgs
    $quarantineSummary = Get-LastOutputObject -Value $quarantineRaw
}

$overallStatus = if ($blockingHitsTotal -eq 0) { "pass" } else { "fail" }
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd_HHmmss_fff")
$jsonPath = Join-Path $OutDir ("error_sorting_pass_{0}.json" -f $stamp)
$mdPath = Join-Path $OutDir ("error_sorting_pass_{0}.md" -f $stamp)

$summary = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    instance_name = $InstanceName
    logs = $resolvedLogs
    include_warnings = [bool]$IncludeWarnings
    report_json_path = [System.IO.Path]::GetFullPath($jsonPath)
    report_md_path = [System.IO.Path]::GetFullPath($mdPath)
    top_n = $TopN
    triage_total_events = [int]$triageSummary.total_events
    triage_unique_signatures = [int]$triageSummary.unique_signatures
    triage_top_csv_path = [string]$triageArtifacts.top_csv_path
    triage_report_md_path = [string]$triageArtifacts.report_md_path
    triage_report_json_path = [string]$triageArtifacts.report_json_path
    blocking_pattern_count = $blockingRows.Count
    blocking_patterns_triggered = $blockingTriggered.Count
    blocking_hits_total = $blockingHitsTotal
    blocking_patterns = $blockingRows
    known_noise_pattern_count = $noiseRows.Count
    known_noise_patterns_triggered = $noiseTriggered.Count
    known_noise_hits_total = $noiseHitsTotal
    known_noise_patterns = $noiseRows
    quarantine_ran = [bool]$RunQuarantine
    quarantine_created_count = if ($null -eq $quarantineSummary) { 0 } else { [int]$quarantineSummary.created_count }
    quarantine_adopted_count = if ($null -eq $quarantineSummary) { 0 } else { [int]$quarantineSummary.adopted_count }
    quarantine_manifest_path = if ($null -eq $quarantineSummary) { "" } else { [string]$quarantineSummary.manifest_path }
    overall_status = $overallStatus
}

$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$md = New-Object System.Collections.Generic.List[string]
$md.Add("# Error Sorting Pass")
$md.Add("")
$md.Add(("- Timestamp UTC: {0}" -f $summary.timestamp_utc))
$md.Add(("- Instance: {0}" -f $summary.instance_name))
$md.Add(("- Logs: {0}" -f ($summary.logs -join " | ")))
$md.Add(("- Overall status: {0}" -f $summary.overall_status))
$md.Add(("- Blocking hits total: {0}" -f $summary.blocking_hits_total))
$md.Add(("- Known-noise hits total: {0}" -f $summary.known_noise_hits_total))
$md.Add("")
$md.Add("## Triage")
$md.Add("")
$md.Add(("- Total events: {0}" -f $summary.triage_total_events))
$md.Add(("- Unique signatures: {0}" -f $summary.triage_unique_signatures))
$md.Add(("- Top CSV: {0}" -f $summary.triage_top_csv_path))
if (-not [string]::IsNullOrWhiteSpace($summary.triage_report_md_path)) {
    $md.Add(("- Report MD: {0}" -f $summary.triage_report_md_path))
}
if (-not [string]::IsNullOrWhiteSpace($summary.triage_report_json_path)) {
    $md.Add(("- Report JSON: {0}" -f $summary.triage_report_json_path))
}
$md.Add("")
$md.Add("## Blocking Patterns")
$md.Add("")
$md.Add("| Pattern | Count | Files | Samples |")
$md.Add("|---|---:|---|---|")
foreach ($row in $blockingRows) {
    $files = if ($row.files.Count -eq 0) { "-" } else { ($row.files -join ", ") }
    $samples = if ($row.sample_refs.Count -eq 0) { "-" } else { ($row.sample_refs -join ", ") }
    $patternValue = ([string]$row.pattern).Replace("|", "\|")
    $md.Add(("| {0} | {1} | {2} | {3} |" -f $patternValue, $row.count, $files, $samples))
}
$md.Add("")
$md.Add("## Known Noise Patterns")
$md.Add("")
$md.Add("| Pattern | Count | Files | Samples |")
$md.Add("|---|---:|---|---|")
foreach ($row in $noiseRows) {
    $files = if ($row.files.Count -eq 0) { "-" } else { ($row.files -join ", ") }
    $samples = if ($row.sample_refs.Count -eq 0) { "-" } else { ($row.sample_refs -join ", ") }
    $patternValue = ([string]$row.pattern).Replace("|", "\|")
    $md.Add(("| {0} | {1} | {2} | {3} |" -f $patternValue, $row.count, $files, $samples))
}
$md.Add("")
$md.Add("## Quarantine")
$md.Add("")
$md.Add(("- Ran: {0}" -f [bool]$summary.quarantine_ran))
$md.Add(("- Created count: {0}" -f $summary.quarantine_created_count))
$md.Add(("- Adopted count: {0}" -f $summary.quarantine_adopted_count))
if (-not [string]::IsNullOrWhiteSpace($summary.quarantine_manifest_path)) {
    $md.Add(("- Manifest: {0}" -f $summary.quarantine_manifest_path))
}
$md.Add("")
$md.Add(("- JSON: {0}" -f (Resolve-Path -LiteralPath $jsonPath).Path))
$md | Set-Content -LiteralPath $mdPath -Encoding UTF8

Write-Host ""
Write-Host "PauC error sorting pass"
Write-Host "-----------------------"
Write-Host ""
$summary | Format-List
Write-Host ""
Write-Host ("Report MD:  {0}" -f (Resolve-Path -LiteralPath $mdPath).Path)
Write-Host ("Report JSON:{0}" -f (Resolve-Path -LiteralPath $jsonPath).Path)

if ($PassThru) {
    $summary
}

if ($FailOnBlocking -and $overallStatus -ne "pass") {
    exit 2
}
