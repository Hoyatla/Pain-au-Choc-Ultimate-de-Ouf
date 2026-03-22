param(
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$DestinationDir = ".\run\pauc_telemetry",
    [string]$PrismRoot = "$env:APPDATA\PrismLauncher\instances",
    [string]$InstanceName = "",
    [switch]$DisableAutoMetricsDiscovery,
    [bool]$CopySegments = $true,
    [switch]$IncludeCaptureState,
    [switch]$Force,
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Copy-FileIfNeeded {
    param(
        [string]$SourcePath,
        [string]$DestinationPath,
        [bool]$ForceCopy
    )

    if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf)) {
        return [PSCustomObject]@{
            copied = $false
            skipped = $true
            reason = "source_missing"
            source = $SourcePath
            destination = $DestinationPath
        }
    }

    $sourceFull = (Resolve-Path -LiteralPath $SourcePath).Path
    $destinationFull = [System.IO.Path]::GetFullPath($DestinationPath)

    if ($sourceFull.Equals($destinationFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        return [PSCustomObject]@{
            copied = $false
            skipped = $true
            reason = "same_path"
            source = $sourceFull
            destination = $destinationFull
        }
    }

    $sourceItem = Get-Item -LiteralPath $sourceFull
    if ((Test-Path -LiteralPath $destinationFull -PathType Leaf) -and -not $ForceCopy) {
        $destinationItem = Get-Item -LiteralPath $destinationFull
        $sameSize = $sourceItem.Length -eq $destinationItem.Length
        $destinationNewerOrEqual = $destinationItem.LastWriteTimeUtc -ge $sourceItem.LastWriteTimeUtc
        if ($sameSize -and $destinationNewerOrEqual) {
            return [PSCustomObject]@{
                copied = $false
                skipped = $true
                reason = "up_to_date"
                source = $sourceFull
                destination = $destinationFull
            }
        }
    }

    $destinationParent = Split-Path -Path $destinationFull -Parent
    if (-not [string]::IsNullOrWhiteSpace($destinationParent)) {
        New-Item -ItemType Directory -Path $destinationParent -Force | Out-Null
    }

    Copy-Item -LiteralPath $sourceFull -Destination $destinationFull -Force
    return [PSCustomObject]@{
        copied = $true
        skipped = $false
        reason = "copied"
        source = $sourceFull
        destination = $destinationFull
    }
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $scriptRoot "..")).Path
$resolverScriptPath = Join-Path $scriptRoot "resolve_pauc_metrics_path.ps1"

$resolvedMetricsPath = $MetricsPath
$metricsSourceLabel = "input"
$preferPrismMetrics = -not [string]::IsNullOrWhiteSpace($InstanceName)
$shouldResolveMetrics = -not $DisableAutoMetricsDiscovery `
    -and (Test-Path -LiteralPath $resolverScriptPath -PathType Leaf) `
    -and ($preferPrismMetrics -or -not (Test-Path -LiteralPath $resolvedMetricsPath -PathType Leaf))

if ($shouldResolveMetrics) {
    try {
        $resolution = & $resolverScriptPath `
            -PreferredPath $MetricsPath `
            -PrismRoot $PrismRoot `
            -InstanceName $InstanceName `
            -SearchAllPrismInstances:$true `
            -PassThru

        if ($resolution -is [System.Array]) {
            $resolution = $resolution | Select-Object -Last 1
        }

        if ($null -ne $resolution -and [bool]$resolution.resolved) {
            $resolvedMetricsPath = [string]$resolution.metrics_path
            $metricsSourceLabel = [string]$resolution.source
        }
    } catch {
        Write-Warning ("Unable to auto-resolve metrics path: {0}" -f $_.Exception.Message)
    }
}

if (-not (Test-Path -LiteralPath $resolvedMetricsPath -PathType Leaf)) {
    throw "Metrics file not found: $MetricsPath"
}

$resolvedMetricsPath = (Resolve-Path -LiteralPath $resolvedMetricsPath).Path
$destinationRoot = if ([System.IO.Path]::IsPathRooted($DestinationDir)) {
    [System.IO.Path]::GetFullPath($DestinationDir)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot $DestinationDir))
}
New-Item -ItemType Directory -Path $destinationRoot -Force | Out-Null

$runtimeTargetPath = Join-Path $destinationRoot "runtime_metrics.csv"
$runtimeCopy = Copy-FileIfNeeded -SourcePath $resolvedMetricsPath -DestinationPath $runtimeTargetPath -ForceCopy:$Force

$sourceTelemetryDir = Split-Path -Path $resolvedMetricsPath -Parent
$sourceSegmentsDir = Join-Path $sourceTelemetryDir "ab_segments"
$targetSegmentsDir = Join-Path $destinationRoot "ab_segments"
$segmentFilesFound = 0
$segmentFilesCopied = 0
$segmentFilesSkipped = 0

if ($CopySegments -and (Test-Path -LiteralPath $sourceSegmentsDir -PathType Container)) {
    New-Item -ItemType Directory -Path $targetSegmentsDir -Force | Out-Null
    $segmentFiles = @(Get-ChildItem -LiteralPath $sourceSegmentsDir -File)
    $segmentFilesFound = $segmentFiles.Count
    foreach ($segmentFile in $segmentFiles) {
        $segmentTarget = Join-Path $targetSegmentsDir $segmentFile.Name
        $segmentCopy = Copy-FileIfNeeded -SourcePath $segmentFile.FullName -DestinationPath $segmentTarget -ForceCopy:$Force
        if ($segmentCopy.copied) {
            $segmentFilesCopied++
        } else {
            $segmentFilesSkipped++
        }
    }
}

$captureStateCopied = $false
$captureStateSource = Join-Path $sourceTelemetryDir "ab_capture_state.json"
$captureStateTarget = Join-Path $destinationRoot "ab_capture_state.json"
if ($IncludeCaptureState -and (Test-Path -LiteralPath $captureStateSource -PathType Leaf)) {
    $stateCopy = Copy-FileIfNeeded -SourcePath $captureStateSource -DestinationPath $captureStateTarget -ForceCopy:$Force
    $captureStateCopied = [bool]$stateCopy.copied
}

$targetMetricsPath = if (Test-Path -LiteralPath $runtimeTargetPath -PathType Leaf) {
    (Resolve-Path -LiteralPath $runtimeTargetPath).Path
} else {
    $resolvedMetricsPath
}

$summary = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    source_metrics_path = $resolvedMetricsPath
    source_metrics_label = $metricsSourceLabel
    destination_root = $destinationRoot
    target_metrics_path = $targetMetricsPath
    runtime_metrics_copied = [bool]$runtimeCopy.copied
    runtime_metrics_action = [string]$runtimeCopy.reason
    segment_files_found = $segmentFilesFound
    segment_files_copied = $segmentFilesCopied
    segment_files_skipped = $segmentFilesSkipped
    capture_state_copied = $captureStateCopied
}

Write-Host ""
Write-Host "PauC telemetry sync"
Write-Host "-------------------"
$summary | Format-List

if ($PassThru) {
    Write-Output $summary
}
