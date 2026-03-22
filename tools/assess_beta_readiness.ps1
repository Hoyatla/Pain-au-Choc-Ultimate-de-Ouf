param(
    [string]$ReportPath = "",
    [string]$ReportsDir = ".\run\pauc_reports",
    [int]$MinReadinessPercent = 80,
    [double]$SkippedWeightFactor = 0.5,
    [bool]$TreatServerGovernorSkipAsPassWhenInsufficientPressure = $true,
    [string[]]$BlockingGateKeys = @("compile", "kpi_gate", "soak_stability", "ab_audit", "ab_progress"),
    [switch]$DisableBlockingGates,
    [string]$OutJsonPath = "",
    [switch]$PassThru,
    [switch]$FailBelowThreshold
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($MinReadinessPercent -lt 0 -or $MinReadinessPercent -gt 100) {
    throw "MinReadinessPercent must be between 0 and 100"
}
if ($SkippedWeightFactor -lt 0.0 -or $SkippedWeightFactor -gt 1.0) {
    throw "SkippedWeightFactor must be between 0.0 and 1.0"
}

function Extract-Status {
    param(
        [string[]]$Lines,
        [string]$Label
    )
    $prefix = "- ${Label}:"
    $line = $Lines | Where-Object { $_.StartsWith($prefix) } | Select-Object -First 1
    if ($null -eq $line) {
        return "missing"
    }
    return $line.Substring($prefix.Length).Trim()
}

function Extract-AbCompletionPercent {
    param([string[]]$Lines)
    $line = $Lines | Where-Object { $_.StartsWith("- A/B progress: completion=") } | Select-Object -First 1
    if ($null -eq $line) {
        return $null
    }
    if ($line -match 'completion=([0-9]+(?:[.,][0-9]+)?)%') {
        $raw = $Matches[1].Replace(",", ".")
        $parsed = 0.0
        $ok = [double]::TryParse(
            $raw,
            [System.Globalization.NumberStyles]::Float,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [ref]$parsed
        )
        if ($ok) {
            return $parsed
        }
    }
    return $null
}

function Extract-ArtifactPath {
    param(
        [string[]]$Lines,
        [string]$Label
    )
    $prefix = "- ${Label}:"
    $line = $Lines | Where-Object { $_.StartsWith($prefix) } | Select-Object -First 1
    if ($null -eq $line) {
        return ""
    }
    return $line.Substring($prefix.Length).Trim()
}

function Resolve-ArtifactPathFromReport {
    param(
        [string]$ReportFilePath,
        [string]$ArtifactPath
    )
    if ([string]::IsNullOrWhiteSpace($ArtifactPath)) {
        return ""
    }
    if (Test-Path -LiteralPath $ArtifactPath) {
        return (Resolve-Path -LiteralPath $ArtifactPath).Path
    }
    $reportDir = Split-Path -Parent (Resolve-Path -LiteralPath $ReportFilePath).Path
    $candidate = Join-Path $reportDir $ArtifactPath
    if (Test-Path -LiteralPath $candidate) {
        return (Resolve-Path -LiteralPath $candidate).Path
    }
    return ""
}

function Test-GatePassed {
    param(
        [string]$GateKey,
        [string]$Status
    )
    $normalizedStatus = if ($null -eq $Status) { "" } else { $Status.Trim().ToLowerInvariant() }
    switch ($GateKey) {
        "compile" { return ($normalizedStatus -eq "ok") }
        "documentation_freshness" { return ($normalizedStatus -eq "ok") }
        "compile_warnings" { return ($normalizedStatus -eq "pass" -or $normalizedStatus -eq "ok") }
        "shader_compatibility" { return ($normalizedStatus -eq "ok") }
        "metrics_summary" { return ($normalizedStatus -eq "ok") }
        "server_governor_health" { return ($normalizedStatus -eq "pass" -or $normalizedStatus -eq "ok") }
        "chunk_compile_health" { return ($normalizedStatus -eq "pass" -or $normalizedStatus -eq "ok") }
        "drs_deferred_safety" { return ($normalizedStatus -eq "pass" -or $normalizedStatus -eq "ok") }
        "soak_stability" { return ($normalizedStatus -eq "pass" -or $normalizedStatus -eq "ok") }
        "kpi_gate" { return ($normalizedStatus -eq "pass" -or $normalizedStatus -eq "ok") }
        "ab_audit" { return ($normalizedStatus -eq "pass" -or $normalizedStatus -eq "ok") }
        "ab_progress" { return ($normalizedStatus -eq "pass" -or $normalizedStatus -eq "ok") }
        default { return $false }
    }
}

$normalizedBlockingGateKeys = New-Object System.Collections.Generic.List[string]
if (-not $DisableBlockingGates) {
    foreach ($rawGateKey in $BlockingGateKeys) {
        if ([string]::IsNullOrWhiteSpace($rawGateKey)) {
            continue
        }
        $normalizedGateKey = $rawGateKey.Trim().ToLowerInvariant()
        if (-not $normalizedBlockingGateKeys.Contains($normalizedGateKey)) {
            $normalizedBlockingGateKeys.Add($normalizedGateKey)
        }
    }
    if ($normalizedBlockingGateKeys.Count -eq 0) {
        throw "BlockingGateKeys must contain at least one gate when blocking gates are enabled"
    }
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    if (-not (Test-Path -LiteralPath $ReportsDir)) {
        throw "Reports directory not found: $ReportsDir"
    }
    $candidate = Get-ChildItem -LiteralPath $ReportsDir -File -Filter "phase6_preflight_*.md" |
            Sort-Object Name -Descending |
            Select-Object -First 1
    if ($null -eq $candidate) {
        throw "No preflight reports found in: $ReportsDir"
    }
    $ReportPath = $candidate.FullName
}

if (-not (Test-Path -LiteralPath $ReportPath)) {
    throw "Report file not found: $ReportPath"
}

$lines = Get-Content -LiteralPath $ReportPath
$docStatus = Extract-Status -Lines $lines -Label "Documentation freshness"
$compileStatus = Extract-Status -Lines $lines -Label "Compile"
$compileWarningsStatus = Extract-Status -Lines $lines -Label "Compile warnings"
$shaderStatus = Extract-Status -Lines $lines -Label "Shader compatibility"
$metricsStatus = Extract-Status -Lines $lines -Label "Metrics summary"
$serverGovernorStatus = Extract-Status -Lines $lines -Label "Server governor health"
$chunkCompileStatus = Extract-Status -Lines $lines -Label "Chunk compile health"
$drsDeferredSafetyStatus = Extract-Status -Lines $lines -Label "DRS/deferred safety"
$soakStabilityStatus = Extract-Status -Lines $lines -Label "Soak stability"
$kpiStatus = Extract-Status -Lines $lines -Label "KPI gate"
$abStatus = Extract-Status -Lines $lines -Label "A/B audit"
$abProgressStatus = Extract-Status -Lines $lines -Label "A/B progress"
$abCompletionPercent = Extract-AbCompletionPercent -Lines $lines
$serverGovernorCsvReportedPath = Extract-ArtifactPath -Lines $lines -Label "Server governor CSV"
$serverGovernorCsvPath = Resolve-ArtifactPathFromReport -ReportFilePath $ReportPath -ArtifactPath $serverGovernorCsvReportedPath
$serverGovernorIssueDetails = ""
$serverGovernorSkippedForInsufficientPressure = $false
if (-not [string]::IsNullOrWhiteSpace($serverGovernorCsvPath) -and (Test-Path -LiteralPath $serverGovernorCsvPath)) {
    try {
        $serverRows = @(Import-Csv -LiteralPath $serverGovernorCsvPath)
        if ($serverRows.Count -gt 0) {
            $lastServer = $serverRows | Select-Object -Last 1
            $serverGovernorIssueDetails = [string]$lastServer.issues
        }
    } catch {
        $serverGovernorIssueDetails = ""
    }
}
if ($serverGovernorStatus -like "skipped*" -and -not [string]::IsNullOrWhiteSpace($serverGovernorIssueDetails)) {
    $serverGovernorSkippedForInsufficientPressure = $serverGovernorIssueDetails.ToLowerInvariant().Contains("insufficient pressure samples for evaluation")
}

$weights = [ordered]@{
    compile = 20
    compile_warnings = 3
    doc = 10
    shader = 10
    metrics = 10
    server = 10
    chunk_compile = 5
    drs_deferred = 5
    soak_stability = 5
    kpi = 10
    ab = 12
}

$score = 0.0
$notes = New-Object System.Collections.Generic.List[string]

if ($compileStatus -eq "ok") {
    $score += $weights.compile
} else {
    $notes.Add("compile not ok")
}

if ($compileWarningsStatus -eq "pass" -or $compileWarningsStatus -eq "ok") {
    $score += $weights.compile_warnings
} elseif ($compileWarningsStatus -eq "warn") {
    $score += ($weights.compile_warnings * 0.4)
    $notes.Add("compile warnings present")
} elseif ($compileWarningsStatus -like "skipped*" -or $compileWarningsStatus -eq "missing") {
    $score += ($weights.compile_warnings * $SkippedWeightFactor)
    $notes.Add("compile warnings check skipped")
} else {
    $notes.Add("compile warnings gate failed")
}

if ($docStatus -eq "ok" -or $docStatus -like "skipped*") {
    $score += $weights.doc
} else {
    $notes.Add("documentation freshness failed")
}

if ($shaderStatus -eq "ok") {
    $score += $weights.shader
} elseif ($shaderStatus -like "skipped*") {
    $score += ($weights.shader * $SkippedWeightFactor)
    $notes.Add("shader compatibility skipped")
} else {
    $notes.Add("shader compatibility failed")
}

if ($metricsStatus -eq "ok") {
    $score += $weights.metrics
} elseif ($metricsStatus -like "skipped*") {
    $score += ($weights.metrics * $SkippedWeightFactor)
    $notes.Add("metrics summary skipped")
} else {
    $notes.Add("metrics summary failed")
}

if ($serverGovernorStatus -eq "pass" -or $serverGovernorStatus -eq "ok") {
    $score += $weights.server
} elseif ($serverGovernorStatus -eq "warn") {
    $score += ($weights.server * 0.4)
    $notes.Add("server governor health warning")
} elseif ($serverGovernorStatus -like "skipped*") {
    if ($TreatServerGovernorSkipAsPassWhenInsufficientPressure -and $serverGovernorSkippedForInsufficientPressure) {
        $score += $weights.server
        $notes.Add("server governor health skipped (insufficient pressure samples)")
    } else {
        $score += ($weights.server * $SkippedWeightFactor)
        if ($serverGovernorSkippedForInsufficientPressure) {
            $notes.Add("server governor health skipped (insufficient pressure samples)")
        } else {
            $notes.Add("server governor health skipped")
        }
    }
} else {
    $notes.Add("server governor health failed")
}

if ($chunkCompileStatus -eq "pass" -or $chunkCompileStatus -eq "ok") {
    $score += $weights.chunk_compile
} elseif ($chunkCompileStatus -eq "warn") {
    $score += ($weights.chunk_compile * 0.4)
    $notes.Add("chunk compile health warning")
} elseif ($chunkCompileStatus -like "skipped*" -or $chunkCompileStatus -eq "missing") {
    $score += ($weights.chunk_compile * $SkippedWeightFactor)
    $notes.Add("chunk compile health skipped")
} else {
    $notes.Add("chunk compile health failed")
}

if ($drsDeferredSafetyStatus -eq "pass" -or $drsDeferredSafetyStatus -eq "ok") {
    $score += $weights.drs_deferred
} elseif ($drsDeferredSafetyStatus -eq "warn") {
    $score += ($weights.drs_deferred * 0.4)
    $notes.Add("drs/deferred safety warning")
} elseif ($drsDeferredSafetyStatus -like "skipped*" -or $drsDeferredSafetyStatus -eq "missing") {
    $score += ($weights.drs_deferred * $SkippedWeightFactor)
    $notes.Add("drs/deferred safety skipped")
} else {
    $notes.Add("drs/deferred safety failed")
}

if ($soakStabilityStatus -eq "pass" -or $soakStabilityStatus -eq "ok") {
    $score += $weights.soak_stability
} elseif ($soakStabilityStatus -eq "warn") {
    $score += ($weights.soak_stability * 0.4)
    $notes.Add("soak stability warning")
} elseif ($soakStabilityStatus -like "skipped*" -or $soakStabilityStatus -eq "missing") {
    $score += ($weights.soak_stability * $SkippedWeightFactor)
    $notes.Add("soak stability skipped")
} else {
    $notes.Add("soak stability failed")
}

if ($kpiStatus -eq "pass" -or $kpiStatus -eq "ok") {
    $score += $weights.kpi
} elseif ($kpiStatus -like "skipped*") {
    $score += ($weights.kpi * $SkippedWeightFactor)
    $notes.Add("kpi gate skipped")
} elseif ($kpiStatus -eq "fail") {
    $notes.Add("kpi gate failed")
} else {
    $notes.Add("kpi gate unknown status")
}

if ($abStatus -eq "pass" -or $abStatus -eq "ok") {
    $score += $weights.ab
} elseif ($abStatus -like "skipped*") {
    $score += ($weights.ab * $SkippedWeightFactor)
    $notes.Add("A/B audit skipped")
} elseif ($abStatus -eq "fail") {
    if ($null -ne $abCompletionPercent -and $abCompletionPercent -gt 0) {
        $partialAb = $weights.ab * ($abCompletionPercent / 100.0)
        $score += $partialAb
        $notes.Add(("A/B audit failed (partial completion {0}%)" -f [Math]::Round($abCompletionPercent, 1)))
    } else {
        $notes.Add("A/B audit failed")
    }
} else {
    $notes.Add("A/B audit unknown status")
}

$gateStatusMap = [ordered]@{
    documentation_freshness = $docStatus
    compile = $compileStatus
    compile_warnings = $compileWarningsStatus
    shader_compatibility = $shaderStatus
    metrics_summary = $metricsStatus
    server_governor_health = $serverGovernorStatus
    server_governor_skip_issue = $serverGovernorIssueDetails
    server_governor_skipped_for_insufficient_pressure = $serverGovernorSkippedForInsufficientPressure
    chunk_compile_health = $chunkCompileStatus
    drs_deferred_safety = $drsDeferredSafetyStatus
    soak_stability = $soakStabilityStatus
    kpi_gate = $kpiStatus
    ab_audit = $abStatus
    ab_progress = $abProgressStatus
}

$blockingIssues = New-Object System.Collections.Generic.List[string]
if (-not $DisableBlockingGates) {
    foreach ($gateKey in $normalizedBlockingGateKeys) {
        if (-not $gateStatusMap.Contains($gateKey)) {
            $blockingIssues.Add(("unknown blocking gate: {0}" -f $gateKey))
            continue
        }
        $gateStatus = [string]$gateStatusMap[$gateKey]
        if (-not (Test-GatePassed -GateKey $gateKey -Status $gateStatus)) {
            $blockingIssues.Add(("{0}={1}" -f $gateKey, $gateStatus))
        }
    }
    if ($blockingIssues.Count -gt 0) {
        $notes.Add(("blocking gates failed: {0}" -f ($blockingIssues -join ", ")))
    }
}

$readinessPercent = [math]::Round($score, 1)
$hasBlockingIssues = $blockingIssues.Count -gt 0
$decision = if ($readinessPercent -ge $MinReadinessPercent -and -not $hasBlockingIssues) { "ready_for_beta" } else { "not_ready" }

$result = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    report_path = (Resolve-Path -LiteralPath $ReportPath).Path
    documentation_freshness = $docStatus
    compile = $compileStatus
    compile_warnings = $compileWarningsStatus
    shader_compatibility = $shaderStatus
    metrics_summary = $metricsStatus
    server_governor_health = $serverGovernorStatus
    server_governor_skip_issue = $serverGovernorIssueDetails
    server_governor_skipped_for_insufficient_pressure = $serverGovernorSkippedForInsufficientPressure
    chunk_compile_health = $chunkCompileStatus
    drs_deferred_safety = $drsDeferredSafetyStatus
    soak_stability = $soakStabilityStatus
    kpi_gate = $kpiStatus
    ab_audit = $abStatus
    ab_progress = $abProgressStatus
    ab_completion_percent = if ($null -eq $abCompletionPercent) { "" } else { [Math]::Round($abCompletionPercent, 1) }
    skipped_weight_factor = $SkippedWeightFactor
    treat_server_governor_skip_as_pass_when_insufficient_pressure = $TreatServerGovernorSkipAsPassWhenInsufficientPressure
    blocking_gates_enabled = (-not $DisableBlockingGates)
    blocking_gate_keys = if ($DisableBlockingGates) { "" } else { ($normalizedBlockingGateKeys -join "|") }
    blocking_issues_count = $blockingIssues.Count
    blocking_issues = ($blockingIssues -join "; ")
    readiness_percent = $readinessPercent
    threshold_percent = $MinReadinessPercent
    decision = $decision
    notes = ($notes -join "; ")
}

Write-Host ""
Write-Host "PauC beta readiness"
Write-Host "-------------------"
$result | Format-List

if (-not [string]::IsNullOrWhiteSpace($OutJsonPath)) {
    $result | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $OutJsonPath
    Write-Host ("Readiness JSON written to: {0}" -f $OutJsonPath)
}

if ($FailBelowThreshold -and $decision -ne "ready_for_beta") {
    if ($hasBlockingIssues) {
        throw ("Beta readiness blocked by critical gates: {0}" -f ($blockingIssues -join ", "))
    }
    throw ("Beta readiness below threshold: {0}% < {1}%" -f $readinessPercent, $MinReadinessPercent)
}

if ($PassThru) {
    Write-Output $result
}
