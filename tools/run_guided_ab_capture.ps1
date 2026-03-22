param(
    [Parameter(Mandatory = $true)][string]$Scene,
    [Parameter(Mandatory = $true)][string]$Profile,
    [string]$Build = "pauc-2.0.0-ultimate",
    [int]$DurationSeconds = 180,
    [int]$WarmupSeconds = 0,
    [int]$MinNewRows = 0,
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$StatePath = ".\run\pauc_telemetry\ab_capture_state.json",
    [string]$ResultsPath = ".\RESULTATS_TESTS_AB_PAUC.csv",
    [string]$PrismRoot = "$env:APPDATA\PrismLauncher\instances",
    [string]$InstanceName = "test",
    [switch]$OverwriteCapture,
    [switch]$SkipAutoFinish,
    [switch]$ForceAppend,
    [string]$Notes = "",
    [switch]$DisableAutoMetricsDiscovery
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($DurationSeconds -lt 30) {
    throw "DurationSeconds must be >= 30"
}
if ($WarmupSeconds -lt 0) {
    throw "WarmupSeconds must be >= 0"
}
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

function Resolve-SceneAlias {
    param([string]$InputScene)
    $normalized = $InputScene.Trim().ToLowerInvariant()
    switch ($normalized) {
        "scene_1_village" { return "scene_1_village" }
        "scene1_village" { return "scene_1_village" }
        "scene1" { return "scene_1_village" }
        "village" { return "scene_1_village" }
        "scene_2_fast_move" { return "scene_2_fast_move" }
        "scene2_fast_move" { return "scene_2_fast_move" }
        "scene2" { return "scene_2_fast_move" }
        "fast_move" { return "scene_2_fast_move" }
        "scene_3_combat_particles" { return "scene_3_combat_particles" }
        "scene3_combat_particles" { return "scene_3_combat_particles" }
        "scene3" { return "scene_3_combat_particles" }
        "combat_particles" { return "scene_3_combat_particles" }
        "scene_4_modded_base" { return "scene_4_modded_base" }
        "scene4_modded_base" { return "scene_4_modded_base" }
        "scene4" { return "scene_4_modded_base" }
        "modded_base" { return "scene_4_modded_base" }
        default { return $InputScene }
    }
}

function Resolve-ProfileAlias {
    param([string]$InputProfile)
    $normalized = $InputProfile.Trim().ToLowerInvariant()
    switch ($normalized) {
        "a" { return "A_baseline" }
        "a_baseline" { return "A_baseline" }
        "baseline" { return "A_baseline" }
        "baseline_off" { return "A_baseline" }
        "b1" { return "B1_stable" }
        "b1_stable" { return "B1_stable" }
        "stable" { return "B1_stable" }
        "b2" { return "B2_aggressive" }
        "b2_aggressive" { return "B2_aggressive" }
        "aggressive" { return "B2_aggressive" }
        "b_safe" { return "B_safe" }
        "safe" { return "B_safe" }
        "b_balanced" { return "B_balanced" }
        "balanced" { return "B_balanced" }
        "b_competitive240" { return "B_competitive240" }
        "competitive240" { return "B_competitive240" }
        "competitive" { return "B_competitive240" }
        "b_cinematic" { return "B_cinematic" }
        "cinematic" { return "B_cinematic" }
        default { return $InputProfile }
    }
}

function Get-SceneProtocolLines {
    param([string]$ResolvedScene)
    switch ($ResolvedScene) {
        "scene_1_village" {
            return @(
                "Start in the same village entry point each run.",
                "Do 3 identical loops around the village center.",
                "Stand still 10s at the end facing the same direction."
            )
        }
        "scene_2_fast_move" {
            return @(
                "Use the same mount/elytra setup each run.",
                "Do 4 out-and-back passes on the same axis.",
                "Avoid menu pauses during the timed window."
            )
        }
        "scene_3_combat_particles" {
            return @(
                "Use the same combat area each run.",
                "Trigger the same sequence of attacks/effects in cycles.",
                "Keep intensity constant over the whole window."
            )
        }
        "scene_4_modded_base" {
            return @(
                "Use the same modded base route each run.",
                "Do 3 loops through machine-heavy + transparent areas.",
                "End with 10s static view on the densest zone."
            )
        }
        default {
            return @(
                "Use the same start point and route for each repetition.",
                "Keep movement and actions as consistent as possible.",
                "Avoid opening menus during the timed window."
            )
        }
    }
}

function Invoke-Countdown {
    param([int]$Seconds)
    $startUtc = (Get-Date).ToUniversalTime()
    for ($remaining = $Seconds; $remaining -gt 0; $remaining--) {
        if ($remaining -eq $Seconds -or $remaining % 30 -eq 0 -or $remaining -le 10) {
            Write-Host ("Capture running... remaining {0}s" -f $remaining)
        }
        Start-Sleep -Seconds 1
    }
    $elapsed = [Math]::Round(((Get-Date).ToUniversalTime() - $startUtc).TotalSeconds, 1)
    Write-Host ("Capture timer finished (elapsed ~{0}s)." -f $elapsed)
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $scriptRoot "..")).Path

$resolvedStartScript = Resolve-ExistingPath -InputPath ".\tools\ab_mark_start.ps1" -RepoRoot $repoRoot
$resolvedFinishScript = Resolve-ExistingPath -InputPath ".\tools\ab_mark_finish.ps1" -RepoRoot $repoRoot
$resolvedScene = Resolve-SceneAlias -InputScene $Scene
$resolvedProfile = Resolve-ProfileAlias -InputProfile $Profile
$effectiveMinRows = if ($MinNewRows -gt 0) { $MinNewRows } else { [Math]::Max(60, [int][Math]::Floor($DurationSeconds * 0.85)) }

Push-Location $repoRoot
try {
    Write-Host ""
    Write-Host "Guided A/B capture"
    Write-Host "------------------"
    Write-Host ("Scene   : {0}" -f $resolvedScene)
    Write-Host ("Profile : {0}" -f $resolvedProfile)
    Write-Host ("Duration: {0}s" -f $DurationSeconds)
    Write-Host ("Min rows: {0}" -f $effectiveMinRows)
    Write-Host ""
    Write-Host "Protocol:"
    foreach ($line in (Get-SceneProtocolLines -ResolvedScene $resolvedScene)) {
        Write-Host ("- {0}" -f $line)
    }

    $startArgs = @{
        Scene = $resolvedScene
        Profile = $resolvedProfile
        Build = $Build
        MetricsPath = $MetricsPath
        StatePath = $StatePath
        PrismRoot = $PrismRoot
        PrismInstanceName = $InstanceName
    }
    if ($OverwriteCapture) {
        $startArgs.Overwrite = $true
    }
    if ($DisableAutoMetricsDiscovery) {
        $startArgs.DisableAutoMetricsDiscovery = $true
    }
    & $resolvedStartScript @startArgs

    if ($WarmupSeconds -gt 0) {
        Write-Host ("Warmup started ({0}s)..." -f $WarmupSeconds)
        Invoke-Countdown -Seconds $WarmupSeconds
    }

    Write-Host ("Timed capture started for {0}s. Play now." -f $DurationSeconds)
    Invoke-Countdown -Seconds $DurationSeconds

    if ($SkipAutoFinish) {
        Write-Host ""
        Write-Host "Auto finish skipped."
        Write-Host ("Run manually: .\tools\ab_mark_finish.ps1 -MinNewRows {0}" -f $effectiveMinRows)
        return
    }

    $resolvedNotes = if ([string]::IsNullOrWhiteSpace($Notes)) {
        "guided_capture duration={0}s min_rows={1}" -f $DurationSeconds, $effectiveMinRows
    } else {
        "{0}; guided_capture duration={1}s min_rows={2}" -f $Notes, $DurationSeconds, $effectiveMinRows
    }

    $finishArgs = @{
        MetricsPath = $MetricsPath
        StatePath = $StatePath
        ResultsPath = $ResultsPath
        Scene = $resolvedScene
        Profile = $resolvedProfile
        Build = $Build
        MinNewRows = $effectiveMinRows
        PrismRoot = $PrismRoot
        InstanceName = $InstanceName
        Notes = $resolvedNotes
    }
    if ($ForceAppend) {
        $finishArgs.ForceAppend = $true
    }
    if ($DisableAutoMetricsDiscovery) {
        $finishArgs.DisableAutoMetricsDiscovery = $true
    }

    & $resolvedFinishScript @finishArgs
} finally {
    Pop-Location
}
