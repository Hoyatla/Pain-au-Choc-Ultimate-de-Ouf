param(
    [string]$InstanceName = "test",
    [string]$PrismInstancesRoot = "",
    [string[]]$LogPaths = @(),
    [int]$TopN = 10,
    [string]$OutDir = ".\run\pauc_reports",
    [switch]$IncludeWarnings,
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-Bucket {
    param([string]$Signature)
    $s = $Signature.ToLowerInvariant()
    if ($s.Contains("hanging entity at invalid position")) { return "world_entities" }
    if ($s.Contains("invalid or unsupported recipe type") -or $s.Contains("unknown item")) { return "modpack_recipes_items" }
    if ($s.Contains("using missing texture") -or $s.Contains("filenotfoundexception")) { return "assets_missing" }
    if ($s.Contains("can't keep up!")) { return "server_overload" }
    if ($s.Contains("opengl debug message") -or $s.Contains("gl_invalid_operation")) { return "graphics_api" }
    if ($s.Contains("ftb quests freeze fix")) { return "mod_integration" }
    if ($s.Contains("jsonsyntaxexception")) { return "json_content" }
    if ($s.Contains("connectexception") -or $s.Contains("unresolvedaddressexception")) { return "network" }
    return "other"
}

function Normalize-Message {
    param([string]$Message)

    $m = $Message
    $m = $m -replace "BlockPos\{x=-?\d+,\s*y=-?\d+,\s*z=-?\d+\}", "BlockPos{...}"
    $m = $m -replace "Running \d+ms or \d+ ticks behind", "Running <ms>ms or <ticks> ticks behind"
    $m = $m -replace "line \d+ column \d+", "line <n> column <n>"
    $m = $m -replace "frame \d+[: ]", "frame <n>: "
    $m = $m.Trim()
    return $m
}

function Resolve-DefaultPrismLogPaths {
    param(
        [string]$PrismRootPath,
        [string]$PrismInstanceName
    )

    if ([string]::IsNullOrWhiteSpace($PrismRootPath) -or [string]::IsNullOrWhiteSpace($PrismInstanceName)) {
        return @()
    }

    $instanceRoot = Join-Path $PrismRootPath $PrismInstanceName
    $candidateLogRoots = @(
        (Join-Path (Join-Path $instanceRoot ".minecraft") "logs"),
        (Join-Path (Join-Path $instanceRoot "minecraft") "logs")
    )

    foreach ($candidateRoot in $candidateLogRoots) {
        if (Test-Path -LiteralPath $candidateRoot -PathType Container) {
            return @(
                (Join-Path $candidateRoot "latest.log"),
                (Join-Path $candidateRoot "debug.log")
            )
        }
    }

    $fallbackRoot = $candidateLogRoots[0]
    return @(
        (Join-Path $fallbackRoot "latest.log"),
        (Join-Path $fallbackRoot "debug.log")
    )
}

if ([string]::IsNullOrWhiteSpace($PrismInstancesRoot)) {
    $PrismInstancesRoot = Join-Path $env:APPDATA "PrismLauncher\instances"
}

if ($LogPaths.Count -eq 0) {
    $LogPaths = Resolve-DefaultPrismLogPaths -PrismRootPath $PrismInstancesRoot -PrismInstanceName $InstanceName
}

$resolvedLogs = @($LogPaths | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Trim() } | Select-Object -Unique)
$missingLogs = @($resolvedLogs | Where-Object { -not (Test-Path -LiteralPath $_) })
if ($missingLogs.Count -gt 0) {
    throw ("Missing log files: {0}" -f ($missingLogs -join ", "))
}

$entries = New-Object System.Collections.Generic.List[object]

foreach ($logPath in $resolvedLogs) {
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $logPath) {
        $lineNumber++

        $isErrorLevel = $line -match "\[(ERROR|FATAL)\]"
        $isException = $line -match "(Exception|Caused by:)"
        $isWarnHotspot = $IncludeWarnings -and ($line -match "Can't keep up!|OpenGL debug message:.*type=ERROR|Hanging entity at invalid position|Invalid or unsupported recipe type|Using missing texture")

        if (-not $isErrorLevel -and -not $isException -and -not $isWarnHotspot) {
            continue
        }

        $logger = ""
        $message = $line
        if ($line -match "^\[[^\]]+\]\s+\[[^\]]+\]\s+\[([^\]]+)\]:\s*(.*)$") {
            $logger = $matches[1].TrimEnd("/")
            $message = $matches[2]
        }

        $normalizedMessage = Normalize-Message -Message $message
        $signature = if ([string]::IsNullOrWhiteSpace($logger)) { $normalizedMessage } else { "{0} :: {1}" -f $logger, $normalizedMessage }
        $bucket = Get-Bucket -Signature $signature
        $category = if ($isErrorLevel) { "error_level" } elseif ($isException) { "exception" } else { "warn_hotspot" }

        $entries.Add([PSCustomObject]@{
                log_file = $logPath
                line_number = $lineNumber
                category = $category
                bucket = $bucket
                signature = $signature
                sample_line = $line
            })
    }
}

$totalEvents = $entries.Count
$grouped = @($entries | Group-Object -Property signature | Sort-Object Count -Descending)

$topRows = New-Object System.Collections.Generic.List[object]
$index = 0
foreach ($group in $grouped) {
    $index++
    $sample = $group.Group | Select-Object -First 1
    $topRows.Add([PSCustomObject]@{
            rank = $index
            count = $group.Count
            bucket = $sample.bucket
            category = $sample.category
            signature = $group.Name
            sample_log_file = $sample.log_file
            sample_line_number = $sample.line_number
        })
}

$topRows = @($topRows | Select-Object -First $TopN)
$bucketSummary = @($entries | Group-Object -Property bucket | Sort-Object Count -Descending | ForEach-Object {
        [PSCustomObject]@{
            bucket = $_.Name
            count = $_.Count
        }
    })

if (-not (Test-Path -LiteralPath $OutDir)) {
    New-Item -Path $OutDir -ItemType Directory -Force | Out-Null
}

$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd_HHmmss_fff")
$csvPath = Join-Path $OutDir ("modpack_error_triage_top_{0}.csv" -f $stamp)
$jsonPath = Join-Path $OutDir ("modpack_error_triage_{0}.json" -f $stamp)
$mdPath = Join-Path $OutDir ("modpack_error_triage_{0}.md" -f $stamp)

$topRows | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding UTF8

$summary = [PSCustomObject]@{
    timestamp_utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    instance_name = $InstanceName
    logs = $resolvedLogs
    include_warnings = [bool]$IncludeWarnings
    total_events = $totalEvents
    unique_signatures = $grouped.Count
    top_n = $TopN
    top_signatures = $topRows
    bucket_summary = $bucketSummary
    top_csv_path = (Resolve-Path -LiteralPath $csvPath).Path
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$md = New-Object System.Collections.Generic.List[string]
$md.Add("# Modpack Error Triage")
$md.Add("")
$md.Add(("- Timestamp UTC: {0}" -f $summary.timestamp_utc))
$md.Add(("- Instance: {0}" -f $InstanceName))
$md.Add(("- Logs: {0}" -f ($resolvedLogs -join " | ")))
$md.Add(("- Total events: {0}" -f $totalEvents))
$md.Add(("- Unique signatures: {0}" -f $grouped.Count))
$md.Add(("- Include warnings: {0}" -f [bool]$IncludeWarnings))
$md.Add("")
$md.Add("## Bucket Summary")
$md.Add("")
$md.Add("| Bucket | Count |")
$md.Add("|---|---:|")
foreach ($b in $bucketSummary) {
    $md.Add(("| {0} | {1} |" -f $b.bucket, $b.count))
}
$md.Add("")
$md.Add(("## Top {0} Signatures" -f $TopN))
$md.Add("")
$md.Add("| Rank | Count | Bucket | Signature | Sample |")
$md.Add("|---:|---:|---|---|---|")
foreach ($row in $topRows) {
    $sampleRef = "{0}:{1}" -f [System.IO.Path]::GetFileName($row.sample_log_file), $row.sample_line_number
    $sig = $row.signature.Replace("|", "\|")
    $md.Add(("| {0} | {1} | {2} | {3} | {4} |" -f $row.rank, $row.count, $row.bucket, $sig, $sampleRef))
}
$md.Add("")
$md.Add(("- CSV: {0}" -f (Resolve-Path -LiteralPath $csvPath).Path))
$md.Add(("- JSON: {0}" -f (Resolve-Path -LiteralPath $jsonPath).Path))
$md | Set-Content -LiteralPath $mdPath -Encoding UTF8

Write-Host ""
Write-Host "PauC modpack error triage"
Write-Host "-------------------------"
Write-Host ""
if (-not $PassThru) {
    $summary | Format-List
}
Write-Host ""
Write-Host ("Report MD:  {0}" -f (Resolve-Path -LiteralPath $mdPath).Path)
Write-Host ("Report CSV: {0}" -f (Resolve-Path -LiteralPath $csvPath).Path)
Write-Host ("Report JSON:{0}" -f (Resolve-Path -LiteralPath $jsonPath).Path)

if ($PassThru) {
    $summary
}
