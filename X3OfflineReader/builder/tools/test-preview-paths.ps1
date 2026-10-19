$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'preview-paths.ps1')
$root = Join-Path ([IO.Path]::GetTempPath()) 'preview-path-fixture'
$defaults = Get-PreviewPaths -BuildRoot $root
$candidate = Get-PreviewPaths -BuildRoot $root -CandidateName 'security-20261019'
if ($defaults.outputRoot -ne (Join-Path $root 'windows-preview') -or
    $defaults.archive -ne (Join-Path $root 'X3AudiobookBuilder-0.7.0-UNSIGNED-PREVIEW.zip') -or
    $defaults.archiveNext -ne (Join-Path $root 'X3AudiobookBuilder-0.7.0-UNSIGNED-PREVIEW.next.zip')) {
    throw 'Default preview paths changed.'
}
foreach ($key in $defaults.Keys) {
    if ($candidate[$key] -eq $defaults[$key] -or
        -not $candidate[$key].StartsWith($root + [IO.Path]::DirectorySeparatorChar) -or
        -not $candidate[$key].Contains('security-20261019')) {
        throw "Candidate path is not isolated: $key"
    }
}
foreach ($invalid in @('../escape', '..\escape', 'A', ' name', 'x/y', 'x:y', ('x' * 49),
        'next', 'previous', 'security-next', 'security-previous')) {
    $rejected = $false
    try { Get-PreviewPaths -BuildRoot $root -CandidateName $invalid | Out-Null }
    catch { $rejected = $true }
    if (-not $rejected) { throw "Unsafe candidate name accepted: $invalid" }
}
Write-Output 'Preview path fixtures passed: defaults, isolation and 11 invalid names.'
