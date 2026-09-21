param(
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT,
    [switch]$RequireAllApplications,
    [ValidateSet(
        'X3AccessibilityProbe',
        'X3VoiceLedger',
        'X3WolaiNotes',
        'X3OfflineReader',
        'X3Schedule')]
    [string]$Product
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
$requiredRelativePaths = @(
    'X3AccessibilityProbe\.build\app\outputs\apk\debug\app-debug.apk',
    'X3VoiceLedger\app\build\outputs\apk\debug\app-debug.apk',
    'X3VoiceLedger\phone\build\outputs\apk\debug\phone-debug.apk',
    'X3WolaiNotes\app\build\outputs\apk\debug\app-debug.apk',
    'X3OfflineReader\watch\build\outputs\apk\debug\watch-debug.apk',
    'X3Schedule\phone\build\outputs\apk\debug\phone-debug.apk',
    'X3Schedule\watch\build\outputs\apk\debug\watch-debug.apk'
)

$productRelativePaths = @{
    X3AccessibilityProbe = @($requiredRelativePaths[0])
    X3VoiceLedger = @($requiredRelativePaths[1], $requiredRelativePaths[2])
    X3WolaiNotes = @($requiredRelativePaths[3])
    X3OfflineReader = @($requiredRelativePaths[4])
    X3Schedule = @($requiredRelativePaths[5], $requiredRelativePaths[6])
}

$productSourceMarkers = @{
    X3AccessibilityProbe = 'X3AccessibilityProbe\app\build.gradle'
    X3VoiceLedger = 'X3VoiceLedger\settings.gradle'
    X3WolaiNotes = 'X3WolaiNotes\settings.gradle'
    X3OfflineReader = 'X3OfflineReader\settings.gradle'
    X3Schedule = 'X3Schedule\settings.gradle'
}
$productOrder = @(
    'X3AccessibilityProbe',
    'X3VoiceLedger',
    'X3WolaiNotes',
    'X3OfflineReader',
    'X3Schedule'
)

if ($RequireAllApplications -and $Product) {
    throw 'Pass either -RequireAllApplications or -Product, not both.'
}

if ($RequireAllApplications -or $Product) {
    $selectedRelativePaths = if ($Product) {
        $productRelativePaths[$Product]
    } else {
        @(
            foreach ($productName in $productOrder) {
                $sourceMarker = Join-Path $workspace $productSourceMarkers[$productName]
                if (Test-Path -LiteralPath $sourceMarker -PathType Leaf) {
                    $productRelativePaths[$productName]
                }
            }
        )
    }
    $missing = @(
        foreach ($relativePath in $selectedRelativePaths) {
            $candidate = Join-Path $workspace $relativePath
            if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
                $relativePath
            }
        }
    )
    if ($missing.Count -gt 0) {
        throw "Required Debug APKs are missing:`n$($missing -join "`n")"
    }
    $apks = $selectedRelativePaths |
        ForEach-Object { Get-Item -LiteralPath (Join-Path $workspace $_) }
} else {
    $apks = Get-ChildItem -LiteralPath $workspace -Recurse -File -Filter '*.apk' |
        Where-Object {
            $_.FullName -match '\\(?:build|\.build)\\(?:[^\\]+\\)*outputs\\apk\\' -and
            $_.Name -match 'debug'
        } |
        Sort-Object FullName
}

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
