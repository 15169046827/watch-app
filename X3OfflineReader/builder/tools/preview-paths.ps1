function Get-PreviewPaths {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$BuildRoot,
        [string]$CandidateName
    )
    if ($CandidateName -and ($CandidateName -cnotmatch '^[a-z0-9][a-z0-9-]{0,47}$' -or
            $CandidateName -match '(^|-)(next|previous)$')) {
        throw 'CandidateName must contain 1-48 lowercase letters, digits or hyphens.'
    }
    $root = [IO.Path]::GetFullPath($BuildRoot)
    $suffix = if ($CandidateName) { "-$CandidateName" } else { '' }
    $directory = "windows-preview$suffix"
    $archiveName = "X3AudiobookBuilder-0.7.0$suffix-UNSIGNED-PREVIEW"
    $archive = Join-Path $root "$archiveName.zip"
    return @{
        outputRoot = Join-Path $root $directory
        stagingRoot = Join-Path $root "$directory-next"
        backupRoot = Join-Path $root "$directory-previous"
        archive = $archive
        archiveNext = Join-Path $root "$archiveName.next.zip"
        digestPath = "$archive.sha256"
        digestNext = "$archive.sha256.next"
        archiveBackup = "$archive.previous"
        digestBackup = "$archive.sha256.previous"
    }
}
