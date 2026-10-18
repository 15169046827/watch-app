param()

$ErrorActionPreference = 'Stop'
$gate = Join-Path $PSScriptRoot 'verify-public-source-boundary.ps1'
$temporaryBase = [System.IO.Path]::GetTempPath()
$testRoot = Join-Path $temporaryBase ('public-source-boundary-' + [guid]::NewGuid())

try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    & git -C $testRoot init --quiet
    Set-Content -LiteralPath (Join-Path $testRoot 'README.md') -Value '# Safe fixture'
    & git -C $testRoot add README.md
    & $gate -Root $testRoot | Out-Null

    Set-Content -LiteralPath (Join-Path $testRoot 'AGENTS.md') -Value 'local coordination'
    & git -C $testRoot add -f AGENTS.md
    $pathRejected = $false
    try {
        & $gate -Root $testRoot | Out-Null
    } catch {
        $pathRejected = $true
    }
    if (-not $pathRejected) {
        throw 'Public source gate accepted a tracked local coordination file.'
    }
    & git -C $testRoot rm --cached --quiet AGENTS.md
    Remove-Item -LiteralPath (Join-Path $testRoot 'AGENTS.md') -Force

    $forbiddenPath = 'Machine path: ' + (@('E:', 'Project', 'private-workspace') -join '\')
    Set-Content -LiteralPath (Join-Path $testRoot 'leak.md') -Value $forbiddenPath
    & git -C $testRoot add leak.md
    $contentRejected = $false
    try {
        & $gate -Root $testRoot | Out-Null
    } catch {
        $contentRejected = $true
    }
    if (-not $contentRejected) {
        throw 'Public source gate accepted a machine-specific absolute path.'
    }

    Write-Host 'PUBLIC_SOURCE_BOUNDARY_TESTS_OK positive=accepted negatives=2_rejected'
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
