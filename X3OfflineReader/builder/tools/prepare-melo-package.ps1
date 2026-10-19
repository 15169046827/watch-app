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
