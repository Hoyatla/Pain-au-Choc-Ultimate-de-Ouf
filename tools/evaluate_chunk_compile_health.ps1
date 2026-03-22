param(
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$OutCsvPath = "",
    [double]$MinBudgetPreviewAvg = 4.0,
    [double]$MaxCompileBackpressureAvg = 0.45,
    [double]$MaxCompileBackpressureP95 = 0.90,
    [double]$MaxBuilderBackpressureAvg = 0.65,
    [double]$MaxBuilderBackpressureP95 = 0.95,
    [double]$MaxBuilderPendingAvg = 18.0,
    [double]$MaxBuilderPendingP95 = 48.0,
    [double]$NoPressureCompileBackpressureP95 = 0.02,
    [double]$NoPressureBuilderBackpressureP95 = 0.02,
    [double]$NoPressureBuilderPendingP95 = 1.0,
    [switch]$FailOnIssues
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Parse-DoubleValue {
    param(
        [string]$Value,
        [double]$Fallback = 0.0
    )
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $Fallback
    }
    $parsed = 0.0
    $normalized = $Value.Replace(",", ".")
    if ([double]::TryParse(
            $normalized,
            [System.Globalization.NumberStyles]::Float,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [ref]$parsed
        )) {
        return $parsed
    }
    return $Fallback
}

function Get-Percentile {
    param(
        [double[]]$SortedValues,
        [double]$Percentile
    )
    if ($null -eq $SortedValues -or $SortedValues.Count -eq 0) {
        return [double]::NaN
    }
    if ($SortedValues.Count -eq 1) {
        return $SortedValues[0]
    }

    $p = [Math]::Max(0.0, [Math]::Min(1.0, $Percentile / 100.0))
    $position = ($SortedValues.Count - 1) * $p
    $lowerIndex = [Math]::Floor($position)
    $upperIndex = [Math]::Ceiling($position)
    if ($lowerIndex -eq $upperIndex) {
        return $SortedValues[$lowerIndex]
    }

    $weight = $position - $lowerIndex
    return $SortedValues[$lowerIndex] + (($SortedValues[$upperIndex] - $SortedValues[$lowerIndex]) * $weight)
}

if (-not (Test-Path -LiteralPath $MetricsPath)) {
    throw "Metrics file not found: $MetricsPath"
}

$rows = @(Import-Csv -LiteralPath $MetricsPath)
if (-not $rows -or $rows.Count -eq 0) {
    throw "Metrics file is empty: $MetricsPath"
}

$requiredColumns = @(
    "chunk_compile_budget_preview",
    "chunk_compile_backpressure",
    "chunk_builder_backpressure",
    "chunk_builder_pending"
)
$availableColumns = $rows[0].PSObject.Properties.Name
$missingColumns = @($requiredColumns | Where-Object { $availableColumns -notcontains $_ })

$issues = New-Object System.Collections.Generic.List[string]
$overallStatus = "pass"
$samples = $rows.Count
$budgetPreviewAvg = [double]::NaN
$budgetPreviewP95 = [double]::NaN
$compileBackpressureAvg = [double]::NaN
$compileBackpressureP95 = [double]::NaN
$builderBackpressureAvg = [double]::NaN
$builderBackpressureP95 = [double]::NaN
$builderPendingAvg = [double]::NaN
$builderPendingP95 = [double]::NaN
$compilePressureDetected = $false

if ($missingColumns.Count -gt 0) {
    $overallStatus = "skipped"
    $issues.Add(("missing telemetry columns: {0}" -f ($missingColumns -join ", ")))
} else {
    $budgetPreview = New-Object System.Collections.Generic.List[double]
    $compileBackpressure = New-Object System.Collections.Generic.List[double]
    $builderBackpressure = New-Object System.Collections.Generic.List[double]
    $builderPending = New-Object System.Collections.Generic.List[double]

    foreach ($row in $rows) {
        $budgetPreview.Add((Parse-DoubleValue -Value $row.chunk_compile_budget_preview -Fallback 0.0))
        $compileBackpressure.Add((Parse-DoubleValue -Value $row.chunk_compile_backpressure -Fallback 0.0))
        $builderBackpressure.Add((Parse-DoubleValue -Value $row.chunk_builder_backpressure -Fallback 0.0))
        $builderPending.Add((Parse-DoubleValue -Value $row.chunk_builder_pending -Fallback 0.0))
    }

    $budgetPreviewValues = [double[]]$budgetPreview
    $compileBackpressureValues = [double[]]$compileBackpressure
    $builderBackpressureValues = [double[]]$builderBackpressure
    $builderPendingValues = [double[]]$builderPending

    [Array]::Sort($budgetPreviewValues)
    [Array]::Sort($compileBackpressureValues)
    [Array]::Sort($builderBackpressureValues)
    [Array]::Sort($builderPendingValues)

    $budgetPreviewAvg = (($budgetPreviewValues | Measure-Object -Average).Average)
    $budgetPreviewP95 = Get-Percentile -SortedValues $budgetPreviewValues -Percentile 95
    $compileBackpressureAvg = (($compileBackpressureValues | Measure-Object -Average).Average)
    $compileBackpressureP95 = Get-Percentile -SortedValues $compileBackpressureValues -Percentile 95
    $builderBackpressureAvg = (($builderBackpressureValues | Measure-Object -Average).Average)
    $builderBackpressureP95 = Get-Percentile -SortedValues $builderBackpressureValues -Percentile 95
    $builderPendingAvg = (($builderPendingValues | Measure-Object -Average).Average)
    $builderPendingP95 = Get-Percentile -SortedValues $builderPendingValues -Percentile 95

    $compilePressureDetected = ($compileBackpressureP95 -gt $NoPressureCompileBackpressureP95) `
        -or ($builderBackpressureP95 -gt $NoPressureBuilderBackpressureP95) `
        -or ($builderPendingP95 -gt $NoPressureBuilderPendingP95)

    if ($budgetPreviewValues[-1] -le 0.0) {
        $overallStatus = "skipped"
        $issues.Add("chunk compile budget preview is zero across all samples")
    } else {
        if ($compilePressureDetected -and $budgetPreviewAvg -lt $MinBudgetPreviewAvg) {
            $issues.Add(("budget preview avg too low ({0:0.###} < {1:0.###})" -f $budgetPreviewAvg, $MinBudgetPreviewAvg))
        }
        if ($compileBackpressureAvg -gt $MaxCompileBackpressureAvg) {
            $issues.Add(("compile backpressure avg too high ({0:0.###} > {1:0.###})" -f $compileBackpressureAvg, $MaxCompileBackpressureAvg))
        }
        if ($compileBackpressureP95 -gt $MaxCompileBackpressureP95) {
            $issues.Add(("compile backpressure p95 too high ({0:0.###} > {1:0.###})" -f $compileBackpressureP95, $MaxCompileBackpressureP95))
        }
        if ($builderBackpressureAvg -gt $MaxBuilderBackpressureAvg) {
            $issues.Add(("builder backpressure avg too high ({0:0.###} > {1:0.###})" -f $builderBackpressureAvg, $MaxBuilderBackpressureAvg))
        }
        if ($builderBackpressureP95 -gt $MaxBuilderBackpressureP95) {
            $issues.Add(("builder backpressure p95 too high ({0:0.###} > {1:0.###})" -f $builderBackpressureP95, $MaxBuilderBackpressureP95))
        }
        if ($builderPendingAvg -gt $MaxBuilderPendingAvg) {
            $issues.Add(("builder pending avg too high ({0:0.###} > {1:0.###})" -f $builderPendingAvg, $MaxBuilderPendingAvg))
        }
        if ($builderPendingP95 -gt $MaxBuilderPendingP95) {
            $issues.Add(("builder pending p95 too high ({0:0.###} > {1:0.###})" -f $builderPendingP95, $MaxBuilderPendingP95))
        }
    }

    if ($overallStatus -ne "skipped" -and $issues.Count -gt 0) {
        $overallStatus = "warn"
    }
}

$summary = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    samples = $samples
    budget_preview_avg = if ([double]::IsNaN($budgetPreviewAvg)) { "" } else { [Math]::Round($budgetPreviewAvg, 3) }
    budget_preview_p95 = if ([double]::IsNaN($budgetPreviewP95)) { "" } else { [Math]::Round($budgetPreviewP95, 3) }
    compile_backpressure_avg = if ([double]::IsNaN($compileBackpressureAvg)) { "" } else { [Math]::Round($compileBackpressureAvg, 4) }
    compile_backpressure_p95 = if ([double]::IsNaN($compileBackpressureP95)) { "" } else { [Math]::Round($compileBackpressureP95, 4) }
    builder_backpressure_avg = if ([double]::IsNaN($builderBackpressureAvg)) { "" } else { [Math]::Round($builderBackpressureAvg, 4) }
    builder_backpressure_p95 = if ([double]::IsNaN($builderBackpressureP95)) { "" } else { [Math]::Round($builderBackpressureP95, 4) }
    builder_pending_avg = if ([double]::IsNaN($builderPendingAvg)) { "" } else { [Math]::Round($builderPendingAvg, 3) }
    builder_pending_p95 = if ([double]::IsNaN($builderPendingP95)) { "" } else { [Math]::Round($builderPendingP95, 3) }
    compile_pressure_detected = $compilePressureDetected
    issue_count = $issues.Count
    issues = ($issues -join "; ")
    overall_status = $overallStatus
    source = (Resolve-Path -LiteralPath $MetricsPath).Path
}

Write-Host ""
Write-Host "PauC chunk compile health"
Write-Host "-------------------------"
$summary | Format-List

if (-not [string]::IsNullOrWhiteSpace($OutCsvPath)) {
    $exportExists = Test-Path -LiteralPath $OutCsvPath
    $summary | Export-Csv -LiteralPath $OutCsvPath -NoTypeInformation -Append:$exportExists
    Write-Host ""
    Write-Host ("Chunk compile summary appended to: {0}" -f $OutCsvPath)
}

if ($FailOnIssues -and $overallStatus -ne "pass") {
    throw ("Chunk compile health not pass: overall_status={0}; issues={1}" -f $overallStatus, $summary.issues)
}
