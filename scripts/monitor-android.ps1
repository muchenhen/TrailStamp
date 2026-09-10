param(
    [double]$DurationHours = 8,
    [int]$IntervalSeconds = 300,
    [string]$OutputPath = "reports\android-monitor.jsonl",
    [switch]$PreventWindowsSleep
)

$ErrorActionPreference = "Continue"
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$repo = Split-Path -Parent $PSScriptRoot
$resolvedOutput = if ([System.IO.Path]::IsPathRooted($OutputPath)) { $OutputPath } else { Join-Path $repo $OutputPath }
$parent = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$deadline = (Get-Date).AddHours($DurationHours)
$executionStateType = $null
if ($PreventWindowsSleep) {
    $executionStateType = Add-Type -PassThru -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class GPSLogExecutionState {
    [DllImport("kernel32.dll")]
    public static extern uint SetThreadExecutionState(uint flags);
}
"@
    $continuousSystemRequired = [uint32]2147483649
    if ($executionStateType::SetThreadExecutionState($continuousSystemRequired) -eq 0) {
        throw "Could not prevent Windows sleep"
    }
}

function Write-MonitorSample([bool]$Complete) {
    $service = (& adb shell dumpsys activity services com.muchenhen.gpslog 2>&1) -join "`n"
    $notifications = (& adb shell dumpsys notification --noredact 2>&1) -join "`n"
    $power = (& adb shell dumpsys power 2>&1) -join "`n"
    $battery = (& adb shell dumpsys battery 2>&1) -join "`n"
    $locationEnabled = ((& adb shell cmd location is-location-enabled --user 0 2>&1) -join "").Trim()
    $appProcessId = ((& adb shell pidof com.muchenhen.gpslog 2>&1) -join "").Trim()
    $notificationRecord = [regex]::Match(
        $notifications,
        'NotificationRecord\([^\r\n]*pkg=com\.muchenhen\.gpslog[\s\S]*?(?=\r?\n\s*NotificationRecord\(|\z)'
    ).Value
    $notificationText = [regex]::Match(
        $notificationRecord,
        'android\.bigText=String \(([^\r\n]*)\)'
    ).Groups[1].Value
    if (-not $notificationText) {
        $gpslogTitleIndex = $notifications.IndexOf('android.title=String (GPSLog 正在记录)')
        if ($gpslogTitleIndex -ge 0) {
            $gpslogNotificationTail = $notifications.Substring(
                $gpslogTitleIndex,
                [math]::Min(4096, $notifications.Length - $gpslogTitleIndex)
            )
            $notificationText = [regex]::Match(
                $gpslogNotificationTail,
                'android\.bigText=String \(([^\r\n]*)\)'
            ).Groups[1].Value
        }
    }
    $wakefulness = [regex]::Match($power, 'mWakefulness=([^\r\n]+)').Groups[1].Value
    $batteryLevel = [regex]::Match($battery, '(?m)^\s*level:\s*(\d+)').Groups[1].Value
    $batteryPowered = [regex]::IsMatch($battery, '(?m)^\s*(AC|USB|Wireless) powered:\s*true')
    $record = [ordered]@{
        timestamp = (Get-Date).ToUniversalTime().ToString("o")
        adb = ((& adb get-state 2>&1) -join "").Trim()
        pid = $appProcessId
        foregroundService = $service.Contains("isForeground=true")
        notificationText = $notificationText
        wakefulness = $wakefulness
        locationEnabled = $locationEnabled
        batteryLevel = $batteryLevel
        batteryPowered = $batteryPowered
        monitorComplete = $Complete
    }
    ($record | ConvertTo-Json -Compress) | Add-Content -LiteralPath $resolvedOutput -Encoding UTF8
}

try {
    while ((Get-Date) -lt $deadline) {
        Write-MonitorSample -Complete $false
        Start-Sleep -Seconds $IntervalSeconds
    }

    Write-MonitorSample -Complete $true
} finally {
    if ($PreventWindowsSleep -and $null -ne $executionStateType) {
        [void]$executionStateType::SetThreadExecutionState([uint32]2147483648)
    }
}
