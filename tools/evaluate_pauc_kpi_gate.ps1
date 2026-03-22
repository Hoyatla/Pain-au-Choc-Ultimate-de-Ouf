param(
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$OutCsvPath = "",
    [double]$FrameMsP95Max = 6.0,
    [double]$FrameMsP99Max = 8.0,
    [double]$MsptP95Max = 50.0,
    [switch]$FailOnBreach
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function To-DoubleArray {
    param([object[]]$Values)

    $buffer = New-Object System.Collections.Generic.List[double]
    foreach ($value in $Values) {
        if ($null -eq $value) {
            continue
        }
        $raw = $value.ToString().Trim()
        if ([string]::IsNullOrWhiteSpace($raw)) {
            continue
        }
        $normalized = $raw.Replace(",", ".")
        $parsed = 0.0
        if ([double]::TryParse(
                $normalized,
                [System.Globalization.NumberStyles]::Float,
                [System.Globalization.CultureInfo]::InvariantCulture,
                [ref]$parsed
            )) {
            $buffer.Add($parsed)
        }
    }
    return [double[]]$buffer
}

function Get-Percentile {
    param(
        [double[]]$SortedValues,
        [double]$Percentile
    )
    if ($SortedValues.Count -eq 0) {
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

function Evaluate-Criterion {
    param(
        [string]$Name,
        [double]$Measured,
        [double]$TargetMax
    )
    $status = if ($Measured -le $TargetMax) { "pass" } else { "fail" }
    return [PSCustomObject]@{
        criterion = $Name
        measured = [Math]::Round($Measured, 4)
        target_max = [Math]::Round($TargetMax, 4)
        status = $status
    }
}

if (-not (Test-Path -LiteralPath $MetricsPath)) {
    throw "Metrics file not found: $MetricsPath"
}

$rows = @(Import-Csv -LiteralPath $MetricsPath)
if (-not $rows -or $rows.Count -eq 0) {
    throw "Metrics file is empty: $MetricsPath"
}

$frameMs = To-DoubleArray ($rows | Select-Object -ExpandProperty frame_ms)
$mspt = To-DoubleArray ($rows | Select-Object -ExpandProperty mspt_smoothed)

if ($frameMs.Count -eq 0) {
    throw "No valid numeric frame_ms samples in metrics file: $MetricsPath"
}
if ($mspt.Count -eq 0) {
    throw "No valid numeric mspt_smoothed samples in metrics file: $MetricsPath"
}

[Array]::Sort($frameMs)
[Array]::Sort($mspt)

$frameP95 = Get-Percentile -SortedValues $frameMs -Percentile 95
$frameP99 = Get-Percentile -SortedValues $frameMs -Percentile 99
$msptP95 = Get-Percentile -SortedValues $mspt -Percentile 95

$criteria = New-Object System.Collections.Generic.List[object]
$criteria.Add((Evaluate-Criterion -Name "frame_ms_p95" -Measured $frameP95 -TargetMax $FrameMsP95Max))
$criteria.Add((Evaluate-Criterion -Name "frame_ms_p99" -Measured $frameP99 -TargetMax $FrameMsP99Max))
$criteria.Add((Evaluate-Criterion -Name "mspt_p95" -Measured $msptP95 -TargetMax $MsptP95Max))

$hasFail = @($criteria | Where-Object { $_.status -eq "fail" }).Count -gt 0
$overallStatus = if ($hasFail) { "fail" } else { "pass" }

$summary = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    samples = $rows.Count
    frame_ms_p95 = [Math]::Round($frameP95, 4)
    frame_ms_p99 = [Math]::Round($frameP99, 4)
    mspt_p95 = [Math]::Round($msptP95, 4)
    target_frame_ms_p95_max = [Math]::Round($FrameMsP95Max, 4)
    target_frame_ms_p99_max = [Math]::Round($FrameMsP99Max, 4)
    target_mspt_p95_max = [Math]::Round($MsptP95Max, 4)
    overall_status = $overallStatus
    source = (Resolve-Path -LiteralPath $MetricsPath).Path
}

Write-Host ""
Write-Host "PauC KPI gate"
Write-Host "-------------"
$criteria | Format-Table -AutoSize
Write-Host ""
$summary | Format-List

if (-not [string]::IsNullOrWhiteSpace($OutCsvPath)) {
    $exportExists = Test-Path -LiteralPath $OutCsvPath
    $summary | Export-Csv -LiteralPath $OutCsvPath -NoTypeInformation -Append:$exportExists
    Write-Host ""
    Write-Host "KPI summary appended to: $OutCsvPath"
}

if ($FailOnBreach -and $overallStatus -ne "pass") {
    throw "KPI gate breached: overall_status=$overallStatus"
}
