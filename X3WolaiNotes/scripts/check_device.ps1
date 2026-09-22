param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT
)

$ErrorActionPreference = 'Stop'
if (-not $Serial) {
    throw 'Device serial not configured. Pass -Serial or set ANDROID_SERIAL.'
}
if (-not $AndroidSdk) {
    $AndroidSdk = $env:ANDROID_HOME
}
$adb = if ($AndroidSdk) { Join-Path $AndroidSdk 'platform-tools\adb.exe' } else { $null }
if (-not $adb -or -not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($command) {
        $adb = $command.Source
    }
}
if (-not $adb -or -not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw 'ADB not found. Pass -AndroidSdk, set ANDROID_SDK_ROOT, or add platform-tools to PATH.'
}

$stateOutput = & $adb -s $Serial get-state 2>$null
$stateExitCode = $LASTEXITCODE
$state = if ($stateOutput) { ([string]($stateOutput | Select-Object -First 1)).Trim() } else { 'unavailable' }
if ($stateExitCode -ne 0 -or $state -ne 'device') {
    throw "Selected device is not online (state: $state)"
}

$model = (& $adb -s $Serial shell getprop ro.product.model).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to read model from selected device'
}
$sdk = (& $adb -s $Serial shell getprop ro.build.version.sdk).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to read Android API level from selected device'
}
$abi = (& $adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to read ABI from selected device'
}
"ONLINE model=$model api=$sdk abi=$abi"
