param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT
)

$ErrorActionPreference = 'Stop'
if (-not $Serial) { throw 'Device serial not configured. Pass -Serial or set ANDROID_SERIAL.' }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
$adb = if ($AndroidSdk) { Join-Path $AndroidSdk 'platform-tools\adb.exe' } else { $null }
if (-not $adb -or -not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($command) { $adb = $command.Source }
}
if (-not $adb -or -not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw 'ADB not found. Pass -AndroidSdk, set ANDROID_SDK_ROOT, or add platform-tools to PATH.'
}

$stateOutput = & $adb -s $Serial get-state 2>$null
$state = if ($stateOutput) { ([string]($stateOutput | Select-Object -First 1)).Trim() } else { 'unavailable' }
if ($state -ne 'device') {
    throw "Device $Serial is not online (state: $state)"
}

$model = (& $adb -s $Serial shell getprop ro.product.model).Trim()
$sdk = (& $adb -s $Serial shell getprop ro.build.version.sdk).Trim()
$abi = (& $adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
"ONLINE serial=$Serial model=$model api=$sdk abi=$abi"
