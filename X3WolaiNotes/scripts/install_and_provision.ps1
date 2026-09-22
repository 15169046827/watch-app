param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
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
$apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$packageName = 'com.canace.x3wolainotes'
$signatureVerifier = Join-Path (Split-Path -Parent $projectRoot) 'scripts\verify-debug-signatures.ps1'

if (-not $adb -or -not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw 'ADB not found. Pass -AndroidSdk, set ANDROID_SDK_ROOT, or add platform-tools to PATH.'
}
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
    throw "APK not found: $apk"
}
if (-not (Test-Path -LiteralPath $signatureVerifier -PathType Leaf)) {
    throw 'Shared signature verifier not found'
}
& $signatureVerifier -AndroidSdk $AndroidSdk -Product X3WolaiNotes

$token = $env:WOLAI_OPENAPI_TOKEN
if (-not $token) {
    $token = [Environment]::GetEnvironmentVariable('WOLAI_OPENAPI_TOKEN', 'User')
}
if (-not $token -or $token.Length -lt 20) {
    throw 'WOLAI_OPENAPI_TOKEN is not configured'
}

$stateOutput = & $adb -s $Serial get-state 2>$null
$stateExitCode = $LASTEXITCODE
$state = if ($stateOutput) { ([string]($stateOutput | Select-Object -First 1)).Trim() } else { 'unavailable' }
if ($stateExitCode -ne 0 -or $state -ne 'device') {
    throw "Selected device is not online (state: $state)"
}

& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw 'APK installation failed'
}

& $adb -s $Serial shell am force-stop $packageName | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to stop the existing app process'
}

$tokenImported = $false
try {
    & $adb -s $Serial shell run-as $packageName mkdir -p files | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to prepare app-private files directory'
    }

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
        throw 'Token provisioning failed'
    }
    $stdout = $null
    $stderr = $null

    & $adb -s $Serial shell run-as $packageName chmod 600 files/wolai.token | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to protect provisioned token file'
    }

    & $adb -s $Serial shell am start -n "$packageName/.MainActivity" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'App launch failed'
    }

    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        & $adb -s $Serial shell run-as $packageName ls files/wolai.token 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            $tokenImported = $true
            break
        }
        Start-Sleep -Milliseconds 200
    }
    if (-not $tokenImported) {
        throw 'App did not import and remove the temporary token file'
    }
} finally {
    $token = $null
    if (-not $tokenImported) {
        & $adb -s $Serial shell run-as $packageName rm -f files/wolai.token 2>$null | Out-Null
    }
}

'INSTALLED_AND_PROVISIONED'
