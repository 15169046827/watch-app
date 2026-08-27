param(
    [string]$AdbPath
)

$ErrorActionPreference = "Stop"

if (-not $AdbPath) {
    $sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
    if ($sdkRoot) {
        $candidate = Join-Path $sdkRoot 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $AdbPath = $candidate }
    }
}
if (-not $AdbPath) {
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($command) { $AdbPath = $command.Source }
}
if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw 'ADB not found. Pass -AdbPath, set ANDROID_SDK_ROOT, or add platform-tools to PATH.'
}

Write-Output "=== Device ==="
& $AdbPath devices -l

Write-Output "=== Probe package ==="
& $AdbPath shell pm list packages com.example.x3accessibilityprobe
& $AdbPath shell dumpsys package com.example.x3accessibilityprobe |
    Select-String "versionName=|versionCode=|ProbeAccessibilityService|BIND_ACCESSIBILITY_SERVICE" |
    ForEach-Object { $_.Line }

Write-Output "=== Accessibility state ==="
& $AdbPath shell dumpsys accessibility |
    Select-String "installedServiceCount|Bound services|Enabled services|Binding services|Crashed services|x3accessibilityprobe" |
    ForEach-Object { $_.Line }

Write-Output "accessibility_enabled=$(& $AdbPath shell settings get secure accessibility_enabled)"
Write-Output "enabled_accessibility_services=$(& $AdbPath shell settings get secure enabled_accessibility_services)"

Write-Output "=== Official accessibility settings resolver ==="
& $AdbPath shell cmd package resolve-activity --brief --user 0 `
    -a android.settings.ACCESSIBILITY_SETTINGS
