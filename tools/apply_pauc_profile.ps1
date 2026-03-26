param(
    [ValidateSet("baseline_off", "stable", "aggressive", "safe", "balanced", "competitive240", "cinematic")]
    [string]$Profile = "stable",
    [string]$PrismRoot = "$env:APPDATA\\PrismLauncher\\instances",
    [string]$InstanceName = "1.20.1(1)",
    [string]$TargetConfigPath = ""
)

$projectRoot = Split-Path -Parent $PSScriptRoot
$profileFile = Join-Path $PSScriptRoot ("pauc_profile_" + $Profile + ".properties")

if (!(Test-Path $profileFile)) {
    throw "Profile introuvable: $profileFile"
}

function Resolve-PrismConfigPath {
    param(
        [string]$PrismRootPath,
        [string]$PrismInstanceName
    )

    if ([string]::IsNullOrWhiteSpace($PrismRootPath) -or [string]::IsNullOrWhiteSpace($PrismInstanceName)) {
        return ""
    }

    $instanceRoot = Join-Path $PrismRootPath $PrismInstanceName
    $dotConfigDir = Join-Path (Join-Path $instanceRoot ".minecraft") "config"
    $legacyConfigDir = Join-Path (Join-Path $instanceRoot "minecraft") "config"

    if (Test-Path -LiteralPath $dotConfigDir -PathType Container) {
        return (Join-Path $dotConfigDir "pauc_ultimate_de_ouf.properties")
    }
    if (Test-Path -LiteralPath $legacyConfigDir -PathType Container) {
        return (Join-Path $legacyConfigDir "pauc_ultimate_de_ouf.properties")
    }

    return ""
}

if (![string]::IsNullOrWhiteSpace($TargetConfigPath)) {
    if ([System.IO.Path]::IsPathRooted($TargetConfigPath)) {
        $targetConfig = $TargetConfigPath
    } else {
        $targetConfig = Join-Path $projectRoot $TargetConfigPath
    }
} else {
    $prismConfig = Resolve-PrismConfigPath -PrismRootPath $PrismRoot -PrismInstanceName $InstanceName
    if (-not [string]::IsNullOrWhiteSpace($prismConfig)) {
        $targetConfig = $prismConfig
    } else {
        $targetConfig = Join-Path (Join-Path $projectRoot "config") "pauc_ultimate_de_ouf.properties"
        Write-Warning "Prism instance introuvable, fallback vers config locale: $targetConfig"
    }
}

$targetDir = Split-Path -Parent $targetConfig
if (!(Test-Path $targetDir)) {
    New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
}

Copy-Item -Path $profileFile -Destination $targetConfig -Force
Write-Output "Profil applique: $Profile"
Write-Output "Config cible: $targetConfig"
