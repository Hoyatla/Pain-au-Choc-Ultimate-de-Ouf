param(
    [string]$ResultsPath = ".\RESULTATS_TESTS_AB_PAUC.csv",
    [string]$CampaignStatusScriptPath = ".\tools\ab_campaign_status.ps1",
    [string]$MarkStartScriptPath = ".\tools\ab_mark_start.ps1",
    [string]$ApplyProfileScriptPath = ".\tools\apply_pauc_profile.ps1",
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$StatePath = ".\run\pauc_telemetry\ab_capture_state.json",
    [string]$Build = "",
    [string]$PrismRoot = "$env:APPDATA\PrismLauncher\instances",
    [string]$InstanceName = "1.20.1(1)",
    [switch]$DisableAutoMetricsDiscovery,
    [switch]$ApplyProfile,
    [switch]$StartCapture,
    [switch]$OverwriteCapture,
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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

    $candidateLocal = Join-Path $RepoRoot $InputPath
    if (Test-Path -LiteralPath $candidateLocal) {
        return (Resolve-Path -LiteralPath $candidateLocal).Path
    }

    if (Test-Path -LiteralPath $InputPath) {
        return (Resolve-Path -LiteralPath $InputPath).Path
    }

    throw "Path not found: $InputPath"
}

function Get-GradlePropertyValue {
    param(
        [string]$FilePath,
        [string]$Key,
        [string]$DefaultValue = ""
    )
    if (-not (Test-Path -LiteralPath $FilePath)) {
        return $DefaultValue
    }
    $prefix = "$Key="
    foreach ($line in Get-Content -LiteralPath $FilePath) {
        if ($line.StartsWith($prefix)) {
            return $line.Substring($prefix.Length).Trim()
        }
    }
    return $DefaultValue
}

function Resolve-LauncherProfile {
    param([string]$CampaignProfile)
    switch -Regex ($CampaignProfile) {
        '^A_baseline(_repeat)?$' { return "baseline_off" }
        '^B1_stable$' { return "stable" }
        '^B2_aggressive$' { return "aggressive" }
        '^B_safe$' { return "safe" }
        '^B_balanced$' { return "balanced" }
        '^B_competitive240$' { return "competitive240" }
        '^B_cinematic$' { return "cinematic" }
        default { return "" }
    }
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $scriptRoot "..")).Path

Push-Location $repoRoot
try {
    $resolvedResultsPath = Resolve-ExistingPath -InputPath $ResultsPath -RepoRoot $repoRoot
    $resolvedStatusScript = Resolve-ExistingPath -InputPath $CampaignStatusScriptPath -RepoRoot $repoRoot
    $resolvedMarkStartScript = Resolve-ExistingPath -InputPath $MarkStartScriptPath -RepoRoot $repoRoot
    $resolvedApplyProfileScript = Resolve-ExistingPath -InputPath $ApplyProfileScriptPath -RepoRoot $repoRoot

    if ([string]::IsNullOrWhiteSpace($Build)) {
        $gradlePropsPath = Join-Path $repoRoot "gradle.properties"
        $modVersion = Get-GradlePropertyValue -FilePath $gradlePropsPath -Key "mod_version" -DefaultValue "2.0.0-ultimate"
        $Build = "pauc-$modVersion"
    }

    $resolvedMetricsPath = $MetricsPath
    $metricsResolverScript = Join-Path $scriptRoot "resolve_pauc_metrics_path.ps1"
    $preferPrismMetrics = -not [string]::IsNullOrWhiteSpace($InstanceName)
    $shouldResolveMetrics = -not $DisableAutoMetricsDiscovery `
        -and (Test-Path -LiteralPath $metricsResolverScript -PathType Leaf) `
        -and ($preferPrismMetrics -or -not (Test-Path -LiteralPath $resolvedMetricsPath -PathType Leaf))
    if ($shouldResolveMetrics) {
        try {
            $metricsResolution = & $metricsResolverScript `
                -PreferredPath $MetricsPath `
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

    $status = & $resolvedStatusScript -ResultsPath $resolvedResultsPath -PassThru
    if ($status -is [System.Array]) {
        $status = $status | Select-Object -Last 1
    }
    if ($null -eq $status) {
        throw "Unable to resolve A/B campaign status from $resolvedStatusScript"
    }

    $completion = [double]$status.completion_percent
    $nextScene = [string]$status.next_scene
    $nextProfile = [string]$status.next_profile
    $campaignDone = [string]::IsNullOrWhiteSpace($nextScene) -or [string]::IsNullOrWhiteSpace($nextProfile)
    $launcherProfile = Resolve-LauncherProfile -CampaignProfile $nextProfile

    $profileApplied = $false
    $captureStarted = $false

    if (-not $campaignDone -and $ApplyProfile) {
        if ([string]::IsNullOrWhiteSpace($launcherProfile)) {
            Write-Warning ("No launcher profile mapping found for campaign profile '{0}'." -f $nextProfile)
        } else {
            & $resolvedApplyProfileScript -Profile $launcherProfile -PrismRoot $PrismRoot -InstanceName $InstanceName
            $profileApplied = $true
        }
    }

    if (-not $campaignDone -and $StartCapture) {
        $startArgs = @{
            Scene = $nextScene
            Profile = $nextProfile
            Build = $Build
            MetricsPath = $resolvedMetricsPath
            StatePath = $StatePath
        }
        if ($OverwriteCapture) {
            $startArgs.Overwrite = $true
        }
        & $resolvedMarkStartScript @startArgs
        $captureStarted = $true
    }

    $result = [PSCustomObject]@{
        timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        completion_percent = $completion
        campaign_done = $campaignDone
        next_scene = if ($campaignDone) { "" } else { $nextScene }
        next_profile = if ($campaignDone) { "" } else { $nextProfile }
        launcher_profile = if ($campaignDone) { "" } else { $launcherProfile }
        metrics_path = if ($campaignDone) { "" } else { $resolvedMetricsPath }
        profile_applied = $profileApplied
        capture_started = $captureStarted
        next_finish_command = if ($campaignDone) { "" } else { ".\tools\ab_mark_finish.ps1" }
        next_strict_beta_command = ".\tools\build_beta_candidate.ps1 -StrictPreflight -StrictReadiness"
        source_results = $resolvedResultsPath
    }

    Write-Host ""
    Write-Host "PauC A/B next step"
    Write-Host "------------------"
    if (-not $PassThru) {
        $result | Format-List
    }
    if (-not $campaignDone) {
        Write-Host "Guidance:"
        if (-not $captureStarted) {
            Write-Host ("- Start capture: .\tools\ab_mark_start.ps1 -Scene {0} -Profile {1}" -f $nextScene, $nextProfile)
        }
        Write-Host "- Play the target scene for ~3 minutes."
        Write-Host "- Finish capture: .\tools\ab_mark_finish.ps1"
    } else {
        Write-Host "Campaign is complete. You can run strict beta candidate now."
    }

    if ($PassThru) {
        Write-Output $result
    }
} finally {
    Pop-Location
}
