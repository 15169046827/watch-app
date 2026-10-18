param()

$ErrorActionPreference = 'Stop'
$gate = Join-Path $PSScriptRoot 'verify-watch-app-audit-profile.ps1'
$sourceRoot = Split-Path -Parent $PSScriptRoot
$temporaryBase = [System.IO.Path]::GetTempPath()
$testRoot = Join-Path $temporaryBase ('watch-profile-gate-' + [guid]::NewGuid())

try {
    foreach ($path in @('config\audit', 'docs\audit')) {
        New-Item -ItemType Directory -Path (Join-Path $testRoot $path) -Force | Out-Null
    }
    $configAsset = 'config\audit\watch-app-audit-profile.json'
    $profileAsset = 'docs\audit\WATCH_APP_AUDIT_PROFILE.md'
    foreach ($asset in @($configAsset, $profileAsset)) {
        Copy-Item -LiteralPath (Join-Path $sourceRoot $asset) `
            -Destination (Join-Path $testRoot $asset)
    }
    & $gate -Root $testRoot | Out-Null

    $configPath = Join-Path $testRoot $configAsset
    $sourceConfigPath = Join-Path $sourceRoot $configAsset
    $negativeCases = @(
        @{ Name = 'project'; Mutate = {
                param($config)
                $config.projects = @($config.projects | Where-Object { $_ -ne 'x3_schedule' })
            } },
        @{ Name = 'public product'; Mutate = {
                param($config)
                $config.productClassification.publicProducts = @(
                    $config.productClassification.publicProducts |
                        Where-Object { $_ -ne 'x3_schedule' })
            } },
        @{ Name = 'audit order'; Mutate = {
                param($config)
                $config.auditOrder = @($config.auditOrder | Where-Object { $_ -ne 'x3_schedule' })
            } }
    )
    foreach ($case in $negativeCases) {
        Copy-Item -LiteralPath $sourceConfigPath -Destination $configPath -Force
        $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
        & $case.Mutate $config
        $config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $configPath
        $rejected = $false
        try {
            & $gate -Root $testRoot | Out-Null
        } catch {
            $rejected = $true
        }
        if (-not $rejected) {
            throw "Watch-app audit-profile gate accepted missing X3Schedule $($case.Name)."
        }
    }
    Write-Host 'WATCH_APP_AUDIT_PROFILE_TESTS_OK positive=accepted negatives=3_rejected'
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
