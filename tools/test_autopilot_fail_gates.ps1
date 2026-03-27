param(
    [string]$AutopilotScriptPath = "",
    [string]$ReportsRoot = ".\run\pauc_reports",
    [string]$PrismRoot = "",
    [string]$InstanceName = "ci_selftest_missing_logs",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($AutopilotScriptPath)) {
    $AutopilotScriptPath = Join-Path $PSScriptRoot "run_roadmap_autopilot.ps1"
}
if (-not (Test-Path -LiteralPath $AutopilotScriptPath -PathType Leaf)) {
    throw "Autopilot script not found: $AutopilotScriptPath"
}
$selfTestRepoRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path

function Get-LastExitCodeOrZero {
    $exitVar = Get-Variable -Name LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue
    if ($null -eq $exitVar) {
        return 0
    }
    return [int]$exitVar.Value
}

function Reset-LastExitCode {
    Set-Variable -Name LASTEXITCODE -Scope Global -Value 0
}

function Get-ObjectPropertyValue {
    param(
        [object]$InputObject,
        [string]$PropertyName,
        [object]$DefaultValue = $null
    )

    if ($null -eq $InputObject -or [string]::IsNullOrWhiteSpace($PropertyName)) {
        return $DefaultValue
    }

    $property = $InputObject.PSObject.Properties[$PropertyName]
    if ($null -eq $property) {
        return $DefaultValue
    }

    if ($null -eq $property.Value) {
        return $DefaultValue
    }

    return $property.Value
}

function Get-ObjectStringArrayProperty {
    param(
        [object]$InputObject,
        [string]$PropertyName
    )

    $value = Get-ObjectPropertyValue -InputObject $InputObject -PropertyName $PropertyName -DefaultValue $null
    if ($null -eq $value) {
        return @()
    }

    if ($value -is [System.Array]) {
        return @($value | ForEach-Object { [string]$_ })
    }

    return @([string]$value)
}

function Get-InvalidSummaryPath {
    param(
        [string]$BaseDir,
        [string]$CaseName
    )

    if ([string]::IsNullOrWhiteSpace($BaseDir)) {
        return ".\run\pauc_reports\bad<summary>\summary.json"
    }
    $invalidDir = Join-Path $BaseDir ("bad<summary>_{0}" -f $CaseName)
    return (Join-Path $invalidDir "summary.json")
}

function Infer-FailureReasonFromException {
    param(
        [string]$ExceptionMessage
    )

    if ([string]::IsNullOrWhiteSpace($ExceptionMessage)) {
        return ""
    }

    $message = $ExceptionMessage.Trim()
    if ($message -like "*Final decision is pending_metrics*") {
        return "pending_metrics_decision"
    }
    if ($message -like "*Latest metrics freshness is*") {
        return "latest_metrics_not_fresh"
    }
    if ($message -like "*Effective decision is empty*") {
        return "missing_effective_decision"
    }
    if ($message -like "*Decision freshness is*") {
        return "effective_decision_not_fresh"
    }
    if ($message -like "*Decision source is*") {
        return "cached_decision_source_used"
    }
    if ($message -like "*Error sorting reports are missing*") {
        return "error_sorting_report_missing"
    }
    if ($message -like "*FailOnErrorSortingBlockingPatterns*") {
        return "error_sorting_blocking_patterns"
    }
    if ($message -like "*FailOnErrorSortingStatusNotPass*") {
        return "error_sorting_status_not_pass"
    }
    if ($message -like "*Error sorting known noise status is*FailOnErrorSortingNoiseFail*") {
        if ($message -like "*status is 'fail'*") {
            return "error_sorting_noise_fail"
        }
        return "error_sorting_noise_status_unavailable_for_fail"
    }
    if ($message -like "*FailOnErrorSortingNoiseWarn*") {
        if ($message -like "*warn*" -or $message -like "*fail*" -or $message -like "*error*") {
            return "error_sorting_noise_warn_or_worse"
        }
        return "error_sorting_noise_status_unavailable"
    }
    if ($message -like "*FailOnPrismJarSyncNotSynced*") {
        return "prism_jar_sync_not_synced"
    }
    if ($message -like "*Summary output write failed*") {
        return "summary_output_write_error"
    }
    if ($message -like "*Summary output was not produced*") {
        return "missing_summary_output"
    }
    if ($message -like "*Git context is unavailable*") {
        return "git_context_unavailable"
    }
    if ($message -like "*Git worktree is dirty*") {
        return "git_dirty_worktree"
    }
    if ($message -like "*Summary output integrity is incomplete*") {
        return "summary_integrity_missing"
    }
    if ($message -like "*FailOnEffectiveDecisionNotReadyForBeta*") {
        return "effective_decision_not_ready_for_beta"
    }
    if ($message -like "*Startup Prism sync was blocked*") {
        return "startup_sync_stale_cache_blocked"
    }

    return ""
}

function Initialize-PrismLogFixture {
    param(
        [string]$PrismRootPath,
        [string]$InstanceName,
        [string[]]$LatestLines,
        [string[]]$DebugLines,
        [string]$MinecraftDirName = "minecraft"
    )

    if ([string]::IsNullOrWhiteSpace($PrismRootPath) -or [string]::IsNullOrWhiteSpace($InstanceName)) {
        return
    }

    $normalizedMinecraftDirName = if ([string]::IsNullOrWhiteSpace($MinecraftDirName)) {
        "minecraft"
    } else {
        [string]$MinecraftDirName.Trim()
    }
    $instanceRoot = Join-Path $PrismRootPath $InstanceName
    $instanceMinecraftDir = Join-Path $instanceRoot $normalizedMinecraftDirName
    $logsDir = Join-Path $instanceMinecraftDir "logs"
    New-Item -Path $logsDir -ItemType Directory -Force | Out-Null

    $latestPath = Join-Path $logsDir "latest.log"
    $debugPath = Join-Path $logsDir "debug.log"

    $latestPayload = if ($null -eq $LatestLines -or @($LatestLines).Count -eq 0) {
        @("[00:00:00] [Render thread/INFO]: PauC self-test latest.log fixture")
    } else {
        @($LatestLines)
    }
    $debugPayload = if ($null -eq $DebugLines -or @($DebugLines).Count -eq 0) {
        @("[00:00:00] [Render thread/INFO]: PauC self-test debug.log fixture")
    } else {
        @($DebugLines)
    }

    $latestPayload | Set-Content -LiteralPath $latestPath -Encoding UTF8
    $debugPayload | Set-Content -LiteralPath $debugPath -Encoding UTF8
}

function Get-CaseLogStringArrayField {
    param(
        [string]$LogPath,
        [string]$FieldName
    )

    if ([string]::IsNullOrWhiteSpace($LogPath) -or [string]::IsNullOrWhiteSpace($FieldName)) {
        return @()
    }
    if (-not (Test-Path -LiteralPath $LogPath -PathType Leaf)) {
        return @()
    }

    $raw = ""
    try {
        $raw = Get-Content -LiteralPath $LogPath -Raw -ErrorAction Stop
    } catch {
        return @()
    }

    if ([string]::IsNullOrWhiteSpace($raw)) {
        return @()
    }
    $normalizedRaw = ($raw -replace "`r`n", "`n" -replace "`r", "`n")

    $pattern = "(?ms)^\s*{0}\s*:\s*\{{(?<payload>.*?)\}}" -f [regex]::Escape($FieldName)
    $match = [regex]::Match($normalizedRaw, $pattern)
    if (-not $match.Success) {
        return @()
    }

    $payload = [string]$match.Groups["payload"].Value
    if ([string]::IsNullOrWhiteSpace($payload)) {
        return @()
    }

    $normalizedPayload = ($payload -replace "[\r\n]+", " ")
    return @(
        $normalizedPayload.Split(",") |
            ForEach-Object { [string]$_.Trim() } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Sort-Object -Unique
    )
}

function Get-CaseLogScalarField {
    param(
        [string]$LogPath,
        [string]$FieldName
    )

    if ([string]::IsNullOrWhiteSpace($LogPath) -or [string]::IsNullOrWhiteSpace($FieldName)) {
        return ""
    }
    if (-not (Test-Path -LiteralPath $LogPath -PathType Leaf)) {
        return ""
    }

    $raw = ""
    try {
        $raw = Get-Content -LiteralPath $LogPath -Raw -ErrorAction Stop
    } catch {
        return ""
    }

    if ([string]::IsNullOrWhiteSpace($raw)) {
        return ""
    }
    $normalizedRaw = ($raw -replace "`r`n", "`n" -replace "`r", "`n")

    $pattern = "(?m)^[ \t]*{0}[ \t]*:[ \t]*(?<value>[^\r\n]*)$" -f [regex]::Escape($FieldName)
    $match = [regex]::Match($normalizedRaw, $pattern)
    if (-not $match.Success) {
        return ""
    }

    return [string]$match.Groups["value"].Value.Trim()
}

function Get-TriggeredToActiveFailGateMap {
    $map = [ordered]@{}
    $map["pending_metrics_decision"] = "pending_metrics_decision"
    $map["latest_metrics_not_fresh"] = "latest_metrics_not_fresh"
    $map["missing_effective_decision"] = "missing_effective_decision"
    $map["effective_decision_not_fresh"] = "effective_decision_not_fresh"
    $map["cached_decision_source_used"] = "cached_decision_source_used"
    $map["error_sorting_report_missing"] = "error_sorting_report_missing"
    $map["error_sorting_blocking_patterns"] = "error_sorting_blocking_patterns"
    $map["error_sorting_status_not_pass"] = "error_sorting_status_not_pass"
    $map["error_sorting_noise_fail"] = "error_sorting_noise_fail"
    $map["error_sorting_noise_status_unavailable_for_fail"] = "error_sorting_noise_fail"
    $map["error_sorting_noise_warn_or_worse"] = "error_sorting_noise_warn_or_worse"
    $map["error_sorting_noise_status_unavailable"] = "error_sorting_noise_warn_or_worse"
    $map["prism_jar_sync_not_synced"] = "prism_jar_sync_not_synced"
    $map["effective_decision_not_ready_for_beta"] = "effective_decision_not_ready_for_beta"
    $map["startup_sync_stale_cache_blocked"] = "startup_sync_stale_cache_blocked"
    $map["git_context_unavailable"] = "git_dirty_worktree"
    $map["git_dirty_worktree"] = "git_dirty_worktree"
    $map["summary_output_write_error"] = "summary_output_write_error"
    $map["missing_summary_output"] = "missing_summary_output"
    $map["summary_integrity_missing"] = "summary_integrity_missing"
    return $map
}

function Get-KnownTriggeredFailGateReasons {
    return @(
        "pending_metrics_decision",
        "latest_metrics_not_fresh",
        "missing_effective_decision",
        "effective_decision_not_fresh",
        "cached_decision_source_used",
        "error_sorting_report_missing",
        "error_sorting_blocking_patterns",
        "error_sorting_status_not_pass",
        "error_sorting_noise_fail",
        "error_sorting_noise_status_unavailable_for_fail",
        "error_sorting_noise_warn_or_worse",
        "error_sorting_noise_status_unavailable",
        "prism_jar_sync_not_synced",
        "effective_decision_not_ready_for_beta",
        "startup_sync_stale_cache_blocked",
        "git_context_unavailable",
        "git_dirty_worktree",
        "summary_output_write_error",
        "missing_summary_output",
        "summary_integrity_missing"
    )
}

function Get-FailureReasonsFromAutopilotScript {
    param(
        [string]$ScriptPath
    )

    if ([string]::IsNullOrWhiteSpace($ScriptPath)) {
        return @()
    }
    if (-not (Test-Path -LiteralPath $ScriptPath -PathType Leaf)) {
        return @()
    }

    $raw = ""
    try {
        $raw = Get-Content -LiteralPath $ScriptPath -Raw -ErrorAction Stop
    } catch {
        return @()
    }
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return @()
    }

    $pattern = '\$result\.autopilot_failure_reason\s*=\s*"(?<reason>[^"]*)"'
    $matches = [regex]::Matches($raw, $pattern)
    if ($matches.Count -eq 0) {
        return @()
    }

    $reasons = New-Object System.Collections.Generic.List[string]
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($match in $matches) {
        $reason = [string]$match.Groups["reason"].Value
        if ([string]::IsNullOrWhiteSpace($reason)) {
            continue
        }
        if ($seen.Add($reason)) {
            $reasons.Add($reason)
        }
    }

    return @($reasons.ToArray())
}

function ConvertTo-BooleanFromString {
    param(
        [string]$Value,
        [bool]$DefaultValue = $false
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $DefaultValue
    }

    $normalized = $Value.Trim().ToLowerInvariant()
    if ($normalized -eq "true" -or $normalized -eq "1" -or $normalized -eq "yes") {
        return $true
    }
    if ($normalized -eq "false" -or $normalized -eq "0" -or $normalized -eq "no") {
        return $false
    }

    return $DefaultValue
}

function New-CaseArtifactToken {
    param(
        [int]$CaseIndex,
        [string]$CaseName,
        [int]$MaxSlugLength = 24
    )

    $rawName = if ([string]::IsNullOrWhiteSpace($CaseName)) {
        "case"
    } else {
        $CaseName.Trim()
    }
    $safeSlug = ($rawName -replace "[^A-Za-z0-9_-]", "_")
    if ([string]::IsNullOrWhiteSpace($safeSlug)) {
        $safeSlug = "case"
    }
    if ($safeSlug.Length -gt $MaxSlugLength) {
        $safeSlug = $safeSlug.Substring(0, $MaxSlugLength)
    }

    $hashHex = "00000000"
    $sha256 = $null
    try {
        $sha256 = [System.Security.Cryptography.SHA256]::Create()
        $nameBytes = [System.Text.Encoding]::UTF8.GetBytes($rawName)
        $hashBytes = $sha256.ComputeHash($nameBytes)
        $hashHex = ([System.BitConverter]::ToString($hashBytes)).Replace("-", "").Substring(0, 8).ToLowerInvariant()
    } finally {
        if ($null -ne $sha256) {
            $sha256.Dispose()
        }
    }

    return ("c{0:D2}_{1}_{2}" -f $CaseIndex, $safeSlug, $hashHex)
}

function Assert-PathLengthWithinLimit {
    param(
        [string]$PathValue,
        [string]$Label,
        [int]$MaxLength = 240
    )

    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return
    }
    if ($PathValue.Length -le $MaxLength) {
        return
    }

    throw ("{0} path length {1} exceeds safe limit {2}: {3}" -f `
            $Label,
            $PathValue.Length,
            $MaxLength,
            $PathValue)
}

function Get-StringArrayDuplicates {
    param(
        [string[]]$Values
    )

    if ($null -eq $Values -or @($Values).Count -eq 0) {
        return @()
    }

    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
    $duplicates = New-Object System.Collections.Generic.List[string]
    foreach ($value in $Values) {
        $item = [string]$value
        if ([string]::IsNullOrWhiteSpace($item)) {
            continue
        }
        if (-not $seen.Add($item)) {
            if (-not ($duplicates -contains $item)) {
                $duplicates.Add($item)
            }
        }
    }

    return @($duplicates.ToArray())
}

if (-not (Test-Path -LiteralPath $ReportsRoot)) {
    New-Item -Path $ReportsRoot -ItemType Directory -Force | Out-Null
}

$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd_HHmmss_fff")
$sessionDir = Join-Path $ReportsRoot ("autopilot_fail_gate_selftest_{0}" -f $stamp)
New-Item -Path $sessionDir -ItemType Directory -Force | Out-Null

if ([string]::IsNullOrWhiteSpace($PrismRoot)) {
    # Intentionally absent path so error-sorting pass hits deterministic missing-log behavior.
    $PrismRoot = Join-Path $sessionDir "missing_prism_root"
}
$freshCachedCandidateTimestampUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$triggeredToActiveFailGateMap = Get-TriggeredToActiveFailGateMap
$knownTriggeredFailGateReasons = @(Get-KnownTriggeredFailGateReasons)
foreach ($knownReason in $knownTriggeredFailGateReasons) {
    if ([string]::IsNullOrWhiteSpace([string]$knownReason)) {
        continue
    }
    if (-not $triggeredToActiveFailGateMap.Contains([string]$knownReason)) {
        throw ("Triggered->active fail-gate map is missing reason: {0}" -f [string]$knownReason)
    }
    $mappedActiveGate = [string]$triggeredToActiveFailGateMap[[string]$knownReason]
    if ([string]::IsNullOrWhiteSpace($mappedActiveGate)) {
        throw ("Triggered->active fail-gate map has empty target for reason: {0}" -f [string]$knownReason)
    }
}
$mapKeys = @($triggeredToActiveFailGateMap.Keys | ForEach-Object { [string]$_ })
$unexpectedMapKeys = @($mapKeys | Where-Object { $knownTriggeredFailGateReasons -notcontains $_ })
if ($unexpectedMapKeys.Count -gt 0) {
    throw ("Triggered->active fail-gate map contains unknown reasons: {0}" -f (($unexpectedMapKeys | Sort-Object -Unique) -join ", "))
}
$autopilotFailureReasons = @(Get-FailureReasonsFromAutopilotScript -ScriptPath $AutopilotScriptPath)
if ($autopilotFailureReasons.Count -gt 0) {
    $missingKnownReasons = @($autopilotFailureReasons | Where-Object { $knownTriggeredFailGateReasons -notcontains [string]$_ })
    if ($missingKnownReasons.Count -gt 0) {
        throw ("Known fail-gate reason list is missing autopilot reasons: {0}" -f (($missingKnownReasons | Sort-Object -Unique) -join ", "))
    }
    $missingMapReasons = @($autopilotFailureReasons | Where-Object { -not $triggeredToActiveFailGateMap.Contains([string]$_) })
    if ($missingMapReasons.Count -gt 0) {
        throw ("Triggered->active fail-gate map is missing autopilot reasons: {0}" -f (($missingMapReasons | Sort-Object -Unique) -join ", "))
    }
}

$cases = @(
    [PSCustomObject]@{
        name = "baseline_no_fail_gates"
        gate_args = @{
            RunErrorSortingPass = $false
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = $null
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "blocking_gate_forces_error_sorting"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingBlockingPatterns = $true
        }
        expected_reason = "error_sorting_blocking_patterns"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_blocking_patterns = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_blocking_patterns"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @(
            "error_sorting_blocking_patterns"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "blocking_gate_pass_with_status_pass"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingBlockingPatterns = $true
        }
        prism_log_fixture = [ordered]@{
            latest_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture blocking pass"
            )
            debug_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture blocking pass debug"
            )
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_blocking_patterns = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_blocking_patterns"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @()
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "report_missing_gate_forces_error_sorting"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingReportMissing = $true
        }
        expected_reason = "error_sorting_report_missing"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_report_missing = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_report_missing"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @(
            "error_sorting_report_missing"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "report_missing_gate_pass_with_reports_present"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingReportMissing = $true
        }
        prism_log_fixture = [ordered]@{
            latest_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture report-missing pass"
            )
            debug_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture report-missing pass debug"
            )
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_report_missing = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_report_missing"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @()
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "status_gate_forces_error_sorting"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingStatusNotPass = $true
        }
        expected_reason = "error_sorting_status_not_pass"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_status_not_pass = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_status_not_pass"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @(
            "error_sorting_status_not_pass"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "status_gate_pass_with_status_pass"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingStatusNotPass = $true
        }
        prism_log_fixture = [ordered]@{
            latest_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture status pass"
            )
            debug_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture status pass debug"
            )
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_status_not_pass = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_status_not_pass"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @()
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "noise_warn_gate_forces_error_sorting"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingNoiseWarn = $true
        }
        expected_reason = "error_sorting_noise_warn_or_worse"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_noise_warn = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_noise_warn_or_worse"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @(
            "error_sorting_noise_warn_or_worse"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "noise_warn_gate_detects_real_noise_warn"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingNoiseWarn = $true
            ErrorSortingNoiseWarnHitsTotal = 1
            ErrorSortingNoiseFailHitsTotal = 2
        }
        prism_log_fixture = [ordered]@{
            latest_lines = @(
                "[00:00:00] [Render thread/WARN]: OpenGL debug message: id=150 type=ERROR source=API"
            )
            debug_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture noise-warn warn-status fail"
            )
        }
        expected_reason = "error_sorting_noise_warn_or_worse"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_noise_warn = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_noise_warn_or_worse"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @(
            "error_sorting_noise_warn_or_worse"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "noise_warn_gate_pass_with_noise_pass"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingNoiseWarn = $true
        }
        prism_log_fixture = [ordered]@{
            latest_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture noise-warn pass"
            )
            debug_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture noise-warn pass debug"
            )
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_noise_warn = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_noise_warn_or_worse"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @()
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "noise_fail_gate_forces_error_sorting"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingNoiseFail = $true
        }
        expected_reason = "error_sorting_noise_status_unavailable_for_fail"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_noise_fail = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_noise_fail"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @(
            "error_sorting_noise_status_unavailable_for_fail"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "noise_fail_gate_pass_with_noise_warn_status"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingNoiseFail = $true
            ErrorSortingNoiseWarnHitsTotal = 1
            ErrorSortingNoiseFailHitsTotal = 2
        }
        prism_log_fixture = [ordered]@{
            latest_lines = @(
                "[00:00:00] [Render thread/WARN]: OpenGL debug message: id=200 type=ERROR source=API"
            )
            debug_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture noise-fail warn-status pass"
            )
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_noise_fail = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_noise_fail"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @()
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "noise_fail_gate_detects_real_noise_fail"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingNoiseFail = $true
            ErrorSortingNoiseWarnHitsTotal = 1
            ErrorSortingNoiseFailHitsTotal = 2
        }
        prism_log_fixture = [ordered]@{
            latest_lines = @(
                "[00:00:00] [Render thread/WARN]: OpenGL debug message: id=100 type=ERROR source=API",
                "[00:00:01] [Render thread/WARN]: OpenGL debug message: id=101 type=ERROR source=API"
            )
            debug_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture noise-fail"
            )
        }
        expected_reason = "error_sorting_noise_fail"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_noise_fail = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_noise_fail"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @(
            "error_sorting_noise_fail"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "error_sorting_priority_report_missing_over_blocking_status_and_noise"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingReportMissing = $true
            FailOnErrorSortingBlockingPatterns = $true
            FailOnErrorSortingStatusNotPass = $true
            FailOnErrorSortingNoiseWarn = $true
            FailOnErrorSortingNoiseFail = $true
        }
        expected_reason = "error_sorting_report_missing"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_report_missing = $true
            fail_on_error_sorting_blocking_patterns = $true
            fail_on_error_sorting_status_not_pass = $true
            fail_on_error_sorting_noise_warn = $true
            fail_on_error_sorting_noise_fail = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_report_missing",
            "error_sorting_blocking_patterns",
            "error_sorting_status_not_pass",
            "error_sorting_noise_warn_or_worse",
            "error_sorting_noise_fail"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @(
            "error_sorting_report_missing",
            "error_sorting_blocking_patterns",
            "error_sorting_status_not_pass",
            "error_sorting_noise_status_unavailable_for_fail",
            "error_sorting_noise_warn_or_worse"
        )
        expected_triggered_fail_gates_in_order = @(
            "error_sorting_report_missing",
            "error_sorting_blocking_patterns",
            "error_sorting_status_not_pass",
            "error_sorting_noise_status_unavailable_for_fail",
            "error_sorting_noise_warn_or_worse"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "error_sorting_priority_blocking_over_status"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingBlockingPatterns = $true
            FailOnErrorSortingStatusNotPass = $true
        }
        prism_log_fixture = [ordered]@{
            latest_lines = @(
                "[00:00:00] [Server thread/ERROR]: Parsing error loading recipe pauc:test_recipe"
            )
            debug_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture blocking-vs-status"
            )
        }
        expected_reason = "error_sorting_blocking_patterns"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_blocking_patterns = $true
            fail_on_error_sorting_status_not_pass = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_blocking_patterns",
            "error_sorting_status_not_pass"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @(
            "error_sorting_blocking_patterns",
            "error_sorting_status_not_pass"
        )
        expected_triggered_fail_gates_in_order = @(
            "error_sorting_blocking_patterns",
            "error_sorting_status_not_pass"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "error_sorting_priority_status_over_noise_warn"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingStatusNotPass = $true
            FailOnErrorSortingNoiseWarn = $true
            ErrorSortingNoiseWarnHitsTotal = 1
            ErrorSortingNoiseFailHitsTotal = 2
        }
        prism_log_fixture = [ordered]@{
            latest_lines = @(
                "[00:00:00] [Server thread/ERROR]: Parsing error loading recipe pauc:test_recipe_status_vs_noise",
                "[00:00:01] [Render thread/WARN]: OpenGL debug message: id=250 type=ERROR source=API"
            )
            debug_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture status-vs-noise-warn"
            )
        }
        expected_reason = "error_sorting_status_not_pass"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_status_not_pass = $true
            fail_on_error_sorting_noise_warn = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_status_not_pass",
            "error_sorting_noise_warn_or_worse"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @(
            "error_sorting_status_not_pass",
            "error_sorting_noise_warn_or_worse"
        )
        expected_triggered_fail_gates_in_order = @(
            "error_sorting_status_not_pass",
            "error_sorting_noise_warn_or_worse"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "errsort_priority_noise_fail_over_warn"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingNoiseWarn = $true
            FailOnErrorSortingNoiseFail = $true
            ErrorSortingNoiseWarnHitsTotal = 1
            ErrorSortingNoiseFailHitsTotal = 2
        }
        prism_log_fixture = [ordered]@{
            latest_lines = @(
                "[00:00:00] [Render thread/WARN]: OpenGL debug message: id=300 type=ERROR source=API",
                "[00:00:01] [Render thread/WARN]: OpenGL debug message: id=301 type=ERROR source=API"
            )
            debug_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture noise-fail-vs-noise-warn"
            )
        }
        expected_reason = "error_sorting_noise_fail"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_noise_warn = $true
            fail_on_error_sorting_noise_fail = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_noise_warn_or_worse",
            "error_sorting_noise_fail"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @(
            "error_sorting_noise_fail",
            "error_sorting_noise_warn_or_worse"
        )
        expected_triggered_fail_gates_in_order = @(
            "error_sorting_noise_fail",
            "error_sorting_noise_warn_or_worse"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "errsort_priority_noise_unavail_over_warn"
        gate_args = @{
            RunErrorSortingPass = $false
            FailOnErrorSortingNoiseWarn = $true
            FailOnErrorSortingNoiseFail = $true
        }
        expected_reason = "error_sorting_noise_status_unavailable_for_fail"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $true
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_error_sorting_noise_warn = $true
            fail_on_error_sorting_noise_fail = $true
        }
        expected_active_fail_gates = @(
            "error_sorting_noise_warn_or_worse",
            "error_sorting_noise_fail"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $null
        expected_strict_ci_summary_output_compress_forced = $null
        expected_summary_output_compressed = $null
        expected_triggered_fail_gates = @(
            "error_sorting_noise_status_unavailable_for_fail",
            "error_sorting_noise_warn_or_worse"
        )
        expected_triggered_fail_gates_in_order = @(
            "error_sorting_noise_status_unavailable_for_fail",
            "error_sorting_noise_warn_or_worse"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "strict_bundle_enables_error_sorting_gates"
        gate_args = @{
            EnableStrictCiFailGates = $true
            StrictCiForceSummaryOutputCompress = $false
        }
        expected_reason = "pending_metrics_decision"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $true
        expected_fail_on_error_sorting_blocking_patterns = $true
        expected_fail_on_error_sorting_noise_fail = $true
        expected_fail_gate_flags = [ordered]@{
            fail_on_startup_sync_stale_cache_block = $true
            fail_on_pending_metrics_decision = $true
            fail_on_latest_metrics_not_fresh = $true
            fail_on_no_effective_decision = $true
            fail_on_effective_decision_not_ready_for_beta = $true
            fail_on_non_fresh_effective_decision = $true
            fail_on_cached_decision_source = $true
            fail_on_prism_jar_sync_not_synced = $true
            fail_on_summary_output_write_error = $true
            fail_on_missing_summary_output = $true
            fail_on_summary_integrity_missing = $true
            fail_on_git_dirty_worktree = $true
            fail_on_error_sorting_report_missing = $true
            fail_on_error_sorting_status_not_pass = $true
            fail_on_error_sorting_noise_warn = $true
            fail_on_error_sorting_blocking_patterns = $true
            fail_on_error_sorting_noise_fail = $true
        }
        expected_active_fail_gates = @(
            "pending_metrics_decision",
            "latest_metrics_not_fresh",
            "missing_effective_decision",
            "effective_decision_not_ready_for_beta",
            "effective_decision_not_fresh",
            "cached_decision_source_used",
            "prism_jar_sync_not_synced",
            "summary_output_write_error",
            "missing_summary_output",
            "summary_integrity_missing",
            "git_dirty_worktree",
            "startup_sync_stale_cache_blocked",
            "error_sorting_report_missing",
            "error_sorting_status_not_pass",
            "error_sorting_noise_warn_or_worse",
            "error_sorting_blocking_patterns",
            "error_sorting_noise_fail"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        required_triggered_fail_gates = @(
            "pending_metrics_decision",
            "latest_metrics_not_fresh",
            "missing_effective_decision",
            "effective_decision_not_fresh",
            "error_sorting_report_missing",
            "error_sorting_blocking_patterns",
            "error_sorting_status_not_pass",
            "error_sorting_noise_status_unavailable_for_fail",
            "error_sorting_noise_warn_or_worse",
            "git_dirty_worktree"
        )
        forbidden_triggered_fail_gates = @(
            "summary_integrity_missing",
            "missing_summary_output",
            "summary_output_write_error"
        )
        force_git_dirty_probe = $true
    },
    [PSCustomObject]@{
        name = "strict_bundle_defaults_summary_and_compress"
        gate_args = @{
            EnableStrictCiFailGates = $true
        }
        expected_reason = "pending_metrics_decision"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $true
        expected_fail_on_error_sorting_blocking_patterns = $true
        expected_fail_on_error_sorting_noise_fail = $true
        expected_fail_gate_flags = $null
        expected_active_fail_gates = @(
            "pending_metrics_decision",
            "latest_metrics_not_fresh",
            "missing_effective_decision",
            "effective_decision_not_ready_for_beta",
            "effective_decision_not_fresh",
            "cached_decision_source_used",
            "prism_jar_sync_not_synced",
            "summary_output_write_error",
            "missing_summary_output",
            "summary_integrity_missing",
            "git_dirty_worktree",
            "startup_sync_stale_cache_blocked",
            "error_sorting_report_missing",
            "error_sorting_status_not_pass",
            "error_sorting_noise_warn_or_worse",
            "error_sorting_blocking_patterns",
            "error_sorting_noise_fail"
        )
        use_explicit_summary_output_path = $false
        use_summary_path_as_strict_output = $true
        expected_strict_ci_summary_output_defaulted = $true
        expected_strict_ci_summary_output_compress_forced = $true
        expected_summary_output_compressed = $true
        required_triggered_fail_gates = @(
            "pending_metrics_decision",
            "latest_metrics_not_fresh",
            "missing_effective_decision",
            "effective_decision_not_fresh",
            "error_sorting_report_missing",
            "error_sorting_blocking_patterns",
            "error_sorting_status_not_pass",
            "error_sorting_noise_status_unavailable_for_fail",
            "error_sorting_noise_warn_or_worse",
            "git_dirty_worktree"
        )
        forbidden_triggered_fail_gates = @(
            "summary_integrity_missing",
            "missing_summary_output",
            "summary_output_write_error"
        )
        force_git_dirty_probe = $true
    },
    [PSCustomObject]@{
        name = "strict_bundle_git_context_unavailable"
        gate_args = @{
            EnableStrictCiFailGates = $true
            StrictCiForceSummaryOutputCompress = $false
        }
        expected_reason = "pending_metrics_decision"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $true
        expected_fail_on_error_sorting_blocking_patterns = $true
        expected_fail_on_error_sorting_noise_fail = $true
        expected_fail_gate_flags = $null
        expected_active_fail_gates = @(
            "pending_metrics_decision",
            "latest_metrics_not_fresh",
            "missing_effective_decision",
            "effective_decision_not_ready_for_beta",
            "effective_decision_not_fresh",
            "cached_decision_source_used",
            "prism_jar_sync_not_synced",
            "summary_output_write_error",
            "missing_summary_output",
            "summary_integrity_missing",
            "git_dirty_worktree",
            "startup_sync_stale_cache_blocked",
            "error_sorting_report_missing",
            "error_sorting_status_not_pass",
            "error_sorting_noise_warn_or_worse",
            "error_sorting_blocking_patterns",
            "error_sorting_noise_fail"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        required_triggered_fail_gates = @(
            "pending_metrics_decision",
            "latest_metrics_not_fresh",
            "missing_effective_decision",
            "effective_decision_not_fresh",
            "error_sorting_report_missing",
            "error_sorting_blocking_patterns",
            "error_sorting_status_not_pass",
            "error_sorting_noise_status_unavailable_for_fail",
            "error_sorting_noise_warn_or_worse",
            "git_context_unavailable"
        )
        forbidden_triggered_fail_gates = @(
            "summary_integrity_missing",
            "missing_summary_output",
            "summary_output_write_error",
            "git_dirty_worktree"
        )
        env_overrides = [ordered]@{
            PATH = ""
        }
    },
    [PSCustomObject]@{
        name = "missing_summary_output_gate_without_summary_path"
        gate_args = @{
            FailOnMissingSummaryOutput = $true
        }
        expected_reason = "missing_summary_output"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = @(
            "missing_summary_output"
        )
        use_explicit_summary_output_path = $false
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        expected_triggered_fail_gates = @(
            "missing_summary_output"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "missing_summary_output_gate_pass_with_summary_path"
        gate_args = @{
            FailOnMissingSummaryOutput = $true
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_missing_summary_output = $true
        }
        expected_active_fail_gates = @(
            "missing_summary_output"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "summary_write_error_gate_with_invalid_path"
        gate_args = @{
            FailOnSummaryOutputWriteError = $true
        }
        expected_reason = "summary_output_write_error"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = @(
            "summary_output_write_error"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        summary_output_path_mode = "invalid"
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        expected_triggered_fail_gates = @(
            "summary_output_write_error"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "summary_write_error_gate_pass_with_valid_path"
        gate_args = @{
            FailOnSummaryOutputWriteError = $true
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_summary_output_write_error = $true
        }
        expected_active_fail_gates = @(
            "summary_output_write_error"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "summary_write_error_gate_without_summary_path_pass"
        gate_args = @{
            FailOnSummaryOutputWriteError = $true
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_summary_output_write_error = $true
        }
        expected_active_fail_gates = @(
            "summary_output_write_error"
        )
        use_explicit_summary_output_path = $false
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        expected_triggered_fail_gates = @()
        forbidden_triggered_fail_gates = @(
            "summary_output_write_error"
        )
    },
    [PSCustomObject]@{
        name = "summary_integrity_gate_with_invalid_path"
        gate_args = @{
            FailOnSummaryIntegrityMissing = $true
        }
        expected_reason = "summary_integrity_missing"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = @(
            "summary_integrity_missing"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        summary_output_path_mode = "invalid"
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        expected_triggered_fail_gates = @(
            "summary_integrity_missing"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "summary_integrity_gate_pass_with_valid_path"
        gate_args = @{
            FailOnSummaryIntegrityMissing = $true
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_summary_integrity_missing = $true
        }
        expected_active_fail_gates = @(
            "summary_integrity_missing"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "summary_integrity_gate_without_summary_path_pass"
        gate_args = @{
            FailOnSummaryIntegrityMissing = $true
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = @(
            "summary_integrity_missing"
        )
        use_explicit_summary_output_path = $false
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "summary_gate_priority_write_error_over_missing_and_integrity"
        gate_args = @{
            FailOnSummaryOutputWriteError = $true
            FailOnMissingSummaryOutput = $true
            FailOnSummaryIntegrityMissing = $true
        }
        expected_reason = "summary_output_write_error"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = @(
            "summary_output_write_error",
            "missing_summary_output",
            "summary_integrity_missing"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        summary_output_path_mode = "invalid"
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        expected_triggered_fail_gates = @(
            "summary_output_write_error",
            "missing_summary_output",
            "summary_integrity_missing"
        )
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "summary_gate_priority_missing_over_integrity_without_path"
        gate_args = @{
            FailOnMissingSummaryOutput = $true
            FailOnSummaryIntegrityMissing = $true
        }
        expected_reason = "missing_summary_output"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = @(
            "missing_summary_output",
            "summary_integrity_missing"
        )
        use_explicit_summary_output_path = $false
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        expected_triggered_fail_gates = @(
            "missing_summary_output"
        )
        forbidden_triggered_fail_gates = @(
            "summary_integrity_missing"
        )
    },
    [PSCustomObject]@{
        name = "summary_gate_priority_missing_without_path_even_with_write_error"
        gate_args = @{
            FailOnSummaryOutputWriteError = $true
            FailOnMissingSummaryOutput = $true
            FailOnSummaryIntegrityMissing = $true
        }
        expected_reason = "missing_summary_output"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = @(
            "summary_output_write_error",
            "missing_summary_output",
            "summary_integrity_missing"
        )
        use_explicit_summary_output_path = $false
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        expected_triggered_fail_gates = @(
            "missing_summary_output"
        )
        forbidden_triggered_fail_gates = @(
            "summary_output_write_error",
            "summary_integrity_missing"
        )
    },
    [PSCustomObject]@{
        name = "pending_metrics_decision_gate"
        gate_args = @{
            FailOnPendingMetricsDecision = $true
        }
        expected_reason = "pending_metrics_decision"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = $null
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "no_effective_decision_gate"
        gate_args = @{
            FailOnNoEffectiveDecision = $true
        }
        expected_reason = "missing_effective_decision"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = $null
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "non_fresh_effective_decision_gate_from_cached_candidate"
        gate_args = @{
            FailOnNonFreshEffectiveDecision = $true
            MaxCachedCandidateAgeMinutes = 180
        }
        autopilot_state_fixture = [ordered]@{
            last_candidate_dir = ".\\run\\beta_candidates\\fixture_cached_candidate"
            last_candidate_decision = "ready_for_beta"
            last_candidate_readiness_percent = "100"
            last_candidate_timestamp_utc = $freshCachedCandidateTimestampUtc
        }
        expected_reason = "effective_decision_not_fresh"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = $null
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "cached_decision_source_gate_from_cached_candidate"
        gate_args = @{
            FailOnCachedDecisionSource = $true
            MaxCachedCandidateAgeMinutes = 180
        }
        autopilot_state_fixture = [ordered]@{
            last_candidate_dir = ".\\run\\beta_candidates\\fixture_cached_candidate"
            last_candidate_decision = "ready_for_beta"
            last_candidate_readiness_percent = "100"
            last_candidate_timestamp_utc = $freshCachedCandidateTimestampUtc
        }
        expected_reason = "cached_decision_source_used"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = $null
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "effective_decision_not_ready_gate_from_cached_candidate"
        gate_args = @{
            FailOnEffectiveDecisionNotReadyForBeta = $true
            MaxCachedCandidateAgeMinutes = 180
        }
        autopilot_state_fixture = [ordered]@{
            last_candidate_dir = ".\\run\\beta_candidates\\fixture_cached_candidate"
            last_candidate_decision = "not_ready"
            last_candidate_readiness_percent = "80"
            last_candidate_timestamp_utc = $freshCachedCandidateTimestampUtc
        }
        expected_reason = "effective_decision_not_ready_for_beta"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = $null
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "prism_sync_not_synced_gate"
        gate_args = @{
            FailOnPrismJarSyncNotSynced = $true
            AutoSyncModJarToPrism = $true
        }
        expected_reason = "prism_jar_sync_not_synced"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = $null
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "prism_sync_gate_pass_when_auto_sync_disabled"
        gate_args = @{
            FailOnPrismJarSyncNotSynced = $true
            AutoSyncModJarToPrism = $false
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_prism_jar_sync_not_synced = $true
        }
        expected_active_fail_gates = @(
            "prism_jar_sync_not_synced"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        expected_triggered_fail_gates = @()
        forbidden_triggered_fail_gates = @(
            "prism_jar_sync_not_synced"
        )
    },
    [PSCustomObject]@{
        name = "prism_sync_gate_pass_with_dot_minecraft_layout"
        gate_args = @{
            FailOnPrismJarSyncNotSynced = $true
            AutoSyncModJarToPrism = $true
            RunErrorSortingPass = $true
        }
        prism_log_fixture = [ordered]@{
            minecraft_dir_name = ".minecraft"
            latest_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture prism dot layout latest"
            )
            debug_lines = @(
                "[00:00:00] [Render thread/INFO]: PauC self-test fixture prism dot layout debug"
            )
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_prism_jar_sync_not_synced = $true
        }
        expected_active_fail_gates = @(
            "prism_jar_sync_not_synced"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        expected_triggered_fail_gates = @()
        forbidden_triggered_fail_gates = @(
            "prism_jar_sync_not_synced"
        )
    },
    [PSCustomObject]@{
        name = "startup_sync_stale_cache_block_gate"
        gate_args = @{
            FailOnStartupSyncStaleCacheBlock = $true
            AutoSyncModJarToPrism = $true
            MaxCachedCandidateAgeMinutes = 60
            EnforceFreshCachedCandidateForStartupSync = $true
        }
        autopilot_state_fixture = [ordered]@{
            last_candidate_dir = ".\\run\\beta_candidates\\fixture_cached_candidate_stale"
            last_candidate_decision = "ready_for_beta"
            last_candidate_readiness_percent = "100"
            last_candidate_timestamp_utc = "2020-01-01T00:00:00Z"
        }
        expected_reason = "startup_sync_stale_cache_blocked"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = $null
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "startup_sync_gate_pass_without_stale_cached_candidate"
        gate_args = @{
            FailOnStartupSyncStaleCacheBlock = $true
            AutoSyncModJarToPrism = $true
            MaxCachedCandidateAgeMinutes = 60
            EnforceFreshCachedCandidateForStartupSync = $true
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_startup_sync_stale_cache_block = $true
        }
        expected_active_fail_gates = @(
            "startup_sync_stale_cache_blocked"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        expected_triggered_fail_gates = @()
        forbidden_triggered_fail_gates = @(
            "startup_sync_stale_cache_blocked"
        )
    },
    [PSCustomObject]@{
        name = "startup_sync_gate_pass_when_freshness_enforcement_disabled"
        gate_args = @{
            FailOnStartupSyncStaleCacheBlock = $true
            AutoSyncModJarToPrism = $true
            MaxCachedCandidateAgeMinutes = 60
            EnforceFreshCachedCandidateForStartupSync = $false
        }
        autopilot_state_fixture = [ordered]@{
            last_candidate_dir = ".\\run\\beta_candidates\\fixture_cached_candidate_stale"
            last_candidate_decision = "ready_for_beta"
            last_candidate_readiness_percent = "100"
            last_candidate_timestamp_utc = "2020-01-01T00:00:00Z"
        }
        expected_reason = ""
        expect_failed = $false
        expect_exit_nonzero = $false
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = [ordered]@{
            fail_on_startup_sync_stale_cache_block = $true
        }
        expected_active_fail_gates = @(
            "startup_sync_stale_cache_blocked"
        )
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        expected_triggered_fail_gates = @()
        forbidden_triggered_fail_gates = @(
            "startup_sync_stale_cache_blocked"
        )
    },
    [PSCustomObject]@{
        name = "latest_metrics_not_fresh_gate"
        gate_args = @{
            FailOnLatestMetricsNotFresh = $true
        }
        expected_reason = "latest_metrics_not_fresh"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = $null
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        forbidden_triggered_fail_gates = $null
    },
    [PSCustomObject]@{
        name = "git_context_unavailable_gate"
        gate_args = @{
            FailOnGitDirtyWorktree = $true
        }
        expected_reason = "git_context_unavailable"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = $null
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        forbidden_triggered_fail_gates = $null
        env_overrides = [ordered]@{
            PATH = ""
        }
    },
    [PSCustomObject]@{
        name = "git_dirty_worktree_gate"
        gate_args = @{
            FailOnGitDirtyWorktree = $true
        }
        expected_reason = "git_dirty_worktree"
        expect_failed = $true
        expect_exit_nonzero = $true
        expect_forced_error_sorting = $false
        expected_strict_ci = $null
        expected_fail_on_error_sorting_blocking_patterns = $null
        expected_fail_on_error_sorting_noise_fail = $null
        expected_fail_gate_flags = $null
        expected_active_fail_gates = $null
        use_explicit_summary_output_path = $true
        use_summary_path_as_strict_output = $false
        expected_strict_ci_summary_output_defaulted = $false
        expected_strict_ci_summary_output_compress_forced = $false
        expected_summary_output_compressed = $false
        forbidden_triggered_fail_gates = $null
        force_git_dirty_probe = $true
    }
)

foreach ($case in $cases) {
    $caseNameForValidation = [string]$case.name
    if ([string]::IsNullOrWhiteSpace($caseNameForValidation)) {
        throw "Case definition contains an empty name."
    }
    if (-not ($case.PSObject.Properties.Name -contains "gate_args") -or $null -eq $case.gate_args) {
        throw ("Case '{0}' is missing gate_args." -f $caseNameForValidation)
    }
    if (-not ($case.gate_args -is [System.Collections.IDictionary])) {
        throw ("Case '{0}' gate_args must be a dictionary/hashtable." -f $caseNameForValidation)
    }

    $expectedReasonForValidation = [string](Get-ObjectPropertyValue -InputObject $case -PropertyName "expected_reason" -DefaultValue "")
    $expectFailedForValidation = [bool](Get-ObjectPropertyValue -InputObject $case -PropertyName "expect_failed" -DefaultValue $false)
    if ($expectFailedForValidation -and [string]::IsNullOrWhiteSpace($expectedReasonForValidation)) {
        throw ("Case '{0}' expects failure but expected_reason is empty." -f $caseNameForValidation)
    }
    if (-not $expectFailedForValidation -and -not [string]::IsNullOrWhiteSpace($expectedReasonForValidation)) {
        throw ("Case '{0}' expects success but expected_reason is not empty ('{1}')." -f $caseNameForValidation, $expectedReasonForValidation)
    }
    if (-not [string]::IsNullOrWhiteSpace($expectedReasonForValidation) -and `
            ($knownTriggeredFailGateReasons -notcontains $expectedReasonForValidation)) {
        throw ("Case '{0}' expected_reason '{1}' is not listed in known fail-gate reasons." -f $caseNameForValidation, $expectedReasonForValidation)
    }

    if ($case.PSObject.Properties.Name -contains "expected_active_fail_gates" -and `
            $null -ne $case.expected_active_fail_gates) {
        $expectedActiveForValidation = @($case.expected_active_fail_gates | ForEach-Object { [string]$_ })
        $duplicateExpectedActiveForValidation = @(Get-StringArrayDuplicates -Values $expectedActiveForValidation)
        if ($duplicateExpectedActiveForValidation.Count -gt 0) {
            throw ("Case '{0}' has duplicated expected_active_fail_gates: {1}" -f `
                    $caseNameForValidation,
                    (($duplicateExpectedActiveForValidation | Sort-Object -Unique) -join ", "))
        }
    }

    $expectedTriggeredForValidation = @()
    if ($case.PSObject.Properties.Name -contains "expected_triggered_fail_gates" -and `
            $null -ne $case.expected_triggered_fail_gates) {
        $expectedTriggeredForValidation = @($case.expected_triggered_fail_gates | ForEach-Object { [string]$_ })
        $duplicateExpectedTriggeredForValidation = @(Get-StringArrayDuplicates -Values $expectedTriggeredForValidation)
        if ($duplicateExpectedTriggeredForValidation.Count -gt 0) {
            throw ("Case '{0}' has duplicated expected_triggered_fail_gates: {1}" -f `
                    $caseNameForValidation,
                    (($duplicateExpectedTriggeredForValidation | Sort-Object -Unique) -join ", "))
        }
        foreach ($expectedTriggeredGateForValidation in $expectedTriggeredForValidation) {
            if ([string]::IsNullOrWhiteSpace($expectedTriggeredGateForValidation)) {
                continue
            }
            if (-not $triggeredToActiveFailGateMap.Contains($expectedTriggeredGateForValidation)) {
                throw ("Case '{0}' references unmapped expected_triggered_fail_gate '{1}'." -f `
                        $caseNameForValidation,
                        $expectedTriggeredGateForValidation)
            }
        }
        if (-not [string]::IsNullOrWhiteSpace($expectedReasonForValidation) -and `
                ($expectedTriggeredForValidation -notcontains $expectedReasonForValidation)) {
            throw ("Case '{0}' expected_reason '{1}' is missing from expected_triggered_fail_gates." -f `
                    $caseNameForValidation,
                    $expectedReasonForValidation)
        }
    }

    if ($case.PSObject.Properties.Name -contains "expected_triggered_fail_gates_in_order" -and `
            $null -ne $case.expected_triggered_fail_gates_in_order) {
        $expectedTriggeredInOrderForValidation = @($case.expected_triggered_fail_gates_in_order | ForEach-Object { [string]$_ })
        $duplicateExpectedTriggeredInOrderForValidation = @(Get-StringArrayDuplicates -Values $expectedTriggeredInOrderForValidation)
        if ($duplicateExpectedTriggeredInOrderForValidation.Count -gt 0) {
            throw ("Case '{0}' has duplicated expected_triggered_fail_gates_in_order: {1}" -f `
                    $caseNameForValidation,
                    (($duplicateExpectedTriggeredInOrderForValidation | Sort-Object -Unique) -join ", "))
        }
        if ($expectedTriggeredForValidation.Count -eq 0) {
            throw ("Case '{0}' defines expected_triggered_fail_gates_in_order without expected_triggered_fail_gates." -f `
                    $caseNameForValidation)
        }
        if ($expectedTriggeredInOrderForValidation.Count -ne $expectedTriggeredForValidation.Count) {
            throw ("Case '{0}' expected_triggered_fail_gates_in_order count ({1}) differs from expected_triggered_fail_gates count ({2})." -f `
                    $caseNameForValidation,
                    $expectedTriggeredInOrderForValidation.Count,
                    $expectedTriggeredForValidation.Count)
        }
        $missingInOrderForValidation = @($expectedTriggeredForValidation | Where-Object { $expectedTriggeredInOrderForValidation -notcontains $_ })
        if ($missingInOrderForValidation.Count -gt 0) {
            throw ("Case '{0}' expected_triggered_fail_gates_in_order is missing entries from expected_triggered_fail_gates: {1}" -f `
                    $caseNameForValidation,
                    (($missingInOrderForValidation | Sort-Object -Unique) -join ", "))
        }
        if ($expectFailedForValidation -and -not [string]::IsNullOrWhiteSpace($expectedReasonForValidation)) {
            if ($expectedTriggeredInOrderForValidation.Count -lt 1 -or `
                    [string]$expectedTriggeredInOrderForValidation[0] -ne $expectedReasonForValidation) {
                throw ("Case '{0}' expected_triggered_fail_gates_in_order must start with expected_reason '{1}'." -f `
                        $caseNameForValidation,
                        $expectedReasonForValidation)
            }
        }
    }

    if ($case.PSObject.Properties.Name -contains "required_triggered_fail_gates" -and `
            $null -ne $case.required_triggered_fail_gates) {
        $requiredTriggeredForValidation = @($case.required_triggered_fail_gates | ForEach-Object { [string]$_ })
        $duplicateRequiredTriggeredForValidation = @(Get-StringArrayDuplicates -Values $requiredTriggeredForValidation)
        if ($duplicateRequiredTriggeredForValidation.Count -gt 0) {
            throw ("Case '{0}' has duplicated required_triggered_fail_gates: {1}" -f `
                    $caseNameForValidation,
                    (($duplicateRequiredTriggeredForValidation | Sort-Object -Unique) -join ", "))
        }
        foreach ($requiredGateForValidation in $requiredTriggeredForValidation) {
            if ([string]::IsNullOrWhiteSpace($requiredGateForValidation)) {
                continue
            }
            if (-not $triggeredToActiveFailGateMap.Contains($requiredGateForValidation)) {
                throw ("Case '{0}' references unmapped required_triggered_fail_gate '{1}'." -f `
                        $caseNameForValidation,
                        $requiredGateForValidation)
            }
        }
    }

    if ($case.PSObject.Properties.Name -contains "forbidden_triggered_fail_gates" -and `
            $null -ne $case.forbidden_triggered_fail_gates) {
        $forbiddenTriggeredForValidation = @($case.forbidden_triggered_fail_gates | ForEach-Object { [string]$_ })
        $duplicateForbiddenTriggeredForValidation = @(Get-StringArrayDuplicates -Values $forbiddenTriggeredForValidation)
        if ($duplicateForbiddenTriggeredForValidation.Count -gt 0) {
            throw ("Case '{0}' has duplicated forbidden_triggered_fail_gates: {1}" -f `
                    $caseNameForValidation,
                    (($duplicateForbiddenTriggeredForValidation | Sort-Object -Unique) -join ", "))
        }
    }
}

$results = New-Object System.Collections.Generic.List[object]
$usedCaseNames = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
$usedCaseArtifactTokens = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
$caseCounter = 0

foreach ($case in $cases) {
    $caseCounter += 1
    $caseName = [string]$case.name
    if (-not $usedCaseNames.Add($caseName)) {
        throw ("Duplicate case name detected: {0}" -f $caseName)
    }
    $caseArtifactToken = New-CaseArtifactToken -CaseIndex $caseCounter -CaseName $caseName
    if (-not $usedCaseArtifactTokens.Add($caseArtifactToken)) {
        throw ("Duplicate case artifact token detected: {0} (case='{1}')" -f $caseArtifactToken, $caseName)
    }
    $summaryPath = Join-Path $sessionDir ("{0}.json" -f $caseArtifactToken)
    $caseLogPath = Join-Path $sessionDir ("{0}.log" -f $caseArtifactToken)
    $runArgs = @{
        OneShot = $true
        PrismRoot = $PrismRoot
        InstanceName = $InstanceName
        ReportsDir = $sessionDir
        CandidateRoot = (Join-Path $sessionDir "missing_candidates")
        MetricsPath = (Join-Path $sessionDir "missing_runtime_metrics.csv")
        StatePath = (Join-Path $sessionDir "missing_ab_capture_state.json")
        AutopilotStatePath = (Join-Path $sessionDir ("autopilot_state_{0}.json" -f $caseArtifactToken))
        DisableAutoMetricsDiscovery = $true
        AutoSyncModJarToPrism = $false
    }
    Assert-PathLengthWithinLimit -PathValue $summaryPath -Label "summary"
    Assert-PathLengthWithinLimit -PathValue $caseLogPath -Label "case_log"
    Assert-PathLengthWithinLimit -PathValue ([string]$runArgs.AutopilotStatePath) -Label "autopilot_state"
    if (-not ($case.PSObject.Properties.Name -contains "use_explicit_summary_output_path") -or `
            [bool]$case.use_explicit_summary_output_path) {
        $summaryOutputPathMode = if ($case.PSObject.Properties.Name -contains "summary_output_path_mode") {
            [string]$case.summary_output_path_mode
        } else {
            "valid"
        }
        if ($summaryOutputPathMode -eq "invalid") {
            $runArgs.SummaryOutputPath = Get-InvalidSummaryPath -BaseDir $sessionDir -CaseName $caseArtifactToken
        } else {
            $runArgs.SummaryOutputPath = $summaryPath
        }
    }
    if (($case.PSObject.Properties.Name -contains "use_summary_path_as_strict_output") -and `
            [bool]$case.use_summary_path_as_strict_output) {
        $runArgs.StrictCiSummaryOutputPath = $summaryPath
    }
    foreach ($entry in $case.gate_args.GetEnumerator()) {
        $runArgs[$entry.Key] = $entry.Value
    }
    if ($case.PSObject.Properties.Name -contains "autopilot_state_fixture" -and `
            $null -ne $case.autopilot_state_fixture) {
        $stateFixture = [PSCustomObject]$case.autopilot_state_fixture
        $stateFixture | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $runArgs.AutopilotStatePath -Encoding UTF8
    }
    $casePrismFixtureRoot = ""
    if ($case.PSObject.Properties.Name -contains "prism_log_fixture" -and $null -ne $case.prism_log_fixture) {
        $casePrismFixtureRoot = Join-Path $sessionDir ("fixture_prism_root_{0}" -f $caseArtifactToken)
        Assert-PathLengthWithinLimit -PathValue $casePrismFixtureRoot -Label "prism_fixture_root"
        $runArgs.PrismRoot = $casePrismFixtureRoot
        $latestFixtureLines = @()
        $debugFixtureLines = @()
        $fixtureMinecraftDirName = "minecraft"
        if ($case.prism_log_fixture -is [System.Collections.IDictionary]) {
            if ($case.prism_log_fixture.Contains("latest_lines") -and $null -ne $case.prism_log_fixture["latest_lines"]) {
                $latestFixtureLines = @($case.prism_log_fixture["latest_lines"] | ForEach-Object { [string]$_ })
            }
            if ($case.prism_log_fixture.Contains("debug_lines") -and $null -ne $case.prism_log_fixture["debug_lines"]) {
                $debugFixtureLines = @($case.prism_log_fixture["debug_lines"] | ForEach-Object { [string]$_ })
            }
            if ($case.prism_log_fixture.Contains("minecraft_dir_name") -and $null -ne $case.prism_log_fixture["minecraft_dir_name"]) {
                $fixtureMinecraftDirName = [string]$case.prism_log_fixture["minecraft_dir_name"]
            }
        } else {
            if ($case.prism_log_fixture.PSObject.Properties.Name -contains "latest_lines" -and $null -ne $case.prism_log_fixture.latest_lines) {
                $latestFixtureLines = @($case.prism_log_fixture.latest_lines | ForEach-Object { [string]$_ })
            }
            if ($case.prism_log_fixture.PSObject.Properties.Name -contains "debug_lines" -and $null -ne $case.prism_log_fixture.debug_lines) {
                $debugFixtureLines = @($case.prism_log_fixture.debug_lines | ForEach-Object { [string]$_ })
            }
            if ($case.prism_log_fixture.PSObject.Properties.Name -contains "minecraft_dir_name" -and $null -ne $case.prism_log_fixture.minecraft_dir_name) {
                $fixtureMinecraftDirName = [string]$case.prism_log_fixture.minecraft_dir_name
            }
        }
        Initialize-PrismLogFixture `
            -PrismRootPath $casePrismFixtureRoot `
            -InstanceName $runArgs.InstanceName `
            -LatestLines $latestFixtureLines `
            -DebugLines $debugFixtureLines `
            -MinecraftDirName $fixtureMinecraftDirName
    }
    $gitDirtyProbePath = ""
    if (($case.PSObject.Properties.Name -contains "force_git_dirty_probe") -and [bool]$case.force_git_dirty_probe) {
        $gitDirtyProbePath = Join-Path $selfTestRepoRoot ("autopilot_fail_gate_selftest_git_dirty_{0}.tmp" -f $stamp)
        "git-dirty-probe" | Set-Content -LiteralPath $gitDirtyProbePath -Encoding UTF8
    }
    $environmentSnapshot = [ordered]@{}
    if (($case.PSObject.Properties.Name -contains "env_overrides") -and $null -ne $case.env_overrides) {
        foreach ($overrideEntry in $case.env_overrides.GetEnumerator()) {
            $envName = [string]$overrideEntry.Key
            if ([string]::IsNullOrWhiteSpace($envName)) {
                continue
            }
            $envPath = "Env:{0}" -f $envName
            $originalEnvExists = Test-Path -LiteralPath $envPath
            $originalEnvValue = $null
            if ($originalEnvExists) {
                $originalEnvValue = [string](Get-Item -LiteralPath $envPath).Value
            }
            $environmentSnapshot[$envName] = [PSCustomObject]@{
                exists = $originalEnvExists
                value = $originalEnvValue
            }
            if ($null -eq $overrideEntry.Value) {
                if ($originalEnvExists) {
                    Remove-Item -LiteralPath $envPath -ErrorAction SilentlyContinue
                }
            } else {
                Set-Item -LiteralPath $envPath -Value ([string]$overrideEntry.Value)
            }
        }
    }

    $exceptionMessage = ""
    $exitCode = 0
    Reset-LastExitCode
    try {
        & $AutopilotScriptPath @runArgs *> $caseLogPath
        $exitCode = Get-LastExitCodeOrZero
    } catch {
        $exceptionMessage = $_.Exception.Message
        $exitCode = Get-LastExitCodeOrZero
        if ($exitCode -eq 0) {
            $exitCode = 1
        }
    } finally {
        if (-not [string]::IsNullOrWhiteSpace($casePrismFixtureRoot) -and (Test-Path -LiteralPath $casePrismFixtureRoot -PathType Container)) {
            Remove-Item -LiteralPath $casePrismFixtureRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
        if (-not [string]::IsNullOrWhiteSpace($gitDirtyProbePath) -and (Test-Path -LiteralPath $gitDirtyProbePath -PathType Leaf)) {
            Remove-Item -LiteralPath $gitDirtyProbePath -Force -ErrorAction SilentlyContinue
        }
        if ($environmentSnapshot.Count -gt 0) {
            foreach ($snapshotEntry in $environmentSnapshot.GetEnumerator()) {
                $envName = [string]$snapshotEntry.Key
                $envPath = "Env:{0}" -f $envName
                $snapshotValue = $snapshotEntry.Value
                $snapshotExists = [bool]$snapshotValue.exists
                $snapshotOriginalValue = $snapshotValue.value
                if ($snapshotExists) {
                    Set-Item -LiteralPath $envPath -Value ([string]$snapshotOriginalValue)
                } elseif (Test-Path -LiteralPath $envPath) {
                    Remove-Item -LiteralPath $envPath -ErrorAction SilentlyContinue
                }
            }
        }
        Reset-LastExitCode
    }

    $summary = $null
    if (Test-Path -LiteralPath $summaryPath -PathType Leaf) {
        try {
            $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
        } catch {
            $summary = $null
        }
    }

    $actualReason = if ($null -eq $summary) {
        $reasonFromException = Infer-FailureReasonFromException -ExceptionMessage $exceptionMessage
        if ([string]::IsNullOrWhiteSpace($reasonFromException)) {
            Get-CaseLogScalarField -LogPath $caseLogPath -FieldName "autopilot_failure_reason"
        } else {
            $reasonFromException
        }
    } else {
        [string](Get-ObjectPropertyValue -InputObject $summary -PropertyName "autopilot_failure_reason" -DefaultValue "")
    }
    $actualFailed = if ($null -eq $summary) { ($exitCode -ne 0) } else { [bool](Get-ObjectPropertyValue -InputObject $summary -PropertyName "autopilot_failed" -DefaultValue $false) }
    $actualForced = if ($null -eq $summary) { $false } else { [bool](Get-ObjectPropertyValue -InputObject $summary -PropertyName "error_sorting_pass_forced_by_fail_gate" -DefaultValue $false) }
    $actualStrictCi = if ($null -eq $summary) { $false } else { [bool](Get-ObjectPropertyValue -InputObject $summary -PropertyName "strict_ci_fail_gates_enabled" -DefaultValue $false) }
    $actualFailOnBlocking = if ($null -eq $summary) { $false } else { [bool](Get-ObjectPropertyValue -InputObject $summary -PropertyName "fail_on_error_sorting_blocking_patterns" -DefaultValue $false) }
    $actualFailOnNoiseFail = if ($null -eq $summary) { $false } else { [bool](Get-ObjectPropertyValue -InputObject $summary -PropertyName "fail_on_error_sorting_noise_fail" -DefaultValue $false) }
    $actualActiveFailGates = if ($null -eq $summary) {
        Get-CaseLogStringArrayField -LogPath $caseLogPath -FieldName "active_fail_gates"
    } else {
        Get-ObjectStringArrayProperty -InputObject $summary -PropertyName "active_fail_gates"
    }
    $actualActiveFailGateCount = if ($null -eq $summary) {
        @($actualActiveFailGates).Count
    } else {
        [int](Get-ObjectPropertyValue -InputObject $summary -PropertyName "active_fail_gate_count" -DefaultValue 0)
    }
    $actualTriggeredFailGates = if ($null -eq $summary) {
        Get-CaseLogStringArrayField -LogPath $caseLogPath -FieldName "triggered_fail_gates"
    } else {
        Get-ObjectStringArrayProperty -InputObject $summary -PropertyName "triggered_fail_gates"
    }
    $actualTriggeredFailGateCount = if ($null -eq $summary) {
        @($actualTriggeredFailGates).Count
    } else {
        [int](Get-ObjectPropertyValue -InputObject $summary -PropertyName "triggered_fail_gate_count" -DefaultValue 0)
    }
    $actualStrictSummaryOutputDefaulted = if ($null -eq $summary) { $false } else { [bool](Get-ObjectPropertyValue -InputObject $summary -PropertyName "strict_ci_summary_output_defaulted" -DefaultValue $false) }
    $actualStrictSummaryOutputCompressForced = if ($null -eq $summary) { $false } else { [bool](Get-ObjectPropertyValue -InputObject $summary -PropertyName "strict_ci_summary_output_compress_forced" -DefaultValue $false) }
    $actualSummaryOutputCompressed = if ($null -eq $summary) { $false } else { [bool](Get-ObjectPropertyValue -InputObject $summary -PropertyName "summary_output_compressed" -DefaultValue $false) }
    $actualSummaryOutputWritten = if ($null -eq $summary) { $false } else { [bool](Get-ObjectPropertyValue -InputObject $summary -PropertyName "summary_output_written" -DefaultValue $false) }
    $actualSummaryOutputWrittenUtc = if ($null -eq $summary) { "" } else { [string](Get-ObjectPropertyValue -InputObject $summary -PropertyName "summary_output_written_utc" -DefaultValue "") }
    $actualSummaryOutputSizeBytes = if ($null -eq $summary) { 0 } else { [int64](Get-ObjectPropertyValue -InputObject $summary -PropertyName "summary_output_size_bytes" -DefaultValue 0) }
    $actualSummaryOutputSha256 = if ($null -eq $summary) { "" } else { [string](Get-ObjectPropertyValue -InputObject $summary -PropertyName "summary_output_sha256" -DefaultValue "") }
    $summaryFileExists = Test-Path -LiteralPath $summaryPath -PathType Leaf
    $summaryFileLength = 0
    if ($summaryFileExists) {
        $summaryFileLength = [int64](Get-Item -LiteralPath $summaryPath -ErrorAction Stop).Length
    }

    $checks = New-Object System.Collections.Generic.List[string]
    $passed = $true

    if ($case.expect_exit_nonzero -and $exitCode -eq 0) {
        $checks.Add("expected_nonzero_exit")
        $passed = $false
    } elseif (-not $case.expect_exit_nonzero -and $exitCode -ne 0) {
        $checks.Add("expected_zero_exit")
        $passed = $false
    }
    if ($actualReason -ne [string]$case.expected_reason) {
        $checks.Add("unexpected_reason")
        $passed = $false
    }
    if ($actualFailed -ne [bool]$case.expect_failed) {
        $checks.Add("unexpected_failed_flag")
        $passed = $false
    }
    if ($actualForced -ne [bool]$case.expect_forced_error_sorting) {
        $checks.Add("unexpected_error_sorting_force_flag")
        $passed = $false
    }
    if ($null -ne $case.expected_strict_ci -and $actualStrictCi -ne [bool]$case.expected_strict_ci) {
        $checks.Add("unexpected_strict_ci_flag")
        $passed = $false
    }
    if ($null -ne $case.expected_fail_on_error_sorting_blocking_patterns -and `
            $actualFailOnBlocking -ne [bool]$case.expected_fail_on_error_sorting_blocking_patterns) {
        $checks.Add("unexpected_fail_on_error_sorting_blocking_patterns_flag")
        $passed = $false
    }
    if ($null -ne $case.expected_fail_on_error_sorting_noise_fail -and `
            $actualFailOnNoiseFail -ne [bool]$case.expected_fail_on_error_sorting_noise_fail) {
        $checks.Add("unexpected_fail_on_error_sorting_noise_fail_flag")
        $passed = $false
    }
    if ($null -ne $case.expected_fail_gate_flags) {
        foreach ($flagExpectation in $case.expected_fail_gate_flags.GetEnumerator()) {
            $actualFlagValue = if ($null -eq $summary) {
                ConvertTo-BooleanFromString `
                    -Value (Get-CaseLogScalarField -LogPath $caseLogPath -FieldName ([string]$flagExpectation.Key)) `
                    -DefaultValue $false
            } else {
                [bool](Get-ObjectPropertyValue -InputObject $summary -PropertyName ([string]$flagExpectation.Key) -DefaultValue $false)
            }
            if ($actualFlagValue -ne [bool]$flagExpectation.Value) {
                $checks.Add("unexpected_{0}_flag" -f [string]$flagExpectation.Key)
                $passed = $false
            }
        }
    }
    if ($null -ne $case.expected_active_fail_gates) {
        $expectedActive = @($case.expected_active_fail_gates | ForEach-Object { [string]$_ } | Sort-Object -Unique)
        $actualActive = @($actualActiveFailGates | ForEach-Object { [string]$_ } | Sort-Object -Unique)
        $missingActive = @($expectedActive | Where-Object { $actualActive -notcontains $_ })
        $unexpectedActive = @($actualActive | Where-Object { $expectedActive -notcontains $_ })
        if ($missingActive.Count -gt 0) {
            $checks.Add("missing_active_fail_gates")
            $passed = $false
        }
        if ($unexpectedActive.Count -gt 0) {
            $checks.Add("unexpected_active_fail_gates")
            $passed = $false
        }
        if ($actualActiveFailGateCount -ne $expectedActive.Count) {
            $checks.Add("unexpected_active_fail_gate_count")
            $passed = $false
        }
    }
    if ($case.PSObject.Properties.Name -contains "expected_triggered_fail_gates" -and `
            $null -ne $case.expected_triggered_fail_gates) {
        $expectedTriggered = @($case.expected_triggered_fail_gates | ForEach-Object { [string]$_ } | Sort-Object -Unique)
        $actualTriggered = @($actualTriggeredFailGates | ForEach-Object { [string]$_ } | Sort-Object -Unique)
        $missingTriggered = @($expectedTriggered | Where-Object { $actualTriggered -notcontains $_ })
        $unexpectedTriggered = @($actualTriggered | Where-Object { $expectedTriggered -notcontains $_ })
        if ($missingTriggered.Count -gt 0) {
            $checks.Add("missing_expected_triggered_fail_gates")
            $passed = $false
        }
        if ($unexpectedTriggered.Count -gt 0) {
            $checks.Add("unexpected_triggered_fail_gates")
            $passed = $false
        }
        if ($actualTriggeredFailGateCount -ne $expectedTriggered.Count) {
            $checks.Add("unexpected_triggered_fail_gate_count_vs_expected")
            $passed = $false
        }
    }
    if ($case.PSObject.Properties.Name -contains "expected_triggered_fail_gates_in_order" -and `
            $null -ne $case.expected_triggered_fail_gates_in_order) {
        $expectedTriggeredOrdered = @($case.expected_triggered_fail_gates_in_order | ForEach-Object { [string]$_ })
        $actualTriggeredOrdered = @($actualTriggeredFailGates | ForEach-Object { [string]$_ })
        if ($actualTriggeredOrdered.Count -ne $expectedTriggeredOrdered.Count) {
            $checks.Add("unexpected_triggered_fail_gate_count_in_order")
            $passed = $false
        } else {
            for ($triggeredIndex = 0; $triggeredIndex -lt $expectedTriggeredOrdered.Count; $triggeredIndex++) {
                if ($actualTriggeredOrdered[$triggeredIndex] -ne $expectedTriggeredOrdered[$triggeredIndex]) {
                    $checks.Add("unexpected_triggered_fail_gate_order")
                    $passed = $false
                    break
                }
            }
        }
    }
    if ($case.PSObject.Properties.Name -contains "required_triggered_fail_gates" -and `
            $null -ne $case.required_triggered_fail_gates) {
        $requiredTriggered = @($case.required_triggered_fail_gates | ForEach-Object { [string]$_ } | Sort-Object -Unique)
        $actualTriggered = @($actualTriggeredFailGates | ForEach-Object { [string]$_ } | Sort-Object -Unique)
        $missingRequiredTriggered = @($requiredTriggered | Where-Object { $actualTriggered -notcontains $_ })
        if ($missingRequiredTriggered.Count -gt 0) {
            $checks.Add("missing_required_triggered_fail_gates")
            $passed = $false
        }
    }
    if ($case.PSObject.Properties.Name -contains "forbidden_triggered_fail_gates" -and `
            $null -ne $case.forbidden_triggered_fail_gates) {
        $forbiddenTriggered = @($case.forbidden_triggered_fail_gates | ForEach-Object { [string]$_ } | Sort-Object -Unique)
        foreach ($forbiddenGate in $forbiddenTriggered) {
            if ($actualTriggeredFailGates -contains $forbiddenGate) {
                $checks.Add("forbidden_triggered_fail_gate_{0}" -f $forbiddenGate)
                $passed = $false
            }
        }
    }
    $actualTriggeredUnique = @($actualTriggeredFailGates | ForEach-Object { [string]$_ } | Sort-Object -Unique)
    $actualActiveUnique = @($actualActiveFailGates | ForEach-Object { [string]$_ } | Sort-Object -Unique)
    if (@($actualTriggeredFailGates).Count -ne $actualTriggeredUnique.Count) {
        $checks.Add("duplicate_triggered_fail_gates")
        $passed = $false
    }
    if (@($actualActiveFailGates).Count -ne $actualActiveUnique.Count) {
        $checks.Add("duplicate_active_fail_gates")
        $passed = $false
    }
    foreach ($triggeredGate in $actualTriggeredUnique) {
        if ([string]::IsNullOrWhiteSpace($triggeredGate)) {
            continue
        }
        if (-not $triggeredToActiveFailGateMap.Contains($triggeredGate)) {
            $checks.Add("triggered_gate_missing_active_map_{0}" -f $triggeredGate)
            $passed = $false
            continue
        }
        $requiredActiveGate = [string]$triggeredToActiveFailGateMap[$triggeredGate]
        if ([string]::IsNullOrWhiteSpace($requiredActiveGate)) {
            $checks.Add("triggered_gate_empty_active_map_{0}" -f $triggeredGate)
            $passed = $false
            continue
        }
        if (-not [string]::IsNullOrWhiteSpace($requiredActiveGate) -and `
                ($actualActiveUnique -notcontains $requiredActiveGate)) {
            $checks.Add("triggered_gate_without_active_gate_{0}" -f $triggeredGate)
            $passed = $false
        }
    }
    if ($case.expect_failed -and $actualTriggeredFailGateCount -lt 1) {
        $checks.Add("missing_triggered_fail_gate")
        $passed = $false
    }
    if (-not $case.expect_failed -and $actualTriggeredFailGateCount -ne 0) {
        $checks.Add("unexpected_triggered_fail_gate")
        $passed = $false
    }
    $reasonImpliesFailure = -not [string]::IsNullOrWhiteSpace($actualReason)
    if ($actualFailed -ne $reasonImpliesFailure) {
        $checks.Add("inconsistent_failed_reason_pair")
        $passed = $false
    }
    if ($reasonImpliesFailure -and -not ($actualTriggeredFailGates -contains $actualReason)) {
        $checks.Add("missing_failure_reason_in_triggered_fail_gates")
        $passed = $false
    }
    if ($reasonImpliesFailure -and -not $triggeredToActiveFailGateMap.Contains($actualReason)) {
        $checks.Add("failure_reason_missing_active_map")
        $passed = $false
    }
    $actualTriggeredOrderedForReasonCheck = @($actualTriggeredFailGates | ForEach-Object { [string]$_ })
    if ($null -ne $summary -and $reasonImpliesFailure -and $actualTriggeredOrderedForReasonCheck.Count -gt 0) {
        $firstTriggeredFailGate = [string]$actualTriggeredOrderedForReasonCheck[0]
        if ($firstTriggeredFailGate -ne $actualReason) {
            $checks.Add("failure_reason_not_first_triggered_gate")
            $passed = $false
        }
    }

    if ($null -ne $summary) {
        if ($summaryFileExists -and -not $actualSummaryOutputWritten) {
            $checks.Add("summary_file_written_flag_false")
            $passed = $false
        }
        if ($summaryFileExists -and [string]::IsNullOrWhiteSpace($actualSummaryOutputWrittenUtc)) {
            $checks.Add("summary_file_written_utc_missing")
            $passed = $false
        }
        if ($summaryFileExists -and $actualSummaryOutputWritten -and $actualSummaryOutputSizeBytes -le 0) {
            $checks.Add("summary_file_metadata_size_missing")
            $passed = $false
        }
        if ($summaryFileExists -and $actualSummaryOutputWritten -and [string]::IsNullOrWhiteSpace($actualSummaryOutputSha256)) {
            $checks.Add("summary_file_metadata_sha256_missing")
            $passed = $false
        }
        if ($summaryFileExists -and $actualSummaryOutputWritten -and $actualSummaryOutputSizeBytes -ne $summaryFileLength) {
            $checks.Add("summary_file_metadata_size_mismatch")
            $passed = $false
        }
        if ($actualActiveFailGateCount -ne @($actualActiveFailGates).Count) {
            $checks.Add("inconsistent_active_fail_gate_count")
            $passed = $false
        }
        if ($actualTriggeredFailGateCount -ne @($actualTriggeredFailGates).Count) {
            $checks.Add("inconsistent_triggered_fail_gate_count")
            $passed = $false
        }
    }
    if ($null -ne $case.expected_strict_ci_summary_output_defaulted -and `
            $actualStrictSummaryOutputDefaulted -ne [bool]$case.expected_strict_ci_summary_output_defaulted) {
        $checks.Add("unexpected_strict_ci_summary_output_defaulted")
        $passed = $false
    }
    if ($null -ne $case.expected_strict_ci_summary_output_compress_forced -and `
            $actualStrictSummaryOutputCompressForced -ne [bool]$case.expected_strict_ci_summary_output_compress_forced) {
        $checks.Add("unexpected_strict_ci_summary_output_compress_forced")
        $passed = $false
    }
    if ($null -ne $case.expected_summary_output_compressed -and `
            $actualSummaryOutputCompressed -ne [bool]$case.expected_summary_output_compressed) {
        $checks.Add("unexpected_summary_output_compressed")
        $passed = $false
    }

    $results.Add([PSCustomObject]@{
            name = $case.name
            passed = $passed
            exit_code = $exitCode
            autopilot_failed = $actualFailed
            autopilot_failure_reason = $actualReason
            error_sorting_pass_forced_by_fail_gate = $actualForced
            strict_ci_fail_gates_enabled = $actualStrictCi
            fail_on_error_sorting_blocking_patterns = $actualFailOnBlocking
            fail_on_error_sorting_noise_fail = $actualFailOnNoiseFail
            active_fail_gate_count = $actualActiveFailGateCount
            triggered_fail_gate_count = $actualTriggeredFailGateCount
            active_fail_gates = @($actualActiveFailGates)
            triggered_fail_gates = @($actualTriggeredFailGates)
            strict_ci_summary_output_defaulted = $actualStrictSummaryOutputDefaulted
            strict_ci_summary_output_compress_forced = $actualStrictSummaryOutputCompressForced
            summary_output_compressed = $actualSummaryOutputCompressed
            summary_output_written = $actualSummaryOutputWritten
            case_artifact_token = $caseArtifactToken
            summary_path = if (Test-Path -LiteralPath $summaryPath -PathType Leaf) { (Resolve-Path -LiteralPath $summaryPath).Path } else { "" }
            case_log_path = if (Test-Path -LiteralPath $caseLogPath -PathType Leaf) { (Resolve-Path -LiteralPath $caseLogPath).Path } else { "" }
            checks = @($checks.ToArray())
            exception = $exceptionMessage
        })
}

$passedCount = @($results | Where-Object { [bool]$_.passed }).Count
$failedCount = $results.Count - $passedCount

$report = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    autopilot_script_path = (Resolve-Path -LiteralPath $AutopilotScriptPath).Path
    session_dir = (Resolve-Path -LiteralPath $sessionDir).Path
    total_cases = $results.Count
    passed_cases = $passedCount
    failed_cases = $failedCount
    results = @($results.ToArray())
}

$reportPath = Join-Path $sessionDir "autopilot_fail_gate_selftest_summary.json"
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8

Write-Host ""
Write-Host "Autopilot fail-gate self-test"
Write-Host "-----------------------------"
Write-Host ("Cases: total={0}, passed={1}, failed={2}" -f $report.total_cases, $report.passed_cases, $report.failed_cases)
Write-Host ("Case logs directory: {0}" -f (Resolve-Path -LiteralPath $sessionDir).Path)
Write-Host ""
@($results.ToArray()) | Select-Object name, passed, exit_code, autopilot_failure_reason, strict_ci_fail_gates_enabled, active_fail_gate_count, triggered_fail_gate_count, error_sorting_pass_forced_by_fail_gate, case_log_path | Format-Table -AutoSize
Write-Host ""
Write-Host ("Report: {0}" -f (Resolve-Path -LiteralPath $reportPath).Path)

if ($failedCount -gt 0) {
    throw ("Autopilot fail-gate self-test failed: {0} case(s) failed." -f $failedCount)
}

if ($PassThru) {
    $report
}
