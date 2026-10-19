function Remove-RedundantPackagedBertWeights {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$App)

    $root = [IO.Path]::GetFullPath($App)
    $snapshots = Join-Path $root '.tools/melo/huggingface/hub/models--bert-base-multilingual-uncased/snapshots'
    $ancestor = $snapshots
    while ($ancestor) {
        if ((Test-Path -LiteralPath $ancestor) -and
            ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Packaged model cache must not traverse reparse points.'
        }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
    if (-not (Test-Path -LiteralPath $snapshots -PathType Container)) { return 0 }
    $removed = 0
    foreach ($snapshot in Get-ChildItem -LiteralPath $snapshots -Directory -Force) {
        if ($snapshot.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Packaged model snapshots must be regular directories.'
        }
        $legacy = Join-Path $snapshot.FullName 'pytorch_model.bin'
        $safe = Join-Path $snapshot.FullName 'model.safetensors'
        if (-not (Test-Path -LiteralPath $legacy -PathType Leaf) -or
            -not (Test-Path -LiteralPath $safe -PathType Leaf)) { continue }
        foreach ($file in @($legacy, $safe)) {
            $item = Get-Item -LiteralPath $file -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint -or $item.Length -eq 0) {
                throw 'Paired packaged BERT weights must be nonempty regular files.'
            }
        }
        # Only this known pretrained BERT cache pair; never touch the source environment,
        # Melo checkpoint.pth, other repositories, or a lone legacy checkpoint.
        Remove-Item -LiteralPath $legacy -Force
        $removed++
    }
    return $removed
}

function Remove-EmptyPackagedPythonCaches {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$App)
    $root = [IO.Path]::GetFullPath($App)
    $removed = 0
    $directories = @(Get-ChildItem -LiteralPath $root -Directory -Recurse -Force -Filter '__pycache__' |
        Sort-Object { $_.FullName.Length } -Descending)
    foreach ($directory in $directories) {
        if (-not $directory.FullName.StartsWith($root.TrimEnd([IO.Path]::DirectorySeparatorChar) +
                [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
            $directory.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Unsafe packaged cache path.'
        }
        if (@(Get-ChildItem -LiteralPath $directory.FullName -Force).Count -eq 0) {
            # No recursive deletion: never discard nonempty caches or unknown data.
            Remove-Item -LiteralPath $directory.FullName -Force
            $removed++
        }
    }
    return $removed
}

function Assert-PackagedContentBoundary {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$App)
    $root = [IO.Path]::GetFullPath($App).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $bad = @(Get-ChildItem -LiteralPath $root -Recurse -Force | ForEach-Object {
        $relative = '/' + $_.FullName.Substring($root.Length + 1).Replace('\', '/')
        if ($_.PSIsContainer) { $relative += '/' }
        if ($relative -match '(?i)/(__pycache__|\.pytest_cache|\.git|\.work|conda-meta)/|\.(pyc|pyo|log|tmp|bak)$') {
            $relative
        }
    })
    if ($bad.Count -gt 0) {
        throw ('Forbidden staged package entries: ' + (($bad | Select-Object -First 3) -join '; '))
    }
}
