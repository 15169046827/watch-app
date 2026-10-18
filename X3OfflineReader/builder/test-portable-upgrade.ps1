#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$OldApp,
    [string]$NewApp,
    [switch]$RollbackOnly
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $temporaryBase ('tingdu-upgrade-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Test-PreviewRollback([string]$FailurePoint) {
    $caseRoot = Join-Path $testRoot $FailurePoint
    $outputRoot = Join-Path $caseRoot 'current'
    $stagingRoot = Join-Path $caseRoot 'next'
    $backupRoot = Join-Path $caseRoot 'previous'
    foreach ($directory in @($outputRoot, $stagingRoot)) {
        New-Item -ItemType Directory -Path (Join-Path $directory 'X3AudiobookBuilder') -Force | Out-Null
    }
    $oldMarker = Join-Path $outputRoot 'X3AudiobookBuilder/version.txt'
    [IO.File]::WriteAllText($oldMarker, 'old-public-fixture')
    [IO.File]::WriteAllText((Join-Path $stagingRoot 'X3AudiobookBuilder/version.txt'), 'new-public-fixture')
    [IO.File]::WriteAllText((Join-Path $stagingRoot 'X3AudiobookBuilder/UNSIGNED-PREVIEW.txt'), 'test-only')
    $archive = Join-Path $caseRoot 'preview.zip'
    $digestPath = $archive + '.sha256'
    $archiveNext = Join-Path $caseRoot 'preview.next.zip'
    $digestNext = $digestPath + '.next'
    $archiveBackup = $archive + '.previous'
    $digestBackup = $digestPath + '.previous'
    [IO.File]::WriteAllText($archive, 'old-public-archive')
    [IO.File]::WriteAllText($digestPath, 'old-public-checksum')
    $beforeArchive = (Get-FileHash -LiteralPath $archive).Hash
    $beforeDigest = (Get-FileHash -LiteralPath $digestPath).Hash
    $hadPrevious = $true
    $previousMoved = $false
    $parseErrors = $null
    $tokens = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $PSScriptRoot 'package-windows-preview.ps1'), [ref]$tokens, [ref]$parseErrors)
    Assert-True (-not $parseErrors) 'Preview script did not parse.'
    $transactions = @($ast.EndBlock.Statements | Where-Object {
        $_ -is [Management.Automation.Language.TryStatementAst] -and
        $_.Extent.Text.Contains('Unsigned preview ZIP creation failed.')
    })
    Assert-True ($transactions.Count -eq 1) 'Preview transaction could not be selected unambiguously.'
    $transaction = [ScriptBlock]::Create($transactions[0].Extent.Text)
    $failure = ''
    # Local command wrappers inject failures into the actual maintained transaction,
    # never into the real application tree or user work directory.
    & {
        function Get-Command {
            param([string]$Name, [string]$ErrorAction)
            if ($FailurePoint -eq 'archive-tool') { return $null }
            Microsoft.PowerShell.Core\Get-Command -Name $Name -ErrorAction Stop
        }
        function Move-Item {
            param([string]$LiteralPath, [string]$Destination)
            if ($FailurePoint -eq 'checksum-promotion' -and $LiteralPath -eq $digestNext) {
                throw 'Injected checksum promotion failure'
            }
            Microsoft.PowerShell.Management\Move-Item -LiteralPath $LiteralPath -Destination $Destination
        }
        try { & $transaction } catch { $script:transactionFailure = $_.Exception.Message }
    }
    $failure = $script:transactionFailure
    Remove-Variable transactionFailure -Scope Script -ErrorAction SilentlyContinue
    Assert-True (-not [string]::IsNullOrWhiteSpace($failure)) 'Injected failure did not occur.'
    $expectedFailure = if ($FailurePoint -eq 'archive-tool') { '*生成预览 ZIP 需要*' } else { '*Injected checksum promotion failure*' }
    Assert-True ($failure -like $expectedFailure) ('Unexpected failure instead of the intended injection: ' + $failure)
    Assert-True ([IO.File]::ReadAllText($oldMarker) -eq 'old-public-fixture') 'Old application was not restored.'
    Assert-True ((Get-FileHash -LiteralPath $archive).Hash -eq $beforeArchive) 'Old archive changed.'
    Assert-True ((Get-FileHash -LiteralPath $digestPath).Hash -eq $beforeDigest) 'Old checksum changed.'
    Assert-True (-not (Test-Path -LiteralPath $archiveNext)) 'Temporary archive remains.'
    Assert-True (-not (Test-Path -LiteralPath $digestNext)) 'Temporary checksum remains.'
    [pscustomobject]@{ case = $FailurePoint; passed = $true }
}

function Invoke-PublicBridge([string]$App, [string]$Work, [hashtable]$Request, [bool]$Pause) {
    $requestRoot = Join-Path $Work 'ui-requests'
    New-Item -ItemType Directory -Path $requestRoot -Force | Out-Null
    $control = Join-Path $requestRoot 'pause.stop'
    $Request.controlFile = $control
    $requestFile = Join-Path $requestRoot 'request.json'
    [IO.File]::WriteAllText($requestFile, ($Request | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = Join-Path $App 'X3BuilderCLI.exe'
    $start.ArgumentList.Add('bridge-build')
    $start.ArgumentList.Add($requestFile)
    $start.WorkingDirectory = $App
    $start.Environment['X3_BUILDER_WORK_ROOT'] = $Work
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = [Text.Encoding]::UTF8
    $process = [Diagnostics.Process]::Start($start)
    $errors = $process.StandardError.ReadToEndAsync()
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $events = [Collections.Generic.List[object]]::new()
    $line = $process.StandardOutput.ReadLineAsync()
    $pauseSent = $false
    try {
        while ($clock.Elapsed.TotalSeconds -lt 180) {
            if (-not $line.Wait(100)) { continue }
            $text = $line.Result
            if ($null -eq $text) { break }
            if (-not [string]::IsNullOrWhiteSpace($text)) {
                $event = $text | ConvertFrom-Json
                $events.Add($event)
                if ($Pause -and -not $pauseSent -and $event.type -eq 'progress' -and $event.percent -ge 15) {
                    [IO.File]::WriteAllText($control, 'pause')
                    $pauseSent = $true
                }
            }
            $line = $process.StandardOutput.ReadLineAsync()
        }
        Assert-True ($process.WaitForExit(5000)) 'Public fixture conversion did not finish in time.'
        Assert-True ($process.ExitCode -eq 0) ('Public bridge failed: ' + $errors.Result)
        $expected = if ($Pause) { 'paused' } else { 'complete' }
        Assert-True (@($events | Where-Object type -EQ $expected).Count -eq 1) ('Missing event: ' + $expected)
        return $events.ToArray()
    } finally {
        if (-not $process.HasExited) { $process.Kill($true); $process.WaitForExit() }
        $process.Dispose()
    }
}

function Test-PublicResume {
    foreach ($path in @($OldApp, $NewApp)) {
        Assert-True ([IO.Path]::IsPathFullyQualified($path)) 'Both app paths must be absolute.'
        Assert-True (Test-Path -LiteralPath (Join-Path $path 'X3BuilderCLI.exe') -PathType Leaf) 'CLI is missing.'
    }
    $work = Join-Path $testRoot 'public-work'
    $source = Join-Path $testRoot 'public-source.txt'
    $output = Join-Path $testRoot 'public-result.opbook'
    $content = [Text.StringBuilder]::new()
    foreach ($chapter in 1..16) {
        [void]$content.AppendLine("第${chapter}章 公开升级验证")
        foreach ($sentence in 1..5) {
            [void]$content.AppendLine("这是第${chapter}章第${sentence}段自行编写的公开文本，用于检查版本升级后缓存和断点是否保持完整。")
        }
    }
    [IO.File]::WriteAllText($source, $content.ToString(), [Text.UTF8Encoding]::new($false))
    $null = Invoke-PublicBridge $OldApp $work @{
        inputs = @($source); output = $output; title = '公开升级验证'; author = '公开样本'
        tts = 'tone'; voice = 'tone'; rate = 1; pauseMs = 0
    } $true
    $checkpoints = @(Get-ChildItem -LiteralPath (Join-Path $work 'checkpoints') -Filter '*.properties')
    Assert-True ($checkpoints.Count -eq 1) 'Old app did not retain one checkpoint.'
    $cache = @(Get-ChildItem -LiteralPath (Join-Path $work 'cache') -File | ForEach-Object {
        [pscustomobject]@{ path = $_.FullName; hash = (Get-FileHash -LiteralPath $_.FullName).Hash; time = $_.LastWriteTimeUtc.Ticks }
    })
    Assert-True ($cache.Count -gt 0) 'Pause occurred before reusable audio was cached.'
    $null = Invoke-PublicBridge $NewApp $work @{ resume = $true } $false
    foreach ($file in $cache) {
        Assert-True ((Get-FileHash -LiteralPath $file.path).Hash -eq $file.hash) 'Cached audio changed during upgrade.'
        Assert-True ((Get-Item -LiteralPath $file.path).LastWriteTimeUtc.Ticks -eq $file.time) 'Cached audio was regenerated.'
    }
    Assert-True (Test-Path -LiteralPath $output -PathType Leaf) 'Resumed output is missing.'
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $work 'checkpoints') -Filter '*.properties').Count -eq 0) 'Completed checkpoint remains.'
    $python = Join-Path $project '.tools/melo/python.exe'
    Assert-True (Test-Path -LiteralPath $python -PathType Leaf) 'Validator Python runtime is required.'
    & $python (Join-Path $project 'specification/tools/validate_fixtures.py') $output
    Assert-True ($LASTEXITCODE -eq 0) 'Resumed OPBOOK validation failed.'
    [pscustomobject]@{ case = 'old-to-new-resume'; preservedCacheFiles = $cache.Count; passed = $true }
}

try {
    Test-PreviewRollback 'archive-tool'
    Test-PreviewRollback 'checksum-promotion'
    if (-not $RollbackOnly) { Test-PublicResume }
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $boundary = $temporaryBase.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolved) -notmatch '^tingdu-upgrade-[0-9a-f]{32}$') {
        throw 'Unsafe test cleanup path'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction Stop
}
