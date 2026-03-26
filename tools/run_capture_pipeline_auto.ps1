param(
    [string]$InstanceName = "",
    [string]$MetricsPath = "",
    [string]$PrismRoot = "$env:APPDATA\PrismLauncher\instances",
    [string]$JarPath = "",
    [string]$CandidateRoot = ".\run\beta_candidates",
    [string]$ReportsDir = ".\run\pauc_reports",
    [bool]$BuildJar = $true,
    [bool]$CopyJarToInstance = $true,
    [bool]$WaitForFreshMetrics = $true,
    [int]$CaptureWaitTimeoutMinutes = 45,
    [int]$CapturePollSeconds = 15,
    [int]$MinNewRows = 240,
    [bool]$RunPreflight = $true,
    [bool]$RunCandidate = $true,
    [bool]$RunAutopilot = $true,
    [bool]$StrictPreflight = $true,
    [int]$MetricsWarmupTrimSeconds = 120,
    [int]$MaxMetricsAgeMinutes = 240,
    [bool]$EnableStrictCiFailGates = $true,
    [bool]$AutopilotAllowOneShotMetricsSignatureReplay = $false,
    [string]$SummaryOutputPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($CaptureWaitTimeoutMinutes -lt 1) {
    throw "CaptureWaitTimeoutMinutes must be >= 1"
}
if ($CapturePollSeconds -lt 1) {
    throw "CapturePollSeconds must be >= 1"
}
if ($MinNewRows -lt 0) {
    throw "MinNewRows must be >= 0"
}
if ($MetricsWarmupTrimSeconds -lt 0) {
    throw "MetricsWarmupTrimSeconds must be >= 0"
}
if ($MaxMetricsAgeMinutes -lt 0) {
    throw "MaxMetricsAgeMinutes must be >= 0"
}

function Get-LastExitCodeOrZero {
    $exitVar = Get-Variable -Name LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue
    if ($null -eq $exitVar -or $null -eq $exitVar.Value) {
        return 0
    }
    return [int]$exitVar.Value
}

function Reset-LastExitCode {
    Set-Variable -Name LASTEXITCODE -Scope Global -Value 0
}

function Resolve-RepoRoot {
    param([string]$ScriptRoot)
    return (Resolve-Path -LiteralPath (Join-Path $ScriptRoot "..")).Path
}

function Convert-ToRepoRelativePath {
    param(
        [string]$PathValue,
        [string]$RepoRoot
    )

    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return "."
    }
    if (-not [System.IO.Path]::IsPathRooted($PathValue)) {
        return $PathValue
    }

    $repoFull = [System.IO.Path]::GetFullPath($RepoRoot).TrimEnd('\', '/')
    $pathFull = [System.IO.Path]::GetFullPath($PathValue)
    if (-not $pathFull.StartsWith($repoFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw ("Path must be under repo root for this pipeline: {0}" -f $PathValue)
    }

    $relative = $pathFull.Substring($repoFull.Length).TrimStart('\', '/')
    if ([string]::IsNullOrWhiteSpace($relative)) {
        return "."
    }
    return (".\{0}" -f ($relative -replace "/", "\"))
}

function Resolve-PathFromRepo {
    param(
        [string]$PathValue,
        [string]$RepoRoot,
        [string]$PathType
    )

    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return $null
    }

    $candidates = New-Object System.Collections.Generic.List[string]
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        $candidates.Add($PathValue)
    } else {
        $candidates.Add($PathValue)
        $candidates.Add((Join-Path $RepoRoot $PathValue))
    }

    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        if ($PathType -eq "Leaf" -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
        if ($PathType -eq "Container" -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    return $null
}

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

    $resolvedPath = (Resolve-Path -LiteralPath $CandidatePath).Path
    if ($Seen.Add($resolvedPath)) {
        $item = Get-Item -LiteralPath $resolvedPath
        $Buffer.Add([PSCustomObject]@{
                path = $resolvedPath
                source = $Source
                last_write_utc = $item.LastWriteTimeUtc
                size_bytes = [int64]$item.Length
            })
    }
}

function Resolve-LatestMetricsPath {
    param(
        [string]$RequestedMetricsPath,
        [string]$RepoRoot,
        [string]$PrismRootPath,
        [string]$PrismInstanceName
    )

    if (-not [string]::IsNullOrWhiteSpace($RequestedMetricsPath)) {
        $resolvedRequested = Resolve-PathFromRepo -PathValue $RequestedMetricsPath -RepoRoot $RepoRoot -PathType "Leaf"
        if ($null -ne $resolvedRequested) {
            return [PSCustomObject]@{
                resolved = $true
                metrics_path = $resolvedRequested
                source = "requested"
            }
        }
        throw "Requested metrics path not found: $RequestedMetricsPath"
    }

    $buffer = New-Object System.Collections.Generic.List[object]
    $seen = New-Object System.Collections.Generic.HashSet[string] ([System.StringComparer]::OrdinalIgnoreCase)

    $envMetricsPath = [Environment]::GetEnvironmentVariable("PAUC_METRICS_PATH")
    if (-not [string]::IsNullOrWhiteSpace($envMetricsPath)) {
        Add-MetricsCandidate -Buffer $buffer -Seen $seen -CandidatePath $envMetricsPath -Source "env:PAUC_METRICS_PATH"
    }

    Add-MetricsCandidate -Buffer $buffer -Seen $seen -CandidatePath ".\run\pauc_telemetry\runtime_metrics.csv" -Source "repo_relative"
    Add-MetricsCandidate -Buffer $buffer -Seen $seen -CandidatePath (Join-Path $RepoRoot "run\pauc_telemetry\runtime_metrics.csv") -Source "repo_absolute"

    if (-not [string]::IsNullOrWhiteSpace($PrismInstanceName)) {
        $instanceRoot = Join-Path $PrismRootPath $PrismInstanceName
        Add-MetricsCandidate -Buffer $buffer -Seen $seen -CandidatePath (Join-Path $instanceRoot ".minecraft\pauc_telemetry\runtime_metrics.csv") -Source ("prism:{0}" -f $PrismInstanceName)
        Add-MetricsCandidate -Buffer $buffer -Seen $seen -CandidatePath (Join-Path $instanceRoot "minecraft\pauc_telemetry\runtime_metrics.csv") -Source ("prism:{0}:legacy" -f $PrismInstanceName)
    }

    if (-not [string]::IsNullOrWhiteSpace($PrismRootPath) -and (Test-Path -LiteralPath $PrismRootPath -PathType Container)) {
        $instanceDirs = Get-ChildItem -LiteralPath $PrismRootPath -Directory -ErrorAction SilentlyContinue
        foreach ($instanceDir in $instanceDirs) {
            Add-MetricsCandidate -Buffer $buffer -Seen $seen -CandidatePath (Join-Path $instanceDir.FullName ".minecraft\pauc_telemetry\runtime_metrics.csv") -Source ("prism-scan:{0}" -f $instanceDir.Name)
            Add-MetricsCandidate -Buffer $buffer -Seen $seen -CandidatePath (Join-Path $instanceDir.FullName "minecraft\pauc_telemetry\runtime_metrics.csv") -Source ("prism-scan:{0}:legacy" -f $instanceDir.Name)
        }
    }

    if ($buffer.Count -eq 0) {
        return [PSCustomObject]@{
            resolved = $false
            metrics_path = ""
            source = ""
        }
    }

    $best = $buffer | Sort-Object -Property last_write_utc -Descending | Select-Object -First 1
    return [PSCustomObject]@{
        resolved = $true
        metrics_path = [string]$best.path
        source = [string]$best.source
    }
}

function Resolve-InstanceNameFromMetricsPath {
    param([string]$ResolvedMetricsPath)

    if ([string]::IsNullOrWhiteSpace($ResolvedMetricsPath)) {
        return ""
    }

    $match = [regex]::Match($ResolvedMetricsPath, "[\\/]instances[\\/](?<instance>[^\\/]+)[\\/]", [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $match.Success) {
        return ""
    }
    return [string]$match.Groups["instance"].Value
}

function Resolve-MinecraftDirPreferenceFromMetricsPath {
    param([string]$MetricsPath)

    if ([string]::IsNullOrWhiteSpace($MetricsPath)) {
        return ""
    }

    if ([regex]::IsMatch($MetricsPath, "[\\/]\\.minecraft[\\/]", [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
        return "dot"
    }
    if ([regex]::IsMatch($MetricsPath, "[\\/]minecraft[\\/]", [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
        return "legacy"
    }
    return ""
}

function Resolve-PrismInstanceMinecraftDir {
    param(
        [string]$PrismRootPath,
        [string]$PrismInstanceName,
        [string]$PreferredStyle
    )

    if ([string]::IsNullOrWhiteSpace($PrismRootPath) -or [string]::IsNullOrWhiteSpace($PrismInstanceName)) {
        return $null
    }

    $instanceRoot = Join-Path $PrismRootPath $PrismInstanceName
    $dotMinecraftDir = Join-Path $instanceRoot ".minecraft"
    $legacyMinecraftDir = Join-Path $instanceRoot "minecraft"
    $normalizedStyle = if ([string]::IsNullOrWhiteSpace($PreferredStyle)) { "" } else { $PreferredStyle.Trim().ToLowerInvariant() }

    if ($normalizedStyle -eq "dot" -and (Test-Path -LiteralPath $dotMinecraftDir -PathType Container)) {
        return $dotMinecraftDir
    }
    if ($normalizedStyle -eq "legacy" -and (Test-Path -LiteralPath $legacyMinecraftDir -PathType Container)) {
        return $legacyMinecraftDir
    }

    if (Test-Path -LiteralPath $dotMinecraftDir -PathType Container) {
        return $dotMinecraftDir
    }
    if (Test-Path -LiteralPath $legacyMinecraftDir -PathType Container) {
        return $legacyMinecraftDir
    }

    if ($normalizedStyle -eq "dot") {
        return $dotMinecraftDir
    }
    return $legacyMinecraftDir
}

function Get-MetricsSnapshot {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [PSCustomObject]@{
            exists = $false
            path = $Path
            last_write_utc = [datetime]::MinValue
            size_bytes = 0
            row_count = 0
        }
    }

    $item = Get-Item -LiteralPath $Path
    $lineCount = 0
    try {
        $lineCount = ([System.IO.File]::ReadLines($item.FullName) | Measure-Object -Line).Lines
    } catch {
        $lineCount = 0
    }

    $rowCount = if ($lineCount -gt 0) { [Math]::Max(0, $lineCount - 1) } else { 0 }
    return [PSCustomObject]@{
        exists = $true
        path = $item.FullName
        last_write_utc = $item.LastWriteTimeUtc
        size_bytes = [int64]$item.Length
        row_count = [int]$rowCount
    }
}

function Wait-ForFreshMetricsUpdate {
    param(
        [string]$MetricsFilePath,
        [int]$TimeoutMinutes,
        [int]$PollSeconds,
        [int]$RequiredNewRows,
        [object]$BaselineSnapshot
    )

    $deadline = (Get-Date).ToUniversalTime().AddMinutes($TimeoutMinutes)
    $lastStatusLogUtc = [datetime]::MinValue

    while ((Get-Date).ToUniversalTime() -lt $deadline) {
        Start-Sleep -Seconds $PollSeconds
        $current = Get-MetricsSnapshot -Path $MetricsFilePath
        if ($current.exists) {
            $newRows = [Math]::Max(0, [int]$current.row_count - [int]$BaselineSnapshot.row_count)
            $isUpdated = [datetime]$current.last_write_utc -gt [datetime]$BaselineSnapshot.last_write_utc
            if ($isUpdated -and $newRows -ge $RequiredNewRows) {
                return [PSCustomObject]@{
                    updated = $true
                    snapshot = $current
                    new_rows = $newRows
                }
            }
        }

        $nowUtc = (Get-Date).ToUniversalTime()
        if ($lastStatusLogUtc -eq [datetime]::MinValue -or ($nowUtc - $lastStatusLogUtc).TotalSeconds -ge 30) {
            $remaining = [Math]::Max(0, [int][Math]::Ceiling(($deadline - $nowUtc).TotalSeconds))
            Write-Host ("Waiting for fresh metrics... remaining={0}s" -f $remaining)
            $lastStatusLogUtc = $nowUtc
        }
    }

    return [PSCustomObject]@{
        updated = $false
        snapshot = (Get-MetricsSnapshot -Path $MetricsFilePath)
        new_rows = [Math]::Max(0, [int](Get-MetricsSnapshot -Path $MetricsFilePath).row_count - [int]$BaselineSnapshot.row_count)
    }
}

function Resolve-JarArtifactPath {
    param(
        [string]$RequestedJarPath,
        [string]$RepoRoot
    )

    if (-not [string]::IsNullOrWhiteSpace($RequestedJarPath)) {
        $resolvedRequested = Resolve-PathFromRepo -PathValue $RequestedJarPath -RepoRoot $RepoRoot -PathType "Leaf"
        if ($null -eq $resolvedRequested) {
            throw "Jar path not found: $RequestedJarPath"
        }
        return $resolvedRequested
    }

    $libsDir = Join-Path $RepoRoot "build\libs"
    if (-not (Test-Path -LiteralPath $libsDir -PathType Container)) {
        throw "build/libs directory not found. Run with -BuildJar true or pass -JarPath."
    }

    $jar = Get-ChildItem -LiteralPath $libsDir -File |
        Where-Object { $_.Name -like "pauc-ultimate-de-ouf-*-ultimate.jar" } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "No PauC jar artifact found under $libsDir"
    }

    return $jar.FullName
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-RepoRoot -ScriptRoot $scriptRoot
$resolvedCandidateRoot = Resolve-PathFromRepo -PathValue $CandidateRoot -RepoRoot $repoRoot -PathType "Container"
if ($null -eq $resolvedCandidateRoot) {
    $resolvedCandidateRoot = Join-Path $repoRoot "run\beta_candidates"
    New-Item -ItemType Directory -Path $resolvedCandidateRoot -Force | Out-Null
}
$resolvedReportsDir = Resolve-PathFromRepo -PathValue $ReportsDir -RepoRoot $repoRoot -PathType "Container"
if ($null -eq $resolvedReportsDir) {
    $resolvedReportsDir = Join-Path $repoRoot "run\pauc_reports"
    New-Item -ItemType Directory -Path $resolvedReportsDir -Force | Out-Null
}
$reportsDirForScripts = Convert-ToRepoRelativePath -PathValue $resolvedReportsDir -RepoRoot $repoRoot

$result = [ordered]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    repo_root = $repoRoot
    prism_root = $PrismRoot
    instance_name = ""
    metrics_path = ""
    metrics_source = ""
    metrics_rows_before = 0
    metrics_rows_after = 0
    metrics_new_rows = 0
    jar_path = ""
    jar_sha256 = ""
    jar_copied_to_mods = $false
    jar_deployed_path = ""
    preflight_executed = $false
    preflight_exit_code = 0
    preflight_report_path = ""
    candidate_executed = $false
    candidate_exit_code = 0
    candidate_dir = ""
    candidate_readiness_decision = ""
    autopilot_executed = $false
    autopilot_exit_code = 0
    autopilot_summary_path = ""
    autopilot_allow_one_shot_metrics_signature_replay = [bool]$AutopilotAllowOneShotMetricsSignatureReplay
    autopilot_failed = $false
    autopilot_failure_reason = ""
    autopilot_effective_decision = ""
    errors = @()
}

Push-Location $repoRoot
try {
    $metricsResolution = Resolve-LatestMetricsPath `
        -RequestedMetricsPath $MetricsPath `
        -RepoRoot $repoRoot `
        -PrismRootPath $PrismRoot `
        -PrismInstanceName $InstanceName
    if (-not [bool]$metricsResolution.resolved) {
        throw "Unable to resolve runtime_metrics.csv. Start a session once or pass -MetricsPath explicitly."
    }
    $resolvedMetricsPath = [string]$metricsResolution.metrics_path
    $result.metrics_path = $resolvedMetricsPath
    $result.metrics_source = [string]$metricsResolution.source

    if ([string]::IsNullOrWhiteSpace($InstanceName)) {
        $InstanceName = Resolve-InstanceNameFromMetricsPath -ResolvedMetricsPath $resolvedMetricsPath
    }
    $result.instance_name = $InstanceName

    $baselineMetrics = Get-MetricsSnapshot -Path $resolvedMetricsPath
    $result.metrics_rows_before = [int]$baselineMetrics.row_count

    if ($BuildJar) {
        Write-Host ""
        Write-Host "[1/6] Building jar..."
        Reset-LastExitCode
        & .\gradlew.bat jar
        $buildExit = Get-LastExitCodeOrZero
        if ($buildExit -ne 0) {
            throw "gradlew jar failed with exit code $buildExit"
        }
    }

    $resolvedJarPath = Resolve-JarArtifactPath -RequestedJarPath $JarPath -RepoRoot $repoRoot
    $result.jar_path = $resolvedJarPath
    $result.jar_sha256 = (Get-FileHash -LiteralPath $resolvedJarPath -Algorithm SHA256).Hash.ToUpperInvariant()

    if ($CopyJarToInstance) {
        if ([string]::IsNullOrWhiteSpace($InstanceName)) {
            throw "InstanceName could not be inferred from metrics path; pass -InstanceName explicitly to deploy jar."
        }
        $minecraftDirPreference = Resolve-MinecraftDirPreferenceFromMetricsPath -MetricsPath $resolvedMetricsPath
        $instanceMinecraftDir = Resolve-PrismInstanceMinecraftDir `
            -PrismRootPath $PrismRoot `
            -PrismInstanceName $InstanceName `
            -PreferredStyle $minecraftDirPreference
        if ($null -eq $instanceMinecraftDir -or [string]::IsNullOrWhiteSpace($instanceMinecraftDir)) {
            throw "Unable to resolve Prism instance minecraft directory."
        }
        $modsDir = Join-Path $instanceMinecraftDir "mods"
        New-Item -ItemType Directory -Path $modsDir -Force | Out-Null
        $deployedJarPath = Join-Path $modsDir (Split-Path -Path $resolvedJarPath -Leaf)
        Write-Host ""
        Write-Host "[2/6] Deploying jar to Prism instance..."
        Copy-Item -LiteralPath $resolvedJarPath -Destination $deployedJarPath -Force
        $result.jar_copied_to_mods = $true
        $result.jar_deployed_path = $deployedJarPath
    }

    if ($WaitForFreshMetrics) {
        Write-Host ""
        Write-Host "[3/6] Waiting for fresh gameplay metrics..."
        Write-Host ("Instance: {0}" -f $(if ([string]::IsNullOrWhiteSpace($InstanceName)) { "<unknown>" } else { $InstanceName }))
        Write-Host ("Metrics : {0}" -f $resolvedMetricsPath)
        Write-Host ("Target  : at least +{0} rows within {1} minutes" -f $MinNewRows, $CaptureWaitTimeoutMinutes)
        Write-Host "Play now (world loaded + heavy scene)."

        $waitResult = Wait-ForFreshMetricsUpdate `
            -MetricsFilePath $resolvedMetricsPath `
            -TimeoutMinutes $CaptureWaitTimeoutMinutes `
            -PollSeconds $CapturePollSeconds `
            -RequiredNewRows $MinNewRows `
            -BaselineSnapshot $baselineMetrics
        if (-not [bool]$waitResult.updated) {
            throw ("No fresh metrics detected in time (required +{0} rows)." -f $MinNewRows)
        }
    }

    $afterMetrics = Get-MetricsSnapshot -Path $resolvedMetricsPath
    $result.metrics_rows_after = [int]$afterMetrics.row_count
    $result.metrics_new_rows = [Math]::Max(0, [int]$afterMetrics.row_count - [int]$baselineMetrics.row_count)

    if ($RunPreflight) {
        Write-Host ""
        Write-Host "[4/6] Running phase6 preflight..."
        $result.preflight_executed = $true
        try {
            $preflightArgs = @{
                ReportDir = $reportsDirForScripts
                MetricsPath = $resolvedMetricsPath
                PrismRoot = $PrismRoot
                MetricsWarmupTrimSeconds = $MetricsWarmupTrimSeconds
                MaxMetricsAgeMinutes = $MaxMetricsAgeMinutes
                ReportAsJson = $true
            }
            if (-not [string]::IsNullOrWhiteSpace($InstanceName)) {
                $preflightArgs.PrismInstanceName = $InstanceName
            }
            if ($StrictPreflight) {
                $preflightArgs.StrictDocFreshness = $true
                $preflightArgs.StrictAbMatrix = $true
                $preflightArgs.StrictAbProgress = $true
                $preflightArgs.StrictDrsDeferredSafety = $true
                $preflightArgs.StrictSoakStability = $true
                $preflightArgs.StrictCompileWarnings = $true
                $preflightArgs.StrictMetricsFreshness = $true
                $preflightArgs.StrictKpiGate = $true
            }

            Reset-LastExitCode
            $null = @(& .\tools\run_phase6_preflight.ps1 @preflightArgs)
            $result.preflight_exit_code = Get-LastExitCodeOrZero
            if ($result.preflight_exit_code -ne 0) {
                throw ("run_phase6_preflight exited with code {0}" -f $result.preflight_exit_code)
            }
        } catch {
            $result.preflight_exit_code = if (Get-LastExitCodeOrZero -ne 0) { Get-LastExitCodeOrZero } else { 1 }
            $result.errors += ("preflight: {0}" -f $_.Exception.Message)
        }

        $latestPreflight = Get-ChildItem -LiteralPath $resolvedReportsDir -File -Filter "phase6_preflight_*.md" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1
        if ($null -ne $latestPreflight) {
            $result.preflight_report_path = $latestPreflight.FullName
        }
    }

    if ($RunCandidate) {
        Write-Host ""
        Write-Host "[5/6] Building strict beta candidate..."
        $result.candidate_executed = $true
        $candidateDirsBefore = @(Get-ChildItem -LiteralPath $resolvedCandidateRoot -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
        try {
            $candidateArgs = @{
                CandidateRoot = $resolvedCandidateRoot
                ReportsDir = $reportsDirForScripts
                MetricsPath = $resolvedMetricsPath
                PrismRoot = $PrismRoot
                MetricsWarmupTrimSeconds = $MetricsWarmupTrimSeconds
                MaxMetricsAgeMinutes = $MaxMetricsAgeMinutes
                StrictReadiness = $true
            }
            if (-not [string]::IsNullOrWhiteSpace($InstanceName)) {
                $candidateArgs.PrismInstanceName = $InstanceName
            }
            if ($StrictPreflight) {
                $candidateArgs.StrictPreflight = $true
            }

            Reset-LastExitCode
            $null = @(& .\tools\build_beta_candidate.ps1 @candidateArgs)
            $result.candidate_exit_code = Get-LastExitCodeOrZero
            if ($result.candidate_exit_code -ne 0) {
                throw ("build_beta_candidate exited with code {0}" -f $result.candidate_exit_code)
            }
        } catch {
            $result.candidate_exit_code = if (Get-LastExitCodeOrZero -ne 0) { Get-LastExitCodeOrZero } else { 1 }
            $result.errors += ("candidate: {0}" -f $_.Exception.Message)
        }

        $candidateDirsAfter = @(Get-ChildItem -LiteralPath $resolvedCandidateRoot -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
        $newCandidateDirs = @($candidateDirsAfter | Where-Object { $candidateDirsBefore -notcontains $_ })
        $candidateDirResolved = if ($newCandidateDirs.Count -gt 0) {
            $newCandidateDirs | Sort-Object -Descending | Select-Object -First 1
        } else {
            $latestCandidate = Get-ChildItem -LiteralPath $resolvedCandidateRoot -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                Select-Object -First 1
            if ($null -ne $latestCandidate) { $latestCandidate.FullName } else { "" }
        }
        $result.candidate_dir = $candidateDirResolved

        if (-not [string]::IsNullOrWhiteSpace($candidateDirResolved)) {
            $candidateManifestPath = Join-Path $candidateDirResolved "candidate_manifest.json"
            if (Test-Path -LiteralPath $candidateManifestPath -PathType Leaf) {
                try {
                    $candidateManifest = Get-Content -LiteralPath $candidateManifestPath -Raw | ConvertFrom-Json
                    if ($null -ne $candidateManifest.PSObject.Properties["readiness_decision"]) {
                        $result.candidate_readiness_decision = [string]$candidateManifest.readiness_decision
                    }
                } catch {
                    $result.errors += ("candidate_manifest_parse: {0}" -f $_.Exception.Message)
                }
            }
        }
    }

    if ($RunAutopilot) {
        Write-Host ""
        Write-Host "[6/6] Running roadmap autopilot..."
        $result.autopilot_executed = $true
        $summaryPathResolved = $SummaryOutputPath
        if ([string]::IsNullOrWhiteSpace($summaryPathResolved)) {
            $stamp = Get-Date -Format "yyyyMMdd_HHmmss"
            $summaryPathResolved = Join-Path $resolvedReportsDir ("autopilot_summary_capture_auto_{0}.json" -f $stamp)
        } elseif (-not [System.IO.Path]::IsPathRooted($summaryPathResolved)) {
            $summaryPathResolved = Join-Path $repoRoot $summaryPathResolved
        }
        $summaryParentDir = Split-Path -Path $summaryPathResolved -Parent
        if (-not [string]::IsNullOrWhiteSpace($summaryParentDir)) {
            New-Item -ItemType Directory -Path $summaryParentDir -Force | Out-Null
        }

        try {
            $autopilotArgs = @{
                OneShot = $true
                MetricsPath = $resolvedMetricsPath
                PrismRoot = $PrismRoot
                SummaryOutputPath = $summaryPathResolved
                SummaryOutputCompress = $true
            }
            if (-not [string]::IsNullOrWhiteSpace($InstanceName)) {
                $autopilotArgs.InstanceName = $InstanceName
            }
            if ($EnableStrictCiFailGates) {
                $autopilotArgs.EnableStrictCiFailGates = $true
            }
            if ($AutopilotAllowOneShotMetricsSignatureReplay) {
                $autopilotArgs.AllowOneShotMetricsSignatureReplay = $true
            }

            Reset-LastExitCode
            $null = @(& .\tools\run_roadmap_autopilot.ps1 @autopilotArgs)
            $result.autopilot_exit_code = Get-LastExitCodeOrZero
        } catch {
            $result.autopilot_exit_code = if (Get-LastExitCodeOrZero -ne 0) { Get-LastExitCodeOrZero } else { 1 }
            $result.errors += ("autopilot: {0}" -f $_.Exception.Message)
        }

        $result.autopilot_summary_path = $summaryPathResolved
        if (Test-Path -LiteralPath $summaryPathResolved -PathType Leaf) {
            try {
                $summaryJson = Get-Content -LiteralPath $summaryPathResolved -Raw | ConvertFrom-Json
                if ($null -ne $summaryJson.PSObject.Properties["autopilot_failed"]) {
                    $result.autopilot_failed = [bool]$summaryJson.autopilot_failed
                }
                if ($null -ne $summaryJson.PSObject.Properties["autopilot_failure_reason"]) {
                    $result.autopilot_failure_reason = [string]$summaryJson.autopilot_failure_reason
                }
                if ($null -ne $summaryJson.PSObject.Properties["effective_decision"]) {
                    $result.autopilot_effective_decision = [string]$summaryJson.effective_decision
                }
            } catch {
                $result.errors += ("autopilot_summary_parse: {0}" -f $_.Exception.Message)
            }
        }
    }

    Write-Host ""
    Write-Host "Capture pipeline summary"
    Write-Host "------------------------"
    Write-Host ("Instance: {0}" -f $(if ([string]::IsNullOrWhiteSpace($result.instance_name)) { "<unknown>" } else { $result.instance_name }))
    Write-Host ("Metrics : {0}" -f $result.metrics_path)
    Write-Host ("Rows    : before={0}, after={1}, new={2}" -f $result.metrics_rows_before, $result.metrics_rows_after, $result.metrics_new_rows)
    Write-Host ("Jar     : {0}" -f $result.jar_path)
    Write-Host ("Jar SHA : {0}" -f $result.jar_sha256)
    Write-Host ("Preflight exit : {0}" -f $result.preflight_exit_code)
    Write-Host ("Candidate exit : {0}" -f $result.candidate_exit_code)
    Write-Host ("Autopilot exit : {0}" -f $result.autopilot_exit_code)
    if (-not [string]::IsNullOrWhiteSpace($result.candidate_dir)) {
        Write-Host ("Candidate dir  : {0}" -f $result.candidate_dir)
    }
    if (-not [string]::IsNullOrWhiteSpace($result.autopilot_summary_path)) {
        Write-Host ("Autopilot json : {0}" -f $result.autopilot_summary_path)
    }
    if ($result.errors.Count -gt 0) {
        Write-Host "Errors:"
        foreach ($errorEntry in $result.errors) {
            Write-Host ("- {0}" -f $errorEntry)
        }
    } else {
        Write-Host "Errors: none"
    }

    if ($PassThru) {
        [PSCustomObject]$result
    }
} finally {
    Pop-Location
}
