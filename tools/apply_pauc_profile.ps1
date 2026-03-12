param(
    [ValidateSet("baseline_off", "stable", "aggressive")]
    [string]$Profile = "stable",
    [string]$PrismRoot = "$env:APPDATA\\PrismLauncher\\instances",
    [string]$InstanceName = "1.20.1(1)"
)

$projectRoot = Split-Path -Parent $PSScriptRoot
$profileFile = Join-Path $PSScriptRoot ("pauc_profile_" + $Profile + ".properties")
$targetConfig = Join-Path (Join-Path (Join-Path $PrismRoot $InstanceName) "minecraft\\config") "pauc_ultimate_de_ouf.properties"

if (!(Test-Path $profileFile)) {
    throw "Profile introuvable: $profileFile"
}

$targetDir = Split-Path -Parent $targetConfig
if (!(Test-Path $targetDir)) {
    throw "Dossier config introuvable: $targetDir"
}

Copy-Item -Path $profileFile -Destination $targetConfig -Force
Write-Output "Profil applique: $Profile"
Write-Output "Config cible: $targetConfig"
