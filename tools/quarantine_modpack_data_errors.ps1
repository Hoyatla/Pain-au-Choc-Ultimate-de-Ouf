param(
    [string]$InstanceName = "test",
    [string]$PrismInstancesRoot = "",
    [string[]]$LogPaths = @(),
    [string]$KubeJsRoot = "",
    [string]$OutDir = ".\run\pauc_reports",
    [switch]$SkipRecipes,
    [switch]$SkipLootTables,
    [switch]$AdoptExisting,
    [switch]$Undo,
    [string]$ManifestPath = "",
    [switch]$DryRun,
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Split-ResourceId {
    param([string]$Id)
    $parts = $Id.Split(":", 2)
    if ($parts.Count -ne 2 -or [string]::IsNullOrWhiteSpace($parts[0]) -or [string]::IsNullOrWhiteSpace($parts[1])) {
        throw ("Invalid resource id: {0}" -f $Id)
    }

    return [PSCustomObject]@{
        namespace = $parts[0].ToLowerInvariant()
        path = $parts[1].ToLowerInvariant()
    }
}

function Get-RelativeDataPath {
    param(
        [ValidateSet("recipe", "loot_table")]
        [string]$Kind,
        [string]$Id
    )

    $split = Split-ResourceId -Id $Id
    $base = if ($Kind -eq "recipe") { "recipes" } else { "loot_tables" }
    $resourcePath = $split.path -replace "/", [System.IO.Path]::DirectorySeparatorChar
    return ("data{0}{1}{0}{2}{0}{3}.json" -f [System.IO.Path]::DirectorySeparatorChar, $split.namespace, $base, $resourcePath)
}

function Get-AbsoluteDataPath {
    param(
        [string]$KubeJsRootPath,
        [ValidateSet("recipe", "loot_table")]
        [string]$Kind,
        [string]$Id
    )
    $relative = Get-RelativeDataPath -Kind $Kind -Id $Id
    return Join-Path $KubeJsRootPath $relative
}

function Get-RecipeOverrideJson {
    return @(
        "{"
        "  `"type`": `"minecraft:crafting_special_repairitem`""
        "}"
    ) -join "`n"
}

function Get-LootOverrideJson {
    param([string]$Id)
    $split = Split-ResourceId -Id $Id
    $lootType = if ($split.path.StartsWith("chests/")) { "minecraft:chest" } else { "minecraft:generic" }
    return @(
        "{"
        "  `"type`": `"$lootType`","
        "  `"pools`": []"
        "}"
    ) -join "`n"
}

function Ensure-Directory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -Path $Path -ItemType Directory -Force | Out-Null
    }
}

function Remove-EmptyParents {
    param(
        [string]$StartDirectory,
        [string]$StopDirectory
    )
    if (-not (Test-Path -LiteralPath $StartDirectory)) {
        return
    }

    $stopFull = [System.IO.Path]::GetFullPath($StopDirectory.TrimEnd('\', '/'))
    $current = [System.IO.Path]::GetFullPath($StartDirectory.TrimEnd('\', '/'))
    while ($current.StartsWith($stopFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        if ($current.Equals($stopFull, [System.StringComparison]::OrdinalIgnoreCase)) {
            break
        }
        if (-not (Test-Path -LiteralPath $current)) {
            break
        }
        $children = @(Get-ChildItem -LiteralPath $current -Force)
        if ($children.Count -gt 0) {
            break
        }
        Remove-Item -LiteralPath $current -Force
        $parent = Split-Path -Path $current -Parent
        if ([string]::IsNullOrWhiteSpace($parent)) {
            break
        }
        $current = $parent
    }
}

function Get-CurrentHashOrEmpty {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return ""
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

if ([string]::IsNullOrWhiteSpace($PrismInstancesRoot)) {
    $PrismInstancesRoot = Join-Path $env:APPDATA "PrismLauncher\instances"
}

if ([string]::IsNullOrWhiteSpace($KubeJsRoot)) {
    $KubeJsRoot = Join-Path $PrismInstancesRoot "$InstanceName\minecraft\kubejs"
}

if ($LogPaths.Count -eq 0) {
    $logsRoot = Join-Path $PrismInstancesRoot "$InstanceName\minecraft\logs"
    $LogPaths = @(
        (Join-Path $logsRoot "latest.log"),
        (Join-Path $logsRoot "debug.log")
    )
}

$resolvedLogs = @(
    $LogPaths |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { $_.Trim() } |
        Select-Object -Unique
)

if (-not $Undo) {
    $missingLogs = @($resolvedLogs | Where-Object { -not (Test-Path -LiteralPath $_) })
    if ($missingLogs.Count -gt 0) {
        throw ("Missing log files: {0}" -f ($missingLogs -join ", "))
    }
}

Ensure-Directory -Path $OutDir
Ensure-Directory -Path $KubeJsRoot

$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd_HHmmss_fff")

if ($Undo) {
    $manifestDir = Join-Path $KubeJsRoot "pauc_quarantine"
    if ([string]::IsNullOrWhiteSpace($ManifestPath)) {
        $latestManifest = Get-ChildItem -LiteralPath $manifestDir -Filter "manifest_*.json" -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($null -eq $latestManifest) {
            throw ("No manifest found in {0}" -f $manifestDir)
        }
        $ManifestPath = $latestManifest.FullName
    }

    if (-not (Test-Path -LiteralPath $ManifestPath)) {
        throw ("Manifest file not found: {0}" -f $ManifestPath)
    }

    $manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
    $createdFiles = @($manifest.created_files)

    $removed = New-Object System.Collections.Generic.List[object]
    $skippedModified = New-Object System.Collections.Generic.List[object]
    $alreadyMissing = New-Object System.Collections.Generic.List[object]

    foreach ($entry in $createdFiles) {
        $targetPath = [string]$entry.target_path
        $recordedHash = [string]$entry.sha256

        if (-not (Test-Path -LiteralPath $targetPath)) {
            $alreadyMissing.Add([PSCustomObject]@{
                    target_path = $targetPath
                    reason = "missing"
                })
            continue
        }

        $currentHash = Get-CurrentHashOrEmpty -Path $targetPath
        if (-not [string]::IsNullOrWhiteSpace($recordedHash) -and $recordedHash -ne $currentHash) {
            $skippedModified.Add([PSCustomObject]@{
                    target_path = $targetPath
                    recorded_hash = $recordedHash
                    current_hash = $currentHash
                    reason = "hash_mismatch"
                })
            continue
        }

        if (-not $DryRun) {
            Remove-Item -LiteralPath $targetPath -Force
            $parent = Split-Path -Path $targetPath -Parent
            Remove-EmptyParents -StartDirectory $parent -StopDirectory (Join-Path $KubeJsRoot "data")
        }

        $removed.Add([PSCustomObject]@{
                target_path = $targetPath
                hash = $currentHash
            })
    }

    $undoSummary = [PSCustomObject]@{
        timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        mode = "undo"
        dry_run = [bool]$DryRun
        manifest_path = $ManifestPath
        removed_count = $removed.Count
        skipped_modified_count = $skippedModified.Count
        already_missing_count = $alreadyMissing.Count
        removed_files = $removed.ToArray()
        skipped_modified_files = $skippedModified.ToArray()
        already_missing_files = $alreadyMissing.ToArray()
    }

    $undoJsonPath = Join-Path $OutDir ("modpack_quarantine_undo_{0}.json" -f $stamp)
    $undoMdPath = Join-Path $OutDir ("modpack_quarantine_undo_{0}.md" -f $stamp)
    $undoSummary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $undoJsonPath -Encoding UTF8

    $md = New-Object System.Collections.Generic.List[string]
    $md.Add("# Modpack Data Quarantine Undo")
    $md.Add("")
    $md.Add(("- Timestamp UTC: {0}" -f $undoSummary.timestamp_utc))
    $md.Add(("- Dry run: {0}" -f $undoSummary.dry_run))
    $md.Add(("- Manifest: {0}" -f $undoSummary.manifest_path))
    $md.Add(("- Removed files: {0}" -f $undoSummary.removed_count))
    $md.Add(("- Skipped modified: {0}" -f $undoSummary.skipped_modified_count))
    $md.Add(("- Already missing: {0}" -f $undoSummary.already_missing_count))
    $md.Add("")
    $md.Add(("- JSON: {0}" -f (Resolve-Path -LiteralPath $undoJsonPath).Path))
    $md | Set-Content -LiteralPath $undoMdPath -Encoding UTF8

    Write-Host ""
    Write-Host "PauC modpack quarantine (undo)"
    Write-Host "------------------------------"
    Write-Host ""
    $undoSummary | Format-List
    Write-Host ""
    Write-Host ("Report MD:  {0}" -f (Resolve-Path -LiteralPath $undoMdPath).Path)
    Write-Host ("Report JSON:{0}" -f (Resolve-Path -LiteralPath $undoJsonPath).Path)

    if ($PassThru) {
        $undoSummary
    }
    return
}

$candidates = New-Object System.Collections.Generic.List[object]
foreach ($logPath in $resolvedLogs) {
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $logPath) {
        $lineNumber++

        $recipeMatch = [regex]::Match($line, "Parsing error loading recipe (?<id>[A-Za-z0-9_.-]+:[A-Za-z0-9_./-]+)")
        if ($recipeMatch.Success) {
            $candidates.Add([PSCustomObject]@{
                    kind = "recipe"
                    id = $recipeMatch.Groups["id"].Value.ToLowerInvariant()
                    log_file = $logPath
                    line_number = $lineNumber
                    line = $line
                })
        }

        $lootMatch = [regex]::Match($line, "Couldn't parse element loot_tables:(?<id>[A-Za-z0-9_.-]+:[A-Za-z0-9_./-]+)")
        if ($lootMatch.Success) {
            $candidates.Add([PSCustomObject]@{
                    kind = "loot_table"
                    id = $lootMatch.Groups["id"].Value.ToLowerInvariant()
                    log_file = $logPath
                    line_number = $lineNumber
                    line = $line
                })
        }
    }
}

$recipeIds = @(
    $candidates |
        Where-Object { $_.kind -eq "recipe" } |
        Select-Object -ExpandProperty id -Unique |
        Sort-Object
)
$lootIds = @(
    $candidates |
        Where-Object { $_.kind -eq "loot_table" } |
        Select-Object -ExpandProperty id -Unique |
        Sort-Object
)

if ($SkipRecipes) { $recipeIds = @() }
if ($SkipLootTables) { $lootIds = @() }

$createdFiles = New-Object System.Collections.Generic.List[object]
$adoptedFiles = New-Object System.Collections.Generic.List[object]
$alreadyManaged = New-Object System.Collections.Generic.List[object]
$skippedExisting = New-Object System.Collections.Generic.List[object]

function Apply-Override {
    param(
        [ValidateSet("recipe", "loot_table")]
        [string]$Kind,
        [string]$Id
    )

    $targetPath = Get-AbsoluteDataPath -KubeJsRootPath $KubeJsRoot -Kind $Kind -Id $Id
    $parentDir = Split-Path -Path $targetPath -Parent
    $content = if ($Kind -eq "recipe") { Get-RecipeOverrideJson } else { Get-LootOverrideJson -Id $Id }

    if (Test-Path -LiteralPath $targetPath) {
        $existing = Get-Content -LiteralPath $targetPath -Raw
        if ($existing.Trim() -eq $content.Trim()) {
            if ($AdoptExisting) {
                $hash = Get-CurrentHashOrEmpty -Path $targetPath
                $adoptedEntry = [PSCustomObject]@{
                    kind = $Kind
                    id = $Id
                    target_path = $targetPath
                    relative_path = Get-RelativeDataPath -Kind $Kind -Id $Id
                    sha256 = $hash
                    source = "adopted_existing"
                }
                $adoptedFiles.Add($adoptedEntry)
                $createdFiles.Add($adoptedEntry)
            }
            else {
                $alreadyManaged.Add([PSCustomObject]@{
                        kind = $Kind
                        id = $Id
                        target_path = $targetPath
                        reason = "already_same_content"
                    })
            }
        }
        else {
            $skippedExisting.Add([PSCustomObject]@{
                    kind = $Kind
                    id = $Id
                    target_path = $targetPath
                    reason = "path_exists_with_different_content"
                })
        }
        return
    }

    if (-not $DryRun) {
        Ensure-Directory -Path $parentDir
        Set-Content -LiteralPath $targetPath -Value $content -Encoding UTF8
    }

    $hash = if ($DryRun) { "" } else { Get-CurrentHashOrEmpty -Path $targetPath }
    $createdFiles.Add([PSCustomObject]@{
            kind = $Kind
            id = $Id
            target_path = $targetPath
            relative_path = Get-RelativeDataPath -Kind $Kind -Id $Id
            sha256 = $hash
            source = "created_new"
        })
}

foreach ($id in $recipeIds) {
    Apply-Override -Kind "recipe" -Id $id
}
foreach ($id in $lootIds) {
    Apply-Override -Kind "loot_table" -Id $id
}

$manifestDir = Join-Path $KubeJsRoot "pauc_quarantine"
if (-not $DryRun) {
    Ensure-Directory -Path $manifestDir
}

$manifestObject = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    instance_name = $InstanceName
    kubejs_root = $KubeJsRoot
    logs = $resolvedLogs
    recipe_candidates = $recipeIds.Count
    loot_table_candidates = $lootIds.Count
    created_count = $createdFiles.Count
    adopted_count = $adoptedFiles.Count
    already_managed_count = $alreadyManaged.Count
    skipped_existing_count = $skippedExisting.Count
    created_files = $createdFiles.ToArray()
    adopted_files = $adoptedFiles.ToArray()
    already_managed_files = $alreadyManaged.ToArray()
    skipped_existing_files = $skippedExisting.ToArray()
}

$manifestOutPath = Join-Path $manifestDir ("manifest_{0}.json" -f $stamp)
if (-not $DryRun) {
    $manifestObject | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestOutPath -Encoding UTF8
}

$summary = [PSCustomObject]@{
    timestamp_utc = $manifestObject.timestamp_utc
    mode = "apply"
    dry_run = [bool]$DryRun
    instance_name = $InstanceName
    kubejs_root = $KubeJsRoot
    logs = $resolvedLogs
    recipe_candidates = $recipeIds.Count
    loot_table_candidates = $lootIds.Count
    created_count = $createdFiles.Count
    adopted_count = $adoptedFiles.Count
    already_managed_count = $alreadyManaged.Count
    skipped_existing_count = $skippedExisting.Count
    manifest_path = if ($DryRun) { "" } else { (Resolve-Path -LiteralPath $manifestOutPath).Path }
    created_files = $createdFiles.ToArray()
    adopted_files = $adoptedFiles.ToArray()
    already_managed_files = $alreadyManaged.ToArray()
    skipped_existing_files = $skippedExisting.ToArray()
}

$reportJsonPath = Join-Path $OutDir ("modpack_quarantine_apply_{0}.json" -f $stamp)
$reportMdPath = Join-Path $OutDir ("modpack_quarantine_apply_{0}.md" -f $stamp)
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportJsonPath -Encoding UTF8

$md = New-Object System.Collections.Generic.List[string]
$md.Add("# Modpack Data Quarantine Apply")
$md.Add("")
$md.Add(("- Timestamp UTC: {0}" -f $summary.timestamp_utc))
$md.Add(("- Instance: {0}" -f $summary.instance_name))
$md.Add(("- Dry run: {0}" -f $summary.dry_run))
$md.Add(("- KubeJS root: {0}" -f $summary.kubejs_root))
$md.Add(("- Logs: {0}" -f ($summary.logs -join " | ")))
$md.Add(("- Recipe candidates: {0}" -f $summary.recipe_candidates))
$md.Add(("- Loot table candidates: {0}" -f $summary.loot_table_candidates))
$md.Add(("- Created overrides: {0}" -f $summary.created_count))
$md.Add(("- Adopted existing overrides: {0}" -f $summary.adopted_count))
$md.Add(("- Already managed: {0}" -f $summary.already_managed_count))
$md.Add(("- Skipped existing: {0}" -f $summary.skipped_existing_count))
if (-not [string]::IsNullOrWhiteSpace($summary.manifest_path)) {
    $md.Add(("- Manifest: {0}" -f $summary.manifest_path))
}
$md.Add("")
$md.Add(("- JSON: {0}" -f (Resolve-Path -LiteralPath $reportJsonPath).Path))
$md | Set-Content -LiteralPath $reportMdPath -Encoding UTF8

Write-Host ""
Write-Host "PauC modpack quarantine (apply)"
Write-Host "-------------------------------"
Write-Host ""
$summary | Format-List
Write-Host ""
Write-Host ("Report MD:  {0}" -f (Resolve-Path -LiteralPath $reportMdPath).Path)
Write-Host ("Report JSON:{0}" -f (Resolve-Path -LiteralPath $reportJsonPath).Path)
if (-not $DryRun) {
    Write-Host ("Manifest:   {0}" -f (Resolve-Path -LiteralPath $manifestOutPath).Path)
}

if ($PassThru) {
    $summary
}
