param(
    [Parameter(Mandatory = $true)][string]$Scene,
    [Parameter(Mandatory = $true)][string]$Profile,
    [string]$Build = "pauc-2.0.0-ultimate",
    [string]$MetricsPath = ".\run\pauc_telemetry\runtime_metrics.csv",
    [string]$ResultsPath = ".\RESULTATS_TESTS_AB_PAUC.csv",
    [int]$LastSamples = 0,
    [double]$LastSeconds = 0.0,
    [string]$FromTimestamp = "",
    [string]$ToTimestamp = "",
    [switch]$DisableSceneAlias,
    [switch]$DisableProfileAlias,
    [switch]$ForceAppend,
    [int]$StutterCount = 0,
    [string]$VisualIssues = "",
    [string]$CrashOrError = "",
    [string]$PointEval = "",
    [string]$Decision = "",
    [string]$Notes = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function To-DoubleArray {
    param([object[]]$Values)
    return $Values | ForEach-Object {
        [double]::Parse($_.ToString(), [System.Globalization.CultureInfo]::InvariantCulture)
    }
}

function Get-Percentile {
    param(
        [double[]]$SortedValues,
        [double]$Percentile
    )
    if ($SortedValues.Count -eq 0) {
        return [double]::NaN
    }
    if ($SortedValues.Count -eq 1) {
        return $SortedValues[0]
    }

    $p = [Math]::Max(0.0, [Math]::Min(1.0, $Percentile / 100.0))
    $position = ($SortedValues.Count - 1) * $p
    $lowerIndex = [Math]::Floor($position)
    $upperIndex = [Math]::Ceiling($position)
    if ($lowerIndex -eq $upperIndex) {
        return $SortedValues[$lowerIndex]
    }

    $weight = $position - $lowerIndex
    return $SortedValues[$lowerIndex] + (($SortedValues[$upperIndex] - $SortedValues[$lowerIndex]) * $weight)
}

function Format-InvariantNumber {
    param(
        [double]$Value,
        [string]$Pattern = "0.###"
    )
    return $Value.ToString($Pattern, [System.Globalization.CultureInfo]::InvariantCulture)
}

function Is-Number {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $false
    }
    $parsed = 0.0
    return [double]::TryParse(
        $Value,
        [System.Globalization.NumberStyles]::Float,
        [System.Globalization.CultureInfo]::InvariantCulture,
        [ref]$parsed
    )
}

function Try-ParseInvariantDouble {
    param(
        [object]$Value,
        [ref]$Parsed
    )
    if ($null -eq $Value) {
        return $false
    }

    $raw = $Value.ToString().Trim()
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return $false
    }

    return [double]::TryParse(
        $raw,
        [System.Globalization.NumberStyles]::Float,
        [System.Globalization.CultureInfo]::InvariantCulture,
        $Parsed
    )
}

function Resolve-EffectiveFps {
    param([object]$Row)

    $fpsRaw = 0.0
    if (Try-ParseInvariantDouble -Value $Row.fps_raw -Parsed ([ref]$fpsRaw)) {
        if (-not [double]::IsNaN($fpsRaw) -and -not [double]::IsInfinity($fpsRaw) -and $fpsRaw -gt 0.0) {
            return [PSCustomObject]@{
                valid = $true
                fps = $fpsRaw
                source = "fps_raw"
            }
        }
    }

    $frameMs = 0.0
    if (Try-ParseInvariantDouble -Value $Row.frame_ms -Parsed ([ref]$frameMs)) {
        if (-not [double]::IsNaN($frameMs) -and -not [double]::IsInfinity($frameMs) -and $frameMs -gt 0.0) {
            $derivedFps = 1000.0 / $frameMs
            if (-not [double]::IsNaN($derivedFps) -and -not [double]::IsInfinity($derivedFps) -and $derivedFps -gt 0.0) {
                return [PSCustomObject]@{
                    valid = $true
                    fps = $derivedFps
                    source = "frame_ms"
                }
            }
        }
    }

    return [PSCustomObject]@{
        valid = $false
        fps = 0.0
        source = "none"
    }
}

function Resolve-SceneName {
    param(
        [string]$InputName,
        [bool]$EnableAlias
    )
    if (-not $EnableAlias) {
        return $InputName
    }

    $normalized = $InputName.ToLowerInvariant()
    $map = @{
        "scene_1_village" = "scene_1_village"
        "scene1_village" = "scene_1_village"
        "scene1" = "scene_1_village"
        "village" = "scene_1_village"
        "scene_2_fast_move" = "scene_2_fast_move"
        "scene2_fast_move" = "scene_2_fast_move"
        "scene2" = "scene_2_fast_move"
        "fast_move" = "scene_2_fast_move"
        "scene_3_combat_particles" = "scene_3_combat_particles"
        "scene3_combat_particles" = "scene_3_combat_particles"
        "scene3" = "scene_3_combat_particles"
        "combat_particles" = "scene_3_combat_particles"
        "scene_4_modded_base" = "scene_4_modded_base"
        "scene4_modded_base" = "scene_4_modded_base"
        "scene4" = "scene_4_modded_base"
        "modded_base" = "scene_4_modded_base"
    }

    if ($map.ContainsKey($normalized)) {
        return $map[$normalized]
    }
    return $InputName
}

function Resolve-ProfileName {
    param(
        [string]$InputName,
        [bool]$EnableAlias
    )
    if (-not $EnableAlias) {
        return $InputName
    }

    $normalized = $InputName.ToLowerInvariant()
    $map = @{
        "a" = "A_baseline"
        "a_baseline" = "A_baseline"
        "baseline" = "A_baseline"
        "baseline_off" = "A_baseline"
        "a_repeat" = "A_baseline_repeat"
        "a_baseline_repeat" = "A_baseline_repeat"
        "baseline_repeat" = "A_baseline_repeat"
        "b1" = "B1_stable"
        "b1_stable" = "B1_stable"
        "stable" = "B1_stable"
        "b2" = "B2_aggressive"
        "b2_aggressive" = "B2_aggressive"
        "aggressive" = "B2_aggressive"
        "b_safe" = "B_safe"
        "safe" = "B_safe"
        "b_balanced" = "B_balanced"
        "balanced" = "B_balanced"
        "b_competitive240" = "B_competitive240"
        "competitive240" = "B_competitive240"
        "competitive" = "B_competitive240"
        "b_cinematic" = "B_cinematic"
        "cinematic" = "B_cinematic"
    }

    if ($map.ContainsKey($normalized)) {
        return $map[$normalized]
    }
    return $InputName
}

function Try-ParseTimestamp {
    param([string]$Value)
    $parsed = [DateTimeOffset]::MinValue
    $styles = [System.Globalization.DateTimeStyles]::AssumeLocal
    if ([DateTimeOffset]::TryParse(
            $Value,
            [System.Globalization.CultureInfo]::InvariantCulture,
            $styles,
            [ref]$parsed
        )) {
        return $parsed
    }
    return $null
}

if ($LastSamples -lt 0) {
    throw "LastSamples must be >= 0"
}
if ($LastSeconds -lt 0.0) {
    throw "LastSeconds must be >= 0"
}

if (-not (Test-Path -LiteralPath $MetricsPath)) {
    throw "Metrics file not found: $MetricsPath"
}

$rows = @(Import-Csv -LiteralPath $MetricsPath)
if (-not $rows -or $rows.Count -eq 0) {
    throw "Metrics file is empty: $MetricsPath"
}

$resolvedScene = Resolve-SceneName -InputName $Scene -EnableAlias:(-not $DisableSceneAlias)
$resolvedProfile = Resolve-ProfileName -InputName $Profile -EnableAlias:(-not $DisableProfileAlias)

$workingRows = @($rows)

if (-not [string]::IsNullOrWhiteSpace($FromTimestamp)) {
    $fromTs = Try-ParseTimestamp -Value $FromTimestamp
    if ($null -eq $fromTs) {
        throw "Invalid FromTimestamp value: $FromTimestamp"
    }
    $workingRows = @($workingRows | Where-Object {
            $rowTs = Try-ParseTimestamp -Value $_.timestamp
            $null -ne $rowTs -and $rowTs -ge $fromTs
        })
}

if (-not [string]::IsNullOrWhiteSpace($ToTimestamp)) {
    $toTs = Try-ParseTimestamp -Value $ToTimestamp
    if ($null -eq $toTs) {
        throw "Invalid ToTimestamp value: $ToTimestamp"
    }
    $workingRows = @($workingRows | Where-Object {
            $rowTs = Try-ParseTimestamp -Value $_.timestamp
            $null -ne $rowTs -and $rowTs -le $toTs
        })
}

if ($LastSeconds -gt 0.0) {
    $sessionValues = New-Object System.Collections.Generic.List[double]
    foreach ($row in $workingRows) {
        $session = 0.0
        if ([double]::TryParse(
                $row.session_seconds,
                [System.Globalization.NumberStyles]::Float,
                [System.Globalization.CultureInfo]::InvariantCulture,
                [ref]$session
            )) {
            $sessionValues.Add($session)
        }
    }

    if ($sessionValues.Count -eq 0) {
        throw "No valid session_seconds values found for LastSeconds filtering."
    }

    $maxSession = ($sessionValues | Measure-Object -Maximum).Maximum
    $threshold = $maxSession - $LastSeconds
    $workingRows = @($workingRows | Where-Object {
            $session = 0.0
            [double]::TryParse(
                $_.session_seconds,
                [System.Globalization.NumberStyles]::Float,
                [System.Globalization.CultureInfo]::InvariantCulture,
                [ref]$session
            ) -and $session -ge $threshold
        })
}

if ($LastSamples -gt 0 -and $workingRows.Count -gt $LastSamples) {
    $workingRows = @($workingRows | Select-Object -Last $LastSamples)
}

if ($workingRows.Count -eq 0) {
    throw "No metric rows selected after filters."
}

$fpsList = New-Object System.Collections.Generic.List[double]
$rawFpsCount = 0
$derivedFpsCount = 0
$discardedFpsCount = 0
foreach ($row in $workingRows) {
    $fpsInfo = Resolve-EffectiveFps -Row $row
    if ($fpsInfo.valid) {
        $fpsList.Add([double]$fpsInfo.fps)
        if ($fpsInfo.source -eq "fps_raw") {
            $rawFpsCount++
        } else {
            $derivedFpsCount++
        }
    } else {
        $discardedFpsCount++
    }
}

if ($fpsList.Count -eq 0) {
    throw "No valid fps values found in selected metric rows (fps_raw/frame_ms)."
}

$fpsRaw = [double[]]$fpsList
[Array]::Sort($fpsRaw)
$fpsAvg = [Math]::Round((($fpsRaw | Measure-Object -Average).Average), 3)
$fps1pctLow = [Math]::Round((Get-Percentile -SortedValues $fpsRaw -Percentile 1), 3)

$windowStart = $workingRows[0].timestamp
$windowEnd = $workingRows[$workingRows.Count - 1].timestamp

$resultRow = [PSCustomObject]@{
    date = (Get-Date -Format "yyyy-MM-dd")
    build = $Build
    scene = $resolvedScene
    profile = $resolvedProfile
    fps_avg = (Format-InvariantNumber -Value $fpsAvg)
    fps_1pct_low = (Format-InvariantNumber -Value $fps1pctLow)
    stutter_count = $StutterCount
    visual_issues = $VisualIssues
    crash_or_error = $CrashOrError
    point_eval = $PointEval
    decision = $Decision
    notes = $Notes
}

$resultsExists = Test-Path -LiteralPath $ResultsPath
$writeMode = "append"

if (-not $resultsExists) {
    $resultRow | Export-Csv -LiteralPath $ResultsPath -NoTypeInformation
    $writeMode = "create"
} elseif ($ForceAppend) {
    $resultRow | Export-Csv -LiteralPath $ResultsPath -NoTypeInformation -Append
    $writeMode = "append"
} else {
    $existingRows = @(Import-Csv -LiteralPath $ResultsPath)
    $targetIndex = -1

    for ($i = 0; $i -lt $existingRows.Count; $i++) {
        if ($existingRows[$i].scene -eq $resolvedScene -and $existingRows[$i].profile -eq $resolvedProfile) {
            if (-not (Is-Number $existingRows[$i].fps_avg)) {
                $targetIndex = $i
                break
            }
            if ($targetIndex -lt 0) {
                $targetIndex = $i
            }
        }
    }

    if ($targetIndex -ge 0) {
        $target = $existingRows[$targetIndex]
        $target.date = $resultRow.date
        $target.build = $resultRow.build
        $target.scene = $resultRow.scene
        $target.profile = $resultRow.profile
        $target.fps_avg = $resultRow.fps_avg
        $target.fps_1pct_low = $resultRow.fps_1pct_low
        $target.stutter_count = $resultRow.stutter_count
        $target.visual_issues = $resultRow.visual_issues
        $target.crash_or_error = $resultRow.crash_or_error
        $target.point_eval = $resultRow.point_eval
        $target.decision = $resultRow.decision
        $target.notes = $resultRow.notes
        $writeMode = "upsert"
    } else {
        $existingRows += $resultRow
        $writeMode = "append"
    }

    $existingRows | Export-Csv -LiteralPath $ResultsPath -NoTypeInformation
}

Write-Host ""
Write-Host "A/B row written:"
$resultRow | Format-List
Write-Host "Write mode: $writeMode"
Write-Host ("Metrics rows used: {0}/{1}" -f $workingRows.Count, $rows.Count)
Write-Host ("FPS source rows: raw={0}, derived_from_frame_ms={1}, discarded={2}" -f $rawFpsCount, $derivedFpsCount, $discardedFpsCount)
Write-Host ("Metrics window: {0} -> {1}" -f $windowStart, $windowEnd)
Write-Host "Results file: $ResultsPath"
