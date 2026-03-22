param(
    [Parameter(Mandatory = $true)][string]$Scene,
    [Parameter(Mandatory = $true)][string]$Profile,
    [string]$Build = "pauc-2.0.0-ultimate",
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$StatePath = ".\run\pauc_telemetry\ab_capture_state.json",
    [string]$PrismRoot = "$env:APPDATA\PrismLauncher\instances",
    [string]$PrismInstanceName = "",
    [switch]$DisableAutoMetricsDiscovery,
    [switch]$Overwrite
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$resolverScriptPath = Join-Path $PSScriptRoot "resolve_pauc_metrics_path.ps1"
$resolvedMetricsPath = $MetricsPath
$preferPrismMetrics = -not [string]::IsNullOrWhiteSpace($PrismInstanceName)
$shouldResolveMetrics = -not $DisableAutoMetricsDiscovery `
    -and (Test-Path -LiteralPath $resolverScriptPath -PathType Leaf) `
    -and ($preferPrismMetrics -or -not (Test-Path -LiteralPath $resolvedMetricsPath -PathType Leaf))
if ($shouldResolveMetrics) {
    try {
        $metricsResolution = & $resolverScriptPath `
            -PreferredPath $MetricsPath `
            -PrismRoot $PrismRoot `
            -InstanceName $PrismInstanceName `
            -SearchAllPrismInstances:$true `
            -PassThru

        if ($metricsResolution -is [System.Array]) {
            $metricsResolution = $metricsResolution | Select-Object -Last 1
        }
        if ($null -ne $metricsResolution -and [bool]$metricsResolution.resolved) {
            $resolvedMetricsPath = [string]$metricsResolution.metrics_path
        }
    } catch {
        Write-Warning ("Unable to auto-resolve metrics path: {0}" -f $_.Exception.Message)
    }
}
if (-not (Test-Path -LiteralPath $resolvedMetricsPath -PathType Leaf)) {
    throw "Metrics file not found: $MetricsPath"
}

if ((Test-Path -LiteralPath $StatePath) -and -not $Overwrite) {
    throw "Capture state already exists. Use -Overwrite to replace it: $StatePath"
}

$rows = @(Import-Csv -LiteralPath $resolvedMetricsPath)
if (-not $rows -or $rows.Count -eq 0) {
    throw "Metrics file is empty: $resolvedMetricsPath"
}

$lastRow = $rows[$rows.Count - 1]
$state = [PSCustomObject]@{
    schema = "pauc_ab_capture_v1"
    started_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    metrics_path = (Resolve-Path -LiteralPath $resolvedMetricsPath).Path
    start_row_count = $rows.Count
    start_timestamp = $lastRow.timestamp
    start_session_seconds = $lastRow.session_seconds
    scene = $Scene
    profile = $Profile
    build = $Build
}

$stateDir = Split-Path -Path $StatePath -Parent
if (-not [string]::IsNullOrWhiteSpace($stateDir)) {
    New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
}

$state | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $StatePath -Encoding UTF8

Write-Host ""
Write-Host "A/B capture started"
Write-Host "------------------"
$state | Format-List
Write-Host ("State file: {0}" -f $StatePath)
