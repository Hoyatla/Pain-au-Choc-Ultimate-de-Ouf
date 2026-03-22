param(
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$OutCsvPath = "",
    [int]$MinDeferredSamples = 5,
    [double]$MaxDrsActiveRatioWhenDeferred = 1.0,
    [double]$MinDeferredSafetyReasonRatio = 0.0,
    [bool]$TreatNoDeferredSamplesAsPass = $true,
    [switch]$FailOnIssues
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($MinDeferredSamples -lt 1) {
    throw "MinDeferredSamples must be >= 1"
}
if ($MaxDrsActiveRatioWhenDeferred -lt 0.0 -or $MaxDrsActiveRatioWhenDeferred -gt 1.0) {
    throw "MaxDrsActiveRatioWhenDeferred must be between 0.0 and 1.0"
}
if ($MinDeferredSafetyReasonRatio -lt 0.0 -or $MinDeferredSafetyReasonRatio -gt 1.0) {
    throw "MinDeferredSafetyReasonRatio must be between 0.0 and 1.0"
}

function Parse-BoolValue {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }
    switch ($Value.Trim().ToLowerInvariant()) {
        "true" { return $true }
        "false" { return $false }
        "1" { return $true }
        "0" { return $false }
        "yes" { return $true }
        "no" { return $false }
        default { return $null }
    }
}

if (-not (Test-Path -LiteralPath $MetricsPath)) {
    throw "Metrics file not found: $MetricsPath"
}

$rows = @(Import-Csv -LiteralPath $MetricsPath)
if (-not $rows -or $rows.Count -eq 0) {
    throw "Metrics file is empty: $MetricsPath"
}

$requiredColumns = @("deferred_active", "drs_active", "drs_reason")
$availableColumns = @($rows[0].PSObject.Properties.Name)
$missingColumns = @($requiredColumns | Where-Object { $availableColumns -notcontains $_ })
$issues = New-Object System.Collections.Generic.List[string]

$sampleCount = $rows.Count
$deferredSamples = 0
$deferredDrsActiveSamples = 0
$deferredSafetyReasonSamples = 0
$overallStatus = "pass"

if ($missingColumns.Count -gt 0) {
    $overallStatus = "skipped"
    $issues.Add(("missing columns: {0}" -f ($missingColumns -join ", ")))
} else {
    foreach ($row in $rows) {
        $deferredActive = Parse-BoolValue -Value ([string]$row.deferred_active)
        if ($deferredActive -ne $true) {
            continue
        }

        $deferredSamples++
        $drsActive = Parse-BoolValue -Value ([string]$row.drs_active)
        if ($drsActive -eq $true) {
            $deferredDrsActiveSamples++
        }

        $drsReason = [string]$row.drs_reason
        if (-not [string]::IsNullOrWhiteSpace($drsReason) -and $drsReason.Trim().ToLowerInvariant().Contains("deferred pipeline safety")) {
            $deferredSafetyReasonSamples++
        }
    }

    if ($deferredSamples -lt $MinDeferredSamples) {
        if ($deferredSamples -eq 0 -and $TreatNoDeferredSamplesAsPass) {
            $overallStatus = "pass"
        } else {
            $overallStatus = "skipped"
            $issues.Add(("insufficient deferred samples: {0} < {1}" -f $deferredSamples, $MinDeferredSamples))
        }
    } else {
        $drsActiveRatioWhenDeferred = $deferredDrsActiveSamples / [double]$deferredSamples
        $deferredSafetyReasonRatio = $deferredSafetyReasonSamples / [double]$deferredSamples

        if ($drsActiveRatioWhenDeferred -gt $MaxDrsActiveRatioWhenDeferred) {
            $issues.Add(
                ("drs active while deferred exceeds limit ({0:P2} > {1:P2})" -f $drsActiveRatioWhenDeferred, $MaxDrsActiveRatioWhenDeferred)
            )
        }
        if ($MinDeferredSafetyReasonRatio -gt 0.0 -and $deferredSafetyReasonRatio -lt $MinDeferredSafetyReasonRatio) {
            $issues.Add(
                ("deferred safety reason ratio below minimum ({0:P2} < {1:P2})" -f $deferredSafetyReasonRatio, $MinDeferredSafetyReasonRatio)
            )
        }

        if ($issues.Count -gt 0) {
            $overallStatus = "warn"
        }
    }
}

$drsActiveRatioValue = if ($deferredSamples -gt 0) {
    [Math]::Round(($deferredDrsActiveSamples / [double]$deferredSamples), 4)
} else {
    0.0
}
$deferredSafetyReasonRatioValue = if ($deferredSamples -gt 0) {
    [Math]::Round(($deferredSafetyReasonSamples / [double]$deferredSamples), 4)
} else {
    0.0
}

$result = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    sample_count = $sampleCount
    deferred_samples = $deferredSamples
    deferred_with_drs_active = $deferredDrsActiveSamples
    deferred_with_safety_reason = $deferredSafetyReasonSamples
    drs_active_ratio_when_deferred = $drsActiveRatioValue
    deferred_safety_reason_ratio = $deferredSafetyReasonRatioValue
    max_drs_active_ratio_when_deferred = [Math]::Round($MaxDrsActiveRatioWhenDeferred, 4)
    min_required_deferred_safety_reason_ratio = [Math]::Round($MinDeferredSafetyReasonRatio, 4)
    issue_count = $issues.Count
    issues = ($issues -join "; ")
    overall_status = $overallStatus
    source = (Resolve-Path -LiteralPath $MetricsPath).Path
}

Write-Host ""
Write-Host "PauC DRS/deferred safety gate"
Write-Host "-----------------------------"
$result | Format-List

if (-not [string]::IsNullOrWhiteSpace($OutCsvPath)) {
    $exportExists = Test-Path -LiteralPath $OutCsvPath
    $result | Export-Csv -LiteralPath $OutCsvPath -NoTypeInformation -Append:$exportExists
    Write-Host ("DRS/deferred safety summary appended to: {0}" -f $OutCsvPath)
}

if ($FailOnIssues -and $overallStatus -ne "pass") {
    throw ("DRS/deferred safety gate failed: status={0}; issues={1}" -f $overallStatus, $result.issues)
}
