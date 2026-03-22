param(
    [string]$PreferredPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$PrismRoot = "$env:APPDATA\PrismLauncher\instances",
    [string]$InstanceName = "",
    [bool]$SearchAllPrismInstances = $true,
    [switch]$FailIfMissing,
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $scriptRoot "..")).Path

function Add-MetricsCandidate {
    param(
        [System.Collections.Generic.List[object]]$Buffer,
        [System.Collections.Generic.HashSet[string]]$Seen,
        [string]$CandidatePath,
        [string]$Source
    )
    if ([string]::IsNullOrWhiteSpace($CandidatePath)) {
        return
    }
    if (-not (Test-Path -LiteralPath $CandidatePath -PathType Leaf)) {
        return
    }

    $resolved = (Resolve-Path -LiteralPath $CandidatePath).Path
    if ($Seen.Add($resolved)) {
        $item = Get-Item -LiteralPath $resolved
        $Buffer.Add([PSCustomObject]@{
                path = $resolved
                source = $Source
                last_write_utc = $item.LastWriteTimeUtc
                size_bytes = $item.Length
            })
    }
}

function Add-CandidateWithRelativeFallback {
    param(
        [System.Collections.Generic.List[object]]$Buffer,
        [System.Collections.Generic.HashSet[string]]$Seen,
        [string]$InputPath,
        [string]$Source
    )
    if ([string]::IsNullOrWhiteSpace($InputPath)) {
        return
    }

    if ([System.IO.Path]::IsPathRooted($InputPath)) {
        Add-MetricsCandidate -Buffer $Buffer -Seen $Seen -CandidatePath $InputPath -Source $Source
        return
    }

    Add-MetricsCandidate -Buffer $Buffer -Seen $Seen -CandidatePath $InputPath -Source $Source
    Add-MetricsCandidate -Buffer $Buffer -Seen $Seen -CandidatePath (Join-Path $repoRoot $InputPath) -Source ("{0}:repo" -f $Source)
}

$candidates = New-Object System.Collections.Generic.List[object]
$seen = New-Object System.Collections.Generic.HashSet[string] ([System.StringComparer]::OrdinalIgnoreCase)

$envMetricsPath = [Environment]::GetEnvironmentVariable("PAUC_METRICS_PATH")
Add-CandidateWithRelativeFallback -Buffer $candidates -Seen $seen -InputPath $envMetricsPath -Source "env:PAUC_METRICS_PATH"
Add-CandidateWithRelativeFallback -Buffer $candidates -Seen $seen -InputPath $PreferredPath -Source "preferred"

if (-not [string]::IsNullOrWhiteSpace($InstanceName)) {
    $prismInstanceRoot = Join-Path $PrismRoot $InstanceName
    Add-MetricsCandidate -Buffer $candidates -Seen $seen -CandidatePath (Join-Path $prismInstanceRoot ".minecraft\pauc_telemetry\runtime_metrics.csv") -Source ("prism:{0}" -f $InstanceName)
    Add-MetricsCandidate -Buffer $candidates -Seen $seen -CandidatePath (Join-Path $prismInstanceRoot "minecraft\pauc_telemetry\runtime_metrics.csv") -Source ("prism:{0}:legacy" -f $InstanceName)
}

if ($SearchAllPrismInstances -and -not [string]::IsNullOrWhiteSpace($PrismRoot) -and (Test-Path -LiteralPath $PrismRoot -PathType Container)) {
    $instanceDirs = Get-ChildItem -LiteralPath $PrismRoot -Directory -ErrorAction SilentlyContinue
    foreach ($instanceDir in $instanceDirs) {
        Add-MetricsCandidate -Buffer $candidates -Seen $seen -CandidatePath (Join-Path $instanceDir.FullName ".minecraft\pauc_telemetry\runtime_metrics.csv") -Source ("prism-scan:{0}" -f $instanceDir.Name)
        Add-MetricsCandidate -Buffer $candidates -Seen $seen -CandidatePath (Join-Path $instanceDir.FullName "minecraft\pauc_telemetry\runtime_metrics.csv") -Source ("prism-scan:{0}:legacy" -f $instanceDir.Name)
    }
}

$bestCandidate = $null
if ($candidates.Count -gt 0) {
    $bestCandidate = $candidates | Sort-Object -Property last_write_utc -Descending | Select-Object -First 1
}

$result = if ($null -eq $bestCandidate) {
    [PSCustomObject]@{
        timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        resolved = $false
        metrics_path = ""
        source = ""
        candidate_count = 0
        last_write_utc = ""
        size_bytes = 0
    }
} else {
    [PSCustomObject]@{
        timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        resolved = $true
        metrics_path = $bestCandidate.path
        source = $bestCandidate.source
        candidate_count = $candidates.Count
        last_write_utc = $bestCandidate.last_write_utc.ToString("yyyy-MM-ddTHH:mm:ssZ")
        size_bytes = $bestCandidate.size_bytes
    }
}

Write-Host ""
Write-Host "PauC metrics path resolver"
Write-Host "-------------------------"
$result | Format-List

if ($FailIfMissing -and -not $result.resolved) {
    throw "No runtime_metrics.csv found (checked preferred path, env, and Prism instances)."
}

if ($PassThru) {
    Write-Output $result
}
