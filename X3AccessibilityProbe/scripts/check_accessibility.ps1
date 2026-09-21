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

$deviceOutput = & $AdbPath devices -l
if ($LASTEXITCODE -ne 0) {
    throw "ADB device discovery failed with exit code $LASTEXITCODE."
}
$connectedSerials = @(
    $deviceOutput |
        Where-Object { $_ -match '^(\S+)\s+device(?:\s|$)' } |
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

function Invoke-AdbCommand {
    param([Parameter(Mandatory)][string[]]$Arguments)

    & $AdbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "ADB command failed for '$Serial' with exit code ${LASTEXITCODE}: $($Arguments -join ' ')"
    }
}

Write-Output "=== Device ==="
$deviceOutput | Write-Output

Write-Output "=== Probe package ==="
Invoke-AdbCommand -Arguments @('shell', 'pm', 'list', 'packages', 'com.example.x3accessibilityprobe')
Invoke-AdbCommand -Arguments @('shell', 'dumpsys', 'package', 'com.example.x3accessibilityprobe') |
    Select-String "versionName=|versionCode=|ProbeAccessibilityService|BIND_ACCESSIBILITY_SERVICE" |
    ForEach-Object { $_.Line }

Write-Output "=== Accessibility state ==="
Invoke-AdbCommand -Arguments @('shell', 'dumpsys', 'accessibility') |
    Select-String "installedServiceCount|Bound services|Enabled services|Binding services|Crashed services|x3accessibilityprobe" |
    ForEach-Object { $_.Line }

Write-Output "accessibility_enabled=$(Invoke-AdbCommand -Arguments @('shell', 'settings', 'get', 'secure', 'accessibility_enabled'))"
Write-Output "enabled_accessibility_services=$(Invoke-AdbCommand -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services'))"

Write-Output "=== Official accessibility settings resolver ==="
Invoke-AdbCommand -Arguments @(
    'shell', 'cmd', 'package', 'resolve-activity', '--brief', '--user', '0',
    '-a', 'android.settings.ACCESSIBILITY_SETTINGS')
