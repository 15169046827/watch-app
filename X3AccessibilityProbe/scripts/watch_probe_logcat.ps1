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

Write-Output "Streaming X3 Accessibility Probe logs. Press Ctrl+C to stop."
& $AdbPath logcat -v time -s "X3AccessibilityProbe:I" "*:S"
