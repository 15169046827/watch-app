param(
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT
)

$ErrorActionPreference = 'Stop'
$expectedDigest = '138cb8267ee755e9d2ac92fb3b93428b872fdda68712f6744b9d7f8bca6a2faa'
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk) {
    throw 'Android SDK not found. Pass -AndroidSdk or set ANDROID_SDK_ROOT.'
}
$buildTools = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory |
    Sort-Object Name -Descending
$apksigner = $buildTools |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1

if (-not $apksigner) {
    throw "apksigner.bat was not found below $AndroidSdk\build-tools"
}

$workspace = Split-Path -Parent $PSScriptRoot
$apks = Get-ChildItem -LiteralPath $workspace -Recurse -File -Filter '*.apk' |
    Where-Object {
        $_.FullName -match '\\(?:build|\.build)\\(?:[^\\]+\\)*outputs\\apk\\' -and
        $_.Name -match 'debug'
    } |
    Sort-Object FullName

if (-not $apks) {
    throw "No Debug APKs were found below $workspace"
}

foreach ($apk in $apks) {
    $relativePath = $apk.FullName.Substring($workspace.Length).TrimStart('\')

    $certificateOutput = & $apksigner verify --print-certs $apk.FullName 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "APK signature verification failed: $($apk.FullName)`n$certificateOutput"
    }

    $digestLine = $certificateOutput |
        Where-Object { $_ -match 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)' } |
        Select-Object -First 1
    if (-not $digestLine -or $digestLine -notmatch '([0-9a-fA-F]{64})') {
        throw "SHA-256 certificate digest was not found: $($apk.FullName)"
    }

    $actualDigest = $Matches[1].ToLowerInvariant()
    if ($actualDigest -ne $expectedDigest) {
        throw "Unexpected signer for $($apk.FullName). Expected $expectedDigest, got $actualDigest"
    }

    Write-Host "OK  $relativePath  $actualDigest"
}

Write-Host 'All debug APK signatures match the shared X3 development key.'
