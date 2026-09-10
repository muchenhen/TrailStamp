param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
$records = @(Get-Content -LiteralPath $InputPath | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json -DateKind String })
if ($records.Count -eq 0) { throw "Monitor log contains no records: $InputPath" }

$timestamps = @($records | ForEach-Object { [DateTimeOffset]::Parse($_.timestamp) })
$sampleIntervals = for ($index = 1; $index -lt $timestamps.Count; $index++) {
    ($timestamps[$index] - $timestamps[$index - 1]).TotalSeconds
}
$pointSamples = @(
    for ($index = 0; $index -lt $records.Count; $index++) {
        $matched = [regex]::Match([string]$records[$index].notificationText, '^(\d+)\s*点')
        if ($matched.Success) {
            [pscustomobject]@{ timestamp = $timestamps[$index]; count = [int]$matched.Groups[1].Value }
        }
    }
)
$pointCountRegressions = 0
$maxPointStallSeconds = 0.0
if ($pointSamples.Count) {
    $lastCount = $pointSamples[0].count
    $lastIncreaseAt = $pointSamples[0].timestamp
    foreach ($sample in $pointSamples | Select-Object -Skip 1) {
        $maxPointStallSeconds = [math]::Max($maxPointStallSeconds, ($sample.timestamp - $lastIncreaseAt).TotalSeconds)
        if ($sample.count -lt $lastCount) { $pointCountRegressions++ }
        if ($sample.count -gt $lastCount) { $lastIncreaseAt = $sample.timestamp }
        $lastCount = $sample.count
    }
}
$processIds = @($records | ForEach-Object { [string]$_.pid } | Where-Object { $_ } | Select-Object -Unique)

$summary = [ordered]@{
    schema = "gpslog.android-monitor-summary/v1"
    input = (Resolve-Path -LiteralPath $InputPath).Path
    startedAt = $timestamps[0].ToUniversalTime().ToString("o")
    finishedAt = $timestamps[-1].ToUniversalTime().ToString("o")
    durationHours = [math]::Round(($timestamps[-1] - $timestamps[0]).TotalHours, 4)
    samples = $records.Count
    monitorComplete = [bool]$records[-1].monitorComplete
    maxSampleIntervalSeconds = if ($sampleIntervals.Count) { [math]::Round(($sampleIntervals | Measure-Object -Maximum).Maximum, 3) } else { 0 }
    adbFailures = @($records | Where-Object { $_.adb -ne "device" }).Count
    foregroundServiceFailures = @($records | Where-Object { -not $_.foregroundService }).Count
    notificationMissing = @($records | Where-Object { -not $_.notificationText }).Count
    statusOnlyNotificationSamples = $records.Count - $pointSamples.Count - @($records | Where-Object { -not $_.notificationText }).Count
    locationDisabledSamples = @($records | Where-Object { $_.locationEnabled -ne "true" }).Count
    distinctProcessIds = $processIds
    firstPointCount = if ($pointSamples.Count) { $pointSamples[0].count } else { $null }
    lastPointCount = if ($pointSamples.Count) { $pointSamples[-1].count } else { $null }
    pointCountRegressions = $pointCountRegressions
    maxObservedPointStallSeconds = [math]::Round($maxPointStallSeconds, 3)
    batteryLevelStart = [int]$records[0].batteryLevel
    batteryLevelEnd = [int]$records[-1].batteryLevel
    externallyPoweredSamples = @($records | Where-Object { $_.batteryPowered }).Count
}

$json = $summary | ConvertTo-Json -Depth 5
if ($OutputPath) {
    $parent = Split-Path -Parent $OutputPath
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $json | Set-Content -LiteralPath $OutputPath -Encoding UTF8
}
$json
