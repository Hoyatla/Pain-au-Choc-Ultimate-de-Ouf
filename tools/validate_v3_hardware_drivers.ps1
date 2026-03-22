param(
    [string]$InstanceName = "test",
    [string]$CandidateDir = "",
    [string]$PrismInstancesRoot = "",
    [string]$OutDir = ".\run\pauc_reports",
    [switch]$AllowMissingVendors,
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-Stamp {
    return (Get-Date).ToUniversalTime().ToString("yyyyMMdd_HHmmss_fff")
}

function Get-VendorKey {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return ""
    }
    $value = $Text.ToLowerInvariant()
    if ($value.Contains("nvidia")) { return "NVIDIA" }
    if ($value.Contains("amd") -or $value.Contains("advanced micro devices") -or $value.Contains("radeon")) { return "AMD" }
    if ($value.Contains("intel")) { return "Intel" }
    return ""
}

function Get-GlInfoEntries {
    param([string]$LogsDir)

    if (-not (Test-Path -LiteralPath $LogsDir)) {
        return @()
    }

    $entries = New-Object System.Collections.Generic.List[object]
    $files = @(Get-ChildItem -LiteralPath $LogsDir -File | Sort-Object LastWriteTime)
    foreach ($file in $files) {
        if ($file.Extension -eq ".gz") {
            $stream = [System.IO.File]::OpenRead($file.FullName)
            try {
                $gzip = New-Object System.IO.Compression.GzipStream($stream, [System.IO.Compression.CompressionMode]::Decompress)
                $reader = New-Object System.IO.StreamReader($gzip)
                try {
                    $lineNumber = 0
                    while (($line = $reader.ReadLine()) -ne $null) {
                        $lineNumber++
                        if ($line -notmatch "GL info:") {
                            continue
                        }

                        $renderer = ""
                        $glVersion = ""
                        $glVendor = ""
                        if ($line -match "GL info:\s*(.+?)\s+GL version\s+(.+?),\s*(.+)$") {
                            $renderer = $matches[1].Trim()
                            $glVersion = $matches[2].Trim()
                            $glVendor = $matches[3].Trim()
                        }

                        $vendorKey = Get-VendorKey -Text ("{0} {1}" -f $renderer, $glVendor)
                        $entries.Add([PSCustomObject]@{
                                file = $file.Name
                                file_path = $file.FullName
                                line_number = $lineNumber
                                line = $line
                                renderer = $renderer
                                gl_version = $glVersion
                                gl_vendor = $glVendor
                                vendor_key = $vendorKey
                            })
                    }
                }
                finally {
                    $reader.Close()
                    $gzip.Close()
                }
            }
            finally {
                $stream.Close()
            }
        }
        else {
            $lineNumber = 0
            foreach ($line in Get-Content -LiteralPath $file.FullName) {
                $lineNumber++
                if ($line -notmatch "GL info:") {
                    continue
                }

                $renderer = ""
                $glVersion = ""
                $glVendor = ""
                if ($line -match "GL info:\s*(.+?)\s+GL version\s+(.+?),\s*(.+)$") {
                    $renderer = $matches[1].Trim()
                    $glVersion = $matches[2].Trim()
                    $glVendor = $matches[3].Trim()
                }

                $vendorKey = Get-VendorKey -Text ("{0} {1}" -f $renderer, $glVendor)
                $entries.Add([PSCustomObject]@{
                        file = $file.Name
                        file_path = $file.FullName
                        line_number = $lineNumber
                        line = $line
                        renderer = $renderer
                        gl_version = $glVersion
                        gl_vendor = $glVendor
                        vendor_key = $vendorKey
                    })
            }
        }
    }

    return $entries.ToArray()
}

if ([string]::IsNullOrWhiteSpace($PrismInstancesRoot)) {
    $PrismInstancesRoot = Join-Path $env:APPDATA "PrismLauncher\instances"
}

$resolvedCandidateDir = $CandidateDir
if ([string]::IsNullOrWhiteSpace($resolvedCandidateDir)) {
    $candidateRoot = ".\run\beta_candidates"
    if (-not (Test-Path -LiteralPath $candidateRoot)) {
        throw "Candidate root not found: $candidateRoot"
    }
    $latestCandidate = Get-ChildItem -LiteralPath $candidateRoot -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $latestCandidate) {
        throw "No candidate directory found under: $candidateRoot"
    }
    $resolvedCandidateDir = $latestCandidate.FullName
}

$candidateManifestPath = Join-Path $resolvedCandidateDir "candidate_manifest.json"
if (-not (Test-Path -LiteralPath $candidateManifestPath)) {
    throw "Candidate manifest not found: $candidateManifestPath"
}
$candidateManifest = Get-Content -LiteralPath $candidateManifestPath -Raw | ConvertFrom-Json

$candidateDecision = [string]$candidateManifest.readiness_decision
$candidateReadiness = [int]$candidateManifest.readiness_percent
$candidateJarHash = [string]$candidateManifest.jar_sha256

$logsDir = Join-Path $PrismInstancesRoot "$InstanceName\minecraft\logs"
$glInfoEntries = Get-GlInfoEntries -LogsDir $logsDir

$videoAdapters = @(Get-CimInstance Win32_VideoController | Select-Object Name, DriverVersion, DriverDate, AdapterCompatibility, VideoProcessor)

$vendors = @("NVIDIA", "AMD", "Intel")
$vendorMatrix = New-Object System.Collections.Generic.List[object]
$waivers = New-Object System.Collections.Generic.List[string]
$issues = New-Object System.Collections.Generic.List[string]

foreach ($vendor in $vendors) {
    $matchingAdapters = @($videoAdapters | Where-Object {
            $key = Get-VendorKey -Text ("{0} {1}" -f $_.AdapterCompatibility, $_.Name)
            $key -eq $vendor
        })
    $matchingGl = @($glInfoEntries | Where-Object { $_.vendor_key -eq $vendor })

    $adapterPresent = $matchingAdapters.Count -gt 0
    $runtimeSeen = $matchingGl.Count -gt 0
    $status = "pass"

    if (-not $adapterPresent) {
        $status = "missing_hardware"
    }
    elseif (-not $runtimeSeen) {
        $status = "no_runtime_evidence"
    }

    if ($status -eq "missing_hardware" -or $status -eq "no_runtime_evidence") {
        $message = "{0}: {1}" -f $vendor, $status
        if ($AllowMissingVendors) {
            $waivers.Add($message)
        }
        else {
            $issues.Add($message)
        }
    }

    $latestGl = $null
    if ($runtimeSeen) {
        $latestGl = $matchingGl | Select-Object -Last 1
    }

    $vendorMatrix.Add([PSCustomObject]@{
            vendor = $vendor
            adapter_present = $adapterPresent
            adapter_names = @($matchingAdapters | Select-Object -ExpandProperty Name)
            driver_versions = @($matchingAdapters | Select-Object -ExpandProperty DriverVersion)
            runtime_gl_seen = $runtimeSeen
            runtime_gl_count = $matchingGl.Count
            runtime_latest_gl_file = if ($null -ne $latestGl) { $latestGl.file } else { "" }
            runtime_latest_gl_renderer = if ($null -ne $latestGl) { $latestGl.renderer } else { "" }
            runtime_latest_gl_version = if ($null -ne $latestGl) { $latestGl.gl_version } else { "" }
            runtime_latest_gl_vendor = if ($null -ne $latestGl) { $latestGl.gl_vendor } else { "" }
            status = $status
        })
}

$candidateOk = ($candidateDecision -eq "ready_for_beta") -and ($candidateReadiness -ge 80)
if (-not $candidateOk) {
    $issues.Add(("candidate not ready_for_beta (decision={0}, readiness={1})" -f $candidateDecision, $candidateReadiness))
}

$overallStatus = "pass"
if ($issues.Count -gt 0) {
    $overallStatus = "fail"
}
elseif ($waivers.Count -gt 0) {
    $overallStatus = "pass_with_waivers"
}

$timestampUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$stamp = Get-Stamp
if (-not (Test-Path -LiteralPath $OutDir)) {
    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
}
$jsonPath = Join-Path $OutDir ("v3_hardware_driver_matrix_{0}.json" -f $stamp)
$mdPath = Join-Path $OutDir ("v3_hardware_driver_matrix_{0}.md" -f $stamp)

$summary = [PSCustomObject]@{
    timestamp_utc = $timestampUtc
    instance_name = $InstanceName
    prism_instances_root = $PrismInstancesRoot
    logs_dir = $logsDir
    candidate_dir = (Resolve-Path -LiteralPath $resolvedCandidateDir).Path
    candidate_decision = $candidateDecision
    candidate_readiness_percent = $candidateReadiness
    candidate_jar_sha256 = $candidateJarHash
    allow_missing_vendors = [bool]$AllowMissingVendors
    overall_status = $overallStatus
    issues = $issues.ToArray()
    waivers = $waivers.ToArray()
    vendor_matrix = $vendorMatrix.ToArray()
}

($summary | ConvertTo-Json -Depth 8) | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$mdLines = New-Object System.Collections.Generic.List[string]
$mdLines.Add("# V3 Hardware/Drivers Validation")
$mdLines.Add("")
$mdLines.Add(("- Timestamp UTC: {0}" -f $timestampUtc))
$mdLines.Add(("- Instance: {0}" -f $InstanceName))
$mdLines.Add(("- Candidate: {0}" -f $summary.candidate_dir))
$mdLines.Add(("- Candidate decision: {0} ({1}%)" -f $candidateDecision, $candidateReadiness))
$mdLines.Add(("- Candidate jar sha256: {0}" -f $candidateJarHash))
$mdLines.Add(("- Overall status: {0}" -f $overallStatus))
$mdLines.Add("")
$mdLines.Add("## Vendor Matrix")
$mdLines.Add("")
$mdLines.Add("| Vendor | Adapter Present | Runtime GL Seen | Status | Drivers |")
$mdLines.Add("|---|---|---|---|---|")
foreach ($row in $vendorMatrix) {
    $drivers = if ($row.driver_versions.Count -gt 0) { ($row.driver_versions -join ", ") } else { "-" }
    $mdLines.Add(("| {0} | {1} | {2} | {3} | {4} |" -f $row.vendor, $row.adapter_present, $row.runtime_gl_seen, $row.status, $drivers))
}
$mdLines.Add("")
$mdLines.Add("## Waivers")
if ($waivers.Count -eq 0) {
    $mdLines.Add("- none")
}
else {
    foreach ($item in $waivers) { $mdLines.Add(("- {0}" -f $item)) }
}
$mdLines.Add("")
$mdLines.Add("## Issues")
if ($issues.Count -eq 0) {
    $mdLines.Add("- none")
}
else {
    foreach ($item in $issues) { $mdLines.Add(("- {0}" -f $item)) }
}
$mdLines.Add("")
$mdLines.Add("## Runtime GL Evidence (latest per vendor)")
foreach ($row in $vendorMatrix) {
    if ([string]::IsNullOrWhiteSpace($row.runtime_latest_gl_renderer)) {
        $mdLines.Add(("- {0}: none" -f $row.vendor))
    }
    else {
        $mdLines.Add(("- {0}: {1} | GL {2} | {3} | source={4}" -f $row.vendor, $row.runtime_latest_gl_renderer, $row.runtime_latest_gl_version, $row.runtime_latest_gl_vendor, $row.runtime_latest_gl_file))
    }
}
$mdLines | Set-Content -LiteralPath $mdPath -Encoding UTF8

Write-Host ""
Write-Host "PauC V3 hardware/drivers validation"
Write-Host "-----------------------------------"
Write-Host ""
$summary | Format-List
Write-Host ""
Write-Host ("Report JSON: {0}" -f (Resolve-Path -LiteralPath $jsonPath).Path)
Write-Host ("Report MD:   {0}" -f (Resolve-Path -LiteralPath $mdPath).Path)

if ($PassThru) {
    $summary
}

if ($overallStatus -eq "fail") {
    exit 2
}
