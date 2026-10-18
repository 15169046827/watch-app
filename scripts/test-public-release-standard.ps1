param()

$ErrorActionPreference = 'Stop'
$gate = Join-Path $PSScriptRoot 'verify-public-release-standard.ps1'
$sourceRoot = Split-Path -Parent $PSScriptRoot
$temporaryBase = [System.IO.Path]::GetTempPath()
$testRoot = Join-Path $temporaryBase ('wars-gate-' + [guid]::NewGuid())

try {
    $paths = @(
        'config\release',
        'docs\release',
        'gradle',
        'scripts',
        'X3OfflineReader\builder'
    )
    foreach ($path in $paths) {
        New-Item -ItemType Directory -Path (Join-Path $testRoot $path) -Force | Out-Null
    }
    $assets = @(
        'README.md',
        'SECURITY.md',
        'CONTRIBUTING.md',
        'config\release\wars-1.0.json',
        'docs\release\WARS_1_0.md',
        'docs\release\PRODUCTION_ACCEPTANCE_CHECKLIST.md',
        'docs\release\PUBLIC_SOURCE_STATUS.md',
        'gradle\shared-release-signing-v1.gradle',
        'scripts\verify-release-signatures.ps1',
        'scripts\verify-authenticode.ps1',
        'X3OfflineReader\builder\package-windows.ps1',
        'X3OfflineReader\builder\package-windows-preview.ps1'
    )
    foreach ($asset in $assets) {
        Copy-Item -LiteralPath (Join-Path $sourceRoot $asset) `
            -Destination (Join-Path $testRoot $asset)
    }
    & $gate -Root $testRoot | Out-Null

    $configPath = Join-Path $testRoot 'config\release\wars-1.0.json'
    $sourceConfigPath = Join-Path $sourceRoot 'config\release\wars-1.0.json'
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $config.publicProducts = @($config.publicProducts | Where-Object {
            $_ -notin @('x3_schedule_phone', 'x3_schedule_watch')
        })
    $config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $configPath
    $missingScheduleRejected = $false
    try {
        & $gate -Root $testRoot | Out-Null
    } catch {
        $missingScheduleRejected = $true
    }
    if (-not $missingScheduleRejected) {
        throw 'WARS gate accepted a public-product list without X3Schedule.'
    }
    Copy-Item -LiteralPath $sourceConfigPath -Destination $configPath -Force
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $config.productSpecificControls.x3Schedule.debugToReleaseInPlaceUpgradeAllowed = $true
    $config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $configPath
    $unsafeScheduleMigrationRejected = $false
    try {
        & $gate -Root $testRoot | Out-Null
    } catch {
        $unsafeScheduleMigrationRejected = $true
    }
    if (-not $unsafeScheduleMigrationRejected) {
        throw 'WARS gate accepted an in-place X3Schedule Debug-to-Release upgrade.'
    }
    Copy-Item -LiteralPath $sourceConfigPath -Destination $configPath -Force
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $config.distributionTracks.formalPublicProduction.windowsAuthenticodeRequired = $false
    $config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $configPath
    $negativeRejected = $false
    try {
        & $gate -Root $testRoot | Out-Null
    } catch {
        $negativeRejected = $true
    }
    if (-not $negativeRejected) {
        throw 'WARS gate accepted weakened formal-production Authenticode.'
    }
    Copy-Item -LiteralPath $sourceConfigPath -Destination $configPath -Force
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $config.distributionTracks.personalGithubPreview.wolaiPersonalToken.encryptedLocalStorageRequired = $false
    $config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $configPath
    $unsafeTokenRejected = $false
    try {
        & $gate -Root $testRoot | Out-Null
    } catch {
        $unsafeTokenRejected = $true
    }
    if (-not $unsafeTokenRejected) {
        throw 'WARS gate accepted unencrypted personal-token storage.'
    }
    Write-Host 'PUBLIC_RELEASE_STANDARD_TESTS_OK positive=accepted negatives=4_rejected'
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
