param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $Serial) { throw 'Device serial not configured. Pass -Serial or set ANDROID_SERIAL.' }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
$adb = if ($AndroidSdk) { Join-Path $AndroidSdk 'platform-tools\adb.exe' } else { $null }
if (-not $adb -or -not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($command) { $adb = $command.Source }
}
$apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$packageName = 'com.canace.x3wolainotes'

if (-not $adb -or -not (Test-Path -LiteralPath $adb -PathType Leaf)) { throw 'ADB not found. Pass -AndroidSdk, set ANDROID_SDK_ROOT, or add platform-tools to PATH.' }
if (-not (Test-Path -LiteralPath $apk)) { throw "APK not found: $apk" }

$token = $env:WOLAI_OPENAPI_TOKEN
if (-not $token) {
    $token = [Environment]::GetEnvironmentVariable('WOLAI_OPENAPI_TOKEN', 'User')
}
if (-not $token -or $token.Length -lt 20) {
    throw 'WOLAI_OPENAPI_TOKEN is not configured'
}

$stateOutput = & $adb -s $Serial get-state 2>$null
$state = if ($stateOutput) { ([string]($stateOutput | Select-Object -First 1)).Trim() } else { 'unavailable' }
if ($state -ne 'device') {
    throw "Device $Serial is not online (state: $state)"
}

& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed' }

& $adb -s $Serial shell am force-stop $packageName | Out-Null

& $adb -s $Serial shell run-as $packageName mkdir -p files | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Unable to prepare app-private files directory' }

$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $adb
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardInput = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.ArgumentList.Add('-s')
$startInfo.ArgumentList.Add($Serial)
$startInfo.ArgumentList.Add('shell')
$startInfo.ArgumentList.Add('run-as')
$startInfo.ArgumentList.Add($packageName)
$startInfo.ArgumentList.Add('tee')
$startInfo.ArgumentList.Add('files/wolai.token')

$process = [System.Diagnostics.Process]::Start($startInfo)
$process.StandardInput.Write($token)
$process.StandardInput.Close()
$stdout = $process.StandardOutput.ReadToEnd()
$stderr = $process.StandardError.ReadToEnd()
$process.WaitForExit()
if ($process.ExitCode -ne 0) {
    throw "Token provisioning failed: $stderr"
}
$stdout = $null

& $adb -s $Serial shell run-as $packageName chmod 600 files/wolai.token | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Unable to protect provisioned token file' }

& $adb -s $Serial shell am start -n "$packageName/.MainActivity" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'App launch failed' }

'INSTALLED_AND_PROVISIONED'
