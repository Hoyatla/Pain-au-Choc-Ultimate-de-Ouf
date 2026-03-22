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

if (![string]::IsNullOrWhiteSpace($TargetConfigPath)) {
    if ([System.IO.Path]::IsPathRooted($TargetConfigPath)) {
        $targetConfig = $TargetConfigPath
    } else {
        $targetConfig = Join-Path $projectRoot $TargetConfigPath
    }
} else {
    $prismConfig = Join-Path (Join-Path (Join-Path $PrismRoot $InstanceName) "minecraft\\config") "pauc_ultimate_de_ouf.properties"
    $prismConfigDir = Split-Path -Parent $prismConfig
    if (Test-Path $prismConfigDir) {
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
