$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'prepare-melo-package.ps1')
$temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$root = Join-Path $temporaryBase ('melo-package-' + [guid]::NewGuid().ToString('N'))
try {
    $snapshots = Join-Path $root '.tools/melo/huggingface/hub/models--bert-base-multilingual-uncased/snapshots'
    foreach ($name in @('paired', 'legacy-only', 'safe-only')) {
        New-Item -ItemType Directory -Path (Join-Path $snapshots $name) -Force | Out-Null
    }
    foreach ($relative in @('paired/pytorch_model.bin', 'paired/model.safetensors',
            'legacy-only/pytorch_model.bin', 'safe-only/model.safetensors')) {
        [IO.File]::WriteAllText((Join-Path $snapshots $relative), 'public fixture')
    }
    $safe = Join-Path $snapshots 'paired/model.safetensors'
    $hash = (Get-FileHash -LiteralPath $safe).Hash
    if ((Remove-RedundantPackagedBertWeights -App $root) -ne 1 -or
        (Test-Path -LiteralPath (Join-Path $snapshots 'paired/pytorch_model.bin')) -or
        -not (Test-Path -LiteralPath (Join-Path $snapshots 'legacy-only/pytorch_model.bin')) -or
        (Get-FileHash -LiteralPath $safe).Hash -ne $hash -or
        (Remove-RedundantPackagedBertWeights -App $root) -ne 0) {
        throw 'Packaged BERT pruning must be scoped, lossless for safetensors and idempotent.'
    }
    [IO.File]::WriteAllText((Join-Path $snapshots 'paired/pytorch_model.bin'), 'fixture')
    [IO.File]::WriteAllText($safe, '')
    $rejected = $false
    try { Remove-RedundantPackagedBertWeights -App $root | Out-Null } catch { $rejected = $true }
    if (-not $rejected -or -not (Test-Path -LiteralPath (Join-Path $snapshots 'paired/pytorch_model.bin'))) {
        throw 'Corrupt safetensors must not cause legacy weight removal.'
    }
    Write-Output 'Melo package fixtures passed: pair-only pruning, safetensors unchanged, legacy-only retained, idempotence, corrupt-pair rejection.'
} finally {
    $resolved = [IO.Path]::GetFullPath($root)
    if (-not $resolved.StartsWith($temporaryBase.TrimEnd([IO.Path]::DirectorySeparatorChar) +
            [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^melo-package-[0-9a-f]{32}$') {
        throw 'Unsafe fixture cleanup path'
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
