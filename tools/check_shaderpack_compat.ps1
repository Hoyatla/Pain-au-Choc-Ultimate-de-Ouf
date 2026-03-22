param(
    [string]$ShaderpacksDir = "",
    [string]$OutCsvPath = "",
    [switch]$IncludeZip = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-DefaultShaderpacksDir {
    param([string]$RequestedPath)
    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        if (-not (Test-Path -LiteralPath $RequestedPath)) {
            throw "Shaderpacks directory not found: $RequestedPath"
        }
        return (Resolve-Path -LiteralPath $RequestedPath).Path
    }

    $runCandidate = Join-Path (Get-Location) "run\shaderpacks"
    if (Test-Path -LiteralPath $runCandidate) {
        return (Resolve-Path -LiteralPath $runCandidate).Path
    }

    $rootCandidate = Join-Path (Get-Location) "shaderpacks"
    if (Test-Path -LiteralPath $rootCandidate) {
        return (Resolve-Path -LiteralPath $rootCandidate).Path
    }

    throw "Shaderpacks directory not found. Expected .\\run\\shaderpacks or .\\shaderpacks, or provide -ShaderpacksDir."
}

function Normalize-PathSegments {
    param([string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return ""
    }

    $normalized = $PathValue.Replace('\', '/')
    $segments = @()
    foreach ($segment in $normalized.Split('/')) {
        if ([string]::IsNullOrWhiteSpace($segment) -or $segment -eq ".") {
            continue
        }
        if ($segment -eq "..") {
            if ($segments.Count -gt 0) {
                $segments = $segments[0..($segments.Count - 2)]
            }
            continue
        }
        $segments += $segment
    }

    return ($segments -join "/")
}

function Normalize-IncludePath {
    param([string]$IncludePath)
    $normalized = Normalize-PathSegments $IncludePath
    while ($normalized.StartsWith("/")) {
        $normalized = $normalized.Substring(1)
    }
    if ($normalized.StartsWith("shaders/")) {
        $normalized = $normalized.Substring("shaders/".Length)
    }
    return $normalized
}

function Resolve-IncludeCandidate {
    param(
        [string]$CurrentShaderFile,
        [string]$IncludePath
    )
    $currentDir = ""
    if ($CurrentShaderFile.Contains("/")) {
        $currentDir = $CurrentShaderFile.Substring(0, $CurrentShaderFile.LastIndexOf("/"))
    }
    if ([string]::IsNullOrWhiteSpace($currentDir)) {
        return Normalize-PathSegments $IncludePath
    }
    return Normalize-PathSegments ($currentDir + "/" + $IncludePath)
}

function Get-PackFromDirectory {
    param([System.IO.DirectoryInfo]$DirectoryInfo)
    $shadersPath = Join-Path $DirectoryInfo.FullName "shaders"
    if (-not (Test-Path -LiteralPath $shadersPath)) {
        return $null
    }

    $fileSet = [System.Collections.Generic.HashSet[string]]::new()
    $shaderFiles = New-Object System.Collections.Generic.List[object]
    $basePath = (Resolve-Path -LiteralPath $shadersPath).Path
    $basePrefixLength = $basePath.Length + 1

    Get-ChildItem -LiteralPath $basePath -Recurse -File | ForEach-Object {
        $relative = $_.FullName.Substring($basePrefixLength)
        $relative = Normalize-PathSegments $relative
        [void]$fileSet.Add($relative)
        if ($relative.EndsWith(".vsh") -or $relative.EndsWith(".fsh") -or $relative.EndsWith(".gsh")) {
            $content = Get-Content -LiteralPath $_.FullName -Raw
            $shaderFiles.Add([PSCustomObject]@{
                path = $relative
                content = $content
            })
        }
    }

    return [PSCustomObject]@{
        name = $DirectoryInfo.Name
        source = "directory"
        fileSet = $fileSet
        shaderFiles = $shaderFiles
    }
}

function Get-PackFromZip {
    param([System.IO.FileInfo]$FileInfo)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($FileInfo.FullName)
    try {
        $fileSet = [System.Collections.Generic.HashSet[string]]::new()
        $shaderFiles = New-Object System.Collections.Generic.List[object]
        $shaderRootPrefix = $null

        foreach ($entry in $archive.Entries) {
            if ([string]::IsNullOrWhiteSpace($entry.Name)) {
                continue
            }

            $entryPath = Normalize-PathSegments $entry.FullName
            if ([string]::IsNullOrWhiteSpace($entryPath)) {
                continue
            }

            $segments = $entryPath.Split('/')
            $shaderIndex = [Array]::IndexOf($segments, "shaders")
            if ($shaderIndex -lt 0 -or $shaderIndex -ge ($segments.Length - 1)) {
                continue
            }

            $candidatePrefix = if ($shaderIndex -eq 0) {
                ""
            } else {
                ($segments[0..($shaderIndex - 1)] -join "/")
            }

            if ($null -eq $shaderRootPrefix) {
                $shaderRootPrefix = $candidatePrefix
                continue
            }

            $currentDepth = if ([string]::IsNullOrWhiteSpace($shaderRootPrefix)) { 0 } else { $shaderRootPrefix.Split('/').Length }
            $candidateDepth = if ([string]::IsNullOrWhiteSpace($candidatePrefix)) { 0 } else { $candidatePrefix.Split('/').Length }
            if ($candidateDepth -lt $currentDepth) {
                $shaderRootPrefix = $candidatePrefix
            }
        }

        if ($null -eq $shaderRootPrefix) {
            return $null
        }

        $shaderPrefix = if ([string]::IsNullOrWhiteSpace($shaderRootPrefix)) {
            "shaders/"
        } else {
            (Normalize-PathSegments ($shaderRootPrefix + "/shaders/"))
        }

        foreach ($entry in $archive.Entries) {
            if ([string]::IsNullOrWhiteSpace($entry.Name)) {
                continue
            }

            $entryPath = Normalize-PathSegments $entry.FullName
            if (-not $entryPath.StartsWith($shaderPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
                continue
            }

            $relative = $entryPath.Substring($shaderPrefix.Length)
            if ([string]::IsNullOrWhiteSpace($relative)) {
                continue
            }
            [void]$fileSet.Add($relative)

            if ($relative.EndsWith(".vsh") -or $relative.EndsWith(".fsh") -or $relative.EndsWith(".gsh")) {
                $stream = $entry.Open()
                try {
                    $reader = New-Object System.IO.StreamReader($stream)
                    $content = $reader.ReadToEnd()
                    $shaderFiles.Add([PSCustomObject]@{
                        path = $relative
                        content = $content
                    })
                } finally {
                    $stream.Dispose()
                }
            }
        }

        if ($fileSet.Count -eq 0) {
            return $null
        }

        return [PSCustomObject]@{
            name = $FileInfo.Name
            source = "zip"
            fileSet = $fileSet
            shaderFiles = $shaderFiles
        }
    } finally {
        $archive.Dispose()
    }
}

function Has-ProgramPair {
    param(
        [System.Collections.Generic.HashSet[string]]$FileSet,
        [string]$BaseName
    )
    return $FileSet.Contains($BaseName + ".vsh") -and $FileSet.Contains($BaseName + ".fsh")
}

function Get-ProgramPairs {
    param([System.Collections.Generic.HashSet[string]]$FileSet)
    $vertexPrograms = @{}
    $fragmentPrograms = @{}

    foreach ($path in $FileSet) {
        if ($path.EndsWith(".vsh")) {
            $base = $path.Substring(0, $path.Length - 4)
            $vertexPrograms[$base] = $true
        } elseif ($path.EndsWith(".fsh")) {
            $base = $path.Substring(0, $path.Length - 4)
            $fragmentPrograms[$base] = $true
        }
    }

    $pairs = New-Object System.Collections.Generic.List[string]
    foreach ($base in $vertexPrograms.Keys) {
        if ($fragmentPrograms.ContainsKey($base)) {
            $pairs.Add($base)
        }
    }
    return $pairs
}

function Get-ProgramNameFromPair {
    param([string]$PairPath)
    if ([string]::IsNullOrWhiteSpace($PairPath)) {
        return ""
    }

    $normalized = $PairPath.Replace('\', '/')
    if ($normalized.Contains('/')) {
        return $normalized.Substring($normalized.LastIndexOf('/') + 1)
    }
    return $normalized
}

function Count-MissingIncludes {
    param(
        [System.Collections.Generic.List[object]]$ShaderFiles,
        [System.Collections.Generic.HashSet[string]]$FileSet
    )
    $regex = [regex]::new('^\s*#include\s+["<]([^">]+)[">]', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $missing = 0
    foreach ($shader in $ShaderFiles) {
        $lines = $shader.content -split "`n"
        foreach ($line in $lines) {
            $match = $regex.Match($line)
            if (-not $match.Success) {
                continue
            }

            $includePath = Normalize-IncludePath $match.Groups[1].Value
            $relativeCandidate = Resolve-IncludeCandidate -CurrentShaderFile $shader.path -IncludePath $includePath
            if ($FileSet.Contains($relativeCandidate) -or $FileSet.Contains($includePath)) {
                continue
            }

            $missing++
        }
    }
    return $missing
}

function Evaluate-Pack {
    param([object]$Pack)
    $pairs = Get-ProgramPairs -FileSet $Pack.fileSet
    $pairSet = [System.Collections.Generic.HashSet[string]]::new()
    $programNameSet = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($pair in $pairs) {
        [void]$pairSet.Add($pair)
        $programName = Get-ProgramNameFromPair -PairPath $pair
        if (-not [string]::IsNullOrWhiteSpace($programName)) {
            [void]$programNameSet.Add($programName)
        }
    }

    $hasCore = $programNameSet.Contains("gbuffers_terrain") -or $programNameSet.Contains("gbuffers_basic") -or $programNameSet.Contains("gbuffers_textured")
    $hasFinal = $programNameSet.Contains("final")
    $hasShadow = $programNameSet.Contains("shadow")

    $deferredPasses = 0
    $compositePasses = 0
    foreach ($programName in $programNameSet) {
        if ($programName -match '^deferred([0-9]+)?$') {
            $deferredPasses++
        }
        if ($programName -match '^composite([0-9]+)?$') {
            $compositePasses++
        }
    }

    $missingIncludes = Count-MissingIncludes -ShaderFiles $Pack.shaderFiles -FileSet $Pack.fileSet

    $strict = "ok"
    $balanced = "ok"
    $fast = "ok"
    $notes = New-Object System.Collections.Generic.List[string]

    if (-not $hasCore) {
        $strict = "fail"
        $balanced = "fail"
        $fast = "fail"
        $notes.Add("missing core gbuffer")
    }
    if (-not $hasFinal) {
        if ($strict -ne "fail") { $strict = "warn" }
        if ($balanced -ne "fail") { $balanced = "warn" }
        if ($fast -ne "fail") { $fast = "warn" }
        $notes.Add("missing final pass")
    }
    if ($missingIncludes -gt 0) {
        if ($strict -ne "fail") { $strict = "fail" }
        if ($balanced -ne "fail") { $balanced = "warn" }
        if ($fast -ne "fail") { $fast = "warn" }
        $notes.Add("missing includes=$missingIncludes")
    }
    if (-not $hasShadow) {
        $notes.Add("shadow absent")
    }
    if ($deferredPasses -gt 4 -or $compositePasses -gt 4) {
        if ($fast -ne "fail") { $fast = "warn" }
        $notes.Add("fast truncation likely")
    }

    return [PSCustomObject]@{
        pack = $Pack.name
        source = $Pack.source
        has_core_gbuffer = $hasCore
        has_final = $hasFinal
        has_shadow = $hasShadow
        deferred_passes = $deferredPasses
        composite_passes = $compositePasses
        include_missing = $missingIncludes
        strict_status = $strict
        balanced_status = $balanced
        fast_status = $fast
        notes = ($notes -join "; ")
    }
}

$resolvedDir = Resolve-DefaultShaderpacksDir -RequestedPath $ShaderpacksDir
$dirInfo = Get-Item -LiteralPath $resolvedDir

$results = New-Object System.Collections.Generic.List[object]
Get-ChildItem -LiteralPath $dirInfo.FullName | ForEach-Object {
    if ($_.PSIsContainer) {
        $pack = Get-PackFromDirectory -DirectoryInfo $_
        if ($pack -ne $null) {
            $results.Add((Evaluate-Pack -Pack $pack))
        }
        return
    }

    if ($IncludeZip -and $_.Extension.Equals(".zip", [System.StringComparison]::OrdinalIgnoreCase)) {
        $pack = Get-PackFromZip -FileInfo $_
        if ($pack -ne $null) {
            $results.Add((Evaluate-Pack -Pack $pack))
        }
    }
}

$sorted = $results | Sort-Object pack

Write-Host ""
Write-Host "Shaderpack compatibility check"
Write-Host "-----------------------------"
Write-Host "Directory: $resolvedDir"
Write-Host ""
$sorted | Format-Table -AutoSize

if (-not [string]::IsNullOrWhiteSpace($OutCsvPath)) {
    $exportExists = Test-Path -LiteralPath $OutCsvPath
    $sorted | Export-Csv -LiteralPath $OutCsvPath -NoTypeInformation -Append:$exportExists
    Write-Host ""
    Write-Host "Report appended to: $OutCsvPath"
}
