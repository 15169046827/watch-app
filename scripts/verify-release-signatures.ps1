param(
    [Parameter(Mandatory = $true)]
    [string[]]$Artifact,
    [string]$ExpectedDigest = $env:X3_RELEASE_CERT_SHA256,
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT
)

$ErrorActionPreference = 'Stop'
if (-not $ExpectedDigest) {
    throw 'Expected Release certificate SHA-256 is required.'
}
$expected = ($ExpectedDigest -replace '[^0-9A-Fa-f]', '').ToLowerInvariant()
if ($expected.Length -ne 64) {
    throw 'Expected Release certificate SHA-256 must contain 64 hexadecimal characters.'
}
$debugDigest = '138cb8267ee755e9d2ac92fb3b93428b872fdda68712f6744b9d7f8bca6a2faa'
if ($expected -eq $debugDigest) {
    throw 'The shared Debug certificate cannot be accepted for Release artifacts.'
}
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk) {
    throw 'Android SDK not found. Pass -AndroidSdk or set ANDROID_SDK_ROOT.'
}
$apksigner = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
if (-not $apksigner) {
    throw 'apksigner.bat was not found in the Android SDK.'
}

foreach ($item in $Artifact) {
    $resolved = (Resolve-Path -LiteralPath $item).Path
    $output = & $apksigner verify --print-certs $resolved 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Release signature verification failed: $resolved`n$output"
    }
    $line = $output |
        Where-Object { $_ -match 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)' } |
        Select-Object -First 1
    if (-not $line -or $line -notmatch '([0-9a-fA-F]{64})') {
        throw "Release certificate digest was not found: $resolved"
    }
    $actual = $Matches[1].ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "Unexpected Release signer for $resolved. Expected $expected, got $actual"
    }
    Write-Host "RELEASE_SIGNATURE_OK artifact=$resolved sha256=$actual"
}
