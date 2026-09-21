param()

$ErrorActionPreference = 'Stop'
$gate = Join-Path $PSScriptRoot 'verify-network-safety.ps1'
$temporaryBase = [System.IO.Path]::GetTempPath()
$testRoot = Join-Path $temporaryBase ("x3-network-safety-" + [guid]::NewGuid())
$safeRoot = Join-Path $testRoot 'safe'
$unsafeRoot = Join-Path $testRoot 'unsafe'

try {
    New-Item -ItemType Directory -Path $safeRoot, $unsafeRoot | Out-Null
    Set-Content -LiteralPath (Join-Path $safeRoot 'sample.ps1') -Value 'adb shell getprop ro.build.version.sdk'
    & $gate -Root $safeRoot | Out-Null

    $forbiddenCommand = @(
        'adb shell settings ',
        'put global ',
        'http_',
        'proxy 127.0.0.1:8888'
    ) -join ''
    Set-Content -LiteralPath (Join-Path $unsafeRoot 'sample.ps1') -Value $forbiddenCommand

    $negativeRejected = $false
    try {
        & $gate -Root $unsafeRoot | Out-Null
    } catch {
        $negativeRejected = $true
    }
    if (-not $negativeRejected) {
        throw 'Network safety gate accepted the forbidden negative fixture.'
    }

    Write-Host 'NETWORK_SAFETY_TESTS_OK positive=accepted negative=rejected'
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        $resolvedTestRoot = (Resolve-Path -LiteralPath $testRoot).Path
        if (-not $resolvedTestRoot.StartsWith(
                $temporaryBase,
                [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove unexpected test path: $resolvedTestRoot"
        }
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
