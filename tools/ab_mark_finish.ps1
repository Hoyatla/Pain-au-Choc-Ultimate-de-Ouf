param(
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$StatePath = ".\run\pauc_telemetry\ab_capture_state.json",
    [string]$ResultsPath = ".\RESULTATS_TESTS_AB_PAUC.csv",
    [string]$AppendScriptPath = ".\tools\append_ab_result_from_metrics.ps1",
    [string]$CampaignStatusScriptPath = ".\tools\ab_campaign_status.ps1",
    [string]$CampaignNextScriptPath = ".\tools\ab_campaign_next.ps1",
    [string]$Scene = "",
    [string]$Profile = "",
    [string]$Build = "",
    [int]$MinNewRows = 0,
    [switch]$ForceAppend,
    [bool]$ShowCampaignStatus = $true,
    [switch]$AutoPrepareNext,
    [switch]$ApplyProfileForNext,
    [string]$PrismRoot = "$env:APPDATA\PrismLauncher\instances",
    [string]$InstanceName = "1.20.1(1)",
    [int]$StutterCount = 0,
    [string]$VisualIssues = "",
    [string]$CrashOrError = "",
    [string]$PointEval = "",
    [string]$Decision = "",
    [string]$Notes = "",
    [switch]$DisableAutoMetricsDiscovery
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($MinNewRows -lt 0) {
    throw "MinNewRows must be >= 0"
}

function Resolve-ExistingPath {
    param(
        [string]$InputPath,
        [string]$RepoRoot
    )
    if ([System.IO.Path]::IsPathRooted($InputPath)) {
        if (-not (Test-Path -LiteralPath $InputPath)) {
            throw "Path not found: $InputPath"
        }
        return (Resolve-Path -LiteralPath $InputPath).Path
    }

    if (Test-Path -LiteralPath $InputPath) {
        return (Resolve-Path -LiteralPath $InputPath).Path
    }

    $candidate = Join-Path $RepoRoot $InputPath
    if (Test-Path -LiteralPath $candidate) {
        return (Resolve-Path -LiteralPath $candidate).Path
    }

    throw "Path not found: $InputPath"
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path

if (-not (Test-Path -LiteralPath $StatePath)) {
    throw "Capture state not found: $StatePath"
}

$state = Get-Content -LiteralPath $StatePath -Raw | ConvertFrom-Json
if ($state.schema -ne "pauc_ab_capture_v1") {
    throw "Unsupported capture state schema: $($state.schema)"
}

$resolvedMetricsPath = $MetricsPath
$stateMetricsPath = [string]$state.metrics_path
if (-not (Test-Path -LiteralPath $resolvedMetricsPath -PathType Leaf) -and -not [string]::IsNullOrWhiteSpace($stateMetricsPath)) {
    $resolvedMetricsPath = $stateMetricsPath
}
$resolverScriptPath = Join-Path $PSScriptRoot "resolve_pauc_metrics_path.ps1"
$preferPrismMetrics = -not [string]::IsNullOrWhiteSpace($InstanceName)
$shouldResolveMetrics = -not $DisableAutoMetricsDiscovery `
    -and (Test-Path -LiteralPath $resolverScriptPath -PathType Leaf) `
    -and ($preferPrismMetrics -or -not (Test-Path -LiteralPath $resolvedMetricsPath -PathType Leaf))
if ($shouldResolveMetrics) {
    try {
        $metricsResolution = & $resolverScriptPath `
            -PreferredPath $resolvedMetricsPath `
            -PrismRoot $PrismRoot `
            -InstanceName $InstanceName `
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

$resolvedAppendScript = Resolve-ExistingPath -InputPath $AppendScriptPath -RepoRoot $repoRoot
$resolvedCampaignStatusScript = Resolve-ExistingPath -InputPath $CampaignStatusScriptPath -RepoRoot $repoRoot
$resolvedCampaignNextScript = Resolve-ExistingPath -InputPath $CampaignNextScriptPath -RepoRoot $repoRoot

$rows = @(Import-Csv -LiteralPath $resolvedMetricsPath)
if (-not $rows -or $rows.Count -eq 0) {
    throw "Metrics file is empty: $resolvedMetricsPath"
}

$startCount = [int]$state.start_row_count
if ($rows.Count -le $startCount) {
    throw ("No new metric rows found since capture start (start_row_count={0}, current={1})." -f $startCount, $rows.Count)
}

$segmentRows = @($rows | Select-Object -Skip $startCount)
if ($segmentRows.Count -eq 0) {
    throw "Computed segment is empty."
}
if ($MinNewRows -gt 0 -and $segmentRows.Count -lt $MinNewRows) {
    throw ("Not enough new metric rows for capture finish (required={0}, current={1})." -f $MinNewRows, $segmentRows.Count)
}

$resolvedScene = if ([string]::IsNullOrWhiteSpace($Scene)) { [string]$state.scene } else { $Scene }
$resolvedProfile = if ([string]::IsNullOrWhiteSpace($Profile)) { [string]$state.profile } else { $Profile }
$resolvedBuild = if ([string]::IsNullOrWhiteSpace($Build)) { [string]$state.build } else { $Build }

$segmentsDir = Join-Path -Path (Split-Path -Path $resolvedMetricsPath -Parent) -ChildPath "ab_segments"
New-Item -ItemType Directory -Path $segmentsDir -Force | Out-Null
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss_fff"
$segmentPath = Join-Path -Path $segmentsDir -ChildPath ("ab_segment_{0}.csv" -f $timestamp)
$segmentRows | Export-Csv -LiteralPath $segmentPath -NoTypeInformation

$appendArgs = @{
    Scene = $resolvedScene
    Profile = $resolvedProfile
    Build = $resolvedBuild
    MetricsPath = $segmentPath
    ResultsPath = $ResultsPath
    StutterCount = $StutterCount
    VisualIssues = $VisualIssues
    CrashOrError = $CrashOrError
    PointEval = $PointEval
    Decision = $Decision
    Notes = $Notes
}
if ($ForceAppend) {
    $appendArgs.ForceAppend = $true
}

& $resolvedAppendScript @appendArgs

try {
    Remove-Item -LiteralPath $StatePath -Force
} catch {
    Write-Warning ("Could not remove state file after successful capture: {0}" -f $StatePath)
}

Write-Host ""
Write-Host "A/B capture finished"
Write-Host "-------------------"
Write-Host ("Segment rows: {0}" -f $segmentRows.Count)
Write-Host ("Segment file: {0}" -f $segmentPath)
Write-Host ("Results file: {0}" -f $ResultsPath)

$campaignStatus = $null
if ($ShowCampaignStatus) {
    $campaignStatus = & $resolvedCampaignStatusScript -ResultsPath $ResultsPath -PassThru
    if ($campaignStatus -is [System.Array]) {
        $campaignStatus = $campaignStatus | Select-Object -Last 1
    }
}

if ($AutoPrepareNext -and $null -ne $campaignStatus) {
    $missingCells = 0
    [void][int]::TryParse([string]$campaignStatus.missing_cells, [ref]$missingCells)
    if ($missingCells -gt 0) {
        $nextArgs = @{
            ResultsPath = $ResultsPath
            StartCapture = $true
            OverwriteCapture = $true
            Build = $resolvedBuild
            MetricsPath = $resolvedMetricsPath
            StatePath = $StatePath
        }
        if ($ApplyProfileForNext) {
            $nextArgs.ApplyProfile = $true
            $nextArgs.PrismRoot = $PrismRoot
            $nextArgs.InstanceName = $InstanceName
        }
        & $resolvedCampaignNextScript @nextArgs
    } else {
        Write-Host ""
        Write-Host "A/B campaign complete: no automatic next capture prepared."
    }
}
