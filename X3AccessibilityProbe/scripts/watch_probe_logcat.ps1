param(
    [string]$AdbPath,
    [string]$Serial
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

$deviceOutput = & $AdbPath devices
if ($LASTEXITCODE -ne 0) {
    throw "ADB device discovery failed with exit code $LASTEXITCODE."
}
$connectedSerials = @(
    $deviceOutput |
        Where-Object { $_ -match '^(\S+)\s+device$' } |
        ForEach-Object { $Matches[1] }
)
if ($Serial) {
    if ($Serial -notin $connectedSerials) {
        throw "Requested device '$Serial' is not connected and authorized."
    }
} elseif ($connectedSerials.Count -eq 1) {
    $Serial = $connectedSerials[0]
} else {
    throw "Expected exactly one connected device, found $($connectedSerials.Count). Pass -Serial."
}

Write-Output "Streaming X3 Accessibility Probe logs from $Serial. Press Ctrl+C to stop."
& $AdbPath -s $Serial logcat -v time -s "X3AccessibilityProbe:I" "*:S"
if ($LASTEXITCODE -ne 0) {
    throw "ADB logcat failed for '$Serial' with exit code $LASTEXITCODE."
}
